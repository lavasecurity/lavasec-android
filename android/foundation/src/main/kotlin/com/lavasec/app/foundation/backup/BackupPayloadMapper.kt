package com.lavasec.app.foundation.backup

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CustomBlocklistSource
import com.lavasec.app.foundation.filter.FilterIdentityPolicy
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.CustomResolverValidator
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.foundation.share.ShareableSourceUrlValidator
import com.lavasec.app.foundation.preferences.GuardianProgressPolicy
import java.util.Collections
import java.util.LinkedHashSet

/** A payload could not be mapped onto Android's filter/resolver models. */
class BackupPayloadMappingException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * The retention and protection-intent facts a backup carries beside the filter library: the four
 * diagnostics-retention toggles plus the advisory protection-enabled hint. None of these can be
 * derived from the filter drafts, so the caller supplies them explicitly.
 */
data class BackupRetainedPreferences(
    val keepFilteringCounts: Boolean = true,
    val keepDomainDiagnostics: Boolean = false,
    val keepNetworkActivity: Boolean = true,
    val keepLavaGuardProgress: Boolean = true,
    val protectionEnabledHint: Boolean = false,
) {
    companion object {
        val DEFAULT = BackupRetainedPreferences()
    }
}

/** The Android-side result of restoring a payload: the library, its resolver plan and the facts. */
data class RestoredBackupSettings(
    val library: FilterLibraryDraft,
    val preferences: BackupRetainedPreferences,
    val lavaGuardUnlocks: BackupLavaGuardAchievementLedger = BackupLavaGuardAchievementLedger(),
)

/**
 * The canonical wire vocabulary for resolver presets, used to bridge between Android's
 * `ResolverProviderId` values and the iOS `DNSResolverPreset` ids the payload stores.
 */
object BackupResolverPresetBridge {
    const val DEVICE_DNS_ID = "device-dns"

    /**
     * Android's retained legacy milestone route uses Mullvad DoH. iOS retired the Mullvad
     * public resolver and migrates `mullvad-doh` to `quad9-unfiltered-doh`, so an Android payload
     * written with this id restores correctly on iOS through that migration.
     */
    const val ANDROID_MULLVAD_DOH_ID = "mullvad-doh"

    /**
     * The canonical id for a user-supplied resolver. The concrete endpoint (a normalized DoH URL
     * or a `tls://host:port` / `doq://host:port` endpoint) travels in `customResolverAddress`, so a
     * custom selection is not lost across backup/restore.
     */
    const val CUSTOM_DNS_ID = "custom-dns"

    /** The canonical id Android writes for one of its provider ids. */
    fun canonicalId(providerId: ResolverProviderId): String = when (providerId) {
        ResolverProviderId.DEVICE_DNS -> DEVICE_DNS_ID
        ResolverProviderId.MULLVAD -> ANDROID_MULLVAD_DOH_ID
        ResolverProviderId.CLOUDFLARE -> "cloudflare-1111"
        ResolverProviderId.QUAD9_SECURE -> "quad9-secure"
        ResolverProviderId.GOOGLE_PUBLIC_DNS -> "google-public-dns"
        ResolverProviderId.CUSTOM -> CUSTOM_DNS_ID
        else -> providerId.rawId
    }

    /** The Android provider a canonical preset id names, or `null` when Android has no equivalent. */
    fun androidProviderId(presetId: String): ResolverProviderId? =
        if (presetId == CUSTOM_DNS_ID) ResolverProviderId.CUSTOM
        else ResolverCatalog.entryForPresetId(presetId, migrateRetired = true)?.providerId
}

/**
 * Enforces the exclusion boundary: facts that must NEVER enter a portable backup. The payload model
 * has no field for any of these, and [BackupPayloadMapper.toPayload] can only ever produce the
 * inventory below, so the boundary is structural rather than a redaction pass. The list is exposed
 * so a test can assert the serialized field inventory stays clean.
 */
