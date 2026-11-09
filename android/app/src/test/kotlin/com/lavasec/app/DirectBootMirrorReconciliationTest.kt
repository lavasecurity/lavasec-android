package com.lavasec.app

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.ArtifactRuleCounts
import com.lavasec.app.foundation.filter.BlocklistParser
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactIdentity
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.DirectBootAppState
import com.lavasec.app.platform.FilterArtifactReference
import com.lavasec.app.platform.PersistedAppState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootMirrorReconciliationTest {
    @Test
    fun `a mirror missing or reordering retained DNS rows requires fresh publication`() = runTest {
        val rows = listOf(com.lavasec.app.foundation.resolver.ResolverTier(
            com.lavasec.app.foundation.resolver.ResolverSelectionPresets.MULLVAD_DOH, true, "Private"),
            com.lavasec.app.foundation.resolver.ResolverTier(
                com.lavasec.app.foundation.resolver.ResolverSelectionPresets.DEVICE_PLAIN, true, "Network"))
        val resolver = checkNotNull(com.lavasec.app.foundation.resolver.ResolverSelection(rows).toFilterResolverPlan()).configuration
        val persisted = persistedState().let { it.copy(configuration = it.configuration!!.copy(resolver = resolver)) }
        val artifact = artifactReference("b", "credential")
        val state = directBootState(persisted)
        assertFalse(requiresFreshDeviceMirrorPublication(persisted, artifact) { VerifiedDeviceMirror(state, artifact) })
        for (changed in listOf(state.copy(savedTiers = null), state.copy(savedTiers = rows.reversed()),
            state.copy(savedTiers = listOf(rows.first(), rows.last().copy(retainedName = "Other"))))) {
            assertTrue(requiresFreshDeviceMirrorPublication(persisted, artifact) { VerifiedDeviceMirror(changed, artifact) })
        }
    }

    @Test
    fun `coherent verified mirror does not require fresh key publication`() = runTest {
        val persisted = persistedState()
        val credentialArtifact = artifactReference("b", "credential")
        var reads = 0

        val required = requiresFreshDeviceMirrorPublication(persisted, credentialArtifact) {
            reads += 1
            VerifiedDeviceMirror(directBootState(persisted), credentialArtifact)
        }

        assertFalse(required)
        assertTrue(reads == 1)
    }

    @Test
    fun `missing or mismatched verified mirror requires fresh publication`() = runTest {
        val persisted = persistedState()
        val credentialArtifact = artifactReference("b", "credential")

        assertTrue(
            requiresFreshDeviceMirrorPublication(persisted, credentialArtifact) { null },
        )
        assertTrue(
            requiresFreshDeviceMirrorPublication(persisted, credentialArtifact) {
                VerifiedDeviceMirror(
                    directBootState(persisted).copy(protectionIntent = false),
                    credentialArtifact,
                )
            },
        )
    }

    @Test
    fun `matching state with stale artifact sha or identity requires fresh publication`() = runTest {
        val persisted = persistedState()
        val credentialArtifact = artifactReference("b", "credential")
        val state = directBootState(persisted)

        assertTrue(
            requiresFreshDeviceMirrorPublication(persisted, credentialArtifact) {
                VerifiedDeviceMirror(state, artifactReference("c", "credential"))
            },
        )
        assertTrue(
            requiresFreshDeviceMirrorPublication(persisted, credentialArtifact) {
                VerifiedDeviceMirror(state, artifactReference("b", "stale"))
            },
        )
    }

    private fun persistedState(): PersistedAppState {
        val configuration = AppConfiguration.default()
        return PersistedAppState.initial().copy(
            onboardingCompleted = true,
            configuration = configuration,
            protectionIntent = true,
            activeFilter = checkNotNull(
                com.lavasec.app.platform.FilterMetadata.forId(configuration.activeFilterId),
            ),
            protectionSessionId = 1L,
            configurationIdentity = ConfigurationIdentity(7L, "a".repeat(64)),
        )
    }

    private fun directBootState(persisted: PersistedAppState): DirectBootAppState {
        val configuration = checkNotNull(persisted.configuration)
        return DirectBootAppState(
            onboardingCompleted = persisted.onboardingCompleted,
            protectionIntent = persisted.protectionIntent,
            useDeviceDns = configuration.resolver.useDeviceDns,
            useEncryptedFallback = configuration.resolver.useEncryptedFallback,
            encryptedFallbackId = configuration.resolver.encryptedFallbackId,
            customDohUrl = configuration.resolver.customDohUrl,
            customDotEndpoint = configuration.resolver.customDotEndpoint,
            customDoqEndpoint = configuration.resolver.customDoqEndpoint,
            customName = configuration.resolver.customName,
            customSecondaryEndpoint = configuration.resolver.customSecondaryEndpoint,
            savedTiers = configuration.resolver.savedTiers,
            activeProfileId = persisted.activeFilter.id,
            configurationIdentity = persisted.configurationIdentity,
            credentialUnlockRequired = false,
        )
    }

    private fun artifactReference(digest: String, revision: String) = FilterArtifactReference(
        sha256 = digest.repeat(64),
        artifactIdentity = FilterArtifactIdentity(
            configuration = ArtifactConfigurationIdentity(7L, "a".repeat(64)),
            selectedSources = emptyList(),
            catalogRevision = revision,
            catalogSha256 = "d".repeat(64),
            artifactSchema = FilterArtifact.ARTIFACT_SCHEMA,
            pointerSchema = FilterArtifact.POINTER_SCHEMA,
            parserRulesVersion = BlocklistParser.RULES_VERSION,
            ruleCounts = ArtifactRuleCounts(0, 0, 0, 0, 0, 0),
            ruleTablesSha256 = "e".repeat(64),
            compileInputsSha256 = "f".repeat(64),
            intentionalEmpty = true,
        ),
    )
}
