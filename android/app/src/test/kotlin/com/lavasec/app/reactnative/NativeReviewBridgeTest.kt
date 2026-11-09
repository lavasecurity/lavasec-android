package com.lavasec.app.reactnative

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeReviewBridgeTest {
    @Test fun `legal bridge exposes packaged offline notices with searchable content`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val content = JSONObject(reviewLegalNotices(context))
        assertTrue(content.getString("disclaimer").isNotBlank())
        val sections = content.getJSONArray("sections")
        assertEquals(3, sections.length())
        val rows = (0 until sections.length()).flatMap { section ->
            val notices = sections.getJSONObject(section).getJSONArray("notices")
            (0 until notices.length()).map { notices.getJSONObject(it) }
        }
        assertEquals(rows.size, rows.map { it.getString("id") }.toSet().size)
        assertTrue(rows.all { it.getString("displayName").isNotBlank() && it.getString("noticeText").isNotBlank() })
        assertTrue(rows.any { it.getString("displayName") == "StevenBlack Unified Hosts" && it.getString("licenseTextURL").startsWith("https://") })
    }

    @Test fun `inclusive custom dates span daylight saving transitions without adding a day`() {
        val zone = ZoneId.of("America/New_York")
        for ((day, hours) in listOf(LocalDate.of(2026, 3, 8) to 23, LocalDate.of(2026, 11, 1) to 25)) {
            val result = activityDates(day, day, day, zone)
            assertEquals(hours * 3600L, result.end - result.start + 1)
            assertEquals(day, java.time.Instant.ofEpochSecond(result.end).atZone(zone).toLocalDate())
            assertTrue(result.includesToday)
        }
    }

    @Test fun `historical dates do not report today and reversed dates are refused`() {
        val today = LocalDate.of(2026, 10, 3)
        assertFalse(activityDates(today.minusDays(3), today.minusDays(1), today, ZoneId.of("Asia/Tokyo")).includesToday)
        assertThrows(IllegalArgumentException::class.java) { activityDates(today, today.minusDays(1), today, ZoneId.of("UTC")) }
    }
}
