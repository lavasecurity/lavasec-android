package com.lavasec.app.foundation.resolver

import com.lavasec.app.foundation.subscription.FeatureLimits

/**
 * The provider catalogue available to one policy decision. It wraps the one Android
 * [ResolverCatalog] so a caller can inject an ordered subset in tests, while runtime
 * capability ALWAYS comes from [ResolverCatalog.capability] — the single source of
 * "what the milestone engine can actually serve". A policy must never invent a route
 * the runtime cannot carry.
 */
data class ResolverCatalogue(
    val providers: List<ResolverProvider>,
    val acceptsLegacyAuthorities: Boolean = false,
) {
    fun providerByRawId(rawId: String): ResolverProvider? =
        providers.firstOrNull { it.id.rawId == rawId }
            ?: if (acceptsLegacyAuthorities) ResolverCatalog.legacyProviders.firstOrNull { it.id.rawId == rawId } else null

    fun capability(
        provider: ResolverProvider,
        transport: CatalogResolverTransport,
        customDohUrl: String? = null,
        customDotEndpoint: String? = null,
        customDoqEndpoint: String? = null,
        customPlainEndpoint: String? = null,
    ): ResolverTransportCapability =
        ResolverCatalog.capability(provider, transport, customDohUrl, customDotEndpoint, customDoqEndpoint, customPlainEndpoint)

    companion object {
        val canonical: ResolverCatalogue = ResolverCatalogue(ResolverCatalog.providers, acceptsLegacyAuthorities = true)
    }
}

/**
 * One editable resolver tier: the Android mirror of the iOS `DNSResolutionSelection`
 * (`Sources/LavaSecKit/DNSResolutionSelection.swift`). It is a PROPOSAL, so it may
 * name an unknown provider, an unknown transport token, or an unvalidated custom
 * address; [ResolverSelectionPolicy] is what turns it into a typed accept/reject.
 *
 * Custom tiers carry the primary/secondary values; built-in tiers ignore them (the
 * iOS `applyDNSResolutionSelections` writes custom values only for `custom-dns`).
 */
data class ResolverTierChoice(
    val providerId: String,
    val transportToken: String,
    val name: String = "",
    val primary: String = "",
    val secondary: String = "",
    /**
     * Whether the retained row is active. A disabled row keeps its identity and position so the
     * ordered editor round-trips it; the active projection is recomputed from enabled rows only.
     */
    val isEnabled: Boolean = true,
) {
    val isCustom: Boolean get() = providerId == ResolverProviderId.CUSTOM.rawId

    val normalizedName: String? get() = name.trim().takeIf { it.isNotEmpty() }

    val trimmedPrimary: String get() = primary.trim()

    val trimmedSecondary: String get() = secondary.trim()

    fun toDraft(): CustomResolverDraft = CustomResolverDraft(name, primary, secondary)

    companion object {
        fun of(entry: ResolverSelectionEntry): ResolverTierChoice =
            ResolverTierChoice(entry.providerId.rawId, entry.transport.token)
    }
}

/** Parses the stable iOS transport menu tokens ("IP", "DoH", "DoT", "DoQ"). */
object ResolverTransportTokens {
    fun fromToken(token: String): CatalogResolverTransport? =
        CatalogResolverTransport.entries.firstOrNull { it.token == token }
}

/** The catalog transport named by a parsed custom endpoint. */
fun ParsedCustomResolverTransport.toCatalogTransport(): CatalogResolverTransport = when (this) {
    ParsedCustomResolverTransport.PLAIN -> CatalogResolverTransport.PLAIN_DNS
    ParsedCustomResolverTransport.DOH -> CatalogResolverTransport.DOH
    ParsedCustomResolverTransport.DOT -> CatalogResolverTransport.DOT
    ParsedCustomResolverTransport.DOQ -> CatalogResolverTransport.DOQ
}

