package com.lavasec.app.foundation.resolver

/**
 * One typed resolver selection: the exact primary provider/transport, whether
 * an encrypted fallback is configured, and the exact fallback provider/transport
 * when it is. This is the UI-facing projection of the milestone runtime
 * configuration; the durable authority still stores [ResolverConfiguration]
 * (see [MilestoneResolverPlan]).
 */
data class ResolverSelectionEntry(
    val providerId: ResolverProviderId,
    val transport: CatalogResolverTransport,
    /**
     * The normalized DoH URL carried by a custom DoH selection, or null for every
     * built-in selection. It is the exact value the runtime connects to, so a
     * value-carrying entry and the persisted [ResolverConfiguration] agree.
     */
    val customDohUrl: String? = null,
    /**
     * The normalized DoT endpoint (`host:port`, IPv6 bracketed) carried by a custom DoT
     * selection, or null for every built-in and custom-DoH selection. At most one of
     * [customDohUrl] and this value is ever set.
     */
    val customDotEndpoint: String? = null,
    /**
     * The committed display name of a custom selection, or null for a built-in selection and a
     * custom selection with no name. It carries no runtime meaning; it exists so the selection
     * projection and the persisted plan name the same value.
     */
    val customName: String? = null,
    /**
     * The committed optional second endpoint (same transport as the primary) of a custom selection,
     * or null when none was set. It is not the encrypted fallback tier; the runtime tries the
     * primary endpoint and falls back to this one.
     */
    val customSecondaryEndpoint: String? = null,
    /** Canonical `doq://host:port`, mutually exclusive with DoH/DoT values. */
    val customDoqEndpoint: String? = null,
    /** Canonical single usable IP literal for ordinary UDP DNS with bounded TCP retry. */
    val customPlainEndpoint: String? = null,
) {
    val provider: ResolverProvider get() = ResolverCatalog.provider(providerId)

    init {
        require(transport in provider.supportedTransports) {
            "$transport is not offered by $providerId"
        }
        if (providerId == ResolverProviderId.CUSTOM) {
            require(listOfNotNull(customDohUrl, customDotEndpoint, customDoqEndpoint, customPlainEndpoint).size == 1)
            val customValid = when (transport) {
                CatalogResolverTransport.DOH -> customDohUrl != null && customDotEndpoint == null && customDoqEndpoint == null
                CatalogResolverTransport.DOT -> customDotEndpoint != null && customDohUrl == null && customDoqEndpoint == null
                CatalogResolverTransport.DOQ -> customDoqEndpoint != null && customDohUrl == null && customDotEndpoint == null
                CatalogResolverTransport.PLAIN_DNS -> customPlainEndpoint != null &&
                    CustomResolverValidator.normalizedPlainEndpoint(customPlainEndpoint) == customPlainEndpoint
            }
            require(customValid) {
                "a custom selection must carry exactly one endpoint matching its transport"
            }
            require(customName == null || (customName == customName.trim() && customName.isNotEmpty())) {
                "a custom selection name must be non-empty and trimmed"
            }
            if (customSecondaryEndpoint != null) {
                val secondaryValid = when (transport) {
                    CatalogResolverTransport.DOH ->
                        CustomResolverValidator.normalizedDohUrl(customSecondaryEndpoint) ==
                            customSecondaryEndpoint
                    CatalogResolverTransport.DOT ->
                        CustomResolverValidator.normalizedDotEndpoint(customSecondaryEndpoint) ==
                            customSecondaryEndpoint
                    CatalogResolverTransport.DOQ ->
                        CustomResolverValidator.normalizedDoqEndpoint(customSecondaryEndpoint) ==
                            customSecondaryEndpoint
                    CatalogResolverTransport.PLAIN_DNS -> CustomResolverValidator.normalizedPlainEndpoint(customSecondaryEndpoint) == customSecondaryEndpoint
                }
                require(secondaryValid) {
                    "a custom selection secondary must match the primary transport"
                }
            }
        } else {
            require(
                customDohUrl == null && customDotEndpoint == null && customDoqEndpoint == null && customPlainEndpoint == null &&
                    customName == null && customSecondaryEndpoint == null,
            ) {
                "only a custom selection may carry a custom endpoint, name or secondary"
            }
        }
        require(
            ResolverCatalog.capability(provider, transport, customDohUrl, customDotEndpoint, customDoqEndpoint, customPlainEndpoint).available,
        ) {
            "a selection entry must name a runtime-served provider/transport pair"
        }
    }
}

