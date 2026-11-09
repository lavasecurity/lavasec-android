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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.filter.ManualRuleEditResult
import com.lavasec.app.foundation.filter.ManualRuleRejection
import com.lavasec.app.foundation.filter.ManualRuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilterDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `detail renders the two shelves with catalog names verbatim`() {
        setScreen(detailState, actions)

        compose.onNodeWithTag("filter_detail_screen").assertExists()
        compose.onNodeWithText("Lava blocks these").assertExists()
        compose.onNodeWithText("Lava lets these through").assertExists()
        compose.onNodeWithText("Block List Basic").assertExists()
        compose.onNodeWithText("Security & Threat Intel").assertExists()
        compose.onNodeWithText("ads.example.com").assertExists()
        compose.onNodeWithText("trusted.example.com").assertExists()
        compose.onNodeWithText("128 rules").assertExists()
        compose.onNodeWithText("rules in effect").assertExists()
    }

    @Test
    fun `an empty in-effect filter shows the not-protected alarm`() {
        setScreen(detailState.copy(blocksNothing = true, ruleCount = 0), actions)

        compose.onNodeWithTag("filters_empty_alarm").assertExists()
        compose.onNodeWithText("Blocks nothing — not protected").assertExists()
    }

    @Test
    fun `a frozen filter renders read-only without any edit affordance`() {
        setScreen(detailState.copy(frozen = true, editing = false), actions)

        compose.onNodeWithTag("filters_frozen_panel").assertExists()
        compose.onNodeWithTag("filters_begin_edit").assertDoesNotExist()
        compose.onNodeWithTag("filters_add_blocked_domain").assertDoesNotExist()
    }

    @Test
    fun `view mode offers the edit entry which begins a draft`() {
        val edits = mutableListOf<String>()
        setScreen(detailState, actions.copy(onBeginEdit = { edits += it }))

        compose.onNodeWithTag("filters_begin_edit").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf("filter-balanced"), edits) }
    }

    @Test
    fun `edit mode toggles sources through the callback and gates save on changes`() {
        val toggles = mutableListOf<Triple<String, String, Boolean>>()
        val state = detailState.copy(editing = true, draftHasChanges = true)
        setScreen(
            state,
            actions.copy(
                onToggleSource = { id, source, enabled ->
                    toggles += Triple(id, source, enabled)
                    true
                },
            ),
        )

        compose.onNodeWithTag("filters_source_remove_blocklistproject-basic").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(Triple("filter-balanced", "blocklistproject-basic", false)),
                toggles,
            )
        }
        compose.onNodeWithTag("filters_open_review").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("filters_open_review").performScrollTo().performClick()
    }

    @Test
    fun `an unchanged draft keeps the review button disabled`() {
        setScreen(detailState.copy(editing = true, draftHasChanges = false), actions)

        compose.onNodeWithTag("filters_open_review").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("filters_discard_draft").assertIsEnabled()
    }

    @Test
    fun `the block-domain sheet shows the usage counter and dispatches the editor result`() {
        val added = mutableListOf<String>()
        setScreen(
            detailState.copy(editing = true, draftHasChanges = true),
            actions.copy(
                onAddBlockedDomain = { _, domain ->
                    added += domain
                    ManualRuleEditResult.Applied(
                        previous = draft,
                        profile = draft,
                        normalizedDomain = "new.example.com",
                        target = ManualRuleTarget.BLOCKED,
                    )
                },
            ),
        )

        compose.onNodeWithTag("filters_add_blocked_domain").performScrollTo().performClick()

        compose.onNodeWithTag("filters_blocked_domain_sheet").assertExists()
        compose.onNodeWithText("1/25 blocked domains used").assertExists()
        compose.onNodeWithTag("filters_domain_input").performTextInput("new.example.com")
        compose.onNodeWithTag("filters_domain_submit").performClick()

        compose.runOnIdle { assertEquals(listOf("new.example.com"), added) }
        compose.onNodeWithTag("filters_blocked_domain_sheet").assertDoesNotExist()
    }

    @Test
    fun `the add-exception sheet shows the pinned warning and the inline rejection`() {
        setScreen(
            detailState.copy(editing = true, draftHasChanges = true),
            actions.copy(
                onAddAllowedException = { _, _ ->
                    ManualRuleEditResult.Rejected(draft, ManualRuleRejection.INVALID_DOMAIN)
                },
            ),
        )

        compose.onNodeWithTag("filters_add_allowed_exception").performScrollTo().performClick()

        compose.onNodeWithTag("filters_allowed_exception_sheet").assertExists()
        compose.onNodeWithText("Before you allow a site").assertExists()
        compose.onNodeWithText(
            "A site you allow here always gets through, even if a blocklist would block it. Only add sites you fully trust.",
        ).assertExists()
        compose.onNodeWithTag("filters_domain_input").performTextInput("not a domain")
        compose.onNodeWithTag("filters_domain_submit").performClick()

        compose.onNodeWithText("Domain cannot be added").assertExists()
        compose.onNodeWithText("Invalid domain").assertExists()
        compose.onNodeWithTag("filters_allowed_exception_sheet").assertExists()
    }

    @Test
    fun `removing a domain dispatches the exact target`() {
        val removed = mutableListOf<Pair<String, ManualRuleTarget>>()
        setScreen(
            detailState.copy(editing = true, draftHasChanges = true),
            actions.copy(
                onRemoveDomain = { _, domain, target -> removed += domain to target },
            ),
        )

        compose.onAllNodesWithTag("filters_domain_remove")[1].performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(
                listOf("trusted.example.com" to ManualRuleTarget.ALLOWED),
                removed,
            )
        }
    }

    @Test
    fun `discarding a dirty draft asks first`() {
        val discarded = mutableListOf<String>()
        setScreen(
            detailState.copy(editing = true, draftHasChanges = true),
            actions.copy(onDiscardDraft = { discarded += it }),
        )

        compose.onNodeWithTag("filters_discard_draft").performScrollTo().performClick()

        compose.onNodeWithTag("filters_discard_draft_dialog").assertExists()
        compose.onNodeWithText(
            "Your draft changes will be removed. The current saved filter will stay active.",
        ).assertExists()
        compose.onNodeWithTag("filters_discard_draft_confirm").performClick()

        compose.runOnIdle { assertEquals(listOf("filter-balanced"), discarded) }
    }

    private fun setScreen(state: FilterDetailState, actions: FilterLibraryActions) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1f),
            ) {
                LavaTheme {
                    FilterDetailScreen(
                        onBack = {},
                        state = state,
                        actions = actions,
                    )
                }
            }
        }
    }

    private companion object {
        val draft = FilterProfileDraft(
            id = "filter-balanced",
            name = "Balanced",
            protectionLevel = com.lavasec.app.foundation.config.ProtectionLevel.BALANCED,
            enabledSourceIds = setOf("blocklistproject-basic"),
            manualRules = ManualDomainRules(
                blockedDomains = setOf("ads.example.com"),
                allowedDomains = setOf("trusted.example.com"),
            ),
        )

        val actions = FilterLibraryActions()

        val detailState = FilterDetailState(
            filterId = "filter-balanced",
            name = "Balanced",
            inEffect = true,
            frozen = false,
            blocksNothing = false,
            ruleCount = 128,
            sources = listOf(
                FilterSourceOptionUi(
                    id = "blocklistproject-basic",
                    name = "Block List Basic",
                    category = com.lavasec.app.foundation.filter.FilterSourceCategory.SECURITY,
                    selected = true,
                ),
                FilterSourceOptionUi(
                    id = "stevenblack-unified",
                    name = "StevenBlack Unified Hosts",
                    category = com.lavasec.app.foundation.filter.FilterSourceCategory.MULTI_PURPOSE,
                    selected = false,
                ),
            ),
            blockedDomains = listOf("ads.example.com"),
            allowedDomains = listOf("trusted.example.com"),
            maxAllowedDomains = 25,
            maxBlockedDomains = 25,
            editing = false,
            draftHasChanges = false,
            busy = false,
        )
    }
}
