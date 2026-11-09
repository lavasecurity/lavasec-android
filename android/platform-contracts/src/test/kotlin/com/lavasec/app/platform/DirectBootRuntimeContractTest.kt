package com.lavasec.app.platform

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.ArtifactRuleCounts
import com.lavasec.app.foundation.filter.ArtifactSourceIdentity
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.BlocklistParser
import com.lavasec.app.foundation.filter.CatalogFilterSource
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactIdentity
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.foundation.filter.ManualDomainRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Modifier
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootRuntimeContractTest {
    @Test
    fun `authenticated mirror rejects coherent raw stamp bootstrap replacement at either endpoint`() {
        val primary = "sdns://AgAAAAAAAAAABzEuMS4xLjEAEmNsb3VkZmxhcmUtZG5zLmNvbQovZG5zLXF1ZXJ5hzEuMC4wLjEUMjYwNjo0NzAwOjQ3MDA6OjExMTE"
        val secondary = "sdns://AgAAAAAAAAAABzguOC44LjgACmRucy5nb29nbGUKL2Rucy1xdWVyeYc4LjguNC40FDIwMDE6NDg2MDo0ODYwOjo4ODg4"
        fun changedBootstrap(raw: String): String {
            val bytes = java.util.Base64.getUrlDecoder().decode(raw.substring(7))
            bytes[bytes.lastIndex] = '2'.code.toByte()
            return "sdns://" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        val identity = ConfigurationIdentity(7L, "configuration-fingerprint")
        val reference = FilterArtifactReference("a".repeat(64), artifactIdentity(identity))
        val entry = com.lavasec.app.foundation.resolver.ResolverSelectionEntry(
            com.lavasec.app.foundation.resolver.ResolverProviderId.CUSTOM,
            com.lavasec.app.foundation.resolver.CatalogResolverTransport.DOH,
            customDohUrl = primary, customSecondaryEndpoint = secondary)
        val row = com.lavasec.app.foundation.resolver.ResolverTier(entry)
        val state = DirectBootAppState.safeDefault().copy(onboardingCompleted = true, protectionIntent = true,
            useDeviceDns = false, useEncryptedFallback = true, encryptedFallbackId = "mullvad-doh",
            activeProfileId = "filter-balanced", configurationIdentity = identity, customDohUrl = primary,
            customSecondaryEndpoint = secondary, savedTiers = listOf(row))
        val authenticator = testAuthenticator()
        val sealed = DirectBootConfigurationSnapshot.sealForPublication(state, reference, committedPublication(authenticator, state))
        assertNotNull(DirectBootConfigurationSnapshot.restore(sealed, authenticator))
        val primaryReplacement = changedBootstrap(primary)
        val secondaryReplacement = changedBootstrap(secondary)
        assertEquals(com.lavasec.app.foundation.resolver.CustomResolverValidator.resolvedDohUrl(primary),
            com.lavasec.app.foundation.resolver.CustomResolverValidator.resolvedDohUrl(primaryReplacement))
        for (replacement in listOf(
            state.copy(customDohUrl = primaryReplacement, savedTiers = listOf(row.copy(entry = entry.copy(customDohUrl = primaryReplacement)))),
            state.copy(customSecondaryEndpoint = secondaryReplacement,
                savedTiers = listOf(row.copy(entry = entry.copy(customSecondaryEndpoint = secondaryReplacement)))),
        )) assertNull(DirectBootConfigurationSnapshot.restore(sealed.copy(state = replacement), authenticator))
    }

    @Test
    fun `authenticated mirror binds retained row order enablement names and secondary endpoints`() {
        val identity = ConfigurationIdentity(7L, "configuration-fingerprint")
        val reference = FilterArtifactReference("a".repeat(64), artifactIdentity(identity))
        val entry = com.lavasec.app.foundation.resolver.ResolverSelectionEntry(
            com.lavasec.app.foundation.resolver.ResolverProviderId.CUSTOM,
            com.lavasec.app.foundation.resolver.CatalogResolverTransport.DOQ,
            customDoqEndpoint = "doq://dns.example:8853", customName = "Primary",
            customSecondaryEndpoint = "doq://secondary.example:853")
        val rows = listOf(com.lavasec.app.foundation.resolver.ResolverTier(entry),
            com.lavasec.app.foundation.resolver.ResolverTier(
                com.lavasec.app.foundation.resolver.ResolverSelectionPresets.MULLVAD_DOH, true, "Fallback"))
        val state = DirectBootAppState.safeDefault().copy(onboardingCompleted = true, protectionIntent = true,
            useDeviceDns = false, encryptedFallbackId = "mullvad-doh", activeProfileId = "filter-balanced",
            configurationIdentity = identity, customDoqEndpoint = entry.customDoqEndpoint,
            customName = entry.customName, customSecondaryEndpoint = entry.customSecondaryEndpoint, savedTiers = rows)
        val authenticator = testAuthenticator()
        val sealed = DirectBootConfigurationSnapshot.sealForPublication(state, reference,
            committedPublication(authenticator, state))
        assertNotNull(DirectBootConfigurationSnapshot.restore(sealed, authenticator))
        for (changed in listOf(
            state.copy(savedTiers = rows.reversed()),
            state.copy(savedTiers = listOf(rows.first().copy(isEnabled = false), rows.last())),
            state.copy(savedTiers = listOf(rows.first(), rows.last().copy(retainedName = "Other label"))),
            state.copy(customSecondaryEndpoint = "doq://other.example:853"),
            state.copy(savedTiers = listOf(rows.first().copy(entry = entry.copy(
                customSecondaryEndpoint = "doq://other.example:853")), rows.last())),
        )) {
            assertNull(DirectBootConfigurationSnapshot.restore(sealed.copy(state = changed), authenticator))
        }
    }

    @Test
    fun `authenticated mirror rejects custom plain address splicing`() {
        val identity = ConfigurationIdentity(7L, "configuration-fingerprint")
        val reference = FilterArtifactReference("a".repeat(64), artifactIdentity(identity))
        val entry = com.lavasec.app.foundation.resolver.ResolverSelectionEntry(
            com.lavasec.app.foundation.resolver.ResolverProviderId.CUSTOM,
            com.lavasec.app.foundation.resolver.CatalogResolverTransport.PLAIN_DNS,
            customPlainEndpoint = "192.168.1.53", customSecondaryEndpoint = "192.168.1.54", customName = "Private resolver")
        val rows = listOf(com.lavasec.app.foundation.resolver.ResolverTier(entry))
        val state = DirectBootAppState.safeDefault().copy(onboardingCompleted = true, protectionIntent = true,
            useDeviceDns = true, useEncryptedFallback = false, activeProfileId = "filter-balanced",
            configurationIdentity = identity, savedTiers = rows)
        val authenticator = testAuthenticator()
        val sealed = DirectBootConfigurationSnapshot.sealForPublication(state, reference,
            committedPublication(authenticator, state))
        assertNotNull(DirectBootConfigurationSnapshot.restore(sealed, authenticator))
        for (changed in listOf(entry.copy(customPlainEndpoint = "192.168.1.55"),
            entry.copy(customSecondaryEndpoint = "192.168.1.55"))) {
            val splice = state.copy(savedTiers = listOf(rows.single().copy(entry = changed)))
            assertNull(DirectBootConfigurationSnapshot.restore(sealed.copy(state = splice), authenticator))
        }
    }

    @Test
    fun `authenticated snapshot rejects every behavior field splice and artifact substitution`() {
        val identity = ConfigurationIdentity(7, "configuration-fingerprint")
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(identity),
        )
        val state = DirectBootAppState.safeDefault().copy(
            onboardingCompleted = true,
            protectionIntent = true,
            useDeviceDns = true,
            useEncryptedFallback = true,
            encryptedFallbackId = "mullvad-doh",
            activeProfileId = "filter-balanced",
            configurationIdentity = identity,
        )
        val authenticator = testAuthenticator()
        val persisted = DirectBootConfigurationSnapshot.sealForPublication(
            state,
            reference,
            committedPublication(authenticator, state),
        )

        assertFalse(
            DirectBootConfigurationSnapshot::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .any { Modifier.isPublic(it.modifiers) },
        )
        assertNotNull(DirectBootConfigurationSnapshot.restore(persisted, authenticator))
        listOf(
            state.copy(onboardingCompleted = false),
            state.copy(protectionIntent = false),
            state.copy(activeProfileId = "filter-essential"),
            state.copy(useDeviceDns = false),
            state.copy(useEncryptedFallback = false),
            state.copy(encryptedFallbackId = "quad9-secure-doh"),
        ).forEach { spliced ->
            assertNull(
                DirectBootConfigurationSnapshot.restore(
                    persisted.copy(state = spliced),
                    authenticator,
                ),
            )
        }
        assertNull(
            DirectBootConfigurationSnapshot.restore(
                persisted.copy(
                    artifact = FilterArtifactReference(
                        sha256 = "b".repeat(64),
                        artifactIdentity = reference.artifactIdentity,
                    ),
                ),
                authenticator,
            ),
        )
        assertNull(
            DirectBootConfigurationSnapshot.restore(
                persisted.copy(authenticationTag = "0".repeat(64)),
                authenticator,
            ),
        )
        assertNull(
            DirectBootConfigurationSnapshot.restore(
                persisted.copy(authenticationTag = "truncated"),
                authenticator,
            ),
        )
        assertNull(
            DirectBootConfigurationSnapshot.restore(
                persisted,
                unavailableFreshnessAuthority(),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            val unavailableAuthority = unavailableFreshnessAuthority()
            DirectBootConfigurationSnapshot.sealForPublication(
                state,
                reference,
                committedPublication(unavailableAuthority, state),
            )
        }
    }

    @Test
    fun `authenticated protection intent changes do not require artifact recompilation`() {
        val identity = ConfigurationIdentity(7, "configuration-fingerprint")
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(identity),
        )
        val base = DirectBootAppState.safeDefault().copy(
            onboardingCompleted = true,
            useDeviceDns = true,
            useEncryptedFallback = true,
            encryptedFallbackId = "mullvad-doh",
            activeProfileId = "filter-balanced",
            configurationIdentity = identity,
        )
        val authenticator = testAuthenticator()
        val stoppedState = base.copy(protectionIntent = false)
        val stopped = DirectBootConfigurationSnapshot.sealForPublication(
            stoppedState,
            reference,
            committedPublication(authenticator, stoppedState),
        )
        assertNotNull(DirectBootConfigurationSnapshot.restore(stopped, authenticator))

        val startedState = base.copy(protectionIntent = true)
        val started = DirectBootConfigurationSnapshot.sealForPublication(
            startedState,
            reference,
            committedPublication(authenticator, startedState),
        )

        assertNull(DirectBootConfigurationSnapshot.restore(stopped, authenticator))
        assertNotNull(DirectBootConfigurationSnapshot.restore(started, authenticator))
        assertEquals(
            reference,
            DirectBootConfigurationSnapshot.restore(started, authenticator)?.artifact,
        )
    }

    @Test
    fun `fresh publication rejects every older authenticated protection intent snapshot`() {
        val identity = ConfigurationIdentity(7, "configuration-fingerprint")
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(identity),
        )
        val base = DirectBootAppState.safeDefault().copy(
            onboardingCompleted = true,
            useDeviceDns = true,
            useEncryptedFallback = true,
            encryptedFallbackId = "mullvad-doh",
            activeProfileId = "filter-balanced",
            configurationIdentity = identity,
        )
        val authority = RotatingTestFreshnessAuthority()
        val stoppedState = base.copy(protectionIntent = false)
        val stopped = DirectBootConfigurationSnapshot.sealForPublication(
            stoppedState,
            reference,
            committedPublication(authority, stoppedState),
        )

        assertNotNull(DirectBootConfigurationSnapshot.restore(stopped, authority))

        val startedState = base.copy(protectionIntent = true)
        val started = DirectBootConfigurationSnapshot.sealForPublication(
            startedState,
            reference,
            committedPublication(authority, startedState),
        )

        assertNull(DirectBootConfigurationSnapshot.restore(stopped, authority))
        assertNotNull(DirectBootConfigurationSnapshot.restore(started, authority))

        val stoppedAgainState = base.copy(protectionIntent = false)
        val stoppedAgain = DirectBootConfigurationSnapshot.sealForPublication(
            stoppedAgainState,
            reference,
            committedPublication(authority, stoppedAgainState),
        )

        assertNull(DirectBootConfigurationSnapshot.restore(stopped, authority))
        assertNull(DirectBootConfigurationSnapshot.restore(started, authority))
        assertFalse(
            requireNotNull(DirectBootConfigurationSnapshot.restore(stoppedAgain, authority))
                .state.protectionIntent,
        )
    }

    @Test
    fun `failed replacement publication leaves an older persisted snapshot inadmissible`() =
        runTest {
        val identity = ConfigurationIdentity(7, "configuration-fingerprint")
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(identity),
        )
        val base = DirectBootAppState.safeDefault().copy(
            onboardingCompleted = true,
            protectionIntent = true,
            configurationIdentity = identity,
        )
        val authority = RotatingTestFreshnessAuthority()
        val persistedOld = DirectBootConfigurationSnapshot.sealForPublication(
            base,
            reference,
            committedPublication(authority, base),
        )
        assertNotNull(DirectBootConfigurationSnapshot.restore(persistedOld, authority))

        // Rotation succeeds, but the replacement envelope is discarded as if its durable write failed.
        val replacementState = base.copy(protectionIntent = false)
        DirectBootConfigurationSnapshot.sealForPublication(
            replacementState,
            reference,
            committedPublication(authority, replacementState),
        )
        assertNull(DirectBootConfigurationSnapshot.restore(persistedOld, authority))

        // A failure on the very next rotation invalidates a currently valid old envelope without
        // invoking the authoritative commit callback or misreporting a pre-invalidation rejection.
        val failingAuthority = RotatingTestFreshnessAuthority()
        val currentlyValid = DirectBootConfigurationSnapshot.sealForPublication(
            base,
            reference,
            committedPublication(failingAuthority, base),
        )
        assertNotNull(DirectBootConfigurationSnapshot.restore(currentlyValid, failingAuthority))
        var authoritativeCommits = 0
        val coordinator = ProtectionIntentWriteCoordinator(
            freshnessAuthority = failingAuthority,
            authoritativeCommit = ProtectionIntentAuthoritativeCommit {
                authoritativeCommits += 1
                base.copy(protectionIntent = false)
            },
            mirrorPublisher = DirectBootProtectionIntentMirrorPublisher { _, _ ->
                error("publisher must not run without a replacement key")
            },
        )
        failingAuthority.failAfterInvalidation = true

        val unavailable = coordinator.write(desired = false)

        assertTrue(
            unavailable is
                ProtectionIntentWriteResult.AuthoritativeUnchangedMirrorUnavailable,
        )
        assertEquals(0, authoritativeCommits)
        assertNull(DirectBootConfigurationSnapshot.restore(currentlyValid, failingAuthority))
    }

    @Test
    fun `current keystore key admits the latest snapshot after authority recreation`() {
        val identity = ConfigurationIdentity(7, "configuration-fingerprint")
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(identity),
        )
        val state = DirectBootAppState.safeDefault().copy(
            onboardingCompleted = true,
            protectionIntent = true,
            configurationIdentity = identity,
        )
        var currentAuthenticator: DirectBootSnapshotAuthenticator? = null
        var keyGeneration = 0L
        fun recreatedAuthority(): DirectBootSnapshotFreshnessAuthority =
            object : DirectBootSnapshotFreshnessAuthority() {
                override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult {
                    currentAuthenticator = null
                    keyGeneration += 1
                    val key = SecretKeySpec(
                        "restart-test-key-$keyGeneration".encodeToByteArray(),
                        "HmacSHA256",
                    )
                    val authenticator = DirectBootSnapshotAuthenticator { commitmentSha256 ->
                        Mac.getInstance("HmacSHA256").run {
                            init(key)
                            doFinal(commitmentSha256.encodeToByteArray()).joinToString("") {
                                "%02x".format(it)
                            }
                        }
                    }
                    currentAuthenticator = authenticator
                    return rotated(authenticator)
                }

                override fun currentAuthenticatorOrNull(): DirectBootSnapshotAuthenticator? =
                    currentAuthenticator
            }

        val writerAuthority = recreatedAuthority()
        val persisted = DirectBootConfigurationSnapshot.sealForPublication(
            state,
            reference,
            committedPublication(writerAuthority, state),
        )

        assertNotNull(
            DirectBootConfigurationSnapshot.restore(
                persisted,
                recreatedAuthority(),
            ),
        )
    }

    @Test
    fun `one authority serializes separate coordinators through final mirror publication`() =
        runTest {
            val identity = ConfigurationIdentity(7, "configuration-fingerprint")
            val reference = FilterArtifactReference(
                sha256 = "a".repeat(64),
                artifactIdentity = artifactIdentity(identity),
            )
            val base = DirectBootAppState.safeDefault().copy(
                onboardingCompleted = true,
                configurationIdentity = identity,
            )
            val authority = RotatingTestFreshnessAuthority()
            val firstCommitEntered = CompletableDeferred<Unit>()
            val releaseFirstCommit = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val published = mutableListOf<PersistedDirectBootConfigurationSnapshot>()

            fun coordinator(
                name: String,
                pauseCommit: Boolean,
            ): ProtectionIntentWriteCoordinator = ProtectionIntentWriteCoordinator(
                freshnessAuthority = authority,
                authoritativeCommit = ProtectionIntentAuthoritativeCommit { desired ->
                    order += "commit-$name"
                    if (pauseCommit) {
                        firstCommitEntered.complete(Unit)
                        releaseFirstCommit.await()
                    }
                    base.copy(protectionIntent = desired)
                },
                mirrorPublisher = DirectBootProtectionIntentMirrorPublisher {
                        state,
                        committedPublication,
                    ->
                    order += "publish-$name"
                    published += DirectBootConfigurationSnapshot.sealForPublication(
                        state,
                        reference,
                        committedPublication,
                    )
                    DirectBootMirrorResult.MirrorUnavailable
                },
            )

            val first = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator("first", pauseCommit = true).write(desired = true)
            }
            firstCommitEntered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator("second", pauseCommit = false).write(desired = false)
            }

            assertEquals(listOf("commit-first"), order)
            releaseFirstCommit.complete(Unit)
            assertTrue(first.await() is ProtectionIntentWriteResult.AuthoritativeCommitted)
            assertTrue(second.await() is ProtectionIntentWriteResult.AuthoritativeCommitted)
            assertEquals(
                listOf("commit-first", "publish-first", "commit-second", "publish-second"),
                order,
            )
            assertEquals(2, published.size)
            assertNull(DirectBootConfigurationSnapshot.restore(published.first(), authority))
            assertFalse(
                requireNotNull(
                    DirectBootConfigurationSnapshot.restore(published.last(), authority),
                ).state.protectionIntent,
            )
        }

    @Test
    fun `authoritative protection commit requires prior freshness invalidation`() = runTest {
        val identity = ConfigurationIdentity(7, "configuration-fingerprint")
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(identity),
        )
        val state = DirectBootAppState.safeDefault().copy(
            onboardingCompleted = true,
            protectionIntent = true,
            configurationIdentity = identity,
        )
        val authority = RotatingTestFreshnessAuthority()
        val persistedOn = DirectBootConfigurationSnapshot.sealForPublication(
            state,
            reference,
            committedPublication(authority, state),
        )
        val committedOffState = state.copy(protectionIntent = false)
        var authoritativeCommits = 0
        val coordinator = ProtectionIntentWriteCoordinator(
            freshnessAuthority = authority,
            authoritativeCommit = ProtectionIntentAuthoritativeCommit {
                authoritativeCommits += 1
                committedOffState
            },
            mirrorPublisher = DirectBootProtectionIntentMirrorPublisher { _, _ ->
                throw IllegalStateException("simulated atomic mirror write failure")
            },
        )

        authority.failBeforeInvalidation = true
        val rejected = coordinator.write(desired = false)

        assertTrue(rejected is ProtectionIntentWriteResult.RejectedBeforeAuthoritativeCommit)
        assertEquals(0, authoritativeCommits)
        assertNotNull(DirectBootConfigurationSnapshot.restore(persistedOn, authority))

        val committed = coordinator.write(desired = false)

        // The authoritative OFF state can now commit. A later mirror write failure stays fail closed.
        assertTrue(committed is ProtectionIntentWriteResult.AuthoritativeCommitted)
        committed as ProtectionIntentWriteResult.AuthoritativeCommitted
        assertFalse(committed.desired)
        assertSame(DirectBootMirrorResult.MirrorUnavailable, committed.mirror)
        assertEquals(1, authoritativeCommits)
        assertNull(DirectBootConfigurationSnapshot.restore(persistedOn, authority))
        assertFalse(
            ProtectionIntentWriteResult.AuthoritativeCommitted::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .any { Modifier.isPublic(it.modifiers) },
        )
    }

    @Test
    fun `publisher cancellation propagates after invalidating the old mirror`() = runTest {
        val identity = ConfigurationIdentity(7, "configuration-fingerprint")
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(identity),
        )
        val onState = DirectBootAppState.safeDefault().copy(
            onboardingCompleted = true,
            protectionIntent = true,
            configurationIdentity = identity,
        )
        val authority = RotatingTestFreshnessAuthority()
        val persistedOn = DirectBootConfigurationSnapshot.sealForPublication(
            onState,
            reference,
            committedPublication(authority, onState),
        )
        var authoritativeCommitted = false
        val coordinator = ProtectionIntentWriteCoordinator(
            freshnessAuthority = authority,
            authoritativeCommit = ProtectionIntentAuthoritativeCommit {
                authoritativeCommitted = true
                onState.copy(protectionIntent = false)
            },
            mirrorPublisher = DirectBootProtectionIntentMirrorPublisher { _, _ ->
                throw CancellationException("caller cancelled")
            },
        )

        var cancellation: CancellationException? = null
        try {
            coordinator.write(desired = false)
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertNotNull(cancellation)
        assertTrue(authoritativeCommitted)
        assertNull(DirectBootConfigurationSnapshot.restore(persistedOn, authority))
    }

    @Test
    fun `publication epoch is one shot and reusable authenticator cannot bypass freshness`() {
        val identity = ConfigurationIdentity(7, "configuration-fingerprint")
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(identity),
        )
        val state = DirectBootAppState.safeDefault().copy(
            onboardingCompleted = true,
            protectionIntent = true,
            configurationIdentity = identity,
        )
        val authority = RotatingTestFreshnessAuthority()
        val publicationEpoch = requireNotNull(authority.beginPublicationEpochOrNullForTest())
        assertTrue(authority.claimAuthoritativeCommit(publicationEpoch, state.protectionIntent))
        val committedPublication = requireNotNull(
            authority.confirmAuthoritativeCommit(publicationEpoch, state),
        )
        val persisted = DirectBootConfigurationSnapshot.sealForPublication(
            state,
            reference,
            committedPublication,
        )

        assertThrows(IllegalArgumentException::class.java) {
            DirectBootConfigurationSnapshot.sealForPublication(
                state,
                reference,
                committedPublication,
            )
        }
        assertFalse(authority.claimAuthoritativeCommit(publicationEpoch, desired = false))
        assertNotNull(DirectBootConfigurationSnapshot.restore(persisted, authority))

        val superseded = requireNotNull(authority.beginPublicationEpochOrNullForTest())
        val current = requireNotNull(authority.beginPublicationEpochOrNullForTest())
        assertFalse(authority.claimAuthoritativeCommit(superseded, desired = false))
        assertTrue(authority.claimAuthoritativeCommit(current, desired = false))

        val mismatchAuthority = RotatingTestFreshnessAuthority()
        val mismatchEpoch = requireNotNull(
            mismatchAuthority.beginPublicationEpochOrNullForTest(),
        )
        assertNull(mismatchAuthority.confirmAuthoritativeCommit(mismatchEpoch, state))
        assertTrue(mismatchAuthority.claimAuthoritativeCommit(mismatchEpoch, desired = true))
        assertNull(
            mismatchAuthority.confirmAuthoritativeCommit(
                mismatchEpoch,
                state.copy(protectionIntent = false),
            ),
        )
        val exactPublication = requireNotNull(
            mismatchAuthority.confirmAuthoritativeCommit(mismatchEpoch, state),
        )
        assertThrows(IllegalArgumentException::class.java) {
            DirectBootConfigurationSnapshot.sealForPublication(
                state.copy(useDeviceDns = !state.useDeviceDns),
                reference,
                exactPublication,
            )
        }
        assertNotNull(
            DirectBootConfigurationSnapshot.sealForPublication(
                state,
                reference,
                exactPublication,
            ),
        )

        val supersededAfterClaim = requireNotNull(
            mismatchAuthority.beginPublicationEpochOrNullForTest(),
        )
        assertTrue(
            mismatchAuthority.claimAuthoritativeCommit(
                supersededAfterClaim,
                desired = true,
            ),
        )
        requireNotNull(mismatchAuthority.beginPublicationEpochOrNullForTest())
        assertNull(
            mismatchAuthority.confirmAuthoritativeCommit(supersededAfterClaim, state),
        )

        val confirmedThenSuperseded = requireNotNull(
            mismatchAuthority.beginPublicationEpochOrNullForTest(),
        )
        assertTrue(
            mismatchAuthority.claimAuthoritativeCommit(
                confirmedThenSuperseded,
                desired = true,
            ),
        )
        val stalePublication = requireNotNull(
            mismatchAuthority.confirmAuthoritativeCommit(confirmedThenSuperseded, state),
        )
        requireNotNull(mismatchAuthority.beginPublicationEpochOrNullForTest())
        assertThrows(IllegalArgumentException::class.java) {
            DirectBootConfigurationSnapshot.sealForPublication(
                state,
                reference,
                stalePublication,
            )
        }

        assertFalse(
            DirectBootSnapshotPublicationEpoch::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .any { Modifier.isPublic(it.modifiers) },
        )
        assertFalse(
            DirectBootCommittedSnapshotPublication::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .any { Modifier.isPublic(it.modifiers) },
        )
        assertFalse(
            DirectBootSnapshotFreshnessAuthority::class.java.declaredMethods.any {
                Modifier.isPublic(it.modifiers) &&
                    it.returnType == String::class.java
            },
        )

        val publicSealers = DirectBootConfigurationSnapshot.Companion::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("seal") }
        assertEquals(listOf("sealForPublication"), publicSealers.map { it.name }.distinct())
        assertEquals(
            listOf(
                DirectBootAppState::class.java,
                FilterArtifactReference::class.java,
                DirectBootCommittedSnapshotPublication::class.java,
            ),
            publicSealers.single { !it.isSynthetic }.parameterTypes.toList(),
        )
    }

    @Test
    fun `artifact reader structurally requires admitted snapshot and never opens a tampered envelope`() {
        val identity = ConfigurationIdentity(7, "configuration-fingerprint")
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(identity),
        )
        val state = DirectBootAppState.safeDefault().copy(
            onboardingCompleted = true,
            protectionIntent = true,
            configurationIdentity = identity,
        )
        val authenticator = testAuthenticator()
        val persisted = DirectBootConfigurationSnapshot.sealForPublication(
            state,
            reference,
            committedPublication(authenticator, state),
        )
        var fileLookups = 0
        DirectBootArtifactReader.verifying {
            fileLookups += 1
            error("tampered state must not reach artifact lookup")
        }

        assertNull(
            DirectBootConfigurationSnapshot.restore(
                persisted.copy(state = state.copy(protectionIntent = false)),
                authenticator,
            ),
        )
        assertEquals(0, fileLookups)
        val readMethod = DirectBootArtifactReader::class.java.declaredMethods.single {
            it.name == "read" && !it.isSynthetic
        }
        assertEquals(
            listOf(DirectBootConfigurationSnapshot::class.java),
            readMethod.parameterTypes.toList(),
        )
    }

    @Test
    fun `verifying reader never converts cancellation into artifact corruption`() {
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(
                ConfigurationIdentity(7, "configuration-fingerprint"),
            ),
        )
        val reader = DirectBootArtifactReader.verifying {
            throw CancellationException("cancel file lookup")
        }
        val snapshot = admittedSnapshot(reference)

        val cancellation = assertThrows(CancellationException::class.java) {
            reader.read(snapshot)
        }

        assertEquals("cancel file lookup", cancellation.message)
    }

    @Test
    fun `artifact reference is untrusted immutable expected identity`() {
        val selectedSources = mutableListOf(
            ArtifactSourceIdentity("source-a", "d".repeat(64), BlocklistFormat.HOSTS),
        )
        val reference = FilterArtifactReference(
            sha256 = "A".repeat(64),
            artifactIdentity = artifactIdentity(
                ConfigurationIdentity(7, "configuration-fingerprint"),
                selectedSources,
            ),
        )

        selectedSources.clear()

        assertEquals("a".repeat(64), reference.sha256)
        assertEquals(listOf("source-a"), reference.artifactIdentity.selectedSources.map { it.id })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (reference.artifactIdentity.selectedSources as MutableList<ArtifactSourceIdentity>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) {
            FilterArtifactReference("not-a-sha256", reference.artifactIdentity)
        }
    }

    @Test
    fun `Direct Boot snapshot rejects mismatched identity and credential-only state`() {
        val identity = ConfigurationIdentity(7, "configuration-fingerprint")
        val reference = FilterArtifactReference(
            sha256 = "a".repeat(64),
            artifactIdentity = artifactIdentity(identity),
        )
        val unlocked = DirectBootAppState.safeDefault().copy(
            onboardingCompleted = true,
            protectionIntent = true,
            configurationIdentity = identity,
        )
        val authenticator = testAuthenticator()

        val persisted = DirectBootConfigurationSnapshot.sealForPublication(
            unlocked,
            reference,
            committedPublication(authenticator, unlocked),
        )
        assertSame(
            reference,
            DirectBootConfigurationSnapshot.restore(persisted, authenticator)?.artifact,
        )
        assertThrows(IllegalArgumentException::class.java) {
            val mismatched = unlocked.copy(
                configurationIdentity = ConfigurationIdentity(8, identity.fingerprint),
            )
            DirectBootConfigurationSnapshot.sealForPublication(
                mismatched,
                reference,
                committedPublication(authenticator, mismatched),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val credentialOnly = unlocked.copy(credentialUnlockRequired = true)
            DirectBootConfigurationSnapshot.sealForPublication(
                credentialOnly,
                reference,
                committedPublication(authenticator, credentialOnly),
            )
        }
    }

    @Test
    fun `successful Direct Boot artifact open mints its witness and owns close`() = runTest {
        val fixture = RealFilterArtifactFixture.create(ConfigurationIdentity(7, "configuration-fingerprint"))
        try {
            assertFalse(
                VerifiedFilterArtifact::class.java.declaredConstructors.filterNot { it.isSynthetic }.any {
                    Modifier.isPublic(it.modifiers)
                },
            )
            assertFalse(
                DirectBootArtifactReadResult.Available::class.java.declaredConstructors
                    .filterNot { it.isSynthetic }
                    .any { Modifier.isPublic(it.modifiers) },
            )

            val result = fixture.read()
            assertTrue(result is DirectBootArtifactReadResult.Available)
            val available = result as DirectBootArtifactReadResult.Available

            assertTrue(available.artifact.channelIsOpenForTesting)
            assertEquals(fixture.reference.sha256, available.verifiedArtifact.sha256)
            assertEquals(
                fixture.reference.artifactIdentity.configuration.generation,
                available.state.configurationIdentity?.generation,
            )
            assertEquals(
                fixture.reference.artifactIdentity,
                available.verifiedArtifact.artifactIdentity,
            )
            assertEquals(available.artifact.identity, available.verifiedArtifact.artifactIdentity)

            available.close()
            assertFalse(available.artifact.channelIsOpenForTesting)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `authenticated Direct Boot snapshot mints an exact runtime lease without private rules`() =
        runTest {
            val fixture = RealFilterArtifactFixture.create(
                ConfigurationIdentity(17, "configuration-fingerprint"),
            )
            val snapshot = admittedSnapshot(fixture.reference)
            val opened = FilterArtifact.open(
                fixture.file,
                fixture.reference.sha256,
                fixture.reference.artifactIdentity,
            )
            try {
                val lease = requireNotNull(
                    VerifiedFilterRuntimeLease.takeDirectBootOpenedOrNull(snapshot, opened),
                )

                assertEquals(null, lease.confirmedState)
                assertEquals(snapshot.state.configurationIdentity, lease.configurationIdentity)
                assertEquals(fixture.reference.sha256, lease.artifactDigest)
                assertTrue(lease.withDecisions { Unit } is FilterRuntimeLeaseRead.Available<*>)
                lease.close()
                assertFalse(opened.channelIsOpenForTesting)
            } finally {
                opened.close()
                fixture.close()
            }
        }

    @Test
    fun `missing bytes wrong digest and full identity mismatch never yield verified state`() = runTest {
        val fixture = RealFilterArtifactFixture.create(ConfigurationIdentity(7, "configuration-fingerprint"))
        val missing = Files.createTempDirectory("missing-artifact-").resolve("absent.lsfa").toFile()
        try {
            assertUnavailable(
                ProtectionFailure.ArtifactMissing,
                fixture.read(file = missing),
            )
            assertUnavailable(
                ProtectionFailure.ArtifactCorrupt,
                fixture.read(
                    reference = FilterArtifactReference(
                        sha256 = "f".repeat(64),
                        artifactIdentity = fixture.reference.artifactIdentity,
                    ),
                ),
            )
            assertUnavailable(
                ProtectionFailure.ArtifactMismatched,
                fixture.read(
                    reference = FilterArtifactReference(
                        sha256 = fixture.reference.sha256,
                        artifactIdentity = fixture.reference.artifactIdentity.copy(
                            catalogRevision = "forged-revision",
                        ),
                    ),
                ),
            )
        } finally {
            missing.parentFile?.deleteRecursively()
            fixture.close()
        }
    }

    @Test
    fun `only credential publication can represent credential unlock required state`() = runTest {
        val base = seededConfiguration()
        val active = requireNotNull(base.profile(base.activeProfileId))
        val inactive = base.profiles.first { it.id != active.id }
        val activeManualConfiguration = base.withProfile(
            active.withManualRules(
                ManualDomainRules(blockedDomains = setOf("manual.example")),
            ),
        )
        val inactiveManualConfiguration = base.withProfile(
            inactive.withManualRules(
                ManualDomainRules(blockedDomains = setOf("inactive-private.example")),
            ),
        )
        val activeFixture =
            RealFilterArtifactFixture.createForConfiguration(activeManualConfiguration, 7L)
        val inactiveFixture =
            RealFilterArtifactFixture.createForConfiguration(inactiveManualConfiguration, 7L)
        try {
            val witness = activeFixture.verifiedArtifact()
            val lockedManualState = confirmedState(
                configuration = activeManualConfiguration,
                credentialUnlockRequired = true,
                verifiedArtifact = witness,
            )

            assertThrows(IllegalArgumentException::class.java) {
                DirectBootMirrorResult.deviceProtectedPublished(lockedManualState)
            }
            val credential = DirectBootMirrorResult.credentialRuntimePublished(lockedManualState)

            assertSame(witness, credential.verifiedArtifact)
            assertTrue(credential.isExactRuntimePublished)
            val inactiveManualState = confirmedState(
                configuration = inactiveManualConfiguration,
                credentialUnlockRequired = true,
                verifiedArtifact = inactiveFixture.verifiedArtifact(),
            )
            assertThrows(IllegalArgumentException::class.java) {
                DirectBootMirrorResult.deviceProtectedPublished(inactiveManualState)
            }
            assertNull(DirectBootMirrorResult.MirrorUnavailable.verifiedArtifact)
            assertFalse(DirectBootMirrorResult.MirrorUnavailable.isExactRuntimePublished)
        } finally {
            inactiveFixture.close()
            activeFixture.close()
        }
    }

    private fun confirmedState(
        configuration: FilterLibraryDraft,
        credentialUnlockRequired: Boolean,
        verifiedArtifact: VerifiedFilterArtifact,
    ): ConfirmedFilterState {
        val artifactConfiguration = verifiedArtifact.artifactIdentity.configuration
        val configurationIdentity = ConfigurationIdentity(
            artifactConfiguration.generation,
            artifactConfiguration.fingerprint,
        )
        return ConfirmedFilterState(
            configuration = configuration,
            configurationIdentity = configurationIdentity,
            credentialUnlockRequired = credentialUnlockRequired,
            verifiedArtifact = verifiedArtifact,
        )
    }

    private fun seededConfiguration(): FilterLibraryDraft =
        FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(
            listOf(
                CatalogFilterSource("security", FilterSourceCategory.SECURITY, true),
                CatalogFilterSource("multi", FilterSourceCategory.MULTI_PURPOSE, true),
                CatalogFilterSource("ads", FilterSourceCategory.ADS_TRACKING, false),
            ),
        ))

    private fun artifactIdentity(
        configuration: ConfigurationIdentity,
        selectedSources: List<ArtifactSourceIdentity> = emptyList(),
    ) = FilterArtifactIdentity(
        configuration = ArtifactConfigurationIdentity(configuration.generation, configuration.fingerprint),
        selectedSources = selectedSources,
        catalogRevision = "catalog-revision",
        catalogSha256 = "b".repeat(64),
        artifactSchema = FilterArtifact.ARTIFACT_SCHEMA,
        pointerSchema = FilterArtifact.POINTER_SCHEMA,
        parserRulesVersion = BlocklistParser.RULES_VERSION,
        ruleCounts = ArtifactRuleCounts(0, 0, 0, 0, 0, 0),
        ruleTablesSha256 = "c".repeat(64),
        compileInputsSha256 = "d".repeat(64),
        intentionalEmpty = true,
    )

    private fun assertUnavailable(
        expected: ProtectionFailure,
        result: DirectBootArtifactReadResult,
    ) {
        assertTrue(result is DirectBootArtifactReadResult.Unavailable)
        assertEquals(expected, (result as DirectBootArtifactReadResult.Unavailable).failure)
    }

    private fun testAuthenticator(): RotatingTestFreshnessAuthority =
        RotatingTestFreshnessAuthority()

    private class RotatingTestFreshnessAuthority : DirectBootSnapshotFreshnessAuthority() {
        private var generation = 0L
        private var currentAuthenticator: DirectBootSnapshotAuthenticator? = null
        var failBeforeInvalidation = false
        var failAfterInvalidation = false

        override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult {
            if (failBeforeInvalidation) {
                failBeforeInvalidation = false
                return DirectBootSnapshotKeyRotationResult.RejectedBeforeInvalidation
            }
            currentAuthenticator = null
            if (failAfterInvalidation) {
                failAfterInvalidation = false
                return DirectBootSnapshotKeyRotationResult.InvalidatedWithoutReplacement
            }
            generation += 1
            val key = SecretKeySpec(
                "test-direct-boot-key-$generation".encodeToByteArray(),
                "HmacSHA256",
            )
            val authenticator = DirectBootSnapshotAuthenticator { commitmentSha256 ->
                Mac.getInstance("HmacSHA256").run {
                    init(key)
                    doFinal(commitmentSha256.encodeToByteArray()).joinToString("") {
                        "%02x".format(it)
                    }
                }
            }
            currentAuthenticator = authenticator
            return rotated(authenticator)
        }

        override fun currentAuthenticatorOrNull(): DirectBootSnapshotAuthenticator? =
            currentAuthenticator
    }

    private fun admittedSnapshot(
        reference: FilterArtifactReference,
    ): DirectBootConfigurationSnapshot {
        val artifactConfiguration = reference.artifactIdentity.configuration
        val state = DirectBootAppState.safeDefault().copy(
            configurationIdentity = ConfigurationIdentity(
                artifactConfiguration.generation,
                artifactConfiguration.fingerprint,
            ),
        )
        val authenticator = testAuthenticator()
        return requireNotNull(
            DirectBootConfigurationSnapshot.restore(
                DirectBootConfigurationSnapshot.sealForPublication(
                    state,
                    reference,
                    committedPublication(authenticator, state),
                ),
                authenticator,
            ),
        )
    }

    private fun committedPublication(
        authority: DirectBootSnapshotFreshnessAuthority,
        state: DirectBootAppState,
    ): DirectBootCommittedSnapshotPublication {
        val epoch = requireNotNull(authority.beginPublicationEpochOrNullForTest())
        check(authority.claimAuthoritativeCommit(epoch, state.protectionIntent))
        return requireNotNull(authority.confirmAuthoritativeCommit(epoch, state))
    }

    private fun DirectBootSnapshotFreshnessAuthority
        .beginPublicationEpochOrNullForTest(): DirectBootSnapshotPublicationEpoch? =
        when (val start = beginPublication()) {
            DirectBootSnapshotPublicationStart.RejectedBeforeInvalidation,
            DirectBootSnapshotPublicationStart.InvalidatedWithoutReplacement,
            -> null
            is DirectBootSnapshotPublicationStart.Ready -> start.epoch
        }

    private fun unavailableFreshnessAuthority(): DirectBootSnapshotFreshnessAuthority {
        val unavailableAuthenticator = DirectBootSnapshotAuthenticator { null }
        return object : DirectBootSnapshotFreshnessAuthority() {
            override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult =
                rotated(unavailableAuthenticator)

            override fun currentAuthenticatorOrNull(): DirectBootSnapshotAuthenticator =
                unavailableAuthenticator
        }
    }
}
