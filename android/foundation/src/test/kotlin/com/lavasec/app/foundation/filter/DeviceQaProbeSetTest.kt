package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.domain.DomainValidationError
import com.lavasec.app.foundation.domain.DomainValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceQaProbeSetTest {
    @Test fun `ordinary immutable library operations preserve the global device-only overlay`() {
        val ordinary = FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList()))
        val qa = ordinary.withQaProbeSet(DeviceQaProbeSet.HOSTED)
        val changes = listOf(qa.withProfile(qa.profiles.first().withManualRules(ManualDomainRules(blockedDomains = setOf("ordinary.example")))),
            qa.withProfiles(qa.profiles), qa.withActiveProfile(qa.profiles.first().id),
            qa.withResolverPlan(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY),
            qa.withResolverPlan(qa.resolverTarget))
        changes.forEach { assertEquals(DeviceQaProbeSet.HOSTED, it.qaProbeSet) }
        assertNotEquals(ordinary, qa)
        assertEquals(ordinary, qa.withQaProbeSet(null))
    }

    @Test fun `hosted identities and normalized custom suffix match the frozen probes`() {
        assertEquals("https://lavasecurity.app/qa/", DeviceQaProbeSet.HOSTED_PAGE_URL)
        assertEquals(listOf("allowed.qa-probe.lavasecurity.app", "blocked.qa-probe.lavasecurity.app",
            "exception.qa-probe.lavasecurity.app", "guardrail.qa-probe.lavasecurity.app"), DeviceQaProbeSet.HOSTED.allDomains)
        val custom = DeviceQaProbeSet.fromSuffix(" .192-168-1-20.SSLIP.io... ")
        assertEquals(listOf("allowed.192-168-1-20.sslip.io", "blocked.192-168-1-20.sslip.io",
            "exception.192-168-1-20.sslip.io", "guardrail.192-168-1-20.sslip.io"), custom.allDomains)
        assertEquals(custom, DeviceQaProbeSet.fromSuffix("192-168-1-20.sslip.io"))
        assertEquals(custom.hashCode(), DeviceQaProbeSet.fromSuffix("192-168-1-20.sslip.io.").hashCode())
        assertNotEquals(custom, DeviceQaProbeSet.HOSTED)
        assertEquals("allowed.xn--bcher-kva.example", DeviceQaProbeSet.fromSuffix("BÜCHER.example").allowedDomain)
    }

    @Test fun `suffix and completed hosts are independently validated without retaining mutable input`() {
        for (invalid in listOf("localhost", "127.0.0.1", "::1", "bad..example", "-bad.example", "https://example.com", " ")) {
            try { DeviceQaProbeSet.fromSuffix(invalid); fail("Expected invalid suffix rejection") }
            catch (_: DomainValidationException) { }
        }
        val label = "a".repeat(63)
        val validSuffixTooLongAfterPrefix = listOf(label, label, label, "a".repeat(52)).joinToString(".")
        try { DeviceQaProbeSet.fromSuffix(validSuffixTooLongAfterPrefix); fail("Completed probe must be bounded") }
        catch (error: DomainValidationException) { assertEquals(DomainValidationError.TOO_LONG, error.reason) }
        try { (DeviceQaProbeSet.HOSTED.allDomains as MutableList<String>).clear(); fail("Probe identities must be immutable") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun `exact probe overlay exercises actual threat allow block precedence and does not grant subdomains`() {
        val probes = DeviceQaProbeSet.HOSTED
        val ordinary = FilterSnapshot(DomainRuleSet.of(DomainRule.parse("ordinary-block.example")),
            DomainRuleSet.of(DomainRule.parse("ordinary-allow.example"),
                DomainRule.parse(probes.guardrailDomain, matchesSubdomains = false)))
        val snapshot = probes.applyingTo(ordinary)
        assertEquals(FilterDecision.DEFAULT_ALLOW, snapshot.decide(probes.allowedDomain))
        assertEquals(FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST), snapshot.decide(probes.blockedDomain))
        assertEquals(FilterDecision(FilterAction.ALLOW, FilterDecisionReason.LOCAL_ALLOWLIST), snapshot.decide(probes.exceptionDomain))
        assertEquals(FilterDecision(FilterAction.BLOCK, FilterDecisionReason.THREAT_GUARDRAIL), snapshot.decide(probes.guardrailDomain))
        assertEquals(FilterDecisionReason.BLOCKLIST, snapshot.decide("ordinary-block.example").reason)
        assertEquals(FilterDecisionReason.LOCAL_ALLOWLIST, snapshot.decide("ordinary-allow.example").reason)
        // Ordinary suffix allowances remain broad; only the QA overlay is exact.
        assertEquals(FilterDecisionReason.LOCAL_ALLOWLIST, snapshot.decide("child.ordinary-allow.example").reason)
        for (domain in probes.allDomains) assertEquals(FilterDecision.DEFAULT_ALLOW, snapshot.decide("child.$domain"))
        assertEquals(1, snapshot.guardrailRuleCount)
        assertTrue((probes.blockRules + probes.allowRules + probes.guardrailRules).all { !it.matchesSubdomains })
        assertFalse(probes.allowRules.any { it.domain == probes.allowedDomain })
        assertEquals(0, ordinary.guardrailRuleCount)
    }

    @Test fun `positive control preserves ordinary blocking and reapplying exact probes is idempotent`() {
        val probes = DeviceQaProbeSet.HOSTED
        val ordinary = FilterSnapshot(DomainRuleSet.of(DomainRule.parse(probes.allowedDomain)))
        val first = probes.applyingTo(ordinary)
        val again = probes.applyingTo(first)
        assertEquals(FilterDecisionReason.BLOCKLIST, first.decide(probes.allowedDomain).reason)
        assertEquals(first.blockRuleCount, again.blockRuleCount)
        assertEquals(first.allowRuleCount, again.allowRuleCount)
        assertEquals(first.guardrailRuleCount, again.guardrailRuleCount)
    }
}
