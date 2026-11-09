package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.domain.DomainValidationException
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toSelection
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

class ManualDomainRules(
    allowedDomains: Set<String> = emptySet(),
    blockedDomains: Set<String> = emptySet(),
) {
    val allowedDomains: Set<String> = normalizedImmutableSet(allowedDomains)
    val blockedDomains: Set<String> = normalizedImmutableSet(blockedDomains)

    init {
        require(this.allowedDomains.size <= MAX_STORED_ALLOWED_DOMAINS) { "allowed domain limit exceeded" }
        require(this.blockedDomains.size <= MAX_STORED_BLOCKED_DOMAINS) { "blocked domain limit exceeded" }
        require(this.allowedDomains.intersect(this.blockedDomains).isEmpty()) {
            "a manual domain cannot be both allowed and blocked"
        }
    }

    /** Both shelves must retain only previously saved rules; moving or replacing a domain is
     * an addition to its target shelf, even if the total rule count decreases. */
    fun isSubsetOf(previous: ManualDomainRules): Boolean = this === previous ||
        (previous.allowedDomains.containsAll(allowedDomains) &&
            previous.blockedDomains.containsAll(blockedDomains))

    fun asAllowRules(): DomainRuleSet =
        DomainRuleSet.from(allowedDomains.map { DomainRule.parse(it, matchesSubdomains = true) })

    fun asBlockRules(): DomainRuleSet =
        DomainRuleSet.from(blockedDomains.map { DomainRule.parse(it, matchesSubdomains = true) })

    override fun equals(other: Any?): Boolean =
        other is ManualDomainRules &&
            allowedDomains == other.allowedDomains &&
            blockedDomains == other.blockedDomains

    override fun hashCode(): Int = 31 * allowedDomains.hashCode() + blockedDomains.hashCode()

    companion object {
        /** Free admission defaults; storage must preserve Plus data after a lapse. */
        const val MAX_ALLOWED_DOMAINS = 25
        const val MAX_BLOCKED_DOMAINS = 25
        // Storage is edition-neutral; commercial admission belongs to FeatureLimits. Preserve
        // FOSS data up to the engine safety ceiling without making it a paid entitlement.
        const val MAX_STORED_ALLOWED_DOMAINS = FilterCompiler.MAX_SOURCE_RULES
        const val MAX_STORED_BLOCKED_DOMAINS = FilterCompiler.MAX_SOURCE_RULES

        val EMPTY = ManualDomainRules()

        private fun normalizedImmutableSet(domains: Set<String>): Set<String> =
            Collections.unmodifiableSet(LinkedHashSet(domains.map { DomainName.parse(it).value }))
    }
}

