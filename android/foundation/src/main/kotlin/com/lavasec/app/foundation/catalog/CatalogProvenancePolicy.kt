package com.lavasec.app.foundation.catalog

/**
 * Provenance and license rules for default-enabled sources.
 *
 * The `default_enabled` flag is authored by whoever serves the catalog, so on its own it is not a
 * trusted input. iOS relies on a curated catalog and generation-time review; Android treats every
 * non-bundled catalog as untrusted and refuses to let a new or under-specified source turn itself
 * on by default:
 *
 *  - a source id that is not already approved (present in the bundled trust anchor) is never
 *    enabled by default;
 *  - a source must declare a pinned SHA-256 (a direct `source_hash` or an accepted hash) or it
 *    cannot be default-enabled;
 *  - the license and attribution provenance must both be present;
 *  - the redistribution mode must be one this client understands;
 *  - the category must be part of the canonical taxonomy (the structural guardrail tier is never
 *    user-selectable).
 *
 * The rules are applied to the whole catalog at selection time ([CatalogSelectionPolicy]) and are
 * idempotent, so a last-known-good catalog is normalized the same way as a fresh candidate.
 */
object CatalogProvenancePolicy {
    /** Redistribution modes the client can safely serve. Unknown modes fail closed. */
    val SUPPORTED_REDISTRIBUTION_MODES: Set<String> = setOf(CatalogSource.REDISTRIBUTION_SOURCE_URL_ONLY)

    /** Canonical taxonomy (mirrors `BlocklistCategory` plus the structural guardrail marker). */
    val KNOWN_CATEGORIES: Set<String> = setOf(
        "security",
        "multi_purpose",
        "ads_tracking",
        "social",
        "nsfw",
        "gambling",
        "piracy",
        CatalogSource.GUARDRAIL_CATEGORY,
    )

    /** The trust anchor's approved ids: every selectable source and guardrail the app ships. */
    fun approvedSourceIds(manifest: RemoteCatalogManifest): Set<String> =
        manifest.allSources.mapTo(linkedSetOf()) { it.id }

    fun isDefaultEnabled(
        source: CatalogSource,
        provenance: CatalogProvenance,
        approvedSourceIds: Set<String>,
    ): Boolean {
        if (!source.defaultEnabled || source.isGuardrail) return false
        if (source.id !in approvedSourceIds) return false
        if (provenance == CatalogProvenance.BUNDLED) return true
        return hasPinnedHash(source) &&
            source.licenseName.isNotBlank() &&
            source.attribution.isNotBlank() &&
            source.redistributionMode in SUPPORTED_REDISTRIBUTION_MODES &&
            source.category in KNOWN_CATEGORIES
    }

    /** Returns a copy whose only change is clearing ineligible `defaultEnabled` flags. */
    fun sanitized(
        manifest: RemoteCatalogManifest,
        provenance: CatalogProvenance,
        approvedSourceIds: Set<String>,
    ): RemoteCatalogManifest {
        val sanitizedSources = manifest.sources.map { source ->
            if (source.defaultEnabled && !isDefaultEnabled(source, provenance, approvedSourceIds)) {
                source.copy(defaultEnabled = false)
            } else {
                source
            }
        }
        return manifest.copy(sources = sanitizedSources)
    }

    private fun hasPinnedHash(source: CatalogSource): Boolean =
        isCanonicalSha256(source.sourceHash) ||
            source.acceptedSourceHashes.any { isCanonicalSha256(it.sha256) }
}
