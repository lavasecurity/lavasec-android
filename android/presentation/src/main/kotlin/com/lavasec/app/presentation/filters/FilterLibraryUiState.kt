package com.lavasec.app.presentation.filters

import com.lavasec.app.foundation.subscription.FeatureLimits

import com.lavasec.app.foundation.filter.FilterDraftPolicy
import com.lavasec.app.foundation.filter.FilterDraftReviewRejection
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterLibraryPolicy
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.FilterSourceCategory

/**
 * Read-only UI facts for the saved-filter library. Built once per credential graph from
 * verified product state; every screen renders exactly these facts and every mutation goes
 * through the generation-fenced owners, never by mutating these facts.
 *
 * iOS anchor: `FilterLibraryView.swift` (`AllFiltersView`) lists the saved filters with an
 * in-effect marker, per-filter rule summaries, and the frozen (read-only) treatment; the
 * overview (`FiltersView.swift`) shows the active filter under "What's filtering?".
 */
data class FilterLibraryState(
    val filters: List<FilterRowUi>,
    val activeFilterId: String,
    val maxFilters: Int,
) {
    init {
        require(filters.isNotEmpty()) { "filter library must contain at least one filter" }
        require(filters.map(FilterRowUi::id).distinct().size == filters.size) {
            "filter ids must be unique"
        }
        require(activeFilterId in filters.map(FilterRowUi::id)) {
            "active filter id must be present in the library"
        }
        require(maxFilters >= 1) { "filter cap must be positive" }
    }

    fun filter(id: String): FilterRowUi? = filters.firstOrNull { it.id == id }

    val activeFilter: FilterRowUi
        get() = checkNotNull(filter(activeFilterId))
}

/** One saved filter row: identity, in-effect/frozen flags, and the honest rule summary. */
data class FilterRowUi(
    val id: String,
    val name: String,
    val active: Boolean,
    val frozen: Boolean,
    /** True when the filter has no sources and no manual rules — it blocks nothing. */
    val blocksNothing: Boolean,
    /**
     * Compiled block-rule count of the in-effect filter. Non-null only for the active filter —
     * the Android artifact is compiled once for the loaded filter, so other filters have no
     * honest rule count to show (their rows show the not-in-effect line instead).
     */
    val activeRuleCount: Int?,
)

/**
 * Catalog facts needed to name each filter's sources. The names are third-party product names
 * from the signed catalog (e.g. "StevenBlack Unified Hosts") and are shown verbatim, matching
 * the iOS surfaces that render catalog names raw.
 */
data class FilterSourceCatalogEntry(
    val id: String,
    val name: String,
    val category: FilterSourceCategory,
    /** The source URL, used to collapse an added custom URL onto its curated source. */
    val sourceUrl: String = "",
    val ruleCount: Int? = null,
)

/** One source picker row of the detail screen, with its draft selection state. */
data class FilterSourceOptionUi(
    val id: String,
    val name: String,
    val category: FilterSourceCategory,
    val selected: Boolean,
    val ruleCount: Int? = null,
)

/**
 * One filter's detail ("My list"): the two shelves (block sources + blocked domains, allowed
 * exceptions) plus the in-effect/frozen/empty facts. Derived from the saved filter overlaid
 * with its per-filter draft, so the screen never reads draft state directly.
 */
data class FilterDetailState(
    val filterId: String,
    val name: String,
    val inEffect: Boolean,
    val frozen: Boolean,
    val blocksNothing: Boolean,
    /** Compiled rule count, active filter only (projected counts need a compile we don't run). */
    val ruleCount: Int?,
    val sources: List<FilterSourceOptionUi>,
    val blockedDomains: List<String>,
    val allowedDomains: List<String>,
    val maxAllowedDomains: Int,
    val maxBlockedDomains: Int,
    /** True while a per-filter draft exists — the screen renders its edit affordances. */
    val editing: Boolean,
    /** True when the draft differs from the saved filter — Save/Review is enabled. */
    val draftHasChanges: Boolean,
    /** True while a durable mutation targets this filter (review/commit in flight). */
    val busy: Boolean,
)

/**
 * The grouped review diff, matching the iOS review groups: Blocklists, Blocked Domains,
 * Allowed Exceptions — green-plus for added, orange-minus for removed. Source entries carry
 * their catalog names; domains are user data shown verbatim.
 */
