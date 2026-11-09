package com.lavasec.app.reactnative

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local-day semantics for the canonical `activity.query`/`domains.query` range. The range crosses
 * the wire as Unix seconds; the owner must rebuild the exact local calendar days in the app zone,
 * refuse a range outside the selectable window, and never substitute a device-timezone day.
 */
class ProductQueryOwnerTest {
    private val zone = ZoneId.of("America/New_York")

    private fun secondsFor(date: LocalDate, endOfDay: Boolean = false): Long {
        val instant = if (endOfDay) {
            date.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)
        } else {
            date.atStartOfDay(zone).toInstant()
        }
        return instant.epochSecond
    }

    @Test fun `canonical seconds rebuild the exact local days in the owner zone`() {
        val today = LocalDate.now(zone)
        val start = today.minusDays(13)
        val lens = activityDateLens(zone, secondsFor(start), secondsFor(today, endOfDay = true))
        assertEquals(start.atStartOfDay(zone).toInstant().toEpochMilli(), lens!!.startDayMillis)
        assertEquals(today.atStartOfDay(zone).toInstant().toEpochMilli(), lens.endDayMillis)
        assertEquals("America/New_York", lens.zoneId)
        assertEquals(today, lens.today)
    }

    @Test fun `reversed bounds normalize to the same days`() {
        val today = LocalDate.now(zone)
        val earlier = today.minusDays(3)
        val forward = activityDateLens(zone, secondsFor(earlier), secondsFor(today, endOfDay = true))!!
        val reversed = activityDateLens(zone, secondsFor(today, endOfDay = true), secondsFor(earlier))!!
        assertEquals(forward.startDayMillis, reversed.startDayMillis)
        assertEquals(forward.endDayMillis, reversed.endDayMillis)
    }

    @Test fun `a range outside the selectable window is refused, not clamped`() {
        val today = LocalDate.now(zone)
        val ancient = today.minusMonths(25)
        assertNull(activityDateLens(zone, secondsFor(ancient), secondsFor(today)))
        assertNull(activityDateLens(zone, null, null))
    }
}
