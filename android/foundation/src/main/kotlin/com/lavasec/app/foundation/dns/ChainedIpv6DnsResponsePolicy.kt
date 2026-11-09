package com.lavasec.app.foundation.dns

/**
 * Keeps DNS answers consistent with a full-tunnel chained data path that drops every outbound IPv6
 * packet.
 *
 * WHY THIS EXISTS (Android port of iOS `ChainedIPv6DNSPolicy` + `DNSServiceBinding`). A full-tunnel
 * chained upstream claims `::/0` and blackholes outbound IPv6 by design — a v6 packet must never
 * leak around the tunnel. If the resolver still advertises a v6 address, a dual-stack client
 * prefers IPv6 (RFC 6724), sends the packet into the blackhole, and stalls for ~15–30 s until
 * Happy-Eyeballs falls back to IPv4. A security filter that only classified IP traffic would miss
 * this: nothing is blocked, the connection just hangs.
 *
 * The policy makes DNS agree with the data path in the two shapes an upstream can advertise v6
 * reachability:
 *
 *  - an **AAAA** answer (TYPE 28) is replaced with NODATA — `NOERROR` with no answer records — so
 *    the client learns the name has no record of that type and uses `A` directly. This mirrors the
 *    iOS `DNSMessage.emptyResponse` synthesis; it is a *pre-upstream* decision, so the caller can
 *    skip the lookup entirely.
 *  - an **HTTPS** (TYPE 65) or **SVCB** (TYPE 64) answer can carry the same doomed address in its
 *    `ipv6hint` SvcParam (RFC 9460 §7.3). That hint is stripped from the *upstream response* by
 *    [stripIpv6Hints] — a surgical rewrite, not NODATA, because the record also carries ALPN,
 *    `ipv4hint`, port, and ECH that a v4-only site needs.
 *
 * Both transforms only *decline* a record; they never admit a blocked name. Filtering runs first at
 * the call site, so the fail-closed DNS invariant (`INV-DNS-1` on iOS) is preserved: the bottom of
 * the ladder is still a refusal, never a bypass.
 *
 * GATING. Both paths are gated on `fullTunnelChained`, the caller's statement that the active data
 * path drops outbound IPv6 (`::/0`). A split-tunnel chained upstream leaves IPv6 direct on the
 * physical interface where it works, and DNS-only mode never claims `::/0`; suppressing v6 there
 * would break working connectivity. This object deliberately takes a plain boolean rather than any
 * configuration type so the pure foundation layer does not depend on the chained-config model.
 *
 * The response walk is deliberately conservative: [stripIpv6Hints] returns the response
 * byte-for-byte unchanged whenever it cannot prove the rewrite is compression-safe. A malformed or
 * partially-understood record is never rewritten (fail-open on the *rewrite*), because corrupting
 * an answer is worse than forwarding an `ipv6hint` the data path will drop.
 */
object ChainedIpv6DnsResponsePolicy {
    /** Whether [recordType] asks for the IPv6 (AAAA) address record. */
    fun isIpv6AddressQuery(recordType: DnsRecordType): Boolean = recordType == DnsRecordType.AAAA

    /** Whether [recordType] asks for an HTTPS (TYPE 65) or SVCB (TYPE 64) service binding record. */
    fun isServiceBindingQuery(recordType: DnsRecordType): Boolean =
        recordType == DnsRecordType.HTTPS || recordType == DnsRecordType.SVCB

    /**
     * True when an AAAA query must be answered NODATA instead of forwarded, because the data path
     * drops outbound IPv6 and the answer would advertise a reachable-looking address for a dropped
     * path. Every other record type still forwards: `A` is the path we want the client on, and
     * HTTPS/SVCB take the separate [stripsIpv6Hint] + [stripIpv6Hints] path rather than NODATA.
     */
    fun answersWithNoData(fullTunnelChained: Boolean, recordType: DnsRecordType): Boolean =
        fullTunnelChained && isIpv6AddressQuery(recordType)

    /**
     * True when an HTTPS/SVCB upstream response must have its `ipv6hint` SvcParam stripped. Mirrors
     * [answersWithNoData] for the record types whose answer is rewritten rather than synthesized.
     */
    fun stripsIpv6Hint(fullTunnelChained: Boolean, recordType: DnsRecordType): Boolean =
        fullTunnelChained && isServiceBindingQuery(recordType)

