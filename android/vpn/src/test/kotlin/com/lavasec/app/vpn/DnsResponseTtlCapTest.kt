package com.lavasec.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class DnsResponseTtlCapTest {
    @Test
    fun `no records returns an equal defensive copy`() {
        val message = response()

        val capped = DnsResponseTtlCap.capCacheableTtls(message, 1)

        assertArrayEquals(message, capped)
        assertNotSame(message, capped)
    }

    @Test
    fun `caps cacheable TTLs in every record section and preserves zero and one`() {
        val message = response(
            answers = listOf(
                record(ttl = 60),
                record(ttl = 1),
            ),
            authorities = listOf(record(ttl = 4_294_967_295L)),
            additionals = listOf(
                record(ttl = 2),
                record(ttl = 0),
            ),
        )
        val original = message.copyOf()

        val capped = DnsResponseTtlCap.capCacheableTtls(message, 1)!!

        assertArrayEquals(original, message)
        assertEquals(listOf(1L, 1L, 1L, 1L, 0L), recordTtls(capped))
    }

    @Test
    fun `preserves a valid root OPT bit for bit and never treats its fields as a TTL`() {
        val ordinary = record(ttl = 60)
        val opt = opt(
            extendedFields = 0xfedc_ba98L,
            options = byteArrayOf(0, 12, 0, 2, 1, 2),
        )
        val message = response(
            answers = listOf(ordinary),
            additionals = listOf(opt),
        )
        val optStart = message.size - opt.size

        val capped = DnsResponseTtlCap.capCacheableTtls(message, 1)!!

        assertEquals(1L, recordTtls(capped).first())
        assertArrayEquals(opt, capped.copyOfRange(optStart, capped.size))
    }

    @Test
    fun `rejects non-root duplicate misplaced and malformed OPT without mutating input`() {
        val malformedOptions = byteArrayOf(0, 12, 0, 3, 1, 2)
        val candidates = listOf(
            response(additionals = listOf(opt(owner = pointer(12)))),
            response(additionals = listOf(opt(), opt())),
            response(answers = listOf(opt())),
            response(authorities = listOf(opt())),
            response(additionals = listOf(opt(options = malformedOptions))),
            response(additionals = listOf(opt())).copyOf().also {
                write16(it, it.size - 2, 100)
            },
        )

        candidates.forEach { candidate ->
            val original = candidate.copyOf()

            assertNull(DnsResponseTtlCap.capCacheableTtls(candidate, 1))
            assertArrayEquals(original, candidate)
        }
    }

    @Test
    fun `walks compressed owners without rewriting their targets`() {
        val answer = record(owner = pointer(12), ttl = 60)
        val message = response(answers = listOf(answer))
        val ttlOffset = question().size + pointer(12).size + 4
        val expected = message.copyOf().also { write32(it, ttlOffset, 1) }

        val capped = DnsResponseTtlCap.capCacheableTtls(message, 1)

        assertArrayEquals(expected, capped)
        assertArrayEquals(pointer(12), capped!!.copyOfRange(question().size, question().size + 2))
    }

    @Test
    fun `requires exactly one complete declared question`() {
        val candidates = listOf(
            response().also { write16(it, 4, 0) },
            response().also { write16(it, 4, 2) },
            response().copyOf(11),
            response().copyOf(question().size - 1),
        )

        candidates.forEach { candidate ->
            assertNull(DnsResponseTtlCap.capCacheableTtls(candidate, 1))
        }
    }

    @Test
    fun `rejects unsafe compression pointers and structured RDATA names`() {
        val recordOffset = question().size
        val selfPointer = pointer(recordOffset)
        val forwardTarget = recordOffset + 2
        val forwardPointer = pointer(forwardTarget)
        val outOfRangePointer = byteArrayOf(0xff.toByte(), 0xff.toByte())
        val secondRecordOffset = recordOffset + record().size
        val multiPointerLoop =
            response(
                answers = listOf(
                    record(owner = pointer(secondRecordOffset)),
                    record(owner = pointer(recordOffset)),
                ),
            )
        val cnameRdataOffset = recordOffset + pointer(12).size + 10
        val malformedCname =
            response(
                answers = listOf(
                    record(
                        owner = pointer(12),
                        type = 5,
                        rdata = pointer(cnameRdataOffset),
                    ),
                ),
            )
        val candidates = listOf(
            response(answers = listOf(record(owner = selfPointer))),
            response(answers = listOf(record(owner = forwardPointer))),
            response(answers = listOf(record(owner = outOfRangePointer))),
            multiPointerLoop,
            malformedCname,
        )

        candidates.forEach { candidate ->
            assertNull(DnsResponseTtlCap.capCacheableTtls(candidate, 1))
        }
    }

    @Test
    fun `rejects malformed names section overruns RDATA overruns and trailing bytes`() {
        val valid = response(answers = listOf(record(ttl = 60)))
        val answerOffset = question().size
        val truncatedOwner =
            response(answers = listOf(byteArrayOf(3, 'a'.code.toByte())))
        val invalidLabelTag =
            response(answers = listOf(byteArrayOf(0x40, 0)))
        val countOverrun = response().also { write16(it, 6, 1) }
        val rdataOverrun = valid.copyOf().also {
            val rdataLengthOffset = answerOffset + pointer(12).size + 8
            write16(it, rdataLengthOffset, 100)
        }
        val candidates = listOf(
            truncatedOwner,
            invalidLabelTag,
            countOverrun,
            rdataOverrun,
            valid + byteArrayOf(0),
        )

        candidates.forEach { candidate ->
            val original = candidate.copyOf()

            assertNull(DnsResponseTtlCap.capCacheableTtls(candidate, 1))
            assertArrayEquals(original, candidate)
        }
    }

    @Test
    fun `validates the unsigned 32 bit maximum argument`() {
        val message = response(answers = listOf(record(ttl = 4_294_967_295L)))

        assertArrayEquals(
            message,
            DnsResponseTtlCap.capCacheableTtls(message, 4_294_967_295L),
        )
        assertNull(DnsResponseTtlCap.capCacheableTtls(message, -1))
        assertNull(DnsResponseTtlCap.capCacheableTtls(message, 4_294_967_296L))
        assertEquals(
            listOf(0L),
            recordTtls(DnsResponseTtlCap.capCacheableTtls(message, 0)!!),
        )
        assertEquals(listOf(4_294_967_295L), recordTtls(message))
    }

    private fun response(
        answers: List<ByteArray> = emptyList(),
        authorities: List<ByteArray> = emptyList(),
        additionals: List<ByteArray> = emptyList(),
    ): ByteArray {
        val query = question()
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

    private fun question(): ByteArray {
        val labels = byteArrayOf(
            7,
            'e'.code.toByte(),
            'x'.code.toByte(),
            'a'.code.toByte(),
            'm'.code.toByte(),
            'p'.code.toByte(),
            'l'.code.toByte(),
            'e'.code.toByte(),
            3,
            'c'.code.toByte(),
            'o'.code.toByte(),
            'm'.code.toByte(),
            0,
        )
        return ByteArray(12 + labels.size + 4).also {
            write16(it, 0, 0x1234)
            write16(it, 2, 0x0100)
            write16(it, 4, 1)
            labels.copyInto(it, 12)
            write16(it, 12 + labels.size, 1)
            write16(it, 14 + labels.size, 1)
        }
    }

    private fun record(
        owner: ByteArray = pointer(12),
        type: Int = 1,
        ttl: Long = 60,
        rdata: ByteArray = byteArrayOf(1, 2, 3, 4),
    ): ByteArray = ByteArray(owner.size + 10 + rdata.size).also {
        owner.copyInto(it)
        val cursor = owner.size
        write16(it, cursor, type)
        write16(it, cursor + 2, 1)
        write32(it, cursor + 4, ttl)
        write16(it, cursor + 8, rdata.size)
        rdata.copyInto(it, cursor + 10)
    }

    private fun opt(
        owner: ByteArray = byteArrayOf(0),
        extendedFields: Long = 0,
        options: ByteArray = byteArrayOf(),
    ): ByteArray = record(
        owner = owner,
        type = 41,
        ttl = extendedFields,
        rdata = options,
    )

    private fun recordTtls(message: ByteArray): List<Long> {
        var cursor = question().size
        return buildList {
            val count = read16(message, 6) + read16(message, 8) + read16(message, 10)
            repeat(count) {
                cursor += if (message[cursor].toInt() and 0xc0 == 0xc0) 2 else 1
                val type = read16(message, cursor)
                if (type != 41) add(read32(message, cursor + 4))
                val rdataLength = read16(message, cursor + 8)
                cursor += 10 + rdataLength
            }
        }
    }

    private fun pointer(offset: Int): ByteArray =
        byteArrayOf(
            (0xc0 or (offset ushr 8)).toByte(),
            offset.toByte(),
        )

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private fun read32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun write16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun write32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
