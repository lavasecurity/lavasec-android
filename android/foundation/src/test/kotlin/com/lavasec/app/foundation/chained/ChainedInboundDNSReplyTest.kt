package com.lavasec.app.foundation.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The allocation-free inbound DNS-reply identification, ported from iOS `ChainedInboundDNSReply`. */
class ChainedInboundDNSReplyTest {

    @Test
    fun `a port fifty three source is a DNS reply and not counted as non-DNS`() {
        val packet = udp(
            sourcePort = 53,
            destinationPort = 40000,
            source = byteArrayOf(10, 64, 0, 1),
        )

        assertEquals(53, ChainedInboundDNSReply.sourcePort(packet))
        assertTrue(ChainedInboundDNSReply.isDNSReply(packet))
        assertFalse(ChainedInboundDNSReply.carriesNonDNSSourcePort(packet))
    }

    @Test
    fun `an HTTPS source port from the resolver is non-DNS`() {
        val packet = udp(
            sourcePort = 443,
            destinationPort = 40000,
            source = byteArrayOf(1, 1, 1, 1),
        )

        assertEquals(443, ChainedInboundDNSReply.sourcePort(packet))
        assertFalse(ChainedInboundDNSReply.isDNSReply(packet))
        assertTrue(ChainedInboundDNSReply.carriesNonDNSSourcePort(packet))
    }

    @Test
    fun `a TCP source port is read the same way`() {
        val packet = ipv4(
            protocol = 6,
            source = byteArrayOf(10, 64, 0, 1),
        ).also { putU16(it, 20, 53) }

        assertEquals(53, ChainedInboundDNSReply.sourcePort(packet))
        assertTrue(ChainedInboundDNSReply.isDNSReply(packet))
    }

    @Test
    fun `a non-first fragment is unreadable and stays DNS-shaped`() {
        val packet = udp(
            sourcePort = 443,
            destinationPort = 40000,
            source = byteArrayOf(1, 1, 1, 1),
        )
        // Fragment offset 1, no more-fragments flag.
        putU16(packet, 6, 0x0001)

        assertNull(ChainedInboundDNSReply.sourcePort(packet))
        assertFalse(ChainedInboundDNSReply.carriesNonDNSSourcePort(packet))
        assertFalse(ChainedInboundDNSReply.isDNSReply(packet))
    }

    @Test
    fun `a non-UDP-TCP protocol and a truncated header are unreadable`() {
        val icmp = ipv4(protocol = 1, source = byteArrayOf(1, 1, 1, 1))
        assertNull(ChainedInboundDNSReply.sourcePort(icmp))

        val short = byteArrayOf(0x45, 0, 0)
        assertNull(ChainedInboundDNSReply.sourcePort(short))

        val notV4 = ByteArray(20)
        notV4[0] = 0x60
        assertNull(ChainedInboundDNSReply.sourcePort(notV4))
    }

    private fun udp(
        sourcePort: Int,
        destinationPort: Int,
        source: ByteArray,
    ): ByteArray {
        val packet = ipv4(protocol = 17, source = source)
        putU16(packet, 20, sourcePort)
        putU16(packet, 22, destinationPort)
        putU16(packet, 24, 8)
        return packet
    }

    private fun ipv4(protocol: Int, source: ByteArray): ByteArray {
        val packet = ByteArray(28)
        packet[0] = 0x45
        putU16(packet, 2, packet.size)
        packet[9] = protocol.toByte()
        source.copyInto(packet, 12)
        byteArrayOf(10, 7, 0, 2).copyInto(packet, 16)
        return packet
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
