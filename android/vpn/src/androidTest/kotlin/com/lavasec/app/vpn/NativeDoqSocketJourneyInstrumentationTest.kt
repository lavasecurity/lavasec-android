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
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.platform.ConfigurationIdentity
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual packaged JNI/quiche + Android UDP + exact captured ordinary-Network binding.
 * The source-controlled host-loopback peer uses a public synthetic certificate for resolver.test.
 * Only explicit test-CA trust and socket-protection authorization are controlled; certificate
 * chain/hostname/SNI/ALPN, QUIC, DNS framing and sockets remain real. No provider, Plus, shipping
 * VpnService protect authorization, TUN delivery or physical-network qualification is claimed.
 * The host sees a NAT-translated tuple; 10.0.2.2 is the Android-observed peer, never that tuple.
 * This method requires its separately launched peer; absent arguments fail rather than skip.
 */
@RunWith(AndroidJUnit4::class)
class NativeDoqSocketJourneyInstrumentationTest {
    @Test
    fun verifiedAndroidQuicPeerRestoresCallerIdAndReleasesExactEpochResources(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("doqPeerHost") == "10.0.2.2") {
            "Controlled DoQ requires explicit doqPeerHost=10.0.2.2 on the owned emulator"
        }
        val port = arguments.getString("doqPeerPort")?.toIntOrNull()
            ?.takeIf { it in 1024..65535 } ?: error("Controlled DoQ requires the explicit ephemeral doqPeerPort")
        val peer = InetAddress.getByAddress(byteArrayOf(10, 0, 2, 2))
        val connectivity = instrumentation.targetContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: error("Controlled DoQ requires an active ordinary Network")
        fun assertNetwork() {
            assertEquals("The fixture must retain its captured active Network", network, connectivity.activeNetwork)
            val capabilities = checkNotNull(connectivity.getNetworkCapabilities(network))
            assertTrue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            assertTrue("Never bind this fixture to a VPN", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        }
        assertNetwork()
        val context = NetworkContext(network, 1, emptyList(),
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
        val configuration = ResolverConfiguration(useDeviceDns = false, useEncryptedFallback = true,
            customDoqEndpoint = "doq://resolver.test:$port")
        val plan = (ResolverPlanFactory.build(configuration, context) as ResolverPlanBuildResult.Ready).plan
        assertTrue(plan.isCanonicalReadyRoute())
        assertEquals(CustomDoqEndpoint("resolver.test", port), plan.doqEndpoint)
        val epoch = EngineEpoch(1, ConfigurationIdentity(1, "controlled-Android-DoQ-peer"), "d".repeat(64),
            1, 1, 1, plan.primaryResolverIdentity, plan.fallbackCoverage)
        val registry = GenerationSocketRegistry(epoch)
        val guards = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<DatagramSocket>()
        val sessionCreations = AtomicInteger()
        val sessionReleases = AtomicInteger()
        val sessionCancellations = AtomicInteger()
        val nativeReplies = CopyOnWriteArrayList<ByteArray>()
        val certificate = File.createTempFile("controlled-doq-root-", ".pem", instrumentation.targetContext.cacheDir)
        var runtime: CustomDoqRuntime? = null
        var originalFailure: Throwable? = null
        try {
            instrumentation.context.assets.open("doq/resolver.crt").use { source ->
                certificate.outputStream().use { target -> source.copyTo(target) }
            }
            val platform = JavaNetDeviceDnsSocketPlatform(
                protector = object : OrdinaryVpnSocketProtector {
                    override fun protect(socket: DatagramSocket): Boolean {
                        assertNetwork()
                        assertFalse(socket.isClosed)
                        assertFalse(socket.isConnected)
                        assertTrue(sockets.isEmpty())
                        sockets += socket
                        guards += "controlled-protect"
                        return true
                    }
                    override fun protect(socket: Socket): Boolean = error("DoQ cannot open a TCP socket")
                },
                binder = object : ExactNetworkSocketBinder {
                    override fun bind(captured: Network, socket: DatagramSocket) {
                        assertSame(network, captured)
                        assertSame(sockets.single(), socket)
                        assertEquals(listOf("controlled-protect"), guards.toList())
                        assertFalse(socket.isConnected)
                        captured.bindSocket(socket) // Real Android syscall, not a fake binder.
                        guards += "exact-network-bind"
                    }
                    override fun bind(network: Network, socket: Socket) = error("DoQ cannot bind a TCP socket")
                },
            )
            val query = query()
            val canonical = query.copyOf().apply { this[0] = 0; this[1] = 0 }
            val nativeFactory = NativeDoqSessionFactory { "file:${certificate.absolutePath}" }
            val observedFactory = DoqSessionFactory { endpoint, capturedPeer, posted ->
                assertEquals(checkNotNull(plan.doqEndpoint), endpoint)
                assertEquals(peer, capturedPeer)
                assertArrayEquals(canonical, posted)
                assertEquals(listOf("controlled-protect", "exact-network-bind"), guards.toList())
                assertTrue(registry.isCurrent(epoch))
                assertEquals(1, registry.sizeForTesting)
                val native = nativeFactory.create(endpoint, capturedPeer, posted)
                sessionCreations.incrementAndGet()
                guards += "verified-native-session"
                // Observe the genuine native engine; never substitute an event or an answer.
                object : DoqSession by native {
                    override fun nextEvent(): ByteArray = native.nextEvent().also { event ->
                        if (event.firstOrNull() == 2.toByte()) nativeReplies += event.copyOfRange(1, event.size)
                    }
                    override fun cancel() { sessionCancellations.incrementAndGet(); native.cancel() }
                    override fun close() { sessionReleases.incrementAndGet(); native.close() }
                }
            }
            val actual = CustomDoqRuntime(epoch, context, registry, platform, checkNotNull(plan.doqEndpoint),
                sessions = observedFactory,
                addressLookup = DoqAddressLookup { _, _ -> error("The controlled bootstrap must not query external DNS") },
                bootstrapAddresses = listOf(peer))
            runtime = actual
            val request = ResolverRequest(query, DnsMessage.parseQuestion(query), epoch, context)
            val result = withTimeout(8_000) { actual.resolver.resolve(request) }
            assertTrue("The actual native QUIC/TLS exchange must return a DNS answer; got ${(result as? ResolverResult.Failure)?.reason}",
                result is ResolverResult.Answer)
            val answer = result as ResolverResult.Answer
            assertEquals(ResolverTransport.DOQ, answer.transport)
            assertArrayEquals(reply(query), answer.dnsMessage)
            assertEquals(1, nativeReplies.size)
            assertArrayEquals(reply(canonical), nativeReplies.single())
            assertArrayEquals(byteArrayOf(0x6a, 0x71), answer.dnsMessage.copyOfRange(0, 2))
            assertEquals(listOf("controlled-protect", "exact-network-bind", "verified-native-session"), guards.toList())
            assertEquals(1, sessionCreations.get())
            assertEquals("The successful genuine JNI handle is released once", 1, sessionReleases.get())
            assertEquals(0, sessionCancellations.get())
            assertEquals(0, registry.sizeForTesting)
            assertTrue("The actual Android UDP socket is closed at terminal release", sockets.single().isClosed)
            assertNetwork()

            // The same real runtime cannot reopen a socket or JNI session after owner retirement.
            registry.advanceTo(epoch.copy(engineGeneration = 2))
            val retired = withTimeout(2_000) { actual.resolver.resolve(request) }
            assertEquals(ResolverFailureReason.STALE_EPOCH, (retired as ResolverResult.Failure).reason)
            assertEquals(1, sessionCreations.get())
            assertEquals(1, sessionReleases.get())
            assertEquals(1, sockets.size)
            assertEquals(0, registry.sizeForTesting)
            assertEquals(3, guards.size)
            println("LAVA_NATIVE_DOQ_SOCKET peer=10.0.2.2:$port tls=verified-test-CA-hostname-SNI-ALPN result=real-NXDOMAIN caller-id=restored native-release=one socket=closed retired-epoch=refused protect=controlled-authorization bind=actual-Network TUN=not-claimed")
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            val cleanup = runCatching {
                try { runtime?.close() } finally {
                    try { registry.close() } finally { assertTrue("Release the public fixture root file", certificate.delete()) }
                }
            }.exceptionOrNull()
            if (cleanup != null) {
                val original = originalFailure
                if (original != null) original.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun query(): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x6a, 0x71, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in listOf("android-doq", "invalid")) {
            val bytes = label.toByteArray(Charsets.US_ASCII); write(bytes.size); write(bytes)
        }
        write(byteArrayOf(0, 0, 1, 0, 1))
    }.toByteArray()

    private fun reply(query: ByteArray): ByteArray = query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x83.toByte() }
}
