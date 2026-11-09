package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage

/** Reply meaning is independent of socket permission and peer health. No server text is retained. */
internal object DnsFallbackResponse {
    enum class Kind { POSITIVE, NEGATIVE, TRANSIENT_FAILURE, AMBIGUOUS_FAILURE, PROHIBITED, MALFORMED, MISMATCHED, TRUNCATED }
    data class Classification(val kind: Kind, val fullResponseCode: Int? = null,
        val aliases: List<String> = emptyList(), val terminalAddressCount: Int = 0,
        val extendedErrors: List<Int> = emptyList())

    fun classify(query: ByteArray, response: ByteArray): Classification {
        val question = runCatching { DnsMessage.parseQuestion(query) }.getOrNull()
            ?: return Classification(Kind.MALFORMED)
        val end = 12 + question.questionBytes(query).size
        if (response.size < end || response.size > DnsMessage.MAX_MESSAGE_BYTES ||
            read16(response, 0) != question.transactionId || read16(response, 2) and 0x8000 == 0 ||
            read16(response, 2) and 0x7800 != read16(query, 2) and 0x7800 || read16(response, 4) != 1 ||
            !response.copyOfRange(12, end).contentEquals(query.copyOfRange(12, end))) return Classification(Kind.MISMATCHED)
        if (read16(response, 2) and 0x0200 != 0) return Classification(Kind.TRUNCATED)
        val strict = DnsResponseWireWalker.walk(response)
        val structure = strict ?: DnsResponseWireWalker.walk(response, validateStructuredRdata = false) ?: return Classification(Kind.MALFORMED)
        val opt = structure.records.singleOrNull { it.type == 41 }
        val code = ((opt?.let { response[it.ttlOffset].toInt() and 255 } ?: 0) shl 4) or (response[3].toInt() and 15)
        val errors = mutableListOf<Int>()
        var prohibited = false
        var operational = false
        for (opt in structure.records.filter { it.type == 41 }) {
            var cursor = opt.rdataOffset
            while (cursor < opt.endOffset) {
                val option = read16(response, cursor)
                val length = read16(response, cursor + 2)
                cursor += 4
                if (option == 15) {
                    if (length < 2) return Classification(Kind.MALFORMED, code)
                    val error = read16(response, cursor)
                    if (error in setOf(1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 16, 17, 18, 24, 25, 27, 28, 35)) prohibited = true
                    if (error in setOf(14, 22, 23)) operational = true
                    if (errors.size < 32 && error !in errors) errors += error
                }
                cursor += length
            }
        }
        if (prohibited) return Classification(Kind.PROHIBITED, code, extendedErrors = errors)
        val walk = strict ?: return Classification(Kind.MALFORMED, code, extendedErrors = errors)
        val answers = walk.records.filter { it.section == DnsResponseWireWalker.Section.ANSWER }
        val aliases = mutableListOf<String>()
        val original = name(query, 12) ?: return Classification(Kind.MALFORMED, code)
        val visited = mutableSetOf(original)
        var terminal = original
        var terminalReached = false
        for (step in 0..answers.size) {
            val cnames = answers.filter { it.type == 5 && it.recordClass == 1 && name(response, it.ownerOffset) == terminal }
            val dnames = answers.filter { it.type == 39 && it.recordClass == 1 }.mapNotNull { record ->
                val owner = name(response, record.ownerOffset) ?: return Classification(Kind.MALFORMED, code)
                if (terminal.isStrictDescendantOf(owner)) owner to record else null
            }
            val targets = cnames.map { name(response, it.rdataOffset) ?: return Classification(Kind.MALFORMED, code) }.toMutableSet()
            if (dnames.isNotEmpty()) {
                val mostSpecific = dnames.maxBy { it.first.labels.size }
                if (dnames.count { it.first.labels.size == mostSpecific.first.labels.size } > 1) return Classification(Kind.MALFORMED, code)
                val target = name(response, mostSpecific.second.rdataOffset) ?: return Classification(Kind.MALFORMED, code)
                targets += terminal.replacingSuffix(mostSpecific.first, target)
            }
            if (targets.isEmpty()) {
                terminalReached = true
                break
            }
            if (targets.size != 1 || !visited.add(targets.single())) return Classification(Kind.MALFORMED, code)
            terminal = targets.single()
            // The walker validates encoded names; a synthesized DNAME target must also fit.
            // DNS wire labels do not inherit filtering-input hostname or label-count rules.
            if (terminal.expandedBytes > 255) return Classification(Kind.MALFORMED, code)
            aliases += terminal.presentation()
        }
        if (!terminalReached) return Classification(Kind.MALFORMED, code)
        var addressCount = 0
        for (record in answers.filter { it.recordClass == 1 && it.type in setOf(1, 28) }) {
            val owner = name(response, record.ownerOffset) ?: return Classification(Kind.MALFORMED, code)
            if (record.endOffset - record.rdataOffset != if (record.type == 1) 4 else 16) return Classification(Kind.MALFORMED, code)
            if (owner in visited && owner != terminal) return Classification(Kind.MALFORMED, code)
            if (owner == terminal && record.type == question.rawRecordType) addressCount++
        }
        val kind = when (code) {
            0 -> if (if (question.rawRecordType in setOf(1, 28)) addressCount > 0 else answers.isNotEmpty()) Kind.POSITIVE else Kind.NEGATIVE
            3 -> if (addressCount == 0) Kind.NEGATIVE else Kind.MALFORMED
            else -> if (operational) Kind.TRANSIENT_FAILURE else Kind.AMBIGUOUS_FAILURE
        }
        return Classification(kind, code, aliases, addressCount, errors)
    }

