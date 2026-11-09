package com.lavasec.app.reactnative

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.R
import com.lavasec.app.presentation.R as PresentationResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Settings -> embedded native QA. No fake plan, preview snapshot or alternate graph. */
@RunWith(AndroidJUnit4::class)
class LavaReactDeviceQaJourneyTest {
    @Test
    fun shippingDeviceQaSearchMockCancelHapticFeedbackCancelAndReopenKeepRealProductFacts() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val originalGeneration = checkNotNull(product.state.value.graphGeneration)
            val originalLibrary = product.currentFilterLibrary()
            val originalLogging = product.state.value.privacy?.logging
            val originalLimits = product.currentFeatureLimits()
            val originalHaptics = product.state.value.customization?.haptics
            val originalPlus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            assertTrue("The real debug snapshot must admit QA", journey.snapshot().getBoolean("qaTools"))
            journey.click("Settings", prefix = true, scroll = false)
            journey.scrollToTop()
            journey.click("Device QA")
            journey.waitLabel("Test Lava on this device, Prepare test settings, check app features, and run guided network checks.")
            journey.waitLabel("Search Device QA")
            var controls = captureControls(journey)
            journey.setText("Search Device QA", "qa-no-matching-check-73491")
            controls.reveal("No matching checks")
            journey.waitLabel("No matching checks")
            openRow(journey, controls, "Mock fresh onboarding", R.string.device_qa_rehearse_setup_with_simulated_actions_nothing_is_saved)
            journey.waitLabel("The internet is lava")
            journey.await("shared mock visit in its DEBUG React host") {
                journey.snapshot().optJSONObject("onboardingSetup")?.optBoolean("mock") == true
            }
            val mockID = journey.snapshot().getJSONObject("onboardingSetup").getString("id")
            journey.click("Meet Lava")
            journey.await("mock shared feature page") { journey.snapshot().optJSONObject("onboardingSetup")?.optInt("page") == 1 }
            journey.click("Close", scroll = false)
            journey.await("mock shared host returned to original QA Activity") {
                journey.application.reactHost.currentReactContext?.currentActivity === journey.requireActivity() &&
                    journey.snapshot().optJSONObject("onboardingSetup") == null
            }
            journey.waitLabel("Search Device QA")
            assertEquals("Mock cancellation keeps the real graph", originalGeneration, product.state.value.graphGeneration)
            assertFalse("A retired mock visit cannot be projected again", journey.snapshot().optJSONObject("onboardingSetup")?.optString("id") == mockID)
            // A separate preview Activity returns to the original native QA visit.
            // Restore the complete list before acquiring its fresh scroll owner.
            journey.setText("Search Device QA", "")
            controls = captureControls(journey)
            assertEquals("Mock setup must not overwrite configured filters", originalLibrary, product.currentFilterLibrary())
            assertEquals(originalLogging, product.state.value.privacy?.logging)

