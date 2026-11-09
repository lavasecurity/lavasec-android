package com.lavasec.app.presentation.settings

import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `account route renders the honest unavailable state with no sign-in surface`() {
        compose.setContent {
            LavaTheme {
                AccountSettingsScreen(
                    state = AccountUiState.Unavailable,
                    onBack = {},
                    modifier = Modifier.requiredWidth(400.dp).requiredHeight(800.dp),
                )
            }
        }

        compose.onNodeWithText("Account & Backup").assertExists()
        compose.onNodeWithTag("account_unavailable_panel").assertExists()
        compose.onNodeWithText("Account setup pending").assertExists()
        // No fake sign-in UI exists in the honest unavailable state.
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithText("Continue without account").assertDoesNotExist()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpgradeSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `upgrade route renders the honest unavailable state with no purchase surface`() {
        compose.setContent {
            LavaTheme {
                UpgradeSettingsScreen(
                    state = EntitlementUiState.Unavailable,
                    onBack = {},
                    modifier = Modifier.requiredWidth(400.dp).requiredHeight(800.dp),
                )
            }
        }

        compose.onNodeWithText("Upgrade").assertExists()
        compose.onNodeWithTag("upgrade_unavailable_panel").assertExists()
        compose.onNodeWithText("Lava Security Plus isn't available on Android yet")
            .assertExists()
        compose.onNodeWithText("Restore Purchase").assertDoesNotExist()
        compose.onNodeWithText("Choose a plan").assertDoesNotExist()
    }
}
