package com.lavasec.app.product.privacy

import com.lavasec.app.platform.LoggingSettings
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

data class PrivacyOwnerState(
    val logging: LoggingSettings? = null,
    val inFlight: Boolean = false,
    val lastMutation: PrivacyMutationResult? = null,
    val exportHandle: LocalExportHandle? = null,
    val error: String? = null,
)

/** Serial product owner for explicit privacy mutations and local-only export creation. */
class PrivacyOwner(
    initialLogging: LoggingSettings? = null,
    setLogging: suspend (LoggingSettings) -> PrivacyMutationResult,
    clear: suspend (LocalActivityClearSelection) -> PrivacyMutationResult,
    export: suspend (LocalActivityExportOptions) -> LocalExportResult,
    scope: CoroutineScope,
    clearNetworkActivity: suspend () -> PrivacyMutationResult = { PrivacyMutationResult.Completed },
    clearGuardProgress: suspend () -> PrivacyMutationResult = { PrivacyMutationResult.Completed },
    restoreLogging: suspend (LoggingSettings, () -> Boolean) -> PrivacyMutationResult = { _, _ ->
        PrivacyMutationResult.Failed("Restore retention authority is unavailable")
    },
    resetQa: suspend (LoggingSettings, Long, () -> Boolean) -> PrivacyMutationResult = { _, _, _ -> PrivacyMutationResult.Cancelled },
) : AutoCloseable {
    private val setLoggingPort = setLogging
    private val restoreLoggingPort = restoreLogging
    private val resetQaPort = resetQa
    private val clearPort = clear
    private val exportPort = export
    private val clearNetworkActivityPort = clearNetworkActivity
    private val clearGuardProgressPort = clearGuardProgress
    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private val admission = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(PrivacyOwnerState(logging = initialLogging))

    val state: StateFlow<PrivacyOwnerState> = mutableState.asStateFlow()

    suspend fun setLogging(settings: LoggingSettings): Boolean =
        mutate(committedLogging = settings) { setLoggingPort(settings) }

    /** Returns the actual commit result even when this owner retires after the durable write. */
    suspend fun setLoggingForRestore(settings: LoggingSettings, isCurrent: () -> Boolean): PrivacyMutationResult {
        if (closed.get() || !admission.tryLock()) return PrivacyMutationResult.Cancelled
        publish(mutableState.value.copy(inFlight = true, error = null, exportHandle = null))
        return try {
            val result = restoreLoggingPort(settings) { !closed.get() && isCurrent() }
            publish(mutableState.value.copy(
                logging = if (result == PrivacyMutationResult.Completed || result is PrivacyMutationResult.CommittedReconciliationRequired)
                    settings else mutableState.value.logging,
                lastMutation = result,
                error = when (result) {
                    is PrivacyMutationResult.Failed -> result.message
                    is PrivacyMutationResult.CommittedReconciliationRequired -> result.message
                    else -> null
                },
            ))
            result
        } finally {
            publish(mutableState.value.copy(inFlight = false))
            admission.unlock()
        }
    }

    suspend fun resetForQa(expected: LoggingSettings, expectedConfigurationGeneration: Long, isCurrent: () -> Boolean): PrivacyMutationResult {
        if (closed.get() || !admission.tryLock()) return PrivacyMutationResult.Cancelled
        publish(mutableState.value.copy(inFlight = true, error = null, exportHandle = null))
        return try {
            if (mutableState.value.logging != expected) return PrivacyMutationResult.Cancelled
            val result = resetQaPort(expected, expectedConfigurationGeneration) { !closed.get() && isCurrent() }
            publish(mutableState.value.copy(
                logging = if (result == PrivacyMutationResult.Completed || result is PrivacyMutationResult.CommittedReconciliationRequired) expected.copy(keepHistory = false) else mutableState.value.logging,
                lastMutation = result,
                error = when (result) {
                    is PrivacyMutationResult.Failed -> result.message
                    is PrivacyMutationResult.CommittedReconciliationRequired -> result.message
                    else -> null
                },
            ))
            result
        } finally { publish(mutableState.value.copy(inFlight = false)); admission.unlock() }
    }

    suspend fun clear(selection: LocalActivityClearSelection): Boolean =
        mutate { clearPort(selection) }

    /** Destructive Network Activity-only clear; counts/uptime and Domain History stay. */
    suspend fun clearNetworkActivity(): Boolean =
        mutate { clearNetworkActivityPort() }

    /** Destructive Lava Guard progress-only clear; counts/uptime and Domain History stay. */
    suspend fun clearGuardProgress(): Boolean =
        mutate { clearGuardProgressPort() }

    suspend fun export(options: LocalActivityExportOptions): Boolean = owned(false) {
        exportAdmitted(options)
    }

    private suspend fun exportAdmitted(options: LocalActivityExportOptions): Boolean {
        if (closed.get() || !admission.tryLock()) return false
        publish(mutableState.value.copy(inFlight = true, error = null))
        try {
            when (val result = exportPort(options)) {
                is LocalExportResult.Completed -> publish(
                    PrivacyOwnerState(
                        logging = mutableState.value.logging,
                        exportHandle = result.handle,
                    ),
                )
                LocalExportResult.Cancelled -> publish(
                    PrivacyOwnerState(logging = mutableState.value.logging),
                )
                is LocalExportResult.Failed -> publish(
                    PrivacyOwnerState(
                        logging = mutableState.value.logging,
                        error = result.message,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            publish(mutableState.value.copy(inFlight = false))
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
        committedLogging: LoggingSettings? = null,
        operation: suspend () -> PrivacyMutationResult,
    ): Boolean = owned(false) {
        mutateAdmitted(committedLogging, operation)
    }

    private suspend fun mutateAdmitted(
        committedLogging: LoggingSettings?,
        operation: suspend () -> PrivacyMutationResult,
    ): Boolean {
        if (closed.get() || !admission.tryLock()) return false
        publish(mutableState.value.copy(inFlight = true, error = null, exportHandle = null))
        try {
            val result = operation()
            publish(
                PrivacyOwnerState(
                    logging = if (
                        result == PrivacyMutationResult.Completed ||
                        result is PrivacyMutationResult.CommittedReconciliationRequired
                    ) {
                        committedLogging ?: mutableState.value.logging
                    } else {
                        mutableState.value.logging
                    },
                    lastMutation = result,
                    error = when (result) {
                        is PrivacyMutationResult.Failed -> result.message
                        is PrivacyMutationResult.CommittedReconciliationRequired -> result.message
                        PrivacyMutationResult.Completed,
                        PrivacyMutationResult.Cancelled,
                        -> null
                    },
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            publish(mutableState.value.copy(inFlight = false))
            admission.unlock()
        }
        return true
    }

    private fun publish(state: PrivacyOwnerState) {
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
