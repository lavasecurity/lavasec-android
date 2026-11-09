package com.lavasec.app.vpn.chained

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The outcome of the chained peer-endpoint preflight the command owner runs before retiring the
 * predecessor's tunnel.
 */
internal enum class ChainedPeerPreflightOutcome {
    /** No preflight ran (chaining off, session paused, or no network available). */
    NOT_ATTEMPTED,

    /** The peer resolved while the predecessor still owned its tunnel. */
    RESOLVED,

    /** The hostname lookup ran and failed or timed out while the predecessor still owned its tunnel. */
    REFUSED,
}

/**
 * Whether start may (re)attempt chaining after the preflight.
 *
 * [ChainedPeerPreflightOutcome.REFUSED] already ran the hostname lookup while the predecessor still
 * owned its tunnel; retrying it inside start (after the predecessor is retired) would leave the
 * device with no TUN during a slow second lookup, so start must fall straight back to DNS-only.
 *
 * [ChainedPeerPreflightOutcome.NOT_ATTEMPTED] is admitted only when the endpoint needs no underlay
 * resolver ([resolutionRequired] is false, i.e. an IP literal): `ChainedEndpointResolver` resolves an
 * IP literal locally and never touches the underlay, whereas admitting an unresolved hostname would
 * repeat the lookup in the no-tunnel gap after the predecessor is retired, bypassing protection on
 * non-lockdown devices.
 */
internal fun chainedAttemptAdmittedAfterPreflight(
    outcome: ChainedPeerPreflightOutcome,
    resolutionRequired: Boolean,
): Boolean = when (outcome) {
    ChainedPeerPreflightOutcome.RESOLVED -> true
    ChainedPeerPreflightOutcome.REFUSED -> false
    ChainedPeerPreflightOutcome.NOT_ATTEMPTED -> !resolutionRequired
}

/**
 * Whether a start must enter (or stay in) awaiting-network recovery instead of committing.
 *
 * A preflight that reached the credential read while no eligible underlay was available records
 * nothing carryable, but it must NOT be consumed as a final DNS-only decision: if a network has
 * appeared by the time start runs, the start must re-enter recovery so the preflight is re-run
 * against it. Treating "preflight needed a network" as authoritative on its own closes the race in
 * which the network arrives between the preflight and the start, which previously stranded the
 * device on DNS-only with no availability callback and no later chained retry.
 */
internal fun chainedStartRequiresAwaitingNetwork(
    eligibleNetworkAvailable: Boolean,
    preflightNetworkUnavailable: Boolean,
): Boolean = !eligibleNetworkAvailable || preflightNetworkUnavailable

/** The next step of the bounded chained peer re-resolution loop. */
internal enum class ChainedEndpointPreflightResolution {
    /** The underlay is stable; keep the address resolved on it. */
    RESOLVED,

    /** The underlay changed; re-resolve the peer on the freshly selected network. */
    RE_RESOLVE,

    /** No eligible network remains; discard the stale snapshot and await a network. */
    UNDERLAY_LOST,
}

/**
 * Decides the next step of the bounded re-resolution loop from the freshly observed underlay.
 *
 * A missing network ([networkAvailable] false) must never reuse the probe's snapshot: the probe
 * network is no longer eligible, so the address resolved on it can no longer be used. Returning it
 * would let a start install a tunnel bound to a dead underlay (or turn an ordinary handoff into a
 * terminal start failure) instead of entering the awaiting-network/reselect path.
 *
 * A stable snapshot resolves. A changed snapshot re-resolves while the retry budget remains; once
 * the budget is spent the probe is still not the current underlay, so it is discarded rather than
 * accepted — accepting it would hand start (or an internal reconnect) an address resolved on an
 * obsolete underlay.
 */
internal fun chainedEndpointPreflightResolution(
    networkAvailable: Boolean,
    sameNetwork: Boolean,
    retries: Int,
    maxRetries: Int,
): ChainedEndpointPreflightResolution = when {
    !networkAvailable -> ChainedEndpointPreflightResolution.UNDERLAY_LOST
    sameNetwork -> ChainedEndpointPreflightResolution.RESOLVED
    retries >= maxRetries -> ChainedEndpointPreflightResolution.UNDERLAY_LOST
    else -> ChainedEndpointPreflightResolution.RE_RESOLVE
}

