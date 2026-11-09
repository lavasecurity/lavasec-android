package com.lavasec.app.foundation.share

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.domain.DomainValidationException
import com.lavasec.app.foundation.filter.DomainRuleSet
import com.lavasec.app.foundation.subscription.FeatureLimits

/** Result of validating one imported allow-domain exception. */
sealed interface ShareableAllowlistValidationResult {
    data class Allowed(val normalizedDomain: String) : ShareableAllowlistValidationResult
    data object Rejected : ShareableAllowlistValidationResult
}

/**
 * Validates an imported allowed exception with the same gates local editing uses, ported from the
 * canonical iOS `AllowlistValidator` (#858): the domain must normalize and must not be a
 * non-allowable threat rule. Service domains have no implicit exception or edit veto.
 */
class ShareableAllowlistValidator(
    private val nonAllowableThreatRules: DomainRuleSet,
) {
    fun validate(rawDomain: String): ShareableAllowlistValidationResult {
        val normalized = try {
            DomainName.parse(rawDomain).value
        } catch (_: DomainValidationException) {
            return ShareableAllowlistValidationResult.Rejected
        }
        if (nonAllowableThreatRules.containsNormalized(normalized)) {
            return ShareableAllowlistValidationResult.Rejected
        }
        return ShareableAllowlistValidationResult.Allowed(normalized)
    }
}

/**
 * What this device can actually accept from a shared configuration, ported from the canonical iOS
 * `ShareableFilterImportCapabilities` (pinned `c4658d9`).
 */
data class ShareableFilterImportCapabilities(
    /** Curated blocklist ids that exist in this build's catalog. */
    val availableCuratedBlocklistIDs: Set<String>,
    /** Ids an imported custom blocklist may not claim (curated + guardrail), so it can't shadow one. */
    val reservedBlocklistIDs: Set<String> = emptySet(),
    /** Whether custom blocklist sources are unlocked (Lava Security+). */
    val allowsCustomBlocklists: Boolean,
    /** The maximum number of manually blocked domains on the current plan. */
    val maxBlockedDomains: Int,
    /** Maximum allowed exceptions accepted by the recipient. */
    val maxAllowedDomains: Int = Int.MAX_VALUE,
    /** The tier ceiling on total compiled filter rules. */
    val maxFilterRules: Int = Int.MAX_VALUE,
    /** Known per-list rule counts; lists absent count as 0 known rules. */
    val blocklistRuleCounts: Map<String, Int> = emptyMap(),
    /** Rules the recipient already has that the import preserves (counted against the budget). */
    val preservedRuleCount: Int = 0,
    /** Current threat rules used by the same validation as manual exception editing. */
    val nonAllowableThreatRules: DomainRuleSet = DomainRuleSet.EMPTY,
) {
    companion object {
        /** Maps a subscription tier's [FeatureLimits] into import capabilities. */
        fun from(
            limits: FeatureLimits,
            availableCuratedBlocklistIDs: Set<String>,
            reservedBlocklistIDs: Set<String> = emptySet(),
            blocklistRuleCounts: Map<String, Int> = emptyMap(),
            preservedRuleCount: Int = 0,
            nonAllowableThreatRules: DomainRuleSet = DomainRuleSet.EMPTY,
        ): ShareableFilterImportCapabilities = ShareableFilterImportCapabilities(
            availableCuratedBlocklistIDs = availableCuratedBlocklistIDs,
            reservedBlocklistIDs = reservedBlocklistIDs,
            allowsCustomBlocklists = limits.allowsCustomBlocklists,
            maxBlockedDomains = limits.maxBlockedDomains,
            maxAllowedDomains = limits.maxAllowedDomains,
            maxFilterRules = limits.maxFilterRules,
            blocklistRuleCounts = blocklistRuleCounts,
            preservedRuleCount = preservedRuleCount,
            nonAllowableThreatRules = nonAllowableThreatRules,
        )
    }
}

/**
 * The result of reconciling a shared config against this device, ported from the canonical iOS
 * `ShareableFilterImportPlan`: the subset that will actually be applied, plus every entry dropped
 * and why. Nothing is ever dropped silently.
 */
data class ShareableFilterImportPlan(
    val applied: ShareableFilterConfiguration,
    val dropped: List<DroppedEntry>,
) {
    data class DroppedEntry(val kind: Kind, val label: String) {
        enum class Kind {
            /** A curated blocklist that isn't in this build's catalog. */
            UNAVAILABLE_BLOCKLIST,

            /** A custom blocklist that needs Lava Security+ to use. */
            REQUIRES_UPGRADE,

            /** A manually blocked or allowed domain beyond the current plan's limit. */
            EXCEEDS_LIMIT,

            /** A malformed or prohibited manually entered domain. */
            INVALID_DOMAIN,

            /** An unsafe custom source (non-HTTPS, credentialed, private-network, or shadowing). */
            UNSAFE_SOURCE,

            /** A list dropped because keeping it would exceed the tier filter-rule budget. */
            EXCEEDS_RULE_BUDGET,
        }
    }

    val hasUnsupportedEntries: Boolean get() = dropped.isNotEmpty()

    fun droppedCount(kind: DroppedEntry.Kind): Int = dropped.count { it.kind == kind }

    val isEmpty: Boolean get() = applied.isEmpty
}

/**
 * Reconciles this shared config against a device's capabilities, ported from the canonical iOS
 * `importPlan(capabilities:)` (pinned `c4658d9`).
 *
 * Importing `A + B + C` onto a device that only supports `A + B` yields an `applied` of `A + B`
 * and one dropped entry for `C` — never a failure, and never a silent omission. Because LF1 codes
 * are unsigned, imported custom blocklists are treated as untrusted: each is re-validated through
 * the HTTPS/public-host/no-credentials checks and an id that shadows a curated list is rejected.
 */
