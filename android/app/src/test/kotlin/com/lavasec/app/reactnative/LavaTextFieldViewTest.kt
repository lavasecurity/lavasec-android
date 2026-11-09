package com.lavasec.app.reactnative

import android.app.Application
import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.FrameLayout
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.DisplayMetricsHolder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LavaTextFieldViewTest {
    @Suppress("DEPRECATION")
    private fun field(): LavaTextFieldView {
        val application = RuntimeEnvironment.getApplication()
        // The packaged React host initializes these before creating ReactEditText.
        // This isolated fixture must initialize the same API from its actual
        // Android display, rather than bypassing the native font/layout path.
        DisplayMetricsHolder.initDisplayMetrics(application)
        assertTrue(DisplayMetricsHolder.getScreenDisplayMetrics().density > 0f)
        // RN 0.87 makes ReactApplicationContext abstract. Its public testing
        // implementation supplies real context/lifecycle behavior without a JS
        // engine; use the production Fabric marker so ReactEditText does not
        // request a legacy UIManager during its actual native text callbacks.
        val reactApplication = object : BridgeReactContext(application) {
            override fun isBridgeless(): Boolean = true
        }
        val context = ThemedReactContext(reactApplication, application, LavaReactActivity.COMPONENT_NAME, 1)
        return LavaTextFieldView(context).apply { suppliedPresentationAuthority = { true }; onHostResume() }
    }

    @Test fun `monospace presentation survives keyboard traits and returns to the default face`() {
        val field = field().apply { monospace = true; keyboardType = "url"; characterLimit = 3; commitProps() }
        assertEquals(android.graphics.Typeface.MONOSPACE, field.editor.typeface)
        field.editor.setText("abcd")
        assertEquals("abc", field.editor.text.toString())
        field.keyboardType = "email-address"; field.commitProps()
        assertEquals(android.graphics.Typeface.MONOSPACE, field.editor.typeface)
        field.monospace = false; field.commitProps()
        assertEquals(android.graphics.Typeface.DEFAULT, field.editor.typeface)
        assertEquals("abc", field.editor.text.toString())
    }

    @Test fun `ordinary acknowledgements preserve newer native typing and reset once`() {
        val field = field().apply { kind = "plain"; ownerID = "visit"; seedValue = "first"; commitProps() }
        field.editor.setText("three uninterrupted aaa")
        field.seedValue = "first acknowledgement"; field.commitProps()
        assertEquals("three uninterrupted aaa", field.editor.text.toString())
        field.resetRevision = 1; field.seedValue = "corrected"; field.commitProps()
        assertEquals("corrected", field.editor.text.toString())
        field.editor.setText("newer")
        field.seedValue = "late correction ack"; field.commitProps()
        assertEquals("newer", field.editor.text.toString())
        field.ownerID = "replacement"; field.seedValue = "replacement seed"; field.commitProps()
        assertEquals("replacement seed", field.editor.text.toString())
    }

    @Test fun `committed limits retain whole emoji while marked composition stays untouched`() {
        assertEquals("👩🏽‍💻", LavaTextFieldBufferPolicy.bound("👩🏽‍💻x", 1))
        assertEquals("🇯🇵", LavaTextFieldBufferPolicy.bound("🇯🇵next", 1))
        assertEquals("é", LavaTextFieldBufferPolicy.bound("éx", 1))
        val field = field().apply { characterLimit = 1; commitProps() }
        field.editor.requestFocusFromJS()
        val connection = checkNotNull(field.editor.onCreateInputConnection(EditorInfo()))
        // Android's IME installs its own composing sentinel. An arbitrary
        // SPAN_COMPOSING object is not reported by getComposingSpanStart.
        assertTrue(connection.setComposingText("かな", 1))
        assertEquals(0, BaseInputConnection.getComposingSpanStart(checkNotNull(field.editor.text)))
        assertEquals(2, BaseInputConnection.getComposingSpanEnd(checkNotNull(field.editor.text)))
        assertEquals("かな", field.editor.text.toString())
        assertTrue(connection.finishComposingText())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(checkNotNull(field.editor.text)))
        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(checkNotNull(field.editor.text)))
        assertEquals("か", field.editor.text.toString())
        assertTrue(connection.commitText("x", 1))
        assertEquals("か", field.editor.text.toString())
    }

    @Test fun `maxLength bounds committed UTF-16 without splitting composed input`() {
        val events = mutableListOf<String>()
        val field = field().apply {
            maxLength = 2
            emit = { name, payload -> if (name == "topChange") events += payload["text"] as String }
            commitProps()
        }
        field.editor.setText("😀x")
        assertEquals("😀", field.editor.text.toString())
        assertEquals("😀", events.last())
        field.editor.setText("")
        field.editor.requestFocusFromJS()
        val connection = checkNotNull(field.editor.onCreateInputConnection(EditorInfo()))
        assertTrue(connection.setComposingText("éx", 1))
        assertEquals("éx", field.editor.text.toString())
        assertTrue(connection.finishComposingText())
        assertEquals("é", field.editor.text.toString())
        assertEquals("é", events.last())
        assertEquals("", LavaTextFieldBufferPolicy.bound("👩🏽‍💻", 0, 2))
        assertEquals("👩🏽‍💻", LavaTextFieldBufferPolicy.bound("👩🏽‍💻x", 1, 8))
        assertEquals("", LavaTextFieldBufferPolicy.bound("é", 0, 1))
        assertEquals("", LavaTextFieldBufferPolicy.bound("x", 0, 0))
    }

    @Test fun `confidential edits never emit text and protected revocation clears only displayed buffer`() {
        val port = Port()
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        val field = field().apply {
            suppliedPort = port; kind = "wireGuard"; ownerID = "owner"; seedValue = "Fabric must never seed this"
            emit = { name, payload -> events += name to payload }; onHostResume(); commitProps()
        }
        assertEquals("native unsaved synthetic comment", field.editor.text.toString())
        field.editor.setText("native newest synthetic comment")
        assertEquals("native newest synthetic comment", port.text)
        assertFalse(events.any { it.second.containsKey("text") })
        port.state = port.state.copy(authorized = false, revealed = false, text = null)
        port.changed?.invoke()
        assertEquals("", field.editor.text.toString())
        assertEquals("native newest synthetic comment", port.text)
        port.state = port.state.copy(authorized = true, revealed = false)
        port.changed?.invoke()
        assertEquals("Authorization alone cannot reveal configuration", "", field.editor.text.toString())
        assertFalse(events.any { it.second.containsKey("text") })
    }

    @Test fun `Security off retains accepted paint during a read pause without new reads or edits`() {
        val port = Port()
        val field = field().apply { suppliedPort = port; kind = "wireGuard"; ownerID = "owner"; onHostResume(); commitProps() }
        port.state = port.state.copy(authorized = false, retainAcceptedDisplay = true, text = "must not be read")
        port.changed?.invoke()
        assertEquals("native unsaved synthetic comment", field.editor.text.toString())
        assertFalse(field.editor.onTextContextMenuItem(android.R.id.copy))
        field.ownerID = "replacement"; field.commitProps()
        assertEquals("A replacement owner cannot inherit an accepted frame", "", field.editor.text.toString())
    }

    @Test fun `stale IME cannot read or mutate an accepted private frame during host pause`() {
        val port = Port()
        val field = field().apply { suppliedPort = port; kind = "wireGuard"; ownerID = "owner"; commitProps() }
        field.editor.requestFocusFromJS()
        val connection = checkNotNull(field.editor.onCreateInputConnection(EditorInfo()))
        port.state = port.state.copy(retainAcceptedDisplay = true)
        field.onHostPause()
        assertEquals("native unsaved synthetic comment", field.editor.text.toString())
        assertEquals("", connection.getTextBeforeCursor(1000, 0).toString())
        assertEquals("", connection.getTextAfterCursor(1000, 0).toString())
        assertNull(connection.getSelectedText(0))
        assertNull(connection.getExtractedText(ExtractedTextRequest(), 0))
        assertFalse(connection.commitText("late private write", 1))
        assertFalse(connection.setComposingText("late composition", 1))
        assertFalse(connection.setSelection(0, field.editor.length()))
        assertFalse(connection.performContextMenuAction(android.R.id.copy))
        assertEquals("native unsaved synthetic comment", port.text)
        field.onHostResume()
        assertTrue(connection.commitText(" admitted", 1))
        assertTrue(port.text.endsWith(" admitted"))
    }

    @Test fun `ordinary native buffer also rejects stale IME edits while paused`() {
        val field = field().apply { kind = "plain"; seedValue = "buffer"; commitProps() }
        field.editor.requestFocusFromJS()
        val connection = checkNotNull(field.editor.onCreateInputConnection(EditorInfo()))
        field.onHostPause()
        assertFalse(connection.commitText("stale", 1))
        assertEquals("buffer", field.editor.text.toString())
        assertFalse(field.editor.hasFocus())
        field.onHostResume()
        assertEquals("buffer", field.editor.text.toString())
    }

    @Test fun `ordinary retained field waits for exact native presentation grant and successor Ready`() {
        var authorized = true
        var ready = true
        val field = field().apply {
            kind = "plain"; ownerID = "same-draft"; seedValue = "native unsaved value"
            suppliedPresentationAuthority = { authorized && ready }; commitProps()
        }
        field.editor.requestFocusFromJS()
        val connection = checkNotNull(field.editor.onCreateInputConnection(EditorInfo()))
        authorized = false; ready = false
        field.reconcileAuthority()
        assertFalse(connection.commitText("revoked", 1))
        assertEquals("", connection.getTextBeforeCursor(1000, 0).toString())
        assertNull(field.editor.onCreateInputConnection(EditorInfo()))
        val staleAccessibilityArguments = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "stale accessibility write")
        }
        assertFalse(field.editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, staleAccessibilityArguments))
        val node = android.view.accessibility.AccessibilityNodeInfo.obtain()
        field.editor.onInitializeAccessibilityNodeInfo(node)
        assertNull(node.text)
        assertNull(node.contentDescription)
        assertFalse(node.isEditable)
        node.recycle()
        assertEquals("native unsaved value", field.editor.text.toString())
        authorized = true
        field.reconcileAuthority()
        assertFalse("A grant cannot replace its exact layout receipt", connection.commitText("too early", 1))
        ready = true
        field.reconcileAuthority()
        assertFalse("A Ready successor cannot revive an underlying blurred input connection", connection.commitText("covered successor", 1))
        assertEquals("", connection.getTextBeforeCursor(1000, 0).toString())
        assertFalse(connection.setSelection(0, 3))
        assertNull(field.editor.onCreateInputConnection(EditorInfo()))
        field.editor.requestFocusFromJS()
        assertTrue(connection.commitText(" accepted", 1))
        assertEquals("native unsaved value accepted", field.editor.text.toString())
    }

    @Test fun `accessibility advertises writes only after local editing and native Ready both admit them`() {
        var nativeReady = true
        val field = field().apply {
            kind = "plain"; ownerID = "rename-visit"; inputLabel = "Name"; placeholder = "Filter name"
            seedValue = "retained native draft"; editable = false
            suppliedPresentationAuthority = { nativeReady }; commitProps()
        }
        // Android's base node initializer requires an attached window before
        // copying the label. Exercise the real RN delegate and native child,
        // rather than manually initializing an empty node on a detached editor.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            activity.get().setContentView(field)
            assertTrue("The real editor must be attached for label emission", field.editor.isAttachedToWindow)
            fun node() = checkNotNull(field.editor.createAccessibilityNodeInfo())
            val arguments = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "accepted native edit")
            }
            node().let { denied ->
                assertEquals("An authorized read-only field keeps its display", "retained native draft", denied.text.toString())
                assertEquals("Name", denied.contentDescription.toString())
                assertEquals("Filter name", denied.hintText.toString())
                assertFalse(denied.isEditable)
                assertFalse(denied.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT })
                assertFalse(denied.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION })
                denied.recycle()
            }
            assertFalse(field.editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            assertEquals("retained native draft", field.editor.text.toString())

            field.editable = true; nativeReady = false; field.commitProps()
            node().let { concealed ->
                assertFalse(concealed.isEditable)
                assertNull(concealed.text)
                assertNull(concealed.contentDescription)
                assertNull(concealed.hintText)
                assertFalse(concealed.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT })
                assertFalse(concealed.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION })
                concealed.recycle()
            }
            assertFalse(field.editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            assertEquals("retained native draft", field.editor.text.toString())

            nativeReady = true; field.reconcileAuthority()
            node().let { admitted ->
                assertEquals("Name", admitted.contentDescription.toString())
                assertEquals("Filter name", admitted.hintText.toString())
                assertTrue(admitted.isEditable)
                assertTrue(admitted.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT })
                assertTrue(admitted.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION })
                admitted.recycle()
            }
            var reports = 0
            val changes = mutableListOf<String>()
            field.emit = { event, payload ->
                if (event == "topChange") {
                    ++reports
                    changes += payload["text"] as String
                }
            }
            assertTrue("One real action succeeds only on actual admission", field.editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            assertEquals("accepted native edit", field.editor.text.toString())
            assertEquals(1, reports)

            field.editable = false; field.commitProps()
            node().let { revoked ->
                assertFalse(revoked.isEditable)
                assertFalse(revoked.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT })
                assertFalse(revoked.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION })
                revoked.recycle()
            }
            assertEquals("accepted native edit", field.editor.text.toString())
            assertEquals(1, reports)
            assertEquals(listOf("accepted native edit"), changes)

            field.editable = true; field.commitProps()
            val nextArguments = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "next native edit")
            }
            nativeReady = false; field.reconcileAuthority()
            assertFalse(field.editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, nextArguments))
            assertEquals("accepted native edit", field.editor.text.toString())
            assertEquals(1, reports)
            nativeReady = true; field.reconcileAuthority()
            assertTrue(field.editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, nextArguments))
            assertEquals("next native edit", field.editor.text.toString())
            assertEquals(2, reports)
            assertEquals(listOf("accepted native edit", "next native edit"), changes)

            // A real native watcher may retire the original admission while
            // Android is replacing its buffer. The return edge cannot publish
            // that action through a successor owner or renewed authority.
            fun retireDuringReplacement(retire: () -> Unit) {
                field.editor.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        field.editor.removeTextChangedListener(this)
                        retire()
                    }
                })
                assertTrue(field.editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
                assertEquals(2, reports)
                assertEquals(listOf("accepted native edit", "next native edit"), changes)
            }
            retireDuringReplacement { nativeReady = false; field.reconcileAuthority() }
            nativeReady = true; field.reconcileAuthority()
            retireDuringReplacement { field.ownerID = "successor-rename-visit"; field.commitProps() }
            retireDuringReplacement { field.kind = "multiline"; field.commitProps() }
            retireDuringReplacement { ++field.resetRevision; field.commitProps() }

            field.editor.setText("ordinary subsequent typing")
            assertEquals("ordinary subsequent typing", field.editor.text.toString())
            assertTrue("Ordinary native typing is still published after the action returns", reports > 2)
            assertEquals("ordinary subsequent typing", changes.last())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test @Config(sdk = [34])
    fun `modern IME overloads cannot bypass paused or concealed native presentation authority`() {
        var ready = true
        val field = field().apply {
            kind = "plain"; seedValue = "retained native draft"
            suppliedPresentationAuthority = { ready }; commitProps()
        }
        field.editor.requestFocusFromJS()
        val connection = checkNotNull(field.editor.onCreateInputConnection(EditorInfo()))
        ready = false; field.reconcileAuthority()
        val attribute = android.view.inputmethod.TextAttribute.Builder().build()
        assertFalse(connection.commitText("overload bypass", 1, attribute))
        assertFalse(connection.setComposingText("overload bypass", 1, attribute))
        assertFalse(connection.setComposingRegion(0, 3, attribute))
        assertFalse(connection.replaceText(0, 3, "overload bypass", 1, attribute))
        assertFalse(connection.requestCursorUpdates(1, 0))
        assertFalse(connection.performPrivateCommand("retired-ime", null))
        assertNull(connection.takeSnapshot())
        assertNull(connection.getSurroundingText(1000, 1000, 0))
        var bounds: android.view.inputmethod.TextBoundsInfoResult? = null
        val executor = java.util.concurrent.Executor { it.run() }
        connection.requestTextBoundsInfo(android.graphics.RectF(0f, 0f, 100f, 100f), executor) { bounds = it }
        assertEquals(android.view.inputmethod.TextBoundsInfoResult.CODE_FAILED, bounds?.resultCode)
        var handwriting = -1
        val gesture = android.view.inputmethod.SelectGesture.Builder()
            .setGranularity(android.view.inputmethod.HandwritingGesture.GRANULARITY_CHARACTER)
            .setSelectionArea(android.graphics.RectF(0f, 0f, 100f, 100f)).build()
        connection.performHandwritingGesture(gesture, executor) { handwriting = it }
        assertEquals(InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED, handwriting)
        assertFalse(connection.previewHandwritingGesture(gesture, null))
        assertEquals("retained native draft", field.editor.text.toString())
        ready = true; field.reconcileAuthority()
        assertFalse("Other displayed readiness cannot revive a blurred field", connection.commitText("still stale", 1, attribute))
        field.editor.requestFocusFromJS()
        assertTrue(connection.commitText(" accepted", 1, attribute))
        assertEquals("retained native draft accepted", field.editor.text.toString())
    }

    @Test fun `automatic focus waits for the opening transition without withholding native paint or size`() {
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        val field = field().apply {
            ownerID = "opening"; seedValue = "native draft"; autoFocus = true; autoFocusReady = false
            emit = { name, payload -> events += name to payload }; commitProps()
        }
        assertEquals("native draft", field.editor.text.toString())
        assertTrue(field.editor.isFocusable)
        assertTrue(events.any { it.first == "topSizeChange" })
        assertFalse(field.editor.hasFocus())
        assertFalse(events.any { it.first == "topFocusChange" && it.second["focused"] == true })
        field.seedValue = "stale acknowledgement"; field.commitProps()
        assertEquals("native draft", field.editor.text.toString())
        assertFalse(field.editor.hasFocus())
        // Exercise the actual manager prop boundary used after native-stack onAppear.
        LavaTextFieldManager().focusReady(field, true); field.commitProps()
        assertTrue(field.editor.hasFocus())
        assertEquals(1, events.count { it.first == "topFocusChange" && it.second["focused"] == true })
    }

    @Test fun `transition completion never replaces native host grant Ready or editability`() {
        var authorizedAndReady = false
        val field = field().apply {
            ownerID = "opening"; autoFocus = true; autoFocusReady = false
            suppliedPresentationAuthority = { authorizedAndReady }; commitProps()
        }
        field.autoFocusReady = true; field.commitProps()
        assertFalse(field.editor.hasFocus())
        authorizedAndReady = true; field.editable = false; field.commitProps()
        assertFalse(field.editor.hasFocus())
        field.onHostPause(); field.editable = true; field.commitProps()
        assertFalse(field.editor.hasFocus())
        field.onHostResume()
        assertTrue(field.editor.hasFocus())
    }

    @Test fun `cancelled opening request cannot focus while a replacement owner waits for its own transition`() {
        val field = field().apply {
            ownerID = "cancelled"; autoFocus = true; autoFocusReady = false; commitProps()
        }
        field.autoFocus = false; field.commitProps()
        field.autoFocusReady = true; field.autoFocus = true; field.commitProps()
        assertFalse("An unchanged owner cannot rearm a cancelled opening request", field.editor.hasFocus())
        field.ownerID = "replacement"; field.seedValue = "replacement draft"; field.autoFocusReady = false; field.commitProps()
        assertEquals("replacement draft", field.editor.text.toString())
        assertFalse(field.editor.hasFocus())
        field.autoFocusReady = true; field.commitProps()
        assertTrue(field.editor.hasFocus())
    }

    @Test fun `transition and authority rising edges never refocus after the user blurs`() {
        val field = field().apply { ownerID = "retained"; autoFocus = true; commitProps() }
        assertTrue(field.editor.hasFocus())
        field.editor.setText("user draft"); field.editor.clearFocus()
        field.autoFocusReady = false; field.commitProps()
        field.onHostPause(); field.autoFocusReady = true; field.commitProps(); field.onHostResume()
        assertFalse(field.editor.hasFocus())
        assertEquals("user draft", field.editor.text.toString())
    }

    @Test fun `manual focus before transition completion consumes the pending automatic request`() {
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        val field = field().apply {
            ownerID = "opening"; autoFocus = true; autoFocusReady = false
            emit = { name, payload -> events += name to payload }; commitProps()
        }
        val blurTarget = View(field.context).apply { isFocusable = true; isFocusableInTouchMode = true }
        FrameLayout(field.context).apply { addView(field); addView(blurTarget) }
        field.editor.requestFocusFromJS()
        assertTrue(field.editor.hasFocus())
        // API28 clearFocus can immediately choose the same first focusable
        // child. Transfer focus to a real sibling to exercise an actual blur.
        assertTrue(blurTarget.requestFocus())
        assertTrue(blurTarget.hasFocus())
        assertTrue("Blurring must not disable the admitted editor", field.editor.isFocusable)
        assertFalse(field.editor.hasFocus())
        assertEquals(1, events.count { it.first == "topFocusChange" && it.second["focused"] == true })
        LavaTextFieldManager().focusReady(field, true); field.commitProps()
        assertFalse(field.editor.hasFocus())
        assertTrue(blurTarget.hasFocus())
        assertEquals(1, events.count { it.first == "topFocusChange" && it.second["focused"] == true })
    }

    private class Port : LavaWireGuardInputPort {
        var text = "native unsaved synthetic comment"
        var state = LavaWireGuardInputState(7, authorized = true, revealed = true, text = text)
        var changed: (() -> Unit)? = null
        override fun snapshot(ownerID: String) = if (ownerID == "owner") state.copy(text = if (state.authorized) text else state.text) else null
        override fun observe(ownerID: String, onChanged: () -> Unit): () -> Unit { changed = onChanged; return { changed = null } }
        override fun replace(ownerID: String, text: String): Boolean {
            if (ownerID != "owner" || !state.authorized || !state.revealed) return false
            this.text = text; return true
        }
        override fun canEditName(ownerID: String) = ownerID == "owner" && state.authorized
        override fun reveal(ownerID: String): Boolean {
            if (ownerID != "owner" || !state.authorized) return false
            state = state.copy(revealed = true); changed?.invoke(); return true
        }
    }
}
