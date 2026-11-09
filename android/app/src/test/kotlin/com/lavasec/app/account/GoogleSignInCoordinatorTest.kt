package com.lavasec.app.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GoogleSignInCoordinatorTest {
    private fun accounts(): AccountSessionOwner = AccountSessionOwner(object : AccountAuthApi {
        override suspend fun exchangeGoogleIdToken(idToken: String, rawNonce: String): AccountSession {
            assertEquals("native-id-token", idToken)
            assertTrue(rawNonce.length >= 32)
            return session()
        }
        override suspend fun refresh(refreshToken: String) = session()
        override suspend fun signOut(accessToken: String) = Unit
    }, object : AccountSessionStore {
        var value: AccountSession? = session()
        override suspend fun load() = value
        override suspend fun save(session: AccountSession) { value = session }
        override suspend fun clear() { value = null }
    }) { 100 }

    @Test fun `native provider receives only challenge and concurrent prompts are refused`() = runTest {
        val accounts = accounts()
        val coordinator = GoogleSignInCoordinator(accounts, true)
        val gate = CompletableDeferred<Unit>()
        val first = async { coordinator.signIn { nonce -> assertTrue(nonce.matches(Regex("[a-f0-9]{64}"))); gate.await(); "native-id-token" } }
        runCurrent()
        assertEquals(NativeSignInResult.BUSY, coordinator.signIn { error("second prompt") })
        gate.complete(Unit)
        assertEquals(NativeSignInResult.SIGNED_IN, first.await())
        assertEquals(AccountAvailability.SIGNED_IN, accounts.state.value.availability)
    }
    @Test fun `dismissed or cancelled prompt restores previous account and allows retry`() = runTest {
        val accounts = accounts()
        val coordinator = GoogleSignInCoordinator(accounts, true)
        assertEquals(NativeSignInResult.CANCELLED, coordinator.signIn { throw ProviderSignInCancelled() })
        assertEquals(AccountAvailability.SIGNED_IN, accounts.state.value.availability)
        val suspended = async { coordinator.signIn { CompletableDeferred<String>().await() } }
        runCurrent()
        suspended.cancelAndJoin()
        assertEquals(AccountAvailability.SIGNED_IN, accounts.state.value.availability)
        assertEquals(NativeSignInResult.SIGNED_IN, coordinator.signIn { "native-id-token" })
    }
    @Test fun `logout while provider is open refuses its late result`() = runTest {
        val accounts = accounts()
        val coordinator = GoogleSignInCoordinator(accounts, true)
        val result = CompletableDeferred<String>()
        val login = async { coordinator.signIn { result.await() } }
        runCurrent()
        accounts.signOut()
        result.complete("native-id-token")
        assertEquals(NativeSignInResult.SUPERSEDED, login.await())
        assertEquals(AccountAvailability.SIGNED_OUT, accounts.state.value.availability)
    }
    @Test fun `unconfigured provider never opens a prompt or withdraws account authority`() = runTest {
        val accounts = accounts()
        accounts.initialize()
        assertEquals(NativeSignInResult.UNAVAILABLE, GoogleSignInCoordinator(accounts, false).signIn { error("unconfigured") })
        assertEquals(AccountAvailability.SIGNED_IN, accounts.state.value.availability)
    }
    private fun session() = AccountSession("access", "refresh", 3600, "10000000-0000-0000-0000-000000000000", null, setOf("google"))
}
