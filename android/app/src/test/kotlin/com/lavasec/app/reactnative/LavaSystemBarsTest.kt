package com.lavasec.app.reactnative

import android.app.Activity
import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lavasec.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34, 36], application = Application::class, qualifiers = "notnight")
class LavaSystemBarsTest {
    @Suppress("DEPRECATION")
    @Test fun `retained window updates system bar surfaces and icon contrast in both directions`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controller = WindowInsetsControllerCompat(host.window, host.window.decorView)
        for (dark in listOf(false, true, false)) {
            RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
            host.applyLavaSystemBars()
            val surface = ContextCompat.getColor(host, R.color.lava_rn_groupedbackground)
            assertEquals(surface, (host.window.decorView.background as ColorDrawable).color)
            assertEquals(!dark, controller.isAppearanceLightStatusBars)
            assertEquals(!dark, controller.isAppearanceLightNavigationBars)
            if (Build.VERSION.SDK_INT < 35) {
                assertEquals(surface, host.window.statusBarColor)
                assertEquals(ContextCompat.getColor(host, R.color.lava_rn_cardbackground), host.window.navigationBarColor)
            }
        }
        host.finish()
    }
}
