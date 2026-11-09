package com.lavasec.app.vpn

import java.util.Collections
import java.util.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

internal enum class RuntimeTransition {
    READ,
    FINISH_PLAN,
    CLAIM_RESOLUTION,
    FINISH_RESOLUTION,
    STAGE,
    WRITE,
    WRITABLE,
    REPLACE,
    CLOSE,
}

private const val SESSION_TOKEN = 23L
private const val INITIAL_ENGINE_GENERATION = 29L
private const val SEEDED_TRANSITIONS = 10_000

private val REDUCED_CAPACITIES = TunRuntimeCapacities(
    activeResolutions = 1,
    waitingResolutions = 2,
    materializedWrites = 2,
    liveRecords = 6,
)

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoundedTunRuntimeStressTest {
    @Test
    fun `reduced capacities exhaustively match an immutable reference model`() = runTest {
        val initial = ReferenceRuntime.initial(
            capacities = REDUCED_CAPACITIES,
            program = QueryProgram.fixed(
                QuerySpec(1, TunStressPlanKind.FORWARD, resolutionPublishes = true),
                QuerySpec(2, TunStressPlanKind.IMMEDIATE, resolutionPublishes = false),
            ),
            replacementBudget = 1,
        )
        val exhaustive = enumerateExhaustivePaths(initial)

        exhaustive.paths.forEachIndexed { pathIndex, path ->
            val scenario = RuntimeScenario(
                initialModel = initial,
                seedLabel = "exhaustive-$pathIndex",
            )
            path.forEachIndexed { transitionIndex, step ->
                scenario.apply(step, transitionIndex)
            }
            scenario.assertFullyReleased()
        }

        assertEquals(RuntimeTransition.entries.toSet(), exhaustive.transitions)
        assertEquals(172, exhaustive.stateCount)
        assertEquals(3_485, exhaustive.paths.size)
        println(
            "bounded TUN exhaustive states=${exhaustive.stateCount} " +
                "paths=${exhaustive.paths.size}",
        )
    }

    @Test
    fun `reduced full backpressure prefix exhausts every replacement suffix`() = runTest {
        val queries = buildList {
            repeat(4) { index ->
                add(
                    QuerySpec(
                        ordinal = index + 1,
                        planKind = TunStressPlanKind.FORWARD,
                        resolutionPublishes = true,
                    ),
                )
            }
            repeat(2) { index ->
                add(
                    QuerySpec(
                        ordinal = index + 5,
                        planKind = TunStressPlanKind.IMMEDIATE,
                        resolutionPublishes = false,
                    ),
                )
            }
        }
        val initial = ReferenceRuntime.initial(
            capacities = REDUCED_CAPACITIES,
            program = QueryProgram.fixed(queries),
            replacementBudget = 1,
        )
        val boundaryPrefix = buildList {
            queries.forEach { query ->
                add(RuntimeStep(RuntimeTransition.READ, query.ordinal.toLong()))
                add(RuntimeStep(RuntimeTransition.FINISH_PLAN, query.ordinal.toLong()))
            }
            add(RuntimeStep(RuntimeTransition.REPLACE))
        }
        val full = boundaryPrefix.dropLast(1)
            .fold(initial, ReferenceRuntime::advance)
            .snapshot()
        assertEquals(6, full.liveRecords)
        assertEquals(1, full.activeResolutions)
        assertEquals(2, full.waitingResolutions)
        assertEquals(2, full.materializedWrites)
        assertEquals(
            1,
            full.states.values.count { it == "AwaitingOriginalWriteCapacity" },
        )

        val boundary = boundaryPrefix.fold(initial, ReferenceRuntime::advance)
        val snapshot = boundary.snapshot()
        assertEquals(6, snapshot.liveRecords)
        assertEquals(0, snapshot.activeResolutions)
        assertEquals(0, snapshot.waitingResolutions)
        assertEquals(2, snapshot.materializedWrites)
        assertEquals(6, snapshot.replacementPromotions)
        assertEquals(
            2,
            snapshot.states.values.count { it == "ReplacementReady" },
        )
        assertEquals(
            4,
            snapshot.states.values.count {
                it == "AwaitingReplacementWriteCapacity"
            },
        )

        val suffixes = enumerateExhaustivePaths(boundary)
        assertEquals(32, suffixes.stateCount)
        assertEquals(25, suffixes.paths.size)
        suffixes.paths.forEachIndexed { pathIndex, suffix ->
            val scenario = RuntimeScenario(
                initialModel = initial,
                seedLabel = "reduced-boundary-$pathIndex",
            )
            (boundaryPrefix + suffix).forEachIndexed { transitionIndex, step ->
                scenario.apply(step, transitionIndex)
            }
            scenario.assertFullyReleased()
        }
    }

    @Test
    fun `seed 0x5A17C0DE ten thousand transitions preserve bounds and output`() = runTest {
        runSeed(seed = 0x5A17C0DEL)
    }

    @Test
    fun `seed 0x12D024 ten thousand transitions preserve bounds and output`() = runTest {
        runSeed(seed = 0x12D024L)
    }

    @Test
    fun `seed 0x086464137 ten thousand transitions preserve bounds and output`() = runTest {
        runSeed(seed = 0x086464137L)
    }

    @Test
    fun `production capacities reach every literal boundary and fully release`() = runTest {
        val queries = buildList {
            repeat(73) { index ->
                add(
                    QuerySpec(
                        ordinal = index + 1,
                        planKind = TunStressPlanKind.FORWARD,
                        resolutionPublishes = true,
                    ),
                )
            }
            repeat(64) { index ->
                add(
                    QuerySpec(
                        ordinal = index + 74,
                        planKind = TunStressPlanKind.IMMEDIATE,
                        resolutionPublishes = false,
                    ),
                )
            }
        }
        val scenario = RuntimeScenario(
            initialModel = ReferenceRuntime.initial(
                capacities = TunRuntimeCapacities.Production,
                program = QueryProgram.fixed(queries),
                replacementBudget = 0,
            ),
            seedLabel = "production-capacity-boundary",
        )
        var transitionIndex = 0
        repeat(queries.size) {
            val read = scenario.enabledSteps(includeClose = false)
                .single { it.transition === RuntimeTransition.READ }
            scenario.apply(read, transitionIndex++)
            val finish = scenario.enabledSteps(includeClose = false)
                .single { it.transition === RuntimeTransition.FINISH_PLAN }
            scenario.apply(finish, transitionIndex++)
        }

        val snapshot = scenario.modelSnapshot()
        assertEquals(137, snapshot.liveRecords)
        assertEquals(8, snapshot.activeResolutions)
        assertEquals(64, snapshot.waitingResolutions)
        assertEquals(64, snapshot.materializedWrites)
        assertEquals(8, snapshot.states.values.count { it == "ResolutionReady" })
        assertEquals(64, snapshot.states.values.count { it == "WaitingResolution" })
        assertEquals(64, snapshot.states.values.count { it == "ReadyPending" })
        assertEquals(
            1,
            snapshot.states.values.count { it == "AwaitingOriginalWriteCapacity" },
        )

        scenario.closeAndAssertFullyReleased(transitionIndex)
    }

    @Test
    fun `runtime close owns the session and joins the fixed reduced topology`() = runTest {
        val fixture = ReplacementFixture(
            scope = this,
            capacities = REDUCED_CAPACITIES,
        )
        fixture.start()
        fixture.mode(2, PlanMode.Immediate)
        fixture.mode(3, PlanMode.Immediate)
        fixture.enqueue(1)
        fixture.enqueue(2)
        fixture.enqueue(3)
        runCurrent()

        val topology = fixture.runtime.jobTopologyForTest()
        assertEquals(3, topology.childCount)
        assertEquals(
            setOf("tun-worker-0", "tun-reader", "tun-writer"),
            topology.childIdentities.toSet(),
        )
        val blocked = fixture.runtime.snapshotForTest()
        assertEquals("Resolving", blocked.states[TunQueryId(1)])
        assertEquals("StagedOriginal", blocked.states[TunQueryId(2)])
        assertEquals("ReadyPending", blocked.states[TunQueryId(3)])
        assertEquals(1, fixture.tun.writableCalls.get())
        assertEquals(1, fixture.tun.attemptedWrites.size)
        assertEquals(0, fixture.tun.recordedWrites.size)
        assertEquals(1, fixture.tun.maximumConcurrentWrites.get())

        fixture.runtime.closeAndJoin()

        assertEquals(1, fixture.tun.closeCalls.get())
        assertEquals(1, fixture.initialEngine.closeCalls)
        assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
        assertEquals(0, fixture.runtime.jobTopologyForTest().childCount)
    }

    private suspend fun runSeed(seed: Long) {
        val scenario = SeededRuntimeScenario(seed)
        repeat(SEEDED_TRANSITIONS) {
            scenario.applyNextEnabledTransition()
        }
        scenario.closeAndAssertFullyReleased()
    }
}

