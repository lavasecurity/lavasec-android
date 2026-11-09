package com.lavasec.app.foundation.catalog

/** Small JSON builders so parser tests read as the schema they exercise. */
internal object CatalogTestFixtures {
    val SHA_A = "a".repeat(64)
    val SHA_B = "b".repeat(64)
    val SHA_C = "c".repeat(64)

    fun str(value: String): String = "\"$value\""

    fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "\"$key\":$value"
        }

    fun arr(vararg items: String): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",")

    fun nullableStr(value: String?): String = value?.let(::str) ?: "null"

    fun sourceJson(
        id: String,
        category: String = "security",
        defaultEnabled: Boolean = true,
        licenseName: String = "Unlicense",
        attribution: String = "Provider",
        redistributionMode: String = "source_url_only",
        parseFormat: String = "hosts",
        sourceHash: String = SHA_A,
        sourceUrl: String = "https://example.org/$id.txt",
        versionId: String = "$id-v1",
        entryCount: Long = 10,
        byteSize: Long = 100,
        acceptedSourceHashes: String = arr(
            obj(
                "sha256" to str(SHA_A),
                "byte_size" to "100",
                "entry_count" to "10",
                "status" to str("accepted"),
            ),
        ),
        extra: List<Pair<String, String>> = emptyList(),
    ): String {
        val pairs = mutableListOf(
            "id" to str(id),
            "name" to str("List $id"),
            "category" to str(category),
            "risk_level" to str("normal"),
            "default_enabled" to defaultEnabled.toString(),
            "license_name" to str(licenseName),
            "attribution" to str(attribution),
            "project_url" to str("https://example.org/$id"),
            "source_url" to str(sourceUrl),
            "version_id" to str(versionId),
            "entry_count" to entryCount.toString(),
            "byte_size" to byteSize.toString(),
            "source_hash" to str(sourceHash),
            "accepted_source_hashes" to acceptedSourceHashes,
            "normalized_hash" to str(sourceHash),
            "published_at" to str("2026-08-01T00:00:00Z"),
            "redistribution_mode" to str(redistributionMode),
            "parse_format" to str(parseFormat),
            "license_text_url" to "null",
            "notice_url" to "null",
        )
        return obj(*(pairs + extra).toTypedArray())
    }

    fun manifestJson(
        schemaVersion: Long = 2,
        catalogVersion: String = "catalog-1",
        generatedAt: String = "2026-09-01T00:00:00Z",
        sources: List<String>,
        guardrails: List<String> = emptyList(),
        extra: List<Pair<String, String>> = emptyList(),
    ): String {
        val pairs = mutableListOf(
            "schema_version" to schemaVersion.toString(),
            "catalog_version" to str(catalogVersion),
            "generated_at" to str(generatedAt),
            "sources" to arr(*sources.toTypedArray()),
            "guardrails" to arr(*guardrails.toTypedArray()),
        )
        return obj(*(pairs + extra).toTypedArray())
    }

    fun bytes(json: String): ByteArray = json.encodeToByteArray()

    fun parseAccepted(json: String): RemoteCatalogManifest =
        when (val result = CatalogManifestParser.parse(bytes(json))) {
            is CatalogParseResult.Accepted -> result.manifest
            is CatalogParseResult.Rejected -> error("expected a valid manifest, got ${result.failure}")
        }

    fun parseFailure(json: String): CatalogParseFailure =
        when (val result = CatalogManifestParser.parse(bytes(json))) {
            is CatalogParseResult.Accepted -> error("expected a rejection")
            is CatalogParseResult.Rejected -> result.failure
        }

    fun artifact(
        json: String,
        provenance: CatalogProvenance,
        approvedSourceIds: Set<String> = emptySet(),
    ): CatalogArtifact = requireNotNull(
        CatalogArtifactLoader.load(bytes(json), provenance, approvedSourceIds),
    ) { "fixture manifest must load" }

    /**
     * The trust anchor used by selection tests: a set of curated ids, one default-enabled.
     */
    fun bundledArtifact(): CatalogArtifact {
        val json = manifestJson(
            catalogVersion = "bundled-1",
            generatedAt = "2026-01-01T00:00:00Z",
            sources = listOf(
                sourceJson(id = "approved-on", defaultEnabled = true),
                sourceJson(id = "approved-off", defaultEnabled = false),
                sourceJson(id = "approved-license", defaultEnabled = false),
                sourceJson(id = "approved-mode", defaultEnabled = false),
                sourceJson(id = "approved-hash", defaultEnabled = false),
            ),
            guardrails = listOf(
                sourceJson(id = "guardrail-1", category = "guardrail", defaultEnabled = false),
            ),
        )
        return artifact(json, CatalogProvenance.BUNDLED)
    }

    fun approvedIds(bundled: CatalogArtifact): Set<String> =
        CatalogProvenancePolicy.approvedSourceIds(bundled.manifest)
}
