package com.lavasec.app.backend

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ServiceHttpFailure(val status: Int) : IOException("Service request failed ($status)")

/** No redirects, cookies, cache or error-body propagation; cancellation closes the live socket. */
internal class BoundedServiceHttp(
    private val calls: Call.Factory = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).cache(null).build(),
) {
    suspend fun execute(request: Request, maximumBytes: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        require(request.url.isHttps && maximumBytes in 1..1024 * 1024)
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Service unavailable"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw ServiceHttpFailure(it.code)
                        val body = it.body ?: return@use ByteArray(0)
                        if (body.contentLength() > maximumBytes) throw IOException("Service response exceeds limit")
                        body.byteStream().use { stream ->
                            val result = ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (result.size() + count > maximumBytes) throw IOException("Service response exceeds limit")
                                result.write(buffer, 0, count)
                            }
                            result.toByteArray()
                        }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }
}