object BackupPayloadExclusions {
    val EXCLUDED_FIELD_NAMES: Set<String> = Collections.unmodifiableSet(
        LinkedHashSet(
            listOf(
                // Chained upstream private material.
                "privateKey", "presharedKey", "preSharedKey", "chainedPrivateKey", "chainedKey",
                "wireGuardKey", "tunnelPrivateKey",
                // Device eligibility / entitlement device state.
                "deviceEligibility", "eligibleDevice", "chainedDeviceEligibility",
                "eligibilityStore", "deviceEligibilityStore",
                // OAuth / session tokens.
                "accessToken", "refreshToken", "idToken", "sessionToken", "oauthToken",
                "authToken", "supabaseSession", "session", "credential",
                // Play receipts / purchase state.
                "playPurchaseToken", "purchaseToken", "playReceipt", "receipt",
                "obfuscatedAccountId", "obfuscatedProfileId", "acknowledgement",
                // Credential handles / secrets.
                "credentialHandle", "credentialID", "credentialId", "keychainSecret",
                "passkeyCredential", "passkeyPRF", "serverRecoveryShare",
                // Device-local caches / history.
                "lastCompiledToken", "lastSyncedAt", "domainHistory", "networkActivity",
                "topDomains", "filteringCount", "qualifiedUsageDay", "usageByDay",
                "activeUsageStartedAt", "activityRows", "snapshot",
            ),
        ),
    )
}

/**
 * Pure mapper between Android's filter library drafts and the canonical portable payload.
 *
 * The library draft is Android's authoritative filter record: its active profile supplies the
 * payload's top-level filter-scoped fields, and the whole library travels as `filterLibrary` so a
 * multi-filter backup survives. The resolver plan and the supplied [BackupRetainedPreferences]
 * supply the device-global fields.
 *
 * Deliberate, documented exclusions (see also [BackupPayloadExclusions]):
 * - chained private keys, device eligibility, OAuth/session tokens, Play receipts and credential
 *   handles have no representation and are never read or written;
 * - per-filter `createdAt`/`lastCompiledToken`/`lastSyncedAt` are not part of Android's draft model,
 *   so Android writes a deterministic `0`/absent value (`lastCompiledToken`/`lastSyncedAt` are the
 *   device-local cache fields iOS strips from backups anyway);
 * - the Lava Guard ledger is supplied by the actual progress owner at capture and adopted through
 *   its durable compare-and-replace port; local qualified usage stays excluded;
 * - A custom primary (one usable IP literal, DoH, DoT, DoQ or a supported DNS stamp) and its optional same-transport secondary/name
 *   are carried under the canonical `custom-dns` preset id, so they round-trip through
 *   backup and restore; malformed or runtime-unserved rows are refused before publication. Legacy
 *   retired preset headers migrate explicitly through the frozen catalog; retained rows preserve
 *   exact identities and never use that migration.
 */
object BackupPayloadMapper {
    const val DEFAULT_FILTER_ID = "default"
    const val DEFAULT_FILTER_NAME = "Default"

    /** Seconds from the Apple 2001-01-01 reference date to the Unix epoch. */
    private const val APPLE_REFERENCE_DATE_SECONDS = 978_307_200.0

    fun toPayload(
        library: FilterLibraryDraft,
        preferences: BackupRetainedPreferences = BackupRetainedPreferences.DEFAULT,
        lavaGuardUnlocks: BackupLavaGuardAchievementLedger = BackupLavaGuardAchievementLedger(),
    ): BackupConfigurationPayload {
        val active = library.profile(library.activeProfileId)
            ?: throw BackupPayloadMappingException("active filter '${library.activeProfileId}' is missing")
        val resolver = resolverFields(library)
        return BackupConfigurationPayload(
            schemaVersion = when {
                library.savedTiers?.any { !it.isEnabled } == true -> 3
                resolver.usesExplicitDNSTiers -> 2
                else -> 1
            },
            enabledBlocklistIDs = active.enabledSourceIds,
            allowedDomains = active.manualRules.allowedDomains,
            blockedDomains = active.manualRules.blockedDomains,
            resolverPresetID = resolver.primaryPresetID,
            customResolverAddress = resolver.customResolverAddress,
            customResolverSecondaryAddress = resolver.customSecondaryEndpoint,
            customResolverName = resolver.customName,
            fallbackToDeviceDNS = resolver.fallbackToDeviceDNS,
            usesExplicitDNSTiers = resolver.usesExplicitDNSTiers,
            usesEncryptedDeviceDNSFallback = resolver.usesEncryptedDeviceDNSFallback,
            fallbackResolverPresetID = resolver.fallbackPresetID,
            fallbackCustomResolverAddress = resolver.fallbackCustomAddress,
            fallbackCustomResolverSecondaryAddress = resolver.fallbackCustomSecondary,
            fallbackCustomResolverName = resolver.fallbackCustomName,
            savedDNSResolutionSelections = library.savedTiers?.map(::toWireResolverRow),
            keepFilteringCounts = preferences.keepFilteringCounts,
            keepDomainDiagnostics = preferences.keepDomainDiagnostics,
            keepNetworkActivity = preferences.keepNetworkActivity,
            keepLavaGuardProgress = preferences.keepLavaGuardProgress,
            protectionEnabledHint = preferences.protectionEnabledHint,
            lavaGuardUnlocks = normalizedLedger(lavaGuardUnlocks),
            filterLibrary = BackupFilterLibrary(
                schemaVersion = BackupFilterLibrary.CURRENT_SCHEMA_VERSION,
                filters = library.profiles.map(::toWireFilter),
                activeFilterID = library.activeProfileId,
                configurationGeneration = 0,
            ),
        )
    }

