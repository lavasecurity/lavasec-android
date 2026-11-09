package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterProfileDraftTest {
    @Test
    fun `Plus domain capacity survives a lapse and Free only refuses additions`() {
        val rules = ManualDomainRules(
            allowedDomains = (0 until 1_000).mapTo(linkedSetOf()) { "allow-$it.example" },
            blockedDomains = (0 until 1_000).mapTo(linkedSetOf()) { "block-$it.example" },
        )
        val full = FilterProfileDraft("custom", "Custom", null, emptySet(), rules)
        for (target in ManualRuleTarget.entries) {
            val removed = ManualRuleEditor.remove(
                if (target == ManualRuleTarget.ALLOWED) "allow-0.example" else "block-0.example", target, full,
            )
            assertTrue(ManualRuleEditor.add("new.example", target, removed, FeatureLimits.PLUS) is ManualRuleEditResult.Applied)
            assertTrue(ManualRuleEditor.add("new.example", target, removed, FeatureLimits.FREE) is ManualRuleEditResult.Rejected)
            assertTrue(ManualRuleEditor.add("new.example", target, full, FeatureLimits.PLUS) is ManualRuleEditResult.Rejected)
        }
        assertEquals(1_000, full.manualRules.allowedDomains.size)
        assertEquals(1_000, full.manualRules.blockedDomains.size)
        // The value model preserves FOSS data beyond a commercial tier's admission quota.
        assertEquals(1_001, ManualDomainRules(allowedDomains = rules.allowedDomains + "overflow.example").allowedDomains.size)
    }

    @Test
    fun `FOSS edits exceed Plus manual domain quotas while Play admission stays capped`() {
        val rules = ManualDomainRules(
            allowedDomains = (0 until 1_000).mapTo(linkedSetOf()) { "allow-$it.example" },
            blockedDomains = (0 until 1_000).mapTo(linkedSetOf()) { "block-$it.example" },
        )
        val profile = FilterProfileDraft("custom", "Custom", null, emptySet(), rules)
        for (target in ManualRuleTarget.entries) {
            val result = ManualRuleEditor.add("new.example", target, profile, FeatureLimits.FOSS)
                as ManualRuleEditResult.Applied
            assertEquals(1_001, if (target == ManualRuleTarget.ALLOWED)
                result.profile.manualRules.allowedDomains.size else result.profile.manualRules.blockedDomains.size)
            assertTrue(ManualRuleEditor.add("new.example", target, profile, FeatureLimits.PLUS) is ManualRuleEditResult.Rejected)
            assertTrue(ManualRuleEditor.add("new.example", target, profile, FeatureLimits.FREE) is ManualRuleEditResult.Rejected)
        }
        assertEquals(FilterCompiler.MAX_SOURCE_RULES, ManualDomainRules.MAX_STORED_ALLOWED_DOMAINS)
        assertEquals(FilterCompiler.MAX_SOURCE_RULES, ManualDomainRules.MAX_STORED_BLOCKED_DOMAINS)
    }

    @Test
    fun `cross-shelf additions cannot retain over-quota rules but a move fully within quotas is valid`() {
        for (limits in listOf(FeatureLimits.FREE, FeatureLimits.PLUS)) {
            for (target in ManualRuleTarget.entries) {
                val over = (0..limits.maxAllowedDomains).mapTo(linkedSetOf()) { "domain-$it.example" }
                val rules = if (target == ManualRuleTarget.BLOCKED) ManualDomainRules(allowedDomains = over)
                    else ManualDomainRules(blockedDomains = over)
                val profile = FilterProfileDraft("saved", "Saved", null, emptySet(), rules)
                val rejected = ManualRuleEditor.add("new.example", target, profile, limits) as ManualRuleEditResult.Rejected
                assertEquals(if (target == ManualRuleTarget.BLOCKED) ManualRuleRejection.ALLOWED_LIMIT_REACHED
                    else ManualRuleRejection.BLOCKED_LIMIT_REACHED, rejected.reason)
                // Moving one rule from a shelf exactly one above quota removes the last excess;
                // both resulting shelves meet the ordinary plan, so no grandfathering is needed.
                val validMove = ManualRuleEditor.add("domain-0.example", target, profile, limits) as ManualRuleEditResult.Applied
                assertTrue(validMove.profile.manualRules.allowedDomains.size <= limits.maxAllowedDomains)
                assertTrue(validMove.profile.manualRules.blockedDomains.size <= limits.maxBlockedDomains)
                val tooMany = if (target == ManualRuleTarget.BLOCKED) ManualDomainRules(allowedDomains = over + "extra.example")
                    else ManualDomainRules(blockedDomains = over + "extra.example")
                assertTrue(ManualRuleEditor.add("domain-0.example", target, profile.withManualRules(tooMany), limits)
                    is ManualRuleEditResult.Rejected)
            }
        }
    }

    @Test
    fun `manual domain edits normalize suffix rules and move from the opposite set`() {
        val initial = FilterProfileDraft(
            id = "filter-balanced",
            name = "Balanced",
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = setOf("source-a"),
            manualRules = ManualDomainRules(
                allowedDomains = setOf("move.example"),
                blockedDomains = setOf("allowed.example"),
            ),
        )

        val blocked = ManualRuleEditor.add(
            rawDomain = "  .MOVE.Example. ",
            target = ManualRuleTarget.BLOCKED,
            profile = initial,
        ) as ManualRuleEditResult.Applied
        assertEquals("move.example", blocked.normalizedDomain)
        assertEquals(setOf("allowed.example", "move.example"), blocked.profile.manualRules.blockedDomains)
        assertTrue(blocked.profile.manualRules.allowedDomains.isEmpty())

        val allowed = ManualRuleEditor.add(
            rawDomain = "Allowed.Example.",
            target = ManualRuleTarget.ALLOWED,
            profile = blocked.profile,
        ) as ManualRuleEditResult.Applied
        assertEquals(setOf("allowed.example"), allowed.profile.manualRules.allowedDomains)
        assertEquals(setOf("move.example"), allowed.profile.manualRules.blockedDomains)
        assertTrue(allowed.profile.manualRules.asAllowRules().contains("child.allowed.example"))
        assertTrue(allowed.profile.manualRules.asBlockRules().contains("child.move.example"))
    }

    @Test
    fun `manual domains enforce independent 25 entry limits without dropping opposite entry`() {
        val allowed = (0 until ManualDomainRules.MAX_ALLOWED_DOMAINS)
            .mapTo(linkedSetOf()) { "allowed-$it.example" }
        val blocked = (0 until ManualDomainRules.MAX_BLOCKED_DOMAINS)
            .mapTo(linkedSetOf()) { "blocked-$it.example" }
        allowed.remove("allowed-0.example")
        allowed += "move-to-blocked.example"
        blocked.remove("blocked-0.example")
        blocked += "move-to-allowed.example"
        val profile = FilterProfileDraft(
            id = "filter-balanced",
            name = "Balanced",
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = emptySet(),
            manualRules = ManualDomainRules(
                allowedDomains = allowed,
                blockedDomains = blocked,
            ),
        )

        val allowRejected = ManualRuleEditor.add(
            "move-to-allowed.example",
            ManualRuleTarget.ALLOWED,
            profile,
        ) as ManualRuleEditResult.Rejected
        assertEquals(ManualRuleRejection.ALLOWED_LIMIT_REACHED, allowRejected.reason)
        assertTrue(allowRejected.previous.manualRules.blockedDomains.contains("move-to-allowed.example"))

        val blockRejected = ManualRuleEditor.add(
            "move-to-blocked.example",
            ManualRuleTarget.BLOCKED,
            profile,
        ) as ManualRuleEditResult.Rejected
        assertEquals(ManualRuleRejection.BLOCKED_LIMIT_REACHED, blockRejected.reason)
        assertTrue(blockRejected.previous.manualRules.allowedDomains.contains("move-to-blocked.example"))
    }

    @Test
    fun `exactly 25 allowed and blocked entries are accepted and collections are immutable snapshots`() {
        var profile = FilterProfileDraft(
            id = "filter-core",
            name = "Core",
            protectionLevel = ProtectionLevel.CORE,
            enabledSourceIds = linkedSetOf("source-a"),
        )
        for (index in 0 until 25) {
            profile = (ManualRuleEditor.add(
                "allowed-$index.example",
                ManualRuleTarget.ALLOWED,
                profile,
            ) as ManualRuleEditResult.Applied).profile
            profile = (ManualRuleEditor.add(
                "blocked-$index.example",
                ManualRuleTarget.BLOCKED,
                profile,
            ) as ManualRuleEditResult.Applied).profile
        }

        assertEquals(25, profile.manualRules.allowedDomains.size)
        assertEquals(25, profile.manualRules.blockedDomains.size)
        assertMutationFails { (profile.enabledSourceIds as MutableSet<String>) += "source-b" }
        assertMutationFails { (profile.manualRules.allowedDomains as MutableSet<String>) += "extra.example" }
    }

    @Test
    fun `service domains and their children can be explicit user exceptions`() {
        val domains = listOf("apple.com", "icloud.com", "mzstatic.com", "itunes.apple.com", "apps.apple.com", "lavasecurity.com", "lavasecurity.app", "api.lavasecurity.app", "lavasec.app", "lavasec.example", "accounts.google.com", "google.com")
        val profile = FilterProfileDraft(id="filter-extra",name="Extra",protectionLevel=ProtectionLevel.EXTRA,enabledSourceIds=emptySet())
        for (domain in domains) {
            val allowed=ManualRuleEditor.add("child.$domain",ManualRuleTarget.ALLOWED,profile) as ManualRuleEditResult.Applied
            assertEquals(setOf("child.$domain"),allowed.profile.manualRules.allowedDomains)
            assertTrue(ManualRuleEditor.add(domain,ManualRuleTarget.BLOCKED,profile) is ManualRuleEditResult.Applied)
        }
    }

    @Test
    fun `service-domain allowed values normalize and survive the manual rule boundary`() {
        assertEquals(setOf("child.apple.com"),ManualDomainRules(allowedDomains=setOf("Child。APPLE.com")).allowedDomains)
        assertEquals(setOf("child.apple.com"),ManualDomainRules(blockedDomains=setOf("Child。APPLE.com")).blockedDomains)
    }

    @Test
    fun `IDNA and punycode aliases collide and move as one normalized manual rule`() {
        assertThrowsIllegalArgument {
            ManualDomainRules(
                allowedDomains = setOf("Bücher.Example"),
                blockedDomains = setOf("xn--bcher-kva.example"),
            )
        }

        val profile = FilterProfileDraft(
            id = "filter-balanced",
            name = "Balanced",
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = emptySet(),
            manualRules = ManualDomainRules(allowedDomains = setOf("Bücher.Example")),
        )
        val moved = ManualRuleEditor.add(
            rawDomain = "xn--bcher-kva.example",
            target = ManualRuleTarget.BLOCKED,
            profile = profile,
        ) as ManualRuleEditResult.Applied

        assertTrue(moved.profile.manualRules.allowedDomains.isEmpty())
        assertEquals(setOf("xn--bcher-kva.example"), moved.profile.manualRules.blockedDomains)
    }

    @Test
    fun `Unicode dot aliases collide move and normalize service-domain exceptions`() {
        assertThrowsIllegalArgument {
            ManualDomainRules(
                allowedDomains = setOf("move。example"),
                blockedDomains = setOf("MOVE.example"),
            )
        }

        val profile = FilterProfileDraft(
            id = "filter-balanced",
            name = "Balanced",
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = emptySet(),
            manualRules = ManualDomainRules(allowedDomains = setOf("move。example")),
        )
        val moved = ManualRuleEditor.add(
            rawDomain = "MOVE.example",
            target = ManualRuleTarget.BLOCKED,
            profile = profile,
        ) as ManualRuleEditResult.Applied
        assertTrue(moved.profile.manualRules.allowedDomains.isEmpty())
        assertEquals(setOf("move.example"), moved.profile.manualRules.blockedDomains)

        val protected = ManualRuleEditor.add(
            rawDomain = "child。APPLE.com",
            target = ManualRuleTarget.ALLOWED,
            profile = profile.withManualRules(ManualDomainRules.EMPTY),
        ) as ManualRuleEditResult.Applied
        assertEquals(setOf("child.apple.com"), protected.profile.manualRules.allowedDomains)
    }

    @Test
    fun `undo restores each shelf to configured membership and never leaves both`() {
        val configured = ManualDomainRules(
            allowedDomains = setOf("allowed-configured.example", "move.example"),
            blockedDomains = setOf("blocked-configured.example"),
        )
        val profile = FilterProfileDraft(
            id = "filter-balanced",
            name = "Balanced",
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = emptySet(),
            manualRules = ManualDomainRules(
                allowedDomains = setOf("added.example", "blocked-configured.example"),
                blockedDomains = setOf("move.example", "added-blocked.example"),
            ),
        )

        val afterAdded = ManualRuleEditor.undo("added.example", ManualRuleTarget.ALLOWED, profile, configured)
        assertFalse(afterAdded.manualRules.allowedDomains.contains("added.example"))
        assertFalse(afterAdded.manualRules.blockedDomains.contains("added.example"))

        val afterRemoved = ManualRuleEditor.undo("allowed-configured.example", ManualRuleTarget.ALLOWED, profile, configured)
        assertTrue(afterRemoved.manualRules.allowedDomains.contains("allowed-configured.example"))

        val afterMoved = ManualRuleEditor.undo("move.example", ManualRuleTarget.BLOCKED, profile, configured)
        assertTrue(afterMoved.manualRules.allowedDomains.contains("move.example"))
        assertFalse(afterMoved.manualRules.blockedDomains.contains("move.example"))

        val afterOppositeMove = ManualRuleEditor.undo("blocked-configured.example", ManualRuleTarget.ALLOWED, profile, configured)
        assertTrue(afterOppositeMove.manualRules.blockedDomains.contains("blocked-configured.example"))
        assertFalse(afterOppositeMove.manualRules.allowedDomains.contains("blocked-configured.example"))

        val afterBlockedAddition = ManualRuleEditor.undo("added-blocked.example", ManualRuleTarget.BLOCKED, profile, configured)
        assertFalse(afterBlockedAddition.manualRules.blockedDomains.contains("added-blocked.example"))
    }

    @Test
    fun `undo of an unparseable domain is a pure no-op and normalizes before restoring`() {
        val profile = FilterProfileDraft(
            id = "filter-balanced",
            name = "Balanced",
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = emptySet(),
            manualRules = ManualDomainRules(blockedDomains = setOf("kept.example")),
        )
        assertSame(
            profile,
            ManualRuleEditor.undo("not a domain", ManualRuleTarget.BLOCKED, profile, ManualDomainRules.EMPTY),
        )

        val restored = ManualRuleEditor.undo(
            rawDomain = "Bücher.Example",
            target = ManualRuleTarget.BLOCKED,
            profile = profile,
            configured = ManualDomainRules(blockedDomains = setOf("xn--bcher-kva.example")),
        )
        assertEquals(setOf("xn--bcher-kva.example", "kept.example"), restored.manualRules.blockedDomains)
    }

    @Test
    fun `filter library is exactly the three stable profiles and snapshots every collection`() {
        val catalog = FilterCatalogSnapshot(listOf(
            CatalogFilterSource("security", FilterSourceCategory.SECURITY, true),
            CatalogFilterSource("multi", FilterSourceCategory.MULTI_PURPOSE, true),
            CatalogFilterSource("ads", FilterSourceCategory.ADS_TRACKING, false),
        ))
        val library = FilterLibraryDraft.seededDefaults(catalog)

        assertEquals(
            listOf("filter-essential", "filter-balanced", "filter-comprehensive"),
            library.profiles.map(FilterProfileDraft::id),
        )
        assertEquals("filter-balanced", library.activeProfileId)
        assertEquals(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK, library.resolverPlan)
        assertMutationFails { (library.profiles as MutableList<FilterProfileDraft>).clear() }
        assertFalse(library.profiles.any { it.guardrailSourceIds.isNotEmpty() })
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
