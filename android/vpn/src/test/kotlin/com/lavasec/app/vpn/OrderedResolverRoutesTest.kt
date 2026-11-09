package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OrderedResolverRoutesTest {
    @Test
    fun `encrypted primary falls back to Device and later recovers the exact primary`() = runBlocking {
        val f = Fixture(listOf(ResolverTier(doq()), ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN)))
        assertEquals(ResolverFallbackCoverage.DEVICE_DNS, f.plan.fallbackCoverage)
        assertEquals("custom-doq:doq://first.example:8853", f.plan.primaryResolverIdentity.value)
        assertTrue(f.calls.isEmpty()) // Composition starts no query.
        f.primaryFailure = ResolverFailureReason.TLS_FAILED
        val fallback = f.bundle.resolve(f.request()) as ResolverResult.Answer
        assertEquals(listOf("custom-doq:doq://first.example:8853", "device"), f.calls)
        assertEquals(ProtectionFallback.DEVICE_DNS, fallback.resolverQueryEvidence?.successfulFallback)
        assertTrue(fallback.healthEvents.contains(DnsHealthEvent.FallbackActivated(ResolverKind.DEVICE)))
        f.now += 30_000_000_000L
        f.primaryFailure = null
        val recovered = f.bundle.resolve(f.request()) as ResolverResult.Answer
        assertEquals(ResolverTransport.DOQ, recovered.transport)
        assertEquals(ProtectionFallback.NONE, recovered.resolverQueryEvidence?.successfulFallback)
        assertTrue(recovered.healthEvents.contains(DnsHealthEvent.ResolverRecovered(ResolverKind.MULLVAD_DOH)))
        f.close()
        assertEquals(listOf("custom-doq:doq://first.example:8853"), f.closed)
    }

    @Test
    fun `two encrypted tiers keep independent endpoint backoff and honest activation`() = runBlocking {
        val f = Fixture(listOf(ResolverTier(doq()), ResolverTier(doh())))
        assertEquals(ResolverFallbackCoverage.ENCRYPTED_DNS, f.plan.fallbackCoverage)
        f.primaryFailure = ResolverFailureReason.TIMEOUT
        val fallback = f.bundle.resolve(f.request()) as ResolverResult.Answer
        assertEquals(ResolverTransport.MULLVAD_DOH, fallback.transport)
        assertEquals(ProtectionFallback.ENCRYPTED_DNS, fallback.resolverQueryEvidence?.successfulFallback)
        assertTrue(fallback.healthEvents.contains(DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH)))
        f.bundle.resolve(f.request())
        assertEquals(listOf("custom-doq:doq://first.example:8853", "custom-doh:https://second.example/dns-query",
            "custom-doh:https://second.example/dns-query"), f.calls)
        f.now += 30_000_000_000L
        f.primaryFailure = null
        val recovered = f.bundle.resolve(f.request()) as ResolverResult.Answer
        assertTrue(recovered.healthEvents.contains(DnsHealthEvent.ResolverRecovered(ResolverKind.MULLVAD_DOH)))
        f.close()
        assertEquals(2, f.closed.size)
        assertEquals(2, f.closed.distinct().size)
    }

    @Test
    fun `lifecycle failures and cancellation never attempt a later tier`() = runBlocking {
        for (reason in listOf(ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.CANCELLED,
            ResolverFailureReason.OVERLOADED)) {
            val f = Fixture(listOf(ResolverTier(doq()), ResolverTier(doh())))
            f.primaryFailure = reason
            assertEquals(reason, (f.bundle.resolve(f.request()) as ResolverResult.Failure).reason)
            assertEquals(1, f.calls.size)
            f.close()
        }
        val f = Fixture(listOf(ResolverTier(doq()), ResolverTier(doh())))
        f.cancelPrimary = true
        assertEquals(ResolverFailureReason.CANCELLED, (f.bundle.resolve(f.request()) as ResolverResult.Failure).reason)
        assertEquals(1, f.calls.size)
        f.close()
    }

    @Test
    fun `unusable Device fallback is omitted without changing saved order or adding encrypted fallback`() {
        val selection = ResolverSelection(listOf(ResolverTier(doq()), ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN)))
        val context = testNetworkContext(dnsServers = emptyList())
        val plan = (ResolverPlanFactory.build(checkNotNull(selection.toFilterResolverPlan()).configuration, context)
            as ResolverPlanBuildResult.Ready).plan
        assertEquals(1, plan.orderedRoutes?.size)
        assertEquals(ResolverKind.MULLVAD_DOH, plan.primaryResolverKind)
        assertEquals(ResolverFallbackCoverage.NONE, plan.fallbackCoverage)
        assertTrue(plan.deviceDnsRequested)
        assertFalse(plan.hasDeviceRoute)
        assertTrue(plan.isCanonicalReadyRoute())
        assertEquals(ResolverSelectionPresets.DEVICE_PLAIN, selection.fallback)
    }

    @Test
    fun `a disabled encrypted row is retained but never constructed or contacted`() = runBlocking {
        val f = Fixture(listOf(ResolverTier(doq(), false, "Keep for later"), ResolverTier(doh())))
        f.bundle.resolve(f.request())
        assertEquals(listOf("custom-doh:https://second.example/dns-query"), f.calls)
        f.close()
        assertEquals(f.calls, f.closed)
    }

    @Test
    fun `failure constructing the second encrypted runtime closes the first cold runtime`() {
        val context = testNetworkContext()
        val plan = (ResolverPlanFactory.build(checkNotNull(ResolverSelection(listOf(
            ResolverTier(doq()), ResolverTier(doh()))).toFilterResolverPlan()).configuration, context)
            as ResolverPlanBuildResult.Ready).plan
        val epoch = testEpoch(resolverPlan = plan)
        val registry = GenerationSocketRegistry(epoch)
        var closed = 0
        var created = 0
        val factory = ProductionReadyDnsResolverFactory(
            deviceRouteFactory = ReadyDnsDeviceRouteFactory { _, _, _, _ -> error("no Device route") },
            mullvadRuntimeFactory = ReadyDnsMullvadRuntimeFactory { _, _, _, _ ->
                if (++created == 2) throw IllegalStateException("controlled construction failure")
                object : ReadyDnsMullvadRuntime {
                    override val resolver = DnsResolver { error("cold construction cannot send") }
                    override fun close() { closed++ }
                }
            },
            coordinatorFactory = ReadyDnsCoordinatorFactory { _, _, _, _, _, _, _ -> error("no coordinator after failure") },
            wedgeEvidence = DeviceResolverWedgeEvidence { false },
        )
        assertThrows(IllegalStateException::class.java) { factory.create(epoch, context, plan, registry) }
        assertEquals(1, closed)
        registry.close()
    }

    @Test
    fun `catalog leaves retain distinct exact endpoints and literal bootstraps through ordered fallback`() = runBlocking {
        val f = Fixture(listOf(
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.QUAD9_UNFILTERED, CatalogResolverTransport.DOT)),
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.CLOUDFLARE, CatalogResolverTransport.DOH)),
        ))
        val leaves = f.plan.orderedRoutes!!
        assertEquals("tls://dns10.quad9.net:853", leaves[0].dotEndpoint!!.normalized)
        assertEquals("https://cloudflare-dns.com/dns-query", leaves[1].dohEndpointUrl)
        assertEquals(listOf(java.net.InetAddress.getByName("9.9.9.10"), java.net.InetAddress.getByName("2620:fe::10")), leaves[0].encryptedBootstrapAddresses)
        assertTrue(f.plan.isCanonicalReadyRoute())
        assertTrue(f.calls.isEmpty())
        f.primaryFailure = ResolverFailureReason.TLS_FAILED
        val answer = f.bundle.resolve(f.request()) as ResolverResult.Answer
        assertEquals(listOf("catalog-dot:quad9-unfiltered:tls://dns10.quad9.net:853",
            "catalog-doh:cloudflare-1111:https://cloudflare-dns.com/dns-query"), f.calls)
        assertEquals(ProtectionFallback.ENCRYPTED_DNS, answer.resolverQueryEvidence!!.successfulFallback)
        f.close()
    }

    @Test
    fun `catalog bootstrap refuses unrelated hosts without a process DNS lookup`() {
        val addresses = listOf(java.net.InetAddress.getByName("192.168.1.53"))
        val bootstrap = CatalogDohBootstrap("resolver.example", addresses)
        assertEquals(addresses, bootstrap.lookup("resolver.example"))
        assertThrows(java.net.UnknownHostException::class.java) { bootstrap.lookup("other.example") }
    }

    @Test
    fun `encrypted primary fallback to fixed plain is reported as plain and recovers exact primary`() = runBlocking {
        val f = Fixture(listOf(ResolverTier(doq()), ResolverTier(ResolverSelectionEntry(
            ResolverProviderId.CLOUDFLARE, CatalogResolverTransport.PLAIN_DNS))))
        assertEquals(ResolverFallbackCoverage.PLAIN_DNS, f.plan.fallbackCoverage)
        assertFalse(f.plan.deviceDnsRequested)
        assertTrue(f.plan.deviceDnsServers.isEmpty())
        f.primaryFailure = ResolverFailureReason.TIMEOUT
        val fallback = f.bundle.resolve(f.request()) as ResolverResult.Answer
        assertEquals(ResolverTransport.PLAIN_UDP, fallback.transport)
        assertEquals(ProtectionFallback.PLAIN_DNS, fallback.resolverQueryEvidence!!.successfulFallback)
        assertTrue(fallback.healthEvents.contains(DnsHealthEvent.FallbackActivated(ResolverKind.PLAIN_DNS)))
        f.now += 30_000_000_000L
        f.primaryFailure = null
        assertTrue((f.bundle.resolve(f.request()) as ResolverResult.Answer).healthEvents
            .contains(DnsHealthEvent.ResolverRecovered(ResolverKind.MULLVAD_DOH)))
        f.close()
    }

    @Test
    fun `custom plain primary retains secondary addresses without claiming the current network DNS`() {
        val context = testNetworkContext()
        val entry = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.PLAIN_DNS,
            customPlainEndpoint = "192.168.1.53", customSecondaryEndpoint = "192.168.1.54", customName = "Office")
        val plan = (ResolverPlanFactory.build(ResolverSelection(listOf(ResolverTier(entry))).toFilterResolverPlan()!!.configuration,
            context) as ResolverPlanBuildResult.Ready).plan
        assertEquals(ResolverKind.PLAIN_DNS, plan.primaryResolverKind)
        assertFalse(plan.deviceDnsRequested)
        assertFalse(plan.hasDeviceRoute)
        assertTrue(plan.isCanonicalReadyRoute())
        assertEquals(listOf(java.net.InetAddress.getByName("192.168.1.53"), java.net.InetAddress.getByName("192.168.1.54")),
            plan.orderedRoutes!!.single().fixedDnsServers)
    }

    private fun doq() = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOQ,
        customDoqEndpoint = "doq://first.example:8853")
    private fun doh() = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH,
        customDohUrl = "https://second.example/dns-query")

    private class Fixture(tiers: List<ResolverTier>) {
        val context = testNetworkContext()
        val plan = (ResolverPlanFactory.build(checkNotNull(ResolverSelection(tiers).toFilterResolverPlan()).configuration,
            context) as ResolverPlanBuildResult.Ready).plan
        val epoch = testEpoch(resolverPlan = plan)
        val registry = GenerationSocketRegistry(epoch)
        val calls = mutableListOf<String>()
        val closed = mutableListOf<String>()
        var primaryFailure: ResolverFailureReason? = null
        var cancelPrimary = false
        var now = 0L
        private val clock = MonotonicClock { now }
        val bundle = ProductionReadyDnsResolverFactory(
            deviceRouteFactory = ReadyDnsDeviceRouteFactory { _, _, _, _ ->
                DeviceDnsRoute { request, _ ->
                    calls += "device"
                    DeviceDnsRouteResult(answer(request, ResolverTransport.DEVICE_UDP),
                        ResolverEndpointKey.DeviceAddress(context.dnsServers.first()))
                }
            },
            mullvadRuntimeFactory = ReadyDnsMullvadRuntimeFactory { _, _, leaf, _ ->
                object : ReadyDnsMullvadRuntime {
                    override val resolver = DnsResolver { request ->
                        calls += leaf.primaryResolverIdentity.value
                        if (leaf.primaryResolverIdentity == plan.primaryResolverIdentity) {
                            if (cancelPrimary) throw CancellationException("controlled cancellation")
                            primaryFailure?.let { return@DnsResolver ResolverResult.Failure(it) }
                        }
                        answer(request, when { leaf.doqEndpoint != null -> ResolverTransport.DOQ; leaf.dotEndpoint != null -> ResolverTransport.DOT; else -> ResolverTransport.MULLVAD_DOH })
                    }
                    override fun close() { closed += leaf.primaryResolverIdentity.value }
                }
            },
            fixedPlainRuntimeFactory = ReadyDnsMullvadRuntimeFactory { _, _, leaf, _ ->
                object : ReadyDnsMullvadRuntime {
                    override val resolver = DnsResolver { request ->
                        calls += leaf.primaryResolverIdentity.value
                        if (leaf.primaryResolverIdentity == plan.primaryResolverIdentity) primaryFailure?.let { return@DnsResolver ResolverResult.Failure(it) }
                        answer(request, ResolverTransport.PLAIN_UDP)
                    }
                    override fun close() { closed += leaf.primaryResolverIdentity.value }
                }
            },
            coordinatorFactory = ReadyDnsCoordinatorFactory { epoch, _, plan, registry, device, encrypted, wedge ->
                ResolverCoordinator(epoch, plan, registry, device, encrypted, wedge, clock = clock,
                    childDispatcher = Dispatchers.Unconfined)
            },
            wedgeEvidence = DeviceResolverWedgeEvidence { false },
        ).create(epoch, context, plan, registry)

        fun request(): ResolverRequest {
            val query = byteArrayOf(0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                1, 97, 7, 101, 120, 97, 109, 112, 108, 101, 0, 0, 1, 0, 1)
            return ResolverRequest(query, DnsMessage.parseQuestion(query), epoch, context)
        }
        fun close() { registry.close(); bundle.close(); bundle.close() }
        private fun answer(request: ResolverRequest, transport: ResolverTransport) = ResolverResult.Answer(
            request.query.copyOf().apply { this[2] = 0x80.toByte() }, transport)
    }
}
