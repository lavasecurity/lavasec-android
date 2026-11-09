package com.lavasec.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.PrimaryResolverOutcome
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.platform.ConfigurationIdentity
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production plan/coordinator, Device transport, TLS transport and exact health owner share
 * actual wire evidence. Peers bind the captured network's usable emulator IPv4. Protection
 * authorization and trust for the controlled certificate are explicit fixture seams; actual
 * binding, source tuples, DNS/HTTP framing, TLS hostname verification and replies remain real.
 * This does not activate a VPN service, grant Plus, qualify a live provider, or claim a TUN receipt.
 * The injected clock offset expires endpoint backoff between real wire failures; it never sets a
 * wedge verdict. The existing health owner derives that verdict solely from completed attempts.
 */
@RunWith(AndroidJUnit4::class)
class DeviceResolverReconnectInstrumentationTest {
    @Test
    fun mismatchingDeviceWireIdsStopBeforeEncryptedFallback(): Unit = runBlocking {
        Fixture().use { f ->
            f.device.rcode.set(MISMATCHED_IDS)
            val rejected = f.resolve(1) as ResolverResult.Failure
            assertEquals(ResolverFailureReason.MISMATCH_LIMIT, rejected.reason)
            assertFalse(rejected.peerAnswered)
            assertFalse(rejected.local)
            assertEquals(PrimaryResolverOutcome.FAILED, rejected.resolverQueryEvidence?.primaryOutcome)
            assertEquals(ResolverFailureReason.MISMATCH_LIMIT, rejected.resolverQueryEvidence?.primaryFailureReason)
            assertEquals(ProtectionFallback.NONE, rejected.resolverQueryEvidence?.successfulFallback)
            assertEquals(0, f.encrypted.requestCount)
            assertTrue(f.device.tcpQueries.isEmpty())
            assertEquals(1, f.device.queries.size)
            assertArrayEquals(query(1), f.device.queries.single().body)
            assertEquals(f.address, f.device.queries.single().source.address)
            assertTrue(f.reconnects.isEmpty())
            assertFalse(f.health.deviceResolverReconnectEpisodeIsActive(f.current.epoch))
            f.assertProtectionAndBinding()
            assertEquals(0, f.current.registry.sizeForTesting)
        }
    }

