package com.lavasec.app.foundation.chained

import com.lavasec.app.foundation.chained.ChainedOutboundDisposition.DropMalformed
import com.lavasec.app.foundation.chained.ChainedOutboundDisposition.DropOutboundIPv6
import com.lavasec.app.foundation.chained.ChainedOutboundDisposition.DropUnfilterableDNS
import com.lavasec.app.foundation.chained.ChainedOutboundDisposition.Encapsulate
import com.lavasec.app.foundation.chained.ChainedOutboundDisposition.EncapsulateOwnResolverQuery
import com.lavasec.app.foundation.chained.ChainedOutboundDisposition.HandleAsDNS
import com.lavasec.app.foundation.chained.ChainedPacketFixtures.IPPROTO_TCP
import com.lavasec.app.foundation.chained.ChainedPacketFixtures.IPPROTO_UDP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ChainedOutboundPacketClassifierTest {

    private class FakeResolverPortRegistry : ChainedOwnResolverPortRegistry {
        private val claimed = mutableSetOf<Pair<Int, Int>>()
        private val recent = mutableSetOf<Pair<Int, Int>>()

        fun claim(port: Int, protocolNumber: Int) {
            claimed += port to protocolNumber
            recent -= port to protocolNumber
        }

        fun release(port: Int, protocolNumber: Int) {
            claimed -= port to protocolNumber
            recent += port to protocolNumber
        }

        override fun claims(sourcePort: Int, protocolNumber: Int): Boolean =
            (sourcePort to protocolNumber) in claimed

        override fun wasRecentlyClaimed(sourcePort: Int, protocolNumber: Int): Boolean =
            (sourcePort to protocolNumber) in recent
    }

    private fun classify(
        packet: ByteArray,
        fragments: ChainedDroppedFragmentTable = ChainedDroppedFragmentTable(),
        registry: ChainedOwnResolverPortRegistry = FakeResolverPortRegistry(),
        reclaims: Boolean = true,
    ): ChainedOutboundDisposition =
        ChainedOutboundPacketClassifier.disposition(packet, fragments, registry, reclaims)

    private fun dnsPayload(): ByteArray = ByteArray(12) { 0x11 }

    @Test
    fun `split floor refuses DoT and DoQ only at installed resolver destinations`() {
        val floor = ChainedDnsCaptureFloor.forCapturedResolvers(listOf("4.2.2.2"))
        for (protocol in listOf(ChainedPacketFixtures.IPPROTO_TCP, ChainedPacketFixtures.IPPROTO_UDP)) {
            val transport = if (protocol == ChainedPacketFixtures.IPPROTO_TCP) {
                ChainedPacketFixtures.tcp(40000, 853)
            } else ChainedPacketFixtures.udp(40000, 853, dnsPayload())
            for (destination in listOf("8.8.8.8", "4.2.2.2")) {
                val packet = ChainedPacketFixtures.ipv4(protocol, destination, transport)
                assertEquals(DropUnfilterableDNS, ChainedOutboundPacketClassifier.disposition(
                    packet, ChainedDroppedFragmentTable(), FakeResolverPortRegistry(),
                    claimedResolverDestinations = floor,
                ))
            }
            val ordinary = ChainedPacketFixtures.ipv4(protocol, "4.2.2.3", transport)
            assertEquals(Encapsulate, ChainedOutboundPacketClassifier.disposition(
                ordinary, ChainedDroppedFragmentTable(), FakeResolverPortRegistry(),
                claimedResolverDestinations = floor,
            ))
        }
    }

    @Test
    fun `damaged encrypted heads cannot release query tails and own UDP DNS carveout survives floor`() {
        val floor = ChainedDnsCaptureFloor.forCapturedResolvers(emptyList())
        val fragments = ChainedDroppedFragmentTable()
        val registry = FakeResolverPortRegistry().apply { claim(40123, ChainedPacketFixtures.IPPROTO_UDP) }
        val head = ChainedPacketFixtures.ipv4(
            ChainedPacketFixtures.IPPROTO_UDP, "8.8.8.8",
            ChainedPacketFixtures.udp(40000, 853, dnsPayload()),
            identification = 17, moreFragments = true, totalLength = 999,
        )
        val tail = ChainedPacketFixtures.ipv4(
            ChainedPacketFixtures.IPPROTO_UDP, "8.8.8.8", ByteArray(8),
            identification = 17, fragmentOffset = 1,
        )
        for (packet in listOf(head, tail, tail)) {
            assertEquals(DropUnfilterableDNS, ChainedOutboundPacketClassifier.disposition(
                packet, fragments, registry, claimedResolverDestinations = floor,
            ))
        }
        val own = ChainedPacketFixtures.ipv4(
            ChainedPacketFixtures.IPPROTO_UDP, "8.8.8.8", ChainedPacketFixtures.udp(40123, 53, dnsPayload()),
        )
        assertEquals(EncapsulateOwnResolverQuery, ChainedOutboundPacketClassifier.disposition(
            own, fragments, registry, claimedResolverDestinations = floor,
        ))
        registry.release(40123, ChainedPacketFixtures.IPPROTO_UDP)
        assertEquals(DropUnfilterableDNS, ChainedOutboundPacketClassifier.disposition(
            own, fragments, registry, claimedResolverDestinations = floor,
        ))
    }

    @Test
    fun `served IPv6 DNS admission matches codec and refuses claimed or retired IPv4 ports`() {
        val floor = ChainedDnsCaptureFloor.forCapturedResolvers(emptyList())
        val registry = FakeResolverPortRegistry()
        val packet = ChainedInnerUdpPacket.build(
            InetAddress.getByName("fd00:1a7a::2").address,
            InetAddress.getByName("2606:4700:4700::1111").address, 40000, 53, 0, dnsPayload(),
        )
        fun serve(bytes: ByteArray): ChainedOutboundDisposition = ChainedOutboundPacketClassifier.disposition(
            bytes, ChainedDroppedFragmentTable(), registry, claimedResolverDestinations = floor, servesIPv6Dns = true,
        )
        assertEquals(HandleAsDNS, serve(packet))
        assertTrue(ChainedInnerUdpPacket.parseClientDns(packet) != null)
        registry.claim(40000, ChainedPacketFixtures.IPPROTO_UDP)
        assertEquals(DropUnfilterableDNS, serve(packet))
        registry.release(40000, ChainedPacketFixtures.IPPROTO_UDP)
        assertEquals(DropUnfilterableDNS, serve(packet))
        assertEquals(DropUnfilterableDNS, serve(packet.copyOf().also { it[4] = 0x7f }))
        for (header in listOf(0, 43, 44, 60, 6)) {
            assertEquals(DropOutboundIPv6, serve(packet.copyOf().also { it[6] = header.toByte() }))
        }
        val doq = packet.copyOf().also { it[42] = 3; it[43] = 85 } // 853
        assertEquals(DropUnfilterableDNS, serve(doq))
        assertEquals(DropOutboundIPv6, classify(packet)) // A path that did not advertise IPv6 keeps its old shape.
    }

    @Test
    fun `IPv6 client collision with a real T0 port never acquires the IPv4 own query carveout`() {
        val ports = ChainedOwnResolverPorts(nowMillis = { 0L }, firstPort = 40000, lastPort = 40000)
        val sourcePort = requireNotNull(ports.claimForUdp())
        val ipv4 = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP, "8.8.8.8", ChainedPacketFixtures.udp(sourcePort, 53, dnsPayload()),
        )
        val ipv6 = ChainedInnerUdpPacket.build(
            InetAddress.getByName("fd00:1a7a::2").address,
            InetAddress.getByName("2606:4700:4700::1111").address,
            sourcePort, 53, 0, dnsPayload(),
        )
        assertEquals(EncapsulateOwnResolverQuery, classify(ipv4, registry = ports))
        assertEquals(DropUnfilterableDNS, ChainedOutboundPacketClassifier.disposition(
            ipv6, ChainedDroppedFragmentTable(), ports, servesIPv6Dns = true,
        ))
        ports.release(sourcePort)
        assertEquals(DropUnfilterableDNS, ChainedOutboundPacketClassifier.disposition(
            ipv6, ChainedDroppedFragmentTable(), ports, servesIPv6Dns = true,
        ))
    }

    // MARK: - Version and header shape

    @Test
    fun `an empty packet is malformed`() {
        assertEquals(DropMalformed, classify(ByteArray(0)))
    }

    @Test
    fun `an outbound IPv6 packet is dropped deliberately`() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        assertEquals(DropOutboundIPv6, classify(packet))
    }

    @Test
    fun `a version that is neither 4 nor 6 is malformed`() {
        val packet = ByteArray(40)
        packet[0] = 0x30
        assertEquals(DropMalformed, classify(packet))
    }

    @Test
    fun `a sub-header IPv4 packet is malformed`() {
        val packet = ByteArray(19)
        packet[0] = 0x45
        assertEquals(DropMalformed, classify(packet))
    }

    @Test
    fun `an IHL below the fixed header is malformed`() {
        val packet = ByteArray(40)
        packet[0] = 0x40
        assertEquals(DropMalformed, classify(packet))
    }

    // MARK: - Ordinary traffic

    @Test
    fun `a bare TCP ACK is encapsulated`() {
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_TCP,
            destination = "1.2.3.4",
            transport = ChainedPacketFixtures.tcp(40000, 80),
        )
        assertEquals(Encapsulate, classify(packet))
    }

    @Test
    fun `a non-DNS UDP datagram is encapsulated`() {
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 443, dnsPayload()),
        )
        assertEquals(Encapsulate, classify(packet))
    }

    // MARK: - DNS interception

    @Test
    fun `a DNS query to a public resolver is still intercepted`() {
        // Interception is keyed on port 53, not on the tunnel resolver's address: narrowing it
        // would hand a hardcoded resolver query to the peer unfiltered and legible.
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 53, dnsPayload()),
        )
        assertEquals(HandleAsDNS, classify(packet))
    }

    @Test
    fun `a DNS query to the tunnel proxy is intercepted`() {
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "10.111.0.1",
            transport = ChainedPacketFixtures.udp(40000, 53, dnsPayload()),
        )
        assertEquals(HandleAsDNS, classify(packet))
    }

    @Test
    fun `DNS over TCP is dropped rather than handed to the peer`() {
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_TCP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.tcp(40000, 53),
        )
        assertEquals(DropUnfilterableDNS, classify(packet))
    }

    @Test
    fun `an empty DNS datagram is dropped rather than served`() {
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "10.111.0.1",
            transport = ChainedPacketFixtures.udp(40000, 53, ByteArray(0)),
        )
        assertEquals(DropUnfilterableDNS, classify(packet))
    }

    @Test
    fun `an unfragmented UDP length that overruns its datagram is malformed`() {
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            // The UDP header claims 100 bytes while the datagram is 28.
            transport = ChainedPacketFixtures.udp(40000, 443, ByteArray(0), lengthOverride = 100),
        )
        assertEquals(DropMalformed, classify(packet))
    }

    // MARK: - The own-resolver carve-out

    @Test
    fun `our own UDP query is encapsulated rather than re-resolved`() {
        val registry = FakeResolverPortRegistry().apply { claim(40001, IPPROTO_UDP) }
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "10.111.0.1",
            transport = ChainedPacketFixtures.udp(40001, 53, dnsPayload()),
        )
        assertEquals(EncapsulateOwnResolverQuery, classify(packet, registry = registry))
    }

    @Test
    fun `the UDP carve-out is exactly the claim and nothing else`() {
        val registry = FakeResolverPortRegistry().apply { claim(40002, IPPROTO_UDP) }
        val other = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "10.111.0.1",
            transport = ChainedPacketFixtures.udp(40003, 53, dnsPayload()),
        )
        // A different source port is a CLIENT query, and is served locally.
        assertEquals(HandleAsDNS, classify(other, registry = registry))
    }

    @Test
    fun `a UDP claim does not carve out TCP on the same port`() {
        val registry = FakeResolverPortRegistry().apply { claim(40004, IPPROTO_UDP) }
        val tcp = ChainedPacketFixtures.ipv4(
            IPPROTO_TCP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.tcp(40004, 53),
        )
        assertEquals(DropUnfilterableDNS, classify(tcp, registry = registry))
    }

    @Test
    fun `our own TCP DNS retry is encapsulated`() {
        val registry = FakeResolverPortRegistry().apply { claim(40006, IPPROTO_TCP) }
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_TCP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.tcp(40006, 53),
        )
        assertEquals(EncapsulateOwnResolverQuery, classify(packet, registry = registry))
    }

    @Test
    fun `a query from a just-released port is dropped not served`() {
        // The socket is gone, but a packet classified after its claim ended must not be read as a
        // client query or our own resolver would answer it by sending another.
        val registry = FakeResolverPortRegistry().apply {
            claim(40005, IPPROTO_UDP)
            release(40005, IPPROTO_UDP)
        }
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "10.111.0.1",
            transport = ChainedPacketFixtures.udp(40005, 53, dnsPayload()),
        )
        assertEquals(DropUnfilterableDNS, classify(packet, registry = registry))
    }

    // MARK: - Fragmentation

    @Test
    fun `a fragmented DNS query is dropped and its tails too`() {
        val fragments = ChainedDroppedFragmentTable()
        val head = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 53, dnsPayload()),
            identification = 7,
            moreFragments = true,
        )
        assertEquals(DropUnfilterableDNS, classify(head, fragments = fragments))

        val tail = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ByteArray(8),
            identification = 7,
            fragmentOffset = 8,
        )
        assertEquals(DropUnfilterableDNS, classify(tail, fragments = fragments))
    }

    @Test
    fun `a non-DNS fragment head is encapsulated and its tails too`() {
        val fragments = ChainedDroppedFragmentTable()
        val head = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 443, dnsPayload()),
            identification = 8,
            moreFragments = true,
        )
        assertEquals(Encapsulate, classify(head, fragments = fragments))

        val tail = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ByteArray(8),
            identification = 8,
            fragmentOffset = 8,
        )
        assertEquals(Encapsulate, classify(tail, fragments = fragments))
    }

    @Test
    fun `a non-first fragment whose head is unknown is encapsulated`() {
        val tail = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ByteArray(8),
            identification = 9,
            fragmentOffset = 8,
        )
        assertEquals(Encapsulate, classify(tail))
    }

    @Test
    fun `an out-of-order tail is still dropped after the final one`() {
        val fragments = ChainedDroppedFragmentTable()
        val head = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 53, dnsPayload()),
            identification = 11,
            moreFragments = true,
        )
        classify(head, fragments = fragments)

        fun tail(): ByteArray = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ByteArray(8),
            identification = 11,
            fragmentOffset = 8,
        )
        // A tail never releases the denial; another tail still arrives.
        assertEquals(DropUnfilterableDNS, classify(tail(), fragments = fragments))
        assertEquals(DropUnfilterableDNS, classify(tail(), fragments = fragments))
    }

    @Test
    fun `a damaged length field does not forward a DNS query`() {
        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 53, dnsPayload()),
            totalLength = 10,
        )
        assertEquals(DropUnfilterableDNS, classify(packet))
    }

    @Test
    fun `a damaged fragment head still silences its tails`() {
        val fragments = ChainedDroppedFragmentTable()
        val head = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 53, dnsPayload()),
            identification = 12,
            moreFragments = true,
            totalLength = 10,
        )
        assertEquals(DropUnfilterableDNS, classify(head, fragments = fragments))

        val tail = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ByteArray(8),
            identification = 12,
            fragmentOffset = 8,
        )
        assertEquals(DropUnfilterableDNS, classify(tail, fragments = fragments))
    }

    // MARK: - Reclaim rules

    @Test
    fun `an unfragmented permitted datagram does not clear the denial`() {
        val fragments = ChainedDroppedFragmentTable()
        val identity = ChainedFragmentIdentity(
            source = address("10.7.0.2"),
            destination = address("8.8.8.8"),
            identification = 20,
            protocolNumber = IPPROTO_UDP,
        )
        fragments.remember(identity)

        val packet = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 443, dnsPayload()),
            identification = 20,
        )
        assertEquals(Encapsulate, classify(packet, fragments = fragments))
        assertTrue("an unfragmented datagram is no evidence about fragments", fragments.contains(identity))
    }

    @Test
    fun `a permitted head reusing an identification clears the stale denial`() {
        val fragments = ChainedDroppedFragmentTable()
        val identity = ChainedFragmentIdentity(
            source = address("10.7.0.2"),
            destination = address("8.8.8.8"),
            identification = 21,
            protocolNumber = IPPROTO_UDP,
        )
        fragments.remember(identity)

        val head = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 443, dnsPayload()),
            identification = 21,
            moreFragments = true,
        )
        assertEquals(Encapsulate, classify(head, fragments = fragments))
        assertFalse(fragments.contains(identity))
    }

    @Test
    fun `a head does not reclaim when older packets are still waiting`() {
        val fragments = ChainedDroppedFragmentTable()
        val identity = ChainedFragmentIdentity(
            source = address("10.7.0.2"),
            destination = address("8.8.8.8"),
            identification = 22,
            protocolNumber = IPPROTO_UDP,
        )
        fragments.remember(identity)

        val head = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 443, dnsPayload()),
            identification = 22,
            moreFragments = true,
        )
        assertEquals(
            Encapsulate,
            classify(head, fragments = fragments, reclaims = false),
        )
        assertTrue(fragments.contains(identity))
    }

    // MARK: - Options and the destination read

    @Test
    fun `IPv4 options shift the transport offsets`() {
        val tcp = ChainedPacketFixtures.ipv4(
            IPPROTO_TCP,
            destination = "1.2.3.4",
            transport = ChainedPacketFixtures.tcp(40000, 80),
            headerLength = 24,
        )
        assertEquals(Encapsulate, classify(tcp))

        val dns = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 53, dnsPayload()),
            headerLength = 24,
        )
        assertEquals(HandleAsDNS, classify(dns))
    }

    @Test
    fun `the destination read is IPv4 only and bounds checked`() {
        val v4 = ChainedPacketFixtures.ipv4(
            IPPROTO_UDP,
            destination = "8.8.8.8",
            transport = ChainedPacketFixtures.udp(40000, 443, dnsPayload()),
        )
        assertEquals(address("8.8.8.8"), ChainedOutboundPacketClassifier.ipv4Destination(v4))

        val v6 = ByteArray(40)
        v6[0] = 0x60
        assertNull(ChainedOutboundPacketClassifier.ipv4Destination(v6))

        val short = ByteArray(19)
        short[0] = 0x45
        assertNull(ChainedOutboundPacketClassifier.ipv4Destination(short))
    }

    @Test
    fun `the disposition log values are stable`() {
        assertEquals("handle-as-dns", HandleAsDNS.logValue)
        assertEquals("encapsulate", Encapsulate.logValue)
        assertEquals("drop-outbound-ipv6", DropOutboundIPv6.logValue)
        assertEquals("drop-unfilterable-dns", DropUnfilterableDNS.logValue)
        assertEquals("encapsulate-own-resolver-query", EncapsulateOwnResolverQuery.logValue)
        assertEquals("drop-malformed", DropMalformed.logValue)
    }

    @Test
    fun `every disposition case is a singleton so classification allocates nothing`() {
        val registry = FakeResolverPortRegistry().apply { claim(40001, IPPROTO_UDP) }
        assertSame(
            Encapsulate,
            classify(
                ChainedPacketFixtures.ipv4(
                    IPPROTO_TCP,
                    destination = "1.2.3.4",
                    transport = ChainedPacketFixtures.tcp(40000, 80),
                ),
            ),
        )
        assertSame(
            HandleAsDNS,
            classify(
                ChainedPacketFixtures.ipv4(
                    IPPROTO_UDP,
                    destination = "8.8.8.8",
                    transport = ChainedPacketFixtures.udp(40000, 53, dnsPayload()),
                ),
            ),
        )
        assertSame(DropOutboundIPv6, classify(ByteArray(40).apply { this[0] = 0x60 }))
        assertSame(
            DropUnfilterableDNS,
            classify(
                ChainedPacketFixtures.ipv4(
                    IPPROTO_TCP,
                    destination = "8.8.8.8",
                    transport = ChainedPacketFixtures.tcp(40000, 53),
                ),
            ),
        )
        assertSame(DropMalformed, classify(ByteArray(0)))
        assertSame(
            EncapsulateOwnResolverQuery,
            classify(
                ChainedPacketFixtures.ipv4(
                    IPPROTO_UDP,
                    destination = "10.111.0.1",
                    transport = ChainedPacketFixtures.udp(40001, 53, dnsPayload()),
                ),
                registry = registry,
            ),
        )
    }

    private fun address(text: String): Long =
        InetAddress.getByName(text).address.fold(0L) { acc, byte ->
            (acc shl 8) or (byte.toLong() and 0xFF)
        }
}
