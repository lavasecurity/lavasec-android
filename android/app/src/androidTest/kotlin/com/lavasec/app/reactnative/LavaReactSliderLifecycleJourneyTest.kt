package com.lavasec.app.reactnative

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.PromiseImpl
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import com.lavasec.app.settings.ProductSettingsStore
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NF-CMP-SLIDER: installed shipping Customization/SeekBar and real settings-owner receipts.
 * Touches use current native thumb geometry in the owned application window. No gallery,
 * replacement product, paid fixture, injected product receipt or direct durable write is used.
 * The disabled case explicitly applies the registered native leaf's disabled prop: shipping
 * Text size is always enabled while mounted, so that case does not qualify a disabled route.
 * Match System removal/replacement proves actual mount isolation, not a Fabric pool recycle.
 * This is emulator OS-touch/AX evidence, not physical hardware or the whole component matrix.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactSliderLifecycleJourneyTest {
    @Test
    fun shippingSliderRealDragParentScrollCancelRotateAndRemountUseDurableOwner() = withFixture { fixture ->
        val journey = fixture.journey
        fixture.manualText()
        openCustomization(journey)
        val initial = slider(journey, 3)
        TouchDrag(journey, initial).use { drag ->
            // Cross the measured native touch slop within one integer tick. Keep
            // the owner value and text layout unchanged while testing interception:
            // an acknowledged font-size change can legitimately resize/clamp a ScrollView.
            drag.moveTo(3, withinCurrentValue = true)
            assertPressed(journey, initial.view, true)
            fixture.assertOwned()
            journey.instrumentation.runOnMainSync { assertEquals(3, initial.view.progress) }
            drag.moveTo(3, verticalMotion = true, withinCurrentValue = true)
            assertParentPosition(journey, initial)
            drag.finish()
        }
        assertParentPosition(journey, initial)
        TouchDrag(journey, initial).use { drag ->
            drag.moveTo(0)
            fixture.acknowledgeText(0)
            assertPressed(journey, initial.view, true)
            drag.finish()
        }
        slider(journey, 0)
        val refused = fixture.refuseTextValue(8)
        assertEquals("The real settings owner must refuse an out-of-range value", "rejected", refused.getString("status"))
        assertEquals("invalid-setting-value", refused.getString("reason"))
        fixture.assertOwned()
        slider(journey, 0)

        rotate(journey, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        fixture.assertOwned()
        val rotated = slider(journey, 0)
        TouchDrag(journey, rotated).use { drag ->
            drag.moveTo(3)
            fixture.acknowledgeText(3)
            assertPressed(journey, rotated.view, true)
            drag.cancel()
        }
        assertPressed(journey, rotated.view, false)
        slider(journey, 3)

        rotate(journey, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        // Acquire the actual switch before beginning a touch. Do not navigate or
        // accidentally scroll another container while the thumb owns the pointer.
        val matchSystem = matchSystemSwitch(journey, false)
        val retiring = slider(journey, 3)
        val remountDrag = TouchDrag(journey, retiring)
        var removalFailure: Throwable? = null
        try {
            remountDrag.moveTo(0)
            fixture.acknowledgeText(0)
            assertPressed(journey, retiring.view, true)
            fixture.toggleMatchSystem(matchSystem, true)
            journey.await("Match System retires the exact dragged native leaf") {
                var retired = false
                journey.instrumentation.runOnMainSync {
                    retired = !retiring.view.isAttachedToWindow && !retiring.view.isPressed && nativeSliders(journey).isEmpty()
                }
                retired
            }
        } catch (failure: Throwable) {
            removalFailure = failure
            throw failure
        } finally {
            // The parent removal already delivered native cancellation. End the real
            // OS pointer in the same owned window; never dispatch to the retired view.
            val cleanup = runCatching(remountDrag::cancelAfterRemoval).exceptionOrNull()
            if (cleanup != null) {
                val original = removalFailure
                if (original != null) original.addSuppressed(cleanup) else throw cleanup
            }
        }
        fixture.toggleMatchSystem(matchSystemSwitch(journey, true), false)
        val replacement = slider(journey, 0)
        assertNotSame("A new manual-text mount cannot inherit the retired thumb's tracking state", retiring.view, replacement.view)
        assertPressed(journey, replacement.view, false)
        fixture.assertOwned()

        val oldActivity = journey.requireActivity()
        journey.reopen()
        assertTrue(oldActivity.isDestroyed)
        fixture.assertOwned()
        openCustomization(journey)
        val reopened = slider(journey, 0)
        assertNotSame("The recreated real Activity reads a new native leaf", replacement.view, reopened.view)
        TouchDrag(journey, reopened).use { drag ->
            drag.moveTo(3)
            fixture.acknowledgeText(3)
            drag.finish()
        }
        slider(journey, 3)
        fixture.assertOwned()
        journey.assertNoFallback()
        println("LAVA_UI_NATIVE_SLIDER shipping=real-touch owner=durable-ack-and-invalid-value-refusal parent-scroll=isolated cancel=actual rotate=landscape-portrait lifecycle=active-removal-and-new-Activity-remount pool-recycle=not-claimed")
    }

    @Test
    fun installedSliderDisabledPropRefusesRealInputAndReenabledDragReachesOwner() = withFixture { fixture ->
        val journey = fixture.journey
        fixture.manualText()
        openCustomization(journey)
        val target = slider(journey, 3)
        val manager = LavaSliderManager()
        journey.instrumentation.runOnMainSync { manager.disabled(target.view, true) }
        try {
            val disabled = slider(journey, 3, enabled = false)
            assertSame(target.view, disabled.view)
            val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 6f) }
            assertFalse("The actual disabled SeekBar must refuse an accessibility adjustment",
                disabled.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
            TouchDrag(journey, disabled, enabled = false).use { drag ->
                drag.moveTo(5)
                drag.finish()
            }
            journey.instrumentation.waitForIdleSync()
            slider(journey, 3, enabled = false)
            fixture.assertOwned()
        } finally {
            // Restore only the exact native object whose prop this controlled leaf test changed.
            journey.instrumentation.runOnMainSync { manager.disabled(target.view, false) }
        }
        val enabled = slider(journey, 3)
        assertSame(target.view, enabled.view)
        TouchDrag(journey, enabled).use { drag ->
            drag.moveTo(0)
            fixture.acknowledgeText(0)
            drag.finish()
        }
        slider(journey, 0)
        fixture.assertOwned()
        journey.assertNoFallback()
        println("LAVA_UI_NATIVE_SLIDER disabled=controlled-installed-native-prop touch-and-AX-input=refused durable-state=unchanged reenabled=real-owner-ack shipping-disabled-policy=not-claimed")
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

    private class Fixture(val journey: ReactEmulatorJourney) {
        private val product = journey.application.foregroundProduct
        private val module = checkNotNull(journey.application.reactHost.currentReactContext?.getNativeModule(LavaAppModule::class.java))
        private val graph = checkNotNull(product.state.value.graphGeneration)
        private val library = checkNotNull(product.currentFilterLibrary())
        private val logging = checkNotNull(product.currentQaLogging())
        private val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
        private val store = ProductSettingsStore(journey.context)
        private val original = store.loadPreferences()
        private var owned = original
        private val originalOrientation = journey.requireActivity().requestedOrientation
        private val inputMethods = InputMethods.capture(journey.context)

        init { assertFalse("Exercise the real Free product, never install a paid fixture", plus) }

        fun assertOwned() {
            assertSame("Refuse a successor product", product, journey.application.foregroundProduct)
            assertSame("Refuse a successor native command owner", module,
                journey.application.reactHost.currentReactContext?.getNativeModule(LavaAppModule::class.java))
            assertEquals("Refuse a successor credential graph", graph, product.state.value.graphGeneration)
            assertEquals(library, product.currentFilterLibrary())
            assertEquals(logging, product.currentQaLogging())
            assertEquals(plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            assertEquals("Only the exact acknowledged settings delta remains test-owned", owned, store.loadPreferences())
            assertEquals("Preserve the selected IME and enabled IMEs/explicit/effective subtypes", inputMethods,
                InputMethods.capture(journey.context))
        }

        fun manualText() {
            set(ProductPreferenceKey.MATCH_TEXT_SIZE, PreferenceValue.Bool(false))
            set(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(3))
            rotate(journey, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        }

        fun acknowledgeText(value: Int) {
            owned = owned.set(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(value)).getOrThrow()
            awaitPreferences()
        }

        fun refuseTextValue(value: Int): JSONObject = settingCommand(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(value))

        fun toggleMatchSystem(node: AccessibilityNodeInfo, value: Boolean) {
            assertOwned()
            assertTrue(node.refresh() && node.isVisibleToUser && node.isEnabled && node.isCheckable)
            assertEquals(!value, node.isChecked)
            assertTrue("Submit one real Match System switch action", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            owned = owned.set(ProductPreferenceKey.MATCH_TEXT_SIZE, PreferenceValue.Bool(value)).getOrThrow()
            awaitPreferences()
        }

        private fun awaitPreferences() {
            journey.await("the exact native owner and durable settings acknowledge this test's delta") {
                assertSame(product, journey.application.foregroundProduct)
                assertEquals(graph, product.state.value.graphGeneration)
                val snapshot = journey.snapshot().getJSONObject("preferences")
                store.loadPreferences() == owned && snapshot.getInt("textSize") == owned.textSize.index &&
                    snapshot.getBoolean("matchTextSize") == owned.matchTextSize
            }
            assertOwned()
        }

        private fun set(key: ProductPreferenceKey, value: PreferenceValue) {
            assertOwned()
            val expected = owned.set(key, value).getOrThrow()
            val response = settingCommand(key, value)
            assertEquals("The actual original native settings owner must acknowledge the write", "completed", response.getString("status"))
            owned = expected
            awaitPreferences()
        }

        private fun settingCommand(key: ProductPreferenceKey, value: PreferenceValue): JSONObject {
            assertOwned()
            val wire: Any = when (value) {
                is PreferenceValue.Bool -> value.value
                is PreferenceValue.IntValue -> value.value
                is PreferenceValue.Text -> value.value
            }
            // Capture the original generation explicitly instead of rediscovering a
            // successor snapshot between the cleanup witness and mutation admission.
            val request = JSONObject().put("schema", 1).put("generation", graph.toString())
                .put("type", "settings.set").put("key", key.wire).put("value", wire)
            val done = CountDownLatch(1)
            val response = AtomicReference<String?>()
            val rejected = AtomicBoolean(false)
            module.command(request.toString(), PromiseImpl(
                Callback { arguments -> response.set(arguments.firstOrNull() as? String); done.countDown() },
                Callback { rejected.set(true); done.countDown() },
            ))
            assertTrue("The original settings command must settle", done.await(20, TimeUnit.SECONDS))
            assertFalse("The native settings result must be typed", rejected.get())
            return JSONObject(checkNotNull(response.get()) { "Native settings command returned no JSON" })
        }

        fun restore() {
            // Do not replace settings following graph retirement, another user's mutation,
            // an unacknowledged thumb edit or an unrelated library/retention change.
            assertOwned()
            if (owned.textSize != original.textSize) set(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(original.textSize.index))
            if (owned.matchTextSize != original.matchTextSize) set(ProductPreferenceKey.MATCH_TEXT_SIZE, PreferenceValue.Bool(original.matchTextSize))
            assertEquals(original, store.loadPreferences())
            assertOwned()
            journey.instrumentation.runOnMainSync {
                assertSame(product, journey.application.foregroundProduct)
                assertEquals(graph, product.state.value.graphGeneration)
                journey.requireActivity().requestedOrientation = originalOrientation
            }
        }
    }

    private data class SliderTarget(val view: SeekBar, val parent: ScrollView, val node: AccessibilityNodeInfo, val scrollY: Int)

    private data class TouchGeometry(
        val requested: Int?, val verticalMotion: Boolean, val withinCurrentValue: Boolean,
        val progress: Int, val full: Rect, val clipped: Rect, val thumb: Rect,
        val paddingLeft: Int, val paddingRight: Int, val thumbOffset: Int,
        val intrinsicWidth: Int, val touchTrack: Int, val drawingTrack: Int,
        val parentY: Int, val point: Pair<Float, Float>,
    )

    private class TouchDrag(private val journey: ReactEmulatorJourney, private val target: SliderTarget,
        private val enabled: Boolean = true) : AutoCloseable {
        private val activity = journey.requireActivity()
        private val product = journey.application.foregroundProduct
        private val generation = checkNotNull(product.state.value.graphGeneration)
        private val downTime = SystemClock.uptimeMillis()
        private val moveDeadline = downTime + 20_000L
        private var previousEventTime: Long? = null
        private val window = activity.window
        private val originalCallback = checkNotNull(window.callback)
        private val touchObserver = object : Window.Callback by originalCallback {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // Observe only this fixture's pointer through the public window callback.
                // Delegating exactly once leaves SeekBar and React's handlers intact.
                val ownedPointer = event.downTime == downTime &&
                    (event.source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                if (ownedPointer) recordDeliveredTouch("before", event)
                return originalCallback.dispatchTouchEvent(event).also {
                    if (ownedPointer) recordDeliveredTouch("after", event)
                }
            }
        }
        private var observing = false
        private var active = false
        private var lastPoint = 0f to 0f
        init {
            val sample = geometry(journey, target, null, enabled)
            journey.instrumentation.runOnMainSync {
                assertSame(activity, journey.requireActivity())
                assertSame(product, journey.application.foregroundProduct)
                assertEquals(generation, product.state.value.graphGeneration)
                assertSame("Never replace a successor window callback", originalCallback, window.callback)
                window.callback = touchObserver
                observing = true
            }
            lastPoint = sample.point
            active = true
            try { inject(MotionEvent.ACTION_DOWN, sample.point, sample) } catch (failure: Throwable) {
                runCatching { cancelAfterRemoval() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { stopObserving() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        private fun recordDeliveredTouch(phase: String, event: MotionEvent) {
            val view = target.view
            val screen = IntArray(2).also(view::getLocationOnScreen)
            val localWindow = IntArray(2).also(view::getLocationInWindow)
            // Historical points are window-local. Bound the receipt to the last three
            // public samples; there is no entered text, account or other node payload.
            val history = (maxOf(0, event.historySize - 3) until event.historySize).joinToString(";") {
                "${event.getHistoricalEventTime(it)}:${event.getHistoricalX(it)},${event.getHistoricalY(it)}"
            }
            println("LAVA_UI_NATIVE_SLIDER_TOUCH phase=delivered-$phase action=${event.actionMasked} " +
                "window=${target.node.windowId} view=${System.identityHashCode(view)} " +
                "ownerCurrent=${activity === runCatching { journey.requireActivity() }.getOrNull() && product === journey.application.foregroundProduct && generation == product.state.value.graphGeneration} " +
                "attached=${view.isAttachedToWindow} enabled=${view.isEnabled} pressed=${view.isPressed} " +
                "progress=${view.progress} min=${view.min} max=${view.max} " +
                "screenOrigin=${screen[0]},${screen[1]} windowOrigin=${localWindow[0]},${localWindow[1]} " +
                "size=${view.width}x${view.height} padding=${view.paddingLeft},${view.paddingRight} thumb=${view.thumb?.bounds} " +
                "source=${event.source} tool=${event.getToolType(0)} pointers=${event.pointerCount} " +
                "downTime=${event.downTime} eventTime=${event.eventTime} " +
                "raw=${event.rawX},${event.rawY} windowLocal=${event.x},${event.y} " +
                "leafLocal=${event.x - localWindow[0]},${event.y - localWindow[1]} " +
                "historySize=${event.historySize} history=[$history]")
        }
        private fun stopObserving() {
            if (!observing) return
            journey.instrumentation.runOnMainSync {
                assertSame("Only restore this fixture's window callback", touchObserver, window.callback)
                window.callback = originalCallback
                observing = false
            }
        }
        fun moveTo(value: Int, verticalMotion: Boolean = false, withinCurrentValue: Boolean = false) {
            check(active)
            val sample = geometry(journey, target, value, enabled, verticalMotion, withinCurrentValue)
            lastPoint = sample.point
            inject(MotionEvent.ACTION_MOVE, sample.point, sample)
        }
        fun finish() = end(MotionEvent.ACTION_UP, requireAttached = true)
        fun cancel() = end(MotionEvent.ACTION_CANCEL, requireAttached = true)
        fun cancelAfterRemoval() = end(MotionEvent.ACTION_CANCEL, requireAttached = false)
        private fun end(action: Int, requireAttached: Boolean) {
            if (!active) return
            if (requireAttached) journey.instrumentation.runOnMainSync {
                // A settings acknowledgement can legitimately relayout the text preview
                // during the gesture. Release the captured OS pointer, without inventing
                // a fresh thumb hit test at the old coordinates or scrolling mid-drag.
                assertTrue(nativeSliders(journey).singleOrNull() === target.view &&
                    target.view.isAttachedToWindow && scrollParent(target.view) === target.parent)
            }
            inject(action, lastPoint)
            active = false
            stopObserving()
        }
        private fun inject(action: Int, point: Pair<Float, Float>, sample: TouchGeometry? = null) {
            if (action == MotionEvent.ACTION_MOVE) {
                // Android's InputConsumer/LegacyResampler extrapolates samples up to
                // 20ms apart. A fast single MOVE can be delivered beyond its submitted
                // coordinate (the retained landscape receipt was 320 -> 402). Space
                // real samples by 21ms instead of replaying input, altering coordinates
                // or disabling resampling. This wait stays inside the control's 20s bound.
                val earliest = checkNotNull(previousEventTime) + 21L
                val now = SystemClock.uptimeMillis()
                assertTrue("Real MOVE sample spacing must fit the existing control deadline",
                    now < moveDeadline && earliest < moveDeadline)
                if (earliest > now) SystemClock.sleep(earliest - now)
                assertTrue("Real MOVE sample spacing must finish within the existing control deadline",
                    SystemClock.uptimeMillis() < moveDeadline)
            }
            assertSame("A pointer cannot act through a successor Activity", activity, journey.requireActivity())
            assertSame("A pointer cannot act through a successor product owner", product, journey.application.foregroundProduct)
            assertEquals("A pointer cannot act through a successor graph", generation, product.state.value.graphGeneration)
            ownedWindow(journey, target.node.windowId, point)
            if (sample != null) {
                val current = geometry(journey, target, sample.requested, enabled, sample.verticalMotion, sample.withinCurrentValue)
                println("LAVA_UI_NATIVE_SLIDER_TOUCH phase=before action=$action window=${target.node.windowId} sample=[$sample] current=[$current]")
                assertEquals("Never inject coordinates captured before a native layout/value change", sample, current)
            }
            val eventTime = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_MOVE) {
                assertTrue("Admitted MOVE coordinates retain the original control deadline", eventTime < moveDeadline)
                assertTrue("A real MOVE must be outside Android's 20ms extrapolation interval",
                    eventTime - checkNotNull(previousEventTime) > 20L)
            }
            println("LAVA_UI_NATIVE_SLIDER_TOUCH phase=submitted action=$action window=${target.node.windowId} " +
                "downTime=$downTime eventTime=$eventTime previousEventTime=$previousEventTime point=$point")
            val event = MotionEvent.obtain(downTime, eventTime, action, point.first, point.second, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue("The owned application window must accept the actual slider touch action=$action",
                    journey.instrumentation.uiAutomation.injectInputEvent(event, true))
                previousEventTime = eventTime
            } finally { event.recycle() }
            journey.instrumentation.runOnMainSync {
                val view = target.view
                val origin = IntArray(2).also(view::getLocationOnScreen)
                println("LAVA_UI_NATIVE_SLIDER_TOUCH phase=after action=$action window=${target.node.windowId} " +
                    "view=${System.identityHashCode(view)} attached=${view.isAttachedToWindow} pressed=${view.isPressed} enabled=${view.isEnabled} " +
                    "progress=${view.progress} min=${view.min} max=${view.max} origin=${origin[0]},${origin[1]} size=${view.width}x${view.height} " +
                    "padding=${view.paddingLeft},${view.paddingTop},${view.paddingRight},${view.paddingBottom} " +
                    "thumb=${view.thumb?.bounds} thumbOffset=${view.thumbOffset} parentY=${target.parent.scrollY} point=$point")
                if (sample != null && action == MotionEvent.ACTION_MOVE) assertEquals(
                    "The delivered single MOVE must reach its exact native tick before the durable owner assertion",
                    if (enabled) checkNotNull(sample.requested) else sample.progress, view.progress)
            }
            if (sample != null && action == MotionEvent.ACTION_DOWN) {
                val delivered = geometry(journey, target, null, enabled)
                assertEquals("The delivered DOWN must retain the exact measured value, thumb and layout before any MOVE", sample, delivered)
            }
        }
        override fun close() {
            var primary: Throwable? = null
            try { cancelAfterRemoval() } catch (failure: Throwable) { primary = failure; throw failure } finally {
                val cleanup = runCatching(::stopObserving).exceptionOrNull()
                if (cleanup != null) { val original = primary; if (original != null) original.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }

    private fun withFixture(exercise: (Fixture) -> Unit) {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured(); journey.waitProtectionOff()
            assertEquals("The qualification uses the actual English emulator locale", "en", Locale.getDefault().language)
            val fixture = Fixture(journey)
            var primary: Throwable? = null
            try { exercise(fixture) } catch (failure: Throwable) { primary = failure; throw failure } finally {
                val cleanup = runCatching(fixture::restore).exceptionOrNull()
                if (cleanup != null) { val original = primary; if (original != null) original.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }

    private companion object {
        fun openCustomization(journey: ReactEmulatorJourney) {
            journey.click("Settings", prefix = true, scroll = false)
            journey.scrollToTop(); journey.click("Customization")
            journey.waitLabel("Match System")
        }

        fun views(view: View): List<View> = buildList {
            add(view)
            if (view is ViewGroup) repeat(view.childCount) { addAll(views(view.getChildAt(it))) }
        }

        fun nativeSliders(journey: ReactEmulatorJourney): List<SeekBar> =
            views(journey.requireActivity().window.decorView).filterIsInstance<SeekBar>()
                .filter { it.isShown && it.contentDescription?.toString().equals("Text size", ignoreCase = true) }

        fun scrollParent(view: View): ScrollView {
            var parent = view.parent
            while (parent != null) { if (parent is ScrollView) return parent; parent = parent.parent }
            error("The real shipping control must retain its actual scroll owner")
        }

        private data class ControlGeometry(val view: View, val parent: ScrollView, val y: Int,
            val target: Rect, val clipped: Rect, val viewport: Rect, val parentFrame: Rect, val source: AccessibilityNodeInfo,
            val parentSource: AccessibilityNodeInfo, val enabled: Boolean)

        fun bringControl(journey: ReactEmulatorJourney, label: String, control: (View) -> Boolean): View {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            val activity = journey.requireActivity()
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            var lastGeometry = "not measured"
            var lastSources = "not acquired"
            fun geometry(): ControlGeometry? {
                var result: ControlGeometry? = null
                journey.instrumentation.runOnMainSync {
                    assertTrue("Control navigation retains the exact current Activity",
                        journey.requireActivity() === activity && !activity.isFinishing && !activity.isDestroyed)
                    assertSame("Control navigation retains the exact product owner", product, journey.application.foregroundProduct)
                    assertEquals("Control navigation retains the original graph", generation, product.state.value.graphGeneration)
                    val decor = activity.window.decorView
                    val candidates = views(decor).filter { control(it) &&
                        it.contentDescription?.toString().equals(label, ignoreCase = true) && it.isAttachedToWindow && it.isShown }
                    assertTrue("The native '$label' control must be unambiguous", candidates.size <= 1)
                    val view = candidates.singleOrNull() ?: run { lastGeometry = "attachedShownControlCount=0"; return@runOnMainSync }
                    val parent = scrollParent(view)
                    assertTrue("The control and its exact scroll owner belong to this current window",
                        view.rootView === decor && parent.rootView === decor)
                    val origin = IntArray(2).also(view::getLocationOnScreen)
                    val target = Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
                    val rootOrigin = IntArray(2).also(decor::getLocationOnScreen)
                    // getGlobalVisibleRect is in root coordinates, not getLocationOnScreen's
                    // coordinate space. Normalize both clipped rectangles before comparison.
                    val viewport = Rect()
                    val parentVisible = parent.getGlobalVisibleRect(viewport)
                    viewport.offset(rootOrigin[0], rootOrigin[1])
                    val parentOrigin = IntArray(2).also(parent::getLocationOnScreen)
                    val parentFrame = Rect(parentOrigin[0], parentOrigin[1],
                        parentOrigin[0] + parent.width, parentOrigin[1] + parent.height)
                    val clipped = Rect()
                    val viewVisible = view.getGlobalVisibleRect(clipped)
                    if (viewVisible) clipped.offset(rootOrigin[0], rootOrigin[1])
                    lastGeometry = "view=${System.identityHashCode(view)} size=${view.width}x${view.height} target=$target clipped=$clipped " +
                        "parent=${System.identityHashCode(parent)} attached=${parent.isAttachedToWindow} shown=${parent.isShown} " +
                        "size=${parent.width}x${parent.height} visible=$parentVisible viewport=$viewport frame=$parentFrame y=${parent.scrollY} rootOffset=${rootOrigin[0]},${rootOrigin[1]}"
                    if (target.isEmpty || !parent.isAttachedToWindow || !parent.isShown || parent.width <= 0 ||
                        parent.height <= 0 || !parentVisible || viewport.isEmpty) return@runOnMainSync
                    // After rotation the clipped viewport can settle before the ScrollView's
                    // physical height. SHOW_ON_SCREEN uses that height, so admitting it early
                    // can under-scroll even though two clipped viewport samples are identical.
                    // Await the full current scroll frame before spending the one reveal.
                    if (parentFrame != viewport || parent.isLayoutRequested) return@runOnMainSync
                    val source = view.createAccessibilityNodeInfo()
                    val parentSource = parent.createAccessibilityNodeInfo()
                    val window = decor.createAccessibilityNodeInfo().windowId
                    if (window < 0 || source.windowId != window || parentSource.windowId != window) return@runOnMainSync
                    result = ControlGeometry(view, parent, parent.scrollY, target, clipped, viewport, parentFrame, source, parentSource, view.isEnabled)
                }
                return result
            }
            fun sameLayout(first: ControlGeometry, second: ControlGeometry) =
                first.view === second.view && first.parent === second.parent && first.source == second.source &&
                    first.parentSource == second.parentSource && first.y == second.y && first.target == second.target &&
                    first.clipped == second.clipped && first.viewport == second.viewport &&
                    first.parentFrame == second.parentFrame && first.enabled == second.enabled

            var pinned: ControlGeometry? = null
            var previous: ControlGeometry? = null
            var submitted = false
            var result: View? = null
            try {
                journey.await("the exact current native '$label' control is fully visible", maxOf(1L, deadline - SystemClock.elapsedRealtime())) {
                    val current = geometry() ?: run { previous = null; return@await false }
                    val original = pinned
                    if (original != null) assertTrue("Navigation never substitutes a control or scroll owner",
                        original.view === current.view && original.parent === current.parent &&
                            original.source == current.source && original.parentSource == current.parentSource)
                    val prior = previous
                    previous = current
                    if (prior == null || !sameLayout(prior, current)) return@await false
                    val nodes = journey.accessibilityNodes()
                    val parents = nodes.filter { it == current.parentSource && it.packageName?.toString() == journey.context.packageName }
                    val controls = nodes.filter { it == current.source && it.packageName?.toString() == journey.context.packageName }
                    assertTrue("The connected exact native sources must remain unambiguous", parents.size <= 1 && controls.size <= 1)
                    lastSources = "window=${current.source.windowId} control=${current.source.hashCode()} connected=${controls.size} " +
                        "parent=${current.parentSource.hashCode()} connected=${parents.size}"
                    val parent = parents.singleOrNull() ?: return@await false
                    val node = controls.singleOrNull() ?: return@await false
                    if (!parent.refresh() || parent != current.parentSource || !parent.isVisibleToUser || !parent.isEnabled ||
                        !node.refresh() || node != current.source || !labelled(node, label) || node.isEnabled != current.enabled) return@await false
                    lastSources += " class=${node.className} visible=${node.isVisibleToUser} enabled=${node.isEnabled} " +
                        "actions=${node.actionList.map { it.id }} parentScrollable=${parent.isScrollable}"
                    val confirmed = geometry() ?: return@await false
                    if (!sameLayout(current, confirmed)) { previous = confirmed; return@await false }
                    pinned = current
                    if (current.viewport.contains(current.target) && current.clipped.contains(current.target)) {
                        if (!node.isVisibleToUser) return@await false // Await AX hydration without scrolling an already visible leaf.
                        val accessible = Rect().also(node::getBoundsInScreen)
                        lastSources += " bounds=$accessible expectedNative=${current.target}"
                        // Rotation/font layout can settle before this exact connected AX
                        // source publishes new bounds. Await coherence, without another reveal.
                        if (accessible != current.target) return@await false
                        assertEquals("The connected control identifies its full native screen bounds", current.target, accessible)
                        result = current.view
                        return@await true
                    }
                    if (submitted) return@await false // Await final AX/layout hydration; never replay an accepted reveal.
                    assertTrue("A vertical reveal cannot repair horizontal clipping or an oversized control",
                        current.target.left >= current.viewport.left && current.target.right <= current.viewport.right &&
                            current.target.height() <= current.viewport.height())
                    val forward = current.target.bottom > current.viewport.bottom
                    assertTrue("The actual control must require directional vertical scrolling",
                        forward || current.target.top < current.viewport.top)
                    assertTrue("The exact native scroll owner must actually be scrollable", parent.isScrollable)
                    val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                    assertTrue("The connected native leaf must expose its public reveal action", node.actionList.any { it.id == action })
                    println("LAVA_UI_NATIVE_SLIDER_REVEAL_BEGIN label=$label $lastSources native=[$lastGeometry] forward=$forward")
                    assertTrue("The exact native leaf refused its one accessibility reveal", node.performAction(action))
                    submitted = true
                    var settled: ControlGeometry? = null
                    var sample: ControlGeometry? = null
                    val settleDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 1_500)
                    while (SystemClock.elapsedRealtime() < settleDeadline) {
                        val after = geometry()
                        if (after == null) { sample = null; SystemClock.sleep(100); continue }
                        assertTrue("The accepted reveal retains its exact native control and scroll owner",
                            after.view === current.view && after.parent === current.parent && after.source == current.source &&
                                after.parentSource == current.parentSource)
                        assertEquals("The actual scroll viewport must remain settled", current.viewport, after.viewport)
                        val directional = if (forward) after.y > current.y else after.y < current.y
                        if (directional && sample?.let { sameLayout(it, after) } == true) { settled = after; break }
                        sample = after
                        SystemClock.sleep(100)
                    }
                    assertNotNull("Accepted reveal requires actual settled directional scroll progress", settled)
                    println("LAVA_UI_NATIVE_SLIDER_REVEAL_END label=$label beforeY=${current.y} afterY=${checkNotNull(settled).y} native=[$lastGeometry]")
                    previous = settled
                    false
                }
            } catch (failure: Throwable) {
                println("LAVA_UI_NATIVE_SLIDER_REVEAL_FAILURE label=$label submitted=$submitted ax=[$lastSources] native=[$lastGeometry]")
                throw failure
            }
            return checkNotNull(result)
        }

        fun labelled(node: AccessibilityNodeInfo, label: String): Boolean =
            listOf(node.text, node.contentDescription).any { it?.toString().equals(label, ignoreCase = true) }

        fun slider(journey: ReactEmulatorJourney, value: Int, enabled: Boolean = true): SliderTarget {
            val view = bringControl(journey, "Text size") { it is SeekBar } as SeekBar
            var result: SliderTarget? = null
            var previous: TouchGeometry? = null
            var previousWindow: Rect? = null
            journey.await("one exact installed SeekBar with its current range, value, native geometry and owned window bounds") {
                val candidates = journey.accessibilityNodes().filter { it.packageName?.toString() == journey.context.packageName &&
                    it.isVisibleToUser && labelled(it, "Text size") && it.className?.toString()?.contains("SeekBar") == true }
                assertTrue("The slider accessibility target must be unambiguous", candidates.size <= 1)
                val node = candidates.singleOrNull() ?: return@await false
                if (!node.refresh() || node.isEnabled != enabled || node.rangeInfo?.current != value.toFloat()) return@await false
                assertEquals(0f, checkNotNull(node.rangeInfo).min, 0f)
                assertEquals(6f, checkNotNull(node.rangeInfo).max, 0f)
                var candidate: SliderTarget? = null
                journey.instrumentation.runOnMainSync {
                    assertTrue(nativeSliders(journey).singleOrNull() === view && view.isAttachedToWindow)
                    if (view.max == 6 && view.progress == value && view.isEnabled == enabled) {
                        val position = IntArray(2).also(view::getLocationOnScreen)
                        val native = Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
                        val accessible = Rect().also(node::getBoundsInScreen)
                        if (accessible != native) return@runOnMainSync // The same source may still be hydrating after layout.
                        assertEquals("Touch geometry must identify the current actual native leaf", native, accessible)
                        val parent = scrollParent(view)
                        if (currentThumbContainsTouchValue(view)) candidate = SliderTarget(view, parent, node, parent.scrollY)
                    }
                }
                val current = candidate
                if (current != null) {
                    val sample = geometry(journey, current, null, enabled)
                    val window = ownedWindowBounds(journey, current.node.windowId)
                    // A settled leaf after Activity reopen does not prove window readiness.
                    // Admit no pointer until that same application window contains the
                    // native thumb and both geometries have settled.
                    if (window == null || !window.contains(sample.point.first.toInt(), sample.point.second.toInt())) {
                        previous = null
                        previousWindow = null
                        return@await false
                    }
                    if (sample == previous && window == previousWindow) result = current
                    previous = sample
                    previousWindow = window
                } else {
                    previous = null
                    previousWindow = null
                }
                result != null
            }
            return checkNotNull(result)
        }

        fun matchSystemSwitch(journey: ReactEmulatorJourney, checked: Boolean): AccessibilityNodeInfo {
            bringControl(journey, "Match System") { it is CompoundButton }
            var result: AccessibilityNodeInfo? = null
            journey.await("one current actual Match System switch") {
                val matches = journey.accessibilityNodes().filter { it.packageName?.toString() == journey.context.packageName &&
                    it.isVisibleToUser && it.isEnabled && it.isCheckable && labelled(it, "Match System") &&
                    it.className?.toString()?.contains("Switch") == true }
                assertTrue("The real Match System switch must be unambiguous", matches.size <= 1)
                result = matches.singleOrNull()?.takeIf { it.refresh() && it.isChecked == checked }
                result != null
            }
            return checkNotNull(result)
        }

        // AbsSeekBar maps touch x using width minus padding. Its drawable position uses a
        // different track, and API36 retains the initial finger-to-value grip offset. Begin
        // at the actual input value inside the painted thumb so that offset is zero; do not
        // mistake a stale drawable or a previous orientation's bounds for a ready thumb.
        fun currentThumbContainsTouchValue(view: SeekBar): Boolean {
            val thumb = view.thumb ?: return false
            if (view.min != 0 || view.max != 6 || view.layoutDirection != View.LAYOUT_DIRECTION_LTR) return false
            val width = view.width - view.paddingLeft - view.paddingRight
            if (width <= 0 || thumb.bounds.isEmpty) return false
            val x = view.paddingLeft + width * view.progress / view.max.toFloat()
            val left = view.paddingLeft - view.thumbOffset + thumb.bounds.left
            val right = view.paddingLeft - view.thumbOffset + thumb.bounds.right
            return x >= left && x < right
        }

        fun geometry(journey: ReactEmulatorJourney, target: SliderTarget, value: Int?, enabled: Boolean,
            verticalMotion: Boolean = false, withinCurrentValue: Boolean = false): TouchGeometry {
            var sample: TouchGeometry? = null
            journey.instrumentation.runOnMainSync {
                val view = target.view
                assertTrue("A touch cannot reuse a detached or replaced native slider",
                    nativeSliders(journey).singleOrNull() === view && view.isAttachedToWindow && view.isShown &&
                        scrollParent(view) === target.parent && view.isEnabled == enabled)
                assertEquals("Coordinates retain the exact connected public native AX source", target.node, view.createAccessibilityNodeInfo())
                val thumb = checkNotNull(view.thumb)
                val origin = IntArray(2).also(view::getLocationOnScreen)
                val full = Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
                val visible = Rect()
                assertFalse(full.isEmpty)
                assertTrue("A new drag sample must use a currently visible native thumb", view.getGlobalVisibleRect(visible))
                val rootOrigin = IntArray(2).also(view.rootView::getLocationOnScreen)
                visible.offset(rootOrigin[0], rootOrigin[1])
                val track = view.width - view.paddingLeft - view.paddingRight
                assertTrue(track > 0 && view.min == 0 && view.max == 6)
                assertEquals("The English fixture uses the native left-to-right touch track", View.LAYOUT_DIRECTION_LTR, view.layoutDirection)
                val requested = value ?: view.progress
                assertTrue(requested in view.min..view.max)
                if (value == null) assertTrue("The actual input value must lie inside the current painted thumb",
                    currentThumbContainsTouchValue(view))
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                val horizontalOffset = if (withinCurrentValue) {
                    val offset = slop + 1f
                    assertTrue("The measured track must permit a drag inside the same integer tick",
                        offset < track / (view.max * 2f) - 1f)
                    offset
                } else 0f
                val x = origin[0] + view.paddingLeft + track * requested / view.max.toFloat() + horizontalOffset
                val y = origin[1] + view.paddingTop + thumb.bounds.exactCenterY() +
                    if (verticalMotion) slop * 2f else 0f
                assertTrue("Every horizontal sample remains inside the fully visible actual native control", x >= visible.left && x < visible.right)
                if (value == null) assertTrue("A DOWN must hit the actual visible painted thumb", visible.contains(x.toInt(), y.toInt()))
                sample = TouchGeometry(value, verticalMotion, withinCurrentValue, view.progress, full, visible, Rect(thumb.bounds),
                    view.paddingLeft, view.paddingRight, view.thumbOffset, thumb.intrinsicWidth, track,
                    track - thumb.intrinsicWidth + 2 * view.thumbOffset, target.parent.scrollY, x to y)
            }
            return checkNotNull(sample)
        }

        private fun ownedWindowBounds(journey: ReactEmulatorJourney, windowId: Int): Rect? {
            val matches = journey.instrumentation.uiAutomation.windows.filter { it.id == windowId &&
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
            assertTrue("The exact owned application window must be unambiguous", matches.size <= 1)
            val window = matches.singleOrNull() ?: return null
            val root = window.root ?: return null
            if (!root.refresh()) return null
            assertEquals(journey.context.packageName, root.packageName?.toString())
            assertEquals("The window root retains the captured AX window identity", windowId, root.windowId)
            return Rect().also(window::getBoundsInScreen).takeUnless { it.isEmpty }
        }

        fun ownedWindow(journey: ReactEmulatorJourney, windowId: Int, point: Pair<Float, Float>) {
            val bounds = ownedWindowBounds(journey, windowId)
            assertNotNull("Every physical event requires its original active owned application window $windowId", bounds)
            assertTrue("Every physical event stays in the original owned application window: id=$windowId bounds=$bounds point=$point",
                checkNotNull(bounds).contains(point.first.toInt(), point.second.toInt()))
        }

        fun assertPressed(journey: ReactEmulatorJourney, view: SeekBar, pressed: Boolean) {
            journey.instrumentation.runOnMainSync { assertEquals("The real native thumb's touch lifecycle", pressed, view.isPressed) }
        }

        fun assertParentPosition(journey: ReactEmulatorJourney, target: SliderTarget) {
            journey.instrumentation.runOnMainSync {
                assertTrue(target.parent.isAttachedToWindow && scrollParent(target.view) === target.parent)
                assertEquals("The thumb's physical drag must not scroll its real parent", target.scrollY, target.parent.scrollY)
            }
        }

        fun rotate(journey: ReactEmulatorJourney, orientation: Int) {
            journey.instrumentation.runOnMainSync { journey.requireActivity().requestedOrientation = orientation }
            val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
            journey.await("the actual Activity reaches orientation=$expected") {
                var actual = 0
                journey.instrumentation.runOnMainSync { actual = journey.requireActivity().resources.configuration.orientation }
                actual == expected
            }
        }
    }
}