    /**
     * Builds a NODATA response — a `NOERROR` response that echoes the question with **zero** answer
     * records — from a validated query. This is the honest "name exists, no record of that type"
     * answer; it is not *blocking*, and it does not claim the name failed to resolve. The caller
     * uses [answersWithNoData] to decide when an AAAA query takes this path.
     *
     * Only the question is echoed; the upstream's OPT/EDNS state is deliberately absent because
     * this is a locally synthesized answer, exactly as the iOS `DNSMessage.emptyResponse` behaves.
     * The transaction ID and the RD bit are preserved so the client can match the reply; the
     * response/RA flags are set and RCODE is zero, and unlike a rewrite of an upstream answer there
     * is no upstream AD bit to clear.
     *
     * @throws DnsMessageException when [query] is not a single well-formed IN query.
     */
    fun noDataResponse(query: ByteArray): ByteArray {
        val question = DnsMessage.parseQuestion(query)
        val questionBytes = question.questionBytes(query)
        val queryFlags = readUInt16(query, 2)
        val response = ByteArray(DnsMessage.HEADER_BYTES + questionBytes.size)
        writeUInt16(response, TRANSACTION_ID_OFFSET, question.transactionId)
        writeUInt16(
            response,
            FLAGS_OFFSET,
            RESPONSE_FLAG or (queryFlags and RECURSION_DESIRED_FLAG) or RECURSION_AVAILABLE_FLAG,
        )
        writeUInt16(response, QUESTION_COUNT_OFFSET, 1)
        questionBytes.copyInto(response, DnsMessage.HEADER_BYTES)
        return response
    }

