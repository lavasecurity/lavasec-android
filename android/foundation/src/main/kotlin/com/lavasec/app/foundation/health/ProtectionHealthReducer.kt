package com.lavasec.app.foundation.health

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverKind

/**
 * Domain-free transient health state. It deliberately retains only bounded numeric/runtime
 * evidence; DNS names remain owned by the Activity logging boundary.
 */
data class ProtectionHealthAccumulator(
    val isConnected: Boolean = false,
    val networkAvailable: Boolean = true,
    val consecutiveResolverFailures: Int = 0,
    // Identity-scoped evidence: preserve it across network/engine churn while the exact
    // configured primary is unchanged. Within a live session, accepted-primary recovery
    // or primary-identity change resets it; disconnect/session reset clears all evidence.
    val consecutivePrimaryRejections: Int = 0,
    val recentResponseLatenciesMillis: List<Long> = emptyList(),
    val activeFallback: ProtectionFallback = ProtectionFallback.NONE,
    val requiresReconnect: Boolean = false,
    val context: ProtectionHealthContext? = null,
    val recentResolverQueryIds: List<Long> = emptyList(),
    val resolverQueryIdHighWatermark: Long? = recentResolverQueryIds.maxOrNull(),
) {
    init {
        require(consecutiveResolverFailures >= 0)
        require(consecutivePrimaryRejections >= 0)
        require(recentResponseLatenciesMillis.size <= MAX_RECENT_RESPONSE_LATENCIES)
        require(recentResponseLatenciesMillis.all { it >= 0L })
        require(recentResolverQueryIds.size <= MAX_RECENT_RESOLVER_QUERY_IDS)
        require(recentResolverQueryIds.all { it >= 0L })
        require(recentResolverQueryIds.distinct().size == recentResolverQueryIds.size)
        if (resolverQueryIdHighWatermark == null) {
            require(recentResolverQueryIds.isEmpty())
        } else {
            require(resolverQueryIdHighWatermark >= 0L)
            val retainedFloor = resolverQueryReplayFloor(resolverQueryIdHighWatermark)
            require(resolverQueryIdHighWatermark in recentResolverQueryIds)
            require(recentResolverQueryIds.all { it in retainedFloor..resolverQueryIdHighWatermark })
        }
    }

    val evidence: ProtectionHealthEvidence
        get() = ProtectionHealthEvidence(
            isConnected = isConnected,
            networkAvailable = networkAvailable,
            consecutiveResolverFailures = consecutiveResolverFailures,
            consecutivePrimaryRejections = consecutivePrimaryRejections,
            recentResponseLatenciesMillis = recentResponseLatenciesMillis,
            activeFallback = activeFallback,
            requiresReconnect = requiresReconnect,
        )

    val assessment: ProtectionHealthAssessment get() = ProtectionHealthPolicy.assess(evidence)

    companion object {
        const val MAX_RECENT_RESPONSE_LATENCIES = ProtectionHealthPolicy.SLOW_RESPONSE_COUNT
        const val MAX_RECENT_RESOLVER_QUERY_IDS = 64
    }
}

sealed interface ProtectionHealthObservation {
    data class ConnectionChanged(val connected: Boolean) : ProtectionHealthObservation
    data class NetworkAvailabilityChanged(val available: Boolean) : ProtectionHealthObservation
    data class Decision(val event: DnsDecisionEvent) : ProtectionHealthObservation
    data class Health(val event: DnsHealthEvent) : ProtectionHealthObservation
    data class ResolverQueryCompleted(val evidence: ResolverQueryHealthEvidence) : ProtectionHealthObservation
}

/** Pure, deterministic reducer suitable for the VPN event sinks and restart fixtures. */
object ProtectionHealthReducer {
    fun reduce(
        current: ProtectionHealthAccumulator,
        contextual: ContextualProtectionHealthObservation,
    ): ProtectionHealthAccumulator {
        val reconciled = reconcileContext(current, contextual.context) ?: return current
        return reduce(reconciled, contextual.observation).copy(context = contextual.context)
    }

