package com.lavasec.app.foundation.chained

import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure inner IPv4/UDP codec the T0 egress builds queries and client responses with. */
class ChainedInnerUdpPacketTest {

    private val client = byteArrayOf(10, 7, 0, 2)
    private val resolver = byteArrayOf(10, 64, 0, 1)
    private val dnsPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, 0)

    @Test
    fun `IPv6 client DNS keeps exact tuple and builds mandatory checksum for odd reply length`() {
        val source = InetAddress.getByName("fd00:1a7a::2").address
        val destination = InetAddress.getByName("2606:4700:4700::1111").address
        val query = ChainedInnerUdpPacket.build(source, destination, 40001, 53, 0, byteArrayOf(1, 2, 3))
        assertNull(ChainedInnerUdpPacket.parse(query)) // General WireGuard codec stays IPv4-only.
        val parsed = ChainedInnerUdpPacket.parseClientDns(query)!!
        assertArrayEquals(source, parsed.sourceAddress)
        assertArrayEquals(destination, parsed.destinationAddress)
        val reply = parsed.responseWith(byteArrayOf(4, 5, 6, 7, 8))
        val response = ChainedInnerUdpPacket.parseIPv6Udp(reply)!!
        assertArrayEquals(destination, response.sourceAddress)
        assertArrayEquals(source, response.destinationAddress)
        assertEquals(53, response.sourcePort)
        assertEquals(40001, response.destinationPort)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7, 8), response.payload)
        assertEquals(0xffff, ipv6UdpChecksumSum(reply))
        assertTrue(reply[46] != 0.toByte() || reply[47] != 0.toByte())
        assertNull(ChainedInnerUdpPacket.parseClientDns(reply))
    }

    @Test
    fun `IPv6 DNS refuses extensions fragments missing payload and damaged length before serving`() {
        val packet = ChainedInnerUdpPacket.build(
            InetAddress.getByName("fd00:1a7a::2").address,
            InetAddress.getByName("fd00:1a7a::1").address, 40001, 53, 0, byteArrayOf(1, 2, 3),
        )
        for (nextHeader in listOf(0, 43, 44, 60, 6)) {
            assertNull(ChainedInnerUdpPacket.parseClientDns(packet.copyOf().also { it[6] = nextHeader.toByte() }))
        }
        assertNull(ChainedInnerUdpPacket.parseClientDns(packet.copyOf().also { it[4] = 0x7f }))
        assertNull(ChainedInnerUdpPacket.parseClientDns(packet.copyOf().also { it[44] = 0x7f }))
        assertNull(ChainedInnerUdpPacket.parseClientDns(packet.copyOf(48).also { it[5] = 8; it[45] = 8 }))
    }

    private fun ipv6UdpChecksumSum(packet: ByteArray): Int {
        fun word(offset: Int): Int = ((packet[offset].toInt() and 255) shl 8) +
            if (offset + 1 < packet.size) packet[offset + 1].toInt() and 255 else 0
        var sum = (packet.size - 40) + 17
        for (offset in 8 until packet.size step 2) sum += word(offset)
        while (sum > 0xffff) sum = (sum and 0xffff) + (sum ushr 16)
        return sum
    }

    @Test
    fun `build then parse round-trips the tuple and payload`() {
        val packet = ChainedInnerUdpPacket.build(
            sourceAddress = client,
            destinationAddress = resolver,
            sourcePort = 51000,
            destinationPort = 53,
            identification = 0xABCD,
            payload = dnsPayload,
        )

        val parsed = ChainedInnerUdpPacket.parse(packet)

        requireNotNull(parsed)
        assertArrayEquals(client, parsed.sourceAddress)
        assertArrayEquals(resolver, parsed.destinationAddress)
        assertEquals(51000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertEquals(0xABCD, parsed.identification)
        assertArrayEquals(dnsPayload, parsed.payload)
        assertTrue(ChainedInnerUdpPacket.isDnsQuery(packet))
    }

    @Test
    fun `responseWith reverses addresses and ports and carries the response payload`() {
        val query = ChainedInnerUdpPacket.parse(
            ChainedInnerUdpPacket.build(
                sourceAddress = client,
                destinationAddress = byteArrayOf(10, 111, 0, 1),
                sourcePort = 40000,
                destinationPort = 53,
                identification = 7,
                payload = dnsPayload,
            ),
        )!!

        val responsePayload = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0)
        val response = ChainedInnerUdpPacket.parse(query.responseWith(responsePayload))!!

        assertArrayEquals(byteArrayOf(10, 111, 0, 1), response.sourceAddress)
        assertArrayEquals(client, response.destinationAddress)
        assertEquals(53, response.sourcePort)
        assertEquals(40000, response.destinationPort)
        assertArrayEquals(responsePayload, response.payload)
    }

    @Test
    fun `a fragmented packet is refused rather than parsed`() {
        val packet = ChainedInnerUdpPacket.build(
            sourceAddress = client,
            destinationAddress = resolver,
            sourcePort = 1,
            destinationPort = 53,
            identification = 1,
            payload = dnsPayload,
        )
        // Set the more-fragments bit in the flags/fragment field.
        packet[6] = 0x20

        assertNull(ChainedInnerUdpPacket.parse(packet))
        assertFalse(ChainedInnerUdpPacket.isDnsQuery(packet))
    }

    @Test
    fun `a non-UDP protocol is refused`() {
        val packet = ChainedInnerUdpPacket.build(
            sourceAddress = client,
            destinationAddress = resolver,
            sourcePort = 1,
            destinationPort = 53,
            identification = 1,
            payload = dnsPayload,
        )
        packet[9] = 6 // TCP

        assertNull(ChainedInnerUdpPacket.parse(packet))
    }

    @Test
    fun `a truncated or overlong total length is refused`() {
        val packet = ChainedInnerUdpPacket.build(
            sourceAddress = client,
            destinationAddress = resolver,
            sourcePort = 1,
            destinationPort = 53,
            identification = 1,
            payload = dnsPayload,
        )
        val truncated = packet.copyOf(packet.size - 1)
        assertNull(ChainedInnerUdpPacket.parse(truncated))

        val overlong = packet.copyOf()
        overlong[3] = 0xff.toByte()
        overlong[2] = 0xff.toByte()
        assertNull(ChainedInnerUdpPacket.parse(overlong))
    }

    @Test
    fun `an empty payload is refused`() {
        val packet = ChainedInnerUdpPacket.build(
            sourceAddress = client,
            destinationAddress = resolver,
            sourcePort = 1,
            destinationPort = 53,
            identification = 1,
            payload = ByteArray(0),
        )
        assertNull(ChainedInnerUdpPacket.parse(packet))
    }
}
