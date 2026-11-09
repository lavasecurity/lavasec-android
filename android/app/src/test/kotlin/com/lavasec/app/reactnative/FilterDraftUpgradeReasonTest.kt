package com.lavasec.app.reactnative

import com.lavasec.app.foundation.filter.FilterDraftReviewRejection
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins canonical `filterEditing.upgradeReason`: typed capacity only, never safety/read/auth. */
class FilterDraftUpgradeReasonTest {
    @Test
    fun `capacity refusals map to their Plus reason`() {
        assertEquals(
            "blockedDomains",
            filterDraftUpgradeReason(FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT, hasPlus = false),
        )
        assertEquals(
            "allowedDomains",
            filterDraftUpgradeReason(FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT, hasPlus = false),
        )
        assertEquals(
            "filters",
            filterDraftUpgradeReason(FilterDraftReviewRejection.FILTER_LIMIT_REACHED, hasPlus = false),
        )
    }

    @Test
    fun `safety read and auth refusals never offer an upgrade`() {
        listOf(
            FilterDraftReviewRejection.NO_CHANGES,
            FilterDraftReviewRejection.FILTER_GONE,
            FilterDraftReviewRejection.FILTER_FROZEN,
            FilterDraftReviewRejection.INVALID_NAME,
            FilterDraftReviewRejection.DUPLICATE_NAME,
            FilterDraftReviewRejection.INVALID_EMOJI,
            null,
        ).forEach { rejection ->
            assertEquals("", filterDraftUpgradeReason(rejection, hasPlus = false))
        }
    }

    @Test
    fun `an entitled user has no higher tier to upgrade to`() {
        assertEquals(
            "",
            filterDraftUpgradeReason(FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT, hasPlus = true),
        )
        assertEquals(
            "",
            filterDraftUpgradeReason(FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT, hasPlus = true),
        )
        assertEquals(
            "",
            filterDraftUpgradeReason(FilterDraftReviewRejection.FILTER_LIMIT_REACHED, hasPlus = true),
        )
    }
}
