package com.lavasec.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverSmokeProbeCadence
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.platform.ConfigurationIdentity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual owned engine/composition/UDP/peer and cold smoke producer. The controlled policy is a
 * throwing sentinel: a smoke query must never enter the product filter/TUN publication path.
 * Socket-protection authorization is the explicit fixture seam; real captured-network binding,
 * UDP source tuples, exact DNS validation, cancellation and health admission remain production.
 * Clock advancement schedules due probes without waiting five minutes and never sets a wedge.
 * No external resolver, Plus entitlement, VPN service state, live provider or TUN receipt is claimed.
 */
@RunWith(AndroidJUnit4::class)
class DeviceResolverSmokeProbeInstrumentationTest {
    @Test fun coldDeviceRejectionsRotateRealCanariesAndQualifyOnlyItsCurrentReconnectEpisode(): Unit = runBlocking {
        Fixture().use { f ->
            f.probes.install(f.engine)
            f.awaitFailures(1)
            assertFalse(f.health.deviceResolverReconnectEpisodeIsActive(f.engine.epoch))
            repeat(2) { index ->
                f.advanceRoutine()
                f.awaitFailures(index + 2)
            }
            assertEquals(listOf("example.com", "apple.com", "cloudflare.com"), f.peer.queries.map {
                DnsMessage.parseQuestion(it.body).normalizedDomain
            })
            assertEquals(listOf(1L), f.reconnects)
            assertTrue(f.health.deviceResolverReconnectEpisodeIsActive(f.engine.epoch))
            assertEquals(0, f.rescueAttempts.get())
            val predecessor = f.engine
            f.peer.rcode.set(0)
            f.adoptReconnect()
            withTimeout(5_000) { while (f.health.current().smokeProbeFailureCount != 0) delay(10) }
            assertFalse(predecessor.isCurrent())
            assertFalse(f.health.deviceResolverReconnectEpisodeIsActive(predecessor.epoch))
            assertFalse(f.health.deviceResolverReconnectEpisodeIsActive(f.engine.epoch))
            assertEquals("example.com", DnsMessage.parseQuestion(f.peer.queries.last().body).normalizedDomain)
            assertEquals(0, f.rescueAttempts.get())
            assertTrue(f.peer.queries.all { it.source.address == f.address && it.source.port > 0 })
            assertTrue(f.sockets.isNotEmpty())
            assertEquals(f.sockets.size, f.bindings.get())

            // A real outstanding socket query is cancelled without a synthetic timeout verdict.
            f.peer.rcode.set(-1)
            val before = f.peer.queries.size
            f.advanceRoutine()
            withTimeout(5_000) { while (f.peer.queries.size == before) delay(10) }
            f.probes.close()
            f.engine.close()
            withTimeout(5_000) { while (f.sockets.any { !it.isClosed }) delay(10) }
            assertEquals(0, f.health.current().smokeProbeFailureCount)
            val query = DnsResolverSmokeProbe.query(0, 19)
            val stale = f.engine.resolvePrimaryIfCurrent(ResolverRequest(query, DnsMessage.parseQuestion(query),
                f.engine.epoch, f.context, 19)) as ResolverResult.Failure
            assertEquals(ResolverFailureReason.STALE_EPOCH, stale.reason)
            assertEquals(before + 1, f.peer.queries.size)
            assertEquals(0, f.rescueAttempts.get())
            f.health.recordConnectionChanged(f.engine.epoch.engineGeneration, false)
            assertFalse(f.health.deviceResolverReconnectEpisodeIsActive(f.engine.epoch))
        }
    }

