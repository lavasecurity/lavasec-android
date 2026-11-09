package com.lavasec.app.presentation

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import com.lavasec.app.designsystem.LavaMetrics
import org.junit.Assert.assertTrue

/**
 * Shared geometry assertions for the slice-07 parity gates. Every check reads composed
 * `SemanticsNode.boundsInRoot` values — never screenshot pixels — so the assertions are
 * deterministic across the API 28/API 36 Robolectric viewports and encode the visual
 * contract as exact dp facts ("no card clipped by the navigation surface" is a bounds
 * comparison, not an image-diff tolerance).
 *
 * These helpers exist so the shell-level checks and the per-token checks share one
 * measurement vocabulary: the shell navigation surface is identified by tag, and each
 * named token (inset, row minimum, badge, gaps, action, guardian) is asserted against
 * its design-token constant.
 */
object ParityGeometryAssertions {

    /**
     * Asserts the tagged content node's bottom is at least [minimumClearance] above the
     * navigation surface's top. The navigation surface is tagged `product_navigation_bar`
     * by the compact shell (the layout the acceptance contract measures); callers on the
     * expanded rail pass the rail's tag explicitly.
     */
    fun assertContentClearsNavigationSurface(
        compose: ComposeContentTestRule,
        contentTag: String,
        navigationTag: String = "product_navigation_bar",
        minimumClearance: Dp = LavaMetrics.navSurfaceClearance,
    ) {
        compose.runOnIdle {
            val content = compose.onNodeWithTag(contentTag).fetchSemanticsNode()
            val surface = compose.onNodeWithTag(navigationTag).fetchSemanticsNode()
            val clearancePx = surface.boundsInRoot.top - content.boundsInRoot.bottom
            val clearance = compose.density.run { clearancePx.toDp() }
            assertTrue(
                "$contentTag bottom ${content.boundsInRoot.bottom} must clear the navigation " +
                    "surface top ${surface.boundsInRoot.top} by at least $minimumClearance; " +
                    "measured $clearance",
                clearance >= minimumClearance,
            )
        }
    }

    /**
     * Asserts the tagged content node starts exactly [expectedInset] inside the tagged
     * screen container (the pinned 18dp screen inset). Both bounds come from the same
     * root coordinate space, so the check is immune to the Robolectric host-window
     * offset that shifts every node's absolute root position by a constant.
     */
    fun assertScreenInsetFromStart(
        compose: ComposeContentTestRule,
        containerTag: String,
        contentTag: String,
        expectedInset: Dp = LavaMetrics.screenInset,
    ) {
        compose.runOnIdle {
            val container = compose.onNodeWithTag(containerTag).fetchSemanticsNode()
            val content = compose.onNodeWithTag(contentTag).fetchSemanticsNode()
            val insetPx = content.boundsInRoot.left - container.boundsInRoot.left
            val inset = compose.density.run { insetPx.toDp() }
            assertTrue(
                "$contentTag left edge must sit exactly $expectedInset inside $containerTag; " +
                    "measured $inset",
                inset == expectedInset,
            )
        }
    }

    /**
     * Asserts the tagged content node ends exactly [expectedInset] inside the tagged
     * screen container — the right-hand half of the pinned screen-inset contract.
     */
    fun assertScreenInsetFromEnd(
        compose: ComposeContentTestRule,
        containerTag: String,
        contentTag: String,
        expectedInset: Dp = LavaMetrics.screenInset,
    ) {
        compose.runOnIdle {
            val container = compose.onNodeWithTag(containerTag).fetchSemanticsNode()
            val content = compose.onNodeWithTag(contentTag).fetchSemanticsNode()
            val insetPx = container.boundsInRoot.right - content.boundsInRoot.right
            val inset = compose.density.run { insetPx.toDp() }
            assertTrue(
                "$contentTag right edge must sit exactly $expectedInset inside $containerTag; " +
                    "measured $inset",
                inset == expectedInset,
            )
        }
    }

    /** Asserts the tagged node meets a minimum height (64dp navigation rows, 56dp action). */
    fun assertMinimumHeight(
        compose: ComposeContentTestRule,
        tag: String,
        minimum: Dp,
    ) {
        compose.runOnIdle {
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val height = compose.density.run { node.boundsInRoot.height.toDp() }
            assertTrue(
                "$tag height ${node.boundsInRoot.height} must be at least $minimum; measured $height",
                height >= minimum,
            )
        }
    }

    /** Asserts the tagged node is exactly the given size on both axes (badge, guardian). */
    fun assertExactSize(
        compose: ComposeContentTestRule,
        tag: String,
        expected: Dp,
    ) {
        compose.runOnIdle {
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val width = compose.density.run { node.boundsInRoot.width.toDp() }
            val height = compose.density.run { node.boundsInRoot.height.toDp() }
            assertTrue(
                "$tag must be exactly ${expected}x$expected; measured ${width}x$height",
                width == expected && height == expected,
            )
        }
    }

    /**
     * Asserts the vertical gap between two tagged content nodes — the shared section
     * rhythm between compact cards (10dp) inside one Settings/Filters section. Both
     * bounds are composed, so the check survives font-scale and density changes.
     */
    fun assertVerticalGap(
        compose: ComposeContentTestRule,
        aboveTag: String,
        belowTag: String,
        expectedGap: Dp,
    ) {
        compose.runOnIdle {
            val above = compose.onNodeWithTag(aboveTag).fetchSemanticsNode()
            val below = compose.onNodeWithTag(belowTag).fetchSemanticsNode()
            val gapPx = below.boundsInRoot.top - above.boundsInRoot.bottom
            val gap = compose.density.run { gapPx.toDp() }
            assertTrue(
                "gap between $aboveTag bottom ${above.boundsInRoot.bottom} and $belowTag top " +
                    "${below.boundsInRoot.top} must be exactly $expectedGap; measured $gap",
                gap == expectedGap,
            )
        }
    }

    /**
     * Asserts the horizontal gap between a leading badge and its row title — the pinned
     * badge-to-text rhythm (14dp). The title is located by its text in the unmerged tree
     * (navigation rows merge their descendants into one semantic node, so the title is
     * only individually addressable there), and the badge is the unmerged badge node
     * immediately to its left on the same vertical band. Callers must render the row
     * inside the viewport: nodes scrolled out of the Robolectric viewport report
     * degenerate zero bounds, so the geometry suites compose a tall viewport instead of
     * scrolling.
     */
    fun assertBadgeToTitleGap(
        compose: ComposeContentTestRule,
        badgeTag: String,
        titleText: String,
        expectedGap: Dp = LavaMetrics.badgeToTextGap,
    ) {
        compose.runOnIdle {
            val title = compose.onNodeWithText(titleText, useUnmergedTree = true)
                .fetchSemanticsNode()
            val badge = compose.onAllNodesWithTag(badgeTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { node ->
                    node.boundsInRoot.right <= title.boundsInRoot.left &&
                        node.boundsInRoot.bottom > title.boundsInRoot.top &&
                        node.boundsInRoot.top < title.boundsInRoot.bottom
                }
                .maxByOrNull { it.boundsInRoot.right }
                ?: error("no $badgeTag node found immediately left of title \"$titleText\"")
            val gapPx = title.boundsInRoot.left - badge.boundsInRoot.right
            val gap = compose.density.run { gapPx.toDp() }
            assertTrue(
                "gap between $badgeTag right ${badge.boundsInRoot.right} and title \"$titleText\" " +
                    "left ${title.boundsInRoot.left} must be exactly $expectedGap; measured $gap",
                gap == expectedGap,
            )
        }
    }
}