fun ShareableFilterConfiguration.importPlan(
    capabilities: ShareableFilterImportCapabilities,
): ShareableFilterImportPlan {
    val dropped = mutableListOf<ShareableFilterImportPlan.DroppedEntry>()

    // Custom blocklists: gated behind Lava Security+, then sanitized.
    val supportedCustomBlocklists = mutableListOf<ShareableCustomBlocklistSource>()
    val seenCustomIDs = LinkedHashSet<String>()
    for (source in customBlocklists) {
        // Collapse duplicate ids from a crafted code: two custom sources with the same id later
        // trap dictionary construction.
        if (!seenCustomIDs.add(source.id)) continue
        if (!capabilities.allowsCustomBlocklists) {
            dropped += drop(
                ShareableFilterImportPlan.DroppedEntry.Kind.REQUIRES_UPGRADE,
                source.displayName,
            )
            continue
        }
        if (source.id in capabilities.reservedBlocklistIDs) {
            dropped += drop(ShareableFilterImportPlan.DroppedEntry.Kind.UNSAFE_SOURCE, source.displayName)
            continue
        }
        if (!ShareableSourceUrlValidator.isAcceptable(source.sourceUrl)) {
            dropped += drop(ShareableFilterImportPlan.DroppedEntry.Kind.UNSAFE_SOURCE, source.displayName)
            continue
        }
        supportedCustomBlocklists += source
    }

    val customSourceIDs = customBlocklists.map { it.id }.toSet()
    val supportedCustomIDs = supportedCustomBlocklists.map { it.id }.toSet()
    val acceptableListIDs = capabilities.availableCuratedBlocklistIDs + supportedCustomIDs

    // Enabled lists: keep curated ids that exist here and supported custom ids. Ids belonging to
    // dropped custom lists were already reported above, so they are skipped silently here.
    val supportedListIDs = LinkedHashSet<String>()
    for (id in enabledBlocklistIDs.sorted()) {
        when {
            id in acceptableListIDs -> supportedListIDs += id
            id in customSourceIDs -> Unit
            else -> dropped += drop(ShareableFilterImportPlan.DroppedEntry.Kind.UNAVAILABLE_BLOCKLIST, id)
        }
    }

    // Blocked domains: normalize through the same primitive the manual editor uses.
    val normalizedBlocked = LinkedHashSet<String>()
    for (domain in blockedDomains.sorted()) {
        try {
            normalizedBlocked += DomainName.parse(domain).value
        } catch (_: DomainValidationException) {
            dropped += drop(ShareableFilterImportPlan.DroppedEntry.Kind.INVALID_DOMAIN, domain)
        }
    }
    val sortedBlocked = normalizedBlocked.sorted()
    val keptBlocked = sortedBlocked.take(maxOf(0, capabilities.maxBlockedDomains))
    for (domain in sortedBlocked.drop(keptBlocked.size)) {
        dropped += drop(ShareableFilterImportPlan.DroppedEntry.Kind.EXCEEDS_LIMIT, domain)
    }

    // Allowed exceptions: the shared validator, never a laxer path.
    val validator = ShareableAllowlistValidator(capabilities.nonAllowableThreatRules)
    val normalizedAllowed = LinkedHashSet<String>()
    for (domain in (allowedDomains ?: emptySet()).sorted()) {
        when (val result = validator.validate(domain)) {
            is ShareableAllowlistValidationResult.Allowed -> normalizedAllowed += result.normalizedDomain
            ShareableAllowlistValidationResult.Rejected ->
                dropped += drop(ShareableFilterImportPlan.DroppedEntry.Kind.INVALID_DOMAIN, domain)
        }
    }
    val sortedAllowed = normalizedAllowed.sorted()
    val keptAllowed = sortedAllowed.take(maxOf(0, capabilities.maxAllowedDomains)).toSet()
    for (domain in sortedAllowed.drop(keptAllowed.size)) {
        dropped += drop(ShareableFilterImportPlan.DroppedEntry.Kind.EXCEEDS_LIMIT, domain)
    }

    // Filter-rule budget: keep lists whose known rule counts fit the tier ceiling. Each kept
    // blocked domain costs a rule, and so do preserved exceptions for a legacy v1 import.
    var runningRuleCount = keptBlocked.size +
        (if (allowedDomains == null) capabilities.preservedRuleCount else keptAllowed.size)
    val budgetedListIDs = LinkedHashSet<String>()
    for (id in supportedListIDs.sorted()) {
        val cost = capabilities.blocklistRuleCounts[id] ?: 0
        if (runningRuleCount + cost <= capabilities.maxFilterRules) {
            runningRuleCount += cost
            budgetedListIDs += id
        } else {
            dropped += drop(ShareableFilterImportPlan.DroppedEntry.Kind.EXCEEDS_RULE_BUDGET, id)
        }
    }
    val budgetedCustomBlocklists = supportedCustomBlocklists.filter {
        it.id !in enabledBlocklistIDs || budgetedListIDs.contains(it.id)
    }

    val applied = ShareableFilterConfiguration(
        schemaVersion = schemaVersion,
        emoji = emoji,
        enabledBlocklistIDs = budgetedListIDs,
        blockedDomains = keptBlocked.toSet(),
        customBlocklists = budgetedCustomBlocklists,
        allowedDomains = if (allowedDomains == null) null else keptAllowed,
    )
    return ShareableFilterImportPlan(applied = applied, dropped = dropped)
}

private fun drop(
    kind: ShareableFilterImportPlan.DroppedEntry.Kind,
    label: String,
): ShareableFilterImportPlan.DroppedEntry = ShareableFilterImportPlan.DroppedEntry(kind, label)
