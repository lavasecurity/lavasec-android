package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.vpn.ChainedEngineQueue
import com.lavasec.app.vpn.TunPacketSession
import com.lavasec.app.vpn.TunRead
import com.lavasec.app.vpn.TunWriteAttempt
import com.lavasec.app.vpn.TunWritable
import com.lavasec.app.vpn.engine.LavaWgOp
import com.lavasec.app.vpn.engine.LavaWgResponse
import com.lavasec.app.vpn.engine.LavaWgStats
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The coroutine TUN reader/receiver contract: it feeds the outage driver, which drives the data
 * path. Driven with [Dispatchers.Unconfined] so the loops execute inline and the assertions are
 * deterministic. The engine tick — now owned by the driver's timers — is not exercised here.
 */
class ChainedTunnelRuntimeTest {

    @Test
    fun `the reader loop drives outbound packets through the driver into the engine`() {
        val session = FakeWireGuardSession()
        session.statsValue = EnginePacketFixtures.establishedStats()
        session.encapsulateResult =
            { LavaWgResponse.Produced(LavaWgOp.WRITE_TO_NETWORK, byteArrayOf(0xCC.toByte())) }
        val peer = RecordingPeerSocket()
        val dataPath = dataPath(session, peer, ScriptedTun(listOf(EnginePacketFixtures.ordinaryTcpPacket())))
        dataPath.start()
        dataPath.tick()
        val queue = ChainedEngineQueue()
        val driver = driver(queue)
        driver.adopt(dataPath)
        val runtime = ChainedTunnelRuntime(
            driver = driver,
            dataPath = dataPath,
            scopeDispatcher = Dispatchers.Unconfined,
        )

        runtime.start()
        runtime.close()
        queue.close()

        assertArrayEquals(byteArrayOf(0xCC.toByte()), peer.sent.single())
    }

    @Test
    fun `an engine exception during a packet step reports terminal failure`() {
        val session = FakeWireGuardSession()
        session.statsValue = EnginePacketFixtures.establishedStats()
        session.encapsulateResult = { throw IllegalStateException("engine exploded") }
        val dataPath = dataPath(
            session,
            RecordingPeerSocket(),
            ScriptedTun(listOf(EnginePacketFixtures.ordinaryTcpPacket())),
        )
        dataPath.start()
        dataPath.tick()
        val queue = ChainedEngineQueue()
        val driver = driver(queue)
        driver.adopt(dataPath)
        var terminal: ChainedRuntimeFailure? = null
        var terminalCount = 0
        val runtime = ChainedTunnelRuntime(
            driver = driver,
            dataPath = dataPath,
            scopeDispatcher = Dispatchers.Unconfined,
            onTerminalFailure = {
                terminal = it
                terminalCount += 1
            },
        )

        runtime.start()
        runtime.close()
        queue.close()

        assertEquals(ChainedRuntimeFailure.ENGINE_FAILED, terminal)
        assertEquals(1, terminalCount)
    }

    // MARK: - Fixtures

    private fun driver(queue: ChainedEngineQueue): ChainedOutageDriver = ChainedOutageDriver(
        engineQueue = queue,
        timers = NoopTimers,
        source = object : ChainedSessionSource {
            override fun makeSession(
                events: ChainedSessionEvents,
                delivery: ChainedSessionDelivery,
            ) = Unit
        },
        clock = { 0 },
    )

    private fun dataPath(
        session: WireGuardSession,
        peer: ChainedPeerSocket,
        tun: TunPacketSession,
    ): ChainedDataPath = ChainedDataPath(
        configuration = configuration(),
        installedPlan = com.lavasec.app.foundation.chained.ChainedRoutePlan.make(configuration()),
        packetSession = tun,
        peerSocket = peer,
        session = session,
        ownResolverPorts = FakeResolverPortRegistry(),
        clientDns = NoopClientDns,
        dispatcher = ChainedEngineDispatcher.Direct,
        nowSeconds = { 0 },
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

    private object NoopClientDns : ChainedClientDns {
        override fun bind(
            sendQuery: (ByteArray) -> Unit,
            writeClientResponse: (ChainedDnsResponse) -> Unit,
        ) = Unit

        override fun handleQuery(packet: ByteArray) = Unit

        override fun observeInbound(packet: ByteArray): Boolean = false

        override fun expire() = Unit

        override fun close() = Unit
    }

    private object NoopTimers : ChainedDriverTimers {
        override fun arm(afterMillis: Long, fire: () -> Unit): ChainedArmedTimer =
            object : ChainedArmedTimer {
                override fun cancel() = Unit
            }

        override fun scheduleTick(intervalMillis: Long?) = Unit
    }

    private class RecordingPeerSocket : ChainedPeerSocket {
        val sent = mutableListOf<ByteArray>()
        var closeCount = 0
            private set

        override fun send(datagram: ByteArray): Boolean {
            sent += datagram
            return true
        }

        override suspend fun receive(): ChainedPeerDatagram? = null

        override fun close() {
            closeCount += 1
        }
    }

    private class ScriptedTun(
        scripted: List<ByteArray>,
    ) : TunPacketSession {
        private val packets = ArrayDeque(scripted)
        var closed = false
            private set

        override suspend fun readPacket(into: ByteArray): TunRead {
            val next = packets.removeFirstOrNull() ?: return TunRead.EndOfStream
            next.copyInto(into)
            return TunRead.Packet(next.size)
        }

        override fun tryWritePacket(packet: ByteArray): TunWriteAttempt = TunWriteAttempt.Written

        override suspend fun awaitWritable(): TunWritable = TunWritable.Writable

        override fun close() {
            closed = true
        }
    }
}
