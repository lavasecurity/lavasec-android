package com.lavasec.app.backup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.R
import com.lavasec.app.reactnative.LavaAppModule
import com.lavasec.app.reactnative.NativeCommandAdmission
import com.lavasec.app.reactnative.ReactEmulatorJourney
import com.lavasec.app.security.AndroidKeystorePasscodeVerifier
import com.lavasec.app.security.PasscodeCredentialState
import com.lavasec.app.security.SecurityPreferenceStore
import com.lavasec.app.settings.ProductSettingsStore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed native-port lifetime evidence with real LavaReactActivity windows. The controlled
 * request uses the production prompt and NativeCommandAdmission, without a signed-in account or
 * replacement product facts. It is separate from the legitimately refused signed-out backup flow.
 * API28 proves native field presence, request cancellation and lane/context reuse through AX;
 * direct retained View clearing is inspected only with the public API29+ WindowInspector.
 */
@RunWith(AndroidJUnit4::class)
class NativeBackupPromptLifetimeInstrumentationTest {
    @Test fun destroyedSetupHostCancelsNativePortAndRetainedAdmissionAcceptsSuccessorCancellation() =
        assertNativeHostLifetime(setup = true)

    @Test fun destroyedRecoveryHostCancelsNativePortAndRetainedAdmissionAcceptsSuccessorCancellation() =
        assertNativeHostLifetime(setup = false)

