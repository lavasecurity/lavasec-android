package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.subscription.FeatureLimits
import java.util.LinkedHashSet

/**
 * The grouped change set between a saved filter and its per-filter draft, matching the iOS
 * review groups (`FilterConfirmationSheet.DiffGroup`): Blocklists, Blocked Domains, Allowed
 * Exceptions. Added entries render with green-plus and removed with orange-minus semantics;
 * entries keep the saved/draft order (added first, then removed per group).
 */
data class FilterDraftDiff(
    val addedSourceIds: Set<String>,
    val removedSourceIds: Set<String>,
    val addedBlockedDomains: Set<String>,
    val removedBlockedDomains: Set<String>,
    val addedAllowedDomains: Set<String>,
    val removedAllowedDomains: Set<String>,
) {
    val hasChanges: Boolean
        get() = addedSourceIds.isNotEmpty() ||
            removedSourceIds.isNotEmpty() ||
            addedBlockedDomains.isNotEmpty() ||
            removedBlockedDomains.isNotEmpty() ||
            addedAllowedDomains.isNotEmpty() ||
            removedAllowedDomains.isNotEmpty()

    /**
     * Whether committing the draft weakens protection — removing a blocklist or blocked
     * domain, or adding an allowed exception. iOS uses exactly this test to decide whether
     * a draft must pass the review confirmation before preparing.
     */
    val weakensProtection: Boolean
        get() = removedSourceIds.isNotEmpty() ||
            removedBlockedDomains.isNotEmpty() ||
            addedAllowedDomains.isNotEmpty()

    companion object {
        val EMPTY = FilterDraftDiff(
            addedSourceIds = emptySet(),
            removedSourceIds = emptySet(),
            addedBlockedDomains = emptySet(),
            removedBlockedDomains = emptySet(),
            addedAllowedDomains = emptySet(),
            removedAllowedDomains = emptySet(),
        )
    }
}

/** Why a draft cannot be reviewed/committed; the UI shows the precise rejection beside the diff. */
enum class FilterDraftReviewRejection {
    /** The draft equals the saved filter — there is nothing to commit. */
    NO_CHANGES,

    /** The filter this draft belongs to no longer exists in the library. */
    FILTER_GONE,

    /** The filter became frozen (over-cap, read-only) while the draft was open. */
    FILTER_FROZEN,
    BLOCKED_DOMAIN_LIMIT,
    ALLOWED_DOMAIN_LIMIT,
    FILTER_LIMIT_REACHED,
    INVALID_NAME,
    DUPLICATE_NAME,
    INVALID_EMOJI,
}

object FilterDraftPolicy {
    /** First save is meaningful even for an empty new filter; nothing is saved until admission. */
    fun creationRejection(
        library: FilterLibraryDraft,
        identity: FilterProfileDraft,
        draft: FilterProfileDraft,
        limits: FeatureLimits,
    ): FilterDraftReviewRejection? = when {
        library.profile(identity.id) != null || draft.id != identity.id -> FilterDraftReviewRejection.FILTER_GONE
        library.profiles.size >= limits.maxFilters -> FilterDraftReviewRejection.FILTER_LIMIT_REACHED
        !FilterIdentityPolicy.isValidName(identity.name) -> FilterDraftReviewRejection.INVALID_NAME
        !FilterIdentityPolicy.isValidEmoji(identity.emoji) -> FilterDraftReviewRejection.INVALID_EMOJI
        !FilterLibraryEditor.isNameAvailable(library, identity.name, excludingId = null) ->
            FilterDraftReviewRejection.DUPLICATE_NAME
        draft.manualRules.blockedDomains.size > limits.maxBlockedDomains -> FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT
        draft.manualRules.allowedDomains.size > limits.maxAllowedDomains -> FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT
        else -> null
    }

    /**
     * Exact set difference between a saved filter and its draft. Removed entries preserve the
     * saved order, added entries the draft order, so the review rows read predictably.
     */
    fun diff(saved: FilterProfileDraft, draft: FilterProfileDraft): FilterDraftDiff =
        FilterDraftDiff(
            addedSourceIds = ordered(draft.enabledSourceIds - saved.enabledSourceIds),
            removedSourceIds = ordered(saved.enabledSourceIds - draft.enabledSourceIds),
            addedBlockedDomains = ordered(draft.manualRules.blockedDomains - saved.manualRules.blockedDomains),
            removedBlockedDomains = ordered(saved.manualRules.blockedDomains - draft.manualRules.blockedDomains),
            addedAllowedDomains = ordered(draft.manualRules.allowedDomains - saved.manualRules.allowedDomains),
            removedAllowedDomains = ordered(saved.manualRules.allowedDomains - draft.manualRules.allowedDomains),
        )

    /**
     * The review gate for committing [draft] into [library]. The draft is built exclusively
     * through validated editors, so domain/source validity can never fail here; the honest
     * rejections are staleness (the filter is gone or frozen under the draft) and the no-op.
     * The compile-time rule budget is enforced by the generation-fenced coordinator, not here.
     */
    fun reviewRejection(
        library: FilterLibraryDraft,
        draft: FilterProfileDraft,
        saved: FilterProfileDraft,
        maxFilters: Int,
        limits: FeatureLimits = FeatureLimits.FREE,
    ): FilterDraftReviewRejection? = when {
        library.profile(draft.id) == null -> FilterDraftReviewRejection.FILTER_GONE
        FilterLibraryPolicy.isFrozen(library, draft.id, maxFilters) ->
            FilterDraftReviewRejection.FILTER_FROZEN
        !(draft.id == saved.id && draft.manualRules.isSubsetOf(saved.manualRules)) &&
            draft.manualRules.blockedDomains.size > limits.maxBlockedDomains ->
            FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT
        !(draft.id == saved.id && draft.manualRules.isSubsetOf(saved.manualRules)) &&
            draft.manualRules.allowedDomains.size > limits.maxAllowedDomains ->
            FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT
        !diff(saved, draft).hasChanges -> FilterDraftReviewRejection.NO_CHANGES
        else -> null
    }

    private fun ordered(values: Set<String>): Set<String> = LinkedHashSet(values)
}
