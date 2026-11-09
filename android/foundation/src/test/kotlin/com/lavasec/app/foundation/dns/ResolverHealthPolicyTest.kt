package com.lavasec.app.foundation.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure health/transition policy (plan slice B06) against the canonical iOS
 * `ResolverHealthCoordinator`/`ResolverHealthOrganicEvidence`/`ResolverHealthSmokeEvidence` rules.
 */
class ResolverHealthPolicyTest {
    private val observation = ResolverObservationId(
        networkGeneration = 1L,
        planIdentifier = "plan-a",
        admissionToken = 10L,
    )

    private val network = ResolverNetworkIdentity(
        generation = 1L,
        kind = ResolverNetworkKind.WIFI,
        interfaceIndex = 3,
        capturedResolvers = listOf("192.168.1.1"),
    )

    private fun plan(fallback: ResolverRouteKind? = ResolverRouteKind.DEVICE_DNS) = ResolverHealthPlan(
        identifier = "plan-a",
        primary = ResolverRouteKind.ENCRYPTED,
        fallback = fallback,
    )

    private fun state(
        plan: ResolverHealthPlan = plan(),
        observation: ResolverObservationId = this.observation,
        network: ResolverNetworkIdentity = this.network,
    ) = ResolverHealthPolicy.initial(observation, network, plan)

    private fun failed(
        reason: ResolverFailureReason = ResolverFailureReason.TIMEOUT,
        at: Long = 1_000L,
        observation: ResolverObservationId = this.observation,
    ) = ResolverHealthSample.Organic(
        observation = observation,
        occurredAtMillis = at,
        outcome = ResolverOutcome.Failed(reason),
        resolverIdentifier = "1.1.1.1",
        transport = ResolverTransport.MULLVAD_DOH,
    )

    private fun primaryServed(
        at: Long = 1_000L,
        observation: ResolverObservationId = this.observation,
    ) = ResolverHealthSample.Organic(
        observation = observation,
        occurredAtMillis = at,
        outcome = ResolverOutcome.Served(),
        resolverIdentifier = "1.1.1.1",
        transport = ResolverTransport.MULLVAD_DOH,
    )

    private fun fallbackAccepted(
        primaryAttempted: Boolean,
        at: Long = 1_000L,
    ) = ResolverHealthSample.SmokeFallbackAccepted(
        observation = observation,
        occurredAtMillis = at,
        resolverIdentifier = "192.168.1.1",
        primaryAttempted = primaryAttempted,
    )

    @Test
    fun `fallback enabled recommends fallback once the failure bar is reached`() {
        var current = state(plan(ResolverRouteKind.DEVICE_DNS))
        var transition = ResolverHealthPolicy.observe(current, failed())
        current = transition.state
        assertEquals(ResolverHealthVerdict.DEGRADED, transition.verdict)

        transition = ResolverHealthPolicy.observe(current, failed(at = 1_001L))
        current = transition.state
        assertEquals(ResolverHealthVerdict.DEGRADED, transition.verdict)

        transition = ResolverHealthPolicy.observe(current, failed(at = 1_002L))
        assertEquals(ResolverHealthVerdict.FALLBACK, transition.verdict)
        assertTrue(transition.effects.contains(ResolverHealthEffect.ActivateFallback))
        assertEquals(3, transition.state.episode.consecutiveUpstreamFailures)
    }

    @Test
    fun `fallback disabled recommends a resolver switch instead`() {
        var current = state(plan(fallback = null))
        repeat(2) { index ->
            current = ResolverHealthPolicy.observe(current, failed(at = 1_000L + index)).state
        }
        val transition = ResolverHealthPolicy.observe(current, failed(at = 1_002L))
        assertEquals(ResolverHealthVerdict.SWITCH, transition.verdict)
        assertTrue(transition.effects.contains(ResolverHealthEffect.SwitchResolver))
        assertFalse(transition.effects.contains(ResolverHealthEffect.ActivateFallback))
    }

    @Test
    fun `three device dns fallback successes latch fallback mode and primary recovery exits it`() {
        var current = state()
        var activated = 0
        repeat(3) { index ->
            val transition = ResolverHealthPolicy.observe(current, fallbackAccepted(primaryAttempted = true, at = 1_000L + index))
            current = transition.state
            if (transition.effects.contains(ResolverHealthEffect.ActivateFallback)) activated += 1
        }
        assertTrue(current.episode.deviceDnsFallbackModeActive)
        assertEquals(ResolverHealthVerdict.FALLBACK, ResolverHealthPolicy.verdictOf(current))
        assertEquals(1, activated)

        val recovered = ResolverHealthPolicy.observe(current, primaryServed(at = 2_000L))
        assertEquals(ResolverHealthVerdict.RECOVER, recovered.verdict)
        assertFalse(recovered.state.episode.deviceDnsFallbackModeActive)
        assertTrue(recovered.effects.contains(ResolverHealthEffect.ExitFallback))
    }

