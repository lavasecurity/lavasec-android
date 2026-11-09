package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverPlanUnavailableReason
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResolverPlanTest {
    @Test
    fun `factory builds each supported resolver route`() {
        val deviceAndMullvad = build(
            ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = true),
            addresses = listOf(ip("192.0.2.53")),
        )
        assertEquals(ResolverKind.DEVICE, deviceAndMullvad.primaryResolverKind)
        assertTrue(deviceAndMullvad.mullvadEnabled)
        assertEquals(ResolverFallbackCoverage.ENCRYPTED_DNS, deviceAndMullvad.fallbackCoverage)

        val deviceOnly = build(
            ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = false),
            addresses = listOf(ip("192.0.2.53")),
        )
        assertEquals(ResolverKind.DEVICE, deviceOnly.primaryResolverKind)
        assertFalse(deviceOnly.mullvadEnabled)
        assertEquals(ResolverFallbackCoverage.NONE, deviceOnly.fallbackCoverage)

        val mullvadOnly = build(
            ResolverConfiguration(useDeviceDns = false, useEncryptedFallback = true),
            addresses = listOf(ip("192.0.2.53")),
        )
        assertEquals(ResolverKind.MULLVAD_DOH, mullvadOnly.primaryResolverKind)
        assertTrue(mullvadOnly.mullvadEnabled)
        assertEquals(PrimaryResolverIdentity(BuiltInDohResolverIds.MULLVAD), mullvadOnly.primaryResolverIdentity)
        assertEquals(ResolverFallbackCoverage.NONE, mullvadOnly.fallbackCoverage)
        assertTrue(mullvadOnly.deviceDnsServers.isEmpty())
    }

    @Test
    fun `factory promotes Mullvad when requested Device DNS has no usable address`() {
        val plan = build(
            ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = true),
            addresses = listOf(ip("0.0.0.0"), ip("127.0.0.1")),
        )

        assertEquals(ResolverKind.MULLVAD_DOH, plan.primaryResolverKind)
        assertTrue(plan.mullvadEnabled)
        assertEquals(ResolverFallbackCoverage.NONE, plan.fallbackCoverage)
        assertTrue(plan.deviceDnsServers.isEmpty())
    }

    @Test
    fun `factory reports no usable resolver for Device-only without a usable address`() {
        val result = ResolverPlanFactory.build(
            ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = false),
            networkContext(addresses = listOf(ip("0.0.0.0"))),
        )

        assertEquals(
            ResolverPlanBuildResult.Unavailable(ResolverPlanUnavailableReason.NO_USABLE_RESOLVER),
            result,
        )
    }

    @Test
    fun `legacy encrypted IDs serve the same actual catalog endpoint without silent migration`() {
        (BuiltInDohResolverIds.all - BuiltInDohResolverIds.MULLVAD).forEach { resolverId ->
            val plan = (ResolverPlanFactory.build(ResolverConfiguration(true, true, resolverId),
                networkContext(addresses = listOf(ip("192.0.2.53")))) as ResolverPlanBuildResult.Ready).plan
            val leaf = plan.orderedRoutes!!.last()
            assertEquals(resolverId, com.lavasec.app.foundation.resolver.ResolverCatalog.presetId(leaf.catalogEntry!!))
            assertTrue(leaf.encryptedBootstrapAddresses.isNotEmpty())
            assertTrue(plan.isCanonicalReadyRoute())
        }
    }

    @Test
    fun `factory ignores a disabled fallback stored resolver ID`() {
        val plan = build(
            ResolverConfiguration(
                useDeviceDns = true,
                useEncryptedFallback = false,
                encryptedFallbackId = BuiltInDohResolverIds.CLOUDFLARE,
            ),
            addresses = listOf(ip("192.0.2.53")),
        )

        assertEquals(ResolverKind.DEVICE, plan.primaryResolverKind)
        assertFalse(plan.mullvadEnabled)
        assertEquals(ResolverFallbackCoverage.NONE, plan.fallbackCoverage)
    }

    @Test
    fun `factory retains an immutable selected Device address snapshot`() {
        val source = mutableListOf(ip("192.0.2.53"))
        val plan = build(
            ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = false),
            addresses = source,
        )
        source += ip("2001:db8::53")

        assertEquals(listOf("192.0.2.53"), plan.deviceDnsServers.map(InetAddress::getHostAddress))
        assertImmutable(plan.deviceDnsServers)
    }

    @Test
    fun `Device identity contains canonical raw selected address tokens`() {
        val plan = build(
            ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = false),
            addresses = listOf(ip("192.0.2.53"), ip("2001:db8::53")),
        )

        assertEquals(
            PrimaryResolverIdentity("device-dns:v4=c0000235;v6=20010db8000000000000000000000053"),
            plan.primaryResolverIdentity,
        )
    }

    @Test
    fun `automatic family ordering does not change Device identity`() {
        val addresses = listOf(ip("192.0.2.53"), ip("2001:db8::53"))
        val configuration = ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = false)

        val nonCellular = build(configuration, addresses, isCellular = false)
        val cellular = build(configuration, addresses, isCellular = true)

        assertEquals(
            listOf("192.0.2.53", "2001:db8:0:0:0:0:0:53"),
            nonCellular.deviceDnsServers.map(InetAddress::getHostAddress),
        )
        assertEquals(
            listOf("2001:db8:0:0:0:0:0:53", "192.0.2.53"),
            cellular.deviceDnsServers.map(InetAddress::getHostAddress),
        )
        assertEquals(nonCellular.primaryResolverIdentity, cellular.primaryResolverIdentity)
    }

    @Test
    fun `within-family ordering and selected set changes change Device identity`() {
        val configuration = ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = false)
        val original = build(configuration, listOf(ip("192.0.2.53"), ip("192.0.2.54"), ip("2001:db8::53")))
        val reordered = build(configuration, listOf(ip("192.0.2.54"), ip("192.0.2.53"), ip("2001:db8::53")))
        val changedSet = build(configuration, listOf(ip("192.0.2.53"), ip("2001:db8::54")))

        assertNotEquals(original.primaryResolverIdentity, reordered.primaryResolverIdentity)
        assertNotEquals(original.primaryResolverIdentity, changedSet.primaryResolverIdentity)
    }

    @Test
    fun `fallback-only changes do not change Device primary identity`() {
        val addresses = listOf(ip("192.0.2.53"))
        val deviceOnly = build(
            ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = false),
            addresses,
        )
        val deviceWithMullvad = build(
            ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = true),
            addresses,
        )

        assertEquals(deviceOnly.primaryResolverIdentity, deviceWithMullvad.primaryResolverIdentity)
        assertNotEquals(deviceOnly.fallbackCoverage, deviceWithMullvad.fallbackCoverage)
    }

    @Test
    fun `factory builds a custom DoH route that replaces Device DNS`() {
        val plan = build(
            ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDohUrl = "https://dns.google/dns-query",
            ),
            addresses = listOf(ip("192.0.2.53")),
        )

        assertEquals(ResolverKind.MULLVAD_DOH, plan.primaryResolverKind)
        assertTrue(plan.mullvadEnabled)
        assertTrue(plan.deviceDnsServers.isEmpty())
        assertTrue(plan.isCustomDoh)
        assertEquals("https://dns.google/dns-query", plan.dohEndpointUrl)
        assertEquals(
            ResolverPlanFactory.customDohIdentity("https://dns.google/dns-query"),
            plan.primaryResolverIdentity,
        )
        assertEquals(ResolverFallbackCoverage.NONE, plan.fallbackCoverage)
        assertTrue(plan.isCanonicalReadyRoute())
    }

    @Test
    fun `a custom DoH plan is distinct from the pinned Mullvad route`() {
        val addresses = listOf(ip("192.0.2.53"))
        val custom = build(
            ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDohUrl = "https://dns.google/dns-query",
            ),
            addresses,
        )
        val mullvad = build(
            ResolverConfiguration(useDeviceDns = false, useEncryptedFallback = true),
            addresses,
        )

        assertNotEquals(custom.primaryResolverIdentity, mullvad.primaryResolverIdentity)
        assertTrue(mullvad.dohEndpointUrl == null)
        assertFalse(mullvad.isCustomDoh)
        assertFalse(custom.isCanonicalReadyRoute() && custom.primaryResolverIdentity == mullvad.primaryResolverIdentity)
    }

    @Test
    fun `factory builds a custom DoT route that replaces Device DNS`() {
        val plan = build(
            ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDotEndpoint = "tls://dns.google:853",
            ),
            addresses = listOf(ip("192.0.2.53")),
        )

        assertEquals(ResolverKind.MULLVAD_DOH, plan.primaryResolverKind)
        assertTrue(plan.mullvadEnabled)
        assertTrue(plan.deviceDnsServers.isEmpty())
        assertTrue(plan.isCustomDot)
        assertFalse(plan.isCustomDoh)
        assertEquals(CustomDotEndpoint("dns.google", 853), plan.dotEndpoint)
        assertEquals("tls://dns.google:853", plan.dotEndpoint?.normalized)
        assertEquals(
            ResolverPlanFactory.customDotIdentity("tls://dns.google:853"),
            plan.primaryResolverIdentity,
        )
        assertEquals(ResolverFallbackCoverage.NONE, plan.fallbackCoverage)
        assertTrue(plan.isCanonicalReadyRoute())
    }

    @Test
    fun `a custom DoT plan is distinct from the pinned Mullvad route`() {
        val addresses = listOf(ip("192.0.2.53"))
        val custom = build(
            ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDotEndpoint = "tls://dns.google:853",
            ),
            addresses,
        )
        val mullvad = build(
            ResolverConfiguration(useDeviceDns = false, useEncryptedFallback = true),
            addresses,
        )

        assertNotEquals(custom.primaryResolverIdentity, mullvad.primaryResolverIdentity)
        assertTrue(mullvad.dotEndpoint == null)
        assertFalse(mullvad.isCustomDot)
    }

    @Test
    fun `factory carries a custom DoH secondary without changing the primary identity`() {
        val plan = build(
            ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDohUrl = "https://dns.google/dns-query",
                customSecondaryEndpoint = "https://cloudflare-dns.com/dns-query",
            ),
            addresses = listOf(ip("192.0.2.53")),
        )

        assertEquals("https://dns.google/dns-query", plan.dohEndpointUrl)
        assertEquals("https://cloudflare-dns.com/dns-query", plan.dohSecondaryEndpointUrl)
        assertNull(plan.dotSecondaryEndpoint)
        // The primary identity is the single endpoint the route is health-scoped to.
        assertEquals(
            ResolverPlanFactory.customDohIdentity("https://dns.google/dns-query"),
            plan.primaryResolverIdentity,
        )
        assertTrue(plan.isCanonicalReadyRoute())
    }

    @Test
    fun `factory carries a custom DoT secondary without changing the primary identity`() {
        val plan = build(
            ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDotEndpoint = "tls://dns.google:853",
                customSecondaryEndpoint = "tls://dns.quad9.net:853",
            ),
            addresses = listOf(ip("192.0.2.53")),
        )

        assertEquals(CustomDotEndpoint("dns.google", 853), plan.dotEndpoint)
        assertEquals(CustomDotEndpoint("dns.quad9.net", 853), plan.dotSecondaryEndpoint)
        assertNull(plan.dohSecondaryEndpointUrl)
        assertEquals(
            ResolverPlanFactory.customDotIdentity("tls://dns.google:853"),
            plan.primaryResolverIdentity,
        )
        assertTrue(plan.isCanonicalReadyRoute())
    }

    private fun build(
        configuration: ResolverConfiguration,
        addresses: List<InetAddress>,
        isCellular: Boolean = false,
    ): ResolverPlan = (ResolverPlanFactory.build(configuration, networkContext(addresses, isCellular)) as ResolverPlanBuildResult.Ready).plan

    private fun networkContext(
        addresses: List<InetAddress>,
        isCellular: Boolean = false,
    ) = NetworkContext(network(42), networkGeneration = 7, dnsServers = addresses, isCellular = isCellular)

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    private fun network(handle: Int): Network =
        Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(handle)

    @Suppress("UNCHECKED_CAST")
    private fun <T> assertImmutable(values: List<T>) {
        val mutable = values as MutableList<T>
        try {
            mutable.clear()
        } catch (_: UnsupportedOperationException) {
            return
        }
        assertFalse("plan address list was mutable", true)
    }
}
