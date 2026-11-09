package com.lavasec.app.foundation.health

enum class ProtectionConnectivityNotificationKind(val wireName: String) {
    DNS_SLOW("dns-slow"),
    RECONNECT_NEEDED("reconnect-needed"),
}

enum class ProtectionConnectivityNotificationAction { RECONNECT }

data class ProtectionConnectivityNotification(
    val kind: ProtectionConnectivityNotificationKind,
    val identifier: String,
    val title: String,
    val body: String,
    val action: ProtectionConnectivityNotificationAction,
    val supersededNotificationIds: List<String> = emptyList(),
)

data class ProtectionConnectivityNotificationEvidence(
    val lastResolverFailureAtMillis: Long? = null,
    val lastSlowResponseAtMillis: Long? = null,
    val lastPrimarySuccessAtMillis: Long? = null,
    val lastFallbackSuccessAtMillis: Long? = null,
) {
    init {
        require(
            listOfNotNull(
                lastResolverFailureAtMillis,
                lastSlowResponseAtMillis,
                lastPrimarySuccessAtMillis,
                lastFallbackSuccessAtMillis,
            ).all { it >= 0L },
        )
    }
}

data class ProtectionConnectivityNotificationHistory(
    val lastDeliveredNotificationId: String? = null,
    val lastDeliveredAtMillis: Long? = null,
    val problemDeliveryCooldownUntilMillis: Long? = null,
    val unresolvedProblemNotificationId: String? = null,
    val unresolvedProblemKind: ProtectionConnectivityNotificationKind? = null,
) {
    init {
        require(lastDeliveredAtMillis == null || lastDeliveredAtMillis >= 0L)
        require(problemDeliveryCooldownUntilMillis == null || problemDeliveryCooldownUntilMillis >= 0L)
        require((unresolvedProblemNotificationId == null) == (unresolvedProblemKind == null))
    }

    companion object {
        val EMPTY = ProtectionConnectivityNotificationHistory()
    }
}

/** Pure banner-delivery policy shared by Guard state and Android notification presentation. */
object ProtectionConnectivityNotificationPolicy {
    const val FRESHNESS_WINDOW_MILLIS = 120_000L
    const val MINIMUM_PROBLEM_DELIVERY_INTERVAL_MILLIS = 600_000L
    const val RE_FLAP_GRACE_MILLIS = 60_000L

    fun notification(
        assessment: ProtectionHealthAssessment,
        evidence: ProtectionConnectivityNotificationEvidence,
        history: ProtectionConnectivityNotificationHistory,
        nowMillis: Long,
    ): ProtectionConnectivityNotification? {
        require(nowMillis >= 0L)
        val candidate = candidate(assessment, evidence) ?: return null
        if (!isFresh(candidate.eventAtMillis, nowMillis)) return null

        val identifier = "${candidate.kind.wireName}:${candidate.eventAtMillis}"
        if (identifier == history.lastDeliveredNotificationId) return null

        val unresolvedId = history.unresolvedProblemNotificationId
        val unresolvedKind = history.unresolvedProblemKind
        if (unresolvedId != null && unresolvedKind != null) {
            if (rank(candidate.kind) <= rank(unresolvedKind)) return null
            return candidate.toNotification(
                identifier = identifier,
                supersededNotificationIds = listOf(unresolvedId),
            )
        }

        val cooldownUntil = history.problemDeliveryCooldownUntilMillis
        if (cooldownUntil != null) {
            if (nowMillis < cooldownUntil) return null
        } else {
            val lastDeliveredAt = history.lastDeliveredAtMillis
            if (lastDeliveredAt != null) {
                if (lastDeliveredAt > nowMillis) return null
                if (nowMillis - lastDeliveredAt < MINIMUM_PROBLEM_DELIVERY_INTERVAL_MILLIS) return null
            }
        }
        return candidate.toNotification(identifier)
    }

