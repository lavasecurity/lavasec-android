package com.lavasec.app.data

import com.lavasec.app.foundation.filter.BlocklistFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LocalBlocklistRuleCountsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = SeededSourceDescriptor("local-test", "https://example.com/list.txt", BlocklistFormat.HOSTS)

    @Test fun `missing bytes stay unknown and accepted parser counts exclude comments and rejected lines`() = runBlocking {
        val cache = SeededSourceCache(temporary.newFolder())
        val counts = LocalBlocklistRuleCounts(cache)
        assertNull(counts.count(source))
        cache.acquireExact(source, SeededSourceTransport { SeededSourceHttpResponse.ok("# comment\n0.0.0.0 a.example\n127.0.0.1 b.example\ninvalid content\n".toByteArray()) })
        assertEquals(2, counts.count(source))
        assertNull(counts.count(source.copy(sourceUrl = "https://example.com/changed.txt")))
    }

    @Test fun `refresh digest replaces cached count and corrupt bytes never reuse metadata`() = runBlocking {
        val cache = SeededSourceCache(temporary.newFolder())
        val counts = LocalBlocklistRuleCounts(cache)
        cache.acquireExact(source, SeededSourceTransport { SeededSourceHttpResponse.ok("0.0.0.0 a.example\n".toByteArray()) })
        assertEquals(1, counts.count(source))
        val accepted = cache.acquireExact(source, SeededSourceTransport { SeededSourceHttpResponse.ok("0.0.0.0 b.example\n0.0.0.0 c.example\n".toByteArray()) }, refreshSource = true)
        assertEquals(2, counts.count(source))
        accepted.file.writeText("0.0.0.0 forged.example\n")
        assertNull(counts.count(source))
    }

    @Test fun `a valid empty list reports zero rather than unknown`() = runBlocking {
        val cache = SeededSourceCache(temporary.newFolder())
        cache.acquireExact(source, SeededSourceTransport { SeededSourceHttpResponse.ok("# intentionally empty\n".toByteArray()) })
        assertEquals(0, LocalBlocklistRuleCounts(cache).count(source))
    }
}