private class SeededRuntimeScenario(
    private val seed: Long,
) {
    private val random = Random(seed)
    private val scenario = RuntimeScenario(
        initialModel = ReferenceRuntime.initial(
            capacities = REDUCED_CAPACITIES,
            program = QueryProgram.patterned(),
            replacementBudget = null,
        ),
        seedLabel = "0x${seed.toString(16).uppercase()}",
    )
    private var transitionIndex = 0

    suspend fun applyNextEnabledTransition() {
        val enabled = scenario.enabledSteps(includeClose = false)
        check(enabled.isNotEmpty()) {
            "seed=0x${seed.toString(16)} transitionIndex=$transitionIndex has no transition"
        }
        scenario.apply(
            enabled[random.nextInt(enabled.size)],
            transitionIndex++,
        )
    }

    suspend fun closeAndAssertFullyReleased() {
        scenario.closeAndAssertFullyReleased(transitionIndex)
    }
}

private class RuntimeScenario(
    initialModel: ReferenceRuntime,
    private val seedLabel: String,
) {
    private val artifacts = TunStressArtifacts(SESSION_TOKEN)
    private val engines = mutableListOf(
        artifacts.engine(INITIAL_ENGINE_GENERATION),
    )
    private val scheduler = TunQueryScheduler(
        sessionToken = SESSION_TOKEN,
        initialEngine = engines.single(),
        capacities = initialModel.capacities,
    )
    private val plans = linkedMapOf<Long, ProxyPlan>()
    private val stagedPublications = mutableMapOf<Long, ProxyPublication>()
    private val firstWriteAttempts = mutableMapOf<Long, WriteAttemptObservation>()
    private val observedTerminalIds = mutableSetOf<Int>()
    private var activeWriter: Long? = null
    private var awaitingWritable = false
    private var model = initialModel

    init {
        assertImplementationMatchesModel(transitionIndex = -1, step = null)
    }

    fun enabledSteps(includeClose: Boolean): List<RuntimeStep> =
        model.enabledSteps(includeClose)

    fun modelSnapshot(): ReferenceSnapshot = model.snapshot()

    suspend fun apply(step: RuntimeStep, transitionIndex: Int) {
        val before = model
        val expected = before.advance(step)
        try {
            applyImplementation(step, before)
            model = expected
            assertImplementationMatchesModel(transitionIndex, step)
        } catch (failure: Throwable) {
            throw AssertionError(
                diagnostic(
                    transitionIndex = transitionIndex,
                    step = step,
                    implementation = implementationSnapshot(),
                    reference = expected.snapshot(),
                ),
                failure,
            )
        }
    }

    suspend fun closeAndAssertFullyReleased(transitionIndex: Int) {
        apply(RuntimeStep(RuntimeTransition.CLOSE), transitionIndex)
        assertFullyReleased()
    }

    fun assertFullyReleased() {
        val implementation = implementationSnapshot()
        assertEquals(0, implementation.liveRecords)
        assertEquals(0, implementation.activeResolutions)
        assertEquals(0, implementation.waitingResolutions)
        assertEquals(0, implementation.materializedWrites)
        assertEquals(0, implementation.replacementPromotions)
        assertTrue(implementation.states.isEmpty())
        assertFalse(implementation.readerPermitHeld)
        assertEquals(null, implementation.activeWriter)
        assertFalse(implementation.awaitingWritable)
        assertEquals(1, artifacts.packetSession.closeCalls.get())
        engines.forEach { engine ->
            assertEquals(
                "engine ${engine.epoch.engineGeneration} close ownership",
                1,
                engine.closeCalls,
            )
        }
    }

    fun assertImplementationMatchesModel(
        transitionIndex: Int,
        step: RuntimeStep?,
    ) {
        val implementation = implementationSnapshot()
        val reference = model.snapshot()
        assertEquals(
            diagnostic(transitionIndex, step, implementation, reference),
            reference,
            implementation,
        )
        assertCategoryBounds(reference, model.capacities)
        assertRecordConservation(model)
    }

    private suspend fun applyImplementation(
        step: RuntimeStep,
        before: ReferenceRuntime,
    ) {
        when (step.transition) {
            RuntimeTransition.READ -> {
                val spec = checkNotNull(before.nextQuery())
                val engine = checkNotNull(scheduler.currentEngineForPlanning())
                assertTrue(scheduler.reserveReaderPermit())
                val stressPlan = artifacts.plan(
                    transactionId = spec.transactionId,
                    kind = spec.planKind,
                    engine = engine,
                )
                val id = scheduler.beginPlanning(stressPlan.packet, engine)
                assertEquals(step.queryId, id.value)
                plans[id.value] = stressPlan.plan
            }
            RuntimeTransition.FINISH_PLAN -> {
                val id = TunQueryId(checkNotNull(step.queryId))
                when (val commit = scheduler.commitPlan(id, plans.getValue(id.value))) {
                    is TunPlanCommit.RejectOverloaded -> {
                        val result = commit.plan.engine.proxy.rejectOverloaded(commit.plan)
                        scheduler.commitOverload(id, result)
                    }
                    TunPlanCommit.Dropped,
                    TunPlanCommit.Queued,
                    is TunPlanCommit.AwaitWriterCapacity,
                    is TunPlanCommit.ReplacementOwned,
                    TunPlanCommit.Suppressed,
                    -> Unit
                }
            }
            RuntimeTransition.CLAIM_RESOLUTION -> {
                val claim = assertNotNullValue(
                    scheduler.claimResolution(workerIndex = before.resolvingCount()),
                )
                assertEquals(step.queryId, claim.id.value)
            }
            RuntimeTransition.FINISH_RESOLUTION -> {
                val id = checkNotNull(step.queryId)
                val record = before.records.getValue(id)
                val plan = plans.getValue(id) as ProxyPlan.Forward
                val result = artifacts.finishResolution(
                    plan = plan,
                    publish = record.spec.resolutionPublishes,
                )
                scheduler.completeResolution(TunQueryId(id), result)
            }
            RuntimeTransition.STAGE -> {
                val claim = assertNotNullValue(scheduler.nextWriterClaim())
                assertEquals(step.queryId, claim.id.value)
                when (claim) {
                    is TunWriterClaim.Publish -> {
                        val publication = assertNotNullValue(
                            artifacts.publish(claim.pending),
                        )
                        assertTrue(scheduler.stagePublication(claim.id, publication))
                        stagedPublications[claim.id.value] = publication
                    }
                    is TunWriterClaim.Write -> Unit
                }
                activeWriter = claim.id.value
            }
            RuntimeTransition.WRITE -> {
                val id = checkNotNull(step.queryId)
                assertEquals(id, activeWriter)
                val selected = assertNotNullValue(scheduler.selectWrite(TunQueryId(id)))
                val record = before.records.getValue(id)
                val expectedKind: ExpectedWriteKind
                val expectedPacket: ByteArray
                when (record.phase) {
                    ReferencePhase.STAGED_ORIGINAL -> {
                        expectedKind = ExpectedWriteKind.ORIGINAL
                        val publication = stagedPublications.getValue(id)
                        expectedPacket = publication.responsePacket
                        assertTrue(selected is TunSelectedWrite.Original)
                    }
                    ReferencePhase.READY_REPLACEMENT -> {
                        expectedKind = ExpectedWriteKind.REPLACEMENT
                        expectedPacket = when (val plan = plans.getValue(id)) {
                            is ProxyPlan.Immediate -> plan.replacement.snapshot()
                            is ProxyPlan.Forward -> plan.replacement.snapshot()
                            ProxyPlan.Drop -> error("Drop query $id cannot own a write")
                        }
                        assertTrue(selected is TunSelectedWrite.Replacement)
                    }
                    else -> error("active writer $id has phase ${record.phase}")
                }
                assertArrayEquals(expectedPacket, selected.packet)

                val shouldBlock = record.writeAttempts == 0
                val completedBefore = artifacts.packetSession.recordedWrites.size
                val evidenceBefore = artifacts.observer.evidence.size
                val scripted = if (shouldBlock) {
                    TunWriteAttempt.WouldBlock
                } else {
                    TunWriteAttempt.Written
                }
                check(artifacts.packetSession.writeResults.trySend(scripted).isSuccess)
                assertSame(scripted, artifacts.packetSession.tryWritePacket(selected.packet))
                assertArrayEquals(
                    selected.packet,
                    artifacts.packetSession.attemptedWrites.last(),
                )
                if (shouldBlock) {
                    assertEquals(
                        null,
                        firstWriteAttempts.put(
                            id,
                            WriteAttemptObservation(
                                kind = expectedKind,
                                packet = selected.packet.copyOf(),
                            ),
                        ),
                    )
                    assertEquals(completedBefore, artifacts.packetSession.recordedWrites.size)
                    assertEquals(evidenceBefore, artifacts.observer.evidence.size)
                    scheduler.retainWouldBlock(TunQueryId(id))
                    awaitingWritable = true
                } else {
                    val first = firstWriteAttempts.getValue(id)
                    if (first.kind === expectedKind) {
                        assertArrayEquals(first.packet, selected.packet)
                    }
                    assertEquals(
                        completedBefore + 1,
                        artifacts.packetSession.recordedWrites.size,
                    )
                    assertArrayEquals(
                        selected.packet,
                        artifacts.packetSession.recordedWrites.last(),
                    )
                    val transactionId =
                        artifacts.packetSession.recordedWrites.last().readUInt16(28)
                    assertEquals(record.spec.transactionId, transactionId)
                    assertTrue(
                        "query $id emitted more than one terminal response",
                        observedTerminalIds.add(transactionId),
                    )
                    when (val commit = scheduler.commitWritten(TunQueryId(id))) {
                        TunWriteCommit.ReplacementComplete -> {
                            assertEquals(evidenceBefore, artifacts.observer.evidence.size)
                        }
                        is TunWriteCommit.OriginalEvidence -> {
                            val publication = stagedPublications.getValue(id)
                            assertSame(publication.activity, commit.evidence.activity)
                            assertEquals(
                                publication.healthEvents,
                                commit.evidence.healthEvents,
                            )
                            assertSame(
                                publication.resolverQueryEvidence,
                                commit.evidence.resolverQueryEvidence,
                            )
                            val accepted =
                                artifacts.observer.acceptTerminalEvidence(commit.evidence)
                            scheduler.finishEvidence(TunQueryId(id), accepted)
                            assertEquals(
                                evidenceBefore + 1,
                                artifacts.observer.evidence.size,
                            )
                            assertSame(
                                commit.evidence,
                                artifacts.observer.evidence.last(),
                            )
                        }
                    }
                    activeWriter = null
                }
            }
            RuntimeTransition.WRITABLE -> {
                check(
                    artifacts.packetSession.writableResults
                        .trySend(TunWritable.Writable)
                        .isSuccess,
                )
                assertSame(TunWritable.Writable, artifacts.packetSession.awaitWritable())
                awaitingWritable = false
            }
            RuntimeTransition.REPLACE -> {
                val next = artifacts.engine(before.engineGeneration + 1)
                engines += next
                val install = scheduler.installReplacement(next)
                val installed = install as TunEngineInstall.Installed
                installed.priorEngine.close()
            }
            RuntimeTransition.CLOSE -> {
                scheduler.releaseReaderPermit()
                scheduler.suppressAll().forEach(ReadyDnsEngine::close)
                artifacts.packetSession.close()
                scheduler.releaseSuppressed()
                activeWriter = null
                awaitingWritable = false
            }
        }
    }

    private fun implementationSnapshot(): ReferenceSnapshot {
        val snapshot = scheduler.snapshot()
        check(artifacts.packetSession.recordedWrites.size == observedTerminalIds.size)
        return ReferenceSnapshot(
            readerPermitHeld = snapshot.readerPermitHeld,
            liveRecords = snapshot.liveRecords,
            activeResolutions = snapshot.activeResolutions,
            waitingResolutions = snapshot.waitingResolutions,
            materializedWrites = snapshot.materializedWrites,
            replacementPromotions = snapshot.replacementPromotions,
            states = snapshot.states
                .mapKeys { (id, _) -> id.value }
                .toSortedMap(),
            terminalResponseCount = observedTerminalIds.size,
            evidenceCount = artifacts.observer.evidence.size,
            activeWriter = activeWriter,
            awaitingWritable = awaitingWritable,
            closed = artifacts.packetSession.closeCalls.get() == 1,
        )
    }

    private fun diagnostic(
        transitionIndex: Int,
        step: RuntimeStep?,
        implementation: ReferenceSnapshot,
        reference: ReferenceSnapshot,
    ): String = buildString {
        append("seed=")
        append(seedLabel)
        append(" transitionIndex=")
        append(transitionIndex)
        append(" transition=")
        append(step?.transition ?: "<initial>")
        append(" queryId=")
        append(step?.queryId ?: "<global>")
        append(" implementationSnapshot=")
        append(implementation)
        append(" modelSnapshot=")
        append(reference)
    }
}

