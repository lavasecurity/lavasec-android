package com.lavasec.app.reactnative

import android.net.VpnService
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.settings.ProductSettingsStore
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Separate fresh-data cohort: both real and DEBUG preview use the packaged shared RN tree. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LavaReactSharedForegroundOnboardingDeviceTest {
    @Test fun shippingFirstRunSharedOnboardingUsesRealPermissionsMeasuredReadyAndDurableQuad9() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            assertTrue("This evidence requires freshly cleared emulator data", journey.snapshot().getBoolean("onboardingRequired"))
            val product = journey.application.foregroundProduct
            val host = journey.application.reactHost
            try {
                journey.ensureConfigured(requireFirstRun = true, cancelFirstAttempt = true)
            } catch (failure: AssertionError) {
                runCatching { dumpOnboardingMeasurements(journey, journey.requireActivity(), expectedMock = false) }.onFailure {
                    println("LAVA_ONBOARDING_MEASUREMENT diagnostic-failed=${it.javaClass.simpleName}")
                }
                throw failure
            }
            assertSame(product, journey.application.foregroundProduct)
            assertSame(host, journey.application.reactHost)
            assertNull("Actual OS VPN preparation must be complete", VpnService.prepare(journey.context))
            assertTrue(product.sharedOnboardingAuthority().second?.completed == true)
            val saved = durableLibrary(journey)
            assertEquals("The durable library must agree with the production owner", product.currentFilterLibrary(), saved)
            val selected = saved.resolverTarget.configuration.toSelection()
            assertEquals(ResolverProviderId.DEVICE_DNS, selected.primary.providerId)
            assertEquals("Quad9 copy must persist Quad9's actual fallback route", ResolverProviderId.QUAD9_UNFILTERED,
                checkNotNull(selected.fallback).providerId)
            journey.reopen()
            assertFalse(journey.snapshot().getBoolean("onboardingRequired"))
            assertNull(journey.snapshot().optJSONObject("onboardingSetup"))
            assertEquals("Reopening must prove the same independently read configuration", saved, durableLibrary(journey))
            journey.waitProtectionOff()
            journey.assertNoFallback()
        }
    }

    @Test fun shippingMockQaSharedOnboardingCompletesAndDismissesWithoutSavingOrSystemPermissions() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val graph = product.state.value.graphGeneration
            val library = durableLibrary(journey)
            val preferences = ProductSettingsStore(journey.context).loadPreferences()
            val native = product.sharedOnboardingAuthority().second
            val notification = journey.requireActivity().notificationsGranted()
            val vpnPrepared = VpnService.prepare(journey.context) == null
            val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            journey.click("Settings", prefix = true, scroll = false)
            journey.scrollToTop(); journey.click("Device QA")
            journey.waitLabel("Search Device QA")
            journey.setText("Search Device QA", "Mock fresh onboarding")
            journey.click("Mock fresh onboarding", prefix = true, excludeEditable = true)
            journey.await("actual QA action creates an independent admitted DEBUG preview and its native mock owner") {
                val active = journey.application.reactHost.currentReactContext?.currentActivity as? LavaReactActivity
                active != null && active !== journey.requireActivity() && active.isOnboardingPreview && active.foreground &&
                    journey.snapshot().optJSONObject("onboardingSetup")?.optBoolean("mock") == true
            }
            journey.waitLabel("The internet is lava")
            val preview = journey.application.reactHost.currentReactContext?.currentActivity as LavaReactActivity
            assertTrue(preview.isOnboardingPreview)
            assertNotSame(journey.requireActivity(), preview)
            assertNotEquals("A second React window must have its own native layout authority", journey.requireActivity().nativeHostID, preview.nativeHostID)
            val id = journey.snapshot().getJSONObject("onboardingSetup").getString("id")
            val readyGeneration = checkNotNull(graph)
            journey.await("exact mock preview viewport is admitted") { preview.isPresentationReady(readyGeneration, "onboarding:$id") }
            val serial = presentationSerial(preview)
            val staleSource = org.json.JSONObject().put("nativeHostID", journey.requireActivity().nativeHostID)
            assertEquals("A source-window layout cannot acknowledge the visible preview", "rejected",
                journey.command("navigation.presentationReady", org.json.JSONObject(staleSource.toString())
                    .put("serial", serial).put("ownerID", "onboarding:$id")).getString("status"))
            assertEquals("A source root cannot conceal/replace the preview owner with a later serial", "rejected",
                journey.command("navigation.presentation", org.json.JSONObject(staleSource.toString())
                    .put("serial", serial + 100).put("surface", "filterEditing")).getString("status"))
            assertTrue("Rejected cross-window signals cannot change actual preview readiness", preview.isPresentationReady(readyGeneration, "onboarding:$id"))
            assertEquals("The same native mock owner remains current", id, journey.snapshot().getJSONObject("onboardingSetup").getString("id"))
            journey.click("Meet Lava", scroll = false)
            waitPage(journey, id, 1); journey.click("Set Up Protection", scroll = false)
            waitPage(journey, id, 2)
            journey.clickOnboardingChoice(preview, readyGeneration, id, 2,
                "First, let’s get Lava ready to help.", "Install local VPN")
            journey.await("transient mock VPN choice") { journey.snapshot().getJSONObject("onboardingSetup").getBoolean("vpnInstalled") }
            journey.clickOnboardingChoice(preview, readyGeneration, id, 2,
                "First, let’s get Lava ready to help.", "Enable notifications (optional)")
            journey.await("transient mock notification choice") { journey.snapshot().getJSONObject("onboardingSetup").getBoolean("notifications") }
            assertEquals("Mock cannot ask Android for notification authority", notification, preview.notificationsGranted())
            assertEquals("Mock cannot alter actual VPN consent", vpnPrepared, VpnService.prepare(journey.context) == null)
            journey.click("Next step", scroll = false); waitPage(journey, id, 3)
            journey.clickOnboardingChoice(preview, readyGeneration, id, 3,
                "Pick how much Lava blocks", "Extra", prefix = true)
            journey.await("transient mock protection choice") { journey.snapshot().getJSONObject("onboardingSetup").getString("level") == "comprehensive" }
            journey.click("Next step", scroll = false); waitPage(journey, id, 4)
            journey.clickOnboardingChoice(preview, readyGeneration, id, 4,
                "Lastly, let’s keep your connection running smoothly.", "Keep connections working", prefix = true)
            journey.await("transient mock fallback choice") { !journey.snapshot().getJSONObject("onboardingSetup").getBoolean("fallback") }
            assertEquals(library, durableLibrary(journey))
            journey.click("Next step", scroll = false)
            waitPage(journey, id, 5)
            assertEquals("Final Next enters arrival in the same mock owner", id, journey.snapshot().getJSONObject("onboardingSetup").getString("id"))
            assertEquals("Arrival cannot save a mock draft", library, durableLibrary(journey))
            try {
                journey.await("mock shared measured Ready") { journey.snapshot().getJSONObject("onboardingSetup").getString("phase") == "ready" }
            } catch (failure: AssertionError) {
                runCatching { dumpOnboardingMeasurements(journey, preview, expectedMock = true) }.onFailure {
                    println("LAVA_ONBOARDING_MEASUREMENT diagnostic-failed=${it.javaClass.simpleName}")
                }
                throw failure
            }
            journey.waitLabel("Ready, Your next step to a safer internet.")
            journey.clickOnboardingPrimary(preview, readyGeneration, id, mock = true)
            try {
                journey.await("mock return animation finishes only its preview Activity", 20_000) {
                    preview.isDestroyed && journey.application.reactHost.currentReactContext?.currentActivity === journey.requireActivity() &&
                        journey.snapshot().optJSONObject("onboardingSetup") == null
                }
            } catch (failure: AssertionError) {
                runCatching { dumpOnboardingMeasurements(journey, preview, expectedMock = true) }.onFailure {
                    println("LAVA_ONBOARDING_MEASUREMENT diagnostic-failed=${it.javaClass.simpleName}")
                }
                throw failure
            }
            journey.waitLabel("Search Device QA")
            assertEquals(graph, product.state.value.graphGeneration)
            assertEquals(native, product.sharedOnboardingAuthority().second)
            assertEquals(library, durableLibrary(journey))
            assertEquals(preferences, ProductSettingsStore(journey.context).loadPreferences())
            assertEquals(plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            assertEquals(notification, journey.requireActivity().notificationsGranted())
            assertEquals(vpnPrepared, VpnService.prepare(journey.context) == null)
            // A second same-production-entry preview can be dismissed by real Back.
            journey.setText("Search Device QA", "Mock fresh onboarding")
            journey.click("Mock fresh onboarding", prefix = true, excludeEditable = true)
            journey.await("second QA action creates a fresh independent native mock visit") {
                val active = journey.application.reactHost.currentReactContext?.currentActivity as? LavaReactActivity
                if (active == null || active === journey.requireActivity() || active === preview ||
                    !active.isOnboardingPreview || !active.foreground ||
                    journey.application.nativeVisitHosts.current()?.owner !== active) return@await false
                val setup = journey.snapshot().optJSONObject("onboardingSetup")
                setup != null && setup.optBoolean("mock") && setup.optString("id") != id
            }
            journey.waitLabel("The internet is lava")
            journey.back()
            journey.await("mock Back returns to the admitted QA host") {
                journey.application.reactHost.currentReactContext?.currentActivity === journey.requireActivity() &&
                    journey.snapshot().optJSONObject("onboardingSetup") == null
            }
            journey.waitLabel("Search Device QA")
            assertEquals(library, durableLibrary(journey))
            assertEquals(native, product.sharedOnboardingAuthority().second)
            assertEquals(graph, product.state.value.graphGeneration)
            journey.assertNoFallback()
        }
    }

    /** Failure-only scalar geometry from the actual current window, never private copy or IDs. */
    private fun dumpOnboardingMeasurements(journey: ReactEmulatorJourney, activity: LavaReactActivity, expectedMock: Boolean) {
        // Read the actual visit before entering Main; the bridge read must not block its dispatcher.
        val setup = journey.snapshot().optJSONObject("onboardingSetup")
        val id = setup?.optString("id")?.takeIf(String::isNotEmpty)
        val phase = setup?.optString("phase")
        val generation = journey.application.foregroundProduct.state.value.graphGeneration
        journey.instrumentation.runOnMainSync {
            val current = journey.application.reactHost.currentReactContext?.currentActivity
            if (current !== activity || activity.isOnboardingPreview != expectedMock || activity.isDestroyed) {
                println("LAVA_ONBOARDING_MEASUREMENT currentHost=false mock=$expectedMock destroyed=${activity.isDestroyed}")
                return@runOnMainSync
            }
            val root = activity.window.decorView
            val rootPosition = IntArray(2).also(root::getLocationInWindow)
            println("LAVA_ONBOARDING_MEASUREMENT host=${System.identityHashCode(activity)} foreground=${activity.foreground} " +
                "focus=${activity.hasWindowFocus()} window=${System.identityHashCode(root)} " +
                "root=${root.width}x${root.height}@${rootPosition[0]},${rootPosition[1]} " +
                "visit=${setup != null} mock=${setup?.optBoolean("mock") == true} page=${setup?.optInt("page", -1) ?: -1} " +
                "setupPhase=${phase == "setup"} arriving=${phase == "arriving"} ready=${phase == "ready"} " +
                "opening=${phase == "opening"} released=${phase == "released"} busy=${!setup?.optString("busy").isNullOrEmpty()} " +
                "nativeReady=${generation != null && id != null && activity.isPresentationReady(generation, "onboarding:$id")}")
            val required = setOf("onboarding.surface", "onboarding.runtime", "guard.material", "guard.mascot", "guard.pause-menu", "onboarding.source")
            val found = mutableSetOf<String>()
            fun bounds(view: View, id: String, depth: Int) {
                val position = IntArray(2).also(view::getLocationInWindow)
                println("LAVA_ONBOARDING_MEASUREMENT view=$id depth=$depth size=${view.width}x${view.height} " +
                    "windowPosition=${position[0]},${position[1]} scrollY=${view.scrollY} " +
                    "scrollViewport=${view is android.widget.ScrollView || view is android.widget.HorizontalScrollView} " +
                    "attached=${view.isAttachedToWindow} shown=${view.isShown}")
            }
            fun inspect(view: View) {
                val id = view.getTag(com.facebook.react.R.id.react_test_id)?.toString()
                if (id in required) {
                    val staticID = checkNotNull(id)
                    found += staticID
                    bounds(view, staticID, 0)
                    // Guard's measured anchors are real parents of these leaves. Record the
                    // actual ancestor/scroll viewport bounds to diagnose an offscreen hero.
                    var parent = view.parent as? View
                    var depth = 1
                    while (parent != null && depth <= 12) {
                        bounds(parent, staticID, depth)
                        if (parent === root) break
                        parent = parent.parent as? View
                        depth++
                    }
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
            }
            inspect(root)
            (required - found).sorted().forEach { println("LAVA_ONBOARDING_MEASUREMENT view=$it missing=true") }
        }
    }

    private fun presentationSerial(activity: LavaReactActivity): Long = LavaReactActivity::class.java
        .getDeclaredField("presentationSerial").apply { isAccessible = true }.getLong(activity)

    private fun waitPage(journey: ReactEmulatorJourney, id: String, page: Int) {
        journey.await("same shared onboarding visit page $page") {
            journey.snapshot().optJSONObject("onboardingSetup")?.let { it.getString("id") == id && it.getInt("page") == page } == true
        }
    }
    private fun durableLibrary(journey: ReactEmulatorJourney) = runBlocking {
        val reopened = CredentialFilterConfigurationStore.credentialProtected(journey.context, FeatureLimits.FREE.maxFilterRules).readAuthoritative()
        assertTrue("Durable configuration must independently reopen: $reopened", reopened is CredentialFilterConfigurationRead.Available)
        (reopened as CredentialFilterConfigurationRead.Available).state.configuration
    }
}
