package com.lavasec.app.foundation.dns

import kotlin.math.min

/**
 * Pure bounded-backoff policy ported from the canonical iOS
 * `Sources/LavaSecKit/ResolverBackoffPolicy.swift` and `DeviceDNSFallbackPolicy.swift`
 * (pinned `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`).
 *
 * Two independent clocks exist and the model keeps them distinct:
 *
 * - the **per-address penalty** is a flat [ResolverBackoffPolicy.BACKOFF_INTERVAL_MILLIS] window
 *   (never escalates), and suppression NEVER empties a non-empty route: with every address sick,
 *   the caller gets the whole list back rather than a blackhole;
 * - the **smoke-probe cadence** stretches by doubling once failures pass an activation count, and
 *   is clamped at [ResolverSmokeProbeCadence.MAX_INTERVAL_MILLIS], while the failure streak itself
 *   saturates at [ResolverSmokeProbeCadence.MAX_TRACKED_FAILURES].
 *
 * Every method is a pure state transition; no clock is read and no I/O is performed. Callers pass
 * a monotonic `nowMillis`.
 */

/** How one attempt outcome affects the per-address penalty. */
enum class ResolverBackoffOutcome {
    /** The address answered; its penalty is cleared. */
    SUCCESS,

    /** A wire attempt failed against the upstream; the address is benched for one interval. */
    RETRY_WORTHY,

    /** A local decision (backoff, cancellation, stale epoch, overload): no penalty. */
    NEUTRAL,
}

/** Maps the Android failure vocabulary onto the canonical backoff categories. */
fun ResolverFailureReason.backoffOutcome(): ResolverBackoffOutcome = when (this) {
    // The canonical bench set (timeout / HTTP status / send / receive / mismatched) plus the
    // Android transport failures that are the same wire-exchange class (`PROTECT_FAILED`,
    // `BIND_FAILED`, `SOCKET_FAILED`, `TLS_FAILED`, `INVALID_RESPONSE`, `OVERSIZED_RESPONSE`).
    ResolverFailureReason.TIMEOUT,
    ResolverFailureReason.PROTECT_FAILED,
    ResolverFailureReason.BIND_FAILED,
    ResolverFailureReason.SOCKET_FAILED,
    ResolverFailureReason.SEND_FAILED,
    ResolverFailureReason.RECEIVE_FAILED,
    ResolverFailureReason.MISMATCH_LIMIT,
    ResolverFailureReason.INVALID_RESPONSE,
    ResolverFailureReason.OVERSIZED_RESPONSE,
    ResolverFailureReason.HTTP_STATUS,
    ResolverFailureReason.HTTP_CONTENT_TYPE,
    ResolverFailureReason.TLS_FAILED,
    -> ResolverBackoffOutcome.RETRY_WORTHY

    // Nothing left the device, or the device chose not to send: benching the upstream would be
    // punishing it for the device's own condition (canonical `.tunnelInterfaceUnavailable` case).
    ResolverFailureReason.BACKED_OFF,
    ResolverFailureReason.CANCELLED,
    ResolverFailureReason.STALE_EPOCH,
    ResolverFailureReason.OVERLOADED,
    -> ResolverBackoffOutcome.NEUTRAL
}

/** One recorded attempt against an address. */
data class ResolverBackoffAttempt(
    val address: String,
    val outcome: ResolverBackoffOutcome,
)

/** Immutable per-address penalty box plus the shared encrypted-recovery claim clock. */
data class ResolverBackoffState(
    val backoffUntilMillis: Map<String, Long> = emptyMap(),
    val lastEncryptedRecoveryAtMillis: Long? = null,
)

/** Result of an encrypted-recovery claim: the next state and the one address granted, if any. */
data class ResolverRecoveryClaim(
    val state: ResolverBackoffState,
    val address: String?,
)

object ResolverBackoffPolicy {
    /** Flat per-address penalty window. The canonical default is 30 s. */
    const val BACKOFF_INTERVAL_MILLIS: Long = 30_000L

    /** Shared encrypted-recovery cadence when every endpoint is suppressed. Canonical 1 s. */
    const val ENCRYPTED_RECOVERY_INTERVAL_MILLIS: Long = 1_000L

