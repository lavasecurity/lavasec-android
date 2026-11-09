package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.vpn.TunPacketSession
import com.lavasec.app.vpn.TunRead
import com.lavasec.app.vpn.TunWriteAttempt
import com.lavasec.app.vpn.TunWritable
import com.lavasec.app.vpn.engine.LavaWgOp
import com.lavasec.app.vpn.engine.LavaWgResponse
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition's packet-path wiring, driven synchronously. Everything here is fake: no JNI,
 * no socket, no Android, so the ordering is executable in the host JVM.
 */
class ChainedDataPathTest {

    private val closeOrder = mutableListOf<String>()

    @Test fun `debug auxiliary send requires actual handshake and fires once even in session zero`() {
        val native = FakeWireGuardSession().apply { statsValue = EnginePacketFixtures.establishedStats() }
        val path = dataPath(native)
        assertEquals(null, path.canaryGenerationIfEstablished())
        path.start()
        assertEquals(null, path.canaryGenerationIfEstablished()) // Forced handshake is not receipt.
        path.tick()
        assertEquals(0L, path.canaryGenerationIfEstablished())
        val first = CanaryResource()
        assertTrue(path.adoptCanary(0, first))
        assertEquals(null, path.canaryGenerationIfEstablished()) // One pending FD owner.
        var sends = 0
        assertEquals(com.lavasec.app.vpn.qa.DnsLeakCanarySend.Indeterminate,
            path.runCanary(0, first) { sends++; com.lavasec.app.vpn.qa.DnsLeakCanarySend.Indeterminate })
        path.releaseCanary(first)
        assertEquals(1, first.closes)
        assertEquals(null, path.canaryGenerationIfEstablished())
        val refused = CanaryResource()
        assertEquals(false, path.adoptCanary(0, refused))
        assertEquals(1, refused.closes)
        assertEquals(1, sends)
        path.close()
    }

    @Test fun `no-datagram retry survives but transport rebind does not refire an accepted native session`() {
        val native = FakeWireGuardSession().apply { statsValue = EnginePacketFixtures.establishedStats() }
        val path = dataPath(native)
        path.start(); path.tick()
        val first = CanaryResource()
        assertTrue(path.adoptCanary(0, first))
        assertEquals(com.lavasec.app.vpn.qa.DnsLeakCanarySend.NoDatagramSent,
            path.runCanary(0, first) { com.lavasec.app.vpn.qa.DnsLeakCanarySend.NoDatagramSent })
        path.releaseCanary(first)
        assertEquals(0L, path.canaryGenerationIfEstablished())
        val accepted = CanaryResource()
        assertTrue(path.adoptCanary(0, accepted))
        val arm = com.lavasec.app.vpn.qa.DnsLeakCanaryArm("a01b02c03d", "192.168.1.53")
        val receipt = com.lavasec.app.vpn.qa.DnsLeakCanaryReceipt(arm, 3, 0, 101,
            com.lavasec.app.vpn.qa.DnsLeakCanary.query(arm.nonce).size, 1, null, null)
        path.runCanary(0, accepted) { com.lavasec.app.vpn.qa.DnsLeakCanarySend.Accepted(receipt) }
        path.releaseCanary(accepted)
        path.adoptChannel(ScriptedPeerSocket())
        assertEquals(null, path.canaryGenerationIfEstablished())
        path.replaceSession(FakeWireGuardSession().apply { statsValue = EnginePacketFixtures.establishedStats() })
        assertEquals(null, path.canaryGenerationIfEstablished())
        path.tick()
        assertEquals(1L, path.canaryGenerationIfEstablished())
        path.close()
    }