    @Test
    fun deviceRejectionUsesRealFailureQualifiedReconnectThenTlsFallbackAndPrimaryRecovery(): Unit = runBlocking {
        Fixture().use { f ->
            // A cold, validated rejection remains the selected Device resolver's own answer.
            f.device.rcode.set(5)
            val cold = f.resolve(1) as ResolverResult.Answer
            assertEquals(ResolverTransport.DEVICE_UDP, cold.transport)
            assertArrayEquals(response(query(1), 5), cold.dnsMessage)
            assertEquals(0, f.encrypted.requestCount)
            assertFalse(f.health.deviceResolverReconnectEpisodeIsActive(f.current.epoch))

            // Both legs really fail: a source-matched TC reply enters same-server TCP, whose
            // peer reads the complete query then closes without a frame, followed by verified
            // TLS HTTP 503. Wrong wire IDs deliberately cannot authorize the second resolver.
            f.device.rcode.set(TRUNCATED_THEN_TCP_EOF)
            f.httpStatus.set(503)
            for (id in 2L..4L) {
                val failed = f.resolve(id) as ResolverResult.Failure
                assertEquals(ResolverFailureReason.HTTP_STATUS, failed.reason)
                assertEquals(PrimaryResolverOutcome.FAILED, failed.resolverQueryEvidence?.primaryOutcome)
                assertEquals(ResolverFailureReason.RECEIVE_FAILED, failed.resolverQueryEvidence?.primaryFailureReason)
                assertEquals(ProtectionFallback.NONE, failed.resolverQueryEvidence?.successfulFallback)
                f.assertPost(id)
                f.expireEndpointBackoff()
            }
            assertEquals(listOf(1L), f.reconnects)
            val predecessor = f.current
            assertTrue(f.health.deviceResolverReconnectEpisodeIsActive(predecessor.epoch))
            f.adoptReconnect()
            assertFalse(f.health.deviceResolverReconnectEpisodeIsActive(predecessor.epoch))
            assertTrue(f.health.deviceResolverReconnectEpisodeIsActive(f.current.epoch))
            assertEquals(0, f.health.current().health.consecutiveResolverFailures)

            f.httpStatus.set(200)
            for ((id, rcode) in listOf(5L to 2, 6L to 5)) {
                f.device.rcode.set(rcode)
                val carried = f.resolve(id) as ResolverResult.Answer
                assertEquals(ResolverTransport.MULLVAD_DOH, carried.transport)
                assertArrayEquals(response(query(id), 0), carried.dnsMessage)
                assertEquals(PrimaryResolverOutcome.REJECTED, carried.resolverQueryEvidence?.primaryOutcome)
                assertEquals(null, carried.resolverQueryEvidence?.primaryFailureReason)
                assertEquals(ProtectionFallback.ENCRYPTED_DNS, carried.resolverQueryEvidence?.successfulFallback)
                f.assertPost(id)
                assertTrue(f.health.deviceResolverReconnectEpisodeIsActive(f.current.epoch))
            }

            // A genuine authoritative Device NXDOMAIN proves primary recovery. A subsequent
            // per-domain refusal must stay on Device and cannot re-enter the encrypted leg.
            f.device.rcode.set(3)
            val recovered = f.resolve(7) as ResolverResult.Answer
            assertEquals(ResolverTransport.DEVICE_UDP, recovered.transport)
            assertArrayEquals(response(query(7), 3), recovered.dnsMessage)
            assertFalse(f.health.deviceResolverReconnectEpisodeIsActive(f.current.epoch))
            f.device.rcode.set(5)
            val authoritative = f.resolve(8) as ResolverResult.Answer
            assertEquals(ResolverTransport.DEVICE_UDP, authoritative.transport)
            assertArrayEquals(response(query(8), 5), authoritative.dnsMessage)
            assertEquals(5, f.encrypted.requestCount)
            assertEquals(8, f.device.queries.size)
            assertTrue(f.device.queries.all { it.source.address == f.address && it.source.port > 0 })
            assertEquals((1L..8L).map(::query).map { it.toList() }, f.device.queries.map { it.body.toList() })
            assertEquals((2L..4L).map(::query).map { it.toList() }, f.device.tcpQueries.map { it.body.toList() })
            assertTrue(f.device.tcpQueries.all { it.source.address == f.address && it.source.port > 0 })
            f.assertProtectionAndBinding()

            val before = f.device.queries.size
            f.current.registry.close()
            val stale = f.current.bundle.resolve(f.request(9)) as ResolverResult.Failure
            assertEquals(ResolverFailureReason.STALE_EPOCH, stale.reason)
            assertEquals(before, f.device.queries.size)
            assertEquals(5, f.encrypted.requestCount)
            f.health.recordConnectionChanged(f.current.epoch.engineGeneration, false)
            assertFalse(f.health.deviceResolverReconnectEpisodeIsActive(f.current.epoch))
        }
    }

