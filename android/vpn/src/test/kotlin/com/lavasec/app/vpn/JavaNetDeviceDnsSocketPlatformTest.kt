package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.ResolverTransport
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JavaNetDeviceDnsSocketPlatformTest {
    @Test
    fun `UDP stays unconnected and preserves loopback source through the validator port seam`() {
        val events = mutableListOf<String>()
        val network = network(81)
        val loopback = ipv4Loopback()
        WorkerPool().use { workers ->
            DatagramSocket(InetSocketAddress(loopback, 0)).use { server ->
                val ports = RecordingPorts(events, network)
                val platform = JavaNetDeviceDnsSocketPlatform(ports, ports)
                platform.createUdp().use { socket ->
                    assertTrue(platform.protect(socket))
                    platform.bind(socket, network)

                    val serverWork = workers.submit {
                        val packet = DatagramPacket(ByteArray(4_096), 4_096)
                        server.receive(packet)
                        assertArrayEquals(QUERY, packet.data.copyOf(packet.length))
                        server.send(
                            DatagramPacket(RESPONSE, RESPONSE.size, packet.address, packet.port),
                        )
                    }

                    events += "udp.send"
                    socket.send(QUERY, loopback, server.localPort, 1_000)
                    events += "udp.receive"
                    val received = socket.receive(4_097, 1_000)
                    serverWork.get(2, TimeUnit.SECONDS)

                    assertEquals(RESPONSE.size, received.body.size)
                    assertArrayEquals(RESPONSE, received.body)
                    assertArrayEquals(loopback.address, received.source.address.address)
                    assertEquals(server.localPort, received.source.port)
                    assertTrue(
                        ResponseValidation.validate(
                            query = QUERY,
                            response = received.body,
                            transport = ResolverTransport.DEVICE_UDP,
                            datagramSource = received.source,
                            expectedServer = loopback,
                            expectedPort = server.localPort,
                        ) is ResponseValidationResult.Valid,
                    )
                    assertEquals(
                        listOf("udp.protect", "udp.bind", "udp.send", "udp.receive"),
                        events,
                    )
                    assertFalse(ports.protectedUdpSocket!!.isConnected)
                }
            }
        }
    }

    @Test
    fun `TCP materializes before protection binds the exact network and exchanges exact frames`() {
        val events = mutableListOf<String>()
        val network = network(82)
        val loopback = ipv4Loopback()
        val firstPrefixSent = CountDownLatch(1)
        val releaseSecondPrefix = CountDownLatch(1)
        val firstBodyPartSent = CountDownLatch(1)
        val releaseRestOfBody = CountDownLatch(1)
        val lengthReadEntered = CountDownLatch(1)
        val bodyReadEntered = CountDownLatch(1)
        val frame =
            byteArrayOf((QUERY.size ushr 8).toByte(), QUERY.size.toByte()) + QUERY
        WorkerPool().use { workers ->
            ServerSocket(0, 1, loopback).use { server ->
                val ports = RecordingPorts(events, network)
                val platform = JavaNetDeviceDnsSocketPlatform(ports, ports)
                val serverWork = workers.submit {
                    server.accept().use { accepted ->
                        assertArrayEquals(frame, readExactly(accepted, frame.size))
                        accepted.getOutputStream().apply {
                            write(byteArrayOf(0))
                            flush()
                            firstPrefixSent.countDown()
                            assertTrue(releaseSecondPrefix.await(2, TimeUnit.SECONDS))
                            write(byteArrayOf(RESPONSE.size.toByte()))
                            flush()
                            write(RESPONSE, 0, 7)
                            flush()
                            firstBodyPartSent.countDown()
                            assertTrue(releaseRestOfBody.await(2, TimeUnit.SECONDS))
                            write(RESPONSE, 7, RESPONSE.size - 7)
                            flush()
                        }
                    }
                }

                try {
                    platform.createTcp().use { socket ->
                        assertTrue(platform.protect(socket))
                        platform.bind(socket, network)
                        events += "tcp.connect"
                        socket.connect(loopback, server.localPort, 1_000)
                        events += "tcp.write"
                        socket.writeFrame(frame, 1_000)

                        assertTrue(firstPrefixSent.await(2, TimeUnit.SECONDS))
                        val lengthWork = workers.submit {
                            lengthReadEntered.countDown()
                            socket.readLength(713)
                        }
                        assertTrue(lengthReadEntered.await(2, TimeUnit.SECONDS))
                        assertBlocked(lengthWork)
                        assertEquals(713, ports.protectedTcpSocket!!.soTimeout)
                        releaseSecondPrefix.countDown()
                        assertEquals(RESPONSE.size, lengthWork.get(2, TimeUnit.SECONDS))

                        assertTrue(firstBodyPartSent.await(2, TimeUnit.SECONDS))
                        val bodyWork = workers.submit {
                            bodyReadEntered.countDown()
                            socket.readBody(RESPONSE.size, 947)
                        }
                        assertTrue(bodyReadEntered.await(2, TimeUnit.SECONDS))
                        assertBlocked(bodyWork)
                        assertEquals(947, ports.protectedTcpSocket!!.soTimeout)
                        releaseRestOfBody.countDown()
                        assertArrayEquals(RESPONSE, bodyWork.get(2, TimeUnit.SECONDS))
                        serverWork.get(2, TimeUnit.SECONDS)

                        assertEquals(
                            listOf("tcp.protect", "tcp.bind", "tcp.connect", "tcp.write"),
                            events,
                        )
                        assertTrue(ports.tcpWasWildcardBoundAtProtect)
                        assertFalse(ports.tcpWasConnectedAtProtect)
                    }
                } finally {
                    releaseSecondPrefix.countDown()
                    releaseRestOfBody.countDown()
                }
            }
        }
    }

    @Test
    fun `TCP connect forwards the exact timeout after wildcard materialization`() {
        val delegate = RecordingConnectSocket()
        val platform = JavaNetDeviceDnsSocketPlatform(
            AcceptingProtector,
            NoOpBinder,
            tcpSocketFactory = { delegate },
        )

        platform.createTcp().use { socket ->
            socket.connect(ipv4Loopback(), 5_300, 619)
        }

        assertEquals(InetSocketAddress(0), delegate.boundEndpoint)
        assertEquals(InetSocketAddress(ipv4Loopback(), 5_300), delegate.connectedEndpoint)
        assertEquals(619, delegate.connectTimeoutMillis)
    }

    @Test
    fun `TCP creation closes its raw socket when wildcard materialization fails`() {
        val bindFailure = IOException("wildcard bind")
        val delegate = ThrowingBindSocket(bindFailure)
        val platform = JavaNetDeviceDnsSocketPlatform(
            AcceptingProtector,
            NoOpBinder,
            tcpSocketFactory = { delegate },
        )

        val observed = runCatching { platform.createTcp() }.exceptionOrNull()

        assertSame(bindFailure, observed)
        assertEquals(1, delegate.closeCount)
    }

    @Test
    fun `protection false and protection or binding throws propagate before traffic`() {
        val network = network(83)
        val protectedSockets = mutableListOf<DatagramSocket>()
        val falseProtector = object : OrdinaryVpnSocketProtector {
            override fun protect(socket: DatagramSocket): Boolean {
                protectedSockets += socket
                return false
            }

            override fun protect(socket: Socket): Boolean = false
        }
        val falsePlatform = JavaNetDeviceDnsSocketPlatform(falseProtector, NoOpBinder)
        falsePlatform.createUdp().use { socket ->
            assertFalse(falsePlatform.protect(socket))
            assertFalse(protectedSockets.single().isConnected)
        }

        val protectFailure = IOException("protect")
        val throwingProtector = object : OrdinaryVpnSocketProtector {
            override fun protect(socket: DatagramSocket): Boolean = throw protectFailure

            override fun protect(socket: Socket): Boolean = throw protectFailure
        }
        val protectPlatform = JavaNetDeviceDnsSocketPlatform(throwingProtector, NoOpBinder)
        protectPlatform.createUdp().use { socket ->
            assertSame(protectFailure, runCatching { protectPlatform.protect(socket) }.exceptionOrNull())
        }

        val bindFailure = IOException("bind")
        var protectedSocket: DatagramSocket? = null
        val recordingProtector = object : OrdinaryVpnSocketProtector {
            override fun protect(socket: DatagramSocket): Boolean {
                protectedSocket = socket
                return true
            }

            override fun protect(socket: Socket): Boolean = true
        }
        val throwingBinder = object : ExactNetworkSocketBinder {
            override fun bind(network: Network, socket: DatagramSocket) {
                assertSame(protectedSocket, socket)
                throw bindFailure
            }

            override fun bind(network: Network, socket: Socket) = throw bindFailure
        }
        val bindPlatform = JavaNetDeviceDnsSocketPlatform(recordingProtector, throwingBinder)
        bindPlatform.createUdp().use { socket ->
            assertTrue(bindPlatform.protect(socket))
            assertSame(bindFailure, runCatching { bindPlatform.bind(socket, network) }.exceptionOrNull())
            assertFalse(requireNotNull(protectedSocket).isConnected)
        }
    }

    @Test
    fun `TCP exact reads return null on partial prefix and partial body EOF`() {
        val loopback = ipv4Loopback()
        WorkerPool().use { workers ->
            ServerSocket(0, 1, loopback).use { server ->
                val serverWork = workers.submit {
                    server.accept().use { accepted ->
                        accepted.getOutputStream().write(byteArrayOf(0))
                    }
                }
                val socket = JavaNetDeviceDnsSocketPlatform(
                    AcceptingProtector,
                    NoOpBinder,
                ).createTcp()
                try {
                    socket.connect(loopback, server.localPort, 1_000)
                    assertNull(socket.readLength(1_000))
                    serverWork.get(2, TimeUnit.SECONDS)
                } finally {
                    socket.close()
                }
            }

            ServerSocket(0, 1, loopback).use { server ->
                val serverWork = workers.submit {
                    server.accept().use { accepted ->
                        accepted.getOutputStream().write(byteArrayOf(1, 2))
                    }
                }
                val socket = JavaNetDeviceDnsSocketPlatform(
                    AcceptingProtector,
                    NoOpBinder,
                ).createTcp()
                try {
                    socket.connect(loopback, server.localPort, 1_000)
                    assertNull(socket.readBody(4, 1_000))
                    serverWork.get(2, TimeUnit.SECONDS)
                } finally {
                    socket.close()
                }
            }
        }
    }

    @Test
    fun `closing UDP adapter unblocks a blocked receive`() {
        val platform = JavaNetDeviceDnsSocketPlatform(AcceptingProtector, NoOpBinder)
        val socket = platform.createUdp()
        val receiveEntered = CountDownLatch(1)
        WorkerPool().use { workers ->
            val receiveWork = workers.submit {
                receiveEntered.countDown()
                runCatching { socket.receive(4_097, 30_000) }.exceptionOrNull()
            }
            try {
                assertTrue(receiveEntered.await(2, TimeUnit.SECONDS))
                assertBlocked(receiveWork)
                socket.close()
                assertTrue(receiveWork.get(2, TimeUnit.SECONDS) != null)
            } finally {
                socket.close()
            }
        }
    }

    @Test
    fun `closing TCP adapter unblocks a blocked body read`() {
        val loopback = ipv4Loopback()
        val accepted = CountDownLatch(1)
        val bodyReadEntered = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        WorkerPool().use { workers ->
            ServerSocket(0, 1, loopback).use { server ->
                workers.submit {
                    server.accept().use {
                        accepted.countDown()
                        releaseServer.await(2, TimeUnit.SECONDS)
                    }
                }
                val socket = JavaNetDeviceDnsSocketPlatform(
                    AcceptingProtector,
                    NoOpBinder,
                ).createTcp()
                try {
                    socket.connect(loopback, server.localPort, 1_000)
                    assertTrue(accepted.await(2, TimeUnit.SECONDS))
                    val readWork = workers.submit {
                        bodyReadEntered.countDown()
                        runCatching { socket.readBody(32, 30_000) }.exceptionOrNull()
                    }
                    assertTrue(bodyReadEntered.await(2, TimeUnit.SECONDS))
                    assertBlocked(readWork)
                    socket.close()
                    assertTrue(readWork.get(2, TimeUnit.SECONDS) != null)
                } finally {
                    socket.close()
                    releaseServer.countDown()
                    server.close()
                }
            }
        }
    }

    private class RecordingPorts(
        private val events: MutableList<String>,
        private val expectedNetwork: Network,
    ) : OrdinaryVpnSocketProtector, ExactNetworkSocketBinder {
        var protectedUdpSocket: DatagramSocket? = null
        var protectedTcpSocket: Socket? = null
        var tcpWasWildcardBoundAtProtect = false
        var tcpWasConnectedAtProtect = true

        override fun protect(socket: DatagramSocket): Boolean {
            events += "udp.protect"
            assertFalse(socket.isConnected)
            protectedUdpSocket = socket
            return true
        }

        override fun protect(socket: Socket): Boolean {
            events += "tcp.protect"
            protectedTcpSocket = socket
            tcpWasWildcardBoundAtProtect =
                socket.isBound && socket.localAddress.isAnyLocalAddress && socket.localPort > 0
            tcpWasConnectedAtProtect = socket.isConnected
            return true
        }

        override fun bind(network: Network, socket: DatagramSocket) {
            events += "udp.bind"
            assertSame(expectedNetwork, network)
            assertSame(protectedUdpSocket, socket)
            assertFalse(socket.isConnected)
        }

        override fun bind(network: Network, socket: Socket) {
            events += "tcp.bind"
            assertSame(expectedNetwork, network)
            assertSame(protectedTcpSocket, socket)
            assertTrue(socket.isBound)
            assertFalse(socket.isConnected)
        }
    }

    private class RecordingConnectSocket : Socket() {
        var boundEndpoint: SocketAddress? = null
        var connectedEndpoint: SocketAddress? = null
        var connectTimeoutMillis: Int? = null

        override fun bind(bindpoint: SocketAddress) {
            boundEndpoint = bindpoint
        }

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            connectedEndpoint = endpoint
            connectTimeoutMillis = timeout
        }
    }

    private class ThrowingBindSocket(
        private val failure: IOException,
    ) : Socket() {
        var closeCount = 0

        override fun bind(bindpoint: SocketAddress) = throw failure

        override fun close() {
            closeCount += 1
        }
    }

    private class WorkerPool : AutoCloseable {
        private val threads = Collections.synchronizedList(mutableListOf<Thread>())
        private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "lavasec-device-dns-loopback-test").also { threads += it }
        }

        fun <T> submit(block: () -> T): Future<T> = executor.submit(Callable(block))

        override fun close() {
            executor.shutdownNow()
            assertTrue("loopback workers did not terminate", executor.awaitTermination(2, TimeUnit.SECONDS))
            threads.forEach { it.join(250) }
            assertTrue("loopback worker thread remains live", threads.none { it.isAlive })
        }
    }

    private object AcceptingProtector : OrdinaryVpnSocketProtector {
        override fun protect(socket: DatagramSocket): Boolean = true

        override fun protect(socket: Socket): Boolean = true
    }

    private object NoOpBinder : ExactNetworkSocketBinder {
        override fun bind(network: Network, socket: DatagramSocket) = Unit

        override fun bind(network: Network, socket: Socket) = Unit
    }

    private companion object {
        val QUERY = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 1, 0, 1,
        )
        val RESPONSE = QUERY.copyOf().also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
        }

        fun assertBlocked(work: Future<*>) {
            try {
                work.get(100, TimeUnit.MILLISECONDS)
                fail("operation completed before close or remaining bytes")
            } catch (_: TimeoutException) {
                // Expected: the entered operation remains blocked until the test releases it.
            }
        }

        fun readExactly(socket: Socket, length: Int): ByteArray {
            val body = ByteArray(length)
            var offset = 0
            while (offset < body.size) {
                val read = socket.getInputStream().read(body, offset, body.size - offset)
                if (read < 0) fail("early EOF after $offset of $length bytes")
                offset += read
            }
            return body
        }

        fun ipv4Loopback(): InetAddress = InetAddress.getByName("127.0.0.1")

        fun network(handle: Int): Network =
            Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .newInstance(handle)
    }
}
