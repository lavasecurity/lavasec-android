package com.lavasec.app.foundation.domain

import com.lavasec.app.foundation.parity.ParityFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.system.measureTimeMillis

class DomainNameTest {
    @Test
    fun `shared normalization fixtures match pinned iOS DomainName behavior`() {
        for (fixture in ParityFixtures.objects("domain-name-cases.json")) {
            val input = fixture.getValue("input")!!
            val expected = fixture["normalized"]
            val expectedError = fixture["error"]
            try {
                val parsed = DomainName.parse(input)
                assertEquals(fixture["name"], expected, parsed.value)
                assertEquals(parsed.value, parsed.toString())
                if (expectedError != null) fail("${fixture["name"]}: expected $expectedError")
            } catch (error: DomainValidationException) {
                assertEquals(fixture["name"], expectedError, error.reason.name)
            }
        }
    }

    @Test
    fun `length and label boundaries are measured after canonicalization in UTF-8 bytes`() {
        val label63 = "a".repeat(63)
        val exactly253 = listOf(label63, label63, label63, "a".repeat(61)).joinToString(".")
        assertEquals(253, DomainName.parse(exactly253).value.toByteArray().size)

        assertEquals(
            DomainValidationError.TOO_LONG,
            rejectedReason(listOf(label63, label63, label63, label63).joinToString(".")),
        )
        assertEquals(DomainValidationError.INVALID_LABEL, rejectedReason("${"a".repeat(64)}.com"))
    }

    @Test
    fun `punycode label and host limits are enforced after non-transitional conversion`() {
        val encoded63 = "é".repeat(57)
        assertEquals(63, DomainName.parse("$encoded63.com").value.substringBefore('.').length)

        assertEquals(DomainValidationError.INVALID_LABEL, rejectedReason("${"é".repeat(58)}.com"))
        val exactly253 = listOf(encoded63, encoded63, encoded63, "a".repeat(61)).joinToString(".")
        assertEquals(253, DomainName.parse(exactly253).value.length)
        assertEquals(
            DomainValidationError.TOO_LONG,
            rejectedReason(listOf(encoded63, encoded63, encoded63, "a".repeat(62)).joinToString(".")),
        )
    }

    @Test
    fun `invalid scalars and oversized punycode work are rejected without arithmetic wrap`() {
        assertEquals(DomainValidationError.INVALID_LABEL, rejectedReason("\u0000.example"))
        assertEquals(
            DomainValidationError.INVALID_LABEL,
            rejectedReason(String(charArrayOf('\ud800')) + ".example"),
        )
        assertEquals(DomainValidationError.INVALID_LABEL, rejectedReason("a\udfff.example"))
        lateinit var reason: DomainValidationError
        val elapsedMillis = measureTimeMillis {
            reason = rejectedReason("${"☕".repeat(100_000)}.com")
        }
        assertEquals(DomainValidationError.TOO_LONG, reason)
        assertTrue("oversized punycode input took $elapsedMillis ms", elapsedMillis < 5_000)
    }

    @Test
    fun `value equality uses the normalized spelling`() {
        assertEquals(DomainName.parse("Example.COM"), DomainName.parse("example.com."))
        assertNotEquals(DomainName.parse("example.com"), DomainName.parse("example.net"))
    }

    private fun rejectedReason(input: String): DomainValidationError = try {
        DomainName.parse(input)
        fail("Expected rejection for $input")
        error("unreachable")
    } catch (error: DomainValidationException) {
        error.reason
    }
}