class FilterProfileDraft(
    val id: String,
    val name: String,
    /**
     * The onboarding protection level of a seeded profile (Core/Balanced/Extra), or null for a
     * user-created filter. A filter is a plain set of blocklists and manual rules — like iOS
     * `Filter` — so a user-created filter has no level; the level only identifies which of the
     * three seeded defaults a profile is, and stays pinned for the sealed derivation at
     * onboarding and restore-defaults.
     */
    val protectionLevel: ProtectionLevel?,
    enabledSourceIds: Set<String>,
    val manualRules: ManualDomainRules = ManualDomainRules.EMPTY,
    emoji: String? = null,
    /**
     * User-supplied blocklist definitions for this filter. Additive: an empty list is every
     * pre-existing library. A custom source is compiled only when its id is also in
     * [enabledSourceIds], matching the iOS enable-at-add model.
     */
    customBlocklists: List<CustomBlocklistSource> = emptyList(),
) {
    val enabledSourceIds: Set<String> = immutableSet(enabledSourceIds)
    val customBlocklists: List<CustomBlocklistSource> =
        Collections.unmodifiableList(customBlocklists.toList())

    /**
     * The filter's emoji identity, sanitized against the canonical [FilterIdentityPolicy].
     * An invalid or missing value falls back to the deterministic default derived from the
     * filter's stable id and name.
     */
    val emoji: String = emoji?.takeIf(FilterIdentityPolicy::isValidEmoji)
        ?: FilterIdentityPolicy.defaultEmoji(id = id, name = name)

    /** Pinned empty until the signed catalog actually supplies a guardrail feed. */
    val guardrailSourceIds: Set<String> = emptySet()

    init {
        require(id.isNotBlank()) { "filter profile id must not be blank" }
        require(name.isNotBlank()) { "filter profile name must not be blank" }
        require(this.enabledSourceIds.none(String::isBlank)) { "source id must not be blank" }
        require(this.customBlocklists.none { it.id.isBlank() }) { "custom blocklist id must not be blank" }
        require(this.customBlocklists.map(CustomBlocklistSource::id).distinct().size == this.customBlocklists.size) {
            "custom blocklist ids must be unique"
        }
        require(this.customBlocklists.size <= CustomBlocklistSource.MAX_CUSTOM_BLOCKLISTS_PER_PROFILE) {
            "custom blocklist limit exceeded"
        }
    }

    fun withManualRules(rules: ManualDomainRules): FilterProfileDraft = FilterProfileDraft(
        id = id,
        name = name,
        protectionLevel = protectionLevel,
        enabledSourceIds = enabledSourceIds,
        manualRules = rules,
        emoji = emoji,
        customBlocklists = customBlocklists,
    )

    fun withEnabledSourceIds(sourceIds: Set<String>): FilterProfileDraft = FilterProfileDraft(
        id = id,
        name = name,
        protectionLevel = protectionLevel,
        enabledSourceIds = sourceIds,
        manualRules = manualRules,
        emoji = emoji,
        customBlocklists = customBlocklists,
    )

    fun withCustomBlocklists(sources: List<CustomBlocklistSource>): FilterProfileDraft = FilterProfileDraft(
        id = id,
        name = name,
        protectionLevel = protectionLevel,
        enabledSourceIds = enabledSourceIds,
        manualRules = manualRules,
        emoji = emoji,
        customBlocklists = sources,
    )

    override fun equals(other: Any?): Boolean =
        other is FilterProfileDraft &&
            id == other.id &&
            name == other.name &&
            protectionLevel == other.protectionLevel &&
            enabledSourceIds == other.enabledSourceIds &&
            manualRules == other.manualRules &&
            emoji == other.emoji &&
            customBlocklists == other.customBlocklists

    override fun hashCode(): Int =
        listOf(id, name, protectionLevel, enabledSourceIds, manualRules, emoji, customBlocklists).hashCode()
}

enum class ManualRuleTarget {
    ALLOWED,
    BLOCKED,
}

enum class ManualRuleRejection {
    INVALID_DOMAIN,
    ALREADY_ALLOWED,
    ALREADY_BLOCKED,
    ALLOWED_LIMIT_REACHED,
    BLOCKED_LIMIT_REACHED,
}

sealed interface ManualRuleEditResult {
    val previous: FilterProfileDraft

    data class Applied(
        override val previous: FilterProfileDraft,
        val profile: FilterProfileDraft,
        val normalizedDomain: String,
        val target: ManualRuleTarget,
    ) : ManualRuleEditResult

    data class Rejected(
        override val previous: FilterProfileDraft,
        val reason: ManualRuleRejection,
    ) : ManualRuleEditResult
}

