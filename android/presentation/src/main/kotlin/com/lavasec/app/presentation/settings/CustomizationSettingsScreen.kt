package com.lavasec.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.lavasec.app.presentation.R
import com.lavasec.app.presentation.mascot.GuardianMascotState
import com.lavasec.app.presentation.mascot.GuardianMascotView

/**
 * Typed customization facts. [guardianPreview] uses the exact
 * [GuardianMascotState] enum Guard renders with — there is no second mascot
 * state machine on Android. The notification choices expose the real Android
 * channel status; a blocked channel routes to system settings.
 */
data class CustomizationUiState(
    val guardianPreview: GuardianMascotState = GuardianMascotState.AWAKE,
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true,
    val notificationsAllowed: Boolean? = null,
    val notificationsBlocked: Boolean = false,
    val inFlight: Boolean = false,
    val error: String? = null,
)

data class CustomizationCallbacks(
    val onReduceMotionChanged: (Boolean) -> Unit,
    val onHapticsChanged: (Boolean) -> Unit,
    val onOpenNotificationSettings: () -> Unit,
)

/**
 * Customization route (iOS CustomizationSettingsView anchor). Ships the
 * Android-supported honest subset: the guardian preview (same state enum as
 * Guard), reduced motion, app haptics, and the notification channel status
 * with a route to system settings when the OS has blocked notifications.
 */
@Composable
fun CustomizationSettingsScreen(
    state: CustomizationUiState,
    callbacks: CustomizationCallbacks?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("customization_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        // The persisted haptics choice is applied here: enabling it plays the
        // confirmation feedback so the user feels exactly what the setting
        // controls before it is saved through the owner.
        val haptic = LocalHapticFeedback.current
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.settings_customization_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        LavaPanel {
            Text(
                text = stringResource(R.string.customization_intro_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.customization_intro_detail),
                style = LavaScreenTypography.rowSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LavaSection(title = stringResource(R.string.customization_guardian_section)) {
            LavaPanel(modifier = Modifier.testTag("customization_guardian_panel")) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GuardianMascotView(
                        state = state.guardianPreview,
                        size = 96.dp,
                        animates = !state.reduceMotion,
                        guardianDescription = stringResource(R.string.guardian_accessibility_label),
                    )
                }
                Text(
                    text = stringResource(R.string.customization_guardian_look_title),
                    style = LavaScreenTypography.rowTitle,
                )
                Text(
                    text = stringResource(R.string.customization_guardian_look_detail),
                    style = LavaScreenTypography.rowSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LavaSection(title = stringResource(R.string.customization_appearance_section)) {
            CustomizationToggle(
                title = stringResource(R.string.customization_reduce_motion_title),
                detail = stringResource(R.string.customization_reduce_motion_detail),
                checked = state.reduceMotion,
                enabled = callbacks != null && !state.inFlight,
                switchTag = "customization_reduce_motion_switch",
                onCheckedChange = { callbacks?.onReduceMotionChanged(it) },
            )
            CustomizationToggle(
                title = stringResource(R.string.customization_haptics_title),
                detail = stringResource(R.string.customization_haptics_detail),
                checked = state.haptics,
                enabled = callbacks != null && !state.inFlight,
                switchTag = "customization_haptics_switch",
                onCheckedChange = { enabled ->
                    if (enabled) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    callbacks?.onHapticsChanged(enabled)
                },
            )
        }
        LavaSection(title = stringResource(R.string.customization_notifications_section)) {
            LavaPanel(modifier = Modifier.testTag("customization_notifications_panel")) {
                Text(
                    text = stringResource(
                        when {
                            state.notificationsAllowed == true ->
                                R.string.customization_notifications_allowed
                            state.notificationsAllowed == false ->
                                R.string.customization_notifications_blocked
                            else -> R.string.customization_notifications_unknown
                        },
                    ),
                    style = LavaScreenTypography.rowTitle,
                )
                // The ambient channel contract disclosed here (silent, lock-screen-hidden,
                // removed when protection is off): the channel itself is defined and
                // enforced in ProtectionAmbientNotificationFactory, and this app-wide
                // notification status covers it like every other channel.
                Text(
                    text = stringResource(R.string.customization_notifications_ambient_detail),
                    style = LavaScreenTypography.rowSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.notificationsAllowed == false) {
                    Text(
                        text = stringResource(R.string.customization_notifications_blocked_detail),
                        style = LavaScreenTypography.rowSummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.notificationsAllowed == false && callbacks != null) {
                Button(
                    onClick = callbacks.onOpenNotificationSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("customization_open_notification_settings"),
                ) {
                    Text(stringResource(R.string.customization_open_settings))
                }
            }
        }
        state.error?.let { error ->
            LavaPanel(modifier = Modifier.testTag("customization_error_panel")) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CustomizationToggle(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    switchTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    LavaPanel {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = LavaScreenTypography.rowTitle)
                Text(
                    detail,
                    style = LavaScreenTypography.rowSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.testTag(switchTag),
            )
        }
    }
}
