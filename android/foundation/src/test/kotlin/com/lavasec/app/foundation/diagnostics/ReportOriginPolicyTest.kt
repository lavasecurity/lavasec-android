package com.lavasec.app.foundation.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportOriginPolicyTest {
    private val policy = ReportOriginPolicy
    private val uncertain = ReportDeliveryCompletion.Uncertain(ReportAttemptFailure.TRANSPORT)

    @Test
    fun `the canonical production and fallback origins are recognized`() {
        assertTrue(policy.isCanonicalProductionOrigin(ReportOriginPolicy.PRODUCTION_ORIGIN))
        assertTrue(policy.isCanonicalProductionOrigin("https://api.lavasecurity.app/"))
        assertFalse(policy.isCanonicalProductionOrigin(ReportOriginPolicy.FALLBACK_ORIGIN))
        assertTrue(policy.isCanonicalFallbackOrigin(ReportOriginPolicy.FALLBACK_ORIGIN))
        assertFalse(policy.isCanonicalFallbackOrigin(null))
    }

    @Test
    fun `an uncertain attempt on the canonical production origin may fall back`() {
        val decision = policy.decideFallback(
            primaryOrigin = ReportOriginPolicy.PRODUCTION_ORIGIN,
            configuredFallbackOrigin = ReportOriginPolicy.FALLBACK_ORIGIN,
            completion = uncertain,
        )

        assertEquals(ReportFallbackDecision.Allowed, decision)
    }

    @Test
    fun `a 429 never falls back`() {
        val decision = policy.decideFallback(
            primaryOrigin = ReportOriginPolicy.PRODUCTION_ORIGIN,
            configuredFallbackOrigin = ReportOriginPolicy.FALLBACK_ORIGIN,
            completion = ReportDeliveryCompletion.RateLimited,
        )

        assertEquals(
            ReportFallbackDecision.Forbidden(ReportFallbackReason.RATE_LIMITED),
            decision,
        )
    }

    @Test
    fun `a receipted report does not fall back`() {
        val decision = policy.decideFallback(
            primaryOrigin = ReportOriginPolicy.PRODUCTION_ORIGIN,
            configuredFallbackOrigin = ReportOriginPolicy.FALLBACK_ORIGIN,
            completion = ReportDeliveryCompletion.Receipt("any"),
        )

        assertEquals(
            ReportFallbackDecision.Forbidden(ReportFallbackReason.RECEIPT_RECEIVED),
            decision,
        )
    }

    @Test
    fun `a non-production override never falls over to production infrastructure`() {
        val decision = policy.decideFallback(
            primaryOrigin = "https://staging.example.test",
            configuredFallbackOrigin = ReportOriginPolicy.FALLBACK_ORIGIN,
            completion = uncertain,
        )

        assertEquals(
            ReportFallbackDecision.Forbidden(ReportFallbackReason.NOT_PRODUCTION_ORIGIN),
            decision,
        )
    }

    @Test
    fun `an absent fallback origin is forbidden`() {
        val decision = policy.decideFallback(
            primaryOrigin = ReportOriginPolicy.PRODUCTION_ORIGIN,
            configuredFallbackOrigin = null,
            completion = uncertain,
        )

        assertEquals(
            ReportFallbackDecision.Forbidden(ReportFallbackReason.NO_FALLBACK_CONFIGURED),
            decision,
        )
    }

    @Test
    fun `a non-canonical fallback origin is forbidden`() {
        val decision = policy.decideFallback(
            primaryOrigin = ReportOriginPolicy.PRODUCTION_ORIGIN,
            configuredFallbackOrigin = "https://attacker.example/",
            completion = uncertain,
        )

        assertEquals(
            ReportFallbackDecision.Forbidden(ReportFallbackReason.NOT_CANONICAL_FALLBACK),
            decision,
        )
    }
}
