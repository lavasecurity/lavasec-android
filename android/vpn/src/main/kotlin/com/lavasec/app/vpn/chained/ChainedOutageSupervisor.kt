package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedOutageBudget
import com.lavasec.app.foundation.chained.ChainedReconnectDecision
import com.lavasec.app.foundation.chained.ChainedReconnectPolicy
import com.lavasec.app.foundation.chained.ChainedSessionEndCause
import com.lavasec.app.foundation.chained.ChainedSurrenderReason

/**
 * Identifies one outage, so a callback belonging to it cannot act on another.
 *
 * Opaque outside this file: a caller that could mint one could mint the CURRENT one, which is
 * precisely the check being made. Obtained from [ChainedOutageSupervisor.beginOutage] and handed
 * back to [ChainedOutageSupervisor.endOutage]. The Android mirror of iOS
 * `ChainedOutageGeneration`.
 */
internal class ChainedOutageGeneration internal constructor(internal val value: Int) {
    override fun equals(other: Any?): Boolean =
        other is ChainedOutageGeneration && other.value == value

    override fun hashCode(): Int = value

    override fun toString(): String = "ChainedOutageGeneration($value)"
}

/**
 * Proof of which attempt a completion belongs to.
 *
 * Opaque and obtainable only from an authorization, so a caller cannot retire an attempt it did
 * not start. The Android mirror of iOS `ChainedAttemptReceipt`.
 */
internal class ChainedAttemptReceipt internal constructor(
    internal val generation: Int,
    internal val serial: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is ChainedAttemptReceipt && other.generation == generation && other.serial == serial

    override fun hashCode(): Int = 31 * generation + serial

    override fun toString(): String = "ChainedAttemptReceipt($generation/$serial)"
}

/**
 * Authority to start ONE session attempt.
 *
 * Single-use and ordered: consuming a ticket retires every ticket issued before it, so an
 * out-of-order callback from earlier in the same outage cannot start a second session. The
 * Android mirror of iOS `ChainedAttemptTicket`.
 */
internal class ChainedAttemptTicket internal constructor(
    internal val generation: Int,
    internal val serial: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is ChainedAttemptTicket && other.generation == generation && other.serial == serial

    override fun hashCode(): Int = 31 * generation + serial

    override fun toString(): String = "ChainedAttemptTicket($generation/$serial)"
}

/**
 * What the supervisor tells the driver to do next. The Android mirror of iOS
 * `ChainedOutageAction`.
 */
internal sealed interface ChainedOutageAction {

    /** Traffic is flowing. Nothing to do. */
    data object CarryOn : ChainedOutageAction

    /**
     * Start a session attempt after [afterSeconds].
     *
     * Deliberately carries NO deadline: a deadline computed at decision time would already be
     * overrun by the time the caller waits out the delay and arms it. The real deadline comes
     * back from [ChainedOutageSupervisor.authorizeAttemptStartingNow] at the moment the attempt
     * actually begins.
     */
    data class Attempt(
        val afterSeconds: Int,
        val attempt: Int,
        val ticket: ChainedAttemptTicket,
    ) : ChainedOutageAction

    /** Stop. The driver must surrender chaining with [reason]. */
    data class Surrender(val reason: ChainedSurrenderReason) : ChainedOutageAction
}

/**
 * Whether an attempt may start at this instant, and how long it may run.
 *
 * An action SCHEDULES ("in n seconds") and cannot carry a deadline; an authorization is asked for
 * at the moment of starting, so its deadline is valid. The Android mirror of iOS
 * `ChainedAttemptAuthorization`.
 */
internal sealed interface ChainedAttemptAuthorization {

    /**
     * Start now, and arm a watchdog for this ABSOLUTE instant on the caller's clock.
     *
     * [receipt] is what retires this attempt; a duplicated session-end callback completing an
     * older attempt must not retire a newer one.
     */
    data class Authorized(
        val attempt: Int,
        val deadlineAtSeconds: Int,
        val receipt: ChainedAttemptReceipt,
    ) : ChainedAttemptAuthorization

    /** Nothing to attempt. Do nothing. NOT a refusal: nothing is wrong. */
    data class StandDown(val reason: StandDownReason) : ChainedAttemptAuthorization

    /** Do not start. Surrender chaining with [reason]. */
    data class Refused(val reason: ChainedSurrenderReason) : ChainedAttemptAuthorization

    /** Why an attempt was not authorized despite nothing being wrong. */
    enum class StandDownReason {
        /** No outage is being timed. Traffic is flowing. */
        NO_OUTAGE_IN_PROGRESS,

        /** The callback belongs to an outage that has since ended; another has begun. */
        SUPERSEDED_BY_A_LATER_OUTAGE,

        /** This ticket, or a newer one, has already started an attempt. */
        ATTEMPT_ALREADY_STARTED,
    }
}

