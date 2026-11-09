package com.lavasec.app.data

import com.lavasec.app.foundation.catalog.CatalogArtifact
import com.lavasec.app.foundation.catalog.CatalogParseFormat
import com.lavasec.app.foundation.catalog.CatalogProvenance
import com.lavasec.app.foundation.catalog.CatalogSource
import com.lavasec.app.foundation.catalog.RemoteCatalogManifest
import com.lavasec.app.foundation.filter.FilterSourceCategory

/**
 * Builds the trust anchor the remote-catalog selection policy runs against from the pinned
 * catalog bundled with the application.
 *
 * iOS derives `BlocklistCatalog.builtInSourceURLCatalog()` from `DefaultCatalog.curatedSources`
 * (`CatalogBlocklistSource(defaultSource:)`): schema 2, version `built-in-source-url-catalog-v1`,
 * `generated_at` at the epoch, every source carrying an empty hash/size and the `source_url_only`
 * redistribution mode. Android's bundled asset is the same data in its older on-disk shape, so it
 * is mapped into the same model here rather than mutating the shipped asset or the parser (which
 * only accepts the schema-2 remote wire shape).
 *
 * This is a trust anchor, not fetched content: it declares no accepted hashes and never
 * default-enables an id it does not ship. [com.lavasec.app.foundation.catalog.CatalogProvenancePolicy]
 * approves every id it carries and refuses to let any remote-only id turn itself on by default.
 */
internal object CatalogBundledArtifact {
    const val BUILT_IN_CATALOG_VERSION = "built-in-source-url-catalog-v1"

    fun from(bundled: BundledBlocklistCatalog): CatalogArtifact {
        val sources = bundled.sources.map { source ->
            CatalogSource(
                id = source.id,
                name = source.name,
                category = categoryWire(source.category),
                riskLevel = "normal",
                defaultEnabled = source.defaultEnabled,
                // License/attribution are only consulted for non-bundled provenance, so the
                // anchor carries the names iOS does (source name) and an empty license exactly
                // like `CatalogBlocklistSource(defaultSource:)`.
                licenseName = "",
                attribution = source.name,
                projectUrl = source.sourceUrl,
                sourceUrl = source.sourceUrl,
                versionId = "${source.id}-source-url",
                entryCount = 0,
                byteSize = 0L,
                sourceHash = "",
                acceptedSourceHashes = emptyList(),
                normalizedHash = "",
                publishedAtEpochSeconds = 0L,
                redistributionMode = CatalogSource.REDISTRIBUTION_SOURCE_URL_ONLY,
                parseFormat = parseFormat(source.format),
                licenseTextUrl = null,
                noticeUrl = null,
            )
        }
        return CatalogArtifact(
            manifest = RemoteCatalogManifest(
                schemaVersion = RemoteCatalogManifest.SUPPORTED_SCHEMA_VERSION,
                catalogVersion = BUILT_IN_CATALOG_VERSION,
                generatedAtEpochSeconds = 0L,
                sources = sources,
                guardrails = emptyList(),
            ),
            provenance = CatalogProvenance.BUNDLED,
            contentSha256 = bundled.sha256,
        )
    }

    private fun parseFormat(format: CatalogSourceFormat): CatalogParseFormat = when (format) {
        CatalogSourceFormat.AUTO -> CatalogParseFormat.AUTO
        CatalogSourceFormat.HOSTS -> CatalogParseFormat.HOSTS
        CatalogSourceFormat.PLAIN_DOMAINS -> CatalogParseFormat.PLAIN_DOMAINS
        CatalogSourceFormat.ADBLOCK -> CatalogParseFormat.ADBLOCK
        CatalogSourceFormat.DNSMASQ -> CatalogParseFormat.DNSMASQ
    }

    private fun categoryWire(category: FilterSourceCategory): String = when (category) {
        FilterSourceCategory.SECURITY -> "security"
        FilterSourceCategory.MULTI_PURPOSE -> "multi_purpose"
        FilterSourceCategory.ADS_TRACKING -> "ads_tracking"
        FilterSourceCategory.SOCIAL -> "social"
        FilterSourceCategory.NSFW -> "nsfw"
        FilterSourceCategory.GAMBLING -> "gambling"
        FilterSourceCategory.PIRACY -> "piracy"
        FilterSourceCategory.CUSTOM -> "custom"
    }
}
