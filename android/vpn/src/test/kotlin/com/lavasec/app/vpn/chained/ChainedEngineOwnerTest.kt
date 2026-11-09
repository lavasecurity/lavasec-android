package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedAllowedIPs
import com.lavasec.app.foundation.chained.ChainedPacketQueue
import com.lavasec.app.foundation.chained.ChainedPacketQueueLimits
import com.lavasec.app.foundation.chained.ChainedSessionEndCause
import com.lavasec.app.foundation.chained.WireGuardEngineError
import com.lavasec.app.vpn.engine.LavaWgError
import com.lavasec.app.vpn.engine.LavaWgOp
import com.lavasec.app.vpn.engine.LavaWgResponse
import com.lavasec.app.vpn.engine.LavaWgStats
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The owner is now the driver's per-attempt SESSION behaviour: it drives one WireGuard session,
 * tracks link liveness, and reports a session end through its events sink. The outage budget,
 * retry ladder, and surrender decisions live in [ChainedOutageDriver] and are covered there; this
 * suite pins the packet path, the liveness sample, and the session-end reporting.
 */
class ChainedEngineOwnerTest {

    private lateinit var session: FakeWireGuardSession
    private lateinit var sink: RecordingEngineSink
    private lateinit var queue: ChainedPacketQueue
    private lateinit var registry: FakeResolverPortRegistry
    private lateinit var ended: MutableList<ChainedSessionEndCause>
    private var now: Int = 0

    @Before
    fun setUp() {
        session = FakeWireGuardSession()
        sink = RecordingEngineSink()
        queue = ChainedPacketQueue(
            requireNotNull(ChainedPacketQueueLimits.forChainedTunnel(1280)),
        )
        registry = FakeResolverPortRegistry()
        ended = mutableListOf()
        now = 0
    }

    private fun owner(allowed: String = "10.0.0.0/8"): ChainedEngineOwner =
        ChainedEngineOwner(
            session = session,
            sink = sink,
            allowedIPs = requireNotNull(ChainedAllowedIPs.parse(allowed)),
            queue = queue,
            ownResolverPorts = registry,
            nowSeconds = { now },
            dispatcher = ChainedEngineDispatcher.Direct,
            events = ChainedSessionEvents { ended.add(it) },
        )

    private fun establish(owner: ChainedEngineOwner) {
        session.statsValue = EnginePacketFixtures.establishedStats()
        owner.tick()
    }

