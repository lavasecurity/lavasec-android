package com.lavasec.app.foundation.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Behavioral tests for [ChainedIpv6DnsResponsePolicy]: AAAA → NODATA, the surgical HTTPS/SVCB
 * `ipv6hint` strip, and — most importantly — that the wire rewrite never corrupts a response it
 * cannot rewrite provably-safely (byte-for-byte passthrough).
 */
class ChainedIpv6DnsResponsePolicyTest {
    // MARK: wire builders

    private fun u16(value: Int): ByteArray =
        byteArrayOf(((value ushr 8) and 0xff).toByte(), (value and 0xff).toByte())

    private fun encodedName(vararg labels: String): ByteArray {
        val out = ArrayList<Byte>()
        labels.forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            out += bytes.size.toByte()
            out += bytes.toList()
        }
        out += 0
        return out.toByteArray()
    }

    private fun pointer(to: Int): ByteArray =
        byteArrayOf((0xc0 or ((to ushr 8) and 0x3f)).toByte(), (to and 0xff).toByte())

    private fun svcParam(key: Int, value: ByteArray): ByteArray = u16(key) + u16(value.size) + value

    private fun serviceBindingRData(priority: Int, target: ByteArray, vararg params: ByteArray): ByteArray =
        u16(priority) + target + params.flatMap { it.toList() }.toByteArray()

    private fun resourceRecord(owner: ByteArray, type: Int, rdata: ByteArray, ttl: Long = 3600): ByteArray =
        owner + u16(type) + u16(1) +
            byteArrayOf(
                ((ttl ushr 24) and 0xff).toByte(),
                ((ttl ushr 16) and 0xff).toByte(),
                ((ttl ushr 8) and 0xff).toByte(),
                (ttl and 0xff).toByte(),
            ) +
            u16(rdata.size) + rdata

    private fun header(qd: Int, an: Int, ns: Int = 0, ar: Int = 0, flags: Int = 0x8180): ByteArray =
        u16(0x1234) + u16(flags) + u16(qd) + u16(an) + u16(ns) + u16(ar)

    private fun questionSection(name: ByteArray, type: Int): ByteArray = name + u16(type) + u16(1)

    // Common SvcParams, in the strictly increasing key order RFC 9460 §2.2 requires.
    private val alpn: ByteArray get() = svcParam(1, byteArrayOf(0x02, 0x68, 0x32)) // ALPN "h2"
    private val ipv4Hint: ByteArray get() = svcParam(4, byteArrayOf(1, 2, 3, 4)) // 1.2.3.4
    private val ech: ByteArray get() = svcParam(5, byteArrayOf(0x00, 0x04, 0xfe.toByte(), 0x0d, 0x00, 0x00)) // length-prefixed ECHConfigList: version 0xfe0d, empty config
    private val ipv6Hint: ByteArray get() = svcParam(6, ByteArray(16) { 0x20 })
    private val rootTarget: ByteArray get() = byteArrayOf(0)

    // MARK: gates — AAAA

    @Test
    fun `AAAA is answered NODATA only when the data path drops outbound IPv6`() {
        assertTrue(
            ChainedIpv6DnsResponsePolicy.answersWithNoData(true, DnsRecordType.AAAA),
        )
        assertFalse(
            ChainedIpv6DnsResponsePolicy.answersWithNoData(false, DnsRecordType.AAAA),
        )
        for (type in listOf(DnsRecordType.A, DnsRecordType.TXT, DnsRecordType.SVCB, DnsRecordType.HTTPS, DnsRecordType.UNKNOWN)) {
            assertFalse(
                "$type must still forward even when v6 is dropped",
                ChainedIpv6DnsResponsePolicy.answersWithNoData(true, type),
            )
        }
    }

    @Test
    fun `the strip gate covers exactly HTTPS and SVCB while IPv6 is dropped`() {
        assertTrue(ChainedIpv6DnsResponsePolicy.stripsIpv6Hint(true, DnsRecordType.HTTPS))
        assertTrue(ChainedIpv6DnsResponsePolicy.stripsIpv6Hint(true, DnsRecordType.SVCB))
        assertFalse(ChainedIpv6DnsResponsePolicy.stripsIpv6Hint(false, DnsRecordType.HTTPS))
        assertFalse(ChainedIpv6DnsResponsePolicy.stripsIpv6Hint(false, DnsRecordType.SVCB))
        for (type in listOf(DnsRecordType.A, DnsRecordType.AAAA, DnsRecordType.TXT, DnsRecordType.UNKNOWN)) {
            assertFalse(ChainedIpv6DnsResponsePolicy.stripsIpv6Hint(true, type))
        }
    }

    // MARK: AAAA → NODATA

    @Test
    fun `an AAAA query becomes a NOERROR response with no answers`() {
        val query = dnsQuery("mullvad.net", rawType = 28)
        val response = ChainedIpv6DnsResponsePolicy.noDataResponse(query)

        assertEquals(0x1234, response.readUInt16(0))
        assertEquals(0x8180, response.readUInt16(2))
        assertEquals(1, response.readUInt16(4))
        assertEquals(0, response.readUInt16(6))
        assertEquals(0, response.readUInt16(8))
        assertEquals(0, response.readUInt16(10))
        assertArrayEquals(query.copyOfRange(12, query.size), response.copyOfRange(12, response.size))
    }

    @Test
    fun `NODATA synthesis copies the RD bit and clears it when the query did not set it`() {
        for (recursionDesired in listOf(true, false)) {
            val query = dnsQuery("mullvad.net", rawType = 28, recursionDesired = recursionDesired)
            val response = ChainedIpv6DnsResponsePolicy.noDataResponse(query)
            assertEquals(if (recursionDesired) 0x8180 else 0x8080, response.readUInt16(2))
        }
    }

    @Test
    fun `NODATA synthesis refuses a query that is not a well-formed IN query`() {
        try {
            ChainedIpv6DnsResponsePolicy.noDataResponse(byteArrayOf(0x12, 0x34, 0x01))
            fail("a three-byte message is not a DNS query")
        } catch (expected: DnsMessageException) {
            assertEquals(DnsMessageError.PACKET_TOO_SHORT, expected.reason)
        }
    }

    // MARK: ipv6hint strip — the happy path

    @Test
    fun `strips the ipv6hint keeping ALPN and ipv4hint and fixing RDLENGTH`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val withHint = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, alpn, ipv4Hint, ipv6Hint))
        val expected = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, alpn, ipv4Hint))

        assertArrayEquals(expected, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(withHint, fullTunnelChained = true))
    }

    @Test
    fun `strips an SVCB type 64 record as well as HTTPS`() {
        val question = questionSection(encodedName("_dns", "example", "com"), 64)
        val withHint = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 64, rdata = serviceBindingRData(1, rootTarget, ipv4Hint, ipv6Hint))
        val expected = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 64, rdata = serviceBindingRData(1, rootTarget, ipv4Hint))

        assertArrayEquals(expected, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(withHint, fullTunnelChained = true))
    }

    @Test
    fun `strips an ipv6hint that is the only SvcParam`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val withHint = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, ipv6Hint))
        val expected = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget))

        assertArrayEquals(expected, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(withHint, fullTunnelChained = true))
    }

    @Test
    fun `strips across multiple service binding records and leaves an OPT in additional`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val opt = resourceRecord(owner = byteArrayOf(0), type = 41, rdata = byteArrayOf(), ttl = 0)
        val withHint = header(qd = 1, an = 2, ar = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, alpn, ipv6Hint)) +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(2, rootTarget, ech, ipv6Hint)) +
            opt
        val expected = header(qd = 1, an = 2, ar = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, alpn)) +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(2, rootTarget, ech)) +
            opt

        assertArrayEquals(expected, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(withHint, fullTunnelChained = true))
    }

    @Test
    fun `clears the DNSSEC Authenticated Data bit when an answer is modified`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val withAd = header(qd = 1, an = 1).copyOf().also { it[3] = (it[3].toInt() or 0x20).toByte() }
        val response = withAd + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, alpn, ipv6Hint))

        val stripped = ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true)
        assertEquals(0, stripped[3].toInt() and 0x20)
    }

    // MARK: ipv6hint strip — passthrough

    @Test
    fun `leaves a service binding answer without an ipv6hint unchanged`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val response = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, alpn, ipv4Hint, ech))

        assertArrayEquals(response, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true))
    }

    @Test
    fun `leaves an AliasMode record unchanged`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val response = header(qd = 1, an = 1) + question +
            resourceRecord(
                pointer(to = 12),
                type = 65,
                rdata = serviceBindingRData(0, encodedName("svc", "example", "net")),
            )

        assertArrayEquals(response, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true))
    }

    @Test
    fun `leaves non-service-binding answers unchanged`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val aRecord = resourceRecord(pointer(to = 12), type = 1, rdata = byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val txtRecord = resourceRecord(pointer(to = 12), type = 16, rdata = byteArrayOf(3, 0x61, 0x62, 0x63))
        val response = header(qd = 1, an = 2) + question + aRecord + txtRecord

        assertArrayEquals(response, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true))
    }

    @Test
    fun `leaves the response unchanged when the data path carries IPv6`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val withHint = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, alpn, ipv6Hint))

        assertArrayEquals(withHint, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(withHint, fullTunnelChained = false))
    }

    @Test
    fun `leaves a message with no answer records unchanged`() {
        val response = header(qd = 1, an = 0) + questionSection(encodedName("example", "com"), 65)
        assertArrayEquals(response, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true))
    }

    @Test
    fun `never rewrites a query or a short message`() {
        val query = dnsQuery("example.com", rawType = 65)
        assertArrayEquals(query, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(query, fullTunnelChained = true))

        val short = byteArrayOf(0x00, 0x01, 0x02)
        assertArrayEquals(short, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(short, fullTunnelChained = true))
    }

    // MARK: ipv6hint strip — malformed RDATA and unsafe rewrites

    @Test
    fun `passes through malformed SvcParams rather than corrupting the record`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val badParam = u16(6) + u16(99) + byteArrayOf(0x00, 0x00) // claims 99 bytes, supplies 2
        val rdata = u16(1) + rootTarget + badParam
        val response = header(qd = 1, an = 1) + question + resourceRecord(pointer(to = 12), type = 65, rdata = rdata)

        assertArrayEquals(response, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true))
    }

    @Test
    fun `passes through a malformed fixed-size address record`() {
        val qname = encodedName("example", "com")
        val question = questionSection(qname, 65)
        val https = resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, ipv6Hint))
        // An A record whose RDLENGTH is 3 instead of 4: malformed, so nothing may be rewritten.
        val badA = resourceRecord(pointer(to = 12), type = 1, rdata = byteArrayOf(1, 2, 3))
        val response = header(qd = 1, an = 2) + question + https + badA

        assertArrayEquals(
            response,
            ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true),
        )
    }

    @Test
    fun `passes through a forward compression pointer`() {
        // Owner name points FORWARD, which RFC 1035 forbids; the message must not be rewritten.
        // header(qd=0, an=1): the record starts at offset 12. Its owner is a pointer to the SVCB
        // TargetName that sits later in the record's own RDATA.
        val ownerOffset = 12
        val rdataStart = ownerOffset + 2 + 2 + 2 + 4 + 2
        val rdata = serviceBindingRData(1, rootTarget, ipv6Hint)
        val record = resourceRecord(pointer(to = rdataStart + 2), type = 65, rdata = rdata)
        val response = header(qd = 0, an = 1) + record

        assertArrayEquals(
            response,
            ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true),
        )
    }

    @Test
    fun `passes through when a compression pointer targets a rewritten RDLENGTH field`() {
        val qname = encodedName("example", "com")
        val question = questionSection(qname, 65)
        val rdata = serviceBindingRData(1, rootTarget, ipv6Hint)
        // The first record's RDLENGTH field sits 10 bytes into the record (owner 2 + type 2 +
        // class 2 + ttl 4), after the 12-byte header and the question.
        val rdlengthFieldOffset = 12 + qname.size + 4 + 10
        val first = resourceRecord(pointer(to = 12), type = 65, rdata = rdata)
        // The second record's owner name points AT that field, so rewriting RDLENGTH in place
        // would change the name even though the target is before the deletion.
        val second = resourceRecord(pointer(to = rdlengthFieldOffset), type = 1, rdata = byteArrayOf(1, 2, 3, 4))
        val response = header(qd = 1, an = 2) + question + first + second

        assertArrayEquals(
            response,
            ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true),
        )
    }

    @Test
    fun `passes through an ipv6hint whose length is not a positive multiple of 16`() {
        val question = questionSection(encodedName("example", "com"), 65)
        for (badValue in listOf(ByteArray(0), ByteArray(8) { 0x20 }, ByteArray(17) { 0x20 })) {
            val rdata = u16(1) + rootTarget + svcParam(6, badValue)
            val response = header(qd = 1, an = 1) + question +
                resourceRecord(pointer(to = 12), type = 65, rdata = rdata)
            assertArrayEquals(
                response,
                ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true),
            )
        }
    }

    @Test
    fun `passes through an out-of-order SvcParam instead of guessing`() {
        val question = questionSection(encodedName("example", "com"), 65)
        // ALPN (key 1) precedes ipv4hint (key 4) precedes... a second, lower-key param is malformed.
        val rdata = u16(1) + rootTarget + alpn + ipv4Hint + ipv6Hint + svcParam(2, byteArrayOf(0x01))
        val response = header(qd = 1, an = 1) + question + resourceRecord(pointer(to = 12), type = 65, rdata = rdata)

        assertArrayEquals(response, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true))
    }

    @Test
    fun `passes through a record whose mandatory lists ipv6hint`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val mandatory = svcParam(0, u16(6))
        val response = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, mandatory, ipv6Hint))

        assertArrayEquals(response, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true))
    }

    @Test
    fun `passes through a compressed SVCB target name`() {
        val question = questionSection(encodedName("example", "com"), 65)
        // TargetName MUST be uncompressed (RFC 9460 §2.2): a pointer there is malformed.
        val rdata = u16(1) + pointer(to = 12) + ipv6Hint
        val response = header(qd = 1, an = 1) + question + resourceRecord(pointer(to = 12), type = 65, rdata = rdata)

        assertArrayEquals(response, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true))
    }

    @Test
    fun `passes through a non-allowlisted record type alongside a strippable one`() {
        // An MX record (type 15) is not on the walk's allowlist: it may carry a name in opaque RDATA
        // the walk cannot vouch for, so the whole message must be returned untouched.
        val question = questionSection(encodedName("example", "com"), 65)
        val mxOwner = pointer(to = 12)
        val mxRdata = u16(10) + encodedName("mail", "example", "com")
        val response = header(qd = 1, an = 2) + question +
            resourceRecord(mxOwner, type = 15, rdata = mxRdata) +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, alpn, ipv6Hint))

        assertArrayEquals(response, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true))
    }

    @Test
    fun `passes through a forward compression pointer into the shifted region`() {
        // A later record's owner points INTO the ipv6hint bytes. Removing the hint would shift those
        // bytes and break the pointer, so the strip MUST bail to the original. Load-bearing gate.
        val question = questionSection(encodedName("example", "com"), 65)
        val answer = resourceRecord(
            pointer(to = 12),
            type = 65,
            rdata = serviceBindingRData(1, rootTarget, alpn, ipv4Hint, ipv6Hint),
        )
        val answerStart = 12 + question.size
        // ipv6hint begins after owner(2) + fixed RR header(10) + priority(2) + root target(1) + alpn + ipv4hint.
        val ipv6HintOffset = answerStart + 2 + 10 + 2 + 1 + alpn.size + ipv4Hint.size
        val additional = resourceRecord(pointer(to = ipv6HintOffset), type = 1, rdata = byteArrayOf(5, 6, 7, 8))
        val response = header(qd = 1, an = 1, ar = 1) + question + answer + additional

        assertArrayEquals(response, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(response, fullTunnelChained = true))
    }

    // MARK: RDLENGTH and compression correctness

    @Test
    fun `fixes RDLENGTH exactly and preserves the compressed owner pointer`() {
        val question = questionSection(encodedName("example", "com"), 65)
        val strippedRdata = serviceBindingRData(1, rootTarget, alpn, ipv4Hint)
        val withHint = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, alpn, ipv4Hint, ipv6Hint))

        val stripped = ChainedIpv6DnsResponsePolicy.stripIpv6Hints(withHint, fullTunnelChained = true)
        val answerStart = 12 + question.size

        assertEquals(0xc00c, stripped.readUInt16(answerStart))
        val rdlength = stripped.readUInt16(answerStart + 10)
        assertEquals(strippedRdata.size, rdlength)
        assertArrayEquals(strippedRdata, stripped.copyOfRange(answerStart + 12, answerStart + 12 + rdlength))
        assertEquals(withHint.size - ipv6Hint.size, stripped.size)
    }

    @Test
    fun `a name compressed against the question still resolves after a strip`() {
        // The answer owner (0xC00C) points back into the untouched question section. The deletion is
        // later, so the pointer's target precedes it and stays valid; a CNAME to the canonical name
        // keeps its own compression intact too.
        val question = questionSection(encodedName("www", "example", "com"), 65)
        val canonical = encodedName("cdn", "example", "net")
        val cname = resourceRecord(pointer(to = 12), type = 5, rdata = canonical)
        val withHint = header(qd = 1, an = 2) + question + cname +
            resourceRecord(canonical, type = 65, rdata = serviceBindingRData(1, rootTarget, alpn, ipv6Hint))
        val expected = header(qd = 1, an = 2) + question + cname +
            resourceRecord(canonical, type = 65, rdata = serviceBindingRData(1, rootTarget, alpn))

        assertArrayEquals(expected, ChainedIpv6DnsResponsePolicy.stripIpv6Hints(withHint, fullTunnelChained = true))
    }

    @Test
    fun `the identity decompressor accepts the rewritten wire shape`() {
        // Every rewritten record must still satisfy the shared strict response walker's notion of a
        // well-formed message. This pins RDLENGTH/compression correctness against a second parser.
        val question = questionSection(encodedName("example", "com"), 65)
        val withHint = header(qd = 1, an = 1) + question +
            resourceRecord(pointer(to = 12), type = 65, rdata = serviceBindingRData(1, rootTarget, alpn, ipv6Hint))

        val stripped = ChainedIpv6DnsResponsePolicy.stripIpv6Hints(withHint, fullTunnelChained = true)
        assertEquals(stripped.size, assertWellFormedStructuredMessage(stripped))
    }

    /**
     * A minimal independent decoder: walks the question and every record, requiring names and
     * declared RDLENGTHs to stay inside the message and the sections to consume it exactly. Returns
     * the number of bytes it consumed; fails the test on any inconsistency.
     */
    private fun assertWellFormedStructuredMessage(message: ByteArray): Int {
        var cursor = 12
        val qd = message.readUInt16(4)
        val counts = listOf(message.readUInt16(6), message.readUInt16(8), message.readUInt16(10))
        repeat(qd) {
            while (true) {
                val length = message[cursor].toInt() and 0xff
                cursor += 1
                if (length == 0) break
                if (length and 0xc0 == 0xc0) {
                    cursor += 1
                    break
                }
                cursor += length
            }
            cursor += 4
        }
        counts.forEach { count ->
            repeat(count) {
                while (true) {
                    val length = message[cursor].toInt() and 0xff
                    cursor += 1
                    if (length == 0) break
                    if (length and 0xc0 == 0xc0) {
                        cursor += 1
                        break
                    }
                    cursor += length
                }
                val rdataLength = message.readUInt16(cursor + 8)
                cursor += 10 + rdataLength
                if (cursor > message.size) fail("record overruns the message")
            }
        }
        return cursor
    }
}
