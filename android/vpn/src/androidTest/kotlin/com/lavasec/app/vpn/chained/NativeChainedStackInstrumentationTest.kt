package com.lavasec.app.vpn.chained

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.foundation.chained.ChainedInnerUdpPacket
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.vpn.engine.LavaWgCodec
import com.lavasec.app.vpn.engine.LavaWgOp
import com.lavasec.app.vpn.engine.LavaWgResponse
import com.lavasec.app.vpn.engine.NativeLavaWgCore
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Packaged ABI with RFC 7748 public fixture keys and in-memory fixture peers. This proves
 * actual independent cryptographic sessions and nested packets; it does not start a VPN,
 * supply an entitlement, use a live account, or claim external connectivity.
 */
@RunWith(AndroidJUnit4::class)
class NativeChainedStackInstrumentationTest {
    @Test
    fun fullEntryCarriesFullExitHandshakeDataAndReplyThroughTwoActualEngines() {
        withStack(nested = true) { fixture ->
            fixture.handshake()
            val request = udp("10.2.0.2", "203.0.113.8", byteArrayOf(7, 8, 9))
            fixture.send(request)
            assertTrue(fixture.serverTraffic[0].isEmpty())
            assertArrayEquals(request, fixture.serverTraffic[1].single())
            assertTrue(fixture.physicalSends.all { it == 0 })
            val reply = udp("203.0.113.8", "10.2.0.2", byteArrayOf(10, 11, 12))
            fixture.reply(1, reply)
            assertArrayEquals(reply, fixture.clientTraffic.single())
            assertTrue(fixture.stack.stats()!!.timeSinceLastHandshakeMs >= 0)
        }
    }

    @Test
    fun splitEntryAndFullExitUseIndependentDestinationSelectedEnginesAndRestoreAddresses() {
        withStack(nested = false) { fixture ->
            fixture.handshake()
            fixture.send(udp("10.2.0.2", "10.8.1.1", byteArrayOf(1, 2)))
            fixture.send(udp("10.2.0.2", "203.0.113.8", byteArrayOf(3, 4)))
            assertArrayEquals(ip("10.1.0.2"), fixture.serverTraffic[0].single().copyOfRange(12, 16))
            assertArrayEquals(ip("10.2.0.2"), fixture.serverTraffic[1].single().copyOfRange(12, 16))
            assertTrue(fixture.physicalSends.containsAll(listOf(0, 1)))
            fixture.reply(0, udp("10.8.1.1", "10.1.0.2", byteArrayOf(5, 6)))
            assertArrayEquals(udp("10.8.1.1", "10.2.0.2", byteArrayOf(5, 6)), fixture.clientTraffic.single())
        }
    }

    private fun withStack(nested: Boolean, body: (Fixture) -> Unit) {
        val owned = mutableListOf<RawSession>()
        try {
            val clients = listOf(RawSession(ALICE_PRIVATE, BOB_PUBLIC, 0).also { owned += it }, RawSession(BOB_PRIVATE, ALICE_PUBLIC, 1).also { owned += it })
            val servers = listOf(RawSession(BOB_PRIVATE, ALICE_PUBLIC, 2).also { owned += it }, RawSession(ALICE_PRIVATE, BOB_PUBLIC, 3).also { owned += it })
            val entry = profile("198.51.100.1", "10.1.0.2", BOB_PUBLIC, if(nested) listOf("0.0.0.0/0") else listOf("10.8.0.0/16"))
            val exit = profile("198.51.100.2", "10.2.0.2", ALICE_PUBLIC, listOf("0.0.0.0/0"))
            val stack = ChainedStackWireGuardSession(exit.withEntryHop(entry), clients, RELAY_PORT)
            try { body(Fixture(stack, servers, nested)) } finally { stack.close() }
        } finally { owned.forEach { it.close() } }
    }

    private class Fixture(val stack: ChainedStackWireGuardSession, private val servers: List<RawSession>, private val nested: Boolean) {
        val serverTraffic = listOf(mutableListOf<ByteArray>(), mutableListOf<ByteArray>())
        val clientTraffic = mutableListOf<ByteArray>()
        val physicalSends = mutableListOf<Int>()
        private data class Delivery(val row: Int, val toServer: Boolean, val wire: ByteArray)
        private val deliveries = ArrayDeque<Delivery>()
        fun handshake() {
            drainClient(stack.forceHandshake())
            pump()
            assertTrue("both distinct native sessions must complete a handshake", stack.stats()!!.timeSinceLastHandshakeMs >= 0)
        }
        fun send(packet: ByteArray) { drainClient(stack.encapsulate(packet)); pump() }
        fun reply(index: Int, packet: ByteArray) { captureServer(index, servers[index].encapsulate(packet)); pump() }

