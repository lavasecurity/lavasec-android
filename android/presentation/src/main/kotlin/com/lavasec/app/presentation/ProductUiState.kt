package com.lavasec.app.presentation

import com.lavasec.app.foundation.health.ProtectionHealthAssessment
import com.lavasec.app.foundation.health.ProtectionHealthSeverity
import com.lavasec.app.platform.ProtectionFailure
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.SettingsRoute
import com.lavasec.app.platform.VpnOwnership
import com.lavasec.app.presentation.guard.pauseDeadlineMinutes

/** A semantic Guard action. Rendering this value does not execute the platform command. */
sealed interface GuardPrimaryAction {
    data object TurnOn : GuardPrimaryAction
    data object TurnOff : GuardPrimaryAction
    data object Resume : GuardPrimaryAction
    data object Reconnect : GuardPrimaryAction
    data class OpenSettings(val route: SettingsRoute) : GuardPrimaryAction
}

/**
 * Typed settled-status vocabulary consumed by GuardScreen. Slice 02 replaces the
 * localized-title model with this enum pair so no renderer or mascot mapping ever
 * compares copy; the screen derives every visible string from resources keyed on
 * these values (iOS parity: ProtectionStatusPanel derives copy from `vpnStatus`
 * plus `protectionConnectivityAssessment.severity`, never from a title string).
 */
enum class GuardLifecycleUi {
    /** Protection Off, SetupRequired, or a revoked VPN permission. */
    INACTIVE,
    STARTING,
    AWAITING_NETWORK,
    PROTECTED,
    RECONNECTING,
    PAUSED,
    STOPPING,

    /** Failure-derived states that need user attention (settings routes or reconnect). */
    ATTENTION,
}

/**
 * Connectivity/resolver-health axis. Android's [ProtectionHealthSeverity] folds
 * resolver health (DNS slow / needs reconnect) into the same severity enum that
 * iOS splits across connectivity and resolver-health, so this single typed axis
 * carries both; GuardScreen maps DNS_SLOW and NEEDS_RECONNECT to the concerned
 * mascot pose exactly like iOS maps its resolver-driven severities.
 */
enum class GuardConnectivityUi {
    HEALTHY,
    INTERNET_UNVERIFIED,
    RECHECKING,
    ENCRYPTED_FALLBACK,
    DEVICE_DNS_FALLBACK,
    PLAIN_DNS_FALLBACK,
    RECOVERING,
    DNS_SLOW,
    NETWORK_UNAVAILABLE,
    NEEDS_RECONNECT,

    /** No confirmed health assessment is available for this state. */
    UNAVAILABLE,
}

/**
 * Active temporary pause, rendered through a clock-independent formatter:
 * [remainingMinutes] is frozen at state build time from the injected now, so the
 * screen never reads the device clock and tests always see deterministic copy.
 */
data class GuardPauseUi(
    val deadlineEpochMillis: Long,
    val remainingMinutes: Long,
) {
    init {
        require(deadlineEpochMillis >= 0L) { "pause deadline must be non-negative" }
        require(remainingMinutes >= 0L) { "remaining pause minutes must be non-negative" }
    }
}

/** Typed Guard message lines, rendered below the primary action (iOS guardPanelMessage). */
enum class GuardMessageUi {
    ALWAYS_ON_MANAGED,
    OWNERSHIP_UNVERIFIED_API_28,
    VPN_PERMISSION_REVOKED,
}

/**
 * Typed in-flight/permission states that disable the Guard primary action. The
 * screen derives the explanation copy from this value; the action is disabled
 * for no other reason, so a rendered disabled button always has an explanation.
 */
enum class GuardActionDisabledUi {
    COMMAND_IN_FLIGHT,
    RECONSTRUCTION_REQUIRED,
}

/**
 * Immutable typed Guard status that the Guard renderer displays from lifecycle
 * evidence. It carries no localized copy: GuardScreen maps these values to
 * resources, and the mascot mapping keys on the same typed values.
 */