    /**
     * `response` with the `ipv6hint` SvcParam removed from every HTTPS/SVCB resource record, or
     * `response` unchanged when [fullTunnelChained] is false, there is nothing to strip, or the
     * rewrite is not provably compression-safe.
     *
     * COMPRESSION SAFETY (the load-bearing constraint, ported from the iOS codec). Removing an
     * `ipv6hint` SvcParam shrinks its record's RDATA and shifts every later byte left. A DNS
     * compression pointer stores an ABSOLUTE offset and may only point BACKWARD (RFC 1035 §4.1.4),
     * so a pointer breaks iff it sits after a deletion and targets an offset at or after that
     * deletion. The rewrite is therefore applied only when it is provably offset-safe; otherwise the
     * response is returned byte-for-byte. The proof rests on two checks made in one pass:
     *
     *  1. Every resource record is one of {A, AAAA, CNAME, SVCB, HTTPS, OPT}. Only CNAME and the
     *     SVCB/HTTPS TargetName carry a name in RDATA; A/AAAA/OPT carry none. So every compression
     *     pointer lives in an owner name or a CNAME's RDATA — both of which this walk visits — and
     *     none can hide inside opaque RDATA this walk skips. Any other record type makes the walk
     *     bail conservatively (a type it cannot vouch for).
     *  2. Every pointer target found is strictly before the earliest deleted byte. A pointer whose
     *     target precedes every deletion still resolves to the same (unshifted) bytes. Because
     *     pointers are backward-only, this is preserved inductively for names reached through them.
     *
     * A SVCB/HTTPS TargetName MUST be uncompressed (RFC 9460 §2.2); a pointer there bails. The
     * DNSSEC Authenticated Data (AD) bit is cleared whenever an answer was modified, because the
     * rewritten answer is no longer what the resolver authenticated (RFC 6840 §5.8); a response
     * carrying RRSIGs never reaches here because RRSIG is not on the allowlist. A record whose
     * `mandatory` SvcParam names `ipv6hint` is left intact: removing the hint would leave `mandatory`
     * referencing an absent key, which RFC 9460 §8 says a client MUST reject.
     */
    fun stripIpv6Hints(response: ByteArray, fullTunnelChained: Boolean): ByteArray {
        if (!fullTunnelChained) return response

        // Only responses (QR=1) are rewritten; a query or a header shorter than 12 bytes is
        // returned untouched.
        if (response.size < HEADER_BYTES) return response
        if (response[2].toInt() and QR_FLAG == 0) return response

        val questionCount = readUInt16(response, QUESTION_COUNT_OFFSET)
        val recordCount = readUInt16(response, ANSWER_COUNT_OFFSET) +
            readUInt16(response, AUTHORITY_COUNT_OFFSET) +
            readUInt16(response, ADDITIONAL_COUNT_OFFSET)
        if (recordCount == 0) return response

        val pointerTargets = ArrayList<Int>()
        var cursor = HEADER_BYTES
        repeat(questionCount) {
            val name = readName(response, cursor) ?: return response
            name.pointerTarget?.let(pointerTargets::add)
            cursor = name.end
            if (cursor > response.size - QUESTION_TRAILER_BYTES) return response
            cursor += QUESTION_TRAILER_BYTES
        }

        val rewrites = ArrayList<Rewrite>()
        repeat(recordCount) {
            val owner = readName(response, cursor) ?: return response
            owner.pointerTarget?.let(pointerTargets::add)
            cursor = owner.end
            if (cursor > response.size - FIXED_RECORD_BYTES) return response

            val type = readUInt16(response, cursor)
            val rdataLength = readUInt16(response, cursor + RDATA_LENGTH_OFFSET_FROM_TYPE)
            val rdlengthFieldOffset = cursor + RDATA_LENGTH_OFFSET_FROM_TYPE
            val rdataStart = cursor + FIXED_RECORD_BYTES
            if (rdataStart > response.size - rdataLength) return response

            when (type) {
                // Fixed-size records must carry their required RDLENGTH before we trust the walk
                // enough to rewrite anything: a malformed address record alongside a strippable
                // one must preserve the response byte-for-byte.
                TYPE_A -> if (rdataLength != 4) return response
                TYPE_AAAA -> if (rdataLength != 16) return response
                TYPE_OPT -> Unit
                TYPE_CNAME -> {
                    val name = readName(response, rdataStart) ?: return response
                    if (name.end != rdataStart + rdataLength) return response
                    name.pointerTarget?.let(pointerTargets::add)
                }
                TYPE_SVCB, TYPE_HTTPS -> {
                    val ranges = ipv6HintRanges(response, rdataStart, rdataLength) ?: return response
                    if (ranges.isNotEmpty()) {
                        rewrites += Rewrite(rdlengthFieldOffset, rdataLength, ranges)
                    }
                }
                else -> return response
            }
            cursor = rdataStart + rdataLength
        }

        // The counts and the sections must agree exactly; leftover bytes mean a rewrite would guess.
        if (cursor != response.size) return response

        val deletions = rewrites.flatMap { it.ranges }.sortedBy { it.first }
        val earliestDeletion = deletions.firstOrNull()?.first ?: return response
        // Fields rewritten IN PLACE (the two RDLENGTH bytes of every stripped record, and the AD
        // bit in the flags byte) must not fall inside a referenced name either: those bytes change
        // without shifting, so a name that overlaps one resolves differently even though it ends
        // before the deletion.
        val mutatedOffsets = rewrites.flatMap {
            listOf(it.rdlengthFieldOffset, it.rdlengthFieldOffset + 1)
        }.toSet() + (FLAGS_OFFSET + 1)
        // Every byte a referenced name occupies — across every backward pointer hop — must lie
        // before the first deleted byte AND outside every in-place-mutated field. Checking a
        // contiguous [target, extent) span is not enough: nested pointers move BACKWARD, so a
        // chain can touch a mutated lower offset the span does not cover.
        for (target in pointerTargets) {
            if (!referencedNameIsSafe(response, target, earliestDeletion, mutatedOffsets)) {
                return response
            }
        }

        val fixed = response.copyOf()
        rewrites.forEach { rewrite ->
            val removed = rewrite.ranges.sumOf { it.last - it.first + 1 }
            writeUInt16(fixed, rewrite.rdlengthFieldOffset, rewrite.rdataLength - removed)
        }
        // The answer is no longer what the upstream resolver authenticated (RFC 6840 §5.8): clear AD.
        fixed[FLAGS_OFFSET + 1] = (fixed[FLAGS_OFFSET + 1].toInt() and AD_CLEAR_MASK).toByte()
        return deleteRanges(fixed, deletions)
    }

