package com.lavasec.app.foundation.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterArtifactIdentityCommitmentTest {
    @Test
    fun `identity commitment is deterministic canonical lowercase sha256`() {
        val identity = baselineIdentity()

        val first = canonicalFilterArtifactIdentitySha256(identity)
        val second = canonicalFilterArtifactIdentitySha256(identity.copy())

        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `identity commitment binds every artifact identity field and source order`() {
        val baseline = baselineIdentity()
        val expected = canonicalFilterArtifactIdentitySha256(baseline)
        val variants = listOf(
            baseline.copy(
                configuration = baseline.configuration.copy(generation = 8L),
            ),
            baseline.copy(
                configuration = baseline.configuration.copy(fingerprint = "changed"),
            ),
            baseline.copy(selectedSources = baseline.selectedSources.reversed()),
            baseline.copy(
                selectedSources = baseline.selectedSources.mapIndexed { index, source ->
                    if (index == 0) source.copy(id = "changed") else source
                },
            ),
            baseline.copy(
                selectedSources = baseline.selectedSources.mapIndexed { index, source ->
                    if (index == 0) source.copy(acceptedSha256 = "f".repeat(64)) else source
                },
            ),
            baseline.copy(
                selectedSources = baseline.selectedSources.mapIndexed { index, source ->
                    if (index == 0) source.copy(format = BlocklistFormat.ADBLOCK) else source
                },
            ),
            baseline.copy(catalogRevision = "catalog-2"),
            baseline.copy(catalogSha256 = "e".repeat(64)),
            baseline.copy(artifactSchema = 3),
            baseline.copy(pointerSchema = 2),
            baseline.copy(parserRulesVersion = 2),
            baseline.copy(ruleCounts = baseline.ruleCounts.copy(blockExact = 2)),
            baseline.copy(ruleCounts = baseline.ruleCounts.copy(blockSuffix = 3)),
            baseline.copy(ruleCounts = baseline.ruleCounts.copy(allowExact = 4)),
            baseline.copy(ruleCounts = baseline.ruleCounts.copy(allowSuffix = 5)),
            baseline.copy(ruleCounts = baseline.ruleCounts.copy(guardrailExact = 6)),
            baseline.copy(ruleCounts = baseline.ruleCounts.copy(guardrailSuffix = 7)),
            baseline.copy(ruleTablesSha256 = "d".repeat(64)),
            baseline.copy(compileInputsSha256 = "c".repeat(64)),
            baseline.copy(intentionalEmpty = true),
        )

        variants.forEach { variant ->
            assertNotEquals(expected, canonicalFilterArtifactIdentitySha256(variant))
        }
    }

    private fun baselineIdentity(): FilterArtifactIdentity = FilterArtifactIdentity(
        configuration = ArtifactConfigurationIdentity(
            generation = 7L,
            fingerprint = "configuration-fingerprint",
        ),
        selectedSources = listOf(
            ArtifactSourceIdentity(
                id = "source-a",
                acceptedSha256 = "a".repeat(64),
                format = BlocklistFormat.HOSTS,
            ),
            ArtifactSourceIdentity(
                id = "source-b",
                acceptedSha256 = "b".repeat(64),
                format = BlocklistFormat.PLAIN_DOMAINS,
            ),
        ),
        catalogRevision = "catalog-1",
        catalogSha256 = "1".repeat(64),
        artifactSchema = 2,
        pointerSchema = 1,
        parserRulesVersion = 1,
        ruleCounts = ArtifactRuleCounts(
            blockExact = 1,
            blockSuffix = 2,
            allowExact = 3,
            allowSuffix = 4,
            guardrailExact = 5,
            guardrailSuffix = 6,
        ),
        ruleTablesSha256 = "2".repeat(64),
        compileInputsSha256 = "3".repeat(64),
        intentionalEmpty = false,
    )
}