    @Test fun `attempt retirement closes pending canary and old completion cannot seal a successor`() {
        val path = dataPath(FakeWireGuardSession().apply { statsValue = EnginePacketFixtures.establishedStats() })
        path.start(); path.tick()
        val old = CanaryResource()
        assertTrue(path.adoptCanary(0, old))
        path.shutdown()
        assertEquals(1, old.closes)
        var staleSends = 0
        path.runCanary(0, old) { staleSends++; com.lavasec.app.vpn.qa.DnsLeakCanarySend.Indeterminate }
        assertEquals(0, staleSends)
        path.replaceSession(FakeWireGuardSession().apply { statsValue = EnginePacketFixtures.establishedStats() })
        path.tick()
        val fresh = CanaryResource()
        assertTrue(path.adoptCanary(1, fresh))
        path.releaseCanary(old)
        path.runCanary(0, old) { staleSends++; com.lavasec.app.vpn.qa.DnsLeakCanarySend.Indeterminate }
        assertEquals(0, fresh.closes)
        path.runCanary(1, fresh) { com.lavasec.app.vpn.qa.DnsLeakCanarySend.NoDatagramSent }
        path.releaseCanary(fresh)
        assertEquals(1L, path.canaryGenerationIfEstablished())
        val closing = CanaryResource()
        assertTrue(path.adoptCanary(1, closing))
        path.close()
        assertEquals(1, closing.closes)
        assertEquals(null, path.canaryGenerationIfEstablished())
    }

    private class CanaryResource : com.lavasec.app.vpn.qa.PreparedDnsLeakCanary {
        var closes = 0
        private var closed = false
        override fun prepare() = !closed
        override fun send(chainedSessionGeneration: Long) = com.lavasec.app.vpn.qa.DnsLeakCanarySend.NoDatagramSent
        override fun close() { if (!closed) { closed = true; closes++ } }
    }

    @Test
    fun `installed split resolver floor filters public DNS and refuses only its encrypted destinations`() {
        val base = configuration()
        val split = ChainedUpstreamConfiguration.create(
            "vpn.example.com", 51820, base.peerPublicKey, base.clientAddress,
            listOf("10.64.0.0/16"), 0, 1420, base.dnsAddresses,
        )
        val captured = mutableListOf("4.2.2.2")
        val installed = (ChainedTunPlan.resolve(split, captured) as ChainedTunPlanResolution.Ready).plan
        captured.clear() // Link-properties changes cannot silently mutate an installed classifier.
        val session = FakeWireGuardSession().apply { statsValue = EnginePacketFixtures.establishedStats() }
        val dns = RecordingDnsSink()
        val path = dataPath(session, dns = dns, configurationOverride = split, installedPlanOverride = installed)
        path.start()
        for (destination in listOf("8.8.8.8", "4.2.2.2")) {
            assertTrue(installed.includedIPv4Routes.any { it.cidr == "$destination/32" })
            path.onTunPacket(EnginePacketFixtures.dnsQueryPacket(destination = destination))
            path.onTunPacket(EnginePacketFixtures.ordinaryTcpPacket(destination, 853))
        }
        path.onTunPacket(com.lavasec.app.foundation.chained.ChainedInnerUdpPacket.build(
            java.net.InetAddress.getByName("fd00:1a7a::2").address,
            java.net.InetAddress.getByName("2606:4700:4700::1111").address, 40000, 53, 0, ByteArray(12) { 0x11 },
        ))
        assertEquals(3, dns.packets.size)
        assertTrue(session.encapsulated.isEmpty())
        // A resolver the plan did not claim retains the split transport disposition.
        path.onTunPacket(EnginePacketFixtures.ordinaryTcpPacket("4.2.2.3", 853))
        assertTrue(session.encapsulated.isEmpty()) // Ordinary traffic waits for the real engine state.
        path.tick() // The controlled engine's established stats release that queued packet.
        assertEquals(1, session.encapsulated.size)
        path.close()
    }