/**
 * Tracks the identity of the default `Network` a live chained session is bound to, so a platform
 * path callback reports `interfacesChanged` only for an ACTUAL interface change.
 *
 * The Android form of the iOS `ChainedEligibleInterfaceStash`. `registerDefaultNetworkCallback`
 * delivers the ALREADY-CURRENT default network through its first `onAvailable` (and repeats
 * capability updates for it) as soon as it is registered, and the chained owner established on
 * that network: treating the initial sighting as a transition drove an immediate rebind, and
 * before the first handshake `adoptChannel` answers [ChainedRebindOutcome.NO_CURRENT_SESSION], so
 * the driver shut the fresh session into the retry ladder even though no roam had happened.
 *
 * Same-to-same is never a change, and an absence ([observed] with `null`) is recorded but never
 * reported, so a restore is compared against the last non-null identity: losing and regaining the
 * SAME interface is the offline `pathChanged(satisfied = false)` latch's job, not an identity
 * change. Only a DIFFERENT non-null identity — a real interface replacement — reports `true`.
 *
 * Pure over an opaque identity so the decision is host-JVM testable; production passes the
 * platform `Network`, whose equality is its immutable netId.
 *
 * [initial] seeds the baseline with the underlay the chained socket was ESTABLISHED on when the
 * caller has it (finding 4021623299). An empty tracker assumes the callback's first snapshot is
 * the establishment underlay, but a default-network change landing between the socket bind and
 * that first delivery makes the new underlay the baseline instead: no change is reported, the
 * rebind is skipped, and the peer socket stays pinned to the network that just went away until the
 * slower silence detector reacts. Seeding removes the assumption.
 */
internal class ChainedDefaultNetworkIdentity(initial: Any? = null) {
    /**
     * The last observed non-null identity, seeded with the establishment underlay when known.
     */
    private var known: Any? = initial

    /**
     * Records one observed identity and answers whether it is a CHANGE from the last one.
     *
     * `null` means the platform reported no usable network; it is not itself a change and does not
     * overwrite the last identity (a same-interface restore must compare equal).
     */
    fun observed(identity: Any?): Boolean {
        if (identity == null) return false
        val changed = known != null && known != identity
        known = identity
        return changed
    }

    /**
     * Whether [identity] is the currently tracked one, WITHOUT mutating the tracker.
     *
     * The `onLost` callback needs this: on a default-network handoff the platform can deliver the
     * replacement's `onAvailable`/`onCapabilitiesChanged` BEFORE the old default's `onLost`, so
     * the tracker already holds the replacement identity. Reporting that stale `onLost` as an
     * unsatisfied path would set the driver's offline latch, fence the in-flight rebind, stop the
     * ticks, and retire a live session whose replacement underlay is fine. Only the loss of the
     * TRACKED identity is a path loss; any other `onLost` names a network this session never bound
     * to. Structural equality, matching [observed].
     */
    fun isCurrent(identity: Any?): Boolean = identity != null && known == identity
}

/**
 * Serializes each chained-recovery endpoint preflight with the commit that consumes its artifacts.
 *
 * An explicit reconnect and awaiting-network recovery both run the endpoint lookup OUTSIDE the
 * reconnect serialization (so a slow resolver never blocks Stop) and then commit under it, but they
 * write the runtime's single preflight slot (resolved address, opened credentials, attempted flag).
 * Overlapping attempts therefore interleave: the first serialized commit can consume the second
 * attempt's stashed artifacts, install a replacement, and the second then closes that replacement,
 * finds the slot empty, and reaches startup as [ChainedPeerPreflightOutcome.NOT_ATTEMPTED] — which
 * refuses a hostname and silently degrades chaining to DNS-only.
 *
 * Holding this gate across the whole preflight-and-commit makes every commit consume only the
 * artifacts its own preflight produced; a newer recovery waits for the older to finish instead of
 * racing it. The gate is cancellation-aware and is never acquired by `close()`, so a stop still
 * cancels a slow lookup rather than blocking on it.
 */
internal class ChainedRecoverySerializer {
    private val mutex = Mutex()

    suspend fun <T> run(operation: suspend () -> T): T = mutex.withLock { operation() }
}
