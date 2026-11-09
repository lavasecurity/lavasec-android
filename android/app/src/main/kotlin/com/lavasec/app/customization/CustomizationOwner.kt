package com.lavasec.app.customization

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Durable customization preferences. [reduceMotion] is null until the user
 * overrides the Android system setting; a null value means LavaTheme follows
 * the OS motion setting exactly like the rest of the app.
 */
data class CustomizationPreferences(
    val reduceMotion: Boolean? = null,
    val haptics: Boolean = true,
)

data class CustomizationOwnerState(
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true,
    val notificationsAllowed: Boolean? = null,
    val notificationsBlocked: Boolean = false,
    val inFlight: Boolean = false,
    val error: String? = null,
)

/**
 * Generation-fenced owner for customization preferences. Every mutation goes
 * through the serialized admission fence and the injected persistence ports —
 * Compose never writes preferences directly (the slice-04 customization
 * contract). The notification channel status is a read-only platform port
 * refreshed on demand so the route shows the OS truth, never a cached belief.
 */
class CustomizationOwner(
    initial: CustomizationPreferences,
    private val persist: suspend (CustomizationPreferences) -> Unit,
    private val notificationChannelAllowed: suspend () -> Boolean?,
    scope: CoroutineScope,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private val admission = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(
        CustomizationOwnerState(
            reduceMotion = initial.reduceMotion == true,
            haptics = initial.haptics,
        ),
    )

    val state: StateFlow<CustomizationOwnerState> = mutableState.asStateFlow()

    suspend fun setReduceMotion(enabled: Boolean): Boolean = mutate { previous ->
        previous.copy(reduceMotion = if (enabled) true else null)
    }

    suspend fun setHaptics(enabled: Boolean): Boolean = mutate { previous ->
        previous.copy(haptics = enabled)
    }

    /** Re-reads the OS channel status (call on foreground so system changes land). */
    suspend fun refreshNotifications(): Boolean = owned(false) {
        refreshNotificationsAdmitted()
    }

    private suspend fun refreshNotificationsAdmitted(): Boolean {
        if (closed.get() || !admission.tryLock()) return false
        try {
            val allowed = notificationChannelAllowed()
            publish(
                mutableState.value.copy(
                    notificationsAllowed = allowed,
                    notificationsBlocked = allowed == false,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publish(
                mutableState.value.copy(
                    error = failure.message?.takeIf(String::isNotBlank)
                        ?: "Customization refresh failed.",
                ),
            )
        } finally {
            admission.unlock()
        }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ownerJob.cancel()
        synchronized(stateLock) {
            mutableState.value = mutableState.value.copy(inFlight = false)
        }
    }

    private suspend fun mutate(
        transform: (CustomizationPreferences) -> CustomizationPreferences,
    ): Boolean = owned(false) {
        mutateAdmitted(transform)
    }

    private suspend fun mutateAdmitted(
        transform: (CustomizationPreferences) -> CustomizationPreferences,
    ): Boolean {
        if (closed.get() || !admission.tryLock()) return false
        publish(mutableState.value.copy(inFlight = true, error = null))
        try {
            val desired = transform(
                CustomizationPreferences(
                    reduceMotion = mutableState.value.reduceMotion.takeIf { it },
                    haptics = mutableState.value.haptics,
                ),
            )
            persist(desired)
            publish(
                mutableState.value.copy(
                    reduceMotion = desired.reduceMotion == true,
                    haptics = desired.haptics,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publish(
                mutableState.value.copy(
                    error = failure.message?.takeIf(String::isNotBlank)
                        ?: "Customization change failed.",
                ),
            )
        } finally {
            publish(mutableState.value.copy(inFlight = false))
            admission.unlock()
        }
        return true
    }

    private fun publish(state: CustomizationOwnerState) {
        synchronized(stateLock) {
            if (!closed.get()) mutableState.value = state
        }
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
