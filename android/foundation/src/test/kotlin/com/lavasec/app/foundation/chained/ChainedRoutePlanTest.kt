package com.lavasec.app.foundation.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ChainedRoutePlanTest {

    private fun key(fill: Int = 0x42): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })

    /** A validated upstream; the default `allowedIPs` derives a full tunnel. */
    private fun configuration(
        clientAddress: String = "10.64.0.5",
        allowedIPs: List<String> = listOf("0.0.0.0/0"),
        mtu: Int? = null,
    ): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
        endpointHost = "vpn.example.com",
        endpointPort = 51820,
        peerPublicKey = key(),
        clientAddress = clientAddress,
        allowedIPs = allowedIPs,
        interfaceMTU = mtu,
    )

    @Test
    fun `an installed split plan owns exact floor routes while other modes ignore floor`() {
        val floor = ChainedDnsCaptureFloor.forCapturedResolvers(listOf("4.2.2.2"))
        val split = ChainedRoutePlan.make(configuration(allowedIPs = listOf("10.64.0.0/16")), floor)
        assertEquals(floor, split.dnsCaptureFloor)
        assertTrue(split.includedIPv4Routes.containsAll(floor.ipv4Routes))
        assertFalse(split.claimsDefaultRoute)
        assertTrue(split.includedIPv6Routes.containsAll(floor.ipv6Routes))
        assertFalse(split.claimsIPv6DefaultRoute)
        assertEquals(ChainedRoutePlan.dnsOnly(), ChainedRoutePlan.make(null, floor))
        assertEquals(ChainedRoutePlan.make(configuration()), ChainedRoutePlan.make(configuration(), floor))
        assertThrows(IllegalArgumentException::class.java) {
            ChainedRoutePlan.of(
                split.tunnelAddress, split.tunnelPrefixLength, split.dnsServerAddress,
                split.routeDescription, listOf(ChainedRoutePlan.DNS_PROXY_ROUTE),
                split.tunnelIPv6Address, split.tunnelIPv6PrefixLength, split.includedIPv6Routes,
                split.mtu, floor,
            )
        }
    }

    // MARK: - DNS-only / inactive

    @Test
    fun `ordered active routes union both splits and nested full entry keeps IPv6 closed`() {
        val base = configuration()
        val entry = ChainedUpstreamConfiguration.create("192.0.2.1", 51820, base.peerPublicKey, "10.1.0.2", listOf("10.8.0.0/16"), 0, 1420, listOf("10.8.0.1"))
        val exit = ChainedUpstreamConfiguration.create("192.0.2.2", 51820, base.peerPublicKey, "10.2.0.2", listOf("172.16.0.0/16"), 0, 1380, listOf("172.16.0.1"))
        val split = ChainedRoutePlan.make(exit.withEntryHop(entry))
        assertEquals(listOf("10.8.0.0/16", "172.16.0.0/16", "10.111.0.1/32"), split.includedIPv4Routes.map { it.cidr })
        assertEquals(1380, split.mtu)
        val fullEntry = ChainedUpstreamConfiguration.create("192.0.2.1", 51820, base.peerPublicKey, "10.1.0.2", listOf("0.0.0.0/0"), 0, 1420, listOf("10.8.0.1"))
        val nested = ChainedRoutePlan.make(exit.withEntryHop(fullEntry))
        assertTrue(nested.claimsDefaultRoute)
        assertTrue(nested.claimsIPv6DefaultRoute)
        assertEquals(1360, nested.mtu)
        assertEquals(ChainedRoutePlan.dnsOnly(), ChainedRoutePlan.make(exit.enabled(false).withEntryHop(entry.enabled(false))))
    }

    @Test
    fun `inactive claims only the DNS capture block and no IPv6`() {
        val plan = ChainedRoutePlan.make(null)

        assertEquals(ChainedRoutePlan.DNS_ONLY_TUNNEL_ADDRESS, plan.tunnelAddress)
        assertEquals(32, plan.tunnelPrefixLength)
        assertEquals("10.111.0.1", plan.dnsServerAddress)
        assertEquals("10.111.0.1/32 (DNS)", plan.routeDescription)
        assertEquals(1280, plan.mtu)
        assertEquals(listOf(ChainedRoutePlan.DNS_PROXY_ROUTE), plan.includedIPv4Routes)
        assertFalse(plan.claimsDefaultRoute)
        assertFalse(plan.carriesIPv6)
        assertNull(plan.tunnelIPv6Address)
        assertNull(plan.tunnelIPv6PrefixLength)
        assertEquals(emptyList<IPv6Route>(), plan.includedIPv6Routes)
        assertFalse(plan.claimsIPv6DefaultRoute)
        assertEquals(ChainedRoutePlan.dnsOnly(), plan)
    }

    // MARK: - Full tunnel

    @Test
    fun `full tunnel claims both default routes`() {
        val plan = ChainedRoutePlan.make(configuration())

        assertTrue(plan.claimsDefaultRoute)
        assertEquals(listOf(IPv4Route("0.0.0.0", 0)), plan.includedIPv4Routes)
        // The description is what the device log emits as the claimed route, so it must name
        // every family actually claimed, including the IPv6 blackhole.
        assertEquals("0.0.0.0/0, ::/0", plan.routeDescription)
        assertTrue(plan.claimsIPv6DefaultRoute)
        assertEquals(listOf(IPv6Route("::", 0)), plan.includedIPv6Routes)
        assertTrue(plan.carriesIPv6)
        assertFalse(plan.leaksIPv6AroundTheTunnel)
    }

    @Test
    fun `full tunnel numbers the interface with the configured address at a host mask`() {
        val dnsOnly = ChainedRoutePlan.make(null)
        val full = ChainedRoutePlan.make(configuration(clientAddress = "10.64.0.5"))

        assertEquals("10.64.0.5", full.tunnelAddress)
        assertNotEquals(dnsOnly.tunnelAddress, full.tunnelAddress)
        assertEquals(32, full.tunnelPrefixLength)
        // The DNS proxy does not move into the configured subnet: it is reached through the
        // default route, not on-link.
        assertEquals(dnsOnly.dnsServerAddress, full.dnsServerAddress)
    }

    @Test
    fun `full tunnel adds the ULA interface address needed before an IPv6 route installs`() {
        val plan = ChainedRoutePlan.make(configuration())

        assertEquals("fd00:1a7a::2", plan.tunnelIPv6Address)
        assertEquals(64, plan.tunnelIPv6PrefixLength)
        // A locally-assigned ULA (RFC 4193), never a routable address: IPv6 is claimed to be
        // dropped, not sourced from.
        assertTrue(plan.tunnelIPv6Address!!.startsWith("fd"))
    }

    @Test
    fun `the configured MTU reaches the full-tunnel plan and its absence yields the floor`() {
        assertEquals(1420, ChainedRoutePlan.make(configuration(mtu = 1420)).mtu)
        assertEquals(1280, ChainedRoutePlan.make(configuration()).mtu)
    }

    // MARK: - Split tunnel

    @Test
    fun `split tunnel claims the masked AllowedIPs plus the DNS proxy route`() {
        val config = configuration(
            clientAddress = "10.77.0.2",
            // The second entry carries host bits, to prove the plan masks to the network.
            allowedIPs = listOf("100.64.0.0/10", "10.1.2.3/8"),
        )
        assertEquals(ChainedRoutingPolicy.SPLIT_TUNNEL, config.routingPolicy)
        val plan = ChainedRoutePlan.make(config)

        assertEquals(
            listOf(
                IPv4Route("100.64.0.0", 10),
                IPv4Route("10.0.0.0", 8),
                ChainedRoutePlan.DNS_PROXY_ROUTE,
            ),
            plan.includedIPv4Routes,
        )
        assertEquals("10.77.0.2", plan.tunnelAddress)
        assertEquals(32, plan.tunnelPrefixLength)
        assertFalse(plan.claimsDefaultRoute)
        assertTrue(plan.carriesIPv6)
        assertEquals(ChainedRoutePlan.CHAINED_TUNNEL_IPV6_ADDRESS, plan.tunnelIPv6Address)
        assertEquals(ChainedRoutePlan.CHAINED_DNS_SERVER_IPV6_ADDRESS, plan.dnsServerIPv6Address)
        assertFalse(plan.claimsIPv6DefaultRoute)
        assertEquals(listOf(IPv6Route(ChainedRoutePlan.CHAINED_TUNNEL_IPV6_NETWORK, 64)), plan.includedIPv6Routes)
        // IPv6 outside the served DNS routes and non-AllowedIPs IPv4 still egress direct.
        assertFalse(plan.leaksIPv6AroundTheTunnel)
    }

    @Test
    fun `split keeps the DNS capture route even when a narrow set excludes the proxy`() {
        // No prefix here contains 10.111.0.1; the plan must add the proxy route itself or
        // system-resolver DNS would leave the filter.
        val plan = ChainedRoutePlan.make(
            configuration(clientAddress = "10.77.0.2", allowedIPs = listOf("192.168.1.0/24")),
        )

        assertTrue(plan.includedIPv4Routes.contains(ChainedRoutePlan.DNS_PROXY_ROUTE))
        assertTrue(
            "the DNS proxy must be covered by a claimed route",
            plan.includedIPv4Routes.any {
                it.destinationAddress == ChainedRoutePlan.DNS_SERVER_ADDRESS && it.prefixLength == 32
            },
        )
        // The narrow AllowedIPs prefix is still there, untouched.
        assertTrue(plan.includedIPv4Routes.contains(IPv4Route("192.168.1.0", 24)))
    }

    @Test
    fun `the DNS proxy route is a host route, not the whole capture block`() {
        val split = ChainedRoutePlan.make(
            configuration(clientAddress = "10.77.0.2", allowedIPs = listOf("192.168.1.0/24")),
        )
        // Split adds exactly the host route to the proxy, never the whole capture /24.
        assertTrue(split.includedIPv4Routes.contains(ChainedRoutePlan.DNS_PROXY_ROUTE))
        assertEquals(32, ChainedRoutePlan.DNS_PROXY_ROUTE.prefixLength)
        assertFalse(
            split.includedIPv4Routes.any { it.destinationAddress == "10.111.0.0" && it.prefixLength == 24 },
        )
    }

    @Test
    fun `the configured MTU reaches the split plan and its absence yields the floor`() {
        val splitRoutes = listOf("10.0.0.0/8")
        assertEquals(
            1400,
            ChainedRoutePlan.make(configuration(allowedIPs = splitRoutes, mtu = 1400)).mtu,
        )
        assertEquals(1280, ChainedRoutePlan.make(configuration(allowedIPs = splitRoutes)).mtu)
    }

    // MARK: - Coverage

    @Test
    fun `the two halves idiom is a full tunnel via the coverage sweep`() {
        val halves = listOf("0.0.0.0/1", "128.0.0.0/1")
        assertTrue(ChainedRoutePlan.coversDefaultRoute(halves))
        val config = configuration(allowedIPs = halves)
        assertEquals(ChainedRoutingPolicy.FULL_TUNNEL, config.routingPolicy)
        // Coverage, not a literal match: the plan collapses the pair to the default route.
        assertTrue(ChainedRoutePlan.make(config).claimsDefaultRoute)
        assertEquals(listOf(IPv4Route("0.0.0.0", 0)), ChainedRoutePlan.make(config).includedIPv4Routes)
    }

    @Test
    fun `the coverage sweep recognises idioms and rejects gaps`() {
        assertTrue(ChainedRoutePlan.coversDefaultRoute(listOf("0.0.0.0/0")))
        assertTrue(
            ChainedRoutePlan.coversDefaultRoute(
                listOf("128.0.0.0/1", "0.0.0.0/2", "64.0.0.0/2"),
            ),
        )
        assertFalse(ChainedRoutePlan.coversDefaultRoute(listOf("10.0.0.0/8")))
        assertFalse(
            ChainedRoutePlan.coversDefaultRoute(listOf("0.0.0.0/1", "192.0.0.0/2")),
        )
        // IPv6 entries are ignored: they cannot contribute to covering the IPv4 default route.
        assertFalse(ChainedRoutePlan.coversDefaultRoute(listOf("::/0")))
    }

    @Test
    fun `the prefix helper masks host bits off the network`() {
        assertEquals(IPv4Route("10.0.0.0", 8), ChainedRoutePlan.ipv4RouteFromPrefix("10.1.2.3/8"))
        assertEquals(IPv4Route("203.0.113.7", 32), ChainedRoutePlan.ipv4RouteFromPrefix("203.0.113.7/32"))
        assertEquals(IPv4Route("0.0.0.0", 0), ChainedRoutePlan.ipv4RouteFromPrefix("0.0.0.0/0"))
        // Anything that is not an IPv4 prefix is dropped rather than mis-parsed.
        assertNull(ChainedRoutePlan.ipv4RouteFromPrefix("::/0"))
        assertNull(ChainedRoutePlan.ipv4RouteFromPrefix("10.0.0.0"))
        assertNull(ChainedRoutePlan.ipv4RouteFromPrefix("10.0.0.0/33"))
    }

    // MARK: - Collision guards

    @Test
    fun `an AllowedIPs prefix overlapping the DNS capture block is flagged`() {
        assertTrue(ChainedRoutePlan.overlapsDnsCapture("10.111.0.0/24"))
        assertTrue(ChainedRoutePlan.overlapsDnsCapture("10.111.0.1/32"))
        // A wider prefix that contains the capture block overlaps it.
        assertTrue(ChainedRoutePlan.overlapsDnsCapture("10.0.0.0/8"))
        assertTrue(ChainedRoutePlan.overlapsDnsCapture("0.0.0.0/0"))
        assertFalse(ChainedRoutePlan.overlapsDnsCapture("10.111.1.0/24"))
        assertFalse(ChainedRoutePlan.overlapsDnsCapture("192.168.0.0/16"))
        assertFalse(ChainedRoutePlan.overlapsDnsCapture("::/0"))
    }

    @Test
    fun `an AllowedIPs prefix disjoint from the installed proxy host is not flagged`() {
        // Only 10.111.0.1/32 is installed, so a prefix that excludes it is a valid remote network.
        assertFalse(ChainedRoutePlan.overlapsDnsCapture("10.111.0.128/25"))
        assertTrue(ChainedRoutePlan.collisions(listOf("10.111.0.128/25"), "10.77.0.2").isEmpty())
    }

    @Test
    fun `the two halves idiom counts as claiming the default route`() {
        val plan = ChainedRoutePlan.of(
            tunnelAddress = "10.7.0.2",
            tunnelPrefixLength = 32,
            dnsServerAddress = ChainedRoutePlan.DNS_SERVER_ADDRESS,
            routeDescription = "0.0.0.0/1, 128.0.0.0/1",
            includedIPv4Routes = listOf(IPv4Route("0.0.0.0", 1), IPv4Route("128.0.0.0", 1)),
            tunnelIPv6Address = null,
            tunnelIPv6PrefixLength = null,
            includedIPv6Routes = emptyList(),
            mtu = 1280,
        )
        assertTrue(plan.claimsDefaultRoute)
        // ...and without an IPv6 default route that is a full-tunnel IPv6 leak.
        assertTrue(plan.leaksIPv6AroundTheTunnel)
    }

    @Test
    fun `the IPv6 two halves idiom counts as claiming the IPv6 default route`() {
        val plan = ChainedRoutePlan.of(
            tunnelAddress = "10.7.0.2",
            tunnelPrefixLength = 32,
            dnsServerAddress = ChainedRoutePlan.DNS_SERVER_ADDRESS,
            routeDescription = "0.0.0.0/0, ::/1, 8000::/1",
            includedIPv4Routes = listOf(IPv4Route("0.0.0.0", 0)),
            tunnelIPv6Address = "fd00:1a7a::2",
            tunnelIPv6PrefixLength = 64,
            includedIPv6Routes = listOf(IPv6Route("::", 1), IPv6Route("8000::", 1)),
            mtu = 1280,
        )
        assertTrue(plan.claimsIPv6DefaultRoute)
        assertFalse(plan.leaksIPv6AroundTheTunnel)
    }

    @Test
    fun `an AllowedIPs prefix covering the client address is flagged`() {
        assertTrue(ChainedRoutePlan.coversClientAddress("10.7.0.2/32", "10.7.0.2"))
        // A containing subnet is the ordinary peer-side network, not a self-route.
        assertFalse(ChainedRoutePlan.coversClientAddress("10.7.0.0/24", "10.7.0.2"))
        assertFalse(ChainedRoutePlan.coversClientAddress("10.7.0.0/16", "10.7.0.2"))
        assertFalse(ChainedRoutePlan.coversClientAddress("0.0.0.0/0", "10.7.0.2"))
        assertFalse(ChainedRoutePlan.coversClientAddress("10.8.0.0/16", "10.7.0.2"))
        assertFalse(ChainedRoutePlan.coversClientAddress("10.7.0.3/32", "10.7.0.2"))
        assertFalse(ChainedRoutePlan.coversClientAddress("::/0", "10.7.0.2"))
    }

    @Test
    fun `collisions reports every guard a set trips, without duplicates`() {
        assertEquals(
            listOf(ChainedRoutePlanCollision.ALLOWED_IPS_OVERLAPS_DNS_CAPTURE),
            ChainedRoutePlan.collisions(listOf("10.111.0.0/24"), "10.77.0.2"),
        )
        // Only an EXACT client host route is a self-route; a containing subnet is valid.
        assertEquals(
            listOf(ChainedRoutePlanCollision.ALLOWED_IPS_COVERS_CLIENT_ADDRESS),
            ChainedRoutePlan.collisions(listOf("10.77.0.2/32"), "10.77.0.2"),
        )
        assertEquals(emptyList<ChainedRoutePlanCollision>(), ChainedRoutePlan.collisions(listOf("10.77.0.0/16"), "10.77.0.2"))
        // Two prefixes tripping the same guard collapse to one entry; a set can trip both guards.
        assertEquals(
            listOf(
                ChainedRoutePlanCollision.ALLOWED_IPS_OVERLAPS_DNS_CAPTURE,
                ChainedRoutePlanCollision.ALLOWED_IPS_COVERS_CLIENT_ADDRESS,
            ),
            ChainedRoutePlan.collisions(
                listOf("10.111.0.0/24", "10.111.0.1/32", "10.77.0.2/32"),
                "10.77.0.2",
            ),
        )
        assertEquals(emptyList<ChainedRoutePlanCollision>(), ChainedRoutePlan.collisions(listOf("192.168.1.0/24"), "10.77.0.2"))
    }

    // MARK: - IPv6 boundary

    @Test
    fun `the IPv6 default route is present only in full tunnel`() {
        assertFalse(ChainedRoutePlan.make(null).carriesIPv6)
        val split = ChainedRoutePlan.make(configuration(allowedIPs = listOf("10.0.0.0/8")))
        assertTrue(split.carriesIPv6)
        assertFalse(split.claimsIPv6DefaultRoute)
        assertEquals(listOf(IPv6Route("fd00:1a7a::", 64)), split.includedIPv6Routes)
        assertEquals("fd00:1a7a::1", split.dnsServerIPv6Address)
        val full = ChainedRoutePlan.make(configuration())
        assertTrue(full.carriesIPv6)
        assertTrue(full.claimsIPv6DefaultRoute)
        assertEquals(listOf(IPv6Route("::", 0)), full.includedIPv6Routes)
    }

    @Test
    fun `every equivalent spelling of the unspecified address is recognised`() {
        for (spelling in listOf("::", "::0", "0::0", "0:0:0:0:0:0:0:0")) {
            assertTrue("$spelling is ::/0", ChainedRoutePlan.isUnspecifiedIPv6Address(spelling))
        }
        assertFalse(ChainedRoutePlan.isUnspecifiedIPv6Address("fd00:1a7a::2"))
        assertFalse(ChainedRoutePlan.isUnspecifiedIPv6Address("::1"))
        assertFalse(ChainedRoutePlan.isUnspecifiedIPv6Address("not-an-address"))
        assertFalse(ChainedRoutePlan.isUnspecifiedIPv6Address(""))
    }

    @Test
    fun `the leak predicate catches a full tunnel that forgot its IPv6 blackhole`() {
        val leaky = ChainedRoutePlan.of(
            tunnelAddress = "10.64.0.5",
            tunnelPrefixLength = 32,
            dnsServerAddress = "10.111.0.1",
            routeDescription = "0.0.0.0/0",
            includedIPv4Routes = listOf(IPv4Route("0.0.0.0", 0)),
            tunnelIPv6Address = null,
            tunnelIPv6PrefixLength = null,
            includedIPv6Routes = emptyList(),
            mtu = 1280,
        )
        assertTrue(leaky.leaksIPv6AroundTheTunnel)
        // A plan claiming no IPv4 default route is not a leak even with no IPv6.
        assertFalse(ChainedRoutePlan.make(null).leaksIPv6AroundTheTunnel)
    }

    @Test
    fun `the MTU floor binds a plan that carries IPv6`() {
        assertTrue(ChainedRoutePlan.make(configuration()).mtuIsLegalForClaimedFamilies)
        // A carried IPv6 address at a sub-1280 MTU is out of spec (RFC 8200 §5).
        val illegal = ChainedRoutePlan.of(
            tunnelAddress = "10.64.0.5",
            tunnelPrefixLength = 32,
            dnsServerAddress = "10.111.0.1",
            routeDescription = "custom",
            includedIPv4Routes = emptyList(),
            tunnelIPv6Address = "fd00:1a7a::2",
            tunnelIPv6PrefixLength = 64,
            includedIPv6Routes = emptyList(),
            mtu = 1200,
        )
        assertTrue(illegal.carriesIPv6)
        assertFalse(illegal.mtuIsLegalForClaimedFamilies)
    }

    @Test
    fun `the route plan value type is constructible with Android-ready routes`() {
        // The routes expose the address/length form the VpnService.Builder consumes, so the
        // eventual translation is a field copy rather than arithmetic that can disagree.
        val plan = ChainedRoutePlan.make(configuration())
        assertEquals("0.0.0.0/0", plan.includedIPv4Routes.single().cidr)
        assertEquals("::/0", plan.includedIPv6Routes.single().cidr)
    }

    @Test
    fun `the split builder drops a non-IPv4 entry defensively`() {
        // The configuration boundary refuses a v6 AllowedIPs in split mode before this runs,
        // so the compactMap filter is a defensive no-op; pinned so a future mixed list cannot
        // silently become a route.
        val ok = ChainedRoutePlan.ipv4RouteFromPrefix("10.0.0.0/8")
        val mixed = listOf("10.0.0.0/8", "::/0").mapNotNull(ChainedRoutePlan::ipv4RouteFromPrefix)
        assertEquals(listOf(ok), mixed)
    }

    @Test
    fun `a malformed client address cannot trip the client guard`() {
        assertFalse(ChainedRoutePlan.coversClientAddress("0.0.0.0/0", "not-an-address"))
        assertFalse(ChainedRoutePlan.overlapsDnsCapture("not-a-prefix"))
    }

    @Test
    fun `the plan can only claim from the configuration it was given`() {
        // Two configs, two plans: if the factory read a constant the two would agree.
        val first = ChainedRoutePlan.make(configuration(clientAddress = "10.64.0.5"))
        val second = ChainedRoutePlan.make(configuration(clientAddress = "192.168.7.2"))
        assertEquals("10.64.0.5", first.tunnelAddress)
        assertEquals("192.168.7.2", second.tunnelAddress)
        assertNotEquals(first, second)
    }

    @Test
    fun `a valid full-tunnel configuration never trips the split collision guard when applied`() {
        // The guard is for split mode; a covering set necessarily contains the DNS block and the
        // client, so a full tunnel must not be run through it. This pins the boundary's intent.
        val full = configuration()
        assertEquals(ChainedUpstreamConfiguration.DNS_SERVER_ADDRESS, ChainedRoutePlan.DNS_SERVER_ADDRESS)
        // A covering set contains the DNS proxy; it is not the EXACT client /32, so only the
        // DNS-capture guard fires (and split mode never runs a full-tunnel set through it).
        assertEquals(
            listOf(ChainedRoutePlanCollision.ALLOWED_IPS_OVERLAPS_DNS_CAPTURE),
            ChainedRoutePlan.collisions(full.allowedIPs, full.clientAddress),
        )
    }

    @Test
    fun `the collision guard surfaces the same enum values the plan documents`() {
        val values = ChainedRoutePlanCollision.entries.map { it.name }
        assertTrue(values.contains("ALLOWED_IPS_OVERLAPS_DNS_CAPTURE"))
        assertTrue(values.contains("ALLOWED_IPS_COVERS_CLIENT_ADDRESS"))
        assertEquals(2, values.size)
    }

    @Test
    fun `dnsOnly and full and split are three distinct shapes`() {
        assertNotEquals(ChainedRoutePlan.make(null), ChainedRoutePlan.make(configuration()))
        assertNotEquals(
            ChainedRoutePlan.make(configuration()),
            ChainedRoutePlan.make(configuration(allowedIPs = listOf("10.0.0.0/8"))),
        )
    }

    @Test
    fun `an illegal MTU cannot reach the plan because the configuration boundary refuses it`() {
        // Documented parity with iOS D5/C7: this is unrepresentable rather than clamped.
        assertThrows(ChainedConfigurationException::class.java) {
            configuration(mtu = 1279)
        }
    }
}
