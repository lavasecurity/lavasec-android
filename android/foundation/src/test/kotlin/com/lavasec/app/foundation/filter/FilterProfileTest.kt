package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.config.ProtectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterProfileTest {
    @Test
    fun `seeded profiles derive cumulative selections from catalog category and defaults`() {
        val catalog = FilterCatalogSnapshot(
            sources = listOf(
                CatalogFilterSource("security-default", FilterSourceCategory.SECURITY, defaultEnabled = true),
                CatalogFilterSource("security-optional", FilterSourceCategory.SECURITY, defaultEnabled = false),
                CatalogFilterSource("multi-default", FilterSourceCategory.MULTI_PURPOSE, defaultEnabled = true),
                CatalogFilterSource("ads-default", FilterSourceCategory.ADS_TRACKING, defaultEnabled = true),
                CatalogFilterSource("ads-optional", FilterSourceCategory.ADS_TRACKING, defaultEnabled = false),
                CatalogFilterSource("social-optional", FilterSourceCategory.SOCIAL, defaultEnabled = false),
            ),
        )
        val profiles = FilterProfile.seededDefaults(catalog)
        val core = profiles.getValue(ProtectionLevel.CORE)
        val balanced = profiles.getValue(ProtectionLevel.BALANCED)
        val extra = profiles.getValue(ProtectionLevel.EXTRA)

        assertEquals("filter-essential", core.id)
        assertEquals("filter-balanced", balanced.id)
        assertEquals("filter-comprehensive", extra.id)
        assertEquals(setOf("security-default"), core.enabledSourceIds)
        assertEquals(setOf("security-default", "multi-default", "ads-default"), balanced.enabledSourceIds)
        assertEquals(
            setOf("security-default", "multi-default", "ads-default", "ads-optional"),
            extra.enabledSourceIds,
        )
        assertTrue(catalog.guardrailSources.isEmpty())
    }

    @Test
    fun `profile source inputs and seeded profile map cannot be mutated through aliases`() {
        val sourceIds = linkedSetOf("source-a")
        val profile = FilterProfile("profile", "Profile", ProtectionLevel.CORE, sourceIds)
        sourceIds += "source-b"

        assertEquals(setOf("source-a"), profile.enabledSourceIds)
        assertMutationFails { (profile.enabledSourceIds as MutableSet<String>) += "source-c" }
        assertMutationFails {
            (FilterProfile.seededDefaults(FilterCatalogSnapshot(listOf(
                CatalogFilterSource("source-a", FilterSourceCategory.SECURITY, defaultEnabled = true),
            ))) as MutableMap<ProtectionLevel, FilterProfile>)[
                ProtectionLevel.CORE
            ] = profile
        }
    }

    @Test
    fun `catalog snapshot rejects duplicate ids and never accepts guardrail inputs`() {
        assertThrowsIllegalArgument {
            FilterCatalogSnapshot(
                listOf(
                    CatalogFilterSource("duplicate", FilterSourceCategory.SECURITY, true),
                    CatalogFilterSource("duplicate", FilterSourceCategory.ADS_TRACKING, false),
                ),
            )
        }
        assertTrue(FilterCatalogSnapshot(emptyList()).guardrailSources.isEmpty())
    }

    @Test
    fun `profile rejects blank identity name and source ids`() {
        assertThrowsIllegalArgument {
            FilterProfile(" ", "Core", ProtectionLevel.CORE, emptySet())
        }
        assertThrowsIllegalArgument {
            FilterProfile("profile", " ", ProtectionLevel.CORE, emptySet())
        }
        assertThrowsIllegalArgument {
            FilterProfile("profile", "Core", ProtectionLevel.CORE, setOf("source-a", " "))
        }
    }

    private fun assertMutationFails(mutation: () -> Unit) {
        try {
            mutation()
            assertFalse("collection unexpectedly accepted a mutation", true)
        } catch (_: UnsupportedOperationException) {
            assertTrue(true)
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            assertFalse("expected IllegalArgumentException", true)
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
