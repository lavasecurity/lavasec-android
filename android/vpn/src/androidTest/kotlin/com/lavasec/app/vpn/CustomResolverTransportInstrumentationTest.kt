package com.lavasec.app.vpn

import android.content.Context
import android.net.ConnectivityManager
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
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android TLS/socket/HTTP exchanges; every endpoint and bootstrap address is loopback. */
@RunWith(AndroidJUnit4::class)
class CustomResolverTransportInstrumentationTest {
    @Test
    fun customDohPostsCanonicalDnsAndRestoresCallerIdThroughAndroidTls() = runBlocking {
        DohFixture().use { fixture ->
            val query = query()
            fixture.server.enqueue(dnsResponse(answer(canonical(query))))
            val result = withTimeout(8_000) { fixture.runtime.resolver.resolve(fixture.request(query)) }
            assertTrue(result is ResolverResult.Answer)
            result as ResolverResult.Answer
            assertEquals(ResolverTransport.MULLVAD_DOH, result.transport)
            assertArrayEquals(answer(query), result.dnsMessage)
            val posted = fixture.server.takeRequest(2, TimeUnit.SECONDS)
                ?: throw AssertionError("loopback DoH received no request")
            assertEquals("POST", posted.method)
            assertEquals("/custom-dns?owner=emulator", posted.path)
            assertEquals("application/dns-message", posted.getHeader("Accept"))
            assertEquals("application/dns-message", posted.getHeader("Content-Type"))
            assertArrayEquals(canonical(query), posted.body.readByteArray())
            assertEquals(listOf("protect", "bind"), fixture.guards)
        }
    }

    @Test
    fun catalogBootstrapPreservesVerifiedHostnameForAndroidDohAndDot() = runBlocking {
        DohFixture("resolver.example").use { fixture ->
            fixture.server.enqueue(dnsResponse(answer(canonical(query()))))
            val result = withTimeout(8_000) { fixture.runtime.resolver.resolve(fixture.request(query())) }
            assertTrue(result is ResolverResult.Answer)
            result as ResolverResult.Answer
            assertEquals(ResolverTransport.MULLVAD_DOH, result.transport)
            assertArrayEquals(answer(query()), result.dnsMessage)
            val posted = fixture.server.takeRequest(2, TimeUnit.SECONDS)
                ?: throw AssertionError("catalog bootstrap received no DoH request")
            val authority = "resolver.example:${fixture.server.port}"
            // MockWebServer preserves HTTP/2 pseudo-headers but normalizes its request line to
            // HTTP/1.1. Check the actual authority field instead of inferring from that line.
            val h2Authority = posted.getHeader(":authority")
            if (h2Authority != null) {
                assertNull(posted.getHeader("Host"))
                assertEquals("https", posted.getHeader(":scheme"))
                assertEquals(authority, h2Authority)
            } else {
                assertEquals(authority, posted.getHeader("Host"))
            }
            assertNotNull(posted.handshake)
            assertEquals(listOf(fixture.certificate.certificate), posted.handshake!!.localCertificates)
            assertEquals(listOf("protect", "bind"), fixture.guards)
        }
        DotFixture("resolver.example") { socket, received ->
            DataOutputStream(socket.outputStream).apply { writeShort(received.size); write(answer(received)); flush() }
        }.use { fixture ->
            val result = withTimeout(8_000) { fixture.runtime.resolver.resolve(fixture.request(query())) }
            assertEquals(ResolverTransport.DOT, (result as ResolverResult.Answer).transport)
            assertArrayEquals(answer(query()), result.dnsMessage)
            assertArrayEquals(canonical(query()), fixture.received.get())
            assertEquals(listOf("protect", "bind"), fixture.guards)
            assertEquals(0, fixture.registry.sizeForTesting)
        }
    }

    @Test
    fun customDohRejectsWrongMediaTypeThenAcceptsNextExchange() = runBlocking {
        DohFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("invalid"))
            fixture.server.enqueue(dnsResponse(answer(canonical(query()))))
            assertEquals(
                ResolverFailureReason.HTTP_CONTENT_TYPE,
                (withTimeout(8_000) { fixture.runtime.resolver.resolve(fixture.request(query())) }
                    as ResolverResult.Failure).reason,
            )
            assertTrue(withTimeout(8_000) {
                fixture.runtime.resolver.resolve(fixture.request(query()))
            } is ResolverResult.Answer)
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test
    fun cancellingCustomDohDrainsAndroidSocketAndCallOwnership() = runBlocking {
        DohFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async(Dispatchers.IO) { fixture.runtime.resolver.resolve(fixture.request(query())) }
            try {
                assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
                withTimeout(2_000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
                fixture.runtime.close()
                assertEquals(0, fixture.registry.sizeForTesting)
                assertEquals(0, fixture.runtime.clientForTesting.dispatcher.runningCallsCount())
                assertEquals(0, fixture.runtime.connectionPoolForTesting.connectionCount())
            } finally {
                pending.cancel()
            }
        }
    }

