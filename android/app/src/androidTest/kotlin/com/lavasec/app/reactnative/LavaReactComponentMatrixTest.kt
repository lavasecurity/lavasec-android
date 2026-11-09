package com.lavasec.app.reactnative

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.View
import android.view.ViewGroup
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.AutoCompleteTextView
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lavasec.app.BuildConfig
import com.lavasec.app.R as AppResources
import com.lavasec.app.presentation.R as PresentationResources
import com.lavasec.app.settings.ProductSettingsStore
import com.lavasec.app.data.ActivityDatabase
import com.lavasec.app.data.ActivityHistoryPageToken
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.data.ActivityRevisionBus
import com.lavasec.app.data.CredentialActivityWriter
import com.lavasec.app.billing.PlayPurchaseReadStatus
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import com.lavasec.app.foundation.preferences.ProductPreferences
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual debug gallery/native controls and six shipping routes; no preview launch or QA gate
 * override. Settings use production owners. Gallery values are explicitly synthetic component
 * fixtures, never protection, account or entitlement evidence. Locale fixtures configure this
 * process and real resources, not OS language installation, IME composition or TalkBack speech.
 *
 * This bounded matrix does not qualify missing gallery pages/proportion/identity families,
 * artwork/contrast by visual review, physical devices, or the complete F25 route inventory.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactComponentMatrixTest {
    @Test
    fun shippingUpgradePagerPositionsBenefitsAndBackPreserveNativeEntitlement() {
        withConfiguredJourney { journey, fixture ->
            fixture.setting("appearance", "light")
            fixture.setting("matchTextSize", false)
            fixture.setting("textSize", 3)
            rotate(journey, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            awaitTheme(journey, "light")
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            val library = checkNotNull(product.currentFilterLibrary())
            val logging = checkNotNull(product.currentQaLogging())
            val preferences = ProductSettingsStore(journey.context).loadPreferences()
            assertFalse("This is the real free Upgrade route, not a paid fixture", journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            journey.waitLabel("Protection status", prefix = true)
            journey.capturePublicPage("shipping-guard")
            journey.click("Settings", prefix = true, scroll = false)
            journey.scrollToTop()
            journey.waitLabel("Your connection")
            journey.capturePublicPage("shipping-settings")
            journey.click("Get Lava Plus today")
            journey.waitLabel("More ways to make Lava your own")
            journey.capturePublicPage("shipping-upgrade")
            val page = journey.scrollRegionContaining("More ways to make Lava your own")
            // This lower sibling reveals the actual pager dots without confusing a page's
            // repeated visible title with its independent native Tab activation target.
            page.reveal("Everything included", expectedButtonRole = true)
            val descriptions = listOf(
                "Keep work, home, and everything in between covered — with space for 50 filters and 2 million rules.",
                "Keep the DNS and VPN you trust. Make Lava fit the setup you’ve sweated over.",
                "Choose your Lava Guard. Discover what they’re made of — and the security tips they carry.",
            )
            val titles = listOf("Room for more", "Choose how you connect", "Find your Lava")
            fun tabs(selected: Int): List<AccessibilityNodeInfo> {
                var result: List<AccessibilityNodeInfo>? = null
                journey.await("Upgrade real Tab positions and selected page ${selected + 1}") {
                    val nodes = journey.accessibilityNodes()
                    val candidates = titles.map { title -> nodes.filter { node ->
                        node.packageName?.toString() == journey.context.packageName && node.isVisibleToUser &&
                            ownLabel(node, title) && AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString() ==
                            journey.context.getString(com.facebook.react.R.string.rn_tab_description)
                    }.also { assertTrue("Upgrade must not expose duplicate '$title' Tabs", it.size <= 1) }.singleOrNull() }
                    if (candidates.any { it == null }) return@await false
                    val live = candidates.map { checkNotNull(it) }
                    if (live.any { !it.refresh() || !it.isEnabled || !it.isClickable }) return@await false
                    if (live.withIndex().any { (index, node) -> node.isSelected != (index == selected) }) return@await false
                    if (live.any { it.rangeInfo == null }) return@await false
                    live.forEachIndexed { index, node ->
                        val range = checkNotNull(node.rangeInfo) { "'${titles[index]}' requires native RangeInfo" }
                        assertEquals(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, range.type)
                        assertEquals(1f, range.min, 0f)
                        assertEquals(3f, range.max, 0f)
                        assertEquals((index + 1).toFloat(), range.current, 0f)
                    }
                    result = live
                    true
                }
                assertEquals(1, checkNotNull(result).count { it.isSelected })
                var nativePages: List<Pair<String, Int>> = emptyList()
                journey.instrumentation.runOnMainSync {
                    nativePages = views(journey.requireActivity().window.decorView).mapNotNull { view ->
                        val id = view.getTag(com.facebook.react.R.id.react_test_id)?.toString()
                        if (id in listOf("carousel.page.room", "carousel.page.connection", "carousel.page.guard")) {
                            assertTrue("Real mounted page $id must retain its Yoga footprint", view.width > 0 && view.height > 0)
                            id!! to view.importantForAccessibility
                        } else null
                    }
                }
                assertEquals("All real pages remain mounted", 3, nativePages.size)
                val selectedID = listOf("carousel.page.room", "carousel.page.connection", "carousel.page.guard")[selected]
                nativePages.forEach { (id, importance) ->
                    if (id == selectedID) assertTrue("The current actual page must permit its accessible descendants",
                        importance == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO || importance == View.IMPORTANT_FOR_ACCESSIBILITY_YES)
                    else assertEquals("Every inactive mounted page must hide its accessible descendants",
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, importance)
                }
                println("LAVA_UI_UPGRADE_PAGE selected=${selected + 1} count=3 role=Tab ranges=1..3 nativePages=$nativePages")
                return checkNotNull(result)
            }
            fun awaitPageCopy(selected: Int, selectedTabs: List<AccessibilityNodeInfo>) {
                val activity = journey.requireActivity()
                val window = selectedTabs[selected].windowId
                assertTrue("Upgrade copy requires one actual app window", window >= 0 && selectedTabs.all { it.windowId == window })
                var acceptedVisibility: List<Boolean>? = null
                var lastFacts = "no AX sample"
                fun copyLabel(value: CharSequence?): String = when {
                    value.isNullOrEmpty() -> "<empty>"
                    descriptions.any { value.toString().equals(it, ignoreCase = true) } -> value.toString()
                    else -> "<other-redacted>"
                }
                fun nativeFacts(): String {
                    var facts = ""
                    journey.instrumentation.runOnMainSync {
                        facts = views(activity.window.decorView).mapNotNull { view ->
                            val id = view.getTag(com.facebook.react.R.id.react_test_id)?.toString()
                            if (id in listOf("carousel.page.room", "carousel.page.connection", "carousel.page.guard")) {
                                "$id:importance=${view.importantForAccessibility},size=${view.width}x${view.height}"
                            } else null
                        }.joinToString(";")
                    }
                    return facts
                }
                try {
                    journey.await("Upgrade current page ${selected + 1} copy visible and all retired copy withdrawn") {
                        var currentWindow = -1
                        journey.instrumentation.runOnMainSync {
                            assertTrue("Upgrade copy must retain the exact Activity", activity === journey.requireActivity())
                            currentWindow = activity.window.decorView.createAccessibilityNodeInfo().windowId
                        }
                        // One refreshed traversal supplies both sides of the transition. Native
                        // page importance and the selected Tab can settle before AX copy retires.
                        val nodes = journey.accessibilityNodes().filter {
                            it.packageName?.toString() == journey.context.packageName && it.windowId == window
                        }
                        val copyNodes = descriptions.map { copy -> nodes.filter { node ->
                            listOf(node.text, node.contentDescription).any { it?.toString()?.equals(copy, ignoreCase = true) == true }
                        } }
                        val visibility = copyNodes.map { matches -> matches.any { it.isVisibleToUser } }
                        val selectedPages = titles.mapIndexedNotNull { index, title ->
                            if (nodes.any { node -> node.isVisibleToUser && node.isSelected && ownLabel(node, title) &&
                                AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString() ==
                                journey.context.getString(com.facebook.react.R.string.rn_tab_description) }) index + 1 else null
                        }
                        lastFacts = "requested=${selected + 1} selected=$selectedPages window=$window currentWindow=$currentWindow " +
                            "visible=$visibility nodes=" + copyNodes.flatMapIndexed { index, matches -> matches.map { node ->
                                "page=${index + 1},identity=${node.hashCode()},window=${node.windowId},viewID=${node.viewIdResourceName}," +
                                    "class=${node.className},visible=${node.isVisibleToUser},text=${copyLabel(node.text)},contentDesc=${copyLabel(node.contentDescription)}"
                            } }.take(12).joinToString(";")
                        if (currentWindow != window || selectedPages != listOf(selected + 1) ||
                            !visibility[selected] || visibility.withIndex().any { (index, visible) -> index != selected && visible }) return@await false
                        acceptedVisibility = visibility
                        true
                    }
                } catch (failure: AssertionError) {
                    val native = runCatching { nativeFacts() }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
                    println("LAVA_UI_UPGRADE_COPY phase=timeout $lastFacts nativePages=$native")
                    throw failure
                }
                val visibility = checkNotNull(acceptedVisibility)
                assertTrue("Current page copy must be visible in the actual AX tree", visibility[selected])
                visibility.forEachIndexed { index, visible ->
                    if (index != selected) assertFalse("Retired page copy must be withdrawn from the actual AX tree", visible)
                }
                println("LAVA_UI_UPGRADE_COPY phase=ready $lastFacts nativePages=${nativeFacts()}")
            }
            tabs(0)
            for (index in listOf(1, 2, 0)) {
                val target = tabs(if (index == 1) 0 else if (index == 2) 1 else 2)[index]
                assertTrue("The actual Upgrade Tab must refresh before its single action", target.refresh())
                assertTrue("The actual Upgrade Tab refused its one activation", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitPageCopy(index, tabs(index))
            }
            journey.click("Everything included", scroll = false)
            val probe = MatrixProbe(journey, "shipping-upgrade-benefits")
            for (title in listOf("Custom blocklists", "Custom DNS", "VPN chaining", "All Lava Guards")) {
                probe.bringIntoView(title)
                journey.waitLabel(title)
                var included = false
                journey.instrumentation.runOnMainSync {
                    included = textViews(journey.requireActivity().window.decorView).filter {
                        it.text.toString() == title && it.isShown && it.getGlobalVisibleRect(Rect())
                    }.any { label ->
                        var parent = label.parent as? View
                        repeat(3) {
                            val current = parent
                            if (current != null && textViews(current).any { it.text.toString() == "Included" && it.isShown }) included = true
                            parent = current?.parent as? View
                        }
                        included
                    }
                }
                assertTrue("$title must actually be displayed with its Included metadata", included)
            }
            journey.instrumentation.runOnMainSync {
                assertFalse("Android must not advertise Apple's Family Sharing", textViews(journey.requireActivity().window.decorView)
                    .any { it.isShown && it.text.toString() == "Family Sharing" })
                assertFalse("Implemented capabilities must not display obsolete unavailable copy", textViews(journey.requireActivity().window.decorView)
                    .any { it.isShown && it.text.toString() == "Not available in this build" })
            }
            // Everything included expands inline in the shared Upgrade page. Collapse
            // that same actual disclosure before returning once to Settings.
            probe.bringIntoView("Everything included")
            journey.click("Everything included", scroll = false)
            journey.scrollToTop()
            journey.waitLabel("More ways to make Lava your own")
            journey.back()
            journey.scrollToTop()
            journey.waitLabel("Your connection")
            assertTrue(product === journey.application.foregroundProduct)
            assertEquals(generation, product.state.value.graphGeneration)
            assertEquals(library, product.currentFilterLibrary())
            assertEquals(logging, product.currentQaLogging())
            assertEquals(preferences, ProductSettingsStore(journey.context).loadPreferences())
            assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            journey.assertNoFallback()
            println("LAVA_UI_UPGRADE pager=three-real-pages pagePosition=actual-RangeInfo benefits=implemented familySharing=absent purchase=not-invoked entitlement=unchanged")
        }
    }

    @Test
    fun shippingTextSizeSliderContextMenuCancelAndStepperRefusalUseActualControls() {
        withConfiguredJourney { journey, fixture ->
            fixture.setting("appearance", "light")
            fixture.setting("matchTextSize", false)
            fixture.setting("textSize", 3)
            rotate(journey, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            awaitTheme(journey, "light")
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            val library = checkNotNull(product.currentFilterLibrary())
            val logging = checkNotNull(product.currentQaLogging())
            val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            val preferences = ProductSettingsStore(journey.context).loadPreferences()
            journey.click("Settings", prefix = true, scroll = false)
            journey.scrollToTop()
            journey.click("Customization")
            val probe = MatrixProbe(journey, "shipping-slider-stepper-menu")
            println("LAVA_UI_CONTROL_MATRIX_BEGIN stage=actual-text-slider")
            fun slider(value: Int): AccessibilityNodeInfo {
                var result: AccessibilityNodeInfo? = null
                val expectedSource = probe.revealNativeSeekBar("Text size")
                journey.await("actual native Text size SeekBar progress=$value") {
                    val candidates = journey.accessibilityNodes().filter { it.packageName?.toString() == journey.context.packageName &&
                        it == expectedSource && it.isVisibleToUser && ownLabel(it, "Text size") && it.className.toString().contains("SeekBar") }
                    assertTrue("Text size must identify one actual SeekBar", candidates.size <= 1)
                    val node = candidates.singleOrNull() ?: return@await false
                    if (!node.refresh() || !node.isEnabled || node.rangeInfo?.current != value.toFloat()) return@await false
                    val range = checkNotNull(node.rangeInfo)
                    assertEquals(0f, range.min, 0f)
                    assertEquals(6f, range.max, 0f)
                    assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id })
                    var native = false
                    journey.instrumentation.runOnMainSync {
                        val view = views(journey.requireActivity().window.decorView).filterIsInstance<SeekBar>().singleOrNull {
                            it.contentDescription?.toString().equals("Text size", ignoreCase = true) && it.isShown && it.getGlobalVisibleRect(Rect())
                        }
                        native = view != null && view.createAccessibilityNodeInfo() == expectedSource &&
                            view.isEnabled && view.max == 6 && view.progress == value
                    }
                    if (!native) return@await false
                    result = node
                    true
                }
                println("LAVA_UI_TEXT_SLIDER value=$value native=SeekBar range=0..6")
                return checkNotNull(result)
            }
            fun setSlider(from: Int, value: Int) {
                val control = slider(from)
                val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value.toFloat()) }
                fixture.preferenceAction(ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(value)) {
                    assertTrue(product === journey.application.foregroundProduct)
                    assertEquals(generation, product.state.value.graphGeneration)
                    assertTrue(control.refresh() && control.isVisibleToUser && control.isEnabled)
                    assertEquals("Adjustment retains one current connected native slider source", 1,
                        journey.accessibilityNodes().count { it == control && it.isVisibleToUser && it.isEnabled })
                    assertTrue(control.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id })
                    assertTrue("Text size refused its single real adjustment", control.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
                }
                journey.await("Text size $value reaches the actual owner and durable settings") {
                    journey.snapshot().getJSONObject("preferences").getInt("textSize") == value &&
                        ProductSettingsStore(journey.context).loadPreferences() == preferences.set(
                            ProductPreferenceKey.TEXT_SIZE, PreferenceValue.IntValue(value)).getOrThrow()
                }
                slider(value)
            }
            setSlider(3, 6)
            probe.text("This is how your text will look.", 16f * 1.35f, "light")
            journey.back()
            journey.scrollToTop()
            journey.click("Customization")
            slider(6)
            setSlider(6, 0)
            setSlider(0, 3)
            assertEquals(preferences, ProductSettingsStore(journey.context).loadPreferences())
            journey.back()
            journey.scrollToTop()
            journey.click("Design system")
            journey.waitLabel("Lava component review")
            val gallery = MatrixProbe(journey, "stepper-rejected-owner-confirmation")
            println("LAVA_UI_CONTROL_MATRIX_BEGIN stage=stepper-owner-refusal")
            gallery.control("Pending confirmation Previous", enabled = false)
            gallery.control("Pending confirmation Next", enabled = true)
            gallery.bringIntoView("Requested: normal · Confirmed: normal")
            journey.waitLabel("Requested: normal · Confirmed: normal")
            val previous = gallery.control("Pending confirmation Previous", enabled = false)
            println("LAVA_UI_STEPPER_BOUNDARY previousAccepted=${previous.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
            journey.instrumentation.waitForIdleSync()
            gallery.bringIntoView("Requested: normal · Confirmed: normal")
            journey.waitLabel("Requested: normal · Confirmed: normal")
            gallery.activate("Pending confirmation Next")
            gallery.bringIntoView("Requested: focused · Confirmed: normal")
            journey.waitLabel("Requested: focused · Confirmed: normal")
            // The same native controlled stepper returns to Normal after the synchronous
            // gallery owner declines to change its confirmed value; it cannot invent a save.
            gallery.control("Pending confirmation Previous", enabled = false)
            gallery.control("Pending confirmation Next", enabled = true)
            journey.instrumentation.runOnMainSync {
                val stepper = views(journey.requireActivity().window.decorView).single { view ->
                    view.contentDescription?.toString() == "Pending confirmation" && views(view).any {
                        it.contentDescription?.toString() == "Pending confirmation Previous"
                    }
                }
                assertTrue("The actual stepper must display its retained Normal value", textViews(stepper).any { it.text.toString() == "Normal" })
                assertFalse(textViews(stepper).any { it.text.toString() == "Focused" })
            }
            journey.back()
            journey.scrollToTop()
            journey.waitLabel("Your connection")
            exerciseRetainedComponentHistoryMenu(journey)
            assertTrue(product === journey.application.foregroundProduct)
            assertEquals(generation, product.state.value.graphGeneration)
            assertEquals(library, product.currentFilterLibrary())
            assertEquals(logging, product.currentQaLogging())
            assertEquals(preferences, ProductSettingsStore(journey.context).loadPreferences())
            assertEquals(plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            journey.assertNoFallback()
            println("LAVA_UI_CONTROL_MATRIX slider=actual-native-range-and-durable-reopen stepper=lower-bound-and-rejected-ack contextMenu=real-long-press-more-cancel owner=retained")
        }
    }

    @Test
    fun componentGalleryThemeTextAndOrientationUseRealControlsAndSemantics() {
        withConfiguredJourney { journey, fixture ->
            assertTrue("Components must be eligible through the real debug capability", BuildConfig.DEBUG)
            assertTrue(journey.snapshot().getBoolean("qaTools"))
            for (theme in listOf("light", "dark")) for (size in listOf(3, 6)) {
                for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    val cell = "$theme/text-$size/${if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "portrait" else "landscape"}"
                    println("LAVA_UI_MATRIX_BEGIN cell=$cell")
                    fixture.setting("appearance", theme)
                    fixture.setting("matchTextSize", false)
                    fixture.setting("textSize", size)
                    rotate(journey, orientation)
                    awaitTheme(journey, theme)
                    journey.click("Settings", prefix = true, scroll = false)
                    journey.scrollToTop()
                    // Settings begins with its connection panel; Your Lava can be below the
                    // fold in a compact landscape viewport even at the genuine scroll top.
                    journey.waitLabel("Your connection")
                    journey.click("Design system")
                    journey.waitLabel("Lava component review")
                    val probe = MatrixProbe(journey, cell)
                    probe.heading("Lava component review", true)
                    probe.heading("Row title", false)
                    val scale = if (size == 6) 1.35f else 1f
                    probe.text("Lava component review", 17f * scale, theme)
                    probe.text("Row title", 16f * scale, theme)
                    probe.text("Secondary label", 16f * scale, theme, InkRole.SECONDARY)
                    probe.checkSwitch("Protection feedback", checked = false, enabled = true)
                    probe.activate("Protection feedback", checkable = true)
                    probe.checkSwitch("Protection feedback", checked = true, enabled = true)
                    probe.activate("Protection feedback", checkable = true)
                    probe.checkSwitch("Protection feedback", checked = false, enabled = true)
                    val disabled = probe.checkSwitch("Disabled setting", checked = true, enabled = false)
                    println("LAVA_UI_MATRIX_DISABLED cell=$cell accepted=${disabled.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
                    journey.instrumentation.waitForIdleSync()
                    probe.checkSwitch("Disabled setting", checked = true, enabled = false)

                    probe.bringGroupIntoView("Async confirmation")
                    probe.choice("Async confirmation", "Normal", selected = true, enabled = true)
                    probe.activate("Focused", group = "Async confirmation")
                    probe.choice("Async confirmation", "Focused", selected = true, enabled = false)
                    probe.bringIntoView("Saved async choice: normal")
                    journey.waitLabel("Saved async choice: normal")
                    probe.activate("Cancel choice")
                    probe.choice("Async confirmation", "Normal", selected = true, enabled = true)
                    probe.bringIntoView("Saved async choice: normal")
                    journey.waitLabel("Saved async choice: normal")
                    probe.control("Accept choice", enabled = false)
                    probe.activate("Focused", group = "Async confirmation")
                    probe.activate("Accept choice")
                    probe.choice("Async confirmation", "Focused", selected = true, enabled = true)
                    probe.bringIntoView("Saved async choice: focused")
                    journey.waitLabel("Saved async choice: focused")
                    probe.control("Cancel choice", enabled = false)
                    val longLabel = "Eine längere Bezeichnung, die bei größerer Schrift mehr Platz benötigt"
                    probe.checkSwitch(longLabel, checked = false, enabled = true)
                    probe.text(longLabel, 16f * scale, theme)

                    probe.bringIntoView("primary action")
                    probe.activate("primary action")
                    probe.bringIntoView("Actions received: 1")
                    journey.waitLabel("Actions received: 1")
                    val inert = probe.control("primary disabled", enabled = false)
                    println("LAVA_UI_MATRIX_DISABLED cell=$cell accepted=${inert.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
                    journey.instrumentation.waitForIdleSync()
                    probe.bringIntoView("Actions received: 1")
                    journey.waitLabel("Actions received: 1")

                    probe.bringIntoView("Type roles")
                    for ((role, points) in listOf("title" to 32f, "heading" to 22f, "section" to 17f,
                            "body" to 16f, "supporting" to 15f, "caption" to 13f)) {
                        probe.heading(role, role in listOf("title", "heading", "section"))
                        probe.text(role, points * scale, theme)
                    }
                    probe.bringIntoView("One selection slot")
                    probe.selectedRow("Core", selected = true, enabled = true)
                    probe.activate("Balanced")
                    probe.selectedRow("Balanced", selected = true, enabled = true)
                    probe.selectedRow("Core", selected = false, enabled = true)
                    val disabledSelection = probe.selectedRow("Disabled selection", selected = true, enabled = false)
                    println("LAVA_UI_MATRIX_DISABLED cell=$cell accepted=${disabledSelection.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
                    journey.instrumentation.waitForIdleSync()
                    probe.selectedRow("Balanced", selected = true, enabled = true)
                    // This is the final actual row; scrolling merely to the section title is insufficient.
                    probe.bringIntoView("Locked option")
                    probe.control("Locked option", enabled = true)
                    probe.text("Requests: 0", 15f * scale, theme, InkRole.SECONDARY)
                    probe.recordViewport()
                    journey.assertNoFallback()
                    assertFalse("Gallery fixtures must not grant Plus", journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
                    journey.back()
                    journey.scrollToTop()
                    journey.waitLabel("Your connection")
                    println("LAVA_UI_MATRIX_END cell=$cell")
                }
            }
        }
    }

    @Test
    fun tenProcessLocalesRenderShippingRoutesAndNativeDateCancellation() {
        withConfiguredJourney { journey, fixture ->
            fixture.setting("appearance", "light")
            fixture.setting("matchTextSize", false)
            fixture.setting("textSize", 3)
            rotate(journey, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            journey.click("Settings", prefix = true, scroll = false)
            for (labels in LOCALE_ROUTES) {
                fixture.locale(Locale.forLanguageTag(labels.tag))
                // A real owner command publishes the native presentation locale. No JS snapshot is injected.
                fixture.setting("appearance", "light")
                assertEquals(labels.wire, journey.snapshot().getJSONObject("presentation").getString("locale"))
                val probe = MatrixProbe(journey, "locale-${labels.tag}")
                journey.scrollToTop()
                journey.waitLabel(labels.yourLava)
                probe.heading(labels.yourLava, true)
                journey.click(labels.privacy)
                journey.waitLabel(labels.logs)
                probe.heading(labels.logs, true)
                journey.assertNoFallback()
                journey.back()
                journey.scrollToTop()
                journey.waitLabel(labels.yourLava)
                journey.click(labels.legal)
                probe.bringIntoView(labels.search)
                journey.waitLabel(labels.search)
                journey.setText(labels.search, "matrix-no-notice-${labels.tag}")
                probe.bringIntoView(labels.noMatch)
                journey.waitLabel(labels.noMatch)
                probe.text(labels.noMatch, 13f, "light", InkRole.SECONDARY)
                journey.assertNoFallback()
                journey.back()
                journey.scrollToTop()
                journey.waitLabel(labels.yourLava)
                journey.click(labels.customization)
                journey.waitLabel(labels.appearance)
                probe.heading(labels.appearance, true)
                probe.bringIntoView(labels.matchSystem)
                probe.checkSwitch(labels.matchSystem, checked = false, enabled = true)
                fixture.preferenceAction(ProductPreferenceKey.MATCH_TEXT_SIZE, PreferenceValue.Bool(true)) {
                    probe.activate(labels.matchSystem, checkable = true)
                }
                journey.await("${labels.tag} native Match System owner") {
                    journey.snapshot().getJSONObject("preferences").getBoolean("matchTextSize")
                }
                probe.checkSwitch(labels.matchSystem, checked = true, enabled = true)
                fixture.preferenceAction(ProductPreferenceKey.MATCH_TEXT_SIZE, PreferenceValue.Bool(false)) {
                    probe.activate(labels.matchSystem, checkable = true)
                }
                journey.await("${labels.tag} manual text restored") {
                    !journey.snapshot().getJSONObject("preferences").getBoolean("matchTextSize")
                }
                probe.checkSwitch(labels.matchSystem, checked = false, enabled = true)
                journey.back()
                journey.click(labels.guard, prefix = true, scroll = false)
                journey.scrollToTop()
                journey.click(labels.today, prefix = true)
                journey.waitLabel(labels.activity)
                journey.waitSelection(labels.today, true)
                journey.click(labels.custom, scroll = false)
                val start = journey.requireActivity().getString(PresentationResources.string.activity_date_start)
                journey.waitLabel(start)
                probe.heading(start, true)
                // This is the genuine native calendar; its localized platform Cancel is an actual UI action.
                journey.click(journey.requireActivity().getString(android.R.string.cancel), scroll = false)
                journey.waitSelection(labels.today, true)
                journey.waitSelection(labels.custom, false)
                journey.assertNoFallback()
                journey.back()
                journey.click(labels.settings, prefix = true, scroll = false)
                journey.waitLabel(labels.yourLava)
                assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
                println("LAVA_UI_LOCALE tag=${labels.tag} wire=${labels.wire} routes=Settings,Privacy,Legal,Customization,Guard,Activity nativeCalendar=cancelled")
            }
        }
    }

    @Test
    fun offlineNativeLicenseFlowReturnsToSharedLegalWithTheSameOwner() {
        withConfiguredJourney { journey, _ ->
            journey.click("Settings", prefix = true, scroll = false)
            journey.scrollToTop()
            journey.click("Legal notices")
            journey.waitLabel("Search notices")
            val before = journey.snapshot()
            val probe = MatrixProbe(journey, "offline-native-licenses")
            probe.bringLegalLicenseActionIntoView()
            journey.click("Full license texts")
            journey.waitLabel("Full license texts")
            journey.await("shared full-license reader over the actual native notice owner") {
                val flow = journey.snapshot().optJSONObject("foregroundFlow")
                flow?.optString("kind") == "licenses" && flow.optString("notices").isNotBlank()
            }
            val flow = journey.snapshot().getJSONObject("foregroundFlow")
            val id = flow.getString("id")
            journey.waitLabel("DNS Resolvers", prefix = true)
            assertTrue("The covered shared reader must use the original React Activity",
                resumedActivity(journey) === journey.requireActivity())
            assertTrue("Native license custody must feed real full texts", flow.getString("notices").contains("Permission"))
            journey.click("Close", scroll = false)
            journey.await("exact shared reader dismissed and returned to Legal") {
                journey.snapshot().optJSONObject("foregroundFlow")?.optString("id") != id &&
                    journey.snapshot().getBoolean("foreground")
            }
            // Return preserves this actual long Legal scroll; use its measured
            // original viewport rather than the general twelve-page bound.
            probe.returnLegalToMeasuredTop()
            journey.waitLabel("Search notices")
            val after = journey.snapshot()
            assertEquals(before.getString("generation"), after.getString("generation"))
            assertEquals(before.getJSONObject("preferences").toString(), after.getJSONObject("preferences").toString())
            assertFalse(after.getJSONObject("account").getBoolean("signedIn"))
            assertFalse(after.getJSONObject("plus").getBoolean("enabled"))
            journey.assertNoFallback()
            println("LAVA_UI_NATIVE_FLOW flow=licenses sharedReader=true nativeNotices=true returned=sharedLegal ownerRetained=true")
        }
    }

    @Test
    fun storedAppearanceConfiguresNativeResourcesBeforeTheFirstReactFrame() {
        withConfiguredJourney { journey, fixture ->
            // A process-wide night-mode change may notify other retained hosts.
            // Keep the first CREATED frame by exact instance, never the latest
            // unrelated Activity observed while the original host is retiring.
            val created = IdentityHashMap<LavaReactActivity, Pair<Int, Int>>()
            var createdOverflow = false
            val callback = ActivityLifecycleCallback { activity, stage ->
                if (activity is LavaReactActivity && stage == Stage.CREATED) {
                    // CREATED runs on the main thread before the new Activity's first draw.
                    synchronized(created) {
                        if (!created.containsKey(activity)) {
                            if (created.size < 8) {
                                created[activity] = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) to
                                    activity.getColor(AppResources.color.lava_rn_primarytext)
                            } else createdOverflow = true
                        }
                    }
                }
            }
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            monitor.addLifecycleCallback(callback)
            try {
                for (theme in listOf("light", "dark")) {
                    // Independent bootstrap golden: primaryText aliases ink in the shared
                    // generated palette and values/values-night lava_rn_colors.xml. Do not
                    // obtain the expected color from another Activity resource context.
                    val declaredPrimaryInk = when (theme) {
                        "light" -> 0xff263a2b.toInt()
                        "dark" -> 0xfff6f1e5.toInt()
                        else -> error("Unsupported bootstrap appearance")
                    }
                    fixture.setting("appearance", theme)
                    awaitTheme(journey, theme)
                    journey.instrumentation.runOnMainSync {
                        // Deliberate startup precondition: in-memory process mode disagrees with the
                        // durable owner, as in a new process. Never force the expected test mode.
                        AppCompatDelegate.setDefaultNightMode(if (theme == "dark")
                            AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
                    }
                    synchronized(created) { created.clear(); createdOverflow = false }
                    var reopened: LavaReactActivity? = null
                    var captured: Pair<Int, Int>? = null
                    try {
                        journey.reopen()
                        val actual = journey.requireActivity()
                        reopened = actual
                        val native = synchronized(created) { created[actual] }
                        captured = native
                        assertNotNull("The real Activity creation must be observed", native)
                        assertEquals("Durable mode must be native before first React draw", nightMode(theme), native!!.first)
                        assertEquals(declaredPrimaryInk, native.second)
                        awaitTheme(journey, theme)
                        assertEquals(theme, journey.snapshot().getJSONObject("preferences").getString("appearance"))
                        println("LAVA_UI_BOOTSTRAP theme=$theme createdMode=${native.first} nativeInk=${native.second}")
                    } catch (failure: Throwable) {
                        // Fixed public scalars only; retain the original failure and
                        // distinguish its CREATED frame from later resource lookups.
                        val facts = JSONObject().put("schema", 1).put("theme", theme)
                            .put("createdReceiptPresent", captured != null)
                            .put("createdMode", captured?.first ?: JSONObject.NULL)
                            .put("createdInk", captured?.second ?: JSONObject.NULL)
                            .put("declaredPrimaryInk", declaredPrimaryInk)
                        val capture = runCatching {
                            journey.instrumentation.runOnMainSync {
                                val current = journey.requireActivity()
                                facts.put("reopenedHostSame", reopened === current)
                                    .put("lookupMode", current.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                                    .put("lookupInk", current.getColor(AppResources.color.lava_rn_primarytext))
                                    .put("expectedInk", expectedInk(journey, theme))
                                    .put("defaultNightMode", AppCompatDelegate.getDefaultNightMode())
                                synchronized(created) {
                                    facts.put("capturedHosts", created.size).put("captureOverflow", createdOverflow)
                                }
                            }
                        }
                        facts.put("captureFailed", capture.isFailure)
                        val message = "LAVA_UI_APPEARANCE_CREATED $facts"
                        failure.addSuppressed(AssertionError(message).apply { stackTrace = emptyArray() })
                        println(message)
                        throw failure
                    }
                }
            } finally { monitor.removeLifecycleCallback(callback) }
        }
    }

    private fun exerciseRetainedComponentHistoryMenu(journey: ReactEmulatorJourney) {
        println("LAVA_UI_CONTROL_MATRIX_BEGIN stage=actual-history-context-menu")
        assertTrue("The component receipt belongs only to the task-owned debug emulator", BuildConfig.DEBUG)
        journey.click("Guard", prefix = true, scroll = false)
        journey.waitProtectionOff()
        ActivityDatabase(journey.context).use { database ->
            val repository = ActivityRepository(database)
            val logging = repository.loggingPolicy()
            assertTrue("ContextMenu must use actually retained history, never bypass privacy", logging.settings.keepHistory)
            fun history(): List<DnsDecisionEvent> {
                val result = mutableListOf<DnsDecisionEvent>()
                val seen = mutableSetOf<ActivityHistoryPageToken>()
                var token: ActivityHistoryPageToken? = null
                do {
                    val page = repository.domainHistoryPage(token = token)
                    result += page.rows
                    token = page.next
                    token?.let { assertTrue("The frozen history cut must advance without repeating a page", seen.add(it)) }
                } while (token != null)
                assertEquals("History must not repeat an immutable receipt", result.size, result.map { it.id }.distinct().size)
                return result
            }
            val before = history()
            val identity = UUID.randomUUID()
            val domain = "component-menu-$identity.invalid"
            val event = DnsDecisionEvent(
                id = (identity.mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L),
                timestampMillis = System.currentTimeMillis(), normalizedDomain = domain,
                decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
            )
            // An explicitly synthetic local component receipt, not network or DNS-success
            // evidence. Retain it: no supported one-receipt deletion exists, and clearing
            // the user's history or turning retention off would destroy unrelated records.
            val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
            assertTrue(writer.record(event))
            assertFalse("An exact fixture replay must not create another row or count", writer.record(event))
            val retained = history()
            val fixtureRow = retained.single { it.normalizedDomain == domain }
            // The immutable producer replay ID and SQLite's retained row ID are separate
            // authorities. The shipping query identifies this row as history:<rowId>.
            assertEquals(event, fixtureRow.copy(id = event.id))
            assertEquals("The real producer must preserve all existing user history", before, retained.filter { it.id != fixtureRow.id })
            val revisions = repository.revisions()
            runBlocking { journey.application.foregroundProduct.refreshActivity() }
            journey.click("Today", prefix = true)
            journey.waitLabel("Activity")
            journey.click("Domain history")
            val historyActivity = journey.requireActivity()
            val historyProduct = journey.application.foregroundProduct
            val historyGeneration = checkNotNull(historyProduct.state.value.graphGeneration)
            fun assertHistoryOwner() {
                assertTrue("History controls must retain their original Activity", journey.requireActivity() === historyActivity)
                assertFalse(historyActivity.isFinishing || historyActivity.isDestroyed)
                assertTrue("History controls must retain their original product", journey.application.foregroundProduct === historyProduct)
                assertEquals(historyGeneration, historyProduct.state.value.graphGeneration)
            }
            var historyWindow = -1
            journey.instrumentation.runOnMainSync {
                historyWindow = historyActivity.window.decorView.createAccessibilityNodeInfo().windowId
            }
            assertTrue("History controls require a connected original Activity window", historyWindow >= 0)
            fun searchButtons(): List<AccessibilityNodeInfo> = journey.accessibilityNodes().filter { node ->
                node.windowId == historyWindow && node.packageName?.toString() == journey.context.packageName &&
                    node.viewIdResourceName?.endsWith(":id/search_button") == true &&
                    node.className?.toString() == "android.widget.ImageView" &&
                    node.contentDescription?.toString() == "Search" && node.isVisibleToUser &&
                    node.isEnabled && node.isClickable &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            }
            // DomainListScreen uses the native navigation search bar. Its AppCompat SearchView
            // starts iconified: Search opens the actual Search domains input; no content field
            // exists beforehand. Do not manufacture a read result or change the retained receipt.
            var searchButton: AccessibilityNodeInfo? = null
            journey.await("the current Domain History native Search button") {
                assertHistoryOwner()
                val windows = journey.instrumentation.uiAutomation.windows.filter { it.id == historyWindow &&
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) &&
                    it.root?.packageName?.toString() == journey.context.packageName }
                if (windows.size != 1) return@await false
                val candidates = searchButtons()
                assertTrue("History search must identify one native button", candidates.size <= 1)
                searchButton = candidates.singleOrNull()?.takeIf { it.refresh() && searchButtons().singleOrNull() == it }
                searchButton != null
            }
            val currentSearchButton = checkNotNull(searchButton)
            assertHistoryOwner()
            assertTrue(currentSearchButton.refresh())
            assertEquals("Only the original current native Search button may open search", currentSearchButton, searchButtons().single())
            assertTrue("The actual native Search button must accept its one action",
                currentSearchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var nativeSearchField: AutoCompleteTextView? = null
            var searchInputFacts = "native=absent"
            fun searchFields(): List<AccessibilityNodeInfo> {
                var source: AccessibilityNodeInfo? = null
                journey.instrumentation.runOnMainSync {
                    val field = historyActivity.window.decorView.findViewById<AutoCompleteTextView>(androidx.appcompat.R.id.search_src_text)
                    if (field == null || !field.isAttachedToWindow || !field.isShown || field.width <= 0 || field.height <= 0)
                        return@runOnMainSync
                    nativeSearchField?.let { assertTrue("History must retain its original native input", field === it) }
                    nativeSearchField = field
                    source = field.createAccessibilityNodeInfo()
                }
                val native = source ?: return emptyList()
                searchInputFacts = "window=${native.windowId},source=${native.hashCode()},class=${native.className}," +
                    "editable=${native.isEditable},enabled=${native.isEnabled},visible=${native.isVisibleToUser}," +
                    "hintMatches=${native.hintText?.toString() in listOf("Search domains", "   Search domains")}," +
                    "setText=${native.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }}"
                // API 28's native AutoCompleteTextView inherits EditText's accessibility class;
                // newer Android overrides it. Bind to this exact real native input/source instead
                // of assuming that either platform's reported class applies to the other.
                return journey.accessibilityNodes().filter { node ->
                    node.windowId == historyWindow && node.packageName?.toString() == journey.context.packageName &&
                        node.viewIdResourceName?.endsWith(":id/search_src_text") == true &&
                        node == native && node.className == native.className && node.isVisibleToUser &&
                        // AppCompat's getDecoratedHint prefixes exactly three spaces for its
                        // ImageSpan search icon. Admit only that native decoration or the plain
                        // exact placeholder, never normalize arbitrary labels or entered text.
                        node.isEditable && node.isEnabled && node.hintText?.toString() in
                            listOf("Search domains", "   Search domains") &&
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                }
            }
            var searchField: AccessibilityNodeInfo? = null
            try {
                journey.await("the expanded Domain History native search input") {
                    assertHistoryOwner()
                    val fields = searchFields()
                    assertTrue("History search must expose one actual native input", fields.size <= 1)
                    searchField = fields.singleOrNull()?.takeIf { it.refresh() && searchFields().singleOrNull() == it }
                    searchField != null
                }
            } catch (failure: AssertionError) {
                println("LAVA_UI_HISTORY_INPUT_FAILURE sdk=${android.os.Build.VERSION.SDK_INT} $searchInputFacts")
                throw failure
            }
            val currentSearchField = checkNotNull(searchField)
            assertHistoryOwner()
            assertTrue(currentSearchField.refresh())
            assertEquals("Only the original current native search input may receive text", currentSearchField, searchFields().single())
            assertTrue("The actual native history input must accept its one edit",
                currentSearchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, domain)
                }))
            println("LAVA_UI_HISTORY_SEARCH sdk=${android.os.Build.VERSION.SDK_INT} window=$historyWindow button=${currentSearchButton.hashCode()} input=${currentSearchField.hashCode()} inputClass=${currentSearchField.className} action=one-native-open-and-edit")
            journey.waitLabel(domain, prefix = true, excludeEditable = true)
            assertHistoryOwner()
            // Close only the actual input method before measuring the row's touch target.
            if (journey.instrumentation.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) journey.back()
            val rowTestId = "domain-menu.history:${fixtureRow.id}"
            fun nativeHistoryViews(view: View): List<View> = buildList {
                add(view)
                if (view is ViewGroup) repeat(view.childCount) { addAll(nativeHistoryViews(view.getChildAt(it))) }
            }
            data class HistoryTouchTarget(val view: View, val source: AccessibilityNodeInfo, val bounds: Rect,
                val clipped: Rect, val viewport: Rect, val scroll: ScrollView, val scrollY: Int)
            var nativeTouchFacts = "native=not-measured"
            var accessibilityTouchFacts = "ax=not-acquired"
            fun measureHistoryTouch(): HistoryTouchTarget? {
                assertHistoryOwner()
                val automation = journey.instrumentation.uiAutomation
                val imeVisible = automation.windows.any { window ->
                    window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        window.root?.let { it.refresh() && it.isVisibleToUser } == true
                }
                val window = automation.windows.singleOrNull { it.id == historyWindow &&
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused &&
                    it.root?.let { root -> root.refresh() && root.packageName?.toString() == journey.context.packageName } == true }
                var native: HistoryTouchTarget? = null
                journey.instrumentation.runOnMainSync {
                    assertHistoryOwner()
                    val decor = historyActivity.window.decorView
                    val candidates = nativeHistoryViews(decor).filter { it is ViewGroup &&
                        it.getTag(com.facebook.react.R.id.react_test_id) == rowTestId && it.isAttachedToWindow && it.isShown }
                    assertTrue("The retained row must have one current native ContextMenu view", candidates.size <= 1)
                    val view = candidates.singleOrNull()
                    if (view == null) { nativeTouchFacts = "nativeCount=${candidates.size} imeVisible=$imeVisible"; return@runOnMainSync }
                    var ancestor = view.parent
                    while (ancestor is View && ancestor !is ScrollView) ancestor = ancestor.parent
                    val scroll = ancestor as? ScrollView ?: error("The retained history row requires its actual native scroll ancestor")
                    val origin = IntArray(2).also(decor::getLocationOnScreen)
                    val position = IntArray(2).also(view::getLocationOnScreen)
                    val full = Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
                    val clipped = Rect()
                    if (view.getGlobalVisibleRect(clipped)) clipped.offset(origin[0], origin[1]) else clipped.setEmpty()
                    val viewport = Rect()
                    if (scroll.getGlobalVisibleRect(viewport)) viewport.offset(origin[0], origin[1]) else viewport.setEmpty()
                    val display = Rect().also(decor::getWindowVisibleDisplayFrame)
                    val source = view.createAccessibilityNodeInfo()
                    nativeTouchFacts = "view=${System.identityHashCode(view)} source=${source.hashCode()} window=${source.windowId} " +
                        "attached=${view.isAttachedToWindow} shown=${view.isShown} enabled=${view.isEnabled} " +
                        "size=${view.width}x${view.height} full=$full clipped=$clipped viewport=$viewport display=$display " +
                        "scroll=${System.identityHashCode(scroll)} y=${scroll.scrollY} windowFocus=${decor.hasWindowFocus()} " +
                        "rowFocus=${view.hasFocus()} inputFocus=${nativeSearchField?.hasFocus()} imeVisible=$imeVisible"
                    // A completed AX query is not proof that keyboard/Fabric layout has settled.
                    // Admit only the complete current native row, with no IME or clipped target.
                    if (window == null || imeVisible || !decor.hasWindowFocus() || !view.isEnabled ||
                        view.width <= 0 || view.height <= 0 || source.windowId != historyWindow ||
                        clipped != full || !viewport.contains(full) || !display.contains(full)) return@runOnMainSync
                    native = HistoryTouchTarget(view, source, full, clipped, viewport, scroll, scroll.scrollY)
                }
                val measured = native ?: return null
                val candidates = journey.accessibilityNodes().filter { node ->
                    node.windowId == historyWindow && node.packageName?.toString() == journey.context.packageName &&
                        node == measured.source && node.className == measured.source.className &&
                        node.isVisibleToUser && node.isEnabled && node.viewIdResourceName == rowTestId &&
                        node.contentDescription?.toString()?.startsWith("$domain,") == true
                }
                assertTrue("The fixture must identify one connected native ContextMenu row", candidates.size <= 1)
                val row = candidates.singleOrNull() ?: return null
                if (!row.refresh()) return null
                val bounds = Rect().also(row::getBoundsInScreen)
                accessibilityTouchFacts = "source=${row.hashCode()} window=${row.windowId} class=${row.className} bounds=$bounds " +
                    "visible=${row.isVisibleToUser} enabled=${row.isEnabled} focused=${row.isFocused}"
                if (bounds != measured.bounds || !row.isVisibleToUser || !row.isEnabled) return null
                val windowBounds = Rect().also(checkNotNull(window)::getBoundsInScreen)
                if (!windowBounds.contains(bounds)) return null
                return measured.copy(source = row)
            }
            var previousTouch: HistoryTouchTarget? = null
            var target: HistoryTouchTarget? = null
            try {
                journey.await("the retained native/AX ContextMenu row settled after keyboard dismissal") {
                    val current = measureHistoryTouch()
                    target = current?.takeIf { it == previousTouch }
                    previousTouch = current
                    target != null
                }
            } catch (failure: AssertionError) {
                println("LAVA_UI_HISTORY_GESTURE_FAILURE phase=acquisition native=[$nativeTouchFacts] ax=[$accessibilityTouchFacts]")
                throw failure
            }
            val touch = checkNotNull(target)
            assertEquals("The single gesture must retain its settled native view/source/frame", touch, measureHistoryTouch())
            val bounds = touch.bounds
            fun gestureFacts(phase: String) {
                journey.instrumentation.runOnMainSync {
                    assertHistoryOwner()
                    val view = touch.view
                    val position = IntArray(2).also(view::getLocationOnScreen)
                    val actual = Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
                    if (phase == "before-down") {
                        assertTrue("The initiating native row must still be attached, shown and enabled",
                            view.isAttachedToWindow && view.isShown && view.isEnabled)
                        assertEquals("The held press must start at the settled real native frame", bounds, actual)
                        assertEquals(touch.source, view.createAccessibilityNodeInfo())
                        assertEquals(touch.scrollY, touch.scroll.scrollY)
                        assertTrue("Only the original focused native window may own DOWN", historyActivity.window.decorView.hasWindowFocus())
                    }
                    println("LAVA_UI_HISTORY_GESTURE phase=$phase window=$historyWindow source=${touch.source.hashCode()} " +
                        "view=${System.identityHashCode(view)} attached=${view.isAttachedToWindow} shown=${view.isShown} " +
                        "enabled=${view.isEnabled} expected=$bounds actual=$actual scrollY=${touch.scroll.scrollY} " +
                        "windowFocus=${historyActivity.window.decorView.hasWindowFocus()} rowFocus=${view.hasFocus()} " +
                        "inputFocus=${nativeSearchField?.hasFocus()}")
                }
            }
            gestureFacts("before-down")
            val downTime = SystemClock.uptimeMillis()
            val menuDeadline = SystemClock.elapsedRealtime() + 20_000L
            var down = false
            var copyReceipt: AccessibilityNodeInfo? = null
            try {
                val eventDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { down = journey.instrumentation.uiAutomation.injectInputEvent(eventDown, true) } finally { eventDown.recycle() }
                assertTrue("The current owned row refused its single genuine touch", down)
                gestureFacts("after-down")
                // This shipping wrapper is RN Pressable, whose 500ms long-press timer starts
                // after its JS grant. Android's native timeout may be only 400ms. Keep ONE real
                // DOWN held until the actual native menu receipt, using the original 20s Copy
                // budget instead of releasing at a guessed native timeout and waiting again.
                journey.await("the actual Copy menu from the one held native-row press",
                    (menuDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)) {
                    assertHistoryOwner()
                    val alert = journey.instrumentation.uiAutomation.windows.singleOrNull { window ->
                        window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused &&
                            window.id != historyWindow && window.root?.let {
                                it.refresh() && it.packageName?.toString() == journey.context.packageName
                            } == true
                    } ?: return@await false
                    val nodes = journey.accessibilityNodes().filter { it.windowId == alert.id &&
                        it.packageName?.toString() == journey.context.packageName &&
                        it.className?.toString() == "android.widget.Button" && it.isVisibleToUser &&
                        it.isEnabled && it.isClickable }
                    fun buttons(label: String) = nodes.filter { node -> listOf(node.text, node.contentDescription).any {
                        it?.toString()?.equals(label, ignoreCase = true) == true
                    } }
                    val copies = buttons("Copy")
                    assertTrue("The held press must expose one genuine native Copy button", copies.size <= 1)
                    if (buttons("More options").size != 1 || buttons("Cancel").size != 1) return@await false
                    copyReceipt = copies.singleOrNull()?.takeIf { it.refresh() && it.isVisibleToUser && it.isEnabled }
                    copyReceipt != null
                }
                val copy = checkNotNull(copyReceipt)
                println("LAVA_UI_HISTORY_GESTURE_MENU nativeTimeoutMs=${ViewConfiguration.getLongPressTimeout()} " +
                    "rnLongPressAfterGrantMs=500 downToMenuMs=${SystemClock.uptimeMillis() - downTime} " +
                    "pointer=0 initiatingWindow=$historyWindow initiatingSource=${touch.source.hashCode()} " +
                    "menuWindow=${copy.windowId} copySource=${copy.hashCode()}")
                gestureFacts("before-up")
                val eventUp = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue(journey.instrumentation.uiAutomation.injectInputEvent(eventUp, true)) } finally { eventUp.recycle() }
                down = false
                gestureFacts("after-up")
            } finally {
                if (down) {
                    val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    try { journey.instrumentation.uiAutomation.injectInputEvent(cancel, true) } finally { cancel.recycle() }
                }
            }
            assertHistoryOwner()
            val copy = checkNotNull(copyReceipt)
            assertTrue("The real Copy menu must survive the single UP without invoking an action",
                copy.refresh() && copy.isVisibleToUser && copy.isEnabled &&
                    listOf(copy.text, copy.contentDescription).any { it?.toString()?.equals("Copy", ignoreCase = true) == true })
            journey.waitLabel("More options")
            journey.click("More options", scroll = false)
            journey.waitLabel("Block")
            journey.waitLabel("Allow")
            journey.click("Cancel", scroll = false)
            journey.waitLabel(domain, prefix = true, excludeEditable = true)
            assertEquals("Cancel must preserve every actual history row", retained, history())
            assertEquals("No context action may publish a telemetry mutation", revisions, repository.revisions())
            assertEquals("Cancel preserves the actual retained-data policy", logging, repository.loggingPolicy())
            assertHistoryOwner()
            // The native search bar owns Back while open. The real toolbar navigation action
            // returns to Activity directly, without treating search collapse as route dismissal.
            journey.click("Navigate up", scroll = false)
            journey.waitLabel("Activity")
            assertHistoryOwner()
            journey.back()
            journey.waitProtectionOff()
            println("LAVA_UI_CONTEXT_MENU fixture=synthetic-local-component-receipt domain=$domain producerReceipt=${event.id} historyRow=${fixtureRow.id} retained=true resolverEvidence=absent input=single-measured-long-press menu=more-options-cancel history=all-rows-preserved")
        }
    }

    private fun withConfiguredJourney(block: (ReactEmulatorJourney, ProcessFixture) -> Unit) {
        ReactEmulatorJourney().use { journey ->
            try { journey.launch() } catch (failure: Throwable) {
                appendLaunchDiagnostic(journey, failure)
                throw failure
            }
            journey.ensureConfigured()
            assertEquals("The controlled baseline uses the English emulator locale", "en", Locale.getDefault().language)
            ProcessFixture(journey).use { fixture -> block(journey, fixture) }
        }
    }

    /** Failure-only scalar receipt: no labels, owner nonces, input or hierarchy dump. */
    private fun appendLaunchDiagnostic(journey: ReactEmulatorJourney, failure: Throwable) {
        val receipt = runCatching {
            val facts = JSONObject().put("schema", 1)
            var nativeWindow = -1
            journey.instrumentation.runOnMainSync {
                val host = journey.requireActivity()
                val context = journey.application.reactHost.currentReactContext
                val generation = journey.application.foregroundProduct.state.value.graphGeneration
                val decor = host.window.decorView
                nativeWindow = decor.createAccessibilityNodeInfo().windowId
                facts.put("host", System.identityHashCode(host)).put("currentHost", context?.currentActivity === host)
                    .put("contextPresent", context != null).put("alive", host.nativeHostAlive)
                    .put("foreground", host.foreground).put("focused", host.hasWindowFocus())
                    .put("generation", generation ?: JSONObject.NULL)
                    .put("ready", generation != null && host.isPresentationReady(generation))
                    .put("nativeWindow", nativeWindow)
                fun frame(view: View): JSONObject {
                    val code = when (view.javaClass.name) {
                        "com.facebook.react.ReactRootView" -> "react-root"
                        "com.facebook.react.runtime.ReactSurfaceView" -> "react-surface"
                        "com.facebook.react.views.view.ReactViewGroup" -> "react-view"
                        "com.swmansion.rnscreens.ScreenStack" -> "screen-stack"
                        "com.swmansion.rnscreens.Screen" -> "screen"
                        "com.lavasec.app.reactnative.ReactAppLockView" -> "native-cover"
                        "android.widget.FrameLayout" -> "frame"
                        else -> "other"
                    }
                    return JSONObject().put("class", code).put("visibility", view.visibility)
                        .put("importance", view.importantForAccessibility).put("width", view.width)
                        .put("height", view.height).put("attached", view.isAttachedToWindow)
                        .put("shown", view.isShown).put("focused", view.hasFocus())
                }
                val content = host.findViewById<ViewGroup>(android.R.id.content)
                val children = JSONArray()
                if (content != null) for (index in 0 until minOf(content.childCount, 8)) {
                    children.put(frame(content.getChildAt(index)).put("index", index))
                }
                facts.put("contentChildCount", content?.childCount ?: 0).put("children", children)
                val markers = listOf("lava-render-frame", "guard.material", "guard.mascot", "guard.pause-menu", "lava-privacy-cover")
                val markerFrames = JSONArray()
                val queue = java.util.ArrayDeque<View>().apply { add(decor) }
                var examined = 0
                var childrenBounded = false
                while (queue.isNotEmpty() && examined < 128) {
                    val view = queue.removeFirst(); ++examined
                    val marker = view.getTag(com.facebook.react.R.id.react_test_id)
                    val code = markers.indexOfFirst { it == marker }
                    if (code >= 0 && markerFrames.length() < 8) markerFrames.put(frame(view).put("marker", code))
                    if (view is ViewGroup) {
                        if (view.childCount > 32) childrenBounded = true
                        for (index in 0 until minOf(view.childCount, 32)) queue.add(view.getChildAt(index))
                    }
                }
                facts.put("markers", markerFrames).put("examined", examined)
                    .put("scanComplete", queue.isEmpty() && !childrenBounded)
            }
            val windows = JSONArray()
            for (window in journey.instrumentation.uiAutomation.windows.take(8)) {
                val root = window.root
                windows.put(JSONObject().put("id", window.id).put("type", window.type)
                    .put("layer", window.layer).put("active", window.isActive).put("focused", window.isFocused)
                    .put("ownApp", root?.packageName?.toString() == journey.context.packageName)
                    .put("nativeWindow", window.id == nativeWindow).put("rootChildren", root?.childCount ?: -1))
            }
            facts.put("windows", windows)
        }.getOrElse { JSONObject().put("schema", 1).put("captureFailed", true) }
        val message = "LAVA_UI_MATRIX_LAUNCH $receipt"
        // Suppression preserves this receipt in the original failed transcript even
        // if a later instrumentation case evicts the bounded System.out buffer.
        failure.addSuppressed(AssertionError(message).apply { stackTrace = emptyArray() })
        println(message)
    }

    private class ProcessFixture(private val journey: ReactEmulatorJourney) : AutoCloseable {
        private val preferences = ProductSettingsStore(journey.context).loadPreferences()
        private val product = journey.application.foregroundProduct
        private val module = checkNotNull(journey.application.reactHost.currentReactContext?.getNativeModule(LavaAppModule::class.java))
        private val generation = checkNotNull(product.state.value.graphGeneration)
        private val library = checkNotNull(product.currentFilterLibrary())
        private val logging = checkNotNull(product.currentQaLogging())
        private val plusBackend = journey.application.plusBackend.value
        private val hasPlus = plusBackend?.entitlement?.state?.value?.hasPlus
        private var ownedPreferences = preferences
        private var pendingPreferences: ProductPreferences? = null
        private val originalLocale = Locale.getDefault()
        private val originalLocales = journey.context.resources.configuration.locales
        private val originalOrientation = journey.requireActivity().requestedOrientation
        private val originalNightMode = AppCompatDelegate.getDefaultNightMode()
        private val originalAccessibilityFlags = journey.instrumentation.uiAutomation.serviceInfo.flags
        init {
            // RN deliberately exposes testID as the public accessibility view ID. Keep this
            // inspection capability scoped to this fixture, preserving the real labels.
            val automation = journey.instrumentation.uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        }
        fun locale(value: Locale) {
            Locale.setDefault(value)
            updateLocales(LocaleList(value))
        }
        private fun assertOwned() {
            assertTrue("Matrix cleanup must retain its exact original React module", journey.application.reactHost.currentReactContext?.getNativeModule(LavaAppModule::class.java) === module)
            assertTrue("Matrix cleanup must retain its exact original product owner", journey.application.foregroundProduct === product)
            assertEquals("Matrix cleanup refuses a successor credential graph", generation, product.state.value.graphGeneration)
            assertEquals("Matrix must preserve the full original library", library, product.currentFilterLibrary())
            assertEquals("Matrix must preserve the original logging policy", logging, product.currentQaLogging())
            assertTrue("Matrix must retain the original billing owner", journey.application.plusBackend.value === plusBackend)
            assertEquals("Matrix must preserve actual entitlement", hasPlus, plusBackend?.entitlement?.state?.value?.hasPlus)
            val current = ProductSettingsStore(journey.context).loadPreferences()
            assertTrue("Matrix cleanup refuses any unowned full-preference change", current == ownedPreferences || current == pendingPreferences)
        }
        private fun awaitTerminalAdmission() {
            var terminalSince: Long? = null
            // purchase.refresh performs product loading followed by purchase querying, each
            // bounded by NativePlayBilling.awaitPlayOperation's actual 15 s RPC deadline.
            // Wait for both native owners, not only snapshot.checking (which omits loading).
            journey.await("original native product-loading and purchase owners terminal", 35_000) {
                assertOwned()
                val controller = plusBackend?.controller?.state?.value
                val purchase = plusBackend?.purchases?.state?.value
                val terminal = controller?.loading != true && controller?.busy != true &&
                    purchase?.status !in setOf(PlayPurchaseReadStatus.NOT_CHECKED, PlayPurchaseReadStatus.CHECKING) &&
                    journey.snapshot().optBoolean("foreground")
                if (!terminal) {
                    terminalSince = null
                    false
                } else {
                    val now = SystemClock.elapsedRealtime()
                    val since = terminalSince
                    if (since == null) { terminalSince = now; false }
                    else now - since >= 100
                }
            }
            journey.instrumentation.runOnMainSync { /* terminal-owner main-queue barrier */ }
            assertOwned()
        }
        fun preferenceAction(key: ProductPreferenceKey, value: PreferenceValue, action: () -> Unit) {
            assertOwned()
            check(pendingPreferences == null)
            // Register only this one submitted control intent. Failure cleanup can recognize
            // either its prior value or exact desired whole model, never an arbitrary diff.
            val desired = ownedPreferences.set(key, value).getOrThrow()
            pendingPreferences = desired
            action()
            journey.await("matrix's exact controlled preference acknowledgement") {
                assertOwned()
                ProductSettingsStore(journey.context).loadPreferences() == desired
            }
            ownedPreferences = desired
            pendingPreferences = null
        }
        fun setting(key: String, value: Any) {
            val nativeKey = checkNotNull(ProductPreferenceKey.fromWire(key))
            val nativeValue = when (value) {
                is Boolean -> PreferenceValue.Bool(value)
                is Int -> PreferenceValue.IntValue(value)
                is String -> PreferenceValue.Text(value)
                else -> error("Unsupported controlled preference value")
            }
            awaitTerminalAdmission()
            preferenceAction(nativeKey, nativeValue) {
                journey.completed("settings.set", JSONObject().put("key", key).put("value", value))
            }
        }
        @Suppress("DEPRECATION")
        private fun updateLocales(locales: LocaleList) {
            journey.instrumentation.runOnMainSync {
                listOf(journey.context.resources, journey.application.resources, journey.requireActivity().resources)
                    .distinct().forEach { resources: Resources ->
                        val next = Configuration(resources.configuration).apply { setLocales(locales) }
                        resources.updateConfiguration(next, resources.displayMetrics)
                    }
                journey.requireActivity().onConfigurationChanged(Configuration(journey.requireActivity().resources.configuration))
            }
        }
        override fun close() {
            // A failed native-flow assertion must not leave its Activity/admission lane open.
            // Finishing is the genuine lifecycle cancellation; no successful result is injected.
            assertOwned()
            var nativeChildren: List<Activity> = emptyList()
            journey.instrumentation.runOnMainSync {
                nativeChildren = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filter { it !is LavaReactActivity && it.packageName == journey.context.packageName }
                nativeChildren.forEach { it.finish() }
            }
            journey.await("this matrix's owned native children destroyed") {
                var destroyed = false
                journey.instrumentation.runOnMainSync { destroyed = nativeChildren.all { it.isDestroyed } }
                destroyed
            }
            assertOwned()
            ownedPreferences = ProductSettingsStore(journey.context).loadPreferences()
            pendingPreferences = null
            fun restore(key: ProductPreferenceKey, wire: Any) {
                assertOwned()
                if (ownedPreferences.value(key) != preferences.value(key)) setting(key.wire, wire)
            }
            val repairs = listOf<() -> Unit>(
                { Locale.setDefault(originalLocale); updateLocales(originalLocales) },
                { restore(ProductPreferenceKey.APPEARANCE, preferences.appearance.wire) },
                { restore(ProductPreferenceKey.TEXT_SIZE, preferences.textSize.index) },
                { restore(ProductPreferenceKey.MATCH_TEXT_SIZE, preferences.matchTextSize) },
                { journey.instrumentation.runOnMainSync {
                    journey.requireActivity().requestedOrientation = originalOrientation
                    AppCompatDelegate.setDefaultNightMode(originalNightMode)
                } },
                { val automation = journey.instrumentation.uiAutomation
                    automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags } },
            )
            var failure: Throwable? = null
            repairs.forEach { repair ->
                try { assertOwned(); repair() } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure!!.addSuppressed(problem)
                }
            }
            try {
                assertEquals("Restore only the original full owned preference model", preferences, ProductSettingsStore(journey.context).loadPreferences())
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
            failure?.let { throw it }
        }
    }

    private class MatrixProbe(private val journey: ReactEmulatorJourney, private val cell: String) {
        private var retainedLegalScroll: Pair<LavaReactActivity, ScrollView>? = null
        private data class SliderGeometry(val view: SeekBar, val container: ScrollView, val y: Int,
            val containerWidth: Int, val containerHeight: Int, val target: Rect, val clipped: Rect, val viewport: Rect,
            val source: AccessibilityNodeInfo, val sliderSource: AccessibilityNodeInfo, val enabled: Boolean)
        fun revealNativeSeekBar(label: String): AccessibilityNodeInfo {
            val navigationDeadline = SystemClock.elapsedRealtime() + 20_000
            val activity = journey.requireActivity()
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            var lastNative = "not measured"
            var lastAccessibility = "not acquired"
            fun geometry(): SliderGeometry? {
                var result: SliderGeometry? = null
                journey.instrumentation.runOnMainSync {
                    assertTrue("Slider acquisition refuses a successor React host",
                        journey.requireActivity() === activity && !activity.isFinishing && !activity.isDestroyed)
                    assertTrue("Slider acquisition retains its original product owner", journey.application.foregroundProduct === product)
                    assertEquals("Slider acquisition retains its original credential graph", generation, product.state.value.graphGeneration)
                    val decor = activity.window.decorView
                    val candidates = views(decor).filterIsInstance<SeekBar>().filter {
                        it.isAttachedToWindow && it.isShown && it.contentDescription?.toString().equals(label, ignoreCase = true)
                    }
                    assertTrue("The actual '$label' SeekBar must remain unambiguous", candidates.size <= 1)
                    val slider = candidates.singleOrNull()
                    if (slider == null) {
                        lastNative = "attachedShownSliderCount=0"
                        return@runOnMainSync
                    }
                    var ancestor = slider.parent
                    while (ancestor is View && ancestor !is ScrollView) ancestor = ancestor.parent
                    val container = ancestor as? ScrollView ?: error("The actual '$label' SeekBar requires its native vertical scroll ancestor")
                    val viewport = Rect()
                    val visibleParent = container.getGlobalVisibleRect(viewport)
                    // Global clipping is root-relative; the native target and AX bounds use screen coordinates.
                    val rootOrigin = IntArray(2).also(decor::getLocationOnScreen)
                    if (visibleParent) viewport.offset(rootOrigin[0], rootOrigin[1])
                    val clipped = Rect()
                    if (slider.getGlobalVisibleRect(clipped)) clipped.offset(rootOrigin[0], rootOrigin[1]) else clipped.setEmpty()
                    val position = IntArray(2).also(slider::getLocationOnScreen)
                    val target = Rect(position[0], position[1], position[0] + slider.width, position[1] + slider.height)
                    lastNative = "slider=${System.identityHashCode(slider)} attached=${slider.isAttachedToWindow} shown=${slider.isShown} " +
                        "laidOut=${slider.isLaidOut} size=${slider.width}x${slider.height} target=$target clipped=$clipped; " +
                        "parent=${System.identityHashCode(container)} attached=${container.isAttachedToWindow} shown=${container.isShown} " +
                        "laidOut=${container.isLaidOut} size=${container.width}x${container.height} visible=$visibleParent viewport=$viewport y=${container.scrollY} rootOffset=${rootOrigin[0]},${rootOrigin[1]}"
                    assertTrue("The actual slider and parent must belong to this current React host",
                        slider.rootView === decor && container.rootView === decor)
                    // Android clears isLaidOut on reattachment even when RN retains a positive
                    // frame. Require stable measured geometry and a connected current AX source
                    // below; attachment alone, zero-size frames and invisible parents cannot pass.
                    if (slider.width <= 0 || slider.height <= 0 ||
                        !container.isAttachedToWindow || !container.isShown ||
                        container.width <= 0 || container.height <= 0 || !visibleParent || viewport.isEmpty) return@runOnMainSync
                    val source = container.createAccessibilityNodeInfo()
                    val sliderSource = slider.createAccessibilityNodeInfo()
                    val windowId = decor.createAccessibilityNodeInfo().windowId
                    if (windowId < 0 || source.windowId != windowId || sliderSource.windowId != windowId) return@runOnMainSync
                    result = SliderGeometry(slider, container, container.scrollY,
                        container.width, container.height, target, clipped, viewport, source, sliderSource, slider.isEnabled)
                }
                return result
            }
            fun sameLayout(first: SliderGeometry, second: SliderGeometry) =
                first.view === second.view && first.container === second.container && first.y == second.y &&
                    first.containerWidth == second.containerWidth && first.containerHeight == second.containerHeight &&
                    first.target == second.target && first.clipped == second.clipped && first.viewport == second.viewport &&
                    first.source == second.source && first.sliderSource == second.sliderSource && first.enabled == second.enabled
            var acquired: SliderGeometry? = null
            var previousLayout: SliderGeometry? = null
            try {
                journey.await("$cell actual '$label' nonzero visible parent layout and connected AX source",
                    maxOf(1L, navigationDeadline - SystemClock.elapsedRealtime())) {
                    val current = geometry() ?: run { previousLayout = null; return@await false }
                    val connected = journey.accessibilityNodes().filter { it == current.source && it.isVisibleToUser &&
                        it.packageName?.toString() == journey.context.packageName }
                    assertTrue("The measured slider parent must have at most one connected AX source", connected.size <= 1)
                    lastAccessibility = "parentSource=${current.source.hashCode()} window=${current.source.windowId} connectedVisible=${connected.size}"
                    val node = connected.singleOrNull()
                    if (node == null || !node.refresh() || !node.isVisibleToUser || node != current.source) {
                        previousLayout = null
                        return@await false
                    }
                    val previous = previousLayout
                    previousLayout = current
                    if (previous == null || !sameLayout(previous, current)) return@await false
                    acquired = current
                    true
                }
            } catch (failure: Throwable) {
                println("LAVA_UI_SLIDER_GEOMETRY_FAILURE cell=$cell label=$label native=[$lastNative] ax=[$lastAccessibility]")
                throw failure
            }
            val initial = checkNotNull(acquired)
            println("LAVA_UI_SLIDER_GEOMETRY_READY cell=$cell label=$label native=[$lastNative] ax=[$lastAccessibility]")
            var submitted = false
            var previousNavigation: SliderGeometry? = initial
            var ready: AccessibilityNodeInfo? = null
            journey.await("$cell actual '$label' SeekBar within its measured native viewport",
                maxOf(1L, navigationDeadline - SystemClock.elapsedRealtime())) {
                val before = geometry() ?: run { previousNavigation = null; return@await false }
                assertTrue("Slider navigation must retain the exact native control and parent",
                    before.view === initial.view && before.container === initial.container &&
                        before.source == initial.source && before.sliderSource == initial.sliderSource)
                val previous = previousNavigation
                previousNavigation = before
                if (previous == null || !sameLayout(previous, before)) return@await false
                val nodes = journey.accessibilityNodes()
                val connectedSlider = nodes.filter { node ->
                    node == before.sliderSource && node.packageName?.toString() == journey.context.packageName &&
                        ownLabel(node, label) && node.className?.toString()?.contains("SeekBar") == true
                }
                val connectedParent = nodes.filter { it == before.source && it.packageName?.toString() == journey.context.packageName }
                assertTrue("The measured slider must have at most one connected AX source", connectedSlider.size <= 1)
                assertTrue("The measured slider parent must have at most one connected AX source", connectedParent.size <= 1)
                val node = connectedSlider.singleOrNull() ?: return@await false
                val parent = connectedParent.singleOrNull() ?: return@await false
                if (!node.refresh() || node != before.sliderSource || !ownLabel(node, label) || !before.enabled || node.isEnabled != before.enabled ||
                    !parent.refresh() || parent != before.source || !parent.isVisibleToUser || !parent.isEnabled) return@await false
                lastAccessibility = "window=${node.windowId} sliderSource=${node.hashCode()} parentSource=${parent.hashCode()} " +
                    "visible=${node.isVisibleToUser} enabled=${node.isEnabled} actions=${node.actionList.map { it.id }} parentScrollable=${parent.isScrollable}"
                val confirmed = geometry() ?: return@await false
                if (!sameLayout(before, confirmed)) { previousNavigation = confirmed; return@await false }
                if (before.viewport.contains(before.target) && before.clipped.contains(before.target)) {
                    if (!node.isVisibleToUser) return@await false // Hydrate AX in place without replaying a reveal.
                    assertEquals("The connected slider identifies its full native screen bounds", before.target, Rect().also(node::getBoundsInScreen))
                    assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id })
                    ready = node
                    true
                }
                else {
                    if (submitted) return@await false // One accepted leaf reveal; no full-page oscillation or retry.
                    assertTrue("A vertical reveal cannot repair horizontal clipping or an oversized slider",
                        before.target.left >= before.viewport.left && before.target.right <= before.viewport.right &&
                            before.target.height() <= before.viewport.height())
                    val forward = before.target.bottom > before.viewport.bottom
                    assertTrue("The measured slider requires directional vertical scrolling", forward || before.target.top < before.viewport.top)
                    assertTrue("The exact connected slider parent must be scrollable", parent.isScrollable)
                    val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                    assertTrue("The exact connected slider must expose its public reveal action", node.actionList.any { it.id == action })
                    println("LAVA_UI_SLIDER_SCROLL_BEGIN cell=$cell window=${node.windowId} identity=${node.hashCode()} action=$action forward=$forward y=${before.y} target=${before.target} viewport=${before.viewport} ax=[$lastAccessibility]")
                    assertTrue("The exact native slider refused its one AX reveal", node.performAction(action))
                    submitted = true
                    var sample: SliderGeometry? = null
                    var moved: SliderGeometry? = null
                    val deadline = minOf(navigationDeadline, SystemClock.elapsedRealtime() + 1_500)
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val after = geometry()
                        if (after == null) { sample = null; SystemClock.sleep(100); continue }
                        assertTrue("A scroll may not substitute a different slider or parent",
                            after.view === before.view && after.container === before.container &&
                                after.source == before.source && after.sliderSource == before.sliderSource)
                        assertEquals("Slider parent width must remain settled", before.containerWidth, after.containerWidth)
                        assertEquals("Slider parent height must remain settled", before.containerHeight, after.containerHeight)
                        assertEquals("Slider viewport must settle before another input", before.viewport, after.viewport)
                        val directional = if (forward) after.y > before.y else after.y < before.y
                        if (directional && sample?.let { sameLayout(it, after) } == true) {
                            moved = after
                            break
                        }
                        sample = after
                        SystemClock.sleep(100)
                    }
                    assertNotNull("Accepted slider AX scrolling must have actual settled directional progress", moved)
                    val settled = checkNotNull(moved)
                    println("LAVA_UI_SLIDER_SCROLL_END cell=$cell submitted=$submitted beforeY=${before.y} afterY=${settled.y} target=${settled.target} viewport=${settled.viewport}")
                    previousNavigation = settled
                    false
                }
            }
            return checkNotNull(ready)
        }
        private data class GalleryViewport(val identity: Int, val y: Int, val height: Int,
            val contentHeight: Int, val backward: Boolean, val forward: Boolean)
        private data class GalleryTarget(val identity: Int, val top: Int, val bottom: Int) {
            val center: Int get() = top + (bottom - top) / 2
        }
        private fun nativeTarget(label: String?, group: String?, checkable: Boolean = false): GalleryTarget? {
            var target: GalleryTarget? = null
            journey.instrumentation.runOnMainSync {
                val gallery = views(journey.requireActivity().window.decorView).filterIsInstance<ScrollView>()
                    .singleOrNull { it.getTag(com.facebook.react.R.id.react_test_id) == "lava-component-gallery" && it.isShown }
                    ?: return@runOnMainSync
                val content = gallery.getChildAt(0) ?: return@runOnMainSync
                val scope = if (group == null) content else views(content)
                    .singleOrNull { it.contentDescription?.toString() == group && it.isShown } ?: return@runOnMainSync
                val candidate = if (label == null) scope else if (checkable) {
                    // A clipped row title can be visible while its native switch is below the
                    // compact viewport. Navigate to the actual uniquely labelled control.
                    views(scope).filterIsInstance<CompoundButton>().singleOrNull {
                        it.contentDescription?.toString().equals(label, ignoreCase = true) && it.isShown
                    }
                } else {
                    // Prefer its actual text leaf. A parent can repeat the same accessibility
                    // label, while unrelated duplicated choice labels require their exact group.
                    val text = textViews(scope).filter { it.text.toString().equals(label, ignoreCase = true) && it.isShown }
                    if (text.isNotEmpty()) text.singleOrNull() else views(scope)
                        .singleOrNull { it.contentDescription?.toString().equals(label, ignoreCase = true) && it.isShown }
                } ?: return@runOnMainSync
                if (candidate.width <= 0 || candidate.height <= 0) return@runOnMainSync
                val origin = IntArray(2).also(gallery::getLocationOnScreen)
                val position = IntArray(2).also(candidate::getLocationOnScreen)
                val top = position[1] - origin[1] + gallery.scrollY
                target = GalleryTarget(System.identityHashCode(candidate), top, top + candidate.height)
            }
            return target
        }
        private fun galleryViewport(): GalleryViewport? {
            var result: GalleryViewport? = null
            journey.instrumentation.runOnMainSync {
                val gallery = views(journey.requireActivity().window.decorView).filterIsInstance<ScrollView>()
                    .singleOrNull { it.getTag(com.facebook.react.R.id.react_test_id) == "lava-component-gallery" &&
                        it.isShown && it.getGlobalVisibleRect(Rect()) }
                gallery?.let { view ->
                    val visible = Rect().also { view.getGlobalVisibleRect(it) }
                    result = GalleryViewport(System.identityHashCode(view), view.scrollY,
                        maxOf(1, visible.height() - view.paddingTop - view.paddingBottom),
                        view.getChildAt(0)?.height ?: 0, view.canScrollVertically(-1), view.canScrollVertically(1))
                }
            }
            return result
        }
        private fun scrollToTop() {
            val initial = galleryViewport()
            if (initial == null) { journey.scrollToTop(); return }
            // The actual compact viewport and offset determine the bound. Twelve assumed
            // pages can leave a tall gallery midway down in landscape.
            val limit = (initial.y / initial.height + 1) * 2 + 4
            repeat(limit) { if (!scrollPage(forward = false)) return }
            assertFalse("$cell gallery failed to reach its actual upper boundary: ${galleryViewport()}",
                galleryViewport()?.backward == true)
        }
        private fun scrollPage(forward: Boolean = true): Boolean {
            val before = galleryViewport() ?: return journey.scrollPage(forward)
            if (!(if (forward) before.forward else before.backward)) return false
            val target = journey.accessibilityNodes().firstOrNull { node ->
                node.isVisibleToUser && node.isScrollable && node.viewIdResourceName == "lava-component-gallery"
            }
            val missingTargetDiagnostic = if (target == null) {
                journey.accessibilityNodes().filter { it.isVisibleToUser && it.isScrollable }.joinToString {
                    "id=${it.viewIdResourceName},class=${it.className},window=${it.windowId}"
                }
            } else ""
            assertNotNull("$cell requires its exact gallery AX scroll container. Scrollables: $missingTargetDiagnostic", target)
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val liveTarget = checkNotNull(target)
            assertTrue("$cell gallery target must expose the actual ScrollView role", liveTarget.className.toString().contains("ScrollView"))
            println("LAVA_UI_SCROLL_BEGIN cell=$cell id=${liveTarget.viewIdResourceName} class=${liveTarget.className} window=${liveTarget.windowId} forward=$forward before=$before")
            val accepted = liveTarget.performAction(action)
            assertTrue("$cell gallery refused its single scroll action: $before forward=$forward", accepted)
            val deadline = SystemClock.elapsedRealtime() + 1_500
            var last = before
            var stableSince = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() < deadline) {
                val current = checkNotNull(galleryViewport()) { "$cell gallery retired during its scroll action" }
                assertEquals("$cell scroll must remain attached to the same native gallery", before.identity, current.identity)
                if (current.y != last.y) stableSince = SystemClock.elapsedRealtime()
                last = current
                val moved = if (forward) current.y > before.y else current.y < before.y
                if (moved && SystemClock.elapsedRealtime() - stableSince >= 100) {
                    println("LAVA_UI_SCROLL cell=$cell id=${liveTarget.viewIdResourceName} window=${liveTarget.windowId} forward=$forward accepted=$accepted beforeY=${before.y} afterY=${current.y} viewportPx=${current.height} contentPx=${current.contentHeight}")
                    return true
                }
                SystemClock.sleep(25)
            }
            println("LAVA_UI_SCROLL_FAILURE cell=$cell before=$before after=$last forward=$forward window=${liveTarget.windowId}")
            throw AssertionError("$cell gallery accepted a scroll without settled directional movement: before=$before after=$last forward=$forward window=${liveTarget.windowId}")
        }
        fun bringLegalLicenseActionIntoView() {
            val label = "Full license texts"
            // Legal has 67 packaged notices, so a fixed 20-second gallery fallback
            // can stop midway down its actual ScrollView. Budget from this native
            // viewport and content extent, retaining one exact current container.
            val activity = journey.requireActivity()
            val generation = checkNotNull(journey.application.foregroundProduct.state.value.graphGeneration)
            var legal: ScrollView? = null
            journey.instrumentation.runOnMainSync {
                legal = views(activity.window.decorView).filterIsInstance<ScrollView>().singleOrNull {
                    it.isShown && it.hasWindowFocus() && it.getGlobalVisibleRect(Rect()) && it.height > 0
                }
            }
            val scroll = checkNotNull(legal) { "$cell requires one actual visible Legal ScrollView" }
            retainedLegalScroll = activity to scroll
            var visibilityMillis = 0L
            var visibilityReads = 0
            fun actionVisible(): Boolean {
                val started = SystemClock.elapsedRealtime()
                try { return journey.visible(label) } finally {
                    visibilityMillis += SystemClock.elapsedRealtime() - started; ++visibilityReads
                }
            }
            if (actionVisible()) return
            fun viewport(): GalleryViewport {
                var result: GalleryViewport? = null
                journey.instrumentation.runOnMainSync {
                    assertTrue("$cell must retain the exact Legal Activity", activity === journey.requireActivity())
                    assertEquals("$cell cannot adopt a successor Legal graph", generation,
                        journey.application.foregroundProduct.state.value.graphGeneration)
                    assertTrue("$cell Legal ScrollView must remain mounted, visible and foreground",
                        scroll.isAttachedToWindow && scroll.isShown && scroll.hasWindowFocus())
                    assertTrue("$cell Legal viewport must retain the actual current native host",
                        journey.application.reactHost.currentReactContext?.currentActivity === activity &&
                            journey.application.nativeVisitHosts.current()?.owner === activity && activity.foreground)
                    val visible = Rect().also { assertTrue(scroll.getGlobalVisibleRect(it)) }
                    result = GalleryViewport(System.identityHashCode(scroll), scroll.scrollY,
                        maxOf(1, visible.height() - scroll.paddingTop - scroll.paddingBottom),
                        scroll.getChildAt(0)?.height ?: 0, scroll.canScrollVertically(-1), scroll.canScrollVertically(1))
                }
                return checkNotNull(result)
            }
            val initial = viewport()
            val pages = (maxOf(0, initial.contentHeight - initial.height - initial.y).toLong() + initial.height - 1) / initial.height + 2
            var submitted = 0L
            var connectedScroll: AccessibilityNodeInfo? = null
            var acquisitionMillis = 0L
            var refreshMillis = 0L
            val budgetMillis = 20_000L + pages * 3_000L
            val started = SystemClock.elapsedRealtime()
            try {
                journey.await("$cell actual Legal license action", timeoutMillis = budgetMillis) {
                    val before = viewport()
                    // The settled post-scroll scan already tested this viewport. Avoid
                    // walking every notice again before the next measured scroll.
                    // At the lower boundary, still admit a freshly rendered action
                    // before asserting that the exact viewport cannot scroll further.
                    if (!before.forward && actionVisible()) {
                        viewport() // Revalidate the original owner after the boundary AX read.
                        true
                    } else {
                        assertTrue("$cell reached the actual Legal end without its license action", before.forward)
                        assertTrue("$cell exceeded the measured Legal scroll bound", submitted < pages)
                        var bounds: Rect? = null
                        var nativeSource: AccessibilityNodeInfo? = null
                        journey.instrumentation.runOnMainSync {
                            assertTrue("$cell scroll must retain the actual current native host",
                                journey.application.reactHost.currentReactContext?.currentActivity === activity &&
                                    journey.application.nativeVisitHosts.current()?.owner === activity && activity.foreground)
                            nativeSource = scroll.createAccessibilityNodeInfo()
                            bounds = Rect().also {
                                assertTrue(scroll.getGlobalVisibleRect(it))
                                val origin = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
                                it.offset(origin[0], origin[1])
                            }
                        }
                        if (connectedScroll == null) {
                            val acquisitionStarted = SystemClock.elapsedRealtime()
                            try {
                                connectedScroll = journey.accessibilityNodes().filter {
                                    it.isVisibleToUser && it.isScrollable && it.className?.toString()?.contains("ScrollView") == true &&
                                        Rect().also(it::getBoundsInScreen) == bounds && it == nativeSource
                                }.singleOrNull()
                            } finally { acquisitionMillis += SystemClock.elapsedRealtime() - acquisitionStarted }
                        }
                        val exact = checkNotNull(connectedScroll) { "$cell requires the exact current Legal AX scroll viewport" }
                        val refreshStarted = SystemClock.elapsedRealtime()
                        val refreshed = exact.refresh()
                        refreshMillis += SystemClock.elapsedRealtime() - refreshStarted
                        assertTrue("$cell Legal scroll target must refresh", refreshed)
                        assertTrue("$cell cached Legal scroll source must retain its current native window and bounds",
                            exact == nativeSource && exact.windowId == checkNotNull(nativeSource).windowId &&
                                exact.isVisibleToUser && exact.isScrollable && Rect().also(exact::getBoundsInScreen) == bounds)
                        viewport() // Revalidate native owner/window after the connected AX refresh.
                        assertTrue("$cell Legal refused its one scroll action", exact.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                        submitted += 1
                        val deadline = SystemClock.elapsedRealtime() + 1_500
                        var last = before
                        var stableSince = SystemClock.elapsedRealtime()
                        var settled = false
                        while (SystemClock.elapsedRealtime() < deadline) {
                            val current = viewport()
                            if (current.y != last.y) stableSince = SystemClock.elapsedRealtime()
                            last = current
                            if (current.y > before.y && SystemClock.elapsedRealtime() - stableSince >= 100) { settled = true; break }
                            SystemClock.sleep(25)
                        }
                        assertTrue("$cell Legal accepted a scroll without settled native movement: before=$before after=$last", settled)
                        println("LAVA_UI_LEGAL_SCROLL cell=$cell submitted=$submitted beforeY=${before.y} afterY=${last.y} viewportPx=${last.height} contentPx=${last.contentHeight}")
                        // The final measured scroll can reveal the action at this
                        // deadline edge. Check it now rather than unconditionally
                        // postponing admission to another await iteration.
                        val revealed = actionVisible()
                        if (revealed) viewport() // Retain the exact mounted Legal owner after the AX read.
                        revealed
                    }
                }
            } catch (failure: Throwable) {
                val receipt = runCatching {
                    val current = viewport()
                    var currentHost = false
                    var ready = false
                    journey.instrumentation.runOnMainSync {
                        currentHost = journey.application.reactHost.currentReactContext?.currentActivity === activity &&
                            journey.application.nativeVisitHosts.current()?.owner === activity && activity.foreground
                        ready = activity.isPresentationReady(generation)
                    }
                    JSONObject().put("schema", 1).put("currentHost", currentHost).put("ready", ready).put("pages", pages).put("submitted", submitted)
                        .put("budgetMillis", budgetMillis).put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                        .put("visibilityReads", visibilityReads).put("visibilityMillis", visibilityMillis)
                        .put("acquisitionMillis", acquisitionMillis).put("refreshMillis", refreshMillis)
                        .put("initialY", initial.y).put("initialHeight", initial.height).put("initialContent", initial.contentHeight)
                        .put("currentY", current.y).put("currentHeight", current.height).put("currentContent", current.contentHeight)
                        .put("backward", current.backward).put("forward", current.forward)
                }.getOrElse { JSONObject().put("schema", 1).put("captureFailed", true) }
                val message = "LAVA_UI_LEGAL_NAVIGATION $receipt"
                failure.addSuppressed(AssertionError(message).apply { stackTrace = emptyArray() })
                println(message)
                throw failure
            }
        }
        fun returnLegalToMeasuredTop() {
            val (activity, scroll) = checkNotNull(retainedLegalScroll) { "$cell requires its original Legal viewport" }
            // Native owner dismissal precedes the native-stack close animation.
            // Observe the original viewport's actual return before any scroll action.
            journey.await("$cell original Legal viewport resumed") {
                var resumed = false
                journey.instrumentation.runOnMainSync {
                    assertTrue("$cell return refuses a successor Activity", activity === journey.requireActivity())
                    resumed = scroll.isAttachedToWindow && scroll.isShown && scroll.hasWindowFocus() &&
                        scroll.height > 0 && scroll.getGlobalVisibleRect(Rect())
                }
                resumed
            }
            fun viewport(): GalleryViewport {
                var result: GalleryViewport? = null
                journey.instrumentation.runOnMainSync {
                    assertTrue("$cell return retains its exact Legal Activity", activity === journey.requireActivity())
                    assertTrue("$cell original Legal scroll must be attached, shown and foreground",
                        scroll.isAttachedToWindow && scroll.isShown && scroll.hasWindowFocus())
                    val visible = Rect().also { assertTrue(scroll.getGlobalVisibleRect(it)) }
                    result = GalleryViewport(System.identityHashCode(scroll), scroll.scrollY,
                        maxOf(1, visible.height() - scroll.paddingTop - scroll.paddingBottom),
                        scroll.getChildAt(0)?.height ?: 0, scroll.canScrollVertically(-1), scroll.canScrollVertically(1))
                }
                return checkNotNull(result)
            }
            val initial = viewport()
            val pages = (initial.y.toLong() + initial.height - 1) / initial.height + 2
            repeat(pages.toInt()) { index ->
                val before = viewport()
                if (!before.backward) return
                var bounds: Rect? = null
                journey.instrumentation.runOnMainSync {
                    bounds = Rect().also {
                        assertTrue(scroll.getGlobalVisibleRect(it))
                        val origin = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
                        it.offset(origin[0], origin[1])
                    }
                }
                val target = journey.accessibilityNodes().filter {
                    it.isVisibleToUser && it.isScrollable && it.className?.toString()?.contains("ScrollView") == true &&
                        Rect().also(it::getBoundsInScreen) == bounds
                }.singleOrNull()
                val exact = checkNotNull(target) { "$cell return requires the original Legal AX viewport" }
                assertTrue("$cell Legal return target must refresh", exact.refresh())
                assertTrue("$cell Legal refused its one return scroll", exact.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                val deadline = SystemClock.elapsedRealtime() + 1_500
                var last = before
                var stableSince = SystemClock.elapsedRealtime()
                var settled = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    val current = viewport()
                    if (current.y != last.y) stableSince = SystemClock.elapsedRealtime()
                    last = current
                    if (current.y < before.y && SystemClock.elapsedRealtime() - stableSince >= 100) { settled = true; break }
                    SystemClock.sleep(25)
                }
                assertTrue("$cell Legal return accepted a scroll without settled native movement: before=$before after=$last", settled)
                println("LAVA_UI_LEGAL_RETURN cell=$cell submitted=${index + 1} beforeY=${before.y} afterY=${last.y} viewportPx=${last.height} contentPx=${last.contentHeight}")
            }
            assertFalse("$cell return exhausted its measured bound before the actual Legal top: ${viewport()}", viewport().backward)
        }
        private fun navigateGallery(message: String, label: String? = null, group: String? = null, checkable: Boolean = false,
            targetVisible: () -> Boolean) {
            val target = nativeTarget(label, group, checkable)
            // Geometry only chooses direction for one real, unambiguous mounted target. It never
            // substitutes for the final fresh AX label/role/state assertion or submits a click.
            if (target == null) scrollToTop()
            val initial = galleryViewport()
            if (initial == null) {
                journey.await(message) { if (targetVisible()) true else { journey.scrollPage(); false } }
                return
            }
            // A 175 px landscape viewport can require 23 genuine pages of this same gallery;
            // the ordinary 20 s control wait expired after only 11 settled page submissions.
            // Bound navigation by the actual remaining native geometry, allowing two boundary
            // pages, and budget the existing 1.5 s motion proof plus fresh AX acquisition per page.
            val destination = target?.let {
                (it.center - initial.height / 2).coerceIn(0, maxOf(0, initial.contentHeight - initial.height))
            }
            val remaining = if (destination == null) maxOf(0, initial.contentHeight - initial.height - initial.y).toLong()
                else kotlin.math.abs(destination.toLong() - initial.y)
            val pages = (remaining + initial.height - 1) / initial.height + 2
            val navigationMillis = 20_000L + pages * 3_000L
            var submitted = 0L
            println("LAVA_UI_NAVIGATION_BEGIN cell=$cell target=$message nativeTarget=$target viewport=$initial pageLimit=$pages timeoutMs=$navigationMillis")
            journey.await(message, timeoutMillis = navigationMillis) {
                if (targetVisible()) true else {
                    val current = checkNotNull(galleryViewport()) { "$cell gallery retired during $message" }
                    assertEquals("$cell navigation must retain the actual native gallery", initial.identity, current.identity)
                    val liveTarget = if (target == null) null else nativeTarget(label, group, checkable)
                    if (target != null) assertEquals("$cell must retain its unambiguous native target during $message",
                        target.identity, liveTarget?.identity)
                    val positioned = liveTarget != null && if (checkable) {
                        liveTarget.center in current.y until current.y + current.height
                    } else liveTarget.bottom > current.y && liveTarget.top < current.y + current.height
                    if (positioned) {
                        // Its genuine native bounds intersect this viewport. Wait for fresh AX
                        // hydration in place, rather than scrolling past the target while pending.
                        journey.await("$message within measured native target $liveTarget", condition = targetVisible)
                        return@await true
                    }
                    val forward = liveTarget == null || liveTarget.center > current.y + current.height / 2
                    assertTrue("$cell exceeded its measured page limit for $message: initial=$initial current=$current submitted=$submitted limit=$pages",
                        submitted < pages)
                    if (scrollPage(forward)) {
                        submitted++
                        false
                    } else {
                        // A real bottom ends scrolling. Give the same ordinary accessibility
                        // hydration allowance, with no further action or replay at that boundary.
                        journey.await("$message at native gallery boundary $current forward=$forward", condition = targetVisible)
                        true
                    }
                }
            }
            println("LAVA_UI_NAVIGATION_END cell=$cell target=$message submitted=$submitted viewport=${galleryViewport()}")
        }
        fun bringIntoView(label: String) {
            if (journey.visible(label, prefix = true)) return
            navigateGallery("$cell visible '$label'", label = label) { journey.visible(label, prefix = true) }
        }
        fun bringGroupIntoView(group: String) {
            if (groupBounds(group) != null) return
            navigateGallery("$cell visible native choice group '$group'", group = group) { groupBounds(group) != null }
        }
        private fun node(label: String, group: String? = null, checkable: Boolean = false, clickable: Boolean = false): AccessibilityNodeInfo {
            fun find() = journey.accessibilityNodes().firstOrNull { it.isVisibleToUser && matches(it, label) &&
                (!checkable || it.isCheckable) && (!clickable || it.isClickable) && (group == null || inGroup(it, group)) }
            var found = find()
            if (found == null) {
                navigateGallery("$cell live control '$label'${group?.let { " in $it" }.orEmpty()}", label = label, group = group, checkable = checkable) {
                    found = find()
                    found != null
                }
            }
            return checkNotNull(found)
        }
        fun heading(label: String, expected: Boolean) {
            bringIntoView(label)
            journey.await("$cell heading '$label'=$expected") {
                journey.accessibilityNodes().any { it.isVisibleToUser && matches(it, label) && it.isHeading == expected }
            }
        }
        private fun readyNode(label: String, enabled: Boolean, group: String? = null,
            checkable: Boolean = false, role: String? = null, clickable: Boolean = false): AccessibilityNodeInfo {
            // First reveal the real target regardless of its transitional state. Once visible,
            // wait on fresh AX state without scrolling past it or replaying its triggering action.
            node(label, group, checkable)
            var ready: AccessibilityNodeInfo? = null
            journey.await("$cell control '$label' enabled=$enabled role=$role clickable=$clickable") {
                ready = journey.accessibilityNodes().firstOrNull { it.isVisibleToUser && matches(it, label) &&
                    it.isEnabled == enabled && (!checkable || it.isCheckable) &&
                    (!clickable || it.isClickable) && (role == null || it.className.toString().contains(role)) &&
                    (group == null || inGroup(it, group)) }
                ready != null
            }
            return checkNotNull(ready)
        }
        fun control(label: String, enabled: Boolean): AccessibilityNodeInfo =
            readyNode(label, enabled, role = "Button").also {
                assertEquals("$cell '$label' enabled state", enabled, it.isEnabled)
                assertTrue("$cell '$label' must expose its button role", it.className.toString().contains("Button"))
            }
        fun checkSwitch(label: String, checked: Boolean, enabled: Boolean): AccessibilityNodeInfo {
            var target: AccessibilityNodeInfo? = null
            try {
                // Reveal the Switch itself, rather than treating its independently rendered
                // title as proof that a native control is within the real AX viewport.
                node(label, checkable = true)
                journey.await("$cell native switch '$label' checked=$checked enabled=$enabled") {
                    target = journey.accessibilityNodes().firstOrNull { it.isVisibleToUser && matches(it, label) && it.isCheckable &&
                        it.isChecked == checked && it.isEnabled == enabled }
                    target != null
                }
            } catch (failure: AssertionError) {
                val metadata = switchMetadata(label)
                println("LAVA_UI_SWITCH_FAILURE cell=$cell label=$label expectedChecked=$checked expectedEnabled=$enabled $metadata")
                throw AssertionError("${failure.message}. Native/AX switch: $metadata", failure)
            }
            return checkNotNull(target).also {
                assertTrue("$cell '$label' must be the Android switch", it.className.toString().contains("Switch"))
                println("LAVA_UI_SWITCH cell=$cell label=$label checked=${it.isChecked} enabled=${it.isEnabled} ${switchMetadata(label)}")
            }
        }
        private fun switchMetadata(label: String): String {
            var native = ""
            journey.instrumentation.runOnMainSync {
                native = views(journey.requireActivity().window.decorView).filterIsInstance<CompoundButton>()
                    .filter { it.contentDescription?.toString().equals(label, ignoreCase = true) }.take(4).joinToString(" | ") {
                        val origin = IntArray(2).also(it::getLocationOnScreen)
                        val full = Rect(origin[0], origin[1], origin[0] + it.width, origin[1] + it.height)
                        val clipped = Rect()
                        val visible = it.getGlobalVisibleRect(clipped)
                        "identity=${System.identityHashCode(it)},class=${it.javaClass.name},shown=${it.isShown}," +
                            "checked=${it.isChecked},enabled=${it.isEnabled},bounds=$full,globalVisible=$visible,clipped=$clipped"
                    }
            }
            val ax = journey.accessibilityNodes().filter { matches(it, label) }.take(8).joinToString(" | ") {
                val bounds = Rect().also(it::getBoundsInScreen)
                "window=${it.windowId},class=${it.className},visible=${it.isVisibleToUser},checkable=${it.isCheckable}," +
                    "checked=${it.isChecked},enabled=${it.isEnabled},bounds=$bounds"
            }
            return "viewport=${galleryViewport()} native=[$native] ax=[$ax]"
        }
        fun activate(label: String, group: String? = null, checkable: Boolean = false) {
            val role = if (checkable) "Switch" else if (group == null) "Button" else null
            val target = readyNode(label, enabled = true, group = group, checkable = checkable, role = role, clickable = true)
            assertTrue("$cell '$label' must be enabled", target.isEnabled)
            assertTrue("$cell '$label' must be clickable", target.isClickable)
            val bounds = Rect().also { target.getBoundsInScreen(it) }
            val baselineSelected = target.isSelected
            val baselineChecked = target.isChecked
            val accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            println("LAVA_UI_MATRIX_ACTION cell=$cell label=$label group=$group window=${target.windowId} class=${target.className} bounds=$bounds baselineSelected=$baselineSelected baselineChecked=$baselineChecked accepted=$accepted")
            assertTrue("$cell '$label' must accept exactly one actual accessibility action", accepted)
        }
        fun choice(group: String, label: String, selected: Boolean, enabled: Boolean) {
            node(label, group)
            journey.await("$cell choice '$group/$label' selected=$selected enabled=$enabled") {
                val row = journey.accessibilityNodes().firstOrNull { it.isVisibleToUser && matches(it, label) && inGroup(it, group) }
                row != null && row.isSelected == selected && row.isEnabled == enabled
            }
        }
        fun selectedRow(label: String, selected: Boolean, enabled: Boolean): AccessibilityNodeInfo {
            bringIntoView(label)
            var target: AccessibilityNodeInfo? = null
            journey.await("$cell row '$label' selected=$selected enabled=$enabled") {
                target = journey.accessibilityNodes().firstOrNull { it.isVisibleToUser && matches(it, label) &&
                    it.isSelected == selected && it.isEnabled == enabled }
                target != null
            }
            return checkNotNull(target)
        }
        fun text(label: String, fontDp: Float, theme: String, inkRole: InkRole = InkRole.PRIMARY) {
            bringIntoView(label)
            var measurement: JSONObject? = null
            var observed: JSONObject? = null
            try {
                journey.await("$cell measured native text '$label'") {
                    journey.instrumentation.runOnMainSync {
                        val activity = journey.requireActivity()
                        val view = textViews(activity.window.decorView).firstOrNull { it.text.toString() == label && it.isShown &&
                            it.getGlobalVisibleRect(Rect()) && it.layout != null }
                        if (view != null) {
                            val layout = view.layout
                            val density = view.resources.displayMetrics.density
                            // presentation.Text applies the owner's role scale and sets
                            // allowFontScaling=false. RN 0.87 TextAttributeProps.setFontSize
                            // ceil-rounds DIP to whole physical pixels before installing its
                            // ReactAbsoluteSizeSpan; 15 * 1.35 at density 1 paints at 21px.
                            val expectedFontPixels = kotlin.math.ceil(fontDp * density)
                            val runs = paintedTextRuns(view)
                            observed = JSONObject().put("cell", cell).put("label", label).put("inkRole", inkRole.name)
                                .put("widthDp", view.width / density).put("heightDp", view.height / density)
                                .put("lines", layout.lineCount).put("widgetFontDp", view.textSize / density)
                                .put("expectedFontPixels", expectedFontPixels).put("density", density)
                                .put("widgetInk", view.currentTextColor).put("paintedRuns", JSONArray(runs.map { run ->
                                    JSONObject().put("start", run.start).put("end", run.end)
                                        .put("fontDp", run.paint.textSize / density).put("ink", run.paint.color)
                                }))
                            if (cell == "shipping-slider-stepper-menu" && label == "This is how your text will look.") {
                                val position = IntArray(2).also(view::getLocationOnScreen)
                                observed!!.put("nativeIdentity", System.identityHashCode(view)).put("nativeClass", view.javaClass.name)
                                    .put("nativeWindow", view.createAccessibilityNodeInfo().windowId)
                                    .put("layoutIdentity", System.identityHashCode(layout)).put("layoutClass", layout.javaClass.name)
                                    .put("layoutWidthPx", layout.width).put("layoutHeightPx", layout.height)
                                    .put("usableWidthPx", view.width - view.totalPaddingLeft - view.totalPaddingRight)
                                    .put("paddingPx", JSONArray(listOf(view.totalPaddingLeft, view.totalPaddingTop, view.totalPaddingRight, view.totalPaddingBottom)))
                                    .put("nativeBoundsScreenPx", JSONArray(listOf(position[0], position[1], position[0] + view.width, position[1] + view.height)))
                                    .put("textContentRectPx", JSONArray(listOf(view.totalPaddingLeft, view.totalPaddingTop,
                                        view.width - view.totalPaddingRight, view.height - view.totalPaddingBottom)))
                                    .put("lineFactsTruncated", layout.lineCount > 8)
                                    .put("lineFacts", JSONArray((0 until minOf(layout.lineCount, 8)).map { line ->
                                        JSONObject().put("line", line).put("start", layout.getLineStart(line))
                                            .put("end", layout.getLineEnd(line)).put("visibleEnd", layout.getLineVisibleEnd(line))
                                            .put("widthPx", layout.getLineWidth(line)).put("maxPx", layout.getLineMax(line))
                                            .put("leftPx", layout.getLineLeft(line)).put("rightPx", layout.getLineRight(line))
                                            .put("topPx", layout.getLineTop(line)).put("bottomPx", layout.getLineBottom(line))
                                            .put("ellipsisCount", layout.getEllipsisCount(line))
                                    }))
                            }
                            assertTrue("$cell '$label' requires actual painted characters", runs.isNotEmpty())
                            if (runs.any { kotlin.math.abs(it.paint.textSize - expectedFontPixels) > 0.6f * density }) return@runOnMainSync
                            if (runs.any { it.paint.color != expectedInk(journey, theme, inkRole) }) return@runOnMainSync
                            assertTrue("$cell '$label' requires real native layout", layout.lineCount > 0 && view.width > 0 && view.height > 0)
                            assertEquals("$cell '$label' must lay out its complete text", view.text.length, layout.getLineEnd(layout.lineCount - 1))
                            val usableWidth = view.width - view.totalPaddingLeft - view.totalPaddingRight
                            repeat(layout.lineCount) { line ->
                                assertEquals("$cell '$label' must not ellipsize", 0, layout.getEllipsisCount(line))
                                // Layout.drawText excludes unpainted line-end spaces. Check its
                                // public drawn extents, including both edges, with the same 2px tolerance.
                                val left = layout.getLineLeft(line)
                                val right = layout.getLineRight(line)
                                assertTrue("$cell '$label' must fit its measured width",
                                    left >= -2f && right <= usableWidth + 2f && right >= left &&
                                        right - left <= usableWidth + 2f && layout.getLineMax(line) <= usableWidth + 2f)
                            }
                            assertTrue("$cell '$label' must fit its measured height",
                                layout.getLineBottom(layout.lineCount - 1) <= view.height - view.totalPaddingTop - view.totalPaddingBottom + 2)
                            measurement = observed
                        }
                    }
                    measurement != null
                }
            } catch (failure: AssertionError) {
                throw AssertionError("${failure.message}. Native text: $observed expectedFontDp=$fontDp expectedTheme=$theme expectedInkRole=$inkRole", failure)
            }
            println("LAVA_UI_NATIVE_TEXT $measurement")
        }
        fun recordViewport() {
            journey.instrumentation.runOnMainSync {
                val configuration = journey.requireActivity().resources.configuration
                assertTrue("$cell must exercise a compact emulator viewport",
                    minOf(configuration.screenWidthDp, configuration.screenHeightDp) < 600)
                println("LAVA_UI_VIEWPORT cell=$cell widthDp=${configuration.screenWidthDp} heightDp=${configuration.screenHeightDp} orientation=${configuration.orientation} night=${configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK} finalRow=Locked-option")
            }
        }
        private fun matches(node: AccessibilityNodeInfo, label: String): Boolean = listOf(node.text, node.contentDescription).any {
            val text = it?.toString().orEmpty()
            text.equals(label, ignoreCase = true) || text.startsWith("$label,", ignoreCase = true) || text.startsWith("$label\n", ignoreCase = true)
        }
        private fun inGroup(node: AccessibilityNodeInfo, group: String): Boolean {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth++ < 20) {
                val current = parent
                if (!current.refresh()) break
                if (matches(current, group)) return true
                parent = current.parent
            }
            // Android may prune a non-focusable tablist parent from the AX tree. Disambiguate
            // duplicate option labels by its real native layout, retaining the actual AX child.
            val bounds = groupBounds(group) ?: return false
            val target = Rect().also { node.getBoundsInScreen(it) }
            return !target.isEmpty && bounds.contains(target.centerX(), target.centerY())
        }
        private fun groupBounds(group: String): Rect? {
            var bounds: Rect? = null
            journey.instrumentation.runOnMainSync {
                val view = views(journey.requireActivity().window.decorView).firstOrNull {
                    it.contentDescription?.toString() == group && it.isShown
                }
                view?.let { candidate ->
                    if (candidate.getGlobalVisibleRect(Rect())) {
                        val position = IntArray(2).also(candidate::getLocationOnScreen)
                        bounds = Rect(position[0], position[1], position[0] + candidate.width, position[1] + candidate.height)
                    }
                }
            }
            return bounds
        }
    }

    private enum class InkRole(val resource: Int) {
        PRIMARY(AppResources.color.lava_rn_primarytext),
        SECONDARY(AppResources.color.lava_rn_secondarytext),
    }

    companion object {
        private fun ownLabel(node: AccessibilityNodeInfo, label: String): Boolean = listOf(node.text, node.contentDescription).any {
            val actual = it?.toString().orEmpty()
            actual.equals(label, ignoreCase = true) || actual.startsWith("$label,", ignoreCase = true)
        }
        private fun rotate(journey: ReactEmulatorJourney, orientation: Int) {
            journey.instrumentation.runOnMainSync { journey.requireActivity().requestedOrientation = orientation }
            val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
            journey.await("actual orientation $expected") {
                var actual = 0
                journey.instrumentation.runOnMainSync { actual = journey.requireActivity().resources.configuration.orientation }
                actual == expected
            }
        }
        private fun nightMode(theme: String): Int = if (theme == "dark") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        private fun awaitTheme(journey: ReactEmulatorJourney, theme: String) {
            journey.await("actual Android night configuration for $theme") {
                var mode = 0
                journey.instrumentation.runOnMainSync { mode = journey.requireActivity().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK }
                mode == nightMode(theme)
            }
        }
        private fun expectedInk(journey: ReactEmulatorJourney, theme: String, role: InkRole = InkRole.PRIMARY): Int {
            val activity = journey.requireActivity()
            val expected = Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode(theme)
            }
            return activity.createConfigurationContext(expected).getColor(role.resource)
        }
        private data class PaintedTextRun(val start: Int, val end: Int, val paint: TextPaint)
        private fun paintedTextRuns(view: TextView): List<PaintedTextRun> {
            val text = view.text
            val spans = text as? Spanned
            val base = TextPaint(view.paint).apply { color = view.currentTextColor }
            // RN TextLayoutManager applies ReactForegroundColorSpan and ReactAbsoluteSizeSpan.
            // Public CharacterStyle.updateDrawState follows those actual native drawing overrides;
            // the widget's default color/font alone is not the painted React text.
            return buildList {
                var start = 0
                while (start < text.length) {
                    val end = spans?.nextSpanTransition(start, text.length, CharacterStyle::class.java) ?: text.length
                    val paint = TextPaint(base)
                    spans?.getSpans(start, end, CharacterStyle::class.java)?.forEach { it.updateDrawState(paint) }
                    add(PaintedTextRun(start, end, paint))
                    start = end
                }
            }
        }
        private fun views(view: View): List<View> = buildList {
            add(view)
            if (view is ViewGroup) repeat(view.childCount) { addAll(views(view.getChildAt(it))) }
        }
        private fun textViews(view: View): List<TextView> = views(view).filterIsInstance<TextView>()
        private fun resumedActivity(journey: ReactEmulatorJourney): Activity {
            var activity: Activity? = null
            journey.instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            }
            return checkNotNull(activity)
        }
    }
}

