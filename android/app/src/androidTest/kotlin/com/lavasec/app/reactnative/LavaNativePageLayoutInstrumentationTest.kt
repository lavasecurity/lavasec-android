package com.lavasec.app.reactnative

import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.facebook.react.runtime.ReactSurfaceView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.views.view.ReactViewGroup
import com.lavasec.app.R
import com.lavasec.app.reactnative.components.ForegroundProductAdmissionPort
import com.lavasec.app.reactnative.components.LavaNativePageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native children under RN's actual layout-stopping parent; no replacement product owner. */
@RunWith(AndroidJUnit4::class)
class LavaNativePageLayoutInstrumentationTest {
    @Test fun preAttachmentQaHostRendersActualChildrenAndUsesLatestYogaFootprint() {
        withNativeHost(qaBeforeAttachment = true) { journey, fixture ->
            journey.instrumentation.runOnMainSync {
                assertEquals(0, fixture.content.width)
                assertEquals(0, fixture.surface.width)
                assertFalse("Pre-attachment measurement must not attach the shared surface to a window", fixture.surface.isAttachedToWindow)
                fixture.attach()
            }
            awaitRendered(journey, fixture)
            journey.instrumentation.runOnMainSync {
                // Queue native work first, then deliver a newer Fabric footprint in the same turn.
                fixture.host.requestLayout()
                fixture.size(fixture.width - 24, fixture.height - 48)
            }
            awaitRendered(journey, fixture)
            val searchLabel = journey.context.getString(R.string.device_qa_search_device_qa)
            journey.dumpEditableFieldDiagnostics(searchLabel, "native-layout-pre-attachment-search")
            journey.setText(searchLabel, "DNS Leak Canary")
            journey.waitLabel(journey.context.getString(R.string.device_qa_dns_leak_canary))
            println("LAVA_UI_NATIVE_QA_LAYOUT case=pre-attachment rendered=real-ReactSurface search=actual-AX latest-footprint=true")
        }
    }

    @Test fun laterQaMountAndQueuedLayoutRespectBlurRecycleDetachAndDisposal() {
        withNativeHost(qaBeforeAttachment = false) { journey, fixture ->
            journey.instrumentation.runOnMainSync {
                fixture.attach()
                assertEquals(0, fixture.content.childCount)
                fixture.host.applyPage("phoneQA")
                fixture.host.applyFocused(true)
            }
            awaitRendered(journey, fixture)
            val searchLabel = journey.context.getString(R.string.device_qa_search_device_qa)
            journey.dumpEditableFieldDiagnostics(searchLabel, "native-layout-later-mount-search")
            journey.setText(searchLabel, "DNS Leak Canary")
            journey.waitLabel(journey.context.getString(R.string.device_qa_dns_leak_canary))
            journey.instrumentation.runOnMainSync {
                fixture.host.requestLayout()
                fixture.host.applyFocused(false)
                fixture.assertDenied()
            }
            awaitHidden(journey)
            journey.instrumentation.runOnMainSync {
                fixture.assertDenied()
                fixture.host.applyFocused(true)
            }
            awaitRendered(journey, fixture)
            journey.instrumentation.runOnMainSync {
                fixture.host.requestLayout()
                fixture.host.resetForRecycle()
                fixture.assertDenied()
                assertEquals("Recycle removes the old private shared page", 0, fixture.content.childCount)
            }
            awaitHidden(journey)
            journey.instrumentation.runOnMainSync {
                fixture.assertDenied()
                fixture.detach()
                fixture.host.applyPage("phoneQA")
                fixture.host.applyFocused(true)
                fixture.host.requestLayout()
                fixture.assertDenied()
                fixture.attach()
            }
            awaitRendered(journey, fixture)
            journey.instrumentation.runOnMainSync {
                fixture.host.requestLayout()
                fixture.detach()
                fixture.assertDenied()
                fixture.host.dispose()
            }
            journey.instrumentation.waitForIdleSync()
            journey.instrumentation.runOnMainSync {
                assertFalse(fixture.host.isAttachedToWindow)
                fixture.assertDenied()
            }
            awaitHidden(journey)
            println("LAVA_UI_NATIVE_QA_LAYOUT case=later-page blur=denied recycle=denied reattach=rendered detach-dispose=denied queued-work=settled")
        }
    }

