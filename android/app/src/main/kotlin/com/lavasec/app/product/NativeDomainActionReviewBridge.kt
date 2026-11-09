package com.lavasec.app.product

import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.foundation.subscription.FeatureLimits

import com.lavasec.app.product.domain.ActiveFilterAuthorityPort
import com.lavasec.app.product.domain.DomainAction
import com.lavasec.app.product.domain.DomainActionAuthenticationPolicy
import com.lavasec.app.product.domain.DomainActionAuthenticator
import com.lavasec.app.product.domain.DomainActionConfirmResult
import com.lavasec.app.product.domain.DomainActionCoordinator
import com.lavasec.app.product.domain.DomainActionMutationPort
import com.lavasec.app.product.domain.DomainActionRequestResult
import com.lavasec.app.product.domain.DomainActionReview
import com.lavasec.app.product.domain.DomainActionReviewPresentation
import com.lavasec.app.product.domain.DomainActionReviewPresenter
import com.lavasec.app.product.domain.DomainActionState
import com.lavasec.app.presentation.activity.DomainActionNoticeContentUi
import com.lavasec.app.presentation.activity.DomainActionNoticeUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface NativeDomainActionReviewState {
    data object Idle : NativeDomainActionReviewState
    data class AwaitingVisibility(val review: DomainActionReview) : NativeDomainActionReviewState
    data class Handoff(val review: DomainActionReview) : NativeDomainActionReviewState
}

