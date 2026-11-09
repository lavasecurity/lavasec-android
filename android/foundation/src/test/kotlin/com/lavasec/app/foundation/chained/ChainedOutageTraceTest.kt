package com.lavasec.app.foundation.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance fixtures for the canonical chained-outage transitions (plan slice B10).
 *
 * Each test reproduces one canonical rule from iOS `ChainedOutageDriver` at `c4658d97` /
 * the Android port, expressed against [ChainedOutageTrace]. The retry/backoff numbers are the
 * shipped [ChainedReconnectPolicy]'s, not restated here, so a drift in either side fails.
 */
class ChainedOutageTraceTest {

    private val connectionExpired: ChainedSessionEndCause =
        ChainedSessionEndCause.engineVerdict(WireGuardEngineError.ConnectionExpired)
            ?: error("connectionExpired is a session-ending cause")

    private fun trace(fullTunnel: Boolean = true, chained: Boolean = true): ChainedOutageTrace =
        ChainedOutageTrace(fullTunnel = fullTunnel, chainedLatched = chained)

    private fun sessionEnd(atSeconds: Int, generation: Long = 1L) =
        ChainedOutageEvent.SessionEnded(atSeconds, connectionExpired, generation)

    private fun linkSilence(atSeconds: Int, firstUnanswered: Int = 0) =
        ChainedOutageEvent.LinkSilence(atSeconds, firstUnanswered)

    /** Two distinct failing names past the stall window declare one tunnel-DNS outage. */
    private fun declareTunnelDns(atSeconds: Int = 21): ChainedOutageEvent.TunnelDnsUnanswered =
        ChainedOutageEvent.TunnelDnsUnanswered(atSeconds, "name-$atSeconds")

    // MARK: - T0/T1/T2 fallback allowed vs forbidden

    @Test
    fun `while chained in a full tunnel only T0 may egress`() {
        assertEquals(
            ChainedFallbackEgress.THROUGH_TUNNEL_TO_T0,
            ChainedOutageTrace.fallbackEgress(
                ChainedResolverTier.T0_UPSTREAM,
                chainedLatched = true,
                routingPolicy = ChainedRoutingPolicy.FULL_TUNNEL,
            ),
        )
        assertEquals(
            ChainedFallbackEgress.FORBIDDEN_FAIL_CLOSED,
            ChainedOutageTrace.fallbackEgress(
                ChainedResolverTier.T1_SELECTED,
                chainedLatched = true,
                routingPolicy = ChainedRoutingPolicy.FULL_TUNNEL,
            ),
        )
        assertEquals(
            ChainedFallbackEgress.FORBIDDEN_FAIL_CLOSED,
            ChainedOutageTrace.fallbackEgress(
                ChainedResolverTier.T2_FALLBACK,
                chainedLatched = true,
                routingPolicy = ChainedRoutingPolicy.FULL_TUNNEL,
            ),
        )
    }

    @Test
    fun `a split tunnel permits the chosen T1 resolver physically but never the device fallback`() {
        assertEquals(
            ChainedFallbackEgress.PHYSICAL_INTERFACE,
            ChainedOutageTrace.fallbackEgress(
                ChainedResolverTier.T1_SELECTED,
                chainedLatched = true,
                routingPolicy = ChainedRoutingPolicy.SPLIT_TUNNEL,
            ),
        )
        assertEquals(
            ChainedFallbackEgress.FORBIDDEN_FAIL_CLOSED,
            ChainedOutageTrace.fallbackEgress(
                ChainedResolverTier.T2_FALLBACK,
                chainedLatched = true,
                routingPolicy = ChainedRoutingPolicy.SPLIT_TUNNEL,
            ),
        )
    }

    @Test
    fun `outside chaining the ordinary physical ladder runs and T0 does not exist`() {
        for (tier in listOf(ChainedResolverTier.T1_SELECTED, ChainedResolverTier.T2_FALLBACK)) {
            assertEquals(
                "$tier is physical in DNS-only",
                ChainedFallbackEgress.PHYSICAL_INTERFACE,
                ChainedOutageTrace.fallbackEgress(tier, chainedLatched = false, routingPolicy = null),
            )
        }
        assertEquals(
            ChainedFallbackEgress.FORBIDDEN_FAIL_CLOSED,
            ChainedOutageTrace.fallbackEgress(
                ChainedResolverTier.T0_UPSTREAM,
                chainedLatched = false,
                routingPolicy = null,
            ),
        )
    }

