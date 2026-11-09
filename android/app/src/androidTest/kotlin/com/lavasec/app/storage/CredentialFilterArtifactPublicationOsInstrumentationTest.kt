package com.lavasec.app.storage

import android.os.Build
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.FilterArtifactExpectation
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialFilterArtifactPublicationOsInstrumentationTest {
    @Test
    @SdkSuppress(minSdkVersion = 28, maxSdkVersion = 28)
    fun api28OsBackendPublishesReplacesReadsAndRejectsSymlink() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals(28, Build.VERSION.SDK_INT)
            assertFalse(context.isDeviceProtectedStorage)
            val publicationRoot = File(context.filesDir, "filter-artifacts")
            publicationRoot.deleteRecursively()
            val scratch = File(context.cacheDir, "artifact-publication-${UUID.randomUUID()}")
            val first = compile(scratch, generation = 1)
            val second = compile(scratch, generation = 2)
            try {
                val store = CredentialFilterArtifactPublicationStore.credentialProtected(context)
                val successorStore =
                    CredentialFilterArtifactPublicationStore.credentialProtected(context)

                val firstResult = store.publish(first, expectedCurrentSha256 = null)
                assertTrue(firstResult is CredentialFilterArtifactPublicationResult.Published)
                store.readAvailable(expectation(first)).close()

                val secondResult =
                    successorStore.publish(second, expectedCurrentSha256 = first.sha256)
                assertTrue(secondResult is CredentialFilterArtifactPublicationResult.Published)
                successorStore.readAvailable(expectation(second)).close()
                val descriptorsBeforeRepeatedReads = openDescriptorCount()
                repeat(64) {
                    successorStore.readAvailable(expectation(second)).close()
                }
                val descriptorsAfterRepeatedReads = openDescriptorCount()
                assertTrue(
                    "artifact reads leaked descriptors: before=$descriptorsBeforeRepeatedReads " +
                        "after=$descriptorsAfterRepeatedReads",
                    descriptorsAfterRepeatedReads <= descriptorsBeforeRepeatedReads + 4,
                )

                val generation = successorStore.generationDirectoryForTesting(second.sha256)
                val artifact = File(generation, "artifact.lsfa")
                val external = File(context.filesDir, "symlink-target-${UUID.randomUUID()}.lsfa")
                artifact.copyTo(external)
                assertTrue(artifact.delete())
                Os.symlink(external.path, artifact.path)

                val unavailable = successorStore.readCurrent(expectation(second), maxRules = 0)
                assertTrue(unavailable is CredentialFilterArtifactCurrentRead.Unavailable)
                external.delete()
            } finally {
                second.close()
                first.close()
                scratch.deleteRecursively()
                publicationRoot.deleteRecursively()
            }
        }
    }

    private suspend fun compile(root: File, generation: Long) =
        FilterCompiler(File(root, "compiler-$generation")).compile(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(
                    generation = generation,
                    fingerprint = "instrumentation-$generation",
                ),
                selectedSources = emptyList(),
                catalogRevision = "instrumentation-catalog",
                catalogSha256 = "b".repeat(64),
                maxRules = 0,
                intentionalEmpty = true,
            ),
        )

    private fun expectation(
        prepared: com.lavasec.app.foundation.filter.PreparedFilterArtifact,
    ) = FilterArtifactExpectation(
        configuration = prepared.identity.configuration,
        selectedSources = emptyList(),
        catalogRevision = prepared.identity.catalogRevision,
        catalogSha256 = prepared.identity.catalogSha256,
    )

    private fun CredentialFilterArtifactPublicationStore.readAvailable(
        expectation: FilterArtifactExpectation,
    ): CredentialFilterArtifactCurrentRead.Available {
        val read = readCurrent(expectation, maxRules = 0)
        return read as? CredentialFilterArtifactCurrentRead.Available
            ?: throw AssertionError("expected available artifact but got $read")
    }

    private fun openDescriptorCount(): Int =
        File("/proc/self/fd").list()?.size
            ?: throw AssertionError("could not inspect process descriptors")
}
