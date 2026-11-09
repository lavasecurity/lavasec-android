package com.lavasec.app.vpn.chained

import com.lavasec.app.vpn.engine.LavaWgStats

/**
 * Decides when an ESTABLISHED session's peer looks unreachable, so the shared outage budget is
 * armed while the engine probes, instead of waiting for the engine's own `connectionExpired`.
 *
 * ## The gap this closes
 *
 * Reachability can be lost a second after a successful handshake. BoringTun then initiates a
 * fresh reachability handshake once sent traffic has gone unanswered for
 * `KEEPALIVE_TIMEOUT + REKEY_TIMEOUT` (~15 s, `timers.rs:271-277`) and retransmits for
 * `REKEY_ATTEMPT_TIME` (90 s, `timers.rs:217-226`) before reporting `connectionExpired`. An
 * absolute handshake-age check cannot see this: the age is still small, so the 15 s outage
 * budget would not start until ~105 s in.
 *
 * ## The primary signal is a SUSPECT, not a verdict
 *
 * [ChainedLivenessTracker] watches the engine's monotonic `txBytes`/`rxBytes`. `txBytes` counts
 * data bytes handed to `encapsulate` (`mod.rs:260`) and `rxBytes` counts decrypted inner payload
 * (`mod.rs:501`); a keepalive is a zero-length data packet that returns before that line
 * (`mod.rs:466`), so it advances neither. When `txBytes` advances while `rxBytes` stays flat for
 * [LIVENESS_STALL_WINDOW_SECONDS], the peer MAY be gone — but at exactly that window BoringTun
 * has only just INITIATED its reachability handshake. The owner therefore treats this as a
 * suspect: it arms the outage budget and keeps driving the session, so the handshake response is
 * accepted and a genuinely healthy link recovers.
 *
 * The budget is measured from [ChainedLivenessStep.firstUnansweredSendAtSeconds] — the first send
 * that no progress followed — NOT from detection. The detection window and the blackhole budget
 * are the same 15 s window, so charging them separately would hold the user's routes for 30 s.
 *
 * That makes the reset path load-bearing for an ordinary UNIDIRECTIONAL flow. WireGuard does not
 * acknowledge ordinary data, and the vendored engine counts only non-empty inner packets, so a
 * healthy upload-only session has `txBytes` advancing with `rxBytes` flat. What keeps it healthy
 * is the peer's obliged keepalive (`KEEPALIVE_TIMEOUT`): a zero-length transport-data packet that
 * `rxBytes` misses but that [ChainedLivenessTracker.notePeerActivity] records, clearing the stall
 * before the window closes. A path that sends nothing back at all — the genuinely dead one —
 * reaches the window and is bounded out. Only the engine's `connectionExpired` or the budget's
 * watchdog expiry ends such a session.
 *
 * ## The secondary signal is a verdict — only with rekey evidence
 *
 * BoringTun only starts the 120 s rekey when THIS endpoint is the handshake INITIATOR and has
 * sent data (`timers.rs:238-249`); as the responder it does not initiate that rekey, and an idle
 * session never does. So a high handshake age ALONE is not a failed rekey — it is also exactly
 * what a healthy idle or responder-role session looks like. The age is therefore consulted only
 * alongside [ChainedLivenessTracker]'s unanswered-send evidence: that evidence proves the engine
 * started its own reachability handshake (it does so whenever data goes unanswered for
 * `KEEPALIVE + REKEY_TIMEOUT`, whatever the handshake role), so if the rekey window has also
 * passed, a rekey was genuinely initiated and stalled. Threshold
 * [REKEY_LIVENESS_THRESHOLD_SECONDS] allows two `REKEY_TIMEOUT`s (one for a healthy rekey to
 * complete, one for a retransmitted initiation).
 *
 * Everything here is pure over [LavaWgStats] and an injected `nowSeconds`, so a test drives
 * whole outages with synthetic counters and no engine.
 */
internal object ChainedLivenessPolicy {

    /** BoringTun's data-silence keepalive threshold, in seconds (`timers.rs:23`). */
    const val KEEPALIVE_TIMEOUT_SECONDS: Int = 10

