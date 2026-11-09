package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import java.util.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val ACTUAL_STRESS_TRANSITIONS = 10_000
private const val ACTUAL_STRESS_RESTART_INDEX = 5_000
private const val ACTUAL_STRESS_INITIAL_GENERATION = 29L
private const val ACTUAL_STRESS_ORIGINAL_MAGIC = 0x4F
private const val ACTUAL_STRESS_REPLACEMENT_MAGIC = 0x52

private val ACTUAL_STRESS_CAPACITIES = TunRuntimeCapacities(
    activeResolutions = 1,
    waitingResolutions = 2,
    materializedWrites = 2,
    liveRecords = 6,
)

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoundedTunRuntimeActualStressTest {
    @Test
    fun `actual runtime seed 0x5A17C0DE settles ten thousand transitions`() = runTest {
        runActualRuntimeSeed(seed = 0x5A17C0DEL)
    }

    @Test
    fun `actual runtime seed 0x12D024 settles ten thousand transitions`() = runTest {
        runActualRuntimeSeed(seed = 0x12D024L)
    }

    @Test
    fun `actual runtime seed 0x086464137 settles ten thousand transitions`() = runTest {
        runActualRuntimeSeed(seed = 0x086464137L)
    }
}

private suspend fun TestScope.runActualRuntimeSeed(seed: Long) {
    ActualRuntimeSeedScenario(this, seed).run()
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ActualRuntimeSeedScenario(
    private val scope: TestScope,
    private val seed: Long,
) {
    private val random = Random(seed)
    private val stimulusCounts =
        ActualRuntimeStimulus.entries.associateWith { 0 }.toMutableMap()
    private val sessions = mutableListOf<ActualRuntimeSession>()
    private lateinit var current: ActualRuntimeSession
    private var transitionIndex = 0

    suspend fun run() {
        current = newSession()
        forcedPrefix().forEach { apply(it) }
        while (transitionIndex < ACTUAL_STRESS_RESTART_INDEX) {
            apply(nextWeightedStimulus())
        }
        apply(ActualRuntimeStimulus.CLOSE_AND_RESTART)
        forcedPrefix().forEach { apply(it) }
        while (transitionIndex < ACTUAL_STRESS_TRANSITIONS - 1) {
            apply(nextWeightedStimulus())
        }
        apply(ActualRuntimeStimulus.CLOSE)

        assertEquals(ACTUAL_STRESS_TRANSITIONS, transitionIndex)
        assertEquals(2, sessions.size)
        assertEquals(1, stimulusCounts.getValue(ActualRuntimeStimulus.CLOSE))
        assertEquals(
            1,
            stimulusCounts.getValue(ActualRuntimeStimulus.CLOSE_AND_RESTART),
        )
        listOf(
            ActualRuntimeStimulus.READ_DROP,
            ActualRuntimeStimulus.READ_IMMEDIATE,
            ActualRuntimeStimulus.READ_FORWARD_PUBLISH,
            ActualRuntimeStimulus.READ_FORWARD_REPLACE,
            ActualRuntimeStimulus.COMPLETE_RESOLUTION,
            ActualRuntimeStimulus.SPURIOUS_WRITABLE,
            ActualRuntimeStimulus.COMPLETE_WRITABLE,
            ActualRuntimeStimulus.REPLACE,
        ).forEach { stimulus ->
            assertTrue(
                "seed=${seedLabel()} did not exercise $stimulus",
                stimulusCounts.getValue(stimulus) > 0,
            )
        }
        assertTrue(sessions.sumOf(ActualRuntimeSession::plannedCount) > 0)
        assertTrue(sessions.sumOf(ActualRuntimeSession::completedResolutionCount) > 0)
        assertTrue(sessions.sumOf(ActualRuntimeSession::evidenceCount) > 0)
        assertTrue(
            sessions.flatMap(ActualRuntimeSession::emittedKinds)
                .containsAll(ActualWireKind.entries),
        )
        sessions.forEach(ActualRuntimeSession::assertFullyReleased)

        println(
            "actual bounded TUN seed=${seedLabel()} " +
                "transitions=$transitionIndex sessions=${sessions.size} " +
                "plans=${sessions.sumOf(ActualRuntimeSession::plannedCount)} " +
                "writes=${sessions.sumOf(ActualRuntimeSession::writeCount)} " +
                "evidence=${sessions.sumOf(ActualRuntimeSession::evidenceCount)}",
        )
    }

    private suspend fun apply(stimulus: ActualRuntimeStimulus) {
        try {
            when (stimulus) {
                ActualRuntimeStimulus.READ_DROP ->
                    current.readAndSettle(ActualPlanMode.Drop)
                ActualRuntimeStimulus.READ_IMMEDIATE ->
                    current.readAndSettle(ActualPlanMode.Immediate)
                ActualRuntimeStimulus.READ_FORWARD_PUBLISH ->
                    current.readAndSettle(ActualPlanMode.ForwardPublish)
                ActualRuntimeStimulus.READ_FORWARD_REPLACE ->
                    current.readAndSettle(ActualPlanMode.ForwardReplace)
                ActualRuntimeStimulus.COMPLETE_RESOLUTION ->
                    current.completeOldestResolutionAndSettle()
                ActualRuntimeStimulus.SPURIOUS_WRITABLE ->
                    current.signalWritableAndSettle(written = false)
                ActualRuntimeStimulus.COMPLETE_WRITABLE ->
                    current.signalWritableAndSettle(written = true)
                ActualRuntimeStimulus.REPLACE ->
                    current.replaceAndSettle()
                ActualRuntimeStimulus.CLOSE_AND_RESTART -> {
                    current.closeAndSettle()
                    current = newSession()
                }
                ActualRuntimeStimulus.CLOSE ->
                    current.closeAndSettle()
            }
            stimulusCounts[stimulus] = stimulusCounts.getValue(stimulus) + 1
            transitionIndex += 1
        } catch (failure: Throwable) {
            throw AssertionError(
                "seed=${seedLabel()} transitionIndex=$transitionIndex " +
                    "stimulus=$stimulus snapshot=${current.snapshot()}",
                failure,
            )
        }
    }

    private fun nextWeightedStimulus(): ActualRuntimeStimulus {
        val weighted = buildList {
            if (current.canRead()) {
                add(ActualRuntimeStimulus.READ_DROP)
                repeat(3) { add(ActualRuntimeStimulus.READ_IMMEDIATE) }
                repeat(4) { add(ActualRuntimeStimulus.READ_FORWARD_PUBLISH) }
                repeat(2) { add(ActualRuntimeStimulus.READ_FORWARD_REPLACE) }
            }
            if (current.canCompleteResolution()) {
                repeat(5) { add(ActualRuntimeStimulus.COMPLETE_RESOLUTION) }
            }
            if (current.canSignalWritable()) {
                add(ActualRuntimeStimulus.SPURIOUS_WRITABLE)
                repeat(6) { add(ActualRuntimeStimulus.COMPLETE_WRITABLE) }
            }
            add(ActualRuntimeStimulus.REPLACE)
        }
        return weighted[random.nextInt(weighted.size)]
    }

    private fun forcedPrefix(): List<ActualRuntimeStimulus> = listOf(
        ActualRuntimeStimulus.READ_IMMEDIATE,
        ActualRuntimeStimulus.SPURIOUS_WRITABLE,
        ActualRuntimeStimulus.COMPLETE_WRITABLE,
        ActualRuntimeStimulus.READ_FORWARD_PUBLISH,
        ActualRuntimeStimulus.COMPLETE_RESOLUTION,
        ActualRuntimeStimulus.REPLACE,
        ActualRuntimeStimulus.COMPLETE_WRITABLE,
        ActualRuntimeStimulus.READ_DROP,
        ActualRuntimeStimulus.READ_FORWARD_PUBLISH,
        ActualRuntimeStimulus.REPLACE,
        ActualRuntimeStimulus.COMPLETE_RESOLUTION,
        ActualRuntimeStimulus.COMPLETE_WRITABLE,
    )

    private suspend fun newSession(): ActualRuntimeSession {
        val session = ActualRuntimeSession(
            scope = scope,
            sessionToken = 100L + sessions.size,
        )
        sessions += session
        session.settleStart()
        return session
    }

    private fun seedLabel(): String = "0x${seed.toString(16).uppercase()}"
}

private enum class ActualRuntimeStimulus {
    READ_DROP,
    READ_IMMEDIATE,
    READ_FORWARD_PUBLISH,
    READ_FORWARD_REPLACE,
    COMPLETE_RESOLUTION,
    SPURIOUS_WRITABLE,
    COMPLETE_WRITABLE,
    REPLACE,
    CLOSE_AND_RESTART,
    CLOSE,
}

private enum class ActualPlanMode {
    Drop,
    Immediate,
    ForwardPublish,
    ForwardReplace,
}

private enum class ActualWireKind(
    val magic: Int,
) {
    Original(ACTUAL_STRESS_ORIGINAL_MAGIC),
    Replacement(ACTUAL_STRESS_REPLACEMENT_MAGIC),
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ActualRuntimeSession(
    private val scope: TestScope,
    sessionToken: Long,
) {
    private val fixture = ActualRuntimeFixture(scope, sessionToken)
    private val expectedPackets = mutableMapOf<Int, ByteArray>()
    private val emittedTicketIds = mutableSetOf<Int>()
    private val originalTicketIds = mutableSetOf<Int>()
    private val evidenceTicketIds = mutableSetOf<Int>()
    private var nextTicketId = 1
    private var attemptedWriteCursor = 0
    private var recordedWriteCursor = 0
    private var evidenceCursor = 0
    private var readinessSignals = 0
    private var closed = false

    init {
        fixture.start()
    }

    val plannedCount: Int
        get() = fixture.plannedTicketIds.size

    val completedResolutionCount: Int
        get() = fixture.completedResolutionIds.size

    val writeCount: Int
        get() = fixture.tun.recordedWrites.size

    val evidenceCount: Int
        get() = fixture.observer.evidence.size

    val emittedKinds: List<ActualWireKind>
        get() = fixture.tun.recordedWrites.map(::decodeActualStressPacket)
            .map(ActualStressPacket::kind)

    fun canRead(): Boolean =
        !closed &&
            nextTicketId <= 0xffff &&
            snapshot().readerPermitHeld

    fun canCompleteResolution(): Boolean =
        !closed && fixture.incompleteResolutionIds().isNotEmpty()

    fun canSignalWritable(): Boolean =
        !closed && fixture.tun.writableCalls.get() > readinessSignals

    suspend fun settleStart() {
        scope.runCurrent()
        assertSettled()
        assertTrue(snapshot().readerPermitHeld)
    }

    suspend fun readAndSettle(mode: ActualPlanMode) {
        check(canRead())
        val ticketId = nextTicketId++
        fixture.setMode(ticketId, mode)
        if (mode != ActualPlanMode.Drop) {
            expectedPackets[ticketId] =
                actualStressPacket(ActualWireKind.Original, ticketId)
        }
        val plannedBefore = fixture.plannedTicketIds.size
        fixture.tun.enqueuePacket(
            testDnsPacket(testDnsQuery(transactionId = ticketId)),
        )
        scope.runCurrent()

        assertEquals(plannedBefore + 1, fixture.plannedTicketIds.size)
        assertEquals(ticketId, fixture.plannedTicketIds.last())
        assertSettled()
    }

    suspend fun completeOldestResolutionAndSettle() {
        val ticketId = fixture.incompleteResolutionIds().minOrNull()
            ?: error("no resolution completion is enabled")
        if (fixture.mode(ticketId) == ActualPlanMode.ForwardReplace) {
            expectedPackets[ticketId] =
                actualStressPacket(ActualWireKind.Replacement, ticketId)
        }
        fixture.completeResolution(ticketId)
        scope.runCurrent()
        assertSettled()
    }

    suspend fun signalWritableAndSettle(written: Boolean) {
        check(canSignalWritable())
        if (written) fixture.scriptWritten()
        fixture.signalWritable()
        readinessSignals += 1
        scope.runCurrent()
        assertSettled()
    }

    suspend fun replaceAndSettle() {
        check(!closed)
        snapshot().states.keys.forEach { id ->
            val ticketId = id.value.toInt()
            if (expectedPackets.containsKey(ticketId)) {
                expectedPackets[ticketId] =
                    actualStressPacket(ActualWireKind.Replacement, ticketId)
            }
        }
        assertTrue(fixture.replaceEngine())
        scope.runCurrent()
        assertSettled()
    }

    suspend fun closeAndSettle() {
        check(!closed)
        fixture.runtime.requestClose()
        scope.runCurrent()
        fixture.runtime.join()
        scope.runCurrent()
        closed = true
        assertSettled()
        assertFullyReleased()
    }

    fun snapshot(): TunSchedulerSnapshot = fixture.runtime.snapshotForTest()

    fun assertFullyReleased() {
        assertTrue(closed)
        val snapshot = snapshot()
        assertEquals(0, snapshot.liveRecords)
        assertEquals(0, snapshot.activeResolutions)
        assertEquals(0, snapshot.waitingResolutions)
        assertEquals(0, snapshot.materializedWrites)
        assertEquals(0, snapshot.replacementPromotions)
        assertTrue(snapshot.states.isEmpty())
        assertFalse(snapshot.readerPermitHeld)
        assertEquals(0, fixture.runtime.jobTopologyForTest().childCount)
        assertEquals(1, fixture.tun.closeCalls.get())
        assertEquals(
            fixture.tun.attemptedWrites.size,
            attemptedWriteCursor,
        )
        assertEquals(
            fixture.tun.recordedWrites.size,
            recordedWriteCursor,
        )
        assertEquals(
            fixture.observer.evidence.size,
            evidenceCursor,
        )
        assertTrue(fixture.activeExecutionIds.isEmpty())
        fixture.engines.forEach { engine ->
            assertEquals(1, engine.closeCalls)
        }
    }

    private fun assertSettled() {
        val snapshot = snapshot()
        assertTrue(snapshot.liveRecords <= ACTUAL_STRESS_CAPACITIES.liveRecords)
        assertTrue(
            snapshot.activeResolutions <=
                ACTUAL_STRESS_CAPACITIES.activeResolutions,
        )
        assertTrue(
            snapshot.waitingResolutions <=
                ACTUAL_STRESS_CAPACITIES.waitingResolutions,
        )
        assertTrue(
            snapshot.materializedWrites <=
                ACTUAL_STRESS_CAPACITIES.materializedWrites,
        )
        assertEquals(snapshot.liveRecords, snapshot.states.size)
        assertTrue(
            snapshot.liveRecords + (if (snapshot.readerPermitHeld) 1 else 0) <=
                ACTUAL_STRESS_CAPACITIES.liveRecords,
        )
        assertTrue(snapshot.replacementPromotions <= snapshot.liveRecords)
        assertTrue(fixture.tun.maximumConcurrentReads.get() <= 1)
        assertTrue(fixture.tun.maximumConcurrentWrites.get() <= 1)
        assertTrue(
            fixture.maximumActiveExecutions <=
                ACTUAL_STRESS_CAPACITIES.activeResolutions,
        )
        assertTrue(fixture.observer.health.isEmpty())
        assertTrue(fixture.observer.failures.isEmpty())

        while (attemptedWriteCursor < fixture.tun.attemptedWrites.size) {
            val packet = fixture.tun.attemptedWrites[attemptedWriteCursor++]
            val decoded = decodeActualStressPacket(packet)
            assertArrayEquals(
                expectedPackets.getValue(decoded.ticketId),
                packet,
            )
        }
        while (recordedWriteCursor < fixture.tun.recordedWrites.size) {
            val packet = fixture.tun.recordedWrites[recordedWriteCursor++]
            val decoded = decodeActualStressPacket(packet)
            assertArrayEquals(
                expectedPackets.getValue(decoded.ticketId),
                packet,
            )
            assertTrue(
                "ticket ${decoded.ticketId} emitted more than once",
                emittedTicketIds.add(decoded.ticketId),
            )
            if (decoded.kind == ActualWireKind.Original) {
                originalTicketIds += decoded.ticketId
            }
        }
        while (evidenceCursor < fixture.observer.evidence.size) {
            val evidence = fixture.observer.evidence[evidenceCursor++]
            val ticketId = checkNotNull(evidence.activity).id.toInt()
            val publication = fixture.publications.getValue(ticketId)
            assertSame(publication.activity, evidence.activity)
            assertEquals(publication.healthEvents, evidence.healthEvents)
            assertSame(
                publication.resolverQueryEvidence,
                evidence.resolverQueryEvidence,
            )
            assertTrue(
                "ticket $ticketId accepted evidence more than once",
                evidenceTicketIds.add(ticketId),
            )
        }
        assertEquals(originalTicketIds, evidenceTicketIds)
        assertEquals(emittedTicketIds.size, fixture.tun.recordedWrites.size)

        if (closed) {
            assertEquals(0, fixture.runtime.jobTopologyForTest().childCount)
        } else {
            assertEquals(
                setOf("tun-worker-0", "tun-reader", "tun-writer"),
                fixture.runtime.jobTopologyForTest().childIdentities.toSet(),
            )
        }
    }
}

private class ActualRuntimeFixture(
    scope: TestScope,
    private val sessionToken: Long,
) {
    val tun = ScriptedTunPacketSession()
    val observer = RecordingTunRuntimeObserver()
    val publications = mutableMapOf<Int, ProxyPublication>()
    val completedResolutionIds = mutableSetOf<Int>()
    val activeExecutionIds = mutableSetOf<Int>()
    val engines = mutableListOf<TestReadyDnsEngine>()
    val plannedTicketIds = mutableListOf<Int>()
    var maximumActiveExecutions: Int = 0
        private set
    private val modes = mutableMapOf<Int, ActualPlanMode>()
    private val executionPlans = mutableMapOf<Int, ProxyPlan.Forward>()
    private val resolutionResults =
        mutableMapOf<Int, CompletableDeferred<ProxyResult>>()
    private val networkContext = testNetworkContext()
    private val resolverPlan = testResolverPlan(networkContext)
    private val policyIdentity = testPolicyIdentity()
    private var engineGeneration = ACTUAL_STRESS_INITIAL_GENERATION

    val proxy = TestRuntimeDnsProxy(
        planner = { packet, engine ->
            val ticketId = packet.readUInt16(28)
            plannedTicketIds += ticketId
            when (modes.getValue(ticketId)) {
                ActualPlanMode.Drop -> ProxyPlan.Drop
                ActualPlanMode.Immediate -> ProxyPlan.Immediate(
                    result = pending(ticketId, engine),
                    replacement = ProxyReplacementResponse(
                        actualStressPacket(ActualWireKind.Replacement, ticketId),
                    ),
                )
                ActualPlanMode.ForwardPublish,
                ActualPlanMode.ForwardReplace,
                -> forwardPlan(packet, ticketId, engine)
            }
        },
        unavailablePlanner = { _, _ -> ProxyPlan.Drop },
        executor = { plan ->
            val ticketId = plan.request.queryId.toInt()
            check(executionPlans.put(ticketId, plan) == null)
            check(activeExecutionIds.add(ticketId))
            maximumActiveExecutions =
                maxOf(maximumActiveExecutions, activeExecutionIds.size)
            try {
                resolutionResults.getOrPut(ticketId) { CompletableDeferred() }.await()
            } finally {
                check(activeExecutionIds.remove(ticketId))
            }
        },
        overloadRejector = { plan ->
            pending(plan.request.queryId.toInt(), plan.engine)
        },
    )
    val runtime = BoundedTunRuntime(
        sessionToken = sessionToken,
        packetSession = tun,
        observer = observer,
        dispatcher = StandardTestDispatcher(scope.testScheduler),
        capacities = ACTUAL_STRESS_CAPACITIES,
    )

    fun start() {
        val initial = engine(ACTUAL_STRESS_INITIAL_GENERATION)
        engines += initial
        runtime.start(initial)
    }

    fun setMode(ticketId: Int, mode: ActualPlanMode) {
        check(modes.put(ticketId, mode) == null)
    }

    fun mode(ticketId: Int): ActualPlanMode = modes.getValue(ticketId)

    fun incompleteResolutionIds(): Set<Int> =
        executionPlans.keys - completedResolutionIds

    fun completeResolution(ticketId: Int) {
        val plan = executionPlans.getValue(ticketId)
        val result = when (modes.getValue(ticketId)) {
            ActualPlanMode.ForwardPublish -> pending(ticketId, plan.engine)
            ActualPlanMode.ForwardReplace -> ProxyResult.NotPublishable
            else -> error("ticket $ticketId does not own a resolution")
        }
        check(
            resolutionResults.getValue(ticketId).complete(result),
        )
        completedResolutionIds += ticketId
    }

    suspend fun replaceEngine(): Boolean {
        val next = engine(++engineGeneration)
        engines += next
        return runtime.replaceEngine(next)
    }

    fun scriptWritten() {
        check(tun.writeResults.trySend(TunWriteAttempt.Written).isSuccess)
    }

    fun signalWritable() {
        check(tun.writableResults.trySend(TunWritable.Writable).isSuccess)
    }

    private fun engine(generation: Long) = TestReadyDnsEngine(
        epoch = testEpoch(
            policyIdentity = policyIdentity,
            resolverPlan = resolverPlan,
            sessionGeneration = sessionToken,
            engineGeneration = generation,
        ),
        networkContext = networkContext,
        resolverPlan = resolverPlan,
        policyIdentity = policyIdentity,
        proxy = proxy,
    )

    private fun forwardPlan(
        packet: ByteArray,
        ticketId: Int,
        engine: ReadyDnsEngine,
    ): ProxyPlan.Forward {
        val datagram = checkNotNull(Ipv4UdpDnsPacket.parse(packet))
        val question = DnsMessage.parseQuestion(datagram.payload)
        return ProxyPlan.Forward.create(
            request = ResolverRequest(
                query = datagram.payload,
                question = question,
                epoch = engine.epoch,
                networkContext = engine.networkContext,
                queryId = ticketId.toLong(),
            ),
            activity = activity(ticketId),
            policyIdentity = engine.policyIdentity,
            plannedProtectionSessionId = TEST_PROTECTION_SESSION_ID,
            plannedPauseMaximumResponseTtlSeconds = null,
            startedPaused = false,
            replacement = ProxyReplacementResponse(
                actualStressPacket(ActualWireKind.Replacement, ticketId),
            ),
            engine = engine,
            datagram = datagram,
            owner = proxy,
        )
    }

    private fun pending(
        ticketId: Int,
        engine: ReadyDnsEngine,
    ): ProxyResult.Pending {
        val publication = ProxyPublication(
            responsePacket = actualStressPacket(ActualWireKind.Original, ticketId),
            activity = activity(ticketId),
            healthEvents = listOf(DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE)),
            resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(
                queryId = ticketId.toLong(),
                latencyMillis = ticketId.toLong(),
            ),
        )
        check(publications.put(ticketId, publication) == null)
        return ProxyResult.Pending(
            fence = engine,
            publication = publication,
            sink = runtime.publicationSink,
        )
    }

    private fun activity(ticketId: Int) = DnsDecisionEvent(
        id = ticketId.toLong(),
        timestampMillis = TEST_NOW_MILLIS + ticketId,
        normalizedDomain = "actual-$ticketId.example",
        decision = FilterDecision.DEFAULT_ALLOW,
    )
}

private data class ActualStressPacket(
    val kind: ActualWireKind,
    val ticketId: Int,
)

private fun actualStressPacket(
    kind: ActualWireKind,
    ticketId: Int,
): ByteArray {
    require(ticketId > 0)
    return byteArrayOf(
        kind.magic.toByte(),
        (ticketId ushr 24).toByte(),
        (ticketId ushr 16).toByte(),
        (ticketId ushr 8).toByte(),
        ticketId.toByte(),
    )
}

private fun decodeActualStressPacket(packet: ByteArray): ActualStressPacket {
    require(packet.size == 5)
    val magic = packet[0].toInt() and 0xff
    val kind = ActualWireKind.entries.single { it.magic == magic }
    val ticketId =
        ((packet[1].toInt() and 0xff) shl 24) or
            ((packet[2].toInt() and 0xff) shl 16) or
            ((packet[3].toInt() and 0xff) shl 8) or
            (packet[4].toInt() and 0xff)
    return ActualStressPacket(kind, ticketId)
}
