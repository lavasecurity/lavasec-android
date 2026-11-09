package com.lavasec.app.storage

import android.content.Context
import android.os.Build
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.CredentialFilterArtifactStagingResult
import com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier
import com.lavasec.app.platform.FilterRuntimeLeaseRead
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialFilterConfigurationOsInstrumentationTest {
    @Test
    @SdkSuppress(minSdkVersion = 28, maxSdkVersion = 28)
    fun api28CredentialStoreFailsClosedWhenAuthoritativeRecordDescriptorCloseFails() {
        runBlocking {
            withFixture { context, fixture ->
                var closeFailuresRemaining = 0
                val fileSystem = AndroidOsArtifactPublicationFileSystemBackend(
                    readDescriptorCloser = AndroidOsDescriptorCloser { descriptor ->
                        Os.close(descriptor)
                        if (closeFailuresRemaining > 0) {
                            closeFailuresRemaining -= 1
                            throw IOException(
                                "injected authoritative-record descriptor close failure",
                            )
                        }
                    },
                )
                val root =
                    CredentialFilterArtifactPublicationStore
                        .credentialProtectedRoot(context)
                val artifactStore = CredentialFilterArtifactPublicationStore(
                    rootDirectory = root,
                    authority = CredentialFilterArtifactPublicationAuthority(),
                    fileSystem = fileSystem,
                )
                val store = CredentialFilterConfigurationStore(
                    rootDirectory = root,
                    authority = CredentialFilterConfigurationAuthority(),
                    artifactStore = artifactStore,
                    fileSystem = fileSystem,
                    maxRules = 0,
                )
                assertTrue(
                    store.initialize(fixture.state, fixture.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )

                val descriptorsBeforeFailure = openDescriptorCount()
                repeat(64) {
                    closeFailuresRemaining = 1
                    assertStorageUnavailable(store.readAuthoritative())
                    assertEquals(0, closeFailuresRemaining)
                }
                val descriptorsAfterFailures = openDescriptorCount()
                assertTrue(
                    "64 injected close-boundary failures exceeded the configured " +
                        "four-descriptor measurement tolerance: " +
                        "before=$descriptorsBeforeFailure after=$descriptorsAfterFailures",
                    descriptorsAfterFailures <= descriptorsBeforeFailure + 4,
                )

                assertEquals(fixture.state, store.readAvailable())
                val descriptorsAfterRecovery = openDescriptorCount()
                assertTrue(
                    "close-boundary recovery exceeded the configured four-descriptor " +
                        "measurement tolerance: " +
                        "before=$descriptorsBeforeFailure after=$descriptorsAfterRecovery",
                    descriptorsAfterRecovery <= descriptorsBeforeFailure + 4,
                )
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 28, maxSdkVersion = 28)
    fun api28CredentialStoreRestartsExactlyWithPointerAbsentAndDescriptorCountWithinInstrumentationTolerance() {
        runBlocking {
            withFixture { context, fixture ->
                val store = CredentialFilterConfigurationStore.credentialProtected(
                    context = context,
                    maxRules = 0,
                )
                val pointer =
                    CredentialFilterArtifactPublicationStore
                        .credentialProtected(context)
                        .currentPointerFileForTesting

                val initialized = store.initialize(fixture.state, fixture.prepared)

                assertTrue(
                    initialized is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                initialized as CredentialFilterConfigurationInitializationResult.Initialized
                assertEquals(fixture.state, initialized.state)
                assertFalse(pointer.exists())

                val restarted = CredentialFilterConfigurationStore.credentialProtected(
                    context = context,
                    maxRules = 0,
                )
                val recovered = restarted.readAvailable()
                assertEquals(fixture.state.configuration, recovered.configuration)
                assertEquals(
                    fixture.state.configurationIdentity,
                    recovered.configurationIdentity,
                )
                assertEquals(
                    fixture.state.verifiedArtifact,
                    recovered.verifiedArtifact,
                )
                assertFalse(pointer.exists())

                val descriptorsBeforeRepeatedReads = openDescriptorCount()
                repeat(64) {
                    restarted.readAvailable()
                }
                val descriptorsAfterRepeatedReads = openDescriptorCount()
                assertTrue(
                    "64 exact reads exceeded the configured four-descriptor " +
                        "measurement tolerance: " +
                        "before=$descriptorsBeforeRepeatedReads " +
                        "after=$descriptorsAfterRepeatedReads",
                    descriptorsAfterRepeatedReads <= descriptorsBeforeRepeatedReads + 4,
                )
                assertFalse(pointer.exists())
            }
        }
    }

    @Test
    fun api28AndApi36AuthoritativeRuntimeOwnershipCyclesRetainDescriptorStability() {
        runBlocking {
            withFixture(requireExactApi28 = false) { context, fixture ->
                val store = CredentialFilterConfigurationStore.credentialProtected(
                    context = context,
                    maxRules = 0,
                )
                assertTrue(
                    store.initialize(fixture.state, fixture.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                val restarted = CredentialFilterConfigurationStore.credentialProtected(
                    context = context,
                    maxRules = 0,
                )

                val descriptorsBeforeRuntimeCycles = openDescriptorCount()
                repeat(64) {
                    val read = restarted.readAuthoritativeRuntime()
                    val available =
                        read as? CredentialFilterConfigurationRuntimeRead.Available
                            ?: throw AssertionError(
                                "expected available runtime configuration but got $read",
                            )
                    try {
                        val lease = available.takeLease()
                        try {
                            assertEquals(
                                FilterRuntimeLeaseRead.Available(FilterDecision.DEFAULT_ALLOW),
                                lease.withDecisions { decisions ->
                                    decisions.decide("runtime-cycle.example")
                                },
                            )
                        } finally {
                            lease.close()
                        }
                    } finally {
                        available.close()
                    }
                }
                repeat(64) {
                    val read = restarted.readAuthoritativeRuntime()
                    val available =
                        read as? CredentialFilterConfigurationRuntimeRead.Available
                            ?: throw AssertionError(
                                "expected carrier-owned runtime configuration but got $read",
                            )
                    available.close()
                    assertThrows(IllegalStateException::class.java) {
                        available.takeLease()
                    }
                }
                val descriptorsAfterRuntimeCycles = openDescriptorCount()
                assertTrue(
                    "128 transferred and carrier-owned runtime cycles exceeded the " +
                        "configured four-descriptor " +
                        "measurement tolerance: before=$descriptorsBeforeRuntimeCycles " +
                        "after=$descriptorsAfterRuntimeCycles",
                    descriptorsAfterRuntimeCycles <= descriptorsBeforeRuntimeCycles + 4,
                )
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 28, maxSdkVersion = 28)
    fun api28CredentialStoreRejectsNonRegularAuthoritativeRecordKinds() {
        runBlocking {
            withFixture { context, fixture ->
                val store = CredentialFilterConfigurationStore.credentialProtected(
                    context = context,
                    maxRules = 0,
                )
                assertTrue(
                    store.initialize(fixture.state, fixture.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )

                val authoritativeRecord = store.authoritativeRecordFileForTesting
                val symlinkTarget = File(
                    context.cacheDir,
                    "authoritative-filter-record-${UUID.randomUUID()}",
                )
                try {
                    authoritativeRecord.copyTo(symlinkTarget)
                    assertTrue(authoritativeRecord.delete())
                    Os.symlink(symlinkTarget.path, authoritativeRecord.path)

                    assertStorageUnavailable(store.readAuthoritative())

                    assertTrue(authoritativeRecord.delete())
                    assertTrue(authoritativeRecord.mkdir())

                    assertStorageUnavailable(store.readAuthoritative())
                } finally {
                    symlinkTarget.delete()
                }
            }
        }
    }

    private suspend fun withFixture(
        requireExactApi28: Boolean = true,
        block: suspend (Context, Fixture) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (requireExactApi28) {
            assertEquals(28, Build.VERSION.SDK_INT)
        } else {
            assertTrue(Build.VERSION.SDK_INT == 28 || Build.VERSION.SDK_INT == 36)
        }
        assertFalse(context.isDeviceProtectedStorage)
        val publicationRoot =
            CredentialFilterArtifactPublicationStore.credentialProtectedRoot(context)
        publicationRoot.deleteRecursively()
        val scratch = File(
            context.cacheDir,
            "authoritative-filter-configuration-${UUID.randomUUID()}",
        )
        val fixture = fixture(scratch)
        try {
            block(context, fixture)
        } finally {
            fixture.close()
            scratch.deleteRecursively()
            publicationRoot.deleteRecursively()
        }
    }

    private suspend fun fixture(root: File): Fixture {
        val configuration =
            FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList()))
        val configurationIdentity = ConfigurationIdentity(
            generation = 1L,
            fingerprint = canonicalFilterConfigurationFingerprint(
                configuration = configuration,
                catalogRevision = CATALOG_REVISION,
                catalogSha256 = CATALOG_SHA256,
            ),
        )
        val prepared = FilterCompiler(File(root, "compiler")).compile(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(
                    generation = configurationIdentity.generation,
                    fingerprint = configurationIdentity.fingerprint,
                ),
                selectedSources = emptyList(),
                catalogRevision = CATALOG_REVISION,
                catalogSha256 = CATALOG_SHA256,
                maxRules = 0,
                intentionalEmpty = true,
            ),
        )
        try {
            val verified = CredentialFilterArtifactStagingVerifier.verify(
                file = prepared.file,
                compilationReceipt = prepared.compilationReceipt,
            ) as? CredentialFilterArtifactStagingResult.Verified
                ?: throw AssertionError(
                    "compiler output did not reopen as the exact artifact",
                )
            try {
                val state = ConfirmedFilterState(
                    configuration = configuration,
                    configurationIdentity = configurationIdentity,
                    credentialUnlockRequired = false,
                    verifiedArtifact = verified.verifiedArtifact,
                )
                return Fixture(
                    prepared = prepared,
                    verified = verified,
                    state = state,
                )
            } catch (error: Throwable) {
                verified.close()
                throw error
            }
        } catch (error: Throwable) {
            prepared.close()
            throw error
        }
    }

    private suspend fun CredentialFilterConfigurationStore.readAvailable(): ConfirmedFilterState {
        val read = readAuthoritative()
        return (read as? CredentialFilterConfigurationRead.Available)?.state
            ?: throw AssertionError("expected available configuration but got $read")
    }

    private fun assertStorageUnavailable(read: CredentialFilterConfigurationRead) {
        assertTrue(read is CredentialFilterConfigurationRead.Unavailable)
        read as CredentialFilterConfigurationRead.Unavailable
        assertEquals(
            CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE,
            read.reason,
        )
    }

    private fun openDescriptorCount(): Int =
        File("/proc/self/fd").list()?.size
            ?: throw AssertionError("could not inspect process descriptors")

    private class Fixture(
        val prepared: PreparedFilterArtifact,
        private val verified: CredentialFilterArtifactStagingResult.Verified,
        val state: ConfirmedFilterState,
    ) : AutoCloseable {
        override fun close() {
            verified.close()
            prepared.close()
        }
    }

    private companion object {
        const val CATALOG_REVISION = "authoritative-config-instrumentation-catalog"
        val CATALOG_SHA256 = "d".repeat(64)
    }
}
