package com.lavasec.app.product.privacy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One credential-generation exclusion boundary for all private local-log ownership changes. */
class CredentialPrivacyFence {
    private val mutex = Mutex()

    suspend fun <T> withClear(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> withExport(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> withAttachmentRead(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> withGraphRotation(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> withTelemetryWrite(block: suspend () -> T): T = mutex.withLock { block() }

    /** Backpressured runtime telemetry is dropped rather than buffered across a privacy mutation. */
    fun tryTelemetryWrite(block: () -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            block()
            true
        } finally {
            mutex.unlock()
        }
    }
}
