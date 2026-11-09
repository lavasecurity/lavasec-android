package com.lavasec.app.platform

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** First-party native configuration edit. Raw credentials stay native and enter the reviewed
 * owner staging path; JavaScript receives only row identity and public configuration metadata.
 */
sealed interface VpnConfigurationPick {
    /** A document presentation produced configuration text. Not parsed or staged yet. */
    data class Read(val rawConfiguration: String) : VpnConfigurationPick {
        override fun toString(): String = "VpnConfigurationPick.Read(<redacted>)"
    }

    /** Native identity edit. Blank replacement retains this row's saved credentials. */
    data class Edited(val name: String, val rawConfiguration: String?) : VpnConfigurationPick {
        override fun toString(): String = "VpnConfigurationPick.Edited(<redacted>)"
    }

    /** Actual bounded document-read failure; no URI, provider metadata or input is retained. */
    enum class ImportFailureReason { TOO_LARGE, NOT_TEXT }
    data class ImportFailed(val reason: ImportFailureReason) : VpnConfigurationPick

    /** The user dismissed the presentation before choosing a configuration. */
    data object Cancelled : VpnConfigurationPick

    /** No presentation is registered for this build/launch (never a fabricated candidate). */
    data object Unavailable : VpnConfigurationPick
}

/**
 * The lifecycle-safe first-party presentation seam the coordinator drives. Production binds the
 * foreground activity's `ActivityResultRegistry`; tests and non-device builds supply a fake. A
 * source reports its own absence as [VpnConfigurationPick.Unavailable] rather than fabricating a
 * configuration, so the editor can answer with a typed refusal.
 */
fun interface VpnConfigurationPickSource {
    suspend fun pick(): VpnConfigurationPick
    suspend fun edit(request: VpnConfigurationEditorRequest): VpnConfigurationPick = pick()
    /** Exact native selection handoff finished, including refusal/cancellation. Default pickers own no retained sheet. */
    fun finishPick(pick: VpnConfigurationPick) = Unit
}

data class VpnConfigurationEditorRequest(val name: String, val exists: Boolean)

/** The outcome of `vpn.begin` when no rotation is committed yet (first-rotation import). */
sealed interface VpnFirstConfigurationBegin {
    /**
     * The ordered draft is open over the committed selection. [imported] is true when this draft
     * was created by a first-rotation import in THIS call, so the caller can undo the eagerly
     * committed rotation if the editor is later cancelled (pending-until-commit).
     */
    data class Draft(
        val draft: ChainedConfigurationDraft,
        val imported: Boolean = false,
    ) : VpnFirstConfigurationBegin

    /** The user dismissed the first-configuration presentation; nothing was committed. */
    data object Cancelled : VpnFirstConfigurationBegin

    /** No presentation is registered, or the rotation changed underneath the request. */
    data object Unavailable : VpnFirstConfigurationBegin

    /** An editor refusal with a stable wire code. */
    data class Refused(val refusal: ChainedConfigurationEditorRefusal) : VpnFirstConfigurationBegin

    /** The picked text was not a configuration this feature can stage. */
    data class Rejected(val refusal: com.lavasec.app.foundation.chained.ChainedConfRefusal) :
        VpnFirstConfigurationBegin
}

/** The outcome of `vpn.edit` with a picked replacement configuration. */
sealed interface VpnPickedConfigurationEdit {
    /** The draft's new display-safe state. */
    data class Draft(val draft: ChainedConfigurationDraft) : VpnPickedConfigurationEdit

    /** The user dismissed the replacement presentation; the draft is unchanged. */
    data object Cancelled : VpnPickedConfigurationEdit

    /** No replacement presentation is registered, or no draft is open. */
    data object Unavailable : VpnPickedConfigurationEdit

    /** An editor refusal with a stable wire code. */
    data class Refused(val refusal: ChainedConfigurationEditorRefusal) : VpnPickedConfigurationEdit

    /** The picked text was not a configuration this feature can stage. */
    data class Rejected(val refusal: com.lavasec.app.foundation.chained.ChainedConfRefusal) :
        VpnPickedConfigurationEdit
}

/**
 * The first-party entry into the ordered editor.
 *
 * [beginOrImport] keeps the normal path unchanged (an existing rotation opens a draft) and, only
 * when the owner reports [ChainedConfigurationEditorRefusal.NOT_CONFIGURED], imports a picked
 * configuration through the owner's reviewed import/save path to create the first rotation, then
 * opens the draft over it. [stagePickedReplacement] stages a picked replacement or name edit at its exact draft revision. Neither method reads or returns key material; the owner's opaque key lease owns it.
 */
