package com.lavasec.app.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Stable failure vocabulary for a bounded catalog download. The owner maps these to typed wire
 * reasons; a download never degrades into a silent success.
 */
internal enum class CatalogFetchFailureReason {
    HTTPS_REQUIRED,
    HTTP_STATUS,
    EMPTY_BODY,
    BODY_TOO_LARGE,
    TIMEOUT,
    NETWORK_IO,
}

internal class CatalogFetchException(
    val reason: CatalogFetchFailureReason,
    cause: Throwable? = null,
) : IllegalStateException(reason.name, cause)

/** Injectable transport boundary so the sync owner is testable without a live socket. */
internal fun interface CatalogManifestFetcher {
    suspend fun fetch(url: String): ByteArray
}

/**
 * Production catalog endpoints. Mirrors iOS `LavaSecAPI.catalogURLs`: the configured backend
 * origin first, then the canonical worker fallback — and only when the configured origin IS the
 * canonical production origin, so a test/staging build never silently reaches production.
 *
 * A blank or non-https [backendUrl] is not a usable origin; the client then uses the canonical
 * production origin (there is no other backend), exactly as iOS' compile-time constants do.
 */
internal object CatalogSyncEndpoints {
    const val PRODUCTION_ORIGIN = "https://api.lavasecurity.app"
    const val FALLBACK_ORIGIN = "https://lavasec-api.lavasec.workers.dev"

    fun catalogUrls(backendUrl: String): List<String> {
        val primary = originOf(backendUrl) ?: PRODUCTION_ORIGIN
        val urls = mutableListOf(catalogUrl(primary))
        if (primary == PRODUCTION_ORIGIN) urls += catalogUrl(FALLBACK_ORIGIN)
        return urls.distinct()
    }

    private fun catalogUrl(origin: String): String = "${origin.trimEnd('/')}/v1/catalog"

    private fun originOf(raw: String): String? {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        if (!scheme.equals("https", ignoreCase = true)) return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }
}

/**
 * One OkHttp GET with hard connect/read/whole-call timeouts and a decoded-byte ceiling.
 *
 * The ceiling is enforced twice: against the declared `Content-Length` and again while streaming
 * the body, so a chunked response cannot outrun it. Redirects are followed (bounded by OkHttp)
 * but an https downgrade is refused. The catalog manifest is metadata only — a few hundred KB in
 * practice — so eight mebibytes is a generous cap that still bounds a compromised host.
 */
internal class OkHttpCatalogManifestFetcher(
    private val calls: Call.Factory = OkHttpClient.Builder()
        .callTimeout(WHOLE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .cache(null)
        .build(),
    private val maximumBytes: Int = MAX_CATALOG_BYTES,
) : CatalogManifestFetcher {
    override suspend fun fetch(url: String): ByteArray = suspendCancellableCoroutine { continuation ->
        if (!isHttps(url)) {
            continuation.resumeWithException(CatalogFetchException(CatalogFetchFailureReason.HTTPS_REQUIRED))
            return@suspendCancellableCoroutine
        }
        val call = calls.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isActive) return
                val reason = when {
                    e is java.net.SocketTimeoutException -> CatalogFetchFailureReason.TIMEOUT
                    call.isCanceled() -> CatalogFetchFailureReason.TIMEOUT
                    else -> CatalogFetchFailureReason.NETWORK_IO
                }
                continuation.resumeWithException(CatalogFetchException(reason, e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw CatalogFetchException(CatalogFetchFailureReason.HTTP_STATUS)
                        if (!it.request.url.isHttps) throw CatalogFetchException(CatalogFetchFailureReason.HTTPS_REQUIRED)
                        val body = it.body ?: throw CatalogFetchException(CatalogFetchFailureReason.EMPTY_BODY)
                        if (body.contentLength() > maximumBytes) {
                            throw CatalogFetchException(CatalogFetchFailureReason.BODY_TOO_LARGE)
                        }
                        body.byteStream().use { stream -> readBounded(stream) }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private fun readBounded(stream: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > maximumBytes) {
                throw CatalogFetchException(CatalogFetchFailureReason.BODY_TOO_LARGE)
            }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        if (bytes.isEmpty()) throw CatalogFetchException(CatalogFetchFailureReason.EMPTY_BODY)
        return bytes
    }

    private fun isHttps(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }

    companion object {
        const val MAX_CATALOG_BYTES = 8 * 1024 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 30L
        const val WHOLE_CALL_TIMEOUT_SECONDS = 60L
    }
}
