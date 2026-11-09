package com.lavasec.app.reactnative

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import com.facebook.react.ViewManagerOnDemandReactPackage
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.shell.MainReactPackage
import com.facebook.react.uimanager.DisplayMetricsHolder
import com.facebook.react.uimanager.PointerEvents
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.views.text.ReactTextView
import com.facebook.react.views.view.ReactViewGroup
import com.facebook.react.views.view.ReactViewManager
import com.lavasec.app.LavasecApplication
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual Android transition custody and RN managers; no mirrored transfer predicate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], application = Application::class)
class NativeReactViewManagerTest {
    private var originalFlags: Pair<Any, Any>? = null

    @Before fun useActualRNLocalFeatureFlagAccessor() {
        // RN explicitly provides this testing seam for JVM tests without JNI.
        // Kotlin marks its JVM-public API internal; use the real local accessor
        // with unchanged upstream defaults, never a substitute flag predicate.
        val flags = com.facebook.react.internal.featureflags.ReactNativeFeatureFlags::class.java
        val provider = flags.getDeclaredField("accessorProvider").apply { isAccessible = true }
        val accessor = flags.getDeclaredField("accessor").apply { isAccessible = true }
        originalFlags = checkNotNull(provider.get(null)) to checkNotNull(accessor.get(null))
        val constructor = Class.forName(
            "com.facebook.react.internal.featureflags.ReactNativeFeatureFlagsLocalAccessor"
        ).getDeclaredConstructor().apply { isAccessible = true }
        val install = flags.declaredMethods.single { it.name.startsWith("setAccessorProvider") }
        install.invoke(com.facebook.react.internal.featureflags.ReactNativeFeatureFlags, { constructor.newInstance() })
    }

    @After fun restoreExactRNFeatureFlagAccessor() {
        val previous = originalFlags ?: return
        val flags = com.facebook.react.internal.featureflags.ReactNativeFeatureFlags::class.java
        flags.getDeclaredField("accessorProvider").apply { isAccessible = true }.set(null, previous.first)
        flags.getDeclaredField("accessor").apply { isAccessible = true }.set(null, previous.second)
        originalFlags = null
    }

    @Suppress("DEPRECATION")
    private fun context(surface: Int = 1): ThemedReactContext {
        val application = RuntimeEnvironment.getApplication()
        DisplayMetricsHolder.initDisplayMetrics(application)
        val react = object : BridgeReactContext(application) {
            override fun isBridgeless(): Boolean = true
        }
        return ThemedReactContext(react, application, LavaReactActivity.COMPONENT_NAME, surface)
    }

