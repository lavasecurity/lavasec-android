package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class DnsFallbackResponseTest {
    @Test fun `positive negative operational and prohibited replies remain distinct`() {
        val query = testDnsQuery("deltawifi.com")
        assertEquals(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.classify(query, positive(query)).kind)
        for (rcode in listOf(0, 3)) assertEquals(DnsFallbackResponse.Kind.NEGATIVE, DnsFallbackResponse.classify(query, reply(query, rcode)).kind)
        for (rcode in listOf(2, 5, 16)) assertEquals(DnsFallbackResponse.Kind.AMBIGUOUS_FAILURE, DnsFallbackResponse.classify(query, reply(query, rcode)).kind)
        for (code in listOf(14, 22, 23)) assertEquals(DnsFallbackResponse.Kind.TRANSIENT_FAILURE, DnsFallbackResponse.classify(query, reply(query, 2, listOf(code))).kind)
        for (code in listOf(1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 16, 17, 18, 24, 25, 27, 28, 35)) {
            assertEquals("EDE $code", DnsFallbackResponse.Kind.PROHIBITED,
                DnsFallbackResponse.classify(query, reply(query, 2, listOf(23, code))).kind)
        }
    }
    @Test fun `all portal permission gates and exact registered address question are required`() {
        val context = admitted()
        for (host in DnsPortalTierFallback.registeredHosts) for (type in listOf(1, 28)) {
            val query = testDnsQuery(host, rawType = type)
            val negative = reply(query, 3)
            assertTrue(DnsPortalTierFallback.eligible(query, negative, context))
            for (denied in listOf(context.copy(isSatisfiedWifi = false), context.copy(hasEnabledDeviceTier = false),
                context.copy(hasCurrentDeviceCapture = false), context.copy(permitsPhysicalDeviceDns = false),
                context.copy(portalRescueEnabled = false), context.copy(permittedPortalHosts = emptySet()),
                context.copy(portalNetworkIsCurrent = { false }))) assertFalse(DnsPortalTierFallback.eligible(query, negative, denied))
            assertFalse(DnsPortalTierFallback.eligible(query, reply(query, 2, listOf(15, 23)), context))
            assertTrue(DnsPortalTierFallback.rescueAnswer(query, positive(query), context))
            assertFalse(DnsPortalTierFallback.rescueAnswer(query, negative, context))
        }
        for (host in listOf("private.example", "a.deltawifi.com", "deltawifi.com.evil.example")) {
            val query = testDnsQuery(host)
            assertFalse(DnsPortalTierFallback.eligible(query, reply(query, 3), context.copy(permittedPortalHosts = context.permittedPortalHosts + host)))
        }
        for (type in listOf(16, 64, 65)) {
            val query = testDnsQuery("deltawifi.com", rawType = type)
            assertFalse(DnsPortalTierFallback.eligible(query, reply(query, 3), context))
        }
    }
    @Test fun `unregistered alias unrelated address and cname only cannot earn rescue credit`() {
        val query = testDnsQuery("deltawifi.com")
        val alias = name("wifi.delta.com")
        val cname = rr(byteArrayOf(0xc0.toByte(), 12), 5, alias)
        val cnameOnly = withAnswers(query, cname)
        assertTrue(DnsPortalTierFallback.eligible(query, cnameOnly, admitted()))
        assertFalse(DnsPortalTierFallback.rescueAnswer(query, cnameOnly, admitted()))
        assertFalse(DnsPortalTierFallback.eligible(query, withAnswers(query,
            rr(byteArrayOf(0xc0.toByte(), 12), 5, name("unregistered.example"))), admitted()))
        assertFalse(DnsPortalTierFallback.eligible(query, cnameOnly, admitted().copy(permittedPortalHosts = setOf("deltawifi.com"))))
        val unrelated = withAnswers(query, rr(name("unrelated.example"), 1, byteArrayOf(1, 2, 3, 4)))
        assertFalse(DnsPortalTierFallback.rescueAnswer(query, unrelated, admitted()))
        val completed = withAnswers(query, cname, rr(name("wifi.delta.com"), 1, byteArrayOf(1, 2, 3, 4)))
        assertTrue(DnsPortalTierFallback.rescueAnswer(query, completed, admitted()))
    }
    @Test fun `cycles contradictory alias address and nxdomain address fail closed`() {
        val query = testDnsQuery("deltawifi.com")
        val cycle = withAnswers(query, rr(byteArrayOf(0xc0.toByte(), 12), 5, name("wifi.delta.com")),
            rr(name("wifi.delta.com"), 5, name("deltawifi.com")))
        assertEquals(DnsFallbackResponse.Kind.MALFORMED, DnsFallbackResponse.classify(query, cycle).kind)
        val contradictory = withAnswers(query, rr(byteArrayOf(0xc0.toByte(), 12), 5, name("wifi.delta.com")),
            rr(byteArrayOf(0xc0.toByte(), 12), 1, byteArrayOf(1, 2, 3, 4)))
        assertEquals(DnsFallbackResponse.Kind.MALFORMED, DnsFallbackResponse.classify(query, contradictory).kind)
        val nxdomain = positive(query).also { it[3] = 0x83.toByte() }
        assertEquals(DnsFallbackResponse.Kind.MALFORMED, DnsFallbackResponse.classify(query, nxdomain).kind)
    }
    @Test fun `wire aliases accept single labels root and non-hostname labels without filtering normalization`() {
        for (type in listOf(1, 28)) for (target in listOf("intranet", "_service", "-edge-", "192.0.2.1", "")) {
            val query = testDnsQuery("files.example.com", rawType = type)
            val answer = withAnswers(query, rr(byteArrayOf(0xc0.toByte(), 12), 5, name(target)),
                rr(name(target.uppercase(java.util.Locale.ROOT)), type, ByteArray(if (type == 1) 4 else 16) { 1 }))
            val result = DnsFallbackResponse.classify(query, answer)
            assertEquals("target '$target' type $type", DnsFallbackResponse.Kind.POSITIVE, result.kind)
            assertEquals(listOf(target), result.aliases)
            assertEquals(1, result.terminalAddressCount)
            assertArrayEquals(answer, ResolverReplyBoundary.boundForClient(query, answer))
            val cnameOnly = withAnswers(query, rr(byteArrayOf(0xc0.toByte(), 12), 5, name(target)))
            assertEquals(DnsFallbackResponse.Kind.NEGATIVE, DnsFallbackResponse.classify(query, cnameOnly).kind)
        }
    }
    @Test fun `opaque wire labels remain distinct and cannot impersonate registered portal aliases`() {
        val query = testDnsQuery("deltawifi.com")
        for (label in listOf("wifi.delta.com".toByteArray(), "wifi\\046delta.com".toByteArray(),
            byteArrayOf(0, 0x80.toByte(), 0xff.toByte(), 'A'.code.toByte()))) {
            val target = byteArrayOf(label.size.toByte()) + label + byteArrayOf(0)
            val answer = withAnswers(query, rr(byteArrayOf(0xc0.toByte(), 12), 5, target),
                rr(target, 1, byteArrayOf(1, 2, 3, 4)))
            val result = DnsFallbackResponse.classify(query, answer)
            assertEquals(DnsFallbackResponse.Kind.POSITIVE, result.kind)
            assertFalse(result.aliases.single() in DnsPortalTierFallback.registeredHosts)
            assertFalse(DnsPortalTierFallback.rescueAnswer(query, answer, admitted()))
        }
        val singleLabel = withAnswers(query, rr(byteArrayOf(0xc0.toByte(), 12), 5, name("intranet")),
            rr(name("intranet"), 1, byteArrayOf(1, 2, 3, 4)))
        assertFalse(DnsPortalTierFallback.rescueAnswer(query, singleLabel, admitted()))
        val unrelatedOpaque = withAnswers(query,
            rr(byteArrayOf(0xc0.toByte(), 12), 5, byteArrayOf(1, 0x80.toByte(), 0)),
            rr(name("\\128"), 1, byteArrayOf(1, 2, 3, 4)))
        assertEquals(DnsFallbackResponse.Kind.NEGATIVE, DnsFallbackResponse.classify(query, unrelatedOpaque).kind)
    }
    @Test fun `alias traversal starts from actual query octets instead of normalized IDNA spelling`() {
        val query = testDnsQuery("Bücher.example")
        assertEquals("xn--bcher-kva.example", DnsMessage.parseQuestion(query).normalizedDomain)
        val answer = withAnswers(query, rr(byteArrayOf(0xc0.toByte(), 12), 5, name("intranet")),
            rr(name("intranet"), 1, byteArrayOf(1, 2, 3, 4)))
        assertEquals(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.classify(query, answer).kind)
        val unrelated = withAnswers(query, rr(name("xn--bcher-kva.example"), 5, name("intranet")),
            rr(name("intranet"), 1, byteArrayOf(1, 2, 3, 4)))
        assertEquals(DnsFallbackResponse.Kind.NEGATIVE, DnsFallbackResponse.classify(query, unrelated).kind)
    }
    @Test fun `DNAME synthesis preserves wire labels root targets and the 255 octet boundary`() {
        val query = testDnsQuery("files.example.com")
        for ((target, terminal) in listOf("intranet" to "files.intranet", "" to "files")) {
            val answer = withAnswers(query, rr(name("example.com"), 39, name(target)),
                rr(byteArrayOf(0xc0.toByte(), 12), 5, name(terminal)), rr(name(terminal), 1, byteArrayOf(1, 2, 3, 4)))
            val result = DnsFallbackResponse.classify(query, answer)
            assertEquals(DnsFallbackResponse.Kind.POSITIVE, result.kind)
            assertEquals(listOf(terminal), result.aliases)
        }
        val prefix = "p".repeat(63)
        val longQuery = testDnsQuery("$prefix.example.com")
        for (lastLabelLength in listOf(61, 62)) {
            val target = listOf("a".repeat(63), "b".repeat(63), "c".repeat(lastLabelLength)).joinToString(".")
            val terminal = "$prefix.$target"
            // The overflowing synthesized name is not encoded as an RR owner in this packet.
            val answer = withAnswers(longQuery, rr(name("example.com"), 39, name(target)),
                if (lastLabelLength == 61) rr(name(terminal), 1, byteArrayOf(1, 2, 3, 4))
                else rr(byteArrayOf(0xc0.toByte(), 12), 16, byteArrayOf(0)))
            assertNotNull(DnsResponseWireWalker.walk(answer))
            val result = DnsFallbackResponse.classify(longQuery, answer)
            assertEquals(if (lastLabelLength == 61) DnsFallbackResponse.Kind.POSITIVE else DnsFallbackResponse.Kind.MALFORMED, result.kind)
            if (lastLabelLength == 61) assertEquals(listOf(terminal), result.aliases)
        }
        val manyLabels = List(127) { "a" }.joinToString(".")
        val manyLabelAnswer = withAnswers(query, rr(byteArrayOf(0xc0.toByte(), 12), 5, name(manyLabels)),
            rr(name(manyLabels), 1, byteArrayOf(1, 2, 3, 4)))
        assertEquals(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.classify(query, manyLabelAnswer).kind)
    }
    @Test fun `unterminated DNAME expansion and malformed encoded aliases fail closed`() {
        for ((query, owner, target, finalOwner) in listOf(
            listOf("files.example.com", "", "local", "files.example.com.local.local.local"),
            listOf("files.a", "a", "a.a", "files.a.a.a.a"))) {
            val request = testDnsQuery(query)
            val expansion = withAnswers(request, rr(name(owner), 39, name(target)),
                rr(name(finalOwner), 1, byteArrayOf(1, 2, 3, 4)))
            assertEquals(DnsFallbackResponse.Kind.MALFORMED, DnsFallbackResponse.classify(request, expansion).kind)
        }
        val query = testDnsQuery("files.example.com")
        for ((type, target) in listOf(5 to (byteArrayOf(64) + ByteArray(64) { 'a'.code.toByte() } + byteArrayOf(0)),
            5 to (name("intranet") + byteArrayOf(0)), 5 to byteArrayOf(0xc0.toByte(), 0x7f),
            39 to byteArrayOf(0xc0.toByte(), 12))) {
            val malformed = withAnswers(query, rr(byteArrayOf(0xc0.toByte(), 12), type, target))
            assertEquals(DnsFallbackResponse.Kind.MALFORMED, DnsFallbackResponse.classify(query, malformed).kind)
            assertNull(ResolverReplyBoundary.boundForClient(query, malformed))
        }
    }
    @Test fun `policy evidence survives malformed alias while legal compressed alias targets remain positive`() {
        val query = testDnsQuery("deltawifi.com")
        val cname = rr(byteArrayOf(0xc0.toByte(), 12), 5, name("wifi.delta.com"))
        val targetOffset = query.size + 12
        val compressedOwner = byteArrayOf((0xc0 or (targetOffset ushr 8)).toByte(), targetOffset.toByte())
        val positiveAlias = withAnswers(query, cname, rr(compressedOwner, 1, byteArrayOf(1, 2, 3, 4)))
        assertEquals(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.classify(query, positiveAlias).kind)
        assertTrue(DnsPortalTierFallback.rescueAnswer(query, positiveAlias, admitted()))
        val malformedCname = rr(byteArrayOf(0xc0.toByte(), 12), 5, byteArrayOf(0xc0.toByte(), 0x7f))
        val policy = withAnswers(query, malformedCname).also { it[11] = 1 } +
            testOptRecord(byteArrayOf(0, 15, 0, 2, 0, 15))
        assertEquals(DnsFallbackResponse.Kind.PROHIBITED, DnsFallbackResponse.classify(query, policy).kind)
        val validation = ResponseValidation.validate(query, policy, ResolverTransport.DOT) as ResponseValidationResult.Invalid
        assertTrue(validation.terminal)
        assertTrue(validation.peerAnswered)
        val arbitraryDohId = policy.copyOf().also { it[0] = 0; it[1] = 0 }
        val doh = ResponseValidation.validate(query, arbitraryDohId, ResolverTransport.MULLVAD_DOH) as ResponseValidationResult.Invalid
        assertTrue(doh.terminal)
        assertTrue(doh.peerAnswered)
    }

    @Test fun `truncated udp question permits same resolver tcp retry despite partial resource record`() {
        val query = testDnsQuery()
        val truncated = reply(query).also { it[2] = (it[2].toInt() or 2).toByte(); it[7] = 1 } + byteArrayOf(0xc0.toByte())
        val server = java.net.InetAddress.getByName("192.0.2.53")
        val validation = ResponseValidation.validate(query, truncated, ResolverTransport.DEVICE_UDP, DatagramSource(server, 53), server)
        assertTrue(validation is ResponseValidationResult.Valid && validation.truncated)
        assertEquals(DnsFallbackResponse.Kind.TRUNCATED, DnsFallbackResponse.classify(query, truncated).kind)
        truncated[0] = 0
        assertTrue(ResponseValidation.validate(query, truncated, ResolverTransport.DEVICE_UDP, DatagramSource(server, 53), server) is ResponseValidationResult.Invalid)
    }

    private fun admitted() = DnsFallbackQueryContext(true, true, true, true, DnsPortalTierFallback.registeredHosts)
    private fun positive(query: ByteArray) = DnsMessage.bootstrapResponse(query, byteArrayOf(1, 2, 3, 4), ByteArray(16).apply { this[15] = 1 })
    private fun reply(query: ByteArray, code: Int = 0, ede: List<Int> = emptyList()): ByteArray {
        val packet = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = (0x80 or (code and 15)).toByte() }
        if (ede.isEmpty() && code <= 15) return packet
        val options = ede.flatMap { listOf<Byte>(0, 15, 0, 2, (it ushr 8).toByte(), it.toByte()) }.toByteArray()
        val opt = testOptRecord(options).also { it[5] = (code ushr 4).toByte() }
        packet[11] = 1
        return packet + opt
    }
    private fun withAnswers(query: ByteArray, vararg records: ByteArray) = reply(query).also { it[7] = records.size.toByte() } + records.fold(byteArrayOf()) { all, record -> all + record }
    private fun rr(owner: ByteArray, type: Int, rdata: ByteArray) = owner + byteArrayOf(0, type.toByte(), 0, 1, 0, 0, 0, 60, (rdata.size ushr 8).toByte(), rdata.size.toByte()) + rdata
    private fun name(value: String) = if (value.isEmpty()) byteArrayOf(0) else value.split('.').fold(byteArrayOf()) { all, label -> all + byteArrayOf(label.length.toByte()) + label.toByteArray() } + byteArrayOf(0)
}
