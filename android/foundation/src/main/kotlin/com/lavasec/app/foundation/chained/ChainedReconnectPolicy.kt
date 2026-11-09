package com.lavasec.app.foundation.chained

/**
 * A chained lifecycle's surrender reason. Log/health vocabulary, never user copy; the raw
 * spellings are shared with iOS `ChainedSurrenderReason` so both clients name the same condition
 * in a device log and the persisted marker is read identically across the fleet.
 */
enum class ChainedSurrenderReason(val rawValue: String) {
    /** The peer remained unreachable until the outage budget expired. */
    BUDGET_EXHAUSTED("budgetExhausted"),

    /** Retrying cannot clear the engine fault. */
    ENGINE_UNUSABLE("engineUnusable"),

    /** The packet loop violated the engine contract. */
    CALLER_CONTRACT_VIOLATION("callerContractViolation"),

    /** The clock could not measure the outage budget safely. */
    CLOCK_UNUSABLE("clockUnusable"),
    ;

    /**
     * Only a reachability failure can recover on another network; the other faults remain
     * terminal, because a new network changes nothing about a malformed argument, a mismatched
     * ABI, or a clock that runs backwards.
     */
    val isResolvableByNetworkChange: Boolean
        get() = this == BUDGET_EXHAUSTED

    /** The persisted marker namespace; writers and readers must use the same spelling. */
    val markerReason: String
        get() = MARKER_REASON_PREFIX + rawValue

    /**
     * Marks recovery ready only after suppression was persisted for the next lifecycle.
     *
     * Legacy or unconfirmed markers stay terminal because they carry no durable-recovery
     * evidence: a marker that was never written is not proof the previous session actually
     * surrendered.
     */
    fun markerReason(suppressionPersisted: Boolean): String =
        if (suppressionPersisted && isResolvableByNetworkChange) {
            "chained-recovery-ready:" + rawValue
        } else {
            markerReason
        }

    companion object {
        /** The persisted marker namespace, shared with iOS. */
        const val MARKER_REASON_PREFIX: String = "chained-surrendered:"

        /** Only a confirmed recoverable surrender may authorize degraded DNS-only recovery. */
        fun markerReasonIsRecoverableSurrender(reason: String): Boolean =
            reason == BUDGET_EXHAUSTED.markerReason(suppressionPersisted = true)
    }
}

/**
 * A failure reported by the WireGuard engine across the C ABI. Mirrors iOS
 * `WireGuardEngineError`; the negative ABI codes split into per-packet verdicts and
 * session-ending faults, and conflating them is the reconnect-storm class of bug.
 *
 * A sealed class rather than an enum because `unrecognized` carries the engine's raw status code,
 * which is the one field a field log needs to resynchronize the wrapper with the ABI header.
 */
sealed class WireGuardEngineError(val logValue: String) {
    /** A null pointer, a malformed argument pair, or overlapping source/destination buffers. */
    data object InvalidArgument : WireGuardEngineError("invalid-argument")

    /** The destination buffer was smaller than the call's documented minimum. */
    data object DestinationBufferTooSmall : WireGuardEngineError("destination-buffer-too-small")

    /** A data-path call ran with no established session (handshake incomplete or expired). */
    data object NoCurrentSession : WireGuardEngineError("no-current-session")

    /** The peer or its rate limiter is under load; retry after a backoff. */
    data object UnderLoad : WireGuardEngineError("under-load")

    /** One datagram failed parse or authentication. Drop it and continue. */
    data object ProtocolViolation : WireGuardEngineError("protocol-violation")

    /**
     * An inbound datagram larger than the maximum this wrapper accepts.
     *
     * A per-packet verdict like [ProtocolViolation], separated from [DestinationBufferTooSmall]
     * on purpose: the size is chosen by a REMOTE sender, so it cannot be attributed to our buffer
     * discipline and must not tear the session down.
     */
    data object OversizedDatagram : WireGuardEngineError("oversized-datagram")

    /** The session passed its rekey/reject deadline — the reconnect escalation signal. */
    data object ConnectionExpired : WireGuardEngineError("connection-expired")

    /** An engine-internal failure such as a lock poisoned by an earlier fault. */
    data object EngineInternal : WireGuardEngineError("engine-internal")

    /** An outbound packet exceeded the engine's IP packet ceiling. */
    data object PacketTooLarge : WireGuardEngineError("packet-too-large")

    /** The engine refused to construct a session from the supplied key material. */
    data object SessionCreationFailed : WireGuardEngineError("session-creation-failed")

