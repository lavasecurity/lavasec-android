package com.lavasec.app.vpn.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The startup-window race: a terminal outcome recorded before the commit belongs to the startup
 * (which falls back synchronously), while one recorded after the commit belongs to the live
 * runtime (which is torn down or rebuilt asynchronously). The gate keeps both decisions under one
 * lock so an outcome can never schedule a teardown that outlives the DNS-only fallback, and it is
 * bound to the attempted owner so a superseded owner's delayed callback cannot poison the
 * replacement's startup.
 */
class ChainedStartupGateTest {

    private val first = "owner-a"
    private val second = "owner-b"

    @Test
    fun `an outcome recorded before the commit is owned by the startup`() {
        val gate = ChainedStartupGate<String, String>()
        gate.begin(first)

        // Not live yet: the startup must handle the outcome itself.
        assertFalse(gate.recordTermination(first, "engine-failed"))
        assertEquals("engine-failed", gate.commit(first))
        assertFalse(gate.isLive())
    }

    @Test
    fun `an outcome recorded after the commit is handled asynchronously`() {
        val gate = ChainedStartupGate<String, String>()
        gate.begin(first)

        assertNull(gate.commit(first))
        assertTrue(gate.isLive())

        // Live: the terminal callback must route to the asynchronous handler.
        assertTrue(gate.recordTermination(first, "peer-socket-failed"))
        assertTrue(gate.isLive())
    }

    @Test
    fun `retire clears liveness so a rebuild cannot inherit a stale verdict`() {
        val gate = ChainedStartupGate<String, String>()
        gate.begin(first)
        assertNull(gate.commit(first))
        assertTrue(gate.isLive())

        gate.retire()

        assertFalse(gate.isLive())
        // After a retire no owner is the candidate, so the outcome is discarded rather than
        // becoming a startup-owned verdict for an attempt that no longer exists.
        assertFalse(gate.recordTermination(first, "tun-read-failed"))
    }

    @Test
    fun `retire clears a recorded termination so the replacement starts clean`() {
        val gate = ChainedStartupGate<String, String>()
        gate.begin(first)
        // A live owner records a termination just before a reconnect retires it.
        assertNull(gate.commit(first))
        assertTrue(gate.isLive())
        assertTrue(gate.recordTermination(first, "stale-failure"))

        gate.retire()
        gate.begin(second)

        // The replacement's commit must not inherit the retired termination.
        assertNull(gate.commit(second))
        assertTrue(gate.isLive())
    }

    @Test
    fun `a superseded owner's delayed outcome cannot poison the replacement startup`() {
        val gate = ChainedStartupGate<String, String>()
        gate.begin(first)
        assertNull(gate.commit(first)) // The first owner is live.

        // Its failure was flagged but its callback is descheduled; a reconnect retires it and
        // begins a replacement before the callback resumes.
        gate.retire()
        gate.begin(second)

        // The delayed outcome must be discarded, so it cannot be stored for the replacement.
        assertFalse(gate.recordTermination(first, "tun-read-failed"))
        assertNull(gate.commit(second))
        assertTrue(gate.isLive())
    }

    @Test
    fun `an outcome from a non-candidate owner is never recorded`() {
        val gate = ChainedStartupGate<String, String>()
        gate.begin(second)

        // A different owner reports while the second is the candidate: ignored, not startup-owned.
        assertFalse(gate.recordTermination(first, "peer-socket-failed"))
        assertNull(gate.commit(second))
        assertTrue(gate.isLive())
    }
}
