package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadyDnsResolverCompositionTest {
    @Test
    fun `Device-only composition retains exact inputs and stays cold`() = runBlocking {
        val fixture = CompositionFixture()
        val registry = RecordingRegistry()
        val device = RecordingDeviceRoute()
        val coordinator = RecordingResolver()
        val deviceCalls = AtomicInteger()
        val mullvadCalls = AtomicInteger()
        val coordinatorCalls = AtomicInteger()
        val wedge = DeviceResolverWedgeEvidence { false }
        val factory = ProductionReadyDnsResolverFactory(
            deviceRouteFactory = ReadyDnsDeviceRouteFactory {
                    epoch,
                    networkContext,
                    resolverPlan,
                    suppliedRegistry,
                ->
                deviceCalls.incrementAndGet()
                assertSame(fixture.epoch, epoch)
                assertSame(fixture.context, networkContext)
                assertSame(fixture.plan, resolverPlan)
                assertSame(registry, suppliedRegistry)
                device
            },
            mullvadRuntimeFactory = ReadyDnsMullvadRuntimeFactory { _, _, _, _ ->
                mullvadCalls.incrementAndGet()
                RecordingMullvadRuntime()
            },
            coordinatorFactory = ReadyDnsCoordinatorFactory {
                    epoch,
                    networkContext,
                    resolverPlan,
                    suppliedRegistry,
                    deviceRoute,
                    mullvadRoute,
                    suppliedWedge,
                ->
                coordinatorCalls.incrementAndGet()
                assertSame(fixture.epoch, epoch)
                assertSame(fixture.context, networkContext)
                assertSame(fixture.plan, resolverPlan)
                assertSame(registry, suppliedRegistry)
                assertSame(device, deviceRoute)
                assertNull(mullvadRoute)
                assertSame(wedge, suppliedWedge)
                coordinator
            },
            wedgeEvidence = wedge,
        )

        val bundle = factory.create(
            epoch = fixture.epoch,
            networkContext = fixture.context,
            resolverPlan = fixture.plan,
            registry = registry,
        )

        assertEquals(1, deviceCalls.get())
        assertEquals(0, mullvadCalls.get())
        assertEquals(1, coordinatorCalls.get())
        assertEquals(0, device.resolveCalls.get())
        assertEquals(0, coordinator.resolveCalls.get())
        registry.assertUnused()

        val result = bundle.resolve(fixture.request())

        assertSame(coordinator.result, result)
        assertEquals(1, coordinator.resolveCalls.get())
        assertEquals(0, device.resolveCalls.get())
        registry.assertUnused()

        bundle.close()
        bundle.close()
        registry.assertUnused()
    }

    @Test
    fun `Device retry-worthy failure reaches one Mullvad fallback through coordinator`() =
        runBlocking {
            val fixture = CompositionFixture(mullvadEnabled = true)
            val registry = GenerationSocketRegistry(fixture.epoch)
            val device = RecordingDeviceRoute(
                result = ResolverResult.Failure(ResolverFailureReason.TIMEOUT),
            )
            val mullvad = RecordingMullvadRuntime(
                resultFactory = { request ->
                    ResolverResult.Answer(
                        dnsMessage = response(request.query),
                        transport = ResolverTransport.MULLVAD_DOH,
                    )
                },
            )
            val factory = ProductionReadyDnsResolverFactory(
                deviceRouteFactory = ReadyDnsDeviceRouteFactory { _, _, _, _ -> device },
                mullvadRuntimeFactory =
                    ReadyDnsMullvadRuntimeFactory { _, _, _, _ -> mullvad },
                coordinatorFactory = ReadyDnsCoordinatorFactory {
                        epoch,
                        _,
                        resolverPlan,
                        suppliedRegistry,
                        deviceRoute,
                        mullvadRoute,
                        wedge,
                    ->
                    ResolverCoordinator(
                        epoch = epoch,
                        plan = resolverPlan,
                        registry = suppliedRegistry,
                        deviceRoute = deviceRoute,
                        mullvadRoute = mullvadRoute,
                        wedgeEvidence = wedge,
                    )
                },
                wedgeEvidence = DeviceResolverWedgeEvidence { false },
            )
            val bundle = factory.create(
                fixture.epoch,
                fixture.context,
                fixture.plan,
                registry,
            )

            val result = bundle.resolve(fixture.request())

            assertTrue(result is ResolverResult.Answer)
            assertEquals(
                ResolverTransport.MULLVAD_DOH,
                (result as ResolverResult.Answer).transport,
            )
            assertEquals(1, device.resolveCalls.get())
            assertEquals(1, mullvad.resolveCalls.get())

            registry.close()
            bundle.close()
            assertEquals(1, mullvad.closeCalls.get())
        }

    @Test
    fun `Mullvad primary with requested unusable Device DNS builds no Device route`() {
        val context = testNetworkContext(dnsServers = emptyList())
        val plan = ResolverPlan(
            network = context.network,
            networkGeneration = context.networkGeneration,
            isCellular = context.isCellular,
            deviceDnsServers = emptyList(),
            mullvadEnabled = true,
            primaryResolverKind = ResolverKind.MULLVAD_DOH,
            deviceDnsRequested = true,
            primaryResolverIdentity = PrimaryResolverIdentity(MullvadDohEndpoint.id),
            fallbackCoverage = ResolverFallbackCoverage.NONE,
        )
        val epoch = testEpoch(resolverPlan = plan)
        val runtime = RecordingMullvadRuntime()
        val deviceCalls = AtomicInteger()
        val coordinatorDevice = AtomicReference<DeviceDnsRoute?>()
        val coordinatorMullvad = AtomicReference<DnsResolver?>()
        val factory = ProductionReadyDnsResolverFactory(
            deviceRouteFactory = ReadyDnsDeviceRouteFactory { _, _, _, _ ->
                deviceCalls.incrementAndGet()
                RecordingDeviceRoute()
            },
            mullvadRuntimeFactory = ReadyDnsMullvadRuntimeFactory {
                    suppliedEpoch,
                    suppliedContext,
                    suppliedPlan,
                    _,
                ->
                assertSame(epoch, suppliedEpoch)
                assertSame(context, suppliedContext)
                assertSame(plan, suppliedPlan)
                runtime
            },
            coordinatorFactory = ReadyDnsCoordinatorFactory {
                    _,
                    _,
                    _,
                    _,
                    deviceRoute,
                    mullvadRoute,
                    _,
                ->
                coordinatorDevice.set(deviceRoute)
                coordinatorMullvad.set(mullvadRoute)
                RecordingResolver()
            },
            wedgeEvidence = DeviceResolverWedgeEvidence { false },
        )

        val bundle = factory.create(epoch, context, plan, RecordingRegistry())

        assertEquals(0, deviceCalls.get())
        assertNull(coordinatorDevice.get())
        assertSame(runtime.resolver, coordinatorMullvad.get())

        bundle.close()
        assertEquals(1, runtime.closeCalls.get())
    }

    @Test
    fun `noncanonical resolver inputs fail before every component factory`() {
        val context = testNetworkContext()
        val malformed = ResolverPlan(
            network = context.network,
            networkGeneration = context.networkGeneration,
            isCellular = context.isCellular,
            deviceDnsServers = context.dnsServers,
            mullvadEnabled = false,
            primaryResolverKind = ResolverKind.DEVICE,
            primaryResolverIdentity = PrimaryResolverIdentity("forged"),
            fallbackCoverage = ResolverFallbackCoverage.NONE,
        )
        val epoch = testEpoch(resolverPlan = malformed)
        val componentCalls = AtomicInteger()
        val registry = RecordingRegistry()
        val factory = ProductionReadyDnsResolverFactory(
            deviceRouteFactory = ReadyDnsDeviceRouteFactory { _, _, _, _ ->
                componentCalls.incrementAndGet()
                RecordingDeviceRoute()
            },
            mullvadRuntimeFactory = ReadyDnsMullvadRuntimeFactory { _, _, _, _ ->
                componentCalls.incrementAndGet()
                RecordingMullvadRuntime()
            },
            coordinatorFactory = ReadyDnsCoordinatorFactory { _, _, _, _, _, _, _ ->
                componentCalls.incrementAndGet()
                RecordingResolver()
            },
            wedgeEvidence = DeviceResolverWedgeEvidence { false },
        )

        assertThrows(IllegalArgumentException::class.java) {
            factory.create(epoch, context, malformed, registry)
        }

        assertEquals(0, componentCalls.get())
        registry.assertUnused()
    }

    @Test
    fun `coordinator construction failure closes Mullvad and suppresses close failure`() {
        val fixture = CompositionFixture(mullvadEnabled = true)
        val constructionFailure = IllegalStateException("coordinator failed")
        val closeFailure = IllegalArgumentException("Mullvad close failed")
        val runtime = RecordingMullvadRuntime(closeFailure = closeFailure)
        val registry = RecordingRegistry()
        val factory = ProductionReadyDnsResolverFactory(
            deviceRouteFactory =
                ReadyDnsDeviceRouteFactory { _, _, _, _ -> RecordingDeviceRoute() },
            mullvadRuntimeFactory =
                ReadyDnsMullvadRuntimeFactory { _, _, _, _ -> runtime },
            coordinatorFactory = ReadyDnsCoordinatorFactory { _, _, _, _, _, _, _ ->
                throw constructionFailure
            },
            wedgeEvidence = DeviceResolverWedgeEvidence { false },
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            factory.create(
                fixture.epoch,
                fixture.context,
                fixture.plan,
                registry,
            )
        }

        assertSame(constructionFailure, thrown)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
        assertEquals(1, runtime.closeCalls.get())
        registry.assertUnused()
    }

    @Test
    fun `concurrent and repeated bundle close shares one visible Mullvad failure`() {
        val fixture = CompositionFixture(mullvadEnabled = true)
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val closeFailure = IllegalStateException("bounded drain not proved")
        val runtime = RecordingMullvadRuntime(
            onClose = {
                closeEntered.countDown()
                allowClose.awaitOrFail()
            },
            closeFailure = closeFailure,
        )
        val factory = ProductionReadyDnsResolverFactory(
            deviceRouteFactory =
                ReadyDnsDeviceRouteFactory { _, _, _, _ -> RecordingDeviceRoute() },
            mullvadRuntimeFactory =
                ReadyDnsMullvadRuntimeFactory { _, _, _, _ -> runtime },
            coordinatorFactory =
                ReadyDnsCoordinatorFactory { _, _, _, _, _, _, _ -> RecordingResolver() },
            wedgeEvidence = DeviceResolverWedgeEvidence { false },
        )
        val bundle = factory.create(
            fixture.epoch,
            fixture.context,
            fixture.plan,
            RecordingRegistry(),
        )
        val failures = CopyOnWriteArrayList<Throwable>()
        val first = Thread {
            try {
                bundle.close()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        val second = Thread {
            try {
                bundle.close()
            } catch (failure: Throwable) {
                failures += failure
            }
        }

        first.start()
        closeEntered.awaitOrFail()
        second.start()
        assertFalse(second.joinWithin(100))
        allowClose.countDown()
        first.joinOrFail()
        second.joinOrFail()

        assertEquals(1, runtime.closeCalls.get())
        assertEquals(2, failures.size)
        assertTrue(failures.all { it === closeFailure })
        val repeated = assertThrows(IllegalStateException::class.java) { bundle.close() }
        assertSame(closeFailure, repeated)
        assertEquals(1, runtime.closeCalls.get())
    }

    @Test
    fun `ordinary production constructor performs no socket or registry work`() {
        val fixture = CompositionFixture(mullvadEnabled = true)
        val sockets = RecordingDeviceSocketPlatform()
        val protectCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val registry = RecordingRegistry()
        val factory = ProductionReadyDnsResolverFactory(
            deviceSocketPlatform = sockets,
            dohSocketProtector = DohSocketProtector {
                protectCalls.incrementAndGet()
                true
            },
            dohSocketBinder = DohSocketBinder { _, _ -> bindCalls.incrementAndGet() },
            wedgeEvidence = DeviceResolverWedgeEvidence { false },
            clock = TestMonotonicClock(),
        )

        val bundle = factory.create(
            fixture.epoch,
            fixture.context,
            fixture.plan,
            registry,
        )

        assertEquals(0, sockets.createCalls.get())
        assertEquals(0, protectCalls.get())
        assertEquals(0, bindCalls.get())
        registry.assertUnused()

        bundle.close()
        registry.assertUnused()
    }

    @Test
    fun `composed owner drains registry work before closing Mullvad runtime`() {
        val fixture = CompositionFixture(mullvadEnabled = true)
        val resolverEntered = CountDownLatch(1)
        val resourceClosed = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val resolveReturned = CountDownLatch(1)
        val resourceCloseCalls = AtomicInteger()
        val events = CopyOnWriteArrayList<String>()
        val result = AtomicReference<ResolverResult>()
        val runtime = RecordingMullvadRuntime(
            onClose = {
                assertEquals(1, resourceCloseCalls.get())
                events += "Mullvad"
            },
        )
        val factory = ProductionReadyDnsResolverFactory(
            deviceRouteFactory =
                ReadyDnsDeviceRouteFactory { _, _, _, _ -> RecordingDeviceRoute() },
            mullvadRuntimeFactory =
                ReadyDnsMullvadRuntimeFactory { _, _, _, _ -> runtime },
            coordinatorFactory = ReadyDnsCoordinatorFactory {
                    _,
                    _,
                    _,
                    registry,
                    _,
                    _,
                    _,
                ->
                DnsResolver { request ->
                    val resource = AutoCloseable {
                        resourceCloseCalls.incrementAndGet()
                        events += "registry"
                        resourceClosed.countDown()
                    }
                    check(registry.registerIfCurrent(resource, request.epoch))
                    resolverEntered.countDown()
                    resourceClosed.awaitOrFail()
                    ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
                }
            },
            wedgeEvidence = DeviceResolverWedgeEvidence { false },
        )
        val engine = ReadyDnsEngineOwner.create(fixture.candidate(), factory)
        val resolverThread = Thread {
            try {
                result.set(runBlocking { engine.resolveIfCurrent(fixture.request()) })
            } finally {
                resolveReturned.countDown()
            }
        }
        val closeThread = Thread {
            try {
                engine.close()
            } finally {
                closeReturned.countDown()
            }
        }

        resolverThread.start()
        resolverEntered.awaitOrFail()
        closeThread.start()
        resourceClosed.awaitOrFail()
        resolveReturned.awaitOrFail()
        closeReturned.awaitOrFail()
        resolverThread.joinOrFail()
        closeThread.joinOrFail()

        assertEquals(listOf("registry", "Mullvad"), events)
        assertEquals(1, resourceCloseCalls.get())
        assertEquals(1, runtime.closeCalls.get())
        assertTrue(result.get() is ResolverResult.Failure)
        assertEquals(
            ResolverFailureReason.STALE_EPOCH,
            (result.get() as ResolverResult.Failure).reason,
        )
        assertFalse(engine.isCurrent())
    }

    @Test
    fun `real engine owner constructs composed resolver and surfaces bundle close failure`() =
        runBlocking {
            val fixture = CompositionFixture()
            val closeFailure = IllegalStateException("resolver bundle close failed")
            val coordinator = RecordingResolver(
                result = ResolverResult.Failure(ResolverFailureReason.TIMEOUT),
            )
            val registrySeen = AtomicReference<ResolverResourceRegistry>()
            val factory = ProductionReadyDnsResolverFactory(
                deviceRouteFactory = ReadyDnsDeviceRouteFactory {
                        _,
                        _,
                        _,
                        registry,
                    ->
                    registrySeen.set(registry)
                    RecordingDeviceRoute()
                },
                mullvadRuntimeFactory =
                    ReadyDnsMullvadRuntimeFactory { _, _, _, _ ->
                        error("Device-only plan must not create Mullvad")
                    },
                coordinatorFactory = ReadyDnsCoordinatorFactory {
                        _,
                        _,
                        _,
                        registry,
                        _,
                        _,
                        _,
                    ->
                    RecordingResolver(
                        resultFactory = { request ->
                            assertTrue(registry.isCurrent(request.epoch))
                            coordinator.result
                        },
                    )
                },
                wedgeEvidence = DeviceResolverWedgeEvidence { false },
            )
            val failingFactory = ReadyDnsResolverFactory { epoch, context, plan, registry ->
                val delegate = factory.create(epoch, context, plan, registry)
                object : OwnedReadyDnsResolver {
                    override suspend fun resolve(request: ResolverRequest): ResolverResult =
                        delegate.resolve(request)

                    override fun close() {
                        delegate.close()
                        throw closeFailure
                    }
                }
            }
            val engine = ReadyDnsEngineOwner.create(fixture.candidate(), failingFactory)

            assertTrue(requireNotNull(registrySeen.get()).isCurrent(fixture.epoch))
            val result = engine.resolveIfCurrent(fixture.request())
            assertSame(coordinator.result, result)

            val first = assertThrows(IllegalStateException::class.java) { engine.close() }
            val second = assertThrows(IllegalStateException::class.java) { engine.close() }
            assertSame(closeFailure, first)
            assertSame(closeFailure, second)
            assertFalse(engine.isCurrent())
        }
}

private class CompositionFixture(
    mullvadEnabled: Boolean = false,
) {
    private val harness = DnsProxyHarness()
    val context = testNetworkContext()
    val plan = testResolverPlan(context, mullvadEnabled = mullvadEnabled)
    val epoch = testEpoch(
        policyIdentity = harness.policyIdentity,
        resolverPlan = plan,
    )

    fun request(): ResolverRequest {
        val query = testDnsQuery()
        return ResolverRequest(
            query = query,
            question = DnsMessage.parseQuestion(query),
            epoch = epoch,
            networkContext = context,
            queryId = 817L,
        )
    }

    fun candidate() = ReadyDnsEngineCandidate(
        epoch = epoch,
        networkContext = context,
        resolverPlan = plan,
        policy = TestOwnedDnsPolicyRuntime(harness.policyIdentity, harness.proxy),
    )
}

private class RecordingResolver(
    val result: ResolverResult = ResolverResult.Failure(ResolverFailureReason.TIMEOUT),
    private val resultFactory: ((ResolverRequest) -> ResolverResult)? = null,
) : DnsResolver {
    val resolveCalls = AtomicInteger()

    override suspend fun resolve(request: ResolverRequest): ResolverResult {
        resolveCalls.incrementAndGet()
        return resultFactory?.invoke(request) ?: result
    }
}

private class RecordingDeviceRoute(
    private val result: ResolverResult = ResolverResult.Failure(
        ResolverFailureReason.TIMEOUT,
    ),
) : DeviceDnsRoute {
    val resolveCalls = AtomicInteger()

    override suspend fun resolve(
        request: ResolverRequest,
        endpointBackoff: ResolverEndpointBackoffPolicy,
    ): DeviceDnsRouteResult {
        resolveCalls.incrementAndGet()
        return DeviceDnsRouteResult(
            result = result,
            answeredEndpoint = if (result is ResolverResult.Answer) {
                ResolverEndpointKey.DeviceAddress(
                    DeviceDnsAddressPolicy.select(
                        request.networkContext.dnsServers,
                        request.networkContext.isCellular,
                    ).first(),
                )
            } else {
                null
            },
        )
    }
}

private class RecordingMullvadRuntime(
    resultFactory: ((ResolverRequest) -> ResolverResult)? = null,
    private val onClose: () -> Unit = {},
    private val closeFailure: Throwable? = null,
) : ReadyDnsMullvadRuntime {
    val closeCalls = AtomicInteger()
    val route = RecordingResolver(resultFactory = resultFactory)
    override val resolver: DnsResolver = route
    val resolveCalls: AtomicInteger
        get() = route.resolveCalls

    override fun close() {
        closeCalls.incrementAndGet()
        onClose()
        closeFailure?.let { throw it }
    }
}

private class RecordingRegistry : ResolverResourceRegistry {
    val registerCalls = AtomicInteger()
    val currentCalls = AtomicInteger()
    val unregisterCalls = AtomicInteger()
    val completeCalls = AtomicInteger()

    override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean {
        registerCalls.incrementAndGet()
        return false
    }

    override fun isCurrent(epoch: EngineEpoch): Boolean {
        currentCalls.incrementAndGet()
        return true
    }

    override fun unregister(resource: AutoCloseable): Boolean {
        unregisterCalls.incrementAndGet()
        return false
    }

    override fun completeIfCurrent(
        resource: AutoCloseable,
        epoch: EngineEpoch,
        completion: () -> Boolean,
    ): Boolean {
        completeCalls.incrementAndGet()
        return false
    }

    fun assertUnused() {
        assertEquals(0, registerCalls.get())
        assertEquals(0, currentCalls.get())
        assertEquals(0, unregisterCalls.get())
        assertEquals(0, completeCalls.get())
    }
}

private class RecordingDeviceSocketPlatform : DeviceDnsSocketPlatform {
    val createCalls = AtomicInteger()

    override fun createUdp(): DeviceUdpSocket {
        createCalls.incrementAndGet()
        error("construction must not create a UDP socket")
    }

    override fun createTcp(): DeviceTcpSocket {
        createCalls.incrementAndGet()
        error("construction must not create a TCP socket")
    }

    override fun protect(socket: DeviceDnsSocket): Boolean =
        error("construction must not protect a socket")

    override fun bind(socket: DeviceDnsSocket, network: android.net.Network) =
        error("construction must not bind a socket")
}

private fun response(query: ByteArray): ByteArray = query.copyOf().also { response ->
    response[2] = 0x81.toByte()
    response[3] = 0x80.toByte()
}

private fun CountDownLatch.awaitOrFail() {
    assertTrue("timed out waiting for deterministic gate", await(2, TimeUnit.SECONDS))
}

private fun Thread.joinWithin(timeoutMillis: Long): Boolean {
    join(timeoutMillis)
    return !isAlive
}

private fun Thread.joinOrFail() {
    join(2_000)
    assertFalse("thread did not terminate", isAlive)
}
