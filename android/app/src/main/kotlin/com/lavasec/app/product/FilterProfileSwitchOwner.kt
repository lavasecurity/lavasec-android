package com.lavasec.app.product

import com.lavasec.app.platform.FilterMutationFailure
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One apply of the exact coordinator-backed whole-profile switch. The active profile id on
 * every non-Applied result is the authoritative one after the operation (the coordinator's
 * last verified state), so callers can reconcile display without guessing.
 */
sealed interface FilterProfileSwitchResult {
    val activeProfileId: String

    data class Applied(
        override val activeProfileId: String,
        val generation: Long,
    ) : FilterProfileSwitchResult

    data class Rejected(
        override val activeProfileId: String,
        val reason: FilterProfileSwitchRejection,
    ) : FilterProfileSwitchResult

    data class Failed(
        override val activeProfileId: String,
        val failure: FilterMutationFailure,
    ) : FilterProfileSwitchResult

    data class Superseded(
        override val activeProfileId: String,
    ) : FilterProfileSwitchResult

    data class ProtectionUnavailable(
        override val activeProfileId: String,
    ) : FilterProfileSwitchResult
}

enum class FilterProfileSwitchRejection {
    /** The target id is not one of the saved filters. */
    UNKNOWN_TARGET,

    /** The target filter is already the active filter. */
    NO_CHANGE,

    /**
     * The target filter is frozen (over the hosted-filter cap, read-only): it can be viewed
     * but neither applied nor edited, matching iOS `isFilterFrozen`.
     */
    FROZEN_TARGET,

    /** The coordinator could not authorize the exact draft against the verified catalog. */
    INVALID_DRAFT,

    /** The coordinator requires device unlock before admitting the mutation. */
    DEVICE_LOCKED,
}

/** The capability bound to one credential generation's coordinator. */
fun interface FilterProfileSwitchPort {
    suspend fun apply(targetProfileId: String): FilterProfileSwitchResult
}

