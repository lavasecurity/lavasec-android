package com.lavasec.app.backup

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import com.lavasec.app.R
import com.lavasec.app.foundation.backup.BackupRecoveryMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class NativeBackupPromptsTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `restore chooser exposes only admitted methods and returns the actual selected row`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for ((device, passkey) in listOf(false to false, true to false, false to true, true to true)) {
                val methods = buildList {
                    if (device) add(BackupRecoveryMethod.DEVICE_KEY)
                    add(BackupRecoveryMethod.RECOVERY_CODE)
                    if (passkey) add(BackupRecoveryMethod.PASSKEY)
                }
                for ((index, method) in methods.withIndex()) {
                    val prompt = async { controller.get().presentBackupRestoreMethod(device, passkey) }
                    runCurrent()
                    val dialog = ShadowAlertDialog.getLatestAlertDialog()
                    assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertEquals(methods.size, dialog.listView.adapter.count)
                    assertEquals(controller.get().getString(when (method) {
                        BackupRecoveryMethod.DEVICE_KEY -> R.string.backup_restore_method_device
                        BackupRecoveryMethod.RECOVERY_CODE -> R.string.backup_restore_method_recovery
                        BackupRecoveryMethod.PASSKEY -> R.string.backup_restore_method_passkey
                    }), dialog.listView.adapter.getItem(index))
                    dialog.listView.performItemClick(null, index, index.toLong())
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(method, prompt.await())
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `passkey explicit skip differs from cancellation and use`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for ((button, expected) in listOf(AlertDialog.BUTTON_POSITIVE to true,
                AlertDialog.BUTTON_NEGATIVE to false, AlertDialog.BUTTON_NEUTRAL to null)) {
                val prompt = async { controller.get().presentBackupPasskeyConsent() }
                runCurrent()
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                dialog.getButton(button).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(expected, prompt.await())
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `dismissed and cancelled native backup sheets return no consent or method`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val consent = async { controller.get().presentBackupPasskeyConsent() }
            runCurrent()
            ShadowAlertDialog.getLatestAlertDialog().dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(consent.await())
            val method = async { controller.get().presentBackupRestoreMethod(true, true) }
            runCurrent()
            ShadowAlertDialog.getLatestAlertDialog().cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(method.await())
            val abandoned = async { controller.get().presentBackupPasskeyConsent() }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            abandoned.cancel()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
        } finally { controller.pause().stop().destroy() }
    }
}