private data class RuntimeStep(
    val transition: RuntimeTransition,
    val queryId: Long? = null,
)

private enum class ExpectedWriteKind {
    ORIGINAL,
    REPLACEMENT,
}

private data class WriteAttemptObservation(
    val kind: ExpectedWriteKind,
    val packet: ByteArray,
)

private data class QuerySpec(
    val ordinal: Int,
    val planKind: TunStressPlanKind,
    val resolutionPublishes: Boolean,
) {
    val transactionId: Int
        get() = ordinal
}

private data class QueryProgram(
    private val fixedQueries: List<QuerySpec>?,
) {
    fun queryAt(index: Int): QuerySpec? = if (fixedQueries == null) {
        patternedQuery(index + 1)
    } else {
        fixedQueries.getOrNull(index)
    }

    companion object {
        fun fixed(vararg queries: QuerySpec) =
            QueryProgram(immutableList(queries.asList()))

        fun fixed(queries: List<QuerySpec>) =
            QueryProgram(immutableList(queries))

        fun patterned() = QueryProgram(null)

        private fun patternedQuery(ordinal: Int): QuerySpec {
            require(ordinal in 1..0xffff)
            return when (ordinal % 6) {
                1 -> QuerySpec(ordinal, TunStressPlanKind.FORWARD, true)
                2 -> QuerySpec(ordinal, TunStressPlanKind.IMMEDIATE, false)
                3 -> QuerySpec(ordinal, TunStressPlanKind.FORWARD, false)
                4 -> QuerySpec(ordinal, TunStressPlanKind.FORWARD, true)
                5 -> QuerySpec(ordinal, TunStressPlanKind.DROP, false)
                else -> QuerySpec(ordinal, TunStressPlanKind.IMMEDIATE, false)
            }
        }
    }
}