    /**
     * The byte ranges of every `ipv6hint` SvcParam (key + length + value) inside one SVCB/HTTPS
     * RDATA, or `null` when the RDATA does not parse as a well-formed service binding record. An
     * empty list means a valid record this must not strip: either it carries no `ipv6hint`, or its
     * `mandatory` SvcParam names `ipv6hint` (see [stripIpv6Hints]).
     */
    private fun ipv6HintRanges(
        bytes: ByteArray,
        rdataStart: Int,
        rdataLength: Int,
    ): List<IntRange>? {
        val rdataEnd = rdataStart + rdataLength
        // SvcPriority (2) + TargetName (>= 1 for the root label).
        if (rdataStart + 2 > rdataEnd) return null
        val priority = readUInt16(bytes, rdataStart)

        // TargetName MUST be uncompressed in a service binding record (RFC 9460 §2.2).
        val target = readName(bytes, rdataStart + 2) ?: return null
        if (target.pointerTarget != null) return null
        if (target.end > rdataEnd) return null
        var cursor = target.end

        // AliasMode (priority 0) carries no SvcParams; anything after the TargetName is malformed.
        if (priority == 0) {
            // RFC 9460: an AliasMode TargetName must not be the root label `.`.
            if (bytes[rdataStart + 2].toInt() == 0) return null
            return if (cursor == rdataEnd) emptyList() else null
        }

        // First collect every SvcParam so `mandatory` can be validated against the full key set.
        val keys = ArrayList<Int>()
        val paramSpans = ArrayList<IntRange>()
        val ranges = ArrayList<IntRange>()
        var previousKey = -1
        while (cursor < rdataEnd) {
            if (cursor > rdataEnd - SVC_PARAM_HEADER_BYTES) return null
            val key = readUInt16(bytes, cursor)
            val valueLength = readUInt16(bytes, cursor + 2)
            val valueStart = cursor + SVC_PARAM_HEADER_BYTES
            if (valueStart > rdataEnd - valueLength) return null
            // SvcParams appear in strictly increasing key order (RFC 9460 §2.2).
            if (key <= previousKey) return null
            previousKey = key
            val span = cursor until (valueStart + valueLength)
            keys += key
            paramSpans += span
            // Validate the wire constraints of the REGISTERED params we know, so a malformed
            // service binding is not rewritten. Unknown keys are opaque and pass through.
            when (key) {
                ALPN_KEY -> {
                    // ALPN is a sequence of nonempty length-prefixed protocol IDs that must
                    // consume the value exactly.
                    if (valueLength == 0) return null
                    var scan = valueStart
                    val end = valueStart + valueLength
                    while (scan < end) {
                        val idLength = bytes[scan].toInt() and 0xff
                        if (idLength == 0) return null
                        scan += 1
                        if (scan > end - idLength) return null
                        scan += idLength
                    }
                    if (scan != end) return null
                }
                NO_DEFAULT_ALPN_KEY -> if (valueLength != 0) return null
                PORT_KEY -> if (valueLength != 2) return null
                IPV4_HINT_KEY -> if (valueLength == 0 || valueLength % 4 != 0) return null
                ECH_KEY -> {
                    // RFC 9460: the ech value is a 2-byte length of the complete ECHConfigList
                    // followed by that list, itself a sequence of (version, length, contents)
                    // entries.
                    if (valueLength < 2) return null
                    val listLength = readUInt16(bytes, valueStart)
                    if (listLength == 0 || listLength != valueLength - 2) return null
                    var scan = valueStart + 2
                    val end = valueStart + valueLength
                    while (scan < end) {
                        if (scan > end - 4) return null
                        val configLength = readUInt16(bytes, scan + 2)
                        scan += 4
                        if (scan > end - configLength) return null
                        scan += configLength
                    }
                    if (scan != end) return null
                }
                IPV6_HINT_KEY -> {
                    // A well-formed ipv6hint carries one or more 16-byte addresses.
                    if (valueLength == 0 || valueLength % 16 != 0) return null
                    ranges += span
                }
            }
            cursor = valueStart + valueLength
        }
        if (cursor != rdataEnd) return null

        // RFC 9460: `no-default-alpn` may appear only when `alpn` is also present.
        if (NO_DEFAULT_ALPN_KEY in keys && ALPN_KEY !in keys) return null

        // Validate `mandatory` (key 0): a non-empty even list of strictly increasing, non-zero
        // keys that are all present in the record. If it names ipv6hint, this record must not be
        // stripped (RFC 9460 §8).
        var mandatoryListsIpv6Hint = false
        for (index in keys.indices) {
            if (keys[index] != MANDATORY_KEY) continue
            val span = paramSpans[index]
            val valueStart = span.first + SVC_PARAM_HEADER_BYTES
            val valueLength = span.last - valueStart + 1
            if (valueLength == 0 || valueLength % 2 != 0) return null
            var previousMandatory = -1
            var scan = valueStart
            while (scan < valueStart + valueLength) {
                val mandatoryKey = readUInt16(bytes, scan)
                if (mandatoryKey == 0 || mandatoryKey <= previousMandatory ||
                    mandatoryKey !in keys
                ) {
                    return null
                }
                if (mandatoryKey == IPV6_HINT_KEY) mandatoryListsIpv6Hint = true
                previousMandatory = mandatoryKey
                scan += 2
            }
        }
        return if (mandatoryListsIpv6Hint) emptyList() else ranges
    }

