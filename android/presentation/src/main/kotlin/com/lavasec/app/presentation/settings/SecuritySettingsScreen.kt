package com.lavasec.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSection
import com.lavasec.app.designsystem.LavaSettingsIcons
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.foundation.security.LockTimeoutOption
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
import com.lavasec.app.presentation.R

/** Typed security failures; the screen maps these to copy, never raw messages. */
enum class SecurityFailureUi {
    WRONG_PASSCODE,
    SECURE_STORAGE,
    OPERATION_FAILED,
}

/**
 * Typed app-unlock state. The honest subset of the plan's SecurityUiState:
 * Keystore unavailable, passcode disabled, passcode enabled (with the unlock
 * session facts), and the recovery-failure state for an unreadable verifier.
 * [Enabled.requiresUnlock] is the one pure decision the shell and tests use to
 * decide which surfaces re-require the passcode.
 */
sealed interface SecurityUiState {
    /** Android Keystore could not be opened; no passcode controls exist. */
    data object Unavailable : SecurityUiState

    /** The saved verifier exists but cannot be read; the user must reset it. */
    data object RecoveryFailure : SecurityUiState

    data class Disabled(
        val lockTimeout: LockTimeoutOption,
    ) : SecurityUiState

    data class Enabled(
        val lastUnlockEpochMillis: Long?,
        /** The durable close signal for the change dialog (see SecurityOwnerState). */
        val lastChangeEpochMillis: Long?,
        val lockTimeout: LockTimeoutOption,
        val protectedSurfaces: Set<ProtectedSurface>,
        val inFlight: Boolean,
        val failure: SecurityFailureUi? = null,
        val authenticatedSurfaces: Set<ProtectedSurface> = emptySet(),
    ) : SecurityUiState {
        /** Only the current navigation turn grants access; legacy timestamps never authorize access. */
        @Suppress("UNUSED_PARAMETER")
        fun requiresUnlock(navigation: ProductNavigation, nowEpochMillis: Long): Boolean =
            (ProtectedSurface.APP_UNLOCK in protectedSurfaces &&
                ProtectedSurface.APP_UNLOCK !in authenticatedSurfaces) ||
                navigation.path.any { destination ->
                    protectedSurfaces.any { it.protects(destination) && it !in authenticatedSurfaces }
                }

    }
}

/**
 * The destination families a protected surface covers. Activity protects the
 * Activity root, its evidence children, Network Activity and diagnostics; Filters protects the Filters
 * root and every library/detail/review/preparation child.
 */
fun ProtectedSurface.protects(destination: ProductDestination): Boolean = when (this) {
    ProtectedSurface.PROTECTION_CONTROL, ProtectedSurface.PROTECTION_PAUSE -> false
    ProtectedSurface.ACTIVITY -> destination in setOf(
        ProductDestination.Activity,
        ProductDestination.TopDomains,
        ProductDestination.DomainHistory,
        ProductDestination.NetworkActivity,
        ProductDestination.NerdStats,
    )
    ProtectedSurface.FILTERS -> destination in setOf(
        ProductDestination.Filters,
        ProductDestination.FilterLibrary,
        ProductDestination.FilterDetail,
        ProductDestination.FilterReview,
        ProductDestination.FilterPreparation,
    )
    // Mirrors iOS `SettingsRoute.securityPolicy`: account, upgrade, customization, DNS and
    // privacy data require app settings; Security/Bug report/Legal notices stay read-only and
    // Nerd Stats/Network Activity keep their activityViewing gate above.
    ProtectedSurface.APP_SETTINGS -> destination in setOf(
        ProductDestination.Settings,
        ProductDestination.Account,
        ProductDestination.Upgrade,
        ProductDestination.Customization,
        ProductDestination.DnsResolver,
        ProductDestination.PrivacyData,
    )

    // The whole-app lock is not destination-scoped; the shell blocks every destination until it
    // is authenticated (see SecurityUiState.Enabled.requiresUnlock).
    ProtectedSurface.APP_UNLOCK -> false
}

