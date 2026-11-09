package com.lavasec.app.foundation.diagnostics

import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.DomainRuleSet
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.filter.FilterSnapshot
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthEvidence
import com.lavasec.app.foundation.health.ProtectionHealthSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticDecisionTest {
    private val blockedSiteSnapshot = FilterSnapshot(
        blockRules = DomainRuleSet.of(DomainRule.parse("linkedin.com")),
        allowRules = DomainRuleSet.of(DomainRule.parse("trusted.example.com")),
        nonAllowableThreatRules = DomainRuleSet.of(DomainRule.parse("danger.example.com")),
    )

    // ---- Affected-site decision (canonical BugReportAffectedSiteFilterDecision) ----

    @Test
    fun `a blocked affected site carries the canonical domain action and reason`() {
        val decision = AffectedSiteDiagnosticDecision.make(
            rawAffectedSite = "https://www.linkedin.com/feed/",
            filterDecisions = blockedSiteSnapshot,
        )

        val decided = decision as AffectedSiteDiagnosticDecision.Decided
        assertEquals("www.linkedin.com", decided.domain)
        assertEquals(FilterAction.BLOCK, decided.action)
        assertEquals(FilterDecisionReason.BLOCKLIST, decided.reason)
        assertEquals("blocklist", decided.category)
        assertEquals("affected_site_filter", decided.copyKey.id)
    }

    @Test
    fun `an allowlisted affected site reports the allowlist reason`() {
        val decided = AffectedSiteDiagnosticDecision.make(
            rawAffectedSite = "trusted.example.com",
            filterDecisions = blockedSiteSnapshot,
        ) as AffectedSiteDiagnosticDecision.Decided

        assertEquals(FilterAction.ALLOW, decided.action)
        assertEquals(FilterDecisionReason.LOCAL_ALLOWLIST, decided.reason)
        assertEquals("localAllowlist", decided.category)
    }

    @Test
    fun `a guardrail site stays blocked ahead of an allowlist entry`() {
        val snapshot = FilterSnapshot(
            blockRules = DomainRuleSet.EMPTY,
            allowRules = DomainRuleSet.of(DomainRule.parse("danger.example.com")),
            nonAllowableThreatRules = DomainRuleSet.of(DomainRule.parse("danger.example.com")),
        )

        val decided = AffectedSiteDiagnosticDecision.make("danger.example.com", snapshot)
            as AffectedSiteDiagnosticDecision.Decided

        assertEquals(FilterAction.BLOCK, decided.action)
        assertEquals(FilterDecisionReason.THREAT_GUARDRAIL, decided.reason)
    }

    @Test
    fun `an unfiltered affected site is a canonical default allow`() {
        val decided = AffectedSiteDiagnosticDecision.make("example.org", blockedSiteSnapshot)
            as AffectedSiteDiagnosticDecision.Decided

        assertEquals(FilterAction.ALLOW, decided.action)
        assertEquals(FilterDecisionReason.DEFAULT_ALLOW, decided.reason)
        assertEquals("defaultAllow", decided.category)
    }

    @Test
    fun `an absent affected site is Absent`() {
        assertEquals(
            AffectedSiteDiagnosticDecision.Absent,
            AffectedSiteDiagnosticDecision.make(rawAffectedSite = null, filterDecisions = blockedSiteSnapshot),
        )
        assertEquals(
            AffectedSiteDiagnosticDecision.Absent,
            AffectedSiteDiagnosticDecision.make(rawAffectedSite = "   ", filterDecisions = blockedSiteSnapshot),
        )
    }

    @Test
    fun `a malformed affected site is Malformed and carries no domain`() {
        for (raw in listOf("not a domain", "http://", "singlelabel", "999.999.999.999", "https://[::1]/")) {
            assertEquals(
                "expected Malformed for '$raw'",
                AffectedSiteDiagnosticDecision.Malformed,
                AffectedSiteDiagnosticDecision.make(raw, blockedSiteSnapshot),
            )
        }
    }

    @Test
    fun `a valid site with no rule snapshot is Unavailable never approximated`() {
        assertEquals(
            AffectedSiteDiagnosticDecision.Unavailable,
            AffectedSiteDiagnosticDecision.make("linkedin.com", filterDecisions = null),
        )
    }

    @Test
    fun `malformed negative incident counts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IncidentEvidence(selfReconnectCount = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncidentEvidence(failClosedServedQueryCount = -1)
        }
    }

    // ---- Incident decision (canonical BugReportIncidentSummary.hasContent) ----

    @Test
    fun `a healthy tunnel has no incident content`() {
        val decision = IncidentDiagnosticDecision.from(
            IncidentEvidence(
                selfReconnectCount = 0,
                hasLastFailureReason = false,
                consecutiveUpstreamFailureCount = 0,
                failClosedServedQueryCount = 0,
            ),
        )

        assertFalse(decision.hasContent)
        assertEquals("incident_summary", decision.copyKey.id)
    }

    @Test
    fun `measured incident evidence flips hasContent and is reported present`() {
        val evidence = IncidentEvidence(
            selfReconnectCount = 2,
            hasLastFailureReason = true,
            consecutiveUpstreamFailureCount = 0,
            failClosedServedQueryCount = 0,
        )

        val decision = IncidentDiagnosticDecision.from(evidence)

        assertTrue(decision.hasContent)
        assertTrue(IncidentEvidenceKey.SELF_RECONNECT in decision.presentKeys)
        assertTrue(IncidentEvidenceKey.LAST_FAILURE_REASON in decision.presentKeys)
        assertTrue(IncidentEvidenceKey.SELF_RECONNECT_GAP in decision.omittedKeys)
    }

    @Test
    fun `unsupported evidence is omitted and never flips hasContent`() {
        // The platform measures its own reconnects but nothing about iOS-only gap/ledger/focus
        // evidence. Those keys must be reported omitted, not defaulted to a fabricated value.
        val decision = IncidentDiagnosticDecision.from(IncidentEvidence(selfReconnectCount = 1))

        assertTrue(IncidentEvidenceKey.SELF_RECONNECT in decision.presentKeys)
        assertTrue(IncidentEvidenceKey.SELF_RECONNECT_GAP in decision.omittedKeys)
        assertTrue(IncidentEvidenceKey.FOCUS_FAILURE in decision.omittedKeys)
        assertTrue(IncidentEvidenceKey.RECENT_LEDGER_INCIDENT in decision.omittedKeys)
        assertFalse(
            "omitted keys must not contribute content",
            decision.omittedKeys.any { IncidentEvidence().contributesContent(it) },
        )
    }

    @Test
    fun `a fail closed window alone is incident content`() {
        val decision = IncidentDiagnosticDecision.from(IncidentEvidence(failClosedServedQueryCount = 42))

        assertTrue(decision.hasContent)
        assertEquals(listOf(IncidentEvidenceKey.FAIL_CLOSED_SERVED_QUERY), decision.presentKeys)
    }

    // ---- Combined decision, copy keys, and resolver/connection category ----

    @Test
    fun `resolver and connection evidence yields the canonical severity category`() {
        val cases = mapOf(
            ProtectionHealthEvidence(isConnected = false) to ProtectionHealthSeverity.HEALTHY,
            ProtectionHealthEvidence(isConnected = true) to ProtectionHealthSeverity.HEALTHY,
            ProtectionHealthEvidence(isConnected = true, networkAvailable = false) to
                ProtectionHealthSeverity.NETWORK_UNAVAILABLE,
            ProtectionHealthEvidence(isConnected = true, consecutiveResolverFailures = 3) to
                ProtectionHealthSeverity.NEEDS_RECONNECT,
            ProtectionHealthEvidence(isConnected = true, requiresReconnect = true) to
                ProtectionHealthSeverity.NEEDS_RECONNECT,
            ProtectionHealthEvidence(isConnected = true, activeFallback = ProtectionFallback.DEVICE_DNS) to
                ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK,
            ProtectionHealthEvidence(isConnected = true, activeFallback = ProtectionFallback.ENCRYPTED_DNS) to
                ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK,
            ProtectionHealthEvidence(
                isConnected = true,
                recentResponseLatenciesMillis = listOf(3_000L, 3_000L, 3_000L),
            ) to ProtectionHealthSeverity.DNS_SLOW,
        )

        for ((evidence, expected) in cases) {
            val decision = DiagnosticDecisionModel.evaluate(
                affectedSite = null,
                filterDecisions = null,
                resolverConnection = evidence,
            )
            assertEquals(expected, decision.resolverConnectionSeverity)
            assertTrue(DiagnosticCopyKey.NETWORK_RESOLVER in decision.copyKeys)
        }
    }

    @Test
    fun `missing resolver evidence omits the network resolver category`() {
        val decision = DiagnosticDecisionModel.evaluate(
            affectedSite = null,
            filterDecisions = null,
            resolverConnection = null,
        )

        assertNull(decision.resolverConnectionSeverity)
        assertFalse(DiagnosticCopyKey.NETWORK_RESOLVER in decision.copyKeys)
        assertTrue(decision.copyKeys.isEmpty())
        assertTrue(decision.canonicalCategories.isEmpty())
    }

    @Test
    fun `combined decision exposes canonical copy keys and categories`() {
        val decision = DiagnosticDecisionModel.evaluate(
            affectedSite = "https://www.linkedin.com/feed/",
            filterDecisions = blockedSiteSnapshot,
            incident = IncidentEvidence(selfReconnectCount = 1),
            resolverConnection = ProtectionHealthEvidence(
                isConnected = true,
                consecutiveResolverFailures = 3,
            ),
        )

        assertEquals(
            listOf(
                DiagnosticCopyKey.AFFECTED_SITE_FILTER,
                DiagnosticCopyKey.INCIDENT_SUMMARY,
                DiagnosticCopyKey.NETWORK_RESOLVER,
            ),
            decision.copyKeys,
        )
        assertEquals(listOf("blocklist", "NEEDS_RECONNECT"), decision.canonicalCategories)
    }

    @Test
    fun `copy keys pin the canonical preview ids`() {
        assertEquals(
            listOf("affected_site_filter", "incident_summary", "network_resolver"),
            DiagnosticCopyKey.entries.map(DiagnosticCopyKey::id),
        )
    }

    @Test
    fun `incident evidence keys pin the canonical wire dictionary`() {
        assertEquals(
            listOf(
                "self_reconnect_count",
                "last_failure_reason",
                "consecutive_upstream_failure_count",
                "consecutive_dns_smoke_probe_failure_count",
                "consecutive_rejected_smoke_response_count",
                "last_encrypted_fallback_success_at",
                "resolver_runtime_reset_count",
                "fail_closed_served_query_count",
                "last_self_reconnect_gap_started_at",
                "focus_last_switch",
                "focus_last_failure",
                "recent_incidents",
            ),
            IncidentEvidenceKey.entries.map(IncidentEvidenceKey::wireKey),
        )
    }

    @Test
    fun `a decoded invalid domain is never rewritten to a default allow`() {
        val impossible = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.INVALID_DOMAIN)
        val source = object : com.lavasec.app.foundation.filter.DomainFilterDecisionSource {
            override val blockRuleCount: Int get() = 0
            override val allowRuleCount: Int get() = 0
            override val guardrailRuleCount: Int get() = 0
            override fun decide(rawDomain: String): FilterDecision = impossible
            override fun decideNormalized(normalizedDomain: String): FilterDecision = impossible
        }

        val decided = AffectedSiteDiagnosticDecision.make("example.com", source)
            as AffectedSiteDiagnosticDecision.Decided

        assertEquals(FilterDecisionReason.INVALID_DOMAIN, decided.reason)
        assertEquals("invalidDomain", decided.category)
    }
}
