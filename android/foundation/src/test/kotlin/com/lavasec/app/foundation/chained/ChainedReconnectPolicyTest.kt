package com.lavasec.app.foundation.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outage budget: how long the tunnel may claim traffic it cannot carry. While a chained
 * session is down the user has no internet at all — the tunnel holds `0.0.0.0/0` and `::/0` and
 * forwards nothing. The failure mode is therefore not a crash, it is a VPN that reports itself
 * connected while the user watches a dead connection. These tests bound that, and check that the
 * bound never gives up on a session that was about to work.
 */
class ChainedReconnectPolicyTest {

    private val allTriagedCauses = listOf(
        WireGuardEngineError.InvalidArgument,
        WireGuardEngineError.DestinationBufferTooSmall,
        WireGuardEngineError.NoCurrentSession,
        WireGuardEngineError.UnderLoad,
        WireGuardEngineError.ProtocolViolation,
        WireGuardEngineError.OversizedDatagram,
        WireGuardEngineError.ConnectionExpired,
        WireGuardEngineError.EngineInternal,
        WireGuardEngineError.PacketTooLarge,
        WireGuardEngineError.SessionCreationFailed,
        WireGuardEngineError.Unrecognized(-1),
    )

    /**
     * Builds a session-end cause the only way the tunnel can: classify the engine error, then
     * take what the classifier hands over. Tests must not construct one any other way — doing so
     * would exercise a route the packet loop does not have, which is exactly the mistake that let
     * the two policies disagree.
     */
    private fun sessionEnd(error: WireGuardEngineError): ChainedSessionEndCause =
        ChainedSessionEndCause.engineVerdict(error)
            ?: throw AssertionError("$error is a per-packet verdict")

    private fun decide(
        attempts: Int = 0,
        elapsed: Int = 0,
        cause: WireGuardEngineError = WireGuardEngineError.ConnectionExpired,
    ): ChainedReconnectDecision =
        ChainedReconnectPolicy.decision(attempts, elapsed, sessionEnd(cause))

    // MARK: - The budget is real and finite

    @Test
    fun `the outage is bounded rather than retrying forever`() {
        var elapsed = 0
        var attempts = 0
        val decisions = mutableListOf<ChainedReconnectDecision>()

        while (attempts < 20) {
            val decision = decide(attempts, elapsed)
            decisions += decision
            val retry = decision as? ChainedReconnectDecision.Retry ?: break
            elapsed += retry.afterSeconds
            attempts += 1
        }

        assertTrue("the schedule must terminate in a fallback", decisions.last().surrendersChaining)
        assertTrue(
            "held traffic past the budget",
            elapsed <= ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS,
        )
    }

