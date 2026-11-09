package com.lavasec.app.foundation.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareableFilterImportReviewTest {
    private val target = ShareableFilterConfiguration(
        enabledBlocklistIDs = setOf("a"),
        blockedDomains = emptySet(),
        allowedDomains = emptySet(),
    )

    private val capabilities = ShareableFilterImportCapabilities(
        availableCuratedBlocklistIDs = setOf("a", "b"),
        reservedBlocklistIDs = emptySet(),
        allowsCustomBlocklists = true,
        maxBlockedDomains = 25,
        maxAllowedDomains = 25,
    )

    private val shared = ShareableFilterConfiguration(
        enabledBlocklistIDs = setOf("a", "b"),
        blockedDomains = setOf("ads.example", "not a domain"),
        allowedDomains = setOf("good.example"),
    )

    @Test
    fun `the review shows additions, removals, and invalid entries`() {
        val review = ShareableFilterImportReview.review(shared, target, capabilities)

        assertEquals(setOf("a", "b"), review.plan.applied.enabledBlocklistIDs)
        assertEquals(setOf("ads.example"), review.plan.applied.blockedDomains)
        assertEquals(setOf("good.example"), review.plan.applied.allowedDomains)
        assertEquals(1, review.plan.droppedCount(ShareableFilterImportPlan.DroppedEntry.Kind.INVALID_DOMAIN))

        assertEquals(listOf("b"), review.replacement.selectionDiff.addedBlocklistIDs)
        assertEquals(listOf("ads.example"), review.replacement.selectionDiff.addedBlockedDomains)
        assertEquals(listOf("good.example"), review.replacement.selectionDiff.addedAllowedDomains)
        assertTrue(review.replacement.selectionDiff.removedBlocklistIDs.isEmpty())
        assertTrue(review.canConfirm)
    }

    @Test
    fun `an import produces a reviewed draft and never mutates the target`() {
        val review = ShareableFilterImportReview.review(shared, target, capabilities)

        assertEquals(target, review.target)
        assertNotEquals(review.target, review.plan.applied)

        val confirmation = review.confirmReplace(
            currentTarget = target,
            targetIsActive = false,
            confirmedActiveReplacement = false,
        )
        assertTrue(confirmation is ShareableFilterImportConfirmation.ReplaceContents)
        assertEquals(
            review.plan.applied,
            (confirmation as ShareableFilterImportConfirmation.ReplaceContents).applied,
        )
        assertEquals("the reviewed target is untouched", target, review.target)
    }

    @Test
    fun `replacing the active filter needs explicit confirmation`() {
        val review = ShareableFilterImportReview.review(shared, target, capabilities)

        assertEquals(
            ShareableFilterImportRefusal.ActiveReplacementNotConfirmed,
            (
                review.confirmReplace(target, targetIsActive = true, confirmedActiveReplacement = false)
                    as ShareableFilterImportConfirmation.Refused
                ).reason,
        )
        assertTrue(
            review.confirmReplace(target, targetIsActive = true, confirmedActiveReplacement = true)
                is ShareableFilterImportConfirmation.ReplaceContents,
        )
    }

    @Test
    fun `a review is refused when the target changed since it was taken`() {
        val review = ShareableFilterImportReview.review(shared, target, capabilities)
        val changedTarget = target.copy(blockedDomains = setOf("someone-else.example"))

        assertEquals(
            ShareableFilterImportRefusal.StaleReview,
            (
                review.confirmReplace(changedTarget, targetIsActive = false, confirmedActiveReplacement = false)
                    as ShareableFilterImportConfirmation.Refused
                ).reason,
        )
    }

    @Test
    fun `an import that reconciles to nothing is refused`() {
        val empty = ShareableFilterConfiguration(allowedDomains = emptySet())
        val review = ShareableFilterImportReview.review(empty, target, capabilities)

        assertTrue(!review.canConfirm)
        assertEquals(
            ShareableFilterImportRefusal.NothingToImport,
            (
                review.confirmAddAsNewFilter() as ShareableFilterImportConfirmation.Refused
                ).reason,
        )
    }

    @Test
    fun `a plan that would be trimmed again is refused rather than partially applied`() {
        val constrained = ShareableFilterImportCapabilities(
            availableCuratedBlocklistIDs = setOf("a"),
            allowsCustomBlocklists = true,
            maxBlockedDomains = 1,
        )
        // A plan whose applied subset is larger than a second reconciliation would allow.
        val oversizedPlan = ShareableFilterImportPlan(
            applied = ShareableFilterConfiguration(
                enabledBlocklistIDs = setOf("a"),
                blockedDomains = setOf("one.example", "two.example"),
                allowedDomains = emptySet(),
            ),
            dropped = emptyList(),
        )
        val review = ShareableFilterImportReview(
            shared = shared,
            target = target,
            capabilities = constrained,
            plan = oversizedPlan,
            replacement = oversizedPlan.applied.replacementSummary(target),
            reviewToken = "unused-on-this-branch",
        )

        assertEquals(
            ShareableFilterImportRefusal.PlanNotFullyAccepted,
            (
                review.confirmReplace(target, targetIsActive = false, confirmedActiveReplacement = false)
                    as ShareableFilterImportConfirmation.Refused
                ).reason,
        )
    }

    @Test
    fun `adding a reviewed import as a new filter returns the sanitized content`() {
        val review = ShareableFilterImportReview.review(shared, target, capabilities)

        val confirmation = review.confirmAddAsNewFilter()
            as ShareableFilterImportConfirmation.AddNewFilter

        assertEquals(setOf("ads.example"), confirmation.applied.blockedDomains)
        assertEquals(setOf("good.example"), confirmation.applied.allowedDomains)
    }
}
