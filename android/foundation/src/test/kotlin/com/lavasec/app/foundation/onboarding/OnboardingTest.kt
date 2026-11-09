package com.lavasec.app.foundation.onboarding

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test
    fun `built in encrypted resolver allowlist is exact`() {
        assertEquals(
            setOf(
                "mullvad-doh",
                "cloudflare-1111-doh",
                "quad9-secure-doh",
                "quad9-unfiltered-doh",
                "hagezi-root-doh",
                "google-public-dns-doh",
            ),
            BuiltInDohResolverIds.all,
        )
    }

    @Test
    fun `fresh shared onboarding defaults and fallback toggle use unfiltered Quad9`() {
        val initial = OnboardingState.initial()
        assertTrue(initial.draft.resolver.useDeviceDns)
        assertTrue(initial.draft.resolver.useEncryptedFallback)
        assertEquals(BuiltInDohResolverIds.QUAD9_UNFILTERED, initial.draft.resolver.encryptedFallbackId)
        val quality = advanceTo(initial, OnboardingPage.CONNECTION_QUALITY)
        val disabled = OnboardingReducer.reduce(quality, OnboardingAction.FallbackSelected(false))
        assertTrue(disabled.draft.resolver.useDeviceDns)
        assertFalse(disabled.draft.resolver.useEncryptedFallback)
        assertEquals(BuiltInDohResolverIds.QUAD9_UNFILTERED, disabled.draft.resolver.encryptedFallbackId)
        val enabled = OnboardingReducer.reduce(disabled, OnboardingAction.FallbackSelected(true))
        assertEquals(initial.draft.resolver, enabled.draft.resolver)
        assertEquals(quality.draft.protectionLevel, enabled.draft.protectionLevel)
        assertEquals(quality.draft.activeFilterId, enabled.draft.activeFilterId)
        assertEquals(quality.draft.keepCounts, enabled.draft.keepCounts)
        assertEquals(quality.draft.keepHistory, enabled.draft.keepHistory)
    }

    @Test
    fun `native resolver route choices preserve the actual provider and unrelated draft`() {
        val quality = advanceTo(OnboardingState.initial(), OnboardingPage.CONNECTION_QUALITY)
        for (original in listOf(quality, OnboardingReducer.reduce(quality,
            OnboardingAction.ResolverSelected(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK)))) {
            var selected = original
            for ((device, encrypted) in listOf(true to false, false to true, true to true)) {
                selected = OnboardingReducer.reduce(selected, OnboardingAction.ResolverRouteSelected(device, encrypted))
                assertEquals(original.draft.copy(resolver = original.draft.resolver.copy(
                    useDeviceDns = device, useEncryptedFallback = encrypted)), selected.draft)
                assertEquals(original.page, selected.page)
                assertEquals(original.reached, selected.reached)
            }
            val done = OnboardingReducer.reduce(selected, OnboardingAction.Forward)
            assertEquals(selected.draft, OnboardingReducer.complete(done))
            assertEquals(selected, OnboardingReducer.reduce(selected, OnboardingAction.ResolverRouteSelected(false, false)))
        }
        val savedRows = ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK.tiers.mapIndexed { index, row ->
            row.copy(retainedName = "Recovered row ${index + 1}")
        }
        val recovered = quality.withDraft(quality.draft.copy(resolver =
            MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK.configuration.copy(savedTiers = savedRows)))
        val encrypted = OnboardingReducer.reduce(recovered, OnboardingAction.ResolverRouteSelected(false, true))
        assertEquals(recovered.draft.copy(resolver = recovered.draft.resolver.copy(useDeviceDns = false,
            savedTiers = savedRows.mapIndexed { index, row -> row.copy(isEnabled = index == 1) })), encrypted.draft)
        assertEquals(recovered.draft, OnboardingReducer.reduce(encrypted,
            OnboardingAction.ResolverRouteSelected(true, true)).draft)

        val single = quality.withDraft(quality.draft.copy(resolver = MilestoneResolverPlan.MULLVAD_ONLY.configuration.copy(
            savedTiers = ResolverSelectionPresets.MULLVAD_ONLY.tiers)))
        val both = OnboardingReducer.reduce(single, OnboardingAction.ResolverRouteSelected(true, true))
        assertEquals(ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK.tiers, both.draft.resolver.savedTiers)
        assertEquals(single.draft.resolver.encryptedFallbackId, both.draft.resolver.encryptedFallbackId)

        val customSource = ResolverConfiguration(customDohUrl = "https://resolver.example/dns-query",
            customName = "Recovered DNS", customSecondaryEndpoint = "https://backup.example/dns-query")
        for (source in listOf(customSource, customSource.copy(savedTiers = customSource.toSelection().tiers))) {
            val custom = quality.withDraft(quality.draft.copy(resolver = source))
            assertEquals(custom, OnboardingReducer.reduce(custom, OnboardingAction.ResolverRouteSelected(true, false)))
            val customEncrypted = OnboardingReducer.reduce(custom, OnboardingAction.ResolverRouteSelected(false, true))
            assertEquals(source.customDohUrl, customEncrypted.draft.resolver.customDohUrl)
            assertEquals(source.customName, customEncrypted.draft.resolver.customName)
            assertEquals(source.customSecondaryEndpoint, customEncrypted.draft.resolver.customSecondaryEndpoint)
            assertEquals(source.encryptedFallbackId, customEncrypted.draft.resolver.encryptedFallbackId)
            assertEquals(custom.draft.copy(resolver = customEncrypted.draft.resolver), customEncrypted.draft)
            assertEquals(custom.draft, OnboardingReducer.reduce(customEncrypted,
                OnboardingAction.ResolverRouteSelected(true, true)).draft)
        }
        for (source in listOf(MilestoneResolverPlan.MULLVAD_ONLY.configuration, customSource.copy(useDeviceDns = false))) {
            val encryptedRow = source.toSelection().tiers.single()
            val reversed = quality.withDraft(quality.draft.copy(resolver = source.copy(savedTiers = listOf(
                encryptedRow, ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, isEnabled = false),
            ))))
            assertEquals(reversed, OnboardingReducer.reduce(reversed, OnboardingAction.ResolverRouteSelected(true, true)))
        }
        val rich = quality.withDraft(quality.draft.copy(resolver = ResolverConfiguration(savedTiers = listOf(
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.QUAD9_UNFILTERED, CatalogResolverTransport.DOH)),
        ))))
        assertEquals(rich, OnboardingReducer.reduce(rich, OnboardingAction.ResolverRouteSelected(false, true)))
        val initial = OnboardingState.initial()
        assertEquals(initial, OnboardingReducer.reduce(initial, OnboardingAction.ResolverRouteSelected(false, true)))
    }

    @Test
    fun `pages retain the pinned iOS order`() {
        assertEquals(
            listOf(
                OnboardingPage.LAVA,
                OnboardingPage.GUARD_INTRO,
                OnboardingPage.FEATURES,
                OnboardingPage.VPN,
                OnboardingPage.NOTIFICATIONS,
                OnboardingPage.PROTECTION_LEVEL,
                OnboardingPage.CONNECTION_QUALITY,
                OnboardingPage.DONE,
            ),
            OnboardingPage.entries,
        )
    }

    @Test
    fun `back pops transient navigation history and visited page selection cannot skip ahead`() {
        val initial = OnboardingState.initial()

        assertEquals(initial, OnboardingReducer.reduce(initial, OnboardingAction.Back))
        assertEquals(
            initial,
            OnboardingReducer.reduce(initial, OnboardingAction.PageSelected(OnboardingPage.VPN)),
        )

        val features = advanceTo(initial, OnboardingPage.FEATURES)
        val lava = OnboardingReducer.reduce(features, OnboardingAction.PageSelected(OnboardingPage.LAVA))
        val backed = OnboardingReducer.reduce(lava, OnboardingAction.Back)

        assertEquals(OnboardingPage.LAVA, lava.page)
        assertEquals(OnboardingPage.FEATURES, backed.page)
        assertEquals(
            OnboardingPage.GUARD_INTRO,
            OnboardingReducer.reduce(backed, OnboardingAction.Back).page,
        )
    }

    @Test
    fun `VPN advances only after successful configuration`() {
        val vpn = advanceTo(OnboardingState.initial(), OnboardingPage.VPN)

        assertEquals(vpn, OnboardingReducer.reduce(vpn, OnboardingAction.Forward))
        assertEquals(vpn, OnboardingReducer.reduce(vpn, OnboardingAction.VpnConfigurationResult(false)))

        val configured = OnboardingReducer.reduce(vpn, OnboardingAction.VpnConfigurationResult(true))

        assertEquals(OnboardingPage.NOTIFICATIONS, configured.page)
        assertTrue(configured.vpnConfigured)
    }

    @Test
    fun `granted notification permission advances and denied keeps the page`() {
        val notifications = advanceToVpnSuccess(OnboardingState.initial())

        val granted = OnboardingReducer.reduce(
            notifications,
            OnboardingAction.NotificationPermissionResult(granted = true),
        )
        assertEquals(OnboardingPage.PROTECTION_LEVEL, granted.page)
        assertTrue(granted.notificationHandled)

        val denied = OnboardingReducer.reduce(
            notifications,
            OnboardingAction.NotificationPermissionResult(granted = false),
        )
        assertEquals(OnboardingPage.NOTIFICATIONS, denied.page)
        assertTrue(denied.notificationHandled)

        val advanced = OnboardingReducer.reduce(denied, OnboardingAction.Forward)
        assertEquals(OnboardingPage.PROTECTION_LEVEL, advanced.page)
    }

    @Test
    fun `protection level selection persists when leaving its page`() {
        val levelPage = advanceTo(OnboardingState.initial(), OnboardingPage.PROTECTION_LEVEL)
        val selected = OnboardingReducer.reduce(
            levelPage,
            OnboardingAction.ProtectionLevelSelected(ProtectionLevel.EXTRA),
        )

        val left = OnboardingReducer.reduce(selected, OnboardingAction.Back)

        assertEquals(OnboardingPage.NOTIFICATIONS, left.page)
        assertEquals(ProtectionLevel.EXTRA, left.draft.protectionLevel)
        assertEquals("filter-comprehensive", left.draft.activeFilterId)
    }

    @Test
    fun `connection quality selection persists when leaving its page`() {
        val qualityPage = advanceTo(OnboardingState.initial(), OnboardingPage.CONNECTION_QUALITY)
        val selectedPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY
        val selected = OnboardingReducer.reduce(
            qualityPage,
            OnboardingAction.ResolverSelected(selectedPlan),
        )

        val left = OnboardingReducer.reduce(selected, OnboardingAction.Back)

        assertEquals(OnboardingPage.PROTECTION_LEVEL, left.page)
        assertEquals(selectedPlan.configuration, left.draft.resolver)
    }

    @Test
    fun `protection choice persists through continue and visited dot navigation`() {
        val levelPage = advanceTo(OnboardingState.initial(), OnboardingPage.PROTECTION_LEVEL)
        val continued = OnboardingReducer.reduce(
            OnboardingReducer.reduce(
                levelPage,
                OnboardingAction.ProtectionLevelSelected(ProtectionLevel.CORE),
            ),
            OnboardingAction.Forward,
        )
        val revisited = OnboardingReducer.reduce(
            OnboardingReducer.reduce(
                levelPage,
                OnboardingAction.ProtectionLevelSelected(ProtectionLevel.EXTRA),
            ),
            OnboardingAction.PageSelected(OnboardingPage.NOTIFICATIONS),
        )

        assertEquals(ProtectionLevel.CORE, continued.draft.protectionLevel)
        assertEquals(OnboardingPage.NOTIFICATIONS, revisited.page)
        assertEquals(ProtectionLevel.EXTRA, revisited.draft.protectionLevel)
    }

    @Test
    fun `connection quality choice persists through continue and visited dot navigation`() {
        val qualityPage = advanceTo(OnboardingState.initial(), OnboardingPage.CONNECTION_QUALITY)
        val continuedPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY
        val revisitedPlan = MilestoneResolverPlan.MULLVAD_ONLY
        val continued = OnboardingReducer.reduce(
            OnboardingReducer.reduce(
                qualityPage,
                OnboardingAction.ResolverSelected(continuedPlan),
            ),
            OnboardingAction.Forward,
        )
        val revisited = OnboardingReducer.reduce(
            OnboardingReducer.reduce(
                qualityPage,
                OnboardingAction.ResolverSelected(revisitedPlan),
            ),
            OnboardingAction.PageSelected(OnboardingPage.PROTECTION_LEVEL),
        )

        assertEquals(OnboardingPage.DONE, continued.page)
        assertEquals(continuedPlan.configuration, continued.draft.resolver)
        assertEquals(OnboardingPage.PROTECTION_LEVEL, revisited.page)
        assertEquals(revisitedPlan.configuration, revisited.draft.resolver)
    }

    @Test
    fun `completion exposes one coherent configuration without protection intent`() {
        val done = advanceTo(OnboardingState.initial(), OnboardingPage.DONE)

        val configuration = OnboardingReducer.complete(done)

        assertEquals(done.draft, configuration)
        assertFalse(
            AppConfiguration::class.java.declaredFields.any { it.name == "runtimeProtectionIntent" },
        )
    }

    @Test
    fun `completion requires the done page after both gates`() {
        val quality = advanceTo(OnboardingState.initial(), OnboardingPage.CONNECTION_QUALITY)
        val done = OnboardingReducer.reduce(quality, OnboardingAction.Forward)

        assertNull(OnboardingReducer.complete(quality))
        assertTrue(done.vpnConfigured)
        assertTrue(done.notificationHandled)
        assertEquals(done.draft, OnboardingReducer.complete(done))
    }

    @Test
    fun `state construction rejects impossible page and gate combinations`() {
        val throughNotifications = OnboardingPage.entries
            .take(OnboardingPage.NOTIFICATIONS.ordinal + 1)
            .toSet()
        val throughProtectionLevel = OnboardingPage.entries
            .take(OnboardingPage.PROTECTION_LEVEL.ordinal + 1)
            .toSet()

        assertThrowsIllegalArgument {
            OnboardingState(
                page = OnboardingPage.NOTIFICATIONS,
                reached = throughNotifications,
                vpnConfigured = false,
                notificationHandled = false,
                draft = AppConfiguration.default(),
            )
        }
        assertThrowsIllegalArgument {
            OnboardingState(
                page = OnboardingPage.PROTECTION_LEVEL,
                reached = throughProtectionLevel,
                vpnConfigured = true,
                notificationHandled = false,
                draft = AppConfiguration.default(),
            )
        }
        assertThrowsIllegalArgument {
            OnboardingState(
                page = OnboardingPage.LAVA,
                reached = setOf(OnboardingPage.GUARD_INTRO),
                vpnConfigured = false,
                notificationHandled = false,
                draft = AppConfiguration.default(),
            )
        }
        assertThrowsIllegalArgument {
            OnboardingState(
                page = OnboardingPage.LAVA,
                reached = setOf(OnboardingPage.LAVA, OnboardingPage.DONE),
                vpnConfigured = true,
                notificationHandled = true,
                draft = AppConfiguration.default(),
            )
        }
        assertThrowsIllegalArgument {
            OnboardingState(
                page = OnboardingPage.LAVA,
                reached = OnboardingPage.entries.toSet(),
                vpnConfigured = false,
                notificationHandled = false,
                draft = AppConfiguration.default(),
            )
        }
        assertThrowsIllegalArgument {
            OnboardingState(
                page = OnboardingPage.LAVA,
                reached = setOf(OnboardingPage.LAVA),
                vpnConfigured = false,
                notificationHandled = true,
                draft = AppConfiguration.default(),
            )
        }
    }

    @Test
    fun `state snapshots reached pages and keeps navigation history out of its public API`() {
        val reached = linkedSetOf(OnboardingPage.LAVA)
        val state = OnboardingState(
            page = OnboardingPage.LAVA,
            reached = reached,
            vpnConfigured = false,
            notificationHandled = false,
            draft = AppConfiguration.default(),
        )
        reached += OnboardingPage.GUARD_INTRO

        assertEquals(setOf(OnboardingPage.LAVA), state.reached)
        try {
            (state.reached as MutableSet<OnboardingPage>) += OnboardingPage.GUARD_INTRO
            assertFalse("reached pages unexpectedly accepted a mutation", true)
        } catch (_: UnsupportedOperationException) {
            assertTrue(true)
        }
        assertEquals(5, OnboardingState::class.java.constructors.single().parameterCount)
        assertFalse(OnboardingState::class.java.methods.any { it.name == "getNavigationHistory" })
        assertFalse(OnboardingState::class.java.methods.any { it.name == "copy" })
        assertFalse(OnboardingState::class.java.methods.any { it.name == "component6" })
    }

    private fun advanceTo(initial: OnboardingState, target: OnboardingPage): OnboardingState {
        var state = initial
        while (state.page != target) {
            state = if (state.page == OnboardingPage.VPN) {
                OnboardingReducer.reduce(state, OnboardingAction.VpnConfigurationResult(true))
            } else if (state.page == OnboardingPage.NOTIFICATIONS) {
                OnboardingReducer.reduce(state, OnboardingAction.NotificationPermissionResult(granted = true))
            } else {
                OnboardingReducer.reduce(state, OnboardingAction.Forward)
            }
        }
        return state
    }

    private fun advanceToVpnSuccess(initial: OnboardingState): OnboardingState =
        advanceTo(initial, OnboardingPage.NOTIFICATIONS)

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            assertFalse("expected IllegalArgumentException", true)
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