    /** A status code this wrapper does not know — treat as fatal and resynchronize with the ABI. */
    data class Unrecognized(val code: Int) : WireGuardEngineError("unrecognized-$code")
}

/**
 * A build fault that the session factory diagnosed well enough to say whether rebuilding could
 * do better. Mirrors iOS `ChainedSessionBuildFailure`.
 *
 * The factory, not the reconnect policy, owns the transience claim: only the code that attempted
 * the construction saw what went wrong. An error that does not implement this interface is
 * treated as permanent by [ChainedSessionEndCause.buildFailure], because handing an unclassified
 * fault the retry ladder spends the user's whole blackhole budget on a guess.
 */
interface ChainedSessionBuildFailure {
    /** Whether a fresh attempt could plausibly succeed where this one did not. */
    val warrantsAnotherAttempt: Boolean
}

/**
 * A cause that actually ended the session — the only thing [ChainedReconnectPolicy.decision]
 * accepts.
 *
 * ## Why this type exists rather than a bare engine error
 *
 * A bare `WireGuardEngineError` would let a caller hand the reconnect policy a PER-PACKET
 * verdict. Those would fall through to the retry path, so `protocolViolation`, `underLoad`,
 * `noCurrentSession` and `oversizedDatagram` — every one a condition the data-path classifier
 * drops per packet — would produce a retry. Two public entry points would give opposite answers
 * about the same value, and one hostile datagram could keep a user permanently disconnected
 * (the LAV-80 class this stack is built to avoid).
 *
 * Making the per-packet state unrepresentable is the answer that does not trade one contradiction
 * for a worse behaviour: [engineVerdict] returns `null` for a per-packet error, so the
 * disagreement cannot be expressed. The data-path classifier remains the sole door from an engine
 * outcome to this type.
 *
 * ## A build failure's origin decides it, not its engine error
 *
 * A session that was never CONSTRUCTED reports `sessionCreationFailed` whatever went wrong, so
 * the engine error carries none of the factory's diagnosis. Classifying on the error alone would
 * make a momentary interface handoff (`noEligibleInterface`, documented as transient) read as the
 * deterministic fault that case names, ending chained mode for the rest of the tunnel lifecycle.
 * [Origin] carries the factory's own answer instead, which is why [buildFailure] is the only door
 * from a thrown build error.
 */
