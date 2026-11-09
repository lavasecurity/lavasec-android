package com.lavasec.app.foundation.share

/**
 * The three filter-selection fields compared when presenting configuration changes, ported from
 * the canonical iOS `FilterConfigurationSelection` (pinned `c4658d9`).
 */
data class FilterConfigurationSelection(
    val enabledBlocklistIDs: Set<String>,
    val blockedDomains: Set<String>,
    val allowedDomains: Set<String>,
) {
    companion object {
        val EMPTY = FilterConfigurationSelection(emptySet(), emptySet(), emptySet())
    }
}

/**
 * Sorted additions and removals between two [FilterConfigurationSelection]s, ported from the
 * canonical iOS `FilterConfigurationDiff`. This is the "what will change" half of an import
 * review: blocklists and manual block/allow domains, each split into added and removed.
 */
data class FilterConfigurationDiff(
    val addedBlocklistIDs: List<String>,
    val removedBlocklistIDs: List<String>,
    val addedBlockedDomains: List<String>,
    val removedBlockedDomains: List<String>,
    val addedAllowedDomains: List<String>,
    val removedAllowedDomains: List<String>,
) {
    constructor(from: FilterConfigurationSelection, to: FilterConfigurationSelection) : this(
        addedBlocklistIDs = sorted(to.enabledBlocklistIDs - from.enabledBlocklistIDs),
        removedBlocklistIDs = sorted(from.enabledBlocklistIDs - to.enabledBlocklistIDs),
        addedBlockedDomains = sorted(to.blockedDomains - from.blockedDomains),
        removedBlockedDomains = sorted(from.blockedDomains - to.blockedDomains),
        addedAllowedDomains = sorted(to.allowedDomains - from.allowedDomains),
        removedAllowedDomains = sorted(from.allowedDomains - to.allowedDomains),
    )

    val isEmpty: Boolean
        get() = addedBlocklistIDs.isEmpty() &&
            removedBlocklistIDs.isEmpty() &&
            addedBlockedDomains.isEmpty() &&
            removedBlockedDomains.isEmpty() &&
            addedAllowedDomains.isEmpty() &&
            removedAllowedDomains.isEmpty()

    val changeCount: Int
        get() = addedBlocklistIDs.size +
            removedBlocklistIDs.size +
            addedBlockedDomains.size +
            removedBlockedDomains.size +
            addedAllowedDomains.size +
            removedAllowedDomains.size

    private companion object {
        fun sorted(values: Set<String>): List<String> = values.sorted()
    }
}

/**
 * Before/after filter content for an import (or restore) review, ported from the canonical iOS
 * `FilterReplacementSummary`. It reports the selection diff, plus custom definitions added or
 * removed, and separates a custom source whose definition changed under the same id from a plain
 * selection removal/addition so the review never double-counts it.
 *
 * It has no fields for credentials, tokens, or local cache state: review is a projection of the
 * portable payload over the target's portable content.
 */
data class FilterReplacementSummary(
    val before: ShareableFilterConfiguration?,
    val after: ShareableFilterConfiguration?,
) {
    val selectionDiff: FilterConfigurationDiff = FilterConfigurationDiff(
        before?.selection ?: FilterConfigurationSelection.EMPTY,
        after?.selection ?: FilterConfigurationSelection.EMPTY,
    )

    /** Removed or changed custom definitions, including a changed definition under an existing id. */
    val removedCustomBlocklists: List<ShareableCustomBlocklistSource> =
        (before?.customBlocklists ?: emptyList())
            .filter { it !in (after?.customBlocklists ?: emptyList()) }

    /** Added or changed custom definitions the incoming content will store. */
    val addedCustomBlocklists: List<ShareableCustomBlocklistSource> =
        (after?.customBlocklists ?: emptyList())
            .filter { it !in (before?.customBlocklists ?: emptyList()) }

    /** Selection removals not already represented by a deleted/changed custom source. */
    val removedBlocklistIDs: List<String>
        get() {
            val described = removedCustomBlocklists.map { it.id }.toSet() -
                (after?.customBlocklists ?: emptyList()).map { it.id }.toSet()
            return selectionDiff.removedBlocklistIDs.filter { it !in described }
        }

    /** Selection additions not already represented by a newly added custom source. */
    val addedBlocklistIDs: List<String>
        get() {
            val described = addedCustomBlocklists.map { it.id }.toSet() -
                (before?.customBlocklists ?: emptyList()).map { it.id }.toSet()
            return selectionDiff.addedBlocklistIDs.filter { it !in described }
        }

    val hasChanges: Boolean
        get() = !selectionDiff.isEmpty ||
            removedCustomBlocklists.isNotEmpty() ||
            addedCustomBlocklists.isNotEmpty()
}

/**
 * Projects this incoming configuration over an existing target, ported from the canonical iOS
 * `replacementSummary(for:)`. Legacy v1 preserves the target's exceptions; v2 replaces them.
 */
fun ShareableFilterConfiguration.replacementSummary(
    target: ShareableFilterConfiguration,
): FilterReplacementSummary {
    // A legacy v1 import preserves the target's exceptions: v1 carries no allowed set, so the
    // projection keeps the target's own set and must stay a schema that can carry it.
    val allowed = if (allowedDomains == null) target.allowedDomains else allowedDomains
    val effectiveVersion = if (allowed == null) {
        ShareableFilterConfiguration.LEGACY_SCHEMA_VERSION
    } else {
        ShareableFilterConfiguration.CURRENT_SCHEMA_VERSION
    }
    val replacement = ShareableFilterConfiguration(
        schemaVersion = effectiveVersion,
        emoji = if (effectiveVersion == ShareableFilterConfiguration.LEGACY_SCHEMA_VERSION) {
            emoji ?: target.emoji
        } else {
            null
        },
        enabledBlocklistIDs = enabledBlocklistIDs,
        blockedDomains = blockedDomains,
        customBlocklists = customBlocklists,
        allowedDomains = allowed,
    )
    return FilterReplacementSummary(before = target, after = replacement)
}
