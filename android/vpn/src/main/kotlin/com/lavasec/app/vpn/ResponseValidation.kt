package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import java.net.InetAddress
/**
 * One strict, bounded DNS response validator shared by UDP, TCP, and DoH.
 *
 * `query` must be the exact wire message that the caller sent upstream. If a future transport
 * canonicalizes a captured query before sending it, that transport must pass the canonicalized
 * wire message here. This validator never canonicalizes on the caller's behalf.
 */
internal object ResponseValidation {
    private const val HEADER_BYTES = 12
    private const val MAX_MESSAGE_BYTES = 4_096
    private const val QR = 0x8000
    private const val TC = 0x0200
    private const val DNS_PORT = 53

    fun validate(
        query: ByteArray,
        response: ByteArray,
        transport: ResolverTransport,
        datagramSource: DatagramSource? = null,
        expectedServer: InetAddress? = null,
        expectedPort: Int = DNS_PORT,
    ): ResponseValidationResult {
        if (response.size > MAX_MESSAGE_BYTES) {
            return invalid(ResolverFailureReason.OVERSIZED_RESPONSE)
        }
        if (query.size !in HEADER_BYTES..MAX_MESSAGE_BYTES || response.size < HEADER_BYTES) {
            return invalid()
        }
        if (transport == ResolverTransport.DEVICE_UDP || transport == ResolverTransport.PLAIN_UDP) {
            if (
                datagramSource == null ||
                expectedServer == null ||
                datagramSource.port != expectedPort ||
                !datagramSource.address.address.contentEquals(expectedServer.address)
            ) {
                return invalid()
            }
        }

        val queryFlags = read16(query, 2)
        val responseFlags = read16(response, 2)
        if (queryFlags and QR != 0 || responseFlags and QR == 0) return invalid(ResolverFailureReason.MISMATCH_LIMIT)
        if (opcode(queryFlags) != opcode(responseFlags)) return invalid(ResolverFailureReason.MISMATCH_LIMIT)
        if (read16(query, 4) != 1 || read16(response, 4) != 1) return invalid()
        if (
            transport != ResolverTransport.MULLVAD_DOH &&
            read16(query, 0) != read16(response, 0)
        ) {
            return invalid(ResolverFailureReason.MISMATCH_LIMIT)
        }

        val queryQuestionEnd =
            DnsResponseWireWalker.uncompressedQuestionEnd(query) ?: return invalid()
        val responseQuestionEnd = DnsResponseWireWalker.uncompressedQuestionEnd(response) ?: return invalid()
        if (queryQuestionEnd != responseQuestionEnd) return invalid()
        // Byte-exact question echo is deliberate. In addition to keeping QTYPE and QCLASS exact,
        // preserving QNAME case retains the request's 0x20 anti-forgery entropy. An upstream that
        // rewrites the actually-forwarded question case therefore fails closed instead of
        // weakening response identity.
        for (index in HEADER_BYTES until queryQuestionEnd) {
            if (query[index] != response[index]) return invalid(ResolverFailureReason.MISMATCH_LIMIT)
        }

        val truncated = responseFlags and TC != 0
        if (truncated && transport != ResolverTransport.DEVICE_UDP &&
            transport != ResolverTransport.PLAIN_UDP && transport != ResolverTransport.WIREGUARD_UDP) return invalid()
        if (!truncated && DnsResponseWireWalker.walk(response) == null) {
            val policyResponse = if (transport == ResolverTransport.MULLVAD_DOH) response.copyOf().also {
                it[0] = query[0]; it[1] = query[1]
            } else response
            val policy = DnsFallbackResponse.classify(query, policyResponse).kind == DnsFallbackResponse.Kind.PROHIBITED
            return ResponseValidationResult.Invalid(ResolverFailureReason.INVALID_RESPONSE, terminal = policy, peerAnswered = policy)
        }
        return ResponseValidationResult.Valid(response, truncated)
    }

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private fun opcode(flags: Int): Int = (flags ushr 11) and 0x0f

    private fun invalid(
        reason: ResolverFailureReason = ResolverFailureReason.INVALID_RESPONSE,
    ): ResponseValidationResult = ResponseValidationResult.Invalid(reason)
}

/**
 * Strict bounded DNS response traversal shared by validation and defensive wire transforms.
 */
internal object DnsResponseWireWalker {
    enum class Section {
        ANSWER,
        AUTHORITY,
        ADDITIONAL,
    }

    data class Record(
        val section: Section,
        val type: Int,
        val ttlOffset: Int,
        val ownerOffset: Int = 0,
        val rdataOffset: Int = 0,
        val endOffset: Int = 0,
        val recordClass: Int = 0,
    )

    data class Walk(
        val questionEnd: Int,
        val records: List<Record>,
    )

