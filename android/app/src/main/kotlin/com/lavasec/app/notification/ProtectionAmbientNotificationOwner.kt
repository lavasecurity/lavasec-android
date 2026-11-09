package com.lavasec.app.notification

import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.vpn.VpnHealthStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface ProtectionAmbientNotificationPresenter {
    fun post(state: ProtectionAmbientUiState): Boolean

    fun cancel(): Boolean
}

/**
 * Android-native equivalent of the iOS Live Activity (LavaLiveActivityController.swift):
 * one ongoing notification showing protection state, the active fallback, the selected
 * filter/resolver, and exactly one safe action (open the app). It extends the existing
 * notification anchors — it consumes the same [LavaVpnRuntimeBridge] flows as
 * [CredentialConnectivityNotificationOwner], is wired at the same credential-graph
 * boundary, and shares the factory channel pattern of
 * [ProtectionProblemNotificationFactory]; nothing in a composable ever publishes here.
 *
 * Because Android keeps the process (and this collector) alive behind the VPN runtime,
 * the ambient state stays fresh on every protection/health/confirmed-filter emission
 * instead of the iOS push-only staleness model. The presenter removes the notification
 * when the derived status is OFF, so the surface never claims protection while none is
 * active. A first emission with a visible status posts immediately — the honest
 * restoration semantic after a graph rotation, mirroring the iOS controller's adoption
 * of a still-updatable activity.
 */
internal class ProtectionAmbientNotificationOwner(
    protection: Flow<ProtectionState>,
    health: Flow<VpnHealthStatus>,
    configuration: Flow<FilterLibraryDraft?>,
    private val presenter: ProtectionAmbientNotificationPresenter,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private var lastVisible: ProtectionAmbientUiState? = null
    private var lastDerived: ProtectionAmbientUiState? = null
    // A freshly created owner owns no notification, so an OFF baseline has nothing to
    // cancel; the flag starts true and flips only after a real post.
    private var lastStatusWasOff = true

    init {
        ownerScope.launch {
            combine(protection, health, configuration) { protectionState, healthStatus, library ->
                deriveProtectionAmbientUiState(
                    protection = protectionState,
                    health = healthStatus.health,
                    configuration = library,
                    nowMillis = checkedNow(),
                )
            }
                // Re-publication is status-driven: the wall-clock timestamp advances on every
                // emission, so equivalence is compared without it.
                .map { state -> state to (state.copy(timestampMillis = 0L)) }
                .distinctUntilChanged { previous, current -> previous.second == current.second }
                .collect { (state, comparable) ->
                    withContext(dispatcher) {
                        if (state.status == ProtectionAmbientStatus.OFF) {
                            if (!lastStatusWasOff) {
                                presenter.cancel()
                                lastVisible = null
                                lastStatusWasOff = true
                            }
                            lastDerived = null
                            return@withContext
                        }
                        lastDerived = state
                        if (lastVisible?.copy(timestampMillis = 0L) == comparable) return@withContext
                        if (presenter.post(state)) {
                            lastVisible = state
                            lastStatusWasOff = false
                        }
                    }
                }
        }
    }

    /**
     * Re-posts the current derived state after notification settings may have
     * changed (e.g. the user enabled notifications in system settings): Android
     * silently drops a [NotificationManager.notify] while the app is blocked,
     * so a previously failed post never becomes visible just from state
     * emissions alone. Called on every foreground refresh by the host.
     */
    suspend fun rePostCurrent(): Boolean = withContext(dispatcher) {
        val current = lastDerived ?: return@withContext false
        if (current.status == ProtectionAmbientStatus.OFF) return@withContext false
        if (!presenter.post(current)) return@withContext false
        lastVisible = current
        lastStatusWasOff = false
        true
    }

    override fun close() {
        if (ownerJob.isActive) {
            ownerJob.cancel()
            // A retired owner removes its ambient notification: the replacement graph
            // re-posts from its own first emission, so the shade never shows a stale
            // status from a generation that no longer owns protection.
            runCatching(presenter::cancel)
            lastVisible = null
            lastStatusWasOff = true
        }
    }

    private fun checkedNow(): Long = nowMillis().also { require(it >= 0L) }
}
