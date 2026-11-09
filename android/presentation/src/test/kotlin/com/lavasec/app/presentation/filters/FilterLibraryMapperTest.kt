package com.lavasec.app.presentation.filters

import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.foundation.filter.FilterDraftReviewRejection

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterLibraryMapperTest {
    @Test
    fun `detail and review follow current limits without trimming a Plus draft after lapse`() {
        val library = FilterLibraryDraft(
            profiles = listOf(seededCore, balanced),
            activeProfileId = balanced.id,
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )
        val draft = balanced.withManualRules(ManualDomainRules(
            blockedDomains = (0 until 26).mapTo(linkedSetOf()) { "r$it.example" },
        ))
        val plus = FilterLibraryMapper.detail(library, catalog, 12, balanced, draft, false, FeatureLimits.PLUS)
        val free = FilterLibraryMapper.detail(library, catalog, 12, balanced, draft, false, FeatureLimits.FREE)
        assertEquals(1000, plus.maxBlockedDomains)
        assertEquals(25, free.maxBlockedDomains)
        assertEquals(plus.blockedDomains, free.blockedDomains)
        assertEquals(26, free.blockedDomains.size)
        assertFalse(free.frozen)
        val plusReview = FilterLibraryMapper.review(library, catalog, balanced, draft, false, FeatureLimits.PLUS)
        val freeReview = FilterLibraryMapper.review(library, catalog, balanced, draft, false, FeatureLimits.FREE)
        assertEquals(50, plusReview.maxFilters)
        assertEquals(3, freeReview.maxFilters)
        assertEquals(plusReview.diff, freeReview.diff)
        assertNull(plusReview.rejection)
        assertEquals(FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT, freeReview.rejection)
    }

    @Test
    fun `library maps saved filters with the in-effect flag and honest rule counts`() {
        val draft = FilterLibraryDraft(
            profiles = listOf(
                seededCore,
                balanced,
                userFilter,
            ),
            activeProfileId = "filter-balanced",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )
        val state = FilterLibraryMapper.library(
            draft = draft,
            catalog = catalog,
            activeBlockRuleCount = 128,
            maxFilters = 3,
        )

        assertEquals("filter-balanced", state.activeFilterId)
        assertEquals(listOf("filter-essential", "filter-balanced", "filter-user"), state.filters.map(FilterRowUi::id))
        assertEquals(null, state.filter("filter-essential")!!.activeRuleCount)
        assertEquals(128, state.filter("filter-balanced")!!.activeRuleCount)
        assertTrue(state.filter("filter-balanced")!!.active)
        assertFalse(state.filter("filter-essential")!!.frozen)
    }

    @Test
    fun `library marks an empty filter as blocking nothing`() {
        val draft = FilterLibraryDraft(
            profiles = listOf(seededCore, balanced, emptyFilter),
            activeProfileId = "filter-essential",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )
        val state = FilterLibraryMapper.library(draft, catalog, 12, 3)

        assertTrue(state.filter("filter-empty")!!.blocksNothing)
        assertFalse(state.filter("filter-balanced")!!.blocksNothing)
    }

    @Test
    fun `library freezes over-cap non-active filters by order and never the active`() {
        val draft = FilterLibraryDraft(
            profiles = listOf(seededCore, balanced, userFilter, emptyFilter),
            activeProfileId = "filter-balanced",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )
        val state = FilterLibraryMapper.library(draft, catalog, 128, 3)

        // Active takes one slot; the first cap-1 non-active filters by order stay usable,
        // the excess freezes: balanced (active) + essential + user fit the cap, empty freezes.
        assertFalse(state.filter("filter-balanced")!!.frozen)
        assertFalse(state.filter("filter-essential")!!.frozen)
        assertFalse(state.filter("filter-user")!!.frozen)
        assertTrue(state.filter("filter-empty")!!.frozen)
    }

    @Test
    fun `library rejects a negative rule count`() {
        val draft = FilterLibraryDraft(
            profiles = listOf(seededCore, balanced),
            activeProfileId = "filter-balanced",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            FilterLibraryMapper.library(draft, catalog, -1, 3)
        }
    }

    @Test
    fun `detail overlays the per-filter draft and reports the selected source state`() {
        val draftLibrary = FilterLibraryDraft(
            profiles = listOf(seededCore, balanced),
            activeProfileId = "filter-essential",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )
        val draft = balanced.withEnabledSourceIds(
            setOf("stevenblack-unified"),
        ).withManualRules(
            ManualDomainRules(blockedDomains = setOf("ads.example.com")),
        )
        val detail = FilterLibraryMapper.detail(
            library = draftLibrary,
            catalog = catalog,
            activeBlockRuleCount = 128,
            saved = balanced,
            draft = draft,
            busy = false,
        )

        assertTrue(detail.editing)
        assertTrue(detail.draftHasChanges)
        assertFalse(detail.inEffect)
        assertTrue(detail.sources.single { it.id == "stevenblack-unified" }.selected)
        assertFalse(detail.sources.single { it.id == "blocklistproject-basic" }.selected)
        assertEquals(listOf("ads.example.com"), detail.blockedDomains)
        assertEquals(null, detail.ruleCount)
        assertEquals(25, detail.maxBlockedDomains)
    }

    @Test
    fun `review groups the diff and keeps confirm disabled only on a rejection`() {
        val draftLibrary = FilterLibraryDraft(
            profiles = listOf(seededCore, balanced),
            activeProfileId = "filter-balanced",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )
        val draft = seededCore
            .withEnabledSourceIds(setOf("blocklistproject-basic", "stevenblack-unified"))
            .withManualRules(
                ManualDomainRules(
                    allowedDomains = setOf("trusted.example.com"),
                ),
            )
        val review = FilterLibraryMapper.review(
            library = draftLibrary,
            catalog = catalog,
            saved = seededCore,
            draft = draft,
            busy = false,
        )

        assertEquals(emptyList<String>(), review.diff.removedSources)
        assertEquals(listOf("StevenBlack Unified Hosts"), review.diff.addedSources)
        assertEquals(listOf("trusted.example.com"), review.diff.addedAllowedDomains)
        assertTrue(review.diff.weakensProtection)
        assertNull(review.rejection)
    }

    @Test
    fun `review rejects a no-change draft`() {
        val draftLibrary = FilterLibraryDraft(
            profiles = listOf(seededCore, balanced),
            activeProfileId = "filter-balanced",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )
        val review = FilterLibraryMapper.review(
            library = draftLibrary,
            catalog = catalog,
            saved = balanced,
            draft = balanced,
            busy = false,
        )

        assertEquals(
            com.lavasec.app.foundation.filter.FilterDraftReviewRejection.NO_CHANGES,
            review.rejection,
        )
    }

    @Test
    fun `a new empty detail has a meaningful first save without altering the saved library rows`() {
        val library = FilterLibraryDraft(listOf(seededCore), seededCore.id,
            MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK)
        val pending = FilterProfileDraft("new", "Untitled 1", null, emptySet())
        val detail = FilterLibraryMapper.detail(library, catalog, 123, pending, pending, false,
            FeatureLimits.FREE, creating = true)
        assertTrue(detail.editing)
        assertTrue(detail.draftHasChanges)
        assertTrue(detail.blocksNothing)
        assertFalse(detail.inEffect)
        assertFalse(detail.frozen)
        assertNull(detail.ruleCount)
        val review = FilterLibraryMapper.review(library, catalog, pending, pending, false,
            FeatureLimits.FREE, creating = true)
        assertNull(review.rejection)
        assertFalse(review.diff.hasChanges)
        assertEquals(listOf(seededCore.id), FilterLibraryMapper.library(library, catalog, 123, FeatureLimits.FREE.maxFilters).filters.map { it.id })
    }

    private companion object {
        val catalog = listOf(
            FilterSourceCatalogEntry(
                id = "blocklistproject-basic",
                name = "Block List Basic",
                category = FilterSourceCategory.SECURITY,
            ),
            FilterSourceCatalogEntry(
                id = "stevenblack-unified",
                name = "StevenBlack Unified Hosts",
                category = FilterSourceCategory.MULTI_PURPOSE,
            ),
        )

        val seededCore = FilterProfileDraft(
            id = "filter-essential",
            name = "Core",
            protectionLevel = ProtectionLevel.CORE,
            enabledSourceIds = setOf("blocklistproject-basic"),
        )

        val balanced = FilterProfileDraft(
            id = "filter-balanced",
            name = "Balanced",
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = setOf("blocklistproject-basic", "stevenblack-unified"),
        )

        val userFilter = FilterProfileDraft(
            id = "filter-user",
            name = "Gaming",
            protectionLevel = null,
            enabledSourceIds = setOf("stevenblack-unified"),
        )

        val emptyFilter = FilterProfileDraft(
            id = "filter-empty",
            name = "Empty",
            protectionLevel = null,
            enabledSourceIds = emptySet(),
        )
    }
}
