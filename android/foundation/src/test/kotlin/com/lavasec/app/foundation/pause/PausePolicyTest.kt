package com.lavasec.app.foundation.pause

import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PausePolicyTest {
    private val nowMillis = 1_800_000_000_000L
    private val sessionId = 41L
    private val block = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST)

    @Test
    fun `only pinned five ten and fifteen minute windows are offered`() {
        assertEquals(listOf(5, 10, 15), PauseDuration.entries.map { it.minutes })
        assertEquals(listOf(300_000L, 600_000L, 900_000L), PauseDuration.entries.map { it.durationMillis })
    }

    @Test
    fun `wire values accept only pinned durations`() {
        PauseDuration.entries.forEach { duration ->
            assertEquals(duration, PauseDuration.fromMinutes(duration.minutes))
        }

        assertNull(PauseDuration.fromMinutes(0))
        assertNull(PauseDuration.fromMinutes(1))
        assertNull(PauseDuration.fromMinutes(6))
        assertNull(PauseDuration.fromMinutes(Int.MAX_VALUE))
    }

    @Test
    fun `runtime directive rechecks expiry session and sanity cap on every read`() {
        val state = PausePolicy.start(nowMillis, PauseDuration.FIFTEEN_MINUTES, sessionId)
        val directive = RuntimePauseDirective.Paused(state)

        assertTrue(directive.isActive(nowMillis, sessionId))
        assertFalse(directive.isActive(state.deadlineMillis, sessionId))
        assertFalse(directive.isActive(nowMillis, sessionId + 1L))
        assertFalse(
            directive.isActive(
                nowMillis = nowMillis - PauseDuration.FIFTEEN_MINUTES.durationMillis,
                activeProtectionSessionId = sessionId,
            ),
        )
        assertFalse(RuntimePauseDirective.Filtering.isActive(nowMillis, sessionId))
    }

    @Test
    fun `pause stores an absolute deadline that restores across restart`() {
        val state = PausePolicy.start(nowMillis, PauseDuration.TEN_MINUTES, sessionId)
        val persistedDeadline = state.deadlineMillis

        assertEquals(nowMillis + 600_000L, persistedDeadline)
        assertEquals(
            state,
            PausePolicy.restore(
                persistedDeadlineMillis = persistedDeadline,
                persistedSessionId = sessionId,
                activeSessionId = sessionId,
                nowMillis = nowMillis + 1,
            ),
        )
    }

    @Test
    fun `pause from a terminated session never applies to a replacement session`() {
        val state = PausePolicy.start(nowMillis, PauseDuration.TEN_MINUTES, sessionId)

        assertNull(
            PausePolicy.restore(
                persistedDeadlineMillis = state.deadlineMillis,
                persistedSessionId = sessionId,
                activeSessionId = sessionId + 1L,
                nowMillis = nowMillis + 1,
            ),
        )
    }

    @Test
    fun `expiry and resume now clear the pause`() {
        val state = PausePolicy.start(nowMillis, PauseDuration.FIVE_MINUTES, sessionId)

        assertEquals(
            state,
            PausePolicy.restore(state.deadlineMillis, sessionId, sessionId, state.deadlineMillis - 1),
        )
        assertNull(PausePolicy.restore(state.deadlineMillis, sessionId, sessionId, state.deadlineMillis))
        assertNull(PausePolicy.resumeNow())
    }

    @Test
    fun `restore accepts the maximum pause plus clock slack and rejects anything farther ahead`() {
        val sanityWindow = PauseDuration.FIFTEEN_MINUTES.durationMillis + 120_000L

        assertEquals(
            PauseState(nowMillis + sanityWindow, sessionId),
            PausePolicy.restore(
                persistedDeadlineMillis = nowMillis + sanityWindow,
                persistedSessionId = sessionId,
                activeSessionId = sessionId,
                nowMillis = nowMillis,
            ),
        )
        assertNull(
            PausePolicy.restore(
                persistedDeadlineMillis = nowMillis + sanityWindow + 1L,
                persistedSessionId = sessionId,
                activeSessionId = sessionId,
                nowMillis = nowMillis,
            ),
        )
        assertNull(
            PausePolicy.restore(
                persistedDeadlineMillis = Long.MAX_VALUE,
                persistedSessionId = sessionId,
                activeSessionId = sessionId,
                nowMillis = nowMillis,
            ),
        )
    }

    @Test
    fun `restore fails closed when calculating the persistence sanity boundary overflows`() {
        assertNull(
            PausePolicy.restore(
                persistedDeadlineMillis = Long.MAX_VALUE,
                persistedSessionId = sessionId,
                activeSessionId = sessionId,
                nowMillis = Long.MAX_VALUE - 1L,
            ),
        )
    }

    @Test
    fun `paused query logs pausedAllow and caps a would-be block to TTL one`() {
        val state = PausePolicy.start(nowMillis, PauseDuration.FIVE_MINUTES, sessionId)
        val decision = PausePolicy.queryDecision(
            pauseState = state,
            nowMillis = nowMillis + 1,
            activeProtectionSessionId = sessionId,
            wouldBeDecision = block,
        )!!

        assertEquals(FilterDecision(FilterAction.ALLOW, FilterDecisionReason.PAUSED_ALLOW), decision.decision)
        assertEquals(block, decision.wouldBeDecision)
        assertEquals(1L, decision.maximumResponseTtlSeconds)
        assertEquals(1L, decision.effectiveResponseTtlSeconds(upstreamTtlSeconds = 300))
    }

    @Test
    fun `paused ordinary allow retains its real reason and upstream TTL`() {
        val state = PausePolicy.start(nowMillis, PauseDuration.FIFTEEN_MINUTES, sessionId)
        val decision = PausePolicy.queryDecision(
            pauseState = state,
            nowMillis = nowMillis + 1,
            activeProtectionSessionId = sessionId,
            wouldBeDecision = FilterDecision.DEFAULT_ALLOW,
        )!!

        assertEquals(FilterDecision.DEFAULT_ALLOW, decision.decision)
        assertEquals(FilterDecisionReason.DEFAULT_ALLOW, decision.decision.reason)
        assertEquals(FilterDecision.DEFAULT_ALLOW, decision.wouldBeDecision)
        assertNull(decision.maximumResponseTtlSeconds)
        assertEquals(300L, decision.effectiveResponseTtlSeconds(upstreamTtlSeconds = 300))
    }

    @Test
    fun `paused allowlist pass keeps its allowlist reason so it stays a real pass`() {
        val state = PausePolicy.start(nowMillis, PauseDuration.FIVE_MINUTES, sessionId)
        val allowlist = FilterDecision(FilterAction.ALLOW, FilterDecisionReason.LOCAL_ALLOWLIST)
        val decision = PausePolicy.queryDecision(
            pauseState = state,
            nowMillis = nowMillis + 1,
            activeProtectionSessionId = sessionId,
            wouldBeDecision = allowlist,
        )!!

        assertEquals(allowlist, decision.decision)
        assertEquals(FilterDecisionReason.LOCAL_ALLOWLIST, decision.decision.reason)
        assertNull(decision.maximumResponseTtlSeconds)
        assertEquals(300L, decision.effectiveResponseTtlSeconds(upstreamTtlSeconds = 300))
    }

    @Test
    fun `equivalent paused query decisions retain structural value semantics`() {
        val state = PausePolicy.start(nowMillis, PauseDuration.FIVE_MINUTES, sessionId)
        val first = PausePolicy.queryDecision(
            pauseState = state,
            nowMillis = nowMillis + 1,
            activeProtectionSessionId = sessionId,
            wouldBeDecision = block,
        )
        val second = PausePolicy.queryDecision(
            pauseState = state,
            nowMillis = nowMillis + 1,
            activeProtectionSessionId = sessionId,
            wouldBeDecision = block,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `paused query decision rejects inconsistent policy evidence`() {
        val pausedAllow = FilterDecision(FilterAction.ALLOW, FilterDecisionReason.PAUSED_ALLOW)

        // A pause-overridden block must report paused allow with exactly the one-second cap.
        assertThrowsIllegalArgument {
            PausedQueryDecision(
                decision = pausedAllow,
                wouldBeDecision = block,
                maximumResponseTtlSeconds = 2L,
            )
        }
        assertThrowsIllegalArgument {
            PausedQueryDecision(
                decision = FilterDecision.DEFAULT_ALLOW,
                wouldBeDecision = block,
                maximumResponseTtlSeconds = 1L,
            )
        }
        // An ordinary pause pass must keep the would-be decision and carry no pause cap.
        assertThrowsIllegalArgument {
            PausedQueryDecision(
                decision = pausedAllow,
                wouldBeDecision = FilterDecision.DEFAULT_ALLOW,
                maximumResponseTtlSeconds = null,
            )
        }
        assertThrowsIllegalArgument {
            PausedQueryDecision(
                decision = FilterDecision.DEFAULT_ALLOW,
                wouldBeDecision = FilterDecision.DEFAULT_ALLOW,
                maximumResponseTtlSeconds = 1L,
            )
        }
    }

    @Test
    fun `expired pause does not overlay the filter decision`() {
        val state = PausePolicy.start(nowMillis, PauseDuration.FIVE_MINUTES, sessionId)

        assertNull(
            PausePolicy.queryDecision(
                pauseState = state,
                nowMillis = state.deadlineMillis,
                activeProtectionSessionId = sessionId,
                wouldBeDecision = block,
            ),
        )
    }

    @Test
    fun `paused query rejects foreign sessions and corrupt far future deadlines`() {
        val state = PausePolicy.start(nowMillis, PauseDuration.FIVE_MINUTES, sessionId)
        assertNull(
            PausePolicy.queryDecision(
                pauseState = state,
                nowMillis = nowMillis,
                activeProtectionSessionId = sessionId + 1L,
                wouldBeDecision = block,
            ),
        )

        val corrupt = PauseState(
            deadlineMillis = nowMillis + PauseDuration.FIFTEEN_MINUTES.durationMillis + 120_001L,
            protectionSessionId = sessionId,
        )
        assertNull(
            PausePolicy.queryDecision(
                pauseState = corrupt,
                nowMillis = nowMillis,
                activeProtectionSessionId = sessionId,
                wouldBeDecision = block,
            ),
        )
    }

    @Test
    fun `pause start fails closed when its absolute deadline overflows`() {
        try {
            PausePolicy.start(
                nowMillis = Long.MAX_VALUE,
                duration = PauseDuration.FIVE_MINUTES,
                protectionSessionId = sessionId,
            )
            assertFalse("expected ArithmeticException", true)
        } catch (_: ArithmeticException) {
            assertTrue(true)
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            assertFalse("expected IllegalArgumentException", true)
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
