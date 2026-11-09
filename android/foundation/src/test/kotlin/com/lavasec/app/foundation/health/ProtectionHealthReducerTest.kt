package com.lavasec.app.foundation.health

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverPlanUnavailableReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.dns.TunWriteFailureReason
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionHealthReducerTest {
    @Test
    fun `three consecutive resolver failures require reconnect and a response recovers`() {
        val connected = ProtectionHealthAccumulator(isConnected = true)
        val failed = List(3) { terminalFailure() }
            .map(ProtectionHealthObservation::Decision)
            .fold(connected, ProtectionHealthReducer::reduce)

        assertEquals(3, failed.consecutiveResolverFailures)
        assertEquals(
            ProtectionHealthSeverity.NEEDS_RECONNECT,
            ProtectionHealthPolicy.assess(failed.evidence).severity,
        )

        val recovered = ProtectionHealthReducer.reduce(
            failed,
            ProtectionHealthObservation.Decision(successfulDecision(latencyMillis = 20)),
        )

        assertEquals(0, recovered.consecutiveResolverFailures)
        assertEquals(ProtectionHealthSeverity.HEALTHY, recovered.assessment.severity)
    }

    @Test
    fun `only three consecutive terminal response latencies produce slow DNS`() {
        val connected = ProtectionHealthAccumulator(isConnected = true)
        val slow = listOf(2_500L, 2_900L, 3_100L).fold(connected) { state, latency ->
            ProtectionHealthReducer.reduce(
                state,
                ProtectionHealthObservation.Decision(successfulDecision(latency)),
            )
        }

        assertEquals(listOf(2_500L, 2_900L, 3_100L), slow.recentResponseLatenciesMillis)
        assertEquals(ProtectionHealthSeverity.DNS_SLOW, slow.assessment.severity)

        val fast = ProtectionHealthReducer.reduce(
            slow,
            ProtectionHealthObservation.Decision(successfulDecision(latencyMillis = 25)),
        )
        assertEquals(listOf(2_900L, 3_100L, 25L), fast.recentResponseLatenciesMillis)
        assertEquals(ProtectionHealthSeverity.HEALTHY, fast.assessment.severity)
    }

    @Test
    fun `fallback recovery and network availability are reduced without retaining domains`() {
        val connected = ProtectionHealthAccumulator(isConnected = true)
        val fallback = ProtectionHealthReducer.reduce(
            connected,
            ProtectionHealthObservation.Health(
                DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH),
            ),
        )
        assertEquals(ProtectionFallback.ENCRYPTED_DNS, fallback.activeFallback)
        assertEquals(ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK, fallback.assessment.severity)

        val recovered = ProtectionHealthReducer.reduce(
            fallback,
            ProtectionHealthObservation.Health(
                DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE),
            ),
        )
        assertEquals(ProtectionFallback.NONE, recovered.activeFallback)
        assertEquals(ProtectionHealthSeverity.HEALTHY, recovered.assessment.severity)

        val unavailable = ProtectionHealthReducer.reduce(
            recovered,
            ProtectionHealthObservation.Health(
                DnsHealthEvent.ProtectionUnavailable(ProtectionUnavailableReason.NetworkUnavailable),
            ),
        )
        assertFalse(unavailable.networkAvailable)
        assertEquals(ProtectionHealthSeverity.NETWORK_UNAVAILABLE, unavailable.assessment.severity)

        val restored = ProtectionHealthReducer.reduce(
            unavailable,
            ProtectionHealthObservation.NetworkAvailabilityChanged(available = true),
        )
        assertTrue(restored.networkAvailable)
        assertEquals(ProtectionHealthSeverity.HEALTHY, restored.assessment.severity)
    }

    @Test
    fun `network loss outranks a reconnect latch until network availability returns`() {
        val unavailable = ProtectionHealthAccumulator(
            isConnected = true,
            networkAvailable = false,
            requiresReconnect = true,
        )

        assertEquals(ProtectionHealthSeverity.NETWORK_UNAVAILABLE, unavailable.assessment.severity)
        assertEquals(ProtectionHealthAction.TURN_OFF, unavailable.assessment.primaryAction)

        val restored = ProtectionHealthReducer.reduce(
            unavailable,
            ProtectionHealthObservation.NetworkAvailabilityChanged(available = true),
        )

        assertTrue(restored.requiresReconnect)
        assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, restored.assessment.severity)
        assertEquals(ProtectionHealthAction.RECONNECT, restored.assessment.primaryAction)
    }

    @Test
    fun `successful fallback followed by repeated terminal failures requires reconnect`() {
        val fallback = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(isConnected = true),
            ProtectionHealthObservation.Health(
                DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH),
            ),
        )
        val failed = List(3) { terminalFailure(ResolverKind.MULLVAD_DOH) }
            .map(ProtectionHealthObservation::Decision)
            .fold(fallback, ProtectionHealthReducer::reduce)

        assertEquals(ProtectionFallback.ENCRYPTED_DNS, failed.activeFallback)
        assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, failed.assessment.severity)
        assertEquals(ProtectionHealthAction.RECONNECT, failed.assessment.primaryAction)

        val answered = ProtectionHealthReducer.reduce(
            failed,
            ProtectionHealthObservation.Decision(
                successfulDecision(latencyMillis = 20).copy(
                    resolver = ResolverKind.MULLVAD_DOH,
                    transport = ResolverTransport.MULLVAD_DOH,
                ),
            ),
        )
        assertEquals(0, answered.consecutiveResolverFailures)
        assertEquals(
            ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK,
            answered.assessment.severity,
        )
    }

    @Test
    fun `disconnect clears transient resolver evidence`() {
        val unhealthy = ProtectionHealthAccumulator(
            isConnected = true,
            consecutiveResolverFailures = 2,
            consecutivePrimaryRejections = 2,
            recentResponseLatenciesMillis = listOf(3_000, 3_100),
            activeFallback = ProtectionFallback.DEVICE_DNS,
        )

        val disconnected = ProtectionHealthReducer.reduce(
            unhealthy,
            ProtectionHealthObservation.ConnectionChanged(connected = false),
        )

        assertFalse(disconnected.isConnected)
        assertEquals(0, disconnected.consecutiveResolverFailures)
        assertEquals(0, disconnected.consecutivePrimaryRejections)
        assertTrue(disconnected.recentResponseLatenciesMillis.isEmpty())
        assertEquals(ProtectionFallback.NONE, disconnected.activeFallback)
        assertEquals(ProtectionHealthSeverity.HEALTHY, disconnected.assessment.severity)
    }

    @Test
    fun `proven primary resolver recovery clears rejection evidence`() {
        val recovered = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(
                isConnected = true,
                consecutiveResolverFailures = 2,
                consecutivePrimaryRejections = 2,
                activeFallback = ProtectionFallback.ENCRYPTED_DNS,
            ),
            ProtectionHealthObservation.Health(
                DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE),
            ),
        )

        assertEquals(0, recovered.consecutiveResolverFailures)
        assertEquals(0, recovered.consecutivePrimaryRejections)
        assertEquals(ProtectionFallback.NONE, recovered.activeFallback)
    }

    @Test
    fun `three successful fallback outcomes keep failure streak clear despite primary attempt diagnostics`() {
        val observations = buildList {
            repeat(3) { id ->
                add(
                    ProtectionHealthObservation.Decision(
                        successfulDecision(latencyMillis = 20).copy(
                            id = id.toLong(),
                            resolver = ResolverKind.MULLVAD_DOH,
                            transport = ResolverTransport.MULLVAD_DOH,
                        ),
                    ),
                )
                add(
                    ProtectionHealthObservation.Health(
                        DnsHealthEvent.ResolverAttemptFailed(
                            resolver = ResolverKind.DEVICE,
                            reason = ResolverFailureReason.TIMEOUT,
                        ),
                    ),
                )
                if (id == 0) {
                    add(
                        ProtectionHealthObservation.Health(
                            DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH),
                        ),
                    )
                }
            }
        }

        val state = observations.fold(
            ProtectionHealthAccumulator(isConnected = true),
            ProtectionHealthReducer::reduce,
        )

        assertEquals(0, state.consecutiveResolverFailures)
        assertEquals(ProtectionFallback.ENCRYPTED_DNS, state.activeFallback)
        assertEquals(ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK, state.assessment.severity)
    }

    @Test
    fun `multiple endpoint diagnostics count as one terminal failed query`() {
        val observations = listOf(
            ProtectionHealthObservation.Decision(terminalFailure(ResolverKind.MULLVAD_DOH)),
            ProtectionHealthObservation.Health(
                DnsHealthEvent.ResolverAttemptFailed(
                    resolver = ResolverKind.DEVICE,
                    reason = ResolverFailureReason.TIMEOUT,
                ),
            ),
            ProtectionHealthObservation.Health(
                DnsHealthEvent.ResolverAttemptFailed(
                    resolver = ResolverKind.MULLVAD_DOH,
                    reason = ResolverFailureReason.TLS_FAILED,
                ),
            ),
        )

        val state = observations.fold(
            ProtectionHealthAccumulator(isConnected = true),
            ProtectionHealthReducer::reduce,
        )

        assertEquals(1, state.consecutiveResolverFailures)
    }

    @Test
    fun `local no wire outcomes and lifecycle failures never advance reconnect evidence`() {
        val recovering = ProtectionHealthAccumulator(
            isConnected = true,
            consecutiveResolverFailures = 2,
        )

        listOf(
            ResolverFailureReason.BACKED_OFF,
            ResolverFailureReason.CANCELLED,
            ResolverFailureReason.STALE_EPOCH,
            ResolverFailureReason.OVERLOADED,
        ).forEach { reason ->
            val state = List(3) { terminalFailure(reason = reason) }
                .map(ProtectionHealthObservation::Decision)
                .fold(recovering, ProtectionHealthReducer::reduce)

            assertEquals(reason.name, 2, state.consecutiveResolverFailures)
            assertEquals(reason.name, ProtectionHealthSeverity.RECOVERING, state.assessment.severity)
        }
    }

    @Test
    fun `runtime artifact resolver plan and current session TUN failures require reconnect until lifecycle reset`() {
        val reconnectRequired = listOf(
            DnsHealthEvent.ProtectionUnavailable(ProtectionUnavailableReason.RuntimeUnavailable),
            DnsHealthEvent.ProtectionUnavailable(
                ProtectionUnavailableReason.Artifact(FilterArtifactUnavailableReason.ARTIFACT_MISSING),
            ),
            DnsHealthEvent.ProtectionUnavailable(
                ProtectionUnavailableReason.ResolverPlan(ResolverPlanUnavailableReason.NO_USABLE_RESOLVER),
            ),
            DnsHealthEvent.TunWriteFailed(TunWriteFailureReason.DESCRIPTOR_CLOSED),
            DnsHealthEvent.TunWriteFailed(TunWriteFailureReason.WRITE_FAILED),
        )

        reconnectRequired.forEach { event ->
            val failed = ProtectionHealthReducer.reduce(
                ProtectionHealthAccumulator(isConnected = true),
                ProtectionHealthObservation.Health(event),
            )
            assertEquals(event.toString(), ProtectionHealthSeverity.NEEDS_RECONNECT, failed.assessment.severity)
            assertEquals(event.toString(), ProtectionHealthAction.RECONNECT, failed.assessment.primaryAction)

            val afterDnsDecision = ProtectionHealthReducer.reduce(
                failed,
                ProtectionHealthObservation.Decision(successfulDecision(latencyMillis = 20)),
            )
            assertEquals(event.toString(), ProtectionHealthSeverity.NEEDS_RECONNECT, afterDnsDecision.assessment.severity)

            val disconnected = ProtectionHealthReducer.reduce(
                afterDnsDecision,
                ProtectionHealthObservation.ConnectionChanged(connected = false),
            )
            assertEquals(event.toString(), ProtectionHealthSeverity.HEALTHY, disconnected.assessment.severity)
        }

        val ignored = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(isConnected = true),
            ProtectionHealthObservation.Health(
                DnsHealthEvent.TunWriteFailed(TunWriteFailureReason.STALE_SESSION),
            ),
        )
        assertEquals(ProtectionHealthSeverity.HEALTHY, ignored.assessment.severity)
        assertEquals(ProtectionHealthAction.TURN_OFF, ignored.assessment.primaryAction)
    }

    private fun successfulDecision(latencyMillis: Long) = DnsDecisionEvent(
        id = 1,
        timestampMillis = 1,
        normalizedDomain = "must-not-be-retained.example",
        decision = FilterDecision(FilterAction.ALLOW, FilterDecisionReason.DEFAULT_ALLOW),
        resolver = ResolverKind.DEVICE,
        transport = ResolverTransport.DEVICE_UDP,
        latencyMillis = latencyMillis,
    )

    private fun terminalFailure(
        resolver: ResolverKind = ResolverKind.DEVICE,
        reason: ResolverFailureReason = ResolverFailureReason.TIMEOUT,
    ) = DnsDecisionEvent(
        id = 1,
        timestampMillis = 1,
        normalizedDomain = "must-not-be-retained.example",
        decision = FilterDecision(FilterAction.ALLOW, FilterDecisionReason.DEFAULT_ALLOW),
        resolver = resolver,
        transport = if (resolver == ResolverKind.DEVICE) {
            ResolverTransport.DEVICE_UDP
        } else {
            ResolverTransport.MULLVAD_DOH
        },
        error = reason,
    )
}
