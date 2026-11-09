package com.lavasec.app.foundation.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure refresh/staleness selection policy: newest valid wins, corrupt or rollback
 * candidates fall back, a valid installed artifact is never discarded, and untrusted catalogs
 * cannot silently default-enable new sources.
 */
class CatalogSelectionPolicyTest {
    private val bundled = CatalogTestFixtures.bundledArtifact()
    private val approved = CatalogTestFixtures.approvedIds(bundled)
    private val nowAfterCandidate = epoch("2026-10-02T00:00:00Z")

    @Test
    fun `fresh install with no cache and no candidate uses the bundle`() {
        val selection = CatalogSelectionPolicy.select(bundled, null, null, nowAfterCandidate)

        assertEquals(CatalogSelectionOutcome.BUNDLED_ONLY, selection.outcome)
        assertEquals(bundled, selection.artifact)
        assertEquals(setOf("approved-on"), selection.artifact.defaultEnabledSourceIds(approved))
    }

    @Test
    fun `a newer valid candidate wins and is sanitized`() {
        val candidate = candidate(
            revision = "catalog-2",
            generatedAt = "2026-10-01T00:00:00Z",
            sources = listOf(
                CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = true),
                CatalogTestFixtures.sourceJson(id = "brand-new", defaultEnabled = true),
            ),
        )

        val selection = CatalogSelectionPolicy.select(bundled, null, candidate, nowAfterCandidate)

