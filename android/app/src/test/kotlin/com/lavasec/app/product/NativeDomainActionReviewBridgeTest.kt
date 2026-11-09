package com.lavasec.app.product

import com.lavasec.app.product.domain.DomainActionReviewPresentation
import com.lavasec.app.product.domain.DomainAction
import com.lavasec.app.product.domain.DomainActionReview
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterProfileDraft
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.collect
import com.lavasec.app.product.domain.ActiveFilterAuthority
import com.lavasec.app.product.domain.ActiveFilterAuthorityPort
import com.lavasec.app.product.domain.DomainActionAuthenticationPolicy
import com.lavasec.app.product.domain.DomainActionAuthenticator
import com.lavasec.app.product.domain.DomainActionAuthenticationResult
import com.lavasec.app.product.domain.DomainActionApplyResult
import com.lavasec.app.product.domain.DomainActionMutationPort
import com.lavasec.app.product.domain.DomainActionConfirmResult
import com.lavasec.app.product.domain.DomainActionCoordinator
import com.lavasec.app.product.domain.DomainActionRequestResult
import com.lavasec.app.product.domain.DomainActionState
import com.lavasec.app.presentation.activity.DomainActionNoticeContentUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeDomainActionReviewBridgeTest {
    @Test
    fun `cancelled request releases admission after an admission-lock interleaving`() = runTest {
        val profile = testProfile()
        val host = NativeDomainActionHost(
            authenticationPolicy = DomainActionAuthenticationPolicy.SKIPPED,
            authenticator = DomainActionAuthenticator { DomainActionAuthenticationResult.Authorized },
            authority = ActiveFilterAuthorityPort { ActiveFilterAuthority(0L, profile, false) },
            mutation = DomainActionMutationPort { _, _ -> DomainActionApplyResult.Applied(1L) },
        )
        val first = launch { host.request("first.example", DomainAction.BLOCK) }
        testScheduler.runCurrent()
        val admission = NativeDomainActionHost::class.java.getDeclaredField("admission")
            .also { it.isAccessible = true }.get(host) as Mutex
        admission.lock()

        first.cancel()
        testScheduler.runCurrent()
        admission.unlock()
        first.join()

        val second = async { host.request("second.example", DomainAction.BLOCK) }
        testScheduler.runCurrent()
        val awaiting = host.reviewBridge.stateFlow.value as NativeDomainActionReviewState.AwaitingVisibility
        assertEquals(2L, awaiting.review.id)
        assertEquals("second.example", awaiting.review.normalizedDomain)
        assertEquals(true, host.cancelBeforeVisible(2L))
        second.await()
    }

    @Test
    fun `stale visibility acknowledgement changes nothing`() = runTest {
        val bridge = NativeDomainActionReviewBridge()

        assertEquals(false, bridge.markVisible(1L))
        assertEquals(false, bridge.cancelBeforeVisible(1L))
        assertEquals(false, bridge.completeHandoff(1L))
        assertEquals(NativeDomainActionReviewState.Idle, bridge.stateFlow.value)
    }

    @Test
    fun `visibility hands off exact review and presents caller`() = runTest {
        val bridge = NativeDomainActionReviewBridge()
        val caller = async { bridge.present(testReview(4L)) }
        testScheduler.runCurrent()
        assertEquals(4L, (bridge.stateFlow.value as NativeDomainActionReviewState.AwaitingVisibility).review.id)
        assertTrue(bridge.markVisible(4L))
        assertEquals(4L, (bridge.stateFlow.value as NativeDomainActionReviewState.Handoff).review.id)
        assertEquals(DomainActionReviewPresentation.Presented, caller.await())
        assertFalse(bridge.markVisible(4L))
        assertFalse(bridge.cancelBeforeVisible(4L))
        assertTrue(bridge.completeHandoff(4L))
        assertEquals(NativeDomainActionReviewState.Idle, bridge.stateFlow.value)
    }

    @Test
    fun `exact pre-visible cancellation returns cancelled and stale ids preserve awaiting`() = runTest {
        val bridge = NativeDomainActionReviewBridge()
        val caller = async { bridge.present(testReview(5L)) }
        testScheduler.runCurrent()
        assertFalse(bridge.cancelBeforeVisible(6L))
        assertEquals(5L, (bridge.stateFlow.value as NativeDomainActionReviewState.AwaitingVisibility).review.id)
        assertTrue(bridge.cancelBeforeVisible(5L))
        assertEquals(DomainActionReviewPresentation.Cancelled, caller.await())
        assertEquals(NativeDomainActionReviewState.Idle, bridge.stateFlow.value)
    }

    @Test
    fun `caller cancellation before visibility clears only its exact pending review`() = runTest {
        val bridge = NativeDomainActionReviewBridge()
        val first = launch { bridge.present(testReview(7L)) }
        testScheduler.runCurrent()
        first.cancelAndJoin()
        assertEquals(NativeDomainActionReviewState.Idle, bridge.stateFlow.value)
        val second = async { bridge.present(testReview(8L)) }
        testScheduler.runCurrent()
        assertEquals(8L, (bridge.stateFlow.value as NativeDomainActionReviewState.AwaitingVisibility).review.id)
        assertTrue(bridge.cancelBeforeVisible(8L))
        assertEquals(DomainActionReviewPresentation.Cancelled, second.await())
    }

    @Test
    fun `caller cancellation clears awaiting review despite stale callback mutex contention`() = runTest {
        val bridge = NativeDomainActionReviewBridge()
        val presenter = launch { bridge.present(testReview(9L)) }
        testScheduler.runCurrent()
        val bridgeMutex = privateBridgeMutex(bridge)
        bridgeMutex.lock()
        val staleVisibility = async { bridge.markVisible(99L) }
        testScheduler.runCurrent()

        presenter.cancel()
        testScheduler.runCurrent()
        bridgeMutex.unlock()
        assertFalse(staleVisibility.await())
        presenter.join()
        testScheduler.runCurrent()

        assertEquals(NativeDomainActionReviewState.Idle, bridge.stateFlow.value)
        val second = async { bridge.present(testReview(10L)) }
        testScheduler.runCurrent()
        assertEquals(
            10L,
            (bridge.stateFlow.value as NativeDomainActionReviewState.AwaitingVisibility).review.id,
        )
        assertTrue(bridge.cancelBeforeVisible(10L))
        assertEquals(DomainActionReviewPresentation.Cancelled, second.await())
    }

    @Test
    fun `only one review may await visibility`() = runTest {
        val bridge = NativeDomainActionReviewBridge()
        val first = async { bridge.present(testReview(id = 1L)) }
        testScheduler.runCurrent()

        assertEquals(
            DomainActionReviewPresentation.Rejected("Another domain review is already pending."),
            bridge.present(testReview(id = 2L)),
        )
        assertEquals(true, bridge.cancelBeforeVisible(1L))
        assertEquals(DomainActionReviewPresentation.Cancelled, first.await())
    }

    @Test
    fun `host request handoff publishes pending then exact cancel owns terminal state`() = runTest {
        val fixture = hostFixture()
        val request = async { fixture.host.request("track.example", DomainAction.BLOCK) }
        testScheduler.runCurrent()
        val review = (fixture.host.reviewBridge.stateFlow.value as NativeDomainActionReviewState.AwaitingVisibility).review
        assertTrue(fixture.host.markReviewVisible(review.id))
        assertEquals(DomainActionRequestResult.Staged(review), request.await())
        assertEquals(DomainActionState.ReviewPending(review), fixture.host.stateFlow.value.coordinatorState)
        assertEquals(NativeDomainActionReviewState.Idle, fixture.host.reviewBridge.stateFlow.value)
        assertFalse(fixture.host.cancel(review.id + 1L))
        assertEquals(DomainActionState.ReviewPending(review), fixture.host.stateFlow.value.coordinatorState)
        assertTrue(fixture.host.cancel(review.id))
        assertEquals(DomainActionState.Idle, fixture.host.stateFlow.value.coordinatorState)
    }

    @Test
    fun `host confirm immediately publishes applying and suppresses duplicate operations`() = runTest {
        val gate = CompletableDeferred<DomainActionApplyResult>()
        val fixture = hostFixture(mutation = { gate.await() })
        val review = stage(fixture)
        val confirm = async { fixture.host.confirm(review.id) }
        testScheduler.runCurrent()
        assertEquals(DomainActionState.Applying(review), fixture.host.stateFlow.value.coordinatorState)
        assertEquals(2, fixture.authorityCalls)
        assertEquals(DomainActionRequestResult.Busy, fixture.host.request("other.example", DomainAction.BLOCK))
        assertEquals(DomainActionConfirmResult.Busy, fixture.host.confirm(review.id))
        assertFalse(fixture.host.cancel(review.id))
        assertEquals(2, fixture.authorityCalls)
        assertEquals(1, fixture.mutationCalls)
        gate.complete(DomainActionApplyResult.Applied(1L))
        assertEquals(DomainActionConfirmResult.Applied(1L), confirm.await())
        assertEquals(DomainActionState.Idle, fixture.host.stateFlow.value.coordinatorState)
    }

    @Test
    fun `busy operations cannot regress applying before coordinator transition`() = runTest {
        val mutationGate = CompletableDeferred<DomainActionApplyResult>()
        val fixture = hostFixture(mutation = { mutationGate.await() })
        val review = stage(fixture)
        val transition = privateCoordinatorTransition(fixture.host)
        transition.lock()
        val confirmation = async { fixture.host.confirm(review.id) }
        testScheduler.runCurrent()
        val publishedWhileRejected = mutableListOf<DomainActionState>()

        val duplicateRequest = fixture.host.request("other.example", DomainAction.BLOCK)
        publishedWhileRejected += fixture.host.stateFlow.value.coordinatorState
        val duplicateConfirm = fixture.host.confirm(review.id)
        publishedWhileRejected += fixture.host.stateFlow.value.coordinatorState
        val duplicateCancel = fixture.host.cancel(review.id)
        publishedWhileRejected += fixture.host.stateFlow.value.coordinatorState

        transition.unlock()
        testScheduler.runCurrent()
        val mutationCallsWhileApplying = fixture.mutationCalls
        mutationGate.complete(DomainActionApplyResult.Applied(1L))
        val confirmationResult = confirmation.await()

        assertEquals(DomainActionRequestResult.Busy, duplicateRequest)
        assertEquals(DomainActionConfirmResult.Busy, duplicateConfirm)
        assertFalse(duplicateCancel)
        assertEquals(
            listOf(
                DomainActionState.Applying(review),
                DomainActionState.Applying(review),
                DomainActionState.Applying(review),
            ),
            publishedWhileRejected,
        )
        assertEquals(1, mutationCallsWhileApplying)
        assertEquals(1, fixture.mutationCalls)
        assertEquals(DomainActionConfirmResult.Applied(1L), confirmationResult)
        assertEquals(DomainActionState.Idle, fixture.host.stateFlow.value.coordinatorState)
    }

    @Test
    fun `host retries ApplyFailed and publishes applying before retry terminal result`() = runTest {
        var attempt = 0
        val retryGate = CompletableDeferred<DomainActionApplyResult>()
        val fixture = hostFixture(mutation = {
            attempt += 1
            if (attempt == 1) DomainActionApplyResult.Rejected("retry") else retryGate.await()
        })
        val review = stage(fixture)
        assertEquals(DomainActionConfirmResult.Rejected("retry"), fixture.host.confirm(review.id))
        assertEquals(DomainActionState.ApplyFailed(review, "retry"), fixture.host.stateFlow.value.coordinatorState)
        val retry = async { fixture.host.confirm(review.id) }
        testScheduler.runCurrent()
        assertEquals(DomainActionState.Applying(review), fixture.host.stateFlow.value.coordinatorState)
        retryGate.complete(DomainActionApplyResult.Applied(1L))
        assertEquals(DomainActionConfirmResult.Applied(1L), retry.await())
        assertEquals(DomainActionState.Idle, fixture.host.stateFlow.value.coordinatorState)
    }

    @Test
    fun `confirmation cancellation restores exact pending review and releases host admission`() = runTest {
        val gate = CompletableDeferred<DomainActionApplyResult>()
        val fixture = hostFixture(mutation = { gate.await() })
        val review = stage(fixture)
        val confirmation = launch { fixture.host.confirm(review.id) }
        testScheduler.runCurrent()
        assertEquals(DomainActionState.Applying(review), fixture.host.stateFlow.value.coordinatorState)
        confirmation.cancelAndJoin()
        assertEquals(DomainActionState.ReviewPending(review), fixture.host.stateFlow.value.coordinatorState)
        assertTrue(fixture.host.cancel(review.id))
        assertEquals(DomainActionState.Idle, fixture.host.stateFlow.value.coordinatorState)
    }

    @Test
    fun `request cancellation after handoff publishes pending and never resurrects bridge handoff`() = runTest {
        val fixture = hostFixture()
        val request = async { fixture.host.request("cancel.example", DomainAction.BLOCK) }
        testScheduler.runCurrent()
        val review = (fixture.host.reviewBridge.stateFlow.value as NativeDomainActionReviewState.AwaitingVisibility).review
        assertTrue(fixture.host.markReviewVisible(review.id))
        request.cancel()
        testScheduler.runCurrent()
        assertEquals(DomainActionState.ReviewPending(review), fixture.host.stateFlow.value.coordinatorState)
        assertEquals(NativeDomainActionReviewState.Idle, fixture.host.reviewBridge.stateFlow.value)
        assertTrue(fixture.host.cancel(review.id))
        assertEquals(NativeDomainActionReviewState.Idle, fixture.host.reviewBridge.stateFlow.value)
    }

    @Test
    fun `host publishes pending while exact handoff is still retained before cleanup`() = runTest {
        val fixture = hostFixture()
        val observedBridgeStates = mutableListOf<NativeDomainActionReviewState>()
        val observer = launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.host.stateFlow.collect { hostState ->
                if (hostState.coordinatorState is DomainActionState.ReviewPending) {
                    observedBridgeStates += fixture.host.reviewBridge.stateFlow.value
                }
            }
        }
        val request = async { fixture.host.request("ordered.example", DomainAction.BLOCK) }
        testScheduler.runCurrent()
        val review = (fixture.host.reviewBridge.stateFlow.value as NativeDomainActionReviewState.AwaitingVisibility).review
        assertTrue(fixture.host.markReviewVisible(review.id))
        assertEquals(DomainActionRequestResult.Staged(review), request.await())
        assertEquals(
            listOf(NativeDomainActionReviewState.Handoff(review)),
            observedBridgeStates,
        )
        assertEquals(NativeDomainActionReviewState.Idle, fixture.host.reviewBridge.stateFlow.value)
        observer.cancelAndJoin()
    }

    @Test
    fun `notices have monotonic ids and exact dismissal compare-and-clears`() = runTest {
        val fixture = hostFixture(
            authentication = DomainActionAuthenticationResult.Failed("locked"),
        )
        assertEquals(DomainActionRequestResult.AuthenticationFailed("locked"), fixture.host.request("first.example", DomainAction.BLOCK))
        val first = requireNotNull(fixture.host.stateFlow.value.notice)
        assertEquals(DomainActionNoticeContentUi.AuthenticationFailed("locked"), first.content)
        assertEquals(DomainActionRequestResult.AuthenticationFailed("locked"), fixture.host.request("second.example", DomainAction.BLOCK))
        val second = requireNotNull(fixture.host.stateFlow.value.notice)
        assertTrue(second.id > first.id)
        assertFalse(fixture.host.dismissNotice(first.id))
        assertEquals(second, fixture.host.stateFlow.value.notice)
        assertTrue(fixture.host.dismissNotice(second.id))
        assertNull(fixture.host.stateFlow.value.notice)
    }
}

