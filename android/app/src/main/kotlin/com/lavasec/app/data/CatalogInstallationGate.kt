package com.lavasec.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A fresh credential graph must not retain candidate catalog bytes that an in-flight install
 * can still roll back. Cover its complete catalog/configuration construction and the install's
 * durable write, publication and rollback with one process boundary. Waiters suspend instead
 * of blocking the main thread; cancellation releases admission only after rollback completes.
 */
internal object CatalogInstallationGate {
    private val mutex = Mutex()

    suspend fun <T> withStableCatalog(action: suspend () -> T): T = mutex.withLock { action() }
}