class ChainedSessionEndCause private constructor(
    /** The engine error that ended the session. NOT sufficient on its own to classify a cause. */
    val error: WireGuardEngineError,
    /** What produced this cause, and for a build failure the factory's transience diagnosis. */
    val origin: Origin,
) {
    /**
     * Where a cause came from, which is what says whether [error] is the whole story.
     */
    enum class Origin {
        /** The engine ended a constructed session, and [error] is its verdict. */
        ENGINE_VERDICT,

        /**
         * The session was never constructed, and the fault was a property of the instant — a
         * reading of the system the next attempt takes again. Spends a ladder rung.
         */
        TRANSIENT_BUILD_FAILURE,

        /** The session was never constructed, and a rebuild reaches the same refusal. Surrenders. */
        PERMANENT_BUILD_FAILURE,
    }

    companion object {
        /**
         * The cause carried by a session-ending engine outcome, or `null` for a per-packet
         * verdict.
         *
         * `null` is not a failure the caller reports — a drain loop asks this of every outcome
         * and escalates only when it gets a value. The guard is structural: there is no value of
         * this type that carries a per-packet error, so `decision` cannot be called with one.
         */
        fun engineVerdict(error: WireGuardEngineError): ChainedSessionEndCause? =
            if (isSessionEnding(error)) {
                ChainedSessionEndCause(error, Origin.ENGINE_VERDICT)
            } else {
                null
            }

        /**
         * A session that never started, for a reason another attempt cannot clear.
         *
         * `WireGuardSession` construction throws before any data-path outcome exists, so this
         * origin cannot arrive through an engine verdict at all.
         */
        val sessionCreationFailed: ChainedSessionEndCause =
            ChainedSessionEndCause(
                WireGuardEngineError.SessionCreationFailed,
                Origin.PERMANENT_BUILD_FAILURE,
            )

        /**
         * The end of an attempt whose session was never constructed, carrying the factory's
         * diagnosis of whether the next one could do better.
         *
         * THE ONLY DOOR from a thrown build error to this type. An error this module cannot
         * triage is PERMANENT, deliberately: only [ChainedSessionBuildFailure] carries a
         * transience claim, and anything else — a secret store's own error, most of all — is a
         * fault nobody here has classified. Surrendering is the outcome that keeps working
         * internet with DNS-only filtering, which is why it is the answer when we do not know.
         */
        fun buildFailure(error: Throwable?): ChainedSessionEndCause {
            val diagnosed = error as? ChainedSessionBuildFailure
            return if (diagnosed?.warrantsAnotherAttempt == true) {
                ChainedSessionEndCause(
                    WireGuardEngineError.SessionCreationFailed,
                    Origin.TRANSIENT_BUILD_FAILURE,
                )
            } else {
                sessionCreationFailed
            }
        }

        /**
         * Whether an engine outcome ends the session, matching the data-path classifier.
         *
         * The four `false` cases are per-packet verdicts. Every case is written out rather than
         * folded into an `else`, so a new engine error must make a deliberate choice instead of
         * silently inheriting whichever group it happened to be listed with.
         */
        fun isSessionEnding(error: WireGuardEngineError): Boolean = when (error) {
            WireGuardEngineError.ProtocolViolation,
            WireGuardEngineError.UnderLoad,
            WireGuardEngineError.NoCurrentSession,
            WireGuardEngineError.OversizedDatagram,
            -> false

            WireGuardEngineError.InvalidArgument,
            WireGuardEngineError.DestinationBufferTooSmall,
            WireGuardEngineError.ConnectionExpired,
            WireGuardEngineError.EngineInternal,
            WireGuardEngineError.PacketTooLarge,
            WireGuardEngineError.SessionCreationFailed,
            is WireGuardEngineError.Unrecognized,
            -> true
        }

        /**
         * Whether ending the session should be followed by another attempt, matching the
         * data-path classifier's `warrantsAnotherAttempt`.
         *
         * `connectionExpired` is the ordinary wire condition. `engineInternal` is a poisoned lock
         * and a FRESH session gets a fresh lock, so it earns another attempt; `unrecognized` and
         * `sessionCreationFailed` are deterministic and do not. This must agree with
         * [ChainedReconnectPolicy]'s own branches, and the reconnect tests assert the pairing for
         * every error rather than leaving the two to drift.
         */
        fun warrantsAnotherAttempt(error: WireGuardEngineError): Boolean = when (error) {
            WireGuardEngineError.ConnectionExpired,
            WireGuardEngineError.EngineInternal,
            -> true

            WireGuardEngineError.InvalidArgument,
            WireGuardEngineError.DestinationBufferTooSmall,
            WireGuardEngineError.NoCurrentSession,
            WireGuardEngineError.UnderLoad,
            WireGuardEngineError.ProtocolViolation,
            WireGuardEngineError.OversizedDatagram,
            WireGuardEngineError.PacketTooLarge,
            WireGuardEngineError.SessionCreationFailed,
            is WireGuardEngineError.Unrecognized,
            -> false
        }
    }

    /** Whether this cause should be followed by another attempt, per its origin. */
    val warrantsAnotherAttempt: Boolean
        get() = when (origin) {
            Origin.TRANSIENT_BUILD_FAILURE -> true
            Origin.PERMANENT_BUILD_FAILURE -> false
            Origin.ENGINE_VERDICT -> warrantsAnotherAttempt(error)
        }

    // A cause is a value: two causes with the same error and origin are interchangeable, and the
    // tests compare decisions built from them.
    override fun equals(other: Any?): Boolean =
        other is ChainedSessionEndCause && other.error == error && other.origin == origin

    override fun hashCode(): Int = 31 * error.hashCode() + origin.hashCode()

    override fun toString(): String = "ChainedSessionEndCause(error=${error.logValue}, origin=$origin)"
}

/**
 * What to do after the chained session ends. Mirrors iOS `ChainedReconnectDecision`.
 */
sealed interface ChainedReconnectDecision {
    /** Whether this decision abandons chained mode for the rest of the lifecycle. */
    val surrendersChaining: Boolean

    /** Stable identifier for device logs. Never user copy. */
    val logValue: String

    /**
     * Rebuild the session after [afterSeconds]. [attempt] is 1-based, for logging.
     *
     * [attemptDeadlineSeconds] is an UPPER BOUND on how long the rebuilt session may run, valid
     * only if the attempt starts on time. The engine does not return control while a handshake is
     * in flight — BoringTun retransmits for `REKEY_ATTEMPT_TIME`, 90 seconds, before it produces
     * `connectionExpired` — so a caller that simply starts the session and waits for the engine
     * to give up has left the outage budget unenforced whatever this value says. It has to be
     * armed as a watchdog timer by the caller, recomputed from elapsed time at the moment the
     * attempt starts, not carried verbatim past the wait.
     */
    data class Retry(
        val afterSeconds: Int,
        val attempt: Int,
        val attemptDeadlineSeconds: Int,
    ) : ChainedReconnectDecision {
        override val surrendersChaining: Boolean get() = false

        // The deadline is in the log line because a field report of a long chained outage has to
        // distinguish "we allowed that" from "the timer was never armed".
        override val logValue: String
            get() = "retry-$attempt-in-${afterSeconds}s-deadline-${attemptDeadlineSeconds}s"
    }