    fun reduce(
        current: ProtectionHealthAccumulator,
        observation: ProtectionHealthObservation,
    ): ProtectionHealthAccumulator = when (observation) {
        is ProtectionHealthObservation.ConnectionChanged -> if (observation.connected) {
            current.copy(isConnected = true)
        } else {
            current.copy(
                isConnected = false,
                consecutiveResolverFailures = 0,
                consecutivePrimaryRejections = 0,
                recentResponseLatenciesMillis = emptyList(),
                activeFallback = ProtectionFallback.NONE,
                requiresReconnect = false,
                recentResolverQueryIds = emptyList(),
                resolverQueryIdHighWatermark = null,
            )
        }

        is ProtectionHealthObservation.NetworkAvailabilityChanged -> current.copy(
            networkAvailable = observation.available,
        )

        is ProtectionHealthObservation.Decision -> reduceDecision(current, observation.event)
        is ProtectionHealthObservation.Health -> reduceHealth(current, observation.event)
        is ProtectionHealthObservation.ResolverQueryCompleted -> reduceQuery(current, observation.evidence)
    }

    private fun reconcileContext(
        current: ProtectionHealthAccumulator,
        incoming: ProtectionHealthContext,
    ): ProtectionHealthAccumulator? {
        val existing = current.context ?: return current.copy(context = incoming)
        if (incoming.sessionGeneration < existing.sessionGeneration) return null
        if (incoming.sessionGeneration > existing.sessionGeneration) {
            return current.copy(
                isConnected = false,
                networkAvailable = true,
                consecutiveResolverFailures = 0,
                consecutivePrimaryRejections = 0,
                recentResponseLatenciesMillis = emptyList(),
                activeFallback = ProtectionFallback.NONE,
                requiresReconnect = false,
                context = incoming,
                recentResolverQueryIds = emptyList(),
                resolverQueryIdHighWatermark = null,
            )
        }

        if (incoming.networkGeneration < existing.networkGeneration) return null
        val networkAdvanced = incoming.networkGeneration > existing.networkGeneration
        if (!networkAdvanced && incoming.engineGeneration < existing.engineGeneration) return null
        val engineAdvanced = incoming.engineGeneration > existing.engineGeneration
        val primaryIdentityChanged = incoming.primaryResolverIdentity != existing.primaryResolverIdentity
        if (
            !networkAdvanced &&
            !engineAdvanced &&
            (primaryIdentityChanged ||
                incoming.fallbackCoverage != existing.fallbackCoverage)
        ) {
            return null
        }
        if (
            !networkAdvanced &&
            !engineAdvanced &&
            incoming.observationSequence <= existing.observationSequence
        ) {
            return null
        }

        var next = current
        if (networkAdvanced) {
            // Network-episode evidence resets here. Primary-rejection evidence is scoped
            // to resolver identity and intentionally survives a path handoff.
            next = next.copy(
                networkAvailable = true,
                consecutiveResolverFailures = 0,
                recentResponseLatenciesMillis = emptyList(),
                activeFallback = ProtectionFallback.NONE,
                recentResolverQueryIds = emptyList(),
                resolverQueryIdHighWatermark = null,
            )
        }
        if (engineAdvanced) {
            next = next.copy(
                consecutiveResolverFailures = 0,
                recentResponseLatenciesMillis = emptyList(),
                activeFallback = ProtectionFallback.NONE,
                requiresReconnect = false,
                recentResolverQueryIds = emptyList(),
                resolverQueryIdHighWatermark = null,
            )
        }
        if (primaryIdentityChanged) {
            next = next.copy(consecutivePrimaryRejections = 0)
        }
        return next.copy(context = incoming)
    }

