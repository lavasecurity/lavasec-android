package com.lavasec.app.reactnative

import android.util.AtomicFile
import com.lavasec.app.foundation.backup.PortableBackupFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Native-only ciphertext capture. Neither the token nor bytes belong in React snapshots. */
internal class PendingPortableBackupCapture(val bytes: ByteArray?, val token: String)

/** The publication may have committed; a failed diagnostic read cannot prove either outcome. */
internal class PendingPortableBackupSaveUnverifiedException : IllegalStateException("Pending portable backup save could not be verified")

/**
 * One bounded ORIGINAL encrypted import in credential-protected noBackupFilesDir. No password,
 * decrypted payload, SAF URI or adopted cloud custody is retained. AtomicFile keeps the preceding
 * pending import recoverable when a replacement write is interrupted.
 */
internal class AndroidPendingPortableBackupStore(
    directory: File,
    private val readAfterCommit: (AtomicFile) -> ByteArray = { it.openRead().use { input -> readPortableBackupBytes(input) } },
) {
    private val directory = directory.canonicalFile
    private val state = states.computeIfAbsent(this.directory.path) { SharedState(if (present()) "available" else "none") }
    private val lock get() = state
    private val file = AtomicFile(File(this.directory, FILE_NAME))
    private var currentStatus: String
        get() = state.status
        set(value) { state.status = value }

    fun status(): String = currentStatus

    fun capture(): PendingPortableBackupCapture? = synchronized(lock) {
        if (!present()) { currentStatus = "none"; return@synchronized null }
        try {
            val bytes = file.openRead().use { readPortableBackupBytes(it) }
            val token = digest(bytes)
            try {
                PortableBackupFile.decode(bytes)
                currentStatus = "available"
                PendingPortableBackupCapture(bytes, token)
            } catch (_: Exception) {
                bytes.fill(0)
                currentStatus = "unreadable"
                PendingPortableBackupCapture(null, token)
            }
        } catch (_: Exception) {
            currentStatus = "unreadable"
            PendingPortableBackupCapture(null, unreadableToken())
        }
    }

    fun save(bytes: ByteArray, isCurrent: () -> Boolean = { true }): Boolean = synchronized(lock) {
        // Validation precedes opening/replacing the destination, preserving an existing entry.
        try { PortableBackupFile.decode(bytes) } catch (_: Exception) { return@synchronized false }
        var stream: java.io.FileOutputStream? = null
        try {
            if (!isCurrent()) return@synchronized false
            check(directory.isDirectory || directory.mkdirs())
            stream = file.startWrite()
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            if (!isCurrent()) {
                file.failWrite(stream)
                stream = null
                return@synchronized false
            }
            file.finishWrite(stream)
            stream = null
            // AtomicFile may log a rename failure instead of throwing. Only an exact readback
            // proves publication; a failed read is uncertain rather than a false "not saved".
            val saved = try { readAfterCommit(file) } catch (_: Exception) {
                currentStatus = "unreadable"
                throw PendingPortableBackupSaveUnverifiedException()
            }
            try {
                val matches = saved.contentEquals(bytes)
                // A failed replacement may leave the preceding valid record in place. Keep it
                // resumable while refusing to report that the requested replacement was saved.
                val readable = matches || runCatching { PortableBackupFile.decode(saved) }.isSuccess
                currentStatus = if (readable) "available" else "unreadable"
                matches
            } finally { saved.fill(0) }
        } catch (unverified: PendingPortableBackupSaveUnverifiedException) {
            throw unverified
        } catch (_: Exception) {
            stream?.let { runCatching { file.failWrite(it) } }
            currentStatus = if (present()) "available" else "none"
            false
        }
    }

    /** Replacement consent applies to one exact record, including the absence of a record. */
    fun saveIfUnchanged(bytes: ByteArray, expectedToken: String?, isCurrent: () -> Boolean): Boolean = synchronized(lock) {
        val current = capture()
        try {
            if (current?.token != expectedToken) return@synchronized false
            save(bytes, isCurrent)
        } finally { current?.bytes?.fill(0) }
    }

    /** A late successful restore/discard cannot delete a replacement pending import. */
    fun clearIfMatches(token: String, isCurrent: () -> Boolean = { true }): Boolean = synchronized(lock) {
        val captured = capture() ?: return@synchronized true
        try {
            if (captured.token != token || !isCurrent()) return@synchronized false
            file.delete()
            // Some Android versions use .new rather than .bak for interrupted writes.
            File(directory, "$FILE_NAME.new").delete()
            val cleared = !present()
            currentStatus = if (cleared) "none" else "unreadable"
            cleared
        } finally { captured.bytes?.fill(0) }
    }

    private fun present(): Boolean = listOf(FILE_NAME, "$FILE_NAME.bak", "$FILE_NAME.new")
        .any { File(directory, it).exists() }

    private fun unreadableToken(): String = listOf(FILE_NAME, "$FILE_NAME.bak", "$FILE_NAME.new").joinToString("|") { name ->
        val entry = File(directory, name)
        "$name:${entry.exists()}:${entry.length()}:${entry.lastModified()}"
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    internal companion object {
        const val FILE_NAME = "pending.lava-backup"
        private class SharedState(@Volatile var status: String)
        private val states = ConcurrentHashMap<String, SharedState>()
    }
}

/** Rejects oversized streams before JSON, decryption or an unbounded allocation. */
internal fun readPortableBackupBytes(input: InputStream, checkpoint: () -> Unit = {}): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    try {
        while (true) {
            checkpoint()
            val count = input.read(buffer)
            if (count < 0) break
            require(count > 0) { "Portable backup stream made no progress" }
            require(output.size() + count <= PortableBackupFile.MAX_FILE_BYTES) { "Portable backup exceeds file limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    } finally { buffer.fill(0); output.reset() }
}
