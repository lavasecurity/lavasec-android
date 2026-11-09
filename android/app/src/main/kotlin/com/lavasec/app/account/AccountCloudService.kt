package com.lavasec.app.account

import com.lavasec.app.backend.BoundedServiceHttp
import java.time.Instant
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A short-lived verified purchase verdict, never a user-editable Plus preference. */
internal data class GooglePlayVerdict(
    val accountId: String,
    val accountGeneration: Long,
    val productId: String,
    val active: Boolean,
    val expiresAtEpochSeconds: Long?,
    val validUntilEpochSeconds: Long,
    val acknowledgementRequired: Boolean,
)

internal class AccountCloudService(
    private val origin: HttpUrl,
    private val accounts: AccountSessionOwner,
    private val http: BoundedServiceHttp = BoundedServiceHttp(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    init { require(origin.isHttps && origin.encodedPath == "/" && origin.query == null && origin.fragment == null && origin.username.isEmpty() && origin.password.isEmpty()) }

    suspend fun verifyGooglePlayPurchase(purchaseToken: String): GooglePlayVerdict {
        require(purchaseToken.length in 1..4096)
        val generation = accounts.state.value.generation
        return accounts.withSession { session ->
            accounts.requireCurrent(generation)
            val body = JSONObject().put("purchase_token", purchaseToken).toString()
            val response = JSONObject(http.execute(request("/v1/account/entitlements/google-play-verify", session, body), 16 * 1024).toString(Charsets.UTF_8))
            require(response.getBoolean("verified") && response.getString("source") == "google-play")
            require(response.getString("plan") == "lava_security_plus" && response.getString("account_id") == GooglePlayAccountBinding.forUser(session.userId))
            val checked = Instant.parse(response.getString("checked_at")).epochSecond
            val validUntil = Instant.parse(response.getString("valid_until")).epochSecond
            val expires = if (response.isNull("expires_at")) null else Instant.parse(response.getString("expires_at")).epochSecond
            val active = response.getBoolean("active")
            val product = response.getString("product_id")
            require(product.length in 1..200)
            require(checked <= nowSeconds() + 60 && checked >= nowSeconds() - 300)
            require(validUntil >= checked && validUntil <= checked + 300)
            if (active) require(expires != null && validUntil > nowSeconds() && validUntil <= expires)
            GooglePlayVerdict(session.userId, generation, product, active, expires, validUntil,
                response.getBoolean("acknowledgement_required"))
        }
    }

    /** Call only after the native/product confirmation flow has authorized deletion. */
    suspend fun deleteAccount(expectedGeneration: Long, isCurrent: () -> Boolean = { true }) {
        accounts.requireCurrent(expectedGeneration)
        if (!isCurrent()) throw AccountSessionChanged()
        var acknowledged = false
        try {
            accounts.withSession(expectedGeneration) { session ->
                accounts.requireCurrent(expectedGeneration)
                // withSession may have awaited token refresh. Never POST for a replacement
                // host/account/grant just because the original request was admitted earlier.
                if (!isCurrent()) throw AccountSessionChanged()
                val response = JSONObject(http.execute(request("/v1/account/delete", session, "{}"), 16 * 1024).toString(Charsets.UTF_8))
                require(response.getBoolean("deleted"))
                acknowledged = true
            }
        } finally {
            if (acknowledged) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                // Server deletion is real now: retire only its original local generation,
                // even when its late UI acknowledgement is no longer admissible.
                try { accounts.signOut(expectedGeneration) }
                catch (_: AccountSessionChanged) { /* A newer account owns its credentials. */ }
            }
        }
    }

    private fun request(path: String, session: AccountSession, body: String): Request = Request.Builder()
        .url(origin.newBuilder().encodedPath(path).build()).header("Authorization", "Bearer ${session.accessToken}")
        .header("Accept", "application/json").post(body.toRequestBody("application/json".toMediaType())).build()
}
