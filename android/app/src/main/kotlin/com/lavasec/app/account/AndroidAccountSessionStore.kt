package com.lavasec.app.account

import android.content.Context
import android.util.AtomicFile
import com.lavasec.app.platform.ChainedUpstreamKeyItemStore
import com.lavasec.app.security.AndroidKeystoreChainedKeyItemStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Device-bound ciphertext plus an atomic non-secret pointer, all outside Android backup. */
internal class AndroidAccountSessionStore(
    context: Context,
    private val items: ChainedUpstreamKeyItemStore = AndroidKeystoreChainedKeyItemStore(
        context, alias = "lavasec.account.session.v1", preferencesName = "account_session_ciphertext",
    ),
) : AccountSessionStore {
    init { require(!context.isDeviceProtectedStorage) }
    private val pointer = AtomicFile(File(context.noBackupFilesDir, "account-session-v1"))
    private val lock = Mutex()

    override suspend fun load(): AccountSession? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!pointer.baseFile.exists() && !File(pointer.baseFile.path + ".bak").exists()) return@withLock null
            val id = pointer.openRead().use { stream ->
                val bytes = stream.readBytesBounded(64)
                bytes.toString(Charsets.US_ASCII)
            }
            if (id.isEmpty()) return@withLock null
            if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) throw IOException("Invalid session pointer")
            val bytes = items.load(id) ?: return@withLock null // Restored ciphertext without this device's key.
            try { AccountSessionCodec.decode(bytes) } finally { bytes.fill(0) }
        }
    }

    override suspend fun save(session: AccountSession) = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = UUID.randomUUID().toString()
            val bytes = AccountSessionCodec.encode(session)
            try {
                if (!items.add(bytes, id)) throw IOException("Could not store account credentials")
            } finally { bytes.fill(0) }
            // If publication is uncertain, retain both encrypted generations. Removing the new
            // item could invalidate a pointer that actually committed before a readback failure.
            publish(id)
            runCatching { items.accounts().filter { it != id }.forEach { items.delete(it) } }
            Unit
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            // Publish a durable signed-out tombstone before deleting encrypted generations.
            publish("")
            runCatching { items.accounts().forEach { items.delete(it) } }
            Unit
        }
    }

    private fun publish(value: String) {
        val stream = pointer.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
            pointer.finishWrite(stream)
        } catch (failure: Exception) {
            pointer.failWrite(stream)
            throw failure
        }
        // AtomicFile logs some rename failures instead of throwing. Confirm which generation
        // readers will observe (including recovery of a surviving .bak) before sweeping keys.
        val committed = pointer.openRead().use { it.readBytesBounded(64).toString(Charsets.US_ASCII) }
        if (committed != value) throw IOException("Could not commit account session pointer")
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val buffer = ByteArray(limit + 1)
        var size = 0
        while (size < buffer.size) {
            val count = read(buffer, size, buffer.size - size)
            if (count < 0) break
            size += count
        }
        if (size > limit) throw IOException("Invalid session pointer")
        return buffer.copyOf(size)
    }
}
