package com.lavasec.app.billing

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

/**
 * Device-only verified Play receipts, using the same Keystore item/pointer protocol as account
 * sessions. Tokens never enter RN, logs or backup payloads. An unreadable existing record is
 * uncertainty, never an empty inventory that could revoke a persisted plan.
 */
internal class AndroidPlayReceiptStore(
    context: Context,
    private val items: ChainedUpstreamKeyItemStore = AndroidKeystoreChainedKeyItemStore(
        context, alias = "lavasec.play.receipts.v1", preferencesName = "play_receipt_ciphertext",
    ),
) : PlayReceiptCache {
    init { require(!context.isDeviceProtectedStorage) }
    private val pointer = AtomicFile(File(context.noBackupFilesDir, "play-receipts-v1"))
    private val lock = Mutex()

    override suspend fun load(): List<PlayPurchase> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!pointer.baseFile.exists() && !File(pointer.baseFile.path + ".bak").exists()) return@withLock emptyList()
            val id = pointer.openRead().use { stream ->
                val bytes = stream.readBytesBounded(64)
                bytes.toString(Charsets.US_ASCII)
            }
            if (id.isEmpty()) throw IOException("Invalid purchase record pointer")
            if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) throw IOException("Invalid purchase record pointer")
            val bytes = items.load(id) ?: throw IOException("Purchase verification record is unavailable")
            try { PlayReceiptCodec.decode(bytes) } finally { bytes.fill(0) }
        }
    }

    override suspend fun save(receipts: List<PlayPurchase>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = UUID.randomUUID().toString()
            val bytes = PlayReceiptCodec.encode(receipts)
            try {
                if (!items.add(bytes, id)) throw IOException("Could not store purchase verification record")
            } finally { bytes.fill(0) }
            // If publication is uncertain, retain both encrypted generations. Removing the new
            // item could invalidate a pointer that actually committed before a readback failure.
            publish(id)
            runCatching { items.accounts().filter { it != id }.forEach { items.delete(it) } }
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
        if (committed != value) throw IOException("Could not commit purchase record pointer")
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val buffer = ByteArray(limit + 1)
        var size = 0
        while (size < buffer.size) {
            val count = read(buffer, size, buffer.size - size)
            if (count < 0) break
            size += count
        }
        if (size > limit) throw IOException("Invalid purchase record pointer")
        return buffer.copyOf(size)
    }
}
