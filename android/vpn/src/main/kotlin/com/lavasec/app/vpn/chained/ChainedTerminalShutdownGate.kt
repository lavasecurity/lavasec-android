package com.lavasec.app.vpn.chained

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Latches a terminal chained shutdown so a superseding reconnect cannot be torn down by the stale
 * outcome.
 *
 * A hard failure is claimed under the reconnect serialization, but its teardown must run OUTSIDE
 * that serialization (it enters the service command owner's monitor). In that gap a concurrent
 * `ACTION_RECONNECT` acquires the serialization, sees the now-empty chained slot, and installs a
 * replacement through `startInitialNetworkRuntime()` — which the stale terminal transition then
 * closes, retiring the whole service. Marking the shutdown while still holding the serialization
 * makes every reconnect/rebuild that follows a no-op, so the replacement is never built.
 *
 * Pure and JVM-testable; the runtime owns one instance per session.
 */
internal class ChainedTerminalShutdownGate {

    private val lock = ReentrantLock()
    private var pending = false

    /**
     * Latches the terminal shutdown while the reconnect serialization is held. Idempotent.
     */
    fun mark() = lock.withLock { pending = true }

    /**
     * Whether a terminal shutdown has been latched. Reconnect and network-recovery paths must no-op
     * while this is `true`, because the terminal transition owns the teardown.
     */
    fun isPending(): Boolean = lock.withLock { pending }
}
