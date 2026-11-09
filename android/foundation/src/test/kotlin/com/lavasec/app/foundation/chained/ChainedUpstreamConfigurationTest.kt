package com.lavasec.app.foundation.chained

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class ChainedUpstreamConfigurationTest {

    @Test
    fun `saved row activation preserves order and all disabled rows produce no runtime selection`() {
        val entry = create(endpointHost = "192.0.2.1").named("Entry")
        val exit = create(endpointHost = "192.0.2.2").named("Exit")
        val both = exit.withEntryHop(entry)
        assertEquals(listOf("Entry", "Exit"), both.activeConfiguration?.orderedHops?.map { it.displayName })
        assertEquals(entry, exit.enabled(false).withEntryHop(entry).activeConfiguration)
        assertEquals(exit, exit.withEntryHop(entry.enabled(false)).activeConfiguration)
        assertNull(exit.enabled(false).withEntryHop(entry.enabled(false)).activeConfiguration)
    }

    @Test
    fun `ordered split destinations and stack DNS retain the canonical row precedence`() {
        val entry = create(allowedIPs = listOf("10.0.0.0/8"), endpointHost = "192.0.2.1", dns = listOf("10.0.0.1"))
        val exit = create(allowedIPs = listOf("10.7.0.0/16", "172.16.0.0/12"), endpointHost = "192.0.2.2", dns = listOf("172.16.0.1"))
        val stack = exit.withEntryHop(entry)
        assertEquals(0, stack.routeIndexForIPv4(0x0a070001))
        assertEquals(1, stack.routeIndexForIPv4(0xac100001))
        assertNull(stack.routeIndexForIPv4(0xc0000203))
        assertFalse(stack.usesNestedTransport)
        assertEquals(entry.dnsAddresses, stack.stackDNSAddresses)
        assertEquals(ChainedRoutingPolicy.SPLIT_TUNNEL, stack.effectiveRoutingPolicy)
        val fullExit = create(endpointHost = "192.0.2.2").withEntryHop(entry)
        assertEquals(fullExit.dnsAddresses, fullExit.stackDNSAddresses)
        assertEquals(ChainedRoutingPolicy.FULL_TUNNEL, fullExit.effectiveRoutingPolicy)
    }

    @Test
    fun `two enabled rows validate native endpoint and MTU requirements before composition`() {
        val exit = create(endpointHost = "192.0.2.2")
        assertEquals(ChainedStackFailure.ENTRY_REQUIRES_LITERAL_ENDPOINT,
            assertThrows(ChainedStackException::class.java) { exit.withEntryHop(create()) }.failure)
        val entry = create(endpointHost = "192.0.2.1")
        assertEquals(ChainedStackFailure.EXIT_REQUIRES_IPV4_ENDPOINT,
            assertThrows(ChainedStackException::class.java) { create().withEntryHop(entry) }.failure)
        assertEquals(ChainedStackFailure.ENTRY_MTU_TOO_SMALL,
            assertThrows(ChainedStackException::class.java) { exit.withEntryHop(create(endpointHost = "192.0.2.1", mtu = 1280)) }.failure)
        // A disabled row keeps its independently valid metadata without forcing an active stack.
        assertEquals(entry, create().enabled(false).withEntryHop(entry).activeConfiguration)
        assertEquals(1280, exit.withEntryHop(entry).effectiveInterfaceMTU)
    }

    private fun key(fill: Int = 0x42): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })

    private fun create(
        allowedIPs: List<String> = listOf("0.0.0.0/0"),
        clientAddress: String = "10.7.0.2",
        endpointHost: String = "vpn.example.com",
        endpointPort: Int = 51820,
        peerPublicKey: String = key(),
        keepalive: Int = 0,
        mtu: Int? = null,
        dns: List<String> = listOf("10.64.0.1"),
    ): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
        endpointHost = endpointHost,
        endpointPort = endpointPort,
        peerPublicKey = peerPublicKey,
        clientAddress = clientAddress,
        allowedIPs = allowedIPs,
        persistentKeepaliveSeconds = keepalive,
        interfaceMTU = mtu,
        dnsAddresses = dns,
    )

    private fun failureOf(block: () -> Unit): ChainedConfigurationFailure =
        assertThrows(ChainedConfigurationException::class.java) { block() }.failure

    @Test
    fun `a covering default route derives a full tunnel`() {
        assertEquals(ChainedRoutingPolicy.FULL_TUNNEL, create(allowedIPs = listOf("0.0.0.0/0")).routingPolicy)
    }

    @Test
    fun `the two halves idiom is a full tunnel, not a split one`() {
        assertEquals(
            ChainedRoutingPolicy.FULL_TUNNEL,
            create(allowedIPs = listOf("0.0.0.0/1", "128.0.0.0/1")).routingPolicy,
        )
    }

    @Test
    fun `a covering set accepts a v6 prefix parked alongside it`() {
        assertEquals(
            ChainedRoutingPolicy.FULL_TUNNEL,
            create(allowedIPs = listOf("0.0.0.0/0", "::/0")).routingPolicy,
        )
    }

    @Test
    fun `a non-covering IPv4 set derives a split tunnel`() {
        assertEquals(
            ChainedRoutingPolicy.SPLIT_TUNNEL,
            create(allowedIPs = listOf("10.0.0.0/8", "192.168.0.0/16")).routingPolicy,
        )
    }

    @Test
    fun `the policy is a pure function of the routes, so an equal config has an equal policy`() {
        assertEquals(create(allowedIPs = listOf("10.0.0.0/8")).routingPolicy, ChainedRoutingPolicy.SPLIT_TUNNEL)
        assertEquals(create(allowedIPs = listOf("0.0.0.0/0")).routingPolicy, ChainedRoutingPolicy.FULL_TUNNEL)
    }

    @Test
    fun `an empty AllowedIPs set is refused rather than assigned a policy`() {
        assertEquals(ChainedConfigurationFailure.ALLOWED_IPS_EMPTY, failureOf { create(allowedIPs = emptyList()) })
        assertEquals(
            ChainedConfigurationFailure.ALLOWED_IPS_EMPTY,
            failureOf { create(allowedIPs = listOf("  ", "")) },
        )
    }

    @Test
    fun `an IPv6-only upstream is refused with the truthful reason`() {
        assertEquals(
            ChainedConfigurationFailure.IPV6_ONLY_UPSTREAM_UNSUPPORTED,
            failureOf { create(allowedIPs = listOf("::/0")) },
        )
    }

    @Test
    fun `a split set carrying an IPv6 prefix is refused rather than silently leaking it`() {
        assertEquals(
            ChainedConfigurationFailure.SPLIT_TUNNEL_CARRIES_IPV6_ALLOWED_IPS,
            failureOf { create(allowedIPs = listOf("10.0.0.0/8", "::/0")) },
        )
    }

    @Test
    fun `malformed AllowedIPs entries are refused`() {
        for (prefix in listOf("10.0.0.0", "foo:/bar", "::/129", "10.0.0.0/33", "010.0.0.0/8", "10.0.0.256/8")) {
            assertEquals(
                "expected $prefix to be malformed",
                ChainedConfigurationFailure.MALFORMED_ALLOWED_IPS,
                failureOf { create(allowedIPs = listOf(prefix)) },
            )
        }
    }

    @Test
    fun `endpoint host shapes that mean a pasted URL or host-port are refused`() {
        for (host in listOf("", "https://vpn.example.com", "vpn.example.com:51820", "vpn example", "vpn/a", "vpn.example.com..")) {
            assertEquals(
                "expected '$host' to be refused",
                ChainedConfigurationFailure.MALFORMED_ENDPOINT_HOST,
                failureOf { create(endpointHost = host) },
            )
        }
    }

    @Test
    fun `a trailing-dot FQDN and an IP literal are valid hosts`() {
        assertNotNull(create(endpointHost = "vpn.example.com."))
        assertNotNull(create(endpointHost = "203.0.113.7"))
    }

    @Test
    fun `port zero is not a destination`() {
        assertEquals(ChainedConfigurationFailure.INVALID_ENDPOINT_PORT, failureOf { create(endpointPort = 0) })
    }

    @Test
    fun `a peer key that is not 32 base64 bytes is refused`() {
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_PEER_PUBLIC_KEY,
            failureOf { create(peerPublicKey = "not base64") },
        )
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16) { 1 })
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_PEER_PUBLIC_KEY,
            failureOf { create(peerPublicKey = shortKey) },
        )
        // Java tolerates a missing '='; WireGuard's standard form and Apple's decoder do not.
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_PEER_PUBLIC_KEY,
            failureOf { create(peerPublicKey = key().trimEnd('=')) },
        )
        // Java also ignores the unused low bits of the final sextet, so a noncanonical spelling
        // decodes to the same key; only the canonical encoding is accepted.
        val canonical = key()
        val dataCharIndex = canonical.lastIndex - 1
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val flipped = alphabet[alphabet.indexOf(canonical[dataCharIndex]) xor 1]
        val variant = canonical.substring(0, dataCharIndex) + flipped + "="
        assertArrayEquals(
            Base64.getDecoder().decode(canonical),
            Base64.getDecoder().decode(variant),
        )
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_PEER_PUBLIC_KEY,
            failureOf { create(peerPublicKey = variant) },
        )
        assertNotNull(create(peerPublicKey = canonical))
    }

    @Test
    fun `a small-order peer key is refused at construction`() {
        val allZero = Base64.getEncoder().encodeToString(ByteArray(32) { 0 })
        assertEquals(
            ChainedConfigurationFailure.UNUSABLE_PEER_PUBLIC_KEY,
            failureOf { create(peerPublicKey = allZero) },
        )
        // The high-bit twin of the all-zero point is an equivalent key and must also be refused.
        val twinBytes = ByteArray(32) { 0 }.also { it[31] = 0x80.toByte() }
        assertEquals(
            ChainedConfigurationFailure.UNUSABLE_PEER_PUBLIC_KEY,
            failureOf {
                create(peerPublicKey = Base64.getEncoder().encodeToString(twinBytes))
            },
        )
    }

    @Test
    fun `every canonical small-order point and its twin is refused`() {
        val keys = smallOrderPublicKeys()
        assertEquals(14, keys.size)
        for (encoded in keys) {
            assertEquals(
                "expected a small-order key to be refused",
                ChainedConfigurationFailure.UNUSABLE_PEER_PUBLIC_KEY,
                failureOf { create(peerPublicKey = encoded) },
            )
        }
        // Size is only a cheap cross-check; the per-entry assertions above are the oracle
        // (derived from the Curve25519 spec, not from the implementation table).
        assertEquals(keys.size, ChainedUpstreamConfigurationValidator.smallOrderPointCount())
    }

    /** The seven canonical u-coordinates of order dividing 8 plus each high-bit twin. */
    private fun smallOrderPublicKeys(): List<String> {
        val encoder = Base64.getEncoder()
        val canonical = listOf(
            ByteArray(32) { 0x00 },
            byteArrayOf(0x01) + ByteArray(31) { 0x00 },
            byteArrayOf(
                0xe0.toByte(), 0xeb.toByte(), 0x7a, 0x7c, 0x3b, 0x41, 0xb8.toByte(), 0xae.toByte(),
                0x16, 0x56, 0xe3.toByte(), 0xfa.toByte(), 0xf1.toByte(), 0x9f.toByte(), 0xc4.toByte(),
                0x6a, 0xda.toByte(), 0x09, 0x8d.toByte(), 0xeb.toByte(), 0x9c.toByte(), 0x32, 0xb1.toByte(),
                0xfd.toByte(), 0x86.toByte(), 0x62, 0x05, 0x16, 0x5f, 0x49, 0xb8.toByte(), 0x00,
            ),
            byteArrayOf(
                0x5f, 0x9c.toByte(), 0x95.toByte(), 0xbc.toByte(), 0xa3.toByte(), 0x50, 0x8c.toByte(),
                0x24, 0xb1.toByte(), 0xd0.toByte(), 0xb1.toByte(), 0x55, 0x9c.toByte(), 0x83.toByte(),
                0xef.toByte(), 0x5b, 0x04, 0x44, 0x5c, 0xc4.toByte(), 0x58, 0x1c, 0x8e.toByte(), 0x86.toByte(),
                0xd8.toByte(), 0x22, 0x4e, 0xdd.toByte(), 0xd0.toByte(), 0x9f.toByte(), 0x11, 0x57,
            ),
            byteArrayOf(0xec.toByte()) + ByteArray(30) { 0xff.toByte() } + byteArrayOf(0x7f),
            byteArrayOf(0xed.toByte()) + ByteArray(30) { 0xff.toByte() } + byteArrayOf(0x7f),
            byteArrayOf(0xee.toByte()) + ByteArray(30) { 0xff.toByte() } + byteArrayOf(0x7f),
        )
        val twins = canonical.map { point ->
            point.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() }
        }
        return (canonical + twins).map { encoder.encodeToString(it) }
    }

    @Test
    fun `a fullwidth-colon compatibility character does not bypass the delimiter check`() {
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_ENDPOINT_HOST,
            failureOf { create(endpointHost = "vpn.example.com\uFF1A51820") },
        )
    }

    @Test
    fun `an empty key and an out-of-range port are refused`() {
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_PEER_PUBLIC_KEY,
            failureOf { create(peerPublicKey = "") },
        )
        assertEquals(ChainedConfigurationFailure.INVALID_ENDPOINT_PORT, failureOf { create(endpointPort = -1) })
        assertEquals(ChainedConfigurationFailure.INVALID_ENDPOINT_PORT, failureOf { create(endpointPort = 65536) })
    }

    @Test
    fun `a keepalive below the floor is refused, zero and the floor are accepted`() {
        assertEquals(ChainedConfigurationFailure.KEEPALIVE_TOO_FREQUENT, failureOf { create(keepalive = 1) })
        assertNotNull(create(keepalive = 0))
        assertNotNull(create(keepalive = ChainedUpstreamConfiguration.MINIMUM_KEEPALIVE_SECONDS))
    }

    @Test
    fun `unusable client addresses are refused`() {
        for (address in listOf("", "::1", "0.0.0.0", "127.0.0.1", "169.254.1.1", "224.0.0.1", "255.255.255.255")) {
            assertEquals(
                "expected '$address' to be refused",
                ChainedConfigurationFailure.MALFORMED_CLIENT_ADDRESS,
                failureOf { create(clientAddress = address) },
            )
        }
        // Private ranges are the ordinary case for a WireGuard peer.
        assertNotNull(create(clientAddress = "10.7.0.2"))
        assertNotNull(create(clientAddress = "192.168.50.9"))
    }

    @Test
    fun `the client address colliding with the tunnel DNS proxy is refused`() {
        assertEquals(
            ChainedConfigurationFailure.CLIENT_ADDRESS_COLLIDES_WITH_TUNNEL_DNS,
            failureOf { create(clientAddress = ChainedUpstreamConfiguration.DNS_SERVER_ADDRESS) },
        )
    }

    @Test
    fun `an MTU below 1280 is refused in both modes and the floor is accepted`() {
        assertEquals(
            ChainedConfigurationFailure.MTU_BELOW_IPV6_FLOOR,
            failureOf { create(allowedIPs = listOf("0.0.0.0/0"), mtu = 1279) },
        )
        assertEquals(
            ChainedConfigurationFailure.MTU_BELOW_IPV6_FLOOR,
            failureOf { create(allowedIPs = listOf("10.0.0.0/8"), mtu = 1279) },
        )
        assertEquals(1280, create(mtu = 1280).interfaceMTU)
        assertNull(create(mtu = null).interfaceMTU)
        assertEquals(ChainedConfigurationFailure.MTU_OUT_OF_RANGE, failureOf { create(mtu = 65536) })
        assertEquals(ChainedConfigurationFailure.MTU_OUT_OF_RANGE, failureOf { create(mtu = -1) })
        // Android's 16-bit TUN ceiling is not the binding limit for this data path; the engine's
        // inner-IP cap is, so the whole 1501..65535 range is refused with the specific failure.
        assertEquals(
            ChainedConfigurationFailure.MTU_ABOVE_ENGINE_IP_LIMIT,
            failureOf { create(mtu = 65535) },
        )
    }

    @Test
    fun `an MTU above the engine's inner-IP ceiling is refused`() {
        // The engine ABI caps an `encapsulate` source at 1500 bytes; a 1501+ MTU would make every
        // ordinary MTU-sized packet fail as PacketTooLarge, so the boundary refuses it rather than
        // building a queue that sheds all traffic. The ceiling itself is accepted.
        assertEquals(
            ChainedConfigurationFailure.MTU_ABOVE_ENGINE_IP_LIMIT,
            failureOf { create(mtu = 1501) },
        )
        assertEquals(
            ChainedConfigurationFailure.MTU_ABOVE_ENGINE_IP_LIMIT,
            failureOf { create(mtu = 1532) },
        )
        assertEquals(1500, create(mtu = 1500).interfaceMTU)
    }

    @Test
    fun `a DNS hostname is refused, an IP literal of either family is accepted`() {
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_DNS_ADDRESS,
            failureOf { create(dns = listOf("dns.example.com")) },
        )
        assertNotNull(create(dns = listOf("10.64.0.1", "2001:4860:4860::8888")))
    }

    @Test
    fun `create trims and drops empty entries`() {
        val config = create(
            endpointHost = "  vpn.example.com  ",
            clientAddress = " 10.7.0.2 ",
            allowedIPs = listOf(" 10.0.0.0/8 ", "", " "),
            dns = listOf(" 10.64.0.1 ", ""),
        )
        assertEquals("vpn.example.com", config.endpointHost)
        assertEquals("10.7.0.2", config.clientAddress)
        assertEquals(listOf("10.0.0.0/8"), config.allowedIPs)
        assertEquals(listOf("10.64.0.1"), config.dnsAddresses)
    }

    @Test
    fun `interpolation cannot leak the endpoint or the key`() {
        val config = create(endpointHost = "vpn.example.com", peerPublicKey = key())
        assertFalse(config.redactedSummary.contains("vpn.example.com"))
        assertFalse(config.redactedSummary.contains(key()))
        assertEquals(config.redactedSummary, config.toString())
        assertFalse(config.toString().contains("vpn.example.com"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the stored route and DNS lists cannot be mutated after construction`() {
        val config = create(allowedIPs = listOf("10.0.0.0/8"), dns = listOf("10.64.0.1"))
        assertThrows(UnsupportedOperationException::class.java) {
            (config.allowedIPs as MutableList<String>).add("0.0.0.0/0")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (config.dnsAddresses as MutableList<String>).add("1.1.1.1")
        }
        // The derived policy cannot be moved by a post-construction mutation.
        assertEquals(ChainedRoutingPolicy.SPLIT_TUNNEL, config.routingPolicy)
    }

    @Test
    fun `unicode decimal digits are not accepted in IP fields`() {
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_CLIENT_ADDRESS,
            failureOf { create(clientAddress = "\u0661\u0660.\u0667.0.2") },
        )
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_ALLOWED_IPS,
            failureOf { create(allowedIPs = listOf("0.0.0.0/\u0660")) },
        )
    }

    @Test
    fun `a keepalive outside the 16-bit range is refused`() {
        assertEquals(ChainedConfigurationFailure.KEEPALIVE_OUT_OF_RANGE, failureOf { create(keepalive = 65536) })
        assertEquals(ChainedConfigurationFailure.KEEPALIVE_OUT_OF_RANGE, failureOf { create(keepalive = -1) })
        assertNotNull(create(keepalive = 65535))
    }

    @Test
    fun `an IPv6 endpoint literal is refused at this boundary, matching iOS`() {
        // iOS ChainedUpstreamConfigurationTests asserts malformedEndpointHost for a bare or
        // bracketed IPv6 literal: this field is a host, and a colon is ambiguous with host:port.
        // IPv6 endpoint addresses are handled by the endpoint-resolution layer, not here.
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_ENDPOINT_HOST,
            failureOf { create(endpointHost = "2001:db8::1") },
        )
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_ENDPOINT_HOST,
            failureOf { create(endpointHost = "[2001:db8::1]") },
        )
    }

    @Test
    fun `an internationalized endpoint host keeps its spelling but exposes the A-label form`() {
        // Stored as written (parity with iOS, for log comparison), resolved via the A-label.
        val unicode = "b\u00fccher.example"
        val config = create(endpointHost = unicode)
        assertEquals(unicode, config.endpointHost)
        assertEquals("xn--bcher-kva.example", config.endpointHostWireForm)
        // ASCII hosts are returned unchanged.
        assertEquals("vpn.example.com", create(endpointHost = "vpn.example.com").endpointHostWireForm)
        // A UTS46 root separator canonicalizes to a trailing dot, which is then stripped.
        assertEquals("vpn.example.com", create(endpointHost = "vpn.example.com\u3002").endpointHostWireForm)
        assertEquals("vpn.example.com", create(endpointHost = "vpn.example.com.").endpointHostWireForm)
        // Mixed ASCII and UTS46 root separators normalize identically in validation and the getter.
        assertEquals("vpn.example.com", create(endpointHost = "vpn.example.com\u3002.").endpointHostWireForm)
        // A second real root dot is refused even when the host also carries a non-ASCII separator.
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_ENDPOINT_HOST,
            failureOf { create(endpointHost = "vpn\u3002a..") },
        )
        assertEquals("vpn.a", create(endpointHost = "vpn\u3002a.").endpointHostWireForm)
    }

    @Test
    fun `the wire form uses the pinned non-transitional UTS46 policy, not IDNA2003`() {
        // java.net.IDN.toASCII("fa\u00df.de") yields "fass.de"; the pinned UTS46 policy keeps
        // the sharp s and yields the punycode label, matching the foundation's IDNA fixtures.
        val config = create(endpointHost = "fa\u00df.de")
        assertEquals("xn--fa-hia.de", config.endpointHostWireForm)
    }

    @Test
    fun `bracketed and scoped IPv6 spellings are refused`() {
        // InetAddress.getByName accepts these, but WireGuard/inet_pton syntax does not.
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_ALLOWED_IPS,
            failureOf { create(allowedIPs = listOf("[2001:db8::]/64")) },
        )
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_DNS_ADDRESS,
            failureOf { create(dns = listOf("[2001:db8::1]")) },
        )
        assertEquals(
            ChainedConfigurationFailure.MALFORMED_DNS_ADDRESS,
            failureOf { create(dns = listOf("fe80::1%eth0")) },
        )
        // The bare literal remains accepted.
        assertTrue(ChainedUpstreamConfigurationValidator.isPlausiblePrefix("2001:db8::/64"))
        assertTrue(ChainedUpstreamConfigurationValidator.isIPLiteral("2001:db8::1"))
        // An IPv4-mapped literal parses as Inet4Address but is still valid IPv6 syntax.
        assertTrue(ChainedUpstreamConfigurationValidator.isPlausiblePrefix("::ffff:192.0.2.1/128"))
        assertTrue(ChainedUpstreamConfigurationValidator.isIPLiteral("::ffff:192.0.2.1"))
        // ...but its embedded dotted quad must use canonical octets.
        assertFalse(ChainedUpstreamConfigurationValidator.isIPLiteral("::ffff:192.168.001.001"))
        assertFalse(
            ChainedUpstreamConfigurationValidator.isPlausiblePrefix("::ffff:192.168.001.001/128"),
        )
        // Strict hextet widths: inet_pton rejects overlong/zero-padded groups.
        assertFalse(ChainedUpstreamConfigurationValidator.isIPLiteral("1:00000::1"))
        assertFalse(ChainedUpstreamConfigurationValidator.isIPLiteral("1:2:3:4:5:6:7"))
        assertFalse(ChainedUpstreamConfigurationValidator.isIPLiteral("1:2:3:4:5:6:7:8:9"))
        assertFalse(ChainedUpstreamConfigurationValidator.isIPLiteral(":::"))
        assertTrue(ChainedUpstreamConfigurationValidator.isIPLiteral("1:2:3:4:5:6:7:8"))
        assertTrue(ChainedUpstreamConfigurationValidator.isIPLiteral("::"))
        assertTrue(ChainedUpstreamConfigurationValidator.isIPLiteral("2001:DB8::1"))
    }

    @Test
    fun `the coverage helper recognises idioms and rejects gaps`() {
        assertTrue(ChainedUpstreamConfigurationValidator.coversIPv4DefaultRoute(listOf("0.0.0.0/0")))
        assertTrue(ChainedUpstreamConfigurationValidator.coversIPv4DefaultRoute(listOf("0.0.0.0/1", "128.0.0.0/1")))
        assertTrue(
            ChainedUpstreamConfigurationValidator.coversIPv4DefaultRoute(
                listOf("128.0.0.0/1", "0.0.0.0/2", "64.0.0.0/2"),
            ),
        )
        assertFalse(ChainedUpstreamConfigurationValidator.coversIPv4DefaultRoute(listOf("10.0.0.0/8")))
        assertFalse(
            ChainedUpstreamConfigurationValidator.coversIPv4DefaultRoute(
                listOf("0.0.0.0/1", "192.0.0.0/2"),
            ),
        )
    }

    @Test
    fun `the ipv4 block masks host bits off the network`() {
        assertEquals(ChainedUpstreamConfigurationValidator.ipv4Block("10.0.0.0/8"), 0x0A000000L to 0x0AFFFFFFL)
        assertEquals(ChainedUpstreamConfigurationValidator.ipv4Block("10.1.2.3/8"), 0x0A000000L to 0x0AFFFFFFL)
        assertEquals(ChainedUpstreamConfigurationValidator.ipv4Block("0.0.0.0/0"), 0L to 0xFFFFFFFFL)
        assertEquals(ChainedUpstreamConfigurationValidator.ipv4Block("203.0.113.7/32"), 0xCB007107L to 0xCB007107L)
        assertNull(ChainedUpstreamConfigurationValidator.ipv4Block("::/0"))
    }
}
