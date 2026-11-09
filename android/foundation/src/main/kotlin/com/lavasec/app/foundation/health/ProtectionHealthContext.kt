package com.lavasec.app.foundation.health

import com.lavasec.app.foundation.dns.ResolverFailureReason

/** Configured fallback coverage is identity, not evidence that the fallback is active. */
enum class ResolverFallbackCoverage { NONE, DEVICE_DNS, ENCRYPTED_DNS, PLAIN_DNS }

/**
 * Exact configured primary identity, not merely its resolver kind. Device-DNS producers
 * must include the normalized selected address set so a new network's different resolver
 * cannot inherit identity-scoped evidence.
 */
@JvmInline
value class PrimaryResolverIdentity(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/**
 * Runtime identity carried by every ordered health observation.
 *
 * Generations prevent delayed work from a superseded session, network, or engine from
 * repainting the current health state. [observationSequence] provides a total ordering fence
 * within one generation tuple; [observedAtMillis] remains event-time metadata only.
 */
data class ProtectionHealthContext(
    val sessionGeneration: Long,
    val networkGeneration: Long,
    val engineGeneration: Long,
    val observationSequence: Long,
    val observedAtMillis: Long,
    val primaryResolverIdentity: PrimaryResolverIdentity,
    val fallbackCoverage: ResolverFallbackCoverage,
) {
    init {
        require(sessionGeneration >= 0L)
        require(networkGeneration >= 0L)
        require(engineGeneration >= 0L)
        require(observationSequence >= 0L)
        require(observedAtMillis >= 0L)
    }
}

data class ContextualProtectionHealthObservation(
    val context: ProtectionHealthContext,
    val observation: ProtectionHealthObservation,
)

enum class PrimaryResolverOutcome {
    ACCEPTED,

    /**
     * The primary returned a response that failed acceptance. A producer must never use
     * this for an endpoint skipped because of deadline/backoff or for a missing attempt.
     */
    REJECTED,
    FAILED,
}

/** One terminal user-query outcome; endpoint-attempt diagnostics are intentionally separate. */
data class ResolverQueryHealthEvidence(
    val queryId: Long,
    val primaryOutcome: PrimaryResolverOutcome,
    val primaryFailureReason: ResolverFailureReason? = null,
    val successfulFallback: ProtectionFallback = ProtectionFallback.NONE,
    val latencyMillis: Long? = null,
    val tierObservations: List<ResolverTierDiagnosticObservation> = emptyList(),
    val responseLatencyMillis: Long? = null,
    val httpProtocol: DnsHttpProtocol? = null,
    val fallbackObservations: List<DnsFallbackObservation> = emptyList(),
) {
    init {
        require(fallbackObservations.size <= 2)
        require(tierObservations.size <= 3 && tierObservations.map { it.tier }.distinct().size == tierObservations.size)
        require(responseLatencyMillis == null || responseLatencyMillis >= 0L)
        require(queryId >= 0L)
        require(latencyMillis == null || latencyMillis >= 0L)
        require(primaryOutcome == PrimaryResolverOutcome.FAILED || primaryFailureReason == null)
        require(primaryOutcome != PrimaryResolverOutcome.FAILED || primaryFailureReason != null)
        require(primaryOutcome != PrimaryResolverOutcome.ACCEPTED || successfulFallback == ProtectionFallback.NONE)
        require((primaryOutcome == PrimaryResolverOutcome.ACCEPTED) == (latencyMillis != null))
    }

    companion object {
        fun primaryAccepted(queryId: Long, latencyMillis: Long) = ResolverQueryHealthEvidence(
            queryId = queryId,
            primaryOutcome = PrimaryResolverOutcome.ACCEPTED,
            latencyMillis = latencyMillis,
        )

        fun primaryRejectedWithFallback(
            queryId: Long,
            fallback: ProtectionFallback,
        ): ResolverQueryHealthEvidence {
            require(fallback != ProtectionFallback.NONE)
            return ResolverQueryHealthEvidence(
                queryId = queryId,
                primaryOutcome = PrimaryResolverOutcome.REJECTED,
                successfulFallback = fallback,
            )
        }

        fun primaryFailed(
            queryId: Long,
            reason: ResolverFailureReason,
            successfulFallback: ProtectionFallback = ProtectionFallback.NONE,
        ) = ResolverQueryHealthEvidence(
            queryId = queryId,
            primaryOutcome = PrimaryResolverOutcome.FAILED,
            primaryFailureReason = reason,
            successfulFallback = successfulFallback,
        )
    }
}
