package com.lavasec.app.foundation.chained

/**
 * The canonical chained-outage event/state model — Android parity plan slice B10.
 *
 * ## What this is, and what it deliberately is not
 *
 * This is a CONFORMANCE FIXTURE, not a second implementation of the live driver. The live
 * driver (`vpn` `ChainedOutageDriver`, mirroring iOS `ChainedOutageDriver` at `c4658d97`) is
 * the implementation; this file names the canonical phases, causes, resolver tiers and events
 * in one place and replays an ordered event sequence to the canonical outcome, so the driver's
 * behaviour can be checked against it instead of being re-derived by reading both sides.
 *
 * It does NOT duplicate the shipped pure policy. The retry/backoff arithmetic is
 * [ChainedReconnectPolicy], the budget accounting is [ChainedOutageBudget], and the
 * chained resolver egress is [ChainedResolverEgress]. What this file adds is exactly the part
 * those types do not express:
 *
 * - which EVIDENCE may end which KIND of outage (`T0`/`T1`/`T2`, link, tunnel-DNS, egress-dead);
 * - the suspension/surfacing contract;
 * - the fencing rule that a retired session's late event can never mark a new session healthy;
 * - the surrender disposition (DNS-only fallback vs terminal) and whether a network change may
 *   re-arm chaining.
 *
 * ## Canonical rules reproduced
 *
 * - A LINK-SILENCE or SESSION-END outage ends on link evidence: a decrypted inbound packet
 *   (a zero-length keepalive included) or a real forwarded byte.
 * - A TUNNEL-DNS outage is held by the resolver and ends ONLY on a served answer. This is the
 *   S6 rule: a keepalive is not a resolver, and letting link traffic end the outage would
 *   produce an endless kill-rebuild cycle.
 * - An EGRESS-DEAD outage ends ONLY on real forwarding — a delivered non-DNS byte on the
 *   CURRENT session generation. A rebuilt session's handshake does not count.
 * - EGRESS-DEAD is declared in FULL TUNNEL only: in split a user send to an unresponsive
 *   `AllowedIPs` destination leaves the forwarding counter flat on a healthy chain.
 * - A suspension (sleep) quiesces every one-shot; a PAIRED wake refunds the outage budget, an
 *   unpaired wake does not.
 * - A session end whose generation is not the live one is STALE and decides nothing.
 * - Only `BUDGET_EXHAUSTED` and the recoverable transport failure may re-arm chaining on a
 *   network change; the other surrender reasons are terminal for chained mode.
 *
 * ## Why the retry schedule is delegated
 *
 * [ChainedOutageBudget] and [ChainedReconnectPolicy] already are the shipped pure policy, and
 * the fixture would be a second source of truth if it restated `1, 2, 4` itself. The tests in
 * `ChainedOutageTraceTest` assert the replay's ladder equals what the live driver's own
 * primitives produce, which is the point of a conformance fixture.
 */
