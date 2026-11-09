package com.lavasec.app.presentation.filters

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.lavasec.app.designsystem.LavaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilterPreparationScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `preparing renders the progress phase with no failure affordances`() {
        setScreen(FilterPreparationUiState.Preparing("Balanced"), actions)

        compose.onNodeWithTag("filter_preparation_screen").assertExists()
        compose.onNodeWithTag("filters_prepare_progress_title").assertExists()
        compose.onNodeWithText("Building the filter").assertExists()
        compose.onNodeWithTag("filters_prepare_retry").assertDoesNotExist()
        compose.onNodeWithTag("filters_prepare_keep_current").assertDoesNotExist()
    }

    @Test
    fun `success renders the success phase`() {
        setScreen(FilterPreparationUiState.Success, actions)

        compose.onNodeWithTag("filters_prepare_success").assertExists()
        compose.onNodeWithText("Success").assertExists()
        compose.onNodeWithText("Filters updated.").assertExists()
    }

    @Test
    fun `a retryable failure offers retry, back to review, and keep current`() {
        val retried = mutableListOf<Boolean>()
        val kept = mutableListOf<Boolean>()
        setScreen(
            FilterPreparationUiState.Failed(retryable = true),
            actions.copy(
                onRetryPreparation = { retried += true },
                onKeepCurrentAfterFailure = { kept += true },
            ),
        )

        compose.onNodeWithTag("filters_prepare_failed_title").assertExists()
        compose.onNodeWithText("We couldn't update your filter").assertExists()
        compose.onNodeWithText("Your previous filter is still active.").assertExists()
        compose.onNodeWithTag("filters_prepare_retry").assertExists()
        compose.onNodeWithTag("filters_prepare_back_to_review").assertExists()
        compose.onNodeWithTag("filters_prepare_keep_current").assertExists()

        compose.onNodeWithTag("filters_prepare_retry").performClick()
        compose.onNodeWithTag("filters_prepare_keep_current").performClick()

        compose.runOnIdle {
            assertEquals(listOf(true), retried)
            assertEquals(listOf(true), kept)
        }
    }

    @Test
    fun `a dead-end failure offers only keep current`() {
        setScreen(FilterPreparationUiState.Failed(retryable = false), actions)

        compose.onNodeWithTag("filters_prepare_retry").assertDoesNotExist()
        compose.onNodeWithTag("filters_prepare_keep_current").assertExists()
    }

    @Test
    fun `idle renders the honest nothing-in-flight panel with a back affordance`() {
        setScreen(FilterPreparationUiState.Idle, actions)

        compose.onNodeWithText("Nothing is being prepared.").assertExists()
        compose.onNodeWithTag("filters_prepare_back").assertExists()
        compose.onAllNodesWithTag("filters_prepare_retry").assertCountEquals(0)
    }

    private fun setScreen(state: FilterPreparationUiState, actions: FilterLibraryActions) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1f),
            ) {
                LavaTheme {
                    FilterPreparationScreen(
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
    }
}
