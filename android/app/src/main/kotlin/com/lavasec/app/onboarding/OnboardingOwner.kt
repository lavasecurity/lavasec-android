package com.lavasec.app.onboarding

import android.content.Intent
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.onboarding.OnboardingAction
import com.lavasec.app.foundation.onboarding.OnboardingPage
import com.lavasec.app.foundation.onboarding.OnboardingReducer
import com.lavasec.app.foundation.onboarding.OnboardingState
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.VpnPreparation
import com.lavasec.app.platform.VpnPreparationResult
import com.lavasec.app.presentation.ProductNavigation
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PendingVpnConsent(
    val id: Long,
    val request: Intent,
) {
    init {
        require(id > 0L)
    }
}

data class ReleasedProductNavigation(
    val id: Long,
    val navigation: ProductNavigation,
) {
    init {
        require(id > 0L)
    }
}

data class OnboardingOwnerState(
    val completed: Boolean,
    val onboarding: OnboardingState?,
    val pendingVpnConsent: PendingVpnConsent? = null,
    val notificationPermissionGranted: Boolean? = null,
    /**
     * The system VPN-consent dialog was declined. The screen shows a post-denial
     * explanation while this stays set; a new prepared consent hides it again
     * (the system dialog is on top) and a granted result clears it.
     */
    val vpnConsentDenied: Boolean = false,
    val commitInFlight: Boolean = false,
    val reconstructionRequired: Boolean = false,
    val error: String? = null,
    val stagedNavigation: ProductNavigation? = null,
    val releasedNavigation: ReleasedProductNavigation? = null,
) {
    init {
        require(completed == (onboarding == null))
        require(!completed || !commitInFlight)
        require(!completed || !reconstructionRequired)
        require(!reconstructionRequired || !commitInFlight)
        require(pendingVpnConsent == null || onboarding?.page == OnboardingPage.VPN)
        require(!vpnConsentDenied || onboarding?.page == OnboardingPage.VPN)
    }
}

/**
 * Configuration-stable onboarding authority. Android launchers consume exact request IDs while
 * graph retirement cancels the generation-scoped owner.
 */
