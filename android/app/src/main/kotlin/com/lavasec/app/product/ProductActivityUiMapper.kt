package com.lavasec.app.product

import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.filter.ManualRuleRejection
import com.lavasec.app.product.activity.ActivityDateLens
import com.lavasec.app.product.activity.ActivitySummary
import com.lavasec.app.product.activity.DomainDecision
import com.lavasec.app.product.activity.DomainHistoryAvailability
import com.lavasec.app.product.activity.DomainHistoryRow
import com.lavasec.app.product.activity.ProductActivityState
import com.lavasec.app.product.activity.ProductLoadState
import com.lavasec.app.product.activity.ProductPagedRows
import com.lavasec.app.product.domain.DomainAction
import com.lavasec.app.product.domain.DomainActionConfirmResult
import com.lavasec.app.product.domain.DomainActionRejection
import com.lavasec.app.product.domain.DomainActionRequestResult
import com.lavasec.app.product.domain.DomainActionReview
import com.lavasec.app.product.domain.DomainActionState
import com.lavasec.app.presentation.activity.ActivityContentState
import com.lavasec.app.presentation.activity.ActivityCountSnapshot
import com.lavasec.app.presentation.activity.ActivityDateRangeUi
import com.lavasec.app.presentation.activity.ActivityDecisionUi
import com.lavasec.app.presentation.activity.ActivityDomainQueryUi
import com.lavasec.app.presentation.activity.ActivityLogAvailabilityUi
import com.lavasec.app.presentation.activity.ActivityOverviewSnapshot
import com.lavasec.app.presentation.activity.ActivityQueryControlUi
import com.lavasec.app.presentation.activity.ActivityUiState
import com.lavasec.app.presentation.activity.DomainActionChangeGroupUi
import com.lavasec.app.presentation.activity.DomainActionChangeKindUi
import com.lavasec.app.presentation.activity.DomainActionChangeUi
import com.lavasec.app.presentation.activity.DomainActionNoticeContentUi
import com.lavasec.app.presentation.activity.DomainActionReviewUi
import com.lavasec.app.presentation.activity.DomainActionUi
import com.lavasec.app.presentation.activity.DomainActionUiState
import com.lavasec.app.presentation.activity.DomainActionValidationUi
import com.lavasec.app.presentation.activity.DomainHistoryReasonUi
import com.lavasec.app.presentation.activity.DomainHistoryRowUi
import com.lavasec.app.presentation.activity.DomainHistorySentinelKeyUi
import com.lavasec.app.presentation.activity.DomainHistorySnapshot
import com.lavasec.app.presentation.activity.NetworkActivityRowUi
import com.lavasec.app.presentation.activity.NetworkActivitySentinelKeyUi
import com.lavasec.app.presentation.activity.NetworkActivitySnapshot
import com.lavasec.app.presentation.activity.TopDomainRowUi
import com.lavasec.app.presentation.activity.TopDomainsSnapshot
import com.lavasec.app.product.activity.NetworkActivityRow
import java.time.Instant
import java.time.ZoneId

object ProductActivityUiMapper {
    fun map(
        state: ProductActivityState,
        domainActionHostState: NativeDomainActionHostState =
            NativeDomainActionHostState(DomainActionState.Idle, null),
        nativeReviewState: NativeDomainActionReviewState = NativeDomainActionReviewState.Idle,
    ): ActivityUiState = ActivityUiState(
        overview = mapSummary(state.summary) { summary -> overview(summary) },
        topDomains = mapSummary(state.summary) { summary -> topDomains(summary) },
        domainHistory = mapHistory(state.history),
        retainedDomainLogs = mapRetained(state.retainedDomainLogs),
        historyLoadingMore = state.historyLoadingMore,
        domainAction = mapAction(domainActionHostState.coordinatorState, nativeReviewState),
        domainActionNotice = domainActionHostState.notice,
        topDomainsQuery = queryControl(
            state.summaryQuery.topDomainDecision,
            state.summaryQuery.topDomainSearch,
        ),
        domainHistoryQuery = queryControl(
            state.historyQuery.decision,
            state.historyQuery.search,
        ),
        networkActivity = mapNetwork(state.network),
        networkLoadingMore = state.networkLoadingMore,
    )