        private fun captureClient(response: LavaWgResponse) {
            if(response is LavaWgResponse.Produced) when(response.op) {
                LavaWgOp.WRITE_TO_NETWORK -> {
                    val (index, wire) = checkNotNull(ChainedRowDatagram.decode(response.packet))
                    if(nested) assertEquals("a nested exit must have no direct physical output", 0, index)
                    physicalSends += index
                    deliveries += Delivery(index, true, wire)
                }
                LavaWgOp.WRITE_TO_TUNNEL_V4 -> clientTraffic += response.packet
                else -> Unit
            }
            if(response is LavaWgResponse.Failure) fail("native client engine failed: ${response.error}")
        }
        private fun captureServer(index: Int, response: LavaWgResponse) {
            if(response is LavaWgResponse.Produced) when(response.op) {
                LavaWgOp.WRITE_TO_NETWORK -> {
                    if(index == 1 && nested) {
                        val envelope = ChainedInnerUdpPacket.build(ip("198.51.100.2"), ip("10.1.0.2"), 51820, RELAY_PORT, 0, response.packet)
                        captureServer(0, servers[0].encapsulate(envelope))
                    } else deliveries += Delivery(index, false, response.packet)
                }
                LavaWgOp.WRITE_TO_TUNNEL_V4 -> {
                    val relay = if(index == 0 && nested) ChainedInnerUdpPacket.parse(response.packet) else null
                    if(relay != null && relay.destinationAddress.contentEquals(ip("198.51.100.2")) && relay.destinationPort == 51820) {
                        consumeServer(1, relay.payload)
                    } else serverTraffic[index] += response.packet
                }
                else -> Unit
            }
            if(response is LavaWgResponse.Failure) fail("native fixture peer failed: ${response.error}")
        }
        private fun consumeServer(index: Int, packet: ByteArray) {
            var response = servers[index].decapsulate(packet, if(index == 1 && nested) ip("10.1.0.2") else ip("192.0.2.100"))
            repeat(32) {
                captureServer(index, response)
                if(response !is LavaWgResponse.Produced || (response as LavaWgResponse.Produced).op != LavaWgOp.WRITE_TO_NETWORK) return
                response = servers[index].decapsulate(ByteArray(0), ByteArray(0))
            }
            fail("fixture peer drain exceeded native bound")
        }
        private fun drainClient(first: LavaWgResponse) {
            var response = first
            repeat(32) {
                if (response == LavaWgResponse.None) return
                captureClient(response)
                response = stack.decapsulate(ByteArray(0), ByteArray(0))
            }
            assertEquals("native client output exceeded bounded drain", LavaWgResponse.None, response)
        }
        private fun pump() {
            repeat(128) {
                val item = deliveries.removeFirstOrNull()
                if(item != null) {
                    if(item.toServer) consumeServer(item.row, item.wire)
                    else {
                        drainClient(stack.decapsulate(ChainedRowDatagram.encode(item.row, item.wire), ip(if(item.row == 0) "198.51.100.1" else "198.51.100.2")))
                    }
                } else {
                    drainClient(stack.tick())
                    if(deliveries.isEmpty()) return
                }
            }
            fail("native fixture transport did not settle within 128 operations")
        }
    }

    private class RawSession(privateKey: String, publicKey: String, index: Int) : WireGuardSession {
        private var handle = NativeLavaWgCore.sessionNew(hex(privateKey), hex(publicKey), ByteArray(0), 0, index).also { assertTrue(it != 0L) }
        override val isOpen get() = handle != 0L
        override fun encapsulate(packet: ByteArray) = LavaWgCodec.decode(NativeLavaWgCore.encapsulate(handle, packet))
        override fun decapsulate(packet: ByteArray, sourceAddress: ByteArray) = LavaWgCodec.decode(NativeLavaWgCore.decapsulate(handle, packet, sourceAddress))
        override fun tick() = LavaWgCodec.decode(NativeLavaWgCore.tick(handle))
        override fun forceHandshake() = LavaWgCodec.decode(NativeLavaWgCore.forceHandshake(handle))
        override fun stats() = LavaWgCodec.decodeStats(NativeLavaWgCore.sessionStats(handle))
        override fun close() { if(handle != 0L) NativeLavaWgCore.sessionFree(handle); handle = 0L }
    }
    companion object {
        private const val RELAY_PORT = 50001
        // RFC 7748 section 6.1 public test vectors, never production credentials.
        private const val ALICE_PRIVATE = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        private const val ALICE_PUBLIC = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        private const val BOB_PRIVATE = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
        private const val BOB_PUBLIC = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun profile(endpoint: String, client: String, key: String, routes: List<String>) = ChainedUpstreamConfiguration.create(endpoint, 51820, Base64.getEncoder().encodeToString(hex(key)), client, routes, 0, 1420, listOf("10.8.0.1"))
        private fun ip(address: String) = ChainedStackAddressTranslation.octets(address)
        private fun udp(source: String, destination: String, payload: ByteArray) = ChainedInnerUdpPacket.build(ip(source), ip(destination), 12345, 9000, 0, payload)
    }
}
