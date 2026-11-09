package com.lavasec.app.vpn.chained

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The production peer-socket adapter, exercised over a real loopback UDP socket. */
class ProtectedUnderlyingPeerSocketTest {

    @Test
    fun `send and receive round-trip over loopback`() = runBlocking {
        val peer = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val socket = DatagramSocket()
        val adapter = ProtectedUnderlyingPeerSocket(
            socket = socket,
            peerAddress = InetSocketAddress(peer.localAddress, peer.localPort),
        )
        try {
            val outbound = byteArrayOf(1, 2, 3)
            assertTrue(adapter.send(outbound))
            val receivedOutbound = DatagramPacket(ByteArray(16), 16)
            peer.receive(receivedOutbound)
            assertArrayEquals(
                outbound,
                receivedOutbound.data.copyOfRange(0, receivedOutbound.length),
            )

            val reply = byteArrayOf(9, 8, 7)
            // Reply to the observed client endpoint. An unconnected socket's localAddress is
            // the wildcard address, which is not a routable destination on every host OS.
            peer.send(DatagramPacket(reply, reply.size, receivedOutbound.address, receivedOutbound.port))
            val inbound = adapter.receive()
            assertNotNull(inbound)
            assertArrayEquals(reply, inbound!!.packet)
            assertArrayEquals(peer.localAddress.address, inbound.sourceAddress)
        } finally {
            adapter.close()
            peer.close()
        }
    }

    @Test
    fun `a send after close reports failure rather than throwing`() {
        val socket = DatagramSocket()
        val adapter = ProtectedUnderlyingPeerSocket(
            socket = socket,
            peerAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 9),
        )
        adapter.close()

        assertFalse(adapter.send(byteArrayOf(1)))
    }
}