class ChainedOutageTrace(
    /** Whether this session claims the default route, which scopes egress-dead detection. */
    val fullTunnel: Boolean = true,
    /** The latched routing policy; [fullTunnel] is the convenience form of this. */
    val routingPolicy: ChainedRoutingPolicy =
        if (fullTunnel) ChainedRoutingPolicy.FULL_TUNNEL else ChainedRoutingPolicy.SPLIT_TUNNEL,
    /** Whether chained mode is latched at all. Outside it the ordinary T1/T2 ladder runs. */
    val chainedLatched: Boolean = true,
) {

    /** Replays one ordered event sequence to its canonical outcome. Pure and deterministic. */
    fun replay(events: List<ChainedOutageEvent>): ChainedOutageOutcome =
        Replay().run(events, this)

    /** Replays convenience form; the caller supplies events in time order. */
    fun replay(vararg events: ChainedOutageEvent): ChainedOutageOutcome = replay(events.toList())

    private class Replay {
        private var phase = ChainedOutagePhase.HEALTHY
        private var cause: ChainedOutageCause? = null
        private var budget: ChainedOutageBudget = ChainedOutageBudget()
        private var heldByDns = false
        private var heldByEgress = false
        private var sawSleep = false
        private var pendingPathRecovery = false
        private var attemptInFlight = false
        private var sessionGeneration = 1L
        private var activeSessionGeneration: Long? = 1L
        private var staleIgnored = 0
        private var dnsDeclarations = 0
        private var egressDeclarations = 0
        private var dnsFirstFailureAt: Int? = null
        private val dnsFailedNames = mutableSetOf<String>()
        private var demandFirstAt: Int? = null
        private var demandLastAt: Int? = null
        private var surrendered = false
        private var surrenderReason: ChainedSurrenderReason? = null
        private var surrenderDisposition: ChainedSurrenderDisposition? = null
        private var rearmOnNetworkChange = false
        private var recoveredOnNetworkChange = false
        private val retryDelays = mutableListOf<Int>()
        private var attemptsStarted = 0
        private val phases = mutableListOf(ChainedOutagePhase.HEALTHY)

        fun run(events: List<ChainedOutageEvent>, model: ChainedOutageTrace): ChainedOutageOutcome {
            for (event in events) apply(event, model)
            return outcome()
        }

        private fun transition(next: ChainedOutagePhase) {
            if (phase == next) return
            phase = next
            phases += next
        }

        private fun apply(event: ChainedOutageEvent, model: ChainedOutageTrace) {
            // RETIRED is absorbing. SURRENDERED is absorbing except for an explicit retire and
            // the one network change that may re-arm a recoverable surrender.
            if (phase == ChainedOutagePhase.RETIRED) return
            if (surrendered) {
                when (event) {
                    is ChainedOutageEvent.Retire -> transition(ChainedOutagePhase.RETIRED)
                    is ChainedOutageEvent.PathChanged ->
                        if (event.satisfied && rearmOnNetworkChange) {
                            recoveredOnNetworkChange = true
                            transition(ChainedOutagePhase.AWAITING_NETWORK)
                        }
                    else -> Unit
                }
                return
            }
            // QUIESCED (suspended or offline): a tick decides nothing, but a session end still
            // retires the runner, and a path change/wake is how the quiesce is exited.
            if (phase == ChainedOutagePhase.SUSPENDED || phase == ChainedOutagePhase.OFFLINE) {
                when (event) {
                    is ChainedOutageEvent.Sleep -> sawSleep = true
                    is ChainedOutageEvent.Wake -> wake(event)
                    is ChainedOutageEvent.PathChanged -> pathChanged(event)
                    is ChainedOutageEvent.SessionEnded ->
                        if (event.sessionGeneration == activeSessionGeneration) {
                            activeSessionGeneration = null
                        }
                    is ChainedOutageEvent.Retire -> transition(ChainedOutagePhase.RETIRED)
                    else -> Unit
                }
                return
            }
            when (event) {
                is ChainedOutageEvent.Sleep -> sleep()
                is ChainedOutageEvent.Wake -> wake(event)
                is ChainedOutageEvent.PathChanged -> pathChanged(event)
                is ChainedOutageEvent.SessionEnded -> sessionEnded(event)
                is ChainedOutageEvent.RetryElapsed -> retryElapsed(event)
                is ChainedOutageEvent.WatchdogExpired -> watchdog(event)
                is ChainedOutageEvent.LinkSilence ->
                    declaredOutage(ChainedOutageCause.LINK_SILENCE, event.firstUnansweredAtSeconds)
                is ChainedOutageEvent.ChannelWedge ->
                    declaredOutage(ChainedOutageCause.CHANNEL_WEDGE, event.firstSaturatedAtSeconds)
                is ChainedOutageEvent.TunnelDnsUnanswered -> tunnelDnsUnanswered(event)
                is ChainedOutageEvent.TunnelDnsAnswered -> tunnelDnsAnswered(event)
                is ChainedOutageEvent.NonDnsDemand -> nonDnsDemand(event, model)
                is ChainedOutageEvent.RealForwarding -> forwarding(event)
                is ChainedOutageEvent.AuthenticatedPeerDatagram ->
                    linkEvidence(event.sessionGeneration, event.atSeconds)
                is ChainedOutageEvent.InboundData ->
                    linkEvidence(event.sessionGeneration, event.atSeconds)
                is ChainedOutageEvent.TerminalFailure -> terminalFailure(event)
                is ChainedOutageEvent.Retire -> transition(ChainedOutagePhase.RETIRED)
            }
        }

        // MARK: - Clock and declarations

        /**
         * Starts the outage clock exactly once. Returns whether this call STARTED it.
         *
         * Starting an outage RETIRES the live session, exactly as the driver's
         * `startOutageClock` does: once the ladder owns recovery, the old socket has nothing
         * left to prove, and recovery arrives on a replacement generation. That is why a
         * served answer landing during the retry delay only RELEASES a DNS hold without
         * ending the outage — the replacement's first inbound packet ends it instead.
         */
        private fun startOutageClock(newCause: ChainedOutageCause, atSeconds: Int): Boolean {
            if (budget.isTimingAnOutage) return false
            budget = budget.beginOutage(atSeconds)
            cause = newCause
            activeSessionGeneration = null
            attemptInFlight = false
            transition(ChainedOutagePhase.OUTAGE)
            return true
        }

        /**
         * A declaration from liveness/health evidence. It starts the clock and then synthesizes
         * the session end that starts the ladder, exactly as the live driver's `beginOutage`
         * does: without it a dead path would spend the whole budget on `CarryOn`.
         */
        private fun declaredOutage(newCause: ChainedOutageCause, atSeconds: Int) {
            if (!startOutageClock(newCause, atSeconds)) return
            val synthetic =
                ChainedSessionEndCause.engineVerdict(WireGuardEngineError.ConnectionExpired)
                    ?: return
            decide(synthetic, atSeconds)
        }

        private fun decide(sessionEnd: ChainedSessionEndCause, atSeconds: Int) {
            if (attemptInFlight) {
                budget = budget.recordAttempt()
                attemptInFlight = false
            }
            val step = budget.decisionForSessionEnd(atSeconds, sessionEnd)
            budget = step.budget
            when (val decision = step.decision) {
                is ChainedReconnectDecision.Retry -> {
                    retryDelays += decision.afterSeconds
                    transition(ChainedOutagePhase.RECOVERING)
                }
                is ChainedReconnectDecision.FallBackToDNSOnly -> surrender(decision.reason)
                null -> Unit
            }
        }

        private fun surrender(reason: ChainedSurrenderReason) {
            if (surrendered) return
            surrendered = true
            surrenderReason = reason
            // A reconnect-policy surrender always gives way to the unchanged DNS-only tunnel;
            // only the budget-exhausted reason may re-arm chaining on the next network.
            surrenderDisposition = ChainedSurrenderDisposition.REBUILD_DNS_ONLY
            rearmOnNetworkChange = reason.isResolvableByNetworkChange
            activeSessionGeneration = null
            heldByDns = false
            heldByEgress = false
            transition(ChainedOutagePhase.SURRENDERED)
        }

        private fun terminalFailure(event: ChainedOutageEvent.TerminalFailure) {
            if (surrendered) return
            surrendered = true
            val disposition = dispositionFor(event.failure)
            surrenderDisposition = disposition
            rearmOnNetworkChange = event.failure == ChainedTerminalFailure.PEER_SOCKET_FAILED
            activeSessionGeneration = null
            heldByDns = false
            heldByEgress = false
            transition(ChainedOutagePhase.SURRENDERED)
        }

        private fun endOutage(atSeconds: Int) {
            if (!budget.isTimingAnOutage) return
            budget = budget.endOutage()
            cause = null
            heldByDns = false
            heldByEgress = false
            attemptInFlight = false
            dnsFirstFailureAt = null
            dnsFailedNames.clear()
            demandFirstAt = null
            demandLastAt = null
            transition(ChainedOutagePhase.HEALTHY)
        }

        // MARK: - Session lifecycle

        private fun sessionEnded(event: ChainedOutageEvent.SessionEnded) {
            // FENCING: only the live session's end is news. A late event from a retired session
            // decides nothing, so it cannot mark the replacement's plan.
            if (event.sessionGeneration != activeSessionGeneration) {
                staleIgnored++
                return
            }
            activeSessionGeneration = null
            startOutageClock(ChainedOutageCause.SESSION_END, event.atSeconds)
            decide(event.cause, event.atSeconds)
        }

        private fun retryElapsed(event: ChainedOutageEvent.RetryElapsed) {
            if (phase != ChainedOutagePhase.RECOVERING) return
            budget = budget.observe(event.atSeconds)
            if (budget.remainingSeconds(event.atSeconds) <
                ChainedReconnectPolicy.MINIMUM_USEFUL_ATTEMPT_SECONDS
            ) {
                surrender(ChainedSurrenderReason.BUDGET_EXHAUSTED)
                return
            }
            sessionGeneration += 1
            activeSessionGeneration = sessionGeneration
            attemptInFlight = true
            attemptsStarted += 1
        }

        private fun watchdog(event: ChainedOutageEvent.WatchdogExpired) {
            val step = budget.watchdogSurrender(event.atSeconds) ?: return
            budget = step.budget
            when (val decision = step.decision) {
                is ChainedReconnectDecision.FallBackToDNSOnly -> surrender(decision.reason)
                else -> Unit
            }
        }

        // MARK: - Suspend / wake / path

        private fun sleep() {
            sawSleep = true
            transition(ChainedOutagePhase.SUSPENDED)
        }

        private fun wake(event: ChainedOutageEvent.Wake) {
            val paired = sawSleep
            sawSleep = false
            if (phase == ChainedOutagePhase.OFFLINE) return
            if (paired) {
                // A paired wake refunds the budget: nothing was blackholed while suspended.
                if (budget.isTimingAnOutage) endOutage(event.atSeconds)
                if (pendingPathRecovery) {
                    pendingPathRecovery = false
                    transition(ChainedOutagePhase.HEALTHY)
                    return
                }
                if (activeSessionGeneration == null) {
                    startOutageClock(ChainedOutageCause.SESSION_END, event.atSeconds)
                    decide(syntheticConnectionExpired(), event.atSeconds)
                } else {
                    transition(ChainedOutagePhase.HEALTHY)
                }
            } else {
                // AN UNPAIRED wake is not a suspension this driver saw; it refunds nothing.
                if (phase == ChainedOutagePhase.SUSPENDED) {
                    transition(
                        if (activeSessionGeneration != null) {
                            ChainedOutagePhase.HEALTHY
                        } else {
                            ChainedOutagePhase.OUTAGE
                        },
                    )
                }
            }
        }

        private fun pathChanged(event: ChainedOutageEvent.PathChanged) {
            if (!event.satisfied) {
                if (phase == ChainedOutagePhase.OFFLINE) return
                if (budget.isTimingAnOutage) endOutage(event.atSeconds)
                pendingPathRecovery = false
                transition(ChainedOutagePhase.OFFLINE)
                return
            }
            if (phase == ChainedOutagePhase.SUSPENDED) {
                pendingPathRecovery = true
                return
            }
            val wasOffline = phase == ChainedOutagePhase.OFFLINE
            if (!wasOffline) return
            if (activeSessionGeneration == null) {
                startOutageClock(ChainedOutageCause.SESSION_END, event.atSeconds)
                decide(syntheticConnectionExpired(), event.atSeconds)
            } else {
                transition(ChainedOutagePhase.HEALTHY)
            }
        }

        // MARK: - Evidence

        private fun tunnelDnsUnanswered(event: ChainedOutageEvent.TunnelDnsUnanswered) {
            if (budget.isTimingAnOutage) return
            if (dnsDeclarations >= MAX_DNS_DECLARATIONS) return
            if (dnsFirstFailureAt == null) dnsFirstFailureAt = event.atSeconds
            if (dnsFailedNames.size < MIN_DISTINCT_DNS_NAMES) dnsFailedNames.add(event.nameKey)
            val first = dnsFirstFailureAt ?: return
            if (event.atSeconds - first < DNS_STALL_SECONDS) return
            if (dnsFailedNames.size < MIN_DISTINCT_DNS_NAMES) return
            dnsDeclarations++
            heldByDns = true
            declaredOutage(ChainedOutageCause.TUNNEL_DNS, event.atSeconds)
        }

        private fun tunnelDnsAnswered(event: ChainedOutageEvent.TunnelDnsAnswered) {
            dnsFirstFailureAt = null
            dnsFailedNames.clear()
            if (!heldByDns) return
            heldByDns = false
            // The served answer ends a DNS-held outage only while the tunnel still owns a
            // session; a runner already torn down for a retry rebuilds, and the replacement's
            // first inbound packet ends it instead.
            if (budget.isTimingAnOutage && !heldByEgress && activeSessionGeneration != null) {
                endOutage(event.atSeconds)
            }
        }

        private fun nonDnsDemand(event: ChainedOutageEvent.NonDnsDemand, model: ChainedOutageTrace) {
            // FULL TUNNEL ONLY: split carries the profile AllowedIPs, so a flat forwarding
            // counter there can belong to a healthy chain.
            if (model.routingPolicy != ChainedRoutingPolicy.FULL_TUNNEL) return
            if (budget.isTimingAnOutage) return
            if (egressDeclarations >= MAX_EGRESS_DECLARATIONS) return
            if (demandLastAt?.let { event.atSeconds - it >= DEMAND_CONTINUITY_SECONDS } == true) {
                demandFirstAt = null
                demandLastAt = null
            }
            if (demandFirstAt == null) demandFirstAt = event.atSeconds
            demandLastAt = event.atSeconds
            val first = demandFirstAt ?: return
            if (event.atSeconds - first < EGRESS_STALL_SECONDS) return
            egressDeclarations++
            heldByEgress = true
            declaredOutage(ChainedOutageCause.EGRESS_DEAD, event.atSeconds)
        }

        private fun forwarding(event: ChainedOutageEvent.RealForwarding) {
            if (event.sessionGeneration != activeSessionGeneration) {
                staleIgnored++
                return
            }
            if (!budget.isTimingAnOutage) {
                transition(ChainedOutagePhase.HEALTHY)
                return
            }
            if (heldByDns) return
            // Real forwarding is the ONLY thing that ends an egress-dead outage. It also ends a
            // link-silence or session-end outage, being stronger link evidence than a keepalive.
            endOutage(event.atSeconds)
        }

        private fun linkEvidence(generation: Long, atSeconds: Int) {
            if (generation != activeSessionGeneration) {
                staleIgnored++
                return
            }
            if (!budget.isTimingAnOutage) return
            // A DNS-held outage is the resolver's, not the link's: a keepalive cannot end it.
            if (heldByDns || heldByEgress) return
            endOutage(atSeconds)
        }

        private fun syntheticConnectionExpired(): ChainedSessionEndCause =
            ChainedSessionEndCause.engineVerdict(WireGuardEngineError.ConnectionExpired)
                ?: error("connectionExpired is always a session-ending cause")

        private fun outcome(): ChainedOutageOutcome = ChainedOutageOutcome(
            phase = phase,
            surfaced = surfacedFor(phase),
            holdingCause = cause,
            surrendered = surrendered,
            surrenderReason = surrenderReason,
            surrenderDisposition = surrenderDisposition,
            mayRearmOnNetworkChange = rearmOnNetworkChange,
            recoveredOnNetworkChange = recoveredOnNetworkChange,
            attemptsStarted = attemptsStarted,
            retryDelaysSeconds = retryDelays.toList(),
            dnsDeadDeclarations = dnsDeclarations,
            egressDeadDeclarations = egressDeclarations,
            staleEventsIgnored = staleIgnored,
            phaseHistory = phases.toList(),
        )
    }

    companion object {
        /** The Android `ChainedApplicationHealth` tunnel-DNS stall window, in seconds. */
        const val DNS_STALL_SECONDS: Int = 21

        /** The Android `ChainedApplicationHealth` egress-dead stall window, in seconds. */
        const val EGRESS_STALL_SECONDS: Int = 21

        /** How long a demand lapse must last before the egress-dead window is abandoned. */
        const val DEMAND_CONTINUITY_SECONDS: Int = 10

        /** A DNS declaration needs two distinct failing names, not one flapping query. */
        const val MIN_DISTINCT_DNS_NAMES: Int = 2

        /** Bounded per-lifecycle so a hostile destination cannot force repeated rebuilds. */
        const val MAX_DNS_DECLARATIONS: Int = 3

        /** Bounded per-lifecycle for the same reason. */
        const val MAX_EGRESS_DECLARATIONS: Int = 3

        /** The canonical phase surfaced to the UI for a phase. */
        fun surfacedFor(phase: ChainedOutagePhase): ChainedSurfacedState = when (phase) {
            ChainedOutagePhase.HEALTHY -> ChainedSurfacedState.NORMAL
            ChainedOutagePhase.OUTAGE,
            ChainedOutagePhase.RECOVERING,
            ChainedOutagePhase.AWAITING_NETWORK,
            -> ChainedSurfacedState.RECOVERING
            ChainedOutagePhase.SUSPENDED -> ChainedSurfacedState.SUSPENDED
            ChainedOutagePhase.OFFLINE -> ChainedSurfacedState.OFFLINE
            ChainedOutagePhase.SURRENDERED,
            ChainedOutagePhase.RETIRED,
            -> ChainedSurfacedState.RETIRED
        }

        /**
         * The canonical control-plane disposition of a hard data-path failure — the Android
         * `ChainedTerminationPolicy` mapping. A peer-socket loss on a handoff is recoverable
         * transport loss, not a terminal failure; the rest stop protection.
         */
        fun dispositionFor(failure: ChainedTerminalFailure): ChainedSurrenderDisposition =
            when (failure) {
                ChainedTerminalFailure.PEER_SOCKET_FAILED ->
                    ChainedSurrenderDisposition.REBUILD_DNS_ONLY
                ChainedTerminalFailure.ENGINE_UNAVAILABLE,
                ChainedTerminalFailure.ENGINE_FAILED,
                ChainedTerminalFailure.TUN_READ_FAILED,
                ChainedTerminalFailure.TUN_WRITE_FAILED,
                -> ChainedSurrenderDisposition.TERMINATE_PROTECTION
            }

        /**
         * Where a resolver tier may egress.
         *
         * T0 is the upstream's own resolver and exists only while chained, carried through the
         * tunnel. T1 is the user's chosen resolver: it may leave on the physical interface in a
         * SPLIT tunnel (the data path to that destination already goes direct, so withholding
         * the name protects nothing) but is forbidden in a FULL tunnel, where the local observer
         * would otherwise see the only remaining signal. T2 is the device fallback and is
         * forbidden whenever chained, in every routing shape.
         */
        fun fallbackEgress(
            tier: ChainedResolverTier,
            chainedLatched: Boolean,
            routingPolicy: ChainedRoutingPolicy?,
        ): ChainedFallbackEgress = when (tier) {
            ChainedResolverTier.T0_UPSTREAM ->
                if (chainedLatched) {
                    ChainedFallbackEgress.THROUGH_TUNNEL_TO_T0
                } else {
                    ChainedFallbackEgress.FORBIDDEN_FAIL_CLOSED
                }
            ChainedResolverTier.T1_SELECTED -> when {
                !chainedLatched -> ChainedFallbackEgress.PHYSICAL_INTERFACE
                routingPolicy == ChainedRoutingPolicy.SPLIT_TUNNEL ->
                    ChainedFallbackEgress.PHYSICAL_INTERFACE
                else -> ChainedFallbackEgress.FORBIDDEN_FAIL_CLOSED
            }
            ChainedResolverTier.T2_FALLBACK ->
                if (chainedLatched) {
                    ChainedFallbackEgress.FORBIDDEN_FAIL_CLOSED
                } else {
                    ChainedFallbackEgress.PHYSICAL_INTERFACE
                }
        }
    }
}