    fun walk(message: ByteArray, validateStructuredRdata: Boolean = true): Walk? {
        if (message.size < HEADER_BYTES || read16(message, QUESTION_COUNT_OFFSET) != 1) {
            return null
        }

        val encodedNameOffsets = HashSet<Int>()
        val questionEnd =
            uncompressedQuestionEnd(message, encodedNameOffsets) ?: return null
        val counts = intArrayOf(
            read16(message, ANSWER_COUNT_OFFSET),
            read16(message, AUTHORITY_COUNT_OFFSET),
            read16(message, ADDITIONAL_COUNT_OFFSET),
        )
        val recordCount = counts.sumOf(Int::toLong)
        if (recordCount > message.size.toLong()) return null

        val records = ArrayList<Record>(recordCount.toInt())
        var cursor = questionEnd
        var sawOpt = false
        Section.entries.forEachIndexed { index, section ->
            repeat(counts[index]) {
                val walked =
                    walkRecord(
                        message = message,
                        offset = cursor,
                        section = section,
                        encodedNameOffsets = encodedNameOffsets,
                        validateStructuredRdata = validateStructuredRdata,
                    ) ?: return null
                if (walked.record.type == OPT_RECORD_TYPE) {
                    if (sawOpt) return null
                    sawOpt = true
                }
                records += walked.record
                cursor = walked.end
            }
        }
        if (cursor != message.size) return null
        return Walk(questionEnd, records)
    }

    fun uncompressedQuestionEnd(
        message: ByteArray,
        encodedNameOffsets: MutableSet<Int>? = null,
    ): Int? {
        var cursor = HEADER_BYTES
        var expandedBytes = 0
        val questionNameOffsets = ArrayList<Int>()
        while (true) {
            if (cursor >= message.size) return null
            questionNameOffsets += cursor
            val length = message[cursor].toInt() and 0xff
            cursor += 1
            expandedBytes += 1
            if (expandedBytes > MAX_EXPANDED_NAME_BYTES) return null
            if (length == 0) break
            if (
                length and 0xc0 != 0 ||
                length > 63 ||
                cursor > message.size - length
            ) {
                return null
            }
            cursor += length
            expandedBytes += length
            if (expandedBytes > MAX_EXPANDED_NAME_BYTES) return null
        }
        if (cursor > message.size - 4) return null
        encodedNameOffsets?.addAll(questionNameOffsets)
        return cursor + 4
    }

    private fun walkRecord(
        message: ByteArray,
        offset: Int,
        section: Section,
        encodedNameOffsets: MutableSet<Int>,
        validateStructuredRdata: Boolean,
    ): WalkedRecord? {
        val afterName = skipName(message, offset, encodedNameOffsets) ?: return null
        if (afterName > message.size - 10) return null
        val recordType = read16(message, afterName)
        val rdataLength = read16(message, afterName + 8)
        val rdataStart = afterName + 10
        val end =
            (rdataStart.toLong() + rdataLength.toLong())
                .takeIf { it <= message.size.toLong() }
                ?.toInt()
                ?: return null
        if (
            validateStructuredRdata && !hasWellFormedStructuredRdata(
                message = message,
                recordType = recordType,
                start = rdataStart,
                end = end,
                encodedNameOffsets = encodedNameOffsets,
            )
        ) {
            return null
        }
        if (recordType == OPT_RECORD_TYPE) {
            if (
                section != Section.ADDITIONAL ||
                afterName != offset + 1 ||
                message[offset].toInt() and 0xff != ROOT_LABEL ||
                !hasWellFormedOptRdata(message, rdataStart, end)
            ) {
                return null
            }
        }
        return WalkedRecord(
            end = end,
            record = Record(section, recordType, afterName + TTL_OFFSET_FROM_TYPE,
                offset, rdataStart, end, read16(message, afterName + 2)),
        )
    }

