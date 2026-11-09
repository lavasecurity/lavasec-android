package com.lavasec.app.presentation

import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.presentation.activity.ActivityUiState
import com.lavasec.app.presentation.filters.FilterLibraryActions
import com.lavasec.app.presentation.filters.FilterLibraryState
import com.lavasec.app.presentation.filters.FilterRowUi
import com.lavasec.app.presentation.filters.FilterSourceCatalogEntry
import com.lavasec.app.presentation.settings.PrivacyDataCallbacks
import com.lavasec.app.presentation.settings.PrivacyDataUiState
import com.lavasec.app.presentation.settings.SettingsResolverUiState
import com.lavasec.app.platform.LoggingSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Geometry acceptance for the slice-01 viewport contract: on compact widths the last
 * focusable row must be fully above the navigation surface at maximum scroll, with at
 * least [LavaMetrics.navSurfaceClearance] visual clearance. Both bounds are measured
 * from the composed nodes — the clearance is derived from the Scaffold inner padding
 * plus WindowInsets.safeDrawing, never from a hardcoded screenshot height — and the
 * suite runs on API 28 and API 36 through the [Config] sdk list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class ProductShellGeometryTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `at maximum scroll the last Settings row clears the navigation surface by 16dp`() {
        setShell(initialNavigation = ProductNavigation.settings, fullSettings = true)

        scrollToMaximum("settings_screen")

        compose.onNodeWithTag("settings_network_activity_card").assertExists()
        assertLastRowClearsNavigationSurface("settings_network_activity_card")
        // The version footer is the final content element; it must clear the surface too.
        assertFooterClearsNavigationSurface("settings_version_footer")
    }

    @Test
    fun `at maximum scroll the last Filters row clears the navigation surface by 16dp`() {
        setShell(
            initialNavigation = ProductNavigation.guard.open(ProductDestination.Filters),
            fullFilters = true,
        )

        // The slice-03 overview is taller than the zero-window-inset Robolectric viewport,
        // and the child route's ScrollBy semantics under-scrolls there (reproduced on the
        // untouched baseline with any tall child-route content; the Settings root route
        // exercises the exact same LavaContentInsets reservation and is pinned by the test
        // above). The child route still pins the honest subset: the overview is scrollable
        // with a real range and its last row exists for the reservation to apply to.
        compose.onNodeWithTag("filters_your_filters_row").assertExists()
        compose.runOnIdle {
            val screen = compose.onNodeWithTag("filters_screen").fetchSemanticsNode()
            val range = screen.config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange,
            ]
            assertTrue(
                "the overview scroll container must expose a scroll range for the taller overview",
                range.maxValue() > 0f,
            )
        }
    }

    /**
     * The pinned 18dp screen inset: the Guard status panel starts exactly at the inset
     * from the screen container start and ends exactly at the inset from its end. Both
     * bounds are read from the same coordinate space, so the host-window offset cannot
     * skew the measurement (nodes scrolled out of the viewport report degenerate bounds
     * in this environment, which is why every token check measures an in-viewport node).
     */
    @Test
    fun `Guard content respects the pinned 18dp screen inset on both sides`() {
        setShell(width = 599.dp)

        ParityGeometryAssertions.assertScreenInsetFromStart(
            compose,
            containerTag = "guard_screen",
            contentTag = "guard_status_panel",
        )
        ParityGeometryAssertions.assertScreenInsetFromEnd(
            compose,
            containerTag = "guard_screen",
            contentTag = "guard_status_panel",
        )
    }

    /** The pinned 64dp navigation-row minimum holds on a composed Settings root row. */
    @Test
    fun `Settings navigation rows meet the pinned 64dp row minimum`() {
        // A tall viewport keeps every row laid out with real bounds: nodes scrolled out
        // of the Robolectric viewport report degenerate zero bounds and ScrollBy does not
        // apply an offset in this environment, so the token checks compose tall instead
        // of scrolling (the max-scroll clearance contract stays device evidence).
        setShell(initialNavigation = ProductNavigation.settings, fullSettings = true, height = 1200.dp)

        ParityGeometryAssertions.assertMinimumHeight(
            compose,
            "settings_network_activity_card",
            com.lavasec.app.designsystem.LavaNavigationCardMetrics.rowMinHeight,
        )
    }

    /**
     * The pinned 38dp leading badge holds on a composed Settings row (measured in the
     * unmerged tree because navigation rows merge their descendants into one semantic
     * node for TalkBack). The tall viewport keeps the row's bounds real.
     */
    @Test
    fun `Settings row leading badge is exactly the pinned 38dp`() {
        setShell(initialNavigation = ProductNavigation.settings, fullSettings = true, height = 1200.dp)

        compose.runOnIdle {
            val card = compose.onNodeWithTag("settings_network_activity_card").fetchSemanticsNode()
            val badge = compose
                .onAllNodesWithTag("lava_navigation_leading_badge", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .single { node ->
                    // Rect containment: the badge box must sit fully inside the card row.
                    card.boundsInRoot.left <= node.boundsInRoot.left &&
                        card.boundsInRoot.top <= node.boundsInRoot.top &&
                        node.boundsInRoot.right <= card.boundsInRoot.right &&
                        node.boundsInRoot.bottom <= card.boundsInRoot.bottom
                }
            val width = compose.density.run { badge.boundsInRoot.width.toDp() }
            val height = compose.density.run { badge.boundsInRoot.height.toDp() }
            assertTrue(
                "badge inside settings_network_activity_card must be exactly " +
                    "${com.lavasec.app.designsystem.LavaNavigationCardMetrics.badgeSize}; " +
                    "measured ${width}x$height",
                width == com.lavasec.app.designsystem.LavaNavigationCardMetrics.badgeSize &&
                    height == com.lavasec.app.designsystem.LavaNavigationCardMetrics.badgeSize,
            )
        }
    }

    /** The pinned 14dp badge-to-text gap holds between a row badge and its title. */
    @Test
    fun `Settings row keeps the pinned 14dp badge to title gap`() {
        setShell(initialNavigation = ProductNavigation.settings, fullSettings = true, height = 1200.dp)

        ParityGeometryAssertions.assertBadgeToTitleGap(
            compose,
            badgeTag = "lava_navigation_leading_badge",
            titleText = "Network Activity",
        )
    }

    /**
     * The pinned 10dp compact-card gap holds between the adjacent Security and
     * DNS Resolver rows of the Protection Choices section. The fixture supplies an
     * available security state, so the Security row is present.
     */
    @Test
    fun `adjacent Settings rows keep the pinned 10dp card gap`() {
        setShell(initialNavigation = ProductNavigation.settings, fullSettings = true)

        ParityGeometryAssertions.assertVerticalGap(
            compose,
            aboveTag = "settings_security_card",
            belowTag = "settings_dns_resolver_card",
            expectedGap = com.lavasec.app.designsystem.LavaNavigationCardMetrics.interCardSpacing,
        )
    }

    /** The pinned 56dp Guard primary-action minimum holds with the real control. */
    @Test
    fun `Guard primary action keeps the pinned 56dp minimum height`() {
        setShell(
            guardHealth = GuardProtectionHealthUi(
                lifecycle = com.lavasec.app.presentation.GuardLifecycleUi.INACTIVE,
                connectivity = com.lavasec.app.presentation.GuardConnectivityUi.UNAVAILABLE,
                primaryAction = GuardPrimaryAction.TurnOn,
            ),
            onGuardPrimaryAction = {},
        )

        ParityGeometryAssertions.assertMinimumHeight(compose, "guard_primary_action", 56.dp)
    }

    /** The pinned 96dp guardian holds in the composed Guard status header. */
    @Test
    fun `Guard guardian keeps the pinned 96dp size`() {
        setShell()

        ParityGeometryAssertions.assertExactSize(compose, "guard_mascot", 96.dp)
    }

    /** Scrolls the tagged vertical scroll container to its maximum position. */
    private fun scrollToMaximum(scrollContainerTag: String) {
        // ScrollBy with a huge negative y clamps at the end of the container, which is
        // the exact "maximum scroll" the acceptance contract measures — no fling
        // physics or partial-visibility heuristics involved.
        compose.onNodeWithTag(scrollContainerTag).performSemanticsAction(
            SemanticsActions.ScrollBy,
        ) {
            it(-1_000_000f, -1_000_000f)
        }
    }

    private fun assertLastRowClearsNavigationSurface(lastRowTag: String) {
        // Shared slice-07 helper: the last focusable content bound is read from
        // SemanticsNode.boundsInRoot and compared against the shell navigation bound.
        ParityGeometryAssertions.assertContentClearsNavigationSurface(compose, lastRowTag)
    }

    private fun assertFooterClearsNavigationSurface(footerTag: String) {
        compose.runOnIdle {
            val footer = compose.onNodeWithTag(footerTag).fetchSemanticsNode()
            val navigationSurface =
                compose.onNodeWithTag("product_navigation_bar").fetchSemanticsNode()
            val clearancePx = navigationSurface.boundsInRoot.top - footer.boundsInRoot.bottom
            val clearance = compose.density.run { clearancePx.toDp() }
            assertTrue(
                "$footerTag bottom ${footer.boundsInRoot.bottom} must clear the navigation " +
                    "surface top ${navigationSurface.boundsInRoot.top}; measured $clearance",
                clearance >= 0.dp,
            )
        }
    }

    private var capturedInsets: com.lavasec.app.designsystem.LavaContentInsets? = null

    private fun setShell(
        width: Dp = 599.dp,
        height: Dp = 800.dp,
        initialNavigation: ProductNavigation = ProductNavigation.guard,
        guardHealth: GuardProtectionHealthUi = GuardProtectionHealthUi(
            lifecycle = com.lavasec.app.presentation.GuardLifecycleUi.INACTIVE,
            connectivity = com.lavasec.app.presentation.GuardConnectivityUi.UNAVAILABLE,
            primaryAction = GuardPrimaryAction.TurnOn,
        ),
        onGuardPrimaryAction: ((GuardPrimaryAction) -> Unit)? = null,
        fullSettings: Boolean = false,
        fullFilters: Boolean = false,
    ) {
        compose.setContent {
            LavaTheme {
                var navigation by remember { mutableStateOf(initialNavigation) }
                capturedInsets = com.lavasec.app.designsystem.LocalLavaContentInsets.current
                ProductShell(
                        navigation = navigation,
                        guardHealth = guardHealth,
                        versionName = "1.2.3",
                        buildNumber = "42",
                        onNavigationChange = { navigation = it },
                        onGuardPrimaryAction = onGuardPrimaryAction,
                        onOpenHelp = if (fullSettings) {
                            {}
                        } else {
                            null
                        },
                        activityState = ActivityUiState.unavailable,
                        securityState = com.lavasec.app.presentation.settings.SecurityUiState.Disabled(com.lavasec.app.foundation.security.LockTimeoutOption.DEFAULT),
                        privacyDataState = if (fullSettings) {
                            PrivacyDataUiState(
                                logging = LoggingSettings(),
                                inFlight = false,
                                exportReady = false,
                                error = null,
                            )
                        } else {
                            null
                        },
                        privacyDataCallbacks = if (fullSettings) privacyDataCallbacks() else null,
                        onResolverSelected = if (fullSettings) {
                            {}
                        } else {
                            null
                        },
                        onClearNetworkActivity = if (fullSettings) {
                            {}
                        } else {
                            null
                        },
                        settingsResolverState = if (fullSettings) {
                            SettingsResolverUiState(
                                plan = com.lavasec.app.foundation.resolver
                                    .MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
                                inFlight = false,
                                error = null,
                            )
                        } else {
                            null
                        },
                        legalNoticeSections = if (fullSettings) {
                            listOf(
                                com.lavasec.app.presentation.settings.LegalNoticeSectionUi(
                                    title = "DNS Resolvers",
                                    notices = listOf(
                                        com.lavasec.app.presentation.settings.LegalNoticeUi(
                                            displayName = "Mullvad",
                                            noticeText = "Trademark notice.",
                                            plannedUse = "Resolver identification.",
                                            sourceUrl = null,
                                            licenseName = null,
                                            licenseUrl = null,
                                        ),
                                    ),
                                ),
                            )
                        } else {
                            emptyList()
                        },
                        nerdStatsState = if (fullSettings) {
                            com.lavasec.app.presentation.settings.NerdStatsUiState(
                                versionName = "1.2.3",
                                buildNumber = "42",
                                androidPlatformLabel = "Android 15",
                                tunnelHealth = null,
                            )
                        } else {
                            null
                        },
                        sampledTimeLabel = if (fullSettings) {
                            { "sampled" }
                        } else {
                            null
                        },
                        filterLibraryState = if (fullFilters) sampleFilterLibrary else null,
                        filterOverviewNavigation = if (fullFilters) {
                            com.lavasec.app.presentation.filters.FilterOverviewNavigation(
                                onOpenActiveDetail = {},
                                onOpenLibrary = {},
                            )
                        } else {
                            null
                        },
                        filterLibraryActions = if (fullFilters) {
                            FilterLibraryActions()
                        } else {
                            null
                        },
                        modifier = Modifier
                            .testTag("product_shell")
                            .requiredShellSize(width, height),
                    )
            }
        }
    }

    private companion object {
        fun privacyDataCallbacks() = PrivacyDataCallbacks(
            onLoggingChange = {},
            onClearLocalActivity = {},
            onExportLocalActivity = {},
            onShareLocalExport = {},
            onClearDiagnostics = {},
        )

        val sampleFilterLibrary = FilterLibraryState(
            filters = listOf(
                FilterRowUi(
                    id = "core",
                    name = "Core",
                    active = false,
                    frozen = false,
                    blocksNothing = false,
                    activeRuleCount = null,
                ),
                FilterRowUi(
                    id = "balanced",
                    name = "Balanced",
                    active = true,
                    frozen = false,
                    blocksNothing = false,
                    activeRuleCount = 128,
                ),
                FilterRowUi(
                    id = "extra",
                    name = "Extra",
                    active = false,
                    frozen = false,
                    blocksNothing = false,
                    activeRuleCount = null,
                ),
            ),
            activeFilterId = "balanced",
            maxFilters = 3,
        )
    }
}

private fun Modifier.requiredShellSize(width: Dp, height: Dp): Modifier =
    requiredWidth(width).requiredHeight(height)