private enum class ReferencePhase(val snapshotName: String) {
    PLANNING("Planning"),
    SUPERSEDED_PLANNING("PlanningSuperseded"),
    READY_RESOLUTION("ResolutionReady"),
    WAITING_RESOLUTION("WaitingResolution"),
    RESOLVING("Resolving"),
    AWAIT_ORIGINAL_PLAN_CAPACITY("AwaitingOriginalWriteCapacity"),
    AWAIT_ORIGINAL_RESOLUTION_CAPACITY("AwaitingOriginalWriteCapacity"),
    READY_ORIGINAL("ReadyPending"),
    STAGED_ORIGINAL("StagedOriginal"),
    AWAIT_REPLACEMENT_CAPACITY("AwaitingReplacementWriteCapacity"),
    READY_REPLACEMENT("ReplacementReady"),
}

private data class ReferenceRecord(
    val id: Long,
    val spec: QuerySpec,
    val phase: ReferencePhase,
    val writeAttempts: Int = 0,
)

private data class ReferenceRuntime(
    val capacities: TunRuntimeCapacities,
    val program: QueryProgram,
    val replacementBudget: Int?,
    val nextQueryIndex: Int,
    val nextId: Long,
    val engineGeneration: Long,
    val records: Map<Long, ReferenceRecord>,
    val waitingQueue: List<Long>,
    val writerQueue: List<Long>,
    val replacementQueue: List<Long>,
    val activeWriter: Long?,
    val awaitingWritable: Boolean,
    val terminalResponseIds: Set<Int>,
    val evidenceCount: Int,
    val closed: Boolean,
) {
    fun nextQuery(): QuerySpec? = program.queryAt(nextQueryIndex)

    fun resolvingCount(): Int =
        records.values.count { it.phase === ReferencePhase.RESOLVING }

    fun enabledSteps(includeClose: Boolean): List<RuntimeStep> {
        if (closed) return emptyList()
        return buildList {
            val planning = records.values.singleOrNull {
                it.phase === ReferencePhase.PLANNING ||
                    it.phase === ReferencePhase.SUPERSEDED_PLANNING
            }
            if (
                planning == null &&
                nextQuery() != null &&
                records.size < capacities.liveRecords
            ) {
                add(RuntimeStep(RuntimeTransition.READ, nextId))
            }
            if (planning != null) {
                add(RuntimeStep(RuntimeTransition.FINISH_PLAN, planning.id))
            }
            records.values.firstOrNull {
                it.phase === ReferencePhase.READY_RESOLUTION
            }?.let { add(RuntimeStep(RuntimeTransition.CLAIM_RESOLUTION, it.id)) }
            records.values.filter {
                it.phase === ReferencePhase.RESOLVING
            }.forEach {
                add(RuntimeStep(RuntimeTransition.FINISH_RESOLUTION, it.id))
            }
            if (activeWriter == null) {
                nextWriterCandidate()?.let {
                    add(RuntimeStep(RuntimeTransition.STAGE, it.id))
                }
            } else if (awaitingWritable) {
                add(RuntimeStep(RuntimeTransition.WRITABLE, activeWriter))
            } else {
                add(RuntimeStep(RuntimeTransition.WRITE, activeWriter))
            }
            if (replacementBudget == null || replacementBudget > 0) {
                add(RuntimeStep(RuntimeTransition.REPLACE))
            }
            if (includeClose) add(RuntimeStep(RuntimeTransition.CLOSE))
        }
    }

    fun advance(step: RuntimeStep): ReferenceRuntime {
        check(step in enabledSteps(includeClose = true)) {
            "transition $step is not enabled for ${snapshot()}"
        }
        return when (step.transition) {
            RuntimeTransition.READ -> read()
            RuntimeTransition.FINISH_PLAN -> finishPlan(checkNotNull(step.queryId))
            RuntimeTransition.CLAIM_RESOLUTION ->
                updatePhase(checkNotNull(step.queryId), ReferencePhase.RESOLVING)
            RuntimeTransition.FINISH_RESOLUTION ->
                finishResolution(checkNotNull(step.queryId))
            RuntimeTransition.STAGE -> stage(checkNotNull(step.queryId))
            RuntimeTransition.WRITE -> write(checkNotNull(step.queryId))
            RuntimeTransition.WRITABLE -> copy(awaitingWritable = false)
            RuntimeTransition.REPLACE -> replace()
            RuntimeTransition.CLOSE -> close()
        }
    }

    fun snapshot(): ReferenceSnapshot = ReferenceSnapshot(
        readerPermitHeld = false,
        liveRecords = records.size,
        activeResolutions = activeResolutionCount(),
        waitingResolutions =
            records.values.count {
                it.phase === ReferencePhase.WAITING_RESOLUTION
            },
        materializedWrites = materializedWriteCount(),
        replacementPromotions = replacementQueue.size,
        states = records
            .mapValues { (_, record) -> record.phase.snapshotName }
            .toSortedMap(),
        terminalResponseCount = terminalResponseIds.size,
        evidenceCount = evidenceCount,
        activeWriter = activeWriter,
        awaitingWritable = awaitingWritable,
        closed = closed,
    )

    private fun read(): ReferenceRuntime {
        val spec = checkNotNull(nextQuery())
        val record = ReferenceRecord(
            id = nextId,
            spec = spec,
            phase = ReferencePhase.PLANNING,
        )
        return copy(
            nextQueryIndex = nextQueryIndex + 1,
            nextId = nextId + 1,
            records = records.with(record),
        )
    }

    private fun finishPlan(id: Long): ReferenceRuntime {
        val record = records.getValue(id)
        val superseded =
            record.phase === ReferencePhase.SUPERSEDED_PLANNING
        return when (record.spec.planKind) {
            TunStressPlanKind.DROP -> removeRecord(id)
            TunStressPlanKind.IMMEDIATE ->
                if (superseded) {
                    scheduleReplacement(id)
                } else {
                    materializeOriginal(id, retainsResolutionSlot = false)
                }
            TunStressPlanKind.FORWARD ->
                if (superseded) {
                    scheduleReplacement(id)
                } else {
                    when {
                        activeResolutionCount() <
                            capacities.activeResolutions ->
                            updatePhase(id, ReferencePhase.READY_RESOLUTION)
                        waitingQueue.size < capacities.waitingResolutions ->
                            updatePhase(id, ReferencePhase.WAITING_RESOLUTION)
                                .copy(waitingQueue = waitingQueue.enqueue(id))
                        else ->
                            materializeOriginal(
                                id,
                                retainsResolutionSlot = false,
                            )
                    }
                }
        }
    }

    private fun finishResolution(id: Long): ReferenceRuntime {
        val record = records.getValue(id)
        return if (record.spec.resolutionPublishes) {
            materializeOriginal(id, retainsResolutionSlot = true)
        } else {
            removeRecord(id).promoteWaiting()
        }
    }

    private fun materializeOriginal(
        id: Long,
        retainsResolutionSlot: Boolean,
    ): ReferenceRuntime {
        if (materializedWriteCount() >= capacities.materializedWrites) {
            val phase = if (retainsResolutionSlot) {
                ReferencePhase.AWAIT_ORIGINAL_RESOLUTION_CAPACITY
            } else {
                ReferencePhase.AWAIT_ORIGINAL_PLAN_CAPACITY
            }
            return updatePhase(id, phase)
        }
        var next = updatePhase(id, ReferencePhase.READY_ORIGINAL)
        next = next.copy(writerQueue = next.writerQueue.enqueue(id))
        return if (retainsResolutionSlot) next.promoteWaiting() else next
    }

    private fun stage(id: Long): ReferenceRuntime {
        val candidate = checkNotNull(nextWriterCandidate())
        check(candidate.id == id)
        return when (candidate.kind) {
            WriterCandidateKind.REPLACEMENT -> {
                updatePhase(id, ReferencePhase.READY_REPLACEMENT).copy(
                    replacementQueue = replacementQueue.without(id),
                    activeWriter = id,
                    awaitingWritable = false,
                )
            }
            WriterCandidateKind.ORIGINAL_QUEUE -> {
                val record = records.getValue(id)
                val phase = if (
                    record.phase === ReferencePhase.READY_ORIGINAL
                ) {
                    ReferencePhase.STAGED_ORIGINAL
                } else {
                    record.phase
                }
                updatePhase(id, phase).copy(
                    writerQueue = writerQueue.without(id),
                    activeWriter = id,
                    awaitingWritable = false,
                )
            }
            WriterCandidateKind.AWAITING_ORIGINAL -> {
                val retained =
                    records.getValue(id).phase ===
                        ReferencePhase.AWAIT_ORIGINAL_RESOLUTION_CAPACITY
                val next = updatePhase(id, ReferencePhase.STAGED_ORIGINAL).copy(
                    activeWriter = id,
                    awaitingWritable = false,
                )
                if (retained) next.promoteWaiting() else next
            }
        }
    }

    private fun write(id: Long): ReferenceRuntime {
        val record = records.getValue(id)
        var next = copy(
            writerQueue = writerQueue.without(id),
            replacementQueue = replacementQueue.without(id),
        )
        if (record.writeAttempts == 0) {
            next = next.updateRecord(record.copy(writeAttempts = 1))
            next = when (record.phase) {
                ReferencePhase.STAGED_ORIGINAL ->
                    next.copy(writerQueue = next.writerQueue.enqueue(id))
                ReferencePhase.READY_REPLACEMENT ->
                    next.copy(
                        replacementQueue = next.replacementQueue.enqueue(id),
                    )
                else -> error("active writer $id has phase ${record.phase}")
            }
            return next.copy(awaitingWritable = true)
        }

        val transactionId = record.spec.transactionId
        check(transactionId !in terminalResponseIds) {
            "query $id emitted more than one terminal response"
        }
        val addsEvidence =
            record.phase === ReferencePhase.STAGED_ORIGINAL
        return next.removeRecord(id).copy(
            activeWriter = null,
            awaitingWritable = false,
            terminalResponseIds =
                immutableSet(terminalResponseIds + transactionId),
            evidenceCount = evidenceCount + if (addsEvidence) 1 else 0,
        )
    }

    private fun replace(): ReferenceRuntime {
        var next = copy(
            replacementBudget = replacementBudget?.minus(1),
            engineGeneration = engineGeneration + 1,
        )
        records.keys.forEach { id ->
            val phase = next.records.getValue(id).phase
            next = when (phase) {
                ReferencePhase.PLANNING ->
                    next.updatePhase(id, ReferencePhase.SUPERSEDED_PLANNING)
                ReferencePhase.SUPERSEDED_PLANNING -> next
                else -> next.scheduleReplacement(id)
            }
        }
        return next.promoteWaiting()
    }

    private fun scheduleReplacement(id: Long): ReferenceRuntime {
        val record = records.getValue(id)
        val ownsMaterialized = record.phase.isMaterialized()
        var next = copy(
            waitingQueue = waitingQueue.without(id),
            writerQueue = writerQueue.without(id),
        )
        val phase = if (
            ownsMaterialized ||
            next.materializedWriteCount() < capacities.materializedWrites
        ) {
            ReferencePhase.READY_REPLACEMENT
        } else {
            ReferencePhase.AWAIT_REPLACEMENT_CAPACITY
        }
        next = next.updatePhase(id, phase)
        next = next.copy(
            replacementQueue = next.replacementQueue.enqueue(id),
        )
        return next.promoteWaiting()
    }

    private fun promoteWaiting(): ReferenceRuntime {
        var next = this
        while (
            next.activeResolutionCount() <
            capacities.activeResolutions &&
            next.waitingQueue.isNotEmpty()
        ) {
            val id = next.waitingQueue.first()
            next = next.updatePhase(id, ReferencePhase.READY_RESOLUTION)
                .copy(waitingQueue = next.waitingQueue.dropFirst())
        }
        return next
    }

    private fun close(): ReferenceRuntime = copy(
        records = emptyMap(),
        waitingQueue = emptyList(),
        writerQueue = emptyList(),
        replacementQueue = emptyList(),
        activeWriter = null,
        awaitingWritable = false,
        closed = true,
    )

    private fun nextWriterCandidate(): WriterCandidate? {
        if (replacementQueue.isNotEmpty()) {
            val capacityAvailable =
                materializedWriteCount() < capacities.materializedWrites
            val index = if (capacityAvailable) {
                0
            } else {
                replacementQueue.indexOfFirst { id ->
                    records[id]?.phase === ReferencePhase.READY_REPLACEMENT
                }
            }
            if (index >= 0) {
                return WriterCandidate(
                    replacementQueue[index],
                    WriterCandidateKind.REPLACEMENT,
                )
            }
        }
        writerQueue.firstOrNull()?.let {
            return WriterCandidate(it, WriterCandidateKind.ORIGINAL_QUEUE)
        }
        if (materializedWriteCount() < capacities.materializedWrites) {
            records.values.firstOrNull {
                it.phase === ReferencePhase.AWAIT_ORIGINAL_PLAN_CAPACITY ||
                    it.phase ===
                    ReferencePhase.AWAIT_ORIGINAL_RESOLUTION_CAPACITY
            }?.let {
                return WriterCandidate(
                    it.id,
                    WriterCandidateKind.AWAITING_ORIGINAL,
                )
            }
        }
        return null
    }

    private fun activeResolutionCount(): Int = records.values.count {
        it.phase === ReferencePhase.READY_RESOLUTION ||
            it.phase === ReferencePhase.RESOLVING ||
            it.phase === ReferencePhase.AWAIT_ORIGINAL_RESOLUTION_CAPACITY
    }

    private fun materializedWriteCount(): Int = records.values.count {
        it.phase.isMaterialized()
    }

    private fun updatePhase(
        id: Long,
        phase: ReferencePhase,
    ): ReferenceRuntime =
        updateRecord(records.getValue(id).copy(phase = phase))

    private fun updateRecord(record: ReferenceRecord): ReferenceRuntime =
        copy(records = records.with(record))

    private fun removeRecord(id: Long): ReferenceRuntime = copy(
        records = records.without(id),
        waitingQueue = waitingQueue.without(id),
        writerQueue = writerQueue.without(id),
        replacementQueue = replacementQueue.without(id),
    )

    companion object {
        fun initial(
            capacities: TunRuntimeCapacities,
            program: QueryProgram,
            replacementBudget: Int?,
        ) = ReferenceRuntime(
            capacities = capacities,
            program = program,
            replacementBudget = replacementBudget,
            nextQueryIndex = 0,
            nextId = 1L,
            engineGeneration = INITIAL_ENGINE_GENERATION,
            records = emptyMap(),
            waitingQueue = emptyList(),
            writerQueue = emptyList(),
            replacementQueue = emptyList(),
            activeWriter = null,
            awaitingWritable = false,
            terminalResponseIds = emptySet(),
            evidenceCount = 0,
            closed = false,
        )
    }
}

