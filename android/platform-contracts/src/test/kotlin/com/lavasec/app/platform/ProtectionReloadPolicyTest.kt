package com.lavasec.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reload-liveness policy the React Native module consumes.
 *
 * A preference write must not queue a reload while protection is stopping: the reload would
 * receive a newer service start ID than the stop and restart protection just after the user turned
 * it off.
 */
class ProtectionReloadPolicyTest {

    @Test
    fun `terminal and stopping states are not live for reload`() {
        assertFalse(ProtectionState.Off.isLiveForReload)
        assertFalse(ProtectionState.SetupRequired.isLiveForReload)
        assertFalse(ProtectionState.Stopping.isLiveForReload)
        assertFalse(ProtectionState.Failed(ProtectionFailure.Revoked).isLiveForReload)
    }

    @Test
    fun `in-flight, paused, and active runtimes are live for reload`() {
        assertTrue(ProtectionState.Starting(StartOrigin.VisibleUser, 1L).isLiveForReload)
        assertTrue(ProtectionState.AwaitingNetwork(StartOrigin.VisibleUser, 1L).isLiveForReload)
        assertTrue(ProtectionState.Reconnecting(StartOrigin.Reload, 1L).isLiveForReload)
        assertTrue(ProtectionState.Paused(1_000L).isLiveForReload)
        assertTrue(
            ProtectionState.Protected(
                ConfigurationIdentity(generation = 1L, fingerprint = "a".repeat(64)),
                VpnOwnership.User,
            ).isLiveForReload,
        )
    }

    @Test
    fun `a reload is admitted only for its originating service generation`() {
        // A preference-write reload carries the generation it observed.
        assertTrue(vpnReloadIsAdmitted(expectedServiceGeneration = 7L, currentServiceGeneration = 7L))
        // The stop retired that generation: the queued reload must be a no-op, not a restart.
        assertFalse(vpnReloadIsAdmitted(expectedServiceGeneration = 7L, currentServiceGeneration = null))
        // A newer start replaced it: the old reload is stale too.
        assertFalse(vpnReloadIsAdmitted(expectedServiceGeneration = 7L, currentServiceGeneration = 8L))
        // An internal runtime rebuild is unbound and always admitted.
        assertTrue(vpnReloadIsAdmitted(expectedServiceGeneration = null, currentServiceGeneration = 8L))
        assertTrue(vpnReloadIsAdmitted(expectedServiceGeneration = null, currentServiceGeneration = null))
    }

    @Test
    fun `runtime mode distinguishes inactive, paused, and running`() {
        assertEquals("off", ProtectionState.Off.runtimeModeCode)
        assertEquals("off", ProtectionState.SetupRequired.runtimeModeCode)
        assertEquals("off", ProtectionState.Stopping.runtimeModeCode)
        assertEquals("off", ProtectionState.Failed(ProtectionFailure.Revoked).runtimeModeCode)

        assertEquals("paused", ProtectionState.Paused(1_000L).runtimeModeCode)

        assertEquals("running", ProtectionState.Starting(StartOrigin.VisibleUser, 1L).runtimeModeCode)
        assertEquals("running", ProtectionState.AwaitingNetwork(StartOrigin.VisibleUser, 1L).runtimeModeCode)
        assertEquals("running", ProtectionState.Reconnecting(StartOrigin.Reload, 1L).runtimeModeCode)
        assertEquals(
            "running",
            ProtectionState.Protected(
                ConfigurationIdentity(generation = 1L, fingerprint = "a".repeat(64)),
                VpnOwnership.User,
            ).runtimeModeCode,
        )
    }
}
