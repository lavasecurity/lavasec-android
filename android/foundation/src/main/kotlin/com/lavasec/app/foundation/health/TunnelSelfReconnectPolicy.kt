package com.lavasec.app.foundation.health

enum class SelfReconnectDecision { RECONNECT, THROTTLED, NO_ACTION }

/**
 * Conservative last-resort recovery for resolver wedges and Device-DNS recapture.
 *
 * Android dispatches an explicit, foreground-service reload rather than relying on iOS
 * Connect-On-Demand, so [recoveryArmed] means the current logical protection session is both
 * desired and runtime-confirmed active. The cooldown and persisted attempt window intentionally
 * mirror iOS.
 */
object TunnelSelfReconnectPolicy {
    enum class RestartReason {
        WEDGE,
        DEVICE_DNS_RECAPTURE,
    }

    const val COOLDOWN_MILLIS = 90_000L
    const val ATTEMPT_WINDOW_MILLIS = 600_000L
    const val WEDGE_MAX_ATTEMPTS_PER_WINDOW = 2
    const val DEVICE_DNS_RECAPTURE_MAX_ATTEMPTS_PER_WINDOW = 3

    fun prunedAttemptTimes(
        times: List<Long>,
        nowEpochMillis: Long,
    ): List<Long> {
        require(nowEpochMillis >= 0L)
        return times.mapNotNull { timestamp ->
            if (timestamp < 0L) return@mapNotNull null
            val clamped = minOf(timestamp, nowEpochMillis)
            clamped.takeIf { nowEpochMillis - it < ATTEMPT_WINDOW_MILLIS }
        }
    }

    fun decision(
        assessment: ProtectionHealthAssessment,
        protectionEnabled: Boolean,
        recoveryArmed: Boolean,
        restartReason: RestartReason,
        recentReconnectTimesEpochMillis: List<Long>,
        nowEpochMillis: Long,
    ): SelfReconnectDecision {
        if (
            assessment.severity != ProtectionHealthSeverity.NEEDS_RECONNECT ||
            assessment.primaryAction != ProtectionHealthAction.RECONNECT
        ) {
            return SelfReconnectDecision.NO_ACTION
        }
        if (!protectionEnabled || !recoveryArmed) return SelfReconnectDecision.NO_ACTION

        val recent = prunedAttemptTimes(recentReconnectTimesEpochMillis, nowEpochMillis)
        if (recent.maxOrNull()?.let { nowEpochMillis - it < COOLDOWN_MILLIS } == true) {
            return SelfReconnectDecision.THROTTLED
        }
        val maximumAttempts = when (restartReason) {
            RestartReason.WEDGE -> WEDGE_MAX_ATTEMPTS_PER_WINDOW
            RestartReason.DEVICE_DNS_RECAPTURE -> DEVICE_DNS_RECAPTURE_MAX_ATTEMPTS_PER_WINDOW
        }
        if (recent.size >= maximumAttempts) return SelfReconnectDecision.THROTTLED
        return SelfReconnectDecision.RECONNECT
    }
}