/** The canonical macro-phase of one chained lifecycle. */
enum class ChainedOutagePhase {
    /** Traffic is flowing; no outage is being timed. */
    HEALTHY,

    /** An outage is open and being timed, before any attempt is scheduled. */
    OUTAGE,

    /** A retry attempt is pending or in flight. */
    RECOVERING,

    /** The device is suspended; every one-shot is quiesced. */
    SUSPENDED,

    /** No network at all; there is nothing to blackhole. */
    OFFLINE,

    /** A recoverable surrender is waiting for an eligible network to rebuild DNS-only. */
    AWAITING_NETWORK,

    /** Chained mode gave way; the DNS-only tunnel took over. */
    SURRENDERED,

    /** The lifecycle was torn down. Absorbing. */
    RETIRED,
}

/** What holds an outage open. Recovery evidence is cause-specific. */
enum class ChainedOutageCause {
    /** Sends stopped being answered and the link went silent. */
    LINK_SILENCE,

    /** The send channel stayed saturated past the stall window. */
    CHANNEL_WEDGE,

    /** The tunnelled resolver stopped answering sustained, multi-name queries. */
    TUNNEL_DNS,

    /** The user kept asking for general traffic and no non-DNS byte was forwarded. */
    EGRESS_DEAD,

    /** The engine ended the session (or the ladder synthesized that end on a dead path). */
    SESSION_END,
}

