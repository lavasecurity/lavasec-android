package com.lavasec.app.vpn.qa

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.chained.ChainedOwnResolverPorts
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.vpn.LavaVpnRuntimeBridge
import com.lavasec.app.vpn.ChainedEngineQueue
import com.lavasec.app.vpn.TunPacketSession
import com.lavasec.app.vpn.TunRead
import com.lavasec.app.vpn.TunWritable
import com.lavasec.app.vpn.TunWriteAttempt
import com.lavasec.app.vpn.chained.ChainedClientDns
import com.lavasec.app.vpn.chained.ChainedDataPath
import com.lavasec.app.vpn.chained.ChainedDnsResponse
import com.lavasec.app.vpn.chained.ChainedPeerDatagram
import com.lavasec.app.vpn.chained.ChainedUpstreamChannel
import com.lavasec.app.vpn.chained.EngineQueueChainedDispatcher
import com.lavasec.app.vpn.chained.WireGuardSession
import com.lavasec.app.vpn.engine.LavaWgResponse
import com.lavasec.app.vpn.engine.LavaWgStats
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/**
 * Actual DEBUG emitter/OS datagram and production chained dispatcher/resource gates. The numeric
 * numeric target is the emulator host alias 10.0.2.2 at the unchanged production UDP53 port.
 * A runner-owned host peer receives on loopback UDP53; no privileged Android bind is attempted.
 * Network.bindSocket, UDP connect/send and peer-observed bytes are real. The host source tuple
 * is after emulator NAT and is explicitly distinct from the receipt's Android local tuple.
 *
 * Explicit controlled seams: protect(int) records/refuses admission instead of invoking Android
 * VPN consent; WireGuardSession supplies named controlled handshake stats, not a JNI/live peer;
 * the service-current Boolean and synchronous arm channel are fixtures because the credential owner belongs to the app
 * module (its durable/disarm lock has separate app tests). No VPN service is started, no entitlement
 * is fabricated, and this is not shipping Plus/handshake/physical off-device leak qualification.
 * The canary never enters a resolver, the TUN, or protection-health publication.
 */
@RunWith(AndroidJUnit4::class)
class DnsLeakCanaryInstrumentationTest {
    @Test
    fun debugCanaryEmitsExactPhysicalUdpOnlyForCurrentArmedEstablishedAttempt() {
        Fixture().use { fixture ->
            assertNull(fixture.path.canaryGenerationIfEstablished())
            fixture.path.start()
            assertNull(fixture.path.canaryGenerationIfEstablished())
            fixture.establishControlledHandshake()
            assertEquals(0L, fixture.path.canaryGenerationIfEstablished())
            val resource = fixture.prepare(0)
            val result = fixture.send(0, resource) as? DnsLeakCanarySend.Accepted
                ?: throw AssertionError("actual canary syscall was not accepted")
            fixture.path.releaseCanary(resource)
            val packet = fixture.peer.receive()
            fixture.assertExactReceipt(result.receipt, packet, 0)
            assertEquals(result.receipt, fixture.armChannel.lastReceipt)
            assertEquals(fixture.arm, fixture.armChannel.captureArm()) // Sending never disarms.
            assertEquals(1, resource.closes)
            assertNull(fixture.path.canaryGenerationIfEstablished())
            fixture.peer.assertNoDatagram()
            fixture.assertNoProtectionPublication()
        }
    }

