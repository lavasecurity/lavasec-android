package com.lavasec.app.foundation.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fenced account authorization-flow state machine. Every test exercises a pure transition
 * over [AccountFlowState]; no Android types, network, or storage are involved.
 */
class AccountAuthorizationFlowTest {

    private val apple = AccountAuthProvider.APPLE
    private val google = AccountAuthProvider.GOOGLE

    // --- helpers ---------------------------------------------------------------------------

    private fun applied(result: AccountFlowResult): AccountFlowResult.Applied {
        assertTrue("expected Applied but was $result", result is AccountFlowResult.Applied)
        return result as AccountFlowResult.Applied
    }

    private fun ignored(result: AccountFlowResult): AccountFlowResult.Ignored {
        assertTrue("expected Ignored but was $result", result is AccountFlowResult.Ignored)
        return result as AccountFlowResult.Ignored
    }

    private fun refused(result: AccountFlowResult): AccountFlowResult.Refused {
        assertTrue("expected Refused but was $result", result is AccountFlowResult.Refused)
        return result as AccountFlowResult.Refused
    }

    /** Begins an authorization and drives it to the LINKING leg with a valid credential. */
    private fun atLinking(
        state: AccountFlowState,
        provider: AccountAuthProvider = google,
        accountId: String? = null,
        now: Long = 1_000L,
    ): AccountFlowState {
        val begun = applied(
            AccountAuthorizationFlow.beginAuthorization(
                state = state,
                provider = provider,
                nowEpochSeconds = now,
                nonce = "raw-nonce",
                nonceChallenge = "hashed-nonce",
                authorizationState = "callback-state",
                requiresStateBinding = provider == apple,
                expectedAccountId = accountId,
            ),
        ).state
        val request = begun.inFlight!!.request
        val granted = AccountProviderResult.Granted(
            requestId = request.requestId,
            provider = provider,
            identityToken = "identity-token",
            accessToken = if (provider == google) "access-token" else null,
            nonceEcho = "hashed-nonce",
            stateEcho = if (provider == apple) "callback-state" else null,
        )
        return applied(AccountAuthorizationFlow.completeAuthorization(begun, granted, now)).state
    }

    private fun signedIn(accountId: String, provider: AccountAuthProvider = google): AccountFlowState {
        val linking = atLinking(AccountFlowState.IDLE, provider, accountId = null)
        return applied(
            AccountAuthorizationFlow.completeLinking(
                linking,
                LinkedAccountIdentity(accountId = accountId, provider = provider, email = "$accountId@example.com"),
            ),
        ).state
    }

    // --- late provider callback -------------------------------------------------------------

    @Test
    fun `a late provider result after linking is ignored`() {
        val linking = atLinking(AccountFlowState.IDLE, google)
        val request = linking.inFlight!!.request
        val settled = applied(
            AccountAuthorizationFlow.completeLinking(
                linking,
                LinkedAccountIdentity(accountId = "A", provider = google),
            ),
        ).state
        assertTrue(settled.isSignedIn)

        val duplicate = AccountProviderResult.Granted(
            requestId = request.requestId,
            provider = google,
            identityToken = "identity-token",
            accessToken = "access-token",
            nonceEcho = "hashed-nonce",
        )
        val result = ignored(AccountAuthorizationFlow.completeAuthorization(settled, duplicate, 1_000L))
        assertEquals(AccountFlowIgnore.NOT_IN_FLIGHT, result.reason)
        assertEquals(settled, result.state)
    }

    @Test
    fun `a duplicate provider result while already linking is refused`() {
        val linking = atLinking(AccountFlowState.IDLE, google)
        val flight = requireNotNull(linking.inFlight)
        val request = flight.request
        val duplicate = AccountProviderResult.Granted(
            requestId = request.requestId,
            provider = google,
            identityToken = "identity-token",
            accessToken = "access-token",
            nonceEcho = "hashed-nonce",
        )
        val result = refused(AccountAuthorizationFlow.completeAuthorization(linking, duplicate, 1_000L))
        assertEquals(AccountFlowRefusal.NOT_AUTHORIZING_STEP, result.reason)
        assertEquals(AccountFlowStep.LINKING, flight.step)
    }

    // --- generation fence / account switch --------------------------------------------------

