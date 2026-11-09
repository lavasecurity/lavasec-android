package com.lavasec.app.reactnative

import com.ibm.icu.text.BreakIterator
import com.lavasec.app.diagnostics.BugReportReview
import com.lavasec.app.diagnostics.BugReportSendResult
import com.lavasec.app.diagnostics.NativeBugReportOwner
import com.lavasec.app.foundation.feedback.FeedbackDraft
import com.lavasec.app.foundation.feedback.FeedbackTopic
import com.lavasec.app.foundation.filter.FilterIdentityPolicy
import com.lavasec.app.foundation.filter.FilterLibraryEditSession
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject

internal enum class NativeSharedForegroundKind(val wire: String) {
    CREATE_FILTER("createFilter"), RENAME_FILTER("renameFilter"), DELETE_FILTERS("deleteFilters"),
    AUTOMATION("automation"), LICENSES("licenses"), FEEDBACK("feedback"),
}

internal data class NativeSharedForegroundTemplate(val id: String, val name: String)

/** The bridge captures the real library/editor baseline and supplies its guarded write callbacks. */
internal data class NativeSharedForegroundSpec(
    val kind: NativeSharedForegroundKind,
    val name: String = "",
    val emoji: String = "🌿",
    val templates: List<NativeSharedForegroundTemplate> = emptyList(),
    val canCreate: Boolean = false,
    val reviewedLibrarySession: FilterLibraryEditSession? = null,
    val notices: String = "",
    val isCurrent: () -> Boolean = { true },
    val isNameAvailable: (String) -> Boolean = { true },
    val create: (suspend (String?) -> String?)? = null,
    val rename: (suspend (String, String) -> Boolean)? = null,
    val delete: (suspend () -> Boolean)? = null,
)

internal data class NativeSharedForegroundResult(
    val status: String = "completed",
    val reason: String? = null,
    val value: Any? = null,
) {
    fun toJson(): String = JSONObject().put("status", status)
        .put("reason", reason ?: JSONObject.NULL).put("value", value ?: JSONObject.NULL).toString()
}

/**
 * Navigation owns one native visit. RN supplies every page body, including review and feedback;
 * this owner supplies identity, generation fencing, frozen native capabilities and completion.
 * The suspended begin is separate from control commands, so it never holds the mutation lane.
 */
