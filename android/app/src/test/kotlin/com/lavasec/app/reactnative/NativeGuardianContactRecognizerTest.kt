package com.lavasec.app.reactnative

import android.app.Application
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.uimanager.ReactStylesDiffMap
import com.facebook.react.uimanager.ThemedReactContext
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeGuardianContactRecognizerTest {
    private companion object {
        // These Android callbacks are protected on the final production leaf. Invoke their
        // actual implementations without a public production hook or a competing renderer.
        fun attach(view: LavaDecorationView) {
            LavaDecorationView::class.java.getDeclaredMethod("onAttachedToWindow")
                .apply { isAccessible = true }.invoke(view)
        }
        fun detach(view: LavaDecorationView) {
            LavaDecorationView::class.java.getDeclaredMethod("onDetachedFromWindow")
                .apply { isAccessible = true }.invoke(view)
        }
        fun windowVisibility(view: LavaDecorationView, visibility: Int) {
            LavaDecorationView::class.java.getDeclaredMethod("onWindowVisibilityChanged", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(view, visibility)
        }
    }
    private class Fixture {
        val events = mutableListOf<String>()
        @Suppress("DEPRECATION")
        private val context = RuntimeEnvironment.getApplication().let { application ->
            // RN 0.87 exposes this concrete context for Java-only native-view tests. Themed
            // context matches the real manager without starting a second JS/Fabric renderer.
            ThemedReactContext(BridgeReactContext(application), application, LavaReactActivity.COMPONENT_NAME, 1)
        }
        val view = LavaDecorationView(context).apply {
            symbol = "guardian.gestures"; guardianGestures = true; guardianGestureEmitter = { events += it }
            // Exercise the real native leaf's lifecycle gates without creating a second renderer.
            attach(this); windowVisibility(this, View.VISIBLE); onWindowFocusChanged(true); onHostResume()
        }
        fun touch(action: Int, x: Float = 30f, y: Float = 30f): Boolean {
            val event = MotionEvent.obtain(0L, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        fun idle(millis: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)) }
        fun close() { detach(view) }
    }

    @Test fun `tap emits native contact start end tap with no competing drawing or haptics`() {
        val fixture = Fixture()
        assertTrue(fixture.touch(MotionEvent.ACTION_DOWN)); fixture.idle(80)
        assertTrue(fixture.touch(MotionEvent.ACTION_UP))
        assertEquals(listOf("start", "end", "tap"), fixture.events)
        fixture.idle(1300); assertEquals(3, fixture.events.size); fixture.close()
    }

    @Test fun `held contact reveals at exactly 1200ms and never also taps`() {
        val fixture = Fixture()
        fixture.touch(MotionEvent.ACTION_DOWN); fixture.idle(1199)
        assertEquals(listOf("start"), fixture.events)
        fixture.idle(1); assertEquals(listOf("start", "end", "reveal"), fixture.events)
        fixture.touch(MotionEvent.ACTION_UP); fixture.idle(1300)
        assertEquals(listOf("start", "end", "reveal"), fixture.events); fixture.close()
    }

    @Test fun `hold drift stays owned while a moved short contact cannot become a tap`() {
        val fixture = Fixture()
        fixture.touch(MotionEvent.ACTION_DOWN); fixture.touch(MotionEvent.ACTION_MOVE, 200f, 200f)
        fixture.idle(1200); fixture.touch(MotionEvent.ACTION_UP, 200f, 200f)
        assertEquals(listOf("start", "end", "reveal"), fixture.events)
        fixture.events.clear()
        fixture.touch(MotionEvent.ACTION_DOWN); fixture.touch(MotionEvent.ACTION_MOVE, 200f, 200f)
        fixture.touch(MotionEvent.ACTION_UP, 200f, 200f)
        assertEquals(listOf("start", "end"), fixture.events); fixture.close()
    }

    @Test fun `cancel or second contact ends ownership without tap or later reveal`() {
        for (action in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            val fixture = Fixture()
            fixture.touch(MotionEvent.ACTION_DOWN); fixture.idle(350); fixture.touch(action)
            fixture.idle(2000)
            assertEquals(listOf("start", "end"), fixture.events); fixture.close()
        }
    }

    @Test fun `disable detach host pause destroy and focus loss cancel actual native contact`() {
        val retirements: List<(LavaDecorationView) -> Unit> = listOf(
            { it.guardianGestures = false }, { detach(it) }, { it.onHostPause() },
            { it.onHostDestroy() }, { it.onWindowFocusChanged(false) }, { windowVisibility(it, View.INVISIBLE) },
        )
        for (retire in retirements) {
            val fixture = Fixture()
            fixture.touch(MotionEvent.ACTION_DOWN); fixture.idle(350); retire(fixture.view); fixture.idle(2000)
            assertEquals(listOf("start", "end"), fixture.events)
            fixture.touch(MotionEvent.ACTION_UP); assertEquals(2, fixture.events.size); fixture.close()
        }
    }

    @Test fun `catalog material cancels recycled contact and never captures taps holds or reveals`() {
        val fixture = Fixture(); val manager = LavaDecorationManager()
        fixture.touch(MotionEvent.ACTION_DOWN); fixture.idle(350)
        manager.updateProperties(fixture.view, ReactStylesDiffMap(JavaOnlyMap.of("symbol", "catalog.control.material")))
        assertEquals(listOf("start", "end"), fixture.events)
        fixture.events.clear()
        assertFalse(fixture.touch(MotionEvent.ACTION_DOWN))
        fixture.idle(2000)
        assertFalse(fixture.touch(MotionEvent.ACTION_UP))
        assertTrue(fixture.events.isEmpty())
        assertFalse(fixture.view.isClickable)
        assertFalse(fixture.view.isFocusable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            fixture.view.importantForAccessibility)
        // A recycled ordinary Guardian leaf regains its original contact owner;
        // the material leaves no input, outline or reveal-mask state behind.
        manager.updateProperties(fixture.view, ReactStylesDiffMap(JavaOnlyMap.of("symbol", "guardian.gestures")))
        assertTrue(fixture.touch(MotionEvent.ACTION_DOWN)); fixture.idle(80)
        assertTrue(fixture.touch(MotionEvent.ACTION_UP))
        assertEquals(listOf("start", "end", "tap"), fixture.events)
        fixture.close()
    }

    @Test fun `manager exports direct Guardian event and disabling or recycling retires contact`() {
        val fixture = Fixture(); val manager = LavaDecorationManager()
        assertEquals(mapOf("registrationName" to "onGuardianGesture"),
            manager.getExportedCustomDirectEventTypeConstants()["topGuardianGesture"])
        fixture.touch(MotionEvent.ACTION_DOWN); manager.guardianGestures(fixture.view, false); fixture.idle(1500)
        assertEquals(listOf("start", "end"), fixture.events)
        fixture.events.clear(); manager.guardianGestures(fixture.view, true)
        fixture.touch(MotionEvent.ACTION_DOWN); manager.onDropViewInstance(fixture.view); fixture.idle(1500)
        assertEquals(listOf("start", "end"), fixture.events); fixture.close()
    }
}
