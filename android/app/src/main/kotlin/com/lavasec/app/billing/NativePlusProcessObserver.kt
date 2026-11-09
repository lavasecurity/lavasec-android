package com.lavasec.app.billing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Foreground and SDK observation belongs to one application lifetime, including its pending IO. */
internal class NativePlusProcessObserver(
    private val scope: CoroutineScope,
    appStarted: Flow<Boolean>,
    private val install: suspend () -> Unit,
    private val loadProducts: suspend () -> Unit,
    private val refreshPurchases: suspend () -> Unit,
) : AutoCloseable {
    private val installation = Mutex()

    private suspend fun installReconciliation() {
        if (!installation.tryLock()) return
        try {
            repeat(2) { attempt ->
                try {
                    install()
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (attempt == 0) delay(30_000)
                    // Retry on the next foreground/process start; purchases additionally require
                    // a successful durable installation before opening Play's prompt.
                }
            }
        } finally { installation.unlock() }
    }

    override fun close() { scope.cancel() }

    suspend fun closeAndJoin() { scope.coroutineContext[Job]?.cancelAndJoin() }

    init {
        // Construct only after unlock. Foreground reads and SDK updates are native-owned; an
        // Activity or RN reload must not create another billing connection or plan authority.
        scope.launch { installReconciliation() }
        scope.launch {
            appStarted.distinctUntilChanged().collect { foreground ->
                if (foreground) {
                    scope.launch { installReconciliation() }
                    scope.launch { loadProducts() }
                    refreshPurchases()
                }
            }
        }
    }
}
