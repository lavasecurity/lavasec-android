package com.lavasec.app.reactnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.resolver.CustomResolverValidator
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.CredentialFilterArtifactStagingResult
import com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import com.lavasec.app.storage.AndroidOsArtifactPublicationFileSystemBackend
import com.lavasec.app.storage.CredentialFilterArtifactPublicationAuthority
import com.lavasec.app.storage.CredentialFilterArtifactPublicationStore
import com.lavasec.app.storage.CredentialFilterConfigurationAuthority
import com.lavasec.app.storage.CredentialFilterConfigurationInitializationResult
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reopens production OS-backed authority; endpoint storage is independent of entitlement/UI. */
@RunWith(AndroidJUnit4::class)
class CustomResolverPersistenceInstrumentationTest {
    @Test
    fun nativeAuthoritativeRecordReconstructsExactCustomDohAndDotEndpoints() = runBlocking {
        // This exercises persistence only. Reserved hostnames pass the production endpoint
        // contract, are never resolved/contacted, and avoid weakening its loopback rejection.
        val dohUrl = "https://doh.parity.example:8443/custom-dns"
        val dotEndpoint = "tls://dot.parity.example:8853"
        assertEquals(dohUrl, CustomResolverValidator.normalizedDohUrl(dohUrl))
        assertEquals(dotEndpoint, CustomResolverValidator.normalizedDotEndpoint(dotEndpoint))
        val plans = listOf(
            FilterResolverPlan(MilestoneResolverPlan.MULLVAD_ONLY, customDohUrl = dohUrl),
            FilterResolverPlan(MilestoneResolverPlan.MULLVAD_ONLY, customDotEndpoint = dotEndpoint),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (plan in plans) {
            val root = File(context.cacheDir, "custom-resolver-authority-${UUID.randomUUID()}")
            val configuration = FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList())).withResolverPlan(plan)
            val identity = ConfigurationIdentity(1, canonicalFilterConfigurationFingerprint(
                configuration, CATALOG_REVISION, CATALOG_SHA256,
            ))
            val prepared = FilterCompiler(File(root, "compiler")).compile(FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(identity.generation, identity.fingerprint),
                selectedSources = emptyList(), catalogRevision = CATALOG_REVISION, catalogSha256 = CATALOG_SHA256,
                maxRules = 0, intentionalEmpty = true,
            ))
            try {
                val verified = CredentialFilterArtifactStagingVerifier.verify(prepared.file, prepared.compilationReceipt)
                    as? CredentialFilterArtifactStagingResult.Verified
                    ?: throw AssertionError("custom resolver fixture artifact failed verification")
                try {
                    val expected = ConfirmedFilterState(configuration, identity, false, verified.verifiedArtifact)
                    assertTrue(store(root).initialize(expected, prepared) is CredentialFilterConfigurationInitializationResult.Initialized)
                    val read = store(root).readAuthoritative() as? CredentialFilterConfigurationRead.Available
                        ?: throw AssertionError("custom resolver native authoritative record did not reopen")
                    assertEquals(expected, read.state)
                    assertEquals(plan, read.state.configuration.resolverTarget)
                    assertEquals(plan.configuration, read.state.configuration.resolverTarget.configuration)
                    assertEquals(plan.customDohUrl, read.state.configuration.resolverTarget.customDohUrl)
                    assertEquals(plan.customDotEndpoint, read.state.configuration.resolverTarget.customDotEndpoint)
                } finally { verified.close() }
            } finally {
                prepared.close()
                root.deleteRecursively()
            }
        }
    }

    private fun store(root: File): CredentialFilterConfigurationStore {
        val fileSystem = AndroidOsArtifactPublicationFileSystemBackend()
        val publicationRoot = File(root, "publication")
        val artifactStore = CredentialFilterArtifactPublicationStore(
            rootDirectory = publicationRoot,
            authority = CredentialFilterArtifactPublicationAuthority(),
            fileSystem = fileSystem,
        )
        return CredentialFilterConfigurationStore(publicationRoot, CredentialFilterConfigurationAuthority(), artifactStore, fileSystem, 0)
    }

    private companion object {
        const val CATALOG_REVISION = "emulator-custom-resolver-catalog"
        val CATALOG_SHA256 = "a".repeat(64)
    }
}
