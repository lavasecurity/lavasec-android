package com.lavasec.app.reactnative

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lavasec.app.R
import com.lavasec.app.reactnative.components.LavaNativePageState
import com.lavasec.app.vpn.qa.DnsLeakCanaryMutation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual debug arm controls only. No chained launch, datagram receipt or physical PASS is invented. */
@RunWith(AndroidJUnit4::class)
class LavaReactDeviceQaCanaryJourneyTest {
    @Test fun shippingQaCanaryNumericRefusalCancelArmReopenAndDisarmPreserveProductAuthority() {
        ReactEmulatorJourney().use { journey ->
            try {
                journey.launch()
            } catch (original: Throwable) {
                try { dumpReactLaunchHost(journey) }
                catch (diagnostic: Throwable) { original.addSuppressed(diagnostic) }
                throw original
            }
            journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val original = checkNotNull(product.currentFilterLibrary())
            val logging = product.currentQaLogging()
            val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            assertFalse("The actual Free installation may arm debug positive controls without fake Plus", plus)
            val binding = checkNotNull(journey.application.dnsLeakCanaryBinding)
            assertEquals(product.state.value.graphGeneration, binding.graphGeneration)
            val canary = binding.owner
            // Qualification starts clean. Never erase somebody else's pre-existing armed control.
            assertNull("Controlled shipping fixture requires a previously disarmed owner", canary.snapshot.value.arm)
            try {
                journey.click("Settings", prefix = true, scroll = false)
                journey.scrollToTop(); journey.click("Device QA")
                val controls = try {
                    search(journey)
                } catch (original: AssertionError) {
                    try { dumpNativeQaHost(journey) }
                    catch (diagnostic: Throwable) { original.addSuppressed(diagnostic) }
                    throw original
                }
                val disarmedLabel = journey.context.getString(R.string.device_qa_canary_disarmed)
                controls.reveal(disarmedLabel)
                journey.waitLabel(disarmedLabel)
                val resolverLabel = journey.context.getString(R.string.device_qa_resolver_ip)
                controls.reveal(resolverLabel, forward = false)
                journey.dumpEditableFieldDiagnostics(resolverLabel, "shipping-qa-invalid-resolver")
                journey.setText(resolverLabel, "resolver.example")
                controls.reveal("Arm Leak Canary")
                journey.click("Arm Leak Canary", scroll = false); journey.click("Apply", scroll = false)
                journey.waitLabel(journey.context.getString(R.string.device_qa_invalid_address)); journey.click("Close", scroll = false)
                assertNull(canary.snapshot.value.arm); assertNull(canary.snapshot.value.lastReceipt)

                controls.reveal(resolverLabel, forward = false)
                journey.dumpEditableFieldDiagnostics(resolverLabel, "shipping-qa-numeric-resolver")
                journey.setText(resolverLabel, "192.0.2.53")
                controls.reveal("Arm Leak Canary")
                journey.click("Arm Leak Canary", scroll = false); journey.click("Cancel", scroll = false)
                assertNull(canary.snapshot.value.arm)
                controls.reveal("Arm Leak Canary")
                journey.click("Arm Leak Canary", scroll = false); journey.click("Apply", scroll = false)
                journey.waitLabel("Saved"); journey.click("Close", scroll = false)
                journey.await("the existing graph-owned canary stores an actual arm") { canary.snapshot.value.arm?.resolverIP == "192.0.2.53" }
                val arm = checkNotNull(canary.snapshot.value.arm)
                assertTrue(arm.nonce.matches(Regex("[0-9a-f]{10}")))
                val armedLabel = journey.context.getString(R.string.device_qa_canary_armed, arm.resolverIP, arm.nonce)
                controls.reveal(armedLabel)
                journey.waitLabel(armedLabel)
                assertNull("Arming does not manufacture a send receipt", canary.snapshot.value.lastReceipt)
                assertSame(canary, journey.application.dnsLeakCanaryOwnerFor(binding.graphGeneration))
                assertEquals(original, product.currentFilterLibrary()); assertEquals(logging, product.currentQaLogging())

                journey.back(); journey.scrollToTop(); journey.click("Device QA")
                val reopenedControls = search(journey)
                assertEquals(arm, canary.snapshot.value.arm)
                reopenedControls.reveal(armedLabel)
                journey.waitLabel(armedLabel)
                reopenedControls.reveal("Disarm", forward = false)
                journey.click("Disarm", scroll = false); journey.click("Cancel", scroll = false)
                assertEquals(arm, canary.snapshot.value.arm)
                reopenedControls.reveal("Disarm", forward = false)
                journey.click("Disarm", scroll = false); journey.click("Apply", scroll = false)
                journey.waitLabel("Saved"); journey.click("Close", scroll = false)
                journey.await("the same actual owner durably disarms") { canary.snapshot.value.arm == null }
                reopenedControls.reveal(disarmedLabel)
                journey.waitLabel(disarmedLabel)
                assertNull(canary.snapshot.value.lastReceipt)
                assertEquals(original, product.currentFilterLibrary()); assertEquals(logging, product.currentQaLogging())
                assertEquals(plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
                journey.assertNoFallback()
                println("LAVA_UI_DEVICE_QA_CANARY invalid-numeric=refused arm=cancel-confirm-reopen disarm=cancel-confirm owner=existing-graph product=unchanged evidence=arm-controls-only no-runtime-launch")
            } finally {
                // Clear only this fixture's arm through the same owner and exact graph projection.
                if (canary.snapshot.value.arm != null) assertTrue(canary.disarm {
                    journey.application.dnsLeakCanaryBinding === binding && product.state.value.graphGeneration == binding.graphGeneration
                } is DnsLeakCanaryMutation.Applied)
            }
        }
    }

    private fun search(journey: ReactEmulatorJourney): ReactEmulatorJourney.AccessibilityScrollRegion {
        val label = journey.context.getString(R.string.device_qa_search_device_qa)
        journey.scrollToTop(); journey.waitLabel(label)
        journey.dumpEditableFieldDiagnostics(label, "shipping-qa-search")
        journey.setText(label, "DNS Leak Canary")
        val heading = journey.context.getString(R.string.device_qa_dns_leak_canary)
        journey.waitLabel(heading)
        // The real guidance precedes the field/actions/status and exceeds the compact
        // viewport. Capture its own native scroll parent, never the RN tabs behind it.
        return journey.scrollRegionContaining(heading)
    }

    /** Failure-only native facts before teardown, with no text, descriptions or owner payloads. */
    private fun dumpReactLaunchHost(journey: ReactEmulatorJourney) {
        journey.instrumentation.runOnMainSync {
            val activity = journey.requireActivity()
            val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            val context = journey.application.reactHost.currentReactContext
            val contextActivity = context?.currentActivity
            val root = activity.reactDelegate?.reactRootView
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            println("LAVA_UI_REACT_LAUNCH_STATE activity=${System.identityHashCode(activity)} class=${activity.javaClass.name} finishing=${activity.isFinishing} destroyed=${activity.isDestroyed} foreground=${activity.foreground} resumed=${resumed.any { it === activity }} resumedCount=${resumed.size} reactContext=${context?.let(System::identityHashCode)} contextActivity=${contextActivity?.let(System::identityHashCode)} contextActivityMatches=${contextActivity === activity} root=${root?.let(System::identityHashCode)} rootChildren=${root?.childCount} contentChildren=${content?.childCount}")
            resumed.take(4).forEach { host ->
                println("LAVA_UI_REACT_LAUNCH_ACTIVITY identity=${System.identityHashCode(host)} class=${host.javaClass.name} expected=${host === activity} finishing=${host.isFinishing} destroyed=${host.isDestroyed}")
            }

            fun geometry(view: View, path: String) {
                val visible = Rect()
                val hasVisibleRect = view.getGlobalVisibleRect(visible)
                val children = (view as? ViewGroup)?.childCount ?: 0
                println("LAVA_UI_REACT_LAUNCH_VIEW path=$path identity=${System.identityHashCode(view)} class=${view.javaClass.name} id=${view.id} reactRoot=${view === root} attached=${view.isAttachedToWindow} visibility=${view.visibility} windowVisibility=${view.windowVisibility} shown=${view.isShown} alpha=${view.alpha} enabled=${view.isEnabled} focused=${view.isFocused} focusable=${view.isFocusable} accessibility=${view.importantForAccessibility} layoutRequested=${view.isLayoutRequested} children=$children bounds=${view.left},${view.top},${view.right},${view.bottom} measured=${view.measuredWidth}x${view.measuredHeight} globalVisible=$hasVisibleRect globalBounds=${visible.left},${visible.top},${visible.right},${visible.bottom}")
            }
            // Always retain the exact root/cover siblings even if a mounted React
            // hierarchy exhausts the bounded descendant walk before its second sibling.
            root?.let { geometry(it, "delegate-root") }
            content?.let { parent ->
                repeat(minOf(parent.childCount, 4)) { geometry(parent.getChildAt(it), "content-$it") }
            }
            var visited = 0
            var capped = false
            fun walk(view: View, path: String, depth: Int) {
                if (visited >= 80) { capped = true; return }
                visited += 1
                geometry(view, path)
                if (view is ViewGroup) {
                    if (depth >= 8 && view.childCount > 0) { capped = true; return }
                    for (index in 0 until view.childCount) {
                        if (visited >= 80) { capped = true; break }
                        walk(view.getChildAt(index), "$path.$index", depth + 1)
                    }
                }
            }
            walk(activity.window.decorView, "decor", 0)
            println("LAVA_UI_REACT_LAUNCH_STATE nativeViewsVisited=$visited capped=$capped")
        }
    }

    /** Failure-only inspection of the real mounted host; never grants or lays out anything. */
    private fun dumpNativeQaHost(journey: ReactEmulatorJourney) {
        journey.instrumentation.runOnMainSync {
            val hosts = mutableListOf<View>()
            fun find(view: View) {
                if (view.javaClass.name == "com.lavasec.app.reactnative.components.LavaNativePageView") hosts += view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))
            }
            find(journey.requireActivity().window.decorView)
            println("LAVA_QA_HOST_STATE count=${hosts.size}")
            hosts.forEachIndexed { hostIndex, host ->
                // Read only our own finite state object, then print these explicitly
                // whitelisted primitive projections. No object/input/token toString.
                val state = runCatching {
                    host.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(host) as? LavaNativePageState
                }.getOrNull()
                val page = if (state?.page == "phoneQA") "phoneQA" else "other"
                println("LAVA_QA_HOST_STATE host=$hostIndex readable=${state != null} page=$page focused=${state?.focused} active=${state?.active} admitted=${state?.admission != null} interactionAllowed=${state?.interactionAllowed}")
                fun geometry(view: View, relation: String) {
                    val visible = Rect()
                    val hasVisibleRect = view.getGlobalVisibleRect(visible)
                    println("LAVA_QA_HOST_CHILD host=$hostIndex relation=$relation class=${view.javaClass.name} attached=${view.isAttachedToWindow} visibility=${view.visibility} shown=${view.isShown} enabled=${view.isEnabled} accessibility=${view.importantForAccessibility} bounds=${view.left},${view.top},${view.right},${view.bottom} measured=${view.measuredWidth}x${view.measuredHeight} globalVisible=$hasVisibleRect globalBounds=${visible.left},${visible.top},${visible.right},${visible.bottom}")
                }
                fun children(view: View, path: String, depth: Int) {
                    geometry(view, path)
                    if (view is ViewGroup && depth < 6) {
                        for (index in 0 until view.childCount) children(view.getChildAt(index), "$path.$index", depth + 1)
                    }
                }
                children(host, "host", 0)
                var parent = host.parent
                var depth = 0
                while (parent is View && depth < 6) {
                    geometry(parent, "parent-$depth")
                    parent = parent.parent
                    depth += 1
                }
            }
        }
    }
}
