package com.lavasec.app.foundation.backup

/**
 * A payload advertising a schema newer than this build understands.
 *
 * Mirrors the iOS `BackupConfigurationPayloadError.unsupportedSchemaVersion` case: a future
 * vN+1 backup must be refused at decode time, never decoded as a lossy subset and then re-sealed
 * as a downgraded payload over the newer single-envelope backup.
 */
class BackupSchemaUnsupportedException(val schemaVersion: Int) :
    IllegalArgumentException("unsupported backup payload schemaVersion=$schemaVersion")

/** A payload could not be read as well-formed JSON under the codec's bounds. */
class BackupPayloadFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Portable settings snapshot stored inside an encrypted backup envelope.
 *
 * Field names, optionality and defaults mirror the canonical iOS
 * `BackupConfigurationPayload` (pinned at `c4658d9`) exactly, so an iOS-produced payload maps on
 * Android and an Android-produced payload maps on iOS. The wire uses camelCase keys and the iOS
 * default date strategy (seconds since the Apple 2001-01-01 reference date); see
 * [BackupPayloadJson].
 *
 * A BACKUP IS A PERSISTENCE BOUNDARY. This model deliberately carries only the portable
 * configuration: it has no field for chained private keys, device eligibility, OAuth/session
 * tokens, Play receipts, credential handles or local caches/history. See
 * [BackupPayloadExclusions] for the enforced inventory.
 */
data class BackupConfigurationPayload(
    /** At least 2 for explicit DNS tiers, and 3 when a retained DNS row is disabled. */
    val schemaVersion: Int = 1,
    /** Identifiers of curated blocklists selected when the snapshot was made. */
    val enabledBlocklistIDs: Set<String> = emptySet(),
    /** User domains allowed by the backed-up configuration. */
    val allowedDomains: Set<String> = emptySet(),
    /** User domains blocked by the backed-up configuration. */
    val blockedDomains: Set<String> = emptySet(),
    /** Identifier of the selected primary resolver preset. */
    val resolverPresetID: String,
    /** Primary custom-resolver address, when one was configured. */
    val customResolverAddress: String? = null,
    /** Secondary primary-resolver address, when one was configured. */
    val customResolverSecondaryAddress: String? = null,
    /** User-facing name of the primary custom resolver. */
    val customResolverName: String? = null,
    /** Whether unresolved primary lookups may fall back to device DNS. */
    val fallbackToDeviceDNS: Boolean = false,
    /** Whether the primary and secondary entries form an explicit ordered DNS ladder. */
    val usesExplicitDNSTiers: Boolean = false,
    /** Whether device-DNS fallback should prefer an encrypted system resolver. */
    val usesEncryptedDeviceDNSFallback: Boolean = false,
    /** Identifier of the selected fallback resolver preset. */
    val fallbackResolverPresetID: String = DEFAULT_FALLBACK_RESOLVER_PRESET_ID,
    /** Fallback custom-resolver address, when one was configured. */
    val fallbackCustomResolverAddress: String? = null,
    /** Secondary fallback-resolver address, when one was configured. */
    val fallbackCustomResolverSecondaryAddress: String? = null,
    /** User-facing name of the fallback custom resolver. */
    val fallbackCustomResolverName: String? = null,
    /** Whether filtering counters were enabled when the snapshot was made. */
    val keepFilteringCounts: Boolean = true,
    /** Whether domain-level diagnostics were enabled when the snapshot was made. */
    val keepDomainDiagnostics: Boolean = false,
    /** Whether network-activity recording was enabled when the snapshot was made. */
    val keepNetworkActivity: Boolean = true,
    /** Whether Lava Guard progress recording was enabled when the snapshot was made. */
    val keepLavaGuardProgress: Boolean = true,
    /** Lava Guard achievement progress included in the snapshot. */
    val lavaGuardUnlocks: BackupLavaGuardAchievementLedger = BackupLavaGuardAchievementLedger(),
    /** Historical hint; reviewed restores preserve the current device's protection intent. */
    val protectionEnabledHint: Boolean = false,
    /** Catalog version observed when the snapshot was made, when available. */
    val catalogVersionHint: String? = null,
    /** Custom blocklist definitions included in the snapshot. */
    val customBlocklists: List<BackupCustomBlocklistSource> = emptyList(),
    /**
     * The full multi-filter library (hosted filters + active selection). Optional so
     * pre-multi-filter backups decode to `null` and restore as a single "Default" filter.
     */
    val filterLibrary: BackupFilterLibrary? = null,
    /** Exact frozen-iOS ordered DNS rows, including inactive choices. Null for legacy payloads. */
    val savedDNSResolutionSelections: List<BackupDNSResolutionSelection>? = null,
) {
    init {
        require(schemaVersion >= 1) { "backup schemaVersion must be at least 1" }
    }

    companion object {
        /**
         * The highest schema this build can safely restore. Mirrors iOS
         * `currentSupportedSchemaVersion`; a payload above it is rejected before any state mutation.
         */
        const val CURRENT_SUPPORTED_SCHEMA_VERSION = 3

        /** iOS defaults `fallbackResolverPresetID` to the unfiltered Quad9 DoH preset. */
        const val DEFAULT_FALLBACK_RESOLVER_PRESET_ID = "quad9-unfiltered-doh"
    }
}

