package com.lavasec.app.presentation.mascot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.foundation.preferences.GuardianLook
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Smoke test in the ProductShellSemanticsTest pattern (Robolectric + Compose
// rule). Robolectric provides a software canvas, so the renderer's Canvas pass —
// including the native BlurMaskFilter glow layer — executes real draw calls
// instead of being skipped by a fake canvas; a throwing geometry bug surfaces
// here as a test failure, not a silent blank.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GuardianMascotRendererTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `mascot container renders with the guard_mascot test tag`() {
        compose.setContent {
            LavaTheme {
                GuardianMascotView(state = GuardianMascotState.AWAKE)
            }
        }

        compose.onNodeWithTag("guard_mascot").assertExists()
    }

    @Test
    fun `mascot tap fires the host callback`() {
        var tapped = false
        compose.setContent {
            LavaTheme {
                GuardianMascotView(
                    state = GuardianMascotState.AWAKE,
                    onTap = { tapped = true },
                )
            }
        }

        compose.onNodeWithTag("guard_mascot").performClick()

        compose.runOnIdle { assertTrue(tapped) }
    }

    @Test
    fun `only the awake mascot is a tap target`() {
        // The slice-02 tap acknowledgement fires only when the typed Guard status
        // mapped to AWAKE (protected and healthy); every other state omits the
        // clickable entirely so TalkBack meets a decorative image, not a control.
        var displayedState by mutableStateOf(GuardianMascotState.SLEEPING)
        compose.setContent {
            LavaTheme {
                GuardianMascotView(
                    state = displayedState,
                    animates = false,
                )
            }
        }

        GuardianMascotState.entries
            .filter { it != GuardianMascotState.AWAKE }
            .forEach { state ->
                compose.runOnIdle { displayedState = state }
                compose.onNodeWithTag("guard_mascot")
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            }

        compose.runOnIdle { displayedState = GuardianMascotState.AWAKE }
        compose.onNodeWithTag("guard_mascot")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `reduced motion renders every stable pose without animation`() {
        // Reduced-motion fixture: with LavaTheme(reducedMotion = true) the driver
        // folds the setting into the same effective-animation flag as iOS
        // `animates == false`, so every pose renders its stable frame deterministically.
        var displayedState by mutableStateOf(GuardianMascotState.SLEEPING)
        compose.setContent {
            LavaTheme(reducedMotion = true) {
                GuardianMascotView(state = displayedState)
            }
        }

        GuardianMascotState.entries.forEach { state ->
            compose.runOnIdle { displayedState = state }
            compose.onNodeWithTag("guard_mascot").assertExists()
        }
    }

    @Test
    fun `reduced motion still allows the awake tap acknowledgement`() {
        var tapped = false
        compose.setContent {
            LavaTheme(reducedMotion = true) {
                GuardianMascotView(
                    state = GuardianMascotState.AWAKE,
                    onTap = { tapped = true },
                )
            }
        }

        compose.onNodeWithTag("guard_mascot").performClick()

        compose.runOnIdle { assertTrue(tapped) }
    }

    @Test
    fun `renderer draws every canonical shield style without exception`() {
        // Every GuardianShieldStyle alternate is ported (ObsidianShieldBody/
        // LavaGuardianShellFacet). One composition, look driven: each look must
        // draw its stable frame — the obsidian palette/rim/facet geometry — without
        // throwing on the software canvas.
        var displayedLook by mutableStateOf(GuardianLook.ORIGINAL)
        compose.setContent {
            LavaTheme {
                GuardianMascotView(
                    state = GuardianMascotState.AWAKE,
                    animates = false,
                    look = displayedLook,
                )
            }
        }

        GuardianLook.entries.forEach { look ->
            compose.runOnIdle { displayedLook = look }
            compose.onNodeWithTag("guard_mascot").assertExists()
        }
    }

    @Test
    fun `renderer draws every stable mascot pose without exception`() {
        // One composition, state driven: the compose rule rejects repeated
        // setContent calls, so the pose under test rotates through a state holder.
        var displayedState by mutableStateOf(GuardianMascotState.SLEEPING)
        compose.setContent {
            LavaTheme {
                GuardianMascotView(
                    state = displayedState,
                    animates = false,
                )
            }
        }

        GuardianMascotState.entries.forEach { state ->
            // animates = false pins the renderer on the stable frame: no animation
            // coroutines, so each pose draws deterministically.
            compose.runOnIdle { displayedState = state }
            compose.onNodeWithTag("guard_mascot").assertExists()
        }
    }
}