            // Paid is a clearly named local limits preview. It cannot unlock a native owner.
            openRow(journey, controls, "Test Paid", R.string.device_qa_preview)
            journey.waitLabel("Preview")
            // The shared panel groups its title, preview disclaimer and limit lines.
            journey.waitLabel("QA result, Rehearse setup with simulated actions. Nothing is saved.", prefix = true)
            assertEquals(originalLimits, product.currentFeatureLimits())
            assertEquals(originalPlus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            journey.click("Close", scroll = false)

            openRow(journey, controls, "Turn On Success", R.string.device_qa_notification_success)
            journey.waitLabel("QA result")
            journey.waitLabel("Applying settings does not verify protection or complete a network check.")
            journey.click("Close", scroll = false)
            assertEquals("Previewing a haptic keeps the actual preference", originalHaptics, product.state.value.customization?.haptics)
            assertQaResultControl(journey, enabled = true, stage = "before-feedback")

            // The ordinary composer is a child of native Settings. Its first Back pops that
            // child; a second Back from the native root finishes the Activity without submission.
            val originalFeedback = product.state.value.bugReport
            openRow(journey, controls, "Normal User Feedback", R.string.device_qa_open_the_normal_user_bug_report_sheet)
            journey.waitLabel(journey.context.getString(PresentationResources.string.settings_bug_report_title))
            journey.back()
            journey.waitLabel(journey.context.getString(PresentationResources.string.settings_title))
            journey.waitLabel(journey.context.getString(PresentationResources.string.settings_your_lava_title))
            journey.waitLabel(journey.context.getString(PresentationResources.string.bug_report_done))
            assertTrue("Feedback Back must keep the original product owner", journey.application.foregroundProduct === product)
            assertEquals(originalGeneration, product.state.value.graphGeneration)
            assertTrue("Leaving the composer must keep its unsubmitted state", product.state.value.bugReport == originalFeedback)
            assertFalse("Feedback cancellation must not report submission", product.state.value.bugReport.submitting)
            journey.back()
            journey.await("native feedback cancellation returned to the admitted React QA visit") {
                journey.application.foregroundProduct === product &&
                    product.state.value.graphGeneration == originalGeneration &&
                    journey.snapshot().getBoolean("foreground") &&
                    journey.visible("Test Lava on this device, Prepare test settings, check app features, and run guided network checks.")
            }
            journey.waitLabel("Search Device QA")
            // Background/return starts fresh private QA state; it never replays feedback or auth.
            assertQaResultControl(journey, enabled = false, stage = "after-feedback")
            assertEquals(originalLibrary, product.currentFilterLibrary())
            assertEquals(originalLogging, product.state.value.privacy?.logging)
            assertEquals(originalLimits, product.currentFeatureLimits())
            assertEquals(originalHaptics, product.state.value.customization?.haptics)
            assertEquals(originalPlus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            assertFalse("Feedback cancellation must not report submission", product.state.value.bugReport.submitting)
            assertTrue("Cancelled feedback must preserve the original unsubmitted state", product.state.value.bugReport == originalFeedback)

            journey.back()
            journey.scrollToTop()
            journey.click("Device QA")
            journey.waitLabel("Test Lava on this device, Prepare test settings, check app features, and run guided network checks.")
            journey.waitLabel("Search Device QA")
            assertQaResultControl(journey, enabled = false, stage = "after-reopen")
            captureControls(journey)
                .reveal("Mock fresh onboarding, ${journey.context.getString(R.string.device_qa_rehearse_setup_with_simulated_actions_nothing_is_saved)}")
            journey.waitLabel("Mock fresh onboarding", prefix = true, excludeEditable = true)
            assertEquals(originalLibrary, product.currentFilterLibrary())
            println("LAVA_UI_DEVICE_QA route=Settings->DeviceQA search=no-match mock=cancelled preview=nonpersisting haptic=requested feedback=cancelled reopen=fresh-private-state plus=$originalPlus")
            journey.assertNoFallback()
        }
    }