    @Test
    fun `fallback successes do not count when the primary was never attempted`() {
        var current = state()
        repeat(3) { index ->
            current = ResolverHealthPolicy.observe(current, fallbackAccepted(primaryAttempted = false, at = 1_000L + index)).state
        }
        assertEquals(0, current.episode.deviceDnsFallbackEvidence)
        assertFalse(current.episode.deviceDnsFallbackModeActive)
        assertEquals(ResolverHealthVerdict.HEALTHY, ResolverHealthPolicy.verdictOf(current))
    }

    @Test
    fun `an upstream failure resets candidate evidence but keeps latched fallback mode`() {
        var current = state()
        repeat(3) { index ->
            current = ResolverHealthPolicy.observe(current, fallbackAccepted(primaryAttempted = true, at = 1_000L + index)).state
        }
        assertTrue(current.episode.deviceDnsFallbackModeActive)

        val transition = ResolverHealthPolicy.observe(current, failed(at = 2_000L))
        assertTrue(transition.state.episode.deviceDnsFallbackModeActive)
        assertEquals(0, transition.state.episode.deviceDnsFallbackEvidence)
        assertEquals(ResolverHealthVerdict.FALLBACK, transition.verdict)
    }

    @Test
    fun `a TLS error is a real upstream failure`() {
        var current = state()
        val first = ResolverHealthPolicy.observe(current, failed(ResolverFailureReason.TLS_FAILED))
        assertEquals(1, first.state.episode.consecutiveUpstreamFailures)
        assertEquals(ResolverHealthVerdict.DEGRADED, first.verdict)
        current = first.state
        current = ResolverHealthPolicy.observe(current, failed(ResolverFailureReason.TLS_FAILED, at = 1_001L)).state
        val third = ResolverHealthPolicy.observe(current, failed(ResolverFailureReason.TLS_FAILED, at = 1_002L))
        assertEquals(ResolverHealthVerdict.FALLBACK, third.verdict)
    }

