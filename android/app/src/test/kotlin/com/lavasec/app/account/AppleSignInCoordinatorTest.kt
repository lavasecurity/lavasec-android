package com.lavasec.app.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the Apple owner/adapter seam: an unconfigured client is typed-unavailable, a configured fake
 * client completes through the shared [AccountSessionOwner] admission, and a late/foreign callback
 * is discarded rather than committed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppleSignInCoordinatorTest {

    private class Api : AccountAuthApi {
        var exchangedNonce: String? = null
        override suspend fun exchangeGoogleIdToken(idToken: String, rawNonce: String) = providerSession("google")
        override suspend fun exchangeAppleIdToken(idToken: String, rawNonce: String): AccountSession {
            assertEquals("apple-identity-token", idToken)
            assertTrue(rawNonce.length >= 32)
            exchangedNonce = rawNonce
            return providerSession("apple")
        }
        override suspend fun refresh(refreshToken: String) = providerSession("apple")
        override suspend fun signOut(accessToken: String) = Unit
    }

    private fun accounts(api: Api = Api()): Pair<AccountSessionOwner, Api> {
        val owner = AccountSessionOwner(api, object : AccountSessionStore {
            var value: AccountSession? = providerSession("google")
            override suspend fun load() = value
            override suspend fun save(session: AccountSession) { value = session }
            override suspend fun clear() { value = null }
        }) { 100 }
        return owner to api
    }

    @Test fun `unconfigured client never opens a prompt or withdraws account authority`() = runTest {
        val (owner, _) = accounts()
        owner.initialize()
        val result = AppleSignInCoordinator(owner, configured = false).signIn("https://app/account/apple") {
            error("an unconfigured client must never be invoked")
        }
        assertEquals(NativeSignInResult.UNAVAILABLE, result)
        assertEquals(AccountAvailability.SIGNED_IN, owner.state.value.availability)
    }

    @Test fun `a configured client completes and binds to the request challenge`() = runTest {
        val (owner, api) = accounts()
        val coordinator = AppleSignInCoordinator(owner, configured = true)
        var challenge: AppleAuthorizationChallenge? = null
        val result = coordinator.signIn("https://app/account/apple") { issued ->
            challenge = issued
            AppleAuthorizationCredential(identityToken = "apple-identity-token", stateEcho = issued.authorizationState)
        }
        assertEquals(NativeSignInResult.SIGNED_IN, result)
        assertEquals(AccountAvailability.SIGNED_IN, owner.state.value.availability)
        val bound = requireNotNull(challenge)
        assertTrue("challenge nonce is sha256", bound.nonceChallenge.matches(Regex("[a-f0-9]{64}")))
        assertTrue("callback state is minted per admission", bound.authorizationState.isNotBlank())
        assertEquals("https://app/account/apple", bound.redirectUri)
        assertNotNull(api.exchangedNonce)
    }

    @Test fun `a foreign callback state echo is discarded without committing a session`() = runTest {
        val (owner, api) = accounts()
        val coordinator = AppleSignInCoordinator(owner, configured = true)
        val result = coordinator.signIn("https://app/account/apple") {
            AppleAuthorizationCredential(identityToken = "apple-identity-token", stateEcho = "someone-elses-state")
        }
        assertEquals(NativeSignInResult.FAILED, result)
        assertNull(api.exchangedNonce)
        assertEquals(AccountAvailability.SIGNED_IN, owner.state.value.availability)
    }

    @Test fun `a late callback after sign-out is superseded`() = runTest {
        val (owner, _) = accounts()
        val coordinator = AppleSignInCoordinator(owner, configured = true)
        val gate = CompletableDeferred<Unit>()
        val login = async {
            coordinator.signIn("https://app/account/apple") { issued ->
                gate.await()
                AppleAuthorizationCredential(identityToken = "apple-identity-token", stateEcho = issued.authorizationState)
            }
        }
        runCurrent()
        owner.signOut()
        gate.complete(Unit)
        assertEquals(NativeSignInResult.SUPERSEDED, login.await())
        assertEquals(AccountAvailability.SIGNED_OUT, owner.state.value.availability)
    }

    @Test fun `a concurrent admission is refused as busy`() = runTest {
        val (owner, _) = accounts()
        val coordinator = AppleSignInCoordinator(owner, configured = true)
        val gate = CompletableDeferred<Unit>()
        val first = async {
            coordinator.signIn("https://app/account/apple") { issued ->
                gate.await()
                AppleAuthorizationCredential(identityToken = "apple-identity-token", stateEcho = issued.authorizationState)
            }
        }
        runCurrent()
        assertEquals(NativeSignInResult.BUSY, coordinator.signIn("https://app/account/apple") { error("second prompt") })
        gate.complete(Unit)
        assertEquals(NativeSignInResult.SIGNED_IN, first.await())
    }

}

private fun providerSession(provider: String) = AccountSession(
    "access", "refresh", 3600, "10000000-0000-0000-0000-000000000000", null, setOf(provider),
)
