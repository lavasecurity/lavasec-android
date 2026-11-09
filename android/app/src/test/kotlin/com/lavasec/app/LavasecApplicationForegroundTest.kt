package com.lavasec.app

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = DrainingLavasecTestApplication::class)
class LavasecApplicationForegroundTest {
    @Test
    fun `background jobs can obtain configuration after unlock without a foreground activity`() {
        val application = org.robolectric.RuntimeEnvironment.getApplication() as LavasecApplication
        val user = org.robolectric.Shadows.shadowOf(application.getSystemService(android.os.UserManager::class.java))
        user.setUserUnlocked(true)
        val provider: androidx.work.Configuration.Provider = application
        org.junit.Assert.assertNotNull(provider.workManagerConfiguration)
        assertFalse(application.started.value)
    }

    @Test
    fun `work manager configuration remains unavailable before credential unlock`() {
        val application = org.robolectric.RuntimeEnvironment.getApplication() as LavasecApplication
        val user = org.robolectric.Shadows.shadowOf(application.getSystemService(android.os.UserManager::class.java))
        user.setUserUnlocked(false)
        try {
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                application.workManagerConfiguration
            }
        } finally { user.setUserUnlocked(true) }
    }

    /** Both RN and native flows participate in these Application activity callbacks. */
    class ForegroundActivity : Activity()

    @Test
    fun `native handoff keeps foreground until the last visible activity stops`() {
        val first = Robolectric.buildActivity(ForegroundActivity::class.java).create()
        val application = first.get().application as LavasecApplication
        assertFalse(application.started.value)
        first.start().resume()
        assertTrue(application.started.value)
        val second = Robolectric.buildActivity(ForegroundActivity::class.java).create().start().resume()
        first.pause().stop()
        assertTrue(application.started.value)
        second.pause().stop()
        assertFalse(application.started.value)
        first.destroy()
        second.destroy()
    }

    @Test
    fun `activity destruction does not replace the shared product composition`() {
        val first = Robolectric.buildActivity(ForegroundActivity::class.java).create().start().resume()
        val application = first.get().application as LavasecApplication
        val product = application.foregroundProduct
        first.pause().stop().destroy()
        val replacement = Robolectric.buildActivity(ForegroundActivity::class.java).create().start().resume()
        assertSame(product, (replacement.get().application as LavasecApplication).foregroundProduct)
        replacement.pause().stop().destroy()
        product.close()
    }
}
