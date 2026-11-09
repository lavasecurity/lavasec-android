package com.lavasec.app.reactnative

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import com.lavasec.app.security.NativePasscodeField
import com.lavasec.app.presentation.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowInputMethodManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativePasscodeCompositionTest {
    @Implements(InputMethodManager::class)
    class RestartingIme : ShadowInputMethodManager() {
        companion object { var connection: InputConnection? = null }
        @Implementation override fun restartInput(view: View) {
            connection = view.onCreateInputConnection(EditorInfo())
        }
    }
    @Test @Config(shadows = [RestartingIme::class])
    fun `wrong passcode restarts the focused IME while the stale connection stays revoked`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val field = NativePasscodeField(activity) { true }
        activity.setContentView(field); field.requestFocus()
        val visit = NativePasscodeFormVisit(activity, field, { true }, {})
        val old = checkNotNull(field.onCreateInputConnection(EditorInfo()))
        assertTrue(old.commitText("wrong", 1)); RestartingIme.connection = null
        visit.update(true); field.text?.clear()
        visit.update(false, "Wrong passcode")
        assertFalse(old.commitText("stale", 1))
        val renewed = checkNotNull(RestartingIme.connection)
        assertTrue(renewed.commitText("correct", 1))
        assertEquals("correct", field.text.toString())
        visit.close(); assertFalse(renewed.commitText("retired", 1)); activity.finish()
        RestartingIme.connection = null
    }

    @Test fun `the dialog body constrains stale Fabric height and preserves native buttons after resizing`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // ReactSurfaceView derives AT_MOST height from previously mounted Fabric children.
        val surface = object : View(activity) {
            override fun onMeasure(width: Int, height: Int) {
                setMeasuredDimension(MeasureSpec.getSize(width),
                    if (MeasureSpec.getMode(height) == MeasureSpec.EXACTLY) MeasureSpec.getSize(height) else 2_000)
            }
        }
        val viewport = NativePasscodeViewport(activity, surface)
        val dialog = AlertDialog.Builder(activity).setView(viewport)
            .setPositiveButton("Unlock", null).setNegativeButton("Cancel", null).create()
        dialog.show()
        val decor = checkNotNull(dialog.window).decorView as ViewGroup
        fun measure(height: Int) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 320, height)
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val buttonBounds = Rect(0, 0, button.width, button.height)
            val surfaceBounds = Rect(0, 0, surface.width, surface.height)
            decor.offsetDescendantRectToMyCoords(button, buttonBounds)
            decor.offsetDescendantRectToMyCoords(surface, surfaceBounds)
            assertTrue(surface.height > 0)
            assertTrue("The native action stays inside the resized window: $buttonBounds", buttonBounds.bottom <= height)
            assertTrue("Shared content $surfaceBounds reserves the native action panel $buttonBounds", surfaceBounds.bottom <= buttonBounds.top)
        }
        measure(640); val original = surface.height
        measure(320); assertTrue(surface.height < original)
        dialog.dismiss(); activity.finish()
    }

    @Test @Config(sdk = [28, 36], qualifiers = "w320dp-h640dp-mdpi")
    fun `all three management actions fit the compact window with the keyboard visible`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
        val surface = View(activity)
        val viewport = NativePasscodeViewport(activity, surface)
        val dialog = AlertDialog.Builder(activity).setView(viewport)
            .setPositiveButton(R.string.security_change_passcode, null).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.security_remove_passcode, null).create()
        dialog.show()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val decor = checkNotNull(dialog.window).decorView as ViewGroup
        val buttons = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .map(dialog::getButton)
        // Robolectric's host text widths do not reproduce device typography. Force
        // the genuine framework stack that the packaged API 36 journey reported.
        assertTrue("The real dialog buttons are attached before accessibility actions", buttons.all { it.isAttachedToWindow })
        buttons.forEach { it.minimumWidth = 180 }
        fun measure(height: Int) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 320, height)
        }
        fun fullyVisible(button: View): Boolean {
            val visible = Rect()
            return button.getGlobalVisibleRect(visible) && visible.width() == button.width && visible.height() == button.height
        }
        measure(640)
        assertEquals(android.widget.LinearLayout.VERTICAL,
            (buttons.first().parent as android.widget.LinearLayout).orientation)
        assertTrue("The actual framework panel scrolls its stacked actions", buttons.any { !fullyVisible(it) })
        for (height in listOf(640, 320, 480, 640)) {
            measure(height)
            assertTrue("The bounded shared body still has space", surface.height > 0)
            for (button in buttons) {
                assertTrue("Native action retains its complete target", button.width > 0 && button.height >= 48)
                if (!fullyVisible(button)) {
                    assertTrue("The exact native action supports accessible reveal", button.createAccessibilityNodeInfo().actionList.any {
                        it.id == android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id })
                    assertTrue("The real framework action panel accepts accessible reveal", button.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id, null))
                }
                assertTrue("The requested native action is completely visible at height $height", fullyVisible(button))
            }
        }
        dialog.dismiss(); activity.finish()
    }

    @Test fun `a Compose leaf mounted after dialog show inherits the actual activity owners`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        val dialog = AlertDialog.Builder(activity).setView(root).create()
        dialog.show()
        bindNativePasscodeViewTree(checkNotNull(dialog.window).decorView, activity)
        // Fabric inserts native symbol leaves after the initially empty dialog is shown.
        val leaf = ComposeView(activity).apply {
            setContent { androidx.compose.foundation.text.BasicText("Passcode") }
        }
        root.addView(leaf)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(leaf.isAttachedToWindow)
        assertSame(activity, leaf.findViewTreeLifecycleOwner())
        assertSame(activity, leaf.findViewTreeViewModelStoreOwner())
        assertSame(activity, leaf.findViewTreeSavedStateRegistryOwner())
        assertTrue(leaf.hasComposition)
        leaf.disposeComposition(); dialog.dismiss(); activity.finish()
    }

    @Test fun `dialog detachment revokes immediately and defers its retirement callback until removal returns`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val field = NativePasscodeField(activity) { true }.apply { setText("private") }
        lateinit var dialog: AlertDialog
        var removing = false; var reentered = false; var dismissals = 0; var retirements = 0
        val visit = NativePasscodeFormVisit(activity, field, { true }, {
            retirements++; reentered = removing; dialog.dismiss()
        })
        NativePasscodeForms.register(visit)
        val view = LavaPasscodeFormView(activity).apply { suppliedActivity = activity; ownerID = visit.id }
        dialog = AlertDialog.Builder(activity).setView(view).create()
        dialog.setOnDismissListener { dismissals++; visit.close() }
        dialog.show(); view.bind(); shadowOf(android.os.Looper.getMainLooper()).idle()
        removing = true; dialog.dismiss(); removing = false
        assertEquals("", field.text.toString()); assertEquals(0, retirements)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(reentered); assertEquals(1, retirements); assertEquals(1, dismissals)
        assertNull(NativePasscodeForms.find(visit.id, activity)); activity.finish()
    }

    @Test fun `shared refocus preserves the selected confirmation field in a multi-field native form`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val first = NativePasscodeField(activity) { true }
        val confirmation = NativePasscodeField(activity) { true }
        val form = android.widget.LinearLayout(activity).apply { addView(first); addView(confirmation) }
        val visit = NativePasscodeFormVisit(activity, form, { true }, {})
        val container = FrameLayout(activity)
        activity.setContentView(container); assertTrue(visit.attach(container))
        confirmation.requestFocus(); visit.focus()
        assertTrue(confirmation.hasFocus()); assertFalse(first.hasFocus())
        visit.close(); activity.finish()
    }
    @Test fun `native password form binds once to the exact host and retires without touching a successor`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val other = Robolectric.buildActivity(Activity::class.java).setup().get()
        val field = NativePasscodeField(activity) { true }
        var cancelled = 0
        val visit = NativePasscodeFormVisit(activity, field, { true }, { cancelled++ })
        NativePasscodeForms.register(visit)
        assertNull(NativePasscodeForms.find(visit.id, other))
        val first = FrameLayout(activity); val second = FrameLayout(activity)
        assertTrue(visit.attach(first)); assertFalse(visit.attach(second))
        field.setText("native-only")
        val successorField = NativePasscodeField(activity) { true }.apply { setText("successor") }
        val successor = NativePasscodeFormVisit(activity, successorField, { true }, {})
        NativePasscodeForms.register(successor)
        visit.detached(first); visit.detached(first)
        assertEquals("", field.text.toString()); assertEquals(0, cancelled)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, cancelled); assertEquals("", field.text.toString())
        assertNull(NativePasscodeForms.find(visit.id, activity))
        assertSame(successor, NativePasscodeForms.find(successor.id, activity))
        assertEquals("successor", successorField.text.toString())
        successor.close(); activity.finish(); other.finish()
    }

    @Test fun `metadata reports only native status and natural height without a repeated layout feedback loop`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val field = NativePasscodeField(activity) { true }.apply { setSingleLine(); setText("private-code") }
        val visit = NativePasscodeFormVisit(activity, field, { true }, {})
        NativePasscodeForms.register(visit)
        val emitted = mutableListOf<Triple<Double, Boolean, String>>()
        val view = LavaPasscodeFormView(activity).apply {
            suppliedActivity = activity; ownerID = visit.id; fontScale = 2f
            stateChanged = { height, busy, message -> emitted += Triple(height, busy, message) }
        }
        activity.setContentView(view); view.bind()
        fun measure() {
            view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY))
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        measure(); val count = emitted.size
        repeat(3) { view.bind(); measure() }
        assertEquals(count, emitted.size)
        assertTrue(emitted.last().first >= 48.0)
        assertFalse(emitted.toString().contains("private-code"))
        visit.update(true); assertTrue(emitted.last().second); assertFalse(field.isEnabled)
        visit.update(false, "Wrong passcode"); assertEquals("Wrong passcode", emitted.last().third)
        visit.close(); view.retire(); assertEquals("", field.text.toString())
        activity.finish()
    }

    @Test fun `a cached password IME cannot read or edit after native verification disables then reenables its field`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var active = true
        val field = NativePasscodeField(activity) { active }
        activity.setContentView(field); field.requestFocus()
        val ime = checkNotNull(field.onCreateInputConnection(EditorInfo()))
        assertTrue(ime.commitText("1234", 1))
        field.isEnabled = false; checkNotNull(field.text).clear()
        assertEquals("", ime.getTextBeforeCursor(10, 0).toString())
        field.isEnabled = true; field.requestFocus()
        assertFalse(ime.commitText("stale", 1))
        val current = checkNotNull(field.onCreateInputConnection(EditorInfo()))
        assertTrue(current.commitText("new", 1))
        active = false
        assertEquals("", current.getTextBeforeCursor(10, 0).toString())
        assertFalse(current.commitText("revoked", 1))
        checkNotNull(field.text).clear(); activity.finish()
    }
}
