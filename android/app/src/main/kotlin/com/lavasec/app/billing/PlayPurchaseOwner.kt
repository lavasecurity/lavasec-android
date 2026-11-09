package com.lavasec.app.billing

import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence
import com.lavasec.app.foundation.subscription.LavaSecurityPlusPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Native-only purchase data: tokens must never appear in snapshots, logs, or RN. */
internal class PlayPurchase(
    val token: String,
    val products: Set<String>,
    val purchased: Boolean,
    val acknowledged: Boolean,
) {
    override fun toString() = "PlayPurchase(redacted)"
}

internal interface PlayPurchasePort {
    suspend fun purchases(): List<PlayPurchase>
    suspend fun acknowledge(token: String)
}

/** Returned only by the trusted server-verification adapter, not by the Billing callback. */
internal data class VerifiedPlayPurchase(
    val productId: String,
    val active: Boolean,
    val expiresAt: Instant?,
    val acknowledgementRequired: Boolean,
)

internal enum class PlayPurchaseReadStatus { NOT_CHECKED, CHECKING, CHECKED, PENDING, UNAVAILABLE }
internal data class PlayPurchaseSnapshot(
    val status: PlayPurchaseReadStatus = PlayPurchaseReadStatus.NOT_CHECKED,
    val acknowledgementPending: Boolean = false,
)

/**
 * StoreKit's verified entitlement refresh/restore/update flow with Play-specific verification and
 * acknowledgement. Device Plus is independent of optional account association. Query failures,
 * pending transactions, and verification failures are uncertainty, never confirmed revocation.
 * The production verifier must support app-authenticated signed-out purchases as well as sign-in.
 */
internal class PlayPurchaseOwner(
    private val port: PlayPurchasePort,
    private val plan: NativePlusEntitlementOwner,
    private val cache: PlayReceiptCache,
    private val verify: suspend (PlayPurchase) -> VerifiedPlayPurchase,
) {
    private val operations = Mutex()
    private val mutableState = MutableStateFlow(PlayPurchaseSnapshot())
    val state = mutableState.asStateFlow()

    /**
     * An SDK inventory miss is not a confirmed lapse. Reverify the previously granted receipts
     * as well, because Play omits expired subscriptions from its current-purchases query.
     * Only a complete fresh negative over that known set may revoke persisted Plus.
     */
    suspend fun refresh(): Boolean = operations.withLock {
        val previous = mutableState.value
        mutableState.value = previous.copy(status = PlayPurchaseReadStatus.CHECKING)
        try {
            if (!plan.initialize()) return@withLock unavailable()
            val remembered = cache.load()
            val queried = port.purchases()
            require(queried.size <= PlayReceiptCodec.MAX_RECEIPTS)
            val receipts = (remembered + queried).associateBy { it.token }.values
            require(receipts.size <= PlayReceiptCodec.MAX_RECEIPTS)
            val verified = mutableListOf<Pair<PlayPurchase, VerifiedPlayPurchase>>()
            var uncertain = false
            var pending = false
            for (receipt in receipts) {
                if (receipt.products.none { it in LavaSecurityPlusPolicy.paywallProductIDs }) continue
                if (!receipt.purchased) { pending = true; continue }
                try {
                    val verdict = verify(receipt)
                    require(verdict.productId in receipt.products &&
                        verdict.productId in LavaSecurityPlusPolicy.paywallProductIDs)
                    require(!verdict.active || verdict.expiresAt != null)
                    verified += receipt to verdict
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    uncertain = true
                }
            }
            val active = verified.filter { it.second.active }
            val best = active.maxByOrNull { requireNotNull(it.second.expiresAt) }?.second
            val nextReceipts = remembered.associateBy { it.token }.toMutableMap()
            for ((receipt, verdict) in verified) {
                if (verdict.active) nextReceipts[receipt.token] = PlayPurchase(
                    receipt.token, setOf(verdict.productId), true, receipt.acknowledged || !verdict.acknowledgementRequired,
                ) else nextReceipts.remove(receipt.token)
            }
            var unknownPreviousGrant = false
            if (best != null) {
                // Retain native verification material before granting/acknowledging. A crash
                // between the two commits leaves a re-verifiable receipt, not an orphan grant.
                val retained = nextReceipts.values.toList()
                if (!sameReceipts(remembered, retained)) cache.save(retained)
                if (!plan.apply(VerifiedPlusReading(true, best.expiresAt, EntitlementReadingConfidence.CONFIRMED))) {
                    return@withLock unavailable()
                }
            } else if (!uncertain && !pending) {
                val everyPriorGrantIsInactive = remembered.isNotEmpty() && remembered.all { prior ->
                    verified.any { (receipt, verdict) -> receipt.token == prior.token && !verdict.active }
                }
                val confidence = if (everyPriorGrantIsInactive) EntitlementReadingConfidence.CONFIRMED
                    else EntitlementReadingConfidence.UNCONFIRMED
                if (!plan.apply(VerifiedPlusReading(false, confidence = confidence))) return@withLock unavailable()
                unknownPreviousGrant = plan.state.value.hasPlus
                // Forget only after the durable negative. A failed/uncertain plan publication
                // must leave enough verification material for the next process to recover.
                if (!unknownPreviousGrant && remembered.isNotEmpty()) cache.save(emptyList())
            }
            var acknowledgementPending = false
            for ((receipt, verdict) in active) {
                if (verdict.acknowledgementRequired && !receipt.acknowledged) {
                    try {
                        // Google requires granting the verified entitlement before acknowledging.
                        // An acknowledgement failure retries on the next read without revoking Plus.
                        port.acknowledge(receipt.token)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        acknowledgementPending = true
                    }
                }
            }
            mutableState.value = PlayPurchaseSnapshot(
                status = when {
                    uncertain || unknownPreviousGrant -> PlayPurchaseReadStatus.UNAVAILABLE
                    pending -> PlayPurchaseReadStatus.PENDING
                    else -> PlayPurchaseReadStatus.CHECKED
                },
                acknowledgementPending = acknowledgementPending,
            )
            !uncertain && !unknownPreviousGrant
        } catch (cancelled: CancellationException) {
            mutableState.value = previous
            throw cancelled
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun sameReceipts(previous: List<PlayPurchase>, next: List<PlayPurchase>): Boolean =
        previous.size == next.size && previous.associateBy { it.token }.let { old ->
            next.all { receipt -> old[receipt.token]?.let {
                it.products == receipt.products && it.purchased == receipt.purchased &&
                    it.acknowledged == receipt.acknowledged
            } == true }
        }

    private fun unavailable(): Boolean {
        mutableState.value = mutableState.value.copy(status = PlayPurchaseReadStatus.UNAVAILABLE)
        return false
    }
}
