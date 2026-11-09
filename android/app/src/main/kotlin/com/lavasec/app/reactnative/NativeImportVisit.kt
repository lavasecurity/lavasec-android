package com.lavasec.app.reactnative

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Exact renderer lifetime. Closing revokes pending acquisition and final publication. */
internal class NativeImportVisit(val host: NativeVisitHost, val generation: Long) {
    val id: String = UUID.randomUUID().toString()
    private val active = AtomicBoolean(true)
    fun isCurrent(host: NativeVisitHost?, generation: Long?): Boolean =
        active.get() && this.generation == generation && this.host.matches(host)
    private val lock = Any()
    private var acquisition: Job? = null

    /** A lost ActivityResult callback must not hold the mutation lane indefinitely. */
    suspend fun <T> acquire(operation: suspend () -> T): T = coroutineScope {
        val worker = async(start = CoroutineStart.LAZY) { operation() }
        val admitted = synchronized(lock) {
            if (!active.get() || acquisition != null) false
            else { acquisition = worker; true }
        }
        if (!admitted) worker.cancel()
        try { worker.await() }
        finally { synchronized(lock) { if (acquisition === worker) acquisition = null } }
    }
    fun retire() {
        val worker = synchronized(lock) {
            active.set(false)
            acquisition.also { acquisition = null }
        }
        worker?.cancel()
    }
    fun retireHost(owner: Any, nativeHostID: String): Boolean {
        if (host.owner !== owner || host.nativeHostID != nativeHostID) return false
        retire()
        return true
    }
}
