package com.lavasec.app.foundation.activity

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDateRangePolicyTest {
    private val today = LocalDate.of(2026, 7, 19)

    @Test
    fun `today is the default inclusive Activity range`() {
        assertEquals(
            ActivityDateRange(start = today, endInclusive = today),
            ActivityDateRangePolicy.today(today),
        )
    }

    @Test
    fun `custom range normalizes endpoints and resolves local day boundaries`() {
        val range = ActivityDateRangePolicy.custom(
            start = LocalDate.of(2026, 7, 18),
            endInclusive = LocalDate.of(2026, 7, 16),
            today = today,
        )

        assertEquals(LocalDate.of(2026, 7, 16), range.start)
        assertEquals(LocalDate.of(2026, 7, 18), range.endInclusive)

        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(
            LocalDate.of(2026, 7, 16).atStartOfDay(tokyo).toInstant().toEpochMilli(),
            range.toEpochMillis(tokyo).startInclusiveMillis,
        )
        assertEquals(
            LocalDate.of(2026, 7, 19).atStartOfDay(tokyo).toInstant().toEpochMilli(),
            range.toEpochMillis(tokyo).endExclusiveMillis,
        )
    }

    @Test
    fun `picker exposes current month plus previous 23 months`() {
        val months = ActivityDateRangePolicy.selectableMonths(today)

        assertEquals(24, months.size)
        assertEquals("2024-08", months.first().toString())
        assertEquals("2026-07", months.last().toString())
        assertEquals(LocalDate.of(2024, 8, 1), ActivityDateRangePolicy.earliestSelectableDate(today))
    }

    @Test
    fun `custom range rejects dates outside picker window or in the future`() {
        assertFalse(ActivityDateRangePolicy.isSelectable(LocalDate.of(2024, 7, 31), today))
        assertTrue(ActivityDateRangePolicy.isSelectable(LocalDate.of(2024, 8, 1), today))
        assertTrue(ActivityDateRangePolicy.isSelectable(today, today))
        assertFalse(ActivityDateRangePolicy.isSelectable(today.plusDays(1), today))

        assertIllegalArgument {
            ActivityDateRangePolicy.custom(LocalDate.of(2024, 7, 31), today, today)
        }
        assertIllegalArgument {
            ActivityDateRangePolicy.custom(today, today.plusDays(1), today)
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
