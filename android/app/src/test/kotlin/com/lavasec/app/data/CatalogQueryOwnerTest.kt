package com.lavasec.app.data

import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.FilterSourceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit coverage for the shared blocklist picker's catalog owner. The owner is pure (no
 * Android/org.json), so it runs on the aarch64 work host and pins that `catalog.query` returns the
 * real catalog sections instead of an empty or fabricated picker.
 */
class CatalogQueryOwnerTest {
    private fun catalog() = BundledBlocklistCatalog(
        schemaVersion = 1,
        categories = listOf("security", "piracy"),
        sources = listOf(
            CatalogSourceMetadata(
                id = "sec-1", name = "Threat A", category = FilterSourceCategory.SECURITY,
                format = CatalogSourceFormat.HOSTS, blocklistFormat = BlocklistFormat.HOSTS,
                sourceUrl = "https://example.test/a.txt", defaultEnabled = true,
                licenseName = "MIT", entryCount = 120,
            ),
            CatalogSourceMetadata(
                id = "sec-2", name = "Threat B", category = FilterSourceCategory.SECURITY,
                format = CatalogSourceFormat.HOSTS, blocklistFormat = BlocklistFormat.HOSTS,
                sourceUrl = "https://example.test/b.txt", defaultEnabled = false,
                licenseName = "GPL-3.0",
            ),
            CatalogSourceMetadata(
                id = "pir-1", name = "Torrent", category = FilterSourceCategory.PIRACY,
                format = CatalogSourceFormat.HOSTS, blocklistFormat = BlocklistFormat.HOSTS,
                sourceUrl = "https://example.test/c.txt", defaultEnabled = false,
            ),
        ),
        revision = "r", sha256 = "s", assetText = "{}",
        categoryLabels = mapOf(
            "security" to "Security & Threat Intel",
            "piracy" to "Piracy & Torrent",
        ),
    )

    @Test fun `projects real catalog sections with licenses and bounded rows`() {
        val result = CatalogQueryOwner(catalog()).query(listOf("sec-1", "missing"), budget = 500_000)
        assertEquals(2, result.sections.size)
        val security = result.sections[0]
        assertEquals("Security & Threat Intel", security.title)
        assertFalse(security.isCustom)
        assertEquals(listOf("sec-1", "sec-2"), security.sources.map { it.id })
        assertEquals("Threat A", security.sources[0].name)
        assertEquals("MIT", security.sources[0].licenseName)
        assertEquals("https://example.test/a.txt", security.sources[0].sourceUrl)
        assertEquals("120 rules", security.sources[0].metadata)
        assertNull(security.sources[1].metadata)
        assertEquals("Piracy & Torrent", result.sections[1].title)

        // Android has no per-source rule compile: the bar is honestly indeterminate, not fabricated.
        assertEquals(0, result.count)
        assertEquals(500_000, result.budget)
        assertEquals(0, result.pending)
        assertTrue(result.indeterminate)
        assertFalse(result.exceeded)
        assertEquals("1 of 3 lists selected", result.summary)
    }

    @Test fun `falls back to a taxonomy label when the anchor has no label`() {
        val unlabeled = catalog().copy(categoryLabels = emptyMap())
        val result = CatalogQueryOwner(unlabeled).query(emptyList(), budget = 1)
        assertEquals("Security & Threat Intel", result.sections[0].title)
        assertEquals("Piracy & Torrent", result.sections[1].title)
    }

    @Test fun `an empty catalog projects an empty but typed result`() {
        val empty = BundledBlocklistCatalog(1, emptyList(), emptyList(), "", "", "")
        val result = CatalogQueryOwner(empty).query(listOf("x"), budget = 0)
        assertTrue(result.sections.isEmpty())
        assertEquals(0, result.count)
        assertEquals(0, result.budget)
        assertTrue(result.indeterminate)
    }

    @Test fun `custom sources append a Your Lists section and count as known`() {
        val custom = com.lavasec.app.foundation.filter.CustomBlocklistSource(
            id = "custom-a",
            displayName = "My hosts",
            sourceUrl = "https://mine.example/hosts.txt",
            parseFormat = BlocklistFormat.HOSTS,
        )
        val result = CatalogQueryOwner(catalog()).query(
            ids = listOf("sec-1", "custom-a"),
            budget = 500_000,
            customSources = listOf(custom),
        )
        assertEquals(3, result.sections.size)
        val yourLists = result.sections.last()
        assertTrue(yourLists.isCustom)
        assertEquals("Your Lists", yourLists.title)
        assertEquals(listOf("custom-a"), yourLists.sources.map { it.id })
        assertEquals("My hosts", yourLists.sources[0].name)
        assertEquals("Custom List", yourLists.sources[0].licenseName)
        assertEquals("https://mine.example/hosts.txt", yourLists.sources[0].sourceUrl)
        assertEquals("2 of 4 lists selected", result.summary)
    }
}