/** Resolver identity ignores labels and preset aliases, matching the frozen ordered-row contract. */
fun ResolverSelectionEntry.endpointIdentity(): List<String> {
    val primary = when (transport) {
        CatalogResolverTransport.DOH -> customDohUrl?.let { checkNotNull(CustomResolverValidator.endpointIdentity(it)) }
            ?: checkNotNull(ResolverCatalog.dohUrl(provider)) + "@" + normalizedCatalogAddresses().joinToString("+")
        CatalogResolverTransport.DOT -> customDotEndpoint?.let { checkNotNull(CustomResolverValidator.endpointIdentity(it)) }
            ?: checkNotNull(ResolverCatalog.dotEndpoint(provider)) + "@" + normalizedCatalogAddresses().joinToString("+")
        CatalogResolverTransport.DOQ -> checkNotNull(CustomResolverValidator.endpointIdentity(checkNotNull(customDoqEndpoint)))
        CatalogResolverTransport.PLAIN_DNS -> when {
            providerId == ResolverProviderId.DEVICE_DNS -> "device-network"
            customPlainEndpoint != null -> checkNotNull(CustomResolverValidator.resolvedPlainAddress(customPlainEndpoint))
            else -> normalizedCatalogAddresses().joinToString(",")
        }
    }
    return listOf(transport.name, primary, customSecondaryEndpoint?.let { checkNotNull(CustomResolverValidator.endpointIdentity(it)) }.orEmpty())
}

private fun ResolverSelectionEntry.normalizedCatalogAddresses(): List<String> = ResolverCatalog.addresses(provider)
    .map { java.net.InetAddress.getByName(it).hostAddress ?: error("catalog literal has no address") }

/**
 * One retained rung of the ordered ladder. It mirrors the iOS `DNSResolutionSelection`
 * row: [entry] is the exact typed resolver and [isEnabled] records whether the row is
 * active. A disabled row stays in its original position (never collapsed into a single
 * boolean), so the shared ordered-tier editor round-trips it and a later toggle restores
 * the exact same resolver.
 */
data class ResolverTier(
    val entry: ResolverSelectionEntry,
    val isEnabled: Boolean = true,
    /** Optional retained row label, including built-in names from a portable backup. */
    val retainedName: String? = null,
) {
    init {
        require(retainedName == null || (retainedName.isNotEmpty() && retainedName == retainedName.trim())) {
            "a retained row name must be non-empty and trimmed"
        }
    }
    /** The label the shared editor renders: the custom name, or the built-in display name. */
    val displayName: String get() = retainedName ?: entry.customName ?: entry.provider.displayName
}

/**
 * The committed ordered ladder (iOS `dnsResolutionSelections`). It holds one or two
 * retained rows; [activeTiers] is the projection the runtime serves (enabled rows in
 * order), and the first active row is the effective primary. A disabled row is retained
 * beside its enabled siblings, and the last active row can never be disabled.
 */
data class ResolverSelection(
    val tiers: List<ResolverTier>,
) {
    init {
        require(tiers.size in 1..2) { "the ladder must hold one or two resolver tiers" }
        require(tiers.any { it.isEnabled }) { "the ladder must keep at least one tier active" }
        require(tiers.map { it.entry.endpointIdentity() }.distinct().size == tiers.size) {
            "the ladder must not repeat a resolver"
        }
    }

    /** The enabled rows in order; the runtime serves these, primary first. */
    val activeTiers: List<ResolverTier> get() = tiers.filter { it.isEnabled }

    val primary: ResolverSelectionEntry get() = activeTiers.first().entry

    val fallback: ResolverSelectionEntry? get() = activeTiers.getOrNull(1)?.entry

    val fallbackEnabled: Boolean get() = activeTiers.size > 1
}

