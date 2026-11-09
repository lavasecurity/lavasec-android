package com.lavasec.app.vpn.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LavaWgCodecTest {

    @Test
    fun `a null or empty result is an internal failure`() {
        assertEquals(LavaWgResponse.Failure(LavaWgError.INTERNAL), LavaWgCodec.decode(null))
        assertEquals(LavaWgResponse.Failure(LavaWgError.INTERNAL), LavaWgCodec.decode(ByteArray(0)))
    }

    @Test
    fun `op none is the no-op outcome`() {
        assertEquals(LavaWgResponse.None, LavaWgCodec.decode(byteArrayOf(0, 0)))
    }

    @Test
    fun `write ops carry their payload`() {
        assertEquals(
            LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(1, 2, 3)),
            LavaWgCodec.decode(byteArrayOf(1, 0, 1, 2, 3)),
        )
        assertEquals(
            LavaWgResponse.Produced(LavaWgOp.WRITE_TO_TUNNEL_V4, byteArrayOf(9)),
            LavaWgCodec.decode(byteArrayOf(2, 0, 9)),
        )
        assertEquals(
            LavaWgResponse.Produced(LavaWgOp.WRITE_TO_TUNNEL_V6, byteArrayOf(9)),
            LavaWgCodec.decode(byteArrayOf(3, 0, 9)),
        )
    }

    @Test
    fun `decapsulation carries the inner source address`() {
        assertEquals(
            LavaWgResponse.Produced(
                LavaWgOp.WRITE_TO_TUNNEL_V4,
                byteArrayOf(0xAA.toByte()),
                byteArrayOf(10, 0, 0, 1),
            ),
            LavaWgCodec.decode(byteArrayOf(2, 4, 10, 0, 0, 1, 0xAA.toByte())),
        )
        // A 16-byte IPv6 source.
        val v6 = byteArrayOf(3, 16) + ByteArray(16) { 0x20 } + byteArrayOf(7)
        val decoded = LavaWgCodec.decode(v6) as LavaWgResponse.Produced
        assertEquals(16, decoded.sourceAddress!!.size)
        assertEquals(1, decoded.packet.size)
    }

    @Test
    fun `a malformed address length or truncation is an internal failure`() {
        assertEquals(LavaWgResponse.Failure(LavaWgError.INTERNAL), LavaWgCodec.decode(byteArrayOf(2, 5, 1, 2, 3, 4, 5)))
        assertEquals(LavaWgResponse.Failure(LavaWgError.INTERNAL), LavaWgCodec.decode(byteArrayOf(2, 16, 1, 2)))
        assertEquals(LavaWgResponse.Failure(LavaWgError.INTERNAL), LavaWgCodec.decode(byteArrayOf(2)))
    }

    @Test
    fun `every negative tag maps to its typed error`() {
        for (error in LavaWgError.entries) {
            assertEquals(
                LavaWgResponse.Failure(error),
                LavaWgCodec.decode(byteArrayOf(error.code.toByte(), 0)),
            )
        }
    }

    @Test
    fun `an unknown tag is an internal failure`() {
        assertEquals(LavaWgResponse.Failure(LavaWgError.INTERNAL), LavaWgCodec.decode(byteArrayOf(9, 0)))
    }

    @Test
    fun `stats decode the five-field snapshot`() {
        val values = longArrayOf(
            1234L,
            10L,
            20L,
            42L,
            java.lang.Float.floatToRawIntBits(0.25f).toLong(),
        )
        val stats = LavaWgCodec.decodeStats(values)
        assertEquals(1234L, stats!!.timeSinceLastHandshakeMs)
        assertEquals(10L, stats.txBytes)
        assertEquals(20L, stats.rxBytes)
        assertEquals(42, stats.estimatedRoundTripMs)
        assertEquals(0.25f, stats.estimatedLoss, 0.0f)
    }

    @Test
    fun `stats tolerate the no-session and unknown shapes`() {
        assertEquals(-1L, LavaWgCodec.decodeStats(longArrayOf(-1, 0, 0, -1, 0))!!.timeSinceLastHandshakeMs)
        assertNull(LavaWgCodec.decodeStats(null))
        assertNull(LavaWgCodec.decodeStats(longArrayOf(-6))) // single-element error
        assertNull(LavaWgCodec.decodeStats(longArrayOf(1, 2, 3))) // too short
    }

    @Test
    fun `a peer address must be 0, 4, or 16 bytes`() {
        assertTrue(LavaWgCodec.requireValidPeerAddress(ByteArray(0)).isEmpty())
        assertEquals(4, LavaWgCodec.requireValidPeerAddress(ByteArray(4)).size)
        assertEquals(16, LavaWgCodec.requireValidPeerAddress(ByteArray(16)).size)
        assertThrows(IllegalArgumentException::class.java) {
            LavaWgCodec.requireValidPeerAddress(ByteArray(6))
        }
    }
}
