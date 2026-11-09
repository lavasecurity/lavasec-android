package com.lavasec.app.foundation.catalog

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Reasons a catalog document is refused. Every one is terminal: the caller falls back to the
 * last-known-good or bundled catalog rather than partially trusting the input.
 */
enum class CatalogParseFailure {
    /** The document exceeds [CatalogLimits.MAX_DOCUMENT_BYTES]. */
    DOCUMENT_TOO_LARGE,

    /** The bytes are not valid UTF-8 JSON, or are truncated / have trailing garbage. */
    MALFORMED_JSON,

    /** A JSON object declares the same key twice. */
    DUPLICATE_FIELD,

    /** `schema_version` is missing, or is a version this client does not understand. */
    UNSUPPORTED_SCHEMA_VERSION,

    /** A required field is absent. */
    MISSING_REQUIRED_FIELD,

    /** An object carries a key that is not part of the catalog schema. */
    UNKNOWN_KEY,

    /** A present field has the wrong JSON type (for example a string where an integer is required). */
    INVALID_FIELD_TYPE,

    /** A field is the right type but violates a range, format or identity rule. */
    INVALID_FIELD_VALUE,

    /** Two sources share an id (compared case-insensitively). */
    DUPLICATE_SOURCE_ID,

    /** `parse_format` is not one of the formats this client can parse. */
    UNSUPPORTED_PARSE_FORMAT,

    /** A source URL is not a public HTTPS URL. */
    INVALID_SOURCE_URL,

    /** More sources than the fixed catalog cap. */
    TOO_MANY_SOURCES,

    /** The envelope's declared SHA-256 does not match the document bytes. */
    CHECKSUM_MISMATCH,

    /** The envelope's declared revision does not match the decoded `catalog_version`. */
    REVISION_MISMATCH,
}

/** Hard bounds applied before and during decoding. None are configurable by the document. */
object CatalogLimits {
    /** Decode-side ceiling, matching iOS `BlocklistCatalogRepository.maximumCatalogBytes`. */
    const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024

    /** Maximum sources plus guardrails, matching iOS `validateDecodedMetadata`. */
    const val MAX_SOURCES = 512

    /** Maximum UTF-8 bytes in a source id. */
    const val MAX_SOURCE_ID_BYTES = 128

    /** Maximum UTF-8 bytes in a source `version_id`, leaving room for the cache filename suffix. */
    const val MAX_VERSION_ID_BYTES = 238

    /** Upper bound on a declared entry count; matches the iOS parse budget's byte ceiling. */
    const val MAX_SOURCE_ENTRY_COUNT = 45 * 1024 * 1024

    internal const val MAX_STRING_CHARS = 64 * 1024
    internal const val MAX_DEPTH = 48
    internal const val MAX_ARRAY_MEMBERS = 4096
    internal const val MAX_OBJECT_MEMBERS = 256
}

/** A minimal, bounded JSON tree. Numbers are integers only: the catalog schema has no floats. */
internal sealed interface CatalogJson {
    data class Obj(val fields: LinkedHashMap<String, CatalogJson>) : CatalogJson
    data class Arr(val items: List<CatalogJson>) : CatalogJson
    data class Str(val value: String) : CatalogJson
    data class Num(val value: Long) : CatalogJson
    data class Bool(val value: Boolean) : CatalogJson
    data object Null : CatalogJson
}

internal class CatalogJsonException(val failure: CatalogParseFailure) : Exception(failure.name)

/**
 * Strict, allocation-bounded JSON reader used only for catalog documents.
 *
 * Unlike a general decoder it refuses duplicate object keys, integer overflow, numbers with a
 * fraction or exponent, invalid UTF-8, control characters inside strings, input past
 * [CatalogLimits.MAX_STRING_CHARS] and nesting past [CatalogLimits.MAX_DEPTH]. Each of those is a
 * way a hostile 8 MiB document could otherwise expand or wedge the caller, so all of them fail
 * closed as [CatalogParseFailure.MALFORMED_JSON] or [CatalogParseFailure.DUPLICATE_FIELD].
 */
internal object CatalogJsonDecoder {
    fun decode(bytes: ByteArray): CatalogJson {
        if (bytes.size > CatalogLimits.MAX_DOCUMENT_BYTES) {
            throw CatalogJsonException(CatalogParseFailure.DOCUMENT_TOO_LARGE)
        }
        val reader = Reader(decodeUtf8Strict(bytes))
        val value = reader.readDocument()
        return value
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw CatalogJsonException(CatalogParseFailure.MALFORMED_JSON)
        }
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun readDocument(): CatalogJson {
            skipWhitespace()
            val value = readValue(0)
            skipWhitespace()
            if (index != text.length) fail()
            return value
        }

