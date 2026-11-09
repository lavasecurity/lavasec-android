package com.lavasec.app.foundation.dns

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.domain.DomainValidationException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class DnsRecordType(val wireValue: Int) {
    A(1),
    TXT(16),
    AAAA(28),
    SRV(33),
    SVCB(64),
    HTTPS(65),
    UNKNOWN(0),
    ;

    companion object {
        fun fromWireValue(value: Int): DnsRecordType = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

enum class DnsMessageError {
    PACKET_TOO_SHORT,
    PACKET_TOO_LARGE,
    NOT_A_QUERY,
    UNSUPPORTED_OPCODE,
    NO_QUESTION,
    UNSUPPORTED_QUESTION_COUNT,
    UNEXPECTED_ANSWER_RECORDS,
    UNEXPECTED_AUTHORITY_RECORDS,
    MALFORMED_QUESTION,
    COMPRESSED_QUESTION_NAME,
    UNSUPPORTED_QUESTION_CLASS,
    INVALID_DOMAIN,
    MALFORMED_OPT_RECORD,
    DUPLICATE_OPT_RECORD,
    UNSUPPORTED_ADDITIONAL_RECORD,
    DECLARED_SECTION_OVERRUN,
    UNDECLARED_TRAILING_BYTES,
}

class DnsMessageException(val reason: DnsMessageError) : IllegalArgumentException(reason.name)

class DnsQuestion internal constructor(
    val transactionId: Int,
    val domain: String,
    val normalizedDomain: String,
    val recordType: DnsRecordType,
    val rawRecordType: Int,
    parsedQuery: ByteArray,
    encodedQuestion: ByteArray,
) {
    private val parsedQuery = parsedQuery.copyOf()
    private val encodedQuestion = encodedQuestion.copyOf()

    fun questionBytes(query: ByteArray): ByteArray {
        validateProvenance(query)
        return encodedQuestion.copyOf()
    }

    internal fun validatedQuestionBytes(query: ByteArray): ByteArray {
        validateProvenance(query)
        return encodedQuestion
    }

    private fun validateProvenance(query: ByteArray) {
        if (!query.contentEquals(parsedQuery)) throw DnsMessageException(DnsMessageError.MALFORMED_QUESTION)
    }
}

/** Bounded DNS query parser and fail-closed response synthesizer. */
object DnsMessage {
    const val HEADER_BYTES = 12
    const val MAX_MESSAGE_BYTES = 4096

    fun parseQuestion(data: ByteArray): DnsQuestion {
        if (data.size > MAX_MESSAGE_BYTES) reject(DnsMessageError.PACKET_TOO_LARGE)
        if (data.size < HEADER_BYTES) reject(DnsMessageError.PACKET_TOO_SHORT)
        val packet = data.copyOf()

        val transactionId = readUInt16(packet, 0)
        val flags = readUInt16(packet, 2)
        if (flags and RESPONSE_FLAG != 0) reject(DnsMessageError.NOT_A_QUERY)
        if (((flags ushr OPCODE_SHIFT) and OPCODE_MASK) != QUERY_OPCODE) {
            reject(DnsMessageError.UNSUPPORTED_OPCODE)
        }

        val questionCount = readUInt16(packet, 4)
        when (questionCount) {
            0 -> reject(DnsMessageError.NO_QUESTION)
            1 -> Unit
            else -> reject(DnsMessageError.UNSUPPORTED_QUESTION_COUNT)
        }
        if (readUInt16(packet, 6) != 0) reject(DnsMessageError.UNEXPECTED_ANSWER_RECORDS)
        if (readUInt16(packet, 8) != 0) reject(DnsMessageError.UNEXPECTED_AUTHORITY_RECORDS)
        val additionalCount = readUInt16(packet, 10)

        var cursor = HEADER_BYTES
        val questionNameStart = cursor
        val labels = ArrayList<String>()
        while (true) {
            if (cursor >= packet.size) reject(DnsMessageError.MALFORMED_QUESTION)
            val length = packet[cursor].toInt() and 0xff
            cursor++
            if (length == 0) break
            if (length and 0xc0 == 0xc0) reject(DnsMessageError.COMPRESSED_QUESTION_NAME)
            if (length > 63 || cursor > packet.size - length) reject(DnsMessageError.MALFORMED_QUESTION)
            val label = decodeUtf8Strict(packet, cursor, length)
                ?: reject(DnsMessageError.MALFORMED_QUESTION)
            labels += label
            cursor += length
        }
        if (cursor - questionNameStart > MAX_QUESTION_NAME_BYTES) {
            reject(DnsMessageError.MALFORMED_QUESTION)
        }
        if (cursor > packet.size - QUESTION_TRAILER_BYTES) reject(DnsMessageError.MALFORMED_QUESTION)

        val rawRecordType = readUInt16(packet, cursor)
        val rawClass = readUInt16(packet, cursor + 2)
        if (rawClass != INTERNET_CLASS) reject(DnsMessageError.UNSUPPORTED_QUESTION_CLASS)
        cursor += QUESTION_TRAILER_BYTES
        val domain = labels.joinToString(".")
        val normalized = try {
            DomainName.parse(domain).value
        } catch (_: DomainValidationException) {
            reject(DnsMessageError.INVALID_DOMAIN)
        }
        validateWireLabelNormalization(labels, normalized)
        val questionEnd = cursor

        var sawOpt = false
        repeat(additionalCount) {
            if (cursor >= packet.size) reject(DnsMessageError.DECLARED_SECTION_OVERRUN)
            if (packet[cursor].toInt() and 0xff != ROOT_LABEL) reject(DnsMessageError.MALFORMED_OPT_RECORD)
            cursor++
            if (cursor > packet.size - OPT_FIXED_BYTES_AFTER_OWNER) {
                reject(DnsMessageError.DECLARED_SECTION_OVERRUN)
            }
            val type = readUInt16(packet, cursor)
            cursor += 2
            if (type != OPT_RECORD_TYPE) reject(DnsMessageError.UNSUPPORTED_ADDITIONAL_RECORD)
            if (sawOpt) reject(DnsMessageError.DUPLICATE_OPT_RECORD)
            sawOpt = true
            cursor += OPT_CLASS_AND_TTL_BYTES
            val rdataLength = readUInt16(packet, cursor)
            cursor += 2
            if (cursor > packet.size - rdataLength) reject(DnsMessageError.DECLARED_SECTION_OVERRUN)
            cursor += rdataLength
        }
        if (cursor != packet.size) reject(DnsMessageError.UNDECLARED_TRAILING_BYTES)

        return DnsQuestion(
            transactionId = transactionId,
            domain = domain,
            normalizedDomain = normalized,
            recordType = DnsRecordType.fromWireValue(rawRecordType),
            rawRecordType = rawRecordType,
            parsedQuery = packet,
            encodedQuestion = packet.copyOfRange(HEADER_BYTES, questionEnd),
        )
    }

    fun blockedResponse(query: ByteArray, ttl: Long = 1): ByteArray =
        blockedResponse(query, parseQuestion(query), ttl)

    fun blockedResponse(query: ByteArray, question: DnsQuestion, ttl: Long = 1): ByteArray {
        if (query.size > MAX_MESSAGE_BYTES) reject(DnsMessageError.PACKET_TOO_LARGE)
        require(ttl in 0..0xffff_ffffL) { "TTL must fit an unsigned 32-bit DNS field" }
        val address = when (question.recordType) {
            DnsRecordType.A -> ByteArray(4)
            DnsRecordType.AAAA -> ByteArray(16)
            else -> null
        }
        return addressResponse(query, question, address, ttl)
    }

    fun bootstrapResponse(
        query: ByteArray,
        ipv4Address: ByteArray,
        ipv6Address: ByteArray,
        ttl: Long = 60,
    ): ByteArray = bootstrapResponse(query, parseQuestion(query), ipv4Address, ipv6Address, ttl)

    fun bootstrapResponse(
        query: ByteArray,
        question: DnsQuestion,
        ipv4Address: ByteArray,
        ipv6Address: ByteArray,
        ttl: Long = 60,
    ): ByteArray {
        if (query.size > MAX_MESSAGE_BYTES) reject(DnsMessageError.PACKET_TOO_LARGE)
        require(ipv4Address.size == IPV4_ADDRESS_BYTES) { "IPv4 bootstrap address must contain four bytes" }
        require(ipv6Address.size == IPV6_ADDRESS_BYTES) { "IPv6 bootstrap address must contain sixteen bytes" }
        require(ttl in 0..0xffff_ffffL) { "TTL must fit an unsigned 32-bit DNS field" }
        val address = when (question.recordType) {
            DnsRecordType.A -> ipv4Address
            DnsRecordType.AAAA -> ipv6Address
            else -> null
        }
        return addressResponse(query, question, address, ttl)
    }

    private fun addressResponse(
        query: ByteArray,
        question: DnsQuestion,
        address: ByteArray?,
        ttl: Long,
    ): ByteArray {
        val questionBytes = question.validatedQuestionBytes(query)
        val response = ByteArrayOutputStream(HEADER_BYTES + questionBytes.size + 28)
        response.appendUInt16(question.transactionId)
        response.appendUInt16(responseFlags(query, rcode = 0))
        response.appendUInt16(1)
        response.appendUInt16(if (address == null) 0 else 1)
        response.appendUInt16(0)
        response.appendUInt16(0)
        response.write(questionBytes)
        if (address != null) {
            response.write(byteArrayOf(0xc0.toByte(), 0x0c))
            response.appendUInt16(question.rawRecordType)
            response.appendUInt16(1)
            response.appendUInt32(ttl)
            response.appendUInt16(address.size)
            response.write(address)
        }
        return response.toByteArray().also { check(it.size <= MAX_MESSAGE_BYTES) }
    }

    /** Returns no response below a full header; malformed formable queries get header-only SERVFAIL. */
    fun servfailResponse(query: ByteArray): ByteArray? {
        if (query.size < HEADER_BYTES) return null
        val question = try {
            parseQuestion(query)
        } catch (_: DnsMessageException) {
            null
        }
        val questionBytes = question?.questionBytes(query)
        val response = ByteArrayOutputStream(HEADER_BYTES + (questionBytes?.size ?: 0))
        response.appendUInt16(readUInt16(query, 0))
        response.appendUInt16(responseFlags(query, rcode = 2))
        response.appendUInt16(if (questionBytes == null) 0 else 1)
        response.appendUInt16(0)
        response.appendUInt16(0)
        response.appendUInt16(0)
        if (questionBytes != null) response.write(questionBytes)
        return response.toByteArray()
    }

    private fun responseFlags(query: ByteArray, rcode: Int): Int =
        RESPONSE_FLAG or (readUInt16(query, 2) and RECURSION_DESIRED_FLAG) or RECURSION_AVAILABLE_FLAG or rcode

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun decodeUtf8Strict(data: ByteArray, offset: Int, length: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, offset, length))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun validateWireLabelNormalization(labels: List<String>, normalizedDomain: String) {
        val normalizedLabels = normalizedDomain.split('.')
        if (normalizedLabels.size != labels.size) reject(DnsMessageError.MALFORMED_QUESTION)

        for (index in labels.indices) {
            val contextualDomain = listOf(
                NORMALIZATION_SENTINEL_PREFIX,
                labels[index],
                NORMALIZATION_SENTINEL_SUFFIX,
            ).joinToString(".")
            val contextualLabels = try {
                DomainName.parse(contextualDomain).value.split('.')
            } catch (_: DomainValidationException) {
                reject(DnsMessageError.MALFORMED_QUESTION)
            }
            if (
                contextualLabels.size != 3 ||
                contextualLabels[0] != NORMALIZATION_SENTINEL_PREFIX ||
                contextualLabels[1] != normalizedLabels[index] ||
                contextualLabels[2] != NORMALIZATION_SENTINEL_SUFFIX
            ) {
                reject(DnsMessageError.MALFORMED_QUESTION)
            }
        }
    }

    private fun reject(reason: DnsMessageError): Nothing = throw DnsMessageException(reason)

    private fun ByteArrayOutputStream.appendUInt16(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.appendUInt32(value: Long) {
        write(((value ushr 24) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write((value and 0xff).toInt())
    }

    private const val RESPONSE_FLAG = 0x8000
    private const val RECURSION_DESIRED_FLAG = 0x0100
    private const val RECURSION_AVAILABLE_FLAG = 0x0080
    private const val OPCODE_SHIFT = 11
    private const val OPCODE_MASK = 0x0f
    private const val QUERY_OPCODE = 0
    private const val INTERNET_CLASS = 1
    private const val ROOT_LABEL = 0
    private const val OPT_RECORD_TYPE = 41
    private const val QUESTION_TRAILER_BYTES = 4
    private const val OPT_CLASS_AND_TTL_BYTES = 6
    private const val OPT_FIXED_BYTES_AFTER_OWNER = 10
    private const val MAX_QUESTION_NAME_BYTES = 255
    private const val NORMALIZATION_SENTINEL_PREFIX = "wire"
    private const val NORMALIZATION_SENTINEL_SUFFIX = "invalid"
    private const val IPV4_ADDRESS_BYTES = 4
    private const val IPV6_ADDRESS_BYTES = 16
}