private data class HostFixture(
    val host: NativeDomainActionHost,
    var authorityCalls: Int,
    var mutationCalls: Int,
)

private fun hostFixture(
    authentication: DomainActionAuthenticationResult = DomainActionAuthenticationResult.Authorized,
    mutation: suspend (DomainActionReview) -> DomainActionApplyResult = { DomainActionApplyResult.Applied(1L) },
): HostFixture {
    val profile = testProfile()
    lateinit var fixture: HostFixture
    val host = NativeDomainActionHost(
        authenticationPolicy = DomainActionAuthenticationPolicy.REQUIRED,
        authenticator = DomainActionAuthenticator { authentication },
        authority = ActiveFilterAuthorityPort {
            fixture.authorityCalls += 1
            ActiveFilterAuthority(0L, profile, false)
        },
        mutation = DomainActionMutationPort { review, _ ->
            fixture.mutationCalls += 1
            mutation(review)
        },
    )
    fixture = HostFixture(host, 0, 0)
    return fixture
}

private suspend fun TestScope.stage(fixture: HostFixture): DomainActionReview {
    val request = async { fixture.host.request("stage.example", DomainAction.BLOCK) }
    testScheduler.runCurrent()
    val review = (fixture.host.reviewBridge.stateFlow.value as NativeDomainActionReviewState.AwaitingVisibility).review
    assertTrue(fixture.host.markReviewVisible(review.id))
    assertEquals(DomainActionRequestResult.Staged(review), request.await())
    return review
}

