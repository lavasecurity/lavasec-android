package com.lavasec.app.foundation.resolver

/**
 * Editor session for the custom resolver draft, the Android mirror of the iOS
 * `DNSResolverSettingsView` custom-resolver state (pinned c4658d9).
 *
 * The session keeps the last committed [saved] entry and the in-progress [draft].
 * Dirty state is derived, never stored: the draft has changes exactly when its
 * trimmed content differs from the saved entry. [clearEditor] is the editor's
 * "Clear" button (it empties the fields, it does not commit); [dismiss] discards
 * unsaved changes back to the saved entry; [save] is the canonical commit and
 * validates through the existing [CustomResolverValidator].
 *
 * Nothing here talks to the runtime or the durable store. The editor transport is
 * implied by the parsed custom value; the policy layer owns the runtime capability
 * gate.
 */
data class CustomResolverDraftSession(
    val saved: CustomResolverDraft,
    val draft: CustomResolverDraft,
) {
    /** True when the draft differs from the saved entry in any trimmed field. */
    val isDirty: Boolean get() = draft.hasChangesAgainst(saved)

    /** Canonical `customResolverHasUnsavedDraft`: a dismissal must confirm. */
    val hasUnsavedDraft: Boolean get() = isDirty

    /** True only when every field is empty after trimming (the iOS "clear" shape). */
    val isCleared: Boolean get() = draft.isCleared

    /** The live validation of the draft; the cleared shape reports the empty primary. */
    val validation: CustomResolverValidation get() = CustomResolverValidator.validate(draft)

    /**
     * Canonical `canSaveCustomResolver`: a change is required, and either the primary
     * is non-empty or the draft is the cleared shape (a clear is a valid save).
     */
    val canSave: Boolean get() = isDirty && (draft.trimmedPrimary.isNotEmpty() || draft.isCleared)

    /** Canonical `canClearCustomResolver`: any raw field is non-empty. */
    val canClear: Boolean
        get() = draft.name.isNotEmpty() || draft.primaryAddress.isNotEmpty() || draft.secondaryAddress.isNotEmpty()

    fun edit(next: CustomResolverDraft): CustomResolverDraftSession = copy(draft = next)

    fun editName(value: String): CustomResolverDraftSession = edit(draft.copy(name = value))

    fun editPrimary(value: String): CustomResolverDraftSession = edit(draft.copy(primaryAddress = value))

    fun editSecondary(value: String): CustomResolverDraftSession = edit(draft.copy(secondaryAddress = value))

    /** The editor "Clear" button: empties every field without committing. */
    fun clearEditor(): CustomResolverDraftSession = edit(CustomResolverDraft())

    /** Discards unsaved changes, reverting the draft to the saved entry. */
    fun dismiss(): CustomResolverDraftSession = edit(saved)

    /**
     * Commits the draft. The cleared shape commits a cleared saved entry; otherwise
     * the draft is validated and, when valid, normalized (name trimmed to null,
     * addresses trimmed) before it becomes the saved entry.
     */
    fun save(): CustomResolverSaveResult {
        if (!isDirty) return CustomResolverSaveResult.NoChanges
        if (draft.isCleared) {
            val committed = CustomResolverDraft()
            return CustomResolverSaveResult.Cleared(copy(saved = committed, draft = committed))
        }
        return when (val validation = CustomResolverValidator.validate(draft)) {
            is CustomResolverValidation.Invalid -> CustomResolverSaveResult.Rejected(validation.reason)
            CustomResolverValidation.Valid -> {
                val normalized = CustomResolverDraft(
                    name = draft.normalizedName ?: "",
                    primaryAddress = draft.trimmedPrimary,
                    secondaryAddress = draft.trimmedSecondary,
                )
                CustomResolverSaveResult.Saved(copy(saved = normalized, draft = normalized))
            }
        }
    }

    companion object {
        /** A session with no saved entry and an empty draft. */
        val empty: CustomResolverDraftSession = CustomResolverDraftSession(CustomResolverDraft(), CustomResolverDraft())

        /** A session seeded from a committed entry (draft starts equal to it). */
        fun from(saved: CustomResolverDraft): CustomResolverDraftSession =
            CustomResolverDraftSession(saved, saved)
    }
}

sealed interface CustomResolverSaveResult {
    /** The draft was unchanged; nothing to commit. */
    data object NoChanges : CustomResolverSaveResult

    /** A valid, non-empty draft became the saved entry. */
    data class Saved(val session: CustomResolverDraftSession) : CustomResolverSaveResult

    /** The cleared shape committed an empty saved entry. */
    data class Cleared(val session: CustomResolverDraftSession) : CustomResolverSaveResult

    /** The draft failed validation and was not committed. */
    data class Rejected(val failure: CustomResolverValidationFailure) : CustomResolverSaveResult
}
