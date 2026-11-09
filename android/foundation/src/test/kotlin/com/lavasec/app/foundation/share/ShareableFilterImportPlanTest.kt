package com.lavasec.app.foundation.share

import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.DomainRuleSet

import com.lavasec.app.foundation.subscription.FeatureLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareableFilterImportPlanTest {
    private fun configuration(
        lists: Set<String> = emptySet(),
        blocked: Set<String> = emptySet(),
        allowed: Set<String> = emptySet(),
        custom: List<ShareableCustomBlocklistSource> = emptyList(),
    ) = ShareableFilterConfiguration(
        enabledBlocklistIDs = lists,
        blockedDomains = blocked,
        allowedDomains = allowed,
        customBlocklists = custom,
    )

    private fun plusCapabilities(
        available: Set<String> = setOf("a", "b"),
        reserved: Set<String> = emptySet(),
        maxBlocked: Int = 1_000,
        maxAllowed: Int = 1_000,
        maxRules: Int = Int.MAX_VALUE,
        ruleCounts: Map<String, Int> = emptyMap(),
    ) = ShareableFilterImportCapabilities(
        availableCuratedBlocklistIDs = available,
        reservedBlocklistIDs = reserved,
        allowsCustomBlocklists = true,
        maxBlockedDomains = maxBlocked,
        maxAllowedDomains = maxAllowed,
        maxFilterRules = maxRules,
        blocklistRuleCounts = ruleCounts,
    )

    @Test
    fun `imported service-domain exceptions remain accepted while threat guardrails still refuse`() {
        val validator=ShareableAllowlistValidator(DomainRuleSet.from(listOf(DomainRule.parse("dangerous.example"))))
        for(domain in listOf("apple.com","accounts.google.com","child.google.com","api.lavasecurity.app")) {
            assertEquals(ShareableAllowlistValidationResult.Allowed(domain),validator.validate(domain))
        }
        assertEquals(ShareableAllowlistValidationResult.Rejected,validator.validate("dangerous.example"))
        assertEquals(ShareableAllowlistValidationResult.Rejected,validator.validate("child.dangerous.example"))
    }

    @Test
    fun `an unavailable curated list is dropped and reported, never silently imported`() {
        val shared = configuration(lists = setOf("a", "unknown"))

        val plan = shared.importPlan(plusCapabilities(available = setOf("a")))

        assertEquals(setOf("a"), plan.applied.enabledBlocklistIDs)
        assertEquals(1, plan.droppedCount(ShareableFilterImportPlan.DroppedEntry.Kind.UNAVAILABLE_BLOCKLIST))
        assertEquals("unknown", plan.dropped.single().label)
        assertTrue(plan.hasUnsupportedEntries)
    }

    @Test
    fun `custom blocklists require plus and are dropped on a free tier`() {
        val shared = configuration(
            lists = setOf("custom-1"),
            custom = listOf(
                ShareableCustomBlocklistSource("custom-1", "Mine", "https://lists.example/l.txt"),
            ),
        )
        val free = ShareableFilterImportCapabilities.from(
            limits = FeatureLimits.FREE,
            availableCuratedBlocklistIDs = emptySet(),
        )

        val plan = shared.importPlan(free)

        assertEquals(1, plan.droppedCount(ShareableFilterImportPlan.DroppedEntry.Kind.REQUIRES_UPGRADE))
        assertFalse(plan.applied.enabledBlocklistIDs.contains("custom-1"))
        assertTrue(plan.applied.customBlocklists.isEmpty())
    }

    @Test
    fun `a custom id that shadows a reserved list is refused as unsafe`() {
        val shared = configuration(
            custom = listOf(
                ShareableCustomBlocklistSource("blocklistproject-basic", "Shadow", "https://evil.example/l.txt"),
            ),
        )

        val plan = shared.importPlan(plusCapabilities(reserved = setOf("blocklistproject-basic")))

        assertEquals(1, plan.droppedCount(ShareableFilterImportPlan.DroppedEntry.Kind.UNSAFE_SOURCE))
        assertTrue(plan.applied.customBlocklists.isEmpty())
    }

    @Test
    fun `an unsafe source url is refused`() {
        val shared = configuration(
            custom = listOf(
                ShareableCustomBlocklistSource("custom-http", "Insecure", "http://lists.example/l.txt"),
                ShareableCustomBlocklistSource("custom-local", "Local", "https://127.0.0.1/l.txt"),
                ShareableCustomBlocklistSource("custom-cred", "Cred", "https://u:p@lists.example/l.txt"),
            ),
        )

        val plan = shared.importPlan(plusCapabilities())

        assertEquals(3, plan.droppedCount(ShareableFilterImportPlan.DroppedEntry.Kind.UNSAFE_SOURCE))
        assertTrue(plan.applied.customBlocklists.isEmpty())
    }

    @Test
    fun `the blocked-domain limit trims and reports every overflow`() {
        val shared = configuration(blocked = setOf("a.example", "b.example", "c.example"))

        val plan = shared.importPlan(plusCapabilities(maxBlocked = 2))

        assertEquals(2, plan.applied.blockedDomains.size)
        assertEquals(1, plan.droppedCount(ShareableFilterImportPlan.DroppedEntry.Kind.EXCEEDS_LIMIT))
    }

    @Test
    fun `invalid blocked and allowed domains are reported as invalid entries`() {
        val shared = configuration(
            blocked = setOf("not a domain", "ads.example"),
            allowed = setOf("also bad"),
        )

        val plan = shared.importPlan(plusCapabilities())

        assertEquals(2, plan.droppedCount(ShareableFilterImportPlan.DroppedEntry.Kind.INVALID_DOMAIN))
        assertEquals(setOf("ads.example"), plan.applied.blockedDomains)
    }

    @Test
    fun `an over-budget list is dropped as a tier restriction`() {
        val shared = configuration(lists = setOf("big"))

        val plan = shared.importPlan(
            plusCapabilities(
                available = setOf("big"),
                maxRules = 10,
                ruleCounts = mapOf("big" to 100),
            ),
        )

        assertTrue(plan.applied.enabledBlocklistIDs.isEmpty())
        assertEquals(1, plan.droppedCount(ShareableFilterImportPlan.DroppedEntry.Kind.EXCEEDS_RULE_BUDGET))
    }

    @Test
    fun `duplicate custom ids from a crafted payload collapse to the first definition`() {
        val shared = configuration(
            custom = listOf(
                ShareableCustomBlocklistSource("custom-1", "First", "https://a.example/l.txt"),
                ShareableCustomBlocklistSource("custom-1", "Second", "https://b.example/l.txt"),
            ),
        )

        val plan = shared.importPlan(plusCapabilities())

        assertEquals(1, plan.applied.customBlocklists.size)
        assertEquals("First", plan.applied.customBlocklists.single().displayName)
    }

    @Test
    fun `every input entry is either applied or explicitly reported`() {
        val shared = configuration(
            lists = setOf("a", "unknown", "custom-1"),
            blocked = setOf("ads.example", "not a domain"),
            custom = listOf(
                ShareableCustomBlocklistSource("custom-1", "Mine", "https://lists.example/l.txt"),
            ),
        )

        val plan = shared.importPlan(
            plusCapabilities(available = setOf("a"), maxBlocked = 1_000),
        )

        assertEquals(setOf("a", "custom-1"), plan.applied.enabledBlocklistIDs)
        assertEquals(setOf("ads.example"), plan.applied.blockedDomains)
        assertTrue(
            plan.dropped.any { it.kind == ShareableFilterImportPlan.DroppedEntry.Kind.UNAVAILABLE_BLOCKLIST },
        )
        assertTrue(
            plan.dropped.any { it.kind == ShareableFilterImportPlan.DroppedEntry.Kind.INVALID_DOMAIN },
        )
    }

    @Test
    fun `planning the applied subset again is a fixed point`() {
        val shared = configuration(
            lists = setOf("a", "unknown"),
            blocked = setOf("ads.example", "bad domain"),
        )
        val capabilities = plusCapabilities(available = setOf("a"), maxBlocked = 1_000)

        val plan = shared.importPlan(capabilities)
        val replanned = plan.applied.importPlan(capabilities)

        assertEquals(plan.applied, replanned.applied)
        assertFalse(replanned.hasUnsupportedEntries)
    }

    @Test
    fun `free tier limits map into import capabilities`() {
        val capabilities = ShareableFilterImportCapabilities.from(
            limits = FeatureLimits.FREE,
            availableCuratedBlocklistIDs = setOf("a"),
        )

        assertFalse(capabilities.allowsCustomBlocklists)
        assertEquals(FeatureLimits.FREE.maxBlockedDomains, capabilities.maxBlockedDomains)
        assertEquals(FeatureLimits.FREE.maxAllowedDomains, capabilities.maxAllowedDomains)
        assertEquals(FeatureLimits.FREE.maxFilterRules, capabilities.maxFilterRules)
    }
}
