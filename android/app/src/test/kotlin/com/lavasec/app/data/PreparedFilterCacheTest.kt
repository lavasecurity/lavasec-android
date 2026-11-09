package com.lavasec.app.data

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PreparedFilterCacheTest {
    private suspend fun compile(root: java.io.File) = FilterCompiler(root).compile(FilterCompileRequest(
        ArtifactConfigurationIdentity(1, "a".repeat(64)), emptyList(), "catalog", "b".repeat(64), 100,
        manualBlockRules = listOf(DomainRule.parse("blocked.example")),
    ))
    @Test fun `matching capability transfers exactly once and keeps compiler receipt`() = runTest {
        val root = Files.createTempDirectory("prepared-cache").toFile()
        try { PreparedFilterCache(nowMillis = { 100L }).use { cache ->
            val artifact = compile(root); val receipt = artifact.compilationReceipt
            assertTrue(cache.put("inputs:budget", artifact, 200L))
            assertNull(cache.take("other-inputs:budget")); assertNull(cache.take("inputs:other-budget"))
            val taken = cache.take("inputs:budget")!!
            assertSame(receipt, taken.compilationReceipt); assertTrue(taken.file.isFile)
            assertNull(cache.take("inputs:budget")); taken.close(); assertFalse(taken.file.exists())
        } } finally { root.deleteRecursively() }
    }
    @Test fun `expiry eviction and retired graph delete their scratch files`() = runTest {
        val root = Files.createTempDirectory("prepared-cache").toFile(); var now = 100L
        try { val cache = PreparedFilterCache(nowMillis = { now }, maxEntries = 1)
            val first = compile(root); assertTrue(cache.put("first", first, 200L))
            val second = compile(root); assertTrue(cache.put("second", second, 200L))
            assertFalse(first.file.exists()); assertTrue(second.file.exists())
            now = 200L; assertFalse(cache.contains("second")); assertFalse(second.file.exists())
            cache.close(); val late = compile(root)
            assertFalse(cache.put("late", late, 300L)); assertFalse(late.file.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun `oversize artifact is discarded without growing the cache`() = runTest {
        val root = Files.createTempDirectory("prepared-cache").toFile()
        try { PreparedFilterCache(nowMillis = { 0L }, maxBytes = 1L).use { cache ->
            val artifact = compile(root)
            assertFalse(cache.put("large", artifact, 100L)); assertFalse(artifact.file.exists())
        } } finally { root.deleteRecursively() }
    }
    @Test fun `byte limit includes the real compiler tables retained beside the artifact`() = runTest {
        val root = Files.createTempDirectory("prepared-owned-bytes").toFile()
        try {
            val artifact = compile(root)
            val owned = artifact.file.parentFile!!
            val retained = owned.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            assertTrue("The fixture must include real retained compiler tables", retained > artifact.file.length())
            PreparedFilterCache(nowMillis = { 0L }, maxBytes = artifact.file.length()).use { cache ->
                assertFalse("An artifact-only budget cannot admit its larger owned directory", cache.put("owned", artifact, 100L))
                assertFalse(owned.exists())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `aggregate byte eviction charges both owned compilation directories`() = runTest {
        val root = Files.createTempDirectory("prepared-total-bytes").toFile()
        try {
            val first = compile(root); val firstOwned = first.file.parentFile!!
            val second = compile(root); val secondOwned = second.file.parentFile!!
            val firstBytes = firstOwned.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val secondBytes = secondOwned.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val budget = firstBytes + second.file.length()
            assertTrue(firstBytes <= budget && secondBytes <= budget)
            assertTrue(firstBytes + secondBytes > budget)
            PreparedFilterCache(nowMillis = { 0L }, maxBytes = budget).use { cache ->
                assertTrue(cache.put("first", first, 100L))
                assertTrue(cache.put("second", second, 100L))
                assertFalse("The older owned directory must be evicted to respect the aggregate bound", firstOwned.exists())
                assertFalse(cache.contains("first")); assertTrue(cache.contains("second"))
                assertTrue(second.file.isFile)
            }
        } finally { root.deleteRecursively() }
    }
}
