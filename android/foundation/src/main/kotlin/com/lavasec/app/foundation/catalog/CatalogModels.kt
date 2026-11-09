package com.lavasec.app.foundation.catalog

import com.lavasec.app.foundation.filter.BlocklistFormat

/**
 * Remote blocklist catalog source/manifest models.
 *
 * Ported from the pinned iOS `Sources/LavaSecKit/CatalogSourceModels.swift` and
 * `Sources/LavaSecKit/BlocklistModels.swift` (c4658d973bae05dd3b7aa84c014cb2f76ff61a0b). The
 * models are network-free value types: this slice validates and selects catalogs, it never
 * fetches them. A later slice wires the transport and cache; keeping the wire shape and the
 * acceptance rules here lets both be tested as pure functions.
 *
 * Android stays fail-closed wherever iOS is *permissive by construction*: every remote field is
 * decoded through an explicitly bounded parser ([CatalogManifestParser]) rather than a general
 * purpose JSON decoder, so oversized documents, unknown keys and malformed numbers are rejected
 * before they can become state.
 */
enum class CatalogParseFormat(val wire: String) {
    AUTO("auto"),
    PLAIN_DOMAINS("plain_domains"),
    HOSTS("hosts"),
    ADBLOCK("adblock"),
    DNSMASQ("dnsmasq"),
    ;

    fun toBlocklistFormat(): BlocklistFormat = when (this) {
        AUTO -> BlocklistFormat.AUTO
        PLAIN_DOMAINS -> BlocklistFormat.PLAIN_DOMAINS
        HOSTS -> BlocklistFormat.HOSTS
        ADBLOCK -> BlocklistFormat.ADBLOCK
        DNSMASQ -> BlocklistFormat.DNSMASQ
    }

    companion object {
        /** Fails closed for every unknown/unsupported wire value. */
        fun fromWire(value: String): CatalogParseFormat? = entries.firstOrNull { it.wire == value }
    }
}

/** Where an effective catalog came from. Provenance is the default-enable trust boundary. */
enum class CatalogProvenance {
    /** The catalog compiled into the app; the trust anchor. */
    BUNDLED,

    /** A previously validated remote catalog persisted on device. */
    LAST_KNOWN_GOOD,

    /** A freshly fetched, not-yet-installed candidate. */
    REMOTE,
}

/**
 * One accepted payload hash for a source, ported from `CatalogAcceptedSourceHash`.
 *
 * [accepts] mirrors the iOS `accepts(_:now:)`: an entry authorizes a payload only while its
 * [status] is `accepted`, the checksum matches, and it has not expired.
 */
data class CatalogAcceptedSourceHash(
    val sha256: String,
    val byteSize: Long? = null,
    val entryCount: Int? = null,
    val reviewedAtEpochSeconds: Long? = null,
    val expiresAtEpochSeconds: Long? = null,
    val status: String = STATUS_ACCEPTED,
) {
    fun accepts(checksumSha256: String, nowEpochSeconds: Long): Boolean {
        if (status != STATUS_ACCEPTED) return false
        if (!sha256.equals(checksumSha256, ignoreCase = true)) return false
        val expiresAt = expiresAtEpochSeconds ?: return true
        return expiresAt >= nowEpochSeconds
    }

    companion object {
        const val STATUS_ACCEPTED = "accepted"
    }
}

/**
 * One catalog blocklist source, mirroring `CatalogBlocklistSource`.
 *
 * Field names follow the iOS names; the JSON wire names live in the parser. `versionId` is the
 * canonical revision, `sourceHash` is the declared SHA-256 and `byteSize` the declared payload
 * size. [defaultEnabled] is advisory: a remote value is only ever honored through
 * [CatalogProvenancePolicy], never trusted directly.
 */
