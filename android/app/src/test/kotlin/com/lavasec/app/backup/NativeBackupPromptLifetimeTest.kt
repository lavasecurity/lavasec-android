package com.lavasec.app.backup

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.lavasec.app.reactnative.NativeCommandAdmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** Native fixture evidence only: no account, cloud, credential grant or provider is fabricated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], application = NativeBackupPromptLifetimeTest.LifetimeApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NativeBackupPromptLifetimeTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun setupHostDestructionClearsPhraseAndReleasesRetainedAdmission() = runTest {
        assertHostDestruction(Prompt.SETUP)
    }

    @Test fun recoveryHostDestructionClearsInputAndReleasesRetainedAdmission() = runTest {
        assertHostDestruction(Prompt.RECOVERY)
    }

    @Test fun methodHostDestructionCancelsSelectionAndReleasesRetainedAdmission() = runTest {
        assertHostDestruction(Prompt.METHOD)
    }

    @Test fun consentHostDestructionCancelsApprovalAndReleasesRetainedAdmission() = runTest {
        assertHostDestruction(Prompt.CONSENT)
    }

    @Test fun reviewHostDestructionCancelsConfirmationAndReleasesRetainedAdmission() = runTest {
        assertHostDestruction(Prompt.REVIEW)
    }

    private suspend fun TestScope.assertHostDestruction(kind: Prompt) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val other = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val application = activity.application as LifetimeApplication
        val callbacksBefore = application.activeCallbacks.size
        val retainedScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val admission = NativeCommandAdmission()
        var returned = false
        val command = retainedScope.async {
            admission.run("native.flow") { Returned(kind.present(activity)) }.also { returned = true }
        }
        var successor: Deferred<Returned?>? = null
        val replacement = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            drainDialogLifecycle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            val fields = dialogViews(dialog).filterIsInstance<EditText>()
            assertEquals(if (kind == Prompt.SETUP || kind == Prompt.RECOVERY) 1 else 0, fields.size)
            fields.forEach { it.setText(SYNTHETIC_PHRASE) }
            val phraseViews = dialogViews(dialog).filterIsInstance<TextView>().filter {
                it !is EditText && it.text.toString() == SYNTHETIC_PHRASE
            }
            if (kind == Prompt.SETUP) assertEquals("The real native phrase is displayed", 1, phraseViews.size)
            assertEquals(callbacksBefore + 1, application.activeCallbacks.size)
            other.pause().stop().destroy()
            drainDialogLifecycle()
            assertFalse("Another Activity cannot cancel the original request", command.isCompleted)
            assertTrue(dialog.isShowing)
            assertNull("The actual module admission stays occupied while its prompt is pending",
                admission.run("native.flow") { Returned(Unit) })

            // No explicit dialog dismissal, caller cancellation or simulated lifecycle callback.
            controller.pause().stop().destroy()
            drainDialogLifecycle()
            assertTrue(command.isCompleted)
            assertTrue("Destroying the initiating host is cancellation, never prompt success", command.isCancelled)
            assertFalse(returned)
            assertFalse(dialog.isShowing)
            assertTrue("Cancellation directly clears the original native input", fields.all { it.text.isEmpty() })
            assertTrue("Cancellation directly clears the displayed setup phrase", phraseViews.all { it.text.isEmpty() })
            assertEquals(callbacksBefore, application.activeCallbacks.size)
            assertTrue("The retained module-like scope remains usable", retainedScope.coroutineContext.job.isActive)

            // Reuse the actual admission and exercise a real successor native prompt/Cancel.
            successor = retainedScope.async {
                admission.run("native.flow") { Returned(replacement.get().presentBackupRestorePhrase()) }
            }
            drainDialogLifecycle()
            val successorDialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(successorDialog !== dialog)
            val successorField = dialogViews(successorDialog).filterIsInstance<EditText>().single()
            successorField.setText(SYNTHETIC_PHRASE)
            command.cancel()
            dialog.dismiss()
            drainDialogLifecycle()
            assertTrue("Retired cleanup cannot dismiss the successor dialog", successorDialog.isShowing)
            assertEquals("Retired cleanup cannot clear a successor's input", SYNTHETIC_PHRASE, successorField.text.toString())
            successorDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            drainDialogLifecycle()
            val cancelled = successor.await()
            assertNotNull("The successor was admitted through the same retained lane", cancelled)
            assertNull(cancelled!!.value)
            assertTrue(successorField.text.isEmpty())
            assertEquals(callbacksBefore, application.activeCallbacks.size)
        } finally {
            successor?.cancel()
            command.cancel()
            retainedScope.cancel()
            drainDialogLifecycle()
            if (!other.get().isDestroyed) other.pause().stop().destroy()
            if (!activity.isDestroyed) controller.pause().stop().destroy()
            if (!replacement.get().isDestroyed) replacement.pause().stop().destroy()
            drainDialogLifecycle()
        }
    }

    @Test fun callerCancellationClearsNativeSecretsAndUnregistersEachHost() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val application = controller.get().application as LifetimeApplication
        val callbacksBefore = application.activeCallbacks.size
        try {
            for (kind in Prompt.entries) {
                val command = async { kind.present(controller.get()) }
                drainDialogLifecycle()
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                val fields = dialogViews(dialog).filterIsInstance<EditText>()
                fields.forEach { it.setText(SYNTHETIC_PHRASE) }
                command.cancel()
                drainDialogLifecycle()
                assertTrue(command.isCancelled)
                assertFalse(dialog.isShowing)
                assertTrue(fields.all { it.text.isEmpty() })
                assertEquals(callbacksBefore, application.activeCallbacks.size)
                assertFalse("Caller cancellation does not destroy its Activity", controller.get().isDestroyed)
            }
        } finally { controller.pause().stop().destroy(); drainDialogLifecycle() }
    }

    @Test fun acceptedSensitivePromptsTransferOwnedMaterialAndRemoveTheirHostListener() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val application = controller.get().application as LifetimeApplication
        val callbacksBefore = application.activeCallbacks.size
        val owned = mutableListOf<CharArray>()
        try {
            for (kind in listOf(Prompt.SETUP, Prompt.RECOVERY)) {
                val command = async { kind.present(controller.get()) }
                drainDialogLifecycle()
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                val input = dialogViews(dialog).filterIsInstance<EditText>().single()
                input.setText(SYNTHETIC_PHRASE)
                dialogViews(dialog).filterIsInstance<CheckBox>().forEach { it.isChecked = true }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                drainDialogLifecycle()
                val value = command.await()
                val secret = when (value) {
                    is BackupSetupPrompt -> {
                        assertTrue(value.consent.canFinish)
                        value.phrase
                    }
                    is CharArray -> value
                    else -> error("Actual sensitive native prompt did not complete")
                }
                owned += secret
                assertEquals(SYNTHETIC_PHRASE, secret.concatToString())
                assertTrue(input.text.isEmpty())
                assertEquals(callbacksBefore, application.activeCallbacks.size)
            }
            controller.pause().stop().destroy()
            drainDialogLifecycle()
            assertTrue("A completed prompt transfers its secret to the caller, not a stale host listener",
                owned.all { it.concatToString() == SYNTHETIC_PHRASE })
        } finally {
            owned.forEach { it.fill('\u0000') }
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
            drainDialogLifecycle()
        }
    }

    @Test fun hostDestructionBeforeAcceptedSecretDeliveryCannotCompleteTheRequest() = runTest {
        for (kind in listOf(Prompt.SETUP, Prompt.RECOVERY)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val application = controller.get().application as LifetimeApplication
            val callbacksBefore = application.activeCallbacks.size
            var returned = false
            val command = async { kind.present(controller.get()).also { returned = true } }
            try {
                drainDialogLifecycle()
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                val input = dialogViews(dialog).filterIsInstance<EditText>().single()
                input.setText(SYNTHETIC_PHRASE)
                dialogViews(dialog).filterIsInstance<CheckBox>().forEach { it.isChecked = true }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                // Android's dismissal/continuation delivery is still queued. Destroy the actual
                // initiating Activity before draining either queue, with no caller cancellation.
                assertFalse(command.isCompleted)
                controller.pause().stop().destroy()
                drainDialogLifecycle()
                assertTrue(command.isCancelled)
                assertFalse("An accepted but undelivered secret is not a completed request", returned)
                assertTrue(input.text.isEmpty())
                assertEquals(callbacksBefore, application.activeCallbacks.size)
            } finally {
                command.cancel()
                if (!controller.get().isDestroyed) controller.pause().stop().destroy()
                drainDialogLifecycle()
            }
        }
    }

    private enum class Prompt {
        SETUP, RECOVERY, METHOD, CONSENT, REVIEW;

        suspend fun present(activity: Activity): Any? = when (this) {
            SETUP -> activity.presentBackupSetupPhrase(BackupRecoveryPhraseGenerator { SYNTHETIC_PHRASE })
            RECOVERY -> activity.presentBackupRestorePhrase()
            METHOD -> activity.presentBackupRestoreMethod(deviceKeyAvailable = false, passkeyAvailable = false)
            CONSENT -> activity.presentBackupPasskeyConsent()
            REVIEW -> activity.presentBackupRestoreReview("Native fixture change counts", requiresResolverConfirmation = true)
        }
    }

    private data class Returned(val value: Any?)

    class LifetimeApplication : Application() {
        val activeCallbacks = mutableSetOf<Application.ActivityLifecycleCallbacks>()
        override fun registerActivityLifecycleCallbacks(callback: Application.ActivityLifecycleCallbacks) {
            super.registerActivityLifecycleCallbacks(callback)
            activeCallbacks += callback
        }
        override fun unregisterActivityLifecycleCallbacks(callback: Application.ActivityLifecycleCallbacks) {
            super.unregisterActivityLifecycleCallbacks(callback)
            activeCallbacks -= callback
        }
    }

    private fun TestScope.drainDialogLifecycle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    private fun dialogViews(dialog: Dialog): List<View> {
        val result = mutableListOf<View>()
        fun visit(view: View) {
            result += view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(checkNotNull(dialog.window).decorView)
        return result
    }

    private companion object {
        const val SYNTHETIC_PHRASE = "bada bede bidi bodo budu bydy caba cebe"
    }
}
