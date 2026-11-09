package com.lavasec.app.foundation.filter

import java.util.Locale

/**
 * The canonical native-owned filter-library edit session, ported from iOS
 * `FilterLibraryEditSession` (LavaSecPresentation, pinned at `c4658d9`) as a pure immutable value
 * type.
 *
 * Forms stage values into one draft transaction; only a validated review commits. The session
 * never mutates the saved library ([baseline]) and never persists — the whole point is that a
 * batch of additions, deletions, renames and emoji changes is reviewed as one unit and then
 * either committed whole or rejected whole, with no partial publication.
 *
 * Android adaptation:
 *  - The iOS `FilterLibrary` / `Filter` pair maps to [FilterLibraryDraft] / [FilterProfileDraft].
 *    Because [FilterProfileDraft] is immutable and cannot hold a blank or invalid name (its
 *    constructor requires a non-blank name and sanitizes the emoji), staged renames and emoji
 *    changes are always kept in [renames] / [emojiChanges] keyed by id — including for staged
 *    additions (iOS mutated the addition element in place). [displayedFilters] and
 *    [validatedLibrary] apply those maps to both saved filters and additions, so the result is
 *    identical to the canonical in-place mutation.
 *  - There is no per-filter compiled-artifact cache (`lastCompiledToken` / `lastSyncedAt`) and no
 *    library `configurationGeneration` on Android, so the canonical
 *    `strippingLocalCacheState()` comparison is a documented no-op: [validatedLibrary] compares
 *    the complete baseline value against `current`. The live device-local metadata the result must
 *    keep from `current` is the active-profile id and the resolver plan.
 *
 * @property isEditing whether this session owns a draft. Staging before [beginEditing] is a no-op.
 * @property stagedDeletions saved filter ids marked for removal at confirmation.
 * @property baseline the saved library captured once when editing began.
 * @property additions new filters kept only in this draft until confirmation.
 * @property renames staged canonical names keyed by filter id (saved filters and additions).
 * @property emojiChanges staged emojis keyed by filter id (saved filters and additions).
 */
