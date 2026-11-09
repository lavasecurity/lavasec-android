package com.lavasec.app.foundation.diagnostics

import com.lavasec.app.foundation.health.ContextualProtectionHealthObservation
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import com.lavasec.app.foundation.health.ProtectionHealthContext
import com.lavasec.app.foundation.health.ProtectionHealthObservation
import com.lavasec.app.foundation.health.ProtectionHealthReducer
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationalDiagnosticsDeltaCalculatorTest {
    @Test
    fun `compatible monotonic samples produce exact query delta and rate formatting`() {
        val sample = OperationalDiagnosticsDeltaCalculator.queryRate(
            previous = snapshot(
                wall = 10_000L,
                elapsed = 2_000L,
                queryHighWatermark = 10L,
                recentQueryIds = (8L..10L).toList(),
                observationSequence = 20L,
            ),
            current = snapshot(
                wall = 11_000L,
                elapsed = 3_000L,
                queryHighWatermark = 16L,
                recentQueryIds = (8L..16L).toList(),
                observationSequence = 26L,
            ),
        )

        assertEquals(
            OperationalDiagnosticsRateSample.Available(
                delta = 6L,
                intervalMillis = 1_000L,
                ratePerSecond = 6.0,
            ),
            sample,
        )
        assertEquals("6 · 6.0/s", OperationalDiagnosticsSampleFormatter.format(sample))
        assertEquals("11.000 s", OperationalDiagnosticsSampleFormatter.formatSampleTime(11_000L))
    }

    @Test
    fun `counter reset is explicit rather than a negative rate`() {
        val sample = OperationalDiagnosticsDeltaCalculator.queryRate(
            previous = snapshot(
                wall = 1_000L,
                elapsed = 1_000L,
                queryHighWatermark = 30L,
                observationSequence = 40L,
            ),
            current = snapshot(
                wall = 2_000L,
                elapsed = 2_000L,
                queryHighWatermark = 4L,
                observationSequence = 5L,
            ),
        )

        assertEquals(OperationalDiagnosticsRateSample.Reset, sample)
        assertEquals("Reset", OperationalDiagnosticsSampleFormatter.format(sample))
    }

    @Test
    fun `gapped and late out-of-order completions are typed unavailable rather than invented rates`() {
        val gapped = OperationalDiagnosticsDeltaCalculator.queryRate(
            previous = snapshot(
                wall = 1_000L,
                elapsed = 1_000L,
                queryHighWatermark = 10L,
                recentQueryIds = listOf(9L, 10L),
                observationSequence = 10L,
            ),
            current = snapshot(
                wall = 2_000L,
                elapsed = 2_000L,
                queryHighWatermark = 12L,
                recentQueryIds = listOf(9L, 10L, 12L),
                observationSequence = 12L,
            ),
        )
        val lateOutOfOrder = OperationalDiagnosticsDeltaCalculator.queryRate(
            previous = snapshot(
                wall = 2_000L,
                elapsed = 2_000L,
                queryHighWatermark = 12L,
                recentQueryIds = listOf(10L, 11L, 12L),
                observationSequence = 12L,
            ),
            current = snapshot(
                wall = 3_000L,
                elapsed = 3_000L,
                queryHighWatermark = 12L,
                recentQueryIds = listOf(10L, 11L, 12L, 9L),
                observationSequence = 13L,
            ),
        )
        val droppedRetainedEvidence = OperationalDiagnosticsDeltaCalculator.queryRate(
            previous = snapshot(
                wall = 3_000L,
                elapsed = 3_000L,
                queryHighWatermark = 10L,
                recentQueryIds = listOf(9L, 10L),
                observationSequence = 14L,
            ),
            current = snapshot(
                wall = 4_000L,
                elapsed = 4_000L,
                queryHighWatermark = 11L,
                recentQueryIds = listOf(10L, 11L),
                observationSequence = 15L,
            ),
        )

        val expected = OperationalDiagnosticsRateSample.Unavailable(
            OperationalDiagnosticsUnavailableReason.NON_CONTIGUOUS_EVIDENCE,
        )
        assertEquals(expected, gapped)
        assertEquals(expected, lateOutOfOrder)
        assertEquals(expected, droppedRetainedEvidence)
        assertEquals("Unavailable", OperationalDiagnosticsSampleFormatter.format(gapped))
    }

    @Test
    fun `contiguous retained-window eviction still proves an exact completion delta`() {
        val sample = OperationalDiagnosticsDeltaCalculator.queryRate(
            previous = snapshot(
                wall = 1_000L,
                elapsed = 1_000L,
                queryHighWatermark = 64L,
                recentQueryIds = (1L..64L).toList(),
                observationSequence = 64L,
            ),
            current = snapshot(
                wall = 2_000L,
                elapsed = 2_000L,
                queryHighWatermark = 65L,
                recentQueryIds = (2L..65L).toList(),
                observationSequence = 65L,
            ),
        )

        assertEquals(
            OperationalDiagnosticsRateSample.Available(
                delta = 1L,
                intervalMillis = 1_000L,
                ratePerSecond = 1.0,
            ),
            sample,
        )
    }

    @Test
    fun `late completion below retained floor advances observation and makes zero delta unavailable`() {
        val retainedIds = (2L..65L).toList()
        val previousHealth = ProtectionHealthAccumulator(
            isConnected = true,
            context = healthContext(observationSequence = 100L),
            recentResolverQueryIds = retainedIds,
            resolverQueryIdHighWatermark = 65L,
        )
        val currentHealth = ProtectionHealthReducer.reduce(
            previousHealth,
            ContextualProtectionHealthObservation(
                context = healthContext(observationSequence = 101L),
                observation = ProtectionHealthObservation.ResolverQueryCompleted(
                    ResolverQueryHealthEvidence.primaryAccepted(
                        queryId = 1L,
                        latencyMillis = 7L,
                    ),
                ),
            ),
        )

        assertEquals(retainedIds, currentHealth.recentResolverQueryIds)
        assertEquals(65L, currentHealth.resolverQueryIdHighWatermark)
        assertEquals(101L, currentHealth.context?.observationSequence)
        assertEquals(
            OperationalDiagnosticsRateSample.Unavailable(
                OperationalDiagnosticsUnavailableReason.NON_CONTIGUOUS_EVIDENCE,
            ),
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(
                    wall = 1_000L,
                    elapsed = 1_000L,
                    queryHighWatermark = 65L,
                    recentQueryIds = retainedIds,
                    observationSequence = 100L,
                ),
                current = snapshot(
                    wall = 2_000L,
                    elapsed = 2_000L,
                    queryHighWatermark = 65L,
                    recentQueryIds = retainedIds,
                    observationSequence = 101L,
                ),
            ),
        )
    }

    @Test
    fun `identical evidence with unchanged observation sequence proves exact zero`() {
        val retainedIds = (2L..65L).toList()

        assertEquals(
            OperationalDiagnosticsRateSample.Available(
                delta = 0L,
                intervalMillis = 1_000L,
                ratePerSecond = 0.0,
            ),
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(
                    wall = 1_000L,
                    elapsed = 1_000L,
                    queryHighWatermark = 65L,
                    recentQueryIds = retainedIds,
                    observationSequence = 100L,
                ),
                current = snapshot(
                    wall = 2_000L,
                    elapsed = 2_000L,
                    queryHighWatermark = 65L,
                    recentQueryIds = retainedIds,
                    observationSequence = 100L,
                ),
            ),
        )
    }

    @Test
    fun `clock skew process replacement missing evidence and overflow are unavailable`() {
        assertEquals(
            OperationalDiagnosticsRateSample.Unavailable(
                OperationalDiagnosticsUnavailableReason.CLOCK_SKEW,
            ),
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(wall = 10L, elapsed = 10L),
                current = snapshot(wall = 9L, elapsed = 11L),
            ),
        )
        assertEquals(
            OperationalDiagnosticsRateSample.Unavailable(
                OperationalDiagnosticsUnavailableReason.PROCESS_REPLACED,
            ),
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(process = 1L),
                current = snapshot(process = 2L),
            ),
        )
        assertEquals(
            OperationalDiagnosticsRateSample.Unavailable(
                OperationalDiagnosticsUnavailableReason.MISSING_EVIDENCE,
            ),
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(
                    wall = 1_000L,
                    elapsed = 1_000L,
                    queryHighWatermark = null,
                ),
                current = snapshot(
                    wall = 2_000L,
                    elapsed = 2_000L,
                    queryHighWatermark = 2L,
                ),
            ),
        )
        assertEquals(
            OperationalDiagnosticsRateSample.Unavailable(
                OperationalDiagnosticsUnavailableReason.OVERFLOW,
            ),
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(
                    wall = 1_000L,
                    elapsed = 1_000L,
                    queryHighWatermark = 0L,
                ),
                current = snapshot(
                    wall = 2_000L,
                    elapsed = 2_000L,
                    queryHighWatermark = Long.MAX_VALUE,
                    observationSequence = Long.MAX_VALUE,
                ),
            ),
        )
        assertEquals(
            "Unavailable",
            OperationalDiagnosticsSampleFormatter.format(
                OperationalDiagnosticsRateSample.Unavailable(
                    OperationalDiagnosticsUnavailableReason.PROCESS_REPLACED,
                ),
            ),
        )
    }

    @Test
    fun `owner and runtime replacements are typed in both generation directions`() {
        val ownerReplaced = OperationalDiagnosticsRateSample.Unavailable(
            OperationalDiagnosticsUnavailableReason.OWNER_REPLACED,
        )
        val runtimeReplaced = OperationalDiagnosticsRateSample.Unavailable(
            OperationalDiagnosticsUnavailableReason.RUNTIME_REPLACED,
        )

        assertEquals(
            ownerReplaced,
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(wall = 1_000L, elapsed = 1_000L, owner = 1L),
                current = snapshot(wall = 2_000L, elapsed = 2_000L, owner = 2L),
            ),
        )
        assertEquals(
            ownerReplaced,
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(wall = 1_000L, elapsed = 1_000L, owner = 2L),
                current = snapshot(wall = 2_000L, elapsed = 2_000L, owner = 1L),
            ),
        )
        assertEquals(
            runtimeReplaced,
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(wall = 1_000L, elapsed = 1_000L, runtime = 1L),
                current = snapshot(wall = 2_000L, elapsed = 2_000L, runtime = 2L),
            ),
        )
        assertEquals(
            runtimeReplaced,
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(wall = 1_000L, elapsed = 1_000L, runtime = 2L),
                current = snapshot(wall = 2_000L, elapsed = 2_000L, runtime = 1L),
            ),
        )
    }

    @Test
    fun `each health generation change is a reset in either direction`() {
        val generationPairs = listOf(
            snapshot(wall = 1_000L, elapsed = 1_000L, session = 1L) to
                snapshot(wall = 2_000L, elapsed = 2_000L, session = 2L),
            snapshot(wall = 1_000L, elapsed = 1_000L, session = 2L) to
                snapshot(wall = 2_000L, elapsed = 2_000L, session = 1L),
            snapshot(wall = 1_000L, elapsed = 1_000L, network = 1L) to
                snapshot(wall = 2_000L, elapsed = 2_000L, network = 2L),
            snapshot(wall = 1_000L, elapsed = 1_000L, network = 2L) to
                snapshot(wall = 2_000L, elapsed = 2_000L, network = 1L),
            snapshot(wall = 1_000L, elapsed = 1_000L, engine = 1L) to
                snapshot(wall = 2_000L, elapsed = 2_000L, engine = 2L),
            snapshot(wall = 1_000L, elapsed = 1_000L, engine = 2L) to
                snapshot(wall = 2_000L, elapsed = 2_000L, engine = 1L),
        )

        generationPairs.forEach { (previous, current) ->
            assertEquals(
                OperationalDiagnosticsRateSample.Reset,
                OperationalDiagnosticsDeltaCalculator.queryRate(previous, current),
            )
        }
    }

    @Test
    fun `replacement clock and health reset precedence stays explicit without counters`() {
        val missingAndSkewedPrevious = snapshot(
            wall = 10L,
            elapsed = 10L,
            queryHighWatermark = null,
        )
        assertEquals(
            OperationalDiagnosticsRateSample.Unavailable(
                OperationalDiagnosticsUnavailableReason.OWNER_REPLACED,
            ),
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = missingAndSkewedPrevious,
                current = snapshot(
                    wall = 9L,
                    elapsed = 9L,
                    owner = 2L,
                    queryHighWatermark = null,
                ),
            ),
        )
        assertEquals(
            OperationalDiagnosticsRateSample.Unavailable(
                OperationalDiagnosticsUnavailableReason.RUNTIME_REPLACED,
            ),
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = missingAndSkewedPrevious,
                current = snapshot(
                    wall = 9L,
                    elapsed = 9L,
                    runtime = 2L,
                    queryHighWatermark = null,
                ),
            ),
        )
        assertEquals(
            OperationalDiagnosticsRateSample.Unavailable(
                OperationalDiagnosticsUnavailableReason.CLOCK_SKEW,
            ),
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(wall = 10L, elapsed = 10L, session = 1L),
                current = snapshot(wall = 9L, elapsed = 9L, session = 2L),
            ),
        )
        assertEquals(
            OperationalDiagnosticsRateSample.Reset,
            OperationalDiagnosticsDeltaCalculator.queryRate(
                previous = snapshot(
                    wall = 1_000L,
                    elapsed = 1_000L,
                    session = 1L,
                    queryHighWatermark = null,
                ),
                current = snapshot(
                    wall = 2_000L,
                    elapsed = 2_000L,
                    session = 2L,
                    queryHighWatermark = null,
                ),
            ),
        )
    }

    private fun snapshot(
        wall: Long = 2_000L,
        elapsed: Long = 2_000L,
        owner: Long = 1L,
        process: Long = 1L,
        runtime: Long = 1L,
        session: Long = 1L,
        network: Long = 1L,
        engine: Long = 1L,
        queryHighWatermark: Long? = 10L,
        recentQueryIds: List<Long> = queryHighWatermark?.let(::listOf).orEmpty(),
        observationSequence: Long = 20L,
    ) = OperationalDiagnosticsSnapshotV1(
        credentialOwnerGeneration = owner,
        healthSessionGeneration = session,
        healthNetworkGeneration = network,
        healthEngineGeneration = engine,
        healthObservationSequence = observationSequence,
        sampledWallClockMillis = wall,
        sampledElapsedRealtimeMillis = elapsed,
        processGeneration = process,
        runtimeGeneration = runtime,
        serviceState = OperationalServiceState.RUNNING,
        tunnelState = OperationalTunnelState.CONNECTED,
        androidTransport = OperationalAndroidTransport.WIFI,
        androidPathState = OperationalAndroidPathState.VALIDATED,
        isConnected = true,
        networkAvailable = true,
        consecutiveResolverFailures = 0,
        consecutiveResolverRejections = 0,
        recentResponseLatenciesMillis = emptyList(),
        activeFallback = ProtectionFallback.NONE,
        requiresReconnect = false,
        recentResolverQueryIds = recentQueryIds,
        resolverQueryIdHighWatermark = queryHighWatermark,
    )

    private fun healthContext(
        observationSequence: Long,
    ) = ProtectionHealthContext(
        sessionGeneration = 1L,
        networkGeneration = 1L,
        engineGeneration = 1L,
        observationSequence = observationSequence,
        observedAtMillis = observationSequence,
        primaryResolverIdentity = PrimaryResolverIdentity("transient-test-resolver"),
        fallbackCoverage = ResolverFallbackCoverage.NONE,
    )
}
