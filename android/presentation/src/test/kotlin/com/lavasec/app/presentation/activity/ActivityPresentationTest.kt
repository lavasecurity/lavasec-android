package com.lavasec.app.presentation.activity

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.lavasec.app.designsystem.LavaTheme
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class ActivityPresentationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `count percentages use one denominator and zero total is stable`() {
        assertEquals(ActivityPercentageUi.Zero, ActivityCountSnapshot(0L, 0L).allowedShare)
        assertEquals(ActivityPercentageUi.Zero, ActivityCountSnapshot(0L, 0L).blockedShare)
        assertEquals(ActivityPercentageUi.Rounded(25), ActivityCountSnapshot(1L, 3L).allowedShare)
        assertEquals(ActivityPercentageUi.Rounded(75), ActivityCountSnapshot(1L, 3L).blockedShare)
    }

    @Test
    fun `date draft accepts Today and the first day of the twenty fourth selectable month`() {
        val today = LocalDate.of(2026, 8, 1)
        assertEquals(ActivityDateRangeDraft(today, today, today), ActivityDateRangeDraft(today, today, today))
        val earliest = LocalDate.of(2024, 9, 1)
        assertEquals(earliest, ActivityDateRangeDraft(earliest, today, today).start)
    }

    @Test
    fun `date draft rejects future reversed and twenty fifth month endpoints`() {
        val today = LocalDate.of(2026, 8, 1)
        assertThrows(IllegalArgumentException::class.java) {
            ActivityDateRangeDraft(today, today.plusDays(1), today)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityDateRangeDraft(today, today.minusDays(1), today)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityDateRangeDraft(LocalDate.of(2024, 8, 31), today, today)
        }
    }

    @Test
    fun `unavailable companion contains no snapshot or action evidence`() {
        compose.setContent {
            LavaTheme {
                ActivityScreen(ActivityUiState.unavailable, {}, {}, {}, queryCallbacks = callbacks())
            }
        }

        compose.onNodeWithText("Local Activity evidence is unavailable.").assertExists()
        compose.onNodeWithText("Total activity").assertDoesNotExist()
        compose.onNodeWithText("Show Activity").assertDoesNotExist()
        compose.onNodeWithText("Top Domains").assertDoesNotExist()
        compose.onNodeWithTag("activity_refresh_button").assertDoesNotExist()
        compose.onNodeWithTag("activity_retry_button").assertDoesNotExist()
    }

    @Test
    fun `large skewed counts use truthful model bands and rendered copy`() {
        val allowedDominant = ActivityCountSnapshot(Long.MAX_VALUE, 1L)
        val blockedDominant = ActivityCountSnapshot(1L, Long.MAX_VALUE)
        assertEquals(ActivityPercentageUi.GreaterThan99, allowedDominant.allowedShare)
        assertEquals(ActivityPercentageUi.LessThanOne, allowedDominant.blockedShare)
        assertEquals(ActivityPercentageUi.LessThanOne, blockedDominant.allowedShare)
        assertEquals(ActivityPercentageUi.GreaterThan99, blockedDominant.blockedShare)

        compose.setContent {
            LavaTheme {
                ActivityScreen(readyState(allowedDominant), {}, {}, {})
            }
        }

        compose.onNodeWithText(">99%", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("<1%", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("100%", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("0%", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `failed domain action copy never claims it was applied or successful`() {
        val copy = domainActionFailureCopy(
            DomainActionUiState.ApplyFailed(
                DomainActionReviewUi(1L, "example.com", DomainActionUi.Block, emptyList()),
                "Could not update the domain.",
            ),
        )

        assertFalse(copy.contains("applied", ignoreCase = true))
        assertFalse(copy.contains("success", ignoreCase = true))
        assertEquals("Could not update the domain.", copy)
    }

    @Test
    fun `compact date copy and pure uptime parts preserve their boundaries`() {
        val today = LocalDate.of(2026, 8, 1)
        fun range(start: LocalDate, end: LocalDate) = ActivityDateRangeUi(start, end, today, "Asia/Tokyo")

        assertEquals("Today", formatActivityDateRange(range(today, today)))
        assertEquals("Jul 31", formatActivityDateRange(range(LocalDate.of(2026, 7, 31), LocalDate.of(2026, 7, 31))))
        assertEquals("Dec 31, 2025", formatActivityDateRange(range(LocalDate.of(2025, 12, 31), LocalDate.of(2025, 12, 31))))
        assertEquals("Jul 1–3", formatActivityDateRange(range(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3))))
        assertEquals("Dec 31, 2025 – Jan 1", formatActivityDateRange(range(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1))))
        assertEquals("Mar 2026 – Aug 2026", formatActivityDateRange(range(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 8, 1))))
        assertEquals(listOf(ActivityUptimePart(ActivityUptimeUnit.Minute, 0L)), activityUptimeParts(0L))
        assertEquals(
            listOf(ActivityUptimePart(ActivityUptimeUnit.Day, 1L), ActivityUptimePart(ActivityUptimeUnit.Hour, 2L)),
            activityUptimeParts((1L * 24L * 60L + 2L * 60L + 59L) * 60_000L + 59_999L),
        )
        assertEquals(
            listOf(ActivityUptimePart(ActivityUptimeUnit.Day, 2L), ActivityUptimePart(ActivityUptimeUnit.Hour, 3L)),
            activityUptimeParts((2L * 24L * 60L + 3L * 60L + 4L) * 60_000L),
        )
    }

    @Test
    fun `date draft reducer clamps Start normalizes End and reset stays draft only`() {
        val today = LocalDate.of(2026, 8, 1)
        val initial = ActivityDateDraftState(ActivityDateRangeDraft(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 20), today))
        val selectedEnd = ActivityDateDraftReducer.select(initial, ActivityDateEndpoint.End)
        assertEquals(initial.draft, selectedEnd.draft)
        assertEquals(ActivityDateEndpoint.End, selectedEnd.endpoint)
        val start = ActivityDateDraftReducer.choose(initial, LocalDate.of(2026, 7, 25))
        assertEquals(
            ActivityDateDraftState(
                ActivityDateRangeDraft(LocalDate.of(2026, 7, 25), LocalDate.of(2026, 7, 25), today),
                ActivityDateEndpoint.End,
            ),
            start,
        )
        val end = ActivityDateDraftReducer.choose(start, LocalDate.of(2026, 7, 15))
        assertEquals(
            ActivityDateDraftState(
                ActivityDateRangeDraft(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 25), today),
                ActivityDateEndpoint.Start,
            ),
            end,
        )
        assertEquals(
            ActivityDateDraftState(ActivityDateRangeDraft(today, today, today), ActivityDateEndpoint.Start),
            ActivityDateDraftReducer.reset(end),
        )
    }

    @Test
    fun `date picker transport uses UTC midnight independently of the default timezone`() {
        val previous = TimeZone.getDefault()
        val springDate = LocalDate.of(2026, 3, 8)
        val autumnDate = LocalDate.of(2026, 11, 1)
        val springUtc = Instant.parse("2026-03-08T00:00:00Z").toEpochMilli()
        val autumnUtc = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        try {
            listOf("Asia/Tokyo", "America/Los_Angeles").forEach { zoneId ->
                TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
                assertEquals(springUtc, localDateToUtcMillis(springDate))
                assertEquals(autumnUtc, localDateToUtcMillis(autumnDate))
                assertEquals(springDate, utcMillisToLocalDate(springUtc))
                assertEquals(autumnDate, utcMillisToLocalDate(autumnUtc))
            }
        } finally {
            TimeZone.setDefault(previous)
        }

        val state = ActivityDateDraftState(
            draft = ActivityDateRangeDraft(springDate, autumnDate, autumnDate),
            endpoint = ActivityDateEndpoint.Start,
        )
        assertEquals(
            ActivityDatePickerTransport(
                selectedDate = springDate,
                selectedDateMillis = springUtc,
                displayedMonthMillis = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli(),
                focusEndpoint = ActivityDateEndpoint.Start,
            ),
            activityDatePickerTransport(state),
        )
    }

    @Test
    fun `flow geometry reserves the gap and keeps both nonzero segments visible`() {
        val total = java.math.BigInteger.valueOf(101L)
        val geometry = flowBarGeometry(100L, 1L, total, 100.dp)
        assertEquals(3.dp, geometry.gapWidth)
        assertEquals(10.dp, geometry.blockedWidth)
        assertEquals(87.dp, geometry.allowedWidth)
        assertEquals(ActivityFlowGeometry(100.dp, 0.dp, 0.dp), flowBarGeometry(1L, 0L, java.math.BigInteger.ONE, 100.dp))
        assertEquals(ActivityFlowGeometry(0.dp, 0.dp, 0.dp), flowBarGeometry(0L, 0L, java.math.BigInteger.ZERO, 100.dp))
    }

    @Test
    fun `ready overview refresh invokes the real callback exactly once`() {
        var refreshes = 0
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    readyState(ActivityCountSnapshot(1L, 1L)),
                    {},
                    {},
                    {},
                    queryCallbacks = callbacks(onRefresh = { refreshes += 1 }),
                )
            }
        }
        compose.onNodeWithTag("activity_refresh_button").assertExists().performClick()
        compose.onNodeWithTag("activity_date_range_control").assertExists()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun `the date control discloses the fixed window and the device timezone`() {
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    readyState(ActivityCountSnapshot(1L, 1L)),
                    {},
                    {},
                    {},
                    queryCallbacks = callbacks(),
                )
            }
        }
        compose.onNodeWithTag("activity_date_scope_detail").assertExists()
        compose.onNodeWithText(
            "Activity dates go back 24 months from today and use your device timezone (Asia/Tokyo).",
        ).assertExists()
    }

    @Test
    fun `the overview states the clear scope after the privacy detail`() {
        compose.setContent {
            LavaTheme { ActivityScreen(readyState(ActivityCountSnapshot(1L, 1L)), {}, {}, {}) }
        }
        compose.onNodeWithTag("activity_clear_scope").assertExists()
        compose.onNodeWithText(
            "Clearing local Activity removes counts, uptime, and Domain History together.",
        ).assertExists()
    }

    @Test
    fun `an empty range explains itself and mentions date adjustment with a real control`() {
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    readyState(ActivityCountSnapshot(0L, 0L)),
                    {},
                    {},
                    {},
                    queryCallbacks = callbacks(),
                )
            }
        }
        compose.onNodeWithText("No activity was recorded for these dates.").assertExists()
        compose.onNodeWithTag("activity_empty_adjust_dates").assertExists()
        compose.onNodeWithText("Adjust the dates to see earlier activity.").assertExists()
    }

    @Test
    fun `an empty read-only range never links a date adjustment`() {
        compose.setContent {
            LavaTheme { ActivityScreen(readyState(ActivityCountSnapshot(0L, 0L)), {}, {}, {}) }
        }
        compose.onNodeWithText("No activity was recorded for these dates.").assertExists()
        compose.onNodeWithTag("activity_empty_adjust_dates").assertDoesNotExist()
    }

    @Test
    fun `ready overview without callbacks has no query controls`() {
        compose.setContent { LavaTheme { ActivityScreen(readyState(ActivityCountSnapshot(1L, 1L)), {}, {}, {}) } }
        compose.onNodeWithTag("activity_refresh_button").assertDoesNotExist()
        compose.onNodeWithTag("activity_date_range_control").assertDoesNotExist()
    }

    @Test
    fun `ready overview with unavailable counts never fabricates a zero snapshot`() {
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    stateWithOverview(ActivityContentState.Ready(overviewSnapshot(null))),
                    {},
                    {},
                    {},
                )
            }
        }

        compose.onNodeWithTag("activity_counts_unavailable_panel").assertExists()
        compose.onNodeWithText("No local count evidence is available for this date range.").assertExists()
        compose.onNodeWithTag("activity_counts_panel").assertDoesNotExist()
        compose.onNodeWithTag("activity_flow_bar").assertDoesNotExist()
    }

    @Test
    fun `disabled overview is distinct and never exposes query controls`() {
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    stateWithOverview(ActivityContentState.Disabled),
                    {},
                    {},
                    {},
                    queryCallbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("activity_disabled_panel").assertExists()
        compose.onNodeWithText("Local Activity has been turned off.").assertExists()
        compose.onNodeWithTag("activity_counts_panel").assertDoesNotExist()
        compose.onNodeWithTag("activity_refresh_button").assertDoesNotExist()
        compose.onNodeWithTag("activity_retry_button").assertDoesNotExist()
    }

    @Test
    fun `initial loading has no retained snapshot or query controls`() {
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    stateWithOverview(ActivityContentState.Loading(null)),
                    {},
                    {},
                    {},
                    queryCallbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("activity_initial_loading_panel").assertExists()
        compose.onNodeWithTag("activity_refreshing_panel").assertDoesNotExist()
        compose.onNodeWithTag("activity_counts_panel").assertDoesNotExist()
        compose.onNodeWithTag("activity_refresh_button").assertDoesNotExist()
    }

    @Test
    fun `retained loading preserves the snapshot and gates refresh on callbacks`() {
        val callbacksEnabled = mutableStateOf(false)
        var refreshes = 0
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    stateWithOverview(ActivityContentState.Loading(overviewSnapshot(ActivityCountSnapshot(7L, 3L)))),
                    {},
                    {},
                    {},
                    queryCallbacks = if (callbacksEnabled.value) callbacks(onRefresh = { refreshes += 1 }) else null,
                )
            }
        }

        compose.onNodeWithTag("activity_refreshing_panel").assertExists()
        compose.onNodeWithTag("activity_initial_loading_panel").assertDoesNotExist()
        compose.onNodeWithText("10 requests processed").assertExists()
        compose.onNodeWithTag("activity_refresh_button").assertDoesNotExist()
        compose.runOnIdle { callbacksEnabled.value = true }
        compose.onNodeWithTag("activity_refresh_button").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun `blocking failure gates retry on a real callback and invokes it once`() {
        val callbacksEnabled = mutableStateOf(false)
        var retries = 0
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    stateWithOverview(ActivityContentState.Failed("Summary source is offline.", null)),
                    {},
                    {},
                    {},
                    queryCallbacks = if (callbacksEnabled.value) callbacks(onRefresh = { retries += 1 }) else null,
                )
            }
        }

        compose.onNodeWithTag("activity_failed_panel").assertExists()
        compose.onNodeWithText("Summary source is offline.").assertExists()
        compose.onNodeWithTag("activity_counts_panel").assertDoesNotExist()
        compose.onNodeWithTag("activity_retry_button").assertDoesNotExist()
        compose.runOnIdle { callbacksEnabled.value = true }
        compose.onNodeWithTag("activity_retry_button").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `retained failure preserves its snapshot and gates retry and refresh together`() {
        val callbacksEnabled = mutableStateOf(false)
        var refreshRequests = 0
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    stateWithOverview(
                        ActivityContentState.Failed(
                            "The retained summary could not be refreshed.",
                            overviewSnapshot(ActivityCountSnapshot(11L, 2L)),
                        ),
                    ),
                    {},
                    {},
                    {},
                    queryCallbacks = if (callbacksEnabled.value) {
                        callbacks(onRefresh = { refreshRequests += 1 })
                    } else {
                        null
                    },
                )
            }
        }

        compose.onNodeWithTag("activity_refresh_failed_panel").assertExists()
        compose.onNodeWithText("13 requests processed").assertExists()
        compose.onNodeWithTag("activity_refresh_button").assertDoesNotExist()
        compose.onNodeWithTag("activity_retry_button").assertDoesNotExist()
        compose.runOnIdle { callbacksEnabled.value = true }
        compose.onNodeWithTag("activity_refresh_button").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, refreshRequests) }
        compose.onNodeWithTag("activity_retry_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(2, refreshRequests) }
    }

    @Test
    fun `overview navigation cards invoke only their exact destinations`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    readyState(ActivityCountSnapshot(1L, 1L)),
                    {},
                    { opened += "top" },
                    { opened += "history" },
                )
            }
        }

        compose.onNodeWithTag("activity_open_top_domains")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(listOf("top"), opened) }
        compose.onNodeWithTag("activity_open_domain_history")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(listOf("top", "history"), opened) }
    }

    @Test
    fun `flow bar has one accessible description`() {
        compose.setContent { LavaTheme { ActivityScreen(readyState(ActivityCountSnapshot(2L, 1L)), {}, {}, {}) } }
        compose.onAllNodesWithContentDescription("Allowed 2, blocked 1").assertCountEquals(1)
    }

    @Test
    fun `zero activity renders one track-only accessible flow bar`() {
        compose.setContent { LavaTheme { ActivityScreen(readyState(ActivityCountSnapshot(0L, 0L)), {}, {}, {}) } }
        compose.onNodeWithTag("activity_flow_bar").assertExists()
        compose.onNodeWithTag("activity_flow_track").assertExists()
        compose.onNodeWithTag("activity_flow_allowed_segment").assertDoesNotExist()
        compose.onNodeWithTag("activity_flow_blocked_segment").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Allowed 0, blocked 0").assertCountEquals(1)
        compose.onNodeWithText("No activity was recorded for these dates.").assertExists()
        compose.onNodeWithText("0 minutes protected locally").assertExists()
    }

    @Test
    fun `unavailable uptime never renders numeric protection evidence`() {
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    readyState(ActivityCountSnapshot(1L, 1L), uptimeMillis = null),
                    {},
                    {},
                    {},
                )
            }
        }

        compose.onNodeWithText("Local protection uptime is unavailable").assertExists()
        compose.onNodeWithText("protected locally", substring = true).assertDoesNotExist()
    }

    @Test
    fun `uptime renders singular and other units while flooring and bounding output`() {
        val uptimeMillis = (1L * 24L * 60L + 2L * 60L + 59L) * 60_000L + 59_999L
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    readyState(ActivityCountSnapshot(1L, 1L), uptimeMillis = uptimeMillis),
                    {},
                    {},
                    {},
                )
            }
        }

        compose.onNodeWithText("1 day 2 hours protected locally").assertExists()
        compose.onNodeWithText("minute", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [28, 36], qualifiers = "de-rDE")
    fun `uptime renders Long values with the active non-English number locale`() {
        val uptimeMillis = (1_234L * 24L * 60L + 1L * 60L) * 60_000L
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    readyState(ActivityCountSnapshot(1L, 1L), uptimeMillis = uptimeMillis),
                    {},
                    {},
                    {},
                )
            }
        }

        compose.onNodeWithText("1.234 Tage 1 Stunde lokal geschützt").assertExists()
    }

    @Test
    fun `date dialog reset Close reopen and Show preserve draft authority and exact callbacks`() {
        val selected = mutableListOf<Pair<LocalDate, LocalDate>>()
        val originalStart = LocalDate.of(2026, 7, 10)
        val originalEnd = LocalDate.of(2026, 7, 20)
        val state = readyState(
            ActivityCountSnapshot(1L, 1L),
            start = originalStart,
            endInclusive = originalEnd,
        )
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    state,
                    {},
                    {},
                    {},
                    queryCallbacks = callbacks(onDates = { start, end -> selected += start to end }),
                )
            }
        }

        compose.onNodeWithTag("activity_date_range_control").performClick()
        compose.onNodeWithTag("activity_date_start")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertIsFocused()
        compose.onNodeWithTag("activity_date_end").performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertIsFocused()
        compose.onNodeWithTag("activity_date_start").performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertIsFocused()
        compose.onNodeWithTag("activity_date_end").performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertIsFocused()
        compose.onNodeWithTag("activity_date_reset").performScrollTo().performClick()
        compose.onNodeWithTag("activity_date_start")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertIsFocused()
        compose.onNodeWithText("Start: Today").assertExists()
        compose.onNodeWithText("End: Today").assertExists()
        compose.onNodeWithTag("activity_date_close").performClick()
        compose.runOnIdle { assertTrue(selected.isEmpty()) }

        compose.onNodeWithTag("activity_date_range_control").performClick()
        compose.onNodeWithText("Start: Jul 10").assertExists()
        compose.onNodeWithText("End: Jul 20").assertExists()
        compose.onNodeWithTag("activity_date_show").performClick()
        compose.runOnIdle { assertEquals(listOf(originalStart to originalEnd), selected) }

        compose.onNodeWithTag("activity_date_range_control").performClick()
        compose.onNodeWithTag("activity_date_reset").performScrollTo().performClick()
        compose.onNodeWithTag("activity_date_show").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    originalStart to originalEnd,
                    LocalDate.of(2026, 8, 1) to LocalDate.of(2026, 8, 1),
                ),
                selected,
            )
        }
    }

    @Test
    fun `open date dialog rebinds picker when today advances from December 31 to January 1`() {
        val december31 = LocalDate.of(2025, 12, 31)
        val january1 = LocalDate.of(2026, 1, 1)
        val januaryMonth = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val selected = mutableListOf<Pair<LocalDate, LocalDate>>()
        val state = mutableStateOf(
            readyState(
                counts = ActivityCountSnapshot(1L, 1L),
                start = december31,
                endInclusive = december31,
                today = december31,
            ),
        )
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    state = state.value,
                    onBack = {},
                    onOpenTopDomains = {},
                    onOpenDomainHistory = {},
                    queryCallbacks = callbacks(onDates = { start, end -> selected += start to end }),
                )
            }
        }

        compose.onNodeWithTag("activity_date_range_control").performClick()
        compose.runOnIdle {
            state.value = readyState(
                counts = ActivityCountSnapshot(1L, 1L),
                start = january1,
                endInclusive = january1,
                today = january1,
            )
        }
        compose.onNodeWithTag("activity_date_picker").assert(
            SemanticsMatcher.expectValue(ActivityDatePickerDisplayedMonthMillisKey, januaryMonth),
        )
        compose.onNodeWithText("Start: Today").assertExists()
        compose.onNodeWithText("End: Today").assertExists()
        compose.onNodeWithTag("activity_date_show").performClick()
        compose.runOnIdle { assertEquals(listOf(january1 to january1), selected) }
    }

    @Test
    fun `open date dialog refreshes selectable bounds when today advances within one year range`() {
        val january31 = LocalDate.of(2026, 1, 31)
        val january30 = LocalDate.of(2026, 1, 30)
        val february1 = LocalDate.of(2026, 2, 1)
        val state = mutableStateOf(
            readyState(
                counts = ActivityCountSnapshot(1L, 1L),
                start = january31,
                endInclusive = january31,
                today = january31,
            ),
        )
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    state = state.value,
                    onBack = {},
                    onOpenTopDomains = {},
                    onOpenDomainHistory = {},
                    queryCallbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("activity_date_range_control").performClick()
        compose.runOnIdle {
            state.value = readyState(
                counts = ActivityCountSnapshot(1L, 1L),
                start = january30,
                endInclusive = february1,
                today = february1,
            )
        }

        compose.onNodeWithContentDescription("Change to next month")
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Sunday, February 1, 2026")
            .assertIsEnabled()
    }

    @Test
    fun `equal endpoints and reset resynchronize the real picker browsed month`() {
        val augustMonth = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
        val julyMonth = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    readyState(ActivityCountSnapshot(1L, 1L)),
                    {},
                    {},
                    {},
                    queryCallbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("activity_date_range_control").performClick()
        val picker = compose.onNodeWithTag("activity_date_picker")
        picker.assert(
            SemanticsMatcher.expectValue(ActivityDatePickerDisplayedMonthMillisKey, augustMonth),
        )

        compose.onNodeWithContentDescription("Change to previous month")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        picker.assert(
            SemanticsMatcher.expectValue(ActivityDatePickerDisplayedMonthMillisKey, julyMonth),
        )

        compose.onNodeWithTag("activity_date_end").performScrollTo().performClick()
        picker.assert(
            SemanticsMatcher.expectValue(ActivityDatePickerDisplayedMonthMillisKey, augustMonth),
        )

        compose.onNodeWithContentDescription("Change to previous month")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        picker.assert(
            SemanticsMatcher.expectValue(ActivityDatePickerDisplayedMonthMillisKey, julyMonth),
        )

        compose.onNodeWithTag("activity_date_reset").performScrollTo().performClick()
        picker.assert(
            SemanticsMatcher.expectValue(ActivityDatePickerDisplayedMonthMillisKey, augustMonth),
        )
    }

    @Test
    fun `no-op reset resynchronizes the real picker browsed month`() {
        val augustMonth = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
        val julyMonth = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    readyState(ActivityCountSnapshot(1L, 1L)),
                    {},
                    {},
                    {},
                    queryCallbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("activity_date_range_control").performClick()
        val picker = compose.onNodeWithTag("activity_date_picker")
        picker.assert(
            SemanticsMatcher.expectValue(ActivityDatePickerDisplayedMonthMillisKey, augustMonth),
        )

        compose.onNodeWithContentDescription("Change to previous month")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        picker.assert(
            SemanticsMatcher.expectValue(ActivityDatePickerDisplayedMonthMillisKey, julyMonth),
        )

        compose.onNodeWithTag("activity_date_reset").performScrollTo().performClick()
        picker.assert(
            SemanticsMatcher.expectValue(ActivityDatePickerDisplayedMonthMillisKey, augustMonth),
        )
    }

    @Test
    @Config(sdk = [28, 36], qualifiers = "ldrtl-w320dp-h240dp")
    fun `date dialog controls are scroll reachable at compact RTL enlarged font`() {
        var observedLayoutDirection: LayoutDirection? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                observedLayoutDirection = LocalLayoutDirection.current
                LavaTheme {
                    ActivityScreen(readyState(ActivityCountSnapshot(1L, 1L)), {}, {}, {}, queryCallbacks = callbacks())
                }
            }
        }

        compose.runOnIdle { assertEquals(LayoutDirection.Rtl, observedLayoutDirection) }
        compose.onNodeWithTag("activity_date_range_control")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("activity_date_dialog_scroll")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
        compose.onNodeWithTag("activity_date_start").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("activity_date_end").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("activity_date_reset").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("activity_date_close").assertIsDisplayed()
        compose.onNodeWithTag("activity_date_show").assertIsDisplayed()
    }

    @Test
    fun `integer count formatting is deterministic outside English`() {
        assertEquals("1.234.567", formatActivityCount(java.math.BigInteger.valueOf(1_234_567L), Locale.GERMANY))
        assertTrue(formatActivityCount(java.math.BigInteger.valueOf(Long.MAX_VALUE), Locale.JAPAN).isNotBlank())
    }

    private fun readyState(
        counts: ActivityCountSnapshot,
        uptimeMillis: Long? = 0L,
        start: LocalDate = TODAY,
        endInclusive: LocalDate = TODAY,
        today: LocalDate = TODAY,
    ): ActivityUiState = stateWithOverview(
        ActivityContentState.Ready(
            overviewSnapshot(counts, uptimeMillis, start, endInclusive, today),
        ),
    )

    private fun overviewSnapshot(
        counts: ActivityCountSnapshot?,
        uptimeMillis: Long? = 0L,
        start: LocalDate = TODAY,
        endInclusive: LocalDate = TODAY,
        today: LocalDate = TODAY,
    ) = ActivityOverviewSnapshot(
        dateRange = ActivityDateRangeUi(
            start = start,
            endInclusive = endInclusive,
            today = today,
            zoneId = "Asia/Tokyo",
        ),
        counts = counts,
        localProtectionUptimeMillis = uptimeMillis,
        retainedDomainLogs = ActivityLogAvailabilityUi.Retained,
    )

    private fun stateWithOverview(
        overview: ActivityContentState<ActivityOverviewSnapshot>,
    ) = ActivityUiState(
        overview = overview,
        topDomains = ActivityContentState.Unavailable,
        domainHistory = ActivityContentState.Unavailable,
        retainedDomainLogs = ActivityContentState.Unavailable,
        historyLoadingMore = false,
        domainAction = DomainActionUiState.Idle,
        domainActionNotice = null,
    )

    private fun callbacks(
        onRefresh: () -> Unit = {},
        onDates: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    ) = ActivityQueryCallbacks(
        onRefreshSummary = onRefresh,
        onDateRangeSelected = onDates,
        onTopDomainDecisionSelected = {},
        onTopDomainSearchChanged = {},
        onRefreshHistory = {},
        onHistoryDecisionSelected = {},
        onHistorySearchChanged = {},
        onLoadMoreHistory = {},
        onRefreshNetworkActivity = {},
        onLoadMoreNetworkActivity = {},
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 1)
    }
}
