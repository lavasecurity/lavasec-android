package com.lavasec.app.presentation.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.presentation.resolver.ResolverHealthUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DnsResolverSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `device primary with fallback renders the exact grouped controls`() {
        setScreen(state = state(plan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK))

        compose.onNodeWithTag("settings_resolver_device_toggle").performScrollTo().assertIsOn()
        compose.onNodeWithTag("settings_resolver_fallback_toggle").performScrollTo().assertIsOn()
        compose.onNodeWithTag("settings_resolver_provider_mullvad").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("settings_resolver_recommended_badge").assertExists()
        compose.onNodeWithText(
            "If the primary DNS resolver stops responding, Lava sends allowed requests " +
                "to a backup resolver, then switches back automatically.",
        ).performScrollTo().assertExists()
    }

    @Test
    fun `toggling the fallback off requests the exact device only plan`() {
        val selected = mutableListOf<MilestoneResolverPlan>()
        setScreen(
            state = state(plan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK),
            onSelected = { selected += it },
        )

        compose.onNodeWithTag("settings_resolver_fallback_toggle").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf(MilestoneResolverPlan.DEVICE_DNS_ONLY), selected) }
    }

    @Test
    fun `toggling the fallback on requests the exact fallback plan`() {
        val selected = mutableListOf<MilestoneResolverPlan>()
        setScreen(
            state = state(plan = MilestoneResolverPlan.DEVICE_DNS_ONLY),
            onSelected = { selected += it },
        )

        compose.onNodeWithTag("settings_resolver_fallback_toggle").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(listOf(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK), selected)
        }
    }

    @Test
    fun `turning device DNS off adopts the exact Mullvad primary plan`() {
        val selected = mutableListOf<MilestoneResolverPlan>()
        setScreen(
            state = state(plan = MilestoneResolverPlan.DEVICE_DNS_ONLY),
            onSelected = { selected += it },
        )

        compose.onNodeWithTag("settings_resolver_device_toggle").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf(MilestoneResolverPlan.MULLVAD_ONLY), selected) }
    }

    @Test
    fun `turning device DNS on restores the safe default fallback plan`() {
        val selected = mutableListOf<MilestoneResolverPlan>()
        setScreen(
            state = state(plan = MilestoneResolverPlan.MULLVAD_ONLY),
            onSelected = { selected += it },
        )

        compose.onNodeWithTag("settings_resolver_device_toggle").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(listOf(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK), selected)
        }
    }

    @Test
    fun `the Mullvad primary renders the transport group with the DoH route selected`() {
        setScreen(state = state(plan = MilestoneResolverPlan.MULLVAD_ONLY))

        compose.onNodeWithTag("settings_resolver_transport_doh").performScrollTo().assertExists()
        compose.onNodeWithTag("settings_resolver_transport_doh_selected_mark", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(
            "IP uses standard DNS. DNS over HTTPS (DoH), TLS (DoT), and QUIC (DoQ) encrypt " +
                "allowed lookups to the resolver.",
        ).performScrollTo().assertExists()
    }

    @Test
    fun `the retained provider exposes actual IP DoH and DoT capability without an invented DoQ endpoint`() {
        setScreen(state = state(plan = MilestoneResolverPlan.MULLVAD_ONLY))
        compose.onNodeWithTag("settings_resolver_transport_dot").performScrollTo().assertExists()
        compose.onNodeWithTag("settings_resolver_transport_plain_dns").performScrollTo().assertExists()
        compose.onNodeWithText("DNS over TLS isn't available on Android yet.").assertDoesNotExist()
        compose.onNodeWithTag("settings_resolver_transport_doq").assertDoesNotExist()
    }

    @Test
    fun `the encrypted primary fallback to device DNS renders disabled with its reason`() {
        setScreen(state = state(plan = MilestoneResolverPlan.MULLVAD_ONLY))

        compose.onNodeWithTag("settings_resolver_fallback_toggle")
            .performScrollTo()
            .assertIsOff()
            .assertIsNotEnabled()
        compose.onNodeWithText(
            "Falling back to device DNS from an encrypted provider isn't available on Android yet.",
        ).performScrollTo().assertExists()
    }

    @Test
    fun `compatibility callbacks disable provider changes they cannot express and retain current legacy identity`() {
        val selected = mutableListOf<MilestoneResolverPlan>()
        setScreen(state = state(plan = MilestoneResolverPlan.MULLVAD_ONLY), onSelected = { selected += it })
        for (provider in listOf(ResolverCatalog.cloudflare, ResolverCatalog.quad9Unfiltered,
            ResolverCatalog.hagezi, ResolverCatalog.googlePublicDns)) {
            compose.onNodeWithTag("settings_resolver_provider_${provider.id.rawId}")
                .performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("settings_resolver_provider_mullvad").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("settings_resolver_provider_custom-dns").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save a custom resolver endpoint before selecting it.").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(emptyList<MilestoneResolverPlan>(), selected) }
    }

    @Test
    fun `typed provider and transport callbacks carry exact endpoints while preserving the other row`() {
        val selected = mutableListOf<ResolverSelection>()
        val initial = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK.toSelection()
        setScreen(state = state(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK),
            onDnsSelection = selected::add)
        compose.onNodeWithTag("settings_resolver_provider_cloudflare-1111").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(ResolverProviderId.DEVICE_DNS, selected.single().primary.providerId)
            assertEquals(ResolverSelectionEntry(ResolverProviderId.CLOUDFLARE, CatalogResolverTransport.DOH),
                selected.single().fallback)
            assertEquals(initial.tiers.first(), selected.single().tiers.first())
        }
        // This stateless screen keeps rendering the confirmed plan until the owner commits.
        compose.onNodeWithTag("settings_resolver_transport_dot").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ResolverSelectionEntry(ResolverProviderId.MULLVAD, CatalogResolverTransport.DOT), selected.last().fallback)
            assertEquals(initial.tiers.first(), selected.last().tiers.first())
        }
    }

    @Test
    fun `a value carrying custom DoQ selection renders with its retained endpoint and transport`() {
        val entry = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOQ,
            customDoqEndpoint = "doq://resolver.example:8853", customName = "Private resolver",
            customSecondaryEndpoint = "doq://secondary.example:853")
        val selection = ResolverSelection(listOf(ResolverTier(entry)))
        setScreen(state = state(null).copy(selection = selection), onDnsSelection = {})
        compose.onNodeWithTag("settings_resolver_unavailable_panel").assertDoesNotExist()
        compose.onNodeWithTag("settings_resolver_provider_custom-dns").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Private resolver").assertExists()
        compose.onNodeWithText("doq://resolver.example:8853, doq://secondary.example:853").assertExists()
        compose.onNodeWithTag("settings_resolver_transport_doq_selected_mark", useUnmergedTree = true)
            .performScrollTo().assertExists()
    }

    @Test
    fun `the selected served provider row does not request a switch`() {
        val selected = mutableListOf<MilestoneResolverPlan>()
        setScreen(
            state = state(plan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK),
            onSelected = { selected += it },
        )

        compose.onNodeWithTag("settings_resolver_provider_mullvad").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(emptyList<MilestoneResolverPlan>(), selected) }
    }

    @Test
    fun `an in flight switch disables every toggle and shows the applying label`() {
        val selected = mutableListOf<MilestoneResolverPlan>()
        setScreen(
            state = state(plan = MilestoneResolverPlan.DEVICE_DNS_ONLY),
            switchState = ResolverPlanSwitchUiState.Applying(MilestoneResolverPlan.MULLVAD_ONLY),
            onSelected = { selected += it },
        )

        compose.onNodeWithTag("settings_resolver_applying").performScrollTo().assertExists()
        compose.onNodeWithTag("settings_resolver_device_toggle").assertIsNotEnabled()
        compose.onNodeWithTag("settings_resolver_fallback_toggle")
            .performScrollTo()
            .assertIsNotEnabled()

        compose.runOnIdle { assertEquals(emptyList<MilestoneResolverPlan>(), selected) }
    }

    @Test
    fun `a failed switch keeps the honest failure panel`() {
        setScreen(
            state = state(plan = MilestoneResolverPlan.DEVICE_DNS_ONLY),
            switchState = ResolverPlanSwitchUiState.Failed(MilestoneResolverPlan.MULLVAD_ONLY),
        )

        compose.onNodeWithTag("settings_resolver_switch_failed").performScrollTo()
        compose.onNodeWithText(
            "The DNS resolver switch could not be applied. Your current resolver stays in effect.",
        ).assertExists()
    }

    @Test
    fun `each typed health state renders its exact title and detail pair`() {
        setScreen(
            state = state(
                plan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
                health = ResolverHealthUi.ENCRYPTED_FALLBACK,
            ),
        )
        compose.onNodeWithText("Encrypted fallback").performScrollTo().assertExists()
        compose.onNodeWithText(
            "Filtering is on using the encrypted DNS backup because your DNS provider is " +
                "briefly unavailable",
        ).performScrollTo().assertExists()
    }

    @Test
    fun `slow health renders the slow copy that does not claim a reconnect fix`() {
        setScreen(
            state = state(
                plan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
                health = ResolverHealthUi.SLOW,
            ),
        )
        compose.onNodeWithText("DNS slow").performScrollTo().assertExists()
        compose.onNodeWithText(
            "Your DNS provider is responding slowly. Reconnect or switch providers.",
        ).performScrollTo().assertExists()
    }

    @Test
    fun `missing health assessment renders the honest unavailable line`() {
        setScreen(state = state(plan = MilestoneResolverPlan.DEVICE_DNS_ONLY, health = null))

        compose.onNodeWithText("DNS health is unavailable").performScrollTo().assertExists()
    }

    @Test
    fun `an unavailable plan keeps the honest unavailable panel`() {
        setScreen(state = state(plan = null))

        compose.onNodeWithTag("settings_resolver_unavailable_panel").performScrollTo()
        compose.onNodeWithText("The current DNS resolver plan is unavailable.").assertExists()
        compose.onNodeWithTag("settings_resolver_device_toggle").assertDoesNotExist()
    }

    @Test
    fun `a null selection callback disables the served controls`() {
        setScreen(
            state = state(plan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK),
            onSelected = null,
        )

        compose.onNodeWithTag("settings_resolver_device_toggle")
            .performScrollTo()
            .assertIsNotEnabled()
        compose.onNodeWithTag("settings_resolver_fallback_toggle")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    private fun setScreen(
        state: SettingsResolverUiState,
        switchState: ResolverPlanSwitchUiState = ResolverPlanSwitchUiState.Idle,
        onSelected: ((MilestoneResolverPlan) -> Unit)? = {},
        onDnsSelection: ((ResolverSelection) -> Unit)? = null,
    ) {
        compose.setContent {
            LavaTheme {
                DnsResolverSettingsScreen(
                    state = state,
                    onBack = {},
                    switchState = switchState,
                    onResolverSelected = onSelected,
                    onDnsSelection = onDnsSelection,
                )
            }
        }
    }

    private fun state(
        plan: MilestoneResolverPlan?,
        health: ResolverHealthUi? = ResolverHealthUi.HEALTHY,
    ) = SettingsResolverUiState(
        plan = plan,
        health = health,
        inFlight = false,
        error = null,
    )
}
