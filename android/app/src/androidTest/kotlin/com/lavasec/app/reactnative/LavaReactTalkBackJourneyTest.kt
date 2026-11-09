package com.lavasec.app.reactnative

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.BuildConfig
import com.lavasec.app.data.CatalogRepository
import com.lavasec.app.data.CredentialConfigurationCodec
import com.lavasec.app.data.CredentialConfigurationRepository
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.settings.ProductSettingsStore
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/**
 * Capability-dependent, explicitly invoked owned-API36 evidence. This requires the installed
 * Google TalkBack service and a verified InputReader touchscreen transport for its ordinary
 * next-item/double-tap gestures. An explicitly configured authenticated host bridge must prove
 * fresh kernel delivery before secure-setting writes; absent configuration fails before launch.
 * UiAutomation input injection
 * bypasses accessibility input filtering and cannot provide this evidence. This does not install
 * a service, alter TalkBack preferences/tutorial state, capture speech, or qualify physical use.
 * Missing capabilities, a tutorial, remapped gestures, and foreign foreground windows fail.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactTalkBackJourneyTest {
    @Test
    fun installedTalkBackSwipeFocusAndDoubleTapActivateShippingSettingsWithoutChangingProduct() {
        assertTrue("Invoke this scoped capability test on the owned API36 emulator", Build.VERSION.SDK_INT >= 36)
        assertTrue("Only the task-owned debug emulator may change accessibility service settings", BuildConfig.DEBUG &&
            (Build.FINGERPRINT.contains("generic") || Build.HARDWARE in setOf("ranchu", "goldfish")))
        assertEquals("Use the existing English fixture; do not change its locale", "en", Locale.getDefault().language)
        val transport = TalkBackInputReaderTransport.fromArguments()

        ReactEmulatorJourney(AUTOMATION_FLAGS).use { journey ->
            journey.launch()
            val ready = journey.snapshot()
            assertTrue("Use the already configured real emulator owner; this capability fixture performs no setup/settings writes",
                ready.optBoolean("available") && !ready.optBoolean("onboardingRequired"))
            val preserved = Preservation(journey)
            val automation = journey.instrumentation.getUiAutomation(AUTOMATION_FLAGS)
            val service = InstalledTalkBack(journey, automation, preserved::assertOwner)
            var failure: Throwable? = null
            try {
                val guard = tab(journey, automation, "Guard")
                if (!guard.isSelected) {
                    assertTrue("Select the real Guard tab as the pre-service baseline",
                        guard.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                journey.await("the real selected Guard baseline") { findTab(journey, automation, "Guard")?.isSelected == true }
                journey.waitProtectionOff()
                preserved.assertCurrent()
                val preflightWindow = ownedWindow(automation, journey)
                val preflightViewport = Rect().also(preflightWindow::getBoundsInScreen)
                val preflightSource = tab(journey, automation, "Guard")
                val assertPreflight = {
                    preserved.assertCurrent()
                    val current = ownedWindow(automation, journey)
                    assertEquals("The kernel preflight retains the exact focused owned window", preflightWindow.id, current.id)
                    assertEquals("The kernel preflight retains the measured app viewport", preflightViewport,
                        Rect().also(current::getBoundsInScreen))
                    assertEquals("The kernel preflight retains the fresh selected Guard source", preflightSource,
                        findTab(journey, automation, "Guard", current)?.takeIf { it.isSelected })
                }
                assertPreflight()
                transport.preflight(transportWitness(journey, preflightWindow.id, preflightSource.hashCode(), preflightViewport),
                    assertPreflight)
                preserved.assertCurrent()
                service.enable()
                preserved.assertCurrent()

                val baseline = tab(journey, automation, "Guard", preflightWindow.id, preflightSource)
                assertTrue("The baseline must be the currently selected, enabled shipping tab", baseline.isSelected && baseline.isEnabled)
                val gestureWindow = ownedWindow(automation, journey)
                val viewport = Rect().also(gestureWindow::getBoundsInScreen)
                assertTrue("Gesture coordinates require the measured, nonempty app viewport", viewport.width() > 0 && viewport.height() > 0)
                val assertGestureWindow = {
                    preserved.assertCurrent()
                    service.assertOwnedEnabled()
                    val current = ownedWindow(automation, journey)
                    assertEquals("Never gesture into a replacement app window", gestureWindow.id, current.id)
                    assertEquals("Use only the measured current viewport", viewport, Rect().also(current::getBoundsInScreen))
                }
                val baselineBounds = Rect().also(baseline::getBoundsInScreen)
                assertTrue("Touch-explore only the measured current Guard target", baselineBounds.width() >= 4 &&
                    baselineBounds.height() >= 4 && viewport.contains(baselineBounds))
                val assertBaseline = {
                    assertGestureWindow()
                    val current = checkNotNull(findTab(journey, automation, "Guard", ownedWindow(automation, journey)))
                    assertEquals("Touch exploration retains the exact refreshed Guard source", baseline, current)
                    assertTrue("Touch exploration starts with Guard still selected", current.isSelected)
                    assertEquals("Touch exploration retains actual target geometry", baselineBounds, Rect().also(current::getBoundsInScreen))
                }
                // One genuine touch-explore contact establishes TalkBack's own focus history.
                // A foreign AX focus action cannot suppress its delayed initial-screen focus.
                BaselineWindowTrace(journey, gestureWindow.id).use { windowTrace ->
                    BaselineAccessibilityTrace(automation, gestureWindow.id).use { trace ->
                        service.printPublicState("before-baseline-contact")
                        trace.mark("contact")
                        transport.touchExplore(transportWitness(journey, gestureWindow.id, baseline.hashCode(), viewport),
                            baselineBounds, { windowTrace.assertInstalled(); assertBaseline() }, assertGestureWindow)
                        trace.mark("kernel-released")
                        service.printPublicState("after-baseline-contact")
                        awaitFocus(journey, automation, "Guard", gestureWindow.id)
                        trace.mark("guard-focus-confirmed")
                    }
                }
                preserved.assertCurrent()
                assertTrue("Touch exploration does not activate another route", tab(journey, automation, "Guard").isSelected)
                assertFalse("Touch exploration must leave Settings unselected", tab(journey, automation, "Settings").isSelected)
                gesture(journey, automation, transport, viewport, swipeRight = true, expectedFocus = "Guard", assertOwned = assertGestureWindow)
                awaitFocus(journey, automation, "Settings", gestureWindow.id)
                assertTrue("A next-item gesture changes focus without changing the selected route", tab(journey, automation, "Guard").isSelected)
                assertFalse(tab(journey, automation, "Settings").isSelected)

                gesture(journey, automation, transport, viewport, swipeRight = false, expectedFocus = "Settings", assertOwned = assertGestureWindow)
                journey.await("TalkBack double-tap activates the focused shipping Settings tab") {
                    findTab(journey, automation, "Settings")?.isSelected == true &&
                        findTab(journey, automation, "Guard")?.isSelected == false
                }
                journey.waitLabel("Your connection")
                journey.assertNoFallback()
                preserved.assertCurrent()
                journey.back()
                journey.await("the real Back returns to the same shipping Guard owner") { findTab(journey, automation, "Guard")?.isSelected == true }
                preserved.assertCurrent()
                println("LAVA_UI_TALKBACK scope=owned-api36 baseline=one-touch-explore navigation=one-swipe-right activation=one-double-tap settings=real back=guard speech=not-observed product=preserved")
            } catch (problem: Throwable) {
                failure = problem
                throw problem
            } finally {
                try {
                    service.close()
                    preserved.assertCurrent()
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    /** Forward every event once; bounds candidates are observations, not a claimed hit-test result. */
    private class BaselineWindowTrace(private val journey: ReactEmulatorJourney, private val windowId: Int) : AutoCloseable {
        private val activity = journey.requireActivity()
        private val product = journey.application.foregroundProduct
        private val generation = checkNotNull(product.state.value.graphGeneration)
        private val startedAt = SystemClock.uptimeMillis()
        private lateinit var window: Window
        private lateinit var original: Window.Callback
        private lateinit var forwarding: Window.Callback
        private val records = ArrayList<String>()
        private var count = 0
        private var dropped = 0
        private var restored = false

        init {
            journey.instrumentation.runOnMainSync {
                window = activity.window
                original = checkNotNull(window.callback)
                forwarding = object : Window.Callback by original {
                    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                        val hover = event.actionMasked in setOf(MotionEvent.ACTION_HOVER_ENTER,
                            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT)
                        val index = if (hover) ++count else 0
                        if (hover && index <= 8) {
                            try {
                                records += "event=$index action=${event.actionMasked} source=${event.source} device=${event.deviceId} " +
                                    "windowDisplay=${window.decorView.display?.displayId} " +
                                    "eventAtMs=${event.eventTime - startedAt} observedAtMs=${SystemClock.uptimeMillis() - startedAt} " +
                                    "raw=${event.rawX},${event.rawY} local=${event.x},${event.y} window=$windowId " +
                                    "callbackOwned=${window.callback === this} activityOwned=${journey.requireActivity() === activity} " +
                                    "ownerOwned=${journey.application.foregroundProduct === product} " +
                                    "graphOwned=${product.state.value.graphGeneration == generation}"
                                recordBoundsCandidates(window.decorView, event.rawX, event.rawY, index)
                            } catch (diagnostic: Throwable) {
                                records += "event=$index metadataFailure=${diagnostic.javaClass.simpleName}"
                            }
                        } else if (hover) dropped++
                        try {
                            val result = original.dispatchGenericMotionEvent(event)
                            if (hover && index <= 8) records += "event=$index delegated=true handled=$result"
                            return result
                        } catch (failure: Throwable) {
                            if (hover && index <= 8) records += "event=$index delegated=true threw=${failure.javaClass.simpleName}"
                            throw failure
                        }
                    }
                }
                assertTrue("Observe only the exact original live Activity/window/callback",
                    journey.requireActivity() === activity && !activity.isFinishing && !activity.isDestroyed &&
                        activity.window === window && window.callback === original)
                window.callback = forwarding
            }
        }

        fun assertInstalled() {
            journey.instrumentation.runOnMainSync {
                assertTrue("The forwarding observer retains its exact original window/callback/product/graph",
                    activity.window === window && window.callback === forwarding &&
                        journey.application.foregroundProduct === product && product.state.value.graphGeneration == generation)
            }
        }

        private fun recordBoundsCandidates(root: View, x: Float, y: Float, event: Int) {
            val rootScreenOrigin = IntArray(2).also(root.rootView::getLocationOnScreen)
            var candidates = 0
            fun visit(view: View, path: String, depth: Int) {
                if (depth > 24 || candidates >= 32 || !view.isShown) return
                val visible = Rect()
                if (!view.getGlobalVisibleRect(visible)) return
                // This public rectangle is root-relative; event.rawX/Y are screen-relative.
                visible.offset(rootScreenOrigin[0], rootScreenOrigin[1])
                if (x < visible.left || x >= visible.right || y < visible.top || y >= visible.bottom) return
                val location = IntArray(2).also(view::getLocationOnScreen)
                candidates++
                // Do not read text, hints, content descriptions, span/buffer data, or resource names.
                records += "event=$event boundsCandidate=$path class=${view.javaClass.name} id=${view.id} " +
                    "identity=${System.identityHashCode(view)} display=${view.display?.displayId} " +
                    "screen=${location[0]},${location[1]},${view.width},${view.height} visibleScreen=$visible " +
                    "importantA11y=${view.importantForAccessibility} clickable=${view.isClickable} focusable=${view.isFocusable} " +
                    "attached=${view.isAttachedToWindow} alpha=${view.alpha} z=${view.z} matrixIdentity=${view.matrix.isIdentity}"
                if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) {
                    visit(view.getChildAt(index), "$path/$index", depth + 1)
                }
            }
            visit(root, "decor", 0)
        }

        override fun close() {
            try {
                journey.instrumentation.runOnMainSync {
                    assertTrue("Never overwrite a foreign replacement window callback during diagnostic cleanup",
                        activity.window === window && window.callback === forwarding)
                    window.callback = original
                    restored = window.callback === original
                    assertTrue("Restore the exact original Window.Callback before service cleanup", restored)
                }
            } finally {
                records.forEach { println("LAVA_UI_TALKBACK_WINDOW_HOVER $it") }
                println("LAVA_UI_TALKBACK_WINDOW_TRACE observed=$count dropped=$dropped restored=$restored input=unchanged")
            }
        }
    }

    /** Metadata-only observer: it neither requests focus nor changes the event/service policy. */
    private class BaselineAccessibilityTrace(private val automation: UiAutomation, private val windowId: Int) : AutoCloseable {
        private val lock = Any()
        private val startedAt = SystemClock.uptimeMillis()
        private val events = ArrayList<String>()
        private var phase = "before-contact"
        private var closed = false
        private var dropped = 0

        init {
            // This isolated fixture and ReactEmulatorJourney install no other event listener.
            // Do not retain/read event sources, text, content descriptions or provider state.
            automation.setOnAccessibilityEventListener { event ->
                if (event.eventType in setOf(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END, AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
                        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
                        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)) {
                    synchronized(lock) {
                        if (!closed) {
                            if (events.size < 64) events += "phase=$phase type=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                                "eventAtMs=${event.eventTime - startedAt} observedAtMs=${SystemClock.uptimeMillis() - startedAt} " +
                                "window=${event.windowId} ownedWindow=${event.windowId == windowId}"
                            else dropped++
                        }
                    }
                }
            }
        }

        fun mark(value: String) {
            synchronized(lock) { phase = value }
            println("LAVA_UI_TALKBACK_AX_TIMELINE stage=$value atMs=${SystemClock.uptimeMillis() - startedAt}")
        }

        override fun close() {
            val snapshot = synchronized(lock) {
                closed = true
                events.toList() to dropped
            }
            try { automation.setOnAccessibilityEventListener(null) } finally {
                snapshot.first.forEach { println("LAVA_UI_TALKBACK_AX_EVENT $it") }
                println("LAVA_UI_TALKBACK_AX_TIMELINE stage=closed observed=${snapshot.first.size} dropped=${snapshot.second} policy=unchanged")
            }
        }
    }

    private data class EnabledSubtypes(
        val explicit: Set<InputMethodSubtype>,
        val effective: Set<InputMethodSubtype>,
    )

    private data class InputMethods(val selectedId: String?, val enabled: Map<String, EnabledSubtypes>) {
        companion object {
            fun capture(context: Context): InputMethods {
                val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val methods = manager.enabledInputMethodList
                assertEquals("Enabled IME identities must be unambiguous", methods.size, methods.map { it.id }.toSet().size)
                // ENABLED_INPUT_METHODS is restricted at targetSdk >= 34. These public APIs
                // retain actual explicit and implicit subtype facts without privileged reads.
                return InputMethods(
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD),
                    methods.associate { method -> method.id to EnabledSubtypes(
                        manager.getEnabledInputMethodSubtypeList(method, false).toSet(),
                        manager.getEnabledInputMethodSubtypeList(method, true).toSet(),
                    ) },
                )
            }
        }
    }

    private class Preservation(val journey: ReactEmulatorJourney) {
        private val activity = journey.requireActivity()
        private val product = journey.application.foregroundProduct
        private val generation = checkNotNull(product.state.value.graphGeneration)
        private val library = checkNotNull(product.currentFilterLibrary())
        private val logging = checkNotNull(product.currentQaLogging())
        private val protection = protectionAuthority(journey.context)
        private val preferences = ProductSettingsStore(journey.context).loadPreferences()
        private val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
        private val inputMethods = InputMethods.capture(journey.context)
        private val configuration = journey.context.resources.configuration.let { Triple(it.orientation, it.fontScale, it.locales.toLanguageTags()) }

        init {
            assertFalse("This gesture fixture requires the existing unlocked Settings admission; never remove a credential",
                product.state.value.security?.enabled == true)
            assertCurrent()
        }

        fun assertOwner() {
            assertTrue("Preserve the exact live Activity owner", journey.requireActivity() === activity &&
                !activity.isFinishing && !activity.isDestroyed)
            assertTrue("Preserve the exact original product owner", journey.application.foregroundProduct === product)
            assertEquals("Never act against a replacement credential graph", generation, product.state.value.graphGeneration)
        }

        fun assertCurrent() {
            assertOwner()
            assertEquals("TalkBack cannot mutate library, DNS, chain or QA configuration", library, product.currentFilterLibrary())
            assertEquals("Preserve logging and history retention", logging, product.currentQaLogging())
            assertEquals("TalkBack cannot change durable protection intent, session or pause", protection,
                protectionAuthority(journey.context))
            assertEquals("Do not change app presentation preferences", preferences, ProductSettingsStore(journey.context).loadPreferences())
            assertEquals("Do not acquire or revoke entitlement", plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            assertEquals("Preserve the selected IME and enabled IMEs/explicit/effective subtypes", inputMethods,
                InputMethods.capture(journey.context))
            val current = journey.context.resources.configuration
            assertEquals("Preserve platform orientation/font/languages", configuration, Triple(current.orientation, current.fontScale, current.locales.toLanguageTags()))
        }
    }

    private data class ProtectionAuthority(val requested: Boolean, val session: Long, val pause: PauseState?)

    private companion object {
        fun protectionAuthority(context: Context): ProtectionAuthority {
            val credentialContext = context.applicationContext
            assertFalse("Read protection authority only from the actual credential store", credentialContext.isDeviceProtectedStorage)
            // Match the existing Guard TUN witness: reopen the published file through DataStore's
            // public serializer and the production strict codec, without a second store or write.
            val file = credentialContext.preferencesDataStoreFile(CredentialConfigurationRepository.CREDENTIAL_STORE_NAME)
            val preferences = runBlocking { file.inputStream().use { PreferencesFileSerializer.readFrom(it) } }
            val state = CredentialConfigurationCodec.requireWritable(preferences, CatalogRepository(credentialContext.assets).loadBundled())
            return ProtectionAuthority(state.protectionIntent, state.protectionSessionId, state.pause)
        }

        const val AUTOMATION_FLAGS = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        const val TALKBACK_PACKAGE = "com.google.android.marvin.talkback"
        const val SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        const val ENABLED = Settings.Secure.ACCESSIBILITY_ENABLED
        const val TOUCH = Settings.Secure.TOUCH_EXPLORATION_ENABLED
        // Public Settings getString/putString retain the exact original value; the SDK names
        // this legacy key but hides its constant. Modern TalkBack needs no grant-list mutation.
        const val GRANTS = "touch_exploration_granted_accessibility_services"
    }

    /** Only this resolved installed component is enabled, and only its exact owned values may be restored. */
    private class InstalledTalkBack(
        val journey: ReactEmulatorJourney,
        val automation: UiAutomation,
        val isCurrent: () -> Unit,
    ) : AutoCloseable {
        private val resolver = journey.context.contentResolver
        private val manager = journey.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        private val original = values()
        private val info = manager.installedAccessibilityServiceList.filter {
            it.resolveInfo.serviceInfo.packageName == TALKBACK_PACKAGE
        }.let { installed ->
            val candidates = installed.filter {
                it.feedbackType and AccessibilityServiceInfo.FEEDBACK_SPOKEN != 0 &&
                    it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION != 0
            }
            if (candidates.size != 1) installed.take(8).forEach { service ->
                val declared = service.resolveInfo.serviceInfo
                val label = service.resolveInfo.loadLabel(journey.context.packageManager).toString()
                    .replace('\n', ' ').replace('\r', ' ').take(80)
                println("LAVA_UI_TALKBACK_INSTALLED component=${ComponentName(declared.packageName, declared.name).flattenToShortString()} " +
                    "label=$label feedback=${service.feedbackType} capabilities=${service.capabilities} targetSdk=${declared.applicationInfo.targetSdkVersion}")
            }
            assertEquals("Require one actually installed spoken TalkBack service with declared touch exploration", 1, candidates.size)
            candidates.single()
        }
        private val component = info.resolveInfo.serviceInfo.let { ComponentName(it.packageName, it.name) }
        private val enabled = original + mapOf(SERVICES to component.flattenToString(), ENABLED to "1", TOUCH to "1")
        private var started = false

        init {
            val declared = journey.context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
            assertEquals("Resolve the actual installed ServiceInfo, never a guessed service class", component,
                ComponentName(declared.packageName, declared.name))
            assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, declared.permission)
            assertTrue("The installed modern service must own the touch-exploration capability",
                declared.applicationInfo.targetSdkVersion >= 18 && info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION != 0)
            assertTrue("Do not replace or disable any originally enabled accessibility service", original.getValue(SERVICES).isNullOrBlank())
            assertTrue("The test must not inherit a secure-settings permission grant",
                journey.context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED)
        }

        fun enable() {
            isCurrent()
            assertEquals("The exact original secure values must still be owned before activation", original, values())
            started = true // Include partial activation in finally; never drop a failed activation's cleanup.
            withWritePermission {
                write(SERVICES, component.flattenToString())
                assertActivationTransition()
                write(ENABLED, "1")
            }
            journey.await("the installed TalkBack service bound with real touch exploration") {
                assertActivationTransition()
                val active = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
                active.size == 1 && active.single().resolveInfo.serviceInfo.let { ComponentName(it.packageName, it.name) } == component &&
                    active.single().flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0 &&
                    manager.isTouchExplorationEnabled && values() == enabled
            }
            assertOwnedEnabled()
            val version = journey.context.packageManager.getPackageInfo(component.packageName, 0).versionName
            println("LAVA_UI_TALKBACK_SERVICE component=${component.flattenToShortString()} version=$version capabilities=${info.capabilities} touchExploration=true automationFlags=$AUTOMATION_FLAGS")
        }

        fun assertOwnedEnabled() {
            isCurrent()
            assertEquals("Do not use or restore foreign accessibility settings", enabled, values())
            assertTrue("TalkBack must still own live touch exploration", manager.isTouchExplorationEnabled)
        }

        fun printPublicState(stage: String) {
            assertOwnedEnabled()
            val active = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
                .singleOrNull { it.resolveInfo.serviceInfo.let { declared -> ComponentName(declared.packageName, declared.name) } == component }
            val observer = automation.serviceInfo
            println("LAVA_UI_TALKBACK_PUBLIC_STATE stage=$stage boundComponent=${active != null} providerFlags=${active?.flags} " +
                "providerEventTypes=${active?.eventTypes} providerCapabilities=${active?.capabilities} " +
                "touchExploration=${manager.isTouchExplorationEnabled} observerFlags=${observer.flags} observerEventTypes=${observer.eventTypes}")
        }

        private fun assertActivationTransition() {
            isCurrent()
            val current = values()
            assertEquals("Refuse a replacement enabled-services list", enabled.getValue(SERVICES), current.getValue(SERVICES))
            for (key in listOf(ENABLED, TOUCH)) assertTrue("Only the original or this activation's framework value may change $key",
                current.getValue(key) == original.getValue(key) || current.getValue(key) == "1")
            assertEquals("Modern TalkBack activation must not replace legacy touch grants", original.getValue(GRANTS), current.getValue(GRANTS))
        }

        override fun close() {
            if (!started) { assertEquals(original, values()); return }
            assertActivationTransition()
            withWritePermission { write(SERVICES, original.getValue(SERVICES)) }
            journey.await("the owned TalkBack component unbound before exact secure restoration") {
                val current = values()
                assertEquals("Refuse any replacement service during teardown", original.getValue(SERVICES), current.getValue(SERVICES))
                assertEquals("Preserve all original touch grants", original.getValue(GRANTS), current.getValue(GRANTS))
                for (key in listOf(ENABLED, TOUCH)) assertTrue("Only this service's framework teardown may change $key",
                    current.getValue(key) in setOf(original.getValue(key), "0", "1"))
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty() && !manager.isTouchExplorationEnabled
            }
            // The framework owns its disable transition. Once settled, every subsequent write
            // is fenced by the exact preceding snapshot, including nullable original values.
            var expected = values()
            withWritePermission {
                for (key in listOf(ENABLED, TOUCH)) {
                    isCurrent()
                    assertEquals("Refuse concurrent secure changes before restoring $key", expected, values())
                    write(key, original.getValue(key))
                    expected = expected + (key to original.getValue(key))
                }
            }
            journey.await("all exact original accessibility secure values restored") { values() == original }
            assertEquals("Do not leave shell permission adopted", PackageManager.PERMISSION_DENIED,
                journey.context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS))
            started = false
            println("LAVA_UI_TALKBACK_RESTORE enabledServices=original accessibilityEnabled=original touchExploration=original grants=original")
        }

        private fun values(): Map<String, String?> = listOf(SERVICES, ENABLED, TOUCH, GRANTS)
            .associateWith { Settings.Secure.getString(resolver, it) }

        private fun write(key: String, value: String?) {
            isCurrent()
            assertTrue("The scoped secure write must be accepted for $key", Settings.Secure.putString(resolver, key, value))
        }

        private fun withWritePermission(action: () -> Unit) {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try { action() } finally { automation.dropShellPermissionIdentity() }
        }
    }

    private fun ownedWindow(automation: UiAutomation, journey: ReactEmulatorJourney): AccessibilityWindowInfo {
        val window = focusedOwnedWindow(automation, journey)
        assertTrue("Input requires one actually focused owned app window right now", window != null)
        return checkNotNull(window)
    }

    /** Only absent focus is pending during acquisition; an actual foreign/ambiguous focus refuses immediately. */
    private fun focusedOwnedWindow(automation: UiAutomation, journey: ReactEmulatorJourney): AccessibilityWindowInfo? {
        val windows = automation.windows
        try {
            val focused = windows.filter { it.isFocused }
            if (focused.isEmpty()) return null
            assertEquals("A gesture requires exactly one focused actual app window; no tutorial/system/foreign fallback", 1, focused.size)
            val window = focused.single()
            assertEquals("A focused system overlay cannot admit an app gesture", AccessibilityWindowInfo.TYPE_APPLICATION, window.type)
            val root = checkNotNull(window.root)
            assertTrue("The gesture's current window root must refresh", root.refresh())
            assertEquals("Never inject a gesture into a tutorial or foreign application", journey.context.packageName, root.packageName?.toString())
            return window
        } catch (failure: Throwable) {
            // Retain only public window identity/state and root type: no title, node text,
            // descendants, provider preferences, or attempt to admit a foreign tutorial.
            runCatching {
                val metadata = windows.take(8).joinToString(" | ") { window ->
                    val root = window.root
                    "id=${window.id} type=${window.type} layer=${window.layer} focused=${window.isFocused} active=${window.isActive} " +
                        "rootPackage=${root?.packageName} rootClass=${root?.className}"
                }
                println("LAVA_UI_TALKBACK_WINDOW_REFUSAL total=${windows.size} windows=[$metadata]")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun tab(journey: ReactEmulatorJourney, automation: UiAutomation, label: String,
        expectedWindowId: Int? = null, expectedSource: AccessibilityNodeInfo? = null): AccessibilityNodeInfo {
        val activity = journey.requireActivity()
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        var windowId = expectedWindowId
        var result: AccessibilityNodeInfo? = null
        var lastSources = "not acquired"
        var reportedMissing = false
        try {
            journey.await("one fresh connected '$label' shipping Tab") {
                assertTrue("Tab acquisition retains the exact live Activity",
                    journey.requireActivity() === activity && !activity.isFinishing && !activity.isDestroyed)
                assertTrue("Tab acquisition retains the exact native product owner", journey.application.foregroundProduct === product)
                assertEquals("Tab acquisition retains the original credential graph", generation, product.state.value.graphGeneration)
                // Initial window readiness and subsequent target readiness share this one
                // existing twenty-second await. A zero-focus service transition never grants input.
                val window = focusedOwnedWindow(automation, journey)
                if (window == null) {
                    result = null
                    lastSources = "no focused window; no target or gesture admitted"
                    if (!reportedMissing) {
                        println("LAVA_UI_TALKBACK_TAB_WAIT label=$label $lastSources")
                        reportedMissing = true
                    }
                    return@await false
                }
                if (windowId == null) windowId = window.id
                assertEquals("Tab acquisition refuses a replacement owned window", checkNotNull(windowId), window.id)
                result = findTab(journey, automation, label, window) { lastSources = it }
                if (expectedSource != null && result != null) assertEquals(
                    "Tab acquisition retains the original shipping source", expectedSource, result)
                if (result == null && !reportedMissing) {
                    println("LAVA_UI_TALKBACK_TAB_WAIT label=$label $lastSources")
                    reportedMissing = true
                }
                result != null
            }
        } catch (failure: Throwable) {
            println("LAVA_UI_TALKBACK_TAB_FAILURE label=$label $lastSources")
            throw failure
        }
        println("LAVA_UI_TALKBACK_TAB_READY label=$label $lastSources")
        return checkNotNull(result)
    }

    private fun findTab(journey: ReactEmulatorJourney, automation: UiAutomation, label: String,
        window: AccessibilityWindowInfo? = focusedOwnedWindow(automation, journey), diagnostics: ((String) -> Unit)? = null): AccessibilityNodeInfo? {
        if (window == null) return null
        val nodes = journey.accessibilityNodes().filter { it.windowId == window.id && it.packageName?.toString() == journey.context.packageName }
        fun isMatchingTab(node: AccessibilityNodeInfo): Boolean =
            node.windowId == window.id && node.packageName?.toString() == journey.context.packageName && node.isVisibleToUser &&
                node.isEnabled && node.isClickable && AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString() == "Tab" &&
                (node.contentDescription ?: node.text)?.toString()?.trim()?.removeSuffix(",")?.trim() == label
        val matches = nodes.filter { isMatchingTab(it) && it.refresh() && isMatchingTab(it) }
        if (diagnostics != null) {
            fun bounded(value: CharSequence?): String = value?.toString()?.replace('\n', ' ')?.replace('\r', ' ')?.take(80) ?: "<null>"
            val known = nodes.filter { node ->
                listOf(node.text, node.contentDescription).any { value ->
                    val text = value?.toString()?.trim().orEmpty()
                    listOf("Guard", "Settings").any { text == it || text.startsWith("$it,") }
                }
            }.take(8).joinToString(" | ") { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                "source=${node.hashCode()} class=${node.className} role=${AccessibilityNodeInfoCompat.wrap(node).roleDescription} " +
                    "text=${bounded(node.text)} description=${bounded(node.contentDescription)} " +
                    "visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable} " +
                    "selected=${node.isSelected} focused=${node.isAccessibilityFocused} bounds=$bounds actions=${node.actionList.map { it.id }}"
            }
            diagnostics("window=${window.id} connectedAppNodes=${nodes.size} matchingTabs=${matches.size} knownShippingLabels=[$known]")
        }
        assertTrue("'$label' must never identify ambiguous shipping Tabs", matches.size <= 1)
        return matches.singleOrNull()
    }

    private data class FocusSnapshot(
        val windowId: Int,
        val target: AccessibilityNodeInfo?,
        val focused: AccessibilityNodeInfo?,
    ) {
        val isTargetFocused: Boolean get() = target != null && focused != null && target == focused &&
            target.isAccessibilityFocused && focused.isAccessibilityFocused
    }

    private fun focusSnapshot(journey: ReactEmulatorJourney, automation: UiAutomation, label: String,
        window: AccessibilityWindowInfo = ownedWindow(automation, journey)): FocusSnapshot {
        val target = findTab(journey, automation, label, window)
        val root = checkNotNull(window.root)
        assertTrue("Read focus only from the fresh owned window root", root.refresh())
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.takeIf { node ->
            node.refresh() && node.windowId == window.id && node.packageName?.toString() == journey.context.packageName
        }
        return FocusSnapshot(window.id, target, focused)
    }

    private fun reportFocus(stage: String, label: String, snapshot: FocusSnapshot) {
        fun metadata(node: AccessibilityNodeInfo?): String {
            if (node == null) return "<none>"
            val ownLabel = (node.contentDescription ?: node.text)?.toString()?.trim()?.removeSuffix(",")?.trim()
            // A focused app node can contain private text. Expose only the two known shipping
            // labels; class, role, bounds and public source/state facts suffice for other nodes.
            val knownLabel = ownLabel?.takeIf { it == "Guard" || it == "Settings" } ?: "<redacted>"
            val role = AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString()
                ?.replace('\n', ' ')?.replace('\r', ' ')?.take(40)
            return "nodeHash=${node.hashCode()} window=${node.windowId} class=${node.className} role=$role label=$knownLabel " +
                "visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable} " +
                "selected=${node.isSelected} focused=${node.isAccessibilityFocused} heading=${AccessibilityNodeInfoCompat.wrap(node).isHeading} " +
                "bounds=${Rect().also(node::getBoundsInScreen)} actions=${node.actionList.map { it.id }}"
        }
        println("LAVA_UI_TALKBACK_FOCUSED_SOURCE stage=$stage expected=$label window=${snapshot.windowId} " +
            "matchesTarget=${snapshot.isTargetFocused} target=[${metadata(snapshot.target)}] focused=[${metadata(snapshot.focused)}]")
    }

    private fun awaitFocus(journey: ReactEmulatorJourney, automation: UiAutomation, label: String, windowId: Int) {
        val activity = journey.requireActivity()
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        var previous: AccessibilityNodeInfo? = null
        var consecutive = 0
        var last: FocusSnapshot? = null
        try {
            journey.await("real TalkBack accessibility focus settled on the '$label' shipping tab") {
                assertTrue("Focus acquisition retains the exact live Activity",
                    journey.requireActivity() === activity && !activity.isFinishing && !activity.isDestroyed)
                assertTrue("Focus acquisition retains the exact native owner", journey.application.foregroundProduct === product)
                assertEquals("Focus acquisition retains the original graph", generation, product.state.value.graphGeneration)
                val window = focusedOwnedWindow(automation, journey)
                if (window == null) {
                    previous = null
                    consecutive = 0
                    return@await false
                }
                val current = focusSnapshot(journey, automation, label, window).also { last = it }
                assertEquals("Focus acquisition refuses a replacement window", windowId, current.windowId)
                consecutive = if (current.isTargetFocused) {
                    if (current.focused == previous) consecutive + 1 else 1
                } else 0
                previous = current.focused.takeIf { current.isTargetFocused }
                if (consecutive < 2) return@await false
                // Re-read the settled target inside the same original deadline. A transient
                // zero-focus gap resets acquisition rather than granting or replaying a gesture.
                val settledWindow = focusedOwnedWindow(automation, journey)
                if (settledWindow == null) {
                    previous = null
                    consecutive = 0
                    return@await false
                }
                val settled = focusSnapshot(journey, automation, label, settledWindow).also { last = it }
                assertEquals("Settled focus retains the original window", windowId, settled.windowId)
                val sameFocus = settled.isTargetFocused && settled.focused == current.focused
                if (!sameFocus) {
                    previous = null
                    consecutive = 0
                }
                sameFocus
            }
            // Report the exact actual-focus reread from the successful predicate above.
            // Each input admission independently rechecks it before any socket write.
            val current = checkNotNull(last)
            assertEquals(windowId, current.windowId)
            reportFocus("settled", label, current)
            assertTrue("The exact tab must still own fresh accessibility focus", current.isTargetFocused)
        } catch (failure: Throwable) {
            runCatching { last?.let { reportFocus("focus-failure", label, it) } }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    /** Exactly one intended swipe or double-tap must traverse the actual accessibility filter. */
    private fun gesture(journey: ReactEmulatorJourney, automation: UiAutomation, transport: TalkBackInputReaderTransport, viewport: Rect,
        swipeRight: Boolean, expectedFocus: String, assertOwned: () -> Unit) {
        assertOwned()
        assertTrue("The real gesture requires nonempty measured bounds", viewport.width() > 0 && viewport.height() > 0)
        val action = if (swipeRight) "swipe-right" else "double-tap"
        val before = focusSnapshot(journey, automation, expectedFocus)
        reportFocus("before-$action", expectedFocus, before)
        assertTrue("The intended gesture must start from the exact fresh focused tab", before.isTargetFocused)
        var failure: Throwable? = null
        try {
            val assertSource = {
                assertOwned()
                val current = focusSnapshot(journey, automation, expectedFocus)
                assertEquals("Never send a gesture against a replacement focused window", before.windowId, current.windowId)
                assertTrue("The exact refreshed shipping source must still own focus immediately before delivery",
                    current.isTargetFocused && current.target == before.target && current.focused == before.focused)
            }
            assertSource()
            transport.gesture(swipeRight, transportWitness(journey, before.windowId, checkNotNull(before.target).hashCode(), viewport),
                assertSource, assertOwned)
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            try {
                assertOwned()
                reportFocus("after-$action", expectedFocus, focusSnapshot(journey, automation, expectedFocus))
            } catch (diagnostic: Throwable) {
                if (failure != null) failure.addSuppressed(diagnostic) else throw diagnostic
            }
        }
    }

    private fun transportWitness(journey: ReactEmulatorJourney, windowId: Int, source: Int, viewport: Rect): JSONObject {
        var result: JSONObject? = null
        journey.instrumentation.runOnMainSync {
            if (Build.VERSION.SDK_INT < 36) throw AssertionError("The scoped InputReader fixture requires the owned API36 display")
            val activity = journey.requireActivity()
            assertTrue("Read geometry only from the live owned Activity", !activity.isFinishing && !activity.isDestroyed)
            assertEquals("The qualified controller serves only the real default display", 0, activity.display?.displayId)
            val display = activity.windowManager.currentWindowMetrics.bounds
            assertTrue("Require a nonempty default display rooted at physical pixel zero", display.left == 0 && display.top == 0 &&
                display.width() > 0 && display.height() > 0 && display.contains(viewport))
            result = JSONObject().put("pid", Process.myPid())
                .put("owner", System.identityHashCode(journey.application.foregroundProduct))
                .put("graph", checkNotNull(journey.application.foregroundProduct.state.value.graphGeneration))
                .put("window", windowId).put("source", source)
                .put("display", JSONObject().put("id", 0).put("width", display.width()).put("height", display.height()))
                .put("app_rect", JSONObject().put("left", viewport.left).put("top", viewport.top)
                    .put("right", viewport.right).put("bottom", viewport.bottom))
        }
        return checkNotNull(result)
    }

}
