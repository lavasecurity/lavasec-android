package com.lavasec.app.billing

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class PlayBillingUnavailable : Exception("Google Play billing is unavailable")
/** A Play RPC timeout is retriable unavailability; parent cancellation must still propagate. */
internal suspend fun <T> awaitPlayOperation(timeoutMillis: Long = 15_000, block: suspend () -> T): T =
    try { withTimeout(timeoutMillis) { block() } }
    catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw PlayBillingUnavailable()
    }

internal enum class PlayLaunchResult { PURCHASED, PENDING, CANCELLED, UNAVAILABLE, STALE_OFFER, ALREADY_OWNED }
internal data class PlayPricePhase(val formattedPrice: String, val billingPeriod: String, val cycles: Int, val recurrenceMode: Int)
internal data class PlayInstallmentCommitment(val initialPayments: Int, val renewalPayments: Int)
internal data class PlayOffer(val handle: String, val productId: String, val title: String, val basePlanId: String,
    val phases: List<PlayPricePhase>, val installment: PlayInstallmentCommitment?)

/** Process retirement carries no proof about an unresolved purchase's store outcome. */
internal class NativePlayBillingLifetime(
    private val onChanged: () -> Unit,
    private val endConnection: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val pendingLaunch = AtomicReference<CompletableDeferred<PlayLaunchResult>?>(null)
    val isOpen: Boolean get() = !closed.get()

    fun ensureOpen() { if (!isOpen) throw PlayBillingUnavailable() }

    fun registerLaunch(completion: CompletableDeferred<PlayLaunchResult>): Boolean {
        if (!isOpen || !pendingLaunch.compareAndSet(null, completion)) return false
        if (!isOpen) {
            if (pendingLaunch.compareAndSet(completion, null)) completion.complete(PlayLaunchResult.UNAVAILABLE)
            return false
        }
        return true
    }

    fun clearLaunch(completion: CompletableDeferred<PlayLaunchResult>) {
        pendingLaunch.compareAndSet(completion, null)
    }

    fun purchaseChanged() { if (isOpen) onChanged() }

    fun completeLaunch(result: PlayLaunchResult) {
        if (isOpen) pendingLaunch.getAndSet(null)?.complete(result)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingLaunch.getAndSet(null)?.complete(PlayLaunchResult.UNAVAILABLE)
        endConnection()
    }
}
