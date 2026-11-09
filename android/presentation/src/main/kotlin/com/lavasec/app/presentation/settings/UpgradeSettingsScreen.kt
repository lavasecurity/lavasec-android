package com.lavasec.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaScreenTypography
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.designsystem.LocalLavaContentInsets
import com.lavasec.app.designsystem.lavaScreenScrollPadding
import com.lavasec.app.presentation.R

/**
 * The honest Android entitlement state: there is no signed/authoritative Plus
 * entitlement contract on Android, so the only state that exists is
 * unavailable. Paid access is never derived from a local boolean, and no
 * purchase UI is rendered before the backend entitlement contract exists.
 */
sealed interface EntitlementUiState {
    /** Lava Security Plus and purchases are not available on Android yet. */
    data object Unavailable : EntitlementUiState
}

/**
 * Upgrade route (iOS UpgradeSettingsView anchor). Slice 04 ships the honest
 * unavailable state: Plus is not sold or granted on Android yet, Lava stays
 * free here, and there is no purchase/restore surface to fake.
 */
@Composable
fun UpgradeSettingsScreen(
    state: EntitlementUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("upgrade_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.settings_upgrade_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        when (state) {
            EntitlementUiState.Unavailable -> {
                LavaPanel(modifier = Modifier.testTag("upgrade_unavailable_panel")) {
                    Text(
                        text = stringResource(R.string.upgrade_unavailable_title),
                        style = LavaScreenTypography.rowTitle,
                    )
                    Text(
                        text = stringResource(R.string.upgrade_unavailable_body),
                        style = LavaScreenTypography.rowSummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