    @Test
    fun `nested selection still filters client DNS before either engine sees configuration traffic`() {
        val base = configuration()
        val entry = ChainedUpstreamConfiguration.create("192.0.2.1", 51820, base.peerPublicKey, "10.1.0.2", listOf("0.0.0.0/0"), 0, 1420, base.dnsAddresses)
        val exit = ChainedUpstreamConfiguration.create("192.0.2.2", 51820, base.peerPublicKey, base.clientAddress, listOf("0.0.0.0/0"), 0, 1420, base.dnsAddresses)
        val first = FakeWireGuardSession().apply { statsValue = EnginePacketFixtures.establishedStats(); encapsulateResult = { LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(4)) } }
        val second = FakeWireGuardSession().apply { statsValue = EnginePacketFixtures.establishedStats(); encapsulateResult = { LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(4)) } }
        val stack = ChainedStackWireGuardSession(exit.withEntryHop(entry), listOf(first, second))
        val dns = RecordingDnsSink()
        val peer = ScriptedPeerSocket()
        val path = dataPath(stack, peer, dns = dns, configurationOverride = exit.withEntryHop(entry))
        path.tick()
        path.onTunPacket(EnginePacketFixtures.dnsQueryPacket())
        assertEquals(1, dns.packets.size)
        assertTrue(first.encapsulated.isEmpty())
        assertTrue(second.encapsulated.isEmpty())
        path.onTunPacket(EnginePacketFixtures.ordinaryTcpPacket())
        assertEquals(1, second.encapsulated.size)
        assertEquals(1, first.encapsulated.size)
        assertTrue(com.lavasec.app.foundation.chained.ChainedInnerUdpPacket.parse(first.encapsulated.single()) != null)
        assertEquals(0, ChainedRowDatagram.decode(peer.sent.single())!!.first)
        path.close()
    }

    @Test
    fun `resolver DNS bytes do not prove application forwarding but HTTPS from that host does`() {
        val session = FakeWireGuardSession()
        val tun = ScriptedTunPacketSession()
        val path = dataPath(session, ScriptedPeerSocket(), tun)
        path.start()
        for (port in listOf(53, 443)) {
            val packet = com.lavasec.app.foundation.chained.ChainedInnerUdpPacket.build(
                sourceAddress = EnginePacketFixtures.address("10.64.0.1"),
                destinationAddress = EnginePacketFixtures.address("10.7.0.2"),
                sourcePort = port, destinationPort = 50000, identification = 1, payload = byteArrayOf(1),
            )
            session.decapsulateResponses += LavaWgResponse.Produced(
                LavaWgOp.WRITE_TO_TUNNEL_V4, packet, EnginePacketFixtures.address("10.64.0.1"),
            )
            path.onPeerDatagram(byteArrayOf(9), EnginePacketFixtures.address("203.0.113.1"))
            assertEquals(if (port == 53) 0L else packet.size.toLong(), path.takeLivenessSample().forwardedNonDnsBytes)
        }
    }

    @Test
    fun `an outbound packet encapsulates and reaches the peer socket`() {
        val session = FakeWireGuardSession()
        session.statsValue = EnginePacketFixtures.establishedStats()
        session.encapsulateResult =
            { LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(9)) }
        val peer = ScriptedPeerSocket()
        val tun = ScriptedTunPacketSession()
        val dataPath = dataPath(session, peer, tun)

        dataPath.tick()
        dataPath.onTunPacket(EnginePacketFixtures.ordinaryTcpPacket())

        assertEquals(1, session.encapsulated.size)
        assertArrayEquals(
            EnginePacketFixtures.ordinaryTcpPacket(),
            session.encapsulated.single(),
        )
        assertEquals(1, peer.sent.size)
        assertArrayEquals(byteArrayOf(9), peer.sent.single())
        assertTrue(dataPath.takeLivenessSample().sawObligingNonDnsSend)
        assertEquals(false, dataPath.takeLivenessSample().sawObligingNonDnsSend)
    }

    @Test
    fun `an inbound allowed packet is written back to the tunnel`() {
        val session = FakeWireGuardSession()
        val peer = ScriptedPeerSocket()
        val tun = ScriptedTunPacketSession()
        val dataPath = dataPath(session, peer, tun)
        val packet = byteArrayOf(1, 2, 3)
        session.decapsulateResponses += LavaWgResponse.Produced(
            LavaWgOp.WRITE_TO_TUNNEL_V4,
            packet,
            EnginePacketFixtures.address("10.1.2.3"),
        )
        dataPath.start()

        dataPath.onPeerDatagram(byteArrayOf(9), EnginePacketFixtures.address("203.0.113.1"))

        assertEquals(1, tun.writes.size)
        assertArrayEquals(packet, tun.writes.single())
    }

    @Test
    fun `a client DNS query is handed to the DNS sink and never encapsulated`() {
        val session = FakeWireGuardSession()
        session.statsValue = EnginePacketFixtures.establishedStats()
        val dns = RecordingDnsSink()
        val dataPath = dataPath(session, ScriptedPeerSocket(), ScriptedTunPacketSession(), dns = dns)

        dataPath.tick()
        dataPath.onTunPacket(EnginePacketFixtures.dnsQueryPacket())

        assertEquals(1, dns.packets.size)
        assertTrue(session.encapsulated.isEmpty())
        assertEquals(false, dataPath.takeLivenessSample().sawObligingNonDnsSend)
    }

    @Test
    fun `a deferred tunnel write is drained on the next tick`() {
        val session = FakeWireGuardSession()
        val tun = ScriptedTunPacketSession()
        tun.writeResults += TunWriteAttempt.WouldBlock
        val dataPath = dataPath(session, ScriptedPeerSocket(), tun)
        session.decapsulateResponses += LavaWgResponse.Produced(
            LavaWgOp.WRITE_TO_TUNNEL_V4,
            byteArrayOf(7),
            EnginePacketFixtures.address("10.1.2.3"),
        )
        dataPath.start()

        dataPath.onPeerDatagram(byteArrayOf(9), EnginePacketFixtures.address("203.0.113.1"))
        assertEquals(0, tun.writes.size)
        assertEquals(0L, dataPath.takeLivenessSample().forwardedNonDnsBytes)

        dataPath.tick()

        assertEquals(1, tun.writes.size)
        assertArrayEquals(byteArrayOf(7), tun.writes.single())
        val delivered = dataPath.takeLivenessSample()
        assertEquals(1L, delivered.forwardedNonDnsBytes)
        dataPath.replaceSession(FakeWireGuardSession())
        assertEquals(delivered.forwardingGeneration + 1, dataPath.takeLivenessSample().forwardingGeneration)
        assertEquals(0L, dataPath.takeLivenessSample().forwardedNonDnsBytes)
    }

    @Test
    fun `a failed peer send reports one terminal failure`() {
        val session = FakeWireGuardSession()
        session.statsValue = EnginePacketFixtures.establishedStats()
        session.encapsulateResult =
            { LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(1)) }
        val peer = ScriptedPeerSocket(sendResult = false)
        var failures = 0
        var lastFailure: ChainedRuntimeFailure? = null
        val dataPath = dataPath(session, peer, ScriptedTunPacketSession(), onFailure = {
            failures += 1
            lastFailure = it
        })

        dataPath.tick()
        dataPath.onTunPacket(EnginePacketFixtures.ordinaryTcpPacket())
        dataPath.onTunPacket(EnginePacketFixtures.ordinaryTcpPacket())

        assertEquals(1, failures)
        assertEquals(ChainedRuntimeFailure.PEER_SOCKET_FAILED, lastFailure)
    }

    @Test
    fun `close releases the owner session then the socket then the tunnel`() {
        val tun = ScriptedTunPacketSession()
        val peer = ScriptedPeerSocket()
        val session = RecordingCloseSession()
        val dataPath = dataPath(session = session, peer = peer, tun = tun)

        dataPath.close()
        dataPath.close()

        assertEquals(listOf("session", "socket", "tun"), closeOrder)
        assertEquals(1, session.closeCount)
    }

    @Test
    fun `dns teardown shares engine confinement and late callbacks cannot write retired TUN`() {
        var onQueue = false
        var dnsClosedOnQueue = false
        val dispatcher = object : ChainedEngineDispatcher {
            override fun <T> run(block: () -> T): T {
                val previous = onQueue
                onQueue = true
                try { return block() } finally { onQueue = previous }
            }
        }
        val dns = RecordingDnsSink { dnsClosedOnQueue = onQueue }
        val tun = ScriptedTunPacketSession()
        val path = dataPath(FakeWireGuardSession(), ScriptedPeerSocket(), tun,
            dns = dns, dispatcher = dispatcher)
        path.close()
        assertTrue(dnsClosedOnQueue)
        var replyEvaluated = false
        dispatcher.run {
            dns.respond(ChainedDnsResponse { write ->
                replyEvaluated = true
                write(byteArrayOf(9))
            })
        }
        assertEquals(false, replyEvaluated)
        assertTrue(tun.writes.isEmpty())
        assertEquals(1, tun.closeCount)
    }

    @Test
    fun `start forces the initial handshake to the network sink`() {
        val session = FakeWireGuardSession()
        session.forceHandshakeResponse =
            LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(0xAA.toByte()))
        val peer = ScriptedPeerSocket()
        val dataPath = dataPath(session, peer, ScriptedTunPacketSession())

        dataPath.start()

        assertEquals(1, peer.sent.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte()), peer.sent.single())
    }

    @Test
    fun `installing a replacement session swaps the engine and the driver forces its handshake`() {
        val session = FakeWireGuardSession()
        val replacement = FakeWireGuardSession()
        replacement.forceHandshakeResponse =
            LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(0xBB.toByte()))
        val peer = ScriptedPeerSocket()
        val dataPath = dataPath(session, peer, ScriptedTunPacketSession())

        dataPath.replaceSession(replacement)
        dataPath.forceHandshake()

        assertEquals(1, peer.sent.size)
        assertArrayEquals(byteArrayOf(0xBB.toByte()), peer.sent.single())
    }

    @Test
    fun `DNS evidence follows deferred TUN write and is suppressed when its generation expires`() {
        for (currentOnDrain in listOf(true, false)) {
            val tun = ScriptedTunPacketSession()
            tun.writeResults += TunWriteAttempt.WouldBlock
            val dns = RecordingDnsSink()
            val path = dataPath(tun = tun, dns = dns)
            var current = true
            var recorded = 0
            dns.respond(ChainedDnsResponse { write ->
                if (!current) null else write(byteArrayOf(7)).also {
                    if (it == TunWriteAttempt.Written) recorded++
                }
            })
            assertEquals(0, recorded)
            current = currentOnDrain
            path.tick()
            path.tick()
            assertEquals(if (currentOnDrain) 1 else 0, recorded)
            assertEquals(if (currentOnDrain) 1 else 0, tun.writes.size)
            path.close()
        }
    }

    @Test
    fun `failed or retired DNS writes never commit successful client evidence`() {
        val tun = ScriptedTunPacketSession()
        tun.writeResults += TunWriteAttempt.Closed
        val dns = RecordingDnsSink()
        val failures = mutableListOf<ChainedRuntimeFailure>()
        val path = dataPath(tun = tun, dns = dns, onFailure = { failures += it })
        var recorded = 0
        dns.respond(ChainedDnsResponse { write ->
            write(byteArrayOf(7)).also { if (it == TunWriteAttempt.Written) recorded++ }
        })
        assertEquals(0, recorded)
        assertEquals(listOf(ChainedRuntimeFailure.TUN_WRITE_FAILED), failures)
        path.close()
    }

    // MARK: - Fixtures

    private fun dataPath(
        session: WireGuardSession = FakeWireGuardSession(),
        peer: ScriptedPeerSocket = ScriptedPeerSocket(),
        tun: ScriptedTunPacketSession = ScriptedTunPacketSession(),
        dns: RecordingDnsSink = RecordingDnsSink(),
        onFailure: (ChainedRuntimeFailure) -> Unit = {},
        configurationOverride: ChainedUpstreamConfiguration? = null,
        installedPlanOverride: ChainedRoutePlan? = null,
        dispatcher: ChainedEngineDispatcher = ChainedEngineDispatcher.Direct,
    ): ChainedDataPath = ChainedDataPath(
        configuration = configurationOverride ?: configuration(),
        installedPlan = installedPlanOverride ?: ChainedRoutePlan.make(configurationOverride ?: configuration()),
        packetSession = tun,
        peerSocket = peer,
        session = session,
        ownResolverPorts = FakeResolverPortRegistry(),
        clientDns = dns,
        dispatcher = dispatcher,
        nowSeconds = { 0 },
        onFailure = onFailure,
    )

    private fun configuration(): ChainedUpstreamConfiguration =
        ChainedUpstreamConfiguration.create(
            endpointHost = "vpn.example.com",
            endpointPort = 51820,
            peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 }),
            clientAddress = "10.7.0.2",
            allowedIPs = listOf("0.0.0.0/0"),
            persistentKeepaliveSeconds = 0,
            interfaceMTU = null,
            dnsAddresses = listOf("10.64.0.1"),
        )

    private class RecordingDnsSink(private val onClose: () -> Unit = {}) : ChainedClientDns {
        val packets = mutableListOf<ByteArray>()
        lateinit var respond: (ChainedDnsResponse) -> Unit

        override fun bind(
            sendQuery: (ByteArray) -> Unit,
            writeClientResponse: (ChainedDnsResponse) -> Unit,
        ) { respond = writeClientResponse }

        override fun handleQuery(packet: ByteArray) {
            packets += packet
        }

        override fun observeInbound(packet: ByteArray): Boolean = false

        override fun expire() = Unit

        override fun close() = onClose()
    }

    private inner class RecordingCloseSession : WireGuardSession {
        var closeCount = 0
            private set

        override val isOpen: Boolean get() = closeCount == 0
        override fun encapsulate(packet: ByteArray): LavaWgResponse = LavaWgResponse.None
        override fun decapsulate(packet: ByteArray, sourceAddress: ByteArray): LavaWgResponse =
            LavaWgResponse.None

        override fun tick(): LavaWgResponse = LavaWgResponse.None
        override fun forceHandshake(): LavaWgResponse = LavaWgResponse.None
        override fun stats(): com.lavasec.app.vpn.engine.LavaWgStats? = null
        override fun close() {
            closeCount += 1
            closeOrder += "session"
        }
    }

    private inner class ScriptedPeerSocket(
        private val sendResult: Boolean = true,
    ) : ChainedPeerSocket {
        val sent = mutableListOf<ByteArray>()
        private val receiveQueue = ArrayDeque<ChainedPeerDatagram?>()
        var closeCount = 0
            private set

        fun enqueue(datagram: ChainedPeerDatagram?) {
            receiveQueue += datagram
        }

        override fun send(datagram: ByteArray): Boolean {
            if (!sendResult) return false
            sent += datagram
            return true
        }

        override suspend fun receive(): ChainedPeerDatagram? =
            if (receiveQueue.isEmpty()) null else receiveQueue.removeFirst()

        override fun close() {
            closeCount += 1
            closeOrder += "socket"
        }
    }

    private inner class ScriptedTunPacketSession : TunPacketSession {
        val writes = mutableListOf<ByteArray>()
        val writeResults = ArrayDeque<TunWriteAttempt>()
        var closeCount = 0
            private set

        override suspend fun readPacket(into: ByteArray): TunRead = TunRead.EndOfStream

        override fun tryWritePacket(packet: ByteArray): TunWriteAttempt {
            val result = writeResults.removeFirstOrNull() ?: TunWriteAttempt.Written
            if (result == TunWriteAttempt.Written) writes += packet
            return result
        }

        override suspend fun awaitWritable(): TunWritable = TunWritable.Writable

        override fun close() {
            closeCount += 1
            closeOrder += "tun"
        }
    }
}
