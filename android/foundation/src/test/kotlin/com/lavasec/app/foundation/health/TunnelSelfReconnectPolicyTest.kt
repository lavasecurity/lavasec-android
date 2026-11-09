package com.lavasec.app.foundation.health

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelSelfReconnectPolicyTest {
    private val now = 100_000_000L
    private val reconnectAssessment = ProtectionHealthAssessment(
        severity = ProtectionHealthSeverity.NEEDS_RECONNECT,
        primaryAction = ProtectionHealthAction.RECONNECT,
    )

    @Test
    fun `wedge reconnect requires exact assessment and armed protection`() {
        assertEquals(
            SelfReconnectDecision.RECONNECT,
            decision(),
        )
        assertEquals(
            SelfReconnectDecision.NO_ACTION,
            decision(assessment = reconnectAssessment.copy(severity = ProtectionHealthSeverity.DNS_SLOW)),
        )
        assertEquals(SelfReconnectDecision.NO_ACTION, decision(protectionEnabled = false))
        assertEquals(SelfReconnectDecision.NO_ACTION, decision(recoveryArmed = false))
    }

    @Test
    fun `restart reasons have distinct ceilings over one shared attempt history`() {
        val twoSharedAttempts = listOf(
            now - TunnelSelfReconnectPolicy.COOLDOWN_MILLIS - 200_000L,
            now - TunnelSelfReconnectPolicy.COOLDOWN_MILLIS - 100_000L,
        )
        val threeSharedAttempts = twoSharedAttempts + (
            now - TunnelSelfReconnectPolicy.COOLDOWN_MILLIS - 50_000L
        )

        assertEquals(
            SelfReconnectDecision.THROTTLED,
            decision(
                restartReason = TunnelSelfReconnectPolicy.RestartReason.WEDGE,
                recent = twoSharedAttempts,
            ),
        )
        assertEquals(
            SelfReconnectDecision.RECONNECT,
            decision(
                restartReason = TunnelSelfReconnectPolicy.RestartReason.DEVICE_DNS_RECAPTURE,
                recent = twoSharedAttempts,
            ),
        )
        assertEquals(
            SelfReconnectDecision.THROTTLED,
            decision(
                restartReason = TunnelSelfReconnectPolicy.RestartReason.DEVICE_DNS_RECAPTURE,
                recent = threeSharedAttempts,
            ),
        )
    }

    @Test
    fun `cooldown and sliding window bound both restart reasons`() {
        assertEquals(
            SelfReconnectDecision.THROTTLED,
            decision(
                restartReason = TunnelSelfReconnectPolicy.RestartReason.WEDGE,
                recent = listOf(now - TunnelSelfReconnectPolicy.COOLDOWN_MILLIS + 1L),
            ),
        )
        assertEquals(
            SelfReconnectDecision.THROTTLED,
            decision(
                restartReason = TunnelSelfReconnectPolicy.RestartReason.DEVICE_DNS_RECAPTURE,
                recent = listOf(now - TunnelSelfReconnectPolicy.COOLDOWN_MILLIS + 1L),
            ),
        )
        assertEquals(
            SelfReconnectDecision.RECONNECT,
            decision(recent = listOf(now - TunnelSelfReconnectPolicy.COOLDOWN_MILLIS - 1L)),
        )
        assertEquals(
            SelfReconnectDecision.THROTTLED,
            decision(
                recent = listOf(
                    now - TunnelSelfReconnectPolicy.COOLDOWN_MILLIS - 200_000L,
                    now - TunnelSelfReconnectPolicy.COOLDOWN_MILLIS - 100_000L,
                ),
            ),
        )
        assertEquals(
            SelfReconnectDecision.RECONNECT,
            decision(
                recent = listOf(
                    now - TunnelSelfReconnectPolicy.ATTEMPT_WINDOW_MILLIS - 100_000L,
                    now - TunnelSelfReconnectPolicy.ATTEMPT_WINDOW_MILLIS - 50_000L,
                ),
            ),
        )
    }

    @Test
    fun `device DNS recapture still rejects disabled unarmed and non reconnect health`() {
        val recapture = TunnelSelfReconnectPolicy.RestartReason.DEVICE_DNS_RECAPTURE

        assertEquals(
            SelfReconnectDecision.NO_ACTION,
            decision(restartReason = recapture, protectionEnabled = false),
        )
        assertEquals(
            SelfReconnectDecision.NO_ACTION,
            decision(restartReason = recapture, recoveryArmed = false),
        )
        assertEquals(
            SelfReconnectDecision.NO_ACTION,
            decision(
                restartReason = recapture,
                assessment = reconnectAssessment.copy(
                    severity = ProtectionHealthSeverity.DNS_SLOW,
                    primaryAction = ProtectionHealthAction.RECONNECT,
                ),
            ),
        )
        assertEquals(
            SelfReconnectDecision.NO_ACTION,
            decision(
                restartReason = recapture,
                assessment = reconnectAssessment.copy(
                    primaryAction = ProtectionHealthAction.TURN_OFF,
                ),
            ),
        )
    }

    @Test
    fun `pruning drops stale attempts and clamps future timestamps to now`() {
        assertEquals(
            listOf(now - 60_000L, now),
            TunnelSelfReconnectPolicy.prunedAttemptTimes(
                times = listOf(
                    now - 60_000L,
                    now - TunnelSelfReconnectPolicy.ATTEMPT_WINDOW_MILLIS - 1L,
                    now + 60_000L,
                ),
                nowEpochMillis = now,
            ),
        )
        assertEquals(
            SelfReconnectDecision.THROTTLED,
            decision(recent = listOf(now + 120_000L, now + 240_000L)),
        )
    }

    private fun decision(
        assessment: ProtectionHealthAssessment = reconnectAssessment,
        protectionEnabled: Boolean = true,
        recoveryArmed: Boolean = true,
        restartReason: TunnelSelfReconnectPolicy.RestartReason =
            TunnelSelfReconnectPolicy.RestartReason.WEDGE,
        recent: List<Long> = emptyList(),
    ): SelfReconnectDecision = TunnelSelfReconnectPolicy.decision(
        assessment = assessment,
        protectionEnabled = protectionEnabled,
        recoveryArmed = recoveryArmed,
        restartReason = restartReason,
        recentReconnectTimesEpochMillis = recent,
        nowEpochMillis = now,
    )
}
