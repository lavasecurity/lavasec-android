package com.lavasec.app.backend

import com.lavasec.app.account.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

internal class ServiceTestFixture : AutoCloseable {
    val server = MockWebServer()
    private val client: OkHttpClient
    val http: BoundedServiceHttp
    val accounts = AccountSessionOwner(object : AccountAuthApi {
        override suspend fun exchangeGoogleIdToken(idToken: String, rawNonce: String) = session()
        override suspend fun refresh(refreshToken: String) = session()
        override suspend fun signOut(accessToken: String) = Unit
    }, object : AccountSessionStore {
        var stored: AccountSession? = session()
        override suspend fun load() = stored
        override suspend fun save(session: AccountSession) { stored = session }
        override suspend fun clear() { stored = null }
    }) { NOW }
    val configuration: AccountServiceConfiguration
    init {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        http = BoundedServiceHttp(client)
        configuration = AccountServiceConfiguration(server.url("/"), "sb_publishable_fixture")
    }
    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }
    companion object {
        const val USER = "10000000-0000-0000-0000-000000000000"
        const val NOW = 1_000L
        fun session() = AccountSession("access", "refresh", NOW + 3600, USER, null, setOf("google"))
    }
}