/** The DNS tiers: the existing T1/T2 with the chained upstream's T0 inserted above them. */
enum class ChainedResolverTier {
    /** The chained upstream's own `DNS =`, carried through the tunnel. */
    T0_UPSTREAM,

    /** The user's selected resolver. */
    T1_SELECTED,

    /** The selected device fallback. */
    T2_FALLBACK,
}

/** Where a resolver query may egress. */
enum class ChainedFallbackEgress {
    /** Plain UDP DNS encapsulated through the WireGuard session. */
    THROUGH_TUNNEL_TO_T0,

    /** The physical interface (permitted only where the data path already goes direct). */
    PHYSICAL_INTERFACE,

    /** Never allowed while chained; the query fails closed with SERVFAIL. */
    FORBIDDEN_FAIL_CLOSED,
}

/** What the control plane does when chained mode gives way. */
enum class ChainedSurrenderDisposition {
    /** Rebuild the unchanged DNS-only tunnel; filtering continues. */
    REBUILD_DNS_ONLY,

    /** Publish the terminal failure and tear the runtime down. */
    TERMINATE_PROTECTION,
}

/**
 * The canonical hard data-path failure vocabulary — the foundation mirror of the `vpn`
 * `ChainedRuntimeFailure`, so the fixture can name the terminal/recoverable split without
 * depending on the vpn module.
 */