    fun requestNotice(result: DomainActionRequestResult): DomainActionNoticeContentUi? = when (result) {
        is DomainActionRequestResult.Staged,
        is DomainActionRequestResult.ReviewAlreadyPending,
        DomainActionRequestResult.AuthenticationCancelled,
        DomainActionRequestResult.ReviewCancelled,
        -> null
        DomainActionRequestResult.Busy -> DomainActionNoticeContentUi.Busy
        is DomainActionRequestResult.AuthenticationFailed ->
            DomainActionNoticeContentUi.AuthenticationFailed(result.message)
        is DomainActionRequestResult.ReviewRejected -> DomainActionNoticeContentUi.ReviewRejected(result.message)
        is DomainActionRequestResult.Failed -> DomainActionNoticeContentUi.Failed(result.message)
        is DomainActionRequestResult.Rejected -> requestRejection(result.reason, result.ruleRejection)
    }

    fun confirmNotice(result: DomainActionConfirmResult): DomainActionNoticeContentUi? = when (result) {
        is DomainActionConfirmResult.Applied -> null
        DomainActionConfirmResult.Superseded -> DomainActionNoticeContentUi.Superseded
        DomainActionConfirmResult.NoPendingReview -> DomainActionNoticeContentUi.ReviewUnavailable
        DomainActionConfirmResult.Busy -> DomainActionNoticeContentUi.Busy
        is DomainActionConfirmResult.Rejected -> DomainActionNoticeContentUi.ApplyRejected(result.message)
        is DomainActionConfirmResult.Failed -> DomainActionNoticeContentUi.Failed(result.message)
    }

    private fun requestRejection(
        reason: DomainActionRejection,
        ruleRejection: ManualRuleRejection?,
    ): DomainActionNoticeContentUi = when (reason) {
        DomainActionRejection.UNSAVED_ACTIVE_FILTER_DRAFT -> DomainActionNoticeContentUi.UnsavedFilterEdits
        DomainActionRejection.RULE_NOT_PRESENT -> validation(DomainActionValidationUi.RuleNotPresent)
        DomainActionRejection.RULE_REJECTED -> validation(
            when (ruleRejection) {
                ManualRuleRejection.INVALID_DOMAIN -> DomainActionValidationUi.InvalidDomain
                ManualRuleRejection.ALREADY_ALLOWED -> DomainActionValidationUi.AlreadyAllowed
                ManualRuleRejection.ALREADY_BLOCKED -> DomainActionValidationUi.AlreadyBlocked
                ManualRuleRejection.ALLOWED_LIMIT_REACHED -> DomainActionValidationUi.AllowedLimitReached
                ManualRuleRejection.BLOCKED_LIMIT_REACHED -> DomainActionValidationUi.BlockedLimitReached
                null -> DomainActionValidationUi.RuleRejected
            },
        )
    }

    private fun validation(reason: DomainActionValidationUi) =
        DomainActionNoticeContentUi.ValidationFailed(reason)

    private fun <T> mapSummary(
        state: ProductLoadState<ActivitySummary>,
        transform: (ActivitySummary) -> T,
    ): ActivityContentState<T> = when (state) {
        is ProductLoadState.Loading -> ActivityContentState.Loading(state.lastGood?.let(transform))
        is ProductLoadState.Ready -> ActivityContentState.Ready(transform(state.value))
        ProductLoadState.Disabled -> ActivityContentState.Disabled
        is ProductLoadState.Failed -> ActivityContentState.Failed(state.message.ifBlank { "Activity data unavailable" }, state.lastGood?.let(transform))
    }

    private fun overview(summary: ActivitySummary): ActivityOverviewSnapshot = ActivityOverviewSnapshot(
        dateRange = summary.dateLens.toUi(),
        counts = summary.digest.takeIf { summary.countsAndUptimeAvailable }
            ?.let { ActivityCountSnapshot(it.allowedCount, it.blockedCount) },
        localProtectionUptimeMillis = summary.digest.localProtectionUptimeMillis
            .takeIf { summary.countsAndUptimeAvailable },
        retainedDomainLogs = summary.topDomainsAvailability.toUi(),
    )

