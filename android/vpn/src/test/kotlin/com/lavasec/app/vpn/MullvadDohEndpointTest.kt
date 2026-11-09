package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.DnsMessageError
import com.lavasec.app.foundation.dns.DnsMessageException
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MullvadDohEndpointTest {
    @Test
    fun `returns exact normalized Mullvad address answers with TTL 60`() {
        val cases = listOf(
            1 to byteArrayOf(194.toByte(), 242.toByte(), 2, 2),
            28 to "2a07e340000000000000000000000002".hexBytes(),
        )

        for ((rawType, expectedAddress) in cases) {
            val query = dnsQuery("DnS.MuLlVaD.NeT", rawType = rawType, transactionId = 0xbeef)
            val question = DnsMessage.parseQuestion(query)
            val response = MullvadDohEndpoint.bootstrapResponse(query, question)!!

            assertEquals(0xbeef, response.readUInt16(0))
            assertEquals(0x8180, response.readUInt16(2))
            assertEquals(1, response.readUInt16(4))
            assertEquals(1, response.readUInt16(6))
            assertArrayEquals(query.copyOfRange(12, query.size), response.copyOfRange(12, query.size))
            assertEquals(rawType, response.readUInt16(query.size + 2))
            assertEquals(1, response.readUInt16(query.size + 4))
            assertEquals(60L, response.readUInt32(query.size + 6))
            assertArrayEquals(expectedAddress, response.copyOfRange(response.size - expectedAddress.size, response.size))
        }
    }

    @Test
    fun `returns NOERROR NODATA for other query types`() {
        val query = dnsQuery("dns.mullvad.net", rawType = 16)
        val question = DnsMessage.parseQuestion(query)
        val response = MullvadDohEndpoint.bootstrapResponse(query, question)!!

        assertEquals(0x8180, response.readUInt16(2))
        assertEquals(1, response.readUInt16(4))
        assertEquals(0, response.readUInt16(6))
        assertEquals(query.size, response.size)
        assertArrayEquals(query.copyOfRange(12, query.size), response.copyOfRange(12, response.size))
    }

    @Test
    fun `returns no bootstrap response for every other normalized hostname`() {
        listOf(
            "dns.mullvad.net.evil.example",
            "mullvad.net",
            "example.com",
        ).forEach { domain ->
            val query = dnsQuery(domain, rawType = 1)

            assertNull(
                domain,
                MullvadDohEndpoint.bootstrapResponse(query, DnsMessage.parseQuestion(query)),
            )
        }
    }

    @Test
    fun `requires the exact query provenance of the parsed question`() {
        val query = dnsQuery("dns.mullvad.net", rawType = 1, transactionId = 0x1234)
        val staleQuery = dnsQuery("dns.mullvad.net", rawType = 28, transactionId = 0x5678)
        val question = DnsMessage.parseQuestion(query)

        val error = assertThrows(DnsMessageException::class.java) {
            MullvadDohEndpoint.bootstrapResponse(staleQuery, question)
        }

        assertEquals(DnsMessageError.MALFORMED_QUESTION, error.reason)
    }

    @Test
    fun `defensively owns query and every response including same-length NODATA`() {
        for (rawType in listOf(1, 16)) {
            val query = dnsQuery("dns.mullvad.net", rawType = rawType)
            val querySnapshot = query.copyOf()
            val response = MullvadDohEndpoint.bootstrapResponse(
                query,
                DnsMessage.parseQuestion(query),
            )!!
            val responseSnapshot = response.copyOf()

            assertArrayEquals(querySnapshot, query)
            assertNotSame(query, response)
            if (rawType == 16) assertEquals(query.size, response.size)

            query.fill(0)
            assertArrayEquals(responseSnapshot, response)

            val separateQuery = querySnapshot.copyOf()
            val separateResponse = MullvadDohEndpoint.bootstrapResponse(
                separateQuery,
                DnsMessage.parseQuestion(separateQuery),
            )!!
            assertNotSame(response, separateResponse)
            assertArrayEquals(responseSnapshot, separateResponse)

            response.fill(0)
            assertArrayEquals(responseSnapshot, separateResponse)
        }
    }

    @Test
    fun `exposes the exact HTTPS endpoint and ordered bootstrap addresses`() {
        val addresses = MullvadDohEndpoint.lookup("dns.mullvad.net")

        assertEquals("https://dns.mullvad.net/dns-query", MullvadDohEndpoint.url.toString())
        assertEquals(listOf("194.242.2.2", "2a07:e340:0:0:0:0:0:2"), addresses.map { it.hostAddress })
    }

    @Test
    fun `returns a fresh immutable bootstrap snapshot for the endpoint hostname`() {
        val first = MullvadDohEndpoint.lookup("dns.mullvad.net")
        val second = MullvadDohEndpoint.lookup("dns.mullvad.net")

        assertNotSame(first, second)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (first as MutableList<*>).clear()
        }
        assertEquals(listOf("194.242.2.2", "2a07:e340:0:0:0:0:0:2"), second.map { it.hostAddress })
    }

    @Test
    fun `refuses every hostname other than the exact endpoint host`() {
        listOf(
            "dns.mullvad.net.",
            "DNS.MULLVAD.NET",
            "dns.mullvad.net.evil.example",
            "mullvad.net",
            "example.com",
        ).forEach { hostname ->
            assertThrows(UnknownHostException::class.java) {
                MullvadDohEndpoint.lookup(hostname)
            }
        }
    }

    private fun dnsQuery(domain: String, rawType: Int, transactionId: Int = 0x1234): ByteArray =
        ByteArrayOutputStream().apply {
            appendUInt16(transactionId)
            appendUInt16(0x0100)
            appendUInt16(1)
            appendUInt16(0)
            appendUInt16(0)
            appendUInt16(0)
            for (label in domain.split('.')) {
                val bytes = label.encodeToByteArray()
                write(bytes.size)
                write(bytes)
            }
            write(0)
            appendUInt16(rawType)
            appendUInt16(1)
        }.toByteArray()

    private fun ByteArrayOutputStream.appendUInt16(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArray.readUInt16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.readUInt32(offset: Int): Long =
        ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)

    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
