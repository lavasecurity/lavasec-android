package com.lavasec.app.presentation.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSection
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.foundation.feedback.FeedbackDraft
import com.lavasec.app.foundation.feedback.FeedbackTopic
import com.lavasec.app.presentation.R

/**
 * The iOS BugReportIssueType topics, in the iOS picker order. The label
 * resources are pinned iOS copy; no topic carries free-form user input.
 */
enum class BugReportTopicUi(@StringRes val labelRes: Int) {
    WEBSITE_ACCESS(R.string.bug_report_topic_website_access),
    VPN_OR_FILTER(R.string.bug_report_topic_vpn_or_filter),
    FEATURE(R.string.bug_report_topic_feature),
    TRANSLATION(R.string.bug_report_topic_translation),
    SUGGESTION(R.string.bug_report_topic_suggestion),
    OTHER(R.string.bug_report_topic_other),
}

enum class BugReportStepUi {
    TOPIC,
    DETAILS,
    ATTACHMENTS,
    CONSENT,
    SUBMITTED,
}

/** Typed failure that the screen maps to copy; never carries raw draft text. */
enum class BugReportFailureUi {
    DRAFT_FAILED,
    SEND_FAILED,
    RATE_LIMITED,
}

/**
 * The full typed bug-report state: topic, description, the previewed
 * attachment inventory, the explicit diagnostics consent, and the submit
 * lifecycle. Transitions are pure (see the extensions) so the flow and its
 * reviewed content are testable without a device.
 */
data class BugReportUiState(
    val step: BugReportStepUi = BugReportStepUi.TOPIC,
    val topic: BugReportTopicUi? = null,
    val description: String = "",
    val includeDiagnostics: Boolean = false,
    val affectedSite: String = "",
    val contactEmail: String = "",
    val reviewedPreview: String? = null,
    val consentGranted: Boolean = false,
    val submitting: Boolean = false,
    val failure: BugReportFailureUi? = null,
) {
    val draft: FeedbackDraft get() = FeedbackDraft(
        topic = topic?.let { when (it) {
            BugReportTopicUi.WEBSITE_ACCESS -> FeedbackTopic.WEBSITE_ACCESS
            BugReportTopicUi.VPN_OR_FILTER -> FeedbackTopic.VPN_OR_FILTER_ISSUE
            BugReportTopicUi.FEATURE -> FeedbackTopic.FEATURE_ISSUE
            BugReportTopicUi.TRANSLATION -> FeedbackTopic.TRANSLATION_ISSUE
            BugReportTopicUi.SUGGESTION -> FeedbackTopic.SUGGESTION
            BugReportTopicUi.OTHER -> FeedbackTopic.OTHER
        } },
        affectedSite = affectedSite, details = description, contactEmail = contactEmail,
    )
    val descriptionPreview: String get() = draft.normalizedDetails

    fun canAdvance(): Boolean = when (step) {
        BugReportStepUi.TOPIC -> topic != null
        BugReportStepUi.DETAILS -> draft.isReadyForReview
        BugReportStepUi.ATTACHMENTS,
        BugReportStepUi.CONSENT,
        -> true
        BugReportStepUi.SUBMITTED -> false
    }

    fun canSubmit(): Boolean =
        step == BugReportStepUi.CONSENT && draft.isReadyForReview &&
            reviewedPreview != null && consentGranted && !submitting

    companion object {
        const val MAX_DESCRIPTION_LENGTH = 5_000
    }
}

// The pure step/draft transitions. The ViewModel applies these to the typed
// state; no transition here touches the device or the privacy owner, so the
// whole flow is unit-testable.
fun BugReportUiState.selectTopic(topic: BugReportTopicUi): BugReportUiState =
    copy(topic = topic, affectedSite = if (topic == this.topic) affectedSite else "", consentGranted = false, reviewedPreview = null, failure = null)

