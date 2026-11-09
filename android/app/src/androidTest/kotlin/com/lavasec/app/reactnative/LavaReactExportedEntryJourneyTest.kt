package com.lavasec.app.reactnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent fresh-data cohort, additional to the original launcher/onboarding identities. */
@RunWith(AndroidJUnit4::class)
class LavaReactExportedEntryJourneyTest {
    @Test fun shippingValidatedExternalEntriesUseSharedReactAndRetireExactNativeReceipts() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(deepLink = "lavasecurity://settings")
            assertTrue("Exported fresh entry requires freshly cleared emulator data", journey.snapshot().getBoolean("onboardingRequired"))
            val product = journey.application.foregroundProduct
            val reactHost = journey.application.reactHost
            val original = journey.requireActivity()
            journey.ensureConfigured(requireFirstRun = true, cancelFirstAttempt = true)
            revealSettingsSecurity(journey, original)
            assertSame(product, journey.application.foregroundProduct)
            assertSame(reactHost, journey.application.reactHost)
            assertTrue(product.sharedOnboardingAuthority().second?.completed == true)
            assertNull(android.net.VpnService.prepare(journey.context))
            val library = durableLibrary(journey)
            assertEquals(product.currentFilterLibrary(), library)
            val selected = library.resolverTarget.configuration.toSelection()
            assertEquals(ResolverProviderId.DEVICE_DNS, selected.primary.providerId)
            assertEquals(ResolverProviderId.QUAD9_UNFILTERED, checkNotNull(selected.fallback).providerId)
            val generation = checkNotNull(product.state.value.graphGeneration)
            val retired = mutableListOf<LavaReactActivity>()
            val targets = listOf("guard" to "Protection status", "filters" to "Switch or manage filters",
                "activity" to "Top Domains", "settings" to "Security", "settings" to "Security")
            var previous = journey.requireActivity()
            targets.forEach { (route, anchor) ->
                val old = previous
                val child = journey.openDeepLink("lavasecurity://$route")
                assertNotSame(old, child)
                assertTrue("Each validated link must create a fresh native host", old.nativeHostID != child.nativeHostID)
                assertTrue("Identical links must stage distinct one-use entry tickets",
                    old.nativeDeepLinkEntryID != child.nativeDeepLinkEntryID)
                if (route == "settings") revealSettingsSecurity(journey, child) else {
                    journey.waitLabel(anchor, prefix = route == "guard")
                    awaitAcceptedEntry(journey, child)
                }
                assertNull("Configured external entry must not replay shared setup", journey.snapshot().optJSONObject("onboardingSetup"))
                assertEquals(generation, product.state.value.graphGeneration)
                assertEquals(library, durableLibrary(journey))
                val stale = journey.command("navigation.accept", org.json.JSONObject()
                    .put("nativeHostID", old.nativeHostID).put("serial", 1).put("presentationSerial", 1))
                assertEquals("rejected", stale.getString("status"))
                assertEquals("stale-host", stale.getString("reason"))
                assertSame(child, journey.application.reactHost.currentReactContext?.currentActivity)
                journey.await("retired original host cannot change its admitted successor") {
                    old.isDestroyed && !old.nativeHostAlive && !old.foreground && child.foreground &&
                        journey.application.nativeVisitHosts.current()?.owner === child &&
                        journey.application.reactHost.currentReactContext?.currentActivity === child
                }
                assertNull("The destroyed original entry cannot retain an accepted receipt",
                    NativeDeepLinkEntryRegistry.process.acceptance(old.nativeDeepLinkEntryID, old, old.nativeHostID, generation))
                retired.add(old)
                previous = child
            }
            // Invalid material reaches the real exported validator but never creates an RN ticket.
            listOf("lavasecurity://settings?unexpected=value", "lavasecurity://activity/extra").forEach { uri ->
                val invalid = journey.instrumentation.startActivitySync(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(uri), journey.context, com.lavasec.app.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                assertTrue(invalid is com.lavasec.app.MainActivity)
                assertNull(invalid.intent?.getStringExtra(LavaReactActivity.DEEP_LINK_ENTRY_EXTRA))
                assertNotNull(NativeDeepLinkEntryRegistry.process.acceptance(previous.nativeDeepLinkEntryID, previous,
                    previous.nativeHostID, generation))
                journey.instrumentation.runOnMainSync { invalid.finish() }
                journey.await("invalid source returns to its original admitted RN child") {
                    journey.application.reactHost.currentReactContext?.currentActivity === previous && previous.foreground
                }
                assertEquals(library, durableLibrary(journey))
            }
            journey.assertNoFallback()
            // Real Back may pop React navigation, finish the root, or background its task.
            // Every replaced host must stay destroyed in each supported Android behavior.
            journey.back()
            journey.instrumentation.runOnMainSync {
                assertTrue("Back cannot revive any replaced native host",
                    retired.all { it.isDestroyed && !it.nativeHostAlive && !it.foreground })
                val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                assertFalse("No replaced Activity may resume after real Back",
                    resumed.any { candidate -> retired.any { it === candidate } })
                assertFalse("React must not borrow a replaced Activity after Back",
                    retired.any { it === journey.application.reactHost.currentReactContext?.currentActivity })
                assertFalse("The native host registry must not return a replaced owner after Back",
                    retired.any { it === journey.application.nativeVisitHosts.current()?.owner })
            }
        }
    }

    private fun revealSettingsSecurity(journey: ReactEmulatorJourney, activity: LavaReactActivity) {
        // Security follows both story columns on a compact phone. The exported
        // destination is admitted before scrolling its actual Settings viewport.
        journey.waitLabel("Your connection")
        awaitAcceptedEntry(journey, activity)
        val generation = checkNotNull(journey.application.foregroundProduct.state.value.graphGeneration)
        val current = { acceptedEntryCurrent(journey, activity, generation, settings = true) }
        assertTrue("The closed Settings destination must own this exact native receipt", current())
        journey.scrollRegionContaining("Your connection", isCurrent = current).reveal("Security")
        journey.waitLabel("Security")
        assertTrue("Revealing Security must preserve the same admitted Settings destination", current())
    }

    private fun awaitAcceptedEntry(journey: ReactEmulatorJourney, activity: LavaReactActivity) {
        val generation = checkNotNull(journey.application.foregroundProduct.state.value.graphGeneration)
        journey.await("actual closed destination acknowledged by its current native viewport") {
            acceptedEntryCurrent(journey, activity, generation)
        }
    }

    private fun acceptedEntryCurrent(journey: ReactEmulatorJourney, activity: LavaReactActivity,
        generation: Long, settings: Boolean = false): Boolean {
        val id = activity.nativeDeepLinkEntryID ?: return false
        val product = journey.application.foregroundProduct
        var ready = false
        journey.instrumentation.runOnMainSync {
            val receipt = NativeDeepLinkEntryRegistry.process.acceptance(id, activity, activity.nativeHostID, generation)
                ?: return@runOnMainSync
            val security = product.securityStateForSnapshot(generation) ?: return@runOnMainSync
            val surface = receipt.navigation.surface
            ready = journey.requireActivity() === activity &&
                journey.application.reactHost.currentReactContext?.currentActivity === activity &&
                journey.application.nativeVisitHosts.current()?.owner === activity &&
                activity.nativeHostAlive && activity.foreground && !activity.isFinishing && !activity.isDestroyed &&
                activity.hasWindowFocus() &&
                product.state.value.graphGeneration == generation &&
                activity.hasPresentationReceipt(generation, receipt.presentationSerial) &&
                security.isAuthorized(ProtectedSurface.APP_UNLOCK) &&
                (surface == null || security.isAuthorized(surface)) &&
                (!settings || receipt.navigation.tab == "SettingsTab" && receipt.navigation.screen == "Settings" &&
                    receipt.navigation.surface == ProtectedSurface.APP_SETTINGS)
        }
        if (!ready) return false
        val snapshot = journey.snapshot()
        return snapshot.optString("nativeHostID") == activity.nativeHostID &&
            snapshot.optString("generation").toLongOrNull() == generation &&
            snapshot.optJSONObject("navigation") == null &&
            snapshot.optJSONObject("onboardingSetup") == null &&
            !snapshot.optBoolean("presentationBlocked") &&
            !snapshot.optBoolean("backgroundPrivacyCoverRequired") &&
            product.sharedOnboardingAuthority().first == generation &&
            product.sharedOnboardingAuthority().second?.completed == true
    }

    private fun durableLibrary(journey: ReactEmulatorJourney) = runBlocking {
        val reopened = CredentialFilterConfigurationStore.credentialProtected(journey.context, FeatureLimits.FREE.maxFilterRules).readAuthoritative()
        assertTrue("Durable configuration must independently reopen: $reopened", reopened is CredentialFilterConfigurationRead.Available)
        (reopened as CredentialFilterConfigurationRead.Available).state.configuration
    }
}