    /** BoringTun's handshake retransmission interval, in seconds (`timers.rs:22`). */
    const val REKEY_TIMEOUT_SECONDS: Int = 5

    /**
     * How long sent data may go unanswered before the peer is SUSPECTED lost and the outage
     * budget is armed.
     *
     * `KEEPALIVE_TIMEOUT + REKEY_TIMEOUT` — the same window at which the engine itself starts a
     * fresh handshake, plus one lost-datagram allowance. Measuring from the first unanswered send
     * matches the engine's own `data_packet_sent > aut_packet_received` decision.
     */
    const val LIVENESS_STALL_WINDOW_SECONDS: Int =
        KEEPALIVE_TIMEOUT_SECONDS + REKEY_TIMEOUT_SECONDS

    /** BoringTun's initiator rekey trigger, in seconds (`timers.rs:19`). */
    const val REKEY_AFTER_TIME_SECONDS: Int = 120

    /**
     * How many `REKEY_TIMEOUT`s of headroom the age guard allows: one for the healthy rekey to
     * complete, one for a lost initiation to be retransmitted.
     */
    const val REKEY_LIVENESS_HEADROOM_TIMEOUTS: Int = 2

    /**
     * The handshake age, in seconds, at which a stalled rekey is considered to have FAILED.
     *
     * Not a verdict by itself: the caller must pair it with [ChainedLivenessTracker]'s
     * unanswered-send evidence, or a healthy idle/responder-only session would be torn down here.
     */
    const val REKEY_LIVENESS_THRESHOLD_SECONDS: Int =
        REKEY_AFTER_TIME_SECONDS + REKEY_LIVENESS_HEADROOM_TIMEOUTS * REKEY_TIMEOUT_SECONDS

    /** [REKEY_LIVENESS_THRESHOLD_SECONDS] in the unit [LavaWgStats] reports. */
    const val REKEY_LIVENESS_THRESHOLD_MILLIS: Long = REKEY_LIVENESS_THRESHOLD_SECONDS * 1000L

    /**
     * The secondary guard: [stats] reports an established session whose handshake age has crossed
     * the rekey window. NOT a verdict on its own — callers must combine it with the stall
     * evidence (an unanswered send), or an idle/responder-only session would be torn down at the
     * window though it never initiated a rekey.
     */
    fun isRekeyWindowExceeded(stats: LavaWgStats?): Boolean {
        if (stats == null || stats.timeSinceLastHandshakeMs < 0L) return false
        return stats.timeSinceLastHandshakeMs >= REKEY_LIVENESS_THRESHOLD_MILLIS
    }
}

/**
 * One observation of a [ChainedLivenessTracker]: the tracker to carry forward, whether the peer
 * is SUSPECTED unreachable (a probe should be in flight), and the instant the blackhole clock
 * actually started. Mirrors the carry-forward shape of `ChainedOutageBudget` — a pure policy
 * cannot mutate the caller's copy, so the updated value travels back and the caller persists it.
 */
internal class ChainedLivenessStep(
    val tracker: ChainedLivenessTracker,
    val isStallSuspected: Boolean,
    /**
     * The first send that no progress followed, or `null` when none is outstanding. The outage
     * budget must be measured from HERE, not from the moment the stall is detected, or the 15 s
     * detection window would be added on top of the 15 s budget (a 30 s blackhole).
     */
    val firstUnansweredSendAtSeconds: Int?,
    /**
     * Whether a send is currently outstanding unanswered. [firstUnansweredSendAtSeconds] is a
     * retained anchor (it names the last progress instant after a reset), so the driver consumes
     * it only while this is `true`; the two together are the read-and-clear level the outage
     * driver's liveness sample expects.
     */
    val hasUnansweredSend: Boolean,
)

