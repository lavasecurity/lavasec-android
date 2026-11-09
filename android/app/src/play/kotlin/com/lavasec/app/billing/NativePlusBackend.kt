package com.lavasec.app.billing

import android.content.Context
import com.lavasec.app.BuildConfig
import com.lavasec.app.LavasecApplication
import com.lavasec.app.backend.AndroidAppService
import com.lavasec.app.foundation.subscription.LavaSecurityPlusPolicy
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.lavasec.app.foundation.subscription.FeatureLimits
import kotlinx.coroutines.launch
import com.lavasec.app.work.PlayReconciliationScheduler

/** One device plan, receipt cache and Play connection across credential graph rotations. */
internal class NativePlusBackend(context: Context, appStarted: Flow<Boolean>, appService: AndroidAppService?) : AutoCloseable {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val persistence = AndroidPlusPlanPersistence(application)
    val entitlement = NativePlusEntitlementOwner(persistence)
    val limits = entitlement.state.map { it.limits }.stateIn(scope, SharingStarted.Eagerly, FeatureLimits.FREE)
    private val verification = appService?.let(::PlayDeviceVerificationService)
    val billing: NativePlayBilling = NativePlayBilling(application, LavaSecurityPlusPolicy.paywallProductIDs.toSet(), ::purchaseChanged)
    val purchases: PlayPurchaseOwner = PlayPurchaseOwner(billing, entitlement, AndroidPlayReceiptStore(application)) {
        val service = verification ?: throw IOException("Purchase verification is not configured")
        service.verify(it)
    }

    val controller = NativePlusController(entitlement, purchases, billing::offers) {
        check(verification != null && BuildConfig.PLAY_CLOUD_PROJECT_NUMBER > 0) { "Purchase verification is not configured" }
        PlayReconciliationScheduler.installIfConfigured(application)
    }

    // Register before starting an observer: a concurrently terminated application cancels this
    // scope before it can enqueue work. Credential graph rotation keeps the process owner alive.
    init { (application as? LavasecApplication)?.registerPlusBackend(this) }

    private val observer = NativePlusProcessObserver(scope, appStarted,
        install = { PlayReconciliationScheduler.installIfConfigured(application) },
        loadProducts = controller::loadProducts, refreshPurchases = { purchases.refresh() })

    override fun close() {
        scope.cancel()
        billing.close()
    }

    suspend fun closeAndJoin() {
        try { close() } finally { scope.coroutineContext[Job]?.join() }
    }

    private fun purchaseChanged() { scope.launch { purchases.refresh() } }
}
