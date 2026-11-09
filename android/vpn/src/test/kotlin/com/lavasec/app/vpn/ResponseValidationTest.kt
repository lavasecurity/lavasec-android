package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseValidationTest {
    private val server = InetAddress.getByName("192.0.2.53")

    @Test
    fun `UDP accepts an exact response and returns immutable bytes`() {
        val query = query("MiXeD.example", id = 0x1234)
        val response = response(query)
        val expected = response.copyOf()

        val result = ResponseValidation.validate(
            query = query,
            response = response,
            transport = ResolverTransport.DEVICE_UDP,
            datagramSource = DatagramSource(server, 53),
            expectedServer = server,
        )

        assertTrue(result is ResponseValidationResult.Valid)
        result as ResponseValidationResult.Valid
        assertFalse(result.truncated)

        response.fill(0)
        val firstRead = result.dnsMessage
        assertArrayEquals(expected, firstRead)
        firstRead.fill(0)
        assertArrayEquals(expected, result.dnsMessage)
    }

    @Test
    fun `every transport preserves exact mixed-case question identity`() {
        val query = query("MiXeD.example")
        val exact = response(query)
        val recased = response(query).also { it[13] = 'm'.code.toByte() }

        listOf(
            ResolverTransport.DEVICE_UDP,
            ResolverTransport.DEVICE_TCP,
            ResolverTransport.MULLVAD_DOH,
        ).forEach { transport ->
            val accepted = ResponseValidation.validate(
                query,
                exact,
                transport,
                DatagramSource(server, 53),
                server,
            )
            assertTrue(
                "$transport should accept the byte-identical question",
                accepted is ResponseValidationResult.Valid,
            )
            assertInvalid(query, recased, transport)
        }
    }

    @Test
    fun `UDP binds source address port and transaction ID while DoH exempts only ID`() {
        val query = query("example.com", id = 7)
        val wrongId = response(query).also { write16(it, 0, 8) }

        assertInvalid(query, wrongId, ResolverTransport.DEVICE_UDP)
        assertInvalid(query, wrongId, ResolverTransport.DEVICE_TCP)
        assertInvalid(
            query,
            response(query),
            ResolverTransport.DEVICE_UDP,
            source = null,
        )
        assertInvalid(
            query,
            response(query),
            ResolverTransport.DEVICE_UDP,
            source = DatagramSource(server, 5353),
        )
        assertInvalid(
            query,
            response(query),
            ResolverTransport.DEVICE_UDP,
            source = DatagramSource(InetAddress.getByName("192.0.2.54"), 53),
        )
        assertInvalid(
            query,
            response(query),
            ResolverTransport.DEVICE_UDP,
            expectedServer = null,
        )

        assertTrue(
            ResponseValidation.validate(
                query,
                wrongId,
                ResolverTransport.MULLVAD_DOH,
            ) is ResponseValidationResult.Valid,
        )
    }

    @Test
    fun `rejects query response opcode count and exact raw question mismatches`() {
        val query = query("MiXeD.example")
        val invalidPairs = listOf(
            query.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() } to response(query),
            query to response(query).also { it[2] = (it[2].toInt() and 0x7f).toByte() },
            query to response(query).also { it[2] = (it[2].toInt() or 0x08).toByte() },
            query.copyOf().also { write16(it, 4, 0) } to response(query),
            query to response(query).also { write16(it, 4, 0) },
            query to response(query).also { it[13] = 'm'.code.toByte() },
            query to response(query).also { it[query.size - 3] = 2 },
            query to response(query).also { it[query.size - 1] = 2 },
        )

        invalidPairs.forEach { (candidateQuery, candidateResponse) ->
            assertInvalid(candidateQuery, candidateResponse, ResolverTransport.DEVICE_TCP)
        }
    }

    @Test
    fun `rejects compressed response questions`() {
        val query = query("example.com")
        val compressedQuestion = ByteArray(12 + 2 + 4).also {
            query.copyInto(it, 0, 0, 12)
            write16(it, 2, 0x8180)
            it[12] = 0xc0.toByte()
            it[13] = 0x0c
            query.copyInto(it, 14, query.size - 4, query.size)
        }

        assertInvalid(query, compressedQuestion, ResolverTransport.DEVICE_TCP)
    }

    @Test
    fun `enforces the 255 octet expanded name limit for questions and record owners`() {
        val maximumName = wireName(63, 63, 63, 61)
        val oversizedName = wireName(63, 63, 63, 62)
        val maximumQuery = query(maximumName)
        val oversizedQuery = query(oversizedName)

        assertTrue(
            ResponseValidation.validate(
                maximumQuery,
                response(maximumQuery),
                ResolverTransport.DEVICE_TCP,
            ) is ResponseValidationResult.Valid,
        )
        assertInvalid(
            oversizedQuery,
            response(oversizedQuery),
            ResolverTransport.DEVICE_TCP,
        )
        assertTrue(
            ResponseValidation.validate(
                maximumQuery,
                response(
                    maximumQuery,
                    answers = listOf(
                        answer(owner = byteArrayOf(0xc0.toByte(), 0x0c)),
                    ),
                ),
                ResolverTransport.DEVICE_TCP,
            ) is ResponseValidationResult.Valid,
        )
        assertTrue(
            ResponseValidation.validate(
                query("example.com"),
                response(
                    query("example.com"),
                    answers = listOf(answer(owner = maximumName)),
                ),
                ResolverTransport.DEVICE_TCP,
            ) is ResponseValidationResult.Valid,
        )
        assertInvalid(
            query("example.com"),
            response(
                query("example.com"),
                answers = listOf(answer(owner = oversizedName)),
            ),
            ResolverTransport.DEVICE_TCP,
        )
    }

    @Test
    fun `walks compressed record owners and rejects unsafe pointers and section bounds`() {
        val query = query("example.com")
        val validAnswer = answer(
            owner = byteArrayOf(0xc0.toByte(), 0x0c),
            rdata = byteArrayOf(1, 2, 3, 4),
        )
        assertTrue(
            ResponseValidation.validate(
                query,
                response(query, answers = listOf(validAnswer)),
                ResolverTransport.DEVICE_TCP,
            ) is ResponseValidationResult.Valid,
        )

        val recordOffset = query.size
        val loop = response(
            query,
            answers = listOf(
                answer(
                    owner = byteArrayOf(
                        (0xc0 or (recordOffset ushr 8)).toByte(),
                        recordOffset.toByte(),
                    ),
                ),
            ),
        )
        assertInvalid(query, loop, ResolverTransport.DEVICE_TCP)

        val forwardTarget = recordOffset + 12
        val forwardPointer = response(
            query,
            answers = listOf(
                answer(
                    owner = byteArrayOf(
                        (0xc0 or (forwardTarget ushr 8)).toByte(),
                        forwardTarget.toByte(),
                    ),
                    rdata = byteArrayOf(0),
                ),
            ),
        )
        assertInvalid(query, forwardPointer, ResolverTransport.DEVICE_TCP)

        val outOfRange = response(
            query,
            answers = listOf(answer(owner = byteArrayOf(0xff.toByte(), 0xff.toByte()))),
        )
        assertInvalid(query, outOfRange, ResolverTransport.DEVICE_TCP)

        val reservedLabelKind = response(
            query,
            answers = listOf(answer(owner = byteArrayOf(0x40, 0))),
        )
        assertInvalid(query, reservedLabelKind, ResolverTransport.DEVICE_TCP)

        val rdataOverrun = response(query, answers = listOf(validAnswer)).also {
            write16(it, query.size + validAnswer.size - 6, 100)
        }
        assertInvalid(query, rdataOverrun, ResolverTransport.DEVICE_TCP)
        assertInvalid(
            query,
            response(query, answers = listOf(validAnswer)) + byteArrayOf(0),
            ResolverTransport.DEVICE_TCP,
        )
    }

    @Test
    fun `compression pointers target only prior encoded name boundaries`() {
        val query = query("example.com")

        listOf(
            4, // Header QDCOUNT high byte.
            query.size - 4, // Question QTYPE high byte.
        ).forEach { nonNameOffset ->
            assertInvalid(
                query,
                response(
                    query,
                    answers = listOf(answer(owner = pointer(nonNameOffset))),
                ),
                ResolverTransport.DEVICE_TCP,
            )
        }

        val comLabelOffset = 12 + 1 + "example".length
        assertTrue(
            ResponseValidation.validate(
                query,
                response(
                    query,
                    answers = listOf(answer(owner = pointer(comLabelOffset))),
                ),
                ResolverTransport.DEVICE_TCP,
            ) is ResponseValidationResult.Valid,
        )

        val opaqueTxt = answer(
            owner = pointer(12),
            type = 16,
            rdata = byteArrayOf(3, 'b'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 0),
        )
        val opaqueRdataOffset = query.size + pointer(12).size + 10
        assertInvalid(
            query,
            response(
                query,
                answers = listOf(
                    opaqueTxt,
                    answer(owner = pointer(opaqueRdataOffset)),
                ),
            ),
            ResolverTransport.DEVICE_TCP,
        )
    }

    @Test
    fun `validates compressed names inside structured record data`() {
        val query = query("example.com")
        val validName = pointer(12)
        val invalidName = pointer(4)
        val fixedSoaFields = ByteArray(20)
        val firstRdataOffset = query.size + pointer(12).size + 10

        val malformedRecords = listOf(
            answer(owner = pointer(12), type = 2, rdata = invalidName), // NS
            answer(owner = pointer(12), type = 5, rdata = invalidName), // CNAME
            answer(
                owner = pointer(12),
                type = 5,
                rdata = pointer(firstRdataOffset),
            ), // CNAME self-loop
            answer(
                owner = pointer(12),
                type = 5,
                rdata = pointer(firstRdataOffset + 2),
            ), // CNAME forward pointer
            answer(
                owner = pointer(12),
                type = 5,
                rdata = validName + byteArrayOf(0),
            ), // CNAME trailing bytes
            answer(owner = pointer(12), type = 12, rdata = invalidName), // PTR
            answer(owner = pointer(12), type = 15, rdata = byteArrayOf(0)), // MX prefix
            answer(
                owner = pointer(12),
                type = 15,
                rdata = byteArrayOf(0, 10) + invalidName,
            ), // MX
            answer(
                owner = pointer(12),
                type = 15,
                rdata = byteArrayOf(0, 10) + validName + byteArrayOf(0),
            ), // MX trailing bytes
            answer(
                owner = pointer(12),
                type = 6,
                rdata = invalidName + validName + fixedSoaFields,
            ), // SOA MNAME
            answer(
                owner = pointer(12),
                type = 6,
                rdata = validName + invalidName + fixedSoaFields,
            ), // SOA RNAME
            answer(
                owner = pointer(12),
                type = 6,
                rdata = validName + validName + ByteArray(19),
            ), // SOA short fixed fields
            answer(
                owner = pointer(12),
                type = 6,
                rdata = validName + validName + ByteArray(21),
            ), // SOA trailing bytes
        )

        malformedRecords.forEach { record ->
            assertInvalid(
                query,
                response(query, answers = listOf(record)),
                ResolverTransport.DEVICE_TCP,
            )
        }

        val validRecords = listOf(
            answer(owner = pointer(12), type = 2, rdata = validName),
            answer(owner = pointer(12), type = 5, rdata = validName),
            answer(owner = pointer(12), type = 12, rdata = validName),
            answer(
                owner = pointer(12),
                type = 15,
                rdata = byteArrayOf(0, 10) + validName,
            ),
            answer(
                owner = pointer(12),
                type = 6,
                rdata = validName + validName + fixedSoaFields,
            ),
        )

        validRecords.forEach { record ->
            assertTrue(
                ResponseValidation.validate(
                    query,
                    response(query, answers = listOf(record)),
                    ResolverTransport.DEVICE_TCP,
                ) is ResponseValidationResult.Valid,
            )
        }

        val aliasQuery = query("alias.example")
        val uncompressedAlias = aliasQuery.copyOfRange(
            12,
            aliasQuery.size - 4,
        )
        val cname = answer(
            owner = pointer(12),
            type = 5,
            rdata = uncompressedAlias,
        )
        val exampleSuffixOffset = firstRdataOffset + 1 + "alias".length
        assertTrue(
            ResponseValidation.validate(
                query,
                response(
                    query,
                    answers = listOf(
                        cname,
                        answer(owner = pointer(exampleSuffixOffset)),
                    ),
                ),
                ResolverTransport.DEVICE_TCP,
            ) is ResponseValidationResult.Valid,
        )
    }

    @Test
    fun `does not interpret opaque record data as compressed names`() {
        val query = query("example.com")
        val opaqueAddress = byteArrayOf(0xc0.toByte(), 0x04, 0, 1)

        assertTrue(
            ResponseValidation.validate(
                query,
                response(
                    query,
                    answers = listOf(
                        answer(owner = pointer(12), type = 1, rdata = opaqueAddress),
                    ),
                ),
                ResolverTransport.DEVICE_TCP,
            ) is ResponseValidationResult.Valid,
        )
    }

    @Test
    fun `DNAME registers an uncompressed target but rejects compressed target data`() {
        val query = query("example.com")
        val targetQuery = query("target.example")
        val uncompressedTarget = targetQuery.copyOfRange(12, targetQuery.size - 4)
        val dnameRdataOffset = query.size + pointer(12).size + 10
        val dname = answer(
            owner = pointer(12),
            type = 39,
            rdata = uncompressedTarget,
        )

        assertTrue(
            ResponseValidation.validate(
                query,
                response(
                    query,
                    answers = listOf(
                        dname,
                        answer(
                            owner = pointer(12),
                            type = 5,
                            rdata = pointer(dnameRdataOffset),
                        ),
                    ),
                ),
                ResolverTransport.DEVICE_TCP,
            ) is ResponseValidationResult.Valid,
        )

        assertInvalid(
            query,
            response(
                query,
                answers = listOf(
                    answer(owner = pointer(12), type = 39, rdata = pointer(12)),
                ),
            ),
            ResolverTransport.DEVICE_TCP,
        )
        assertInvalid(
            query,
            response(
                query,
                answers = listOf(
                    answer(
                        owner = pointer(12),
                        type = 39,
                        rdata = uncompressedTarget + byteArrayOf(0),
                    ),
                ),
            ),
            ResolverTransport.DEVICE_TCP,
        )
    }

    @Test
    fun `accepts one root OPT only in additional and rejects OPT anomalies`() {
        val query = query("example.com")
        val validOpt = opt(byteArrayOf(0, 12, 0, 2, 1, 2))

        assertTrue(
            ResponseValidation.validate(
                query,
                response(query, additionals = listOf(validOpt)),
                ResolverTransport.DEVICE_TCP,
            ) is ResponseValidationResult.Valid,
        )

        listOf(
            response(
                query,
                additionals = listOf(
                    answer(
                        owner = pointer(12),
                        type = 41,
                        rdata = byteArrayOf(),
                    ),
                ),
            ),
            response(query, additionals = listOf(validOpt, validOpt)),
            response(query, answers = listOf(validOpt)),
            response(query, authorities = listOf(validOpt)),
            response(query, additionals = listOf(opt(byteArrayOf(0, 12, 0, 3, 1, 2)))),
        ).forEach { invalidOptResponse ->
            assertInvalid(query, invalidOptResponse, ResolverTransport.DEVICE_TCP)
        }
    }

    @Test
    fun `accepts truncation only for UDP`() {
        val query = query("example.com")
        val truncated = response(query).also { it[2] = (it[2].toInt() or 0x02).toByte() }

        val udp = ResponseValidation.validate(
            query,
            truncated,
            ResolverTransport.DEVICE_UDP,
            DatagramSource(server, 53),
            server,
        )

        assertTrue(udp is ResponseValidationResult.Valid)
        assertTrue((udp as ResponseValidationResult.Valid).truncated)
        assertInvalid(query, truncated, ResolverTransport.DEVICE_TCP)
        assertInvalid(query, truncated, ResolverTransport.MULLVAD_DOH)
    }

    @Test
    fun `enforces the uniform 4096 byte response bound`() {
        val query = query("example.com")
        val optOverhead = 11
        val padding = ByteArray(4_096 - query.size - optOverhead)
        val exactly4096 = response(query, additionals = listOf(opt(padding)))

        assertEquals(4_096, exactly4096.size)
        assertTrue(
            ResponseValidation.validate(
                query,
                exactly4096,
                ResolverTransport.DEVICE_TCP,
            ) is ResponseValidationResult.Valid,
        )
        val oversized = ResponseValidation.validate(
            query,
            exactly4096 + byteArrayOf(0),
            ResolverTransport.DEVICE_TCP,
        )
        assertEquals(
            ResponseValidationResult.Invalid(ResolverFailureReason.OVERSIZED_RESPONSE),
            oversized,
        )
        assertInvalid(query, ByteArray(11), ResolverTransport.DEVICE_TCP)
        assertInvalid(ByteArray(4_097), response(query), ResolverTransport.DEVICE_TCP)
    }

    private fun assertInvalid(
        query: ByteArray,
        response: ByteArray,
        transport: ResolverTransport,
        source: DatagramSource? = DatagramSource(server, 53),
        expectedServer: InetAddress? = server,
    ) {
        val result = ResponseValidation.validate(
            query,
            response,
            transport,
            source,
            expectedServer,
        )
        assertTrue("expected invalid, got $result", result is ResponseValidationResult.Invalid)
    }

    private fun query(domain: String, id: Int = 0x1234): ByteArray {
        val labels = domain.split('.').flatMap { label ->
            listOf(label.length.toByte()) + label.encodeToByteArray().toList()
        }
        return query(labels.toByteArray() + byteArrayOf(0), id)
    }

    private fun query(wireName: ByteArray, id: Int = 0x1234): ByteArray {
        return ByteArray(12 + wireName.size + 4).also {
            write16(it, 0, id)
            write16(it, 2, 0x0100)
            write16(it, 4, 1)
            wireName.copyInto(it, 12)
            val end = 12 + wireName.size
            write16(it, end, 1)
            write16(it, end + 2, 1)
        }
    }

    private fun wireName(vararg labelLengths: Int): ByteArray =
        labelLengths.flatMapIndexed { index, length ->
            listOf(length.toByte()) +
                List(length) { ('a'.code + index).toByte() }
        }.toByteArray() + byteArrayOf(0)

    private fun response(
        query: ByteArray,
        answers: List<ByteArray> = emptyList(),
        authorities: List<ByteArray> = emptyList(),
        additionals: List<ByteArray> = emptyList(),
    ): ByteArray {
        val records = answers + authorities + additionals
        return ByteArray(query.size + records.sumOf(ByteArray::size)).also { result ->
            query.copyInto(result)
            write16(result, 2, 0x8180)
            write16(result, 6, answers.size)
            write16(result, 8, authorities.size)
            write16(result, 10, additionals.size)
            var cursor = query.size
            records.forEach { record ->
                record.copyInto(result, cursor)
                cursor += record.size
            }
        }
    }

    private fun answer(
        owner: ByteArray,
        type: Int = 1,
        rdata: ByteArray = byteArrayOf(1, 2, 3, 4),
    ): ByteArray = ByteArray(owner.size + 10 + rdata.size).also {
        owner.copyInto(it)
        val cursor = owner.size
        write16(it, cursor, type)
        write16(it, cursor + 2, 1)
        write32(it, cursor + 4, 60)
        write16(it, cursor + 8, rdata.size)
        rdata.copyInto(it, cursor + 10)
    }

    private fun pointer(offset: Int): ByteArray =
        byteArrayOf(
            (0xc0 or (offset ushr 8)).toByte(),
            offset.toByte(),
        )

    private fun opt(rdata: ByteArray): ByteArray = ByteArray(11 + rdata.size).also {
        it[0] = 0
        write16(it, 1, 41)
        write16(it, 3, 1_232)
        write32(it, 5, 0)
        write16(it, 9, rdata.size)
        rdata.copyInto(it, 11)
    }

    private fun write16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun write32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
