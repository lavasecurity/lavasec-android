package com.lavasec.app.reactnative

import com.lavasec.app.account.AccountAvailability
import com.lavasec.app.account.AccountSessionChanged
import com.lavasec.app.account.AccountSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeLinkedAccountVisitTest {
    private class Fixture {
        private val originalOwner = Any()
        var alive = true
        val originalHost = NativeVisitHost(originalOwner, "host-a") { alive }
        var host: NativeVisitHost? = originalHost
        val originalBackend = Any()
        var backend: Any? = originalBackend
        var generation: Long? = 7
        var authority: Long? = 11
        val originalAccount = AccountSnapshot(4, AccountAvailability.SIGNED_IN, "account-a", "a@example.test", setOf("google", "apple"))
        var account: AccountSnapshot? = originalAccount
        var read = true
        var mutate = true
        var authorized = true
        var authorization: CompletableDeferred<Unit>? = null
        var writeBarrier: CompletableDeferred<Unit>? = null
        var afterRevocation: CompletableDeferred<Unit>? = null
        var actions = 0
        var confirms = 0
        var signOut: suspend (Long, () -> Boolean) -> Unit = { expected, current ->
            writeBarrier?.await()
            if (!current() || account?.generation != expected) throw AccountSessionChanged()
            actions++
            account = AccountSnapshot(expected + 1, AccountAvailability.SIGNED_OUT)
            afterRevocation?.await()
        }
        var delete: suspend (Long, () -> Boolean) -> Unit = { expected, current ->
            confirms++; signOut(expected, current)
        }
        fun visit() = NativeLinkedAccountVisit(originalHost, 7, originalBackend, originalAccount, 11,
            currentHost = { host }, currentGeneration = { generation }, currentBackend = { backend },
            currentAccount = { account }, currentAuthorityRevision = { authority },
            canRead = { read }, canMutate = { mutate },
            authorize = { authorization?.await(); authorized },
            signOut = { expected, current -> signOut(expected, current) },
            deleteAccount = { expected, current -> delete(expected, current) })
    }

    @Test fun `connections stay native and both provider rows use the captured account`() {
        val f = Fixture(); val visit = f.visit()
        assertEquals(listOf("apple", "google"), visit.connections()!!.map { it.provider })
        assertEquals(listOf("a@example.test", "a@example.test"), visit.connections()!!.map { it.email })
        f.read = false
        assertNull(visit.connections())
        f.read = true; f.host = null
        assertNull(visit.connections())
        f.host = f.originalHost
        assertEquals(2, visit.connections()!!.size)
        visit.cancel()
        assertNull(visit.connections())
    }

    @Test fun `confirmed delete and sign out report only actual original revocation`() = runTest {
        for (action in NativeLinkedAccountAction.entries) {
            val f = Fixture(); val visit = f.visit()
            assertEquals(NativeLinkedAccountOutcome.COMPLETED, visit.perform(action, confirmed = true))
            assertEquals(1, f.actions)
            assertEquals(if (action == NativeLinkedAccountAction.DELETE) 1 else 0, f.confirms)
            assertEquals(AccountAvailability.SIGNED_OUT, f.account!!.availability)
            assertNull(visit.connections())
        }
    }

    @Test fun `deletion requires explicit confirmation and cancelled authorization never writes`() = runTest {
        val f = Fixture(); val visit = f.visit()
        assertEquals(NativeLinkedAccountOutcome.REFUSED, visit.perform(NativeLinkedAccountAction.DELETE))
        assertEquals(0, f.confirms)
        f.authorized = false
        assertEquals(NativeLinkedAccountOutcome.CANCELLED, visit.perform(NativeLinkedAccountAction.SIGN_OUT))
        assertEquals(0, f.actions)
        f.authorized = true; f.mutate = false
        assertEquals(NativeLinkedAccountOutcome.STALE, visit.perform(NativeLinkedAccountAction.SIGN_OUT))
        assertEquals(0, f.actions)
    }

    @Test fun `every original identity is rechecked after awaited authorization`() = runTest {
        val replacements: List<(Fixture) -> Unit> = listOf(
            { it.host = NativeVisitHost(Any(), "host-b") { true } },
            { it.alive = false }, { it.generation = 8 }, { it.backend = Any() },
            { it.account = it.originalAccount.copy(generation = 5) },
            { it.account = it.originalAccount.copy(email = "changed@example.test") },
            { it.authority = 12 }, { it.read = false }, { it.mutate = false },
        )
        for (replace in replacements) {
            val f = Fixture().apply { authorization = CompletableDeferred() }
            val visit = f.visit()
            val operation = async { visit.perform(NativeLinkedAccountAction.DELETE, true) }
            runCurrent(); replace(f); f.authorization!!.complete(Unit)
            assertEquals(NativeLinkedAccountOutcome.STALE, operation.await())
            assertEquals(0, f.actions)
            assertEquals(0, f.confirms)
        }
    }

    @Test fun `the original write edge refuses successor after authorization already passed`() = runTest {
        val f = Fixture().apply { writeBarrier = CompletableDeferred() }
        val visit = f.visit()
        val operation = async { visit.perform(NativeLinkedAccountAction.SIGN_OUT) }
        runCurrent()
        f.host = NativeVisitHost(Any(), "host-b") { true }
        f.writeBarrier!!.complete(Unit)
        assertEquals(NativeLinkedAccountOutcome.STALE, operation.await())
        assertEquals(0, f.actions)
        assertEquals(f.originalAccount, f.account)
    }

    @Test fun `read revocation at the delayed native write edge refuses both actions without retiring the account`() = runTest {
        for (action in NativeLinkedAccountAction.entries) {
            val f = Fixture().apply { writeBarrier = CompletableDeferred() }
            val visit = f.visit()
            val operation = async { visit.perform(action, confirmed = true) }
            runCurrent()
            // Authorization passed and the callback is pending, while every other
            // original identity and the independent mutation capability stay valid.
            assertTrue(f.mutate)
            assertEquals(f.originalHost, f.host)
            assertEquals(f.originalAccount, f.account)
            f.read = false
            f.writeBarrier!!.complete(Unit)
            assertEquals(NativeLinkedAccountOutcome.STALE, operation.await())
            assertEquals(0, f.actions)
            assertEquals(f.originalAccount, f.account)
            assertNull(visit.connections())
            assertFalse(visit.completingRevocation())
            // This was a refusal before the real mutation, not a permanent visit
            // cancellation: a fresh read grant admits the same original account.
            f.read = true
            assertEquals(NativeLinkedAccountOutcome.COMPLETED, visit.perform(action, confirmed = true))
            assertEquals(1, f.actions)
            assertEquals(AccountAvailability.SIGNED_OUT, f.account!!.availability)
        }
    }

    @Test fun `one pending original authorization owns the action slot`() = runTest {
        val f = Fixture().apply { authorization = CompletableDeferred() }
        val visit = f.visit()
        val first = async { visit.perform(NativeLinkedAccountAction.SIGN_OUT) }
        runCurrent()
        assertEquals(NativeLinkedAccountOutcome.BUSY, visit.perform(NativeLinkedAccountAction.DELETE, true))
        assertEquals(0, f.actions)
        f.authorization!!.complete(Unit)
        assertEquals(NativeLinkedAccountOutcome.COMPLETED, first.await())
        assertEquals(1, f.actions)
    }

    @Test fun `one visit owns one pending action and durable original revocation survives late host loss`() = runTest {
        val f = Fixture().apply { afterRevocation = CompletableDeferred() }
        val visit = f.visit()
        val operation = async { visit.perform(NativeLinkedAccountAction.SIGN_OUT) }
        runCurrent()
        assertTrue(visit.completingRevocation())
        // The same sheet must not start another POST while its first action is pending.
        assertEquals(NativeLinkedAccountOutcome.STALE, visit.perform(NativeLinkedAccountAction.DELETE, true))
        f.host = NativeVisitHost(Any(), "host-b") { true }
        f.afterRevocation!!.complete(Unit)
        assertEquals(NativeLinkedAccountOutcome.STALE, operation.await())
        assertEquals(1, f.actions)
        assertEquals(AccountAvailability.SIGNED_OUT, f.account!!.availability)
    }

    @Test fun `a newer account cannot be adopted as a successful old action`() = runTest {
        val f = Fixture().apply { afterRevocation = CompletableDeferred() }
        val visit = f.visit()
        val operation = async { visit.perform(NativeLinkedAccountAction.SIGN_OUT) }
        runCurrent()
        val successor = f.originalAccount.copy(generation = 6, userId = "account-b")
        f.account = successor
        f.afterRevocation!!.complete(Unit)
        assertEquals(NativeLinkedAccountOutcome.STALE, operation.await())
        assertEquals(successor, f.account)
    }

    @Test fun `failure without revocation stays retryable and cancellation is not a success`() = runTest {
        val f = Fixture(); val visit = f.visit()
        val real = f.signOut
        f.signOut = { _, _ -> error("controlled offline") }
        assertEquals(NativeLinkedAccountOutcome.FAILED, visit.perform(NativeLinkedAccountAction.SIGN_OUT))
        assertNotNull(visit.connections())
        f.signOut = { _, _ -> throw CancellationException("controlled cancellation") }
        assertTrue(runCatching { visit.perform(NativeLinkedAccountAction.SIGN_OUT) }.exceptionOrNull() is CancellationException)
        f.signOut = real
        assertEquals(NativeLinkedAccountOutcome.COMPLETED, visit.perform(NativeLinkedAccountAction.SIGN_OUT))
    }
}
