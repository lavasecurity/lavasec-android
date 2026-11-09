package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ProtectionFailure
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoundedTunRuntimeCapacityTest {
    @Test
    fun `runtime owns one reader eight workers and one writer after query admission`() = runTest {
        val fixture = RuntimeFixture(this)
        try {
            fixture.start()
            runCurrent()

            val beforeAdmission = fixture.runtime.jobTopologyForTest()
            assertEquals(10, beforeAdmission.childCount)
            assertEquals(FIXED_PRODUCTION_JOB_IDENTITIES, beforeAdmission.childIdentities)

            repeat(72) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            runCurrent()

            val afterAdmission = fixture.runtime.jobTopologyForTest()
            assertEquals(10, afterAdmission.childCount)
            assertEquals(FIXED_PRODUCTION_JOB_IDENTITIES, afterAdmission.childIdentities)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `production runtime admits eight active and sixty four FIFO waiters`() = runTest {
        val fixture = RuntimeFixture(this)
        try {
            fixture.start()
            repeat(72) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            runCurrent()

            val snapshot = fixture.runtime.snapshotForTest()
            assertEquals(8, snapshot.activeResolutions)
            assertEquals(64, snapshot.waitingResolutions)
            assertEquals(72, snapshot.liveRecords)
            assertEquals(1, fixture.tun.maximumConcurrentReads.get())
            assertEquals(8, fixture.maximumConcurrentExecutions.get())
            assertEquals((1L..8L).toList(), fixture.proxy.executedIds)
            assertFalse(fixture.tun.writeAttempts.tryReceive().isSuccess)
            assertTrue(fixture.tun.recordedWrites.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `completed workers promote waiting forwards in FIFO order`() = runTest {
        val fixture = RuntimeFixture(
            scope = this,
            capacities = TunRuntimeCapacities(
                activeResolutions = 2,
                waitingResolutions = 3,
                materializedWrites = 2,
                liveRecords = 8,
            ),
        )
        try {
            fixture.start()
            repeat(5) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            runCurrent()
            assertEquals(listOf(1L, 2L), fixture.proxy.executedIds)

            repeat(3) { completedWaiters ->
                fixture.releaseResolver()
                runCurrent()
                assertEquals(
                    (1L..(completedWaiters + 3L)).toList(),
                    fixture.proxy.executedIds,
                )
            }
            assertEquals(5L, fixture.proxy.executedIds.last())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `seventy third forward is rejected and never executed`() = runTest {
        val fixture = RuntimeFixture(this)
        try {
            fixture.start()
            repeat(73) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            runCurrent()

            assertEquals(listOf(73L), fixture.proxy.rejectedIds)
            assertEquals((1L..8L).toList(), fixture.proxy.executedIds)
            assertFalse(73L in fixture.proxy.executedIds)
            val snapshot = fixture.runtime.snapshotForTest()
            assertEquals(8, snapshot.activeResolutions)
            assertEquals(64, snapshot.waitingResolutions)
            assertEquals(1, snapshot.materializedWrites)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `drop and immediate plans bypass full resolver capacity`() = runTest {
        val fixture = RuntimeFixture(
            scope = this,
            capacities = TunRuntimeCapacities(
                activeResolutions = 1,
                waitingResolutions = 1,
                materializedWrites = 2,
                liveRecords = 5,
            ),
        )
        fixture.planAsDrop(3)
        fixture.planAsImmediate(4)
        try {
            fixture.start()
            repeat(4) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            runCurrent()

            assertEquals(listOf(1L), fixture.proxy.executedIds)
            assertTrue(fixture.proxy.rejectedIds.isEmpty())
            val snapshot = fixture.runtime.snapshotForTest()
            assertEquals(1, snapshot.activeResolutions)
            assertEquals(1, snapshot.waitingResolutions)
            assertEquals(1, snapshot.materializedWrites)
            assertEquals(3, snapshot.liveRecords)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `writer pressure retains a completed worker active slot`() = runTest {
        val fixture = RuntimeFixture(
            scope = this,
            capacities = TunRuntimeCapacities(
                activeResolutions = 1,
                waitingResolutions = 1,
                materializedWrites = 1,
                liveRecords = 4,
            ),
        )
        fixture.planAsImmediate(1)
        fixture.completeWithPending(2)
        try {
            fixture.start()
            repeat(3) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            runCurrent()
            assertEquals(listOf(2L), fixture.proxy.executedIds)

            fixture.releaseResolver()
            runCurrent()

            val snapshot = fixture.runtime.snapshotForTest()
            assertEquals(1, snapshot.activeResolutions)
            assertEquals(1, snapshot.waitingResolutions)
            assertEquals(1, snapshot.materializedWrites)
            assertEquals("AwaitingOriginalWriteCapacity", snapshot.stateFor(2L))
            assertEquals("WaitingResolution", snapshot.stateFor(3L))
            assertEquals(listOf(2L), fixture.proxy.executedIds)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `reader stops behind one immediate intent awaiting writer capacity`() = runTest {
        val fixture = RuntimeFixture(
            scope = this,
            capacities = TunRuntimeCapacities(
                activeResolutions = 1,
                waitingResolutions = 1,
                materializedWrites = 1,
                liveRecords = 5,
            ),
        )
        fixture.planAsImmediate(1)
        fixture.planAsImmediate(2)
        try {
            repeat(3) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            fixture.start()
            runCurrent()

            assertEquals(2, fixture.tun.readCalls.get())
            assertEquals(2, fixture.proxy.plannedPackets.size)
            assertEquals(
                "AwaitingOriginalWriteCapacity",
                fixture.runtime.snapshotForTest().stateFor(2),
            )

            check(fixture.tun.writeResults.trySend(TunWriteAttempt.Written).isSuccess)
            check(fixture.tun.writableResults.trySend(TunWritable.Writable).isSuccess)
            runCurrent()

            assertEquals(3, fixture.proxy.plannedPackets.size)
            assertEquals("StagedOriginal", fixture.runtime.snapshotForTest().stateFor(2))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `reader stops behind one overload intent awaiting writer capacity`() = runTest {
        val fixture = RuntimeFixture(
            scope = this,
            capacities = TunRuntimeCapacities(
                activeResolutions = 1,
                waitingResolutions = 1,
                materializedWrites = 1,
                liveRecords = 5,
            ),
        )
        fixture.planAsImmediate(1)
        try {
            repeat(5) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            fixture.start()
            runCurrent()

            assertEquals(listOf(4L), fixture.proxy.rejectedIds)
            assertEquals(4, fixture.tun.readCalls.get())
            assertEquals(4, fixture.proxy.plannedPackets.size)
            assertEquals(
                "AwaitingOriginalWriteCapacity",
                fixture.runtime.snapshotForTest().stateFor(4),
            )

            check(fixture.tun.writeResults.trySend(TunWriteAttempt.Written).isSuccess)
            check(fixture.tun.writableResults.trySend(TunWritable.Writable).isSuccess)
            runCurrent()

            assertEquals(5, fixture.tun.readCalls.get())
            assertEquals(5, fixture.proxy.plannedPackets.size)
            assertEquals("StagedOriginal", fixture.runtime.snapshotForTest().stateFor(4))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `reader holds the plus one record and stops at one hundred thirty seven`() = runTest {
        val fixture = RuntimeFixture(this)
        try {
            fixture.start()
            repeat(138) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            runCurrent()

            val snapshot = fixture.runtime.snapshotForTest()
            assertEquals(137, snapshot.liveRecords)
            assertEquals(8, snapshot.activeResolutions)
            assertEquals(64, snapshot.waitingResolutions)
            assertEquals(64, snapshot.materializedWrites)
            assertEquals(137, fixture.proxy.plannedPackets.size)
            assertEquals(137, fixture.tun.readCalls.get())
            assertEquals((73L..137L).toList(), fixture.proxy.rejectedIds)
            assertTrue(fixture.tun.reads.tryReceive().isSuccess)
            assertEquals(8, fixture.maximumConcurrentExecutions.get())
            assertEquals(1, fixture.tun.attemptedWrites.size)
            assertTrue(fixture.tun.recordedWrites.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `completed write wakes reader stopped at production live record capacity`() = runTest {
        val fixture = RuntimeFixture(this)
        try {
            repeat(138) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            fixture.start()
            runCurrent()

            assertEquals(137, fixture.proxy.plannedPackets.size)
            assertEquals(137, fixture.runtime.snapshotForTest().liveRecords)
            assertEquals(1, fixture.tun.attemptedWrites.size)

            check(fixture.tun.writeResults.trySend(TunWriteAttempt.Written).isSuccess)
            check(fixture.tun.writableResults.trySend(TunWritable.Writable).isSuccess)
            runCurrent()

            val afterWrite = fixture.runtime.snapshotForTest()
            assertEquals(
                "snapshot=$afterWrite readCalls=${fixture.tun.readCalls.get()} " +
                    "attempts=${fixture.tun.attemptedWrites.size}",
                138,
                fixture.proxy.plannedPackets.size,
            )
            assertEquals(138, fixture.tun.readCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `completed write materializes blocked pending and wakes promoted waiter`() = runTest {
        val fixture = RuntimeFixture(
            scope = this,
            capacities = TunRuntimeCapacities(
                activeResolutions = 1,
                waitingResolutions = 1,
                materializedWrites = 1,
                liveRecords = 4,
            ),
        )
        fixture.planAsImmediate(1)
        fixture.completeWithPending(2)
        try {
            repeat(3) {
                fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = it + 1))
            }
            fixture.start()
            runCurrent()
            fixture.releaseResolver()
            runCurrent()

            assertEquals(
                "AwaitingOriginalWriteCapacity",
                fixture.runtime.snapshotForTest().stateFor(2L),
            )
            assertEquals(
                "WaitingResolution",
                fixture.runtime.snapshotForTest().stateFor(3L),
            )

            check(fixture.tun.writeResults.trySend(TunWriteAttempt.Written).isSuccess)
            check(fixture.tun.writableResults.trySend(TunWritable.Writable).isSuccess)
            runCurrent()

            assertEquals(listOf(2L, 3L), fixture.proxy.executedIds)
            assertEquals("Resolving", fixture.runtime.snapshotForTest().stateFor(3L))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `intentional close is silent and launches no writer work`() = runTest {
        val fixture = RuntimeFixture(this)
        fixture.start()
        runCurrent()

        fixture.runtime.closeAndJoin()
        runCurrent()

        assertTrue(fixture.observer.failures.isEmpty())
        assertEquals(1, fixture.tun.closeCalls.get())
        assertEquals(1, fixture.engine.closeCalls)
        assertFalse(fixture.tun.writeAttempts.tryReceive().isSuccess)
        assertTrue(fixture.tun.recordedWrites.isEmpty())
    }

    @Test
    fun `EOF read failure and planning failure publish exact terminal failures`() = runTest {
        val eof = RuntimeFixture(this)
        eof.start()
        eof.tun.enqueue(TunReadScript.EndOfStream)
        runCurrent()
        eof.runtime.join()
        assertEquals(listOf(ProtectionFailure.TunnelEnded), eof.observer.failures)

        val readFailure = RuntimeFixture(this)
        readFailure.start()
        readFailure.tun.enqueue(TunReadScript.Failed(IllegalStateException("read")))
        runCurrent()
        readFailure.runtime.join()
        assertEquals(
            listOf(ProtectionFailure.TunnelReadFailed),
            readFailure.observer.failures,
        )

        val planningFailure = RuntimeFixture(this)
        planningFailure.failPlanning(1)
        planningFailure.start()
        planningFailure.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = 1))
        runCurrent()
        planningFailure.runtime.join()
        assertEquals(
            listOf(ProtectionFailure.DependencyUnavailable),
            planningFailure.observer.failures,
        )
        assertEquals(0, planningFailure.runtime.snapshotForTest().liveRecords)
    }

    @Test
    fun `execution dependency failure seals and releases the runtime`() = runTest {
        val fixture = RuntimeFixture(this)
        fixture.failExecution(1)
        fixture.start()
        fixture.tun.enqueuePacket(testRuntimeDnsPacket(transactionId = 1))

        runCurrent()
        fixture.runtime.join()

        assertEquals(listOf(1L), fixture.proxy.executedIds)
        assertEquals(
            listOf(ProtectionFailure.DependencyUnavailable),
            fixture.observer.failures,
        )
        assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
        assertEquals(1, fixture.tun.closeCalls.get())
        assertEquals(1, fixture.engine.closeCalls)
    }

    @Test
    fun `packet count above MTU fails before copy or planning`() = runTest {
        val fixture = RuntimeFixture(this)
        try {
            fixture.start()
            fixture.tun.enqueuePacket(ByteArray(DnsOnlyTunContract.MTU + 1))
            runCurrent()
            fixture.runtime.join()

            assertEquals(
                listOf(ProtectionFailure.TunnelReadFailed),
                fixture.observer.failures,
            )
            assertTrue(fixture.proxy.plannedPackets.isEmpty())
            assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `initial engine coherence is fully validated before launch`() = runTest {
        val policyIdentity = testPolicyIdentity()
        val networkContext = testNetworkContext()
        val resolverPlan = testResolverPlan(networkContext)
        val epoch = testEpoch(
            policyIdentity = policyIdentity,
            resolverPlan = resolverPlan,
            sessionGeneration = SESSION_TOKEN,
        )
        val proxy = TestRuntimeDnsProxy(
            planner = { _, _ -> ProxyPlan.Drop },
            unavailablePlanner = { _, _ -> ProxyPlan.Drop },
            executor = { ProxyResult.NotPublishable },
            overloadRejector = { ProxyResult.NotPublishable },
        )
        fun engine(
            selectedEpoch: EngineEpoch = epoch,
            selectedNetworkContext: NetworkContext = networkContext,
            selectedResolverPlan: ResolverPlan = resolverPlan,
            selectedPolicyIdentity: ProxyPolicyIdentity = policyIdentity,
        ) = TestReadyDnsEngine(
            epoch = selectedEpoch,
            networkContext = selectedNetworkContext,
            resolverPlan = selectedResolverPlan,
            policyIdentity = selectedPolicyIdentity,
            proxy = proxy,
        )

        val incoherentEngines = listOf(
            engine(selectedEpoch = epoch.copy(sessionGeneration = SESSION_TOKEN + 1)),
            engine(
                selectedPolicyIdentity = policyIdentity.copy(
                    configurationIdentity = testConfigurationIdentity(generation = 99L),
                ),
            ),
            engine(
                selectedPolicyIdentity =
                    policyIdentity.copy(artifactDigest = "different-artifact"),
            ),
            engine(
                selectedPolicyIdentity = policyIdentity.copy(
                    revision = policyIdentity.revision + 1,
                ),
            ),
            engine(
                selectedNetworkContext = testNetworkContext(
                    network = networkContext.network,
                    networkGeneration = networkContext.networkGeneration + 1,
                ),
            ),
            engine(
                selectedResolverPlan = testResolverPlan(
                    networkContext,
                    network = testNetwork(handle = 99),
                ),
            ),
            engine(
                selectedResolverPlan = testResolverPlan(
                    networkContext,
                    networkGeneration = networkContext.networkGeneration + 1,
                ),
            ),
            engine(
                selectedResolverPlan = testResolverPlan(
                    networkContext,
                    isCellular = !networkContext.isCellular,
                ),
            ),
            engine(
                selectedEpoch = epoch.copy(
                    primaryResolverIdentity = PrimaryResolverIdentity("different-resolver"),
                ),
            ),
            engine(
                selectedEpoch = epoch.copy(
                    fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                ),
            ),
        )

        incoherentEngines.forEach { incoherent ->
            val tun = ScriptedTunPacketSession()
            val runtime = BoundedTunRuntime(
                sessionToken = SESSION_TOKEN,
                packetSession = tun,
                observer = RecordingTunRuntimeObserver(),
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            assertThrows(IllegalArgumentException::class.java) {
                runtime.start(incoherent)
            }
            assertEquals(0, tun.readCalls.get())
            assertEquals(0, tun.closeCalls.get())
            assertEquals(0, incoherent.closeCalls)
        }

        val zeroSessionRuntime = BoundedTunRuntime(
            sessionToken = 0L,
            packetSession = ScriptedTunPacketSession(),
            observer = RecordingTunRuntimeObserver(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        assertThrows(IllegalArgumentException::class.java) {
            zeroSessionRuntime.start(engine())
        }
    }

    @Test
    fun `start is one shot`() = runTest {
        val fixture = RuntimeFixture(this)
        try {
            fixture.start()
            assertThrows(IllegalStateException::class.java) {
                fixture.runtime.start(fixture.engine)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `close before start rejects later engine adoption`() = runTest {
        val fixture = RuntimeFixture(this)

        fixture.runtime.requestClose()

        assertThrows(IllegalStateException::class.java) {
            fixture.runtime.start(fixture.engine)
        }
        assertEquals(0, fixture.engine.closeCalls)
        assertEquals(0, fixture.tun.readCalls.get())
    }

    @Test
    fun `close winning during validation prevents adoption and job entry`() = runTest {
        val fixture = RuntimeFixture(this)
        val barrierEngine = ValidationBarrierEngine(fixture.engine)
        val startAttempt = async(Dispatchers.Default) {
            runCatching { fixture.runtime.start(barrierEngine) }
        }
        barrierEngine.validationEntered.receive()

        fixture.runtime.requestClose()
        barrierEngine.releaseValidation()

        val startFailure = startAttempt.await().exceptionOrNull()
        assertTrue(startFailure is IllegalStateException)
        val topology = fixture.runtime.jobTopologyForTest()
        assertEquals(0, topology.childCount)
        assertTrue(topology.childIdentities.isEmpty())
        assertEquals(0, fixture.tun.readCalls.get())
        assertEquals(1, fixture.tun.closeCalls.get())
        assertEquals(0, fixture.engine.closeCalls)

        barrierEngine.close()
        assertEquals(1, fixture.engine.closeCalls)
    }

    @Test
    fun `immediate dispatcher never enters packet session while runtime lock is held`() = runTest {
        val fixture = RuntimeFixture(
            scope = this,
            dispatcher = Dispatchers.Unconfined,
        )
        val lockField = BoundedTunRuntime::class.java.getDeclaredField("lock").apply {
            isAccessible = true
        }
        val runtimeLock = lockField.get(fixture.runtime) as ReentrantLock
        var externalCallHeldRuntimeLock: Boolean? = null
        fixture.tun.onRead = {
            externalCallHeldRuntimeLock = runtimeLock.isHeldByCurrentThread
        }
        fixture.tun.enqueue(TunReadScript.EndOfStream)

        fixture.start()
        fixture.runtime.join()

        assertEquals(false, externalCallHeldRuntimeLock)
    }

    private class RuntimeFixture(
        scope: TestScope,
        val capacities: TunRuntimeCapacities = TunRuntimeCapacities.Production,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
    ) {
        val tun = ScriptedTunPacketSession()
        val observer = RecordingTunRuntimeObserver()
        val maximumConcurrentExecutions = AtomicInteger()

        private val concurrentExecutions = AtomicInteger()
        private val resolverReleases = Channel<Unit>(Channel.UNLIMITED)
        private val modes = ConcurrentHashMap<Long, PlanMode>()
        private val resolutionResults = ConcurrentHashMap<Long, ProxyResult>()
        private val executionFailures = ConcurrentHashMap.newKeySet<Long>()
        private val networkContext = testNetworkContext()
        private val resolverPlan = testResolverPlan(networkContext)
        private val policyIdentity = testPolicyIdentity()

        val engine: TestReadyDnsEngine

        val proxy = TestRuntimeDnsProxy(
            planner = { packet, readyEngine ->
                val queryId = packet.runtimeTransactionId()
                when (modes[queryId] ?: PlanMode.Forward) {
                    PlanMode.Drop -> ProxyPlan.Drop
                    PlanMode.Immediate -> immediatePlan(packet, readyEngine)
                    PlanMode.Fail -> throw IllegalStateException("planning failed")
                    PlanMode.Forward -> forwardPlan(packet, readyEngine)
                }
            },
            unavailablePlanner = { _, _ -> ProxyPlan.Drop },
            executor = { plan ->
                if (executionFailures.remove(plan.request.queryId)) {
                    throw IllegalStateException("execution failed")
                }
                val concurrent = concurrentExecutions.incrementAndGet()
                maximumConcurrentExecutions.updateAndGet { maximum ->
                    maxOf(maximum, concurrent)
                }
                try {
                    resolverReleases.receive()
                    resolutionResults.remove(plan.request.queryId)
                        ?: ProxyResult.NotPublishable
                } finally {
                    concurrentExecutions.decrementAndGet()
                }
            },
            overloadRejector = { plan ->
                pending(plan.engine, plan.replacement.snapshot())
            },
        )

        val runtime: BoundedTunRuntime

        init {
            engine = TestReadyDnsEngine(
                epoch = testEpoch(
                    policyIdentity = policyIdentity,
                    resolverPlan = resolverPlan,
                    sessionGeneration = SESSION_TOKEN,
                ),
                networkContext = networkContext,
                resolverPlan = resolverPlan,
                policyIdentity = policyIdentity,
                proxy = proxy,
            )
            runtime = BoundedTunRuntime(
                sessionToken = SESSION_TOKEN,
                packetSession = tun,
                observer = observer,
                dispatcher = dispatcher,
                capacities = capacities,
            )
        }

        fun start() {
            runtime.start(engine)
        }

        fun planAsDrop(queryId: Long) {
            modes[queryId] = PlanMode.Drop
        }

        fun planAsImmediate(queryId: Long) {
            modes[queryId] = PlanMode.Immediate
        }

        fun failPlanning(queryId: Long) {
            modes[queryId] = PlanMode.Fail
        }

        fun failExecution(queryId: Long) {
            executionFailures += queryId
        }

        fun completeWithPending(queryId: Long) {
            resolutionResults[queryId] =
                pending(engine, testRuntimeDnsPacket(queryId.toInt()))
        }

        fun releaseResolver() {
            check(resolverReleases.trySend(Unit).isSuccess)
        }

        suspend fun close() {
            runtime.closeAndJoin()
        }

        private fun forwardPlan(
            packet: ByteArray,
            readyEngine: ReadyDnsEngine,
        ): ProxyPlan.Forward {
            val datagram = checkNotNull(Ipv4UdpDnsPacket.parse(packet))
            val query = datagram.payload
            val question = DnsMessage.parseQuestion(query)
            val queryId = packet.runtimeTransactionId()
            return ProxyPlan.Forward.create(
                request = ResolverRequest(
                    query = query,
                    question = question,
                    epoch = readyEngine.epoch,
                    networkContext = readyEngine.networkContext,
                    queryId = queryId,
                ),
                activity = DnsDecisionEvent(
                    id = queryId,
                    timestampMillis = TEST_NOW_MILLIS,
                    normalizedDomain = question.normalizedDomain,
                    decision = FilterDecision.DEFAULT_ALLOW,
                ),
                policyIdentity = readyEngine.policyIdentity,
                plannedProtectionSessionId = TEST_PROTECTION_SESSION_ID,
                plannedPauseMaximumResponseTtlSeconds = null,
                startedPaused = false,
                replacement = ProxyReplacementResponse(packet),
                engine = readyEngine,
                datagram = datagram,
                owner = proxy,
            )
        }

        private fun immediatePlan(
            packet: ByteArray,
            readyEngine: ReadyDnsEngine,
        ) = ProxyPlan.Immediate(
            result = pending(readyEngine, packet),
            replacement = ProxyReplacementResponse(packet),
        )

        private fun pending(
            readyEngine: ReadyDnsEngine,
            packet: ByteArray,
        ) = ProxyResult.Pending(
            fence = readyEngine,
            publication = ProxyPublication(
                responsePacket = packet,
                activity = null,
                healthEvents = emptyList(),
                resolverQueryEvidence = null,
            ),
            sink = runtime.publicationSink,
        )
    }

    private enum class PlanMode {
        Forward,
        Drop,
        Immediate,
        Fail,
    }

    private class ValidationBarrierEngine(
        private val delegate: ReadyDnsEngine,
    ) : ReadyDnsEngine {
        val validationEntered = Channel<Unit>(capacity = 1)
        private val validationRelease = Channel<Unit>(capacity = 1)
        private val shouldBlock = AtomicBoolean(true)

        override val epoch: EngineEpoch
            get() {
                if (shouldBlock.compareAndSet(true, false)) {
                    runBlocking {
                        validationEntered.send(Unit)
                        validationRelease.receive()
                    }
                }
                return delegate.epoch
            }
        override val networkContext: NetworkContext
            get() = delegate.networkContext
        override val resolverPlan: ResolverPlan
            get() = delegate.resolverPlan
        override val policyIdentity: ProxyPolicyIdentity
            get() = delegate.policyIdentity
        override val proxy: DnsProxy
            get() = delegate.proxy

        fun releaseValidation() {
            check(validationRelease.trySend(Unit).isSuccess)
        }

        override fun isCurrent(): Boolean = delegate.isCurrent()

        override suspend fun resolveIfCurrent(request: ResolverRequest): ResolverResult =
            delegate.resolveIfCurrent(request)

        override fun completeIfCurrent(completion: () -> Boolean): Boolean =
            delegate.completeIfCurrent(completion)

        override fun close() {
            delegate.close()
        }
    }

    private companion object {
        const val SESSION_TOKEN = 23L
        val FIXED_PRODUCTION_JOB_IDENTITIES = listOf(
            "tun-worker-0",
            "tun-worker-1",
            "tun-worker-2",
            "tun-worker-3",
            "tun-worker-4",
            "tun-worker-5",
            "tun-worker-6",
            "tun-worker-7",
            "tun-reader",
            "tun-writer",
        )
    }
}

private fun TunSchedulerSnapshot.stateFor(queryId: Long): String? =
    states[TunQueryId(queryId)]

private fun ByteArray.runtimeTransactionId(): Long =
    readUInt16(28).toLong()

private fun testRuntimeDnsPacket(transactionId: Int): ByteArray =
    testDnsPacket(testDnsQuery(transactionId = transactionId))
