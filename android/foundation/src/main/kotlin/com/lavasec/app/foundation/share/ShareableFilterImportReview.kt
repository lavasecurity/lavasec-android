package com.lavasec.app.foundation.share

import java.security.MessageDigest

/** Why an import confirmation was refused. */
sealed interface ShareableFilterImportRefusal {
    /** The reviewed plan reconciled to nothing this device can import. */
    data object NothingToImport : ShareableFilterImportRefusal

    /** The reviewed target changed since the review; re-review the current filter. */
    data object StaleReview : ShareableFilterImportRefusal

    /** Replacing the filter currently in effect needs explicit consent to THAT. */
    data object ActiveReplacementNotConfirmed : ShareableFilterImportRefusal

    /** The applied subset would itself be trimmed on a second pass — never apply a partial set. */
    data object PlanNotFullyAccepted : ShareableFilterImportRefusal
}

/**
 * The outcome of confirming a reviewed import. Applying is a pure hand-back of content; the caller
 * owns persistence. An import NEVER mutates the saved library directly — it produces a review that
 * must be confirmed, and confirmation returns the content to write.
 */
sealed interface ShareableFilterImportConfirmation {
    data class ReplaceContents(
        val applied: ShareableFilterConfiguration,
        val replacement: FilterReplacementSummary,
    ) : ShareableFilterImportConfirmation

    data class AddNewFilter(
        val applied: ShareableFilterConfiguration,
    ) : ShareableFilterImportConfirmation

    data class Refused(
        val reason: ShareableFilterImportRefusal,
    ) : ShareableFilterImportConfirmation
}

/**
 * A bounded reviewed import preflight, ported from the canonical iOS import review/apply boundary
 * (pinned `c4658d9`).
 *
 * The review is a pure function of the decoded (untrusted) payload, the current target content,
 * and the device capabilities. It records the reconciled [plan] (applied subset + every dropped
 * entry) and the [replacement] diff (additions/removals/invalid entries carried as dropped rows),
 * and commits to both with a [reviewToken]. Confirmation revalidates that token against the
 * current target, so a target that changed after review cannot be silently overwritten.
 *
 * "No partial import" is enforced at apply time: if the already-planned [plan] would be trimmed
 * again by a second reconciliation, confirmation refuses rather than applying a subset the user
 * never reviewed.
 */
data class ShareableFilterImportReview(
    val shared: ShareableFilterConfiguration,
    val target: ShareableFilterConfiguration,
    val capabilities: ShareableFilterImportCapabilities,
    val plan: ShareableFilterImportPlan,
    val replacement: FilterReplacementSummary,
    val reviewToken: String,
) {
    /** Whether the planned subset survives a second reconciliation unchanged. */
    val isFullyAccepted: Boolean
        get() = plan.applied.importPlan(capabilities).applied == plan.applied

    /** Whether there is a non-empty, fully-reconciled plan to confirm. */
    val canConfirm: Boolean
        get() = !plan.applied.isEmpty && isFullyAccepted

    fun confirmReplace(
        currentTarget: ShareableFilterConfiguration,
        targetIsActive: Boolean,
        confirmedActiveReplacement: Boolean,
    ): ShareableFilterImportConfirmation {
        if (plan.applied.isEmpty) {
            return ShareableFilterImportConfirmation.Refused(ShareableFilterImportRefusal.NothingToImport)
        }
        if (!isFullyAccepted) {
            return ShareableFilterImportConfirmation.Refused(
                ShareableFilterImportRefusal.PlanNotFullyAccepted,
            )
        }
        if (commitment(currentTarget, plan.applied) != reviewToken) {
            return ShareableFilterImportConfirmation.Refused(ShareableFilterImportRefusal.StaleReview)
        }
        if (targetIsActive && !confirmedActiveReplacement) {
            return ShareableFilterImportConfirmation.Refused(
                ShareableFilterImportRefusal.ActiveReplacementNotConfirmed,
            )
        }
        return ShareableFilterImportConfirmation.ReplaceContents(
            applied = plan.applied,
            replacement = plan.applied.replacementSummary(currentTarget),
        )
    }

    fun confirmAddAsNewFilter(): ShareableFilterImportConfirmation {
        if (plan.applied.isEmpty) {
            return ShareableFilterImportConfirmation.Refused(ShareableFilterImportRefusal.NothingToImport)
        }
        if (!isFullyAccepted) {
            return ShareableFilterImportConfirmation.Refused(
                ShareableFilterImportRefusal.PlanNotFullyAccepted,
            )
        }
        return ShareableFilterImportConfirmation.AddNewFilter(plan.applied)
    }

    companion object {
        /** Builds the review for [shared] against the target the import would replace. */
        fun review(
            shared: ShareableFilterConfiguration,
            target: ShareableFilterConfiguration,
            capabilities: ShareableFilterImportCapabilities,
        ): ShareableFilterImportReview {
            val plan = shared.importPlan(capabilities)
            return ShareableFilterImportReview(
                shared = shared,
                target = target,
                capabilities = capabilities,
                plan = plan,
                replacement = plan.applied.replacementSummary(target),
                reviewToken = commitment(target, plan.applied),
            )
        }

        private fun commitment(
            target: ShareableFilterConfiguration,
            applied: ShareableFilterConfiguration,
        ): String {
            val material = ShareableFilterCodec.deterministicJson(target) +
                "\u0000" +
                ShareableFilterCodec.deterministicJson(applied)
            return MessageDigest.getInstance("SHA-256")
                .digest(material.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
