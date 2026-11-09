package com.lavasec.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsEdns0Test {
    private val plain = byteArrayOf(
        0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        1, 97, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1,
    )

    @Test fun `cap changes only oversized OPT class and leaves source unchanged`() {
        val options = byteArrayOf(0xfd.toByte(), 0xe9.toByte(), 0, 2, 3, 4)
        val query = query(4096, options)
        val original = query.copyOf()
        val capped = DnsEdns0.capTunnelPayload(query)
        val expected = query.copyOf().also {
            it[plain.size + 3] = 4
            it[plain.size + 4] = 0xd0.toByte()
        }
        assertArrayEquals(expected, capped)
        assertArrayEquals(original, query)
    }

    @Test fun `classic and smaller client payloads never grow`() {
        assertArrayEquals(plain, DnsEdns0.capTunnelPayload(plain))
        for (size in listOf(0, 512, 1000, 1232)) {
            val query = query(size)
            assertArrayEquals(query, DnsEdns0.capTunnelPayload(query))
        }
    }

    @Test fun `authenticated messages are never rewritten`() {
        for (type in listOf(24, 250)) {
            val signed = (query(4096) + byteArrayOf(0, 0, type.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
                .also { it[11] = 2 }
            assertArrayEquals(signed, DnsEdns0.capTunnelPayload(signed))
        }
    }

    @Test fun `ambiguous damaged or non-query messages are not rewritten`() {
        val duplicate = (query(4096) + query(4096).copyOfRange(plain.size, query(4096).size))
            .also { it[11] = 2 }
        val malformedOption = query(4096, byteArrayOf(1))
        for (bytes in listOf(duplicate, query(4096).dropLast(1).toByteArray(),
            query(4096) + byteArrayOf(1), malformedOption, response(query(4096)))) {
            assertArrayEquals(bytes, DnsEdns0.capTunnelPayload(bytes))
        }
        assertNull(DnsEdns0.fullResponseCode(response(duplicate)))
    }

    @Test fun `extended response codes include OPT high bits`() {
        val badVersion = response(query(1232)).also { it[plain.size + 5] = 1 }
        assertEquals(16, DnsEdns0.fullResponseCode(badVersion))
        badVersion[3] = 3
        assertEquals(19, DnsEdns0.fullResponseCode(badVersion))
        assertEquals(3, DnsEdns0.fullResponseCode(response(plain).also { it[3] = 3 }))
        assertNull(DnsEdns0.fullResponseCode(plain))
        assertNull(DnsEdns0.fullResponseCode(badVersion + byteArrayOf(0)))
    }

    private fun query(size: Int, options: ByteArray = ByteArray(0)): ByteArray =
        (plain + byteArrayOf(0, 0, 41, (size ushr 8).toByte(), size.toByte(),
            0, 0, 0x80.toByte(), 0, (options.size ushr 8).toByte(), options.size.toByte()) + options)
            .also { it[11] = 1 }

    private fun response(query: ByteArray) = query.copyOf().also { it[2] = 0x81.toByte() }
}
