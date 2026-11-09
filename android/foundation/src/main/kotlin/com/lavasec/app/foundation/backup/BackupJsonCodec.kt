package com.lavasec.app.foundation.backup

/**
 * Minimal, dependency-free JSON tree and codec for the portable backup payload.
 *
 * The foundation module deliberately carries no JSON library (only ICU4J and coroutines, and its
 * unit tests run on a plain JVM with no `org.json`). The backup payload is a fixed, known shape,
 * so a small bounded recursive-descent codec is enough and keeps the wire deterministic:
 *
 * - object keys are always emitted in lexicographic order (mirrors the iOS encoder's `.sortedKeys`);
 * - arrays preserve their stored order, and set-backed fields are sorted before emission;
 * - the decoder enforces an input-character budget, a nesting-depth budget and a per-collection
 *   budget, so a hostile payload cannot make the parser allocate unboundedly.
 *
 * This mirrors the iOS payload encoder (`ZeroKnowledgeBackupEnvelope.makeJSONEncoder`, which sets
 * only `.sortedKeys`; default date strategy = seconds since the Apple reference date).
 */
internal sealed interface BackupJsonValue {
    data class JsonObject(val members: Map<String, BackupJsonValue>) : BackupJsonValue
    data class JsonArray(val values: List<BackupJsonValue>) : BackupJsonValue
    data class JsonString(val value: String) : BackupJsonValue
    data class JsonNumber(val raw: String) : BackupJsonValue
    data class JsonBoolean(val value: Boolean) : BackupJsonValue
    data object JsonNull : BackupJsonValue
}

/** The payload bytes/characters were not well-formed JSON under the codec's bounds. */
internal class BackupJsonSyntaxException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object BackupJsonCodec {
    /** Nesting budget; the payload's deepest path is object -> array -> object -> array. */
    const val MAX_DEPTH = 32

    /** Elements per array / members per object. A 50-filter backup stays far below this. */
    const val MAX_COLLECTION_SIZE = 200_000

    /** Characters per JSON string and per whole input; mirrors the envelope ciphertext bound. */
    const val MAX_STRING_LENGTH = 262_144

