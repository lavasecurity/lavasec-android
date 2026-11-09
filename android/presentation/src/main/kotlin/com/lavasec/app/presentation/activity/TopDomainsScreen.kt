package com.lavasec.app.presentation.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.presentation.R
import java.math.BigInteger

@Composable
fun TopDomainsScreen(
    state: ActivityContentState<TopDomainsSnapshot>,
    onBack: () -> Unit,
    queryState: ActivityQueryControlUi? = null,
    queryCallbacks: ActivityQueryCallbacks? = null,
    rowActionCallbacks: DomainRowActionCallbacks? = null,
) {
    val snapshot = when (state) {
        is ActivityContentState.Ready -> state.value
        is ActivityContentState.Loading -> state.lastGood
        is ActivityContentState.Failed -> state.lastGood
        ActivityContentState.Unavailable,
        ActivityContentState.Disabled,
        -> null
    }
    Box(modifier = Modifier.fillMaxSize().testTag("top_domains_screen")) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("activity_domain_list"),
            contentPadding = LocalLavaContentInsets.current.scrollContentPadding(),
            verticalArrangement = Arrangement.spacedBy(LavaSpacing.md),
        ) {
            item(key = "top-header") {
                ActivityDetailHeader(stringResource(R.string.activity_top_domains_title), onBack)
            }

            if (
                queryState != null &&
                queryCallbacks != null &&
                state != ActivityContentState.Unavailable &&
                state != ActivityContentState.Disabled &&
                snapshot?.availability != ActivityLogAvailabilityUi.Disabled
            ) {
                item(key = "top-query") {
                    Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.md)) {
                        ActivityQueryControls(
                            query = queryState,
                            onDecisionSelected = queryCallbacks.onTopDomainDecisionSelected,
                            onSearchChanged = queryCallbacks.onTopDomainSearchChanged,
                        )
                    }
                }
            }

            if (snapshot == null) {
                item(key = "top-state") {
                    when (state) {
                        ActivityContentState.Unavailable -> ActivityStatePanel(
                            title = stringResource(R.string.activity_live_unavailable),
                            detail = stringResource(R.string.activity_live_unavailable_detail),
                            tag = "activity_top_unavailable",
                        )
                        ActivityContentState.Disabled -> ActivityStatePanel(
                            title = stringResource(R.string.activity_top_domains_disabled),
                            tag = "activity_top_disabled",
                        )
                        is ActivityContentState.Loading -> ActivityStatePanel(
                            title = stringResource(R.string.activity_top_domains_loading),
                            tag = "activity_top_initial_loading",
                            loading = true,
                        )
                        is ActivityContentState.Failed -> ActivityStatePanel(
                            title = stringResource(R.string.activity_top_domains_failed),
                            detail = state.message,
                            tag = "activity_top_failure",
                        )
                        is ActivityContentState.Ready -> error("Ready always carries a snapshot")
                    }
                }
                if (state == ActivityContentState.Disabled) {
                    item(key = "top-disabled-footer") {
                        androidx.compose.material3.Text(stringResource(R.string.activity_retention_footer))
                    }
                }
                return@LazyColumn
            }

            when (state) {
                is ActivityContentState.Loading -> item(key = "top-refreshing") {
                    ActivityStatePanel(
                        title = stringResource(R.string.activity_top_domains_refreshing),
                        tag = "activity_top_refreshing",
                        loading = true,
                    )
                }
                is ActivityContentState.Failed -> item(key = "top-retained-failure") {
                    ActivityStatePanel(
                        title = stringResource(R.string.activity_top_domains_refresh_failed),
                        detail = state.message,
                        tag = "activity_top_retained_failure",
                    )
                }
                ActivityContentState.Unavailable,
                ActivityContentState.Disabled,
                is ActivityContentState.Ready,
                -> Unit
            }

            when (snapshot.availability) {
                ActivityLogAvailabilityUi.Disabled -> item(key = "top-disabled-snapshot") {
                    ActivityStatePanel(
                        title = stringResource(R.string.activity_top_domains_disabled),
                        tag = "activity_top_disabled",
                    )
                }
                ActivityLogAvailabilityUi.Empty -> item(key = "top-global-empty") {
                    ActivityStatePanel(
                        title = selectedEmptyCopy(snapshot.decision),
                        tag = "activity_top_global_empty",
                    )
                }
                ActivityLogAvailabilityUi.Retained -> {
                    if (snapshot.rows.isEmpty()) {
                        item(key = "top-query-empty") {
                            ActivityStatePanel(
                                title = if (snapshot.search.isNotEmpty()) {
                                    stringResource(R.string.activity_search_empty)
                                } else {
                                    selectedEmptyCopy(snapshot.decision)
                                },
                                tag = if (snapshot.search.isNotEmpty()) {
                                    "activity_top_search_empty"
                                } else {
                                    "activity_top_global_empty"
                                },
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = snapshot.rows,
                            key = { index, row -> "top-row:$index:${row.domain}" },
                        ) { _, row ->
                            ActivityDomainRow(
                                domain = row.domain,
                                metadata = pluralStringResource(
                                    R.plurals.activity_domain_times,
                                    row.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                    formatActivityCount(BigInteger.valueOf(row.count)),
                                ),
                                decision = snapshot.decision,
                                pausedAllow = false,
                                rowActionCallbacks = rowActionCallbacks,
                            )
                        }
                    }
                }
            }
            item(key = "top-retention") {
                Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                    androidx.compose.material3.Text(stringResource(R.string.activity_retention_footer))
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.activity_domain_history_clear_scope),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("top_domains_clear_scope"),
                    )
                }
            }
        }
    }
}

@Composable
internal fun selectedEmptyCopy(decision: ActivityDecisionUi): String = stringResource(
    when (decision) {
        ActivityDecisionUi.Allowed -> R.string.activity_allowed_empty
        ActivityDecisionUi.Blocked -> R.string.activity_blocked_empty
    },
)
