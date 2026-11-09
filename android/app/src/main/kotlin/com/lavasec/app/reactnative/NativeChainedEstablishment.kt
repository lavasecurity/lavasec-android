package com.lavasec.app.reactnative

import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.presentation.GuardLifecycleUi
import com.lavasec.app.vpn.chained.ChainedRuntimeMode

/** A cached family may describe establishment only while its exact installed-owner publication
 * still matches the current service. Saved preference/configuration alone is not runtime evidence.
 */
internal fun nativeChainedEstablishing(
    captured: VpnRuntimeRotationWitness?,
    current: VpnRuntimeRotationWitness?,
    lifecycle: GuardLifecycleUi?,
): Boolean {
    if (captured == null || current == null || captured != current) return false
    val activity = current.activity
    if (activity.active || activity.mode != ChainedRuntimeMode.CHAINED ||
        activity.runningConfigurationGeneration <= 0L) return false
    return when (current.state) {
        is ProtectionState.Starting -> lifecycle == GuardLifecycleUi.STARTING
        is ProtectionState.Reconnecting -> lifecycle == GuardLifecycleUi.RECONNECTING
        is ProtectionState.Protected -> lifecycle == GuardLifecycleUi.PROTECTED
        else -> false
    }
}
