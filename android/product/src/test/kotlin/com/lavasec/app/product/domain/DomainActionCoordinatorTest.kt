package com.lavasec.app.product.domain

import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterProfile
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.filter.ManualRuleRejection
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DomainActionCoordinatorTest {
    @Test
    fun `required authentication cancellation stops before authority and review`() = runTest {
        var authorityCalls = 0
        var reviewCalls = 0
        val coordinator = coordinator(
            authenticator = DomainActionAuthenticator {
                DomainActionAuthenticationResult.Cancelled
            },
            authority = ActiveFilterAuthorityPort {
                authorityCalls += 1
                authority()
            },
            reviewer = DomainActionReviewPresenter {
                reviewCalls += 1
                DomainActionReviewPresentation.Presented
            },
        )

        assertEquals(
            DomainActionRequestResult.AuthenticationCancelled,
            coordinator.request("tracker.example", DomainAction.BLOCK),
        )
        assertEquals(0, authorityCalls)
        assertEquals(0, reviewCalls)
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `skipped authentication stages one normalized frozen review without mutation`() = runTest {
        var reviewed: DomainActionReview? = null
        var mutationCalls = 0
        val coordinator = coordinator(
            authenticationPolicy = DomainActionAuthenticationPolicy.SKIPPED,
            authenticator = DomainActionAuthenticator { error("authentication must be skipped") },
            authority = ActiveFilterAuthorityPort {
                authority(
                    profile = profile(
                        ManualDomainRules(allowedDomains = setOf("xn--bcher-kva.example")),
                    ),
                )
            },
            reviewer = DomainActionReviewPresenter { review ->
                reviewed = review
                DomainActionReviewPresentation.Presented
            },
            mutation = DomainActionMutationPort { _, _ ->
                mutationCalls += 1
                DomainActionApplyResult.Applied(8L)
            },
        )

        val result = coordinator.request("  BÜCHER.Example.  ", DomainAction.BLOCK)
        val staged = result as DomainActionRequestResult.Staged

        assertEquals(staged.review, reviewed)
        assertEquals("xn--bcher-kva.example", staged.review.normalizedDomain)
        assertEquals(DomainAction.BLOCK, staged.review.action)
        assertTrue(staged.review.desiredProfile.manualRules.allowedDomains.isEmpty())
        assertEquals(
            setOf("xn--bcher-kva.example"),
            staged.review.desiredProfile.manualRules.blockedDomains,
        )
        assertEquals(0, mutationCalls)
        assertEquals(DomainActionState.ReviewPending(staged.review), coordinator.state)
    }

    @Test
    fun `cancellation after native review presentation preserves the exact pending review`() =
        runTest {
            var presented: DomainActionReview? = null
            val cancellation = CancellationException("review host left after presentation")
            val coordinator = coordinator(
                reviewer = DomainActionReviewPresenter { review ->
                    presented = review
                    currentCoroutineContext()[Job]!!.cancel(cancellation)
                    DomainActionReviewPresentation.Presented
                },
            )

            val requesting = async {
                coordinator.request("tracker.example", DomainAction.BLOCK)
            }
            runCurrent()
            requesting.cancelAndJoin()

            val exactReview = requireNotNull(presented)
            assertTrue(requesting.isCancelled)
            assertEquals(DomainActionState.ReviewPending(exactReview), coordinator.state)
        }

    @Test
    fun `authentication failure is typed and does not read authority`() = runTest {
        var authorityCalls = 0
        val coordinator = coordinator(
            authenticator = DomainActionAuthenticator {
                DomainActionAuthenticationResult.Failed("unlock unavailable")
            },
            authority = ActiveFilterAuthorityPort {
                authorityCalls += 1
                authority()
            },
        )

        assertEquals(
            DomainActionRequestResult.AuthenticationFailed("unlock unavailable"),
            coordinator.request("tracker.example", DomainAction.ALLOW),
        )
        assertEquals(0, authorityCalls)
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `invalid domain exposes the exact editor rejection and no review`() = runTest {
        var reviewCalls = 0
        val coordinator = coordinator(
            reviewer = DomainActionReviewPresenter {
                reviewCalls += 1
                DomainActionReviewPresentation.Presented
            },
        )

        assertEquals(
            DomainActionRequestResult.Rejected(
                reason = DomainActionRejection.RULE_REJECTED,
                ruleRejection = ManualRuleRejection.INVALID_DOMAIN,
            ),
            coordinator.request("not a domain", DomainAction.ALLOW),
        )
        assertEquals(0, reviewCalls)
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `allow moves a blocked domain into the reviewed allow set`() = runTest {
        val coordinator = coordinator(
            authority = ActiveFilterAuthorityPort {
                authority(
                    profile = profile(
                        ManualDomainRules(blockedDomains = setOf("tracker.example")),
                    ),
                )
            },
        )

        val staged = coordinator.request(
            "TRACKER.EXAMPLE",
            DomainAction.ALLOW,
        ) as DomainActionRequestResult.Staged

        assertEquals(setOf("tracker.example"), staged.review.desiredProfile.manualRules.allowedDomains)
        assertTrue(staged.review.desiredProfile.manualRules.blockedDomains.isEmpty())
    }

    @Test
    fun `remove deletes the existing normalized manual rule from the frozen profile`() = runTest {
        val coordinator = coordinator(
            authority = ActiveFilterAuthorityPort {
                authority(
                    profile = profile(
                        ManualDomainRules(allowedDomains = setOf("tracker.example")),
                    ),
                )
            },
        )

        val staged = coordinator.request(
            ".TRACKER.EXAMPLE.",
            DomainAction.REMOVE,
        ) as DomainActionRequestResult.Staged

        assertEquals("tracker.example", staged.review.normalizedDomain)
        assertEquals(DomainAction.REMOVE, staged.review.action)
        assertTrue(staged.review.desiredProfile.manualRules.allowedDomains.isEmpty())
        assertTrue(staged.review.desiredProfile.manualRules.blockedDomains.isEmpty())
    }

    @Test
    fun `remove rejects a domain absent from both manual rule sets`() = runTest {
        var reviewCalls = 0
        val coordinator = coordinator(
            reviewer = DomainActionReviewPresenter {
                reviewCalls += 1
                DomainActionReviewPresentation.Presented
            },
        )

        assertEquals(
            DomainActionRequestResult.Rejected(DomainActionRejection.RULE_NOT_PRESENT),
            coordinator.request("tracker.example", DomainAction.REMOVE),
        )
        assertEquals(0, reviewCalls)
    }

    @Test
    fun `review cancellation is typed and leaves no pending mutation`() = runTest {
        var mutationCalls = 0
        val coordinator = coordinator(
            reviewer = DomainActionReviewPresenter {
                DomainActionReviewPresentation.Cancelled
            },
            mutation = DomainActionMutationPort { _, _ ->
                mutationCalls += 1
                DomainActionApplyResult.Applied(8L)
            },
        )

        assertEquals(
            DomainActionRequestResult.ReviewCancelled,
            coordinator.request("tracker.example", DomainAction.BLOCK),
        )
        assertEquals(0, mutationCalls)
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `review rejection is typed and leaves no pending mutation`() = runTest {
        val coordinator = coordinator(
            reviewer = DomainActionReviewPresenter {
                DomainActionReviewPresentation.Rejected("review host unavailable")
            },
        )

        assertEquals(
            DomainActionRequestResult.ReviewRejected("review host unavailable"),
            coordinator.request("tracker.example", DomainAction.BLOCK),
        )
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `pending review deduplicates another request before authentication`() = runTest {
        var authenticationCalls = 0
        val coordinator = coordinator(
            authenticator = DomainActionAuthenticator {
                authenticationCalls += 1
                DomainActionAuthenticationResult.Authorized
            },
        )
        val first = coordinator.request(
            "first.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        assertEquals(
            DomainActionRequestResult.ReviewAlreadyPending(first.review),
            coordinator.request("second.example", DomainAction.ALLOW),
        )
        assertEquals(1, authenticationCalls)
    }

    @Test
    fun `cancelled duplicate waiting for transition preserves pending review`() = runTest {
        val coordinator = coordinator()
        val staged = coordinator.request(
            "first.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged
        val transition = privateTransition(coordinator)
        transition.lock()
        val duplicate = async {
            coordinator.request("second.example", DomainAction.ALLOW)
        }
        runCurrent()

        duplicate.cancel()
        transition.unlock()
        withTimeout(1_000L) { duplicate.join() }

        assertTrue(duplicate.isCancelled)
        assertEquals(DomainActionState.ReviewPending(staged.review), coordinator.state)
    }

    @Test
    fun `cancelled duplicate waiting for transition preserves retryable failed review`() = runTest {
        val coordinator = coordinator(
            mutation = DomainActionMutationPort { _, _ ->
                DomainActionApplyResult.Failed("compile failed")
            },
        )
        val staged = coordinator.request(
            "first.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged
        assertEquals(
            DomainActionConfirmResult.Failed("compile failed"),
            coordinator.confirm(staged.review.id),
        )
        val failed = DomainActionState.ApplyFailed(staged.review, "compile failed")
        assertEquals(failed, coordinator.state)
        val transition = privateTransition(coordinator)
        transition.lock()
        val duplicate = async {
            coordinator.request("second.example", DomainAction.ALLOW)
        }
        runCurrent()

        duplicate.cancel()
        transition.unlock()
        withTimeout(1_000L) { duplicate.join() }

        assertTrue(duplicate.isCancelled)
        assertEquals(failed, coordinator.state)
    }

    @Test
    fun `explicit review dismissal consumes only the matching pending review`() = runTest {
        var authenticationCalls = 0
        val coordinator = coordinator(
            authenticator = DomainActionAuthenticator {
                authenticationCalls += 1
                DomainActionAuthenticationResult.Authorized
            },
        )
        val first = coordinator.request(
            "first.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        assertFalse(coordinator.cancel(first.review.id + 1L))
        assertEquals(DomainActionState.ReviewPending(first.review), coordinator.state)
        assertTrue(coordinator.cancel(first.review.id))
        assertEquals(DomainActionState.Idle, coordinator.state)
        assertTrue(
            coordinator.request("second.example", DomainAction.BLOCK) is
                DomainActionRequestResult.Staged,
        )
        assertEquals(2, authenticationCalls)
    }

    @Test
    fun `single flight rejects a duplicate action while authentication is in progress`() = runTest {
        val authenticationEntered = CompletableDeferred<Unit>()
        val releaseAuthentication = CompletableDeferred<DomainActionAuthenticationResult>()
        val coordinator = coordinator(
            authenticator = DomainActionAuthenticator {
                authenticationEntered.complete(Unit)
                releaseAuthentication.await()
            },
        )

        val first = async { coordinator.request("tracker.example", DomainAction.BLOCK) }
        authenticationEntered.await()

        assertEquals(
            DomainActionRequestResult.Busy,
            coordinator.request("tracker.example", DomainAction.BLOCK),
        )
        releaseAuthentication.complete(DomainActionAuthenticationResult.Authorized)
        assertTrue(first.await() is DomainActionRequestResult.Staged)
    }

    @Test
    fun `generation change after review supersedes without applying`() = runTest {
        var current = authority()
        var mutationCalls = 0
        val coordinator = coordinator(
            authority = ActiveFilterAuthorityPort { current },
            mutation = DomainActionMutationPort { _, _ ->
                mutationCalls += 1
                DomainActionApplyResult.Applied(8L)
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged
        current = current.copy(generation = 8L)

        assertEquals(DomainActionConfirmResult.Superseded, coordinator.confirm(staged.review.id))
        assertEquals(0, mutationCalls)
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `same generation with changed source profile supersedes without applying`() = runTest {
        var current = authority()
        var mutationCalls = 0
        val coordinator = coordinator(
            authority = ActiveFilterAuthorityPort { current },
            mutation = DomainActionMutationPort { _, _ ->
                mutationCalls += 1
                DomainActionApplyResult.Applied(8L)
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged
        current = current.copy(
            activeProfile = current.activeProfile.withEnabledSourceIds(setOf("security", "privacy")),
        )

        assertEquals(DomainActionConfirmResult.Superseded, coordinator.confirm(staged.review.id))
        assertEquals(0, mutationCalls)
    }

    @Test
    fun `confirmation applies the exact frozen review and consumes it only after commit`() = runTest {
        var appliedReview: DomainActionReview? = null
        val coordinator = coordinator(
            mutation = DomainActionMutationPort { review, _ ->
                appliedReview = review
                DomainActionApplyResult.Applied(8L)
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        assertEquals(DomainActionConfirmResult.Applied(8L), coordinator.confirm(staged.review.id))
        assertEquals(staged.review, appliedReview)
        assertEquals(DomainActionState.Idle, coordinator.state)
        assertEquals(
            DomainActionConfirmResult.NoPendingReview,
            coordinator.confirm(staged.review.id),
        )
    }

    @Test
    fun `atomic mutation stale result never becomes optimistic success`() = runTest {
        val coordinator = coordinator(
            mutation = DomainActionMutationPort { _, _ -> DomainActionApplyResult.Superseded },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        assertEquals(DomainActionConfirmResult.Superseded, coordinator.confirm(staged.review.id))
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `commit failure keeps the exact review retryable until committed`() = runTest {
        var fail = true
        val coordinator = coordinator(
            mutation = DomainActionMutationPort { _, _ ->
                if (fail) DomainActionApplyResult.Failed("compile failed")
                else DomainActionApplyResult.Applied(8L)
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        assertEquals(
            DomainActionConfirmResult.Failed("compile failed"),
            coordinator.confirm(staged.review.id),
        )
        assertEquals(
            DomainActionState.ApplyFailed(staged.review, "compile failed"),
            coordinator.state,
        )

        fail = false
        assertEquals(DomainActionConfirmResult.Applied(8L), coordinator.confirm(staged.review.id))
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `mutation exception is a commit failure and does not consume the review`() = runTest {
        val coordinator = coordinator(
            mutation = DomainActionMutationPort { _, _ -> throw IllegalStateException("disk full") },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        assertEquals(
            DomainActionConfirmResult.Failed("disk full"),
            coordinator.confirm(staged.review.id),
        )
        assertEquals(
            DomainActionState.ApplyFailed(staged.review, "disk full"),
            coordinator.state,
        )
    }

    @Test
    fun `adapter exception after atomic publication is reconciled without duplicate commit`() =
        runTest {
            var current = authority()
            var mutationCalls = 0
            val coordinator = coordinator(
                authority = ActiveFilterAuthorityPort { current },
                mutation = DomainActionMutationPort { review, _ ->
                    mutationCalls += 1
                    current = current.copy(
                        generation = review.expectedGeneration + 1L,
                        activeProfile = review.desiredProfile,
                    )
                    throw IllegalStateException("acknowledgement lost")
                },
            )
            val staged = coordinator.request(
                "tracker.example",
                DomainAction.BLOCK,
            ) as DomainActionRequestResult.Staged

            assertEquals(
                DomainActionConfirmResult.Applied(8L),
                coordinator.confirm(staged.review.id),
            )
            assertEquals(1, mutationCalls)
            assertEquals(DomainActionState.Idle, coordinator.state)
            assertEquals(
                DomainActionConfirmResult.NoPendingReview,
                coordinator.confirm(staged.review.id),
            )
            assertEquals(1, mutationCalls)
        }

    @Test
    fun `adapter cancellation after atomic publication is reconciled as committed`() = runTest {
        val cancellation = CancellationException("acknowledgement cancelled")
        var current = authority()
        var mutationCalls = 0
        val coordinator = coordinator(
            authority = ActiveFilterAuthorityPort { current },
            mutation = DomainActionMutationPort { review, _ ->
                mutationCalls += 1
                current = current.copy(
                    generation = review.expectedGeneration + 1L,
                    activeProfile = review.desiredProfile,
                )
                throw cancellation
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        assertEquals(
            DomainActionConfirmResult.Applied(8L),
            coordinator.confirm(staged.review.id),
        )
        assertEquals(1, mutationCalls)
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `returned failure after atomic publication is reconciled without duplicate commit`() =
        runTest {
            var current = authority()
            var mutationCalls = 0
            val coordinator = coordinator(
                authority = ActiveFilterAuthorityPort { current },
                mutation = DomainActionMutationPort { review, _ ->
                    mutationCalls += 1
                    current = current.copy(
                        generation = review.expectedGeneration + 1L,
                        activeProfile = review.desiredProfile,
                    )
                    DomainActionApplyResult.Failed("acknowledgement lost")
                },
            )
            val staged = coordinator.request(
                "tracker.example",
                DomainAction.BLOCK,
            ) as DomainActionRequestResult.Staged

            assertEquals(
                DomainActionConfirmResult.Applied(8L),
                coordinator.confirm(staged.review.id),
            )
            assertEquals(1, mutationCalls)
            assertEquals(DomainActionState.Idle, coordinator.state)
            assertEquals(
                DomainActionConfirmResult.NoPendingReview,
                coordinator.confirm(staged.review.id),
            )
            assertEquals(1, mutationCalls)
        }

    @Test
    fun `returned rejection after atomic publication is reconciled without duplicate commit`() =
        runTest {
            var current = authority()
            var mutationCalls = 0
            val coordinator = coordinator(
                authority = ActiveFilterAuthorityPort { current },
                mutation = DomainActionMutationPort { review, _ ->
                    mutationCalls += 1
                    current = current.copy(
                        generation = review.expectedGeneration + 1L,
                        activeProfile = review.desiredProfile,
                    )
                    DomainActionApplyResult.Rejected("late rejection")
                },
            )
            val staged = coordinator.request(
                "tracker.example",
                DomainAction.BLOCK,
            ) as DomainActionRequestResult.Staged

            assertEquals(
                DomainActionConfirmResult.Applied(8L),
                coordinator.confirm(staged.review.id),
            )
            assertEquals(1, mutationCalls)
            assertEquals(DomainActionState.Idle, coordinator.state)
            assertEquals(
                DomainActionConfirmResult.NoPendingReview,
                coordinator.confirm(staged.review.id),
            )
            assertEquals(1, mutationCalls)
        }

    @Test
    fun `returned rejection with unchanged authority stays typed and retryable`() = runTest {
        var reject = true
        val coordinator = coordinator(
            mutation = DomainActionMutationPort { _, _ ->
                if (reject) DomainActionApplyResult.Rejected("policy denied")
                else DomainActionApplyResult.Applied(8L)
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        assertEquals(
            DomainActionConfirmResult.Rejected("policy denied"),
            coordinator.confirm(staged.review.id),
        )
        assertEquals(
            DomainActionState.ApplyFailed(staged.review, "policy denied"),
            coordinator.state,
        )

        reject = false
        assertEquals(DomainActionConfirmResult.Applied(8L), coordinator.confirm(staged.review.id))
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `wrong review id cannot publish or consume the pending review`() = runTest {
        var mutationCalls = 0
        val coordinator = coordinator(
            mutation = DomainActionMutationPort { _, _ ->
                mutationCalls += 1
                DomainActionApplyResult.Applied(8L)
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        assertEquals(
            DomainActionConfirmResult.NoPendingReview,
            coordinator.confirm(staged.review.id + 1L),
        )
        assertEquals(0, mutationCalls)
        assertEquals(DomainActionState.ReviewPending(staged.review), coordinator.state)
    }

    @Test
    fun `generation exhaustion fails before mutation publication`() = runTest {
        var mutationCalls = 0
        val coordinator = coordinator(
            authority = ActiveFilterAuthorityPort { authority(generation = Long.MAX_VALUE) },
            mutation = DomainActionMutationPort { _, _ ->
                mutationCalls += 1
                DomainActionApplyResult.Applied(Long.MAX_VALUE)
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        assertEquals(
            DomainActionConfirmResult.Failed("Active filter generation is exhausted"),
            coordinator.confirm(staged.review.id),
        )
        assertEquals(0, mutationCalls)
    }

    @Test
    fun `structured cancellation during confirmation restores the review`() = runTest {
        val cancellation = CancellationException("review host left")
        var cancel = true
        val coordinator = coordinator(
            mutation = DomainActionMutationPort { _, _ ->
                if (cancel) throw cancellation
                DomainActionApplyResult.Applied(8L)
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        val thrown = runCatching { coordinator.confirm(staged.review.id) }.exceptionOrNull()

        assertTrue(thrown === cancellation)
        assertEquals(DomainActionState.ReviewPending(staged.review), coordinator.state)
        cancel = false
        assertEquals(DomainActionConfirmResult.Applied(8L), coordinator.confirm(staged.review.id))
    }

    @Test
    fun `authority cancellation is observed immediately before synchronous mutation`() = runTest {
        val cancellation = CancellationException("review host left after authority read")
        var authorityReads = 0
        var mutationCalls = 0
        val coordinator = coordinator(
            authority = ActiveFilterAuthorityPort {
                authorityReads += 1
                if (authorityReads == 2) {
                    currentCoroutineContext()[Job]!!.cancel(cancellation)
                }
                authority()
            },
            mutation = DomainActionMutationPort { _, _ ->
                mutationCalls += 1
                DomainActionApplyResult.Applied(8L)
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged

        val confirming = async { coordinator.confirm(staged.review.id) }
        runCurrent()
        confirming.cancelAndJoin()

        assertTrue(confirming.isCancelled)
        assertEquals(2, authorityReads)
        assertEquals(0, mutationCalls)
        assertEquals(DomainActionState.ReviewPending(staged.review), coordinator.state)
    }

    @Test
    fun `caller cancellation joins restores exact review releases slot and permits retry`() =
        runTest {
            val mutationEntered = CompletableDeferred<Unit>()
            var waitForever = true
            val coordinator = coordinator(
                mutation = DomainActionMutationPort { _, _ ->
                    if (waitForever) {
                        mutationEntered.complete(Unit)
                        awaitCancellation()
                    }
                    DomainActionApplyResult.Applied(8L)
                },
            )
            val staged = coordinator.request(
                "tracker.example",
                DomainAction.BLOCK,
            ) as DomainActionRequestResult.Staged
            val confirming = async { coordinator.confirm(staged.review.id) }
            mutationEntered.await()

            withTimeout(1_000L) {
                confirming.cancelAndJoin()
            }

            assertTrue(confirming.isCancelled)
            assertEquals(DomainActionState.ReviewPending(staged.review), coordinator.state)
            waitForever = false
            assertEquals(
                DomainActionConfirmResult.Applied(8L),
                coordinator.confirm(staged.review.id),
            )
            assertEquals(DomainActionState.Idle, coordinator.state)
        }

    @Test
    fun `caller cancellation joins when reconciliation authority read stalls`() = runTest {
        val mutationEntered = CompletableDeferred<Unit>()
        var authorityReads = 0
        var waitForever = true
        val coordinator = coordinator(
            authority = ActiveFilterAuthorityPort {
                authorityReads += 1
                if (authorityReads == 3) awaitCancellation()
                authority()
            },
            mutation = DomainActionMutationPort { _, _ ->
                if (waitForever) {
                    mutationEntered.complete(Unit)
                    awaitCancellation()
                }
                DomainActionApplyResult.Applied(8L)
            },
        )
        val staged = coordinator.request(
            "tracker.example",
            DomainAction.BLOCK,
        ) as DomainActionRequestResult.Staged
        val confirming = async { coordinator.confirm(staged.review.id) }
        mutationEntered.await()

        withTimeout(2_000L) {
            confirming.cancelAndJoin()
        }

        assertTrue(confirming.isCancelled)
        assertEquals(
            DomainActionState.ApplyFailed(
                staged.review,
                "Mutation outcome could not be reconciled safely",
            ),
            coordinator.state,
        )
        waitForever = false
        assertEquals(
            DomainActionConfirmResult.Applied(8L),
            coordinator.confirm(staged.review.id),
        )
        assertEquals(DomainActionState.Idle, coordinator.state)
    }

    @Test
    fun `unsaved active draft is rejected before review`() = runTest {
        var reviewCalls = 0
        val coordinator = coordinator(
            authority = ActiveFilterAuthorityPort { authority(hasUnsavedChanges = true) },
            reviewer = DomainActionReviewPresenter {
                reviewCalls += 1
                DomainActionReviewPresentation.Presented
            },
        )

        assertEquals(
            DomainActionRequestResult.Rejected(
                DomainActionRejection.UNSAVED_ACTIVE_FILTER_DRAFT,
            ),
            coordinator.request("tracker.example", DomainAction.BLOCK),
        )
        assertEquals(0, reviewCalls)
    }

    @Test
    fun `a Plus domain review cannot commit after a Free downgrade without a generation change`() = runTest {
        var tier = FeatureLimits.PLUS
        var commits = 0
        val saved = profile(ManualDomainRules(blockedDomains = (0 until 25).mapTo(linkedSetOf()) { "block-$it.example" }))
        val owner = coordinator(
            authority = ActiveFilterAuthorityPort { authority(profile = saved) },
            limits = { tier },
            mutation = DomainActionMutationPort { _, _ -> commits++; DomainActionApplyResult.Applied(8L) },
        )
        val staged = owner.request("next.example", DomainAction.BLOCK) as DomainActionRequestResult.Staged
        tier = FeatureLimits.FREE
        assertEquals(DomainActionConfirmResult.Superseded, owner.confirm(staged.review.id))
        assertEquals(0, commits)
        assertEquals(25, saved.manualRules.blockedDomains.size)
    }

    @Test
    fun `confirmation uses the exact captured admission passed to publication`() = runTest {
        val admitted = FeatureLimitAdmission(FeatureLimits.PLUS)
        var viewLimits = FeatureLimits.PLUS
        val saved = profile(ManualDomainRules(blockedDomains = (0 until 25).mapTo(linkedSetOf()) { "block-$it.example" }))
        var received: FeatureLimitAdmission? = null
        val owner = coordinator(
            authority = ActiveFilterAuthorityPort { authority(profile = saved) },
            limits = { viewLimits },
            captureAdmission = { admitted },
            mutation = DomainActionMutationPort { _, captured ->
                received = captured
                DomainActionApplyResult.Applied(8L)
            },
        )
        val staged = owner.request("next.example", DomainAction.BLOCK) as DomainActionRequestResult.Staged
        viewLimits = FeatureLimits.FREE
        assertEquals(DomainActionConfirmResult.Applied(8L), owner.confirm(staged.review.id))
        assertSame(admitted, received)
    }

    @Test
    fun `unavailable tier leaves confirmation retryable without a mutation`() = runTest {
        var available = false
        var commits = 0
        val owner = coordinator(
            captureAdmission = { if (available) FeatureLimitAdmission(FeatureLimits.FREE) else null },
            mutation = DomainActionMutationPort { _, _ -> commits++; DomainActionApplyResult.Applied(8L) },
        )
        val staged = owner.request("next.example", DomainAction.BLOCK) as DomainActionRequestResult.Staged
        assertTrue(owner.confirm(staged.review.id) is DomainActionConfirmResult.Failed)
        assertEquals(0, commits)
        available = true
        assertEquals(DomainActionConfirmResult.Applied(8L), owner.confirm(staged.review.id))
        assertEquals(1, commits)
    }

    private fun coordinator(
        authenticationPolicy: DomainActionAuthenticationPolicy =
            DomainActionAuthenticationPolicy.REQUIRED,
        authenticator: DomainActionAuthenticator = DomainActionAuthenticator {
            DomainActionAuthenticationResult.Authorized
        },
        authority: ActiveFilterAuthorityPort = ActiveFilterAuthorityPort { authority() },
        reviewer: DomainActionReviewPresenter = DomainActionReviewPresenter {
            DomainActionReviewPresentation.Presented
        },
        mutation: DomainActionMutationPort = DomainActionMutationPort { _, _ ->
            DomainActionApplyResult.Applied(8L)
        },
        limits: () -> FeatureLimits = { FeatureLimits.FREE },
        captureAdmission: () -> FeatureLimitAdmission? = { FeatureLimitAdmission(limits()) },
    ) = DomainActionCoordinator(
        authenticationPolicy = authenticationPolicy,
        authenticator = authenticator,
        authority = authority,
        reviewer = reviewer,
        mutation = mutation,
        limits = limits,
        captureAdmission = captureAdmission,
    )

    private fun authority(
        generation: Long = 7L,
        profile: FilterProfileDraft = profile(),
        hasUnsavedChanges: Boolean = false,
    ) = ActiveFilterAuthority(
        generation = generation,
        activeProfile = profile,
        hasUnsavedChanges = hasUnsavedChanges,
    )

    private fun profile(rules: ManualDomainRules = ManualDomainRules.EMPTY) = FilterProfileDraft(
        id = FilterProfile.stableId(ProtectionLevel.BALANCED),
        name = FilterProfile.displayName(ProtectionLevel.BALANCED),
        protectionLevel = ProtectionLevel.BALANCED,
        enabledSourceIds = setOf("security"),
        manualRules = rules,
    )

    private fun privateTransition(coordinator: DomainActionCoordinator): Mutex =
        DomainActionCoordinator::class.java
            .getDeclaredField("transition")
            .apply { isAccessible = true }
            .get(coordinator) as Mutex
}
