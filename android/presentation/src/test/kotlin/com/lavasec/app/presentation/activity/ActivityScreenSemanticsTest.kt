package com.lavasec.app.presentation.activity

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.presentation.R
import java.time.LocalDate
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], qualifiers = "ldrtl-w320dp-h240dp")
class ActivityScreenSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    // State, paging, row-action, review, and compact-layout tests are grouped below. Every test
    // runs against both supported Android behavior boundaries through the class-level SDK matrix.

    private lateinit var previousLocale: Locale
    private lateinit var previousTimeZone: TimeZone

    @Before
    fun useDeterministicLocalTime() {
        previousLocale = Locale.getDefault()
        previousTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    @After
    fun restoreLocalTime() {
        Locale.setDefault(previousLocale)
        TimeZone.setDefault(previousTimeZone)
    }

    @Test
    fun `Top Domains renders every load state without fabricated evidence`() {
        lateinit var update: (ActivityContentState<TopDomainsSnapshot>) -> Unit
        setTopDomains(ActivityContentState.Unavailable, captureState = { update = it })

        compose.onNodeWithText("Local Activity evidence is unavailable.").assertExists()
        compose.onNodeWithTag("activity_search").assertDoesNotExist()
        compose.onNodeWithTag("activity_domain_row").assertDoesNotExist()

        compose.runOnIdle { update(ActivityContentState.Loading(null)) }
        compose.onNodeWithTag("activity_top_initial_loading").assertExists()

        compose.runOnIdle { update(ActivityContentState.Loading(topSnapshot())) }
        compose.onNodeWithText("zeta.example").assertExists()
        compose.onNodeWithTag("activity_top_refreshing").assertExists()

        compose.runOnIdle { update(ActivityContentState.Disabled) }
        compose.onNodeWithText("Turn on Domain History to see your most frequent domains.").assertExists()
        compose.onNodeWithText("zeta.example").assertDoesNotExist()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    topSnapshot(
                        availability = ActivityLogAvailabilityUi.Disabled,
                        rows = emptyList(),
                    ),
                ),
            )
        }
        compose.onNodeWithText("Turn on Domain History to see your most frequent domains.").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    topSnapshot(
                        decision = ActivityDecisionUi.Allowed,
                        availability = ActivityLogAvailabilityUi.Empty,
                        rows = emptyList(),
                    ),
                ),
            )
        }
        compose.onNodeWithText("No allowed domains saved yet").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    topSnapshot(search = "missing", rows = emptyList()),
                ),
            )
        }
        compose.onNodeWithText("No domains match this search").assertExists()

        compose.runOnIdle { update(ActivityContentState.Failed("offline", null)) }
        compose.onNodeWithText("Top Domains could not be loaded").assertExists()
        compose.onNodeWithText("offline").assertExists()
        compose.onNodeWithTag("activity_retry_button").assertDoesNotExist()

        compose.runOnIdle { update(ActivityContentState.Failed("stale", topSnapshot())) }
        compose.onNodeWithText("zeta.example").assertExists()
        compose.onNodeWithTag("activity_top_retained_failure").assertExists()
        compose.onNodeWithText("stale").assertExists()
    }

    @Test
    fun `unavailable Activity has no fake zero date search paging or row action`() {
        compose.setContent {
            LavaTheme {
                ActivityScreen(
                    state = ActivityUiState.unavailable,
                    onBack = {},
                    onOpenTopDomains = {},
                    onOpenDomainHistory = {},
                )
            }
        }

        compose.onNodeWithText("Local Activity evidence is unavailable.").assertExists()
        compose.onNodeWithText("0").assertDoesNotExist()
        compose.onNodeWithTag("activity_date_range_control").assertDoesNotExist()
        compose.onNodeWithTag("activity_search").assertDoesNotExist()
        compose.onNodeWithTag("activity_load_more").assertDoesNotExist()
        compose.onNodeWithTag("activity_domain_action").assertDoesNotExist()
    }

    @Test
    fun `Domain History renders every load state and does not invent empty provenance`() {
        lateinit var update: (
            ActivityContentState<DomainHistorySnapshot>,
            ActivityContentState<Boolean>,
            Boolean,
        ) -> Unit
        setDomainHistory(
            initialState = ActivityContentState.Unavailable,
            retainedDomainLogs = ActivityContentState.Unavailable,
            captureUpdate = { update = it },
        )

        compose.onNodeWithText("Local Activity evidence is unavailable.").assertExists()
        compose.onNodeWithTag("activity_domain_row").assertDoesNotExist()

        compose.runOnIdle {
            update(ActivityContentState.Loading(null), ActivityContentState.Loading(null), false)
        }
        compose.onNodeWithTag("activity_history_initial_loading").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Loading(historySnapshot()),
                ActivityContentState.Ready(true),
                false,
            )
        }
        compose.onNodeWithText("zeta.example").assertExists()
        compose.onNodeWithTag("activity_history_refreshing").assertExists()

        compose.runOnIdle {
            update(ActivityContentState.Disabled, ActivityContentState.Disabled, false)
        }
        compose.onNodeWithText("Local history is off").assertExists()
        compose.onNodeWithText("Turn on local history only if you want this searchable list.").assertExists()
        compose.onNodeWithText("Turn On Local History").assertDoesNotExist()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(historySnapshot(rows = emptyList())),
                ActivityContentState.Ready(false),
                false,
            )
        }
        compose.onNodeWithText("No blocked domains saved yet").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(historySnapshot(search = "missing", rows = emptyList())),
                ActivityContentState.Ready(true),
                false,
            )
        }
        compose.onNodeWithText("No domains match this search").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(historySnapshot(rows = emptyList())),
                ActivityContentState.Unavailable,
                false,
            )
        }
        compose.onNodeWithText("No blocked domains saved yet").assertDoesNotExist()
        compose.onNodeWithText("No domains match this search").assertDoesNotExist()
        compose.onNodeWithTag("activity_history_provenance_unavailable").assertExists()

        compose.runOnIdle {
            update(ActivityContentState.Failed("database closed", null), ActivityContentState.Failed("unknown", null), false)
        }
        compose.onNodeWithText("Domain History could not be loaded").assertExists()
        compose.onNodeWithText("database closed").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Failed("refresh failed", historySnapshot()),
                ActivityContentState.Ready(true),
                false,
            )
        }
        compose.onNodeWithText("zeta.example").assertExists()
        compose.onNodeWithTag("activity_history_retained_failure").assertExists()
    }

    @Test
    fun `retained log loading and failure preserve exact provenance with additive evidence`() {
        lateinit var update: (
            ActivityContentState<DomainHistorySnapshot>,
            ActivityContentState<Boolean>,
            Boolean,
        ) -> Unit
        setDomainHistory(
            initialState = ActivityContentState.Ready(historySnapshot()),
            retainedDomainLogs = ActivityContentState.Loading(true),
            captureUpdate = { update = it },
        )

        compose.onNodeWithText("zeta.example").assertExists()
        compose.onNodeWithTag("activity_retained_logs_loading").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(historySnapshot(rows = emptyList())),
                ActivityContentState.Loading(false),
                false,
            )
        }
        compose.onNodeWithText("No blocked domains saved yet").assertExists()
        compose.onNodeWithTag("activity_retained_logs_loading").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(historySnapshot(search = "missing", rows = emptyList())),
                ActivityContentState.Loading(null),
                false,
            )
        }
        compose.onNodeWithText("No domains match this search").assertDoesNotExist()
        compose.onNodeWithText("No blocked domains saved yet").assertDoesNotExist()
        compose.onNodeWithTag("activity_history_provenance_unavailable").assertExists()
        compose.onNodeWithTag("activity_retained_logs_loading").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(historySnapshot()),
                ActivityContentState.Failed("retention refresh failed", true),
                false,
            )
        }
        compose.onNodeWithText("zeta.example").assertExists()
        compose.onNodeWithText("retention refresh failed").assertExists()
        compose.onNodeWithTag("activity_retained_logs_failure").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(historySnapshot(rows = emptyList())),
                ActivityContentState.Failed("empty evidence stale", false),
                false,
            )
        }
        compose.onNodeWithText("No blocked domains saved yet").assertExists()
        compose.onNodeWithText("empty evidence stale").assertExists()

        compose.runOnIdle {
            update(
                ActivityContentState.Ready(historySnapshot(search = "missing", rows = emptyList())),
                ActivityContentState.Failed("unknown retention", null),
                false,
            )
        }
        compose.onNodeWithText("No domains match this search").assertDoesNotExist()
        compose.onNodeWithText("No blocked domains saved yet").assertDoesNotExist()
        compose.onNodeWithTag("activity_history_provenance_unavailable").assertExists()
        compose.onNodeWithText("unknown retention").assertExists()
    }

    @Test
    fun `Top Domains keeps product order and wires only its explicit query callbacks`() {
        val decisions = mutableListOf<ActivityDecisionUi>()
        val searches = mutableListOf<String>()
        setTopDomains(
            initialState = ActivityContentState.Ready(topSnapshot()),
            queryCallbacks = callbacks(onTopDecision = decisions::add, onTopSearch = searches::add),
        )

        compose.onNodeWithTag("activity_search").performTextReplacement("needle")
        compose.onNodeWithTag("activity_decision_allowed").performClick()
        compose.onNodeWithTag("activity_refresh_button").assertDoesNotExist()
        val renderedDomains = compose.onAllNodesWithTag("activity_domain_row")
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].first().text }
        compose.runOnIdle {
            assertEquals(listOf("zeta.example", "alpha.example"), renderedDomains)
            assertEquals(listOf("needle"), searches)
            assertEquals(listOf(ActivityDecisionUi.Allowed), decisions)
        }
        compose.onNodeWithText("7 times").assertExists()
        compose.onNodeWithText("3 times").assertExists()
        compose.onNodeWithText(retentionCopy).assertExists()
    }

    @Test
    fun `Top Domains states the exact clear scope next to the retention footer`() {
        setTopDomains(ActivityContentState.Ready(topSnapshot()))
        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasText(domainClearScopeCopy))
        compose.onNodeWithText(domainClearScopeCopy).assertExists()
        compose.onNodeWithTag("top_domains_clear_scope").assertExists()
    }

    @Test
    fun `Domain History states the exact clear scope next to the retention footer`() {
        setDomainHistory(ActivityContentState.Ready(historySnapshot()))
        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasText(domainClearScopeCopy))
        compose.onNodeWithText(domainClearScopeCopy).assertExists()
        compose.onNodeWithTag("domain_history_clear_scope").assertExists()
    }

    @Test
    fun `Top Domains keeps query controls through callback driven query transitions`() {
        val searches = mutableListOf<String>()
        val decisions = mutableListOf<ActivityDecisionUi>()
        lateinit var update: (ActivityContentState<TopDomainsSnapshot>) -> Unit
        setTopDomains(
            initialState = ActivityContentState.Ready(
                topSnapshot(rows = listOf(TopDomainRowUi("old.example", 9L))),
            ),
            queryCallbacks = callbacks(
                onTopSearch = { search ->
                    searches += search
                    update(ActivityContentState.Loading(null))
                },
                onTopDecision = { decision ->
                    decisions += decision
                    update(ActivityContentState.Loading(null))
                },
            ),
            captureState = { update = it },
        )

        compose.onNodeWithTag("activity_search").performTextReplacement("a")
        compose.onNodeWithTag("activity_top_initial_loading").assertExists()
        compose.onNodeWithTag("activity_search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("a")),
        )
        compose.onNodeWithText("old.example").assertDoesNotExist()
        compose.onNodeWithText("No domains match this search").assertDoesNotExist()
        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    topSnapshot(
                        search = "a",
                        rows = listOf(TopDomainRowUi("returned-a.example", 4L)),
                    ),
                ),
            )
        }
        compose.onNodeWithText("returned-a.example").assertExists()

        compose.onNodeWithTag("activity_search").performTextReplacement("ab")
        compose.onNodeWithTag("activity_top_initial_loading").assertExists()
        compose.onNodeWithTag("activity_search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("ab")),
        )
        compose.onNodeWithText("returned-a.example").assertDoesNotExist()
        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    topSnapshot(
                        search = "ab",
                        rows = listOf(
                            TopDomainRowUi("z-returned.example", 5L),
                            TopDomainRowUi("a-returned.example", 3L),
                        ),
                    ),
                ),
            )
        }

        compose.onNodeWithTag("activity_decision_allowed").performClick()
        compose.onNodeWithTag("activity_top_initial_loading").assertExists()
        compose.onNodeWithTag("activity_decision_allowed")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("z-returned.example").assertDoesNotExist()
        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    topSnapshot(
                        decision = ActivityDecisionUi.Allowed,
                        search = "ab",
                        rows = listOf(TopDomainRowUi("allowed.example", 2L)),
                    ),
                ),
            )
        }
        compose.onNodeWithText("allowed.example").assertExists()

        compose.onNodeWithTag("activity_decision_blocked").performClick()
        compose.onNodeWithTag("activity_top_initial_loading").assertExists()
        compose.onNodeWithTag("activity_decision_blocked")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    topSnapshot(
                        decision = ActivityDecisionUi.Blocked,
                        search = "ab",
                        rows = listOf(TopDomainRowUi("blocked.example", 1L)),
                    ),
                ),
            )
            assertEquals(listOf("a", "ab"), searches)
            assertEquals(
                listOf(ActivityDecisionUi.Allowed, ActivityDecisionUi.Blocked),
                decisions,
            )
        }
        compose.onNodeWithText("blocked.example").assertExists()
    }

    @Test
    fun `Domain History preserves order reason labels and system-local timestamp`() {
        val rows = DomainHistoryReasonUi.entries.mapIndexed { index, reason ->
            DomainHistoryRowUi(
                id = 100L - index,
                timestampMillis = localTimestamp,
                domain = "${index}-${reason.name}.example",
                decision = if (reason == DomainHistoryReasonUi.PausedAllow) ActivityDecisionUi.Allowed else ActivityDecisionUi.Blocked,
                reason = reason,
            )
        }
        setDomainHistory(ActivityContentState.Ready(historySnapshot(rows = rows)))

        listOf(
            "Default", "Allowed on Pause", "Allowlist", "Blocklist", "Threat Guardrail",
            "Invalid domain", "Failed safe",
        ).forEach { compose.onNodeWithText(it, substring = true).assertExists() }
        compose.onAllNodesWithText("Aug 1", substring = true).assertCountEquals(7)
        compose.onAllNodesWithText("12:04", substring = true).assertCountEquals(7)
        compose.onNodeWithContentDescription("Allowed on Pause").assertExists()
        compose.onNodeWithTag("activity_paused_row").assertExists()
        val renderedDomains = compose.onAllNodesWithTag("activity_domain_row")
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].first().text }
        assertEquals(rows.map(DomainHistoryRowUi::domain), renderedDomains)
        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasText(retentionCopy))
        compose.onNodeWithText(retentionCopy).assertExists()
    }

    @Test
    fun `Domain History wires refresh decision and search only with callbacks`() {
        var refreshes = 0
        val decisions = mutableListOf<ActivityDecisionUi>()
        val searches = mutableListOf<String>()
        setDomainHistory(
            initialState = ActivityContentState.Ready(historySnapshot()),
            queryCallbacks = callbacks(
                onHistoryRefresh = { refreshes += 1 },
                onHistoryDecision = decisions::add,
                onHistorySearch = searches::add,
            ),
        )

        compose.onNodeWithTag("activity_history_refresh").performClick()
        compose.onNodeWithTag("activity_search").performTextReplacement("needle")
        compose.onNodeWithTag("activity_decision_allowed").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(listOf("needle"), searches)
            assertEquals(listOf(ActivityDecisionUi.Allowed), decisions)
        }
    }

    @Test
    fun `Domain History keeps query controls through callback driven query transitions`() {
        val searches = mutableListOf<String>()
        val decisions = mutableListOf<ActivityDecisionUi>()
        var loads = 0
        val oldRows = (60L downTo 31L).map { id ->
            DomainHistoryRowUi(
                id,
                localTimestamp,
                "old-$id.example",
                ActivityDecisionUi.Blocked,
                DomainHistoryReasonUi.Blocklist,
            )
        }
        lateinit var update: (
            ActivityContentState<DomainHistorySnapshot>,
            ActivityContentState<Boolean>,
            Boolean,
        ) -> Unit
        setDomainHistory(
            initialState = ActivityContentState.Ready(
                historySnapshot(
                    rows = oldRows,
                    nextPageKey = sentinelKey(finalRowId = oldRows.last().id),
                ),
            ),
            queryCallbacks = callbacks(
                onHistorySearch = { search ->
                    searches += search
                    update(
                        ActivityContentState.Loading(null),
                        ActivityContentState.Loading(true),
                        false,
                    )
                },
                onHistoryDecision = { decision ->
                    decisions += decision
                    update(
                        ActivityContentState.Loading(null),
                        ActivityContentState.Loading(true),
                        false,
                    )
                },
                onLoadMore = { loads += 1 },
            ),
            captureUpdate = { update = it },
        )

        compose.onNodeWithTag("activity_search").performTextReplacement("a")
        compose.onNodeWithTag("activity_history_initial_loading").assertExists()
        compose.onNodeWithTag("activity_search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("a")),
        )
        compose.onNodeWithText("old-60.example").assertDoesNotExist()
        compose.onNodeWithTag("activity_history_sentinel").assertDoesNotExist()
        compose.onNodeWithText("No domains match this search").assertDoesNotExist()
        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    historySnapshot(
                        search = "a",
                        rows = listOf(
                            historyRow(25L, "z-returned.example"),
                            historyRow(24L, "a-returned.example"),
                        ),
                    ),
                ),
                ActivityContentState.Ready(true),
                false,
            )
        }
        assertEquals(
            listOf("z-returned.example", "a-returned.example"),
            compose.onAllNodesWithTag("activity_domain_row")
                .fetchSemanticsNodes()
                .map { node -> node.config[SemanticsProperties.Text].first().text },
        )

        compose.onNodeWithTag("activity_search").performTextReplacement("ab")
        compose.onNodeWithTag("activity_history_initial_loading").assertExists()
        compose.onNodeWithTag("activity_search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("ab")),
        )
        compose.onNodeWithText("z-returned.example").assertDoesNotExist()
        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    historySnapshot(
                        search = "ab",
                        rows = listOf(historyRow(23L, "ab.example")),
                    ),
                ),
                ActivityContentState.Ready(true),
                false,
            )
        }

        compose.onNodeWithTag("activity_decision_allowed").performClick()
        compose.onNodeWithTag("activity_history_initial_loading").assertExists()
        compose.onNodeWithTag("activity_decision_allowed")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("ab.example").assertDoesNotExist()
        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    historySnapshot(
                        decision = ActivityDecisionUi.Allowed,
                        search = "ab",
                        rows = listOf(
                            historyRow(
                                id = 22L,
                                domain = "allowed.example",
                                decision = ActivityDecisionUi.Allowed,
                            ),
                        ),
                    ),
                ),
                ActivityContentState.Ready(true),
                false,
            )
        }
        compose.onNodeWithText("allowed.example").assertExists()

        compose.onNodeWithTag("activity_decision_blocked").performClick()
        compose.onNodeWithTag("activity_history_initial_loading").assertExists()
        compose.onNodeWithTag("activity_decision_blocked")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.runOnIdle {
            update(
                ActivityContentState.Ready(
                    historySnapshot(
                        decision = ActivityDecisionUi.Blocked,
                        search = "ab",
                        rows = listOf(historyRow(21L, "blocked.example")),
                    ),
                ),
                ActivityContentState.Ready(true),
                false,
            )
            assertEquals(listOf("a", "ab"), searches)
            assertEquals(
                listOf(ActivityDecisionUi.Allowed, ActivityDecisionUi.Blocked),
                decisions,
            )
            assertEquals(0, loads)
        }
        compose.onNodeWithText("blocked.example").assertExists()
    }

    @Test
    fun `Domain History failure retry exists only with its history refresh callback`() {
        var retries = 0
        setDomainHistory(
            initialState = ActivityContentState.Failed("offline", null),
            queryCallbacks = callbacks(onHistoryRefresh = { retries += 1 }),
        )

        compose.onNodeWithTag("activity_retry_button").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `ready details remain read-only without query callbacks`() {
        setTopDomains(ActivityContentState.Ready(topSnapshot()))

        compose.onNodeWithText("zeta.example").assertExists()
        compose.onNodeWithTag("activity_search").assertDoesNotExist()
        compose.onNodeWithTag("activity_decision_allowed").assertDoesNotExist()
        compose.onNodeWithTag("activity_refresh_button").assertDoesNotExist()
    }

    @Test
    fun `history sentinel admits each exact publication once and never exposes a load button`() {
        val rows = (60L downTo 31L).map { id ->
            DomainHistoryRowUi(
                id = id,
                timestampMillis = localTimestamp,
                domain = "$id.example",
                decision = ActivityDecisionUi.Blocked,
                reason = DomainHistoryReasonUi.Blocklist,
            )
        }
        val firstKey = sentinelKey(finalRowId = rows.last().id)
        var loads = 0
        lateinit var update: (
            ActivityContentState<DomainHistorySnapshot>,
            ActivityContentState<Boolean>,
            Boolean,
        ) -> Unit
        setDomainHistory(
            initialState = ActivityContentState.Ready(historySnapshot(rows = rows, nextPageKey = firstKey)),
            queryCallbacks = callbacks(onLoadMore = {
                loads += 1
                update(
                    ActivityContentState.Ready(historySnapshot(rows = rows, nextPageKey = firstKey)),
                    ActivityContentState.Ready(true),
                    true,
                )
            }),
            height = 240.dp,
            captureUpdate = { update = it },
        )

        compose.onNodeWithTag("activity_load_more").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, loads) }
        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasTestTag("activity_history_sentinel"))
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, loads) }

        compose.onNodeWithTag("activity_history_paging_progress").assertExists()
        compose.runOnIdle {
            update(
                ActivityContentState.Ready(historySnapshot(rows = rows, nextPageKey = firstKey)),
                ActivityContentState.Ready(true),
                false,
            )
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, loads) }

        val newerKey = firstKey.copy(
            cutCapturedAtMillis = firstKey.cutCapturedAtMillis + 1L,
            sourceRevision = firstKey.sourceRevision + 1L,
        )
        compose.runOnIdle {
            update(
                ActivityContentState.Ready(historySnapshot(rows = rows, nextPageKey = newerKey)),
                ActivityContentState.Ready(true),
                false,
            )
        }
        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasTestTag("activity_history_sentinel"))
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, loads) }
    }

    @Test
    fun `history sentinel retries a failed exact publication only after viewport reentry`() {
        val rows = (60L downTo 31L).map { id ->
            DomainHistoryRowUi(
                id = id,
                timestampMillis = localTimestamp,
                domain = "$id.example",
                decision = ActivityDecisionUi.Blocked,
                reason = DomainHistoryReasonUi.Blocklist,
            )
        }
        val key = sentinelKey(finalRowId = rows.last().id)
        val snapshot = historySnapshot(rows = rows, nextPageKey = key)
        var loads = 0
        lateinit var update: (
            ActivityContentState<DomainHistorySnapshot>,
            ActivityContentState<Boolean>,
            Boolean,
        ) -> Unit
        setDomainHistory(
            initialState = ActivityContentState.Ready(snapshot),
            queryCallbacks = callbacks(onLoadMore = {
                loads += 1
                update(
                    ActivityContentState.Ready(snapshot),
                    ActivityContentState.Ready(true),
                    true,
                )
            }),
            height = 240.dp,
            captureUpdate = { update = it },
        )

        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasTestTag("activity_history_sentinel"))
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, loads) }

        compose.runOnIdle {
            update(
                ActivityContentState.Failed("offline", snapshot),
                ActivityContentState.Ready(true),
                false,
            )
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, loads) }

        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasContentDescription("Back"))
        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasTestTag("activity_history_sentinel"))
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, loads) }
    }

    @Test
    fun `review summary preserves the actual count for a locale whose one category includes 21`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru"))
        }
        val resources = context.createConfigurationContext(configuration).resources

        assertEquals(
            "21 change will be prepared and saved locally.",
            resources.getQuantityString(R.plurals.activity_review_summary, 21, 21),
        )
    }

    @Test
    fun `history sentinel cannot page without truthful retained evidence`() {
        val rows = (60L downTo 31L).map { id ->
            DomainHistoryRowUi(id, localTimestamp, "$id.example", ActivityDecisionUi.Blocked, DomainHistoryReasonUi.Blocklist)
        }
        val key = sentinelKey(finalRowId = rows.last().id)
        var loads = 0
        lateinit var update: (
            ActivityContentState<DomainHistorySnapshot>,
            ActivityContentState<Boolean>,
            Boolean,
        ) -> Unit
        val state = ActivityContentState.Ready(historySnapshot(rows = rows, nextPageKey = key))
        setDomainHistory(
            initialState = state,
            retainedDomainLogs = ActivityContentState.Unavailable,
            queryCallbacks = callbacks(onLoadMore = { loads += 1 }),
            height = 240.dp,
            captureUpdate = { update = it },
        )

        compose.onNodeWithTag("activity_history_sentinel").assertDoesNotExist()
        compose.runOnIdle { update(state, ActivityContentState.Ready(false), false) }
        compose.onNodeWithTag("activity_history_sentinel").assertDoesNotExist()
        compose.runOnIdle { update(state, ActivityContentState.Loading(null), false) }
        compose.onNodeWithTag("activity_history_sentinel").assertDoesNotExist()
        compose.runOnIdle { update(state, ActivityContentState.Failed("unknown", null), false) }
        compose.onNodeWithTag("activity_history_sentinel").assertDoesNotExist()

        compose.runOnIdle { update(state, ActivityContentState.Ready(true), false) }
        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasTestTag("activity_history_sentinel"))
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, loads) }
    }

    @Test
    fun `sentinel lazy identity changes for every complete publication field`() {
        val base = sentinelKey(finalRowId = 31L)
        val variants = listOf(
            base,
            base.copy(decision = ActivityDecisionUi.Allowed),
            base.copy(search = "a|12:b"),
            base.copy(cutMaxRowId = base.cutMaxRowId + 1L),
            base.copy(cutCapturedAtMillis = base.cutCapturedAtMillis + 1L),
            base.copy(cutContinuationEpoch = base.cutContinuationEpoch + 1L),
            base.copy(sourceRevision = base.sourceRevision + 1L),
            base.copy(sourcePolicyRevision = base.sourcePolicyRevision + 1L),
            base.copy(finalRowId = base.finalRowId - 1L),
        )

        val identities = variants.map(::domainHistorySentinelLazyItemKey)

        assertEquals(variants.size, identities.toSet().size)
        assertFalse(
            domainHistorySentinelLazyItemKey(base.copy(search = "1:a|b")) ==
                domainHistorySentinelLazyItemKey(base.copy(search = "a|1:b")),
        )
    }

    @Test
    fun `real rows always copy natively while mutation actions require callbacks`() {
        setTopDomains(
            ActivityContentState.Ready(
                topSnapshot(rows = listOf(TopDomainRowUi("copy.example", 4L))),
            ),
        )

        compose.onNodeWithTag("activity_domain_action")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("activity_action_copy")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle {
            assertEquals("copy.example", clipboard.primaryClip?.getItemAt(0)?.coerceToText(ApplicationProvider.getApplicationContext()).toString())
        }

        compose.onNodeWithTag("activity_domain_action").performClick()
        compose.onNodeWithText("Block").assertDoesNotExist()
        compose.onNodeWithText("Allow").assertDoesNotExist()
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test
    fun `row overflow emits exact Block and Allow identities and never Remove`() {
        val requests = mutableListOf<Pair<String, DomainActionUi>>()
        setDomainHistory(
            initialState = ActivityContentState.Ready(
                historySnapshot(rows = listOf(historyRows.first())),
            ),
            rowActionCallbacks = DomainRowActionCallbacks { domain, action -> requests += domain to action },
        )

        compose.onNodeWithContentDescription("Actions for zeta.example").performClick()
        val copyTop = compose.onNodeWithTag("activity_action_copy").fetchSemanticsNode().boundsInRoot.top
        val blockTop = compose.onNodeWithTag("activity_action_block")
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot.top
        val allowTop = compose.onNodeWithTag("activity_action_allow")
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(copyTop < blockTop && blockTop < allowTop)
        compose.onNodeWithTag("activity_action_block").performClick()
        compose.onNodeWithContentDescription("Actions for zeta.example").performClick()
        compose.onNodeWithTag("activity_action_allow").performClick()
        compose.onNodeWithContentDescription("Actions for zeta.example").performClick()
        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    "zeta.example" to DomainActionUi.Block,
                    "zeta.example" to DomainActionUi.Allow,
                ),
                requests,
            )
        }
    }

    @Test
    fun `Top Domains remains reachable at compact RTL size and doubled font`() {
        setTopDomains(
            initialState = ActivityContentState.Ready(topSnapshot()),
            queryCallbacks = callbacks(),
            height = 240.dp,
            fontScale = 2f,
        )

        compose.onNodeWithText("Top Domains")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription("Back").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasText(retentionCopy))
        compose.onNodeWithText(retentionCopy).assertIsDisplayed()
    }

    @Test
    fun `Domain History remains reachable at compact RTL size and doubled font`() {
        setDomainHistory(
            initialState = ActivityContentState.Ready(historySnapshot()),
            queryCallbacks = callbacks(),
            height = 240.dp,
            fontScale = 2f,
        )

        compose.onNodeWithText("Domain History")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription("Back").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("activity_domain_list")
            .performScrollToNode(hasText(retentionCopy))
        compose.onNodeWithText(retentionCopy).assertIsDisplayed()
    }

    @Test
    fun `Presenting acknowledges visibility once and permits only exact pre-visible cancel`() {
        val visible = mutableListOf<Long>()
        val preVisibleCancels = mutableListOf<Long>()
        val confirms = mutableListOf<Long>()
        val cancels = mutableListOf<Long>()
        lateinit var update: (DomainActionUiState, DomainActionNoticeUi?) -> Unit
        setReview(
            initialState = DomainActionUiState.Presenting(review),
            reviewCallbacks = DomainReviewCallbacks(
                onReviewVisible = visible::add,
                onCancelBeforeVisible = preVisibleCancels::add,
                onConfirm = confirms::add,
                onCancel = cancels::add,
            ),
            captureUpdate = { update = it },
        )

        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(review.id), visible) }
        compose.runOnIdle {
            update(
                DomainActionUiState.Presenting(review),
                DomainActionNoticeUi(4L, DomainActionNoticeContentUi.Busy),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("activity_review_cancel").performClick()
        compose.runOnIdle {
            assertEquals(listOf(review.id), visible)
            assertEquals(listOf(review.id), preVisibleCancels)
            assertTrue(confirms.isEmpty())
            assertTrue(cancels.isEmpty())
        }
    }

    @Test
    fun `Handoff and Applying reviews are nondismissible and deduplicate confirmation`() {
        val confirms = mutableListOf<Long>()
        val cancels = mutableListOf<Long>()
        lateinit var update: (DomainActionUiState, DomainActionNoticeUi?) -> Unit
        val callbacks = DomainReviewCallbacks({}, {}, confirms::add, cancels::add)
        setReview(
            initialState = DomainActionUiState.Handoff(review),
            reviewCallbacks = callbacks,
            captureUpdate = { update = it },
        )

        compose.onNodeWithTag("activity_review_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("activity_review_cancel").assertIsNotEnabled()
        ShadowDialog.getLatestDialog().onBackPressed()
        val outside = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_OUTSIDE,
            0f,
            0f,
            0,
        )
        ShadowDialog.getLatestDialog().window?.decorView?.dispatchTouchEvent(outside)
        outside.recycle()
        compose.onNodeWithTag("activity_domain_review_dialog").assertExists()
        compose.runOnIdle { update(DomainActionUiState.Applying(review), null) }
        compose.onNodeWithTag("activity_review_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("activity_review_cancel").assertIsNotEnabled()
        ShadowDialog.getLatestDialog().onBackPressed()
        compose.onNodeWithTag("activity_domain_review_dialog").assertExists()
        compose.runOnIdle {
            assertTrue(confirms.isEmpty())
            assertTrue(cancels.isEmpty())
        }
    }

    @Test
    fun `pending and failed reviews confirm cancel and retry the exact immutable identity`() {
        val confirms = mutableListOf<Long>()
        val cancels = mutableListOf<Long>()
        lateinit var update: (DomainActionUiState, DomainActionNoticeUi?) -> Unit
        setReview(
            initialState = DomainActionUiState.ReviewPending(review),
            reviewCallbacks = DomainReviewCallbacks({}, {}, confirms::add, cancels::add),
            captureUpdate = { update = it },
        )

        compose.onNodeWithTag("activity_review_confirm").assertIsEnabled().performClick()
        compose.onNodeWithTag("activity_review_cancel").assertIsEnabled().performClick()
        compose.runOnIdle { update(DomainActionUiState.ApplyFailed(review, "still active"), null) }
        compose.onNodeWithText("still active").assertExists()
        compose.onNodeWithTag("activity_review_confirm").assertIsEnabled().performClick()
        compose.onNodeWithTag("activity_review_cancel").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(review.id, review.id), confirms)
            assertEquals(listOf(review.id, review.id), cancels)
        }
    }

    @Test
    fun `review exposes exact summary warning groups and accessible change values`() {
        val detailedReview = DomainActionReviewUi(
            id = 99L,
            domain = "trusted.example",
            action = DomainActionUi.Allow,
            changes = listOf(
                DomainActionChangeUi(DomainActionChangeGroupUi.BlockedDomains, DomainActionChangeKindUi.Removed, "trusted.example"),
                DomainActionChangeUi(DomainActionChangeGroupUi.AllowedExceptions, DomainActionChangeKindUi.Added, "trusted.example"),
            ),
        )
        setReview(
            initialState = DomainActionUiState.ReviewPending(detailedReview),
            reviewCallbacks = DomainReviewCallbacks({}, {}, {}, {}),
        )

        compose.onNodeWithText("Review").assertExists()
        compose.onNodeWithText("trusted.example").assertExists()
        compose.onNodeWithText("Allow").assertExists()
        compose.onNodeWithText("2 changes will be prepared and saved locally.").assertExists()
        compose.onNodeWithText("Be extra careful").assertExists()
        compose.onNodeWithText("Allowed exceptions let a site through even when a blocklist would catch it.").assertExists()
        compose.onNodeWithText("Blocked Domains").assertExists()
        compose.onNodeWithText("Allowed Exceptions").assertExists()
        val removedNode = compose.onNodeWithContentDescription("Removed")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "trusted.example"))
        val addedNode = compose.onNodeWithContentDescription("Added")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "trusted.example"))
        val orderedReviewRows = compose.onAllNodes(
            hasText("Blocked Domains") or hasContentDescription("Removed") or
                hasText("Allowed Exceptions") or hasContentDescription("Added"),
        ).fetchSemanticsNodes().map { node ->
            if (SemanticsProperties.ContentDescription in node.config) {
                node.config[SemanticsProperties.ContentDescription].single()
            } else {
                node.config[SemanticsProperties.Text].single().text
            }
        }
        assertEquals(
            listOf("Blocked Domains", "Removed", "Allowed Exceptions", "Added"),
            orderedReviewRows,
        )
        compose.onNodeWithText("Confirm Changes").assertExists()
        compose.onNodeWithContentDescription("Cancel").assertExists()
    }

    @Test
    fun `review body and actions remain reachable at compact RTL size and doubled font`() {
        val detailedReview = DomainActionReviewUi(
            id = 100L,
            domain = "trusted.example",
            action = DomainActionUi.Allow,
            changes = listOf(
                DomainActionChangeUi(
                    DomainActionChangeGroupUi.BlockedDomains,
                    DomainActionChangeKindUi.Removed,
                    "trusted.example",
                ),
                DomainActionChangeUi(
                    DomainActionChangeGroupUi.AllowedExceptions,
                    DomainActionChangeKindUi.Added,
                    "trusted.example",
                ),
            ),
        )
        setReview(
            initialState = DomainActionUiState.ReviewPending(detailedReview),
            reviewCallbacks = DomainReviewCallbacks({}, {}, {}, {}),
            width = 320.dp,
            height = 240.dp,
            fontScale = 2f,
        )

        compose.onNodeWithText("Allowed exceptions let a site through even when a blocklist would catch it.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("activity_review_confirm")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("activity_review_cancel")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `single review surface keeps a concurrent exact notice inline`() {
        val dismissed = mutableListOf<Long>()
        setReview(
            initialState = DomainActionUiState.ReviewPending(review),
            initialNotice = DomainActionNoticeUi(82L, DomainActionNoticeContentUi.Superseded),
            reviewCallbacks = DomainReviewCallbacks({}, {}, {}, {}),
            noticeCallbacks = DomainNoticeCallbacks(dismissed::add),
        )

        compose.onAllNodesWithTag("activity_domain_review_dialog").assertCountEquals(1)
        compose.onNodeWithTag("activity_domain_notice_dialog").assertDoesNotExist()
        compose.onNodeWithTag("activity_domain_notice").assertExists()
        compose.onNodeWithText("The review changed before it could be applied.").assertExists()
        compose.onNodeWithTag("activity_notice_dismiss").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(82L), dismissed) }
    }

    @Test
    fun `review state is hidden when no real review callbacks exist`() {
        setReview(initialState = DomainActionUiState.ReviewPending(review))

        compose.onNodeWithTag("activity_domain_review_dialog").assertDoesNotExist()
        compose.onNodeWithText("Confirm Changes").assertDoesNotExist()
    }

    @Test
    fun `every typed notice remains distinguishable dismissible and never claims success`() {
        val notices = listOf(
            NoticeExpectation(DomainActionNoticeContentUi.UnsavedFilterEdits, "Unsaved filter edits", "You have unsaved changes to your active filter. Save or discard them in Filters before changing domains here."),
            NoticeExpectation(DomainActionNoticeContentUi.ValidationFailed(DomainActionValidationUi.InvalidDomain), "Invalid domain", "Enter a valid domain."),
            NoticeExpectation(DomainActionNoticeContentUi.ValidationFailed(DomainActionValidationUi.AlreadyAllowed), "Already allowed", "This domain is already an allowed exception."),
            NoticeExpectation(DomainActionNoticeContentUi.ValidationFailed(DomainActionValidationUi.AlreadyBlocked), "Already blocked", "This domain is already blocked."),
            NoticeExpectation(DomainActionNoticeContentUi.ValidationFailed(DomainActionValidationUi.AllowedLimitReached), "Allowed limit reached", "Remove an allowed exception before adding another."),
            NoticeExpectation(DomainActionNoticeContentUi.ValidationFailed(DomainActionValidationUi.BlockedLimitReached), "Blocked limit reached", "Remove a blocked domain before adding another."),
            NoticeExpectation(DomainActionNoticeContentUi.ValidationFailed(DomainActionValidationUi.RuleNotPresent), "Rule not present", "This domain rule is no longer present."),
            NoticeExpectation(DomainActionNoticeContentUi.ValidationFailed(DomainActionValidationUi.RuleRejected), "Domain change rejected", "This domain rule could not be changed."),
            NoticeExpectation(DomainActionNoticeContentUi.AuthenticationFailed("auth detail"), "Authentication failed", "auth detail"),
            NoticeExpectation(DomainActionNoticeContentUi.ReviewRejected("review detail"), "Review could not be opened", "review detail"),
            NoticeExpectation(DomainActionNoticeContentUi.ApplyRejected("apply detail"), "Changes were not applied", "apply detail"),
            NoticeExpectation(DomainActionNoticeContentUi.Failed("failure detail"), "Domain change failed", "failure detail"),
            NoticeExpectation(DomainActionNoticeContentUi.Busy, "Another change is in progress", "Wait for the current domain change to finish."),
            NoticeExpectation(DomainActionNoticeContentUi.ReviewUnavailable, "Review unavailable", "The pending review is no longer available."),
            NoticeExpectation(DomainActionNoticeContentUi.Superseded, "Review changed", "The review changed before it could be applied."),
        )
        val dismissed = mutableListOf<Long>()
        lateinit var update: (DomainActionUiState, DomainActionNoticeUi?) -> Unit
        setReview(
            initialState = DomainActionUiState.Idle,
            initialNotice = DomainActionNoticeUi(1L, notices.first().content),
            noticeCallbacks = DomainNoticeCallbacks(dismissed::add),
            captureUpdate = { update = it },
        )

        notices.forEachIndexed { index, expectation ->
            val id = index.toLong() + 1L
            compose.runOnIdle {
                update(DomainActionUiState.Idle, DomainActionNoticeUi(id, expectation.content))
            }
            compose.onNodeWithText(expectation.title).assertExists()
            compose.onNodeWithText(expectation.body).assertExists()
            compose.onNodeWithText("Success").assertDoesNotExist()
            compose.onNodeWithText("Applied").assertDoesNotExist()
            compose.onNodeWithTag("activity_notice_dismiss").performClick()
        }
        compose.runOnIdle { assertEquals((1L..notices.size.toLong()).toList(), dismissed) }
    }

    @Test
    fun `notice cannot imply dismiss capability without an exact callback`() {
        setReview(
            initialState = DomainActionUiState.Idle,
            initialNotice = DomainActionNoticeUi(5L, DomainActionNoticeContentUi.Busy),
        )

        compose.onNodeWithText("Another change is in progress").assertExists()
        compose.onNodeWithTag("activity_notice_dismiss").assertDoesNotExist()
    }

    private fun setTopDomains(
        initialState: ActivityContentState<TopDomainsSnapshot>,
        queryCallbacks: ActivityQueryCallbacks? = null,
        rowActionCallbacks: DomainRowActionCallbacks? = null,
        width: Dp = 320.dp,
        height: Dp = 800.dp,
        fontScale: Float = 1f,
        captureState: (((ActivityContentState<TopDomainsSnapshot>) -> Unit) -> Unit)? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            var state by remember { mutableStateOf(initialState) }
            var queryState by remember { mutableStateOf(topQuery(initialState)) }
            SideEffect { captureState?.invoke { nextState ->
                state = nextState
                topQuery(nextState)?.let { queryState = it }
            } }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                LavaTheme {
                    Box(Modifier.requiredWidth(width).requiredHeight(height)) {
                        TopDomainsScreen(
                            state = state,
                            onBack = {},
                            queryState = queryState,
                            queryCallbacks = queryCallbacks,
                            rowActionCallbacks = rowActionCallbacks,
                        )
                    }
                }
            }
        }
    }

    private fun setDomainHistory(
        initialState: ActivityContentState<DomainHistorySnapshot>,
        retainedDomainLogs: ActivityContentState<Boolean> = ActivityContentState.Ready(true),
        initialLoadingMore: Boolean = false,
        queryCallbacks: ActivityQueryCallbacks? = null,
        rowActionCallbacks: DomainRowActionCallbacks? = null,
        width: Dp = 320.dp,
        height: Dp = 800.dp,
        fontScale: Float = 1f,
        captureUpdate: (((
            ActivityContentState<DomainHistorySnapshot>,
            ActivityContentState<Boolean>,
            Boolean,
        ) -> Unit) -> Unit)? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            var state by remember { mutableStateOf(initialState) }
            var retainedLogs by remember { mutableStateOf(retainedDomainLogs) }
            var loadingMore by remember { mutableStateOf(initialLoadingMore) }
            var queryState by remember { mutableStateOf(historyQuery(initialState)) }
            SideEffect { captureUpdate?.invoke { nextState, nextRetainedLogs, nextLoading ->
                state = nextState
                retainedLogs = nextRetainedLogs
                loadingMore = nextLoading
                historyQuery(nextState)?.let { queryState = it }
            } }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                LavaTheme {
                    Box(Modifier.requiredWidth(width).requiredHeight(height)) {
                        DomainHistoryScreen(
                            state = state,
                            retainedDomainLogs = retainedLogs,
                            loadingMore = loadingMore,
                            onBack = {},
                            queryState = queryState,
                            queryCallbacks = queryCallbacks,
                            rowActionCallbacks = rowActionCallbacks,
                        )
                    }
                }
            }
        }
    }

    private fun setReview(
        initialState: DomainActionUiState,
        initialNotice: DomainActionNoticeUi? = null,
        reviewCallbacks: DomainReviewCallbacks? = null,
        noticeCallbacks: DomainNoticeCallbacks? = null,
        width: Dp = 320.dp,
        height: Dp = 800.dp,
        fontScale: Float = 1f,
        captureUpdate: (((DomainActionUiState, DomainActionNoticeUi?) -> Unit) -> Unit)? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            var state by remember { mutableStateOf(initialState) }
            var notice by remember { mutableStateOf(initialNotice) }
            SideEffect { captureUpdate?.invoke { nextState, nextNotice ->
                state = nextState
                notice = nextNotice
            } }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                LavaTheme {
                    Box(Modifier.requiredWidth(width).requiredHeight(height)) {
                        DomainActionReviewDialog(
                            state = state,
                            notice = notice,
                            reviewCallbacks = reviewCallbacks,
                            noticeCallbacks = noticeCallbacks,
                        )
                    }
                }
            }
        }
    }

    private fun callbacks(
        onTopDecision: (ActivityDecisionUi) -> Unit = {},
        onTopSearch: (String) -> Unit = {},
        onHistoryRefresh: () -> Unit = {},
        onHistoryDecision: (ActivityDecisionUi) -> Unit = {},
        onHistorySearch: (String) -> Unit = {},
        onLoadMore: () -> Unit = {},
    ) = ActivityQueryCallbacks(
        onRefreshSummary = {},
        onDateRangeSelected = { _, _ -> },
        onTopDomainDecisionSelected = onTopDecision,
        onTopDomainSearchChanged = onTopSearch,
        onRefreshHistory = onHistoryRefresh,
        onHistoryDecisionSelected = onHistoryDecision,
        onHistorySearchChanged = onHistorySearch,
        onLoadMoreHistory = onLoadMore,
        onRefreshNetworkActivity = {},
        onLoadMoreNetworkActivity = {},
    )

    private fun topSnapshot(
        decision: ActivityDecisionUi = ActivityDecisionUi.Blocked,
        search: String = "",
        availability: ActivityLogAvailabilityUi = ActivityLogAvailabilityUi.Retained,
        rows: List<TopDomainRowUi> = listOf(
            TopDomainRowUi("zeta.example", 7),
            TopDomainRowUi("alpha.example", 3),
        ),
    ) = TopDomainsSnapshot(dateRange, decision, search, availability, rows)

    private fun topQuery(
        state: ActivityContentState<TopDomainsSnapshot>,
    ): ActivityQueryControlUi? = when (state) {
        is ActivityContentState.Ready -> state.value
        is ActivityContentState.Loading -> state.lastGood
        is ActivityContentState.Failed -> state.lastGood
        ActivityContentState.Unavailable,
        ActivityContentState.Disabled,
        -> null
    }?.let { snapshot ->
        val current = ActivityDomainQueryUi(snapshot.decision, snapshot.search)
        ActivityQueryControlUi(current = current, draft = current)
    }

    private fun historySnapshot(
        decision: ActivityDecisionUi = ActivityDecisionUi.Blocked,
        search: String = "",
        rows: List<DomainHistoryRowUi> = historyRows,
        nextPageKey: DomainHistorySentinelKeyUi? = null,
    ) = DomainHistorySnapshot(decision, search, rows, nextPageKey)

    private fun historyQuery(
        state: ActivityContentState<DomainHistorySnapshot>,
    ): ActivityQueryControlUi? = when (state) {
        is ActivityContentState.Ready -> state.value
        is ActivityContentState.Loading -> state.lastGood
        is ActivityContentState.Failed -> state.lastGood
        ActivityContentState.Unavailable,
        ActivityContentState.Disabled,
        -> null
    }?.let { snapshot ->
        val current = ActivityDomainQueryUi(snapshot.decision, snapshot.search)
        ActivityQueryControlUi(current = current, draft = current)
    }

    private fun historyRow(
        id: Long,
        domain: String,
        decision: ActivityDecisionUi = ActivityDecisionUi.Blocked,
    ) = DomainHistoryRowUi(
        id = id,
        timestampMillis = localTimestamp,
        domain = domain,
        decision = decision,
        reason = if (decision == ActivityDecisionUi.Allowed) {
            DomainHistoryReasonUi.LocalAllowlist
        } else {
            DomainHistoryReasonUi.Blocklist
        },
    )

    private fun sentinelKey(
        decision: ActivityDecisionUi = ActivityDecisionUi.Blocked,
        search: String = "",
        finalRowId: Long,
    ) = DomainHistorySentinelKeyUi(
        decision = decision,
        search = search,
        cutMaxRowId = 60L,
        cutCapturedAtMillis = 1_754_031_000_000L,
        cutContinuationEpoch = 3L,
        sourceRevision = 7L,
        sourcePolicyRevision = 9L,
        finalRowId = finalRowId,
    )

    private data class NoticeExpectation(
        val content: DomainActionNoticeContentUi,
        val title: String,
        val body: String,
    )

    private companion object {
        val today: LocalDate = LocalDate.of(2026, 8, 1)
        val dateRange = ActivityDateRangeUi(today, today, today, "Asia/Tokyo")
        val localTimestamp: Long = Instant.parse("2026-08-01T03:04:00Z").toEpochMilli()
        const val retentionCopy = "Kept on this device for 7 days, and only leaves the device if you export it or attach it to a bug report."
        const val domainClearScopeCopy = "Clearing local Activity removes Domain History together with local counts and uptime."
        val historyRows = listOf(
            DomainHistoryRowUi(42L, 1_754_031_000_000L, "zeta.example", ActivityDecisionUi.Blocked, DomainHistoryReasonUi.Blocklist),
            DomainHistoryRowUi(41L, 1_754_030_000_000L, "alpha.example", ActivityDecisionUi.Allowed, DomainHistoryReasonUi.LocalAllowlist),
        )
        val review = DomainActionReviewUi(
            id = 71L,
            domain = "example.com",
            action = DomainActionUi.Allow,
            changes = listOf(
                DomainActionChangeUi(DomainActionChangeGroupUi.AllowedExceptions, DomainActionChangeKindUi.Added, "example.com"),
            ),
        )
    }
}
