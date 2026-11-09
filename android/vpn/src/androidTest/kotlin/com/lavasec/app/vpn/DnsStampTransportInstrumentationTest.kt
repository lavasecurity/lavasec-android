package com.lavasec.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.platform.ConfigurationIdentity
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Raw stamps feed the actual plan factory, encrypted runtime selection, coordinator and Android
 * DoH/DoT transports. Peers bind
 * the captured network's usable local IPv4; the stamp address policy is not bypassed. Only fixture
 * certificate trust and socket-protection authorization are injected. Network binding, TLS name
 * verification, HTTP/2 framing, TCP DNS frames and answers are real. This claims neither Internet
 * connectivity nor VpnService authorization or a device TUN route. Extra bootstrap literals are
 * retained/order assertions; only the first, locally owned bootstrap peer is contacted.
 */
@RunWith(AndroidJUnit4::class)
class DnsStampTransportInstrumentationTest {
    @Test
    fun rawDohStampsPreserveTlsAuthorityPathAndSeparateSecondaryBootstrap(): Unit = runBlocking {
        for (failPrimary in listOf(false, true)) {
            StampFixture(doh = true, failPrimary = failPrimary).use { fixture ->
                val result = withTimeout(8_000) { fixture.resolve() }
                assertEquals(ResolverTransport.MULLVAD_DOH, result.transport)
                assertArrayEquals(answer(QUERY), result.dnsMessage)
                fixture.assertDohRequest(fixture.primary as DohPeer)
                if (failPrimary) fixture.assertDohRequest(fixture.secondary as DohPeer)
                else assertEquals(0, (fixture.secondary as DohPeer).server.requestCount)
                val expectedLookups = listOf(fixture.primary.hostname) +
                    if (failPrimary) listOf(fixture.secondary.hostname) else emptyList()
                assertEquals(expectedLookups, fixture.bootstrapLookups.map { it.first })
                assertEquals(fixture.primaryBootstrap, fixture.bootstrapLookups[0].second)
                if (failPrimary) assertEquals(fixture.secondaryBootstrap, fixture.bootstrapLookups[1].second)
                fixture.assertPooledOwnership(failPrimary)
                fixture.assertRuntimeDrained(failPrimary)
            }
        }
    }

    @Test
    fun retiringRawDohStampEpochClosesReusedSocketAndRefusesPendingAnswer(): Unit = runBlocking {
        StampFixture(doh = true, failPrimary = false).use { fixture ->
            assertArrayEquals(answer(QUERY), withTimeout(8_000) { fixture.resolve() }.dnsMessage)
            val primary = fixture.primary as DohPeer
            fixture.assertDohRequest(primary)
            fixture.assertPooledOwnership(failedPrimary = false)
            primary.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async(Dispatchers.IO) { fixture.resolveResult() }
            try {
                fixture.assertDohRequest(primary)
                // A second exchange uses the epoch-owned pooled connection, without a new
                // unprotected socket or an unrelated lookup. Retirement must close that socket.
                assertEquals(listOf("protect", "bind"), fixture.guards)
                assertEquals(listOf(fixture.primary.hostname), fixture.bootstrapLookups.map { it.first })
                fixture.retireEpoch()
                assertEquals(ResolverFailureReason.STALE_EPOCH,
                    (withTimeout(2_000) { pending.await() } as ResolverResult.Failure).reason)
                assertEquals(ResolverFailureReason.STALE_EPOCH,
                    (withTimeout(2_000) { fixture.resolveResult() } as ResolverResult.Failure).reason)
                assertEquals(2, primary.server.requestCount)
                assertEquals(0, (fixture.secondary as DohPeer).server.requestCount)
                fixture.assertRuntimeDrained(failedPrimary = false)
            } finally {
                withTimeout(2_000) { pending.cancelAndJoin() }
            }
        }
    }

    @Test
    fun rawDotStampsPreserveTlsHostnameAndServeSecondaryAfterRealPrimaryFrameFailure(): Unit = runBlocking {
        for (failPrimary in listOf(false, true)) {
            StampFixture(doh = false, failPrimary = failPrimary).use { fixture ->
                val result = withTimeout(8_000) { fixture.resolve() }
                assertEquals(ResolverTransport.DOT, result.transport)
                assertArrayEquals(answer(QUERY), result.dnsMessage)
                val primary = fixture.primary as DotPeer
                val secondary = fixture.secondary as DotPeer
                primary.assertHealthy()
                secondary.assertHealthy()
                assertArrayEquals(canonical(QUERY), primary.received.get())
                if (failPrimary) assertArrayEquals(canonical(QUERY), secondary.received.get())
                else assertNull(secondary.received.get())
                fixture.assertRuntimeDrained(failPrimary)
            }
        }
    }