    /** Label boundaries and octets define identity; only ASCII letters are case-insensitive. */
    private data class WireName(val labels: List<String>) {
        val expandedBytes: Int get() = 1 + labels.sumOf { it.length + 1 }

        fun isStrictDescendantOf(owner: WireName): Boolean =
            labels.size > owner.labels.size && labels.takeLast(owner.labels.size) == owner.labels

        fun replacingSuffix(owner: WireName, target: WireName) =
            WireName(labels.dropLast(owner.labels.size) + target.labels)

        // Escaping prevents a literal dot, backslash or opaque label octet from acquiring
        // hostname structure or matching the portal registry's plain ASCII spellings.
        fun presentation(): String = labels.joinToString(".") { label -> buildString {
            for (octet in label) {
                if (octet.code in 33..126 && octet != '.' && octet != '\\') append(octet)
                else append('\\').append(octet.code.toString().padStart(3, '0'))
            }
        } }
    }

    /** The strict walker has already proved compression boundaries and bounded expansion. */
    private fun name(bytes: ByteArray, offset: Int): WireName? {
        var cursor = offset
        val labels = mutableListOf<String>()
        val visited = mutableSetOf<Int>()
        var expandedBytes = 1
        while (cursor in bytes.indices && visited.add(cursor)) {
            val length = bytes[cursor].toInt() and 255
            if (length == 0) return WireName(labels)
            if (length and 0xc0 == 0xc0) {
                if (cursor + 1 >= bytes.size) return null
                cursor = ((length and 63) shl 8) or (bytes[cursor + 1].toInt() and 255)
            } else {
                if (length and 0xc0 != 0 || length > 63 || cursor + 1 + length > bytes.size) return null
                expandedBytes += length + 1
                if (expandedBytes > 255) return null
                labels += buildString {
                    for (index in cursor + 1..cursor + length) {
                        val octet = bytes[index].toInt() and 255
                        append((if (octet in 65..90) octet + 32 else octet).toChar())
                    }
                }
                cursor += length + 1
            }
        }
        return null
    }
    private fun read16(bytes: ByteArray, offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
}

/** Immutable query permission from the current Android Network and normal filter owner. */
data class DnsFallbackQueryContext(
    val isSatisfiedWifi: Boolean = false,
    val hasEnabledDeviceTier: Boolean = false,
    val hasCurrentDeviceCapture: Boolean = false,
    val permitsPhysicalDeviceDns: Boolean = false,
    val permittedPortalHosts: Set<String> = emptySet(),
    val operationalFallbackEnabled: Boolean = true,
    val portalRescueEnabled: Boolean = true,
    val requiresPortalAdmission: Boolean = false,
    val portalNetworkIsCurrent: () -> Boolean = { true },
)

internal object DnsPortalTierFallback {
    const val REGISTRY_REVISION = 1
    val registeredHosts = setOf("deltawifi.com", "wifi.delta.com")
    fun eligible(query: ByteArray, response: ByteArray, context: DnsFallbackQueryContext): Boolean {
        val classification = DnsFallbackResponse.classify(query, response)
        return admitted(query, classification, context) && classification.kind == DnsFallbackResponse.Kind.NEGATIVE
    }
    fun rescueAnswer(query: ByteArray, response: ByteArray, context: DnsFallbackQueryContext): Boolean {
        val classification = DnsFallbackResponse.classify(query, response)
        return admitted(query, classification, context) && classification.kind == DnsFallbackResponse.Kind.POSITIVE && classification.terminalAddressCount > 0
    }
    private fun admitted(query: ByteArray, result: DnsFallbackResponse.Classification, context: DnsFallbackQueryContext): Boolean {
        if (!context.portalRescueEnabled || !context.isSatisfiedWifi || !context.hasEnabledDeviceTier ||
            !context.hasCurrentDeviceCapture || !context.permitsPhysicalDeviceDns || !context.portalNetworkIsCurrent()) return false
        val question = runCatching { DnsMessage.parseQuestion(query) }.getOrNull() ?: return false
        return question.rawRecordType in setOf(1, 28) && question.normalizedDomain in registeredHosts &&
            question.normalizedDomain in context.permittedPortalHosts &&
            result.aliases.all { it in registeredHosts && it in context.permittedPortalHosts }
    }
}