        assertEquals(CatalogSelectionOutcome.SELECTED_CANDIDATE, selection.outcome)
        assertNull(selection.rejection)
        assertEquals(CatalogProvenance.REMOTE, selection.artifact.provenance)
        assertEquals("catalog-2", selection.artifact.catalogVersion)
        // The unknown id is present but not silently enabled; the approved one still is.
        assertTrue(selection.artifact.manifest.sources.any { it.id == "brand-new" })
        assertEquals(setOf("approved-on"), selection.artifact.defaultEnabledSourceIds(approved))
    }

    @Test
    fun `a stale rollback candidate is refused and the installed catalog is kept`() {
        val installed = CatalogTestFixtures.artifact(
            CatalogTestFixtures.manifestJson(
                catalogVersion = "catalog-2",
                generatedAt = "2026-10-01T00:00:00Z",
                sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = true)),
            ),
            CatalogProvenance.LAST_KNOWN_GOOD,
            approved,
        )
        val rollback = candidate(
            revision = "catalog-1",
            generatedAt = "2026-09-01T00:00:00Z",
            sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = false)),
        )

        val selection = CatalogSelectionPolicy.select(bundled, installed, rollback, nowAfterCandidate)

        assertEquals(CatalogSelectionOutcome.KEPT_LAST_KNOWN_GOOD, selection.outcome)
        assertEquals(CatalogCandidateRejection.StaleRollback, selection.rejection)
        assertEquals(installed, selection.artifact)
    }

    @Test
    fun `a candidate older than the bundle is refused even without a cache`() {
        val candidate = candidate(
            revision = "catalog-old",
            generatedAt = "2025-12-01T00:00:00Z",
            sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = false)),
        )

        val selection = CatalogSelectionPolicy.select(bundled, null, candidate, nowAfterCandidate)

        assertEquals(CatalogSelectionOutcome.FELL_BACK_TO_BUNDLED, selection.outcome)
        assertEquals(CatalogCandidateRejection.StaleRollback, selection.rejection)
        assertEquals(bundled, selection.artifact)
    }

    @Test
    fun `a corrupt candidate falls back to the last known good catalog`() {
        val installed = CatalogTestFixtures.artifact(
            CatalogTestFixtures.manifestJson(
                catalogVersion = "catalog-2",
                generatedAt = "2026-10-01T00:00:00Z",
                sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = true)),
            ),
            CatalogProvenance.LAST_KNOWN_GOOD,
            approved,
        )
        val corrupt = CatalogFetchEnvelope("not json at all".encodeToByteArray())

        val selection = CatalogSelectionPolicy.select(bundled, installed, corrupt, nowAfterCandidate)

        assertEquals(CatalogSelectionOutcome.KEPT_LAST_KNOWN_GOOD, selection.outcome)
        assertEquals(
            CatalogCandidateRejection.Invalid(CatalogParseFailure.MALFORMED_JSON),
            selection.rejection,
        )
        assertEquals(installed, selection.artifact)
    }

    @Test
    fun `a corrupt cache does not load and a corrupt candidate falls back to the bundle`() {
        assertNull(
            CatalogArtifactLoader.load(
                "corrupt cache".encodeToByteArray(),
                CatalogProvenance.LAST_KNOWN_GOOD,
                approved,
            ),
        )

        val selection = CatalogSelectionPolicy.select(
            bundled,
            lastKnownGood = null,
            candidate = CatalogFetchEnvelope("{ broken".encodeToByteArray()),
            nowEpochSeconds = nowAfterCandidate,
        )

        assertEquals(CatalogSelectionOutcome.FELL_BACK_TO_BUNDLED, selection.outcome)
        assertEquals(bundled, selection.artifact)
    }

    @Test
    fun `identical candidate bytes keep the installed artifact`() {
        val json = CatalogTestFixtures.manifestJson(
            sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = true)),
        )
        val installed = CatalogTestFixtures.artifact(json, CatalogProvenance.LAST_KNOWN_GOOD, approved)
        val bytes = CatalogTestFixtures.bytes(json)

        val selection = CatalogSelectionPolicy.select(
            bundled,
            installed,
            CatalogFetchEnvelope(bytes),
            nowAfterCandidate,
        )

        assertEquals(CatalogSelectionOutcome.KEPT_INSTALLED, selection.outcome)
        assertEquals(installed, selection.artifact)
    }

    @Test
    fun `an equal timestamp with different bytes is not re-stamped`() {
        val manifest = CatalogTestFixtures.manifestJson(
            catalogVersion = "catalog-tie",
            generatedAt = "2026-10-01T00:00:00Z",
            sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = true)),
        )
        val installed = CatalogTestFixtures.artifact(manifest, CatalogProvenance.LAST_KNOWN_GOOD, approved)
        val other = CatalogFetchEnvelope(
            CatalogTestFixtures.bytes(
                manifest.replaceFirst("\"catalog_version\":\"catalog-tie\"", "\"catalog_version\":\"catalog-other\""),
            ),
        )

        val selection = CatalogSelectionPolicy.select(bundled, installed, other, nowAfterCandidate)

        assertEquals(CatalogSelectionOutcome.KEPT_INSTALLED, selection.outcome)
        assertEquals(installed, selection.artifact)
    }

    @Test
    fun `a far future candidate is refused`() {
        val candidate = candidate(
            revision = "catalog-future",
            generatedAt = "2027-01-01T00:00:00Z",
            sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = true)),
        )

        val selection = CatalogSelectionPolicy.select(bundled, null, candidate, epoch("2026-09-15T00:00:00Z"))

        assertEquals(CatalogSelectionOutcome.FELL_BACK_TO_BUNDLED, selection.outcome)
        assertEquals(
            CatalogCandidateRejection.Invalid(CatalogParseFailure.INVALID_FIELD_VALUE),
            selection.rejection,
        )
    }

    @Test
    fun `license and provenance gaps block default enable on a remote catalog`() {
        val candidate = candidate(
            revision = "catalog-2",
            generatedAt = "2026-10-01T00:00:00Z",
            sources = listOf(
                CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = true),
                CatalogTestFixtures.sourceJson(id = "approved-license", defaultEnabled = true, licenseName = ""),
                CatalogTestFixtures.sourceJson(id = "approved-mode", defaultEnabled = true, redistributionMode = "unknown_mode"),
                CatalogTestFixtures.sourceJson(
                    id = "approved-hash",
                    defaultEnabled = true,
                    sourceHash = "",
                    acceptedSourceHashes = "[]",
                ),
            ),
        )

        val selection = CatalogSelectionPolicy.select(bundled, null, candidate, nowAfterCandidate)

        assertEquals(CatalogSelectionOutcome.SELECTED_CANDIDATE, selection.outcome)
        assertEquals(setOf("approved-on"), selection.artifact.defaultEnabledSourceIds(approved))
    }

    @Test
    fun `a guardrail is never default enabled`() {
        val candidate = candidate(
            revision = "catalog-2",
            generatedAt = "2026-10-01T00:00:00Z",
            sources = listOf(
                // A source that claims the guardrail category but sits in the selectable array.
                CatalogTestFixtures.sourceJson(id = "approved-off", category = "guardrail", defaultEnabled = true),
            ),
            guardrails = listOf(
                CatalogTestFixtures.sourceJson(id = "guardrail-1", category = "guardrail", defaultEnabled = true),
            ),
        )

        val selection = CatalogSelectionPolicy.select(bundled, null, candidate, nowAfterCandidate)

        assertTrue(selection.artifact.defaultEnabledSourceIds(approved).isEmpty())
    }

    @Test
    fun `a revision mismatch in the envelope is rejected`() {
        val bytes = CatalogTestFixtures.bytes(
            CatalogTestFixtures.manifestJson(
                catalogVersion = "catalog-2",
                generatedAt = "2026-10-01T00:00:00Z",
                sources = listOf(CatalogTestFixtures.sourceJson(id = "approved-on", defaultEnabled = true)),
            ),
        )
        val selection = CatalogSelectionPolicy.select(
            bundled,
            null,
            CatalogFetchEnvelope(bytes, claimedRevision = "catalog-1", claimedSha256 = sha256Hex(bytes)),
            nowAfterCandidate,
        )

        assertEquals(CatalogSelectionOutcome.FELL_BACK_TO_BUNDLED, selection.outcome)
        assertEquals(
            CatalogCandidateRejection.Invalid(CatalogParseFailure.REVISION_MISMATCH),
            selection.rejection,
        )
    }

    @Test
    fun `freshness policy accepts a one week window and rejects errors and old caches`() {
        val policy = CatalogFreshnessPolicy()
        assertEquals(CatalogFreshnessPolicy.ONE_WEEK_SECONDS, policy.maxAgeSeconds)
        assertTrue(policy.isFresh(ageSeconds = null, statusIsError = false))
        assertTrue(policy.isFresh(ageSeconds = 0, statusIsError = false))
        assertTrue(policy.isFresh(ageSeconds = CatalogFreshnessPolicy.ONE_WEEK_SECONDS - 1, statusIsError = false))
        assertEquals(false, policy.isFresh(ageSeconds = CatalogFreshnessPolicy.ONE_WEEK_SECONDS, statusIsError = false))
        assertEquals(false, policy.isFresh(ageSeconds = -1, statusIsError = false))
        assertEquals(false, policy.isFresh(ageSeconds = 10, statusIsError = true))
        assertTrue(policy.needsRefresh(ageSeconds = CatalogFreshnessPolicy.ONE_WEEK_SECONDS, statusIsError = false))
    }

    private fun candidate(
        revision: String,
        generatedAt: String,
        sources: List<String>,
        guardrails: List<String> = emptyList(),
    ): CatalogFetchEnvelope = CatalogFetchEnvelope(
        bytes = CatalogTestFixtures.bytes(
            CatalogTestFixtures.manifestJson(
                catalogVersion = revision,
                generatedAt = generatedAt,
                sources = sources,
                guardrails = guardrails,
            ),
        ),
    )

    private fun epoch(value: String): Long = requireNotNull(CatalogTimestamps.parseEpochSeconds(value))
}