/** One proposed edit to the saved resolver selection. */
sealed interface ResolverEditRequest {
    /**
     * The editor context the request was built against, or null when the caller has no
     * context binding. A non-null value that disagrees with the live context is a stale
     * editor (iOS "DNS settings changed. Reopen the editor before saving.").
     */
    val expectedEditorContext: String?
}

/** The canonical complete-ladder commit (iOS `saveDNSTiers` / `applyDNSResolutionSelections`). */
data class SaveResolverTiers(
    val tiers: List<ResolverTierChoice>,
    override val expectedEditorContext: String? = null,
) : ResolverEditRequest

/** The fallback tier toggle (iOS `setUsesEncryptedDeviceDNSFallback` / `setFallbackToDeviceDNS`). */
data class SetResolverFallbackEnabled(
    val enabled: Boolean,
    override val expectedEditorContext: String? = null,
) : ResolverEditRequest

/**
 * One row enable toggle (iOS `setDNSResolutionEnabled(_:index:)`). The row keeps its position and
 * identity while disabled, and the last active row can never be turned off.
 */
data class SetResolverTierEnabled(
    val index: Int,
    val enabled: Boolean,
    override val expectedEditorContext: String? = null,
) : ResolverEditRequest

/** A committed selection plus the normalized editable pair that produced it. */
data class ResolverSelectionCommit(
    val selection: ResolverSelection,
    val tiers: List<ResolverTierChoice>,
)

/** Typed rejections. Every reason is a distinct value a surface can map to copy. */
sealed interface ResolverEditRejection {
    /** Chained full tunnel / fallback-off: the saved selection is bypassed and locked. */
    data object ChainedEditLocked : ResolverEditRejection

    /** The editor was opened against an older configuration. */
    data object StaleEditor : ResolverEditRejection

    /** The ladder must be one primary and at most one secondary (iOS 1...2). */
    data object InvalidTierCount : ResolverEditRejection

    /**
     * The raw provider id is not in the catalogue. It is NEVER coerced to Device DNS
     * or Mullvad: an unknown/legacy id (e.g. the iOS `device` / legacy `mullvad-doh`
     * tokens) is a rejection, not a silent default.
     */
    data class UnknownProvider(val rawId: String) : ResolverEditRejection

    /** The raw transport token is not one of the four menu tokens. */
    data class UnknownTransport(val token: String) : ResolverEditRejection

    /** A custom resolver requires Lava Plus (iOS `customRequiresPlus`). */
    data object CustomRequiresPlus : ResolverEditRejection

    /** The custom primary/secondary failed [CustomResolverValidator]. */
    data class CustomInvalid(val failure: CustomResolverValidationFailure) : ResolverEditRejection

    /** The custom value's own transport is not the selected transport. */
    data object CustomTransportMismatch : ResolverEditRejection

    /** The provider does not offer the transport (iOS `availableTransports`). */
    data class TransportNotOffered(
        val providerId: ResolverProviderId,
        val transport: CatalogResolverTransport,
    ) : ResolverEditRejection

    /** The runtime cannot serve the provider/transport pair; the reason is the capability fact. */
    data class TransportUnavailable(
        val providerId: ResolverProviderId,
        val transport: CatalogResolverTransport,
        val reason: ResolverTransportUnavailableReason,
    ) : ResolverEditRejection

    /** A second tier must name a fallback-eligible provider (only Mullvad DoH today). */
    data object FallbackNotEligible : ResolverEditRejection

    /** The second tier is the same resolver as the primary (iOS `duplicate`). */
    data object DuplicateTier : ResolverEditRejection
}

sealed interface ResolverEditResult {
    data class Accepted(val commit: ResolverSelectionCommit) : ResolverEditResult

    data class Rejected(val rejection: ResolverEditRejection) : ResolverEditResult
}

