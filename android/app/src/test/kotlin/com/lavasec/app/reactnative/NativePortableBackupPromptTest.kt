package com.lavasec.app.reactnative

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.ScrollView
import android.widget.CheckBox
import com.lavasec.app.R
import com.lavasec.app.backup.presentBackupRestoreReview
import com.lavasec.app.backup.presentBackupSetupPhrase
import com.lavasec.app.backup.BackupRecoveryPhraseGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NativePortableBackupPromptTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `password export requires policy and confirmation then clears both native fields`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pending = async { host.get().presentPortableBackupPassword(export = true) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val fields = fields(dialog.window!!.decorView)
            assertEquals(2, fields.size)
            fields.forEach {
                assertFalse(it.isSaveEnabled)
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, it.importantForAutofill)
            }
            fields[0].setText("short")
            fields[1].setText("short")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(pending.isCompleted)
            fields[0].setText("strong-password1!")
            fields[1].setText("mismatch1!")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(pending.isCompleted)
            fields[1].setText("strong-password1!")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val secret = pending.await()!!
            assertArrayEquals("strong-password1!".toCharArray(), secret)
            assertTrue(fields.all { it.text.isEmpty() })
            secret.fill('\u0000')
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `restore secret stays bounded and dismissal returns no material`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pending = async { host.get().presentPortableBackupPassword(export = false) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val field = fields(dialog.window!!.decorView).single()
            field.setText("x".repeat(5000))
            assertEquals(4096, field.text.length)
            dialog.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(pending.await())
            assertTrue(field.text.isEmpty())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `empty import password asks only for existing password and admits short legacy secret`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pending = async { host.get().presentPortableBackupPassword(export = false) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val field = fields(dialog.window!!.decorView).single()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(pending.isCompleted)
            val visible = views(dialog.window!!.decorView).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }
            assertTrue(visible.any { it.text.toString() == host.get().getString(R.string.portable_backup_import_password_required) })
            assertFalse(visible.any { it.text.toString() == host.get().getString(R.string.portable_backup_password_invalid) })
            field.setText("old")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val secret = pending.await()!!
            assertArrayEquals("old".toCharArray(), secret)
            assertTrue(field.text.isEmpty())
            secret.fill('\u0000')
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `coroutine retirement clears secret input and dismisses secure window`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pending = async { host.get().presentPortableBackupPassword(export = false) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val field = fields(dialog.window!!.decorView).single()
            field.setText("do-not-retain")
            pending.cancel()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(field.text.isEmpty())
            assertFalse(dialog.isShowing)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `backgrounding portable password clears native secret and retires window`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pending = async { host.get().presentPortableBackupPassword(export = false) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val field = fields(dialog.window!!.decorView).single()
            field.setText("do-not-show-after-lock")
            host.pause()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(pending.isCancelled)
            assertTrue(field.text.isEmpty())
            assertFalse(dialog.isShowing)
            host.resume()
            assertFalse(dialog.isShowing)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `foreground authorization revocation retires secure native prompt and removes observer`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        var current = true
        var changed: (() -> Unit)? = null
        var stopped = false
        try {
            val pending = async {
                withPortableBackupPromptAuthority({ current }, { changed = it; { changed = null; stopped = true } }) {
                    host.get().presentPortableBackupPassword(export = false)
                }
            }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val field = fields(dialog.window!!.decorView).single()
            field.setText("do-not-retain-after-revision")
            current = false
            changed!!.invoke()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(pending.isCancelled)
            assertTrue(field.text.isEmpty())
            assertFalse(dialog.isShowing)
            assertTrue(stopped)
            assertNull(changed)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `portable recovery phrase is cleared when host leaves foreground`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pending = async {
                host.get().presentBackupSetupPhrase(BackupRecoveryPhraseGenerator { "baba keke" }, retireOnPause = true)
            }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val phrase = views(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == "baba keke" }
            host.pause()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(pending.isCancelled)
            assertTrue(phrase.text.isEmpty())
            assertFalse(dialog.isShowing)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `portable restore review scrolls preserves summary on acknowledgment error and clears on pause`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val summary = "Sensitive DNS endpoint change\n".repeat(100)
        try {
            val pending = async { host.get().presentBackupRestoreReview(summary, true, retireOnPause = true) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val all = views(dialog.window!!.decorView)
            assertTrue(all.any { it is ScrollView })
            val body = all.filterIsInstance<TextView>().first { it.text.toString() == summary }
            assertFalse(all.filterIsInstance<CheckBox>().single().isChecked)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(pending.isCompleted)
            assertEquals(summary, body.text.toString())
            host.pause()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(pending.isCancelled)
            assertTrue(body.text.isEmpty())
            assertFalse(dialog.isShowing)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `pending save is available without DNS apply acknowledgment and clears review`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val summary = "Private resolver and filter changes"
            val pending = async { host.get().presentPortableBackupRestoreReview(summary, true) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val body = views(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == summary }
            assertFalse(views(dialog.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(PortableBackupReviewAction.SAVE_FOR_LATER, pending.await())
            assertTrue(body.text.isEmpty())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `restoring now still requires resolver acknowledgment`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pending = async { host.get().presentPortableBackupRestoreReview("Resolver changes", true) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(pending.isCompleted)
            views(dialog.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(PortableBackupReviewAction.RESTORE_NOW, pending.await())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `cancelled pending review produces neither save nor apply`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pending = async { host.get().presentPortableBackupRestoreReview("Sensitive change", false) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(pending.await())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `pending review retirement clears plaintext and prevents either action`() = runTest {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val summary = "Do not retain this private review"
            val pending = async { host.get().presentPortableBackupRestoreReview(summary, false) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val body = views(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == summary }
            host.pause()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(pending.isCancelled)
            assertTrue(body.text.isEmpty())
            assertFalse(dialog.isShowing)
        } finally { host.pause().stop().destroy() }
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun fields(view: View): List<EditText> = if (view is EditText) listOf(view)
        else if (view is ViewGroup) (0 until view.childCount).flatMap { fields(view.getChildAt(it)) }
        else emptyList()
}
