package com.lavasec.app.product

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.filter.ManualRuleRejection
import com.lavasec.app.presentation.activity.ActivityContentState
import com.lavasec.app.presentation.activity.ActivityCountSnapshot
import com.lavasec.app.presentation.activity.ActivityDecisionUi
import com.lavasec.app.presentation.activity.ActivityLogAvailabilityUi
import com.lavasec.app.presentation.activity.ActivityPercentageUi
import com.lavasec.app.presentation.activity.ActivityDomainQueryUi
import com.lavasec.app.presentation.activity.ActivityQueryControlUi
import com.lavasec.app.presentation.activity.ActivityUiState
import com.lavasec.app.presentation.activity.DomainActionChangeGroupUi
import com.lavasec.app.presentation.activity.DomainActionChangeKindUi
import com.lavasec.app.presentation.activity.DomainActionNoticeContentUi
import com.lavasec.app.presentation.activity.DomainActionUi
import com.lavasec.app.presentation.activity.DomainActionUiState
import com.lavasec.app.presentation.activity.DomainActionValidationUi
import com.lavasec.app.presentation.activity.DomainHistoryReasonUi
import com.lavasec.app.product.activity.ActivityDateLens
import com.lavasec.app.product.activity.ActivityDigest
import com.lavasec.app.product.activity.ActivitySummary
import com.lavasec.app.product.activity.ActivitySummaryQuery
import com.lavasec.app.product.activity.DomainDecision
import com.lavasec.app.product.activity.DomainHistoryAvailability
import com.lavasec.app.product.activity.DomainHistoryQuery
import com.lavasec.app.product.activity.DomainHistoryRow
import com.lavasec.app.product.activity.ProductActivityState
import com.lavasec.app.product.activity.ProductLoadState
import com.lavasec.app.product.activity.ProductPagedRows
import com.lavasec.app.product.activity.TopDomainRow
import com.lavasec.app.product.domain.DomainAction
import com.lavasec.app.product.domain.DomainActionConfirmResult
import com.lavasec.app.product.domain.DomainActionRejection
import com.lavasec.app.product.domain.DomainActionRequestResult
import com.lavasec.app.product.domain.DomainActionReview
import com.lavasec.app.product.domain.DomainActionState
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductActivityUiMapperTest {
    @Test
    fun `unavailable contains no visible evidence or action`() {
        assertEquals(ActivityContentState.Unavailable, ActivityUiState.unavailable.overview)
        assertEquals(ActivityContentState.Unavailable, ActivityUiState.unavailable.topDomains)
        assertEquals(ActivityContentState.Unavailable, ActivityUiState.unavailable.domainHistory)
        assertEquals(ActivityContentState.Unavailable, ActivityUiState.unavailable.retainedDomainLogs)
        assertFalse(ActivityUiState.unavailable.historyLoadingMore)
        assertEquals(DomainActionUiState.Idle, ActivityUiState.unavailable.domainAction)
        assertNull(ActivityUiState.unavailable.domainActionNotice)
    }

    @Test
    fun `network-only product changes cannot alter any Activity UI value`() {
        val base = ProductActivityState(summaryQuery = query())
        // Internal network bookkeeping never leaks into the Activity UI; the paging flag
        // maps to the Network Activity surface below Settings → Advanced instead.
        assertEquals(
            ProductActivityUiMapper.map(base),
            ProductActivityUiMapper.map(base.copy(networkRefreshing = true, networkContentGeneration = 9L)),
        )
    }

    @Test
    fun `ready maps every snapshot field from its payload in order`() {
        val summary = summary(query = query(day = LocalDate.of(2026, 3, 8), decision = DomainDecision.ALLOWED, search = "payload"))
        val page = history(query = DomainHistoryQuery(DomainDecision.ALLOWED, "history payload"), next = true)
        val ui = ProductActivityUiMapper.map(
            ProductActivityState(
                summaryQuery = query(day = LocalDate.of(2026, 8, 1), decision = DomainDecision.BLOCKED, search = "top level"),
                historyQuery = DomainHistoryQuery(DomainDecision.BLOCKED, "top level history"),
                summary = ProductLoadState.Ready(summary),
                history = ProductLoadState.Ready(page),
                retainedDomainLogs = ProductLoadState.Ready(true),
                historyLoadingMore = true,
            ),
        )

        val overview = (ui.overview as ActivityContentState.Ready).value
        assertEquals(summary.dateLens.today.minusDays(1), overview.dateRange.start)
        assertEquals(summary.dateLens.today, overview.dateRange.endInclusive)
        assertEquals(summary.dateLens.today, overview.dateRange.today)
        assertEquals("America/New_York", overview.dateRange.zoneId)
        assertEquals(ActivityCountSnapshot(7L, 3L), overview.counts)
        assertEquals(ActivityPercentageUi.Rounded(70), overview.counts!!.allowedShare)
        assertEquals(ActivityPercentageUi.Rounded(30), overview.counts!!.blockedShare)
        assertEquals(BigInteger.TEN, overview.counts!!.total)
        assertEquals(99L, overview.localProtectionUptimeMillis)
        assertEquals(ActivityLogAvailabilityUi.Retained, overview.retainedDomainLogs)

        val top = (ui.topDomains as ActivityContentState.Ready).value
        assertEquals(summary.dateLens.today.minusDays(1), top.dateRange.start)
        assertEquals(summary.dateLens.today, top.dateRange.endInclusive)
        assertEquals(summary.dateLens.today, top.dateRange.today)
        assertEquals("America/New_York", top.dateRange.zoneId)
        assertEquals(ActivityDecisionUi.Allowed, top.decision)
        assertEquals("payload", top.search)
        assertEquals(ActivityLogAvailabilityUi.Retained, top.availability)
        assertEquals(listOf("z.example", "a.example"), top.rows.map { it.domain })
        assertEquals(listOf(8L, 2L), top.rows.map { it.count })

        val rows = (ui.domainHistory as ActivityContentState.Ready).value
        assertEquals(ActivityDecisionUi.Allowed, rows.decision)
        assertEquals("history payload", rows.search)
        assertEquals(listOf(30L, 20L, 10L), rows.rows.map { it.id })
        assertEquals(listOf(300L, 200L, 100L), rows.rows.map { it.timestampMillis })
        assertEquals(listOf("one.example", "two.example", "three.example"), rows.rows.map { it.domain })
        assertEquals(listOf(ActivityDecisionUi.Allowed, ActivityDecisionUi.Allowed, ActivityDecisionUi.Allowed), rows.rows.map { it.decision })
        assertEquals(
            listOf(DomainHistoryReasonUi.DefaultAllow, DomainHistoryReasonUi.PausedAllow, DomainHistoryReasonUi.LocalAllowlist),
            rows.rows.map { it.reason },
        )
        assertEquals(30L, rows.nextPageKey?.cutMaxRowId)
        assertEquals(700L, rows.nextPageKey?.cutCapturedAtMillis)
        assertEquals(4L, rows.nextPageKey?.cutContinuationEpoch)
        assertEquals(21L, rows.nextPageKey?.sourceRevision)
        assertEquals(22L, rows.nextPageKey?.sourcePolicyRevision)
        assertEquals(10L, rows.nextPageKey?.finalRowId)
        assertEquals(ActivityContentState.Ready(true), ui.retainedDomainLogs)
        assertEquals(true, ui.historyLoadingMore)
    }

    @Test
    fun `loading and failed retain exact payload provenance rather than current queries`() {
        val staleSummary = summary(query = query(day = LocalDate.of(2026, 3, 8), decision = DomainDecision.ALLOWED, search = "last good"))
        val staleHistory = history(query = DomainHistoryQuery(DomainDecision.ALLOWED, "last history"), next = false)
        val state = ProductActivityState(
            summaryQuery = query(day = LocalDate.of(2026, 8, 1), decision = DomainDecision.BLOCKED, search = "current"),
            historyQuery = DomainHistoryQuery(DomainDecision.BLOCKED, "current history"),
            summary = ProductLoadState.Loading(staleSummary),
            history = ProductLoadState.Loading(staleHistory),
            retainedDomainLogs = ProductLoadState.Loading(false),
        )
        val loading = ProductActivityUiMapper.map(state)
        val expectedLoading = ProductActivityUiMapper.map(state.copy(
            summary = ProductLoadState.Ready(staleSummary),
            history = ProductLoadState.Ready(staleHistory),
            retainedDomainLogs = ProductLoadState.Ready(false),
        ))
        assertEquals(
            (expectedLoading.overview as ActivityContentState.Ready).value,
            (loading.overview as ActivityContentState.Loading).lastGood,
        )
        assertEquals(
            (expectedLoading.topDomains as ActivityContentState.Ready).value,
            (loading.topDomains as ActivityContentState.Loading).lastGood,
        )
        assertEquals(
            (expectedLoading.domainHistory as ActivityContentState.Ready).value,
            (loading.domainHistory as ActivityContentState.Loading).lastGood,
        )
        assertEquals(ActivityContentState.Loading(false), loading.retainedDomainLogs)
        val top = (loading.topDomains as ActivityContentState.Loading).lastGood!!
        assertEquals(ActivityDecisionUi.Allowed, top.decision)
        assertEquals("last good", top.search)
        val history = (loading.domainHistory as ActivityContentState.Loading).lastGood!!
        assertEquals(ActivityDecisionUi.Allowed, history.decision)
        assertEquals("last history", history.search)

        val failed = ProductActivityUiMapper.map(state.copy(
            summary = ProductLoadState.Failed("", staleSummary),
            history = ProductLoadState.Failed(" history unavailable ", staleHistory),
            retainedDomainLogs = ProductLoadState.Failed("", true),
        ))
        assertEquals("Activity data unavailable", (failed.overview as ActivityContentState.Failed).message)
        assertEquals(" history unavailable ", (failed.domainHistory as ActivityContentState.Failed).message)
        assertEquals("last good", ((failed.topDomains as ActivityContentState.Failed).lastGood!!).search)
        assertEquals("last history", ((failed.domainHistory as ActivityContentState.Failed).lastGood!!).search)
        val expectedFailed = ProductActivityUiMapper.map(state.copy(
            summary = ProductLoadState.Ready(staleSummary),
            history = ProductLoadState.Ready(staleHistory),
            retainedDomainLogs = ProductLoadState.Ready(true),
        ))
        assertEquals(
            (expectedFailed.overview as ActivityContentState.Ready).value,
            (failed.overview as ActivityContentState.Failed).lastGood,
        )
        assertEquals(
            (expectedFailed.topDomains as ActivityContentState.Ready).value,
            (failed.topDomains as ActivityContentState.Failed).lastGood,
        )
        assertEquals(
            (expectedFailed.domainHistory as ActivityContentState.Ready).value,
            (failed.domainHistory as ActivityContentState.Failed).lastGood,
        )
        assertEquals(ActivityContentState.Failed("Activity data unavailable", true), failed.retainedDomainLogs)
    }

    @Test
    fun `current query controls stay separate from last good evidence provenance`() {
        val lastSummary = summary(
            query = query(
                day = LocalDate.of(2026, 3, 8),
                decision = DomainDecision.ALLOWED,
                search = "last top evidence",
            ),
        )
        val lastHistory = history(
            query = DomainHistoryQuery(DomainDecision.ALLOWED, "last history evidence"),
            next = false,
        )

        val ui = ProductActivityUiMapper.map(
            ProductActivityState(
                summaryQuery = query(
                    day = LocalDate.of(2026, 8, 1),
                    decision = DomainDecision.BLOCKED,
                    search = "current top query",
                ),
                historyQuery = DomainHistoryQuery(
                    DomainDecision.BLOCKED,
                    "current history query",
                ),
                summary = ProductLoadState.Loading(lastGood = lastSummary),
                history = ProductLoadState.Loading(lastGood = lastHistory),
            ),
        )

        assertEquals(
            ActivityQueryControlUi(
                current = ActivityDomainQueryUi(
                    ActivityDecisionUi.Blocked,
                    "current top query",
                ),
                draft = ActivityDomainQueryUi(
                    ActivityDecisionUi.Blocked,
                    "current top query",
                ),
            ),
            ui.topDomainsQuery,
        )
        assertEquals(
            ActivityQueryControlUi(
                current = ActivityDomainQueryUi(
                    ActivityDecisionUi.Blocked,
                    "current history query",
                ),
                draft = ActivityDomainQueryUi(
                    ActivityDecisionUi.Blocked,
                    "current history query",
                ),
            ),
            ui.domainHistoryQuery,
        )
        assertEquals(
            "last top evidence",
            (ui.topDomains as ActivityContentState.Loading).lastGood!!.search,
        )
        assertEquals(
            "last history evidence",
            (ui.domainHistory as ActivityContentState.Loading).lastGood!!.search,
        )
    }

    @Test
    fun `failed without last good exposes only typed fallback failures`() {
        val ui = ProductActivityUiMapper.map(ProductActivityState(
            summaryQuery = query(),
            summary = ProductLoadState.Failed("", null),
            history = ProductLoadState.Failed("history broken", null),
            retainedDomainLogs = ProductLoadState.Failed("", null),
        ))
        assertEquals(ActivityContentState.Failed("Activity data unavailable", null), ui.overview)
        assertEquals(ActivityContentState.Failed("Activity data unavailable", null), ui.topDomains)
        assertEquals(ActivityContentState.Failed("history broken", null), ui.domainHistory)
        assertEquals(ActivityContentState.Failed("Activity data unavailable", null), ui.retainedDomainLogs)
    }

    @Test
    fun `disabled maps disabled surfaces and removes counts and uptime`() {
        val disabledSummary = summary(
            countsAvailable = false,
            availability = DomainHistoryAvailability.DISABLED,
            topRows = emptyList(),
        )
        val ui = ProductActivityUiMapper.map(ProductActivityState(
            summaryQuery = query(),
            summary = ProductLoadState.Ready(disabledSummary),
            history = ProductLoadState.Disabled,
            retainedDomainLogs = ProductLoadState.Disabled,
        ))
        val overview = (ui.overview as ActivityContentState.Ready).value
        assertNull(overview.counts)
        assertNull(overview.localProtectionUptimeMillis)
        assertEquals(ActivityLogAvailabilityUi.Disabled, overview.retainedDomainLogs)
        assertEquals(ActivityContentState.Disabled, ui.domainHistory)
        assertEquals(ActivityContentState.Disabled, ui.retainedDomainLogs)
    }

    @Test
    fun `retained logs and all seven history reasons map exactly`() {
        val reasons = FilterDecisionReason.entries.mapIndexed { index, reason ->
            DomainHistoryRow(index + 1L, 100L + index, "row$index.example", if (index < 3) DomainDecision.ALLOWED else DomainDecision.BLOCKED, reason)
        }
        val page = ProductPagedRows.domainHistory(
            rows = reasons,
            next = null,
            cut = StableReadCut(7L, 700L),
            sourceRevision = 1L,
            sourcePolicyRevision = 2L,
            historyQuery = DomainHistoryQuery(DomainDecision.ALLOWED, ""),
        )
        val ui = ProductActivityUiMapper.map(ProductActivityState(
            summaryQuery = query(),
            summary = ProductLoadState.Ready(summary(availability = DomainHistoryAvailability.EMPTY, topRows = emptyList())),
            history = ProductLoadState.Ready(page),
            retainedDomainLogs = ProductLoadState.Ready(false),
        ))
        assertEquals(ActivityLogAvailabilityUi.Empty, ((ui.overview as ActivityContentState.Ready).value).retainedDomainLogs)
        assertEquals(ActivityContentState.Ready(false), ui.retainedDomainLogs)
        assertEquals(DomainHistoryReasonUi.entries, ((ui.domainHistory as ActivityContentState.Ready).value).rows.map { it.reason })
        assertNull(((ui.domainHistory as ActivityContentState.Ready).value).nextPageKey)
        assertEquals(ActivityContentState.Ready(true), ProductActivityUiMapper.map(ProductActivityState(query(), retainedDomainLogs = ProductLoadState.Ready(true))).retainedDomainLogs)
    }

    @Test
    fun `mapped top-domain and history lists are defensive immutable snapshots`() {
        val topRows = mutableListOf(TopDomainRow("first.example", 4L))
        val historyRows = mutableListOf(DomainHistoryRow(2L, 2L, "history.example", DomainDecision.BLOCKED, FilterDecisionReason.BLOCKLIST))
        val page = ProductPagedRows.domainHistory(
            rows = historyRows,
            next = null,
            cut = StableReadCut(2L, 2L),
            sourceRevision = 1L,
            sourcePolicyRevision = 1L,
            historyQuery = DomainHistoryQuery(),
        )
        val ui = ProductActivityUiMapper.map(ProductActivityState(
            summaryQuery = query(),
            summary = ProductLoadState.Ready(summary(topRows = topRows)),
            history = ProductLoadState.Ready(page),
        ))
        topRows += TopDomainRow("late.example", 2L)
        historyRows += DomainHistoryRow(1L, 1L, "late.example", DomainDecision.BLOCKED, FilterDecisionReason.BLOCKLIST)
        val top = (ui.topDomains as ActivityContentState.Ready).value
        val history = (ui.domainHistory as ActivityContentState.Ready).value
        assertEquals(listOf("first.example"), top.rows.map { it.domain })
        assertEquals(listOf("history.example"), history.rows.map { it.domain })
        assertThrows(UnsupportedOperationException::class.java) { (top.rows as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (history.rows as MutableList).clear() }
    }

    @Test
    fun `count percentage bands are complete exact and never overflow`() {
        val saturated = ActivityCountSnapshot(Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), saturated.total)
        assertEquals(ActivityPercentageUi.Rounded(50), saturated.allowedShare)
        assertEquals(ActivityPercentageUi.Zero, ActivityCountSnapshot(0L, 3L).allowedShare)
        assertEquals(ActivityPercentageUi.OneHundred, ActivityCountSnapshot(3L, 0L).allowedShare)
        assertEquals(ActivityPercentageUi.LessThanOne, ActivityCountSnapshot(1L, 200L).allowedShare)
        assertEquals(ActivityPercentageUi.Rounded(1), ActivityCountSnapshot(1L, 99L).allowedShare)
        assertEquals(ActivityPercentageUi.Rounded(50), ActivityCountSnapshot(1L, 1L).allowedShare)
        assertEquals(ActivityPercentageUi.GreaterThan99, ActivityCountSnapshot(200L, 1L).allowedShare)
        assertEquals(ActivityPercentageUi.GreaterThan99, ActivityCountSnapshot(Long.MAX_VALUE, 1L).allowedShare)
        assertEquals(ActivityPercentageUi.LessThanOne, ActivityCountSnapshot(Long.MAX_VALUE, 1L).blockedShare)
        assertEquals(ActivityPercentageUi.LessThanOne, ActivityCountSnapshot(1L, Long.MAX_VALUE).allowedShare)
        assertEquals(ActivityPercentageUi.GreaterThan99, ActivityCountSnapshot(1L, Long.MAX_VALUE).blockedShare)
    }

    @Test
    fun `action states map presentation coordinator and immutable review differences`() {
        val review = review(9L, DomainAction.ALLOW)
        val awaiting = ProductActivityUiMapper.map(ProductActivityState(query()), nativeReviewState = NativeDomainActionReviewState.AwaitingVisibility(review))
        val handoff = ProductActivityUiMapper.map(ProductActivityState(query()), nativeReviewState = NativeDomainActionReviewState.Handoff(review))
        val pending = ProductActivityUiMapper.map(ProductActivityState(query()), NativeDomainActionHostState(DomainActionState.ReviewPending(review), null), NativeDomainActionReviewState.Handoff(review))
        val applying = ProductActivityUiMapper.map(ProductActivityState(query()), NativeDomainActionHostState(DomainActionState.Applying(review), null))
        val failed = ProductActivityUiMapper.map(ProductActivityState(query()), NativeDomainActionHostState(DomainActionState.ApplyFailed(review, "nope"), null))
        val presentation = (awaiting.domainAction as DomainActionUiState.Presenting).review
        assertEquals(DomainActionUi.Allow, presentation.action)
        assertEquals(4, presentation.changeCount)
        assertEquals(true, presentation.requiresAllowWarning)
        assertEquals(
            listOf(
                Triple(DomainActionChangeGroupUi.BlockedDomains, DomainActionChangeKindUi.Added, "new-block.example"),
                Triple(DomainActionChangeGroupUi.BlockedDomains, DomainActionChangeKindUi.Removed, "old-block.example"),
                Triple(DomainActionChangeGroupUi.AllowedExceptions, DomainActionChangeKindUi.Added, "new-allow.example"),
                Triple(DomainActionChangeGroupUi.AllowedExceptions, DomainActionChangeKindUi.Removed, "old-allow.example"),
            ),
            presentation.changes.map { Triple(it.group, it.kind, it.domain) },
        )
        assertEquals(9L, (handoff.domainAction as DomainActionUiState.Handoff).review.id)
        assertEquals(9L, (pending.domainAction as DomainActionUiState.ReviewPending).review.id)
        assertEquals(9L, (applying.domainAction as DomainActionUiState.Applying).review.id)
        assertEquals("nope", (failed.domainAction as DomainActionUiState.ApplyFailed).message)
        assertThrows(UnsupportedOperationException::class.java) { (presentation.changes as MutableList).clear() }
    }

    @Test
    fun `request and confirm notices cover every constructible result tuple`() {
        assertNull(ProductActivityUiMapper.requestNotice(DomainActionRequestResult.Staged(review())))
        assertNull(ProductActivityUiMapper.requestNotice(DomainActionRequestResult.ReviewAlreadyPending(review())))
        assertNull(ProductActivityUiMapper.requestNotice(DomainActionRequestResult.AuthenticationCancelled))
        assertNull(ProductActivityUiMapper.requestNotice(DomainActionRequestResult.ReviewCancelled))
        assertEquals(DomainActionNoticeContentUi.Busy, ProductActivityUiMapper.requestNotice(DomainActionRequestResult.Busy))
        assertEquals(DomainActionNoticeContentUi.AuthenticationFailed("auth"), ProductActivityUiMapper.requestNotice(DomainActionRequestResult.AuthenticationFailed("auth")))
        assertEquals(DomainActionNoticeContentUi.ReviewRejected("review"), ProductActivityUiMapper.requestNotice(DomainActionRequestResult.ReviewRejected("review")))
        assertEquals(DomainActionNoticeContentUi.Failed("failed"), ProductActivityUiMapper.requestNotice(DomainActionRequestResult.Failed("failed")))
        val allManualRejections = listOf<ManualRuleRejection?>(null) + ManualRuleRejection.entries
        allManualRejections.forEach { ignored ->
            assertEquals(
                DomainActionNoticeContentUi.UnsavedFilterEdits,
                ProductActivityUiMapper.requestNotice(DomainActionRequestResult.Rejected(DomainActionRejection.UNSAVED_ACTIVE_FILTER_DRAFT, ignored)),
            )
            assertEquals(
                DomainActionNoticeContentUi.ValidationFailed(DomainActionValidationUi.RuleNotPresent),
                ProductActivityUiMapper.requestNotice(DomainActionRequestResult.Rejected(DomainActionRejection.RULE_NOT_PRESENT, ignored)),
            )
        }
        val expected = mapOf(
            ManualRuleRejection.INVALID_DOMAIN to DomainActionValidationUi.InvalidDomain,
            ManualRuleRejection.ALREADY_ALLOWED to DomainActionValidationUi.AlreadyAllowed,
            ManualRuleRejection.ALREADY_BLOCKED to DomainActionValidationUi.AlreadyBlocked,
            ManualRuleRejection.ALLOWED_LIMIT_REACHED to DomainActionValidationUi.AllowedLimitReached,
            ManualRuleRejection.BLOCKED_LIMIT_REACHED to DomainActionValidationUi.BlockedLimitReached,
        )
        expected.forEach { (rejection, ui) -> assertEquals(DomainActionNoticeContentUi.ValidationFailed(ui), ProductActivityUiMapper.requestNotice(DomainActionRequestResult.Rejected(DomainActionRejection.RULE_REJECTED, rejection))) }
        assertEquals(DomainActionNoticeContentUi.ValidationFailed(DomainActionValidationUi.RuleRejected), ProductActivityUiMapper.requestNotice(DomainActionRequestResult.Rejected(DomainActionRejection.RULE_REJECTED)))
        assertNull(ProductActivityUiMapper.confirmNotice(DomainActionConfirmResult.Applied(2L)))
        assertEquals(DomainActionNoticeContentUi.Superseded, ProductActivityUiMapper.confirmNotice(DomainActionConfirmResult.Superseded))
        assertEquals(DomainActionNoticeContentUi.ReviewUnavailable, ProductActivityUiMapper.confirmNotice(DomainActionConfirmResult.NoPendingReview))
        assertEquals(DomainActionNoticeContentUi.Busy, ProductActivityUiMapper.confirmNotice(DomainActionConfirmResult.Busy))
        assertEquals(DomainActionNoticeContentUi.ApplyRejected("no"), ProductActivityUiMapper.confirmNotice(DomainActionConfirmResult.Rejected("no")))
        assertEquals(DomainActionNoticeContentUi.Failed("bad"), ProductActivityUiMapper.confirmNotice(DomainActionConfirmResult.Failed("bad")))
    }
}

