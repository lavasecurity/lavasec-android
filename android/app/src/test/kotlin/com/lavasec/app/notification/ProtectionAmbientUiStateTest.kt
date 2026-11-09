package com.lavasec.app.notification

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionFailure
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VpnOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionAmbientUiStateTest {
    @Test
    fun `off setup and failed protection derive OFF with no actions`() {
        for (protection in listOf(
            ProtectionState.Off,
            ProtectionState.SetupRequired,
            ProtectionState.Failed(ProtectionFailure.EstablishmentFailed),
        )) {
            val state = derive(protection, healthy())
            assertEquals(ProtectionAmbientStatus.OFF, state.status)
            assertTrue(state.allowedActions.isEmpty())
        }
    }

    @Test
    fun `starting awaiting and reconnecting all derive STARTING`() {
        for (protection in listOf(
            ProtectionState.Starting(StartOrigin.VisibleUser, 1L),
            ProtectionState.AwaitingNetwork(StartOrigin.VisibleUser, 1L),
            ProtectionState.Reconnecting(StartOrigin.VisibleUser, 1L),
        )) {
            assertEquals(ProtectionAmbientStatus.STARTING, derive(protection, healthy()).status)
        }
    }

    @Test
    fun `paused and stopping keep their honest transient statuses`() {
        assertEquals(ProtectionAmbientStatus.PAUSED, derive(protectedPaused(), healthy()).status)
        assertEquals(ProtectionAmbientStatus.STOPPING, derive(ProtectionState.Stopping, healthy()).status)
    }

    @Test
    fun `protected health decides healthy fallback and reconnect needed`() {
        assertEquals(ProtectionAmbientStatus.PROTECTED, derive(protected(), healthy()).status)

        assertEquals(
            ProtectionAmbientStatus.DEVICE_DNS_FALLBACK,
            derive(protected(), healthy(activeFallback = ProtectionFallback.DEVICE_DNS)).status,
        )
        assertEquals(
            ProtectionAmbientStatus.ENCRYPTED_FALLBACK,
            derive(protected(), healthy(activeFallback = ProtectionFallback.ENCRYPTED_DNS)).status,
        )
        assertEquals(
            ProtectionAmbientStatus.RECONNECT_NEEDED,
            derive(protected(), healthy(requiresReconnect = true)).status,
        )
        assertEquals(
            ProtectionAmbientStatus.RECONNECT_NEEDED,
            derive(protected(), healthy(isConnected = false)).status,
        )
    }

    @Test
    fun `filter name and resolver plan come from the active profile`() {
        val state = derive(
            protected(),
            healthy(),
            configuration = library("Balanced", MilestoneResolverPlan.MULLVAD_ONLY),
        )

        assertEquals("Balanced", state.filterName)
        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, state.resolverPlan)
        assertEquals(setOf(ProtectionAmbientAction.OPEN_APP), state.allowedActions)
    }

    @Test
    fun `missing configuration leaves summaries null without inventing facts`() {
        val state = derive(protected(), healthy(), configuration = null)

        assertNull(state.filterName)
        assertNull(state.resolverPlan)
    }

    @Test
    fun `the timestamp is the derivation wall clock`() {
        assertEquals(42_000L, derive(protected(), healthy(), nowMillis = 42_000L).timestampMillis)
    }

    private fun derive(
        protection: ProtectionState,
        health: ProtectionHealthAccumulator,
        configuration: FilterLibraryDraft? = library(),
        nowMillis: Long = 1_000L,
    ) = deriveProtectionAmbientUiState(protection, health, configuration, nowMillis)

    private fun protectedPaused() = ProtectionState.Paused(untilEpochMillis = Long.MAX_VALUE)

    private fun protected() = ProtectionState.Protected(
        configurationIdentity = ConfigurationIdentity(generation = 1L, fingerprint = "fixture"),
        ownership = VpnOwnership.User,
    )

    private fun healthy(
        isConnected: Boolean = true,
        requiresReconnect: Boolean = false,
        activeFallback: ProtectionFallback = ProtectionFallback.NONE,
    ) = ProtectionHealthAccumulator(
        isConnected = isConnected,
        requiresReconnect = requiresReconnect,
        activeFallback = activeFallback,
    )

    private fun library(
        activeName: String = "Balanced",
        plan: MilestoneResolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
    ): FilterLibraryDraft = FilterLibraryDraft(
        profiles = listOf(
            FilterProfileDraft(
                id = "balanced",
                name = activeName,
                protectionLevel = ProtectionLevel.BALANCED,
                enabledSourceIds = emptySet(),
            ),
        ),
        activeProfileId = "balanced",
        resolverPlan = plan,
    )
}
