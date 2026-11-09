package com.lavasec.app.presentation.settings

import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.security.LockTimeoutOption
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `settings groups follow the iOS order with the version footer`() {
        setSettings(rows = fullRows())

        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        // "Support" is also a nav-card title elsewhere; the group labels below are the
        // headings. Your Lava / Protection choices / Support / Advanced appear in the
        // pinned iOS order, top to bottom, and the About card is gone.
        for (label in listOf("Your Lava", "Protection choices", "Support", "Advanced")) {
            compose.onAllNodesWithText(label).filter(heading).assertCountEquals(1)
        }
        compose.runOnIdle {
            val yourLava = compose.onAllNodesWithText("Your Lava").filter(heading)[0]
                .getBoundsInRoot().top
            val protection = compose.onAllNodesWithText("Protection choices").filter(heading)[0]
                .getBoundsInRoot().top
            val support = compose.onAllNodesWithText("Support").filter(heading)[0]
                .getBoundsInRoot().top
            val advanced = compose.onAllNodesWithText("Advanced").filter(heading)[0]
                .getBoundsInRoot().top
            assertTrue(yourLava < protection)
            assertTrue(protection < support)
            assertTrue(support < advanced)
        }
        compose.onNodeWithText("About").assertDoesNotExist()
        compose.onNodeWithTag("settings_version_footer").assertExists()
        compose.onNodeWithText("Version 1.0 (1)").assertExists()
    }

    @Test
    fun `every root row renders one card and the badge is not separately exposed`() {
        setSettings(rows = fullRows())

        // Merged-tree queries see exactly one node per row label (the button), and the
        // decorative leading badge never appears as its own focusable element.
        for (label in listOf(
            "Account & Backup", "Upgrade", "Customization", "Privacy & Data",
            "Security", "DNS Resolver", "Bug report", "Help", "Legal Notices",
            "Nerd Stats", "Network Activity",
        )) {
            compose.onAllNodesWithText(label).assertCountEquals(1)
        }
        compose.onNodeWithTag("lava_navigation_leading_badge").assertDoesNotExist()
    }

    @Test
    fun `each trust row navigates to its exact destination`() {
        val opened = mutableListOf<SettingsRowState>()
        setSettings(rows = fullRows(), onRowClick = opened::add)

        compose.onNodeWithTag("settings_account_card").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, opened.size)
            assertEquals(ProductDestination.Account, opened.single().destination)
        }
    }

    @Test
    fun `trust row summaries come from the exact typed states`() {
        val rows = settingsRowStates(
            account = AccountUiState.Unavailable,
            entitlement = EntitlementUiState.Unavailable,
            customization = CustomizationUiState(),
            privacyLogging = LoggingSettings(),
            security = SecurityUiState.Disabled(LockTimeoutOption.IMMEDIATELY),
            resolver = SettingsResolverUiState(
                plan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
                inFlight = false,
                error = null,
            ),
            bugReportAvailable = true,
            legalNoticesAvailable = true,
            nerdStatsAvailable = true,
            networkActivityAvailable = true,
        )
        assertEquals(R.string.settings_account_backup_summary, summaryOf(rows, "Account"))
        assertEquals(R.string.settings_upgrade_summary, summaryOf(rows, "Upgrade"))
        assertEquals(R.string.settings_customization_summary, summaryOf(rows, "Customization"))
        assertEquals(R.string.settings_privacy_data_summary_all_on, summaryOf(rows, "PrivacyData"))
        assertEquals(R.string.settings_security_summary_off, summaryOf(rows, "Security"))
    }

    @Test
    fun `privacy summary is composed from the owners real logging state`() {
        assertEquals(
            R.string.settings_privacy_data_summary_all_on,
            summaryOf(rows(privacyLogging = LoggingSettings()), "PrivacyData"),
        )
        assertEquals(
            R.string.settings_privacy_data_summary_all_off,
            summaryOf(
                rows(
                    privacyLogging = LoggingSettings(
                        keepCounts = false,
                        keepHistory = false,
                        keepNetworkActivity = false,
                    ),
                ),
                "PrivacyData",
            ),
        )
        assertEquals(
            R.string.settings_privacy_data_summary_some,
            summaryOf(
                rows(
                    privacyLogging = LoggingSettings(
                        keepCounts = true,
                        keepHistory = false,
                        keepNetworkActivity = true,
                    ),
                ),
                "PrivacyData",
            ),
        )
    }

    @Test
    fun `security summary reflects the lock state`() {
        assertEquals(
            R.string.settings_security_summary_on,
            summaryOf(
                rows(
                    security = SecurityUiState.Enabled(
                        lastUnlockEpochMillis = 1L,
                        lastChangeEpochMillis = null,
                        lockTimeout = LockTimeoutOption.IMMEDIATELY,
                        protectedSurfaces = setOf(ProtectedSurface.ACTIVITY),
                        inFlight = false,
                        failure = null,
                    ),
                ),
                "Security",
            ),
        )
        assertEquals(
            R.string.settings_security_summary_unavailable,
            summaryOf(rows(security = SecurityUiState.Unavailable), "Security"),
        )
    }

    @Test
    fun `trust rows carry the honest unavailable summaries`() {
        setSettings(rows = fullRows())
        compose.onNodeWithText("Account setup pending").assertExists()
        compose.onNodeWithText("Plus isn't on Android yet").assertExists()
    }

    @Test
    fun `network activity card reflects disabled retention`() {
        setSettings(rows = rows(privacyLogging = LoggingSettings(keepNetworkActivity = false)))
        compose.onNodeWithText("Local network activity off").assertExists()
    }

    @Test
    fun `the resolver row summary is composed from the typed selection`() {
        assertEquals(
            R.string.onboarding_resolver_fallback,
            summaryOf(
                resolverRows(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK),
                "DnsResolver",
            ),
        )
        assertEquals(
            R.string.onboarding_resolver_device,
            summaryOf(resolverRows(MilestoneResolverPlan.DEVICE_DNS_ONLY), "DnsResolver"),
        )
        val mullvadRow = resolverRows(MilestoneResolverPlan.MULLVAD_ONLY)
            .first { it.destination.name == "DnsResolver" }
        assertEquals(R.string.settings_resolver_summary_provider_transport, mullvadRow.summaryRes)
        // "Mullvad (DoH)": the provider name and transport token come from the
        // catalog product data, not from localized plan copy.
        assertEquals(listOf("Mullvad", "DoH"), mullvadRow.summaryArgs)
    }

    private fun resolverRows(plan: MilestoneResolverPlan): List<SettingsRowState> =
        settingsRowStates(
            account = AccountUiState.Unavailable,
            entitlement = EntitlementUiState.Unavailable,
            customization = CustomizationUiState(),
            privacyLogging = LoggingSettings(),
            security = SecurityUiState.Disabled(LockTimeoutOption.IMMEDIATELY),
            resolver = SettingsResolverUiState(
                plan = plan,
                inFlight = false,
                error = null,
            ),
            bugReportAvailable = true,
            legalNoticesAvailable = true,
            nerdStatsAvailable = true,
            networkActivityAvailable = true,
        )

    private fun summaryOf(rows: List<SettingsRowState>, destination: String): Int =
        rows.first { it.destination.name == destination }.summaryRes

    private fun rows(
        privacyLogging: LoggingSettings = LoggingSettings(),
        security: SecurityUiState = SecurityUiState.Disabled(LockTimeoutOption.IMMEDIATELY),
    ): List<SettingsRowState> = settingsRowStates(
        account = AccountUiState.Unavailable,
        entitlement = EntitlementUiState.Unavailable,
        customization = CustomizationUiState(),
        privacyLogging = privacyLogging,
        security = security,
        resolver = null,
        bugReportAvailable = true,
        legalNoticesAvailable = true,
        nerdStatsAvailable = true,
        networkActivityAvailable = true,
    )

    private fun fullRows(): List<SettingsRowState> = settingsRowStates(
        account = AccountUiState.Unavailable,
        entitlement = EntitlementUiState.Unavailable,
        customization = CustomizationUiState(),
        privacyLogging = LoggingSettings(),
        security = SecurityUiState.Disabled(LockTimeoutOption.IMMEDIATELY),
        resolver = SettingsResolverUiState(
            plan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
            inFlight = false,
            error = null,
        ),
        bugReportAvailable = true,
        legalNoticesAvailable = true,
        nerdStatsAvailable = true,
        networkActivityAvailable = true,
    )

    private fun setSettings(
        rows: List<SettingsRowState>,
        onRowClick: (SettingsRowState) -> Unit = {},
        onOpenHelp: (() -> Unit)? = {},
        screenHeight: Dp = 1400.dp,
    ) {
        compose.setContent {
            LavaTheme {
                SettingsScreen(
                    versionName = "1.0",
                    buildNumber = "1",
                    rows = rows,
                    onRowClick = onRowClick,
                    onOpenHelp = onOpenHelp,
                    // A tall test viewport keeps every section composed and placed so
                    // the group-order bounds are real coordinates; the short-height
                    // reachability contract lives in the shell geometry tests.
                    modifier = Modifier
                        .requiredWidth(400.dp)
                        .requiredHeight(screenHeight),
                )
            }
        }
    }
}
