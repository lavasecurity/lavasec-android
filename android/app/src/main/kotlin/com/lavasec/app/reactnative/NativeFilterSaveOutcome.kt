package com.lavasec.app.reactnative

import com.lavasec.app.product.FilterLibraryOwner

/** Only the authenticated attempt's native typed capacity refusal may open contextual Plus. */
internal fun nativeFilterSaveUpgradeReason(
    attempt: FilterLibraryOwner.ReviewAttempt,
    hasPlus: Boolean,
): String? {
    val rejection = (attempt as? FilterLibraryOwner.ReviewAttempt.Rejected)?.reason ?: return null
    return filterDraftUpgradeReason(rejection, hasPlus).takeIf { it.isNotEmpty() }
}