private data class LocaleRouteLabels(val tag: String, val wire: String, val settings: String, val yourLava: String,
    val privacy: String, val logs: String, val legal: String, val search: String, val noMatch: String,
    val customization: String, val appearance: String, val matchSystem: String, val guard: String,
    val today: String, val custom: String, val activity: String)

// Independent expected rendered copy from the frozen catalog; never read from a test snapshot.
private val LOCALE_ROUTES = listOf(
    LocaleRouteLabels("en", "en", "Settings", "Your Lava", "Privacy & data", "Local logs", "Legal notices", "Search notices", "No matching notices", "Customization", "Appearance", "Match system", "Guard", "Today", "Custom", "Activity"),
    LocaleRouteLabels("de", "de", "Einstellungen", "Dein Lava", "Datenschutz & Daten", "Lokale Protokolle", "Rechtliche Hinweise", "Hinweise suchen", "Keine passenden Hinweise", "Anpassung", "Darstellung", "An System anpassen", "Schutz", "Heute", "Eigener Zeitraum", "Aktivität"),
    LocaleRouteLabels("es", "es", "Ajustes", "Tu Lava", "Privacidad y datos", "Registros locales", "Avisos legales", "Buscar avisos", "No hay avisos coincidentes", "Personalización", "Apariencia", "Igualar al sistema", "Protección", "Hoy", "Personalizado", "Actividad"),
    LocaleRouteLabels("fr", "fr", "Réglages", "Votre Lava", "Confidentialité et données", "Journaux locaux", "Mentions légales", "Rechercher des notices", "Aucune notice correspondante", "Personnalisation", "Apparence", "Aligner sur le système", "Protection", "Aujourd’hui", "Personnalisé", "Activité"),
    LocaleRouteLabels("it", "it", "Impostazioni", "La tua Lava", "Privacy e dati", "Registri locali", "Note legali", "Cerca avvisi", "Nessun avviso corrispondente", "Personalizzazione", "Aspetto", "Abbina al sistema", "Protezione", "Oggi", "Personalizzato", "Attività"),
    LocaleRouteLabels("ja", "ja", "設定", "あなたの Lava", "プライバシーとデータ", "ローカルログ", "法的通知", "通知を検索", "一致する通知はありません", "カスタマイズ", "外観", "システムに合わせる", "ガード", "今日", "カスタム", "アクティビティ"),
    LocaleRouteLabels("ko", "ko", "설정", "내 Lava", "개인정보 및 데이터", "로컬 기록", "법적 고지", "고지 검색", "일치하는 고지가 없습니다", "맞춤 설정", "화면 표시", "시스템에 맞추기", "보호", "오늘", "사용자 지정", "활동"),
    LocaleRouteLabels("pt-BR", "pt-BR", "Ajustes", "Sua Lava", "Privacidade e dados", "Registros locais", "Avisos legais", "Buscar avisos", "Nenhum aviso correspondente", "Personalização", "Aparência", "Igualar ao sistema", "Proteção", "Hoje", "Personalizado", "Atividade"),
    LocaleRouteLabels("zh-CN", "zh-Hans", "设置", "你的 Lava", "隐私与数据", "本地日志", "法律声明", "搜索声明", "没有匹配的声明", "个性化", "外观", "匹配系统", "防护", "今天", "自定义", "活动"),
    LocaleRouteLabels("zh-TW", "zh-Hant", "設定", "你的 Lava", "隱私權與資料", "本機記錄", "法律聲明", "搜尋聲明", "沒有符合的聲明", "個人化", "外觀", "配合系統", "防護", "今天", "自訂", "活動"),
)