    fun restore(payload: BackupConfigurationPayload): RestoredBackupSettings {
        if (payload.schemaVersion > BackupConfigurationPayload.CURRENT_SUPPORTED_SCHEMA_VERSION) {
            throw BackupSchemaUnsupportedException(payload.schemaVersion)
        }
        val profiles = profilesFor(payload)
        if (profiles.isEmpty()) throw BackupPayloadMappingException("backup carries no filters")
        val libraryWire = payload.filterLibrary
        val activeId = libraryWire?.activeFilterID?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        val resolver = restoredResolver(payload)
        val library = try {
            FilterLibraryDraft(
                profiles = profiles,
                activeProfileId = activeId,
                resolverPlan = resolver.plan,
                customDohUrl = resolver.customDohUrl,
                customDotEndpoint = resolver.customDotEndpoint,
                customDoqEndpoint = resolver.customDoqEndpoint,
                customSecondaryEndpoint = resolver.customSecondaryEndpoint,
                customName = resolver.customName,
                savedTiers = resolver.savedTiers,
            )
        } catch (error: IllegalArgumentException) {
            throw BackupPayloadMappingException("backup filter library is not restorable", error)
        }
        return RestoredBackupSettings(
            library = library,
            lavaGuardUnlocks = normalizedLedger(payload.lavaGuardUnlocks),
            preferences = BackupRetainedPreferences(
                keepFilteringCounts = payload.keepFilteringCounts,
                keepDomainDiagnostics = payload.keepDomainDiagnostics,
                keepNetworkActivity = payload.keepNetworkActivity,
                keepLavaGuardProgress = payload.keepLavaGuardProgress,
                protectionEnabledHint = payload.protectionEnabledHint,
            ),
        )
    }

    private fun normalizedLedger(ledger: BackupLavaGuardAchievementLedger): BackupLavaGuardAchievementLedger =
        try { GuardianProgressPolicy.normalize(ledger) } catch (error: IllegalArgumentException) {
            throw BackupPayloadMappingException("backup achievement ledger is not restorable", error)
        }

    private fun toWireFilter(profile: FilterProfileDraft): BackupFilter = BackupFilter(
        id = profile.id,
        name = profile.name,
        emoji = profile.emoji,
        enabledBlocklistIDs = profile.enabledSourceIds,
        customBlocklists = profile.customBlocklists.map { source ->
            BackupCustomBlocklistSource(
                id = source.id,
                displayName = source.displayName,
                sourceURL = source.sourceUrl,
                parseFormat = source.parseFormat.name.lowercase(),
                createdAt = (source.createdAtEpochMillis / 1000.0) - APPLE_REFERENCE_DATE_SECONDS,
                lastAcceptedHash = source.lastAcceptedHash,
            )
        },
        blockedDomains = profile.manualRules.blockedDomains,
        allowedDomains = profile.manualRules.allowedDomains,
        createdAt = 0.0,
    )

