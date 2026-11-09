package com.lavasec.app.foundation.diagnostics

import com.lavasec.app.foundation.feedback.FeedbackDraft
import java.util.UUID

/**
 * The canonical report identity — the wire `report_id` the backend echoes in a submission receipt.
 *
 * The value is opaque to this layer; it is only minted, compared, and (case-insensitively) matched
 * against a receipt. Ports the canonical lowercase-UUID identity from
 * `Sources/LavaSecAppServices/BugReportBundle.swift` (`report_id`) and the Android submitter's
 * `receipt.equals(review.reportID, ignoreCase = true)` match.
 */
data class ReportIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "report identity must not be blank" }
    }

    /** Canonical case-insensitive receipt match; surrounding whitespace in the echoed value is ignored. */
    fun matchesReceipt(receiptValue: String): Boolean =
        value.equals(receiptValue.trim(), ignoreCase = true)

    /** The value is an opaque random identifier, never account- or device-derived. */
    override fun toString(): String = "ReportIdentity(redacted)"
}

/**
 * The reviewed user input a frozen review is bound to. Equality is the canonical "has the draft
 * changed?" test — an unchanged retry reuses the identity and body, an edit rebuilds it.
 *
 * Only the user's own report fields live here (topic, the single affected site, details, optional
 * contact). There is no account identity, resolver secret/address, or domain-history collection.
 */
data class ReportReviewRequest(
    val draft: FeedbackDraft,
    val includeDiagnostics: Boolean,
)

/**
 * The frozen pre-submission review: the exact identity and request bytes the user reviewed.
 *
 * [request] is retained only so an unchanged retry can be recognized; the canonical request bytes
 * are what actually ship. The bytes are copied on construction and on every read, so a caller can
 * never mutate what was reviewed (mirrors the canonical `BugReportReview`).
 */
