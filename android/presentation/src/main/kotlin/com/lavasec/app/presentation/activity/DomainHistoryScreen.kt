package com.lavasec.app.presentation.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.presentation.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

@Composable
fun DomainHistoryScreen(
    state: ActivityContentState<DomainHistorySnapshot>,
    retainedDomainLogs: ActivityContentState<Boolean>,
    loadingMore: Boolean,
    onBack: () -> Unit,
    queryState: ActivityQueryControlUi? = null,
    queryCallbacks: ActivityQueryCallbacks? = null,
    rowActionCallbacks: DomainRowActionCallbacks? = null,
) {
    val listState = rememberLazyListState()
    val snapshot = when (state) {
        is ActivityContentState.Ready -> state.value
        is ActivityContentState.Loading -> state.lastGood
        is ActivityContentState.Failed -> state.lastGood
        ActivityContentState.Unavailable,
        ActivityContentState.Disabled,
        -> null
    }
    val sentinelKey = snapshot?.nextPageKey?.takeIf {
        retainedEvidence(retainedDomainLogs) == true && snapshot.rows.isNotEmpty()
    }
    val sentinelItemKey = sentinelKey?.let(::domainHistorySentinelLazyItemKey)
    var dispatchedForVisibilityCycle by remember(sentinelKey) { mutableStateOf(false) }
    val onLoadMore = queryCallbacks?.onLoadMoreHistory
    val loadingMoreDescription = stringResource(R.string.activity_loading_more_history)

    LaunchedEffect(listState, sentinelKey, sentinelItemKey, loadingMore, onLoadMore) {
        if (sentinelKey == null || sentinelItemKey == null || onLoadMore == null || loadingMore) {
            return@LaunchedEffect
        }
        val sentinelVisibility = snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { it.key == sentinelItemKey }
        }.distinctUntilChanged()
        if (dispatchedForVisibilityCycle) {
            sentinelVisibility.first { visible -> !visible }
            dispatchedForVisibilityCycle = false
        }
        sentinelVisibility.first { visible -> visible }
        dispatchedForVisibilityCycle = true
        onLoadMore()
    }

    Box(modifier = Modifier.fillMaxSize().testTag("domain_history_screen")) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("activity_domain_list"),
            contentPadding = LocalLavaContentInsets.current.scrollContentPadding(),
            verticalArrangement = Arrangement.spacedBy(LavaSpacing.md),
        ) {
            item(key = "history-header") {
                ActivityDetailHeader(stringResource(R.string.activity_domain_history_title), onBack)
            }

            if (
                queryState != null &&
                queryCallbacks != null &&
                state != ActivityContentState.Unavailable &&
                state != ActivityContentState.Disabled &&
                retainedDomainLogs != ActivityContentState.Disabled
            ) {
                item(key = "history-query") {
                    Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.md)) {
                        ActivityQueryControls(
                            query = queryState,
                            onDecisionSelected = queryCallbacks.onHistoryDecisionSelected,
                            onSearchChanged = queryCallbacks.onHistorySearchChanged,
                        )
                        Button(
                            onClick = queryCallbacks.onRefreshHistory,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("activity_history_refresh"),
                        ) {
                            Text(stringResource(R.string.activity_refresh_history))
                        }
                    }
                }
            }

            if (snapshot == null) {
                item(key = "history-state") {
                    when (state) {
                        ActivityContentState.Unavailable -> ActivityStatePanel(
                            title = stringResource(R.string.activity_live_unavailable),
                            detail = stringResource(R.string.activity_live_unavailable_detail),
                            tag = "activity_history_unavailable",
                        )
                        ActivityContentState.Disabled -> HistoryDisabledPanel()
                        is ActivityContentState.Loading -> ActivityStatePanel(
                            title = stringResource(R.string.activity_domain_history_loading),
                            tag = "activity_history_initial_loading",
                            loading = true,
                        )
                        is ActivityContentState.Failed -> ActivityStatePanel(
                            title = stringResource(R.string.activity_domain_history_failed),
                            detail = state.message,
                            tag = "activity_history_failure",
                            onRetry = queryCallbacks?.onRefreshHistory,
                        )
                        is ActivityContentState.Ready -> error("Ready always carries a snapshot")
                    }
                }
                if (state == ActivityContentState.Disabled) {
                    item(key = "history-disabled-footer") {
                        Text(stringResource(R.string.activity_retention_footer))
                    }
                }
                return@LazyColumn
            }

            when (state) {
                is ActivityContentState.Loading -> item(key = "history-refreshing") {
                    ActivityStatePanel(
                        title = stringResource(R.string.activity_domain_history_refreshing),
                        tag = "activity_history_refreshing",
                        loading = true,
                    )
                }
                is ActivityContentState.Failed -> item(key = "history-retained-failure") {
                    ActivityStatePanel(
                        title = stringResource(R.string.activity_domain_history_refresh_failed),
                        detail = state.message,
                        tag = "activity_history_retained_failure",
                        onRetry = queryCallbacks?.onRefreshHistory,
                    )
                }
                ActivityContentState.Unavailable,
                ActivityContentState.Disabled,
                is ActivityContentState.Ready,
                -> Unit
            }

            when (retainedDomainLogs) {
                is ActivityContentState.Loading -> item(key = "retained-logs-refreshing") {
                    ActivityStatePanel(
                        title = stringResource(R.string.activity_retained_logs_refreshing),
                        tag = "activity_retained_logs_loading",
                        loading = true,
                    )
                }
                is ActivityContentState.Failed -> item(key = "retained-logs-failure") {
                    ActivityStatePanel(
                        title = stringResource(R.string.activity_retained_logs_refresh_failed),
                        detail = retainedDomainLogs.message,
                        tag = "activity_retained_logs_failure",
                    )
                }
                ActivityContentState.Unavailable,
                ActivityContentState.Disabled,
                is ActivityContentState.Ready,
                -> Unit
            }

            if (retainedDomainLogs == ActivityContentState.Disabled) {
                item(key = "history-disabled-snapshot") { HistoryDisabledPanel() }
            } else if (snapshot.rows.isNotEmpty()) {
                items(
                    items = snapshot.rows,
                    key = { row -> "history-row:${row.id}" },
                ) { row ->
                    val reason = domainHistoryReasonCopy(row.reason)
                    ActivityDomainRow(
                        domain = row.domain,
                        metadata = stringResource(
                            R.string.activity_history_row_detail,
                            reason,
                            formatDomainHistoryTimestamp(row.timestampMillis),
                        ),
                        decision = row.decision,
                        pausedAllow = row.reason == DomainHistoryReasonUi.PausedAllow,
                        rowActionCallbacks = rowActionCallbacks,
                    )
                }
            } else {
                item(key = "history-empty") {
                    val retainedEvidence = retainedEvidence(retainedDomainLogs)
                    when {
                        retainedEvidence == null -> ActivityStatePanel(
                            title = stringResource(R.string.activity_domain_history_provenance_unavailable),
                            tag = "activity_history_provenance_unavailable",
                        )
                        snapshot.search.isNotEmpty() && retainedEvidence -> ActivityStatePanel(
                            title = stringResource(R.string.activity_search_empty),
                            tag = "activity_history_search_empty",
                        )
                        else -> ActivityStatePanel(
                            title = selectedEmptyCopy(snapshot.decision),
                            tag = "activity_history_global_empty",
                        )
                    }
                }
            }

            if (sentinelKey != null && sentinelItemKey != null) {
                item(key = sentinelItemKey) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .testTag("activity_history_sentinel"),
                        )
                        if (loadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(28.dp)
                                    .semantics {
                                        contentDescription = loadingMoreDescription
                                    }
                                    .testTag("activity_history_paging_progress"),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            } else if (loadingMore) {
                item(key = "history-progress") {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(28.dp)
                            .semantics { contentDescription = loadingMoreDescription }
                            .testTag("activity_history_paging_progress"),
                        strokeWidth = 2.dp,
                    )
                }
            }

            item(key = "history-retention") {
                Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                    Text(stringResource(R.string.activity_retention_footer))
                    Text(
                        text = stringResource(R.string.activity_domain_history_clear_scope),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("domain_history_clear_scope"),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryDisabledPanel() {
    ActivityStatePanel(
        title = stringResource(R.string.activity_history_disabled_title),
        detail = stringResource(R.string.activity_history_disabled_body),
        tag = "activity_history_disabled",
    )
}

private fun retainedEvidence(state: ActivityContentState<Boolean>): Boolean? = when (state) {
    is ActivityContentState.Ready -> state.value
    is ActivityContentState.Loading -> state.lastGood
    is ActivityContentState.Failed -> state.lastGood
    ActivityContentState.Disabled -> false
    ActivityContentState.Unavailable -> null
}

@Composable
private fun domainHistoryReasonCopy(reason: DomainHistoryReasonUi): String = stringResource(
    when (reason) {
        DomainHistoryReasonUi.DefaultAllow -> R.string.activity_history_reason_default
        DomainHistoryReasonUi.PausedAllow -> R.string.activity_history_reason_paused_allow
        DomainHistoryReasonUi.LocalAllowlist -> R.string.activity_history_reason_allowlist
        DomainHistoryReasonUi.Blocklist -> R.string.activity_history_reason_blocklist
        DomainHistoryReasonUi.ThreatGuardrail -> R.string.activity_history_reason_threat_guardrail
        DomainHistoryReasonUi.InvalidDomain -> R.string.activity_history_reason_invalid_domain
        DomainHistoryReasonUi.ProtectionUnavailable -> R.string.activity_history_reason_failed_safe
    },
)

internal fun formatDomainHistoryTimestamp(
    timestampMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val local = Instant.ofEpochMilli(timestampMillis).atZone(zoneId)
    val day = local.format(DateTimeFormatter.ofPattern("MMM d", locale))
    val time = local.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return "$day · $time"
}

internal fun domainHistorySentinelLazyItemKey(key: DomainHistorySentinelKeyUi): String = buildString {
    append("activity-history-sentinel|")
    append(key.decision.name)
    append('|')
    append(key.search.length)
    append(':')
    append(key.search)
    append('|')
    append(key.cutMaxRowId)
    append('|')
    append(key.cutCapturedAtMillis)
    append('|')
    append(key.cutContinuationEpoch)
    append('|')
    append(key.sourceRevision)
    append('|')
    append(key.sourcePolicyRevision)
    append('|')
    append(key.finalRowId)
}
