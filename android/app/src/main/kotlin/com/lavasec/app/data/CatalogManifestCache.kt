package com.lavasec.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal enum class CatalogCacheWriteResult {
    /** The bytes were durably replaced. */
    WRITTEN,

    /** A concurrent writer already advanced the cache; this write was fenced out. */
    CONFLICT,

    /** The previous artifact could not be safely replaced; the caller must keep what it has. */
    STORAGE_FAILED,
}

internal data class CatalogCachedManifest(
    val bytes: ByteArray,
    val verifiedAtEpochSeconds: Long,
)

/**
 * Durable last-known-good catalog store. One `catalog/latest.json`-style file under [root],
 * atomically replaced, carrying the epoch seconds at which the payload was network-verified so
 * freshness is a property of the content, not of the filesystem's mtime (which Android and the
 * test host do not guarantee monotonically).
 *
 * [write] is a compare-and-swap against the exact bytes expected by the caller: a refresh that
 * read an older artifact and lost a race to a newer one must not roll the cache back. A corrupt
 * or oversized file reads as absent, so a partially-written cache can never become state.
 */
internal class CatalogManifestCache(
    private val root: File,
    private val maximumBytes: Int = OkHttpCatalogManifestFetcher.MAX_CATALOG_BYTES,
    private val afterDurableWrite: () -> Unit = {},
) {
    private val latestFile: File get() = File(root, "latest.json")

    fun read(): CatalogCachedManifest? {
        val file = latestFile
        if (!file.isFile || file.length() !in 1L..(maximumBytes + HEADER_BYTES).toLong()) return null
        return try {
            DataInputStream(ByteArrayInputStream(file.readBytes())).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) return null
                if (input.readInt() != SCHEMA) return null
                val verifiedAt = input.readLong()
                if (verifiedAt < 0L) return null
                val length = input.readInt()
                if (length !in 1..maximumBytes) return null
                val payload = ByteArray(length)
                input.readFully(payload)
                if (input.read() != -1) return null
                CatalogCachedManifest(payload, verifiedAt)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Replaces the cache when its current payload is exactly [expectedCurrentSha256] (`null` means
     * "no cache exists"). Any other current state — a newer writer or an unreadable file — fences
     * this write.
     */
    fun write(
        bytes: ByteArray,
        verifiedAtEpochSeconds: Long,
        expectedCurrentSha256: String?,
    ): CatalogCacheWriteResult {
        if (bytes.isEmpty() || bytes.size > maximumBytes || verifiedAtEpochSeconds < 0L) {
            return CatalogCacheWriteResult.STORAGE_FAILED
        }
        val current = read()
        val currentSha = current?.let { catalogSha256Hex(it.bytes) }
        val expectedMatches = if (expectedCurrentSha256 == null) {
            current == null
        } else {
            currentSha != null && currentSha == expectedCurrentSha256
        }
        if (!expectedMatches) {
            return if (current == null) CatalogCacheWriteResult.STORAGE_FAILED
            else CatalogCacheWriteResult.CONFLICT
        }
        val temporary = File(root, ".catalog-${UUID.randomUUID()}.tmp")
        return try {
            if (!root.isDirectory && !root.mkdirs()) return CatalogCacheWriteResult.STORAGE_FAILED
            FileOutputStream(temporary).use { output ->
                output.write(encode(bytes, verifiedAtEpochSeconds))
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                latestFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            afterDurableWrite()
            CatalogCacheWriteResult.WRITTEN
        } catch (_: Exception) {
            CatalogCacheWriteResult.STORAGE_FAILED
        } finally {
            temporary.delete()
        }
    }

    /**
     * Removes the persisted last-known-good. Used to undo an install-failed refresh that had no
     * prior cache, so the next resolve falls back to the bundled anchor instead of an
     * uninstalled remote selection. Returns true when no file remains.
     */
    fun delete(): Boolean {
        val file = latestFile
        return try {
            !file.exists() || file.delete()
        } catch (_: Exception) {
            false
        }
    }

    private fun encode(bytes: ByteArray, verifiedAtEpochSeconds: Long): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(MAGIC)
                output.writeInt(SCHEMA)
                output.writeLong(verifiedAtEpochSeconds)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
            buffer.toByteArray()
        }

    private companion object {
        val MAGIC = "LAVACAT1".encodeToByteArray()
        const val SCHEMA = 1
        const val HEADER_BYTES = 8 + 4 + 8 + 4
    }
}

internal fun catalogSha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
