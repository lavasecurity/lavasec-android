package com.lavasec.app.billing

import android.app.Activity

/** Source-set refusal adapter: FOSS never constructs a store SDK or calls the binding supplier. */
internal class NativePlayBilling : PlayPurchasePort, AutoCloseable {
    override suspend fun purchases(): List<PlayPurchase> = throw PlayBillingUnavailable()
    override suspend fun acknowledge(token: String): Unit = throw PlayBillingUnavailable()
    suspend fun offers(): List<PlayOffer> = emptyList()
    suspend fun launch(activity: Activity, offerHandle: String, accountBinding: () -> String? = { null }): PlayLaunchResult =
        PlayLaunchResult.UNAVAILABLE
    override fun close() = Unit
}
