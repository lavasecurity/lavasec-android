package com.lavasec.app.directboot

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.DirectBootAppState
import com.lavasec.app.platform.DirectBootArtifactReadResult
import com.lavasec.app.platform.DirectBootArtifactReader
import com.lavasec.app.platform.DirectBootCommittedSnapshotPublication
import com.lavasec.app.platform.DirectBootMirrorResult
import com.lavasec.app.platform.DirectBootSnapshotAuthenticator
import com.lavasec.app.platform.DirectBootSnapshotFreshnessAuthority
import com.lavasec.app.platform.DirectBootSnapshotKeyRotationResult
import com.lavasec.app.platform.ProtectionIntentWriteCoordinator
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProtectedArtifactPublisherTest {
    @Test
    fun `staged artifact remains inert until fresh snapshot publication`() = runPublisherTest {
        root, authority ->
        val prepared = FilterCompiler(root.resolve("compiler")).compile(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(1L, "a".repeat(64)),
                selectedSources = emptyList(),
                catalogRevision = "catalog-v1",
                catalogSha256 = "b".repeat(64),
                maxRules = 0,
                intentionalEmpty = true,
            ),
        )
        prepared.use {
            val state = directBootState(credentialUnlockRequired = false)
            val publisher = DeviceProtectedArtifactPublisher(root)

            val reference = publisher.stage(prepared)

            assertNotNull(reference)
            assertNull(DeviceProtectedConfigurationReader(root, authority).readOrNull())
            assertTrue(publishThroughFreshCommit(authority, state) { publication ->
                publisher.publishReference(state, checkNotNull(reference), publication)
            })
            assertNotNull(DeviceProtectedConfigurationReader(root, authority).readOrNull())
        }
    }

    @Test
    fun `published artifact and authenticated snapshot reopen exactly`() = runPublisherTest {
        root, authority ->
        val prepared = FilterCompiler(root.resolve("compiler")).compile(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(1L, "a".repeat(64)),
                selectedSources = emptyList(),
                catalogRevision = "catalog-v1",
                catalogSha256 = "b".repeat(64),
                maxRules = 0,
                intentionalEmpty = true,
            ),
        )
        prepared.use {
            val state = directBootState(credentialUnlockRequired = false)
            val publisher = DeviceProtectedArtifactPublisher(root)

            assertTrue(publishThroughFreshCommit(authority, state) { publication ->
                publisher.publish(state, prepared, publication)
            })

            val snapshot = DeviceProtectedConfigurationReader(root, authority).readOrNull()
            assertNotNull(snapshot)
            assertEquals(prepared.sha256, snapshot?.artifact?.sha256)
            assertEquals(
                state,
                readVerifiedDeviceProtectedStateOrNull(root, authority),
            )
            val read = DirectBootArtifactReader.verifying { reference ->
                DirectBootStorageLayout.artifact(root, reference.sha256)
            }.read(checkNotNull(snapshot))
            assertTrue(read is DirectBootArtifactReadResult.Available)
            (read as DirectBootArtifactReadResult.Available).use {
                assertEquals(prepared.identity, it.artifact.identity)
            }

            assertTrue(DirectBootStorageLayout.artifact(root, prepared.sha256).delete())
            assertNotNull(DeviceProtectedConfigurationReader(root, authority).readOrNull())
            assertNull(readVerifiedDeviceProtectedStateOrNull(root, authority))
        }
    }

    @Test
    fun `custom DoH endpoint survives the Direct Boot snapshot round trip`() = runPublisherTest {
        root, authority ->
        val prepared = FilterCompiler(root.resolve("compiler")).compile(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(1L, "a".repeat(64)),
                selectedSources = emptyList(),
                catalogRevision = "catalog-v1",
                catalogSha256 = "b".repeat(64),
                maxRules = 0,
                intentionalEmpty = true,
            ),
        )
        prepared.use {
            val state = directBootState(credentialUnlockRequired = false).copy(
                useDeviceDns = false,
                customDohUrl = "https://dns.google/dns-query",
            )
            val publisher = DeviceProtectedArtifactPublisher(root)

            assertTrue(publishThroughFreshCommit(authority, state) { publication ->
                publisher.publish(state, prepared, publication)
            })

            assertEquals(
                state,
                readVerifiedDeviceProtectedStateOrNull(root, authority),
            )
            assertEquals(
                "https://dns.google/dns-query",
                readVerifiedDeviceProtectedStateOrNull(root, authority)?.customDohUrl,
            )
        }
    }

    @Test
    fun `custom DoT endpoint survives the Direct Boot snapshot round trip`() = runPublisherTest {
        root, authority ->
        val prepared = FilterCompiler(root.resolve("compiler")).compile(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(1L, "a".repeat(64)),
                selectedSources = emptyList(),
                catalogRevision = "catalog-v1",
                catalogSha256 = "b".repeat(64),
                maxRules = 0,
                intentionalEmpty = true,
            ),
        )
        prepared.use {
            val state = directBootState(credentialUnlockRequired = false).copy(
                useDeviceDns = false,
                customDotEndpoint = "tls://dns.google:853",
            )
            val publisher = DeviceProtectedArtifactPublisher(root)

            assertTrue(publishThroughFreshCommit(authority, state) { publication ->
                publisher.publish(state, prepared, publication)
            })

            assertEquals(
                state,
                readVerifiedDeviceProtectedStateOrNull(root, authority),
            )
            assertEquals(
                "tls://dns.google:853",
                readVerifiedDeviceProtectedStateOrNull(root, authority)?.customDotEndpoint,
            )
        }
    }

    @Test
    fun `credential unlock required state invalidates public publication`() = runPublisherTest {
        root, authority ->
        val prepared = FilterCompiler(root.resolve("compiler")).compile(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(1L, "a".repeat(64)),
                selectedSources = emptyList(),
                catalogRevision = "catalog-v1",
                catalogSha256 = "b".repeat(64),
                maxRules = 0,
                intentionalEmpty = true,
            ),
        )
        prepared.use {
            val publisher = DeviceProtectedArtifactPublisher(root)
            val publicState = directBootState(credentialUnlockRequired = false)
            assertTrue(publishThroughFreshCommit(authority, publicState) { publication ->
                publisher.publish(publicState, prepared, publication)
            })
            assertNotNull(DeviceProtectedConfigurationReader(root, authority).readOrNull())

            val privateState = directBootState(credentialUnlockRequired = true)
            assertFalse(publishThroughFreshCommit(authority, privateState) { publication ->
                publisher.publish(privateState, prepared, publication)
            })
            assertNull(DeviceProtectedConfigurationReader(root, authority).readOrNull())
        }
    }

    private suspend fun publishThroughFreshCommit(
        authority: DirectBootSnapshotFreshnessAuthority,
        state: DirectBootAppState,
        publish: (DirectBootCommittedSnapshotPublication) -> Boolean,
    ): Boolean {
        var published = false
        object : ProtectionIntentWriteCoordinator(authority) {
            override suspend fun commitAndReadBack(desired: Boolean): DirectBootAppState = state

            override suspend fun publishMirror(
                state: DirectBootAppState,
                committedPublication: DirectBootCommittedSnapshotPublication,
            ): DirectBootMirrorResult {
                published = publish(committedPublication)
                return DirectBootMirrorResult.MirrorUnavailable
            }
        }.write(state.protectionIntent)
        return published
    }

    private fun directBootState(credentialUnlockRequired: Boolean) = DirectBootAppState(
        onboardingCompleted = true,
        protectionIntent = false,
        useDeviceDns = true,
        useEncryptedFallback = true,
        encryptedFallbackId = "mullvad-doh",
        activeProfileId = "filter-balanced",
        configurationIdentity = ConfigurationIdentity(1L, "a".repeat(64)),
        credentialUnlockRequired = credentialUnlockRequired,
    )

    private fun runPublisherTest(
        block: suspend (java.io.File, DirectBootSnapshotFreshnessAuthority) -> Unit,
    ) = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("direct-boot-publisher-").toFile()
        try {
            block(root, TestFreshnessAuthority())
        } finally {
            root.deleteRecursively()
        }
    }

    private class TestFreshnessAuthority : DirectBootSnapshotFreshnessAuthority() {
        private var generation = 0
        private var current: DirectBootSnapshotAuthenticator? = null

        override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult {
            generation += 1
            val key = generation
            val authenticator = DirectBootSnapshotAuthenticator { commitment ->
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("$key:$commitment".toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
            current = authenticator
            return rotated(authenticator)
        }

        override fun currentAuthenticatorOrNull(): DirectBootSnapshotAuthenticator? = current
    }
}
