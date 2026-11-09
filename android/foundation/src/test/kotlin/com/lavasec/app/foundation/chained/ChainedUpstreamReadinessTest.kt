package com.lavasec.app.foundation.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ChainedUpstreamReadinessTest {

    private fun key(fill: Int = 0x42): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })

    private fun create(
        allowedIPs: List<String> = listOf("0.0.0.0/0"),
        dns: List<String> = listOf("10.64.0.1"),
        clientAddress: String = "10.7.0.2",
    ): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
        endpointHost = "vpn.example.com",
        endpointPort = 51820,
        peerPublicKey = key(),
        clientAddress = clientAddress,
        allowedIPs = allowedIPs,
        dnsAddresses = dns,
    )

    private fun reasonOf(readiness: ChainedReadiness): ChainedReadinessRefusal {
        assertTrue("expected not-ready but was $readiness", readiness is ChainedReadiness.NotReady)
        return (readiness as ChainedReadiness.NotReady).reason
    }

    private fun assertNotReady(
        expected: ChainedReadinessRefusal,
        configuration: ChainedUpstreamConfiguration? = create(),
        hasEntitlement: Boolean = true,
        deviceEligible: Boolean = true,
        userEnabled: Boolean = true,
    ) {
        val readiness = ChainedReadiness.evaluate(
            configuration = configuration,
            hasEntitlement = hasEntitlement,
            deviceEligible = deviceEligible,
            userEnabled = userEnabled,
        )
        assertEquals(expected, reasonOf(readiness))
    }

    @Test
    fun `a null configuration is not ready`() {
        assertNotReady(ChainedReadinessRefusal.NO_CONFIGURATION, configuration = null)
    }

    @Test
    fun `an empty DNS list is not ready`() {
        assertNotReady(
            ChainedReadinessRefusal.NO_USABLE_TUNNEL_DNS,
            configuration = create(dns = emptyList()),
        )
    }

    @Test
    fun `DNS containing only the proxy, loopback and unspecified is not ready`() {
        assertNotReady(
            ChainedReadinessRefusal.NO_USABLE_TUNNEL_DNS,
            configuration = create(
                dns = listOf("10.111.0.1", "127.0.0.1", "0.0.0.0"),
            ),
        )
    }

    @Test
    fun `an IPv6-only DNS list is not usable as chained egress`() {
        assertNotReady(
            ChainedReadinessRefusal.NO_USABLE_TUNNEL_DNS,
            configuration = create(dns = listOf("2606:4700:4700::1111")),
        )
    }

    @Test
    fun `the configuration's own client address is local delivery, not egress`() {
        assertNotReady(
            ChainedReadinessRefusal.NO_USABLE_TUNNEL_DNS,
            configuration = create(dns = listOf("10.7.0.2"), clientAddress = "10.7.0.2"),
        )
    }

    @Test
    fun `one usable literal among unusable ones is ready`() {
        val readiness = ChainedReadiness.evaluate(
            configuration = create(dns = listOf("10.111.0.1", "0.0.0.0", "10.64.0.1")),
            hasEntitlement = true,
            deviceEligible = true,
            userEnabled = true,
        )
        assertEquals(ChainedReadiness.Ready, readiness)
        assertTrue(readiness.isReady)
    }

    @Test
    fun `a missing entitlement is not ready`() {
        assertNotReady(ChainedReadinessRefusal.NOT_ENTITLED, hasEntitlement = false)
    }

    @Test
    fun `an ineligible device is not ready`() {
        assertNotReady(ChainedReadinessRefusal.DEVICE_INELIGIBLE, deviceEligible = false)
    }

    @Test
    fun `a user-disabled preference is not ready`() {
        assertNotReady(ChainedReadinessRefusal.DISABLED_BY_USER, userEnabled = false)
    }

    @Test
    fun `the entitlement gate outranks a missing configuration`() {
        assertNotReady(
            ChainedReadinessRefusal.NOT_ENTITLED,
            configuration = null,
            hasEntitlement = false,
        )
    }

    @Test
    fun `the device gate outranks a missing configuration`() {
        assertNotReady(
            ChainedReadinessRefusal.DEVICE_INELIGIBLE,
            configuration = null,
            deviceEligible = false,
        )
    }

    @Test
    fun `the user's own switch outranks a missing configuration`() {
        assertNotReady(
            ChainedReadinessRefusal.DISABLED_BY_USER,
            configuration = null,
            userEnabled = false,
        )
    }

    @Test
    fun `a full-tunnel configuration is ready`() {
        val readiness = ChainedReadiness.evaluate(
            configuration = create(allowedIPs = listOf("0.0.0.0/0")),
            hasEntitlement = true,
            deviceEligible = true,
            userEnabled = true,
        )
        assertEquals(ChainedReadiness.Ready, readiness)
        assertEquals("chained-ready", readiness.logValue)
    }

    @Test
    fun `a split-tunnel configuration is ready when its resolver is routed`() {
        val readiness = ChainedReadiness.evaluate(
            configuration = create(
                allowedIPs = listOf("192.168.0.0/16", "10.64.0.0/16"),
                dns = listOf("10.64.0.1"),
            ),
            hasEntitlement = true,
            deviceEligible = true,
            userEnabled = true,
        )
        assertEquals(ChainedReadiness.Ready, readiness)
    }

    @Test
    fun `a split resolver outside AllowedIPs is refused`() {
        assertNotReady(
            ChainedReadinessRefusal.TUNNEL_DNS_NOT_ROUTED,
            configuration = create(
                allowedIPs = listOf("192.168.0.0/16"),
                dns = listOf("10.64.0.1"),
            ),
        )
    }

    @Test
    fun `a split AllowedIPs set colliding with the proxy or client is refused`() {
        // 10.111.0.1 (the proxy) is inside 10.0.0.0/8.
        assertNotReady(
            ChainedReadinessRefusal.ROUTES_COLLIDE,
            configuration = create(allowedIPs = listOf("10.0.0.0/8"), dns = listOf("10.64.0.1")),
        )
    }

    @Test
    fun `an IPv4 inner route is required and is judged defensively`() {
        // Unreachable through `create` (the boundary refuses a no-IPv4 AllowedIPs set), so the
        // helper is exercised over its raw input to pin the defensive branch.
        assertTrue(ChainedReadiness.hasUsableIpv4Route(listOf("0.0.0.0/0")))
        assertTrue(ChainedReadiness.hasUsableIpv4Route(listOf("10.0.0.0/8", "::/0")))
        assertFalse(ChainedReadiness.hasUsableIpv4Route(listOf("::/0")))
        assertFalse(ChainedReadiness.hasUsableIpv4Route(emptyList()))
    }

    @Test
    fun `malformed DNS entries surface the malformed reason, not absence`() {
        assertEquals(
            ChainedReadinessRefusal.MALFORMED_TUNNEL_DNS,
            ChainedReadiness.tunnelDnsRefusal(listOf("not-an-ip"), "10.7.0.2", listOf("0.0.0.0/0")),
        )
        assertEquals(
            ChainedReadinessRefusal.MALFORMED_TUNNEL_DNS,
            ChainedReadiness.tunnelDnsRefusal(
                listOf("10.64.0.1", "vpn.example.com"),
                "10.7.0.2",
                listOf("0.0.0.0/0"),
            ),
        )
    }

    @Test
    fun `the refusal carries a stable log identifier`() {
        assertEquals(
            "chained-not-ready-no-usable-tunnel-dns",
            ChainedReadinessRefusal.NO_USABLE_TUNNEL_DNS.logValue,
        )
        assertEquals("chained-not-ready-disabled-by-user", ChainedReadinessRefusal.DISABLED_BY_USER.logValue)
        assertEquals(
            "chained-not-ready-no-usable-ipv4-route",
            ChainedReadinessRefusal.NO_USABLE_IPV4_ROUTE.logValue,
        )
    }

    @Test
    fun `the DNS proxy constant matches the Android tunnel contract`() {
        assertEquals("10.111.0.1", ChainedReadiness.TUNNEL_DNS_PROXY_ADDRESS)
        assertFalse(ChainedReadiness.isUsableTunnelResolver("10.111.0.1", "10.7.0.2"))
    }

    @Test
    fun `usability admits private and public unicast and refuses local-delivery addresses`() {
        assertTrue(ChainedReadiness.isUsableTunnelResolver("10.64.0.1", "10.7.0.2"))
        assertTrue(ChainedReadiness.isUsableTunnelResolver("1.1.1.1", "10.7.0.2"))
        assertTrue(ChainedReadiness.isUsableTunnelResolver("192.168.1.1", "10.7.0.2"))
        assertFalse(ChainedReadiness.isUsableTunnelResolver("127.0.0.1", "10.7.0.2"))
        assertFalse(ChainedReadiness.isUsableTunnelResolver("0.0.0.0", "10.7.0.2"))
        assertFalse(ChainedReadiness.isUsableTunnelResolver("169.254.1.1", "10.7.0.2"))
        assertFalse(ChainedReadiness.isUsableTunnelResolver("224.0.0.1", "10.7.0.2"))
        assertFalse(ChainedReadiness.isUsableTunnelResolver("255.255.255.255", "10.7.0.2"))
    }
}