internal class NativeSharedForegroundOwner(
    private val currentGeneration: () -> Long,
    private val canRead: (NativeSharedForegroundKind) -> Boolean,
    private val publish: () -> Unit,
    private val currentHost: () -> NativeVisitHost?,
    private val feedbackOwner: () -> NativeBugReportOwner? = { null },
    private val copyReceipt: (String) -> Boolean = { false },
    private val sampleFeedback: suspend () -> Unit = {},
    private val authorizationRevision: () -> Long = { 0L },
    private val canInteract: (NativeSharedForegroundKind) -> Boolean = canRead,
) : AutoCloseable {
    private class Visit(
        val spec: NativeSharedForegroundSpec,
        val generation: Long,
        val host: NativeVisitHost,
    ) {
        val id = UUID.randomUUID().toString()
        val completion = CompletableDeferred<NativeSharedForegroundResult>()
        var dismissAttempt = 0
        var dirty = false
        var busy = false
        val feedback = if (spec.kind == NativeSharedForegroundKind.FEEDBACK) FeedbackVisit() else null
    }
    private class FeedbackVisit {
        var draft = FeedbackDraft()
        var diagnostics = false
        var step = 0
        var furthest = 0
        var revision = 0L
        var reviewRevision: Long? = null
        var phase = FeedbackPhase.EDITING
        var presentationEdited = false
        var owner: NativeBugReportOwner? = null
        var prepared: BugReportReview? = null
        var error = ""
        var receipt = ""
        var copied = false
        val dirty: Boolean get() = phase != FeedbackPhase.SENT && (presentationEdited || draft.topic != null ||
            draft.affectedSite.isNotBlank() || draft.details.isNotBlank() || !draft.contactEmail.isNullOrBlank() || diagnostics)
        fun edited() { ++revision; reviewRevision = null; error = ""; copied = false }
    }
    private enum class FeedbackPhase { EDITING, PREPARING, SENDING, SENT }
    private val lock = Any()
    private var visit: Visit? = null
    private var closed = false

    // Public ownership metadata reserves the shared foreground slot even while private reads
    // are covered, the React context is absent, or an owned write replaces its own baseline.
    val currentKind: NativeSharedForegroundKind? get() = synchronized(lock) { retainedVisitLocked()?.spec?.kind }
    val currentId: String? get() = synchronized(lock) { retainedVisitLocked()?.id }

    /** A successor cannot inherit a form; ordinary pause/grant loss retains its original visit. */
    fun authorityChanged() {
        val retired = synchronized(lock) { retireObsoleteLocked() }
        if (retired) publish()
    }

    /** Exact destruction retires only the original Activity/window, even after a successor opens. */
    fun retireHost(owner: Any, nativeHostID: String) {
        val retired = synchronized(lock) {
            val owned = visit ?: return@synchronized false
            if (owned.host.owner !== owner || owned.host.nativeHostID != nativeHostID) false
            else { retireLocked(owned); true }
        }
        if (retired) publish()
    }

    suspend fun begin(spec: NativeSharedForegroundSpec): NativeSharedForegroundResult {
        var retired = false
        var busy = false
        val owned = synchronized(lock) {
            retired = retireObsoleteLocked()
            busy = closed || visit != null
            val host = currentHost()
            val generation = currentGeneration()
            if (busy || host == null || !host.matches(currentHost()) || !spec.isCurrent() || !canRead(spec.kind)) null
            else if (generation != currentGeneration() || !host.matches(currentHost())) null
            else Visit(spec, generation, host).also { visit = it }
        }
        if (owned == null) {
            if (retired) publish()
            return rejected(if (busy) "foreground-busy" else "foreground-unavailable")
        }
        publish()
        return try {
            val result = owned.completion.await()
            // Cancellation carries no fields. A completed form result still belongs to its
            // original live host, including the interval before the suspended caller resumes.
            if (result.status != "cancelled" &&
                (owned.generation != currentGeneration() || !owned.host.matches(currentHost()))) rejected("foreground-stale")
            else result
        } catch (cancelled: CancellationException) { retire(owned); throw cancelled }
    }

    /** Revocation hides fields but retains the route and draft until its owner explicitly retires. */
    fun projection(): JSONObject? = synchronized(lock) {
        val owned = visit ?: return null
        if (!isCurrent(owned) || !canRead(owned.spec.kind)) return null
        JSONObject().put("id", owned.id).put("kind", owned.spec.kind.wire)
            .put("name", owned.spec.name).put("emoji", owned.spec.emoji)
            .put("dismissAttempt", owned.dismissAttempt).put("canCreate", owned.spec.canCreate)
            .put("templates", JSONArray(owned.spec.templates.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .also { result ->
                owned.spec.reviewedLibrarySession?.let { session ->
                    result.put("groups", libraryGroups(session)).put("hasDeletions", session.stagedDeletions.isNotEmpty())
                }
                if (owned.spec.kind == NativeSharedForegroundKind.LICENSES) result.put("notices", owned.spec.notices)
                owned.feedback?.let { result.put("feedback", feedbackProjection(it)) }
            }
    }

    /** A native back attempt asks the shared page to show its existing discard confirmation. */
    fun requestDismiss(): Boolean {
        synchronized(lock) {
            val owned = visit ?: return false
            if (!isCurrent(owned) || owned.busy || owned.feedback?.phase in setOf(FeedbackPhase.PREPARING, FeedbackPhase.SENDING)) return false
            ++owned.dismissAttempt
        }
        publish()
        return true
    }

    suspend fun execute(input: JSONObject): NativeSharedForegroundResult {
        val type = input.optString("type")
        val owned = synchronized(lock) {
            val current = visit ?: return rejected("foreground-closed")
            if (input.optString("id") != current.id || !current.host.matches(currentHost()) ||
                type != "foreground.dismiss" && !isCurrent(current)) return rejected("foreground-stale")
            current
        }
        if (type == "foreground.dismiss") {
            synchronized(lock) {
                if (visit !== owned || !owned.host.matches(currentHost())) return rejected("foreground-stale")
                if (owned.busy || owned.feedback?.phase in setOf(FeedbackPhase.PREPARING, FeedbackPhase.SENDING)) {
                    return NativeSharedForegroundResult(value = false)
                }
                // Read-free exact-ID teardown may retire an obsolete editor or graph, but never
                // a form adopted by another Activity. The lifecycle callback handles dead hosts.
                retireLocked(owned)
            }
            publish()
            return NativeSharedForegroundResult()
        }
        if (!canRead(owned.spec.kind)) return rejected("foreground-locked")
        if (type == "foreground.enter") return NativeSharedForegroundResult()
        // A fresh native grant permits metadata/preparation beneath the cover. Actual user
        // mutations and private preview/clipboard reads additionally require this exact visit's
        // native presentation layout acknowledgment. Local exact-ID discard remains read-free.
        if (type in setOf("foreground.dirty", "foreground.submit", "feedback.topic", "feedback.change",
                "feedback.diagnostics", "feedback.step", "feedback.submit", "feedback.copy", "feedback.preview") &&
            !canInteract(owned.spec.kind)) return rejected("foreground-not-ready")
        if (type == "foreground.dirty") {
            if (owned.spec.kind == NativeSharedForegroundKind.FEEDBACK) {
                val feedback = checkNotNull(owned.feedback)
                if (!input.optBoolean("dirty") || feedback.phase != FeedbackPhase.EDITING) return rejected("feedback-busy")
                feedback.presentationEdited = true
            } else {
                if (owned.spec.kind != NativeSharedForegroundKind.RENAME_FILTER) return rejected("foreground-invalid-command")
                owned.dirty = input.optBoolean("dirty")
            }
            publish(); return NativeSharedForegroundResult()
        }
        if (type.startsWith("feedback.")) return feedbackCommand(owned, type, input)
        if (owned.busy) return rejected("foreground-busy")
        if (type == "foreground.identity") {
            if (owned.spec.kind != NativeSharedForegroundKind.RENAME_FILTER) return rejected("foreground-invalid-command")
            return NativeSharedForegroundResult(value = identity(owned.spec, input.optString("name"), input.optString("emoji")))
        }
        if (type != "foreground.submit") return rejected("foreground-invalid-command")
        synchronized(lock) { if (!isCurrent(owned)) return rejected("foreground-stale"); owned.busy = true }
        publish()
        try {
            val result = when (owned.spec.kind) {
                NativeSharedForegroundKind.CREATE_FILTER -> {
                    if (!owned.spec.canCreate) return rejected("foreground-save-refused")
                    val template = input.optString("template").takeIf(String::isNotEmpty)
                    if (template != null && owned.spec.templates.none { it.id == template }) return rejected("foreground-stale")
                    val id = owned.spec.create?.invoke(template)
                    if (!synchronized(lock) { ownsCurrentHost(owned) }) return rejected("foreground-stale")
                    if (id == null) return rejected("foreground-save-refused")
                    NativeSharedForegroundResult(value = id)
                }
                NativeSharedForegroundKind.RENAME_FILTER -> {
                    val name = input.optString("name"); val emoji = input.optString("emoji")
                    if (!identity(owned.spec, name, emoji).getBoolean("valid")) return rejected("foreground-identity-invalid")
                    val resolvedName = FilterIdentityPolicy.nameForEdit(name, owned.spec.name) ?: return rejected("foreground-identity-invalid")
                    val renamed = owned.spec.rename?.invoke(resolvedName, emoji)
                    if (!synchronized(lock) { ownsCurrentHost(owned) }) return rejected("foreground-stale")
                    if (renamed != true) return rejected("foreground-save-refused")
                    NativeSharedForegroundResult(value = true)
                }
                NativeSharedForegroundKind.DELETE_FILTERS -> {
                    val deleted = owned.spec.delete?.invoke()
                    if (!synchronized(lock) { ownsCurrentHost(owned) }) return rejected("foreground-stale")
                    if (deleted != true) return rejected("foreground-save-refused")
                    NativeSharedForegroundResult(value = true)
                }
                else -> return rejected("foreground-invalid-command")
            }
            // The write callback owns the commit-edge CAS. Its own successful edit may replace
            // the captured baseline, so completion checks visit + graph + original live host.
            if (!finish(owned, result)) return rejected("foreground-stale")
            return NativeSharedForegroundResult(value = true)
        } finally { owned.busy = false; publish() }
    }

    private suspend fun feedbackCommand(owned: Visit, type: String, input: JSONObject): NativeSharedForegroundResult {
        val feedback = owned.feedback ?: return rejected("foreground-invalid-command")
        if (type == "feedback.copy") {
            if (feedback.phase != FeedbackPhase.SENT || feedback.receipt.isEmpty()) return NativeSharedForegroundResult(value = false)
            feedback.copied = copyReceipt(feedback.receipt); publish()
            return NativeSharedForegroundResult(value = feedback.copied)
        }
        if (type == "feedback.preview") return NativeSharedForegroundResult(value = feedbackPreview(feedback))
        if (type == "feedback.enter") {
            if (feedback.phase != FeedbackPhase.EDITING) return NativeSharedForegroundResult()
            val authorization = authorizationRevision(); val revision = feedback.revision
            sampleFeedback()
            if (!isCurrent(owned) || !canRead(owned.spec.kind) || authorization != authorizationRevision() ||
                revision != feedback.revision || feedback.phase != FeedbackPhase.EDITING) return rejected("foreground-stale")
            prepare(feedback); feedback.error = ""; publish(); return NativeSharedForegroundResult()
        }
        if (type == "feedback.submit") return submitFeedback(owned, feedback, input)
        if (feedback.phase != FeedbackPhase.EDITING) return rejected("feedback-busy")
        when (type) {
            "feedback.topic" -> {
                val topic = topicForWire(input.optString("topic")) ?: return rejected("feedback-topic-required")
                feedback.draft = feedback.draft.copy(topic = topic, affectedSite = if (topic == FeedbackTopic.WEBSITE_ACCESS) feedback.draft.affectedSite else "")
                feedback.edited()
            }
            "feedback.change" -> {
                val field = input.optString("field"); val value = input.optString("value")
                val previous = feedback.draft
                feedback.draft = when (field) {
                    "site" -> previous.copy(affectedSite = prefixGraphemes(value, FeedbackDraft.MAX_AFFECTED_SITE_GRAPHEMES))
                    "details" -> previous.copy(details = prefixGraphemes(value, FeedbackDraft.MAX_DETAILS_GRAPHEMES))
                    "email" -> previous.copy(contactEmail = prefixGraphemes(value, FeedbackDraft.MAX_CONTACT_EMAIL_GRAPHEMES))
                    else -> return rejected("foreground-invalid-command")
                }
                if (previous.normalizedAffectedSite != feedback.draft.normalizedAffectedSite ||
                    previous.normalizedDetails != feedback.draft.normalizedDetails ||
                    previous.normalizedContactEmail != feedback.draft.normalizedContactEmail) feedback.edited()
            }
            "feedback.diagnostics" -> { feedback.diagnostics = input.optBoolean("value"); feedback.edited() }
            "feedback.step" -> {
                val destination = if (input.optBoolean("next")) {
                    if (feedback.step >= 2 || if (feedback.step == 0) feedback.draft.topic == null else !feedback.draft.isReadyForReview) return rejected("feedback-step-incomplete")
                    feedback.step + 1
                } else input.optInt("step", -1).also { if (it !in 0..feedback.furthest) return rejected("feedback-step-incomplete") }
                feedback.step = destination; feedback.furthest = maxOf(destination, feedback.furthest)
                if (destination == 2) feedback.reviewRevision = feedback.revision
            }
            else -> return rejected("foreground-invalid-command")
        }
        prepare(feedback); publish()
        return NativeSharedForegroundResult(value = feedbackProjection(feedback))
    }

    private suspend fun submitFeedback(owned: Visit, feedback: FeedbackVisit, input: JSONObject): NativeSharedForegroundResult {
        val reviewRevision = input.optString("review").toLongOrNull()
        if (feedback.phase != FeedbackPhase.EDITING || feedback.step != 2 || !feedback.draft.isReadyForReview ||
            reviewRevision == null || feedback.reviewRevision != reviewRevision || feedback.revision != reviewRevision) {
            return rejected("feedback-review-required")
        }
        val owner = feedback.owner ?: feedbackOwner() ?: return rejected("feedback-unavailable")
        val reviewed = feedback.prepared ?: owner.prepare(feedback.draft, feedback.diagnostics) ?: return rejected("feedback-unavailable")
        feedback.owner = owner; feedback.prepared = reviewed
        feedback.phase = FeedbackPhase.PREPARING; feedback.error = ""; feedback.copied = false; publish()
        val authorization = authorizationRevision()
        try {
            sampleFeedback()
            if (!isCurrent(owned) || !canRead(owned.spec.kind) || !canInteract(owned.spec.kind) ||
                authorization != authorizationRevision()) return rejected("foreground-stale")
            if (!owner.grantConsent()) return rejected("feedback-unavailable")
            feedback.phase = FeedbackPhase.SENDING; publish()
            val outcome = owner.submit(reviewed)
            val sent = outcome == BugReportSendResult.SENT
            // A real matching native receipt is the only authority to paint the sent page.
            feedback.phase = if (sent) FeedbackPhase.SENT else FeedbackPhase.EDITING
            if (sent) feedback.receipt = reviewed.reportID
            else feedback.error = if (outcome == BugReportSendResult.RATE_LIMITED) "Too many reports. Try again later." else "Could not send feedback. Try again."
            if (isCurrent(owned)) publish()
            return if (isCurrent(owned) && canRead(owned.spec.kind)) NativeSharedForegroundResult(value = sent)
            else rejected("foreground-stale")
        } catch (cancelled: CancellationException) {
            feedback.phase = FeedbackPhase.EDITING; throw cancelled
        } finally {
            if (feedback.phase == FeedbackPhase.PREPARING) feedback.phase = FeedbackPhase.EDITING
            if (isCurrent(owned)) publish()
        }
    }

    private fun prepare(feedback: FeedbackVisit) {
        val owner = feedback.owner ?: feedbackOwner()
        feedback.owner = owner
        feedback.prepared = owner?.prepare(feedback.draft, feedback.diagnostics)
    }

    private fun feedbackProjection(feedback: FeedbackVisit): JSONObject = JSONObject()
        .put("topic", wireForTopic(feedback.draft.topic)).put("site", feedback.draft.affectedSite)
        .put("details", feedback.draft.details).put("email", feedback.draft.contactEmail ?: "")
        .put("diagnostics", feedback.diagnostics).put("step", feedback.step).put("furthest", feedback.furthest)
        .put("revision", feedback.revision.toString()).put("review", feedback.reviewRevision?.toString() ?: "")
        .put("normalizedSite", feedback.draft.normalizedAffectedSite).put("normalizedDetails", feedback.draft.normalizedDetails)
        .put("normalizedEmail", feedback.draft.normalizedContactEmail ?: "").put("count", graphemeCount(feedback.draft.details))
        .put("canContinue", feedback.draft.isReadyForReview).put("dirty", feedback.dirty)
        .put("busy", feedback.phase in setOf(FeedbackPhase.PREPARING, FeedbackPhase.SENDING))
        .put("prepared", feedback.prepared != null).put("sent", feedback.phase == FeedbackPhase.SENT)
        .put("error", feedback.error).put("receipt", feedback.receipt).put("copied", feedback.copied)
        .put("topics", JSONArray(FeedbackTopic.entries.map { JSONObject().put("id", wireForTopic(it)).put("title", it.title) }))

    /** Preview only fields that occur in the frozen reviewed request; never sample new diagnostics. */
    private fun feedbackPreview(feedback: FeedbackVisit): JSONArray {
        val review = feedback.prepared ?: return JSONArray()
        val body = JSONObject(review.requestBytes().toString(Charsets.UTF_8))
        return JSONArray(buildList<JSONObject> {
            for ((key, title) in listOf("app" to "App", "device" to "Device", "vpn" to "VPN", "filters" to "Filters")) {
                val values = body.optJSONObject(key) ?: continue
                add(JSONObject().put("id", key).put("title", title).put("purpose", "Sent with your report.")
                    .put("items", JSONArray(values.keys().asSequence().sorted().map { field ->
                        JSONObject().put("id", field).put("label", field.replace('_', ' ')).put("value", values.get(field).toString())
                    }.toList())))
            }
        })
    }

    private fun identity(spec: NativeSharedForegroundSpec, name: String, emoji: String): JSONObject {
        val resolved = FilterIdentityPolicy.nameForEdit(name, spec.name)
        val available = spec.isNameAvailable(FilterIdentityPolicy.normalizedName(name))
        val validEmoji = FilterIdentityPolicy.isValidEmoji(emoji)
        val message = when {
            resolved == null && name.isNotEmpty() -> "Use letters, numbers and spaces."
            !validEmoji && emoji.isNotEmpty() -> "Choose one emoji."
            !available && name.isNotEmpty() -> "You already have a filter with that name."
            else -> ""
        }
        return JSONObject().put("valid", resolved != null && validEmoji && available)
            .put("dirty", !FilterIdentityPolicy.canonicallyEqual(name, spec.name) || emoji != spec.emoji).put("message", message)
    }

    private fun libraryGroups(session: FilterLibraryEditSession): JSONArray = JSONArray(buildList<JSONObject> {
        fun group(title: String, added: List<String>, removed: List<String>) {
            if (added.isNotEmpty() || removed.isNotEmpty()) add(JSONObject().put("title", title).put("added", JSONArray(added)).put("removed", JSONArray(removed)))
        }
        group("Added filters", session.additions.map { it.name }, emptyList())
        for (id in session.renames.keys.filter { it !in session.stagedDeletions }.sorted()) {
            group("Renamed filter", listOf(session.renames.getValue(id)), listOf(session.baseline?.profile(id)?.name ?: ""))
        }
        for (id in session.emojiChanges.keys.filter { it !in session.stagedDeletions }.sorted()) {
            group("Emoji", listOf(session.emojiChanges.getValue(id)), listOf(session.baseline?.profile(id)?.emoji ?: ""))
        }
        group("Deleted filters", emptyList(), session.baseline?.profiles.orEmpty().filter { it.id in session.stagedDeletions }.map { it.name })
    })

    private fun ownsCurrentHost(owned: Visit): Boolean = !closed && visit === owned &&
        owned.generation == currentGeneration() && owned.host.matches(currentHost())

    private fun isCurrent(owned: Visit): Boolean = synchronized(lock) {
        ownsCurrentHost(owned) && owned.spec.isCurrent()
    }

    private fun retainedVisitLocked(): Visit? {
        retireObsoleteLocked()
        return visit
    }

    /** Called under [lock]; null context retains an alive original host but admits no controls. */
    private fun retireObsoleteLocked(): Boolean {
        val owned = visit ?: return false
        val publishing = owned.busy || owned.feedback?.phase in setOf(FeedbackPhase.PREPARING, FeedbackPhase.SENDING)
        // A successful owned write can replace its own baseline while its callback is returning.
        // Host/graph loss always retires; baseline invalidation retires when no write owns it.
        if (owned.generation == currentGeneration() && owned.host.retains(currentHost()) &&
            (publishing || owned.spec.isCurrent())) return false
        retireLocked(owned)
        return true
    }
    private fun retire(owned: Visit) {
        synchronized(lock) {
            if (visit !== owned) return
            retireLocked(owned)
        }
        publish()
    }
    private fun retireLocked(owned: Visit) {
        if (visit !== owned) return
        // A submitting POST belongs to its native report owner. Forced retirement never
        // discards that attempt or makes a replacement form eligible to submit it again.
        if (owned.feedback?.phase !in setOf(FeedbackPhase.PREPARING, FeedbackPhase.SENDING)) owned.feedback?.owner?.discard()
        visit = null; owned.completion.complete(NativeSharedForegroundResult("cancelled"))
    }
    private fun finish(owned: Visit, result: NativeSharedForegroundResult): Boolean {
        val finished = synchronized(lock) {
            if (!ownsCurrentHost(owned)) false
            else { visit = null; owned.completion.complete(result); true }
        }
        if (finished) publish()
        return finished
    }
    override fun close() {
        synchronized(lock) { if (closed) return; visit?.let(::retireLocked); closed = true }
        publish()
    }
    private fun rejected(reason: String) = NativeSharedForegroundResult("rejected", reason)
    private fun topicForWire(value: String): FeedbackTopic? = FeedbackTopic.entries.firstOrNull { wireForTopic(it) == value }
    private fun wireForTopic(topic: FeedbackTopic?): String = when (topic) {
        FeedbackTopic.WEBSITE_ACCESS -> "websiteAccess"
        FeedbackTopic.VPN_OR_FILTER_ISSUE -> "vpnOrFilterIssue"
        FeedbackTopic.FEATURE_ISSUE -> "featureIssue"
        FeedbackTopic.TRANSLATION_ISSUE -> "translationIssue"
        FeedbackTopic.SUGGESTION -> "suggestion"
        FeedbackTopic.OTHER -> "other"
        null -> ""
    }
    private fun prefixGraphemes(value: String, maximum: Int): String {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT); iterator.setText(value)
        var boundary = iterator.first()
        repeat(maximum) { val next = iterator.next(); if (next == BreakIterator.DONE) return value; boundary = next }
        return value.substring(0, boundary)
    }
    private fun graphemeCount(value: String): Int {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT); iterator.setText(value)
        var count = 0; while (iterator.next() != BreakIterator.DONE) ++count
        return count
    }
}
