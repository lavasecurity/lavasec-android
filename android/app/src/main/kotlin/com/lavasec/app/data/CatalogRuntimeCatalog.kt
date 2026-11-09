package com.lavasec.app.data

import android.content.Context
import com.lavasec.app.foundation.catalog.CatalogArtifact
import com.lavasec.app.foundation.catalog.CatalogArtifactLoader
import com.lavasec.app.foundation.catalog.CatalogParseFormat
import com.lavasec.app.foundation.catalog.CatalogProvenance
import com.lavasec.app.foundation.catalog.CatalogProvenancePolicy
import com.lavasec.app.foundation.filter.FilterSourceCategory
import java.io.File

/**
 * Projects a validated remote [CatalogArtifact] into the exact runtime catalog shape the filter
 * authority, credential configuration repository and onboarding seeder already consume
 * ([BundledBlocklistCatalog]).
 *
 * This is the one bridge between the network-free catalog core (which validates and selects raw
 * manifests) and the compile authority (which is built from pinned metadata). The projection is
 * destructive on purpose: every runtime field is re-derived from the already-sanitized artifact,
 * never trusted verbatim:
 *
 *  - only selectable `sources` are installed; the structural `guardrails` tier is not selectable
 *    on Android and is intentionally dropped;
 *  - a source whose category is outside the canonical taxonomy is dropped, so an unknown category
 *    can never become a selectable runtime source;
 *  - `defaultEnabled` is whatever the selection/sanitize policy already decided
 *    ([CatalogProvenancePolicy]) — this projection never re-enables anything.
 *
 * The bundled anchor round-trips to the exact supplied [BundledBlocklistCatalog]: its revision is
 * still the pinned blob and its digest the pinned asset digest, so a process that resolves the
 * bundled catalog does not accidentally re-fingerprint the persisted identity.
 */
internal object CatalogRuntimeCatalog {
    fun fromArtifact(artifact: CatalogArtifact, bundled: BundledBlocklistCatalog): BundledBlocklistCatalog {
        // The bundle is the trust anchor with a pinned revision/digest; return it verbatim so the
        // persisted configuration identity (derived from those pins) never moves.
        if (artifact.provenance == CatalogProvenance.BUNDLED) return bundled

        val sources = artifact.manifest.sources.mapNotNull { source ->
            if (source.id in RETIRED_CATALOG_SOURCE_IDS) return@mapNotNull null
            val category = runtimeCategory(source.category) ?: return@mapNotNull null
            val format = runtimeFormat(source.parseFormat) ?: return@mapNotNull null
            CatalogSourceMetadata(
                id = source.id,
                name = source.name,
                category = category,
                format = format,
                blocklistFormat = source.parseFormat.toBlocklistFormat(),
                sourceUrl = source.sourceUrl,
                defaultEnabled = source.defaultEnabled,
                licenseName = source.licenseName,
                entryCount = source.entryCount,
            )
        }
        require(sources.map(CatalogSourceMetadata::id).distinct().size == sources.size) {
            "remote catalog carries duplicate selectable source ids"
        }
        return BundledBlocklistCatalog(
            schemaVersion = artifact.manifest.schemaVersion,
            categories = sources.map { it.category.name.lowercase() }.distinct(),
            sources = sources,
            revision = artifact.catalogVersion,
            sha256 = artifact.contentSha256,
            assetText = "",
            categoryLabels = bundled.categoryLabels,
        )
    }

    /** The exact source ids a runtime catalog exposes as selectable. */
    fun selectableSourceIds(catalog: BundledBlocklistCatalog): Set<String> =
        catalog.sources.mapTo(linkedSetOf(), CatalogSourceMetadata::id)

    /**
     * Resolves the catalog a fresh credential graph must run with: the durable last-known-good
     * installed by a previous successful refresh, or the pinned bundle. A corrupt or foreign cache
     * decodes to `null` and the bundle stays effective, exactly like the sync owner.
     */
    fun resolveInstalled(context: Context, bundled: BundledBlocklistCatalog): BundledBlocklistCatalog {
        // Separate package storage is an extra defense; FOSS never consumes a dynamic catalog cache.
        if (!com.lavasec.app.edition.AppEdition.current.dynamicCatalog) return bundled
        val cached = CatalogManifestCache(File(context.filesDir, CACHE_DIRECTORY)).read() ?: return bundled
        val approved = CatalogProvenancePolicy.approvedSourceIds(CatalogBundledArtifact.from(bundled).manifest)
        val artifact = CatalogArtifactLoader.load(
            bytes = cached.bytes,
            provenance = CatalogProvenance.LAST_KNOWN_GOOD,
            approvedSourceIds = approved,
        ) ?: return bundled
        return fromArtifact(artifact, bundled)
    }

    /** Credential-protected catalog cache directory under `filesDir`, shared with the sync owner. */
    const val CACHE_DIRECTORY = "catalog"

    private fun runtimeCategory(raw: String): FilterSourceCategory? = when (raw) {
        "security" -> FilterSourceCategory.SECURITY
        "multi_purpose" -> FilterSourceCategory.MULTI_PURPOSE
        "ads_tracking" -> FilterSourceCategory.ADS_TRACKING
        "social" -> FilterSourceCategory.SOCIAL
        "nsfw" -> FilterSourceCategory.NSFW
        "gambling" -> FilterSourceCategory.GAMBLING
        "piracy" -> FilterSourceCategory.PIRACY
        else -> null
    }

    private fun runtimeFormat(format: CatalogParseFormat): CatalogSourceFormat? = when (format) {
        CatalogParseFormat.AUTO -> CatalogSourceFormat.AUTO
        CatalogParseFormat.PLAIN_DOMAINS -> CatalogSourceFormat.PLAIN_DOMAINS
        CatalogParseFormat.HOSTS -> CatalogSourceFormat.HOSTS
        CatalogParseFormat.ADBLOCK -> CatalogSourceFormat.ADBLOCK
        CatalogParseFormat.DNSMASQ -> CatalogSourceFormat.DNSMASQ
    }
}

/**
 * Reconciles one saved filter library against a catalog's selectable source ids.
 *
 * A refresh may add, drop or rename curated sources. Each profile retains enabled ids from the
 * new catalog and its own custom definitions, so catalog replacement cannot disable an accepted
 * custom list. Vanished curated sources and dangling custom references are removed. Neither new
 * default-enabled catalog sources nor disabled custom lists are enabled without the user's choice.
 */
internal fun reconcileLibraryForCatalog(
    library: com.lavasec.app.foundation.filter.FilterLibraryDraft,
    selectableSourceIds: Set<String>,
): com.lavasec.app.foundation.filter.FilterLibraryDraft =
    library.withProfiles(
        library.profiles.map { profile ->
            val validSourceIds = selectableSourceIds + profile.customBlocklists.map { it.id }
            profile.withEnabledSourceIds(profile.enabledSourceIds intersect validSourceIds)
        },
    )
