package com.lavasec.app.foundation.domain

enum class DomainValidationError {
    EMPTY,
    TOO_LONG,
    NEEDS_AT_LEAST_TWO_LABELS,
    INVALID_LABEL,
    IP_ADDRESS_NOT_ALLOWED,
}

class DomainValidationException(
    val reason: DomainValidationError,
    val invalidLabel: String? = null,
) : IllegalArgumentException(
    if (invalidLabel == null) reason.name else "${reason.name}: $invalidLabel",
)

/** A validated DNS hostname stored in the canonical form used by filtering. */
class DomainName private constructor(val value: String) {
    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is DomainName && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun parse(rawValue: String): DomainName = DomainName(normalize(rawValue))

        internal fun normalize(rawValue: String): String {
            var candidate = rawValue.trim(::isFoundationWhitespaceOrNewline)
            if (candidate.startsWith('.')) candidate = candidate.drop(1)
            while (candidate.endsWith('.')) candidate = candidate.dropLast(1)
            candidate = normalizedIdnaHostname(candidate.lowercase())

            if (candidate.isEmpty()) reject(DomainValidationError.EMPTY)
            if (looksLikeIpAddress(candidate)) reject(DomainValidationError.IP_ADDRESS_NOT_ALLOWED)
            if (candidate.toByteArray(Charsets.UTF_8).size > 253) reject(DomainValidationError.TOO_LONG)

            val labels = candidate.split('.')
            if (labels.size < 2) reject(DomainValidationError.NEEDS_AT_LEAST_TWO_LABELS)
            for (label in labels) {
                if (!isValidLabel(label)) reject(DomainValidationError.INVALID_LABEL, label)
            }
            return candidate
        }

        private fun normalizedIdnaHostname(value: String): String {
            if (value.all { it.code < 0x80 }) return value
            return try {
                IdnaHostCanonicalizer.toAscii(value)
            } catch (_: IllegalArgumentException) {
                value
            }
        }

        private fun isFoundationWhitespaceOrNewline(character: Char): Boolean = when (character.code) {
            in 0x0009..0x000d,
            0x0020,
            0x0085,
            0x00a0,
            0x1680,
            in 0x2000..0x200b,
            in 0x2028..0x2029,
            0x202f,
            0x205f,
            0x3000,
            -> true
            else -> false
        }

        private fun looksLikeIpAddress(value: String): Boolean {
            if (':' in value) return true
            val pieces = value.split('.')
            return pieces.size == 4 && pieces.all { piece ->
                piece.isNotEmpty() && piece.all { it in '0'..'9' }
            }
        }

        private fun isValidLabel(label: String): Boolean {
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > 63 || bytes.first() == HYPHEN || bytes.last() == HYPHEN) {
                return false
            }
            return bytes.all { byte -> byte in LOWERCASE || byte in DIGITS || byte == HYPHEN }
        }

        private fun reject(reason: DomainValidationError, label: String? = null): Nothing =
            throw DomainValidationException(reason, label)

        private val LOWERCASE = 'a'.code.toByte()..'z'.code.toByte()
        private val DIGITS = '0'.code.toByte()..'9'.code.toByte()
        private val HYPHEN = '-'.code.toByte()
    }
}
