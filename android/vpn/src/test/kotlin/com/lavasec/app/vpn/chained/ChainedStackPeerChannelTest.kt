package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChainedStackPeerChannelTest {
    @Test
    fun `private row tags are removed before send and distinct receives retain socket source identity`() = runTest {
        val first = RecordingChannel()
        val second = RecordingChannel()
        val stack = ChainedStackPeerChannel(listOf(first, second), StandardTestDispatcher(testScheduler))
        try {
            assertTrue(stack.send(ChainedRowDatagram.encode(0, byteArrayOf(4, 5))))
            assertTrue(stack.send(ChainedRowDatagram.encode(1, byteArrayOf(6, 7))))
            assertArrayEquals(byteArrayOf(4, 5), first.sent.single())
            assertArrayEquals(byteArrayOf(6, 7), second.sent.single())
            first.input.send(ChainedPeerDatagram(byteArrayOf(8), byteArrayOf(192.toByte(), 0, 2, 1)))
            second.input.send(ChainedPeerDatagram(byteArrayOf(9), byteArrayOf(192.toByte(), 0, 2, 2)))
            val received = listOf(stack.receive()!!, stack.receive()!!).associateBy { ChainedRowDatagram.decode(it.packet)!!.first }
            assertArrayEquals(byteArrayOf(8), ChainedRowDatagram.decode(received[0]!!.packet)!!.second)
            assertArrayEquals(byteArrayOf(9), ChainedRowDatagram.decode(received[1]!!.packet)!!.second)
            assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 2), received[1]!!.sourceAddress)
        } finally { stack.close() }
        assertTrue(first.closed && second.closed)
    }

    @Test
    fun `nested channel refuses exit physical output and close releases a waiting receive`() = runTest {
        val first = RecordingChannel()
        val stack = ChainedStackPeerChannel(listOf(first), StandardTestDispatcher(testScheduler))
        assertFalse(stack.send(ChainedRowDatagram.encode(1, byteArrayOf(4))))
        assertTrue(first.sent.isEmpty())
        stack.close()
        assertNull(stack.receive())
        assertTrue(first.closed)
    }

    @Test
    fun `non-nested ordered entry and retained exit reuse the exact preflight only for the exit`() {
        val entry = configuration("198.51.100.1", "10.7.0.2", "10.64.0.0/16", 1)
        val exit = configuration("exit.example", "10.8.0.2", "10.65.0.0/16", 2).withEntryHop(entry)
        val primary = address(54)
        val entryAddress = address(53)
        val resolutions = mutableListOf<Pair<String, Int>>()
        assertFalse(exit.usesNestedTransport)
        val addresses = exit.orderedHops.map { row ->
            chainedPhysicalPeerAddress(row, exit, primary) { host, port ->
                resolutions += host to port
                // A second exit lookup would fail after the already successful preflight.
                if (host == entry.endpointHostWireForm) entryAddress else null
            }
        }
        assertEquals(listOf(entryAddress, primary), addresses)
        assertSame(primary, addresses[1])
        assertEquals(listOf(entry.endpointHostWireForm to entry.endpointPort), resolutions)
        assertEquals(listOf(entry), exit.precedingHops)
    }

    @Test
    fun `same endpoint with a different peer identity cannot adopt the other profile preflight`() {
        val transport = configuration("shared.example", "10.8.0.2", "10.65.0.0/16", 2)
        val unrelated = configuration("shared.example", "10.8.0.2", "10.65.0.0/16", 3)
        val primary = address(54)
        var lookups = 0
        val resolved = chainedPhysicalPeerAddress(unrelated, transport, primary) { host, port ->
            assertEquals(transport.endpointHostWireForm, host)
            assertEquals(transport.endpointPort, port)
            lookups++
            null
        }
        assertNull(resolved)
        assertEquals(1, lookups)
    }

    @Test
    fun `nested entry keeps its physical preflight without resolving the tunneled exit`() {
        val entry = configuration("198.51.100.1", "10.7.0.2", "0.0.0.0/0", 1)
        val exit = configuration("198.51.100.2", "10.8.0.2", "10.65.0.0/16", 2).withEntryHop(entry)
        val primary = address(53)
        assertTrue(exit.usesNestedTransport)
        assertSame(primary, chainedPhysicalPeerAddress(entry, exit, primary) { _, _ ->
            error("The nested physical entry was already resolved")
        })
    }

    private fun configuration(host: String, client: String, allowed: String, key: Byte): ChainedUpstreamConfiguration =
        ChainedUpstreamConfiguration.create(
            endpointHost = host,
            endpointPort = 51820,
            peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { key }),
            clientAddress = client,
            allowedIPs = listOf(allowed),
            persistentKeepaliveSeconds = 0,
            interfaceMTU = null,
            dnsAddresses = listOf("10.64.0.1"),
        )

    private fun address(lastOctet: Byte): InetSocketAddress =
        InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, lastOctet)), 51820)

    private class RecordingChannel : ChainedUpstreamChannel {
        val input = Channel<ChainedPeerDatagram?>(2)
        val sent = mutableListOf<ByteArray>()
        var closed = false
        override fun send(datagram: ByteArray): Boolean { sent += datagram.copyOf(); return !closed }
        override suspend fun receive(): ChainedPeerDatagram? = input.receiveCatching().getOrNull()
        override fun close() { closed = true; input.close() }
    }
}