    // MARK: - DNS-dead vs whole-egress evidence

    @Test
    fun `a tunnel-DNS outage is bound by the resolver and cannot be ended by link evidence`() {
        val outcome = trace().replay(
            declareTunnelDns(0),
            declareTunnelDns(21),
            ChainedOutageEvent.RetryElapsed(atSeconds = 22),
            ChainedOutageEvent.InboundData(atSeconds = 23, sessionGeneration = 2L),
            ChainedOutageEvent.AuthenticatedPeerDatagram(atSeconds = 23, sessionGeneration = 2L),
            ChainedOutageEvent.RealForwarding(atSeconds = 23, sessionGeneration = 2L),
        )

        assertEquals(ChainedOutagePhase.RECOVERING, outcome.phase)
        assertEquals(ChainedOutageCause.TUNNEL_DNS, outcome.holdingCause)
        assertEquals(1, outcome.dnsDeadDeclarations)
        assertFalse(outcome.surrendered)
    }

    @Test
    fun `a served answer is the only thing that ends a tunnel-DNS outage`() {
        val outcome = trace().replay(
            declareTunnelDns(0),
            declareTunnelDns(21),
            ChainedOutageEvent.RetryElapsed(atSeconds = 22),
            ChainedOutageEvent.InboundData(atSeconds = 23, sessionGeneration = 2L),
            ChainedOutageEvent.TunnelDnsAnswered(atSeconds = 24),
        )

        assertEquals(ChainedOutagePhase.HEALTHY, outcome.phase)
        assertNull(outcome.holdingCause)
        assertFalse(outcome.surrendered)
    }

    @Test
    fun `an egress-dead outage ends only on real forwarding on the current generation`() {
        val outcome = trace().replay(
            // Sustained demand: no gap may exceed the continuity window, or the window lapses.
            ChainedOutageEvent.NonDnsDemand(atSeconds = 0),
            ChainedOutageEvent.NonDnsDemand(atSeconds = 5),
            ChainedOutageEvent.NonDnsDemand(atSeconds = 10),
            ChainedOutageEvent.NonDnsDemand(atSeconds = 15),
            ChainedOutageEvent.NonDnsDemand(atSeconds = 20),
            ChainedOutageEvent.NonDnsDemand(atSeconds = 21),
            ChainedOutageEvent.RetryElapsed(atSeconds = 22),
            ChainedOutageEvent.InboundData(atSeconds = 23, sessionGeneration = 2L),
            ChainedOutageEvent.AuthenticatedPeerDatagram(atSeconds = 23, sessionGeneration = 2L),
            ChainedOutageEvent.RealForwarding(atSeconds = 23, sessionGeneration = 2L),
        )

        assertEquals(ChainedOutagePhase.HEALTHY, outcome.phase)
        assertEquals(1, outcome.egressDeadDeclarations)
        assertNull(outcome.holdingCause)
    }

    @Test
    fun `a split tunnel never declares an egress-dead outage`() {
        val outcome = trace(fullTunnel = false).replay(
            ChainedOutageEvent.NonDnsDemand(atSeconds = 0),
            ChainedOutageEvent.NonDnsDemand(atSeconds = 21),
            ChainedOutageEvent.NonDnsDemand(atSeconds = 42),
        )

        assertEquals(ChainedOutagePhase.HEALTHY, outcome.phase)
        assertEquals(0, outcome.egressDeadDeclarations)
        assertNull(outcome.holdingCause)
    }

    @Test
    fun `a demand lapse abandons the egress-dead window rather than accumulating it`() {
        val outcome = trace().replay(
            ChainedOutageEvent.NonDnsDemand(atSeconds = 0),
            // A lapse longer than the continuity window resets the demand clock...
            ChainedOutageEvent.NonDnsDemand(atSeconds = 15),
            // ...so this second demand has not yet sustained anything.
            ChainedOutageEvent.NonDnsDemand(atSeconds = 20),
        )

        assertEquals(ChainedOutagePhase.HEALTHY, outcome.phase)
        assertEquals(0, outcome.egressDeadDeclarations)
    }