private data class ReferenceSnapshot(
    val readerPermitHeld: Boolean,
    val liveRecords: Int,
    val activeResolutions: Int,
    val waitingResolutions: Int,
    val materializedWrites: Int,
    val replacementPromotions: Int,
    val states: Map<Long, String>,
    val terminalResponseCount: Int,
    val evidenceCount: Int,
    val activeWriter: Long?,
    val awaitingWritable: Boolean,
    val closed: Boolean,
)

private enum class WriterCandidateKind {
    REPLACEMENT,
    ORIGINAL_QUEUE,
    AWAITING_ORIGINAL,
}

private data class WriterCandidate(
    val id: Long,
    val kind: WriterCandidateKind,
)

private data class ExhaustiveSpace(
    val paths: List<List<RuntimeStep>>,
    val stateCount: Int,
    val transitions: Set<RuntimeTransition>,
)

private fun enumerateExhaustivePaths(
    initial: ReferenceRuntime,
): ExhaustiveSpace {
    val paths = mutableListOf<List<RuntimeStep>>()
    val states = mutableSetOf<ReferenceRuntime>()
    val transitions = mutableSetOf<RuntimeTransition>()

    fun visit(model: ReferenceRuntime, path: List<RuntimeStep>) {
        states += model
        model.enabledSteps(includeClose = true).forEach { step ->
            transitions += step.transition
            val nextPath = path + step
            if (step.transition === RuntimeTransition.CLOSE) {
                paths += nextPath
                states += model.advance(step)
            } else {
                visit(model.advance(step), nextPath)
            }
        }
    }

    visit(initial, emptyList())
    return ExhaustiveSpace(
        paths = immutableList(paths),
        stateCount = states.size,
        transitions = Collections.unmodifiableSet(transitions.toSet()),
    )
}

