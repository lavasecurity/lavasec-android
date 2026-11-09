package com.lavasec.app.presentation.guard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaNavigationCard
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.platform.SettingsRoute
import com.lavasec.app.presentation.GuardActionDisabledUi
import com.lavasec.app.presentation.GuardConnectivityUi
import com.lavasec.app.presentation.GuardLifecycleUi
import com.lavasec.app.presentation.GuardMessageUi
import com.lavasec.app.presentation.GuardPrimaryAction
import com.lavasec.app.presentation.GuardProtectionHealthUi
import com.lavasec.app.presentation.R
import com.lavasec.app.presentation.mascot.GuardianMascotState
import com.lavasec.app.presentation.mascot.GuardianMascotView

/**
 * Pinned slice-02 Guard geometry, verbatim from the iOS source:
 *   lavasec-ios/LavaSecApp/GuardView.swift `ProtectionStatusMetrics`
 * (96-point guardian, 300-point primary action maximum width, 56-point primary
 * action height) plus the 10-point action-to-message VStack spacing of the same
 * panel. The 48dp minimum touch target is Compose's minimum interactive size.
 */
internal object GuardMetrics {
    val mascotSize = 96.dp
    val primaryActionHeight = 56.dp
    val primaryActionMaxWidth = 300.dp
    val actionToMessageSpacing = 10.dp

    /**
     * Below this available panel width the status text stacks under the mascot
     * instead of sitting beside it; the guardian stays exactly 96dp either way.
     */
    val headerStackedMaxWidth = 320.dp
}

/**
 * The temporary-pause menu contract: every [PauseDuration] the foundation owner
 * supports, the current deadline (null while protection is simply pausable), and
 * the exact pause dispatch callback. The screen derives this only when the
 * protection state is pausable, so the affordance is absent otherwise.
 */
data class GuardPauseMenuUi(
    val durations: List<PauseDuration>,
    val currentDeadlineEpochMillis: Long?,
    val onPauseSelected: (PauseDuration) -> Unit,
)

/**
 * Clock-independent pause-deadline formatter: pure function of the deadline and
 * the injected now, ceil-rounded to whole minutes. Expired (or exactly-due)
 * deadlines collapse to zero minutes; the screen renders the zero case as the
 * dedicated "resume is due" resource and every positive case through the plural.
 */
internal fun pauseDeadlineMinutes(deadlineEpochMillis: Long, nowEpochMillis: Long): Long {
    val remainingMillis = (deadlineEpochMillis - nowEpochMillis).coerceAtLeast(0L)
    return (remainingMillis + MILLIS_PER_MINUTE - 1L) / MILLIS_PER_MINUTE
}

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * Tracks the settled status value so only DISTINCT transitions are announced:
 * the first composition records the value silently (iOS `.onChange` never fires
 * for the initial value) and identical re-emissions (the Guard poll re-reading
 * the same state) return null.
 */
internal class GuardStatusAnnouncementTracker {
    private var lastAnnounced: String? = null

    /** Returns the value to announce, or null when it repeats the settled status. */
    fun distinctSettledChange(value: String): String? {
        val previous = lastAnnounced
        lastAnnounced = value
        if (previous == null || previous == value) return null
        return value
    }
}