    /** Stop trying and restart the tunnel into DNS-only, with [reason]. */
    data class FallBackToDNSOnly(
        val reason: ChainedSurrenderReason,
    ) : ChainedReconnectDecision {
        override val surrendersChaining: Boolean get() = true
        override val logValue: String get() = "fall-back-dns-only-${reason.rawValue}"
    }
}

/**
 * How long the tunnel may keep claiming traffic it cannot carry. Mirrors iOS
 * `ChainedReconnectPolicy`.
 *
 * ## This is an outage budget, not a retry count
 *
 * Chained mode claims `0.0.0.0/0` and `::/0`, so while the session is down the user has no
 * internet at all — packets enter the tunnel and go nowhere. "Retry five times with exponential
 * backoff" sounds prudent and quietly spends a minute of the user's connectivity to find out
 * something WireGuard usually knows in seconds. So the policy is expressed as the question a
 * user would ask — how long may this go on? — and the retry schedule is derived from
 * [MAXIMUM_BLACKHOLE_SECONDS] rather than the other way round. Falling back is not a failure
 * state: DNS-only still filters and still has working internet, strictly better than holding
 * traffic hostage to a handshake that is not completing.
 *
 * ## Some causes are not worth one retry
 *
 * A DETERMINISTIC fault cannot be repaired by trying again: a status code this wrapper does not
 * recognize means the declarations and the engine binary disagree, and a construction refused
 * for a reason that does not depend on the moment refuses identically next time. `unrecognized`
 * surrenders immediately, and so does a build failure the factory diagnosed as permanent. A
 * transient build failure — a handoff that left no eligible interface for an instant — spends a
 * rung exactly like a wire condition, with the same budget arithmetic and no refund.
 *
 * A poisoned lock is NOT deterministic: `engineInternal` is fixed by rebuilding, because a fresh
 * session gets a fresh lock, so it earns the retry budget. Our own contract violations —
 * `invalidArgument`, `destinationBufferTooSmall`, `packetTooLarge` — surrender under
 * [ChainedSurrenderReason.CALLER_CONTRACT_VIOLATION] rather than `engineUnusable`, because
 * rebuilding retains the malformed argument or undersized buffer and the distinction tells a
 * field log which code to look at.
 *
 * Every one of these classifications matches [ChainedSessionEndCause.warrantsAnotherAttempt] /
 * `isSessionEnding`; the test suite asserts that agreement over every engine error rather than
 * trusting the two switches to be edited together.
 *
 * ## Pure, no clock
 *
 * Every entry point is a pure function of its arguments. Elapsed time is passed in (measured by
 * the caller from a monotonic source); nothing here reads a clock, so the schedule and the
 * watchdog instants are executable in a unit test by construction rather than by waiting.
 */
object ChainedReconnectPolicy {
    /**
     * The longest the tunnel may hold traffic while unable to forward it.
     *
     * Fifteen seconds, not sixty. A WireGuard handshake that is going to complete normally
     * completes in about one round trip, and the engine already retries internally on its own
     * ~5 s cadence — so a session still down after three delays is not usually one delay away
     * from working. What the extra time would buy is a longer stretch of a user staring at a
     * dead connection while the VPN reports itself connected.
     */
    const val MAXIMUM_BLACKHOLE_SECONDS: Int = 15

    /**
     * The least remaining budget worth starting a session with.
     *
     * Derived from the engine's retransmission cadence rather than picked, and the derivation has
     * THREE terms, not one:
     *
     * 1. `REKEY_TIMEOUT` — 5 s. BoringTun sends the handshake initiation once and does not retry
     *    before this. A window shorter than it buys exactly one initiation and zero retransmits,
     *    so a single lost datagram — the ordinary reason a handshake fails — makes the attempt
     *    unable to recover while it spends the rest of the outage budget.
     * 2. Clock flooring — up to 1 s. A monotonic reading truncated to whole seconds stamps an
     *    attempt beginning at 10.99 s as 10, while its watchdog is scheduled at absolute 16.0 s.
     *    The authorization says 6 seconds; the attempt gets 5.01.
     * 3. Pump quantisation — up to ~275 ms. `update_timers` is the only thing that can emit the
     *    retransmit, and it runs on the outage pump: [OUTAGE_TICK_INTERVAL_MILLIS] plus
     *    [OUTAGE_TICK_LEEWAY_MILLIS]. The retransmit therefore lands up to 275 ms AFTER the 5 s
     *    threshold, not at it.
     *
     * Terms 2 and 3 compose in the worst case — 5 + 1 + 0.275 = 6.275 — so 6 was not enough:
     * an attempt starting late in a clock second could be torn down at 5.01 s of real time with
     * the retransmit still 0.26 s away, which is the precise failure this constant prevents. 7.
     */
    const val MINIMUM_USEFUL_ATTEMPT_SECONDS: Int = 7

