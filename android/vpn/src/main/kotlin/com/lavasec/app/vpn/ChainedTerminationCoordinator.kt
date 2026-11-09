package com.lavasec.app.vpn

import com.lavasec.app.vpn.chained.ChainedOwnerSlot

/**
 * Claims a chained owner and runs its terminal action as the engine queue's newest operation.
 *
 * Running as a new [ChainedEngineQueue] operation BEFORE claiming closes the race where a terminal
 * callback removed the failing owner, an `ACTION_RECONNECT` then saw no chained owner and installed
 * a replacement, and the pending action operated on the superseded state — leaving both runtime
 * fields populated (and possibly reporting the superseded chained owner as active), or tearing the
 * replacement down.
 *
 * The queue serializes the claim with every other lifecycle transition, so a concurrent reconnect
 * either completes first (its replacement makes [claimAndRun]'s `candidate` stale, so the claim
 * fails and the action is skipped) or waits until the action finishes. The terminal publication
 * must still run OUTSIDE this call, because it enters the service command owner's monitor and
 * holding the queue there would invert the lock order (and block the service thread behind a slow
 * teardown).
 *
 * [claimAndRun]'s `guard` is evaluated with the queue held, so a closed runtime is never claimed.
 * The action receives the operation's generation token so any establishment it performs (the
 * DNS-only rebuild after a surrender) passes the same current-operation gate.
 */
internal class ChainedTerminationCoordinator<T : Any>(
    private val engineQueue: ChainedEngineQueue,
    private val owners: ChainedOwnerSlot<T>,
) {
    fun <R> claimAndRun(candidate: T, guard: () -> Boolean, action: (Long) -> R): R? =
        engineQueue.run { generation ->
            if (!guard() || !owners.takeIfCurrent(candidate)) null else action(generation)
        }
}
