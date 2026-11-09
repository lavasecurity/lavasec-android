package com.lavasec.app.reactnative

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.ReactHost
import com.lavasec.app.LavasecApplication
import com.lavasec.app.MainProductViewModel
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises native bootstrap, the packaged Hermes bundle, and the real JS foreground. */
@RunWith(AndroidJUnit4::class)
class LavaReactActivityLaunchTest {
    @Test
    fun decorationCanBeMeasuredBeforeWindowAttachment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (symbol in listOf("", "shield")) {
                val decoration = LavaDecorationView(instrumentation.targetContext).apply { this.symbol = symbol }
                decoration.measure(
                    View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
                )
                decoration.layout(0, 0, 120, 80)
                assertEquals(120, decoration.measuredWidth)
                assertEquals(80, decoration.measuredHeight)
                assertFalse((decoration.getChildAt(0) as ComposeView).hasComposition)
            }
        }
    }

    @Test
    fun launcherLoadsProtectionOnFirstLaunchAndAfterReopening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LavasecApplication
        val processId = Process.myPid()
        var previousActivity: LavaReactActivity? = null
        var retainedReactHost: ReactHost? = null
        var retainedProduct: MainProductViewModel? = null
        var retainedGraphGeneration: Long? = null
        // The process and React host survive closing the activity. A new surface must subscribe
        // and read the owner again even when the process was already initialized in background.
        repeat(2) { launch ->
            println("LAVA_UI_REACT_LAUNCH attempt=$launch flags=new-task-clear-top")
            val activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, LavaReactActivity::class.java)
                    // MonitoringInstrumentation adds CLEAR_TOP only to the runner's first
                    // launch. A retained task must not consume this creation waiter as an
                    // intent delivery to an existing top Activity after earlier test cases.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            ) as LavaReactActivity
            var failure: Throwable? = null
            try {
                val deadline = SystemClock.elapsedRealtime() + 20_000L
                var loaded = false
                val observed = mutableSetOf<String>()
                while (SystemClock.elapsedRealtime() < deadline) {
                    val text = mutableSetOf<String>()
                    var ownerReady = false
                    instrumentation.runOnMainSync {
                        collectText(activity.window.decorView, text)
                        val generation = application.foregroundProduct.state.value.graphGeneration
                        ownerReady = generation != null && activity.isPresentationReady(generation) &&
                            application.reactHost.currentReactContext?.currentActivity === activity
                    }
                    observed.addAll(text)
                    if (text.containsAll(setOf("Guard", "Settings")) &&
                        text.any(::reportsProtectionState) &&
                        text.none { it.contains("Loading your protection") || it == "Loading Lava" } && ownerReady
                    ) {
                        loaded = true
                        break
                    }
                    SystemClock.sleep(100L)
                }
                assertTrue("Launch $launch did not load protection; observed labels: $observed", loaded)
                instrumentation.runOnMainSync {
                    assertEquals("Reopening must remain in the original application process", processId, Process.myPid())
                    val product = application.foregroundProduct
                    val generation = checkNotNull(product.state.value.graphGeneration)
                    if (launch == 0) {
                        retainedReactHost = application.reactHost
                        retainedProduct = product
                        retainedGraphGeneration = generation
                    } else {
                        assertNotSame("Reopening must create a distinct Activity", previousActivity, activity)
                        assertTrue("The preceding Activity must already be destroyed", checkNotNull(previousActivity).isDestroyed)
                        assertSame("The application React host must survive Activity closure", retainedReactHost, application.reactHost)
                        assertSame("The product owner must survive Activity closure", retainedProduct, product)
                        assertEquals("Reopening must retain the credential graph", retainedGraphGeneration, generation)
                    }
                    assertSame("The actual React surface must belong to the newly launched Activity",
                        activity, application.reactHost.currentReactContext?.currentActivity)
                }
                // Startup continues after the first text frame: native decorations and
                // screen-removal listeners previously crashed during these later frames.
                // Fabric and Compose mount after the first text frame. A configured app may
                // restore its ScrollView below the decorative hero, so return to the top first.
                repeat(12) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    fun scrollTop(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
                        if (node.isScrollable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
                        repeat(node.childCount) { child -> node.getChild(child)?.let { if (scrollTop(it)) return true } }
                        return false
                    }
                    if (root != null) scrollTop(root)
                    SystemClock.sleep(100L)
                }
                val mountDeadline = SystemClock.elapsedRealtime() + 20_000L
                var composed = false
                while (SystemClock.elapsedRealtime() < mountDeadline && !composed) {
                    instrumentation.runOnMainSync {
                        val decorations = mutableListOf<LavaDecorationView>()
                        collectDecorations(activity.window.decorView, decorations)
                        composed = decorations.isNotEmpty() && decorations.all {
                            (it.getChildAt(0) as ComposeView).hasComposition
                        }
                    }
                    if (!composed) SystemClock.sleep(100L)
                }
                assertTrue("Launch $launch did not mount and compose native decorations", composed)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    instrumentation.runOnMainSync { activity.finish() }
                    val destroyDeadline = SystemClock.elapsedRealtime() + 20_000L
                    var destroyed = false
                    while (SystemClock.elapsedRealtime() < destroyDeadline && !destroyed) {
                        instrumentation.runOnMainSync { destroyed = activity.isDestroyed }
                        if (!destroyed) SystemClock.sleep(100L)
                    }
                    assertTrue("Launch $launch did not destroy its exact Activity before reopening", destroyed)
                    instrumentation.waitForIdleSync()
                } catch (cleanupError: Throwable) {
                    val original = failure
                    if (original != null) original.addSuppressed(cleanupError) else throw cleanupError
                }
            }
            previousActivity = activity
        }
    }

    private fun collectDecorations(view: View, result: MutableList<LavaDecorationView>) {
        if (view is LavaDecorationView && view.isShown) result.add(view)
        if (view is ViewGroup) repeat(view.childCount) { collectDecorations(view.getChildAt(it), result) }
    }

    private fun collectText(view: View, result: MutableSet<String>) {
        if (!view.isShown) return
        if (view is TextView) result.add(view.text.toString())
        view.contentDescription?.toString()?.let(result::add)
        if (view is ViewGroup) repeat(view.childCount) { collectText(view.getChildAt(it), result) }
    }

    private fun reportsProtectionState(label: String): Boolean = label in PROTECTION_STATES ||
        label.startsWith("Protection status,", ignoreCase = true) &&
            PROTECTION_STATES.any { label.contains(it, ignoreCase = true) }

    private companion object {
        val PROTECTION_STATES = setOf(
            "Protection is off", "Starting protection", "Waiting for a network", "Protection is on",
            "Reconnecting", "Protection is paused", "Stopping protection", "Protection needs attention",
            "Protection is unavailable", "Set up your protection",
        )
    }
}