    @Test
    fun `a result for a superseded request is ignored`() {
        val first = applied(
            AccountAuthorizationFlow.beginAuthorization(
                AccountFlowState.IDLE,
                google,
                nowEpochSeconds = 1_000L,
                nonce = "n1",
                nonceChallenge = "c1",
                authorizationState = "s1",
            ),
        ).state
        val firstRequestId = first.inFlight!!.request.requestId
        val cancelled = applied(AccountAuthorizationFlow.cancelAuthorization(first, firstRequestId)).state

        val second = applied(
            AccountAuthorizationFlow.beginAuthorization(
                cancelled,
                google,
                nowEpochSeconds = 1_000L,
                nonce = "n2",
                nonceChallenge = "c2",
                authorizationState = "s2",
            ),
        ).state

        val stale = AccountProviderResult.Granted(
            requestId = firstRequestId,
            provider = google,
            identityToken = "identity-token",
            accessToken = "access-token",
            nonceEcho = "c1",
        )
        val result = ignored(AccountAuthorizationFlow.completeAuthorization(second, stale, 1_000L))
        assertEquals(AccountFlowIgnore.STALE_REQUEST, result.reason)
        assertEquals(second, result.state)
    }

    @Test
    fun `an identity for a different account never applies to a targeted link`() {
        val signedInA = signedIn("A")
        val linking = atLinking(signedInA, google, accountId = "A")

        val crossAccount = ignored(
            AccountAuthorizationFlow.completeLinking(
                linking,
                LinkedAccountIdentity(accountId = "B", provider = google),
            ),
        )
        assertEquals(AccountFlowIgnore.CROSS_ACCOUNT, crossAccount.reason)
        assertEquals(linking, crossAccount.state)
        assertTrue(crossAccount.state.connections.containsAccount("A"))
    }

    @Test
    fun `a fresh sign-in as B replaces A's connections but links the same account`() {
        val signedInA = signedIn("A")

        val switch = applied(
            AccountAuthorizationFlow.completeLinking(
                atLinking(signedInA, apple),
                LinkedAccountIdentity(accountId = "B", provider = apple, email = "b@example.com"),
            ),
        ).state
        assertFalse(switch.connections.containsAccount("A"))
        assertTrue(switch.connections.containsAccount("B"))
        assertEquals("B", switch.currentAccountId)

        val link = applied(
            AccountAuthorizationFlow.completeLinking(
                atLinking(switch, google, accountId = "B"),
                LinkedAccountIdentity(accountId = "B", provider = google),
            ),
        ).state
        assertEquals(2, link.connections.all.size)
        assertEquals(setOf(apple, google), link.connections.all.map { it.provider }.toSet())
    }

    // --- concurrent sign-out ----------------------------------------------------------------

    @Test
    fun `sign-out during authorization supersedes the in-flight request`() {
        val begun = applied(
            AccountAuthorizationFlow.beginAuthorization(
                signedIn("A"),
                google,
                nowEpochSeconds = 1_000L,
                nonce = "n",
                nonceChallenge = "c",
                authorizationState = "s",
            ),
        ).state
        val request = begun.inFlight!!.request

        val out = applied(AccountAuthorizationFlow.signOut(begun)).state
        assertFalse(out.isSignedIn)
        assertNull(out.inFlight)
        assertEquals(AccountFlowOutcome.SIGNED_OUT, out.lastOutcome)

        val late = AccountProviderResult.Granted(
            requestId = request.requestId,
            provider = google,
            identityToken = "identity-token",
            accessToken = "access-token",
            nonceEcho = "c",
        )
        val result = ignored(AccountAuthorizationFlow.completeAuthorization(out, late, 1_000L))
        assertEquals(AccountFlowIgnore.NOT_IN_FLIGHT, result.reason)
        assertFalse(result.state.isSignedIn)
    }

    // --- rejected / expired -----------------------------------------------------------------