/** The exact typed selections the milestone runtime can serve; every value is capability-checked. */
object ResolverSelectionPresets {
    val DEVICE_PLAIN = ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS)
    val MULLVAD_DOH = ResolverSelectionEntry(ResolverProviderId.MULLVAD, CatalogResolverTransport.DOH)

    val DEVICE_DNS_ONLY = ResolverSelection(listOf(ResolverTier(DEVICE_PLAIN)))
    val DEVICE_DNS_WITH_MULLVAD_FALLBACK = ResolverSelection(
        listOf(ResolverTier(DEVICE_PLAIN), ResolverTier(MULLVAD_DOH)),
    )
    val MULLVAD_ONLY = ResolverSelection(listOf(ResolverTier(MULLVAD_DOH)))
}

/**
 * The single preset bridge: every milestone plan maps to exactly one typed
 * selection and every selection maps back to at most one plan. Onboarding and
 * Settings both render `MilestoneResolverPlan.entries` and derive their typed
 * selections through this function, so there is no second preset list anywhere.
 */
fun MilestoneResolverPlan.toSelection(): ResolverSelection = when (this) {
    MilestoneResolverPlan.DEVICE_DNS_ONLY -> ResolverSelectionPresets.DEVICE_DNS_ONLY
    MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK ->
        ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK
    MilestoneResolverPlan.MULLVAD_ONLY -> ResolverSelectionPresets.MULLVAD_ONLY
}

/**
 * The milestone plan for the ACTIVE projection of this ladder, or null when the active
 * projection is not one of the milestone routes. Disabled rows never affect it, matching
 * the iOS `applyDNSResolutionSelections` derivation from enabled rows only.
 */
fun ResolverSelection.toMilestonePlan(): MilestoneResolverPlan? = when {
    !fallbackEnabled && primary.providerId == ResolverProviderId.DEVICE_DNS &&
        primary.transport == CatalogResolverTransport.PLAIN_DNS ->
        MilestoneResolverPlan.DEVICE_DNS_ONLY

    !fallbackEnabled && primary.providerId == ResolverProviderId.MULLVAD &&
        primary.transport == CatalogResolverTransport.DOH ->
        MilestoneResolverPlan.MULLVAD_ONLY

    fallbackEnabled &&
        primary.providerId == ResolverProviderId.DEVICE_DNS &&
        primary.transport == CatalogResolverTransport.PLAIN_DNS &&
        fallback?.providerId == ResolverProviderId.MULLVAD &&
        fallback?.transport == CatalogResolverTransport.DOH ->
        MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK

    else -> null
}

/**
 * The value-carrying plan for any runtime-servable ACTIVE projection: a milestone plan, or an
 * encrypted route that carries a custom DoH URL / DoT endpoint. The retained ladder travels with
 * the plan so the persisted authority and the ordered editor agree even while a row is disabled.
 * The retained rows are the authority for order; the legacy fields summarize which route kinds
 * are present and the first custom encrypted value for older readers.
 */
fun ResolverSelection.toFilterResolverPlan(): FilterResolverPlan? {
    val target = FilterResolverPlan.from(legacySummary()) ?: return null
    // Persist retained rows whenever legacy fields cannot express their order or identities.
    val legacy = target.configuration.legacySelection()
    return if (legacy == this) target else target.copy(savedTiers = tiers)
}

/** A compatibility projection. Runtime routing reads the retained rows when present. */
internal fun ResolverSelection.legacySummary(): ResolverConfiguration {
    val active = activeTiers.map { it.entry }
    val custom = active.firstOrNull { it.providerId == ResolverProviderId.CUSTOM && it.transport != CatalogResolverTransport.PLAIN_DNS }
    return ResolverConfiguration(
        // Legacy readers only know ordinary DNS presence. Retained rows identify Device versus fixed provider.
        useDeviceDns = active.any { it.transport == CatalogResolverTransport.PLAIN_DNS },
        useEncryptedFallback = active.any { it.transport != CatalogResolverTransport.PLAIN_DNS },
        customDohUrl = custom?.customDohUrl,
        customDotEndpoint = custom?.customDotEndpoint,
        customDoqEndpoint = custom?.customDoqEndpoint,
        customName = custom?.customName,
        customSecondaryEndpoint = custom?.customSecondaryEndpoint,
    )
}

/** The typed selection for a value-carrying plan, so a plan and its selection never disagree. */
fun FilterResolverPlan.toSelection(): ResolverSelection = configuration.toSelection()

