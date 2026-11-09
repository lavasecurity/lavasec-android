package com.lavasec.app.data

import com.lavasec.app.foundation.filter.BlocklistParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Optional display metadata from accepted bytes; no download, compilation or configuration write. */
internal class LocalBlocklistRuleCounts(private val cache: SeededSourceCache) {
    private val counts = object : LinkedHashMap<String, Int>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 128
    }

    suspend fun count(source: SeededSourceDescriptor): Int? {
        val context = currentCoroutineContext()
        context.ensureActive()
        val accepted = cache.exactOrNull(source) ?: return null
        val key = "${accepted.acceptedSha256}:${source.format}"
        synchronized(counts) { counts[key] }?.let { return it }
        val summary = try {
            accepted.file.inputStream().use { input ->
                BlocklistParser().forEachRule(input, source.format, checkpoint = { context.ensureActive() }) { }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (_: Exception) { return null }
        if (summary.whollyRejected || summary.acceptedRuleCount > Int.MAX_VALUE) return null
        // A replaced/evicted accepted file cannot publish metadata for a different source revision.
        if (cache.exactOrNull(source) != accepted) return null
        return summary.acceptedRuleCount.toInt().also { synchronized(counts) { counts[key] = it } }
    }
}
