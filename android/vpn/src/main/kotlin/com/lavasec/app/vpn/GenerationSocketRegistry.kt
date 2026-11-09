package com.lavasec.app.vpn

import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal fun interface ResolverResourceCloseScope {
    fun run(close: () -> Unit)
}

/**
 * Owns resolver resources for one complete engine epoch. Advancing is a visibility fence: no
 * successor resource can be admitted until every retired resource's close call has returned.
 */
class GenerationSocketRegistry private constructor(
    initialEpoch: EngineEpoch,
    private val resourceCloseScope: ResolverResourceCloseScope,
) : ResolverResourceRegistry, Closeable {
    private val lock = ReentrantLock()
    private val stable = lock.newCondition()
    private var current = initialEpoch
    private var closed = false
    private var advancing = false
    private var inFlightDrains = 0
    private val resources = Collections.newSetFromMap(IdentityHashMap<AutoCloseable, Boolean>())

    constructor(initialEpoch: EngineEpoch) : this(
        initialEpoch = initialEpoch,
        resourceCloseScope = ResolverResourceCloseScope { close -> close() },
    )

    override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean {
        val accepted = try {
            lock.withLock {
                if (closed || epoch != current) return@withLock false
                while (advancing && !closed && epoch == current) stable.await()
                if (closed || epoch != current) false else {
                    resources += resource
                    true
                }
            }
        } catch (failure: Throwable) {
            closeQuietly(resource)
            throw failure
        }
        if (!accepted) closeQuietly(resource)
        return accepted
    }

    override fun isCurrent(epoch: EngineEpoch): Boolean = lock.withLock {
        !closed && !advancing && epoch == current
    }

    override fun unregister(resource: AutoCloseable): Boolean = try {
        lock.withLock { resources.remove(resource) }
    } catch (_: Throwable) {
        false
    }

    override fun completeIfCurrent(
        resource: AutoCloseable,
        epoch: EngineEpoch,
        completion: () -> Boolean,
    ): Boolean {
        var drainAfterFailure = false
        val completed = lock.withLock {
            if (closed || advancing || epoch != current || !resources.contains(resource)) {
                return@withLock false
            }
            val succeeded = try {
                completion()
            } catch (_: Throwable) {
                false
            }
            if (resources.remove(resource) && !succeeded) {
                inFlightDrains += 1
                drainAfterFailure = true
            }
            succeeded
        }
        if (drainAfterFailure) {
            try {
                closeQuietly(resource)
            } finally {
                lock.withLock {
                    inFlightDrains -= 1
                    stable.signalAll()
                }
            }
        }
        return completed
    }

    fun advanceTo(nextEpoch: EngineEpoch) {
        val retired = lock.withLock {
            while (advancing && !closed) stable.awaitUninterruptibly()
            if (closed || !nextEpoch.isStrictSuccessorOf(current)) return
            advancing = true
            current = nextEpoch
            resources.toList().also { resources.clear() }
        }
        try {
            retired.forEach(::closeQuietly)
        } finally {
            lock.withLock {
                while (inFlightDrains > 0) stable.awaitUninterruptibly()
                advancing = false
                stable.signalAll()
            }
        }
    }

    override fun close() {
        val retired = lock.withLock {
            while (advancing && !closed) stable.awaitUninterruptibly()
            if (closed) return
            closed = true
            resources.toList().also { resources.clear() }
        }
        try {
            retired.forEach(::closeQuietly)
        } finally {
            lock.withLock {
                while (inFlightDrains > 0) stable.awaitUninterruptibly()
            }
        }
    }

    internal val sizeForTesting: Int
        get() = lock.withLock { resources.size }

    private fun closeQuietly(resource: AutoCloseable) {
        try {
            resourceCloseScope.run { resource.close() }
        } catch (_: Throwable) {
            // Continue draining; resource ownership is consumed even when its close reports failure.
        }
    }

    companion object {
        internal fun withCloseScope(
            initialEpoch: EngineEpoch,
            resourceCloseScope: ResolverResourceCloseScope,
        ): GenerationSocketRegistry = GenerationSocketRegistry(
            initialEpoch = initialEpoch,
            resourceCloseScope = resourceCloseScope,
        )
    }

    private fun EngineEpoch.isStrictSuccessorOf(other: EngineEpoch): Boolean =
        when {
            sessionGeneration != other.sessionGeneration ->
                sessionGeneration > other.sessionGeneration
            networkGeneration != other.networkGeneration ->
                networkGeneration > other.networkGeneration
            else -> engineGeneration > other.engineGeneration
        }
}
