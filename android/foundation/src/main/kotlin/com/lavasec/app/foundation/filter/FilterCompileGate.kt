package com.lavasec.app.foundation.filter

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Linearizable publication capability bound to one compile epoch. */
fun interface FilterCommitFence {
    fun commitIfCurrent(commit: () -> Unit): Boolean

    companion object {
        val ALWAYS = FilterCommitFence { commit ->
            commit()
            true
        }
    }
}

/** Serializes compile memory peaks, coalesces equal inputs, and epoch-fences publication. */
class FilterCompileGate(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val admission = Mutex()
    private val epochMonitor = Any()
    private var epoch = 0L
    private var active: Active? = null

    suspend fun <T> submit(
        key: String,
        compile: suspend () -> T,
        publish: suspend (T, fence: FilterCommitFence) -> Unit,
    ): T {
        val selected = admission.withLock {
            val existing = synchronized(epochMonitor) { active }
            if (existing != null && existing.key == key && !existing.task.isCancelled) {
                existing
            } else {
                if (existing != null) {
                    invalidateAndCancel(existing, "Superseded by a newer compile")
                    existing.task.cancelAndJoin()
                }
                createActive(key, compile, publish)
            }
        }

        @Suppress("UNCHECKED_CAST")
        return try {
            selected.awaitLinkedToCaller() as T
        } catch (error: CancellationException) {
            withContext(NonCancellable) { selected.task.cancelAndJoin() }
            throw error
        } finally {
            withContext(NonCancellable) {
                admission.withLock {
                    synchronized(epochMonitor) {
                        if (active?.task === selected.task && selected.task.isCompleted) active = null
                    }
                }
            }
        }
    }

    private fun <T> createActive(
        key: String,
        compile: suspend () -> T,
        publish: suspend (T, fence: FilterCommitFence) -> Unit,
    ): Active {
        val ownEpoch = synchronized(epochMonitor) { ++epoch }
        val task = scope.async<Any?>(start = CoroutineStart.LAZY) {
            val taskContext = coroutineContext
            val value = compile()
            ensureActive()
            synchronized(epochMonitor) {
                if (epoch != ownEpoch) throw CancellationException("Stale compile epoch")
            }
            val fence = FilterCommitFence { commit ->
                synchronized(epochMonitor) {
                    if (epoch != ownEpoch || !taskContext.isActive) {
                        false
                    } else {
                        commit()
                        true
                    }
                }
            }
            publish(value, fence)
            ensureActive()
            synchronized(epochMonitor) {
                if (epoch != ownEpoch) throw CancellationException("Publication epoch changed")
            }
            value
        }
        return Active(key, ownEpoch, task).also { created ->
            synchronized(epochMonitor) { active = created }
            task.start()
        }
    }

    private fun invalidateAndCancel(selected: Active, reason: String) {
        synchronized(epochMonitor) {
            if (epoch == selected.epoch) epoch++
            selected.task.cancel(CancellationException(reason))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Active.awaitLinkedToCaller(): Any? = suspendCancellableCoroutine { continuation ->
        val completion = task.invokeOnCompletion { error ->
            if (error == null) {
                continuation.resumeWith(Result.success(task.getCompleted()))
            } else {
                continuation.resumeWith(Result.failure(error))
            }
        }
        continuation.invokeOnCancellation {
            completion.dispose()
            invalidateAndCancel(this, "Compile caller cancelled")
        }
    }

    override fun close() {
        synchronized(epochMonitor) {
            epoch++
            active?.task?.cancel(CancellationException("Compile gate closed"))
        }
        scope.cancel()
    }

    private data class Active(val key: String, val epoch: Long, val task: Deferred<Any?>)
}
