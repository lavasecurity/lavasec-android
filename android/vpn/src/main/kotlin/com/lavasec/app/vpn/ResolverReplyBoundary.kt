package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.DnsMessageException
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport

/** Converts a validated resolver answer into the bounded local DNS reply contract. */
internal object ResolverReplyBoundary {
    private const val DNS_HEADER_BYTES = 12
    private const val TRUNCATION_FLAG = 0x0200

    fun validateRequest(request: ResolverRequest): ResolverResult.Failure? =
        if (validatedQuestionBytes(request) == null) invalidResponse() else null

    /** Validates the complete upstream message without changing facts needed by tier selection. */
    fun validate(
        request: ResolverRequest,
        expectedResolver: ResolverKind,
        answer: ResolverResult.Answer,
    ): ResolverResult {
        validatedQuestionBytes(request) ?: return invalidResponse()
        if (!transportMatches(expectedResolver, answer.transport)) return invalidResponse()
        return responseFailure(request.query, answer.dnsMessage) ?: answer
    }

    /** Applies the local TUN size limit only after the complete upstream answer was selected. */
    fun prepare(
        request: ResolverRequest,
        expectedResolver: ResolverKind,
        answer: ResolverResult.Answer,
    ): ResolverResult {
        val validated = validate(request, expectedResolver, answer)
        if (validated !is ResolverResult.Answer) return validated
        val questionBytes = validatedQuestionBytes(request) ?: return invalidResponse()
        return ResolverResult.Answer(
            dnsMessage = boundedResponse(request.query, questionBytes, validated.dnsMessage),
            transport = validated.transport,
            healthEvents = validated.healthEvents,
            resolverQueryEvidence = validated.resolverQueryEvidence,
            httpProtocol = validated.httpProtocol,
        )
    }

    /** Final delivery seam shared by DNS-only and chained owners; needs no network identity. */
    fun boundForClient(query: ByteArray, response: ByteArray): ByteArray? {
        val questionBytes = try { DnsMessage.parseQuestion(query).questionBytes(query) }
            catch (_: DnsMessageException) { return null }
        if (responseFailure(query, response) != null) return null
        return boundedResponse(query, questionBytes, response)
    }

    private fun responseFailure(query: ByteArray, response: ByteArray): ResolverResult.Failure? {
        if (response.size < DNS_HEADER_BYTES) return invalidResponse()
        val meaning = DnsFallbackResponse.classify(query, response).kind
        if (meaning == DnsFallbackResponse.Kind.PROHIBITED && DnsResponseWireWalker.walk(response) == null)
            return ResolverResult.Failure(ResolverFailureReason.INVALID_RESPONSE, terminal = true, peerAnswered = true)
        return when (meaning) {
            DnsFallbackResponse.Kind.MALFORMED, DnsFallbackResponse.Kind.TRUNCATED -> invalidResponse()
            DnsFallbackResponse.Kind.MISMATCHED -> ResolverResult.Failure(ResolverFailureReason.MISMATCH_LIMIT, terminal = true)
            else -> null
        }
    }

    private fun boundedResponse(query: ByteArray, questionBytes: ByteArray, response: ByteArray): ByteArray =
        (if (response.size <= DnsOnlyTunContract.MAX_RESPONSE_DNS_BYTES) response.copyOf()
        else questionOnlyResponse(response, questionBytes)).also { prepared ->
            query.copyInto(prepared, destinationOffset = 0, startIndex = 0, endIndex = 2)
        }

    private fun validatedQuestionBytes(request: ResolverRequest): ByteArray? = try {
        request.question.questionBytes(request.query)
    } catch (_: DnsMessageException) {
        null
    }

    private fun questionOnlyResponse(source: ByteArray, questionBytes: ByteArray): ByteArray =
        ByteArray(DNS_HEADER_BYTES + questionBytes.size).also { bounded ->
            source.copyInto(bounded, startIndex = 0, endIndex = DNS_HEADER_BYTES)
            write16(bounded, 2, read16(source, 2) or TRUNCATION_FLAG)
            write16(bounded, 4, 1)
            write16(bounded, 6, 0)
            write16(bounded, 8, 0)
            write16(bounded, 10, 0)
            questionBytes.copyInto(bounded, DNS_HEADER_BYTES)
        }

    private fun transportMatches(expectedResolver: ResolverKind, transport: ResolverTransport): Boolean =
        when (expectedResolver) {
            ResolverKind.DEVICE ->
                transport == ResolverTransport.DEVICE_UDP || transport == ResolverTransport.DEVICE_TCP

            ResolverKind.MULLVAD_DOH -> transport == ResolverTransport.MULLVAD_DOH ||
                transport == ResolverTransport.DOQ || transport == ResolverTransport.DOT
            ResolverKind.PLAIN_DNS -> transport == ResolverTransport.PLAIN_UDP || transport == ResolverTransport.PLAIN_TCP
            ResolverKind.WIREGUARD_T0 -> transport == ResolverTransport.WIREGUARD_UDP
        }

    private fun invalidResponse(): ResolverResult.Failure =
        ResolverResult.Failure(ResolverFailureReason.INVALID_RESPONSE)

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun write16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
