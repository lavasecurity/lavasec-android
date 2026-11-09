package com.lavasec.app.reactnative

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.data.ActivityDatabase
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.data.ActivityRevisionBus
import com.lavasec.app.data.CredentialActivityWriter
import com.lavasec.app.data.GuardianProgressStore
import com.lavasec.app.foundation.activity.ActivityDateRangePolicy
import com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger
import com.lavasec.app.foundation.backup.BackupLavaGuardUnlockRecord
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.preferences.GuardianProgressPolicy
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.presentation.R
import com.lavasec.app.settings.ProductSettingsStore
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Always-run whole-app emulator evidence. Start this class on freshly cleared app data, before
 * configured-owner tests. Every navigation/edit/confirmation below goes through the packaged
 * shared React UI; snapshots and independently reopened storage establish the native outcomes.
 * Fixture data are synthetic local log receipts and a controlled portable achievement ledger,
 * restored through their production authorities. The ledger is not elapsed-use evidence. No
 * network request, account, entitlement, or authentication grant is fabricated.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactProductJourneyTest {
    @Test
    fun firstRunFilterDraftRoutesGuardianAndLogClearUseTheShippingSurface() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured(requireFirstRun = true, cancelFirstAttempt = true)
            exerciseFilterDraft(journey)
            exerciseSharedRoutesAndGuardian(journey)
            exerciseLogClear(journey)
            journey.reopen()
            assertTrue("Reopening must read the durable configured owner", journey.snapshot().getBoolean("available"))
            assertFalse(journey.snapshot().getBoolean("onboardingRequired"))
            journey.waitProtectionOff()
            journey.assertNoFallback()
        }
    }

    private fun exerciseFilterDraft(journey: ReactEmulatorJourney) {
        val rows = journey.snapshot().getJSONObject("filters").getJSONArray("rows")
        val active = (0 until rows.length()).map(rows::getJSONObject).first { it.getBoolean("active") }
        val id = active.getString("id")
        val savedDomain = "ui-save-${UUID.randomUUID()}.invalid"
        val cancelledDomain = "ui-cancel-${UUID.randomUUID()}.invalid"
        val before = persistedBlocked(journey, id)
        try {
            journey.clickGuardFiltering(journey.requireActivity(), journey.snapshot().getString("generation").toLong())
            journey.waitLabel("Switch or manage filters")
            journey.click("Now filtering", prefix = true)
            journey.waitLabel("Lava blocks these")
            journey.click("Edit", scroll = false)
            journey.await("filter edit mode") { journey.snapshot().optJSONObject("filterSession")?.optBoolean("editing") == true }
            exerciseBlocklistPicker(journey)
            addBlockedDomain(journey, cancelledDomain)
            journey.click("Cancel editing", scroll = false)
            journey.waitLabel("Discard changes?")
            journey.click("Discard", scroll = false)
            journey.await("draft cancellation") { journey.snapshot().getJSONObject("filterSession").getBoolean("editing").not() }
            assertEquals("Discard must leave the durable filter unchanged", before, persistedBlocked(journey, id))
            assertFalse(journey.snapshot().getJSONObject("filterSession").getJSONArray("blocked").toString().contains(cancelledDomain))

            journey.click("Edit", scroll = false)
            journey.await("second filter edit") { journey.snapshot().getJSONObject("filterSession").getBoolean("editing") }
            addBlockedDomain(journey, savedDomain)
            // #856 P2R: every changed draft, strengthening included, stops at the visible Review
            // boundary before any durable mutation. Confirm is the only path that persists it.
            journey.click("Save", scroll = false)
            journey.waitLabel("Confirm changes")
            assertTrue("P2R: Save must open the visible Confirm-changes Review", journey.visible("Confirm changes"))
            assertFalse("A changed draft must not be durable before confirmation",
                persistedBlocked(journey, id).contains(savedDomain))
            journey.click("Confirm changes", scroll = false)
            journey.await("saved strengthening edit", 60_000) { persistedBlocked(journey, id).contains(savedDomain) }
            assertEquals(before + savedDomain, persistedBlocked(journey, id))
            journey.assertNoFallback()

            // The Activity/React surface is replaced. The authoritative filter reader separately
            // reopens and validates its artifact from disk, so this is more than a cached label.
            journey.reopen()
            journey.clickGuardFiltering(journey.requireActivity(), journey.snapshot().getString("generation").toLong())
            journey.click("Now filtering", prefix = true)
            journey.await("saved rule after reopening") {
                journey.snapshot().optJSONObject("filterSession")?.getJSONArray("savedBlocked")?.toString()?.contains(savedDomain) == true
            }
            assertEquals(before + savedDomain, persistedBlocked(journey, id))
        } finally {
            // Remove only this test's own saved rule through review and the production commit.
            // Keeping that cleanup on the bridge does not count it as UI acceptance evidence.
            restoreFixtureRule(journey, id, savedDomain)
        }
        journey.reopen()
    }

    private fun exerciseBlocklistPicker(journey: ReactEmulatorJourney) {
        val original = journey.snapshot().getJSONObject("filterSession").getJSONArray("lists")
        val originalIds = (0 until original.length()).map(original::getString).toSet()
        assertTrue("Core must expose a real selected bundled source", originalIds.isNotEmpty())
        val catalog = journey.completed("catalog.query", JSONObject().put("ids", original)).getJSONObject("value")
        val sections = catalog.getJSONArray("sections")
        val rows = (0 until sections.length()).flatMap { section ->
            val sources = sections.getJSONObject(section).getJSONArray("sources")
            (0 until sources.length()).map(sources::getJSONObject)
        }
        val source = rows.first { it.getString("id") in originalIds }
        val sourceId = source.getString("id")
        val sourceName = source.getString("name")
        val sourceRowLabel = "$sourceName, ${source.getString("licenseName")}"
        fun selectedIds(): Set<String> {
            val list = journey.snapshot().getJSONObject("filterSession").getJSONArray("lists")
            return (0 until list.length()).map(list::getString).toSet()
        }
        fun savedIds(): Set<String> {
            val list = journey.snapshot().getJSONObject("filterSession").getJSONArray("savedLists")
            return (0 until list.length()).map(list::getString).toSet()
        }
        val originalSavedIds = savedIds()
        fun openPicker() {
            journey.scrollToTop()
            journey.click("Add a blocklist")
            journey.waitLabel("Choose blocklists")
            journey.waitLabel("Search blocklists or categories")
            // The catalog arrives asynchronously. Use the shipping search instead of scrolling
            // past its first row while it mounts, and prove that the matching row is visible.
            journey.setText("Search blocklists or categories", sourceName)
            journey.waitLabel(sourceRowLabel, prefix = true, excludeEditable = true)
            journey.assertNoFallback()
        }
        fun toggleSource(selected: Boolean) {
            // A fresh sheet may mount before JS receives the previous native draft mutation.
            // Verify its actual checkbox baseline before toggling, then verify the UI result.
            journey.waitSelection(sourceRowLabel, !selected, prefix = true)
            journey.click(sourceRowLabel, prefix = true, scroll = false, excludeEditable = true)
            journey.waitSelection(sourceRowLabel, selected, prefix = true)
        }
        // Each cancellation creates a distinct picker instance. Exercise the actual Back/reopen
        // boundary repeatedly; a failed click is never retried within an instance.
        repeat(6) { index ->
            val cycle = index + 1
            println("LAVA_PICKER_CYCLE cycle=$cycle phase=open")
            try {
                openPicker()
                toggleSource(selected = false)
                journey.back()
                journey.waitLabel("Cancel editing")
                assertEquals("Cycle $cycle: Android Back must discard the picker's unsubmitted selection", originalIds, selectedIds())
                assertEquals("Cycle $cycle: Android Back must preserve the saved blocklists", originalSavedIds, savedIds())
                println("LAVA_PICKER_CYCLE cycle=$cycle phase=discarded")
            } catch (failure: AssertionError) {
                throw AssertionError("Picker cancellation cycle $cycle failed: ${failure.message}", failure)
            }
        }

        openPicker()
        toggleSource(selected = false)
        journey.click("Save selection", scroll = false)
        journey.waitLabel("Cancel editing")
        journey.await("saved blocklist selection in the filter draft") { sourceId !in selectedIds() }
        assertEquals(originalIds - sourceId, selectedIds())

        // Restore the cached bundled source through the same reachable picker. Only the parent
        // filter's Save/Review commits a filter; chooser Save Selection must remain a draft edit.
        openPicker()
        toggleSource(selected = true)
        journey.click("Save selection", scroll = false)
        journey.waitLabel("Cancel editing")
        journey.await("restored bundled source in the filter draft") { selectedIds() == originalIds }
    }

    private fun addBlockedDomain(journey: ReactEmulatorJourney, domain: String) {
        journey.scrollToTop()
        journey.click("Block a domain")
        journey.waitLabel("Add blocked domain")
        journey.setText("Domain to block", domain)
        journey.click("Add domain", scroll = false)
        journey.await("UI domain edit in the native owner") {
            journey.snapshot().getJSONObject("filterSession").getJSONArray("blocked").toString().contains(domain)
        }
        // The input sheet removes itself after the native command; dismiss any retained IME
        // before testing the editing toolbar without consuming the screen's Back action.
        journey.waitLabel("Cancel editing")
    }

    private fun exerciseSharedRoutesAndGuardian(journey: ReactEmulatorJourney) {
        val startDateStage = journey.context.getString(R.string.activity_date_start)
        val endDateStage = journey.context.getString(R.string.activity_date_end)
        journey.clickGuardToday(journey.requireActivity(), journey.snapshot().getString("generation").toLong())
        journey.waitLabel("Activity")
        journey.waitLabel("Today", prefix = true)
        journey.waitSelection("Today", true)
        journey.click("Custom", scroll = false)
        journey.waitLabel(startDateStage)
        journey.click("Cancel", scroll = false)
        journey.waitSelection("Today", true)
        journey.waitSelection("Custom", false)
        journey.click("Custom", scroll = false)
        journey.waitLabel(startDateStage)
        journey.click("OK", scroll = false)
        journey.waitLabel(endDateStage)
        journey.click("Cancel", scroll = false)
        journey.waitSelection("Today", true)
        journey.waitSelection("Custom", false)
        journey.click("Custom", scroll = false)
        journey.waitLabel(startDateStage)
        journey.click("OK", scroll = false)
        journey.waitLabel(endDateStage)
        journey.click("OK", scroll = false)
        journey.waitSelection("Custom", true)
        journey.click("Today", scroll = false)
        journey.waitSelection("Today", true)
        journey.assertNoFallback()
        journey.back()
        journey.waitProtectionOff()
        journey.scrollToTop()
        journey.click("Explore how Lava works")
        journey.waitLabel("Play demo")
        journey.assertNoFallback()
        journey.back()
        journey.waitProtectionOff()

        journey.click("Settings", prefix = true, scroll = false)
        journey.waitLabel("Your Lava")
        val returnToSettings = captureSettingsReturn(journey)
        for ((route, marker) in listOf(
            "Account & backup" to "Encrypted backup",
            "Privacy & data" to "Local logs",
            "Security" to "Authentication method",
            "Network activity" to "Clear network activity",
            "Nerd stats" to "DNS tiers",
            "Legal Notices" to "Search notices",
        )) {
            journey.scrollToTop()
            journey.click(route)
            journey.waitLabel(marker)
            journey.assertNoFallback()
            if (route == "Account & backup") {
                journey.waitLabel("Not signed in")
                assertFalse("Route traversal must not sign in", journey.snapshot().getJSONObject("account").getBoolean("signedIn"))
            }
            if (route == "Legal Notices") {
                val legalScroll = journey.scrollRegionContaining("Search notices")
                journey.setText("Search notices", "parity-no-matching-notice-${UUID.randomUUID()}")
                legalScroll.reveal("No matching notices")
                journey.waitLabel("No matching notices")
                assertFalse("A no-match search must remove the previously available notice",
                    journey.visible("StevenBlack Unified Hosts"))
                legalScroll.reveal("Search notices", forward = false)
                journey.setText("Search notices", "StevenBlack")
                journey.click("StevenBlack Unified Hosts")
                val attribution =
                    "StevenBlack Unified Hosts is a third-party source shown for attribution and source identification. " +
                        "License: MIT. Owner or project: Steven Black."
                legalScroll.reveal(attribution)
                journey.waitLabel(attribution)
                journey.assertNoFallback()
            }
            journey.back()
            returnToSettings()
        }
        journey.click("Customization")
        journey.waitLabel("Appearance")
        journey.click("Original", prefix = true)
        journey.waitLabel("Choose your Lava Guard")
        journey.waitEnabled("Original", enabled = true, prefix = true)
        journey.waitLabel("0 days so far", prefix = true)
        // iOS #851 parity: a paid Guard row is no longer a dead disabled row (it is tappable into
        // the contextual Lava Plus explanation; the tap navigation and resume-once behavior are
        // pinned by the focused RN plus-intents coverage). A real accessibility press must still
        // not grant an unearned Guard: the native owner independently refuses the same look.
        journey.waitEnabled("Use Lava for 3 days", enabled = true, prefix = true)
        val refused = journey.command("settings.set", JSONObject().put("key", "look").put("value", "emberObsidian"))
        assertEquals("The real owner must refuse an unearned paid Guard", "rejected", refused.getString("status"))
        assertEquals("An unearned paid Guard must retain the current look", "original",
            journey.snapshot().getJSONObject("preferences").getString("look"))
        assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
        journey.click("Original", prefix = true)
        journey.await("owner-selected Original Guard") { journey.snapshot().getJSONObject("preferences").getString("look") == "original" }
        assertFalse("Selecting the free Guard must not invent Plus", journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
        exerciseRestoredEarnedGuardian(journey)
        journey.assertNoFallback()
        journey.back()
        journey.waitLabel("Appearance")
        journey.back()
        journey.scrollToTop()
        journey.waitLabel("Your Lava")
    }

    /** Return acquisition owns the original Settings viewport and its one original 20-second budget. */
    private fun captureSettingsReturn(journey: ReactEmulatorJourney): () -> Unit {
        val host = journey.requireActivity()
        val product = journey.application.foregroundProduct
        val visit = checkNotNull(journey.application.nativeVisitHosts.current())
        val context = checkNotNull(journey.application.reactHost.currentReactContext)
        val generation = checkNotNull(product.state.value.graphGeneration)
        var captured: ScrollView? = null
        fun visibleSettingsScrolls(): List<ScrollView> {
            val found = mutableListOf<ScrollView>()
            fun visitView(view: View) {
                if (view is ScrollView && view.getTag(com.facebook.react.R.id.react_test_id) == "screen.scroll" &&
                    view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
                    view.getGlobalVisibleRect(Rect())) found += view
                if (view is ViewGroup) for (index in 0 until view.childCount) visitView(view.getChildAt(index))
            }
            visitView(host.window.decorView)
            return found
        }
        journey.instrumentation.runOnMainSync {
            captured = visibleSettingsScrolls().singleOrNull()
        }
        val scroll = checkNotNull(captured) { "Settings requires one actual visible original ScrollView" }
        data class Viewport(val y: Int, val backward: Boolean, val bounds: Rect,
            val source: AccessibilityNodeInfo, val window: Int)
        fun viewport(): Viewport? {
            var current: Viewport? = null
            journey.instrumentation.runOnMainSync {
                assertTrue("Settings return must retain its exact Activity, product, visit and React context",
                    journey.requireActivity() === host && journey.application.foregroundProduct === product &&
                        visit.owner === host && visit.matches(journey.application.nativeVisitHosts.current()) &&
                        journey.application.reactHost.currentReactContext === context && context.currentActivity === host &&
                        host.nativeHostAlive && !host.isDestroyed && !host.isFinishing)
                assertEquals("Settings return must not adopt another credential graph", generation,
                    product.state.value.graphGeneration)
                // Painted labels alone can precede the original route's hydration/Ready ACK.
                if (!host.foreground || !host.hasWindowFocus() || !host.isPresentationReady(generation)) return@runOnMainSync
                val visible = visibleSettingsScrolls()
                assertTrue("Settings return must expose at most one actual current ScrollView", visible.size <= 1)
                if (visible.singleOrNull() !== scroll) return@runOnMainSync
                val bounds = Rect().also { assertTrue(scroll.getGlobalVisibleRect(it)) }
                val origin = IntArray(2).also(host.window.decorView::getLocationOnScreen)
                bounds.offset(origin[0], origin[1])
                val window = host.window.decorView.createAccessibilityNodeInfo().windowId
                if (window < 0) return@runOnMainSync
                current = Viewport(scroll.scrollY, scroll.canScrollVertically(-1), bounds,
                    scroll.createAccessibilityNodeInfo(), window)
            }
            return current
        }
        return {
            var target: AccessibilityNodeInfo? = null
            var submitted = 0
            var pendingY: Int? = null
            var lastY = 0
            var stableSince = 0L
            var movementDeadline = 0L
            // This replaces the original return-label wait, rather than adding a second budget.
            journey.await("UI label 'Your Lava' in the original ready Settings viewport", timeoutMillis = 20_000L) {
                val before = viewport() ?: return@await false
                val now = SystemClock.elapsedRealtime()
                pendingY?.let { departedY ->
                    if (before.y != lastY) { lastY = before.y; stableSince = now }
                    if (before.y < departedY && now - stableSince >= 100L) pendingY = null else {
                        assertTrue("Settings accepted a backward scroll without settled original native movement", now < movementDeadline)
                        return@await false
                    }
                }
                val nodes = journey.accessibilityNodes()
                if (target == null) {
                    val connected = nodes.filter { it == before.source && it.windowId == before.window &&
                        it.packageName?.toString() == journey.context.packageName && it.isVisibleToUser && it.isScrollable }
                    assertTrue("Settings return cannot adopt multiple AX scroll owners", connected.size <= 1)
                    target = connected.singleOrNull() ?: return@await false
                }
                val exact = checkNotNull(target)
                assertTrue("Settings return must refresh its original connected AX scroll source", exact.refresh())
                val current = viewport() ?: return@await false
                assertTrue("Settings return must retain the same AX source, current window and bounds",
                    exact == current.source && exact.windowId == current.window && exact.isVisibleToUser &&
                        exact.isScrollable && exact.packageName?.toString() == journey.context.packageName &&
                        Rect().also(exact::getBoundsInScreen) == current.bounds && nodes.any { it == exact })
                if (current.y != before.y) return@await false
                if (!current.backward) {
                    // The original label must be a fresh visible descendant of this exact Settings scroll owner.
                    nodes.any { node ->
                        if (!node.refresh() || !node.isVisibleToUser || node.windowId != current.window ||
                            node.packageName?.toString() != journey.context.packageName ||
                            listOf(node.text, node.contentDescription).none { it?.toString()?.equals("Your Lava", ignoreCase = true) == true }) false
                        else {
                            var ancestor = node.parent
                            var depth = 0
                            while (ancestor != null && ancestor != exact && depth++ < 32) ancestor = ancestor.parent
                            ancestor == exact
                        }
                    }
                } else {
                    assertTrue("Settings return must retain the original twelve-step scroll bound", submitted < 12)
                    assertTrue("Settings refused its one original backward scroll action",
                        exact.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                    ++submitted
                    pendingY = current.y; lastY = current.y
                    stableSince = SystemClock.elapsedRealtime(); movementDeadline = stableSince + 1_500L
                    false
                }
            }
        }
    }

    private fun exerciseRestoredEarnedGuardian(journey: ReactEmulatorJourney) {
        val usageBefore = GuardianProgressStore.get(journey.context).state.value.usageByDay.toMap()
        restoreControlledPortableGuardianLedger(journey).use {
            assertEquals("Restoring a portable unlock must not manufacture local usage", usageBefore,
                GuardianProgressStore.get(journey.context).state.value.usageByDay)
            assertFalse("The portable restore fixture must leave Plus off", journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            val automation = journey.instrumentation.uiAutomation
            val previousFlags = automation.serviceInfo.flags
            try {
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                }
                // waitEnabled can return enabled row text rather than its Pressable accessibility
                // parent. API 28 failed here before any earned-Guard selection action was sent.
                // Resolve the same refreshed clickable ancestry used by the real UI driver,
                // and require the target itself to retain the exact earned Guard label.
                fun isEarnedLabel(node: AccessibilityNodeInfo): Boolean =
                    listOf(node.text, node.contentDescription).any { value ->
                        val label = value?.toString().orEmpty()
                        label.equals("Fire Opal", ignoreCase = true) ||
                            label.startsWith("Fire Opal,", ignoreCase = true) ||
                            label.startsWith("Fire Opal\n", ignoreCase = true)
                    }
                var earnedRow: AccessibilityNodeInfo? = null
                val earnedRowID = "guardian.option.emberObsidian"
                var revealScrolls = 0
                journey.await("the restored-earned Guard's actual enabled selectable row") {
                    val nodes = journey.accessibilityNodes()
                    // The spotlight precedes the option list. Reveal the exact option only when
                    // absent; keep a visible row still while its eligibility/AX state updates.
                    val rowVisible = nodes.any { node ->
                        node.refresh() && node.isVisibleToUser && !node.isEditable &&
                            node.packageName?.toString() == journey.context.packageName &&
                            node.viewIdResourceName == earnedRowID
                    }
                    val ready = nodes.any { label ->
                        if (!label.refresh() || !label.isVisibleToUser || label.isEditable ||
                            !isEarnedLabel(label)) return@any false
                        var target = label
                        while (!target.isClickable) {
                            target = target.parent ?: return@any false
                        }
                        if (!target.refresh() || !target.isVisibleToUser || !target.isEnabled ||
                            !target.isClickable || target.isEditable ||
                            target.packageName?.toString() != journey.context.packageName ||
                            target.viewIdResourceName != earnedRowID ||
                            target.className?.toString() != "android.widget.Button" ||
                            !isEarnedLabel(target)) return@any false
                        earnedRow = target
                        true
                    }
                    if (!ready && !rowVisible && revealScrolls < 8) {
                        revealScrolls++
                        journey.scrollPage(forward = true)
                    }
                    ready
                }
                val earned = checkNotNull(earnedRow)
                assertTrue("The restored-earned Guard must expose its actual selectable row", earned.isClickable)
                assertTrue("The acquired earned Guard row must remain the current enabled button",
                    earned.refresh() && earned.isVisibleToUser && earned.isEnabled && earned.isClickable &&
                        !earned.isEditable && earned.packageName?.toString() == journey.context.packageName &&
                        earned.viewIdResourceName == earnedRowID &&
                        earned.className?.toString() == "android.widget.Button" && isEarnedLabel(earned))
                // Submit one action on the acquired option, never reacquire the spotlight by label.
                val accepted = earned.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                println("LAVA_JOURNEY_EARNED_GUARD_CLICK accepted=$accepted window=${earned.windowId} " +
                    "viewId=${earned.viewIdResourceName} class=${earned.className}")
                assertTrue("The actual restored-earned Guard row must accept its UI action", accepted)
                journey.instrumentation.waitForIdleSync()
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
            }
            journey.await("native admission of the restored-earned Guard without Plus") {
                journey.snapshot().getJSONObject("preferences").getString("look") == "emberObsidian"
            }
            assertEquals("The accepted look must be durably stored", "emberObsidian",
                ProductSettingsStore(journey.context).loadPreferences().look.wire)
            assertFalse("An earned Guard must not invent Plus", journey.snapshot().getJSONObject("plus").getBoolean("enabled"))

            journey.reopen()
            assertEquals("Reopening must retain the native-selected earned Guard", "emberObsidian",
                journey.snapshot().getJSONObject("preferences").getString("look"))
            assertEquals("An independently reopened preference store must retain the earned look", "emberObsidian",
                ProductSettingsStore(journey.context).loadPreferences().look.wire)
            assertTrue("The portable unlock must independently reopen from durable storage",
                GuardianProgressStore(File(journey.context.filesDir, "guardian-progress-v1.json"))
                    .captureLedger().records.any { it.guardID == "emberObsidian" && it.unlockedAt == 17.0 })
            assertFalse(journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            journey.click("Settings", prefix = true, scroll = false)
            journey.waitLabel("Your Lava")
            journey.click("Customization")
            journey.waitLabel("Appearance")
            // The appearance entry must reconstruct the saved look's exact rendered name.
            journey.click("Fire Opal", prefix = true)
            journey.waitLabel("Choose your Lava Guard")
            journey.assertNoFallback()
        }
    }

    /** A controlled portable restore fixture, never a claim of observed usage or an entitlement. */
    private fun restoreControlledPortableGuardianLedger(journey: ReactEmulatorJourney): AutoCloseable {
        val store = GuardianProgressStore.get(journey.context)
        val before = store.captureLedger()
        val usageBefore = store.state.value.usageByDay.toMap()
        assertFalse("The controlled restore must introduce an unavailable Guard",
            before.records.any { it.guardID == "emberObsidian" })
        val restored = GuardianProgressPolicy.normalize(BackupLavaGuardAchievementLedger(
            before.records + BackupLavaGuardUnlockRecord("emberObsidian", 17.0)))
        assertTrue("The production achievement authority must accept the controlled restore CAS",
            store.replaceLedger(restored, before))
        return AutoCloseable {
            // Cleanup uses the native owner; the selection evidence above uses the actual UI.
            journey.completed("settings.set", JSONObject().put("key", "look").put("value", "original"))
            journey.await("restored original look after portable-ledger fixture") {
                journey.snapshot().getJSONObject("preferences").getString("look") == "original"
            }
            assertEquals("original", ProductSettingsStore(journey.context).loadPreferences().look.wire)
            assertTrue("Cleanup must remove only the exact controlled portable ledger",
                store.replaceLedger(before, restored))
            assertEquals(before, GuardianProgressStore(File(journey.context.filesDir, "guardian-progress-v1.json")).captureLedger())
            assertEquals("The restore and cleanup must preserve actual local usage", usageBefore, store.state.value.usageByDay)
        }
    }

    private fun exerciseLogClear(journey: ReactEmulatorJourney) {
        val database = ActivityDatabase(journey.context)
        try {
            val repository = ActivityRepository(database)
            val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
            // This is a producer fixture, not claimed DNS tunnel traffic. Privacy controls and
            // the persisted repository remain the production authorities for retaining it.
            val domain = "local-log-${UUID.randomUUID()}.invalid"
            val range = ActivityDateRangePolicy.today(LocalDate.now())
            val baselineCount = repository.summary(range).blockedCount
            val event = DnsDecisionEvent(
                id = System.currentTimeMillis(),
                timestampMillis = System.currentTimeMillis(),
                normalizedDomain = domain,
                decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
            )
            assertTrue("The real persisted logging policy must admit the synthetic receipt", writer.record(event))
            assertTrue(repository.history(searchText = domain).isNotEmpty())
            assertEquals(baselineCount + 1, repository.summary(range).blockedCount)
            // Refresh the production Activity owner after the fixture transaction commits.
            runBlocking { journey.application.foregroundProduct.refreshActivity() }
            journey.click("Privacy & data")
            journey.waitLabel("Local logs")
            journey.click("Clear local logs")
            journey.click("Clear domain history")
            journey.waitLabel("Clear domain history?")
            journey.click("Cancel", scroll = false)
            assertTrue("Cancelling the confirmation must preserve the stored receipt", repository.history(searchText = domain).isNotEmpty())
            journey.click("Clear domain history")
            journey.waitLabel("Clear domain history?")
            journey.click("Clear History", scroll = false)
            journey.await("durable history deletion") { repository.history(searchText = domain).isEmpty() }
            assertEquals("History deletion must preserve filtering counts", baselineCount + 1, repository.summary(range).blockedCount)
            journey.click("Clear all logs")
            journey.waitLabel("Clear all logs?")
            journey.click("Clear All Logs", scroll = false)
            journey.await("durable count deletion") { repository.summary(range).totalCount == 0L }
            assertTrue(repository.history().isEmpty())
            assertTrue("Clearing data must preserve the user's logging preference", repository.loggingPolicy().settings.keepCounts)
            journey.assertNoFallback()
        } finally {
            database.close()
        }
        // Reopen a distinct SQLite connection after the UI owner reported success.
        ActivityDatabase(journey.context).use { reopened ->
            val repository = ActivityRepository(reopened)
            assertTrue(repository.history().isEmpty())
            assertEquals(0L, repository.summary(ActivityDateRangePolicy.today(LocalDate.now())).totalCount)
        }
    }

    private fun persistedBlocked(journey: ReactEmulatorJourney, id: String): Set<String> = runBlocking {
        val read = CredentialFilterConfigurationStore.credentialProtected(journey.context, FeatureLimits.FREE.maxFilterRules).readAuthoritative()
        assertTrue("The saved configuration/artifact must reopen from disk: $read", read is CredentialFilterConfigurationRead.Available)
        checkNotNull((read as CredentialFilterConfigurationRead.Available).state.configuration.profile(id)).manualRules.blockedDomains
    }

    private fun restoreFixtureRule(journey: ReactEmulatorJourney, id: String, domain: String) {
        if (!persistedBlocked(journey, id).contains(domain)) return
        journey.completed("filter.open", JSONObject().put("id", id))
        journey.completed("filter.edit", JSONObject().put("id", id))
        journey.completed("filter.domain", JSONObject().put("id", id).put("domain", domain).put("decision", "blocked").put("remove", true))
        assertEquals("review", journey.completed("filter.save", JSONObject().put("id", id)).getString("value"))
        val review = journey.completed("filter.review", JSONObject().put("id", id)).getString("value")
        journey.completed("filter.apply", JSONObject().put("id", id).put("review", review))
        journey.await("fixture rule cleanup", 60_000) { !persistedBlocked(journey, id).contains(domain) }
    }
}