/**
 * Pure DNS selection policy. Given the catalogue, the committed selection, the current
 * tier, the chained-mode state and a proposed edit, it returns a typed accept/reject.
 * It ports the canonical iOS rules and is the only place that decides whether a
 * provider/transport/custom/fallback edit may be committed:
 *
 * - the chained-mode edit gate (`ChainedDNSSettingsPresentation.canEditDNS`);
 * - a complete ladder of one primary and at most one secondary (`1...2`);
 * - unknown provider ids / transport tokens are rejected, never defaulted;
 * - custom requires Plus before it is even validated, then [CustomResolverValidator],
 *   then transport coherence, then the runtime capability fact (a custom DoH URL or DoT
 *   endpoint is served when it carries a valid normalized value; DoQ authorities and plain IP lists are served too);
 * - provider/transport must be offered AND runtime-served ([ResolverCatalog.capability]);
 * - a second tier must be fallback-eligible and distinct from the primary.
 *
 * This pure policy touches no runtime; capability checks admit only endpoint-carrying
 * transports the Android composition serves.
 */
object ResolverSelectionPolicy {
    fun evaluate(
        request: ResolverEditRequest,
        current: ResolverSelection,
        limits: FeatureLimits,
        chained: ChainedDnsEditState,
        catalogue: ResolverCatalogue = ResolverCatalogue.canonical,
        editorContext: String? = null,
    ): ResolverEditResult {
        if (!chained.canEditDns) return ResolverEditResult.Rejected(ResolverEditRejection.ChainedEditLocked)
        val expected = request.expectedEditorContext
        if (expected != null && expected != editorContext) {
            return ResolverEditResult.Rejected(ResolverEditRejection.StaleEditor)
        }
        return when (request) {
            is SaveResolverTiers -> saveTiers(request.tiers, limits, catalogue, current)
            is SetResolverFallbackEnabled -> setFallbackEnabled(request.enabled, current, limits, catalogue)
            is SetResolverTierEnabled -> setTierEnabled(request.index, request.enabled, current, limits, catalogue)
        }
    }

    /**
     * Validates a complete ladder (iOS `applyDNSResolutionSelections`). Every row is resolved
     * through the same provider/transport/custom checks, the whole ladder must not repeat a
     * resolver, and at least one row must stay enabled. Disabled rows are retained in position
     * in the committed selection so the ordered editor round-trips them. The active projection
     * is what the runtime later maps to a plan; a combination the runtime cannot serve is
     * reported at commit as [ResolverDnsEditOutcome.Unserved], never silently rewritten.
     */
    private fun saveTiers(
        tiers: List<ResolverTierChoice>,
        limits: FeatureLimits,
        catalogue: ResolverCatalogue,
        current: ResolverSelection? = null,
    ): ResolverEditResult {
        if (tiers.size !in 1..2) return ResolverEditResult.Rejected(ResolverEditRejection.InvalidTierCount)
        if (tiers.none { it.isEnabled }) {
            return ResolverEditResult.Rejected(ResolverEditRejection.InvalidTierCount)
        }

        val resolved = tiers.map { resolveTier(it, limits, catalogue) }
        for (resolution in resolved) {
            if (resolution is TierResolution.Failed) {
                return ResolverEditResult.Rejected(resolution.rejection)
            }
        }

        val ladder = tiers.mapIndexed { index, choice ->
            // Old decoded rows may retain a label separately from a custom resolver's name.
            // An unchanged row is already validated above; keep all of that row's metadata.
            current?.tiers?.getOrNull(index)?.takeIf { it.toChoice() == choice } ?: ResolverTier(
                (resolved[index] as TierResolution.Resolved).entry,
                choice.isEnabled,
                retainedName = if (choice.isCustom) null else choice.normalizedName,
            )
        }
        if (ladder.map { it.entry.endpointIdentity() }.distinct().size != ladder.size) {
            return ResolverEditResult.Rejected(ResolverEditRejection.DuplicateTier)
        }

        return ResolverEditResult.Accepted(
            ResolverSelectionCommit(
                selection = ResolverSelection(ladder),
                tiers = resolved.map { (it as TierResolution.Resolved).normalized },
            ),
        )
    }