    fun parse(text: String, maxChars: Int = MAX_STRING_LENGTH): BackupJsonValue {
        if (maxChars < 1) throw BackupJsonSyntaxException("json character budget must be positive")
        if (text.length > maxChars) {
            throw BackupJsonSyntaxException("json input exceeds the $maxChars character budget")
        }
        val parser = Parser(text)
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            throw BackupJsonSyntaxException("unexpected trailing content at offset ${parser.offset}")
        }
        return value
    }

    fun print(value: BackupJsonValue): String {
        val builder = StringBuilder()
        writeValue(value, builder, 0)
        return builder.toString()
    }

    private fun writeValue(value: BackupJsonValue, out: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) throw BackupJsonSyntaxException("json nesting exceeds $MAX_DEPTH")
        when (value) {
            is BackupJsonValue.JsonObject -> {
                out.append('{')
                var first = true
                for (key in value.members.keys.sorted()) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key, out)
                    out.append(':')
                    writeValue(value.members.getValue(key), out, depth + 1)
                }
                out.append('}')
            }

            is BackupJsonValue.JsonArray -> {
                out.append('[')
                value.values.forEachIndexed { index, element ->
                    if (index > 0) out.append(',')
                    writeValue(element, out, depth + 1)
                }
                out.append(']')
            }

            is BackupJsonValue.JsonString -> writeString(value.value, out)
            is BackupJsonValue.JsonNumber -> out.append(value.raw)
            is BackupJsonValue.JsonBoolean -> out.append(if (value.value) "true" else "false")
            BackupJsonValue.JsonNull -> out.append("null")
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (character < ' ') {
                    out.append("\\u")
                    val hex = character.code.toString(16)
                    repeat(4 - hex.length) { out.append('0') }
                    out.append(hex)
                } else {
                    out.append(character)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        var offset: Int = 0
            private set

        fun atEnd(): Boolean = offset >= text.length

        fun skipWhitespace() {
            while (offset < text.length && text[offset].isJsonWhitespace()) offset++
        }

        fun parseValue(depth: Int): BackupJsonValue {
            if (depth > MAX_DEPTH) throw BackupJsonSyntaxException("json nesting exceeds $MAX_DEPTH")
            if (atEnd()) throw BackupJsonSyntaxException("unexpected end of input")
            return when (val character = text[offset]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> BackupJsonValue.JsonString(parseString())
                't' -> parseKeyword("true", BackupJsonValue.JsonBoolean(true))
                'f' -> parseKeyword("false", BackupJsonValue.JsonBoolean(false))
                'n' -> parseKeyword("null", BackupJsonValue.JsonNull)
                else -> if (character == '-' || character in '0'..'9') {
                    parseNumber()
                } else {
                    throw BackupJsonSyntaxException("unexpected character '$character' at offset $offset")
                }
            }
        }

        private fun parseObject(depth: Int): BackupJsonValue.JsonObject {
            expect('{')
            skipWhitespace()
            val members = LinkedHashMap<String, BackupJsonValue>()
            if (peek() == '}') {
                offset++
                return BackupJsonValue.JsonObject(members)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') {
                    throw BackupJsonSyntaxException("expected object key at offset $offset")
                }
                val key = parseString()
                if (members.containsKey(key)) {
                    throw BackupJsonSyntaxException("duplicate object key '$key'")
                }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                members[key] = parseValue(depth + 1)
                if (members.size > MAX_COLLECTION_SIZE) {
                    throw BackupJsonSyntaxException("object exceeds $MAX_COLLECTION_SIZE members")
                }
                skipWhitespace()
                when (peek()) {
                    ',' -> offset++
                    '}' -> {
                        offset++
                        return BackupJsonValue.JsonObject(members)
                    }

                    else -> throw BackupJsonSyntaxException("expected ',' or '}' at offset $offset")
                }
            }
        }

        private fun parseArray(depth: Int): BackupJsonValue.JsonArray {
            expect('[')
            skipWhitespace()
            val values = ArrayList<BackupJsonValue>()
            if (peek() == ']') {
                offset++
                return BackupJsonValue.JsonArray(values)
            }
            while (true) {
                skipWhitespace()
                values.add(parseValue(depth + 1))
                if (values.size > MAX_COLLECTION_SIZE) {
                    throw BackupJsonSyntaxException("array exceeds $MAX_COLLECTION_SIZE elements")
                }
                skipWhitespace()
                when (peek()) {
                    ',' -> offset++
                    ']' -> {
                        offset++
                        return BackupJsonValue.JsonArray(values)
                    }

                    else -> throw BackupJsonSyntaxException("expected ',' or ']' at offset $offset")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) throw BackupJsonSyntaxException("unterminated string")
                val character = text[offset++]
                when {
                    character == '"' -> return builder.toString()
                    character == '\\' -> builder.append(parseEscape())
                    character < ' ' -> throw BackupJsonSyntaxException(
                        "unescaped control character at offset ${offset - 1}",
                    )

                    else -> builder.append(character)
                }
                if (builder.length > MAX_STRING_LENGTH) {
                    throw BackupJsonSyntaxException("string exceeds the $MAX_STRING_LENGTH character budget")
                }
            }
        }

        private fun parseEscape(): Char {
            if (atEnd()) throw BackupJsonSyntaxException("unterminated escape sequence")
            return when (val escape = text[offset++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicodeEscape()
                else -> throw BackupJsonSyntaxException("invalid escape '\\$escape' at offset ${offset - 1}")
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (offset + 4 > text.length) throw BackupJsonSyntaxException("truncated unicode escape")
            var value = 0
            repeat(4) {
                val digit = text[offset++].digitToIntOrNull(16)
                    ?: throw BackupJsonSyntaxException("invalid unicode escape at offset ${offset - 1}")
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun parseKeyword(keyword: String, value: BackupJsonValue): BackupJsonValue {
            if (!text.startsWith(keyword, offset)) {
                throw BackupJsonSyntaxException("expected '$keyword' at offset $offset")
            }
            offset += keyword.length
            return value
        }

        private fun parseNumber(): BackupJsonValue.JsonNumber {
            val start = offset
            if (peek() == '-') offset++
            if (peek() == '0') {
                offset++
            } else if (peek() in '1'..'9') {
                while (peek() in '0'..'9') offset++
            } else {
                throw BackupJsonSyntaxException("invalid number at offset $start")
            }
            if (peek() == '.') {
                offset++
                if (peek() !in '0'..'9') throw BackupJsonSyntaxException("invalid number at offset $start")
                while (peek() in '0'..'9') offset++
            }
            if (peek() == 'e' || peek() == 'E') {
                offset++
                if (peek() == '+' || peek() == '-') offset++
                if (peek() !in '0'..'9') throw BackupJsonSyntaxException("invalid number at offset $start")
                while (peek() in '0'..'9') offset++
            }
            return BackupJsonValue.JsonNumber(text.substring(start, offset))
        }

        private fun expect(character: Char) {
            if (atEnd() || text[offset] != character) {
                throw BackupJsonSyntaxException("expected '$character' at offset $offset")
            }
            offset++
        }

        private fun peek(): Char = if (offset < text.length) text[offset] else '\u0000'

        private fun Char.isJsonWhitespace(): Boolean =
            this == ' ' || this == '\t' || this == '\n' || this == '\r'
    }
}

internal fun BackupJsonValue.asObjectOrNull(): BackupJsonValue.JsonObject? =
    this as? BackupJsonValue.JsonObject

internal fun BackupJsonValue.asArrayOrNull(): BackupJsonValue.JsonArray? =
    this as? BackupJsonValue.JsonArray

internal fun BackupJsonValue.asStringOrNull(): String? =
    (this as? BackupJsonValue.JsonString)?.value

internal fun BackupJsonValue.asBooleanOrNull(): Boolean? =
    (this as? BackupJsonValue.JsonBoolean)?.value

internal fun BackupJsonValue.asNumberOrNull(): BackupJsonValue.JsonNumber? =
    this as? BackupJsonValue.JsonNumber

internal fun BackupJsonValue.asIntOrNull(): Int? =
    asNumberOrNull()?.raw?.toIntOrNull()

internal fun BackupJsonValue.asDoubleOrNull(): Double? =
    asNumberOrNull()?.raw?.toDoubleOrNull()
