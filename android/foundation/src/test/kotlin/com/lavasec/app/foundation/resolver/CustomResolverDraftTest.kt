package com.lavasec.app.foundation.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomResolverDraftTest {
    @Test
    fun `draft dirtiness is derived from trimmed content against the saved entry`() {
        val saved = CustomResolverDraft("Mine", "8.8.8.8", "1.1.1.1")
        assertFalse(CustomResolverDraft("Mine", "8.8.8.8", "1.1.1.1").hasChangesAgainst(saved))
        // Whitespace is trimmed before comparison, matching the iOS draft semantics.
        assertFalse(CustomResolverDraft(" Mine ", "8.8.8.8", "1.1.1.1").hasChangesAgainst(saved))
        assertFalse(CustomResolverDraft("Mine", " 8.8.8.8 ", " 1.1.1.1 ").hasChangesAgainst(saved))
        assertTrue(CustomResolverDraft("", "8.8.8.8", "").hasChangesAgainst(saved))
        assertTrue(CustomResolverDraft("Mine", "9.9.9.9", "1.1.1.1").hasChangesAgainst(saved))
        assertTrue(CustomResolverDraft("Other", "8.8.8.8", "1.1.1.1").hasChangesAgainst(saved))
    }

    @Test
    fun `a nil saved entry is a cleared saved entry`() {
        assertFalse(CustomResolverDraft().hasChangesAgainst(null))
        assertTrue(CustomResolverDraft("", "8.8.8.8", "").hasChangesAgainst(null))
        assertTrue(CustomResolverDraft("Named", "", "").hasChangesAgainst(null))
    }

    @Test
    fun `the cleared shape is exactly all fields empty after trimming`() {
        assertTrue(CustomResolverDraft().isCleared)
        assertTrue(CustomResolverDraft("   ", " ", "\t").isCleared)
        assertFalse(CustomResolverDraft("", "8.8.8.8", "").isCleared)
        assertFalse(CustomResolverDraft("Name", "", "").isCleared)
    }

    @Test
    fun `an empty primary is invalid`() {
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.EMPTY_PRIMARY),
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = "   ")),
        )
    }

    @Test
    fun `a valid DoH endpoint parses to the normalized https URL`() {
        assertEquals(
            ParsedCustomResolverEndpoint(ParsedCustomResolverTransport.DOH, "https://dns.google/dns-query"),
            CustomResolverValidator.parse(" https://dns.google/dns-query "),
        )
        assertEquals(
            ParsedCustomResolverEndpoint(ParsedCustomResolverTransport.DOH, "https://dns.google:443/dns-query"),
            CustomResolverValidator.parse("https://dns.google:443/dns-query"),
        )
        assertEquals(
            CustomResolverValidation.Valid,
            CustomResolverValidator.validate(
                CustomResolverDraft(primaryAddress = "https://dns.google/dns-query"),
            ),
        )
    }

    @Test
    fun `DoH retains encoded path and exact query ordering duplicates and empty values`() {
        for (suffix in listOf("", "?", "?mode=strict&empty=&flag", "?value=%2F%2f%25%20%2B&value=second&ordered=first")) {
            val normalized = "https://Resolver.Example:8443/dns%2Fquery$suffix"
            val parsed = CustomResolverValidator.parse(" HTTPS://Resolver.Example:8443/dns%2Fquery$suffix ")!!
            assertEquals(ParsedCustomResolverTransport.DOH, parsed.transport)
            assertEquals(normalized, parsed.normalized)
            assertEquals(normalized, parsed.resolvedEndpoint)
            assertEquals(normalized, CustomResolverValidator.normalizedDohUrl(normalized))
            assertEquals(normalized, CustomResolverValidator.resolvedDohUrl(normalized))
            assertEquals(CustomResolverValidation.Valid,
                CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = normalized)))
        }
    }

    @Test
    fun `DoH fragments and malformed percent escapes are refused instead of silently stripped`() {
        for (suffix in listOf("#", "#unused", "?mode=strict#unused", "?mode=%", "?mode=%GG")) {
            val endpoint = "https://resolver.example/dns-query$suffix"
            assertNull(CustomResolverValidator.parse(endpoint))
            assertNull(CustomResolverValidator.normalizedDohUrl(endpoint))
            assertNull(CustomResolverValidator.resolvedDohUrl(endpoint))
            assertEquals(CustomResolverValidationFailure.UNSUPPORTED_FORMAT, CustomResolverValidator.parseFailure(endpoint))
            assertEquals(CustomResolverValidation.Invalid(CustomResolverValidationFailure.UNSUPPORTED_FORMAT),
                CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = endpoint)))
        }
        // An encoded '#' is query data, not a fragment delimiter.
        val endpoint = "https://resolver.example/dns-query?mode=%23strict"
        assertEquals(endpoint, CustomResolverValidator.normalizedDohUrl(endpoint))
    }

    @Test
    fun `DoH primary and secondary retain independent query identities and same transport admission`() {
        val primary = "https://resolver.example/dns-query?profile=primary&empty="
        val secondary = "https://resolver.example/dns-query?profile=secondary&flag"
        assertEquals(CustomResolverValidation.Valid,
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = primary, secondaryAddress = secondary)))
        assertEquals(primary, CustomResolverValidator.normalizedDohUrl(primary))
        assertEquals(secondary, CustomResolverValidator.normalizedDohUrl(secondary))
        assertFalse(CustomResolverValidator.endpointIdentity(primary) == CustomResolverValidator.endpointIdentity(secondary))
        assertEquals(CustomResolverValidation.Invalid(CustomResolverValidationFailure.SECONDARY_TRANSPORT_MISMATCH),
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = primary, secondaryAddress = "tls://resolver.example")))
        assertEquals(CustomResolverValidation.Invalid(CustomResolverValidationFailure.UNSUPPORTED_FORMAT),
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = primary, secondaryAddress = "$secondary#unused")))
    }

    @Test
    fun `DoH rejects ports outside the connectable range`() {
        assertNull(CustomResolverValidator.parse("https://dns.google:99999/dns-query"))
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.UNSUPPORTED_FORMAT),
            CustomResolverValidator.validate(
                CustomResolverDraft(primaryAddress = "https://dns.google:99999/dns-query"),
            ),
        )
    }

    @Test
    fun `ip literal hosts inside encrypted schemes obey the unusable address contract`() {
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.LOCALHOST_NOT_ALLOWED),
            CustomResolverValidator.validate(
                CustomResolverDraft(primaryAddress = "https://127.0.0.1/dns-query"),
            ),
        )
        assertEquals(
            CustomResolverValidationFailure.LOCALHOST_NOT_ALLOWED,
            CustomResolverValidator.parseFailure("https://127.0.0.1/dns-query"),
        )
        assertEquals(
            CustomResolverValidationFailure.UNUSABLE_RESOLVER_ADDRESS,
            CustomResolverValidator.parseFailure("tls://169.254.1.1"),
        )
        assertNull(CustomResolverValidator.parse("tls://169.254.1.1"))
    }

    @Test
    fun `DoH requires a non empty path beyond the root`() {
        assertEquals(
            CustomResolverValidationFailure.MISSING_DOH_PATH,
            CustomResolverValidator.parseFailure("https://dns.google"),
        )
        assertEquals(
            CustomResolverValidationFailure.MISSING_DOH_PATH,
            CustomResolverValidator.parseFailure("https://dns.google/"),
        )
    }

    @Test
    fun `credentials are rejected on every scheme`() {
        assertEquals(
            CustomResolverValidationFailure.CREDENTIALS_NOT_ALLOWED,
            CustomResolverValidator.parseFailure("https://user:pass@dns.google/dns-query"),
        )
        assertEquals(
            CustomResolverValidationFailure.CREDENTIALS_NOT_ALLOWED,
            CustomResolverValidator.parseFailure("tls://user:pass@dns.google"),
        )
    }

    @Test
    fun `localhost names and loopback literals are rejected`() {
        assertEquals(
            CustomResolverValidationFailure.LOCALHOST_NOT_ALLOWED,
            CustomResolverValidator.parseFailure("https://localhost/dns-query"),
        )
        assertEquals(
            CustomResolverValidationFailure.LOCALHOST_NOT_ALLOWED,
            CustomResolverValidator.parseFailure("tls://localhost"),
        )
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.LOCALHOST_NOT_ALLOWED),
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = "127.0.0.1")),
        )
    }

    @Test
    fun `encrypted resolver names follow frozen localhost namespace and DNS label rules`() {
        for (host in listOf("localhost", "LOCALHOST", "dev.localhost", "a.b.localhost")) {
            for (endpoint in listOf("https://$host/dns-query", "tls://$host:853", "doq://$host:853")) {
                assertNull(CustomResolverValidator.parse(endpoint))
                assertEquals(CustomResolverValidationFailure.LOCALHOST_NOT_ALLOWED,
                    CustomResolverValidator.parseFailure(endpoint))
            }
        }
        for (host in listOf("resolver.local", "localhost.example.com", "resolver.example")) {
            for (endpoint in listOf("https://$host/dns-query", "tls://$host:853", "doq://$host:853")) {
                assertNotNull(CustomResolverValidator.parse(endpoint))
            }
        }
        for (host in listOf("resolver", "bad..example", "-bad.example", "bad-.example", "bad_label.example",
            "${"a".repeat(64)}.example")) {
            for (endpoint in listOf("https://$host/dns-query", "tls://$host:853", "doq://$host:853")) {
                assertNull(CustomResolverValidator.parse(endpoint))
                assertEquals(CustomResolverValidationFailure.UNSUPPORTED_FORMAT,
                    CustomResolverValidator.parseFailure(endpoint))
            }
        }
    }

    @Test
    fun `unusable address literals are rejected`() {
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.UNUSABLE_RESOLVER_ADDRESS),
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = "169.254.1.1")),
        )
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.UNUSABLE_RESOLVER_ADDRESS),
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = "0.0.0.0")),
        )
    }

    @Test
    fun `a valid DoT endpoint parses with the default port and explicit ports are kept`() {
        assertEquals(
            ParsedCustomResolverEndpoint(ParsedCustomResolverTransport.DOT, "dns.google:853"),
            CustomResolverValidator.parse("tls://dns.google"),
        )
        assertEquals(
            ParsedCustomResolverEndpoint(ParsedCustomResolverTransport.DOT, "dns.google:8853"),
            CustomResolverValidator.parse("dot://dns.google:8853"),
        )
    }

    @Test
    fun `DoT rejects query fragments non root paths and invalid ports`() {
        assertEquals(
            CustomResolverValidationFailure.UNSUPPORTED_FORMAT,
            CustomResolverValidator.parseFailure("tls://dns.google?x=1"),
        )
        assertEquals(
            CustomResolverValidationFailure.UNSUPPORTED_FORMAT,
            CustomResolverValidator.parseFailure("tls://dns.google/path"),
        )
        assertEquals(
            CustomResolverValidationFailure.UNSUPPORTED_FORMAT,
            CustomResolverValidator.parseFailure("tls://dns.google:0"),
        )
        assertEquals(
            CustomResolverValidationFailure.UNSUPPORTED_FORMAT,
            CustomResolverValidator.parseFailure("tls://dns.google:99999"),
        )
    }

    @Test
    fun `DoQ validates and normalizes both endpoint schemes`() {
        assertEquals(
            ParsedCustomResolverEndpoint(ParsedCustomResolverTransport.DOQ, "dns.google:8853"),
            CustomResolverValidator.parse("doq://dns.google:8853"),
        )
        assertEquals(
            CustomResolverValidation.Valid,
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = "quic://dns.google")),
        )
        assertEquals("doq://dns.google:853", CustomResolverValidator.normalizedDoqEndpoint("quic://dns.google"))
        assertEquals("doq://[fd00::53]:8853", CustomResolverValidator.normalizedDoqEndpoint("doq://[fd00::53]:8853"))
        assertEquals(null, CustomResolverValidator.normalizedDoqEndpoint("doq://dns.google:53"))
    }

    @Test
    fun `stamps report the bad stamp failure`() {
        assertEquals(
            CustomResolverValidationFailure.BAD_STAMP,
            CustomResolverValidator.parseFailure("sdns://anything"),
        )
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.BAD_STAMP),
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = "sdns://anything")),
        )
    }

    @Test
    fun `bare IPv4 and IPv6 literals parse as plain addresses`() {
        assertEquals(
            ParsedCustomResolverEndpoint(ParsedCustomResolverTransport.PLAIN, "8.8.8.8"),
            CustomResolverValidator.parse("8.8.8.8"),
        )
        assertEquals(null, CustomResolverValidator.parse("8.8.8.8, 1.1.1.1"))
        assertEquals(
            ParsedCustomResolverEndpoint(ParsedCustomResolverTransport.PLAIN, "2001:4860:4860:0:0:0:0:8888"),
            CustomResolverValidator.parse("2001:4860:4860::8888"),
        )
    }

    @Test
    fun `portable plain fields accept one private literal and reject lists reserved and embedded unusable addresses`() {
        for (address in listOf("192.168.1.53", "10.0.0.53", "172.16.0.53", "fd00::53", "64:ff9b:1::53")) {
            assertNotNull(CustomResolverValidator.normalizedPlainEndpoint(address))
        }
        for (address in listOf("8.8.8.8,1.1.1.1", "8.8.8.8 1.1.1.1", "dns.google", "udp://8.8.8.8:53",
            "192.168.1.53,", ",192.168.1.53", "192.168.1.53,,", "192.168.1.53\t192.168.1.54",
            "0.1.2.3", "127.0.0.1", "169.254.1.1", "224.0.0.1", "240.0.0.1", "100.64.0.1",
            "192.0.0.53", "192.0.2.53", "192.88.99.1", "198.18.0.1", "198.51.100.53", "203.0.113.53",
            "2001:db8::53", "::ffff:192.0.2.53", "::c000:235", "64:ff9b::c000:235")) {
            assertEquals(address, null, CustomResolverValidator.normalizedPlainEndpoint(address))
        }
        assertEquals(CustomResolverValidation.Valid, CustomResolverValidator.validate(CustomResolverDraft(
            primaryAddress = "192.168.1.53", secondaryAddress = "192.168.1.54")))
        assertEquals(CustomResolverValidation.Invalid(CustomResolverValidationFailure.UNSUPPORTED_FORMAT),
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = "192.168.1.53",
                secondaryAddress = "192.168.1.54,")))
        assertTrue(CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = "192.168.1.53",
            secondaryAddress = "192.0.2.54")) is CustomResolverValidation.Invalid)
    }

    @Test
    fun `a bare hostname or free text is unsupported`() {
        assertEquals(
            CustomResolverValidationFailure.UNSUPPORTED_FORMAT,
            CustomResolverValidator.parseFailure("dns.google"),
        )
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.UNSUPPORTED_FORMAT),
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = "not a resolver")),
        )
    }

    @Test
    fun `the secondary must use the same transport as the primary`() {
        assertEquals(
            CustomResolverValidation.Valid,
            CustomResolverValidator.validate(
                CustomResolverDraft("", "8.8.8.8", "1.1.1.1"),
            ),
        )
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.SECONDARY_TRANSPORT_MISMATCH),
            CustomResolverValidator.validate(
                CustomResolverDraft("", "8.8.8.8", "https://dns.google/dns-query"),
            ),
        )
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.SECONDARY_TRANSPORT_MISMATCH),
            CustomResolverValidator.validate(
                CustomResolverDraft("", "https://dns.google/dns-query", "tls://dns.google"),
            ),
        )
    }

    @Test
    fun `a bracket-wrapped IPv6 DoT endpoint normalizes with a single bracket pair`() {
        // URI.getHost() returns an IPv6 literal already wrapped in brackets; re-bracketing it
        // would yield `[[..]]:port`, which the durable plan and runtime cannot re-parse.
        assertEquals(
            "tls://[fd00::1]:853",
            CustomResolverValidator.normalizedDotEndpoint("tls://[fd00::1]:853"),
        )
        assertEquals(
            "tls://[fd00::1]:853",
            CustomResolverValidator.normalizedDotEndpoint("tls://[fd00::1]"),
        )
        assertEquals(
            "[fd00::1]:853",
            CustomResolverValidator.parse("tls://[fd00::1]:853")?.normalized,
        )
    }

    @Test
    fun `the cleared shape is a caller level clear intent not a validated save`() {
        // The iOS save flow branches on the cleared shape before validation:
        // validation of a cleared draft reports the empty primary so a caller
        // can never confuse "clear" with a saved empty resolver.
        assertEquals(
            CustomResolverValidation.Invalid(CustomResolverValidationFailure.EMPTY_PRIMARY),
            CustomResolverValidator.validate(CustomResolverDraft()),
        )
        assertTrue(CustomResolverDraft().isCleared)
    }
}