    @Test fun `debug root measurement trace preserves requested geometry and remains bounded`() {
        val manager = NativeReactViewManager()
        val root = manager.createViewInstance(context()) as NativeMeasuredReactView
        root.id = 12
        root.setTag(com.facebook.react.R.id.react_test_id, "lava-window-viewport")
        for (width in 320..329) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, 640)
            assertEquals(width, root.measuredWidth)
            assertEquals(width, root.width)
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 0, 640)
        assertEquals("Diagnostics must not repair or conceal a bad measurement", 0, root.width)
        assertEquals(0, root.measuredWidth)
        val trace = root.measurementTrace()
        assertEquals(12, trace.length())
        val last = trace.getJSONObject(trace.length() - 1)
        assertEquals("layout", last.getString("stage"))
        assertEquals(0, last.getInt("requestedWidth"))
        assertEquals(640, last.getInt("requestedHeight"))
        assertTrue(last.getJSONArray("callers").length() in 1..12)
        root.id = 14
        assertEquals("A recycled identity never exposes the former root's history", 0, root.measurementTrace().length())
        root.layout(0, 0, 300, 600)
        assertEquals(1, root.measurementTrace().length())
        manager.onDropViewInstance(root)
        assertEquals(0, root.measurementTrace().length())
    }

    @Test fun `ordinary React views do not accumulate root measurement traces`() {
        val root = NativeReactViewManager().createViewInstance(context()) as NativeMeasuredReactView
        root.id = 16
        root.setTag(com.facebook.react.R.id.react_test_id, "private-field")
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 320, 640)
        assertEquals(0, root.measurementTrace().length())
        assertEquals(320, root.width)
        assertEquals(640, root.height)
    }

    private data class Scene(
        val former: ReactViewGroup,
        val target: ReactViewGroup,
        val child: ReactTextView,
    )

    private fun retained(context: ThemedReactContext = context()): Scene {
        val original = ReactViewManager()
        val former = original.createViewInstance(context).apply { id = 2 }
        val target = original.createViewInstance(context).apply { id = 4 }
        val child = ReactTextView(context).apply { id = 6 }
        original.addView(former, child, 0)
        former.startViewTransition(child)
        original.removeViewAt(former, 0)
        assertEquals("The manager has actually removed the logical child", 0, original.getChildCount(former))
        assertEquals(-1, former.indexOfChild(child))
        assertSame("Android still retains the disappearing child's physical parent", former, child.parent)
        // SurfaceMountingManager's existing evasive remove cannot detach an
        // already-removed child; this is the actual pre-insertion state.
        former.removeView(child)
        assertSame(former, child.parent)
        return Scene(former, target, child)
    }

    private fun rejects(action: () -> Unit) {
        try {
            action()
            fail("An invalid live-parent insertion must retain RN's exception")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("already has a parent"))
        }
    }

    @Test fun `original RN insertion fails for an actually removed transitioning text child`() {
        val scene = retained()
        rejects { ReactViewManager().addView(scene.target, scene.child, 0) }
        assertSame(scene.former, scene.child.parent)
        assertEquals(0, scene.target.childCount)
        scene.former.endViewTransition(scene.child)
        assertNull(scene.child.parent)
    }

    @Test fun `actual insertion releases only the moved child and late old completion cannot steal it`() {
        val scene = retained()
        val sibling = ReactTextView(scene.former.context).apply { id = 8 }
        val original = ReactViewManager()
        original.addView(scene.former, sibling, 0)
        scene.former.startViewTransition(sibling)
        original.removeViewAt(scene.former, 0)
        assertSame(scene.former, sibling.parent)
        val prefix = View(scene.target.context).apply { id = 10 }
        original.addView(scene.target, prefix, 0)
        NativeReactViewManager().addView(scene.target, scene.child, 1)
        assertSame(scene.target, scene.child.parent)
        assertEquals(-1, scene.former.indexOfChild(scene.child))
        assertEquals(2, scene.target.childCount)
        assertSame(prefix, scene.target.getChildAt(0))
        assertSame(scene.child, scene.target.getChildAt(1))
        assertSame("An unrelated disappearing child keeps its own transition", scene.former, sibling.parent)
        scene.former.endViewTransition(scene.child)
        assertSame("The outgoing screen's late end cannot take the successor's child", scene.target, scene.child.parent)
        assertSame(scene.child, scene.target.getChildAt(1))
        scene.former.endViewTransition(sibling)
        assertNull(sibling.parent)
    }

    @Test fun `ordinary props ordering removal and clipped insertion use the inherited manager`() {
        val context = context()
        val manager = NativeReactViewManager()
        assertEquals(ReactViewManager.REACT_CLASS, manager.name)
        assertEquals(ReactViewManager().nativeProps, manager.nativeProps)
        val parent = manager.createViewInstance(context)
        manager.setAccessible(parent, true)
        manager.setPointerEvents(parent, "none")
        assertTrue(parent.isFocusable)
        assertEquals(PointerEvents.NONE, parent.pointerEvents)
        manager.setOpacity(parent, 0.4f)
        assertEquals(0.4f, parent.alpha, 0f)
        val first = ReactTextView(context)
        val second = View(context)
        manager.addView(parent, first, 0)
        manager.addView(parent, second, 0)
        assertSame(second, manager.getChildAt(parent, 0))
        assertSame(first, manager.getChildAt(parent, 1))
        manager.removeViewAt(parent, 0)
        assertNull(second.parent)
        assertSame(first, manager.getChildAt(parent, 0))
        manager.removeAllViews(parent)
        assertNull(first.parent)
        assertEquals(0, manager.getChildCount(parent))
        manager.setRemoveClippedSubviews(parent, true)
        manager.addView(parent, first, 0)
        assertEquals(1, manager.getChildCount(parent))
        assertSame(first, manager.getChildAt(parent, 0))
        manager.removeViewAt(parent, 0)
        assertEquals(0, manager.getChildCount(parent))
        manager.setRemoveClippedSubviews(parent, false)
    }

    @Test fun `live indexed and same-parent insertions do not release transition custody`() {
        val context = context()
        val original = ReactViewManager()
        val manager = NativeReactViewManager()
        val former = original.createViewInstance(context).apply { id = 2 }
        val target = original.createViewInstance(context).apply { id = 4 }
        val child = ReactTextView(context).apply { id = 6 }
        original.addView(former, child, 0)
        former.startViewTransition(child)
        rejects { manager.addView(target, child, 0) }
        assertSame(former, child.parent)
        assertSame(child, former.getChildAt(0))
        original.removeViewAt(former, 0)
        assertSame("The rejected insertion did not prematurely end the live transition", former, child.parent)
        rejects { manager.addView(former, child, 0) }
        assertSame(former, child.parent)
        former.endViewTransition(child)
        assertNull(child.parent)
    }

    @Test fun `a physically absent clipped logical child is never mistaken for removed custody`() {
        val context = context()
        val original = ReactViewManager()
        val former = original.createViewInstance(context).apply { id = 2 }
        val target = original.createViewInstance(context).apply { id = 4 }
        val child = ReactTextView(context).apply { id = 6 }
        original.addView(former, child, 0)
        former.startViewTransition(child)
        // This unattached group's actual zero clipping rect hides the child,
        // while the clipping manager's logical array still owns it.
        original.setRemoveClippedSubviews(former, true)
        assertTrue(former.removeClippedSubviews)
        assertEquals(-1, former.indexOfChild(child))
        assertEquals(1, original.getChildCount(former))
        assertSame(child, original.getChildAt(former, 0))
        assertSame(former, child.parent)
        rejects { NativeReactViewManager().addView(target, child, 0) }
        assertSame(former, child.parent)
        assertSame(child, original.getChildAt(former, 0))
        original.removeViewAt(former, 0)
        former.endViewTransition(child)
        original.setRemoveClippedSubviews(former, false)
    }

    @Test fun `other surface contexts and nonReact parents retain the original refusal`() {
        val scene = retained()
        val otherSurface = ReactViewManager().createViewInstance(context(2))
        rejects { NativeReactViewManager().addView(otherSurface, scene.child, 0) }
        assertSame(scene.former, scene.child.parent)
        assertEquals(0, otherSurface.childCount)
        scene.former.endViewTransition(scene.child)
        val context = context()
        val former = FrameLayout(context)
        val child = ReactTextView(context).apply { id = 6 }
        former.addView(child)
        former.startViewTransition(child)
        former.removeView(child)
        assertEquals(-1, former.indexOfChild(child))
        assertSame(former, child.parent)
        rejects { NativeReactViewManager().addView(ReactViewManager().createViewInstance(context), child, 0) }
        assertSame(former, child.parent)
        former.endViewTransition(child)
        assertNull(child.parent)
    }

    @Test fun `invalid target index cannot release the old transitioning child`() {
        val scene = retained()
        try {
            NativeReactViewManager().addView(scene.target, scene.child, 3)
            fail("The original invalid insertion must still fail")
        } catch (_: IllegalStateException) {
            // Android rejects the retained parent before examining its index.
        }
        assertSame(scene.former, scene.child.parent)
        assertEquals(0, scene.target.childCount)
        scene.former.endViewTransition(scene.child)
    }

    @Test fun `actual application packages select the adapter before the default on demand RCTView`() {
        val context = context().reactApplicationContext
        val packages = LavasecApplication().nativeReactPackages()
        assertTrue(packages.first() is NativeReactViewPackage)
        assertEquals(1, packages.count { it is NativeReactViewPackage })
        assertEquals(1, packages.count { it is LavaAppPackage })
        val adapters = packages.filterIsInstance<ViewManagerOnDemandReactPackage>()
        val adapter = adapters.first { ReactViewManager.REACT_CLASS in it.getViewManagerNames(context) }
        val selected = adapter.createViewManager(context, ReactViewManager.REACT_CLASS)
        assertTrue("RN's first on-demand factory is the actual application adapter", selected is NativeReactViewManager)
        assertEquals(ReactViewManager.REACT_CLASS, checkNotNull(selected).name)
        assertEquals(listOf(ReactViewManager.REACT_CLASS), adapter.getViewManagerNames(context).toList())
        assertNull(adapter.createViewManager(context, "RNSScreen"))
        assertTrue("The original core package remains in the shipping list", packages.any { it is MainReactPackage })
        val default = packages.filterIsInstance<MainReactPackage>().single()
        val inherited = default.createViewManager(context, ReactViewManager.REACT_CLASS)
        assertEquals(ReactViewManager::class.java, checkNotNull(inherited).javaClass)
    }
}
