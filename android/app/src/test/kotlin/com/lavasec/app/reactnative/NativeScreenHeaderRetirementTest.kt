package com.lavasec.app.reactnative

import android.app.Application
import android.os.Bundle
import android.view.Choreographer
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import com.facebook.react.internal.ChoreographerProvider
import com.facebook.react.modules.core.ReactChoreographer
import com.facebook.react.uimanager.DisplayMetricsHolder
import com.facebook.react.uimanager.ThemedReactContext
import com.swmansion.rnscreens.Screen
import com.swmansion.rnscreens.ScreenContainer
import com.swmansion.rnscreens.ScreenStack
import com.swmansion.rnscreens.ScreenStackFragment
import com.swmansion.rnscreens.ScreenStackHeaderConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Freezes upstream #4498's real transient state: the Screen has left ScreenStack,
 * while its actual Fragment/Activity and attached-header flag remain live.
 * Calls the shipped Header implementation, not a mirror of its admission guard.
 * A real Fabric transition is still required in the packaged device cohort.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeScreenHeaderRetirementTest {
    class HeaderActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar)
            super.onCreate(savedInstanceState)
        }
    }

    private data class Row(val screen: Screen, val fragment: ScreenStackFragment, val header: ScreenStackHeaderConfig)

    @Suppress("DEPRECATION")
    private fun fixture(run: (HeaderActivity, ThemedReactContext, ScreenStack, Row) -> Unit) {
        val controller = Robolectric.buildActivity(HeaderActivity::class.java).setup()
        val activity = controller.get()
        DisplayMetricsHolder.initDisplayMetrics(activity)
        // Use the actual Android frame queue. No JS bridge is needed to inspect
        // a native toolbar; native event delivery is absent in this unit fixture.
        ReactChoreographer.initialize(object : ChoreographerProvider {
            override fun getChoreographer(): ChoreographerProvider.Choreographer {
                val delegate = Choreographer.getInstance()
                return object : ChoreographerProvider.Choreographer {
                    override fun postFrameCallback(callback: Choreographer.FrameCallback) = delegate.postFrameCallback(callback)
                    override fun removeFrameCallback(callback: Choreographer.FrameCallback) = delegate.removeFrameCallback(callback)
                }
            }
        })
        val applicationContext = NativeScreensTestContext(activity.application)
        applicationContext.onHostResume(activity)
        val context = ThemedReactContext(applicationContext, activity, LavaReactActivity.COMPONENT_NAME, 1)
        val stack = ScreenStack(context)
        try {
            val root = row(activity, context, stack, "Root", 0)
            run(activity, context, stack, root)
        } finally {
            // Release the real fragments and their retained native toolbars.
            try { controller.pause().stop().destroy() }
            finally { applicationContext.onHostDestroy() }
        }
    }

    private fun row(activity: HeaderActivity, context: ThemedReactContext, stack: ScreenStack, title: String, index: Int): Row {
        val screen = Screen(context)
        val header = ScreenStackHeaderConfig(context)
        header.setTitle(title)
        screen.addView(header)
        stack.addScreen(screen, index)
        val fragment = screen.fragment as ScreenStackFragment
        activity.supportFragmentManager.beginTransaction().add(fragment, "row-$index").commitNow()
        assertSame(activity, fragment.activity)
        assertSame(screen, header.parent)
        // Keep actual stack membership/back calculation and a real fragment host;
        // freeze only the two lifecycle fields a delayed Fabric update observes.
        ReflectionHelpers.setField(stack, "topScreenWrapper", fragment)
        ReflectionHelpers.setField(header, "isAttachedToWindow", true)
        header.onUpdate()
        assertEquals(title, activity.supportActionBar?.title?.toString())
        return Row(screen, fragment, header)
    }

    @Test fun `mounted root and child headers retain titles and correct native back affordance`() = fixture { activity, context, stack, root ->
        assertFalse(root.fragment.canNavigateBack())
        assertEquals(0, checkNotNull(activity.supportActionBar).displayOptions and ActionBar.DISPLAY_HOME_AS_UP)
        val child = row(activity, context, stack, "Child", 1)
        assertTrue(child.fragment.canNavigateBack())
        assertTrue(checkNotNull(activity.supportActionBar).displayOptions and ActionBar.DISPLAY_HOME_AS_UP != 0)
        child.header.setTitle("Renamed child")
        child.header.onUpdate()
        assertEquals("Renamed child", activity.supportActionBar?.title?.toString())
    }

    @Test fun `retained detached header cannot mutate live action bar or its fresh successor`() = fixture { activity, context, stack, _ ->
        val child = row(activity, context, stack, "Live child", 1)
        val bar = checkNotNull(activity.supportActionBar)
        val options = bar.displayOptions
        // Actual container removal clears Screen.container before fragment teardown.
        stack.removeScreenAt(1)
        assertNull(child.screen.container)
        assertSame(activity, child.fragment.activity)
        child.header.setTitle("Retired header must not publish")
        child.header.onUpdate()
        assertSame(bar, activity.supportActionBar)
        assertEquals("Live child", bar.title.toString())
        assertEquals(options, bar.displayOptions)
        // Do not soften the Fragment invariant to make the header test pass.
        val rejected = runCatching { child.fragment.canNavigateBack() }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
        assertEquals("ScreenStackFragment added into a non-stack container", rejected?.message)

        // The ordinary next mounted update must still configure a successor.
        val successor = row(activity, context, stack, "Fresh child", 1)
        assertNotSame(child.fragment, successor.fragment)
        assertTrue(successor.fragment.canNavigateBack())
        assertEquals("Fresh child", activity.supportActionBar?.title?.toString())
        val freshBar = checkNotNull(activity.supportActionBar)
        child.header.setTitle("Late retired update")
        child.header.onUpdate()
        assertSame(freshBar, activity.supportActionBar)
        assertEquals("Fresh child", freshBar.title.toString())
        assertTrue(freshBar.displayOptions and ActionBar.DISPLAY_HOME_AS_UP != 0)
    }

    @Test fun `wrong container retained header is refused before any toolbar mutation`() = fixture { activity, _, _, root ->
        val bar = checkNotNull(activity.supportActionBar)
        root.screen.container = ScreenContainer(root.screen.reactContext)
        root.header.setTitle("Mismatched container must not publish")
        root.header.onUpdate()
        assertSame(bar, activity.supportActionBar)
        assertEquals("Root", bar.title.toString())
        assertTrue(runCatching { root.fragment.canNavigateBack() }.exceptionOrNull() is IllegalStateException)
    }
}