    /**
     * The outage pump's tick interval, in milliseconds.
     *
     * Pure arithmetic only — the DispatchSource scheduling that consumes it lives in the native
     * control plane, not in this policy. Kept here because [MINIMUM_USEFUL_ATTEMPT_SECONDS] is
     * derived from the pump cadence and the two must move together.
     */
    const val OUTAGE_TICK_INTERVAL_MILLIS: Int = 250

    /**
     * How late an outage tick may run, in milliseconds.
     *
     * A tick, and therefore the handshake retransmit it can emit, can land after its nominal
     * instant by this much; the attempt floor has to clear it.
     */
    const val OUTAGE_TICK_LEEWAY_MILLIS: Int = 25

    /**
     * How much of the outage budget is left, given time already spent.
     *
     * THE WATCHDOG THIS FEEDS MUST BE ARMED BEFORE THE FIRST HANDSHAKE, not after the first
     * failure. [decision] is consulted only when a session ENDS, and the engine does not end one
     * promptly: BoringTun retransmits an in-flight handshake for `REKEY_ATTEMPT_TIME` — 90
     * seconds — before reporting `connectionExpired`. So on the very first loss of reachability
     * the policy is not consulted at all for a minute and a half, and a per-attempt deadline
     * attached to a REBUILT session never gets the chance to fire. Call this at outage start to
     * size that watchdog, and again immediately before each attempt begins.
     *
     * Saturates at zero rather than going negative, and never throws on hostile input.
     */
    fun remainingBlackholeSeconds(elapsedSeconds: Int): Int {
        val spent = elapsedSeconds.coerceAtLeast(0)
        if (spent >= MAXIMUM_BLACKHOLE_SECONDS) return 0
        return MAXIMUM_BLACKHOLE_SECONDS - spent
    }

    /**
     * Delay before the given 1-based attempt.
     *
     * Doubling from one second, capped at `1 shl 8` = 256 s. The first retry is deliberately
     * quick — the common cause is a network transition where the peer is reachable again almost
     * immediately — and the growth keeps a genuinely unreachable peer from being hammered.
     *
     * Although the outage budget will surrender long before the cap matters, the cap still binds
     * so a caller-side off-by-one (or a nonsensical attempt number) cannot produce a zero,
     * negative, or overflowing delay that spins the reconnect loop at full speed.
     */
    fun delaySeconds(forAttempt: Int): Int {
        if (forAttempt < 1) return 1
        return 1 shl minOf(forAttempt - 1, 8)
    }

