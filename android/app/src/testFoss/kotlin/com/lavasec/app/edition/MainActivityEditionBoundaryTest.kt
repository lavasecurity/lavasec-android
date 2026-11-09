package com.lavasec.app.edition

import android.content.Intent
import android.os.Bundle
import com.lavasec.app.MainActivity
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
import com.lavasec.app.reactnative.LavaReactActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class MainActivityEditionBoundaryTest {
    @Test fun `legacy native flow extras cannot expose FOSS commercial or native report screens`() {
        for (flow in listOf("settings", "account", "plus", "feedback")) {
            val controller = Robolectric.buildActivity(MainActivity::class.java,
                Intent().putExtra(MainActivity.NATIVE_FLOW_ROUTE, flow)).create()
            val source = controller.get()
            val launched = checkNotNull(shadowOf(source).nextStartedActivity)
            assertTrue(source.isFinishing)
            assertEquals(LavaReactActivity::class.java.name, launched.component?.className)
            assertNull(launched.data)
            assertFalse(launched.hasExtra(MainActivity.NATIVE_FLOW_ROUTE))
            assertTrue(launched.hasExtra(LavaReactActivity.DEEP_LINK_ENTRY_EXTRA))
            controller.close()
        }
    }

    @Test fun `restored native commercial and report paths return to FOSS Settings before rendering`() {
        for (destination in listOf(ProductDestination.Account, ProductDestination.Upgrade, ProductDestination.BugReport)) {
            val saved = Bundle().apply {
                putStringArrayList("product_navigation_path", ArrayList(ProductNavigation.settings.open(destination).save()))
            }
            val controller = Robolectric.buildActivity(MainActivity::class.java).create(saved)
            val source = controller.get()
            val launched = checkNotNull(shadowOf(source).nextStartedActivity)
            assertTrue(source.isFinishing)
            assertEquals(LavaReactActivity::class.java.name, launched.component?.className)
            assertNull(launched.data)
            assertTrue(launched.hasExtra(LavaReactActivity.DEEP_LINK_ENTRY_EXTRA))
            controller.close()
        }
    }
}
