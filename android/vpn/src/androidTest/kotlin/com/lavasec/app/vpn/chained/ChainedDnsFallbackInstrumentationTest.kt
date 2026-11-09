package com.lavasec.app.vpn.chained

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.chained.ChainedInnerUdpPacket
import com.lavasec.app.foundation.chained.ChainedOwnResolverPorts
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactOpenResult
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.foundation.health.PrimaryResolverOutcome
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.FilterArtifactReference
import com.lavasec.app.platform.VerifiedFilterRuntimeLease
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import com.lavasec.app.vpn.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Controlled Android integration: a compiled/verified filter, production T0 policy and saved-DNS
 * adapter, production coordinator and actual device-local UDP/TCP sockets. The fixture protection
 * boundary records admission; exact Android Network binding and endpoint observations are real.
 * The T0 carry intentionally has no responding peer; its bounded expiry is driven by a fixture
 * clock. Client publication writes a real OS pipe. This neither starts a VPN nor claims live
 * account, paid entitlement, Internet connectivity, or a device TUN route.
 */
@RunWith(AndroidJUnit4::class)
class ChainedDnsFallbackInstrumentationTest {
    @Test
    fun splitT0FailureUsesRealSavedUdpAndTcpAndCommitsOnlyAtClientWrite(): Unit = runBlocking {
        for (tcp in listOf(false, true)) {
            Fixture(tcp = tcp).use { fixture ->
                fixture.startLookup()
                assertEquals(1, fixture.t0.size)
                assertEquals(0, fixture.server.received.size)
                fixture.expireT0()
                assertTrue("saved DNS never completed", fixture.ready.await(5, TimeUnit.SECONDS))
                fixture.server.assertHealthy()
                assertArrayEquals(QUERY, fixture.server.received.first())
                assertEquals(if (tcp) 2 else 1, fixture.server.received.size)
                assertTrue(fixture.events.isEmpty())
                assertTrue(fixture.evidence.isEmpty())
                assertEquals(listOf("protect", "bind").let { if (tcp) it + it else it }, fixture.guards)

                val response = fixture.responses.single()
                assertEquals(TunWriteAttempt.WouldBlock, response.tryWrite { TunWriteAttempt.WouldBlock })
                assertTrue(fixture.evidence.isEmpty())
                fixture.writeAndVerify(response)
                assertEquals(ResolverKind.DEVICE, fixture.events.single().resolver)
                assertEquals(if (tcp) ResolverTransport.DEVICE_TCP else ResolverTransport.DEVICE_UDP,
                    fixture.events.single().transport)
                val evidence = fixture.evidence.single()
                assertEquals(PrimaryResolverOutcome.FAILED, evidence.primaryOutcome)
                assertEquals(ResolverFailureReason.TIMEOUT, evidence.primaryFailureReason)
                assertEquals(ProtectionFallback.DEVICE_DNS, evidence.successfulFallback)
                assertEquals(fixture.events.single().id, evidence.queryId)
                assertEquals(null, evidence.latencyMillis)
                assertEquals(null, response.tryWrite { error("reply cannot publish twice") })
                assertEquals(0, fixture.registry.get().sizeForTesting)
            }
        }
    }

    @Test
    fun splitIpv6ClientUsesRealSavedUdpAndTcpAndWritesExactChecksummedTuple(): Unit = runBlocking {
        for (tcp in listOf(false, true)) {
            Fixture(tcp = tcp, ipv6Client = true).use { fixture ->
                fixture.startLookup()
                val t0 = checkNotNull(ChainedInnerUdpPacket.parse(fixture.t0.single()))
                assertArrayEquals(ip("10.7.0.2"), t0.sourceAddress)
                assertArrayEquals(QUERY, t0.payload)
                fixture.expireT0()
                assertTrue("IPv6 client saved DNS never completed", fixture.ready.await(5, TimeUnit.SECONDS))
                fixture.server.assertHealthy()
                assertArrayEquals(QUERY, fixture.server.received.first())
                assertEquals(if (tcp) 2 else 1, fixture.server.received.size)
                assertTrue(fixture.events.isEmpty())
                assertTrue(fixture.evidence.isEmpty())
                val response = fixture.responses.single()
                assertEquals(TunWriteAttempt.WouldBlock, response.tryWrite { TunWriteAttempt.WouldBlock })
                assertTrue(fixture.evidence.isEmpty())
                fixture.writeAndVerify(response)
                assertEquals(if (tcp) ResolverTransport.DEVICE_TCP else ResolverTransport.DEVICE_UDP,
                    fixture.events.single().transport)
                assertEquals(ProtectionFallback.DEVICE_DNS, fixture.evidence.single().successfulFallback)
                assertEquals(ResolverFailureReason.TIMEOUT, fixture.evidence.single().primaryFailureReason)
                assertEquals(fixture.events.single().id, fixture.evidence.single().queryId)
                assertEquals(null, response.tryWrite { error("IPv6 reply cannot publish twice") })
                assertEquals(0, fixture.registry.get().sizeForTesting)
            }
        }
    }