    /**
     * Returns the cursor after a name in its containing section.
     *
     * A compression pointer is accepted only when it targets a label boundary from a name that
     * has already been parsed. Merely pointing backward to bytes that happen to decode as a name
     * is not sufficient.
     */
    private fun skipName(
        message: ByteArray,
        start: Int,
        encodedNameOffsets: MutableSet<Int>,
    ): Int? {
        if (start !in message.indices) return null
        var cursor = start
        var containingEnd: Int? = null
        val visited = HashSet<Int>()
        val newlyEncodedNameOffsets = ArrayList<Int>()
        var expandedBytes = 0
        while (true) {
            if (cursor !in message.indices || !visited.add(cursor)) return null
            val length = message[cursor].toInt() and 0xff
            when {
                length == 0 -> {
                    expandedBytes += 1
                    if (expandedBytes > MAX_EXPANDED_NAME_BYTES) return null
                    if (containingEnd == null) newlyEncodedNameOffsets += cursor
                    encodedNameOffsets.addAll(newlyEncodedNameOffsets)
                    return containingEnd ?: cursor + 1
                }
                length and 0xc0 == 0xc0 -> {
                    if (cursor >= message.size - 1) return null
                    val target =
                        ((length and 0x3f) shl 8) or
                            (message[cursor + 1].toInt() and 0xff)
                    // RFC 1035 compression pointers reference a prior occurrence. Rejecting
                    // arbitrary prior bytes prevents header fields and opaque RDATA from being
                    // reinterpreted as names.
                    if (target >= cursor || target !in encodedNameOffsets) return null
                    if (containingEnd == null) {
                        newlyEncodedNameOffsets += cursor
                        containingEnd = cursor + 2
                    }
                    cursor = target
                }
                length and 0xc0 != 0 || length > 63 -> return null
                cursor + 1 > message.size - length -> return null
                else -> {
                    if (containingEnd == null) newlyEncodedNameOffsets += cursor
                    cursor += length + 1
                    expandedBytes += length + 1
                }
            }
            if (expandedBytes > MAX_EXPANDED_NAME_BYTES) return null
        }
    }

    /**
     * Validates domain-name fields in the explicitly supported RR layouts used by this boundary.
     *
     * Unknown and binary record data stays opaque; interpreting every `0xc0` byte as a pointer
     * would incorrectly reject valid address, key, text, and extension records.
     */
    private fun hasWellFormedStructuredRdata(
        message: ByteArray,
        recordType: Int,
        start: Int,
        end: Int,
        encodedNameOffsets: MutableSet<Int>,
    ): Boolean {
        fun nameEnd(offset: Int): Int? =
            skipName(message, offset, encodedNameOffsets)?.takeIf { it <= end }

        return when (recordType) {
            // NS, CNAME, PTR.
            2, 5, 12 ->
                nameEnd(start) == end

            // SOA: MNAME, RNAME, then five UInt32 fields.
            6 -> {
                val afterMname = nameEnd(start) ?: return false
                val afterRname = nameEnd(afterMname) ?: return false
                afterRname <= end - SOA_FIXED_BYTES &&
                    afterRname + SOA_FIXED_BYTES == end
            }

            // MX: UInt16 preference followed by an exchange name.
            15 ->
                start <= end - 2 && nameEnd(start + 2) == end

            // RFC 6672 requires the DNAME target to be present and uncompressed.
            39 ->
                uncompressedNameEnd(message, start, end, encodedNameOffsets) == end

            else -> true
        }
    }

    private fun hasWellFormedOptRdata(
        message: ByteArray,
        start: Int,
        end: Int,
    ): Boolean {
        var cursor = start
        while (cursor < end) {
            if (cursor > end - OPT_OPTION_HEADER_BYTES) return false
            val optionLength = read16(message, cursor + 2)
            cursor += OPT_OPTION_HEADER_BYTES
            if (cursor > end - optionLength) return false
            cursor += optionLength
        }
        return cursor == end
    }

    private fun uncompressedNameEnd(
        message: ByteArray,
        start: Int,
        end: Int,
        encodedNameOffsets: MutableSet<Int>,
    ): Int? {
        if (start !in message.indices || end > message.size || start >= end) return null
        var cursor = start
        var expandedBytes = 0
        val newOffsets = ArrayList<Int>()
        while (true) {
            if (cursor >= end) return null
            newOffsets += cursor
            val length = message[cursor].toInt() and 0xff
            cursor += 1
            expandedBytes += 1
            if (expandedBytes > MAX_EXPANDED_NAME_BYTES) return null
            if (length == 0) {
                if (cursor != end) return null
                encodedNameOffsets.addAll(newOffsets)
                return cursor
            }
            if (
                length and 0xc0 != 0 ||
                length > 63 ||
                cursor > end - length
            ) {
                return null
            }
            cursor += length
            expandedBytes += length
            if (expandedBytes > MAX_EXPANDED_NAME_BYTES) return null
        }
    }

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private data class WalkedRecord(
        val end: Int,
        val record: Record,
    )

    private const val HEADER_BYTES = 12
    private const val QUESTION_COUNT_OFFSET = 4
    private const val ANSWER_COUNT_OFFSET = 6
    private const val AUTHORITY_COUNT_OFFSET = 8
    private const val ADDITIONAL_COUNT_OFFSET = 10
    private const val TTL_OFFSET_FROM_TYPE = 4
    private const val OPT_RECORD_TYPE = 41
    private const val ROOT_LABEL = 0
    private const val OPT_OPTION_HEADER_BYTES = 4
    private const val MAX_EXPANDED_NAME_BYTES = 255
    private const val SOA_FIXED_BYTES = 20
}
