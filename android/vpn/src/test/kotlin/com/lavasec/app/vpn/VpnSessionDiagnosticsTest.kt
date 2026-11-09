package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.*
import com.lavasec.app.foundation.health.*
import org.junit.Assert.*
import org.junit.Test

class VpnSessionDiagnosticsTest {
    @Test fun `failed fallback after primary backoff is one real upstream failure`() {
        val evidence = ResolverQueryHealthEvidence.primaryFailed(1, ResolverFailureReason.BACKED_OFF).copy(tierObservations = listOf(
            ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.NOT_ATTEMPTED, failure = ResolverFailureReason.BACKED_OFF),
            ResolverTierDiagnosticObservation(2, ResolverKind.MULLVAD_DOH, ResolverTierDiagnosticOutcome.FAILED, failure = ResolverFailureReason.TIMEOUT)))
        val result = VpnSessionDiagnostics().query(evidence, 1000)
        assertEquals(1L, result.upstreamFailures)
        assertEquals(1000L, result.lastFailureAtMillis)
        assertEquals(ResolverFailureReason.TIMEOUT, result.lastFailure)
        assertEquals(1L, result.timeouts)
        assertEquals(0L, result.tiers[1]!!.attempts)
        assertEquals(1L, result.tiers[2]!!.failures)
    }

    @Test fun `probe transport and timeout evidence stays in probe tiers without changing user counters`() {
        val original = VpnSessionDiagnostics(lastResolver = ResolverKind.MULLVAD_DOH,
            lastTransport = ResolverTransport.MULLVAD_DOH, lastHttpProtocol = DnsHttpProtocol.HTTP_2)
        val timedOut = ResolverQueryHealthEvidence.primaryFailed(1, ResolverFailureReason.TIMEOUT).copy(tierObservations = listOf(
            ResolverTierDiagnosticObservation(1, ResolverKind.PLAIN_DNS, ResolverTierDiagnosticOutcome.FAILED, failure = ResolverFailureReason.TIMEOUT)))
        val answered = ResolverQueryHealthEvidence.primaryAccepted(2, 5).copy(tierObservations = listOf(
            ResolverTierDiagnosticObservation(1, ResolverKind.PLAIN_DNS, ResolverTierDiagnosticOutcome.SERVED, ResolverTransport.PLAIN_TCP)))
        val result = original.query(timedOut, 1000, smoke = true).query(answered, 1100, smoke = true)
        assertEquals(original, result.copy(smokeProbes = 0, smokeSuccesses = 0, tiers = emptyMap()))
        assertEquals(2L, result.smokeProbes)
        assertEquals(1L, result.smokeSuccesses)
        assertEquals(1L, result.tiers[1]!!.failures)
        assertEquals(1L, result.tiers[1]!!.served)
    }

    @Test fun `fallback success preserves failed primary but counts one served query`() {
        val evidence = ResolverQueryHealthEvidence.primaryFailed(1, ResolverFailureReason.TIMEOUT, ProtectionFallback.ENCRYPTED_DNS).copy(
            responseLatencyMillis = 207,
            httpProtocol = DnsHttpProtocol.HTTP_2,
            tierObservations = listOf(
                ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.FAILED, failure = ResolverFailureReason.TIMEOUT),
                ResolverTierDiagnosticObservation(2, ResolverKind.MULLVAD_DOH, ResolverTierDiagnosticOutcome.SERVED, ResolverTransport.MULLVAD_DOH),
            ))
        val result = VpnSessionDiagnostics().query(evidence, 1000)
        assertEquals(1L, result.upstreamSuccesses)
        assertEquals(0L, result.upstreamFailures)
        assertEquals(1L, result.timeouts)
        assertEquals(1L, result.tiers[1]!!.failures)
        assertEquals(1L, result.tiers[2]!!.served)
        assertEquals(DnsHttpProtocol.HTTP_2, result.lastHttpProtocol)
        assertEquals(listOf(207L), result.responseLatenciesMillis)
    }
    @Test fun `backoff does not fabricate a wire failure and probes do not inflate user queries`() {
        val skipped = ResolverQueryHealthEvidence.primaryFailed(1, ResolverFailureReason.BACKED_OFF).copy(tierObservations = listOf(
            ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.NOT_ATTEMPTED, failure = ResolverFailureReason.BACKED_OFF)))
        val first = VpnSessionDiagnostics().query(skipped, 1000)
        assertEquals(0L, first.upstreamFailures)
        assertEquals(0L, first.tiers[1]!!.attempts)
        assertEquals(1L, first.tiers[1]!!.notAttempted)
        val next = first.query(ResolverQueryHealthEvidence.primaryAccepted(2, 5), 1100, smoke = true)
        assertEquals(1L, next.smokeProbes)
        assertEquals(1L, next.smokeSuccesses)
        assertEquals(0L, next.upstreamSuccesses)
        assertTrue(next.responseLatenciesMillis.isEmpty())
    }
}
