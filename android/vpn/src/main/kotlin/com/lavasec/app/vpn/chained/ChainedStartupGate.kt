package com.lavasec.app.vpn.chained

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Coordinates the chained startup commit with a terminal outcome that races it.
 *
 * The runtime can report a terminal outcome (a hard failure, or a fall-back-to-DNS-only surrender)
 * on an engine/reader thread while the service thread is still committing the startup. Both paths
 * must agree, under one lock, whether that outcome belongs to the STARTUP (which clears the owner
 * synchronously and falls back to DNS-only) or to the LIVE runtime (which is torn down or rebuilt
 * asynchronously). Splitting the decision across two volatile flags left a window where an outcome
 * scheduled for the live runtime could outlive the startup return and tear down the DNS-only
 * fallback that had replaced it.
 *
 * The gate is bound to one candidate owner per attempt ([begin]). A terminating owner that was
 * already superseded — its failure was flagged before a reconnect closed it, but its callback ran
 * after `retire()` and the next `begin` — is not the candidate, so its outcome is discarded. Without
 * that binding the stale outcome was stored in the shared gate and the replacement's [commit] read
 * it as its own failure, falling back to DNS-only despite a healthy startup.
 *
 * The generic types keep this pure and JVM-testable without the engine's owner or error enum.
 */
internal class ChainedStartupGate<O : Any, F : Any> {

    private val lock = ReentrantLock()
    private var candidate: O? = null
    private var live = false
    private var termination: F? = null

    /**
     * Opens the startup window for [owner], discarding any previous candidate or outcome.
     *
     * No callback can fire for the owner before it exists and is installed, so binding the
     * candidate here (after establishment, before the start/commit) covers the whole startup race.
     */
    fun begin(owner: O) {
        lock.withLock {
            candidate = owner
            live = false
            termination = null
        }
    }

    /**
     * Records a terminal outcome (a failure or a fall-back-to-DNS-only surrender) reported by
     * [owner].
     *
     * An outcome from an owner that is not the current candidate (a superseded owner whose callback
     * resumed after a reconnect began a replacement) is discarded and reported as not live, so the
     * caller does not route it to the asynchronous live handler.
     *
     * @return `true` when the gate is already live for [owner], so the caller must route the outcome
     *   to the asynchronous live-runtime handler; `false` when the startup still owns it (the commit
     *   will return it) or when [owner] is not the candidate.
     */
    fun recordTermination(owner: O, termination: F): Boolean = lock.withLock {
        if (candidate !== owner) {
            false
        } else {
            this.termination = termination
            live
        }
    }

    /**
     * Commits [owner]'s startup.
     *
     * @return the termination that raced the commit, or `null` when the runtime is now live or
     *   [owner] is not the candidate. When a termination is returned the gate is NOT live and the
     *   caller must close the owner synchronously and fall back.
     */
    fun commit(owner: O): F? = lock.withLock {
        if (candidate !== owner) {
            null
        } else {
            val raced = termination
            live = raced == null
            raced
        }
    }

    /**
     * Transitions back to not-live, clears the candidate, and clears the recorded termination.
     *
     * Clearing the termination is essential: a live owner can record one just before a reconnect
     * retires it, and if the old outcome survived, the replacement's [commit] would read it and
     * treat a healthy startup as a failure. Clearing the candidate is equally essential: after a
     * retire, an outcome from any prior owner must be discarded rather than stored for the next
     * attempt. The store is therefore reset at every retire/new-attempt boundary.
     */
    fun retire() {
        lock.withLock {
            candidate = null
            live = false
            termination = null
        }
    }

    /** Whether the committed runtime is live. Diagnostic/test use. */
    fun isLive(): Boolean = lock.withLock { live }
}