    private fun setTierEnabled(
        index: Int,
        enabled: Boolean,
        current: ResolverSelection,
        limits: FeatureLimits,
        catalogue: ResolverCatalogue,
    ): ResolverEditResult {
        if (index !in current.tiers.indices) {
            return ResolverEditResult.Rejected(ResolverEditRejection.InvalidTierCount)
        }
        val ladder = current.tiers.toMutableList()
        ladder[index] = ladder[index].copy(isEnabled = enabled)
        return saveTiers(ladder.map { it.toChoice() }, limits, catalogue)
    }

    private fun setFallbackEnabled(
        enabled: Boolean,
        current: ResolverSelection,
        limits: FeatureLimits,
        catalogue: ResolverCatalogue,
    ): ResolverEditResult {
        // The encrypted-fallback toggle addresses the second retained row. Enabling with no
        // second row appends the pinned Mullvad DoH rung; disabling retains the row disabled so
        // its identity survives a later re-enable (iOS setUsesEncryptedDeviceDNSFallback).
        val ladder = current.tiers.toMutableList()
        if (!enabled) {
            if (ladder.size >= 2) ladder[1] = ladder[1].copy(isEnabled = false)
            return saveTiers(ladder.map { it.toChoice() }, limits, catalogue)
        }
        if (ladder.size >= 2) {
            ladder[1] = ladder[1].copy(isEnabled = true)
            return saveTiers(ladder.map { it.toChoice() }, limits, catalogue)
        }
        return saveTiers(
            ladder.map { it.toChoice() } + ResolverTierChoice(
                providerId = ResolverProviderId.MULLVAD.rawId,
                transportToken = CatalogResolverTransport.DOH.token,
            ),
            limits,
            catalogue,
        )
    }

    private sealed interface TierResolution {
        data class Resolved(
            val provider: ResolverProvider,
            val entry: ResolverSelectionEntry,
            val normalized: ResolverTierChoice,
        ) : TierResolution

        data class Failed(val rejection: ResolverEditRejection) : TierResolution
    }