    private fun topDomains(summary: ActivitySummary): TopDomainsSnapshot = TopDomainsSnapshot(
        dateRange = summary.dateLens.toUi(),
        decision = summary.topDomainsDecision.toUi(),
        search = summary.topDomainsSearch,
        availability = summary.topDomainsAvailability.toUi(),
        rows = summary.topDomains.map { TopDomainRowUi(it.domain, it.count) },
    )

    private fun mapHistory(
        state: ProductLoadState<ProductPagedRows<DomainHistoryRow>>,
    ): ActivityContentState<DomainHistorySnapshot> = when (state) {
        is ProductLoadState.Loading -> ActivityContentState.Loading(state.lastGood?.let(::history))
        is ProductLoadState.Ready -> ActivityContentState.Ready(history(state.value))
        ProductLoadState.Disabled -> ActivityContentState.Disabled
        is ProductLoadState.Failed -> ActivityContentState.Failed(state.message.ifBlank { "Activity data unavailable" }, state.lastGood?.let(::history))
    }

    private fun history(page: ProductPagedRows<DomainHistoryRow>): DomainHistorySnapshot {
        val query = requireNotNull(page.historyQuery)
        val rows = page.rows.map { row ->
            DomainHistoryRowUi(row.id, row.timestampMillis, row.domain, row.decision.toUi(), row.reason.toUi())
        }
        return DomainHistorySnapshot(
            decision = query.decision.toUi(),
            search = query.search,
            rows = rows,
            nextPageKey = page.next?.let {
                DomainHistorySentinelKeyUi(
                    decision = query.decision.toUi(),
                    search = query.search,
                    cutMaxRowId = page.cut.maxRowId,
                    cutCapturedAtMillis = page.cut.capturedAtMillis,
                    cutContinuationEpoch = page.cut.continuationEpoch,
                    sourceRevision = page.sourceRevision,
                    sourcePolicyRevision = requireNotNull(page.sourcePolicyRevision),
                    finalRowId = page.rows.last().id,
                )
            },
        )
    }

    private fun mapRetained(state: ProductLoadState<Boolean>): ActivityContentState<Boolean> = when (state) {
        is ProductLoadState.Loading -> ActivityContentState.Loading(state.lastGood)
        is ProductLoadState.Ready -> ActivityContentState.Ready(state.value)
        ProductLoadState.Disabled -> ActivityContentState.Disabled
        is ProductLoadState.Failed -> ActivityContentState.Failed(state.message.ifBlank { "Activity data unavailable" }, state.lastGood)
    }

    private fun mapNetwork(
        state: ProductLoadState<ProductPagedRows<NetworkActivityRow>>,
    ): ActivityContentState<NetworkActivitySnapshot> = when (state) {
        is ProductLoadState.Loading -> ActivityContentState.Loading(state.lastGood?.let(::network))
        is ProductLoadState.Ready -> ActivityContentState.Ready(network(state.value))
        ProductLoadState.Disabled -> ActivityContentState.Disabled
        is ProductLoadState.Failed ->
            ActivityContentState.Failed(
                state.message.ifBlank { "Activity data unavailable" },
                state.lastGood?.let(::network),
            )
    }

    private fun network(page: ProductPagedRows<NetworkActivityRow>): NetworkActivitySnapshot {
        val rows = page.rows.map { row ->
            NetworkActivityRowUi(
                id = row.id,
                timestampMillis = row.timestampMillis,
                event = row.entry.event,
                capturedState = row.entry.capturedState,
                occurrenceCount = row.entry.occurrenceCount,
            )
        }
        return NetworkActivitySnapshot(
            rows = rows,
            nextPageKey = page.next?.let {
                NetworkActivitySentinelKeyUi(
                    cutMaxRowId = page.cut.maxRowId,
                    cutCapturedAtMillis = page.cut.capturedAtMillis,
                    cutContinuationEpoch = page.cut.continuationEpoch,
                    sourceRevision = page.sourceRevision,
                    finalRowId = page.rows.last().id,
                )
            },
        )
    }

