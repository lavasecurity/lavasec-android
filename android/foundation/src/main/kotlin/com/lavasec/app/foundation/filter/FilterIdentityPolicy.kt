package com.lavasec.app.foundation.filter

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UCharacterCategory
import com.ibm.icu.lang.UProperty
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.text.Normalizer2
import java.util.Locale

/**
 * Canonical filter name identity, ported from iOS `FilterIdentityPolicy` (LavaSecKit).
 *
 * A filter's user-facing name is metadata: it is independent of rule compilation and of the
 * filter id. Names follow one validation and one canonical spelling on every surface so the
 * library list, the share/import pickers, and the review rows can never show two
 * indistinguishable filters.
 *
 * The Unicode work uses bundled ICU4J (the same dependency the feedback and IDNA owners use)
 * so behavior is identical on every supported Android API level instead of drifting with the
 * platform's ICU version. Swift compares `String`s for canonical equivalence; [canonicallyEqual]
 * reproduces that with an NFC comparison.
 */
object FilterIdentityPolicy {
    /**
     * Canonical spelling for newly entered names: NFC-composed with surrounding Unicode
     * whitespace removed. This is what a committed rename stores.
     */
    fun normalizedName(raw: String): String =
        trimUnicodeWhitespace(NORMALIZER.normalize(raw))

    /**
     * Validate on commit, not during IME composition. Existing stored names are deliberately
     * decoded unchanged; this rule governs newly entered names.
     *
     * Rejects any control character, then requires at least one base character where a base is a
     * letter or a decimal digit, a combining mark may only follow a base, and a space resets the
     * base requirement. Every other scalar is rejected.
     */
    fun isValidName(raw: String): Boolean {
        var index = 0
        while (index < raw.length) {
            val scalar = raw.codePointAt(index)
            if (UCharacter.getType(scalar) == UCharacterCategory.CONTROL.toInt()) return false
            index += Character.charCount(scalar)
        }
        val value = normalizedName(raw)
        if (value.isEmpty()) return false
        var hasBase = false
        index = 0
        while (index < value.length) {
            val scalar = value.codePointAt(index)
            when {
                isNonBaseCharacter(scalar) -> if (!hasBase) return false
                isLetter(scalar) || isDecimalDigit(scalar) -> hasBase = true
                scalar == SPACE -> hasBase = false
                else -> return false
            }
            index += Character.charCount(scalar)
        }
        return true
    }

    /**
     * An emoji-only edit retains the exact stored name, including legacy punctuation. An actual
     * rename must meet today's entry policy and use the canonical trimmed spelling.
     */
    fun nameForEdit(raw: String, savedName: String): String? {
        if (canonicallyEqual(raw, savedName)) return savedName
        return if (isValidName(raw)) normalizedName(raw) else null
    }

    /** Swift `String ==` canonical equivalence, reproduced through NFC. */
    fun canonicallyEqual(a: String, b: String): Boolean =
        (a == b) || NORMALIZER.normalize(a) == NORMALIZER.normalize(b)

    /**
     * Exactly one extended grapheme; accepts flags, keycaps, variation selectors and joined
     * families/professions. Plain digits and text are not emoji.
     */
    fun isValidEmoji(value: String): Boolean {
        if (value.isEmpty()) return false
        if (graphemes(value).size != 1) return false
        val scalars = value.codePoints().toArray()
        if (scalars.size == 2 && scalars.all { it in REGIONAL_INDICATOR_RANGE }) return true
        if (scalars.last() == COMBINING_ENCLOSING_KEYCAP) {
            return scalars.size <= 3 &&
                scalars[0] in KEYCAP_BASES &&
                (scalars.size == 2 || scalars[1] == VARIATION_SELECTOR_16)
        }
        if (isEmojiModifier(scalars[0]) ||
            scalars.last() == ZERO_WIDTH_JOINER ||
            scalars.any { it in REGIONAL_INDICATOR_RANGE }
        ) {
            return false
        }
        for (index in scalars.indices) {
            if (scalars[index] == ZERO_WIDTH_JOINER) {
                val next = scalars.getOrNull(index + 1)
                if (index <= 0 || next == null || !isEmoji(next) || isEmojiModifier(next)) {
                    return false
                }
            }
        }
        if (!scalars.any { isEmojiPresentation(it) || it == VARIATION_SELECTOR_16 }) return false
        if (!isEmoji(scalars[0])) return false
        return scalars.all { scalar ->
            isEmoji(scalar) ||
                scalar == ZERO_WIDTH_JOINER ||
                scalar == VARIATION_SELECTOR_16 ||
                scalar in TAG_RANGE
        }
    }

