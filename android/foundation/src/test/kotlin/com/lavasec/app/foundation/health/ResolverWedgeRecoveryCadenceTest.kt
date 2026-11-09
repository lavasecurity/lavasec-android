package com.lavasec.app.foundation.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverWedgeRecoveryCadenceTest {
    @Test
    fun `default wedge cadence is two four eight sixteen then thirty seconds`() {
        val cadence = ResolverWedgeRecoveryCadence()

        assertEquals(2_000L, cadence.delayMillisForAttempt(0))
        assertEquals(4_000L, cadence.delayMillisForAttempt(1))
        assertEquals(8_000L, cadence.delayMillisForAttempt(2))
        assertEquals(16_000L, cadence.delayMillisForAttempt(3))
        assertEquals(30_000L, cadence.delayMillisForAttempt(4))
        assertEquals(30_000L, cadence.delayMillisForAttempt(5))
        assertEquals(30_000L, cadence.delayMillisForAttempt(Int.MAX_VALUE))
    }

    @Test
    fun `cadence is monotonic capped and negative attempts use the first delay`() {
        val cadence = ResolverWedgeRecoveryCadence()
        assertEquals(2_000L, cadence.delayMillisForAttempt(-1))
        assertEquals(2_000L, cadence.delayMillisForAttempt(Int.MIN_VALUE))

        val delays = (0..64).map(cadence::delayMillisForAttempt)
        assertTrue(delays.zipWithNext().all { (left, right) -> right >= left })
        assertTrue(delays.all { it <= 30_000L })
    }

    @Test
    fun `custom and inverted cadence parameters remain bounded`() {
        val custom = ResolverWedgeRecoveryCadence(
            firstDelayMillis = 5_000L,
            maxDelayMillis = 30_000L,
        )
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L),
            (0..4).map(custom::delayMillisForAttempt))

        val inverted = ResolverWedgeRecoveryCadence(
            firstDelayMillis = 50_000L,
            maxDelayMillis = 30_000L,
        )
        assertEquals(30_000L, inverted.firstDelayMillis)
        assertEquals(30_000L, inverted.delayMillisForAttempt(10))

        val zero = ResolverWedgeRecoveryCadence(maxDelayMillis = 0L)
        assertEquals(0L, zero.delayMillisForAttempt(0))
        assertEquals(0L, zero.delayMillisForAttempt(10))
    }
}