    private fun mapAction(
        coordinator: DomainActionState,
        native: NativeDomainActionReviewState,
    ): DomainActionUiState = when (coordinator) {
        DomainActionState.Idle -> when (native) {
            NativeDomainActionReviewState.Idle -> DomainActionUiState.Idle
            is NativeDomainActionReviewState.AwaitingVisibility -> DomainActionUiState.Presenting(native.review.toUi())
            is NativeDomainActionReviewState.Handoff -> DomainActionUiState.Handoff(native.review.toUi())
        }
        is DomainActionState.ReviewPending -> DomainActionUiState.ReviewPending(coordinator.review.toUi())
        is DomainActionState.Applying -> DomainActionUiState.Applying(coordinator.review.toUi())
        is DomainActionState.ApplyFailed -> DomainActionUiState.ApplyFailed(coordinator.review.toUi(), coordinator.message)
    }

    private fun ActivityDateLens.toUi(): ActivityDateRangeUi {
        val zone = ZoneId.of(zoneId)
        val start = Instant.ofEpochMilli(startDayMillis).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(endDayMillis).atZone(zone).toLocalDate()
        return ActivityDateRangeUi(start, end, today, zoneId)
    }

    private fun queryControl(
        decision: DomainDecision,
        search: String,
    ): ActivityQueryControlUi {
        val current = ActivityDomainQueryUi(decision.toUi(), search)
        return ActivityQueryControlUi(current = current, draft = current)
    }

    private fun DomainDecision.toUi() = when (this) {
        DomainDecision.ALLOWED -> ActivityDecisionUi.Allowed
        DomainDecision.BLOCKED -> ActivityDecisionUi.Blocked
    }

    private fun DomainHistoryAvailability.toUi() = when (this) {
        DomainHistoryAvailability.DISABLED -> ActivityLogAvailabilityUi.Disabled
        DomainHistoryAvailability.EMPTY -> ActivityLogAvailabilityUi.Empty
        DomainHistoryAvailability.RETAINED -> ActivityLogAvailabilityUi.Retained
    }

    private fun FilterDecisionReason.toUi() = when (this) {
        FilterDecisionReason.DEFAULT_ALLOW -> DomainHistoryReasonUi.DefaultAllow
        FilterDecisionReason.PAUSED_ALLOW -> DomainHistoryReasonUi.PausedAllow
        FilterDecisionReason.LOCAL_ALLOWLIST -> DomainHistoryReasonUi.LocalAllowlist
        FilterDecisionReason.BLOCKLIST -> DomainHistoryReasonUi.Blocklist
        FilterDecisionReason.THREAT_GUARDRAIL -> DomainHistoryReasonUi.ThreatGuardrail
        FilterDecisionReason.INVALID_DOMAIN -> DomainHistoryReasonUi.InvalidDomain
        FilterDecisionReason.PROTECTION_UNAVAILABLE -> DomainHistoryReasonUi.ProtectionUnavailable
    }

    private fun DomainActionReview.toUi(): DomainActionReviewUi = DomainActionReviewUi(
        id = id,
        domain = normalizedDomain,
        action = action.toUi(),
        changes = changes(sourceProfile.manualRules.blockedDomains, desiredProfile.manualRules.blockedDomains,
            DomainActionChangeGroupUi.BlockedDomains) +
            changes(sourceProfile.manualRules.allowedDomains, desiredProfile.manualRules.allowedDomains,
                DomainActionChangeGroupUi.AllowedExceptions),
    )

    private fun changes(
        source: Set<String>,
        desired: Set<String>,
        group: DomainActionChangeGroupUi,
    ): List<DomainActionChangeUi> =
        (desired - source).sorted().map { DomainActionChangeUi(group, DomainActionChangeKindUi.Added, it) } +
            (source - desired).sorted().map { DomainActionChangeUi(group, DomainActionChangeKindUi.Removed, it) }

    private fun DomainAction.toUi() = when (this) {
        DomainAction.ALLOW -> DomainActionUi.Allow
        DomainAction.BLOCK -> DomainActionUi.Block
        DomainAction.REMOVE -> DomainActionUi.Remove
    }
}