    /**
     * Decides what happens after a session ends.
     *
     * This is consulted only at session BOUNDARIES, which is why it cannot enforce the budget by
     * itself — see [remainingBlackholeSeconds]. [elapsedSeconds] is measured from the moment
     * traffic STOPPED, not from the current session boundary: measuring from the session boundary
     * would restart the budget on every attempt and it would never expire.
     *
     * @param completedAttempts how many reconnects have already been made this outage.
     * @param elapsedSeconds seconds since the outage began, measured by the caller's monotonic
     *   clock.
     * @param cause what ended the session, obtainable only from a session-ending engine verdict
     *   or a diagnosed build failure.
     */
    fun decision(
        completedAttempts: Int,
        elapsedSeconds: Int,
        cause: ChainedSessionEndCause,
    ): ChainedReconnectDecision {
        when (cause.origin) {
            ChainedSessionEndCause.Origin.PERMANENT_BUILD_FAILURE -> {
                // A construction the factory diagnosed as refusing identically next time: bad key
                // material, an unusable MTU, a store re-serving one read. Rebuilding runs the
                // same code to the same answer, so the budget would be spent proving it.
                return ChainedReconnectDecision.FallBackToDNSOnly(
                    ChainedSurrenderReason.ENGINE_UNUSABLE,
                )
            }

            ChainedSessionEndCause.Origin.TRANSIENT_BUILD_FAILURE -> {
                // FALLS THROUGH TO THE BUDGET ARITHMETIC. A handoff that left the factory with no
                // interface to bind to for an instant costs one rung, like any other attempt that
                // failed — elapsedSeconds is the OUTAGE's and is untouched here, so the failure
                // neither refunds nor extends anything.
            }

            ChainedSessionEndCause.Origin.ENGINE_VERDICT -> {
                when (cause.error) {
                    WireGuardEngineError.InvalidArgument,
                    WireGuardEngineError.DestinationBufferTooSmall,
                    WireGuardEngineError.PacketTooLarge,
                    -> {
                        // Contract violations, classified as caller bugs that do not warrant
                        // another attempt. Rebuilding does not change a malformed argument or an
                        // undersized buffer, so retrying would spend the outage budget rebuilding
                        // a session around the same defect.
                        return ChainedReconnectDecision.FallBackToDNSOnly(
                            ChainedSurrenderReason.CALLER_CONTRACT_VIOLATION,
                        )
                    }

                    is WireGuardEngineError.Unrecognized,
                    WireGuardEngineError.SessionCreationFailed,
                    -> {
                        // Deterministic faults in the object, not the network: an unknown status
                        // code means the wrapper and the engine binary disagree, and a
                        // construction refused on fixed configuration refuses identically next
                        // time. (A FAILED BUILD carries the factory's diagnosis in `origin` and is
                        // answered above; `sessionCreationFailed` here is the engine's own verdict
                        // on a session it was asked to build.)
                        return ChainedReconnectDecision.FallBackToDNSOnly(
                            ChainedSurrenderReason.ENGINE_UNUSABLE,
                        )
                    }

                    WireGuardEngineError.ConnectionExpired,
                    WireGuardEngineError.EngineInternal,
                    -> {
                        // The two session-ending causes worth another attempt: a rekey that
                        // missed its window, and a poisoned lock that a fresh session clears.
                    }

                    WireGuardEngineError.ProtocolViolation,
                    WireGuardEngineError.UnderLoad,
                    WireGuardEngineError.NoCurrentSession,
                    WireGuardEngineError.OversizedDatagram,
                    -> {
                        // NOT REACHABLE through `cause`: all four are per-packet verdicts, so
                        // ChainedSessionEndCause refuses to carry them. Written out rather than
                        // folded into the branch above because a future edit that made one
                        // session-ending must land on a deliberate choice rather than inherit
                        // whichever group it happened to be listed with. The choice, if that ever
                        // happens: retry, because a single hostile datagram permanently
                        // downgrading the user is the LAV-80 outcome.
                    }
                }
            }
        }

        // Both of these saturate rather than throw. This is a PUBLIC entry point that already
        // normalizes negative input, so it treats hostile values as in scope on one side and must
        // not crash on the other — in a tunnel a thrown error drops the user to no protection at
        // all rather than to the DNS-only fallback this type exists to reach.
        val normalizedAttempts = completedAttempts.coerceAtLeast(0)
        val attempt = if (normalizedAttempts == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            normalizedAttempts + 1
        }
        val delay = delaySeconds(attempt)

        // The budget is checked against the delay we are ABOUT to spend, plus time reserved for
        // the session itself. Waiting past the deadline to discover we are past it would be the
        // same mistake in slower motion, and a check that only required
        // `elapsed + delay <= budget` authorized a retry with nothing left to run in.
        val spent = elapsedSeconds.coerceAtLeast(0)
        val spentPlusDelay = spent.toLong() + delay.toLong()
        val remainingAfterDelay = if (spentPlusDelay > Int.MAX_VALUE.toLong()) {
            Int.MIN_VALUE
        } else {
            MAXIMUM_BLACKHOLE_SECONDS - spentPlusDelay.toInt()
        }
        if (remainingAfterDelay < MINIMUM_USEFUL_ATTEMPT_SECONDS) {
            return ChainedReconnectDecision.FallBackToDNSOnly(
                ChainedSurrenderReason.BUDGET_EXHAUSTED,
            )
        }
        return ChainedReconnectDecision.Retry(
            afterSeconds = delay,
            attempt = attempt,
            attemptDeadlineSeconds = remainingAfterDelay,
        )
    }
}

