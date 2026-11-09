package com.lavasec.app.reactnative

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.facebook.react.ReactDelegate
import com.facebook.react.ReactHost
import com.facebook.react.interfaces.fabric.ReactSurface
import com.facebook.react.runtime.internal.bolts.Task
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], application = Application::class)
class NativeMeasuredSurfaceStartTest {
    @Test fun `delegate installation starts its registered measured surface once and preserves the later native cover`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val starts = mutableListOf<Pair<Int, Int>>()
        val surface = object : ReactSurface {
            override val surfaceID = 1
            override val moduleName = LavaReactActivity.COMPONENT_NAME
            override val isRunning get() = starts.isNotEmpty()
            override val view = root
            override val context = activity
            override fun start(): Task<Void> {
                assertTrue(root.isAttachedToWindow)
                starts += root.measuredWidth to root.measuredHeight
                return Task.forResult(null)
            }
            override fun stop(): Task<Void> = Task.forResult(null)
            override fun prerender(): Task<Void> = error("No prerender before native measurement")
            override fun clear() = Unit
            override fun detach() = Unit
        }
        val props = Bundle().apply { putString(LavaReactActivity.NATIVE_HOST_ID_KEY, "native-host") }
        var created = 0
        val host = Proxy.newProxyInstance(ReactHost::class.java.classLoader, arrayOf(ReactHost::class.java)) { _, method, args ->
            check(method.name == "createSurface") { "Unexpected host call: ${method.name}" }
            created++
            assertSame(activity, args!![0]); assertEquals(surface.moduleName, args[1]); assertSame(props, args[2])
            surface
        } as ReactHost
        val delegate = ReactDelegate(activity, host, surface.moduleName, props)
        val ownership = ReactDelegate::class.java.getDeclaredField("reactSurface").apply { isAccessible = true }
        var completions = 0
        val pending = NativeMeasuredSurfaceStart.install(activity, host, surface.moduleName, props,
            registerSurface = { delegate.setReactSurface(it); assertFalse(root.isAttachedToWindow) },
            isCurrent = { true }, onStarted = { completions++ })
        assertSame(surface, ownership.get(delegate))
        assertTrue(starts.isEmpty())
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val cover = View(activity)
        content.addView(cover)
        // The actual privacy owner conceals with INVISIBLE, retaining native measurement.
        root.visibility = View.INVISIBLE
        measure(root, 320, 616); root.viewTreeObserver.dispatchOnPreDraw()
        root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(1, created); assertEquals(1, completions)
        assertEquals(listOf(320 to 616), starts)
        assertSame(surface, ownership.get(delegate))
        assertEquals(2, content.childCount)
        assertSame(root, content.getChildAt(0)); assertSame(cover, content.getChildAt(1))
        pending.close(); controller.pause().stop().destroy()
    }

    private fun measure(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    @Test fun `first render receives measured content bounds and subsequent resizing never restarts it`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val starts = mutableListOf<Pair<Int, Int>>()
        val pending = NativeMeasuredSurfaceStart(root, { true }) { starts += root.measuredWidth to root.measuredHeight }
        // Construction and even a speculative traversal while detached cannot start JS.
        measure(root, 320, 640); root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(starts.isEmpty())
        activity.setContentView(root)
        assertTrue(root.isAttachedToWindow)
        measure(root, 0, 640); root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(starts.isEmpty())
        measure(root, 320, 616); root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(listOf(320 to 616), starts)
        measure(root, 480, 320); root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(listOf(320 to 616), starts)
        pending.close(); controller.pause().stop().destroy()
    }

    @Test fun `pending native pane resize is measured before the first render`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val starts = mutableListOf<Pair<Int, Int>>()
        val pending = NativeMeasuredSurfaceStart(root, { true }) { starts += root.width to root.height }
        activity.setContentView(root)
        measure(root, 640, 640)
        root.requestLayout(); root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(starts.isEmpty())
        measure(root, 300, 616); root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(listOf(300 to 616), starts)
        pending.close(); controller.pause().stop().destroy()
    }

    @Test fun `a retired host cannot start later and cannot be revived by another layout`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        var current = true; var starts = 0
        val pending = NativeMeasuredSurfaceStart(root, { current }) { starts++ }
        activity.setContentView(root); measure(root, 320, 616)
        current = false; root.viewTreeObserver.dispatchOnPreDraw()
        current = true; root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(0, starts)
        pending.close(); controller.pause().stop().destroy()
    }

    @Test fun `destruction before attachment removes the pending start`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        var starts = 0
        val pending = NativeMeasuredSurfaceStart(root, { true }) { starts++ }
        pending.close(); pending.close()
        activity.setContentView(root); measure(root, 320, 616)
        root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(0, starts)
        controller.pause().stop().destroy()
    }
}
