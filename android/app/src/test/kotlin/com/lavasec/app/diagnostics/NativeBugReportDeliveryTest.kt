package com.lavasec.app.diagnostics

import com.lavasec.app.foundation.feedback.FeedbackDraft
import com.lavasec.app.foundation.feedback.FeedbackTopic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the owner's adoption of [com.lavasec.app.foundation.diagnostics.ReportDeliveryStateMachine].
 * The review factory is injected so the lifecycle is exercised without the platform JSON body
 * builder; [NativeBugReportOwnerTest] pins the production body/preview separately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeBugReportDeliveryTest {
    private val draft = FeedbackDraft(
        topic = FeedbackTopic.WEBSITE_ACCESS,
        affectedSite = "example.com",
        details = "The site will not load",
        contactEmail = "user@example.com",
    )

    private val factory = NativeBugReportReviewFactory { id, reviewed, _, _ ->
        BugReportReview(id, "preview:${reviewed.normalizedDetails}", "body:$id".toByteArray())
    }

    @Test
    fun `explicit consent gates delivery`() = runTest {
        var sends = 0
        val owner = NativeBugReportOwner(
            capture = { error("the injected factory must not capture an environment") },
            send = { sends++; BugReportSendResult.SENT },
            scope = this,
            reviewFactory = factory,
        )
        val review = checkNotNull(owner.prepare(draft, false))

        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(review))
        assertEquals(0, sends)

        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.SENT, owner.submit(review))
        assertEquals(1, sends)
        owner.close()
    }

    @Test
    fun `a matching receipt confirms the report and a resend is refused`() = runTest {
        var sends = 0
        val owner = NativeBugReportOwner({ error("no capture") }, { sends++; BugReportSendResult.SENT }, this, factory)
        val review = checkNotNull(owner.prepare(draft, false))
        assertTrue(owner.grantConsent())

        assertEquals(BugReportSendResult.SENT, owner.submit(review))
        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(review))
        assertEquals(1, sends)
        owner.close()
    }

    @Test
    fun `an unchanged retry reuses the exact identity and body`() = runTest {
        val sent = mutableListOf<BugReportReview>()
        val owner = NativeBugReportOwner(
            { error("no capture") },
            { sent += it; if (sent.size == 1) BugReportSendResult.FAILED else BugReportSendResult.SENT },
            this,
            factory,
        )
        val first = checkNotNull(owner.prepare(draft, false))
        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.FAILED, owner.submit(first))

        val retried = checkNotNull(owner.prepare(draft, false))
        assertSame(first, retried)
        assertArrayEquals(first.requestBytes(), retried.requestBytes())

        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.SENT, owner.submit(retried))
        assertEquals(sent[0].reportID, sent[1].reportID)
        assertArrayEquals(sent[0].requestBytes(), sent[1].requestBytes())
        owner.close()
    }

    @Test
    fun `a post-send edit mints a new identity and rejects the old capability`() = runTest {
        var sends = 0
        val owner = NativeBugReportOwner({ error("no capture") }, { sends++; BugReportSendResult.SENT }, this, factory)
        val first = checkNotNull(owner.prepare(draft, false))
        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.SENT, owner.submit(first))

        val edited = checkNotNull(owner.prepare(draft.copy(details = "changed"), false))
        assertNotEquals(first.reportID, edited.reportID)
        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(first))
        assertEquals(1, sends)
        owner.close()
    }

    @Test
    fun `an edit before any attempt keeps the reviewed identity`() = runTest {
        val owner = NativeBugReportOwner({ error("no capture") }, { BugReportSendResult.SENT }, this, factory)
        val first = checkNotNull(owner.prepare(draft, false))
        val edited = checkNotNull(owner.prepare(draft.copy(details = "more detail"), false))

        assertEquals(first.reportID, edited.reportID)
        assertNotSame(first, edited)
        owner.close()
    }

    @Test
    fun `a 429 terminates the attempt`() = runTest {
        var sends = 0
        val owner = NativeBugReportOwner({ error("no capture") }, { sends++; BugReportSendResult.RATE_LIMITED }, this, factory)
        val review = checkNotNull(owner.prepare(draft, false))
        assertTrue(owner.grantConsent())

        assertEquals(BugReportSendResult.RATE_LIMITED, owner.submit(review))
        assertEquals(1, sends)
        owner.close()
    }

    @Test
    fun `retirement cancels an in-flight delivery and refuses later sends`() = runTest {
        val gate = CompletableDeferred<BugReportSendResult>()
        var sends = 0
        val owner = NativeBugReportOwner({ error("no capture") }, { sends++; gate.await() }, this, factory)
        val review = checkNotNull(owner.prepare(draft, false))
        assertTrue(owner.grantConsent())

        val sending = async { owner.submit(review) }
        runCurrent()
        owner.close()
        runCurrent()

        assertTrue(sending.isCancelled)
        assertFalse(owner.grantConsent())
        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(review))
        assertNull(owner.prepare(draft, false))
        assertEquals(1, sends)
    }

    @Test
    fun `discarding the review makes it unsubmittable`() = runTest {
        var sends = 0
        val owner = NativeBugReportOwner({ error("no capture") }, { sends++; BugReportSendResult.SENT }, this, factory)
        val review = checkNotNull(owner.prepare(draft, false))
        assertTrue(owner.grantConsent())

        owner.discard()

        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(review))
        assertEquals(0, sends)
        owner.close()
    }
}