    private fun reduceQuery(
        current: ProtectionHealthAccumulator,
        evidence: ResolverQueryHealthEvidence,
    ): ProtectionHealthAccumulator {
        val highWatermark = current.resolverQueryIdHighWatermark
        if (highWatermark != null) {
            val retainedFloor = resolverQueryReplayFloor(highWatermark)
            if (evidence.queryId < retainedFloor || evidence.queryId in current.recentResolverQueryIds) {
                return current
            }
        }
        val nextHighWatermark = maxOf(highWatermark ?: evidence.queryId, evidence.queryId)
        val nextRetainedFloor = resolverQueryReplayFloor(nextHighWatermark)
        val recentQueryIds = (current.recentResolverQueryIds + evidence.queryId)
            .filter { it >= nextRetainedFloor }
        val primaryObservation = evidence.tierObservations.firstOrNull()
        val neutral = primaryObservation?.outcome == ResolverTierDiagnosticOutcome.NOT_ATTEMPTED || primaryObservation?.peerLivenessOnly == true
        val rejectedReply = primaryObservation?.outcome == ResolverTierDiagnosticOutcome.ANSWERED && primaryObservation.peerLivenessOnly != true
        val reduced = if (neutral) {
            if (primaryObservation?.peerLivenessOnly == true) current.copy(networkAvailable = true, consecutiveResolverFailures = 0)
            else if (evidence.successfulFallback != ProtectionFallback.NONE) current.copy(networkAvailable = true, activeFallback = evidence.successfulFallback, consecutiveResolverFailures = 0)
            else current
        } else if (rejectedReply && evidence.primaryOutcome == PrimaryResolverOutcome.ACCEPTED) {
            current.copy(networkAvailable = true, consecutiveResolverFailures = 0,
                consecutivePrimaryRejections = incrementSaturated(current.consecutivePrimaryRejections))
        } else when (evidence.primaryOutcome) {
            PrimaryResolverOutcome.ACCEPTED -> {
                val latency = requireNotNull(evidence.latencyMillis)
                val recent = (current.recentResponseLatenciesMillis + latency)
                    .takeLast(ProtectionHealthAccumulator.MAX_RECENT_RESPONSE_LATENCIES)
                current.copy(
                    networkAvailable = true,
                    consecutiveResolverFailures = 0,
                    consecutivePrimaryRejections = 0,
                    recentResponseLatenciesMillis = recent,
                    activeFallback = ProtectionFallback.NONE,
                )
            }

            PrimaryResolverOutcome.REJECTED -> current.copy(
                networkAvailable = true,
                consecutiveResolverFailures = if (evidence.successfulFallback == ProtectionFallback.NONE) {
                    incrementSaturated(current.consecutiveResolverFailures)
                } else {
                    0
                },
                consecutivePrimaryRejections = incrementSaturated(current.consecutivePrimaryRejections),
                recentResponseLatenciesMillis = emptyList(),
                activeFallback = evidence.successfulFallback.takeUnless { it == ProtectionFallback.NONE }
                    ?: current.activeFallback,
            )

            PrimaryResolverOutcome.FAILED -> {
                val restartWorthy = requireNotNull(evidence.primaryFailureReason).isRestartWorthy()
                val fallbackSucceeded = evidence.successfulFallback != ProtectionFallback.NONE
                current.copy(
                    networkAvailable = current.networkAvailable || fallbackSucceeded,
                    consecutiveResolverFailures = if (
                        fallbackSucceeded
                    ) {
                        0
                    } else if (restartWorthy) {
                        incrementSaturated(current.consecutiveResolverFailures)
                    } else {
                        current.consecutiveResolverFailures
                    },
                    recentResponseLatenciesMillis = if (restartWorthy) {
                        emptyList()
                    } else {
                        current.recentResponseLatenciesMillis
                    },
                    activeFallback = evidence.successfulFallback.takeUnless { it == ProtectionFallback.NONE }
                        ?: current.activeFallback,
                )
            }
        }
        return reduced.copy(
            recentResolverQueryIds = recentQueryIds,
            resolverQueryIdHighWatermark = nextHighWatermark,
        )
    }

    private fun reduceDecision(
        current: ProtectionHealthAccumulator,
        event: DnsDecisionEvent,
    ): ProtectionHealthAccumulator {
        // A local block/answer has no resolver evidence and must not hide a resolver failure.
        if (event.resolver == null && event.latencyMillis == null && event.error == null) return current
        if (event.error?.isRestartWorthy() == true) {
            // The terminal decision owns failure counting. A query may try more than one endpoint,
            // but it is still exactly one failed DNS outcome from the user's perspective.
            return current.copy(
                consecutiveResolverFailures = incrementSaturated(current.consecutiveResolverFailures),
                recentResponseLatenciesMillis = emptyList(),
            )
        }
        // Lifecycle cancellation, a superseded epoch, and local admission overload are not
        // evidence that the active resolver is wedged. Preserve prior evidence, but never let
        // repeated churn manufacture a reconnect recommendation.
        if (event.error != null) return current
        val latency = event.latencyMillis ?: return current
        val recent = (current.recentResponseLatenciesMillis + latency)
            .takeLast(ProtectionHealthAccumulator.MAX_RECENT_RESPONSE_LATENCIES)
        return current.copy(
            networkAvailable = true,
            consecutiveResolverFailures = 0,
            recentResponseLatenciesMillis = recent,
        )
    }

