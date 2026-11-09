package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedReconnectPolicy
import com.lavasec.app.foundation.chained.ChainedSessionEndCause
import com.lavasec.app.foundation.chained.ChainedSurrenderReason
import com.lavasec.app.foundation.chained.WireGuardEngineError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android port of the iOS `ChainedOutageSupervisor`: the outage clock is idempotent, the
 * attempt ticket is single-use and ordered, and only the newest recovery token can end the
 * outage. A session end is turned into a decision exactly once.
 */
class ChainedOutageSupervisorTest {

    private val expired: ChainedSessionEndCause =
        requireNotNull(ChainedSessionEndCause.engineVerdict(WireGuardEngineError.ConnectionExpired))

    @Test
    fun `the outage clock is idempotent so a flap cannot restart the budget`() {
        val supervisor = ChainedOutageSupervisor()
        supervisor.beginOutage(100)
        // A second notification must not restart a running clock.
        supervisor.beginOutage(500)

        assertTrue(supervisor.isTimingAnOutage)
        // 16 s after the FIRST begin the budget is gone, proving the clock was not restarted.
        val action = supervisor.action(116, endedBy = expired, receipt = null)
        assertEquals(
            ChainedOutageAction.Surrender(ChainedSurrenderReason.BUDGET_EXHAUSTED),
            action,
        )
    }

    @Test
    fun `a session end is turned into a retry ticket that authorizes exactly one attempt`() {
        val supervisor = ChainedOutageSupervisor()
        supervisor.beginOutage(0)

        val action = supervisor.action(5, endedBy = expired, receipt = null)
        assertTrue(action is ChainedOutageAction.Attempt)
        val attempt = action as ChainedOutageAction.Attempt
        assertEquals(1, attempt.attempt)
        assertEquals(ChainedReconnectPolicy.delaySeconds(1), attempt.afterSeconds)

        val authorization = supervisor.authorizeAttemptStartingNow(5, attempt.ticket)
        assertTrue(authorization is ChainedAttemptAuthorization.Authorized)
        val authorized = authorization as ChainedAttemptAuthorization.Authorized
        assertEquals(1, authorized.attempt)
        // The authorized deadline is an absolute instant inside the budget.
        assertTrue(authorized.deadlineAtSeconds <= 5 + ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS)

        supervisor.recordAttempt(authorized.receipt)
        assertEquals(1, supervisor.completedAttempts)
    }

    @Test
    fun `a ticket authorizes only once and a second use stands down`() {
        val supervisor = ChainedOutageSupervisor()
        supervisor.beginOutage(0)
        val ticket = (supervisor.action(0, endedBy = expired, receipt = null)
            as ChainedOutageAction.Attempt).ticket

        supervisor.authorizeAttemptStartingNow(0, ticket)
        val second = supervisor.authorizeAttemptStartingNow(0, ticket)
        assertEquals(
            ChainedAttemptAuthorization.StandDown(
                ChainedAttemptAuthorization.StandDownReason.ATTEMPT_ALREADY_STARTED,
            ),
            second,
        )
    }

    @Test
    fun `a ticket from a superseded outage stands down`() {
        val supervisor = ChainedOutageSupervisor()
        supervisor.beginOutage(0)
        supervisor.action(0, endedBy = expired, receipt = null)

        // A ticket minted for an outage that has since ended names a stale epoch.
        val stale = ChainedAttemptTicket(generation = 0, serial = 1)
        assertEquals(
            ChainedAttemptAuthorization.StandDown(
                ChainedAttemptAuthorization.StandDownReason.SUPERSEDED_BY_A_LATER_OUTAGE,
            ),
            supervisor.authorizeAttemptStartingNow(0, stale),
        )
    }

    @Test
    fun `a stale recovery token cannot end a later outage`() {
        val supervisor = ChainedOutageSupervisor()
        val first = supervisor.beginOutage(0)
        supervisor.beginOutage(1)

        // The first token's recovery is stale: it must not end the current outage.
        supervisor.endOutage(first)
        assertTrue(supervisor.isTimingAnOutage)

        supervisor.endOutage(supervisor.beginOutage(2))
        assertFalse(supervisor.isTimingAnOutage)
    }

    @Test
    fun `the same session end is turned into a decision only once`() {
        val supervisor = ChainedOutageSupervisor()
        supervisor.beginOutage(0)

        val first = supervisor.action(1, endedBy = expired, receipt = null)
        assertTrue(first is ChainedOutageAction.Attempt)
        // The duplicate delivery must not mint a second ticket.
        val second = supervisor.action(1, endedBy = expired, receipt = null)
        assertEquals(ChainedOutageAction.CarryOn, second)
    }

    @Test
    fun `the watchdog surrenders only when the budget is gone`() {
        val supervisor = ChainedOutageSupervisor()
        supervisor.beginOutage(0)

        assertEquals(ChainedOutageAction.CarryOn, supervisor.action(1, endedBy = null, receipt = null))
        assertEquals(
            ChainedOutageAction.Surrender(ChainedSurrenderReason.BUDGET_EXHAUSTED),
            supervisor.action(ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS, endedBy = null, receipt = null),
        )
    }

    @Test
    fun `a backward clock reading is refused rather than absorbed`() {
        val supervisor = ChainedOutageSupervisor()
        supervisor.beginOutage(100)

        assertEquals(
            ChainedOutageAction.Surrender(ChainedSurrenderReason.CLOCK_UNUSABLE),
            supervisor.action(50, endedBy = null, receipt = null),
        )
    }

    @Test
    fun `a null factory diagnosis surrenders instead of spending a rung`() {
        val supervisor = ChainedOutageSupervisor()
        supervisor.beginOutage(0)
        val cause = ChainedSessionEndCause.buildFailure(null)

        assertEquals(
            ChainedOutageAction.Surrender(ChainedSurrenderReason.ENGINE_UNUSABLE),
            supervisor.action(1, endedBy = cause, receipt = null),
        )
    }
}
