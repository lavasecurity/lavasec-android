package com.lavasec.app.foundation.feedback

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UCharacterCategory
import com.ibm.icu.lang.UProperty
import com.ibm.icu.text.BreakIterator
import java.util.Locale

enum class FeedbackTopic(
    val title: String,
) {
    WEBSITE_ACCESS("I can't visit a website"),
    VPN_OR_FILTER_ISSUE("VPN or filter doesn't work"),
    FEATURE_ISSUE("A Lava feature doesn't work"),
    TRANSLATION_ISSUE("Translation is not quite right"),
    SUGGESTION("I have a suggestion"),
    OTHER("Something else"),
}

enum class FeedbackDraftIssue {
    TOPIC_REQUIRED,
    AFFECTED_SITE_REQUIRED,
    DETAILS_REQUIRED,
}

data class FeedbackDraft(
    val topic: FeedbackTopic? = null,
    val affectedSite: String = "",
    val details: String = "",
    val contactEmail: String? = null,
) {
    val normalizedAffectedSite: String
        get() = if (topic == FeedbackTopic.WEBSITE_ACCESS) {
            FeedbackTextNormalizer.normalize(
                value = affectedSite,
                maxGraphemes = MAX_AFFECTED_SITE_GRAPHEMES,
                allowsLineBreaks = false,
            )
        } else {
            ""
        }

    val normalizedDetails: String
        get() = FeedbackTextNormalizer.normalize(
            value = details,
            maxGraphemes = MAX_DETAILS_GRAPHEMES,
            allowsLineBreaks = true,
        )

    val normalizedContactEmail: String?
        get() = contactEmail
            ?.let {
                FeedbackTextNormalizer.normalize(
                    value = it,
                    maxGraphemes = MAX_CONTACT_EMAIL_GRAPHEMES,
                    allowsLineBreaks = false,
                )
            }
            ?.takeIf(String::isNotEmpty)

    val validationIssues: List<FeedbackDraftIssue>
        get() = buildList {
            if (topic == null) {
                add(FeedbackDraftIssue.TOPIC_REQUIRED)
            }
            if (
                topic == FeedbackTopic.WEBSITE_ACCESS &&
                normalizedAffectedSite.isEmpty()
            ) {
                add(FeedbackDraftIssue.AFFECTED_SITE_REQUIRED)
            }
            if (normalizedDetails.isEmpty()) {
                add(FeedbackDraftIssue.DETAILS_REQUIRED)
            }
        }

    val isReadyForReview: Boolean
        get() = validationIssues.isEmpty()

    fun selectingTopic(topic: FeedbackTopic): FeedbackDraft = copy(
        topic = topic,
        affectedSite = if (
            this.topic == FeedbackTopic.WEBSITE_ACCESS &&
            topic == FeedbackTopic.WEBSITE_ACCESS
        ) {
            affectedSite
        } else {
            ""
        },
    )

    companion object {
        const val MAX_AFFECTED_SITE_GRAPHEMES = 300
        const val MAX_DETAILS_GRAPHEMES = 5_000
        const val MAX_CONTACT_EMAIL_GRAPHEMES = 320
    }
}

private object FeedbackTextNormalizer {
    fun normalize(
        value: String,
        maxGraphemes: Int,
        allowsLineBreaks: Boolean,
    ): String {
        require(maxGraphemes >= 0)
        val sanitized = sanitize(value, allowsLineBreaks)
        val trimmed = trimUnicodeWhitespace(sanitized)
        return prefixGraphemes(trimmed, maxGraphemes)
    }

    private fun sanitize(
        value: String,
        allowsLineBreaks: Boolean,
    ): String {
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val scalar = value.codePointAt(index)
            var scalarWidth = Character.charCount(scalar)

            if (scalar == CARRIAGE_RETURN) {
                val nextIndex = index + scalarWidth
                if (
                    nextIndex < value.length &&
                    value.codePointAt(nextIndex) == LINE_FEED
                ) {
                    scalarWidth += Character.charCount(LINE_FEED)
                }
                appendLineBreak(output, allowsLineBreaks)
            } else if (isLineBreak(scalar)) {
                appendLineBreak(output, allowsLineBreaks)
            } else if (scalar == HORIZONTAL_TAB) {
                output.append(if (allowsLineBreaks) '\t' else ' ')
            } else if (!isStrippable(scalar)) {
                output.appendCodePoint(scalar)
            }

            index += scalarWidth
        }
        return output.toString()
    }

    private fun appendLineBreak(
        output: StringBuilder,
        allowsLineBreaks: Boolean,
    ) {
        output.append(if (allowsLineBreaks) '\n' else ' ')
    }

    private fun isLineBreak(scalar: Int): Boolean = when (scalar) {
        LINE_FEED,
        VERTICAL_TAB,
        FORM_FEED,
        NEXT_LINE,
        LINE_SEPARATOR,
        PARAGRAPH_SEPARATOR,
        -> true

        else -> false
    }

    private fun isStrippable(scalar: Int): Boolean {
        val category = UCharacter.getType(scalar)
        return category == UCharacterCategory.CONTROL.toInt() ||
            category == UCharacterCategory.FORMAT.toInt() ||
            UCharacter.hasBinaryProperty(scalar, UProperty.DEFAULT_IGNORABLE_CODE_POINT) ||
            UCharacter.hasBinaryProperty(scalar, UProperty.BIDI_CONTROL)
    }

    private fun trimUnicodeWhitespace(value: String): String {
        var start = 0
        while (start < value.length) {
            val scalar = value.codePointAt(start)
            if (!isWhitespace(scalar)) break
            start += Character.charCount(scalar)
        }

        var end = value.length
        while (end > start) {
            val scalar = value.codePointBefore(end)
            if (!isWhitespace(scalar)) break
            end -= Character.charCount(scalar)
        }
        return value.substring(start, end)
    }

    private fun isWhitespace(scalar: Int): Boolean =
        UCharacter.hasBinaryProperty(scalar, UProperty.WHITE_SPACE)

    private fun prefixGraphemes(
        value: String,
        maxGraphemes: Int,
    ): String {
        if (value.isEmpty() || maxGraphemes == 0) return ""

        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        var boundary = iterator.first()
        repeat(maxGraphemes) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return value
            boundary = next
        }
        return if (boundary == value.length) value else value.substring(0, boundary)
    }

    private const val HORIZONTAL_TAB = 0x0009
    private const val LINE_FEED = 0x000A
    private const val VERTICAL_TAB = 0x000B
    private const val FORM_FEED = 0x000C
    private const val CARRIAGE_RETURN = 0x000D
    private const val NEXT_LINE = 0x0085
    private const val LINE_SEPARATOR = 0x2028
    private const val PARAGRAPH_SEPARATOR = 0x2029
}
