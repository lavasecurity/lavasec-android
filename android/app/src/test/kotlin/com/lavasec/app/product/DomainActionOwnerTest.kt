package com.lavasec.app.product

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.product.domain.ActiveFilterAuthority
import com.lavasec.app.product.domain.ActiveFilterAuthorityPort
import com.lavasec.app.product.domain.DomainAction
import com.lavasec.app.product.domain.DomainActionApplyResult
import com.lavasec.app.product.domain.DomainActionAuthenticationPolicy
import com.lavasec.app.product.domain.DomainActionAuthenticationResult
import com.lavasec.app.product.domain.DomainActionAuthenticator
import com.lavasec.app.product.domain.DomainActionMutationPort
import com.lavasec.app.product.domain.DomainActionRequestResult
import com.lavasec.app.product.domain.DomainActionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DomainActionOwnerTest {
    @Test
    fun `owner forwards one exact review lifecycle`() = runTest {
        val owner = DomainActionOwner(host(), this, UnconfinedTestDispatcher(testScheduler))
        val request = async { owner.request("Example.COM", DomainAction.BLOCK) }
        advanceUntilIdle()

        val awaiting = owner.state.value.review as NativeDomainActionReviewState.AwaitingVisibility
        assertEquals("example.com", awaiting.review.normalizedDomain)
        assertTrue(owner.markReviewVisible(awaiting.review.id))
        assertEquals(DomainActionRequestResult.Staged(awaiting.review), request.await())
        assertTrue(owner.state.value.host.coordinatorState is DomainActionState.ReviewPending)

        assertTrue(owner.cancel(awaiting.review.id))
        assertEquals(DomainActionState.Idle, owner.state.value.host.coordinatorState)
        owner.close()
    }

    @Test
    fun `closed owner rejects commands and cannot publish later host state`() = runTest {
        val host = host()
        val owner = DomainActionOwner(host, this, UnconfinedTestDispatcher(testScheduler))
        val frozen = owner.state.value
        owner.close()

        assertEquals(null, owner.request("closed.example", DomainAction.BLOCK))
        assertFalse(owner.markReviewVisible(1L))
        assertFalse(owner.cancelBeforeVisible(1L))
        assertEquals(null, owner.confirm(1L))
        assertFalse(owner.cancel(1L))
        assertFalse(owner.dismissNotice(1L))

        val external = async { host.request("external.example", DomainAction.BLOCK) }
        advanceUntilIdle()
        val review = (host.reviewBridge.stateFlow.value as
            NativeDomainActionReviewState.AwaitingVisibility).review
        assertTrue(host.cancelBeforeVisible(review.id))
        external.await()
        advanceUntilIdle()
        assertEquals(frozen, owner.state.value)
    }

    @Test
    fun `closing owner cancels a review that has not crossed the visibility handoff`() = runTest {
        val host = host()
        val owner = DomainActionOwner(host, this, UnconfinedTestDispatcher(testScheduler))
        val request = async { owner.request("pending.example", DomainAction.BLOCK) }
        advanceUntilIdle()
        assertTrue(owner.state.value.review is NativeDomainActionReviewState.AwaitingVisibility)

        owner.close()
        advanceUntilIdle()

        assertEquals(null, request.await())
        assertEquals(NativeDomainActionReviewState.Idle, host.reviewBridge.stateFlow.value)
    }

    private fun host(): NativeDomainActionHost {
        val profile = FilterProfileDraft(
            id = "profile",
            name = "Profile",
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = emptySet(),
        )
        return NativeDomainActionHost(
            authenticationPolicy = DomainActionAuthenticationPolicy.SKIPPED,
            authenticator = DomainActionAuthenticator {
                DomainActionAuthenticationResult.Authorized
            },
            authority = ActiveFilterAuthorityPort {
                ActiveFilterAuthority(0L, profile, hasUnsavedChanges = false)
            },
            mutation = DomainActionMutationPort { _, _ -> DomainActionApplyResult.Applied(1L) },
        )
    }
}
