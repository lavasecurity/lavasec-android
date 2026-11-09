package com.lavasec.app.backend

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The Android substitute for Apple's App Attest transport boundary. No background UI. */
internal class NativePlayIntegrity(context: Context, private val cloudProjectNumber: Long) : PlayIntegrityTokenPort {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val mutex = Mutex()
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    override suspend fun token(requestHash: String): String = mutex.withLock {
        if (cloudProjectNumber <= 0L) throw IOException("Android app verification is not configured")
        require(requestHash.matches(Regex("[A-Za-z0-9_-]{43}")))
        try {
            withTimeout(20_000) {
                val current = provider ?: manager.prepareIntegrityToken(
                    StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber).build(),
                ).awaitIntegrity().also { provider = it }
                current.request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash).build()).awaitIntegrity().token()
            }
        } catch (_: TimeoutCancellationException) {
            provider = null
            throw IOException("Android app verification is unavailable")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Includes expired provider tokens. The next explicit attempt prepares a fresh one.
            provider = null
            throw IOException("Android app verification is unavailable")
        }
    }
}

private suspend fun <T> Task<T>.awaitIntegrity(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(IOException("Integrity unavailable")) }
    addOnCanceledListener { continuation.cancel() }
}
