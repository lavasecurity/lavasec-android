package com.lavasec.app.account

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Actual native browser/callback -> Supabase Auth PKCE -> existing owner/store production path. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppleBrowserOAuthTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun restoreDispatcher() { ShadowLooper.idleMainLooper(); Dispatchers.resetMain() }

    @Test fun `production callback exchanges only Auth PKCE code and adopts the server linked identity`() = runBlocking {
        withServer { server, configuration, api ->
            server.enqueue(MockResponse().setBody(SESSION_JSON))
            val store = Store()
            val owner = AccountSessionOwner(api, store) { 100 }
            val host = Robolectric.buildActivity(Activity::class.java).setup()
            var authorizationUrl: String? = null
            var callbackUri: String? = null
            val client = SupabaseAppleBrowserOAuthClient(host.get(), configuration, launch = { _, id ->
                val management = Robolectric.buildActivity(AppleOAuthBrowserActivity::class.java,
                    Intent().putExtra(AppleOAuthBrowserActivity.REQUEST_ID, id)).create().start().resume()
                try {
                    val browserIntent = shadowOf(management.get()).nextStartedActivity
                    assertEquals(Intent.ACTION_VIEW, browserIntent.action)
                    assertTrue(browserIntent.categories.contains(Intent.CATEGORY_BROWSABLE))
                    val url = checkNotNull(browserIntent.dataString).toHttpUrl()
                    authorizationUrl = url.toString()
                    assertEquals(server.url("/").host, url.host)
                    assertEquals(server.port, url.port)
                    assertEquals("/auth/v1/authorize", url.encodedPath)
                    assertEquals("apple", url.queryParameter("provider"))
                    assertEquals("name email", url.queryParameter("scopes"))
                    assertEquals("s256", url.queryParameter("code_challenge_method"))
                    assertNull(url.queryParameter("state"))
                    val redirect = checkNotNull(url.queryParameter("redirect_to"))
                    assertEquals("${host.get().packageName}.auth://oauth/apple/$id", redirect)
                    assertTrue(AppleOAuthBrowserBroker.process.isPending(id))
                    callbackUri = "$redirect?code=browser-auth-code"
                    val callback = Robolectric.buildActivity(AppleOAuthCallbackActivity::class.java,
                        Intent(Intent.ACTION_VIEW, Uri.parse(callbackUri))).create()
                    assertTrue(callback.get().isFinishing)
                    assertNull(callback.get().intent.data)
                    callback.destroy()
                } finally { management.pause().stop().destroy() }
            })
            try {
                assertEquals(NativeSignInResult.SIGNED_IN, NativeAppleSignIn(owner, client).signIn(host.get()))
                val request = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/auth/v1/token?grant_type=pkce", request.path)
                assertEquals("sb_publishable_fixture", request.getHeader("apikey"))
                assertNull(request.getHeader("Authorization"))
                val body = JSONObject(request.body.readUtf8())
                assertEquals(setOf("auth_code", "code_verifier"), body.keys().asSequence().toSet())
                assertEquals("browser-auth-code", body.getString("auth_code"))
                val verifier = body.getString("code_verifier")
                assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
                val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
                assertEquals(expectedChallenge, checkNotNull(authorizationUrl).toHttpUrl().queryParameter("code_challenge"))
                assertFalse(checkNotNull(authorizationUrl).contains(verifier))
                assertEquals(USER, owner.state.value.userId)
                assertEquals(setOf("google", "apple"), owner.state.value.providers)
                assertEquals("server-access", checkNotNull(store.value).accessToken)
                assertEquals("server-refresh", checkNotNull(store.value).refreshToken)
                assertEquals(1, store.saves)
                assertFalse(owner.state.value.toString().contains("server-access"))
                assertFalse(owner.state.value.toString().contains("server-refresh"))
                assertFalse(AppleOAuthBrowserBroker.process.callback(checkNotNull(callbackUri)))
                assertEquals(1, server.requestCount)
            } finally { host.pause().stop().destroy() }
        }
    }

    @Test fun `backend gate binds a real client only after public provider activation flag`() {
        val application: Application = org.robolectric.RuntimeEnvironment.getApplication()
        assertFalse(AccountBackend(application, appleBrowserOAuthEnabled = false).appleSignIn.configured)
        assertTrue(AccountBackend(application, appleBrowserOAuthEnabled = true).appleSignIn.configured)
    }

    @Test fun `actual PKCE HTTP redirects malformed sessions and non Apple identity cannot replace prior account`() = runBlocking {
        withServer { server, configuration, api ->
            val refusals = listOf(
                MockResponse().setBody("{}"),
                MockResponse().setBody(SESSION_JSON.replace(USER, "not-a-valid-user")),
                MockResponse().setBody(SESSION_JSON.replace("[\"google\",\"apple\"]", "[\"google\"]")),
                MockResponse().setResponseCode(401).setBody("private-provider-response"),
                MockResponse().setResponseCode(302).setHeader("Location", "https://foreign.example/steal"),
            )
            for (response in refusals) {
                server.enqueue(response)
                val store = Store()
                val owner = AccountSessionOwner(api, store) { 100 }
                val host = Robolectric.buildActivity(Activity::class.java).setup()
                val broker = AppleOAuthBrowserBroker()
                val client = SupabaseAppleBrowserOAuthClient(host.get(), configuration, broker, launch = { _, id ->
                    broker.callback("${host.get().packageName}.auth://oauth/apple/$id?code=browser-auth-code")
                })
                try {
                    assertEquals(NativeSignInResult.FAILED, NativeAppleSignIn(owner, client).signIn(host.get()))
                    val request = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    assertEquals("/auth/v1/token?grant_type=pkce", request.path)
                    assertEquals(0, store.saves)
                    assertEquals(USER, store.value?.userId)
                    assertEquals(AccountAvailability.SIGNED_IN, owner.state.value.availability)
                } finally { host.pause().stop().destroy() }
            }
            assertEquals(refusals.size, server.requestCount)
        }
    }

    @Test fun `returning from actual browser host without callback cancels and preserves prior account`() = runBlocking {
        val fixture = Fixture()
        try {
            val client = fixture.client(AppleOAuthBrowserBroker.process) { _, id ->
                val management = Robolectric.buildActivity(AppleOAuthBrowserActivity::class.java,
                    Intent().putExtra(AppleOAuthBrowserActivity.REQUEST_ID, id)).create().start().resume()
                assertNotNull(shadowOf(management.get()).nextStartedActivity)
                management.pause().stop().restart().start().resume()
                assertTrue(management.get().isFinishing)
                management.pause().stop().destroy()
            }
            assertEquals(NativeSignInResult.CANCELLED, NativeAppleSignIn(fixture.owner, client).signIn(fixture.host.get()))
            fixture.assertPriorAccount()
        } finally { fixture.close() }
    }

    @Test fun `initiating Activity destruction cancels its exact pending browser attempt`() = runBlocking {
        val fixture = Fixture()
        try {
            var id: String? = null
            val client = fixture.client { _, requestId ->
                id = requestId
                fixture.host.pause().stop().destroy()
            }
            assertEquals(NativeSignInResult.CANCELLED, NativeAppleSignIn(fixture.owner, client).signIn(fixture.host.get()))
            assertFalse(fixture.broker.isPending(checkNotNull(id)))
            fixture.assertPriorAccount()
        } finally { fixture.close(destroyHost = false) }
    }

    @Test fun `destroying actual browser management Activity cancels without retaining a busy request`() = runBlocking {
        val fixture = Fixture()
        try {
            val client = fixture.client(AppleOAuthBrowserBroker.process) { _, id ->
                val management = Robolectric.buildActivity(AppleOAuthBrowserActivity::class.java,
                    Intent().putExtra(AppleOAuthBrowserActivity.REQUEST_ID, id)).create().start().resume()
                management.pause().stop().destroy()
                assertFalse(AppleOAuthBrowserBroker.process.isPending(id))
            }
            assertEquals(NativeSignInResult.CANCELLED, NativeAppleSignIn(fixture.owner, client).signIn(fixture.host.get()))
            fixture.assertPriorAccount()
        } finally { fixture.close() }
    }

    @Test fun `foreign and previous-attempt callback paths cannot retire or exchange current flow`() = runBlocking {
        val fixture = Fixture()
        try {
            var previous: String? = null
            val cancel = fixture.client { _, id ->
                previous = fixture.callback(id)
                fixture.broker.cancel(id)
            }
            assertEquals(NativeSignInResult.CANCELLED, NativeAppleSignIn(fixture.owner, cancel).signIn(fixture.host.get()))
            val client = fixture.client { _, id ->
                assertFalse(fixture.broker.callback(checkNotNull(previous)))
                val correct = fixture.callback(id)
                for (foreign in listOf(correct.replace(".auth:", ".foreign:"),
                    correct.replace("://oauth/", "://foreign/"),
                    correct.replace("/apple/$id", "/apple/someone-else"),
                    correct.replace("://oauth/", "://attacker@oauth/"))) {
                    assertFalse(fixture.broker.callback(foreign))
                    assertTrue(fixture.broker.isPending(id))
                }
                assertTrue(fixture.broker.callback(correct))
                assertFalse(fixture.broker.callback(correct))
            }
            assertEquals(NativeSignInResult.SIGNED_IN, NativeAppleSignIn(fixture.owner, client).signIn(fixture.host.get()))
            assertEquals(1, fixture.api.exchanges)
            assertEquals(1, fixture.store.saves)
        } finally { fixture.close() }
    }

    @Test fun `matching malformed implicit-token and provider-error callbacks never exchange or log payload`() = runBlocking {
        for (suffix in listOf("?code=", "?code=a&code=b", "?%63ode=a&code=b", "?code=a&error=access_denied",
            "?code=a&unknown=field", "?code=a#access_token=must-not-adopt",
            "?error=server_error&error_description=private-provider-detail", "?code=%0Ahidden")) {
            val fixture = Fixture()
            try {
                val client = fixture.client { _, id ->
                    assertTrue(fixture.broker.callback(fixture.base(id) + suffix))
                    assertFalse(fixture.broker.isPending(id))
                }
                assertEquals(NativeSignInResult.FAILED, NativeAppleSignIn(fixture.owner, client).signIn(fixture.host.get()))
                fixture.assertPriorAccount()
            } finally { fixture.close() }
        }
        assertEquals("AppleOAuthCallback.Code(redacted)", AppleOAuthCallback.Code("private-code").toString())
        assertEquals("Apple authorization could not be completed", AppleBrowserAuthorizationFailed().message)
    }

    @Test fun `provider denial and bounded browser timeout release admission for the next attempt`() = runBlocking {
        val fixture = Fixture()
        try {
            val denied = fixture.client { _, id ->
                fixture.broker.callback(fixture.base(id) + "?error=access_denied&error_description=cancel-detail")
            }
            assertEquals(NativeSignInResult.CANCELLED, NativeAppleSignIn(fixture.owner, denied).signIn(fixture.host.get()))
            val timedOut = SupabaseAppleBrowserOAuthClient(fixture.host.get(), fixture.configuration, fixture.broker,
                launch = { _, _ -> }, timeoutMillis = 20)
            val native = NativeAppleSignIn(fixture.owner, timedOut)
            assertEquals(NativeSignInResult.CANCELLED, native.signIn(fixture.host.get()))
            assertEquals(NativeSignInResult.CANCELLED, native.signIn(fixture.host.get()))
            fixture.assertPriorAccount()
        } finally { fixture.close() }
    }

    @Test fun `coroutine cancellation clears broker and process-lost callbacks cannot create sessions`() = runBlocking {
        val fixture = Fixture()
        val opened = CompletableDeferred<String>()
        val login = async { NativeAppleSignIn(fixture.owner, fixture.client { _, id -> opened.complete(id) }).signIn(fixture.host.get()) }
        try {
            val id = withTimeout(2_000) { opened.await() }
            login.cancelAndJoin()
            assertFalse(fixture.broker.isPending(id))
            assertFalse(fixture.broker.callback(fixture.callback(id)))
            assertFalse(AppleOAuthBrowserBroker().callback(fixture.callback(id)))
            val callback = Robolectric.buildActivity(AppleOAuthCallbackActivity::class.java,
                Intent(Intent.ACTION_VIEW, Uri.parse(fixture.callback(id)))).create()
            assertTrue(callback.get().isFinishing)
            assertNull(callback.get().intent.data)
            callback.destroy()
            fixture.assertPriorAccount()
        } finally { login.cancelAndJoin(); fixture.close() }
    }

    @Test fun `account switch while browser is open retires it without exchanging a late code`() = runBlocking {
        val fixture = Fixture()
        val opened = CompletableDeferred<String>()
        val login = async { NativeAppleSignIn(fixture.owner, fixture.client { _, id -> opened.complete(id) }).signIn(fixture.host.get()) }
        try {
            val id = withTimeout(2_000) { opened.await() }
            val current = fixture.owner.beginSignIn()
            fixture.owner.finishGoogleSignIn(current, "new-google-identity")
            assertEquals(NativeSignInResult.SUPERSEDED, withTimeout(2_000) { login.await() })
            assertFalse(fixture.broker.callback(fixture.callback(id)))
            assertEquals(0, fixture.api.exchanges)
            assertEquals(OTHER_USER, fixture.owner.state.value.userId)
            assertEquals(OTHER_USER, fixture.store.value?.userId)
        } finally { login.cancelAndJoin(); fixture.close() }
    }

    @Test fun `account switch during code exchange refuses stale durability and wipes borrowed verifier`() = runBlocking {
        val fixture = Fixture()
        fixture.api.gate = CompletableDeferred()
        val login = async { NativeAppleSignIn(fixture.owner,
            fixture.client { _, id -> fixture.broker.callback(fixture.callback(id)) }).signIn(fixture.host.get()) }
        try {
            withTimeout(2_000) { fixture.api.started.await() }
            val current = fixture.owner.beginSignIn()
            fixture.owner.finishGoogleSignIn(current, "new-google-identity")
            fixture.api.gate!!.complete(Unit)
            assertEquals(NativeSignInResult.SUPERSEDED, withTimeout(2_000) { login.await() })
            assertEquals(OTHER_USER, fixture.store.value?.userId)
            assertEquals(1, fixture.store.saves)
            assertTrue(checkNotNull(fixture.api.borrowedVerifier).all { it == '\u0000' })
        } finally { fixture.api.gate?.complete(Unit); login.cancelAndJoin(); fixture.close() }
    }

    @Test fun `initiating Activity destruction during suspended code exchange cancels and drains the admission`() = runBlocking {
        val fixture = Fixture()
        fixture.api.gate = CompletableDeferred()
        val login = async { NativeAppleSignIn(fixture.owner,
            fixture.client { _, id -> fixture.broker.callback(fixture.callback(id)) }).signIn(fixture.host.get()) }
        try {
            withTimeout(2_000) { fixture.api.started.await() }
            fixture.host.pause().stop().destroy()
            assertEquals(NativeSignInResult.CANCELLED, withTimeout(2_000) { login.await() })
            assertEquals(0, fixture.store.saves)
            assertEquals(USER, fixture.store.value?.userId)
            assertEquals(AccountAvailability.SIGNED_IN, fixture.owner.state.value.availability)
            assertTrue(checkNotNull(fixture.api.borrowedVerifier).all { it == '\u0000' })
        } finally { login.cancelAndJoin(); fixture.close(destroyHost = false) }
    }

    @Test fun `exchange cancellation failure wrong-provider and failed durable save wipe verifier and preserve prior session`() = runBlocking {
        for (mode in listOf("cancel", "exchange", "provider", "storage")) {
            val fixture = Fixture()
            fixture.api.fail = mode == "exchange"
            fixture.api.providers = if (mode == "provider") setOf("google") else setOf("google", "apple")
            fixture.store.failSave = mode == "storage"
            if (mode == "cancel") fixture.api.gate = CompletableDeferred()
            val login = async { NativeAppleSignIn(fixture.owner,
                fixture.client { _, id -> fixture.broker.callback(fixture.callback(id)) }).signIn(fixture.host.get()) }
            try {
                withTimeout(2_000) { fixture.api.started.await() }
                if (mode == "cancel") login.cancelAndJoin()
                else assertEquals(NativeSignInResult.FAILED, withTimeout(2_000) { login.await() })
                assertEquals(USER, fixture.store.value?.userId)
                assertEquals(AccountAvailability.SIGNED_IN, fixture.owner.state.value.availability)
                assertEquals(0, fixture.store.saves)
                assertTrue(checkNotNull(fixture.api.borrowedVerifier).all { it == '\u0000' })
            } finally { login.cancelAndJoin(); fixture.close() }
        }
    }

    private class Fixture : AutoCloseable {
        val configuration = AccountServiceConfiguration("https://project.example/".toHttpUrl(), "sb_publishable_fixture")
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val broker = AppleOAuthBrowserBroker()
        val api = Api()
        val store = Store()
        val owner = AccountSessionOwner(api, store) { 100 }
        fun base(id: String) = "${host.get().packageName}.auth://oauth/apple/$id"
        fun callback(id: String) = base(id) + "?code=browser-auth-code"
        fun client(browser: AppleOAuthBrowserBroker = broker, launch: (Activity, String) -> Unit) =
            SupabaseAppleBrowserOAuthClient(host.get(), configuration, browser, launch)
        fun assertPriorAccount() {
            assertEquals(USER, store.value?.userId)
            assertEquals(AccountAvailability.SIGNED_IN, owner.state.value.availability)
            assertEquals(0, store.saves)
            assertEquals(0, api.exchanges)
        }
        fun close(destroyHost: Boolean) { if (destroyHost) host.pause().stop().destroy() }
        override fun close() = close(destroyHost = true)
    }

    private class Store : AccountSessionStore {
        var value: AccountSession? = session()
        var saves = 0
        var failSave = false
        override suspend fun load() = value
        override suspend fun save(session: AccountSession) {
            if (failSave) error("controlled storage refusal")
            saves++; value = session
        }
        override suspend fun clear() { value = null }
    }
    private class Api : AccountAuthApi {
        var exchanges = 0
        var borrowedVerifier: CharArray? = null
        var providers = setOf("google", "apple")
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun exchangeGoogleIdToken(idToken: String, rawNonce: String) = session(OTHER_USER)
        override suspend fun exchangeApplePkceCode(authCode: String, codeVerifier: CharArray): AccountSession {
            assertEquals("browser-auth-code", authCode)
            exchanges++; borrowedVerifier = codeVerifier; started.complete(Unit)
            gate?.await()
            if (fail) error("controlled exchange refusal")
            return session(providers = providers)
        }
        override suspend fun refresh(refreshToken: String) = session()
        override suspend fun signOut(accessToken: String) = Unit
    }

    private suspend fun withServer(block: suspend (MockWebServer, AccountServiceConfiguration, SupabaseAccountAuthApi) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.SECONDS).build()
        val configuration = AccountServiceConfiguration(server.url("/"), "sb_publishable_fixture")
        try { block(server, configuration, SupabaseAccountAuthApi(configuration, client) { 100 }) }
        finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    private companion object {
        const val USER = "10000000-0000-0000-0000-000000000000"
        const val OTHER_USER = "20000000-0000-0000-0000-000000000000"
        const val SESSION_JSON = """{"access_token":"server-access","refresh_token":"server-refresh","expires_in":3600,"user":{"id":"10000000-0000-0000-0000-000000000000","app_metadata":{"providers":["google","apple"]}}}"""
        fun session(user: String = USER, providers: Set<String> = setOf("google")) =
            AccountSession("old-access", "old-refresh", 3600, user, null, providers)
    }
}
