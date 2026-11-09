package com.lavasec.app.product.domain

import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.foundation.subscription.FeatureLimits

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.domain.DomainValidationException
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.filter.ManualRuleEditResult
import com.lavasec.app.foundation.filter.ManualRuleEditor
import com.lavasec.app.foundation.filter.ManualRuleRejection
import com.lavasec.app.foundation.filter.ManualRuleTarget
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val AUTHORITY_RECONCILIATION_TIMEOUT_MILLIS = 1_000L

enum class DomainAction {
    ALLOW,
    BLOCK,
    REMOVE,
}

enum class DomainActionAuthenticationPolicy {
    REQUIRED,
    SKIPPED,
}

sealed interface DomainActionAuthenticationResult {
    data object Authorized : DomainActionAuthenticationResult
    data object Cancelled : DomainActionAuthenticationResult

    data class Failed(val message: String) : DomainActionAuthenticationResult {
        init {
            require(message.isNotBlank())
        }
    }
}

fun interface DomainActionAuthenticator {
    suspend fun authenticate(): DomainActionAuthenticationResult
}

data class ActiveFilterAuthority(
    val generation: Long,
    val activeProfile: FilterProfileDraft,
    val hasUnsavedChanges: Boolean,
) {
    init {
        require(generation >= 0L)
    }
}

fun interface ActiveFilterAuthorityPort {
    suspend fun read(): ActiveFilterAuthority
}

data class DomainActionReview(
    val id: Long,
    val normalizedDomain: String,
    val action: DomainAction,
    val expectedGeneration: Long,
    val expectedHasUnsavedChanges: Boolean,
    val activeProfileId: String,
    val sourceProfile: FilterProfileDraft,
    val desiredProfile: FilterProfileDraft,
) {
    init {
        require(id > 0L)
        require(normalizedDomain.isNotBlank())
        require(expectedGeneration >= 0L)
        require(!expectedHasUnsavedChanges) {
            "domain actions require a clean active filter authority"
        }
        require(activeProfileId.isNotBlank())
        require(sourceProfile.id == activeProfileId)
        require(desiredProfile.id == activeProfileId)
    }
}

sealed interface DomainActionReviewPresentation {
    /** The native review surface is visible; mutation still requires [DomainActionCoordinator.confirm]. */
    data object Presented : DomainActionReviewPresentation

    data object Cancelled : DomainActionReviewPresentation

    data class Rejected(val message: String) : DomainActionReviewPresentation {
        init {
            require(message.isNotBlank())
        }
    }
}

fun interface DomainActionReviewPresenter {
    suspend fun present(review: DomainActionReview): DomainActionReviewPresentation
}

sealed interface DomainActionApplyResult {
    data class Applied(val newGeneration: Long) : DomainActionApplyResult {
        init {
            require(newGeneration >= 0L)
        }
    }

    data object Superseded : DomainActionApplyResult

    data class Rejected(val message: String) : DomainActionApplyResult {
        init {
            require(message.isNotBlank())
        }
    }

    data class Failed(val message: String) : DomainActionApplyResult {
        init {
            require(message.isNotBlank())
        }
    }
}

/**
 * Applies a review as one generation-bound compare-and-publish transaction.
 *
 * Implementations must compare the authoritative unsaved-change flag, generation, and complete
 * active profile with the review under the same lock or transaction used for publication. Any
 * mismatch returns [DomainActionApplyResult.Superseded] without a partial write. [Applied] means
 * the exact desired profile was durably published at exactly the expected generation plus one.
 * Returned rejection and failure signals are not assumed to prove that publication did not happen;
 * the coordinator reconciles them against the authority before exposing a retryable result.
 */
fun interface DomainActionMutationPort {
    suspend fun apply(review: DomainActionReview, admission: FeatureLimitAdmission): DomainActionApplyResult
}

enum class DomainActionRejection {
    UNSAVED_ACTIVE_FILTER_DRAFT,
    RULE_REJECTED,
    RULE_NOT_PRESENT,
}

sealed interface DomainActionRequestResult {
    data class Staged(val review: DomainActionReview) : DomainActionRequestResult
    data class ReviewAlreadyPending(val review: DomainActionReview) : DomainActionRequestResult

