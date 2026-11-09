package com.lavasec.app.presentation.filters

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.lavasec.app.designsystem.LavaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FiltersScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `overview renders the connection card, diagram, explanation, and Whats filtering`() {
        setScreen(libraryState, navigation)

        compose.onNodeWithTag("filters_screen").assertExists()
        compose.onNodeWithText("Filters").assert(hasHeading())
        compose.onNodeWithTag("filters_overview_card").assertExists()
        compose.onNodeWithTag("filters_flow_diagram").assertExists()
        compose.onNodeWithText("When the connection is").assertExists()
        compose.onNodeWithText("Normal").assertExists()
        compose.onNodeWithText(
            "Lava uses a local filter to block your phone's access to unwanted sites.",
        ).assertExists()
        compose.onNodeWithTag("filters_local_explanation").assertExists()
        compose.onNodeWithText("What's filtering?").assertExists()
        compose.onNodeWithTag("filters_now_filtering_row").assertExists()
        compose.onNodeWithTag("filters_your_filters_row").assertExists()
        // Diagram nodes: phone + Lava + internet labels.
        compose.onNodeWithText("Phone").assertExists()
        compose.onNodeWithText("Internet").assertExists()
        compose.onNodeWithText("Lava").assertExists()
    }

    @Test
    fun `the connection picker is educational and switches the caption and blocked hop`() {
        setScreen(libraryState, navigation)

        compose.onNodeWithTag("filters_connection_preview_chip").performClick()
        compose.onNodeWithTag("filters_connection_preview_option_poor").performClick()

        compose.onNodeWithText("Poor").assertExists()
        compose.onNodeWithText(
            "When your phone can't reach the internet, Lava blocks sites to stay safe " +
                "— they load again once your connection is back.",
        ).assertExists()
        compose.onNodeWithContentDescription("Blocked").assertExists()
    }

    @Test
    fun `Now filtering names the active filter verbatim and opens the active detail`() {
        val opened = mutableListOf<String>()
        setScreen(
            libraryState,
            FilterOverviewNavigation(onOpenActiveDetail = { opened += "active" }, onOpenLibrary = {}),
        )

        compose.onNodeWithText("Balanced").assertExists()
        compose.onNodeWithTag("filters_now_filtering_row").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf("active"), opened) }
    }

    @Test
    fun `an empty active filter swaps the name for the not-protected warning`() {
        setScreen(emptyActiveLibraryState, navigation)

        compose.onNodeWithText("Blocks nothing — not protected").assertExists()
    }

    @Test
    fun `Your filters opens the library`() {
        val opened = mutableListOf<String>()
        setScreen(
            libraryState,
            FilterOverviewNavigation(onOpenActiveDetail = {}, onOpenLibrary = { opened += "library" }),
        )

        compose.onNodeWithTag("filters_your_filters_row").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf("library"), opened) }
    }

    @Test
    fun `null state keeps the honest unavailable panel without rows`() {
        setScreen(null, navigation)

        compose.onNodeWithTag("filters_unavailable_panel").assertExists()
        compose.onNodeWithText("Live filter data is unavailable.").assertExists()
        compose.onNodeWithTag("filters_now_filtering_row").assertDoesNotExist()
        compose.onNodeWithTag("filters_overview_card").assertDoesNotExist()
    }

    @Test
    fun `absent navigation omits the Whats filtering rows entirely`() {
        setScreen(libraryState, null)

        compose.onNodeWithTag("filters_now_filtering_row").assertDoesNotExist()
        compose.onNodeWithTag("filters_your_filters_row").assertDoesNotExist()
        compose.onNodeWithText("What's filtering?").assertDoesNotExist()
    }

    @Test
    fun `overview rows stay reachable at short height and enlarged font`() {
        setScreen(libraryState, navigation, fontScale = 2f)

        compose.onNodeWithTag("filters_your_filters_row")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun setScreen(
        state: FilterLibraryState?,
        navigation: FilterOverviewNavigation?,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                LavaTheme {
                    FiltersScreen(
                        onBack = {},
                        state = state,
                        navigation = navigation,
                    )
                }
            }
        }
    }

    private fun hasHeading(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private companion object {
        val navigation = FilterOverviewNavigation(
            onOpenActiveDetail = {},
            onOpenLibrary = {},
        )

        val libraryState = FilterLibraryState(
            filters = listOf(
                FilterRowUi("core", "Core", false, false, false, null),
                FilterRowUi("balanced", "Balanced", true, false, false, 128),
                FilterRowUi("extra", "Extra", false, false, false, null),
            ),
            activeFilterId = "balanced",
            maxFilters = 3,
        )

        val emptyActiveLibraryState = FilterLibraryState(
            filters = listOf(
                FilterRowUi("core", "Core", false, false, false, null),
                FilterRowUi("balanced", "Balanced", true, false, true, 0),
                FilterRowUi("extra", "Extra", false, false, false, null),
            ),
            activeFilterId = "balanced",
            maxFilters = 3,
        )
    }
}