data class FilterLibraryEditSession(
    val isEditing: Boolean = false,
    val stagedDeletions: Set<String> = emptySet(),
    val baseline: FilterLibraryDraft? = null,
    val additions: List<FilterProfileDraft> = emptyList(),
    val renames: Map<String, String> = emptyMap(),
    val emojiChanges: Map<String, String> = emptyMap(),
) {
    /** Whether confirming would change saved library content. */
    val hasChanges: Boolean
        get() = stagedDeletions.isNotEmpty() ||
            additions.isNotEmpty() ||
            renames.isNotEmpty() ||
            emojiChanges.isNotEmpty()

    /**
     * Captures the baseline once. A repeated entry while already editing is a no-op, so re-entering
     * the editor keeps the in-progress draft and its original baseline.
     */
    fun beginEditing(library: FilterLibraryDraft? = null): FilterLibraryEditSession =
        if (isEditing) this else copy(isEditing = true, baseline = library)

    /** Discards all draft state after cancellation or a successful persistence. */
    fun endEditing(): FilterLibraryEditSession = FilterLibraryEditSession()

    /**
     * Toggles a saved filter removal, or removes a newly staged addition (discarding its staged
     * rename and emoji change too). A no-op before [beginEditing].
     */
    fun toggleDeletion(id: String): FilterLibraryEditSession {
        if (!isEditing) return this
        val additionIndex = additions.indexOfFirst { it.id == id }
        if (additionIndex >= 0) {
            return copy(
                additions = additions.filterIndexed { index, _ -> index != additionIndex },
                renames = renames - id,
                emojiChanges = emojiChanges - id,
            )
        }
        return copy(
            stagedDeletions = if (id in stagedDeletions) stagedDeletions - id else stagedDeletions + id,
        )
    }

    /** Stages a new filter without changing the saved library. A no-op before [beginEditing]. */
    fun add(profile: FilterProfileDraft): FilterLibraryEditSession =
        if (isEditing) copy(additions = additions + profile) else this

    /**
     * Stages a canonical name. A rename that returns the filter to the name it already had (its
     * baseline name for a saved filter, its added name for an addition) removes the staged change
     * instead of recording it, so undoing a rename restores [hasChanges] to its prior value.
     */
    fun rename(id: String, name: String): FilterLibraryEditSession {
        if (!isEditing) return this
        val canonical = FilterIdentityPolicy.normalizedName(name)
        val currentName = additions.firstOrNull { it.id == id }?.name ?: baseline?.profile(id)?.name
        return copy(
            renames = if (currentName == canonical) renames - id else renames + (id to canonical),
        )
    }

    /**
     * Stages a complete identity edit so name and emoji share one review boundary. Uses the same
     * return-to-baseline rule as [rename] for each half independently.
     */
    fun rename(id: String, name: String, emoji: String): FilterLibraryEditSession {
        if (!isEditing) return this
        val renamed = rename(id, name)
        val currentEmoji = additions.firstOrNull { it.id == id }?.emoji ?: baseline?.profile(id)?.emoji
        return renamed.copy(
            emojiChanges = if (currentEmoji == emoji) {
                renamed.emojiChanges - id
            } else {
                renamed.emojiChanges + (id to emoji)
            },
        )
    }

    /**
     * The rows the library should render while editing: saved filters (pending removals included,
     * so rows keep stable positions until confirmation) followed by staged additions, each
     * overlaid with its staged name and emoji. A blank staged name is not representable as a
     * [FilterProfileDraft], so the last valid name is shown; [validatedLibrary] still refuses the
     * blank batch.
     */
    val displayedFilters: List<FilterProfileDraft>
        get() = (baseline?.profiles.orEmpty() + additions).map { stagedProfile(it) }

    /**
     * Rejects the entire batch if its baseline or any live constraint changed, otherwise returns
     * the committed library. Returns null when there is nothing to commit, no baseline was
     * captured, the baseline no longer matches [current], or any one rule fails:
     *
     *  - the active filter is never deleted;
     *  - every staged deletion / rename / emoji change targets a filter that still exists and is
     *    not frozen (a staged rename or emoji change may target a staged addition);
     *  - every staged emoji is a single valid emoji;
     *  - names are non-empty and unique under canonical spelling;
     *  - ids are unique and the result is non-empty;
     *  - the count cap holds, except that an addition-free batch may commit an over-cap state (so a
     *    lapsed entitlement can still delete or rename down toward the cap).
     *
     * @param current the authoritative library at the commit edge.
     * @param maximumFilters the current tier's filter cap.
     */
    fun validatedLibrary(current: FilterLibraryDraft, maximumFilters: Int): FilterLibraryDraft? {
        if (!isEditing || !hasChanges) return null
        val baseline = this.baseline ?: return null
        // Baseline equality. Android carries no device-local per-filter cache or library
        // generation, so `strippingLocalCacheState()` is a documented no-op and the whole value is
        // compared: any concurrent content change makes the draft stale and rejects the batch.
        if (baseline != current) return null

        val additionIds = additions.mapTo(HashSet()) { it.id }

        // The active filter can never be part of a deletion batch.
        if (current.activeProfileId in stagedDeletions) return null

        // Every staged deletion targets an existing, non-frozen saved filter.
        if (stagedDeletions.any {
                current.profile(it) == null || FilterLibraryPolicy.isFrozen(current, it, maximumFilters)
            }
        ) {
            return null
        }

        // Every staged rename targets an existing, editable filter (saved or staged addition).
        if (renames.keys.any { !isEditableTarget(it, current, additionIds, maximumFilters) }) return null

        // Every staged emoji change is valid and targets an existing, editable filter.
        if (emojiChanges.any { (id, emoji) ->
                !FilterIdentityPolicy.isValidEmoji(emoji) ||
                    !isEditableTarget(id, current, additionIds, maximumFilters)
            }
        ) {
            return null
        }

        // Result set: saved filters minus staged deletions, in order, then staged additions.
        val survivors = current.profiles.filter { it.id !in stagedDeletions } + additions

        if (survivors.isEmpty()) return null
        if (survivors.map { it.id }.toSet().size != survivors.size) return null

        val canonicalNames = survivors.map {
            FilterIdentityPolicy.normalizedName(renames[it.id] ?: it.name).lowercase(Locale.ROOT)
        }
        if (canonicalNames.any { it.isEmpty() }) return null
        if (canonicalNames.toSet().size != canonicalNames.size) return null

        // Count cap with the canonical addition-free exception.
        if (survivors.size > maximumFilters && additions.isNotEmpty()) return null

        // Keep the live device-local metadata from `current`, never from the captured baseline.
        return FilterLibraryDraft(
            profiles = survivors.map { stagedProfile(it) },
            activeProfileId = current.activeProfileId,
            resolverPlan = current.resolverPlan,
            customDohUrl = current.customDohUrl,
            customDotEndpoint = current.customDotEndpoint,
            customDoqEndpoint = current.customDoqEndpoint,
            customName = current.customName,
            customSecondaryEndpoint = current.customSecondaryEndpoint,
            savedTiers = current.savedTiers,
            qaProbeSet = current.qaProbeSet,
        )
    }

    private fun isEditableTarget(
        id: String,
        current: FilterLibraryDraft,
        additionIds: Set<String>,
        maximumFilters: Int,
    ): Boolean = id in additionIds ||
        (current.profile(id) != null && !FilterLibraryPolicy.isFrozen(current, id, maximumFilters))

    private fun stagedProfile(profile: FilterProfileDraft): FilterProfileDraft {
        val stagedName = renames[profile.id]
        val name = if (stagedName != null && FilterIdentityPolicy.normalizedName(stagedName).isNotEmpty()) {
            stagedName
        } else {
            profile.name
        }
        return FilterProfileDraft(
            id = profile.id,
            name = name,
            protectionLevel = profile.protectionLevel,
            enabledSourceIds = profile.enabledSourceIds,
            manualRules = profile.manualRules,
            emoji = emojiChanges[profile.id] ?: profile.emoji,
            customBlocklists = profile.customBlocklists,
        )
    }
}
