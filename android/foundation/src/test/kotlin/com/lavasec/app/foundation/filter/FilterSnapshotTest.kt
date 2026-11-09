package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.parity.ParityFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterSnapshotTest {
    @Test
    fun `protection unavailable is representable as a fail closed decision`() {
        val reason = FilterDecisionReason.valueOf("PROTECTION_UNAVAILABLE")
        val decision = FilterDecision(
            action = FilterAction.BLOCK,
            reason = reason,
        )

        assertEquals(FilterDecisionReason.PROTECTION_UNAVAILABLE, reason)
        assertEquals(FilterAction.BLOCK, decision.action)
        assertEquals(FilterDecisionReason.PROTECTION_UNAVAILABLE, decision.reason)
    }

    @Test
    fun `shared fixtures pin invalid guardrail allow block default precedence`() {
        val snapshot = FilterSnapshot(
            blockRules = DomainRuleSet.of(DomainRule.parse("ads.example.com")),
            allowRules = DomainRuleSet.of(
                DomainRule.parse("trusted.ads.example.com"),
                DomainRule.parse("danger.example.com"),
            ),
            nonAllowableThreatRules = DomainRuleSet.of(DomainRule.parse("danger.example.com")),
        )

        for (fixture in ParityFixtures.objects("filter-precedence-cases.json")) {
            val decision = snapshot.decide(fixture.getValue("domain")!!)
            assertEquals(fixture["domain"], fixture["action"], decision.action.name)
            assertEquals(fixture["domain"], fixture["reason"], decision.reason.name)
        }
    }

    @Test
    fun `normalized decision path avoids renormalization but keeps rule ordering`() {
        val snapshot = FilterSnapshot(
            blockRules = DomainRuleSet.of(DomainRule.parse("ads.example.com")),
            allowRules = DomainRuleSet.of(DomainRule.parse("trusted.ads.example.com")),
        )

        assertEquals(FilterDecisionReason.BLOCKLIST, snapshot.decideNormalized("cdn.ads.example.com").reason)
        assertEquals(FilterDecisionReason.LOCAL_ALLOWLIST, snapshot.decideNormalized("trusted.ads.example.com").reason)
        assertEquals(FilterDecision.DEFAULT_ALLOW, snapshot.decideNormalized("apple.com"))
    }
}