    private fun assertQaResultControl(journey: ReactEmulatorJourney, enabled: Boolean, stage: String) {
        val label = journey.context.getString(R.string.device_qa_view_qa_result)
        fun labels() = journey.accessibilityNodes().filter { node ->
            node.refresh() && node.isVisibleToUser && !node.isEditable &&
                node.packageName?.toString() == journey.context.packageName &&
                listOf(node.text, node.contentDescription).any { it?.toString() == label }
        }
        fun controls() = labels().mapNotNull { named ->
            // Compose exposes Text separately from the clickable parent's Disabled semantics.
            // Checking the label's enabled flag cannot establish the button's result state.
            var candidate: AccessibilityNodeInfo? = named
            var depth = 0
            while (candidate != null && depth < 8) {
                val current = candidate
                if (!current.refresh() || !current.isVisibleToUser || current.isEditable ||
                    current.windowId != named.windowId || current.packageName?.toString() != journey.context.packageName) break
                if (current.isClickable && !current.isScrollable) return@mapNotNull current
                if (current.isScrollable) break
                candidate = current.parent
                depth += 1
            }
            null
        }.distinct()
        fun admittedSearch(window: Int): Boolean {
            val searchLabel = journey.context.getString(R.string.device_qa_search_device_qa)
            val fields = journey.accessibilityNodes().filter { node ->
                node.windowId == window && node.packageName?.toString() == journey.context.packageName &&
                    listOf(node.text, node.contentDescription, node.hintText).any { it?.toString() == searchLabel }
            }.mapNotNull { named ->
                var candidate: AccessibilityNodeInfo? = named
                var depth = 0
                while (candidate != null && depth <= 6) {
                    val current = candidate
                    if (!current.refresh() || !current.isVisibleToUser || current.windowId != window ||
                        current.packageName?.toString() != journey.context.packageName) break
                    if (current.isEditable) return@mapNotNull current
                    candidate = current.parent
                    depth += 1
                }
                null
            }.distinct()
            return fields.singleOrNull()?.let { it.isEnabled && it.className?.toString() == "android.widget.EditText" &&
                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } } == true
        }
        try {
            var target: AccessibilityNodeInfo? = null
            journey.await("actual QA result control enabled=$enabled at $stage") {
                val candidates = controls()
                assertTrue("The exact QA result label must own at most one clickable control", candidates.size <= 1)
                target = candidates.singleOrNull()?.takeIf { it.isEnabled == enabled && admittedSearch(it.windowId) }
                target != null
            }
            val button = checkNotNull(target)
            assertTrue("The actual QA result control must remain live", button.refresh())
            assertEquals("The refreshed exact label must still own this control", button, controls().singleOrNull())
            assertTrue("The admitted QA search must remain interactive", admittedSearch(button.windowId))
            assertEquals("The actual result control must have the expected private state", enabled, button.isEnabled)
            println("LAVA_UI_QA_RESULT_CONTROL stage=$stage window=${button.windowId} identity=${button.hashCode()} " +
                "class=${button.className} enabled=${button.isEnabled} clickable=${button.isClickable} " +
                "actions=${button.actionList.map { it.id }}")
            if (!enabled) {
                assertFalse("A fresh QA visit refuses activation of a missing result", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertFalse("A fresh QA visit must not reopen a previous result", journey.visible("QA result"))
            }
        } catch (failure: AssertionError) {
            labels().take(4).forEach { named ->
                var candidate: AccessibilityNodeInfo? = named
                var depth = 0
                while (candidate != null && depth < 8) {
                    val current = candidate
                    if (current.windowId != named.windowId || current.packageName?.toString() != journey.context.packageName) break
                    println("LAVA_UI_QA_RESULT_CONTROL stage=$stage depth=$depth identity=${current.hashCode()} " +
                        "window=${current.windowId} class=${current.className} refreshed=${current.refresh()} " +
                        "visible=${current.isVisibleToUser} enabled=${current.isEnabled} editable=${current.isEditable} " +
                        "clickable=${current.isClickable} actions=${current.actionList.map { it.id }}")
                    candidate = current.parent
                    depth += 1
                }
            }
            throw failure
        }
    }

    private fun captureControls(journey: ReactEmulatorJourney): ReactEmulatorJourney.AccessibilityScrollRegion {
        journey.await("the actual native QA list has a mounted scroll owner") {
            journey.accessibilityNodes().filter { node -> node.isVisibleToUser &&
                node.packageName?.toString() == journey.context.packageName &&
                listOf(node.text, node.contentDescription).any { it?.toString() == "Search Device QA" }
            }.any { named ->
                var parent = named.parent
                var depth = 0
                while (parent != null && depth < 16) {
                    val current = parent
                    if (!current.refresh() || current.packageName?.toString() != journey.context.packageName) break
                    if (current.isScrollable && current.isVisibleToUser) return@any true
                    parent = current.parent
                    depth += 1
                }
                false
            }
        }
        return journey.scrollRegionContaining("Search Device QA")
    }

    private fun openRow(journey: ReactEmulatorJourney, controls: ReactEmulatorJourney.AccessibilityScrollRegion, title: String, summary: Int) {
        controls.reveal("Search Device QA", forward = false)
        journey.setText("Search Device QA", title)
        // The shared row exposes its title and summary as one accessible button.
        // Exclude the search input, which contains the same title.
        controls.reveal("$title, ${journey.context.getString(summary)}")
        journey.waitLabel(title, prefix = true, excludeEditable = true)
        journey.click(title, prefix = true, scroll = false, excludeEditable = true)
    }
}