    @Test
    fun `a nonce mismatch is rejected, not linked`() {
        val begun = applied(
            AccountAuthorizationFlow.beginAuthorization(
                AccountFlowState.IDLE,
                google,
                nowEpochSeconds = 1_000L,
                nonce = "raw",
                nonceChallenge = "expected-challenge",
                authorizationState = "s",
            ),
        ).state
        val request = begun.inFlight!!.request
        val tampered = AccountProviderResult.Granted(
            requestId = request.requestId,
            provider = google,
            identityToken = "identity-token",
            accessToken = "access-token",
            nonceEcho = "wrong-challenge",
        )
        val result = applied(AccountAuthorizationFlow.completeAuthorization(begun, tampered, 1_000L))
        assertEquals(AccountFlowOutcome.REJECTED, result.outcome)
        assertEquals(AccountFlowPhase.FAILED, result.state.phase)
        assertNull(result.state.inFlight)
    }

    @Test
    fun `an expired request or token settles to expired`() {
        val begun = applied(
            AccountAuthorizationFlow.beginAuthorization(
                AccountFlowState.IDLE,
                google,
                nowEpochSeconds = 1_000L,
                nonce = "raw",
                nonceChallenge = "c",
                authorizationState = "s",
                lifetimeSeconds = 10L,
            ),
        ).state
        val request = begun.inFlight!!.request
        val credential = AccountProviderResult.Granted(
            requestId = request.requestId,
            provider = google,
            identityToken = "identity-token",
            accessToken = "access-token",
            nonceEcho = "c",
        )

        val expiredFlow = applied(AccountAuthorizationFlow.completeAuthorization(begun, credential, 1_011L))
        assertEquals(AccountFlowOutcome.EXPIRED, expiredFlow.outcome)

        val tokenExpiry = applied(
            AccountAuthorizationFlow.completeAuthorization(
                AccountFlowState(
                    generation = 1L,
                    inFlight = InFlightAuthorization(
                        request.copy(requestId = 1L, generation = 1L, expiresAtEpochSeconds = 2_000L),
                        AccountFlowStep.AUTHORIZING,
                    ),
                ),
                credential.copy(requestId = 1L, tokenExpiresAtEpochSeconds = 1_500L),
                1_600L,
            ),
        )
        assertEquals(AccountFlowOutcome.EXPIRED, tokenExpiry.outcome)
    }

    @Test
    fun `Google without an access token is rejected but Apple needs no access token`() {
        val googleBegun = applied(
            AccountAuthorizationFlow.beginAuthorization(
                AccountFlowState.IDLE,
                google,
                nowEpochSeconds = 1_000L,
                nonce = "raw",
                nonceChallenge = "c",
                authorizationState = "s",
            ),
        ).state
        val googleRequest = googleBegun.inFlight!!.request
        val missingAccess = AccountProviderResult.Granted(
            requestId = googleRequest.requestId,
            provider = google,
            identityToken = "identity-token",
            accessToken = null,
            nonceEcho = "c",
        )
        assertEquals(
            AccountFlowOutcome.REJECTED,
            applied(AccountAuthorizationFlow.completeAuthorization(googleBegun, missingAccess, 1_000L)).outcome,
        )

        val appleBegun = applied(
            AccountAuthorizationFlow.beginAuthorization(
                AccountFlowState.IDLE,
                apple,
                nowEpochSeconds = 1_000L,
                nonce = "raw",
                nonceChallenge = "c",
                authorizationState = "callback",
                requiresStateBinding = true,
            ),
        ).state
        val appleRequest = appleBegun.inFlight!!.request
        val missingState = AccountProviderResult.Granted(
            requestId = appleRequest.requestId,
            provider = apple,
            identityToken = "identity-token",
            nonceEcho = "c",
            stateEcho = "wrong-callback",
        )
        assertEquals(
            AccountFlowOutcome.REJECTED,
            applied(AccountAuthorizationFlow.completeAuthorization(appleBegun, missingState, 1_000L)).outcome,
        )

        val goodState = missingState.copy(stateEcho = "callback")
        assertEquals(
            AccountFlowStep.LINKING,
            applied(AccountAuthorizationFlow.completeAuthorization(appleBegun, goodState, 1_000L)).state.inFlight!!.step,
        )
    }

    // --- cancellation -----------------------------------------------------------------------

