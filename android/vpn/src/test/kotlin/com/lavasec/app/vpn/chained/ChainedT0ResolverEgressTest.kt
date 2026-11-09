package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedInnerUdpPacket
import com.lavasec.app.foundation.chained.ChainedOwnResolverPorts
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.health.PrimaryResolverOutcome
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.DnsFallbackDisposition
import com.lavasec.app.foundation.health.DnsFallbackObservation
import com.lavasec.app.foundation.health.DnsFallbackReason
import com.lavasec.app.foundation.health.DnsFallbackResponseClass
import com.lavasec.app.foundation.health.DnsHttpProtocol
import com.lavasec.app.foundation.health.ResolverTierDiagnosticObservation
import com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.vpn.ResolverResult
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.vpn.ArtifactBackedProxyPolicySource
import com.lavasec.app.vpn.FactoryFilterFixture
import com.lavasec.app.vpn.ProxyPolicyControl
import com.lavasec.app.vpn.ProxyPolicyControlSource
import com.lavasec.app.platform.FilterRuntimeLeaseRead
import com.lavasec.app.foundation.dns.DnsMessage
import kotlinx.coroutines.test.runTest
import com.lavasec.app.vpn.DecisionEventIdSource
import com.lavasec.app.vpn.EpochMillisClock
import com.lavasec.app.vpn.ProxyPolicySnapshot
import com.lavasec.app.vpn.TestDecisionSource
import com.lavasec.app.vpn.TestPolicySource
import com.lavasec.app.vpn.TestPublicationFence
import com.lavasec.app.vpn.TunWriteAttempt
import com.lavasec.app.vpn.VpnSessionDiagnostics
import com.lavasec.app.vpn.VpnTierDiagnostics
import com.lavasec.app.vpn.testPolicyIdentity
import java.util.Base64
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T0 client-DNS egress, driven with no engine and no socket: every outbound byte is captured
 * from the bound `sendQuery`, and every client response from the bound write path.
 */
class ChainedT0ResolverEgressTest {

    private var now = 1_000L

    private val clientAddress = byteArrayOf(10, 7, 0, 2)
    private val resolverAddress = byteArrayOf(10, 64, 0, 1)
    private val dnsProxyAddress = byteArrayOf(10, 111, 0, 1)