/**
 * Keeps the outage budget honest across session boundaries — the Android mirror of iOS
 * `ChainedOutageSupervisor`.
 *
 * ## Why this type exists
 *
 * [ChainedReconnectPolicy] decides what to do when a session ENDS, which is not enough to bound
 * an outage: the engine does not end a session promptly (BoringTun retransmits an in-flight
 * handshake for `REKEY_ATTEMPT_TIME`, ~90 s, before reporting `connectionExpired`). So the budget
 * is measured from the moment traffic STOPPED, not from the moment a session ended, and the clock
 * starts before the first handshake.
 *
 * This type owns that clock, layered over the pure [ChainedOutageBudget] (which owns the
 * monotonic accumulation and the reconnect arithmetic). It adds the three identities iOS keeps
 * apart because conflating any two has been a defect:
 *
 * - the OUTAGE clock, idempotent: a clock that restarts is not a budget;
 * - the OUTAGE EPOCH, advancing only when an outage actually starts, so a flap during an outage
 *   does not invalidate in-flight attempt tickets;
 * - the RECOVERY token, advancing on every [beginOutage], so only the most recent notification
 *   can end the outage.
 *
 * Attempt tickets are per-attempt, not per-outage: a token that outlived its attempt could be
 * replayed to start concurrent sessions inside one outage.
 */
internal class ChainedOutageSupervisor {

    /** The monotonic budget accumulation. Reused rather than re-derived. */
    private var budget: ChainedOutageBudget = ChainedOutageBudget()

    /** Identifies the outage itself. Advances only when one STARTS. */
    private var outageEpoch: Int = 0

    /** Identifies one traffic-stopped notification. Advances on EVERY [beginOutage]. */
    private var recoverySerial: Int = 0

    /** The last ticket serial issued, and the last consumed. */
    private var lastIssuedTicket: Int = 0
    private var lastConsumedTicket: Int = 0

    /** Whether an authorized attempt is still running. */
    private var attemptInFlight: Boolean = false

    /** The end already turned into a scheduling decision, keyed by the receipt that named it. */
    private var consumedEndSerial: Int? = null

    /** Whether an outage is currently being timed. */
    val isTimingAnOutage: Boolean get() = budget.isTimingAnOutage

    /** Attempts completed during the current outage. */
    val completedAttempts: Int get() = budget.completedAttempts

    /**
     * Records that traffic has stopped.
     *
     * Idempotent by design: the first call wins. A second call would restart the clock, and a
     * clock that restarts is not a budget. The recovery token advances on every call so only the
     * most recent notification's recovery can end the outage.
     */
    fun beginOutage(nowSeconds: Int): ChainedOutageGeneration {
        recoverySerial += 1
        if (budget.isTimingAnOutage) return ChainedOutageGeneration(recoverySerial)
        budget = budget.beginOutage(nowSeconds)
        attemptInFlight = false
        consumedEndSerial = null
        outageEpoch += 1
        return ChainedOutageGeneration(recoverySerial)
    }

    /**
     * Records that traffic is flowing again, ending the outage this token names.
     *
     * A stale token is ignored rather than reported: the outage it refers to is already over.
     */
    fun endOutage(generation: ChainedOutageGeneration) {
        if (generation.value != recoverySerial) return
        budget = budget.endOutage()
        attemptInFlight = false
    }

    /**
     * Decides what to do at this instant.
     *
     * [endedBy] is the cause AND receipt of the attempt that ended, when this call follows a
     * session ending. `null` means the watchdog fired while a session was still notionally alive
     * — the 90-second case — and the budget is being enforced from outside the engine.
     */
    fun action(
        nowSeconds: Int,
        endedBy: ChainedSessionEndCause?,
        receipt: ChainedAttemptReceipt?,
    ): ChainedOutageAction {
        if (!budget.isTimingAnOutage) return ChainedOutageAction.CarryOn
        budget = budget.observe(nowSeconds)
        if (budget.clockIsUnusable) {
            return ChainedOutageAction.Surrender(ChainedSurrenderReason.CLOCK_UNUSABLE)
        }

        val cause = acceptedEnd(endedBy, receipt)
        if (cause == null) {
            // The watchdog path: no session end to classify, so the reconnect policy has nothing
            // to do. The budget is checked against the FULL budget — no new attempt is being
            // authorized, only a decision about the one already running.
            val step = budget.watchdogSurrender(nowSeconds) ?: return ChainedOutageAction.CarryOn
            budget = step.budget
            val decision = step.decision
            return if (decision is ChainedReconnectDecision.FallBackToDNSOnly) {
                ChainedOutageAction.Surrender(decision.reason)
            } else {
                ChainedOutageAction.CarryOn
            }
        }

        val step = budget.decisionForSessionEnd(nowSeconds, cause)
        budget = step.budget
        return when (val decision = step.decision) {
            null -> ChainedOutageAction.CarryOn

            is ChainedReconnectDecision.Retry -> {
                // The policy's deadline is discarded here on purpose: it is valid only if the
                // attempt starts on time, and the caller asks for the real one when it starts.
                lastIssuedTicket += 1
                ChainedOutageAction.Attempt(
                    afterSeconds = decision.afterSeconds,
                    attempt = decision.attempt,
                    ticket = ChainedAttemptTicket(outageEpoch, lastIssuedTicket),
                )
            }

            is ChainedReconnectDecision.FallBackToDNSOnly ->
                ChainedOutageAction.Surrender(decision.reason)
        }
    }