    /**
     * `USING_ENCRYPTED_FALLBACK` is authoritative coverage evidence, not a speculative
     * activation. The resolver producer emits it only after the fallback answers, while
     * the timestamp projection can arrive later in the same event fan-out. Matching iOS,
     * covered state therefore clears a stale actionable banner without waiting for, or
     * expiring on, [ProtectionConnectivityNotificationEvidence.lastFallbackSuccessAtMillis].
     */
    fun resolvedProblemNotificationIds(
        assessment: ProtectionHealthAssessment,
        evidence: ProtectionConnectivityNotificationEvidence,
        history: ProtectionConnectivityNotificationHistory,
        nowMillis: Long,
    ): List<String> {
        require(nowMillis >= 0L)
        val unresolvedId = history.unresolvedProblemNotificationId ?: return emptyList()
        if (assessment.severity == ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK) {
            return listOf(unresolvedId)
        }
        if (assessment.severity != ProtectionHealthSeverity.HEALTHY) return emptyList()

        val primarySuccessAt = evidence.lastPrimarySuccessAtMillis ?: return emptyList()
        if (!isFresh(primarySuccessAt, nowMillis)) return emptyList()
        val problemAt = unresolvedId.substringAfterLast(':').toLongOrNull() ?: return emptyList()
        if (primarySuccessAt <= problemAt) return emptyList()
        return listOf(unresolvedId)
    }

    /**
     * Shortens the delivery cooldown only when encrypted fallback silently covered a problem.
     * An explicit deadline preserves the exact grace even close to the clock's lower bound.
     */
    fun problemDeliveryCooldownUntilAfterClear(
        assessment: ProtectionHealthAssessment,
        history: ProtectionConnectivityNotificationHistory,
        nowMillis: Long,
    ): Long? {
        require(nowMillis >= 0L)
        if (
            assessment.severity != ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK ||
            history.unresolvedProblemNotificationId == null
        ) {
            return null
        }
        return saturatedAdd(nowMillis, RE_FLAP_GRACE_MILLIS)
    }

    private fun candidate(
        assessment: ProtectionHealthAssessment,
        evidence: ProtectionConnectivityNotificationEvidence,
    ): Candidate? {
        if (assessment.primaryAction != ProtectionHealthAction.RECONNECT) return null
        return when (assessment.severity) {
            ProtectionHealthSeverity.DNS_SLOW -> evidence.lastSlowResponseAtMillis?.let {
                Candidate(
                    kind = ProtectionConnectivityNotificationKind.DNS_SLOW,
                    eventAtMillis = it,
                    title = "Lava DNS is slow",
                    body = "Your DNS provider is responding slowly. Tap to reconnect protection.",
                )
            }

            ProtectionHealthSeverity.NEEDS_RECONNECT -> evidence.lastResolverFailureAtMillis?.let {
                Candidate(
                    kind = ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
                    eventAtMillis = it,
                    title = "Reconnect Lava",
                    body = "DNS is not resolving on this network. Tap to reconnect protection.",
                )
            }

            ProtectionHealthSeverity.HEALTHY,
            ProtectionHealthSeverity.RECOVERING,
            ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK,
            ProtectionHealthSeverity.USING_PLAIN_DNS_FALLBACK,
            ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK,
            ProtectionHealthSeverity.NETWORK_UNAVAILABLE,
            -> null
        }
    }

    private fun rank(kind: ProtectionConnectivityNotificationKind): Int = when (kind) {
        ProtectionConnectivityNotificationKind.DNS_SLOW -> 1
        ProtectionConnectivityNotificationKind.RECONNECT_NEEDED -> 2
    }

    private fun isFresh(timestampMillis: Long, nowMillis: Long): Boolean =
        timestampMillis <= nowMillis && nowMillis - timestampMillis <= FRESHNESS_WINDOW_MILLIS

    private fun saturatedAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private data class Candidate(
        val kind: ProtectionConnectivityNotificationKind,
        val eventAtMillis: Long,
        val title: String,
        val body: String,
    ) {
        fun toNotification(
            identifier: String,
            supersededNotificationIds: List<String> = emptyList(),
        ) = ProtectionConnectivityNotification(
            kind = kind,
            identifier = identifier,
            title = title,
            body = body,
            action = ProtectionConnectivityNotificationAction.RECONNECT,
            supersededNotificationIds = supersededNotificationIds,
        )
    }
}
