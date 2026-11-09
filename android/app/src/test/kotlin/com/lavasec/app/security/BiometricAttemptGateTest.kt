package com.lavasec.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-bearing prompt safety guarantee: a terminal callback can only settle the attempt that is
 * still current, so a never-presented / cancelled / superseded prompt can never deliver a success.
 */
class BiometricAttemptGateTest {

    @Test
    fun `a fresh gate settles nothing until an attempt begins`() {
        val gate = BiometricAttemptGate()
        assertFalse(gate.isCurrent(1L))
        assertFalse(gate.settle(1L))
    }

    @Test
    fun `the current attempt settles exactly once`() {
        val gate = BiometricAttemptGate()
        val attempt = gate.begin()
        assertTrue(gate.isCurrent(attempt))
        assertTrue(gate.settle(attempt))
        // A duplicate terminal callback for the same attempt is ignored.
        assertFalse(gate.settle(attempt))
        assertFalse(gate.isCurrent(attempt))
    }

    @Test
    fun `an invalidated attempt can never settle a late success`() {
        val gate = BiometricAttemptGate()
        val attempt = gate.begin()
        assertTrue(gate.invalidate(attempt))
        assertFalse(gate.settle(attempt))
        assertFalse(gate.isCurrent(attempt))
    }

    @Test
    fun `a superseding attempt retires the previous token`() {
        val gate = BiometricAttemptGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.settle(first))
        assertTrue(gate.isCurrent(second))
        assertTrue(gate.settle(second))
    }

    @Test
    fun `tokens are monotonically distinct across attempts`() {
        val gate = BiometricAttemptGate()
        val tokens = (1..5).map { gate.begin() }
        assertEquals(tokens.toSet().size, tokens.size)
    }
}