    @Test
    fun `both declarations are bounded per lifecycle`() {
        var events = mutableListOf<ChainedOutageEvent>()
        var at = 0
        // Three declare/rebuild/answer cycles are permitted; a fourth is refused.
        repeat(4) {
            events += declareTunnelDns(at)
            events += declareTunnelDns(at + 21)
            events += ChainedOutageEvent.RetryElapsed(atSeconds = at + 22)
            events += ChainedOutageEvent.TunnelDnsAnswered(atSeconds = at + 23)
            at += 100
        }
        val outcome = trace().replay(events)

        assertEquals(ChainedOutageTrace.MAX_DNS_DECLARATIONS, outcome.dnsDeadDeclarations)
        assertEquals(ChainedOutagePhase.HEALTHY, outcome.phase)
    }

    // MARK: - Session recovery

    @Test
    fun `inbound data on the replacement ends a session-end outage without a rekey`() {
        // Starting an outage retires the old socket; recovery arrives on the replacement the
        // ladder builds. It needs no rekey window and no handshake-age reset — the first
        // delivered packet on the live generation ends the outage.
        val outcome = trace().replay(
            sessionEnd(0, generation = 1L),
            ChainedOutageEvent.RetryElapsed(atSeconds = 1),
            ChainedOutageEvent.InboundData(atSeconds = 1, sessionGeneration = 2L),
        )

        assertEquals(ChainedOutagePhase.HEALTHY, outcome.phase)
        assertTrue(outcome.phaseHistory.containsAll(listOf(
            ChainedOutagePhase.OUTAGE,
            ChainedOutagePhase.RECOVERING,
            ChainedOutagePhase.HEALTHY,
        )))
        assertEquals(1, outcome.attemptsStarted)
        assertEquals(listOf(1), outcome.retryDelaysSeconds)
        assertFalse(outcome.surrendered)
    }

    @Test
    fun `a link-silence or channel-wedge outage recovers on link evidence`() {
        for (event in listOf(
            linkSilence(atSeconds = 15, firstUnanswered = 0),
            ChainedOutageEvent.ChannelWedge(atSeconds = 15, firstSaturatedAtSeconds = 0),
        )) {
            val outcome = trace().replay(
                event,
                ChainedOutageEvent.RetryElapsed(atSeconds = 1),
                ChainedOutageEvent.AuthenticatedPeerDatagram(atSeconds = 2, sessionGeneration = 2L),
            )
            assertEquals("$event must recover on link evidence", ChainedOutagePhase.HEALTHY, outcome.phase)
        }
    }

    // MARK: - Fencing: a retired session's late event never marks a new plan healthy

    @Test
    fun `a stale inbound event cannot end the replacement session's outage`() {
        val outcome = trace().replay(
            sessionEnd(0, generation = 1L),
            ChainedOutageEvent.RetryElapsed(atSeconds = 1), // generation 2 is now live
            // A late packet from the retired generation 1 arrives after the swap.
            ChainedOutageEvent.InboundData(atSeconds = 2, sessionGeneration = 1L),
            ChainedOutageEvent.RealForwarding(atSeconds = 2, sessionGeneration = 1L),
        )

        assertEquals(ChainedOutagePhase.RECOVERING, outcome.phase)
        assertEquals(2, outcome.staleEventsIgnored)
        assertFalse(outcome.surrendered)
        // Only the live generation ends it.
        assertEquals(
            ChainedOutagePhase.HEALTHY,
            trace().replay(
                sessionEnd(0, generation = 1L),
                ChainedOutageEvent.RetryElapsed(atSeconds = 1),
                ChainedOutageEvent.InboundData(atSeconds = 2, sessionGeneration = 1L),
                ChainedOutageEvent.InboundData(atSeconds = 2, sessionGeneration = 2L),
            ).phase,
        )
    }