    private class Fixture : AutoCloseable {
        private val connectivity = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private fun eligible(network: Network): Boolean = connectivity.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
        private val network = connectivity.activeNetwork?.takeIf(::eligible)
            ?: connectivity.allNetworks.firstOrNull(::eligible)
            ?: error("emulator requires a captured underlying network")
        val address = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && DeviceDnsAddressPolicy.isUsable(it) }
            ?: error("emulator requires a usable local underlying IPv4")
        val context = NetworkContext(network, 1, listOf(address),
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
        val peer = Peer(address)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val offset = AtomicLong()
        private val ids = AtomicLong()
        val reconnects = CopyOnWriteArrayList<Long>()
        val health = ExactSessionHealthOwner(1, { System.currentTimeMillis() + offset.get() }, {}, reconnects::add)
        val rescueAttempts = AtomicInteger()
        val sockets = CopyOnWriteArrayList<DatagramSocket>()
        val bindings = AtomicInteger()
        private val socketPlatform = JavaNetDeviceDnsSocketPlatform(object : OrdinaryVpnSocketProtector {
            override fun protect(socket: DatagramSocket): Boolean { sockets += socket; return true }
            override fun protect(socket: Socket): Boolean = error("this peer returns complete UDP replies")
        }, object : ExactNetworkSocketBinder {
            override fun bind(captured: Network, socket: DatagramSocket) {
                assertEquals(network, captured)
                assertTrue(sockets.contains(socket))
                captured.bindSocket(socket)
                bindings.incrementAndGet()
            }
            override fun bind(captured: Network, socket: Socket) = error("this peer returns complete UDP replies")
        })
        private val selection = ResolverSelection(listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH,
                customDohUrl = "https://unused-controlled.example/dns-query"))))
        private val plan = (ResolverPlanFactory.build(checkNotNull(selection.toFilterResolverPlan()).configuration, context)
            as ResolverPlanBuildResult.Ready).plan
        private val factory = ProductionReadyDnsResolverFactory(
            deviceRouteFactory = ReadyDnsDeviceRouteFactory { _, _, _, registry ->
                DeviceDnsTransport(socketPlatform, registry, dnsPort = peer.port)
            },
            mullvadRuntimeFactory = ReadyDnsMullvadRuntimeFactory { _, _, _, _ ->
                object : ReadyDnsMullvadRuntime {
                    override val resolver = DnsResolver {
                        rescueAttempts.incrementAndGet()
                        error("a primary-only smoke probe must never contact rescue")
                    }
                    override fun close() = Unit
                }
            },
            coordinatorFactory = ReadyDnsCoordinatorFactory { epoch, _, exactPlan, registry, device, encrypted, wedge ->
                ResolverCoordinator(epoch, exactPlan, registry, device, encrypted, wedge)
            },
            wedgeEvidence = DeviceResolverWedgeEvidence(health::deviceResolverReconnectEpisodeIsActive),
        )
        var engine = createEngine(1)
            private set
        val probes = ResolverSmokeProbeOwner(scope, { System.currentTimeMillis() + offset.get() }, ids::incrementAndGet,
            { it === engine && it.isCurrent() }, { health })
        init { installHealth(engine) }
        fun advanceRoutine() { offset.addAndGet(ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS + 1); probes.tick() }
        suspend fun awaitFailures(expected: Int) = withTimeout(5_000) {
            while (health.current().smokeProbeFailureCount != expected) delay(10)
        }
        fun adoptReconnect() {
            val generation = engine.epoch.engineGeneration + 1
            engine.close()
            engine = createEngine(generation)
            installHealth(engine)
            assertTrue(health.deviceResolverReconnectEpisodeIsActive(engine.epoch))
            probes.install(engine)
        }
        private fun installHealth(engine: ReadyDnsEngine) = health.installEngine(VpnHealthEngineIdentity(1, 1,
            engine.epoch.engineGeneration, plan.primaryResolverIdentity, plan.fallbackCoverage),
            devicePrimaryEpoch = engine.epoch, primaryEpoch = engine.epoch)
        private fun createEngine(generation: Long): ReadyDnsEngine {
            val policy = SentinelPolicy()
            val epoch = EngineEpoch(1, policy.identity.configurationIdentity, policy.identity.artifactDigest,
                policy.identity.revision, 1, generation, plan.primaryResolverIdentity, plan.fallbackCoverage)
            return ReadyDnsEngineOwner.create(ReadyDnsEngineCandidate(epoch, context, plan, policy), factory)
        }
        override fun close() {
            probes.close()
            try { engine.close() } finally { scope.cancel(); peer.close() }
            assertTrue("owned smoke sockets must be physically closed", sockets.all { it.isClosed })
        }
    }

    private class SentinelPolicy : OwnedDnsPolicyRuntime {
        override val identity = ProxyPolicyIdentity(ConfigurationIdentity(1, "controlled-smoke"), "d".repeat(64), 1, 1)
        override val proxy = object : DnsProxy {
            override fun plan(packet: ByteArray, engine: ReadyDnsEngine): ProxyPlan = error("smoke entered product query policy")
            override fun planUnavailable(packet: ByteArray, reason: ProtectionUnavailableReason): ProxyPlan = error("smoke entered product query policy")
            override fun rejectOverloaded(plan: ProxyPlan.Forward): ProxyResult = error("smoke entered product query policy")
            override suspend fun execute(plan: ProxyPlan.Forward): ProxyResult = error("smoke entered product query policy")
        }
        override fun checkRetirementAllowed() = Unit
        override fun beginRetirement() = Unit
        override fun close() = Unit
    }

    private class Peer(address: InetAddress) : AutoCloseable {
        private val socket = DatagramSocket(InetSocketAddress(address, 0)).apply { soTimeout = 500 }
        val port = socket.localPort
        val rcode = AtomicInteger(5)
        data class Query(val body: ByteArray, val source: InetSocketAddress)
        val queries = CopyOnWriteArrayList<Query>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread({
            while (!socket.isClosed) try {
                val packet = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(packet)
                val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                queries += Query(query, InetSocketAddress(packet.address, packet.port))
                val code = rcode.get()
                if (code >= 0) {
                    val response = if (code == 0) DnsMessage.bootstrapResponse(query,
                        byteArrayOf(8, 8, 8, 8), ByteArray(16).also { it[15] = 1 }) else query.copyOf().apply {
                        this[2] = 0x81.toByte(); this[3] = (0x80 or code).toByte()
                    }
                    socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                }
            } catch (_: SocketTimeoutException) {
                // Bounded receive permits fixture shutdown; never emits resolver-health evidence.
            } catch (error: Throwable) {
                if (!socket.isClosed) failure.set(error)
                return@Thread
            }
        }, "emulator-cold-smoke-peer").apply { isDaemon = true; start() }
        override fun close() {
            socket.close(); worker.join(2000)
            assertFalse("controlled smoke peer leaked", worker.isAlive)
            failure.get()?.let { throw AssertionError("controlled smoke peer failed", it) }
        }
    }
}