    /**
     * Defaults are stable across locale, relaunch, import and backup. Never use a randomized
     * hash for persistent visual identity.
     */
    fun defaultEmoji(id: String, name: String): String {
        when (name.lowercase(Locale.ROOT)) {
            "core" -> return "🌱"
            "balanced" -> return "🪴"
            "extra" -> return "💐"
        }
        val hash = id.toByteArray(Charsets.UTF_8).fold(FNV_OFFSET_BASIS) { accumulator, byte ->
            (accumulator xor byte.toUByte().toULong()) * FNV_PRIME
        }
        return EMOJI_POOL[(hash % EMOJI_POOL.size.toULong()).toInt()]
    }

    /** The presentation identity used by notifications and intents; stored names are unchanged. */
    fun displayName(name: String, emoji: String): String =
        if (isValidEmoji(emoji)) "$emoji $name" else name

    private fun graphemes(value: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        val result = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            result.add(value.substring(start, end))
            start = end
            end = iterator.next()
        }
        return result
    }

    private fun isEmoji(scalar: Int): Boolean =
        UCharacter.hasBinaryProperty(scalar, UProperty.EMOJI)

    private fun isEmojiModifier(scalar: Int): Boolean =
        UCharacter.hasBinaryProperty(scalar, UProperty.EMOJI_MODIFIER)

    private fun isEmojiPresentation(scalar: Int): Boolean =
        UCharacter.hasBinaryProperty(scalar, UProperty.EMOJI_PRESENTATION)

    private fun isLetter(scalar: Int): Boolean = UCharacter.isLetter(scalar)

    private fun isDecimalDigit(scalar: Int): Boolean =
        UCharacter.getType(scalar) == UCharacterCategory.DECIMAL_DIGIT_NUMBER.toInt()

    private fun isNonBaseCharacter(scalar: Int): Boolean = when (UCharacter.getType(scalar)) {
        UCharacterCategory.NON_SPACING_MARK.toInt(),
        UCharacterCategory.COMBINING_SPACING_MARK.toInt(),
        UCharacterCategory.ENCLOSING_MARK.toInt(),
        -> true

        else -> false
    }

    private fun trimUnicodeWhitespace(value: String): String {
        var start = 0
        while (start < value.length) {
            val scalar = value.codePointAt(start)
            if (!UCharacter.hasBinaryProperty(scalar, UProperty.WHITE_SPACE)) break
            start += Character.charCount(scalar)
        }
        var end = value.length
        while (end > start) {
            val scalar = value.codePointBefore(end)
            if (!UCharacter.hasBinaryProperty(scalar, UProperty.WHITE_SPACE)) break
            end -= Character.charCount(scalar)
        }
        return value.substring(start, end)
    }

    private const val SPACE = 0x20
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val VARIATION_SELECTOR_16 = 0xFE0F
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
    private val REGIONAL_INDICATOR_RANGE = 0x1F1E6..0x1F1FF
    private val TAG_RANGE = 0xE0020..0xE007F
    private val KEYCAP_BASES: Set<Int> =
        ("0123456789#*").codePoints().toArray().toSet()
    private val EMOJI_POOL = listOf(
        "🌿", "🪴", "🧩", "🧭", "🎒", "🏡", "🚲", "☕️", "📚", "🔑", "🎨", "🌻",
    )
    private const val FNV_OFFSET_BASIS = 14695981039346656037uL
    private const val FNV_PRIME = 1099511628211uL

    private val NORMALIZER = Normalizer2.getNFCInstance()
}
