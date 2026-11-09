package com.lavasec.app.foundation.domain

import org.junit.Assert.*
import org.junit.Test

class DomainCheckInputTest {
    @Test fun `normalizes hostnames and URL hosts without retaining URL credentials or paths`() {
        val plain = DomainCheckInput.parse("  EXAMPLE.COM.  ")
        assertEquals("example.com", plain.domain); assertFalse(plain.ignoredURLParts)
        for (text in listOf("https://Example.com:443/a?secret=value#fragment", "//example.com/a", "example.com/path")) {
            val parsed = DomainCheckInput.parse(text)
            assertEquals("example.com", parsed.domain); assertTrue(parsed.ignoredURLParts)
        }
        assertEquals("xn--bcher-kva.example", DomainCheckInput.parse("https://bücher.example/path").domain)
        val repaired = DomainCheckInput.parse("https//EXAMPLE.com/path")
        assertEquals("example.com", repaired.domain); assertTrue(repaired.repairedScheme)
    }

    @Test fun `refuses credentials encoded authority invalid ports IPs and parser repairs`() {
        for (text in listOf("https://user:password@example.com/", "https://example.com%40evil.example/", "https://example.com:/",
            "https://example.com:0/", "https://example.com:65536/", "https://example.com:abc/", "http://127.0.0.1/", "https://[::1]/",
            "ftp://example.com/", "localhost", "example.com\\evil.example", "https:///example.com", "example.com\n.evil.example", "not a domain")) {
            val failure = assertThrows(text, DomainCheckInputException::class.java) { DomainCheckInput.parse(text) }
            assertEquals("Enter one valid domain or web URL.", failure.message)
            assertFalse(failure.message!!.contains("password"))
        }
    }

    @Test fun `bounds raw UTF8 before parsing and gives fixed empty input copy`() {
        assertEquals("Enter a domain or URL.", assertThrows(DomainCheckInputException::class.java) { DomainCheckInput.parse("  ") }.message)
        assertEquals("This address is too long. Enter its domain instead.",
            assertThrows(DomainCheckInputException::class.java) { DomainCheckInput.parse("あ".repeat(1400)) }.message)
    }
}
