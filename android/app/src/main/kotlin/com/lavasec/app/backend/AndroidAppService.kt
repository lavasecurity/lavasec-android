package com.lavasec.app.backend

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Request-bound application authentication, without requiring a Lava account. */
internal class AndroidAppService(
    private val origin: HttpUrl,
    private val integrity: PlayIntegrityTokenPort,
    private val http: BoundedServiceHttp = BoundedServiceHttp(),
) {
    init {
        require(origin.isHttps && origin.encodedPath == "/" && origin.query == null && origin.fragment == null &&
            origin.username.isEmpty() && origin.password.isEmpty())
    }

    suspend fun post(action: AndroidServiceAction, body: ByteArray): ByteArray {
        // Freeze the bytes before suspension; hashing and sending must observe the same body.
        val exact = body.copyOf()
        val token = integrity.token(playIntegrityRequestHash(action, exact))
        require(token.isNotBlank() && token.length <= 32 * 1024 && token.none { it.isWhitespace() || it.code < 32 || it.code == 127 })
        return http.execute(Request.Builder().url(origin.newBuilder().encodedPath(action.path).build())
            .header("Accept", "application/json").header("X-Lava-Play-Integrity", token)
            .post(exact.toRequestBody("application/json".toMediaType())).build(), 16 * 1024)
    }
}
