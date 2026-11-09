package com.lavasec.app.reactnative

import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VpnOwnership
import com.lavasec.app.presentation.GuardLifecycleUi
import com.lavasec.app.vpn.chained.ChainedActivitySignal
import com.lavasec.app.vpn.chained.ChainedRuntimeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChainedEstablishmentTest {
    private val protectedState = ProtectionState.Protected(ConfigurationIdentity(5L, "c".repeat(64)), VpnOwnership.User)
    private fun witness(state: ProtectionState = protectedState) = VpnRuntimeRotationWitness(
        ChainedActivitySignal(false, ChainedRuntimeMode.CHAINED, 2L, 7L), state,
    )

    @Test fun `only an exact live installed owner can describe establishment`() {
        for ((state, lifecycle) in listOf(
            ProtectionState.Starting(StartOrigin.Reload, 11L) to GuardLifecycleUi.STARTING,
            ProtectionState.Reconnecting(StartOrigin.Reload, 11L) to GuardLifecycleUi.RECONNECTING,
            protectedState to GuardLifecycleUi.PROTECTED,
        )) {
            val live = witness(state)
            assertTrue(nativeChainedEstablishing(live, live, lifecycle))
            assertFalse(nativeChainedEstablishing(null, live, lifecycle))
            assertFalse(nativeChainedEstablishing(live, null, lifecycle))
            assertFalse(nativeChainedEstablishing(live, live, GuardLifecycleUi.INACTIVE))
        }
    }

    @Test fun `active dns-only absent and unknown-generation runtimes cannot claim a handshake`() {
        val live = witness()
        for (activity in listOf(
            live.activity.copy(active = true),
            live.activity.copy(mode = ChainedRuntimeMode.DNS_ONLY),
            live.activity.copy(mode = ChainedRuntimeMode.NONE),
            live.activity.copy(runningConfigurationGeneration = 0L),
        )) {
            val changed = live.copy(activity = activity)
            assertFalse(nativeChainedEstablishing(changed, changed, GuardLifecycleUi.PROTECTED))
        }
        for (state in listOf(ProtectionState.Off, ProtectionState.Stopping, ProtectionState.Paused(1000L),
            ProtectionState.AwaitingNetwork(StartOrigin.Reload, 11L))) {
            val changed = witness(state)
            assertFalse(nativeChainedEstablishing(changed, changed, GuardLifecycleUi.PROTECTED))
        }
    }

    @Test fun `a cached family cannot paint after a publication configuration or service state changes`() {
        val captured = witness()
        for (current in listOf(
            captured.copy(activity = captured.activity.copy(sequence = 3L)),
            captured.copy(activity = captured.activity.copy(runningConfigurationGeneration = 8L)),
            captured.copy(state = ProtectionState.Reconnecting(StartOrigin.Reload, 12L)),
        )) assertFalse(nativeChainedEstablishing(captured, current, GuardLifecycleUi.PROTECTED))
    }
}
