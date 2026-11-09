package com.lavasec.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ConfigurationIdentity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** OS-backed on-device UDP/TCP peer; the selected network's DNS list is never contacted. */
@RunWith(AndroidJUnit4::class)
class PlainResolverTransportInstrumentationTest {
    @Test
    fun fixedPlainTruncationRetriesTcpOnTheExactRetainedEndpoint() = runBlocking {
        Fixture(truncated = true).use { f ->
            val answer = withTimeout(8_000) { f.resolver.resolve(f.request()) } as ResolverResult.Answer
            assertEquals(ResolverTransport.PLAIN_TCP, answer.transport)
            assertArrayEquals(response(QUERY), answer.dnsMessage)
            assertArrayEquals(QUERY, f.received.get())
            assertEquals(listOf("udp.protect", "udp.bind", "tcp.protect", "tcp.bind"), f.guards)
            assertEquals(0, f.registry.sizeForTesting)
        }
    }

    @Test
    fun fixedPlainUdpCancellationAndRetirementReleaseRealAndroidSockets() = runBlocking {
        for (retire in listOf(false, true)) {
            Fixture(silent = true).use { f ->
                val pending = async(Dispatchers.IO) { f.resolver.resolve(f.request()) }
                try {
                    assertTrue(f.queryRead.await(3, TimeUnit.SECONDS))
                    if (retire) {
                        f.registry.advanceTo(f.epoch.copy(engineGeneration = 2))
                        assertEquals(ResolverFailureReason.STALE_EPOCH,
                            (withTimeout(2_000) { pending.await() } as ResolverResult.Failure).reason)
                    } else {
                        withTimeout(2_000) { pending.cancelAndJoin() }
                        assertTrue(pending.isCancelled)
                    }
                    assertEquals(0, f.registry.sizeForTesting)
                } finally { pending.cancel() }
            }
        }
    }

    private class Fixture(private val truncated: Boolean = false, private val silent: Boolean = false) : AutoCloseable {
        private val manager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val network = requireNotNull(manager.activeNetwork)
        private val localAddress = manager.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && DeviceDnsAddressPolicy.isUsable(it) }
            ?: throw AssertionError("emulator requires a usable local underlying IPv4 address")
        private val sockets = LocalDnsSockets.bind(localAddress, tcp = true)
        private val udp = sockets.udp.apply { soTimeout = 8_000 }
        private val tcp = checkNotNull(sockets.tcp).apply { soTimeout = 8_000 }
        private val accepted = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        private val stop = CountDownLatch(1)
        val queryRead = CountDownLatch(1)
        val received = AtomicReference<ByteArray>()
        val guards = CopyOnWriteArrayList<String>()
        val epoch = EngineEpoch(1, ConfigurationIdentity(1, "plain-loopback"), "d".repeat(64), 1, 1, 1,
            PrimaryResolverIdentity("controlled-plain-loopback"), ResolverFallbackCoverage.NONE)
        private val context = NetworkContext(network, 1, listOf(InetAddress.getByName("192.0.2.99")), false)
        val registry = GenerationSocketRegistry(epoch)
        private val platform = JavaNetDeviceDnsSocketPlatform(object : OrdinaryVpnSocketProtector {
            override fun protect(socket: DatagramSocket): Boolean { guards += "udp.protect"; return true }
            override fun protect(socket: Socket): Boolean {
                assertFalse(socket.isConnected); guards += "tcp.protect"; return true
            }
        }, object : ExactNetworkSocketBinder {
            override fun bind(network: Network, socket: DatagramSocket) {
                assertEquals(this@Fixture.network, network); assertEquals("udp.protect", guards.last())
                network.bindSocket(socket); guards += "udp.bind"
            }
            override fun bind(network: Network, socket: Socket) {
                assertEquals(this@Fixture.network, network); assertEquals("tcp.protect", guards.last())
                network.bindSocket(socket); guards += "tcp.bind"
            }
        })
        val resolver = DeviceDnsTransport(platform, registry, dnsPort = udp.localPort,
            fixedDnsServers = listOf(localAddress), fixedResolverIdentity = epoch.primaryResolverIdentity)
        private val worker = Thread({
            try {
                val packet = DatagramPacket(ByteArray(4_096), 4_096)
                udp.receive(packet)
                val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                received.set(query); queryRead.countDown()
                if (silent) { stop.await(8, TimeUnit.SECONDS); return@Thread }
                val answer = response(query).apply { if (truncated) this[2] = 0x83.toByte() }
                udp.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
                if (truncated) tcp.accept().use { socket ->
                    accepted.set(socket); socket.soTimeout = 8_000
                    val input = DataInputStream(socket.getInputStream())
                    val length = input.readUnsignedShort()
                    assertTrue(length in 12..4_096)
                    val tcpQuery = ByteArray(length).also(input::readFully)
                    assertArrayEquals(query, tcpQuery)
                    DataOutputStream(socket.getOutputStream()).apply {
                        val body = response(tcpQuery); writeShort(body.size); write(body); flush()
                    }
                }
            } catch (error: Throwable) { if (!udp.isClosed) failure.set(error) }
        }, "plain-dns-local-peer").apply { isDaemon = true; start() }

        fun request() = ResolverRequest(QUERY, DnsMessage.parseQuestion(QUERY), epoch, context)
        override fun close() {
            registry.close(); stop.countDown(); sockets.close(); runCatching { accepted.get()?.close() }
            worker.join(2_000)
            assertFalse("plain DNS peer leaked", worker.isAlive)
            failure.get()?.let { throw AssertionError("local peer failed", it) }
        }
    }

    private companion object {
        val QUERY = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
        fun response(query: ByteArray) = query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() }
    }
}