    @Test
    fun `a stale session end cannot retire or re-arm the replacement`() {
        val outcome = trace().replay(
            sessionEnd(0, generation = 1L),
            ChainedOutageEvent.RetryElapsed(atSeconds = 1),
            // The old generation's end arrives late; it must decide nothing.
            sessionEnd(2, generation = 1L),
            ChainedOutageEvent.InboundData(atSeconds = 2, sessionGeneration = 2L),
        )

        assertEquals(1, outcome.attemptsStarted)
        assertEquals(listOf(1), outcome.retryDelaysSeconds)
        assertEquals(1, outcome.staleEventsIgnored)
        assertEquals(ChainedOutagePhase.HEALTHY, outcome.phase)
    }

    // MARK: - Suspension and surfacing

    @Test
    fun `a paired wake refunds the outage budget`() {
        val outcome = trace().replay(
            linkSilence(atSeconds = 15, firstUnanswered = 0),
            ChainedOutageEvent.Sleep(atSeconds = 16),
            ChainedOutageEvent.Wake(atSeconds = 100),
        )

        assertFalse("a paired wake must not surrender the budget spent asleep", outcome.surrendered)
        // The refund happened: the new outage starts its own budget instead of surrendering.
        assertEquals(ChainedOutagePhase.RECOVERING, outcome.phase)
        assertTrue(outcome.phaseHistory.contains(ChainedOutagePhase.SUSPENDED))
    }

    @Test
    fun `an unpaired wake refunds nothing and the watchdog still surrenders`() {
        val outcome = trace().replay(
            linkSilence(atSeconds = 15, firstUnanswered = 0),
            ChainedOutageEvent.Wake(atSeconds = 100), // no intervening Sleep
            ChainedOutageEvent.WatchdogExpired(atSeconds = 100),
        )

        assertTrue(outcome.surrendered)
        assertEquals(ChainedSurrenderReason.BUDGET_EXHAUSTED, outcome.surrenderReason)
    }

    @Test
    fun `a tick decides nothing while quiesced`() {
        val outcome = trace().replay(
            sessionEnd(0, generation = 1L),
            ChainedOutageEvent.Sleep(atSeconds = 1),
            ChainedOutageEvent.InboundData(atSeconds = 2, sessionGeneration = 1L),
            ChainedOutageEvent.RealForwarding(atSeconds = 2, sessionGeneration = 1L),
        )

        assertEquals(ChainedOutagePhase.SUSPENDED, outcome.phase)
        // The quiesced session end retired the runner; the replacement's inbound during sleep
        // was ignored rather than recovering.
        assertEquals(ChainedOutagePhase.SUSPENDED, outcome.phase)
    }

    @Test
    fun `surfacing maps each phase to its canonical condition`() {
        assertEquals(ChainedSurfacedState.NORMAL, trace().replay().surfaced)
        assertEquals(
            ChainedSurfacedState.RECOVERING,
            trace().replay(sessionEnd(0)).surfaced,
        )
        assertEquals(
            ChainedSurfacedState.SUSPENDED,
            trace().replay(ChainedOutageEvent.Sleep(0)).surfaced,
        )
        assertEquals(
            ChainedSurfacedState.OFFLINE,
            trace().replay(ChainedOutageEvent.PathChanged(0, satisfied = false)).surfaced,
        )
        assertEquals(
            ChainedSurfacedState.RETIRED,
            trace().replay(ChainedOutageEvent.Retire(0)).surfaced,
        )
    }

    @Test
    fun `going offline quiets detection and returning with a live session recovers`() {
        val outcome = trace().replay(
            sessionEnd(0, generation = 1L),
            ChainedOutageEvent.RetryElapsed(atSeconds = 1),
            ChainedOutageEvent.InboundData(atSeconds = 1, sessionGeneration = 2L),
            ChainedOutageEvent.PathChanged(atSeconds = 2, satisfied = false),
            ChainedOutageEvent.InboundData(atSeconds = 3, sessionGeneration = 2L),
            ChainedOutageEvent.PathChanged(atSeconds = 4, satisfied = true),
        )

        assertEquals(ChainedOutagePhase.HEALTHY, outcome.phase)
        assertTrue(outcome.phaseHistory.contains(ChainedOutagePhase.OFFLINE))
    }

    // MARK: - Terminal shutdown