    @Test
    fun `provider cancellation and explicit cancel both settle to cancelled`() {
        val begun = applied(
            AccountAuthorizationFlow.beginAuthorization(
                AccountFlowState.IDLE,
                google,
                nowEpochSeconds = 1_000L,
                nonce = "raw",
                nonceChallenge = "c",
                authorizationState = "s",
            ),
        ).state
        val request = begun.inFlight!!.request

        val denied = applied(
            AccountAuthorizationFlow.completeAuthorization(
                begun,
                AccountProviderResult.Denied(request.requestId, google, AccountProviderDenial.CANCELLED),
                1_000L,
            ),
        )
        assertEquals(AccountFlowOutcome.CANCELLED, denied.outcome)
        assertEquals(AccountFlowPhase.IDLE, denied.state.phase)
        assertNull(denied.state.inFlight)

        val explicit = applied(AccountAuthorizationFlow.cancelAuthorization(begun, request.requestId))
        assertEquals(AccountFlowOutcome.CANCELLED, explicit.outcome)
        assertEquals(AccountFlowPhase.IDLE, explicit.state.phase)
    }

    @Test
    fun `admission is single-flight and reports busy`() {
        val begun = applied(
            AccountAuthorizationFlow.beginAuthorization(
                AccountFlowState.IDLE,
                google,
                nowEpochSeconds = 1_000L,
                nonce = "raw",
                nonceChallenge = "c",
                authorizationState = "s",
            ),
        ).state

        val second = refused(
            AccountAuthorizationFlow.beginAuthorization(
                begun,
                apple,
                nowEpochSeconds = 1_001L,
                nonce = "raw2",
                nonceChallenge = "c2",
                authorizationState = "s2",
            ),
        )
        assertEquals(AccountFlowRefusal.FLOW_BUSY, second.reason)
        assertEquals(AccountFlowPhase.BUSY, second.state.phase)
        assertNotNull(second.state.inFlight)
        assertEquals(google, second.state.inFlight!!.request.provider)
    }

    // --- deletion ---------------------------------------------------------------------------

    @Test
    fun `a pending deletion is never complete without acknowledgement`() {
        val signedInA = signedIn("A")
        val begun = applied(AccountAuthorizationFlow.beginDeletion(signedInA, "A", 2_000L))
        assertEquals(AccountFlowOutcome.DELETION_PENDING, begun.outcome)
        val record = begun.state.deletion!!
        assertFalse(record.serverAcknowledged)
        assertTrue(begun.state.isDeletionPending)

        val premature = refused(AccountAuthorizationFlow.completeDeletion(begun.state, record.operationId))
        assertEquals(AccountFlowRefusal.NOT_ACKNOWLEDGED, premature.reason)
        assertTrue(premature.state.isDeletionPending)
        assertFalse(premature.state.isDeletionComplete(record.operationId))

        val acknowledged = applied(AccountAuthorizationFlow.acknowledgeDeletion(begun.state, record.operationId)).state
        assertTrue(acknowledged.deletion!!.serverAcknowledged)

        val completed = applied(AccountAuthorizationFlow.completeDeletion(acknowledged, record.operationId))
        assertEquals(AccountFlowOutcome.DELETED, completed.outcome)
        assertNull(completed.state.deletion)
        assertFalse(completed.state.isSignedIn)
        assertTrue(completed.state.isDeletionComplete(record.operationId))
    }

    @Test
    fun `local sign-out leaves the durable deletion fence pending`() {
        val signedInA = signedIn("A")
        val pending = applied(AccountAuthorizationFlow.beginDeletion(signedInA, "A", 2_000L)).state
        val record = pending.deletion!!

        val signedOut = applied(AccountAuthorizationFlow.signOut(pending)).state
        assertFalse(signedOut.isSignedIn)
        assertEquals(record, signedOut.deletion)

        // The still-pending deletion can be acknowledged and completed after the local sign-out.
        val acknowledged = applied(AccountAuthorizationFlow.acknowledgeDeletion(signedOut, record.operationId)).state
        assertTrue(acknowledged.deletion!!.serverAcknowledged)
        assertTrue(applied(AccountAuthorizationFlow.completeDeletion(acknowledged, record.operationId)).state.isDeletionComplete(record.operationId))
    }

