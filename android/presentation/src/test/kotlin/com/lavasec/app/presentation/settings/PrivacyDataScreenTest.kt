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
import com.lavasec.app.platform.LoggingSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivacyDataScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `retention toggles dispatch the owners exact logging changes`() {
        var latest: LoggingSettings? = null
        setScreen(callbacks = callbacks().copy(onLoggingChange = { latest = it }))

        compose.onNodeWithTag("settings_keep_network_activity_switch")
            .performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertEquals(false, latest?.keepNetworkActivity)
            assertEquals(true, latest?.keepCounts)
            assertEquals(true, latest?.keepHistory)
        }
    }

    @Test
    fun `destructive clear requires the explicit confirmation fence`() {
        var clears = 0
        setScreen(callbacks = callbacks(onClear = { clears += 1 }))

        compose.onNodeWithTag("settings_clear_activity").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithText("Clear local Activity?").assertExists()
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
    }

    @Test
    fun `diagnostics clear requires its own explicit confirmation fence`() {
        var clears = 0
        setScreen(callbacks = callbacks(onClearDiagnostics = { clears += 1 }))

        compose.onNodeWithTag("settings_clear_diagnostics").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithText("Clear local diagnostics?").assertExists()
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
    }

    @Test
    fun `share is exposed only for an exact completed export handle`() {
        var shares = 0
        setScreen(exportReady = true, callbacks = callbacks().copy(onShareLocalExport = { shares += 1 }))
        compose.onNodeWithTag("settings_share_export").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, shares) }
    }

    @Test
    fun `share is absent without an exact completed export handle`() {
        setScreen(exportReady = false)
        compose.onNodeWithTag("settings_share_export").assertDoesNotExist()
    }

    private fun setScreen(
        exportReady: Boolean = false,
        callbacks: PrivacyDataCallbacks = callbacks(),
    ) {
        compose.setContent {
            LavaTheme {
                PrivacyDataScreen(
                    state = PrivacyDataUiState(
                        logging = LoggingSettings(),
                        inFlight = false,
                        exportReady = exportReady,
                        error = null,
                    ),
                    callbacks = callbacks,
                    onBack = {},
                    modifier = Modifier.requiredWidth(400.dp).requiredHeight(800.dp),
                )
            }
        }
    }

    private fun callbacks(
        onClear: () -> Unit = {},
        onClearDiagnostics: () -> Unit = {},
    ) = PrivacyDataCallbacks(
        onLoggingChange = {},
        onClearLocalActivity = onClear,
        onExportLocalActivity = {},
        onShareLocalExport = {},
        onClearDiagnostics = onClearDiagnostics,
    )
}
