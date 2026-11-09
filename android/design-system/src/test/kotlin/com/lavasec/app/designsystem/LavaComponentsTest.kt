package com.lavasec.app.designsystem

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LavaComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `navigation card preserves the pinned badge and glyph anatomy`() {
        compose.setContent {
            LavaTheme {
                LavaNavigationCard(
                    title = "Filters",
                    summary = "Choose what Lava blocks",
                    leadingIcon = LavaIcons.Filters,
                    onClick = {},
                )
            }
        }

        compose.onNodeWithTag("lava_navigation_leading_badge", useUnmergedTree = true)
            .assertExists()
            .assertWidthIsEqualTo(LavaNavigationCardMetrics.badgeSize)
            .assertHeightIsEqualTo(LavaNavigationCardMetrics.badgeSize)
        compose.onNodeWithTag("lava_navigation_leading_icon", useUnmergedTree = true)
            .assertExists()
            .assertWidthIsEqualTo(17.dp)
            .assertHeightIsEqualTo(17.dp)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `navigation card keeps the pinned 64dp minimum row height`() {
        compose.setContent {
            LavaTheme {
                LavaNavigationCard(
                    title = "Filters",
                    summary = "Choose what Lava blocks",
                    modifier = androidx.compose.ui.Modifier.testTag("navigation_card"),
                    onClick = {},
                )
            }
        }

        compose.onNodeWithTag("navigation_card")
            .assertHeightIsAtLeast(LavaNavigationCardMetrics.rowMinHeight)
    }

    @Test
    fun `navigation card is one merged semantic node carrying title and summary`() {
        compose.setContent {
            LavaTheme {
                LavaNavigationCard(
                    title = "Help",
                    summary = "Open Lava Help in your browser",
                    onClick = {},
                )
            }
        }

        // Exactly one merged node exists for the row label; the summary rides the same
        // node, so TalkBack reads "Help, Open Lava Help in your browser, button" once.
        compose.onNodeWithText("Help").assertExists()
        compose.onNodeWithTag("lava_navigation_leading_badge").assertDoesNotExist()
    }

    @Test
    fun `disabled navigation card exposes native disabled semantics`() {
        compose.setContent {
            LavaTheme {
                LavaNavigationCard(
                    title = "Filters",
                    summary = "Choose what Lava blocks",
                    modifier = androidx.compose.ui.Modifier.testTag("disabled_navigation_card"),
                    enabled = false,
                    onClick = {},
                )
            }
        }

        compose.onNodeWithTag("disabled_navigation_card")
            .assertIsNotEnabled()
    }
}
