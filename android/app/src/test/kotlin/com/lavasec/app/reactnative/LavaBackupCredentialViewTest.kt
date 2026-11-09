package com.lavasec.app.reactnative

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LavaBackupCredentialViewTest {
    @Test fun `copy remains native refuses a covered visit and expiration leaves a successor clip untouched`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = NativeVisitHost(activity, "host") { true }
        var granted = true
        val flow = NativeAccountBackupFlow("backupSetup", host, 1, { host }, { 1 }, { true }, { granted }, { granted }, {})
        flow.createPhrase()
        val view = LavaBackupCredentialView(activity).apply {
            suppliedFlow = flow; suppliedAuthority = { granted }; ownerID = flow.id
        }
        activity.setContentView(view); view.bind()
        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        view.copy.performClick()
        assertEquals(flow.credential()!!.first, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(NativeRecoveryClipboard.RETENTION_MS))
        assertFalse(clipboard.hasPrimaryClip())
        view.copy.performClick()
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("other", "other"))
        granted = false; flow.authorityChanged()
        view.copy.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(NativeRecoveryClipboard.RETENTION_MS))
        assertEquals("other", clipboard.primaryClip!!.getItemAt(0).text.toString())
        flow.close(); activity.finish()
    }
    @Test fun `native credential input is erased under a cover and an old IME cannot borrow the resumed grant`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = NativeVisitHost(activity, "host") { true }
        var granted = true
        val flow = NativeAccountBackupFlow("backupRestore", host, 1, { host }, { 1 }, { true }, { granted }, { granted }, {})
        flow.configureRestore(false, false)
        val view = LavaBackupCredentialView(activity).apply {
            suppliedFlow = flow; suppliedAuthority = { granted }; ownerID = flow.id
        }
        activity.setContentView(view); view.bind()
        assertTrue(view.input.requestFocus())
        val ime = checkNotNull(view.input.onCreateInputConnection(EditorInfo()))
        assertTrue(ime.commitText("first recovery words", 1))
        assertEquals("first recovery words", flow.credential()!!.second)
        assertFalse(view.input.isSaveEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, view.input.importantForAutofill)
        granted = false; flow.authorityChanged()
        assertEquals("", view.input.text.toString())
        assertEquals("", ime.getTextBeforeCursor(100, 0).toString())
        assertFalse(ime.commitText("stale edit", 1))
        granted = true; flow.authorityChanged()
        view.input.requestFocus()
        assertEquals("", ime.getTextBeforeCursor(100, 0).toString())
        assertFalse(ime.commitText("still stale", 1))
        assertEquals("first recovery words", flow.credential()!!.second)
        flow.close()
        assertEquals("", view.input.text.toString())
        activity.finish()
    }

    @Test fun `credential native layout reports its actual scaled height instead of clipping to a fixed row`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = NativeVisitHost(activity, "host") { true }
        val flow = NativeAccountBackupFlow("backupSetup", host, 1, { host }, { 1 }, { true }, { true }, { true }, {})
        flow.createPhrase()
        val view = LavaBackupCredentialView(activity).apply {
            suppliedFlow = flow; suppliedAuthority = { true }; ownerID = flow.id; fontScale = 2f
        }
        activity.setContentView(view); view.bind()
        var height = 0.0
        view.sizeChanged = { height = it }
        view.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(height > 48)
        flow.close(); activity.finish()
    }
}
