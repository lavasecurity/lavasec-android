package com.lavasec.app.presentation.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.lavasec.app.designsystem.LavaIconSizes
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaMetrics
import com.lavasec.app.designsystem.LavaNavigationCardMetrics
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSettingsIcons
import com.lavasec.app.designsystem.LavaSection
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.LocalLavaSemanticColors
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.foundation.filter.FilterDraftReviewRejection
import com.lavasec.app.presentation.R

/**
 * The pre-commit review — the iOS `FilterConfirmationSheet`: the change summary, the grouped
 * diff (Blocklists, Blocked Domains, Allowed Exceptions) with green-plus / orange-minus
 * semantics, the warning panel when allowed exceptions were added, and Confirm — disabled
 * whenever the draft cannot be committed, with the precise rejection beside the groups.
 *
 * Confirm is a real commit request into the generation-fenced owner; nothing here writes state.
 */
@Composable
fun FilterReviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    state: FilterReviewState? = null,
    actions: FilterLibraryActions? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("filter_review_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaMetrics.sectionSpacing),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.filters_review_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        val review = state
        if (review == null) {
            LavaPanel(modifier = Modifier.testTag("filters_review_unavailable")) {
                Text(
                    text = stringResource(R.string.filters_review_unavailable_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.filters_review_unavailable_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val changeCount = review.diff.changeCount()
            Text(
                text = pluralStringResource(
                    R.plurals.filters_review_summary,
                    changeCount,
                    changeCount,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (review.rejection != null) {
                LavaPanel(modifier = Modifier.testTag("filters_review_rejection")) {
                    Text(
                        text = stringResource(R.string.filters_review_cannot_continue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalLavaSemanticColors.current.attentionText,
                    )
                    Text(
                        text = if (review.rejection == FilterDraftReviewRejection.FILTER_LIMIT_REACHED) {
                            stringResource(R.string.filters_max_filters_detail, review.maxFilters)
                        } else {
                            stringResource(review.rejection.detailResource())
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (review.diff.addedAllowedDomains.isNotEmpty()) {
                LavaPanel(modifier = Modifier.testTag("filters_review_allow_warning")) {
                    Text(
                        text = stringResource(R.string.filters_review_warning_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalLavaSemanticColors.current.attentionText,
                    )
                    Text(
                        text = stringResource(R.string.filters_review_warning_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ReviewDiffGroup(
                title = stringResource(R.string.filters_review_blocklists),
                added = review.diff.addedSources,
                removed = review.diff.removedSources,
            )
            ReviewDiffGroup(
                title = stringResource(R.string.filters_review_blocked_domains),
                added = review.diff.addedBlockedDomains,
                removed = review.diff.removedBlockedDomains,
            )
            ReviewDiffGroup(
                title = stringResource(R.string.filters_review_allowed_exceptions),
                added = review.diff.addedAllowedDomains,
                removed = review.diff.removedAllowedDomains,
            )
            if (actions != null) {
                Button(
                    onClick = { actions.onCommitReview(review.filterId) },
                    enabled = review.rejection == null && !review.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filters_review_confirm"),
                ) {
                    Text(stringResource(R.string.filters_review_confirm))
                }
                OutlinedButton(
                    onClick = actions.onCancelReview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filters_review_cancel"),
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        }
    }
}

private fun FilterDraftDiffUi.changeCount(): Int =
    addedSources.size + removedSources.size +
        addedBlockedDomains.size + removedBlockedDomains.size +
        addedAllowedDomains.size + removedAllowedDomains.size

/** One grouped diff section; omitted entirely when the group is empty (iOS DiffGroup). */
@Composable
private fun ReviewDiffGroup(
    title: String,
    added: List<String>,
    removed: List<String>,
) {
    if (added.isEmpty() && removed.isEmpty()) return
    LavaSection(
        title = title,
        contentSpacing = LavaNavigationCardMetrics.interCardSpacing,
    ) {
        added.forEach { name ->
            ReviewChangeRow(symbol = "+", title = name)
        }
        removed.forEach { name ->
            ReviewChangeRow(symbol = "-", title = name)
        }
    }
}

@Composable
private fun ReviewChangeRow(
    symbol: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    val added = symbol == "+"
    LavaPanel(modifier = modifier.testTag(if (added) "filters_review_added_row" else "filters_review_removed_row")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (added) LavaIcons.Plus else LavaIcons.Minus,
                contentDescription = stringResource(
                    if (added) R.string.filters_review_added else R.string.filters_review_removed,
                ),
                tint = if (added) {
                    LocalLavaSemanticColors.current.safe
                } else {
                    LocalLavaSemanticColors.current.attentionText
                },
                modifier = Modifier.size(LavaIconSizes.control),
            )
            // Diff titles are fixed UI copy for sources and verbatim user data for domains;
            // rendering everything raw keeps a domain that matches a localization key honest.
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun FilterDraftReviewRejection.detailResource(): Int = when (this) {
    FilterDraftReviewRejection.NO_CHANGES -> R.string.filters_review_no_changes
    FilterDraftReviewRejection.FILTER_GONE -> R.string.filters_review_filter_gone
    FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT -> R.string.filters_blocked_limit_detail
    FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT -> R.string.filters_allowed_limit_detail
    FilterDraftReviewRejection.FILTER_FROZEN -> R.string.filters_frozen_detail
    FilterDraftReviewRejection.FILTER_LIMIT_REACHED -> R.string.filters_max_filters_detail
    FilterDraftReviewRejection.INVALID_NAME -> R.string.filters_invalid_name
    FilterDraftReviewRejection.DUPLICATE_NAME -> R.string.filters_duplicate_name
    FilterDraftReviewRejection.INVALID_EMOJI -> R.string.filters_invalid_emoji
}

/**
 * The full-screen preparation cover — the iOS `FilterPreparationScreen` phases: preparing
 * (the ticker title), success, and failure with retry / back-to-review / keep-current. Retry
 * goes through the owner's re-admission (no duplicated writes, no stale generation); the
 * current filter stays active on every failure, and Back is blocked while preparing.
 */
@Composable
fun FilterPreparationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    state: FilterPreparationUiState = FilterPreparationUiState.Idle,
    actions: FilterLibraryActions? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("filter_preparation_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            FilterPreparationUiState.Idle -> {
                Text(
                    text = stringResource(R.string.filters_prepare_idle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("filters_prepare_back"),
                ) {
                    Text(stringResource(R.string.navigate_back))
                }
            }
            is FilterPreparationUiState.Preparing -> {
                Icon(
                    imageVector = LavaIcons.Guard,
                    contentDescription = null,
                    tint = LocalLavaSemanticColors.current.safe,
                    modifier = Modifier.size(LavaIconSizes.heroResult),
                )
                Text(
                    text = stringResource(R.string.filters_preparing_building),
                    style = LavaScreenTypography.screenTitle,
                    modifier = Modifier
                        .semantics { heading() }
                        .testTag("filters_prepare_progress_title"),
                )
                Text(
                    text = stringResource(R.string.filters_applying),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilterPreparationUiState.Success -> {
                Icon(
                    imageVector = LavaIcons.Check,
                    contentDescription = null,
                    tint = LocalLavaSemanticColors.current.safe,
                    modifier = Modifier.size(LavaIconSizes.heroResult),
                )
                Text(
                    text = stringResource(R.string.filters_prepare_success_title),
                    style = LavaScreenTypography.screenTitle,
                    modifier = Modifier
                        .semantics { heading() }
                        .testTag("filters_prepare_success"),
                )
                Text(
                    text = stringResource(R.string.filters_prepare_success_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is FilterPreparationUiState.Failed -> {
                Icon(
                    imageVector = LavaSettingsIcons.Info,
                    contentDescription = null,
                    tint = LocalLavaSemanticColors.current.attentionText,
                    modifier = Modifier.size(LavaIconSizes.heroResult),
                )
                Text(
                    text = stringResource(R.string.filters_prepare_failed_title),
                    style = LavaScreenTypography.screenTitle,
                    modifier = Modifier.testTag("filters_prepare_failed_title"),
                )
                Text(
                    text = stringResource(R.string.filters_prepare_failed_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.filters_prepare_failed_previous_active),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actions != null) {
                    if (state.retryable) {
                        Button(
                            onClick = actions.onRetryPreparation,
                            modifier = Modifier.testTag("filters_prepare_retry"),
                        ) {
                            Text(stringResource(R.string.filters_retry))
                        }
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("filters_prepare_back_to_review"),
                        ) {
                            Text(stringResource(R.string.filters_back_to_edit))
                        }
                    }
                    OutlinedButton(
                        onClick = actions.onKeepCurrentAfterFailure,
                        modifier = Modifier.testTag("filters_prepare_keep_current"),
                    ) {
                        Text(stringResource(R.string.filters_keep_current))
                    }
                }
            }
        }
    }
}