class VpnFirstConfigurationCoordinator(
    private val owner: ChainedConfigurationCommandOwner,
    private val picks: VpnConfigurationPickSource,
    /**
     * Re-validated after any external presentation returns — the document picker pauses the
     * activity, whose `onPause` resets the App Settings authorization turn, and the credential graph
     * can rotate while the picker is open. It must confirm the owner is still live AND the grant is
     * still in effect before anything is imported/staged; otherwise the picked text is dropped.
     * Defaults to always-true for tests and non-device builds.
     */
    private val revalidate: suspend () -> Boolean = { true },
    /** Original Activity ownership, carried through suspended reads to the repository write. */
    private val isCurrent: (() -> Boolean)? = null,
) {
    suspend fun beginOrImport(id: String, expectedGeneration: Long): VpnFirstConfigurationBegin {
        if (isCurrent?.invoke() == false) return VpnFirstConfigurationBegin.Unavailable
        val opened = owner.beginEditor(id, expectedGeneration, isCurrent)
        if (isCurrent?.invoke() == false) return VpnFirstConfigurationBegin.Unavailable
        when (opened) {
            is ChainedConfigurationEditorResult.Draft ->
                return if (isCurrent?.invoke() == false) VpnFirstConfigurationBegin.Unavailable
                else VpnFirstConfigurationBegin.Draft(opened.draft)

            is ChainedConfigurationEditorResult.Refused ->
                if (opened.refusal != ChainedConfigurationEditorRefusal.NOT_CONFIGURED) {
                    return VpnFirstConfigurationBegin.Refused(opened.refusal)
                }

            is ChainedConfigurationEditorResult.Rejected ->
                return VpnFirstConfigurationBegin.Rejected(opened.refusal)
        }
        // The initial authoritative read may suspend while a successor Activity resumes.
        if (isCurrent?.invoke() == false) return VpnFirstConfigurationBegin.Unavailable
        // No committed rotation: create the first one from a first-party pick.
        val pick = picks.pick()
        return try {
            when (pick) {
                VpnConfigurationPick.Cancelled -> VpnFirstConfigurationBegin.Cancelled
                VpnConfigurationPick.Unavailable, is VpnConfigurationPick.ImportFailed -> VpnFirstConfigurationBegin.Unavailable
                is VpnConfigurationPick.Read ->
                    // The picker just returned; revalidate the owner/grant before importing.
                    if (!revalidate() || isCurrent?.invoke() == false) VpnFirstConfigurationBegin.Unavailable
                    else importFirst(id, expectedGeneration, pick.rawConfiguration)
                is VpnConfigurationPick.Edited ->
                    if (!revalidate() || isCurrent?.invoke() == false || pick.rawConfiguration == null) VpnFirstConfigurationBegin.Unavailable
                    else importFirst(id, expectedGeneration, pick.rawConfiguration, pick.name)
            }
        } finally {
            picks.finishPick(pick)
        }
    }

    suspend fun stagePickedReplacement(id: String, index: Int): VpnPickedConfigurationEdit {
        if (isCurrent?.invoke() == false) return VpnPickedConfigurationEdit.Unavailable
        val draft = owner.editorDraft(id) ?: return VpnPickedConfigurationEdit.Unavailable
        if (index !in 0..draft.rows.size || index >= 2) {
            return VpnPickedConfigurationEdit.Refused(
                ChainedConfigurationEditorRefusal.INVALID_ROW,
            )
        }
        val pick = picks.edit(VpnConfigurationEditorRequest(draft.rows.getOrNull(index)?.name.orEmpty(), index < draft.rows.size))
        return try {
            when (pick) {
                VpnConfigurationPick.Cancelled -> VpnPickedConfigurationEdit.Cancelled
                VpnConfigurationPick.Unavailable, is VpnConfigurationPick.ImportFailed -> VpnPickedConfigurationEdit.Unavailable
                is VpnConfigurationPick.Read -> when (
                    // The picker just returned; revalidate before staging into the (possibly retired)
                    // owner.
                    val staged = if (!revalidate() || isCurrent?.invoke() == false) null
                    else owner.stageEditorConfiguration(id, index, pick.rawConfiguration, expectedRevision = draft.revision)
                ) {
                    null -> VpnPickedConfigurationEdit.Unavailable
                    is ChainedConfigurationEditorResult.Draft ->
                        VpnPickedConfigurationEdit.Draft(staged.draft)

                    is ChainedConfigurationEditorResult.Refused ->
                        VpnPickedConfigurationEdit.Refused(staged.refusal)

                    is ChainedConfigurationEditorResult.Rejected ->
                        VpnPickedConfigurationEdit.Rejected(staged.refusal)
                }
                is VpnConfigurationPick.Edited -> when (val staged = if (!revalidate() || isCurrent?.invoke() == false) null
                    else if (pick.rawConfiguration == null) owner.stageEditorName(id, index, pick.name, draft.revision)
                    else owner.stageEditorConfiguration(id, index, pick.rawConfiguration, pick.name, draft.revision)) {
                    null -> VpnPickedConfigurationEdit.Unavailable
                    is ChainedConfigurationEditorResult.Draft -> VpnPickedConfigurationEdit.Draft(staged.draft)
                    is ChainedConfigurationEditorResult.Refused -> VpnPickedConfigurationEdit.Refused(staged.refusal)
                    is ChainedConfigurationEditorResult.Rejected -> VpnPickedConfigurationEdit.Rejected(staged.refusal)
                }
            }
        } finally {
            picks.finishPick(pick)
        }
    }

    private suspend fun importFirst(
        id: String,
        expectedGeneration: Long,
        raw: String,
        name: String? = null,
    ): VpnFirstConfigurationBegin =
        when (val review = owner.importReview(raw)) {
            is ChainedConfigurationReview.Rejected ->
                VpnFirstConfigurationBegin.Rejected(review.refusal)

            is ChainedConfigurationReview.Ready -> {
                // CAS on absence: commit the first rotation only while the owner still reports
                // NOT_CONFIGURED. A rotation committed (or the graph moved) while the picker was
                // open must not be overwritten by the picked text. The separate check below is an
                // early-out; the authoritative absence CAS runs inside the serialized repository
                // save, so a commit landing between this check and the save still refuses.
                val stillAbsent = (owner.beginEditor(id, expectedGeneration, isCurrent)
                    as? ChainedConfigurationEditorResult.Refused)
                    ?.refusal == ChainedConfigurationEditorRefusal.NOT_CONFIGURED
                if (!stillAbsent || isCurrent?.invoke() == false) return VpnFirstConfigurationBegin.Unavailable
                val saved = owner.save(review, ChainedUpstreamSaveExpectation.AbsentPendingImport(id), isCurrent)
                when (saved) {
                    is ChainedConfigurationSaveResult.Committed -> {
                        var editorOpened = false
                        try {
                            if (isCurrent?.invoke() == false) return VpnFirstConfigurationBegin.Unavailable
                            when (val reopened = owner.beginEditor(id, saved.identity.generation, isCurrent)) {
                            is ChainedConfigurationEditorResult.Draft ->
                                if (isCurrent?.invoke() == false) VpnFirstConfigurationBegin.Unavailable
                                else if (name == null) VpnFirstConfigurationBegin.Draft(reopened.draft, imported = true).also { editorOpened = true }
                                else when (val named = owner.stageEditorName(id, 0, name, reopened.draft.revision)) {
                                    is ChainedConfigurationEditorResult.Draft -> VpnFirstConfigurationBegin.Draft(named.draft, imported = true).also { editorOpened = true }
                                    is ChainedConfigurationEditorResult.Refused -> VpnFirstConfigurationBegin.Refused(named.refusal)
                                    is ChainedConfigurationEditorResult.Rejected -> VpnFirstConfigurationBegin.Rejected(named.refusal)
                                }

                            is ChainedConfigurationEditorResult.Refused ->
                                VpnFirstConfigurationBegin.Refused(reopened.refusal)

                            is ChainedConfigurationEditorResult.Rejected ->
                                VpnFirstConfigurationBegin.Rejected(reopened.refusal)
                            }
                        } finally {
                            // Cancellation can land after the atomic pending write but before the
                            // bridge captures a draft marker. Retain a failed cleanup's durable tag
                            // for retry; successful cleanup cannot remove a newer saved successor.
                            if (!editorOpened) withContext(NonCancellable) {
                                owner.cancelFirstImport(id, saved.identity)
                            }
                        }
                    }

                    is ChainedConfigurationSaveResult.NotReviewable ->
                        VpnFirstConfigurationBegin.Rejected(saved.refusal)

                    ChainedConfigurationSaveResult.AlreadyConsumed,
                    ChainedConfigurationSaveResult.StaleGeneration,
                    ChainedConfigurationSaveResult.AuthoritativeReadFailed,
                    is ChainedConfigurationSaveResult.Rejected,
                    is ChainedConfigurationSaveResult.Failed,
                    -> VpnFirstConfigurationBegin.Unavailable
                }
            }
        }
}
