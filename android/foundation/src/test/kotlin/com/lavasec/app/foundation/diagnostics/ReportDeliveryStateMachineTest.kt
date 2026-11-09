package com.lavasec.app.foundation.diagnostics

import com.lavasec.app.foundation.feedback.FeedbackDraft
import com.lavasec.app.foundation.feedback.FeedbackTopic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDeliveryStateMachineTest {
    private val machine = ReportDeliveryStateMachine

    // ---- review -> consent -> send-attempt -> matching receipt -> received ----

    @Test
    fun `a matching receipt confirms the report`() {
        var state = prepare(ReportDeliveryState(), request())
        val review = state.review!!
        state = grantConsent(state)
        state = begin(state, review)

        state = machine.complete(state, state.activeAttempt!!, receiptFor(review))

        assertEquals(ReportDeliveryPhase.RECEIVED, state.phase)
        assertNull(state.activeAttempt)
        assertSame(review, state.review)
    }

    @Test
    fun `the receipt match is case-insensitive`() {
        val forged = ReportIdentity("AABBCCDD-1111-2222-3333-444455556666")
        var state = prepare(ReportDeliveryState(), request(), minting(forged.value))
        val review = state.review!!
        state = begin(grantConsent(state), review)

        state = machine.complete(
            state,
            state.activeAttempt!!,
            ReportDeliveryCompletion.Receipt("aabbccdd-1111-2222-3333-444455556666"),
        )

        assertEquals(ReportDeliveryPhase.RECEIVED, state.phase)
    }

    @Test
    fun `a mismatched receipt is not received and retains the review`() {
        var state = prepare(ReportDeliveryState(), request())
        val review = state.review!!
        state = begin(grantConsent(state), review)

        state = machine.complete(
            state,
            state.activeAttempt!!,
            ReportDeliveryCompletion.Receipt("00000000-0000-0000-0000-000000000000"),
        )

        assertEquals(ReportDeliveryPhase.UNCONFIRMED, state.phase)
        assertNull(state.activeAttempt)
        assertSame(review, state.review)
        assertTrue(state.retainsReview)
    }

    @Test
    fun `an uncertain attempt retains the frozen draft`() {
        var state = prepare(ReportDeliveryState(), request())
        val review = state.review!!
        state = begin(grantConsent(state), review)

        state = machine.complete(
            state,
            state.activeAttempt!!,
            ReportDeliveryCompletion.Uncertain(ReportAttemptFailure.TRANSPORT),
        )

        assertEquals(ReportDeliveryPhase.UNCONFIRMED, state.phase)
        assertSame(review, state.review)
        assertArrayEquals(bodyFor(review), state.review!!.requestBytes())
    }

    @Test
    fun `a 429 ends the attempt without falling back`() {
        var state = prepare(ReportDeliveryState(), request())
        val review = state.review!!
        state = begin(grantConsent(state), review)

        state = machine.complete(state, state.activeAttempt!!, ReportDeliveryCompletion.RateLimited)

        assertEquals(ReportDeliveryPhase.RATE_LIMITED, state.phase)
        assertNull(state.activeAttempt)
        assertSame(review, state.review)

        val decision = ReportOriginPolicy.decideFallback(
            primaryOrigin = ReportOriginPolicy.PRODUCTION_ORIGIN,
            configuredFallbackOrigin = ReportOriginPolicy.FALLBACK_ORIGIN,
            completion = ReportDeliveryCompletion.RateLimited,
        )
        assertEquals(
            ReportFallbackDecision.Forbidden(ReportFallbackReason.RATE_LIMITED),
            decision,
        )
    }

    // ---- idempotent unchanged retry / new identity after post-send edits ----

    @Test
    fun `an unchanged retry reuses the exact identity and body`() {
        var state = prepare(ReportDeliveryState(), request())
        val first = state.review!!
        state = begin(grantConsent(state), first)
        state = machine.complete(
            state,
            state.activeAttempt!!,
            ReportDeliveryCompletion.Uncertain(ReportAttemptFailure.SERVER_ERROR),
        )

        // The user retries without editing: prepare returns the SAME review object.
        state = prepare(state, request())

        val reviewed = state.review!!
        assertSame(first, reviewed)
        assertArrayEquals(bodyFor(first), reviewed.requestBytes())

        // ...and the retry attempt reuses the exact identity (a fresh attempt id).
        state = begin(grantConsent(state), reviewed)
        val retryAttempt = state.activeAttempt!!
        assertEquals(first.identity, retryAttempt.identity)
        assertEquals(2L, retryAttempt.id)
    }

    @Test
    fun `preparing an unchanged request twice is idempotent`() {
        val first = prepare(ReportDeliveryState(), request())
        val second = prepare(first, request())

        assertSame(first.review, second.review)
    }

    @Test
    fun `an edit before any attempt retains the reviewed identity`() {
        val first = prepare(ReportDeliveryState(), request(site = "first.example.com"))
        val edited = request(site = "second.example.com")

        val second = prepare(first, edited)

        val firstReview = first.review!!
        val secondReview = second.review!!
        assertEquals(firstReview.identity, secondReview.identity)
        assertNotEquals(bodyFor(firstReview).toList(), secondReview.requestBytes().toList())
    }

    @Test
    fun `an edit after a send attempt mints a new identity`() {
        var state = prepare(
            ReportDeliveryState(),
            request(site = "first.example.com"),
            minting("11111111-1111-1111-1111-111111111111"),
        )
        val first = state.review!!
        state = begin(grantConsent(state), first)
        state = machine.complete(state, state.activeAttempt!!, ReportDeliveryCompletion.RateLimited)

        val edited = request(site = "second.example.com")
        state = prepare(state, edited, minting("22222222-2222-2222-2222-222222222222"))

        val rebuilt = state.review!!
        assertNotEquals(first.identity, rebuilt.identity)
        assertEquals(edited, rebuilt.request)
        assertArrayEquals(bodyFor(rebuilt), rebuilt.requestBytes())
    }

    // ---- consent revocation / owner retirement cancel delivery ----

    @Test
    fun `consent revocation cancels an in-flight delivery and ignores its late completion`() {
        var state = prepare(ReportDeliveryState(), request())
        val review = state.review!!
        state = begin(grantConsent(state), review)
        val attempt = state.activeAttempt!!

        val revoked = machine.revokeConsent(state)

        assertEquals(ReportDeliveryPhase.CANCELLED, revoked.phase)
        assertNull(revoked.activeAttempt)
        assertSame(review, revoked.review)

        // A completion that raced the revocation must not confirm the report.
        assertSame(revoked, machine.complete(revoked, attempt, receiptFor(review)))
    }

    @Test
    fun `consent can be re-granted after revocation and the retry reuses the body`() {
        var state = prepare(ReportDeliveryState(), request())
        val review = state.review!!
        state = begin(grantConsent(state), review)
        state = machine.revokeConsent(state)

        state = begin(grantConsent(state), review)
        assertEquals(ReportDeliveryPhase.SENDING, state.phase)
        assertArrayEquals(bodyFor(review), state.review!!.requestBytes())
    }

    @Test
    fun `owner retirement cancels delivery and makes the state terminal`() {
        var state = prepare(ReportDeliveryState(), request())
        val review = state.review!!
        state = begin(grantConsent(state), review)
        val attempt = state.activeAttempt!!

        val retired = machine.retire(state)

        assertEquals(ReportDeliveryPhase.CANCELLED, retired.phase)
        assertTrue(retired.retired)
        assertNull(retired.activeAttempt)

        assertSame(retired, machine.complete(retired, attempt, receiptFor(review)))
        assertSame(retired, prepare(retired, request()))
        assertEquals(
            ReportDeliveryTransition.Refused(ReportDeliveryRefusal.RETIRED),
            machine.beginSend(retired, review),
        )
    }

    // ---- duplicate / late callbacks and idempotency ----

    @Test
    fun `a duplicate completion is a no-op`() {
        var state = prepare(ReportDeliveryState(), request())
        val review = state.review!!
        state = begin(grantConsent(state), review)
        val attempt = state.activeAttempt!!
        state = machine.complete(state, attempt, receiptFor(review))

        assertSame(state, machine.complete(state, attempt, receiptFor(review)))
    }

    @Test
    fun `a late completion from a superseded attempt cannot confirm the replacement`() {
        var state = prepare(ReportDeliveryState(), request(site = "first.example.com"))
        val first = state.review!!
        state = begin(grantConsent(state), first)
        val firstAttempt = state.activeAttempt!!
        state = machine.complete(
            state,
            firstAttempt,
            ReportDeliveryCompletion.Uncertain(ReportAttemptFailure.TRANSPORT),
        )

        // Edit + new attempt.
        state = prepare(state, request(site = "second.example.com"))
        val second = state.review!!
        state = begin(grantConsent(state), second)
        val secondAttempt = state.activeAttempt!!

        // The old attempt's receipt arrives late.
        val afterLate = machine.complete(state, firstAttempt, receiptFor(first))
        assertSame(state, afterLate)
        assertEquals(ReportDeliveryPhase.SENDING, afterLate.phase)

        // Only the current attempt can complete.
        val afterCurrent = machine.complete(state, secondAttempt, receiptFor(second))
        assertEquals(ReportDeliveryPhase.RECEIVED, afterCurrent.phase)
    }

    @Test
    fun `a duplicate send while an attempt is in flight is refused`() {
        var state = prepare(ReportDeliveryState(), request())
        val review = state.review!!
        state = begin(grantConsent(state), review)

        assertEquals(
            ReportDeliveryTransition.Refused(ReportDeliveryRefusal.ALREADY_SENDING),
            machine.beginSend(state, review),
        )
    }

    @Test
    fun `sending without explicit consent is refused`() {
        val state = prepare(ReportDeliveryState(), request())

        assertEquals(
            ReportDeliveryTransition.Refused(ReportDeliveryRefusal.CONSENT_REQUIRED),
            machine.beginSend(state, state.review!!),
        )
    }

    @Test
    fun `sending a review object that is not the frozen one is refused`() {
        var state = prepare(ReportDeliveryState(), request())
        state = grantConsent(state)
        val foreign = prepare(ReportDeliveryState(), request(), minting("11111111-1111-1111-1111-111111111111"))

        assertEquals(
            ReportDeliveryTransition.Refused(ReportDeliveryRefusal.REVIEW_MISMATCH),
            machine.beginSend(state, foreign.review!!),
        )
    }

    @Test
    fun `a received report cannot be sent again`() {
        var state = prepare(ReportDeliveryState(), request())
        val review = state.review!!
        state = begin(grantConsent(state), review)
        state = machine.complete(state, state.activeAttempt!!, receiptFor(review))

        assertEquals(
            ReportDeliveryTransition.Refused(ReportDeliveryRefusal.ALREADY_RECEIVED),
            machine.beginSend(grantConsent(state), review),
        )
    }

    @Test
    fun `retiring an untouched draft leaves it terminal with no review transitions`() {
        val retired = machine.retire(ReportDeliveryState())

        assertTrue(retired.retired)
        assertNull(retired.review)
        assertSame(retired, prepare(retired, request()))
        assertSame(retired, machine.discard(retired))
    }

    @Test
    fun `a discarded draft clears the retained review`() {
        val prepared = prepare(ReportDeliveryState(), request())

        val discarded = machine.discard(prepared)

        assertNull(discarded.review)
        assertEquals(ReportDeliveryPhase.DRAFTING, discarded.phase)
    }

    // ---- helpers ----

    private fun prepare(
        state: ReportDeliveryState,
        request: ReportReviewRequest,
        mint: () -> ReportIdentity = minting("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
    ): ReportDeliveryState = machine.prepare(state, request, mint, ::buildBody)

    private fun grantConsent(state: ReportDeliveryState): ReportDeliveryState =
        machine.grantConsent(state)

    private fun begin(state: ReportDeliveryState, review: ReportReview): ReportDeliveryState =
        (machine.beginSend(state, review) as ReportDeliveryTransition.Updated).state

    private fun receiptFor(review: ReportReview): ReportDeliveryCompletion =
        ReportDeliveryCompletion.Receipt(review.identity.value)

    private fun request(
        site: String = "example.com",
        includeDiagnostics: Boolean = false,
    ): ReportReviewRequest = ReportReviewRequest(
        draft = FeedbackDraft(
            topic = FeedbackTopic.WEBSITE_ACCESS,
            affectedSite = site,
            details = "The site will not load",
        ),
        includeDiagnostics = includeDiagnostics,
    )

    private fun minting(first: String): () -> ReportIdentity {
        var used = false
        return {
            if (!used) {
                used = true
                ReportIdentity(first)
            } else {
                ReportIdentity("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            }
        }
    }

    private fun buildBody(identity: ReportIdentity, request: ReportReviewRequest): ByteArray =
        listOf(
            identity.value,
            request.draft.normalizedAffectedSite,
            request.draft.normalizedDetails,
            request.includeDiagnostics,
        ).joinToString("|").toByteArray()

    private fun bodyFor(review: ReportReview): ByteArray = review.requestBytes()
}
