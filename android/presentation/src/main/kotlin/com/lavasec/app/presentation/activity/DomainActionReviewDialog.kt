package com.lavasec.app.presentation.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.presentation.R

@Composable
fun DomainActionReviewDialog(
    state: DomainActionUiState,
    notice: DomainActionNoticeUi?,
    reviewCallbacks: DomainReviewCallbacks?,
    noticeCallbacks: DomainNoticeCallbacks?,
) {
    val acknowledgedReviewIds = remember { mutableStateSetOf<Long>() }
    val review = state.reviewOrNull()
    if (review != null && reviewCallbacks != null) {
        ReviewDialog(
            state = state,
            review = review,
            notice = notice,
            reviewCallbacks = reviewCallbacks,
            noticeCallbacks = noticeCallbacks,
            onPresentingVisible = { reviewId ->
                if (acknowledgedReviewIds.add(reviewId)) {
                    reviewCallbacks.onReviewVisible(reviewId)
                }
            },
        )
    } else if (notice != null) {
        StandaloneNoticeDialog(notice, noticeCallbacks)
    }
}

@Composable
private fun ReviewDialog(
    state: DomainActionUiState,
    review: DomainActionReviewUi,
    notice: DomainActionNoticeUi?,
    reviewCallbacks: DomainReviewCallbacks,
    noticeCallbacks: DomainNoticeCallbacks?,
    onPresentingVisible: (Long) -> Unit,
) {
    val awaitingVisibility = state is DomainActionUiState.Presenting
    val handoff = state is DomainActionUiState.Handoff
    val applying = state is DomainActionUiState.Applying
    val actionable = state is DomainActionUiState.ReviewPending ||
        state is DomainActionUiState.ApplyFailed
    val dismissible = awaitingVisibility || actionable
    val cancel: () -> Unit = {
        when {
            awaitingVisibility -> reviewCallbacks.onCancelBeforeVisible(review.id)
            actionable -> reviewCallbacks.onCancel(review.id)
        }
    }
    AlertDialog(
        onDismissRequest = { if (dismissible) cancel() },
        modifier = Modifier.testTag("activity_domain_review_dialog"),
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        ),
        title = {
            // Visibility acknowledgement belongs to content installed by the actual dialog. This
            // prevents a parent composition from acknowledging a review before the modal exists.
            if (awaitingVisibility) {
                LaunchedEffect(review.id) { onPresentingVisible(review.id) }
            }
            Text(stringResource(R.string.activity_review_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            ) {
                Text(
                    domainActionCopy(review.action),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(review.domain)
                Text(
                    pluralStringResource(
                        R.plurals.activity_review_summary,
                        review.changeCount,
                        review.changeCount,
                    ),
                )
                if (review.requiresAllowWarning) {
                    LavaPanel {
                        Text(
                            stringResource(R.string.activity_review_allow_warning_title),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(stringResource(R.string.activity_review_allow_warning_body))
                    }
                }
                DomainActionChangeGroup(
                    title = stringResource(R.string.activity_review_blocked_domains),
                    changes = review.changes.filter {
                        it.group == DomainActionChangeGroupUi.BlockedDomains
                    },
                )
                DomainActionChangeGroup(
                    title = stringResource(R.string.activity_review_allowed_exceptions),
                    changes = review.changes.filter {
                        it.group == DomainActionChangeGroupUi.AllowedExceptions
                    },
                )
                when {
                    handoff -> ReviewProgress(stringResource(R.string.activity_review_preparing))
                    applying -> ReviewProgress(stringResource(R.string.activity_review_applying))
                    state is DomainActionUiState.ApplyFailed -> {
                        ActivityStatePanel(
                            title = stringResource(R.string.activity_review_apply_failed),
                            detail = state.message,
                            tag = "activity_review_failure",
                        )
                    }
                }
                notice?.let {
                    DomainActionNotice(
                        notice = it,
                        callbacks = noticeCallbacks,
                        inline = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { reviewCallbacks.onConfirm(review.id) },
                enabled = actionable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_review_confirm"),
            ) {
                Text(stringResource(R.string.activity_review_confirm))
            }
        },
        dismissButton = {
            val cancelDescription = stringResource(R.string.activity_review_cancel)
            TextButton(
                onClick = cancel,
                enabled = dismissible,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = cancelDescription
                    }
                    .testTag("activity_review_cancel"),
            ) {
                Text(stringResource(R.string.activity_review_cancel))
            }
        },
    )
}

@Composable
private fun DomainActionChangeGroup(
    title: String,
    changes: List<DomainActionChangeUi>,
) {
    if (changes.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    changes.forEach { change ->
        val action = when (change.kind) {
            DomainActionChangeKindUi.Added -> stringResource(R.string.activity_review_added)
            DomainActionChangeKindUi.Removed -> stringResource(R.string.activity_review_removed)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = action
                    stateDescription = change.domain
                },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(LavaSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            ) {
                Text(if (change.kind == DomainActionChangeKindUi.Added) "+" else "−")
                Text(change.domain, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReviewProgress(label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(label)
    }
}

@Composable
private fun StandaloneNoticeDialog(
    notice: DomainActionNoticeUi,
    callbacks: DomainNoticeCallbacks?,
) {
    val copy = noticeCopy(notice.content)
    AlertDialog(
        onDismissRequest = {
            callbacks?.onDismissNotice?.invoke(notice.id)
        },
        modifier = Modifier.testTag("activity_domain_notice_dialog"),
        properties = DialogProperties(
            dismissOnBackPress = callbacks != null,
            dismissOnClickOutside = callbacks != null,
        ),
        title = { Text(copy.title) },
        text = { Text(copy.body) },
        confirmButton = {
            if (callbacks != null) {
                TextButton(
                    onClick = { callbacks.onDismissNotice(notice.id) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("activity_notice_dismiss"),
                ) {
                    Text(stringResource(R.string.activity_notice_dismiss))
                }
            }
        },
    )
}

@Composable
private fun DomainActionNotice(
    notice: DomainActionNoticeUi,
    callbacks: DomainNoticeCallbacks?,
    inline: Boolean,
) {
    val copy = noticeCopy(notice.content)
    LavaPanel(
        modifier = Modifier.testTag(
            if (inline) "activity_domain_notice" else "activity_domain_notice_dialog",
        ),
    ) {
        Text(copy.title, fontWeight = FontWeight.SemiBold)
        Text(copy.body)
        if (callbacks != null) {
            TextButton(
                onClick = { callbacks.onDismissNotice(notice.id) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_notice_dismiss"),
            ) {
                Text(stringResource(R.string.activity_notice_dismiss))
            }
        }
    }
}

private data class NoticeCopy(val title: String, val body: String)

@Composable
private fun noticeCopy(content: DomainActionNoticeContentUi): NoticeCopy = when (content) {
    DomainActionNoticeContentUi.UnsavedFilterEdits -> NoticeCopy(
        stringResource(R.string.activity_notice_unsaved_title),
        stringResource(R.string.activity_notice_unsaved_body),
    )
    is DomainActionNoticeContentUi.ValidationFailed -> when (content.reason) {
        DomainActionValidationUi.InvalidDomain -> NoticeCopy(
            stringResource(R.string.activity_notice_invalid_title),
            stringResource(R.string.activity_notice_invalid_body),
        )
        DomainActionValidationUi.AlreadyAllowed -> NoticeCopy(
            stringResource(R.string.activity_notice_already_allowed_title),
            stringResource(R.string.activity_notice_already_allowed_body),
        )
        DomainActionValidationUi.AlreadyBlocked -> NoticeCopy(
            stringResource(R.string.activity_notice_already_blocked_title),
            stringResource(R.string.activity_notice_already_blocked_body),
        )
        DomainActionValidationUi.AllowedLimitReached -> NoticeCopy(
            stringResource(R.string.activity_notice_allowed_limit_title),
            stringResource(R.string.activity_notice_allowed_limit_body),
        )
        DomainActionValidationUi.BlockedLimitReached -> NoticeCopy(
            stringResource(R.string.activity_notice_blocked_limit_title),
            stringResource(R.string.activity_notice_blocked_limit_body),
        )
        DomainActionValidationUi.RuleNotPresent -> NoticeCopy(
            stringResource(R.string.activity_notice_rule_missing_title),
            stringResource(R.string.activity_notice_rule_missing_body),
        )
        DomainActionValidationUi.RuleRejected -> NoticeCopy(
            stringResource(R.string.activity_notice_rule_rejected_title),
            stringResource(R.string.activity_notice_rule_rejected_body),
        )
    }
    is DomainActionNoticeContentUi.AuthenticationFailed -> NoticeCopy(
        stringResource(R.string.activity_notice_authentication_title),
        content.message,
    )
    is DomainActionNoticeContentUi.ReviewRejected -> NoticeCopy(
        stringResource(R.string.activity_notice_review_rejected_title),
        content.message,
    )
    is DomainActionNoticeContentUi.ApplyRejected -> NoticeCopy(
        stringResource(R.string.activity_notice_apply_rejected_title),
        content.message,
    )
    is DomainActionNoticeContentUi.Failed -> NoticeCopy(
        stringResource(R.string.activity_notice_failed_title),
        content.message,
    )
    DomainActionNoticeContentUi.Busy -> NoticeCopy(
        stringResource(R.string.activity_notice_busy_title),
        stringResource(R.string.activity_notice_busy_body),
    )
    DomainActionNoticeContentUi.ReviewUnavailable -> NoticeCopy(
        stringResource(R.string.activity_notice_unavailable_title),
        stringResource(R.string.activity_notice_unavailable_body),
    )
    DomainActionNoticeContentUi.Superseded -> NoticeCopy(
        stringResource(R.string.activity_notice_superseded_title),
        stringResource(R.string.activity_notice_superseded_body),
    )
}

@Composable
private fun domainActionCopy(action: DomainActionUi): String = stringResource(
    when (action) {
        DomainActionUi.Allow -> R.string.activity_action_allow
        DomainActionUi.Block -> R.string.activity_action_block
        DomainActionUi.Remove -> R.string.activity_action_remove
    },
)

private fun DomainActionUiState.reviewOrNull(): DomainActionReviewUi? = when (this) {
    is DomainActionUiState.Presenting -> review
    is DomainActionUiState.Handoff -> review
    DomainActionUiState.Idle -> null
    is DomainActionUiState.ReviewPending -> review
    is DomainActionUiState.Applying -> review
    is DomainActionUiState.ApplyFailed -> review
}
