package com.lavasec.app.reactnative

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.security.SecurityAvailability
import com.lavasec.app.security.SecurityOwnerState
import com.lavasec.app.security.SecurityOwner
import com.lavasec.app.security.SecurityPresentationVisit
import com.lavasec.app.security.SecurityPreferences
import com.lavasec.app.security.SecurityPreferencesPort
import com.lavasec.app.security.PasscodeCredentialState
import com.lavasec.app.security.PasscodeVerification
import com.lavasec.app.security.PasscodeVerifierAvailability
import com.lavasec.app.security.PasscodeVerifierPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReactAppLockViewTest {
    @Test fun `an interrupted accepted session keeps pixels but blocks input accessibility and another unlock`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        val react = View(activity)
        content.addView(react)
        val cover = ReactAppLockView(activity, content, {}, {})
        content.addView(cover)
        val paused = SecurityOwnerState(availability = SecurityAvailability.Available, enabled = true,
            protectedSurfaces = setOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.FILTERS),
            authenticatedSurfaces = emptySet(), appUnlockCoverChoice = true)
        cover.render(false, paused, false, ProtectedSurface.FILTERS, presentationReady = false,
            appCoverRequired = false, retainSessionFrame = true)
        assertEquals(View.VISIBLE, react.visibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, react.importantForAccessibility)
        assertEquals(View.VISIBLE, cover.visibility)
        assertTrue(cover.isClickable)
        assertEquals(View.GONE, cover.getChildAt(0).visibility)
        assertEquals(View.GONE, cover.getChildAt(1).visibility)
        cover.render(false, paused, false, ProtectedSurface.FILTERS, presentationReady = false,
            appCoverRequired = true, retainSessionFrame = true)
        assertEquals("A real privacy boundary retires retained paint", View.INVISIBLE, react.visibility)
    }

    @Test fun `known OFF unavailable security exposes public Retry without admitting private content`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        val react = View(activity)
        content.addView(react)
        var retries = 0
        val cover = ReactAppLockView(activity, content, { retries++ }, {})
        content.addView(cover)
        val unavailable = SecurityOwnerState(availability = SecurityAvailability.Unavailable, appUnlockCoverChoice = false)
        cover.render(true, unavailable, false, appCoverRequired = false)
        assertEquals(View.INVISIBLE, react.visibility)
        val retry = cover.getChildAt(1) as Button
        assertEquals(activity.getString(com.lavasec.app.presentation.R.string.onboarding_retry), retry.text)
        assertTrue(retry.isEnabled)
        retry.performClick()
        assertEquals(1, retries)
        assertFalse(ReactAppLockView.admits(true, unavailable, null, true))
    }

    @Test fun `app unlock cannot reveal a separately protected retained page`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        val react = View(activity)
        content.addView(react)
        val cover = ReactAppLockView(activity, content, {}, {})
        content.addView(cover)
        for (surface in listOf(ProtectedSurface.ACTIVITY, ProtectedSurface.FILTERS, ProtectedSurface.APP_SETTINGS)) {
            val state = SecurityOwnerState(availability = SecurityAvailability.Available, enabled = true,
                protectedSurfaces = setOf(ProtectedSurface.APP_UNLOCK, surface),
                authenticatedSurfaces = setOf(ProtectedSurface.APP_UNLOCK))
            cover.render(true, state, false, surface)
            assertEquals(View.INVISIBLE, react.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, react.importantForAccessibility)
            assertSame("Concealment preserves the retained route and draft owner", react, content.getChildAt(0))
            cover.render(true, state.copy(authenticatedSurfaces = setOf(ProtectedSurface.APP_UNLOCK, surface)), false, surface)
            assertEquals(View.VISIBLE, react.visibility)
            cover.render(false, state, false, surface)
            cover.render(true, state, false, surface)
            assertEquals("A resume without a fresh page grant stays covered", View.INVISIBLE, react.visibility)
        }
    }

    @Test fun `authorized page remains covered until its exact presentation is ready`() {
        val state = SecurityOwnerState(availability = SecurityAvailability.Available, enabled = true,
            protectedSurfaces = setOf(ProtectedSurface.ACTIVITY), authenticatedSurfaces = setOf(ProtectedSurface.ACTIVITY))
        assertFalse(ReactAppLockView.admits(true, state, ProtectedSurface.ACTIVITY, false))
        assertTrue(ReactAppLockView.admits(true, state, ProtectedSurface.ACTIVITY, true))
        assertTrue("A known public page does not require an unrelated surface grant",
            ReactAppLockView.admits(true, state.copy(authenticatedSurfaces = emptySet()), null, true))
    }

    @Test fun `credential presentation is not admitted as a public route`() {
        val state = SecurityOwnerState(availability = SecurityAvailability.Available, enabled = true,
            protectedSurfaces = emptySet(), authenticatedSurfaces = setOf(ProtectedSurface.APP_UNLOCK))
        assertFalse(ReactAppLockView.admits(true, state, null, true, credentials = true))
        assertTrue(ReactAppLockView.admits(true, state.copy(credentialsAuthenticated = true), null, true, credentials = true))
        assertFalse(ReactAppLockView.admits(false, state.copy(credentialsAuthenticated = true), null, true, credentials = true))
    }

    @Test fun `native cover requires whole app authority and blocks drawing and accessibility`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        val react = View(activity)
        content.addView(react)
        var requests = 0
        val cover = ReactAppLockView(activity, content, { requests++ }, {})
        content.addView(cover)
        val locked = SecurityOwnerState(availability = SecurityAvailability.Available,
            enabled = true, protectedSurfaces = setOf(ProtectedSurface.APP_UNLOCK))
        for (authority in listOf(null, locked, locked.copy(authenticatedSurfaces = setOf(ProtectedSurface.FILTERS)))) {
            cover.render(true, authority, false)
            assertEquals(View.INVISIBLE, react.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, react.importantForAccessibility)
            assertEquals(View.VISIBLE, cover.visibility)
        }
        (cover.getChildAt(1) as Button).performClick()
        assertEquals(1, requests)
        val unlocked = locked.copy(authenticatedSurfaces = setOf(ProtectedSurface.APP_UNLOCK))
        cover.render(true, unlocked, false)
        assertEquals(View.VISIBLE, react.visibility)
        assertEquals(View.GONE, cover.visibility)
        cover.render(false, unlocked, false)
        assertEquals(View.INVISIBLE, react.visibility)
        cover.render(true, unlocked.copy(recoveryRequired = true), false)
        assertEquals(View.INVISIBLE, react.visibility)
    }

    @Test fun `confirmed all-off policy never covers, while a credential alone does not opt in`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        val react = View(activity)
        content.addView(react)
        val cover = ReactAppLockView(activity, content, {}, {})
        content.addView(cover)
        // Credential configured but no protected surface: not an opt-in.
        val credentialOnly = SecurityOwnerState(availability = SecurityAvailability.Available,
            enabled = true, protectedSurfaces = emptySet())
        // Credential removed: the all-off state.
        val allOff = SecurityOwnerState(availability = SecurityAvailability.Available,
            enabled = false, protectedSurfaces = emptySet())
        for (state in listOf(credentialOnly, allOff)) {
            cover.render(true, state, false)
            assertEquals(View.VISIBLE, react.visibility)
            assertEquals(View.GONE, cover.visibility)
            // Confirmed all-off keeps rendering through the inactive frame too.
            cover.render(false, state, false)
            assertEquals(View.VISIBLE, react.visibility)
            assertEquals(View.GONE, cover.visibility)
        }
    }
    @Test fun `public error paint cannot become private write or entry readiness and normal handoff conceals first`() {
        val frames = NativePresentationFrames()
        assertTrue(frames.publish(7, 1, publicRetry = true, foreground = true))
        assertFalse(frames.paintReady(7))
        assertTrue(frames.acknowledge(7, 1, publicRetry = true, foreground = true))
        assertTrue(frames.paintReady(7))
        assertFalse("Public paint never admits a native write or entry", frames.normalReady(7))
        val locked = SecurityOwnerState(availability = SecurityAvailability.Available, enabled = true,
            protectedSurfaces = setOf(ProtectedSurface.APP_UNLOCK))
        assertFalse(ReactAppLockView.admits(true, locked, null, frames.paintReady(7)))
        assertTrue(ReactAppLockView.admits(true, locked.copy(authenticatedSurfaces = setOf(ProtectedSurface.APP_UNLOCK)), null, frames.paintReady(7)))
        assertTrue(frames.publish(7, 2, publicRetry = false, foreground = true))
        assertFalse("Normal publication retires public paint before private construction", frames.paintReady(7))
        assertFalse(frames.normalReady(7))
        assertFalse(frames.acknowledge(7, 1, publicRetry = true, foreground = true))
        assertTrue(frames.acknowledge(7, 2, publicRetry = false, foreground = true))
        assertTrue(frames.normalReady(7))
    }

    @Test fun `queued pre pause Ready cannot reacknowledge after resume until a fresh normal serial is published`() {
        val frames = NativePresentationFrames()
        assertTrue(frames.publish(7, 10, publicRetry = false, foreground = true))
        // The old Ready was queued on IO before the real pause reset the authentication turn.
        frames.pause()
        assertFalse(frames.acknowledge(7, 10, publicRetry = false, foreground = true))
        assertFalse(frames.publish(7, 10, publicRetry = false, foreground = true))
        assertFalse(frames.normalReady(7))
        assertTrue(frames.publish(7, 11, publicRetry = false, foreground = true))
        assertFalse(frames.acknowledge(7, 10, publicRetry = false, foreground = true))
        assertTrue(frames.acknowledge(7, 11, publicRetry = false, foreground = true))
        assertTrue(frames.normalReady(7))
    }

    @Test fun `public paint refuses paused stale graph and wrong kind receipts while preserving a successor`() {
        val frames = NativePresentationFrames()
        assertTrue(frames.publish(7, 1, publicRetry = true, foreground = true))
        assertFalse(frames.acknowledge(7, 1, publicRetry = false, foreground = true))
        assertFalse(frames.acknowledge(7, 1, publicRetry = true, foreground = false))
        frames.pause()
        assertFalse(frames.acknowledge(7, 1, publicRetry = true, foreground = true))
        assertTrue(frames.publish(8, 2, publicRetry = false, foreground = true))
        assertFalse(frames.acknowledge(7, 1, publicRetry = true, foreground = true))
        assertTrue(frames.acknowledge(8, 2, publicRetry = false, foreground = true))
        assertTrue(frames.normalReady(8))
        assertFalse(frames.paintReady(7))
    }

    @Test fun `a queued higher serial publication cannot acquire a normal receipt during background`() {
        val frames = NativePresentationFrames()
        assertTrue(frames.publish(7, 1, publicRetry = false, foreground = true))
        assertTrue(frames.acknowledge(7, 1, publicRetry = false, foreground = true))
        frames.pause()
        assertFalse(frames.publish(7, 2, publicRetry = false, foreground = false))
        assertFalse(frames.acknowledge(7, 2, publicRetry = false, foreground = true))
        assertFalse(frames.normalReady(7))
        assertTrue(frames.publish(7, 3, publicRetry = false, foreground = true))
        assertFalse(frames.acknowledge(7, 2, publicRetry = false, foreground = true))
        assertTrue(frames.acknowledge(7, 3, publicRetry = false, foreground = true))
        assertTrue(frames.normalReady(7))
    }

    @Test fun `one fresh unlock restores a retained filter read but its native cover still requires a fresh measured receipt`() = runTest {
        val verifier = object : PasscodeVerifierPort {
            override fun availability() = PasscodeVerifierAvailability.Available
            override fun credentialState() = PasscodeCredentialState.Available
            override fun configure(code: String) = false
            override fun verify(code: String) = if (code == "1234") PasscodeVerification.Verified else PasscodeVerification.Wrong
            override fun remove() = false
        }
        val preferences = object : SecurityPreferencesPort {
            private var value = SecurityPreferences(enabled = true,
                protectedSurfaces = setOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.FILTERS))
            override fun load() = value
            override fun save(preferences: SecurityPreferences) { value = preferences }
        }
        val security = SecurityOwner(verifier, preferences, backgroundScope)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity); val retainedDraft = View(activity); content.addView(retainedDraft)
        val cover = ReactAppLockView(activity, content, {}, {}); content.addView(cover)
        val visit = SecurityPresentationVisit { true }; val frames = NativePresentationFrames()
        var prompts = 0
        suspend fun unlock(surface: ProtectedSurface) = security.requireAuthentication(surface) {
            prompts++; assertTrue(security.completeAuthentication(it.id, "1234"))
        }
        fun render(foreground: Boolean) = cover.render(foreground, security.state.value, false,
            ProtectedSurface.FILTERS, frames.normalReady(7, security.state.value.authenticationRevision))
        try {
            security.enterForeground(activity, 7, visit)
            assertTrue(unlock(ProtectedSurface.APP_UNLOCK)); assertTrue(unlock(ProtectedSurface.FILTERS))
            val oldRevision = security.state.value.authenticationRevision
            assertTrue(frames.publish(7, 1, publicRetry = false, foreground = true,
                readRevision = oldRevision, currentReadRevision = oldRevision))
            assertTrue(frames.acknowledge(7, 1, publicRetry = false, foreground = true,
                readRevision = oldRevision, currentReadRevision = oldRevision))
            render(true); assertEquals(View.VISIBLE, retainedDraft.visibility)
            frames.pause(); security.suspendForeground(activity, 7, visit); render(false)
            assertEquals(View.INVISIBLE, retainedDraft.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, retainedDraft.importantForAccessibility)
            security.enterForeground(activity, 7, visit); prompts = 0
            assertTrue(unlock(ProtectedSurface.APP_UNLOCK)); assertTrue(unlock(ProtectedSurface.FILTERS))
            assertEquals(1, prompts)
            val freshRevision = security.state.value.authenticationRevision
            render(true); assertEquals("Fresh read is not a layout receipt", View.INVISIBLE, retainedDraft.visibility)
            assertFalse(frames.acknowledge(7, 1, publicRetry = false, foreground = true,
                readRevision = oldRevision, currentReadRevision = freshRevision))
            assertTrue(frames.publish(7, 2, publicRetry = false, foreground = true,
                readRevision = freshRevision, currentReadRevision = freshRevision))
            render(true); assertEquals(View.INVISIBLE, retainedDraft.visibility)
            assertTrue(frames.acknowledge(7, 2, publicRetry = false, foreground = true,
                readRevision = freshRevision, currentReadRevision = freshRevision))
            render(true); assertEquals(View.VISIBLE, retainedDraft.visibility)
            assertSame(retainedDraft, content.getChildAt(0))
        } finally { security.close() }
    }

    @Test fun `actual all off authentication reset rejects queued higher serial publication after resume and recovers with captured fresh epoch`() = runTest {
        val verifier = object : PasscodeVerifierPort {
            override fun availability() = PasscodeVerifierAvailability.Available
            override fun credentialState() = PasscodeCredentialState.Absent
            override fun configure(code: String) = false
            override fun verify(code: String) = PasscodeVerification.Wrong
            override fun remove() = true
        }
        val preferences = object : SecurityPreferencesPort {
            private var value = SecurityPreferences()
            override fun load() = value
            override fun save(preferences: SecurityPreferences) { value = preferences }
        }
        val security = SecurityOwner(verifier, preferences, backgroundScope)
        try {
            val frames = NativePresentationFrames()
            val original = security.state.value.authenticationRevision
            assertTrue(security.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            assertTrue(frames.publish(7, 1, publicRetry = false, foreground = true,
                readRevision = original, currentReadRevision = original))
            frames.pause()
            // Exact production pause action; all-off remains authorized but its real turn changes.
            security.resetAuthenticationTurn()
            val resumed = security.state.value.authenticationRevision
            assertTrue(resumed > original)
            assertTrue(security.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            // The old higher-serial command reaches Main only AFTER resume.
            assertFalse(frames.publish(7, 2, publicRetry = false, foreground = true,
                readRevision = original, currentReadRevision = resumed))
            assertFalse(frames.acknowledge(7, 2, publicRetry = false, foreground = true,
                readRevision = original, currentReadRevision = resumed))
            assertTrue(frames.publish(7, 3, publicRetry = false, foreground = true,
                readRevision = resumed, currentReadRevision = resumed))
            assertTrue(frames.acknowledge(7, 3, publicRetry = false, foreground = true,
                readRevision = resumed, currentReadRevision = resumed))
            assertTrue(frames.normalReady(7, resumed))
            // Even a reset outside pause invalidates private readiness immediately.
            security.resetAuthenticationTurn()
            assertFalse(frames.normalReady(7, security.state.value.authenticationRevision))
            assertFalse(frames.acknowledge(7, 3, publicRetry = false, foreground = true,
                readRevision = security.state.value.authenticationRevision, currentReadRevision = security.state.value.authenticationRevision))
            assertTrue(frames.publish(7, 4, publicRetry = true, foreground = true))
            assertTrue(frames.acknowledge(7, 4, publicRetry = true, foreground = true))
            security.resetAuthenticationTurn()
            assertTrue("Public paint stays safe through authentication of the Retry cover", frames.paintReady(7, security.state.value.authenticationRevision))
            assertFalse(frames.normalReady(7, security.state.value.authenticationRevision))
        } finally { security.close() }
    }

}