private fun assertCategoryBounds(
    snapshot: ReferenceSnapshot,
    capacities: TunRuntimeCapacities,
) {
    assertEquals(snapshot.liveRecords, snapshot.states.size)
    assertTrue(snapshot.activeResolutions <= capacities.activeResolutions)
    assertTrue(snapshot.waitingResolutions <= capacities.waitingResolutions)
    assertTrue(snapshot.materializedWrites <= capacities.materializedWrites)
    assertTrue(
        snapshot.liveRecords +
            (if (snapshot.readerPermitHeld) 1 else 0) <= capacities.liveRecords,
    )
    assertTrue(snapshot.replacementPromotions <= snapshot.liveRecords)
}

private fun assertRecordConservation(model: ReferenceRuntime) {
    val records = model.records.values
    assertEquals(records.size, records.map(ReferenceRecord::id).toSet().size)
    assertEquals(
        records.size,
        ReferencePhase.entries.sumOf { phase ->
            records.count { it.phase === phase }
        },
    )
    listOf(
        model.waitingQueue,
        model.writerQueue,
        model.replacementQueue,
    ).forEach { queue ->
        assertEquals(queue.size, queue.toSet().size)
        assertTrue(queue.all(model.records::containsKey))
    }
    assertTrue(
        model.writerQueue.none(model.replacementQueue.toSet()::contains),
    )
    model.activeWriter?.let { assertTrue(model.records.containsKey(it)) }
}

