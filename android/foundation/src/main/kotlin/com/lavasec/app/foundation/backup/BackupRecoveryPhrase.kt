package com.lavasec.app.foundation.backup

import java.security.SecureRandom
import java.util.Locale

private fun secureRecoveryPhraseIndex(upperBound: Int): Int {
    require(upperBound > 0) { "upperBound must be positive" }
    return SecureRandom().nextInt(upperBound)
}

/**
 * Generates and normalizes the human-entered recovery phrase used by backup key slots.
 *
 * Ported from the canonical iOS `BackupRecoveryPhrase` (pinned at `c4658d9`). The parsing rules are
 * what make a phrase round-trip between clients: numbering and separators are collapsed, words are
 * trimmed and lowercased, and the canonical form is the space-joined non-empty words. No phrase is
 * ever stored in the state machine.
 */
object BackupRecoveryPhrase {

    /** Number of generated tokens in a recovery phrase. */
    const val WORD_COUNT = 8

    private val NUMBERING = Regex("\\b\\d{1,2}[.)]")
    private val SEPARATORS = Regex("[-_,;:\\n\\r\\t]+")
    private val WHITESPACE = Regex("\\s+")

    private val CONSONANTS = "bcdfghjklmnprstv".toCharArray()
    private val VOWELS = "aeiouy".toCharArray()

    /**
     * Generates a space-separated phrase from pronounceable tokens. [randomIndex] is injected so the
     * generator is deterministic in tests; production supplies cryptographically random indices.
     */
    fun generate(randomIndex: (upperBound: Int) -> Int = ::secureRecoveryPhraseIndex): String =
        (0 until WORD_COUNT).joinToString(" ") {
            buildString {
                append(CONSONANTS[randomIndex(CONSONANTS.size)])
                append(VOWELS[randomIndex(VOWELS.size)])
                append(CONSONANTS[randomIndex(CONSONANTS.size)])
                append(VOWELS[randomIndex(VOWELS.size)])
            }
        }

    /**
     * Parses pasted or typed phrase text into lowercase words, accepting common separators and
     * numbered lists ("1. word", "2) word").
     */
    fun words(from: String): List<String> {
        val withoutNumbers = NUMBERING.replace(from, " ")
        val separated = SEPARATORS.replace(withoutNumbers, " ")
        val collapsed = WHITESPACE.replace(separated, " ")
        return collapsed.trim()
            .split(" ")
            .filter { it.isNotEmpty() }
            .map { normalizedWord(it) }
    }

    /** Maps parsed words into the requested slots and pads missing positions with empty strings. */
    fun fillSlots(from: String, count: Int = WORD_COUNT): List<String> {
        val parsed = words(from)
        return (0 until count).map { index -> parsed.getOrElse(index) { "" } }
    }

    /** Joins normalized, non-empty words into the canonical space-separated phrase form. */
    fun phrase(from: List<String>): String = from
        .map { normalizedWord(it) }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    /** Trims surrounding whitespace and lowercases one recovery word, locale-independently. */
    fun normalizedWord(word: String): String = word.trim().lowercase(Locale.ROOT)
}
