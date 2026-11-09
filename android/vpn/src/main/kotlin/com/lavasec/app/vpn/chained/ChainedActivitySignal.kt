package com.lavasec.app.vpn.chained

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The mode of the installed protection runtime.
 *
 * [DNS_ONLY] is the settled fallback after a chained establishment failure or an outage surrender:
 * protection is still on, but no chained retry is scheduled, so the surface must not render it as
 * an in-progress "Recovering". [code] is the stable wire value the JS layer consumes.
 */
enum class ChainedRuntimeMode(val code: String) {
    NONE("none"),
    DNS_ONLY("dnsOnly"),
    CHAINED("chained"),
}

/**
 * One publication of the live chained-activity state.
 *
 * [sequence] advances on EVERY publish, including a false→false DNS-only fallback. A bare Boolean
 * flow suppresses an equal value, so a screen opened during a failed enable would never learn the
 * runtime settled on DNS-only — the version makes every attempt outcome observable. [mode] carries
 * the installed runtime mode so the screen can distinguish an in-progress chained startup
 * ([CHAINED] with [active] false) from a settled DNS-only fallback ([DNS_ONLY]).
 * [runningConfigurationGeneration] is the exact configuration latched by the installed owner,
 * not the currently saved configuration. Zero means no chained rotation or an older publisher
 * that does not know its identity. It is cleared on NONE/DNS_ONLY publications.
 */
data class ChainedActivitySignal(
    val active: Boolean,
    val mode: ChainedRuntimeMode,
    val sequence: Long,
    val runningConfigurationGeneration: Long = 0L,
)

/**
 * The next signal for a publish. The sequence advances unconditionally, so republishing the same
 * [active]/[mode] pair is still a distinct emission.
 */
internal fun nextChainedActivitySignal(
    previous: ChainedActivitySignal,
    active: Boolean,
    mode: ChainedRuntimeMode,
    runningConfigurationGeneration: Long = 0L,
): ChainedActivitySignal = ChainedActivitySignal(
    active = active,
    mode = mode,
    sequence = previous.sequence + 1L,
    runningConfigurationGeneration = if (mode == ChainedRuntimeMode.CHAINED) {
        runningConfigurationGeneration.coerceAtLeast(0L)
    } else {
        0L
    },
)

/**
 * Whether a claimed chained owner's terminal action must clear the activity publication.
 *
 * A `REBUILD_DNS_ONLY` action installs the DNS-only fallback, which publishes [ChainedRuntimeMode.DNS_ONLY].
 * An unconditional post-claim clear would overwrite that settled mode with
 * [ChainedRuntimeMode.NONE], so the screen would render the fallback as Recovering. Clear only
 * when the action left no live runtime; a DNS-only runtime (or a replacement owner) publishes its
 * own mode and must not be clobbered.
 */
internal fun shouldClearChainedActivityOnClaim(
    hasChainedOwner: Boolean,
    hasDnsOnlyRuntime: Boolean,
): Boolean = !hasChainedOwner && !hasDnsOnlyRuntime

/**
 * The activity/mode pair a chained owner publishes for one engine state.
 *
 * [ChainedEngineState.Established] carries a fresh health sample on every ticker interval, so the
 * state value changes even while the session stays established. Reducing to [ChainedEngineState.isEstablished]
 * — the only field the surface consumes — lets the publication stream deduplicate those samples.
 */
internal fun ChainedEngineState.chainedActivityMode(): Pair<Boolean, ChainedRuntimeMode> =
    isEstablished to ChainedRuntimeMode.CHAINED

/**
 * The deduplicated activity/mode publications for a chained owner's engine-state flow.
 *
 * [distinctUntilChanged] suppresses the repeated `Established(stats)` samples that keep
 * `isEstablished` true; without it each sample advanced [ChainedActivitySignal.sequence] and made
 * an open screen re-read status (a real Keystore decrypt) every ticker interval. Genuine
 * `established → recovering/surrendered` (and back) transitions still publish.
 */
internal fun Flow<ChainedEngineState>.chainedActivityPublications():
    Flow<Pair<Boolean, ChainedRuntimeMode>> =
    map { it.chainedActivityMode() }.distinctUntilChanged()
