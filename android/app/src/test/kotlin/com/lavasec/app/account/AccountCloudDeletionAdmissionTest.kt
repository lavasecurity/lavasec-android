package com.lavasec.app.account

import android.app.Application
import com.lavasec.app.backend.BoundedServiceHttp
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No sockets or live deletion: the real bounded HTTP/parser sees controlled Call callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountCloudDeletionAdmissionTest {
    private class Store(var value: AccountSession?) : AccountSessionStore {
        var clearBarrier: CompletableDeferred<Unit>? = null
        val clearing = CompletableDeferred<Unit>()
        override suspend fun load() = value
        override suspend fun save(session: AccountSession) { value = session }
        override suspend fun clear() { clearing.complete(Unit); clearBarrier?.await(); value = null }
    }
    private class Api : AccountAuthApi {
        var refreshBarrier: CompletableDeferred<Unit>? = null
        var refreshes = 0
        var signOuts = 0
        var loginUser = "original"
        override suspend fun exchangeGoogleIdToken(idToken: String, rawNonce: String) = session(user = loginUser)
        override suspend fun refresh(refreshToken: String): AccountSession {
            refreshes++; refreshBarrier?.await(); return session(access = "refreshed")
        }
        override suspend fun signOut(accessToken: String) { signOuts++ }
    }
    private class PendingCall(private val outgoing: Request) : Call {
        private var callback: Callback? = null
        private var executed = false
        private var cancelled = false
        override fun request() = outgoing
        override fun execute(): Response = error("Only controlled async callbacks are supported")
        override fun enqueue(responseCallback: Callback) { check(!executed); executed = true; callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = PendingCall(outgoing)
        fun respond(deleted: Boolean) {
            val response = Response.Builder().request(outgoing).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{\"deleted\":$deleted}".toResponseBody("application/json".toMediaType())).build()
            callback!!.onResponse(this, response)
        }
        fun fail() { callback!!.onFailure(this, IOException("controlled offline")) }
    }
    private class Calls : Call.Factory {
        val made = mutableListOf<PendingCall>()
        override fun newCall(request: Request): Call = PendingCall(request).also { made += it }
    }
    private class Fixture(expired: Boolean = false) {
        val api = Api()
        val store = Store(session(expiry = if (expired) 1 else 3600))
        val accounts = AccountSessionOwner(api, store) { 100 }
        val calls = Calls()
        val cloud = AccountCloudService("https://backend.example.test/".toHttpUrl(), accounts, BoundedServiceHttp(calls)) { 100 }
        var admitted = true
    }

    @Test fun `stale before admission cannot refresh or construct a deletion request`() = runTest {
        val f = Fixture(expired = true)
        f.accounts.initialize(); val before = f.accounts.state.value
        assertTrue(runCatching { f.cloud.deleteAccount(before.generation) { false } }.exceptionOrNull() is AccountSessionChanged)
        assertEquals(0, f.api.refreshes)
        assertTrue(f.calls.made.isEmpty())
        assertEquals(before, f.accounts.state.value)
        assertNotNull(f.store.value)
    }

    @Test fun `lost original visit during refresh refuses POST while retaining the refreshed same account`() = runTest {
        val f = Fixture(expired = true).apply { api.refreshBarrier = CompletableDeferred() }
        f.accounts.initialize(); val before = f.accounts.state.value
        val deletion = async { runCatching { f.cloud.deleteAccount(before.generation) { f.admitted && f.accounts.state.value == before } }.exceptionOrNull() }
        runCurrent(); assertEquals(1, f.api.refreshes)
        f.admitted = false; f.api.refreshBarrier!!.complete(Unit)
        assertTrue(deletion.await() is AccountSessionChanged)
        assertTrue(f.calls.made.isEmpty())
        assertEquals(before, f.accounts.state.value)
        assertEquals("refreshed", f.store.value!!.accessToken)
        assertEquals(0, f.api.signOuts)
    }

    @Test fun `same-account refresh keeps native identity and can legitimately delete`() = runTest {
        val f = Fixture(expired = true).apply { api.refreshBarrier = CompletableDeferred() }
        f.accounts.initialize(); val before = f.accounts.state.value
        val deletion = async { f.cloud.deleteAccount(before.generation) { f.admitted && f.accounts.state.value == before } }
        runCurrent(); f.api.refreshBarrier!!.complete(Unit); runCurrent()
        assertEquals(before, f.accounts.state.value)
        assertEquals(1, f.calls.made.size)
        assertEquals("/v1/account/delete", f.calls.made.single().request().url.encodedPath)
        assertEquals("Bearer refreshed", f.calls.made.single().request().header("Authorization"))
        f.calls.made.single().respond(true)
        deletion.await()
        assertEquals(AccountAvailability.SIGNED_OUT, f.accounts.state.value.availability)
        assertNull(f.store.value)
        assertEquals(1, f.api.signOuts)
    }

    @Test fun `acknowledged deletion cleans the original account even after host authority is lost`() = runTest {
        val f = Fixture(); f.accounts.initialize()
        val before = f.accounts.state.value
        val deletion = async { f.cloud.deleteAccount(before.generation) { f.admitted } }
        runCurrent(); assertEquals(1, f.calls.made.size)
        f.admitted = false; f.calls.made.single().respond(true)
        deletion.await()
        assertEquals(before.generation + 1, f.accounts.state.value.generation)
        assertEquals(AccountAvailability.SIGNED_OUT, f.accounts.state.value.availability)
        assertNull(f.store.value)
    }

    @Test fun `cancelled UI after acknowledgement cannot abandon durable local cleanup`() = runTest {
        val f = Fixture().apply { store.clearBarrier = CompletableDeferred() }
        f.accounts.initialize()
        val deletion = async { f.cloud.deleteAccount(f.accounts.state.value.generation) { f.admitted } }
        runCurrent(); f.calls.made.single().respond(true); runCurrent()
        assertTrue(f.store.clearing.isCompleted)
        deletion.cancel(); runCurrent()
        f.store.clearBarrier!!.complete(Unit)
        deletion.join()
        assertTrue(deletion.isCancelled)
        assertNull(f.store.value)
        assertEquals(AccountAvailability.SIGNED_OUT, f.accounts.state.value.availability)
    }

    @Test fun `late old deletion acknowledgement cannot sign out successor credentials`() = runTest {
        val f = Fixture(); f.accounts.initialize()
        val original = f.accounts.state.value.generation
        val deletion = async { runCatching { f.cloud.deleteAccount(original) { f.admitted } }.exceptionOrNull() }
        runCurrent()
        f.api.loginUser = "successor"
        val login = f.accounts.beginSignIn()
        f.accounts.finishGoogleSignIn(login, "controlled-id-token")
        val successor = f.accounts.state.value
        f.calls.made.single().respond(true)
        assertTrue(deletion.await() is AccountSessionChanged)
        assertEquals(successor, f.accounts.state.value)
        assertEquals("successor", f.store.value!!.userId)
        assertEquals(0, f.api.signOuts)
    }

    @Test fun `failed response and declined deletion never revoke the signed-in account`() = runTest {
        for (offline in listOf(false, true)) {
            val f = Fixture(); f.accounts.initialize(); val before = f.accounts.state.value
            val deletion = async { runCatching { f.cloud.deleteAccount(before.generation) { true } }.exceptionOrNull() }
            runCurrent()
            if (offline) f.calls.made.single().fail() else f.calls.made.single().respond(false)
            assertNotNull(deletion.await())
            assertEquals(before, f.accounts.state.value)
            assertNotNull(f.store.value)
            assertEquals(0, f.api.signOuts)
        }
    }

    private companion object {
        fun session(access: String = "original-access", expiry: Long = 3600, user: String = "original") =
            AccountSession(access, "controlled-refresh", expiry, user, "a@example.test", setOf("google"))
    }
}
