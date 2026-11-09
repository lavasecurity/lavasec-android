package com.lavasec.app.foundation.activity

import com.lavasec.app.foundation.dns.DnsDecisionEvent

/** Rolling detailed-history policy, independent of the digest/Top Domains date lens. */
object ActivityHistoryPolicy {
    const val RETENTION_DAYS = 7L
    const val RETENTION_MILLIS = RETENTION_DAYS * 24L * 60L * 60L * 1_000L
    const val FUTURE_TIMESTAMP_TOLERANCE_MILLIS = 5L * 60L * 1_000L

    fun visibleHistory(
        events: Iterable<DnsDecisionEvent>,
        nowMillis: Long,
    ): List<DnsDecisionEvent> {
        val cutoffMillis = cutoffMillis(nowMillis)
        val upperBoundMillis = upperBoundMillis(nowMillis)
        return events
            .filter { it.timestampMillis in cutoffMillis..upperBoundMillis }
            .sortedWith(
                compareByDescending<DnsDecisionEvent> { it.timestampMillis }
                    .thenByDescending { it.id },
            )
    }

    /** Saturates rather than wrapping when a wall clock moves near the epoch boundary. */
    fun cutoffMillis(nowMillis: Long): Long =
        if (nowMillis < Long.MIN_VALUE + RETENTION_MILLIS) Long.MIN_VALUE else nowMillis - RETENTION_MILLIS

    fun upperBoundMillis(nowMillis: Long): Long =
        if (nowMillis > Long.MAX_VALUE - FUTURE_TIMESTAMP_TOLERANCE_MILLIS) {
            Long.MAX_VALUE
        } else {
            nowMillis + FUTURE_TIMESTAMP_TOLERANCE_MILLIS
        }

    fun isRetainable(timestampMillis: Long, nowMillis: Long): Boolean =
        timestampMillis in cutoffMillis(nowMillis)..upperBoundMillis(nowMillis)
}
