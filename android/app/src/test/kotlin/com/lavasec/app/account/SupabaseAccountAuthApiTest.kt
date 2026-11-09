package com.lavasec.app.account

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SupabaseAccountAuthApiTest {
    @Test fun `native token and refresh requests use the fixed project and retain rotated tokens`() = runTest {
        server { server, api ->
            server.enqueue(MockResponse().setBody(sessionJson()))
            val session = api.exchangeGoogleIdToken("provider-identity-token", "nonce-with-enough-entropy")
            assertEquals("refresh-rotated", session.refreshToken)
            val request = server.takeRequest()
            assertEquals("/auth/v1/token?grant_type=id_token", request.path)
            assertEquals("sb_publishable_fixture", request.getHeader("apikey"))
            assertNull(request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("google", body.getString("provider"))
            assertEquals("nonce-with-enough-entropy", body.getString("nonce"))
            server.enqueue(MockResponse().setBody(sessionJson()))
            api.refresh("old-refresh")
            val refresh = server.takeRequest()
            assertEquals("/auth/v1/token?grant_type=refresh_token", refresh.path)
            assertEquals("old-refresh", JSONObject(refresh.body.readUtf8()).getString("refresh_token"))
        }
    }

    @Test fun `apple token exchange uses the apple provider and the fixed project`() = runTest {
        server { server, api ->
            server.enqueue(MockResponse().setBody(sessionJson()))
            api.exchangeAppleIdToken("apple-identity-token", "nonce-with-enough-entropy")
            val request = server.takeRequest()
            assertEquals("/auth/v1/token?grant_type=id_token", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("apple", body.getString("provider"))
            assertEquals("apple-identity-token", body.getString("id_token"))
            assertEquals("nonce-with-enough-entropy", body.getString("nonce"))
        }
    }

    @Test fun `redirects and excessive responses are refused without exposing response secrets`() = runTest {        server { server, api ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/steal"))
            assertTrue(runCatching { api.refresh("refresh") }.isFailure)
            server.enqueue(MockResponse().setBody("x".repeat(128 * 1024 + 1)))
            assertTrue(runCatching { api.refresh("refresh") }.isFailure)
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret-server-detail"))
            val failure = runCatching { api.refresh("refresh") }.exceptionOrNull()
            assertTrue(failure is AccountAuthRejected)
            assertFalse(failure.toString().contains("secret-server-detail"))
        }
    }

    private suspend fun server(block: suspend (MockWebServer, SupabaseAccountAuthApi) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        try {
            block(server, SupabaseAccountAuthApi(AccountServiceConfiguration(server.url("/"), "sb_publishable_fixture"), client) { 100 })
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    private fun sessionJson() = """{"access_token":"access","refresh_token":"refresh-rotated","expires_in":3600,"user":{"id":"10000000-0000-0000-0000-000000000000","app_metadata":{"provider":"google"}}}"""
}
