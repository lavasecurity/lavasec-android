package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedInnerUdpPacket
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.vpn.engine.LavaWgError
import com.lavasec.app.foundation.chained.ChainedPacketQueueLimits
import com.lavasec.app.vpn.engine.LavaWgOp
import com.lavasec.app.vpn.engine.LavaWgResponse
import com.lavasec.app.vpn.engine.LavaWgStats
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ChainedStackWireGuardSessionTest {
    @Test
    fun `split entry selects its routes and full exit uses a separate channel`() {
        val config = profile("198.51.100.2", "10.2.0.2", listOf("0.0.0.0/0"))
            .withEntryHop(profile("198.51.100.1", "10.1.0.2", listOf("10.8.0.0/16")))
        val first = ScriptedSession(0)
        val second = ScriptedSession(1)
        val stack = ChainedStackWireGuardSession(config, listOf(first, second))
        val privateOutput = stack.encapsulate(udp(config.clientAddress, "10.8.1.1")) as LavaWgResponse.Produced
        assertEquals(0, ChainedRowDatagram.decode(privateOutput.packet)!!.first)
        assertArrayEquals(ip("10.1.0.2"), first.sent.single().copyOfRange(12, 16))
        val publicOutput = stack.encapsulate(udp(config.clientAddress, "203.0.113.8")) as LavaWgResponse.Produced
        assertEquals(1, ChainedRowDatagram.decode(publicOutput.packet)!!.first)
        assertArrayEquals(ip("10.2.0.2"), second.sent.single().copyOfRange(12, 16))
        stack.close()
        assertEquals(1, first.closed)
        assertEquals(1, second.closed)
    }

    @Test
    fun `full entry wraps split exit UDP with no direct to exit output`() {
        val config = profile("198.51.100.2", "10.2.0.2", listOf("10.8.0.0/16"))
            .withEntryHop(profile("198.51.100.1", "10.1.0.2", listOf("0.0.0.0/0")))
        val first = ScriptedSession(0)
        val second = ScriptedSession(1)
        val stack = ChainedStackWireGuardSession(config, listOf(first, second), 50001)
        val output = stack.encapsulate(udp(config.clientAddress, "10.8.1.1")) as LavaWgResponse.Produced
        assertEquals(0, ChainedRowDatagram.decode(output.packet)!!.first)
        val envelope = ChainedInnerUdpPacket.parse(first.sent.single())!!
        assertArrayEquals(ip("10.1.0.2"), envelope.sourceAddress)
        assertArrayEquals(ip("198.51.100.2"), envelope.destinationAddress)
        assertEquals(50001, envelope.sourcePort)
        assertEquals(51820, envelope.destinationPort)
        assertArrayEquals(byteArrayOf(4, 1), envelope.payload)
        stack.encapsulate(udp(config.clientAddress, "203.0.113.8"))
        assertEquals(2, first.sent.size)
        assertEquals(1, second.sent.size)
        // An injection labelled as a direct exit channel is impossible in nested mode.
        assertEquals(LavaWgResponse.None, stack.decapsulate(ChainedRowDatagram.encode(1, byteArrayOf(4)), ip("198.51.100.2")))
        assertTrue(second.received.isEmpty())
    }

    @Test
    fun `two full rows still encrypt exit traffic inside entry`() {
        val config = profile("198.51.100.2", "10.2.0.2", listOf("0.0.0.0/0"))
            .withEntryHop(profile("198.51.100.1", "10.1.0.2", listOf("0.0.0.0/0")))
        val first = ScriptedSession(0)
        val second = ScriptedSession(1)
        val stack = ChainedStackWireGuardSession(config, listOf(first, second))
        val result = stack.encapsulate(udp(config.clientAddress, "203.0.113.8")) as LavaWgResponse.Produced
        assertEquals(0, ChainedRowDatagram.decode(result.packet)!!.first)
        assertNotNull(ChainedInnerUdpPacket.parse(first.sent.single()))
        assertEquals(1, second.sent.size)
    }

    @Test
    fun `inbound packets belong to their selected row and restore the TUN address`() {
        val config = profile("198.51.100.2", "10.2.0.2", listOf("10.8.0.0/16"))
            .withEntryHop(profile("198.51.100.1", "10.1.0.2", listOf("0.0.0.0/0")))
        val first = ScriptedSession(0)
        val second = ScriptedSession(1)
        val stack = ChainedStackWireGuardSession(config, listOf(first, second), 50001)
        first.next = LavaWgResponse.Produced(LavaWgOp.WRITE_TO_TUNNEL_V4, udp("10.8.1.1", "10.1.0.2"), ip("10.8.1.1"))
        assertEquals(LavaWgResponse.None, stack.decapsulate(ChainedRowDatagram.encode(0, byteArrayOf(4)), ip("198.51.100.1")))
        val reply = udp("10.8.1.1", "10.2.0.2")
        second.next = LavaWgResponse.Produced(LavaWgOp.WRITE_TO_TUNNEL_V4, reply, ip("10.8.1.1"))
        first.next = LavaWgResponse.Produced(LavaWgOp.WRITE_TO_TUNNEL_V4,
            ChainedInnerUdpPacket.build(ip("198.51.100.2"), ip("10.1.0.2"), 51820, 50001, 0, byteArrayOf(4)), ip("198.51.100.2"))
        val result = stack.decapsulate(ChainedRowDatagram.encode(0, byteArrayOf(4)), ip("198.51.100.1")) as LavaWgResponse.Produced
        assertArrayEquals(reply, result.packet)
        assertEquals(1, second.received.size)
        // A foreign relay tuple never reaches the exit engine.
        first.next = LavaWgResponse.Produced(LavaWgOp.WRITE_TO_TUNNEL_V4,
            ChainedInnerUdpPacket.build(ip("198.51.100.2"), ip("10.1.0.2"), 51820, 50002, 0, byteArrayOf(4)), ip("198.51.100.2"))
        val foreign = stack.decapsulate(ChainedRowDatagram.encode(0, byteArrayOf(4)), ip("198.51.100.1"))
        assertTrue(foreign is LavaWgResponse.Produced) // ordinary entry traffic, never an exit relay
        assertEquals(1, second.received.size)
    }

    @Test
    fun `healthy branch cannot acknowledge demand or new transport on another branch`() {
        val config = profile("198.51.100.2", "10.2.0.2", listOf("0.0.0.0/0"))
            .withEntryHop(profile("198.51.100.1", "10.1.0.2", listOf("10.8.0.0/16")))
        val first = ScriptedSession(0)
        val second = ScriptedSession(1)
        val stack = ChainedStackWireGuardSession(config, listOf(first, second))
        stack.encapsulate(udp(config.clientAddress, "10.8.1.1"))
        stack.encapsulate(udp(config.clientAddress, "203.0.113.8"))
        first.receivedBytes = 100
        val a = ChainedRowDatagram.encode(0, byteArrayOf(4))
        val b = ChainedRowDatagram.encode(1, byteArrayOf(4))
        val responseA = stack.decapsulate(a, ip("198.51.100.1"))
        assertFalse(stack.provesPeerActivity(a, responseA))
        assertEquals(0L, stack.stats()!!.rxBytes)
        second.receivedBytes = 50
        val responseB = stack.decapsulate(b, ip("198.51.100.2"))
        assertTrue(stack.provesPeerActivity(b, responseB))
        assertEquals(150L, stack.stats()!!.rxBytes)
        stack.transportChanged()
        assertFalse(stack.provesPeerActivity(a, stack.decapsulate(a, ip("198.51.100.1"))))
        assertTrue(stack.provesPeerActivity(b, stack.decapsulate(b, ip("198.51.100.2"))))
    }

    @Test
    fun `a terminal engine verdict survives a saturated stack output queue`() {
        val config = profile("198.51.100.2", "10.2.0.2", listOf("0.0.0.0/0"))
            .withEntryHop(profile("198.51.100.1", "10.1.0.2", listOf("10.8.0.0/16")))
        val first = ScriptedSession(0)
        val second = ScriptedSession(1)
        first.timerResponse = LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(0))
        second.timerResponse = LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(1))
        val stack = ChainedStackWireGuardSession(config, listOf(first, second))
        repeat(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH * 2) { stack.tick() }
        second.timerResponse = LavaWgResponse.Failure(LavaWgError.INTERNAL)
        assertEquals(LavaWgResponse.Failure(LavaWgError.INTERNAL), stack.tick())
        var queued = 0
        while (stack.takePendingResponse() != LavaWgResponse.None) queued++
        assertTrue(queued < ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH)
    }

    private class ScriptedSession(private val index: Int) : WireGuardSession {
        val sent = mutableListOf<ByteArray>()
        val received = mutableListOf<ByteArray>()
        var closed = 0
        var receivedBytes = 0L
        var next: LavaWgResponse = LavaWgResponse.None
        override val isOpen get() = closed == 0
        override fun encapsulate(packet: ByteArray): LavaWgResponse {
            sent += packet.copyOf()
            return LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(4, index.toByte()))
        }
        override fun decapsulate(packet: ByteArray, sourceAddress: ByteArray): LavaWgResponse {
            if (packet.isEmpty()) return LavaWgResponse.None
            received += packet.copyOf()
            return next.also { next = LavaWgResponse.None }
        }
        var timerResponse: LavaWgResponse = LavaWgResponse.None
        override fun tick() = timerResponse
        override fun forceHandshake() = LavaWgResponse.None
        override fun stats() = LavaWgStats(0, sent.sumOf { it.size.toLong() }, receivedBytes, 10, 0f)
        override fun close() { closed++ }
    }

    private fun profile(endpoint: String, client: String, routes: List<String>) = ChainedUpstreamConfiguration.create(
        endpoint, 51820, Base64.getEncoder().encodeToString(ByteArray(32) { 1 }), client, routes, 0, 1420, listOf("10.8.0.1"))
    private fun ip(address: String) = ChainedStackAddressTranslation.octets(address)
    private fun udp(source: String, destination: String) = ChainedInnerUdpPacket.build(ip(source), ip(destination), 12345, 9000, 0, byteArrayOf(7, 8, 9))
}