data class FilterDraftDiffUi(
    val addedSources: List<String>,
    val removedSources: List<String>,
    val addedBlockedDomains: List<String>,
    val removedBlockedDomains: List<String>,
    val addedAllowedDomains: List<String>,
    val removedAllowedDomains: List<String>,
) {
    val hasChanges: Boolean
        get() = addedSources.isNotEmpty() ||
            removedSources.isNotEmpty() ||
            addedBlockedDomains.isNotEmpty() ||
            removedBlockedDomains.isNotEmpty() ||
            addedAllowedDomains.isNotEmpty() ||
            removedAllowedDomains.isNotEmpty()

    val weakensProtection: Boolean
        get() = removedSources.isNotEmpty() ||
            removedBlockedDomains.isNotEmpty() ||
            addedAllowedDomains.isNotEmpty()
}

/**
 * The review gate for one draft. `rejection` is null exactly when Confirm is enabled; a
 * non-null rejection renders the precise reason beside the affected group.
 */
data class FilterReviewState(
    val filterId: String,
    val filterName: String,
    val diff: FilterDraftDiffUi,
    val rejection: FilterDraftReviewRejection?,
    val busy: Boolean,
    val maxFilters: Int = FeatureLimits.FREE.maxFilters,
)

/** The preparation phases the full-screen cover renders (iOS FilterPreparationScreen). */
sealed interface FilterPreparationUiState {
    data object Idle : FilterPreparationUiState

    data class Preparing(val filterName: String) : FilterPreparationUiState

    data object Success : FilterPreparationUiState

    data class Failed(
        /**
         * A target that went away/frozen mid-prepare dead-ends (retrying re-fails); compile
         * and publish failures are retryable through the generation fence.
         */
        val retryable: Boolean,
    ) : FilterPreparationUiState
}

/**
 * Honest in-flight/failure facts for the library mutations (create, rename, staged delete,
 * restore defaults), derived from the generation-fenced owner state.
 */
sealed interface FilterLibraryMutationUiState {
    data object Idle : FilterLibraryMutationUiState

    data class InFlight(
        val kind: FilterLibraryMutationKindUi,
        val filterId: String?,
    ) : FilterLibraryMutationUiState

    data class Failed(
        val kind: FilterLibraryMutationKindUi,
        val filterId: String?,
        /** The precise editor refusal, or null for a compile/publish failure. */
        val rejection: FilterLibraryRejectionUi?,
    ) : FilterLibraryMutationUiState

    /**
     * The mutation landed durably. Sticky until the next admission (mirroring the owner's
     * Succeeded state) so dialogs can close on the actual durable outcome.
     */
    data class Succeeded(
        val kind: FilterLibraryMutationKindUi,
        val filterId: String?,
    ) : FilterLibraryMutationUiState
}

enum class FilterLibraryMutationKindUi {
    CREATE,
    RENAME,
    DELETE,
    RESTORE_DEFAULTS,
    DRAFT_COMMIT,
}

enum class FilterLibraryRejectionUi {
    UNKNOWN_FILTER,
    BLANK_NAME,
    INVALID_NAME,
    INVALID_EMOJI,
    DUPLICATE_NAME,
    LIMIT_REACHED,
    FROZEN_FILTER,
    BLOCKED_DOMAIN_LIMIT,
    ALLOWED_DOMAIN_LIMIT,
    ACTIVE_FILTER_CANNOT_BE_DELETED,
    LAST_FILTER_CANNOT_BE_DELETED,
    UNKNOWN_DUPLICATE_SOURCE,
}

/**
 * Pure mapping from verified product facts to the Filters UI states.
 *
 * `draft` is the authoritative configuration carried by the platform's `ConfirmedFilterState`;
 * the profile order here is the saved library order. The catalog naming entries are stable
 * bundled-catalog facts carried from graph creation; every source id in a confirmed
 * configuration was derived from this exact catalog, so unknown ids are dropped rather than
 * invented (an incoherent state can never fabricate a source row).
 */
object FilterLibraryMapper {
    fun library(
        draft: FilterLibraryDraft,
        catalog: List<FilterSourceCatalogEntry>,
        activeBlockRuleCount: Int?,
        maxFilters: Int,
    ): FilterLibraryState {
        require(activeBlockRuleCount == null || activeBlockRuleCount >= 0) {
            "active block rule count must not be negative"
        }
        val frozenIds = FilterLibraryPolicy.frozenProfileIds(draft, maxFilters)
        val filters = draft.profiles.map { profile ->
            FilterRowUi(
                id = profile.id,
                name = profile.name,
                active = profile.id == draft.activeProfileId,
                frozen = profile.id in frozenIds,
                blocksNothing = blocksNothing(profile),
                activeRuleCount = activeBlockRuleCount.takeIf {
                    profile.id == draft.activeProfileId
                },
            )
        }
        return FilterLibraryState(
            filters = filters,
            activeFilterId = draft.activeProfileId,
            maxFilters = maxFilters,
        )
    }