    private class StampFixture(private val doh: Boolean, failPrimary: Boolean) : AutoCloseable {
        private val connectivity = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val network = connectivity.activeNetwork ?: error("emulator requires a captured network")
        private val address = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && DeviceDnsAddressPolicy.isUsable(it) }
            ?: error("emulator requires a usable local underlying IPv4 address")
        private val context = NetworkContext(network, 1, emptyList(),
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
        val primary: Peer = if (doh) DohPeer("primary.stamp.example", address, failPrimary)
            else DotPeer("primary.stamp.example", address, failPrimary)
        val secondary: Peer = if (doh) DohPeer("secondary.stamp.example", address, false)
            else DotPeer("secondary.stamp.example", address, false)
        // These two cold literals make accidental primary/secondary list aliasing observable.
        val primaryBootstrap = listOf(address, InetAddress.getByName("10.250.200.1"))
        val secondaryBootstrap = listOf(address, InetAddress.getByName("10.250.200.2"))
        private val rawPrimary = stamp(if (doh) 2 else 3, address.hostAddress!!,
            primary.hostname, primary.port, "/stamp-primary", "10.250.200.1")
        private val rawSecondary = stamp(if (doh) 2 else 3, address.hostAddress!!,
            secondary.hostname, secondary.port, "/stamp-secondary", "10.250.200.2")
        private val configuration by lazy {
            ResolverConfiguration(useDeviceDns = false, useEncryptedFallback = true,
                customDohUrl = if (doh) rawPrimary else null,
                customDotEndpoint = if (doh) null else rawPrimary,
                customSecondaryEndpoint = rawSecondary)
        }
        private val plan by lazy {
            (ResolverPlanFactory.build(configuration, context) as ResolverPlanBuildResult.Ready).plan.also {
                assertTrue(it.isCanonicalReadyRoute())
                assertEquals(rawPrimary, it.customEndpointValue)
                assertEquals(rawSecondary, it.customSecondaryEndpointValue)
                assertEquals(primaryBootstrap, it.encryptedBootstrapAddresses)
                assertEquals(secondaryBootstrap, it.encryptedSecondaryBootstrapAddresses)
                if (doh) {
                    assertEquals("https://${primary.hostname}:${primary.port}/stamp-primary", it.dohEndpointUrl)
                    assertEquals("https://${secondary.hostname}:${secondary.port}/stamp-secondary", it.dohSecondaryEndpointUrl)
                } else {
                    assertEquals(CustomDotEndpoint(primary.hostname, primary.port), it.dotEndpoint)
                    assertEquals(CustomDotEndpoint(secondary.hostname, secondary.port), it.dotSecondaryEndpoint)
                }
            }
        }
        private val epoch by lazy { EngineEpoch(1, ConfigurationIdentity(1, "raw-stamp-peer"), "d".repeat(64),
            1, 1, 1, plan.primaryResolverIdentity, plan.fallbackCoverage) }
        private var registry: GenerationSocketRegistry? = null
        private val runtimes = mutableListOf<AutoCloseable>()
        private val dohRuntimes = mutableListOf<MullvadDohRuntime>()
        private val protectedSockets = CopyOnWriteArrayList<Socket>()
        val guards = CopyOnWriteArrayList<String>()
        val bootstrapLookups = CopyOnWriteArrayList<Pair<String, List<InetAddress>>>()
        private val protector = DohSocketProtector { socket ->
            assertTrue(socket.isBound); assertFalse(socket.isConnected)
            protectedSockets += socket
            guards += "protect"; true
        }
        private val binder = DohSocketBinder { socket, captured ->
            assertEquals(network, captured); assertFalse(socket.isConnected)
            assertEquals("protect", guards.last())
            captured.bindSocket(socket); guards += "bind"
        }
        private val tls by lazy {
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(primary.certificate.certificate)
                .addTrustedCertificate(secondary.certificate.certificate).build()
            MullvadDohTlsConfiguration(trust.sslSocketFactory(), trust.trustManager)
        }

        private val coordinator by lazy {
            val resources = GenerationSocketRegistry(epoch).also { registry = it }
            // Shipping chooses the primary/secondary protocols and attaches each bootstrap list.
            // The injected builders preserve that boundary while trusting only our local peers.
            val encrypted = buildReadyEncryptedRuntime(plan,
                doh = { endpoint, bootstrap ->
                    assertTrue(doh)
                    dohRuntime(checkNotNull(endpoint), bootstrap, resources)
                }, dot = { endpoint, bootstrap ->
                    assertFalse(doh)
                    dotRuntime(endpoint, bootstrap, resources)
                }, doq = { _, _ -> error("a DoH/DoT stamp cannot construct DoQ") })
            runtimes += encrypted
            ResolverCoordinator(epoch, plan, resources, null,
                encrypted.resolver, DeviceResolverWedgeEvidence { false })
        }

        suspend fun resolve(): ResolverResult.Answer = resolveResult() as ResolverResult.Answer

        suspend fun resolveResult(): ResolverResult {
            val request = ResolverRequest(QUERY, DnsMessage.parseQuestion(QUERY), epoch, context)
            return coordinator.resolve(request)
        }

        private fun dohRuntime(endpoint: String, bootstrap: List<InetAddress>, resources: GenerationSocketRegistry): ReadyDnsMullvadRuntime {
            val url = endpoint.toHttpUrl()
            assertEquals(if (url.host == primary.hostname) primaryBootstrap else secondaryBootstrap, bootstrap)
            val captured = CatalogDohBootstrap(url.host, bootstrap)
            val runtime = MullvadDohRuntime(epoch, context, resources, protector, binder, url,
                dns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = captured.lookup(hostname).also {
                        bootstrapLookups += hostname to it.toList()
                    }
                }, tls = tls)
            dohRuntimes += runtime
            return object : ReadyDnsMullvadRuntime {
                override val resolver = runtime.resolver
                override fun close() = runtime.close()
            }
        }

