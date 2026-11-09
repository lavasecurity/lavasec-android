package com.lavasec.app.reactnative

import com.lavasec.app.foundation.filter.FilterDraftReviewRejection
import com.lavasec.app.product.FilterLibraryOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeFilterSaveOutcomeTest {
    @Test fun `only typed native capacity attempts offer the exact contextual Plus reason`() {
        val capacity = mapOf(
            FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT to "blockedDomains",
            FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT to "allowedDomains",
            FilterDraftReviewRejection.FILTER_LIMIT_REACHED to "filters",
        )
        FilterDraftReviewRejection.entries.forEach { rejection ->
            val attempt = FilterLibraryOwner.ReviewAttempt.Rejected(rejection)
            assertEquals(capacity[rejection], nativeFilterSaveUpgradeReason(attempt, hasPlus = false))
            assertNull(nativeFilterSaveUpgradeReason(attempt, hasPlus = true))
        }
        assertNull(nativeFilterSaveUpgradeReason(FilterLibraryOwner.ReviewAttempt.Unavailable, hasPlus = false))
    }
}