    /**
     * Walks a wire-format name from `offset`, returning where it ends and the target of its
     * compression pointer if it ends in one (RFC 1035 §4.1.4). The pointer is not followed for
     * `end`, but the referenced suffix IS measured, so the 255-octet expanded-name limit holds
     * across a compressed suffix. `null` on a malformed name, a forward/self pointer, or an
     * over-long expansion.
     */
    private fun readName(bytes: ByteArray, offset: Int): WalkedName? {
        var cursor = offset
        var expandedBytes = 0
        var hops = 0
        while (cursor < bytes.size) {
            if (++hops > MAX_NAME_HOPS) return null
            val length = bytes[cursor].toInt() and 0xff
            if (length == 0) {
                // +1 for the root terminator.
                return if (expandedBytes + 1 > MAX_EXPANDED_NAME_BYTES) null else WalkedName(cursor + 1, null)
            }
            if (length and POINTER_MASK == POINTER_MASK) {
                if (cursor + 2 > bytes.size) return null
                val target = ((length and POINTER_VALUE_MASK) shl 8) or
                    (bytes[cursor + 1].toInt() and 0xff)
                // Compression pointers must reference a PRIOR occurrence (RFC 1035 §4.1.4); a
                // forward or self pointer is malformed.
                if (target >= cursor) return null
                val referenced = expandedNameLength(bytes, target) ?: return null
                return if (expandedBytes + referenced > MAX_EXPANDED_NAME_BYTES) {
                    null
                } else {
                    WalkedName(cursor + 2, target)
                }
            }
            if (length and POINTER_MASK != 0 || length > MAX_LABEL_BYTES) return null
            if (cursor + 1 > bytes.size - length) return null
            expandedBytes += length + 1
            if (expandedBytes + 1 > MAX_EXPANDED_NAME_BYTES) return null
            cursor += length + 1
        }
        return null
    }

    /**
     * The expanded octet length (labels plus root terminator) of the name at [offset], following
     * compression pointers. `null` when the name is malformed, points forward, loops, or exceeds
     * [MAX_EXPANDED_NAME_BYTES].
     */
    private fun expandedNameLength(bytes: ByteArray, offset: Int): Int? {
        var cursor = offset
        var total = 0
        var hops = 0
        while (cursor < bytes.size) {
            if (++hops > MAX_NAME_HOPS) return null
            val length = bytes[cursor].toInt() and 0xff
            if (length == 0) return if (total + 1 > MAX_EXPANDED_NAME_BYTES) null else total + 1
            if (length and POINTER_MASK == POINTER_MASK) {
                if (cursor + 2 > bytes.size) return null
                val target = ((length and POINTER_VALUE_MASK) shl 8) or
                    (bytes[cursor + 1].toInt() and 0xff)
                if (target >= cursor) return null
                // Iterative: follow the backward pointer in the SAME loop so one shared hop
                // budget bounds the whole chain (recursion would reset the counter per call and
                // let a long chain overflow the stack).
                cursor = target
                continue
            }
            if (length and POINTER_MASK != 0 || length > MAX_LABEL_BYTES) return null
            if (cursor + 1 > bytes.size - length) return null
            total += length + 1
            if (total + 1 > MAX_EXPANDED_NAME_BYTES) return null
            cursor += length + 1
        }
        return null
    }

