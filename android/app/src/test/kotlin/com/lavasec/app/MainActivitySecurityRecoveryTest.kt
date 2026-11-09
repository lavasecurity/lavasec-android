package com.lavasec.app

import android.app.Activity
import android.content.Intent
import android.os.Looper
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
import com.lavasec.app.security.PasscodeCredentialState
import com.lavasec.app.security.PasscodeVerification
import com.lavasec.app.security.PasscodeVerifierAvailability
import com.lavasec.app.security.PasscodeVerifierPort
import com.lavasec.app.security.SecurityAvailability
import com.lavasec.app.security.SecurityOwner
import com.lavasec.app.security.SecurityPreferences
import com.lavasec.app.security.SecurityPreferencesPort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner

/** Actual Activity callback/lifecycle and real SecurityOwner credential mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class MainActivitySecurityRecoveryTest {
    @Test fun `actual successful recovery returns only the original native Security window`() = runTest {
        Fixture(this).use { f ->
            assertTrue(f.security.state.value.recoveryRequired)
            assertTrue(f.activity.hasWindowFocus())
            f.action()()
            assertEquals(1, f.verifier.removals)
            assertEquals(SecurityAvailability.Available, f.security.state.value.availability)
            assertFalse(f.security.state.value.enabled)
            assertFalse(f.security.state.value.recoveryRequired)
            assertTrue(f.security.state.value.authenticatedSurfaces.isEmpty())
            assertTrue(f.activity.isFinishing)
            assertEquals(Activity.RESULT_OK, shadowOf(f.activity).resultCode)
        }
    }

    @Test fun `owner refusal and admitted failed removal never report recovered or close`() = runTest {
        for (closed in listOf(false, true)) Fixture(this).use { f ->
            if (closed) f.security.close() else f.verifier.removeSucceeds = false
            f.action()()
            assertEquals(if (closed) 0 else 1, f.verifier.removals)
            assertTrue(f.security.state.value.recoveryRequired)
            assertFalse(f.activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(f.activity).resultCode)
        }
    }

    @Test fun `saved callback refuses graph and exact request changes before credential mutation`() = runTest {
        for (change in listOf("graph", "route")) Fixture(this).use { f ->
            val saved = f.action()
            when (change) {
                "graph" -> f.generation = 8
                else -> f.navigate(ProductNavigation.settings.open(ProductDestination.PrivacyData))
            }
            saved()
            assertEquals(0, f.verifier.removals)
            assertTrue(f.security.state.value.recoveryRequired)
            assertFalse(f.activity.isFinishing)
        }
    }

    @Test fun `lost window focus and background resume refuse old reset while fresh foreground callback works`() = runTest {
        Fixture(this).use { f ->
            val old = f.action()
            f.controller.windowFocusChanged(false)
            old(); assertEquals(0, f.verifier.removals)
            f.controller.pause().stop(); f.epoch++
            old(); assertEquals(0, f.verifier.removals)
            f.controller.start().resume().visible().windowFocusChanged(true)
            old(); assertEquals(0, f.verifier.removals)
            f.action()()
            assertEquals(1, f.verifier.removals)
            assertTrue(f.activity.isFinishing)
        }
    }

    @Test fun `durable reset cannot finish a successor graph or navigation request`() = runTest {
        for (changedGraph in listOf(false, true)) Fixture(this).use { f ->
            f.verifier.onRemove = { if (changedGraph) f.generation = 8 else f.navigate(ProductNavigation.settings.open(ProductDestination.PrivacyData)) }
            f.action()()
            assertEquals(1, f.verifier.removals)
            assertFalse(f.security.state.value.recoveryRequired)
            assertFalse(f.security.state.value.enabled)
            assertFalse(f.activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(f.activity).resultCode)
        }
    }

    @Test fun `actual background or window focus loss during credential publication cannot close the caller`() = runTest {
        for (background in listOf(false, true)) Fixture(this).use { f ->
            val mainThread = Thread.currentThread()
            assertSame(Looper.getMainLooper().thread, mainThread)
            val publicationEntered = CountDownLatch(1)
            val releasePublication = CountDownLatch(1)
            val publicationThread = AtomicReference<Thread>()
            f.verifier.onRemove = {
                // The real SecurityOwner owns this IO call. Hold publication;
                // never control Robolectric's main looper from the verifier.
                publicationThread.set(Thread.currentThread())
                publicationEntered.countDown()
                check(releasePublication.await(10, TimeUnit.SECONDS)) {
                    "Recovery fixture publication was not released"
                }
            }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { f.action()() }
            try {
                assertTrue(publicationEntered.await(10, TimeUnit.SECONDS))
                assertNotSame(mainThread, publicationThread.get())
                assertTrue(f.security.state.value.inFlight)
                assertTrue(f.security.state.value.recoveryRequired)
                assertSame(mainThread, Thread.currentThread())
                f.controller.windowFocusChanged(false)
                assertFalse(f.activity.hasWindowFocus())
                if (background) { f.controller.pause().stop(); f.epoch++ }
            } finally {
                // Even a failed assertion releases and joins the actual owned
                // operation before Fixture.close cancels its Security handle.
                releasePublication.countDown()
                withContext(NonCancellable) { pending.join() }
            }
            pending.await()
            assertEquals(1, f.verifier.removals)
            assertFalse(f.security.state.value.enabled)
            assertFalse(f.security.state.value.recoveryRequired)
            assertFalse(f.activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(f.activity).resultCode)
        }
    }

    @Test fun `the native return callback cannot reset or close an unrelated ordinary Security page`() = runTest {
        Fixture(this, nativeFlow = false).use { f ->
            f.action()()
            assertEquals(0, f.verifier.removals)
            assertTrue(f.security.state.value.recoveryRequired)
            assertFalse(f.activity.isFinishing)
        }
    }

    private class Fixture(scope: TestScope, nativeFlow: Boolean = true) : AutoCloseable {
        val controller: ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java,
            Intent().apply { if (nativeFlow) putExtra(MainActivity.NATIVE_FLOW_ROUTE, "security") })
            .setup().visible().windowFocusChanged(true)
        val activity: MainActivity = controller.get()
        var generation = 7L
        var epoch = 0L
        val verifier = BrokenVerifier()
        private val preferences = object : SecurityPreferencesPort {
            var stored = SecurityPreferences(enabled = true)
            override fun load() = stored
            override fun save(preferences: SecurityPreferences) { stored = preferences }
        }
        val security = SecurityOwner(verifier, preferences, scope)
        init { if (!nativeFlow) navigate(ProductNavigation.settings.open(ProductDestination.Security)) }
        fun action() = activity.securityRecoveryResetAction(generation, epoch, { epoch }, { generation },
            { expected -> security.state.value.takeIf { expected == generation } }, security::resetAfterRecoveryFailure)
        fun navigate(navigation: ProductNavigation) {
            MainActivity::class.java.getDeclaredMethod("navigate", ProductNavigation::class.java)
                .apply { isAccessible = true }.invoke(activity, navigation)
        }
        override fun close() { security.close(); controller.close() }
    }

    private class BrokenVerifier : PasscodeVerifierPort {
        var broken = true
        var removeSucceeds = true
        var removals = 0
        var onRemove: () -> Unit = {}
        override fun availability() = PasscodeVerifierAvailability.Available
        override fun configure(code: String) = false
        override fun verify(code: String) = PasscodeVerification.Unavailable
        override fun credentialState() = if (broken) PasscodeCredentialState.Unavailable else PasscodeCredentialState.Absent
        override fun remove(): Boolean {
            removals++; onRemove()
            if (removeSucceeds) broken = false
            return removeSucceeds
        }
    }
}
