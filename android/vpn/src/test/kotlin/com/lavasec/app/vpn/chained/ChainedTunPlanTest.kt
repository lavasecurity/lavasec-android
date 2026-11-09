package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedDnsCaptureFloor
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.foundation.chained.ChainedRoutePlanCollision
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.vpn.DnsOnlyTunContract
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mode-specific route-plan resolve. Pure: no Android types, so it runs in the host JVM.
 */
class ChainedTunPlanTest {

    @Test
    fun `no configuration is the unchanged DNS-only plan`() {
        val ready = ChainedTunPlan.resolve(null) as ChainedTunPlanResolution.Ready

        assertEquals(ChainedRoutePlan.dnsOnly(), ready.plan)
        assertEquals(DnsOnlyTunContract.CLIENT_ADDRESS, ready.plan.tunnelAddress)
        assertEquals(DnsOnlyTunContract.HOST_PREFIX_LENGTH, ready.plan.tunnelPrefixLength)
        assertEquals(DnsOnlyTunContract.DNS_ADDRESS, ready.plan.dnsServerAddress)
        assertEquals(1, ready.plan.includedIPv4Routes.size)
        assertEquals(
            "${DnsOnlyTunContract.DNS_ADDRESS}/${DnsOnlyTunContract.HOST_PREFIX_LENGTH}",
            ready.plan.includedIPv4Routes.single().cidr,
        )
        assertEquals(null, ready.plan.tunnelIPv6Address)
        assertTrue(ready.plan.includedIPv6Routes.isEmpty())
        assertEquals(DnsOnlyTunContract.MTU, ready.plan.mtu)
        assertFalse(ready.plan.claimsDefaultRoute)
        assertFalse(ready.plan.carriesIPv6)
    }

    @Test
    fun `full tunnel claims both default routes and the configured address`() {
        val plan = resolvePlan(fullTunnel())

        assertTrue(plan.claimsDefaultRoute)
        assertTrue(plan.claimsIPv6DefaultRoute)
        assertFalse(plan.leaksIPv6AroundTheTunnel)
        assertEquals("10.7.0.2", plan.tunnelAddress)
        assertEquals(listOf("0.0.0.0/0"), plan.includedIPv4Routes.map { it.cidr })
        assertEquals(listOf("::/0"), plan.includedIPv6Routes.map { it.cidr })
        assertEquals(DnsOnlyTunContract.DNS_ADDRESS, plan.dnsServerAddress)
    }

    @Test
    fun `full tunnel honors the configured interface MTU`() {
        val plan = resolvePlan(
            configuration(allowedIPs = listOf("0.0.0.0/0"), mtu = 1420),
        )

        assertEquals(1420, plan.mtu)
    }

    @Test
    fun `split tunnel claims the allowed prefixes plus the DNS capture route`() {
        val plan = resolvePlan(
            configuration(allowedIPs = listOf("192.168.0.0/16", "172.16.0.0/12")),
        )

        assertEquals(
            listOf("192.168.0.0/16", "172.16.0.0/12", "${DnsOnlyTunContract.DNS_ADDRESS}/32") + curatedRoutes(),
            plan.includedIPv4Routes.map { it.cidr },
        )
        assertFalse(plan.claimsDefaultRoute)
        assertTrue(plan.carriesIPv6)
        assertFalse(plan.claimsIPv6DefaultRoute)
        assertEquals("10.7.0.2", plan.tunnelAddress)
    }

    @Test
    fun `a host allowed prefix is masked to its network in the plan`() {
        val plan = resolvePlan(configuration(allowedIPs = listOf("192.168.1.5/16")))

        assertEquals(
            listOf("192.168.0.0/16", "${DnsOnlyTunContract.DNS_ADDRESS}/32") + curatedRoutes(),
            plan.includedIPv4Routes.map { it.cidr },
        )
    }

    @Test
    fun `a split config exceeding the DNS capture route is rejected before install`() {
        val rejected = ChainedTunPlan.resolve(
            configuration(allowedIPs = listOf(DnsOnlyTunContract.DNS_ADDRESS + "/32")),
        ) as ChainedTunPlanResolution.Rejected

        assertTrue(
            ChainedRoutePlanCollision.ALLOWED_IPS_OVERLAPS_DNS_CAPTURE in rejected.collisions,
        )
    }

    @Test
    fun `a split allowed prefix equal to the client host route is rejected`() {
        val rejected = ChainedTunPlan.resolve(
            configuration(allowedIPs = listOf("10.7.0.2/32")),
        ) as ChainedTunPlanResolution.Rejected

        assertTrue(
            ChainedRoutePlanCollision.ALLOWED_IPS_COVERS_CLIENT_ADDRESS in rejected.collisions,
        )
    }

    @Test
    fun `a full tunnel is not run through the split collision guard`() {
        // 0.0.0.0/0 contains both the DNS proxy and the client address; that is the mode's claim,
        // not a collision.
        val ready = ChainedTunPlan.resolve(fullTunnel())
        assertTrue(ready is ChainedTunPlanResolution.Ready)
    }

    @Test
    fun `only split mode merges curated and eligible captured DNS as exact host routes`() {
        val captured = mutableListOf("4.2.2.2", "8.8.8.8", "192.168.1.1", "2606:4700:4700::1111")
        val split = (ChainedTunPlan.resolve(
            configuration(allowedIPs = listOf("10.64.0.0/16")), captured,
        ) as ChainedTunPlanResolution.Ready).plan
        captured.clear()
        assertEquals(curatedRoutes() + "4.2.2.2/32", split.dnsCaptureFloor.ipv4Routes.map { it.cidr })
        assertTrue(split.includedIPv4Routes.containsAll(split.dnsCaptureFloor.ipv4Routes))
        assertTrue(split.carriesIPv6)
        assertTrue(split.includedIPv6Routes.containsAll(split.dnsCaptureFloor.ipv6Routes))
        assertFalse(split.claimsIPv6DefaultRoute)
        assertEquals(ChainedRoutePlan.dnsOnly(), (ChainedTunPlan.resolve(null, listOf("4.2.2.2")) as ChainedTunPlanResolution.Ready).plan)
        assertEquals(resolvePlan(fullTunnel()), (ChainedTunPlan.resolve(fullTunnel(), listOf("4.2.2.2")) as ChainedTunPlanResolution.Ready).plan)
    }

    private fun curatedRoutes(): List<String> = ChainedDnsCaptureFloor.curatedIPv4ResolverAddresses.map { "$it/32" }

    private fun resolvePlan(configuration: ChainedUpstreamConfiguration): ChainedRoutePlan =
        (ChainedTunPlan.resolve(configuration) as ChainedTunPlanResolution.Ready).plan

    private fun fullTunnel(): ChainedUpstreamConfiguration =
        configuration(allowedIPs = listOf("0.0.0.0/0"))

    private fun configuration(
        allowedIPs: List<String>,
        clientAddress: String = "10.7.0.2",
        mtu: Int? = null,
    ): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
        endpointHost = "vpn.example.com",
        endpointPort = 51820,
        peerPublicKey = key(),
        clientAddress = clientAddress,
        allowedIPs = allowedIPs,
        persistentKeepaliveSeconds = 0,
        interfaceMTU = mtu,
        dnsAddresses = listOf("10.64.0.1"),
    )

    private fun key(): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 })
}