data class GuardProtectionHealthUi(
    val lifecycle: GuardLifecycleUi,
    val connectivity: GuardConnectivityUi,
    val pause: GuardPauseUi? = null,
    val primaryAction: GuardPrimaryAction? = null,
    val message: GuardMessageUi? = null,
    val canPause: Boolean = false,
) {
    init {
        require(pause == null || lifecycle == GuardLifecycleUi.PAUSED) {
            "a pause deadline is only meaningful for the paused lifecycle"
        }
        require(lifecycle != GuardLifecycleUi.PAUSED || pause != null) {
            "the paused lifecycle requires a pause deadline"
        }
    }

    companion object {
        fun from(
            state: ProtectionState,
            health: ProtectionHealthAssessment? = null,
            nowEpochMillis: Long,
            internetValidated: Boolean? = null,
            rechecking: Boolean = false,
        ): GuardProtectionHealthUi = when (state) {
            ProtectionState.Off,
            ProtectionState.SetupRequired,
            -> inactive()

            is ProtectionState.Starting -> transitioning(GuardLifecycleUi.STARTING)

            is ProtectionState.AwaitingNetwork -> transitioning(GuardLifecycleUi.AWAITING_NETWORK)

            is ProtectionState.Reconnecting -> transitioning(GuardLifecycleUi.RECONNECTING)

            is ProtectionState.Protected -> protected(state.ownership, health, internetValidated, rechecking)

            is ProtectionState.Paused -> paused(state.untilEpochMillis, nowEpochMillis)

            ProtectionState.Stopping -> transitioning(GuardLifecycleUi.STOPPING)

            is ProtectionState.Failed -> failure(state.failure)
        }

        private fun inactive() = GuardProtectionHealthUi(
            lifecycle = GuardLifecycleUi.INACTIVE,
            connectivity = GuardConnectivityUi.UNAVAILABLE,
            primaryAction = GuardPrimaryAction.TurnOn,
        )

        private fun transitioning(lifecycle: GuardLifecycleUi) = GuardProtectionHealthUi(
            lifecycle = lifecycle,
            connectivity = GuardConnectivityUi.UNAVAILABLE,
        )

        private fun protected(
            ownership: VpnOwnership,
            health: ProtectionHealthAssessment?,
            internetValidated: Boolean?,
            rechecking: Boolean,
        ): GuardProtectionHealthUi {
            val healthPresentation = health?.let(::protectedWithHealth)
                ?: userProtected(GuardConnectivityUi.UNAVAILABLE)
            val presentation = when {
                rechecking && healthPresentation.connectivity !in setOf(GuardConnectivityUi.NETWORK_UNAVAILABLE,
                    GuardConnectivityUi.DNS_SLOW, GuardConnectivityUi.NEEDS_RECONNECT) ->
                    userProtected(GuardConnectivityUi.RECHECKING).copy(canPause = false)
                internetValidated == false && healthPresentation.connectivity in setOf(GuardConnectivityUi.HEALTHY,
                    GuardConnectivityUi.DEVICE_DNS_FALLBACK, GuardConnectivityUi.ENCRYPTED_FALLBACK) ->
                    userProtected(GuardConnectivityUi.INTERNET_UNVERIFIED)
                else -> healthPresentation
            }

            return when (ownership) {
                VpnOwnership.User -> presentation

                // Always-on ownership replaces the action with OpenSettings and adds the
                // ownership message line while the health-derived status stays intact.
                VpnOwnership.SystemAlwaysOn -> presentation.copy(
                    primaryAction = GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings),
                    message = GuardMessageUi.ALWAYS_ON_MANAGED,
                    canPause = false,
                )

                VpnOwnership.UnknownApi28 -> presentation.copy(
                    primaryAction = GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings),
                    message = GuardMessageUi.OWNERSHIP_UNVERIFIED_API_28,
                    canPause = false,
                )
            }
        }

        private fun protectedWithHealth(health: ProtectionHealthAssessment): GuardProtectionHealthUi =
            when (health.severity) {
                ProtectionHealthSeverity.HEALTHY -> userProtected(GuardConnectivityUi.HEALTHY)

                ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK ->
                    userProtected(GuardConnectivityUi.ENCRYPTED_FALLBACK)

                ProtectionHealthSeverity.USING_PLAIN_DNS_FALLBACK -> userProtected(GuardConnectivityUi.PLAIN_DNS_FALLBACK)
                ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK ->
                    userProtected(GuardConnectivityUi.DEVICE_DNS_FALLBACK)

                ProtectionHealthSeverity.RECOVERING -> GuardProtectionHealthUi(
                    lifecycle = GuardLifecycleUi.PROTECTED,
                    connectivity = GuardConnectivityUi.RECOVERING,
                )

                ProtectionHealthSeverity.DNS_SLOW -> GuardProtectionHealthUi(
                    lifecycle = GuardLifecycleUi.PROTECTED,
                    connectivity = GuardConnectivityUi.DNS_SLOW,
                    primaryAction = GuardPrimaryAction.Reconnect,
                )

                ProtectionHealthSeverity.NETWORK_UNAVAILABLE -> GuardProtectionHealthUi(
                    lifecycle = GuardLifecycleUi.PROTECTED,
                    connectivity = GuardConnectivityUi.NETWORK_UNAVAILABLE,
                )

                ProtectionHealthSeverity.NEEDS_RECONNECT -> GuardProtectionHealthUi(
                    lifecycle = GuardLifecycleUi.PROTECTED,
                    connectivity = GuardConnectivityUi.NEEDS_RECONNECT,
                    primaryAction = GuardPrimaryAction.Reconnect,
                )
            }

        private fun userProtected(connectivity: GuardConnectivityUi) = GuardProtectionHealthUi(
            lifecycle = GuardLifecycleUi.PROTECTED,
            connectivity = connectivity,
            primaryAction = GuardPrimaryAction.TurnOff,
            canPause = true,
        )

        private fun paused(untilEpochMillis: Long, nowEpochMillis: Long) = GuardProtectionHealthUi(
            lifecycle = GuardLifecycleUi.PAUSED,
            connectivity = GuardConnectivityUi.UNAVAILABLE,
            pause = GuardPauseUi(
                deadlineEpochMillis = untilEpochMillis,
                remainingMinutes = pauseDeadlineMinutes(untilEpochMillis, nowEpochMillis),
            ),
            primaryAction = GuardPrimaryAction.Resume,
        )

        private fun failure(failure: ProtectionFailure): GuardProtectionHealthUi = when (failure) {
            is ProtectionFailure.AlwaysOnStopRejected -> GuardProtectionHealthUi(
                lifecycle = GuardLifecycleUi.PROTECTED,
                connectivity = GuardConnectivityUi.UNAVAILABLE,
                primaryAction = GuardPrimaryAction.OpenSettings(failure.route),
                message = GuardMessageUi.ALWAYS_ON_MANAGED,
            )

            ProtectionFailure.Revoked -> GuardProtectionHealthUi(
                lifecycle = GuardLifecycleUi.INACTIVE,
                connectivity = GuardConnectivityUi.UNAVAILABLE,
                primaryAction = GuardPrimaryAction.TurnOn,
                message = GuardMessageUi.VPN_PERMISSION_REVOKED,
            )

            ProtectionFailure.NetworkUnavailable ->
                transitioning(GuardLifecycleUi.AWAITING_NETWORK)

            else -> failure.route?.let { route ->
                GuardProtectionHealthUi(
                    lifecycle = GuardLifecycleUi.ATTENTION,
                    connectivity = GuardConnectivityUi.UNAVAILABLE,
                    primaryAction = GuardPrimaryAction.OpenSettings(route),
                )
            } ?: GuardProtectionHealthUi(
                lifecycle = GuardLifecycleUi.ATTENTION,
                connectivity = GuardConnectivityUi.UNAVAILABLE,
                primaryAction = GuardPrimaryAction.Reconnect,
            )
        }
    }
}