    data class Rejected(
        val reason: DomainActionRejection,
        val ruleRejection: ManualRuleRejection? = null,
    ) : DomainActionRequestResult

    data object AuthenticationCancelled : DomainActionRequestResult
    data object ReviewCancelled : DomainActionRequestResult
    data object Busy : DomainActionRequestResult

    data class AuthenticationFailed(val message: String) : DomainActionRequestResult {
        init {
            require(message.isNotBlank())
        }
    }

    data class ReviewRejected(val message: String) : DomainActionRequestResult {
        init {
            require(message.isNotBlank())
        }
    }

    data class Failed(val message: String) : DomainActionRequestResult {
        init {
            require(message.isNotBlank())
        }
    }
}

sealed interface DomainActionConfirmResult {
    data class Applied(val newGeneration: Long) : DomainActionConfirmResult {
        init {
            require(newGeneration >= 0L)
        }
    }

    data object Superseded : DomainActionConfirmResult
    data object NoPendingReview : DomainActionConfirmResult
    data object Busy : DomainActionConfirmResult

    data class Rejected(val message: String) : DomainActionConfirmResult {
        init {
            require(message.isNotBlank())
        }
    }

    data class Failed(val message: String) : DomainActionConfirmResult {
        init {
            require(message.isNotBlank())
        }
    }
}

sealed interface DomainActionState {
    data object Idle : DomainActionState
    data class ReviewPending(val review: DomainActionReview) : DomainActionState
    data class Applying(val review: DomainActionReview) : DomainActionState

    data class ApplyFailed(
        val review: DomainActionReview,
        val message: String,
    ) : DomainActionState {
        init {
            require(message.isNotBlank())
        }
    }
}

private sealed interface PreparedDomainAction {
    data class Ready(
        val normalizedDomain: String,
        val desiredProfile: FilterProfileDraft,
    ) : PreparedDomainAction

    data class Rejected(
        val reason: DomainActionRejection,
        val ruleRejection: ManualRuleRejection? = null,
    ) : PreparedDomainAction
}

private sealed interface ConfirmationResolution {
    data class Complete(val result: DomainActionConfirmResult) : ConfirmationResolution
    data class Cancelled(val cause: CancellationException) : ConfirmationResolution
}

/**
 * Owns the portable policy for an authenticated Activity-domain mutation.
 *
 * Authentication can only earn an immutable review. The presenter cannot mutate, and a successful
 * presentation remains inert until the native surface explicitly calls [confirm]. Confirmation
 * rechecks the full authority and delegates the final atomic generation fence to [mutation].
 */