    @Test
    fun debugCanaryRefusesDisarmCancellationAndCloseAfterRealSocketPreparation() {
        for (reason in listOf("disarm", "rearmed-nonce", "cancelled-owner", "closed-attempt")) {
            Fixture().use { fixture ->
                fixture.path.start(); fixture.establishControlledHandshake()
                val resource = fixture.prepare(0)
                when (reason) {
                    "disarm" -> fixture.armChannel.disarm()
                    "rearmed-nonce" -> fixture.armChannel.rearm(fixture.arm.copy(
                        nonce = (if (fixture.arm.nonce.first() == '0') "1" else "0") + fixture.arm.nonce.drop(1)))
                    "cancelled-owner" -> fixture.ownerCurrent.set(false)
                    else -> fixture.path.close()
                }
                assertEquals(reason, DnsLeakCanarySend.NoDatagramSent, fixture.send(0, resource))
                fixture.path.releaseCanary(resource)
                assertEquals(1, resource.closes)
                assertEquals(DnsLeakCanarySend.NoDatagramSent, resource.delegate.send(0))
                assertNull(fixture.armChannel.lastReceipt)
                fixture.peer.assertNoDatagram()
                fixture.assertNoProtectionPublication()
            }
        }
    }

    @Test
    fun stalePreparedAttemptCannotSendOrCloseSuccessorAndTransportRebindNeverRefires() {
        Fixture().use { fixture ->
            fixture.path.start(); fixture.establishControlledHandshake()
            val old = fixture.prepare(0)
            fixture.path.shutdown()
            assertEquals(1, old.closes)
            assertEquals(DnsLeakCanarySend.NoDatagramSent, old.delegate.send(0))
            fixture.path.replaceSession(ControlledHandshakeSession(hasHandshake = true))
            fixture.path.tick()
            assertEquals(1L, fixture.path.canaryGenerationIfEstablished())
            val successor = fixture.prepare(1)
            assertEquals(DnsLeakCanarySend.NoDatagramSent, fixture.send(0, old))
            fixture.path.releaseCanary(old)
            assertEquals(0, successor.closes)
            fixture.peer.assertNoDatagram()
            val accepted = fixture.send(1, successor) as? DnsLeakCanarySend.Accepted
                ?: throw AssertionError("successor's actual canary syscall was not accepted")
            fixture.path.releaseCanary(successor)
            fixture.assertExactReceipt(accepted.receipt, fixture.peer.receive(), 1)
            fixture.path.adoptChannel(NoWirePeer())
            assertNull(fixture.path.canaryGenerationIfEstablished())
            val refused = fixture.resource()
            assertFalse(fixture.path.adoptCanary(1, refused))
            assertEquals(1, refused.closes)
            assertEquals(DnsLeakCanarySend.NoDatagramSent, refused.delegate.send(1))
            fixture.peer.assertNoDatagram()
            fixture.assertNoProtectionPublication()
        }
    }