    private fun toDurableCustomSource(source: BackupCustomBlocklistSource): CustomBlocklistSource {
        val format = BlocklistFormat.entries.firstOrNull {
            it.name.equals(source.parseFormat, ignoreCase = true)
        } ?: throw BackupPayloadMappingException("backup custom blocklist has an unsupported parse format")
        return CustomBlocklistSource(
            id = source.id,
            displayName = source.displayName,
            sourceUrl = source.sourceURL,
            parseFormat = format,
            createdAtEpochMillis = ((source.createdAt + APPLE_REFERENCE_DATE_SECONDS) * 1000.0).toLong(),
            lastAcceptedHash = source.lastAcceptedHash?.takeIf { it.isNotBlank() },
        )
    }

    private fun profilesFor(payload: BackupConfigurationPayload): List<FilterProfileDraft> {
        val library = payload.filterLibrary
        val filters = if (library != null && library.filters.isNotEmpty()) {
            library.filters
        } else {
            // Pre-multi-filter backup: migrate the top-level filter-scoped fields into one filter,
            // exactly like iOS `FilterLibrary(migratingLegacy:)`.
            listOf(
                BackupFilter(
                    id = DEFAULT_FILTER_ID,
                    name = DEFAULT_FILTER_NAME,
                    emoji = "",
                    enabledBlocklistIDs = payload.enabledBlocklistIDs,
                    blockedDomains = payload.blockedDomains,
                    allowedDomains = payload.allowedDomains,
                    customBlocklists = payload.customBlocklists,
                ),
            )
        }
        return filters.map { filter ->
            val id = filter.id.ifBlank { DEFAULT_FILTER_ID }
            val name = FilterIdentityPolicy.normalizedName(filter.name).ifBlank { DEFAULT_FILTER_NAME }
            val manualRules = manualRulesOrThrow(id, filter.allowedDomains, filter.blockedDomains)
            try {
                FilterProfileDraft(
                    id = id,
                    name = name,
                    protectionLevel = protectionLevelFor(id),
                    enabledSourceIds = filter.enabledBlocklistIDs.filter(String::isNotBlank).toSet(),
                    manualRules = manualRules,
                    emoji = filter.emoji.takeIf(String::isNotBlank),
                    customBlocklists = customSourcesOrThrow(id, filter.customBlocklists),
                )
            } catch (error: IllegalArgumentException) {
                throw BackupPayloadMappingException("backup filter '$id' is not restorable", error)
            }
        }
    }

    /**
     * Restores a backup's custom blocklists, re-validating every URL through the same public-source
     * gate an unsigned share import uses. A crafted backup cannot point the syncer at a private or
     * non-HTTPS URL: the whole filter is refused instead of silently dropping the source.
     */
    private fun customSourcesOrThrow(
        filterId: String,
        sources: List<BackupCustomBlocklistSource>,
    ): List<CustomBlocklistSource> {
        sources.forEach { source ->
            if (ShareableSourceUrlValidator.validate(source.sourceURL) != null) {
                throw BackupPayloadMappingException(
                    "backup filter '$filterId' has an invalid custom blocklist URL",
                )
            }
        }
        return sources.map(::toDurableCustomSource)
    }

    private fun manualRulesOrThrow(
        filterId: String,
        allowedDomains: Set<String>,
        blockedDomains: Set<String>,
    ): ManualDomainRules = try {
        ManualDomainRules(allowedDomains = allowedDomains, blockedDomains = blockedDomains)
    } catch (error: IllegalArgumentException) {
        throw BackupPayloadMappingException("backup filter '$filterId' has invalid manual rules", error)
    }

    private fun protectionLevelFor(id: String): ProtectionLevel? =
        ProtectionLevel.entries.firstOrNull { it.seededFilterId == id }

