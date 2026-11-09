package com.lavasec.app.reactnative

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.lavasec.app.R as AppResources
import com.lavasec.app.foundation.preferences.AppearancePreference
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import com.lavasec.app.settings.ProductSettingsStore
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.savedstate.SavedStateRegistryController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Constructor/launch metadata checks require no React runtime or credential graph. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LavaReactActivityHostIdentityTest {
    @Test fun `separate Activities cannot claim each others native viewport identity`() {
        val source = Robolectric.buildActivity(LavaReactActivity::class.java).get()
        val preview = Robolectric.buildActivity(LavaReactActivity::class.java).get()
        assertNotEquals(source.nativeHostID, preview.nativeHostID)
        assertEquals(source.nativeHostID, java.util.UUID.fromString(source.nativeHostID).toString())
        assertTrue(source.belongsToNativeHost(source.nativeHostID))
        assertFalse(source.belongsToNativeHost(preview.nativeHostID))
        assertFalse(preview.belongsToNativeHost(source.nativeHostID))
        assertFalse(preview.belongsToNativeHost(null))
        assertFalse(preview.belongsToNativeHost(""))
    }

    @Test fun `launch props keep the immutable Activity identity across repeated root options`() {
        val host = Robolectric.buildActivity(LavaReactActivity::class.java).get()
        val delegate = com.facebook.react.ReactActivity::class.java.getDeclaredField("mDelegate")
            .apply { isAccessible = true }.get(host)
        val method = delegate.javaClass.getDeclaredMethod("getLaunchOptions").apply { isAccessible = true }
        val first = method.invoke(delegate) as Bundle
        val second = method.invoke(delegate) as Bundle
        assertEquals(host.nativeHostID, first.getString(LavaReactActivity.NATIVE_HOST_ID_KEY))
        assertEquals(first.getString(LavaReactActivity.NATIVE_HOST_ID_KEY), second.getString(LavaReactActivity.NATIVE_HOST_ID_KEY))
        assertNotNull("The existing first-frame appearance bootstrap remains available", first.getString(LavaReactActivity.INITIAL_APPEARANCE_KEY))
    }

    @Test fun `native lifecycle authority is explicitly published for repository IO readers`() {
        assertTrue(Modifier.isVolatile(LavaReactActivity::class.java.getDeclaredField("foreground").modifiers))
        assertTrue(Modifier.isVolatile(LavaReactActivity::class.java.getDeclaredField("nativeHostAlive").modifiers))
    }

    @Test @Config(sdk = [28, 36])
    fun `real Back dispatcher gives only the foreground native owner first refusal then React`() {
        val host = Robolectric.buildActivity(LavaReactActivity::class.java).get()
        var nativeCalls = 0
        var reactCalls = 0
        var consumes = true
        installBackDelegate(host) { reactCalls++; true }
        host.setForegroundBackHandler { nativeCalls++; consumes }
        publishForeground(host, true)
        val lifecycle = startBackDispatcher(host)
        try {
            host.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, nativeCalls)
            assertEquals(0, reactCalls)
            consumes = false
            host.onBackPressedDispatcher.onBackPressed()
            assertEquals(2, nativeCalls)
            assertEquals(1, reactCalls)
            publishForeground(host, false)
            host.onBackPressedDispatcher.onBackPressed()
            assertEquals("A paused owner cannot cancel or alter its retained visit", 2, nativeCalls)
            assertEquals(2, reactCalls)
            publishForeground(host, true)
            consumes = true
            @Suppress("DEPRECATION")
            host.onBackPressed()
            assertEquals("RN's API-28 hardware entry retains the same native-owner priority", 3, nativeCalls)
            assertEquals(2, reactCalls)
        } finally { lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
    }

    @Test @Config(sdk = [28, 36])
    fun `React default Back reaches the actual child once without reentering native or React`() {
        val host = Robolectric.buildActivity(LavaReactActivity::class.java).get()
        var nativeCalls = 0
        var reactCalls = 0
        var childCalls = 0
        var failChild = false
        installBackDelegate(host) { reactCalls++; false }
        host.setForegroundBackHandler { nativeCalls++; false }
        publishForeground(host, true)
        val child = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                childCalls++
                if (failChild) throw IllegalStateException("controlled child refusal")
            }
        }
        host.onBackPressedDispatcher.addCallback(child)
        val lifecycle = startBackDispatcher(host)
        try {
            host.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, nativeCalls)
            assertEquals(1, reactCalls)
            assertEquals(1, childCalls)
            @Suppress("DEPRECATION")
            host.onBackPressed()
            assertEquals("A declined legacy Back dispatches its native owner exactly once", 2, nativeCalls)
            assertEquals("A declined legacy Back dispatches its React event exactly once", 2, reactCalls)
            assertEquals(2, childCalls)
            // This is React's public default-Back callback after a JS refusal.
            host.invokeDefaultOnBackPressed()
            assertEquals(2, nativeCalls)
            assertEquals(2, reactCalls)
            assertEquals(3, childCalls)
            failChild = true
            assertThrows(IllegalStateException::class.java) { host.invokeDefaultOnBackPressed() }
            failChild = false
            host.onBackPressedDispatcher.onBackPressed()
            assertEquals("Default continuation restores the native callback even on failure", 3, nativeCalls)
            assertEquals(3, reactCalls)
            assertEquals(5, childCalls)
        } finally {
            child.remove()
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    @Test @Config(sdk = [28, 36])
    fun `native Back callback follows the real Activity lifecycle and is removed at destruction`() {
        val host = Robolectric.buildActivity(LavaReactActivity::class.java).get()
        var nativeCalls = 0
        var childCalls = 0
        installBackDelegate(host) { error("A consumed native Back cannot reach React") }
        host.setForegroundBackHandler { nativeCalls++; true }
        publishForeground(host, true)
        val child = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { childCalls++ }
        }
        host.onBackPressedDispatcher.addCallback(child)
        val lifecycle = startBackDispatcher(host)
        try {
            host.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, nativeCalls)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            host.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, nativeCalls)
            assertEquals(1, childCalls)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            host.onBackPressedDispatcher.onBackPressed()
            assertEquals(2, nativeCalls)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            host.onBackPressedDispatcher.onBackPressed()
            assertEquals(2, nativeCalls)
            assertEquals(2, childCalls)
        } finally {
            child.remove()
            if (lifecycle.currentState != Lifecycle.State.DESTROYED) lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    private fun installBackDelegate(host: LavaReactActivity, onBack: () -> Boolean) {
        val delegate = object : com.facebook.react.ReactActivityDelegate(host, LavaReactActivity.COMPONENT_NAME) {
            override fun onBackPressed(): Boolean = host.routeReactBack(onBack)
        }
        com.facebook.react.ReactActivity::class.java.getDeclaredField("mDelegate")
            .apply { isAccessible = true }.set(host, delegate)
    }

    private fun publishForeground(host: LavaReactActivity, value: Boolean) {
        LavaReactActivity::class.java.getDeclaredField("foreground").apply { isAccessible = true }.setBoolean(host, value)
    }

    private fun startBackDispatcher(host: LavaReactActivity): LifecycleRegistry {
        // Exercise the exact production registration and real dispatcher/lifecycle
        // without starting a JS engine or borrowing any native product authority.
        // ComponentActivity normally restores this controller in onCreate before
        // Recreator observes ON_CREATE. Initialize that actual saved-state owner,
        // while this fixture deliberately does not boot a JS engine/product graph.
        val savedState = ComponentActivity::class.java.getDeclaredField("savedStateRegistryController")
            .apply { isAccessible = true }.get(host) as SavedStateRegistryController
        assertFalse(host.savedStateRegistry.isRestored)
        savedState.performRestore(null)
        assertTrue(host.savedStateRegistry.isRestored)
        LavaReactActivity::class.java.getDeclaredMethod("registerForegroundBackCallback")
            .apply { isAccessible = true }.invoke(host)
        return (host.lifecycle as LifecycleRegistry).apply {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
    }

    @Test fun `confirmed finish revokes a captured host on already running IO before destroy`() {
        val activity = Robolectric.buildActivity(LavaReactActivity::class.java).get()
        val witness = NativeVisitHost(activity, activity.nativeHostID) { activity.nativeHostAlive }
        val started = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val io = Executors.newSingleThreadExecutor()
        try {
            assertTrue(witness.retains(null))
            val reading = io.submit<Boolean> {
                assertTrue(activity.nativeHostAlive)
                started.countDown()
                while (activity.nativeHostAlive && !stop.get()) Thread.yield()
                activity.nativeHostAlive
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            // This is the real Android Activity.finish path. No post-finish latch
            // releases the reader, and no onDestroy callback has been delivered.
            activity.finish()
            assertTrue("The framework must confirm this finish request", activity.isFinishing)
            assertFalse(activity.isDestroyed)
            assertFalse(reading.get(5, TimeUnit.SECONDS))
            assertFalse(activity.foreground)
            assertFalse(witness.retains(null))
            assertFalse(witness.matches(witness))
        } finally {
            stop.set(true)
            io.shutdownNow()
        }
    }

    @Test fun `confirmed finish and remove task retires native authority before destruction`() {
        val activity = Robolectric.buildActivity(LavaReactActivity::class.java).get()
        val witness = NativeVisitHost(activity, activity.nativeHostID) { activity.nativeHostAlive }
        activity.finishAndRemoveTask()
        assertTrue("The framework must confirm task removal", activity.isFinishing)
        assertFalse(activity.isDestroyed)
        assertFalse(activity.nativeHostAlive)
        assertFalse(activity.foreground)
        assertFalse(witness.retains(null))
    }

    @Test @Config(sdk = [28, 36], qualifiers = "notnight")
    fun `stored light and dark select their resource context before Activity creation`() {
        assertAppearanceAtAttachment(AppearancePreference.LIGHT, AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_NO, "#ff263a2b")
        assertAppearanceAtAttachment(AppearancePreference.DARK, AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_YES, "#fff6f1e5")
    }

    @Test @Config(sdk = [28, 36], qualifiers = "notnight")
    fun `stored system inherits a day system before creation despite a dark process default`() {
        assertAppearanceAtAttachment(AppearancePreference.SYSTEM, AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, Configuration.UI_MODE_NIGHT_NO, "#ff263a2b")
    }

    @Test @Config(sdk = [28, 36], qualifiers = "night")
    fun `stored system inherits a night system before creation despite a light process default`() {
        assertAppearanceAtAttachment(AppearancePreference.SYSTEM, AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, Configuration.UI_MODE_NIGHT_YES, "#fff6f1e5")
    }

    private fun assertAppearanceAtAttachment(preference: AppearancePreference, processMode: Int,
        expectedDefault: Int, expectedNight: Int, expectedInk: String) {
        val application = RuntimeEnvironment.getApplication()
        val store = ProductSettingsStore(application)
        val originalPreferences = store.loadPreferences()
        val originalDefault = AppCompatDelegate.getDefaultNightMode()
        val requested = originalPreferences.set(ProductPreferenceKey.APPEARANCE,
            PreferenceValue.Text(preference.wire)).getOrThrow()
        try {
            assertTrue("The actual presentation preference must be durably stored", store.persistPreferences(requested))
            AppCompatDelegate.setDefaultNightMode(processMode)
            assertEquals(processMode, AppCompatDelegate.getDefaultNightMode())
            val host = LavaReactActivity()
            // This is the real platform wrapper fast path in AppCompat.attachBaseContext2.
            // Robolectric deliberately skips AppCompat's different-context branch for a plain
            // ContextImpl; use its supported wrapper path without changing library/static flags.
            val base = ContextThemeWrapper(application, androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
            AppCompatActivity::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(host, base)
            assertSame("The actual AppCompat override is applied to the supplied native context", base, host.baseContext)
            assertEquals("The durable mode wins before onCreate can run", expectedDefault, AppCompatDelegate.getDefaultNightMode())
            assertEquals(expectedNight, host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            assertEquals(Color.parseColor(expectedInk), host.getColor(AppResources.color.lava_rn_primarytext))
            assertEquals("Later owner-confirmed AppearanceModule changes retain global authority",
                AppCompatDelegate.MODE_NIGHT_UNSPECIFIED, host.delegate.localNightMode)
            assertEquals(Lifecycle.State.INITIALIZED, host.lifecycle.currentState)
            assertEquals(requested, store.loadPreferences())
        } finally {
            assertTrue(store.persistPreferences(originalPreferences))
            AppCompatDelegate.setDefaultNightMode(originalDefault)
        }
    }
}
