package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds
import com.lavasec.app.platform.ConfigurationIdentity
import java.net.InetAddress
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadyDnsEngineOwnerTest {
    @Test
    fun `an adapter without explicit primary probing refuses and preserves exact current ownership`() = runBlocking {
        val fixture = CandidateFixture()
        val factory = RecordingResolverFactory()
        val engine = ReadyDnsEngineOwner.create(fixture.candidate, factory)
        assertEquals(ResolverFailureReason.OVERLOADED,
            (engine.resolvePrimaryIfCurrent(request(engine)) as ResolverResult.Failure).reason)
        assertEquals(0, factory.runtimeFor(engine.epoch).resolveCalls.get())
        val captured = request(engine)
        assertStale(engine.resolvePrimaryIfCurrent(ResolverRequest(captured.query, captured.question,
            captured.epoch.copy(), captured.networkContext, captured.queryId)))
        engine.close()
        assertStale(engine.resolvePrimaryIfCurrent(captured))
        assertEquals(0, factory.runtimeFor(engine.epoch).resolveCalls.get())
    }

    @Test
    fun `coherent construction retains exact identity and activates only after factory return`() {
        val fixture = CandidateFixture()
        val factory = RecordingResolverFactory()
        val prePublicationResource = CountingResource()
        val registryReference = AtomicReference<ResolverResourceRegistry>()
        factory.onCreate = { epoch, _, _, registry ->
            registryReference.set(registry)
            assertFalse(registry.isCurrent(epoch))
            assertFalse(registry.registerIfCurrent(prePublicationResource, epoch))
            assertEquals(1, prePublicationResource.closeCalls.get())
        }

        val engine = ReadyDnsEngineOwner.create(fixture.candidate, factory)

        assertSame(fixture.candidate.epoch, engine.epoch)
        assertSame(fixture.candidate.networkContext, engine.networkContext)
        assertSame(fixture.candidate.resolverPlan, engine.resolverPlan)
        assertSame(fixture.candidate.policyIdentity, engine.policyIdentity)
        assertSame(fixture.candidate.proxy, engine.proxy)
        assertSame(engine.networkContext.network, engine.resolverPlan.network)
        assertTrue(engine.isCurrent())
        assertTrue(requireNotNull(registryReference.get()).isCurrent(engine.epoch))

        val activeResource = CountingResource()
        assertTrue(
            requireNotNull(registryReference.get())
                .registerIfCurrent(activeResource, engine.epoch),
        )
        engine.close()

        assertEquals(1, activeResource.closeCalls.get())
        assertEquals(1, factory.runtimeFor(engine.epoch).closeCalls.get())
    }

    @Test
    fun `incoherent candidates fail before resolver construction`() {
        val fixture = CandidateFixture()
        val base = fixture.candidate
        val otherNetworkContext = testNetworkContext(
            network = base.networkContext.network,
            networkGeneration = base.networkContext.networkGeneration,
            dnsServers = listOf(InetAddress.getByName("192.0.2.54")),
            isCellular = base.networkContext.isCellular,
        )
        val incoherent = listOf(
            fixture.candidate(
                epoch = base.epoch.copy(
                    configurationIdentity = ConfigurationIdentity(99, "foreign"),
                ),
            ),
            fixture.candidate(epoch = base.epoch.copy(artifactDigest = "foreign")),
            fixture.candidate(epoch = base.epoch.copy(policyRevision = 99)),
            fixture.candidate(
                networkContext = testNetworkContext(
                    network = base.networkContext.network,
                    networkGeneration = base.networkContext.networkGeneration + 1,
                    dnsServers = base.networkContext.dnsServers,
                    isCellular = base.networkContext.isCellular,
                ),
                resolverPlan = testResolverPlan(
                    testNetworkContext(
                        network = base.networkContext.network,
                        networkGeneration = base.networkContext.networkGeneration + 1,
                        dnsServers = base.networkContext.dnsServers,
                        isCellular = base.networkContext.isCellular,
                    ),
                ),
            ),
            fixture.candidate(
                resolverPlan = testResolverPlan(
                    base.networkContext,
                    network = testNetwork(404),
                ),
            ),
            fixture.candidate(
                resolverPlan = testResolverPlan(
                    base.networkContext,
                    networkGeneration = base.networkContext.networkGeneration + 1,
                ),
            ),
            fixture.candidate(
                resolverPlan = testResolverPlan(
                    base.networkContext,
                    isCellular = !base.networkContext.isCellular,
                ),
            ),
            fixture.candidate(
                resolverPlan = testResolverPlan(otherNetworkContext),
            ),
            fixture.candidate(
                epoch = base.epoch.copy(
                    primaryResolverIdentity = PrimaryResolverIdentity("foreign"),
                ),
            ),
            fixture.candidate(
                epoch = base.epoch.copy(
                    fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                ),
            ),
            fixture.candidate(epoch = base.epoch.copy(sessionGeneration = 0)),
        )

        incoherent.forEach { candidate ->
            val factory = RecordingResolverFactory()
            assertThrows(IllegalArgumentException::class.java) {
                ReadyDnsEngineOwner.create(candidate, factory)
            }
            assertEquals(0, factory.createCalls.get())
        }
    }

    @Test
    fun `noncanonical ready route shapes fail before resolver construction`() {
        val fixture = CandidateFixture()
        val base = fixture.candidate
        val context = base.networkContext
        val canonicalDeviceIdentity = base.resolverPlan.primaryResolverIdentity
        val mullvadIdentity = PrimaryResolverIdentity(BuiltInDohResolverIds.MULLVAD)
        fun plan(
            servers: List<InetAddress>,
            mullvadEnabled: Boolean,
            primary: ResolverKind,
            deviceDnsRequested: Boolean = primary == ResolverKind.DEVICE,
            identity: PrimaryResolverIdentity,
            fallback: ResolverFallbackCoverage,
        ) = ResolverPlan(
            network = context.network,
            networkGeneration = context.networkGeneration,
            isCellular = context.isCellular,
            deviceDnsServers = servers,
            mullvadEnabled = mullvadEnabled,
            primaryResolverKind = primary,
            deviceDnsRequested = deviceDnsRequested,
            primaryResolverIdentity = identity,
            fallbackCoverage = fallback,
        )

        val malformedPlans = listOf(
            plan(
                servers = emptyList(),
                mullvadEnabled = false,
                primary = ResolverKind.DEVICE,
                deviceDnsRequested = false,
                identity = PrimaryResolverIdentity("device-dns:v4=;v6="),
                fallback = ResolverFallbackCoverage.NONE,
            ),
            plan(
                servers = base.resolverPlan.deviceDnsServers,
                mullvadEnabled = false,
                primary = ResolverKind.DEVICE,
                identity = PrimaryResolverIdentity("device-dns:forged"),
                fallback = ResolverFallbackCoverage.NONE,
            ),
            plan(
                servers = base.resolverPlan.deviceDnsServers,
                mullvadEnabled = false,
                primary = ResolverKind.DEVICE,
                identity = canonicalDeviceIdentity,
                fallback = ResolverFallbackCoverage.ENCRYPTED_DNS,
            ),
            plan(
                servers = emptyList(),
                mullvadEnabled = false,
                primary = ResolverKind.MULLVAD_DOH,
                identity = mullvadIdentity,
                fallback = ResolverFallbackCoverage.NONE,
            ),
            plan(
                servers = base.resolverPlan.deviceDnsServers,
                mullvadEnabled = true,
                primary = ResolverKind.MULLVAD_DOH,
                deviceDnsRequested = true,
                identity = mullvadIdentity,
                fallback = ResolverFallbackCoverage.NONE,
            ),
            plan(
                servers = emptyList(),
                mullvadEnabled = true,
                primary = ResolverKind.MULLVAD_DOH,
                identity = PrimaryResolverIdentity("mullvad-forged"),
                fallback = ResolverFallbackCoverage.NONE,
            ),
            plan(
                servers = emptyList(),
                mullvadEnabled = true,
                primary = ResolverKind.MULLVAD_DOH,
                identity = mullvadIdentity,
                fallback = ResolverFallbackCoverage.ENCRYPTED_DNS,
            ),
        )

        malformedPlans.forEach { malformed ->
            val factory = RecordingResolverFactory()
            val candidate = fixture.candidate(
                epoch = base.epoch.copy(
                    primaryResolverIdentity = malformed.primaryResolverIdentity,
                    fallbackCoverage = malformed.fallbackCoverage,
                ),
                resolverPlan = malformed,
            )
            assertThrows(IllegalArgumentException::class.java) {
                ReadyDnsEngineOwner.create(candidate, factory)
            }
            assertEquals(0, factory.createCalls.get())
        }
    }

    @Test
    fun `factory failure publishes no engine and consumes cold resources`() {
        val fixture = CandidateFixture()
        val resource = CountingResource()
        val registryReference = AtomicReference<ResolverResourceRegistry>()
        val failure = IllegalStateException("resolver construction failed")
        val factory = ReadyDnsResolverFactory { epoch, _, _, registry ->
            registryReference.set(registry)
            assertFalse(registry.registerIfCurrent(resource, epoch))
            throw failure
        }

        val thrown = assertThrows(IllegalStateException::class.java) {
            ReadyDnsEngineOwner.create(fixture.candidate, factory)
        }

        assertSame(failure, thrown)
        assertEquals(1, resource.closeCalls.get())
        val late = CountingResource()
        assertFalse(
            requireNotNull(registryReference.get())
                .registerIfCurrent(late, fixture.candidate.epoch),
        )
        assertEquals(1, late.closeCalls.get())
    }

    @Test
    fun `resolution requires exact epoch and network context identity`() = runBlocking {
        val fixture = CandidateFixture()
        val terminal = ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
        val factory = RecordingResolverFactory().apply {
            result = terminal
        }
        val engine = ReadyDnsEngineOwner.create(fixture.candidate, factory)
        val runtime = factory.runtimeFor(engine.epoch)
        val exact = request(engine)
        val equalEpoch = ResolverRequest(
            query = exact.query,
            question = exact.question,
            epoch = engine.epoch.copy(),
            networkContext = engine.networkContext,
            queryId = exact.queryId,
        )
        val equalContext = ResolverRequest(
            query = exact.query,
            question = exact.question,
            epoch = engine.epoch,
            networkContext = testNetworkContext(
                network = engine.networkContext.network,
                networkGeneration = engine.networkContext.networkGeneration,
                dnsServers = engine.networkContext.dnsServers,
                isCellular = engine.networkContext.isCellular,
            ),
            queryId = exact.queryId,
        )

        assertStale(engine.resolveIfCurrent(equalEpoch))
        assertStale(engine.resolveIfCurrent(equalContext))
        assertEquals(0, runtime.resolveCalls.get())
        assertSame(terminal, engine.resolveIfCurrent(exact))
        assertEquals(1, runtime.resolveCalls.get())

        engine.close()

        assertStale(engine.resolveIfCurrent(exact))
        assertEquals(1, runtime.resolveCalls.get())
    }

    @Test
    fun `admitted resolve cannot touch a closed resolver bundle`() {
        val fixture = CandidateFixture()
        val resolverEntered = CountDownLatch(1)
        val allowRegistryAdmission = CountDownLatch(1)
        val resolveReturned = CountDownLatch(1)
        val registryResource = CountingResource()
        val bundleClosed = CountDownLatch(1)
        val result = AtomicReference<ResolverResult>()
        val factory = RecordingResolverFactory().apply {
            resolverHandler = { request, registry ->
                resolverEntered.countDown()
                allowRegistryAdmission.awaitOrFail()
                val admitted = registry.registerIfCurrent(registryResource, request.epoch)
                if (admitted) {
                    check(registry.unregister(registryResource))
                    registryResource.close()
                    ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
                } else {
                    ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
                }
            }
            onRuntimeClose = { bundleClosed.countDown() }
        }
        val engine = ReadyDnsEngineOwner.create(fixture.candidate, factory)
        val resolverThread = Thread {
            try {
                result.set(runBlocking { engine.resolveIfCurrent(request(engine)) })
            } finally {
                resolveReturned.countDown()
            }
        }
        val closeReturned = CountDownLatch(1)
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
        awaitCondition { !engine.isCurrent() }
        assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
        assertEquals(0, factory.runtimeFor(engine.epoch).closeCalls.get())

        allowRegistryAdmission.countDown()
        resolveReturned.awaitOrFail()
        closeReturned.awaitOrFail()
        resolverThread.joinOrFail()
        closeThread.joinOrFail()

        assertStale(requireNotNull(result.get()))
        assertEquals(1, registryResource.closeCalls.get())
        assertEquals(1, factory.runtimeFor(engine.epoch).closeCalls.get())
        assertEquals(0L, bundleClosed.count)
    }

    @Test
    fun `resolver lifecycle reentry follows dispatcher hop and fails fast`() {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "resolver-dispatch-hop").apply { isDaemon = true }
        }.asCoroutineDispatcher().use { resolverDispatcher ->
            val fixture = CandidateFixture()
            val engineReference = AtomicReference<ReadyDnsEngineOwner>()
            val factory = RecordingResolverFactory().apply {
                resolverHandler = { _, _ ->
                    val entryThread = Thread.currentThread()
                    withContext(resolverDispatcher) {
                        assertTrue(Thread.currentThread() !== entryThread)
                        engineReference.get().close()
                    }
                    ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
                }
            }
            val engine = ReadyDnsEngineOwner.create(fixture.candidate, factory)
            engineReference.set(engine)
            val returned = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val resolverThread = Thread {
                try {
                    runBlocking { engine.resolveIfCurrent(request(engine)) }
                } catch (thrown: Throwable) {
                    failure.set(thrown)
                } finally {
                    returned.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }

            returned.awaitOrFail()
            resolverThread.joinOrFail()

            assertTrue(failure.get() is IllegalStateException)
            assertEquals(
                "engine close cannot re-enter an admitted resolver call",
                failure.get()?.message,
            )
            assertTrue(engine.isCurrent())
            engine.close()
        }
    }

    @Test
    fun `publication is synchronous exact result and permits read only current reentry`() {
        val fixture = CandidateFixture()
        val engine = ReadyDnsEngineOwner.create(fixture.candidate, RecordingResolverFactory())
        val invokingThread = Thread.currentThread()
        val callbackCalls = AtomicInteger()

        assertFalse(
            engine.completeIfCurrent {
                callbackCalls.incrementAndGet()
                assertSame(invokingThread, Thread.currentThread())
                assertTrue(engine.isCurrent())
                false
            },
        )
        assertTrue(
            engine.completeIfCurrent {
                callbackCalls.incrementAndGet()
                assertTrue(engine.isCurrent())
                true
            },
        )
        assertEquals(2, callbackCalls.get())

        engine.close()
        assertFalse(engine.completeIfCurrent { throw AssertionError("retired callback invoked") })
    }

    @Test
    fun `close retires before waiting for an entered publication`() {
        val fixture = CandidateFixture()
        val factory = RecordingResolverFactory()
        val engine = ReadyDnsEngineOwner.create(fixture.candidate, factory)
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val callbackResult = AtomicReference<Boolean>()
        val publicationReturned = CountDownLatch(1)
        val publicationThread = Thread {
            try {
                callbackResult.set(
                    engine.completeIfCurrent {
                        callbackEntered.countDown()
                        callbackRelease.awaitOrFail()
                        assertFalse(engine.isCurrent())
                        true
                    },
                )
            } finally {
                publicationReturned.countDown()
            }
        }
        val closeReturned = CountDownLatch(1)
        val closeThread = Thread {
            try {
                engine.close()
            } finally {
                closeReturned.countDown()
            }
        }

        publicationThread.start()
        callbackEntered.awaitOrFail()
        closeThread.start()
        awaitCondition { !engine.isCurrent() }
        assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
        assertEquals(0, factory.runtimeFor(engine.epoch).closeCalls.get())

        callbackRelease.countDown()
        publicationReturned.awaitOrFail()
        closeReturned.awaitOrFail()
        publicationThread.joinOrFail()
        closeThread.joinOrFail()

        assertTrue(callbackResult.get())
        assertEquals(1, factory.runtimeFor(engine.epoch).closeCalls.get())
    }

    @Test
    fun `publication close reentry fails fast without retiring the engine`() {
        val fixture = CandidateFixture()
        val engine = ReadyDnsEngineOwner.create(fixture.candidate, RecordingResolverFactory())

        assertTrue(
            engine.completeIfCurrent {
                val failure = assertThrows(IllegalStateException::class.java) {
                    engine.close()
                }
                assertEquals(
                    "engine close cannot re-enter an admitted publication callback",
                    failure.message,
                )
                assertTrue(engine.isCurrent())
                true
            },
        )
        assertTrue(engine.isCurrent())

        engine.close()
        assertFalse(engine.isCurrent())
    }

    @Test
    fun `concurrent close callers share one physical drain`() {
        val fixture = CandidateFixture()
        val factory = RecordingResolverFactory()
        val engine = ReadyDnsEngineOwner.create(fixture.candidate, factory)
        val registry = factory.registryFor(engine.epoch)
        val resourceCloseEntered = CountDownLatch(1)
        val allowResourceClose = CountDownLatch(1)
        val reentrantFailure = AtomicReference<Throwable>()
        val resource = CountingResource {
            resourceCloseEntered.countDown()
            reentrantFailure.set(
                assertThrows(IllegalStateException::class.java) {
                    engine.close()
                },
            )
            allowResourceClose.awaitOrFail()
        }
        assertTrue(registry.registerIfCurrent(resource, engine.epoch))
        val start = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val callers = List(8) {
            Thread {
                start.awaitOrFail()
                try {
                    engine.close()
                } catch (failure: Throwable) {
                    failures += failure
                }
            }.apply { start() }
        }

        start.countDown()
        resourceCloseEntered.awaitOrFail()
        assertFalse(engine.isCurrent())
        allowResourceClose.countDown()
        callers.forEach(Thread::joinOrFail)

        assertTrue(failures.isEmpty())
        assertTrue(reentrantFailure.get() is IllegalStateException)
        assertEquals(1, resource.closeCalls.get())
        assertEquals(1, factory.runtimeFor(engine.epoch).closeCalls.get())
    }

    @Test
    fun `cross thread resource close reentry fails fast without blocking registry drain`() {
        val fixture = CandidateFixture()
        val factory = RecordingResolverFactory()
        val engine = ReadyDnsEngineOwner.create(fixture.candidate, factory)
        val registry = factory.registryFor(engine.epoch)
        val resourceCloseEntered = CountDownLatch(1)
        val allowResourceReentry = CountDownLatch(1)
        val resourceCloseReturned = CountDownLatch(1)
        val reentrantFailure = AtomicReference<Throwable>()
        val resource = CountingResource {
            resourceCloseEntered.countDown()
            allowResourceReentry.awaitOrFail()
            reentrantFailure.set(
                runCatching { engine.close() }.exceptionOrNull(),
            )
            resourceCloseReturned.countDown()
        }
        assertTrue(registry.registerIfCurrent(resource, engine.epoch))
        val completionReturned = CountDownLatch(1)
        val completionThread = Thread {
            try {
                assertFalse(registry.completeIfCurrent(resource, engine.epoch) { false })
            } finally {
                completionReturned.countDown()
            }
        }.apply {
            isDaemon = true
            start()
        }
        val ownerCloseReturned = CountDownLatch(1)
        val ownerCloseThread = Thread {
            try {
                engine.close()
            } finally {
                ownerCloseReturned.countDown()
            }
        }.apply { isDaemon = true }

        resourceCloseEntered.awaitOrFail()
        ownerCloseThread.start()
        awaitCondition { !engine.isCurrent() }
        allowResourceReentry.countDown()

        resourceCloseReturned.awaitOrFail()
        completionReturned.awaitOrFail()
        ownerCloseReturned.awaitOrFail()
        completionThread.joinOrFail()
        ownerCloseThread.joinOrFail()

        assertTrue(reentrantFailure.get() is IllegalStateException)
        assertEquals(1, resource.closeCalls.get())
        assertEquals(1, factory.runtimeFor(engine.epoch).closeCalls.get())
    }

    @Test
    fun `resource errors do not stop sibling drain or resolver bundle close`() {
        val fixture = CandidateFixture()
        val factory = RecordingResolverFactory()
        val engine = ReadyDnsEngineOwner.create(fixture.candidate, factory)
        val registry = factory.registryFor(engine.epoch)
        val resources = List(3) { ErrorResource() }
        resources.forEach { resource ->
            assertTrue(registry.registerIfCurrent(resource, engine.epoch))
        }

        engine.close()

        resources.forEach { resource -> assertEquals(1, resource.closeCalls.get()) }
        assertEquals(1, factory.runtimeFor(engine.epoch).closeCalls.get())
    }

    @Test
    fun `resolver bundle close failure reaches every close caller`() {
        val fixture = CandidateFixture()
        val failure = IllegalStateException("resolver drain unproved")
        val factory = RecordingResolverFactory().apply {
            closeFailure = failure
        }
        val engine = ReadyDnsEngineOwner.create(fixture.candidate, factory)
        val start = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val callers = List(6) {
            Thread {
                start.awaitOrFail()
                try {
                    engine.close()
                } catch (thrown: Throwable) {
                    failures += thrown
                }
            }.apply { start() }
        }

        start.countDown()
        callers.forEach(Thread::joinOrFail)

        assertEquals(6, failures.size)
        failures.forEach { thrown -> assertSame(failure, thrown) }
        assertEquals(1, factory.runtimeFor(engine.epoch).closeCalls.get())
        assertFalse(engine.isCurrent())
        assertStale(runBlocking { engine.resolveIfCurrent(request(engine)) })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `bounded runtime alone owns accepted and rejected replacement ordering`() = runTest {
        val fixture = CandidateFixture()
        val factory = RecordingResolverFactory()
        val proxy = TestRuntimeDnsProxy(
            planner = { _, _ -> ProxyPlan.Drop },
            unavailablePlanner = { _, _ -> ProxyPlan.Drop },
            executor = { ProxyResult.NotPublishable },
            overloadRejector = { ProxyResult.NotPublishable },
        )
        val first = ReadyDnsEngineOwner.create(
            fixture.candidate(proxy = proxy),
            factory,
        )
        val accepted = ReadyDnsEngineOwner.create(
            fixture.candidate(
                epoch = fixture.candidate.epoch.copy(engineGeneration = 30),
                proxy = proxy,
            ),
            factory,
        )
        val rejected = ReadyDnsEngineOwner.create(
            fixture.candidate(
                epoch = fixture.candidate.epoch.copy(engineGeneration = 29),
                proxy = proxy,
            ),
            factory,
        )
        val tun = ScriptedTunPacketSession()
        val runtime = BoundedTunRuntime(
            sessionToken = fixture.candidate.epoch.sessionGeneration,
            packetSession = tun,
            observer = RecordingTunRuntimeObserver(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        runtime.start(first)
        runCurrent()
        val incumbentCloseEntered = CountDownLatch(1)
        val releaseIncumbentClose = CountDownLatch(1)
        val replacementResult = AtomicReference<Boolean>()
        val replacementFailure = AtomicReference<Throwable?>()
        val replacementDone = CountDownLatch(1)
        factory.onRuntimeClose = {
            incumbentCloseEntered.countDown()
            releaseIncumbentClose.awaitOrFail()
        }
        val replacementThread = Thread {
            try {
                replacementResult.set(runBlocking { runtime.replaceEngine(accepted) })
            } catch (failure: Throwable) {
                replacementFailure.set(failure)
            } finally {
                replacementDone.countDown()
            }
        }.apply {
            isDaemon = true
            start()
        }

        incumbentCloseEntered.awaitOrFail()
        tun.enqueuePacket(byteArrayOf(1))
        runCurrent()
        assertEquals(
            "runtime did not plan with the successor while incumbent close was blocked",
            30L,
            proxy.plannedEngineGenerations.single(),
        )
        releaseIncumbentClose.countDown()
        replacementDone.awaitOrFail()
        replacementThread.joinOrFail()
        assertTrue(
            "accepted replacement failed: ${replacementFailure.get()}",
            replacementFailure.get() == null,
        )
        assertTrue(requireNotNull(replacementResult.get()))
        assertFalse(first.isCurrent())
        assertTrue(accepted.isCurrent())
        assertEquals(1, factory.runtimeFor(first.epoch).closeCalls.get())

        factory.onRuntimeClose = null
        assertFalse(runtime.replaceEngine(rejected))
        assertFalse(rejected.isCurrent())
        assertTrue(accepted.isCurrent())
        assertEquals(1, factory.runtimeFor(rejected.epoch).closeCalls.get())
        tun.enqueuePacket(byteArrayOf(2))
        runCurrent()
        assertEquals(
            "rejected proposal displaced the runtime incumbent",
            30L,
            proxy.plannedEngineGenerations.last(),
        )

        runtime.closeAndJoin()
        assertFalse(accepted.isCurrent())
        assertEquals(1, factory.runtimeFor(accepted.epoch).closeCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `bounded runtime surfaces accepted rejected and terminal owner close failures`() = runTest {
        val fixture = CandidateFixture()
        val factory = RecordingResolverFactory()
        val first = ReadyDnsEngineOwner.create(fixture.candidate, factory)
        val accepted = ReadyDnsEngineOwner.create(
            fixture.candidate(
                epoch = fixture.candidate.epoch.copy(engineGeneration = 30),
            ),
            factory,
        )
        val rejected = ReadyDnsEngineOwner.create(
            fixture.candidate(
                epoch = fixture.candidate.epoch.copy(engineGeneration = 29),
            ),
            factory,
        )
        val observer = RecordingTunRuntimeObserver()
        val runtime = BoundedTunRuntime(
            sessionToken = fixture.candidate.epoch.sessionGeneration,
            packetSession = ScriptedTunPacketSession(),
            observer = observer,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        runtime.start(first)

        val acceptedFailure = IllegalStateException("incumbent resolver drain unproved")
        factory.closeFailure = acceptedFailure
        assertSame(
            acceptedFailure,
            runCatching { runtime.replaceEngine(accepted) }.exceptionOrNull(),
        )
        assertTrue(accepted.isCurrent())

        val rejectedFailure = IllegalStateException("proposal resolver drain unproved")
        factory.closeFailure = rejectedFailure
        assertSame(
            rejectedFailure,
            runCatching { runtime.replaceEngine(rejected) }.exceptionOrNull(),
        )
        assertTrue(accepted.isCurrent())

        val terminalFailure = IllegalStateException("terminal resolver drain unproved")
        factory.closeFailure = terminalFailure
        val terminalThrown =
            requireNotNull(runCatching { runtime.closeAndJoin() }.exceptionOrNull())
        assertEquals(terminalFailure.message, terminalThrown.message)
        assertTrue(terminalThrown === terminalFailure || terminalThrown.cause === terminalFailure)
        assertFalse(accepted.isCurrent())
        assertTrue(
            observer.failures.contains(
                com.lavasec.app.platform.ProtectionFailure.DependencyUnavailable,
            ),
        )
    }

    private fun request(engine: ReadyDnsEngine): ResolverRequest {
        val query = testDnsQuery()
        return ResolverRequest(
            query = query,
            question = DnsMessage.parseQuestion(query),
            epoch = engine.epoch,
            networkContext = engine.networkContext,
            queryId = 701,
        )
    }

    private fun assertStale(result: ResolverResult) {
        assertTrue(result is ResolverResult.Failure)
        assertEquals(
            ResolverFailureReason.STALE_EPOCH,
            (result as ResolverResult.Failure).reason,
        )
    }
}

private class CandidateFixture {
    private val harness = DnsProxyHarness()
    private val networkContext = testNetworkContext()
    private val resolverPlan = testResolverPlan(networkContext)

    val candidate = ReadyDnsEngineCandidate(
        epoch = testEpoch(
            policyIdentity = harness.policyIdentity,
            resolverPlan = resolverPlan,
        ),
        networkContext = networkContext,
        resolverPlan = resolverPlan,
        policy = TestOwnedDnsPolicyRuntime(harness.policyIdentity, harness.proxy),
    )

    fun candidate(
        epoch: EngineEpoch = candidate.epoch,
        networkContext: NetworkContext = candidate.networkContext,
        resolverPlan: ResolverPlan = candidate.resolverPlan,
        policyIdentity: ProxyPolicyIdentity = candidate.policyIdentity,
        proxy: DnsProxy = candidate.proxy,
        policy: OwnedDnsPolicyRuntime = TestOwnedDnsPolicyRuntime(policyIdentity, proxy),
    ) = ReadyDnsEngineCandidate(
        epoch = epoch,
        networkContext = networkContext,
        resolverPlan = resolverPlan,
        policy = policy,
    )
}

private class RecordingResolverFactory : ReadyDnsResolverFactory {
    val createCalls = AtomicInteger()
    var result: ResolverResult = ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
    var resolverHandler:
        (suspend (ResolverRequest, ResolverResourceRegistry) -> ResolverResult)? = null
    var onCreate:
        ((EngineEpoch, NetworkContext, ResolverPlan, ResolverResourceRegistry) -> Unit)? = null
    var onRuntimeClose: (() -> Unit)? = null
    var closeFailure: Throwable? = null
    private val runtimes =
        Collections.synchronizedMap(
            IdentityHashMap<EngineEpoch, RecordingOwnedResolver>(),
        )
    private val registries =
        Collections.synchronizedMap(
            IdentityHashMap<EngineEpoch, ResolverResourceRegistry>(),
        )

    override fun create(
        epoch: EngineEpoch,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
        registry: ResolverResourceRegistry,
    ): OwnedReadyDnsResolver {
        createCalls.incrementAndGet()
        onCreate?.invoke(epoch, networkContext, resolverPlan, registry)
        registries[epoch] = registry
        return RecordingOwnedResolver(
            registry = registry,
            result = { result },
            handler = { resolverHandler },
            onClose = { onRuntimeClose?.invoke() },
            closeFailure = { closeFailure },
        ).also { runtimes[epoch] = it }
    }

    fun runtimeFor(epoch: EngineEpoch): RecordingOwnedResolver =
        requireNotNull(runtimes[epoch])

    fun registryFor(epoch: EngineEpoch): ResolverResourceRegistry =
        requireNotNull(registries[epoch])
}

private class RecordingOwnedResolver(
    private val registry: ResolverResourceRegistry,
    private val result: () -> ResolverResult,
    private val handler:
        () -> (suspend (ResolverRequest, ResolverResourceRegistry) -> ResolverResult)?,
    private val onClose: () -> Unit,
    private val closeFailure: () -> Throwable?,
) : OwnedReadyDnsResolver {
    val resolveCalls = AtomicInteger()
    val closeCalls = AtomicInteger()

    override suspend fun resolve(request: ResolverRequest): ResolverResult {
        resolveCalls.incrementAndGet()
        return handler()?.invoke(request, registry) ?: result()
    }

    override fun close() {
        closeCalls.incrementAndGet()
        onClose()
        closeFailure()?.let { throw it }
    }
}

private class CountingResource(
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    val closeCalls = AtomicInteger()

    override fun close() {
        if (closeCalls.incrementAndGet() == 1) onClose()
    }
}

private class ErrorResource : AutoCloseable {
    val closeCalls = AtomicInteger()

    override fun close() {
        closeCalls.incrementAndGet()
        throw AssertionError("resource close failed")
    }
}

private fun CountDownLatch.awaitOrFail() {
    assertTrue("timed out waiting for deterministic gate", await(2, TimeUnit.SECONDS))
}

private fun Thread.joinOrFail() {
    join(2_000)
    assertFalse("thread did not terminate", isAlive)
}

private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (!condition()) {
        assertTrue("timed out waiting for condition", System.nanoTime() < deadline)
        Thread.yield()
    }
}
