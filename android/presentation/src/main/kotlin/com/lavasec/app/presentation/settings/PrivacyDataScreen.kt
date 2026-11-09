package com.lavasec.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.presentation.R

/**
 * Typed Privacy & data route state: the retention switches, the export
 * lifecycle, and the clear scopes. Everything here is derived from the same
 * privacy owner the Settings root summary reads — the route never duplicates
 * the owner, and every destructive action still runs behind the privacy fence.
 */
data class PrivacyDataUiState(
    val logging: LoggingSettings?,
    val inFlight: Boolean,
    val exportReady: Boolean,
    val error: String?,
)

data class PrivacyDataCallbacks(
    val onLoggingChange: (LoggingSettings) -> Unit,
    val onClearLocalActivity: () -> Unit,
    val onExportLocalActivity: () -> Unit,
    val onShareLocalExport: () -> Unit,
    val onClearDiagnostics: () -> Unit,
)

/**
 * Privacy & data route (iOS PrivacySecuritySettingsView anchor): the retention
 * toggles, local export/share, and the destructive clears that Slice 04 moves
 * out of the Settings root. The confirmation dialogs preserve the exact
 * clear/export fence semantics of the previous inline surface.
 */
@Composable
fun PrivacyDataScreen(
    state: PrivacyDataUiState,
    callbacks: PrivacyDataCallbacks,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDiagnosticsClear by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("privacy_data_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.settings_privacy_data_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        LavaPanel {
            Text(
                text = stringResource(R.string.privacy_data_retention_detail),
                style = LavaScreenTypography.rowSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LavaSection(title = stringResource(R.string.privacy_data_retention_section)) {
            state.logging?.let { logging ->
                LoggingToggle(
                    title = stringResource(R.string.settings_keep_counts),
                    checked = logging.keepCounts,
                    enabled = !state.inFlight,
                    switchTag = "settings_keep_counts_switch",
                    onCheckedChange = {
                        callbacks.onLoggingChange(logging.copy(keepCounts = it))
                    },
                )
                LoggingToggle(
                    title = stringResource(R.string.settings_keep_history),
                    checked = logging.keepHistory,
                    enabled = !state.inFlight,
                    switchTag = "settings_keep_history_switch",
                    onCheckedChange = {
                        callbacks.onLoggingChange(logging.copy(keepHistory = it))
                    },
                )
                LoggingToggle(
                    title = stringResource(R.string.settings_keep_network_activity),
                    checked = logging.keepNetworkActivity,
                    enabled = !state.inFlight,
                    switchTag = "settings_keep_network_activity_switch",
                    onCheckedChange = {
                        callbacks.onLoggingChange(logging.copy(keepNetworkActivity = it))
                    },
                )
            }
            state.error?.let { error ->
                LavaPanel(modifier = Modifier.testTag("privacy_data_error_panel")) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        LavaSection(title = stringResource(R.string.privacy_data_export_section)) {
            Button(
                onClick = callbacks.onExportLocalActivity,
                enabled = !state.inFlight,
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("settings_export_activity"),
            ) {
                Text(stringResource(R.string.settings_export_activity))
            }
            if (state.exportReady) {
                OutlinedButton(
                    onClick = callbacks.onShareLocalExport,
                    enabled = !state.inFlight,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("settings_share_export"),
                ) {
                    Text(stringResource(R.string.settings_share_export))
                }
            }
        }
        LavaSection(title = stringResource(R.string.privacy_data_clear_section)) {
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = !state.inFlight,
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("settings_clear_activity"),
            ) {
                Text(stringResource(R.string.settings_clear_activity))
            }
            OutlinedButton(
                onClick = { confirmDiagnosticsClear = true },
                enabled = !state.inFlight,
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("settings_clear_diagnostics"),
            ) {
                Text(stringResource(R.string.settings_clear_diagnostics))
            }
        }
    }
    if (confirmClear) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_clear_activity_confirm_title),
            detail = stringResource(R.string.settings_clear_activity_confirm_detail),
            onConfirm = {
                confirmClear = false
                callbacks.onClearLocalActivity()
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmDiagnosticsClear) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_clear_diagnostics_confirm_title),
            detail = stringResource(R.string.settings_clear_diagnostics_confirm_detail),
            onConfirm = {
                confirmDiagnosticsClear = false
                callbacks.onClearDiagnostics()
            },
            onDismiss = { confirmDiagnosticsClear = false },
        )
    }
}

@Composable
private fun LoggingToggle(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    switchTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    LavaPanel {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(title, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.testTag(switchTag),
            )
        }
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    detail: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.settings_confirm)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}