fun BugReportUiState.setDescription(text: String): BugReportUiState =
    copy(description = text, consentGranted = false, reviewedPreview = null, failure = null)

fun BugReportUiState.setDiagnosticsConsent(enabled: Boolean): BugReportUiState =
    copy(includeDiagnostics = enabled, consentGranted = false, reviewedPreview = null, failure = null)

fun BugReportUiState.setAffectedSite(text: String): BugReportUiState =
    copy(affectedSite = text, consentGranted = false, reviewedPreview = null, failure = null)

fun BugReportUiState.setContactEmail(text: String): BugReportUiState =
    copy(contactEmail = text, consentGranted = false, reviewedPreview = null, failure = null)

fun BugReportUiState.setConsentGranted(granted: Boolean): BugReportUiState =
    copy(consentGranted = granted)

fun BugReportUiState.advance(): BugReportUiState = when {
    !canAdvance() -> this
    step == BugReportStepUi.TOPIC -> copy(step = BugReportStepUi.DETAILS)
    step == BugReportStepUi.DETAILS -> copy(step = BugReportStepUi.ATTACHMENTS)
    step == BugReportStepUi.ATTACHMENTS -> copy(step = BugReportStepUi.CONSENT)
    else -> this
}

fun BugReportUiState.backStep(): BugReportUiState = when (step) {
    BugReportStepUi.DETAILS -> copy(step = BugReportStepUi.TOPIC)
    BugReportStepUi.ATTACHMENTS -> copy(step = BugReportStepUi.DETAILS)
    BugReportStepUi.CONSENT -> copy(step = BugReportStepUi.ATTACHMENTS, consentGranted = false)
    else -> this
}

fun BugReportUiState.discard(): BugReportUiState =
    BugReportUiState()

fun BugReportUiState.markSubmitting(): BugReportUiState = copy(submitting = true, failure = null)

fun BugReportUiState.markSubmitted(): BugReportUiState =
    copy(step = BugReportStepUi.SUBMITTED, submitting = false, failure = null)

fun BugReportUiState.markFailed(failure: BugReportFailureUi): BugReportUiState =
    copy(submitting = false, failure = failure)

/** Submitted only after reviewing the native captured report. */
data class BugReportSubmission(
    val topic: BugReportTopicUi,
    val description: String,
    val includeDiagnostics: Boolean,
    val affectedSite: String,
    val contactEmail: String,
)

data class BugReportCallbacks(
    val onTopicSelected: (BugReportTopicUi) -> Unit,
    val onDescriptionChanged: (String) -> Unit,
    val onDiagnosticsConsentChanged: (Boolean) -> Unit,
    val onAffectedSiteChanged: (String) -> Unit,
    val onContactEmailChanged: (String) -> Unit,
    val onConsentGrantedChanged: (Boolean) -> Unit,
    val onAdvance: () -> Unit,
    val onBack: () -> Unit,
    val onSubmit: () -> Unit,
    val onDiscard: () -> Unit,
    val onDone: () -> Unit,
)

