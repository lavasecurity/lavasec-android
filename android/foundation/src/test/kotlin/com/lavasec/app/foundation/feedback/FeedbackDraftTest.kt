package com.lavasec.app.foundation.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackDraftTest {
    @Test
    fun `topics preserve the six pinned titles and order`() {
        assertEquals(
            listOf(
                "I can't visit a website",
                "VPN or filter doesn't work",
                "A Lava feature doesn't work",
                "Translation is not quite right",
                "I have a suggestion",
                "Something else",
            ),
            FeedbackTopic.entries.map(FeedbackTopic::title),
        )
    }

    @Test
    fun `changing away from website topic clears the affected site`() {
        val websiteDraft = FeedbackDraft(
            topic = FeedbackTopic.WEBSITE_ACCESS,
            affectedSite = "example.com",
            details = "The site did not load.",
            contactEmail = "person@example.com",
        )

        val suggestionDraft = websiteDraft.selectingTopic(FeedbackTopic.SUGGESTION)

        assertEquals("", suggestionDraft.affectedSite)
        assertEquals("", suggestionDraft.normalizedAffectedSite)
        assertEquals("The site did not load.", suggestionDraft.normalizedDetails)
        assertEquals("person@example.com", suggestionDraft.normalizedContactEmail)
    }

    @Test
    fun `changing into website topic cannot revive hidden raw site text`() {
        val nonWebsiteDraft = FeedbackDraft(
            topic = FeedbackTopic.SUGGESTION,
            affectedSite = "hidden.example",
            details = "Please add a compact status view.",
        )

        val websiteDraft = nonWebsiteDraft.selectingTopic(FeedbackTopic.WEBSITE_ACCESS)

        assertEquals("", websiteDraft.affectedSite)
        assertEquals("", websiteDraft.normalizedAffectedSite)
    }

    @Test
    fun `reselecting current website topic preserves the affected site`() {
        val websiteDraft = FeedbackDraft(
            topic = FeedbackTopic.WEBSITE_ACCESS,
            affectedSite = "example.com",
            details = "The site did not load.",
        )

        val reselected = websiteDraft.selectingTopic(FeedbackTopic.WEBSITE_ACCESS)

        assertEquals("example.com", reselected.affectedSite)
        assertEquals("example.com", reselected.normalizedAffectedSite)
    }

    @Test
    fun `normalization follows pinned scalar stripping and line rules`() {
        val draft = FeedbackDraft(
            topic = FeedbackTopic.WEBSITE_ACCESS,
            affectedSite = "  ex\r\nample\t\u200D\uFE0F.com\u2028ok\u0000 ",
            details =
                "  First\r\nSecond\rThird\u0085Fourth\u2028Fifth\u2029" +
                    "Sixth\tkeep\u0000\u200D\u2066  ",
            contactEmail = " name\r\n\u200D@example.com\t ",
        )

        assertEquals("ex ample .com ok", draft.normalizedAffectedSite)
        assertEquals(
            "First\nSecond\nThird\nFourth\nFifth\nSixth\tkeep",
            draft.normalizedDetails,
        )
        assertEquals("name @example.com", draft.normalizedContactEmail)
    }

    @Test
    fun `required fields are judged after invisible input is stripped`() {
        val invisible = "\u0000\u200B\u200D\u2066\uFE0F"
        val missingTopic = FeedbackDraft(details = invisible)
        val missingWebsiteContext = FeedbackDraft(
            topic = FeedbackTopic.WEBSITE_ACCESS,
            affectedSite = invisible,
            details = invisible,
        )

        assertEquals(
            listOf(
                FeedbackDraftIssue.TOPIC_REQUIRED,
                FeedbackDraftIssue.DETAILS_REQUIRED,
            ),
            missingTopic.validationIssues,
        )
        assertEquals(
            listOf(
                FeedbackDraftIssue.AFFECTED_SITE_REQUIRED,
                FeedbackDraftIssue.DETAILS_REQUIRED,
            ),
            missingWebsiteContext.validationIssues,
        )
        assertFalse(missingWebsiteContext.isReadyForReview)
    }

    @Test
    fun `site is required only for website topic and email remains optional`() {
        val suggestion = FeedbackDraft(
            topic = FeedbackTopic.SUGGESTION,
            affectedSite = "must-not-leak.example",
            details = "Please add a compact status view.",
            contactEmail = " \u200B ",
        )

        assertEquals(emptyList<FeedbackDraftIssue>(), suggestion.validationIssues)
        assertTrue(suggestion.isReadyForReview)
        assertEquals("", suggestion.normalizedAffectedSite)
        assertNull(suggestion.normalizedContactEmail)
    }

    @Test
    fun `limits count extended grapheme clusters without splitting them`() {
        val grapheme = "e\u0301"
        val draft = FeedbackDraft(
            topic = FeedbackTopic.WEBSITE_ACCESS,
            affectedSite = grapheme.repeat(300) + "x",
            details = grapheme.repeat(5_000) + "x",
            contactEmail = grapheme.repeat(320) + "x",
        )

        assertEquals(grapheme.repeat(300), draft.normalizedAffectedSite)
        assertEquals(grapheme.repeat(5_000), draft.normalizedDetails)
        assertEquals(grapheme.repeat(320), draft.normalizedContactEmail)
    }
}
