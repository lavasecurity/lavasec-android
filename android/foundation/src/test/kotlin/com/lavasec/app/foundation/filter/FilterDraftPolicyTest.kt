package com.lavasec.app.foundation.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterDraftPolicyTest {
    @Test
    fun `diff groups additions and removals with saved and draft order`() {
        val saved = FilterProfileDraft(
            id = "filter-balanced",
            name = "Balanced",
            protectionLevel = com.lavasec.app.foundation.config.ProtectionLevel.BALANCED,
            enabledSourceIds = setOf("basic", "unified"),
            manualRules = ManualDomainRules(
                blockedDomains = setOf("a.example.com", "b.example.com"),
                allowedDomains = setOf("trusted.example.com"),
            ),
        )
        val draft = saved
            .withEnabledSourceIds(setOf("unified", "ads"))
            .withManualRules(
                ManualDomainRules(
                    blockedDomains = setOf("b.example.com", "c.example.com"),
                    allowedDomains = emptySet(),
                ),
            )

        val diff = FilterDraftPolicy.diff(saved, draft)

        assertEquals(setOf("ads"), diff.addedSourceIds)
        assertEquals(setOf("basic"), diff.removedSourceIds)
        assertEquals(setOf("c.example.com"), diff.addedBlockedDomains)
        assertEquals(setOf("a.example.com"), diff.removedBlockedDomains)
        assertEquals(emptySet<String>(), diff.addedAllowedDomains)
        assertEquals(setOf("trusted.example.com"), diff.removedAllowedDomains)
        assertTrue(diff.hasChanges)
        assertTrue(diff.weakensProtection)
    }

    @Test
    fun `an empty diff is the no-changes rejection`() {
        val diff = FilterDraftPolicy.diff(profile, profile)
        assertFalse(diff.hasChanges)
        assertFalse(diff.weakensProtection)
        assertEquals(
            FilterDraftReviewRejection.NO_CHANGES,
            FilterDraftPolicy.reviewRejection(library, profile, profile, 3),
        )
    }

    @Test
    fun `a draft for a gone filter rejects the review`() {
        assertEquals(
            FilterDraftReviewRejection.FILTER_GONE,
            FilterDraftPolicy.reviewRejection(
                FilterLibraryDraft.seededDefaults(catalog),
                FilterProfileDraft("filter-user", "User", null, emptySet()),
                FilterProfileDraft("filter-user", "User", null, emptySet()),
                3,
            ),
        )
    }

    @Test
    fun `a frozen draft target rejects the review`() {
        val overCap = FilterLibraryDraft.seededDefaults(catalog).withProfiles(
            FilterLibraryDraft.seededDefaults(catalog).profiles + FilterProfileDraft(
                "filter-user",
                "User",
                null,
                emptySet(),
            ),
        )
        assertEquals(
            FilterDraftReviewRejection.FILTER_FROZEN,
            FilterDraftPolicy.reviewRejection(
                overCap,
                FilterProfileDraft("filter-user", "User", null, emptySet()),
                FilterProfileDraft("filter-user", "User", null, emptySet()),
                3,
            ),
        )
    }

    @Test
    fun `a valid changed draft passes the review gate`() {
        val draft = profile.withEnabledSourceIds(profile.enabledSourceIds + "ads")
        assertNull(FilterDraftPolicy.reviewRejection(library, draft, profile, 3))
    }

    @Test
    fun `a new empty filter is reviewable without inventing a change to the saved library`() {
        val saved = library.withActiveProfile(library.profiles.first().id).withProfiles(library.profiles.take(1))
        val identity = FilterProfileDraft("new", "Untitled 1", null, emptySet())
        assertFalse(FilterDraftPolicy.diff(identity, identity).hasChanges)
        assertNull(FilterDraftPolicy.creationRejection(saved, identity, identity,
            com.lavasec.app.foundation.subscription.FeatureLimits.FREE))
        assertEquals(FilterDraftReviewRejection.FILTER_GONE,
            FilterDraftPolicy.creationRejection(saved, identity, profile,
                com.lavasec.app.foundation.subscription.FeatureLimits.FREE))
        assertEquals(FilterDraftReviewRejection.DUPLICATE_NAME,
            FilterDraftPolicy.creationRejection(saved,
                FilterProfileDraft("new", saved.profiles.first().name, null, emptySet()), identity,
                com.lavasec.app.foundation.subscription.FeatureLimits.FREE))
        assertEquals(FilterDraftReviewRejection.FILTER_LIMIT_REACHED,
            FilterDraftPolicy.creationRejection(library, identity, identity,
                com.lavasec.app.foundation.subscription.FeatureLimits.FREE))
        val excessive = identity.withManualRules(ManualDomainRules(
            blockedDomains = (0 until 26).mapTo(linkedSetOf()) { "rule$it.example" },
        ))
        assertEquals(FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT,
            FilterDraftPolicy.creationRejection(saved, identity, excessive,
                com.lavasec.app.foundation.subscription.FeatureLimits.FREE))
    }

    @Test
    fun `FOSS reviews admit domains beyond Plus quota but Play keeps its review boundary`() {
        val foss = com.lavasec.app.foundation.subscription.FeatureLimits.FOSS
        val plus = com.lavasec.app.foundation.subscription.FeatureLimits.PLUS
        for (target in ManualRuleTarget.entries) {
            val domains = (0 until 1_001).mapTo(linkedSetOf()) { "domain-$it.example" }
            val rules = if (target == ManualRuleTarget.ALLOWED) ManualDomainRules(allowedDomains = domains)
                else ManualDomainRules(blockedDomains = domains)
            val draft = profile.withManualRules(rules)
            assertNull(FilterDraftPolicy.reviewRejection(library, draft, profile, foss.maxFilters, foss))
            assertEquals(if (target == ManualRuleTarget.ALLOWED) FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT
                else FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT,
                FilterDraftPolicy.reviewRejection(library, draft, profile, plus.maxFilters, plus))
        }
    }

    @Test
    fun `lapsed review accepts only same-profile subset reductions above current quotas`() {
        val allowed = (0 until 60).mapTo(linkedSetOf()) { "allow-$it.example" }
        val blocked = (0 until 60).mapTo(linkedSetOf()) { "block-$it.example" }
        val saved = profile.withManualRules(ManualDomainRules(allowed, blocked))
        val savedLibrary = library.withProfile(saved)
        val free = com.lavasec.app.foundation.subscription.FeatureLimits.FREE
        val reduced = saved.withManualRules(ManualDomainRules(allowed - "allow-0.example", blocked - "block-0.example"))
        assertNull(FilterDraftPolicy.reviewRejection(savedLibrary, reduced, saved, free.maxFilters, free))
        val oneShelfReduced = saved.withManualRules(ManualDomainRules(allowed, blocked - "block-0.example"))
        assertNull(FilterDraftPolicy.reviewRejection(savedLibrary, oneShelfReduced, saved, free.maxFilters, free))
        // A removal cannot conceal a replacement or an addition to the other shelf.
        val mixed = saved.withManualRules(ManualDomainRules(allowed - "allow-0.example", setOf("new-block.example")))
        assertEquals(FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT,
            FilterDraftPolicy.reviewRejection(savedLibrary, mixed, saved, free.maxFilters, free))
        val replacement = saved.withManualRules(ManualDomainRules(allowed - "allow-0.example" + "new-allow.example", emptySet()))
        assertEquals(FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT,
            FilterDraftPolicy.reviewRejection(savedLibrary, replacement, saved, free.maxFilters, free))
        val differentIdentity = FilterProfileDraft("new-id", "New", null, emptySet(), reduced.manualRules)
        assertEquals(FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT,
            FilterDraftPolicy.creationRejection(savedLibrary, differentIdentity, differentIdentity, free.copy(maxFilters = 4)))
    }

    private companion object {
        val catalog = FilterCatalogSnapshot(
            listOf(
                CatalogFilterSource("basic", FilterSourceCategory.SECURITY, true),
                CatalogFilterSource("unified", FilterSourceCategory.MULTI_PURPOSE, true),
                CatalogFilterSource("ads", FilterSourceCategory.ADS_TRACKING, false),
            ),
        )

        val library = FilterLibraryDraft.seededDefaults(catalog)

        val profile = FilterProfileDraft(
            id = "filter-balanced",
            name = "Balanced",
            protectionLevel = com.lavasec.app.foundation.config.ProtectionLevel.BALANCED,
            enabledSourceIds = setOf("basic"),
        )
    }
}