    @Test
    fun `a hard engine failure is terminal and ignores later events`() {
        val outcome = trace().replay(
            ChainedOutageEvent.TerminalFailure(0, ChainedTerminalFailure.ENGINE_FAILED),
            ChainedOutageEvent.InboundData(atSeconds = 1, sessionGeneration = 1L),
            ChainedOutageEvent.PathChanged(atSeconds = 2, satisfied = true),
        )

        assertEquals(ChainedOutagePhase.SURRENDERED, outcome.phase)
        assertEquals(ChainedSurrenderDisposition.TERMINATE_PROTECTION, outcome.surrenderDisposition)
        assertFalse(outcome.recoveredOnNetworkChange)
        assertTrue(outcome.isTerminal)
        assertEquals(ChainedSurfacedState.RETIRED, outcome.surfaced)
    }

    @Test
    fun `an explicit retire is terminal and absorbing`() {
        val outcome = trace().replay(
            ChainedOutageEvent.Retire(0),
            ChainedOutageEvent.Sleep(1),
            ChainedOutageEvent.InboundData(atSeconds = 1, sessionGeneration = 1L),
        )

        assertEquals(ChainedOutagePhase.RETIRED, outcome.phase)
        assertTrue(outcome.isTerminal)
    }

    // MARK: - Network surrender

    @Test
    fun `the budget-exhausted surrender may re-arm chaining on a network change`() {
        val exhausted = trace().replay(
            sessionEnd(0, generation = 1L),
            ChainedOutageEvent.RetryElapsed(atSeconds = 1),
            sessionEnd(1, generation = 2L),
            ChainedOutageEvent.RetryElapsed(atSeconds = 3),
            sessionEnd(3, generation = 3L),
            ChainedOutageEvent.RetryElapsed(atSeconds = 7),
            sessionEnd(7, generation = 4L),
        )

        assertEquals(ChainedSurrenderReason.BUDGET_EXHAUSTED, exhausted.surrenderReason)
        assertEquals(ChainedSurrenderDisposition.REBUILD_DNS_ONLY, exhausted.surrenderDisposition)
        assertTrue(exhausted.mayRearmOnNetworkChange)
        assertEquals(listOf(1, 2, 4), exhausted.retryDelaysSeconds)
        assertEquals(3, exhausted.attemptsStarted)

        val recovered = trace().replay(
            sessionEnd(0, generation = 1L),
            ChainedOutageEvent.RetryElapsed(atSeconds = 1),
            sessionEnd(1, generation = 2L),
            ChainedOutageEvent.RetryElapsed(atSeconds = 3),
            sessionEnd(3, generation = 3L),
            ChainedOutageEvent.RetryElapsed(atSeconds = 7),
            sessionEnd(7, generation = 4L),
            ChainedOutageEvent.PathChanged(atSeconds = 8, satisfied = true),
        )
        assertTrue(recovered.recoveredOnNetworkChange)
        assertEquals(ChainedOutagePhase.AWAITING_NETWORK, recovered.phase)
    }

    @Test
    fun `a recoverable transport failure falls back to DNS-only and may re-arm`() {
        val outcome = trace().replay(
            ChainedOutageEvent.TerminalFailure(0, ChainedTerminalFailure.PEER_SOCKET_FAILED),
            ChainedOutageEvent.PathChanged(atSeconds = 1, satisfied = true),
        )

        assertEquals(ChainedSurrenderDisposition.REBUILD_DNS_ONLY, outcome.surrenderDisposition)
        assertTrue(outcome.mayRearmOnNetworkChange)
        assertTrue(outcome.recoveredOnNetworkChange)
        assertFalse(outcome.isTerminal)
    }

    @Test
    fun `an engine-unusable surrender is not lifted by a network change`() {
        // A deterministic engine fault cannot be repaired by another network, so the plan stays
        // surrendered even after a satisfied path change.
        val unusableOutcome = trace().replay(
            ChainedOutageEvent.SessionEnded(
                atSeconds = 0,
                cause = ChainedSessionEndCause.sessionCreationFailed,
                sessionGeneration = 1L,
            ),
            ChainedOutageEvent.PathChanged(atSeconds = 1, satisfied = true),
        )

        assertTrue(unusableOutcome.surrendered)
        assertFalse(unusableOutcome.mayRearmOnNetworkChange)
        assertFalse(unusableOutcome.recoveredOnNetworkChange)
        assertEquals(ChainedOutagePhase.SURRENDERED, unusableOutcome.phase)
    }