    @Test
    fun retiringCustomDohEpochCannotPublishLoopbackAnswer() = runBlocking {
        DohFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async(Dispatchers.IO) { fixture.runtime.resolver.resolve(fixture.request(query())) }
            try {
                assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
                fixture.registry.advanceTo(fixture.epoch.copy(engineGeneration = 2))
                assertEquals(
                    ResolverFailureReason.STALE_EPOCH,
                    (withTimeout(2_000) { pending.await() } as ResolverResult.Failure).reason,
                )
                assertEquals(0, fixture.registry.sizeForTesting)
            } finally {
                pending.cancel()
            }
        }
    }

    @Test
    fun customDotUsesTlsLengthPrefixAndRestoresCallerId() = runBlocking {
        DotFixture { socket, received ->
            DataOutputStream(socket.outputStream).apply {
                val body = answer(received)
                writeShort(body.size)
                write(body)
                flush()
            }
        }.use { fixture ->
            val query = query()
            val result = withTimeout(8_000) { fixture.runtime.resolver.resolve(fixture.request(query)) }
            assertTrue(result is ResolverResult.Answer)
            result as ResolverResult.Answer
            assertEquals(ResolverTransport.DOT, result.transport)
            assertArrayEquals(answer(query), result.dnsMessage)
            assertArrayEquals(canonical(query), fixture.received.get())
            assertEquals(listOf("protect", "bind"), fixture.guards)
            assertEquals(0, fixture.registry.sizeForTesting)
        }
    }

    @Test
    fun customDotRejectsMismatchingWireIdRatherThanRepairingIt() = runBlocking {
        DotFixture { socket, received ->
            val response = answer(received).apply { this[0] = 1 }
            DataOutputStream(socket.outputStream).apply { writeShort(response.size); write(response); flush() }
        }.use { fixture ->
            val result = withTimeout(8_000) { fixture.runtime.resolver.resolve(fixture.request(query())) }
            result as ResolverResult.Failure
            assertEquals(ResolverFailureReason.MISMATCH_LIMIT, result.reason)
            assertFalse(result.peerAnswered)
            assertFalse(result.local)
            assertArrayEquals(canonical(query()), fixture.received.get())
            assertEquals(listOf("protect", "bind"), fixture.guards)
            assertEquals(0, fixture.registry.sizeForTesting)
        }
    }

    @Test
    fun customDotRejectsTruncatedFrameAndReleasesAndroidSocket() = runBlocking {
        DotFixture { socket, received ->
            DataOutputStream(socket.outputStream).apply {
                writeShort(received.size + 1)
                write(answer(received))
                flush()
            }
        }.use { fixture ->
            assertEquals(
                ResolverFailureReason.RECEIVE_FAILED,
                (withTimeout(8_000) { fixture.runtime.resolver.resolve(fixture.request(query())) }
                    as ResolverResult.Failure).reason,
            )
            assertEquals(0, fixture.registry.sizeForTesting)
        }
    }

    @Test
    fun cancellingCustomDotWakesBlockedAndroidTlsReadBeforeTransportTimeout() = runBlocking {
        DotFixture { socket, _ -> runCatching { socket.inputStream.read() } }.use { fixture ->
            val pending = async(Dispatchers.IO) { fixture.runtime.resolver.resolve(fixture.request(query())) }
            try {
                assertTrue("loopback DoT did not receive the DNS frame", fixture.queryRead.await(3, TimeUnit.SECONDS))
                // The production transport has a five-second read timeout. A two-second bound
                // proves caller cancellation closes the socket instead of waiting for that timeout.
                withTimeout(2_000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
                assertEquals(0, fixture.registry.sizeForTesting)
            } finally {
                pending.cancel()
            }
        }
    }

    @Test
    fun retiringCustomDotEpochWakesReadAndRefusesStalePublication() = runBlocking {
        DotFixture { socket, _ -> runCatching { socket.inputStream.read() } }.use { fixture ->
            val pending = async(Dispatchers.IO) { fixture.runtime.resolver.resolve(fixture.request(query())) }
            try {
                assertTrue(fixture.queryRead.await(3, TimeUnit.SECONDS))
                fixture.registry.advanceTo(fixture.epoch.copy(engineGeneration = 2))
                assertEquals(
                    ResolverFailureReason.STALE_EPOCH,
                    (withTimeout(2_000) { pending.await() } as ResolverResult.Failure).reason,
                )
                assertEquals(0, fixture.registry.sizeForTesting)
            } finally {
                pending.cancel()
            }
        }
    }

    private open class SocketFixture(tlsHostname: String = "127.0.0.1") : AutoCloseable {
        val epoch = EngineEpoch(
            sessionGeneration = 1,
            configurationIdentity = ConfigurationIdentity(1, "emulator-custom-resolver"),
            artifactDigest = "d".repeat(64),
            policyRevision = 1,
            networkGeneration = 1,
            engineGeneration = 1,
            primaryResolverIdentity = PrimaryResolverIdentity("custom-loopback"),
            fallbackCoverage = ResolverFallbackCoverage.NONE,
        )
        private val network = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CONNECTIVITY_SERVICE).let { it as ConnectivityManager }
            .activeNetwork ?: throw AssertionError("emulator requires an active underlying network")
        val networkContext = NetworkContext(network, 1, emptyList(), false)
        val registry = GenerationSocketRegistry(epoch)
        val guards = CopyOnWriteArrayList<String>()
        // This exercises the production protection boundary and real Android network binding.
        // VpnService consent/protect authorization is covered by service qualification separately.
        val protector = DohSocketProtector { socket ->
            assertTrue(socket.isBound)
            assertFalse(socket.isConnected)
            guards += "protect"
            true
        }
        val binder = DohSocketBinder { socket, captured ->
            assertEquals(network, captured)
            assertFalse(socket.isConnected)
            assertEquals("protect", guards.last())
            captured.bindSocket(socket)
            guards += "bind"
        }
        // In a bootstrap fixture this certificate deliberately omits the loopback address.
        // Real TLS can succeed only when it verifies the retained logical endpoint hostname.
        val certificate = HeldCertificate.Builder().commonName(tlsHostname)
            .addSubjectAlternativeName(tlsHostname).build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val tls = MullvadDohTlsConfiguration(clientTls.sslSocketFactory(), clientTls.trustManager)

        fun request(query: ByteArray) = ResolverRequest(query, DnsMessage.parseQuestion(query), epoch, networkContext)

        override fun close() = registry.close()
    }

    private class DohFixture(private val bootstrapHostname: String? = null) : SocketFixture(bootstrapHostname ?: "127.0.0.1") {
        val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val runtime = MullvadDohRuntime(
            epoch, networkContext, registry, protector, binder,
            endpoint = server.url("/custom-dns?owner=emulator").newBuilder().host(bootstrapHostname ?: "127.0.0.1").build(),
            dns = bootstrapHostname?.let { CatalogDohBootstrap(it, listOf(InetAddress.getByName("127.0.0.1"))) }
                ?: NetworkDohBootstrap(networkContext.network),
            tls = tls,
        )

        override fun close() {
            try { runtime.close() } finally {
                try { super.close() } finally { server.shutdown() }
            }
        }
    }

    private class DotFixture(private val bootstrapHostname: String? = null, private val reply: (SSLSocket, ByteArray) -> Unit) : SocketFixture(bootstrapHostname ?: "127.0.0.1") {
        private val listener = serverTls.sslContext().serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        private val accepted = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        val queryRead = CountDownLatch(1)
        val received = AtomicReference<ByteArray>()
        val runtime = CustomDotRuntime(
            epoch, networkContext, registry, protector, binder,
            CustomDotEndpoint(bootstrapHostname ?: "127.0.0.1", listener.localPort), tls,
            bootstrapAddresses = bootstrapHostname?.let { listOf(InetAddress.getByName("127.0.0.1")) }.orEmpty(),
        )
        private val worker = Thread({
            try {
                (listener.accept() as SSLSocket).use { socket ->
                    accepted.set(socket)
                    socket.soTimeout = 8_000
                    socket.startHandshake()
                    val input = DataInputStream(socket.inputStream)
                    val size = input.readUnsignedShort()
                    assertTrue("unbounded DNS frame", size in 12..4_096)
                    val query = ByteArray(size).also(input::readFully)
                    received.set(query)
                    queryRead.countDown()
                    reply(socket, query)
                }
            } catch (error: Throwable) {
                if (!listener.isClosed) failure.set(error)
            }
        }, "emulator-custom-dot-loopback").apply { isDaemon = true; start() }

        override fun close() {
            registry.close()
            listener.close()
            runCatching { accepted.get()?.close() }
            worker.join(2_000)
            assertFalse("loopback TLS worker leaked", worker.isAlive)
            failure.get()?.let { throw AssertionError("loopback TLS fixture failed", it) }
        }
    }

    private companion object {
        fun query(): ByteArray = byteArrayOf(
            0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1,
        )
        fun canonical(query: ByteArray) = query.copyOf().also { it[0] = 0; it[1] = 0 }
        fun answer(query: ByteArray) = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
        fun dnsResponse(body: ByteArray) = MockResponse().setHeader("Content-Type", "application/dns-message")
            .setBody(Buffer().write(body))
    }
}
