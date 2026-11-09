package com.lavasec.app.security

/**
 * Pure attempt-token gate for a single in-flight biometric prompt.
 *
 * The platform prompt runs outside the coroutine and can deliver a terminal callback after the
 * caller has been cancelled, superseded or retired. This gate is the load-bearing guarantee that a
 * terminal callback can only ever settle the attempt that is still current: a late success after
 * [invalidate] (explicit cancel, coroutine cancellation, or a superseding attempt) is discarded, so
 * a never-presented/cancelled prompt can never yield a success.
 */
internal class BiometricAttemptGate {
    // `begin` can run on the caller's thread while a terminal callback settles on the main
    // executor, so every transition is synchronized: the token check and the state change are one
    // atomic step, and a supersede can never interleave with a settle.
    private var current: Long = NONE
    private var issued: Long = NONE

    /** Begin a new attempt, superseding any current one. Returns its token. */
    @Synchronized
    fun begin(): Long {
        issued += 1
        current = issued
        return current
    }

    /** True while [attempt] is the live attempt. */
    @Synchronized
    fun isCurrent(attempt: Long): Boolean = attempt == current

    /** The live attempt token, or [NONE] when no attempt is current. */
    @Synchronized
    fun currentAttempt(): Long = current

    /** Resolve the current attempt once. Any later settlement for [attempt] is ignored. */
    @Synchronized
    fun settle(attempt: Long): Boolean {
        if (attempt != current) return false
        current = NONE
        return true
    }

    /** Invalidate the current attempt without resolving it; a late callback can no longer settle. */
    @Synchronized
    fun invalidate(attempt: Long): Boolean {
        if (attempt != current) return false
        current = NONE
        return true
    }

    private companion object {
        const val NONE = 0L
    }
}
