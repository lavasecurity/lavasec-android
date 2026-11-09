package com.lavasec.app.product

import com.lavasec.app.platform.FilterSharePayload

/**
 * The import preview gate for a decoded share payload, staged here so the app-side owner can
 * validate every source id against the signed catalog before anything could be imported.
 *
 * The full share/import UI ships with the platform import path (the overview must not show
 * inert buttons until then); this slice ships the contract (platform-contracts) plus this
 * preview validation, because a decoded payload must never be trusted until every source id
 * resolves inside the pinned catalog — the same requirement the compile plan enforces for a
 * saved library (`AuthorizedFilterCompilePlan.create` rejects unknown source ids).
 *
 * Domains were already validated by the codec with the exact local-editing validator
 * (`DomainName.parse`); the preview only resolves catalog membership.
 */
sealed interface FilterImportPreview {
    /**
     * The payload is safe to stage: every source id resolves in the signed catalog. The
     * blocked domains arrived through the codec's validator; the caller shows them verbatim.
     */
    data class Ready(val payload: FilterSharePayload) : FilterImportPreview

    /** One or more source ids are not part of the signed catalog — never preview them. */
    data class Rejected(val unknownSourceIds: Set<String>) : FilterImportPreview
}

object FilterImportPreflight {
    fun preview(
        payload: FilterSharePayload,
        catalogSourceIds: Set<String>,
        /**
         * Custom blocklist ids the payload itself carries. A custom source is not a catalog id, so
         * it resolves against the payload's own definitions rather than the signed catalog.
         */
        customSourceIds: Set<String> = emptySet(),
    ): FilterImportPreview {
        val known = catalogSourceIds + customSourceIds
        val unknown = payload.sourceIds.filter { it !in known }.toSet()
        return if (unknown.isEmpty()) {
            FilterImportPreview.Ready(payload)
        } else {
            FilterImportPreview.Rejected(unknown)
        }
    }
}
