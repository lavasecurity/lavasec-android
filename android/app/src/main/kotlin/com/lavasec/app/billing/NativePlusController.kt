package com.lavasec.app.billing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Producer-owned messages, matching the canonical controller's purchase/restore states. */
internal enum class PlusMessage {
    PURCHASE_CANCELLED, PURCHASE_PENDING, PURCHASE_UNAVAILABLE, OFFER_EXPIRED,
    VERIFICATION_UNAVAILABLE, RESTORED, NO_ACTIVE_PURCHASE, PRODUCTS_UNAVAILABLE,
}

internal data class NativePlusPresentation(
    val offers: List<PlayOffer> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: PlusMessage? = null,
)

/** Port of LavaSecurityPlusController. Store verification and durable plan state stay in their owners. */
internal class NativePlusController(
    private val plan: NativePlusEntitlementOwner,
    private val purchases: PlayPurchaseOwner,
    private val loadOffers: suspend () -> List<PlayOffer>,
    private val ensureReconciliation: suspend () -> Unit,
) {
    private val operations = Mutex()
    private val products = Mutex()
    private val mutableState = MutableStateFlow(NativePlusPresentation())
    val state = mutableState.asStateFlow()

    suspend fun loadProducts() {
        if (!products.tryLock()) return
        mutableState.update { it.copy(loading = true) }
        try {
            val offers = loadOffers()
            mutableState.update { it.copy(offers = offers,
                message = when {
                    it.message != null && it.message != PlusMessage.PRODUCTS_UNAVAILABLE -> it.message
                    offers.isEmpty() -> PlusMessage.PRODUCTS_UNAVAILABLE
                    else -> null
                }) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed refresh must not leave stale offer handles purchasable.
            mutableState.update { it.copy(offers = emptyList(), message = it.message ?: PlusMessage.PRODUCTS_UNAVAILABLE) }
        } finally {
            mutableState.update { it.copy(loading = false) }
            products.unlock()
        }
    }

    suspend fun refresh() { purchases.refresh() }

    suspend fun buy(handle: String, launch: suspend () -> PlayLaunchResult): Boolean {
        if (!operations.tryLock()) return false
        mutableState.update { it.copy(busy = true, message = null) }
        try {
            if (state.value.offers.none { it.handle == handle }) {
                mutableState.update { it.copy(message = PlusMessage.OFFER_EXPIRED) }
                return false
            }
            // Enqueue must be durable before a store prompt can create an unacknowledged purchase.
            ensureReconciliation()
            when (launch()) {
                PlayLaunchResult.PURCHASED, PlayLaunchResult.ALREADY_OWNED -> {
                    val verified = purchases.refresh()
                    mutableState.update { it.copy(message = when {
                        !verified -> PlusMessage.VERIFICATION_UNAVAILABLE
                        purchases.state.value.status == PlayPurchaseReadStatus.PENDING -> PlusMessage.PURCHASE_PENDING
                        plan.state.value.hasPlus -> null
                        else -> PlusMessage.NO_ACTIVE_PURCHASE
                    }) }
                }
                PlayLaunchResult.PENDING -> mutableState.update { it.copy(message = PlusMessage.PURCHASE_PENDING) }
                PlayLaunchResult.CANCELLED -> mutableState.update { it.copy(message = PlusMessage.PURCHASE_CANCELLED) }
                PlayLaunchResult.STALE_OFFER -> mutableState.update { it.copy(message = PlusMessage.OFFER_EXPIRED) }
                PlayLaunchResult.UNAVAILABLE -> mutableState.update { it.copy(message = PlusMessage.PURCHASE_UNAVAILABLE) }
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(message = PlusMessage.PURCHASE_UNAVAILABLE) }
            return false
        } finally {
            mutableState.update { it.copy(busy = false) }
            operations.unlock()
        }
    }

    suspend fun restore(): Boolean {
        if (!operations.tryLock()) return false
        mutableState.update { it.copy(busy = true, message = null) }
        try {
            ensureReconciliation()
            val verified = purchases.refresh()
            mutableState.update { it.copy(message = when {
                !verified -> PlusMessage.VERIFICATION_UNAVAILABLE
                purchases.state.value.status == PlayPurchaseReadStatus.PENDING -> PlusMessage.PURCHASE_PENDING
                plan.state.value.hasPlus -> PlusMessage.RESTORED
                else -> PlusMessage.NO_ACTIVE_PURCHASE
            }) }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(message = PlusMessage.VERIFICATION_UNAVAILABLE) }
            return false
        } finally {
            mutableState.update { it.copy(busy = false) }
            operations.unlock()
        }
    }

    fun clearMessage() { mutableState.update { it.copy(message = null) } }
}