data class CatalogSource(
    val id: String,
    val name: String,
    val category: String,
    val riskLevel: String,
    val defaultEnabled: Boolean,
    val licenseName: String,
    val attribution: String,
    val projectUrl: String,
    val sourceUrl: String,
    val versionId: String,
    val entryCount: Int,
    val byteSize: Long,
    val sourceHash: String,
    val acceptedSourceHashes: List<CatalogAcceptedSourceHash>,
    val normalizedHash: String,
    val publishedAtEpochSeconds: Long,
    val redistributionMode: String,
    val parseFormat: CatalogParseFormat,
    val licenseTextUrl: String?,
    val noticeUrl: String?,
) {
    val isGuardrail: Boolean get() = category == GUARDRAIL_CATEGORY

    /**
     * Community lists fetched directly over TLS accept upstream rotation; the threat guardrail is
     * Lava-curated and hash-pinned. Ported verbatim from iOS `acceptsDirectUpstreamRotation`.
     */
    val acceptsDirectUpstreamRotation: Boolean
        get() = redistributionMode == REDISTRIBUTION_SOURCE_URL_ONLY && category != GUARDRAIL_CATEGORY

    fun acceptsDownloadedHash(checksumSha256: String, nowEpochSeconds: Long): Boolean =
        acceptedSourceHashes.any { it.accepts(checksumSha256, nowEpochSeconds) }

    fun activeAcceptedHashValues(nowEpochSeconds: Long): List<String> =
        acceptedSourceHashes.mapNotNull { accepted ->
            if (accepted.accepts(accepted.sha256, nowEpochSeconds)) accepted.sha256 else null
        }

    /**
     * Structurally stamps this source into the guardrail tier. The catalog's `guardrails[]` array
     * is the source of truth, so a tampered `category` string cannot relax a guardrail into
     * rotation-accepting community behavior.
     */
    fun markedAsGuardrail(): CatalogSource =
        if (category == GUARDRAIL_CATEGORY) this else copy(category = GUARDRAIL_CATEGORY)

    /**
     * Returns this source resolved against downloaded payload bytes, mirroring iOS
     * `resolvingDownloadedPayload`. Rotation-accepting community sources prepend the observed
     * hash to their accepted set; the guardrail keeps its pinned set.
     */
    fun resolvingDownloadedPayload(
        checksumSha256: String,
        byteSize: Long,
        entryCount: Int,
    ): CatalogSource {
        val resolvedVersionId =
            if (sourceHash.isNotEmpty() && sourceHash.equals(checksumSha256, ignoreCase = true)) {
                versionId
            } else {
                "$id-direct-${checksumSha256.take(12)}"
            }
        val resolvedAccepted = resolvingAcceptedSourceHashes(checksumSha256, byteSize, entryCount)
        return copy(
            versionId = resolvedVersionId,
            entryCount = entryCount,
            byteSize = byteSize,
            sourceHash = checksumSha256,
            acceptedSourceHashes = resolvedAccepted,
            normalizedHash = checksumSha256,
        )
    }

    private fun resolvingAcceptedSourceHashes(
        checksumSha256: String,
        byteSize: Long,
        entryCount: Int,
    ): List<CatalogAcceptedSourceHash> {
        if (!acceptsDirectUpstreamRotation ||
            acceptedSourceHashes.any { it.sha256.equals(checksumSha256, ignoreCase = true) }
        ) {
            return acceptedSourceHashes
        }
        return listOf(
            CatalogAcceptedSourceHash(
                sha256 = checksumSha256,
                byteSize = byteSize,
                entryCount = entryCount,
                reviewedAtEpochSeconds = null,
            ),
        ) + acceptedSourceHashes
    }

    companion object {
        const val GUARDRAIL_CATEGORY = "guardrail"
        const val REDISTRIBUTION_SOURCE_URL_ONLY = "source_url_only"
    }
}

/**
 * Versioned catalog metadata, mirroring `BlocklistCatalog`. `guardrails` is the structural
 * safety-critical tier and is kept separate from the user-selectable [sources].
 */
data class RemoteCatalogManifest(
    val schemaVersion: Int,
    val catalogVersion: String,
    val generatedAtEpochSeconds: Long,
    val sources: List<CatalogSource>,
    val guardrails: List<CatalogSource>,
) {
    val allSources: List<CatalogSource> get() = sources + guardrails

    /** Upper bound on guardrail rules a compile will process; mirrors iOS `guardrailEntryCount`. */
    val guardrailEntryCount: Int get() = guardrails.sumOf { it.entryCount }

    companion object {
        /** The only schema this client understands; anything else fails closed. */
        const val SUPPORTED_SCHEMA_VERSION = 2
    }
}

/** A validated catalog together with where it came from and its exact-bytes identity. */
data class CatalogArtifact(
    val manifest: RemoteCatalogManifest,
    val provenance: CatalogProvenance,
    val contentSha256: String,
) {
    init {
        require(contentSha256.length == 64 && contentSha256.all { it.isCanonicalHexDigit() }) {
            "catalog content SHA-256 must be canonical lowercase hexadecimal"
        }
    }

    val catalogVersion: String get() = manifest.catalogVersion
    val generatedAtEpochSeconds: Long get() = manifest.generatedAtEpochSeconds

    /** Selectable source ids this artifact would enable by default under [CatalogProvenancePolicy]. */
    fun defaultEnabledSourceIds(approvedSourceIds: Set<String>): Set<String> =
        manifest.sources
            .filter { CatalogProvenancePolicy.isDefaultEnabled(it, provenance, approvedSourceIds) }
            .mapTo(linkedSetOf()) { it.id }
}

/**
 * A fetched catalog document with its optional out-of-band identity claims. The claims let the
 * selection policy reject a document whose bytes do not match the revision/hash the fetch was
 * supposed to produce, before any of it is trusted.
 */
class CatalogFetchEnvelope(
    val bytes: ByteArray,
    val claimedRevision: String? = null,
    val claimedSha256: String? = null,
)

private fun Char.isCanonicalHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'