    @Test
    fun `the budget is checked against the delay about to be spent`() {
        // Waiting past the deadline to discover we are past it is the same mistake in slower
        // motion. With 14s spent and a 2s delay pending, 16 > 15, so surrender now.
        assertEquals(
            ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.BUDGET_EXHAUSTED),
            decide(attempts = 1, elapsed = 14),
        )
    }

    @Test
    fun `a fallback is reached promptly`() {
        assertTrue(ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS <= 20)
    }

    // MARK: - Retrying quickly, then backing off

    @Test
    fun `the first retry is immediate enough for a network transition`() {
        assertEquals(
            ChainedReconnectDecision.Retry(afterSeconds = 1, attempt = 1, attemptDeadlineSeconds = 14),
            decide(),
        )
    }

    @Test
    fun `delays grow so an unreachable peer is not hammered`() {
        val delays = (1..5).map { ChainedReconnectPolicy.delaySeconds(forAttempt = it) }
        assertEquals(listOf(1, 2, 4, 8, 16), delays)
        assertEquals(delays, delays.sorted())
    }

    @Test
    fun `delays are defined for nonsensical attempt numbers`() {
        for (attempt in listOf(Int.MIN_VALUE, -1, 0)) {
            assertTrue(ChainedReconnectPolicy.delaySeconds(forAttempt = attempt) >= 1)
        }
        assertTrue(ChainedReconnectPolicy.delaySeconds(forAttempt = Int.MAX_VALUE) >= 1)
    }

    @Test
    fun `delays are capped so a large attempt cannot overflow`() {
        assertEquals(256, ChainedReconnectPolicy.delaySeconds(forAttempt = 9))
        assertEquals(256, ChainedReconnectPolicy.delaySeconds(forAttempt = 1_000))
        assertEquals(256, ChainedReconnectPolicy.delaySeconds(forAttempt = Int.MAX_VALUE))
    }

    @Test
    fun `negative elapsed is treated as no time spent`() {
        assertEquals(
            ChainedReconnectDecision.Retry(afterSeconds = 1, attempt = 1, attemptDeadlineSeconds = 14),
            decide(attempts = 0, elapsed = -100),
        )
    }

    // MARK: - Causes that no retry can fix

    @Test
    fun `an unusable engine surrenders without spending the budget`() {
        for (cause in listOf(WireGuardEngineError.Unrecognized(-99), WireGuardEngineError.SessionCreationFailed)) {
            assertEquals(
                "$cause must not consume a retry",
                ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.ENGINE_UNUSABLE),
                decide(cause = cause),
            )
        }
    }

    @Test
    fun `an expired session is worth retrying`() {
        assertEquals(
            ChainedReconnectDecision.Retry(afterSeconds = 1, attempt = 1, attemptDeadlineSeconds = 14),
            decide(cause = WireGuardEngineError.ConnectionExpired),
        )
    }

    @Test
    fun `a poisoned lock earns another attempt but a deterministic fault does not`() {
        assertTrue(decide(cause = WireGuardEngineError.EngineInternal) is ChainedReconnectDecision.Retry)
        for (cause in listOf(WireGuardEngineError.Unrecognized(-99), WireGuardEngineError.SessionCreationFailed)) {
            assertEquals(
                ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.ENGINE_UNUSABLE),
                decide(cause = cause),
            )
        }
    }

    @Test
    fun `caller contract failures surrender rather than rebuild`() {
        for (cause in listOf(
            WireGuardEngineError.InvalidArgument,
            WireGuardEngineError.DestinationBufferTooSmall,
            WireGuardEngineError.PacketTooLarge,
        )) {
            assertEquals(
                "$cause is our bug — rebuilding retains it",
                ChainedReconnectDecision.FallBackToDNSOnly(
                    ChainedSurrenderReason.CALLER_CONTRACT_VIOLATION,
                ),
                decide(cause = cause),
            )
        }
        assertEquals(
            "fall-back-dns-only-callerContractViolation",
            decide(cause = WireGuardEngineError.InvalidArgument).logValue,
        )
    }

    @Test
    fun `every cause resolves to a decision`() {
        // A new engine error must not silently acquire retry semantics by falling into a default
        // branch. Totality is two questions: every error must be classified, and every error
        // classified as a session end must resolve to a decision.
        for (cause in allTriagedCauses) {
            val ending = ChainedSessionEndCause.engineVerdict(cause)
            if (ending == null) {
                assertFalse("$cause must be a per-packet verdict", ChainedSessionEndCause.isSessionEnding(cause))
                continue
            }
            val decision = ChainedReconnectPolicy.decision(0, 0, ending)
            assertTrue(
                decision is ChainedReconnectDecision.Retry ||
                    decision is ChainedReconnectDecision.FallBackToDNSOnly,
            )
        }
        assertEquals(11, allTriagedCauses.size)
        assertEquals(11, allTriagedCauses.map { it.logValue }.toSet().size)
    }

    // MARK: - Logging

    @Test
    fun `decisions name themselves for a field log`() {
        assertEquals("retry-1-in-1s-deadline-14s", decide().logValue)
        assertEquals(
            "fall-back-dns-only-engineUnusable",
            decide(cause = WireGuardEngineError.SessionCreationFailed).logValue,
        )
        assertEquals(
            "fall-back-dns-only-budgetExhausted",
            ChainedReconnectPolicy.decision(9, 999, sessionEnd(WireGuardEngineError.ConnectionExpired)).logValue,
        )
        assertNotEquals(
            decide(cause = WireGuardEngineError.EngineInternal).logValue,
            ChainedReconnectPolicy.decision(9, 999, sessionEnd(WireGuardEngineError.ConnectionExpired)).logValue,
        )
    }

    // MARK: - The budget bounds the outage, not just the pauses between attempts

    @Test
    fun `a retry never authorizes more than the remaining budget`() {
        // The policy is only consulted when a session ENDS, but BoringTun retransmits a handshake
        // for REKEY_ATTEMPT_TIME (90 s) before it yields connectionExpired. Budgeting only the
        // delays bounded the pauses BETWEEN attempts while leaving the attempts themselves
        // unbounded. Every retry now carries the deadline that closes it.
        for (elapsed in 0..ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS) {
            for (attempts in 0..10) {
                val decision = ChainedReconnectPolicy.decision(
                    attempts,
                    elapsed,
                    sessionEnd(WireGuardEngineError.ConnectionExpired),
                )
                val retry = decision as? ChainedReconnectDecision.Retry ?: continue
                assertTrue(
                    "elapsed $elapsed + delay ${retry.afterSeconds} + deadline " +
                        "${retry.attemptDeadlineSeconds} overruns the budget",
                    elapsed + retry.afterSeconds + retry.attemptDeadlineSeconds <=
                        ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS,
                )
                assertTrue(retry.attemptDeadlineSeconds >= ChainedReconnectPolicy.MINIMUM_USEFUL_ATTEMPT_SECONDS)
            }
        }
    }

    @Test
    fun `the budget is exhausted rather than retried with no time left`() {
        assertEquals(
            ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.BUDGET_EXHAUSTED),
            ChainedReconnectPolicy.decision(
                0,
                ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS - 1,
                sessionEnd(WireGuardEngineError.ConnectionExpired),
            ),
        )
    }

    @Test
    fun `the deadline shrinks as the budget is spent`() {
        // Successive attempts must get strictly less room, or it is not a budget. Sample points
        // are WALKED rather than listed, so a change to the attempt floor cannot leave a stale
        // list behind.
        var previous = Int.MAX_VALUE
        var samples = 0
        for (elapsed in 0..ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS) {
            val retry = ChainedReconnectPolicy.decision(
                0,
                elapsed,
                sessionEnd(WireGuardEngineError.ConnectionExpired),
            ) as? ChainedReconnectDecision.Retry ?: break
            assertTrue("deadline did not shrink at elapsed $elapsed", retry.attemptDeadlineSeconds < previous)
            previous = retry.attemptDeadlineSeconds
            samples += 1
        }
        assertTrue("fewer than three elapsed values authorize a retry", samples >= 3)
    }

    @Test
    fun `hostile integers surrender rather than throw`() {
        val hostile = listOf(
            Int.MAX_VALUE to 0,
            Int.MAX_VALUE to Int.MAX_VALUE,
            Int.MAX_VALUE - 1 to 0,
            0 to Int.MAX_VALUE,
            0 to Int.MAX_VALUE - 1,
            Int.MIN_VALUE to Int.MIN_VALUE,
            Int.MIN_VALUE to 0,
            0 to Int.MIN_VALUE,
        )
        for ((attempts, elapsed) in hostile) {
            val decision = ChainedReconnectPolicy.decision(
                attempts,
                elapsed,
                sessionEnd(WireGuardEngineError.ConnectionExpired),
            )
            val retry = decision as? ChainedReconnectDecision.Retry ?: continue
            assertTrue(
                "attempts=$attempts elapsed=$elapsed retried past the budget",
                maxOf(elapsed, 0) + retry.afterSeconds + retry.attemptDeadlineSeconds <=
                    ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS,
            )
        }
    }

    @Test
    fun `the budget is enforceable from the start of an outage`() {
        // decision is only consulted when a session ENDS, and the engine does not end one
        // promptly. remainingBlackholeSeconds is what a watchdog armed at outage start uses, and
        // it must be answerable before any decision exists.
        assertEquals(
            ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS,
            ChainedReconnectPolicy.remainingBlackholeSeconds(0),
        )
        for (elapsed in 0..ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS) {
            assertEquals(
                ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS - elapsed,
                ChainedReconnectPolicy.remainingBlackholeSeconds(elapsed),
            )
        }
        for (elapsed in listOf(15, 100, 10_000, Int.MAX_VALUE)) {
            assertEquals(0, ChainedReconnectPolicy.remainingBlackholeSeconds(elapsed))
        }
        for (elapsed in listOf(-1, -10_000, Int.MIN_VALUE)) {
            assertEquals(
                ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS,
                ChainedReconnectPolicy.remainingBlackholeSeconds(elapsed),
            )
        }
    }

    // MARK: - Agreement between the classifier and the reconnect policy

    @Test
    fun `the two policies agree on every engine error`() {
        // The invariant rather than a list of cases: for every engine error, if the classifier
        // says another attempt is not warranted, this policy must not authorize one.
        var comparedEndings = 0
        var comparedDrops = 0
        for (cause in allTriagedCauses) {
            val ending = ChainedSessionEndCause.engineVerdict(cause)
            if (ending == null) {
                assertFalse(cause.logValue, ChainedSessionEndCause.isSessionEnding(cause))
                assertFalse(cause.logValue, ChainedSessionEndCause.warrantsAnotherAttempt(cause))
                comparedDrops += 1
                continue
            }
            assertTrue(cause.logValue, ChainedSessionEndCause.isSessionEnding(cause))
            val retries = ChainedReconnectPolicy.decision(0, 0, ending) is ChainedReconnectDecision.Retry
            assertEquals(
                "$cause: classifier warrantsAnotherAttempt=${ChainedSessionEndCause.warrantsAnotherAttempt(cause)} " +
                    "but the reconnect policy ${if (retries) "retries" else "surrenders"}",
                ChainedSessionEndCause.warrantsAnotherAttempt(cause),
                retries,
            )
            comparedEndings += 1
        }
        assertEquals(7, comparedEndings)
        assertEquals(4, comparedDrops)
    }

    @Test
    fun `per packet verdicts cannot reach the reconnect policy`() {
        for (cause in listOf(
            WireGuardEngineError.ProtocolViolation,
            WireGuardEngineError.UnderLoad,
            WireGuardEngineError.NoCurrentSession,
            WireGuardEngineError.OversizedDatagram,
        )) {
            assertNull("$cause is a per-packet drop", ChainedSessionEndCause.engineVerdict(cause))
        }
        // The honest pairings still work — the check must not be a blanket refusal.
        assertTrue(ChainedSessionEndCause.engineVerdict(WireGuardEngineError.ConnectionExpired) != null)
        assertTrue(ChainedSessionEndCause.engineVerdict(WireGuardEngineError.InvalidArgument) != null)
        assertEquals(
            WireGuardEngineError.SessionCreationFailed,
            ChainedSessionEndCause.sessionCreationFailed.error,
        )
    }

    // MARK: - Builds that never produced a session

    private class TransientBuildFailure : Exception(), ChainedSessionBuildFailure {
        override val warrantsAnotherAttempt: Boolean = true
    }

    private class PermanentBuildFailure : Exception(), ChainedSessionBuildFailure {
        override val warrantsAnotherAttempt: Boolean = false
    }

    private class UnclassifiedFailure : Exception()

    @Test
    fun `a build failure's origin decides it rather than its engine error`() {
        val transient = ChainedSessionEndCause.buildFailure(TransientBuildFailure())
        val permanent = ChainedSessionEndCause.sessionCreationFailed

        // The two build outcomes are indistinguishable by engine error, or this test is asserting
        // something easier than the defect.
        assertEquals(transient.error, permanent.error)
        assertNotEquals(transient.origin, permanent.origin)

        assertTrue(
            "a handoff with no eligible interface surrendered chained mode",
            ChainedReconnectPolicy.decision(0, 0, transient) is ChainedReconnectDecision.Retry,
        )
        assertEquals(
            ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.ENGINE_UNUSABLE),
            ChainedReconnectPolicy.decision(0, 0, permanent),
        )
    }

    @Test
    fun `an error the factory did not diagnose is treated as permanent`() {
        assertEquals(
            ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.ENGINE_UNUSABLE),
            ChainedReconnectPolicy.decision(0, 0, ChainedSessionEndCause.buildFailure(UnclassifiedFailure())),
        )
        assertEquals(
            ChainedSessionEndCause.Origin.PERMANENT_BUILD_FAILURE,
            ChainedSessionEndCause.buildFailure(UnclassifiedFailure()).origin,
        )
        assertEquals(
            ChainedSessionEndCause.Origin.PERMANENT_BUILD_FAILURE,
            ChainedSessionEndCause.buildFailure(PermanentBuildFailure()).origin,
        )
    }

    @Test
    fun `a transient build failure spends a rung against the same budget`() {
        // A rung, not a refund: the same backoff, the same deadline shrinkage, and the same
        // surrender when what is left will not fit a useful attempt.
        val transient = ChainedSessionEndCause.buildFailure(TransientBuildFailure())
        assertEquals(
            ChainedReconnectDecision.Retry(afterSeconds = 1, attempt = 1, attemptDeadlineSeconds = 14),
            ChainedReconnectPolicy.decision(0, 0, transient),
        )
        assertEquals(
            ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.BUDGET_EXHAUSTED),
            ChainedReconnectPolicy.decision(1, 14, transient),
        )
    }

    // MARK: - Surrender vocabulary

    @Test
    fun `only budget exhausted is resolvable by a network change`() {
        assertTrue(ChainedSurrenderReason.BUDGET_EXHAUSTED.isResolvableByNetworkChange)
        assertFalse(ChainedSurrenderReason.ENGINE_UNUSABLE.isResolvableByNetworkChange)
        assertFalse(ChainedSurrenderReason.CALLER_CONTRACT_VIOLATION.isResolvableByNetworkChange)
        assertFalse(ChainedSurrenderReason.CLOCK_UNUSABLE.isResolvableByNetworkChange)
    }

    @Test
    fun `only a recoverable surrender marker permits a DNS-only start`() {
        assertTrue(
            ChainedSurrenderReason.markerReasonIsRecoverableSurrender(
                ChainedSurrenderReason.BUDGET_EXHAUSTED.markerReason(suppressionPersisted = true),
            ),
        )
        for (surrender in listOf(
            ChainedSurrenderReason.ENGINE_UNUSABLE,
            ChainedSurrenderReason.CALLER_CONTRACT_VIOLATION,
            ChainedSurrenderReason.CLOCK_UNUSABLE,
        )) {
            assertFalse(
                "$surrender is not lifted by a network change",
                ChainedSurrenderReason.markerReasonIsRecoverableSurrender(surrender.markerReason),
            )
        }
        for (foreign in listOf("upstreamUnavailable", "deviceStateUnavailable", "", "chained-surrendered:")) {
            assertFalse(
                "$foreign is not a recognised surrender marker",
                ChainedSurrenderReason.markerReasonIsRecoverableSurrender(foreign),
            )
        }
    }

    // MARK: - The pure outage-budget value type

    @Test
    fun `the ladder backs off and escalates to a surrender`() {
        var budget = ChainedOutageBudget().beginOutage(nowSeconds = 0)
        var now = 0
        val delays = mutableListOf<Int>()
        var last: ChainedReconnectDecision? = null
        repeat(10) {
            val step = budget.decisionForSessionEnd(
                now,
                sessionEnd(WireGuardEngineError.ConnectionExpired),
            )
            last = step.decision
            budget = step.budget
            val retry = step.decision as? ChainedReconnectDecision.Retry ?: return@repeat
            delays += retry.afterSeconds
            budget = budget.recordAttempt()
            now += retry.afterSeconds
        }
        assertEquals(listOf(1, 2, 4), delays)
        assertEquals(
            ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.BUDGET_EXHAUSTED),
            last,
        )
        assertTrue(now <= ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS)
    }

    @Test
    fun `a healthy session resets the ladder`() {
        var budget = ChainedOutageBudget().beginOutage(nowSeconds = 0)
        budget = budget.recordAttempt().recordAttempt()
        assertEquals(2, budget.completedAttempts)

        budget = budget.endOutage()
        assertFalse(budget.isTimingAnOutage)
        assertEquals(0, budget.completedAttempts)
        assertFalse(budget.clockIsUnusable)

        budget = budget.beginOutage(nowSeconds = 100)
        val step = budget.decisionForSessionEnd(
            nowSeconds = 100,
            cause = sessionEnd(WireGuardEngineError.ConnectionExpired),
        )
        budget = step.budget
        val decision = step.decision as ChainedReconnectDecision.Retry
        assertEquals(1, decision.attempt)
        assertEquals(1, decision.afterSeconds)
        assertEquals(14, decision.attemptDeadlineSeconds)
    }

    @Test
    fun `beginning an outage twice does not restart the clock`() {
        val first = ChainedOutageBudget().beginOutage(nowSeconds = 0)
        val again = first.beginOutage(nowSeconds = 50)
        assertEquals(first, again)
        assertEquals(first.elapsedSeconds(50), again.elapsedSeconds(50))
    }

    @Test
    fun `a backward clock is reported rather than absorbed`() {
        var budget = ChainedOutageBudget().beginOutage(nowSeconds = 100)
        budget = budget.observe(nowSeconds = 112)
        assertFalse(budget.clockIsUnusable)
        assertEquals(12, budget.elapsedSeconds(112))

        // A backward jump contributes nothing and takes nothing away; the accumulated time
        // stays accumulated.
        budget = budget.observe(nowSeconds = 90)
        assertTrue(budget.clockIsUnusable)
        assertEquals(12, budget.elapsedSeconds(90))

        // And the decision refuses rather than bounding a time source that moves both ways.
        assertEquals(
            ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.CLOCK_UNUSABLE),
            budget.decisionForSessionEnd(
                90,
                sessionEnd(WireGuardEngineError.ConnectionExpired),
            ).decision,
        )
    }

    @Test
    fun `a returned budget carries its observation into the next call`() {
        // decisionForSessionEnd observes the clock but is pure, so the observation reaches the
        // caller ONLY through the returned step. If it is dropped, the next call measures from the
        // stale high-water mark and MISSES the backward jump at 101: the clock is not flagged
        // unusable and elapsed restarts from the old reading instead of holding.
        var budget = ChainedOutageBudget().beginOutage(nowSeconds = 100)
        budget = budget.decisionForSessionEnd(
            nowSeconds = 104,
            cause = sessionEnd(WireGuardEngineError.ConnectionExpired),
        ).budget

        val watchdog = requireNotNull(budget.watchdogSurrender(101))
        assertEquals(
            ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.CLOCK_UNUSABLE),
            watchdog.decision,
        )
        assertTrue(watchdog.budget.clockIsUnusable)
        assertEquals(
            "elapsed must hold at the 104 high-water, not restart from the stale 100",
            4,
            watchdog.budget.elapsedSeconds(101),
        )
    }

    @Test
    fun `remaining seconds track elapsed time and saturate`() {
        val budget = ChainedOutageBudget().beginOutage(nowSeconds = 0)
        assertEquals(15, budget.remainingSeconds(0))
        assertEquals(9, budget.remainingSeconds(6))
        assertEquals(0, budget.remainingSeconds(20))
        assertEquals("no outage is a full budget", 15, ChainedOutageBudget().remainingSeconds(0))
    }

    @Test
    fun `the watchdog carries on inside the budget and surrenders at the threshold`() {
        var budget = ChainedOutageBudget().beginOutage(nowSeconds = 0)

        val waiting = requireNotNull(budget.watchdogSurrender(0))
        assertNull("time remains, so keep waiting on the in-flight attempt", waiting.decision)
        budget = waiting.budget

        val stillWaiting = requireNotNull(budget.watchdogSurrender(14))
        assertNull(stillWaiting.decision)
        budget = stillWaiting.budget

        val surrender = requireNotNull(budget.watchdogSurrender(15))
        assertEquals(
            ChainedReconnectDecision.FallBackToDNSOnly(ChainedSurrenderReason.BUDGET_EXHAUSTED),
            surrender.decision,
        )
        assertNull("no outage is nothing to enforce", ChainedOutageBudget().watchdogSurrender(100))
    }

    @Test
    fun `recording an attempt advances the ladder and returns a usable budget`() {
        var budget = ChainedOutageBudget().beginOutage(0)
        for (expected in 1..3) {
            budget = budget.recordAttempt()
            assertEquals(expected, budget.completedAttempts)
        }
        assertTrue(budget.isTimingAnOutage)
        assertTrue(
            budget.decisionForSessionEnd(0, sessionEnd(WireGuardEngineError.ConnectionExpired))
                .decision is ChainedReconnectDecision.Retry,
        )
    }
}