@Composable
fun GuardScreen(
    health: GuardProtectionHealthUi,
    onOpenFilters: () -> Unit,
    onOpenActivity: () -> Unit,
    onPrimaryAction: ((GuardPrimaryAction) -> Unit)?,
    modifier: Modifier = Modifier,
    onPause: ((PauseDuration) -> Unit)? = null,
    disabledReason: GuardActionDisabledUi? = null,
    error: String? = null,
) {
    val title = stringResource(health.titleResource)
    val subtitle = guardSubtitle(health)
    val statusValue = stringResource(R.string.guard_status_accessibility_value, title, subtitle)

    // iOS ProtectionStatusPanel.onChange(of: title + " " + subtitle): announce the
    // settled status only when the composed value actually changes, so the Guard
    // poll (which re-reads the same state) never re-announces. The view's
    // announceForAccessibility already no-ops while Android accessibility is off;
    // the tracker keeps the change detection pure and testable.
    val view = LocalView.current
    val announcer = remember { GuardStatusAnnouncementTracker() }
    LaunchedEffect(statusValue) {
        announcer.distinctSettledChange(statusValue)?.let { pending ->
            view.announceForAccessibility(pending)
        }
    }

    // iOS shows the pause context menu on the primary action only while protection
    // is pausable; Android maps that to a long-press on the same control, offered
    // only when the state is pausable and the host owns a pause path.
    val pauseMenu = if (onPause != null && health.canPause) {
        GuardPauseMenuUi(
            durations = PauseDuration.entries,
            currentDeadlineEpochMillis = health.pause?.deadlineEpochMillis,
            onPauseSelected = onPause,
        )
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("guard_screen")
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        Text(
            text = stringResource(R.string.guard_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        LavaPanel(modifier = Modifier.testTag("guard_status_panel")) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < GuardMetrics.headerStackedMaxWidth) {
                    // Very narrow widths stack the text column under the guardian;
                    // the guardian itself stays exactly 96dp.
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(LavaSpacing.md),
                    ) {
                        GuardianMascotView(
                            guardianDescription = stringResource(
                                R.string.guardian_accessibility_label,
                            ),
                            state = health.toGuardianMascotState(),
                            size = GuardMetrics.mascotSize,
                        )
                        GuardStatusColumn(
                            title = title,
                            subtitle = subtitle,
                            statusLabel = stringResource(R.string.guard_status_accessibility_label),
                            statusValue = statusValue,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    // Horizontal status header, iOS HStack(alignment: .top, spacing: 16).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        GuardianMascotView(
                            guardianDescription = stringResource(
                                R.string.guardian_accessibility_label,
                            ),
                            state = health.toGuardianMascotState(),
                            size = GuardMetrics.mascotSize,
                        )
                        GuardStatusColumn(
                            title = title,
                            subtitle = subtitle,
                            statusLabel = stringResource(R.string.guard_status_accessibility_label),
                            statusValue = statusValue,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(GuardMetrics.actionToMessageSpacing),
            ) {
                val action = health.primaryAction
                if (action != null && onPrimaryAction != null) {
                    GuardPrimaryActionControl(
                        action = action,
                        label = stringResource(action.labelResource),
                        hint = pauseMenu?.let {
                            stringResource(R.string.guard_long_press_pause_hint)
                        },
                        disabledReason = disabledReason,
                        pauseMenu = pauseMenu,
                        onPrimaryAction = onPrimaryAction,
                    )
                }
                health.message?.let { message ->
                    Text(
                        text = stringResource(message.labelResource),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                disabledReason?.let { reason ->
                    Text(
                        text = stringResource(reason.explanationResource),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        LavaNavigationCard(
            title = stringResource(R.string.guard_filters_title),
            leadingIcon = LavaIcons.Filters,
            summary = stringResource(R.string.guard_filters_summary),
            onClick = onOpenFilters,
        )
        LavaNavigationCard(
            title = stringResource(R.string.guard_activity_title),
            leadingIcon = LavaIcons.Activity,
            summary = stringResource(R.string.guard_activity_summary),
            onClick = onOpenActivity,
        )
    }
}

/**
 * The product label, protection title, and subtitle merged into ONE stable
 * accessibility element: the label is the constant localized protection-status
 * name and the value is the composed settled status (iOS ProtectionStatusPanel
 * `.accessibilityElement(children: .ignore)` + label/value pair). Only distinct
 * settled changes are announced, driven by the caller's composed status value.
 */
@Composable
private fun GuardStatusColumn(
    title: String,
    subtitle: String,
    statusLabel: String,
    statusValue: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag("guard_status_text")
            .semantics(mergeDescendants = true) {
                contentDescription = statusLabel
                stateDescription = statusValue
            },
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.guard_product_label),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = com.lavasec.app.designsystem.LocalLavaSemanticColors.current.attentionText,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Guard primary action: 56dp visual height, 300dp maximum width, 48dp minimum
 * touch target (Compose minimum interactive size), and the iOS "Long-press for
 * pause options" caption when a pause menu exists. `combinedClickable` exposes
 * both the click and the long-press as semantics actions so the pause affordance
 * is reachable on touchscreens and keyboards alike.
 */
@Composable
private fun GuardPrimaryActionControl(
    action: GuardPrimaryAction,
    label: String,
    hint: String?,
    disabledReason: GuardActionDisabledUi?,
    pauseMenu: GuardPauseMenuUi?,
    onPrimaryAction: (GuardPrimaryAction) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val enabled = disabledReason == null
    val colors = MaterialTheme.colorScheme
    val actionHint = stringResource(R.string.guard_action_accessibility_hint)

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .widthIn(max = GuardMetrics.primaryActionMaxWidth)
                .fillMaxWidth()
                .heightIn(min = GuardMetrics.primaryActionHeight)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = label,
                    onLongClickLabel = hint,
                    onClick = { onPrimaryAction(action) },
                    onLongClick = if (pauseMenu == null) {
                        null
                    } else {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuExpanded = true
                        }
                    },
                )
                // One action-state element: the label and the pause hint merge into
                // the action, and the pinned iOS accessibilityHint
                // ("Controls Lava's local DNS protection.") rides as the state
                // description, exactly like GuardView.swift's button hint.
                .semantics(mergeDescendants = true) {
                    stateDescription = actionHint
                }
                .testTag("guard_primary_action"),
            shape = MaterialTheme.shapes.extraLarge,
            color = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.12f),
            contentColor = if (enabled) colors.onPrimary else colors.onSurface.copy(alpha = 0.38f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LavaSpacing.lg, vertical = LavaSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (enabled) {
                            colors.onPrimary.copy(alpha = 0.72f)
                        } else {
                            colors.onSurface.copy(alpha = 0.38f)
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            pauseMenu?.durations?.forEach { duration ->
                DropdownMenuItem(
                    text = { Text(stringResource(duration.labelResource)) },
                    onClick = {
                        menuExpanded = false
                        pauseMenu.onPauseSelected(duration)
                    },
                )
            }
        }
    }
}

@Composable
private fun guardSubtitle(health: GuardProtectionHealthUi): String = when {
    health.lifecycle == GuardLifecycleUi.PAUSED -> {
        val pause = checkNotNull(health.pause) { "paused lifecycle requires a pause deadline" }
        when (pause.remainingMinutes) {
            0L -> stringResource(R.string.guard_pause_resume_due)
            else -> pluralStringResource(
                R.plurals.guard_pause_resumes_in_minutes,
                pause.remainingMinutes.toInt(),
                pause.remainingMinutes,
            )
        }
    }

    else -> stringResource(health.subtitleResource)
}

/**
 * Typed mascot mapping (slice 02): the iOS GuardianView state machine with the
 * Android lifecycle vocabulary.
 *   - a pause override always wins (iOS isProtectionTemporarilyPaused);
 *   - connected/healthy severity drives awake, fallback keeps awake, recovering
 *     and network-unavailable retry, slow/needs-reconnect concern (iOS
 *     guardianState over protectionConnectivitySeverity);
 *   - connecting/reasserting-style transient states wake;
 *   - off (and unarmed default) sleeps;
 *   - Android attention states (settings-owned, reconnect-needed failures) map to
 *     concerned — the honest Android equivalent of the iOS needs-reconnect pose.
 * Only protected/healthy states reach AWAKE, so the mascot's awake tap
 * acknowledgement fires exactly when protection is healthy (iOS parity).
 */
internal fun GuardProtectionHealthUi.toGuardianMascotState(): GuardianMascotState = when {
    pause != null -> GuardianMascotState.PAUSED

    lifecycle == GuardLifecycleUi.PROTECTED -> when (connectivity) {
        GuardConnectivityUi.HEALTHY,
        GuardConnectivityUi.ENCRYPTED_FALLBACK,
        GuardConnectivityUi.DEVICE_DNS_FALLBACK,
        GuardConnectivityUi.PLAIN_DNS_FALLBACK,
        GuardConnectivityUi.UNAVAILABLE,
        -> GuardianMascotState.AWAKE

        GuardConnectivityUi.RECOVERING,
        GuardConnectivityUi.RECHECKING,
        GuardConnectivityUi.NETWORK_UNAVAILABLE,
        -> GuardianMascotState.RETRYING

        GuardConnectivityUi.DNS_SLOW,
        GuardConnectivityUi.INTERNET_UNVERIFIED,
        GuardConnectivityUi.NEEDS_RECONNECT,
        -> GuardianMascotState.CONCERNED
    }

    lifecycle == GuardLifecycleUi.INACTIVE -> GuardianMascotState.SLEEPING

    lifecycle == GuardLifecycleUi.ATTENTION -> GuardianMascotState.CONCERNED

    else -> GuardianMascotState.WAKING
}

internal val GuardProtectionHealthUi.titleResource: Int
    get() = when (lifecycle) {
        GuardLifecycleUi.INACTIVE -> R.string.guard_status_off_title
        GuardLifecycleUi.STARTING -> R.string.guard_status_starting_title
        GuardLifecycleUi.AWAITING_NETWORK -> R.string.guard_status_awaiting_network_title
        GuardLifecycleUi.RECONNECTING -> R.string.guard_status_reconnecting_title
        GuardLifecycleUi.PROTECTED -> when (connectivity) {
            GuardConnectivityUi.HEALTHY,
            GuardConnectivityUi.ENCRYPTED_FALLBACK,
            GuardConnectivityUi.DEVICE_DNS_FALLBACK,
        GuardConnectivityUi.PLAIN_DNS_FALLBACK,
            GuardConnectivityUi.UNAVAILABLE,
            -> R.string.guard_status_protected_title

            GuardConnectivityUi.RECOVERING -> R.string.guard_status_reconnecting_title
            GuardConnectivityUi.RECHECKING -> R.string.guard_status_rechecking_title
            GuardConnectivityUi.INTERNET_UNVERIFIED -> R.string.guard_status_internet_unverified_title
            GuardConnectivityUi.NETWORK_UNAVAILABLE -> R.string.guard_status_awaiting_network_title
            GuardConnectivityUi.DNS_SLOW -> R.string.guard_status_dns_slow_title
            GuardConnectivityUi.NEEDS_RECONNECT -> R.string.guard_status_reconnect_needed_title
        }

        GuardLifecycleUi.PAUSED -> R.string.guard_status_paused_title
        GuardLifecycleUi.STOPPING -> R.string.guard_status_stopping_title
        GuardLifecycleUi.ATTENTION -> when (val action = primaryAction) {
            is GuardPrimaryAction.OpenSettings -> when (action.route) {
                SettingsRoute.VpnSettings -> R.string.guard_settings_vpn_title
                SettingsRoute.NetworkSettings -> R.string.guard_settings_network_title
                SettingsRoute.NotificationSettings -> R.string.guard_settings_notifications_title
            }

            else -> R.string.guard_status_reconnect_needed_title
        }
    }

internal val GuardProtectionHealthUi.subtitleResource: Int
    get() = when (lifecycle) {
        GuardLifecycleUi.INACTIVE -> R.string.guard_status_off_subtitle
        GuardLifecycleUi.STARTING -> R.string.guard_status_starting_subtitle
        GuardLifecycleUi.AWAITING_NETWORK -> R.string.guard_status_awaiting_network_subtitle
        GuardLifecycleUi.RECONNECTING -> R.string.guard_status_reconnecting_subtitle
        GuardLifecycleUi.PROTECTED -> when (connectivity) {
            GuardConnectivityUi.HEALTHY -> R.string.guard_status_healthy_subtitle
            GuardConnectivityUi.ENCRYPTED_FALLBACK ->
                R.string.guard_status_encrypted_fallback_subtitle
            GuardConnectivityUi.PLAIN_DNS_FALLBACK -> R.string.guard_status_plain_fallback_subtitle
            GuardConnectivityUi.DEVICE_DNS_FALLBACK ->
                R.string.guard_status_device_fallback_subtitle
            GuardConnectivityUi.UNAVAILABLE -> R.string.guard_status_health_unavailable_subtitle
            GuardConnectivityUi.RECOVERING -> R.string.guard_status_recovering_subtitle
            GuardConnectivityUi.RECHECKING -> R.string.guard_status_rechecking_subtitle
            GuardConnectivityUi.INTERNET_UNVERIFIED -> R.string.guard_status_internet_unverified_subtitle
            GuardConnectivityUi.NETWORK_UNAVAILABLE ->
                R.string.guard_status_network_resume_subtitle
            GuardConnectivityUi.DNS_SLOW -> R.string.guard_status_dns_slow_subtitle
            GuardConnectivityUi.NEEDS_RECONNECT ->
                R.string.guard_status_reconnect_needed_subtitle
        }

        GuardLifecycleUi.PAUSED -> error("pause subtitle uses the plural formatter")
        GuardLifecycleUi.STOPPING -> R.string.guard_status_stopping_subtitle
        GuardLifecycleUi.ATTENTION -> when (val action = primaryAction) {
            is GuardPrimaryAction.OpenSettings -> when (action.route) {
                SettingsRoute.VpnSettings -> R.string.guard_settings_vpn_subtitle
                SettingsRoute.NetworkSettings -> R.string.guard_settings_network_subtitle
                SettingsRoute.NotificationSettings ->
                    R.string.guard_settings_notifications_subtitle
            }

            else -> R.string.guard_status_reconnect_attention_subtitle
        }
    }

private val GuardPrimaryAction.labelResource: Int
    get() = when (this) {
        GuardPrimaryAction.TurnOn -> R.string.guard_action_turn_on
        GuardPrimaryAction.TurnOff -> R.string.guard_action_turn_off
        GuardPrimaryAction.Resume -> R.string.guard_action_resume
        GuardPrimaryAction.Reconnect -> R.string.guard_action_reconnect
        is GuardPrimaryAction.OpenSettings -> R.string.guard_action_open_settings
    }

internal val GuardMessageUi.labelResource: Int
    get() = when (this) {
        GuardMessageUi.ALWAYS_ON_MANAGED -> R.string.guard_message_always_on
        GuardMessageUi.OWNERSHIP_UNVERIFIED_API_28 -> R.string.guard_message_ownership_unverified
        GuardMessageUi.VPN_PERMISSION_REVOKED -> R.string.guard_message_vpn_permission_revoked
    }

internal val GuardActionDisabledUi.explanationResource: Int
    get() = when (this) {
        GuardActionDisabledUi.COMMAND_IN_FLIGHT -> R.string.guard_action_disabled_in_flight
        GuardActionDisabledUi.RECONSTRUCTION_REQUIRED ->
            R.string.guard_action_disabled_reconstruction
    }

private val PauseDuration.labelResource: Int
    get() = when (this) {
        PauseDuration.FIVE_MINUTES -> R.string.guard_pause_duration_5_minutes
        PauseDuration.TEN_MINUTES -> R.string.guard_pause_duration_10_minutes
        PauseDuration.FIFTEEN_MINUTES -> R.string.guard_pause_duration_15_minutes
    }