    fun detail(
        library: FilterLibraryDraft,
        catalog: List<FilterSourceCatalogEntry>,
        activeBlockRuleCount: Int?,
        saved: FilterProfileDraft,
        draft: FilterProfileDraft?,
        busy: Boolean,
        limits: FeatureLimits = FeatureLimits.FREE,
        creating: Boolean = false,
    ): FilterDetailState {
        val sourcesById = catalog.associateBy(FilterSourceCatalogEntry::id)
        val shown = draft ?: saved
        // The user's own blocklists are not in the signed catalog; synthesize their entries so the
        // detail and the snapshot's blocklist name map surface them instead of a raw id.
        val customEntries = shown.customBlocklists.map { source ->
            FilterSourceCatalogEntry(
                id = source.id,
                name = source.displayName,
                category = FilterSourceCategory.CUSTOM,
                sourceUrl = source.sourceUrl,
            )
        }
        val allEntries = catalog + customEntries
        val inEffect = saved.id == library.activeProfileId
        val frozen = FilterLibraryPolicy.isFrozen(
            library,
            saved.id,
            limits.maxFilters,
        )
        return FilterDetailState(
            filterId = saved.id,
            name = saved.name,
            inEffect = inEffect,
            frozen = frozen,
            blocksNothing = blocksNothing(shown),
            ruleCount = activeBlockRuleCount.takeIf { inEffect },
            sources = shown.enabledSourceIds.mapNotNull { sourceId ->
                allEntries.firstOrNull { it.id == sourceId }?.let { entry ->
                    FilterSourceOptionUi(
                        id = entry.id,
                        name = entry.name,
                        category = entry.category,
                        selected = true,
                        ruleCount = entry.ruleCount,
                    )
                }
            } + allEntries
                .filter { it.id !in shown.enabledSourceIds }
                .map { entry ->
                    FilterSourceOptionUi(
                        id = entry.id,
                        name = entry.name,
                        category = entry.category,
                        selected = false,
                        ruleCount = entry.ruleCount,
                    )
                },
            blockedDomains = shown.manualRules.blockedDomains.toList(),
            allowedDomains = shown.manualRules.allowedDomains.toList(),
            maxAllowedDomains = limits.maxAllowedDomains,
            maxBlockedDomains = limits.maxBlockedDomains,
            editing = draft != null,
            draftHasChanges = draft != null && (creating || FilterDraftPolicy.diff(saved, draft).hasChanges),
            busy = busy,
        )
    }

    fun review(
        library: FilterLibraryDraft,
        catalog: List<FilterSourceCatalogEntry>,
        saved: FilterProfileDraft,
        draft: FilterProfileDraft,
        busy: Boolean,
        limits: FeatureLimits = FeatureLimits.FREE,
        creating: Boolean = false,
    ): FilterReviewState {
        val names = (
            catalog.map { it.id to it.name } +
                draft.customBlocklists.map { it.id to it.displayName } +
                saved.customBlocklists.map { it.id to it.displayName }
            ).toMap()
        val diff = FilterDraftPolicy.diff(saved, draft)
        return FilterReviewState(
            filterId = draft.id,
            filterName = saved.name,
            diff = FilterDraftDiffUi(
                addedSources = diff.addedSourceIds.mapNotNull { names[it] },
                removedSources = diff.removedSourceIds.mapNotNull { names[it] },
                addedBlockedDomains = diff.addedBlockedDomains.toList(),
                removedBlockedDomains = diff.removedBlockedDomains.toList(),
                addedAllowedDomains = diff.addedAllowedDomains.toList(),
                removedAllowedDomains = diff.removedAllowedDomains.toList(),
            ),
            rejection = if (creating) FilterDraftPolicy.creationRejection(library, saved, draft, limits)
            else FilterDraftPolicy.reviewRejection(
                library = library,
                draft = draft,
                saved = saved,
                maxFilters = limits.maxFilters,
                limits = limits,
            ),
            busy = busy,
            maxFilters = limits.maxFilters,
        )
    }

    private fun blocksNothing(profile: FilterProfileDraft): Boolean =
        profile.enabledSourceIds.isEmpty() &&
            profile.manualRules.blockedDomains.isEmpty() &&
            profile.manualRules.allowedDomains.isEmpty()
}