    /**
     * The deadline to arm for an attempt STARTING NOW, or a stand-down/refusal.
     *
     * An ABSOLUTE instant on the caller's clock, so scheduling latency between here and the
     * caller's arming code cannot extend it. An overflow is refused, not saturated: a saturated
     * deadline is a watchdog instant that never arrives, i.e. an unbounded blackhole wearing the
     * shape of a successful authorization.
     */
    fun authorizeAttemptStartingNow(
        nowSeconds: Int,
        ticket: ChainedAttemptTicket,
    ): ChainedAttemptAuthorization {
        if (!budget.isTimingAnOutage) {
            return ChainedAttemptAuthorization.StandDown(
                ChainedAttemptAuthorization.StandDownReason.NO_OUTAGE_IN_PROGRESS,
            )
        }
        if (ticket.generation != outageEpoch) {
            return ChainedAttemptAuthorization.StandDown(
                ChainedAttemptAuthorization.StandDownReason.SUPERSEDED_BY_A_LATER_OUTAGE,
            )
        }
        // Issuing supersedes: a newer ticket invalidates every older pending one, and a consumed
        // one cannot be replayed. Serial comparison is not enough (two pending tickets both
        // satisfy `>`), so the exact latest serial is required.
        if (ticket.serial != lastIssuedTicket || ticket.serial <= lastConsumedTicket) {
            return ChainedAttemptAuthorization.StandDown(
                ChainedAttemptAuthorization.StandDownReason.ATTEMPT_ALREADY_STARTED,
            )
        }
        if (attemptInFlight) {
            return ChainedAttemptAuthorization.StandDown(
                ChainedAttemptAuthorization.StandDownReason.ATTEMPT_ALREADY_STARTED,
            )
        }
        budget = budget.observe(nowSeconds)
        if (budget.clockIsUnusable) {
            return ChainedAttemptAuthorization.Refused(ChainedSurrenderReason.CLOCK_UNUSABLE)
        }
        val remaining = budget.remainingSeconds(nowSeconds)
        // Re-AUTHORIZED, not just re-measured: scheduling can consume more time than the delay
        // asked for, so a window that ends before the engine's first retransmit is refused.
        if (remaining < ChainedReconnectPolicy.MINIMUM_USEFUL_ATTEMPT_SECONDS) {
            return ChainedAttemptAuthorization.Refused(ChainedSurrenderReason.BUDGET_EXHAUSTED)
        }
        val deadline = nowSeconds.toLong() + remaining.toLong()
        if (deadline > Int.MAX_VALUE.toLong()) {
            return ChainedAttemptAuthorization.Refused(ChainedSurrenderReason.CLOCK_UNUSABLE)
        }
        lastConsumedTicket = ticket.serial
        attemptInFlight = true
        return ChainedAttemptAuthorization.Authorized(
            attempt = nextAttemptNumber(),
            deadlineAtSeconds = deadline.toInt(),
            receipt = ChainedAttemptReceipt(outageEpoch, ticket.serial),
        )
    }

    /**
     * Records that an attempt has been made, so the next decision backs off.
     *
     * Retires THAT attempt, not whatever is running: a duplicated session-end callback completing
     * an older attempt must not clear the in-flight state of a newer one.
     */
    fun recordAttempt(receipt: ChainedAttemptReceipt) {
        if (receipt.serial != lastConsumedTicket || !attemptInFlight) return
        attemptInFlight = false
        budget = budget.recordAttempt()
    }

    /**
     * Classifies a delivered session end exactly once.
     *
     * A receipt-less end is accepted only when nothing has EVER been authorized, which is once in
     * a supervisor's lifetime. An end whose receipt does not name the last authorized attempt is
     * not this outage's news: treated as the watchdog path, nothing is classified from a stale
     * cause and no ticket is minted from it.
     */
    private fun acceptedEnd(
        endedBy: ChainedSessionEndCause?,
        receipt: ChainedAttemptReceipt?,
    ): ChainedSessionEndCause? {
        val end = endedBy ?: return null
        val endKey = receipt?.serial ?: 0
        if (consumedEndSerial == endKey) return null
        val accepted = if (receipt == null) {
            lastConsumedTicket == 0
        } else {
            receipt.serial == lastConsumedTicket
        }
        if (!accepted) return null
        consumedEndSerial = endKey
        return end
    }

    private fun nextAttemptNumber(): Int =
        if (budget.completedAttempts == Int.MAX_VALUE) Int.MAX_VALUE else budget.completedAttempts + 1
}