private fun query(day: LocalDate = LocalDate.of(2026, 8, 1), decision: DomainDecision = DomainDecision.BLOCKED, search: String = "") = ActivitySummaryQuery(
    dateLens = lens(day), topDomainDecision = decision, topDomainSearch = search,
)

private fun lens(today: LocalDate): ActivityDateLens {
    val zone = ZoneId.of("America/New_York")
    return ActivityDateLens(today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), today.atStartOfDay(zone).toInstant().toEpochMilli(), zone.id, today)
}

private fun summary(query: ActivitySummaryQuery = query(), availability: DomainHistoryAvailability = DomainHistoryAvailability.RETAINED, topRows: List<TopDomainRow> = listOf(TopDomainRow("z.example", 8L), TopDomainRow("a.example", 2L)), countsAvailable: Boolean = true) = ActivitySummary(
    digest = if (countsAvailable) ActivityDigest(7L, 3L, 99L) else ActivityDigest(0L, 0L, 0L), topDomains = topRows,
    dateLens = query.dateLens, topDomainsDomainRevision = 1L, topDomainsPolicyRevision = 2L, topDomainsAvailability = availability,
    capturedAtMillis = 100L, topDomainsDecision = query.topDomainDecision, topDomainsSearch = query.topDomainSearch, countsAndUptimeAvailable = countsAvailable,
)