/**
 * The typed selection for any configuration the runtime can serve: the milestone presets
 * plus a custom DoH or DoT resolver. When the configuration carries a retained ladder it is
 * the exact authority; otherwise the legacy two-slot fields decode to an equivalent ladder
 * (primary enabled, plus the fallback enabled when one is configured). A custom configuration
 * has no milestone plan, so this is the one place that turns the persisted value into a
 * value-carrying selection.
 */
fun ResolverConfiguration.toSelection(): ResolverSelection {
    savedTiers?.let { return ResolverSelection(it) }
    return legacySelection()
}

/** The ladder derived from the legacy two-slot effective fields, ignoring any retained rows. */
internal fun ResolverConfiguration.legacySelection(): ResolverSelection {
    customDoqEndpoint?.let { endpoint ->
        val custom = ResolverTier(ResolverSelectionEntry(
            providerId = ResolverProviderId.CUSTOM,
            transport = CatalogResolverTransport.DOQ,
            customDoqEndpoint = endpoint,
            customName = customName,
            customSecondaryEndpoint = customSecondaryEndpoint,
        ))
        return if (useDeviceDns) {
            ResolverSelection(listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN), custom))
        } else ResolverSelection(listOf(custom))
    }
    customDohUrl?.let { url ->
        val custom = ResolverTier(
            ResolverSelectionEntry(
                providerId = ResolverProviderId.CUSTOM,
                transport = CatalogResolverTransport.DOH,
                customDohUrl = url,
                customName = customName,
                customSecondaryEndpoint = customSecondaryEndpoint,
            ),
        )
        return if (useDeviceDns) {
            ResolverSelection(listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN), custom))
        } else {
            ResolverSelection(listOf(custom))
        }
    }
    customDotEndpoint?.let { endpoint ->
        val custom = ResolverTier(
            ResolverSelectionEntry(
                providerId = ResolverProviderId.CUSTOM,
                transport = CatalogResolverTransport.DOT,
                customDotEndpoint = endpoint,
                customName = customName,
                customSecondaryEndpoint = customSecondaryEndpoint,
            ),
        )
        return if (useDeviceDns) {
            ResolverSelection(listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN), custom))
        } else {
            ResolverSelection(listOf(custom))
        }
    }
    if (encryptedFallbackId != BuiltInDohResolverIds.MULLVAD) {
        if (!useEncryptedFallback) return ResolverSelectionPresets.DEVICE_DNS_ONLY
        val provider = ResolverProviderId.entries.firstOrNull { it.rawId + "-doh" == encryptedFallbackId }
            ?: error("unknown persisted encrypted endpoint")
        val encrypted = ResolverTier(ResolverSelectionEntry(provider, CatalogResolverTransport.DOH))
        return ResolverSelection(if (useDeviceDns) listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN), encrypted) else listOf(encrypted))
    }
    return requireNotNull(MilestoneResolverPlan.from(copy(savedTiers = null))) {
        "configuration is neither a milestone plan nor a custom encrypted route"
    }.toSelection()
}

/**
 * The legacy two-slot runtime configuration for a single custom encrypted primary, or null
 * when the ladder is not one (so a caller can fall back to [toMilestonePlan]). The endpoint is
 * the parser's normalized value, so the persisted endpoint is exactly the selected one.
 */
fun ResolverSelection.toCustomResolverConfiguration(): ResolverConfiguration? {
    if (fallbackEnabled) return null
    val entry = primary
    if (entry.providerId != ResolverProviderId.CUSTOM) return null
    entry.customDoqEndpoint?.let { endpoint ->
        return ResolverConfiguration(
            useDeviceDns = false, useEncryptedFallback = true,
            customDoqEndpoint = endpoint, customName = entry.customName,
            customSecondaryEndpoint = entry.customSecondaryEndpoint,
        )
    }
    entry.customDohUrl?.let { url ->
        return ResolverConfiguration(
            useDeviceDns = false,
            useEncryptedFallback = true,
            customDohUrl = url,
            customName = entry.customName,
            customSecondaryEndpoint = entry.customSecondaryEndpoint,
        )
    }
    entry.customDotEndpoint?.let { endpoint ->
        return ResolverConfiguration(
            useDeviceDns = false,
            useEncryptedFallback = true,
            customDotEndpoint = endpoint,
            customName = entry.customName,
            customSecondaryEndpoint = entry.customSecondaryEndpoint,
        )
    }
    return null
}
