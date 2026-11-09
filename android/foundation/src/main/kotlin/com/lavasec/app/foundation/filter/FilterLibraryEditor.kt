package com.lavasec.app.foundation.filter

import java.util.Locale

/**
 * Why a filter-library edit was refused. Mirrors iOS AppViewModel's guards: the count cap
 * (`canCreateFilter`), the ≥1-library invariant, the never-delete-the-active rule, the
 * uniqueness of filter names, and the frozen (read-only) filter. Every rejection is a
 * durable, testable fact — the owner maps these to honest user copy, and the durable
 * adoption re-fences everything at publication, so a rejection here can never escalate
 * into an authority reconstruction.
 */
enum class FilterLibraryRejection {
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

sealed interface FilterLibraryEditResult {
    val previous: FilterLibraryDraft

    data class Applied(
        override val previous: FilterLibraryDraft,
        val library: FilterLibraryDraft,
    ) : FilterLibraryEditResult

    data class Rejected(
        override val previous: FilterLibraryDraft,
        val reason: FilterLibraryRejection,
    ) : FilterLibraryEditResult
}

/**
 * Pure edits of the saved filter library: create (optionally duplicating a filter's
 * contents), rename, staged batch delete, and restore-defaults. All rules are iOS-shaped
 * (`AppViewModel.createFilter`/`renameFilter`/`deleteFilter`/`restoreFiltersToDefault`):
 * a duplicate-from copies the source filter's blocklists and both manual-domain shelves;
 * an explicit name must be unique (trimmed, case-insensitive) and a blank one derives a
 * unique default; deletion refuses the active filter, the last remaining filter, and
 * frozen filters. New filter ids are caller-supplied (`filter-` + UUID in production) so
 * the editor stays a pure function.
 */
object FilterLibraryEditor {
    fun create(
        library: FilterLibraryDraft,
        name: String,
        duplicateFromId: String?,
        maxFilters: Int,
        newId: String,
    ): FilterLibraryEditResult {
        if (library.profiles.size >= maxFilters) {
            return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.LIMIT_REACHED)
        }
        val source = duplicateFromId?.let { duplicate ->
            library.profile(duplicate)
                ?: return FilterLibraryEditResult.Rejected(
                    library,
                    FilterLibraryRejection.UNKNOWN_DUPLICATE_SOURCE,
                )
        }
        val trimmed = FilterIdentityPolicy.normalizedName(name)
        if (trimmed.isNotEmpty() && !FilterIdentityPolicy.isValidName(name)) {
            return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.INVALID_NAME)
        }
        val resolvedName = if (trimmed.isEmpty()) {
            uniqueName(
                library = library,
                base = source?.name?.let { duplicateName(it) }
                    ?: "Filter",
            )
        } else {
            trimmed
        }
        if (trimmed.isNotEmpty() && !isNameAvailable(library, resolvedName, excludingId = null)) {
            return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.DUPLICATE_NAME)
        }
        val newFilter = if (source == null) {
            FilterProfileDraft(
                id = newId,
                name = resolvedName,
                protectionLevel = null,
                enabledSourceIds = emptySet(),
            )
        } else {
            FilterProfileDraft(
                id = newId,
                name = resolvedName,
                protectionLevel = null,
                enabledSourceIds = source.enabledSourceIds,
                manualRules = source.manualRules,
                customBlocklists = source.customBlocklists,
            )
        }
        return FilterLibraryEditResult.Applied(
            previous = library,
            library = library.withProfiles(library.profiles + newFilter),
        )
    }

    fun rename(
        library: FilterLibraryDraft,
        filterId: String,
        name: String,
        maxFilters: Int,
        emoji: String? = null,
    ): FilterLibraryEditResult {
        val target = library.profile(filterId)
            ?: return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.UNKNOWN_FILTER)
        if (FilterLibraryPolicy.isFrozen(library, filterId, maxFilters)) {
            return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.FROZEN_FILTER)
        }
        if (FilterIdentityPolicy.normalizedName(name).isEmpty()) {
            return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.BLANK_NAME)
        }
        // Ports iOS `renameFilter`: an unchanged legacy name is retained verbatim, while an
        // actual rename must meet today's entry policy and use canonical spelling.
        val resolvedName = FilterIdentityPolicy.nameForEdit(name, target.name)
            ?: return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.INVALID_NAME)
        if (!isNameAvailable(library, resolvedName, excludingId = filterId)) {
            return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.DUPLICATE_NAME)
        }
        if (emoji != null && !FilterIdentityPolicy.isValidEmoji(emoji)) {
            return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.INVALID_EMOJI)
        }
        val renamed = FilterProfileDraft(
            id = target.id,
            name = resolvedName,
            protectionLevel = target.protectionLevel,
            enabledSourceIds = target.enabledSourceIds,
            manualRules = target.manualRules,
            // Both identity fields commit together; name-only callers retain the saved emoji.
            emoji = emoji ?: target.emoji,
            customBlocklists = target.customBlocklists,
        )
        return FilterLibraryEditResult.Applied(
            previous = library,
            library = library.withProfile(renamed),
        )
    }

    fun delete(
        library: FilterLibraryDraft,
        filterIds: Set<String>,
        maxFilters: Int,
    ): FilterLibraryEditResult {
        val known = library.profiles.map(FilterProfileDraft::id).toSet()
        if (filterIds.any { it !in known }) {
            return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.UNKNOWN_FILTER)
        }
        if (library.profiles.size - filterIds.size < 1) {
            // The ≥1-library invariant is the fundamental one: a batch that would empty the
            // library is refused even when it also contains the active filter.
            return FilterLibraryEditResult.Rejected(
                library,
                FilterLibraryRejection.LAST_FILTER_CANNOT_BE_DELETED,
            )
        }
        if (library.activeProfileId in filterIds) {
            return FilterLibraryEditResult.Rejected(
                library,
                FilterLibraryRejection.ACTIVE_FILTER_CANNOT_BE_DELETED,
            )
        }
        if (filterIds.any { FilterLibraryPolicy.isFrozen(library, it, maxFilters) }) {
            return FilterLibraryEditResult.Rejected(library, FilterLibraryRejection.FROZEN_FILTER)
        }
        return FilterLibraryEditResult.Applied(
            previous = library,
            library = library.withProfiles(
                library.profiles.filter { it.id !in filterIds },
            ),
        )
    }

    /**
     * Reseeds the three iOS defaults (Core/Balanced/Extra) with Balanced active while keeping
     * the library's resolver plan. This is a wholesale replacement — every user filter and any
     * active-filter edit is replaced by the seed — matching iOS `restoreFiltersToDefault`.
     */
    fun restoreDefaults(
        library: FilterLibraryDraft,
        catalog: FilterCatalogSnapshot,
    ): FilterLibraryDraft =
        FilterLibraryDraft.seededDefaults(catalog).withResolverPlan(library.resolverTarget)

    /**
     * Whether `name` is free (non-blank, canonically trimmed, case-insensitive) excluding one
     * filter. Ports iOS `FilterLibraryAccessPolicy.isNameAvailable`: a rename may retain its own
     * name, and names collide under canonical-equivalent spelling.
     */
    fun isNameAvailable(
        library: FilterLibraryDraft,
        name: String,
        excludingId: String?,
    ): Boolean {
        val trimmed = FilterIdentityPolicy.normalizedName(name)
        if (trimmed.isEmpty()) return false
        val key = trimmed.lowercase(Locale.ROOT)
        return library.profiles.none { filter ->
            filter.id != excludingId &&
                FilterIdentityPolicy.normalizedName(filter.name).lowercase(Locale.ROOT) == key
        }
    }

    /** `base`, then "base 2", "base 3"… — the iOS uniqueFilterName derivation. */
    fun uniqueName(library: FilterLibraryDraft, base: String): String {
        val root = FilterIdentityPolicy.normalizedName(base).ifEmpty { "Filter" }
        if (isNameAvailable(library, root, excludingId = null)) return root
        var index = 2
        while (!isNameAvailable(library, "$root $index", excludingId = null)) index++
        return "$root $index"
    }

    private fun duplicateName(base: String): String = "$base 2"
}