private fun history(query: DomainHistoryQuery, next: Boolean) = ProductPagedRows.domainHistory(
    rows = listOf(
        DomainHistoryRow(30L, 300L, "one.example", query.decision, if (query.decision == DomainDecision.ALLOWED) FilterDecisionReason.DEFAULT_ALLOW else FilterDecisionReason.BLOCKLIST),
        DomainHistoryRow(20L, 200L, "two.example", query.decision, if (query.decision == DomainDecision.ALLOWED) FilterDecisionReason.PAUSED_ALLOW else FilterDecisionReason.THREAT_GUARDRAIL),
        DomainHistoryRow(10L, 100L, "three.example", query.decision, if (query.decision == DomainDecision.ALLOWED) FilterDecisionReason.LOCAL_ALLOWLIST else FilterDecisionReason.INVALID_DOMAIN),
    ), next = if (next) PageToken(StableReadCut(30L, 700L, 4L)) else null, cut = StableReadCut(30L, 700L, 4L), sourceRevision = 21L, sourcePolicyRevision = 22L, historyQuery = query,
)

private fun review(id: Long = 9L, action: DomainAction = DomainAction.BLOCK): DomainActionReview {
    val source = FilterProfileDraft("profile", "Profile", ProtectionLevel.BALANCED, emptySet(), ManualDomainRules(setOf("old-allow.example"), setOf("old-block.example")))
    val desired = FilterProfileDraft("profile", "Profile", ProtectionLevel.BALANCED, emptySet(), ManualDomainRules(setOf("new-allow.example"), setOf("new-block.example")))
    return DomainActionReview(id, "example.com", action, 0L, false, source.id, source, desired)
}
