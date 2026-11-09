package com.lavasec.app.foundation.resolver

import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class DNSStampParserTest {
    @Test
    fun `each frozen supported stamp retains raw text and resolves the real route`() {
        val plain = stamp(0, "192.168.1.53:53")
        assertEquals(plain, CustomResolverValidator.normalizedPlainEndpoint(plain))
        assertEquals("192.168.1.53", CustomResolverValidator.resolvedPlainAddress(plain))
        for ((protocol, transport) in listOf(2 to ParsedCustomResolverTransport.DOH,
            3 to ParsedCustomResolverTransport.DOT, 4 to ParsedCustomResolverTransport.DOQ)) {
            val value = stamp(protocol, "192.168.1.53:8853", "resolver.example", "/dns-query",
                listOf("192.168.1.54", "[fd00::53]"))
            val parsed = checkNotNull(CustomResolverValidator.parse(value))
            assertEquals(transport, parsed.transport)
            assertEquals(value, parsed.normalized)
            assertEquals(listOf("192.168.1.53", "192.168.1.54", "fd00:0:0:0:0:0:0:53"), parsed.bootstrapAddresses)
            when (protocol) {
                2 -> {
                    assertEquals(value, CustomResolverValidator.normalizedDohUrl(value))
                    assertEquals("https://resolver.example/dns-query", CustomResolverValidator.resolvedDohUrl(value))
                }
                3 -> {
                    assertEquals(value, CustomResolverValidator.normalizedDotEndpoint(value))
                    assertEquals("resolver.example" to 8853, CustomResolverValidator.dotHostPort(value))
                }
                4 -> {
                    assertEquals(value, CustomResolverValidator.normalizedDoqEndpoint(value))
                    assertEquals("resolver.example" to 8853, CustomResolverValidator.doqHostPort(value))
                }
            }
        }
    }

    @Test
    fun `host port takes precedence and DoH path is safely encoded as a path`() {
        val doh = stamp(2, "192.168.1.53:8853", "resolver.example:4443", "dns-query?tenant=one")
        assertEquals("https://resolver.example:4443/dns-query%3Ftenant=one", CustomResolverValidator.resolvedDohUrl(doh))
        val dot = stamp(3, "192.168.1.53:8853", "resolver.example:853")
        assertEquals("resolver.example" to 853, CustomResolverValidator.dotHostPort(dot))
        val ipv6 = stamp(4, "[fd00::53]:8853", "[fd00::54]:853")
        assertEquals("fd00::54" to 853, CustomResolverValidator.doqHostPort(ipv6))
        assertEquals(listOf("fd00:0:0:0:0:0:0:53"), CustomResolverValidator.bootstrapAddresses(ipv6))
    }

    @Test
    fun `bootstrap filtering and family order match frozen source without resolving a name`() {
        val value = stamp(3, "bad-bootstrap.example:853", "resolver.example", bootstrap = listOf(
            "[fd00::53]", "192.168.1.53", "192.168.1.53:8853", "127.0.0.1", "192.0.2.53", "resolver.example"))
        assertEquals(listOf("192.168.1.53", "fd00:0:0:0:0:0:0:53"), CustomResolverValidator.bootstrapAddresses(value))
        assertNotNull(CustomResolverValidator.normalizedDotEndpoint(value))
    }

    @Test
    fun `raw primary and secondary stamps retain their own bootstrap and enforce transport`() {
        val primary = stamp(4, "192.168.1.53", "resolver.example")
        val secondary = stamp(4, "192.168.1.54", "secondary.example:8853")
        assertEquals(CustomResolverValidation.Valid, CustomResolverValidator.validate(CustomResolverDraft(
            primaryAddress = primary, secondaryAddress = secondary)))
        val request = SaveResolverTiers(listOf(ResolverTierChoice("custom-dns", "DoQ", "Private", primary, secondary)))
        val accepted = ResolverSelectionPolicy.evaluate(request, ResolverSelectionPresets.DEVICE_DNS_ONLY,
            com.lavasec.app.foundation.subscription.FeatureLimits.PLUS,
            ChainedDnsEditState.editable).let { it as ResolverEditResult.Accepted }
        assertEquals(primary, accepted.commit.selection.primary.customDoqEndpoint)
        assertEquals(secondary, accepted.commit.selection.primary.customSecondaryEndpoint)
        assertEquals(CustomResolverValidation.Invalid(CustomResolverValidationFailure.SECONDARY_TRANSPORT_MISMATCH),
            CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = primary,
                secondaryAddress = stamp(3, "192.168.1.54", "secondary.example"))))
    }

    @Test
    fun `certificate hashes unsupported protocols malformed lengths and trailing data fail closed`() {
        val invalid = listOf("sdns://not-base64!", "sdns://AA", stamp(1, "192.168.1.53"),
            stamp(2, "192.168.1.53", "resolver.example", hashes = byteArrayOf(1)),
            stamp(3, "192.168.1.53", "dev.localhost"), stamp(0, "192.168.1.53:5353"),
            stamp(0, "127.0.0.1"), stamp(4, "192.168.1.53", "resolver.example:53"),
            stamp(3, "192.168.1.53", "resolver.example:0"), stamp(2, "192.168.1.53", "resolver.example", path = ""),
            rawStamp(byteArrayOf(0) + ByteArray(8) + byteArrayOf(127, 1)),
            rawStamp(byteArrayOf(0) + ByteArray(8) + byteArrayOf(1, 0xff.toByte())),
            rawStamp(Base64.getUrlDecoder().decode(stamp(0, "192.168.1.53").substring(7)) + byteArrayOf(0)))
        for (value in invalid) {
            assertNull(value, CustomResolverValidator.parse(value))
            assertEquals(CustomResolverValidation.Invalid(CustomResolverValidationFailure.BAD_STAMP),
                CustomResolverValidator.validate(CustomResolverDraft(primaryAddress = value)))
        }
    }

    @Test
    fun `stamp operational identity includes bootstrap and ignores unused properties`() {
        val first = stamp(2, "192.168.1.53", "resolver.example")
        val propertyOnly = stamp(2, "192.168.1.53", "resolver.example", properties = 7)
        val changedPeer = stamp(2, "192.168.1.54", "resolver.example")
        assertEquals(CustomResolverValidator.endpointIdentity(first), CustomResolverValidator.endpointIdentity(propertyOnly))
        assertNotEquals(CustomResolverValidator.endpointIdentity(first), CustomResolverValidator.endpointIdentity(changedPeer))
        val firstEntry = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH, customDohUrl = first)
        val duplicate = firstEntry.copy(customDohUrl = propertyOnly, customName = "Different name")
        try {
            ResolverSelection(listOf(ResolverTier(firstEntry), ResolverTier(duplicate, false)))
            fail("equivalent decoded route must remain a duplicate")
        } catch (_: IllegalArgumentException) { }
        assertEquals(2, ResolverSelection(listOf(ResolverTier(firstEntry),
            ResolverTier(firstEntry.copy(customDohUrl = changedPeer), false))).tiers.size)
    }

    private fun stamp(protocol: Int, address: String, host: String = "resolver.example", path: String = "/dns-query",
        bootstrap: List<String> = emptyList(), hashes: ByteArray = byteArrayOf(), properties: Int = 0): String {
        val out = ByteArrayOutputStream()
        out.write(protocol); out.write(byteArrayOf(properties.toByte()) + ByteArray(7))
        fun lp(value: ByteArray) { require(value.size <= 127); out.write(value.size); out.write(value) }
        lp(address.toByteArray())
        if (protocol in 2..4) {
            lp(hashes); lp(host.toByteArray())
            if (protocol == 2) lp(path.toByteArray())
            bootstrap.forEachIndexed { index, value ->
                val bytes = value.toByteArray()
                out.write(bytes.size or if (index + 1 < bootstrap.size) 128 else 0); out.write(bytes)
            }
        }
        return rawStamp(out.toByteArray())
    }
    private fun rawStamp(bytes: ByteArray) = "sdns://" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