    private fun assertNativeHostLifetime(setup: Boolean) {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            val library = checkNotNull(product.currentFilterLibrary())
            val preferences = ProductSettingsStore(journey.context)
            val originalPreferences = preferences.loadPreferences()
            val security = SecurityPreferenceStore(journey.context)
            val originalSecurity = security.load()
            val verifier = AndroidKeystorePasscodeVerifier(journey.context)
            assertEquals("Never replace an existing credential", PasscodeCredentialState.Absent, verifier.credentialState())
            assertFalse(originalSecurity.enabled)
            assertTrue(originalSecurity.protectedSurfaces.isEmpty())
            assertFalse(journey.snapshot().getJSONObject("account").getBoolean("signedIn"))
            assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            val flow = if (setup) "backupSetup" else "backupRestore"
            val refused = journey.command("native.flow", JSONObject().put("flow", flow))
            assertEquals("rejected", refused.getString("status"))
            assertEquals(if (setup) "backup-setup-sign-in-required" else "backup-restore-sign-in-required",
                refused.getString("reason"))

            val originalActivity = journey.requireActivity()
            val reactContext = checkNotNull(journey.application.reactHost.currentReactContext)
            val module = checkNotNull(reactContext.getNativeModule(LavaAppModule::class.java))
            fun requireOriginalAuthority() {
                assertTrue(journey.application.foregroundProduct === product)
                assertEquals(generation, product.state.value.graphGeneration)
                assertTrue(journey.application.reactHost.currentReactContext === reactContext)
                assertTrue(reactContext.getNativeModule(LavaAppModule::class.java) === module)
            }
            requireOriginalAuthority()
            assertTrue(reactContext.currentActivity === originalActivity)
            // This retained test-owned scope/admission is a native-port fixture. It does not
            // replace the real module, call its private members or bypass a backup account gate.
            val retainedScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val admission = NativeCommandAdmission()
            val terminal = AtomicReference<PortTerminal?>()
            val handoff = AtomicReference<CharArray?>()
            var primaryFailure: Throwable? = null
            val hint = journey.context.getString(if (setup) R.string.backup_setup_confirm_hint else R.string.backup_restore_hint)
            val request = retainedScope.async {
                try {
                    val outcome = admission.run("native.flow") {
                        handoff.set(if (setup) originalActivity.presentBackupSetupPhrase(
                            BackupRecoveryPhraseGenerator { SYNTHETIC_PHRASE })?.phrase
                        else originalActivity.presentBackupRestorePhrase())
                        PortTerminal.RETURNED
                    }
                    terminal.set(outcome ?: PortTerminal.BUSY)
                } catch (cancelled: CancellationException) {
                    terminal.set(PortTerminal.CANCELLED)
                    throw cancelled
                }
            }
            try {
                val field = requireNativeField(journey, hint)
                assertTrue(field.refresh() && field.isEnabled && field.isEditable)
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, SYNTHETIC_PHRASE) }))
                journey.await("the real native fixture input contains only its synthetic phrase") {
                    requireOriginalAuthority()
                    field.refresh() && field.text?.toString() == SYNTHETIC_PHRASE
                }
                val nativeViews = if (Build.VERSION.SDK_INT >= 29) captureNativeViews(journey, originalActivity, hint, setup)
                    else emptyList()
                assertFalse("The untouched native request still owns its admission", request.isCompleted)
                assertNull("The actual admission must refuse a concurrent mutation", runBlocking {
                    admission.run("native.flow") { PortTerminal.RETURNED }
                })

                // No dialog dismiss/cancel, request cancellation, owner mutation or account grant.
                journey.reopen()
                assertTrue(originalActivity.isDestroyed)
                requireOriginalAuthority()
                assertTrue(reactContext.currentActivity === journey.requireActivity())
                journey.await("actual backup host destruction cancels its suspended request") { request.isCompleted }
                assertTrue(request.isCancelled)
                assertEquals(PortTerminal.CANCELLED, terminal.get())
                assertNull("A destroyed prompt cannot hand a secret to setup or restore", handoff.get())
                assertTrue("The retained fixture scope survives the exact prompt host", retainedScope.coroutineContext.job.isActive)
                if (Build.VERSION.SDK_INT >= 29) journey.instrumentation.runOnMainSync {
                    assertTrue("Captured original native input/phrase views are cleared", nativeViews.all { it.text.isEmpty() })
                }

                val successorHost = journey.requireActivity()
                val successor = retainedScope.async {
                    admission.run("native.flow") { PortReturned(successorHost.presentBackupRestorePhrase()) }
                }
                val successorField = requireNativeField(journey, journey.context.getString(R.string.backup_restore_hint))
                assertTrue(successorField.refresh() && successorField.isEnabled)
                assertTrue(successorField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, SYNTHETIC_PHRASE) }))
                request.cancel() // Spent old-request cleanup must not affect this successor host.
                journey.await("the successor native recovery window remains operable") {
                    requireOriginalAuthority()
                    successorField.refresh() && successorField.isEnabled && successorField.text?.toString() == SYNTHETIC_PHRASE
                }
                requireOriginalAuthority()
                val cancelLabel = journey.context.getString(android.R.string.cancel)
                val successorWindow = successorField.windowId
                fun nativeButtons() = journey.accessibilityNodes().filter {
                    it.packageName?.toString() == journey.context.packageName && it.windowId == successorWindow &&
                        it.isVisibleToUser && it.className?.toString() == "android.widget.Button"
                }
                // Android native button styles can expose all-caps AX text. Match the same
                // localized resource, without broadening to a different action or window.
                fun namedCancel(node: AccessibilityNodeInfo) = node.text?.toString()?.equals(cancelLabel, ignoreCase = true) == true
                var cancel: AccessibilityNodeInfo? = null
                try {
                    journey.await("one fresh enabled Cancel in the successor native dialog") {
                        requireOriginalAuthority()
                        if (!successorField.refresh() || successorField.windowId != successorWindow) return@await false
                        val candidates = nativeButtons().filter(::namedCancel)
                        assertTrue("The same-window native Cancel must be unique", candidates.size <= 1)
                        cancel = candidates.singleOrNull()?.takeIf {
                            it.refresh() && namedCancel(it) && it.isEnabled && it.isClickable &&
                                nativeButtons().filter(::namedCancel).singleOrNull() == it
                        }
                        cancel != null
                    }
                } catch (failure: AssertionError) {
                    // Only native button shape and resource-match flags; never input/account text.
                    val facts = runCatching { nativeButtons().take(8).map {
                        "exactCase=${it.text?.toString() == cancelLabel},caseFolded=${namedCancel(it)},enabled=${it.isEnabled},clickable=${it.isClickable}"
                    } }.getOrNull()
                    println("LAVA_UI_BACKUP_PROMPT_CANCEL sdk=${Build.VERSION.SDK_INT} window=$successorWindow buttonFacts=$facts")
                    throw failure
                }
                requireOriginalAuthority()
                val control = checkNotNull(cancel)
                assertTrue(successorField.refresh() && successorField.windowId == successorWindow)
                assertTrue(control.refresh() && namedCancel(control) && control.isEnabled && control.isClickable &&
                    nativeButtons().filter(::namedCancel).singleOrNull() == control)
                assertTrue("Exactly one genuine native Cancel action is accepted", control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                journey.await("the admitted successor native Cancel completes") { successor.isCompleted }
                val cancelled = runBlocking { successor.await() }
                assertNotNull("The same real admission accepted the successor prompt", cancelled)
                assertNull(cancelled!!.secret)
                requireOriginalAuthority()
                assertEquals(library, product.currentFilterLibrary())
                assertEquals(originalPreferences, preferences.loadPreferences())
                assertEquals(originalSecurity, security.load())
                assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
                val rows = journey.snapshot().getJSONObject("filters").getJSONArray("rows")
                val selected = (0 until rows.length()).map(rows::getJSONObject).single { it.getBoolean("active") }
                val share = journey.completed("share.query", JSONObject().put("id", selected.getString("id"))).getJSONObject("value")
                assertNotNull("The unchanged real retained module also accepts one genuine next query", share.optJSONObject("card"))
                assertEquals(library, product.currentFilterLibrary())
                journey.assertNoFallback()
                println("LAVA_UI_BACKUP_PROMPT_LIFETIME port=${if (setup) "setup" else "recovery"} host=destroyed request=cancelled context=retained successor=real-native-cancel signedOutBridge=refused directViewClearing=${if (Build.VERSION.SDK_INT >= 29) "public-inspected" else "unavailable-api28"} arrayClearing=device-unobserved")
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                try {
                    retainedScope.cancel()
                    try {
                        runBlocking { withTimeout(5_000) { retainedScope.coroutineContext.job.join() } }
                    } finally { handoff.get()?.fill('\u0000') }
                    journey.instrumentation.waitForIdleSync()
                } catch (cleanup: Throwable) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    private fun requireNativeField(journey: ReactEmulatorJourney, hint: String): AccessibilityNodeInfo {
        var field: AccessibilityNodeInfo? = null
        journey.await("one actual native backup input with its fixed resource hint") {
            val fields = journey.accessibilityNodes().filter {
                it.packageName?.toString() == journey.context.packageName && it.isVisibleToUser && it.isEditable &&
                    it.className?.toString() == "android.widget.EditText" && it.hintText?.toString() == hint
            }
            assertTrue("A native backup fixture field must be unique", fields.size <= 1)
            field = fields.singleOrNull()?.takeIf { it.refresh() && it.isEnabled && it.isEditable }
            field != null
        }
        return checkNotNull(field)
    }

    @RequiresApi(29)
    private fun captureNativeViews(journey: ReactEmulatorJourney, host: Activity, hint: String, setup: Boolean): List<TextView> {
        var retained = emptyList<TextView>()
        journey.instrumentation.runOnMainSync {
            val descendants = WindowInspector.getGlobalWindowViews().filter { root ->
                root !== host.window.decorView && root.isAttachedToWindow && contextHost(root.context) === host
            }.flatMap(::views)
            val fields = descendants.filterIsInstance<EditText>().filter { it.hint?.toString() == hint && it.isShown }
            assertEquals("Public window inspection captures the exact original native input", 1, fields.size)
            assertEquals(SYNTHETIC_PHRASE, fields.single().text.toString())
            val phrases = descendants.filterIsInstance<TextView>().filter {
                it !is EditText && it.isShown && it.text.toString() == SYNTHETIC_PHRASE
            }
            if (setup) assertEquals("The setup phrase is the actual native displayed fixture", 1, phrases.size)
            retained = fields + if (setup) phrases else emptyList()
        }
        return retained
    }

    private fun contextHost(initial: Context): Activity? {
        var context: Context? = initial
        repeat(12) {
            when (val current = context) {
                is Activity -> return current
                is ContextWrapper -> context = current.baseContext.takeUnless { it === current }
                else -> return null
            }
        }
        return null
    }

    private fun views(root: View): List<View> = buildList {
        fun visit(view: View, depth: Int) {
            check(size < 400 && depth < 16) { "Owned native view traversal exceeded its bound" }
            add(view)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it), depth + 1) }
        }
        visit(root, 0)
    }

    private enum class PortTerminal { CANCELLED, RETURNED, BUSY }
    private data class PortReturned(val secret: CharArray?)
    private companion object {
        const val SYNTHETIC_PHRASE = "bada bede bidi bodo budu bydy caba cebe"
    }
}