object ManualRuleEditor {
    fun add(
        rawDomain: String,
        target: ManualRuleTarget,
        profile: FilterProfileDraft,
        limits: FeatureLimits = FeatureLimits.FREE,
    ): ManualRuleEditResult {
        val normalized = try {
            DomainName.parse(rawDomain).value
        } catch (_: DomainValidationException) {
            return ManualRuleEditResult.Rejected(profile, ManualRuleRejection.INVALID_DOMAIN)
        }
        val current = profile.manualRules

        when (target) {
            ManualRuleTarget.ALLOWED -> {
                if (normalized in current.allowedDomains) {
                    return ManualRuleEditResult.Rejected(profile, ManualRuleRejection.ALREADY_ALLOWED)
                }
                if (current.allowedDomains.size >= minOf(limits.maxAllowedDomains, ManualDomainRules.MAX_STORED_ALLOWED_DOMAINS)) {
                    return ManualRuleEditResult.Rejected(profile, ManualRuleRejection.ALLOWED_LIMIT_REACHED)
                }
                val remainingBlocked = current.blockedDomains.size - if (normalized in current.blockedDomains) 1 else 0
                if (remainingBlocked > limits.maxBlockedDomains) {
                    return ManualRuleEditResult.Rejected(profile, ManualRuleRejection.BLOCKED_LIMIT_REACHED)
                }
                return applied(
                    previous = profile,
                    normalized = normalized,
                    target = target,
                    allowed = current.allowedDomains + normalized,
                    blocked = current.blockedDomains - normalized,
                )
            }
            ManualRuleTarget.BLOCKED -> {
                if (normalized in current.blockedDomains) {
                    return ManualRuleEditResult.Rejected(profile, ManualRuleRejection.ALREADY_BLOCKED)
                }
                if (current.blockedDomains.size >= minOf(limits.maxBlockedDomains, ManualDomainRules.MAX_STORED_BLOCKED_DOMAINS)) {
                    return ManualRuleEditResult.Rejected(profile, ManualRuleRejection.BLOCKED_LIMIT_REACHED)
                }
                val remainingAllowed = current.allowedDomains.size - if (normalized in current.allowedDomains) 1 else 0
                if (remainingAllowed > limits.maxAllowedDomains) {
                    return ManualRuleEditResult.Rejected(profile, ManualRuleRejection.ALLOWED_LIMIT_REACHED)
                }
                return applied(
                    previous = profile,
                    normalized = normalized,
                    target = target,
                    allowed = current.allowedDomains - normalized,
                    blocked = current.blockedDomains + normalized,
                )
            }
        }
    }

    fun remove(
        rawDomain: String,
        target: ManualRuleTarget,
        profile: FilterProfileDraft,
    ): FilterProfileDraft {
        val normalized = try {
            DomainName.parse(rawDomain).value
        } catch (_: DomainValidationException) {
            return profile
        }
        val rules = profile.manualRules
        return when (target) {
            ManualRuleTarget.ALLOWED -> profile.withManualRules(
                ManualDomainRules(rules.allowedDomains - normalized, rules.blockedDomains),
            )
            ManualRuleTarget.BLOCKED -> profile.withManualRules(
                ManualDomainRules(rules.allowedDomains, rules.blockedDomains - normalized),
            )
        }
    }

    /**
     * Restores one domain to the membership it had in [configured] (the saved profile's rules),
     * the Android form of canonical `filter.undoDomain`. [ManualDomainRules] keeps allowed and
     * blocked exclusive — unlike iOS's independent sets — so both shelves are reset for the
     * domain and only the configured shelf is repopulated; [target] is the decision the caller is
     * undoing (the shelf that currently holds the domain) and mirrors the canonical command, but
     * the exclusive model makes the outcome independent of it. An unparseable domain is a no-op.
     */
    fun undo(
        rawDomain: String,
        target: ManualRuleTarget,
        profile: FilterProfileDraft,
        configured: ManualDomainRules,
    ): FilterProfileDraft {
        val normalized = try {
            DomainName.parse(rawDomain).value
        } catch (_: DomainValidationException) {
            return profile
        }
        val rules = profile.manualRules
        val allowed = rules.allowedDomains - normalized
        val blocked = rules.blockedDomains - normalized
        return when {
            normalized in configured.allowedDomains ->
                profile.withManualRules(ManualDomainRules(allowed + normalized, blocked))
            normalized in configured.blockedDomains ->
                profile.withManualRules(ManualDomainRules(allowed, blocked + normalized))
            else ->
                profile.withManualRules(ManualDomainRules(allowed, blocked))
        }
    }

    private fun applied(
        previous: FilterProfileDraft,
        normalized: String,
        target: ManualRuleTarget,
        allowed: Set<String>,
        blocked: Set<String>,
    ): ManualRuleEditResult.Applied {
        val updated = previous.withManualRules(ManualDomainRules(allowed, blocked))
        return ManualRuleEditResult.Applied(previous, updated, normalized, target)
    }
}

