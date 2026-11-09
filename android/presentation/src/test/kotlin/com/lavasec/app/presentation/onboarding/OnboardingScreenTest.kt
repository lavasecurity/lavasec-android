package com.lavasec.app.presentation.onboarding

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.onboarding.OnboardingAction
import com.lavasec.app.foundation.onboarding.OnboardingReducer
import com.lavasec.app.foundation.onboarding.OnboardingState
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `vpn page dispatches only the explicit consent preparation callback`() {
        var forwards = 0
        val vpn = sequenceToVpn()
        compose.setContent {
            OnboardingScreen(
                state = OnboardingUiState(vpn),
                callbacks = OnboardingCallbacks(onForward = { forwards += 1 }),
            )
        }

        compose.onNodeWithText("Allow VPN configuration").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, forwards) }
    }

    @Test
    fun `reconstruction state exposes no optimistic retry action`() {
        compose.setContent {
            OnboardingScreen(
                state = OnboardingUiState(
                    onboarding = OnboardingState.initial(),
                    reconstructionRequired = true,
                    error = "Onboarding authority requires reconstruction.",
                ),
                callbacks = OnboardingCallbacks(onForward = {}),
            )
        }

        compose.onNodeWithText("Onboarding authority requires reconstruction.")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `failed setup offers reconstruction and keeps wizard actions disabled while retrying`() {
        var retries = 0
        var forwards = 0
        val retrying = mutableStateOf(false)
        val done = OnboardingReducer.reduce(
            OnboardingReducer.reduce(
                sequenceToVpn(), OnboardingAction.VpnConfigurationResult(successful = true),
            ),
            OnboardingAction.NotificationPermissionResult(granted = true),
        ).let { OnboardingReducer.reduce(it, OnboardingAction.Forward) }
            .let { OnboardingReducer.reduce(it, OnboardingAction.Forward) }
        compose.setContent {
            OnboardingScreen(
                state = OnboardingUiState(
                    onboarding = done,
                    reconstructionRequired = true,
                    reconstructionInFlight = retrying.value,
                    error = "Your saved setup needs to be checked.",
                ),
                callbacks = OnboardingCallbacks(
                    onForward = { forwards += 1 },
                    onRetry = { retries += 1; retrying.value = true },
                ),
            )
        }

        compose.onNodeWithText("Back").assertIsNotEnabled()
        compose.onNodeWithText("Finish setup").assertIsNotEnabled()
        compose.onNodeWithText("Try again").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, forwards)
        }
        compose.onNodeWithText("Try again").assertIsNotEnabled()
        compose.onNodeWithText("Checking your saved setup…").assertExists()
        compose.onNodeWithText("Finish setup").assertIsNotEnabled()
    }

    @Test
    fun `healthy onboarding never exposes reconstruction even with a retry callback`() {
        compose.setContent {
            OnboardingScreen(
                state = OnboardingUiState(OnboardingState.initial()),
                callbacks = OnboardingCallbacks(onForward = {}, onRetry = {}),
            )
        }
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `notification page has one explicit permission action`() {
        var requests = 0
        val notification = OnboardingReducer.reduce(
            sequenceToVpn(),
            OnboardingAction.VpnConfigurationResult(successful = true),
        )
        compose.setContent {
            OnboardingScreen(
                state = OnboardingUiState(notification),
                callbacks = OnboardingCallbacks(
                    onForward = {},
                    onNotificationPermissionRequest = { requests += 1 },
                ),
            )
        }

        compose.onNodeWithText("Choose notification access").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun `notification denial explains and routes to system settings instead of re-requesting`() {
        var forwards = 0
        var settingsOpened = 0
        val notification = OnboardingReducer.reduce(
            sequenceToVpn(),
            OnboardingAction.VpnConfigurationResult(successful = true),
        )
        compose.setContent {
            OnboardingScreen(
                state = OnboardingUiState(
                    onboarding = notification,
                    notificationPermissionGranted = false,
                ),
                callbacks = OnboardingCallbacks(
                    onForward = { forwards += 1 },
                    onOpenNotificationSettings = { settingsOpened += 1 },
                ),
            )
        }

        compose.onNodeWithText(
            "Notification access was declined. Lava cannot show protection alerts. " +
                "You can enable them later in Android settings.",
        ).assertExists()
        compose.onNodeWithText("Open settings").performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened) }

        // A denied state never re-requests; the primary action advances instead.
        compose.onNodeWithText("Choose notification access").assertDoesNotExist()
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { assertEquals(1, forwards) }
    }

    @Test
    fun `vpn denial shows the post-denial explanation while the consent stays retryable`() {
        val vpn = sequenceToVpn()
        compose.setContent {
            OnboardingScreen(
                state = OnboardingUiState(vpn, vpnConsentDenied = true),
                callbacks = OnboardingCallbacks(onForward = {}),
            )
        }

        compose.onNodeWithText(
            "Android declined the VPN configuration request. Lava needs this consent " +
                "to create its local DNS tunnel. You can try again.",
        ).assertExists()
        compose.onNodeWithText("Allow VPN configuration").assertIsEnabled()
    }

    @Test
    fun `protection level renders grouped choice cards with summaries and the default disclosure`() {
        var selected: ProtectionLevel? = null
        val level = OnboardingReducer.reduce(
            sequenceToVpn().let { OnboardingReducer.reduce(it, OnboardingAction.VpnConfigurationResult(successful = true)) },
            OnboardingAction.NotificationPermissionResult(granted = true),
        )
        compose.setContent {
            OnboardingScreen(
                state = OnboardingUiState(level),
                callbacks = OnboardingCallbacks(
                    onForward = {},
                    onProtectionLevelSelected = { selected = it },
                ),
            )
        }

        compose.onAllNodesWithTag("onboarding_choices").assertCountEquals(1)
        compose.onNodeWithTag("onboarding_choice_0").assertExists()
        compose.onNodeWithTag("onboarding_choice_1").assertExists()
        compose.onNodeWithTag("onboarding_choice_2").assertExists()
        compose.onNodeWithText("Blocks malicious sites: phishing, scams, and malware.")
            .assertExists()
        compose.onNodeWithText("Adds spam, fraud, and abuse coverage. Best for most.")
            .assertExists()
        compose.onNodeWithText("Adds ads and trackers. May break some sites.")
            .assertExists()
        // The durable default (Balanced) is disclosed and marked selected.
        compose.onNodeWithText("Your current default is Balanced.").assertExists()
        compose.onNodeWithTag("onboarding_choice_1").assertIsSelected()
        compose.onNodeWithTag("onboarding_choice_0").assertIsNotSelected()

        compose.onNodeWithTag("onboarding_choice_0").performClick()
        compose.runOnIdle { assertEquals(ProtectionLevel.CORE, selected) }
    }

    @Test
    fun `resolver renders grouped choice cards with summaries and the provider disclosure`() {
        var selected: MilestoneResolverPlan? = null
        val resolver = OnboardingReducer.reduce(
            sequenceToVpn().let { OnboardingReducer.reduce(it, OnboardingAction.VpnConfigurationResult(successful = true)) },
            OnboardingAction.NotificationPermissionResult(granted = true),
        ).let { OnboardingReducer.reduce(it, OnboardingAction.Forward) }.let {
            // This retained native preset renderer covers legacy Mullvad selections. Fresh
            // production onboarding is the shared RN flow and now defaults to Quad9 Unfiltered.
            OnboardingReducer.reduce(it, OnboardingAction.ResolverSelected(
                MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK))
        }
        compose.setContent {
            OnboardingScreen(
                state = OnboardingUiState(resolver),
                callbacks = OnboardingCallbacks(
                    onForward = {},
                    onResolverSelected = { selected = it },
                ),
            )
        }

        compose.onAllNodesWithTag("onboarding_choices").assertCountEquals(1)
        compose.onNodeWithTag("onboarding_choice_0").assertExists()
        compose.onNodeWithTag("onboarding_choice_1").assertExists()
        compose.onNodeWithTag("onboarding_choice_2").assertExists()
        compose.onNodeWithText(
            "Uses the DNS resolver from the current Wi-Fi or cellular network while " +
                "Lava still filters locally.",
        ).assertExists()
        compose.onNodeWithText(
            "If your device's DNS can't be reached, allowed requests briefly use Mullvad " +
                "over an encrypted connection, then switch back automatically. Mullvad is " +
                "an outside provider, used only for recovery.",
        ).assertExists()
        compose.onNodeWithText(
            "Sends every allowed request to Mullvad over an encrypted connection.",
        ).assertExists()
        // The current fresh-install default is distinct from this retained legacy choice.
        compose.onNodeWithText(
            "Your current default is Device DNS with encrypted fallback through Quad9.",
        ).assertExists()
        compose.onNodeWithTag("onboarding_choice_0").assertIsSelected()

        compose.onNodeWithTag("onboarding_choice_1").performClick()
        compose.runOnIdle { assertEquals(MilestoneResolverPlan.DEVICE_DNS_ONLY, selected) }
    }

    @Test
    fun `fresh native resolver cards display Quad9 and radio actions retain its actual draft`() {
        val quality = OnboardingReducer.reduce(
            OnboardingReducer.reduce(sequenceToVpn(), OnboardingAction.VpnConfigurationResult(true)),
            OnboardingAction.NotificationPermissionResult(true),
        ).let { OnboardingReducer.reduce(it, OnboardingAction.Forward) }
        val retained = mutableStateOf(quality)
        var legacyCalls = 0
        val choices = mutableListOf<Pair<Boolean, Boolean>>()
        compose.setContent {
            OnboardingScreen(OnboardingUiState(retained.value), OnboardingCallbacks(
                onForward = {}, onResolverSelected = { legacyCalls++ },
                onResolverRouteSelected = { device, encrypted ->
                    choices += device to encrypted
                    retained.value = OnboardingReducer.reduce(retained.value, OnboardingAction.ResolverRouteSelected(device, encrypted))
                },
            ))
        }
        compose.onNodeWithTag("onboarding_choice_0").assertIsSelected()
        compose.onNodeWithText("Your current default is Device DNS with encrypted fallback through Quad9.").assertExists()
        compose.onNodeWithText("If your device's DNS can't be reached, allowed requests briefly use Quad9 over an encrypted connection, then switch back automatically. Quad9 is an outside provider, used only for recovery.").assertExists()
        compose.onNodeWithText("Sends every allowed request to Quad9 over an encrypted connection.").assertExists()
        for ((index, route) in listOf(1 to (true to false), 2 to (false to true), 0 to (true to true))) {
            compose.onNodeWithTag("onboarding_choice_$index").performClick()
            compose.onNodeWithTag("onboarding_choice_$index").assertIsSelected()
            compose.runOnIdle {
                assertEquals(route, choices.last())
                assertEquals(quality.draft.copy(resolver = quality.draft.resolver.copy(
                    useDeviceDns = route.first, useEncryptedFallback = route.second)), retained.value.draft)
                assertEquals(0, legacyCalls)
            }
        }
        compose.runOnIdle { assertTrue(choices.size == 3) }
        // Rich recovered rows are authoritative even when their legacy summary names
        // Mullvad. The read-only label must never silently borrow that scalar provider.
        val quad9 = ResolverTier(ResolverSelectionEntry(ResolverProviderId.QUAD9_UNFILTERED, CatalogResolverTransport.DOH))
        val recovered = ResolverConfiguration(savedTiers = listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN), quad9))
        assertEquals("Quad9", resolverProviderName(recovered))
        val disabled = recovered.copy(useEncryptedFallback = false, savedTiers = listOf(
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN), quad9.copy(isEnabled = false, retainedName = "Retained backup")))
        assertEquals("Retained backup", resolverProviderName(disabled))
    }

    private fun sequenceToVpn(): OnboardingState {
        var state = OnboardingState.initial()
        repeat(3) { state = OnboardingReducer.reduce(state, OnboardingAction.Forward) }
        return state
    }
}
