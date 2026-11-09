package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.*
import com.lavasec.app.foundation.health.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResolverExecutionBudgetTest {
    @Test fun `tier deadline cannot renew and every tier consumes the original whole lifetime`() {
        var now = 1_000L
        val clock = MonotonicClock { now }
        val whole = ResolverExecutionBudget.whole(request(), clock)
        val one = ResolverExecutionBudget.tier(whole, clock, 1)
        now += 500_000_000L
        assertEquals(one.absoluteDeviceDeadlineNanos, ResolverExecutionBudget.tier(whole, clock, 1).absoluteDeviceDeadlineNanos)
        val two = ResolverExecutionBudget.tier(whole, clock, 2)
        assertTrue(two.absoluteDeviceDeadlineNanos!! > one.absoluteDeviceDeadlineNanos!!)
        assertEquals(whole.wholeQueryDeadlineNanos, two.wholeQueryDeadlineNanos)
        now = checkNotNull(whole.wholeQueryDeadlineNanos) - 1
        val late = ResolverExecutionBudget.tier(whole, clock, 0)
        assertEquals(whole.wholeQueryDeadlineNanos, late.absoluteDeviceDeadlineNanos)
    }
    @Test fun `expired tier before send stays local while validated late reply is terminal liveness`() = runBlocking {
        var now = 100L
        val clock = MonotonicClock { now }
        val bounded = ResolverExecutionBudget.tier(ResolverExecutionBudget.whole(request(), clock), clock)
        now = checkNotNull(bounded.absoluteDeviceDeadlineNanos)
        val local = ResolverExecutionBudget.execute(bounded, clock) { fail("expired tier must never send"); error("unreachable") } as ResolverResult.Failure
        assertTrue(local.local)
        assertFalse(local.terminal)
        now = 100L
        val late = ResolverExecutionBudget.execute(bounded, clock) {
            now = checkNotNull(bounded.absoluteDeviceDeadlineNanos)
            ResolverResult.Answer(DnsMessage.servfailResponse(bounded.query)!!, ResolverTransport.MULLVAD_DOH)
        } as ResolverResult.Failure
        assertTrue(late.terminal)
        assertTrue(late.peerAnswered)
        assertFalse(late.local)
    }
    @Test fun `local admission preserves peer verdict then peer liveness retires local reason without rejection`() {
        val failure = ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.FAILED, failure = ResolverFailureReason.TIMEOUT)
        val first = VpnTierDiagnostics().observe(failure, 10)
        val local = first.observe(ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.NOT_ATTEMPTED, failure = ResolverFailureReason.BACKED_OFF), 20)
        assertEquals(first.latest, local.latest)
        assertEquals(10L, local.observedAtMillis)
        assertEquals(20L, local.admissionObservedAtMillis)
        assertEquals(1L, local.failureStreak)
        val liveness = local.observe(ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.ANSWERED, peerLivenessOnly = true), 30)
        assertNull(liveness.latestAdmission)
        assertEquals(0L, liveness.failureStreak)
        assertEquals(0L, liveness.rejectedStreak)
        assertEquals(ResolverFailureReason.TIMEOUT, liveness.lastFailure)
    }
    @Test fun `sole enabled or promoted secondary keeps canonical tier two and no lower route`() {
        val context = testNetworkContext()
        val explicit = com.lavasec.app.foundation.resolver.ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = false,
            savedTiers = listOf(
                com.lavasec.app.foundation.resolver.ResolverTier(com.lavasec.app.foundation.resolver.ResolverSelectionPresets.MULLVAD_DOH, isEnabled = false),
                com.lavasec.app.foundation.resolver.ResolverTier(com.lavasec.app.foundation.resolver.ResolverSelectionPresets.DEVICE_PLAIN)))
        val selected = (ResolverPlanFactory.build(explicit, context) as ResolverPlanBuildResult.Ready).plan
        assertEquals(listOf(2), selected.configuredTierNumbers)
        assertEquals(1, selected.orderedRoutes!!.size)
        assertEquals(ResolverFallbackCoverage.NONE, selected.fallbackCoverage)
        val noAddresses = NetworkContext(context.network, context.networkGeneration, emptyList(), false)
        val promoted = (ResolverPlanFactory.build(com.lavasec.app.foundation.resolver.ResolverConfiguration(), noAddresses) as ResolverPlanBuildResult.Ready).plan
        assertEquals(listOf(2), promoted.configuredTierNumbers)
        assertEquals(ResolverFallbackCoverage.NONE, promoted.fallbackCoverage)
    }
    @Test fun `retained error reply counts unserved query without transport failure or success`() {
        val evidence = ResolverQueryHealthEvidence.primaryAccepted(1, 0).copy(tierObservations = listOf(
            ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.ANSWERED,
                ResolverTransport.DEVICE_UDP, responseClass = DnsFallbackResponseClass.DNS_ERROR, responseCode = 5)))
        val result = VpnSessionDiagnostics().query(evidence, 10)
        assertEquals(0L, result.upstreamSuccesses)
        assertEquals(1L, result.upstreamFailures)
        assertNull(result.lastFailure)
        assertNull(result.lastSuccessAtMillis)
        assertEquals(0L, result.tiers[1]!!.failures)
        assertEquals(1L, result.tiers[1]!!.answered)
    }

    private fun request(): ResolverRequest {
        val context = testNetworkContext()
        val plan = testResolverPlan(context)
        val query = testDnsQuery()
        return ResolverRequest(query, DnsMessage.parseQuestion(query), testEpoch(resolverPlan = plan), context)
    }
}
