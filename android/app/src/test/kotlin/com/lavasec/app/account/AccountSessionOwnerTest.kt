package com.lavasec.app.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountSessionOwnerTest {
    private class Store(var value: AccountSession? = null) : AccountSessionStore {
        var failSave = false
        override suspend fun load() = value
        override suspend fun save(session: AccountSession) { if (failSave) error("disk"); value = session }
        override suspend fun clear() { value = null }
    }
    private class Api : AccountAuthApi {
        var refreshes = 0
        var gate: CompletableDeferred<Unit>? = null
        var refreshUser = USER
        override suspend fun exchangeGoogleIdToken(idToken: String, rawNonce: String): AccountSession {
            gate?.await(); return session()
        }
        override suspend fun refresh(refreshToken: String): AccountSession {
            refreshes++; gate?.await(); return session(user = refreshUser)
        }
        override suspend fun signOut(accessToken: String) = Unit
    }

    @Test fun `native sign in challenges are unique expire and cannot be replayed`() = runTest {
        var now = 100L
        val owner = AccountSessionOwner(Api(), Store()) { now }
        val first = owner.beginSignIn()
        val nonce = owner.googleProviderNonce(first)
        assertEquals(64, nonce.length)
        val second = owner.beginSignIn()
        assertNotEquals(nonce, owner.googleProviderNonce(second))
        assertTrue(runCatching { owner.finishGoogleSignIn(first, "id") }.exceptionOrNull() is AccountSessionChanged)
        now = 701L
        assertTrue(runCatching { owner.finishGoogleSignIn(second, "id") }.exceptionOrNull() is AccountSessionChanged)
        val third = owner.beginSignIn()
        owner.finishGoogleSignIn(third, "id")
        assertTrue(runCatching { owner.finishGoogleSignIn(third, "id") }.exceptionOrNull() is AccountSessionChanged)
    }

    @Test fun `concurrent requests rotate the token exactly once and persist before use`() = runTest {
        val store = Store(session(expiry = 1))
        val api = Api()
        api.gate = CompletableDeferred()
        val owner = AccountSessionOwner(api, store) { 100 }
        val first = async { owner.withSession { assertSame(store.value, it); it.accessToken } }
        val second = async { owner.withSession { it.accessToken } }
        runCurrent()
        assertEquals(1, api.refreshes)
        api.gate!!.complete(Unit)
        assertEquals("access", first.await())
        assertEquals("access", second.await())
        assertEquals(1, api.refreshes)
    }

    @Test fun `sign out fences an in-flight refresh before clearing storage`() = runTest {
        val store = Store(session(expiry = 1))
        val api = Api().apply { gate = CompletableDeferred() }
        val owner = AccountSessionOwner(api, store) { 100 }
        val refresh = async { runCatching { owner.withSession { error("must not reach operation") } }.exceptionOrNull() }
        runCurrent()
        val logout = launch { owner.signOut() }
        runCurrent()
        assertEquals(AccountAvailability.SIGNED_OUT, owner.state.value.availability)
        api.gate!!.complete(Unit)
        assertTrue(refresh.await() is AccountSessionChanged)
        logout.join()
        assertNull(store.value)
    }

    @Test fun `late login cannot resurrect a signed out account`() = runTest {
        val store = Store()
        val api = Api().apply { gate = CompletableDeferred() }
        val owner = AccountSessionOwner(api, store) { 100 }
        val ticket = owner.beginSignIn()
        val login = async { runCatching { owner.finishGoogleSignIn(ticket, "id") }.exceptionOrNull() }
        runCurrent()
        owner.signOut()
        api.gate!!.complete(Unit)
        assertTrue(login.await() is AccountSessionChanged)
        assertNull(store.value)
    }

    @Test fun `unexpected refresh identity revokes credentials`() = runTest {
        val store = Store(session(expiry = 1))
        val api = Api().apply { refreshUser = "20000000-0000-0000-0000-000000000000" }
        val owner = AccountSessionOwner(api, store) { 100 }
        assertTrue(runCatching { owner.withSession { } }.exceptionOrNull() is AccountAuthRejected)
        assertNull(store.value)
        assertEquals(AccountAvailability.SIGNED_OUT, owner.state.value.availability)
    }

    @Test fun `failed durable refresh never exposes the new access token`() = runTest {
        val store = Store(session(expiry = 1)).apply { failSave = true }
        val owner = AccountSessionOwner(Api(), store) { 100 }
        var reached = false
        assertTrue(runCatching { owner.withSession { reached = true } }.isFailure)
        assertFalse(reached)
    }

    @Test fun `sign in admission cannot reload old credentials from disk`() = runTest {
        val owner = AccountSessionOwner(Api(), Store(session())) { 100 }
        owner.beginSignIn()
        assertTrue(runCatching { owner.withSession { } }.exceptionOrNull() is AccountUnavailable)
        assertEquals(AccountAvailability.SIGNED_OUT, owner.state.value.availability)
    }

    @Test fun `stale presentation predicate cannot revoke a current account or its stored session`() = runTest {
        val stored = session()
        val store = Store(stored)
        val owner = AccountSessionOwner(Api(), store) { 100 }
        owner.initialize()
        val before = owner.state.value
        assertTrue(runCatching { owner.signOut(before.generation) { false } }.exceptionOrNull() is AccountSessionChanged)
        assertEquals(before, owner.state.value)
        assertSame(stored, store.value)
        // A fresh, exact owner can still revoke; a stale refusal never poisons logout.
        owner.signOut(before.generation) { owner.state.value == before }
        assertEquals(before.generation + 1, owner.state.value.generation)
        assertEquals(AccountAvailability.SIGNED_OUT, owner.state.value.availability)
        assertNull(store.value)
    }

    private companion object {
        const val USER = "10000000-0000-0000-0000-000000000000"
        fun session(expiry: Long = 3600, user: String = USER) = AccountSession("access", "refresh", expiry, user, "me@example.com", setOf("google"))
    }
}
