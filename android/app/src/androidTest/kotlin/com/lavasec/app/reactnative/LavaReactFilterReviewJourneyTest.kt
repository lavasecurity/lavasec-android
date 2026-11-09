package com.lavasec.app.reactnative

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.billing.VerifiedPlusReading
import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.product.FilterLibraryEditOutcome
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
 * #856 P2R (#6f9ed1a5) production acceptance for the Review gate on changed filter drafts and
 * Allow/Block domain actions.
 *
 * Every case drives the production command bridge exactly as the passing
 * [LavaReactFilterDraftDeviceTest] does (`filter.open`/`filter.edit`/`filter.domain`/`filter.save`/
 * `filter.review`/`filter.apply`/`filter.cancel`, `library.edit`/`library.form`/`library.cancel`,
 * `domains.stage`/`domains.cancel`) and asserts the native owner's results — a real owner journey,
 * not a mock. No UI navigation is required beyond onboarding, so there is no fragile create sheet,
 * no Top-Domains row lookup, and no durable domain-history receipt to clean up.
 *
 * The UI entry surfaces (ContextMenu Block/Allow rendering, the contextual Plus sheet) are the
 * concern of the RN/Jest suites; this identity asserts the native Review handoff those surfaces
 * are driven by.
 *
 * A changed active draft, a changed inactive draft and a changed new filter each stop at the native
 * Review boundary; Confirm/apply persists the reviewed change and Cancel leaves the saved library
 * untouched. The native projection suppresses `newFilter` once its id is a saved row, so the
 * new-filter commit no longer trips the shared parser's `New filter is already saved` guard.
 *
 * The emulator runner clears data once, so each case captures the exact baseline and restores it in
 * `finally`.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactFilterReviewJourneyTest {

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
     * P2R case 1: a changed active draft, a changed inactive draft and a changed new filter each
     * stop at the native Review boundary before any durable write. The active filter confirms/applies
     * the exact reviewed change AND the new filter commit/apply persists its change; the cancel path
     * leaves every saved library untouched.
     */
    @Test
    fun shippingChangedActiveInactiveAndNewFilterSavesEnterVisibleReviewThenCancelOrConfirm() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val baseline = captureBaseline(journey)
            withRestoredBaseline(journey, baseline) {
                baseline.revokePlus = grantControlledPlus(journey)
                val active = activeFilter(journey)
                val inactive = inactiveFilter(journey)
                val cancelled = "p2r-cancel-${UUID.randomUUID()}.invalid"
                val confirmed = "p2r-confirm-${UUID.randomUUID()}.invalid"
                val inactiveDomain = "p2r-inactive-${UUID.randomUUID()}.invalid"
                val newDomain = "p2r-new-${UUID.randomUUID()}.invalid"

                // Active: changed draft -> review; nothing durable; cancel leaves saved unchanged.
                editRule(journey, active.id, "blocked", cancelled)
                assertEquals("A changed active draft stops at Review", "review", saveDraft(journey, active.id))
                assertTrue("The active review handoff token must be real", reviewDraft(journey, active.id).isNotBlank())
                assertEquals("Review must precede any durable mutation", baseline.rules.getValue(active.id),
                    ruleSet(journey, active.id))
                journey.completed("filter.cancel", JSONObject().put("id", active.id))
                journey.await("active draft cancelled") { !isEditing(journey) }
                assertEquals("Cancel must leave the saved active filter unchanged", baseline.rules.getValue(active.id),
                    ruleSet(journey, active.id))

                // Active: changed draft -> review -> apply persists the exact change.
                editRule(journey, active.id, "blocked", confirmed)
                assertEquals("A changed active draft stops at Review", "review", saveDraft(journey, active.id))
                val activeReview = reviewDraft(journey, active.id)
                assertEquals("Review must precede the durable write", baseline.rules.getValue(active.id),
                    ruleSet(journey, active.id))
                applyReview(journey, active.id, activeReview)
                journey.await("confirmed active review persisted", 60_000) {
                    ruleSet(journey, active.id).blocked.contains(confirmed)
                }
                baseline.added += AddedRule(active.id, "blocked", confirmed)
                journey.assertNoFallback()

                // Inactive: same review gate; cancel leaves the inactive filter unchanged.
                editRule(journey, inactive.id, "blocked", inactiveDomain)
                assertEquals("A changed inactive draft stops at Review", "review", saveDraft(journey, inactive.id))
                assertTrue("The inactive review handoff token must be real", reviewDraft(journey, inactive.id).isNotBlank())
                assertEquals("Review must precede the inactive durable write", baseline.rules.getValue(inactive.id),
                    ruleSet(journey, inactive.id))
                journey.completed("filter.cancel", JSONObject().put("id", inactive.id))
                journey.await("inactive draft cancelled") { !isEditing(journey) }
                assertEquals("Cancel must leave the inactive filter unchanged", baseline.rules.getValue(inactive.id),
                    ruleSet(journey, inactive.id))

                // New filter: a changed UNSAVED new-filter draft stops at Review, then Confirm/apply
                // persists it. The native projection suppresses `newFilter` once its id is a saved
                // row, so the commit no longer trips the shared parser's "New filter is already
                // saved" guard.
                val hosted = createUnsavedFilterViaOwner(journey, baseline)
                journey.completed(
                    "filter.domain",
                    JSONObject().put("id", hosted).put("domain", newDomain).put("decision", "blocked").put("remove", false),
                )
                assertEquals("A changed new filter stops at Review", "review", saveDraft(journey, hosted))
                val newReview = reviewDraft(journey, hosted)
                assertTrue("The new-filter review handoff token must be real", newReview.isNotBlank())
                assertFalse("Review must precede the new filter's durable write", savedFilterIds(journey).contains(hosted))
                applyReview(journey, hosted, newReview)
                journey.await("confirmed new filter review persisted", 60_000) {
                    savedFilterIds(journey).contains(hosted) && ruleSet(journey, hosted).blocked.contains(newDomain)
                }
                baseline.added += AddedRule(hosted, "blocked", newDomain)
                journey.assertNoFallback()
            }
        }
    }

    /**
     * P2R case 2: an unchanged existing edit and an untouched new-filter template take the no-op
     * path — the owner returns `saved` with no Review handoff, and the untouched template commits
     * directly. The new-filter suppression in the native projection keeps the commit observable.
     */
    @Test
    fun shippingUnchangedEditAndUntouchedNewTemplateSkipVisibleReview() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val baseline = captureBaseline(journey)
            withRestoredBaseline(journey, baseline) {
                baseline.revokePlus = grantControlledPlus(journey)
                val active = activeFilter(journey)

                journey.completed("filter.open", JSONObject().put("id", active.id))
                journey.completed("filter.edit", JSONObject().put("id", active.id))
                journey.await("unchanged edit session") { isEditing(journey) }
                assertEquals("An unchanged edit must save without Review", "saved", saveDraft(journey, active.id))
                journey.await("unchanged edit closed") { !isEditing(journey) }
                assertEquals("An unchanged edit must not mutate the saved filter", baseline.rules.getValue(active.id),
                    ruleSet(journey, active.id))

                // Untouched new-filter template: Create opens the detail on its template and Save is
                // the one creation that commits without a Review handoff.
                val hosted = createUnsavedFilterViaOwner(journey, baseline)
                assertEquals("An untouched new template must save without Review", "saved", saveDraft(journey, hosted))
                journey.await("untouched new template committed", 60_000) {
                    savedFilterIds(journey).contains(hosted) && !isEditing(journey)
                }
                journey.assertNoFallback()
            }
        }
    }

    /**
     * P2R case 3: Allow and Block each transfer a native staged proposal to a real single-use
     * Review handoff token. The proposal is not durable until an apply; cancelling retires it.
     *
     * The Top-Domains/Domain-History context-menu entry points both call this same
     * `domains.stage` owner; the menu rendering/registration is pinned by the RN component test,
     * so this identity asserts the owner handoff deterministically rather than a fragile UI lookup.
     */
    @Test
    fun shippingTopDomainsAndDomainHistoryAllowAndBlockTransferStagedReviewToVisibleReview() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val baseline = captureBaseline(journey)
            withRestoredBaseline(journey, baseline) {
                val active = activeFilter(journey)
                val saved = ruleSet(journey, active.id)
                val blocked = "p2r-top-blocked-${UUID.randomUUID()}.invalid"
                val allowed = "p2r-top-allowed-${UUID.randomUUID()}.invalid"

                val blockToken = stageDomain(journey, "blocked", blocked)
                assertTrue("Block must hand off a real review token", blockToken.isNotBlank())
                assertTrue("The staged blocked proposal must be in the native draft",
                    draftRules(journey, "blocked").contains(blocked))
                assertEquals("Block must not durably mutate before confirmation", saved, ruleSet(journey, active.id))
                journey.completed("domains.cancel", JSONObject().put("token", blockToken))
                journey.await("blocked standalone review cancelled") { !isEditing(journey) }
                assertEquals("A cancelled Block must leave the filter unchanged", saved, ruleSet(journey, active.id))

                val allowToken = stageDomain(journey, "allowed", allowed)
                assertTrue("Allow must hand off a real review token", allowToken.isNotBlank())
                assertTrue("The staged allowed proposal must be in the native draft",
                    draftRules(journey, "allowed").contains(allowed))
                assertEquals("Allow must not durably mutate before confirmation", saved, ruleSet(journey, active.id))
                journey.completed("domains.cancel", JSONObject().put("token", allowToken))
                journey.await("allowed standalone review cancelled") { !isEditing(journey) }
                assertEquals("A cancelled Allow must leave the filter unchanged", saved, ruleSet(journey, active.id))

                // Actual shipping lifecycle: native host destruction must retire the standalone
                // token's original draft even if no React domains.cancel reaches the dead root.
                val product = journey.application.foregroundProduct
                val originalHost = checkNotNull(journey.application.nativeVisitHosts.current())
                val originalGeneration = journey.snapshot().getString("generation").toLong()
                fun savedConfiguration() = runBlocking {
                    val read = CredentialFilterConfigurationStore
                        .credentialProtected(journey.context, FeatureLimits.PLUS.maxFilterRules)
                        .readAuthoritative()
                    assertTrue("The saved library must reopen authoritatively",
                        read is CredentialFilterConfigurationRead.Available)
                    (read as CredentialFilterConfigurationRead.Available).state.configuration
                }
                val savedLibrary = savedConfiguration()
                val abandonedDomain = "p2r-destroyed-${UUID.randomUUID()}.invalid"
                val abandonedToken = stageDomain(journey, "blocked", abandonedDomain)
                val abandonedLease = checkNotNull(product.sharedFilterDraftLease(originalGeneration, active.id))
                assertTrue("Original standalone review must hold the staged draft",
                    draftRules(journey, "blocked").contains(abandonedDomain))
                assertEquals("Stage must retain the original host", originalHost.nativeHostID,
                    journey.snapshot().getString("nativeHostID"))
                assertEquals("Stage must retain the original graph", originalGeneration.toString(),
                    journey.snapshot().getString("generation"))
                assertEquals("Staging must not change the saved library", savedLibrary, savedConfiguration())
                // close() invokes the existing exact-instance finishActivity/destroy wait.
                journey.close()
                assertFalse("The captured native host must be destroyed", originalHost.retains(null))
                journey.launch()
                val freshHost = checkNotNull(journey.application.nativeVisitHosts.current())
                assertFalse("Reopening must create a distinct native host", freshHost.owner === originalHost.owner)
                assertFalse("Reopening must create a fresh host nonce", freshHost.nativeHostID == originalHost.nativeHostID)
                journey.await("destroyed standalone host draft retired and fresh host projected") {
                    assertTrue("The reopened exact host must remain current",
                        freshHost.matches(journey.application.nativeVisitHosts.current()))
                    assertEquals("Reopening must retain the original product graph", originalGeneration,
                        product.state.value.graphGeneration)
                    val current = journey.snapshot()
                    current.getString("nativeHostID") == freshHost.nativeHostID &&
                        current.getString("generation") == originalGeneration.toString() &&
                        product.sharedFilterDraftLease(originalGeneration, active.id) == null && !isEditing(journey)
                }
                assertEquals("Host retirement must preserve the saved library", savedLibrary, savedConfiguration())
                val freshDomain = "p2r-reopened-${UUID.randomUUID()}.invalid"
                val freshToken = stageDomain(journey, "allowed", freshDomain)
                val freshLease = checkNotNull(product.sharedFilterDraftLease(originalGeneration, active.id))
                assertFalse("Fresh staging must receive a distinct draft lease", freshLease == abandonedLease)
                assertTrue("Fresh staging must succeed after host retirement",
                    draftRules(journey, "allowed").contains(freshDomain))
                journey.completed("domains.cancel", JSONObject().put("token", abandonedToken))
                assertEquals("An old token must not retire the fresh draft", freshLease,
                    product.sharedFilterDraftLease(originalGeneration, active.id))
                assertTrue("An old token must preserve the successor staged domain",
                    draftRules(journey, "allowed").contains(freshDomain))
                assertEquals("A stale Cancel must not change the saved library", savedLibrary, savedConfiguration())
                journey.completed("domains.cancel", JSONObject().put("token", freshToken))
                journey.await("fresh standalone review cancelled after reopen") {
                    !isEditing(journey) && product.sharedFilterDraftLease(originalGeneration, active.id) == null
                }
                assertEquals("Fresh Cancel must preserve the saved library", savedLibrary, savedConfiguration())
                journey.assertNoFallback()
            }
        }
    }

    /**
     * P2R case 4: a full free blocked list and a full free allowed list each return the typed
     * capacity refusal; after the controlled verified entitlement the original action is
     * re-staged exactly once into a fresh Review handoff with no automatic apply. The full list is
     * the inactive dedicated filter (the shared active filter is never filled) and is fully
     * restored.
     */
    @Test
    fun shippingFullFreeBlockedAndAllowedListsReturnIntoReviewAfterControlledPlusWithoutAutoApply() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val baseline = captureBaseline(journey)
            val dedicated = inactiveFilter(journey)
            withRestoredBaseline(journey, baseline) {
                switchActive(journey, dedicated.id)
                fillList(journey, baseline, dedicated.id, "blocked", FeatureLimits.FREE.maxBlockedDomains)
                fillList(journey, baseline, dedicated.id, "allowed", FeatureLimits.FREE.maxAllowedDomains)
                val blocked = "p2r-plus-blocked-${UUID.randomUUID()}.invalid"
                val allowed = "p2r-plus-allowed-${UUID.randomUUID()}.invalid"

                assertTrue("A full free blocked list must return the typed capacity refusal",
                    stageDomainRejection(journey, "blocked", blocked).getJSONObject("rejection").getBoolean("limitReached"))
                val revokeBlock = grantControlledPlus(journey)
                try {
                    val token = stageDomain(journey, "blocked", blocked)
                    assertTrue("The re-staged blocked proposal must hand off once", token.isNotBlank())
                    assertEquals("The re-staged blocked proposal must appear exactly once",
                        1, draftRules(journey, "blocked").count { it == blocked })
                    assertEquals("The Plus return must not auto-apply the staged rule",
                        FeatureLimits.FREE.maxBlockedDomains, ruleSet(journey, dedicated.id).blocked.size)
                    journey.completed("domains.cancel", JSONObject().put("token", token))
                    journey.await("blocked plus return cancelled") { !isEditing(journey) }
                } finally {
                    revokeBlock()
                }

                assertTrue("A full free allowed list must return the typed capacity refusal",
                    stageDomainRejection(journey, "allowed", allowed).getJSONObject("rejection").getBoolean("limitReached"))
                val revokeAllow = grantControlledPlus(journey)
                try {
                    val token = stageDomain(journey, "allowed", allowed)
                    assertTrue("The re-staged allowed proposal must hand off once", token.isNotBlank())
                    assertEquals("The re-staged allowed proposal must appear exactly once",
                        1, draftRules(journey, "allowed").count { it == allowed })
                    assertEquals("The Plus return must not auto-apply the staged rule",
                        FeatureLimits.FREE.maxAllowedDomains, ruleSet(journey, dedicated.id).allowed.size)
                    journey.completed("domains.cancel", JSONObject().put("token", token))
                    journey.await("allowed plus return cancelled") { !isEditing(journey) }
                } finally {
                    revokeAllow()
                }
                journey.assertNoFallback()
            }
        }
    }

    // ---- Command helpers (the proven `LavaReactFilterDraftDeviceTest` pattern) -------------------

    private fun editRule(journey: ReactEmulatorJourney, filterId: String, decision: String, domain: String) {
        journey.completed("filter.open", JSONObject().put("id", filterId))
        if (!isEditing(journey)) journey.completed("filter.edit", JSONObject().put("id", filterId))
        journey.completed(
            "filter.domain",
            JSONObject().put("id", filterId).put("domain", domain).put("decision", decision).put("remove", false),
        )
        journey.await("staged rule in the native draft") { draftRules(journey, decision).contains(domain) }
    }

    private fun saveDraft(journey: ReactEmulatorJourney, filterId: String): String =
        journey.completed("filter.save", JSONObject().put("id", filterId)).getString("value")

    private fun reviewDraft(journey: ReactEmulatorJourney, filterId: String): String =
        journey.completed("filter.review", JSONObject().put("id", filterId)).getString("value")

    private fun applyReview(journey: ReactEmulatorJourney, filterId: String, review: String) {
        journey.completed("filter.apply", JSONObject().put("id", filterId).put("review", review))
    }

    private fun stageDomain(journey: ReactEmulatorJourney, decision: String, domain: String): String {
        val product = journey.application.foregroundProduct
        val before = journey.snapshot()
        val generation = before.getString("generation").toLong()
        val host = checkNotNull(journey.application.nativeVisitHosts.current())
        assertTrue("Stage must originate from the live native host", host.matches(journey.application.nativeVisitHosts.current()))
        assertEquals("Stage must use the original native host", host.nativeHostID, before.getString("nativeHostID"))
        assertTrue("Stage must use the original product graph", product.state.value.graphGeneration == generation)
        val value = journey.completed(
            "domains.stage", JSONObject().put("domain", domain).put("decision", decision)
                .put("nativeHostID", host.nativeHostID), expectedGeneration = generation,
        ).getJSONObject("value")
        assertFalse("Expected a staged review token, got $value", value.has("rejection"))
        val token = value.getString("standaloneReview")
        val filterId = value.getString("id")
        val lease = checkNotNull(product.sharedFilterDraftLease(generation, filterId)) {
            "The stage receipt must retain its original native draft lease"
        }
        // Stage acknowledges the actual owner mutation; its combined UI StateFlow publishes
        // separately. Wait for that exact draft's projection without re-staging or adopting a
        // replacement host, graph or same-ID draft.
        fun assertOriginalStageOwner() {
            assertTrue("Stage host changed before its projection", host.matches(journey.application.nativeVisitHosts.current()))
            assertTrue("Stage graph changed before its projection", product.state.value.graphGeneration == generation)
            assertEquals("Stage draft lease changed before its projection", lease,
                product.sharedFilterDraftLease(generation, filterId))
        }
        journey.await("the exact staged domain review projection") {
            assertOriginalStageOwner()
            val current = journey.snapshot()
            assertOriginalStageOwner()
            assertEquals("Stage projection belongs to another native host", host.nativeHostID, current.getString("nativeHostID"))
            assertEquals("Stage projection belongs to another graph", generation.toString(), current.getString("generation"))
            val session = current.optJSONObject("filterSession")
            session != null && session.optString("id") == filterId &&
                session.optString("lease") == lease && session.optBoolean("editing") &&
                strings(session.getJSONArray(decision)).count { it == domain } == 1
        }
        return token
    }

    private fun stageDomainRejection(journey: ReactEmulatorJourney, decision: String, domain: String): JSONObject =
        journey.completed("domains.stage", JSONObject().put("domain", domain).put("decision", decision)).getJSONObject("value")

    /**
     * Creates an UNSAVED new-filter draft through the production library owner (no fragile create
     * sheet, no `library.cancel` after create). The create opens the detail as an unsaved draft;
     * `filter.save`/`filter.review`/`filter.cancel` operate on it directly.
     */
    private fun createUnsavedFilterViaOwner(journey: ReactEmulatorJourney, baseline: Baseline): String {
        journey.completed("library.edit")
        // library.form now opens the shared foreground picker and awaits its UI.
        // This owner-only fixture calls the same typed owner, preserving exact-session
        // admission; the separately required packaged companion proves the picker UI.
        val product = journey.application.foregroundProduct
        val generation = checkNotNull(product.state.value.graphGeneration)
        val session = checkNotNull(product.libraryEditSessionForSnapshot(generation))
        val created = runBlocking {
            product.beginCreatingFilter(null, null, generation, { _, _, cancel -> cancel() }, expectedSession = session)
        }
        assertTrue("The real library owner must admit the captured create session: $created", created is FilterLibraryEditOutcome.Created)
        val id = (created as FilterLibraryEditOutcome.Created).filterId
        assertTrue("The library owner must return the new filter id", id.isNotBlank())
        baseline.created += id
        journey.await("new filter detail draft") {
            journey.snapshot().optJSONObject("filterSession")?.optString("id") == id && isEditing(journey)
        }
        assertTrue("A created filter must not already be in the saved library", id !in savedFilterIds(journey))
        return id
    }

    private fun fillList(journey: ReactEmulatorJourney, baseline: Baseline, filterId: String, decision: String, target: Int) {
        val current = ruleSet(journey, filterId)
        val need = target - (if (decision == "blocked") current.blocked else current.allowed).size
        check(need >= 0) { "The dedicated $decision list already exceeds the free cap" }
        if (need == 0) return
        journey.completed("filter.open", JSONObject().put("id", filterId))
        if (!isEditing(journey)) journey.completed("filter.edit", JSONObject().put("id", filterId))
        repeat(need) { index ->
            val domain = "p2r-fill-$decision-$index-${UUID.randomUUID().toString().take(8)}.invalid"
            journey.completed(
                "filter.domain",
                JSONObject().put("id", filterId).put("domain", domain).put("decision", decision).put("remove", false),
            )
            baseline.added += AddedRule(filterId, decision, domain)
        }
        val review = reviewAfterSave(journey, filterId)
        applyReview(journey, filterId, review)
        journey.await("dedicated $decision list filled to the free cap", 90_000) {
            val rules = ruleSet(journey, filterId)
            (if (decision == "blocked") rules.blocked else rules.allowed).size == target
        }
    }

    private fun removeRules(journey: ReactEmulatorJourney, filterId: String, decision: String, domains: List<String>) {
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
        assertEquals("A changed draft stops at Review", "review", saveDraft(journey, filterId))
        return reviewDraft(journey, filterId)
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

    // ---- Baseline capture / restore -------------------------------------------------------------

    private fun captureBaseline(journey: ReactEmulatorJourney): Baseline {
        assertFalse("A prior journey left a library draft open", libraryEditingActive(journey))
        assertFalse("A prior journey left a filter draft open", isEditing(journey))
        assertTrue("A prior journey left a new-filter template open", journey.snapshot().isNull("newFilter"))
        assertNoP2rDomains(journey)
        assertFalse("A prior journey left Plus enabled", journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
        val ids = savedFilterIds(journey)
        return Baseline(activeFilter(journey).id, ids, ids.associateWith { ruleSet(journey, it) })
    }

    /** A cleanup rejection must not replace the operation that originally failed. */
    private inline fun withRestoredBaseline(
        journey: ReactEmulatorJourney,
        baseline: Baseline,
        body: () -> Unit,
    ) {
        var bodyFailure: Throwable? = null
        try {
            body()
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            try {
                restoreBaseline(journey, baseline)
            } catch (cleanupFailure: Throwable) {
                val original = bodyFailure
                if (original == null) throw cleanupFailure
                if (cleanupFailure !== original) original.addSuppressed(cleanupFailure)
            }
        }
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
            val cause = firstFailure?.let { " (cleanup failure: $it)" } ?: ""
            assertFalse("No library draft may survive$cause", libraryEditingActive(journey))
            assertFalse("No filter draft may survive$cause", isEditing(journey))
            assertTrue("No new-filter template may survive$cause", journey.snapshot().isNull("newFilter"))
            assertEquals("The baseline active filter must be restored", baseline.activeId, activeFilter(journey).id)
            assertEquals("The baseline filter set must be restored", baseline.filterIds, savedFilterIds(journey))
            baseline.filterIds.forEach { id ->
                assertEquals("Filter $id must be restored", baseline.rules.getValue(id), ruleSet(journey, id))
            }
            assertNoP2rDomains(journey)
            assertFalse("The controlled Plus entitlement must be restored",
                journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
        }
    }

    private fun cancelAnyDraft(journey: ReactEmulatorJourney) {
        if (!isEditing(journey)) return
        val id = journey.snapshot().getJSONObject("filterSession").getString("id")
        runCatching { journey.completed("filter.cancel", JSONObject().put("id", id)) }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline && isEditing(journey)) SystemClock.sleep(100)
    }

    private fun assertNoP2rDomains(journey: ReactEmulatorJourney) {
        val offenders = savedFilterIds(journey).flatMap { id ->
            val rules = ruleSet(journey, id)
            (rules.blocked + rules.allowed).filter { it.startsWith("p2r-") }.map { "$id:$it" }
        }
        assertTrue("No P2R fixture domain may survive a journey: $offenders", offenders.isEmpty())
    }

    // ---- Controlled entitlement adapter ---------------------------------------------------------

    private fun grantControlledPlus(journey: ReactEmulatorJourney): () -> Unit {
        val backend = journey.awaitPlusBackend()
        val entitlement = backend.entitlement
        val before = runBlocking { entitlement.initialize(); entitlement.state.value.hasPlus }
        assertFalse("This controlled pass must begin from the real Free installation", before)
        assertTrue("The controlled entitlement authority must accept the verified reading",
            runBlocking {
                entitlement.apply(VerifiedPlusReading(active = true, confidence = EntitlementReadingConfidence.CONFIRMED))
            })
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

    // ---- Snapshot helpers -----------------------------------------------------------------------

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
