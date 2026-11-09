package com.lavasec.app.foundation.share

import com.lavasec.app.foundation.filter.FilterProfileDraft
import java.net.URI
import java.net.URISyntaxException

/**
 * The portable, versioned filter configuration a Lava share payload carries, ported from the
 * canonical iOS `ShareableFilterConfiguration` (pinned `c4658d9`).
 *
 * Schema version 2 carries explicit allowed exceptions and the full definitions of custom
 * blocklists. Version 1 is the legacy block-only shape: it has no allowed set (an absent set is
 * NOT an empty replacement) and its emoji identity is the only emoji the wire understands.
 * Unsupported versions are rejected by [ShareableFilterCodec], never decoded as a lossy subset.
 *
 * The payload structurally cannot carry: credentials, account/session tokens, device or network
 * details, or any local cache/bookkeeping (`createdAt`, `lastAcceptedHash`, compile tokens). It is
 * exactly the filter-scoped content: enabled blocklist source ids, user blocked/allowed domains,
 * and custom blocklist definitions.
 */
data class ShareableFilterConfiguration(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val emoji: String? = null,
    val enabledBlocklistIDs: Set<String> = emptySet(),
    val blockedDomains: Set<String> = emptySet(),
    val customBlocklists: List<ShareableCustomBlocklistSource> = emptyList(),
    val allowedDomains: Set<String>? = emptySet(),
) {
    init {
        require(schemaVersion >= 1) { "share schema version must be at least 1" }
        if (schemaVersion == 1) {
            require(allowedDomains == null) { "a version 1 payload carries no allowed set" }
        } else {
            require(allowedDomains != null) { "a version $schemaVersion payload requires its allowed set" }
        }
    }

    /** `true` when there is nothing meaningful to share or apply. */
    val isEmpty: Boolean
        get() = enabledBlocklistIDs.isEmpty() &&
            blockedDomains.isEmpty() &&
            customBlocklists.isEmpty() &&
            (allowedDomains?.isEmpty() ?: true)

    /**
     * Query/fragment data can carry credentials or private identifiers, and userinfo is the
     * classic confusable. A full-content share must refuse such a source instead of stripping its
     * parameters silently. A URL this model cannot parse is treated as private (fail closed).
     */
    val containsPrivateSourceParameters: Boolean
        get() = customBlocklists.any { source ->
            try {
                val uri = URI(source.sourceUrl.trim())
                uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null
            } catch (_: URISyntaxException) {
                true
            }
        }

    /** The selection fields a review diff compares. */
    val selection: FilterConfigurationSelection
        get() = FilterConfigurationSelection(
            enabledBlocklistIDs = enabledBlocklistIDs,
            blockedDomains = blockedDomains,
            allowedDomains = allowedDomains.orEmpty(),
        )

    /**
     * Whether this configuration is small enough for a recipient to import, gated on BOTH caps
     * the decoder enforces: the encoded-code character budget AND the uncompressed payload byte
     * budget. The latter matters because a highly compressible but very large setup can yield a
     * short code that still blows the inflate limit on import.
     */
    fun fitsShareableCodeCapacity(): Boolean = ShareableFilterCodec.fitsShareableCodeCapacity(this)

    companion object {
        /** Version 2 is the canonical current schema; version 1 is the legacy block-only shape. */
        const val CURRENT_SCHEMA_VERSION = 2
        const val LEGACY_SCHEMA_VERSION = 1

        /**
         * Projects a local [FilterProfileDraft] (Android's `Filter`) plus its custom blocklist
         * sources into the portable v2 payload. The profile's stored emoji is deliberately not
         * shared: v2 wire carries no identity, and the recipient derives its own.
         */
        fun fromProfile(
            profile: FilterProfileDraft,
            customBlocklists: List<ShareableCustomBlocklistSource> = profile.customBlocklists.map { source ->
                ShareableCustomBlocklistSource(
                    source.id, source.displayName, source.sourceUrl,
                    ShareableBlocklistParseFormat.fromWire(source.parseFormat.name.lowercase(java.util.Locale.ROOT))
                        ?: ShareableBlocklistParseFormat.AUTO,
                )
            },
        ): ShareableFilterConfiguration = ShareableFilterConfiguration(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            enabledBlocklistIDs = profile.enabledSourceIds,
            blockedDomains = profile.manualRules.blockedDomains,
            customBlocklists = customBlocklists,
            allowedDomains = profile.manualRules.allowedDomains,
        )

        /** The legacy v1 shape: blocklists + blocked domains + optional emoji, no allowed set. */
        fun legacy(
            emoji: String? = null,
            enabledBlocklistIDs: Set<String> = emptySet(),
            blockedDomains: Set<String> = emptySet(),
            customBlocklists: List<ShareableCustomBlocklistSource> = emptyList(),
        ): ShareableFilterConfiguration = ShareableFilterConfiguration(
            schemaVersion = LEGACY_SCHEMA_VERSION,
            emoji = emoji,
            enabledBlocklistIDs = enabledBlocklistIDs,
            blockedDomains = blockedDomains,
            customBlocklists = customBlocklists,
            allowedDomains = null,
        )
    }
}
