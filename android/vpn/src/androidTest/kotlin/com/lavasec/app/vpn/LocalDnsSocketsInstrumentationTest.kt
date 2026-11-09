package com.lavasec.app.vpn

import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

/** Uses real OS sockets; also runs unchanged on the host JVM for deterministic fixture checks. */
class LocalDnsSocketsInstrumentationTest {
    private val address = InetAddress.getByName("127.0.0.1")

    @Test
    fun occupiedTcpPortReleasesUdpAndSelectsAnOwnedPair() {
        LocalDnsSockets.bind(address, tcp = true).use { occupied ->
            val candidates = mutableListOf<DatagramSocket>()
            val allocated = LocalDnsSockets.bind(address, tcp = true, createUdp = {
                (if (candidates.isEmpty()) occupied.udp else DatagramSocket(0, address))
                    .also(candidates::add)
            })
            allocated.use { pair ->
                assertTrue(candidates.size >= 2)
                assertTrue(candidates.dropLast(1).all { it.isClosed })
                assertFalse(checkNotNull(occupied.tcp).isClosed)
                assertNotEquals(occupied.tcp.localPort, pair.udp.localPort)
                assertEquals(pair.udp.localPort, checkNotNull(pair.tcp).localPort)
                pair.udp.soTimeout = 2_000
                pair.tcp.soTimeout = 2_000
                DatagramSocket().use { client ->
                    val payload = byteArrayOf(1, 2, 3)
                    client.send(DatagramPacket(payload, payload.size, address, pair.udp.localPort))
                    val received = DatagramPacket(ByteArray(3), 3)
                    pair.udp.receive(received)
                    assertArrayEquals(payload, received.data)
                }
                Socket(address, pair.tcp.localPort).use { client ->
                    pair.tcp.accept().use { accepted ->
                        accepted.soTimeout = 2_000
                        client.getOutputStream().write(42)
                        assertEquals(42, accepted.getInputStream().read())
                    }
                }
            }
            assertTrue(allocated.udp.isClosed)
            assertTrue(checkNotNull(allocated.tcp).isClosed)
        }
    }

    @Test
    fun exhaustedCollisionsFailAndCloseEveryCandidate() {
        LocalDnsSockets.bind(address, tcp = true).use { occupied ->
            val candidates = mutableListOf<DatagramSocket>()
            try {
                LocalDnsSockets.bind(address, tcp = true, attempts = 3, createUdp = {
                    (if (candidates.isEmpty()) occupied.udp
                    else DatagramSocket(checkNotNull(occupied.tcp).localPort, address))
                        .also(candidates::add)
                }).use { fail("An occupied TCP port cannot produce a DNS peer") }
                fail("The bounded allocation must fail")
            } catch (_: BindException) {
                assertEquals(3, candidates.size)
                assertTrue(candidates.all { it.isClosed })
                assertFalse(checkNotNull(occupied.tcp).isClosed)
            }
        }
    }
}
