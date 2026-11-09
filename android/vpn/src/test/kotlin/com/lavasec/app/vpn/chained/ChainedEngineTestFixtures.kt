package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedOwnResolverPortRegistry
import com.lavasec.app.vpn.engine.LavaWgResponse
import com.lavasec.app.vpn.engine.LavaWgStats

/**
 * Hand-built IPv4 packets for the owner tests. Mirrors the foundation classifier's fixture shape
 * so a test names the header it exercises rather than copying a capture.
 */
internal object EnginePacketFixtures {
    const val IPPROTO_TCP = 6
    const val IPPROTO_UDP = 17

    fun ipv4(
        protocol: Int,
        destination: String,
        transport: ByteArray,
        source: String = "10.7.0.2",
    ): ByteArray {
        val out = ByteArray(20 + transport.size)
        out[0] = 0x45
        val total = out.size
        out[2] = ((total shr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        out[8] = 64
        out[9] = protocol.toByte()
        writeAddress(out, 12, source)
        writeAddress(out, 16, destination)
        transport.copyInto(out, 20)
        return out
    }

    fun udp(
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArray(8 + payload.size)
        putU16(out, 0, sourcePort)
        putU16(out, 2, destinationPort)
        putU16(out, 4, out.size)
        payload.copyInto(out, 8)
        return out
    }

    fun tcp(sourcePort: Int, destinationPort: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(20 + payload.size)
        putU16(out, 0, sourcePort)
        putU16(out, 2, destinationPort)
        putU16(out, 12, 0x5000)
        putU16(out, 14, 0x0010)
        payload.copyInto(out, 20)
        return out
    }

    /** Ordinary TCP traffic: encapsulates. */
    fun ordinaryTcpPacket(destination: String = "8.8.8.8", destinationPort: Int = 443): ByteArray =
        ipv4(IPPROTO_TCP, destination, tcp(40000, destinationPort))

    /** A client UDP/53 query for the local proxy: arrives for the DNS path. */
    fun dnsQueryPacket(sourcePort: Int = 40000, destination: String = "10.111.0.1"): ByteArray =
        ipv4(IPPROTO_UDP, destination, udp(sourcePort, 53, ByteArray(12) { 0x11 }))

    /** An IPv4 address as four network-order bytes. */
    fun address(text: String): ByteArray {
        val parts = text.split('.')
        require(parts.size == 4) { "not a dotted quad: $text" }
        return ByteArray(4) { index -> parts[index].toInt().toByte() }
    }

    /** A 16-byte IPv6 address, e.g. the permitted `::1` or an arbitrary v6 source. */
    fun ipv6Address(vararg hextets: Int): ByteArray {
        val out = ByteArray(16)
        for (index in hextets.indices) {
            out[index * 2] = ((hextets[index] shr 8) and 0xFF).toByte()
            out[index * 2 + 1] = (hextets[index] and 0xFF).toByte()
        }
        return out
    }

    fun establishedStats(): LavaWgStats = LavaWgStats(0L, 0L, 0L, -1, 0f)

    fun noSessionStats(): LavaWgStats = LavaWgStats(-1L, 0L, 0L, -1, 0f)

    private fun putU16(out: ByteArray, index: Int, value: Int) {
        out[index] = ((value shr 8) and 0xFF).toByte()
        out[index + 1] = (value and 0xFF).toByte()
    }

    private fun writeAddress(out: ByteArray, index: Int, address: String) {
        val octets = address(address)
        octets.copyInto(out, index)
    }
}

/** A [WireGuardSession] the owner can be driven against with no JNI and no socket. */
internal class FakeWireGuardSession : WireGuardSession {
    override var isOpen: Boolean = true
        private set

    /** Scripted return for [encapsulate]; defaults to `None`. */
    var encapsulateResult: (ByteArray) -> LavaWgResponse = { LavaWgResponse.None }

    /** Packets handed to [encapsulate], in order (copied). */
    val encapsulated: MutableList<ByteArray> = mutableListOf()

    /** Scripted decapsulate replies, consumed in order; then [defaultDecapsulate]. */
    val decapsulateResponses: ArrayDeque<LavaWgResponse> = ArrayDeque()
    var defaultDecapsulate: LavaWgResponse = LavaWgResponse.None

    var decapsulateCallCount: Int = 0
    var lastSourceAddress: ByteArray? = null

    var tickResponse: LavaWgResponse = LavaWgResponse.None
    var tickCallCount: Int = 0
    var forceHandshakeResponse: LavaWgResponse = LavaWgResponse.None

    var statsValue: LavaWgStats? = EnginePacketFixtures.noSessionStats()
    var statsCallCount: Int = 0

    val pendingResponses: ArrayDeque<LavaWgResponse> = ArrayDeque()

    override fun takePendingResponse(): LavaWgResponse = pendingResponses.removeFirstOrNull() ?: LavaWgResponse.None

    var closeCount: Int = 0

    override fun encapsulate(packet: ByteArray): LavaWgResponse {
        encapsulated += packet.copyOf()
        return encapsulateResult(packet)
    }

    override fun decapsulate(packet: ByteArray, sourceAddress: ByteArray): LavaWgResponse {
        decapsulateCallCount++
        lastSourceAddress = sourceAddress.copyOf()
        return decapsulateResponses.removeFirstOrNull() ?: defaultDecapsulate
    }

    override fun tick(): LavaWgResponse {
        tickCallCount++
        return tickResponse
    }

    override fun forceHandshake(): LavaWgResponse = forceHandshakeResponse

    override fun stats(): LavaWgStats? {
        statsCallCount++
        return statsValue
    }

    override fun close() {
        isOpen = false
        closeCount++
    }
}

/**
 * A dispatcher that counts the blocks it runs and executes them inline, modelling the
 * production re-entrancy contract (a nested call runs inline rather than deadlocking).
 */
internal class RecordingDispatcher : ChainedEngineDispatcher {
    var runCount: Int = 0
        private set

    override fun <T> run(block: () -> T): T {
        runCount++
        return block()
    }
}

/** Records everything the owner emitted, in order, for assertions. */
internal class RecordingEngineSink : ChainedEngineSink {
    val network: MutableList<ByteArray> = mutableListOf()
    val tunnel: MutableList<ByteArray> = mutableListOf()
    val dns: MutableList<ByteArray> = mutableListOf()

    override fun emitToNetwork(datagram: ByteArray) {
        network += datagram
    }

    override fun deliverToTunnel(packet: ByteArray) {
        tunnel += packet
    }

    override fun handleDnsQuery(packet: ByteArray) {
        dns += packet
    }
}

/** Minimal in-memory resolver-port registry; the owner only reads the two claim predicates. */
internal class FakeResolverPortRegistry : ChainedOwnResolverPortRegistry {
    private val claimed = mutableSetOf<Pair<Int, Int>>()
    private val recent = mutableSetOf<Pair<Int, Int>>()

    fun claim(sourcePort: Int, protocolNumber: Int) {
        claimed += sourcePort to protocolNumber
        recent -= sourcePort to protocolNumber
    }

    fun release(sourcePort: Int, protocolNumber: Int) {
        claimed -= sourcePort to protocolNumber
        recent += sourcePort to protocolNumber
    }

    override fun claims(sourcePort: Int, protocolNumber: Int): Boolean =
        (sourcePort to protocolNumber) in claimed

    override fun wasRecentlyClaimed(sourcePort: Int, protocolNumber: Int): Boolean =
        (sourcePort to protocolNumber) in recent
}
