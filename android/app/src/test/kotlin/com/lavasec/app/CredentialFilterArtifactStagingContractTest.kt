package com.lavasec.app

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.AcceptedFilterSourceAuthority
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.CredentialFilterArtifactStagingResult
import com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier
import com.lavasec.app.platform.FilterCatalogCompileAuthority
import com.lavasec.app.platform.FilterMutationFailure
import com.lavasec.app.platform.FilterMutationPreparationResult
import com.lavasec.app.platform.FilterMutationPreparer
import com.lavasec.app.platform.FilterMutationPublishResult
import com.lavasec.app.platform.FilterMutationPublisher
import com.lavasec.app.platform.FilterMutationRequest
import com.lavasec.app.platform.FilterMutationResult
import com.lavasec.app.platform.PreparedFilterMutation
import com.lavasec.app.platform.TransactionalFilterMutationCoordinator
import com.lavasec.app.platform.VerifiedFilterArtifact
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import java.lang.reflect.Modifier
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialFilterArtifactStagingContractTest {
    @Test
    fun `app preparer can verify exact compiler output without Direct Boot publication`() =
        runBlocking {
            val root = Files.createTempDirectory("credential-staging-contract-").toFile()
            try {
                val prepared = FilterCompiler(root.resolve("scratch")).compile(
                    FilterCompileRequest(
                        configurationIdentity = ArtifactConfigurationIdentity(
                            generation = 2L,
                            fingerprint = "app-preparer-contract",
                        ),
                        selectedSources = emptyList(),
                        catalogRevision = "contract-catalog",
                        catalogSha256 = "b".repeat(64),
                        maxRules = 0,
                        intentionalEmpty = true,
                    ),
                )
                try {
                    val stagedFile = root.resolve("credential/staged.lsfa")
                    requireNotNull(stagedFile.parentFile).mkdirs()
                    prepared.file.copyTo(stagedFile)

                    val verified = CredentialFilterArtifactStagingVerifier.verify(
                        file = stagedFile,
                        compilationReceipt = prepared.compilationReceipt,
                    )
                    assertTrue(verified is CredentialFilterArtifactStagingResult.Verified)
                    verified as CredentialFilterArtifactStagingResult.Verified
                    assertTrue(verified.artifact.channelIsOpenForTesting)
                    try {
                        assertEquals(prepared.sha256, verified.verifiedArtifact.sha256)
                        assertEquals(prepared.identity, verified.verifiedArtifact.artifactIdentity)
                    } finally {
                        verified.close()
                    }
                    assertFalse(verified.artifact.channelIsOpenForTesting)

                    stagedFile.appendBytes(byteArrayOf(0))
                    val tampered = CredentialFilterArtifactStagingVerifier.verify(
                        file = stagedFile,
                        compilationReceipt = prepared.compilationReceipt,
                    )
                    assertEquals(
                        CredentialFilterArtifactStagingResult.Unavailable(
                            FilterArtifactUnavailableReason.DIGEST_MISMATCH,
                        ),
                        tampered,
                    )
                } finally {
                    prepared.close()
                }
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `app preparer can stage verify and publish through public contracts`() = runBlocking {
        val root = Files.createTempDirectory("app-filter-preparer-contract-").toFile()
        val catalogRevision = "contract-catalog"
        val catalogSha256 = "b".repeat(64)
        val base = FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList()))
        val desired = base.withResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val initialPrepared = compileForConfiguration(
            root = root.resolve("initial"),
            configuration = base,
            generation = 1L,
            catalogRevision = catalogRevision,
            catalogSha256 = catalogSha256,
        )
        val initialVerification = CredentialFilterArtifactStagingVerifier.verify(
            file = initialPrepared.file,
            compilationReceipt = initialPrepared.compilationReceipt,
        ) as CredentialFilterArtifactStagingResult.Verified
        var stagedVerification: CredentialFilterArtifactStagingResult.Verified? = null
        var stagedPrepared: PreparedFilterArtifact? = null
        var cleanupCount = 0
        try {
            val initial = confirmed(base, initialVerification.verifiedArtifact)
            val preparer = FilterMutationPreparer { plan ->
                val compiled = FilterCompiler(root.resolve("candidate-scratch"))
                    .compile(plan.compileRequest)
                val stagedFile = root.resolve("credential/candidate.lsfa")
                requireNotNull(stagedFile.parentFile).mkdirs()
                compiled.file.copyTo(stagedFile, overwrite = true)
                when (
                    val verification = CredentialFilterArtifactStagingVerifier.verify(
                        file = stagedFile,
                        compilationReceipt = compiled.compilationReceipt,
                    )
                ) {
                    is CredentialFilterArtifactStagingResult.Unavailable -> {
                        compiled.close()
                        FilterMutationPreparationResult.Failed(
                            FilterMutationFailure.REOPEN_FAILED,
                        )
                    }
                    is CredentialFilterArtifactStagingResult.Verified -> {
                        stagedVerification = verification
                        stagedPrepared = compiled
                        val candidate = confirmed(
                            configuration = plan.mutationRequest.desired,
                            verifiedArtifact = verification.verifiedArtifact,
                        )
                        FilterMutationPreparationResult.Prepared(
                            PreparedFilterMutation(
                                candidate = candidate,
                                compilationReceipt = compiled.compilationReceipt,
                            ) {
                                cleanupCount += 1
                                verification.close()
                                compiled.close()
                                stagedFile.delete()
                            },
                        )
                    }
                }
            }
            val coordinator = TransactionalFilterMutationCoordinator(
                initialState = initial,
                catalogCompileAuthority = FilterCatalogCompileAuthority(
                    revision = catalogRevision,
                    sha256 = catalogSha256,
                    sources = emptyList(),
                    maxRules = 0,
                ),
                acceptedSourceAuthority = AcceptedFilterSourceAuthority { sources, _ ->
                    check(sources.isEmpty())
                    emptyList()
                },
                preparer = preparer,
                publisher = FilterMutationPublisher { _, mutation ->
                    val verification = requireNotNull(stagedVerification)
                    assertTrue(verification.artifact.channelIsOpenForTesting)
                    assertSame(
                        verification.verifiedArtifact,
                        mutation.candidate.verifiedArtifact,
                    )
                    FilterMutationPublishResult.Committed
                },
            )

            val outcome = coordinator.apply(
                FilterMutationRequest(
                    expectedGeneration = 1L,
                    desired = desired,
                ),
            )

            assertTrue(outcome is FilterMutationResult.Applied)
            assertSame(
                requireNotNull(stagedVerification).verifiedArtifact,
                outcome.confirmedState.verifiedArtifact,
            )
            assertEquals(1, cleanupCount)
            assertFalse(requireNotNull(stagedVerification).artifact.channelIsOpenForTesting)
            assertFalse(
                VerifiedFilterArtifact::class.java.declaredConstructors
                    .filterNot { it.isSynthetic }
                    .any { Modifier.isPublic(it.modifiers) },
            )
            assertFalse(
                CredentialFilterArtifactStagingResult.Verified::class.java.declaredConstructors
                    .filterNot { it.isSynthetic }
                    .any { Modifier.isPublic(it.modifiers) },
            )
            assertFalse(
                VerifiedFilterArtifact.Companion::class.java.declaredMethods.any {
                    Modifier.isPublic(it.modifiers) && it.name == "fromOpened"
                },
            )
            assertFalse(
                CredentialFilterArtifactStagingResult.Verified::class.java.declaredClasses
                    .single { it.simpleName == "Companion" }
                    .declaredMethods
                    .any {
                        Modifier.isPublic(it.modifiers) && it.name == "create"
                    },
            )
        } finally {
            stagedVerification
                ?.takeIf { it.artifact.channelIsOpenForTesting }
                ?.close()
            stagedPrepared?.close()
            initialVerification.close()
            initialPrepared.close()
            root.deleteRecursively()
        }
    }

    private suspend fun compileForConfiguration(
        root: java.io.File,
        configuration: FilterLibraryDraft,
        generation: Long,
        catalogRevision: String,
        catalogSha256: String,
    ): PreparedFilterArtifact = FilterCompiler(root.resolve("scratch")).compile(
        FilterCompileRequest(
            configurationIdentity = ArtifactConfigurationIdentity(
                generation = generation,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    configuration = configuration,
                    catalogRevision = catalogRevision,
                    catalogSha256 = catalogSha256,
                ),
            ),
            selectedSources = emptyList(),
            catalogRevision = catalogRevision,
            catalogSha256 = catalogSha256,
            maxRules = 0,
            intentionalEmpty = true,
        ),
    )

    private fun confirmed(
        configuration: FilterLibraryDraft,
        verifiedArtifact: VerifiedFilterArtifact,
    ): ConfirmedFilterState {
        val identity = verifiedArtifact.artifactIdentity.configuration
        return ConfirmedFilterState(
            configuration = configuration,
            configurationIdentity = ConfigurationIdentity(
                generation = identity.generation,
                fingerprint = identity.fingerprint,
            ),
            credentialUnlockRequired = false,
            verifiedArtifact = verifiedArtifact,
        )
    }
}
