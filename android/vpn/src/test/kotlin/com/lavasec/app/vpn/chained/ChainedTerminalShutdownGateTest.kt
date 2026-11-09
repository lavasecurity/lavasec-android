package com.lavasec.app.vpn.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal-shutdown latch: once a hard chained failure is claimed under the reconnect
 * serialization, a reconnect that acquires it afterwards must not install a replacement that the
 * stale terminal transition (which runs outside that serialization) would then close.
 */
class ChainedTerminalShutdownGateTest {

    @Test
    fun `the latch starts clear and is sticky once marked`() {
        val gate = ChainedTerminalShutdownGate()

        assertFalse(gate.isPending())
        gate.mark()
        assertTrue(gate.isPending())
        // Idempotent: a second mark cannot clear or double-count.
        gate.mark()
        assertTrue(gate.isPending())
    }

    @Test
    fun `a reconnect observed after the latch is refused`() {
        val gate = ChainedTerminalShutdownGate()
        val installs = mutableListOf<String>()

        // Before the terminal claim, a reconnect installs a replacement.
        if (!gate.isPending()) installs += "replacement"

        // The claim latches the shutdown while holding the reconnect serialization.
        gate.mark()

        // The reconnect that acquires the serialization in the teardown gap must not install, or
        // the stale terminal transition would close it and retire the whole service.
        if (!gate.isPending()) installs += "stale-replacement"

        assertEquals(listOf("replacement"), installs)
    }
}
