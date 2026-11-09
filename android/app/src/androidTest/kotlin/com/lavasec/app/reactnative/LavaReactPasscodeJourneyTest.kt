package com.lavasec.app.reactnative

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.MainProductViewModel
import com.lavasec.app.foundation.filter.CanonicalSha256Encoder
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.canonicalSha256
import com.lavasec.app.presentation.R
import com.lavasec.app.security.AndroidKeystorePasscodeVerifier
import com.lavasec.app.security.PasscodeCredentialState
import com.lavasec.app.security.PasscodeVerification
import com.lavasec.app.security.SecurityAvailability
import com.lavasec.app.security.SecurityFailure
import com.lavasec.app.security.SecurityOwnerState
import com.lavasec.app.security.SecurityPreferenceStore
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Packaged RN Security → native passcode management, using the installed credential authority.
 * Starts without a credential and removes only the credential created here through the real UI.
 * Keystore/preferences probes are read-only; no owner, credential, entitlement or result is faked.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactPasscodeJourneyTest {
    @Test
    fun shippingPasscodeCancelSetupWrongCurrentBackReopenAndVerifiedRemovalUseNativeCredentialAuthority() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            InstrumentationRegistry.getArguments().getString(RECOVERY_MODE)?.let { mode ->
                recoverRecordedFailedFixture(journey, mode)
            }
            journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val verifier = AndroidKeystorePasscodeVerifier(journey.context)
            val preferences = SecurityPreferenceStore(journey.context)
            val generation = checkNotNull(product.state.value.graphGeneration)
            val library = checkNotNull(product.currentFilterLibrary())
            val originalPreferences = preferences.load()
            assertEquals("A dedicated unconfigured credential fixture is required; never replace an existing passcode",
                PasscodeCredentialState.Absent, verifier.credentialState())
            assertEquals(SecurityAvailability.Available, security(journey).availability)
            assertFalse(security(journey).enabled)
            assertFalse(originalPreferences.enabled)
            assertTrue(originalPreferences.protectedSurfaces.isEmpty())
            assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))

            var ownsCreatedCredential = false
            var primaryFailure: Throwable? = null
            try {
                openSecurity(journey)
                waitPasscodeChecked(journey, false)
                clickPasscode(journey)
                expectFields(journey, enabled = false)
                setField(journey, R.string.security_new_passcode, CREATED_CODE)
                setField(journey, R.string.security_confirm_passcode, CREATED_CODE)
                assertNativePrivacy(journey, expectedFields = 2)
                clickNativeButton(journey, journey.context.getString(android.R.string.cancel))
                awaitReturned(journey, enabled = false)
                assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
                assertEquals("Cancel does not publish credential preferences", originalPreferences, preferences.load())
                assertEquals(library, product.currentFilterLibrary())

                clickPasscode(journey)
                expectFields(journey, enabled = false)
                setField(journey, R.string.security_new_passcode, CREATED_CODE)
                setField(journey, R.string.security_confirm_passcode, CREATED_CODE)
                assertNativePrivacy(journey, expectedFields = 2)
                // Initial absence was established before this real UI action. Cleanup may only
                // remove a credential that verifies one of this fixture's own submitted codes.
                ownsCreatedCredential = true
                clickNativeButton(journey, journey.context.getString(R.string.security_set_passcode))
                val currentAfterSetup = { requireFixtureAuthority(journey, product, generation) }
                awaitNative(journey, "successful native setup leaves credentials page concealed without a grant") {
                    currentAfterSetup()
                    passwordFields(journey).isEmpty() && !journey.visible("Authentication method") &&
                        journey.visible(journey.context.getString(R.string.security_unlock_hint)) &&
                        security(journey).let { it.enabled && !it.inFlight && !it.credentialsAuthenticated &&
                            it.authenticatedSurfaces.isEmpty() && it.availability == SecurityAvailability.Available }
                }
                assertEquals(PasscodeCredentialState.Available, verifier.credentialState())
                assertEquals(PasscodeVerification.Verified, verifier.verify(CREATED_CODE))
                val configuredPreferences = preferences.load()
                assertTrue(configuredPreferences.enabled)
                assertFalse(configuredPreferences.credentialChangePending)
                assertTrue("Credential setup never opts into protected actions", configuredPreferences.protectedSurfaces.isEmpty())
                assertTrue("Setup never fabricates authentication grants", security(journey).authenticatedSurfaces.isEmpty())
                assertFalse(security(journey).credentialsAuthenticated)
                assertBridgePrivacy(journey)
                authenticateCoveredCredential(journey, verifier, CREATED_CODE, currentAfterSetup)
                awaitReturned(journey, enabled = true, requireCurrent = currentAfterSetup)
                assertTrue("Only the actual fresh credentials prompt may reveal the retained Security page",
                    security(journey).credentialsAuthenticated)
                assertTrue(security(journey).authenticatedSurfaces.isEmpty())
                assertEquals(configuredPreferences, preferences.load())

                clickPasscode(journey)
                expectFields(journey, enabled = true)
                setField(journey, R.string.security_enter_passcode, WRONG_CODE)
                setField(journey, R.string.security_new_passcode, REPLACEMENT_CODE)
                setField(journey, R.string.security_confirm_passcode, REPLACEMENT_CODE)
                assertNativePrivacy(journey, expectedFields = 3)
                clickNativeButton(journey, journey.context.getString(R.string.security_change_passcode))
                awaitWrongCurrent(journey)
                assertEquals("Refused change keeps the durable credential preferences", configuredPreferences, preferences.load())
                assertEquals(PasscodeVerification.Verified, verifier.verify(CREATED_CODE))
                assertEquals(PasscodeVerification.Wrong, verifier.verify(REPLACEMENT_CODE))
                assertTrue("The actual native management dialog remains live after wrong-current change",
                    nativeButton(journey, journey.context.getString(R.string.security_change_passcode)) != null)

                setField(journey, R.string.security_enter_passcode, WRONG_CODE)
                clickNativeButton(journey, journey.context.getString(R.string.security_remove_passcode))
                awaitWrongCurrent(journey)
                assertEquals("Refused removal keeps the same durable credential", configuredPreferences, preferences.load())
                assertEquals(PasscodeCredentialState.Available, verifier.credentialState())
                assertEquals(PasscodeVerification.Verified, verifier.verify(CREATED_CODE))
                assertTrue("Wrong-current removal cannot dismiss the credential dialog", passwordFields(journey).size == 3)

                // Real Back may first dismiss the native keyboard; a second Back is admitted
                // only while this same actual password dialog still exists.
                dismissWithBack(journey)
                awaitReturned(journey, enabled = true)
                assertEquals(configuredPreferences, preferences.load())
                assertEquals(PasscodeVerification.Verified, verifier.verify(CREATED_CODE))
                journey.reopen()
                requireFixtureAuthority(journey, product, generation)
                assertEquals("Activity recreation preserves the installed credential graph", generation, product.state.value.graphGeneration)
                assertEquals(configuredPreferences, preferences.load())
                assertTrue(security(journey).authenticatedSurfaces.isEmpty())
                assertFalse(security(journey).credentialsAuthenticated)
                val current = { requireFixtureAuthority(journey, product, generation) }
                openSecurity(journey, authenticate = {
                    authenticateOwnCredential(journey, verifier, CREATED_CODE, current)
                }, requireCurrent = current)
                assertTrue("The real reopened credentials route requires a fresh native grant",
                    security(journey).credentialsAuthenticated)
                assertTrue("Credential navigation does not authorize protected action surfaces",
                    security(journey).authenticatedSurfaces.isEmpty())
                assertEquals("Native authentication preserves durable opt-in preferences",
                    configuredPreferences, preferences.load())
                assertEquals(library, product.currentFilterLibrary())
                waitPasscodeChecked(journey, true)
                assertBridgePrivacy(journey)

                clickPasscode(journey)
                expectFields(journey, enabled = true)
                assertTrue("Dismissed native credential text is not retained on reopen", fieldsCleared(journey))
                setField(journey, R.string.security_enter_passcode, CREATED_CODE)
                assertNativePrivacy(journey, expectedFields = 3)
                clickNativeButton(journey, journey.context.getString(R.string.security_remove_passcode))
                awaitReturned(journey, enabled = false)
                assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
                val removed = preferences.load()
                assertFalse(removed.enabled)
                assertFalse(removed.credentialChangePending)
                assertTrue(removed.protectedSurfaces.isEmpty())
                assertFalse(removed.biometricEnabled)
                assertEquals(library, product.currentFilterLibrary())
                assertEquals(generation, product.state.value.graphGeneration)
                assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
                assertBridgePrivacy(journey)
                journey.assertNoFallback()
                ownsCreatedCredential = false
                println("LAVA_UI_PASSCODE cancel=unchanged setup=actual-keystore wrong-change=retained wrong-remove=retained back=reopened removal=verified native-password-fields=true JS-credentials=absent")
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                if (ownsCreatedCredential) {
                    try {
                        cleanUpOwnCredential(journey, verifier, product, generation)
                    } catch (cleanup: Throwable) {
                        if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
                    }
                }
            }
        }
    }

    private fun openSecurity(
        journey: ReactEmulatorJourney, authenticate: (() -> Unit)? = null,
        requireCurrent: () -> Unit = {},
    ) {
        requireCurrent()
        journey.click("Settings", prefix = true, scroll = false)
        requireCurrent()
        journey.scrollToTop()
        requireCurrent()
        // This top anchor precedes every Settings group even on the narrowest viewport.
        // Security acquisition below performs the genuine scroll to its actual row.
        journey.waitLabel("Your connection")
        requireCurrent()
        journey.click("Security")
        requireCurrent()
        awaitNative(journey, "RN Security route or its genuine native authentication prompt") {
            requireCurrent()
            journey.visible("Authentication method") || isAuthenticationPrompt(journey)
        }
        requireCurrent()
        if (isAuthenticationPrompt(journey)) {
            checkNotNull(authenticate) { "Credential navigation requires explicitly verified fixture authentication" }.invoke()
        }
        requireCurrent()
        awaitNative(journey, "authenticated RN Security route") {
            requireCurrent()
            passwordFields(journey).isEmpty() && journey.visible("Authentication method")
        }
        requireCurrent()
    }

    private fun isAuthenticationPrompt(journey: ReactEmulatorJourney): Boolean {
        val nodes = journey.accessibilityNodes()
        val field = passwordFields(journey, nodes).singleOrNull() ?: return false
        if (!field.isEnabled || field.hintText?.toString() !=
            journey.context.getString(R.string.security_enter_passcode)) return false
        val unlock = journey.context.getString(R.string.security_unlock_action)
        // The cover behind this modal has another Unlock button. Identify the
        // prompt in the password field's window, even while the IME obscures its
        // button; actual submission still requires the scoped visible control.
        return nodes.any { node ->
            node.windowId == field.windowId && node.packageName?.toString() == journey.context.packageName &&
                node.className?.toString() == "android.widget.Button" && node.text?.toString()?.equals(unlock, ignoreCase = true) == true &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        }
    }

    /** Complete the actual credentials navigation request; never grant through an owner probe. */
    private fun authenticateOwnCredential(
        journey: ReactEmulatorJourney, verifier: AndroidKeystorePasscodeVerifier,
        ownedCode: String, requireCurrent: () -> Unit,
    ) {
        requireCurrent()
        assertEquals("Only this fixture's verified credential may authenticate its native prompt",
            PasscodeVerification.Verified, verifier.verify(ownedCode))
        awaitNative(journey, "authentication is scoped to the actual single-field native Unlock dialog") {
            requireCurrent()
            isAuthenticationPrompt(journey)
        }
        setField(journey, R.string.security_enter_passcode, ownedCode, requireCurrent)
        requireCurrent()
        assertNativePrivacy(journey, expectedFields = 1)
        clickNativeButton(journey, journey.context.getString(R.string.security_unlock_action), requireCurrent)
        awaitNative(journey, "real native credentials authentication returns to RN") {
            requireCurrent()
            passwordFields(journey).isEmpty() && journey.visible("Authentication method") &&
                security(journey).let { it.enabled && it.credentialsAuthenticated && !it.inFlight &&
                    !it.recoveryRequired && it.failure == null && it.availability == SecurityAvailability.Available }
        }
        requireCurrent()
        assertBridgePrivacy(journey)
    }

    /** Real native cover → real native credential prompt, scoped to this exact fixture host. */
    private fun authenticateCoveredCredential(
        journey: ReactEmulatorJourney, verifier: AndroidKeystorePasscodeVerifier,
        ownedCode: String, requireCurrent: () -> Unit,
    ) {
        val host = journey.requireActivity()
        val hostID = host.nativeHostID
        val current = {
            requireCurrent()
            assertTrue("Covered credential authentication retains its exact live originating host",
                journey.requireActivity() === host && host.nativeHostID == hostID &&
                    journey.application.reactHost.currentReactContext?.currentActivity === host &&
                    host.foreground && !host.isFinishing && !host.isDestroyed)
        }
        current()
        assertEquals("A cover can authenticate only the fixture's independently verified credential",
            PasscodeVerification.Verified, verifier.verify(ownedCode))
        assertFalse("The retained credentials page cannot be exposed before its fresh grant",
            journey.visible("Authentication method"))
        assertFalse(security(journey).credentialsAuthenticated)
        assertTrue(security(journey).authenticatedSurfaces.isEmpty())
        clickNativeButton(journey, journey.context.getString(R.string.security_unlock_action), current)
        awaitNative(journey, "cover opens the exact actual single-field credentials authentication prompt") {
            current()
            isAuthenticationPrompt(journey)
        }
        authenticateOwnCredential(journey, verifier, ownedCode, current)
        current()
    }

    private fun security(journey: ReactEmulatorJourney): SecurityOwnerState =
        checkNotNull(journey.application.foregroundProduct.securityStateForSnapshot(
            journey.application.foregroundProduct.state.value.graphGeneration))

    private fun waitPasscodeChecked(journey: ReactEmulatorJourney, checked: Boolean, requireCurrent: () -> Unit = {}) {
        // The label's Pressable and Text are separate matching AX nodes. The Android
        // Switch publishes its controlled value as checked, not the label's selected flag.
        fun candidates() = journey.accessibilityNodes().filter { node ->
            node.packageName?.toString() == journey.context.packageName && node.isVisibleToUser &&
                !node.isEditable && node.className?.toString() == "android.widget.Switch" && node.isCheckable &&
                listOf(node.text, node.contentDescription).any { it?.toString()?.equals("Passcode", ignoreCase = true) == true }
        }
        requireCurrent()
        var target: AccessibilityNodeInfo? = null
        awaitNative(journey, "exact RN Passcode switch checked=$checked") {
            requireCurrent()
            val controls = candidates()
            assertTrue("The exact Passcode label must identify at most one native Switch", controls.size <= 1)
            val candidate = controls.singleOrNull() ?: return@awaitNative false
            if (!candidate.refresh() || candidates().singleOrNull() != candidate) return@awaitNative false
            if (!candidate.isEnabled || !candidate.isClickable || candidate.isChecked != checked) return@awaitNative false
            target = candidate
            true
        }
        requireCurrent()
        val control = checkNotNull(target)
        assertTrue("The actual Passcode Switch must remain current and operable",
            control.refresh() && candidates().singleOrNull() == control && control.isEnabled && control.isClickable)
        assertEquals("The native Switch's checked value reflects the real credential state", checked, control.isChecked)
        println("LAVA_UI_PASSCODE_SWITCH ${nodeFacts(control)}")
        requireCurrent()
    }

    private fun clickPasscode(journey: ReactEmulatorJourney, requireCurrent: () -> Unit = {}) {
        requireCurrent()
        try { dumpPasscodeClickCandidates(journey) }
        catch (diagnostic: Throwable) {
            println("LAVA_UI_PASSCODE_CLICK diagnosticFailureClass=${diagnostic.javaClass.name}")
        }
        // Keep the existing real action acquisition. Diagnostic reads neither select a
        // different target nor submit an extra action if the accepted click has no result.
        journey.click("Passcode", scroll = false)
        requireCurrent()
        println("LAVA_UI_PASSCODE_CLICK existingActionReturned=true")
    }

    private fun dumpPasscodeClickCandidates(journey: ReactEmulatorJourney) {
        val candidates = journey.accessibilityNodes().filter { node ->
            node.isVisibleToUser && listOf(node.text, node.contentDescription)
                .any { it?.toString()?.equals("Passcode", ignoreCase = true) == true }
        }
        println("LAVA_UI_PASSCODE_CLICK candidates=${candidates.size}")
        candidates.take(4).forEachIndexed { index, node ->
            var target = node
            var depth = 0
            while (!target.isClickable && depth < 12) {
                val parent = target.parent ?: break
                target = parent
                depth += 1
            }
            println("LAVA_UI_PASSCODE_CLICK_CANDIDATE index=$index labelNode=[${nodeFacts(node)}] ancestorDepth=$depth target=[${nodeFacts(target)}]")
        }
    }

    /** Whitelisted AX primitives only; never text, hints, descriptions or action labels. */
    private fun nodeFacts(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return "identity=${node.hashCode()} window=${node.windowId} class=${node.className} " +
            "bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom} children=${node.childCount} " +
            "visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable} " +
            "editable=${node.isEditable} password=${node.isPassword} focused=${node.isFocused} " +
            "selected=${node.isSelected} checkable=${node.isCheckable} checked=${node.isChecked} " +
            "showingHint=${node.isShowingHintText} actions=${node.actionList.map { it.id }}"
    }

    private fun passwordFields(
        journey: ReactEmulatorJourney, nodes: List<AccessibilityNodeInfo> = journey.accessibilityNodes(),
    ): List<AccessibilityNodeInfo> =
        nodes.filter {
            it.packageName?.toString() == journey.context.packageName && it.isVisibleToUser &&
                it.className?.toString() == "android.widget.EditText" && it.isEditable && it.isPassword
        }

    private fun expectFields(journey: ReactEmulatorJourney, enabled: Boolean, requireCurrent: () -> Unit = {}) {
        requireCurrent()
        val labels = listOfNotNull(
            R.string.security_enter_passcode.takeIf { enabled },
            R.string.security_new_passcode, R.string.security_confirm_passcode,
        ).map { journey.context.getString(it) }.toSet()
        awaitNative(journey, "exact native password fields") {
            requireCurrent()
            val fields = passwordFields(journey)
            fields.size == labels.size && fields.map { it.hintText?.toString() }.toSet() == labels
        }
        requireCurrent()
    }

    private fun setField(journey: ReactEmulatorJourney, resource: Int, value: String, requireCurrent: () -> Unit = {}) {
        requireCurrent()
        val hint = journey.context.getString(resource)
        var target: AccessibilityNodeInfo? = null
        awaitNative(journey, "native password field ready") {
            requireCurrent()
            target = passwordFields(journey).singleOrNull { it.hintText?.toString() == hint && it.isEnabled }
            target != null
        }
        requireCurrent()
        val field = checkNotNull(target)
        assertTrue(field.refresh())
        assertEquals("The refreshed actual field keeps its exact native hint", hint, field.hintText?.toString())
        assertTrue(field.isPassword && field.isEditable && field.isEnabled && field.isVisibleToUser)
        assertTrue(field.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
        requireCurrent()
        assertTrue("The actual password field accepts its one native text action", field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
        SystemClock.sleep(150)
        requireCurrent()
    }

    private fun nativeButton(journey: ReactEmulatorJourney, label: String, visibleOnly: Boolean = true): AccessibilityNodeInfo? =
        journey.accessibilityNodes().singleOrNull {
            it.packageName?.toString() == journey.context.packageName && (!visibleOnly || it.isVisibleToUser) && it.isEnabled &&
                it.className?.toString() == "android.widget.Button" && it.text?.toString()?.equals(label, ignoreCase = true) == true &&
                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
        }

    private fun clickNativeButton(
        journey: ReactEmulatorJourney, label: String, requireCurrent: () -> Unit = {},
        beforeClick: () -> Unit = {},
    ) = journey.clickNativeDialogButton(label, requireCurrent, beforeClick)

    private fun fieldsCleared(journey: ReactEmulatorJourney): Boolean = passwordFields(journey).let { fields ->
        fields.isNotEmpty() && fields.all { it.isShowingHintText || it.text.isNullOrEmpty() }
    }

    private fun awaitWrongCurrent(journey: ReactEmulatorJourney) {
        val wrong = journey.context.getString(R.string.security_wrong_passcode)
        awaitNative(journey, "wrong-current refusal stays in the native dialog") {
            passwordFields(journey).size == 3 && fieldsCleared(journey) &&
                journey.accessibilityNodes().any { it.isVisibleToUser && !it.isEditable && it.text?.toString() == wrong } &&
                security(journey).let { it.enabled && !it.inFlight && it.failure == SecurityFailure.WRONG_PASSCODE }
        }
        assertBridgePrivacy(journey)
    }

    private fun awaitReturned(journey: ReactEmulatorJourney, enabled: Boolean, requireCurrent: () -> Unit = {}) {
        requireCurrent()
        awaitNative(journey, "native dialog returned to RN Security") {
            requireCurrent()
            passwordFields(journey).isEmpty() && journey.visible("Authentication method") &&
                security(journey).let { it.enabled == enabled && !it.inFlight && it.availability == SecurityAvailability.Available } &&
                journey.snapshot().getJSONObject("security").getBoolean("hasAuthenticationMethod") == enabled
        }
        requireCurrent()
        waitPasscodeChecked(journey, enabled, requireCurrent)
        requireCurrent()
        assertBridgePrivacy(journey)
        requireCurrent()
    }

    private fun dismissWithBack(journey: ReactEmulatorJourney, requireCurrent: () -> Unit = {}) {
        requireCurrent()
        assertTrue("Back is scoped to a genuine native password dialog", passwordFields(journey).isNotEmpty())
        journey.back()
        requireCurrent()
        if (passwordFields(journey).isNotEmpty()) {
            requireCurrent()
            journey.back()
            requireCurrent()
        }
    }

    private fun assertNativePrivacy(journey: ReactEmulatorJourney, expectedFields: Int) {
        assertEquals(expectedFields, passwordFields(journey).size)
        assertBridgePrivacy(journey)
    }

    private fun assertBridgePrivacy(journey: ReactEmulatorJourney) {
        val snapshot = journey.snapshot().toString()
        for (code in listOf(CREATED_CODE, REPLACEMENT_CODE, WRONG_CODE)) {
            assertFalse("Native passcode text never enters the JS snapshot", snapshot.contains(code))
        }
    }

    /** Static geometry/state only on timeout; never print password text or arbitrary AX labels. */
    private fun awaitNative(journey: ReactEmulatorJourney, stage: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        val fields = passwordFields(journey)
        val owner = security(journey)
        val original = AssertionError("Native passcode stage '$stage' timed out: fields=${fields.size}, " +
            "enabled=${owner.enabled}, inFlight=${owner.inFlight}, failure=${owner.failure}, " +
            "availability=${owner.availability}, graph=${journey.application.foregroundProduct.state.value.graphGeneration}")
        try { original.addSuppressed(AssertionError("Native passcode action facts: " + passcodeActionFacts(journey))) }
        catch (diagnostic: Throwable) { original.addSuppressed(diagnostic) }
        try { dumpPasscodeWindowFacts(journey, stage) }
        catch (diagnostic: Throwable) { original.addSuppressed(diagnostic) }
        throw original
    }

    /** Static action identities and geometry survive in JUnit even when stdout is not retained. */
    private fun passcodeActionFacts(journey: ReactEmulatorJourney): String {
        val labels = mapOf(
            "cancel" to journey.context.getString(android.R.string.cancel),
            "set" to journey.context.getString(R.string.security_set_passcode),
            "change" to journey.context.getString(R.string.security_change_passcode),
            "remove" to journey.context.getString(R.string.security_remove_passcode),
        )
        val accessibility = org.json.JSONArray()
        journey.accessibilityNodes().filter { it.className?.toString() == "android.widget.Button" }.take(16).forEach { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            accessibility.put(JSONObject().put("action", labels.entries.firstOrNull {
                node.text?.toString()?.equals(it.value, ignoreCase = true) == true }?.key ?: "other")
                .put("window", node.windowId).put("bounds", bounds.toShortString())
                .put("visible", node.isVisibleToUser).put("enabled", node.isEnabled)
                .put("clickable", node.isClickable).put("clickAction", node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }))
        }
        val windows = org.json.JSONArray()
        journey.instrumentation.runOnMainSync {
            val roots = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews()
                else listOf(journey.requireActivity().window.decorView)
            roots.take(8).forEach { root ->
                val frame = Rect().also(root::getWindowVisibleDisplayFrame)
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
                val buttons = org.json.JSONArray()
                var visited = 0
                fun walk(view: View, depth: Int = 0) {
                    if (visited++ >= 400 || depth >= 16) return
                    if (view is android.widget.Button && buttons.length() < 12) {
                        val bounds = Rect(); val visible = view.getGlobalVisibleRect(bounds)
                        buttons.put(JSONObject().put("id", view.id).put("action", labels.entries.firstOrNull {
                            view.text?.toString()?.equals(it.value, ignoreCase = true) == true }?.key ?: "other")
                            .put("enabled", view.isEnabled).put("shown", view.isShown).put("attached", view.isAttachedToWindow)
                            .put("visible", visible).put("bounds", bounds.toShortString()).put("width", view.width).put("height", view.height))
                    }
                    if (view is ViewGroup) repeat(view.childCount) { walk(view.getChildAt(it), depth + 1) }
                }
                walk(root)
                windows.put(JSONObject().put("focus", root.hasWindowFocus()).put("width", root.width).put("height", root.height)
                    .put("visibleFrame", frame.toShortString())
                    .put("imeVisible", insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
                    .put("imeBottom", insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom)
                    .put("buttons", buttons))
            }
        }
        return JSONObject().put("accessibility", accessibility).put("windows", windows).toString()
    }

    /** Failure-only independent AX/native window facts, captured before Activity teardown. */
    private fun dumpPasscodeWindowFacts(journey: ReactEmulatorJourney, stage: String) {
        dumpPasscodeClickCandidates(journey)
        val automation = journey.instrumentation.uiAutomation
        val active = automation.rootInActiveWindow
        println("LAVA_UI_PASSCODE_WINDOWS stage=$stage count=${automation.windows.size} activeWindow=${active?.windowId} activeRootClass=${active?.className} activeRootChildren=${active?.childCount}")
        automation.windows.take(12).forEach { window ->
            val root = window.root
            val appOwned = root?.packageName?.toString() == journey.context.packageName
            val bounds = Rect().also(window::getBoundsInScreen)
            println("LAVA_UI_PASSCODE_WINDOW id=${window.id} type=${window.type} layer=${window.layer} active=${window.isActive} focused=${window.isFocused} appOwned=$appOwned bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom} rootClass=${root?.className} rootChildren=${root?.childCount}")
            if (!appOwned || root == null) return@forEach
            var visited = 0
            var relevant = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (visited >= 400 || depth >= 16 || !node.refresh()) return
                visited += 1
                if (relevant < 16 && (node.isEditable || node.isPassword ||
                        node.className?.toString() == "android.widget.EditText" ||
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })) {
                    relevant += 1
                    println("LAVA_UI_PASSCODE_AX_FIELD ${nodeFacts(node)}")
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let { walk(it, depth + 1) } }
            }
            walk(root, 0)
            println("LAVA_UI_PASSCODE_WINDOW id=${window.id} visited=$visited relevantFields=$relevant bounded=true")
        }
        journey.instrumentation.runOnMainSync {
            val activity = journey.requireActivity()
            val current = journey.application.reactHost.currentReactContext?.currentActivity
            println("LAVA_UI_PASSCODE_ACTIVITY identity=${System.identityHashCode(activity)} class=${activity.javaClass.name} currentIdentity=${current?.let(System::identityHashCode)} currentMatches=${current === activity} finishing=${activity.isFinishing} destroyed=${activity.isDestroyed} foreground=${activity.foreground} windowFocus=${activity.hasWindowFocus()}")
            val roots = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews()
                else listOf(activity.window.decorView)
            println("LAVA_UI_PASSCODE_NATIVE_WINDOWS count=${roots.size} publicWindowInspector=${Build.VERSION.SDK_INT >= 29}")
            roots.take(12).forEachIndexed { windowIndex, root ->
                var context: Context? = root.context
                var host: Activity? = null
                repeat(12) {
                    when (val candidate = context) {
                        is Activity -> { host = candidate; context = null }
                        is ContextWrapper -> context = candidate.baseContext.takeUnless { it === candidate }
                        else -> context = null
                    }
                }
                val bounds = Rect()
                val visible = root.getGlobalVisibleRect(bounds)
                println("LAVA_UI_PASSCODE_NATIVE_WINDOW index=$windowIndex root=${System.identityHashCode(root)} class=${root.javaClass.name} expectedDecor=${root === activity.window.decorView} contextHostMatches=${host === activity} attached=${root.isAttachedToWindow} shown=${root.isShown} focus=${root.hasWindowFocus()} visibility=${root.visibility} children=${(root as? ViewGroup)?.childCount ?: 0} measured=${root.measuredWidth}x${root.measuredHeight} globalVisible=$visible bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                var visited = 0
                var fields = 0
                fun walk(view: View, depth: Int) {
                    if (visited >= 400 || depth >= 16) return
                    visited += 1
                    if (view is EditText && fields < 16) {
                        fields += 1
                        val fieldBounds = Rect()
                        val fieldVisible = view.getGlobalVisibleRect(fieldBounds)
                        // Only compare fixed native resource hints; entered text is never read.
                        val currentHint = view.hint == journey.context.getString(R.string.security_enter_passcode)
                        val newHint = view.hint == journey.context.getString(R.string.security_new_passcode)
                        val confirmHint = view.hint == journey.context.getString(R.string.security_confirm_passcode)
                        println("LAVA_UI_PASSCODE_NATIVE_FIELD window=$windowIndex identity=${System.identityHashCode(view)} class=${view.javaClass.name} currentHint=$currentHint newHint=$newHint confirmHint=$confirmHint attached=${view.isAttachedToWindow} shown=${view.isShown} enabled=${view.isEnabled} focused=${view.isFocused} visibility=${view.visibility} accessibility=${view.importantForAccessibility} inputType=${view.inputType} measured=${view.measuredWidth}x${view.measuredHeight} globalVisible=$fieldVisible bounds=${fieldBounds.left},${fieldBounds.top},${fieldBounds.right},${fieldBounds.bottom}")
                    }
                    if (view is ViewGroup) repeat(view.childCount) { walk(view.getChildAt(it), depth + 1) }
                }
                walk(root, 0)
                println("LAVA_UI_PASSCODE_NATIVE_WINDOW index=$windowIndex visited=$visited editTextFields=$fields bounded=true")
            }
        }
    }

    private fun requireFixtureAuthority(journey: ReactEmulatorJourney, product: MainProductViewModel, generation: Long) {
        check(journey.application.foregroundProduct === product && product.state.value.graphGeneration == generation &&
            product.securityStateForSnapshot(generation) != null) {
            "Fixture credential cleanup refuses a retired or successor product authority"
        }
    }

    private fun cleanUpOwnCredential(
        journey: ReactEmulatorJourney, verifier: AndroidKeystorePasscodeVerifier,
        product: MainProductViewModel, generation: Long,
    ) {
        val current = { requireFixtureAuthority(journey, product, generation) }
        current()
        if (verifier.credentialState() == PasscodeCredentialState.Absent) return
        val ownedCode = listOf(CREATED_CODE, REPLACEMENT_CODE).singleOrNull {
            current()
            verifier.verify(it) == PasscodeVerification.Verified
        } ?: error("Cleanup refuses a credential not verified as this fixture's own")
        current()
        if (isAuthenticationPrompt(journey)) {
            authenticateOwnCredential(journey, verifier, ownedCode, current)
        } else if (passwordFields(journey).isNotEmpty()) dismissWithBack(journey, current)
        current()
        awaitNative(journey, "return to RN before fixture credential cleanup") {
            current()
            passwordFields(journey).isEmpty()
        }
        current()
        if (!journey.visible("Authentication method") &&
            journey.visible(journey.context.getString(R.string.security_unlock_hint))) {
            authenticateCoveredCredential(journey, verifier, ownedCode, current)
        }
        current()
        if (!journey.visible("Authentication method")) openSecurity(journey, authenticate = {
            authenticateOwnCredential(journey, verifier, ownedCode, current)
        }, requireCurrent = current)
        current()
        clickPasscode(journey, current)
        current()
        expectFields(journey, enabled = true, requireCurrent = current)
        setField(journey, R.string.security_enter_passcode, ownedCode, current)
        clickNativeButton(journey, journey.context.getString(R.string.security_remove_passcode), current)
        awaitReturned(journey, enabled = false, requireCurrent = current)
        current()
        assertEquals("Cleanup removes only the fixture credential through the real native flow",
            PasscodeCredentialState.Absent, verifier.credentialState())
    }

    /**
     * Explicit recovery for the recorded failed 598 journey only. Both modes deliberately fail
     * this instrumentation case so a maintenance invocation can never qualify as the normal
     * Absent → setup → removal journey. No argument means this branch is never entered.
     */
    private fun recoverRecordedFailedFixture(journey: ReactEmulatorJourney, mode: String): Nothing {
        val arguments = InstrumentationRegistry.getArguments()
        check(mode == "preflight" || mode == "remove") { "Unknown fixture recovery mode" }
        check(arguments.getString(RECOVERY_FAILED_RUN) == FAILED_FIXTURE_RUN) {
            "Recovery requires the exact recorded failed fixture run"
        }
        fun expectedDigest(key: String): String = checkNotNull(arguments.getString(key)) {
            "Recovery requires an explicit digest witness"
        }.also { check(it.matches(Regex("[0-9a-f]{64}"))) { "Recovery digest witness is malformed" } }

        // Hash the actual installed target APK once, never a compile-time source label or the
        // separately installed instrumentation APK. Retain file identity throughout this attempt.
        val apk = File(journey.context.applicationInfo.sourceDir).canonicalFile
        val apkLength = apk.length()
        val apkModified = apk.lastModified()
        fun requireSameApk() {
            check(File(journey.context.applicationInfo.sourceDir).canonicalFile == apk &&
                apk.length() == apkLength && apk.lastModified() == apkModified) {
                "Recovery refuses an installed APK replacement"
            }
        }
        val apkDigest = sha256(apk)
        requireSameApk()
        check(apkDigest == expectedDigest(RECOVERY_APK_DIGEST)) { "Recovery target APK digest differs" }
        assertFalse("Recovery never performs first-run setup", journey.snapshot().optBoolean("onboardingRequired"))
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        val library = checkNotNull(product.currentFilterLibrary())
        val preferences = SecurityPreferenceStore(journey.context)
        val originalPreferences = preferences.load()
        val verifier = AndroidKeystorePasscodeVerifier(journey.context)
        // FilterLibraryDraft is a normal class: Object.toString includes its enum-bearing
        // hashCode, which changes across processes. Commit to every durable value explicitly.
        // SecurityPreferences is a data class with a stable value-only toString representation.
        val libraryDigest = recoveryLibraryDigest(library)
        val preferencesDigest = sha256(originalPreferences.toString())
        val current = {
            requireFixtureAuthority(journey, product, generation)
            requireSameApk()
            assertTrue("Recovery cannot change the native filter library", library == product.currentFilterLibrary())
        }
        val unchanged = {
            current()
            assertTrue("Recovery preserves the exact recorded security preferences until removal",
                originalPreferences == preferences.load())
            security(journey).let { state ->
                assertEquals(SecurityAvailability.Available, state.availability)
                assertTrue(state.enabled)
                assertFalse(state.recoveryRequired)
                assertTrue(state.protectedSurfaces.isEmpty())
                assertTrue(state.authenticatedSurfaces.isEmpty())
                assertFalse(state.biometricEnabled)
            }
        }
        assertEquals(PasscodeCredentialState.Available, verifier.credentialState())
        assertTrue(originalPreferences.enabled)
        assertFalse(originalPreferences.credentialChangePending)
        assertFalse(originalPreferences.biometricEnabled)
        assertTrue(originalPreferences.protectedSurfaces.isEmpty())
        assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
        unchanged()
        assertFalse(security(journey).inFlight)
        assertFalse("Recovery starts without a pre-existing credentials grant", security(journey).credentialsAuthenticated)
        assertEquals("Recovery refuses every credential other than the recorded fixture's created code",
            PasscodeVerification.Verified, verifier.verify(CREATED_CODE))
        if (mode == "preflight") {
            assertFalse("Preflight never authenticates the credentials route", security(journey).credentialsAuthenticated)
            println("LAVA_PASSCODE_RECOVERY mode=preflight qualification=false failedRun=$FAILED_FIXTURE_RUN " +
                "apkSha256=$apkDigest librarySha256=$libraryDigest preferencesSha256=$preferencesDigest " +
                "libraryEncoding=$LIBRARY_ENCODING preferencesEncoding=SecurityPreferences-toString-UTF8 " +
                "createdFixtureVerified=true mutation=false")
            throw AssertionError("NON_QUALIFICATION: read-only recorded fixture recovery preflight completed")
        }
        check(arguments.getString(RECOVERY_LIBRARY_ENCODING) == LIBRARY_ENCODING) {
            "Recovery requires the current canonical native library witness encoding"
        }
        check(libraryDigest == expectedDigest(RECOVERY_LIBRARY_DIGEST)) { "Recovery native library witness differs" }
        check(preferencesDigest == expectedDigest(RECOVERY_PREFERENCES_DIGEST)) { "Recovery security preference witness differs" }
        // completeAuthentication publishes only the live credentials grant; it does not write
        // lastUnlockEpochMillis. The full preference witness therefore stays exact through Unlock.
        openSecurity(journey, authenticate = {
            authenticateOwnCredential(journey, verifier, CREATED_CODE, unchanged)
        }, requireCurrent = unchanged)
        unchanged()
        waitPasscodeChecked(journey, true, unchanged)
        clickPasscode(journey, unchanged)
        expectFields(journey, enabled = true, requireCurrent = unchanged)
        assertTrue("Recovery must acquire a fresh empty native management form", fieldsCleared(journey))
        setField(journey, R.string.security_enter_passcode, CREATED_CODE, unchanged)
        assertNativePrivacy(journey, expectedFields = 3)
        // Check every original witness immediately before the one genuine Remove action. After
        // that action only the documented removal preferences may differ; authority/library/APK
        // identity still remain fenced during completion. No reset, direct write or code search.
        clickNativeButton(journey, journey.context.getString(R.string.security_remove_passcode),
            requireCurrent = current, beforeClick = {
                unchanged()
                assertFalse("Recovery refuses a concurrent credential operation", security(journey).inFlight)
                assertEquals("The recorded fixture credential must still verify at the removal edge",
                    PasscodeVerification.Verified, verifier.verify(CREATED_CODE))
                unchanged()
            })
        awaitReturned(journey, enabled = false, requireCurrent = current)
        current()
        assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
        assertTrue("Recovery changes only confirmed passcode-removal preferences",
            originalPreferences.copy(enabled = false, lastUnlockEpochMillis = null,
                credentialChangePending = false, biometricEnabled = false) == preferences.load())
        assertBridgePrivacy(journey)
        println("LAVA_PASSCODE_RECOVERY mode=remove qualification=false failedRun=$FAILED_FIXTURE_RUN " +
            "apkSha256=$apkDigest librarySha256=$libraryDigest preferencesBeforeSha256=$preferencesDigest " +
            "preferencesAfterSha256=${sha256(preferences.load().toString())} libraryEncoding=$LIBRARY_ENCODING " +
            "credentialAbsent=true realNativeRemoval=true")
        throw AssertionError("NON_QUALIFICATION: recorded fixture removed through the real native flow; run the normal Absent journey separately")
    }

    /**
     * Test-only recovery identity over ALL durable FilterLibraryDraft values, matching the
     * fields of AuthoritativeFilterConfigurationRecordCodec and native value equality. Keep
     * profile, custom-source and resolver-tier order; sort only membership sets. Explicit
     * nullable tags keep absence distinct from an empty value. Enum tokens never use hashCode.
     * The production canonical encoder supplies length-prefixed UTF-8 and fixed-width primitives;
     * no fake artifact/configuration identity, portable projection or JS snapshot is involved.
     */
    private fun recoveryLibraryDigest(library: FilterLibraryDraft): String = canonicalSha256(LIBRARY_ENCODING) {
        writeInt(1)
        writeString(library.activeProfileId)
        writeString(library.resolverPlan.name)
        writeInt(library.profiles.size)
        library.profiles.forEach { profile ->
            writeString(profile.id)
            writeString(profile.name)
            writeRecoveryNullable(profile.protectionLevel?.name)
            writeString(profile.emoji)
            writeRecoverySet(profile.enabledSourceIds)
            writeRecoverySet(profile.guardrailSourceIds)
            writeRecoverySet(profile.manualRules.allowedDomains)
            writeRecoverySet(profile.manualRules.blockedDomains)
            writeInt(profile.customBlocklists.size)
            profile.customBlocklists.forEach { source ->
                writeString(source.id)
                writeString(source.displayName)
                writeString(source.sourceUrl)
                writeString(source.parseFormat.name)
                writeLong(source.createdAtEpochMillis)
                writeRecoveryNullable(source.lastAcceptedHash)
            }
        }
        writeRecoveryNullable(library.customDohUrl)
        writeRecoveryNullable(library.customDotEndpoint)
        writeRecoveryNullable(library.customDoqEndpoint)
        writeRecoveryNullable(library.customName)
        writeRecoveryNullable(library.customSecondaryEndpoint)
        writeBoolean(library.savedTiers != null)
        library.savedTiers?.let { tiers ->
            writeInt(tiers.size)
            tiers.forEach { tier ->
                writeString(tier.entry.providerId.rawId)
                writeString(tier.entry.transport.token)
                writeRecoveryNullable(tier.entry.customDohUrl)
                writeRecoveryNullable(tier.entry.customDotEndpoint)
                writeRecoveryNullable(tier.entry.customDoqEndpoint)
                writeRecoveryNullable(tier.entry.customPlainEndpoint)
                writeRecoveryNullable(tier.entry.customName)
                writeRecoveryNullable(tier.entry.customSecondaryEndpoint)
                writeBoolean(tier.isEnabled)
                writeRecoveryNullable(tier.retainedName)
            }
        }
        writeBoolean(library.qaProbeSet != null)
        library.qaProbeSet?.let { probes ->
            writeInt(probes.allDomains.size)
            probes.allDomains.forEach(::writeString)
        }
    }

    private fun CanonicalSha256Encoder.writeRecoveryNullable(value: String?) {
        writeBoolean(value != null)
        value?.let(::writeString)
    }

    private fun CanonicalSha256Encoder.writeRecoverySet(values: Set<String>) {
        writeInt(values.size)
        values.sorted().forEach(::writeString)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        // Public synthetic fixture inputs; neither these nor entered values are logged.
        const val CREATED_CODE = "73918264"
        const val REPLACEMENT_CODE = "48261539"
        const val WRONG_CODE = "61927385"
        const val RECOVERY_MODE = "parityPasscodeRecovery"
        const val RECOVERY_FAILED_RUN = "parityPasscodeRecoveryFailedRun"
        const val RECOVERY_APK_DIGEST = "parityPasscodeRecoveryApkSha256"
        const val RECOVERY_LIBRARY_DIGEST = "parityPasscodeRecoveryLibrarySha256"
        const val RECOVERY_LIBRARY_ENCODING = "parityPasscodeRecoveryLibraryEncoding"
        const val RECOVERY_PREFERENCES_DIGEST = "parityPasscodeRecoveryPreferencesSha256"
        const val FAILED_FIXTURE_RUN = "598b00f5661fb69c221fe1b9445f4f6a8de3483b"
        const val LIBRARY_ENCODING = "canonical-native-library-v1"
    }
}
