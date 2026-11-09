package com.lavasec.app.presentation.activity

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaIconSizes
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.LocalLavaSemanticColors
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.presentation.R
import java.time.LocalDate
import kotlinx.coroutines.launch

data class ActivityQueryCallbacks(
    val onRefreshSummary: () -> Unit,
    val onDateRangeSelected: (start: LocalDate, endInclusive: LocalDate) -> Unit,
    val onTopDomainDecisionSelected: (ActivityDecisionUi) -> Unit,
    val onTopDomainSearchChanged: (String) -> Unit,
    val onRefreshHistory: () -> Unit,
    val onHistoryDecisionSelected: (ActivityDecisionUi) -> Unit,
    val onHistorySearchChanged: (String) -> Unit,
    val onLoadMoreHistory: () -> Unit,
    val onRefreshNetworkActivity: () -> Unit,
    val onLoadMoreNetworkActivity: () -> Unit,
)

data class DomainRowActionCallbacks(val onRequest: (domain: String, action: DomainActionUi) -> Unit)

data class DomainReviewCallbacks(
    val onReviewVisible: (reviewId: Long) -> Unit,
    val onCancelBeforeVisible: (reviewId: Long) -> Unit,
    val onConfirm: (reviewId: Long) -> Unit,
    val onCancel: (reviewId: Long) -> Unit,
)

data class DomainNoticeCallbacks(val onDismissNotice: (noticeId: Long) -> Unit)

/** Activity's Android-facing overview. State and callback ownership remain with its host. */
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onBack: () -> Unit,
    onOpenTopDomains: () -> Unit,
    onOpenDomainHistory: () -> Unit,
    queryCallbacks: ActivityQueryCallbacks? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("activity_screen")
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.activity_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        ActivityOverviewContent(
            state = state,
            onOpenTopDomains = onOpenTopDomains,
            onOpenDomainHistory = onOpenDomainHistory,
            queryCallbacks = queryCallbacks,
        )
    }
}

/** Transitional overload keeps the existing shell truthful until Task 3 wires state/navigation. */
@Composable
fun ActivityScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    ActivityScreen(
        state = ActivityUiState.unavailable,
        onBack = onBack,
        onOpenTopDomains = {},
        onOpenDomainHistory = {},
        modifier = modifier,
    )
}

@Composable
internal fun ActivityDetailHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = title,
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
internal fun ActivityQueryControls(
    query: ActivityQueryControlUi,
    onDecisionSelected: (ActivityDecisionUi) -> Unit,
    onSearchChanged: (String) -> Unit,
) {
    var draft by remember(query) { mutableStateOf(query.draft) }
    OutlinedTextField(
        value = draft.search,
        onValueChange = { search ->
            draft = draft.copy(search = search)
            onSearchChanged(search)
        },
        modifier = Modifier.fillMaxWidth().testTag("activity_search"),
        label = { Text(stringResource(R.string.activity_search_domains)) },
        singleLine = true,
    )
    Text(
        text = stringResource(R.string.activity_show_decision),
        style = MaterialTheme.typography.labelLarge,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
    ) {
        ActivityDecisionUi.entries.forEach { candidate ->
            val allowed = candidate == ActivityDecisionUi.Allowed
            FilterChip(
                selected = draft.decision == candidate,
                onClick = {
                    draft = draft.copy(decision = candidate)
                    onDecisionSelected(candidate)
                },
                label = {
                    Text(
                        stringResource(
                            if (allowed) R.string.activity_allowed_decision
                            else R.string.activity_blocked_decision,
                        ),
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag(
                        if (allowed) "activity_decision_allowed"
                        else "activity_decision_blocked",
                    ),
            )
        }
    }
}

@Composable
internal fun ActivityStatePanel(
    title: String,
    detail: String? = null,
    tag: String,
    loading: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    LavaPanel(modifier = Modifier.testTag(tag)) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        detail?.let { Text(it) }
        if (onRetry != null) {
            androidx.compose.material3.Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_retry_button"),
            ) {
                Text(stringResource(R.string.activity_retry))
            }
        }
    }
}

@Composable
internal fun ActivityDomainRow(
    domain: String,
    metadata: String,
    decision: ActivityDecisionUi,
    pausedAllow: Boolean,
    rowActionCallbacks: DomainRowActionCallbacks?,
) {
    val colors = LocalLavaSemanticColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (pausedAllow) Modifier.testTag("activity_paused_row") else Modifier),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (pausedAllow) 4.dp else 1.dp,
        color = if (pausedAllow) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(LavaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
        ) {
            Icon(
                imageVector = when {
                    pausedAllow -> LavaIcons.Pause
                    decision == ActivityDecisionUi.Blocked -> LavaIcons.Blocked
                    else -> LavaIcons.Allowed
                },
                contentDescription = if (pausedAllow) {
                    stringResource(R.string.activity_history_reason_paused_allow)
                } else {
                    null
                },
                tint = when {
                    pausedAllow -> MaterialTheme.colorScheme.onSurfaceVariant
                    decision == ActivityDecisionUi.Blocked -> colors.danger
                    else -> colors.safe
                },
                modifier = Modifier.size(LavaIconSizes.control),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {}
                    .testTag("activity_domain_row"),
                verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
            ) {
                Text(domain, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    metadata,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ActivityDomainActions(domain, rowActionCallbacks)
        }
    }
}

@Composable
private fun ActivityDomainActions(
    domain: String,
    callbacks: DomainRowActionCallbacks?,
) {
    var expanded by remember(domain) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.size(48.dp).testTag("activity_domain_action"),
    ) {
        Icon(
            imageVector = LavaIcons.MoreVert,
            contentDescription = stringResource(R.string.activity_domain_actions, domain),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.activity_action_copy)) },
            onClick = {
                expanded = false
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("domain", domain)),
                    )
                }
            },
            modifier = Modifier.heightIn(min = 48.dp).testTag("activity_action_copy"),
        )
        if (callbacks != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.activity_action_block)) },
                onClick = {
                    expanded = false
                    callbacks.onRequest(domain, DomainActionUi.Block)
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_action_block"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.activity_action_allow)) },
                onClick = {
                    expanded = false
                    callbacks.onRequest(domain, DomainActionUi.Allow)
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_action_allow"),
            )
        }
    }
}
