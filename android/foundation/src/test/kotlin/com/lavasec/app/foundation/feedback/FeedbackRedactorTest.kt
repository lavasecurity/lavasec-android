package com.lavasec.app.foundation.feedback

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackRedactorTest {
    @Test
    fun `domains and urls are redacted but surrounding prose survives`() {
        assertEquals(
            "I tried [domain] and [domain] earlier.",
            FeedbackRedactor.redact("I tried example.com and www.example.com earlier."),
        )
        assertEquals(
            "See https://[domain]/help for details.",
            FeedbackRedactor.redact("See https://example.com/help for details."),
        )
        assertEquals(
            "nothing here",
            FeedbackRedactor.redact("nothing here"),
        )
    }

    @Test
    fun `emails collapse to one marker even inside a url`() {
        assertEquals(
            "write to [email] about it",
            FeedbackRedactor.redact("write to user@example.com about it"),
        )
        assertEquals(
            "https://[email]/",
            FeedbackRedactor.redact("https://user@example.com/"),
        )
    }

    @Test
    fun `account identifiers and ip literals are redacted`() {
        assertEquals(
            "account [id] failed",
            FeedbackRedactor.redact("account 123456789 failed"),
        )
        assertEquals(
            "connected from [domain]",
            FeedbackRedactor.redact("connected from 192.168.1.10"),
        )
        // Short numbers stay (dates, ports, small counters are not identifiers).
        assertEquals("port 443 was used", FeedbackRedactor.redact("port 443 was used"))
    }

    @Test
    fun `subdomain chains and exotic tlds collapse to one marker`() {
        assertEquals(
            "visit [domain] now",
            FeedbackRedactor.redact("visit deep.sub.example.co.uk now"),
        )
        assertEquals(
            "the [domain] link",
            FeedbackRedactor.redact("the my-app.vercel.app link"),
        )
    }

    @Test
    fun `any valid suffix is redacted without a tld allowlist`() {
        assertEquals(
            "contact [domain] for help",
            FeedbackRedactor.redact("contact customer.example.nl for help"),
        )
        assertEquals(
            "see [domain] or [domain]",
            FeedbackRedactor.redact("see shop.paris.store or api.my.team"),
        )
        // Human prose abbreviations stay untouched.
        assertEquals("i.e. and e.g. are prose", FeedbackRedactor.redact("i.e. and e.g. are prose"))
    }

    @Test
    fun `redaction is stable and idempotent`() {
        val once = FeedbackRedactor.redact("user@example.com and 123456789 and example.com")
        assertEquals(once, FeedbackRedactor.redact(once))
        assertEquals(
            "[email] and [id] and [domain]",
            once,
        )
    }
}
