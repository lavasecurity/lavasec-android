package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ConfigurationIdentity
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResolverReplyBoundaryTest {
    @Test
    fun `accepts Device UDP TCP and Mullvad answers only for their resolver kinds`() {
        val request = request(query(id = 0x4321))

        listOf(
            ResolverTransport.DEVICE_UDP to ResolverKind.DEVICE,
            ResolverTransport.DEVICE_TCP to ResolverKind.DEVICE,
            ResolverTransport.MULLVAD_DOH to ResolverKind.MULLVAD_DOH,
            ResolverTransport.DOT to ResolverKind.MULLVAD_DOH,
            ResolverTransport.DOQ to ResolverKind.MULLVAD_DOH,
        ).forEach { (transport, kind) ->
            val result = ResolverReplyBoundary.prepare(request, kind, answer(transport, response(request.query, 64)))

            assertTrue("$transport should match $kind", result is ResolverResult.Answer)
        }
    }

    @Test
    fun `rejects a mismatched transport and a body shorter than a DNS header`() {
        val request = request(query())

        assertInvalid(
            ResolverReplyBoundary.prepare(
                request,
                ResolverKind.DEVICE,
                answer(ResolverTransport.MULLVAD_DOH, response(request.query, 64)),
            ),
        )
        assertInvalid(
            ResolverReplyBoundary.prepare(
                request,
                ResolverKind.MULLVAD_DOH,
                answer(ResolverTransport.DEVICE_TCP, response(request.query, 64)),
            ),
        )
        assertInvalid(
            ResolverReplyBoundary.prepare(
                request,
                ResolverKind.DEVICE,
                answer(ResolverTransport.DEVICE_UDP, ByteArray(11)),
            ),
        )
    }

    @Test
    fun `preserves validated caller ID in a fresh defensive answer copy`() {
        val request = request(query(id = 0x4321))
        val source = response(request.query, 64)
        val sourceSnapshot = source.copyOf()

        val result = ResolverReplyBoundary.prepare(
            request,
            ResolverKind.DEVICE,
            answer(ResolverTransport.DEVICE_UDP, source),
        )

        assertTrue(result is ResolverResult.Answer)
        result as ResolverResult.Answer
        assertEquals(0x4321, read16(result.dnsMessage, 0))
        val exposed = result.dnsMessage
        exposed[0] = 0
        assertEquals(0x4321, read16(result.dnsMessage, 0))
        assertArrayEquals(sourceSnapshot, source)
    }

    @Test
    fun `rejects question provenance mismatch without throwing on both reply paths`() {
        val goodQuery = query(id = 0x4321)
        val mismatchedRequest = request(goodQuery, parsedQuery = query(id = 0x1234))

        assertInvalidRequest(mismatchedRequest)
        assertInvalid(
            ResolverReplyBoundary.prepare(
                mismatchedRequest,
                ResolverKind.DEVICE,
                answer(ResolverTransport.DEVICE_UDP, response(goodQuery, 64)),
            ),
        )
        assertInvalid(
            ResolverReplyBoundary.prepare(
                mismatchedRequest,
                ResolverKind.DEVICE,
                answer(ResolverTransport.DEVICE_UDP, response(goodQuery, 1_253)),
            ),
        )
    }

    @Test
    fun `passes through an exact 1252 byte answer`() {
        val request = request(query())
        val source = response(request.query, DnsOnlyTunContract.MAX_RESPONSE_DNS_BYTES)

        val result = ResolverReplyBoundary.prepare(
            request,
            ResolverKind.DEVICE,
            answer(ResolverTransport.DEVICE_TCP, source),
        )

        assertTrue(result is ResolverResult.Answer)
        result as ResolverResult.Answer
        assertEquals(DnsOnlyTunContract.MAX_RESPONSE_DNS_BYTES, result.dnsMessage.size)
        assertEquals(read16(source, 2), read16(result.dnsMessage, 2))
        assertEquals(1, read16(result.dnsMessage, 6))
    }

    @Test
    fun `bounds 1253 and 4096 byte answers to question only replies`() {
        val request = request(query())

        listOf(1_253, 4_096).forEach { size ->
            val source = response(request.query, size)
            val result = ResolverReplyBoundary.prepare(
                request,
                ResolverKind.MULLVAD_DOH,
                answer(ResolverTransport.MULLVAD_DOH, source),
            )

            assertTrue("$size byte answer should be bounded", result is ResolverResult.Answer)
            assertBoundedQuestionOnlyReply(request, result as ResolverResult.Answer, source)
        }
    }

    @Test
    fun `bounded reply retains flags sets TC and leaves upstream source unchanged`() {
        val request = request(query())
        val source = response(request.query, 4_096).also { write16(it, 2, 0x81a3) }
        val sourceSnapshot = source.copyOf()
        val accepted = answer(ResolverTransport.DEVICE_UDP, source)

        val result = ResolverReplyBoundary.prepare(
            request,
            ResolverKind.DEVICE,
            accepted,
        )

        assertTrue(result is ResolverResult.Answer)
        assertBoundedQuestionOnlyReply(request, result as ResolverResult.Answer, source)
        assertArrayEquals(sourceSnapshot, source)
        assertArrayEquals(sourceSnapshot, accepted.dnsMessage)
    }

    @Test
    fun `semantic validation retains full large reply until delivery helper caps it`() {
        val request = request(query())
        val source = response(request.query, 4_096)
        val original = answer(ResolverTransport.DEVICE_TCP, source)
        val validated = ResolverReplyBoundary.validate(request, ResolverKind.DEVICE, original)
        assertSame(original, validated)
        assertArrayEquals(source, (validated as ResolverResult.Answer).dnsMessage)
        val delivered = checkNotNull(ResolverReplyBoundary.boundForClient(request.query, validated.dnsMessage))
        assertTrue(delivered.readUInt16(2) and 0x0200 != 0)
        assertEquals(request.query.size, delivered.size)
        assertEquals(4_096, validated.dnsMessage.size)
        assertEquals(0, validated.dnsMessage.readUInt16(2) and 0x0200)
    }

    private fun assertBoundedQuestionOnlyReply(
        request: ResolverRequest,
        result: ResolverResult.Answer,
        source: ByteArray,
    ) {
        val bytes = result.dnsMessage
        val question = request.question.questionBytes(request.query)

        assertTrue(bytes.size <= DnsOnlyTunContract.MAX_RESPONSE_DNS_BYTES)
        assertEquals(12 + question.size, bytes.size)
        assertEquals(read16(request.query, 0), read16(bytes, 0))
        assertEquals(read16(source, 2) or 0x0200, read16(bytes, 2))
        assertEquals(1, read16(bytes, 4))
        assertEquals(0, read16(bytes, 6))
        assertEquals(0, read16(bytes, 8))
        assertEquals(0, read16(bytes, 10))
        assertArrayEquals(question, bytes.copyOfRange(12, bytes.size))
        assertFalse(bytes.contentEquals(source))
    }

    private fun assertInvalidRequest(request: ResolverRequest) {
        val failure = ResolverReplyBoundary.validateRequest(request)
        assertTrue("expected invalid request but was $failure", failure is ResolverResult.Failure)
        assertEquals(ResolverFailureReason.INVALID_RESPONSE, failure?.reason)
    }

    private fun assertInvalid(result: ResolverResult) {
        assertTrue("expected invalid response but was $result", result is ResolverResult.Failure)
        assertEquals(ResolverFailureReason.INVALID_RESPONSE, (result as ResolverResult.Failure).reason)
    }

    private fun request(query: ByteArray, parsedQuery: ByteArray = query): ResolverRequest =
        ResolverRequest(
            query = query,
            question = DnsMessage.parseQuestion(parsedQuery),
            epoch = EngineEpoch(
                sessionGeneration = 1,
                configurationIdentity = ConfigurationIdentity(1, "cfg"),
                artifactDigest = "artifact",
                policyRevision = 1,
                networkGeneration = 1,
                engineGeneration = 1,
                primaryResolverIdentity = PrimaryResolverIdentity("device-dns"),
                fallbackCoverage = ResolverFallbackCoverage.NONE,
            ),
            networkContext = NetworkContext(
                network = network(42),
                networkGeneration = 1,
                dnsServers = listOf(InetAddress.getByName("192.0.2.53")),
                isCellular = false,
            ),
        )

    private fun answer(transport: ResolverTransport, body: ByteArray): ResolverResult.Answer =
        ResolverResult.Answer(body, transport)

    private fun query(id: Int = 0x1234): ByteArray = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01,
    )

    private fun response(query: ByteArray, size: Int): ByteArray {
        require(size >= query.size + 12)
        return ByteArray(size).also { result ->
            query.copyInto(result)
            write16(result, 2, 0x8180)
            write16(result, 6, 1)
            result[query.size] = 0xc0.toByte()
            result[query.size + 1] = 0x0c
            write16(result, query.size + 2, 65_280)
            write16(result, query.size + 4, 1)
            write16(result, query.size + 10, size - query.size - 12)
        }
    }

    private fun network(handle: Int): Network =
        Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(handle)

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun write16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
