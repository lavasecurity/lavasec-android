package com.lavasec.app.reactnative

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.AutoCompleteTextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.billing.VerifiedPlusReading
import com.lavasec.app.data.ActivityDatabase
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.data.ActivityRevisionBus
import com.lavasec.app.data.CredentialActivityWriter
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #856 P2R production acceptance driven through the packaged React UI (not only the command
 * bridge). Every confirmation/cancellation below is a real accessibility action on the shipping
 * `Review` sheet, the filter detail toolbar, the production domain-row `ContextMenu`, or the
 * contextual Plus page; the durable library is independently reopened from disk to prove nothing
 * mutated before Confirm.
 *
 * UI companion to [LavaReactFilterReviewJourneyTest], which stays command-driven supplemental
 * evidence. Setup/cleanup fixtures (filling a dedicated filter over the free cap under the
 * controlled entitlement, writing one synthetic local log receipt, staging a command-level draft
 * change so Save reaches Review) reuse the same production owners as the existing passing journeys;
 * the acceptance action itself — Save -> visible Review, the domain-row ContextMenu Block/Allow, the
 * contextual Plus resume — is real UI.
 *
 * Navigation is taken verbatim from the passing [LavaReactProductJourneyTest] (Guard -> Now
 * filtering -> detail edit/save) and [LavaReactComponentMatrixTest] (Today -> Activity -> Domain
 * History + synthetic receipt + native Search).
 *
 * The emulator runner clears app data once, so each case captures the exact baseline and restores
 * it in `finally`; no edit draft, over-cap list, standalone review token or controlled entitlement
 * survives on a shared filter.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactFilterReviewUiJourneyTest {

    private data class RuleSet(val blocked: Set<String>, val allowed: Set<String>)
    private data class FilterRow(val id: String, val name: String)
    private data class AddedRule(val filterId: String, val decision: String, val domain: String)

    private class Baseline(
        val activeId: String,
        val filterIds: Set<String>,
        val rules: Map<String, RuleSet>,
    ) {
        val added = mutableListOf<AddedRule>()
        val created = mutableListOf<String>()
        var revokePlus: (() -> Unit)? = null
    }

    /**
     * P2R case 1 (UI): a changed draft's Save opens the visible Review, the saved library is
     * untouched until Confirm, the editing Cancel preserves the saved filter, and a second UI run
     * confirms and persists exactly the reviewed change.
     */
    @Test
    fun changedFilterSaveOpensVisibleReviewWithoutDurableMutationThenBackCancelAndConfirmPersist() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val baseline = captureBaseline(journey)
            try {
                val active = activeFilter(journey)
                val cancelled = "ui-review-cancel-${UUID.randomUUID()}.invalid"
                val confirmed = "ui-review-confirm-${UUID.randomUUID()}.invalid"

                openActiveFilterDetail(journey)

                // Cancel half: the real editing Cancel/Discard leaves the saved filter untouched.
                startEditing(journey)
                addBlockedDomainViaUi(journey, cancelled)
                assertEquals("Editing must not mutate the saved filter before Save/Review",
                    baseline.rules.getValue(active.id), ruleSet(journey, active.id))
                discardDraft(journey)
                assertEquals("Cancel/Discard must preserve the saved filter", baseline.rules.getValue(active.id),
                    ruleSet(journey, active.id))

                // Reopen the packaged host before the review half so no cancel-half React effect or
                // retained snapshot lands between Save->Review and Confirm. The Review screen mints
                // exactly one owner token on mount; a second capture (from a re-rendered effect)
                // mints a new token and makes the Confirm tap's captured token stale. The reference
                // LavaReactFilterDraftDeviceTest sequences save -> review -> apply exactly once.
                journey.reopen()
                openActiveFilterDetail(journey)

                // Review half: Save opens the visible Review, nothing is durable until Confirm.
                startEditing(journey)
                addBlockedDomainViaUi(journey, confirmed)
                journey.click("Save", scroll = false)
                journey.waitLabel("Confirm changes")
                assertEquals("Review must precede any durable mutation before Confirm",
                    baseline.rules.getValue(active.id), ruleSet(journey, active.id))
                assertFalse("The reviewed-but-unconfirmed change must not be durable",
                    ruleSet(journey, active.id).blocked.contains(confirmed))
                journey.click("Confirm changes", scroll = false)
                journey.await("confirmed UI review persisted", 60_000) {
                    ruleSet(journey, active.id).blocked.contains(confirmed)
                }
                baseline.added += AddedRule(active.id, "blocked", confirmed)
                // Tear the committed Review/screen churn down exactly as the passing
                // LavaReactProductJourneyTest does, instead of lingering on the modal.
                journey.reopen()
                assertTrue("The confirmed change must survive a real host reopen",
                    ruleSet(journey, active.id).blocked.contains(confirmed))
                journey.assertNoFallback()
            } finally {
                closeAnyReview(journey)
                dismissAlert(journey)
                cancelAnyDraft(journey)
                clearAnyStandaloneReview(journey)
                restoreBaseline(journey, baseline)
            }
        }
    }

    /**
     * Both shipping domain lists consume the production credential Activity repository.
     * One bounded synthetic DNS receipt is admitted by CredentialActivityWriter, then
     * actual Top Domains and History rows each drive both named Block/Allow actions.
     * Every action must reach visible Review before any durable filter write; Close
     * retires that exact standalone review and leaves the saved library unchanged.
     */
    @Test
    fun topDomainsAndDomainHistoryContextMenuBlockAndAllowOpenTheirStandaloneReview() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val baseline = captureBaseline(journey)
            try {
                val active = activeFilter(journey)
                val saved = ruleSet(journey, active.id)
                val domain = "ui-domain-row-${UUID.randomUUID()}.invalid"
                writeRetainedActivityReceipt(journey, domain)

                openTopDomainsFromGuard(journey)
                exerciseDomainRowReview(journey, domain, active, saved, "Top Domains")
                returnToActivityFromTopDomains(journey)
                openDomainHistoryFromActivity(journey)
                exerciseDomainRowReview(journey, domain, active, saved, "Domain History")
                journey.assertNoFallback()
            } finally {
                // Defensive teardown: a staged standalone token must never outlive its journey, and
                // an unresolved generic alert must not block the later suites.
                closeAnyReview(journey)
                dismissAlert(journey)
                cancelAnyDraft(journey)
                clearAnyStandaloneReview(journey)
                restoreBaseline(journey, baseline)
            }
        }
    }

    /**
     * P2R case 4 (UI): a dedicated filter whose blocked/allowed list is over the free cap produces a
     * capacity-blocked Save, whose contextual Plus offer resumes the SAME draft into the visible
     * Review exactly once with Confirm re-enabled and NO automatic apply.
     *
     * The over-cap draft and a real draft change are staged through the production owners (setup);
     * the acceptance path — Save, the contextual Plus reason, the controlled verified-entitlement
     * resume and the returned Review — is driven entirely through the packaged React surface. The
     * controlled adapter is explicitly labelled and never a genuine Play purchase.
     */
    @Test
    fun fullFreeBlockedAndAllowedListsOpenContextualPlusThenResumeIntoOneFreshReviewWithoutApply() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val baseline = captureBaseline(journey)
            val dedicated = inactiveFilter(journey)
            var journeyFailure: Throwable? = null
            try {
                switchActive(journey, dedicated.id)
                NativeFabricMountWitness(journey.requireActivity()) {
                    journey.application.reactHost.currentReactContext
                }.use {
                    for ((decision, cap) in listOf(
                        "blocked" to FeatureLimits.FREE.maxBlockedDomains,
                        "allowed" to FeatureLimits.FREE.maxAllowedDomains,
                    )) {
                        exerciseCapacityPlus(journey, baseline, dedicated.id, decision, cap)
                    }
                }
                journey.assertNoFallback()
            } catch (failure: Throwable) {
                journeyFailure = failure
                throw failure
            } finally {
                try {
                    cancelAnyDraft(journey)
                    restoreBaseline(journey, baseline)
                } catch (cleanupFailure: Throwable) {
                    if (journeyFailure != null) journeyFailure.addSuppressed(cleanupFailure)
                    else throw cleanupFailure
                }
            }
        }
    }

    private fun exerciseCapacityPlus(
        journey: ReactEmulatorJourney,
        baseline: Baseline,
        filterId: String,
        decision: String,
        cap: Int,
    ) {
        val other = if (decision == "blocked") "allowed" else "blocked"
        val overCap = cap + 1
        val stagedChange = "ui-cap-$other-${UUID.randomUUID()}.invalid"
        // Build the saved over-free-cap list AND a valid changed draft under controlled Plus.
        // Revoke only after staging: Free correctly rejects an addition while the opposite shelf
        // remains over quota. The real UI Save below then discovers the lapse on this same draft.
        val revokeSetup = grantControlledPlus(journey)
        try {
            fillList(journey, baseline, filterId, decision, overCap)
            journey.completed("filter.open", JSONObject().put("id", filterId))
            if (!isEditing(journey)) journey.completed("filter.edit", JSONObject().put("id", filterId))
            val staged = journey.completed(
                "filter.domain",
                JSONObject().put("id", filterId).put("domain", stagedChange).put("decision", other).put("remove", false),
            )
            assertTrue("Controlled Plus must admit the setup addition before lapse: $staged",
                staged.optJSONObject("value")?.optBoolean("isAccepted", true) != false)
            baseline.added += AddedRule(filterId, other, stagedChange)
            journey.await("staged capacity change in the draft") { draftRules(journey, other).contains(stagedChange) }
        } finally {
            // The adapter waits for the genuine Free projection before any acceptance UI action.
            revokeSetup()
        }

        // The SAVED opposite list before the resume; an auto-apply would append `stagedChange` to it.
        // The over-cap `decision` list cannot detect that (it is unchanged by the draft), so the
        // meaningful guard is on the changed list.
        val otherBefore = countFor(journey, filterId, other)

        // The owner staging above does not move the React navigation; reach the dedicated filter's
        // editor on the packaged surface so the real Save control is present before it is clicked.
        ensureFilterEditor(journey, filterId, other, stagedChange)

        // Real UI: Save offers the contextual Plus page for the full list (no capacity Review).
        android.util.Log.i("LavaCapacitySaveWitness", if (decision == "allowed") "capacity-save-allowed" else "capacity-save-blocked")
        journey.click("Save", scroll = false)
        val message = plusMessageFor(decision)
        try {
            journey.waitLabel(message)
        } catch (failure: AssertionError) {
            try { failure.addSuppressed(AssertionError("LavaCapacitySaveFailureTrace " + captureCapacitySaveDispatchWitness(journey, decision))) } catch (_: Throwable) { }
            throw failure
        }
        // The controlled verified-entitlement adapter resumes the SAME draft into Review once.
        val revokeResume = grantControlledPlus(journey)
        try {
            journey.waitLabel("Confirm changes")
            assertEquals("The contextual Plus resume must not auto-apply the draft change",
                otherBefore, countFor(journey, filterId, other))
            assertFalse("The Plus resume must not persist the staged draft change",
                ruleSet(journey, filterId).let { if (other == "blocked") it.blocked else it.allowed }.contains(stagedChange))
        } finally {
            revokeResume()
        }
        // Close the returned Review without applying; discard the draft through the real toolbar.
        journey.click("Close", scroll = false)
        journey.waitLabel("Cancel editing")
        discardDraft(journey, expectedCapacityReason = if (decision == "blocked") "blockedDomains" else "allowedDomains")
        // Restore this decision's over-cap list before the next decision.
        removeRules(journey, filterId, decision, baseline.added
            .filter { it.filterId == filterId && it.decision == decision }.map { it.domain })
        removeRules(journey, filterId, other, listOf(stagedChange))
        baseline.added.removeAll { it.filterId == filterId && it.decision == decision }
        baseline.added.removeAll { it.filterId == filterId && it.decision == other && it.domain == stagedChange }
    }

    /** Failure-only, bounded QA scalar records from this invocation; never a second action/read. */
    private fun captureCapacitySaveDispatchWitness(journey: ReactEmulatorJourney, decision: String): String {
        try {
            val result = java.util.concurrent.atomic.AtomicReference<JSONObject?>(null)
            val active = java.util.concurrent.atomic.AtomicBoolean(true)
            val channel = java.util.concurrent.atomic.AtomicReference<android.os.ParcelFileDescriptor?>(null)
            val done = java.util.concurrent.CountDownLatch(1)
            val marker = if (decision == "allowed") "capacity-save-allowed" else "capacity-save-blocked"
            val worker = Thread(Runnable {
                if (!active.get()) return@Runnable
                try {
                    val descriptor = journey.instrumentation.uiAutomation.executeShellCommand(
                        "logcat -d -t 512 -v epoch -s ReactNativeJS:I LavaCapacitySaveWitness:I '*:S'",
                    )
                    channel.set(descriptor)
                    if (!active.get()) return@Runnable
                    val raw = StringBuilder()
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                        val chunk = CharArray(4096)
                        while (active.get() && raw.length <= 131_072) {
                            val count = reader.read(chunk, 0, minOf(chunk.size, 131_073 - raw.length))
                            if (count < 0) break
                            raw.append(chunk, 0, count)
                        }
                    }
                    val truncated = raw.length > 131_072
                    val lines = raw.toString().lineSequence().take(1024).toList()
                    val boundary = lines.indexOfLast { it.contains("LavaCapacitySaveWitness:") && it.substringAfter("LavaCapacitySaveWitness:").trim() == marker }
                    val rows = JSONArray()
                    var eligible = 0
                    if (boundary >= 0 && !truncated) {
                        for (line in lines.drop(boundary + 1)) {
                            val row = capacitySaveScalarRow(line) ?: continue
                            eligible++
                            if (rows.length() < 16) rows.put(row)
                        }
                    }
                    val captured = JSONObject().put("schema", 1).put("decision", if (decision == "allowed") "allowed" else "blocked")
                        .put("status", if (truncated) "truncated" else if (boundary < 0) "marker-missing" else "captured")
                        .put("markerPresent", boundary >= 0).put("rawTruncated", truncated)
                        .put("eligibleRows", eligible).put("retainedRows", rows.length()).put("rows", rows)
                    if (active.get()) result.compareAndSet(null, captured)
                } catch (error: Throwable) {
                    if (active.get()) result.compareAndSet(null, JSONObject().put("schema", 1).put("status", "capture-error")
                        .put("errorClass", error.javaClass.name.take(80)))
                } finally {
                    try { channel.getAndSet(null)?.close() } catch (_: Throwable) { }
                    done.countDown()
                }
            }, "lava-capacity-save-witness").apply { isDaemon = true }
            worker.start()
            try {
                val completed = done.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!completed) result.compareAndSet(null, JSONObject().put("schema", 1).put("status", "collection-timeout"))
            } finally {
                active.set(false)
                try { channel.getAndSet(null)?.close() } catch (_: Throwable) { }
            }
            return (result.get() ?: JSONObject().put("schema", 1).put("status", "capture-unavailable")).toString()
        } catch (error: Throwable) {
            // Diagnostic collection cannot replace the original AssertionError or retry its action.
            return JSONObject().put("schema", 1).put("status", "capture-error")
                .put("errorClass", error.javaClass.name.take(80)).toString()
        }
    }

    /** Reject unknown keys/types before retaining any log row; no arbitrary log text is printed. */
    private fun capacitySaveScalarRow(line: String): JSONObject? {
        val tags = listOf("LavaToolbarDispatchTrace ", "LavaFilterSaveTrace ", "LavaSavePlusCommandTrace ")
        val tag = tags.firstOrNull { line.contains(it) } ?: return null
        val value = line.substringAfter(tag).trim()
        if (value.length > 384) return null
        val row = try { JSONObject(value) } catch (_: Throwable) { return null }
        val numberKeys: Set<String>
        val booleanKeys: Set<String>
        val enumValues: Map<String, Set<String>>
        when (tag) {
            "LavaToolbarDispatchTrace " -> {
                numberKeys = setOf("schema", "event")
                booleanKeys = setOf("mounted", "interactionEvaluated", "interaction", "readEpochEvaluated", "epochCurrent", "admitted")
                enumValues = mapOf("scope" to setOf("filter-save"), "reason" to setOf("mounted", "interaction", "read-epoch", "admitted"))
            }
            "LavaFilterSaveTrace " -> {
                numberKeys = setOf("schema", "event")
                booleanKeys = setOf("appPresent", "ready", "idPresent", "busy", "capacityPresent")
                enumValues = mapOf("phase" to setOf("callback", "admitted", "upgrade-offer", "native-save", "retired-result", "covered-result"))
            }
            else -> {
                numberKeys = setOf("schema", "event", "request", "commands", "filterSaveCommands", "authorizationCommands")
                booleanKeys = emptySet()
                enumValues = mapOf("kind" to setOf("filter-save", "authorize"), "phase" to setOf("request", "resolved", "rejected"))
            }
        }
        if (row.keys().asSequence().toSet() != numberKeys + booleanKeys + enumValues.keys) return null
        if (numberKeys.any { key ->
                val number = row.opt(key) as? Number
                number == null || !number.toDouble().isFinite() || number.toDouble() != number.toLong().toDouble() || number.toLong() !in 0L..2_147_483_647L
            }) return null
        if (row.optInt("schema") != 1 || booleanKeys.any { row.opt(it) !is Boolean } ||
            enumValues.any { (key, values) -> row.opt(key) !is String || row.optString(key) !in values }) return null
        val captured = JSONObject().put("tag", when (tag) {
            "LavaToolbarDispatchTrace " -> "toolbar"
            "LavaFilterSaveTrace " -> "filter"
            else -> "command"
        }).put("record", row)
        val timestamp = line.trimStart().substringBefore(' ')
        if (timestamp.matches(Regex("[0-9]{1,12}\\.[0-9]{1,9}"))) captured.put("recordedAt", timestamp)
        return captured
    }

    // ---- Proven UI navigation (mirrors LavaReactProductJourneyTest) ------------------------

    private fun openActiveFilterDetail(journey: ReactEmulatorJourney) {
        journey.scrollToTop()
        journey.click("Now filtering", prefix = true)
        journey.waitLabel("Switch or manage filters")
        journey.click("Now filtering", prefix = true)
        journey.waitLabel("Lava blocks these")
    }

    /**
     * The owner command path (`filter.open`/`filter.edit`) sets the native edit session but does not
     * by itself move the packaged navigation. Reach the dedicated filter's editor through the real
     * surface (mirrors [LavaReactProductJourneyTest]'s Guard -> Now filtering -> detail sequence) so
     * the real `Save` control is present, without re-navigating when the editor is already mounted.
     */
    private fun ensureFilterEditor(journey: ReactEmulatorJourney, filterId: String, decision: String, stagedChange: String) {
        val host = journey.requireActivity()
        val capturedHost = checkNotNull(journey.application.nativeVisitHosts.current())
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        val lease = checkNotNull(product.sharedFilterDraftLease(generation, filterId))
        assertTrue("The staged capacity draft must belong to the original native host",
            capturedHost.owner === host && capturedHost.matches(journey.application.nativeVisitHosts.current()))
        if (!journey.visible("Cancel editing")) {
            if (!journey.visible("Lava blocks these")) {
                // Guard -> Now filtering -> active filter detail. When the owner already set the native
                // edit session, the detail opens straight into the editor; the overview label is not
                // required in between.
                journey.scrollToTop()
                journey.click("Now filtering", prefix = true)
                journey.waitLabel("Switch or manage filters")
                journey.click("Now filtering", prefix = true)
            }
            if (!awaitLabel(journey, "Cancel editing", 5_000)) startEditing(journey)
        }
        // Native setup can finish while React still displays the preceding draft's toolbar.
        // Require this fixture's unique rule in the actual mounted tree before Save captures
        // its draft lease. The row may be below the fold; measurement proves React committed
        // it without scrolling or sending another product action. Keep the original wait.
        awaitDomainPresentation(journey, host, generation, ProtectedSurface.FILTERS, visible = {
            assertTrue("Capacity Save must retain its original native host",
                capturedHost.matches(journey.application.nativeVisitHosts.current()))
            assertEquals("Capacity Save must retain its original graph", generation, product.state.value.graphGeneration)
            assertTrue("Capacity Save must retain its original draft lease",
                product.sharedFilterDraftLease(generation, filterId) == lease)
            val snapshot = journey.snapshot()
            assertTrue("Capacity Save must retain its original wire host", snapshot.optString("nativeHostID") == host.nativeHostID)
            assertEquals("Capacity Save must retain its original wire graph", generation, snapshot.optString("generation").toLongOrNull())
            val session = snapshot.optJSONObject("filterSession")
            if (session == null) false else {
                assertTrue("Capacity Save must retain its staged filter", session.optString("id") == filterId)
                assertTrue("Capacity Save must project its original draft lease", session.optString("lease") == lease)
                var stagedRowMounted = false
                journey.instrumentation.runOnMainSync {
                    val matches = mutableListOf<android.view.View>()
                    fun visit(view: android.view.View) {
                        if (view.getTag(com.facebook.react.R.id.react_test_id) == "filter.rule.$decision.$stagedChange" &&
                            view.isAttachedToWindow && view.width > 0 && view.height > 0) matches += view
                        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                    }
                    visit(host.window.decorView)
                    assertTrue("The staged capacity rule must have at most one mounted row", matches.size <= 1)
                    stagedRowMounted = matches.size == 1
                }
                session.optBoolean("editing") && stagedRowMounted && journey.visible("Cancel editing") && journey.visible("Save")
            }
        })
    }

    private fun startEditing(journey: ReactEmulatorJourney) {
        journey.click("Edit", scroll = false)
        journey.await("filter edit mode") { isEditing(journey) }
    }

    private fun addBlockedDomainViaUi(journey: ReactEmulatorJourney, domain: String) {
        journey.scrollToTop()
        journey.click("Block a domain")
        journey.waitLabel("Add blocked domain")
        journey.setText("Domain to block", domain)
        journey.click("Add domain", scroll = false)
        journey.await("UI domain edit in the native owner") { draftRules(journey, "blocked").contains(domain) }
        journey.waitLabel("Cancel editing")
    }

    private fun discardDraft(journey: ReactEmulatorJourney, expectedCapacityReason: String? = null) {
        if (!isEditing(journey)) return
        if (expectedCapacityReason != null) {
            // FilterScreen.changed is the authoritative canSave flag. Restoring Free makes
            // this saved over-cap draft refused, so Cancel may discard directly. This
            // exception never applies to an ordinary dirty draft or an unsaved new filter.
            val before = journey.snapshot()
            val id = before.getJSONObject("filterSession").getString("id")
            journey.await("the same Free capacity-refused draft projected before cancellation") {
                val current = journey.snapshot()
                assertEquals("Capacity cancellation must retain its original graph",
                    before.getString("generation"), current.getString("generation"))
                assertEquals("Capacity cancellation must retain its original host",
                    before.getString("nativeHostID"), current.getString("nativeHostID"))
                assertEquals("Capacity cancellation must retain its original filter",
                    id, current.getJSONObject("filterSession").getString("id"))
                assertTrue("Capacity cancellation is only for the saved filter",
                    current.optJSONObject("newFilter")?.optString("id") != id)
                val editing = current.getJSONObject("filterEditing")
                !current.getJSONObject("plus").getBoolean("enabled") &&
                    editing.optString("upgradeReason") == expectedCapacityReason && !editing.getBoolean("canSave")
            }
        }
        journey.click("Cancel editing", scroll = false)
        if (expectedCapacityReason != null) {
            journey.await("capacity-refused draft cancellation or its actual discard confirmation") {
                !isEditing(journey) || journey.visible("Discard changes?")
            }
        } else journey.waitLabel("Discard changes?")
        if (expectedCapacityReason == null || journey.visible("Discard changes?")) {
            journey.click("Discard", scroll = false)
        }
        journey.await("draft discarded") { !isEditing(journey) }
    }

    // ---- Domain History navigation (mirrors LavaReactComponentMatrixTest) ------------------

    private fun openTopDomainsFromGuard(journey: ReactEmulatorJourney) {
        journey.scrollToTop()
        journey.click("Today", prefix = true)
        journey.waitLabel("Activity")
        journey.click("Top Domains")
        journey.waitLabel("Top Domains")
        journey.waitLabel("Show")
    }

    private fun exerciseDomainRowReview(journey: ReactEmulatorJourney, domain: String,
        active: FilterRow, saved: RuleSet, surface: String) {
        journey.click("Blocked", scroll = false)
        searchDomainHistory(journey, domain)
        journey.waitLabel(domain, prefix = true, excludeEditable = true)
        val host = journey.requireActivity()
        val generation = checkNotNull(journey.application.foregroundProduct.state.value.graphGeneration)
        var returnedAfter: Long? = null
        for ((action, decision) in linkedMapOf("Block" to "blocked", "Allow" to "allowed")) {
            awaitDomainPresentation(journey, host, generation, ProtectedSurface.ACTIVITY, returnedAfter) {
                !isEditing(journey) && currentDomainRowVisible(journey, domain)
            }
            val before = ruleSet(journey, active.id)
            journey.namedAccessibilityAction(domain, action, prefix = true)
            journey.waitLabel("Confirm changes")
            assertEquals("$surface $action must open Review before any durable write", before, ruleSet(journey, active.id))
            assertTrue("$surface $action must review the exact domain", draftRules(journey, decision).contains(domain))
            val reviewSerial = awaitDomainPresentation(journey, host, generation, ProtectedSurface.FILTERS) {
                isEditing(journey) && journey.visible("Confirm changes")
            }
            journey.click("Close", scroll = false)
            journey.await("$surface $action standalone review cancelled") { !isEditing(journey) }
            assertEquals("Cancelling $surface $action must leave the saved filter unchanged", saved, ruleSet(journey, active.id))
            journey.waitLabel(domain, prefix = true, excludeEditable = true)
            returnedAfter = reviewSerial
            awaitDomainPresentation(journey, host, generation, ProtectedSurface.ACTIVITY, returnedAfter) {
                !isEditing(journey) && currentDomainRowVisible(journey, domain)
            }
        }
    }

    private fun currentDomainRowVisible(journey: ReactEmulatorJourney, domain: String): Boolean =
        journey.accessibilityNodes().count { node ->
            node.packageName?.toString() == journey.context.packageName && node.isVisibleToUser &&
                !node.isEditable && node.isEnabled && listOf(node.text, node.contentDescription).any { value ->
                    val label = value?.toString().orEmpty()
                    label.equals(domain, ignoreCase = true) || label.startsWith("$domain,", ignoreCase = true) ||
                        label.startsWith("$domain\n", ignoreCase = true)
                } && listOf("Block", "Allow").all { action -> node.actionList.any { it.label?.toString() == action } }
        } == 1

    private fun awaitDomainPresentation(journey: ReactEmulatorJourney, host: LavaReactActivity,
        generation: Long, surface: ProtectedSurface, afterSerial: Long? = null, visible: () -> Boolean): Long {
        val serialField = LavaReactActivity::class.java.getDeclaredField("presentationSerial").apply { isAccessible = true }
        val surfaceField = LavaReactActivity::class.java.getDeclaredField("protectedPresentation").apply { isAccessible = true }
        var admittedSerial: Long? = null
        journey.await("the exact native domain/Review presentation is ready before its one action") {
            if (!visible()) return@await false
            var ready = false
            journey.instrumentation.runOnMainSync {
                val product = journey.application.foregroundProduct
                val security = product.securityStateForSnapshot(generation) ?: return@runOnMainSync
                val serial = serialField.getLong(host)
                ready = journey.requireActivity() === host &&
                    journey.application.reactHost.currentReactContext?.currentActivity === host &&
                    journey.application.nativeVisitHosts.current()?.owner === host &&
                    host.nativeHostAlive && host.foreground && !host.isFinishing && !host.isDestroyed && host.hasWindowFocus() &&
                    product.state.value.graphGeneration == generation && host.hasPresentationReceipt(generation, serial) &&
                    surfaceField.get(host) == surface && security.isAuthorized(ProtectedSurface.APP_UNLOCK) &&
                    security.isAuthorized(surface) && (afterSerial == null || serial > afterSerial)
                if (ready) admittedSerial = serial
            }
            val current = journey.snapshot()
            ready && current.optString("nativeHostID") == host.nativeHostID &&
                current.optString("generation").toLongOrNull() == generation &&
                !current.optBoolean("presentationBlocked")
        }
        return checkNotNull(admittedSerial)
    }

    /** Close the actual native search/IME before ONE navigation Back to its Activity parent. */
    private fun returnToActivityFromTopDomains(journey: ReactEmulatorJourney) {
        val host = journey.requireActivity()
        val generation = checkNotNull(journey.application.foregroundProduct.state.value.graphGeneration)
        val automation = journey.instrumentation.uiAutomation
        fun currentHost(): Boolean {
            var current = false
            journey.instrumentation.runOnMainSync {
                current = journey.application.reactHost.currentReactContext?.currentActivity === host &&
                    journey.requireActivity() === host && !host.isDestroyed && host.foreground && host.hasWindowFocus() &&
                    journey.application.foregroundProduct.state.value.graphGeneration == generation
            }
            return current
        }
        fun observedIme(): Boolean = automation.windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && window.root?.isVisibleToUser == true
        }
        fun currentSearchField(): AccessibilityNodeInfo? {
            val fields = journey.accessibilityNodes().filter { node ->
                node.packageName?.toString() == journey.context.packageName &&
                    node.viewIdResourceName?.endsWith(":id/search_src_text") == true &&
                    node.isEditable && node.isVisibleToUser &&
                    node.hintText?.toString() in listOf("Search domains", "   Search domains")
            }
            assertTrue("Top Domains must expose at most one native search input", fields.size <= 1)
            return fields.singleOrNull()?.takeIf { it.refresh() && it.isVisibleToUser }
        }
        // TextView exposes its hint as accessibility text for an empty native input (API26+).
        fun emptyNativeQuery(field: AccessibilityNodeInfo): Boolean =
            field.isShowingHintText || field.text.isNullOrEmpty()
        fun clickSearchClose(field: AccessibilityNodeInfo) {
            var button: AccessibilityNodeInfo? = null
            journey.await("the current native search Clear query control") {
                assertTrue("Search closure must stay in its original host and generation", currentHost())
                val buttons = journey.accessibilityNodes().filter { node ->
                    node.packageName?.toString() == journey.context.packageName && node.windowId == field.windowId &&
                        node.viewIdResourceName?.endsWith(":id/search_close_btn") == true &&
                        node.contentDescription?.toString() == "Clear query" &&
                        node.isVisibleToUser && node.isEnabled && node.isClickable &&
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                }
                assertTrue("The current native search must expose at most one Clear query control", buttons.size <= 1)
                button = buttons.singleOrNull()?.takeIf { it.refresh() && it.isVisibleToUser && it.isEnabled }
                button != null
            }
            assertTrue("Search closure must still own its original host and generation", currentHost())
            assertTrue("The native search close control must accept its one action",
                checkNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        if (observedIme()) {
            assertTrue("IME closure must stay in its original host and generation", currentHost())
            journey.back()
            journey.await("the observed Top Domains input method to close") { !observedIme() }
        }
        currentSearchField()?.let { field ->
            // AppCompat first clears a nonempty query; only the empty close icon iconifies search.
            if (!emptyNativeQuery(field)) {
                clickSearchClose(field)
                journey.await("the actual native domain search query to become empty") {
                    currentSearchField()?.let(::emptyNativeQuery) == true
                }
            }
            currentSearchField()?.let { empty ->
                assertTrue("Only an empty native search may now be collapsed", emptyNativeQuery(empty))
                clickSearchClose(empty)
            }
        }
        journey.await("the same Top Domains route to settle before its one navigation Back") {
            if (!journey.visible("Top Domains") || currentSearchField() != null || observedIme()) return@await false
            var ready = false
            journey.instrumentation.runOnMainSync {
                ready = journey.application.reactHost.currentReactContext?.currentActivity === host &&
                    journey.requireActivity() === host && !host.isDestroyed && host.foreground && host.hasWindowFocus() &&
                    journey.application.foregroundProduct.state.value.graphGeneration == generation &&
                    host.isPresentationReady(generation)
            }
            ready
        }
        journey.back()
        journey.waitLabel("Activity")
    }

    private fun openDomainHistoryFromActivity(journey: ReactEmulatorJourney) {
        journey.scrollToTop()
        journey.click("Domain History")
        journey.waitLabel("Show")
    }

    /**
     * Each domain list's native navigation search bar (AppCompat `SearchView`, iconified by default).
     * The shipping search is the deterministic way to surface one retained receipt; no row is
     * fabricated and the retained data is never mutated.
     */
    private fun searchDomainHistory(journey: ReactEmulatorJourney, domain: String) {
        val host = journey.requireActivity()
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        fun assertSearchOwner() {
            journey.instrumentation.runOnMainSync {
                assertTrue("Domain search must retain its original native host",
                    journey.requireActivity() === host &&
                        journey.application.reactHost.currentReactContext?.currentActivity === host &&
                        journey.application.nativeVisitHosts.current()?.owner === host &&
                        host.nativeHostAlive && host.foreground && !host.isFinishing && !host.isDestroyed &&
                        host.hasWindowFocus())
                assertTrue("Domain search must retain its original product", journey.application.foregroundProduct === product)
                assertEquals("Domain search must retain its original graph", generation, product.state.value.graphGeneration)
            }
        }
        var searchWindow = -1
        assertSearchOwner()
        journey.instrumentation.runOnMainSync {
            searchWindow = host.window.decorView.createAccessibilityNodeInfo().windowId
        }
        assertTrue("Domain search must have its actual application window", searchWindow >= 0)
        val automation = journey.instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        var searchButton: AccessibilityNodeInfo? = null
        journey.await("the current domain-list native Search button") {
            assertSearchOwner()
            val candidates = journey.accessibilityNodes().filter { node ->
                node.windowId == searchWindow && node.packageName?.toString() == journey.context.packageName &&
                    node.viewIdResourceName?.endsWith(":id/search_button") == true &&
                    node.className?.toString() == "android.widget.ImageView" &&
                    node.contentDescription?.toString() == "Search" && node.isVisibleToUser &&
                    node.isEnabled && node.isClickable &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            }
            assertTrue("Domain-list search must identify one native button", candidates.size <= 1)
            searchButton = candidates.singleOrNull()?.takeIf { it.refresh() && candidates.singleOrNull() == it }
            searchButton != null
        }
        val button = checkNotNull(searchButton)
        assertSearchOwner()
        assertTrue(button.refresh())
        assertEquals("Only the original application window may open domain search", searchWindow, button.windowId)
        assertTrue("The actual native Search button must accept its one action",
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        var nativeSearchField: AutoCompleteTextView? = null
        fun searchFields(): List<AccessibilityNodeInfo> {
            assertSearchOwner()
            var source: AccessibilityNodeInfo? = null
            journey.instrumentation.runOnMainSync {
                val field = host.window.decorView.findViewById<AutoCompleteTextView>(androidx.appcompat.R.id.search_src_text)
                if (field == null || !field.isAttachedToWindow || !field.isShown || field.width <= 0 || field.height <= 0)
                    return@runOnMainSync
                nativeSearchField?.let { original ->
                    assertTrue("Domain search must retain its original native input", field === original)
                }
                nativeSearchField = field
                source = field.createAccessibilityNodeInfo()
            }
            val native = source ?: return emptyList()
            assertEquals("The native domain input must retain its original application window", searchWindow, native.windowId)
            return journey.accessibilityNodes().filter { node ->
                node == native && node.windowId == searchWindow &&
                    node.packageName?.toString() == journey.context.packageName &&
                    node.viewIdResourceName?.endsWith(":id/search_src_text") == true &&
                    node.className == native.className && node.isEditable && node.isVisibleToUser && node.isEnabled &&
                    node.hintText?.toString() in listOf("Search domains", "   Search domains") &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            }
        }
        var searchField: AccessibilityNodeInfo? = null
        journey.await("the expanded domain-list native search input") {
            val fields = searchFields()
            assertTrue("Domain-list search must expose one actual native input", fields.size <= 1)
            searchField = fields.singleOrNull()?.takeIf { it.refresh() && searchFields().singleOrNull() == it }
            searchField != null
        }
        val field = checkNotNull(searchField)
        assertSearchOwner()
        assertTrue(field.refresh())
        assertEquals("Only the original current native input may receive the query", field, searchFields().single())
        assertTrue("The actual native history input must accept its one edit",
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, domain)
            }))
        // Settle the one real edit before dismissing the keyboard. Row visibility is
        // asserted afterward, so an IME-constrained viewport cannot block its own release.
        journey.await("the original native domain search input contains its accepted query") {
            val current = searchFields().singleOrNull() ?: return@await false
            current.refresh() && current == field && current.text?.toString() == domain
        }
        assertSearchOwner()
        // Close only a genuinely visible input method; do not turn Back into route navigation.
        if (automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                it.root?.isVisibleToUser == true }) journey.back()
        journey.await("the original queried domain row is visible with its existing Block and Allow actions") {
            val current = searchFields().singleOrNull() ?: return@await false
            current.refresh() && current == field && current.text?.toString() == domain &&
                currentDomainRowVisible(journey, domain)
        }
    }

    // ---- Retained synthetic local receipt --------------------------------------------------

    private fun writeRetainedActivityReceipt(journey: ReactEmulatorJourney, domain: String) {
        ActivityDatabase(journey.context).use { database ->
            val repository = ActivityRepository(database)
            val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
            val event = DnsDecisionEvent(
                id = System.currentTimeMillis(),
                timestampMillis = System.currentTimeMillis(),
                normalizedDomain = domain,
                decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
            )
            assertTrue("The real persisted logging policy must admit the synthetic receipt", writer.record(event))
            assertTrue(repository.history(searchText = domain).isNotEmpty())
        }
        // Refresh the production Activity owner after the fixture transaction commits, exactly as
        // the passing [LavaReactComponentMatrixTest] history journey does.
        runBlocking { journey.application.foregroundProduct.refreshActivity() }
    }

    // ---- Command-level fixtures (setup/cleanup only; acceptance is UI) ----------------------

    private fun fillList(journey: ReactEmulatorJourney, baseline: Baseline, filterId: String, decision: String, target: Int) {
        val current = ruleSet(journey, filterId)
        val need = target - (if (decision == "blocked") current.blocked else current.allowed).size
        check(need >= 0) { "The dedicated $decision list already exceeds the target" }
        if (need == 0) return
        journey.completed("filter.open", JSONObject().put("id", filterId))
        if (!isEditing(journey)) journey.completed("filter.edit", JSONObject().put("id", filterId))
        repeat(need) { index ->
            val domain = "ui-fill-$decision-$index-${UUID.randomUUID().toString().take(8)}.invalid"
            journey.completed(
                "filter.domain",
                JSONObject().put("id", filterId).put("domain", domain).put("decision", decision).put("remove", false),
            )
            baseline.added += AddedRule(filterId, decision, domain)
        }
        val review = reviewAfterSave(journey, filterId)
        applyReview(journey, filterId, review)
        journey.await("dedicated $decision list filled", 90_000) {
            val rules = ruleSet(journey, filterId)
            (if (decision == "blocked") rules.blocked else rules.allowed).size == target
        }
    }

    private fun removeRules(journey: ReactEmulatorJourney, filterId: String, decision: String, domains: List<String>) {
        if (domains.isEmpty()) return
        val rules = ruleSet(journey, filterId)
        val present = if (decision == "blocked") rules.blocked else rules.allowed
        val toRemove = domains.filter { it in present }
        if (toRemove.isEmpty()) return
        journey.completed("filter.open", JSONObject().put("id", filterId))
        if (!isEditing(journey)) journey.completed("filter.edit", JSONObject().put("id", filterId))
        toRemove.forEach { domain ->
            journey.completed(
                "filter.domain",
                JSONObject().put("id", filterId).put("domain", domain).put("decision", decision).put("remove", true),
            )
        }
        val review = reviewAfterSave(journey, filterId)
        applyReview(journey, filterId, review)
        journey.await("fixture rules removed", 90_000) {
            val current = ruleSet(journey, filterId)
            toRemove.none { it in (if (decision == "blocked") current.blocked else current.allowed) }
        }
    }

    private fun reviewAfterSave(journey: ReactEmulatorJourney, filterId: String): String {
        assertEquals("A changed draft stops at Review", "review",
            journey.completed("filter.save", JSONObject().put("id", filterId)).getString("value"))
        return journey.completed("filter.review", JSONObject().put("id", filterId)).getString("value")
    }

    private fun applyReview(journey: ReactEmulatorJourney, filterId: String, review: String) {
        journey.completed("filter.apply", JSONObject().put("id", filterId).put("review", review))
    }

    private fun switchActive(journey: ReactEmulatorJourney, id: String) {
        journey.completed("filter.switch", JSONObject().put("id", id))
        journey.await("active filter switched to the dedicated filter", 60_000) { activeFilter(journey).id == id }
    }

    private fun deleteFilter(journey: ReactEmulatorJourney, id: String) {
        if (id !in savedFilterIds(journey)) return
        journey.completed("filter.delete", JSONObject().put("id", id))
        journey.await("created filter removed", 60_000) { id !in savedFilterIds(journey) }
    }

    private fun countFor(journey: ReactEmulatorJourney, filterId: String, decision: String): Int {
        val rules = ruleSet(journey, filterId)
        return if (decision == "blocked") rules.blocked.size else rules.allowed.size
    }

    private fun plusMessageFor(decision: String): String =
        if (decision == "blocked") "Upgrade to Lava Plus to block more domains"
        else "Upgrade to Lava Plus to add more exceptions"

    // ---- Baseline capture / restore --------------------------------------------------------

    private fun captureBaseline(journey: ReactEmulatorJourney): Baseline {
        assertFalse("A prior journey left a library draft open", libraryEditingActive(journey))
        assertFalse("A prior journey left a filter draft open", isEditing(journey))
        assertTrue("A prior journey left a new-filter template open", journey.snapshot().isNull("newFilter"))
        assertNoUiDomains(journey)
        assertFalse("A prior journey left Plus enabled", journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
        val ids = savedFilterIds(journey)
        return Baseline(activeFilter(journey).id, ids, ids.associateWith { ruleSet(journey, it) })
    }

    private fun restoreBaseline(journey: ReactEmulatorJourney, baseline: Baseline) {
        var firstFailure: Throwable? = null
        try {
            if (libraryEditingActive(journey)) {
                runCatching { journey.completed("library.cancel") }
                SystemClock.sleep(200)
            }
            cancelAnyDraft(journey)
            baseline.added.groupBy { it.filterId to it.decision }.forEach { (key, rules) ->
                removeRules(journey, key.first, key.second, rules.map { it.domain })
            }
            if (activeFilter(journey).id != baseline.activeId) {
                runCatching { journey.completed("filter.switch", JSONObject().put("id", baseline.activeId)) }
                journey.await("baseline active filter restored", 60_000) {
                    activeFilter(journey).id == baseline.activeId
                }
            }
            baseline.created.forEach { deleteFilter(journey, it) }
            baseline.revokePlus?.invoke()
        } catch (failure: Throwable) {
            firstFailure = failure
        } finally {
            val cause = firstFailure?.let { " (original failure: $it)" } ?: ""
            assertFalse("No library draft may survive$cause", libraryEditingActive(journey))
            assertFalse("No filter draft may survive$cause", isEditing(journey))
            assertTrue("No new-filter template may survive$cause", journey.snapshot().isNull("newFilter"))
            assertEquals("The baseline active filter must be restored$cause", baseline.activeId, activeFilter(journey).id)
            assertEquals("The baseline filter set must be restored$cause", baseline.filterIds, savedFilterIds(journey))
            baseline.filterIds.forEach { id ->
                assertEquals("Filter $id must be restored", baseline.rules.getValue(id), ruleSet(journey, id))
            }
            assertNoUiDomains(journey)
            assertFalse("The controlled Plus entitlement must be restored",
                journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
        }
    }

    private fun awaitLabel(journey: ReactEmulatorJourney, label: String, timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (journey.visible(label)) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun dismissAlert(journey: ReactEmulatorJourney) {
        if (awaitLabel(journey, "OK", 1_000)) runCatching { journey.click("OK", scroll = false) }
    }

    /** Close an open Review sheet (its unmount retires the standalone token) before teardown. */
    private fun closeAnyReview(journey: ReactEmulatorJourney) {
        if (journey.visible("Confirm changes")) {
            runCatching { journey.click("Close", scroll = false) }
        }
    }

    /**
     * Deterministically retire any standalone domain review the bridge still owns. The native token
     * is a bridge field that only `domains.cancel` (with the matching token) or a successful
     * `filter.apply` clears, and it is not projected into the snapshot. Stage a throwaway proposal
     * (overwriting any leftover token and draft) and immediately cancel it, so a failed journey can
     * never strand a token that would make a later `filter.apply` on the same filter read as stale.
     * Runs after [cancelAnyDraft], so the active filter holds no unsaved draft to refuse the stage.
     */
    private fun clearAnyStandaloneReview(journey: ReactEmulatorJourney) {
        runCatching {
            val value = journey.completed(
                "domains.stage",
                JSONObject().put("domain", "ui-teardown-${UUID.randomUUID()}.invalid").put("decision", "blocked"),
            ).getJSONObject("value")
            if (!value.has("rejection")) {
                journey.completed("domains.cancel", JSONObject().put("token", value.getString("standaloneReview")))
            }
        }
    }

    private fun cancelAnyDraft(journey: ReactEmulatorJourney) {
        if (!isEditing(journey)) return
        val id = journey.snapshot().getJSONObject("filterSession").getString("id")
        runCatching { journey.completed("filter.cancel", JSONObject().put("id", id)) }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline && isEditing(journey)) SystemClock.sleep(100)
    }

    private fun assertNoUiDomains(journey: ReactEmulatorJourney) {
        val offenders = savedFilterIds(journey).flatMap { id ->
            val rules = ruleSet(journey, id)
            (rules.blocked + rules.allowed).filter { it.startsWith("ui-") }.map { "$id:$it" }
        }
        assertTrue("No UI fixture domain may survive a journey: $offenders", offenders.isEmpty())
    }

    // ---- Controlled verified-entitlement adapter (never a genuine Play purchase) -----------

    private fun grantControlledPlus(journey: ReactEmulatorJourney): () -> Unit {
        val backend = journey.awaitPlusBackend()
        val entitlement = backend.entitlement
        val before = runBlocking { entitlement.initialize(); entitlement.state.value.hasPlus }
        assertFalse("This controlled pass must begin from the real Free installation", before)
        assertTrue("The controlled entitlement authority must accept the verified reading",
            runBlocking {
                entitlement.apply(VerifiedPlusReading(active = true, confidence = EntitlementReadingConfidence.CONFIRMED))
            })
        println("LAVA_UI_PLUS_SOURCE=controlled-verified-entitlement confirmed=true play-purchase=false")
        try {
            journey.await("controlled Plus entitlement projected", 30_000) {
                journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            }
        } catch (failure: Throwable) {
            runCatching {
                runBlocking {
                    entitlement.apply(VerifiedPlusReading(active = false, confidence = EntitlementReadingConfidence.CONFIRMED))
                }
            }
            throw failure
        }
        return {
            assertTrue("The controlled entitlement must be restorable",
                runBlocking {
                    entitlement.apply(VerifiedPlusReading(active = false, confidence = EntitlementReadingConfidence.CONFIRMED))
                })
            journey.await("controlled Plus entitlement restored", 30_000) {
                !journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            }
        }
    }

    private fun ReactEmulatorJourney.awaitPlusBackend(): com.lavasec.app.billing.NativePlusBackend = run {
        var backend: com.lavasec.app.billing.NativePlusBackend? = null
        await("native Plus backend") {
            backend = application.plusBackend.value
            backend != null
        }
        checkNotNull(backend)
    }

    // ---- Snapshot helpers ------------------------------------------------------------------

    private fun libraryEditingActive(journey: ReactEmulatorJourney): Boolean =
        journey.snapshot().optJSONObject("libraryEditing")?.optBoolean("active") == true

    private fun isEditing(journey: ReactEmulatorJourney): Boolean =
        journey.snapshot().optJSONObject("filterSession")?.optBoolean("editing") == true

    private fun activeFilter(journey: ReactEmulatorJourney): FilterRow {
        val row = rows(journey.snapshot().getJSONObject("filters").getJSONArray("rows")).first { it.getBoolean("active") }
        return FilterRow(row.getString("id"), row.getString("name"))
    }

    private fun inactiveFilter(journey: ReactEmulatorJourney): FilterRow {
        val row = rows(journey.snapshot().getJSONObject("filters").getJSONArray("rows")).first { !it.getBoolean("active") }
        return FilterRow(row.getString("id"), row.getString("name"))
    }

    private fun savedFilterIds(journey: ReactEmulatorJourney): Set<String> =
        rows(journey.snapshot().getJSONObject("filters").getJSONArray("rows")).map { it.getString("id") }.toSet()

    private fun draftRules(journey: ReactEmulatorJourney, decision: String): List<String> =
        strings(journey.snapshot().getJSONObject("filterSession").getJSONArray(decision))

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)

    private fun rows(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).map(array::getJSONObject)

    private fun ruleSet(journey: ReactEmulatorJourney, filterId: String): RuleSet = runBlocking {
        val read = CredentialFilterConfigurationStore
            .credentialProtected(journey.context, FeatureLimits.PLUS.maxFilterRules)
            .readAuthoritative()
        assertTrue("The authoritative configuration/artifact must reopen: $read",
            read is CredentialFilterConfigurationRead.Available)
        val profile = checkNotNull((read as CredentialFilterConfigurationRead.Available).state.configuration.profile(filterId))
        RuleSet(profile.manualRules.blockedDomains, profile.manualRules.allowedDomains)
    }
}
