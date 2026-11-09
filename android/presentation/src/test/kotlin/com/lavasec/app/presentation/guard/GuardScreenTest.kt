package com.lavasec.app.presentation.guard

import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.presentation.GuardActionDisabledUi
import com.lavasec.app.presentation.GuardConnectivityUi
import com.lavasec.app.presentation.GuardLifecycleUi
import com.lavasec.app.presentation.GuardMessageUi
import com.lavasec.app.presentation.GuardPauseUi
import com.lavasec.app.presentation.GuardPrimaryAction
import com.lavasec.app.presentation.GuardProtectionHealthUi
import com.lavasec.app.platform.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GuardScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `header shows the product label and the typed status copy`() {
        setGuard(offHealth)

        // The visible product label, title, and subtitle merge into the one
        // accessibility element; its stable label and composed value pin the copy.
        compose.runOnIdle {
            val node = compose.onNodeWithTag("guard_status_text").fetchSemanticsNode()
            val config = node.config
            assertEquals(
                "Protection status",
                config[SemanticsProperties.ContentDescription].single(),
            )
            assertEquals(
                "Protection Off. Local DNS filtering is off",
                config[SemanticsProperties.StateDescription],
            )
        }
    }

    @Test
    fun `guardian visual bounds are exactly 96dp at the compact breakpoint`() {
        setGuard(offHealth, width = 599.dp)

        compose.runOnIdle {
            val bounds = compose.onNodeWithTag("guard_mascot").fetchSemanticsNode().size
            val expected = with(compose.density) { 96.dp.toPx().toInt() }
            assertEquals(expected, bounds.width)
            assertEquals(expected, bounds.height)
        }
    }

    @Test
    fun `primary action renders at 56dp height and no wider than 300dp`() {
        setGuard(offHealth, onPrimaryAction = {}, width = 599.dp)

        val action = compose.onNodeWithTag("guard_primary_action")
        action.assertHeightIsAtLeast(56.dp)
        compose.runOnIdle {
            val bounds = action.fetchSemanticsNode().size
            val maxWidthPx = with(compose.density) { 300.dp.toPx() }
            assertTrue(
                "action width ${bounds.width}px exceeds 300dp (${maxWidthPx}px)",
                bounds.width <= maxWidthPx,
            )
        }
    }

    @Test
    fun `action labels follow the iOS vocabulary`() {
        var action by mutableStateOf<GuardPrimaryAction>(GuardPrimaryAction.TurnOn)
        compose.setContent {
            LavaTheme {
                GuardScreen(
                    health = offHealth.copy(primaryAction = action),
                    onOpenFilters = {},
                    onOpenActivity = {},
                    onPrimaryAction = {},
                    modifier = Modifier.requiredWidth(599.dp).requiredHeight(800.dp),
                )
            }
        }
        val cases = listOf(
            GuardPrimaryAction.TurnOn to "Turn on",
            GuardPrimaryAction.TurnOff to "Turn off",
            GuardPrimaryAction.Resume to "Resume",
            GuardPrimaryAction.Reconnect to "Reconnect",
            GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings) to "Open settings",
        )

        cases.forEach { (expected, label) ->
            compose.runOnIdle { action = expected }
            compose.onNodeWithText(label).assertExists()
        }
    }

    @Test
    fun `action is disabled only for typed in-flight or reconstruction states with an explanation`() {
        var disabledReason by mutableStateOf<GuardActionDisabledUi?>(
            GuardActionDisabledUi.COMMAND_IN_FLIGHT,
        )
        compose.setContent {
            LavaTheme {
                GuardScreen(
                    health = offHealth,
                    onOpenFilters = {},
                    onOpenActivity = {},
                    onPrimaryAction = {},
                    disabledReason = disabledReason,
                    modifier = Modifier.requiredWidth(599.dp).requiredHeight(800.dp),
                )
            }
        }

        compose.onNodeWithTag("guard_primary_action").assertIsNotEnabled()
        compose.onNodeWithText("Wait for the current protection change to finish.").assertExists()

        compose.runOnIdle { disabledReason = GuardActionDisabledUi.RECONSTRUCTION_REQUIRED }
        compose.onNodeWithTag("guard_primary_action").assertIsNotEnabled()
        compose.onNodeWithText("Protection controls are being restored.").assertExists()
        compose.onNodeWithText("Wait for the current protection change to finish.")
            .assertDoesNotExist()

        compose.runOnIdle { disabledReason = null }
        compose.onNodeWithTag("guard_primary_action").assertIsEnabled()
        compose.onNodeWithText("Protection controls are being restored.").assertDoesNotExist()
    }

    @Test
    fun `long press offers every foundation pause duration and dispatches the selection`() {
        val pauses = mutableListOf<PauseDuration>()
        setGuard(protectedHealth, onPrimaryAction = {}, onPause = pauses::add)

        compose.onNodeWithTag("guard_primary_action").performTouchInput { longClick() }

        compose.onNodeWithText("For 5 minutes").assertExists()
        compose.onNodeWithText("For 10 minutes").assertExists()
        compose.onNodeWithText("For 15 minutes").assertExists()

        compose.onNodeWithText("For 5 minutes").performClick()
        compose.runOnIdle {
            assertEquals(listOf(PauseDuration.FIVE_MINUTES), pauses)
        }
        // Every duration the foundation owner supports is offered.
        compose.onNodeWithTag("guard_primary_action").performTouchInput { longClick() }
        compose.onNodeWithText("For 15 minutes").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(PauseDuration.FIVE_MINUTES, PauseDuration.FIFTEEN_MINUTES),
                pauses,
            )
        }
    }

    @Test
    fun `pause menu is absent when protection is not pausable`() {
        setGuard(offHealth, onPrimaryAction = {}, onPause = {})

        compose.onNodeWithTag("guard_primary_action").performTouchInput { longClick() }
        compose.onNodeWithText("For 5 minutes").assertDoesNotExist()
        compose.onNodeWithText("Long-press for pause options").assertDoesNotExist()
    }

    @Test
    fun `pausable action shows the long-press hint`() {
        setGuard(protectedHealth, onPrimaryAction = {}, onPause = {})

        compose.onNodeWithText("Long-press for pause options").assertExists()
    }

    @Test
    fun `pause subtitle renders expired, one-minute, and multi-minute plural forms`() {
        var remaining by mutableStateOf(0L)
        compose.setContent {
            LavaTheme {
                GuardScreen(
                    health = pausedHealth(remaining),
                    onOpenFilters = {},
                    onOpenActivity = {},
                    onPrimaryAction = {},
                    modifier = Modifier.requiredWidth(599.dp).requiredHeight(800.dp),
                )
            }
        }

        // The subtitle lives inside the merged status element, so the plural form
        // is read from its composed value.
        assertEquals("Paused. Protection resume is due", statusValue())

        compose.runOnIdle { remaining = 1L }
        assertEquals("Paused. Protection resumes in 1 minute", statusValue())

        compose.runOnIdle { remaining = 5L }
        assertEquals("Paused. Protection resumes in 5 minutes", statusValue())
    }

    private fun statusValue(): String? {
        var value: String? = null
        compose.runOnIdle {
            value = compose.onNodeWithTag("guard_status_text").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]
        }
        return value
    }

    @Test
    fun `status element merges into one accessibility element with the stable label and live value`() {
        setGuard(offHealth)

        compose.runOnIdle {
            val node = compose.onNodeWithTag("guard_status_text").fetchSemanticsNode()
            val config = node.config
            assertEquals(
                "Protection status",
                config[SemanticsProperties.ContentDescription].single(),
            )
            assertEquals(
                "Protection Off. Local DNS filtering is off",
                config[SemanticsProperties.StateDescription],
            )
        }
    }

    @Test
    fun `primary action exposes the pinned iOS accessibility hint as its state`() {
        setGuard(offHealth, onPrimaryAction = {})

        compose.runOnIdle {
            val node = compose.onNodeWithTag("guard_primary_action").fetchSemanticsNode()
            assertEquals(
                "Controls Lava's local DNS protection.",
                node.config[SemanticsProperties.StateDescription],
            )
        }
    }

    @Test
    fun `guard shows exactly one status element and one primary action`() {
        setGuard(offHealth, onPrimaryAction = {})

        compose.onAllNodesWithTag("guard_status_text").assertCountEquals(1)
        compose.onAllNodesWithTag("guard_primary_action").assertCountEquals(1)
    }

    @Test
    fun `typed messages render below the action`() {
        setGuard(
            offHealth.copy(message = GuardMessageUi.VPN_PERMISSION_REVOKED),
            onPrimaryAction = {},
        )

        compose.onNodeWithText(
            "Android VPN permission is required to turn protection on",
        ).assertExists()
    }

    @Test
    fun `host error renders below the action`() {
        setGuard(offHealth, onPrimaryAction = {}, error = "Protection command failed.")

        compose.onNodeWithText("Protection command failed.").assertExists()
    }

    @Test
    fun `very narrow widths stack the status text under the 96dp guardian`() {
        setGuard(offHealth, onPrimaryAction = {}, width = 300.dp)

        compose.runOnIdle {
            val mascot = compose.onNodeWithTag("guard_mascot").fetchSemanticsNode()
            val text = compose.onNodeWithTag("guard_status_text").fetchSemanticsNode()
            val expected = with(compose.density) { 96.dp.toPx().toInt() }
            // The guardian keeps its exact size and the text column stacks below it.
            assertEquals(expected, mascot.size.width)
            assertTrue(
                "status text must stack below the mascot; mascot bottom " +
                    "${mascot.boundsInRoot.bottom} vs text top ${text.boundsInRoot.top}",
                text.boundsInRoot.top >= mascot.boundsInRoot.bottom,
            )
        }
    }

    @Test
    fun `wide header keeps the guardian and text side by side`() {
        setGuard(offHealth, onPrimaryAction = {}, width = 599.dp)

        compose.runOnIdle {
            val mascot = compose.onNodeWithTag("guard_mascot").fetchSemanticsNode()
            val text = compose.onNodeWithTag("guard_status_text").fetchSemanticsNode()
            assertTrue(
                "status text must sit beside the mascot; mascot right " +
                    "${mascot.boundsInRoot.right} vs text left ${text.boundsInRoot.left}",
                text.boundsInRoot.left >= mascot.boundsInRoot.right,
            )
        }
    }

    private fun setGuard(
        health: GuardProtectionHealthUi,
        width: Dp = 599.dp,
        onPrimaryAction: ((GuardPrimaryAction) -> Unit)? = null,
        onPause: ((PauseDuration) -> Unit)? = null,
        disabledReason: GuardActionDisabledUi? = null,
        error: String? = null,
    ) {
        compose.setContent {
            LavaTheme {
                GuardScreen(
                    health = health,
                    onOpenFilters = {},
                    onOpenActivity = {},
                    onPrimaryAction = onPrimaryAction,
                    onPause = onPause,
                    disabledReason = disabledReason,
                    error = error,
                    modifier = Modifier
                        .requiredWidth(width)
                        .requiredHeight(800.dp),
                )
            }
        }
    }

    private companion object {
        val offHealth = GuardProtectionHealthUi(
            lifecycle = GuardLifecycleUi.INACTIVE,
            connectivity = GuardConnectivityUi.UNAVAILABLE,
            primaryAction = GuardPrimaryAction.TurnOn,
        )

        val protectedHealth = GuardProtectionHealthUi(
            lifecycle = GuardLifecycleUi.PROTECTED,
            connectivity = GuardConnectivityUi.HEALTHY,
            primaryAction = GuardPrimaryAction.TurnOff,
            canPause = true,
        )

        fun pausedHealth(remainingMinutes: Long) = GuardProtectionHealthUi(
            lifecycle = GuardLifecycleUi.PAUSED,
            connectivity = GuardConnectivityUi.UNAVAILABLE,
            pause = GuardPauseUi(
                deadlineEpochMillis = 400_000L,
                remainingMinutes = remainingMinutes,
            ),
            primaryAction = GuardPrimaryAction.Resume,
        )
    }
}
