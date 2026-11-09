package com.lavasec.app.platform

import com.lavasec.app.foundation.filter.CatalogFilterSource
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterSourceCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProtectionLifecycleContractTest {
    @Test
    fun `public lifecycle carries origin generation ownership and typed failures`() {
        val identity = ConfigurationIdentity(7, "fingerprint")

        assertEquals(ProtectionState.Starting(StartOrigin.VisibleUser, 3), ProtectionState.Starting(StartOrigin.VisibleUser, 3))
        assertEquals(ProtectionState.AwaitingNetwork(StartOrigin.StickyRestart, 4), ProtectionState.AwaitingNetwork(StartOrigin.StickyRestart, 4))
        assertEquals(ProtectionState.Reconnecting(StartOrigin.Reload, 5), ProtectionState.Reconnecting(StartOrigin.Reload, 5))
        assertEquals(ProtectionState.Protected(identity, VpnOwnership.SystemAlwaysOn), ProtectionState.Protected(identity, VpnOwnership.SystemAlwaysOn))
        assertEquals(
            ProtectionState.Failed(ProtectionFailure.PrivateDnsUnsupported(SettingsRoute.NetworkSettings)),
            ProtectionState.Failed(ProtectionFailure.PrivateDnsUnsupported(SettingsRoute.NetworkSettings)),
        )
        assertEquals(SettingsRoute.VpnSettings, ProtectionFailure.LockdownEnabled(SettingsRoute.VpnSettings).route)
        assertNull(ProtectionFailure.EngineUnavailable.route)
    }

    @Test
    fun `two store outcomes expose authoritative and verified mirror boundaries`() = runTest {
        val configuration = seededConfiguration()
        val fixture = RealFilterArtifactFixture.createForConfiguration(configuration, 7L)
        try {
            val confirmed = confirmedState(configuration, fixture.verifiedArtifact())
            val rejected = ProtectionIntentWriteResult.RejectedBeforeAuthoritativeCommit(desired = true)
            val coherent = coordinatedWrite(
                desired = true,
                mirror = DirectBootMirrorResult.deviceProtectedPublished(confirmed),
            )
            val partial = coordinatedWrite(
                desired = false,
                mirror = DirectBootMirrorResult.MirrorUnavailable,
            )

            assertEquals(true, rejected.desired)
            coherent as ProtectionIntentWriteResult.AuthoritativeCommitted
            partial as ProtectionIntentWriteResult.AuthoritativeCommitted
            assertEquals(true, coherent.desired)
            assertSame(confirmed.verifiedArtifact, coherent.mirror.verifiedArtifact)
            assertSame(DirectBootMirrorResult.MirrorUnavailable, partial.mirror)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `API 28 cannot observe Always-on or lockdown through public VpnService APIs`() {
        assertEquals(
            PlatformVpnFacts(VpnOwnership.UnknownApi28, LockdownStatus.UnknownApi28),
            PlatformVpnFacts.api28(),
        )
        assertEquals(
            PlatformVpnFacts(VpnOwnership.User, LockdownStatus.Disabled),
            PlatformVpnFacts.api29(alwaysOn = false, lockdown = false),
        )
        assertEquals(
            PlatformVpnFacts(VpnOwnership.SystemAlwaysOn, LockdownStatus.Disabled),
            PlatformVpnFacts.api29(alwaysOn = true, lockdown = false),
        )
        assertEquals(
            PlatformVpnFacts(VpnOwnership.SystemAlwaysOn, LockdownStatus.Enabled),
            PlatformVpnFacts.api29(alwaysOn = true, lockdown = true),
        )
    }

    private fun confirmedState(
        configuration: FilterLibraryDraft,
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
            credentialUnlockRequired = false,
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

    private suspend fun coordinatedWrite(
        desired: Boolean,
        mirror: DirectBootMirrorResult,
    ): ProtectionIntentWriteResult {
        val authenticator = DirectBootSnapshotAuthenticator { "a".repeat(64) }
        val authority = object : DirectBootSnapshotFreshnessAuthority() {
            override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult =
                rotated(authenticator)

            override fun currentAuthenticatorOrNull(): DirectBootSnapshotAuthenticator =
                authenticator
        }
        return ProtectionIntentWriteCoordinator(
            freshnessAuthority = authority,
            authoritativeCommit = ProtectionIntentAuthoritativeCommit {
                DirectBootAppState.safeDefault().copy(protectionIntent = desired)
            },
            mirrorPublisher = DirectBootProtectionIntentMirrorPublisher { _, _ -> mirror },
        ).write(desired)
    }
}
