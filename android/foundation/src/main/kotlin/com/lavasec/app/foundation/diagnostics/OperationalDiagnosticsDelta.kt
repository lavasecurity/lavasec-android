package com.lavasec.app.foundation.diagnostics

import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import java.util.Locale

enum class OperationalDiagnosticsUnavailableReason {
    OWNER_REPLACED,
    PROCESS_REPLACED,
    RUNTIME_REPLACED,
    CLOCK_SKEW,
    MISSING_EVIDENCE,
    NON_CONTIGUOUS_EVIDENCE,
    OVERFLOW,
}

sealed interface OperationalDiagnosticsRateSample {
    data class Available(
        val delta: Long,
        val intervalMillis: Long,
        val ratePerSecond: Double,
    ) : OperationalDiagnosticsRateSample {
        init {
            require(delta >= 0L)
            require(intervalMillis > 0L)
            require(ratePerSecond.isFinite() && ratePerSecond >= 0.0)
        }
    }

    data object Reset : OperationalDiagnosticsRateSample

    data class Unavailable(
        val reason: OperationalDiagnosticsUnavailableReason,
    ) : OperationalDiagnosticsRateSample
}

object OperationalDiagnosticsDeltaCalculator {
    fun queryRate(
        previous: OperationalDiagnosticsSnapshotV1,
        current: OperationalDiagnosticsSnapshotV1,
    ): OperationalDiagnosticsRateSample {
        if (current.credentialOwnerGeneration != previous.credentialOwnerGeneration) {
            return unavailable(OperationalDiagnosticsUnavailableReason.OWNER_REPLACED)
        }
        if (current.processGeneration != previous.processGeneration) {
            return unavailable(OperationalDiagnosticsUnavailableReason.PROCESS_REPLACED)
        }
        if (current.runtimeGeneration != previous.runtimeGeneration) {
            return unavailable(OperationalDiagnosticsUnavailableReason.RUNTIME_REPLACED)
        }
        if (
            current.sampledWallClockMillis < previous.sampledWallClockMillis ||
            current.sampledElapsedRealtimeMillis <= previous.sampledElapsedRealtimeMillis
        ) {
            return unavailable(OperationalDiagnosticsUnavailableReason.CLOCK_SKEW)
        }
        if (healthGenerationAdvancedOrReset(previous, current)) {
            return OperationalDiagnosticsRateSample.Reset
        }

        val previousCounter = previous.resolverQueryIdHighWatermark
            ?: return unavailable(OperationalDiagnosticsUnavailableReason.MISSING_EVIDENCE)
        val currentCounter = current.resolverQueryIdHighWatermark
            ?: return unavailable(OperationalDiagnosticsUnavailableReason.MISSING_EVIDENCE)
        if (
            currentCounter < previousCounter ||
            current.healthObservationSequence < previous.healthObservationSequence
        ) {
            return OperationalDiagnosticsRateSample.Reset
        }

        val delta = currentCounter - previousCounter
        val interval = current.sampledElapsedRealtimeMillis - previous.sampledElapsedRealtimeMillis
        val scaled = try {
            Math.multiplyExact(delta, MILLIS_PER_SECOND)
        } catch (_: ArithmeticException) {
            return unavailable(OperationalDiagnosticsUnavailableReason.OVERFLOW)
        }
        if (!hasExactCompletionCoverage(previous, current, previousCounter, currentCounter, delta)) {
            return unavailable(OperationalDiagnosticsUnavailableReason.NON_CONTIGUOUS_EVIDENCE)
        }
        return OperationalDiagnosticsRateSample.Available(
            delta = delta,
            intervalMillis = interval,
            ratePerSecond = scaled.toDouble() / interval.toDouble(),
        )
    }

    private fun healthGenerationAdvancedOrReset(
        previous: OperationalDiagnosticsSnapshotV1,
        current: OperationalDiagnosticsSnapshotV1,
    ): Boolean =
        current.healthSessionGeneration != previous.healthSessionGeneration ||
            current.healthNetworkGeneration != previous.healthNetworkGeneration ||
            current.healthEngineGeneration != previous.healthEngineGeneration

    private fun hasExactCompletionCoverage(
        previous: OperationalDiagnosticsSnapshotV1,
        current: OperationalDiagnosticsSnapshotV1,
        previousCounter: Long,
        currentCounter: Long,
        delta: Long,
    ): Boolean {
        val previousIds = previous.recentResolverQueryIds
        val currentIds = current.recentResolverQueryIds
        if (!previousIds.isAscendingContiguousEndingAt(previousCounter)) return false
        if (!currentIds.isAscendingContiguousEndingAt(currentCounter)) return false

        val retainedFloor = maxOf(
            0L,
            currentCounter -
                (ProtectionHealthAccumulator.MAX_RECENT_RESOLVER_QUERY_IDS - 1L),
        )
        val retainedPrevious = previousIds.dropWhile { it < retainedFloor }
        val retainedCurrentPrefix = currentIds.takeWhile { it <= previousCounter }
        if (retainedPrevious != retainedCurrentPrefix) return false

        val newlyCovered = currentIds.dropWhile { it <= previousCounter }
        if (newlyCovered.size.toLong() != delta) return false
        if (delta == 0L) {
            return newlyCovered.isEmpty() &&
                current.healthObservationSequence == previous.healthObservationSequence
        }
        if (previousCounter == Long.MAX_VALUE) return false
        return newlyCovered.first() == previousCounter + 1L &&
            newlyCovered.last() == currentCounter
    }

    private fun List<Long>.isAscendingContiguousEndingAt(highWatermark: Long): Boolean =
        isNotEmpty() &&
            last() == highWatermark &&
            zipWithNext().all { (previous, current) ->
                previous != Long.MAX_VALUE && current == previous + 1L
            }

    private fun unavailable(reason: OperationalDiagnosticsUnavailableReason) =
        OperationalDiagnosticsRateSample.Unavailable(reason)

    private const val MILLIS_PER_SECOND = 1_000L
}

object OperationalDiagnosticsSampleFormatter {
    fun format(sample: OperationalDiagnosticsRateSample): String = when (sample) {
        is OperationalDiagnosticsRateSample.Available -> String.format(
            Locale.ROOT,
            "%d · %.1f/s",
            sample.delta,
            sample.ratePerSecond,
        )

        OperationalDiagnosticsRateSample.Reset -> "Reset"
        is OperationalDiagnosticsRateSample.Unavailable -> "Unavailable"
    }

    fun formatSampleTime(sampledWallClockMillis: Long): String {
        require(sampledWallClockMillis >= 0L)
        return String.format(Locale.ROOT, "%.3f s", sampledWallClockMillis / 1_000.0)
    }
}
