package com.lavasec.app.foundation.health

/** Escalating per-episode primary-resolver re-probe cadence, capped at steady state. */
class ResolverWedgeRecoveryCadence(
    firstDelayMillis: Long = 2_000L,
    maxDelayMillis: Long = 30_000L,
) {
    val firstDelayMillis: Long
    val maxDelayMillis: Long

    init {
        val safeMaximum = maxOf(maxDelayMillis, 0L)
        this.maxDelayMillis = safeMaximum
        this.firstDelayMillis = minOf(maxOf(firstDelayMillis, 0L), safeMaximum)
    }

    fun delayMillisForAttempt(attempt: Int): Long {
        if (attempt <= 0 || firstDelayMillis == maxDelayMillis) return firstDelayMillis
        var delay = firstDelayMillis
        repeat(minOf(attempt, 63)) {
            if (delay >= maxDelayMillis || delay > maxDelayMillis / 2L) return maxDelayMillis
            delay *= 2L
        }
        return minOf(delay, maxDelayMillis)
    }
}
