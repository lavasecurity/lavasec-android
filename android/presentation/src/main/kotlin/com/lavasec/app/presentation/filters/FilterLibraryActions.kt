package com.lavasec.app.presentation.filters

import com.lavasec.app.foundation.filter.ManualRuleEditResult
import com.lavasec.app.foundation.filter.ManualRuleTarget

/**
 * Mutation controls the Filters surfaces may own. Each bundle is null when the product cannot
 * supply mutations (pre-onboarding or an unavailable confirmed filter), and the screens then
 * drop every control instead of rendering an inert one. The actual durable work runs inside
 * the generation-fenced owners; these callbacks only forward user intent. The domain-edit
 * callbacks return the exact editor result so the sheets can render the precise rejection
 * inline without polling any state.
 *
 * iOS anchors: `FiltersView.swift` (overview), `FilterLibraryView.swift` (AllFiltersView),
 * `FilterMyListView.swift` (detail), `FilterReviewFlowView.swift` (review + preparation).
 */
data class FilterLibraryActions(
    val onApplyFilterRequest: (filterId: String) -> Unit = {},
    val onOpenDetail: (filterId: String) -> Unit = {},
    val onOpenLibrary: () -> Unit = {},
    val onBeginEdit: (filterId: String) -> Unit = {},
    val onDiscardDraft: (filterId: String) -> Unit = {},
    val onOpenReview: (filterId: String) -> Unit = {},
    val onCommitReview: (filterId: String) -> Unit = {},
    val onCancelReview: () -> Unit = {},
    val onAddBlockedDomain: (suspend (filterId: String, domain: String) -> ManualRuleEditResult?) =
        { _, _ -> null },
    val onAddAllowedException: (suspend (filterId: String, domain: String) -> ManualRuleEditResult?) =
        { _, _ -> null },
    val onRemoveDomain: (filterId: String, domain: String, target: ManualRuleTarget) -> Unit =
        { _, _, _ -> },
    val onToggleSource: (suspend (filterId: String, sourceId: String, enabled: Boolean) -> Boolean) =
        { _, _, _ -> false },
    val onCreateFilter: (name: String, duplicateFromId: String?) -> Unit = { _, _ -> },
    val onRenameFilter: (filterId: String, name: String) -> Unit = { _, _ -> },
    val onDeleteFilters: (filterIds: Set<String>) -> Unit = {},
    val onRestoreDefaults: () -> Unit = {},
    val onRetryPreparation: () -> Unit = {},
    val onKeepCurrentAfterFailure: () -> Unit = {},
    val onConsumePreparationSuccess: () -> Unit = {},
)

/**
 * Honest in-flight and failure facts for whole-filter Apply, derived from the switch owner
 * state. While a switch is applying every Apply control is disabled; a failed switch names
 * its target so the error copy stays truthful.
 */
sealed interface FilterProfileSwitchUiState {
    data object Idle : FilterProfileSwitchUiState

    data class Applying(val targetFilterId: String) : FilterProfileSwitchUiState

    data class Failed(
        val targetFilterId: String,
        val failure: FilterProfileSwitchFailureUi,
    ) : FilterProfileSwitchUiState
}

enum class FilterProfileSwitchFailureUi {
    /** The target filter is frozen (over-cap, read-only) and cannot be applied. */
    FROZEN_TARGET,

    /** Any other rejection, commit failure, or supersession. */
    COMMIT,
}
