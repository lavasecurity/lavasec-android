package com.lavasec.app.foundation.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the bounded per-address backoff and smoke-probe cadence (plan slice B06). */
class ResolverBackoffPolicyTest {
    @Test
    fun `a retry-worthy failure benches for exactly one bounded interval and success clears it`() {
        var state = ResolverBackoffState()
        state = ResolverBackoffPolicy.record(
            state,
            listOf(ResolverBackoffAttempt("1.1.1.1", ResolverBackoffOutcome.RETRY_WORTHY)),
            nowMillis = 1_000L,
        )
        assertEquals(1_000L + ResolverBackoffPolicy.BACKOFF_INTERVAL_MILLIS, ResolverBackoffPolicy.backoffExpiration(state, "1.1.1.1"))
        assertTrue(ResolverBackoffPolicy.isBackedOff(state, "1.1.1.1", 1_000L))

        // A second failure at a later time re-benches for the same fixed window, never escalating.
        state = ResolverBackoffPolicy.record(
            state,
            listOf(ResolverBackoffAttempt("1.1.1.1", ResolverBackoffOutcome.RETRY_WORTHY)),
            nowMillis = 20_000L,
        )
        assertEquals(20_000L + ResolverBackoffPolicy.BACKOFF_INTERVAL_MILLIS, ResolverBackoffPolicy.backoffExpiration(state, "1.1.1.1"))

        state = ResolverBackoffPolicy.record(
            state,
            listOf(ResolverBackoffAttempt("1.1.1.1", ResolverBackoffOutcome.SUCCESS)),
            nowMillis = 21_000L,
        )
        assertNull(ResolverBackoffPolicy.backoffExpiration(state, "1.1.1.1"))
        assertFalse(ResolverBackoffPolicy.isBackedOff(state, "1.1.1.1", 21_000L))
    }

    @Test
    fun `local refusals never bench the upstream`() {
        var state = ResolverBackoffState()
        state = ResolverBackoffPolicy.record(
            state,
            listOf(ResolverBackoffAttempt("1.1.1.1", ResolverBackoffOutcome.NEUTRAL)),
            nowMillis = 1_000L,
        )
        assertFalse(ResolverBackoffPolicy.isBackedOff(state, "1.1.1.1", 1_000L))
        assertNull(ResolverBackoffPolicy.backoffExpiration(state, "1.1.1.1"))
    }

    @Test
    fun `a TLS failure is retry-worthy while a policy backoff is not`() {
        assertEquals(ResolverBackoffOutcome.RETRY_WORTHY, ResolverFailureReason.TLS_FAILED.backoffOutcome())
        assertEquals(ResolverBackoffOutcome.RETRY_WORTHY, ResolverFailureReason.TIMEOUT.backoffOutcome())
        assertEquals(ResolverBackoffOutcome.NEUTRAL, ResolverFailureReason.BACKED_OFF.backoffOutcome())
        assertEquals(ResolverBackoffOutcome.NEUTRAL, ResolverFailureReason.CANCELLED.backoffOutcome())
        assertEquals(ResolverBackoffOutcome.NEUTRAL, ResolverFailureReason.STALE_EPOCH.backoffOutcome())
    }

    @Test
    fun `suppression never empties a non-empty route and preserves the caller order`() {
        var state = ResolverBackoffState()
        state = ResolverBackoffPolicy.record(
            state,
            listOf(
                ResolverBackoffAttempt("a", ResolverBackoffOutcome.RETRY_WORTHY),
                ResolverBackoffAttempt("b", ResolverBackoffOutcome.RETRY_WORTHY),
            ),
            nowMillis = 1_000L,
        )
        assertEquals(listOf("a", "b"), ResolverBackoffPolicy.availableAddresses(state, listOf("a", "b"), 1_001L))

        // One healthy alternative is preferred and the caller's relative order is kept.
        val partial = ResolverBackoffPolicy.record(
            ResolverBackoffState(),
            listOf(ResolverBackoffAttempt("a", ResolverBackoffOutcome.RETRY_WORTHY)),
            nowMillis = 1_000L,
        )
        assertEquals(listOf("b", "c"), ResolverBackoffPolicy.availableAddresses(partial, listOf("a", "b", "c"), 1_001L))
    }

    @Test
    fun `encrypted recovery is shared once per interval`() {
        var state = ResolverBackoffState()
        state = ResolverBackoffPolicy.record(
            state,
            listOf(
                ResolverBackoffAttempt("a", ResolverBackoffOutcome.RETRY_WORTHY),
                ResolverBackoffAttempt("b", ResolverBackoffOutcome.RETRY_WORTHY),
            ),
            nowMillis = 1_000L,
        )
        val first = ResolverBackoffPolicy.claimEncryptedRecovery(state, listOf("a", "b"), nowMillis = 1_000L)
        assertEquals("a", first.address)
        state = first.state

        val immediate = ResolverBackoffPolicy.claimEncryptedRecovery(state, listOf("a", "b"), nowMillis = 1_500L)
        assertNull(immediate.address)

        val later = ResolverBackoffPolicy.claimEncryptedRecovery(state, listOf("a", "b"), nowMillis = 2_000L)
        assertEquals("a", later.address)
    }

    @Test
    fun `a backward clock rebases the recovery cooldown instead of extending it`() {
        // "a" is suppressed far into the future, so the recovery claim is admissible; the recorded
        // last-claim stamp is AHEAD of `now`, which must rebase rather than suppress.
        val claimed = ResolverBackoffPolicy.claimEncryptedRecovery(
            ResolverBackoffState(
                backoffUntilMillis = mapOf("a" to 100_000L),
                lastEncryptedRecoveryAtMillis = 10_000L,
            ),
            listOf("a"),
            nowMillis = 1_000L,
        )
        assertEquals("a", claimed.address)
        assertEquals(1_000L, claimed.state.lastEncryptedRecoveryAtMillis)
    }

    @Test
    fun `smoke cadence stays base then doubles and caps`() {
        assertEquals(ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS, ResolverSmokeProbeCadence.intervalMillis(0))
        assertEquals(ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS, ResolverSmokeProbeCadence.intervalMillis(4))
        assertEquals(600_000L, ResolverSmokeProbeCadence.intervalMillis(5))
        assertEquals(ResolverSmokeProbeCadence.MAX_INTERVAL_MILLIS, ResolverSmokeProbeCadence.intervalMillis(6))
        assertEquals(ResolverSmokeProbeCadence.MAX_INTERVAL_MILLIS, ResolverSmokeProbeCadence.intervalMillis(100))
    }

    @Test
    fun `the smoke failure streak saturates`() {
        assertEquals(1, ResolverSmokeProbeCadence.nextFailureCount(0))
        assertEquals(ResolverSmokeProbeCadence.MAX_TRACKED_FAILURES, ResolverSmokeProbeCadence.nextFailureCount(998))
        assertEquals(ResolverSmokeProbeCadence.MAX_TRACKED_FAILURES, ResolverSmokeProbeCadence.nextFailureCount(999))
    }
}
