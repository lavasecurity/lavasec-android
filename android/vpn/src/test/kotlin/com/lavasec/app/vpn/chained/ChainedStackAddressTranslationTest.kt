package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedInnerUdpPacket
import org.junit.Assert.*
import org.junit.Test

class ChainedStackAddressTranslationTest {
    private val local = ChainedStackAddressTranslation.octets("10.2.0.2")
    private val remote = ChainedStackAddressTranslation.octets("10.1.0.2")
    private val destination = ChainedStackAddressTranslation.octets("203.0.113.8")
    private val translation = ChainedStackAddressTranslation("10.2.0.2", "10.1.0.2")

    @Test
    fun `UDP nonzero checksum remains valid after source translation and reply destination restoration`() {
        val packet = ChainedInnerUdpPacket.build(local, destination, 12345, 443, 0, byteArrayOf(7, 8, 9, 10))
        put(packet, 26, checksum(pseudo(packet) + packet.copyOfRange(20, packet.size)))
        val mapped = translation.outbound(packet)!!
        assertArrayEquals(remote, mapped.copyOfRange(12, 16))
        assertEquals(0, checksum(mapped.copyOfRange(0, 20)))
        assertEquals(0, checksum(pseudo(mapped) + mapped.copyOfRange(20, mapped.size)))
        val reply = ChainedInnerUdpPacket.build(destination, remote, 443, 12345, 0, byteArrayOf(11, 12))
        put(reply, 26, checksum(pseudo(reply) + reply.copyOfRange(20, reply.size)))
        val restored = translation.inbound(reply)!!
        assertArrayEquals(local, restored.copyOfRange(16, 20))
        assertEquals(0, checksum(restored.copyOfRange(0, 20)))
        assertEquals(0, checksum(pseudo(restored) + restored.copyOfRange(20, restored.size)))
    }

    @Test
    fun `TCP pseudoheader is repaired and foreign destination cannot enter TUN`() {
        val packet = tcp(local, destination)
        val mapped = translation.outbound(packet)!!
        assertEquals(0, checksum(pseudo(mapped) + mapped.copyOfRange(20, mapped.size)))
        val reply = tcp(destination, remote)
        val restored = translation.inbound(reply)!!
        assertEquals(0, checksum(pseudo(restored) + restored.copyOfRange(20, restored.size)))
        assertNull(translation.inbound(tcp(destination, local)))
        val unknown = packet.copyOf().also { it[9] = 51 }
        assertNull(translation.outbound(unknown))
    }

    @Test
    fun `ICMP ownership follows quoted destination and repairs both quote and outer checksums`() {
        val original = translation.outbound(tcp(local, destination))!!
        val error = ByteArray(20 + 8 + original.size)
        error[0] = 0x45; put(error, 2, error.size); error[8] = 64; error[9] = 1
        ChainedStackAddressTranslation.octets("192.0.2.254").copyInto(error, 12)
        remote.copyInto(error, 16); error[20] = 3; error[21] = 4
        original.copyInto(error, 28)
        put(error, 10, checksum(error.copyOfRange(0, 20)))
        put(error, 22, checksum(error.copyOfRange(20, error.size)))
        assertEquals(ChainedStackAddressTranslation.ipv4(destination, 0), translation.inboundRouteAddress(error))
        val restored = translation.inbound(error)!!
        assertArrayEquals(local, restored.copyOfRange(16, 20))
        assertArrayEquals(local, restored.copyOfRange(40, 44))
        assertEquals(0, checksum(restored.copyOfRange(0, 20)))
        assertEquals(0, checksum(restored.copyOfRange(20, restored.size)))
        val foreign = error.copyOf().also { local.copyInto(it, 40) }
        assertNull(translation.inboundRouteAddress(foreign))
        assertNull(translation.inbound(foreign))
    }

    private fun tcp(source: ByteArray, destination: ByteArray): ByteArray {
        val packet = ByteArray(40)
        packet[0] = 0x45; put(packet, 2, packet.size); packet[8] = 64; packet[9] = 6
        source.copyInto(packet, 12); destination.copyInto(packet, 16)
        put(packet, 20, 12345); put(packet, 22, 443); packet[32] = 0x50
        put(packet, 10, checksum(packet.copyOfRange(0, 20)))
        put(packet, 36, checksum(pseudo(packet) + packet.copyOfRange(20, packet.size)))
        return packet
    }
    private fun pseudo(packet: ByteArray): ByteArray = packet.copyOfRange(12, 20) + byteArrayOf(0, packet[9], ((packet.size-20) ushr 8).toByte(), (packet.size-20).toByte())
    private fun checksum(bytes: ByteArray): Int {
        var sum = 0L
        for (index in bytes.indices step 2) sum += ((bytes[index].toInt() and 255) shl 8) + if(index+1<bytes.size) bytes[index+1].toInt() and 255 else 0
        while(sum>65535) sum=(sum and 65535)+(sum ushr 16)
        return sum.toInt().inv() and 65535
    }
    private fun put(bytes: ByteArray, at: Int, value: Int) { bytes[at]=(value ushr 8).toByte(); bytes[at+1]=value.toByte() }
}
