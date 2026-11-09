package com.lavasec.app.reactnative

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.lavasec.app.account.AccountAvailability
import com.lavasec.app.account.AccountSessionChanged
import com.lavasec.app.account.AccountSnapshot
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.security.PasscodeCredentialState
import com.lavasec.app.security.PasscodeVerification
import com.lavasec.app.security.PasscodeVerifierAvailability
import com.lavasec.app.security.PasscodeVerifierPort
import com.lavasec.app.security.SecurityOwner
import com.lavasec.app.security.SecurityPreferences
import com.lavasec.app.security.SecurityPreferencesPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeLinkedAccountSheetTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Fixture(val activity: Activity, private val security: SecurityOwner? = null) {
        private val captured = NativeVisitHost(activity, "original") { !activity.isDestroyed && !activity.isFinishing }
        var host: NativeVisitHost? = captured
        var account = AccountSnapshot(4, AccountAvailability.SIGNED_IN, "original-user", "native@example.test", setOf("google", "apple"))
        var authority = 8L
        var signedOut = 0
        var deleted = 0
        var mutation: suspend () -> Unit = {
            signedOut++; account = AccountSnapshot(5, AccountAvailability.SIGNED_OUT)
        }
        val changes = MutableStateFlow(0)
        val visit = NativeLinkedAccountVisit(captured, 7, this, account, security?.state?.value?.authenticationRevision ?: authority,
            currentHost = { host }, currentGeneration = { 7 }, currentBackend = { this },
            currentAccount = { account }, currentAuthorityRevision = { security?.state?.value?.authenticationRevision ?: authority },
            canRead = { security?.state?.value?.let {
                it.isAuthorized(ProtectedSurface.APP_UNLOCK) && it.isAuthorized(ProtectedSurface.APP_SETTINGS)
            } ?: true }, canMutate = { true }, authorize = { true },
            signOut = { _, current -> if (!current()) throw AccountSessionChanged(); mutation() },
            deleteAccount = { _, current ->
                if (!current()) throw AccountSessionChanged()
                deleted++; account = AccountSnapshot(5, AccountAvailability.SIGNED_OUT)
            })
        val copy = activity.linkedAccountCopy()
    }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
    private fun button(dialog: AlertDialog, text: String): Button = descendants(dialog.window!!.decorView)
        .filterIsInstance<Button>().single { it.text.toString() == text }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    @Test fun `real native sheet renders both linked providers and close never revokes`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val f = Fixture(controller.get())
            val sheet = async { f.activity.presentLinkedAccountSheet(f.visit, f.copy, f.changes) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val labels = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                .filter { it.text.contains("native@example.test") }
            assertEquals(2, labels.size)
            assertTrue(labels.any { it.text.contains("Google") })
            assertTrue(labels.any { it.text.contains("Apple") })
            assertTrue(labels.all { !it.isSaveEnabled })
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
            assertEquals(NativeLinkedAccountOutcome.CANCELLED, sheet.await())
            assertEquals(0, f.signedOut); assertEquals(0, f.deleted)
            assertTrue(labels.all { it.text.isEmpty() })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `delete confirmation cancel does nothing and confirmed action alone deletes`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val f = Fixture(controller.get())
            val sheet = async { f.activity.presentLinkedAccountSheet(f.visit, f.copy, f.changes) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            button(dialog, f.copy.delete).performClick(); idle()
            val cancelled = ShadowAlertDialog.getLatestAlertDialog()
            assertNotSame(dialog, cancelled)
            assertTrue(cancelled.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
            assertEquals(0, f.deleted)
            assertFalse(sheet.isCompleted)
            button(dialog, f.copy.delete).performClick(); idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); runCurrent(); idle()
            assertEquals(NativeLinkedAccountOutcome.COMPLETED, sheet.await())
            assertEquals(1, f.deleted); assertEquals(0, f.signedOut)
            assertFalse(dialog.isShowing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `revoked authority clears original labels and refuses old destructive confirmation`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val f = Fixture(controller.get())
            val sheet = async { f.activity.presentLinkedAccountSheet(f.visit, f.copy, f.changes) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val labels = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().filter { it.text.contains("native@example.test") }
            button(dialog, f.copy.delete).performClick(); idle()
            val confirmation = ShadowAlertDialog.getLatestAlertDialog()
            val oldConfirm = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
            f.authority++; f.changes.value++; runCurrent(); idle()
            assertEquals(NativeLinkedAccountOutcome.STALE, sheet.await())
            assertFalse(dialog.isShowing); assertFalse(confirmation.isShowing)
            assertTrue(labels.all { it.text.isEmpty() })
            oldConfirm.performClick(); idle(); runCurrent()
            assertEquals(0, f.deleted); assertEquals(0, f.signedOut)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `original host pause cancels presentation and old button cannot revoke successor`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val f = Fixture(controller.get())
            val sheet = async { f.activity.presentLinkedAccountSheet(f.visit, f.copy, f.changes) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val oldSignOut = button(dialog, f.copy.signOut)
            controller.pause(); runCurrent(); idle()
            assertTrue(sheet.isCancelled)
            assertFalse(dialog.isShowing)
            f.account = f.account.copy(generation = 6, userId = "successor-user")
            oldSignOut.performClick(); idle(); runCurrent()
            assertEquals(0, f.signedOut)
            assertEquals("successor-user", f.account.userId)
        } finally { controller.stop().destroy() }
    }

    @Test fun `real App Settings grant gates native identity and its revocation retires destructive confirmation`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val verifier = object : PasscodeVerifierPort {
            override fun availability() = PasscodeVerifierAvailability.Available
            override fun credentialState() = PasscodeCredentialState.Available
            override fun configure(code: String) = false
            override fun verify(code: String) = if (code == "2468") PasscodeVerification.Verified else PasscodeVerification.Wrong
            override fun remove() = false
        }
        val preferences = object : SecurityPreferencesPort {
            var value = SecurityPreferences(enabled = true, protectedSurfaces = setOf(ProtectedSurface.APP_SETTINGS))
            override fun load() = value
            override fun save(preferences: SecurityPreferences) { value = preferences }
        }
        val security = SecurityOwner(verifier, preferences, this)
        try {
            assertTrue(security.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            assertFalse(security.state.value.isAuthorized(ProtectedSurface.APP_SETTINGS))
            val locked = Fixture(controller.get(), security)
            val previousDialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNull(locked.visit.connections())
            assertEquals(NativeLinkedAccountOutcome.STALE,
                locked.activity.presentLinkedAccountSheet(locked.visit, locked.copy, locked.changes))
            assertSame("No dialog or private native identity may be created without App Settings", previousDialog, ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(0, locked.signedOut); assertEquals(0, locked.deleted)

            assertTrue(security.unlock("2468", ProtectedSurface.APP_SETTINGS))
            assertTrue(security.state.value.isAuthorized(ProtectedSurface.APP_SETTINGS))
            val admitted = Fixture(controller.get(), security)
            val sheet = async { admitted.activity.presentLinkedAccountSheet(admitted.visit, admitted.copy, admitted.changes) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val labels = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().filter { it.text.contains("native@example.test") }
            assertEquals(2, labels.size)
            button(dialog, admitted.copy.delete).performClick(); idle()
            val confirmation = ShadowAlertDialog.getLatestAlertDialog()
            val cachedConfirm = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
            security.resetAuthenticationTurn()
            assertFalse(security.state.value.isAuthorized(ProtectedSurface.APP_SETTINGS))
            admitted.changes.value++; runCurrent(); idle()
            assertEquals(NativeLinkedAccountOutcome.STALE, sheet.await())
            assertFalse(dialog.isShowing); assertFalse(confirmation.isShowing)
            assertTrue(labels.all { it.text.isEmpty() })
            cachedConfirm.performClick(); idle(); runCurrent()
            assertEquals(0, admitted.deleted); assertEquals(0, admitted.signedOut)
        } finally { security.close(); controller.pause().stop().destroy() }
    }

    @Test fun `real sign out button closes exactly after original local revocation`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val f = Fixture(controller.get())
            val sheet = async { f.activity.presentLinkedAccountSheet(f.visit, f.copy, f.changes) }
            runCurrent()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            button(dialog, f.copy.signOut).performClick(); runCurrent(); idle()
            assertEquals(NativeLinkedAccountOutcome.COMPLETED, sheet.await())
            assertEquals(1, f.signedOut)
            assertEquals(AccountAvailability.SIGNED_OUT, f.account.availability)
            assertFalse(dialog.isShowing)
        } finally { controller.pause().stop().destroy() }
    }
}