/**
 * One clock observation of an outage budget together with the decision it produced.
 *
 * [ChainedOutageBudget] is a pure value type, but [ChainedOutageBudget.decisionForSessionEnd] and
 * [ChainedOutageBudget.watchdogSurrender] both read the injected clock, and a pure method cannot
 * mutate the caller's copy. Returning the updated budget alongside the decision is what lets the
 * caller PERSIST that reading: without it the observation was computed and discarded, so the next
 * call measured its delta from the previous high-water mark and a backward jump between the two
 * calls went unnoticed — a clock that should have been refused as unusable was absorbed, and
 * elapsed accounting silently restarted.
 *
 * [decision] is the reconnect decision. It is `null` only for
 * [ChainedOutageBudget.watchdogSurrender] while budget remains — "carry on with the in-flight
 * attempt" — because the caller must still persist [budget] in that case.
 * [ChainedOutageBudget.decisionForSessionEnd] always carries a non-null decision.
 */
class ChainedOutageStep(
    /** The budget after its instant was observed. Persist this for the next call. */
    val budget: ChainedOutageBudget,
    /** The decision this observation produced, or `null` when the watchdog is to carry on. */
    val decision: ChainedReconnectDecision?,
)

/**
 * The outage budget as a pure value type over an injected monotonic clock.
 *
 * This is the Android mirror of the budget-accounting subset of iOS
 * `ChainedOutageSupervisor`: the part that decides whether the tunnel is timing an outage, how
 * much of the budget is left, and which reconnect decision a session end produces. The ticket /
 * receipt / outage-epoch machinery stays native in the Slice-4 control plane — it protects
 * against concurrent sessions and duplicated callbacks, which are properties of the runner and
 * its timers rather than of a pure policy.
 *
 * Every method takes a monotonic `nowSeconds` reading and returns a new value; nothing here
 * reads a clock, so a test drives a whole outage in microseconds. A backward reading is
 * REPORTED ([clockIsUnusable]) rather than absorbed: a monotonic clock cannot go backwards, so a
 * backward reading is evidence the caller passed a wall clock, and the budget cannot be enforced
 * on a time source that moves both ways.
 */