class OnboardingOwner(
    onboardingCompleted: Boolean,
    reconstructionRequired: Boolean = false,
    private val vpnPreparation: VpnPreparation<Intent>,
    private val commit: suspend (AppConfiguration) -> Unit,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + dispatcher + ownerJob)
    private val mutex = Mutex()
    private var nextConsentId = 1L
    private var nextNavigationId = 1L
    private val mutableState = MutableStateFlow(
        if (onboardingCompleted) {
            OnboardingOwnerState(completed = true, onboarding = null)
        } else if (reconstructionRequired) {
            OnboardingOwnerState(
                completed = false,
                onboarding = OnboardingState.initial(),
                reconstructionRequired = true,
                error = "Onboarding authority requires reconstruction.",
            )
        } else {
            OnboardingOwnerState(completed = false, onboarding = OnboardingState.initial())
        },
    )

    init {
        require(!onboardingCompleted || !reconstructionRequired)
    }

    val state: StateFlow<OnboardingOwnerState> = mutableState.asStateFlow()

    suspend fun forward(): Unit = owned(Unit) { mutex.withLock {
        val current = mutableState.value
        if (current.completed || current.reconstructionRequired || current.commitInFlight) {
            return@withLock
        }
        val onboarding = checkNotNull(current.onboarding)
        if (onboarding.page == OnboardingPage.VPN) {
            if (current.pendingVpnConsent != null) return@withLock
            when (val preparation = vpnPreparation.prepare()) {
                VpnPreparationResult.Prepared -> publishOnboarding(
                    OnboardingReducer.reduce(
                        onboarding,
                        OnboardingAction.VpnConfigurationResult(successful = true),
                    ),
                )
                is VpnPreparationResult.ConsentRequired -> {
                    val id = nextConsentId
                    nextConsentId = Math.addExact(nextConsentId, 1L)
                    mutableState.value = current.copy(
                        pendingVpnConsent = PendingVpnConsent(id, preparation.request),
                        error = null,
                    )
                }
                VpnPreparationResult.Unavailable -> {
                    mutableState.value = current.copy(
                        error = "Android VPN preparation is unavailable.",
                    )
                }
            }
        } else {
            publishOnboarding(OnboardingReducer.reduce(onboarding, OnboardingAction.Forward))
        }
        commitIfComplete()
    } }

    suspend fun back(): Unit = owned(Unit) { mutex.withLock {
        val current = mutableState.value
        if (current.completed || current.commitInFlight || current.reconstructionRequired) {
            return@withLock
        }
        mutableState.value = current.copy(
            onboarding = OnboardingReducer.reduce(
                checkNotNull(current.onboarding),
                OnboardingAction.Back,
            ),
            pendingVpnConsent = null,
            // Leaving the VPN page must clear its denial explanation: the invariant
            // permits the flag only while the VPN page is current.
            vpnConsentDenied = false,
            error = null,
        )
    } }

    suspend fun completeVpnConsent(id: Long, granted: Boolean): Boolean = owned(false) { mutex.withLock {
        val current = mutableState.value
        val pending = current.pendingVpnConsent ?: return@withLock false
        if (pending.id != id) return@withLock false
        val onboarding = checkNotNull(current.onboarding)
        mutableState.value = current.copy(
            onboarding = OnboardingReducer.reduce(
                onboarding,
                OnboardingAction.VpnConfigurationResult(successful = granted),
            ),
            pendingVpnConsent = null,
            vpnConsentDenied = !granted,
            error = null,
        )
        true
    } }

    /** Retire an unpresented/interrupted request without manufacturing a denial or editing its draft. */
    suspend fun cancelVpnConsent(id: Long): Boolean = owned(false) { mutex.withLock {
        val current = mutableState.value
        if (current.pendingVpnConsent?.id != id) return@withLock false
        mutableState.value = current.copy(pendingVpnConsent = null)
        true
    } }

    suspend fun completeNotificationPermission(granted: Boolean): Boolean = owned(false) { mutex.withLock {
        val current = mutableState.value
        val onboarding = current.onboarding ?: return@withLock false
        if (onboarding.page != OnboardingPage.NOTIFICATIONS) return@withLock false
        mutableState.value = current.copy(
            onboarding = OnboardingReducer.reduce(
                onboarding,
                OnboardingAction.NotificationPermissionResult(granted),
            ),
            notificationPermissionGranted = granted,
            error = null,
        )
        true
    } }

    suspend fun selectProtectionLevel(level: ProtectionLevel): Unit = owned(Unit) { mutex.withLock {
        reduceDraft(OnboardingAction.ProtectionLevelSelected(level))
    } }

    suspend fun selectResolver(plan: MilestoneResolverPlan): Unit = owned(Unit) { mutex.withLock {
        reduceDraft(OnboardingAction.ResolverSelected(plan))
    } }

    suspend fun selectResolverRoute(useDeviceDns: Boolean, useEncryptedFallback: Boolean): Unit = owned(Unit) { mutex.withLock {
        reduceDraft(OnboardingAction.ResolverRouteSelected(useDeviceDns, useEncryptedFallback))
    } }

    suspend fun selectFallback(enabled: Boolean): Unit = owned(Unit) { mutex.withLock {
        reduceDraft(OnboardingAction.FallbackSelected(enabled))
    } }

    suspend fun stageNavigation(navigation: ProductNavigation): Boolean = owned(false) { mutex.withLock {
        val current = mutableState.value
        if (current.reconstructionRequired || current.commitInFlight) return@withLock false
        if (current.completed) {
            mutableState.value = current.copy(
                releasedNavigation = release(navigation),
            )
        } else {
            mutableState.value = current.copy(stagedNavigation = navigation)
        }
        true
    } }

    suspend fun consumeReleasedNavigation(id: Long): Boolean = owned(false) { mutex.withLock {
        val current = mutableState.value
        if (current.releasedNavigation?.id != id) return@withLock false
        mutableState.value = current.copy(releasedNavigation = null)
        true
    } }

    override fun close() {
        if (closed.compareAndSet(false, true)) ownerJob.cancel()
    }

    private fun reduceDraft(action: OnboardingAction) {
        val current = mutableState.value
        if (current.completed || current.commitInFlight || current.reconstructionRequired) return
        mutableState.value = current.copy(
            onboarding = OnboardingReducer.reduce(checkNotNull(current.onboarding), action),
            error = null,
        )
    }

    private fun publishOnboarding(onboarding: OnboardingState) {
        mutableState.value = mutableState.value.copy(onboarding = onboarding, error = null)
    }

    private suspend fun commitIfComplete() {
        val current = mutableState.value
        val configuration = current.onboarding?.let(OnboardingReducer::complete) ?: return
        mutableState.value = current.copy(commitInFlight = true, error = null)
        try {
            commit(configuration)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (!closed.get()) {
                    publishAmbiguousFailure("Onboarding commit was interrupted.")
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            publishAmbiguousFailure(
                failure.message?.takeIf(String::isNotBlank)
                    ?: "Onboarding authority requires reconstruction.",
            )
            return
        }
        if (closed.get()) return
        val committed = mutableState.value
        val staged = committed.stagedNavigation
        mutableState.value = committed.copy(
            completed = true,
            onboarding = null,
            commitInFlight = false,
            stagedNavigation = null,
            releasedNavigation = staged?.let(::release),
        )
    }

    private fun publishAmbiguousFailure(message: String) {
        mutableState.value = mutableState.value.copy(
            commitInFlight = false,
            reconstructionRequired = true,
            error = message,
        )
    }

    private fun release(navigation: ProductNavigation): ReleasedProductNavigation {
        val id = nextNavigationId
        nextNavigationId = Math.addExact(nextNavigationId, 1L)
        return ReleasedProductNavigation(id, navigation)
    }

    private suspend fun <T> owned(closedResult: T, operation: suspend () -> T): T {
        if (closed.get()) return closedResult
        val task = ownerScope.async {
            if (closed.get()) closedResult else operation()
        }
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            if (closed.get()) closedResult else {
                task.cancel()
                throw cancelled
            }
        }
    }
}