        private fun dotRuntime(endpoint: CustomDotEndpoint, bootstrap: List<InetAddress>, resources: GenerationSocketRegistry): ReadyDnsMullvadRuntime {
            assertEquals(if (endpoint.host == primary.hostname) primaryBootstrap else secondaryBootstrap, bootstrap)
            val runtime = CustomDotRuntime(epoch, context, resources, protector, binder, endpoint, tls,
                bootstrapAddresses = bootstrap)
            return runtime
        }

        fun assertDohRequest(peer: DohPeer) {
            val posted = peer.server.takeRequest(2, TimeUnit.SECONDS)
                ?: error("stamp peer received no DoH request")
            assertEquals("POST", posted.method)
            assertEquals(if (peer === primary) "/stamp-primary" else "/stamp-secondary", posted.path)
            assertEquals("application/dns-message", posted.getHeader("Content-Type"))
            assertEquals("application/dns-message", posted.getHeader("Accept"))
            assertArrayEquals(canonical(QUERY), posted.body.readByteArray())
            assertAuthority(posted, "${peer.hostname}:${peer.port}")
            assertNotNull(posted.handshake)
            assertEquals(listOf(peer.certificate.certificate), posted.handshake!!.localCertificates)
        }

        fun assertPooledOwnership(failedPrimary: Boolean) {
            val expectedConnections = if (failedPrimary) 2 else 1
            // A completed HTTP exchange releases its call handle, while the reusable socket
            // remains protected and owned by this epoch until runtime close or retirement.
            assertEquals(expectedConnections, protectedSockets.size)
            assertTrue(protectedSockets.all { !it.isClosed })
            assertEquals(expectedConnections, checkNotNull(registry).sizeForTesting)
            assertEquals(expectedConnections, dohRuntimes.sumOf { it.connectionPoolForTesting.connectionCount() })
            assertEquals(expectedConnections, dohRuntimes.sumOf { it.connectionPoolForTesting.idleConnectionCount() })
        }

        fun retireEpoch() {
            checkNotNull(registry).advanceTo(epoch.copy(engineGeneration = 2))
            assertEquals(0, checkNotNull(registry).sizeForTesting)
            assertTrue("retirement must physically close protected sockets", protectedSockets.all { it.isClosed })
        }

