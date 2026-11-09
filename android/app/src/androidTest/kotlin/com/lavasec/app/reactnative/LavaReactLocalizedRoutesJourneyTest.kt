package com.lavasec.app.reactnative

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.MainProductViewModel
import com.lavasec.app.billing.PlayPurchaseReadStatus
import com.lavasec.app.security.AndroidKeystorePasscodeVerifier
import com.lavasec.app.security.PasscodeCredentialState
import com.lavasec.app.security.SecurityPreferenceStore
import com.lavasec.app.settings.ProductSettingsStore
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real packaged routes omitted from the existing six-route process-locale matrix. Expected copy
 * is an independent literal fixture, not a lookup in the catalog or an injected owner snapshot.
 * Native Cancel is exercised; no credential, imported filter, subscription or account is created.
 * Process/resources are localized, not the OS language, IME, TalkBack voice or live provider UI.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactLocalizedRoutesJourneyTest {
    @Test
    fun tenProcessLocalesRenderFilterLibraryImportAndIdentityCancellation() = withJourney { journey, fixture ->
        val filterState = journey.snapshot().getJSONObject("filters")
        assertTrue("The real saved filter library must be available", filterState.getBoolean("available"))
        assertFalse("Locale chooser coverage requires an unlocked saved library", filterState.getBoolean("locked"))
        val rows = filterState.getJSONArray("rows")
        val filters = (0 until rows.length()).map(rows::getJSONObject)
        val confirmed = checkNotNull(fixture.product.currentFilterLibrary())
        assertEquals("Chooser rows belong to the actual confirmed library", confirmed.profiles.map { it.id }, filters.map { it.getString("id") })
        val active = filters.single { it.getBoolean("active") }
        val activeId = active.getString("id")
        assertEquals("The edit target is the actual filter in effect", confirmed.activeProfileId, activeId)
        assertFalse("The actual baseline filter must permit unchanged edit cancellation", active.getBoolean("frozen"))
        // The native wire supplies `empty`; Android's RN adapter derives `shareable` from it.
        // Require that real field, rather than reading the shared RN projection as native data.
        val inactive = filters.firstOrNull { !it.getBoolean("active") && !it.getBoolean("frozen") && !it.getBoolean("empty") }
        checkNotNull(inactive) { "A real saved inactive filter with the two chooser actions is required" }
        assertEquals("The chooser retains the confirmed owner's verbatim name",
            confirmed.profiles.single { it.id == inactive.getString("id") }.name, inactive.getString("name"))
        var ownsUnchangedEdit = false
        var primaryFailure: Throwable? = null
        try {
            for (copy in LOCALIZED_ROUTE_COPY) {
                fixture.locale(copy)
                journey.click(copy["Guard"], prefix = true, scroll = false)
                journey.scrollToTop()
                journey.click(copy["Now filtering"], prefix = true)
                requireCopy(journey, copy, "Switch or manage filters")
                journey.click(copy["Switch or manage filters"])
                requireCopy(journey, copy, "Choose a filter to use, view or edit.")
                journey.click(inactive.getString("name"), excludeEditable = true)
                // The title is the owner's name, kept verbatim. Only the chooser's chrome changes.
                journey.waitLabel(inactive.getString("name"))
                requireNativeButton(journey, copy, "View filter")
                clickNativeButton(journey, copy, "More actions")
                requireNativeButton(journey, copy, "Use this filter")
                requireNativeButton(journey, copy, "Share")
                val chooserWindow = clickNativeButton(journey, copy, "Cancel")
                journey.await("${copy.tag} native filter chooser cancelled") {
                    nativeButtons(journey).none { it.windowId == chooserWindow }
                }
                requireCopy(journey, copy, "Choose a filter to use, view or edit.")
                fixture.assertUnchanged()

                journey.back()
                requireCopy(journey, copy, "Switch or manage filters")
                journey.click(copy["Now filtering"], prefix = true)
                requireCopy(journey, copy, "Lava blocks these")
                ownsUnchangedEdit = true
                journey.click(copy["Edit"], scroll = false)
                journey.await("${copy.tag} actual filter edit") {
                    fixture.requireCurrent()
                    journey.snapshot().optJSONObject("filterSession")?.let {
                        it.optString("id") == activeId && it.optBoolean("editing")
                    } == true
                }
                journey.click(copy["Rename filter"], scroll = false)
                requireCopy(journey, copy, "Rename filter")
                requireCopy(journey, copy, "Emoji")
                requireCopy(journey, copy, "Name")
                requireCopy(journey, copy, "Save")
                var renameID: String? = null
                journey.await("${copy.tag} exact native shared rename owner") {
                    fixture.requireCurrent()
                    val flow = journey.snapshot().optJSONObject("foregroundFlow")
                    val valid = flow != null && flow.optString("kind") == "renameFilter" && flow.optString("name") == active.getString("name")
                    if (valid) renameID = flow?.getString("id")
                    valid
                }
                val capturedRenameID = checkNotNull(renameID)
                val renameHost = journey.requireActivity()
                var identityInputs: List<AccessibilityNodeInfo> = emptyList()
                journey.await("${copy.tag} both actual native identity fields after exact owner and measured Ready") {
                    fixture.requireCurrent()
                    val current = journey.snapshot().optJSONObject("foregroundFlow")
                    if (current?.optString("id") != capturedRenameID ||
                        !presentationReceipt(journey, renameHost, fixture.generation, "foreground:$capturedRenameID").second) return@await false
                    val fields = journey.accessibilityNodes().filter {
                        it.packageName?.toString() == journey.context.packageName && it.isVisibleToUser && it.isEditable &&
                            it.isEnabled && it.className?.toString() == "android.widget.EditText"
                    }
                    val valid = fields.size == 2 && fields.count { matches(it, copy["Emoji"]) } == 1 &&
                        fields.count { matches(it, copy["Name"]) } == 1 && fields.map { it.windowId }.distinct().size == 1
                    if (valid) identityInputs = fields
                    valid
                }
                assertEquals("The shared rename form mounts both real native identity fields", 2, identityInputs.size)
                val emojiInput = identityInputs.single { matches(it, copy["Emoji"]) }
                val nameInput = identityInputs.single { matches(it, copy["Name"]) }
                assertEquals("A filter emoji remains verbatim data across locales", active.getString("emoji"), emojiInput.text?.toString())
                assertEquals("A filter name remains verbatim data across locales", active.getString("name"), nameInput.text?.toString())
                assertEquals("The actual shared native name field retains its localized placeholder", copy["Filter name"], nameInput.hintText?.toString())
                journey.click(copy["Cancel"], scroll = false)
                journey.await("${copy.tag} identity Cancel returns to the same edit") {
                    journey.visible(copy["Cancel editing"]) && !journey.visible(copy["Name"])
                }
                journey.click(copy["Cancel editing"], scroll = false)
                journey.await("${copy.tag} unchanged edit cancelled") {
                    fixture.requireCurrent()
                    journey.snapshot().optJSONObject("filterSession")?.optBoolean("editing") == false
                }
                ownsUnchangedEdit = false
                fixture.assertUnchanged()
                journey.back()
                requireCopy(journey, copy, "Switch or manage filters")
                journey.click(copy["Import"], scroll = false)
                requireCopy(journey, copy, "Scan a QR code")
                requireCopy(journey, copy, "Import from Photo")
                requireCopy(journey, copy, "Enter a code")
                journey.click(copy["Enter a code"])
                requireCopy(journey, copy, "Enter a setup code")
                requireCopy(journey, copy, "Setup code")
                requireCopy(journey, copy, "Continue")
                assertFalse("Blank code cannot submit an import", buttonOwner(journey, copy["Continue"]).isEnabled)
                // This is the actual localized toolbar Back, which returns to Import's method
                // stage. The subsequent system Back closes the genuine sheet.
                journey.click(copy["Back"], scroll = false)
                requireCopy(journey, copy, "Scan a QR code")
                journey.back()
                requireCopy(journey, copy, "Switch or manage filters")
                fixture.assertUnchanged()
                journey.assertNoFallback()
                journey.back()
                journey.waitLabel(copy["Guard"], prefix = true)
                println("LAVA_UI_LOCALIZED_ROUTES tag=${copy.tag} routes=Filters,Library,FilterRename,Import nativeFilterChooser=cancelled identity=cancelled import=blank-not-submitted")
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (ownsUnchangedEdit) {
                // Failure teardown only: a captured owner/generation can discard its unchanged
                // edit, never a successor's draft or any changed/confirmed configuration.
                try {
                    fixture.requireCurrent()
                    if (journey.snapshot().optJSONObject("filterSession")?.optBoolean("editing") == true) {
                        assertTrue("Only this unchanged captured edit may be cleaned up", runBlocking {
                            fixture.product.finishUnchangedFilterEdit(activeId, fixture.generation)
                        })
                    }
                } catch (cleanup: Throwable) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    @Test
    fun tenProcessLocalesRenderAccountSecurityAndPlusWithNativePasscodeCancellation() = withJourney { journey, fixture ->
        for (copy in LOCALIZED_ROUTE_COPY) {
            fixture.locale(copy)
            openSettings(journey, copy)
            val accountHost = journey.requireActivity()
            var returnedSettingsSerial: Long? = null
            val currentSettingsOwner = {
                fixture.requireCurrent()
                // A normal publication can temporarily withdraw Ready without retiring
                // this host or scroller. Readiness waits separately under the same budget.
                presentationReceipt(journey, accountHost, fixture.generation)
                true
            }
            fun settingsReady(): Boolean {
                fixture.requireCurrent()
                val receipt = presentationReceipt(journey, accountHost, fixture.generation)
                return returnedSettingsSerial?.let { receipt.first >= it && receipt.second } ?: false
            }
            // Capture this Settings route while its heading is already visible. Returning
            // from a child must reveal this same physical scroller, never another page.
            val settingsScroll = journey.scrollRegionContaining(copy["Your connection"], isCurrent = currentSettingsOwner)
            assertTrue("${copy.tag} Settings retains its original physical scroll source", settingsScroll.isVisibleOnCurrentHost())
            fun returnToSettings() {
                val departingSerial = presentationReceipt(journey, accountHost, fixture.generation).first
                val deadline = SystemClock.elapsedRealtime() + 20_000
                fun remaining(): Long = (deadline - SystemClock.elapsedRealtime()).also {
                    assertTrue("${copy.tag} Settings return exceeded its original 20s deadline", it > 0)
                }
                journey.back()
                journey.await("${copy.tag} newer measured Settings presentation and original scroll owner", remaining()) {
                    fixture.requireCurrent()
                    val receipt = presentationReceipt(journey, accountHost, fixture.generation)
                    if (receipt.first <= departingSerial || !receipt.second) return@await false
                    if (!settingsScroll.isVisibleOnCurrentHost()) return@await false
                    if (SystemClock.elapsedRealtime() >= deadline) return@await false
                    returnedSettingsSerial = receipt.first
                    true
                }
                settingsScroll.reveal(copy["Your connection"], forward = false, timeoutMillis = remaining(),
                    absoluteDeadlineMillis = deadline, ready = ::settingsReady)
                journey.await("${copy.tag} ready Settings top heading on the original owner", remaining()) {
                    if (!settingsReady() || !settingsScroll.isVisibleOnCurrentHost() ||
                        !journey.visible(copy["Your connection"])) return@await false
                    if (!copy["Your connection"].equals("Your connection", ignoreCase = true))
                        assertFalse("${copy.tag} route leaks the English adapter label 'Your connection'", journey.visible("Your connection"))
                    settingsReady() && SystemClock.elapsedRealtime() < deadline
                }
            }
            val settingsSerial = presentationReceipt(journey, accountHost, fixture.generation).first
            journey.click(copy["Account & Backup"])
            requireCopy(journey, copy, "Not signed in")
            revealCopy(journey, copy, "Ready after sign-in")
            requireCheckable(journey, copy, "Enable backup", checked = false, enabled = false)
            fixture.assertUnchanged()
            try {
                journey.await("${copy.tag} Account exact newer native presentation is measured before one Back") {
                    fixture.requireCurrent()
                    val receipt = presentationReceipt(journey, accountHost, fixture.generation)
                    receipt.first > settingsSerial && receipt.second
                }
                returnToSettings()
            } catch (failure: AssertionError) {
                runCatching { dumpAccountBackFacts(journey, accountHost, fixture.generation, settingsSerial) }
                    .onFailure { println("LAVA_LOCALIZED_ACCOUNT_BACK diagnostic-failed=${it.javaClass.simpleName}") }
                throw failure
            }
            journey.click(copy["Security"])
            requireCopy(journey, copy, "Authentication method")
            requireCheckable(journey, copy, "Passcode", checked = false, enabled = true)
            val credentialHost = journey.requireActivity()
            journey.click(copy["Passcode"])
            // Independent Android resource copy and actual password AX semantics, not a fake flag.
            journey.waitLabel(copy.nativeSetPasscode)
            var credentialFields: List<AccessibilityNodeInfo> = emptyList()
            journey.await("${copy.tag} localized actual native masked credential fields") {
                fixture.requireCurrent()
                val fields = journey.accessibilityNodes().filter {
                    it.packageName?.toString() == journey.context.packageName && it.isVisibleToUser &&
                        it.className?.toString() == "android.widget.EditText" && it.isEditable && it.isPassword
                }
                val ready = fields.size == 2 && fields.map { it.windowId }.distinct().size == 1 &&
                    fields.all { it.isEnabled && (it.isShowingHintText || it.text.isNullOrEmpty()) } &&
                    fields.map { it.hintText?.toString() }.toSet() == setOf(copy.nativeNewPasscode, copy.nativeConfirmPasscode)
                if (ready) credentialFields = fields
                ready
            }
            assertFalse("Native setup title must not leak English", copy.tag != "en" && nativeButtons(journey).any {
                it.text?.toString()?.equals("Set Passcode", ignoreCase = true) == true
            })
            val credentialWindow = credentialFields.first().windowId
            assertTrue("The native credential inputs identify a connected window", credentialWindow >= 0)
            cancelNativePasscode(journey, copy, credentialWindow) {
                fixture.assertUnchanged()
                assertTrue("Native Cancel belongs to the original credential host", journey.requireActivity() === credentialHost &&
                    !credentialHost.isFinishing && !credentialHost.isDestroyed)
                assertTrue("Both original empty masked inputs remain in the captured dialog", credentialFields.all {
                    it.refresh() && it.windowId == credentialWindow && it.packageName?.toString() == journey.context.packageName &&
                        it.className?.toString() == "android.widget.EditText" && it.isVisibleToUser && it.isEditable && it.isPassword && it.isEnabled &&
                        (it.isShowingHintText || it.text.isNullOrEmpty())
                })
                assertEquals(setOf(copy.nativeNewPasscode, copy.nativeConfirmPasscode), credentialFields.map { it.hintText?.toString() }.toSet())
                assertTrue("The exact credential window retains its localized setup title", journey.accessibilityNodes().any {
                    it.windowId == credentialWindow && matches(it, copy.nativeSetPasscode)
                })
            }
            journey.await("${copy.tag} native passcode Cancel returns") {
                nativeButtons(journey).none { it.windowId == credentialWindow } && journey.visible(copy["Authentication method"])
            }
            requireCheckable(journey, copy, "Passcode", checked = false, enabled = true,
                settledForNativeCancel = {
                    fixture.requireCurrent()
                    presentationReceipt(journey, credentialHost, fixture.generation).second
                })
            fixture.assertUnchanged()
            returnToSettings()
            fixture.enteringPlus()
            journey.click(copy["Get Lava Plus today"])
            // The actual Play owner may have no live offers; only the real free presentation and
            // Android disclosure are asserted. No buy, restore or manage action is submitted.
            // Billing replaces its loading row with the plan heading. Wait for that native
            // publication before scrolling, or the replacement can land above the viewport.
            fixture.awaitPlusTerminal()
            journey.scrollToTop()
            revealCopy(journey, copy, "Choose a plan")
            revealCopy(journey, copy, "Google Play Terms")
            fixture.awaitPlusTerminal()
            fixture.assertUnchanged()
            journey.assertNoFallback()
            returnToSettings()
            println("LAVA_UI_LOCALIZED_ROUTES tag=${copy.tag} routes=Account,Security,Plus backup=signed-out-disabled nativePasscode=masked-cancelled billing=no-purchase-action")
        }
    }

    private fun withJourney(block: (ReactEmulatorJourney, LocaleFixture) -> Unit) {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            LocaleFixture(journey).use { fixture -> block(journey, fixture) }
        }
    }

    /** Capture the actual native receipt on its owning UI thread, never a visible-copy proxy. */
    private fun presentationReceipt(journey: ReactEmulatorJourney, host: LavaReactActivity, generation: Long, ownerID: String? = null): Pair<Long, Boolean> {
        var receipt: Pair<Long, Boolean>? = null
        journey.instrumentation.runOnMainSync {
            assertTrue("A native presentation receipt belongs to this exact live foreground host",
                journey.requireActivity() === host && journey.application.reactHost.currentReactContext?.currentActivity === host &&
                    host.foreground && !host.isDestroyed && !host.isFinishing)
            assertEquals(generation, journey.application.foregroundProduct.state.value.graphGeneration)
            val serial = LavaReactActivity::class.java.getDeclaredField("presentationSerial")
                .apply { isAccessible = true }.getLong(host)
            receipt = serial to host.isPresentationReady(generation, ownerID)
        }
        return checkNotNull(receipt)
    }

    /** Failure-only current-window numeric facts; never labels, input text or snapshots. */
    private fun dumpAccountBackFacts(journey: ReactEmulatorJourney, host: LavaReactActivity, generation: Long, settingsSerial: Long) {
        journey.instrumentation.runOnMainSync {
            if (journey.application.reactHost.currentReactContext?.currentActivity !== host || host.isDestroyed) {
                println("LAVA_LOCALIZED_ACCOUNT_BACK currentHost=false destroyed=${host.isDestroyed}")
                return@runOnMainSync
            }
            val root = host.window.decorView
            val visible = android.graphics.Rect().also(root::getWindowVisibleDisplayFrame)
            val position = IntArray(2).also(root::getLocationInWindow)
            val serial = LavaReactActivity::class.java.getDeclaredField("presentationSerial")
                .apply { isAccessible = true }.getLong(host)
            val manager = host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            @Suppress("DEPRECATION") val bottomInset = root.rootWindowInsets?.systemWindowInsetBottom
            println("LAVA_LOCALIZED_ACCOUNT_BACK host=${System.identityHashCode(host)} generation=$generation " +
                "settingsSerial=$settingsSerial currentSerial=$serial ready=${host.isPresentationReady(generation)} " +
                "foreground=${host.foreground} focus=${host.hasWindowFocus()} size=${root.width}x${root.height} " +
                "windowPosition=${position[0]},${position[1]} visible=${visible.left},${visible.top},${visible.right},${visible.bottom} " +
                "bottomInset=$bottomInset imeAcceptingText=${manager.isAcceptingText}")
        }
    }

    private fun openSettings(journey: ReactEmulatorJourney, copy: LocalizedRouteCopy) {
        journey.click(copy["Settings"], prefix = true, scroll = false)
        journey.scrollToTop()
        requireCopy(journey, copy, "Your connection")
    }

    private fun requireCopy(journey: ReactEmulatorJourney, copy: LocalizedRouteCopy, source: String, reveal: Boolean = false) {
        if (reveal) journey.await("UI label '${copy[source]}'") {
            if (journey.visible(copy[source])) true else { journey.scrollPage(); false }
        } else journey.waitLabel(copy[source])
        if (!copy[source].equals(source, ignoreCase = true))
            assertFalse("${copy.tag} route leaks the English adapter label '$source'", journey.visible(source))
    }

    private fun revealCopy(journey: ReactEmulatorJourney, copy: LocalizedRouteCopy, source: String) {
        journey.await("${copy.tag} actual localized route control '$source'") {
            if (journey.visible(copy[source])) true else { journey.scrollPage(); false }
        }
        // A real native billing/layout publication can move a heading after its first
        // visible frame. Keep revealing during the original second20s wait as well;
        // the exact localized heading and English-leak assertion remain mandatory.
        requireCopy(journey, copy, source, reveal = true)
    }

    private fun matches(node: AccessibilityNodeInfo, label: String): Boolean = node.isVisibleToUser &&
        listOf(node.text, node.contentDescription).any { it?.toString()?.equals(label, ignoreCase = true) == true }

    private fun nativeButtons(journey: ReactEmulatorJourney) = journey.accessibilityNodes().filter {
        it.packageName?.toString() == journey.context.packageName && it.isVisibleToUser &&
            it.className?.toString() == "android.widget.Button"
    }

    private fun cancelNativePasscode(journey: ReactEmulatorJourney, copy: LocalizedRouteCopy, window: Int, requireCurrent: () -> Unit) {
        // API28's retained native Cancel label contains only U+200E/U+200F around CANCEL.
        // Remove those observed direction marks only, never arbitrary formatting or RN/user text.
        fun semantic(value: CharSequence?) = value?.toString()?.filterNot { it == '\u200e' || it == '\u200f' }
        val resource = journey.requireActivity().getString(android.R.string.cancel)
        assertTrue("The native resource retains the independently expected localized Cancel",
            semantic(resource)?.equals(copy["Cancel"], ignoreCase = true) == true)
        fun named(node: AccessibilityNodeInfo) = semantic(node.text)?.equals(semantic(resource), ignoreCase = true) == true
        fun candidates() = nativeButtons(journey).filter { it.windowId == window && named(it) }
        var acquired: AccessibilityNodeInfo? = null
        journey.await("${copy.tag} exact localized native Cancel in the original credential window") {
            requireCurrent()
            val current = candidates()
            assertTrue("The captured credential window has one native Cancel source", current.size <= 1)
            acquired = current.singleOrNull()?.takeIf {
                it.refresh() && it.windowId == window && named(it) && it.isVisibleToUser && it.isEnabled && it.isClickable &&
                    it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
            }
            acquired != null
        }
        requireCurrent()
        val button = checkNotNull(acquired)
        assertTrue(button.refresh() && button.windowId == window && named(button) && button.isVisibleToUser && button.isEnabled && button.isClickable)
        assertEquals("Final Cancel admission retains the unique original native button source", listOf(button), candidates())
        assertTrue(button.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        assertTrue("The actual original native Cancel accepts exactly one action", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        println("LAVA_UI_LOCALIZED_NATIVE_CANCEL tag=${copy.tag} window=$window source=${button.hashCode()} directionMarks=${button.text?.count { it == '\u200e' || it == '\u200f' } ?: 0} scope=native-cancel-only")
    }

    private fun requireNativeButton(journey: ReactEmulatorJourney, copy: LocalizedRouteCopy, source: String): AccessibilityNodeInfo {
        var target: AccessibilityNodeInfo? = null
        journey.await("${copy.tag} exact localized native button '$source'") {
            val candidates = nativeButtons(journey).filter { matches(it, copy[source]) }
            assertTrue("Native dialog button must be unique", candidates.size <= 1)
            target = candidates.singleOrNull()?.takeIf { it.refresh() && matches(it, copy[source]) && it.isEnabled && it.isClickable }
            target != null
        }
        if (copy[source] != source) assertFalse("${copy.tag} native button leaks English '$source'",
            nativeButtons(journey).any { matches(it, source) })
        return checkNotNull(target)
    }

    private fun clickNativeButton(journey: ReactEmulatorJourney, copy: LocalizedRouteCopy, source: String): Int {
        val button = requireNativeButton(journey, copy, source)
        assertTrue(button.refresh() && matches(button, copy[source]) && button.isEnabled && button.isClickable)
        assertTrue("The actual native localized button accepts exactly one action",
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        return button.windowId
    }

    private fun buttonOwner(journey: ReactEmulatorJourney, label: String): AccessibilityNodeInfo {
        val candidates = journey.accessibilityNodes().filter {
            it.packageName?.toString() == journey.context.packageName && matches(it, label)
        }.map { named ->
            var owner = named
            // A disabled RN semantic button can withdraw its click action. Its genuine Android
            // Button role remains the target; do not climb past it to an enabled page ancestor.
            while (owner.className?.toString() != "android.widget.Button") {
                val parent = owner.parent ?: break
                if (parent.windowId != named.windowId) break
                owner = parent
            }
            owner
        }.filter { it.isVisibleToUser && it.className?.toString() == "android.widget.Button" }.distinct()
        assertEquals("Named control needs one actual same-window button owner", 1, candidates.size)
        return candidates.single().also { assertTrue(it.refresh()) }
    }

    private fun requireCheckable(journey: ReactEmulatorJourney, copy: LocalizedRouteCopy, source: String, checked: Boolean, enabled: Boolean,
        settledForNativeCancel: (() -> Boolean)? = null) {
        var target: AccessibilityNodeInfo? = null
        journey.await("${copy.tag} real native checkable '$source'") {
            if (settledForNativeCancel?.invoke() == false) return@await false
            val candidates = journey.accessibilityNodes().filter { it.isCheckable && matches(it, copy[source]) }
            assertTrue("The named checkable must be unique", candidates.size <= 1)
            target = candidates.singleOrNull()?.takeIf { it.refresh() && matches(it, copy[source]) }
            if (target != null) {
                // Native dialog dismissal precedes the flow Promise and the
                // controlled Switch's resetRevision effect. Wait for its actual
                // cancelled value under the same receipt and original 20s budget.
                settledForNativeCancel == null || checkNotNull(target).let {
                    it.className?.toString() == "android.widget.Switch" && it.isChecked == checked && it.isEnabled == enabled
                } && settledForNativeCancel()
            } else { journey.scrollPage(); false }
        }
        assertEquals(checked, checkNotNull(target).isChecked)
        assertEquals(enabled, checkNotNull(target).isEnabled)
        assertEquals("The shipping control must be the actual native Switch", "android.widget.Switch", checkNotNull(target).className?.toString())
    }

    private class LocaleFixture(private val journey: ReactEmulatorJourney) : AutoCloseable {
        val product: MainProductViewModel = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        private val plus = checkNotNull(journey.application.plusBackend.value)
        private var visitedPlus = false
        private val library = checkNotNull(product.currentFilterLibrary())
        private val settings = ProductSettingsStore(journey.context)
        private val preferences = settings.loadPreferences()
        private val securityPreferences = SecurityPreferenceStore(journey.context)
        private val security = securityPreferences.load()
        private val verifier = AndroidKeystorePasscodeVerifier(journey.context)
        private val originalLocale = Locale.getDefault()
        private val originalLocaleList = LocaleList.getDefault()
        private val originalOrientation = journey.requireActivity().requestedOrientation
        private val resources = listOf(journey.context.resources, journey.application.resources, journey.requireActivity().resources)
            .distinct().map { it to Configuration(it.configuration) }

        init {
            assertEquals("The runner baseline must start in English", "en", originalLocale.language)
            assertEquals("Never touch an existing credential", PasscodeCredentialState.Absent, verifier.credentialState())
            assertFalse(security.enabled)
            assertTrue(security.protectedSurfaces.isEmpty())
            assertUnchanged()
            journey.instrumentation.runOnMainSync { journey.requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            journey.await("real portrait layout") { journey.requireActivity().resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        }

        fun requireCurrent() {
            assertTrue("A retired VM cannot navigate or clean up a successor", journey.application.foregroundProduct === product)
            assertEquals(generation, product.state.value.graphGeneration)
            assertTrue("Only the original native billing owner may finish these route reads", journey.application.plusBackend.value === plus)
        }

        fun enteringPlus() {
            requireCurrent()
            visitedPlus = true
        }

        fun awaitPlusTerminal() {
            // Native purchase.refresh awaits connection, offer query, then inventory query.
            // Each public RPC has its own 15s deadline; allow delivery after that concrete bound.
            journey.await("original native Plus offer and purchase reads terminal", 50_000) {
                requireCurrent()
                val presentation = plus.controller.state.value
                val terminal = plus.purchases.state.value.status in setOf(
                    PlayPurchaseReadStatus.CHECKED, PlayPurchaseReadStatus.PENDING, PlayPurchaseReadStatus.UNAVAILABLE,
                )
                val rendered = journey.snapshot().getJSONObject("plus")
                !presentation.loading && !presentation.busy && terminal &&
                    !rendered.getBoolean("busy") && !rendered.getBoolean("checking")
            }
        }

        @Suppress("DEPRECATION")
        fun locale(copy: LocalizedRouteCopy) {
            requireCurrent()
            val locales = LocaleList(Locale.forLanguageTag(copy.tag))
            LocaleList.setDefault(locales)
            journey.instrumentation.runOnMainSync {
                resources.forEach { (resource, _) ->
                    resource.updateConfiguration(Configuration(resource.configuration).apply { setLocales(locales) }, resource.displayMetrics)
                }
                journey.requireActivity().onConfigurationChanged(Configuration(journey.requireActivity().resources.configuration))
            }
            // The same actual owner value publishes the newly computed native presentation locale.
            // It neither grants access nor changes preferences; no JS snapshot is supplied.
            journey.completed("settings.set", JSONObject().put("key", "appearance").put("value", preferences.appearance.wire))
            assertEquals(copy.wire, journey.snapshot().getJSONObject("presentation").getString("locale"))
            assertUnchanged()
        }

        fun assertUnchanged() {
            requireCurrent()
            assertEquals("No confirmed library/configuration mutation", library, product.currentFilterLibrary())
            assertEquals("Locale coverage preserves every durable product preference", preferences, settings.loadPreferences())
            assertEquals("Native Cancel preserves every security preference", security, securityPreferences.load())
            assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
            val snapshot = journey.snapshot()
            assertFalse("The actual account remains signed out", snapshot.getJSONObject("account").getBoolean("signedIn"))
            assertFalse("The actual free entitlement is never fabricated", snapshot.getJSONObject("plus").getBoolean("enabled"))
            assertFalse("A locale route cannot leave a library draft", snapshot.optJSONObject("libraryEditing")?.optBoolean("active") == true)
            assertFalse("A locale route cannot leave a filter draft", snapshot.optJSONObject("filterSession")?.optBoolean("editing") == true)
            assertTrue("A locale route cannot create a filter", snapshot.isNull("newFilter"))
        }

        @Suppress("DEPRECATION")
        override fun close() {
            // Resource/process state is restored even if a native route fails. The outer journey
            // then destroys its exact Activity, cancelling any unfinished native presentation.
            try {
                if (visitedPlus) awaitPlusTerminal()
            } finally {
                LocaleList.setDefault(originalLocaleList)
                Locale.setDefault(originalLocale)
                journey.instrumentation.runOnMainSync {
                    resources.forEach { (resource, configuration) -> resource.updateConfiguration(configuration, resource.displayMetrics) }
                    journey.requireActivity().onConfigurationChanged(Configuration(journey.requireActivity().resources.configuration))
                    journey.requireActivity().requestedOrientation = originalOrientation
                }
            }
        }
    }
}

private data class LocalizedRouteCopy(
    val tag: String, val wire: String, val messages: Map<String, String>,
    val nativeSetPasscode: String, val nativeNewPasscode: String, val nativeConfirmPasscode: String,
) {
    operator fun get(source: String): String = checkNotNull(messages[source]) { "Missing literal fixture for $source" }
}

// Literal expected rendered copy: frozen 3bd86116 plus reviewed Android-only chooser/billing
// labels and existing native passcode resources. User filter names are read verbatim from owners.
private val LOCALIZED_ROUTE_COPY = listOf(
    LocalizedRouteCopy("en", "en", mapOf(
        "Guard" to "Guard", "Now filtering" to "Now filtering", "Switch or manage filters" to "Switch or manage filters",
        "Choose a filter to use, view or edit." to "Choose a filter to use, view or edit.", "View filter" to "View filter", "More actions" to "More actions",
        "Use this filter" to "Use this filter", "Share" to "Share", "Cancel" to "Cancel",
        "Lava blocks these" to "Lava blocks these", "Edit" to "Edit", "Rename filter" to "Rename filter",
        "Emoji" to "Emoji", "Name" to "Name", "Save" to "Save",
        "Cancel editing" to "Cancel editing", "Import" to "Import", "Scan a QR code" to "Scan a QR code",
        "Import from Photo" to "Import from photo", "Enter a code" to "Enter a code", "Enter a setup code" to "Enter a setup code",
        "Setup code" to "Setup code", "Continue" to "Continue", "Settings" to "Settings",
        "Your connection" to "Your connection", "Account & Backup" to "Account & backup", "Not signed in" to "Not signed in",
        "Ready after sign-in" to "Ready after sign-in", "Enable backup" to "Enable backup", "Security" to "Security",
        "Authentication method" to "Authentication method", "Passcode" to "Passcode", "Get Lava Plus today" to "Get Lava Plus today",
        "Choose a plan" to "Choose a plan", "Google Play Terms" to "Google Play Terms",
        "Filter name" to "Filter name", "Back" to "Back",
    ), "Set Passcode", "New passcode", "Confirm Passcode"),
    LocalizedRouteCopy("de", "de", mapOf(
        "Guard" to "Schutz", "Now filtering" to "Filtert jetzt", "Switch or manage filters" to "Filter wechseln oder verwalten",
        "Choose a filter to use, view or edit." to "Wähle einen Filter zum Nutzen, Ansehen oder Bearbeiten.", "View filter" to "Filter ansehen", "More actions" to "Weitere Aktionen",
        "Use this filter" to "Diesen Filter verwenden", "Share" to "Teilen", "Cancel" to "Abbrechen",
        "Lava blocks these" to "Lava blockiert diese", "Edit" to "Bearbeiten", "Rename filter" to "Filter umbenennen",
        "Emoji" to "Emoji", "Name" to "Name", "Save" to "Speichern",
        "Cancel editing" to "Bearbeiten abbrechen", "Import" to "Importieren", "Scan a QR code" to "QR-Code scannen",
        "Import from Photo" to "Aus Foto importieren", "Enter a code" to "Code eingeben", "Enter a setup code" to "Einrichtungscode eingeben",
        "Setup code" to "Einrichtungscode", "Continue" to "Weiter", "Settings" to "Einstellungen",
        "Your connection" to "Deine Verbindung", "Account & Backup" to "Konto & Backup", "Not signed in" to "Nicht angemeldet",
        "Ready after sign-in" to "Bereit nach der Anmeldung", "Enable backup" to "Backup aktivieren", "Security" to "Sicherheit",
        "Authentication method" to "Authentifizierungsmethode", "Passcode" to "Code", "Get Lava Plus today" to "Hol dir heute Lava Plus",
        "Choose a plan" to "Plan auswählen", "Google Play Terms" to "Google Play-Nutzungsbedingungen",
        "Filter name" to "Filtername", "Back" to "Zurück",
    ), "Code festlegen", "Neuer Passcode", "Code bestätigen"),
    LocalizedRouteCopy("es", "es", mapOf(
        "Guard" to "Protección", "Now filtering" to "Filtrando ahora", "Switch or manage filters" to "Cambiar o gestionar filtros",
        "Choose a filter to use, view or edit." to "Elige un filtro para usarlo, verlo o editarlo.", "View filter" to "Ver filtro", "More actions" to "Más acciones",
        "Use this filter" to "Usar este filtro", "Share" to "Compartir", "Cancel" to "Cancelar",
        "Lava blocks these" to "Lava bloquea estos", "Edit" to "Editar", "Rename filter" to "Renombrar filtro",
        "Emoji" to "Emoji", "Name" to "Nombre", "Save" to "Guardar",
        "Cancel editing" to "Cancelar edición", "Import" to "Importar", "Scan a QR code" to "Escanear un código QR",
        "Import from Photo" to "Importar desde una foto", "Enter a code" to "Introduce un código", "Enter a setup code" to "Introduce un código de configuración",
        "Setup code" to "Código de configuración", "Continue" to "Continuar", "Settings" to "Ajustes",
        "Your connection" to "Tu conexión", "Account & Backup" to "Cuenta y copia de seguridad", "Not signed in" to "No has iniciado sesión",
        "Ready after sign-in" to "Listo tras iniciar sesión", "Enable backup" to "Activar copia de seguridad", "Security" to "Seguridad",
        "Authentication method" to "Método de autenticación", "Passcode" to "Código", "Get Lava Plus today" to "Consigue Lava Plus hoy",
        "Choose a plan" to "Elige un plan", "Google Play Terms" to "Condiciones de Google Play",
        "Filter name" to "Nombre del filtro", "Back" to "Atrás",
    ), "Definir código", "Nuevo código", "Confirmar código"),
    LocalizedRouteCopy("fr", "fr", mapOf(
        "Guard" to "Protection", "Now filtering" to "Filtre actif", "Switch or manage filters" to "Changer ou gérer les filtres",
        "Choose a filter to use, view or edit." to "Choisissez un filtre à utiliser, consulter ou modifier.", "View filter" to "Afficher le filtre", "More actions" to "Plus d’actions",
        "Use this filter" to "Utiliser ce filtre", "Share" to "Partager", "Cancel" to "Annuler",
        "Lava blocks these" to "Lava bloque ceci", "Edit" to "Modifier", "Rename filter" to "Renommer le filtre",
        "Emoji" to "Emoji", "Name" to "Nom", "Save" to "Enregistrer",
        "Cancel editing" to "Annuler la modification", "Import" to "Importer", "Scan a QR code" to "Scanner un code QR",
        "Import from Photo" to "Importer depuis une photo", "Enter a code" to "Saisir un code", "Enter a setup code" to "Saisir un code de configuration",
        "Setup code" to "Code de configuration", "Continue" to "Continuer", "Settings" to "Réglages",
        "Your connection" to "Votre connexion", "Account & Backup" to "Compte et sauvegarde", "Not signed in" to "Non connecté",
        "Ready after sign-in" to "Prêt après la connexion", "Enable backup" to "Activer la sauvegarde", "Security" to "Sécurité",
        "Authentication method" to "Méthode d’authentification", "Passcode" to "Code", "Get Lava Plus today" to "Passez à Lava Plus dès aujourd’hui",
        "Choose a plan" to "Choisir une offre", "Google Play Terms" to "Conditions de Google Play",
        "Filter name" to "Nom du filtre", "Back" to "Retour",
    ), "Définir un code", "Nouveau code", "Confirmer le code"),
    LocalizedRouteCopy("it", "it", mapOf(
        "Guard" to "Protezione", "Now filtering" to "Ora in filtraggio", "Switch or manage filters" to "Cambia o gestisci i filtri",
        "Choose a filter to use, view or edit." to "Scegli un filtro da usare, visualizzare o modificare.", "View filter" to "Visualizza filtro", "More actions" to "Altre azioni",
        "Use this filter" to "Usa questo filtro", "Share" to "Condividi", "Cancel" to "Annulla",
        "Lava blocks these" to "Lava blocca questi", "Edit" to "Modifica", "Rename filter" to "Rinomina filtro",
        "Emoji" to "Emoji", "Name" to "Nome", "Save" to "Salva",
        "Cancel editing" to "Annulla la modifica", "Import" to "Importa", "Scan a QR code" to "Scansiona un codice QR",
        "Import from Photo" to "Importa da foto", "Enter a code" to "Inserisci un codice", "Enter a setup code" to "Inserisci un codice di configurazione",
        "Setup code" to "Codice di configurazione", "Continue" to "Continua", "Settings" to "Impostazioni",
        "Your connection" to "La tua connessione", "Account & Backup" to "Account e backup", "Not signed in" to "Accesso non effettuato",
        "Ready after sign-in" to "Pronto dopo l’accesso", "Enable backup" to "Attiva backup", "Security" to "Sicurezza",
        "Authentication method" to "Metodo di autenticazione", "Passcode" to "Codice", "Get Lava Plus today" to "Passa a Lava Plus oggi",
        "Choose a plan" to "Scegli un piano", "Google Play Terms" to "Termini di Google Play",
        "Filter name" to "Nome del filtro", "Back" to "Indietro",
    ), "Imposta codice", "Nuovo passcode", "Conferma codice"),
    LocalizedRouteCopy("ja", "ja", mapOf(
        "Guard" to "ガード", "Now filtering" to "フィルタリング中", "Switch or manage filters" to "フィルターを切り替え・管理",
        "Choose a filter to use, view or edit." to "使う、見る、編集するフィルターを選びます。", "View filter" to "フィルターを表示", "More actions" to "その他の操作",
        "Use this filter" to "このフィルタを使用", "Share" to "共有", "Cancel" to "キャンセル",
        "Lava blocks these" to "Lava がブロックするもの", "Edit" to "編集", "Rename filter" to "フィルターの名前を変更",
        "Emoji" to "絵文字", "Name" to "名前", "Save" to "保存",
        "Cancel editing" to "編集をキャンセル", "Import" to "読み込む", "Scan a QR code" to "QR コードをスキャン",
        "Import from Photo" to "写真から読み込む", "Enter a code" to "コードを入力", "Enter a setup code" to "セットアップコードを入力",
        "Setup code" to "セットアップコード", "Continue" to "続ける", "Settings" to "設定",
        "Your connection" to "接続の流れ", "Account & Backup" to "アカウントとバックアップ", "Not signed in" to "未サインイン",
        "Ready after sign-in" to "サインイン後に利用可能", "Enable backup" to "バックアップを有効にする", "Security" to "セキュリティ",
        "Authentication method" to "認証方法", "Passcode" to "パスコード", "Get Lava Plus today" to "Lava Plusを始めよう",
        "Choose a plan" to "プランを選択", "Google Play Terms" to "Google Play 利用規約",
        "Filter name" to "フィルター名", "Back" to "戻る",
    ), "パスコードを設定", "新しいパスコード", "パスコードを確認"),
    LocalizedRouteCopy("ko", "ko", mapOf(
        "Guard" to "보호", "Now filtering" to "현재 필터링 중", "Switch or manage filters" to "필터 전환 또는 관리",
        "Choose a filter to use, view or edit." to "사용하거나 보거나 편집할 필터를 선택하세요.", "View filter" to "필터 보기", "More actions" to "추가 작업",
        "Use this filter" to "이 필터 사용", "Share" to "공유", "Cancel" to "취소",
        "Lava blocks these" to "Lava가 차단하는 항목", "Edit" to "편집", "Rename filter" to "필터 이름 변경",
        "Emoji" to "이모지", "Name" to "이름", "Save" to "저장",
        "Cancel editing" to "편집 취소", "Import" to "가져오기", "Scan a QR code" to "QR 코드 스캔",
        "Import from Photo" to "사진에서 가져오기", "Enter a code" to "코드 입력", "Enter a setup code" to "설정 코드 입력",
        "Setup code" to "설정 코드", "Continue" to "계속", "Settings" to "설정",
        "Your connection" to "내 연결", "Account & Backup" to "계정 및 백업", "Not signed in" to "로그인하지 않음",
        "Ready after sign-in" to "로그인 후 사용 가능", "Enable backup" to "백업 활성화", "Security" to "보안",
        "Authentication method" to "인증 방법", "Passcode" to "암호", "Get Lava Plus today" to "지금 Lava Plus를 시작하세요",
        "Choose a plan" to "플랜 선택", "Google Play Terms" to "Google Play 약관",
        "Filter name" to "필터 이름", "Back" to "뒤로",
    ), "암호 설정", "새 암호", "암호 확인"),
    LocalizedRouteCopy("pt-BR", "pt-BR", mapOf(
        "Guard" to "Proteção", "Now filtering" to "Filtrando agora", "Switch or manage filters" to "Trocar ou gerenciar os filtros",
        "Choose a filter to use, view or edit." to "Escolha um filtro para usar, ver ou editar.", "View filter" to "Ver filtro", "More actions" to "Mais ações",
        "Use this filter" to "Usar este filtro", "Share" to "Compartilhar", "Cancel" to "Cancelar",
        "Lava blocks these" to "A Lava bloqueia estes", "Edit" to "Editar", "Rename filter" to "Renomear filtro",
        "Emoji" to "Emoji", "Name" to "Nome", "Save" to "Salvar",
        "Cancel editing" to "Cancelar edição", "Import" to "Importar", "Scan a QR code" to "Ler um código QR",
        "Import from Photo" to "Importar de uma foto", "Enter a code" to "Digite um código", "Enter a setup code" to "Inserir um código de configuração",
        "Setup code" to "Código de configuração", "Continue" to "Continuar", "Settings" to "Ajustes",
        "Your connection" to "Sua conexão", "Account & Backup" to "Conta e backup", "Not signed in" to "Não conectado",
        "Ready after sign-in" to "Pronto após o login", "Enable backup" to "Ativar backup", "Security" to "Segurança",
        "Authentication method" to "Método de autenticação", "Passcode" to "Código", "Get Lava Plus today" to "Adquira o Lava Plus hoje",
        "Choose a plan" to "Escolha um plano", "Google Play Terms" to "Termos do Google Play",
        "Filter name" to "Nome do filtro", "Back" to "Voltar",
    ), "Definir código", "Novo código", "Confirmar código"),
    LocalizedRouteCopy("zh-CN", "zh-Hans", mapOf(
        "Guard" to "防护", "Now filtering" to "正在筛选", "Switch or manage filters" to "切换或管理筛选器",
        "Choose a filter to use, view or edit." to "选择要使用、查看或编辑的筛选器。", "View filter" to "查看过滤器", "More actions" to "更多操作",
        "Use this filter" to "使用此筛选器", "Share" to "分享", "Cancel" to "取消",
        "Lava blocks these" to "Lava 会拦截这些", "Edit" to "编辑", "Rename filter" to "重命名筛选器",
        "Emoji" to "表情符号", "Name" to "名称", "Save" to "保存",
        "Cancel editing" to "取消编辑", "Import" to "导入", "Scan a QR code" to "扫描 QR 码",
        "Import from Photo" to "从照片导入", "Enter a code" to "输入代码", "Enter a setup code" to "输入设置代码",
        "Setup code" to "设置代码", "Continue" to "继续", "Settings" to "设置",
        "Your connection" to "你的连接", "Account & Backup" to "账户与备份", "Not signed in" to "未登录",
        "Ready after sign-in" to "登录后即可使用", "Enable backup" to "启用备份", "Security" to "安全",
        "Authentication method" to "验证方法", "Passcode" to "密码", "Get Lava Plus today" to "今天就升级 Lava Plus",
        "Choose a plan" to "选择方案", "Google Play Terms" to "Google Play 条款",
        "Filter name" to "筛选器名称", "Back" to "返回",
    ), "设置密码", "新密码", "确认密码"),
    LocalizedRouteCopy("zh-TW", "zh-Hant", mapOf(
        "Guard" to "防護", "Now filtering" to "篩選中", "Switch or manage filters" to "切換或管理篩選器",
        "Choose a filter to use, view or edit." to "選擇要使用、查看或編輯的篩選器。", "View filter" to "檢視篩選器", "More actions" to "更多操作",
        "Use this filter" to "使用此過濾器", "Share" to "分享", "Cancel" to "取消",
        "Lava blocks these" to "Lava 會封鎖這些", "Edit" to "編輯", "Rename filter" to "重新命名篩選器",
        "Emoji" to "表情符號", "Name" to "名稱", "Save" to "儲存",
        "Cancel editing" to "取消編輯", "Import" to "匯入", "Scan a QR code" to "掃描 QR 碼",
        "Import from Photo" to "從照片匯入", "Enter a code" to "輸入代碼", "Enter a setup code" to "輸入設定代碼",
        "Setup code" to "設定代碼", "Continue" to "繼續", "Settings" to "設定",
        "Your connection" to "你的連線", "Account & Backup" to "帳號與備份", "Not signed in" to "尚未登入",
        "Ready after sign-in" to "登入後即可使用", "Enable backup" to "啟用備份", "Security" to "安全性",
        "Authentication method" to "驗證方法", "Passcode" to "密碼", "Get Lava Plus today" to "今天就升級 Lava Plus",
        "Choose a plan" to "選擇方案", "Google Play Terms" to "Google Play 條款",
        "Filter name" to "篩選器名稱", "Back" to "返回",
    ), "設定密碼", "新密碼", "確認密碼"),
)