enum class ChainedTerminalFailure {
    ENGINE_UNAVAILABLE,
    ENGINE_FAILED,
    TUN_READ_FAILED,
    TUN_WRITE_FAILED,
    PEER_SOCKET_FAILED,
}

/** The canonical condition surfaced to the UI, mirroring iOS `ChainedRuntimeCondition`. */
enum class ChainedSurfacedState {
    NORMAL,
    SUSPENDED,
    RECOVERING,
    OFFLINE,
    RETIRED,
}

/** One input to a canonical trace. */
sealed interface ChainedOutageEvent {
    /** Seconds on the caller's monotonic clock. */
    val atSeconds: Int

    /** A send went unanswered for the whole link-stall window, first at the given instant. */
    data class LinkSilence(
        override val atSeconds: Int,
        val firstUnansweredAtSeconds: Int,
    ) : ChainedOutageEvent

    /** The send channel stayed saturated for the whole stall window. */
    data class ChannelWedge(
        override val atSeconds: Int,
        val firstSaturatedAtSeconds: Int,
    ) : ChainedOutageEvent

    /** A tunnel-DNS resolution failed for one more distinct name. */
    data class TunnelDnsUnanswered(
        override val atSeconds: Int,
        val nameKey: String,
    ) : ChainedOutageEvent

