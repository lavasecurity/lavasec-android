package com.lavasec.app.foundation.activity

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.filter.FilterDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityHistoryPolicyTest {
    private val nowMillis = 1_800_000_000_000L

    @Test
    fun `history is an independent rolling seven day window with an inclusive cutoff`() {
        val cutoff = nowMillis - ActivityHistoryPolicy.RETENTION_MILLIS
        val events = listOf(
            event(id = 1, timestampMillis = cutoff - 1),
            event(id = 2, timestampMillis = cutoff),
            event(id = 3, timestampMillis = nowMillis - 1),
        )

        assertEquals(
            listOf(3L, 2L),
            ActivityHistoryPolicy.visibleHistory(events, nowMillis).map { it.id },
        )
    }

    @Test
    fun `history is newest first and higher row id breaks timestamp ties`() {
        val tiedTimestamp = nowMillis - 1_000
        val events = listOf(
            event(id = 7, timestampMillis = tiedTimestamp),
            event(id = 9, timestampMillis = tiedTimestamp),
            event(id = 5, timestampMillis = tiedTimestamp + 1),
        )

        assertEquals(
            listOf(5L, 9L, 7L),
            ActivityHistoryPolicy.visibleHistory(events, nowMillis).map { it.id },
        )
    }

    @Test
    fun `retention cutoff saturates across a backwards wall clock jump`() {
        assertEquals(Long.MIN_VALUE, ActivityHistoryPolicy.cutoffMillis(Long.MIN_VALUE))
        assertEquals(
            listOf(1L),
            ActivityHistoryPolicy.visibleHistory(
                events = listOf(event(id = 1, timestampMillis = Long.MIN_VALUE)),
                nowMillis = Long.MIN_VALUE,
            ).map { it.id },
        )
    }

    @Test
    fun `history rejects implausibly future timestamps after a clock correction`() {
        val tolerated = nowMillis + ActivityHistoryPolicy.FUTURE_TIMESTAMP_TOLERANCE_MILLIS
        val future = tolerated + 1L

        assertEquals(
            listOf(1L),
            ActivityHistoryPolicy.visibleHistory(
                events = listOf(
                    event(id = 1, timestampMillis = tolerated),
                    event(id = 2, timestampMillis = future),
                ),
                nowMillis = nowMillis,
            ).map { it.id },
        )
        assertEquals(Long.MAX_VALUE, ActivityHistoryPolicy.upperBoundMillis(Long.MAX_VALUE))
    }

    private fun event(id: Long, timestampMillis: Long) = DnsDecisionEvent(
        id = id,
        timestampMillis = timestampMillis,
        normalizedDomain = "event-$id.example",
        decision = FilterDecision.DEFAULT_ALLOW,
    )
}
