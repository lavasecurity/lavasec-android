package com.lavasec.app.vpn.chained

import org.junit.Assert.*
import org.junit.Test

class ChainedApplicationHealthTest {
    @Test fun `DNS needs two distinct names over a sustained window`() {
        val health = ChainedApplicationHealth(true)
        assertFalse(health.dnsUnanswered(0, "a", 0, true))
        assertFalse(health.dnsUnanswered(0, "a", 21, true))
        assertTrue(health.dnsUnanswered(0, "b", 22, true))
        health.sample(sample(authenticated = true), 23, false)
        assertTrue(health.holdsOutage)
        assertTrue(health.dnsAnswered(0))
        assertFalse(health.holdsOutage)
    }

    @Test fun `old timeouts cannot cross a resolver answer or network transition`() {
        val health = ChainedApplicationHealth(true)
        health.dnsUnanswered(0, "a", 0, true)
        health.dnsAnswered(0)
        assertFalse(health.dnsUnanswered(0, "b", 30, true))
        assertFalse(health.dnsUnanswered(1, "b", 30, true))
        health.resetWindow(clearHolds = true)
        assertFalse(health.dnsUnanswered(1, "a", 60, true))
        assertFalse(health.dnsUnanswered(2, "a", 60, true))
    }

    @Test fun `DNS declarations stay capped across window resets`() {
        val health = ChainedApplicationHealth(true)
        repeat(4) { index ->
            val epoch = health.dnsEpoch
            assertFalse(health.dnsUnanswered(epoch, "a", index * 30, true))
            assertEquals(index < 3, health.dnsUnanswered(epoch, "b", index * 30 + 21, true))
            health.resetWindow(clearHolds = true)
        }
        assertEquals(3, health.dnsDeclarations)
    }

    @Test fun `continuous application demand detects a keepalive-only full tunnel`() {
        val health = ChainedApplicationHealth(true)
        for (now in 0..20) assertFalse(health.sample(sample(send = true, authenticated = true), now, true))
        assertTrue(health.sample(sample(send = true, authenticated = true), 21, true))
        assertTrue(health.egressHeld)
        health.sample(sample(generation = 1, authenticated = true), 22, false)
        assertTrue(health.egressHeld)
        health.sample(sample(generation = 1, bytes = 50), 23, false)
        assertFalse(health.egressHeld)
    }

    @Test fun `idle gaps split routing and real forwarding prevent egress declarations`() {
        val idle = ChainedApplicationHealth(true)
        idle.sample(sample(send = true), 0, true)
        idle.sample(sample(), 10, true)
        assertFalse(idle.sample(sample(send = true), 22, true))
        val split = ChainedApplicationHealth(false)
        val active = ChainedApplicationHealth(true)
        for (now in 0..100) {
            assertFalse(split.sample(sample(send = true), now, true))
            assertFalse(active.sample(sample(send = true, bytes = now.toLong()), now, true))
        }
    }

    @Test fun `egress declarations capped and disabled during existing outage`() {
        val health = ChainedApplicationHealth(true)
        repeat(4) { index ->
            health.resetWindow(clearHolds = true)
            for (second in 0..20) assertFalse(health.sample(sample(send = true), index * 30 + second, true))
            assertFalse(health.sample(sample(send = true), index * 30 + 21, false))
            assertEquals(index < 3, health.sample(sample(send = true), index * 30 + 22, true))
        }
        assertEquals(3, health.egressDeclarations)
    }

    private fun sample(send: Boolean = false, authenticated: Boolean = false, generation: Long = 0, bytes: Long = 0) =
        ChainedDriverLivenessSample(true, null, authenticated, send, generation, bytes)
}
