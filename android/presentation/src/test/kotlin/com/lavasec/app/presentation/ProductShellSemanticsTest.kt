package com.lavasec.app.presentation

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.presentation.activity.ActivityContentState
import com.lavasec.app.presentation.activity.ActivityDateRangeUi
import com.lavasec.app.presentation.activity.ActivityDecisionUi
import com.lavasec.app.presentation.activity.ActivityLogAvailabilityUi
import com.lavasec.app.presentation.activity.ActivityUiState
import com.lavasec.app.presentation.activity.DomainActionChangeGroupUi
import com.lavasec.app.presentation.activity.DomainActionChangeKindUi
import com.lavasec.app.presentation.activity.DomainActionChangeUi
import com.lavasec.app.presentation.activity.DomainActionReviewUi
import com.lavasec.app.presentation.activity.DomainActionUi
import com.lavasec.app.presentation.activity.DomainActionUiState
import com.lavasec.app.presentation.activity.DomainHistoryReasonUi
import com.lavasec.app.presentation.activity.DomainHistoryRowUi
import com.lavasec.app.presentation.activity.DomainHistorySnapshot
import com.lavasec.app.presentation.activity.DomainReviewCallbacks
import com.lavasec.app.presentation.activity.TopDomainRowUi
import com.lavasec.app.presentation.activity.TopDomainsSnapshot
import com.lavasec.app.presentation.filters.FilterLibraryState
import com.lavasec.app.presentation.filters.FilterRowUi
import com.lavasec.app.presentation.filters.FilterSourceCatalogEntry
import com.lavasec.app.presentation.settings.LegalNoticeSectionUi
import com.lavasec.app.presentation.settings.LegalNoticeUi
import com.lavasec.app.presentation.settings.NerdStatsUiState
import com.lavasec.app.presentation.settings.PrivacyDataCallbacks
import com.lavasec.app.presentation.settings.PrivacyDataUiState
import com.lavasec.app.platform.LoggingSettings
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProductShellSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `compact shell exposes exactly Guard and Settings in a navigation bar`() {
        setShell(width = 599.dp)

        compose.onNodeWithTag("product_navigation_bar").assertExists()
        compose.onNodeWithTag("product_navigation_rail").assertDoesNotExist()
        compose.onAllNodes(rootDestinationMatcher).assertCountEquals(2)
        compose.onNodeWithTag("product_root_guard").assertExists()
        compose.onNodeWithTag("product_root_settings").assertExists()
        compose.onNodeWithTag("product_root_filters").assertDoesNotExist()
        compose.onNodeWithTag("product_root_activity").assertDoesNotExist()
    }

    @Test
    fun `root navigation exposes exactly one Selected state and reads the label once`() {
        setShell()

        compose.onNodeWithTag("product_root_guard").assertIsSelected()
        compose.onNodeWithTag("product_root_settings").assertIsNotSelected()
        // The selected root's semantics node carries the label exactly once, so
        // TalkBack does not read the same root twice.
        compose.runOnIdle {
            val guardNode = compose.onNodeWithTag("product_root_guard").fetchSemanticsNode()
            assertEquals(
                listOf(AnnotatedString("Guard")),
                guardNode.config[SemanticsProperties.Text],
            )
        }

        compose.onNodeWithTag("product_root_settings").performClick()

        compose.onNodeWithTag("product_root_settings").assertIsSelected()
        compose.onNodeWithTag("product_root_guard").assertIsNotSelected()
    }

    @Test
    fun `reduced motion keeps navigation and Guard content bounds stable across root switches`() {
        setShell(reducedMotion = true)

        val barBefore = compose.onNodeWithTag("product_navigation_bar").getBoundsInRoot()
        val panelBefore = compose.onNodeWithTag("guard_status_panel").getBoundsInRoot()

        compose.onNodeWithTag("product_root_settings").performClick()
        compose.onNodeWithTag("product_root_guard").performClick()

        // The mascot/selection treatment must not move the navigation surface or the
        // Guard content bounds when motion is reduced.
        compose.runOnIdle {
            assertEquals(
                barBefore,
                compose.onNodeWithTag("product_navigation_bar").getBoundsInRoot(),
            )
            assertEquals(
                panelBefore,
                compose.onNodeWithTag("guard_status_panel").getBoundsInRoot(),
            )
        }
    }

    @Test
    fun `expanded shell exposes exactly Guard and Settings in a navigation rail`() {
        setShell(width = 600.dp)

        compose.onNodeWithTag("product_navigation_rail").assertExists()
        compose.onNodeWithTag("product_navigation_bar").assertDoesNotExist()
        compose.onAllNodes(rootDestinationMatcher).assertCountEquals(2)
    }

    @Test
    fun `Guard shows one live status panel and its two nested journeys`() {
        setShell()

        compose.onAllNodesWithTag("guard_status_panel").assertCountEquals(1)
        // The status header merges into one accessibility element with the stable
        // label and the composed title/subtitle value.
        compose.runOnIdle {
            val node = compose.onNodeWithTag("guard_status_text").fetchSemanticsNode()
            assertEquals(
                "Protection status",
                node.config[SemanticsProperties.ContentDescription].single(),
            )
            assertEquals(
                "Protection Off. Local DNS filtering is off",
                node.config[SemanticsProperties.StateDescription],
            )
        }
        compose.onAllNodesWithText("How Lava filters").assertCountEquals(1)
        compose.onAllNodesWithText("What Lava has caught").assertCountEquals(1)
        compose.onNodeWithTag("guard_primary_action").assertDoesNotExist()
    }

    @Test
    fun `Guard renders and dispatches its semantic primary action only with a real callback`() {
        val actions = mutableListOf<GuardPrimaryAction>()
        setShell(
            guardHealth = offState.copy(primaryAction = GuardPrimaryAction.TurnOn),
            onGuardPrimaryAction = actions::add,
        )

        compose.onNodeWithTag("guard_primary_action").assertExists().performClick()

        compose.runOnIdle {
            assertEquals(listOf(GuardPrimaryAction.TurnOn), actions)
        }
    }

    @Test
    fun `Filters child reports an unavailable filter library without a switch or clickable placeholder`() {
        setShell()

        compose.onNodeWithText("How Lava filters").performClick()

        compose.onNodeWithText("Live filter data is unavailable.")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .assertCountEquals(0)
        compose.onAllNodesWithTag("filters_profile_card").assertCountEquals(0)
        compose.onAllNodesWithTag("filters_active_marker").assertCountEquals(0)
        compose.onNodeWithText("Apply").assertDoesNotExist()
    }

    @Test
    fun `Filters child renders the overview without fabricated mutation controls`() {
        setShell(filterLibraryState = sampleFilterLibrary)

        compose.onNodeWithText("How Lava filters").performClick()

        compose.onNodeWithTag("filters_unavailable_panel").assertDoesNotExist()
        compose.onNodeWithTag("filters_overview_card").assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .assertCountEquals(0)
        // Without navigation callbacks the shell must not fabricate dead rows.
        compose.onNodeWithTag("filters_now_filtering_row").assertDoesNotExist()
        compose.onNodeWithTag("filters_your_filters_row").assertDoesNotExist()
    }

    @Test
    fun `Filters overview routes to the library through the Your filters row`() {
        val opened = mutableListOf<String>()
        setShell(
            filterLibraryState = sampleFilterLibrary,
            filterOverviewNavigation =
                com.lavasec.app.presentation.filters.FilterOverviewNavigation(
                    onOpenActiveDetail = {},
                    onOpenLibrary = { opened += "library" },
                ),
        )

        compose.onNodeWithText("How Lava filters").performClick()

        compose.onNodeWithTag("filters_your_filters_row").performClick()

        compose.runOnIdle { assertEquals(listOf("library"), opened) }
    }

    @Test
    fun `Filters overview opens the active filter detail through Now filtering`() {
        val opened = mutableListOf<String>()
        setShell(
            filterLibraryState = sampleFilterLibrary,
            filterOverviewNavigation =
                com.lavasec.app.presentation.filters.FilterOverviewNavigation(
                    onOpenActiveDetail = { opened += "active" },
                    onOpenLibrary = {},
                ),
        )

        compose.onNodeWithText("How Lava filters").performClick()

        compose.onNodeWithTag("filters_now_filtering_row").performClick()

        compose.runOnIdle { assertEquals(listOf("active"), opened) }
    }

    @Test
    fun `Activity child reports unavailable local evidence without a fake count or action`() {
        setShell()

        compose.onNodeWithText("What Lava has caught").performClick()

        compose.onNodeWithText("Local Activity evidence is unavailable.")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("0").assertDoesNotExist()
        compose.onNodeWithTag("activity_unavailable_action").assertDoesNotExist()
    }

    @Test
    fun `Settings shows the trust rows and build details and opens external Help`() {
        var helpOpened = false
        setShell(
            onOpenHelp = { helpOpened = true },
            customizationState = com.lavasec.app.presentation.settings.CustomizationUiState(),
            customizationCallbacks = com.lavasec.app.presentation.settings
                .CustomizationCallbacks(
                    onReduceMotionChanged = {},
                    onHapticsChanged = {},
                    onOpenNotificationSettings = {},
                ),
        )

        compose.onNodeWithTag("product_root_settings").performClick()

        // The slice-04 trust rows carry the honest unavailable/typed summaries.
        compose.onNodeWithText("Account setup pending").assertExists()
        compose.onNodeWithText("Plus isn't on Android yet").assertExists()
        compose.onNodeWithText("Make Lava Security yours").assertExists()
        compose.onNodeWithText("Version 1.2.3 (42)").assertExists()
        compose.onNodeWithText("Help").assertExists().performClick()
        compose.runOnIdle { assertTrue(helpOpened) }
    }

    @Test
    fun `every page title is exposed as an accessibility heading`() {
        setShell()

        compose.onAllNodesWithText("Guard").filter(headingMatcher).assertCountEquals(1)
        compose.onNodeWithText("How Lava filters").performClick()
        compose.onNodeWithText("Filters").assert(headingMatcher)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("What Lava has caught").performClick()
        compose.onNodeWithText("Activity").assert(headingMatcher)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("product_root_settings").performClick()
        compose.onAllNodesWithText("Settings").filter(headingMatcher).assertCountEquals(1)
    }

    @Test
    fun `Guard lower journey remains reachable at short height and enlarged font`() {
        setShell(height = 240.dp, fontScale = 2f)

        compose.onNodeWithText("What Lava has caught")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `Guard primary action grows and remains reachable at short height and enlarged font`() {
        val actions = mutableListOf<GuardPrimaryAction>()
        setShell(
            width = 320.dp,
            height = 240.dp,
            fontScale = 2f,
            guardHealth = offState,
            onGuardPrimaryAction = actions::add,
        )

        val action = compose.onNodeWithTag("guard_primary_action")
        action
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(49.dp)

        // The overlay shell can leave the target's lower half behind the navigation
        // surface after a minimal scroll, so the dispatch assertion activates the
        // semantics action directly (the reachability contract is the part above);
        // this is exactly how a screen reader would activate the row.
        action.performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(listOf(GuardPrimaryAction.TurnOn), actions)
        }
    }

    @Test
    fun `Settings lower Help control remains reachable at short height and enlarged font`() {
        setShell(
            initialNavigation = ProductNavigation.settings,
            height = 240.dp,
            fontScale = 2f,
            onOpenHelp = {},
        )

        compose.onNodeWithText("Help")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `Filters lower unavailable panel remains reachable at short height and enlarged font`() {
        setShell(
            initialNavigation = ProductNavigation.guard.open(ProductDestination.Filters),
            height = 240.dp,
            fontScale = 2f,
        )

        compose.onNodeWithTag("filters_unavailable_panel")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `Activity lower unavailable panel remains reachable at short height and enlarged font`() {
        setShell(
            initialNavigation = ProductNavigation.guard.open(ProductDestination.Activity),
            height = 240.dp,
            fontScale = 2f,
        )

        compose.onNodeWithTag("activity_unavailable_panel")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `Top Domains page Back pops one level to Activity`() {
        val activity = ProductNavigation.guard.open(ProductDestination.Activity)
        val changes = mutableListOf<ProductNavigation>()
        setShell(
            initialNavigation = activity.open(ProductDestination.TopDomains),
            onNavigationChanged = changes::add,
        )
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf(activity), changes) }
    }

    @Test
    fun `Domain History page Back pops one level to Activity`() {
        val activity = ProductNavigation.guard.open(ProductDestination.Activity)
        val changes = mutableListOf<ProductNavigation>()
        setShell(
            initialNavigation = activity.open(ProductDestination.DomainHistory),
            onNavigationChanged = changes::add,
        )
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf(activity), changes) }
    }

    @Test
    fun `system Back from Top Domains pops one level to Activity exactly once`() {
        val activity = ProductNavigation.guard.open(ProductDestination.Activity)
        val changes = mutableListOf<ProductNavigation>()
        var owner: OnBackPressedDispatcherOwner? = null
        setShell(
            initialNavigation = activity.open(ProductDestination.TopDomains),
            onNavigationChanged = changes::add,
            captureBackPressedOwner = { owner = it },
        )
        compose.runOnIdle {
            checkNotNull(owner).onBackPressedDispatcher.onBackPressed()
        }
        compose.runOnIdle { assertEquals(listOf(activity), changes) }
    }

    @Test
    fun `system Back from Domain History pops one level to Activity exactly once`() {
        val activity = ProductNavigation.guard.open(ProductDestination.Activity)
        val changes = mutableListOf<ProductNavigation>()
        var owner: OnBackPressedDispatcherOwner? = null
        setShell(
            initialNavigation = activity.open(ProductDestination.DomainHistory),
            onNavigationChanged = changes::add,
            captureBackPressedOwner = { owner = it },
        )
        compose.runOnIdle {
            checkNotNull(owner).onBackPressedDispatcher.onBackPressed()
        }
        compose.runOnIdle { assertEquals(listOf(activity), changes) }
    }

    @Test
    fun `shell renders the exact Top Domains grandchild instead of the overview`() {
        setShell(
            initialNavigation = ProductNavigation.guard
                .open(ProductDestination.Activity)
                .open(ProductDestination.TopDomains),
            activityState = readyActivityState,
        )

        compose.onNodeWithText("Top Domains").assert(headingMatcher)
        compose.onNodeWithText("zeta.example").assertExists()
        compose.onNodeWithText("Activity").assertDoesNotExist()
    }

    @Test
    fun `shell renders the exact Domain History grandchild instead of the overview`() {
        setShell(
            initialNavigation = ProductNavigation.guard
                .open(ProductDestination.Activity)
                .open(ProductDestination.DomainHistory),
            activityState = readyActivityState,
        )

        compose.onNodeWithText("Domain History").assert(headingMatcher)
        compose.onNodeWithText("history.example").assertExists()
        compose.onNodeWithText("Activity").assertDoesNotExist()
    }

    @Test
    fun `shell owns one review surface across Activity destinations`() {
        setShell(
            initialNavigation = ProductNavigation.guard
                .open(ProductDestination.Activity)
                .open(ProductDestination.TopDomains),
            activityState = readyActivityState.copy(
                domainAction = DomainActionUiState.ReviewPending(review),
            ),
            domainReviewCallbacks = noOpReviewCallbacks,
        )

        compose.onAllNodesWithTag("activity_domain_review_dialog").assertCountEquals(1)
    }

    @Test
    fun `shell does not imply review capability when review callbacks are absent`() {
        setShell(
            activityState = readyActivityState.copy(
                domainAction = DomainActionUiState.ReviewPending(review),
            ),
        )

        compose.onNodeWithTag("activity_domain_review_dialog").assertDoesNotExist()
    }

    @Test
    fun `Legal Notices is a Settings child that lists the notice sections and opens the privacy link`() {
        val opened = mutableListOf<String>()
        setShell(
            onOpenHelp = {},
            legalNoticeSections = listOf(
                LegalNoticeSectionUi(
                    title = "DNS Resolvers",
                    notices = listOf(
                        LegalNoticeUi(
                            displayName = "Mullvad",
                            noticeText = "Mullvad is a trademark of Mullvad VPN AB.",
                            plannedUse = "Plain-text identification of a selectable DNS resolver.",
                            sourceUrl = null,
                            licenseName = null,
                            licenseUrl = null,
                        ),
                    ),
                ),
            ),
            onOpenUrl = opened::add,
        )

        compose.onNodeWithTag("product_root_settings").performClick()
        compose.onNodeWithText("Legal Notices").performScrollTo().performClick()

        compose.onNodeWithText("DNS Resolvers").assertExists()
        compose.onNodeWithText("Mullvad").assertExists()
        compose.onNodeWithText("Privacy Policy").performScrollTo().performClick()
        compose.onNodeWithTag("legal_protection_terms_link").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://lavasecurity.app/privacy/", "https://lavasecurity.app/terms/"), opened)
        }
    }

    @Test
    fun `Legal Notices is not advertised when the app does not supply notice data`() {
        setShell(onOpenHelp = {})

        compose.onNodeWithTag("product_root_settings").performClick()

        compose.onNodeWithText("Help").assertExists()
        compose.onNodeWithText("Legal Notices").assertDoesNotExist()
    }

    @Test
    fun `Nerd Stats is a Settings child that renders the app facts and no invented tunnel rows`() {
        setShell(
            nerdStatsState = NerdStatsUiState(
                versionName = "1.2.3",
                buildNumber = "42",
                androidPlatformLabel = "Android 15",
                tunnelHealth = null,
            ),
            sampledTimeLabel = { "sampled" },
        )

        compose.onNodeWithTag("product_root_settings").performClick()
        compose.onNodeWithText("Nerd Stats").performScrollTo().performClick()

        compose.onNodeWithText("1.2.3").assertExists()
        compose.onNodeWithText("Android 15").assertExists()
        compose.onNodeWithText("Not sampled yet").assertExists()
    }

    @Test
    fun `Nerd Stats is not advertised without an app-supplied state`() {
        setShell()

        compose.onNodeWithTag("product_root_settings").performClick()

        compose.onNodeWithText("Nerd Stats").assertDoesNotExist()
    }

    @Test
    fun `Network Activity is a Settings child that renders the privacy panel and rows`() {
        setShell(
            activityState = ActivityUiState.unavailable.copy(
                networkActivity = ActivityContentState.Ready(
                    com.lavasec.app.presentation.activity.NetworkActivitySnapshot(
                        rows = listOf(
                            com.lavasec.app.presentation.activity.NetworkActivityRowUi(
                                id = 1L,
                                timestampMillis = 1_753_600_000_000L,
                                event = com.lavasec.app.foundation.activity.NetworkActivityEvent.ProtectionConnected,
                                capturedState = com.lavasec.app.foundation.activity.NetworkActivityCapturedState(
                                    protection = com.lavasec.app.foundation.activity.NetworkActivityProtectionState.PROTECTED,
                                    connectivity = com.lavasec.app.foundation.activity.NetworkActivityConnectivityState.HEALTHY,
                                    networkKind = com.lavasec.app.foundation.activity.NetworkActivityNetworkKind.WIFI,
                                    networkPathSatisfied = true,
                                    resolver = com.lavasec.app.foundation.activity.NetworkActivityResolverIdentity.DEVICE_DNS,
                                    resolverTransport = com.lavasec.app.foundation.activity.NetworkActivityResolverTransport.DEVICE_UDP,
                                    fallbackCoverage = com.lavasec.app.foundation.health.ResolverFallbackCoverage.NONE,
                                    activeFallback = com.lavasec.app.foundation.health.ProtectionFallback.NONE,
                                ),
                                occurrenceCount = 1,
                            ),
                        ),
                        nextPageKey = null,
                    ),
                ),
            ),
            privacyDataState = PrivacyDataUiState(
                logging = LoggingSettings(),
                inFlight = false,
                exportReady = false,
                error = null,
            ),
            privacyDataCallbacks = privacyDataCallbacks(),
            onResolverSelected = {},
            onClearNetworkActivity = {},
        )

        compose.onNodeWithTag("product_root_settings").performClick()
        compose.onNodeWithText("Network Activity").performScrollTo()
        // See the Guard primary-action test: after a minimal scroll the row can sit
        // behind the navigation surface, so the navigation is dispatched through the
        // semantics action (screen-reader activation) instead of a coordinate tap.
        compose.onNodeWithText("Network Activity")
            .performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithText("Stays on this device").assertExists()
        compose.onNodeWithText("Connected").assertExists()
        compose.onNodeWithText("Protection").assertExists()
    }

    @Test
    fun `Network Activity is not advertised without the privacy surface`() {
        setShell()

        compose.onNodeWithTag("product_root_settings").performClick()

        compose.onNodeWithText("Network Activity").assertDoesNotExist()
    }

    @Test
    fun `an expired unlock session gates the protected Activity surface and unlocks`() {
        val unlocks = mutableListOf<String>()
        setShell(
            initialNavigation = ProductNavigation.guard.open(ProductDestination.Activity),
            activityState = readyActivityState,
            securityState = com.lavasec.app.presentation.settings.SecurityUiState.Enabled(
                lastUnlockEpochMillis = 1_000L,
                lastChangeEpochMillis = null,
                lockTimeout = com.lavasec.app.foundation.security.LockTimeoutOption.IMMEDIATELY,
                protectedSurfaces = setOf(
                    com.lavasec.app.foundation.security.ProtectedSurface.ACTIVITY,
                    com.lavasec.app.foundation.security.ProtectedSurface.FILTERS,
                ),
                inFlight = false,
                failure = null,
            ),
            securityCallbacks = com.lavasec.app.presentation.settings.SecurityCallbacks(
                onSetPasscode = {},
                onChangePasscode = { _, _ -> },
                onRemovePasscode = {},
                onUnlock = { unlocks += it },
                onLockTimeoutSelected = {},
                onProtectedSurfaceChanged = { _, _ -> },
                onResetAfterRecoveryFailure = {},
            ),
            securityNowMillis = 2_000L,
        )

        compose.onNodeWithTag("activity_screen").assertDoesNotExist()
        // The Activity route re-requires the passcode after the expired session.
        compose.onNodeWithTag("app_unlock_overlay").assertExists()
        compose.onNodeWithText("Lava Locked").assertExists()
        compose.onNodeWithTag("app_unlock_passcode_field").performTextInput("1234")
        compose.onNodeWithTag("app_unlock_submit").performClick()
        compose.runOnIdle { assertEquals(listOf("1234"), unlocks) }
    }

    @Test
    fun `a current turn grant reveals its protected surface`() {
        setShell(
            initialNavigation = ProductNavigation.guard.open(ProductDestination.Activity),
            activityState = readyActivityState,
            securityState = com.lavasec.app.presentation.settings.SecurityUiState.Enabled(
                lastUnlockEpochMillis = 1_000L,
                lastChangeEpochMillis = null,
                lockTimeout = com.lavasec.app.foundation.security.LockTimeoutOption.ONE_MINUTE,
                protectedSurfaces = setOf(
                    com.lavasec.app.foundation.security.ProtectedSurface.ACTIVITY,
                ),
                authenticatedSurfaces = setOf(com.lavasec.app.foundation.security.ProtectedSurface.ACTIVITY),
                inFlight = false,
                failure = null,
            ),
            securityCallbacks = com.lavasec.app.presentation.settings.SecurityCallbacks(
                onSetPasscode = {},
                onChangePasscode = { _, _ -> },
                onRemovePasscode = {},
                onUnlock = {},
                onLockTimeoutSelected = {},
                onProtectedSurfaceChanged = { _, _ -> },
                onResetAfterRecoveryFailure = {},
            ),
            securityNowMillis = 2_000L,
        )

        compose.onNodeWithTag("app_unlock_overlay").assertDoesNotExist()
    }

    @Test
    fun `unknown security never composes native Activity evidence`() {
        setShell(initialNavigation = ProductNavigation.guard.open(ProductDestination.Activity),
            activityState = readyActivityState, securityState = null)
        compose.onNodeWithTag("security_unavailable_panel").assertExists()
        compose.onNodeWithTag("activity_screen").assertDoesNotExist()
    }

    @Test
    fun `unavailable security never composes native Filters controls`() {
        setShell(initialNavigation = ProductNavigation.guard.open(ProductDestination.Filters),
            securityState = com.lavasec.app.presentation.settings.SecurityUiState.Unavailable)
        compose.onNodeWithTag("security_unavailable_panel").assertExists()
        compose.onNodeWithTag("filters_screen").assertDoesNotExist()
    }

    @Test
    fun `unreadable credentials show recovery without underlying evidence`() {
        setShell(initialNavigation = ProductNavigation.guard.open(ProductDestination.Activity),
            activityState = readyActivityState,
            securityState = com.lavasec.app.presentation.settings.SecurityUiState.RecoveryFailure)
        compose.onNodeWithTag("security_recovery_panel").assertExists()
        compose.onNodeWithTag("activity_screen").assertDoesNotExist()
    }

    private fun setShell(
        width: Dp = 599.dp,
        height: Dp = 800.dp,
        fontScale: Float = 1f,
        reducedMotion: Boolean = false,
        initialNavigation: ProductNavigation = ProductNavigation.guard,
        guardHealth: GuardProtectionHealthUi = offState,
        onGuardPrimaryAction: ((GuardPrimaryAction) -> Unit)? = null,
        onOpenHelp: (() -> Unit)? = null,
        onNavigationChanged: (ProductNavigation) -> Unit = {},
        captureBackPressedOwner: (OnBackPressedDispatcherOwner) -> Unit = {},
        activityState: ActivityUiState = ActivityUiState.unavailable,
        domainReviewCallbacks: DomainReviewCallbacks? = null,
        legalNoticeSections: List<LegalNoticeSectionUi> = emptyList(),
        onOpenUrl: ((String) -> Unit)? = null,
        nerdStatsState: NerdStatsUiState? = null,
        sampledTimeLabel: ((Long) -> String)? = null,
        privacyDataState:
            com.lavasec.app.presentation.settings.PrivacyDataUiState? = null,
        privacyDataCallbacks:
            com.lavasec.app.presentation.settings.PrivacyDataCallbacks? = null,
        customizationState:
            com.lavasec.app.presentation.settings.CustomizationUiState? = null,
        customizationCallbacks:
            com.lavasec.app.presentation.settings.CustomizationCallbacks? = null,
        onResolverSelected:
            ((com.lavasec.app.foundation.resolver.MilestoneResolverPlan) -> Unit)? = null,
        onClearNetworkActivity: (() -> Unit)? = null,
        securityState:
            com.lavasec.app.presentation.settings.SecurityUiState? =
                com.lavasec.app.presentation.settings.SecurityUiState.Disabled(com.lavasec.app.foundation.security.LockTimeoutOption.DEFAULT),
        securityCallbacks:
            com.lavasec.app.presentation.settings.SecurityCallbacks? = null,
        securityNowMillis: Long = 0L,
        filterLibraryState: FilterLibraryState? = null,
        filterOverviewNavigation:
            com.lavasec.app.presentation.filters.FilterOverviewNavigation? = null,
        filterProfileSwitchState:
            com.lavasec.app.presentation.filters.FilterProfileSwitchUiState =
                com.lavasec.app.presentation.filters.FilterProfileSwitchUiState.Idle,
        filterLibraryActions:
            com.lavasec.app.presentation.filters.FilterLibraryActions? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            val backPressedOwner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            SideEffect { captureBackPressedOwner(backPressedOwner) }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                LavaTheme(reducedMotion = reducedMotion) {
                    var navigation by remember { mutableStateOf(initialNavigation) }
                    ProductShell(
                        navigation = navigation,
                        guardHealth = guardHealth,
                        versionName = "1.2.3",
                        buildNumber = "42",
                        onNavigationChange = {
                            navigation = it
                            onNavigationChanged(it)
                        },
                        onGuardPrimaryAction = onGuardPrimaryAction,
                        onOpenHelp = onOpenHelp,
                        activityState = activityState,
                        domainReviewCallbacks = domainReviewCallbacks,
                        legalNoticeSections = legalNoticeSections,
                        legalPrivacyUrl = "https://lavasecurity.app/privacy/",
                        onOpenUrl = onOpenUrl,
                        nerdStatsState = nerdStatsState,
                        sampledTimeLabel = sampledTimeLabel,
                        privacyDataState = privacyDataState,
                        privacyDataCallbacks = privacyDataCallbacks,
                        customizationState = customizationState,
                        customizationCallbacks = customizationCallbacks,
                        onResolverSelected = onResolverSelected,
                        onClearNetworkActivity = onClearNetworkActivity,
                        securityState = securityState,
                        securityCallbacks = securityCallbacks,
                        securityNowMillis = securityNowMillis,
                        filterLibraryState = filterLibraryState,
                        filterOverviewNavigation = filterOverviewNavigation,
                        filterProfileSwitchState = filterProfileSwitchState,
                        filterLibraryActions = filterLibraryActions,
                        modifier = Modifier
                            .testTag("product_shell")
                            .then(Modifier)
                            .requiredShellSize(width, height),
                    )
                }
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

        val rootDestinationMatcher =
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "product_root_guard") or
                SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "product_root_settings")

        val headingMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

        val offState = GuardProtectionHealthUi(
            lifecycle = com.lavasec.app.presentation.GuardLifecycleUi.INACTIVE,
            connectivity = com.lavasec.app.presentation.GuardConnectivityUi.UNAVAILABLE,
            primaryAction = GuardPrimaryAction.TurnOn,
        )

        val today: LocalDate = LocalDate.of(2026, 8, 1)
        val topSnapshot = TopDomainsSnapshot(
            dateRange = ActivityDateRangeUi(today, today, today, "Asia/Tokyo"),
            decision = ActivityDecisionUi.Blocked,
            search = "",
            availability = ActivityLogAvailabilityUi.Retained,
            rows = listOf(TopDomainRowUi("zeta.example", 7L)),
        )
        val review = DomainActionReviewUi(
            id = 71L,
            domain = "example.com",
            action = DomainActionUi.Block,
            changes = listOf(
                DomainActionChangeUi(
                    group = DomainActionChangeGroupUi.BlockedDomains,
                    kind = DomainActionChangeKindUi.Added,
                    domain = "example.com",
                ),
            ),
        )
        val readyActivityState = ActivityUiState.unavailable.copy(
            topDomains = ActivityContentState.Ready(topSnapshot),
            domainHistory = ActivityContentState.Ready(
                DomainHistorySnapshot(
                    decision = ActivityDecisionUi.Blocked,
                    search = "",
                    rows = listOf(
                        DomainHistoryRowUi(
                            id = 1L,
                            timestampMillis = 1_775_200_000_000L,
                            domain = "history.example",
                            decision = ActivityDecisionUi.Blocked,
                            reason = DomainHistoryReasonUi.Blocklist,
                        ),
                    ),
                    nextPageKey = null,
                ),
            ),
            retainedDomainLogs = ActivityContentState.Ready(true),
        )
        val noOpReviewCallbacks = DomainReviewCallbacks(
            onReviewVisible = {},
            onCancelBeforeVisible = {},
            onConfirm = {},
            onCancel = {},
        )

        val sampleFilterLibrary = FilterLibraryState(
            filters = listOf(
                FilterRowUi("core", "Core", false, false, false, null),
                FilterRowUi("balanced", "Balanced", true, false, false, 128),
                FilterRowUi("extra", "Extra", false, false, false, null),
            ),
            activeFilterId = "balanced",
            maxFilters = 3,
        )
    }
}

private fun Modifier.requiredShellSize(width: Dp, height: Dp): Modifier =
    requiredWidth(width).requiredHeight(height)