    private fun networkDatagram(bytes: ByteArray): LavaWgResponse =
        LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, bytes)

    private fun peerAddress(): ByteArray = EnginePacketFixtures.address("203.0.113.1")

    @Test
    fun `all operation paths flush already produced stack output before the next engine call`() {
        listOf("outbound", "keepalive", "handshake", "tick").forEach { operation ->
            setUp()
            val owner = owner()
            establish(owner)
            val initial = networkDatagram(byteArrayOf(0))
            session.encapsulateResult = { initial }
            session.forceHandshakeResponse = initial
            session.tickResponse = initial
            val reply = EnginePacketFixtures.ordinaryTcpPacket(sourceForReply(), 443)
            session.pendingResponses.addLast(networkDatagram(byteArrayOf(1)))
            session.pendingResponses.addLast(LavaWgResponse.Produced(LavaWgOp.WRITE_TO_TUNNEL_V4, reply, EnginePacketFixtures.address(sourceForReply())))
            session.pendingResponses.addLast(networkDatagram(byteArrayOf(2)))
            when (operation) {
                "outbound" -> owner.handleOutboundPacket(EnginePacketFixtures.ordinaryTcpPacket())
                "keepalive" -> owner.sendKeepalive()
                "handshake" -> owner.forceHandshake()
                "tick" -> owner.tick()
            }
            assertEquals(operation, listOf(0, 1, 2), sink.network.map { it.single().toInt() })
            assertArrayEquals(reply, sink.tunnel.single())
            assertTrue(session.pendingResponses.isEmpty())
        }
    }

    private fun sourceForReply() = "10.8.0.1"

    @Test
    fun `a terminal stack response stops draining and cannot emit the following stale datagram`() {
        val owner = owner()
        session.forceHandshakeResponse = networkDatagram(byteArrayOf(0))
        session.pendingResponses.addLast(LavaWgResponse.Failure(LavaWgError.INTERNAL))
        session.pendingResponses.addLast(networkDatagram(byteArrayOf(1)))
        owner.forceHandshake()
        assertEquals(1, ended.size)
        assertEquals(1, sink.network.size)
        assertEquals(1, session.pendingResponses.size)
    }

    // MARK: - Outbound

    @Test
    fun `established outbound encapsulates and emits the datagram`() {
        val owner = owner()
        establish(owner)
        val datagram = byteArrayOf(9, 9, 9)
        session.encapsulateResult = { networkDatagram(datagram) }

        owner.handleOutboundPacket(EnginePacketFixtures.ordinaryTcpPacket())

        assertEquals(1, session.encapsulated.size)
        assertArrayEquals(
            EnginePacketFixtures.ordinaryTcpPacket(),
            session.encapsulated.single(),
        )
        assertEquals(1, sink.network.size)
        assertArrayEquals(datagram, sink.network.single())
    }

    @Test
    fun `outbound queues while there is no established session and flushes on tick`() {
        val owner = owner()
        owner.handleOutboundPacket(EnginePacketFixtures.ordinaryTcpPacket())

        assertEquals(1, queue.count)
        assertTrue(session.encapsulated.isEmpty())
        assertTrue(sink.network.isEmpty())

        val datagram = byteArrayOf(1)
        session.encapsulateResult = { networkDatagram(datagram) }
        establish(owner)

        assertEquals(0, queue.count)
        assertEquals(1, session.encapsulated.size)
        assertEquals(1, sink.network.size)
    }

    @Test
    fun `a client dns query is handed to the dns path`() {
        val owner = owner()
        establish(owner)

        owner.handleOutboundPacket(EnginePacketFixtures.dnsQueryPacket())

        assertEquals(1, sink.dns.size)
        assertTrue(session.encapsulated.isEmpty())
    }

    @Test
    fun `a client dns query is served even before the session is established`() {
        val owner = owner()

        owner.handleOutboundPacket(EnginePacketFixtures.dnsQueryPacket())

        assertEquals(1, sink.dns.size)
        assertEquals(0, queue.count)
        assertTrue(session.encapsulated.isEmpty())
    }

    @Test
    fun `an own-resolver query is encapsulated rather than served locally`() {
        val owner = owner()
        establish(owner)
        registry.claim(40000, EnginePacketFixtures.IPPROTO_UDP)
        session.encapsulateResult = { networkDatagram(byteArrayOf(7)) }

        owner.handleOutboundPacket(EnginePacketFixtures.dnsQueryPacket(sourcePort = 40000))

        assertEquals(1, session.encapsulated.size)
        assertTrue(sink.dns.isEmpty())
    }

    @Test
    fun `a queued own-resolver query whose claim lapsed is neither served nor forwarded`() {
        val owner = owner()
        registry.claim(40000, EnginePacketFixtures.IPPROTO_UDP)
        owner.handleOutboundPacket(EnginePacketFixtures.dnsQueryPacket(sourcePort = 40000))
        assertEquals(1, queue.count)

        registry.release(40000, EnginePacketFixtures.IPPROTO_UDP)
        establish(owner)

        assertTrue(sink.dns.isEmpty())
        assertTrue(session.encapsulated.isEmpty())
        assertEquals(0, queue.count)
    }

    @Test
    fun `malformed and ipv6 outbound packets are dropped without touching the engine`() {
        val owner = owner()
        establish(owner)

        owner.handleOutboundPacket(ByteArray(0))
        owner.handleOutboundPacket(ByteArray(40).also { it[0] = 0x60 })

        assertTrue(session.encapsulated.isEmpty())
        assertTrue(sink.dns.isEmpty())
        assertEquals(0, queue.count)
    }

    @Test
    fun `an oversized outbound packet is shed before the engine sees it`() {
        val owner = owner()
        establish(owner)

        owner.handleOutboundPacket(
            ByteArray(ChainedPacketQueueLimits.MAXIMUM_IP_PACKET_BYTE_COUNT + 1),
        )

        assertTrue(session.encapsulated.isEmpty())
        assertEquals(0, queue.count)
    }

    @Test
    fun `a batch is driven packet by packet`() {
        val owner = owner()
        establish(owner)
        session.encapsulateResult = { networkDatagram(byteArrayOf(1)) }

        owner.handleOutboundBatch(
            listOf(
                EnginePacketFixtures.ordinaryTcpPacket(),
                EnginePacketFixtures.ordinaryTcpPacket(),
            ),
        )

        assertEquals(2, session.encapsulated.size)
    }

    // MARK: - Inbound

    @Test
    fun `inbound delivers an allowed source and drops a spoofed one`() {
        val owner = owner("10.0.0.0/8")
        val packet = byteArrayOf(1, 2, 3)

        session.decapsulateResponses += LavaWgResponse.Produced(
            LavaWgOp.WRITE_TO_TUNNEL_V4,
            packet,
            EnginePacketFixtures.address("10.1.2.3"),
        )
        owner.handleInboundDatagram(byteArrayOf(9), peerAddress())

        session.decapsulateResponses += LavaWgResponse.Produced(
            LavaWgOp.WRITE_TO_TUNNEL_V4,
            packet,
            EnginePacketFixtures.address("192.168.1.1"),
        )
        owner.handleInboundDatagram(byteArrayOf(9), peerAddress())

        assertEquals(1, sink.tunnel.size)
        assertArrayEquals(packet, sink.tunnel.single())
    }

    @Test
    fun `inbound with no captured source is refused`() {
        val owner = owner()
        session.decapsulateResponses += LavaWgResponse.Produced(
            LavaWgOp.WRITE_TO_TUNNEL_V4,
            byteArrayOf(1),
            null,
        )

        owner.handleInboundDatagram(byteArrayOf(9), peerAddress())

        assertTrue(sink.tunnel.isEmpty())
    }

    @Test
    fun `inbound ipv6 whose source is not authorized is dropped`() {
        val owner = owner("10.0.0.0/8")
        session.decapsulateResponses += LavaWgResponse.Produced(
            LavaWgOp.WRITE_TO_TUNNEL_V6,
            byteArrayOf(1),
            EnginePacketFixtures.ipv6Address(0x2001, 0x0db8, 0, 0, 0, 0, 0, 1),
        )

        owner.handleInboundDatagram(byteArrayOf(9), peerAddress())

        assertTrue(sink.tunnel.isEmpty())
    }

    @Test
    fun `inbound drains network replies until the engine stops`() {
        val owner = owner()
        session.decapsulateResponses += networkDatagram(byteArrayOf(1))
        session.decapsulateResponses += networkDatagram(byteArrayOf(2))
        session.decapsulateResponses += LavaWgResponse.None

        owner.handleInboundDatagram(byteArrayOf(9), peerAddress())

        assertEquals(2, sink.network.size)
        assertEquals(3, session.decapsulateCallCount)
    }

    @Test
    fun `the inbound drain is bounded when the engine never stops producing`() {
        val owner = owner()
        session.defaultDecapsulate = networkDatagram(byteArrayOf(1))

        owner.handleInboundDatagram(byteArrayOf(9), peerAddress())

        assertEquals(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH, sink.network.size)
        assertEquals(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH, session.decapsulateCallCount)
    }

    @Test
    fun `an oversized inbound datagram is dropped before the engine`() {
        val owner = owner()
        val oversized = ByteArray(ChainedPacketQueueLimits.MAXIMUM_DATAGRAM_BYTE_COUNT + 1)

        owner.handleInboundDatagram(oversized, peerAddress())

        assertEquals(0, session.decapsulateCallCount)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `an inbound destination-too-small verdict is a per-packet drop, not a session end`() {
        val owner = owner()
        establish(owner)
        session.decapsulateResponses +=
            LavaWgResponse.Failure(LavaWgError.DESTINATION_BUFFER_TOO_SMALL)

        owner.handleInboundDatagram(byteArrayOf(1), peerAddress())

        assertTrue(owner.state.value is ChainedEngineState.Established)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `an outbound destination-too-small verdict reports a caller-contract end`() {
        val owner = owner()
        establish(owner)
        session.encapsulateResult =
            { LavaWgResponse.Failure(LavaWgError.DESTINATION_BUFFER_TOO_SMALL) }

        owner.handleOutboundPacket(EnginePacketFixtures.ordinaryTcpPacket())

        assertEquals(WireGuardEngineError.DestinationBufferTooSmall, ended.single().error)
    }

    // MARK: - Tick, handshake, health

    @Test
    fun `tick emits the datagram the engine produced`() {
        val owner = owner()
        session.tickResponse = networkDatagram(byteArrayOf(3))

        owner.tick()

        assertEquals(1, sink.network.size)
    }

    @Test
    fun `forceHandshake emits the datagram the engine produced`() {
        val owner = owner()
        session.forceHandshakeResponse = networkDatagram(byteArrayOf(4))

        owner.forceHandshake()

        assertEquals(1, sink.network.size)
    }

    @Test
    fun `stats are surfaced and promote the state to established`() {
        val owner = owner()
        session.statsValue = LavaWgStats(123L, 1L, 2L, 3, 0.5f)

        owner.tick()

        assertEquals(123L, requireNotNull(owner.stats()).timeSinceLastHandshakeMs)
        assertTrue(owner.state.value is ChainedEngineState.Established)
    }

    @Test
    fun `an idle session past the rekey window stays established`() {
        val owner = owner()
        establish(owner)

        // No sends at all, but the handshake age is well past the rekey window. BoringTun only
        // rekeys an initiator that has sent data, so this is a healthy idle session.
        now = 200
        session.statsValue = LavaWgStats(200_000L, 0L, 0L, -1, 0f)
        owner.tick()

        assertTrue(owner.state.value is ChainedEngineState.Established)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `a stall that begins after the rekey window reports a failed rekey`() {
        val owner = owner()
        session.statsValue = LavaWgStats(0L, 0L, 0L, -1, 0f)
        owner.tick()

        // Idle past the rekey window: no sends, so no rekey was initiated and it stays healthy.
        now = ChainedLivenessPolicy.REKEY_LIVENESS_THRESHOLD_SECONDS
        session.statsValue = LavaWgStats(130_000L, 0L, 0L, -1, 0f)
        owner.tick()
        assertTrue(owner.state.value is ChainedEngineState.Established)

        // Now send and get nothing back; the stall begins after the window.
        session.statsValue = LavaWgStats(130_000L, 100L, 0L, -1, 0f)
        owner.tick()

        now += ChainedLivenessPolicy.LIVENESS_STALL_WINDOW_SECONDS
        session.statsValue = LavaWgStats(145_000L, 200L, 0L, -1, 0f)
        owner.tick()

        assertEquals(WireGuardEngineError.ConnectionExpired, ended.single().error)
    }

    @Test
    fun `a rekey that completes within the window stays established`() {
        val owner = owner()
        establish(owner)

        session.statsValue = LavaWgStats(
            ChainedLivenessPolicy.REKEY_LIVENESS_THRESHOLD_MILLIS - 1,
            0L,
            0L,
            -1,
            0f,
        )
        owner.tick()
        assertTrue(owner.state.value is ChainedEngineState.Established)
        assertTrue(ended.isEmpty())

        // A completed rekey resets the handshake age toward zero.
        session.statsValue = EnginePacketFixtures.establishedStats()
        owner.tick()
        assertTrue(owner.state.value is ChainedEngineState.Established)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `the liveness sample names the first unanswered send and clears on progress`() {
        val owner = owner()
        owner.tick() // no-session stats: no handshake
        val before = owner.takeLivenessSample()
        assertFalse(before.hasHandshake)
        assertNull(before.firstUnansweredSendAtSeconds)

        session.statsValue = LavaWgStats(0L, 0L, 0L, -1, 0f)
        owner.tick()
        session.statsValue = LavaWgStats(0L, 100L, 0L, -1, 0f)
        owner.tick()

        val stalling = owner.takeLivenessSample()
        assertTrue(stalling.hasHandshake)
        assertEquals(0, stalling.firstUnansweredSendAtSeconds)

        now = 1
        session.statsValue = LavaWgStats(0L, 100L, 50L, -1, 0f)
        owner.tick()

        assertNull(owner.takeLivenessSample().firstUnansweredSendAtSeconds)
    }

    @Test
    fun `an authenticated keepalive clears the upload-only stall`() {
        val owner = owner()
        session.statsValue = LavaWgStats(0L, 0L, 0L, -1, 0f)
        owner.tick()
        assertTrue(owner.state.value is ChainedEngineState.Established)

        // Upload-only: tx advances, rx stays flat (WireGuard does not ack ordinary data).
        session.statsValue = LavaWgStats(1_000L, 100L, 0L, -1, 0f)
        owner.tick()

        // The peer's obliged keepalive (transport data, type 4) arrives before the window closes.
        now = 10
        session.decapsulateResponses += LavaWgResponse.None
        owner.handleInboundDatagram(byteArrayOf(4), peerAddress())
        assertEquals(1, session.decapsulateCallCount)

        // Still sending, still flat rx, but the keepalive cleared the ORIGINAL stall clock: the
        // next send starts a fresh unanswered window at t=16, so the sample no longer names the
        // t=0 send that would otherwise have reached the 15 s window and been bounded out.
        now = 16
        session.statsValue = LavaWgStats(17_000L, 200L, 0L, -1, 0f)
        owner.tick()

        assertTrue(owner.state.value is ChainedEngineState.Established)
        assertEquals(now, owner.takeLivenessSample().firstUnansweredSendAtSeconds)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `rx progress keeps an established session healthy`() {
        val owner = owner()
        session.statsValue = LavaWgStats(0L, 0L, 0L, -1, 0f)
        owner.tick()

        session.statsValue = LavaWgStats(0L, 100L, 0L, -1, 0f)
        owner.tick() // stall clock starts

        now = 5
        session.statsValue = LavaWgStats(0L, 200L, 50L, -1, 0f)
        owner.tick() // the peer answered -> reset

        now = 30
        session.statsValue = LavaWgStats(0L, 200L, 50L, -1, 0f)
        owner.tick() // no new sends -> not stalled

        assertTrue(owner.state.value is ChainedEngineState.Established)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `a completed handshake resets the stall signal`() {
        val owner = owner()
        session.statsValue = LavaWgStats(0L, 0L, 0L, -1, 0f)
        owner.tick()

        session.statsValue = LavaWgStats(0L, 100L, 0L, -1, 0f)
        owner.tick() // stall clock starts

        now = 10
        session.statsValue = LavaWgStats(10_000L, 200L, 0L, -1, 0f)
        owner.tick()
        assertTrue(owner.state.value is ChainedEngineState.Established)

        // A new handshake completes: the age goes backwards.
        now = 11
        session.statsValue = LavaWgStats(100L, 200L, 0L, -1, 0f)
        owner.tick()

        now = 40
        session.statsValue = LavaWgStats(29_100L, 200L, 0L, -1, 0f)
        owner.tick() // reset held; no new sends -> healthy

        assertTrue(owner.state.value is ChainedEngineState.Established)
        assertTrue(ended.isEmpty())
    }

    // MARK: - Session end reporting and replacement

    @Test
    fun `a connection expiry reports a session end`() {
        val owner = owner()
        session.tickResponse = LavaWgResponse.Failure(LavaWgError.CONNECTION_EXPIRED)

        owner.tick()

        assertEquals(WireGuardEngineError.ConnectionExpired, ended.single().error)
        assertTrue(owner.state.value is ChainedEngineState.Starting)
    }

    @Test
    fun `a per-packet protocol verdict is dropped and the session survives`() {
        val owner = owner()
        establish(owner)
        session.tickResponse = LavaWgResponse.Failure(LavaWgError.PROTOCOL)

        owner.tick()

        assertTrue(owner.state.value is ChainedEngineState.Established)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `replaceSession swaps the session and clears the shutdown latch`() {
        val owner = owner()
        establish(owner)
        owner.shutdown()
        assertEquals(1, session.closeCount)

        val replacement = FakeWireGuardSession()
        owner.replaceSession(replacement)

        assertTrue(owner.state.value is ChainedEngineState.Starting)
        replacement.encapsulateResult = { networkDatagram(byteArrayOf(1)) }
        replacement.statsValue = EnginePacketFixtures.establishedStats()
        owner.tick()
        owner.handleOutboundPacket(EnginePacketFixtures.ordinaryTcpPacket())
        assertEquals(1, replacement.encapsulated.size)
    }

    @Test
    fun `a shut down owner stops acting on outbound traffic`() {
        val owner = owner()
        establish(owner)
        owner.shutdown()

        owner.handleOutboundPacket(EnginePacketFixtures.ordinaryTcpPacket())

        assertTrue(session.encapsulated.isEmpty())
        assertEquals(0, queue.count)
    }

    // MARK: - Lifecycle

    @Test
    fun `close releases the session once and parks the state at idle`() {
        val owner = owner()

        owner.close()
        owner.close()

        assertEquals(1, session.closeCount)
        assertTrue(owner.state.value is ChainedEngineState.Idle)
    }

    @Test
    fun `a replacement rejected by a closed owner is closed`() {
        val owner = owner()
        owner.close()
        val replacement = FakeWireGuardSession()

        owner.replaceSession(replacement)

        assertEquals(1, replacement.closeCount)
        assertFalse(replacement.isOpen)
    }
}