class NativeDomainActionReviewBridge : DomainActionReviewPresenter {
    private data class Pending(
        val review: DomainActionReview,
        val completion: CompletableDeferred<DomainActionReviewPresentation>,
        val handoff: Boolean,
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<NativeDomainActionReviewState>(NativeDomainActionReviewState.Idle)
    private var pending: Pending? = null

    val stateFlow: StateFlow<NativeDomainActionReviewState> = mutableState.asStateFlow()

    override suspend fun present(review: DomainActionReview): DomainActionReviewPresentation {
        val completion = mutex.withLock {
            if (pending != null) return DomainActionReviewPresentation.Rejected(
                "Another domain review is already pending.",
            )
            CompletableDeferred<DomainActionReviewPresentation>().also {
                pending = Pending(review, it, handoff = false)
                mutableState.value = NativeDomainActionReviewState.AwaitingVisibility(review)
            }
        }
        return try {
            completion.await()
        } catch (cancelled: CancellationException) {
            val completedAfterHandoff = withContext(NonCancellable) {
                mutex.withLock {
                    val current = pending
                    if (current?.completion === completion && !current.handoff) {
                        pending = null
                        mutableState.value = NativeDomainActionReviewState.Idle
                    }
                    current?.completion === completion && current.handoff
                }
            }
            if (completedAfterHandoff) DomainActionReviewPresentation.Presented else throw cancelled
        }
    }

    suspend fun markVisible(reviewId: Long): Boolean = mutex.withLock {
        val current = pending ?: return false
        if (current.review.id != reviewId || current.handoff) return false
        pending = current.copy(handoff = true)
        mutableState.value = NativeDomainActionReviewState.Handoff(current.review)
        current.completion.complete(DomainActionReviewPresentation.Presented)
    }

    suspend fun cancelBeforeVisible(reviewId: Long): Boolean = mutex.withLock {
        val current = pending ?: return false
        if (current.review.id != reviewId || current.handoff) return false
        pending = null
        mutableState.value = NativeDomainActionReviewState.Idle
        current.completion.complete(DomainActionReviewPresentation.Cancelled)
    }

    internal suspend fun completeHandoff(reviewId: Long): Boolean = mutex.withLock {
        val current = pending ?: return false
        if (current.review.id != reviewId || !current.handoff) return false
        pending = null
        mutableState.value = NativeDomainActionReviewState.Idle
        true
    }
}

data class NativeDomainActionHostState(
    val coordinatorState: DomainActionState,
    val notice: DomainActionNoticeUi?,
)

class NativeDomainActionHost(
    authenticationPolicy: DomainActionAuthenticationPolicy,
    authenticator: DomainActionAuthenticator,
    authority: ActiveFilterAuthorityPort,
    mutation: DomainActionMutationPort,
    limits: () -> FeatureLimits = { FeatureLimits.FREE },
    captureAdmission: () -> FeatureLimitAdmission? = { FeatureLimitAdmission(limits()) },
) {
    val reviewBridge = NativeDomainActionReviewBridge()
    private val coordinator = DomainActionCoordinator(
        authenticationPolicy = authenticationPolicy,
        authenticator = authenticator,
        authority = authority,
        reviewer = reviewBridge,
        mutation = mutation,
        limits = limits,
        captureAdmission = captureAdmission,
    )
    private val admission = Mutex()
    private val terminal = Mutex()
    private var inFlight = false
    private var nextNoticeId = 0L
    private val mutableState = MutableStateFlow(
        NativeDomainActionHostState(DomainActionState.Idle, null),
    )

    val stateFlow: StateFlow<NativeDomainActionHostState> = mutableState.asStateFlow()

    suspend fun request(domain: String, action: DomainAction): DomainActionRequestResult {
        if (!admit()) {
            publishBusy()
            return DomainActionRequestResult.Busy
        }
        return try {
            val result = coordinator.request(domain, action)
            reconcile(ProductActivityUiMapper.requestNotice(result))
            result
        } catch (cancelled: CancellationException) {
            reconcile(null)
            throw cancelled
        } finally {
            release()
        }
    }

    suspend fun markReviewVisible(reviewId: Long): Boolean = reviewBridge.markVisible(reviewId)

    suspend fun cancelBeforeVisible(reviewId: Long): Boolean = reviewBridge.cancelBeforeVisible(reviewId)

    suspend fun confirm(reviewId: Long): DomainActionConfirmResult {
        if (!admit()) {
            publishBusy()
            return DomainActionConfirmResult.Busy
        }
        return try {
            val retryable = when (val current = coordinator.state) {
                is DomainActionState.ReviewPending -> current.review
                is DomainActionState.ApplyFailed -> current.review
                DomainActionState.Idle,
                is DomainActionState.Applying,
                -> null
            }
            retryable?.takeIf { it.id == reviewId }
                ?.let { publish(coordinatorState = DomainActionState.Applying(it), notice = null) }
            val result = coordinator.confirm(reviewId)
            reconcile(ProductActivityUiMapper.confirmNotice(result))
            result
        } catch (cancelled: CancellationException) {
            reconcile(null)
            throw cancelled
        } finally {
            release()
        }
    }

    suspend fun cancel(reviewId: Long): Boolean {
        if (!admit()) {
            publishBusy()
            return false
        }
        return try {
            val result = coordinator.cancel(reviewId)
            reconcile(null)
            result
        } catch (cancelled: CancellationException) {
            reconcile(null)
            throw cancelled
        } finally {
            release()
        }
    }

    suspend fun dismissNotice(noticeId: Long): Boolean = terminal.withLock {
        val current = mutableState.value
        if (current.notice?.id != noticeId) return@withLock false
        mutableState.value = current.copy(notice = null)
        true
    }

    private suspend fun admit(): Boolean = admission.withLock {
        if (inFlight) false else true.also { inFlight = true }
    }

    private suspend fun release() = withContext(NonCancellable) {
        admission.withLock { inFlight = false }
    }

    private suspend fun publishBusy() = terminal.withLock {
        val previous = mutableState.value
        nextNoticeId = Math.incrementExact(nextNoticeId)
        mutableState.value = previous.copy(
            notice = DomainActionNoticeUi(nextNoticeId, DomainActionNoticeContentUi.Busy),
        )
    }

    /** Snapshot first, then clear only the matching handoff after coordinator publication. */
    private suspend fun reconcile(notice: DomainActionNoticeContentUi?) = withContext(NonCancellable) {
        publish(coordinatorState = coordinator.state, notice = notice)
        val handoff = reviewBridge.stateFlow.value as? NativeDomainActionReviewState.Handoff
        handoff?.let { reviewBridge.completeHandoff(it.review.id) }
    }

    private suspend fun publish(
        coordinatorState: DomainActionState,
        notice: DomainActionNoticeContentUi?,
    ) = terminal.withLock {
        val previous = mutableState.value
        val nextNotice = notice?.let {
            nextNoticeId = Math.incrementExact(nextNoticeId)
            DomainActionNoticeUi(nextNoticeId, it)
        } ?: previous.notice
        mutableState.value = NativeDomainActionHostState(coordinatorState, nextNotice)
    }
}