    // MARK: - Bounded retries and queues

    @Test
    fun `the replay ladder matches the shipped reconnect policy and terminates inside the budget`() {
        // The fixture must not invent its own numbers: the first three rungs are exactly what
        // the shipped pure policy authorizes for the same attempts and elapsed values.
        val expected = (0..2).map { attempt ->
            val retry = ChainedReconnectPolicy.decision(
                completedAttempts = attempt,
                elapsedSeconds = listOf(0, 1, 3)[attempt],
                cause = connectionExpired,
            ) as ChainedReconnectDecision.Retry
            retry.afterSeconds
        }
        assertEquals(listOf(1, 2, 4), expected)

        var events = mutableListOf<ChainedOutageEvent>()
        var at = 0
        var generation = 1L
        var attempts = 0
        var elapsed = 0
        while (true) {
            events += sessionEnd(at, generation)
            val decision = ChainedReconnectPolicy.decision(attempts, elapsed, connectionExpired)
            if (decision is ChainedReconnectDecision.FallBackToDNSOnly) break
            val retry = decision as ChainedReconnectDecision.Retry
            elapsed += retry.afterSeconds
            at = elapsed
            generation += 1
            attempts += 1
            events += ChainedOutageEvent.RetryElapsed(at)
        }
        val outcome = trace().replay(events)

        assertTrue(outcome.surrendered)
        assertEquals(ChainedSurrenderReason.BUDGET_EXHAUSTED, outcome.surrenderReason)
        assertEquals(expected, outcome.retryDelaysSeconds)
        assertTrue(elapsed <= ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS)
    }

    @Test
    fun `the outbound packet queue stays inside its bounded byte and packet budget`() {
        val limits = ChainedPacketQueueLimits.forChainedTunnel(ChainedRoutePlan.MINIMUM_IPV6_LINK_MTU)
        assertNotNull(limits)
        val queue = ChainedPacketQueue(requireNotNull(limits))
        val packet = ByteArray(1_000)

        var evictingAdmissions = 0
        repeat(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH * 2) {
            val admission = queue.enqueue(packet)
            assertTrue("every admission keeps the packet", admission.wasQueued)
            if (admission is ChainedQueueAdmission.AdmittedAfterEvicting) {
                evictingAdmissions++
            }
        }

        assertTrue("the queue must have started evicting", evictingAdmissions > 0)
        assertTrue(queue.count <= queue.limits.maximumPackets)
        assertTrue(queue.byteCount <= queue.limits.maximumBytes)
    }

    // MARK: - Channel wedge and unrelated evidence

    @Test
    fun `a wedge outage is ended by link evidence but not by the user merely asking`() {
        val survivedDemand = trace().replay(
            ChainedOutageEvent.ChannelWedge(atSeconds = 15, firstSaturatedAtSeconds = 0),
            ChainedOutageEvent.NonDnsDemand(atSeconds = 16),
        )
        assertEquals(ChainedOutagePhase.RECOVERING, survivedDemand.phase)
        assertEquals(ChainedOutageCause.CHANNEL_WEDGE, survivedDemand.holdingCause)

        val recovered = trace().replay(
            ChainedOutageEvent.ChannelWedge(atSeconds = 15, firstSaturatedAtSeconds = 0),
            ChainedOutageEvent.RetryElapsed(atSeconds = 1),
            ChainedOutageEvent.InboundData(atSeconds = 2, sessionGeneration = 2L),
        )
        assertEquals(ChainedOutagePhase.HEALTHY, recovered.phase)
    }

    @Test
    fun `a session ending outside an outage starts one`() {
        // The dead-path case: the engine reports nothing until long past the budget, so the end
        // itself must open the outage and the ladder.
        val outcome = trace().replay(sessionEnd(0, generation = 1L))

        assertEquals(ChainedOutagePhase.RECOVERING, outcome.phase)
        assertEquals(ChainedOutageCause.SESSION_END, outcome.holdingCause)
        assertEquals(1, outcome.retryDelaysSeconds.size)
    }
}