    @Test
    fun `IPv6 client uses IPv4 T0 and publishes exact reverse tuple only at successful client write`() {
        val source6 = InetAddress.getByName("fd00:1a7a::2").address
        val destination6 = InetAddress.getByName("2606:4700:4700::1111").address
        val query = dnsQuery("example.com", TYPE_AAAA, 0x1234)
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), deferWrites = true)
        h.egress.handleQuery(ChainedInnerUdpPacket.build(source6, destination6, 40000, 53, 0, query))
        val upstream = ChainedInnerUdpPacket.parse(h.sent.single())!!
        assertArrayEquals(clientAddress, upstream.sourceAddress)
        assertArrayEquals(resolverAddress, upstream.destinationAddress)
        assertArrayEquals(query, upstream.payload)
        val answer = dnsResponse(query)
        assertTrue(h.egress.observeInbound(resolverReplyPacket(answer, upstream.sourcePort)))
        assertTrue(h.events.isEmpty())
        assertTrue(h.evidence.isEmpty())
        val reply = h.replies.single()
        assertEquals(TunWriteAttempt.Written, reply.tryWrite { packet ->
            val response = ChainedInnerUdpPacket.parseIPv6Udp(packet)!!
            assertArrayEquals(destination6, response.sourceAddress)
            assertArrayEquals(source6, response.destinationAddress)
            assertEquals(53, response.sourcePort)
            assertEquals(40000, response.destinationPort)
            assertArrayEquals(answer, response.payload)
            TunWriteAttempt.Written
        })
        assertEquals(ResolverTransport.WIREGUARD_UDP, h.events.single().transport)
        assertEquals(PrimaryResolverOutcome.ACCEPTED, h.evidence.single().primaryOutcome)
        assertEquals(null, reply.tryWrite { error("IPv6 receipt cannot be duplicated") })
        h.egress.close()
    }

    @Test
    fun `IPv6 local blocking and full-mode AAAA NODATA produce actual IPv6 replies without peer work`() {
        val source6 = InetAddress.getByName("fd00:1a7a::2").address
        val listener6 = InetAddress.getByName("fd00:1a7a::1").address
        val denied = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), decision = blocked)
        val query = dnsQuery("ads.example", TYPE_A, 1)
        denied.egress.handleQuery(ChainedInnerUdpPacket.build(source6, listener6, 40000, 53, 0, query))
        val blockedReply = ChainedInnerUdpPacket.parseIPv6Udp(denied.written.single())!!
        assertArrayEquals(source6, blockedReply.destinationAddress)
        assertArrayEquals(listener6, blockedReply.sourceAddress)
        assertTrue(denied.sent.isEmpty())
        assertEquals(blocked, denied.events.single().decision)
        assertTrue(denied.evidence.isEmpty())
        denied.egress.close()
        val full = harness(ChainedOwnResolverPorts(nowMillis = { now }))
        full.egress.handleQuery(ChainedInnerUdpPacket.build(source6, listener6, 40001, 53, 0, dnsQuery("example.com", TYPE_AAAA, 2)))
        val noData = ChainedInnerUdpPacket.parseIPv6Udp(full.written.single())!!.payload
        assertEquals(0, noData[3].toInt() and 15)
        assertEquals(0, noData[6].toInt())
        assertEquals(0, noData[7].toInt())
        assertTrue(full.sent.isEmpty())
        full.egress.close()
    }

    @Test
    fun `IPv6 retained reply cannot write or record after artifact retirement`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), decision = blocked, deferWrites = true)
        h.egress.handleQuery(ChainedInnerUdpPacket.build(
            InetAddress.getByName("fd00:1a7a::2").address, InetAddress.getByName("fd00:1a7a::1").address,
            40000, 53, 0, dnsQuery("ads.example", TYPE_A, 1),
        ))
        val old = h.source.snapshot
        h.source.snapshot = ProxyPolicySnapshot(old.identity.copy(artifactDigest = "retired"), old.activeProtectionSessionId, old.pause, old.decisions)
        assertEquals(null, h.replies.single().tryWrite { error("retired IPv6 result cannot write") })
        assertTrue(h.events.isEmpty())
        assertTrue(h.evidence.isEmpty())
        h.egress.close()
    }

    @Test
    fun `only timeout-only resolution reports an unanswered hashed name with its starting epoch`() {
        val observations = mutableListOf<Pair<Long, String?>>()
        var epoch = 10L
        val harness = harness(ChainedOwnResolverPorts(nowMillis = { now }), epoch = { epoch }, observe = { e, n -> observations += e to n })
        val query = dnsQuery("example.com", TYPE_A, txid = 9)
        harness.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        epoch = 11L
        now += 5001
        harness.egress.expire()
        assertEquals(10L, observations.single().first)
        assertEquals(64, observations.single().second!!.length)
        assertFalse(observations.single().second!!.contains("example"))
    }

    @Test
    fun `wrong-transaction reply proves resolver liveness but off-source reply does not`() {
        val observations = mutableListOf<Pair<Long, String?>>()
        val harness = harness(ChainedOwnResolverPorts(nowMillis = { now }), observe = { e, n -> observations += e to n })
        val query = dnsQuery("example.com", TYPE_A, txid = 9)
        harness.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val port = ChainedInnerUdpPacket.parse(harness.sent.single())!!.sourcePort
        val wrong = dnsResponse(query).also { it[1] = 10 }
        harness.egress.observeInbound(resolverReplyPacket(wrong, port, byteArrayOf(1, 1, 1, 1)))
        assertTrue(observations.isEmpty())
        harness.egress.observeInbound(resolverReplyPacket(wrong, port))
        assertEquals(listOf(0L to null), observations)
        assertTrue(harness.written.isEmpty())
        now += 5001
        harness.egress.expire()
        assertEquals(1, observations.size)
    }

    @Test
    fun `a client query is rebuilt for the resolver with a claimed source port`() {
        val ports = ChainedOwnResolverPorts(nowMillis = { now })
        val harness = harness(ports)
        val query = dnsQuery("example.com", TYPE_A, txid = 0x1234)

        harness.egress.handleQuery(
            clientQueryPacket(query, sourcePort = 40000, destination = dnsProxyAddress),
        )

        assertEquals(1, harness.sent.size)
        val sent = ChainedInnerUdpPacket.parse(harness.sent.single())!!
        assertArrayEquals(clientAddress, sent.sourceAddress)
        assertArrayEquals(resolverAddress, sent.destinationAddress)
        assertEquals(53, sent.destinationPort)
        assertArrayEquals(query, sent.payload)
        assertTrue(ports.claims(sent.sourcePort, UDP_PROTOCOL))
        assertTrue(harness.written.isEmpty())
    }

    @Test
    fun `a valid reply is rewritten back to the original client tuple`() {
        val ports = ChainedOwnResolverPorts(nowMillis = { now })
        val harness = harness(ports)
        val query = dnsQuery("example.com", TYPE_A, txid = 0x1234)
        harness.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val resolverPort = ChainedInnerUdpPacket.parse(harness.sent.single())!!.sourcePort
        val response = dnsResponse(query)

        val consumed = harness.egress.observeInbound(
            resolverReplyPacket(response, resolverPort),
        )

        assertTrue(consumed)
        assertEquals(1, harness.written.size)
        val written = ChainedInnerUdpPacket.parse(harness.written.single())!!
        assertArrayEquals(dnsProxyAddress, written.sourceAddress)
        assertArrayEquals(clientAddress, written.destinationAddress)
        assertEquals(53, written.sourcePort)
        assertEquals(40000, written.destinationPort)
        assertArrayEquals(response, written.payload)
        assertFalse(ports.claims(resolverPort, UDP_PROTOCOL))
    }

    @Test
    fun `an ordinary inbound packet is not consumed`() {
        val harness = harness(ChainedOwnResolverPorts(nowMillis = { now }))

        val consumed = harness.egress.observeInbound(
            ChainedInnerUdpPacket.build(
                sourceAddress = byteArrayOf(203.toByte(), 0, 113, 1),
                destinationAddress = clientAddress,
                sourcePort = 443,
                destinationPort = 55555,
                identification = 1,
                payload = byteArrayOf(1, 2, 3),
            ),
        )

        assertFalse(consumed)
    }

    @Test
    fun `AAAA is answered NODATA while full tunnel and never sent upstream`() {
        val harness = harness(ChainedOwnResolverPorts(nowMillis = { now }))
        val query = dnsQuery("example.com", TYPE_AAAA, txid = 0x0102)

        harness.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))

        assertTrue(harness.sent.isEmpty())
        assertEquals(1, harness.written.size)
        val written = ChainedInnerUdpPacket.parse(harness.written.single())!!
        val payload = written.payload
        assertEquals(0x0102, readU16(payload, 0))
        assertTrue(payload[2].toInt() and 0x80 != 0)
        assertEquals(0, readU16(payload, 6)) // ANCOUNT 0
        assertArrayEquals(
            query.copyOfRange(12, query.size),
            payload.copyOfRange(12, 12 + (query.size - 12)),
        )
    }

    @Test
    fun `AAAA is forwarded while split tunnel`() {
        val harness = harness(
            ChainedOwnResolverPorts(nowMillis = { now }),
            configuration = splitConfiguration(),
        )
        val query = dnsQuery("example.com", TYPE_AAAA, txid = 0x0102)

        harness.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))

        assertEquals(1, harness.sent.size)
        assertTrue(harness.written.isEmpty())
    }

    @Test
    fun `the ipv6hint is stripped from an HTTPS reply while full tunnel`() {
        val ports = ChainedOwnResolverPorts(nowMillis = { now })
        val harness = harness(ports)
        val query = dnsQuery("example.com", TYPE_HTTPS, txid = 0x2222)
        harness.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val resolverPort = ChainedInnerUdpPacket.parse(harness.sent.single())!!.sourcePort
        val response = dnsResponse(query, answers = serviceBindingAnswer(withIpv6Hint = true))

        harness.egress.observeInbound(resolverReplyPacket(response, resolverPort))

        val writtenPayload = ChainedInnerUdpPacket.parse(harness.written.single())!!.payload
        assertEquals(response.size - IPV6_HINT_PARAM_BYTES, writtenPayload.size)
        assertTrue(containsSequence(writtenPayload, IPV4_HINT_MARKER))
        assertFalse(containsSequence(writtenPayload, IPV6_HINT_MARKER))
    }

    @Test
    fun `an HTTPS reply is passed through unchanged while split tunnel`() {
        val harness = harness(
            ChainedOwnResolverPorts(nowMillis = { now }),
            configuration = splitConfiguration(),
        )
        val query = dnsQuery("example.com", TYPE_HTTPS, txid = 0x2222)
        harness.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val resolverPort = ChainedInnerUdpPacket.parse(harness.sent.single())!!.sourcePort
        val response = dnsResponse(query, answers = serviceBindingAnswer(withIpv6Hint = true))

        harness.egress.observeInbound(resolverReplyPacket(response, resolverPort))

        val writtenPayload = ChainedInnerUdpPacket.parse(harness.written.single())!!.payload
        assertArrayEquals(response, writtenPayload)
        assertTrue(containsSequence(writtenPayload, IPV6_HINT_MARKER))
    }

    @Test
    fun `a spoofed reply is consumed and dropped without completing the lookup`() {
        val ports = ChainedOwnResolverPorts(nowMillis = { now })
        val harness = harness(ports)
        val query = dnsQuery("example.com", TYPE_A, txid = 0x1234)
        harness.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val resolverPort = ChainedInnerUdpPacket.parse(harness.sent.single())!!.sourcePort
        val spoofed = dnsResponse(query).also { it[0] = 0x7f; it[1] = 0xff.toByte() }

        val consumed = harness.egress.observeInbound(
            resolverReplyPacket(spoofed, resolverPort),
        )

        assertTrue(consumed)
        assertTrue(harness.written.isEmpty())
        assertTrue(ports.claims(resolverPort, UDP_PROTOCOL))
    }

    @Test
    fun `a malformed question is answered fail-closed with SERVFAIL`() {
        val harness = harness(ChainedOwnResolverPorts(nowMillis = { now }))
        // A well-formed 12-byte header with a declared question that is absent.
        val malformed = ByteArray(12).also { putU16(it, 0, 0x3333); putU16(it, 2, 0x0100); putU16(it, 4, 1) }

        harness.egress.handleQuery(clientQueryPacket(malformed, 40000, dnsProxyAddress))

        assertEquals(1, harness.written.size)
        val payload = ChainedInnerUdpPacket.parse(harness.written.single())!!.payload
        assertTrue(payload[2].toInt() and 0x80 != 0)
        assertEquals(2, payload[3].toInt() and 0x0f) // SERVFAIL
    }

    @Test
    fun `no usable resolver answers SERVFAIL and sends nothing`() {
        val harness = harness(
            ChainedOwnResolverPorts(nowMillis = { now }),
            configuration = configurationWithDns(emptyList()),
        )
        val query = dnsQuery("example.com", TYPE_A, txid = 0x1234)

        harness.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))

        assertTrue(harness.sent.isEmpty())
        assertEquals(1, harness.written.size)
        val payload = ChainedInnerUdpPacket.parse(harness.written.single())!!.payload
        assertEquals(2, payload[3].toInt() and 0x0f)
    }

    @Test
    fun `a timeout fails over to the next resolver then answers SERVFAIL`() {
        val ports = ChainedOwnResolverPorts(nowMillis = { now })
        val harness = harness(
            ports,
            configuration = configurationWithDns(listOf("10.64.0.1", "10.64.0.2")),
            queryTimeoutMillis = 1_000L,
        )
        val query = dnsQuery("example.com", TYPE_A, txid = 0x1234)
        harness.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        assertEquals(1, harness.sent.size)
        val first = ChainedInnerUdpPacket.parse(harness.sent.single())!!

        now += 501L
        harness.egress.expire()

        assertEquals(2, harness.sent.size)
        val second = ChainedInnerUdpPacket.parse(harness.sent[1])!!
        assertArrayEquals(first.sourceAddress, second.sourceAddress)
        assertArrayEquals(byteArrayOf(10, 64, 0, 2), second.destinationAddress)

        now += 1_001L
        harness.egress.expire()

        assertEquals(1, harness.written.size)
        val payload = ChainedInnerUdpPacket.parse(harness.written.single())!!.payload
        assertEquals(2, payload[3].toInt() and 0x0f)
        assertFalse(ports.claims(first.sourcePort, UDP_PROTOCOL))
        assertEquals(PrimaryResolverOutcome.FAILED, harness.evidence.single().primaryOutcome)
    }

    @Test
    fun `blocked A and AAAA never emit upstream and preserve filter reasons in Activity`() {
        for (type in listOf(TYPE_A, TYPE_AAAA)) {
            val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), decision = blocked)
            h.egress.handleQuery(clientQueryPacket(dnsQuery("ads.example", type, 1), 40000, dnsProxyAddress))
            assertTrue(h.sent.isEmpty())
            assertEquals(blocked, h.events.single().decision)
            assertEquals(null, h.events.single().resolver)
            assertEquals(1, h.written.size)
        }
    }

    @Test
    fun `allowed response records the configured tunnel transport only after client write`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), deferWrites = true)
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val port = ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort
        assertTrue(h.events.isEmpty())
        h.egress.observeInbound(resolverReplyPacket(dnsResponse(query), port))
        assertTrue(h.events.isEmpty())
        val response = h.replies.single()
        assertEquals(TunWriteAttempt.WouldBlock, response.tryWrite { TunWriteAttempt.WouldBlock })
        assertTrue(h.events.isEmpty())
        assertEquals(TunWriteAttempt.Written, response.tryWrite { h.written += it; TunWriteAttempt.Written })
        assertEquals(ResolverKind.WIREGUARD_T0, h.events.single().resolver)
        assertEquals(ResolverTransport.WIREGUARD_UDP, h.events.single().transport)
        assertEquals(PrimaryResolverOutcome.ACCEPTED, h.evidence.single().primaryOutcome)
        assertEquals(h.events.single().id, h.evidence.single().queryId)
        assertEquals(null, response.tryWrite { error("must not write twice") })
        assertEquals(1, h.events.size)
    }

    @Test
    fun `unavailable filter fails closed without fabricated Activity`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), decision =
            FilterDecision(FilterAction.BLOCK, FilterDecisionReason.PROTECTION_UNAVAILABLE))
        h.egress.handleQuery(clientQueryPacket(dnsQuery("example.com", TYPE_A, 1), 40000, dnsProxyAddress))
        assertTrue(h.sent.isEmpty())
        assertTrue(h.events.isEmpty())
        assertTrue(h.health.single() is DnsHealthEvent.ProtectionUnavailable)
        assertEquals(2, ChainedInnerUdpPacket.parse(h.written.single())!!.payload[3].toInt() and 15)
    }

    @Test
    fun `superseded generation suppresses retained replies and resolver retries`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), deferWrites = true)
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        h.egress.observeInbound(resolverReplyPacket(dnsResponse(query), ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
        h.fence.current = false
        assertEquals(null, h.replies.single().tryWrite { error("stale response escaped") })
        assertTrue(h.events.isEmpty())
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        now += 6_000
        h.egress.expire()
        assertEquals(1, h.sent.size)
    }

    @Test
    fun `different artifact or protection session cannot publish an old response`() {
        for (changeArtifact in listOf(true, false)) {
            val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), deferWrites = true)
            val query = dnsQuery("example.com", TYPE_A, 1)
            h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
            h.egress.observeInbound(resolverReplyPacket(dnsResponse(query), ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
            val old = h.source.snapshot
            h.source.snapshot = ProxyPolicySnapshot(
                identity = if (changeArtifact) old.identity.copy(artifactDigest = "new-artifact") else old.identity,
                activeProtectionSessionId = if (changeArtifact) old.activeProtectionSessionId else 99,
                pause = old.pause,
                decisions = old.decisions,
            )
            assertEquals(null, h.replies.single().tryWrite { error("mismatched result escaped") })
            assertTrue(h.events.isEmpty())
        }
    }

    @Test
    fun `pause expiring during TUN backpressure restores blocked result and Activity`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), decision = blocked, deferWrites = true,
            pause = RuntimePauseDirective.Paused(PauseState(now + 500, 41)))
        val query = dnsQuery("ads.example", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        h.egress.observeInbound(resolverReplyPacket(dnsResponse(query), ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
        assertEquals(TunWriteAttempt.WouldBlock, h.replies.single().tryWrite { TunWriteAttempt.WouldBlock })
        now += 501
        h.replies.single().tryWrite { h.written += it; TunWriteAttempt.Written }
        assertEquals(blocked, h.events.single().decision)
        assertEquals(null, h.events.single().resolver)
    }

    @Test
    fun `close releases owned policy once and suppresses queued reply evidence`() {
        val ports = ChainedOwnResolverPorts(nowMillis = { now })
        val h = harness(ports, deferWrites = true)
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val port = ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort
        h.egress.observeInbound(resolverReplyPacket(dnsResponse(query), port))
        h.egress.close()
        h.egress.close()
        assertEquals(1, h.closes[0])
        assertFalse(ports.claims(port, UDP_PROTOCOL))
        assertEquals(null, h.replies.single().tryWrite { error("closed response escaped") })
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun `chained policy pins and retires the actual verified artifact lease`() = runTest {
        FactoryFilterFixture.create().use { fixture ->
            val lease = fixture.openLease()
            lease.claimExclusiveRuntimeOwnership()
            val source = ArtifactBackedProxyPolicySource.take(lease, ProxyPolicyControlSource {
                ProxyPolicyControl(41, RuntimePauseDirective.Filtering)
            })
            val policy = ChainedDnsPolicy(
                source, source.identity, source, DecisionEventIdSource { 1 },
                EpochMillisClock { now }, TestPublicationFence(), { _, _, _ -> },
            )
            val query = dnsQuery("example.com", TYPE_A, 1)
            val datagram = ChainedInnerUdpPacket.parse(clientQueryPacket(query, 40000, dnsProxyAddress))!!
            val plan = policy.plan(datagram, DnsMessage.parseQuestion(query))!!
            assertEquals(fixture.configurationIdentity, plan.identity.configurationIdentity)
            assertEquals(fixture.reference.sha256, plan.identity.artifactDigest)
            policy.close()
            policy.close()
            assertEquals(FilterRuntimeLeaseRead.Unavailable, lease.withDecisions { it.blockRuleCount })
            assertEquals(null, policy.plan(datagram, DnsMessage.parseQuestion(query)))
        }
    }

    @Test
    fun `pause expiring before a resolver retry does not emit another blocked query`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), decision = blocked,
            configuration = configurationWithDns(listOf("10.64.0.1", "10.64.0.2")),
            queryTimeoutMillis = 1_000,
            pause = RuntimePauseDirective.Paused(PauseState(now + 500, 41)))
        h.egress.handleQuery(clientQueryPacket(dnsQuery("ads.example", TYPE_A, 1), 40000, dnsProxyAddress))
        now += 1_001
        h.egress.expire()
        assertEquals(1, h.sent.size)
        assertEquals(blocked, h.events.single().decision)
        assertEquals(null, h.events.single().resolver)
    }

    @Test
    fun `truncated upstream UDP answer fails closed without advertising a TCP retry`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }))
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val truncated = dnsResponse(query).also { it[2] = (it[2].toInt() or 0x02).toByte() }
        h.egress.observeInbound(resolverReplyPacket(truncated, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
        val reply = ChainedInnerUdpPacket.parse(h.written.single())!!.payload
        assertEquals(2, reply[3].toInt() and 15)
        assertEquals(0, reply[2].toInt() and 2)
        assertEquals(PrimaryResolverOutcome.FAILED, h.evidence.single().primaryOutcome)
        assertEquals(1, h.sent.size)
    }

    @Test
    fun `bare resolver SERVFAIL stays terminal without independent outage evidence`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }),
            configuration = configurationWithDns(listOf("10.64.0.1", "10.64.0.2")))
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val failure = dnsResponse(query).also { it[3] = 0x82.toByte() }
        h.egress.observeInbound(resolverReplyPacket(failure, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
        assertEquals(1, h.sent.size)
        assertArrayEquals(failure, ChainedInnerUdpPacket.parse(h.written.single())!!.payload)
        assertEquals(PrimaryResolverOutcome.REJECTED, h.evidence.single().primaryOutcome)
    }

    @Test
    fun `truncation tries the next tunnel resolver without changing the capped query`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }),
            configuration = configurationWithDns(listOf("10.64.0.1", "10.64.0.2")))
        val base = dnsQuery("example.com", TYPE_A, 1)
        val query = (base + byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0x80.toByte(), 0, 0, 0))
            .also { it[11] = 1 }
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val first = ChainedInnerUdpPacket.parse(h.sent.single())!!
        assertEquals(1232, readU16(first.payload, base.size + 3))
        assertEquals(4096, readU16(query, base.size + 3))
        val truncated = dnsResponse(base).also { it[2] = (it[2].toInt() or 2).toByte() }
        h.egress.observeInbound(resolverReplyPacket(truncated, first.sourcePort))
        val second = ChainedInnerUdpPacket.parse(h.sent.last())!!
        assertEquals(2, h.sent.size)
        assertArrayEquals(first.payload, second.payload)
        assertTrue(h.written.isEmpty())
        val answer = dnsResponse(base)
        h.egress.observeInbound(resolverReplyPacket(answer, second.sourcePort, second.destinationAddress))
        assertArrayEquals(answer, ChainedInnerUdpPacket.parse(h.written.single())!!.payload)
    }

    @Test
    fun `first real resolver error survives later timeout`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }),
            configuration = configurationWithDns(listOf("10.64.0.1", "10.64.0.2")))
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val refused = dnsResponse(query).also { it[3] = 0x85.toByte() }
        h.egress.observeInbound(resolverReplyPacket(refused, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
        now += 5_001
        h.egress.expire()
        assertArrayEquals(refused, ChainedInnerUdpPacket.parse(h.written.single())!!.payload)
    }

    @Test
    fun `unknown extended EDNS errors and authoritative negatives remain terminal`() {
        for (code in listOf(0, 3, 16)) {
            val h = harness(ChainedOwnResolverPorts(nowMillis = { now }),
                configuration = configurationWithDns(listOf("10.64.0.1", "10.64.0.2")))
            val query = dnsQuery("example.com", TYPE_A, 1)
            h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
            val response = if (code == 16) {
                (dnsResponse(query) + byteArrayOf(0, 0, 41, 4, 0xd0.toByte(), 1, 0, 0, 0, 0, 0))
                    .also { it[11] = 1 }
            } else dnsResponse(query).also { it[3] = (0x80 or code).toByte() }
            h.egress.observeInbound(resolverReplyPacket(response, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
            assertEquals(1, h.sent.size)
            assertEquals(1, h.written.size)
        }
    }

    @Test
    fun `paused would-be blocked responses cap cache lifetime to one second`() {
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), decision = blocked,
            pause = RuntimePauseDirective.Paused(PauseState(now + 10_000, 41)))
        val query = dnsQuery("ads.example", TYPE_HTTPS, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val port = ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort
        h.egress.observeInbound(resolverReplyPacket(dnsResponse(query, serviceBindingAnswer(false)), port))
        val reply = ChainedInnerUdpPacket.parse(h.written.single())!!.payload
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), reply.copyOfRange(query.size + 6, query.size + 10))
        assertEquals(FilterDecisionReason.PAUSED_ALLOW, h.events.single().decision.reason)
    }

    @Test
    fun `split fallback completion is marshalled and records its actual transport after TUN write`() {
        for ((transport, resolver, category) in listOf(
            Triple(ResolverTransport.DEVICE_UDP, ResolverKind.DEVICE, ProtectionFallback.DEVICE_DNS),
            Triple(ResolverTransport.DOQ, ResolverKind.MULLVAD_DOH, ProtectionFallback.ENCRYPTED_DNS),
            Triple(ResolverTransport.PLAIN_TCP, ResolverKind.PLAIN_DNS, ProtectionFallback.PLAIN_DNS),
        )) {
            val fallback = FakeFallback()
            val queue = mutableListOf<() -> Unit>()
            val observations = mutableListOf<Pair<Long, String?>>()
            val ports = ChainedOwnResolverPorts(nowMillis = { now })
            val h = harness(ports, configuration = splitConfiguration(), fallback = fallback,
                deferWrites = true, dispatch = { queue += it }, observe = { e, n -> observations += e to n })
            val query = dnsQuery("example.com", TYPE_A, 9)
            h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
            assertTrue(fallback.queries.isEmpty())
            now += 5001
            h.egress.expire()
            assertArrayEquals(query, fallback.queries.single())
            assertEquals(64, observations.single().second!!.length)
            val port = ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort
            assertTrue(h.egress.observeInbound(resolverReplyPacket(dnsResponse(query), port)))
            assertTrue(h.replies.isEmpty()) // Late T0 cannot race the saved-DNS answer.
            val diagnostics = com.lavasec.app.foundation.health.ResolverTierDiagnosticObservation(1, resolver,
                com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.SERVED, transport)
            fallback.complete(ResolverResult.Answer(dnsResponse(query), transport, resolverQueryEvidence =
                ResolverQueryHealthEvidence.primaryAccepted(123456, 10).copy(tierObservations = listOf(diagnostics))))
            assertTrue(h.replies.isEmpty()) // Completion has not run on the engine queue yet.
            queue.single().invoke()
            assertFalse(ports.claims(port, UDP_PROTOCOL))
            assertEquals(1, fallback.queryCloses)
            assertTrue(h.evidence.isEmpty())
            assertEquals(TunWriteAttempt.WouldBlock, h.replies.single().tryWrite { TunWriteAttempt.WouldBlock })
            h.replies.single().tryWrite { h.written += it; TunWriteAttempt.Written }
            assertEquals(resolver, h.events.single().resolver)
            assertEquals(transport, h.events.single().transport)
            assertEquals(PrimaryResolverOutcome.FAILED, h.evidence.single().primaryOutcome)
            assertEquals(ResolverFailureReason.TIMEOUT, h.evidence.single().primaryFailureReason)
            assertEquals(category, h.evidence.single().successfulFallback)
            assertEquals(h.events.single().id, h.evidence.single().queryId)
            assertEquals(null, h.evidence.single().latencyMillis)
            assertEquals(listOf(0, 1), h.evidence.single().tierObservations.map { it.tier })
            assertEquals(diagnostics, h.evidence.single().tierObservations.last())
            h.egress.close()
            assertEquals(1, fallback.closes)
        }
    }

    @Test
    fun `full mode and split without consented adapter never start physical fallback`() {
        val fullFallback = FakeFallback()
        val full = harness(ChainedOwnResolverPorts(nowMillis = { now }), fallback = fullFallback)
        val split = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration())
        for (h in listOf(full, split)) {
            h.egress.handleQuery(clientQueryPacket(dnsQuery("example.com", TYPE_A, 1), 40000, dnsProxyAddress))
            now += 5001
            h.egress.expire()
            assertEquals(2, ChainedInnerUdpPacket.parse(h.written.single())!!.payload[3].toInt() and 15)
            assertEquals(ProtectionFallback.NONE, h.evidence.single().successfulFallback)
            h.egress.close()
        }
        assertTrue(fullFallback.queries.isEmpty())
        assertEquals(1, fullFallback.closes)
    }

    @Test
    fun `expired pause restores blocked decision before fallback admission`() {
        val fallback = FakeFallback()
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(),
            decision = blocked, pause = RuntimePauseDirective.Paused(PauseState(now + 500, 41)), fallback = fallback)
        h.egress.handleQuery(clientQueryPacket(dnsQuery("ads.example", TYPE_A, 1), 40000, dnsProxyAddress))
        now += 5001
        h.egress.expire()
        assertTrue(fallback.queries.isEmpty())
        assertEquals(blocked, h.events.single().decision)
        assertEquals(null, h.events.single().resolver)
        h.egress.close()
    }

    @Test
    fun `fallback remains bounded and close cancels query and suppresses a queued answer`() {
        val fallback = FakeFallback()
        val queue = mutableListOf<() -> Unit>()
        val ports = ChainedOwnResolverPorts(nowMillis = { now })
        val h = harness(ports, configuration = splitConfiguration(), fallback = fallback,
            dispatch = { queue += it }, maximumPending = 1)
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        now += 5001
        h.egress.expire()
        h.egress.handleQuery(clientQueryPacket(dnsQuery("other.example", TYPE_A, 2), 40001, dnsProxyAddress))
        assertEquals(1, fallback.queries.size)
        assertEquals(1, h.sent.size)
        assertEquals(1, h.written.size) // Overload is local, never an extra fallback query.
        fallback.complete(ResolverResult.Answer(dnsResponse(query), ResolverTransport.MULLVAD_DOH))
        h.egress.close()
        h.egress.close()
        queue.single().invoke()
        assertEquals(1, h.written.size)
        assertEquals(1, fallback.queryCloses)
        assertEquals(1, fallback.closes)
        assertFalse(ports.claims(ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort, UDP_PROTOCOL))
    }

    @Test
    fun `fallback timeout preserves first real T0 error and ignores late saved DNS answer`() {
        val fallback = FakeFallback()
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), fallback = fallback)
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val error = operationalError(query)
        h.egress.observeInbound(resolverReplyPacket(error, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
        assertTrue(h.written.isEmpty())
        now += 12001
        h.egress.expire()
        assertArrayEquals(error, ChainedInnerUdpPacket.parse(h.written.single())!!.payload)
        assertEquals(1, fallback.queryCloses)
        fallback.complete(ResolverResult.Answer(dnsResponse(query), ResolverTransport.DEVICE_TCP))
        assertEquals(1, h.written.size)
        assertEquals(ProtectionFallback.NONE, h.evidence.single().successfulFallback)
        h.egress.close()
    }

    @Test
    fun `synchronous fallback result releases returned handle without installing stale ownership`() {
        val query = dnsQuery("example.com", TYPE_A, 1)
        val fallback = FakeFallback(inline = ResolverResult.Answer(dnsResponse(query), ResolverTransport.MULLVAD_DOH))
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), fallback = fallback)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        now += 5001
        h.egress.expire()
        assertEquals(1, h.written.size)
        assertEquals(1, fallback.queryCloses)
        h.egress.close()
        assertEquals(1, fallback.queryCloses)
    }

    @Test
    fun `lifecycle or mismatched fallback results cannot manufacture tunnel success`() {
        for (lifecycle in listOf(true, false)) {
            val fallback = FakeFallback()
            val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), fallback = fallback)
            val query = dnsQuery("example.com", TYPE_A, 1)
            h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
            now += 5001
            h.egress.expire()
            fallback.complete(if (lifecycle) ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
                else ResolverResult.Answer(dnsResponse(dnsQuery("other.example", TYPE_A, 2)), ResolverTransport.DEVICE_TCP))
            assertEquals(2, ChainedInnerUdpPacket.parse(h.written.single())!!.payload[3].toInt() and 15)
            if (lifecycle) {
                assertTrue(h.events.isEmpty())
                assertTrue(h.evidence.isEmpty())
                assertTrue(h.health.single() is DnsHealthEvent.ProtectionUnavailable)
            } else assertEquals(ProtectionFallback.NONE, h.evidence.single().successfulFallback)
            assertEquals(1, fallback.queryCloses)
            h.egress.close()
        }
    }

    @Test
    fun `retired artifact suppresses fallback evidence retained under TUN backpressure`() {
        val fallback = FakeFallback()
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(),
            fallback = fallback, deferWrites = true)
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        now += 5001
        h.egress.expire()
        fallback.complete(ResolverResult.Answer(dnsResponse(query), ResolverTransport.DOT))
        val old = h.source.snapshot
        h.source.snapshot = ProxyPolicySnapshot(old.identity.copy(artifactDigest = "retired"),
            old.activeProtectionSessionId, old.pause, old.decisions)
        assertEquals(null, h.replies.single().tryWrite { error("retired reply must not publish") })
        assertTrue(h.events.isEmpty())
        assertTrue(h.evidence.isEmpty())
        h.egress.close()
    }

    @Test
    fun `T0 outage retirement during observation cannot start fallback work`() {
        val fallback = FakeFallback()
        var owner: ChainedT0ResolverEgress? = null
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(),
            fallback = fallback, observe = { _, _ -> owner?.close() })
        owner = h.egress
        h.egress.handleQuery(clientQueryPacket(dnsQuery("example.com", TYPE_A, 1), 40000, dnsProxyAddress))
        now += 5001
        h.egress.expire()
        assertTrue(fallback.queries.isEmpty())
        assertTrue(h.written.isEmpty())
        assertEquals(1, fallback.closes)
    }

    @Test
    fun `scoped portal negative can use saved Device while failed rescue retains original negative`() {
        for (servesAddress in listOf(true, false)) {
            val context = com.lavasec.app.vpn.DnsFallbackQueryContext(true, true, true, true,
                com.lavasec.app.vpn.DnsPortalTierFallback.registeredHosts)
            val fallback = FakeFallback(context = context)
            val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), fallback = fallback)
            val query = dnsQuery("deltawifi.com", TYPE_A, 1)
            h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
            val negative = dnsResponse(query).also { it[3] = 0x83.toByte() }
            h.egress.observeInbound(resolverReplyPacket(negative, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
            assertEquals(1, fallback.queries.size)
            assertEquals(setOf("deltawifi.com", "wifi.delta.com"), fallback.portalHosts)
            assertTrue(fallback.requiresPortalAdmission)
            assertTrue(fallback.remainingNanos in 1..12_000_000_000L)
            val rescue = if (servesAddress) com.lavasec.app.foundation.dns.DnsMessage.bootstrapResponse(query,
                byteArrayOf(192.toByte(), 0, 2, 8), ByteArray(16)) else dnsResponse(query)
            fallback.complete(ResolverResult.Answer(rescue, ResolverTransport.DEVICE_UDP))
            assertArrayEquals(if (servesAddress) rescue else negative, ChainedInnerUdpPacket.parse(h.written.single())!!.payload)
            val evidence = h.evidence.single()
            assertEquals(if (servesAddress) ProtectionFallback.DEVICE_DNS else ProtectionFallback.NONE, evidence.successfulFallback)
            assertEquals(com.lavasec.app.foundation.health.DnsFallbackResponseClass.NEGATIVE, evidence.tierObservations.first().responseClass)
            assertEquals(if (servesAddress) com.lavasec.app.foundation.health.DnsFallbackDisposition.SERVED
                else com.lavasec.app.foundation.health.DnsFallbackDisposition.PRESERVED_NEGATIVE, evidence.fallbackObservations.first().disposition)
            h.egress.close()
        }
    }

    @Test
    fun `delivered portal rescue alone earns service while replaced T0 negative retains raw reply facts`() {
        for (type in listOf(TYPE_A, TYPE_AAAA)) for (code in listOf(0, 3)) for (rescued in listOf(true, false)) {
            for ((transport, resolver, coverage) in listOf(
                Triple(ResolverTransport.DEVICE_UDP, ResolverKind.DEVICE, ProtectionFallback.DEVICE_DNS),
                Triple(ResolverTransport.DEVICE_TCP, ResolverKind.DEVICE, ProtectionFallback.DEVICE_DNS),
                Triple(ResolverTransport.MULLVAD_DOH, ResolverKind.MULLVAD_DOH, ProtectionFallback.ENCRYPTED_DNS),
                Triple(ResolverTransport.PLAIN_TCP, ResolverKind.PLAIN_DNS, ProtectionFallback.PLAIN_DNS))) {
                val fallback = FakeFallback(context = com.lavasec.app.vpn.DnsFallbackQueryContext(true, true, true, true,
                    com.lavasec.app.vpn.DnsPortalTierFallback.registeredHosts))
                val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(),
                    fallback = fallback, deferWrites = true)
                val query = dnsQuery("deltawifi.com", type, 1)
                h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
                val original = dnsResponse(query).also { it[3] = (0x80 or code).toByte() }
                h.egress.observeInbound(resolverReplyPacket(original, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
                val saved = if (rescued) DnsMessage.bootstrapResponse(query, byteArrayOf(192.toByte(), 0, 2, 8),
                    ByteArray(16).apply { this[15] = 8 }) else dnsResponse(query).also { it[3] = 0x83.toByte() }
                val rawSaved = ResolverTierDiagnosticObservation(1, resolver, ResolverTierDiagnosticOutcome.SERVED,
                    transport, responseClass = if (rescued) DnsFallbackResponseClass.POSITIVE else DnsFallbackResponseClass.NEGATIVE,
                    responseCode = if (rescued) 0 else 3, queryType = type, terminalAddressPresent = rescued, packetSent = true)
                fallback.complete(ResolverResult.Answer(saved, transport, resolverQueryEvidence =
                    ResolverQueryHealthEvidence.primaryAccepted(123, 10).copy(tierObservations = listOf(rawSaved),
                        httpProtocol = if (transport == ResolverTransport.MULLVAD_DOH) DnsHttpProtocol.HTTP_2 else null)))

                assertTrue(h.events.isEmpty())
                assertTrue(h.evidence.isEmpty())
                assertEquals(TunWriteAttempt.WouldBlock, h.replies.single().tryWrite { TunWriteAttempt.WouldBlock })
                assertTrue(h.events.isEmpty())
                assertTrue(h.evidence.isEmpty())
                assertEquals(TunWriteAttempt.Written, h.replies.single().tryWrite { h.written += it; TunWriteAttempt.Written })
                assertArrayEquals(if (rescued) saved else original, ChainedInnerUdpPacket.parse(h.written.single())!!.payload)
                assertEquals(if (rescued) resolver else ResolverKind.WIREGUARD_T0, h.events.single().resolver)
                assertEquals(if (rescued) transport else ResolverTransport.WIREGUARD_UDP, h.events.single().transport)

                val evidence = h.evidence.single()
                assertEquals(if (rescued) coverage else ProtectionFallback.NONE, evidence.successfulFallback)
                assertEquals(if (rescued) DnsFallbackDisposition.SERVED else DnsFallbackDisposition.PRESERVED_NEGATIVE,
                    evidence.fallbackObservations.single().disposition)
                assertEquals(1, evidence.tierObservations.count { it.outcome == ResolverTierDiagnosticOutcome.SERVED })
                assertEquals(ResolverTierDiagnosticObservation(0, ResolverKind.WIREGUARD_T0,
                    if (rescued) ResolverTierDiagnosticOutcome.ANSWERED else ResolverTierDiagnosticOutcome.SERVED,
                    ResolverTransport.WIREGUARD_UDP, responseClass = DnsFallbackResponseClass.NEGATIVE, responseCode = code,
                    queryType = type, terminalAddressPresent = false, packetSent = true), evidence.tierObservations.first())
                assertEquals(if (rescued) rawSaved else rawSaved.copy(outcome = ResolverTierDiagnosticOutcome.ANSWERED),
                    evidence.tierObservations.last())

                val diagnostics = VpnSessionDiagnostics(tiers = mapOf(
                    0 to VpnTierDiagnostics(served = 7, answered = 2, failures = 2, failureStreak = 4, rejectedStreak = 3),
                    1 to VpnTierDiagnostics(served = 11, answered = 4, rejectedStreak = 5))).query(evidence, now)
                assertEquals(if (rescued) 7L else 8L, diagnostics.tiers.getValue(0).served)
                assertEquals(if (rescued) 3L else 2L, diagnostics.tiers.getValue(0).answered)
                assertEquals(if (rescued) 4L else 0L, diagnostics.tiers.getValue(0).rejectedStreak)
                assertEquals(0L, diagnostics.tiers.getValue(0).failureStreak)
                assertEquals(2L, diagnostics.tiers.getValue(0).failures)
                assertEquals(if (rescued) 12L else 11L, diagnostics.tiers.getValue(1).served)
                assertEquals(if (rescued) 0L else 6L, diagnostics.tiers.getValue(1).rejectedStreak)
                assertEquals(if (rescued) resolver else ResolverKind.WIREGUARD_T0, diagnostics.lastResolver)
                assertEquals(if (rescued) transport else ResolverTransport.WIREGUARD_UDP, diagnostics.lastTransport)
                assertEquals(if (rescued && coverage == ProtectionFallback.DEVICE_DNS) 1L else 0L, diagnostics.deviceFallbacks)
                assertEquals(if (rescued && transport in setOf(ResolverTransport.DEVICE_TCP, ResolverTransport.PLAIN_TCP)) 1L else 0L,
                    diagnostics.tcpResponses)
                assertEquals(if (rescued && transport == ResolverTransport.MULLVAD_DOH) DnsHttpProtocol.HTTP_2 else null,
                    diagnostics.lastHttpProtocol)
                assertEquals(1L, diagnostics.upstreamSuccesses)
                assertEquals(0L, diagnostics.upstreamFailures)
                h.egress.close()
            }
        }
    }

    @Test
    fun `delivered original portal negative retains rejected saved reply facts without service credit`() {
        for (queryType in listOf(TYPE_A, TYPE_AAAA)) for (responseCode in listOf(0, 3)) {
            for (transport in listOf(ResolverTransport.DEVICE_UDP, ResolverTransport.DEVICE_TCP, ResolverTransport.MULLVAD_DOH)) {
                val fallback = FakeFallback(context = com.lavasec.app.vpn.DnsFallbackQueryContext(true, true, true, true,
                    com.lavasec.app.vpn.DnsPortalTierFallback.registeredHosts))
                val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(),
                    fallback = fallback, deferWrites = true)
                val query = dnsQuery("deltawifi.com", queryType, 1)
                h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
                val original = dnsResponse(query).also { it[3] = 0x83.toByte() }
                h.egress.observeInbound(resolverReplyPacket(original, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
                val discarded = dnsResponse(query).also { it[2] = 0x85.toByte(); it[3] = (0x80 or responseCode).toByte() }
                val resolver = if (transport == ResolverTransport.MULLVAD_DOH) ResolverKind.MULLVAD_DOH else ResolverKind.DEVICE
                val raw = ResolverTierDiagnosticObservation(1, resolver, ResolverTierDiagnosticOutcome.SERVED,
                    transport, responseClass = DnsFallbackResponseClass.NEGATIVE, responseCode = responseCode,
                    queryType = queryType, terminalAddressPresent = false, packetSent = true)
                fallback.complete(ResolverResult.Answer(discarded, transport, resolverQueryEvidence =
                    ResolverQueryHealthEvidence.primaryAccepted(123, 10).copy(tierObservations = listOf(raw),
                        httpProtocol = if (transport == ResolverTransport.MULLVAD_DOH) DnsHttpProtocol.HTTP_2 else null)))

                assertTrue(h.events.isEmpty())
                assertTrue(h.evidence.isEmpty())
                assertEquals(TunWriteAttempt.WouldBlock, h.replies.single().tryWrite { TunWriteAttempt.WouldBlock })
                assertTrue(h.evidence.isEmpty())
                assertEquals(TunWriteAttempt.Written, h.replies.single().tryWrite { h.written += it; TunWriteAttempt.Written })
                assertArrayEquals(original, ChainedInnerUdpPacket.parse(h.written.single())!!.payload)
                assertEquals(ResolverKind.WIREGUARD_T0, h.events.single().resolver)
                assertEquals(ResolverTransport.WIREGUARD_UDP, h.events.single().transport)
                assertEquals(null, h.events.single().error)

                val evidence = h.evidence.single()
                assertEquals(PrimaryResolverOutcome.ACCEPTED, evidence.primaryOutcome)
                assertEquals(ProtectionFallback.NONE, evidence.successfulFallback)
                assertEquals(null, evidence.httpProtocol)
                assertEquals(DnsFallbackDisposition.PRESERVED_NEGATIVE, evidence.fallbackObservations.single().disposition)
                assertEquals(ResolverTierDiagnosticOutcome.SERVED, evidence.tierObservations.first().outcome)
                assertEquals(DnsFallbackResponseClass.NEGATIVE, evidence.tierObservations.first().responseClass)
                assertEquals(3, evidence.tierObservations.first().responseCode)
                assertEquals(raw.copy(outcome = ResolverTierDiagnosticOutcome.ANSWERED), evidence.tierObservations.last())

                val diagnostics = VpnSessionDiagnostics(tiers = mapOf(1 to VpnTierDiagnostics(
                    served = 7, answered = 2, rejectedStreak = 3))).query(evidence, now)
                assertEquals(ResolverKind.WIREGUARD_T0, diagnostics.lastResolver)
                assertEquals(ResolverTransport.WIREGUARD_UDP, diagnostics.lastTransport)
                assertEquals(null, diagnostics.lastHttpProtocol)
                assertEquals(0L, diagnostics.deviceFallbacks)
                assertEquals(0L, diagnostics.tcpResponses)
                assertEquals(1L, diagnostics.tiers.getValue(0).served)
                assertEquals(7L, diagnostics.tiers.getValue(1).served)
                assertEquals(3L, diagnostics.tiers.getValue(1).answered)
                assertEquals(4L, diagnostics.tiers.getValue(1).rejectedStreak)
                h.egress.close()
            }
        }
    }

    @Test
    fun `nested saved fallback service and protocol belong only to its delivered answer`() {
        for (portal in listOf(true, false)) for (hasAddress in listOf(true, false)) for (late in listOf(true, false)) {
            val fallback = FakeFallback(context = com.lavasec.app.vpn.DnsFallbackQueryContext(true, true, true, true,
                com.lavasec.app.vpn.DnsPortalTierFallback.registeredHosts))
            val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(),
                fallback = fallback, deferWrites = true)
            val query = dnsQuery("deltawifi.com", TYPE_A, 1)
            h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
            val original = if (portal) dnsResponse(query).also { it[3] = 0x83.toByte() } else operationalError(query)
            h.egress.observeInbound(resolverReplyPacket(original, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
            val saved = if (hasAddress) DnsMessage.bootstrapResponse(query, byteArrayOf(192.toByte(), 0, 2, 8), ByteArray(16))
                else dnsResponse(query).also { it[3] = 0x83.toByte() }
            val device = ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.FAILED,
                ResolverTransport.DEVICE_UDP, ResolverFailureReason.TIMEOUT, packetSent = true)
            val encrypted = ResolverTierDiagnosticObservation(2, ResolverKind.MULLVAD_DOH, ResolverTierDiagnosticOutcome.SERVED,
                ResolverTransport.MULLVAD_DOH, responseClass = if (hasAddress) DnsFallbackResponseClass.POSITIVE else DnsFallbackResponseClass.NEGATIVE,
                responseCode = if (hasAddress) 0 else 3, queryType = TYPE_A, terminalAddressPresent = hasAddress, packetSent = true)
            val nested = DnsFallbackObservation(1, 2, DnsFallbackReason.TRANSPORT_FAILURE, DnsFallbackDisposition.SERVED)
            if (late) now += 12_001
            fallback.complete(ResolverResult.Answer(saved, ResolverTransport.MULLVAD_DOH, resolverQueryEvidence =
                ResolverQueryHealthEvidence.primaryFailed(123, ResolverFailureReason.TIMEOUT, ProtectionFallback.ENCRYPTED_DNS).copy(
                    httpProtocol = DnsHttpProtocol.HTTP_2, tierObservations = listOf(device, encrypted), fallbackObservations = listOf(nested))))
            assertTrue(h.evidence.isEmpty())
            assertEquals(TunWriteAttempt.Written, h.replies.single().tryWrite { h.written += it; TunWriteAttempt.Written })

            val selected = !late && (!portal || hasAddress)
            assertArrayEquals(if (selected) saved else original, ChainedInnerUdpPacket.parse(h.written.single())!!.payload)
            val evidence = h.evidence.single()
            assertEquals(if (selected) ProtectionFallback.ENCRYPTED_DNS else ProtectionFallback.NONE, evidence.successfulFallback)
            assertEquals(if (selected) DnsHttpProtocol.HTTP_2 else null, evidence.httpProtocol)
            assertEquals(if (selected) DnsFallbackDisposition.SERVED else if (portal) DnsFallbackDisposition.PRESERVED_NEGATIVE
                else DnsFallbackDisposition.ATTEMPTED,
                evidence.fallbackObservations.first().disposition)
            assertEquals(if (selected) nested else nested.copy(disposition = DnsFallbackDisposition.ATTEMPTED),
                evidence.fallbackObservations.last())
            assertEquals(if (portal && !selected) ResolverTierDiagnosticOutcome.SERVED else ResolverTierDiagnosticOutcome.ANSWERED,
                evidence.tierObservations.first().outcome)
            assertEquals(if (portal || selected) 1 else 0,
                evidence.tierObservations.count { it.outcome == ResolverTierDiagnosticOutcome.SERVED })
            assertEquals(device, evidence.tierObservations[1])
            assertEquals(if (selected) encrypted else encrypted.copy(outcome = ResolverTierDiagnosticOutcome.ANSWERED),
                evidence.tierObservations.last())
            val diagnostics = VpnSessionDiagnostics(tiers = mapOf(2 to VpnTierDiagnostics(served = 7, rejectedStreak = 3)))
                .query(evidence, now)
            assertEquals(if (selected) ResolverKind.MULLVAD_DOH else if (portal) ResolverKind.WIREGUARD_T0 else ResolverKind.DEVICE,
                diagnostics.lastResolver)
            assertEquals(if (selected) 8L else 7L, diagnostics.tiers.getValue(2).served)
            assertEquals(if (selected) 0L else 4L, diagnostics.tiers.getValue(2).rejectedStreak)
            h.egress.close()
        }
    }

    @Test
    fun `earlier selected encrypted and plain portal positives retain their actual tier credit`() {
        for ((transport, coverage) in listOf(ResolverTransport.MULLVAD_DOH to ProtectionFallback.ENCRYPTED_DNS,
                ResolverTransport.PLAIN_TCP to ProtectionFallback.PLAIN_DNS)) {
            val context = com.lavasec.app.vpn.DnsFallbackQueryContext(true, true, true, true,
                com.lavasec.app.vpn.DnsPortalTierFallback.registeredHosts)
            val fallback = FakeFallback(context = context)
            val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), fallback = fallback)
            val query = dnsQuery("deltawifi.com", TYPE_A, 1)
            h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
            val negative = dnsResponse(query).also { it[3] = 0x83.toByte() }
            h.egress.observeInbound(resolverReplyPacket(negative, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
            val positive = DnsMessage.bootstrapResponse(query, byteArrayOf(192.toByte(), 0, 2, 8), ByteArray(16))

            fallback.complete(ResolverResult.Answer(positive, transport))

            assertArrayEquals(positive, ChainedInnerUdpPacket.parse(h.written.single())!!.payload)
            assertEquals(transport, h.events.single().transport)
            assertEquals(coverage, h.evidence.single().successfulFallback)
            assertFalse(h.evidence.single().successfulFallback == ProtectionFallback.DEVICE_DNS)
            assertEquals(com.lavasec.app.foundation.health.DnsFallbackDisposition.SERVED,
                h.evidence.single().fallbackObservations.first().disposition)
            h.egress.close()
        }
    }

    @Test
    fun `large actual Device portal address is selected before final client truncation`() {
        val fallback = FakeFallback(context = com.lavasec.app.vpn.DnsFallbackQueryContext(true, true, true, true,
            com.lavasec.app.vpn.DnsPortalTierFallback.registeredHosts))
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), fallback = fallback)
        val query = dnsQuery("deltawifi.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val negative = dnsResponse(query).also { it[3] = 0x83.toByte() }
        h.egress.observeInbound(resolverReplyPacket(negative, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
        val padding = 1_400
        val optLength = padding + 4
        val large = DnsMessage.bootstrapResponse(query, byteArrayOf(192.toByte(), 0, 2, 8), ByteArray(16)).also { it[11] = 1 } +
            byteArrayOf(0, 0, 41, 4, 0xd0.toByte(), 0, 0, 0, 0, (optLength ushr 8).toByte(), optLength.toByte(),
                0, 12, (padding ushr 8).toByte(), padding.toByte()) + ByteArray(padding)

        fallback.complete(ResolverResult.Answer(large, ResolverTransport.DEVICE_UDP))

        val delivered = ChainedInnerUdpPacket.parse(h.written.single())!!.payload
        assertTrue(delivered[2].toInt() and 2 != 0)
        assertEquals(query.size, delivered.size)
        assertEquals(0, readU16(delivered, 6))
        assertEquals(0, readU16(delivered, 10))
        assertEquals(ProtectionFallback.DEVICE_DNS, h.evidence.single().successfulFallback)
        assertEquals(com.lavasec.app.foundation.health.DnsFallbackDisposition.SERVED,
            h.evidence.single().fallbackObservations.first().disposition)
        assertFalse(delivered.contentEquals(negative))
        h.egress.close()
    }

    @Test
    fun `portal negative cannot open saved tiers without enabled current Device capture`() {
        val active = com.lavasec.app.vpn.DnsFallbackQueryContext(true, true, true, true,
            com.lavasec.app.vpn.DnsPortalTierFallback.registeredHosts)
        for (context in listOf(active.copy(hasEnabledDeviceTier = false), active.copy(hasCurrentDeviceCapture = false))) {
            val fallback = FakeFallback(context = context)
            val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), fallback = fallback)
            val query = dnsQuery("deltawifi.com", TYPE_A, 1)
            h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
            val negative = dnsResponse(query).also { it[3] = 0x83.toByte() }
            h.egress.observeInbound(resolverReplyPacket(negative, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))

            assertTrue(fallback.queries.isEmpty())
            assertArrayEquals(negative, ChainedInnerUdpPacket.parse(h.written.single())!!.payload)
            assertEquals(ProtectionFallback.NONE, h.evidence.single().successfulFallback)
            h.egress.close()
        }
    }

    @Test
    fun `portal exception never escapes full tunnel or blocked original and aliases`() {
        for ((full, denied) in listOf(true to false, false to true)) {
            val fallback = FakeFallback(context = com.lavasec.app.vpn.DnsFallbackQueryContext(true, true, true, true,
                com.lavasec.app.vpn.DnsPortalTierFallback.registeredHosts))
            val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = if (full) fullConfiguration() else splitConfiguration(),
                fallback = fallback, decision = if (denied) blocked else FilterDecision.DEFAULT_ALLOW)
            val query = dnsQuery("deltawifi.com", TYPE_A, 1)
            h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
            if (!denied) {
                val negative = dnsResponse(query).also { it[3] = 0x83.toByte() }
                h.egress.observeInbound(resolverReplyPacket(negative, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
            }
            assertTrue(fallback.queries.isEmpty())
            assertEquals(1, h.written.size)
            h.egress.close()
        }
    }

    @Test
    fun `matched operational EDE can use authorized split fallback and shares original deadline`() {
        val fallback = FakeFallback()
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), fallback = fallback)
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        now += 1000
        h.egress.observeInbound(resolverReplyPacket(operationalError(query), ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
        assertEquals(1, fallback.queries.size)
        assertEquals(11_000_000_000L, fallback.remainingNanos)
        h.egress.close()
    }

    @Test
    fun `semantic malformed alias cannot be selected when authorized fallback fails`() {
        val fallback = FakeFallback()
        val h = harness(ChainedOwnResolverPorts(nowMillis = { now }), configuration = splitConfiguration(), fallback = fallback)
        val query = dnsQuery("example.com", TYPE_A, 1)
        h.egress.handleQuery(clientQueryPacket(query, 40000, dnsProxyAddress))
        val cycle = dnsResponse(query, byteArrayOf(0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0.toByte(), 12))
        h.egress.observeInbound(resolverReplyPacket(cycle, ChainedInnerUdpPacket.parse(h.sent.single())!!.sourcePort))
        assertEquals(1, fallback.queries.size)
        fallback.complete(ResolverResult.Failure(ResolverFailureReason.TIMEOUT))
        val reply = ChainedInnerUdpPacket.parse(h.written.single())!!.payload
        assertEquals(2, reply[3].toInt() and 15)
        assertEquals(0, readU16(reply, 6))
        assertFalse(reply.contentEquals(cycle))
        h.egress.close()
    }

    private fun operationalError(query: ByteArray): ByteArray =
        (dnsResponse(query).also { it[3] = 0x82.toByte(); it[11] = 1 } +
            byteArrayOf(0, 0, 41, 4, 0xd0.toByte(), 0, 0, 0, 0, 0, 6, 0, 15, 0, 2, 0, 23))

    private class FakeFallback(private val inline: ResolverResult? = null,
        override val context: com.lavasec.app.vpn.DnsFallbackQueryContext = com.lavasec.app.vpn.DnsFallbackQueryContext()) : ChainedDnsFallback {
        var remainingNanos = 0L
        var portalHosts: Set<String> = emptySet()
        var requiresPortalAdmission = false
        override fun resolve(query: ByteArray, remainingNanos: Long, portalHosts: Set<String>, requiresPortalAdmission: Boolean, completion: (ResolverResult) -> Unit): AutoCloseable {
            this.remainingNanos = remainingNanos; this.portalHosts = portalHosts; this.requiresPortalAdmission = requiresPortalAdmission
            return resolve(query, completion)
        }
        val queries = mutableListOf<ByteArray>()
        private val completions = mutableListOf<(ResolverResult) -> Unit>()
        var queryCloses = 0
        var closes = 0
        override fun resolve(query: ByteArray, completion: (ResolverResult) -> Unit): AutoCloseable {
            queries += query.copyOf()
            completions += completion
            inline?.let(completion)
            return AutoCloseable { queryCloses++ }
        }
        fun complete(result: ResolverResult) = completions.single()(result)
        override fun close() { closes++ }
    }

    private val blocked = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST)

    // MARK: - Fixtures

    private class Harness(
        val egress: ChainedT0ResolverEgress,
        val sent: MutableList<ByteArray>,
        val written: MutableList<ByteArray>,
        val events: MutableList<DnsDecisionEvent>,
        val health: MutableList<DnsHealthEvent>,
        val evidence: MutableList<ResolverQueryHealthEvidence>,
        val replies: MutableList<ChainedDnsResponse>,
        val source: TestPolicySource,
        val fence: TestPublicationFence,
        val closes: IntArray,
    )

    private fun harness(
        ports: ChainedOwnResolverPorts,
        configuration: ChainedUpstreamConfiguration = fullConfiguration(),
        queryTimeoutMillis: Long = 5_000L,
        decision: FilterDecision = FilterDecision.DEFAULT_ALLOW,
        pause: RuntimePauseDirective = RuntimePauseDirective.Filtering,
        deferWrites: Boolean = false,
        epoch: () -> Long = { 0L },
        observe: (Long, String?) -> Unit = { _, _ -> },
        fallback: ChainedDnsFallback? = null,
        dispatch: (() -> Unit) -> Unit = { it() },
        maximumPending: Int = 32,
    ): Harness {
        val sent = mutableListOf<ByteArray>()
        val written = mutableListOf<ByteArray>()
        val events = mutableListOf<DnsDecisionEvent>()
        val health = mutableListOf<DnsHealthEvent>()
        val evidence = mutableListOf<ResolverQueryHealthEvidence>()
        val replies = mutableListOf<ChainedDnsResponse>()
        val source = TestPolicySource(ProxyPolicySnapshot(testPolicyIdentity(), 41, pause, TestDecisionSource(decision)))
        val fence = TestPublicationFence()
        val closes = intArrayOf(0)
        var nextId = 1L
        val policy = ChainedDnsPolicy(
            source = source, identity = source.snapshot.identity,
            ownedSource = AutoCloseable { closes[0]++ },
            eventIds = DecisionEventIdSource { nextId++ }, clock = EpochMillisClock { now }, fence = fence,
            record = { event, emittedHealth, queryEvidence ->
                event?.let(events::add)
                health += emittedHealth
                queryEvidence?.let(evidence::add)
            },
        )
        val egress = ChainedT0ResolverEgress(
            configuration = configuration,
            ports = ports,
            nowMillis = { now },
            queryTimeoutMillis = queryTimeoutMillis,
            policy = policy,
            observationEpoch = epoch,
            observeResolution = observe,
            fallback = fallback,
            dispatchCompletion = dispatch,
            maximumPendingQueries = maximumPending,
        )
        egress.bind(
            sendQuery = { sent += it.copyOf() },
            writeClientResponse = { response ->
                replies += response
                if (!deferWrites) response.tryWrite { written += it.copyOf(); TunWriteAttempt.Written }
            },
        )
        return Harness(egress, sent, written, events, health, evidence, replies, source, fence, closes)
    }

    private fun clientQueryPacket(
        dns: ByteArray,
        sourcePort: Int,
        destination: ByteArray,
    ): ByteArray = ChainedInnerUdpPacket.build(
        sourceAddress = clientAddress,
        destinationAddress = destination,
        sourcePort = sourcePort,
        destinationPort = 53,
        identification = 0x0abc,
        payload = dns,
    )

    private fun resolverReplyPacket(dns: ByteArray, destinationPort: Int, source: ByteArray = resolverAddress): ByteArray =
        ChainedInnerUdpPacket.build(
            sourceAddress = source,
            destinationAddress = clientAddress,
            sourcePort = 53,
            destinationPort = destinationPort,
            identification = 0x0abc,
            payload = dns,
        )

    private fun fullConfiguration(): ChainedUpstreamConfiguration = configurationWith(
        allowedIPs = listOf("0.0.0.0/0"),
        dnsAddresses = listOf("10.64.0.1"),
    )

    private fun splitConfiguration(): ChainedUpstreamConfiguration = configurationWith(
        allowedIPs = listOf("10.0.0.0/8"),
        dnsAddresses = listOf("10.64.0.1"),
    )

    private fun configurationWithDns(dnsAddresses: List<String>): ChainedUpstreamConfiguration =
        configurationWith(allowedIPs = listOf("0.0.0.0/0"), dnsAddresses = dnsAddresses)

    private fun configurationWith(
        allowedIPs: List<String>,
        dnsAddresses: List<String>,
    ): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
        endpointHost = "vpn.example.com",
        endpointPort = 51820,
        peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 }),
        clientAddress = "10.7.0.2",
        allowedIPs = allowedIPs,
        persistentKeepaliveSeconds = 0,
        interfaceMTU = null,
        dnsAddresses = dnsAddresses,
    )

    private fun dnsQuery(domain: String, type: Int, txid: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.appendU16(txid)
        bytes.appendU16(0x0100)
        bytes.appendU16(1)
        bytes.appendU16(0)
        bytes.appendU16(0)
        bytes.appendU16(0)
        appendName(bytes, domain)
        bytes.appendU16(type)
        bytes.appendU16(1)
        return bytes.toByteArray()
    }

    private fun dnsResponse(query: ByteArray, answers: ByteArray = ByteArray(0)): ByteArray {
        val question = query.copyOfRange(12, query.size)
        val answerCount = if (answers.isEmpty()) 0 else 1
        val bytes = mutableListOf<Byte>()
        bytes.appendU16(readU16(query, 0))
        bytes.appendU16(0x8180)
        bytes.appendU16(1)
        bytes.appendU16(answerCount)
        bytes.appendU16(0)
        bytes.appendU16(0)
        bytes += question.toList()
        bytes += answers.toList()
        return bytes.toByteArray()
    }

    private fun serviceBindingAnswer(withIpv6Hint: Boolean): ByteArray {
        val rdata = mutableListOf<Byte>()
        rdata.appendU16(1) // SvcPriority
        rdata += 0 // TargetName: root, uncompressed
        // ALPN = ["h2"], key 1.
        rdata.appendU16(1)
        rdata.appendU16(3)
        rdata.add(2)
        rdata.add('h'.code.toByte())
        rdata.add('2'.code.toByte())
        // ipv4hint, key 4.
        rdata.appendU16(4)
        rdata.appendU16(4)
        rdata += IPV4_HINT_MARKER.toList()
        if (withIpv6Hint) {
            // ipv6hint, key 6.
            rdata.appendU16(6)
            rdata.appendU16(16)
            rdata += IPV6_HINT_MARKER.toList()
        }
        val answer = mutableListOf<Byte>()
        answer.add(0xc0.toByte())
        answer.add(0x0c.toByte())
        answer.appendU16(TYPE_HTTPS)
        answer.appendU16(1)
        answer.appendU32(60)
        answer.appendU16(rdata.size)
        answer += rdata.toList()
        return answer.toByteArray()
    }

    private fun appendName(bytes: MutableList<Byte>, domain: String) {
        for (label in domain.split('.')) {
            val labelBytes = label.toByteArray(Charsets.UTF_8)
            bytes += labelBytes.size.toByte()
            bytes += labelBytes.toList()
        }
        bytes += 0
    }

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        for (start in 0..(haystack.size - needle.size)) {
            if (haystack.copyOfRange(start, start + needle.size).contentEquals(needle)) return true
        }
        return false
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun MutableList<Byte>.appendU16(value: Int) {
        add((value ushr 8).toByte())
        add(value.toByte())
    }

    private fun MutableList<Byte>.appendU32(value: Long) {
        add(((value ushr 24) and 0xff).toByte())
        add(((value ushr 16) and 0xff).toByte())
        add(((value ushr 8) and 0xff).toByte())
        add((value and 0xff).toByte())
    }

    private companion object {
        const val TYPE_A = 1
        const val TYPE_AAAA = 28
        const val TYPE_HTTPS = 65
        const val UDP_PROTOCOL = 17
        const val IPV6_HINT_PARAM_BYTES = 20
        val IPV4_HINT_MARKER = byteArrayOf(1, 2, 3, 4)
        val IPV6_HINT_MARKER = ByteArray(16) { (it + 1).toByte() }
    }
}
