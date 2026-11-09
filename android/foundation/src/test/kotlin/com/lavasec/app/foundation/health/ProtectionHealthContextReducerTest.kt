package com.lavasec.app.foundation.health

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionHealthContextReducerTest {
    @Test
    fun `new network generation clears network evidence and rejects the superseded generation`() {
        val initial = ProtectionHealthAccumulator(isConnected = true)
        val failed = listOf(
            contextual(
                context(networkGeneration = 1, observedAtMillis = 100),
                terminalFailure(queryId = 1),
            ),
            contextual(
                context(networkGeneration = 1, observedAtMillis = 110),
                terminalFailure(queryId = 2),
            ),
        ).fold(initial, ProtectionHealthReducer::reduce)

        assertEquals(2, failed.consecutiveResolverFailures)

        val advanced = ProtectionHealthReducer.reduce(
            failed,
            contextual(
                context(networkGeneration = 2, observedAtMillis = 200),
                ProtectionHealthObservation.NetworkAvailabilityChanged(available = true),
            ),
        )

        assertEquals(0, advanced.consecutiveResolverFailures)
        assertTrue(advanced.recentResponseLatenciesMillis.isEmpty())
        assertEquals(ProtectionFallback.NONE, advanced.activeFallback)

        val staleGeneration = ProtectionHealthReducer.reduce(
            advanced,
            contextual(
                context(networkGeneration = 1, observedAtMillis = 300),
                terminalFailure(queryId = 3),
            ),
        )
        val staleTime = ProtectionHealthReducer.reduce(
            staleGeneration,
            contextual(
                context(networkGeneration = 2, observedAtMillis = 199),
                terminalFailure(queryId = 4),
            ),
        )

        assertEquals(advanced, staleTime)
    }

    @Test
    fun `new primary resolver identity establishes zero resolver evidence`() {
        val rejecting = (1L..3L).fold(ProtectionHealthAccumulator(isConnected = true)) { state, id ->
            ProtectionHealthReducer.reduce(
                state,
                contextual(
                    context(engineGeneration = 1, observedAtMillis = id),
                    rejectedPrimaryWithEncryptedFallback(id),
                ),
            )
        }
        assertEquals(3, rejecting.consecutivePrimaryRejections)

        val changed = ProtectionHealthReducer.reduce(
            rejecting,
            contextual(
                context(
                    engineGeneration = 2,
                    observedAtMillis = 10,
                    primaryResolverIdentity = PrimaryResolverIdentity("primary-b"),
                ),
                ProtectionHealthObservation.ConnectionChanged(connected = true),
            ),
        )

        assertEquals(
            PrimaryResolverIdentity("primary-b"),
            changed.context?.primaryResolverIdentity,
        )
        assertEquals(0, changed.consecutivePrimaryRejections)
        assertEquals(0, changed.consecutiveResolverFailures)
        assertTrue(changed.recentResponseLatenciesMillis.isEmpty())
        assertEquals(ProtectionFallback.NONE, changed.activeFallback)
    }

    @Test
    fun `same primary identity across engine generations preserves rejection evidence`() {
        val rejecting = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(isConnected = true),
            contextual(
                context(engineGeneration = 1, observedAtMillis = 1),
                rejectedPrimaryWithEncryptedFallback(queryId = 1),
            ),
        )

        val advanced = ProtectionHealthReducer.reduce(
            rejecting,
            contextual(
                context(engineGeneration = 2, observedAtMillis = 2),
                ProtectionHealthObservation.ConnectionChanged(connected = true),
            ),
        )

        assertEquals(1, advanced.consecutivePrimaryRejections)
        assertEquals(ProtectionHealthSeverity.HEALTHY, advanced.assessment.severity)
    }

    @Test
    fun `same primary rejection threshold survives a network handoff`() {
        val twiceRejected = (1L..2L).fold(ProtectionHealthAccumulator(isConnected = true)) { state, id ->
            ProtectionHealthReducer.reduce(
                state,
                contextual(
                    context(networkGeneration = 1, observedAtMillis = id),
                    rejectedPrimaryWithEncryptedFallback(queryId = id),
                ),
            )
        }

        assertEquals(ProtectionHealthSeverity.RECOVERING, twiceRejected.assessment.severity)

        val settled = ProtectionHealthReducer.reduce(
            twiceRejected,
            contextual(
                context(networkGeneration = 2, observedAtMillis = 3),
                ProtectionHealthObservation.ConnectionChanged(connected = true),
            ),
        )

        assertEquals(2, settled.consecutivePrimaryRejections)
        assertEquals(ProtectionHealthSeverity.HEALTHY, settled.assessment.severity)

        val thresholdReached = ProtectionHealthReducer.reduce(
            settled,
            contextual(
                context(networkGeneration = 2, observedAtMillis = 4),
                // Query ids are generation-scoped, so reuse also proves the replay window reset.
                rejectedPrimaryWithEncryptedFallback(queryId = 1),
            ),
        )

        assertEquals(3, thresholdReached.consecutivePrimaryRejections)
        assertEquals(listOf(1L), thresholdReached.recentResolverQueryIds)
        assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, thresholdReached.assessment.severity)
    }

    @Test
    fun `retained threshold rejection evidence is dormant until the new network has problem evidence`() {
        val threshold = (1L..ProtectionHealthPolicy.RECONNECT_FAILURE_THRESHOLD.toLong()).fold(
            ProtectionHealthAccumulator(isConnected = true),
        ) { state, id ->
            ProtectionHealthReducer.reduce(
                state,
                contextual(
                    context(networkGeneration = 1, observedAtMillis = id),
                    rejectedPrimaryWithEncryptedFallback(queryId = id),
                ),
            )
        }
        assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, threshold.assessment.severity)

        val settled = ProtectionHealthReducer.reduce(
            threshold,
            contextual(
                context(networkGeneration = 2, observedAtMillis = 10),
                ProtectionHealthObservation.ConnectionChanged(connected = true),
            ),
        )

        assertEquals(
            ProtectionHealthPolicy.RECONNECT_FAILURE_THRESHOLD,
            settled.consecutivePrimaryRejections,
        )
        assertEquals(ProtectionHealthSeverity.HEALTHY, settled.assessment.severity)
    }

    @Test
    fun `network handoff to a new primary resets prior identity evidence`() {
        val twiceRejected = (1L..2L).fold(ProtectionHealthAccumulator(isConnected = true)) { state, id ->
            ProtectionHealthReducer.reduce(
                state,
                contextual(
                    context(networkGeneration = 1, observedAtMillis = id),
                    rejectedPrimaryWithEncryptedFallback(queryId = id),
                ),
            )
        }

        val newPrimary = ProtectionHealthReducer.reduce(
            twiceRejected,
            contextual(
                context(
                    networkGeneration = 2,
                    observedAtMillis = 3,
                    primaryResolverIdentity = PrimaryResolverIdentity("primary-b"),
                ),
                ProtectionHealthObservation.ConnectionChanged(connected = true),
            ),
        )

        assertEquals(0, newPrimary.consecutivePrimaryRejections)
        assertEquals(PrimaryResolverIdentity("primary-b"), newPrimary.context?.primaryResolverIdentity)
    }

    @Test
    fun `new engine generation clears reconnect evidence`() {
        val unavailable = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(isConnected = true),
            contextual(
                context(engineGeneration = 1, observedAtMillis = 1),
                ProtectionHealthObservation.Health(
                    DnsHealthEvent.ProtectionUnavailable(
                        ProtectionUnavailableReason.RuntimeUnavailable,
                    ),
                ),
            ),
        )
        assertTrue(unavailable.requiresReconnect)

        val advanced = ProtectionHealthReducer.reduce(
            unavailable,
            contextual(
                context(engineGeneration = 2, observedAtMillis = 2),
                ProtectionHealthObservation.ConnectionChanged(connected = true),
            ),
        )

        assertTrue(advanced.isConnected)
        assertFalse(advanced.requiresReconnect)
    }

    @Test
    fun `fallback-only engine generation preserves rejection evidence`() {
        val rejecting = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(isConnected = true),
            contextual(
                context(
                    engineGeneration = 1,
                    observedAtMillis = 1,
                    fallbackCoverage = ResolverFallbackCoverage.DEVICE_DNS,
                ),
                ProtectionHealthObservation.ResolverQueryCompleted(
                    ResolverQueryHealthEvidence.primaryRejectedWithFallback(
                        queryId = 1,
                        fallback = ProtectionFallback.DEVICE_DNS,
                    ),
                ),
            ),
        )

        val fallbackChanged = ProtectionHealthReducer.reduce(
            rejecting,
            contextual(
                context(
                    engineGeneration = 2,
                    observedAtMillis = 2,
                    fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                ),
                ProtectionHealthObservation.ConnectionChanged(connected = true),
            ),
        )

        assertEquals(1, fallbackChanged.consecutivePrimaryRejections)
    }

    @Test
    fun `primary resolver identity rejects blank values`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrimaryResolverIdentity("  ")
        }
    }

    @Test
    fun `successful fallback restores network and breaks failure streak without hiding rejection streak`() {
        val state = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(
                isConnected = true,
                networkAvailable = false,
                consecutiveResolverFailures = 2,
                consecutivePrimaryRejections = 1,
            ),
            contextual(
                context(observedAtMillis = 1),
                rejectedPrimaryWithEncryptedFallback(queryId = 1),
            ),
        )

        assertTrue(state.networkAvailable)
        assertEquals(0, state.consecutiveResolverFailures)
        assertEquals(2, state.consecutivePrimaryRejections)
    }

    @Test
    fun `successful fallback after primary failure restores network and retains rejection streak`() {
        val state = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(
                isConnected = true,
                networkAvailable = false,
                consecutiveResolverFailures = 2,
                consecutivePrimaryRejections = 1,
            ),
            contextual(
                context(observedAtMillis = 1),
                ProtectionHealthObservation.ResolverQueryCompleted(
                    ResolverQueryHealthEvidence.primaryFailed(
                        queryId = 1,
                        reason = ResolverFailureReason.TIMEOUT,
                        successfulFallback = ProtectionFallback.ENCRYPTED_DNS,
                    ),
                ),
            ),
        )

        assertTrue(state.networkAvailable)
        assertEquals(0, state.consecutiveResolverFailures)
        assertEquals(1, state.consecutivePrimaryRejections)
    }

    @Test
    fun `accepted primary clears rejection evidence while fallback success does not`() {
        val twiceRejected = (1L..2L).fold(ProtectionHealthAccumulator(isConnected = true)) { state, id ->
            ProtectionHealthReducer.reduce(
                state,
                contextual(
                    context(observedAtMillis = id),
                    rejectedPrimaryWithEncryptedFallback(id),
                ),
            )
        }

        assertEquals(2, twiceRejected.consecutivePrimaryRejections)
        assertEquals(ProtectionFallback.ENCRYPTED_DNS, twiceRejected.activeFallback)

        val accepted = ProtectionHealthReducer.reduce(
            twiceRejected,
            contextual(
                context(observedAtMillis = 3),
                ProtectionHealthObservation.ResolverQueryCompleted(
                    ResolverQueryHealthEvidence.primaryAccepted(queryId = 3, latencyMillis = 25),
                ),
            ),
        )

        assertEquals(0, accepted.consecutivePrimaryRejections)
        assertEquals(0, accepted.consecutiveResolverFailures)
        assertEquals(ProtectionFallback.NONE, accepted.activeFallback)
        assertEquals(listOf(25L), accepted.recentResponseLatenciesMillis)
    }

    @Test
    fun `three rejecting primaries remain reconnect worthy despite encrypted fallback coverage`() {
        val state = (1L..3L).fold(ProtectionHealthAccumulator(isConnected = true)) { current, id ->
            ProtectionHealthReducer.reduce(
                current,
                contextual(
                    context(observedAtMillis = id),
                    rejectedPrimaryWithEncryptedFallback(id),
                ),
            )
        }

        assertEquals(3, state.consecutivePrimaryRejections)
        assertEquals(ProtectionFallback.ENCRYPTED_DNS, state.activeFallback)
        assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, state.assessment.severity)
        assertEquals(ProtectionHealthAction.RECONNECT, state.assessment.primaryAction)
    }

    @Test
    fun `endpoint diagnostics plus the terminal query outcome count one failure`() {
        val taggedContext = context(observedAtMillis = 100)
        val withEndpointDiagnostic = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(isConnected = true),
            contextual(
                taggedContext,
                ProtectionHealthObservation.Health(
                    DnsHealthEvent.ResolverAttemptFailed(
                        resolver = ResolverKind.DEVICE,
                        reason = ResolverFailureReason.TIMEOUT,
                    ),
                ),
            ),
        )
        val terminal = ProtectionHealthReducer.reduce(
            withEndpointDiagnostic,
            contextual(
                taggedContext.copy(observedAtMillis = 101, observationSequence = 101),
                terminalFailure(queryId = 7),
            ),
        )

        assertEquals(1, terminal.consecutiveResolverFailures)
        assertEquals(0, terminal.consecutivePrimaryRejections)
    }

    @Test
    fun `exact query replay at a later observation sequence is ignored`() {
        val first = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(isConnected = true),
            contextual(
                context(observedAtMillis = 100, observationSequence = 1),
                terminalFailure(queryId = 7),
            ),
        )

        val replayed = ProtectionHealthReducer.reduce(
            first,
            contextual(
                context(observedAtMillis = 100, observationSequence = 2),
                terminalFailure(queryId = 7),
            ),
        )

        assertEquals(1, replayed.consecutiveResolverFailures)
        assertEquals(2L, replayed.context?.observationSequence)
    }

    @Test
    fun `evicted accepted query replay cannot clear newer failures in the same generation`() {
        val accepted = (0L..61L).fold(ProtectionHealthAccumulator(isConnected = true)) { state, id ->
            ProtectionHealthReducer.reduce(
                state,
                contextual(
                    context(observedAtMillis = id, observationSequence = id),
                    ProtectionHealthObservation.ResolverQueryCompleted(
                        ResolverQueryHealthEvidence.primaryAccepted(queryId = id, latencyMillis = 25),
                    ),
                ),
            )
        }
        val failed = (62L..64L).fold(accepted) { state, id ->
            ProtectionHealthReducer.reduce(
                state,
                contextual(
                    context(observedAtMillis = id, observationSequence = id),
                    terminalFailure(queryId = id),
                ),
            )
        }

        assertEquals(3, failed.consecutiveResolverFailures)
        assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, failed.assessment.severity)

        val replayed = ProtectionHealthReducer.reduce(
            failed,
            contextual(
                context(observedAtMillis = 0, observationSequence = 65),
                ProtectionHealthObservation.ResolverQueryCompleted(
                    ResolverQueryHealthEvidence.primaryAccepted(queryId = 0, latencyMillis = 25),
                ),
            ),
        )

        assertEquals(3, replayed.consecutiveResolverFailures)
        assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, replayed.assessment.severity)
        assertEquals(65L, replayed.context?.observationSequence)
    }

    @Test
    fun `query replay window accepts bounded out of order ids and ignores older ids`() {
        val latest = ProtectionHealthReducer.reduce(
            ProtectionHealthAccumulator(isConnected = true),
            contextual(
                context(observedAtMillis = 1, observationSequence = 1),
                terminalFailure(queryId = 100),
            ),
        )
        val atRetainedFloor = ProtectionHealthReducer.reduce(
            latest,
            contextual(
                context(observedAtMillis = 2, observationSequence = 2),
                terminalFailure(queryId = 37),
            ),
        )
        val olderThanRetainedFloor = ProtectionHealthReducer.reduce(
            atRetainedFloor,
            contextual(
                context(observedAtMillis = 3, observationSequence = 3),
                terminalFailure(queryId = 36),
            ),
        )

        assertEquals(2, atRetainedFloor.consecutiveResolverFailures)
        assertEquals(2, olderThanRetainedFloor.consecutiveResolverFailures)
        assertEquals(3L, olderThanRetainedFloor.context?.observationSequence)
    }

    @Test
    fun `distinct completions in the same millisecond are ordered by observation sequence`() {
        val state = listOf(
            contextual(
                context(observedAtMillis = 100, observationSequence = 1),
                terminalFailure(queryId = 7),
            ),
            contextual(
                context(observedAtMillis = 100, observationSequence = 2),
                terminalFailure(queryId = 8),
            ),
        ).fold(ProtectionHealthAccumulator(isConnected = true), ProtectionHealthReducer::reduce)

        assertEquals(2, state.consecutiveResolverFailures)
    }

    @Test
    fun `observation sequence orders completions despite wall clock regression`() {
        val state = listOf(
            contextual(
                context(observedAtMillis = 100, observationSequence = 1),
                terminalFailure(queryId = 7),
            ),
            contextual(
                context(observedAtMillis = 99, observationSequence = 2),
                terminalFailure(queryId = 8),
            ),
        ).fold(ProtectionHealthAccumulator(isConnected = true), ProtectionHealthReducer::reduce)

        assertEquals(2, state.consecutiveResolverFailures)
    }

    @Test
    fun `query deduplication is generation scoped and bounded`() {
        val initial = (0L..64L).fold(ProtectionHealthAccumulator(isConnected = true)) { state, id ->
            ProtectionHealthReducer.reduce(
                state,
                contextual(
                    context(observedAtMillis = id, observationSequence = id),
                    rejectedPrimaryWithEncryptedFallback(queryId = id),
                ),
            )
        }

        assertEquals(64, initial.recentResolverQueryIds.size)

        val nextGeneration = ProtectionHealthReducer.reduce(
            initial,
            contextual(
                context(
                    engineGeneration = 2,
                    observedAtMillis = 65,
                    observationSequence = 0,
                ),
                rejectedPrimaryWithEncryptedFallback(queryId = 64),
            ),
        )

        assertEquals(66, nextGeneration.consecutivePrimaryRejections)
        assertEquals(listOf(64L), nextGeneration.recentResolverQueryIds)
    }

    private fun context(
        sessionGeneration: Long = 1,
        networkGeneration: Long = 1,
        engineGeneration: Long = 1,
        observedAtMillis: Long,
        observationSequence: Long = observedAtMillis,
        primaryResolverIdentity: PrimaryResolverIdentity = PrimaryResolverIdentity("primary-a"),
        fallbackCoverage: ResolverFallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
    ) = ProtectionHealthContext(
        sessionGeneration = sessionGeneration,
        networkGeneration = networkGeneration,
        engineGeneration = engineGeneration,
        observationSequence = observationSequence,
        observedAtMillis = observedAtMillis,
        primaryResolverIdentity = primaryResolverIdentity,
        fallbackCoverage = fallbackCoverage,
    )

    private fun contextual(
        context: ProtectionHealthContext,
        observation: ProtectionHealthObservation,
    ) = ContextualProtectionHealthObservation(context, observation)

    private fun rejectedPrimaryWithEncryptedFallback(queryId: Long) =
        ProtectionHealthObservation.ResolverQueryCompleted(
            ResolverQueryHealthEvidence.primaryRejectedWithFallback(
                queryId = queryId,
                fallback = ProtectionFallback.ENCRYPTED_DNS,
            ),
        )

    private fun terminalFailure(queryId: Long) =
        ProtectionHealthObservation.ResolverQueryCompleted(
            ResolverQueryHealthEvidence.primaryFailed(
                queryId = queryId,
                reason = ResolverFailureReason.TIMEOUT,
            ),
        )
}
