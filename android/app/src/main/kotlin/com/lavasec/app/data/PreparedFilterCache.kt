package com.lavasec.app.data

import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Process/graph-local, consume-once scratch artifacts. Never a durable configuration authority. */
internal class PreparedFilterCache(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 2,
    private val maxBytes: Long = 16L * 1024 * 1024,
) : AutoCloseable {
    private data class Entry(val artifact: PreparedFilterArtifact, val expiresAt: Long, val retainedBytes: Long)
    private val entries = LinkedHashMap<String, Entry>()
    private var closed = false
    @Synchronized fun contains(key: String): Boolean { prune(); return !closed && entries.containsKey(key) }
    @Synchronized fun take(key: String): PreparedFilterArtifact? { prune(); return entries.remove(key)?.artifact }
    @Synchronized fun put(key: String, artifact: PreparedFilterArtifact, expiresAt: Long): Boolean {
        prune()
        val bytes = if (closed || expiresAt <= nowMillis()) null else retainedBytes(artifact)
        if (bytes == null) { artifact.close(); return false }
        entries.remove(key)?.artifact?.close()
        entries[key] = Entry(artifact, expiresAt, bytes)
        while (entries.size > maxEntries || exceedsByteBudget()) {
            entries.remove(entries.keys.first())?.artifact?.close()
        }
        return entries.containsKey(key)
    }
    @Synchronized fun clear() { entries.values.forEach { it.artifact.close() }; entries.clear() }
    @Synchronized override fun close() { closed = true; clear() }
    private fun prune() {
        val expired = entries.filterValues { it.expiresAt <= nowMillis() || !it.artifact.file.isFile }.keys.toList()
        expired.forEach { entries.remove(it)?.artifact?.close() }
    }

    /** The immutable compile directory is the ownership unit that artifact.close() deletes. */
    private fun retainedBytes(artifact: PreparedFilterArtifact): Long? {
        val directory = artifact.file.parentFile?.takeIf { it.name.startsWith("compile-") } ?: return null
        if (!artifact.file.isFile) return null
        return try {
            var total = 0L
            Files.walk(directory.toPath()).use { paths ->
                val iterator = paths.iterator()
                while (iterator.hasNext()) {
                    val attributes = Files.readAttributes(iterator.next(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) return null
                    if (attributes.isRegularFile) {
                        val bytes = attributes.size()
                        if (bytes > maxBytes - total) return null
                        total += bytes
                    }
                }
            }
            total
        } catch (_: Exception) { null }
    }

    private fun exceedsByteBudget(): Boolean {
        var remaining = maxBytes
        for (entry in entries.values) {
            if (entry.retainedBytes > remaining) return true
            remaining -= entry.retainedBytes
        }
        return false
    }
}