    /**
     * Whether every byte the name reachable from [offset] occupies — across every backward pointer
     * hop — lies strictly before [earliestDeletion] and outside [mutatedOffsets].
     *
     * A contiguous `[target, extent)` check is not enough: nested pointers move BACKWARD, so a
     * chain can touch a mutated lower offset the span does not cover. This walks the chain and
     * checks each label byte, pointer byte, and terminator individually. `false` on a malformed
     * name, a forward/self pointer, or an over-long chain.
     */
    private fun referencedNameIsSafe(
        bytes: ByteArray,
        offset: Int,
        earliestDeletion: Int,
        mutatedOffsets: Set<Int>,
    ): Boolean {
        if (offset < 0 || offset >= bytes.size) return false
        var cursor = offset
        var hops = 0
        while (true) {
            if (++hops > MAX_NAME_HOPS) return false
            if (cursor >= earliestDeletion || cursor in mutatedOffsets) return false
            val length = bytes[cursor].toInt() and 0xff
            if (length == 0) return true
            if (length and POINTER_MASK == POINTER_MASK) {
                if (cursor + 2 > bytes.size) return false
                if (cursor + 1 >= earliestDeletion || cursor + 1 in mutatedOffsets) return false
                val target = ((length and POINTER_VALUE_MASK) shl 8) or
                    (bytes[cursor + 1].toInt() and 0xff)
                if (target >= bytes.size || target >= cursor) return false
                cursor = target
                continue
            }
            if (length and POINTER_MASK != 0 || length > MAX_LABEL_BYTES) return false
            if (cursor + 1 > bytes.size - length) return false
            // Label bytes span [cursor, cursor + length]; every one must be safe.
            if (cursor + length >= earliestDeletion) return false
            for (index in cursor..(cursor + length)) {
                if (index in mutatedOffsets) return false
            }
            cursor += length + 1
        }
    }

    /** Copies every byte of [bytes] except the (sorted, non-overlapping) [deletions]. */
    private fun deleteRanges(bytes: ByteArray, deletions: List<IntRange>): ByteArray {
        val removed = deletions.sumOf { it.last - it.first + 1 }
        val out = ByteArray(bytes.size - removed)
        var source = 0
        var destination = 0
        deletions.forEach { range ->
            while (source < range.first) out[destination++] = bytes[source++]
            source = range.last + 1
        }
        while (source < bytes.size) out[destination++] = bytes[source++]
        return out
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 1] = (value and 0xff).toByte()
    }

    private data class WalkedName(val end: Int, val pointerTarget: Int?)

    private data class Rewrite(val rdlengthFieldOffset: Int, val rdataLength: Int, val ranges: List<IntRange>)

    private const val HEADER_BYTES = 12
    private const val QUESTION_TRAILER_BYTES = 4
    private const val FIXED_RECORD_BYTES = 10
    private const val RDATA_LENGTH_OFFSET_FROM_TYPE = 8
    private const val SVC_PARAM_HEADER_BYTES = 4
    private const val MAX_LABEL_BYTES = 63
    private const val MAX_EXPANDED_NAME_BYTES = 255
    private const val MAX_NAME_HOPS = 128

    private const val TRANSACTION_ID_OFFSET = 0
    private const val FLAGS_OFFSET = 2
    private const val QUESTION_COUNT_OFFSET = 4
    private const val ANSWER_COUNT_OFFSET = 6
    private const val AUTHORITY_COUNT_OFFSET = 8
    private const val ADDITIONAL_COUNT_OFFSET = 10

    private const val QR_FLAG = 0x80
    private const val RESPONSE_FLAG = 0x8000
    private const val RECURSION_DESIRED_FLAG = 0x0100
    private const val RECURSION_AVAILABLE_FLAG = 0x0080
    private const val AD_CLEAR_MASK = 0xdf

    private const val POINTER_MASK = 0xc0
    private const val POINTER_VALUE_MASK = 0x3f

    private const val TYPE_A = 1
    private const val TYPE_CNAME = 5
    private const val TYPE_AAAA = 28
    private const val TYPE_OPT = 41
    private const val TYPE_SVCB = 64
    private const val TYPE_HTTPS = 65

    private const val MANDATORY_KEY = 0
    private const val ALPN_KEY = 1
    private const val NO_DEFAULT_ALPN_KEY = 2
    private const val PORT_KEY = 3
    private const val IPV4_HINT_KEY = 4
    private const val ECH_KEY = 5
    private const val IPV6_HINT_KEY = 6
}
