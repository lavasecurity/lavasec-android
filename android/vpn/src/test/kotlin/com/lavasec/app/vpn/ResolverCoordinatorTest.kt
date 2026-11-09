package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.platform.ConfigurationIdentity
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResolverCoordinatorTest {
    @Test
    fun `primary probes use current query leases without changing client fallback transition state`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = true)
        val registry = GenerationSocketRegistry(epochFor(plan))
        var failed = true
        val device = RecordingDeviceRoute { request, _ ->
            if (failed) ResolverResult.Failure(ResolverFailureReason.TIMEOUT) else answer(request, ResolverTransport.DEVICE_UDP)
        }
        val encrypted = RecordingResolver { request -> answer(request, ResolverTransport.MULLVAD_DOH) }
        val coordinator = coordinator(plan, registry, device, encrypted)
        val carried = coordinator.resolve(request(plan)) as ResolverResult.Answer
        assertTrue(carried.healthEvents.any { it is DnsHealthEvent.FallbackActivated })
        failed = false
        val probe = coordinator.resolvePrimary(request(plan, queryId = 2)) as ResolverResult.Answer
        assertEquals(ResolverTransport.DEVICE_UDP, probe.transport)
        assertTrue(probe.healthEvents.none { it is DnsHealthEvent.ResolverRecovered })
        assertEquals(1, encrypted.requests.size)
        val ordinary = coordinator.resolve(request(plan, queryId = 3)) as ResolverResult.Answer
        assertTrue(ordinary.healthEvents.any { it is DnsHealthEvent.ResolverRecovered })
        registry.close()
        assertEquals(ResolverFailureReason.STALE_EPOCH,
            (coordinator.resolvePrimary(request(plan, queryId = 4)) as ResolverResult.Failure).reason)
        assertEquals(3, device.requests.size)
        assertEquals(0, registry.sizeForTesting)
    }

    @Test
    fun `constructor rejects epoch plan and exact route dependency mismatches`() {
        val deviceOnly = devicePlan(mullvadEnabled = false)
        val deviceWithMullvad = devicePlan(mullvadEnabled = true)
        val mullvadOnly = mullvadPlan()
        val device = RecordingDeviceRoute { request, _ ->
            answer(request, ResolverTransport.DEVICE_UDP)
        }
        val mullvad = RecordingResolver { request ->
            answer(request, ResolverTransport.MULLVAD_DOH)
        }

        assertConstructorRejected(
            epoch = epochFor(deviceOnly).copy(networkGeneration = deviceOnly.networkGeneration + 1),
            plan = deviceOnly,
            device = device,
        )
        assertConstructorRejected(
            epoch = epochFor(deviceOnly).copy(
                primaryResolverIdentity = PrimaryResolverIdentity("different"),
            ),
            plan = deviceOnly,
            device = device,
        )
        assertConstructorRejected(
            epoch = epochFor(deviceOnly).copy(
                fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
            ),
            plan = deviceOnly,
            device = device,
        )
        assertConstructorRejected(
            epoch = epochFor(deviceOnly),
            plan = deviceOnly,
            device = null,
        )
        assertConstructorRejected(
            epoch = epochFor(deviceOnly),
            plan = deviceOnly,
            device = device,
            mullvad = mullvad,
        )
        assertConstructorRejected(
            epoch = epochFor(deviceWithMullvad),
            plan = deviceWithMullvad,
            device = device,
            mullvad = null,
        )
        assertConstructorRejected(
            epoch = epochFor(mullvadOnly),
            plan = mullvadOnly,
            device = device,
            mullvad = mullvad,
        )
        assertConstructorRejected(
            epoch = epochFor(mullvadOnly),
            plan = mullvadOnly,
            device = null,
            mullvad = null,
        )
    }

    @Test
    fun `exact preflight rejects every mismatched request before child route work`() = runBlocking {
        val plan = devicePlan(
            addresses = listOf(ip("192.0.2.53"), ip("192.0.2.54")),
            mullvadEnabled = true,
        )
        val epoch = epochFor(plan)
        val registry = GenerationSocketRegistry(epoch)
        val device = RecordingDeviceRoute { request, _ ->
            answer(request, ResolverTransport.DEVICE_UDP)
        }
        val mullvad = RecordingResolver { request ->
            answer(request, ResolverTransport.MULLVAD_DOH)
        }
        val coordinator = coordinator(plan, registry, device, mullvad)
        val cases = listOf(
            "session generation" to request(
                plan = plan,
                epoch = epoch.copy(sessionGeneration = epoch.sessionGeneration + 1),
            ),
            "configuration identity" to request(
                plan = plan,
                epoch = epoch.copy(
                    configurationIdentity = ConfigurationIdentity(2L, "other"),
                ),
            ),
            "artifact digest" to request(
                plan = plan,
                epoch = epoch.copy(artifactDigest = "other-artifact"),
            ),
            "policy revision" to request(
                plan = plan,
                epoch = epoch.copy(policyRevision = epoch.policyRevision + 1),
            ),
            "engine generation" to request(
                plan = plan,
                epoch = epoch.copy(engineGeneration = epoch.engineGeneration + 1),
            ),
            "primary resolver identity" to request(
                plan = plan,
                epoch = epoch.copy(
                    primaryResolverIdentity = PrimaryResolverIdentity("other-primary"),
                ),
            ),
            "fallback coverage" to request(
                plan = plan,
                epoch = epoch.copy(fallbackCoverage = ResolverFallbackCoverage.NONE),
            ),
            "Android Network" to request(
                plan = plan,
                networkContext = networkContext(plan, network = network(99)),
            ),
            // ResolverRequest owns epoch/context generation equality. This valid request
            // independently varies their coupled snapshot from the coordinator/plan epoch.
            "request generation snapshot" to request(
                plan = plan,
                epoch = epoch.copy(networkGeneration = epoch.networkGeneration + 1),
                networkContext = networkContext(
                    plan,
                    networkGeneration = plan.networkGeneration + 1,
                ),
            ),
            "cellular classification" to request(
                plan = plan,
                networkContext = networkContext(plan, isCellular = !plan.isCellular),
            ),
            "Device snapshot" to request(
                plan = plan,
                networkContext = networkContext(
                    plan,
                    addresses = listOf(ip("192.0.2.53"), ip("192.0.2.55")),
                ),
            ),
            "Device same-family order" to request(
                plan = plan,
                networkContext = networkContext(
                    plan,
                    addresses = listOf(ip("192.0.2.54"), ip("192.0.2.53")),
                ),
            ),
        )

        cases.forEach { (label, mismatchedRequest) ->
            assertStale(label, coordinator.resolve(mismatchedRequest))
            assertEquals(label, 0, registry.sizeForTesting)
        }
        assertTrue(device.requests.isEmpty())
        assertTrue(mullvad.requests.isEmpty())
    }

    @Test
    fun `current snapshot query provenance failure is invalid response before route work`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = true)
            val registry = GenerationSocketRegistry(epochFor(plan))
            val device = RecordingDeviceRoute { routeRequest, _ ->
                answer(routeRequest, ResolverTransport.DEVICE_UDP)
            }
            val mullvad = RecordingResolver { routeRequest ->
                answer(routeRequest, ResolverTransport.MULLVAD_DOH)
            }
            val coordinator = coordinator(plan, registry, device, mullvad)
            val queryBytes = query(id = 0x1234)
            val invalid = request(
                plan = plan,
                queryBytes = queryBytes,
                parsedQuery = query(id = 0x4321),
            )

            val result = coordinator.resolve(invalid)

            val failure = assertFailure(result, ResolverFailureReason.INVALID_RESPONSE)
            assertTrue(failure.healthEvents.isEmpty())
            assertNull(failure.resolver)
            assertNull(failure.resolverQueryEvidence)
            assertTrue(device.requests.isEmpty())
            assertTrue(mullvad.requests.isEmpty())
            assertEquals(0, registry.sizeForTesting)
        }

    @Test
    fun `Device requested promoted Mullvad fences a newly usable Device snapshot`() = runBlocking {
        val selectedNetwork = network(44)
        val plan = readyPlan(
            configuration = ResolverConfiguration(
                useDeviceDns = true,
                useEncryptedFallback = true,
            ),
            networkContext = NetworkContext(
                network = selectedNetwork,
                networkGeneration = 9L,
                dnsServers = listOf(ip("0.0.0.0"), ip("127.0.0.1")),
                isCellular = false,
            ),
        )
        assertEquals(ResolverKind.MULLVAD_DOH, plan.primaryResolverKind)
        assertTrue(plan.deviceDnsServers.isEmpty())
        val registry = GenerationSocketRegistry(epochFor(plan))
        val mullvad = RecordingResolver { request ->
            answer(request, ResolverTransport.MULLVAD_DOH)
        }
        val coordinator = coordinator(
            plan = plan,
            registry = registry,
            device = null,
            mullvad = mullvad,
        )

        val result = coordinator.resolve(
            request(
                plan = plan,
                networkContext = NetworkContext(
                    network = selectedNetwork,
                    networkGeneration = plan.networkGeneration,
                    dnsServers = listOf(ip("192.0.2.53")),
                    isCellular = plan.isCellular,
                ),
            ),
        )

        assertStale(result = result)
        assertTrue(mullvad.requests.isEmpty())
        assertEquals(0, registry.sizeForTesting)
    }

    @Test
    fun `explicit Mullvad only does not fence Device snapshot changes`() = runBlocking {
        val selectedNetwork = network(45)
        val plan = readyPlan(
            configuration = ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
            ),
            networkContext = NetworkContext(
                network = selectedNetwork,
                networkGeneration = 10L,
                dnsServers = emptyList(),
                isCellular = false,
            ),
        )
        val registry = GenerationSocketRegistry(epochFor(plan))
        val mullvad = RecordingResolver { request ->
            answer(request, ResolverTransport.MULLVAD_DOH)
        }
        val coordinator = coordinator(
            plan = plan,
            registry = registry,
            device = null,
            mullvad = mullvad,
        )

        val result = coordinator.resolve(
            request(
                plan = plan,
                networkContext = NetworkContext(
                    network = selectedNetwork,
                    networkGeneration = plan.networkGeneration,
                    dnsServers = listOf(ip("192.0.2.53")),
                    isCellular = plan.isCellular,
                ),
            ),
        )

        assertAnswer(result, ResolverTransport.MULLVAD_DOH)
        assertEquals(1, mullvad.requests.size)
        assertEquals(0, registry.sizeForTesting)
    }

    @Test
    fun `noncurrent ownership preflight calls no route and leaves no registry entry`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = false)
        val epoch = epochFor(plan)
        val next = epoch.copy(engineGeneration = epoch.engineGeneration + 1)
        val registry = GenerationSocketRegistry(epoch)
        val device = RecordingDeviceRoute { request, _ ->
            answer(request, ResolverTransport.DEVICE_UDP)
        }
        val coordinator = coordinator(plan, registry, device)
        registry.advanceTo(next)

        val result = coordinator.resolve(request(plan))

        assertStale(result = result)
        assertTrue(device.requests.isEmpty())
        assertEquals(0, registry.sizeForTesting)
    }

    @Test
    fun `registration rejection consumes an unstarted lease promptly without a route call`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = false)
            val epoch = epochFor(plan)
            val delegate = GenerationSocketRegistry(epoch)
            val registry = RejectingRegistrationRegistry(delegate)
            val observer = RecordingLeaseObserver()
            val device = RecordingDeviceRoute { request, _ ->
                answer(request, ResolverTransport.DEVICE_UDP)
            }

            val result = withTimeout(2_000) {
                coordinator(
                    plan = plan,
                    registry = registry,
                    device = device,
                    leaseObserver = observer,
                ).resolve(request(plan))
            }

            assertStale(result = result)
            assertEquals(1, registry.registrations.get())
            assertEquals(1, registry.registryConsumptions.get())
            assertEquals(0, registry.unregisters.get())
            assertEquals(0, registry.completions.get())
            assertEquals(1, observer.closeInvocations.get())
            assertEquals(1, observer.retirementStarts.get())
            assertEquals(1, observer.drains.get())
            assertEquals(1, observer.retirementFinishes.get())
            assertEquals(0, observer.starts.get())
            assertTrue(device.requests.isEmpty())
            assertEquals(0, delegate.sizeForTesting)
        }

    @Test
    fun `successful query runs in a lease child and leaves its caller active`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = false)
        val registry = GenerationSocketRegistry(epochFor(plan))
        val callerJob = currentCoroutineContext()[Job]
        val routeJob = AtomicReference<Job?>()
        val device = RecordingDeviceRoute { request, _ ->
            routeJob.set(currentCoroutineContext()[Job])
            answer(request, ResolverTransport.DEVICE_UDP)
        }

        val result = coordinator(plan, registry, device).resolve(request(plan))

        assertAnswer(result, ResolverTransport.DEVICE_UDP)
        assertNotNull(routeJob.get())
        assertNotSame(callerJob, routeJob.get())
        assertTrue(checkNotNull(callerJob).isActive)
        assertEquals(0, registry.sizeForTesting)
    }

    @Test
    fun `inline child completion establishes drain without being overwritten by start`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = false)
            val registry = GenerationSocketRegistry(epochFor(plan))
            val observer = RecordingLeaseObserver()
            val device = RecordingDeviceRoute { request, _ ->
                answer(request, ResolverTransport.DEVICE_UDP)
            }

            val result = coordinator(
                plan = plan,
                registry = registry,
                device = device,
                leaseObserver = observer,
                childDispatcher = Dispatchers.Unconfined,
            ).resolve(request(plan))

            assertAnswer(result, ResolverTransport.DEVICE_UDP)
            assertEquals(1, observer.starts.get())
            assertEquals(1, observer.drains.get())
            assertEquals(0, observer.retirementStarts.get())
            assertEquals(0, registry.sizeForTesting)
        }

    @Test
    fun `caller cancellation after child dispatch drains without entering the child body`() {
        val plan = devicePlan(mullvadEnabled = false)
        val registry = GenerationSocketRegistry(epochFor(plan))
        val callerDispatcher = QueuedDispatcher()
        val childDispatcher = QueuedDispatcher()
        val callerScopeJob = SupervisorJob()
        val observer = RecordingLeaseObserver()
        val device = RecordingDeviceRoute { request, _ ->
            answer(request, ResolverTransport.DEVICE_UDP)
        }
        val coordinator = coordinator(
            plan = plan,
            registry = registry,
            device = device,
            leaseObserver = observer,
            childDispatcher = childDispatcher,
        )
        val completionCause = AtomicReference<Throwable?>()
        val pending = CoroutineScope(callerScopeJob + callerDispatcher).async {
            coordinator.resolve(request(plan))
        }.also { deferred ->
            deferred.invokeOnCompletion(completionCause::set)
        }

        assertTrue(callerDispatcher.runNext())
        assertEquals(1, childDispatcher.queuedTaskCount)
        assertEquals(1, observer.starts.get())
        assertTrue(device.requests.isEmpty())

        val callerCancellation = CancellationException("queued caller cancelled")
        pending.cancel(callerCancellation)
        callerDispatcher.runUntilIdle()
        childDispatcher.runUntilIdle()
        callerDispatcher.runUntilIdle()

        assertTrue(
            "caller cancellation remained suspended waiting for dispatched child drain",
            pending.isCompleted,
        )
        assertTrue(pending.isCancelled)
        assertSame(callerCancellation, completionCause.get())
        assertEquals(1, observer.drains.get())
        assertEquals(0, registry.sizeForTesting)

        assertTrue(device.requests.isEmpty())
        assertTrue(callerScopeJob.isActive)
        callerScopeJob.cancel()
        registry.close()
    }

    @Test
    fun `epoch retirement after child dispatch drains before admitting a successor`() {
        val plan = devicePlan(mullvadEnabled = false)
        val epoch = epochFor(plan)
        val next = epoch.copy(engineGeneration = epoch.engineGeneration + 1)
        val registry = GenerationSocketRegistry(epoch)
        val callerDispatcher = QueuedDispatcher()
        val childDispatcher = QueuedDispatcher()
        val callerScopeJob = SupervisorJob()
        val observer = RecordingLeaseObserver()
        val device = RecordingDeviceRoute { request, _ ->
            answer(request, ResolverTransport.DEVICE_UDP)
        }
        val pending = CoroutineScope(callerScopeJob + callerDispatcher).async {
            coordinator(
                plan = plan,
                registry = registry,
                device = device,
                leaseObserver = observer,
                childDispatcher = childDispatcher,
            ).resolve(request(plan))
        }

        assertTrue(callerDispatcher.runNext())
        assertEquals(1, childDispatcher.queuedTaskCount)
        assertEquals(1, observer.starts.get())
        assertTrue(device.requests.isEmpty())

        val advanceFailure = AtomicReference<Throwable?>()
        val advanceDone = CountDownLatch(1)
        val advance = Thread(
            {
                try {
                    registry.advanceTo(next)
                } catch (failure: Throwable) {
                    advanceFailure.set(failure)
                } finally {
                    advanceDone.countDown()
                }
            },
            "resolver-queued-retirement",
        ).apply {
            isDaemon = true
            start()
        }
        awaitWaitingOrReturned(advance, advanceDone)

        assertEquals(
            "epoch retirement returned before the dispatched child completed",
            1L,
            advanceDone.count,
        )
        childDispatcher.runUntilIdle()
        assertEquals(
            "dispatched child completion did not establish retirement drain",
            1,
            observer.drains.get(),
        )
        advanceDone.await()
        advance.join()
        advanceFailure.get()?.let { throw AssertionError("epoch retirement failed", it) }
        assertEquals(1, observer.retirementStarts.get())
        assertEquals(1, observer.retirementFinishes.get())

        val successor = CountingResource()
        assertTrue(registry.registerIfCurrent(successor, next))
        assertTrue(registry.unregister(successor))
        successor.close()

        callerDispatcher.runUntilIdle()
        assertTrue(pending.isCompleted)
        assertStale(result = runBlocking { pending.await() })
        assertTrue(device.requests.isEmpty())
        assertEquals(1, successor.closed.get())
        assertEquals(0, registry.sizeForTesting)
        assertTrue(callerScopeJob.isActive)
        callerScopeJob.cancel()
        registry.close()
    }

    @Test
    fun `lease establishes drain before its candidate reaches the completion gate`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = false)
        val delegate = GenerationSocketRegistry(epochFor(plan))
        val registry = CountingCompletionRegistry(delegate)
        val beforeDrain = CountDownLatch(1)
        val allowDrain = CountDownLatch(1)
        val observer = RecordingLeaseObserver(
            beforeChildDrain = {
                beforeDrain.countDown()
                allowDrain.await()
            },
        )
        val device = RecordingDeviceRoute { request, _ ->
            answer(request, ResolverTransport.DEVICE_UDP)
        }
        val pending = async(Dispatchers.Default) {
            coordinator(
                plan = plan,
                registry = registry,
                device = device,
                leaseObserver = observer,
            ).resolve(request(plan))
        }

        try {
            await(beforeDrain, "candidate before drain")
            assertEquals(
                "completion gate ran before the child established drain",
                0,
                registry.completions.get(),
            )
            allowDrain.countDown()

            val result = withTimeout(2_000) { pending.await() }

            assertAnswer(result, ResolverTransport.DEVICE_UDP)
            assertEquals(1, observer.drains.get())
            assertEquals(1, registry.completions.get())
            assertEquals(0, delegate.sizeForTesting)
        } finally {
            allowDrain.countDown()
            pending.cancelAndJoin()
            delegate.close()
        }
    }

    @Test
    fun `caller cancellation drains and releases its child without retiring the epoch policy`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = false)
            val registry = GenerationSocketRegistry(epochFor(plan))
            val dispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "resolver-caller-cancellation-test").apply {
                    isDaemon = true
                }
            }.asCoroutineDispatcher()
            val calls = AtomicInteger()
            val firstStarted = CountDownLatch(1)
            val firstCancelled = CountDownLatch(1)
            val capturedPolicies = CopyOnWriteArrayList<ResolverEndpointBackoffPolicy>()
            val device = RecordingDeviceRoute { request, policy ->
                capturedPolicies += policy
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown()
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled.countDown()
                    }
                }
                answer(request, ResolverTransport.DEVICE_UDP)
            }
            val coordinator = coordinator(
                plan = plan,
                registry = registry,
                device = device,
                childDispatcher = dispatcher,
            )
            val pending = async(dispatcher) {
                coordinator.resolve(request(plan, queryId = 1L))
            }

            try {
                await(firstStarted, "first route start")
                pending.cancel()
                assertTrue(
                    "caller cancellation did not drain on the shared single worker",
                    withTimeoutOrNull(2_000) {
                        pending.join()
                        true
                    } == true,
                )

                await(firstCancelled, "first route cancellation")
                assertTrue(pending.isCancelled)
                assertEquals(0, registry.sizeForTesting)
                val policy = capturedPolicies.first()
                policy.recordFailure(ResolverEndpointKey.MullvadDoh, ResolverFailureReason.TIMEOUT)
                assertTrue("caller cancellation retired the shared epoch policy", policy.isBackedOff(
                    ResolverEndpointKey.MullvadDoh,
                ))

                val later = withTimeout(2_000) {
                    withContext(dispatcher) {
                        coordinator.resolve(request(plan, queryId = 2L))
                    }
                }

                assertAnswer(later, ResolverTransport.DEVICE_UDP)
                assertEquals(2, calls.get())
                assertSame(policy, capturedPolicies.last())
                assertEquals(0, registry.sizeForTesting)
            } finally {
                pending.cancel()
                val completed = withTimeoutOrNull(2_000) {
                    pending.join()
                    true
                } == true
                if (completed) registry.close()
                dispatcher.close()
            }
        }

    @Test
    fun `self-cancelling child returns typed cancellation and releases current ownership`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = false)
            val registry = GenerationSocketRegistry(epochFor(plan))
            val calls = AtomicInteger()
            val device = RecordingDeviceRoute { request, _ ->
                if (calls.incrementAndGet() == 1) {
                    currentCoroutineContext()[Job]?.cancel()
                    throw CancellationException("child cancelled itself")
                }
                answer(request, ResolverTransport.DEVICE_UDP)
            }
            val coordinator = coordinator(plan, registry, device)

            try {
                val cancelled = coordinator.resolve(request(plan, queryId = 1L))

                assertFailure(cancelled, ResolverFailureReason.CANCELLED)
                assertTrue(checkNotNull(currentCoroutineContext()[Job]).isActive)
                assertEquals(0, registry.sizeForTesting)

                val later = coordinator.resolve(request(plan, queryId = 2L))

                assertAnswer(later, ResolverTransport.DEVICE_UDP)
                assertEquals(2, calls.get())
                assertEquals(0, registry.sizeForTesting)
            } finally {
                registry.close()
            }
        }

    @Test
    fun `non-cancellation child exception propagates after cleanup and later query succeeds`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = false)
            val registry = GenerationSocketRegistry(epochFor(plan))
            val sentinel = IllegalStateException("sentinel route failure")
            val calls = AtomicInteger()
            val device = RecordingDeviceRoute { request, _ ->
                if (calls.incrementAndGet() == 1) throw sentinel
                answer(request, ResolverTransport.DEVICE_UDP)
            }
            val coordinator = coordinator(plan, registry, device)

            val thrown = runCatching {
                coordinator.resolve(request(plan, queryId = 1L))
            }.exceptionOrNull()

            assertEquals(sentinel::class.java, thrown?.javaClass)
            assertEquals(sentinel.message, thrown?.message)
            // Coroutine timeout boundaries may recover the stack trace using a same-type
            // exception copy whose cause retains the exact route failure.
            if (thrown !== sentinel) assertSame(sentinel, thrown?.cause)
            assertTrue(checkNotNull(currentCoroutineContext()[Job]).isActive)
            assertEquals(0, registry.sizeForTesting)

            val later = coordinator.resolve(request(plan, queryId = 2L))

            assertAnswer(later, ResolverTransport.DEVICE_UDP)
            assertEquals(2, calls.get())
            assertEquals(0, registry.sizeForTesting)
        }

    @Test
    fun `retirement between failed child drain and unregister preserves the child failure`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = false)
            val epoch = epochFor(plan)
            val next = epoch.copy(engineGeneration = epoch.engineGeneration + 1)
            val delegate = GenerationSocketRegistry(epoch)
            val registry = BlockingUnregisterRegistry(delegate)
            val observer = RecordingLeaseObserver()
            val sentinel = IllegalStateException("sentinel route failure")
            val device = RecordingDeviceRoute { _, _ -> throw sentinel }
            val pendingScopeJob = SupervisorJob()
            val pending = CoroutineScope(pendingScopeJob + Dispatchers.Default).async {
                coordinator(
                    plan = plan,
                    registry = registry,
                    device = device,
                    leaseObserver = observer,
                ).resolve(request(plan))
            }
            val advanceDone = CountDownLatch(1)
            val advance = Thread {
                delegate.advanceTo(next)
                advanceDone.countDown()
            }

            try {
                await(registry.unregisterEntered, "failed child unregister")
                advance.start()
                await(advanceDone, "epoch retirement before unregister")
                registry.allowUnregister.countDown()

                val thrown = runCatching {
                    withTimeout(2_000) { pending.await() }
                }.exceptionOrNull()

                assertEquals(sentinel::class.java, thrown?.javaClass)
                assertEquals(sentinel.message, thrown?.message)
                assertEquals(1, observer.closeInvocations.get())
                assertEquals(1, observer.retirementStarts.get())
                assertEquals(1, observer.retirementFinishes.get())
                assertTrue(delegate.isCurrent(next))
                assertEquals(0, delegate.sizeForTesting)
            } finally {
                registry.allowUnregister.countDown()
                advance.interrupt()
                advance.join(2_000)
                pending.cancelAndJoin()
                pendingScopeJob.cancel()
                delegate.close()
                assertFalse(advance.isAlive)
            }
        }

    @Test
    fun `typed child stale epoch and cancellation never fall through to Mullvad`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = true)
        val registry = GenerationSocketRegistry(epochFor(plan))
        val reasons = ArrayDeque(
            listOf(ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.CANCELLED),
        )
        val device = RecordingDeviceRoute { _, _ ->
            ResolverResult.Failure(reasons.removeFirst())
        }
        val mullvad = RecordingResolver { request ->
            answer(request, ResolverTransport.MULLVAD_DOH)
        }
        val coordinator = coordinator(plan, registry, device, mullvad)

        listOf(ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.CANCELLED)
            .forEachIndexed { index, reason ->
                val result = coordinator.resolve(request(plan, queryId = index.toLong()))

                assertFailure(result, reason)
                assertEquals(0, registry.sizeForTesting)
            }
        assertEquals(2, device.requests.size)
        assertTrue(mullvad.requests.isEmpty())
    }

    @Test
    fun `losing the completion gate discards candidate events and fallback transition mutation`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = true)
            val epoch = epochFor(plan)
            val delegate = GenerationSocketRegistry(epoch)
            val registry = LoseFirstCompletionRegistry(delegate)
            val deviceCalls = AtomicInteger()
            val device = RecordingDeviceRoute { request, _ ->
                if (deviceCalls.incrementAndGet() <= 2) {
                    ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
                } else {
                    answer(request, ResolverTransport.DEVICE_UDP)
                }
            }
            val mullvad = RecordingResolver { request ->
                answer(request, ResolverTransport.MULLVAD_DOH)
            }
            val coordinator = coordinator(plan, registry, device, mullvad)

            val lost = coordinator.resolve(request(plan, queryId = 1L))
            val publishedFallback = coordinator.resolve(request(plan, queryId = 2L))
            val recoveredDevice = coordinator.resolve(request(plan, queryId = 3L))

            assertStale(result = lost)
            val fallback = assertAnswer(publishedFallback, ResolverTransport.MULLVAD_DOH)
            assertEquals(
                1,
                fallback.healthEvents.count { it is DnsHealthEvent.FallbackActivated },
            )
            val recovered = assertAnswer(recoveredDevice, ResolverTransport.DEVICE_UDP)
            assertEquals(
                listOf(DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE)),
                recovered.healthEvents,
            )
            assertEquals(2, mullvad.requests.size)
            assertEquals(0, delegate.sizeForTesting)
        }

    @Test
    fun `transition publication exception propagates after ownership consumption without mutation`() =
        runBlocking {
            val plan = devicePlan(mullvadEnabled = true)
            val registry = GenerationSocketRegistry(epochFor(plan))
            val sentinel = IllegalStateException("sentinel transition publication")
            val throwNext = AtomicBoolean(true)
            val transitionState = ResolverTransitionState { result, event ->
                if (throwNext.compareAndSet(true, false)) throw sentinel
                resultWithEvent(result, event)
            }
            val deviceCalls = AtomicInteger()
            val device = RecordingDeviceRoute { request, _ ->
                if (deviceCalls.incrementAndGet() <= 2) {
                    ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
                } else {
                    answer(request, ResolverTransport.DEVICE_UDP)
                }
            }
            val mullvad = RecordingResolver { request ->
                answer(request, ResolverTransport.MULLVAD_DOH)
            }
            val coordinator = coordinator(
                plan = plan,
                registry = registry,
                device = device,
                mullvad = mullvad,
                transitionState = transitionState,
            )

            val thrown = runCatching {
                coordinator.resolve(request(plan, queryId = 1L))
            }.exceptionOrNull()

            assertSame(sentinel, thrown)
            assertTrue(checkNotNull(currentCoroutineContext()[Job]).isActive)
            assertEquals(0, registry.sizeForTesting)

            val activated = coordinator.resolve(request(plan, queryId = 2L))
            val recovered = coordinator.resolve(request(plan, queryId = 3L))

            assertEquals(
                1,
                assertAnswer(activated, ResolverTransport.MULLVAD_DOH)
                    .healthEvents
                    .count { it is DnsHealthEvent.FallbackActivated },
            )
            assertEquals(
                listOf(DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE)),
                assertAnswer(recovered, ResolverTransport.DEVICE_UDP).healthEvents,
            )
            assertEquals(0, registry.sizeForTesting)
        }

    @Test
    fun `retirement after child answer wins before terminal publication`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = true)
        val epoch = epochFor(plan)
        val next = epoch.copy(engineGeneration = epoch.engineGeneration + 1)
        val delegate = GenerationSocketRegistry(epoch)
        val registry = BlockingCompletionRegistry(delegate)
        val device = RecordingDeviceRoute { _, _ ->
            ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
        }
        val mullvad = RecordingResolver { request ->
            answer(request, ResolverTransport.MULLVAD_DOH)
        }
        val coordinator = coordinator(plan, registry, device, mullvad)
        val pending = async(Dispatchers.Default) { coordinator.resolve(request(plan)) }
        val advanceDone = CountDownLatch(1)
        val advance = Thread {
            delegate.advanceTo(next)
            advanceDone.countDown()
        }

        try {
            await(registry.completionReached, "completion gate")
            advance.start()
            await(advanceDone, "epoch advance")
            registry.allowCompletion.countDown()
            val result = withTimeout(2_000) { pending.await() }

            assertStale(result = result)
            assertEquals(1, device.requests.size)
            assertEquals(1, mullvad.requests.size)
            assertEquals(0, delegate.sizeForTesting)
        } finally {
            registry.allowCompletion.countDown()
            advance.interrupt()
            advance.join(2_000)
            pending.cancelAndJoin()
            delegate.close()
            assertFalse(advance.isAlive)
        }
    }

    @Test
    fun `retirement of an accepted unstarted lease drains and admits a successor`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = false)
        val epoch = epochFor(plan)
        val next = epoch.copy(engineGeneration = epoch.engineGeneration + 1)
        val registry = GenerationSocketRegistry(epoch)
        val accepted = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val observer = RecordingLeaseObserver(
            afterAcceptedBeforeStart = {
                accepted.countDown()
                allowStart.await()
            },
        )
        val device = RecordingDeviceRoute { request, _ ->
            answer(request, ResolverTransport.DEVICE_UDP)
        }
        val pending = async(Dispatchers.Default) {
            coordinator(
                plan = plan,
                registry = registry,
                device = device,
                leaseObserver = observer,
            ).resolve(request(plan))
        }
        val advanceDone = CountDownLatch(1)
        val advance = Thread {
            registry.advanceTo(next)
            advanceDone.countDown()
        }
        val successor = CountingResource()

        try {
            await(accepted, "accepted lease before start")
            advance.start()
            await(observer.drained, "unstarted lease direct drain")
            await(advanceDone, "unstarted lease drain")
            assertEquals(0, observer.starts.get())
            assertEquals(1, observer.retirementStarts.get())
            assertEquals(1, observer.drains.get())
            assertEquals(1, observer.retirementFinishes.get())
            assertTrue(registry.registerIfCurrent(successor, next))
            assertTrue(registry.unregister(successor))
            successor.close()
            allowStart.countDown()
            val result = withTimeout(2_000) { pending.await() }

            assertStale(result = result)
            assertTrue(device.requests.isEmpty())
            assertEquals(1, successor.closed.get())
            assertEquals(0, registry.sizeForTesting)
        } finally {
            allowStart.countDown()
            advance.interrupt()
            advance.join(2_000)
            pending.cancelAndJoin()
            if (registry.unregister(successor)) successor.close()
            registry.close()
            assertFalse(advance.isAlive)
        }
    }

    @Test
    fun `concurrent repeated close waits until the first retirement close finishes`() = runBlocking {
        val plan = devicePlan(mullvadEnabled = false)
        val delegate = GenerationSocketRegistry(epochFor(plan))
        val registry = CapturingRegistrationRegistry(delegate)
        val accepted = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val retirementFinishEntered = CountDownLatch(1)
        val allowRetirementFinishReturn = CountDownLatch(1)
        val observer = RecordingLeaseObserver(
            afterAcceptedBeforeStart = {
                accepted.countDown()
                allowStart.await()
            },
            onRetirementFinished = {
                retirementFinishEntered.countDown()
                allowRetirementFinishReturn.await()
            },
        )
        val device = RecordingDeviceRoute { request, _ ->
            answer(request, ResolverTransport.DEVICE_UDP)
        }
        val pending = async(Dispatchers.Default) {
            coordinator(
                plan = plan,
                registry = registry,
                device = device,
                leaseObserver = observer,
            ).resolve(request(plan))
        }
        val firstCloseDone = CountDownLatch(1)
        val secondCloseEntered = CountDownLatch(1)
        val secondCloseDone = CountDownLatch(1)
        val firstClose = Thread {
            checkNotNull(registry.resource.get()).close()
            firstCloseDone.countDown()
        }
        val secondClose = Thread {
            secondCloseEntered.countDown()
            checkNotNull(registry.resource.get()).close()
            secondCloseDone.countDown()
        }

        try {
            await(accepted, "accepted lease for concurrent close")
            firstClose.start()
            await(retirementFinishEntered, "first close retirement-finished callback")
            assertEquals(1L, firstCloseDone.count)
            secondClose.start()
            await(secondCloseEntered, "second close entry")
            awaitThreadWaitingOrReturned(secondClose, secondCloseDone, "second close")
            assertEquals(
                "repeated close returned before the first close completed",
                1L,
                secondCloseDone.count,
            )

            allowRetirementFinishReturn.countDown()
            await(firstCloseDone, "first close completion")
            await(secondCloseDone, "second close completion")
            assertTrue(delegate.unregister(checkNotNull(registry.resource.get())))
            allowStart.countDown()
            assertStale(result = withTimeout(2_000) { pending.await() })
            assertEquals(2, observer.closeInvocations.get())
            assertEquals(1, observer.retirementStarts.get())
            assertEquals(1, observer.retirementFinishes.get())
            assertEquals(0, delegate.sizeForTesting)
        } finally {
            allowRetirementFinishReturn.countDown()
            allowStart.countDown()
            firstClose.interrupt()
            secondClose.interrupt()
            firstClose.join(2_000)
            secondClose.join(2_000)
            pending.cancelAndJoin()
            registry.resource.get()?.let(delegate::unregister)
            delegate.close()
            assertFalse(firstClose.isAlive)
            assertFalse(secondClose.isAlive)
        }
    }

    @Test
    fun `retirement deactivates policy before cancellation and fences successor until child drain`() =
        runBlocking {
            val address = ip("192.0.2.53")
            val plan = devicePlan(addresses = listOf(address), mullvadEnabled = false)
            val epoch = epochFor(plan)
            val next = epoch.copy(engineGeneration = epoch.engineGeneration + 1)
            val registry = GenerationSocketRegistry(epoch)
            val routeStarted = CountDownLatch(1)
            val cancellationObserved = CountDownLatch(1)
            val releaseDrain = CountDownLatch(1)
            val observedRetiredFirst = AtomicBoolean(false)
            val capturedPolicy = AtomicReference<ResolverEndpointBackoffPolicy?>()
            val device = RecordingDeviceRoute { _, policy ->
                capturedPolicy.set(policy)
                policy.recordFailure(
                    ResolverEndpointKey.DeviceAddress(address),
                    ResolverFailureReason.TIMEOUT,
                )
                policy.recordFailure(ResolverEndpointKey.MullvadDoh, ResolverFailureReason.TIMEOUT)
                routeStarted.countDown()
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    observedRetiredFirst.set(
                        !policy.isBackedOff(ResolverEndpointKey.DeviceAddress(address)) &&
                            !policy.isBackedOff(ResolverEndpointKey.MullvadDoh),
                    )
                    policy.recordFailure(
                        ResolverEndpointKey.DeviceAddress(address),
                        ResolverFailureReason.TIMEOUT,
                    )
                    policy.recordFailure(
                        ResolverEndpointKey.MullvadDoh,
                        ResolverFailureReason.TIMEOUT,
                    )
                    cancellationObserved.countDown()
                    withContext(NonCancellable) {
                        releaseDrain.await()
                    }
                    throw cancelled
                }
            }
            val coordinator = coordinator(plan, registry, device)
            val pending = async(Dispatchers.Default) { coordinator.resolve(request(plan)) }
            val advanceDone = CountDownLatch(1)
            val advance = Thread {
                registry.advanceTo(next)
                advanceDone.countDown()
            }
            val successor = CountingResource()
            val successorAccepted = AtomicBoolean(false)
            val successorEntered = CountDownLatch(1)
            val successorDone = CountDownLatch(1)
            var successorThread: Thread? = null

            try {
                await(routeStarted, "running route")
                advance.start()
                await(cancellationObserved, "route cancellation")
                assertEquals(
                    "epoch advance returned before the retired child drained",
                    1L,
                    advanceDone.count,
                )
                successorThread = Thread {
                    successorEntered.countDown()
                    successorAccepted.set(registry.registerIfCurrent(successor, next))
                    successorDone.countDown()
                }
                successorThread.start()
                await(successorEntered, "successor registration entry")
                awaitThreadWaitingOrReturned(
                    successorThread,
                    successorDone,
                    "successor registration",
                )
                assertEquals(
                    "successor admission returned before the retired child drained",
                    1L,
                    successorDone.count,
                )
                assertTrue(
                    "child observed cancellation before endpoint policy retirement",
                    observedRetiredFirst.get(),
                )
                releaseDrain.countDown()
                await(advanceDone, "running child drain")
                await(successorDone, "successor admission")
                assertTrue(successorAccepted.get())
                val result = withTimeout(2_000) { pending.await() }

                assertStale(result = result)
                val policy = checkNotNull(capturedPolicy.get())
                assertFalse(policy.isBackedOff(ResolverEndpointKey.DeviceAddress(address)))
                assertFalse(policy.isBackedOff(ResolverEndpointKey.MullvadDoh))
                assertTrue(registry.unregister(successor))
                successor.close()
                assertEquals(0, registry.sizeForTesting)
            } finally {
                releaseDrain.countDown()
                advance.interrupt()
                successorThread?.interrupt()
                advance.join(2_000)
                successorThread?.join(2_000)
                pending.cancelAndJoin()
                if (registry.unregister(successor)) successor.close()
                registry.close()
                assertFalse(advance.isAlive)
                assertFalse(successorThread?.isAlive == true)
            }
        }

    private fun assertConstructorRejected(
        epoch: EngineEpoch,
        plan: ResolverPlan,
        device: DeviceDnsRoute?,
        mullvad: DnsResolver? = null,
    ) {
        assertThrows(IllegalArgumentException::class.java) {
            ResolverCoordinator(
                epoch = epoch,
                plan = plan,
                registry = GenerationSocketRegistry(epoch),
                deviceRoute = device,
                mullvadRoute = mullvad,
                wedgeEvidence = DeviceResolverWedgeEvidence { false },
                clock = FakeClock(),
            )
        }
    }

    private fun coordinator(
        plan: ResolverPlan,
        registry: ResolverResourceRegistry,
        device: DeviceDnsRoute?,
        mullvad: DnsResolver? = null,
        wedge: DeviceResolverWedgeEvidence = DeviceResolverWedgeEvidence { false },
        clock: MonotonicClock = FakeClock(),
        transitionState: ResolverTransitionState = ResolverTransitionState(),
        leaseObserver: ResolverLeaseLifecycleObserver = ResolverLeaseLifecycleObserver.NONE,
        childDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): ResolverCoordinator = ResolverCoordinator(
        epoch = epochFor(plan),
        plan = plan,
        registry = registry,
        deviceRoute = device,
        mullvadRoute = mullvad,
        wedgeEvidence = wedge,
        clock = clock,
        transitionState = transitionState,
        leaseObserver = leaseObserver,
        childDispatcher = childDispatcher,
    )

    private fun readyPlan(
        configuration: ResolverConfiguration,
        networkContext: NetworkContext,
    ): ResolverPlan {
        val result = ResolverPlanFactory.build(configuration, networkContext)
        assertTrue("expected a ready resolver plan but was $result", result is ResolverPlanBuildResult.Ready)
        return (result as ResolverPlanBuildResult.Ready).plan
    }

    private fun devicePlan(
        addresses: List<InetAddress> = listOf(ip("192.0.2.53")),
        mullvadEnabled: Boolean,
    ): ResolverPlan = ResolverPlan(
        network = network(42),
        networkGeneration = 7L,
        isCellular = false,
        deviceDnsServers = addresses,
        mullvadEnabled = mullvadEnabled,
        primaryResolverKind = ResolverKind.DEVICE,
        primaryResolverIdentity = PrimaryResolverIdentity("device-primary"),
        fallbackCoverage = if (mullvadEnabled) {
            ResolverFallbackCoverage.ENCRYPTED_DNS
        } else {
            ResolverFallbackCoverage.NONE
        },
    )

    private fun mullvadPlan(): ResolverPlan = ResolverPlan(
        network = network(43),
        networkGeneration = 8L,
        isCellular = false,
        deviceDnsServers = emptyList(),
        mullvadEnabled = true,
        primaryResolverKind = ResolverKind.MULLVAD_DOH,
        primaryResolverIdentity = PrimaryResolverIdentity("mullvad-doh"),
        fallbackCoverage = ResolverFallbackCoverage.NONE,
    )

    private fun epochFor(plan: ResolverPlan): EngineEpoch = EngineEpoch(
        sessionGeneration = 1L,
        configurationIdentity = ConfigurationIdentity(1L, "cfg"),
        artifactDigest = "artifact",
        policyRevision = 1L,
        networkGeneration = plan.networkGeneration,
        engineGeneration = 1L,
        primaryResolverIdentity = plan.primaryResolverIdentity,
        fallbackCoverage = plan.fallbackCoverage,
    )

    private fun request(
        plan: ResolverPlan,
        queryId: Long = 1L,
        epoch: EngineEpoch = epochFor(plan),
        networkContext: NetworkContext = networkContext(plan),
        queryBytes: ByteArray = query(),
        parsedQuery: ByteArray = queryBytes,
    ): ResolverRequest = ResolverRequest(
        query = queryBytes,
        question = DnsMessage.parseQuestion(parsedQuery),
        epoch = epoch,
        networkContext = networkContext,
        queryId = queryId,
    )

    private fun networkContext(
        plan: ResolverPlan,
        network: Network = plan.network,
        networkGeneration: Long = plan.networkGeneration,
        addresses: List<InetAddress> = plan.deviceDnsServers,
        isCellular: Boolean = plan.isCellular,
    ): NetworkContext = NetworkContext(
        network = network,
        networkGeneration = networkGeneration,
        dnsServers = addresses,
        isCellular = isCellular,
    )

    private fun answer(
        request: ResolverRequest,
        transport: ResolverTransport,
    ): ResolverResult.Answer = ResolverResult.Answer(
        dnsMessage = response(request.query),
        transport = transport,
    )

    private fun response(query: ByteArray): ByteArray = query.copyOf().also { response ->
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
    }

    private fun query(id: Int = 0x1234): ByteArray = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01,
    )

    private fun assertAnswer(
        result: ResolverResult,
        transport: ResolverTransport,
    ): ResolverResult.Answer {
        assertTrue("expected answer but was $result", result is ResolverResult.Answer)
        result as ResolverResult.Answer
        assertEquals(transport, result.transport)
        return result
    }

    private fun assertFailure(
        result: ResolverResult,
        reason: ResolverFailureReason,
    ): ResolverResult.Failure {
        assertTrue("expected failure but was $result", result is ResolverResult.Failure)
        result as ResolverResult.Failure
        assertEquals(reason, result.reason)
        return result
    }

    private fun assertStale(
        label: String = "result",
        result: ResolverResult,
    ) {
        val failure = assertFailure(result, ResolverFailureReason.STALE_EPOCH)
        assertTrue(label, failure.healthEvents.isEmpty())
        assertNull(label, failure.resolver)
        assertNull(label, failure.resolverQueryEvidence)
    }

    private fun resultWithEvent(
        result: ResolverResult,
        event: DnsHealthEvent?,
    ): ResolverResult {
        val healthEvents = if (event == null) result.healthEvents else result.healthEvents + event
        return when (result) {
            is ResolverResult.Answer -> ResolverResult.Answer(
                dnsMessage = result.dnsMessage,
                transport = result.transport,
                healthEvents = healthEvents,
                resolverQueryEvidence = result.resolverQueryEvidence,
            )

            is ResolverResult.Failure -> ResolverResult.Failure(
                reason = result.reason,
                healthEvents = healthEvents,
                resolver = result.resolver,
                resolverQueryEvidence = result.resolverQueryEvidence,
            )
        }
    }

    private fun await(
        latch: CountDownLatch,
        label: String,
    ) {
        assertTrue("$label timed out", latch.await(2, TimeUnit.SECONDS))
    }

    private fun awaitThreadWaitingOrReturned(
        thread: Thread,
        returned: CountDownLatch,
        label: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            returned.count != 0L &&
            thread.state != Thread.State.WAITING &&
            thread.state != Thread.State.TIMED_WAITING &&
            System.nanoTime() < deadline
        ) {
            Thread.onSpinWait()
        }
        assertTrue(
            "$label neither entered a wait nor returned",
            returned.count == 0L ||
                thread.state == Thread.State.WAITING ||
                thread.state == Thread.State.TIMED_WAITING,
        )
    }

    private fun awaitWaitingOrReturned(
        thread: Thread,
        returned: CountDownLatch,
    ) {
        while (
            returned.count != 0L &&
            thread.state != Thread.State.WAITING &&
            thread.state != Thread.State.TIMED_WAITING
        ) {
            Thread.onSpinWait()
        }
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    private fun network(handle: Int): Network =
        Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(handle)

    private class FakeClock(
        private var now: Long = 0L,
    ) : MonotonicClock {
        override fun nowNanos(): Long = now
    }

    private class RecordingDeviceRoute(
        private val result: suspend (
            request: ResolverRequest,
            endpointBackoff: ResolverEndpointBackoffPolicy,
        ) -> ResolverResult,
    ) : DeviceDnsRoute {
        val requests = CopyOnWriteArrayList<ResolverRequest>()

        override suspend fun resolve(
            request: ResolverRequest,
            endpointBackoff: ResolverEndpointBackoffPolicy,
        ): DeviceDnsRouteResult {
            requests += request
            val routeResult = result(request, endpointBackoff)
            return DeviceDnsRouteResult(
                result = routeResult,
                answeredEndpoint = if (routeResult is ResolverResult.Answer) {
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

    private class RecordingResolver(
        private val result: suspend (ResolverRequest) -> ResolverResult,
    ) : DnsResolver {
        val requests = CopyOnWriteArrayList<ResolverRequest>()

        override suspend fun resolve(request: ResolverRequest): ResolverResult {
            requests += request
            return result(request)
        }
    }

    private class RecordingLeaseObserver(
        private val afterAcceptedBeforeStart: () -> Unit = {},
        private val beforeChildDrain: () -> Unit = {},
        private val onRetirementFinished: () -> Unit = {},
    ) : ResolverLeaseLifecycleObserver {
        val starts = AtomicInteger()
        val drains = AtomicInteger()
        val drained = CountDownLatch(1)
        val closeInvocations = AtomicInteger()
        val retirementStarts = AtomicInteger()
        val retirementFinishes = AtomicInteger()

        override fun afterAcceptedBeforeStart() {
            afterAcceptedBeforeStart.invoke()
        }

        override fun onStarted() {
            starts.incrementAndGet()
        }

        override fun beforeChildDrain() {
            beforeChildDrain.invoke()
        }

        override fun onChildDrained() {
            drains.incrementAndGet()
            drained.countDown()
        }

        override fun onCloseInvoked() {
            closeInvocations.incrementAndGet()
        }

        override fun onRetirementStarted() {
            retirementStarts.incrementAndGet()
        }

        override fun onRetirementFinished() {
            onRetirementFinished.invoke()
            retirementFinishes.incrementAndGet()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()

        val queuedTaskCount: Int
            get() = tasks.size

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.add(block)
        }

        fun runNext(): Boolean {
            val task = tasks.poll() ?: return false
            task.run()
            return true
        }

        fun runUntilIdle() {
            while (runNext()) {
                // Each task is fully controlled by this test thread.
            }
        }
    }

    private class RejectingRegistrationRegistry(
        private val delegate: GenerationSocketRegistry,
    ) : ResolverResourceRegistry {
        val registrations = AtomicInteger()
        val registryConsumptions = AtomicInteger()
        val unregisters = AtomicInteger()
        val completions = AtomicInteger()

        override fun registerIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
        ): Boolean {
            registrations.incrementAndGet()
            if (!delegate.registerIfCurrent(resource, epoch)) return false
            registryConsumptions.incrementAndGet()
            delegate.completeIfCurrent(resource, epoch) { false }
            return false
        }

        override fun isCurrent(epoch: EngineEpoch): Boolean = delegate.isCurrent(epoch)

        override fun unregister(resource: AutoCloseable): Boolean {
            unregisters.incrementAndGet()
            return delegate.unregister(resource)
        }

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean {
            completions.incrementAndGet()
            return delegate.completeIfCurrent(resource, epoch, completion)
        }
    }

    private class CountingCompletionRegistry(
        private val delegate: GenerationSocketRegistry,
    ) : ResolverResourceRegistry {
        val completions = AtomicInteger()

        override fun registerIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
        ): Boolean = delegate.registerIfCurrent(resource, epoch)

        override fun isCurrent(epoch: EngineEpoch): Boolean = delegate.isCurrent(epoch)

        override fun unregister(resource: AutoCloseable): Boolean = delegate.unregister(resource)

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean {
            completions.incrementAndGet()
            return delegate.completeIfCurrent(resource, epoch, completion)
        }
    }

    private class CapturingRegistrationRegistry(
        private val delegate: GenerationSocketRegistry,
    ) : ResolverResourceRegistry {
        val resource = AtomicReference<AutoCloseable?>()

        override fun registerIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
        ): Boolean = delegate.registerIfCurrent(resource, epoch).also { accepted ->
            if (accepted) this.resource.set(resource)
        }

        override fun isCurrent(epoch: EngineEpoch): Boolean = delegate.isCurrent(epoch)

        override fun unregister(resource: AutoCloseable): Boolean = delegate.unregister(resource)

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean = delegate.completeIfCurrent(resource, epoch, completion)
    }

    private class LoseFirstCompletionRegistry(
        private val delegate: GenerationSocketRegistry,
    ) : ResolverResourceRegistry {
        private val loseNext = AtomicBoolean(true)

        override fun registerIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
        ): Boolean = delegate.registerIfCurrent(resource, epoch)

        override fun isCurrent(epoch: EngineEpoch): Boolean = delegate.isCurrent(epoch)

        override fun unregister(resource: AutoCloseable): Boolean = delegate.unregister(resource)

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean = if (loseNext.compareAndSet(true, false)) {
            delegate.completeIfCurrent(resource, epoch) { false }
        } else {
            delegate.completeIfCurrent(resource, epoch, completion)
        }
    }

    private class BlockingCompletionRegistry(
        private val delegate: GenerationSocketRegistry,
    ) : ResolverResourceRegistry {
        val completionReached = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)

        override fun registerIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
        ): Boolean = delegate.registerIfCurrent(resource, epoch)

        override fun isCurrent(epoch: EngineEpoch): Boolean = delegate.isCurrent(epoch)

        override fun unregister(resource: AutoCloseable): Boolean = delegate.unregister(resource)

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean {
            completionReached.countDown()
            allowCompletion.await()
            return delegate.completeIfCurrent(resource, epoch, completion)
        }
    }

    private class BlockingUnregisterRegistry(
        private val delegate: GenerationSocketRegistry,
    ) : ResolverResourceRegistry {
        val unregisterEntered = CountDownLatch(1)
        val allowUnregister = CountDownLatch(1)

        override fun registerIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
        ): Boolean = delegate.registerIfCurrent(resource, epoch)

        override fun isCurrent(epoch: EngineEpoch): Boolean = delegate.isCurrent(epoch)

        override fun unregister(resource: AutoCloseable): Boolean {
            unregisterEntered.countDown()
            allowUnregister.await()
            return delegate.unregister(resource)
        }

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean = delegate.completeIfCurrent(resource, epoch, completion)
    }

    private class CountingResource : AutoCloseable {
        val closed = AtomicInteger()

        override fun close() {
            closed.incrementAndGet()
        }
    }
}
