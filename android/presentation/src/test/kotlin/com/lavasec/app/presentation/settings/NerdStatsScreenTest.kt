package com.lavasec.app.presentation.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.foundation.diagnostics.OperationalAndroidPathState
import com.lavasec.app.foundation.diagnostics.OperationalAndroidTransport
import com.lavasec.app.foundation.health.ProtectionFallback
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NerdStatsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `app facts render without any tunnel sample`() {
        compose.setContent {
            LavaTheme {
                NerdStatsScreen(
                    state = NerdStatsUiState(
                        versionName = "0.1.0",
                        buildNumber = "42",
                        androidPlatformLabel = "Android 15",
                        tunnelHealth = null,
                    ),
                    sampledTimeLabel = { "sampled" },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Version").assertExists()
        compose.onNodeWithText("0.1.0").assertExists()
        compose.onNodeWithText("Android 15").assertExists()
        compose.onNodeWithText("Not sampled yet").assertExists()
    }

    @Test
    fun `the privacy panel states local retention the feedback path and the clear scope`() {
        compose.setContent {
            LavaTheme {
                NerdStatsScreen(
                    state = NerdStatsUiState(
                        versionName = "0.1.0",
                        buildNumber = "42",
                        androidPlatformLabel = "Android 15",
                        tunnelHealth = null,
                    ),
                    sampledTimeLabel = { "sampled" },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("nerd_stats_privacy_panel").assertExists()
        compose.onNodeWithText("Stays on this device").assertExists()
        compose.onNodeWithText(
            "This local sample stays on this device and is included only in feedback you " +
                "explicitly submit. Clear it anytime in Settings → Privacy & Data.",
        ).assertExists()
    }

    @Test
    fun `tunnel facts render the exact bounded sample without invented rows`() {
        compose.setContent {
            LavaTheme {
                NerdStatsScreen(
                    state = NerdStatsUiState(
                        versionName = "0.1.0",
                        buildNumber = "42",
                        androidPlatformLabel = "Android 9",
                        tunnelHealth = TunnelHealthUi(
                            transport = OperationalAndroidTransport.UNKNOWN,
                            pathState = OperationalAndroidPathState.LOST,
                            connected = true,
                            consecutiveFailures = 3,
                            consecutiveRejections = 0,
                            lastResponseLatencyMillis = 12L,
                            activeFallback = ProtectionFallback.DEVICE_DNS,
                            requiresReconnect = false,
                            sampledWallClockMillis = 1_775_200_000_000L,
                        ),
                    ),
                    sampledTimeLabel = { millis -> "sample:$millis" },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Yes").assertExists()
        compose.onNodeWithText("Unknown").assertExists()
        compose.onNodeWithText("Lost").assertExists()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("12 ms").assertExists()
        compose.onNodeWithText("Active fallback").assertExists()
        compose.onNodeWithText("Device DNS").assertExists()
        compose.onNodeWithText("No").assertExists()
        compose.onNodeWithText("sample:1775200000000").assertExists()
    }

    @Test
    fun `back button dispatches to the shell handler`() {
        var backed = false
        compose.setContent {
            LavaTheme {
                NerdStatsScreen(
                    state = NerdStatsUiState(
                        versionName = "0.1.0",
                        buildNumber = "42",
                        androidPlatformLabel = "Android 15",
                        tunnelHealth = null,
                    ),
                    sampledTimeLabel = { "sampled" },
                    onBack = { backed = true },
                )
            }
        }

        compose.onNodeWithTag("nerd_stats_back").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(backed) }
    }
}