    private fun withNativeHost(qaBeforeAttachment: Boolean, exercise: (ReactEmulatorJourney, Fixture) -> Unit) {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val library = product.currentFilterLibrary()
            val logging = product.currentQaLogging()
            val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            assertFalse("Use the real Free admission, never a paid fixture", plus)
            var fixture: Fixture? = null
            try {
                journey.instrumentation.runOnMainSync {
                    val activity = journey.requireActivity()
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    val themed = requireNotNull(themedContext(root)) { "The actual shipping RN surface must supply its view context" }
                    val parent = ReactViewGroup(themed).apply { setBackgroundColor(Color.WHITE) }
                    val host = LavaNativePageView(themed).apply {
                        bind(ForegroundProductAdmissionPort(product, journey.application)) { activity }
                        if (qaBeforeAttachment) {
                            applyPage("phoneQA")
                            applyFocused(true)
                        }
                    }
                    val mounted = Fixture(root, parent, host, root.width, root.height).also { fixture = it }
                    parent.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    assertTrue(root.width > 96 && root.height > 144)
                    parent.measure(exact(mounted.width), exact(mounted.height))
                    parent.layout(0, 0, mounted.width, mounted.height)
                    // Fabric assigns the host box before window attachment; ReactViewGroup does
                    // not measure/layout it later. Native children must recover without another pass.
                    mounted.size(mounted.width, mounted.height)
                    assertFalse(host.isAttachedToWindow)
                }
                exercise(journey, requireNotNull(fixture))
                assertEquals(library, product.currentFilterLibrary())
                assertEquals(logging, product.currentQaLogging())
                assertEquals(plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            } finally {
                journey.instrumentation.runOnMainSync { fixture?.let { it.detach(); it.host.dispose() } }
            }
        }
    }

    private fun awaitRendered(journey: ReactEmulatorJourney, fixture: Fixture) {
        journey.await("actual QA native children fill the current Fabric host") {
            var rendered = false
            journey.instrumentation.runOnMainSync {
                rendered = fixture.content.isShown && fixture.content.width == fixture.width && fixture.content.height == fixture.height &&
                    fixture.surface.hasActiveReactInstance() && fixture.surface.hasActiveReactContext() && fixture.surface.childCount > 0 && fixture.surface.width == fixture.width && fixture.surface.height == fixture.height
            }
            rendered
        }
        journey.waitLabel(journey.context.getString(R.string.device_qa_search_device_qa))
        journey.instrumentation.runOnMainSync {
            assertEquals(fixture.width, fixture.host.width); assertEquals(fixture.height, fixture.host.height)
            assertTrue(fixture.content.isEnabled)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, fixture.content.importantForAccessibility)
            assertEquals(View.GONE, fixture.blocker.visibility)
            val visible = Rect()
            assertTrue("Actual shared content occupies the visible window", fixture.surface.getGlobalVisibleRect(visible))
            assertTrue(visible.width() > 0 && visible.height() > 0)
            val sharedRoot = fixture.surface.getChildAt(0)
            assertEquals(fixture.width, sharedRoot.width); assertEquals(fixture.height, sharedRoot.height)
            println("LAVA_UI_NATIVE_QA_GEOMETRY host=${fixture.host.width}x${fixture.host.height} content=${fixture.content.width}x${fixture.content.height} surface=${fixture.surface.width}x${fixture.surface.height} sharedRoot=${sharedRoot.width}x${sharedRoot.height} visible=$visible")
        }
    }

    private fun awaitHidden(journey: ReactEmulatorJourney) {
        journey.instrumentation.waitForIdleSync()
        val search = journey.context.getString(R.string.device_qa_search_device_qa)
        journey.await("revoked QA controls disappear from actual accessibility") {
            journey.accessibilityNodes().none { it.isVisibleToUser && (it.text?.toString() == search || it.contentDescription?.toString() == search) }
        }
    }

    private fun themedContext(view: View): ThemedReactContext? {
        (view.context as? ThemedReactContext)?.let { return it }
        if (view is ViewGroup) for (index in 0 until view.childCount) themedContext(view.getChildAt(index))?.let { return it }
        return null
    }

    private class Fixture(val root: ViewGroup, val parent: ReactViewGroup, val host: LavaNativePageView, var width: Int, var height: Int) {
        val content get() = host.getChildAt(0) as ViewGroup
        val surface get() = content.getChildAt(0) as ReactSurfaceView
        val blocker get() = host.getChildAt(1)
        fun attach() { root.addView(parent, FrameLayout.LayoutParams(width, height)) }
        fun detach() { root.removeView(parent) }
        fun size(nextWidth: Int, nextHeight: Int) {
            width = nextWidth; height = nextHeight
            host.measure(exact(width), exact(height)); host.layout(0, 0, width, height)
        }
        fun assertDenied() {
            assertFalse(content.isEnabled)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, content.importantForAccessibility)
            assertEquals(View.VISIBLE, blocker.visibility)
            assertTrue(blocker.isEnabled)
            assertEquals(host.width, blocker.width); assertEquals(host.height, blocker.height)
            assertFalse(host.requestBack()); assertFalse(host.requestNavigate("VPNChaining"))
        }
    }

    private companion object {
        fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }
}
