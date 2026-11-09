package com.lavasec.app.work

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Enqueue owns a database operation, not a cancellable network request. Cancelling its await does
 * not cancel WorkManager's executor. Drain an operation already started before retiring its
 * application; then restore cancellation so no caller mistakes that retirement for installation.
 */
internal suspend fun installPlayReconciliationIfConfigured(
    configured: Boolean,
    enqueueAndAwait: suspend () -> Unit,
) {
    if (!configured) return
    currentCoroutineContext().ensureActive()
    try {
        withContext(NonCancellable) { enqueueAndAwait() }
    } finally {
        currentCoroutineContext().ensureActive()
    }
}