        fun assertRuntimeDrained(failedPrimary: Boolean) {
            assertEquals(if (failedPrimary) listOf("protect", "bind", "protect", "bind")
                else listOf("protect", "bind"), guards)
            if (!doh) {
                // DoT owns each socket only for the query; retain its pre-close release proof.
                assertEquals(0, checkNotNull(registry).sizeForTesting)
                assertTrue(protectedSockets.all { it.isClosed })
            }
            // Prove runtime-only draining before the fixture's registry.close can hide a leak.
            runtimes.asReversed().forEach { it.close() }
            assertEquals(0, checkNotNull(registry).sizeForTesting)
            assertTrue("runtime drain must physically close protected sockets", protectedSockets.all { it.isClosed })
            dohRuntimes.forEach { runtime ->
                assertEquals(0, runtime.connectionPoolForTesting.connectionCount())
                assertEquals(0, runtime.connectionPoolForTesting.idleConnectionCount())
                assertEquals(0, runtime.clientForTesting.dispatcher.runningCallsCount())
                assertEquals(0, runtime.clientForTesting.dispatcher.queuedCallsCount())
                assertTrue(runtime.clientForTesting.dispatcher.executorService.isTerminated)
            }
        }

        override fun close() {
            try {
                registry?.close()
                runtimes.asReversed().forEach { it.close() }
            } finally {
                try { primary.close() } finally { secondary.close() }
            }
        }
    }

    private abstract class Peer(val hostname: String) : AutoCloseable {
        // The bootstrap IP is deliberately absent: TLS must verify the decoded stamp hostname.
        val certificate = HeldCertificate.Builder().commonName(hostname).addSubjectAlternativeName(hostname).build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        abstract val port: Int
    }

    private class DohPeer(hostname: String, address: InetAddress, fail: Boolean) : Peer(hostname) {
        val server = MockWebServer().apply {
            useHttps(tls.sslSocketFactory(), false); start(address, 0)
            enqueue(if (fail) MockResponse().setResponseCode(503) else MockResponse()
                .setHeader("Content-Type", "application/dns-message")
                .setBody(Buffer().write(answer(canonical(QUERY)))))
        }
        override val port: Int get() = server.port
        override fun close() = server.shutdown()
    }

    private class DotPeer(hostname: String, address: InetAddress, fail: Boolean) : Peer(hostname) {
        private val listener = tls.sslContext().serverSocketFactory.createServerSocket(0, 1, address) as SSLServerSocket
        override val port: Int get() = listener.localPort
        private val accepted = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        val received = AtomicReference<ByteArray>()
        private val worker = Thread({
            try {
                (listener.accept() as SSLSocket).use { socket ->
                    accepted.set(socket); socket.soTimeout = 8_000; socket.startHandshake()
                    val input = DataInputStream(socket.inputStream)
                    val size = input.readUnsignedShort()
                    check(size in 12..4_096)
                    val body = ByteArray(size).also(input::readFully)
                    received.set(body)
                    DataOutputStream(socket.outputStream).apply {
                        val response = answer(body)
                        writeShort(if (fail) response.size + 1 else response.size)
                        write(response); flush()
                    }
                }
            } catch (problem: Throwable) { if (!listener.isClosed) failure.set(problem) }
        }, "lava-stamp-dot-peer").apply { isDaemon = true; start() }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("stamp TLS peer failed", it) } }
        override fun close() {
            listener.close(); accepted.get()?.let { runCatching { it.close() } }; worker.join(2_000)
            assertFalse("stamp TLS peer leaked", worker.isAlive)
            assertHealthy()
        }
    }

    private companion object {
        val QUERY = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
        fun canonical(query: ByteArray) = query.copyOf().also { it[0] = 0; it[1] = 0 }
        fun answer(query: ByteArray) = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }

        fun stamp(protocol: Int, bootstrap: String, hostname: String, port: Int, path: String, extra: String): String {
            val bytes = ByteArrayOutputStream()
            fun string(value: String) {
                val utf8 = value.toByteArray(Charsets.UTF_8)
                require(utf8.size < 128); bytes.write(utf8.size); bytes.write(utf8)
            }
            bytes.write(protocol); bytes.write(ByteArray(8))
            string("$bootstrap:$port"); bytes.write(0) // No unsupported certificate hashes.
            string("$hostname:$port")
            if (protocol == 2) string(path)
            string(extra) // One terminal VLP bootstrap element.
            return "sdns://" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
        }

        fun assertAuthority(posted: RecordedRequest, expected: String) {
            val h2 = posted.getHeader(":authority")
            if (h2 != null) {
                assertNull(posted.getHeader("Host")); assertEquals("https", posted.getHeader(":scheme"))
                assertEquals(expected, h2)
            } else assertEquals(expected, posted.getHeader("Host"))
        }
    }
}
