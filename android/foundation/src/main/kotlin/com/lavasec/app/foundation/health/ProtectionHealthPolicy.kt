package com.lavasec.app.foundation.health

enum class ProtectionHealthSeverity {
    HEALTHY,
    RECOVERING,
    USING_DEVICE_DNS_FALLBACK,
    USING_ENCRYPTED_FALLBACK,
    DNS_SLOW,
    NETWORK_UNAVAILABLE,
    NEEDS_RECONNECT,
    USING_PLAIN_DNS_FALLBACK,
}

enum class ProtectionHealthAction { TURN_OFF, RECONNECT }

enum class ProtectionFallback { NONE, DEVICE_DNS, ENCRYPTED_DNS, PLAIN_DNS }

data class ProtectionHealthEvidence(
    val isConnected: Boolean,
    val networkAvailable: Boolean = true,
    val consecutiveResolverFailures: Int = 0,
    val consecutivePrimaryRejections: Int = 0,
    val recentResponseLatenciesMillis: List<Long> = emptyList(),
    val activeFallback: ProtectionFallback = ProtectionFallback.NONE,
    val requiresReconnect: Boolean = false,
) {
    init {
        require(consecutiveResolverFailures >= 0) { "consecutiveResolverFailures must be non-negative" }
        require(consecutivePrimaryRejections >= 0) { "consecutivePrimaryRejections must be non-negative" }
        require(recentResponseLatenciesMillis.all { it >= 0 }) { "latencies must be non-negative" }
    }
}

data class ProtectionHealthAssessment(
    val severity: ProtectionHealthSeverity,
    val primaryAction: ProtectionHealthAction,
)

/** Pure connectivity assessment shared by Guard and notification presentation. */
object ProtectionHealthPolicy {
    const val RECONNECT_FAILURE_THRESHOLD = 3
    const val SLOW_RESPONSE_THRESHOLD_MILLIS = 2_500L
    const val SLOW_RESPONSE_COUNT = 3

    fun assess(evidence: ProtectionHealthEvidence): ProtectionHealthAssessment {
        if (!evidence.isConnected) return healthy()
        if (!evidence.networkAvailable) {
            return assessment(ProtectionHealthSeverity.NETWORK_UNAVAILABLE)
        }
        if (evidence.requiresReconnect) {
            return assessment(
                severity = ProtectionHealthSeverity.NEEDS_RECONNECT,
                action = ProtectionHealthAction.RECONNECT,
            )
        }

        // The rejection counter is resolver-identity scoped and may survive a handoff.
        // It becomes actionable only after the new context produces uncovered problem
        // evidence. Device-DNS fallback covers its probe; encrypted fallback must not
        // mask a reachable-but-rejecting primary.
        val hasCurrentContextRejectionProblem =
            evidence.consecutiveResolverFailures > 0 ||
                evidence.activeFallback == ProtectionFallback.ENCRYPTED_DNS
        val hasCurrentContextSustainedRejection =
            hasCurrentContextRejectionProblem &&
                evidence.consecutivePrimaryRejections >= RECONNECT_FAILURE_THRESHOLD
        if (
            evidence.consecutiveResolverFailures >= RECONNECT_FAILURE_THRESHOLD ||
            hasCurrentContextSustainedRejection
        ) {
            return assessment(
                severity = ProtectionHealthSeverity.NEEDS_RECONNECT,
                action = ProtectionHealthAction.RECONNECT,
            )
        }

        when (evidence.activeFallback) {
            ProtectionFallback.PLAIN_DNS ->
                return assessment(ProtectionHealthSeverity.USING_PLAIN_DNS_FALLBACK)
            ProtectionFallback.DEVICE_DNS ->
                return assessment(ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK)
            ProtectionFallback.ENCRYPTED_DNS -> {
                // A reachable-but-rejecting primary is not a covered transition wedge.
                // Keep the fallback carrying traffic, but surface the unresolved evidence
                // until primary recovery clears it or the rejection threshold escalates.
                if (evidence.consecutivePrimaryRejections == 0) {
                    return assessment(ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK)
                }
            }
            ProtectionFallback.NONE -> Unit
        }

        if (hasConsecutiveSlowResponses(evidence.recentResponseLatenciesMillis)) {
            return assessment(
                severity = ProtectionHealthSeverity.DNS_SLOW,
                action = ProtectionHealthAction.RECONNECT,
            )
        }

        val hasCurrentContextPendingRejection =
            hasCurrentContextRejectionProblem && evidence.consecutivePrimaryRejections > 0
        if (evidence.consecutiveResolverFailures > 0 || hasCurrentContextPendingRejection) {
            return assessment(ProtectionHealthSeverity.RECOVERING)
        }

        return healthy()
    }

    private fun hasConsecutiveSlowResponses(latenciesMillis: List<Long>): Boolean =
        latenciesMillis.size >= SLOW_RESPONSE_COUNT &&
            latenciesMillis.takeLast(SLOW_RESPONSE_COUNT).all { it >= SLOW_RESPONSE_THRESHOLD_MILLIS }

    private fun healthy() = assessment(ProtectionHealthSeverity.HEALTHY)

    private fun assessment(
        severity: ProtectionHealthSeverity,
        action: ProtectionHealthAction = ProtectionHealthAction.TURN_OFF,
    ) = ProtectionHealthAssessment(severity, action)
}