/**
 * The durable filter library: every saved filter in order, exactly one of which is active.
 *
 * Filters are the iOS-shaped multi-filter model (`FilterLibrary` in LavaSecKit): each filter is
 * its own set of blocklists and manual rules, a user-created filter has no protection level,
 * names are unique, and the active filter loads — not edits — its contents on Apply. The three
 * seeded defaults (Core/Balanced/Extra) keep their pinned ids/names/levels via
 * [seededDefaults]; every other filter is a plain user value with a generated id.
 */
class FilterLibraryDraft(
    profiles: List<FilterProfileDraft>,
    val activeProfileId: String,
    val resolverPlan: MilestoneResolverPlan,
    /**
     * The normalized custom DoH endpoint that replaces the pinned encrypted resolver, or null for
     * every built-in milestone plan. Additive: a null value is every existing library, and a
     * non-null value makes [resolverPlan] the encrypted-only shape the custom route requires.
     */
    val customDohUrl: String? = null,
    /**
     * The normalized custom DoT endpoint (`host:port`) that replaces the pinned encrypted resolver,
     * or null for every built-in milestone plan and custom DoH plan. Additive: a null value is every
     * existing library, and a non-null value makes [resolverPlan] the encrypted-only shape the custom
     * route requires.
     */
    val customDotEndpoint: String? = null,
    /**
     * The committed display name of the custom encrypted resolver, or null for every built-in plan
     * and a custom plan with no name. Additive: a null value is every existing library.
     */
    val customName: String? = null,
    /**
     * The committed optional second endpoint (same transport as the custom primary), or null when
     * none was set. Additive: a null value is every existing library. It is independent of the
     * encrypted fallback tier; the runtime tries the primary and falls back to this endpoint.
     */
    val customSecondaryEndpoint: String? = null,
    /**
     * The retained ordered resolver ladder, including disabled rows, or null for every existing
     * library. Additive: a null value decodes to an equivalent ladder from the effective plan. When
     * present its active projection agrees with [resolverPlan] and the custom fields.
     */
    val savedTiers: List<ResolverTier>? = null,
    val customDoqEndpoint: String? = null,
    /** Native DEBUG-only global overlay; never part of a portable filter/backup payload. */
    val qaProbeSet: DeviceQaProbeSet? = null,
) {
    val profiles: List<FilterProfileDraft> = Collections.unmodifiableList(profiles.toList())
    private val profilesById: Map<String, FilterProfileDraft> =
        Collections.unmodifiableMap(LinkedHashMap(this.profiles.associateBy(FilterProfileDraft::id)))

    init {
        require(this.profiles.isNotEmpty()) { "filter library must contain at least one profile" }
        // Validate custom values and the retained ladder's compatibility summary together.
        FilterResolverPlan(
            resolverPlan,
            customDohUrl,
            customDotEndpoint,
            customName,
            customSecondaryEndpoint,
            savedTiers = savedTiers,
            customDoqEndpoint = customDoqEndpoint,
        ).configuration // Validates that retained rows agree with the compatibility summary.
        require(this.profiles.map(FilterProfileDraft::id).distinct().size == this.profiles.size) {
            "profile ids must be unique"
        }
        require(activeProfileId in profilesById) { "active profile must exist" }
        for (profile in this.profiles) {
            require(profile.guardrailSourceIds.isEmpty()) { "guardrail sources are not available" }
        }
        // Filter names are unique so the library, the share/import pickers, and review rows can
        // never show two indistinguishable filters (iOS isFilterNameAvailable).
        require(uniqueNormalizedNames(this.profiles)) { "filter names must be unique" }
    }

    fun profile(id: String): FilterProfileDraft? = profilesById[id]

    fun withProfile(updated: FilterProfileDraft): FilterLibraryDraft {
        require(updated.id in profilesById) { "unknown profile" }
        return FilterLibraryDraft(
            profiles = profiles.map { if (it.id == updated.id) updated else it },
            activeProfileId = activeProfileId,
            resolverPlan = resolverPlan,
            customDohUrl = customDohUrl,
            customDotEndpoint = customDotEndpoint,
            customName = customName,
            customSecondaryEndpoint = customSecondaryEndpoint,
            savedTiers = savedTiers,
            customDoqEndpoint = customDoqEndpoint,
            qaProbeSet = qaProbeSet,
        )
    }

    fun withProfiles(updated: List<FilterProfileDraft>): FilterLibraryDraft =
        FilterLibraryDraft(
            updated,
            activeProfileId,
            resolverPlan,
            customDohUrl,
            customDotEndpoint,
            customName,
            customSecondaryEndpoint,
            savedTiers,
            customDoqEndpoint,
            qaProbeSet,
        )

    fun withActiveProfile(id: String): FilterLibraryDraft =
        FilterLibraryDraft(
            profiles,
            id,
            resolverPlan,
            customDohUrl,
            customDotEndpoint,
            customName,
            customSecondaryEndpoint,
            savedTiers,
            customDoqEndpoint,
            qaProbeSet,
        )

    /** Replaces the whole value-carrying resolver plan (milestone plan plus optional custom endpoint). */
    fun withResolverPlan(target: FilterResolverPlan): FilterLibraryDraft =
        FilterLibraryDraft(
            profiles,
            activeProfileId,
            target.plan,
            target.customDohUrl,
            target.customDotEndpoint,
            target.customName,
            target.customSecondaryEndpoint,
            target.savedTiers,
            target.customDoqEndpoint,
            qaProbeSet,
        )

    /** Replaces only the milestone plan, clearing any custom endpoint and retained ladder. */
    fun withResolverPlan(plan: MilestoneResolverPlan): FilterLibraryDraft =
        FilterLibraryDraft(profiles, activeProfileId, plan, qaProbeSet = qaProbeSet)

    fun withQaProbeSet(probes: DeviceQaProbeSet?): FilterLibraryDraft = FilterLibraryDraft(
        profiles, activeProfileId, resolverPlan, customDohUrl, customDotEndpoint, customName,
        customSecondaryEndpoint, savedTiers, customDoqEndpoint, probes,
    )

    /** The value-carrying plan this library commits to; the one value plan consumers should read. */
    val resolverTarget: FilterResolverPlan
        get() = FilterResolverPlan(
            resolverPlan,
            customDohUrl,
            customDotEndpoint,
            customName,
            customSecondaryEndpoint,
            savedTiers,
            customDoqEndpoint,
        )

    override fun equals(other: Any?): Boolean =
        other is FilterLibraryDraft &&
            profiles == other.profiles &&
            activeProfileId == other.activeProfileId &&
            resolverPlan == other.resolverPlan &&
            customDohUrl == other.customDohUrl &&
            customDotEndpoint == other.customDotEndpoint &&
            customDoqEndpoint == other.customDoqEndpoint &&
            customName == other.customName &&
            customSecondaryEndpoint == other.customSecondaryEndpoint &&
            savedTiers == other.savedTiers && qaProbeSet == other.qaProbeSet

    override fun hashCode(): Int =
        listOf(
            profiles,
            activeProfileId,
            resolverPlan,
            customDohUrl,
            customDotEndpoint,
            customName,
            customSecondaryEndpoint,
            savedTiers,
            qaProbeSet,
            customDoqEndpoint,
        ).hashCode()

    companion object {
        /**
         * The three cumulative iOS-shaped defaults with Balanced active. Names are the pinned
         * brand tokens (Core/Balanced/Extra), shown verbatim on every surface.
         */
        fun seededDefaults(catalog: FilterCatalogSnapshot): FilterLibraryDraft {
            val profiles = FilterProfile.seededDefaults(catalog).values.map { profile ->
                FilterProfileDraft(
                    id = profile.id,
                    name = profile.name,
                    protectionLevel = profile.protectionLevel,
                    enabledSourceIds = profile.enabledSourceIds,
                )
            }
            return FilterLibraryDraft(
                profiles = profiles,
                activeProfileId = FilterProfile.stableId(ProtectionLevel.BALANCED),
                resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
            )
        }

        private fun uniqueNormalizedNames(profiles: List<FilterProfileDraft>): Boolean {
            val normalized = profiles.map {
                FilterIdentityPolicy.normalizedName(it.name).lowercase(Locale.ROOT)
            }
            return normalized.distinct().size == normalized.size
        }
    }
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