    /** The tunnelled resolver served an answer — the only thing that ends a DNS-held outage. */
    data class TunnelDnsAnswered(override val atSeconds: Int) : ChainedOutageEvent

    /** The user sent general (non-DNS) traffic through the tunnel. */
    data class NonDnsDemand(override val atSeconds: Int) : ChainedOutageEvent

    /** A non-DNS byte was delivered on [sessionGeneration] — the only end for egress-dead. */
    data class RealForwarding(
        override val atSeconds: Int,
        val sessionGeneration: Long,
    ) : ChainedOutageEvent

    /** An authenticated peer datagram (keepalive included) arrived on [sessionGeneration]. */
    data class AuthenticatedPeerDatagram(
        override val atSeconds: Int,
        val sessionGeneration: Long,
    ) : ChainedOutageEvent

    /** A decrypted inbound packet was written to the tunnel on [sessionGeneration]. */
    data class InboundData(
        override val atSeconds: Int,
        val sessionGeneration: Long,
    ) : ChainedOutageEvent

    /** The session with [sessionGeneration] ended with [cause]. */
    data class SessionEnded(
        override val atSeconds: Int,
        val cause: ChainedSessionEndCause,
        val sessionGeneration: Long,
    ) : ChainedOutageEvent

    /** The retry delay elapsed and the authorized replacement session was adopted. */
    data class RetryElapsed(override val atSeconds: Int) : ChainedOutageEvent

