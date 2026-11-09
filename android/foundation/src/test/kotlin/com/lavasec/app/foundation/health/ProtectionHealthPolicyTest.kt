package com.lavasec.app.foundation.health

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionHealthPolicyTest {
    @Test
    fun `fixed plain fallback has its own covered state and terminal failure still escalates`() {
        assertAssessment(ProtectionHealthEvidence(isConnected = true, activeFallback = ProtectionFallback.PLAIN_DNS),
            ProtectionHealthSeverity.USING_PLAIN_DNS_FALLBACK, ProtectionHealthAction.TURN_OFF)
        assertAssessment(ProtectionHealthEvidence(isConnected = true, activeFallback = ProtectionFallback.PLAIN_DNS,
            consecutiveResolverFailures = 3), ProtectionHealthSeverity.NEEDS_RECONNECT, ProtectionHealthAction.RECONNECT)
    }

    @Test
    fun `connected zero-evidence settle remains healthy`() {
        assertAssessment(
            evidence = ProtectionHealthEvidence(isConnected = true),
            severity = ProtectionHealthSeverity.HEALTHY,
            action = ProtectionHealthAction.TURN_OFF,
        )
    }

    @Test
    fun `one uncovered resolver failure is recovering`() {
        assertAssessment(
            evidence = ProtectionHealthEvidence(
                isConnected = true,
                consecutiveResolverFailures = 1,
            ),
            severity = ProtectionHealthSeverity.RECOVERING,
            action = ProtectionHealthAction.TURN_OFF,
        )
    }

    @Test
    fun `three uncovered resolver failures require reconnect`() {
        assertAssessment(
            evidence = ProtectionHealthEvidence(
                isConnected = true,
                consecutiveResolverFailures = 3,
            ),
            severity = ProtectionHealthSeverity.NEEDS_RECONNECT,
            action = ProtectionHealthAction.RECONNECT,
        )
    }

    @Test
    fun `three consecutive responses at least 2500 milliseconds report slow DNS`() {
        assertAssessment(
            evidence = ProtectionHealthEvidence(
                isConnected = true,
                recentResponseLatenciesMillis = listOf(900, 2_500, 3_100, 2_500),
            ),
            severity = ProtectionHealthSeverity.DNS_SLOW,
            action = ProtectionHealthAction.RECONNECT,
        )
        assertAssessment(
            evidence = ProtectionHealthEvidence(
                isConnected = true,
                recentResponseLatenciesMillis = listOf(2_500, 2_499, 4_000),
            ),
            severity = ProtectionHealthSeverity.HEALTHY,
            action = ProtectionHealthAction.TURN_OFF,
        )
    }

    @Test
    fun `working active fallbacks surface their honest carrying state`() {
        assertAssessment(
            evidence = ProtectionHealthEvidence(
                isConnected = true,
                activeFallback = ProtectionFallback.DEVICE_DNS,
            ),
            severity = ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK,
            action = ProtectionHealthAction.TURN_OFF,
        )
        assertAssessment(
            evidence = ProtectionHealthEvidence(
                isConnected = true,
                activeFallback = ProtectionFallback.ENCRYPTED_DNS,
            ),
            severity = ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK,
            action = ProtectionHealthAction.TURN_OFF,
        )
    }

    @Test
    fun `pending primary rejection evidence blocks encrypted coverage until recovery or threshold`() {
        (1 until ProtectionHealthPolicy.RECONNECT_FAILURE_THRESHOLD).forEach { rejectionCount ->
            assertAssessment(
                evidence = ProtectionHealthEvidence(
                    isConnected = true,
                    consecutivePrimaryRejections = rejectionCount,
                    activeFallback = ProtectionFallback.ENCRYPTED_DNS,
                ),
                severity = ProtectionHealthSeverity.RECOVERING,
                action = ProtectionHealthAction.TURN_OFF,
            )
        }
        assertAssessment(
            evidence = ProtectionHealthEvidence(
                isConnected = true,
                consecutivePrimaryRejections = ProtectionHealthPolicy.RECONNECT_FAILURE_THRESHOLD,
                activeFallback = ProtectionFallback.ENCRYPTED_DNS,
            ),
            severity = ProtectionHealthSeverity.NEEDS_RECONNECT,
            action = ProtectionHealthAction.RECONNECT,
        )
    }

    @Test
    fun `retained primary rejection evidence stays dormant without a current context problem`() {
        listOf(
            1,
            ProtectionHealthPolicy.RECONNECT_FAILURE_THRESHOLD,
        ).forEach { retainedRejectionCount ->
            assertAssessment(
                evidence = ProtectionHealthEvidence(
                    isConnected = true,
                    consecutivePrimaryRejections = retainedRejectionCount,
                ),
                severity = ProtectionHealthSeverity.HEALTHY,
                action = ProtectionHealthAction.TURN_OFF,
            )
        }
    }

    @Test
    fun `covered Device DNS fallback does not reactivate retained rejection evidence`() {
        assertAssessment(
            evidence = ProtectionHealthEvidence(
                isConnected = true,
                consecutivePrimaryRejections = ProtectionHealthPolicy.RECONNECT_FAILURE_THRESHOLD,
                activeFallback = ProtectionFallback.DEVICE_DNS,
            ),
            severity = ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK,
            action = ProtectionHealthAction.TURN_OFF,
        )
    }

    @Test
    fun `terminal resolver failures outrank a stale active fallback`() {
        ProtectionFallback.entries.filterNot { it == ProtectionFallback.NONE }.forEach { fallback ->
            assertAssessment(
                evidence = ProtectionHealthEvidence(
                    isConnected = true,
                    consecutiveResolverFailures = 3,
                    activeFallback = fallback,
                ),
                severity = ProtectionHealthSeverity.NEEDS_RECONNECT,
                action = ProtectionHealthAction.RECONNECT,
            )
        }
    }

    @Test
    fun `network loss outranks resolver and fallback evidence`() {
        assertAssessment(
            evidence = ProtectionHealthEvidence(
                isConnected = true,
                networkAvailable = false,
                consecutiveResolverFailures = 3,
                activeFallback = ProtectionFallback.ENCRYPTED_DNS,
            ),
            severity = ProtectionHealthSeverity.NETWORK_UNAVAILABLE,
            action = ProtectionHealthAction.TURN_OFF,
        )
    }

    @Test
    fun `disconnected protection does not request a reconnect`() {
        assertAssessment(
            evidence = ProtectionHealthEvidence(
                isConnected = false,
                networkAvailable = false,
                consecutiveResolverFailures = 3,
            ),
            severity = ProtectionHealthSeverity.HEALTHY,
            action = ProtectionHealthAction.TURN_OFF,
        )
    }

    private fun assertAssessment(
        evidence: ProtectionHealthEvidence,
        severity: ProtectionHealthSeverity,
        action: ProtectionHealthAction,
    ) {
        assertEquals(
            ProtectionHealthAssessment(severity = severity, primaryAction = action),
            ProtectionHealthPolicy.assess(evidence),
        )
    }
}