    /**
     * The addresses worth trying now, in the caller's order.
     *
     * SUPPRESSION DEFERS, NEVER ELIMINATES. When every candidate is backed off, the input is
     * returned unchanged: a throttle that leaves the caller with nowhere to send has become an
     * outage, and the caller's failover order is preserved on the fallback.
     */
    fun availableAddresses(
        state: ResolverBackoffState,
        addresses: List<String>,
        nowMillis: Long,
    ): List<String> {
        val available = addresses.filter { !isBackedOff(state, it, nowMillis) }
        return if (available.isEmpty()) addresses else available
    }

    fun isBackedOff(state: ResolverBackoffState, address: String, nowMillis: Long): Boolean {
        val until = state.backoffUntilMillis[address] ?: return false
        return until > nowMillis
    }

    fun backoffExpiration(state: ResolverBackoffState, address: String): Long? =
        state.backoffUntilMillis[address]

    /** Apply a batch of attempts; a success clears, a retry-worthy failure benches for one interval. */
    fun record(
        state: ResolverBackoffState,
        attempts: List<ResolverBackoffAttempt>,
        nowMillis: Long,
    ): ResolverBackoffState {
        var penalties = state.backoffUntilMillis
        for (attempt in attempts) {
            when (attempt.outcome) {
                ResolverBackoffOutcome.SUCCESS -> penalties = penalties - attempt.address
                ResolverBackoffOutcome.RETRY_WORTHY ->
                    penalties = penalties + (attempt.address to nowMillis + BACKOFF_INTERVAL_MILLIS)
                ResolverBackoffOutcome.NEUTRAL -> Unit
            }
        }
        return state.copy(backoffUntilMillis = penalties)
    }

    /**
     * Claim one encrypted-recovery launch when every permitted address is suppressed.
     *
     * Bounded to one grant per [ENCRYPTED_RECOVERY_INTERVAL_MILLIS] so the recovery cannot become
     * a full blackout. A backward clock reading rebases the cooldown rather than extending it.
     */
    fun claimEncryptedRecovery(
        state: ResolverBackoffState,
        addresses: List<String>,
        nowMillis: Long,
    ): ResolverRecoveryClaim {
        val first = addresses.firstOrNull() ?: return ResolverRecoveryClaim(state, null)
        if (!addresses.all { isBackedOff(state, it, nowMillis) }) {
            return ResolverRecoveryClaim(state, null)
        }
        val last = state.lastEncryptedRecoveryAtMillis
        if (last != null) {
            val elapsed = nowMillis - last
            if (elapsed >= 0 && elapsed < ENCRYPTED_RECOVERY_INTERVAL_MILLIS) {
                return ResolverRecoveryClaim(state, null)
            }
        }
        return ResolverRecoveryClaim(state.copy(lastEncryptedRecoveryAtMillis = nowMillis), first)
    }

    fun reset(): ResolverBackoffState = ResolverBackoffState()
}

/**
 * Bounded routine smoke-probe cadence (iOS `DeviceDNSFallbackPolicy.routineSmokeProbeInterval`
 * and its saturation helpers).
 */
object ResolverSmokeProbeCadence {
    const val BASE_INTERVAL_MILLIS: Long = 300_000L

    /** Cadence ceiling; the doubling can never exceed this. */
    const val MAX_INTERVAL_MILLIS: Long = 900_000L

    /** Failures before the cadence starts stretching (transient blips keep the base cadence). */
    const val BACKOFF_ACTIVATION_FAILURES: Int = 5

    /** Saturation point for the persisted consecutive-failure streak. */
    const val MAX_TRACKED_FAILURES: Int = 999

    fun nextFailureCount(current: Int): Int = min(current + 1, MAX_TRACKED_FAILURES)

    /**
     * Routine probe interval for a failure streak.
     *
     * Below the activation count the base interval is returned exactly. At or above it the
     * interval doubles once per further failure, capped at [MAX_INTERVAL_MILLIS]. The exponent is
     * clamped well before the ceiling so a persisted multi-hundred streak cannot overflow.
     */
    fun intervalMillis(consecutiveFailures: Int): Long {
        if (consecutiveFailures < BACKOFF_ACTIVATION_FAILURES) return BASE_INTERVAL_MILLIS
        val doublings = min(consecutiveFailures - BACKOFF_ACTIVATION_FAILURES + 1, 8)
        var interval = BASE_INTERVAL_MILLIS
        repeat(doublings) {
            interval = min(interval * 2, MAX_INTERVAL_MILLIS)
        }
        return interval
    }
}
