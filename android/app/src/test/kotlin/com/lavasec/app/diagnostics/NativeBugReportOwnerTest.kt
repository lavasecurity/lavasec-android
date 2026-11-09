package com.lavasec.app.diagnostics

import com.lavasec.app.foundation.feedback.FeedbackDraft
import com.lavasec.app.foundation.feedback.FeedbackTopic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeBugReportOwnerTest {
    private val draft = FeedbackDraft(FeedbackTopic.WEBSITE_ACCESS, "example.com", "fails\r\nDetails", "user@example.com")
    private val environment = BugReportEnvironment("1.0", "1", "9", 28, "connected", true, 500001)

    @Test fun `default captures no environment and no browsing history or optional diagnostics`() = runTest {
        val owner = NativeBugReportOwner({ error("must not capture without opt in") }, { BugReportSendResult.SENT }, this)
        val review = checkNotNull(owner.prepare(draft, false))
        val json = JSONObject(review.requestBytes().toString(Charsets.UTF_8))
        assertEquals(setOf("report_id", "include_recent_dns_events", "has_incident_summary", "include_optional_diagnostics", "kind", "user_description", "contact_email", "device"), json.keys().asSequence().toSet())
        assertEquals(setOf("platform"), json.getJSONObject("device").keys().asSequence().toSet())
        assertFalse(json.getBoolean("include_recent_dns_events"))
        assertFalse(json.getBoolean("include_optional_diagnostics"))
        assertTrue(json.getString("user_description").contains("Affected site/domain: example.com"))
        assertTrue(review.preview.contains("user@example.com"))
        owner.close()
    }

    @Test fun `no consent means no send`() = runTest {
        var sends = 0
        val owner = NativeBugReportOwner({ environment }, { sends++; BugReportSendResult.SENT }, this)
        val review = checkNotNull(owner.prepare(draft, false))

        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(review))
        assertEquals(0, sends)
        owner.close()
    }

    @Test fun `a matching receipt confirms the report and a resend is refused`() = runTest {
        var sends = 0
        val owner = NativeBugReportOwner({ environment }, { sends++; BugReportSendResult.SENT }, this)
        val review = checkNotNull(owner.prepare(draft, false))

        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.SENT, owner.submit(review))
        assertEquals(1, sends)

        // A received report is terminal for this review: re-consenting cannot resend it.
        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(review))
        assertEquals(1, sends)
        owner.close()
    }

    @Test fun `an uncertain completion retains the exact reviewed bytes for an unchanged retry`() = runTest {
        val sent = mutableListOf<BugReportReview>()
        val owner = NativeBugReportOwner({ environment }, { sent += it; if (sent.size == 1) BugReportSendResult.FAILED else BugReportSendResult.SENT }, this)
        val first = checkNotNull(owner.prepare(draft, false))

        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.FAILED, owner.submit(first))

        // An unchanged retry reuses the SAME frozen capability, identity and body.
        assertSame(first, owner.prepare(draft, false))
        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.SENT, owner.submit(first))
        assertSame(sent[0], sent[1])
        assertEquals(sent[0].reportID, sent[1].reportID)
        assertArrayEquals(sent[0].requestBytes(), sent[1].requestBytes())
        owner.close()
    }

    @Test fun `one review freezes environment and defensive request copies`() = runTest {
        var captures = 0
        val owner = NativeBugReportOwner({ captures++; environment }, { BugReportSendResult.FAILED }, this)
        val first = checkNotNull(owner.prepare(draft, true))
        val expected = first.requestBytes()
        first.requestBytes().fill(0)
        assertSame(first, owner.prepare(draft, true))
        assertEquals(1, captures)
        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.FAILED, owner.submit(first))
        assertSame(first, owner.prepare(draft, true))
        assertArrayEquals(expected, first.requestBytes())
        val json = JSONObject(expected.toString(Charsets.UTF_8))
        assertEquals(500001, json.getJSONObject("filters").getInt("compiled_rule_count"))
        assertEquals("connected", json.getJSONObject("vpn").getString("status"))
        assertFalse(json.has("debug_log")); assertFalse(json.has("recent_dns_events"))
        assertTrue(first.preview.contains("500001"))
        assertFalse(first.toString().contains("example.com"))
        owner.close()
    }

    @Test fun `ordinary edits preserve canonical capture and report id while replacing review capability`() = runTest {
        var captures = 0
        val owner = NativeBugReportOwner({ captures++; environment }, { BugReportSendResult.SENT }, this)
        val first = checkNotNull(owner.prepare(draft, true))
        val edited = checkNotNull(owner.prepare(draft.copy(details = "More details"), true))
        assertEquals(first.reportID, edited.reportID)
        assertNotSame(first, edited)
        assertEquals(1, captures)
        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(first))
        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.SENT, owner.submit(edited))
        owner.close()
    }

    @Test fun `edited context after a send uses a fresh id and rejects the old capability`() = runTest {
        var sends = 0
        val owner = NativeBugReportOwner({ environment }, { sends++; BugReportSendResult.SENT }, this)
        val first = checkNotNull(owner.prepare(draft, false))
        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.SENT, owner.submit(first))
        val second = checkNotNull(owner.prepare(draft.copy(details = "changed"), false))
        assertNotEquals(first.reportID, second.reportID)
        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(first))
        assertEquals(1, sends)
        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.SENT, owner.submit(second))
        owner.discard()
        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(second))
        owner.close()
    }

    @Test fun `a 429 terminates the attempt without a fallback`() = runTest {
        var sends = 0
        val owner = NativeBugReportOwner({ environment }, { sends++; BugReportSendResult.RATE_LIMITED }, this)
        val review = checkNotNull(owner.prepare(draft, false))

        assertTrue(owner.grantConsent())
        assertEquals(BugReportSendResult.RATE_LIMITED, owner.submit(review))
        assertEquals(1, sends)
        owner.close()
    }

    @Test fun `hidden-only input and unbounded grapheme payload cannot be submitted`() = runTest {
        val owner = NativeBugReportOwner({ environment }, { error("not submitted") }, this)
        assertNull(owner.prepare(draft.copy(details = "\u2060"), false))
        assertNull(owner.prepare(draft.copy(affectedSite = ""), false))
        // One extended grapheme may still contain arbitrarily many combining marks.
        assertNull(owner.prepare(draft.copy(details = "a" + "\u0301".repeat(40000)), false))
        owner.close()
    }

    @Test fun `concurrent submit is refused and close cancels the active transport`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val owner = NativeBugReportOwner({ environment }, { gate.await(); BugReportSendResult.SENT }, this)
        val review = checkNotNull(owner.prepare(draft, false))
        assertTrue(owner.grantConsent())
        val first = async { owner.submit(review) }; runCurrent()
        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(review))
        assertNull(owner.prepare(draft.copy(details = "edited"), false))
        owner.close(); runCurrent()
        assertTrue(first.isCancelled)
        assertNull(owner.prepare(draft, false))
    }

    @Test fun `discarding a prepared review makes it unsubmittable`() = runTest {
        var sends = 0
        val owner = NativeBugReportOwner({ environment }, { sends++; BugReportSendResult.SENT }, this)
        val review = checkNotNull(owner.prepare(draft, false))
        assertTrue(owner.grantConsent())

        owner.discard()

        assertEquals(BugReportSendResult.UNAVAILABLE, owner.submit(review))
        assertEquals(0, sends)
        owner.close()
    }
}
