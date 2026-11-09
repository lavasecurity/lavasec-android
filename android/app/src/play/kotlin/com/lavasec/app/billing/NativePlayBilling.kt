package com.lavasec.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One process connection, receipts stay native, and launches use a recently queried exact offer. */
internal class NativePlayBilling(context: Context, private val productIds: Set<String>, onChanged: () -> Unit) : PlayPurchasePort, AutoCloseable {
    private val connection = Mutex()
    private val lifetime: NativePlayBillingLifetime by lazy { NativePlayBillingLifetime(onChanged) { client.endConnection() } }
    private data class CachedOffer(val detail: ProductDetails, val token: String, val expires: Long)
    private val offers = mutableMapOf<String, CachedOffer>() // main dispatcher
    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            if (!lifetime.isOpen) return@setListener
            // Always re-query owned purchases; never trust or export the callback's receipt.
            if (result.responseCode == BillingClient.BillingResponseCode.OK ||
                result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) lifetime.purchaseChanged()
            val completion = when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> if (purchases.orEmpty().any {
                    it.purchaseState == Purchase.PurchaseState.PENDING
                }) PlayLaunchResult.PENDING else PlayLaunchResult.PURCHASED
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PlayLaunchResult.ALREADY_OWNED
                BillingClient.BillingResponseCode.USER_CANCELED -> PlayLaunchResult.CANCELLED
                else -> PlayLaunchResult.UNAVAILABLE
            }
            lifetime.completeLaunch(completion)
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
        .enableAutoServiceReconnection().build()

    private suspend fun connect() = connection.withLock {
        lifetime.ensureOpen()
        if (client.isReady) return@withLock
        awaitPlayOperation {
            suspendCancellableCoroutine<Unit> { continuation ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (!continuation.isActive) return
                        if (!lifetime.isOpen) continuation.resumeWithException(PlayBillingUnavailable())
                        else if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(Unit)
                        else continuation.resumeWithException(PlayBillingUnavailable())
                    }
                    override fun onBillingServiceDisconnected() = Unit
                })
            }
        }
    }

    override suspend fun purchases(): List<PlayPurchase> = withContext(Dispatchers.Main.immediate) {
        connect()
        lifetime.ensureOpen()
        val receipts: List<PlayPurchase> = awaitPlayOperation {
            suspendCancellableCoroutine { continuation ->
                client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
                    if (!continuation.isActive) return@queryPurchasesAsync
                    if (!lifetime.isOpen || result.responseCode != BillingClient.BillingResponseCode.OK) continuation.resumeWithException(PlayBillingUnavailable())
                    else continuation.resume(purchases.map { PlayPurchase(it.purchaseToken, it.products.toSet(), it.purchaseState == Purchase.PurchaseState.PURCHASED, it.isAcknowledged) })
                }
            }
        }
        lifetime.ensureOpen()
        receipts
    }

    override suspend fun acknowledge(token: String): Unit = withContext(Dispatchers.Main.immediate) {
        connect()
        lifetime.ensureOpen()
        awaitPlayOperation {
            suspendCancellableCoroutine { continuation ->
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { result ->
                    if (!continuation.isActive) return@acknowledgePurchase
                    if (!lifetime.isOpen) continuation.resumeWithException(PlayBillingUnavailable())
                    else if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(Unit)
                    else continuation.resumeWithException(PlayBillingUnavailable())
                }
            }
        }
        lifetime.ensureOpen()
    }

    suspend fun offers(): List<PlayOffer> = withContext(Dispatchers.Main.immediate) {
        if (productIds.isEmpty()) throw PlayBillingUnavailable()
        connect()
        lifetime.ensureOpen()
        val details: List<ProductDetails> = awaitPlayOperation {
            suspendCancellableCoroutine { continuation ->
                val params = QueryProductDetailsParams.newBuilder().setProductList(productIds.map {
                    QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
                }).build()
                client.queryProductDetailsAsync(params) { result, response ->
                    if (!continuation.isActive) return@queryProductDetailsAsync
                    if (!lifetime.isOpen) continuation.resumeWithException(PlayBillingUnavailable())
                    else if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(response.productDetailsList)
                    else continuation.resumeWithException(PlayBillingUnavailable())
                }
            }
        }
        lifetime.ensureOpen()
        val nextOffers = mutableMapOf<String, CachedOffer>()
        val loaded = details.filter { it.productId in productIds }.flatMap { detail ->
            detail.subscriptionOfferDetails.orEmpty().map { offer ->
                val handle = UUID.randomUUID().toString()
                nextOffers[handle] = CachedOffer(detail, offer.offerToken, android.os.SystemClock.elapsedRealtime() + 300_000)
                PlayOffer(handle, detail.productId, detail.title, offer.basePlanId, offer.pricingPhases.pricingPhaseList.map {
                    PlayPricePhase(it.formattedPrice, it.billingPeriod, it.billingCycleCount, it.recurrenceMode)
                }, offer.installmentPlanDetails?.let {
                    PlayInstallmentCommitment(it.installmentPlanCommitmentPaymentsCount, it.subsequentInstallmentPlanCommitmentPaymentsCount)
                })
            }
        }
        offers.clear()
        offers.putAll(nextOffers)
        loaded
    }

    /** Call only from an explicit purchase action after displaying this offer's terms. */
    suspend fun launch(activity: Activity, offerHandle: String, accountBinding: () -> String? = { null }): PlayLaunchResult = try { withContext(Dispatchers.Main.immediate) {
        lifetime.ensureOpen()
        val offer = offers[offerHandle] ?: return@withContext PlayLaunchResult.STALE_OFFER
        if (offer.expires <= android.os.SystemClock.elapsedRealtime()) return@withContext PlayLaunchResult.STALE_OFFER
        connect()
        lifetime.ensureOpen()
        if (offer.expires <= android.os.SystemClock.elapsedRealtime()) return@withContext PlayLaunchResult.STALE_OFFER
        if (activity.isFinishing || activity.isDestroyed) return@withContext PlayLaunchResult.UNAVAILABLE
        // Optional association, sampled after suspension. A missing Lava account never blocks
        // a Play purchase (the canonical iOS appAccountToken is optional too).
        val binding = accountBinding()
        require(binding == null || binding.matches(Regex("[a-f0-9]{64}")))
        val params = BillingFlowParams.newBuilder()
            .apply { if (binding != null) setObfuscatedAccountId(binding) }
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(offer.detail).setOfferToken(offer.token).build())).build()
        val completion = CompletableDeferred<PlayLaunchResult>()
        if (!lifetime.registerLaunch(completion)) return@withContext PlayLaunchResult.UNAVAILABLE
        val immediate = try {
            lifetime.ensureOpen()
            val response = client.launchBillingFlow(activity, params).responseCode
            lifetime.ensureOpen()
            response
        } catch (failure: Exception) {
            lifetime.clearLaunch(completion)
            throw failure
        }
        if (immediate == BillingClient.BillingResponseCode.OK) {
            // Wait for cancellation/failure/pending as well as success. If the host disappears
            // or Play loses its callback, keep the in-flight marker until that callback arrives;
            // a second prompt must not overlap an unresolved store flow.
            withTimeoutOrNull(600_000) { completion.await() } ?: PlayLaunchResult.UNAVAILABLE
        } else {
            lifetime.clearLaunch(completion)
            when (immediate) {
                BillingClient.BillingResponseCode.USER_CANCELED -> PlayLaunchResult.CANCELLED
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> { lifetime.purchaseChanged(); PlayLaunchResult.ALREADY_OWNED }
                else -> PlayLaunchResult.UNAVAILABLE
            }
        }
    }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { PlayLaunchResult.UNAVAILABLE }

    override fun close() { lifetime.close() }
}
