package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.subscription.FeatureLimits
import org.junit.Assert.*
import org.junit.Test

class DomainCheckRuleActionTest {
    private fun profile(allowed: Set<String> = emptySet(), blocked: Set<String> = emptySet()) =
        FilterProfileDraft("active", "Active", null, emptySet(), ManualDomainRules(allowed, blocked))
    private fun apply(input: FilterProfileDraft, domain: String, action: DomainCheckRuleAction) =
        DomainCheckRuleEditor.apply(domain, action, input, FeatureLimits.FREE)

    @Test fun `blocking removes every covering exception as one review proposal`() {
        val initial = profile(setOf("example.com", "shop.example.com", "unrelated.example"))
        val result = apply(initial, "shop.example.com", DomainCheckRuleAction.BLOCKED) as ManualRuleEditResult.Applied
        assertSame(initial, result.previous)
        assertEquals(setOf("unrelated.example"), result.profile.manualRules.allowedDomains)
        assertEquals(setOf("shop.example.com"), result.profile.manualRules.blockedDomains)
        assertEquals(setOf("example.com", "shop.example.com", "unrelated.example"), initial.manualRules.allowedDomains)
    }

    @Test fun `removing an exception preserves existing parent blocks without using another slot`() {
        val initial = profile(setOf("shop.example.com"), setOf("example.com"))
        val removed = apply(initial, "shop.example.com", DomainCheckRuleAction.REMOVE_ALLOWED) as ManualRuleEditResult.Applied
        assertTrue(removed.profile.manualRules.allowedDomains.isEmpty())
        assertEquals(setOf("example.com"), removed.profile.manualRules.blockedDomains)
        val blocked = apply(initial, "shop.example.com", DomainCheckRuleAction.BLOCKED) as ManualRuleEditResult.Applied
        assertEquals(removed.profile.manualRules, blocked.profile.manualRules)
    }

    @Test fun `removing an exact block and allowing a domain leave unrelated rules intact`() {
        val initial = profile(blocked = setOf("example.com", "shop.example.com", "other.example"))
        val removed = apply(initial, "shop.example.com", DomainCheckRuleAction.REMOVE_BLOCKED) as ManualRuleEditResult.Applied
        assertEquals(setOf("example.com", "other.example"), removed.profile.manualRules.blockedDomains)
        val allowed = apply(initial, "shop.example.com", DomainCheckRuleAction.ALLOWED) as ManualRuleEditResult.Applied
        assertEquals(setOf("shop.example.com"), allowed.profile.manualRules.allowedDomains)
        assertEquals(setOf("example.com", "other.example"), allowed.profile.manualRules.blockedDomains)
    }

    @Test fun `capacity rejection leaves all covering exceptions and original rules intact`() {
        val initial = profile(setOf("example.com"), (1..FeatureLimits.FREE.maxBlockedDomains).map { "blocked$it.example" }.toSet())
        val rejected = apply(initial, "shop.example.com", DomainCheckRuleAction.BLOCKED) as ManualRuleEditResult.Rejected
        assertSame(initial, rejected.previous)
        assertEquals(ManualRuleRejection.BLOCKED_LIMIT_REACHED, rejected.reason)
        assertEquals(setOf("example.com"), initial.manualRules.allowedDomains)
    }
}