class ChainedOutageBudget private constructor(
    /** When the current outage began, or `null` while traffic is flowing. */
    val outageStartedAtSeconds: Int?,
    /** Attempts completed during the current outage. */
    val completedAttempts: Int,
    private val accumulatedElapsedSeconds: Int,
    private val lastObservedAtSeconds: Int?,
    /** Whether the caller's clock was observed moving backwards this outage. */
    val clockIsUnusable: Boolean,
) {
    /** Whether an outage is currently being timed. */
    val isTimingAnOutage: Boolean get() = outageStartedAtSeconds != null

    /**
     * Records that traffic has stopped. Idempotent by design: the first call wins.
     *
     * A second call would restart the clock, and a clock that restarts is not a budget — repeated
     * path flaps would each reset it and the tunnel could blackhole indefinitely while every
     * individual measurement looked short.
     */
    fun beginOutage(nowSeconds: Int): ChainedOutageBudget =
        if (isTimingAnOutage) {
            this
        } else {
            ChainedOutageBudget(
                outageStartedAtSeconds = nowSeconds,
                completedAttempts = 0,
                accumulatedElapsedSeconds = 0,
                lastObservedAtSeconds = nowSeconds,
                clockIsUnusable = false,
            )
        }

    /**
     * Records that traffic is flowing again, ending the outage and resetting the ladder.
     *
     * A session that recovers must not carry its attempt count or elapsed time into the next
     * outage, or the second outage would start with a budget already partly spent.
     */
    fun endOutage(): ChainedOutageBudget = ChainedOutageBudget(
        outageStartedAtSeconds = null,
        completedAttempts = 0,
        accumulatedElapsedSeconds = 0,
        lastObservedAtSeconds = null,
        clockIsUnusable = false,
    )

    /**
     * Banks any forward movement since the last observation and records the new reading.
     *
     * Separate from [elapsedSeconds] so reading stays non-mutating; the caller observes on its own
     * cadence. Only forward movement counts: a backward step contributes nothing and takes
     * nothing away, so time already accumulated stays accumulated.
     */
    fun observe(nowSeconds: Int): ChainedOutageBudget {
        if (!isTimingAnOutage) return this
        val wentBackwards = lastObservedAtSeconds != null && nowSeconds < lastObservedAtSeconds
        return ChainedOutageBudget(
            outageStartedAtSeconds = outageStartedAtSeconds,
            completedAttempts = completedAttempts,
            accumulatedElapsedSeconds = elapsedSeconds(nowSeconds),
            lastObservedAtSeconds = nowSeconds,
            clockIsUnusable = clockIsUnusable || wentBackwards,
        )
    }

    /**
     * Seconds spent in the current outage, saturating and never negative.
     *
     * Accumulating forward deltas between successive observations is the only shape that both
     * refuses a backward jump and does not freeze after one. A high-water mark of displacement
     * from the start would stop a refund but then hold the elapsed value constant until the clock
     * caught up to its previous reading.
     */
    fun elapsedSeconds(nowSeconds: Int): Int {
        if (!isTimingAnOutage) return 0
        val last = lastObservedAtSeconds ?: return accumulatedElapsedSeconds
        val delta = nowSeconds.toLong() - last.toLong()
        val forward = if (delta < 0L) 0L else delta
        val total = accumulatedElapsedSeconds.toLong() + forward
        return if (total > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else total.toInt()
    }

    /** How much of the outage budget remains at [nowSeconds], saturating at zero. */
    fun remainingSeconds(nowSeconds: Int): Int =
        ChainedReconnectPolicy.remainingBlackholeSeconds(elapsedSeconds(nowSeconds))

    /**
     * Records that an attempt has been made, so the next decision backs off.
     *
     * Saturating: [completedAttempts] is bounded input to [ChainedReconnectPolicy.decision],
     * which already treats `Int.MAX_VALUE` as in scope. In a tunnel process a throw is a tunnel
     * abort.
     */
    fun recordAttempt(): ChainedOutageBudget =
        if (completedAttempts == Int.MAX_VALUE) {
            this
        } else {
            ChainedOutageBudget(
                outageStartedAtSeconds = outageStartedAtSeconds,
                completedAttempts = completedAttempts + 1,
                accumulatedElapsedSeconds = accumulatedElapsedSeconds,
                lastObservedAtSeconds = lastObservedAtSeconds,
                clockIsUnusable = clockIsUnusable,
            )
        }

    /**
     * The reconnect decision for a session that ended at [nowSeconds], or a clock-unusable
     * surrender if the caller's time source has been observed moving backwards.
     *
     * A time source that moves both ways cannot bound anything, so it is refused rather than
     * absorbed — absorbing it would make the budget guarantee a function of how often the caller
     * happens to look at its clock.
     *
     * Returns the observation WITH the decision ([ChainedOutageStep]); the caller must persist the
     * returned budget, or the next call measures from a stale reading (see [ChainedOutageStep]).
     */
    fun decisionForSessionEnd(
        nowSeconds: Int,
        cause: ChainedSessionEndCause,
    ): ChainedOutageStep {
        val observed = observe(nowSeconds)
        if (observed.clockIsUnusable) {
            return ChainedOutageStep(
                observed,
                ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.CLOCK_UNUSABLE),
            )
        }
        return ChainedOutageStep(
            observed,
            ChainedReconnectPolicy.decision(
                completedAttempts = observed.completedAttempts,
                elapsedSeconds = observed.elapsedSeconds(nowSeconds),
                cause = cause,
            ),
        )
    }

    /**
     * The budget-only escalation the watchdog asks for, when no session end has been reported.
     *
     * Returns `null` for the step itself only when no outage is being timed. Otherwise the step's
     * [ChainedOutageStep.decision] is `null` to CARRY ON while any of the budget remains, or the
     * surrender to apply once it is gone. This is the path that closes the gap the engine leaves
     * open: BoringTun retransmits an in-flight handshake for up to `REKEY_ATTEMPT_TIME` (90 s)
     * before reporting `connectionExpired`, so on the first loss of reachability no session end
     * exists to classify and the budget would otherwise never expire. The comparison is against
     * the FULL budget rather than the useful-attempt floor: no new attempt is being authorized
     * here, only a decision about whether to keep waiting on the one already running.
     *
     * A clock-unusable reading surrenders rather than returning `null` — continuing to hold
     * traffic on a time source that moves both ways is exactly the unbounded blackhole this
     * budget exists to prevent.
     *
     * The updated budget travels with the step even when nothing is to be done, so the watchdog's
     * observation is always available to persist.
     */
    fun watchdogSurrender(nowSeconds: Int): ChainedOutageStep? {
        if (!isTimingAnOutage) return null
        val observed = observe(nowSeconds)
        if (observed.clockIsUnusable) {
            return ChainedOutageStep(
                observed,
                ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.CLOCK_UNUSABLE),
            )
        }
        return ChainedOutageStep(
            observed,
            if (observed.remainingSeconds(nowSeconds) > 0) {
                null
            } else {
                ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.BUDGET_EXHAUSTED)
            },
        )
    }

    companion object {
        /** A budget that is not yet timing an outage. */
        operator fun invoke(): ChainedOutageBudget = ChainedOutageBudget(
            outageStartedAtSeconds = null,
            completedAttempts = 0,
            accumulatedElapsedSeconds = 0,
            lastObservedAtSeconds = null,
            clockIsUnusable = false,
        )
    }
}
