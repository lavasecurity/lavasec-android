package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedDnsCaptureFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChainedCaptureFloorRefreshGateTest {
    private data class Owner(val generation: Long)
    private val installed = ChainedDnsCaptureFloor.forCapturedResolvers(listOf("4.2.2.2", "4.2.2.3"))
    private val changed = ChainedDnsCaptureFloor.forCapturedResolvers(listOf("4.2.2.4"))

    @Test
    fun `IPv6 membership changes request replacement but equivalent literal spelling does not`() {
        val owner = Owner(7)
        val captured = ChainedDnsCaptureFloor.forCapturedResolvers(listOf("2001:4860:4860::1234"))
        val gate = ChainedCaptureFloorRefreshGate(owner, captured)
        assertNull(gate.claim(owner, ChainedDnsCaptureFloor.forCapturedResolvers(listOf("2001:4860:4860:0:0:0:0:1234")), 0L))
        assertNotNull(gate.claim(owner, ChainedDnsCaptureFloor.forCapturedResolvers(listOf("2001:4860:4860::1235")), 1L))
    }

    @Test
    fun `changed membership submits once and rejected delivery explicitly releases retry`() {
        val owner = Owner(7)
        val gate = ChainedCaptureFloorRefreshGate(owner, installed)
        val first = gate.claim(owner, changed, 10L)!!
        assertNull(gate.claim(owner, changed, 11L))
        gate.rejected(first)
        val retry = gate.claim(owner, changed, 12L)!!
        gate.rejected(first) // Late refusal from the prior request cannot release this one.
        assertNull(gate.claim(owner, changed, 13L))
        assertEquals(first + 1L, retry)
    }

    @Test
    fun `scheduled but unadopted refresh retries only while the exact predecessor remains current`() {
        val owner = Owner(7)
        val gate = ChainedCaptureFloorRefreshGate(owner, installed)
        assertNotNull(gate.claim(owner, changed, 0L))
        assertNull(gate.claim(owner, changed, gate.retryAfterMillis - 1L))
        assertNotNull(gate.claim(owner, changed, gate.retryAfterMillis))
        assertNull(gate.claim(Owner(7), changed, gate.retryAfterMillis * 2L))
        gate.retire()
        assertNull(gate.claim(owner, changed, gate.retryAfterMillis * 3L))
    }

    @Test
    fun `same membership reorder private delta missing network and full floor require no rebuild`() {
        val owner = Owner(7)
        val gate = ChainedCaptureFloorRefreshGate(owner, installed)
        val reordered = ChainedDnsCaptureFloor.forCapturedResolvers(listOf("4.2.2.3", "192.168.1.53", "4.2.2.2"))
        assertNull(gate.claim(owner, reordered, 0L))
        assertNull(gate.claim(owner, null, 1L))
        assertNull(gate.claim(null, changed, 2L))
        val full = ChainedCaptureFloorRefreshGate(owner, ChainedDnsCaptureFloor.empty)
        assertNull(full.claim(owner, changed, 3L))
    }
}
