package com.lavasec.app.foundation.chained

import com.lavasec.app.foundation.chained.ChainedUpstreamRotationFreshness.StoredRotation
import com.lavasec.app.foundation.chained.ChainedUpstreamRotationFreshness.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainedUpstreamRotationFreshnessTest {
    @Test
    fun `DNS only or unknown running identity never reports a saved rotation as stale`() {
        for (stored in listOf(StoredRotation.Present(7), StoredRotation.Absent, StoredRotation.Unreadable)) {
            val verdict = ChainedUpstreamRotationFreshness.verdict(0, stored)
            assertEquals(Verdict.NoChainedSession, verdict)
            assertFalse(verdict.deservesSurfacing)
        }
    }

    @Test
    fun `same rotation is current while either direction of a replacement needs restart`() {
        assertEquals(Verdict.Current, ChainedUpstreamRotationFreshness.verdict(7, StoredRotation.Present(7)))
        for ((running, saved) in listOf(7L to 8L, 8L to 7L, 1L to Long.MAX_VALUE, Long.MAX_VALUE to 1L)) {
            val verdict = ChainedUpstreamRotationFreshness.verdict(running, StoredRotation.Present(saved))
            assertEquals(Verdict.AwaitingRestart(running, saved), verdict)
            assertTrue(verdict.deservesSurfacing)
        }
    }

    @Test
    fun `confirmed removal has a stop or replace remedy rather than restarting into nothing`() {
        val verdict = ChainedUpstreamRotationFreshness.verdict(7, StoredRotation.Absent)
        assertEquals(Verdict.RunningRemovedRotation(7), verdict)
        assertTrue(verdict.deservesSurfacing)
    }

    @Test
    fun `unreadable authority establishes neither removal nor a replacement`() {
        val verdict = ChainedUpstreamRotationFreshness.verdict(7, StoredRotation.Unreadable)
        assertEquals(Verdict.Unknown, verdict)
        assertFalse(verdict.deservesSurfacing)
    }

    @Test
    fun `failed reload keeps the notice and actual adoption or stop clears it without another store write`() {
        val saved = StoredRotation.Present(8)
        assertEquals(Verdict.AwaitingRestart(7, 8), ChainedUpstreamRotationFreshness.verdict(7, saved))
        // Queuing a reload does not change the installed identity. A new read remains stale.
        assertEquals(Verdict.AwaitingRestart(7, 8), ChainedUpstreamRotationFreshness.verdict(7, saved))
        assertEquals(Verdict.Current, ChainedUpstreamRotationFreshness.verdict(8, saved))
        assertEquals(Verdict.NoChainedSession, ChainedUpstreamRotationFreshness.verdict(0, saved))
    }
}