internal val ProtectedSurface.destination: ProductDestination
    get() = when (this) {
        ProtectedSurface.ACTIVITY -> ProductDestination.Activity
        ProtectedSurface.FILTERS -> ProductDestination.Filters
        ProtectedSurface.PROTECTION_CONTROL, ProtectedSurface.PROTECTION_PAUSE -> ProductDestination.Guard
        ProtectedSurface.APP_SETTINGS -> ProductDestination.Settings
        ProtectedSurface.APP_UNLOCK -> ProductDestination.Guard
    }

internal val ProtectedSurface.titleRes: Int
    get() = when (this) {
        ProtectedSurface.ACTIVITY -> R.string.activity_title
        ProtectedSurface.FILTERS -> R.string.filters_title
        ProtectedSurface.PROTECTION_CONTROL -> R.string.security_surface_protection_control
        ProtectedSurface.PROTECTION_PAUSE -> R.string.security_surface_protection_pause
        ProtectedSurface.APP_SETTINGS -> R.string.security_surface_app_settings
        ProtectedSurface.APP_UNLOCK -> R.string.security_surface_app_unlock
    }

data class SecurityCallbacks(
    val onSetPasscode: (String) -> Unit,
    val onChangePasscode: (String, String) -> Unit,
    val onRemovePasscode: (String) -> Unit,
    val onUnlock: (String) -> Unit,
    val onLockTimeoutSelected: (LockTimeoutOption) -> Unit,
    val onProtectedSurfaceChanged: (ProtectedSurface, Boolean) -> Unit,
    val onResetAfterRecoveryFailure: () -> Unit,
    /** A native recovery adapter returns to RN after reset; ordinary Security also starts setup. */
    val recoveryReturnsToCaller: Boolean = false,
)

/**
 * Security route (iOS SecurityController anchor): Android Keystore-backed app
 * unlock with setup/change/remove, the lock timeout, and the protected
 * surfaces. Unavailable and recovery-failure states are honest — the screen
 * never renders a control that cannot work on this device.
 */
