package com.lavasec.app.foundation.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
class ChainedUpstreamConfParserTest {

    private fun key(fill: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })

    private fun refusalOf(block: () -> Unit): ChainedConfRefusal =
        assertThrows(ChainedConfException::class.java) { block() }.refusal

    private fun conf(
        interfaceExtra: String = "",
        peerExtra: String = "",
        privateKey: String = key(0x11),
        peerKey: String = key(0x22),
        address: String = "10.7.0.2/32",
        dns: String = "DNS = 10.64.0.1",
        allowedIPs: String = "AllowedIPs = 0.0.0.0/0",
        endpoint: String = "Endpoint = vpn.example.com:51820",
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = $address")
        if (dns.isNotEmpty()) appendLine(dns)
        if (interfaceExtra.isNotEmpty()) appendLine(interfaceExtra)
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $peerKey")
        appendLine(allowedIPs)
        appendLine(endpoint)
        if (peerExtra.isNotEmpty()) appendLine(peerExtra)
    }

    @Test
    fun `a valid full-tunnel conf parses to a rotation`() {
        val rotation = ChainedUpstreamConfParser.rotation(conf())
        assertEquals(ChainedRoutingPolicy.FULL_TUNNEL, rotation.configuration.routingPolicy)
        assertEquals("vpn.example.com", rotation.configuration.endpointHost)
        assertEquals(51820, rotation.configuration.endpointPort)
        assertEquals(listOf("10.64.0.1"), rotation.configuration.dnsAddresses)
        assertFalse(rotation.hasPresharedKey)
        assertEquals(32, rotation.decodedPrivateKey().size)
        assertTrue(rotation.toString().contains("<redacted>"))
    }

    @Test
    fun `a preshared key is carried when present`() {
        val rotation = ChainedUpstreamConfParser.rotation(conf(peerExtra = "PresharedKey = ${key(0x33)}"))
        assertTrue(rotation.hasPresharedKey)
        assertEquals(32, rotation.decodedPresharedKey()!!.size)
    }

    @Test
    fun `CRLF line endings and comments are handled`() {
        val text = conf() + "\n# a trailing comment\n"
        val rotation = ChainedUpstreamConfParser.rotation(text.replace("\n", "\r\n"))
        assertEquals("vpn.example.com", rotation.configuration.endpointHost)
    }

    @Test
    fun `a repeated section is refused rather than merged`() {
        val text = conf() + "[Peer]\nPublicKey = ${key(0x44)}\n"
        assertEquals(
            ChainedConfRefusal.UnsupportedDirective("[peer]"),
            refusalOf { ChainedUpstreamConfParser.rotation(text) },
        )
    }

    @Test
    fun `a supported directive in the wrong section is refused`() {
        assertEquals(
            ChainedConfRefusal.DirectiveInWrongSection("dns", "[Peer]"),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(peerExtra = "DNS = 1.1.1.1")) },
        )
        assertEquals(
            ChainedConfRefusal.DirectiveInWrongSection("persistentkeepalive", "[Interface]"),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(interfaceExtra = "PersistentKeepalive = 10")) },
        )
    }

    @Test
    fun `behaviour-changing and AmneziaWG directives are refused by name`() {
        for (directive in listOf("Table = off", "PostUp = echo", "ListenPort = 51820", "Jc = 4", "IncludedApplications = com.foo")) {
            val refusal = refusalOf { ChainedUpstreamConfParser.rotation(conf(interfaceExtra = directive)) }
            assertTrue("expected $directive to be refused, got $refusal", refusal is ChainedConfRefusal.UnsupportedDirective)
        }
    }

    @Test
    fun `an unrecognised key or section is refused rather than silently ignored`() {
        assertEquals(
            ChainedConfRefusal.UnsupportedDirective("persistantkeepalive"),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(peerExtra = "PersistantKeepalive = 25")) },
        )
        assertEquals(
            ChainedConfRefusal.UnsupportedDirective("[peer2]"),
            refusalOf { ChainedUpstreamConfParser.rotation(conf() + "[Peer2]\nAllowedIPs = 10.0.0.0/8\n") },
        )
    }

    @Test
    fun `a non-empty line without an equals sign is refused`() {
        assertEquals(
            ChainedConfRefusal.MalformedLine,
            refusalOf { ChainedUpstreamConfParser.rotation(conf(interfaceExtra = "MTU 1400")) },
        )
    }

    @Test
    fun `an assignment before the first section is refused`() {
        assertEquals(
            ChainedConfRefusal.DirectiveOutsideSection("persistantkeepalive"),
            refusalOf { ChainedUpstreamConfParser.rotation("PersistantKeepalive = 25\n" + conf()) },
        )
    }

    @Test
    fun `a leading BOM does not hide the first section header`() {
        val rotation = ChainedUpstreamConfParser.rotation("\uFEFF" + conf())
        assertEquals("vpn.example.com", rotation.configuration.endpointHost)
    }

    @Test
    fun `FwMark is refused on Android because BoringTun compiles set_fwmark`() {
        assertEquals(
            ChainedConfRefusal.UnsupportedDirective("fwmark"),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(interfaceExtra = "FwMark = 123")) },
        )
    }

    @Test
    fun `a missing or repeated required key is refused`() {
        val missing = conf().replace("PrivateKey = ${key(0x11)}\n", "")
        assertEquals(
            ChainedConfRefusal.MissingOrRepeatedKey("PrivateKey"),
            refusalOf { ChainedUpstreamConfParser.rotation(missing) },
        )
        val repeatedEndpoint = conf(peerExtra = "Endpoint = other.example.com:51820")
        assertEquals(
            ChainedConfRefusal.MissingOrRepeatedKey("Endpoint"),
            refusalOf { ChainedUpstreamConfParser.rotation(repeatedEndpoint) },
        )
    }

    @Test
    fun `the endpoint is split and its brackets are stripped`() {
        assertEquals(
            ChainedConfRefusal.MalformedValue("Endpoint"),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(endpoint = "Endpoint = vpn.example.com")) },
        )
        assertEquals(
            ChainedConfRefusal.MalformedValue("Endpoint"),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(endpoint = "Endpoint = vpn.example.com:0")) },
        )
        // A bracketed IPv6 literal reaches the configuration, which refuses IPv6 endpoint hosts.
        assertEquals(
            ChainedConfRefusal.ConfigurationRefused(ChainedConfigurationFailure.MALFORMED_ENDPOINT_HOST),
            refusalOf {
                ChainedUpstreamConfParser.rotation(conf(endpoint = "Endpoint = [2001:db8::1]:51820"))
            },
        )
    }

    @Test
    fun `a dual-stack Address keeps its single IPv4 entry and drops the prefix`() {
        val rotation = ChainedUpstreamConfParser.rotation(
            conf(address = "10.68.1.2/32,fc00:bbbb:bbbb:bb01::5:c1a8/128"),
        )
        assertEquals("10.68.1.2", rotation.configuration.clientAddress)
        assertEquals("10.7.0.2", ChainedUpstreamConfParser.rotation(conf(address = "10.7.0.2/24")).configuration.clientAddress)
    }

    @Test
    fun `a malformed Address is refused`() {
        for (address in listOf("10.7.0.2/08", "10.7.0.2/33", "10.7.0.2 /32", "fc00::1/128", "10.7.0.2/32/junk", "10.7.0.2//", "10.7.0.2/")) {
            assertEquals(
                "expected '$address' to be refused",
                ChainedConfRefusal.MalformedValue("Address"),
                refusalOf { ChainedUpstreamConfParser.rotation(conf(address = address)) },
            )
        }
    }

    @Test
    fun `an empty resolver list and an unreadable MTU are refused`() {
        assertEquals(
            ChainedConfRefusal.MalformedValue("DNS"),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(dns = "DNS = ,")) },
        )
        assertEquals(
            ChainedConfRefusal.MalformedValue("MTU"),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(interfaceExtra = "MTU = 70000")) },
        )
        assertEquals(
            ChainedConfRefusal.ConfigurationRefused(ChainedConfigurationFailure.MTU_BELOW_IPV6_FLOOR),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(interfaceExtra = "MTU = 1279")) },
        )
    }

    @Test
    fun `an IPv6-only upstream and a too-frequent keepalive reach the configuration refusal`() {
        assertEquals(
            ChainedConfRefusal.ConfigurationRefused(ChainedConfigurationFailure.IPV6_ONLY_UPSTREAM_UNSUPPORTED),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(allowedIPs = "AllowedIPs = ::/0")) },
        )
        assertEquals(
            ChainedConfRefusal.ConfigurationRefused(ChainedConfigurationFailure.KEEPALIVE_TOO_FREQUENT),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(peerExtra = "PersistentKeepalive = 1")) },
        )
    }

    @Test
    fun `the rotation boundary refuses malformed, all-zero, and peer private keys`() {
        assertEquals(
            ChainedConfRefusal.RotationRefused(ChainedRotationRefusal.MALFORMED_KEY),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(privateKey = "not base64")) },
        )
        assertEquals(
            ChainedConfRefusal.RotationRefused(ChainedRotationRefusal.ALL_ZERO_PRIVATE_KEY),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(privateKey = key(0x00))) },
        )
        assertEquals(
            ChainedConfRefusal.RotationRefused(ChainedRotationRefusal.PEER_KEY_IN_INTERFACE),
            refusalOf {
                ChainedUpstreamConfParser.rotation(conf(privateKey = key(0x22), peerKey = key(0x22)))
            },
        )
        assertEquals(
            ChainedConfRefusal.MalformedValue("PresharedKey"),
            refusalOf { ChainedUpstreamConfParser.rotation(conf(peerExtra = "PresharedKey = nope")) },
        )
    }
}
