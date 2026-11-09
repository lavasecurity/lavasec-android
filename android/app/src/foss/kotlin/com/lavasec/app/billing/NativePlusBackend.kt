package com.lavasec.app.billing

import android.content.Context
import com.lavasec.app.backend.AndroidAppService
import com.lavasec.app.foundation.subscription.FeatureLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local capability authority. No SDK, receipt disk access, observer, jobs or network is started. */
internal class NativePlusBackend(context: Context, appStarted: Flow<Boolean>, appService: AndroidAppService?) : AutoCloseable {
    val persistence = AndroidPlusPlanPersistence(context.applicationContext, localCapabilities = true)
    val entitlement = NativePlusEntitlementOwner(persistence, localCapabilities = true)
    val limits: StateFlow<FeatureLimits> = MutableStateFlow(FeatureLimits.FOSS).asStateFlow()
    val billing = NativePlayBilling()
    private val noReceipts = object : PlayReceiptCache {
        override suspend fun load(): List<PlayPurchase> = emptyList()
        override suspend fun save(receipts: List<PlayPurchase>) { check(receipts.isEmpty()) }
    }
    val purchases = PlayPurchaseOwner(billing, entitlement, noReceipts) { throw PlayBillingUnavailable() }
    val controller = NativePlusController(entitlement, purchases, billing::offers) { throw PlayBillingUnavailable() }
    override fun close() = Unit
    suspend fun closeAndJoin() = Unit
}