private fun privateBridgeMutex(bridge: NativeDomainActionReviewBridge): Mutex =
    NativeDomainActionReviewBridge::class.java
        .getDeclaredField("mutex")
        .apply { isAccessible = true }
        .get(bridge) as Mutex

private fun privateCoordinatorTransition(host: NativeDomainActionHost): Mutex {
    val coordinator = NativeDomainActionHost::class.java
        .getDeclaredField("coordinator")
        .apply { isAccessible = true }
        .get(host) as DomainActionCoordinator
    return DomainActionCoordinator::class.java
        .getDeclaredField("transition")
        .apply { isAccessible = true }
        .get(coordinator) as Mutex
}

private fun testReview(id: Long): DomainActionReview {
    val profile = testProfile()
    return DomainActionReview(
        id = id,
        normalizedDomain = "example.com",
        action = DomainAction.BLOCK,
        expectedGeneration = 0L,
        expectedHasUnsavedChanges = false,
        activeProfileId = profile.id,
        sourceProfile = profile,
        desiredProfile = profile,
    )
}

private fun testProfile(): FilterProfileDraft = FilterProfileDraft(
        id = "profile",
        name = "Profile",
        protectionLevel = ProtectionLevel.BALANCED,
        enabledSourceIds = emptySet(),
    )