/**
 * Tracks whether an established session's peer has stopped making DATA progress, as a pure value
 * over the engine's monotonic byte counters.
 *
 * - `rxBytes` advancing, a completed handshake (the handshake age going backwards), or an
 *   authenticated peer datagram recorded by [notePeerActivity] (a keepalive, which `rxBytes`
 *   cannot see) resets the stall.
 * - A `txBytes` advance with no progress starts the stall clock; it stays started until one of
 *   the resets above, and the peer is SUSPECTED lost once the clock reaches
 *   [ChainedLivenessPolicy.LIVENESS_STALL_WINDOW_SECONDS]. The first send's instant is carried
 *   in [ChainedLivenessStep.firstUnansweredSendAtSeconds] so the outage budget can be measured
 *   from it rather than from detection.
 * - A session that sends nothing never starts the clock, so an idle tunnel is not touched.
 *
 * Pure and immutable: [observe] returns a new tracker, so the owner can hold it on its serial
 * dispatcher and a test can drive it directly.
 */
internal class ChainedLivenessTracker private constructor(
    private val lastRxBytes: Long?,
    private val lastTxBytes: Long?,
    private val lastUnansweredSendAtSeconds: Int?,
    private val sentSinceLastRx: Boolean,
    private val lastHandshakeAgeMillis: Long?,
) {

    fun observe(nowSeconds: Int, stats: LavaWgStats): ChainedLivenessStep {
        val previousRx = lastRxBytes
        val previousTx = lastTxBytes
        val previousAge = lastHandshakeAgeMillis

        val rxAdvanced = previousRx == null || stats.rxBytes > previousRx
        val newHandshake = previousAge != null && stats.timeSinceLastHandshakeMs < previousAge
        val sent = previousTx != null && stats.txBytes > previousTx

        var unansweredSince = lastUnansweredSendAtSeconds
        var sentSince = sentSinceLastRx

        if (rxAdvanced || newHandshake) {
            // The peer answered, or a fresh handshake completed: the link is alive again.
            unansweredSince = nowSeconds
            sentSince = false
        } else if (sent) {
            // The first send with no reply observed since the last progress starts the window;
            // later sends leave the start where it is so a steady sender cannot push it forward.
            if (!sentSince) unansweredSince = nowSeconds
            sentSince = true
        }

        val suspect = sentSince &&
            unansweredSince != null &&
            nowSeconds.toLong() - unansweredSince.toLong() >=
            ChainedLivenessPolicy.LIVENESS_STALL_WINDOW_SECONDS

        return ChainedLivenessStep(
            tracker = ChainedLivenessTracker(
                lastRxBytes = stats.rxBytes,
                lastTxBytes = stats.txBytes,
                lastUnansweredSendAtSeconds = unansweredSince,
                sentSinceLastRx = sentSince,
                lastHandshakeAgeMillis = stats.timeSinceLastHandshakeMs,
            ),
            isStallSuspected = suspect,
            firstUnansweredSendAtSeconds = unansweredSince,
            hasUnansweredSend = sentSince,
        )
    }

    /**
     * Records authenticated peer activity observed on the data path — a decrypted transport-data
     * datagram, which includes a keepalive — and clears the stall clock.
     *
     * This is the signal `rxBytes` cannot provide: a keepalive is a zero-length data packet, so
     * it advances `rxBytes` by zero even though it proves the peer holds the session keys. Without
     * it, a healthy upload-only flow (the peer answers with keepalives, never with payload) would
     * look stalled and surrender at the window.
     */
    fun notePeerActivity(nowSeconds: Int): ChainedLivenessTracker =
        ChainedLivenessTracker(
            lastRxBytes = lastRxBytes,
            lastTxBytes = lastTxBytes,
            lastUnansweredSendAtSeconds = nowSeconds,
            sentSinceLastRx = false,
            lastHandshakeAgeMillis = lastHandshakeAgeMillis,
        )

    companion object {
        /** A fresh tracker: no progress seen and no send outstanding. */
        fun start(): ChainedLivenessTracker =
            ChainedLivenessTracker(
                lastRxBytes = null,
                lastTxBytes = null,
                lastUnansweredSendAtSeconds = null,
                sentSinceLastRx = false,
                lastHandshakeAgeMillis = null,
            )
    }
}
