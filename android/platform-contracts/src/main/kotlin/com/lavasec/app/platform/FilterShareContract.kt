package com.lavasec.app.platform

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.domain.DomainValidationException
import com.lavasec.app.foundation.filter.ManualDomainRules
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashSet
import java.util.Locale

/**
 * The versioned share payload contract for filters, shipped before the share/import UI.
 *
 * iOS anchors: `ShareableFiltersUI.swift` and the pinned copy — "For safety, a code carries
 * just your blocklists and blocked domains. Your allowed exceptions never leave this
 * device." A payload therefore contains exactly catalog source ids and blocked domains;
 * allowed exceptions, credentials, account tokens, and local activity are structurally
 * impossible to carry. The envelope is versioned, size-bounded, and integrity-checked
 * (SHA-256 over version + body): "authenticated or integrity-checked" — Android ships the
 * integrity-checked shape, and the import side refuses to preview anything whose checksum
 * or structure fails.
 *
 * Domains are validated with the exact same validator local editing uses
 * (`DomainName.parse` inside `ManualDomainRules`) at BOTH encode and decode, so a decoded
 * payload can never smuggle a domain past the local-editing gates. Source ids stay opaque
 * strings here — an import preview must resolve them against the signed catalog before
 * anything can be staged (see the app-side import preflight).
 *
 * The wire body is a fixed two-field JSON document written and parsed by the small
 * hand-rolled codec below: the platform-contracts module is JVM-pure, so the Android
 * `org.json` stub must not leak into this contract, and a hand-rolled parser keeps the
 * accepted grammar exactly as narrow as the encoder emits.
 */
data class FilterSharePayload(
    val sourceIds: List<String>,
    val blockedDomains: List<String>,
) {
    init {
        require(sourceIds.size <= FilterShareContract.MAX_SOURCE_IDS) {
            "shared source count exceeds contract limit"
        }
        require(blockedDomains.size <= ManualDomainRules.MAX_BLOCKED_DOMAINS) {
            "shared blocked-domain count exceeds the local editing limit"
        }
        require(sourceIds.distinct().size == sourceIds.size) { "shared source ids must be unique" }
        require(blockedDomains.distinct().size == blockedDomains.size) {
            "shared blocked domains must be unique"
        }
    }
}

object FilterShareContract {
    const val VERSION = 1
    const val MAX_ENCODED_CHARACTERS = 262_144
    const val MAX_SOURCE_IDS = 4_096
    const val MAX_SOURCE_ID_CHARACTERS = 128

    internal const val PREFIX = "LAVA-FILTER-1."
    internal const val CHECKSUM_HEX_CHARACTERS = 64
}

enum class FilterShareRejection {
    NOT_A_FILTER_CODE,
    UNSUPPORTED_VERSION,
    TOO_LARGE,
    CHECKSUM_MISMATCH,
    INVALID_PAYLOAD,
    INVALID_DOMAIN,
    DUPLICATE_DOMAIN,
    DUPLICATE_SOURCE,
    TOO_MANY_SOURCES,
    TOO_MANY_DOMAINS,
    BLANK_SOURCE,
    SOURCE_TOO_LONG,
}

sealed interface FilterShareDecodeResult {
    data class Decoded(val payload: FilterSharePayload) : FilterShareDecodeResult

    data class Rejected(val reason: FilterShareRejection) : FilterShareDecodeResult
}

/**
 * Encoder/decoder for the versioned envelope:
 * `LAVA-FILTER-1.<base64url(JSON body)><sha256-hex of "LAVA-FILTER-1." + body>`.
 */
object FilterShareCodec {
    fun encode(payload: FilterSharePayload): String {
        // Re-validate every domain through the local editing validator before it can leave the
        // device: the encoder itself is the first gate, so a caller cannot serialize a payload
        // that the local draft editor would have rejected.
        val normalizedDomains = payload.blockedDomains.map { raw ->
            try {
                DomainName.parse(raw).value
            } catch (_: DomainValidationException) {
                throw IllegalArgumentException("shared payload contains an invalid domain")
            }
        }
        val json = ShareJson.encode(payload.sourceIds, normalizedDomains)
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(
            json.toByteArray(Charsets.UTF_8),
        )
        val signed = "${FilterShareContract.PREFIX}$body"
        val checksum = sha256Hex(signed.toByteArray(Charsets.UTF_8))
        val encoded = "$signed.$checksum"
        require(encoded.length <= FilterShareContract.MAX_ENCODED_CHARACTERS) {
            "encoded share payload exceeds the contract size bound"
        }
        return encoded
    }

