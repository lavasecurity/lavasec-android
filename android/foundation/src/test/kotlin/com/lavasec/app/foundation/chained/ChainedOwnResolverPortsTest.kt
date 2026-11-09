package com.lavasec.app.foundation.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bounded, grace-windowed registry behind the classifier's own-resolver carve-out. */
class ChainedOwnResolverPortsTest {

    private var now = 1_000L

    @Test
    fun `a claimed port is ours and not recently-claimed twice`() {
        val ports = ChainedOwnResolverPorts(nowMillis = { now })

        val port = ports.claimForUdp()

        requireNotNull(port)
        assertEquals(1, ports.claimedCount())
        assertTrue(ports.claims(port, 17))
        assertFalse(ports.claims(port, 6))
        assertFalse(ports.wasRecentlyClaimed(port, 17))
    }

    @Test
    fun `a released port stays recognized for the grace window then expires`() {
        val ports = ChainedOwnResolverPorts(
            nowMillis = { now },
            recentGraceMillis = 3_000L,
        )
        val port = ports.claimForUdp()!!
        ports.release(port)

        assertFalse(ports.claims(port, 17))
        assertTrue(ports.wasRecentlyClaimed(port, 17))

        now += 3_001L
        assertFalse(ports.wasRecentlyClaimed(port, 17))
    }

    @Test
    fun `a released port is not immediately reallocated within its grace window`() {
        val ports = ChainedOwnResolverPorts(
            nowMillis = { now },
            firstPort = 50000,
            lastPort = 50000,
        )
        val port = ports.claimForUdp()!!
        ports.release(port)

        assertNull(ports.claimForUdp())
    }

    @Test
    fun `the in-flight ceiling is bounded`() {
        val ports = ChainedOwnResolverPorts(
            nowMillis = { now },
            maximumConcurrentClaims = 2,
        )
        ports.claimForUdp()!!
        ports.claimForUdp()!!

        assertNull(ports.claimForUdp())
    }
}
