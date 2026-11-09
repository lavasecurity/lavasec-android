package com.lavasec.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4UdpDnsPacketTest {
    @Test
    fun `parse accepts a bounded IPv4 UDP DNS datagram with header options`() {
        val dns = byteArrayOf(0x12, 0x34, 0x01, 0x00)
        val capture = request(dns, headerWords = 6) + byteArrayOf(0x55, 0x66)

        val packet = requireNotNull(Ipv4UdpDnsPacket.parse(capture))

        assertEquals("10.0.0.8", packet.sourceAddress.hostAddress)
        assertEquals("10.111.0.1", packet.destinationAddress.hostAddress)
        assertEquals(53_000, packet.sourcePort)
        assertEquals(53, packet.destinationPort)
        assertEquals(0x1234, packet.identification)
        assertArrayEquals(dns, packet.payload)
    }

    @Test
    fun `parse rejects malformed bounds non UDP and non DNS datagrams`() {
        assertNull(Ipv4UdpDnsPacket.parse(ByteArray(27)))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1)).also { it[0] = 0x65 }))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1)).also { it[0] = 0x44 }))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1)).also { write16(it, 2, 27) }))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1)).also { write16(it, 2, it.size + 1) }))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1)).also { it[9] = 6 }))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1), destinationPort = 443)))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf())))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1)).also { write16(it, 24, 7) }))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1)).also { write16(it, 24, 10) }))
    }

    @Test
    fun `parse rejects fragments and a reserved IPv4 flag while allowing do-not-fragment`() {
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1)).also { write16(it, 6, 0x2000) }))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1)).also { write16(it, 6, 0x0001) }))
        assertNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1)).also { write16(it, 6, 0x8000) }))

        assertTrue(
            Ipv4UdpDnsPacket.parse(
                request(byteArrayOf(1)).also { write16(it, 6, 0x4000) },
            ) != null,
        )
    }

    @Test
    fun `parse enforces the DNS message bound`() {
        assertEquals(4_096, requireNotNull(Ipv4UdpDnsPacket.parse(request(ByteArray(4_096)))).payload.size)
        assertNull(Ipv4UdpDnsPacket.parse(request(ByteArray(4_097))))
    }

    @Test
    fun `parsed payload is isolated from both capture and caller mutations`() {
        val capture = request(byteArrayOf(1, 2, 3))
        val packet = requireNotNull(Ipv4UdpDnsPacket.parse(capture))

        capture[capture.lastIndex] = 9
        val firstRead = packet.payload
        firstRead[0] = 8

        assertArrayEquals(byteArrayOf(1, 2, 3), packet.payload)
    }

    @Test
    fun `synthetic destination predicate matches only the configured tunnel DNS address`() {
        val synthetic = requireNotNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1))))
        val other = requireNotNull(
            Ipv4UdpDnsPacket.parse(
                request(byteArrayOf(1)).also {
                    byteArrayOf(10, 111, 0, 2).copyInto(it, 16)
                },
            ),
        )

        assertTrue(synthetic.isSyntheticDnsDestination)
        assertFalse(other.isSyntheticDnsDestination)
    }

    @Test
    fun `response swaps addresses and ports and emits a valid IPv4 header checksum`() {
        val packet = requireNotNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1, 2, 3))))

        val response = packet.response(byteArrayOf(4, 5, 6, 7))

        assertEquals(0x45, response[0].toInt() and 0xff)
        assertEquals(32, read16(response, 2))
        assertEquals(0x1234, read16(response, 4))
        assertEquals(0x4000, read16(response, 6))
        assertEquals(64, response[8].toInt() and 0xff)
        assertEquals(17, response[9].toInt() and 0xff)
        assertEquals("10.111.0.1", dottedAddress(response, 12))
        assertEquals("10.0.0.8", dottedAddress(response, 16))
        assertEquals(53, read16(response, 20))
        assertEquals(53_000, read16(response, 22))
        assertEquals(12, read16(response, 24))
        assertEquals(0, read16(response, 26))
        assertEquals(0xffff, foldedHeaderSum(response, 20))
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), response.copyOfRange(28, response.size))
    }

    @Test
    fun `response supports exactly one unfragmented tunnel MTU and rejects larger DNS payloads`() {
        val packet = requireNotNull(Ipv4UdpDnsPacket.parse(request(byteArrayOf(1))))

        assertEquals(1_280, packet.response(ByteArray(1_252)).size)
        assertThrows(IllegalArgumentException::class.java) {
            packet.response(ByteArray(1_253))
        }
    }

    private fun request(
        payload: ByteArray,
        headerWords: Int = 5,
        destinationPort: Int = 53,
    ): ByteArray {
        val headerBytes = headerWords * 4
        val totalLength = headerBytes + UDP_HEADER_BYTES + payload.size
        return ByteArray(totalLength).also { packet ->
            packet[0] = ((4 shl 4) or headerWords).toByte()
            write16(packet, 2, totalLength)
            write16(packet, 4, 0x1234)
            packet[8] = 31
            packet[9] = 17
            byteArrayOf(10, 0, 0, 8).copyInto(packet, 12)
            byteArrayOf(10, 111, 0, 1).copyInto(packet, 16)
            write16(packet, headerBytes, 53_000)
            write16(packet, headerBytes + 2, destinationPort)
            write16(packet, headerBytes + 4, UDP_HEADER_BYTES + payload.size)
            payload.copyInto(packet, headerBytes + UDP_HEADER_BYTES)
        }
    }

    private fun dottedAddress(bytes: ByteArray, offset: Int): String =
        bytes.copyOfRange(offset, offset + 4)
            .joinToString(".") { (it.toInt() and 0xff).toString() }

    private fun foldedHeaderSum(bytes: ByteArray, length: Int): Int {
        var sum = 0L
        for (offset in 0 until length step 2) {
            sum += read16(bytes, offset).toLong()
            while (sum > 0xffffL) {
                sum = (sum and 0xffffL) + (sum ushr 16)
            }
        }
        return sum.toInt()
    }

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private fun write16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private companion object {
        const val UDP_HEADER_BYTES = 8
    }
}
