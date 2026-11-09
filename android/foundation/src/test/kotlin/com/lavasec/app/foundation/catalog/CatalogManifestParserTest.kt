package com.lavasec.app.foundation.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bounded remote-manifest parser against hostile, truncated and malformed input. The
 * vectors mirror the iOS decode boundary (`BlocklistCatalog.init(from:)` +
 * `validateDecodedMetadata`) plus the Android hardening (unknown keys, integer-only numbers,
 * public HTTPS source URLs).
 */
class CatalogManifestParserTest {
    private fun validManifest(
        sources: List<String> = listOf(CatalogTestFixtures.sourceJson(id = "approved-on")),
        guardrails: List<String> = emptyList(),
    ): String = CatalogTestFixtures.manifestJson(sources = sources, guardrails = guardrails)

    @Test
    fun `parses a well formed manifest and stamps guardrails structurally`() {
        val json = validManifest(
            sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = true)),
            guardrails = listOf(
                CatalogTestFixtures.sourceJson(id = "guardrail-1", category = "security"),
            ),
        )

        val manifest = CatalogTestFixtures.parseAccepted(json)

        assertEquals(2, manifest.schemaVersion)
        assertEquals("catalog-1", manifest.catalogVersion)
        assertEquals(2, manifest.allSources.size)
        assertEquals(1, manifest.sources.size)
        assertEquals(1, manifest.guardrails.size)
        // Guardrail stamping comes from array membership, not the server-supplied category.
        assertEquals(CatalogSource.GUARDRAIL_CATEGORY, manifest.guardrails.single().category)
        assertTrue(manifest.sources.single().defaultEnabled)
    }

    @Test
    fun `parses iso timestamps with and without fractional seconds`() {
        val withFraction = CatalogTestFixtures.manifestJson(
            generatedAt = "2026-09-01T12:34:56.789Z",
            sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on")),
        )
        val withoutFraction = CatalogTestFixtures.manifestJson(
            generatedAt = "2026-09-01T12:34:56Z",
            sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on")),
        )

        assertEquals(
            CatalogTestFixtures.parseAccepted(withFraction).generatedAtEpochSeconds,
            CatalogTestFixtures.parseAccepted(withoutFraction).generatedAtEpochSeconds,
        )
    }

    @Test
    fun `rejects a document over the byte cap before decoding`() {
        val oversized = ByteArray(CatalogLimits.MAX_DOCUMENT_BYTES + 1)

        assertEquals(
            CatalogParseFailure.DOCUMENT_TOO_LARGE,
            CatalogManifestParser.parse(oversized).failureOrNull(),
        )
    }

    @Test
    fun `rejects truncated and invalid utf8 documents`() {
        val valid = validManifest()
        val truncated = valid.substring(0, valid.length / 2)

        assertEquals(
            CatalogParseFailure.MALFORMED_JSON,
            CatalogManifestParser.parse(truncated.encodeToByteArray()).failureOrNull(),
        )
        assertEquals(
            CatalogParseFailure.MALFORMED_JSON,
            CatalogManifestParser.parse(byteArrayOf(0x7B, 0xFF.toByte(), 0x7D)).failureOrNull(),
        )
    }

    @Test
    fun `rejects trailing garbage and non integer numbers`() {
        val trailing = validManifest() + " "
        val fractional = CatalogTestFixtures.manifestJson(
            sources = listOf(
                CatalogTestFixtures.sourceJson(id = "approved-on").replace("\"entry_count\":10", "\"entry_count\":1.5"),
            ),
        )

        assertEquals(
            CatalogParseFailure.MALFORMED_JSON,
            CatalogManifestParser.parse((validManifest() + "{}").encodeToByteArray()).failureOrNull(),
        )
        assertEquals(
            CatalogParseFailure.MALFORMED_JSON,
            CatalogManifestParser.parse(fractional.encodeToByteArray()).failureOrNull(),
        )
        // Sanity: the trailing-space variant is still valid.
        assertTrue(CatalogManifestParser.parse(trailing.encodeToByteArray()) is CatalogParseResult.Accepted)
    }

    @Test
    fun `rejects duplicate object keys and hostile nesting`() {
        val duplicateKey = validManifest().replaceFirst("\"catalog_version\":\"catalog-1\"", "\"catalog_version\":\"a\",\"catalog_version\":\"b\"")
        val deep = "[".repeat(60) + "]".repeat(60)
        val hostile = CatalogTestFixtures.obj(
            "schema_version" to "2",
            "catalog_version" to CatalogTestFixtures.str("catalog-1"),
            "generated_at" to deep,
            "sources" to "[]",
            "guardrails" to "[]",
        )

        assertEquals(
            CatalogParseFailure.DUPLICATE_FIELD,
            CatalogManifestParser.parse(duplicateKey.encodeToByteArray()).failureOrNull(),
        )
        assertEquals(
            CatalogParseFailure.MALFORMED_JSON,
            CatalogManifestParser.parse(hostile.encodeToByteArray()).failureOrNull(),
        )
    }

    @Test
    fun `rejects duplicate source ids case insensitively and across tiers`() {
        val duplicates = validManifest(
            sources = listOf(
                CatalogTestFixtures.sourceJson(id = "Shared-Id"),
                CatalogTestFixtures.sourceJson(id = "shared-id"),
            ),
        )
        val acrossTiers = validManifest(
            sources = listOf(CatalogTestFixtures.sourceJson(id = "shared-id")),
            guardrails = listOf(CatalogTestFixtures.sourceJson(id = "SHARED-ID", category = "guardrail")),
        )

        assertEquals(
            CatalogParseFailure.DUPLICATE_SOURCE_ID,
            CatalogManifestParser.parse(duplicates.encodeToByteArray()).failureOrNull(),
        )
        assertEquals(
            CatalogParseFailure.DUPLICATE_SOURCE_ID,
            CatalogManifestParser.parse(acrossTiers.encodeToByteArray()).failureOrNull(),
        )
    }

    @Test
    fun `rejects unknown keys at the manifest and source level`() {
        val unknownTop = CatalogManifestParser.parse(
            validManifest().replaceFirst("{", "{\"evil\":true,").encodeToByteArray(),
        ).failureOrNull()
        val unknownSource = CatalogTestFixtures.parseFailure(
            CatalogTestFixtures.manifestJson(
                sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", extra = listOf("evil" to "true"))),
            ),
        )

        assertEquals(CatalogParseFailure.UNKNOWN_KEY, unknownTop)
        assertEquals(CatalogParseFailure.UNKNOWN_KEY, unknownSource)
    }

    @Test
    fun `rejects missing required fields`() {
        val missingSchema = validManifest().replaceFirst("\"schema_version\":2,", "")
        val missingId = CatalogTestFixtures.manifestJson(
            sources = listOf(
                CatalogTestFixtures.sourceJson(id = "approved-on").replaceFirst("\"id\":\"approved-on\",", ""),
            ),
        )
        val missingGuardrails = validManifest().replaceFirst(",\"guardrails\":[]", "")

        assertEquals(
            CatalogParseFailure.MISSING_REQUIRED_FIELD,
            CatalogManifestParser.parse(missingSchema.encodeToByteArray()).failureOrNull(),
        )
        assertEquals(
            CatalogParseFailure.MISSING_REQUIRED_FIELD,
            CatalogManifestParser.parse(missingId.encodeToByteArray()).failureOrNull(),
        )
        assertEquals(
            CatalogParseFailure.MISSING_REQUIRED_FIELD,
            CatalogManifestParser.parse(missingGuardrails.encodeToByteArray()).failureOrNull(),
        )
    }

    @Test
    fun `rejects unsupported schema versions and parse formats`() {
        val wrongSchema = CatalogTestFixtures.manifestJson(
            schemaVersion = 3,
            sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on")),
        )
        val wrongFormat = CatalogTestFixtures.manifestJson(
            sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", parseFormat = "domain_list")),
        )

        assertEquals(
            CatalogParseFailure.UNSUPPORTED_SCHEMA_VERSION,
            CatalogManifestParser.parse(wrongSchema.encodeToByteArray()).failureOrNull(),
        )
        assertEquals(
            CatalogParseFailure.UNSUPPORTED_PARSE_FORMAT,
            CatalogManifestParser.parse(wrongFormat.encodeToByteArray()).failureOrNull(),
        )
    }

    @Test
    fun `rejects non public or non https source urls`() {
        val badUrls = listOf(
            "http://example.org/list.txt",
            "https://127.0.0.1/list.txt",
            "https://localhost/list.txt",
            "https://10.0.0.5/list.txt",
            "https://[::1]/list.txt",
            "https://user:pass@example.org/list.txt",
        )

        for (url in badUrls) {
            val json = CatalogTestFixtures.manifestJson(
                sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", sourceUrl = url)),
            )
            assertEquals(
                "expected rejection for $url",
                CatalogParseFailure.INVALID_SOURCE_URL,
                CatalogManifestParser.parse(json.encodeToByteArray()).failureOrNull(),
            )
        }
    }

    @Test
    fun `rejects out of range or malformed metadata`() {
        assertFailure(CatalogParseFailure.INVALID_FIELD_VALUE) {
            sourceWith(entryCount = -1)
        }
        assertFailure(CatalogParseFailure.INVALID_FIELD_VALUE) {
            sourceWith(entryCount = CatalogLimits.MAX_SOURCE_ENTRY_COUNT + 1L)
        }
        assertFailure(CatalogParseFailure.INVALID_FIELD_VALUE) {
            sourceWith(id = "a".repeat(CatalogLimits.MAX_SOURCE_ID_BYTES + 1))
        }
        assertFailure(CatalogParseFailure.INVALID_FIELD_VALUE) {
            sourceWith(versionId = "v".repeat(CatalogLimits.MAX_VERSION_ID_BYTES + 1))
        }
        assertFailure(CatalogParseFailure.INVALID_FIELD_VALUE) {
            CatalogTestFixtures.manifestJson(
                sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", sourceHash = "not-a-sha")),
            )
        }
        assertFailure(CatalogParseFailure.INVALID_FIELD_VALUE) {
            sourceWith(parseFormat = "auto", entryCount = 10, byteSize = -5)
        }
    }

    @Test
    fun `rejects an invalid accepted source hash`() {
        val json = CatalogTestFixtures.manifestJson(
            sources = listOf(
                CatalogTestFixtures.sourceJson(
                    id = "approved-on",
                    acceptedSourceHashes = CatalogTestFixtures.arr(
                        CatalogTestFixtures.obj(
                            "sha256" to CatalogTestFixtures.str("short"),
                            "status" to CatalogTestFixtures.str("accepted"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            CatalogParseFailure.INVALID_FIELD_VALUE,
            CatalogManifestParser.parse(json.encodeToByteArray()).failureOrNull(),
        )
    }

    @Test
    fun `rejects more than the fixed source cap`() {
        val sources = List(CatalogLimits.MAX_SOURCES + 1) { index ->
            CatalogTestFixtures.sourceJson(id = "source-$index")
        }
        val json = CatalogTestFixtures.manifestJson(sources = sources)

        assertEquals(
            CatalogParseFailure.TOO_MANY_SOURCES,
            CatalogManifestParser.parse(json.encodeToByteArray()).failureOrNull(),
        )
    }

    @Test
    fun `unknown categories still parse but are ineligible for default enable`() {
        // Categories are free-form on the wire in iOS; eligibility is enforced by the provenance
        // policy rather than the parser, so an unknown category must still decode.
        val manifest = CatalogTestFixtures.parseAccepted(
            CatalogTestFixtures.manifestJson(
                sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", category = "experimental")),
            ),
        )
        assertEquals("experimental", manifest.sources.single().category)
        assertFalse(
            CatalogProvenancePolicy.isDefaultEnabled(
                manifest.sources.single(),
                CatalogProvenance.REMOTE,
                setOf("approved-on"),
            ),
        )
    }

    @Test
    fun `envelope verifies checksum and revision claims`() {
        val bytes = CatalogTestFixtures.bytes(validManifest())
        val checksum = sha256Hex(bytes)

        assertEquals(
            CatalogParseFailure.CHECKSUM_MISMATCH,
            CatalogManifestParser.parseEnvelope(
                CatalogFetchEnvelope(bytes, claimedSha256 = CatalogTestFixtures.SHA_B),
            ).failureOrNull(),
        )
        assertEquals(
            CatalogParseFailure.REVISION_MISMATCH,
            CatalogManifestParser.parseEnvelope(
                CatalogFetchEnvelope(bytes, claimedRevision = "other-revision", claimedSha256 = checksum),
            ).failureOrNull(),
        )
        assertTrue(
            CatalogManifestParser.parseEnvelope(
                CatalogFetchEnvelope(bytes, claimedRevision = "catalog-1", claimedSha256 = checksum),
            ) is CatalogParseResult.Accepted,
        )
    }

    private fun sourceWith(
        id: String = "approved-on",
        versionId: String = "approved-on-v1",
        parseFormat: String = "hosts",
        entryCount: Long = 10,
        byteSize: Long = 100,
    ): String = CatalogTestFixtures.manifestJson(
        sources = listOf(
            CatalogTestFixtures.sourceJson(
                id = id,
                versionId = versionId,
                parseFormat = parseFormat,
                entryCount = entryCount,
                byteSize = byteSize,
            ),
        ),
    )

    private fun assertFailure(
        expected: CatalogParseFailure,
        build: () -> String,
    ) {
        assertEquals(expected, CatalogManifestParser.parse(build().encodeToByteArray()).failureOrNull())
    }

    private fun CatalogParseResult.failureOrNull(): CatalogParseFailure? =
        (this as? CatalogParseResult.Rejected)?.failure
}