    @Test
    fun absentHandshakeAndRefusedProtectionNeverSendAndNoSendRetryUsesSameSession() {
        Fixture().use { fixture ->
            fixture.path.start(); fixture.path.tick() // Explicit no-handshake stats.
            val cold = fixture.resource()
            assertFalse(fixture.path.adoptCanary(0, cold))
            assertEquals(0, fixture.protector.protectCalls)
            assertEquals(1, cold.closes)
            fixture.establishControlledHandshake()
            fixture.protector.admit = false
            val refused = fixture.resource()
            assertTrue(fixture.path.adoptCanary(0, refused))
            assertFalse(refused.prepare())
            fixture.path.releaseCanary(refused)
            assertEquals(DnsLeakCanarySend.NoDatagramSent, refused.delegate.send(0))
            assertEquals(0L, fixture.path.canaryGenerationIfEstablished())
            fixture.peer.assertNoDatagram()
            fixture.protector.admit = true
            val retry = fixture.prepare(0)
            val accepted = fixture.send(0, retry) as? DnsLeakCanarySend.Accepted
                ?: throw AssertionError("same-session no-send retry was not accepted")
            fixture.path.releaseCanary(retry)
            fixture.assertExactReceipt(accepted.receipt, fixture.peer.receive(), 0)
            assertEquals(2, fixture.protector.protectCalls)
            fixture.assertNoProtectionPublication()
        }
    }

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network: Network = connectivity.activeNetwork?.takeIf(::eligible)
            ?: connectivity.allNetworks.firstOrNull(::eligible)
            ?: throw AssertionError("controlled canary requires an INTERNET + NOT_VPN Android Network")
        private val addresses = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.filter { it is Inet4Address && !it.isAnyLocalAddress && !it.isLoopbackAddress }
            ?.takeIf { it.isNotEmpty() }
            ?: throw AssertionError("controlled canary requires captured ordinary IPv4 addresses")
        val peer = HostUdp53Peer(network)
        val arm = DnsLeakCanaryArm(peer.nonce, "10.0.2.2")
        val armChannel = ControlledArmChannel(arm)
        val ownerCurrent = AtomicBoolean(true)
        val protector = ControlledProtector(context)
        private val executor = Executors.newSingleThreadExecutor()
        private val queue = ChainedEngineQueue(executor)
        private val dispatcher = EngineQueueChainedDispatcher(queue)
        private val session = ControlledHandshakeSession()
        private val tun = UnusedTun()
        private val dns = UnusedClientDns()
        private val healthBefore = LavaVpnRuntimeBridge.health.value
        private val activityBefore = LavaVpnRuntimeBridge.chainedActivity.value
        private val profile = ChainedUpstreamConfiguration.create(
            endpointHost = "192.0.2.1", endpointPort = 51820,
            peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 }),
            clientAddress = "10.7.0.2", allowedIPs = listOf("0.0.0.0/0"),
            persistentKeepaliveSeconds = 0, interfaceMTU = 1420, dnsAddresses = listOf("10.64.0.1"),
        )
        val path = ChainedDataPath(profile, ChainedRoutePlan.make(profile), tun, NoWirePeer(), session,
            ChainedOwnResolverPorts({ 0 }), dns, dispatcher, { 0 })

        private fun eligible(network: Network): Boolean {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        fun establishControlledHandshake() {
            dispatcher.run { session.hasHandshake = true }
            path.tick()
        }

        fun resource(): RecordingPrepared = RecordingPrepared(DnsLeakCanaryEmitterFactory.create(
            protector, network, arm, serviceGeneration = 17, nowMillis = { 1_000 },
        ))

        fun prepare(generation: Long): RecordingPrepared = resource().also {
            assertTrue(path.adoptCanary(generation, it))
            assertTrue("real protected/bound UDP53 preparation failed", it.prepare())
        }

        fun send(generation: Long, resource: RecordingPrepared): DnsLeakCanarySend = path.runCanary(generation, resource) {
            armChannel.sendIfCurrent(arm, ownerCurrent::get) { resource.send(generation) }
        }

        fun assertExactReceipt(receipt: DnsLeakCanaryReceipt, packet: ObservedDatagram, generation: Long) {
            assertArrayEquals(DnsLeakCanary.query(arm.nonce), packet.bytes)
            assertEquals(arm, receipt.arm)
            assertEquals("UDP_SEND_ACCEPTED", receipt.outcome)
            assertEquals(53, receipt.destinationPort)
            assertEquals(17L, receipt.serviceGeneration)
            assertEquals(generation, receipt.chainedSessionGeneration)
            assertEquals(network.networkHandle, receipt.networkHandle)
            assertEquals(packet.bytes.size, receipt.acceptedBytes)
            assertEquals("10.0.2.2", receipt.arm.resolverIP)
            assertTrue("Receipt retains the actual pre-NAT Android Network address",
                receipt.localAddress in addresses.map { it.hostAddress })
            assertTrue(requireNotNull(receipt.localPort) > 0)
            assertTrue("Host-observed tuple belongs to host loopback, after emulator NAT", packet.source.address.isLoopbackAddress)
            assertTrue(packet.source.port > 0)
            assertEquals("127.0.0.1", packet.destination.address.hostAddress)
            assertEquals(53, packet.destination.port)
            assertTrue(protector.protectCalls > 0)
            println("LAVA_UI_CANARY_UDP android-source=${receipt.localAddress}:${receipt.localPort} " +
                "android-destination=10.0.2.2:53 host-source=${packet.source} host-destination=${packet.destination} " +
                "tuple-scope=host-after-emulator-NAT same-tuple-claim=false exact-bytes=${packet.bytes.size} no-reply=true")
        }

        fun assertNoProtectionPublication() {
            assertEquals(0, tun.writes)
            assertEquals(0, dns.clientQueries)
            assertEquals(0, dns.inboundPackets)
            assertEquals(healthBefore, LavaVpnRuntimeBridge.health.value)
            assertEquals(activityBefore, LavaVpnRuntimeBridge.chainedActivity.value)
        }

        override fun close() {
            try { path.close() } finally {
                try { peer.close() } finally { queue.close(); executor.shutdownNow() }
            }
        }
    }

    /** Only the protection permission boundary is controlled; Android Network/FD APIs are real. */
    private class ControlledProtector(context: Context) : VpnService() {
        var admit = true
        var protectCalls = 0
        init { attachBaseContext(context) }
        override fun protect(socket: Int): Boolean {
            protectCalls++
            ParcelFileDescriptor.fromFd(socket).use { duplicate ->
                val beforeBind = Os.getsockname(duplicate.fileDescriptor) as InetSocketAddress
                assertEquals("protect must precede bind/connect", 0, beforeBind.port)
                assertTrue(beforeBind.address.isAnyLocalAddress)
            }
            return admit
        }
    }

    /** Test-only substitute for the app-owned durable Arm/Disarm port, explicitly no storage. */
    private class ControlledArmChannel(initial: DnsLeakCanaryArm) : DnsLeakCanaryChannel {
        private val lock = ReentrantLock()
        private var arm: DnsLeakCanaryArm? = initial
        var lastReceipt: DnsLeakCanaryReceipt? = null
            private set
        override fun captureArm(): DnsLeakCanaryArm? = lock.withLock { arm }
        fun disarm() = lock.withLock { arm = null; lastReceipt = null }
        fun rearm(value: DnsLeakCanaryArm) = lock.withLock { arm = value; lastReceipt = null }
        override fun sendIfCurrent(capturedArm: DnsLeakCanaryArm, isCurrent: () -> Boolean,
            send: () -> DnsLeakCanarySend): DnsLeakCanarySend = lock.withLock {
            if (arm != capturedArm || !isCurrent()) DnsLeakCanarySend.NoDatagramSent else send().also {
                if (it is DnsLeakCanarySend.Accepted) lastReceipt = it.receipt
            }
        }
    }

    private class RecordingPrepared(val delegate: PreparedDnsLeakCanary) : PreparedDnsLeakCanary {
        var closes = 0
            private set
        private var closed = false
        override fun prepare() = delegate.prepare()
        override fun send(chainedSessionGeneration: Long) = delegate.send(chainedSessionGeneration)
        override fun close() { if (!closed) { closed = true; closes++; delegate.close() } }
    }

    /** Named fixture stats only: does not allocate JNI or claim a live handshake. */
    private class ControlledHandshakeSession(var hasHandshake: Boolean = false) : WireGuardSession {
        override var isOpen = true
            private set
        override fun encapsulate(packet: ByteArray) = LavaWgResponse.None
        override fun decapsulate(packet: ByteArray, sourceAddress: ByteArray) = LavaWgResponse.None
        override fun tick() = LavaWgResponse.None
        override fun forceHandshake() = LavaWgResponse.None
        override fun stats() = LavaWgStats(if (hasHandshake) 0L else -1L, 0, 0, -1, 0f)
        override fun close() { isOpen = false }
    }

    private class NoWirePeer : ChainedUpstreamChannel {
        override fun send(datagram: ByteArray): Boolean = throw AssertionError("canary must never ride WireGuard")
        override suspend fun receive(): ChainedPeerDatagram? = null
        override fun close() = Unit
    }

    private class UnusedTun : TunPacketSession {
        var writes = 0
        override suspend fun readPacket(into: ByteArray) = TunRead.Closed
        override fun tryWritePacket(packet: ByteArray): TunWriteAttempt { writes++; return TunWriteAttempt.Written }
        override suspend fun awaitWritable() = TunWritable.Closed
        override fun close() = Unit
    }

    private class UnusedClientDns : ChainedClientDns {
        var clientQueries = 0
        var inboundPackets = 0
        override fun bind(sendQuery: (ByteArray) -> Unit, writeClientResponse: (ChainedDnsResponse) -> Unit) = Unit
        override fun handleQuery(packet: ByteArray) { clientQueries++ }
        override fun observeInbound(packet: ByteArray): Boolean { inboundPackets++; return false }
        override fun expire() = Unit
        override fun close() = Unit
    }

    private data class ObservedDatagram(val bytes: ByteArray, val source: InetSocketAddress, val destination: InetSocketAddress)

    /** Test-only host readback. Never acts as a resolver, replies, or sends a canary itself. */
    private class HostUdp53Peer(private val network: Network) : AutoCloseable {
        val nonce = UUID.randomUUID().toString().replace("-", "").take(10)
        private val host = InetAddress.getByAddress(byteArrayOf(10, 0, 2, 2))
        private val arguments = InstrumentationRegistry.getArguments()
        private val port = arguments.getString("canaryPeerControlPort")?.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: throw AssertionError("Canary fixture requires the runner's ready host UDP53 peer; Android app binding UDP53 is forbidden")
        private val token = arguments.getString("canaryPeerControlToken")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw AssertionError("Canary fixture requires a private current host-peer control token")
        private val initial = request("begin")
        private val window = initial.getString("window")
        private var cursor = initial.getLong("cursor")

        private fun request(operation: String, cursorValue: Long? = null, waitMillis: Int? = null): JSONObject {
            val payload = JSONObject().put("token", token).put("op", operation).put("nonce", nonce)
            if (cursorValue != null) payload.put("window", window).put("cursor", cursorValue)
            if (waitMillis != null) payload.put("waitMillis", waitMillis)
            return Socket().use { control ->
                network.bindSocket(control)
                control.soTimeout = 7_000
                control.connect(InetSocketAddress(host, port), 3_000)
                control.getOutputStream().write((payload.toString() + "\n").toByteArray(Charsets.UTF_8))
                control.getOutputStream().flush()
                val line = control.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                    ?: throw AssertionError("Host UDP53 readback ended without an observation")
                val result = JSONObject(line)
                assertTrue("Host UDP53 peer refused current capture/readback: ${result.optString("error")}", result.optBoolean("ok"))
                assertEquals(1, result.getInt("protocol"))
                result
            }
        }

        fun receive(): ObservedDatagram {
            val packets = request("read", cursor, 5_000).getJSONArray("packets")
            assertEquals("Exact one actual host datagram for the current canary", 1, packets.length())
            val packet = packets.getJSONObject(0)
            assertEquals(nonce, packet.getString("nonce"))
            assertEquals("host-after-emulator-NAT", packet.getString("tupleScope"))
            cursor = packet.getLong("sequence")
            return ObservedDatagram(Base64.getDecoder().decode(packet.getString("payloadBase64")),
                InetSocketAddress(InetAddress.getByAddress(requireNotNull(DnsLeakCanary.resolverBytes(
                    packet.getString("hostSourceAddress")))), packet.getInt("hostSourcePort")),
                InetSocketAddress(InetAddress.getByAddress(requireNotNull(DnsLeakCanary.resolverBytes(
                    packet.getString("hostDestinationAddress")))), packet.getInt("hostDestinationPort")))
        }

        fun assertNoDatagram() {
            val packets = request("read", cursor, 250).getJSONArray("packets")
            assertEquals("Refused canary must emit no actual cleartext UDP datagram, including a rearmed nonce", 0, packets.length())
        }

        override fun close() {
            try { assertNoDatagram() } finally { request("finish", cursor) }
        }
    }
}
