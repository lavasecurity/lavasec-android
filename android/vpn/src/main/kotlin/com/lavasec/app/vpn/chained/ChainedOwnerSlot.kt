package com.lavasec.app.vpn.chained

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one installed chained owner, with an identity check for terminal callbacks.
 *
 * A live owner's terminal failure is reported on an engine/reader thread and handled
 * asynchronously. By the time that handler runs, a `reconnect()` may have already closed the owner
 * and installed a replacement, so the handler must act only when the owner that REPORTED the
 * failure is still the installed one — otherwise it would close the fresh session and tear down a
 * healthy replacement. A plain shared reference cannot express "is this still the owner I was
 * reporting for"; this slot makes that comparison explicit and holds it under one lock.
 *
 * Generic over the owner type so the decision is executable on the JVM harness without the
 * engine's Android-gated owner.
 */
internal class ChainedOwnerSlot<T : Any> {

    private val lock = ReentrantLock()
    private var current: T? = null

    /** Installs [owner] as the single current owner. */
    fun install(owner: T) {
        lock.withLock { current = owner }
    }

    /** Clears the slot without touching the owner (the caller closes it). */
    fun clear() {
        lock.withLock { current = null }
    }

    /** The installed owner, or `null`. */
    fun currentOrNull(): T? = lock.withLock { current }

    /**
     * Whether [candidate] is the installed owner, WITHOUT clearing the slot.
     *
     * Used by a state collector to fence a late emission from a retired owner: the emission must be
     * ignored, not published (or published as `false`), because a replacement or a DNS-only
     * fallback may already own the current value.
     */
    fun isCurrent(candidate: T): Boolean = lock.withLock { current === candidate }

    /** Removes and returns the installed owner, or `null`. */
    fun take(): T? = lock.withLock { current.also { current = null } }

    /**
     * Whether [candidate] is still the installed owner, clearing the slot when it is.
     *
     * @return `true` only for the reporting owner that is still installed; `false` for a stale
     *   callback from an owner a reconnect already replaced (the slot is left untouched).
     */
    fun takeIfCurrent(candidate: T): Boolean = lock.withLock {
        if (current !== candidate) {
            false
        } else {
            current = null
            true
        }
    }
}