    private fun reduceHealth(
        current: ProtectionHealthAccumulator,
        event: DnsHealthEvent,
    ): ProtectionHealthAccumulator = when (event) {
        // Per-endpoint attempt failures remain useful diagnostics, but cannot advance the terminal
        // query streak: a later endpoint in the same query may have answered successfully.
        is DnsHealthEvent.ResolverAttemptFailed -> current

        is DnsHealthEvent.FallbackActivated -> current.copy(
            activeFallback = event.resolver.asFallback(),
        )

        is DnsHealthEvent.ResolverRecovered -> current.copy(
            consecutiveResolverFailures = 0,
            consecutivePrimaryRejections = if (event.resolver == ResolverKind.DEVICE) {
                0
            } else {
                current.consecutivePrimaryRejections
            },
            recentResponseLatenciesMillis = emptyList(),
            activeFallback = ProtectionFallback.NONE,
        )

        is DnsHealthEvent.ProtectionUnavailable -> when (event.reason) {
            ProtectionUnavailableReason.NetworkUnavailable -> current.copy(networkAvailable = false)
            is ProtectionUnavailableReason.Artifact,
            is ProtectionUnavailableReason.ResolverPlan,
            ProtectionUnavailableReason.RuntimeUnavailable,
            -> current.copy(requiresReconnect = true)
        }

        is DnsHealthEvent.TunWriteFailed -> when (event.reason) {
            com.lavasec.app.foundation.dns.TunWriteFailureReason.STALE_SESSION -> current
            com.lavasec.app.foundation.dns.TunWriteFailureReason.DESCRIPTOR_CLOSED,
            com.lavasec.app.foundation.dns.TunWriteFailureReason.WRITE_FAILED,
            -> current.copy(requiresReconnect = true)
        }
    }

    private fun ResolverKind.asFallback(): ProtectionFallback = when (this) {
        ResolverKind.PLAIN_DNS -> ProtectionFallback.PLAIN_DNS
        ResolverKind.DEVICE -> ProtectionFallback.DEVICE_DNS
        ResolverKind.MULLVAD_DOH -> ProtectionFallback.ENCRYPTED_DNS
        ResolverKind.WIREGUARD_T0 -> ProtectionFallback.NONE
    }

    private fun com.lavasec.app.foundation.dns.ResolverFailureReason.isRestartWorthy(): Boolean = when (this) {
        com.lavasec.app.foundation.dns.ResolverFailureReason.TIMEOUT,
        com.lavasec.app.foundation.dns.ResolverFailureReason.PROTECT_FAILED,
        com.lavasec.app.foundation.dns.ResolverFailureReason.BIND_FAILED,
        com.lavasec.app.foundation.dns.ResolverFailureReason.SOCKET_FAILED,
        com.lavasec.app.foundation.dns.ResolverFailureReason.SEND_FAILED,
        com.lavasec.app.foundation.dns.ResolverFailureReason.RECEIVE_FAILED,
        com.lavasec.app.foundation.dns.ResolverFailureReason.MISMATCH_LIMIT,
        com.lavasec.app.foundation.dns.ResolverFailureReason.INVALID_RESPONSE,
        com.lavasec.app.foundation.dns.ResolverFailureReason.OVERSIZED_RESPONSE,
        com.lavasec.app.foundation.dns.ResolverFailureReason.HTTP_STATUS,
        com.lavasec.app.foundation.dns.ResolverFailureReason.HTTP_CONTENT_TYPE,
        com.lavasec.app.foundation.dns.ResolverFailureReason.TLS_FAILED,
        -> true

        com.lavasec.app.foundation.dns.ResolverFailureReason.BACKED_OFF,
        com.lavasec.app.foundation.dns.ResolverFailureReason.CANCELLED,
        com.lavasec.app.foundation.dns.ResolverFailureReason.STALE_EPOCH,
        com.lavasec.app.foundation.dns.ResolverFailureReason.OVERLOADED,
        -> false
    }

    private fun incrementSaturated(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1
}

/**
 * Retains exact replay identity for the latest numeric query-ID window. Distinct completions may
 * arrive out of order inside the window; older IDs are ignored because their identity was evicted.
 */
private fun resolverQueryReplayFloor(highWatermark: Long): Long =
    maxOf(
        0L,
        highWatermark - (ProtectionHealthAccumulator.MAX_RECENT_RESOLVER_QUERY_IDS - 1L),
    )
