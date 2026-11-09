package com.lavasec.app.foundation.resolver

import java.util.Collections
import java.util.LinkedHashSet

data class ResolverConfiguration(
    val useDeviceDns: Boolean = true,
    val useEncryptedFallback: Boolean = true,
    val encryptedFallbackId: String = BuiltInDohResolverIds.MULLVAD,
    /**
     * The normalized DoH URL when the encrypted resolver is a user-supplied custom
     * endpoint, or null for every built-in route. It is additive: a null value is
     * every existing configuration, and a non-null value names the exact endpoint
     * the runtime connects to instead of the pinned built-in resolver.
     */
    val customDohUrl: String? = null,
    /**
     * The normalized DoT endpoint (`host:port`) when the encrypted resolver is a user-supplied
     * custom DoT endpoint, or null for every built-in route and custom DoH route. It is additive:
     * a null value is every existing configuration, and a non-null value names the exact endpoint
     * the runtime connects to.
     */
    val customDotEndpoint: String? = null,
    /**
     * The user-set display name for a custom encrypted resolver, or null when none was set. It is
     * additive: a null value is every existing configuration. The runtime never reads it; it is
     * carried so the committed name round-trips to the editor and product surfaces.
     */
    val customName: String? = null,
    /**
     * The optional second endpoint of the same transport as the custom primary, normalized exactly
     * like the primary, or null when none was set. It is additive and independent of the encrypted
     * fallback tier: the runtime tries the primary endpoint first and falls back to this one.
     */
    val customSecondaryEndpoint: String? = null,
    /**
     * The retained ordered ladder, including disabled rows, or null for every legacy two-slot
     * configuration. It is additive: a null value decodes to an equivalent ladder (primary
     * enabled, plus the configured fallback enabled). When present its active projection MUST
     * agree with the compatibility summary fields above, so the runtime route and ordered editor
     * can never disagree.
     */
    val savedTiers: List<ResolverTier>? = null,
    val customDoqEndpoint: String? = null,
) {
    init {
        require(useDeviceDns || useEncryptedFallback) { "at least one resolver route must be active" }
        require(encryptedFallbackId in BuiltInDohResolverIds.all) {
            "encrypted fallback id must identify a built-in resolver"
        }
        require(listOfNotNull(customDohUrl, customDotEndpoint, customDoqEndpoint).size <= 1) {
            "a custom resolver names exactly one endpoint"
        }
        require(customName == null || (customName == customName.trim() && customName.isNotEmpty())) {
            "a custom resolver name must be non-empty and trimmed"
        }
        if (customDohUrl != null) {
            require(CustomResolverValidator.normalizedDohUrl(customDohUrl) != null) {
                "a custom DoH resolver must be a normalized DoH URL"
            }
            require(useEncryptedFallback) {
                "a custom DoH resolver is the active encrypted resolver"
            }
            require(
                customSecondaryEndpoint == null ||
                    CustomResolverValidator.normalizedDohUrl(customSecondaryEndpoint) ==
                    customSecondaryEndpoint,
            ) {
                "a custom DoH secondary must be a normalized DoH URL"
            }
        }
        if (customDotEndpoint != null) {
            require(CustomResolverValidator.normalizedDotEndpoint(customDotEndpoint) != null) {
                "a custom DoT resolver must be a normalized DoT endpoint"
            }
            require(useEncryptedFallback) {
                "a custom DoT resolver is the active encrypted resolver"
            }
            require(
                customSecondaryEndpoint == null ||
                    CustomResolverValidator.normalizedDotEndpoint(customSecondaryEndpoint) ==
                    customSecondaryEndpoint,
            ) {
                "a custom DoT secondary must be a normalized DoT endpoint"
            }
        }
        if (customDoqEndpoint != null) {
            require(CustomResolverValidator.normalizedDoqEndpoint(customDoqEndpoint) == customDoqEndpoint) {
                "a custom DoQ resolver must be a normalized DoQ endpoint"
            }
            require(useEncryptedFallback) {
                "a custom DoQ resolver is the active encrypted resolver"
            }
            require(
                customSecondaryEndpoint == null ||
                    CustomResolverValidator.normalizedDoqEndpoint(customSecondaryEndpoint) ==
                    customSecondaryEndpoint,
            ) {
                "a custom DoQ secondary must be a normalized DoQ endpoint"
            }
        }
        if (customDohUrl == null && customDotEndpoint == null && customDoqEndpoint == null) {
            require(customName == null && customSecondaryEndpoint == null) {
                "a custom name or secondary requires a custom primary endpoint"
            }
        }
        if (savedTiers != null) {
            val ladder = ResolverSelection(savedTiers)
            require(ladder.legacySummary() == copy(savedTiers = null)) {
                "a retained ladder must agree with its resolver summary"
            }
        }
    }
}

