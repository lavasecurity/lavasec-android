package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.platform.ConfigurationIdentity
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SavedDnsChainedFallbackTest {
    @Test
    fun `portal mode preserves configured order and does not force Device after selected public positive`() = runTest {
        for ((first, transport) in listOf(doh() to ResolverTransport.MULLVAD_DOH, plain() to ResolverTransport.PLAIN_TCP)) {
            val f = OrderedFixture(this, listOf(ResolverTier(first), ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN)))
            f.adapter.resolve(PORTAL_QUERY, 12_000_000_000L, DnsPortalTierFallback.registeredHosts, true, f.results::add)
            runCurrent()

            assertEquals(listOf("public"), f.order)
            assertEquals(transport, (f.results.single() as ResolverResult.Answer).transport)
            assertFalse(f.results.single().resolverQueryEvidence!!.successfulFallback ==
                com.lavasec.app.foundation.health.ProtectionFallback.DEVICE_DNS)
            f.adapter.close()
        }
    }

    @Test
    fun `Device first serves before encrypted and unavailable Device retains configured fallback order`() = runTest {
        for (unavailable in listOf(false, true)) {
            val f = OrderedFixture(this, listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN), ResolverTier(doh())),
                deviceUnavailable = unavailable)
            f.adapter.resolve(PORTAL_QUERY, 12_000_000_000L, DnsPortalTierFallback.registeredHosts, true, f.results::add)
            runCurrent()

            assertEquals(if (unavailable) listOf("device", "public") else listOf("device"), f.order)
            assertEquals(if (unavailable) ResolverTransport.MULLVAD_DOH else ResolverTransport.DEVICE_UDP,
                (f.results.single() as ResolverResult.Answer).transport)
            f.adapter.close()
        }
    }

    @Test
    fun `disabled Device is absent from saved route and receives no query`() = runTest {
        val f = OrderedFixture(this, listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, isEnabled = false), ResolverTier(doh())))
        assertFalse(f.adapter.context.hasEnabledDeviceTier)
        f.adapter.resolve(PORTAL_QUERY, f.results::add)
        runCurrent()

        assertEquals(listOf("public"), f.order)
        assertEquals(listOf(2), f.results.single().resolverQueryEvidence!!.tierObservations.map { it.tier })
        f.adapter.close()
    }

    @Test
    fun `saved Device keeps complete large address response for T0 semantic selection`() = runTest {
        val padding = 1_400
        val optLength = padding + 4
        val large = DnsMessage.bootstrapResponse(QUERY, byteArrayOf(192.toByte(), 0, 2, 8), ByteArray(16)).also { it[11] = 1 } +
            byteArrayOf(0, 0, 41, 4, 0xd0.toByte(), 0, 0, 0, 0, (optLength ushr 8).toByte(), optLength.toByte(),
                0, 12, (padding ushr 8).toByte(), padding.toByte()) + ByteArray(padding)
        val f = Fixture(this, listOf(ResolverSelectionPresets.DEVICE_PLAIN)) {
            ResolverResult.Answer(large, ResolverTransport.DEVICE_UDP)
        }
        f.adapter.resolve(QUERY, f.results::add)
        runCurrent()

        val selected = f.results.single() as ResolverResult.Answer
        assertArrayEquals(large, selected.dnsMessage)
        assertEquals(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.classify(QUERY, selected.dnsMessage).kind)
        assertEquals(1, DnsFallbackResponse.classify(QUERY, selected.dnsMessage).terminalAddressCount)
        f.adapter.close()
    }

    @Test
    fun `construction is cold and query retains exact epoch network and client identity`() = runTest {
        val f = Fixture(this, listOf(doh())) { request ->
            assertArrayEquals(QUERY, request.query)
            ResolverResult.Answer(response().apply { this[0] = 0; this[1] = 0 }, ResolverTransport.MULLVAD_DOH)
        }
        assertEquals(0, f.calls)
        val query = QUERY.copyOf()
        f.adapter.resolve(query, f.results::add)
        query.fill(0)
        runCurrent()
        assertEquals(1, f.calls)
        assertSame(f.epoch, f.lastRequest!!.epoch)
        assertSame(f.context, f.lastRequest!!.networkContext)
        assertArrayEquals(response(), (f.results.single() as ResolverResult.Answer).dnsMessage)
        assertEquals(ResolverTransport.MULLVAD_DOH, (f.results.single() as ResolverResult.Answer).transport)
        f.adapter.close(); f.adapter.close()
        assertEquals(1, f.closes)
    }

    @Test
    fun `saved mixed ladder returns actual Device and fixed plain transport evidence`() = runTest {
        for ((entry, transport) in listOf(
            ResolverSelectionPresets.DEVICE_PLAIN to ResolverTransport.DEVICE_TCP,
            plain() to ResolverTransport.PLAIN_TCP,
        )) {
            val f = Fixture(this, listOf(doh(), entry)) {
                ResolverResult.Answer(response(), transport)
            }
            f.adapter.resolve(QUERY, f.results::add); runCurrent()
            assertEquals(transport, (f.results.single() as ResolverResult.Answer).transport)
            f.adapter.close()
        }
    }

    @Test
    fun `wrong message ID question tunnel transport or unconfigured route fails closed`() = runTest {
        val malformed = listOf(
            ResolverResult.Answer(response().apply { this[0] = 0 }, ResolverTransport.DOT),
            ResolverResult.Answer(response().apply { this[13] = 'E'.code.toByte() }, ResolverTransport.DOT),
            ResolverResult.Answer(response(), ResolverTransport.WIREGUARD_UDP),
            ResolverResult.Answer(response(), ResolverTransport.DEVICE_UDP),
            ResolverResult.Answer(response().apply { this[2] = 0x83.toByte() }, ResolverTransport.DOT),
        )
        for (answer in malformed) {
            val f = Fixture(this, listOf(dot())) { answer }
            f.adapter.resolve(QUERY, f.results::add); runCurrent()
            assertEquals(ResolverFailureReason.INVALID_RESPONSE,
                (f.results.single() as ResolverResult.Failure).reason)
            f.adapter.close()
        }
    }

    @Test
    fun `retirement while an answer is pending returns authority loss instead of success`() = runTest {
        val answer = CompletableDeferred<ResolverResult>()
        val f = Fixture(this, listOf(dot())) { answer.await() }
        f.adapter.resolve(QUERY, f.results::add); runCurrent()
        f.current = false
        answer.complete(ResolverResult.Answer(response(), ResolverTransport.DOT)); runCurrent()
        assertEquals(ResolverFailureReason.STALE_EPOCH,
            (f.results.single() as ResolverResult.Failure).reason)
        f.adapter.close()
    }

    @Test
    fun `per query cancel and owner close drain work without callback on another screen`() = runTest {
        for (closeOwner in listOf(false, true)) {
            var drained = 0
            val f = Fixture(this, listOf(dot())) {
                try { awaitCancellation() } finally { drained++ }
            }
            val handle = f.adapter.resolve(QUERY, f.results::add); runCurrent()
            if (closeOwner) f.adapter.close() else handle.close()
            runCurrent()
            assertEquals(1, drained)
            assertTrue(f.results.isEmpty())
            f.adapter.close()
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun `malformed request never starts transport and an unresponsive route has a hard budget`() = runTest {
        val malformed = Fixture(this, listOf(dot())) { error("invalid query reached transport") }
        malformed.adapter.resolve(byteArrayOf(0), malformed.results::add); runCurrent()
        assertEquals(0, malformed.calls)
        assertEquals(ResolverFailureReason.INVALID_RESPONSE,
            (malformed.results.single() as ResolverResult.Failure).reason)
        malformed.adapter.close()
        val silent = Fixture(this, listOf(dot())) { awaitCancellation() }
        silent.adapter.resolve(QUERY, silent.results::add); runCurrent()
        advanceTimeBy(12_001); runCurrent()
        assertEquals(ResolverFailureReason.TIMEOUT, (silent.results.single() as ResolverResult.Failure).reason)
        silent.adapter.close()
    }

    private class Fixture(scope: TestScope, entries: List<ResolverSelectionEntry>,
        private val answer: suspend (ResolverRequest) -> ResolverResult) {
        private val network = Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(7)
        val context = NetworkContext(network, 4, listOf(InetAddress.getByName("192.168.1.53")), false)
        private val plan = (ResolverPlanFactory.build(checkNotNull(ResolverSelection(entries.map { ResolverTier(it) })
            .toFilterResolverPlan()).configuration, context) as ResolverPlanBuildResult.Ready).plan
        val epoch = EngineEpoch(2, ConfigurationIdentity(9, "configuration"), "a".repeat(64), 3, 4, 5,
            plan.primaryResolverIdentity, plan.fallbackCoverage)
        var current = true
        var calls = 0
        var closes = 0
        var lastRequest: ResolverRequest? = null
        val results = mutableListOf<ResolverResult>()
        val adapter = SavedDnsChainedFallback(epoch, context, plan, ReadyDnsResolverFactory { suppliedEpoch, suppliedContext, suppliedPlan, _ ->
            assertSame(epoch, suppliedEpoch); assertSame(context, suppliedContext); assertSame(plan, suppliedPlan)
            object : OwnedReadyDnsResolver {
                override suspend fun resolve(request: ResolverRequest): ResolverResult {
                    calls++; lastRequest = request
                    return answer(request)
                }
                override fun close() { closes++ }
            }
        }, isCurrent = { current }, dispatcher = StandardTestDispatcher(scope.testScheduler))
    }

    /** Real coordinator/selection with cold transport seams; neither fake skips configured tiers. */
    private class OrderedFixture(scope: TestScope, tiers: List<ResolverTier>, deviceUnavailable: Boolean = false) {
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val network = testNetwork(77)
        private val context = NetworkContext(network, 4, listOf(InetAddress.getByName("192.168.1.53")), false, isWifi = true)
        private val plan = (ResolverPlanFactory.build(checkNotNull(ResolverSelection(tiers).toFilterResolverPlan()).configuration,
            context) as ResolverPlanBuildResult.Ready).plan
        private val epoch = EngineEpoch(2, ConfigurationIdentity(9, "configuration"), "a".repeat(64), 3, 4, 5,
            plan.primaryResolverIdentity, plan.fallbackCoverage)
        val order = mutableListOf<String>()
        val results = mutableListOf<ResolverResult>()
        val adapter = SavedDnsChainedFallback(epoch, context, plan, ReadyDnsResolverFactory { _, _, suppliedPlan, registry ->
            val device = if (suppliedPlan.hasDeviceRoute) DeviceDnsRoute { request, _ ->
                order += "device"
                val result = if (deviceUnavailable) ResolverResult.Failure(ResolverFailureReason.SOCKET_FAILED, local = true)
                    else ResolverResult.Answer(positive(request.query), ResolverTransport.DEVICE_UDP)
                DeviceDnsRouteResult(result, if (result is ResolverResult.Answer)
                    ResolverEndpointKey.DeviceAddress(context.dnsServers.single()) else null)
            } else null
            val public = if (suppliedPlan.hasNonDeviceRoutes) object : OrderedEncryptedDnsResolver {
                override fun resolverFor(identity: com.lavasec.app.foundation.health.PrimaryResolverIdentity): DnsResolver {
                    val leaf = suppliedPlan.orderedRoutes?.single { it.primaryResolverIdentity == identity } ?: suppliedPlan
                    return DnsResolver { request ->
                        order += "public"
                        ResolverResult.Answer(positive(request.query), if (leaf.primaryResolverKind == ResolverKind.PLAIN_DNS)
                            ResolverTransport.PLAIN_TCP else ResolverTransport.MULLVAD_DOH)
                    }
                }
                override suspend fun resolve(request: ResolverRequest): ResolverResult =
                    resolverFor(suppliedPlan.primaryResolverIdentity).resolve(request)
            } else null
            val coordinator = ResolverCoordinator(epoch, suppliedPlan, registry, device, public,
                DeviceResolverWedgeEvidence { false }, childDispatcher = dispatcher)
            object : OwnedReadyDnsResolver {
                override suspend fun resolve(request: ResolverRequest) = coordinator.resolve(request)
                override fun close() = Unit
            }
        }, dispatcher = dispatcher)
    }

    private companion object {
        val QUERY = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
        val PORTAL_QUERY = testDnsQuery("deltawifi.com")
        fun positive(query: ByteArray) = DnsMessage.bootstrapResponse(query, byteArrayOf(192.toByte(), 0, 2, 8), ByteArray(16))
        fun response() = QUERY.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() }
        fun doh() = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH,
            customDohUrl = "https://resolver.example/dns-query")
        fun dot() = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOT,
            customDotEndpoint = "tls://resolver.example:853")
        fun plain() = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.PLAIN_DNS,
            customPlainEndpoint = "192.168.1.54")
    }
}
