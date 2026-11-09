package com.lavasec.app.presentation.settings

import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CustomizationSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `reduced motion and haptics toggles dispatch the owner callbacks`() {
        val changes = mutableListOf<String>()
        setScreen(
            state = CustomizationUiState(
                reduceMotion = false,
                haptics = true,
            ),
            callbacks = CustomizationCallbacks(
                onReduceMotionChanged = { changes += "motion:$it" },
                onHapticsChanged = { changes += "haptics:$it" },
                onOpenNotificationSettings = {},
            ),
        )

        compose.onNodeWithTag("customization_reduce_motion_switch")
            .performScrollTo().performClick()
        compose.onNodeWithTag("customization_haptics_switch")
            .performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("motion:true", "haptics:false"), changes)
        }
    }

    @Test
    fun `blocked notifications route to system settings`() {
        var openedSettings = false
        setScreen(
            state = CustomizationUiState(
                notificationsAllowed = false,
                notificationsBlocked = true,
            ),
            callbacks = CustomizationCallbacks(
                onReduceMotionChanged = {},
                onHapticsChanged = {},
                onOpenNotificationSettings = { openedSettings = true },
            ),
        )

        compose.onNodeWithText("Notifications are turned off in Android settings")
            .assertExists()
        compose.onNodeWithTag("customization_open_notification_settings")
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, openedSettings) }
    }

    @Test
    fun `allowed notifications show the allowed status without the settings route`() {
        setScreen(
            state = CustomizationUiState(
                notificationsAllowed = true,
                notificationsBlocked = false,
            ),
            callbacks = null,
        )

        compose.onNodeWithText("Notifications are allowed").assertExists()
        compose.onNodeWithTag("customization_open_notification_settings")
            .assertDoesNotExist()
    }

    @Test
    fun `the ambient notification contract is disclosed in the notifications section`() {
        setScreen(
            state = CustomizationUiState(
                notificationsAllowed = true,
                notificationsBlocked = false,
            ),
            callbacks = null,
        )

        compose.onNodeWithText(
            "The Protection status notification shows current protection and DNS fallback " +
                "status. It stays silent, hides its text on the lock screen, and disappears " +
                "when protection is off.",
        ).performScrollTo().assertExists()
    }

    private fun setScreen(
        state: CustomizationUiState,
        callbacks: CustomizationCallbacks?,
    ) {
        compose.setContent {
            LavaTheme {
                CustomizationSettingsScreen(
                    state = state,
                    callbacks = callbacks,
                    onBack = {},
                    modifier = Modifier.requiredWidth(400.dp).requiredHeight(800.dp),
                )
            }
        }
    }
}