/** The only resolver choices exposed by the first Android parity milestone. */
enum class MilestoneResolverPlan(
    val configuration: ResolverConfiguration,
) {
    DEVICE_DNS_WITH_MULLVAD_FALLBACK(
        ResolverConfiguration(
            useDeviceDns = true,
            useEncryptedFallback = true,
            encryptedFallbackId = BuiltInDohResolverIds.MULLVAD,
        ),
    ),
    DEVICE_DNS_ONLY(
        ResolverConfiguration(
            useDeviceDns = true,
            useEncryptedFallback = false,
            encryptedFallbackId = BuiltInDohResolverIds.MULLVAD,
        ),
    ),
    MULLVAD_ONLY(
        ResolverConfiguration(
            useDeviceDns = false,
            useEncryptedFallback = true,
            encryptedFallbackId = BuiltInDohResolverIds.MULLVAD,
        ),
    ),
    ;

    companion object {
        /** Matches only an exact preset active order; disabled retained rows remain harmless. */
        fun from(configuration: ResolverConfiguration): MilestoneResolverPlan? {
            val effective = configuration.copy(savedTiers = null)
            val matching = entries.firstOrNull { it.configuration == effective } ?: return null
            return if (configuration.savedTiers == null ||
                ResolverSelection(configuration.savedTiers).toMilestonePlan() == matching
            ) matching else null
        }
    }
}

/**
 * The value-carrying resolver plan a persisted library commits to: one milestone plan, plus the
 * normalized custom encrypted endpoint (a DoH URL or a DoT `host:port`) that replaces the pinned
 * encrypted resolver when the user chose a custom selection. A custom selection has no milestone
 * enum constant, so it is represented as the route shape whose encrypted half it takes (the
 * encrypted-only shape for a custom primary, the Device-plus-encrypted shape for a custom second
 * tier) plus exactly one non-null endpoint; every built-in value has both endpoints null. The
 * retained ladder travels alongside so a disabled row survives persistence. [configuration]
 * reconstitutes the exact persisted [ResolverConfiguration], so the plan, the witness, the
 * fingerprint and the runtime route all read one value.
 */
