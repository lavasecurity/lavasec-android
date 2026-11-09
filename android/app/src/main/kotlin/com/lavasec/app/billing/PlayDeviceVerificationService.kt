package com.lavasec.app.billing

import com.lavasec.app.backend.AndroidAppService
import com.lavasec.app.backend.AndroidServiceAction
import com.lavasec.app.backend.ServiceHttpFailure
import com.lavasec.app.foundation.subscription.LavaSecurityPlusPolicy
import org.json.JSONObject
import java.time.Instant

/** Fresh server verification substitutes for StoreKit's verified transaction, including signed-out use. */
internal class PlayDeviceVerificationService(
    private val service: AndroidAppService,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun verify(purchase: PlayPurchase): VerifiedPlayPurchase {
        require(purchase.purchased && purchase.token.length in 1..4096 &&
            purchase.token.none { it.isWhitespace() || it.code < 32 || it.code == 127 })
        val body = JSONObject().put("purchase_token", purchase.token).toString().toByteArray(Charsets.UTF_8)
        val bytes = try {
            service.post(AndroidServiceAction.VERIFY_PURCHASE, body)
        } catch (failure: ServiceHttpFailure) {
            if (failure.status != 410) throw failure
            // This route preserves only Publisher's expired-retention result as 410. The owner
            // can demote only after rechecking its complete retained set of verified grants.
            val product = purchase.products.filter { it in LavaSecurityPlusPolicy.paywallProductIDs }.single()
            return VerifiedPlayPurchase(product, false, null, false)
        }
        val response = JSONObject(bytes.toString(Charsets.UTF_8))
        require(response.getBoolean("verified") && response.getString("source") == "google-play" &&
            response.getString("plan") == "lava_security_plus" && response.has("account_id") && response.isNull("account_id"))
        val product = response.getString("product_id")
        require(product in purchase.products && product in LavaSecurityPlusPolicy.paywallProductIDs)
        val checked = Instant.parse(response.getString("checked_at"))
        val validUntil = Instant.parse(response.getString("valid_until"))
        val expiry = if (response.isNull("expires_at")) null else Instant.parse(response.getString("expires_at"))
        val currentTime = now()
        require(checked <= currentTime.plusSeconds(60) && checked >= currentTime.minusSeconds(300))
        require(validUntil >= checked && validUntil <= checked.plusSeconds(300))
        val active = response.getBoolean("active")
        if (active) require(expiry != null && validUntil > currentTime && validUntil <= expiry)
        val acknowledge = response.getBoolean("acknowledgement_required")
        require(!acknowledge || active)
        return VerifiedPlayPurchase(product, active, expiry, acknowledge)
    }
}