class ReportReview internal constructor(
    val identity: ReportIdentity,
    val request: ReportReviewRequest,
    body: ByteArray,
) {
    private val body: ByteArray = body.copyOf()

    /** Defensive copy of the frozen canonical request body. */
    fun requestBytes(): ByteArray = body.copyOf()

    override fun toString(): String = "ReportReview(identity=$identity, bytes=${body.size})"

    override fun equals(other: Any?): Boolean =
        other is ReportReview &&
            identity == other.identity &&
            request == other.request &&
            body.contentEquals(other.body)

    override fun hashCode(): Int {
        var result = identity.hashCode()
        result = 31 * result + request.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

/**
 * One send attempt, identified so a duplicate, late, or superseded completion can be fenced.
 *
 * [id] advances monotonically within a delivery lifecycle; [identity] is the frozen review identity
 * the attempt is expected to be receipted against.
 */
data class ReportAttempt(val id: Long, val identity: ReportIdentity)

/**
 * The retained-report delivery lifecycle phases. The canonical shape is
 * review -> explicit consent -> send attempt -> receipt.
 */
enum class ReportDeliveryPhase {
    /** No frozen review exists yet. */
    DRAFTING,

    /** A frozen review exists and is awaiting explicit consent to send. */
    IN_REVIEW,

    /** An attempt is in flight; [ReportDeliveryState.activeAttempt] names it. */
    SENDING,

    /** A matching receipt confirmed the report; nothing further needs sending. */
    RECEIVED,

    /** The attempt ended without a usable receipt; the frozen review is retained for manual retry. */
    UNCONFIRMED,

    /** HTTP 429: terminal for this attempt; the fallback endpoint must not be attempted. */
    RATE_LIMITED,

    /** Delivery was cancelled by consent revocation or owner retirement; the review may remain. */
    CANCELLED,
}

/** Why a send completion left the report unconfirmed (drives the fallback decision). */
enum class ReportAttemptFailure {
    /** The request never produced an HTTP response (connect/timeout/TLS). */
    TRANSPORT,

    /** The server answered non-2xx (other than 429). */
    SERVER_ERROR,

    /** The server answered 2xx but the body was not a usable receipt. */
    INVALID_RESPONSE,

    /** The server answered 2xx with a `report_id` that did not match the sent identity. */
    RECEIPT_MISMATCH,
}

/**
 * The outcome reported for a specific attempt. [Receipt] carries the server's echoed `report_id`;
 * the machine — not the transport — decides whether it matches.
 */
sealed interface ReportDeliveryCompletion {
    /** The server returned a `report_id`; a match advances to [ReportDeliveryPhase.RECEIVED]. */
    data class Receipt(val reportId: String) : ReportDeliveryCompletion

    /** The attempt ended without a usable receipt; the review is retained and unconfirmed. */
    data class Uncertain(val failure: ReportAttemptFailure) : ReportDeliveryCompletion

    /** HTTP 429. Terminal for this attempt; the fixed-origin fallback is forbidden. */
    data object RateLimited : ReportDeliveryCompletion
}

/**
 * Immutable retained-report delivery state.
 *
 * The frozen review is 1:1 with the identity/body pair, so "unchanged retry reuses the exact
 * identity and body" is structural: the same [review] object is returned until an edit rebuilds it.
 */
data class ReportDeliveryState(
    val phase: ReportDeliveryPhase = ReportDeliveryPhase.DRAFTING,
    val review: ReportReview? = null,
    /** True once any send has been attempted for [review]; an edit after this mints a new identity. */
    val attempted: Boolean = false,
    /** Explicit consent to deliver the current [review]; reset by every edit/prepare. */
    val consented: Boolean = false,
    /** The in-flight attempt, or `null`; fences duplicate and late completions. */
    val activeAttempt: ReportAttempt? = null,
    val nextAttemptId: Long = 0L,
    /** Terminal: the owning credential graph/owner retired; every later transition is refused. */
    val retired: Boolean = false,
) {
    val hasReview: Boolean get() = review != null

    val isReceived: Boolean get() = phase == ReportDeliveryPhase.RECEIVED

    val isUnconfirmed: Boolean get() = phase == ReportDeliveryPhase.UNCONFIRMED

    val isRateLimited: Boolean get() = phase == ReportDeliveryPhase.RATE_LIMITED

    val isSending: Boolean get() = phase == ReportDeliveryPhase.SENDING

    /** The review is retained for a manual retry whenever one exists and the owner has not retired. */
    val retainsReview: Boolean get() = review != null && !retired
}

/** Why a transition produced no new state. */
enum class ReportDeliveryRefusal {
    /** The owner retired; no further delivery transition is accepted. */
    RETIRED,

    /** No frozen review exists to send. */
    NO_REVIEW,

    /** The presented review is not the frozen reviewed object the caller consented to. */
    REVIEW_MISMATCH,

    /** No explicit consent has been granted for the frozen review. */
    CONSENT_REQUIRED,

    /** An attempt is already in flight; a duplicate send is refused. */
    ALREADY_SENDING,

    /** A matching receipt already confirmed this review; a duplicate send is refused. */
    ALREADY_RECEIVED,
}

/** The result of a delivery transition. */
sealed interface ReportDeliveryTransition {
    data class Updated(val state: ReportDeliveryState) : ReportDeliveryTransition

    data class Refused(val refusal: ReportDeliveryRefusal) : ReportDeliveryTransition
}

/**
 * Pure retained-report delivery state machine, ported from the canonical iOS report review/send
 * lifecycle (pinned at `c4658d9`: `DiagnosticsController` `prepareBugReport` /
 * `refreshBugReportDraftContext` / `sendBugReport`, `BugReportSubmissionBundlePolicy.bundleToSubmit`
 * and `NativeBugReportOwner` on the Android side).
 *
 * Invariants that are structural, not conventions:
 *
 * 1. **Frozen review**: [prepare] is the only producer of a [ReportReview]; a retry with the same
 *    [ReportReviewRequest] returns the SAME object, so the identity and body cannot drift between a
 *    review and its retry.
 * 2. **New identity after a post-send edit**: the identity is retained across pre-send edits (the
 *    canonical "edits preserve the captured environment and ID") but is re-minted once an attempt
 *    has happened, because the server ignores duplicate `report_id`s and edited bytes must not ride
 *    the sent identity.
 * 3. **Completion fencing**: every completion names an [ReportAttempt]; a duplicate, late, or
 *    superseded completion is a no-op, so an old attempt can never confirm a replacement review.
 * 4. **Receipt authority**: a 2xx body only advances to [ReportDeliveryPhase.RECEIVED] when its
 *    `report_id` matches; a mismatch retains the review as unconfirmed.
 * 5. **Consent + retirement cancel delivery**: both clear the in-flight attempt, so a completion
 *    that arrives afterwards cannot confirm it.
 *
 * The machine performs no I/O and stores no secret, account identity, resolver address, or domain
 * history; the single user-supplied affected-site field lives only inside the frozen request.
 */
object ReportDeliveryStateMachine {
    /** Production identity mint: the canonical lowercase UUID. */
    val canonicalIdentityMint: () -> ReportIdentity =
        { ReportIdentity(UUID.randomUUID().toString().lowercase()) }

    /**
     * Freezes (or refreshes) the review for [request].
     *
     * An unchanged request returns the existing review object verbatim — the exact identity and
     * body. A changed request rebuilds the body; it keeps the reviewed identity unless a send has
     * already been attempted, in which case a new identity is minted. Consent is never implied by
     * preparation: the caller must still call [grantConsent] before [beginSend].
     *
     * The caller is responsible for only preparing a reviewable draft (the canonical
     * `isReadyForReview` gate); an unready draft is not a delivery concern.
     */
    fun prepare(
        state: ReportDeliveryState,
        request: ReportReviewRequest,
        mintIdentity: () -> ReportIdentity = canonicalIdentityMint,
        buildBody: (ReportIdentity, ReportReviewRequest) -> ByteArray,
    ): ReportDeliveryState {
        if (state.retired) return state

        val current = state.review
        if (current != null && current.request == request) {
            // Idempotent unchanged retry: reuse the frozen identity and body exactly. Preserve a
            // confirmed receipt (a re-open of an already-sent report is not a new draft), but drop
            // any stale consent/attempt bookkeeping.
            return state.copy(
                phase = if (state.phase == ReportDeliveryPhase.RECEIVED) {
                    ReportDeliveryPhase.RECEIVED
                } else {
                    ReportDeliveryPhase.IN_REVIEW
                },
                consented = false,
                activeAttempt = null,
            )
        }

        val identity = when {
            current == null -> mintIdentity()
            state.attempted -> mintIdentity()
            else -> current.identity
        }
        val review = ReportReview(identity, request, buildBody(identity, request))
        return state.copy(
            phase = ReportDeliveryPhase.IN_REVIEW,
            review = review,
            attempted = false,
            consented = false,
            activeAttempt = null,
        )
    }

    /**
     * Grants explicit consent to deliver the frozen review. A no-op when there is no review, the
     * owner has retired, or the report was already received.
     */
    fun grantConsent(state: ReportDeliveryState): ReportDeliveryState =
        if (state.retired || state.review == null || state.phase == ReportDeliveryPhase.RECEIVED) {
            state
        } else {
            state.copy(phase = ReportDeliveryPhase.IN_REVIEW, consented = true)
        }

    /**
     * Withdraws consent and cancels any in-flight attempt. The frozen review is retained so the
     * user can re-review and re-consent; an automatic worker must never retry after this.
     */
    fun revokeConsent(state: ReportDeliveryState): ReportDeliveryState =
        if (state.retired || state.review == null) {
            state
        } else {
            state.copy(
                phase = ReportDeliveryPhase.CANCELLED,
                consented = false,
                activeAttempt = null,
            )
        }

    /**
     * Begins a send for [consentedReview]. The caller must hand back the EXACT frozen object it
     * reviewed and consent must have been granted; a mismatched object or missing consent is
     * refused rather than sent.
     */
    fun beginSend(
        state: ReportDeliveryState,
        consentedReview: ReportReview,
    ): ReportDeliveryTransition {
        if (state.retired) return ReportDeliveryTransition.Refused(ReportDeliveryRefusal.RETIRED)
        val review = state.review
            ?: return ReportDeliveryTransition.Refused(ReportDeliveryRefusal.NO_REVIEW)
        if (consentedReview !== review) {
            return ReportDeliveryTransition.Refused(ReportDeliveryRefusal.REVIEW_MISMATCH)
        }
        if (!state.consented) {
            return ReportDeliveryTransition.Refused(ReportDeliveryRefusal.CONSENT_REQUIRED)
        }
        when (state.phase) {
            ReportDeliveryPhase.SENDING ->
                return ReportDeliveryTransition.Refused(ReportDeliveryRefusal.ALREADY_SENDING)
            ReportDeliveryPhase.RECEIVED ->
                return ReportDeliveryTransition.Refused(ReportDeliveryRefusal.ALREADY_RECEIVED)
            else -> Unit
        }

        val attempt = ReportAttempt(id = state.nextAttemptId + 1, identity = review.identity)
        return ReportDeliveryTransition.Updated(
            state.copy(
                phase = ReportDeliveryPhase.SENDING,
                attempted = true,
                activeAttempt = attempt,
                nextAttemptId = attempt.id,
            ),
        )
    }

    /**
     * Applies a completion for [attempt]. A completion whose attempt is not the current in-flight
     * one is ignored (duplicate, late, or superseded). A 2xx body only confirms the report when the
     * echoed `report_id` matches the attempt's identity; otherwise the review stays retained and
     * unconfirmed. A 429 ends the attempt terminally.
     */
    fun complete(
        state: ReportDeliveryState,
        attempt: ReportAttempt,
        completion: ReportDeliveryCompletion,
    ): ReportDeliveryState {
        if (state.retired) return state
        val active = state.activeAttempt ?: return state
        if (active != attempt) return state

        return when (completion) {
            is ReportDeliveryCompletion.Receipt ->
                if (attempt.identity.matchesReceipt(completion.reportId)) {
                    state.copy(phase = ReportDeliveryPhase.RECEIVED, activeAttempt = null)
                } else {
                    state.copy(phase = ReportDeliveryPhase.UNCONFIRMED, activeAttempt = null)
                }

            is ReportDeliveryCompletion.Uncertain ->
                state.copy(phase = ReportDeliveryPhase.UNCONFIRMED, activeAttempt = null)

            ReportDeliveryCompletion.RateLimited ->
                state.copy(phase = ReportDeliveryPhase.RATE_LIMITED, activeAttempt = null)
        }
    }

    /**
     * Owner/graph retirement: cancels any in-flight delivery and makes the state terminal. The
     * review may remain for display, but no completion can confirm it and no further send begins.
     */
    fun retire(state: ReportDeliveryState): ReportDeliveryState =
        state.copy(
            phase = ReportDeliveryPhase.CANCELLED,
            consented = false,
            activeAttempt = null,
            retired = true,
        )

    /** Ends an abandoned editing session, discarding the retained review and its consent. */
    fun discard(state: ReportDeliveryState): ReportDeliveryState =
        if (state.retired) state else ReportDeliveryState()
}