data class FilterResolverPlan(
    val plan: MilestoneResolverPlan,
    val customDohUrl: String? = null,
    val customDotEndpoint: String? = null,
    val customName: String? = null,
    val customSecondaryEndpoint: String? = null,
    val savedTiers: List<ResolverTier>? = null,
    val customDoqEndpoint: String? = null,
) {
    init {
        require(listOfNotNull(customDohUrl, customDotEndpoint, customDoqEndpoint).size <= 1) {
            "a custom resolver plan names exactly one endpoint"
        }
        require(customName == null || (customName == customName.trim() && customName.isNotEmpty())) {
            "a custom resolver plan name must be non-empty and trimmed"
        }
        if (customDohUrl != null) {
            require(CustomResolverValidator.normalizedDohUrl(customDohUrl) != null) {
                "a custom DoH resolver plan must carry a normalized DoH URL"
            }
            require(plan.configuration.useEncryptedFallback) {
                "a custom DoH resolver plan must name an encrypted route"
            }
            require(
                customSecondaryEndpoint == null ||
                    CustomResolverValidator.normalizedDohUrl(customSecondaryEndpoint) ==
                    customSecondaryEndpoint,
            ) {
                "a custom DoH plan secondary must be a normalized DoH URL"
            }
        }
        if (customDotEndpoint != null) {
            require(CustomResolverValidator.normalizedDotEndpoint(customDotEndpoint) != null) {
                "a custom DoT resolver plan must carry a normalized DoT endpoint"
            }
            require(plan.configuration.useEncryptedFallback) {
                "a custom DoT resolver plan must name an encrypted route"
            }
            require(
                customSecondaryEndpoint == null ||
                    CustomResolverValidator.normalizedDotEndpoint(customSecondaryEndpoint) ==
                    customSecondaryEndpoint,
            ) {
                "a custom DoT plan secondary must be a normalized DoT endpoint"
            }
        }
        if (customDoqEndpoint != null) {
            require(CustomResolverValidator.normalizedDoqEndpoint(customDoqEndpoint) == customDoqEndpoint) {
                "a custom DoQ resolver plan must carry a normalized DoQ endpoint"
            }
            require(plan.configuration.useEncryptedFallback) {
                "a custom DoQ resolver plan must name an encrypted route"
            }
            require(
                customSecondaryEndpoint == null ||
                    CustomResolverValidator.normalizedDoqEndpoint(customSecondaryEndpoint) ==
                    customSecondaryEndpoint,
            ) {
                "a custom DoQ plan secondary must be a normalized DoQ endpoint"
            }
        }
        if (customDohUrl == null && customDotEndpoint == null && customDoqEndpoint == null) {
            require(customName == null && customSecondaryEndpoint == null) {
                "a custom name or secondary requires a custom primary endpoint"
            }
        }
    }

    /** The exact runtime/persisted configuration this plan names. */
    val configuration: ResolverConfiguration
        get() = plan.configuration.copy(
            customDohUrl = customDohUrl,
            customDotEndpoint = customDotEndpoint,
            customDoqEndpoint = customDoqEndpoint,
            customName = customName,
            customSecondaryEndpoint = customSecondaryEndpoint,
            savedTiers = savedTiers,
        )

    companion object {
        /** The plan for any runtime-servable configuration: a milestone plan or a custom encrypted route. */
        fun from(configuration: ResolverConfiguration): FilterResolverPlan? {
            if (configuration.encryptedFallbackId != BuiltInDohResolverIds.MULLVAD && configuration.savedTiers == null) {
                return configuration.toSelection().toFilterResolverPlan()
            }
            val customDoh = configuration.customDohUrl
            val customDot = configuration.customDotEndpoint
            val customDoq = configuration.customDoqEndpoint
            val savedTiers = configuration.savedTiers
            if (customDoh != null || customDot != null || customDoq != null) {
                val base = MilestoneResolverPlan.from(
                    configuration.copy(
                        customDohUrl = null,
                        customDotEndpoint = null,
                        customDoqEndpoint = null,
                        savedTiers = null,
                        customName = null,
                        customSecondaryEndpoint = null,
                    ),
                ) ?: return null
                return FilterResolverPlan(
                    plan = base,
                    customDohUrl = customDoh,
                    customDotEndpoint = customDot,
                    customDoqEndpoint = customDoq,
                    customName = configuration.customName,
                    customSecondaryEndpoint = configuration.customSecondaryEndpoint,
                    savedTiers = savedTiers,
                )
            }
            return MilestoneResolverPlan.from(configuration.copy(savedTiers = null))?.let { plan ->
                FilterResolverPlan(plan, savedTiers = savedTiers)
            }
        }
    }
}

object BuiltInDohResolverIds {
    const val MULLVAD = "mullvad-doh"
    const val CLOUDFLARE = "cloudflare-1111-doh"
    const val QUAD9_SECURE = "quad9-secure-doh"
    const val HAGEZI_ROOT = "hagezi-root-doh"
    const val GOOGLE_PUBLIC_DNS = "google-public-dns-doh"
    const val QUAD9_UNFILTERED = "quad9-unfiltered-doh"

    val all: Set<String> = Collections.unmodifiableSet(LinkedHashSet(listOf(
        MULLVAD,
        CLOUDFLARE,
        QUAD9_SECURE,
        HAGEZI_ROOT,
        GOOGLE_PUBLIC_DNS,
        QUAD9_UNFILTERED,
    )))
}