    fun decode(code: String): FilterShareDecodeResult {
        if (code.length > FilterShareContract.MAX_ENCODED_CHARACTERS) {
            return FilterShareDecodeResult.Rejected(FilterShareRejection.TOO_LARGE)
        }
        if (!code.startsWith(FilterShareContract.PREFIX)) {
            return FilterShareDecodeResult.Rejected(FilterShareRejection.NOT_A_FILTER_CODE)
        }
        val dot = code.lastIndexOf('.')
        if (dot == FilterShareContract.PREFIX.length - 1) {
            return FilterShareDecodeResult.Rejected(FilterShareRejection.NOT_A_FILTER_CODE)
        }
        val signed = code.substring(0, dot)
        val checksum = code.substring(dot + 1)
        if (
            checksum.length != FilterShareContract.CHECKSUM_HEX_CHARACTERS ||
            checksum.any { it !in "0123456789abcdefABCDEF" }
        ) {
            return FilterShareDecodeResult.Rejected(FilterShareRejection.CHECKSUM_MISMATCH)
        }
        val expected = sha256Hex(signed.toByteArray(Charsets.UTF_8)).lowercase(Locale.ROOT)
        if (!MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                checksum.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8),
            )
        ) {
            return FilterShareDecodeResult.Rejected(FilterShareRejection.CHECKSUM_MISMATCH)
        }
        return decodeBody(code.substring(FilterShareContract.PREFIX.length, dot))
    }

    private fun decodeBody(body: String): FilterShareDecodeResult {
        val json = try {
            String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return FilterShareDecodeResult.Rejected(FilterShareRejection.INVALID_PAYLOAD)
        }
        val parsed = ShareJson.decode(json) ?: return FilterShareDecodeResult.Rejected(
            FilterShareRejection.INVALID_PAYLOAD,
        )
        val (sources, domains) = parsed

        if (sources.size > FilterShareContract.MAX_SOURCE_IDS) {
            return FilterShareDecodeResult.Rejected(FilterShareRejection.TOO_MANY_SOURCES)
        }
        val seenSources = LinkedHashSet<String>()
        for (source in sources) {
            if (source.isBlank()) {
                return FilterShareDecodeResult.Rejected(FilterShareRejection.BLANK_SOURCE)
            }
            if (source.length > FilterShareContract.MAX_SOURCE_ID_CHARACTERS) {
                return FilterShareDecodeResult.Rejected(FilterShareRejection.SOURCE_TOO_LONG)
            }
            if (!seenSources.add(source)) {
                return FilterShareDecodeResult.Rejected(FilterShareRejection.DUPLICATE_SOURCE)
            }
        }
        if (domains.size > ManualDomainRules.MAX_BLOCKED_DOMAINS) {
            return FilterShareDecodeResult.Rejected(FilterShareRejection.TOO_MANY_DOMAINS)
        }
        // Never trust a decoded domain until the same validators local editing uses accept it:
        // each domain runs through DomainName.parse (IDN normalization included), duplicates are
        // rejected, and the result is exactly what ManualRuleEditor would have stored.
        val normalized = LinkedHashSet<String>()
        for (raw in domains) {
            val value = try {
                DomainName.parse(raw).value
            } catch (_: DomainValidationException) {
                return FilterShareDecodeResult.Rejected(FilterShareRejection.INVALID_DOMAIN)
            }
            if (!normalized.add(value)) {
                return FilterShareDecodeResult.Rejected(FilterShareRejection.DUPLICATE_DOMAIN)
            }
        }
        return FilterShareDecodeResult.Decoded(
            FilterSharePayload(
                sourceIds = seenSources.toList(),
                blockedDomains = normalized.toList(),
            ),
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte)
        }
}

/**
 * The narrow hand-rolled JSON subset the envelope carries: exactly
 * `{"sources":["..."],"blocked":["..."]}`. The parser accepts no other grammar, so a
 * payload can never smuggle nested structures into the decoder.
 */
private object ShareJson {
    fun encode(sourceIds: List<String>, blockedDomains: List<String>): String {
        val sources = sourceIds.joinToString(",") { jsonString(it) }
        val blocked = blockedDomains.joinToString(",") { jsonString(it) }
        return """{"sources":[$sources],"blocked":[$blocked]}"""
    }

    fun decode(json: String): Pair<List<String>, List<String>>? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return null

        val sources = field(trimmed, "sources") ?: return null
        val blocked = field(trimmed, "blocked") ?: return null
        val sourceList = parseStringArray(sources) ?: return null
        val blockedList = parseStringArray(blocked) ?: return null
        return sourceList to blockedList
    }

    private fun field(json: String, name: String): String? {
        val prefix = "\"$name\":"
        val start = json.indexOf(prefix)
        if (start < 0) return null
        var index = start + prefix.length
        while (index < json.length && json[index] == ' ') index++
        if (index >= json.length) return null
        if (json[index] == '[') {
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            for (cursor in index until json.length) {
                val character = json[cursor]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> inString = false
                    }
                } else {
                    when (character) {
                        '"' -> inString = true
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) {
                                end = cursor
                                break
                            }
                        }
                    }
                }
            }
            if (end < 0) return null
            return json.substring(index, end + 1)
        }
        if (json[index] == '"') {
            var cursor = index + 1
            var escaped = false
            while (cursor < json.length) {
                val character = json[cursor]
                if (escaped) {
                    escaped = false
                } else {
                    when (character) {
                        '\\' -> escaped = true
                        '"' -> break
                    }
                }
                cursor++
            }
            return json.substring(index, cursor + 1)
        }
        return null
    }

    private fun parseStringArray(field: String): List<String>? {
        if (!field.startsWith("[") || !field.endsWith("]")) return null
        val inner = field.substring(1, field.length - 1)
        if (inner.isEmpty()) return emptyList()
        val values = ArrayList<String>()
        var index = 0
        while (index < inner.length) {
            while (index < inner.length && (inner[index] == ' ' || inner[index] == ',')) index++
            if (index >= inner.length) break
            if (inner[index] != '"') return null
            index++
            val value = StringBuilder()
            var escaped = false
            while (index < inner.length) {
                val character = inner[index]
                if (escaped) {
                    value.append(character)
                    escaped = false
                    index++
                    continue
                }
                when (character) {
                    '\\' -> {
                        escaped = true
                        index++
                    }
                    '"' -> {
                        index++
                        break
                    }
                    else -> {
                        value.append(character)
                        index++
                    }
                }
            }
            values += value.toString()
        }
        return values
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(character)
            }
        }
        append('"')
    }
}