    private class Fixture : AutoCloseable {
        private val connectivity = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val network = connectivity.activeNetwork ?: error("emulator requires a captured network")
        val address = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && DeviceDnsAddressPolicy.isUsable(it) }
            ?: error("emulator requires a usable local underlying IPv4")
        private val context = NetworkContext(network, 1, listOf(address),
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
        val device = LocalDns(address)
        val httpStatus = AtomicInteger(200)
        private val certificate = HeldCertificate.Builder().commonName(HOST).addSubjectAlternativeName(HOST).build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val encrypted = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (httpStatus.get() != 200) MockResponse().setResponseCode(httpStatus.get())
                    else MockResponse().setHeader("Content-Type", "application/dns-message")
                        .setBody(Buffer().write(response(request.body.clone().readByteArray(), 0)))
            }
            start(address, 0)
        }
        private val endpoint = "https://$HOST:${encrypted.port}/dns-query"
        private val selection = ResolverSelection(listOf(
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH,
                customDohUrl = endpoint)),
        ))
        private val plan = (ResolverPlanFactory.build(checkNotNull(selection.toFilterResolverPlan()).configuration, context)
            as ResolverPlanBuildResult.Ready).plan
        private val offset = AtomicLong()
        private val clock = MonotonicClock { System.nanoTime() + offset.get() }
        val reconnects = CopyOnWriteArrayList<Long>()
        val health = ExactSessionHealthOwner(1, { System.currentTimeMillis() + TimeUnit.NANOSECONDS.toMillis(offset.get()) },
            {}, reconnects::add)
        private val guards = CopyOnWriteArrayList<String>()
        private val sockets = JavaNetDeviceDnsSocketPlatform(object : OrdinaryVpnSocketProtector {
            override fun protect(socket: DatagramSocket): Boolean { guards += "udp-protect"; return true }
            override fun protect(socket: Socket): Boolean { guards += "tcp-protect"; return true }
        }, object : ExactNetworkSocketBinder {
            override fun bind(captured: Network, socket: DatagramSocket) {
                assertEquals(network, captured); assertEquals("udp-protect", guards.last())
                captured.bindSocket(socket); guards += "udp-bind"
            }
            override fun bind(captured: Network, socket: Socket) {
                assertEquals(network, captured); assertEquals("tcp-protect", guards.last())
                captured.bindSocket(socket); guards += "tcp-bind"
            }
        })
        private val factory = ProductionReadyDnsResolverFactory(
            deviceRouteFactory = ReadyDnsDeviceRouteFactory { _, _, _, registry ->
                DeviceDnsTransport(sockets, registry, clock = clock, dnsPort = device.port)
            },
            mullvadRuntimeFactory = ReadyDnsMullvadRuntimeFactory { epoch, exactContext, leaf, registry ->
                val runtime = MullvadDohRuntime(epoch, exactContext, registry,
                    DohSocketProtector { guards += "tcp-protect"; true },
                    DohSocketBinder { socket, captured ->
                        assertEquals(network, captured); assertEquals("tcp-protect", guards.last())
                        captured.bindSocket(socket); guards += "tcp-bind"
                    },
                    endpoint = checkNotNull(leaf.dohEndpointUrl).toHttpUrl(),
                    dns = CatalogDohBootstrap(HOST, listOf(address)),
                    tls = MullvadDohTlsConfiguration(clientTls.sslSocketFactory(), clientTls.trustManager))
                object : ReadyDnsMullvadRuntime {
                    override val resolver = runtime.resolver
                    override fun close() = runtime.close()
                }
            },
            coordinatorFactory = ReadyDnsCoordinatorFactory { epoch, _, exactPlan, registry, deviceRoute, encryptedRoute, wedge ->
                ResolverCoordinator(epoch, exactPlan, registry, deviceRoute, encryptedRoute, wedge, clock = clock)
            },
            wedgeEvidence = DeviceResolverWedgeEvidence(health::deviceResolverReconnectEpisodeIsActive),
        )
        var current = engine(1)
            private set
        init { install(current) }

        suspend fun resolve(id: Long): ResolverResult {
            val result = withTimeout(8_000) { current.bundle.resolve(request(id)) }
            health.recordBatch(current.epoch.engineGeneration, result.healthEvents, result.resolverQueryEvidence)
            return result
        }
        fun request(id: Long): ResolverRequest {
            val bytes = query(id)
            return ResolverRequest(bytes, DnsMessage.parseQuestion(bytes), current.epoch, context, id,
                absoluteDeviceDeadlineNanos = clock.nowNanos() + TimeUnit.SECONDS.toNanos(2))
        }
        fun expireEndpointBackoff() { offset.addAndGet(TimeUnit.SECONDS.toNanos(31)) }
        fun adoptReconnect() {
            assertEquals(listOf(current.epoch.engineGeneration), reconnects)
            current.close()
            current = engine(current.epoch.engineGeneration + 1)
            install(current)
        }
        private fun install(engine: Engine) = health.installEngine(VpnHealthEngineIdentity(
            engine.epoch.sessionGeneration, engine.epoch.networkGeneration, engine.epoch.engineGeneration,
            engine.epoch.primaryResolverIdentity, engine.epoch.fallbackCoverage), engine.epoch)
        private fun engine(generation: Long): Engine {
            val epoch = EngineEpoch(1, ConfigurationIdentity(1, "controlled-resolver-reconnect"), "d".repeat(64),
                1, 1, generation, plan.primaryResolverIdentity, plan.fallbackCoverage)
            val registry = GenerationSocketRegistry(epoch)
            return Engine(epoch, registry, factory.create(epoch, context, plan, registry))
        }
        fun assertPost(id: Long) {
            val posted = encrypted.takeRequest(2, TimeUnit.SECONDS) ?: error("controlled encrypted peer received no request")
            assertEquals("POST", posted.method)
            assertEquals("/dns-query", posted.path)
            val authority = posted.getHeader(":authority")
            if (authority != null) {
                assertEquals("https", posted.getHeader(":scheme"))
                assertEquals("$HOST:${encrypted.port}", authority)
            } else assertEquals("$HOST:${encrypted.port}", posted.getHeader("Host"))
            assertEquals("application/dns-message", posted.getHeader("Accept"))
            assertEquals("application/dns-message", posted.getHeader("Content-Type"))
            assertEquals(listOf(certificate.certificate), checkNotNull(posted.handshake).localCertificates)
            assertArrayEquals(query(id).apply { this[0] = 0; this[1] = 0 }, posted.body.readByteArray())
        }
        fun assertProtectionAndBinding() {
            assertTrue(guards.isNotEmpty())
            for (pair in guards.chunked(2)) {
                assertTrue(pair == listOf("udp-protect", "udp-bind") || pair == listOf("tcp-protect", "tcp-bind"))
            }
        }
        override fun close() {
            try { current.close() } finally {
                try { device.close() } finally { encrypted.shutdown() }
            }
            assertEquals(0, current.registry.sizeForTesting)
        }
    }

    private class Engine(val epoch: EngineEpoch, val registry: GenerationSocketRegistry,
        val bundle: OwnedReadyDnsResolver) : AutoCloseable {
        override fun close() { try { registry.close() } finally { bundle.close() } }
    }

    private class LocalDns(address: InetAddress) : AutoCloseable {
        private val sockets = LocalDnsSockets.bind(address, tcp = true)
        private val socket = sockets.udp.apply { soTimeout = 500 }
        private val tcp = checkNotNull(sockets.tcp).apply { soTimeout = 2_000 }
        private val accepted = AtomicReference<Socket?>()
        val port = socket.localPort
        val rcode = AtomicInteger(0)
        data class Query(val body: ByteArray, val source: InetSocketAddress)
        val queries = CopyOnWriteArrayList<Query>()
        val tcpQueries = CopyOnWriteArrayList<Query>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread({
            while (!socket.isClosed) {
                try {
                    val packet = DatagramPacket(ByteArray(4_096), 4_096)
                    socket.receive(packet)
                    val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    queries += Query(bytes, InetSocketAddress(packet.address, packet.port))
                    val code = rcode.get()
                    val reply = response(bytes, code.coerceAtLeast(0))
                    if (code == MISMATCHED_IDS) reply[0] = (reply[0].toInt() xor 0x80).toByte()
                    if (code == TRUNCATED_THEN_TCP_EOF) reply[2] = 0x83.toByte()
                    repeat(if (code == MISMATCHED_IDS) 8 else 1) {
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                    if (code == TRUNCATED_THEN_TCP_EOF) tcp.accept().use { connection ->
                        accepted.set(connection)
                        connection.soTimeout = 2_000
                        val input = DataInputStream(connection.inputStream)
                        val length = input.readUnsignedShort()
                        assertTrue(length in 12..4_096)
                        val tcpQuery = ByteArray(length).also(input::readFully)
                        tcpQueries += Query(tcpQuery, connection.remoteSocketAddress as InetSocketAddress)
                        assertArrayEquals(bytes, tcpQuery)
                        // EOF after a complete request is a real receive failure, without a
                        // fabricated timeout, local refusal, mismatched answer, or health verdict.
                    }
                } catch (_: SocketTimeoutException) {
                    // Bounded receive only makes close observable; it creates no health evidence.
                } catch (error: Throwable) {
                    if (!socket.isClosed) failure.set(error)
                    return@Thread
                }
            }
        }, "emulator-device-reconnect-peer").apply { isDaemon = true; start() }
        override fun close() {
            sockets.close(); runCatching { accepted.get()?.close() }; worker.join(2_000)
            assertFalse("controlled Device peer leaked", worker.isAlive)
            failure.get()?.let { throw AssertionError("controlled Device peer failed", it) }
        }
    }

    private companion object {
        const val HOST = "resolver.reconnect.example"
        const val MISMATCHED_IDS = -1
        const val TRUNCATED_THEN_TCP_EOF = -2
        fun query(id: Long): ByteArray = ByteArrayOutputStream().apply {
            write(byteArrayOf((id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (label in listOf("reconnect", "invalid")) { write(label.length); write(label.toByteArray(Charsets.US_ASCII)) }
            write(byteArrayOf(0, 0, 1, 0, 1))
        }.toByteArray()
        fun response(query: ByteArray, rcode: Int) = query.copyOf().apply {
            this[2] = 0x81.toByte(); this[3] = (0x80 or rcode).toByte()
        }
    }
}
