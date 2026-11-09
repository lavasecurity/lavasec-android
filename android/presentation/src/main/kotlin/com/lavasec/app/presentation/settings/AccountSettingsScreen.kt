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
 * The honest Android account state: there is no backend account/backup contract
 * on Android yet, so the only state that exists is unavailable. The route is a
 * real destination that explains this — no fake sign-in UI, no invented free
 * plan, and no account capability that could silently fail later.
 */
sealed interface AccountUiState {
    /** Android account, sign-in, and encrypted backup are not available yet. */
    data object Unavailable : AccountUiState
}

/**
 * Account & Backup route (iOS AccountBackupSettingsView anchor). Slice 04 ships
 * the honest unavailable state: Lava works without an account on this device,
 * and the Android backend contract does not exist yet, so no sign-in or backup
 * controls are rendered.
 */
@Composable
fun AccountSettingsScreen(
    state: AccountUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .lavaScreenScrollPadding(LocalLavaContentInsets.current)
            .testTag("account_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(LavaSpacing.lg),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = LavaIcons.Back,
                contentDescription = stringResource(R.string.navigate_back),
            )
        }
        Text(
            text = stringResource(R.string.settings_account_backup_title),
            style = LavaScreenTypography.screenTitle,
            modifier = Modifier.semantics { heading() },
        )
        when (state) {
            AccountUiState.Unavailable -> {
                LavaPanel(modifier = Modifier.testTag("account_unavailable_panel")) {
                    Text(
                        text = stringResource(R.string.account_unavailable_title),
                        style = LavaScreenTypography.rowTitle,
                    )
                    Text(
                        text = stringResource(R.string.account_unavailable_body),
                        style = LavaScreenTypography.rowSummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LavaPanel {
                    Text(
                        text = stringResource(R.string.account_works_without_account),
                        style = LavaScreenTypography.rowSummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
