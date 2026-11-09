package com.lavasec.app.billing

import android.content.Context
import android.util.AtomicFile
import com.lavasec.app.platform.ChainedPreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * The plan flag and chaining preference commit together, matching iOS persistPaidPlanFlag.
 * One process instance is shared across credential graph rotations. Both flags are device-local,
 * credential-only and excluded from Auto Backup. A confirmed lapse never deletes the upstream key
 * or requests a tunnel reload; the existing resident session ends at its natural boundary.
 */
internal class AndroidPlusPlanPersistence(
    context: Context,
    private val legacyChainedPreference: () -> Boolean = {
        context.getSharedPreferences("lavasec_chained_preferences", Context.MODE_PRIVATE)
            .getBoolean("chained_enabled", false)
    },
    private val localCapabilities: Boolean = false,
) : PlusPlanPersistence, ChainedPreferenceStore {
    private data class Record(val hasPlus: Boolean, val chained: Boolean, val dnsFallback: Boolean = false)
    private val base = File(context.noBackupFilesDir, "plus-plan-v1")
    private val file = AtomicFile(base)
    private val mutex = Mutex()
    private val enabled = MutableStateFlow(false)
    override val chainedEnabled = enabled.asStateFlow()
    private val fallback = MutableStateFlow(false)
    override val chainedDnsFallbackEnabled = fallback.asStateFlow()

    init { require(!context.isDeviceProtectedStorage) }

    override suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { read().also(::publish).hasPlus }
    }

    override suspend fun loadEnabled(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { read().also(::publish).chained }
    }

    override suspend fun save(hasPlus: Boolean): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!localCapabilities) { "Local capabilities cannot become paid entitlements" }
            val previous = read().also(::publish)
            write(previous.copy(hasPlus = hasPlus, chained = previous.chained && hasPlus))
        }
    }

    override suspend fun setChainedEnabled(enabled: Boolean): Boolean = setChainedEnabledGuarded(enabled, null)

    override suspend fun setChainedEnabledWhileCurrent(enabled: Boolean, isCurrent: () -> Boolean): Boolean =
        setChainedEnabledGuarded(enabled, isCurrent)

    private suspend fun setChainedEnabledGuarded(enabled: Boolean, isCurrent: (() -> Boolean)?): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val previous = read().also(::publish)
                // Recheck under the same lock as plan writes: a toggle racing a confirmed lapse
                // cannot re-enable chaining after its admission-time entitlement check.
                if (enabled && !previous.hasPlus && !localCapabilities) return@withLock false
                if (isCurrent?.invoke() == false) return@withLock false
                write(previous.copy(chained = enabled), isCurrent)
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    override suspend fun loadDnsFallbackEnabled(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { read().also(::publish).dnsFallback }
    }

    override suspend fun setChainedDnsFallbackEnabled(enabled: Boolean): Boolean = setChainedDnsFallbackGuarded(enabled, null)

    override suspend fun setChainedDnsFallbackEnabledWhileCurrent(enabled: Boolean, isCurrent: () -> Boolean): Boolean =
        setChainedDnsFallbackGuarded(enabled, isCurrent)

    private suspend fun setChainedDnsFallbackGuarded(enabled: Boolean, isCurrent: (() -> Boolean)?): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val previous = read().also(::publish)
                if (enabled && !previous.hasPlus && !localCapabilities) return@withLock false
                if (isCurrent?.invoke() == false) return@withLock false
                write(previous.copy(dnsFallback = enabled), isCurrent)
                true
            } catch (_: IOException) { false }
        }
    }

    /** Caller holds the Plus owner's admission before entering this short final-write scope. */
    internal suspend fun <T> withChainedDnsReading(
        block: suspend (ChainedDnsPreferenceReading) -> T,
    ): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val actual = read().also(::publish)
            block(ChainedDnsPreferenceReading(actual.hasPlus, actual.chained, actual.dnsFallback))
        }
    }

    private fun read(): Record {
        if (!base.exists() && !File(base.path + ".bak").exists()) {
            // Read-only migration. Once the new record commits, the old preference is ignored.
            // A corrupt new record must never fall back to stale legacy preferences.
            return Record(false, legacyChainedPreference())
        }
        return DataInputStream(file.openRead()).use { stream ->
            val bytes = ByteArray(5)
            stream.readFully(bytes)
            if (bytes[0] != 0x4c.toByte() || bytes[1] != 0x50.toByte() ||
                bytes[2] !in byteArrayOf(1, 2) || bytes.drop(3).any { it !in byteArrayOf(0, 1) }
            ) throw IOException("unreadable plan record")
            val dnsFallback = if (bytes[2] == 2.toByte()) stream.read() else 0
            if (dnsFallback !in 0..1 || stream.read() != -1) throw IOException("unreadable plan record")
            Record(bytes[3] == 1.toByte(), bytes[4] == 1.toByte(), dnsFallback == 1)
        }
    }

    private class RetiredPreferenceWrite : IOException("Native preference authorization retired")

    private fun write(next: Record, isCurrent: (() -> Boolean)? = null) {
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(0x4c, 0x50, 2, if (next.hasPlus) 1 else 0, if (next.chained) 1 else 0, if (next.dnsFallback) 1 else 0))
            stream.fd.sync()
            if (isCurrent?.invoke() == false) throw RetiredPreferenceWrite()
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        // AtomicFile logs some publication failures instead of throwing. Verify the record a
        // restarted reader sees before publishing state or allowing purchase acknowledgement.
        if (read() != next) throw IOException("plan publication did not commit")
        publish(next)
    }

    private fun publish(record: Record) {
        enabled.value = record.chained
        fallback.value = record.dnsFallback
    }
}

internal data class ChainedDnsPreferenceReading(val hasPlus: Boolean, val enabled: Boolean, val fallback: Boolean)
