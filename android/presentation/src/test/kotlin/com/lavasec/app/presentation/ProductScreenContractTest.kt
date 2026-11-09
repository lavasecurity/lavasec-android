package com.lavasec.app.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.presentation.activity.ActivityUiState
import com.lavasec.app.presentation.filters.FilterDetailState
import com.lavasec.app.presentation.filters.FilterDraftDiffUi
import com.lavasec.app.presentation.filters.FilterLibraryActions
import com.lavasec.app.presentation.filters.FilterLibraryState
import com.lavasec.app.presentation.filters.FilterPreparationUiState
import com.lavasec.app.presentation.filters.FilterReviewState
import com.lavasec.app.presentation.filters.FilterRowUi
import com.lavasec.app.presentation.onboarding.OnboardingCallbacks
import com.lavasec.app.presentation.onboarding.OnboardingScreen
import com.lavasec.app.presentation.onboarding.OnboardingUiState
import com.lavasec.app.presentation.settings.BugReportCallbacks
import com.lavasec.app.presentation.settings.BugReportUiState
import com.lavasec.app.presentation.settings.CustomizationCallbacks
import com.lavasec.app.presentation.settings.CustomizationUiState
import com.lavasec.app.presentation.settings.NerdStatsUiState
import com.lavasec.app.presentation.settings.PrivacyDataCallbacks
import com.lavasec.app.presentation.settings.PrivacyDataUiState
import com.lavasec.app.presentation.settings.SecurityCallbacks
import com.lavasec.app.presentation.settings.SecurityUiState
import com.lavasec.app.presentation.settings.SettingsResolverUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice-07 screen contract, the semantics half of the parity gates: every root and
 * detail screen renders under a stable `testTag` and exposes its page title as exactly
 * one accessibility heading, every navigation row merges its title and summary into one
 * semantic node (so TalkBack reads the row once), and the onboarding surface carries the
 * same tag/heading contract. The suite runs on API 28 and API 36 through the [Config]
 * sdk list, mirroring the geometry suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class ProductScreenContractTest {
    @get:Rule
    val compose = createComposeRule()

    /** One screen row: the shell navigation path, the root tag, the title heading copy, and
     * the expected heading count (the library screen repeats "Your filters" as a section
     * label below the screen title, so it legitimately carries two heading nodes). */
    private data class ScreenRow(
        val navigation: ProductNavigation,
        val screenTag: String,
        val headingText: String,
        val headingCount: Int = 1,
    )

    private val shellScreens = listOf(
        ScreenRow(ProductNavigation.guard, "guard_screen", "Guard"),
        ScreenRow(
            ProductNavigation.guard.open(ProductDestination.Filters),
            "filters_screen",
            "Filters",
        ),
        ScreenRow(
            ProductNavigation.guard.open(ProductDestination.Filters)
                .open(ProductDestination.FilterLibrary),
            "filter_library_screen",
            "Your filters",
            headingCount = 2,
        ),
        ScreenRow(
            ProductNavigation.guard.open(ProductDestination.Filters)
                .open(ProductDestination.FilterDetail),
            "filter_detail_screen",
            "Balanced",
        ),
        ScreenRow(
            ProductNavigation.guard.open(ProductDestination.Filters)
                .open(ProductDestination.FilterDetail)
                .open(ProductDestination.FilterReview),
            "filter_review_screen",
            "Review",
        ),
        ScreenRow(
            ProductNavigation.guard.open(ProductDestination.Filters)
                .open(ProductDestination.FilterDetail)
                .open(ProductDestination.FilterReview)
                .open(ProductDestination.FilterPreparation),
            "filter_preparation_screen",
            "Building the filter",
        ),
        ScreenRow(
            ProductNavigation.guard.open(ProductDestination.Activity),
            "activity_screen",
            "Activity",
        ),
        ScreenRow(
            ProductNavigation.guard.open(ProductDestination.Activity)
                .open(ProductDestination.TopDomains),
            "top_domains_screen",
            "Top Domains",
        ),
        ScreenRow(
            ProductNavigation.guard.open(ProductDestination.Activity)
                .open(ProductDestination.DomainHistory),
            "domain_history_screen",
            "Domain History",
        ),
        ScreenRow(ProductNavigation.settings, "settings_screen", "Settings"),
        ScreenRow(
            ProductNavigation.settings.open(ProductDestination.Account),
            "account_settings_screen",
            "Account & Backup",
        ),
        ScreenRow(
            ProductNavigation.settings.open(ProductDestination.Upgrade),
            "upgrade_settings_screen",
            "Upgrade",
        ),
        ScreenRow(
            ProductNavigation.settings.open(ProductDestination.Customization),
            "customization_settings_screen",
            "Customization",
        ),
        ScreenRow(
            ProductNavigation.settings.open(ProductDestination.PrivacyData),
            "privacy_data_screen",
            "Privacy & Data",
        ),
        ScreenRow(
            ProductNavigation.settings.open(ProductDestination.Security),
            "security_settings_screen",
            "Security",
        ),
        ScreenRow(
            ProductNavigation.settings.open(ProductDestination.BugReport),
            "bug_report_screen",
            "Bug report",
        ),
        ScreenRow(
            ProductNavigation.settings.open(ProductDestination.LegalNotices),
            "legal_notices_screen",
            "Legal Notices",
        ),
        ScreenRow(
            ProductNavigation.settings.open(ProductDestination.NerdStats),
            "nerd_stats_screen",
            "Nerd Stats",
        ),
        ScreenRow(
            ProductNavigation.settings.open(ProductDestination.DnsResolver),
            "dns_resolver_screen",
            "DNS Resolver",
        ),
        ScreenRow(
            ProductNavigation.settings.open(ProductDestination.NetworkActivity),
            "network_activity_screen",
            "Network Activity",
        ),
    )

    @Test
    fun `every root and detail screen exposes a stable tag and exactly one title heading`() {
        var setNavigation: ((ProductNavigation) -> Unit)? = null
        setShell(
            initialNavigation = shellScreens.first().navigation,
            captureNavigate = { setNavigation = it },
        )

        for (row in shellScreens) {
            // Set the destination first, then assert; the shell renders by the last path
            // element, so the tag and heading assertions see exactly that screen.
            compose.runOnIdle { checkNotNull(setNavigation)(row.navigation) }
            compose.runOnIdle {
                compose.onNodeWithTag(row.screenTag).assertExists()
            }
            compose.onAllNodesWithText(row.headingText)
                .filter(headingMatcher)
                .assertCountEquals(row.headingCount)
        }
    }

    @Test
    fun `onboarding surface carries the stable tag and a title heading`() {
        compose.setContent {
            LavaTheme {
                OnboardingScreen(
                    state = OnboardingUiState(
                        onboarding = com.lavasec.app.foundation.onboarding.OnboardingState.initial(),
                    ),
                    callbacks = OnboardingCallbacks(onForward = {}),
                )
            }
        }

        compose.onNodeWithTag("onboarding_screen").assertExists()
        compose.onAllNodesWithText("Meet Lava").filter(headingMatcher).assertCountEquals(1)
    }

    @Test
    fun `navigation rows merge title and summary into one semantic node`() {
        setShell(initialNavigation = ProductNavigation.settings)

        compose.onNodeWithTag("settings_dns_resolver_card").assertExists()
        compose.runOnIdle {
            val row = compose.onNodeWithTag("settings_dns_resolver_card").fetchSemanticsNode()
            // The summary is composed from the real resolver plan, so the merged node
            // carries the plan-derived provider/transport line beside the title.
            assertEquals(
                listOf(
                    "DNS Resolver",
                    "Device DNS with encrypted fallback",
                ),
                row.config[SemanticsProperties.Text].map { it.text },
            )
        }
    }

    @Test
    fun `Guard journey rows merge title and summary into one semantic node`() {
        setShell()

        compose.runOnIdle {
            val row = compose.onNodeWithText("How Lava filters").fetchSemanticsNode()
            assertEquals(
                listOf(
                    "How Lava filters",
                    "See how local filtering works",
                ),
                row.config[SemanticsProperties.Text].map { it.text },
            )
        }
    }

    private fun setShell(
        width: Dp = 599.dp,
        height: Dp = 800.dp,
        initialNavigation: ProductNavigation = ProductNavigation.guard,
        captureNavigate: (((ProductNavigation) -> Unit) -> Unit)? = null,
    ) {
        compose.setContent {
            LavaTheme {
                var navigation by remember { mutableStateOf(initialNavigation) }
                androidx.compose.runtime.SideEffect {
                    captureNavigate?.invoke { updated -> navigation = updated }
                }
                ProductShell(
                    navigation = navigation,
                    guardHealth = GuardProtectionHealthUi(
                        lifecycle = GuardLifecycleUi.INACTIVE,
                        connectivity = GuardConnectivityUi.UNAVAILABLE,
                        primaryAction = GuardPrimaryAction.TurnOn,
                    ),
                    versionName = "1.2.3",
                    buildNumber = "42",
                    onNavigationChange = { navigation = it },
                    onGuardPrimaryAction = {},
                    onOpenHelp = {},
                    activityState = ActivityUiState.unavailable,
                    accountState = com.lavasec.app.presentation.settings.AccountUiState.Unavailable,
                    customizationState = CustomizationUiState(),
                    customizationCallbacks = customizationCallbacks(),
                    privacyDataState = PrivacyDataUiState(
                        logging = LoggingSettings(),
                        inFlight = false,
                        exportReady = false,
                        error = null,
                    ),
                    privacyDataCallbacks = privacyDataCallbacks(),
                    securityState = SecurityUiState.Disabled(com.lavasec.app.foundation.security.LockTimeoutOption.DEFAULT),
                    securityCallbacks = securityCallbacks(),
                    bugReportState = BugReportUiState(),
                    bugReportCallbacks = bugReportCallbacks(),
                    settingsResolverState = SettingsResolverUiState(
                        plan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
                        inFlight = false,
                        error = null,
                    ),
                    onResolverSelected = {},
                    onClearNetworkActivity = {},
                    legalNoticeSections = listOf(
                        com.lavasec.app.presentation.settings.LegalNoticeSectionUi(
                            title = "DNS Resolvers",
                            notices = emptyList(),
                        ),
                    ),
                    nerdStatsState = NerdStatsUiState(
                        versionName = "1.2.3",
                        buildNumber = "42",
                        androidPlatformLabel = "Android 15",
                        tunnelHealth = null,
                    ),
                    sampledTimeLabel = { "sampled" },
                    filterLibraryState = sampleFilterLibrary,
                    filterOverviewNavigation =
                        com.lavasec.app.presentation.filters.FilterOverviewNavigation(
                            onOpenActiveDetail = {},
                            onOpenLibrary = {},
                        ),
                    filterDetailState = sampleFilterDetail,
                    filterReviewState = sampleFilterReview,
                    filterPreparationState = FilterPreparationUiState.Preparing("Balanced"),
                    filterLibraryActions = FilterLibraryActions(),
                    modifier = Modifier
                        .testTag("product_shell")
                        .requiredShellSize(width, height),
                )
            }
        }
    }

    private companion object {
        val headingMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

        fun customizationCallbacks() = CustomizationCallbacks(
            onReduceMotionChanged = {},
            onHapticsChanged = {},
            onOpenNotificationSettings = {},
        )

        fun privacyDataCallbacks() = PrivacyDataCallbacks(
            onLoggingChange = {},
            onClearLocalActivity = {},
            onExportLocalActivity = {},
            onShareLocalExport = {},
            onClearDiagnostics = {},
        )

        fun securityCallbacks() = SecurityCallbacks(
            onSetPasscode = {},
            onChangePasscode = { _, _ -> },
            onRemovePasscode = {},
            onUnlock = {},
            onLockTimeoutSelected = {},
            onProtectedSurfaceChanged = { _, _ -> },
            onResetAfterRecoveryFailure = {},
        )

        fun bugReportCallbacks() = BugReportCallbacks(
            onTopicSelected = {},
            onDescriptionChanged = {},
            onDiagnosticsConsentChanged = {},
            onAffectedSiteChanged = {},
            onContactEmailChanged = {},
            onConsentGrantedChanged = {},
            onAdvance = {},
            onBack = {},
            onSubmit = {},
            onDiscard = {},
            onDone = {},
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

        val sampleFilterDetail = FilterDetailState(
            filterId = "balanced",
            name = "Balanced",
            inEffect = true,
            frozen = false,
            blocksNothing = false,
            ruleCount = 128,
            sources = emptyList(),
            blockedDomains = emptyList(),
            allowedDomains = emptyList(),
            maxAllowedDomains = 5,
            maxBlockedDomains = 5,
            editing = false,
            draftHasChanges = false,
            busy = false,
        )

        val sampleFilterReview = FilterReviewState(
            filterId = "balanced",
            filterName = "Balanced",
            diff = FilterDraftDiffUi(
                addedSources = emptyList(),
                removedSources = emptyList(),
                addedBlockedDomains = listOf("ads.example"),
                removedBlockedDomains = emptyList(),
                addedAllowedDomains = emptyList(),
                removedAllowedDomains = emptyList(),
            ),
            rejection = null,
            busy = false,
        )
    }
}

private fun Modifier.requiredShellSize(width: Dp, height: Dp): Modifier =
    requiredWidth(width).requiredHeight(height)
