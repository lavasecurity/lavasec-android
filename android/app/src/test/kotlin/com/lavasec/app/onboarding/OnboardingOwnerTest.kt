package com.lavasec.app.onboarding

import android.content.Intent
import com.lavasec.app.foundation.onboarding.OnboardingPage
import com.lavasec.app.platform.VpnPreparation
import com.lavasec.app.platform.VpnPreparationResult
import com.lavasec.app.presentation.ProductNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class OnboardingOwnerTest {
    @Test
    fun `prepared VPN advances without manufacturing a consent request`() = runTest {
        val owner = owner(vpn = VpnPreparation { VpnPreparationResult.Prepared })
        advanceToVpn(owner)

        owner.forward()

        assertEquals(OnboardingPage.NOTIFICATIONS, owner.state.value.onboarding?.page)
        assertTrue(owner.state.value.onboarding?.vpnConfigured == true)
        assertNull(owner.state.value.pendingVpnConsent)
    }

    @Test
    fun `consent request is exact and duplicate forward cannot launch it twice`() = runTest {
        val request = Intent("vpn-consent")
        var preparations = 0
        val owner = owner(
            vpn = VpnPreparation {
                preparations += 1
                VpnPreparationResult.ConsentRequired(request)
            },
        )
        advanceToVpn(owner)

        owner.forward()
        val pending = checkNotNull(owner.state.value.pendingVpnConsent)
        owner.forward()

        assertEquals(1, preparations)
        assertTrue(pending.request === request)
        assertEquals(OnboardingPage.VPN, owner.state.value.onboarding?.page)
        assertFalse(owner.completeVpnConsent(pending.id + 1L, granted = true))
        assertEquals(OnboardingPage.VPN, owner.state.value.onboarding?.page)
        assertTrue(owner.completeVpnConsent(pending.id, granted = false))
        assertEquals(OnboardingPage.VPN, owner.state.value.onboarding?.page)

        owner.forward()
        val retry = checkNotNull(owner.state.value.pendingVpnConsent)
        assertTrue(retry.id > pending.id)
        assertTrue(owner.completeVpnConsent(retry.id, granted = true))
        assertEquals(OnboardingPage.NOTIFICATIONS, owner.state.value.onboarding?.page)
    }

    @Test
    fun `cancel retires only the exact pending consent without changing setup`() = runTest {
        val owner = owner(vpn = VpnPreparation { VpnPreparationResult.ConsentRequired(Intent("vpn-consent")) })
        advanceToVpn(owner)
        owner.forward()
        val before = owner.state.value
        val pending = checkNotNull(before.pendingVpnConsent)

        assertFalse(owner.cancelVpnConsent(pending.id + 1L))
        assertEquals(before, owner.state.value)
        assertTrue(owner.cancelVpnConsent(pending.id))
        assertEquals(before.copy(pendingVpnConsent = null), owner.state.value)
        assertFalse(owner.state.value.vpnConsentDenied)

        owner.forward()
        val successor = checkNotNull(owner.state.value.pendingVpnConsent)
        assertTrue(successor.id > pending.id)
        val next = owner.state.value
        assertFalse(owner.cancelVpnConsent(pending.id))
        assertEquals(next, owner.state.value)
        assertTrue(owner.completeVpnConsent(successor.id, granted = true))
        assertEquals(OnboardingPage.NOTIFICATIONS, owner.state.value.onboarding?.page)
    }

    @Test
    fun `late consent cleanup preserves a real denial and a cancelled retry`() = runTest {
        val owner = owner(vpn = VpnPreparation { VpnPreparationResult.ConsentRequired(Intent("vpn-consent")) })
        advanceToVpn(owner)
        owner.forward()
        val denied = checkNotNull(owner.state.value.pendingVpnConsent)
        assertTrue(owner.completeVpnConsent(denied.id, granted = false))
        val denial = owner.state.value
        assertFalse(owner.cancelVpnConsent(denied.id))
        assertEquals(denial, owner.state.value)

        owner.forward()
        val retry = checkNotNull(owner.state.value.pendingVpnConsent)
        val beforeCancel = owner.state.value
        assertTrue(owner.cancelVpnConsent(retry.id))
        assertEquals(beforeCancel.copy(pendingVpnConsent = null), owner.state.value)
        assertTrue(owner.state.value.vpnConsentDenied)
        assertEquals(OnboardingPage.VPN, owner.state.value.onboarding?.page)
    }

    @Test
    fun `notification denial stays on the page and the primary action advances`() = runTest {
        val owner = owner(vpn = VpnPreparation { VpnPreparationResult.Prepared })
        advanceToVpn(owner)
        owner.forward()

        assertTrue(owner.completeNotificationPermission(granted = false))

        val state = checkNotNull(owner.state.value.onboarding)
        assertEquals(OnboardingPage.NOTIFICATIONS, state.page)
        assertTrue(state.notificationHandled)
        assertEquals(false, owner.state.value.notificationPermissionGranted)

        owner.forward()
        assertEquals(OnboardingPage.PROTECTION_LEVEL, owner.state.value.onboarding?.page)
    }

    @Test
    fun `vpn denial arms the post-denial flag and a granted consent clears it`() = runTest {
        val request = Intent("vpn-consent")
        val owner = owner(vpn = VpnPreparation { VpnPreparationResult.ConsentRequired(request) })
        advanceToVpn(owner)
        owner.forward()
        val pending = checkNotNull(owner.state.value.pendingVpnConsent)

        assertTrue(owner.completeVpnConsent(pending.id, granted = false))
        assertTrue(owner.state.value.vpnConsentDenied)
        assertEquals(OnboardingPage.VPN, owner.state.value.onboarding?.page)

        // The denial explanation stays armed until the next consent resolves.
        owner.forward()
        val retry = checkNotNull(owner.state.value.pendingVpnConsent)
        assertTrue(owner.state.value.vpnConsentDenied)
        assertTrue(owner.completeVpnConsent(retry.id, granted = true))
        assertFalse(owner.state.value.vpnConsentDenied)
        assertEquals(OnboardingPage.NOTIFICATIONS, owner.state.value.onboarding?.page)
    }

    @Test
    fun `backing out of the vpn page clears the post-denial flag`() = runTest {
        val request = Intent("vpn-consent")
        val owner = owner(vpn = VpnPreparation { VpnPreparationResult.ConsentRequired(request) })
        advanceToVpn(owner)
        owner.forward()
        val pending = checkNotNull(owner.state.value.pendingVpnConsent)

        assertTrue(owner.completeVpnConsent(pending.id, granted = false))
        assertTrue(owner.state.value.vpnConsentDenied)

        owner.back()
        assertFalse(owner.state.value.vpnConsentDenied)
        assertEquals(OnboardingPage.FEATURES, owner.state.value.onboarding?.page)
    }

    @Test
    fun `protection level and resolver selections land in the exact committed configuration`() =
        runTest {
            val committed = mutableListOf<com.lavasec.app.foundation.config.AppConfiguration>()
            val owner = owner(
                vpn = VpnPreparation { VpnPreparationResult.Prepared },
                commit = { committed += it },
            )
            advanceToVpn(owner)
            owner.forward()
            owner.completeNotificationPermission(granted = true)
            owner.selectProtectionLevel(com.lavasec.app.foundation.config.ProtectionLevel.EXTRA)
            owner.forward()
            owner.selectResolver(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY)
            owner.forward()

            val state = owner.state.value
            assertTrue(state.completed)
            assertEquals(1, committed.size)
            assertEquals(
                com.lavasec.app.foundation.config.ProtectionLevel.EXTRA,
                committed.single().protectionLevel,
            )
            assertEquals(
                com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY
                    .configuration,
                committed.single().resolver,
            )
        }

    @Test
    fun `native resolver route adapter commits exact Quad9 draft and releases the staged deep link`() = runTest {
        val committed = mutableListOf<com.lavasec.app.foundation.config.AppConfiguration>()
        val owner = owner(vpn = VpnPreparation { VpnPreparationResult.Prepared }, commit = { committed += it })
        owner.stageNavigation(ProductNavigation.settings)
        advanceToVpn(owner)
        owner.forward()
        owner.completeNotificationPermission(granted = true)
        owner.forward()
        val before = checkNotNull(owner.state.value.onboarding).draft
        assertEquals(com.lavasec.app.foundation.resolver.BuiltInDohResolverIds.QUAD9_UNFILTERED, before.resolver.encryptedFallbackId)
        owner.selectResolverRoute(true, false)
        owner.selectResolverRoute(false, true)
        val chosen = checkNotNull(owner.state.value.onboarding).draft
        assertEquals(before.copy(resolver = before.resolver.copy(useDeviceDns = false, useEncryptedFallback = true)), chosen)
        assertTrue(committed.isEmpty())
        assertNull(owner.state.value.releasedNavigation)
        owner.forward()
        assertTrue(owner.state.value.completed)
        assertEquals(listOf(chosen), committed)
        assertEquals(ProductNavigation.settings, owner.state.value.releasedNavigation?.navigation)
    }

    @Test
    fun `deep link stays staged until one exact durable onboarding commit completes`() = runTest {
        val committed = mutableListOf<com.lavasec.app.foundation.config.AppConfiguration>()
        val owner = owner(
            vpn = VpnPreparation { VpnPreparationResult.Prepared },
            commit = { committed += it },
        )
        owner.stageNavigation(ProductNavigation.settings)
        assertNull(owner.state.value.releasedNavigation)
        advanceToVpn(owner)
        owner.forward()
        owner.completeNotificationPermission(granted = true)
        owner.forward()
        owner.forward()

        val state = owner.state.value
        assertTrue(state.completed)
        assertEquals(1, committed.size)
        assertEquals(ProductNavigation.settings, state.releasedNavigation?.navigation)

        owner.forward()
        assertEquals(1, committed.size)
        val release = checkNotNull(state.releasedNavigation)
        assertFalse(owner.consumeReleasedNavigation(release.id + 1L))
        assertTrue(owner.consumeReleasedNavigation(release.id))
        assertNull(owner.state.value.releasedNavigation)
    }

    @Test
    fun `ambiguous commit requires durable reconstruction and never publishes completion`() =
        runTest {
            var attempts = 0
            val owner = owner(
                vpn = VpnPreparation { VpnPreparationResult.Prepared },
                commit = {
                    attempts += 1
                    error("ambiguous write")
                },
            )
            advanceToVpn(owner)
            owner.forward()
            owner.completeNotificationPermission(granted = true)
            owner.forward()
            owner.forward()

            assertFalse(owner.state.value.completed)
            assertTrue(owner.state.value.reconstructionRequired)
            assertEquals(1, attempts)
            assertFalse(owner.stageNavigation(ProductNavigation.settings))
            assertNull(owner.state.value.stagedNavigation)
            owner.forward()
            assertEquals(1, attempts)
        }

    @Test
    fun `process recreation trusts only the supplied durable completion`() = runTest {
        var commits = 0
        val recreated = OnboardingOwner(
            onboardingCompleted = true,
            vpnPreparation = VpnPreparation { error("must not prepare") },
            commit = {
                commits += 1
                error("must not commit")
            },
            scope = backgroundScope,
        )

        recreated.stageNavigation(ProductNavigation.settings)

        assertTrue(recreated.state.value.completed)
        assertNull(recreated.state.value.onboarding)
        assertEquals(ProductNavigation.settings, recreated.state.value.releasedNavigation?.navigation)
        assertEquals(0, commits)
    }

    @Test
    fun `graph retirement cancels an in flight onboarding commit without stale completion`() =
        runTest {
            val commitStarted = CompletableDeferred<Unit>()
            val neverComplete = CompletableDeferred<Unit>()
            val owner = owner(
                vpn = VpnPreparation { VpnPreparationResult.Prepared },
                commit = {
                    commitStarted.complete(Unit)
                    neverComplete.await()
                },
            )
            advanceToVpn(owner)
            owner.forward()
            owner.completeNotificationPermission(granted = true)
            owner.forward()

            val completion = launch { owner.forward() }
            commitStarted.await()
            owner.close()
            completion.join()

            assertFalse(owner.state.value.completed)
            assertTrue(owner.state.value.commitInFlight)
            assertFalse(owner.state.value.reconstructionRequired)
        }

    private fun TestScope.owner(
        vpn: VpnPreparation<Intent>,
        commit: suspend (com.lavasec.app.foundation.config.AppConfiguration) -> Unit = {},
    ) = OnboardingOwner(
        onboardingCompleted = false,
        vpnPreparation = vpn,
        commit = commit,
        scope = backgroundScope,
    )

    private suspend fun advanceToVpn(owner: OnboardingOwner) {
        repeat(3) { owner.forward() }
        assertEquals(OnboardingPage.VPN, owner.state.value.onboarding?.page)
    }
}
