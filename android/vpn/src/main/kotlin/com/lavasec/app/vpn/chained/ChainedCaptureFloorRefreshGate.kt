package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedDnsCaptureFloor

/**
 * One installed owner's route-refresh admission, confined by its runtime's lifecycle lock.
 * This does not change the installed floor: only a successor's completed TUN installation does.
 *
 * Android service delivery acknowledges scheduling rather than runtime adoption. A delivery
 * refusal can be retried immediately. A scheduled request that left the exact predecessor current
 * can be retried after its bounded acknowledgement window; a superseded predecessor never retries.
 */
internal class ChainedCaptureFloorRefreshGate<O : Any>(
    private val installedOwner: O,
    private val installedFloor: ChainedDnsCaptureFloor,
    val retryAfterMillis: Long = 5_000L,
) {
    init { require(retryAfterMillis > 0L) }
    private var retired = false
    private var sequence = 0L
    private var pending: Long? = null
    private var requestedAtMillis = 0L

    fun claim(currentOwner: O?, observedFloor: ChainedDnsCaptureFloor?, nowMillis: Long): Long? {
        if (retired || currentOwner !== installedOwner || observedFloor == null ||
            installedFloor.ipv4Routes.isEmpty() || observedFloor.hasSameDestinations(installedFloor)
        ) return null
        if (pending != null && nowMillis - requestedAtMillis < retryAfterMillis) return null
        sequence += 1L
        pending = sequence
        requestedAtMillis = nowMillis
        return sequence
    }

    /** A stale delivery completion cannot reset a newer pending refresh. */
    fun rejected(attempt: Long) {
        if (!retired && pending == attempt) pending = null
    }

    fun retire() { retired = true; pending = null }
}
