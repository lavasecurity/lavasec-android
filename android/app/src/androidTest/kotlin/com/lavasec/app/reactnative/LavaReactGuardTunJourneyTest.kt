package com.lavasec.app.reactnative

import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ScrollView
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.data.ActivityDatabase
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.data.CatalogRepository
import com.lavasec.app.data.CredentialConfigurationCodec
import com.lavasec.app.data.CredentialConfigurationRepository
import com.lavasec.app.foundation.activity.ActivityDateRangePolicy
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.filter.FilterProfile
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionFailure
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.SettingsRoute
import com.lavasec.app.platform.VpnOwnership
import com.lavasec.app.presentation.GuardPrimaryAction
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import com.lavasec.app.vpn.LavaVpnRuntimeBridge
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator shipping-path evidence: the packaged Guard starts the real production service and
 * ordinary app UDP sockets enter its Android-installed TUN. No application graph, service,
 * entitlement, consent result, resolver response, or Activity receipt is replaced/injected.
 *
 * The default Free Core filter retains every bundled source. One temporary strengthening rule
 * supplies a synthetic blocked domain; a different random .invalid domain receives a real
 * negative answer from the emulator's ordinary DNS path. This does not qualify a physical device,
 * a live encrypted provider, or successful public-address resolution.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactGuardTunJourneyTest {
    @Test
    fun freeCoreGuardServiceFiltersAllowedAndBlockedDnsThroughItsInstalledTun() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            journey.waitProtectionOff()
            assertNull("Only an idle dedicated emulator may run this journey", LavaVpnRuntimeBridge.serviceGeneration)
            assertEquals(ProtectionState.Off, LavaVpnRuntimeBridge.state.value)
            assertNull("Actual Android VPN consent is required", VpnService.prepare(journey.context))
            assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))

            val original = confirmed(journey).configuration
            val canonicalCore = FilterProfile.forLevel(ProtectionLevel.CORE)
            val profile = checkNotNull(original.profile(canonicalCore.id)) {
                "The saved canonical Free Core filter must exist"
            }
            assertEquals("Use the shipping default Free Core profile", ProtectionLevel.CORE, profile.protectionLevel)
            assertEquals(canonicalCore.enabledSourceIds, profile.enabledSourceIds)
            assertTrue("Core must retain real bundled sources", profile.enabledSourceIds.isNotEmpty())
            val resolver = original.resolverTarget.configuration.toSelection()
            assertEquals("This test requires actual Device DNS as the primary route",
                ResolverProviderId.DEVICE_DNS, resolver.primary.providerId)
            assertEquals(CatalogResolverTransport.PLAIN_DNS, resolver.primary.transport)

            val suffix = UUID.randomUUID().toString().replace("-", "")
            val blocked = "guard-blocked-$suffix.invalid"
            val allowed = "guard-allowed-$suffix.invalid"
            var primaryFailure: Throwable? = null
            try {
                // This independent journey may follow a failed Plus fixture that selected
                // Balanced. Activate only the existing canonical Core through the real
                // displayed host; preserve every profile and the adopted Quad9 fallback.
                if (original.activeProfileId != profile.id) switchActiveProfile(journey, profile.id)
                assertEquals("Selecting Core must preserve all saved profiles and resolver tiers",
                    original.withActiveProfile(profile.id), confirmed(journey).configuration)
                addRule(journey, profile.id, blocked)
                val admitted = confirmed(journey)
                assertEquals("The fixture must not replace the default source selection", profile.enabledSourceIds,
                    checkNotNull(admitted.configuration.profile(profile.id)).enabledSourceIds)
                assertEquals(profile.manualRules.blockedDomains + blocked,
                    checkNotNull(admitted.configuration.profile(profile.id)).manualRules.blockedDomains)
                journey.reopen()
                journey.waitProtectionOff()

                ActivityDatabase(journey.context).use { database ->
                    val repository = ActivityRepository(database)
                    assertTrue("Production logging must retain this test's real receipts", repository.loggingPolicy().settings.keepHistory)
                    assertTrue(repository.loggingPolicy().settings.keepCounts)
                    assertTrue(repository.history(searchText = allowed).isEmpty())
                    assertTrue(repository.history(searchText = blocked).isEmpty())
                    val range = ActivityDateRangePolicy.today(LocalDate.now())
                    val baseline = repository.summary(range)

                    // This click, rather than consent alone or a synthetic service intent, is
                    // the shipping Guard user command and durable lifecycle admission.
                    val protectionRequestedAt = System.currentTimeMillis()
                    journey.click("Turn on protection", prefix = true)
                    val manager = journey.context.getSystemService(ConnectivityManager::class.java)
                    val plan = ChainedRoutePlan.dnsOnly()
                    var installed: Network? = null
                    var previousActivityAdmission: Pair<Long, Long>? = null
                    journey.await("production service, exact artifact, real Android VPN interface and settled Activity admission", 30_000) {
                        val state = LavaVpnRuntimeBridge.state.value as? ProtectionState.Protected
                        installed = installedVpn(manager, plan)
                        val protected = state?.configurationIdentity == admitted.configurationIdentity &&
                            LavaVpnRuntimeBridge.serviceGeneration != null && installed != null &&
                            journey.snapshot().getJSONObject("protection").optBoolean("confirmed")
                        // Protected is published BEFORE the mandatory initial Activity write.
                        // A terminal DNS receipt racing that write may intentionally be dropped
                        // by the nonblocking attachment/privacy fence. Observe the real durable
                        // startup receipt and a settled revision before sending the one probe;
                        // never retry a packet or replace a missing DNS receipt with health.
                        val activityAdmission = if (protected &&
                            (repository.summary(range).liveUptimeEvidenceThroughMillis ?: -1L) >= protectionRequestedAt
                        ) LavaVpnRuntimeBridge.serviceGeneration?.let { it to repository.revisions().summaryCountRevision }
                        else null
                        val settled = activityAdmission != null && activityAdmission == previousActivityAdmission
                        previousActivityAdmission = activityAdmission
                        protected && settled
                    }
                    val vpn = checkNotNull(installed)
                    val generation = checkNotNull(LavaVpnRuntimeBridge.serviceGeneration)
                    val properties = checkNotNull(manager.getLinkProperties(vpn))
                    assertTrue("A real TUN interface must exist", !properties.interfaceName.isNullOrBlank())
                    assertTrue(properties.linkAddresses.any { it.address.hostAddress == plan.tunnelAddress })
                    assertTrue(properties.routes.any {
                        it.destination.address.hostAddress == plan.dnsServerAddress && it.destination.prefixLength == 32
                    })
                    assertEquals(listOf(plan.dnsServerAddress), properties.dnsServers.map { it.hostAddress })
                    assertTrue(checkNotNull(manager.getNetworkCapabilities(vpn)).hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                    assertFalse("Service activation must not invent a Plus entitlement",
                        journey.snapshot().getJSONObject("plus").getBoolean("enabled"))

                    val startedAt = System.currentTimeMillis()
                    val allowedQuery = query(allowed, 0x41a1)
                    val allowedReply = exchange(plan, allowedQuery)
                    assertQuestionReply(allowedQuery, allowedReply)
                    assertEquals("A synthetic .invalid query must return the real negative DNS answer", 3,
                        unsigned16(allowedReply, 2) and 0x0f)
                    assertEquals(0, unsigned16(allowedReply, 6))
                    val allowedReceipt = receipt(journey, repository, allowed)
                    assertEquals(FilterAction.ALLOW, allowedReceipt.decision.action)
                    assertEquals(FilterDecisionReason.DEFAULT_ALLOW, allowedReceipt.decision.reason)
                    assertEquals(ResolverKind.DEVICE, allowedReceipt.resolver)
                    assertTrue("The persisted receipt must carry actual Device UDP/TCP execution",
                        allowedReceipt.transport == ResolverTransport.DEVICE_UDP ||
                            allowedReceipt.transport == ResolverTransport.DEVICE_TCP)
                    assertNull("An accepted upstream DNS answer must not be a transport failure", allowedReceipt.error)
                    assertNotNull(allowedReceipt.latencyMillis)
                    assertTrue(allowedReceipt.timestampMillis >= startedAt)
                    journey.await("production accepted-response health evidence") {
                        (LavaVpnRuntimeBridge.health.value.notificationEvidence.lastPrimarySuccessAtMillis ?: 0L) >= startedAt
                    }

                    val blockedQuery = query(blocked, 0x41b2)
                    val blockedReply = exchange(plan, blockedQuery)
                    // Exact local A/0.0.0.0 reply bytes distinguish filtering from a resolver
                    // NXDOMAIN, SERVFAIL, timeout or another application's DNS response.
                    assertArrayEquals(DnsMessage.blockedResponse(blockedQuery), blockedReply)
                    val blockedReceipt = receipt(journey, repository, blocked)
                    assertEquals(FilterAction.BLOCK, blockedReceipt.decision.action)
                    assertEquals(FilterDecisionReason.BLOCKLIST, blockedReceipt.decision.reason)
                    assertNull(blockedReceipt.resolver)
                    assertNull(blockedReceipt.transport)
                    assertNull(blockedReceipt.latencyMillis)
                    assertNull(blockedReceipt.error)
                    val counts = repository.summary(range)
                    assertTrue("Only production telemetry records the allowed query", counts.allowedCount >= baseline.allowedCount + 1)
                    assertTrue("Only production telemetry records the blocked query", counts.blockedCount >= baseline.blockedCount + 1)
                    assertEquals("Both exchanges must belong to the same installed service", generation,
                        LavaVpnRuntimeBridge.serviceGeneration)
                    assertEquals(admitted.configurationIdentity,
                        (LavaVpnRuntimeBridge.state.value as ProtectionState.Protected).configurationIdentity)
                    journey.assertNoFallback()

                    val activeAuthority = pauseAuthority(journey)
                    assertTrue(activeAuthority.requested)
                    assertTrue(activeAuthority.session > 0L)
                    assertNull(activeAuthority.pause)
                    assertEquals(admitted.configurationIdentity, activeAuthority.identity)
                    if (Build.VERSION.SDK_INT < 29) {
                        // Public pre-29 APIs cannot establish Always-on/lockdown ownership.
                        // Verify the shipped conservative disposition instead of inventing a
                        // user-owned pause control or changing the emulator's VPN settings.
                        assertUnverifiedOwnership(journey, admitted.configurationIdentity)
                        openVpnSettings(journey)
                        assertEquals(generation, LavaVpnRuntimeBridge.serviceGeneration)
                        assertEquals(vpn, installedVpn(manager, plan))
                        returnFromVpnSettings(journey)
                        assertUnverifiedOwnership(journey, admitted.configurationIdentity)
                        assertEquals("Settings return must preserve the durable logical session",
                            activeAuthority, pauseAuthority(journey))
                        assertEquals(admitted.configurationIdentity, confirmed(journey).configurationIdentity)
                        assertEquals(generation, LavaVpnRuntimeBridge.serviceGeneration)
                        assertEquals(vpn, installedVpn(manager, plan))

                        val returnedAllowedQuery = query(allowed, 0x41e5)
                        val returnedAllowedReply = exchange(plan, returnedAllowedQuery)
                        assertQuestionReply(returnedAllowedQuery, returnedAllowedReply)
                        assertEquals(3, unsigned16(returnedAllowedReply, 2) and 0x0f)
                        assertEquals(0, unsigned16(returnedAllowedReply, 6))
                        val returnedAllowed = receipt(journey, repository, allowed, allowedReceipt.id)
                        assertEquals(FilterAction.ALLOW, returnedAllowed.decision.action)
                        assertEquals(FilterDecisionReason.DEFAULT_ALLOW, returnedAllowed.decision.reason)
                        assertEquals(ResolverKind.DEVICE, returnedAllowed.resolver)
                        assertTrue(returnedAllowed.transport == ResolverTransport.DEVICE_UDP ||
                            returnedAllowed.transport == ResolverTransport.DEVICE_TCP)
                        assertNull(returnedAllowed.error)
                        assertNotNull(returnedAllowed.latencyMillis)
                        val returnedBlockedQuery = query(blocked, 0x41f6)
                        assertArrayEquals(DnsMessage.blockedResponse(returnedBlockedQuery), exchange(plan, returnedBlockedQuery))
                        val returnedBlocked = receipt(journey, repository, blocked, blockedReceipt.id)
                        assertEquals(FilterAction.BLOCK, returnedBlocked.decision.action)
                        assertEquals(FilterDecisionReason.BLOCKLIST, returnedBlocked.decision.reason)
                        assertNull(returnedBlocked.resolver)
                        assertNull(returnedBlocked.transport)
                        assertNull(returnedBlocked.error)
                        assertNull(returnedBlocked.latencyMillis)
                        val returnedCounts = repository.summary(range)
                        assertTrue(returnedCounts.allowedCount >= counts.allowedCount + 1)
                        assertTrue(returnedCounts.blockedCount >= counts.blockedCount + 1)
                        assertEquals(generation, LavaVpnRuntimeBridge.serviceGeneration)
                        assertEquals(vpn, installedVpn(manager, plan))
                        assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))

                        disconnectViaVpnSettings(journey)
                        awaitOsRevoked(journey, manager, vpn)
                    } else {
                        // Ordinary DNS-only pause retains intent/TUN while bypassing the saved rule.
                        // Exercise the verified-ownership Guard's actual advertised UI action.
                        assertEquals(VpnOwnership.User, (LavaVpnRuntimeBridge.state.value as ProtectionState.Protected).ownership)
                        val beforePause = System.currentTimeMillis()
                        performGuardPause(journey)
                        journey.await("the Free Guard's confirmed resident pause") {
                            val paused = LavaVpnRuntimeBridge.state.value as? ProtectionState.Paused
                            val protection = journey.snapshot().getJSONObject("protection")
                            paused != null && protection.optString("action") == "resume" &&
                                protection.optBoolean("requested") && protection.optBoolean("confirmed") &&
                                protection.optLong("pauseUntil") == paused.untilEpochMillis &&
                                manager.getNetworkCapabilities(vpn)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                        }
                        val pausedState = LavaVpnRuntimeBridge.state.value as ProtectionState.Paused
                        val pausedAuthority = pauseAuthority(journey)
                        val pause = checkNotNull(pausedAuthority.pause)
                        assertEquals("The OS-backed pause must belong to the existing logical session",
                            activeAuthority.session, pause.protectionSessionId)
                        assertEquals(activeAuthority.session, pausedAuthority.session)
                        assertTrue(pausedAuthority.requested)
                        assertEquals(pausedState.untilEpochMillis, pause.deadlineMillis)
                        assertTrue(pause.deadlineMillis >= beforePause + PauseDuration.FIVE_MINUTES.durationMillis)
                        assertTrue(pause.deadlineMillis <= System.currentTimeMillis() + PauseDuration.FIVE_MINUTES.durationMillis)
                        assertEquals(admitted.configurationIdentity, pausedAuthority.identity)
                        assertEquals(admitted.configurationIdentity, confirmed(journey).configurationIdentity)
                        assertEquals("Ordinary pause retains its exact service owner", generation, LavaVpnRuntimeBridge.serviceGeneration)
                        assertEquals(vpn, installedVpn(manager, plan))

                        val pausedQuery = query(blocked, 0x41c3)
                        val pausedReply = exchange(plan, pausedQuery)
                        assertQuestionReply(pausedQuery, pausedReply)
                        assertEquals("The actual paused rule must pass through to Device DNS", 3,
                            unsigned16(pausedReply, 2) and 0x0f)
                        assertEquals(0, unsigned16(pausedReply, 6))
                        val pausedReceipt = receipt(journey, repository, blocked, blockedReceipt.id)
                        assertEquals(FilterAction.ALLOW, pausedReceipt.decision.action)
                        assertEquals(FilterDecisionReason.PAUSED_ALLOW, pausedReceipt.decision.reason)
                        assertEquals(ResolverKind.DEVICE, pausedReceipt.resolver)
                        assertTrue(pausedReceipt.transport == ResolverTransport.DEVICE_UDP ||
                            pausedReceipt.transport == ResolverTransport.DEVICE_TCP)
                        assertNull(pausedReceipt.error)
                        assertNotNull(pausedReceipt.latencyMillis)

                        // Resume immediately through the real Guard action. No elapsed expiry,
                        // forced reconnect or invented replacement generation supplies the result.
                        performGuardResume(journey)
                        journey.await("UI resume clearing the pause and restoring the resident filter") {
                            val state = LavaVpnRuntimeBridge.state.value as? ProtectionState.Protected
                            val protection = journey.snapshot().getJSONObject("protection")
                            state?.configurationIdentity == admitted.configurationIdentity &&
                                protection.optString("action") == "turnOff" && protection.isNull("pauseUntil") &&
                                protection.optBoolean("requested") && protection.optBoolean("confirmed")
                        }
                        val resumedAuthority = pauseAuthority(journey)
                        assertNull("Resume must durably remove both pause fields", resumedAuthority.pause)
                        assertTrue(resumedAuthority.requested)
                        assertEquals(activeAuthority.session, resumedAuthority.session)
                        assertEquals(admitted.configurationIdentity, resumedAuthority.identity)
                        assertEquals(generation, LavaVpnRuntimeBridge.serviceGeneration)
                        assertEquals(vpn, installedVpn(manager, plan))
                        val resumedQuery = query(blocked, 0x41d4)
                        assertArrayEquals(DnsMessage.blockedResponse(resumedQuery), exchange(plan, resumedQuery))
                        val resumedReceipt = receipt(journey, repository, blocked, pausedReceipt.id)
                        assertEquals(FilterAction.BLOCK, resumedReceipt.decision.action)
                        assertEquals(FilterDecisionReason.BLOCKLIST, resumedReceipt.decision.reason)
                        assertNull(resumedReceipt.resolver)
                        assertNull(resumedReceipt.transport)
                        assertNull(resumedReceipt.error)
                        assertNull(resumedReceipt.latencyMillis)
                        val resumedCounts = repository.summary(range)
                        assertTrue(resumedCounts.allowedCount >= counts.allowedCount + 1)
                        assertTrue(resumedCounts.blockedCount >= counts.blockedCount + 1)
                        assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))

                        performGuardStop(journey)
                        awaitStopped(journey, manager, vpn)
                        journey.waitProtectionOff()
                    }
                }
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                var cleanupFailure: Throwable? = null
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        val first = cleanupFailure
                        if (first == null) cleanupFailure = failure else first.addSuppressed(failure)
                    }
                }
                cleanup { stopOwnedServiceIfNeeded(journey) }
                cleanup { removeRule(journey, profile.id, blocked) }
                cleanup {
                    val current = confirmed(journey).configuration
                    if (current.activeProfileId != original.activeProfileId) {
                        assertEquals("Cleanup may undo only this journey's Core activation", profile.id, current.activeProfileId)
                        switchActiveProfile(journey, original.activeProfileId)
                    }
                }
                cleanup {
                    assertEquals("Cleanup must restore the complete original filter/resolver configuration", original,
                        confirmed(journey).configuration)
                }
                cleanupFailure?.let { failure ->
                    val primary = primaryFailure
                    if (primary != null) primary.addSuppressed(failure) else throw failure
                }
            }
        }
    }

    private fun confirmed(journey: ReactEmulatorJourney): ConfirmedFilterState = runBlocking {
        val read = CredentialFilterConfigurationStore.credentialProtected(
            journey.context, FeatureLimits.FREE.maxFilterRules,
        ).readAuthoritative()
        assertTrue("The actual compiled configuration must reopen from storage: $read", read is CredentialFilterConfigurationRead.Available)
        (read as CredentialFilterConfigurationRead.Available).state
    }

    private fun switchActiveProfile(journey: ReactEmulatorJourney, id: String) {
        val host = journey.requireActivity()
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        journey.await("the original current native viewport ready for a saved-filter switch") {
            var ready = false
            journey.instrumentation.runOnMainSync {
                ready = journey.requireActivity() === host &&
                    journey.application.reactHost.currentReactContext?.currentActivity === host &&
                    host.nativeHostAlive && host.foreground && !host.isFinishing && !host.isDestroyed &&
                    host.hasWindowFocus() && product.state.value.graphGeneration == generation &&
                    host.isPresentationReady(generation)
            }
            ready
        }
        // completed() carries the actual native host/generation and normal switch
        // authorization. No DataStore/profile rewrite supplies this fixture's state.
        journey.completed("filter.switch", JSONObject().put("id", id).put("nativeHostID", host.nativeHostID))
        journey.await("the exact saved-filter switch durably published", 60_000) {
            product.state.value.graphGeneration == generation &&
                confirmed(journey).configuration.activeProfileId == id
        }
    }

    private fun addRule(journey: ReactEmulatorJourney, id: String, domain: String) {
        journey.completed("filter.open", JSONObject().put("id", id))
        journey.completed("filter.edit", JSONObject().put("id", id))
        journey.completed("filter.domain", JSONObject().put("id", id).put("domain", domain).put("decision", "blocked"))
        // #856 P2R: every changed draft, strengthening included, stops at the visible Review
        // boundary before the durable mutation; the confirmed apply persists the exact change.
        assertEquals("A changed strengthening rule stops at the visible Review boundary", "review",
            journey.completed("filter.save", JSONObject().put("id", id)).getString("value"))
        val review = journey.completed("filter.review", JSONObject().put("id", id)).getString("value")
        journey.completed("filter.apply", JSONObject().put("id", id).put("review", review))
        journey.await("verified strengthening rule publication", 60_000) {
            domain in checkNotNull(confirmed(journey).configuration.profile(id)).manualRules.blockedDomains
        }
    }

    private fun removeRule(journey: ReactEmulatorJourney, id: String, domain: String) {
        if (domain !in checkNotNull(confirmed(journey).configuration.profile(id)).manualRules.blockedDomains) return
        journey.completed("filter.open", JSONObject().put("id", id))
        journey.completed("filter.edit", JSONObject().put("id", id))
        journey.completed("filter.domain", JSONObject().put("id", id).put("domain", domain).put("decision", "blocked").put("remove", true))
        assertEquals("review", journey.completed("filter.save", JSONObject().put("id", id)).getString("value"))
        val review = journey.completed("filter.review", JSONObject().put("id", id)).getString("value")
        journey.completed("filter.apply", JSONObject().put("id", id).put("review", review))
        journey.await("exact synthetic rule cleanup", 60_000) {
            domain !in checkNotNull(confirmed(journey).configuration.profile(id)).manualRules.blockedDomains
        }
    }

    private fun stopOwnedServiceIfNeeded(journey: ReactEmulatorJourney) {
        val protection = journey.snapshot().getJSONObject("protection")
        if (Build.VERSION.SDK_INT < 29) {
            val manager = journey.context.getSystemService(ConnectivityManager::class.java)
            if (LavaVpnRuntimeBridge.serviceGeneration == null) {
                journey.await("API 28 cleanup observing the released Android VPN network") {
                    LavaVpnRuntimeBridge.serviceGeneration == null &&
                        installedVpn(manager, ChainedRoutePlan.dnsOnly()) == null &&
                        !journey.snapshot().getJSONObject("protection").optBoolean("confirmed")
                }
                return
            }
            // The source forbids an unverified-ownership app Stop/Pause. Cleanup must use
            // the same real OS disconnect UI, even when the primary assertion failed.
            val vpn = checkNotNull(installedVpn(manager, ChainedRoutePlan.dnsOnly()))
            disconnectViaVpnSettings(journey)
            awaitOsRevoked(journey, manager, vpn)
            return
        }
        if (LavaVpnRuntimeBridge.serviceGeneration == null && !protection.optBoolean("requested")) return
        // Cleanup uses the same production lifecycle owner; it is not counted as UI evidence.
        if (protection.optString("action") == "resume") {
            journey.completed("protection.toggle", JSONObject().put("action", "resume"))
            journey.await("cleanup pause removal") { LavaVpnRuntimeBridge.state.value is ProtectionState.Protected }
        }
        journey.completed("protection.toggle", JSONObject().put("action", "turnOff"))
        journey.await("cleanup service retirement") {
            LavaVpnRuntimeBridge.serviceGeneration == null && LavaVpnRuntimeBridge.state.value == ProtectionState.Off &&
                !journey.snapshot().getJSONObject("protection").optBoolean("requested")
        }
    }

    private fun assertUnverifiedOwnership(journey: ReactEmulatorJourney, identity: ConfigurationIdentity) {
        val runtime = LavaVpnRuntimeBridge.state.value as ProtectionState.Protected
        assertEquals(VpnOwnership.UnknownApi28, runtime.ownership)
        assertEquals(identity, runtime.configurationIdentity)
        val protection = journey.snapshot().getJSONObject("protection")
        assertEquals("settings", protection.getString("action"))
        assertEquals("ownership_unverified_api_28", protection.getString("message"))
        assertTrue(protection.getBoolean("requested"))
        assertTrue(protection.getBoolean("confirmed"))
        assertFalse(protection.getBoolean("canPause"))
        assertTrue(protection.isNull("pauseUntil"))
        assertFalse("The conservative Guard must advertise no hidden pause action",
            journey.accessibilityNodes().any { node -> node.isVisibleToUser && node.actionList.any {
                it.label?.toString()?.equals("Pause for 5 minutes", ignoreCase = true) == true
            } })
    }

    private fun openVpnSettings(journey: ReactEmulatorJourney) {
        performGuardPrimaryAction(journey, "Open system settings",
            GuardPrimaryAction.OpenSettings(SettingsRoute.VpnSettings))
        awaitSettings(journey, "the genuine focused Android VPN Settings screen") {
            val nodes = settingsNodes(journey)
            nodes.any { settingsLabel(it, "VPN") } && nodes.any { settingsLabel(it, appLabel(journey)) }
        }
    }

    private fun returnFromVpnSettings(journey: ReactEmulatorJourney) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        val activity = journey.requireActivity()
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        fun assertReturnOwner() {
            assertTrue("Settings return must retain its original Activity", journey.requireActivity() === activity)
            assertFalse(activity.isFinishing || activity.isDestroyed)
            assertTrue("Settings return must retain its original product", journey.application.foregroundProduct === product)
            assertEquals(generation, product.state.value.graphGeneration)
        }
        fun remaining() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        var settingsRoot: AccessibilityNodeInfo? = null
        awaitSettings(journey, "the connected focused VPN Settings window before returning", remaining()) {
            assertReturnOwner()
            // Disconnect dismisses a real Settings dialog asynchronously. An absent tree during
            // that handoff is not an acquired return target; wait for the actual VPN window.
            val focused = journey.instrumentation.uiAutomation.windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            }.singleOrNull() ?: return@awaitSettings false
            val root = focused.root ?: return@awaitSettings false
            if (!root.refresh() || root.packageName?.toString() != "com.android.settings") return@awaitSettings false
            val nodes = settingsNodes(journey).filter { it.windowId == focused.id }
            if (nodes.none { settingsLabel(it, "VPN") } || nodes.any { settingsLabel(it, "Disconnect") })
                return@awaitSettings false
            settingsRoot = root
            true
        }
        assertReturnOwner()
        val root = checkNotNull(settingsRoot)
        assertTrue("The exact acquired Settings root must remain connected", root.refresh())
        val focused = journey.instrumentation.uiAutomation.windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        }.single()
        assertEquals("Only the exact genuine focused Settings window may supply Back", root.windowId, focused.id)
        assertEquals(root, focused.root?.takeIf { it.refresh() && it.packageName?.toString() == "com.android.settings" })
        println("LAVA_UI_GUARD_SETTINGS_RETURN window=${root.windowId} source=${root.hashCode()} input=one-scoped-Back")
        journey.back()
        journey.await("the packaged Guard returning from Android VPN Settings", remaining()) {
            assertReturnOwner()
            settingsNodes(journey).isEmpty() && journey.snapshot().optBoolean("foreground") &&
                journey.visible("Guard") && journey.visible("Settings")
        }
    }

    private fun disconnectViaVpnSettings(journey: ReactEmulatorJourney) {
        if (settingsNodes(journey).isEmpty()) openVpnSettings(journey)
        val label = appLabel(journey)
        if (settingsNodes(journey).none { settingsLabel(it, "Disconnect") }) {
            // The connected application row opens Android's disconnect confirmation;
            // never select a gear, Forget, Always-on switch, or another VPN application.
            clickSettingsLabel(journey, label)
        }
        awaitSettings(journey, "Android's disconnect confirmation for the actual Lava application") {
            val nodes = settingsNodes(journey)
            nodes.any { settingsLabel(it, label) } && nodes.any { settingsLabel(it, "Disconnect") }
        }
        clickSettingsLabel(journey, "Disconnect")
        awaitSettings(journey, "Android dismissing the consumed disconnect confirmation") {
            settingsNodes(journey).none { settingsLabel(it, "Disconnect") } &&
                LavaVpnRuntimeBridge.serviceGeneration == null
        }
        returnFromVpnSettings(journey)
    }

    private fun awaitOsRevoked(journey: ReactEmulatorJourney, manager: ConnectivityManager, vpn: Network) {
        journey.await("OS disconnect retiring the actual API 28 TUN and service", 30_000) {
            LavaVpnRuntimeBridge.serviceGeneration == null && manager.getNetworkCapabilities(vpn) == null &&
                LavaVpnRuntimeBridge.state.value == ProtectionState.Failed(ProtectionFailure.Revoked) &&
                !journey.snapshot().getJSONObject("protection").optBoolean("confirmed")
        }
        // Platform observation clears confirmation, never the user's explicit intent. This
        // is the source's revoked outcome, not a fabricated successful app Stop on API 28.
        val authority = pauseAuthority(journey)
        assertTrue(authority.requested)
        assertNull(authority.pause)
        assertEquals("turnOn", journey.snapshot().getJSONObject("protection").getString("action"))
    }

    private fun appLabel(journey: ReactEmulatorJourney): String =
        journey.context.applicationInfo.loadLabel(journey.context.packageManager).toString()

    private fun settingsNodes(journey: ReactEmulatorJourney): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.refresh() || node.packageName?.toString() != "com.android.settings") return
            if (node.isVisibleToUser) result.add(node)
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
        }
        journey.instrumentation.uiAutomation.windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused)
        }.mapNotNull { it.root }.filter {
            it.packageName?.toString() == "com.android.settings"
        }.forEach(::visit)
        return result
    }

    private fun settingsLabel(node: AccessibilityNodeInfo, label: String): Boolean =
        node.isVisibleToUser && listOf(node.text, node.contentDescription).any {
            it?.toString()?.equals(label, ignoreCase = true) == true
        }

    private fun clickSettingsLabel(journey: ReactEmulatorJourney, label: String) {
        awaitSettings(journey, "the fresh enabled Android Settings action '$label'") {
            val named = settingsNodes(journey).firstOrNull { settingsLabel(it, label) } ?: return@awaitSettings false
            if (!named.refresh() || !settingsLabel(named, label)) return@awaitSettings false
            var target = named
            while (!target.isClickable && target.parent != null) target = target.parent
            if (!target.refresh() || !target.isVisibleToUser || !target.isEnabled || !target.isClickable ||
                target.packageName?.toString() != "com.android.settings" || target.windowId != named.windowId) {
                return@awaitSettings false
            }
            assertTrue("Android Settings must accept its actual '$label' UI action",
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            true // An accepted action is submitted once and never retried.
        }
    }

    private fun awaitSettings(journey: ReactEmulatorJourney, message: String, timeoutMillis: Long = 20_000L, condition: () -> Boolean) {
        try {
            journey.await(message, timeoutMillis, condition)
        } catch (failure: AssertionError) {
            val nodes = settingsNodes(journey)
            val windows = journey.instrumentation.uiAutomation.windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }.take(6).joinToString { window ->
                val root = window.root
                "id=${window.id}/active=${window.isActive}/focused=${window.isFocused}/" +
                    "connected=${root?.refresh() == true}/package=${root?.packageName}"
            }
            // Limit diagnostics to public route/action presence; no arbitrary Settings text.
            throw AssertionError("${failure.message}. Focused Settings: nodes=${nodes.size}, " +
                "vpn=${nodes.any { settingsLabel(it, "VPN") }}, " +
                "lava=${nodes.any { settingsLabel(it, appLabel(journey)) }}, " +
                "disconnect=${nodes.any { settingsLabel(it, "Disconnect") }}, windows=[$windows]", failure)
        }
    }

    private fun awaitStopped(journey: ReactEmulatorJourney, manager: ConnectivityManager, vpn: Network) {
        journey.await("Guard stop retiring the actual TUN and service") {
            LavaVpnRuntimeBridge.serviceGeneration == null && LavaVpnRuntimeBridge.state.value == ProtectionState.Off &&
                manager.getNetworkCapabilities(vpn) == null &&
                !journey.snapshot().getJSONObject("protection").optBoolean("requested") &&
                !journey.snapshot().getJSONObject("protection").optBoolean("confirmed")
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVpn(manager: ConnectivityManager, plan: ChainedRoutePlan): Network? =
        manager.allNetworks.singleOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                manager.getLinkProperties(network)?.dnsServers?.any { it.hostAddress == plan.dnsServerAddress } == true
        }

    private fun receipt(journey: ReactEmulatorJourney, repository: ActivityRepository, domain: String, afterId: Long? = null): DnsDecisionEvent {
        var received: DnsDecisionEvent? = null
        // This is the receipt-await-entry witness, after the real DNS response. There is no
        // pre-query telemetry-drop baseline, so diagnostics deliberately report no delta.
        val receiptServiceGeneration = LavaVpnRuntimeBridge.serviceGeneration
        try {
            journey.await("production TUN-delivery receipt for $domain") {
                received = repository.history(searchText = domain).singleOrNull {
                    it.normalizedDomain == domain && (afterId == null || it.id > afterId)
                }
                received != null
            }
        } catch (failure: AssertionError) {
            try {
                // One bounded, ordinary history window on failure; not a durable total scan.
                val rows = repository.history(searchText = domain)
                val matching = rows.count { it.normalizedDomain == domain }
                val eligible = rows.count {
                    it.normalizedDomain == domain && (afterId == null || it.id > afterId)
                }
                val currentGeneration = LavaVpnRuntimeBridge.serviceGeneration
                val sameGeneration = receiptServiceGeneration != null &&
                    currentGeneration == receiptServiceGeneration
                val drops = if (sameGeneration) {
                    LavaVpnRuntimeBridge.health.value.droppedPrivateTelemetryCount
                } else null
                val generationStayedSame = sameGeneration &&
                    LavaVpnRuntimeBridge.serviceGeneration == receiptServiceGeneration
                val diagnostic = "LAVA_GUARD_RECEIPT_FAILURE " +
                    "returnedWindowMatchingCount=$matching returnedWindowEligibleCount=$eligible " +
                    "receiptAwaitGenerationPresent=${receiptServiceGeneration != null} " +
                    "currentGenerationPresent=${currentGeneration != null} " +
                    "receiptAwaitGenerationMatchesCurrent=$generationStayedSame " +
                    "scopedCurrentDropCount=${if (generationStayedSame) drops else null}"
                failure.addSuppressed(AssertionError(diagnostic).apply { stackTrace = emptyArray() })
            } catch (_: Throwable) {
                // Diagnostics must never replace the original assertion, even if storage refuses.
                runCatching {
                    failure.addSuppressed(AssertionError(
                        "LAVA_GUARD_RECEIPT_FAILURE diagnosticUnavailable=true",
                    ).apply { stackTrace = emptyArray() })
                }
            }
            throw failure
        }
        return checkNotNull(received)
    }

    private fun performGuardResume(journey: ReactEmulatorJourney) =
        performGuardPrimaryAction(journey, "Resume protection", GuardPrimaryAction.Resume)

    private fun performGuardStop(journey: ReactEmulatorJourney) =
        performGuardPrimaryAction(journey, "Turn off protection", GuardPrimaryAction.TurnOff)

    private fun performGuardPrimaryAction(
        journey: ReactEmulatorJourney,
        actionLabel: String,
        expectedAction: GuardPrimaryAction,
    ) {
        val host = journey.requireActivity()
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        data class Viewport(val source: AccessibilityNodeInfo, val scrollY: Int)
        var captured: ScrollView? = null
        var pendingScrollY: Int? = null

        fun views(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
        fun viewport(nodes: List<AccessibilityNodeInfo>): Viewport? {
            var result: Viewport? = null
            journey.instrumentation.runOnMainSync {
                assertTrue("The Guard primary action must retain its original native host and graph",
                    journey.requireActivity() === host &&
                        journey.application.reactHost.currentReactContext?.currentActivity === host &&
                        journey.application.nativeVisitHosts.current()?.owner === host &&
                        host.nativeHostAlive && !host.isFinishing && !host.isDestroyed &&
                        product.state.value.graphGeneration == generation)
                val security = product.securityStateForSnapshot(generation) ?: return@runOnMainSync
                if (!host.foreground || !host.hasWindowFocus() || !host.isPresentationReady(generation) ||
                    !security.isAuthorized(ProtectedSurface.APP_UNLOCK) ||
                    product.state.value.protection?.guard?.primaryAction != expectedAction) return@runOnMainSync
                val candidates = views(host.window.decorView).filterIsInstance<ScrollView>().filter { scroll ->
                    scroll.getTag(com.facebook.react.R.id.react_test_id) == "screen.scroll" &&
                        scroll.isAttachedToWindow && scroll.isShown &&
                        views(scroll).any { it.getTag(com.facebook.react.R.id.react_test_id) == "guard.pause-menu" } &&
                        nodes.any { it.isVisibleToUser && it == scroll.createAccessibilityNodeInfo() }
                }.toList()
                assertTrue("The Guard primary action must identify at most one actual viewport", candidates.size <= 1)
                val scroll = candidates.singleOrNull() ?: return@runOnMainSync
                captured?.let { assertTrue("The captured Guard viewport must not be replaced", scroll === it) }
                captured = scroll
                result = Viewport(scroll.createAccessibilityNodeInfo(), scroll.scrollY)
            }
            return result
        }
        fun belongsTo(node: AccessibilityNodeInfo, source: AccessibilityNodeInfo): Boolean {
            var ancestor: AccessibilityNodeInfo? = node
            repeat(32) {
                if (ancestor == source) return true
                ancestor = ancestor?.parent ?: return false
            }
            return false
        }
        fun actionLabelMatches(node: AccessibilityNodeInfo): Boolean = node.isVisibleToUser &&
            listOf(node.text, node.contentDescription).any {
                val label = it?.toString().orEmpty()
                label.equals(actionLabel, ignoreCase = true) ||
                    label.startsWith("$actionLabel,", ignoreCase = true) ||
                    label.startsWith("$actionLabel\n", ignoreCase = true)
            }

        // Pause/resume keeps the same scroll owner. Its hero is before the summary
        // cards, so a forward-only search cannot reveal a hero above the viewport.
        // Readiness, measured backward reveal and the one real click share the
        // original click's twenty-second budget; no native command supplies the action.
        journey.await("the shipping Guard's actual $actionLabel control", 20_000) {
            val nodes = journey.accessibilityNodes()
            val current = viewport(nodes) ?: return@await false
            val candidates = nodes.filter { it.packageName?.toString() == journey.context.packageName &&
                belongsTo(it, current.source) && actionLabelMatches(it) && it.refresh() && actionLabelMatches(it) }.mapNotNull { named ->
                var target = named
                while (!target.isClickable && target.parent != null && target != current.source) target = target.parent
                target.takeIf { it != current.source && belongsTo(it, current.source) && it.refresh() &&
                    it.isVisibleToUser && it.isEnabled && it.isClickable }?.let { named to it }
            }.distinctBy { it.second }
            assertTrue("The visible Guard must have at most one real primary control", candidates.size <= 1)
            val control = candidates.singleOrNull()
            if (control != null) {
                val (named, target) = control
                val admitted = viewport(journey.accessibilityNodes()) ?: return@await false
                assertEquals("The action must remain in the captured native Guard viewport", current.source, admitted.source)
                assertTrue("The real primary control must remain fresh, visible and enabled before its single click",
                    named.refresh() && actionLabelMatches(named) && belongsTo(named, admitted.source) &&
                        target.refresh() && target.isVisibleToUser && target.isEnabled && target.isClickable &&
                        belongsTo(target, admitted.source))
                assertTrue("The shipping Guard refused its one real $actionLabel click",
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                true
            } else {
                val previousY = pendingScrollY
                if (previousY != null) {
                    if (current.scrollY < previousY) pendingScrollY = null
                } else if (current.scrollY > 0 && current.source.refresh() &&
                    current.source.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
                    assertTrue("The captured shipping Guard refused its one backward reveal step",
                        current.source.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                    pendingScrollY = current.scrollY
                }
                false
            }
        }
    }

    private fun performGuardPause(journey: ReactEmulatorJourney) {
        val host = journey.requireActivity()
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        fun remaining(): Long = (deadline - SystemClock.elapsedRealtime()).also {
            assertTrue("Guard reveal, hold, dismissal and pause must share the original twenty-second budget", it > 0L)
        }
        data class Viewport(val source: AccessibilityNodeInfo, val scrollY: Int)
        var captured: ScrollView? = null
        var pendingScrollY: Int? = null

        fun views(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
        fun viewport(nodes: List<AccessibilityNodeInfo>): Viewport? {
            var result: Viewport? = null
            journey.instrumentation.runOnMainSync {
                assertTrue("The Guard pause must retain its original native host and graph",
                    journey.requireActivity() === host &&
                        journey.application.reactHost.currentReactContext?.currentActivity === host &&
                        journey.application.nativeVisitHosts.current()?.owner === host &&
                        host.nativeHostAlive && !host.isFinishing && !host.isDestroyed &&
                        product.state.value.graphGeneration == generation)
                val security = product.securityStateForSnapshot(generation) ?: return@runOnMainSync
                val protection = LavaVpnRuntimeBridge.state.value as? ProtectionState.Protected
                if (!host.foreground || !host.hasWindowFocus() || !host.isPresentationReady(generation) ||
                    !security.isAuthorized(ProtectedSurface.APP_UNLOCK) ||
                    protection?.ownership != VpnOwnership.User) return@runOnMainSync
                val candidates = views(host.window.decorView).filterIsInstance<ScrollView>().filter { scroll ->
                    scroll.getTag(com.facebook.react.R.id.react_test_id) == "screen.scroll" &&
                        scroll.isAttachedToWindow && scroll.isShown &&
                        views(scroll).any { it.getTag(com.facebook.react.R.id.react_test_id) == "guard.pause-menu" } &&
                        nodes.any { it.isVisibleToUser && it == scroll.createAccessibilityNodeInfo() }
                }.toList()
                assertTrue("The pause action must identify at most one actual Guard viewport", candidates.size <= 1)
                val scroll = candidates.singleOrNull() ?: return@runOnMainSync
                captured?.let { assertTrue("The captured Guard viewport must not be replaced", scroll === it) }
                captured = scroll
                result = Viewport(scroll.createAccessibilityNodeInfo(), scroll.scrollY)
            }
            return result
        }
        fun belongsTo(node: AccessibilityNodeInfo, source: AccessibilityNodeInfo): Boolean {
            var ancestor: AccessibilityNodeInfo? = node
            repeat(32) {
                if (ancestor == source) return true
                ancestor = ancestor?.parent ?: return false
            }
            return false
        }
        fun pauseAction(node: AccessibilityNodeInfo): Int? {
            if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled ||
                node.contentDescription?.toString()?.startsWith("Turn off protection", ignoreCase = true) != true)
                return null
            return node.actionList.firstOrNull {
                it.label?.toString()?.equals("Pause for 5 minutes", ignoreCase = true) == true
            }?.id
        }

        fun holdAndCancelPauseMenu(node: AccessibilityNodeInfo, source: AccessibilityNodeInfo) {
            assertTrue("Only verified user ownership can expose the physical pause menu", Build.VERSION.SDK_INT >= 29)
            val manager = journey.context.getSystemService(ConnectivityManager::class.java)
            val plan = ChainedRoutePlan.dnsOnly()
            val service = checkNotNull(LavaVpnRuntimeBridge.serviceGeneration)
            val vpn = checkNotNull(installedVpn(manager, plan))
            val configuration = confirmed(journey).configuration
            val authority = pauseAuthority(journey)
            val protectedState = LavaVpnRuntimeBridge.state.value as ProtectionState.Protected
            assertEquals(VpnOwnership.User, protectedState.ownership)
            assertNull("The held menu must start without any durable pause", authority.pause)
            var button: View? = null
            var point: Pair<Float, Float>? = null
            var originalWindow = -1
            val bounds = Rect()

            fun assertOwner() {
                journey.instrumentation.runOnMainSync {
                    assertTrue("The held Guard control must retain its exact live native owner and graph",
                        journey.requireActivity() === host && journey.application.foregroundProduct === product &&
                            journey.application.reactHost.currentReactContext?.currentActivity === host &&
                            journey.application.nativeVisitHosts.current()?.owner === host &&
                            host.nativeHostAlive && host.foreground && !host.isFinishing && !host.isDestroyed &&
                            product.state.value.graphGeneration == generation)
                    assertTrue("The held Guard menu must retain actual APP_UNLOCK authority",
                        product.securityStateForSnapshot(generation)?.isAuthorized(ProtectedSurface.APP_UNLOCK) == true)
                    button?.let { current ->
                        assertTrue("The original native action button must remain attached to its captured Guard viewport",
                            current.isAttachedToWindow && current.isShown && current.isEnabled &&
                                views(checkNotNull(captured)).any { it === current } &&
                                current.createAccessibilityNodeInfo() == node)
                    }
                }
            }
            fun assertProtectionUnchanged() {
                assertOwner()
                assertEquals("Holding or dismissing the menu must not toggle the actual protection service", protectedState,
                    LavaVpnRuntimeBridge.state.value)
                assertEquals(service, LavaVpnRuntimeBridge.serviceGeneration)
                assertEquals(vpn, installedVpn(manager, plan))
                assertEquals(configuration, confirmed(journey).configuration)
                assertEquals("Menu inspection must preserve durable requested/session/pause/configuration custody",
                    authority, pauseAuthority(journey))
                assertEquals(GuardPrimaryAction.TurnOff, product.state.value.protection?.guard?.primaryAction)
                assertTrue(product.state.value.protection?.guard?.canPause == true)
                assertFalse("The hold must not start a native protection command",
                    product.state.value.protection?.commandInFlight == true)
            }
            assertOwner()
            journey.instrumentation.runOnMainSync {
                assertTrue(host.hasWindowFocus() && host.isPresentationReady(generation))
                assertTrue(node.refresh() && node.isVisibleToUser && node.isEnabled &&
                    node.className?.toString() == "android.widget.Button" && belongsTo(node, source))
                val wrapper = views(checkNotNull(captured)).single {
                    it.getTag(com.facebook.react.R.id.react_test_id) == "guard.pause-menu"
                }
                button = views(wrapper).single { it !== wrapper && it.createAccessibilityNodeInfo() == node }
                val current = checkNotNull(button)
                assertTrue(current.isAttachedToWindow && current.isShown && current.isEnabled &&
                    current.width > 0 && current.height > 0)
                node.getBoundsInScreen(bounds)
                val location = IntArray(2).also(current::getLocationOnScreen)
                assertTrue("The actual visible AX button bounds must lie inside its measured native view",
                    !bounds.isEmpty && Rect(location[0], location[1], location[0] + current.width,
                        location[1] + current.height).contains(bounds))
                originalWindow = host.window.decorView.createAccessibilityNodeInfo().windowId
                assertTrue(originalWindow >= 0 && node.windowId == originalWindow && source.windowId == originalWindow)
                point = bounds.exactCenterX() to bounds.exactCenterY()
            }
            assertProtectionUnchanged()
            fun touch(downTime: Long, action: Int): Boolean {
                val position = checkNotNull(point)
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    position.first, position.second, 0).apply { this.source = InputDevice.SOURCE_TOUCHSCREEN }
                return try { journey.instrumentation.uiAutomation.injectInputEvent(event, true) }
                finally { event.recycle() }
            }
            fun menuCancel(): AccessibilityNodeInfo? {
                val windows = journey.instrumentation.uiAutomation.windows.filter { window ->
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused &&
                        window.id != originalWindow && window.root?.let { root ->
                            root.refresh() && root.packageName?.toString() == journey.context.packageName
                        } == true
                }
                assertTrue("The one held Guard action must open at most one focused native application menu", windows.size <= 1)
                val window = windows.singleOrNull() ?: return null
                val buttons = journey.accessibilityNodes().filter { current ->
                    current.windowId == window.id && current.packageName?.toString() == journey.context.packageName &&
                        current.className?.toString() == "android.widget.Button" && current.refresh() &&
                        current.isVisibleToUser && current.isEnabled && current.isClickable
                }
                fun named(label: String) = buttons.filter { current ->
                    listOf(current.text, current.contentDescription).any { it?.toString()?.equals(label, ignoreCase = true) == true }
                }
                if (named("Pause for 5 minutes").size != 1 || named("More options").size != 1) return null
                val cancel = named("Cancel")
                assertTrue("The actual pause menu must expose at most one Cancel button", cancel.size <= 1)
                return cancel.singleOrNull()
            }
            val downTime = SystemClock.uptimeMillis()
            var down = false
            var cancel: AccessibilityNodeInfo? = null
            try {
                assertOwner()
                journey.instrumentation.runOnMainSync {
                    assertTrue(node.refresh())
                    val fresh = Rect().also(node::getBoundsInScreen)
                    assertTrue("Only the fresh measured original button may own the single physical DOWN",
                        host.hasWindowFocus() && host.isPresentationReady(generation) &&
                            node.isVisibleToUser && node.isEnabled && fresh == bounds)
                }
                down = touch(downTime, MotionEvent.ACTION_DOWN)
                assertTrue("The actual protection button must accept one genuine touchscreen DOWN", down)
                journey.await("the real pause menu from the single held protection-button contact", remaining()) {
                    assertProtectionUnchanged()
                    cancel = menuCancel()
                    cancel != null
                }
                assertTrue("The same held protection contact must accept one touchscreen UP", touch(downTime, MotionEvent.ACTION_UP))
                down = false
                assertProtectionUnchanged()
            } finally {
                if (down) touch(downTime, MotionEvent.ACTION_CANCEL)
            }
            val dismiss = checkNotNull(cancel)
            assertOwner()
            assertEquals("The actual menu must survive UP without selecting or toggling anything", dismiss, menuCancel())
            assertTrue("Only the captured native pause-menu Cancel may dismiss the held menu",
                dismiss.refresh() && dismiss.isVisibleToUser && dismiss.isEnabled &&
                    dismiss.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            journey.await("the original Guard regaining focus and Ready after menu cancellation", remaining()) {
                assertProtectionUnchanged()
                val returned = viewport(journey.accessibilityNodes()) ?: return@await false
                assertEquals(source, returned.source)
                journey.instrumentation.uiAutomation.windows.none { it.id == dismiss.windowId } &&
                    node.refresh() && belongsTo(node, returned.source) && pauseAction(node) != null
            }
            assertProtectionUnchanged()
        }

        // Retain the successful physical contact across later Ready/focus polling.
        // A transient viewport absence must never replay the hold or select a replacement.
        var heldControl: Triple<AccessibilityNodeInfo, Int, AccessibilityNodeInfo>? = null

        // The real hero/action precedes Today and Now filtering. DNS receipts can
        // finish while that hero is above the viewport; forward-only searches
        // then move farther away. Reveal only this measured Guard's earlier
        // content, keeping Ready and one custom action in the original budget.
        journey.await("the shipping Free Guard's five-minute pause control", remaining()) {
            val nodes = journey.accessibilityNodes()
            val current = viewport(nodes) ?: return@await false
            val candidates = nodes.filter { it.packageName?.toString() == journey.context.packageName &&
                belongsTo(it, current.source) }.mapNotNull { node -> pauseAction(node)?.let { node to it } }
                .distinctBy { it.first }
            assertTrue("The visible Guard must have at most one real five-minute pause control", candidates.size <= 1)
            val control = heldControl?.let { it.first to it.second } ?: candidates.singleOrNull()
            if (control != null) {
                val (node, action) = control
                val admitted = viewport(journey.accessibilityNodes()) ?: return@await false
                assertEquals("Pause must remain in the captured native Guard viewport", current.source, admitted.source)
                assertTrue("The pause action must remain fresh, visible and enabled before its single invocation",
                    belongsTo(node, admitted.source) && pauseAction(node) == action)
                val held = heldControl
                if (held == null) {
                    holdAndCancelPauseMenu(node, admitted.source)
                    heldControl = Triple(node, action, admitted.source)
                } else {
                    assertEquals("The completed physical hold must retain its original pause viewport",
                        held.third, admitted.source)
                }
                val returned = viewport(journey.accessibilityNodes()) ?: return@await false
                assertEquals("The original pause action must retain its viewport after the physical menu is cancelled",
                    admitted.source, returned.source)
                assertTrue(node.refresh() && belongsTo(node, returned.source) && pauseAction(node) == action)
                assertTrue("The real Guard accessibility control must accept its advertised pause action",
                    node.performAction(action))
                true
            } else {
                val previousY = pendingScrollY
                if (previousY != null) {
                    if (current.scrollY < previousY) pendingScrollY = null
                } else if (current.scrollY > 0 && current.source.refresh() &&
                    current.source.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
                    assertTrue("The captured shipping Guard refused its one backward pause reveal step",
                        current.source.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                    pendingScrollY = current.scrollY
                }
                false
            }
        }
    }

    private data class PauseAuthority(
        val requested: Boolean,
        val session: Long,
        val pause: PauseState?,
        val identity: ConfigurationIdentity,
    )

    private fun pauseAuthority(journey: ReactEmulatorJourney): PauseAuthority {
        // The graph uses its ordinary credential-protected application context. Read the actual
        // published file with DataStore's public serializer and the production's strict codec,
        // without opening a second DataStore, writing a mirror or trusting the UI owner cache.
        val context = journey.context.applicationContext
        assertFalse("Pause authority must be read from credential storage", context.isDeviceProtectedStorage)
        val file = context.preferencesDataStoreFile(
            CredentialConfigurationRepository.CREDENTIAL_STORE_NAME,
        )
        val preferences = runBlocking { file.inputStream().use { PreferencesFileSerializer.readFrom(it) } }
        // requireWritable only validates/decodes: it rejects mistyped fields, an incomplete
        // deadline/session pair or a mismatched session, and performs no storage mutation.
        val authoritative = CredentialConfigurationCodec.requireWritable(
            preferences, CatalogRepository(context.assets).loadBundled(),
        )
        return PauseAuthority(
            authoritative.protectionIntent, authoritative.protectionSessionId, authoritative.pause,
            checkNotNull(authoritative.configurationIdentity),
        )
    }

    private fun exchange(plan: ChainedRoutePlan, query: ByteArray): ByteArray = DatagramSocket().use { socket ->
        // Deliberately neither protected nor bound to an ordinary Network: Android routes this
        // app's real packet into the installed VPN, rather than a test calling DnsProxy directly.
        val dns = InetAddress.getByName(plan.dnsServerAddress)
        socket.soTimeout = 20_000
        socket.connect(dns, 53)
        assertEquals("Android must select the real TUN client address", plan.tunnelAddress, socket.localAddress.hostAddress)
        socket.send(DatagramPacket(query, query.size))
        val incoming = DatagramPacket(ByteArray(DnsMessage.MAX_MESSAGE_BYTES), DnsMessage.MAX_MESSAGE_BYTES)
        socket.receive(incoming)
        assertEquals("The actual TUN DNS source peer must answer", dns, incoming.address)
        assertEquals(53, incoming.port)
        incoming.data.copyOfRange(incoming.offset, incoming.offset + incoming.length)
    }

    private fun query(domain: String, transactionId: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeShort(transactionId);out.writeShort(0x0100);out.writeShort(1)
            out.writeShort(0);out.writeShort(0);out.writeShort(0)
            domain.split('.').forEach { label ->
                val encoded = label.toByteArray(Charsets.US_ASCII)
                out.writeByte(encoded.size);out.write(encoded)
            }
            out.writeByte(0);out.writeShort(1);out.writeShort(1)
        }
    }.toByteArray().also { assertEquals(domain, DnsMessage.parseQuestion(it).normalizedDomain) }

    private fun assertQuestionReply(query: ByteArray, response: ByteArray) {
        assertTrue(response.size >= query.size)
        assertEquals(unsigned16(query, 0), unsigned16(response, 0))
        assertTrue(unsigned16(response, 2) and 0x8000 != 0)
        assertEquals("The real resolver answer must not be truncated", 0, unsigned16(response, 2) and 0x0200)
        assertEquals(1, unsigned16(response, 4))
        assertArrayEquals(DnsMessage.parseQuestion(query).questionBytes(query), response.copyOfRange(DnsMessage.HEADER_BYTES, query.size))
    }

    private fun unsigned16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
