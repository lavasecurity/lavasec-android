package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedAllowedIPs
import com.lavasec.app.foundation.chained.ChainedInboundVerdict
import com.lavasec.app.foundation.chained.ChainedInnerUdpPacket
import com.lavasec.app.foundation.chained.ChainedPacketQueueLimits
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.vpn.engine.LavaWgError
import com.lavasec.app.vpn.engine.LavaWgOp
import com.lavasec.app.vpn.engine.LavaWgResponse
import com.lavasec.app.vpn.engine.LavaWgStats
import java.security.SecureRandom

/** Two actual independent engine handles behind the existing one serial outage owner.
 * Destination ownership is checked in both directions. A full entry encrypts the exit's UDP
 * envelope; only entry datagrams reach the physical channel. A split entry selects independent
 * transports by destination. The row tag is internal and removed by ChainedStackPeerChannel.
 */
internal class ChainedStackWireGuardSession(
    private val configuration: ChainedUpstreamConfiguration,
    private val sessions: List<WireGuardSession>,
    private val relayPort: Int = 49152 + SecureRandom().nextInt(16384),
) : WireGuardSession {
    init { require(configuration.orderedHops.size == 2 && sessions.size == 2 && relayPort in 49152..65535) }
    private val profiles = configuration.orderedHops.map { it.withoutEntryHop() }
    private val translations = profiles.map { ChainedStackAddressTranslation(configuration.clientAddress, it.clientAddress) }
    private val allowed = profiles.map { requireNotNull(ChainedAllowedIPs.parse(it.allowedIPs.joinToString(","))) }
    private val pending = BooleanArray(2)
    private val freshAuthentication = BooleanArray(2)
    private var authenticatedThisCall = false
    private var transportProofPending = false
    private var reportedRx = 0L
    private var closed = false
    private val output = ArrayDeque<LavaWgResponse>()
    private val entrySource = ChainedStackAddressTranslation.octets(profiles.first().clientAddress)
    private val exitAddress = if (configuration.usesNestedTransport) ChainedStackAddressTranslation.octets(profiles.last().endpointHost) else null

    override val isOpen: Boolean get() = !closed && sessions.all { it.isOpen }

    override fun encapsulate(packet: ByteArray): LavaWgResponse {
        if (!isOpen) return LavaWgResponse.Failure(LavaWgError.NO_CURRENT_SESSION)
        if (packet.isEmpty()) sessions.indices.forEach { consume(it, sessions[it].encapsulate(packet)); drain(it) }
        else {
            if (packet.size < 20 || packet[0].toInt() ushr 4 != 4) return LavaWgResponse.None
            val index = configuration.routeIndexForIPv4(ChainedStackAddressTranslation.ipv4(packet, 16)) ?: return LavaWgResponse.None
            val mapped = translations[index].outbound(packet) ?: return LavaWgResponse.None
            pending[index] = true
            consume(index, sessions[index].encapsulate(mapped))
            drain(index)
        }
        return pop()
    }

    override fun decapsulate(packet: ByteArray, sourceAddress: ByteArray): LavaWgResponse {
        if (!isOpen) return LavaWgResponse.Failure(LavaWgError.NO_CURRENT_SESSION)
        if (packet.isEmpty()) return pop()
        authenticatedThisCall = false
        val (index, wire) = ChainedRowDatagram.decode(packet) ?: return LavaWgResponse.None
        if (configuration.usesNestedTransport && index != 0) return LavaWgResponse.None
        receive(index, wire, sourceAddress)
        return pop()
    }

    private fun receive(index: Int, packet: ByteArray, source: ByteArray) {
        val result = sessions[index].decapsulate(packet, source)
        if (sessions[index].provesPeerActivity(packet, result)) {
            pending[index] = false
            freshAuthentication[index] = true
            val freshTransport = if (configuration.usesNestedTransport) freshAuthentication[0] else freshAuthentication.all { it }
            if (freshTransport) transportProofPending = false
            authenticatedThisCall = pending.none { it } && !transportProofPending
        }
        consume(index, result)
        drain(index)
        authenticatedThisCall = authenticatedThisCall && pending.none { it }
    }

    override fun tick(): LavaWgResponse {
        if (!isOpen) return LavaWgResponse.Failure(LavaWgError.NO_CURRENT_SESSION)
        sessions.indices.forEach { consume(it, sessions[it].tick()); drain(it) }
        return pop()
    }

    override fun forceHandshake(): LavaWgResponse {
        if (!isOpen) return LavaWgResponse.Failure(LavaWgError.NO_CURRENT_SESSION)
        sessions.indices.forEach { consume(it, sessions[it].forceHandshake()); drain(it) }
        return pop()
    }

    /** A healthy branch cannot certify unanswered demand on the other branch. */
    override fun provesPeerActivity(packet: ByteArray, response: LavaWgResponse): Boolean = authenticatedThisCall
    override fun transportChanged() { freshAuthentication.fill(false); authenticatedThisCall = false; transportProofPending = true }

    override fun stats(): LavaWgStats? {
        if (!isOpen) return null
        val stats = sessions.map { it.stats() ?: return null }
        val received = stats.sumOf { it.rxBytes }
        if (pending.none { it }) reportedRx = received
        return LavaWgStats(
            timeSinceLastHandshakeMs = if (stats.any { it.timeSinceLastHandshakeMs < 0 }) -1 else stats.maxOf { it.timeSinceLastHandshakeMs },
            txBytes = stats.sumOf { it.txBytes }, rxBytes = reportedRx,
            estimatedRoundTripMs = stats.maxOf { it.estimatedRoundTripMs }, estimatedLoss = stats.maxOf { it.estimatedLoss },
        )
    }

    private fun consume(index: Int, response: LavaWgResponse) {
        when (response) {
            is LavaWgResponse.Produced -> when (response.op) {
                LavaWgOp.WRITE_TO_NETWORK -> {
                    if (index == 1 && configuration.usesNestedTransport) {
                        if (response.packet.size > ChainedPacketQueueLimits.MAXIMUM_IP_PACKET_BYTE_COUNT - 28) return
                        val envelope = ChainedInnerUdpPacket.build(entrySource, checkNotNull(exitAddress), relayPort, profiles.last().endpointPort, 0, response.packet)
                        pending[0] = true
                        consume(0, sessions[0].encapsulate(envelope))
                        drain(0)
                    } else offer(response.copy(packet = ChainedRowDatagram.encode(index, response.packet)))
                }
                LavaWgOp.WRITE_TO_TUNNEL_V4 -> {
                    val source = response.sourceAddress ?: return
                    if (allowed[index].verdict(source, response.packet.size) !is ChainedInboundVerdict.Deliver) return
                    if (index == 0 && configuration.usesNestedTransport) {
                        val relay = ChainedInnerUdpPacket.parse(response.packet)
                        if (relay != null && response.packet.size == ChainedStackAddressTranslation.word(response.packet, 2) &&
                            response.packet[0] == 0x45.toByte() && relay.sourceAddress.contentEquals(exitAddress) &&
                            relay.destinationAddress.contentEquals(entrySource) && relay.sourcePort == profiles.last().endpointPort && relay.destinationPort == relayPort) {
                            receive(1, relay.payload, checkNotNull(exitAddress))
                            return
                        }
                    }
                    val ownership = translations[index].inboundRouteAddress(response.packet) ?: return
                    if (configuration.routeIndexForIPv4(ownership) != index) return
                    val restored = translations[index].inbound(response.packet) ?: return
                    offer(response.copy(packet = restored))
                }
                LavaWgOp.WRITE_TO_TUNNEL_V6, LavaWgOp.NONE -> Unit
            }
            is LavaWgResponse.Failure -> offer(response)
            LavaWgResponse.None -> Unit
        }
    }

    private fun drain(index: Int) {
        repeat(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH) {
            val result = sessions[index].decapsulate(ByteArray(0), ByteArray(0))
            if (result == LavaWgResponse.None) return
            consume(index, result)
            if (result !is LavaWgResponse.Produced || result.op != LavaWgOp.WRITE_TO_NETWORK) return
        }
    }

    private fun offer(response: LavaWgResponse) {
        if (response is LavaWgResponse.Failure) {
            // A saturated packet queue must never erase an engine verdict. The owner classifies
            // its direction and retires the attempt when terminal, before sending old output.
            output.addFirst(response)
            if (output.size > ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH) output.removeLast()
        } else if (output.size < ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH) output.addLast(response)
    }
    override fun takePendingResponse(): LavaWgResponse = pop()
    private fun pop(): LavaWgResponse = if (output.isEmpty()) LavaWgResponse.None else output.removeFirst()

    override fun close() {
        if (closed) return
        closed = true
        output.clear()
        pending.fill(false)
        freshAuthentication.fill(false)
        sessions.asReversed().forEach { runCatching { it.close() } }
    }
}