    private fun resolveTier(
        choice: ResolverTierChoice,
        limits: FeatureLimits,
        catalogue: ResolverCatalogue,
    ): TierResolution {
        val provider = catalogue.providerByRawId(choice.providerId)
            ?: return TierResolution.Failed(ResolverEditRejection.UnknownProvider(choice.providerId))
        val transport = ResolverTransportTokens.fromToken(choice.transportToken)
            ?: return TierResolution.Failed(ResolverEditRejection.UnknownTransport(choice.transportToken))

        if (provider.id == ResolverProviderId.CUSTOM) {
            if (!limits.allowsCustomDNS) {
                return TierResolution.Failed(ResolverEditRejection.CustomRequiresPlus)
            }
            when (val validation = CustomResolverValidator.validate(choice.toDraft())) {
                is CustomResolverValidation.Invalid ->
                    return TierResolution.Failed(ResolverEditRejection.CustomInvalid(validation.reason))
                CustomResolverValidation.Valid -> Unit
            }
            val parsed = CustomResolverValidator.parse(choice.trimmedPrimary)
            val parsedTransport = parsed?.transport?.toCatalogTransport()
            if (parsedTransport != transport) {
                return TierResolution.Failed(ResolverEditRejection.CustomTransportMismatch)
            }
            // Custom DoH, DoT and DoQ endpoints each carry the parser's canonical value
            // so selection, persistence and the actual runtime agree. Plain custom
            // endpoints remain refused by the capability check below.
            val customDohUrl = if (transport == CatalogResolverTransport.DOH) {
                CustomResolverValidator.normalizedDohUrl(choice.trimmedPrimary)
            } else {
                null
            }
            val customDotEndpoint = if (transport == CatalogResolverTransport.DOT) {
                CustomResolverValidator.normalizedDotEndpoint(choice.trimmedPrimary)
            } else {
                null
            }
            val customDoqEndpoint = if (transport == CatalogResolverTransport.DOQ) {
                CustomResolverValidator.normalizedDoqEndpoint(choice.trimmedPrimary)
            } else null
            // A secondary address is the same transport as the primary (CustomResolverValidator
            // already enforced that), normalized exactly like the primary so the runtime can fall
            // back to it. It is carried, never silently discarded.
            val customSecondaryEndpoint = if (choice.trimmedSecondary.isEmpty()) {
                null
            } else {
                when (transport) {
                    CatalogResolverTransport.DOH ->
                        CustomResolverValidator.normalizedDohUrl(choice.trimmedSecondary)
                    CatalogResolverTransport.DOT ->
                        CustomResolverValidator.normalizedDotEndpoint(choice.trimmedSecondary)
                    CatalogResolverTransport.DOQ ->
                        CustomResolverValidator.normalizedDoqEndpoint(choice.trimmedSecondary)
                    CatalogResolverTransport.PLAIN_DNS -> CustomResolverValidator.normalizedPlainEndpoint(choice.trimmedSecondary)
                }
            }
            val customPlainEndpoint = if (transport == CatalogResolverTransport.PLAIN_DNS) CustomResolverValidator.normalizedPlainEndpoint(choice.trimmedPrimary) else null
            val capability = catalogue.capability(provider, transport, customDohUrl, customDotEndpoint, customDoqEndpoint, customPlainEndpoint)
            if (!capability.available) {
                return TierResolution.Failed(
                    ResolverEditRejection.TransportUnavailable(
                        provider.id,
                        transport,
                        capability.unavailableReason
                            ?: ResolverTransportUnavailableReason.CUSTOM_RESOLVER_RUNTIME_ABSENT,
                    ),
                )
            }
            val entry = ResolverSelectionEntry(
                provider.id,
                transport,
                customDohUrl = customDohUrl,
                customDotEndpoint = customDotEndpoint,
                customDoqEndpoint = customDoqEndpoint,
                customPlainEndpoint = customPlainEndpoint,
                customName = choice.normalizedName,
                customSecondaryEndpoint = customSecondaryEndpoint,
            )
            val normalized = ResolverTierChoice(
                providerId = provider.id.rawId,
                transportToken = transport.token,
                name = choice.normalizedName ?: "",
                primary = choice.trimmedPrimary,
                secondary = choice.trimmedSecondary,
                isEnabled = choice.isEnabled,
            )
            return TierResolution.Resolved(provider, entry, normalized)
        }

        if (transport !in provider.supportedTransports) {
            return TierResolution.Failed(ResolverEditRejection.TransportNotOffered(provider.id, transport))
        }
        val capability = catalogue.capability(provider, transport)
        if (!capability.available) {
            val reason = capability.unavailableReason ?: return TierResolution.Failed(
                ResolverEditRejection.TransportNotOffered(provider.id, transport),
            )
            return TierResolution.Failed(ResolverEditRejection.TransportUnavailable(provider.id, transport, reason))
        }

        val entry = ResolverSelectionEntry(provider.id, transport)
        val normalized = ResolverTierChoice(
            providerId = provider.id.rawId,
            transportToken = transport.token,
            name = choice.normalizedName ?: "",
            isEnabled = choice.isEnabled,
        )
        return TierResolution.Resolved(provider, entry, normalized)
    }
}

/** The normalized editable pair for one retained row, including its enablement. */
internal fun ResolverTier.toChoice(): ResolverTierChoice = ResolverTierChoice(
    providerId = entry.providerId.rawId,
    transportToken = entry.transport.token,
    name = retainedName ?: entry.customName ?: "",
    primary = entry.customDohUrl ?: entry.customDotEndpoint ?: entry.customDoqEndpoint ?: entry.customPlainEndpoint ?: "",
    secondary = entry.customSecondaryEndpoint ?: "",
    isEnabled = isEnabled,
)
