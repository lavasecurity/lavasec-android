package com.lavasec.app.reactnative

import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.NoCopySpan
import android.text.SpanWatcher
import android.text.Spannable
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facebook.react.views.textinput.ReactEditText
import com.lavasec.app.settings.ProductSettingsStore
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Platform InputConnection evidence on the packaged LavaTextField's two shipping callers.
 * The CJK strings are synthetic invalid input, never a saved rule or imported configuration.
 * No keyboard is installed/selected, locale changed, or paid/authentication fact supplied.
 * The test temporarily prevents its own focusable Activity window from targeting the installed
 * IME before either editor mounts, using a brief fixture-owned noneditable window focus handoff.
 * The direct platform InputConnection is then its sole text input
 * producer; original window flags are restored after that connection/observer closes.
 * This is not installed CJK IME candidate-window, TalkBack, or speech qualification.
 *
 * Both current shipping callers pass resetRevision=0. Cancel/reopen below exercises their real
 * route-unmount reset; the revision-triggered reset hook remains separate source/Jest evidence.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactTextFieldInputConnectionTest {
    @Test
    fun shippingDomainCompositionReplacementFinishSubmitAndCancelResetUseTheNativeEditor() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val preserved = preservation(journey)
            assertFalse("Preserve a pre-existing rule editor", preserved.product.state.value.filterDetail?.editing == true)
            journey.clickGuardFiltering(journey.requireActivity(), preserved.generation)
            journey.waitLabel("Switch or manage filters")
            journey.click("Now filtering", prefix = true)
            journey.waitLabel("Lava blocks these")
            val id = checkNotNull(preserved.product.state.value.filterDetail).filterId
            var ownsDraft = false
            var editor: NativeEditor? = null
            var failure: Throwable? = null
            val ime = OwnedImeIsolation(journey)
            try {
                preserved.assertCurrent()
                ime.acquire()
                fun begin() {
                    preserved.assertCurrent()
                    assertFalse("A replacement draft must not be consumed", checkNotNull(preserved.product.state.value.filterDetail).editing)
                    journey.click("Edit", scroll = false)
                    ownsDraft = true
                    journey.await("the exact active filter's unchanged native editor") {
                        preserved.product.state.value.filterDetail?.let {
                            it.filterId == id && it.editing && !it.draftHasChanges && !it.busy
                        } == true
                    }
                    val detail = checkNotNull(preserved.product.state.value.filterDetail)
                    assertTrue("Use an available real free-plan domain slot", detail.blockedDomains.size < detail.maxBlockedDomains)
                    journey.scrollToTop()
                    journey.click("Block a domain")
                    journey.waitLabel("Add blocked domain")
                }
                fun cancel() {
                    hideOwnedKeyboard(journey, checkNotNull(editor).view)
                    journey.back()
                    journey.waitLabel("Cancel editing")
                    assertUnchangedDraft(journey, preserved, id)
                    journey.click("Cancel editing", scroll = false)
                    journey.await("real unchanged filter cancellation") { preserved.product.state.value.filterDetail?.editing == false }
                    ownsDraft = false
                    preserved.assertCurrent()
                }

                begin()
                editor = nativeEditor(journey, "Domain to block", multiline = false, ime = ime)
                val first = checkNotNull(editor)
                first.expect("")
                journey.waitEnabled("Add domain", false)
                first.change("kana composing") { setComposingText("かな", 1) }
                first.expect("かな", 0, 2)
                first.change("CJK candidate replacement") { setComposingText("仮名", 1) }
                first.expect("仮名", 0, 2)
                first.change("reconversion region") { setComposingRegion(0, 2) }
                first.change("replace composing region with invalid CJK domain") { setComposingText("中文 テスト", 1) }
                first.expect("中文 テスト", 0, "中文 テスト".length)
                journey.waitEnabled("Add domain", true)
                // JS receives the changes without echoing a controlled value over composition.
                first.expect("中文 テスト", 0, "中文 テスト".length)
                assertUnchangedDraft(journey, preserved, id)
                first.change("finish candidate") { finishComposingText() }
                first.expect("中文 テスト")
                first.change("append Korean composition") { setComposingText("취소", 1) }
                first.expect("中文 テスト취소", "中文 テスト".length, "中文 テスト취소".length)
                // InputConnection has no universal cancel-candidate method. An IME can replace
                // its current composing range with an empty commit; only that range is removed.
                first.change("cancel only the current composing range") { commitText("", 1) }
                first.expect("中文 テスト")
                first.change("real single-line editor submission") { performEditorAction(EditorInfo.IME_ACTION_DONE) }
                journey.waitLabel("Cannot add domain")
                journey.waitLabel("Enter a valid domain, such as example.com.")
                first.expect("中文 テスト")
                assertUnchangedDraft(journey, preserved, id)
                first.close()
                cancel()
                journey.await("cancelled native domain leaf detached") { !onMain(journey) { first.view.isAttachedToWindow } }

                begin()
                editor = nativeEditor(journey, "Domain to block", multiline = false, ime = ime)
                assertNotSame("Reopening creates the real fresh native buffer", first.view, checkNotNull(editor).view)
                checkNotNull(editor).expect("")
                journey.waitEnabled("Add domain", false)
                assertFalse("The old invalid-input result must not survive route reset", journey.visible("Cannot add domain"))
                checkNotNull(editor).change("composition before route cancellation") { setComposingText("取消", 1) }
                checkNotNull(editor).expect("取消", 0, 2)
                cancel()
                journey.assertNoFallback()
                println("LAVA_UI_TEXTFIELD mode=single-line composition=replaced-finished-cancelled submit=native-refusal routeReset=empty nativeState=preserved")
            } catch (problem: Throwable) {
                failure = problem
                throw problem
            } finally {
                try {
                    editor?.close()
                    preserved.assertCurrent()
                    if (ownsDraft) {
                        assertUnchangedDraft(journey, preserved, id)
                        // Failure cleanup is native-only, not UI acceptance. This atomic owner
                        // API refuses a changed draft instead of discarding somebody else's work.
                        assertTrue("Cleanup may finish only this unchanged filter editor", runBlocking {
                            preserved.product.finishUnchangedFilterEdit(id, preserved.generation)
                        })
                        journey.await("unchanged native fixture editor released") { preserved.product.state.value.filterDetail?.editing == false }
                    }
                    preserved.assertCurrent()
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else {
                        failure = cleanup
                        throw cleanup
                    }
                } finally {
                    try { ime.close() } catch (restore: Throwable) {
                        if (failure != null) failure.addSuppressed(restore) else throw restore
                    }
                }
            }
        }
    }

    @Test
    fun shippingSetupCodeMultilineCompositionCancelAndReopenResetUseTheNativeEditor() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val preserved = preservation(journey)
            assertFalse("Preserve a pre-existing rule editor", preserved.product.state.value.filterDetail?.editing == true)
            journey.clickGuardFiltering(journey.requireActivity(), preserved.generation)
            journey.waitLabel("Switch or manage filters")
            var editor: NativeEditor? = null
            var failure: Throwable? = null
            val ime = OwnedImeIsolation(journey)
            try {
                preserved.assertCurrent()
                ime.acquire()
                fun open() {
                    preserved.assertCurrent()
                    journey.click("Import", scroll = false)
                    journey.waitLabel("Import a filter")
                    journey.click("Enter a code")
                    journey.waitLabel("Enter a setup code")
                }
                fun cancel() {
                    preserved.assertCurrent()
                    hideOwnedKeyboard(journey, checkNotNull(editor).view)
                    // Import's shipping header Back leaves the code stage; Close then
                    // dismisses the import route. Submit each real header action once,
                    // independently of the focused editor/IME's hardware Back handling.
                    journey.click("Back", scroll = false, excludeEditable = true)
                    journey.waitLabel("Import a filter")
                    preserved.assertCurrent()
                    journey.click("Close", scroll = false, excludeEditable = true)
                    journey.waitLabel("Switch or manage filters")
                    preserved.assertCurrent()
                }
                open()
                editor = nativeEditor(journey, "Setup code", multiline = true, ime = ime)
                val first = checkNotNull(editor)
                first.expect("")
                journey.waitEnabled("Continue", false)
                first.change("Japanese composing candidate") { setComposingText("にほん", 1) }
                first.expect("にほん", 0, 3)
                first.change("Japanese candidate replacement") { setComposingText("日本語", 1) }
                first.expect("日本語", 0, 3)
                first.change("finish first line") { finishComposingText() }
                first.change("native multiline newline") { commitText("\n", 1) }
                first.change("second-line CJK composition") { setComposingText("中文", 1) }
                first.expect("日本語\n中文", 4, 6)
                first.change("finish second line") { finishComposingText() }
                first.change("native multiline paste") { commitText("\n한국어", 1) }
                first.expect("日本語\n中文\n한국어", lines = 3)
                first.change("first-line reconversion") { setComposingRegion(0, 3) }
                first.change("replace only first-line composing range") { setComposingText("仮名", 1) }
                first.expect("仮名\n中文\n한국어", 0, 2, selection = 2, lines = 3)
                first.change("finish reconverted line") { finishComposingText() }
                first.expect("仮名\n中文\n한국어", selection = 2, lines = 3)
                first.change("move the real editor cursor to its end") { setSelection(9, 9) }
                first.change("append composition without replacing other lines") { setComposingText("取消", 1) }
                first.expect("仮名\n中文\n한국어取消", 9, 11, lines = 3)
                first.change("cancel only appended composition") { commitText("", 1) }
                first.expect("仮名\n中文\n한국어", lines = 3)
                journey.waitEnabled("Continue", true)
                hideOwnedKeyboard(journey, first.view)
                journey.click("Continue", scroll = false)
                // This is the actual native decoder's refusal, reached by the real shared
                // onChange/Continue action. No valid setup or apply witness is injected.
                journey.waitLabel("That does not look like a Lava setup code.")
                first.expect("仮名\n中文\n한국어", lines = 3)
                preserved.assertCurrent()
                first.close()
                cancel()
                journey.await("cancelled native multiline leaf detached") { !onMain(journey) { first.view.isAttachedToWindow } }

                open()
                editor = nativeEditor(journey, "Setup code", multiline = true, ime = ime)
                assertNotSame("Cancelled import remounts a fresh native leaf", first.view, checkNotNull(editor).view)
                checkNotNull(editor).expect("")
                // The real remount focuses the editor; the owned window still isolates
                // its direct connection. Confirm the shipping footer is unobscured.
                hideOwnedKeyboard(journey, checkNotNull(editor).view)
                journey.waitEnabled("Continue", false)
                assertFalse("Import error is private to the cancelled route", journey.visible("Importing cannot continue"))
                checkNotNull(editor).change("active multiline composition before Cancel") { setComposingText("取消\n중단", 1) }
                checkNotNull(editor).expect("取消\n중단", 0, 5, lines = 2)
                cancel()
                open()
                val cancelled = checkNotNull(editor)
                editor = nativeEditor(journey, "Setup code", multiline = true, ime = ime)
                assertNotSame(cancelled.view, checkNotNull(editor).view)
                cancelled.close()
                checkNotNull(editor).expect("")
                hideOwnedKeyboard(journey, checkNotNull(editor).view)
                journey.waitEnabled("Continue", false)
                cancel()
                journey.assertNoFallback()
                println("LAVA_UI_TEXTFIELD mode=multiline composition=reconverted-finished-cancelled lines=3 review=native-refusal routeReset=empty nativeState=preserved")
            } catch (problem: Throwable) {
                failure = problem
                throw problem
            } finally {
                try {
                    editor?.close()
                    preserved.assertCurrent()
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else {
                        failure = cleanup
                        throw cleanup
                    }
                } finally {
                    try { ime.close() } catch (restore: Throwable) {
                        if (failure != null) failure.addSuppressed(restore) else throw restore
                    }
                }
            }
        }
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

    private class Preservation(val journey: ReactEmulatorJourney) {
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        private val library = checkNotNull(product.currentFilterLibrary())
        private val logging = checkNotNull(product.currentQaLogging())
        private val preferences = ProductSettingsStore(journey.context).loadPreferences()
        private val inputMethods = InputMethods.capture(journey.context)
        private val configuration = journey.context.resources.configuration
        private val orientation = configuration.orientation
        private val fontScale = configuration.fontScale
        private val locales = configuration.locales.toLanguageTags()
        fun assertCurrent() {
            assertTrue("The exact original product owner must remain attached", journey.application.foregroundProduct === product)
            assertEquals("Preserve the original credential graph", generation, product.state.value.graphGeneration)
            assertEquals("Composition must never publish library/resolver/QA changes", library, product.currentFilterLibrary())
            assertEquals("Do not change logging or user-history retention", logging, product.currentQaLogging())
            assertEquals("Do not change device/app presentation preferences", preferences, ProductSettingsStore(journey.context).loadPreferences())
            assertEquals("Preserve the selected IME and enabled IMEs/explicit/effective subtypes", inputMethods,
                InputMethods.capture(journey.context))
            val currentConfiguration = journey.context.resources.configuration
            assertEquals("Do not rotate the emulator", orientation, currentConfiguration.orientation)
            assertEquals("Do not change the platform font scale", fontScale, currentConfiguration.fontScale, 0f)
            assertEquals("Do not change process/device languages", locales, currentConfiguration.locales.toLanguageTags())
            assertFalse("Do not obtain a paid test entitlement", journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
        }
    }

    private fun preservation(journey: ReactEmulatorJourney) = Preservation(journey).also {
        it.assertCurrent()
        assertFalse("Use the real unlocked emulator fixture", journey.snapshot().getJSONObject("filters").getBoolean("locked"))
    }

    private fun assertUnchangedDraft(journey: ReactEmulatorJourney, preserved: Preservation, id: String) {
        preserved.assertCurrent()
        val detail = checkNotNull(preserved.product.state.value.filterDetail)
        assertEquals(id, detail.filterId)
        assertTrue("The test must still own its actual unchanged editor", detail.editing)
        assertFalse("Invalid input cannot add or remove rules/sources", detail.draftHasChanges)
        assertFalse("Do not cancel an in-flight native commit", detail.busy)
    }

    /**
     * FLAG_ALT_FOCUSABLE_IM on a focusable window prevents it being an IME target (the public
     * WindowManager.LayoutParams contract). Acquire before an editor exists: changing the flag
     * after focus does not itself promise to close an already-served platform connection.
     */
    private class OwnedImeIsolation(private val journey: ReactEmulatorJourney) {
        private val window = onMain(journey) { journey.requireActivity().window }
        private val originalFlags = onMain(journey) { window.attributes.flags }
        private val isolatedFlags = originalFlags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        private val manager = onMain(journey) {
            window.decorView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        }
        private var acquired = false

        private fun currentWindow(): Boolean = journey.requireActivity().window === window &&
            window.decorView.isAttachedToWindow && window.decorView.hasWindowFocus() &&
            window.decorView.context.getSystemService(Context.INPUT_METHOD_SERVICE) === manager

        private fun noImeWindow(): Boolean = journey.instrumentation.uiAutomation.windows.none {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }

        /** Only Boolean/identity/flag/geometry facts: no contents, editor text or IME settings. */
        private fun dumpFacts(stage: String, expectedFlags: Int, view: View? = null): String {
            val native = onMain(journey) {
                val current = journey.requireActivity().window
                val currentManager = window.decorView.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                "windowIdentity=${System.identityHashCode(window)} currentWindowIdentity=${System.identityHashCode(current)} " +
                    "windowMatches=${current === window} attached=${window.decorView.isAttachedToWindow} " +
                    "focused=${window.decorView.hasWindowFocus()} currentWindow=${currentWindow()} " +
                    "flags=${window.attributes.flags} expectedFlags=$expectedFlags originalFlags=$originalFlags " +
                    "managerIdentity=${System.identityHashCode(manager)} currentManagerIdentity=${System.identityHashCode(currentManager)} " +
                    "managerMatches=${currentManager === manager} isActiveAny=${manager.isActive()} isAcceptingText=${manager.isAcceptingText()} " +
                    "leafIdentity=${view?.let(System::identityHashCode)} leafAttached=${view?.isAttachedToWindow} " +
                    "leafFocused=${view?.isFocused} leafWindowFocused=${view?.hasWindowFocus()} " +
                    "leafRootMatches=${view?.let { it.rootView === window.decorView }} " +
                    "isActiveExact=${view?.let { manager.isActive(it) }}"
            }
            val imeWindows = journey.instrumentation.uiAutomation.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .take(6).joinToString(";") { imeWindow ->
                    val bounds = Rect().also(imeWindow::getBoundsInScreen)
                    "id=${imeWindow.id},active=${imeWindow.isActive},focused=${imeWindow.isFocused},bounds=$bounds"
                }
            return "stage=$stage $native noImeWindow=${noImeWindow()} imeWindows=[$imeWindows]".also {
                println("LAVA_UI_TEXTFIELD_IME_FACTS $it")
            }
        }

        private fun awaitState(stage: String, expectedFlags: Int, description: String, view: View? = null,
            timeoutMillis: Long = 20_000, ready: () -> Boolean) {
            dumpFacts("$stage-before", expectedFlags, view)
            try {
                journey.await(description, timeoutMillis = timeoutMillis, condition = ready)
            } catch (failure: AssertionError) {
                try {
                    failure.addSuppressed(AssertionError("Redacted input-session facts: ${dumpFacts("$stage-failed", expectedFlags, view)}"))
                } catch (diagnostic: Throwable) {
                    failure.addSuppressed(diagnostic)
                }
                throw failure
            }
        }

        fun acquire() {
            assertFalse("Acquire the owned window only once", acquired)
            assertEquals("The baseline shipping window must be focusable and IME eligible", 0,
                originalFlags and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM))
            // isActive() can name a served non-editor with EditorInfo but no InputConnection.
            // isAcceptingText() is the public no-connection barrier at this pre-mount stage;
            // the exact editor also must fail isActive(view) once it actually exists below.
            awaitState("baseline", originalFlags, "the original list/detail has no served input connection before the editor mounts") {
                onMain(journey) { currentWindow() && window.attributes.flags == originalFlags &&
                    !manager.isAcceptingText() }
            }
            // The handoff and return share the existing 20s isolation deadline.
            val isolationDeadline = SystemClock.elapsedRealtime() + 20_000
            onMain(journey) {
                assertTrue("Never isolate a successor or changed Activity window", currentWindow() && window.attributes.flags == originalFlags)
                manager.hideSoftInputFromWindow(window.decorView.windowToken, 0)
            }
            acquireThroughNonEditor(isolationDeadline)
            awaitState("isolation", isolatedFlags, "the owned focusable window is isolated before either shipping editor mounts",
                timeoutMillis = remaining(isolationDeadline)) {
                onMain(journey) { currentWindow() && window.attributes.flags == isolatedFlags &&
                    !manager.isAcceptingText() } && noImeWindow()
            }
            println("LAVA_UI_TEXTFIELD_IME stage=isolated windowIdentity=${System.identityHashCode(window)} " +
                "managerIdentity=${System.identityHashCode(manager)} originalFlags=$originalFlags isolatedFlags=$isolatedFlags")
        }

        private fun remaining(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

        /**
         * Android 9's ViewRootImpl does not notify IMM when ALT changes an already-focused
         * window away from IME eligibility. View.onFocusChanged can consequently serve a new
         * editor through IMM's remembered Activity root. Retire that root through a real,
         * fixture-owned noneditable window before returning focus to the isolated Activity.
         * No private IMM state is read/written and no shipping editor exists during this step.
         */
        private fun acquireThroughNonEditor(deadline: Long) {
            var handoff: Dialog? = null
            var handoffWindow: Window? = null
            var nonEditor: View? = null
            var failure: Throwable? = null
            try {
                onMain(journey) {
                    assertTrue("Create the handoff only for the original eligible Activity window", currentWindow() &&
                        window.attributes.flags == originalFlags)
                    val activity = journey.requireActivity()
                    val dialog = Dialog(activity)
                    handoff = dialog
                    val nativeWindow = checkNotNull(dialog.window)
                    handoffWindow = nativeWindow
                    val content = View(activity).apply {
                        isFocusableInTouchMode = true
                        minimumWidth = 1
                        minimumHeight = 1
                    }
                    nonEditor = content
                    assertFalse("The fixture handoff must have no editor or editable buffer", content.onCheckIsTextEditor())
                    dialog.setContentView(content)
                    dialog.setCancelable(false)
                    dialog.setCanceledOnTouchOutside(false)
                    nativeWindow.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    nativeWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                    dialog.show()
                    assertTrue("Focus only the fixture's noneditable leaf", content.requestFocus())
                }
                val dialog = checkNotNull(handoff)
                val nativeWindow = checkNotNull(handoffWindow)
                val content = checkNotNull(nonEditor)
                // Called only on main, including immediately before changing the Activity bit.
                fun ownsNonEditorFocus(): Boolean = journey.requireActivity().window === window &&
                    window.decorView.isAttachedToWindow && !window.decorView.hasWindowFocus() && window.attributes.flags == originalFlags &&
                    window.decorView.context.getSystemService(Context.INPUT_METHOD_SERVICE) === manager &&
                    dialog.window === nativeWindow && dialog.isShowing && nativeWindow.decorView.isAttachedToWindow &&
                    nativeWindow.decorView.context.getSystemService(Context.INPUT_METHOD_SERVICE) === manager &&
                    nativeWindow.decorView.hasWindowFocus() && content.isFocused && content.rootView === nativeWindow.decorView &&
                    nativeWindow.attributes.flags and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) == 0 &&
                    manager.isActive(content) && !manager.isAcceptingText()
                awaitState("handoff", originalFlags, "the actual owned noneditable window receives IME focus without a text connection", content,
                    timeoutMillis = remaining(deadline)) {
                    onMain(journey) { ownsNonEditorFocus() } && noImeWindow()
                }
                onMain(journey) {
                    assertTrue("Change only the captured Activity while the noneditor still owns actual focus",
                        ownsNonEditorFocus())
                    acquired = true
                    window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                }
                dumpFacts("handoff-acquired", isolatedFlags, content)
            } catch (problem: Throwable) {
                failure = problem
                throw problem
            } finally {
                val dialog = handoff
                val nativeWindow = handoffWindow
                if (dialog != null && nativeWindow != null) {
                    try {
                        onMain(journey) {
                            assertTrue("Dismiss only the exact fixture-owned handoff window", dialog.window === nativeWindow)
                            dialog.dismiss()
                            // Main-thread Dialog.dismiss removes its decor immediately.
                            assertFalse("The exact fixture-owned noneditor window detached", nativeWindow.decorView.isAttachedToWindow)
                        }
                    } catch (cleanup: Throwable) {
                        if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                    }
                }
            }
        }

        /** Main-thread native session facts, combined with the actual window inventory below. */
        fun nativeExclusive(view: ReactEditText): Boolean = acquired && currentWindow() &&
            window.attributes.flags == isolatedFlags && view.isAttachedToWindow && view.rootView === window.decorView &&
            !manager.isActive(view) && !manager.isAcceptingText()

        fun exclusive(view: ReactEditText): Boolean = onMain(journey) { nativeExclusive(view) } && noImeWindow()

        fun awaitExclusive(view: ReactEditText, label: String) {
            awaitState("leaf", isolatedFlags, "the exact native '$label' leaf has no competing installed-IME connection", view) {
                exclusive(view)
            }
        }

        fun close() {
            if (!acquired) return
            onMain(journey) {
                assertTrue("Restore only this exact owned window and its unchanged fixture flags", currentWindow() &&
                    window.attributes.flags == isolatedFlags)
                // The caller closes its actual synthetic connection and observer first.
                // This mask restores only our bit; no IME identity/settings are changed.
                window.setFlags(originalFlags, WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
            journey.await("the original owned Activity window flags restored") {
                onMain(journey) { currentWindow() && window.attributes.flags == originalFlags }
            }
            acquired = false
            println("LAVA_UI_TEXTFIELD_IME stage=restored windowIdentity=${System.identityHashCode(window)} " +
                "managerIdentity=${System.identityHashCode(manager)} flags=$originalFlags")
        }
    }

    private fun nativeEditor(journey: ReactEmulatorJourney, label: String, multiline: Boolean, ime: OwnedImeIsolation): NativeEditor {
        var result: ReactEditText? = null
        journey.await("one visible packaged ReactEditText '$label'") {
            result = onMain(journey) {
                val candidates = views(journey.requireActivity().window.decorView).filterIsInstance<ReactEditText>().filter { view ->
                    val info = view.createAccessibilityNodeInfo()
                    view.isAttachedToWindow && view.isShown && view.getGlobalVisibleRect(Rect()) &&
                        info.isEditable && info.packageName?.toString() == journey.context.packageName &&
                        info.contentDescription?.toString() == label
                }
                assertTrue("The real native '$label' leaf must be unambiguous", candidates.size <= 1)
                candidates.singleOrNull()
            }
            result != null
        }
        val view = checkNotNull(result)
        assertTrue("Focus the actual editor through its existing native accessibility action", onMain(journey) {
            view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
        })
        journey.await("the current native '$label' editor focused") { onMain(journey) { view.isFocused } }
        ime.awaitExclusive(view, label)
        val info = EditorInfo()
        val connection = onMain(journey) {
            assertTrue("Create the direct connection only for an IME-isolated current leaf", ime.nativeExclusive(view))
            checkNotNull(view.onCreateInputConnection(info))
        }
        try {
            assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
            assertEquals("The shipping leaf's native multiline flag", multiline, info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
            assertEquals(0, info.initialSelStart)
            assertEquals(0, info.initialSelEnd)
            println("LAVA_UI_TEXTFIELD_EDITOR mode=${if (multiline) "multiline" else "single-line"} class=${view.javaClass.simpleName} inputType=${info.inputType} imeOptions=${info.imeOptions}")
            return NativeEditor(journey, view, connection, ime)
        } catch (failure: Throwable) {
            // A failed acquisition never hands the connection to the outer fixture.
            // Close it here before that fixture restores the owned window flag.
            try { onMain(journey) { connection.closeConnection() } } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private class NativeEditor(val journey: ReactEmulatorJourney, val view: ReactEditText, val connection: InputConnection, private val ime: OwnedImeIsolation) {
        private var closed = false
        @Volatile private var lastPhase = "initial"
        @Volatile private var operationInFlight = false
        private val observedBuffer = onMain(journey) {
            assertTrue("Observe only the exact current focused native leaf", view.isAttachedToWindow && view.isFocused &&
                view.rootView === journey.requireActivity().window.decorView)
            checkNotNull(view.text)
        }
        private val composingSpans = IdentityHashMap<Any, Int>()
        private var observing = true
        private var removals = 0
        // NoCopySpan prevents React's measurement cache or a replacement Editable from
        // inheriting this observer. Only our observer span is installed/removed; composing
        // spans, text, selection and the platform connection are never repaired here.
        private val spanObserver = object : SpanWatcher, NoCopySpan {
            private fun remember(text: Spannable, what: Any) {
                if (!observing || text !== observedBuffer) return
                val flags = text.getSpanFlags(what)
                if (flags and Spanned.SPAN_COMPOSING != 0) composingSpans[what] = flags
            }

            override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) = remember(text, what)

            override fun onSpanChanged(text: Spannable, what: Any, oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) =
                remember(text, what)

            override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) {
                if (!observing || text !== observedBuffer) return
                val flags = composingSpans.remove(what) ?: return
                if (removals++ >= 24) return
                val onMain = Looper.myLooper() === Looper.getMainLooper()
                val currentBuffer = if (onMain) view.text === observedBuffer else null
                val origin = Thread.currentThread().stackTrace.asSequence()
                    .filter { it.className != Thread::class.java.name }
                    .take(16).joinToString(">") { "${it.className}.${it.methodName}" }
                println("LAVA_UI_TEXTFIELD_COMPOSING_REMOVED phase=$lastPhase inOperation=$operationInFlight " +
                    "identity=${System.identityHashCode(view)} bufferIdentity=${System.identityHashCode(observedBuffer)} " +
                    "currentBuffer=$currentBuffer mainThread=$onMain chars=${text.length} range=$start:$end flags=$flags origin=$origin")
            }
        }

        init {
            try {
                onMain(journey) {
                    assertTrue("Do not attach an observer to a replaced native buffer", view.text === observedBuffer &&
                        view.isAttachedToWindow && view.isFocused && view.rootView === journey.requireActivity().window.decorView)
                    observedBuffer.getSpans(0, observedBuffer.length, Any::class.java).forEach { span ->
                        val flags = observedBuffer.getSpanFlags(span)
                        if (flags and Spanned.SPAN_COMPOSING != 0) composingSpans[span] = flags
                    }
                    observedBuffer.setSpan(spanObserver, 0, observedBuffer.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                }
            } catch (failure: Throwable) {
                try {
                    onMain(journey) {
                        observing = false
                        observedBuffer.removeSpan(spanObserver)
                        composingSpans.clear()
                    }
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        private data class NativeState(
            val identity: Int,
            val bufferIdentity: Int,
            val attached: Boolean,
            val focused: Boolean,
            val currentRoot: Boolean,
            val chars: Int,
            val textMatches: Boolean?,
            val composingStart: Int,
            val composingEnd: Int,
            val selectionStart: Int,
            val selectionEnd: Int,
            val lines: Int?,
            val firstBaseline: Int?,
            val lastBaseline: Int?,
            val width: Int,
            val height: Int,
            val globalVisible: Boolean,
            val clippedBounds: Rect,
        )

        /** Main-thread native facts only: never emit buffer, hint, selected or composing text. */
        private fun nativeState(expected: String? = null): NativeState {
            val buffer = view.text
            val layout = view.layout
            val paintedLayout = layout?.takeIf { it.lineCount > 0 }
            val visibleBounds = Rect()
            val visible = view.getGlobalVisibleRect(visibleBounds)
            return NativeState(
                System.identityHashCode(view), System.identityHashCode(buffer), view.isAttachedToWindow, view.isFocused,
                view.rootView === journey.requireActivity().window.decorView,
                buffer?.length ?: -1, expected?.let { buffer?.toString() == it },
                buffer?.let { BaseInputConnection.getComposingSpanStart(it) } ?: -1,
                buffer?.let { BaseInputConnection.getComposingSpanEnd(it) } ?: -1,
                view.selectionStart, view.selectionEnd, layout?.lineCount,
                paintedLayout?.getLineBaseline(0),
                paintedLayout?.let { it.getLineBaseline(it.lineCount - 1) },
                view.width, view.height, visible, visibleBounds,
            )
        }

        fun change(phase: String, operation: InputConnection.() -> Boolean) {
            assertFalse("No operation may replay on a closed connection", closed)
            assertTrue("No installed IME may compete with this one genuine operation", ime.exclusive(view))
            assertTrue("The one native InputConnection operation '$phase' must be accepted", onMain(journey) {
                assertTrue("Never edit a retired or foreign native leaf", view.isAttachedToWindow && view.isFocused &&
                    view.rootView === journey.requireActivity().window.decorView)
                assertTrue("Keep the exact leaf's native input session isolated", ime.nativeExclusive(view))
                lastPhase = phase.replace(' ', '-')
                println("LAVA_UI_TEXTFIELD_STATE stage=before-change phase=$lastPhase state=${nativeState()}")
                operationInFlight = true
                try {
                    operation(connection).also {
                        println("LAVA_UI_TEXTFIELD_STATE stage=after-change phase=$lastPhase accepted=$it state=${nativeState()}")
                    }
                } finally {
                    operationInFlight = false
                }
            })
        }
        fun expect(text: String, composingStart: Int = -1, composingEnd: Int = -1, selection: Int = text.length, lines: Int = 1) {
            try {
                journey.await("the actual native text/composing/cursor/line state") {
                    ime.exclusive(view) && onMain(journey) {
                        val buffer = checkNotNull(view.text)
                        val layout = view.layout ?: return@onMain false
                        view.isAttachedToWindow && buffer.toString() == text &&
                            BaseInputConnection.getComposingSpanStart(buffer) == composingStart &&
                            BaseInputConnection.getComposingSpanEnd(buffer) == composingEnd &&
                            view.selectionStart == selection && view.selectionEnd == selection && layout.lineCount >= lines
                    }
                }
            } catch (failure: AssertionError) {
                try {
                    val state = onMain(journey) { nativeState(text) }
                    val redacted = "phase=$lastPhase expectedChars=${text.length} expectedComposing=$composingStart:$composingEnd " +
                        "expectedSelection=$selection expectedLines=$lines state=$state"
                    println("LAVA_UI_TEXTFIELD_STATE stage=failed-expect $redacted")
                    failure.addSuppressed(AssertionError("Redacted native editor state: $redacted"))
                } catch (diagnostic: Throwable) {
                    failure.addSuppressed(diagnostic)
                }
                throw failure
            }
            onMain(journey) {
                val layout = checkNotNull(view.layout)
                if (lines > 1) assertTrue("Native multiline content must have distinct painted baselines",
                    layout.getLineBaseline(lines - 1) > layout.getLineBaseline(0))
                assertTrue("The actual editor must retain a native Yoga footprint", view.width > 0 && view.height > 0)
                println("LAVA_UI_TEXTFIELD_BUFFER chars=${text.length} composingStart=$composingStart composingEnd=$composingEnd selection=$selection lines=${layout.lineCount} width=${view.width} height=${view.height}")
            }
        }
        fun close() {
            if (closed) return
            onMain(journey) {
                lastPhase = "connection-close"
                operationInFlight = true
                try {
                    connection.closeConnection()
                } finally {
                    operationInFlight = false
                    observing = false
                    // Retire from the original Editable even if its view was unmounted or
                    // replaced. Never attach to or mutate a successor buffer during cleanup.
                    observedBuffer.removeSpan(spanObserver)
                    composingSpans.clear()
                }
            }
            closed = true
        }
    }

    private fun hideOwnedKeyboard(journey: ReactEmulatorJourney, view: ReactEditText) {
        onMain(journey) {
            assertTrue("Only dismiss this current app editor's own keyboard", view.isAttachedToWindow &&
                view.rootView === journey.requireActivity().window.decorView)
            (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
        }
        journey.await("the fixture editor's keyboard dismissed") {
            journey.instrumentation.uiAutomation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
    }

    private fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }

    companion object {
        private fun <T> onMain(journey: ReactEmulatorJourney, read: () -> T): T {
            val result = AtomicReference<T>()
            journey.instrumentation.runOnMainSync { result.set(read()) }
            return result.get()
        }
    }
}