/** Native report review and consent. Only a server receipt advances to SUBMITTED. */
@Composable
fun BugReportScreen(
    state: BugReportUiState,
    callbacks: BugReportCallbacks,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("bug_report_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        if (state.step == BugReportStepUi.SUBMITTED) {
            Text(
                text = stringResource(R.string.bug_report_submitted_title),
                style = LavaScreenTypography.screenTitle,
                modifier = Modifier.semantics { heading() },
            )
            LavaPanel {
                Text(
                    text = stringResource(R.string.bug_report_submitted_detail),
                    style = LavaScreenTypography.rowSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = callbacks.onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("bug_report_done"),
            ) {
                Text(stringResource(R.string.bug_report_done))
            }
            return@Column
        }
        IconButton(enabled = !state.submitting, onClick = { confirmDiscard = true }) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.settings_bug_report_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        when (state.step) {
            BugReportStepUi.TOPIC -> TopicStep(state, callbacks)
            BugReportStepUi.DETAILS -> DetailsStep(state, callbacks)
            BugReportStepUi.ATTACHMENTS -> AttachmentsStep(state, callbacks)
            BugReportStepUi.CONSENT -> ConsentStep(state, callbacks)
            BugReportStepUi.SUBMITTED -> Unit
        }
        StepControls(state, callbacks)
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.bug_report_discard_title)) },
            text = { Text(stringResource(R.string.bug_report_discard_detail)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDiscard = false
                        callbacks.onDiscard()
                        // The back arrow owns this dialog: after discarding,
                        // leave the route so the user returns to Settings.
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.bug_report_discard_action))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun TopicStep(state: BugReportUiState, callbacks: BugReportCallbacks) {
    LavaPanel {
        Text(
            text = stringResource(R.string.bug_report_no_silent_telemetry),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.bug_report_no_silent_telemetry_detail),
            style = LavaScreenTypography.rowSummary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LavaSection(title = stringResource(R.string.bug_report_choose_topic)) {
        BugReportTopicUi.entries.forEach { topic ->
            TopicRow(
                topic = topic,
                selected = state.topic == topic,
                onClick = { callbacks.onTopicSelected(topic) },
            )
        }
    }
}

@Composable
private fun DetailsStep(state: BugReportUiState, callbacks: BugReportCallbacks) {
    LavaSection(title = stringResource(R.string.bug_report_details_section)) {
        if (state.topic == BugReportTopicUi.WEBSITE_ACCESS) {
            OutlinedTextField(
                value = state.affectedSite,
                onValueChange = callbacks.onAffectedSiteChanged,
                label = { Text(stringResource(R.string.bug_report_affected_site)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bug_report_affected_site"),
            )
        }
        OutlinedTextField(
            value = state.description,
            onValueChange = { callbacks.onDescriptionChanged(it) },
            label = { Text(stringResource(R.string.bug_report_details)) },
            placeholder = { Text(stringResource(R.string.bug_report_details_placeholder)) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("bug_report_description"),
        )
        OutlinedTextField(
            value = state.contactEmail,
            onValueChange = callbacks.onContactEmailChanged,
            label = { Text(stringResource(R.string.bug_report_contact_email)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("bug_report_contact_email"),
        )
    }
}

@Composable
private fun AttachmentsStep(state: BugReportUiState, callbacks: BugReportCallbacks) {
    LavaSection(title = stringResource(R.string.bug_report_attachments_section)) {
        LavaPanel {
            Text(
                text = stringResource(R.string.bug_report_attachments_inventory_title),
                style = MaterialTheme.typography.titleMedium,
            )
            InventoryLine(
                label = stringResource(R.string.bug_report_attachments_report_body),
                value = stringResource(R.string.bug_report_attachments_redacted),
            )
            InventoryLine(
                label = stringResource(R.string.bug_report_attachments_app_device),
                value = stringResource(if (state.includeDiagnostics) R.string.bug_report_attachments_included else R.string.bug_report_attachments_not_included),
            )
            InventoryLine(
                label = stringResource(R.string.bug_report_attachments_diagnostics),
                value = stringResource(
                    if (state.includeDiagnostics) {
                        R.string.bug_report_attachments_included
                    } else {
                        R.string.bug_report_attachments_not_included
                    },
                ),
            )
        }
        ConsentToggle(
            title = stringResource(R.string.bug_report_attachments_diagnostics_toggle),
            checked = state.includeDiagnostics,
            testTag = "bug_report_include_diagnostics",
            onCheckedChange = callbacks.onDiagnosticsConsentChanged,
        )
        LavaPanel(modifier = Modifier.testTag("bug_report_redaction_note")) {
            Text(
                text = stringResource(R.string.bug_report_attachments_redaction_note),
                style = LavaScreenTypography.rowSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LavaPanel(modifier = Modifier.testTag("bug_report_redacted_preview")) {
            Text(
                text = stringResource(R.string.bug_report_attachments_redacted_preview),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.descriptionPreview,
                style = LavaScreenTypography.rowSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConsentStep(state: BugReportUiState, callbacks: BugReportCallbacks) {
    LavaSection(title = stringResource(R.string.bug_report_consent_section)) {
        state.reviewedPreview?.let { preview ->
            LavaPanel { Text(preview, style = LavaScreenTypography.rowSummary) }
        }
        LavaPanel {
            Text(
                text = stringResource(R.string.bug_report_consent_detail),
                style = LavaScreenTypography.rowSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ConsentToggle(
            title = stringResource(R.string.bug_report_consent_toggle),
            checked = state.consentGranted,
            testTag = "bug_report_consent_checkbox",
            onCheckedChange = { if (!state.submitting) callbacks.onConsentGrantedChanged(it) },
        )
        state.failure?.let { failure ->
            LavaPanel(modifier = Modifier.testTag("bug_report_failure_panel")) {
                Text(
                    text = stringResource(R.string.bug_report_failed_title),
                    style = LavaScreenTypography.rowTitle,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(
                        when (failure) {
                            BugReportFailureUi.DRAFT_FAILED ->
                                R.string.bug_report_failed_detail
                            BugReportFailureUi.SEND_FAILED -> R.string.bug_report_send_failed
                            BugReportFailureUi.RATE_LIMITED -> R.string.bug_report_rate_limited
                        },
                    ),
                    style = LavaScreenTypography.rowSummary,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StepControls(state: BugReportUiState, callbacks: BugReportCallbacks) {
    if (state.step == BugReportStepUi.CONSENT) {
        Button(
            onClick = callbacks.onSubmit,
            enabled = state.canSubmit(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("bug_report_submit"),
        ) {
            Text(
                stringResource(
                    if (state.submitting) R.string.bug_report_submitting else R.string.bug_report_submit,
                ),
            )
        }
        OutlinedButton(
            onClick = callbacks.onBack,
            enabled = !state.submitting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("bug_report_back"),
        ) {
            Text(stringResource(R.string.onboarding_back))
        }
        return
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
        if (state.step != BugReportStepUi.TOPIC) {
            OutlinedButton(
                onClick = callbacks.onBack,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("bug_report_back"),
            ) {
                Text(stringResource(R.string.onboarding_back))
            }
        }
        Button(
            onClick = callbacks.onAdvance,
            enabled = state.canAdvance(),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("bug_report_continue"),
        ) {
            Text(
                stringResource(
                    if (state.step == BugReportStepUi.ATTACHMENTS) {
                        R.string.bug_report_review
                    } else {
                        R.string.onboarding_continue
                    },
                ),
            )
        }
    }
}

@Composable
private fun TopicRow(
    topic: BugReportTopicUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    LavaPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .testTag(topic.testTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(topic.labelRes),
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = LavaIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun InventoryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = LavaScreenTypography.rowSummary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = LavaScreenTypography.rowSummary)
    }
}

@Composable
private fun ConsentToggle(
    title: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    LavaPanel {
        Row(modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(testTag),
            )
            Text(title, modifier = Modifier.weight(1f).alignByBaseline(), style = LavaScreenTypography.rowSummary)
        }
    }
}

internal val BugReportTopicUi.testTag: String
    get() = when (this) {
        BugReportTopicUi.WEBSITE_ACCESS -> "bug_report_topic_website_access"
        BugReportTopicUi.VPN_OR_FILTER -> "bug_report_topic_vpn_or_filter"
        BugReportTopicUi.FEATURE -> "bug_report_topic_feature"
        BugReportTopicUi.TRANSLATION -> "bug_report_topic_translation"
        BugReportTopicUi.SUGGESTION -> "bug_report_topic_suggestion"
        BugReportTopicUi.OTHER -> "bug_report_topic_other"
    }
