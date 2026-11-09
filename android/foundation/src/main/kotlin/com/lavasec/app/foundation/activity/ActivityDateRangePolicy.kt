package com.lavasec.app.foundation.activity

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Inclusive local-calendar range used by the Activity digest and Top Domains lens. */
data class ActivityDateRange(
    val start: LocalDate,
    val endInclusive: LocalDate,
) {
    init {
        require(!endInclusive.isBefore(start)) { "endInclusive must not precede start" }
    }

    fun toEpochMillis(zoneId: ZoneId): ActivityEpochRange = ActivityEpochRange(
        startInclusiveMillis = start.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endExclusiveMillis = endInclusive.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
    )
}

data class ActivityEpochRange(
    val startInclusiveMillis: Long,
    val endExclusiveMillis: Long,
)

/** Matches the iOS picker: the current month plus the preceding 23 calendar months. */
object ActivityDateRangePolicy {
    const val SELECTABLE_MONTH_COUNT = 24

    fun today(today: LocalDate): ActivityDateRange = ActivityDateRange(today, today)

    fun custom(
        start: LocalDate,
        endInclusive: LocalDate,
        today: LocalDate,
    ): ActivityDateRange {
        require(isSelectable(start, today)) { "start is outside the selectable Activity window" }
        require(isSelectable(endInclusive, today)) { "end is outside the selectable Activity window" }
        return if (start <= endInclusive) {
            ActivityDateRange(start, endInclusive)
        } else {
            ActivityDateRange(endInclusive, start)
        }
    }

    fun earliestSelectableDate(today: LocalDate): LocalDate =
        YearMonth.from(today).minusMonths((SELECTABLE_MONTH_COUNT - 1).toLong()).atDay(1)

    fun isSelectable(date: LocalDate, today: LocalDate): Boolean =
        date >= earliestSelectableDate(today) && date <= today

    fun selectableMonths(today: LocalDate): List<YearMonth> {
        val current = YearMonth.from(today)
        return (SELECTABLE_MONTH_COUNT - 1 downTo 0).map { offset ->
            current.minusMonths(offset.toLong())
        }
    }
}
