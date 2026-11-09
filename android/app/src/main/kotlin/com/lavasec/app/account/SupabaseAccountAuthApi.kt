package com.lavasec.app.account

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public project configuration only. Never accepts a service-role key from application code. */
internal class AccountServiceConfiguration(val projectUrl: HttpUrl, val publishableKey: String) {
    init {
        require(projectUrl.isHttps && projectUrl.encodedPath == "/" && projectUrl.query == null && projectUrl.fragment == null)
        require(projectUrl.username.isEmpty() && projectUrl.password.isEmpty())
        require(publishableKey.isNotBlank() && publishableKey.length <= 4096)
        if (!publishableKey.startsWith("sb_publishable_")) {
            val segments = publishableKey.split('.')
            require(segments.size == 3)
            val claims = JSONObject(String(java.util.Base64.getUrlDecoder().decode(segments[1]), Charsets.UTF_8))
            require(claims.optString("role") == "anon")
        }
    }
    override fun toString() = "AccountServiceConfiguration(redacted)"
}

internal class SupabaseAccountAuthApi(
    private val configuration: AccountServiceConfiguration,
    private val calls: Call.Factory = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .cache(null).build(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) : AccountAuthApi {
    override suspend fun exchangeGoogleIdToken(idToken: String, rawNonce: String): AccountSession {
        require(idToken.length in 1..32_768 && rawNonce.length in 16..512)
        return grant("id_token", JSONObject().put("provider", "google").put("id_token", idToken).put("nonce", rawNonce))
    }

    override suspend fun exchangeAppleIdToken(idToken: String, rawNonce: String): AccountSession {
        require(idToken.length in 1..32_768 && rawNonce.length in 16..512)
        return grant("id_token", JSONObject().put("provider", "apple").put("id_token", idToken).put("nonce", rawNonce))
    }

    override suspend fun exchangeApplePkceCode(authCode: String, codeVerifier: CharArray): AccountSession {
        require(authCode.length in 1..2048 && authCode.none { it.isWhitespace() || it.isISOControl() })
        require(codeVerifier.size in 43..128 && codeVerifier.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~"
        })
        return grant("pkce", JSONObject().put("auth_code", authCode).put("code_verifier", String(codeVerifier)))
    }

    override suspend fun refresh(refreshToken: String): AccountSession {
        require(refreshToken.length in 1..32_768)
        return grant("refresh_token", JSONObject().put("refresh_token", refreshToken))
    }

    override suspend fun signOut(accessToken: String) {
        val url = configuration.projectUrl.newBuilder().encodedPath("/auth/v1/logout").addQueryParameter("scope", "local").build()
        execute(Request.Builder().url(url).header("apikey", configuration.publishableKey)
            .header("Authorization", "Bearer $accessToken").post(ByteArray(0).toRequestBody()).build())
    }

    private suspend fun grant(type: String, body: JSONObject): AccountSession {
        val url = configuration.projectUrl.newBuilder().encodedPath("/auth/v1/token").addQueryParameter("grant_type", type).build()
        val response = execute(Request.Builder().url(url).header("apikey", configuration.publishableKey)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        return try { AccountSessionCodec.decode(response, nowSeconds()) }
        catch (_: Exception) { throw IOException("Invalid account service response") }
        finally { response.fill(0) }
    }

    private suspend fun execute(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Account service unavailable"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (it.code == 400 || it.code == 401 || it.code == 403) throw AccountAuthRejected()
                        if (!it.isSuccessful) throw IOException("Account service unavailable")
                        val body = it.body ?: return@use ByteArray(0)
                        if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Account response too large")
                        body.byteStream().use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("Account response too large")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }
    private companion object { const val MAX_RESPONSE_BYTES = 128 * 1024L }
}

/** Same Supabase session wire keys as iOS; expiry is mandatory and bounded at admission. */
internal object AccountSessionCodec {
    fun decode(bytes: ByteArray, nowSeconds: Long? = null): AccountSession {
        require(bytes.size <= 128 * 1024)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val user = json.getJSONObject("user")
        val userId = user.getString("id").lowercase()
        require(UUID.fromString(userId).toString() == userId)
        val access = json.getString("access_token")
        val refresh = json.getString("refresh_token")
        require(access.length in 1..32_768 && refresh.length in 1..32_768)
        val expiry = if (json.has("expires_at")) json.getLong("expires_at")
            else Math.addExact(requireNotNull(nowSeconds), json.getLong("expires_in"))
        require(expiry > 0)
        if (nowSeconds != null) require(expiry > nowSeconds && expiry <= nowSeconds + 86_400)
        val metadata = user.optJSONObject("app_metadata")
        val providers = mutableSetOf<String>()
        metadata?.optString("provider")?.takeIf { it.isNotBlank() }?.let(providers::add)
        metadata?.optJSONArray("providers")?.let { array ->
            require(array.length() <= 16)
            for (index in 0 until array.length()) providers.add(array.getString(index).lowercase())
        }
        require(providers.all { it.length in 1..64 })
        return AccountSession(access, refresh, expiry, userId,
            user.optString("email").takeIf { it.isNotBlank() && it != "null" }, providers)
    }

    fun encode(session: AccountSession): ByteArray = JSONObject()
        .put("access_token", session.accessToken).put("refresh_token", session.refreshToken)
        .put("expires_at", session.expiresAtSeconds)
        .put("user", JSONObject().put("id", session.userId).put("email", session.email)
            .put("app_metadata", JSONObject().put("providers", org.json.JSONArray(session.providers.toList()))))
        .toString().toByteArray(Charsets.UTF_8)
}