/** Lifecycle gate around the coordinator-backed whole-profile switch capability. */
class FilterProfileSwitchOwner(
    private val port: FilterProfileSwitchPort,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    sealed interface State {
        data object Idle : State

        data class Applying(val targetProfileId: String) : State

        data class Failed(
            val targetProfileId: String,
            val failure: Failure,
        ) : State
    }

    enum class Failure {
        /** The target filter is frozen (over-cap, read-only) and cannot be applied. */
        FROZEN_TARGET,

        /** Any other rejection, commit failure, or supersession; the switch did not land. */
        COMMIT,
    }

    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + dispatcher + ownerJob)
    private val admission = Mutex()
    private val publicationEpoch = AtomicLong(0L)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private val mutableReconstructionRequired = MutableStateFlow(false)

    val state: StateFlow<State> = mutableState.asStateFlow()

    /**
     * Sticky for the owner's lifetime once a [FilterProfileSwitchResult.ProtectionUnavailable]
     * outcome settles: the coordinator's ReloadRequired authority state cannot be cleared by
     * this generation's switches, so the capability stays reconstruction-required and the
     * product host (the ViewModel) rotates the credential graph once per generation to
     * recover the coordinator. The synchronous settle and a detached settle (the applying
     * caller was cancelled) both arm the flag through [settle], so every wedge path is
     * observed by the host.
     */
    val reconstructionRequired: StateFlow<Boolean> = mutableReconstructionRequired.asStateFlow()

    /**
     * Runs one whole-profile switch and reports it through [state]. Returns whether the target
     * profile is authoritative after the call.
     *
     * The in-flight state is published before the (slow) compile/commit so the surface can
     * disable every Apply control honestly; a failed switch retains the failure facts so the
     * user sees which target did not land. A `ProtectionUnavailable` outcome instead returns
     * to Idle and arms [reconstructionRequired]: the authority's ReloadRequired state flips
     * the whole Filters library to its unavailable panel (no controls), which is the honest
     * surface for that outcome, and the product host reconstructs the credential graph from
     * the armed requirement rather than wedging this generation behind its sticky
     * ReloadRequired authority.
     *
     * The port operation runs under the owner's lifecycle job (never the caller's coroutine),
     * so closing the owner while the switch is suspended cancels the compile/commit exactly
     * like [DomainActionOwner] fences its host operations; a graph rotation reconciles
     * anything the owner retirement fenced mid-publication.
     *
     * Caller cancellation (e.g. the Activity is recreated and its `rememberCoroutineScope`
     * dies while this owner survives) does NOT cancel the owned task. Once the operation is
     * admitted inside the owner job, its durable publication must run to completion: aborting
     * it after the filter store has advanced its generation would leave this generation's
     * coordinator one durable generation behind with no recovery path, so the task keeps
     * running under the owner job and settles the state from its actual outcome. The state
     * therefore stays Applying across the caller's death (an apply really is still in
     * flight) and the cancellation is rethrown after detaching.
     */
    suspend fun apply(targetProfileId: String): Boolean {
        if (closed.get()) return false
        return admission.withLock {
            if (closed.get()) return false
            mutableState.value = State.Applying(targetProfileId)
            val epoch = publicationEpoch.incrementAndGet()
            val task = ownerScope.async {
                if (closed.get()) null else port.apply(targetProfileId)
            }
            val result = try {
                task.await()
            } catch (cancelled: CancellationException) {
                if (closed.get()) {
                    // close() fenced the task; the owner is retiring and nothing settled
                    // by the surviving coordinator matters to this surface.
                    settle(targetProfileId, null)
                    return false
                }
                // The caller left while the owned operation is admitted in the owner job.
                // Cancelling the task here could abort the publication between the durable
                // store commit and the coordinator's state adoption (the filter store one
                // generation ahead, this coordinator still on the old one), so the task
                // keeps running under the owner job and settles the state when it
                // completes. close() remains the only fence.
                settleWhenCompleted(epoch, targetProfileId, task)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = State.Failed(targetProfileId, Failure.COMMIT)
                return false
            }
            settle(targetProfileId, result)
        }
    }

    private fun settle(targetProfileId: String, result: FilterProfileSwitchResult?): Boolean =
        when (result) {
            null -> {
                mutableState.value = State.Idle
                false
            }
            is FilterProfileSwitchResult.Applied -> {
                mutableState.value = State.Idle
                true
            }
            is FilterProfileSwitchResult.ProtectionUnavailable -> {
                // The coordinator's ReloadRequired authority state is sticky for this
                // generation's lifetime, so the switch capability cannot recover by itself:
                // arm the reconstruction requirement for the product host instead of
                // pretending an Idle switch is usable again.
                mutableReconstructionRequired.value = true
                mutableState.value = State.Idle
                false
            }
            is FilterProfileSwitchResult.Rejected -> {
                mutableState.value = State.Failed(
                    targetProfileId,
                    if (
                        result.reason ==
                        FilterProfileSwitchRejection.FROZEN_TARGET
                    ) {
                        Failure.FROZEN_TARGET
                    } else {
                        Failure.COMMIT
                    },
                )
                false
            }
            is FilterProfileSwitchResult.Failed,
            is FilterProfileSwitchResult.Superseded,
            -> {
                mutableState.value = State.Failed(targetProfileId, Failure.COMMIT)
                false
            }
        }

    /**
     * Settles the state from an owned task the caller has abandoned. The epoch fence keeps a
     * stale detached outcome from clobbering a newer apply that started after the caller left:
     * only the apply that owns the current [publicationEpoch] may publish.
     */
    private fun settleWhenCompleted(
        epoch: Long,
        targetProfileId: String,
        task: Deferred<FilterProfileSwitchResult?>,
    ) {
        ownerScope.launch {
            val result = try {
                task.await()
            } catch (cancelled: CancellationException) {
                // Either close() fenced the task (the owner is retiring and this surface no
                // longer matters) or the port itself abandoned the operation without a
                // result; in the latter case settle to Idle exactly like the close fence.
                if (epoch == publicationEpoch.get() && !closed.get()) {
                    settle(targetProfileId, null)
                }
                return@launch
            } catch (_: Exception) {
                if (epoch == publicationEpoch.get() && !closed.get()) {
                    mutableState.value = State.Failed(targetProfileId, Failure.COMMIT)
                }
                return@launch
            }
            if (epoch == publicationEpoch.get() && !closed.get()) {
                settle(targetProfileId, result)
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) ownerJob.cancel()
    }
}
