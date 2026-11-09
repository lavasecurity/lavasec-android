package com.lavasec.app.diagnostics

import com.lavasec.app.product.privacy.LocalExportHandle
import com.lavasec.app.product.privacy.LocalFeedbackComposer
import com.lavasec.app.product.privacy.LocalFeedbackDraft
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

internal data class DiagnosticsFeedbackOwnerState(
    val inFlight: Boolean = false,
    val record: CredentialOperationalDiagnosticsRecord? = null,
    val error: String? = null,
)

/** Focused read/clear/feedback owner for credential-safe local operational evidence. */
internal class DiagnosticsFeedbackOwner(
    load: suspend () -> CredentialOperationalDiagnosticsRecord?,
    clear: suspend () -> Boolean,
    private val feedback: LocalFeedbackComposer,
    scope: CoroutineScope,
) : AutoCloseable {
    private val loadPort = load
    private val clearPort = clear
    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private val admission = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(DiagnosticsFeedbackOwnerState())

    val state: StateFlow<DiagnosticsFeedbackOwnerState> = mutableState.asStateFlow()

    suspend fun refresh(): Boolean = owned(false) { admitted {
        val record = loadPort()
        publish(DiagnosticsFeedbackOwnerState(record = record))
    } }

    suspend fun clear(): Boolean = owned(false) { admitted {
        if (clearPort()) {
            publish(DiagnosticsFeedbackOwnerState())
        } else {
            publish(mutableState.value.copy(error = "Diagnostics could not be cleared."))
        }
    } }

    suspend fun composeFeedback(
        description: String,
        includeDiagnostics: Boolean,
        localLogExport: LocalExportHandle?,
    ): LocalFeedbackDraft? = owned(null) {
        composeFeedbackAdmitted(description, includeDiagnostics, localLogExport)
    }

    private suspend fun composeFeedbackAdmitted(
        description: String,
        includeDiagnostics: Boolean,
        localLogExport: LocalExportHandle?,
    ): LocalFeedbackDraft? {
        if (closed.get() || !admission.tryLock()) return null
        publish(mutableState.value.copy(inFlight = true, error = null))
        return try {
            feedback.compose(description, includeDiagnostics, localLogExport)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publish(
                mutableState.value.copy(
                    error = failure.message?.takeIf(String::isNotBlank)
                        ?: "Feedback draft could not be created.",
                ),
            )
            null
        } finally {
            publish(mutableState.value.copy(inFlight = false))
            admission.unlock()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ownerJob.cancel()
        synchronized(stateLock) {
            mutableState.value = mutableState.value.copy(inFlight = false)
        }
    }

    private suspend fun admitted(operation: suspend () -> Unit): Boolean {
        if (closed.get() || !admission.tryLock()) return false
        publish(mutableState.value.copy(inFlight = true, error = null))
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publish(
                mutableState.value.copy(
                    error = failure.message?.takeIf(String::isNotBlank)
                        ?: "Diagnostics operation failed.",
                ),
            )
        } finally {
            publish(mutableState.value.copy(inFlight = false))
            admission.unlock()
        }
        return true
    }

    private fun publish(state: DiagnosticsFeedbackOwnerState) {
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
