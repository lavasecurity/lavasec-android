package com.lavasec.app.foundation.health

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Android disposition of iOS #824 (`78a0c3c5`): an observed Device-DNS silence is
 * not a confirmed failure and can never restart the VPN on its own. See
 * `docs/android-predevice-parity-decisions.md` (D1).
 *
 * Android has no Device-DNS recapture restart, so #824's independent-confirmation gate is
 * not ported. What is pinned here is the invariant #824 protects: failures are counted at
 * the terminal query boundary (so a UDP/TCP retry of one leg cannot stack as an independent
 * failure), and three independent failures are required before a reconnect is even
 * considered. The dormant `DEVICE_DNS_RECAPTURE` reason is also refused on any assessment
 * below `NEEDS_RECONNECT`.
 */
class DeviceDnsSilenceRestartGuardTest {
    private val now = 10_000_000L
    private val recapture = TunnelSelfReconnectPolicy.RestartReason.DEVICE_DNS_RECAPTURE

    @Test
    fun `one silent Device DNS leg is recovering and never admits a restart`() {
        val state = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(isConnected = true),
            ProtectionHealthObservation.Decision(deviceFailure(queryId = 1L)),
        )

        assertEquals(1, state.consecutiveResolverFailures)
        assertEquals(ProtectionHealthSeverity.RECOVERING, state.assessment.severity)
        assertEquals(ProtectionHealthAction.TURN_OFF, state.assessment.primaryAction)
        assertEquals(SelfReconnectDecision.NO_ACTION, recaptureDecision(state.assessment))
    }

    @Test
    fun `wire retries of one leg are diagnostics and cannot stack into independent failures`() {
        val afterTerminal = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(isConnected = true),
            ProtectionHealthObservation.Decision(deviceFailure(queryId = 1L)),
        )
        val withRetries = (1..8).fold(afterTerminal) { state, _ ->
            ProtectionHealthReducer.reduce(
                state,
                ProtectionHealthObservation.Health(
                    DnsHealthEvent.ResolverAttemptFailed(
                        resolver = ResolverKind.DEVICE,
                        reason = ResolverFailureReason.TIMEOUT,
                    ),
                ),
            )
        }

        // The wire retries belong to the same failed query: exactly one counted failure.
        assertEquals(1, withRetries.consecutiveResolverFailures)
        assertEquals(ProtectionHealthSeverity.RECOVERING, withRetries.assessment.severity)
        assertEquals(SelfReconnectDecision.NO_ACTION, recaptureDecision(withRetries.assessment))
    }

    @Test
    fun `three independent Device DNS failures are required before a restart is considered`() {
        val state = (1L..3L).fold(ProtectionHealthAccumulator(isConnected = true)) { current, queryId ->
            ProtectionHealthReducer.reduce(
                current,
                ProtectionHealthObservation.Decision(deviceFailure(queryId)),
            )
        }

        assertEquals(3, state.consecutiveResolverFailures)
        assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, state.assessment.severity)
        assertEquals(ProtectionHealthAction.RECONNECT, state.assessment.primaryAction)
        assertEquals(SelfReconnectDecision.RECONNECT, recaptureDecision(state.assessment))
    }

    private fun recaptureDecision(assessment: ProtectionHealthAssessment): SelfReconnectDecision =
        TunnelSelfReconnectPolicy.decision(
            assessment = assessment,
            protectionEnabled = true,
            recoveryArmed = true,
            restartReason = recapture,
            recentReconnectTimesEpochMillis = emptyList(),
            nowEpochMillis = now,
        )

    private fun deviceFailure(queryId: Long) = DnsDecisionEvent(
        id = queryId,
        timestampMillis = 1L,
        normalizedDomain = "must-not-be-retained.example",
        decision = FilterDecision(FilterAction.ALLOW, FilterDecisionReason.DEFAULT_ALLOW),
        resolver = ResolverKind.DEVICE,
        transport = ResolverTransport.DEVICE_UDP,
        error = ResolverFailureReason.TIMEOUT,
    )
}