@Composable
fun SecuritySettingsScreen(
    state: SecurityUiState,
    callbacks: SecurityCallbacks?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSetup by remember { mutableStateOf(false) }
    var showChange by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    // The epoch the change dialog opened at: only an ADVANCING owner epoch
    // (a verified change) closes it, so a WRONG_PASSCODE admission keeps the
    // dialog open with its inline error instead of vanishing before the
    // asynchronous owner result arrives.
    var changeOpenedAtEpoch by remember { mutableStateOf<Long?>(null) }
    val changeEpoch = (state as? SecurityUiState.Enabled)?.lastChangeEpochMillis
    LaunchedEffect(changeEpoch) {
        // Close only on a NEW non-null epoch: a verified change stamps the
        // owner clock, while the WRONG_PASSCODE admission keeps the epoch (and
        // the dialog) unchanged. The null epoch means no change has ever
        // succeeded, so any non-null advance proves verification passed.
        if (showChange && changeEpoch != null && changeEpoch != changeOpenedAtEpoch) {
            showChange = false
            changeOpenedAtEpoch = null
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("security_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.settings_security_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        when (state) {
            SecurityUiState.Unavailable -> {
                LavaPanel(modifier = Modifier.testTag("security_unavailable_panel")) {
                    Text(
                        text = stringResource(R.string.security_unavailable_title),
                        style = LavaScreenTypography.rowTitle,
                    )
                    Text(
                        text = stringResource(R.string.security_unavailable_detail),
                        style = LavaScreenTypography.rowSummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SecurityUiState.RecoveryFailure -> {
                LavaPanel(modifier = Modifier.testTag("security_recovery_panel")) {
                    Text(
                        text = stringResource(R.string.security_recovery_failure_title),
                        style = LavaScreenTypography.rowTitle,
                    )
                    Text(
                        text = stringResource(R.string.security_recovery_failure_detail),
                        style = LavaScreenTypography.rowSummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (callbacks != null) {
                    Button(
                        onClick = {
                            showSetup = !callbacks.recoveryReturnsToCaller
                            callbacks.onResetAfterRecoveryFailure()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("security_recovery_reset"),
                    ) {
                        Text(stringResource(if (callbacks.recoveryReturnsToCaller) R.string.security_recovery_return else R.string.security_recovery_reset))
                    }
                }
            }

            is SecurityUiState.Disabled -> {
                LavaPanel {
                    Text(
                        text = stringResource(R.string.security_intro_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.security_intro_detail),
                        style = LavaScreenTypography.rowSummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LavaPanel {
                    Text(
                        text = stringResource(R.string.security_local_note),
                        style = LavaScreenTypography.rowSummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (callbacks != null) {
                    Button(
                        onClick = { showSetup = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("security_set_passcode"),
                    ) {
                        Text(stringResource(R.string.security_set_passcode))
                    }
                }
            }

            is SecurityUiState.Enabled -> {
                LavaPanel(modifier = Modifier.testTag("security_enabled_panel")) {
                    Text(
                        text = stringResource(R.string.security_passcode_on_title),
                        style = LavaScreenTypography.rowTitle,
                    )
                    Text(
                        text = stringResource(R.string.security_local_note),
                        style = LavaScreenTypography.rowSummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (callbacks != null) {
                    LavaSection(title = stringResource(R.string.security_protected_surfaces_title)) {
                        Text(
                            text = stringResource(R.string.security_protected_surfaces_detail),
                            style = LavaScreenTypography.rowSummary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ProtectedSurface.entries.forEach { surface ->
                            SurfaceToggle(
                                surface = surface,
                                checked = surface in state.protectedSurfaces,
                                enabled = !state.inFlight,
                                onCheckedChange = {
                                    callbacks.onProtectedSurfaceChanged(surface, it)
                                },
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            changeOpenedAtEpoch = state.lastChangeEpochMillis
                            showChange = true
                        },
                        enabled = !state.inFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("security_change_passcode"),
                    ) {
                        Text(stringResource(R.string.security_change_passcode))
                    }
                    OutlinedButton(
                        onClick = { showRemove = true },
                        enabled = !state.inFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("security_remove_passcode"),
                    ) {
                        Text(stringResource(R.string.security_remove_passcode))
                    }
                }
                if (state.failure == SecurityFailureUi.SECURE_STORAGE ||
                    state.failure == SecurityFailureUi.OPERATION_FAILED
                ) {
                    LavaPanel(modifier = Modifier.testTag("security_error_panel")) {
                        Text(
                            text = stringResource(R.string.security_operation_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
    if (callbacks != null && (state is SecurityUiState.Disabled || state is SecurityUiState.RecoveryFailure) && showSetup) {
        PasscodeSetupDialog(
            requireCurrent = false,
            wrongPasscode = false,
            onConfirm = { current, passcode ->
                showSetup = false
                callbacks.onSetPasscode(passcode)
            },
            onDismiss = { showSetup = false },
        )
    }
    if (callbacks != null && state is SecurityUiState.Enabled && showChange) {
        PasscodeSetupDialog(
            requireCurrent = true,
            wrongPasscode = state.failure == SecurityFailureUi.WRONG_PASSCODE,
            onConfirm = { current, passcode ->
                // Dispatch only: the dialog closes when the owner's
                // lastChangeEpochMillis advances (verified change), not on
                // submit — a wrong passcode must keep it open for the error.
                callbacks.onChangePasscode(current, passcode)
            },
            onDismiss = {
                showChange = false
                changeOpenedAtEpoch = null
            },
        )
    }
    if (callbacks != null && state is SecurityUiState.Enabled && showRemove) {
        RemovePasscodeDialog(
            wrongPasscode = state.failure == SecurityFailureUi.WRONG_PASSCODE,
            onConfirm = { passcode ->
                // Dispatch only: a successful remove transitions the state to
                // Disabled, which dismisses the dialog branch; a wrong passcode
                // keeps it open with the inline error.
                callbacks.onRemovePasscode(passcode)
            },
            onDismiss = { showRemove = false },
        )
    }
}

@Composable
private fun SurfaceToggle(
    surface: ProtectedSurface,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    LavaPanel {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(surface.titleRes),
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.testTag(surface.testTag),
            )
        }
    }
}

/**
 * Shared passcode setup/change dialog. Setup asks for the new passcode twice;
 * change additionally verifies the current passcode. A mismatch or a wrong
 * current passcode is reported inline and the dialog stays open.
 */
@Composable
private fun PasscodeSetupDialog(
    requireCurrent: Boolean,
    wrongPasscode: Boolean,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var passcode by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (requireCurrent) {
                        R.string.security_change_passcode
                    } else {
                        R.string.security_set_passcode
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                if (requireCurrent) {
                    PasscodeField(
                        value = current,
                        onValueChange = { current = it },
                        label = stringResource(R.string.security_enter_passcode),
                        testTag = "security_current_passcode_field",
                    )
                }
                PasscodeField(
                    value = passcode,
                    onValueChange = { passcode = it },
                    label = stringResource(
                        if (requireCurrent) {
                            R.string.security_new_passcode
                        } else {
                            R.string.security_enter_passcode
                        },
                    ),
                    testTag = "security_new_passcode_field",
                )
                PasscodeField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = stringResource(R.string.security_confirm_passcode),
                    testTag = "security_confirm_passcode_field",
                )
                if (wrongPasscode) {
                    Text(
                        text = stringResource(R.string.security_wrong_passcode),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (mismatch) {
                    Text(
                        text = stringResource(R.string.security_passcode_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("security_passcode_mismatch"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (passcode.isBlank() || confirmation.isBlank() || passcode != confirmation) {
                        mismatch = passcode.isNotBlank() && passcode != confirmation
                    } else {
                        onConfirm(current, passcode)
                    }
                },
                enabled = passcode.isNotBlank() && confirmation.isNotBlank(),
            ) {
                Text(stringResource(R.string.settings_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun RemovePasscodeDialog(
    wrongPasscode: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passcode by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.security_remove_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                Text(stringResource(R.string.security_remove_confirm_detail))
                PasscodeField(
                    value = passcode,
                    onValueChange = { passcode = it },
                    label = stringResource(R.string.security_enter_passcode),
                    testTag = "security_remove_passcode_field",
                )
                if (wrongPasscode) {
                    Text(
                        text = stringResource(R.string.security_wrong_passcode),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passcode) },
                enabled = passcode.isNotBlank(),
            ) {
                Text(stringResource(R.string.security_remove_confirm_action))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun PasscodeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    testTag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
        ),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    )
}

/**
 * Full-screen app-unlock gate rendered by the shell over any protected surface
 * whose unlock session has expired. The passcode never leaves the device; the
 * wrong-passcode line is the only honest failure feedback.
 */
@Composable
fun AppUnlockOverlay(
    wrongPasscode: Boolean,
    onUnlock: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateAway: (() -> Unit)? = null,
) {
    var passcode by remember { mutableStateOf("") }
    Box(
        modifier = modifier
            .fillMaxSize()
            // The gate must genuinely block: an opaque surface hides the
            // rendered protected screen, and the pointer consumer swallows
            // every event outside the dialog column so no control behind the
            // overlay can receive a tap or scroll.
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .testTag("app_unlock_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(LavaSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = LavaSettingsIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.security_locked_title),
                style = LavaScreenTypography.screenTitle,
            )
            Text(
                text = stringResource(R.string.security_unlock_hint),
                style = LavaScreenTypography.rowSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PasscodeField(
                value = passcode,
                onValueChange = { passcode = it },
                label = stringResource(R.string.security_enter_passcode),
                testTag = "app_unlock_passcode_field",
            )
            if (wrongPasscode) {
                Text(
                    text = stringResource(R.string.security_wrong_passcode),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("app_unlock_wrong_passcode"),
                )
            }
            Button(
                onClick = { onUnlock(passcode) },
                enabled = passcode.isNotBlank(),
                modifier = Modifier.testTag("app_unlock_submit"),
            ) {
                Text(stringResource(R.string.security_unlock_action))
            }
            if (onNavigateAway != null) {
                OutlinedButton(
                    onClick = onNavigateAway,
                    modifier = Modifier.testTag("app_unlock_navigate_away"),
                ) {
                    Text(stringResource(R.string.navigate_back))
                }
            }
        }
    }
}

private val ProtectedSurface.testTag: String
    get() = when (this) {
        ProtectedSurface.ACTIVITY -> "security_surface_activity_switch"
        ProtectedSurface.FILTERS -> "security_surface_filters_switch"
        ProtectedSurface.PROTECTION_CONTROL -> "security_surface_protection_control_switch"
        ProtectedSurface.PROTECTION_PAUSE -> "security_surface_protection_pause_switch"
        ProtectedSurface.APP_SETTINGS -> "security_surface_app_settings_switch"
        ProtectedSurface.APP_UNLOCK -> "security_surface_app_unlock_switch"
    }
