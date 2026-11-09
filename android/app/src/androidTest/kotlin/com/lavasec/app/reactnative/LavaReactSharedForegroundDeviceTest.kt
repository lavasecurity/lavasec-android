package com.lavasec.app.reactnative

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.billing.NativePlusBackend
import com.lavasec.app.billing.VerifiedPlusReading
import com.lavasec.app.diagnostics.NativeBugReportOwner
import com.lavasec.app.foundation.diagnostics.ReportDeliveryState
import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.settings.ProductSettingsStore
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Finite production RN companions for the adopted shared pages. Navigation, controls and
 * confirmations are the packaged UI; native snapshots and independently reopened stores prove
 * the outcome. Controlled Plus is an explicitly labelled verified-reading fixture, not Play
 * purchase evidence. WireGuard text enters the actual native InputConnection and never JS.
 * Guardian gestures are injected real touchscreen contacts, not emitted callback shortcuts.
 * No report is submitted and no synthetic VPN is enabled.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactSharedForegroundDeviceTest {
    @Test
    fun shippingSharedLibraryCreateRenameDeleteUseReviewedOwnersAndCancelWithoutEarlyWrites() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            val baseline = durableLibrary(journey)
            val preferences = ProductSettingsStore(journey.context).loadPreferences()
            assertFalse("Preserve a pre-existing whole-library draft", libraryEditing(journey))
            assertNull("Preserve a pre-existing new-filter owner", product.state.value.newFilter)
            val inactive = filterRows(journey).filter { !it.getBoolean("active") }
            assertTrue("The configured fixture must have two independently editable saved filters", inactive.size >= 2)
            val rename = inactive[0]; val delete = inactive[1]
            val replacement = "RN reviewed ${UUID.randomUUID().toString().take(8)}"
            val restorePlus = grantControlledPlus(journey)
            try {
                openLibrary(journey)
                beginLibraryEdit(journey)
                journey.click("Add a filter")
                val cancelledCreate = awaitFlow(journey, "createFilter")
                journey.waitLabel("New filter"); journey.waitLabel("Create new")
                assertEquals(baseline, durableLibrary(journey))
                journey.click("Close", scroll = false)
                awaitClosed(journey, cancelledCreate)
                assertEquals("Closing the template sheet creates no saved profile", baseline, durableLibrary(journey))

                journey.click("Add a filter")
                val create = awaitFlow(journey, "createFilter")
                journey.waitEnabled("Create", true)
                journey.click("Create", scroll = false)
                awaitClosed(journey, create)
                journey.await("the native new-filter draft displayed by the shared Filter page") {
                    product.state.value.newFilter != null && journey.snapshot().optJSONObject("filterSession")?.optBoolean("editing") == true
                }
                val newID = checkNotNull(product.state.value.newFilter).id
                assertEquals("Create opens a real draft and does not save early", baseline, durableLibrary(journey))
                journey.waitLabel("Cancel editing")
                journey.click("Cancel editing", scroll = false)
                journey.await("the real Cancel retires only the created draft") { product.state.value.newFilter == null }
                assertFalse(durableLibrary(journey).profiles.any { it.id == newID })
                // The real useFilterRoute closes an unsaved draft's route once native
                // cancellation removes its ID. Await that return; another Back would pop Library.
                journey.waitLabel("Choose a filter to use, view or edit.")
                assertEquals(baseline, durableLibrary(journey))

                beginLibraryEdit(journey)
                journey.click(rename.getString("name"), prefix = true)
                val cancelledRename = awaitFlow(journey, "renameFilter")
                journey.waitLabel("Rename filter")
                journey.setText("Name", "Cancelled native identity")
                journey.click("Cancel", scroll = false)
                journey.waitLabel("Discard changes?")
                journey.click("Discard", scroll = false)
                awaitClosed(journey, cancelledRename)
                assertFalse("Discard cannot stage the transient name", journey.snapshot().getJSONObject("libraryEditing").getBoolean("hasChanges"))
                assertEquals(baseline, durableLibrary(journey))

                journey.click(rename.getString("name"), prefix = true)
                val acceptedRename = awaitFlow(journey, "renameFilter")
                journey.setText("Name", replacement)
                journey.waitEnabled("Save", true)
                journey.click("Save", scroll = false)
                awaitClosed(journey, acceptedRename)
                journey.await("rename staged in the same native whole-library session") {
                    journey.snapshot().getJSONObject("libraryEditing").getBoolean("hasChanges") &&
                        filterRows(journey).any { it.getString("id") == rename.getString("id") && it.getString("name") == replacement }
                }
                assertEquals("Saving the library rename must still wait for Review/Confirm", baseline, durableLibrary(journey))
                clickLibraryDeletion(journey, delete.getString("name"))
                journey.await("exact inactive row staged for deletion") {
                    journey.snapshot().getJSONObject("libraryEditing").getJSONArray("deletions").toString().contains(delete.getString("id"))
                }
                journey.click("Review changes", scroll = false)
                val cancelledReview = awaitFlow(journey, "deleteFilters")
                journey.waitLabel("Confirm changes")
                assertEquals("The visible Review cannot apply any identity or deletion", baseline, durableLibrary(journey))
                journey.click("Cancel", scroll = false)
                awaitClosed(journey, cancelledReview)
                assertTrue("Cancelling Review preserves the same staged library changes", journey.snapshot().getJSONObject("libraryEditing").getBoolean("hasChanges"))
                assertEquals(baseline, durableLibrary(journey))

                journey.click("Review changes", scroll = false)
                val confirmedReview = awaitFlow(journey, "deleteFilters")
                journey.waitLabel("Confirm changes")
                journey.click("Confirm changes", scroll = false)
                awaitClosed(journey, confirmedReview)
                journey.await("exact reviewed rename and deletion durable together", 60_000) {
                    durableLibrary(journey).let { saved ->
                        saved.profiles.firstOrNull { it.id == rename.getString("id") }?.name == replacement &&
                            saved.profiles.none { it.id == delete.getString("id") }
                    }
                }
                assertEquals("Only the reviewed inactive row was deleted", baseline.profiles.map { it.id }.toSet() - delete.getString("id"),
                    durableLibrary(journey).profiles.map { it.id }.toSet())
                assertEquals(preferences, ProductSettingsStore(journey.context).loadPreferences())
                journey.assertNoFallback()
            } finally {
                // Fixture cleanup, never acceptance: restore the exact native library captured
                // before the first UI action; do not leave staged editors or synthetic identities.
                try {
                    assertEquals("The fixture cannot cross credential graphs", generation, product.state.value.graphGeneration)
                    product.state.value.newFilter?.let { runBlocking { product.discardFilterDraft(it.id) } }
                    runBlocking { product.cancelLibraryEditing(generation) }
                    assertTrue("Restore the complete captured library through its real authority", runBlocking { product.replaceFilterLibrary(baseline) })
                    assertEquals(baseline, durableLibrary(journey))
                } finally { restorePlus() }
            }
        }
    }

    @Test
    fun shippingFeedbackOptInPreviewReviewAndCancelNeverSends() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured()
            val product = journey.application.foregroundProduct
            val generation = checkNotNull(product.state.value.graphGeneration)
            val baseline = durableLibrary(journey)
            val preferences = ProductSettingsStore(journey.context).loadPreferences()
            journey.click("Settings", scroll = false)
            journey.click("Feedback")
            val id = awaitFlow(journey, "feedback")
            val owner = checkNotNull(product.sharedFeedbackOwner(generation))
            val before = delivery(owner)
            assertFalse("This fixture must not consume another report review", before.hasReview || before.attempted || before.consented)
            try {
                journey.waitLabel("Choose a topic")
                journey.click("I have a suggestion")
                journey.click("Continue", scroll = false)
                journey.await("the original Feedback owner reached Details") {
                    assertEquals("Details cannot adopt a successor graph", generation, product.state.value.graphGeneration)
                    val current = journey.snapshot().getJSONObject("foregroundFlow")
                    assertEquals("Details cannot adopt a successor Feedback owner", id, current.getString("id"))
                    current.getJSONObject("feedback").getInt("step") == 1 && journey.accessibilityNodes().any { node ->
                        node.packageName?.toString() == journey.context.packageName && node.isVisibleToUser && node.isEnabled &&
                            node.isEditable && node.contentDescription?.toString() == "Details" &&
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                    }
                }
                journey.setText("Details", "Synthetic emulator review: shared feedback cancellation preserves all product state.")
                journey.click("Include optional diagnostics")
                journey.await("explicit diagnostic opt-in accepted by the real native report owner") { feedback(journey).getBoolean("diagnostics") }
                journey.click("See what information is sent")
                journey.waitLabel("Information Sent")
                // Native preview titles come from the frozen report body, not the
                // explanatory upstream catalog used when no preview is available.
                journey.waitLabel("App"); journey.waitLabel("Device")
                val preview = checkNotNull(delivery(owner).review)
                assertTrue("Opt-in preview freezes a real native diagnostic review", preview.request.includeDiagnostics)
                val previewBody = JSONObject(preview.requestBytes().toString(Charsets.UTF_8))
                assertTrue(previewBody.has("app"))
                assertEquals("The displayed Device preview comes from the actual emulator environment", android.os.Build.VERSION.SDK_INT,
                    previewBody.getJSONObject("device").getInt("android_api_level"))
                journey.click("Back", scroll = false)
                journey.await("the original Feedback owner reached Details") {
                    assertEquals("Details cannot adopt a successor graph", generation, product.state.value.graphGeneration)
                    val current = journey.snapshot().getJSONObject("foregroundFlow")
                    assertEquals("Details cannot adopt a successor Feedback owner", id, current.getString("id"))
                    current.getJSONObject("feedback").getInt("step") == 1 && journey.accessibilityNodes().any { node ->
                        node.packageName?.toString() == journey.context.packageName && node.isVisibleToUser && node.isEnabled &&
                            node.isEditable && node.contentDescription?.toString() == "Details" &&
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                    }
                }
                journey.click("Review", scroll = false)
                journey.waitLabel("Review and submit")
                journey.waitEnabled("Submit", true)
                assertTrue("The native owner must freeze the exact review shown by the shared page", delivery(owner).hasReview)
                assertEquals("Preview and Review retain the same immutable native report capability", preview, delivery(owner).review)
                assertTrue(feedback(journey).getBoolean("prepared"))
                assertNoAttempt(owner, before)
                journey.click("Cancel", scroll = false)
                journey.waitLabel("Discard feedback?")
                journey.click("Discard", scroll = false)
                awaitClosed(journey, id)
                assertFalse("Cancel retires the frozen report capability", delivery(owner).hasReview)
                assertNoAttempt(owner, before)
                assertEquals(baseline, durableLibrary(journey))
                assertEquals(preferences, ProductSettingsStore(journey.context).loadPreferences())
                assertEquals(generation, product.state.value.graphGeneration)
                journey.assertNoFallback()
            } finally {
                if (journey.snapshot().optJSONObject("foregroundFlow")?.optString("id") == id) {
                    // Failure cleanup releases only the exact fixture report. No submit action.
                    journey.completed("foreground.dismiss", JSONObject().put("id", id))
                }
                assertNoAttempt(owner, before)
            }
        }
    }

    @Test
    fun shippingSharedVpnNativeContentCancelAndSavePreserveConfidentialCustody() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured()
            val composition = checkNotNull(journey.application.chainedComposition.value)
            val repository = composition.configurationRepository
            assertNull("First-import evidence requires an absent saved native rotation", runBlocking { repository.loadConfiguration() })
            val baseline = durableLibrary(journey)
            val preferences = ProductSettingsStore(journey.context).loadPreferences()
            val enabled = runBlocking { composition.preferenceStore.loadEnabled() }
            assertFalse("Synthetic credentials cannot be activated", enabled)
            val restorePlus = grantControlledPlus(journey)
            var sheetID: String? = null
            try {
                journey.click("Settings", scroll = false)
                journey.scrollToTop(); journey.click("VPN chaining", prefix = true)
                journey.waitLabel("I have a WireGuard configuration")
                journey.click("I have a WireGuard configuration")
                sheetID = awaitFlow(journey, "vpnConfiguration")
                journey.waitLabel("WireGuard Configuration")
                typeNativeConfiguration(journey, checkNotNull(sheetID), syntheticConfiguration())
                journey.await("native-only Content creates a dirty real editor") { vpnEditor(journey).getBoolean("hasContent") && vpnEditor(journey).getBoolean("dirty") }
                assertConfidentialSnapshot(journey)
                assertNull("Typing cannot persist an unreviewed configuration", runBlocking { repository.loadConfiguration() })
                journey.click("Cancel", scroll = false)
                journey.waitLabel("Discard changes?"); journey.click("Discard", scroll = false)
                awaitClosed(journey, checkNotNull(sheetID)); sheetID = null
                assertNull("Native sheet Cancel leaves no rotation", runBlocking { repository.loadConfiguration() })
                assertNull("Native sheet Cancel leaves no credential material", runBlocking { repository.loadSecrets() })

                journey.waitLabel("I have a WireGuard configuration")
                journey.click("I have a WireGuard configuration")
                sheetID = awaitFlow(journey, "vpnConfiguration")
                journey.waitLabel("WireGuard Configuration")
                journey.setText("Name", "Predevice native custody")
                typeNativeConfiguration(journey, checkNotNull(sheetID), syntheticConfiguration())
                journey.waitEnabled("Save", true)
                assertConfidentialSnapshot(journey)
                journey.click("Save", scroll = false)
                awaitClosed(journey, checkNotNull(sheetID)); sheetID = null
                journey.await("native sheet Save publishes the pending first-import pair", 60_000) {
                    runBlocking { repository.loadConfiguration() }?.pendingImportId != null &&
                        journey.snapshot().optJSONObject("vpn")?.optJSONObject("draft") != null
                }
                val pending = checkNotNull(runBlocking { repository.loadConfiguration() })
                assertNotNull("Parent Review/Save has not committed the pending rotation", pending.pendingImportId)
                val material = checkNotNull(runBlocking { repository.loadSecrets() })
                try { assertArrayEquals("The protected native credential store owns the entered key", ByteArray(32) { 0x11 }, material.privateKeyCopy()) }
                finally { material.close() }
                assertFalse("Pending import cannot become an enabled tunnel", runBlocking { composition.preferenceStore.loadEnabled() })
                assertConfidentialSnapshot(journey)
                journey.waitLabel("Cancel editing")
                val parentHost = journey.requireActivity()
                val parentGeneration = checkNotNull(journey.application.foregroundProduct.state.value.graphGeneration)
                fun originalParentIsCurrent(): Boolean {
                    var current = false
                    journey.instrumentation.runOnMainSync {
                        current = journey.application.nativeVisitHosts.current()?.owner === parentHost &&
                            journey.application.reactHost.currentReactContext?.currentActivity === parentHost &&
                            parentHost.nativeHostAlive && parentHost.foreground
                    }
                    return current
                }
                assertTrue("Cancel must start in the original admitted native host", originalParentIsCurrent())
                val parentDraft = journey.snapshot().getJSONObject("vpn").getJSONObject("draft")
                val parentId = parentDraft.getString("id")
                val confirmsDiscard = parentDraft.getBoolean("changed")
                journey.click("Cancel editing", scroll = false)
                // Alert mounting is asynchronous. The exact dirty owner determines whether
                // confirmation is required; a one-shot visibility probe can lose that action.
                val cancellationDeadline = SystemClock.elapsedRealtime() + 60_000
                if (confirmsDiscard) {
                    journey.await("the original parent draft's actual discard confirmation",
                        (cancellationDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
                        assertSame("Discard confirmation cannot adopt another native host", parentHost, journey.requireActivity())
                        assertTrue("Discard confirmation requires the actual original foreground host", originalParentIsCurrent())
                        assertEquals("Discard confirmation cannot adopt another graph", parentGeneration,
                            journey.application.foregroundProduct.state.value.graphGeneration)
                        assertEquals("Discard confirmation cannot adopt another parent draft", parentId,
                            journey.snapshot().getJSONObject("vpn").getJSONObject("draft").getString("id"))
                        journey.visible("Discard changes?")
                    }
                    journey.click("Discard", scroll = false)
                }
                journey.await("real parent cancellation removes the exact pending pair",
                    (cancellationDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
                    assertSame("Cancellation cannot adopt another native host", parentHost, journey.requireActivity())
                    assertTrue("Cancellation requires the actual original foreground host", originalParentIsCurrent())
                    assertEquals("Cancellation cannot adopt another graph", parentGeneration,
                        journey.application.foregroundProduct.state.value.graphGeneration)
                    val draft = journey.snapshot().optJSONObject("vpn")?.optJSONObject("draft")
                    if (draft != null) assertEquals("Cancellation cannot adopt another parent draft", parentId, draft.getString("id"))
                    runBlocking { repository.loadConfiguration() } == null && draft == null
                }
                assertNull("Pending rollback also removes the protected key", runBlocking { repository.loadSecrets() })
                assertEquals(enabled, runBlocking { composition.preferenceStore.loadEnabled() })
                assertEquals(baseline, durableLibrary(journey))
                assertEquals(preferences, ProductSettingsStore(journey.context).loadPreferences())
                journey.assertNoFallback()
                // Re-create a real pending parent, then destroy its actual Activity. RN
                // unmount cannot perform an admitted command after native retirement;
                // cleanup must belong to the surviving native draft owner instead.
                journey.click("I have a WireGuard configuration")
                sheetID = awaitFlow(journey, "vpnConfiguration")
                journey.waitLabel("WireGuard Configuration")
                typeNativeConfiguration(journey, checkNotNull(sheetID), syntheticConfiguration())
                journey.waitEnabled("Save", true)
                journey.click("Save", scroll = false)
                awaitClosed(journey, checkNotNull(sheetID)); sheetID = null
                journey.await("the second actual import is pending under its original parent", 60_000) {
                    runBlocking { repository.loadConfiguration() }?.pendingImportId != null &&
                        journey.snapshot().optJSONObject("vpn")?.optJSONObject("draft") != null
                }
                val abandoned = checkNotNull(runBlocking { repository.loadConfiguration() })
                val abandonedID = journey.snapshot().getJSONObject("vpn").getJSONObject("draft").getString("id")
                assertEquals("The imported rotation belongs to this exact parent", abandonedID, abandoned.pendingImportId)
                val abandonedSecrets = checkNotNull(runBlocking { repository.loadSecrets() })
                try { assertArrayEquals("The second actual import has native protected credentials", ByteArray(32) { 0x11 }, abandonedSecrets.privateKeyCopy()) }
                finally { abandonedSecrets.close() }
                val abandonedHost = journey.requireActivity()
                val abandonedGeneration = checkNotNull(journey.application.foregroundProduct.state.value.graphGeneration)
                assertConfidentialSnapshot(journey)
                journey.reopen()
                assertNotSame("Reopen must use an independent actual Activity", abandonedHost, journey.requireActivity())
                onMain(journey) { assertTrue("The original Activity really reached destruction", abandonedHost.isDestroyed) }
                assertEquals("Activity replacement retains the credential graph", abandonedGeneration,
                    journey.application.foregroundProduct.state.value.graphGeneration)
                assertSame("Activity replacement retains the actual native repository owner", composition,
                    journey.application.chainedComposition.value)
                journey.await("native host retirement compensates its exact pending rotation and protected key", 60_000) {
                    val remainingSecrets = runBlocking { repository.loadSecrets() }
                    val secretsRemoved = remainingSecrets == null
                    remainingSecrets?.close()
                    runBlocking { repository.loadConfiguration() } == null &&
                        secretsRemoved &&
                        journey.snapshot().optJSONObject("vpn")?.optJSONObject("draft") == null
                }
                assertFalse("Retirement cannot enable the synthetic tunnel", runBlocking { composition.preferenceStore.loadEnabled() })
                assertEquals(baseline, durableLibrary(journey))
                assertEquals(preferences, ProductSettingsStore(journey.context).loadPreferences())
                journey.click("Settings", scroll = false)
                journey.scrollToTop(); journey.click("VPN chaining", prefix = true)
                journey.waitLabel("I have a WireGuard configuration")
                journey.click("I have a WireGuard configuration")
                sheetID = awaitFlow(journey, "vpnConfiguration")
                assertNotEquals("The successor native visit cannot adopt the abandoned parent", abandonedID, sheetID)
                journey.waitLabel("WireGuard Configuration")
                journey.click("Cancel", scroll = false)
                awaitClosed(journey, checkNotNull(sheetID)); sheetID = null
                assertNull("A successor's real Cancel cannot resurrect the abandoned rotation", runBlocking { repository.loadConfiguration() })
                assertNull("A successor's real Cancel cannot resurrect the protected key", runBlocking { repository.loadSecrets() })
                assertConfidentialSnapshot(journey)
                journey.assertNoFallback()
            } finally {
                try {
                    sheetID?.let { id ->
                        if (journey.snapshot().optJSONObject("foregroundFlow")?.optString("id") == id)
                            journey.completed("foreground.dismiss", JSONObject().put("id", id).put("discardConfirmed", true))
                    }
                    journey.snapshot().optJSONObject("vpn")?.optJSONObject("draft")?.getString("id")?.let { id ->
                        journey.completed("vpn.cancel", JSONObject().put("id", id))
                    }
                    assertNull("No pending synthetic import may survive the fixture", runBlocking { repository.loadConfiguration() })
                } finally { restorePlus() }
            }
        }
    }

    @Test
    fun shippingGuardianPhysicalContactTapAndHoldReachSharedDestinations() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured(); journey.waitProtectionOff()
            val baseline = durableLibrary(journey)
            val preferences = ProductSettingsStore(journey.context).loadPreferences()
            val guardian = guardianContact(journey)
            val host = journey.requireActivity()
            val traces = mutableListOf<GuardianGestureTrace>()
            fun observe(view: LavaDecorationView): GuardianGestureTrace = onMain(journey) {
                traces.firstOrNull { it.view === view } ?: GuardianGestureTrace(view).also(traces::add)
            }
            val contactTrace = observe(guardian)
            try {
                val bounds = onMain(journey) { Rect().also { assertTrue(guardian.getGlobalVisibleRect(it)); assertFalse(it.isEmpty) } }
                // getGlobalVisibleRect is in the decor's coordinate space; injection is screen space.
                val point = onMain(journey) { IntArray(2).also(guardian::getLocationOnScreen).let { it[0] + guardian.width / 2f to it[1] + guardian.height / 2f } }
                assertTrue(bounds.width() > 0 && bounds.height() > 0)
                onMain(journey) { contactTrace.print("before-cancel-contact") }
                val cancelledDown = SystemClock.uptimeMillis()
                touch(journey, cancelledDown, MotionEvent.ACTION_DOWN, point)
                try {
                    journey.await("the actual native Guardian owns a physical contact") { onMain(journey) { tracking(guardian) } }
                } finally {
                    onMain(journey) { contactTrace.print("before-cancel") }
                    touch(journey, cancelledDown, MotionEvent.ACTION_CANCEL, point)
                    onMain(journey) { contactTrace.print("after-cancel") }
                }
                SystemClock.sleep(1_350)
                assertFalse("Cancelled contact cannot fire delayed hold navigation", journey.visible("Choose your Lava Guard"))
                assertFalse("Cancelled contact cannot count as a Sudoku tap", journey.visible("Notes mode off"))
                assertFalse(onMain(journey) { tracking(guardian) })
                onMain(journey) { contactTrace.print("after-cancel-settlement") }
                repeat(5) { index ->
                    onMain(journey) { contactTrace.print("before-tap-${index + 1}") }
                    val down = SystemClock.uptimeMillis()
                    touch(journey, down, MotionEvent.ACTION_DOWN, point)
                    try { SystemClock.sleep(40) }
                    finally { touch(journey, down, MotionEvent.ACTION_UP, point) }
                    SystemClock.sleep(75)
                    onMain(journey) { contactTrace.print("after-tap-${index + 1}") }
                    if (index == 3) {
                        // A cancelled contact must contribute zero taps. If it counted
                        // as one, the fourth real tap would already open the shared game.
                        journey.instrumentation.waitForIdleSync()
                        SystemClock.sleep(350)
                        assertFalse("Four physical taps after Cancel are below the five-tap threshold", journey.visible("Notes mode off"))
                        assertTrue("The shared Guard remains the actual destination before tap five", onMain(journey) { guardian.isShown && guardian.hasWindowFocus() })
                    }
                }
                journey.waitLabel("Notes mode off")
                journey.back(); journey.waitProtectionOff()
                val held = guardianContact(journey)
                val heldTrace = observe(held)
                onMain(journey) { heldTrace.print("before-hold") }
                val holdPoint = onMain(journey) { IntArray(2).also(held::getLocationOnScreen).let { it[0] + held.width / 2f to it[1] + held.height / 2f } }
                val down = SystemClock.uptimeMillis()
                touch(journey, down, MotionEvent.ACTION_DOWN, holdPoint)
                try { journey.waitLabel("Choose your Lava Guard") }
                finally {
                    touch(journey, down, MotionEvent.ACTION_UP, holdPoint)
                    onMain(journey) { heldTrace.print("after-hold") }
                }
                journey.waitLabel("Lava Guard")
                journey.back(); journey.waitProtectionOff()
                assertEquals("Contact navigation never changes the chosen Guard", preferences, ProductSettingsStore(journey.context).loadPreferences())
                assertEquals(baseline, durableLibrary(journey))
                journey.assertNoFallback()
            } catch (failure: Throwable) {
                runCatching { dumpGuardianFacts(journey, host, traces) }.onFailure {
                    println("LAVA_UI_GUARD_CONTACT diagnostic-failed=${it.javaClass.simpleName}")
                }
                throw failure
            } finally {
                onMain(journey) { traces.forEach(GuardianGestureTrace::restore) }
            }
        }
    }

    private fun openLibrary(journey: ReactEmulatorJourney) {
        journey.scrollToTop(); journey.click("Now filtering", prefix = true)
        journey.waitLabel("Switch or manage filters"); journey.click("Switch or manage filters")
        journey.waitLabel("Choose a filter to use, view or edit.")
    }
    private fun beginLibraryEdit(journey: ReactEmulatorJourney) {
        if (!libraryEditing(journey)) journey.click("Edit", scroll = false)
        journey.waitLabel("Close edit mode"); journey.await("real native library edit owner") { libraryEditing(journey) }
    }
    private fun libraryEditing(journey: ReactEmulatorJourney) = journey.snapshot().optJSONObject("libraryEditing")?.optBoolean("active") == true
    private fun filterRows(journey: ReactEmulatorJourney) = journey.snapshot().getJSONObject("filters").getJSONArray("rows").let { rows -> (0 until rows.length()).map(rows::getJSONObject) }
    private fun awaitFlow(journey: ReactEmulatorJourney, kind: String): String {
        journey.await("real shared foreground owner $kind") { journey.snapshot().optJSONObject("foregroundFlow")?.optString("kind") == kind }
        return journey.snapshot().getJSONObject("foregroundFlow").getString("id")
    }
    private fun awaitClosed(journey: ReactEmulatorJourney, id: String) {
        journey.await("exact shared foreground owner retired") { journey.snapshot().optJSONObject("foregroundFlow")?.optString("id") != id }
    }
    private fun feedback(journey: ReactEmulatorJourney) = journey.snapshot().getJSONObject("foregroundFlow").getJSONObject("feedback")
    private fun vpnEditor(journey: ReactEmulatorJourney) = journey.snapshot().getJSONObject("foregroundFlow").getJSONObject("vpnEditor")
    private fun durableLibrary(journey: ReactEmulatorJourney) = runBlocking {
        val reopened = CredentialFilterConfigurationStore.credentialProtected(journey.context, FeatureLimits.FREE.maxFilterRules).readAuthoritative()
        assertTrue("Durable configuration must independently reopen: $reopened", reopened is CredentialFilterConfigurationRead.Available)
        (reopened as CredentialFilterConfigurationRead.Available).state.configuration
    }
    private fun delivery(owner: NativeBugReportOwner): ReportDeliveryState {
        val lock = NativeBugReportOwner::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(owner)
        return synchronized(lock) { NativeBugReportOwner::class.java.getDeclaredField("delivery").apply { isAccessible = true }.get(owner) as ReportDeliveryState }
    }
    private fun assertNoAttempt(owner: NativeBugReportOwner, before: ReportDeliveryState) {
        val current = delivery(owner)
        assertFalse("Review/preview cannot attempt report delivery", current.attempted || current.consented || current.isSending || current.isReceived)
        assertNull(current.activeAttempt)
        assertEquals("No native delivery attempt was minted", before.nextAttemptId, current.nextAttemptId)
    }
    private fun clickLibraryDeletion(journey: ReactEmulatorJourney, filterName: String) {
        var target: AccessibilityNodeInfo? = null
        journey.await("the existing Delete button for only $filterName") {
            val candidates = journey.accessibilityNodes().filter { node ->
                val description = node.contentDescription?.toString().orEmpty()
                node.packageName?.toString() == journey.context.packageName && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                    (description == "Delete, $filterName" || description == "Delete, $filterName, ")
            }
            assertTrue("A scoped deletion button must be unique", candidates.size <= 1)
            target = candidates.singleOrNull()?.takeIf { it.refresh() && it.isEnabled && it.isVisibleToUser }
            target != null
        }
        assertTrue("The actual scoped native Delete control must accept its action", checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun grantControlledPlus(journey: ReactEmulatorJourney): () -> Unit {
        var backend: NativePlusBackend? = null
        journey.await("actual native entitlement backend") { backend = journey.application.plusBackend.value; backend != null }
        val entitlement = checkNotNull(backend).entitlement
        assertFalse("Controlled Plus must begin from Free", runBlocking { entitlement.initialize(); entitlement.state.value.hasPlus })
        assertTrue(runBlocking { entitlement.apply(VerifiedPlusReading(active = true, confidence = EntitlementReadingConfidence.CONFIRMED)) })
        println("LAVA_SHARED_FORMS_PLUS_SOURCE=controlled-verified-entitlement play-purchase=false")
        try {
            journey.await("controlled verified Plus projection") { journey.snapshot().getJSONObject("plus").getBoolean("enabled") }
        } catch (failure: Throwable) {
            runCatching { runBlocking { entitlement.apply(VerifiedPlusReading(active = false, confidence = EntitlementReadingConfidence.CONFIRMED)) } }
            throw failure
        }
        return {
            assertTrue(runBlocking { entitlement.apply(VerifiedPlusReading(active = false, confidence = EntitlementReadingConfidence.CONFIRMED)) })
            journey.await("restore actual Free entitlement") { !journey.snapshot().getJSONObject("plus").getBoolean("enabled") }
        }
    }
    private fun typeNativeConfiguration(journey: ReactEmulatorJourney, ownerID: String, raw: String) {
        var field: LavaTextFieldView? = null
        journey.await("the exact native-only confidential Content editor admitted") {
            field = onMain(journey) { descendants(journey.requireActivity().window.decorView).filterIsInstance<LavaTextFieldView>()
                .singleOrNull { it.kind == "wireGuard" && it.ownerID == ownerID && it.isShown && it.isAttachedToWindow && it.editor.isFocusable && journey.requireActivity().admitsNativeFieldInput() } }
            field != null
        }
        val leaf = checkNotNull(field)
        onMain(journey) {
            val editor = leaf.editor
            // One atomic native paste, with soft-keyboard presentation suppressed only for
            // this fixture operation; installed IME qualification is a separate device case.
            val show = editor.showSoftInputOnFocus
            editor.showSoftInputOnFocus = false
            try {
                editor.requestFocusFromJS()
                assertTrue("Native Content must actually own focus", editor.hasFocus())
                val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
                try {
                    assertTrue("Actual native InputConnection must accept the valid synthetic configuration", connection.commitText(raw, 1))
                    assertTrue(connection.finishComposingText())
                    assertEquals("Native Content alone holds the entered configuration", raw, editor.text.toString())
                } finally { connection.closeConnection() }
                (editor.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken, 0)
                editor.clearFocus()
            } finally { editor.showSoftInputOnFocus = show }
        }
    }
    private fun syntheticConfiguration(): String = """
        [Interface]
        PrivateKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 0x11 })}
        Address = 10.7.0.2/32
        DNS = 10.64.0.1
        [Peer]
        PublicKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 })}
        PresharedKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 0x33 })}
        AllowedIPs = 0.0.0.0/0
        Endpoint = vpn.example:51820
    """.trimIndent()
    private fun assertConfidentialSnapshot(journey: ReactEmulatorJourney) {
        val published = journey.snapshot().toString()
        assertFalse("The native configuration is never serialized into RN", published.contains(syntheticConfiguration()))
        assertFalse("Configuration sections cannot leak through JSON-escaped native text", published.contains("[Interface]") || published.contains("[Peer]"))
        assertFalse("Private key material is never serialized into RN", published.contains(Base64.getEncoder().encodeToString(ByteArray(32) { 0x11 })))
        assertFalse("Preshared key material is never serialized into RN", published.contains(Base64.getEncoder().encodeToString(ByteArray(32) { 0x33 })))
    }
    /** Observer only: the installed native→Fabric emitter receives every event exactly once. */
    private class GuardianGestureTrace(val view: LavaDecorationView) {
        private val original = view.guardianGestureEmitter
        private val events = mutableListOf<String>()
        private val observed: (String) -> Unit = { gesture ->
            events += when (gesture) { "start", "end", "tap", "reveal" -> gesture; else -> "other" }
            original(gesture)
        }
        init { view.guardianGestureEmitter = observed }
        fun print(stage: String) {
            println("LAVA_UI_GUARD_CONTACT kind=sequence stage=$stage leaf=${System.identityHashCode(view)} " +
                "sequence=${events.joinToString(",")} starts=${events.count { it == "start" }} " +
                "ends=${events.count { it == "end" }} taps=${events.count { it == "tap" }} reveals=${events.count { it == "reveal" }}")
        }
        fun restore() {
            // A genuine successor emitter must never be overwritten by this retiring observer.
            if (view.guardianGestureEmitter === observed) view.guardianGestureEmitter = original
        }
    }

    /** Static leaf/host identities and numeric bounds only; no arbitrary AX or entered data. */
    private fun dumpGuardianFacts(journey: ReactEmulatorJourney, host: LavaReactActivity, traces: List<GuardianGestureTrace>) {
        onMain(journey) {
            println("LAVA_UI_GUARD_CONTACT kind=host currentHost=${journey.application.reactHost.currentReactContext?.currentActivity === host} " +
                "host=${System.identityHashCode(host)} foreground=${host.foreground} destroyed=${host.isDestroyed} finishing=${host.isFinishing} " +
                "focus=${host.hasWindowFocus()} leaves=${traces.size}")
            val mounted = if (!host.isDestroyed) descendants(host.window.decorView) else emptyList()
            traces.forEach { trace ->
                trace.print("failure")
                val view = trace.view
                val window = IntArray(2).also(view::getLocationInWindow)
                val screen = IntArray(2).also(view::getLocationOnScreen)
                val bounds = Rect(); val visible = view.getGlobalVisibleRect(bounds)
                println("LAVA_UI_GUARD_CONTACT kind=leaf leaf=${System.identityHashCode(view)} mounted=${mounted.any { it === view }} " +
                    "attached=${view.isAttachedToWindow} shown=${view.isShown} focus=${view.hasWindowFocus()} visibility=${view.visibility} " +
                    "gestures=${view.guardianGestures} tracking=${tracking(view)} size=${view.width}x${view.height} " +
                    "windowPosition=${window[0]},${window[1]} screenPosition=${screen[0]},${screen[1]} " +
                    "globalVisible=$visible bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
            }
        }
    }

    private fun guardianContact(journey: ReactEmulatorJourney): LavaDecorationView {
        journey.await("native Guard route has its actual measured presentation acknowledgment") {
            val generation = journey.application.foregroundProduct.state.value.graphGeneration
            generation != null && onMain(journey) { journey.requireActivity().isPresentationReady(generation) }
        }
        var found: LavaDecorationView? = null
        journey.await("actual transparent native contact leaf over the shared Guardian drawing") {
            found = onMain(journey) { descendants(journey.requireActivity().window.decorView).filterIsInstance<LavaDecorationView>()
                .singleOrNull { it.guardianGestures && it.symbol == "guardian.gestures" && it.isShown && it.isAttachedToWindow && it.hasWindowFocus() } }
            found != null
        }
        return checkNotNull(found)
    }
    private fun tracking(view: LavaDecorationView): Boolean {
        val contact = LavaDecorationView::class.java.getDeclaredField("guardianContact").apply { isAccessible = true }.get(view)
        return NativeGuardianContactRecognizer::class.java.getDeclaredField("tracking").apply { isAccessible = true }.getBoolean(contact)
    }
    private fun touch(journey: ReactEmulatorJourney, down: Long, action: Int, point: Pair<Float, Float>) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.first, point.second, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue("The actual touchscreen contact must be injected", journey.instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun <T> onMain(journey: ReactEmulatorJourney, operation: () -> T): T {
        val result = AtomicReference<T>()
        journey.instrumentation.runOnMainSync { result.set(operation()) }
        return result.get()
    }
}
