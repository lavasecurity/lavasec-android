package com.lavasec.app.foundation.filter

/** iOS filter ordering and freeze policy. Callers supply the current native tier's cap. */
object FilterLibraryPolicy {
    /** Free-tier hosted-filter cap; the three seeded defaults fill it exactly. */
    const val MAX_FILTERS = 3

    /** Free-tier compiled rule budget, matching the production compile authority. */
    const val MAX_FILTER_RULES = 500_000

    /**
     * The ids of filters that are *frozen* — kept and readable, but not switchable or editable.
     *
     * iOS semantics (`AppViewModel.isFilterFrozen`): a library that fits the cap freezes
     * nothing; when the library exceeds the cap, the active filter takes one slot and the
     * first `cap - 1` other filters by order stay usable — the excess freezes. The active
     * filter is never frozen. Stored filters survive a downgrade unchanged.
     */
    fun frozenProfileIds(
        library: FilterLibraryDraft,
        maxFilters: Int = MAX_FILTERS,
    ): Set<String> {
        require(maxFilters >= 1) { "filter cap must be positive" }
        if (library.profiles.size <= maxFilters) return emptySet()
        val nonActiveIds = library.profiles
            .map(FilterProfileDraft::id)
            .filter { it != library.activeProfileId }
        return nonActiveIds.drop((maxFilters - 1).coerceAtLeast(0)).toSet()
    }

    fun isFrozen(
        library: FilterLibraryDraft,
        filterId: String,
        maxFilters: Int = MAX_FILTERS,
    ): Boolean = filterId in frozenProfileIds(library, maxFilters)
}
