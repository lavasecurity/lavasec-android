package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.*

/** Bounded session-only counters. No query names, endpoints, payloads or persisted history. */
data class VpnSessionDiagnostics(
    val startedAtMillis: Long? = null,
    val networkChanges: Long = 0,
    val lastNetworkChangeAtMillis: Long? = null,
    val runtimeResets: Long = 0,
    val lastRuntimeResetAtMillis: Long? = null,
    val upstreamSuccesses: Long = 0,
    val upstreamFailures: Long = 0,
    val lastSuccessAtMillis: Long? = null,
    val lastFailureAtMillis: Long? = null,
    val lastFailure: ResolverFailureReason? = null,
    val timeouts: Long = 0,
    val tcpResponses: Long = 0,
    val deviceFallbacks: Long = 0,
    val smokeProbes: Long = 0,
    val smokeSuccesses: Long = 0,
    val lastResolver: ResolverKind? = null,
    val lastTransport: ResolverTransport? = null,
    val lastHttpProtocol: DnsHttpProtocol? = null,
    val responseLatenciesMillis: List<Long> = emptyList(),
    val tiers: Map<Int, VpnTierDiagnostics> = emptyMap(),
) {
    internal fun installed(previous: VpnHealthEngineIdentity?, next: VpnHealthEngineIdentity, now: Long): VpnSessionDiagnostics = copy(
        startedAtMillis = startedAtMillis ?: now,
        networkChanges = networkChanges.incrementIf(previous != null && previous.networkGeneration != next.networkGeneration),
        lastNetworkChangeAtMillis = if (previous != null && previous.networkGeneration != next.networkGeneration) now else lastNetworkChangeAtMillis,
        runtimeResets = runtimeResets.incrementIf(previous != null),
        lastRuntimeResetAtMillis = if (previous != null) now else lastRuntimeResetAtMillis,
        // Tier observations belong to the installed route selection, not its predecessor.
        tiers = emptyMap(),
    )

    internal fun query(evidence: ResolverQueryHealthEvidence, now: Long, smoke: Boolean = false): VpnSessionDiagnostics {
        val served = evidence.tierObservations.lastOrNull { it.outcome == ResolverTierDiagnosticOutcome.SERVED }
        val primaryServed = evidence.primaryOutcome == PrimaryResolverOutcome.ACCEPTED &&
            (evidence.tierObservations.isEmpty() || evidence.tierObservations.first().outcome == ResolverTierDiagnosticOutcome.SERVED)
        val success = primaryServed || evidence.successfulFallback != ProtectionFallback.NONE
        val failed = evidence.tierObservations.lastOrNull { it.outcome == ResolverTierDiagnosticOutcome.FAILED }
        val abandoned = failed == null && (evidence.tierObservations.any { it.peerLivenessOnly || it.outcome == ResolverTierDiagnosticOutcome.NOT_ATTEMPTED } || evidence.primaryFailureReason in setOf(ResolverFailureReason.CANCELLED, ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.OVERLOADED, ResolverFailureReason.BACKED_OFF))
        val latency = evidence.responseLatencyMillis ?: evidence.latencyMillis
        return copy(
            upstreamSuccesses = upstreamSuccesses.incrementIf(!smoke && success),
            upstreamFailures = upstreamFailures.incrementIf(!smoke && !success && !abandoned),
            lastSuccessAtMillis = if (!smoke && success) now else lastSuccessAtMillis,
            lastFailureAtMillis = if (!smoke && !success && !abandoned) now else lastFailureAtMillis,
            lastFailure = if (!smoke && !success && !abandoned) failed?.failure ?: evidence.primaryFailureReason else lastFailure,
            timeouts = timeouts + if (smoke) 0L else evidence.tierObservations.count { it.failure == ResolverFailureReason.TIMEOUT }.toLong(),
            tcpResponses = tcpResponses.incrementIf(!smoke && served?.transport in setOf(ResolverTransport.DEVICE_TCP, ResolverTransport.PLAIN_TCP)),
            deviceFallbacks = deviceFallbacks.incrementIf(!smoke && evidence.successfulFallback == ProtectionFallback.DEVICE_DNS),
            smokeProbes = smokeProbes.incrementIf(smoke),
            smokeSuccesses = smokeSuccesses.incrementIf(smoke && success),
            lastResolver = if (smoke) lastResolver else served?.resolver ?: failed?.resolver ?: lastResolver,
            lastTransport = if (smoke) lastTransport else served?.transport ?: lastTransport,
            lastHttpProtocol = if (!smoke && served != null) evidence.httpProtocol else lastHttpProtocol,
            responseLatenciesMillis = if (!smoke && success && latency != null) (responseLatenciesMillis + latency).takeLast(128) else responseLatenciesMillis,
            tiers = tiers.toMutableMap().apply { evidence.tierObservations.forEach { observation ->
                this[observation.tier] = (this[observation.tier] ?: VpnTierDiagnostics()).observe(observation, now)
            } }.toMap(),
        )
    }
}

data class VpnTierDiagnostics(
    val latest: ResolverTierDiagnosticObservation? = null,
    val observedAtMillis: Long? = null,
    val latestAdmission: ResolverTierDiagnosticObservation? = null,
    val admissionObservedAtMillis: Long? = null,
    val attempts: Long = 0,
    val served: Long = 0,
    val answered: Long = 0,
    val failures: Long = 0,
    val notAttempted: Long = 0,
    val failureStreak: Long = 0,
    val rejectedStreak: Long = 0,
    val lastFailure: ResolverFailureReason? = null,
) {
    internal fun observe(value: ResolverTierDiagnosticObservation, now: Long): VpnTierDiagnostics {
        val outcome = value.outcome
        if (outcome == ResolverTierDiagnosticOutcome.NOT_ATTEMPTED) return copy(
            latestAdmission = value, admissionObservedAtMillis = now, notAttempted = notAttempted.incrementIf(true))
        return copy(latestAdmission = null, admissionObservedAtMillis = null, latest = value, observedAtMillis = now,
            attempts = attempts.incrementIf(outcome != ResolverTierDiagnosticOutcome.NOT_ATTEMPTED),
            served = served.incrementIf(outcome == ResolverTierDiagnosticOutcome.SERVED),
            answered = answered.incrementIf(outcome == ResolverTierDiagnosticOutcome.ANSWERED),
            failures = failures.incrementIf(outcome == ResolverTierDiagnosticOutcome.FAILED),
            notAttempted = notAttempted.incrementIf(outcome == ResolverTierDiagnosticOutcome.NOT_ATTEMPTED),
            failureStreak = when (outcome) { ResolverTierDiagnosticOutcome.FAILED -> failureStreak.incrementIf(true); ResolverTierDiagnosticOutcome.NOT_ATTEMPTED -> failureStreak; else -> 0 },
            rejectedStreak = when (outcome) { ResolverTierDiagnosticOutcome.ANSWERED -> if (value.peerLivenessOnly) rejectedStreak else rejectedStreak.incrementIf(true); ResolverTierDiagnosticOutcome.SERVED -> 0; else -> rejectedStreak },
            lastFailure = if (value.peerLivenessOnly) lastFailure else value.failure ?: lastFailure)
    }
}
private fun Long.incrementIf(condition: Boolean): Long = if (condition && this < Long.MAX_VALUE) this + 1 else this