    @Test
    fun absentConsentAndFullModeNeverSendToRealSavedRoute(): Unit = runBlocking {
        for (full in listOf(false, true)) {
            // A full-mode fixture deliberately supplies the cold adapter to test the egress's
            // independent refusal. Production never constructs that adapter for full mode.
            Fixture(tcp = false, consent = full, full = full).use { fixture ->
                fixture.startLookup()
                fixture.expireT0()
                assertTrue(fixture.ready.await(2, TimeUnit.SECONDS))
                fixture.writeAndVerify(fixture.responses.single(), servfail = true)
                assertTrue(fixture.server.received.isEmpty())
                assertTrue(fixture.guards.isEmpty())
                assertEquals(if (full) 1 else 0, fixture.resolverCreates.get())
                assertEquals(ProtectionFallback.NONE, fixture.evidence.single().successfulFallback)
                assertEquals(ResolverKind.WIREGUARD_T0, fixture.events.single().resolver)
            }
        }
    }

    @Test
    fun retiringSplitLookupClosesActualPendingSocketAndCannotPublishLateEvidence(): Unit = runBlocking {
        Fixture(tcp = false, respond = false).use { fixture ->
            fixture.startLookup()
            fixture.expireT0()
            assertTrue("local DNS never received the pending query", fixture.server.queryRead.await(3, TimeUnit.SECONDS))
            val started = System.nanoTime()
            fixture.dispatcher.run { fixture.egress.close() }
            assertTrue("owned cancellation waited for the transport timeout",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            fixture.dispatcher.run { Unit } // Drain any completion already queued before retirement.
            assertEquals(0, fixture.registry.get().sizeForTesting)
            assertEquals(1, fixture.resolverCloses.get())
            assertTrue(fixture.responses.isEmpty())
            assertTrue(fixture.events.isEmpty())
            assertTrue(fixture.evidence.isEmpty())
        }
    }

    private class Fixture(tcp: Boolean, consent: Boolean = true, full: Boolean = false, respond: Boolean = true, private val ipv6Client: Boolean = false) : AutoCloseable {
        private val connectivity = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val network = connectivity.activeNetwork
            ?: throw AssertionError("emulator requires an underlying network")
        // Device DNS correctly refuses 127/8. Listen on the emulator's actual captured physical
        // IPv4 address instead: the production address/UDP source validator remains unchanged.
        private val address = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && DeviceDnsAddressPolicy.isUsable(it) }
            ?: throw AssertionError("emulator requires a usable local underlying IPv4 address")
        val server = LocalDns(address, tcp, respond)
        val guards = CopyOnWriteArrayList<String>()
        val t0 = CopyOnWriteArrayList<ByteArray>()
        val responses = CopyOnWriteArrayList<ChainedDnsResponse>()
        val events = CopyOnWriteArrayList<DnsDecisionEvent>()
        val evidence = CopyOnWriteArrayList<ResolverQueryHealthEvidence>()
        val ready = CountDownLatch(1)
        val registry = AtomicReference<GenerationSocketRegistry>()
        val resolverCreates = AtomicInteger()
        val resolverCloses = AtomicInteger()
        private val filter = FilterFixture.create()
        private val executor = Executors.newSingleThreadExecutor()
        private val queue = ChainedEngineQueue(executor)
        val dispatcher = EngineQueueChainedDispatcher(queue)
        private val now = AtomicLong(1_000)
        private val current = AtomicBoolean(true)
        private val context = NetworkContext(network, 1, listOf(address), false)
        private val plan = ResolverPlan(network, 1, false, listOf(address), false,
            ResolverKind.DEVICE, primaryResolverIdentity = ResolverPlanFactory.deviceIdentity(listOf(address)),
            fallbackCoverage = ResolverFallbackCoverage.NONE)
        private val epoch = EngineEpoch(1, filter.identity, filter.prepared.sha256, filter.identity.generation, 1, 1,
            plan.primaryResolverIdentity, plan.fallbackCoverage)
        private val sockets = JavaNetDeviceDnsSocketPlatform(
            object : OrdinaryVpnSocketProtector {
                override fun protect(socket: DatagramSocket): Boolean { guards += "protect"; return true }
                override fun protect(socket: Socket): Boolean { guards += "protect"; return true }
            }, object : ExactNetworkSocketBinder {
                override fun bind(captured: android.net.Network, socket: DatagramSocket) {
                    assertEquals(network, captured); assertEquals("protect", guards.last())
                    captured.bindSocket(socket); guards += "bind"
                }
                override fun bind(captured: android.net.Network, socket: Socket) {
                    assertEquals(network, captured); assertEquals("protect", guards.last())
                    captured.bindSocket(socket); guards += "bind"
                }
            },
        )
        private val fallback = if (consent) SavedDnsChainedFallback(epoch, context, plan,
            ReadyDnsResolverFactory { exactEpoch, exactContext, exactPlan, resources ->
                resolverCreates.incrementAndGet()
                registry.set(resources as GenerationSocketRegistry)
                val device = DeviceDnsTransport(sockets, resources, dnsPort = server.port)
                val coordinator = ResolverCoordinator(exactEpoch, exactPlan, resources, device, null,
                    DeviceResolverWedgeEvidence { false })
                object : OwnedReadyDnsResolver {
                    override suspend fun resolve(request: ResolverRequest) = coordinator.resolve(request)
                    override fun close() { resolverCloses.incrementAndGet() }
                }
            }, isCurrent = current::get) else null
        private val source = ArtifactBackedProxyPolicySource.take(filter.lease(),
            ProxyPolicyControlSource { ProxyPolicyControl(41, RuntimePauseDirective.Filtering) })
        private val policy = ChainedDnsPolicy(source, source.identity, source,
            DecisionEventIdSource { 99 }, EpochMillisClock(now::get),
            ProxyPublicationFence { completion -> current.get() && completion() },
            record = { activity, _, receipt -> activity?.let(events::add); receipt?.let(evidence::add) })
        val egress = ChainedT0ResolverEgress(profile(full), ChainedOwnResolverPorts(now::get), now::get,
            policy, queryTimeoutMillis = 100, fallback = fallback,
            dispatchCompletion = { action -> dispatcher.run(action) }).apply {
            bind({ t0 += it.copyOf() }, { responses += it; ready.countDown() })
        }
        private val clientSource = if (ipv6Client) java.net.InetAddress.getByName("fd00:1a7a::2").address else ip("10.7.0.2")
        private val clientDestination = if (ipv6Client) java.net.InetAddress.getByName("2606:4700:4700::1111").address else ip("10.111.0.1")
        fun startLookup() = dispatcher.run { egress.handleQuery(ChainedInnerUdpPacket.build(
            clientSource, clientDestination, 40000, 53, 1, QUERY)) }
        fun expireT0() = dispatcher.run { now.addAndGet(101); egress.expire() }
        fun writeAndVerify(response: ChainedDnsResponse, servfail: Boolean = false) {
            val pipe = ParcelFileDescriptor.createPipe()
            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    var packet = ByteArray(0)
                    assertEquals(TunWriteAttempt.Written, dispatcher.run { response.tryWrite {
                        packet = it.copyOf(); output.write(it); output.flush(); TunWriteAttempt.Written
                    } })
                    val received = ByteArray(packet.size)
                    DataInputStream(input).readFully(received)
                    assertArrayEquals(packet, received)
                    val datagram = checkNotNull(if (ipv6Client) ChainedInnerUdpPacket.parseIPv6Udp(received)
                        else ChainedInnerUdpPacket.parse(received))
                    assertArrayEquals(clientDestination, datagram.sourceAddress)
                    assertArrayEquals(clientSource, datagram.destinationAddress)
                    assertEquals(53, datagram.sourcePort)
                    assertEquals(40000, datagram.destinationPort)
                    if (ipv6Client) {
                        assertEquals(0xffff, ipv6ChecksumSum(received))
                        assertTrue(received[46] != 0.toByte() || received[47] != 0.toByte())
                    }
                    if (servfail) assertEquals(2, datagram.payload[3].toInt() and 15)
                    else assertArrayEquals(answer(QUERY), datagram.payload)
                }
            }
        }
        override fun close() {
            current.set(false)
            try { dispatcher.run { egress.close() } } finally {
                queue.close(); executor.shutdownNow(); server.close(); filter.close()
            }
        }
    }

    private class LocalDns(address: InetAddress, private val tcp: Boolean, private val respond: Boolean) : AutoCloseable {
        private val sockets = LocalDnsSockets.bind(address, tcp)
        private val udp = sockets.udp
        val port: Int = udp.localPort
        private val listener = sockets.tcp
        private val accepted = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        val received = CopyOnWriteArrayList<ByteArray>()
        val queryRead = CountDownLatch(1)
        private val worker = Thread({
            try {
                val incoming = DatagramPacket(ByteArray(4096), 4096)
                udp.soTimeout = 5_000
                udp.receive(incoming)
                val query = incoming.data.copyOf(incoming.length)
                received += query; queryRead.countDown()
                if (respond) {
                    val reply = answer(query).also { if (tcp) it[2] = (it[2].toInt() or 2).toByte() }
                    udp.send(DatagramPacket(reply, reply.size, incoming.address, incoming.port))
                    if (tcp) checkNotNull(listener).accept().use { socket ->
                        accepted.set(socket); socket.soTimeout = 5_000
                        val input = DataInputStream(socket.getInputStream())
                        val size = input.readUnsignedShort()
                        check(size in 12..4096)
                        val body = ByteArray(size).also(input::readFully)
                        received += body
                        DataOutputStream(socket.getOutputStream()).apply {
                            val response = answer(body); writeShort(response.size); write(response); flush()
                        }
                    }
                }
            } catch (problem: Throwable) { if (!udp.isClosed) failure.set(problem) }
        }, "lava-loopback-fallback").apply { isDaemon = true; start() }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("loopback failed", it) } }
        override fun close() {
            sockets.close(); accepted.get()?.close(); worker.join(2_000)
        }
    }

    private class FilterFixture private constructor(val root: File, val prepared: PreparedFilterArtifact,
        val configuration: FilterLibraryDraft, val identity: ConfigurationIdentity) : AutoCloseable {
        fun lease(): VerifiedFilterRuntimeLease {
            val opened = FilterArtifact.openResult(prepared.file, prepared.sha256) as FilterArtifactOpenResult.Available
            return checkNotNull(VerifiedFilterRuntimeLease.takeOpenedOrNull(configuration, identity, false,
                FilterArtifactReference(prepared.sha256, prepared.identity), opened.artifact))
                .also { it.claimExclusiveRuntimeOwnership() }
        }
        override fun close() { prepared.close(); root.deleteRecursively() }
        companion object {
            fun create(): FilterFixture = runBlocking {
                val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "chained-dns-filter-${UUID.randomUUID()}").apply { mkdirs() }
                val configuration = FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList()))
                val revision = "chained-loopback-fixture"
                val sha = "b".repeat(64)
                val identity = ConfigurationIdentity(41, canonicalFilterConfigurationFingerprint(configuration, revision, sha))
                val prepared = FilterCompiler(File(root, "scratch")).compile(FilterCompileRequest(
                    ArtifactConfigurationIdentity(identity.generation, identity.fingerprint), emptyList(), revision, sha,
                    maxRules = 0, intentionalEmpty = true))
                FilterFixture(root, prepared, configuration, identity)
            }
        }
    }

    companion object {
        private val QUERY = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
        private fun answer(query: ByteArray) = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
        private fun ipv6ChecksumSum(packet: ByteArray): Int {
            var sum = (packet.size - 40) + 17
            for (offset in 8 until packet.size step 2) {
                sum += ((packet[offset].toInt() and 255) shl 8) +
                    if (offset + 1 < packet.size) packet[offset + 1].toInt() and 255 else 0
            }
            while (sum > 0xffff) sum = (sum and 0xffff) + (sum ushr 16)
            return sum
        }
        private fun ip(address: String) = ChainedStackAddressTranslation.octets(address)
        private fun profile(full: Boolean) = ChainedUpstreamConfiguration.create("192.0.2.1", 51820,
            Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 }), "10.7.0.2",
            listOf(if (full) "0.0.0.0/0" else "10.0.0.0/8"), 0, 1420, listOf("10.64.0.1"))
    }
}