private fun ReferencePhase.isMaterialized(): Boolean =
    this === ReferencePhase.READY_ORIGINAL ||
        this === ReferencePhase.STAGED_ORIGINAL ||
        this === ReferencePhase.READY_REPLACEMENT

private fun Map<Long, ReferenceRecord>.with(
    record: ReferenceRecord,
): Map<Long, ReferenceRecord> {
    val copy = LinkedHashMap(this)
    copy[record.id] = record
    return immutableMap(copy)
}

private fun Map<Long, ReferenceRecord>.without(
    id: Long,
): Map<Long, ReferenceRecord> {
    val copy = LinkedHashMap(this)
    copy.remove(id)
    return immutableMap(copy)
}

private fun List<Long>.enqueue(id: Long): List<Long> =
    if (id in this) this else immutableList(this + id)

private fun List<Long>.without(id: Long): List<Long> =
    immutableList(filterNot { it == id })

private fun List<Long>.dropFirst(): List<Long> =
    immutableList(drop(1))

private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))

private fun <T> immutableSet(source: Set<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(source))

private fun <T> immutableList(source: List<T>): List<T> =
    Collections.unmodifiableList(source.toList())

private fun <T> assertNotNullValue(value: T?): T {
    assertNotNull(value)
    return checkNotNull(value)
}
