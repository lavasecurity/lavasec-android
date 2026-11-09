package com.lavasec.app.presentation.filters

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
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
class FilterLibraryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `library renders rows with the active marker and honest summaries`() {
        setScreen(libraryState, actions)

        compose.onNodeWithTag("filter_library_screen").assertExists()
        compose.onAllNodesWithTag("filters_library_row").assertCountEquals(4)
        compose.onNodeWithTag("filters_active_marker").assertExists()
        compose.onNodeWithText("128 rules").assertExists()
        compose.onAllNodesWithText("Not the filter in effect").assertCountEquals(2)
        compose.onNodeWithText("Blocks nothing").assertExists()
        compose.onNodeWithText("Locked").assertExists()
    }

    @Test
    fun `a non-active row offers Apply versus View and edit and dispatches exactly`() {
        val applied = mutableListOf<String>()
        val viewed = mutableListOf<String>()
        setScreen(
            libraryState,
            actions = actions.copy(
                onApplyFilterRequest = { applied += it },
                onOpenDetail = { viewed += it },
            ),
        )

        compose.onNodeWithTag("filters_library_row_action_core").performScrollTo().performClick()

        compose.onNodeWithTag("filters_apply_choice_dialog").assertExists()
        compose.onNodeWithText(
            "Apply makes this the filter in effect. View & edit opens it to read or edit without switching.",
        ).assertExists()
        compose.onNodeWithTag("filters_apply_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("core"), applied) }

        compose.onNodeWithTag("filters_library_row_action_extra").performScrollTo().performClick()
        compose.onAllNodesWithTag("filters_apply_choice_dialog").assertCountEquals(1)
    }

    @Test
    fun `a frozen filter drops Apply and opens the read-only View directly`() {
        val applied = mutableListOf<String>()
        val viewed = mutableListOf<String>()
        setScreen(
            libraryState,
            actions = actions.copy(
                onApplyFilterRequest = { applied += it },
                onOpenDetail = { viewed += it },
            ),
        )

        // Frozen rows never offer Apply: the row's only action opens the read-only detail
        // (the detail surface carries the frozen explanation).
        compose.onNodeWithTag("filters_library_row_action_filter-empty").performScrollTo().performClick()

        compose.onNodeWithTag("filters_apply_choice_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), applied)
            assertEquals(listOf("filter-empty"), viewed)
        }
    }

    @Test
    fun `edit mode stages deletions and the active filter is never deletable`() {
        val deleted = mutableListOf<Set<String>>()
        setScreen(libraryState, actions.copy(onDeleteFilters = { deleted += it }))

        compose.onNodeWithTag("filters_edit_library").performScrollTo().performClick()

        // Active row has no delete control; the two non-active editable rows do.
        compose.onNodeWithTag("filters_stage_delete_balanced").assertDoesNotExist()
        compose.onNodeWithTag("filters_stage_delete_core").assertExists()
        compose.onNodeWithTag("filters_stage_delete_extra").assertExists()
        // Frozen rows are never deletable.
        compose.onNodeWithTag("filters_stage_delete_filter-empty").assertDoesNotExist()

        compose.onNodeWithTag("filters_stage_delete_core").performScrollTo().performClick()
        compose.onNodeWithTag("filters_confirm_deletions").performScrollTo().performClick()

        compose.onNodeWithTag("filters_delete_confirm_dialog").assertExists()
        compose.onNodeWithText("Delete this filter?").assertExists()
        compose.onNodeWithText("This can't be undone").assertExists()
        compose.onNodeWithTag("filters_delete_confirm").performClick()

        compose.runOnIdle { assertEquals(listOf(setOf("core")), deleted) }
        compose.onNodeWithTag("filters_delete_confirm_dialog").assertDoesNotExist()
    }

    @Test
    fun `leaving edit mode with staged deletions asks before discarding`() {
        setScreen(libraryState, actions)

        compose.onNodeWithTag("filters_edit_library").performScrollTo().performClick()
        compose.onNodeWithTag("filters_stage_delete_core").performScrollTo().performClick()
        compose.onNodeWithTag("filters_close_edit_mode").performScrollTo().performClick()

        compose.onNodeWithTag("filters_discard_dialog").assertExists()
        compose.onNodeWithText("Your staged filter deletions won't be applied.").assertExists()
        compose.onNodeWithTag("filters_discard_confirm").performClick()
        compose.onNodeWithTag("filters_discard_dialog").assertDoesNotExist()
    }

    @Test
    fun `restore defaults asks for confirmation and dispatches`() {
        val restored = mutableListOf<Boolean>()
        setScreen(libraryState, actions.copy(onRestoreDefaults = { restored += true }))

        compose.onNodeWithTag("filters_edit_library").performScrollTo().performClick()
        compose.onNodeWithTag("filters_restore_defaults").performScrollTo().performClick()

        compose.onNodeWithTag("filters_restore_dialog").assertExists()
        compose.onNodeWithText(
            "This replaces your filters with the three defaults — Core, Balanced, and Extra — with Balanced in effect.",
        ).assertExists()
        compose.onNodeWithTag("filters_restore_confirm").performClick()

        compose.runOnIdle { assertEquals(listOf(true), restored) }
    }

    @Test
    fun `create opens the sheet and rejects a duplicate name inline`() {
        val created = mutableListOf<Pair<String, String?>>()
        setScreen(
            libraryState,
            actions.copy(onCreateFilter = { name, dup -> created += name to dup }),
        )

        compose.onNodeWithTag("filters_edit_library").performScrollTo().performClick()
        compose.onNodeWithTag("filters_add_filter").performScrollTo().performClick()

        compose.onNodeWithTag("filters_create_dialog").assertExists()
        compose.onNodeWithTag("filters_create_name").performTextInput("Balanced")
        compose.onNodeWithText("You already have a filter with that name.").assertExists()
        compose.onNodeWithTag("filters_create_confirm").assertIsNotEnabled()
    }

    @Test
    fun `create dispatches the name and duplicate-from choice`() {
        val created = mutableListOf<Pair<String, String?>>()
        setScreen(
            libraryState,
            actions.copy(onCreateFilter = { name, dup -> created += name to dup }),
        )

        compose.onNodeWithTag("filters_edit_library").performScrollTo().performClick()
        compose.onNodeWithTag("filters_add_filter").performScrollTo().performClick()
        compose.onNodeWithTag("filters_create_name").performTextInput("Gaming")
        compose.onNodeWithTag("filters_duplicate_from_core").performClick()
        compose.onNodeWithTag("filters_create_confirm").performClick()

        compose.runOnIdle { assertEquals(listOf("Gaming" to "core"), created) }
    }

    @Test
    fun `a successful create closes the sheet so the new name is not re-validated as duplicate`() {
        val mutation = mutableStateOf<FilterLibraryMutationUiState>(
            FilterLibraryMutationUiState.Idle,
        )
        setScreen(
            libraryState,
            actions,
            mutationState = { mutation.value },
        )

        compose.onNodeWithTag("filters_edit_library").performScrollTo().performClick()
        compose.onNodeWithTag("filters_add_filter").performScrollTo().performClick()
        compose.onNodeWithTag("filters_create_dialog").assertExists()

        compose.runOnIdle {
            mutation.value = FilterLibraryMutationUiState.Succeeded(
                kind = FilterLibraryMutationKindUi.CREATE,
                filterId = null,
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("filters_create_dialog").assertDoesNotExist()
    }

    @Test
    fun `a switch failure names its target`() {
        setScreen(
            libraryState,
            actions,
            switchState = FilterProfileSwitchUiState.Failed(
                "extra",
                FilterProfileSwitchFailureUi.COMMIT,
            ),
        )

        compose.onNodeWithTag("filters_switch_error").performScrollTo().assertExists()
        compose.onNodeWithText(
            "Couldn't switch to Extra. Open Lava to check your filter settings.",
        ).assertExists()
    }

    @Test
    fun `a frozen switch refusal has precise copy`() {
        setScreen(
            libraryState,
            actions,
            switchState = FilterProfileSwitchUiState.Failed(
                "filter-empty",
                FilterProfileSwitchFailureUi.FROZEN_TARGET,
            ),
        )

        compose.onNodeWithText("This filter is locked and cannot be applied.").assertExists()
    }

    @Test
    fun `rows stay reachable at enlarged font`() {
        setScreen(libraryState, actions, fontScale = 2f)

        compose.onNodeWithText("Extra")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun setScreen(
        state: FilterLibraryState,
        actions: FilterLibraryActions,
        switchState: FilterProfileSwitchUiState = FilterProfileSwitchUiState.Idle,
        fontScale: Float = 1f,
        mutationState: () -> FilterLibraryMutationUiState = { FilterLibraryMutationUiState.Idle },
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                LavaTheme {
                    FilterLibraryScreen(
                        onBack = {},
                        state = state,
                        switchState = switchState,
                        mutationState = mutationState(),
                        actions = actions,
                    )
                }
            }
        }
    }

    private companion object {
        val actions = FilterLibraryActions()

        val libraryState = FilterLibraryState(
            filters = listOf(
                FilterRowUi("core", "Core", false, false, false, null),
                FilterRowUi("balanced", "Balanced", true, false, false, 128),
                FilterRowUi("extra", "Extra", false, false, false, null),
                FilterRowUi("filter-empty", "Empty", false, true, true, null),
            ),
            activeFilterId = "balanced",
            maxFilters = 3,
        )
    }
}