    @Test
    fun `a local refusal is neutral`() {
        val transition = ResolverHealthPolicy.observe(state(), failed(ResolverFailureReason.BACKED_OFF))
        assertTrue(transition.applied)
        assertEquals(0, transition.state.episode.consecutiveUpstreamFailures)
        assertEquals(ResolverHealthVerdict.HEALTHY, transition.verdict)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `a truncated answer is liveness without failure credit`() {
        val sample = ResolverHealthSample.Organic(
            observation = observation,
            occurredAtMillis = 1_000L,
            outcome = ResolverOutcome.Truncated,
            resolverIdentifier = "1.1.1.1",
            transport = ResolverTransport.MULLVAD_DOH,
        )
        val transition = ResolverHealthPolicy.observe(state(), sample)
        assertEquals(0, transition.state.episode.consecutiveUpstreamFailures)
        assertEquals(1, transition.state.session.truncatedResponseCount)
        assertEquals(ResolverHealthVerdict.HEALTHY, transition.verdict)
    }

    @Test
    fun `network loss awaits instead of scoring the resolver`() {
        val lost = ResolverHealthPolicy.onNetworkPathChanged(state(), isSatisfied = false)
        assertEquals(ResolverHealthVerdict.DEGRADED, lost.verdict)
        assertTrue(lost.effects.contains(ResolverHealthEffect.AwaitNetwork))

        val restored = ResolverHealthPolicy.onNetworkPathChanged(lost.state, isSatisfied = true)
        assertEquals(ResolverHealthVerdict.HEALTHY, restored.verdict)
        assertFalse(restored.effects.contains(ResolverHealthEffect.AwaitNetwork))
    }

    @Test
    fun `a slow but served response degrades without failing`() {
        val sample = ResolverHealthSample.Organic(
            observation = observation,
            occurredAtMillis = 1_000L,
            outcome = ResolverOutcome.Served(),
            resolverIdentifier = "1.1.1.1",
            transport = ResolverTransport.MULLVAD_DOH,
            durationMillis = 3_000,
        )
        val transition = ResolverHealthPolicy.observe(state(), sample)
        assertEquals(1, transition.state.session.upstreamSuccessCount)
        assertEquals(0, transition.state.episode.consecutiveUpstreamFailures)
        assertEquals(1, transition.state.session.consecutiveSlowUpstreamResponses)
        assertEquals(ResolverHealthVerdict.DEGRADED, transition.verdict)
    }

    @Test
    fun `a chained outage-supervised leg clears the physical wedge without credit`() {
        var current = state()
        current = ResolverHealthPolicy.observe(current, failed()).state
        val transition = ResolverHealthPolicy.observe(
            current,
            ResolverHealthSample.Organic(
                observation = observation,
                occurredAtMillis = 1_100L,
                outcome = ResolverOutcome.GovernedByOutageSupervisor,
                resolverIdentifier = "1.1.1.1",
                transport = ResolverTransport.WIREGUARD_UDP,
            ),
        )
        assertEquals(0, transition.state.episode.consecutiveUpstreamFailures)
        assertEquals(0, transition.state.session.upstreamSuccessCount)
        assertEquals(ResolverHealthVerdict.HEALTHY, transition.verdict)
    }

    @Test
    fun `underlying network change clears the episode but preserves identity and session`() {
        var current = state()
        current = ResolverHealthPolicy.observe(current, failed()).state
        current = ResolverHealthPolicy.observe(
            current,
            ResolverHealthSample.SmokeFailed(
                observation = observation,
                occurredAtMillis = 1_100L,
                failure = ResolverFailureReason.HTTP_STATUS,
                resolverIdentifier = "1.1.1.1",
                transport = ResolverTransport.MULLVAD_DOH,
                rejectedResponse = true,
            ),
        ).state
        assertEquals(1, current.identity.rejectedResponseCount)
        assertEquals(2, current.session.upstreamFailureCount)

        val changedObservation = observation.copy(networkGeneration = 2L, admissionToken = 11L)
        val changedNetwork = network.copy(generation = 2L, capturedResolvers = listOf("10.0.0.1"))
        val transition = ResolverHealthPolicy.onUnderlyingNetworkChanged(current, changedNetwork, changedObservation)

        assertEquals(0, transition.state.episode.consecutiveUpstreamFailures)
        assertEquals(0, transition.state.episode.consecutiveSmokeFailures)
        assertEquals(1, transition.state.identity.rejectedResponseCount)
        assertEquals(2, transition.state.session.upstreamFailureCount)
        assertEquals(ResolverHealthVerdict.HEALTHY, transition.verdict)
    }

    @Test
    fun `a late response from a retired network is ignored`() {
        val changedObservation = observation.copy(networkGeneration = 2L, admissionToken = 11L)
        val changedNetwork = network.copy(generation = 2L, capturedResolvers = listOf("10.0.0.1"))
        val current = ResolverHealthPolicy.onUnderlyingNetworkChanged(
            ResolverHealthPolicy.observe(state(), failed()).state,
            changedNetwork,
            changedObservation,
        ).state

        val late = ResolverHealthPolicy.observe(current, primaryServed(observation = observation))
        assertFalse(late.applied)
        assertEquals(current, late.state)
        assertEquals(0, late.state.session.upstreamSuccessCount)
        assertTrue(late.effects.any { it is ResolverHealthEffect.IgnoreStaleEvidence })
        assertEquals(ResolverHealthVerdict.HEALTHY, late.verdict)
    }

    @Test
    fun `a late response from a retired plan is ignored even on the same network`() {
        val current = state()
        val retiredPlanSample = primaryServed(observation = observation.copy(planIdentifier = "plan-b"))
        val late = ResolverHealthPolicy.observe(current, retiredPlanSample)
        assertFalse(late.applied)
        assertEquals(0, late.state.session.upstreamSuccessCount)
    }

    @Test
    fun `active evidence resets the failure streak`() {
        val current = ResolverHealthPolicy.observe(state(), failed()).state
        val active = ResolverHealthPolicy.observe(
            current,
            ResolverHealthSample.Active(observation, occurredAtMillis = 2_000L),
        )
        assertEquals(0, active.state.episode.consecutiveUpstreamFailures)
        assertEquals(2_000L, active.state.episode.lastAcceptedPrimaryEvidenceAtMillis)
        assertEquals(ResolverHealthVerdict.HEALTHY, active.verdict)
    }

    @Test
    fun `a smoke failure exits a latched fallback`() {
        var current = state()
        repeat(3) { index ->
            current = ResolverHealthPolicy.observe(current, fallbackAccepted(primaryAttempted = true, at = 1_000L + index)).state
        }
        val transition = ResolverHealthPolicy.observe(
            current,
            ResolverHealthSample.SmokeFailed(
                observation = observation,
                occurredAtMillis = 2_000L,
                failure = ResolverFailureReason.TIMEOUT,
                resolverIdentifier = "192.168.1.1",
                transport = ResolverTransport.DEVICE_UDP,
            ),
        )
        assertFalse(transition.state.episode.deviceDnsFallbackModeActive)
        assertTrue(transition.effects.contains(ResolverHealthEffect.ExitFallback))
    }

    @Test
    fun `a rejected-response streak is keyed to the resolver identity`() {
        var current = state()
        current = ResolverHealthPolicy.observe(current, smokeRejected("1.1.1.1")).state
        current = ResolverHealthPolicy.observe(current, smokeRejected("1.1.1.1", at = 1_100L)).state
        assertEquals(2, current.identity.rejectedResponseCount)
        assertEquals("1.1.1.1", current.identity.rejectedResponseResolverIdentifier)

        current = ResolverHealthPolicy.observe(current, smokeRejected("9.9.9.9", at = 1_200L)).state
        assertEquals(1, current.identity.rejectedResponseCount)
        assertEquals("9.9.9.9", current.identity.rejectedResponseResolverIdentifier)
    }

    private fun smokeRejected(identifier: String, at: Long = 1_000L) = ResolverHealthSample.SmokeFailed(
        observation = observation,
        occurredAtMillis = at,
        failure = ResolverFailureReason.INVALID_RESPONSE,
        resolverIdentifier = identifier,
        transport = ResolverTransport.MULLVAD_DOH,
        rejectedResponse = true,
    )
}
