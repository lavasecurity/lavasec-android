package com.lavasec.app.product

import com.lavasec.app.product.domain.DomainAction
import com.lavasec.app.product.domain.DomainActionConfirmResult
import com.lavasec.app.product.domain.DomainActionRequestResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async

data class DomainActionOwnerState(
    val host: NativeDomainActionHostState,
    val review: NativeDomainActionReviewState,
)

/** Lifecycle gate around the existing exact native domain review and mutation host. */
class DomainActionOwner(
    private val host: NativeDomainActionHost,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + dispatcher + ownerJob)
    private val mutableState = MutableStateFlow(
        DomainActionOwnerState(host.stateFlow.value, host.reviewBridge.stateFlow.value),
    )

    val state: StateFlow<DomainActionOwnerState> = mutableState.asStateFlow()

    init {
        ownerScope.launch {
            combine(host.stateFlow, host.reviewBridge.stateFlow, ::DomainActionOwnerState)
                .collect { exact ->
                    if (!closed.get()) mutableState.value = exact
                }
        }
    }

    suspend fun request(domain: String, action: DomainAction): DomainActionRequestResult? =
        owned(null) { host.request(domain, action) }

    suspend fun markReviewVisible(reviewId: Long): Boolean =
        owned(false) { host.markReviewVisible(reviewId) }

    suspend fun cancelBeforeVisible(reviewId: Long): Boolean =
        owned(false) { host.cancelBeforeVisible(reviewId) }

    suspend fun confirm(reviewId: Long): DomainActionConfirmResult? =
        owned(null) { host.confirm(reviewId) }

    suspend fun cancel(reviewId: Long): Boolean =
        owned(false) { host.cancel(reviewId) }

    suspend fun dismissNotice(noticeId: Long): Boolean =
        owned(false) { host.dismissNotice(noticeId) }

    override fun close() {
        if (closed.compareAndSet(false, true)) ownerJob.cancel()
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
