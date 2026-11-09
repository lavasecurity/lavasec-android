package com.lavasec.app.presentation.filters

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.foundation.filter.FilterDraftReviewRejection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilterReviewScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `review renders the grouped diff with plus and minus semantics`() {
        setScreen(reviewState, actions)

        compose.onNodeWithTag("filter_review_screen").assertExists()
        compose.onNodeWithText("Blocklists").assertExists()
        compose.onNodeWithText("Blocked Domains").assertExists()
        compose.onNodeWithText("Allowed Exceptions").assertExists()
        // Added rows render with the plus glyph role; removed with minus.
        compose.onAllNodesWithTag("filters_review_added_row").assertCountEquals(2)
        compose.onAllNodesWithTag("filters_review_removed_row").assertCountEquals(2)
        compose.onNodeWithText("StevenBlack Unified Hosts").assertExists()
        compose.onNodeWithText("trusted.example.com").assertExists()
        compose.onNodeWithText("ads.example.com").assertExists()
    }

    @Test
    fun `adding allowed exceptions shows the warning panel`() {
        setScreen(reviewState, actions)

        compose.onNodeWithTag("filters_review_allow_warning").assertExists()
        compose.onNodeWithText("Be extra careful").assertExists()
    }

    @Test
    fun `confirm is enabled without a rejection and dispatches the exact filter`() {
        val committed = mutableListOf<String>()
        setScreen(reviewState, actions.copy(onCommitReview = { committed += it }))

        compose.onNodeWithTag("filters_review_confirm").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("filters_review_confirm").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf("filter-balanced"), committed) }
    }

    @Test
    fun `a rejection disables confirm and renders the precise reason`() {
        setScreen(
            reviewState.copy(rejection = FilterDraftReviewRejection.NO_CHANGES),
            actions,
        )

        compose.onNodeWithTag("filters_review_rejection").assertExists()
        compose.onNodeWithText("Review cannot continue").assertExists()
        compose.onNodeWithText("There are no changes to review.").assertExists()
        compose.onNodeWithTag("filters_review_confirm").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a gone filter rejection renders its precise reason`() {
        setScreen(
            reviewState.copy(rejection = FilterDraftReviewRejection.FILTER_GONE),
            actions,
        )

        compose.onNodeWithText(
            "The filter you were editing is no longer available.",
        ).assertExists()
        compose.onNodeWithTag("filters_review_confirm").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a busy commit keeps confirm disabled`() {
        setScreen(reviewState.copy(busy = true), actions)

        compose.onNodeWithTag("filters_review_confirm").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `null review state keeps the honest unavailable panel`() {
        setScreen(null, actions)

        compose.onNodeWithTag("filters_review_unavailable").assertExists()
        compose.onNodeWithText("Review unavailable").assertExists()
        compose.onNodeWithText("The pending review is no longer available.").assertExists()
        compose.onNodeWithTag("filters_review_confirm").assertDoesNotExist()
    }

    private fun setScreen(state: FilterReviewState?, actions: FilterLibraryActions) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1f),
            ) {
                LavaTheme {
                    FilterReviewScreen(
                        onBack = {},
                        state = state,
                        actions = actions,
                    )
                }
            }
        }
    }

    private companion object {
        val actions = FilterLibraryActions()

        val reviewState = FilterReviewState(
            filterId = "filter-balanced",
            filterName = "Balanced",
            diff = FilterDraftDiffUi(
                addedSources = listOf("StevenBlack Unified Hosts"),
                removedSources = listOf("Block List Basic"),
                addedBlockedDomains = emptyList(),
                removedBlockedDomains = listOf("ads.example.com"),
                addedAllowedDomains = listOf("trusted.example.com"),
                removedAllowedDomains = emptyList(),
            ),
            rejection = null,
            busy = false,
        )
    }
}
