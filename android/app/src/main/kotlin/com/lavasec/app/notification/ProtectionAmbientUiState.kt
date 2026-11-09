package com.lavasec.app.notification

import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.ProtectionState

/**
 * The bounded status an ongoing ambient notification may honestly claim. Android keeps
 * this in-process and refreshed by the live runtime flows, so — unlike the push-only iOS
 * Live Activity, which models only on/paused/restarting — the fallback and
 * reconnect-needed facts can be shown without going stale.
 */
enum class ProtectionAmbientStatus {
    OFF,
    STARTING,
    PROTECTED,
    DEVICE_DNS_FALLBACK,
    PLAIN_DNS_FALLBACK,
    ENCRYPTED_FALLBACK,
    RECONNECT_NEEDED,
    PAUSED,
    STOPPING,
}

/** The only actions the ambient surface may ever offer. */
enum class ProtectionAmbientAction {
    /**
     * Opens the app (plain MainActivity launch restoring the saved navigation path).
     * No protection mutation is ever dispatched from the notification shade.
     */
    OPEN_APP,
}

/**
 * The domain-free, account-free facts one ambient notification renders. The presenter
 * composes the short subtitle from [filterName] and [resolverPlan]; the state itself
 * stays string-free except for product data (filter names are user/catalog data, not
 * resources). [timestampMillis] is the wall clock of the last status change.
 */
data class ProtectionAmbientUiState(
    val status: ProtectionAmbientStatus,
    val filterName: String?,
    val resolverPlan: MilestoneResolverPlan?,
    val timestampMillis: Long,
    val allowedActions: Set<ProtectionAmbientAction>,
) {
    init {
        require(timestampMillis >= 0L)
        require(filterName == null || filterName.isNotBlank())
        require(status != ProtectionAmbientStatus.OFF || allowedActions.isEmpty()) {
            "an OFF status carries no allowed actions"
        }
        require(status == ProtectionAmbientStatus.OFF || allowedActions.isNotEmpty()) {
            "a visible status must offer at least the open-app action"
        }
    }
}

/**
 * Pure derivation of the ambient state from the exact runtime flows plus the live
 * confirmed filter configuration. When protection is off (or setup is incomplete or the
 * runtime failed), the status is OFF and the presenter removes the notification. When
 * protected, the health accumulator decides between healthy, fallback, and
 * reconnect-needed — the same facts the Guard surface and the connectivity-problem
 * pipeline use, so the notification shade can never disagree with the app.
 */
internal fun deriveProtectionAmbientUiState(
    protection: ProtectionState,
    health: ProtectionHealthAccumulator,
    configuration: FilterLibraryDraft?,
    nowMillis: Long,
): ProtectionAmbientUiState {
    require(nowMillis >= 0L)
    val status = when (protection) {
        ProtectionState.Off,
        ProtectionState.SetupRequired,
        is ProtectionState.Failed,
        -> ProtectionAmbientStatus.OFF

        is ProtectionState.Starting,
        is ProtectionState.AwaitingNetwork,
        is ProtectionState.Reconnecting,
        -> ProtectionAmbientStatus.STARTING

        is ProtectionState.Paused -> ProtectionAmbientStatus.PAUSED
        ProtectionState.Stopping -> ProtectionAmbientStatus.STOPPING
        is ProtectionState.Protected -> when {
            !health.isConnected || health.requiresReconnect ->
                ProtectionAmbientStatus.RECONNECT_NEEDED

            health.activeFallback == ProtectionFallback.PLAIN_DNS -> ProtectionAmbientStatus.PLAIN_DNS_FALLBACK
            health.activeFallback == ProtectionFallback.DEVICE_DNS ->
                ProtectionAmbientStatus.DEVICE_DNS_FALLBACK

            health.activeFallback == ProtectionFallback.ENCRYPTED_DNS ->
                ProtectionAmbientStatus.ENCRYPTED_FALLBACK

            else -> ProtectionAmbientStatus.PROTECTED
        }
    }
    return ProtectionAmbientUiState(
        status = status,
        filterName = configuration
            ?.profile(configuration.activeProfileId)
            ?.name
            ?.takeIf(String::isNotBlank),
        resolverPlan = configuration?.resolverPlan,
        timestampMillis = nowMillis,
        allowedActions = if (status == ProtectionAmbientStatus.OFF) {
            emptySet()
        } else {
            setOf(ProtectionAmbientAction.OPEN_APP)
        },
    )
}
