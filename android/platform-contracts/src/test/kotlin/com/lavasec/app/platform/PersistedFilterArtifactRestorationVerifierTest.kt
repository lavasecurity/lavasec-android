package com.lavasec.app.platform

import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactOpenResult
import java.lang.reflect.Modifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedFilterArtifactRestorationVerifierTest {
    @Test
    fun `exact already-open artifact mints a witness without taking caller ownership`() = runTest {
        val fixture = RealFilterArtifactFixture.create(
            ConfigurationIdentity(41, "persisted-configuration"),
        )
        val opened = fixture.openArtifact()
        try {
            val verified = PersistedFilterArtifactRestorationVerifier.verifyOrNull(
                reference = fixture.reference,
                artifact = opened,
            )

            assertEquals(fixture.reference.sha256, verified?.sha256)
            assertEquals(fixture.reference.artifactIdentity, verified?.artifactIdentity)
            assertTrue(opened.channelIsOpenForTesting)
            assertFalse(
                VerifiedFilterArtifact::class.java.declaredConstructors
                    .filterNot { it.isSynthetic }
                    .any { Modifier.isPublic(it.modifiers) },
            )
        } finally {
            opened.close()
            assertFalse(opened.channelIsOpenForTesting)
            fixture.close()
        }
    }

    @Test
    fun `identity substitution is rejected without closing caller artifact`() = runTest {
        val fixture = RealFilterArtifactFixture.create(
            ConfigurationIdentity(42, "persisted-configuration"),
        )
        val opened = fixture.openArtifact()
        try {
            val substituted = FilterArtifactReference(
                sha256 = fixture.reference.sha256,
                artifactIdentity = fixture.reference.artifactIdentity.copy(
                    catalogRevision = "substituted-catalog",
                ),
            )

            assertNull(
                PersistedFilterArtifactRestorationVerifier.verifyOrNull(
                    reference = substituted,
                    artifact = opened,
                ),
            )
            assertTrue(opened.channelIsOpenForTesting)
        } finally {
            opened.close()
            fixture.close()
        }
    }

    @Test
    fun `digest substitution is rejected without closing caller artifact`() = runTest {
        val fixture = RealFilterArtifactFixture.create(
            ConfigurationIdentity(43, "persisted-configuration"),
        )
        val opened = fixture.openArtifact()
        try {
            val substituted = FilterArtifactReference(
                sha256 = if (fixture.reference.sha256 == "0".repeat(64)) {
                    "1".repeat(64)
                } else {
                    "0".repeat(64)
                },
                artifactIdentity = fixture.reference.artifactIdentity,
            )

            assertNull(
                PersistedFilterArtifactRestorationVerifier.verifyOrNull(
                    reference = substituted,
                    artifact = opened,
                ),
            )
            assertTrue(opened.channelIsOpenForTesting)
        } finally {
            opened.close()
            fixture.close()
        }
    }

    @Test
    fun `closed artifact is rejected`() = runTest {
        val fixture = RealFilterArtifactFixture.create(
            ConfigurationIdentity(44, "persisted-configuration"),
        )
        val opened = fixture.openArtifact()
        opened.close()
        try {
            assertNull(
                PersistedFilterArtifactRestorationVerifier.verifyOrNull(
                    reference = fixture.reference,
                    artifact = opened,
                ),
            )
        } finally {
            fixture.close()
        }
    }

    private fun RealFilterArtifactFixture.openArtifact(): FilterArtifact =
        when (
            val result = FilterArtifact.openResult(
                file = file,
                expectedSha256 = reference.sha256,
            )
        ) {
            is FilterArtifactOpenResult.Available -> result.artifact
            is FilterArtifactOpenResult.Unavailable ->
                error("fixture artifact must open: ${result.reason}")
        }
}