    @Test
    fun `deletion is idempotent to retry and resume`() {
        val signedInA = signedIn("A")
        val first = applied(AccountAuthorizationFlow.beginDeletion(signedInA, "A", 2_000L)).state
        val operationId = first.deletion!!.operationId

        val duplicate = refused(AccountAuthorizationFlow.beginDeletion(first, "A", 2_001L))
        assertEquals(AccountFlowRefusal.DELETION_ALREADY_PENDING, duplicate.reason)

        val resumed = applied(AccountAuthorizationFlow.resumeDeletion(first)).state
        assertEquals(operationId, resumed.deletion!!.operationId)

        val ackOnce = applied(AccountAuthorizationFlow.acknowledgeDeletion(first, operationId)).state
        val ackTwice = applied(AccountAuthorizationFlow.acknowledgeDeletion(ackOnce, operationId)).state
        assertEquals(ackOnce, ackTwice)

        val completed = applied(AccountAuthorizationFlow.completeDeletion(ackOnce, operationId)).state
        val completedAgain = applied(AccountAuthorizationFlow.completeDeletion(completed, operationId)).state
        assertEquals(completed, completedAgain)
    }

    @Test
    fun `deletion never crosses users`() {
        val signedInA = signedIn("A")
        val pending = applied(AccountAuthorizationFlow.beginDeletion(signedInA, "A", 2_000L)).state
        val operationId = pending.deletion!!.operationId

        val signedInB = signedIn("B")
        val crossed = signedInB.copy(deletion = pending.deletion)
        assertEquals("B", crossed.currentAccountId)

        assertEquals(
            AccountFlowRefusal.ACCOUNT_MISMATCH,
            refused(AccountAuthorizationFlow.acknowledgeDeletion(crossed, operationId)).reason,
        )
        val acknowledgedByOther = crossed.copy(deletion = crossed.deletion!!.copy(serverAcknowledged = true))
        assertEquals(
            AccountFlowRefusal.ACCOUNT_MISMATCH,
            refused(AccountAuthorizationFlow.completeDeletion(acknowledgedByOther, operationId)).reason,
        )
    }

    // --- durability seam --------------------------------------------------------------------

    @Test
    fun `a pending deletion survives a serialized reopen while an unreadable fence fails closed`() {
        val store = MemoryDeletionStore()
        val signedInA = signedIn("A")
        val result = AccountAuthorizationFlow.beginDeletion(signedInA, "A", 2_000L)
        val persisted = AccountDeletionFence.apply(result, signedInA, store)
        assertNotNull(persisted.deletion)

        // A fresh store over the same backing stands in for a process relaunch.
        val reopened = MemoryDeletionStore(backing = store.backing)
        val read = reopened.read()
        assertTrue(read is AccountDeletionRead.Present)
        val restored = AccountAuthorizationFlow.restoreDeletion(AccountFlowState.IDLE, read)
        assertEquals(persisted.deletion, restored.deletion)
        assertTrue(restored.isDeletionPending)

        assertEquals(
            AccountFlowRefusal.NOT_ACKNOWLEDGED,
            refused(AccountAuthorizationFlow.completeDeletion(restored, persisted.deletion!!.operationId)).reason,
        )

        val unreadable = AccountDeletionFence.restore(AccountDeletionRead.Unreadable)
        assertNull(unreadable)
        assertTrue(AccountDeletionFence.isUnreadable(AccountDeletionRead.Unreadable))
        val failedClosed = AccountAuthorizationFlow.restoreDeletion(AccountFlowState.IDLE, AccountDeletionRead.Unreadable)
        assertNull(failedClosed.deletion)
        assertTrue(failedClosed.isDeletionFenceUnreadable)
        assertEquals(
            AccountFlowRefusal.FENCE_UNREADABLE,
            refused(AccountAuthorizationFlow.beginDeletion(signedInA.copy(deletionFenceReadable = false), "A", 2_000L)).reason,
        )
    }

    /** In-memory durability seam, sufficient to prove the record round-trips and clears. */
    private class MemoryDeletionStore(
        var backing: AccountDeletionRecord? = null,
        var writable: Boolean = true,
    ) : AccountDeletionStore {
        override fun read(): AccountDeletionRead =
            backing?.let { AccountDeletionRead.Present(it) } ?: AccountDeletionRead.Absent

        override fun write(record: AccountDeletionRecord): Boolean {
            if (!writable) return false
            backing = record
            return true
        }

        override fun clear(operationId: Long): Boolean {
            if (!writable) return false
            if (backing?.operationId != operationId) return false
            backing = null
            return true
        }
    }
}