        private fun readValue(depth: Int): CatalogJson {
            if (depth > CatalogLimits.MAX_DEPTH) fail()
            if (index >= text.length) fail()
            return when (val char = text[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> CatalogJson.Str(readString())
                't' -> readLiteral("true", CatalogJson.Bool(true))
                'f' -> readLiteral("false", CatalogJson.Bool(false))
                'n' -> readLiteral("null", CatalogJson.Null)
                '-', in '0'..'9' -> CatalogJson.Num(readInteger())
                else -> fail("unexpected character '$char'")
            }
        }

        private fun readObject(depth: Int): CatalogJson.Obj {
            index++
            val fields = LinkedHashMap<String, CatalogJson>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return CatalogJson.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("object key must be a string")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("missing ':' after object key")
                index++
                skipWhitespace()
                if (fields.put(key, readValue(depth + 1)) != null) {
                    throw CatalogJsonException(CatalogParseFailure.DUPLICATE_FIELD)
                }
                if (fields.size > CatalogLimits.MAX_OBJECT_MEMBERS) fail("object has too many members")
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return CatalogJson.Obj(fields)
                    }

                    else -> fail("expected ',' or '}' in object")
                }
            }
        }

        private fun readArray(depth: Int): CatalogJson.Arr {
            index++
            val items = ArrayList<CatalogJson>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return CatalogJson.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items.add(readValue(depth + 1))
                if (items.size > CatalogLimits.MAX_ARRAY_MEMBERS) fail("array has too many members")
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return CatalogJson.Arr(items)
                    }

                    else -> fail("expected ',' or ']' in array")
                }
            }
        }

        private fun readString(): String {
            index++
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) fail("unterminated string")
                val char = text[index]
                when {
                    char == '"' -> {
                        index++
                        return builder.toString()
                    }

                    char == '\\' -> {
                        index++
                        readEscape(builder)
                    }

                    char.code < 0x20 -> fail("control character in string")
                    else -> {
                        builder.append(char)
                        index++
                    }
                }
                if (builder.length > CatalogLimits.MAX_STRING_CHARS) fail("string is too long")
            }
        }

        private fun readEscape(builder: StringBuilder) {
            if (index >= text.length) fail("unterminated escape")
            when (val escape = text[index]) {
                '"' -> builder.append('"')
                '\\' -> builder.append('\\')
                '/' -> builder.append('/')
                'b' -> builder.append('\b')
                'f' -> builder.append('\u000C')
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                'u' -> {
                    val high = readUnicodeEscape()
                    if (Character.isHighSurrogate(high)) {
                        if (index + 1 >= text.length || text[index] != '\\' || text[index + 1] != 'u') {
                            fail("dangling high surrogate")
                        }
                        index += 2
                        val low = readUnicodeEscape()
                        if (!Character.isLowSurrogate(low)) fail("invalid low surrogate")
                        builder.append(high).append(low)
                    } else if (Character.isLowSurrogate(high)) {
                        fail("unpaired low surrogate")
                    } else {
                        builder.append(high)
                    }
                    return
                }

                else -> fail("invalid escape '$escape'")
            }
            index++
        }

        private fun readUnicodeEscape(): Char {
            if (index + 4 > text.length) fail("truncated unicode escape")
            var value = 0
            for (offset in 0 until 4) {
                val digit = Character.digit(text[index + offset], 16)
                if (digit < 0) fail("invalid unicode escape")
                value = (value shl 4) or digit
            }
            index += 4
            return value.toChar()
        }

        private fun readInteger(): Long {
            val start = index
            if (peek() == '-') index++
            if (index >= text.length || text[index] !in '0'..'9') fail("invalid number")
            if (text[index] == '0') {
                index++
                if (index < text.length && text[index] in '0'..'9') fail("leading zero in number")
            } else {
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index < text.length && (text[index] == '.' || text[index] == 'e' || text[index] == 'E')) {
                fail("fractional or exponent number is unsupported")
            }
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: fail("number out of range")
        }

        private fun <T : CatalogJson> readLiteral(expected: String, value: T): T {
            if (!text.startsWith(expected, index)) fail("invalid literal")
            index += expected.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index++
                    else -> return
                }
            }
        }

        private fun peek(): Char {
            if (index >= text.length) fail("unexpected end of document")
            return text[index]
        }

        private fun fail(message: String = "malformed JSON"): Nothing =
            throw CatalogJsonException(CatalogParseFailure.MALFORMED_JSON)
    }
}
