package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TunQuerySchedulerTest {
    @Test
    fun `reader permit is reserved before a packet record exists`() {
        val fixture = SchedulerFixture()

        assertTrue(fixture.scheduler.reserveReaderPermit())
        assertFalse(fixture.scheduler.reserveReaderPermit())
        fixture.assertSnapshot(
            readerPermitHeld = true,
            liveRecords = 0,
            activeResolutions = 0,
            waitingResolutions = 0,
            materializedWrites = 0,
        )

        fixture.scheduler.releaseReaderPermit()
        fixture.assertEmpty()
    }

    @Test
    fun `drop releases its record after consuming the reader permit`() {
        val fixture = SchedulerFixture()
        val packet = testDnsPacket(testDnsQuery(transactionId = 1))
        assertTrue(fixture.scheduler.reserveReaderPermit())
        val id = fixture.scheduler.beginPlanning(packet, fixture.currentEngine())

        assertFalse(fixture.scheduler.snapshot().readerPermitHeld)
        assertEquals("Planning", fixture.scheduler.snapshot().states[id])
        assertSame(TunPlanCommit.Dropped, fixture.scheduler.commitPlan(id, ProxyPlan.Drop))

        fixture.assertEmpty()
    }

    @Test
    fun `one active and two waiting preserve forward FIFO and reject the fourth`() {
        val fixture = SchedulerFixture()
        val ids = (1..4).map { fixture.commitForward(transactionId = it) }

        fixture.assertSnapshot(
            readerPermitHeld = false,
            liveRecords = 4,
            activeResolutions = 1,
            waitingResolutions = 2,
            materializedWrites = 1,
        )
        assertEquals(ids.last(), fixture.overloadId)
        assertEquals(ids.take(3), fixture.drainResolutionOrder(3))
    }

    @Test
    fun `immediate plans bypass full resolver capacity`() {
        val fixture = SchedulerFixture()
        repeat(3) { fixture.commitForward(transactionId = it + 1) }

        val immediateId = fixture.commitImmediate(transactionId = 10)

        fixture.assertSnapshot(
            readerPermitHeld = false,
            liveRecords = 4,
            activeResolutions = 1,
            waitingResolutions = 2,
            materializedWrites = 1,
        )
        assertEquals("ReadyPending", fixture.scheduler.snapshot().states[immediateId])
    }

    @Test
    fun `completed worker retains its active slot until writer capacity is reserved`() {
        val fixture = SchedulerFixture()
        val firstReady = fixture.commitImmediate(transactionId = 1)
        fixture.commitImmediate(transactionId = 2)
        val activeId = fixture.commitForward(transactionId = 3)
        val waitingId = fixture.commitForward(transactionId = 4)
        val claim = fixture.scheduler.claimResolution(workerIndex = 0)
        assertEquals(activeId, claim?.id)

        val completion = fixture.scheduler.completeResolution(
            activeId,
            fixture.pending(transactionId = 5),
        )

        assertEquals(TunWorkerCommit.AwaitWriterCapacity(activeId), completion)
        fixture.assertSnapshot(
            readerPermitHeld = false,
            liveRecords = 4,
            activeResolutions = 1,
            waitingResolutions = 1,
            materializedWrites = 2,
        )
        assertEquals(
            "AwaitingOriginalWriteCapacity",
            fixture.scheduler.snapshot().states[activeId],
        )

        fixture.writeOriginal(firstReady)
        assertTrue(fixture.scheduler.materializeAwaitingWrite(activeId))
        fixture.assertSnapshot(
            readerPermitHeld = false,
            liveRecords = 3,
            activeResolutions = 1,
            waitingResolutions = 0,
            materializedWrites = 2,
        )
        assertEquals("ResolutionReady", fixture.scheduler.snapshot().states[waitingId])
    }

    @Test
    fun `materialized writes stop exactly at the configured limit`() {
        val fixture = SchedulerFixture()
        val ids = (1..3).map { fixture.commitImmediate(transactionId = it) }

        fixture.assertSnapshot(
            readerPermitHeld = false,
            liveRecords = 3,
            activeResolutions = 0,
            waitingResolutions = 0,
            materializedWrites = 2,
        )
        assertEquals(
            "AwaitingOriginalWriteCapacity",
            fixture.scheduler.snapshot().states[ids.last()],
        )
        assertFalse(fixture.scheduler.materializeAwaitingWrite(ids.last()))

        fixture.writeOriginal(ids.first())
        assertTrue(fixture.scheduler.materializeAwaitingWrite(ids.last()))
        assertEquals(2, fixture.scheduler.snapshot().materializedWrites)
    }

    @Test
    fun `replacement promotion contains one intent through repeated replacement and retry`() {
        val fixture = SchedulerFixture()
        val id = fixture.commitForward(transactionId = 1)

        assertSame(fixture.initialEngine, fixture.installEngine(generation = 30L))
        assertEquals(1, fixture.scheduler.snapshot().replacementPromotions)
        assertEquals("ReplacementReady", fixture.scheduler.snapshot().states[id])

        val firstSuccessor = fixture.currentEngine()
        assertSame(firstSuccessor, fixture.installEngine(generation = 31L))
        assertEquals(1, fixture.scheduler.snapshot().replacementPromotions)

        assertEquals(TunWriterClaim.Write(id), fixture.scheduler.nextWriterClaim())
        fixture.scheduler.retainWouldBlock(id)
        assertEquals(1, fixture.scheduler.snapshot().replacementPromotions)
        assertEquals(TunWriterClaim.Write(id), fixture.scheduler.nextWriterClaim())
        assertSame(
            TunWriteCommit.ReplacementComplete,
            fixture.scheduler.commitWritten(id),
        )
        assertNull(fixture.scheduler.nextWriterClaim())
        fixture.assertEmpty()
    }

    @Test
    fun `planning superseded then drop releases silently`() {
        val fixture = SchedulerFixture()
        val packet = testDnsPacket(testDnsQuery(transactionId = 1))
        assertTrue(fixture.scheduler.reserveReaderPermit())
        val id = fixture.scheduler.beginPlanning(packet, fixture.currentEngine())

        fixture.installEngine(generation = 30L)
        assertEquals("PlanningSuperseded", fixture.scheduler.snapshot().states[id])
        assertSame(TunPlanCommit.Dropped, fixture.scheduler.commitPlan(id, ProxyPlan.Drop))

        assertEquals(0, fixture.scheduler.snapshot().replacementPromotions)
        fixture.assertEmpty()
    }

    @Test
    fun `superseded immediate and forward plans each become one replacement`() {
        val fixture = SchedulerFixture()
        val immediate = fixture.beginImmediate(transactionId = 1)
        val forward = fixture.beginForward(transactionId = 2)
        val expected = mapOf(
            immediate.id to immediate.plan.replacement.snapshot(),
            forward.id to forward.plan.replacement.snapshot(),
        )

        fixture.installEngine(generation = 30L)
        assertEquals(
            TunPlanCommit.ReplacementOwned(immediate.id),
            fixture.scheduler.commitPlan(immediate.id, immediate.plan),
        )
        assertEquals(
            TunPlanCommit.ReplacementOwned(forward.id),
            fixture.scheduler.commitPlan(forward.id, forward.plan),
        )
        fixture.assertSnapshot(
            readerPermitHeld = false,
            liveRecords = 2,
            activeResolutions = 0,
            waitingResolutions = 0,
            materializedWrites = 2,
        )
        assertEquals(2, fixture.scheduler.snapshot().replacementPromotions)

        val promotionOrder = buildList {
            repeat(2) {
                val claim =
                    fixture.scheduler.nextWriterClaim() as TunWriterClaim.Write
                add(claim.id)
                val selected =
                    fixture.scheduler.selectWrite(claim.id) as
                        TunSelectedWrite.Replacement
                assertArrayEquals(expected.getValue(claim.id), selected.packet)
            }
        }
        assertEquals(listOf(immediate.id, forward.id), promotionOrder)
    }

    @Test
    fun `selected packets remain defensive across caller mutation`() {
        val fixture = SchedulerFixture()
        val begun = fixture.beginImmediate(transactionId = 1)
        val expected = begun.plan.replacement.snapshot()
        fixture.installEngine(generation = 30L)
        fixture.scheduler.commitPlan(begun.id, begun.plan)
        fixture.scheduler.nextWriterClaim()

        val exposed = fixture.scheduler.selectWrite(begun.id) as TunSelectedWrite.Replacement
        exposed.packet.fill(0)

        val selectedAgain =
            fixture.scheduler.selectWrite(begun.id) as TunSelectedWrite.Replacement
        assertArrayEquals(expected, selectedAgain.packet)
    }

    @Test
    fun `writer stages original bytes and commits terminal evidence once`() {
        val fixture = SchedulerFixture()
        val id = fixture.commitImmediate(transactionId = 1)
        val claim = fixture.scheduler.nextWriterClaim() as TunWriterClaim.Publish
        val packet = byteArrayOf(9, 8, 7)
        val publication = fixture.publication(packet)

        assertEquals(id, claim.id)
        assertTrue(fixture.scheduler.stagePublication(id, publication))
        val selected = fixture.scheduler.selectWrite(id) as TunSelectedWrite.Original
        assertArrayEquals(packet, selected.packet)
        selected.packet.fill(0)
        assertArrayEquals(
            packet,
            (fixture.scheduler.selectWrite(id) as TunSelectedWrite.Original).packet,
        )

        val commit = fixture.scheduler.commitWritten(id) as TunWriteCommit.OriginalEvidence
        assertSame(publication.activity, commit.evidence.activity)
        assertEquals(publication.healthEvents, commit.evidence.healthEvents)
        assertSame(
            publication.resolverQueryEvidence,
            commit.evidence.resolverQueryEvidence,
        )
        assertFalse(commit.evidence.primaryAnswerBearing)
        fixture.scheduler.finishEvidence(id, accepted = true)
        fixture.scheduler.finishEvidence(id, accepted = true)
        fixture.assertEmpty()
    }

    @Test
    fun `failed pending publication releases original but not replacement ownership`() {
        val original = SchedulerFixture()
        val originalId = original.commitImmediate(transactionId = 1)
        original.scheduler.nextWriterClaim()
        original.scheduler.failWriter(originalId)
        original.assertEmpty()

        val replaced = SchedulerFixture()
        val replacementId = replaced.commitImmediate(transactionId = 2)
        replaced.scheduler.nextWriterClaim()
        replaced.installEngine(generation = 30L)
        replaced.scheduler.failWriter(replacementId)

        assertEquals("ReplacementReady", replaced.scheduler.snapshot().states[replacementId])
        assertEquals(1, replaced.scheduler.snapshot().replacementPromotions)
    }

    @Test
    fun `stop suppression releases reader active waiting materialized and planning ownership once`() {
        val capacities = TunRuntimeCapacities(1, 2, 2, 8)
        val fixture = SchedulerFixture(capacities)
        fixture.commitForward(transactionId = 1)
        fixture.commitForward(transactionId = 2)
        fixture.commitImmediate(transactionId = 3)
        fixture.commitImmediate(transactionId = 4)
        fixture.commitImmediate(transactionId = 5)
        val planningPacket = testDnsPacket(testDnsQuery(transactionId = 6))
        assertTrue(fixture.scheduler.reserveReaderPermit())
        fixture.scheduler.beginPlanning(planningPacket, fixture.currentEngine())
        assertTrue(fixture.scheduler.reserveReaderPermit())
        fixture.assertBounds()

        assertEquals(listOf(fixture.initialEngine), fixture.scheduler.suppressAll())
        fixture.assertSnapshot(
            readerPermitHeld = true,
            liveRecords = 6,
            activeResolutions = 0,
            waitingResolutions = 0,
            materializedWrites = 0,
        )
        assertTrue(fixture.scheduler.snapshot().states.values.all { it == "Suppressed" })
        assertTrue(fixture.scheduler.suppressAll().isEmpty())

        fixture.scheduler.releaseSuppressed()
        fixture.scheduler.releaseSuppressed()
        fixture.scheduler.releaseReaderPermit()
        fixture.scheduler.releaseReaderPermit()
        fixture.assertEmpty()
    }

    @Test
    fun `blocked oldest replacement yields to capacity owners then resumes FIFO`() {
        val fixture = SchedulerFixture()
        val oldest = fixture.commitForward(transactionId = 1)
        val firstCapacityOwner = fixture.commitImmediate(transactionId = 2)
        val secondCapacityOwner = fixture.commitImmediate(transactionId = 3)
        assertEquals(oldest, fixture.scheduler.claimResolution(workerIndex = 0)?.id)
        assertEquals(
            TunWorkerCommit.AwaitWriterCapacity(oldest),
            fixture.scheduler.completeResolution(
                oldest,
                fixture.pending(transactionId = 4),
            ),
        )
        fixture.assertSnapshot(
            readerPermitHeld = false,
            liveRecords = 3,
            activeResolutions = 1,
            waitingResolutions = 0,
            materializedWrites = 2,
        )

        fixture.installEngine(generation = 30L)
        fixture.assertSnapshot(
            readerPermitHeld = false,
            liveRecords = 3,
            activeResolutions = 0,
            waitingResolutions = 0,
            materializedWrites = 2,
        )
        assertEquals(3, fixture.scheduler.snapshot().replacementPromotions)

        val claimOrder = buildList {
            repeat(3) { remainingAfterCommit ->
                val claim =
                    fixture.scheduler.nextWriterClaim() as TunWriterClaim.Write
                add(claim.id)
                assertTrue(
                    fixture.scheduler.selectWrite(claim.id) is
                        TunSelectedWrite.Replacement,
                )
                assertSame(
                    TunWriteCommit.ReplacementComplete,
                    fixture.scheduler.commitWritten(claim.id),
                )
                fixture.assertSnapshot(
                    readerPermitHeld = false,
                    liveRecords = 2 - remainingAfterCommit,
                    activeResolutions = 0,
                    waitingResolutions = 0,
                    materializedWrites =
                        if (remainingAfterCommit < 2) 1 else 0,
                )
                assertEquals(
                    2 - remainingAfterCommit,
                    fixture.scheduler.snapshot().replacementPromotions,
                )
            }
        }

        assertEquals(
            listOf(firstCapacityOwner, oldest, secondCapacityOwner),
            claimOrder,
        )
        fixture.assertEmpty()
    }

    @Test
    fun `terminal release subtracts exact live active and materialized ownership`() {
        val active = SchedulerFixture()
        val activeId = active.commitForward(transactionId = 1)
        assertEquals(activeId, active.scheduler.claimResolution(workerIndex = 0)?.id)
        val beforeActiveRelease = active.scheduler.snapshot()
        assertSame(
            TunWorkerCommit.Suppressed,
            active.scheduler.completeResolution(activeId, ProxyResult.NotPublishable),
        )
        assertCategoryDelta(
            before = beforeActiveRelease,
            after = active.scheduler.snapshot(),
            liveRecords = -1,
            activeResolutions = -1,
        )

        val materialized = SchedulerFixture()
        val materializedId = materialized.commitImmediate(transactionId = 2)
        assertTrue(materialized.scheduler.nextWriterClaim() is TunWriterClaim.Publish)
        val beforeMaterializedRelease = materialized.scheduler.snapshot()
        materialized.scheduler.failWriter(materializedId)
        assertCategoryDelta(
            before = beforeMaterializedRelease,
            after = materialized.scheduler.snapshot(),
            liveRecords = -1,
            materializedWrites = -1,
        )

        val replacement = SchedulerFixture()
        val replacementId = replacement.commitForward(transactionId = 3)
        replacement.installEngine(generation = 30L)
        assertEquals(
            TunWriterClaim.Write(replacementId),
            replacement.scheduler.nextWriterClaim(),
        )
        val beforeReplacementRelease = replacement.scheduler.snapshot()
        assertSame(
            TunWriteCommit.ReplacementComplete,
            replacement.scheduler.commitWritten(replacementId),
        )
        assertCategoryDelta(
            before = beforeReplacementRelease,
            after = replacement.scheduler.snapshot(),
            liveRecords = -1,
            materializedWrites = -1,
        )

        val suppressed = SchedulerFixture()
        val packet = testDnsPacket(testDnsQuery(transactionId = 4))
        assertTrue(suppressed.scheduler.reserveReaderPermit())
        suppressed.scheduler.beginPlanning(packet, suppressed.currentEngine())
        suppressed.scheduler.suppressAll()
        val beforeSuppressedRelease = suppressed.scheduler.snapshot()
        suppressed.scheduler.releaseSuppressed()
        assertCategoryDelta(
            before = beforeSuppressedRelease,
            after = suppressed.scheduler.snapshot(),
            liveRecords = -1,
        )
    }

    @Test
    fun `every transition conserves records and configured category bounds`() {
        val fixture = SchedulerFixture()
        val ids = mutableListOf<TunQueryId>()
        repeat(6) { transactionId ->
            ids += if (transactionId % 2 == 0) {
                fixture.commitForward(transactionId + 1)
            } else {
                fixture.commitImmediate(transactionId + 1)
            }
            fixture.assertBounds()
        }
        assertFalse(fixture.scheduler.reserveReaderPermit())
        fixture.assertBounds()

        fixture.installEngine(generation = 30L)
        fixture.assertBounds()
        repeat(ids.size) {
            val claim =
                fixture.scheduler.nextWriterClaim() as TunWriterClaim.Write
            fixture.assertBounds()
            fixture.scheduler.commitWritten(claim.id)
            fixture.assertBounds()
        }
        assertNull(fixture.scheduler.nextWriterClaim())
        fixture.assertEmpty()
    }

    @Test
    fun `production capacities admit seventy two forwards and return the seventy third`() {
        val fixture = SchedulerFixture(TunRuntimeCapacities.Production)
        val ids = (1..73).map { fixture.commitForward(transactionId = it) }
        val snapshot = fixture.scheduler.snapshot()

        assertEquals(8, snapshot.activeResolutions)
        assertEquals(64, snapshot.waitingResolutions)
        assertEquals(73, snapshot.liveRecords)
        assertEquals(ids.last(), fixture.overloadId)
        assertEquals(
            ResolverFailureReason.OVERLOADED,
            fixture.overloadPublication().activity?.error,
        )
    }

    private class SchedulerFixture(
        val capacities: TunRuntimeCapacities = TunRuntimeCapacities(1, 2, 2, 6),
    ) {
        private val harness = DnsProxyHarness()
        private val networkContext = testNetworkContext()
        private val resolverPlan = testResolverPlan(networkContext)
        val initialEngine = engine(generation = 29L)
        val scheduler = TunQueryScheduler(
            sessionToken = SESSION_TOKEN,
            initialEngine = initialEngine,
            capacities = capacities,
        )
        var overloadId: TunQueryId? = null
            private set
        private var overloadPending: ProxyResult.Pending? = null

        fun currentEngine(): TestReadyDnsEngine =
            scheduler.currentEngineForPlanning() as TestReadyDnsEngine

        fun installEngine(generation: Long): ReadyDnsEngine? =
            (scheduler.installReplacement(engine(generation)) as TunEngineInstall.Installed)
                .priorEngine

        fun beginForward(transactionId: Int): Begun<ProxyPlan.Forward> {
            val packet = testDnsPacket(testDnsQuery(transactionId = transactionId))
            val engine = currentEngine()
            assertTrue(scheduler.reserveReaderPermit())
            val id = scheduler.beginPlanning(packet, engine)
            return Begun(
                id,
                engine.proxy.plan(packet, engine) as ProxyPlan.Forward,
            )
        }

        fun beginImmediate(transactionId: Int): Begun<ProxyPlan.Immediate> {
            val packet = testDnsPacket(testDnsQuery(transactionId = transactionId))
            val engine = currentEngine()
            assertTrue(scheduler.reserveReaderPermit())
            val id = scheduler.beginPlanning(packet, engine)
            val previous = harness.decisions.result
            harness.decisions.result =
                FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST)
            val plan = try {
                engine.proxy.plan(packet, engine) as ProxyPlan.Immediate
            } finally {
                harness.decisions.result = previous
            }
            return Begun(id, plan)
        }

        fun commitForward(transactionId: Int): TunQueryId {
            val begun = beginForward(transactionId)
            when (val commit = scheduler.commitPlan(begun.id, begun.plan)) {
                TunPlanCommit.Queued -> Unit
                is TunPlanCommit.RejectOverloaded -> {
                    assertEquals(begun.id, commit.id)
                    overloadId = commit.id
                    overloadPending =
                        currentEngine().proxy.rejectOverloaded(commit.plan) as ProxyResult.Pending
                    scheduler.commitOverload(commit.id, checkNotNull(overloadPending))
                }
                else -> error("unexpected Forward commit $commit")
            }
            return begun.id
        }

        fun commitImmediate(transactionId: Int): TunQueryId {
            val begun = beginImmediate(transactionId)
            when (val commit = scheduler.commitPlan(begun.id, begun.plan)) {
                TunPlanCommit.Queued,
                is TunPlanCommit.AwaitWriterCapacity,
                -> Unit
                else -> error("unexpected Immediate commit $commit")
            }
            return begun.id
        }

        fun pending(transactionId: Int): ProxyResult.Pending =
            beginUntrackedImmediate(transactionId).result

        fun drainResolutionOrder(count: Int): List<TunQueryId> = buildList {
            repeat(count) { workerIndex ->
                val claim = checkNotNull(scheduler.claimResolution(workerIndex))
                add(claim.id)
                assertSame(
                    TunWorkerCommit.Suppressed,
                    scheduler.completeResolution(claim.id, ProxyResult.NotPublishable),
                )
            }
        }

        fun overloadPublication(): ProxyPublication {
            val before = harness.sink.publications.size
            assertTrue(checkNotNull(overloadPending).publishIfCurrent())
            return harness.sink.publications[before]
        }

        fun writeOriginal(id: TunQueryId) {
            val claim = scheduler.nextWriterClaim() as TunWriterClaim.Publish
            assertEquals(id, claim.id)
            assertTrue(scheduler.stagePublication(id, publication(byteArrayOf(1, 2, 3))))
            assertTrue(scheduler.selectWrite(id) is TunSelectedWrite.Original)
            val commit = scheduler.commitWritten(id) as TunWriteCommit.OriginalEvidence
            scheduler.finishEvidence(id, accepted = true)
            assertTrue(commit.evidence.healthEvents.isEmpty())
        }

        fun publication(packet: ByteArray) = ProxyPublication(
            responsePacket = packet,
            activity = null,
            healthEvents = emptyList(),
            resolverQueryEvidence = null,
        )

        fun assertSnapshot(
            readerPermitHeld: Boolean,
            liveRecords: Int,
            activeResolutions: Int,
            waitingResolutions: Int,
            materializedWrites: Int,
        ) {
            val snapshot = scheduler.snapshot()
            assertEquals(readerPermitHeld, snapshot.readerPermitHeld)
            assertEquals(liveRecords, snapshot.liveRecords)
            assertEquals(activeResolutions, snapshot.activeResolutions)
            assertEquals(waitingResolutions, snapshot.waitingResolutions)
            assertEquals(materializedWrites, snapshot.materializedWrites)
            assertBounds()
        }

        fun assertBounds() {
            val snapshot = scheduler.snapshot()
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

        fun assertEmpty() {
            assertSnapshot(
                readerPermitHeld = false,
                liveRecords = 0,
                activeResolutions = 0,
                waitingResolutions = 0,
                materializedWrites = 0,
            )
            assertEquals(0, scheduler.snapshot().replacementPromotions)
            assertTrue(scheduler.snapshot().states.isEmpty())
        }

        private fun beginUntrackedImmediate(transactionId: Int): ProxyPlan.Immediate {
            val packet = testDnsPacket(testDnsQuery(transactionId = transactionId))
            val engine = currentEngine()
            val previous = harness.decisions.result
            harness.decisions.result =
                FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST)
            return try {
                engine.proxy.plan(packet, engine) as ProxyPlan.Immediate
            } finally {
                harness.decisions.result = previous
            }
        }

        private fun engine(generation: Long): TestReadyDnsEngine = harness.engine(
            networkContext = networkContext,
            resolverPlan = resolverPlan,
            epoch = testEpoch(
                policyIdentity = harness.policyIdentity,
                resolverPlan = resolverPlan,
                sessionGeneration = SESSION_TOKEN,
                engineGeneration = generation,
            ),
        )
    }

    private data class Begun<T : ProxyPlan>(
        val id: TunQueryId,
        val plan: T,
    )

    private fun assertCategoryDelta(
        before: TunSchedulerSnapshot,
        after: TunSchedulerSnapshot,
        liveRecords: Int,
        activeResolutions: Int = 0,
        waitingResolutions: Int = 0,
        materializedWrites: Int = 0,
        replacementPromotions: Int = 0,
    ) {
        assertEquals(liveRecords, after.liveRecords - before.liveRecords)
        assertEquals(
            activeResolutions,
            after.activeResolutions - before.activeResolutions,
        )
        assertEquals(
            waitingResolutions,
            after.waitingResolutions - before.waitingResolutions,
        )
        assertEquals(
            materializedWrites,
            after.materializedWrites - before.materializedWrites,
        )
        assertEquals(
            replacementPromotions,
            after.replacementPromotions - before.replacementPromotions,
        )
    }

    private companion object {
        const val SESSION_TOKEN = 23L
    }
}