/** Mirrors frozen iOS DNSResolutionSelection Codable fields and decoding defaults. */
data class BackupDNSResolutionSelection(
    val id: String,
    val name: String = "",
    val primary: String = "",
    val secondary: String = "",
    val isEnabled: Boolean = true,
)

/**
 * The full multi-filter library. Mirrors iOS `FilterLibrary`, with the device-LOCAL
 * `configurationGeneration` write-race stamp carried only so a decoded payload round-trips; the
 * mapper always writes it as `0` for a backup.
 */
data class BackupFilterLibrary(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val filters: List<BackupFilter> = emptyList(),
    val activeFilterID: String = "",
    val configurationGeneration: Int = 0,
) {
    init {
        require(schemaVersion >= 1) { "filter library schemaVersion must be at least 1" }
    }

    companion object {
        /** Mirrors iOS `FilterLibrary.currentSchemaVersion`. */
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

/**
 * One named filter: the security configuration a user can switch between. Mirrors iOS `Filter`.
 * `lastCompiledToken`/`lastSyncedAt` are the device-LOCAL cache fields that iOS strips before a
 * backup (`strippingLocalCacheState`), so a portable backup never carries a real value for them.
 */
data class BackupFilter(
    val id: String,
    val name: String,
    val emoji: String,
    val enabledBlocklistIDs: Set<String> = emptySet(),
    val customBlocklists: List<BackupCustomBlocklistSource> = emptyList(),
    val blockedDomains: Set<String> = emptySet(),
    val allowedDomains: Set<String> = emptySet(),
    /** Seconds since the Apple 2001-01-01 reference date (iOS `.deferredToDate`). */
    val createdAt: Double = 0.0,
    val lastCompiledToken: String? = null,
    /** Seconds since the Apple 2001-01-01 reference date. */
    val lastSyncedAt: Double? = null,
)

/**
 * A blocklist source record and the metadata needed to parse and cache its contents. Mirrors iOS
 * `CustomBlocklistSource`; downloaded list CONTENTS are never part of the record.
 */
data class BackupCustomBlocklistSource(
    val id: String,
    val displayName: String,
    /** The stored fetch URL (`URL` encodes as a plain string in the iOS payload). */
    val sourceURL: String,
    /** `CatalogBlocklistSource.CatalogParseFormat` raw value: auto/plain_domains/hosts/adblock/dnsmasq. */
    val parseFormat: String = "auto",
    /** Seconds since the Apple 2001-01-01 reference date. */
    val createdAt: Double = 0.0,
    /** Hash of the most recently accepted source contents, when available. */
    val lastAcceptedHash: String? = null,
)

/** A ledger of Lava Guard unlock records. Mirrors iOS `LavaGuardAchievementLedger`. */
data class BackupLavaGuardAchievementLedger(
    val records: List<BackupLavaGuardUnlockRecord> = emptyList(),
)

/** A timestamped record of a guard unlock. Mirrors iOS `LavaGuardUnlockRecord`. */
data class BackupLavaGuardUnlockRecord(
    val guardID: String,
    /** Seconds since the Apple 2001-01-01 reference date. */
    val unlockedAt: Double,
)
