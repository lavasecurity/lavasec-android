package com.lavasec.app.foundation.dns

import com.lavasec.app.foundation.parity.ParityFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DnsMessageTest {
    @Test
    fun `parses exactly one uncompressed IN question preserving spelling and raw QTYPE`() {
        val query = dnsQuery("Ads.Example.COM", rawType = 65_535)
        val question = DnsMessage.parseQuestion(query)

        assertEquals(0x1234, question.transactionId)
        assertEquals("Ads.Example.COM", question.domain)
        assertEquals("ads.example.com", question.normalizedDomain)
        assertEquals(DnsRecordType.UNKNOWN, question.recordType)
        assertEquals(65_535, question.rawRecordType)
        assertArrayEquals(query.copyOfRange(12, query.size), question.questionBytes(query))
    }

    @Test
    fun `parser rejects non-query opcode and non-IN class with exact reasons`() {
        assertEquals(
            DnsMessageError.UNSUPPORTED_OPCODE,
            rejected(dnsQuery("ads.example.com", opcode = 2)).reason,
        )
        assertEquals(
            DnsMessageError.UNSUPPORTED_QUESTION_CLASS,
            rejected(dnsQuery("ads.example.com", rawClass = 3)).reason,
        )
    }

    @Test
    fun `parser rejects nonempty answer and authority sections before accepting a query`() {
        assertEquals(
            DnsMessageError.UNEXPECTED_ANSWER_RECORDS,
            rejected(dnsQuery("ads.example.com", answerCount = 1)).reason,
        )
        assertEquals(
            DnsMessageError.UNEXPECTED_AUTHORITY_RECORDS,
            rejected(dnsQuery("ads.example.com", authorityCount = 1)).reason,
        )
    }

    @Test
    fun `parser accepts one complete OPT and rejects malformed duplicate and non-OPT additionals`() {
        val opt = dnsOptRecord(rdata = byteArrayOf(0, 10, 0, 2, 0xca.toByte(), 0xfe.toByte()))
        val valid = dnsQuery("ads.example.com", additionalRecords = listOf(opt))
        val parsed = DnsMessage.parseQuestion(valid)
        assertEquals("ads.example.com", parsed.normalizedDomain)
        assertArrayEquals(
            dnsQuery("ads.example.com").copyOfRange(12, dnsQuery("ads.example.com").size),
            parsed.questionBytes(valid),
        )

        assertEquals(
            DnsMessageError.MALFORMED_OPT_RECORD,
            rejected(dnsQuery("ads.example.com", additionalRecords = listOf(dnsOptRecord(owner = byteArrayOf(1, 'x'.code.toByte(), 0))))).reason,
        )
        assertEquals(
            DnsMessageError.DECLARED_SECTION_OVERRUN,
            rejected(
                dnsQuery(
                    "ads.example.com",
                    additionalRecords = listOf(dnsOptRecord(rdata = byteArrayOf(1), declaredRdataLength = 2)),
                ),
            ).reason,
        )
        assertEquals(
            DnsMessageError.DUPLICATE_OPT_RECORD,
            rejected(dnsQuery("ads.example.com", additionalRecords = listOf(dnsOptRecord(), dnsOptRecord()))).reason,
        )
        assertEquals(
            DnsMessageError.UNSUPPORTED_ADDITIONAL_RECORD,
            rejected(dnsQuery("ads.example.com", additionalRecords = listOf(dnsOptRecord(type = 1)))).reason,
        )
    }

    @Test
    fun `parser distinguishes declared section overrun from undeclared trailing bytes`() {
        assertEquals(
            DnsMessageError.DECLARED_SECTION_OVERRUN,
            rejected(
                dnsQuery(
                    "ads.example.com",
                    additionalRecords = listOf(dnsOptRecord().copyOfRange(0, 8)),
                ),
            ).reason,
        )
        assertEquals(
            DnsMessageError.UNDECLARED_TRAILING_BYTES,
            rejected(dnsQuery("ads.example.com", trailingBytes = byteArrayOf(0))).reason,
        )
        assertEquals(
            DnsMessageError.UNDECLARED_TRAILING_BYTES,
            rejected(
                dnsQuery(
                    "ads.example.com",
                    additionalRecords = listOf(dnsOptRecord()),
                    trailingBytes = byteArrayOf(0),
                ),
            ).reason,
        )
    }

    @Test
    fun `shared malformed and compressed corpus rejects with exact reasons`() {
        for (fixture in ParityFixtures.objects("dns-malformed-cases.json")) {
            try {
                DnsMessage.parseQuestion(fixture.getValue("hex")!!.hexBytes())
                fail("${fixture["name"]}: expected ${fixture["error"]}")
            } catch (error: DnsMessageException) {
                assertEquals(fixture["name"], fixture["error"], error.reason.name)
            }
        }
    }

    @Test
    fun `parser enforces exact 12 4096 and 4097 byte boundaries`() {
        assertEquals(DnsMessageError.NO_QUESTION, rejected(ByteArray(DnsMessage.HEADER_BYTES)).reason)
        val error = rejected(ByteArray(DnsMessage.MAX_MESSAGE_BYTES + 1))
        assertEquals(DnsMessageError.PACKET_TOO_LARGE, error.reason)

        val base = dnsQuery("ads.example.com")
        val optOverhead = dnsOptRecord().size
        val exactlyBounded = dnsQuery(
            "ads.example.com",
            additionalRecords = listOf(
                dnsOptRecord(rdata = ByteArray(DnsMessage.MAX_MESSAGE_BYTES - base.size - optOverhead)),
            ),
        )
        assertEquals(DnsMessage.MAX_MESSAGE_BYTES, exactlyBounded.size)
        assertEquals("ads.example.com", DnsMessage.parseQuestion(exactlyBounded).normalizedDomain)

        assertEquals(
            DnsMessageError.UNDECLARED_TRAILING_BYTES,
            rejected(base.copyOf(DnsMessage.MAX_MESSAGE_BYTES)).reason,
        )
    }

    @Test
    fun `parser rejects a wire QNAME over 255 bytes before IDNA normalization can shrink it`() {
        val softHyphenLabel = "\u00ad".repeat(31) + "a"
        val query = dnsQuery(List(4) { softHyphenLabel }.joinToString("."))

        assertEquals(257, query.size - DnsMessage.HEADER_BYTES - 4)
        assertEquals(DnsMessageError.MALFORMED_QUESTION, rejected(query).reason)
    }

    @Test
    fun `parser rejects wire labels whose boundaries change during normalization`() {
        val cases = listOf(
            "leading whitespace" to listOf(" ads", "example", "com"),
            "trailing whitespace" to listOf("ads", "example", "com "),
            "literal dot" to listOf("ads.example", "com"),
            "ideographic full stop" to listOf("ads\u3002example", "com"),
            "fullwidth full stop" to listOf("ads\uff0eexample", "com"),
            "halfwidth ideographic full stop" to listOf("ads\uff61example", "com"),
        )

        for ((name, labels) in cases) {
            assertEquals(
                name,
                DnsMessageError.MALFORMED_QUESTION,
                rejected(dnsQueryFromLabels(labels)).reason,
            )
        }
    }

    @Test
    fun `parser preserves legitimate case and IDNA normalization within wire labels`() {
        val question = DnsMessage.parseQuestion(dnsQueryFromLabels(listOf("Bücher", "Example")))

        assertEquals("Bücher.Example", question.domain)
        assertEquals("xn--bcher-kva.example", question.normalizedDomain)
    }

    @Test
    fun `blocked response defaults to TTL one and answers only address types`() {
        for (fixture in ParityFixtures.objects("dns-response-cases.json")) {
            val rawType = fixture.getValue("rawType")!!.toInt()
            val query = dnsQuery("Ads.Example.COM", rawType = rawType)
            val response = DnsMessage.blockedResponse(query)
            val answerCount = fixture.getValue("answers")!!.toInt()
            val rdataBytes = fixture.getValue("rdataBytes")!!.toInt()

            assertEquals(0x1234, response.readUInt16(0))
            assertEquals(0x8180, response.readUInt16(2))
            assertEquals(1, response.readUInt16(4))
            assertEquals(answerCount, response.readUInt16(6))
            assertArrayEquals(query.copyOfRange(12, query.size), response.copyOfRange(12, query.size))
            assertTrue(response.size <= DnsMessage.MAX_MESSAGE_BYTES)

            if (answerCount == 0) {
                assertEquals(query.size, response.size)
            } else {
                val answerOffset = query.size
                assertArrayEquals(byteArrayOf(0xc0.toByte(), 0x0c), response.copyOfRange(answerOffset, answerOffset + 2))
                assertEquals(rawType, response.readUInt16(answerOffset + 2))
                assertEquals(1, response.readUInt16(answerOffset + 4))
                assertEquals(1L, response.readUInt32(answerOffset + 6))
                assertEquals(rdataBytes, response.readUInt16(answerOffset + 10))
                assertArrayEquals(ByteArray(rdataBytes), response.copyOfRange(response.size - rdataBytes, response.size))
            }
        }
    }

    @Test
    fun `blocked response copies RD and exact question bytes`() {
        val noRd = dnsQuery("MiXeD.Example", recursionDesired = false)
        val response = DnsMessage.blockedResponse(noRd)

        assertEquals(0x8080, response.readUInt16(2))
        assertArrayEquals(noRd.copyOfRange(12, noRd.size), response.copyOfRange(12, noRd.size))
    }

    @Test
    fun `bootstrap response returns exact address types with TTL 60 and empty other types`() {
        val ipv4 = byteArrayOf(194.toByte(), 242.toByte(), 2, 2)
        val ipv6 = "2a07e340000000000000000000000002".hexBytes()
        val cases = listOf(1 to ipv4, 28 to ipv6, 16 to byteArrayOf())

        for ((rawType, expectedAddress) in cases) {
            val query = dnsQuery("DnS.MuLlVaD.NeT", rawType = rawType)
            val response = DnsMessage.bootstrapResponse(query, ipv4, ipv6)
            assertEquals(0x1234, response.readUInt16(0))
            assertEquals(0x8180, response.readUInt16(2))
            assertEquals(1, response.readUInt16(4))
            assertEquals(if (expectedAddress.isEmpty()) 0 else 1, response.readUInt16(6))
            assertArrayEquals(query.copyOfRange(12, query.size), response.copyOfRange(12, query.size))
            if (expectedAddress.isNotEmpty()) {
                val answerOffset = query.size
                assertEquals(rawType, response.readUInt16(answerOffset + 2))
                assertEquals(1, response.readUInt16(answerOffset + 4))
                assertEquals(60, response.readUInt32(answerOffset + 6))
                assertArrayEquals(expectedAddress, response.copyOfRange(response.size - expectedAddress.size, response.size))
            }
        }
    }

    @Test
    fun `local synthesis drops OPT but preserves exact ID question and RD`() {
        val query = dnsQuery(
            "dns.mullvad.net",
            recursionDesired = false,
            additionalRecords = listOf(dnsOptRecord(rdata = byteArrayOf(0, 1, 2, 3))),
        )
        val questionOnly = dnsQuery("dns.mullvad.net", recursionDesired = false)
        val blocked = DnsMessage.blockedResponse(query)
        val bootstrap = DnsMessage.bootstrapResponse(
            query,
            byteArrayOf(194.toByte(), 242.toByte(), 2, 2),
            "2a07e340000000000000000000000002".hexBytes(),
        )
        val servfail = DnsMessage.servfailResponse(query)!!

        for (response in listOf(blocked, bootstrap, servfail)) {
            assertEquals(0x1234, response.readUInt16(0))
            assertEquals(0, response.readUInt16(2) and 0x0100)
            assertEquals(0, response.readUInt16(10))
            assertArrayEquals(questionOnly.copyOfRange(12, questionOnly.size), response.copyOfRange(12, questionOnly.size))
        }
    }

    @Test
    fun `pre-parsed blocked response accepts unchanged RD states and rejects header mutation`() {
        for (recursionDesired in listOf(true, false)) {
            val query = dnsQuery("ads.example.com", recursionDesired = recursionDesired)
            val question = DnsMessage.parseQuestion(query)
            val response = DnsMessage.blockedResponse(query, question)
            assertEquals(if (recursionDesired) 0x8180 else 0x8080, response.readUInt16(2))

            val mutatedHeader = query.copyOf().also { it[2] = (it[2].toInt() xor 0x01).toByte() }
            assertEquals(
                DnsMessageError.MALFORMED_QUESTION,
                rejected { DnsMessage.blockedResponse(mutatedHeader, question) }.reason,
            )
        }
    }

    @Test
    fun `pre-parsed synthesis rejects mutation anywhere in the validated query`() {
        val query = dnsQuery(
            "ads.example.com",
            additionalRecords = listOf(dnsOptRecord(rdata = byteArrayOf(1, 2, 3, 4))),
        )
        val question = DnsMessage.parseQuestion(query)
        val mutatedOpt = query.copyOf().also { it[it.lastIndex] = 5 }

        assertEquals(
            DnsMessageError.MALFORMED_QUESTION,
            rejected { DnsMessage.blockedResponse(mutatedOpt, question) }.reason,
        )
    }

    @Test
    fun `blocked response rejects stale pre-parsed question provenance and mutation`() {
        val query = dnsQuery("ads.example.com")
        val question = DnsMessage.parseQuestion(query)
        val sameLengthDifferentQuery = dnsQuery(
            "ads.example.com",
            rawType = 28,
            transactionId = 0x5678,
        )
        val mutatedAfterParse = query.copyOf().also { bytes ->
            bytes[13] = 'x'.code.toByte()
        }

        assertEquals(
            DnsMessageError.MALFORMED_QUESTION,
            rejected { DnsMessage.blockedResponse(sameLengthDifferentQuery, question) }.reason,
        )
        assertEquals(
            DnsMessageError.MALFORMED_QUESTION,
            rejected { DnsMessage.blockedResponse(mutatedAfterParse, question) }.reason,
        )
        assertEquals(
            DnsMessageError.PACKET_TOO_LARGE,
            rejected {
                DnsMessage.blockedResponse(query.copyOf(DnsMessage.MAX_MESSAGE_BYTES + 1), question)
            }.reason,
        )
    }

    @Test
    fun `SERVFAIL echoes valid question and uses header-only fallback for formable malformed queries`() {
        val valid = dnsQuery("ads.example.com")
        val validResponse = DnsMessage.servfailResponse(valid)!!
        assertEquals(0x8182, validResponse.readUInt16(2))
        assertEquals(1, validResponse.readUInt16(4))
        assertArrayEquals(valid.copyOfRange(12, valid.size), validResponse.copyOfRange(12, validResponse.size))

        val malformed = "beef01000000000000000000".hexBytes()
        val malformedResponse = DnsMessage.servfailResponse(malformed)!!
        assertEquals(12, malformedResponse.size)
        assertEquals(0xbeef, malformedResponse.readUInt16(0))
        assertEquals(0x8182, malformedResponse.readUInt16(2))
        assertEquals(0, malformedResponse.readUInt16(4))
        assertNull(DnsMessage.servfailResponse(byteArrayOf(0x12, 0x34, 0x01)))
    }

    private fun rejected(data: ByteArray): DnsMessageException = rejected { DnsMessage.parseQuestion(data) }

    private fun rejected(block: () -> Unit): DnsMessageException = try {
        block()
        fail("Expected DNS message rejection")
        error("unreachable")
    } catch (error: DnsMessageException) {
        error
    }
}