class DomainActionCoordinator(
    private val authenticationPolicy: DomainActionAuthenticationPolicy,
    private val authenticator: DomainActionAuthenticator,
    private val authority: ActiveFilterAuthorityPort,
    private val reviewer: DomainActionReviewPresenter,
    private val mutation: DomainActionMutationPort,
    private val limits: () -> FeatureLimits = { FeatureLimits.FREE },
    private val captureAdmission: () -> FeatureLimitAdmission? = { FeatureLimitAdmission(limits()) },
) {
    private val transition = Mutex()
    private val operationInFlight = AtomicBoolean(false)
    private val reviewIds = AtomicLong(0L)

    @Volatile
    private var currentState: DomainActionState = DomainActionState.Idle

    val state: DomainActionState
        get() = currentState

    suspend fun request(
        rawDomain: String,
        action: DomainAction,
    ): DomainActionRequestResult {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        if (!operationInFlight.compareAndSet(false, true)) return DomainActionRequestResult.Busy
        return try {
            transition.withLock { currentState.pendingReviewOrNull() }?.let {
                return DomainActionRequestResult.ReviewAlreadyPending(it)
            }

            if (authenticationPolicy == DomainActionAuthenticationPolicy.REQUIRED) {
                when (val authentication = authenticator.authenticate()) {
                    DomainActionAuthenticationResult.Authorized -> Unit
                    DomainActionAuthenticationResult.Cancelled ->
                        return DomainActionRequestResult.AuthenticationCancelled

                    is DomainActionAuthenticationResult.Failed ->
                        return DomainActionRequestResult.AuthenticationFailed(authentication.message)
                }
                callerContext.ensureActive()
            }

            val snapshot = authority.read()
            callerContext.ensureActive()
            if (snapshot.hasUnsavedChanges) {
                return DomainActionRequestResult.Rejected(
                    DomainActionRejection.UNSAVED_ACTIVE_FILTER_DRAFT,
                )
            }

            when (val prepared = prepare(rawDomain, action, snapshot.activeProfile)) {
                is PreparedDomainAction.Rejected -> DomainActionRequestResult.Rejected(
                    reason = prepared.reason,
                    ruleRejection = prepared.ruleRejection,
                )

                is PreparedDomainAction.Ready -> {
                    val review = DomainActionReview(
                        id = nextReviewId(),
                        normalizedDomain = prepared.normalizedDomain,
                        action = action,
                        expectedGeneration = snapshot.generation,
                        expectedHasUnsavedChanges = snapshot.hasUnsavedChanges,
                        activeProfileId = snapshot.activeProfile.id,
                        sourceProfile = snapshot.activeProfile,
                        desiredProfile = prepared.desiredProfile,
                    )
                    when (val presentation = reviewer.present(review)) {
                        DomainActionReviewPresentation.Presented -> {
                            withContext(NonCancellable) {
                                transition.withLock {
                                    currentState = DomainActionState.ReviewPending(review)
                                }
                            }
                            callerContext.ensureActive()
                            DomainActionRequestResult.Staged(review)
                        }

                        DomainActionReviewPresentation.Cancelled ->
                            DomainActionRequestResult.ReviewCancelled

                        is DomainActionReviewPresentation.Rejected ->
                            DomainActionRequestResult.ReviewRejected(presentation.message)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DomainActionRequestResult.Failed(failure.safeMessage("Domain action failed"))
        } finally {
            operationInFlight.set(false)
        }
    }

    suspend fun confirm(reviewId: Long): DomainActionConfirmResult {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        if (!operationInFlight.compareAndSet(false, true)) return DomainActionConfirmResult.Busy
        var review: DomainActionReview? = null
        var transactionResolved = false
        return try {
            review = transition.withLock {
                currentState.pendingReviewOrNull()
                    ?.takeIf { it.id == reviewId }
                    ?.also {
                        callerContext.ensureActive()
                        currentState = DomainActionState.Applying(it)
                    }
            }
            val ownedReview = review ?: return DomainActionConfirmResult.NoPendingReview
            val resolution = resolveConfirmedReview(ownedReview)
            if (resolution is ConfirmationResolution.Cancelled) throw resolution.cause
            val result = (resolution as ConfirmationResolution.Complete).result
            transactionResolved = true
            callerContext.ensureActive()
            result
        } catch (cancelled: CancellationException) {
            if (!transactionResolved) review?.let { restoreReviewAfterCancellation(it) }
            throw cancelled
        } catch (failure: Exception) {
            review?.let { failReview(it, failure.safeMessage("Domain action failed")) }
                ?: DomainActionConfirmResult.Failed(failure.safeMessage("Domain action failed"))
        } finally {
            operationInFlight.set(false)
        }
    }

    suspend fun cancel(reviewId: Long): Boolean {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        if (!operationInFlight.compareAndSet(false, true)) return false
        return try {
            transition.withLock {
                val pending = currentState.pendingReviewOrNull()
                if (pending?.id != reviewId) {
                    false
                } else {
                    callerContext.ensureActive()
                    currentState = DomainActionState.Idle
                    true
                }
            }
        } finally {
            operationInFlight.set(false)
        }
    }

    private suspend fun resolveConfirmedReview(
        review: DomainActionReview,
    ): ConfirmationResolution {
        val snapshot = authority.read()
        if (
            snapshot.hasUnsavedChanges != review.expectedHasUnsavedChanges ||
            snapshot.generation != review.expectedGeneration ||
            snapshot.activeProfile.id != review.activeProfileId ||
            snapshot.activeProfile != review.sourceProfile
        ) {
            transition.withLock { currentState = DomainActionState.Idle }
            return ConfirmationResolution.Complete(DomainActionConfirmResult.Superseded)
        }
        // A plan can change without changing the filter generation. Recheck native limits
        // at confirmation; a Plus-era review cannot add a Free-over-limit entry after lapse.
        val admitted = captureAdmission() ?: return ConfirmationResolution.Complete(
            failReview(review, "Plan verification is temporarily unavailable"),
        )
        val currentEdit = prepare(review.normalizedDomain, review.action, snapshot.activeProfile, admitted.limits)
        if (currentEdit !is PreparedDomainAction.Ready || currentEdit.desiredProfile != review.desiredProfile) {
            transition.withLock { currentState = DomainActionState.Idle }
            return ConfirmationResolution.Complete(DomainActionConfirmResult.Superseded)
        }
        val expectedNextGeneration = try {
            Math.addExact(review.expectedGeneration, 1L)
        } catch (_: ArithmeticException) {
            return ConfirmationResolution.Complete(
                failReview(review, "Active filter generation is exhausted"),
            )
        }

        currentCoroutineContext().ensureActive()
        val returned = try {
            mutation.apply(review, admitted)
        } catch (cancelled: CancellationException) {
            return reconcileAmbiguousResolution(review, expectedNextGeneration)
                ?: ConfirmationResolution.Cancelled(cancelled)
        } catch (failure: Exception) {
            return reconcileAmbiguousResolution(review, expectedNextGeneration)
                ?: ConfirmationResolution.Complete(
                    failReview(review, failure.safeMessage("Domain action failed")),
                )
        }
        return resolveReturnedMutationResult(review, expectedNextGeneration, returned)
    }

    /** Finalize only the already-returned mutation outcome without shielding the mutation itself. */
    private suspend fun resolveReturnedMutationResult(
        review: DomainActionReview,
        expectedNextGeneration: Long,
        returned: DomainActionApplyResult,
    ): ConfirmationResolution = withContext(NonCancellable) {
        val reconciled = when (returned) {
            is DomainActionApplyResult.Rejected,
            is DomainActionApplyResult.Failed ->
                reconcileAmbiguousMutation(review, expectedNextGeneration) ?: returned

            is DomainActionApplyResult.Applied,
            DomainActionApplyResult.Superseded -> returned
        }
        ConfirmationResolution.Complete(
            resolveApplyResult(review, expectedNextGeneration, reconciled),
        )
    }

    /** Reconcile one thrown acknowledgement signal in a bounded non-cancellable section. */
    private suspend fun reconcileAmbiguousResolution(
        review: DomainActionReview,
        expectedNextGeneration: Long,
    ): ConfirmationResolution? = withContext(NonCancellable) {
        reconcileAmbiguousMutation(review, expectedNextGeneration)?.let { reconciled ->
            ConfirmationResolution.Complete(
                resolveApplyResult(review, expectedNextGeneration, reconciled),
            )
        }
    }

    private suspend fun resolveApplyResult(
        review: DomainActionReview,
        expectedNextGeneration: Long,
        result: DomainActionApplyResult,
    ): DomainActionConfirmResult = when (result) {
        is DomainActionApplyResult.Applied -> {
            if (result.newGeneration != expectedNextGeneration) {
                failReview(review, "Mutation published an unexpected generation")
            } else {
                transition.withLock { currentState = DomainActionState.Idle }
                DomainActionConfirmResult.Applied(result.newGeneration)
            }
        }

        DomainActionApplyResult.Superseded -> {
            transition.withLock { currentState = DomainActionState.Idle }
            DomainActionConfirmResult.Superseded
        }

        is DomainActionApplyResult.Rejected -> failReview(review, result.message, rejected = true)
        is DomainActionApplyResult.Failed -> failReview(review, result.message)
    }

    /**
     * An adapter can lose or negatively acknowledge success after atomic publication. Re-read
     * authority before deciding whether the exact review committed; blindly retrying would
     * duplicate a generation-bound mutation.
     */
    private suspend fun reconcileAmbiguousMutation(
        review: DomainActionReview,
        expectedNextGeneration: Long,
    ): DomainActionApplyResult? = try {
        withTimeout(AUTHORITY_RECONCILIATION_TIMEOUT_MILLIS) {
            val snapshot = authority.read()
            when {
                snapshot.hasUnsavedChanges == review.expectedHasUnsavedChanges &&
                    snapshot.generation == expectedNextGeneration &&
                    snapshot.activeProfile == review.desiredProfile ->
                    DomainActionApplyResult.Applied(expectedNextGeneration)

                snapshot.hasUnsavedChanges == review.expectedHasUnsavedChanges &&
                    snapshot.generation == review.expectedGeneration &&
                    snapshot.activeProfile == review.sourceProfile -> null

                else -> DomainActionApplyResult.Failed(
                    "Mutation outcome could not be reconciled safely",
                )
            }
        }
    } catch (_: Exception) {
        DomainActionApplyResult.Failed("Mutation outcome could not be reconciled safely")
    }

    private fun prepare(
        rawDomain: String,
        action: DomainAction,
        profile: FilterProfileDraft,
        currentLimits: FeatureLimits = limits(),
    ): PreparedDomainAction = when (action) {
        DomainAction.ALLOW -> prepareAdd(rawDomain, ManualRuleTarget.ALLOWED, profile, currentLimits)
        DomainAction.BLOCK -> prepareAdd(rawDomain, ManualRuleTarget.BLOCKED, profile, currentLimits)
        DomainAction.REMOVE -> prepareRemove(rawDomain, profile)
    }

    private fun prepareAdd(
        rawDomain: String,
        target: ManualRuleTarget,
        profile: FilterProfileDraft,
        currentLimits: FeatureLimits,
    ): PreparedDomainAction = when (val edit = ManualRuleEditor.add(rawDomain, target, profile, currentLimits)) {
        is ManualRuleEditResult.Rejected -> PreparedDomainAction.Rejected(
            reason = DomainActionRejection.RULE_REJECTED,
            ruleRejection = edit.reason,
        )

        is ManualRuleEditResult.Applied -> PreparedDomainAction.Ready(
            normalizedDomain = edit.normalizedDomain,
            desiredProfile = edit.profile,
        )
    }

    private fun prepareRemove(
        rawDomain: String,
        profile: FilterProfileDraft,
    ): PreparedDomainAction {
        val normalized = try {
            DomainName.parse(rawDomain).value
        } catch (_: DomainValidationException) {
            return PreparedDomainAction.Rejected(
                reason = DomainActionRejection.RULE_REJECTED,
                ruleRejection = ManualRuleRejection.INVALID_DOMAIN,
            )
        }
        val rules = profile.manualRules
        val desiredRules = when (normalized) {
            in rules.allowedDomains -> ManualDomainRules(
                allowedDomains = rules.allowedDomains - normalized,
                blockedDomains = rules.blockedDomains,
            )

            in rules.blockedDomains -> ManualDomainRules(
                allowedDomains = rules.allowedDomains,
                blockedDomains = rules.blockedDomains - normalized,
            )

            else -> return PreparedDomainAction.Rejected(
                DomainActionRejection.RULE_NOT_PRESENT,
            )
        }
        return PreparedDomainAction.Ready(
            normalizedDomain = normalized,
            desiredProfile = profile.withManualRules(desiredRules),
        )
    }

    private suspend fun failReview(
        review: DomainActionReview,
        message: String,
        rejected: Boolean = false,
    ): DomainActionConfirmResult {
        transition.withLock {
            currentState = DomainActionState.ApplyFailed(review, message)
        }
        return if (rejected) {
            DomainActionConfirmResult.Rejected(message)
        } else {
            DomainActionConfirmResult.Failed(message)
        }
    }

    private suspend fun restoreReviewAfterCancellation(review: DomainActionReview) {
        withContext(NonCancellable) {
            transition.withLock {
                if (currentState == DomainActionState.Applying(review)) {
                    currentState = DomainActionState.ReviewPending(review)
                }
            }
        }
    }

    private fun nextReviewId(): Long {
        val next = reviewIds.incrementAndGet()
        check(next > 0L) { "domain action review id exhausted" }
        return next
    }

    private fun DomainActionState.pendingReviewOrNull(): DomainActionReview? = when (this) {
        DomainActionState.Idle -> null
        is DomainActionState.ReviewPending -> review
        is DomainActionState.Applying -> review
        is DomainActionState.ApplyFailed -> review
    }

    private fun Exception.safeMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank) ?: fallback
}