    /** The per-attempt watchdog fired with no session end reported. */
    data class WatchdogExpired(override val atSeconds: Int) : ChainedOutageEvent

    /** The monitored underlay changed. `false` means there is no network at all. */
    data class PathChanged(
        override val atSeconds: Int,
        val satisfied: Boolean,
    ) : ChainedOutageEvent

    /** The device is suspending. */
    data class Sleep(override val atSeconds: Int) : ChainedOutageEvent

    /** The device woke; only a wake following a [Sleep] this model saw is PAIRED. */
    data class Wake(override val atSeconds: Int) : ChainedOutageEvent

    /** A hard data-path failure, mapped by [ChainedOutageTrace.dispositionFor]. */
    data class TerminalFailure(
        override val atSeconds: Int,
        val failure: ChainedTerminalFailure,
    ) : ChainedOutageEvent

    /** The lifecycle is being torn down. Terminal and absorbing. */
    data class Retire(override val atSeconds: Int) : ChainedOutageEvent
}

/** The canonical outcome of replaying one event sequence. */
data class ChainedOutageOutcome(
    /** The final canonical phase. */
    val phase: ChainedOutagePhase,
    /** The condition surfaced to the UI for [phase]. */
    val surfaced: ChainedSurfacedState,
    /** The cause that opened the open outage, or `null` when none is open. */
    val holdingCause: ChainedOutageCause?,
    /** Whether chained mode gave way. */
    val surrendered: Boolean,
    /** The reconnect-policy surrender reason, or `null` for a hard failure / clean retire. */
    val surrenderReason: ChainedSurrenderReason?,
    /** What the control plane does now, or `null` while chained is still viable. */
    val surrenderDisposition: ChainedSurrenderDisposition?,
    /** Whether a later network change may re-arm chaining. */
    val mayRearmOnNetworkChange: Boolean,
    /** Whether a satisfied path change actually resolved the surrender. */
    val recoveredOnNetworkChange: Boolean,
    /** Attempts authorized and started (the initial session is not an attempt). */
    val attemptsStarted: Int,
    /** Each retry's backoff, in seconds, in the order the policy produced them. */
    val retryDelaysSeconds: List<Int>,
    /** Tunnel-DNS declarations made in this lifecycle (bounded). */
    val dnsDeadDeclarations: Int,
    /** Egress-dead declarations made in this lifecycle (bounded). */
    val egressDeadDeclarations: Int,
    /** Events ignored because their session generation was not the live one. */
    val staleEventsIgnored: Int,
    /** The phase after each transition; the first entry is always [ChainedOutagePhase.HEALTHY]. */
    val phaseHistory: List<ChainedOutagePhase>,
) {
    /** Whether the final state is terminal for the chained lifecycle. */
    val isTerminal: Boolean
        get() = phase == ChainedOutagePhase.RETIRED ||
            surrendered && !mayRearmOnNetworkChange
}