    /** Frozen legacy ladder semantics, including explicit migration of retired preset headers. */
    private fun restoredResolver(payload: BackupConfigurationPayload): RestoredResolver {
        if (payload.savedDNSResolutionSelections != null || payload.usesExplicitDNSTiers) {
            return restoredOrderedResolver(payload)
        }
        val primary = restoredHeaderRow(BackupDNSResolutionSelection(payload.resolverPresetID,
            payload.customResolverName.orEmpty(), payload.customResolverAddress.orEmpty(),
            payload.customResolverSecondaryAddress.orEmpty()), unknownFallback = "device-dns")
        val rows = buildList {
            add(primary)
            if (primary.entry.providerId == ResolverProviderId.DEVICE_DNS && payload.usesEncryptedDeviceDNSFallback) {
                add(restoredHeaderRow(BackupDNSResolutionSelection(payload.fallbackResolverPresetID,
                    payload.fallbackCustomResolverName.orEmpty(), payload.fallbackCustomResolverAddress.orEmpty(),
                    payload.fallbackCustomResolverSecondaryAddress.orEmpty()),
                    unknownFallback = BackupConfigurationPayload.DEFAULT_FALLBACK_RESOLVER_PRESET_ID))
            } else if (primary.entry.providerId != ResolverProviderId.DEVICE_DNS && payload.fallbackToDeviceDNS) {
                add(ResolverTier(ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS)))
            }
        }
        return restoredSelection(ResolverSelection(rows))
    }

    private fun resolverFields(library: FilterLibraryDraft): ResolverFields {
        // The retained rows carry identities/order the legacy milestone flags cannot express.
        // A Device primary with a custom encrypted fallback also needs the explicit two-slot wire.
        if (library.savedTiers != null || (library.resolverPlan == MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK &&
                (library.customDohUrl != null || library.customDotEndpoint != null || library.customDoqEndpoint != null))) {
            return orderedResolverFields(library.resolverTarget.toSelection())
        }
        val deviceId = BackupResolverPresetBridge.canonicalId(ResolverProviderId.DEVICE_DNS)
        val mullvadId = BackupResolverPresetBridge.canonicalId(ResolverProviderId.MULLVAD)
        // A custom selection replaces the encrypted resolver with the user's endpoint; the
        // endpoint is the value the runtime connects to, and it is exactly what a restore carries.
        val customEndpoint = library.customDohUrl ?: library.customDotEndpoint ?: library.customDoqEndpoint
        if (customEndpoint != null) {
            return ResolverFields(
                primaryPresetID = BackupResolverPresetBridge.CUSTOM_DNS_ID,
                customResolverAddress = customEndpoint,
                fallbackToDeviceDNS = false,
                usesEncryptedDeviceDNSFallback = false,
                fallbackPresetID = BackupConfigurationPayload.DEFAULT_FALLBACK_RESOLVER_PRESET_ID,
                customSecondaryEndpoint = library.customSecondaryEndpoint,
                customName = library.customName,
            )
        }
        return when (library.resolverPlan) {
            MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK -> ResolverFields(
                primaryPresetID = deviceId,
                customResolverAddress = null,
                fallbackToDeviceDNS = true,
                usesEncryptedDeviceDNSFallback = true,
                fallbackPresetID = mullvadId,
            )

            MilestoneResolverPlan.DEVICE_DNS_ONLY -> ResolverFields(
                primaryPresetID = deviceId,
                customResolverAddress = null,
                fallbackToDeviceDNS = true,
                usesEncryptedDeviceDNSFallback = false,
                fallbackPresetID = BackupConfigurationPayload.DEFAULT_FALLBACK_RESOLVER_PRESET_ID,
            )

            MilestoneResolverPlan.MULLVAD_ONLY -> ResolverFields(
                primaryPresetID = mullvadId,
                customResolverAddress = null,
                fallbackToDeviceDNS = false,
                usesEncryptedDeviceDNSFallback = false,
                fallbackPresetID = BackupConfigurationPayload.DEFAULT_FALLBACK_RESOLVER_PRESET_ID,
            )
        }
    }

    private data class ResolverFields(
        val primaryPresetID: String,
        val customResolverAddress: String?,
        val fallbackToDeviceDNS: Boolean,
        val usesEncryptedDeviceDNSFallback: Boolean,
        val fallbackPresetID: String,
        val customSecondaryEndpoint: String? = null,
        val customName: String? = null,
        val usesExplicitDNSTiers: Boolean = false,
        val fallbackCustomAddress: String? = null,
        val fallbackCustomSecondary: String? = null,
        val fallbackCustomName: String? = null,
    )

    private data class RestoredResolver(
        val plan: MilestoneResolverPlan,
        val customDohUrl: String? = null,
        val customDotEndpoint: String? = null,
        val customDoqEndpoint: String? = null,
        val customSecondaryEndpoint: String? = null,
        val customName: String? = null,
        val savedTiers: List<ResolverTier>? = null,
    )

    private fun endpoint(entry: ResolverSelectionEntry): String? = if (entry.customPlainEndpoint != null) {
        CustomResolverValidator.normalizedPlainEndpoint(entry.customPlainEndpoint)
            ?: throw BackupPayloadMappingException("backup DNS row has an unusable custom IP primary")
    } else entry.customDohUrl ?: entry.customDotEndpoint ?: entry.customDoqEndpoint

    private fun secondary(entry: ResolverSelectionEntry): String? =
        if (entry.providerId == ResolverProviderId.CUSTOM && entry.transport == CatalogResolverTransport.PLAIN_DNS) {
            entry.customSecondaryEndpoint?.let { raw -> CustomResolverValidator.normalizedPlainEndpoint(raw)
                ?: throw BackupPayloadMappingException("backup DNS row has an unusable custom IP secondary") }
        } else entry.customSecondaryEndpoint

    /** Exact preset/transport identity. Do not apply the lossy legacy preset migration to rows. */
    private fun wireResolverID(entry: ResolverSelectionEntry): String = ResolverCatalog.presetId(entry)

    private fun toWireResolverRow(tier: ResolverTier) = BackupDNSResolutionSelection(
        id = wireResolverID(tier.entry),
        name = tier.retainedName ?: tier.entry.customName.orEmpty(),
        primary = endpoint(tier.entry).orEmpty(),
        secondary = secondary(tier.entry).orEmpty(),
        isEnabled = tier.isEnabled,
    )

    private fun orderedResolverFields(selection: ResolverSelection): ResolverFields {
        val primary = selection.primary
        val fallback = selection.fallback
        return ResolverFields(
            primaryPresetID = wireResolverID(primary),
            customResolverAddress = endpoint(primary),
            customSecondaryEndpoint = secondary(primary),
            customName = primary.customName,
            fallbackToDeviceDNS = fallback?.providerId == ResolverProviderId.DEVICE_DNS,
            usesEncryptedDeviceDNSFallback = fallback != null && fallback.providerId != ResolverProviderId.DEVICE_DNS,
            fallbackPresetID = fallback?.takeUnless { it.providerId == ResolverProviderId.DEVICE_DNS }
                ?.let(::wireResolverID) ?: BackupConfigurationPayload.DEFAULT_FALLBACK_RESOLVER_PRESET_ID,
            usesExplicitDNSTiers = true,
            fallbackCustomAddress = fallback?.let(::endpoint),
            fallbackCustomSecondary = fallback?.let(::secondary),
            fallbackCustomName = fallback?.customName,
        )
    }

    private fun restoredOrderedResolver(payload: BackupConfigurationPayload): RestoredResolver = try {
        val rows = payload.savedDNSResolutionSelections ?: buildList {
            add(BackupDNSResolutionSelection(payload.resolverPresetID, payload.customResolverName.orEmpty(),
                payload.customResolverAddress.orEmpty(), payload.customResolverSecondaryAddress.orEmpty()))
            if (payload.fallbackToDeviceDNS) {
                add(BackupDNSResolutionSelection(BackupResolverPresetBridge.DEVICE_DNS_ID))
            } else if (payload.usesEncryptedDeviceDNSFallback) {
                add(BackupDNSResolutionSelection(payload.fallbackResolverPresetID, payload.fallbackCustomResolverName.orEmpty(),
                    payload.fallbackCustomResolverAddress.orEmpty(), payload.fallbackCustomResolverSecondaryAddress.orEmpty()))
            }
        }
        val selection = ResolverSelection(rows.map { row ->
            if (payload.savedDNSResolutionSelections != null) restoredResolverRow(row)
            else restoredHeaderRow(row)
        })
        if (payload.savedDNSResolutionSelections != null) {
            // iOS retains rows only while their enabled projection agrees with its legacy fields.
            // Refuse a conflicting payload before publication instead of losing one representation.
            val projection = orderedResolverFields(selection)
            val primary = restoredResolverRow(BackupDNSResolutionSelection(payload.resolverPresetID,
                payload.customResolverName.orEmpty(), payload.customResolverAddress.orEmpty(),
                payload.customResolverSecondaryAddress.orEmpty())).entry
            require(selection.primary == primary) { "saved DNS primary disagrees with the backup header" }
            require(payload.fallbackToDeviceDNS == projection.fallbackToDeviceDNS &&
                payload.usesEncryptedDeviceDNSFallback == projection.usesEncryptedDeviceDNSFallback) {
                "saved DNS fallback disagrees with the backup header"
            }
            if (payload.usesEncryptedDeviceDNSFallback) {
                val fallback = restoredResolverRow(BackupDNSResolutionSelection(payload.fallbackResolverPresetID,
                    payload.fallbackCustomResolverName.orEmpty(), payload.fallbackCustomResolverAddress.orEmpty(),
                    payload.fallbackCustomResolverSecondaryAddress.orEmpty())).entry
                require(selection.fallback == fallback) { "saved DNS fallback identity disagrees with the backup header" }
            }
        }
        restoredSelection(selection)
    } catch (error: IllegalArgumentException) {
        throw BackupPayloadMappingException("backup DNS ladder is not restorable", error)
    }

    private fun restoredResolverRow(row: BackupDNSResolutionSelection): ResolverTier {
        val name = row.name.trim().takeIf(String::isNotEmpty)
        val entry = if (row.id == BackupResolverPresetBridge.CUSTOM_DNS_ID) {
            val doh = CustomResolverValidator.normalizedDohUrl(row.primary)
            val dot = CustomResolverValidator.normalizedDotEndpoint(row.primary)
            val doq = CustomResolverValidator.normalizedDoqEndpoint(row.primary)
            val plain = CustomResolverValidator.normalizedPlainEndpoint(row.primary)
            val transport = when {
                doh != null -> CatalogResolverTransport.DOH
                dot != null -> CatalogResolverTransport.DOT
                doq != null -> CatalogResolverTransport.DOQ
                plain != null -> CatalogResolverTransport.PLAIN_DNS
                else -> throw BackupPayloadMappingException("backup DNS row has an unsupported custom primary")
            }
            val secondary = row.secondary.trim().takeIf(String::isNotEmpty)?.let { raw ->
                when (transport) {
                    CatalogResolverTransport.DOH -> CustomResolverValidator.normalizedDohUrl(raw)
                    CatalogResolverTransport.DOT -> CustomResolverValidator.normalizedDotEndpoint(raw)
                    CatalogResolverTransport.DOQ -> CustomResolverValidator.normalizedDoqEndpoint(raw)
                    CatalogResolverTransport.PLAIN_DNS -> CustomResolverValidator.normalizedPlainEndpoint(raw)
                    else -> null
                } ?: throw BackupPayloadMappingException("backup DNS row secondary does not match its transport")
            }
            ResolverSelectionEntry(ResolverProviderId.CUSTOM, transport, customDohUrl = doh, customDotEndpoint = dot,
                customName = name, customSecondaryEndpoint = secondary, customDoqEndpoint = doq,
                customPlainEndpoint = plain)
        } else {
            ResolverCatalog.entryForPresetId(row.id, migrateRetired = false)
                ?: throw BackupPayloadMappingException("backup DNS row names an unknown or unserved provider")
        }
        return ResolverTier(entry, row.isEnabled,
            retainedName = name.takeIf { entry.providerId != ResolverProviderId.CUSTOM })
    }

    private fun restoredHeaderRow(row: BackupDNSResolutionSelection, unknownFallback: String? = null): ResolverTier {
        if (row.id == BackupResolverPresetBridge.CUSTOM_DNS_ID) return restoredResolverRow(row)
        val migrated = ResolverCatalog.entryForPresetId(row.id, migrateRetired = true)
        if (migrated != null) return ResolverTier(migrated, row.isEnabled)
        val base = row.id.removeSuffix("-doh").removeSuffix("-dot")
        if (unknownFallback == null || ResolverProviderId.entries.any { it.rawId == base } ||
            base in setOf("dns-sb", "mullvad", "quad9-secure")) {
            throw BackupPayloadMappingException("backup DNS header names an unserved provider")
        }
        return ResolverTier(ResolverCatalog.entryForPresetId(unknownFallback)
            ?: throw BackupPayloadMappingException("backup DNS default has no runtime route"))
    }

    private fun restoredSelection(selection: ResolverSelection): RestoredResolver {
        val target = selection.toFilterResolverPlan()
            ?: throw BackupPayloadMappingException("backup DNS ladder has no runtime route")
        return RestoredResolver(target.plan, target.customDohUrl, target.customDotEndpoint, target.customDoqEndpoint,
            target.customSecondaryEndpoint, target.customName, target.savedTiers)
    }
}
