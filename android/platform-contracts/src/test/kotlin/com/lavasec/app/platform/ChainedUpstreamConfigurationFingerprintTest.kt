package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the canonical non-secret chained-upstream fingerprint. */
class ChainedUpstreamConfigurationFingerprintTest {

    private fun peerKey(fill: Int = 0x42): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })

    private fun create(
        endpointHost: String = "vpn.example.com",
        endpointPort: Int = 51820,
        allowedIPs: List<String> = listOf("0.0.0.0/0"),
    ): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
        endpointHost = endpointHost,
        endpointPort = endpointPort,
        peerPublicKey = peerKey(),
        clientAddress = "10.7.0.2",
        allowedIPs = allowedIPs,
        persistentKeepaliveSeconds = 0,
        interfaceMTU = null,
        dnsAddresses = listOf("10.64.0.1"),
    )

    @Test
    fun `the fingerprint is a canonical sha256 and is deterministic`() {
        val configuration = create()

        val first = canonicalChainedUpstreamConfigurationFingerprint(configuration)
        val second = canonicalChainedUpstreamConfigurationFingerprint(configuration)

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `a behavior-bearing field change changes the fingerprint`() {
        assertNotEquals(
            canonicalChainedUpstreamConfigurationFingerprint(create()),
            canonicalChainedUpstreamConfigurationFingerprint(create(endpointPort = 51821)),
        )
        assertNotEquals(
            canonicalChainedUpstreamConfigurationFingerprint(create()),
            canonicalChainedUpstreamConfigurationFingerprint(create(allowedIPs = listOf("10.0.0.0/8"))),
        )
    }

    @Test
    fun `the endpoint host is committed in its canonical wire form`() {
        val unicode = create(endpointHost = "fa\u00df.de")
        val aLabel = create(endpointHost = "xn--fa-hia.de")

        assertEquals(
            canonicalChainedUpstreamConfigurationFingerprint(unicode),
            canonicalChainedUpstreamConfigurationFingerprint(aLabel),
        )
    }
}
