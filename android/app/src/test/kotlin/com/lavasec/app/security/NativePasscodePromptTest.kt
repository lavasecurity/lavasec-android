package com.lavasec.app.security

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import com.lavasec.app.reactnative.NativeCommandAdmission
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.lavasec.app.presentation.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class NativePasscodePromptTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    @Config(sdk = [28, 36], application = LifetimeApplication::class)
    fun `credential management keeps wrong-current dialog live and never completes it`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val application = activity.application as LifetimeApplication
        val callbacksBefore = application.activeCallbacks.size
        val prompt = async {
            activity.presentPasscodeManagement(true,
                setPasscode = { error("existing credential cannot be set") },
                changePasscode = { _, _ -> SecurityCredentialMutationResult.WRONG_PASSCODE },
                removePasscode = { SecurityCredentialMutationResult.WRONG_PASSCODE })
        }
        try {
            drainDialogLifecycle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(callbacksBefore + 1, application.activeCallbacks.size)
            val fields = dialogViews(dialog).filterIsInstance<EditText>()
            assertEquals(3, fields.size)
            fields.forEach { assertMaskedNativePassword(it, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) }
            fields[0].setText("0000"); fields[1].setText("5678"); fields[2].setText("5678")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            drainDialogLifecycle()
            assertTrue(dialog.isShowing)
            assertFalse(prompt.isCompleted)
            assertTrue(fields.all { it.text.isEmpty() })
            fields.forEach { assertMaskedNativePassword(it, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) }
            assertTrue(dialogViews(dialog).filterIsInstance<TextView>().any {
                it.visibility == View.VISIBLE && it.text.toString() == activity.getString(R.string.security_wrong_passcode)
            })
            fields[0].setText("0000")
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            drainDialogLifecycle()
            assertTrue(dialog.isShowing)
            assertFalse(prompt.isCompleted)
            assertTrue(fields.all { it.text.isEmpty() })
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            drainDialogLifecycle()
            assertTrue("Actual Cancel delivers the native dismissal callback", prompt.isCompleted)
            assertFalse(prompt.await())
            assertEquals(callbacksBefore, application.activeCallbacks.size)
        } finally {
            prompt.cancel()
            drainDialogLifecycle()
            controller.pause().stop().destroy()
            drainDialogLifecycle()
        }
    }

    @Test
    @Config(sdk = [28, 36], application = LifetimeApplication::class)
    fun `credential management retains storage failure for a truthful retry`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val application = activity.application as LifetimeApplication
        val callbacksBefore = application.activeCallbacks.size
        var attempts = 0
        val prompt = async {
            activity.presentPasscodeManagement(false,
                setPasscode = {
                    attempts += 1
                    if (attempts == 1) SecurityCredentialMutationResult.SECURE_STORAGE
                    else SecurityCredentialMutationResult.COMPLETED
                },
                changePasscode = { _, _ -> error("setup cannot change") },
                removePasscode = { error("setup cannot remove") })
        }
        try {
            drainDialogLifecycle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val fields = dialogViews(dialog).filterIsInstance<EditText>()
            assertEquals(2, fields.size)
            fields.forEach { assertMaskedNativePassword(it, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) }
            fields.forEach { it.setText("1234") }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            drainDialogLifecycle()
            assertTrue(dialog.isShowing)
            assertFalse(prompt.isCompleted)
            assertTrue(fields.all { it.text.isEmpty() })
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertTrue(dialogViews(dialog).filterIsInstance<TextView>().any {
                it.visibility == View.VISIBLE && it.text.toString() == activity.getString(R.string.security_unavailable_title)
            })
            fields.forEach { it.setText("1234") }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            drainDialogLifecycle()
            assertEquals(2, attempts)
            assertFalse(dialog.isShowing)
            assertTrue("Successful native dismissal settles its own prompt", prompt.isCompleted)
            assertTrue(prompt.await())
            assertEquals(callbacksBefore, application.activeCallbacks.size)
        } finally {
            prompt.cancel()
            drainDialogLifecycle()
            controller.pause().stop().destroy()
            drainDialogLifecycle()
        }
    }

    @Test fun `credential management cannot accept late completion after Cancel`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val release = CompletableDeferred<SecurityCredentialMutationResult>()
        var mutationStarted = false
        val prompt = async {
            activity.presentPasscodeManagement(false,
                setPasscode = { mutationStarted = true; release.await() },
                changePasscode = { _, _ -> error("setup cannot change") },
                removePasscode = { error("setup cannot remove") })
        }
        try {
            drainDialogLifecycle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val fields = dialogViews(dialog).filterIsInstance<EditText>()
            assertEquals(2, fields.size)
            fields.forEach { it.setText("1234") }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            drainDialogLifecycle()
            assertTrue(mutationStarted)
            assertTrue(fields.all { it.text.isEmpty() })
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            drainDialogLifecycle()
            assertFalse("Cancel closes the actual dialog before the suspended operation finishes", dialog.isShowing)
            assertFalse("The operation remains suspended until the fixture releases it", release.isCompleted)
            release.complete(SecurityCredentialMutationResult.COMPLETED)
            drainDialogLifecycle()
            assertTrue("The cancelled native attempt settles after its suspended operation finishes", prompt.isCompleted)
            assertFalse("A dismissed native attempt cannot accept a late completed mutation", prompt.await())
        } finally {
            prompt.cancel()
            drainDialogLifecycle()
            controller.pause().stop().destroy()
            drainDialogLifecycle()
        }
    }

    @Test
    @Config(sdk = [28, 36], application = LifetimeApplication::class)
    fun `destroyed management host cancels only its presentation and releases retained admission`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val other = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val application = activity.application as LifetimeApplication
        val callbacksBefore = application.activeCallbacks.size
        val retainedScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val admission = NativeCommandAdmission()
        var writes = 0
        val command = retainedScope.async {
            admission.run("native.flow") {
                activity.presentPasscodeManagement(false,
                    setPasscode = { writes += 1; SecurityCredentialMutationResult.COMPLETED },
                    changePasscode = { _, _ -> error("setup cannot change") },
                    removePasscode = { error("setup cannot remove") })
            }
        }
        try {
            drainDialogLifecycle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val fields = dialogViews(dialog).filterIsInstance<EditText>()
            assertEquals(2, fields.size)
            fields.forEach { it.setText("1234") }
            assertEquals(callbacksBefore + 1, application.activeCallbacks.size)
            other.pause().stop().destroy()
            drainDialogLifecycle()
            assertFalse("Another Activity cannot retire this management command", command.isCompleted)
            assertTrue(dialog.isShowing)
            assertEquals("A retained mutation must leave the independent read lane available",
                "current-query", admission.run("share.query") { "current-query" })
            assertNull(admission.run("filter.apply") { true })

            // A finishing pause must cancel before a shared dialog's pause callback can
            // mistake host closure for ordinary user dismissal.
            activity.finish()
            controller.pause()
            drainDialogLifecycle()
            assertTrue("A finishing host retires the native command before window dismissal", command.isCancelled)
            controller.stop().destroy()
            drainDialogLifecycle()
            assertTrue(command.isCompleted)
            assertTrue("Host destruction is cancellation, never accepted mutation", command.isCancelled)
            assertFalse(dialog.isShowing)
            assertTrue("Retirement clears native inputs directly", fields.all { it.text.isEmpty() })
            assertEquals(0, writes)
            assertEquals(callbacksBefore, application.activeCallbacks.size)
            assertTrue("The retained module-like scope survives the presentation", retainedScope.coroutineContext.job.isActive)
            val next = retainedScope.async { admission.run("filter.apply") { "actual-next-command" } }
            drainDialogLifecycle()
            assertEquals("actual-next-command", next.await())
        } finally {
            command.cancel()
            retainedScope.cancel()
            drainDialogLifecycle()
            if (!other.get().isDestroyed) other.pause().stop().destroy()
            if (!activity.isDestroyed) controller.pause().stop().destroy()
            drainDialogLifecycle()
        }
    }

    @Test
    @Config(sdk = [28, 36], application = LifetimeApplication::class)
    fun `backgrounding cancels a pending credential mutation before it can write`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val retained = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val admission = NativeCommandAdmission()
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        var writes = 0
        val command = retained.async { admission.run("native.flow") {
            activity.presentPasscodeManagement(false,
                setPasscode = { entered.complete(Unit); release.await(); writes++; SecurityCredentialMutationResult.COMPLETED },
                changePasscode = { _, _ -> error("setup cannot change") }, removePasscode = { error("setup cannot remove") })
        } }
        try {
            drainDialogLifecycle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialogViews(dialog).filterIsInstance<EditText>().forEach { it.setText("1234") }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drainDialogLifecycle()
            assertTrue(entered.isCompleted)
            controller.pause(); drainDialogLifecycle()
            assertFalse(activity.isFinishing); assertTrue(command.isCancelled); assertFalse(dialog.isShowing)
            release.complete(Unit); drainDialogLifecycle()
            assertEquals(0, writes); assertEquals("next", admission.run("filter.apply") { "next" })
            assertTrue(retained.coroutineContext.job.isActive)
        } finally {
            release.complete(Unit); command.cancel(); retained.cancel(); drainDialogLifecycle()
            controller.stop().destroy(); drainDialogLifecycle()
        }
    }

    @Test
    @Config(sdk = [28, 36], application = LifetimeApplication::class)
    fun `host destruction refuses late mutation completion and retains admission until its child settles`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val application = activity.application as LifetimeApplication
        val callbacksBefore = application.activeCallbacks.size
        val retainedScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val admission = NativeCommandAdmission()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<SecurityCredentialMutationResult>()
        var returned = false
        lateinit var mutationJob: kotlinx.coroutines.Job
        val command = retainedScope.async {
            admission.run("native.flow") {
                activity.presentPasscodeManagement(false,
                    // Models a storage operation that has already crossed its uncancellable edge.
                    setPasscode = { mutationJob = kotlinx.coroutines.currentCoroutineContext().job; entered.complete(Unit); withContext(NonCancellable) { release.await() } },
                    changePasscode = { _, _ -> error("setup cannot change") },
                    removePasscode = { error("setup cannot remove") })
                    .also { returned = true }
            }
        }
        try {
            drainDialogLifecycle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val fields = dialogViews(dialog).filterIsInstance<EditText>()
            fields.forEach { it.setText("1234") }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            drainDialogLifecycle()
            assertTrue(entered.isCompleted)
            controller.pause()
            drainDialogLifecycle()
            assertFalse(activity.isFinishing)
            assertTrue("Backgrounding cancels the mutation before any destruction callback", mutationJob.isCancelled)
            controller.stop().destroy()
            drainDialogLifecycle()
            assertFalse(dialog.isShowing)
            assertTrue(fields.all { it.text.isEmpty() })
            assertFalse("An in-progress child is not silently detached", command.isCompleted)
            assertEquals("The admitted storage child must not hold the independent read lane",
                "current-query", admission.run("share.query") { "current-query" })
            assertNull("The lane cannot be released ahead of the admitted storage child",
                admission.run("filter.apply") { true })
            release.complete(SecurityCredentialMutationResult.COMPLETED)
            drainDialogLifecycle()
            assertTrue(command.isCompleted)
            assertTrue(command.isCancelled)
            assertFalse("A late storage result cannot complete the retired presentation", returned)
            assertEquals(callbacksBefore, application.activeCallbacks.size)
            assertTrue(retainedScope.coroutineContext.job.isActive)
            assertEquals("next", admission.run("filter.apply") { "next" })
        } finally {
            release.complete(SecurityCredentialMutationResult.COMPLETED)
            command.cancel()
            retainedScope.cancel()
            drainDialogLifecycle()
            if (!activity.isDestroyed) controller.pause().stop().destroy()
            drainDialogLifecycle()
        }
    }

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

    /** Android posts Dialog.onDismiss to its Handler, separately from the coroutine dispatcher. */
    private fun TestScope.drainDialogLifecycle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    private fun dialogViews(dialog: android.app.Dialog): List<View> {
        val result = mutableListOf<View>()
        fun visit(view: View) {
            result += view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(checkNotNull(dialog.window).decorView)
        return result
    }

    /** Exercises the actual field transformation and framework AX initialization, with no shadow flags. */
    private fun assertMaskedNativePassword(field: EditText, expectedInputType: Int) {
        assertTrue("Framework AX assertions require the actual field attached to its shown dialog", field.isAttachedToWindow)
        assertEquals(expectedInputType, field.inputType)
        assertEquals(1, field.maxLines)
        assertTrue("Single-line credential input retains Android's password transformation",
            field.transformationMethod is PasswordTransformationMethod)
        assertFalse(field.isSaveEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field.importantForAutofill)
        field.setText("12345678")
        try {
            val displayed = checkNotNull(field.transformationMethod).getTransformation(field.text, field)
            assertEquals("The displayed mask keeps the entered length", field.text.length, displayed.length)
            assertTrue("The actual password transformation conceals every synthetic digit", displayed.none { it.isDigit() })
            val node = field.createAccessibilityNodeInfo()
            try {
                assertEquals("android.widget.EditText", node.className?.toString())
                assertTrue("The framework exposes the actual credential field as editable", node.isEditable)
                assertTrue("The framework recognizes the real password transformation", node.isPassword)
                assertEquals(expectedInputType, node.inputType)
                assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        } finally {
            field.text.clear()
        }
    }

    @Test fun `dismissal revokes immediately while verification is suspended`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val verifying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        val prompt = launch {
            activity.presentPasscodeAuthentication({
                verifying.complete(Unit)
                release.await()
                true
            }, { cancelled = true })
        }
        try {
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val input = dialog.findViewById<EditText>(android.R.id.edit)
            input.setText("1234")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals("", input.text.toString())
            runCurrent()
            assertTrue(verifying.isCompleted)
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(cancelled)
            assertFalse(release.isCompleted)
            release.complete(Unit)
            prompt.join()
        } finally { prompt.cancel(); controller.pause().stop().destroy() }
    }

    @Test fun `owner cleanup after verification never reports user cancellation`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var verified = false
        var cancelled = false
        val prompt = launch {
            controller.get().presentPasscodeAuthentication({ verified = true; true }, { cancelled = true })
        }
        try {
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.findViewById<EditText>(android.R.id.edit).setText("1234")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            runCurrent()
            assertTrue(verified)
            // SecurityOwner cancels its presenter once the authentication result is available.
            prompt.cancel()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertFalse(cancelled)
        } finally { prompt.cancel(); controller.pause().stop().destroy() }
    }

    @Test
    @Config(sdk = [28, 36])
    fun `wrong code stays in the same secure prompt and cancellation clears text`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val prompt = launch { controller.get().presentPasscodeAuthentication({ false }, {}) }
        try {
            // Dialog.show posts native attachment separately from the coroutine dispatcher.
            drainDialogLifecycle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val input = dialog.findViewById<EditText>(android.R.id.edit)
            assertMaskedNativePassword(input, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            assertFalse(input.isSaveEnabled)
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            input.setText("wrong")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            drainDialogLifecycle()
            assertTrue(dialog.isShowing)
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertTrue(input.error != null)
            input.setText("unfinished")
            prompt.cancel()
            drainDialogLifecycle()
            assertFalse(dialog.isShowing)
            assertEquals("", input.text.toString())
        } finally {
            prompt.cancel()
            drainDialogLifecycle()
            controller.pause().stop().destroy()
            drainDialogLifecycle()
        }
    }
}
