package com.lavasec.app

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CatalogFilterSource
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfile
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.foundation.filter.FilterSourcePayload
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.CredentialFilterArtifactStagingResult
import com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import com.lavasec.app.presentation.filters.FilterSourceCatalogEntry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Filters library derivation against the live confirmed state: after a coordinator
 * commit (here a whole-profile switch) the UI must render the confirmed configuration's
 * profiles and active profile id, and attach the new artifact's rule count to that profile —
 * never the configuration captured at graph creation.
 */
class CredentialFilterLibraryStateTest {
    private val catalogRevision = "credential-filter-library-state"
    private val catalogSha256 = "c".repeat(64)

    @Test
    fun `committed profile switch renders the confirmed configuration and its artifact count`() =
        runBlocking {
            val root = Files.createTempDirectory("credential-filter-library-state-").toFile()
            try {
                val seeded = seededDraft()
                val switched = seeded.withActiveProfile(FilterProfile.stableId(ProtectionLevel.EXTRA))
                val confirmed = switchedConfirmed(root, switched)
                val entries = catalogEntries()

                val state = requireNotNull(filterLibraryUiStateFor(confirmed, entries))

                // The graph-creation draft had Balanced active; the confirmed configuration
                // is authoritative after the coordinator committed the switch.
                assertEquals(FilterProfile.stableId(ProtectionLevel.EXTRA), state.activeFilterId)
                val expected = confirmed.verifiedArtifact.artifactIdentity.ruleCounts
                    .let { counts -> counts.blockExact + counts.blockSuffix }
                assertTrue(expected > 0)
                val extra = state.filters.first { profile ->
                    profile.id == FilterProfile.stableId(ProtectionLevel.EXTRA)
                }
                assertEquals(expected, extra.activeRuleCount)
                state.filters.filter { profile ->
                    profile.id != FilterProfile.stableId(ProtectionLevel.EXTRA)
                }.forEach { profile ->
                    assertNull(profile.activeRuleCount)
                }
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `unavailable confirmed state or absent catalog entries map to the unavailable surface`() =
        runBlocking {
            val root = Files.createTempDirectory("credential-filter-library-state-").toFile()
            try {
                val confirmed = switchedConfirmed(root, seededDraft())

                assertNull(filterLibraryUiStateFor(null, catalogEntries()))
                assertNull(filterLibraryUiStateFor(confirmed, null))
                assertNull(filterLibraryUiStateFor(null, null))
            } finally {
                root.deleteRecursively()
            }
        }

    private suspend fun switchedConfirmed(
        root: File,
        configuration: FilterLibraryDraft,
    ): ConfirmedFilterState {
        val generation = 2L
        val fingerprint = canonicalFilterConfigurationFingerprint(
            configuration = configuration,
            catalogRevision = catalogRevision,
            catalogSha256 = catalogSha256,
        )
        val bytes = "alpha.fixture.example\nbeta.fixture.example\n".encodeToByteArray()
        val prepared = FilterCompiler(root.resolve("scratch")).compile(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(generation, fingerprint),
                selectedSources = listOf(
                    FilterSourcePayload(
                        id = "blocklistproject-basic",
                        format = BlocklistFormat.PLAIN_DOMAINS,
                        acceptedSha256 = sha256(bytes),
                        declaredByteSize = bytes.size.toLong(),
                        openStream = { bytes.inputStream() },
                    ),
                ),
                catalogRevision = catalogRevision,
                catalogSha256 = catalogSha256,
                maxRules = 100,
            ),
        )
        val verification = CredentialFilterArtifactStagingVerifier.verify(
            file = prepared.file,
            compilationReceipt = prepared.compilationReceipt,
        ) as CredentialFilterArtifactStagingResult.Verified
        val verified = verification.verifiedArtifact
        verification.close()
        prepared.close()
        return ConfirmedFilterState(
            configuration = configuration,
            configurationIdentity = ConfigurationIdentity(
                generation = generation,
                fingerprint = fingerprint,
            ),
            credentialUnlockRequired = false,
            verifiedArtifact = verified,
        )
    }

    private fun seededDraft(): FilterLibraryDraft = FilterLibraryDraft.seededDefaults(
        FilterCatalogSnapshot(
            listOf(
                CatalogFilterSource(
                    "blocklistproject-basic",
                    FilterSourceCategory.SECURITY,
                    defaultEnabled = true,
                ),
                CatalogFilterSource(
                    "stevenblack-unified",
                    FilterSourceCategory.MULTI_PURPOSE,
                    defaultEnabled = true,
                ),
                CatalogFilterSource(
                    "blocklistproject-ads",
                    FilterSourceCategory.ADS_TRACKING,
                    defaultEnabled = false,
                ),
                CatalogFilterSource(
                    "blocklistproject-tracking",
                    FilterSourceCategory.ADS_TRACKING,
                    defaultEnabled = false,
                ),
            ),
        ),
    )

    private fun catalogEntries(): List<FilterSourceCatalogEntry> = listOf(
        FilterSourceCatalogEntry(
            "blocklistproject-basic",
            "Block List Basic",
            FilterSourceCategory.SECURITY,
        ),
        FilterSourceCatalogEntry(
            "stevenblack-unified",
            "StevenBlack Unified Hosts",
            FilterSourceCategory.MULTI_PURPOSE,
        ),
        FilterSourceCatalogEntry(
            "blocklistproject-ads",
            "Block List Ads",
            FilterSourceCategory.ADS_TRACKING,
        ),
        FilterSourceCatalogEntry(
            "blocklistproject-tracking",
            "Block List Tracking",
            FilterSourceCategory.ADS_TRACKING,
        ),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
