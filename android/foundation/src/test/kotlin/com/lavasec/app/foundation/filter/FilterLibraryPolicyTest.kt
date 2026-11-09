package com.lavasec.app.foundation.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterLibraryPolicyTest {
    @Test
    fun `a library within the cap freezes nothing`() {
        val library = FilterLibraryDraft.seededDefaults(catalog)

        assertEquals(emptySet<String>(), FilterLibraryPolicy.frozenProfileIds(library, 3))
        assertFalse(FilterLibraryPolicy.isFrozen(library, "filter-essential", 3))
        assertFalse(FilterLibraryPolicy.isFrozen(library, "filter-balanced", 3))
        assertFalse(FilterLibraryPolicy.isFrozen(library, "filter-comprehensive", 3))
    }

    @Test
    fun `an over-cap library freezes the excess non-active filters by order and never the active`() {
        val library = FilterLibraryDraft.seededDefaults(catalog).withProfiles(
            FilterLibraryDraft.seededDefaults(catalog).profiles + listOf(
                FilterProfileDraft("filter-a", "A", null, emptySet()),
                FilterProfileDraft("filter-b", "B", null, emptySet()),
            ),
        )

        val frozen = FilterLibraryPolicy.frozenProfileIds(library, 3)
        // Non-active order: essential, comprehensive, a, b. Active (balanced) takes one
        // slot; the first cap-1 non-active filters by order stay usable (essential,
        // comprehensive). The excess freezes: a + b.
        assertEquals(setOf("filter-a", "filter-b"), frozen)
        assertFalse(FilterLibraryPolicy.isFrozen(library, "filter-balanced", 3))
        assertFalse(FilterLibraryPolicy.isFrozen(library, "filter-essential", 3))
        assertFalse(FilterLibraryPolicy.isFrozen(library, "filter-comprehensive", 3))
        assertTrue(FilterLibraryPolicy.isFrozen(library, "filter-a", 3))
        assertTrue(FilterLibraryPolicy.isFrozen(library, "filter-b", 3))
    }

    @Test
    fun `the free tier limits pin the iOS free tier`() {
        // iOS FeatureLimits.free (SubscriptionPolicy.swift): 3 filters, 500k rules.
        assertEquals(3, FilterLibraryPolicy.MAX_FILTERS)
        assertEquals(500_000, FilterLibraryPolicy.MAX_FILTER_RULES)
    }

    private companion object {
        val catalog = FilterCatalogSnapshot(
            listOf(
                CatalogFilterSource("blocklistproject-basic", FilterSourceCategory.SECURITY, true),
                CatalogFilterSource("stevenblack-unified", FilterSourceCategory.MULTI_PURPOSE, true),
            ),
        )
    }
}
