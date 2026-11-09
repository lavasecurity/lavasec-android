package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ConfigurationIdentity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoundedTunRuntimeReplacementTest {
    @Test
    fun `replacement during Planning followed by Drop releases silently`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.mode(1, PlanMode.Drop)
        fixture.replaceDuringNextPlan(30)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()

        assertTrue(fixture.planningReplacementResult())
        fixture.assertNoResponse(1)
        assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
        fixture.close()
    }

    @Test
    fun `replacement during Planning followed by Immediate owns one servfail`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.mode(1, PlanMode.Immediate)
        fixture.replaceDuringNextPlan(30)
        fixture.scriptWritten()
        fixture.start()
        fixture.enqueue(1)
        runCurrent()

        assertTrue(fixture.planningReplacementResult())
        fixture.assertReplacementOnly(1)
        assertEquals(0, fixture.initialEngine.fenceCalls)
        fixture.close()
    }

    @Test
    fun `replacement during Planning followed by Forward never executes stale plan`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.replaceDuringNextPlan(30)
        fixture.scriptWritten()
        fixture.start()
        fixture.enqueue(1)
        runCurrent()

        assertTrue(fixture.planningReplacementResult())
        fixture.assertReplacementOnly(1)
        assertTrue(fixture.proxy.executedIds.isEmpty())
        assertEquals(0, fixture.initialEngine.fenceCalls)
        fixture.close()
    }

    @Test
    fun `plan failure after replacement suppresses stale record and keeps successor`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.mode(1, PlanMode.Fail)
        fixture.replaceDuringNextPlan(30)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()

        assertTrue(fixture.planningReplacementResult())
        fixture.assertNoResponse(1)
        assertTrue(fixture.observer.failures.isEmpty())
        assertEquals(0, fixture.planningSuccessor().closeCalls)
        fixture.close()
        assertEquals(1, fixture.planningSuccessor().closeCalls)
    }

    @Test
    fun `overload failure after replacement keeps successor and exact replacements`() = runTest {
        val fixture = ReplacementFixture(this, SMALL_CAPACITIES)
        fixture.failOverload(3)
        fixture.replaceDuringNextOverload(30)
        fixture.scriptWritten(3)
        fixture.start()
        fixture.enqueue(1)
        fixture.enqueue(2)
        fixture.enqueue(3)
        runCurrent()

        assertTrue(fixture.overloadReplacementResult())
        fixture.assertReplacementOnly(1)
        fixture.assertReplacementOnly(2)
        fixture.assertReplacementOnly(3)
        assertTrue(fixture.observer.failures.isEmpty())
        assertEquals(0, fixture.overloadSuccessor().closeCalls)
        fixture.close()
        assertEquals(1, fixture.overloadSuccessor().closeCalls)
    }

    @Test
    fun `replacement while waiting resolution promotes one replacement`() = runTest {
        val fixture = ReplacementFixture(this, SMALL_CAPACITIES)
        fixture.start()
        fixture.enqueue(1)
        fixture.enqueue(2)
        runCurrent()
        assertEquals("WaitingResolution", fixture.runtime.snapshotForTest().stateFor(2))

        fixture.replaceAndWrite(30, writeCount = 2)

        fixture.assertReplacementOnly(1)
        fixture.assertReplacementOnly(2)
        assertFalse(2L in fixture.proxy.executedIds)
        fixture.close()
    }

    @Test
    fun `replacement during active resolution discards late old completion`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()
        assertEquals("Resolving", fixture.runtime.snapshotForTest().stateFor(1))

        fixture.replaceAndWrite(30)
        fixture.completeResolution(1)
        runCurrent()

        fixture.assertReplacementOnly(1)
        assertEquals(0, fixture.observer.evidence.size)
        assertEquals(0, fixture.initialEngine.fenceCalls)
        fixture.close()
    }

    @Test
    fun `ordinary failure from replaced active resolution cannot terminate successor`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()
        assertEquals("Resolving", fixture.runtime.snapshotForTest().stateFor(1))

        val successor = fixture.engine(30)
        assertTrue(fixture.runtime.replaceEngine(successor))
        fixture.completeResolutionFailure(1, IllegalStateException("retired resolver"))
        fixture.scriptWritten()
        fixture.writable()
        runCurrent()

        fixture.assertReplacementOnly(1)
        assertTrue(fixture.observer.failures.isEmpty())
        assertEquals(0, successor.closeCalls)
        fixture.close()
        assertEquals(1, successor.closeCalls)
    }

    @Test
    fun `cancellation from replaced active resolution cannot terminate successor`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()
        assertEquals("Resolving", fixture.runtime.snapshotForTest().stateFor(1))

        val successor = fixture.engine(30)
        assertTrue(fixture.runtime.replaceEngine(successor))
        fixture.completeResolutionFailure(1, CancellationException("retired resolver"))
        fixture.scriptWritten()
        fixture.writable()
        runCurrent()

        fixture.assertReplacementOnly(1)
        assertTrue(fixture.observer.failures.isEmpty())
        assertEquals(0, successor.closeCalls)
        fixture.close()
        assertEquals(1, successor.closeCalls)
    }

    @Test
    fun `current execution failure claims termination atomically before replacement install`() =
        runTest {
            val executor = Executors.newFixedThreadPool(4)
            val dispatcher = executor.asCoroutineDispatcher()
            val fixture = ReplacementFixture(this, dispatcher = dispatcher)
            val executionEntered = CountDownLatch(1)
            val gatedLock = FailureClassificationReleaseLock()
            fixture.installRuntimeLock(gatedLock)
            fixture.proxy.onExecute = {
                executionEntered.countDown()
            }
            try {
                fixture.start()
                fixture.enqueue(1)
                executionEntered.await()

                fixture.beforeNextResolutionFailureRethrow(
                    gatedLock::armForCurrentThread,
                )
                fixture.completeResolutionFailure(1, IllegalStateException("current resolver"))
                gatedLock.awaitClassificationRelease()

                val proposal = fixture.engine(30)
                val replacementResult = AtomicReference<Boolean>()
                val replacementDone = CountDownLatch(1)
                Thread {
                    try {
                        replacementResult.set(
                            runBlocking { fixture.runtime.replaceEngine(proposal) },
                        )
                    } finally {
                        replacementDone.countDown()
                    }
                }.start()
                replacementDone.await()

                assertFalse(replacementResult.get())
                assertEquals(1, proposal.closeCalls)

                gatedLock.allowFailureHandling()
                fixture.runtime.join()
                assertEquals(1, fixture.initialEngine.closeCalls)
                assertEquals(1, fixture.tun.closeCalls.get())
                assertEquals(
                    listOf(com.lavasec.app.platform.ProtectionFailure.DependencyUnavailable),
                    fixture.observer.failures,
                )
            } finally {
                gatedLock.allowFailureHandling()
                fixture.runtime.requestClose()
                runCatching { runBlocking { fixture.runtime.join() } }
                dispatcher.close()
                executor.shutdown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `replacement after Pending before staging prioritizes replacement`() = runTest {
        val fixture = ReplacementFixture(this, TWO_WRITE_CAPACITIES)
        fixture.mode(1, PlanMode.Immediate)
        fixture.mode(2, PlanMode.Immediate)
        fixture.start()
        fixture.enqueue(1)
        fixture.enqueue(2)
        runCurrent()
        assertEquals("StagedOriginal", fixture.runtime.snapshotForTest().stateFor(1))
        assertEquals("ReadyPending", fixture.runtime.snapshotForTest().stateFor(2))

        fixture.replaceAndWrite(30, writeCount = 2)

        fixture.assertReplacementOnly(1)
        fixture.assertReplacementOnly(2)
        fixture.close()
    }

    @Test
    fun `replacement after staging before Writable replaces exact retained ticket`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.mode(1, PlanMode.Immediate)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()
        assertEquals("StagedOriginal", fixture.runtime.snapshotForTest().stateFor(1))

        fixture.replaceAndWrite(30)

        fixture.assertReplacementOnly(1)
        fixture.close()
    }

    @Test
    fun `full original write winning replacement race keeps original evidence`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.mode(1, PlanMode.Immediate)
        fixture.scriptWritten()
        fixture.replaceFromFirstWrite(30)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()
        fixture.awaitWriteReplacement()
        runCurrent()

        fixture.assertOriginalOnly(1)
        assertEquals(1, fixture.observer.evidence.size)
        assertEquals(1, fixture.initialEngine.closeCalls)
        fixture.close()
    }

    @Test
    fun `engine fence rejection racing replacement writes only replacement`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.mode(1, PlanMode.Immediate)
        fixture.replaceFromFirstFence(30)
        fixture.scriptWritten()
        fixture.start()
        fixture.enqueue(1)
        runCurrent()

        fixture.assertReplacementOnly(1)
        assertEquals(1, fixture.initialEngine.fenceCalls)
        fixture.close()
    }

    @Test
    fun `late resolver completion after replacement cannot publish or add evidence`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()

        fixture.replaceAndWrite(30)
        fixture.completeResolution(1, ProxyResult.NotPublishable)
        runCurrent()

        fixture.assertReplacementOnly(1)
        assertEquals(0, fixture.initialEngine.fenceCalls)
        fixture.close()
    }

    @Test
    fun `two same session replacements retain one record packet and promotion`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.mode(1, PlanMode.Immediate)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()

        val first = fixture.engine(30)
        val second = fixture.engine(31)
        assertTrue(fixture.runtime.replaceEngine(first))
        assertTrue(fixture.runtime.replaceEngine(second))
        assertEquals(1, fixture.runtime.snapshotForTest().liveRecords)
        assertEquals(1, fixture.runtime.snapshotForTest().replacementPromotions)
        assertEquals(1, fixture.initialEngine.closeCalls)
        assertEquals(1, first.closeCalls)
        fixture.scriptWritten()
        fixture.writable()
        runCurrent()

        fixture.assertReplacementOnly(1)
        fixture.close()
    }

    @Test
    fun `sequential same instance rejection preserves installed engine ownership`() = runTest {
        val fixture = ReplacementFixture(this)
        val installed = fixture.engine(30)
        fixture.mode(1, PlanMode.Immediate)
        fixture.scriptWritten()
        fixture.start()

        assertTrue(fixture.runtime.replaceEngine(installed))
        assertFalse(fixture.runtime.replaceEngine(installed))
        assertEquals(1, fixture.initialEngine.closeCalls)
        assertEquals(0, installed.closeCalls)

        fixture.enqueue(1)
        runCurrent()

        fixture.assertOriginalOnly(1)
        assertEquals(1, fixture.observer.evidence.size)
        assertEquals(30L, fixture.proxy.plannedEngineGenerations.single())

        fixture.close()
        assertEquals(1, installed.closeCalls)
    }

    @Test
    fun `completed retirement cannot be reproposed through the same engine reference`() = runTest {
        val fixture = ReplacementFixture(this)
        val retired = fixture.engine(30)
        val installed = fixture.engine(31)
        fixture.mode(1, PlanMode.Immediate)
        fixture.scriptWritten()
        fixture.start()

        assertTrue(fixture.runtime.replaceEngine(retired))
        assertTrue(fixture.runtime.replaceEngine(installed))
        assertEquals(1, retired.closeCalls)

        assertFalse(fixture.runtime.replaceEngine(retired))
        assertEquals(1, retired.closeCalls)
        assertEquals(0, installed.closeCalls)

        fixture.enqueue(1)
        runCurrent()

        fixture.assertOriginalOnly(1)
        assertEquals(31L, fixture.proxy.plannedEngineGenerations.single())

        fixture.close()
        assertEquals(1, retired.closeCalls)
        assertEquals(1, installed.closeCalls)
    }

    @Test
    fun `pre retired coherent higher generation is rejected without a second close`() = runTest {
        val fixture = ReplacementFixture(this)
        val retired = fixture.engine(40)
        fixture.mode(1, PlanMode.Immediate)
        fixture.scriptWritten()
        fixture.start()
        retired.close()

        assertFalse(fixture.runtime.replaceEngine(retired))
        assertEquals(1, retired.closeCalls)

        fixture.enqueue(1)
        runCurrent()

        fixture.assertOriginalOnly(1)
        assertEquals(29L, fixture.proxy.plannedEngineGenerations.single())

        fixture.close()
        assertEquals(1, retired.closeCalls)
    }

    @Test
    fun `validation incoherence cannot close the scheduler current engine`() = runTest {
        val fixture = ReplacementFixture(this)
        val delegate = fixture.engine(30)
        val installed = MutableCoherenceEngine(delegate)
        fixture.mode(1, PlanMode.Immediate)
        fixture.scriptWritten()
        fixture.start()

        assertTrue(fixture.runtime.replaceEngine(installed))
        installed.makeIncoherent()

        assertFalse(fixture.runtime.replaceEngine(installed))
        assertEquals(0, delegate.closeCalls)

        fixture.enqueue(1)
        runCurrent()

        fixture.assertOriginalOnly(1)
        assertEquals(30L, fixture.proxy.plannedEngineGenerations.single())

        fixture.close()
        assertEquals(1, delegate.closeCalls)
    }

    @Test
    fun `sequential duplicate incoherent proposal closes exactly once`() = runTest {
        val fixture = ReplacementFixture(this)
        val coherent = fixture.engine(30)
        val proposal = fixture.engine(
            30,
            epoch = coherent.epoch.copy(sessionGeneration = SESSION + 1),
        )
        fixture.start()

        assertFalse(fixture.runtime.replaceEngine(proposal))
        assertFalse(fixture.runtime.replaceEngine(proposal))

        assertEquals(1, proposal.closeCalls)
        fixture.close()
        assertEquals(1, proposal.closeCalls)
    }

    @Test
    fun `concurrent same instance rejection preserves installed engine ownership`() = runTest {
        val fixture = ReplacementFixture(this)
        val delegate = fixture.engine(30)
        val installed = ConcurrentValidationEngine(delegate)
        val firstResult = AtomicReference<Boolean>()
        val secondResult = AtomicReference<Boolean>()
        val replacementsDone = CountDownLatch(2)
        fixture.mode(1, PlanMode.Immediate)
        fixture.scriptWritten()
        fixture.start()

        listOf(firstResult, secondResult).forEach { result ->
            Thread {
                try {
                    result.set(runBlocking { fixture.runtime.replaceEngine(installed) })
                } finally {
                    replacementsDone.countDown()
                }
            }.start()
        }
        replacementsDone.await()

        assertEquals(listOf(false, true), listOf(firstResult.get(), secondResult.get()).sorted())
        assertEquals(1, fixture.initialEngine.closeCalls)
        assertEquals(0, delegate.closeCalls)

        fixture.enqueue(1)
        runCurrent()

        fixture.assertOriginalOnly(1)
        assertEquals(1, fixture.observer.evidence.size)
        assertEquals(30L, fixture.proxy.plannedEngineGenerations.single())

        fixture.close()
        assertEquals(1, delegate.closeCalls)
    }

    @Test
    fun `close winning two same uninstalled proposals closes loser once`() = runTest {
        val fixture = ReplacementFixture(this)
        val proposal = CloseWinningDuplicateEngine(fixture.engine(30))
        val firstResult = AtomicReference<Boolean>()
        val secondResult = AtomicReference<Boolean>()
        val replacementsDone = CountDownLatch(2)
        fixture.start()

        listOf(firstResult, secondResult).forEach { result ->
            Thread {
                try {
                    result.set(runBlocking { fixture.runtime.replaceEngine(proposal) })
                } finally {
                    replacementsDone.countDown()
                }
            }.start()
        }
        proposal.awaitBothValidating()

        fixture.runtime.requestClose()
        proposal.allowValidationToFinish()
        replacementsDone.await()
        fixture.runtime.join()

        assertEquals(listOf(false, false), listOf(firstResult.get(), secondResult.get()))
        assertEquals(1, proposal.closeCalls.get())
        assertEquals(1, fixture.initialEngine.closeCalls)
        assertEquals(1, fixture.tun.closeCalls.get())
        assertTrue(fixture.proxy.plannedEngineGenerations.isEmpty())
    }

    @Test
    fun `delayed duplicate retired proposal does not reclaim close ownership`() = runTest {
        val fixture = ReplacementFixture(this)
        val first = DelayedDuplicateEngine(fixture.engine(30))
        val second = fixture.engine(31)
        val duplicateResult = AtomicReference<Boolean>()
        val duplicateDone = CountDownLatch(1)
        fixture.mode(1, PlanMode.Immediate)
        fixture.scriptWritten()
        fixture.start()

        assertTrue(fixture.runtime.replaceEngine(first))
        first.delayNextValidation()
        Thread {
            try {
                duplicateResult.set(runBlocking { fixture.runtime.replaceEngine(first) })
            } finally {
                duplicateDone.countDown()
            }
        }.start()
        first.awaitDelayedValidation()

        assertTrue(fixture.runtime.replaceEngine(second))
        assertEquals(1, first.closeCalls.get())
        first.allowValidationToFinish()
        duplicateDone.await()

        assertFalse(duplicateResult.get())
        assertEquals(1, fixture.initialEngine.closeCalls)
        assertEquals(1, first.closeCalls.get())
        assertEquals(0, second.closeCalls)

        fixture.enqueue(1)
        runCurrent()

        fixture.assertOriginalOnly(1)
        assertEquals(1, fixture.observer.evidence.size)
        assertEquals(31L, fixture.proxy.plannedEngineGenerations.single())

        fixture.close()
        assertEquals(1, first.closeCalls.get())
        assertEquals(1, second.closeCalls)
    }

    @Test
    fun `duplicate entering during prior close joins retirement ownership`() = runTest {
        val fixture = ReplacementFixture(this)
        val first = RetirementBlockingEngine(fixture.engine(30))
        val second = fixture.engine(31)
        val replacementResult = AtomicReference<Boolean>()
        val replacementDone = CountDownLatch(1)
        val duplicateResult = AtomicReference<Boolean>()
        val duplicateDone = CountDownLatch(1)
        fixture.mode(1, PlanMode.Immediate)
        fixture.scriptWritten()
        fixture.start()

        assertTrue(fixture.runtime.replaceEngine(first))
        Thread {
            try {
                replacementResult.set(runBlocking { fixture.runtime.replaceEngine(second) })
            } finally {
                replacementDone.countDown()
            }
        }.start()
        first.awaitRetirementClose()
        Thread {
            try {
                duplicateResult.set(runBlocking { fixture.runtime.replaceEngine(first) })
            } finally {
                duplicateDone.countDown()
            }
        }.start()

        try {
            duplicateDone.await()
            assertFalse(duplicateResult.get())
            assertEquals(1, first.closeCalls.get())
            assertEquals(0, second.closeCalls)

            first.allowRetirementClose()
            replacementDone.await()
            assertTrue(replacementResult.get())
            assertEquals(1, fixture.initialEngine.closeCalls)
            assertEquals(1, first.closeCalls.get())

            fixture.enqueue(1)
            runCurrent()

            fixture.assertOriginalOnly(1)
            assertEquals(1, fixture.observer.evidence.size)
            assertEquals(31L, fixture.proxy.plannedEngineGenerations.single())
        } finally {
            first.allowRetirementClose()
            replacementDone.await()
            fixture.close()
        }

        assertEquals(1, first.closeCalls.get())
        assertEquals(1, second.closeCalls)
    }

    @Test
    fun `close winning between replacement validation and install closes proposed loser`() =
        runTest {
        val fixture = ReplacementFixture(this)
        fixture.mode(1, PlanMode.Immediate)
        fixture.start()
        fixture.enqueue(1)
        runCurrent()
        val validationEntered = CountDownLatch(1)
        val allowInstall = CountDownLatch(1)
        val delegate = fixture.engine(30)
        val proposed = ValidationGatedEngine(delegate) {
            validationEntered.countDown()
            allowInstall.await()
        }
        val replacement = AtomicReference<Boolean>()
        val replacementDone = CountDownLatch(1)
        val thread = Thread {
            try {
                replacement.set(runBlocking { fixture.runtime.replaceEngine(proposed) })
            } finally {
                replacementDone.countDown()
            }
        }
        thread.start()
        validationEntered.await()

        fixture.runtime.requestClose()
        allowInstall.countDown()
        replacementDone.await()
        fixture.runtime.join()

        assertFalse(replacement.get())
        assertEquals(1, delegate.closeCalls)
        fixture.assertNoResponse(1)
    }

    @Test
    fun `every incoherent replacement is rejected unseen and closed exactly once`() = runTest {
        val fixture = ReplacementFixture(this)
        fixture.start()
        val coherent = fixture.engine(30)
        val invalid = listOf(
            fixture.engine(30, epoch = coherent.epoch.copy(sessionGeneration = SESSION + 1)),
            fixture.engine(29),
            fixture.engine(
                30,
                policyIdentity = coherent.policyIdentity.copy(
                    configurationIdentity = ConfigurationIdentity(999, "other"),
                ),
            ),
            fixture.engine(
                30,
                policyIdentity = coherent.policyIdentity.copy(artifactDigest = "wrong"),
            ),
            fixture.engine(
                30,
                policyIdentity = coherent.policyIdentity.copy(revision = 999),
            ),
            fixture.engine(
                30,
                networkContext = testNetworkContext(
                    network = coherent.networkContext.network,
                    networkGeneration = coherent.networkContext.networkGeneration + 1,
                ),
            ),
            fixture.engine(
                30,
                resolverPlan = testResolverPlan(
                    coherent.networkContext,
                    network = testNetwork(handle = 99),
                ),
            ),
            fixture.engine(
                30,
                resolverPlan = testResolverPlan(
                    coherent.networkContext,
                    networkGeneration = coherent.networkContext.networkGeneration + 1,
                ),
            ),
            fixture.engine(
                30,
                resolverPlan = testResolverPlan(
                    coherent.networkContext,
                    isCellular = !coherent.networkContext.isCellular,
                ),
            ),
            fixture.engine(
                30,
                epoch = coherent.epoch.copy(
                    primaryResolverIdentity = PrimaryResolverIdentity("wrong"),
                ),
            ),
            fixture.engine(
                30,
                epoch = coherent.epoch.copy(
                    fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                ),
            ),
        )

        invalid.forEachIndexed { index, candidate ->
            val queryId = index.toLong() + 1L
            fixture.mode(queryId, PlanMode.Drop)
            assertFalse(fixture.runtime.replaceEngine(candidate))
            assertEquals(1, candidate.closeCalls)
            fixture.enqueue(queryId)
            runCurrent()
            assertEquals(29L, fixture.proxy.plannedEngineGenerations.last())
        }
        fixture.close()
    }

    @Test
    fun `different runtime close suppresses staged old session and late writable signal`() =
        runTest {
        val old = ReplacementFixture(this)
        old.mode(1, PlanMode.Immediate)
        old.start()
        old.enqueue(1)
        runCurrent()
        assertEquals(
            "StagedOriginal",
            old.runtime.snapshotForTest().states[TunQueryId(1)],
        )
        assertEquals(1, old.tun.attemptedWrites.size)
        assertTrue(old.tun.recordedWrites.isEmpty())
        assertEquals(1, old.tun.writableCalls.get())

        old.scriptWritten()
        old.writable()
        old.runtime.requestClose()
        runCurrent()
        old.runtime.join()

        val fresh = ReplacementFixture(this, sessionToken = SESSION + 1)
        fresh.start()
        runCurrent()

        old.assertNoResponse(1)
        assertEquals(0, old.observer.evidence.size)
        assertTrue(fresh.tun.recordedWrites.isEmpty())
        assertEquals(0, fresh.observer.evidence.size)
        fresh.close()
    }

    private companion object {
        const val SESSION = 23L
        val SMALL_CAPACITIES = TunRuntimeCapacities(1, 1, 2, 5)
        val TWO_WRITE_CAPACITIES = TunRuntimeCapacities(1, 1, 2, 5)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ReplacementFixture(
    private val scope: TestScope,
    capacities: TunRuntimeCapacities = TunRuntimeCapacities.Production,
    private val sessionToken: Long = 23L,
    dispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
) {
    val tun = ScriptedTunPacketSession()
    val observer = RecordingTunRuntimeObserver()
    private val modes = ConcurrentHashMap<Long, PlanMode>()
    private val resolutions = ConcurrentHashMap<Long, CompletableDeferred<ProxyResult>>()
    private val overloadFailures = ConcurrentHashMap<Long, Throwable>()
    private val beforeResolutionFailureRethrow = AtomicReference<(() -> Unit)?>()
    private val networkContext = testNetworkContext()
    private val resolverPlan = testResolverPlan(networkContext)
    private val policyIdentity = testPolicyIdentity()
    private val planningReplacement = AtomicReference<Boolean>()
    private val planningReplacementEngine = AtomicReference<TestReadyDnsEngine>()
    private val planningReplacementDone = CountDownLatch(1)
    private val overloadReplacement = AtomicReference<Boolean>()
    private val overloadReplacementEngine = AtomicReference<TestReadyDnsEngine>()
    private val overloadReplacementDone = CountDownLatch(1)
    private val writeReplacementDone = CountDownLatch(1)
    private val fenceReplacementDone = CountDownLatch(1)
    val runtime: BoundedTunRuntime
    val proxy = TestRuntimeDnsProxy(
        planner = { packet, readyEngine ->
            val id = packet.runtimeTransactionIdForTask6()
            when (modes[id] ?: PlanMode.Forward) {
                PlanMode.Drop -> ProxyPlan.Drop
                PlanMode.Immediate -> ProxyPlan.Immediate(
                    result = pending(id, readyEngine),
                    replacement = ProxyReplacementResponse(replacementPacket(id)),
                )
                PlanMode.Fail -> throw IllegalStateException("planning failed")
                PlanMode.Forward -> forwardPlan(id, packet, readyEngine)
            }
        },
        unavailablePlanner = { _, _ -> ProxyPlan.Drop },
        executor = { plan ->
            try {
                resolutions.computeIfAbsent(plan.request.queryId) { CompletableDeferred() }
                    .await()
            } catch (failure: Throwable) {
                beforeResolutionFailureRethrow.getAndSet(null)?.invoke()
                throw failure
            }
        },
        overloadRejector = { plan ->
            overloadFailures.remove(plan.request.queryId)?.let { throw it }
            pending(plan.request.queryId, plan.engine)
        },
    )
    val initialEngine = engine(29)

    init {
        runtime = BoundedTunRuntime(
            sessionToken = sessionToken,
            packetSession = tun,
            observer = observer,
            dispatcher = dispatcher,
            capacities = capacities,
        )
    }

    fun start() = runtime.start(initialEngine)

    fun installRuntimeLock(replacement: ReentrantLock) {
        BoundedTunRuntime::class.java.getDeclaredField("lock").apply {
            isAccessible = true
            set(runtime, replacement)
        }
    }

    fun mode(id: Long, mode: PlanMode) {
        modes[id] = mode
    }

    fun failOverload(id: Long) {
        overloadFailures[id] = IllegalStateException("overload rejection failed")
    }

    fun enqueue(id: Long) {
        tun.enqueuePacket(testRuntimeDnsPacketForTask6(id.toInt()))
    }

    fun completeResolution(
        id: Long,
        result: ProxyResult = pending(id, initialEngine),
    ) {
        resolutions.computeIfAbsent(id) { CompletableDeferred() }
            .complete(result)
    }

    fun completeResolutionFailure(id: Long, failure: Throwable) {
        check(
            resolutions.computeIfAbsent(id) { CompletableDeferred() }
                .completeExceptionally(failure),
        )
    }

    fun beforeNextResolutionFailureRethrow(action: () -> Unit) {
        check(beforeResolutionFailureRethrow.compareAndSet(null, action))
    }

    fun scriptWritten(count: Int = 1) {
        repeat(count) { check(tun.writeResults.trySend(TunWriteAttempt.Written).isSuccess) }
    }

    fun writable() {
        check(tun.writableResults.trySend(TunWritable.Writable).isSuccess)
    }

    suspend fun replace(generation: Long): Boolean =
        runtime.replaceEngine(engine(generation))

    fun replaceDuringNextPlan(generation: Long) {
        proxy.onPlan = { _, _ ->
            proxy.onPlan = null
            val successor = engine(generation)
            planningReplacementEngine.set(successor)
            Thread {
                try {
                    planningReplacement.set(
                        runBlocking { runtime.replaceEngine(successor) },
                    )
                } finally {
                    planningReplacementDone.countDown()
                }
            }.start()
            planningReplacementDone.await()
        }
    }

    fun planningReplacementResult(): Boolean {
        planningReplacementDone.await()
        return planningReplacement.get()
    }

    fun planningSuccessor(): TestReadyDnsEngine =
        checkNotNull(planningReplacementEngine.get())

    fun replaceDuringNextOverload(generation: Long) {
        proxy.onRejectOverloaded = {
            proxy.onRejectOverloaded = null
            val successor = engine(generation)
            overloadReplacementEngine.set(successor)
            Thread {
                try {
                    overloadReplacement.set(
                        runBlocking { runtime.replaceEngine(successor) },
                    )
                } finally {
                    overloadReplacementDone.countDown()
                }
            }.start()
            overloadReplacementDone.await()
        }
    }

    fun overloadReplacementResult(): Boolean {
        overloadReplacementDone.await()
        return overloadReplacement.get()
    }

    fun overloadSuccessor(): TestReadyDnsEngine =
        checkNotNull(overloadReplacementEngine.get())

    fun replaceFromFirstWrite(generation: Long) {
        tun.onWrite = {
            tun.onWrite = null
            Thread {
                try {
                    runBlocking { runtime.replaceEngine(engine(generation)) }
                } finally {
                    writeReplacementDone.countDown()
                }
            }.start()
        }
    }

    fun awaitWriteReplacement() {
        writeReplacementDone.await()
    }

    fun replaceFromFirstFence(generation: Long) {
        initialEngine.onFence = {
            initialEngine.onFence = null
            Thread {
                try {
                    runBlocking { runtime.replaceEngine(engine(generation)) }
                } finally {
                    fenceReplacementDone.countDown()
                }
            }.start()
            fenceReplacementDone.await()
        }
    }

    suspend fun replaceAndWrite(generation: Long, writeCount: Int = 1) {
        assertTrue(replace(generation))
        scriptWritten(writeCount)
        writable()
        scope.runCurrent()
    }

    suspend fun close() {
        runtime.closeAndJoin()
    }

    fun engine(
        generation: Long,
        networkContext: NetworkContext = this.networkContext,
        resolverPlan: ResolverPlan = this.resolverPlan,
        policyIdentity: ProxyPolicyIdentity = this.policyIdentity,
        epoch: EngineEpoch = testEpoch(
            policyIdentity = this.policyIdentity,
            resolverPlan = this.resolverPlan,
            sessionGeneration = sessionToken,
            engineGeneration = generation,
        ),
    ) = TestReadyDnsEngine(
        epoch = epoch,
        networkContext = networkContext,
        resolverPlan = resolverPlan,
        policyIdentity = policyIdentity,
        proxy = proxy,
    )

    fun assertReplacementOnly(id: Long) {
        assertEquals(1, tun.recordedWrites.count { it.contentEquals(replacementPacket(id)) })
        assertEquals(0, tun.recordedWrites.count { it.contentEquals(originalPacket(id)) })
        assertEquals(0, observer.evidence.size)
    }

    fun assertOriginalOnly(id: Long) {
        assertEquals(1, tun.recordedWrites.count { it.contentEquals(originalPacket(id)) })
        assertEquals(0, tun.recordedWrites.count { it.contentEquals(replacementPacket(id)) })
    }

    fun assertNoResponse(id: Long) {
        assertEquals(
            0,
            tun.recordedWrites.count {
                it.contentEquals(originalPacket(id)) || it.contentEquals(replacementPacket(id))
            },
        )
    }

    private fun forwardPlan(
        id: Long,
        packet: ByteArray,
        readyEngine: ReadyDnsEngine,
    ): ProxyPlan.Forward {
        val datagram = checkNotNull(Ipv4UdpDnsPacket.parse(packet))
        val question = com.lavasec.app.foundation.dns.DnsMessage.parseQuestion(datagram.payload)
        return ProxyPlan.Forward.create(
            request = ResolverRequest(
                query = datagram.payload,
                question = question,
                epoch = readyEngine.epoch,
                networkContext = readyEngine.networkContext,
                queryId = id,
            ),
            activity = DnsDecisionEvent(
                id = id,
                timestampMillis = TEST_NOW_MILLIS,
                normalizedDomain = question.normalizedDomain,
                decision = FilterDecision.DEFAULT_ALLOW,
            ),
            policyIdentity = readyEngine.policyIdentity,
            plannedProtectionSessionId = TEST_PROTECTION_SESSION_ID,
            plannedPauseMaximumResponseTtlSeconds = null,
            startedPaused = false,
            replacement = ProxyReplacementResponse(replacementPacket(id)),
            engine = readyEngine,
            datagram = datagram,
            owner = proxy,
        )
    }

    private fun pending(id: Long, engine: ReadyDnsEngine) = ProxyResult.Pending(
        fence = engine,
        publication = ProxyPublication(
            responsePacket = originalPacket(id),
            activity = DnsDecisionEvent(
                id = id,
                timestampMillis = TEST_NOW_MILLIS,
                normalizedDomain = "q$id.example",
                decision = FilterDecision.DEFAULT_ALLOW,
            ),
            healthEvents = emptyList(),
            resolverQueryEvidence = null,
        ),
        sink = runtime.publicationSink,
    )
}

internal enum class PlanMode {
    Drop,
    Immediate,
    Fail,
    Forward,
}

private fun originalPacket(id: Long) = byteArrayOf(0x11, id.toByte())
private fun replacementPacket(id: Long) = byteArrayOf(0x22, id.toByte())

private fun ByteArray.runtimeTransactionIdForTask6(): Long =
    readUInt16(28).toLong()

private fun testRuntimeDnsPacketForTask6(transactionId: Int): ByteArray =
    testDnsPacket(testDnsQuery(transactionId = transactionId))

private fun TunSchedulerSnapshot.stateFor(id: Long): String =
    states.getValue(TunQueryId(id))

private class ValidationGatedEngine(
    private val delegate: ReadyDnsEngine,
    private val onFirstPolicyRead: () -> Unit,
) : ReadyDnsEngine by delegate {
    private var gated = false

    override val policyIdentity: ProxyPolicyIdentity
        get() {
            if (!gated) {
                gated = true
                onFirstPolicyRead()
            }
            return delegate.policyIdentity
        }
}

private class ConcurrentValidationEngine(
    private val delegate: ReadyDnsEngine,
) : ReadyDnsEngine by delegate {
    private val validationReads = AtomicInteger()
    private val bothValidating = CountDownLatch(2)

    override val policyIdentity: ProxyPolicyIdentity
        get() {
            if (validationReads.incrementAndGet() <= 2) {
                bothValidating.countDown()
                bothValidating.await()
            }
            return delegate.policyIdentity
    }
}

private class MutableCoherenceEngine(
    private val delegate: ReadyDnsEngine,
) : ReadyDnsEngine by delegate {
    private val coherent = AtomicBoolean(true)

    fun makeIncoherent() {
        check(coherent.compareAndSet(true, false))
    }

    override val policyIdentity: ProxyPolicyIdentity
        get() = if (coherent.get()) {
            delegate.policyIdentity
        } else {
            delegate.policyIdentity.copy(
                configurationIdentity = ConfigurationIdentity(999, "incoherent"),
            )
        }
}

private class CloseWinningDuplicateEngine(
    private val delegate: ReadyDnsEngine,
) : ReadyDnsEngine by delegate {
    val closeCalls = AtomicInteger()
    private val validationReads = AtomicInteger()
    private val bothValidating = CountDownLatch(2)
    private val allowValidation = CountDownLatch(1)

    override val policyIdentity: ProxyPolicyIdentity
        get() {
            if (validationReads.incrementAndGet() <= 2) {
                bothValidating.countDown()
                allowValidation.await()
            }
            return delegate.policyIdentity
        }

    fun awaitBothValidating() {
        bothValidating.await()
    }

    fun allowValidationToFinish() {
        allowValidation.countDown()
    }

    override fun close() {
        closeCalls.incrementAndGet()
        delegate.close()
    }
}

private class DelayedDuplicateEngine(
    private val delegate: ReadyDnsEngine,
) : ReadyDnsEngine by delegate {
    val closeCalls = AtomicInteger()
    private val delayValidation = AtomicBoolean()
    private val delayedValidation = CountDownLatch(1)
    private val allowValidation = CountDownLatch(1)

    fun delayNextValidation() {
        check(delayValidation.compareAndSet(false, true))
    }

    override val policyIdentity: ProxyPolicyIdentity
        get() {
            if (delayValidation.compareAndSet(true, false)) {
                delayedValidation.countDown()
                allowValidation.await()
            }
            return delegate.policyIdentity
        }

    fun awaitDelayedValidation() {
        delayedValidation.await()
    }

    fun allowValidationToFinish() {
        allowValidation.countDown()
    }

    override fun close() {
        closeCalls.incrementAndGet()
        delegate.close()
    }
}

private class RetirementBlockingEngine(
    private val delegate: ReadyDnsEngine,
) : ReadyDnsEngine by delegate {
    val closeCalls = AtomicInteger()
    private val retirementCloseEntered = CountDownLatch(1)
    private val allowRetirementClose = CountDownLatch(1)

    fun awaitRetirementClose() {
        retirementCloseEntered.await()
    }

    fun allowRetirementClose() {
        allowRetirementClose.countDown()
    }

    override fun close() {
        if (closeCalls.incrementAndGet() == 1) {
            retirementCloseEntered.countDown()
            allowRetirementClose.await()
        }
        delegate.close()
    }
}

private class FailureClassificationReleaseLock : ReentrantLock(true) {
    private val classificationReleased = CountDownLatch(1)
    private val allowFailureHandling = CountDownLatch(1)
    private val targetThread = AtomicReference<Thread>()
    private val gated = AtomicBoolean()

    override fun unlock() {
        val shouldGate =
            Thread.currentThread() === targetThread.get() &&
                gated.compareAndSet(false, true)
        super.unlock()
        if (shouldGate) {
            classificationReleased.countDown()
            allowFailureHandling.await()
        }
    }

    fun armForCurrentThread() {
        check(targetThread.compareAndSet(null, Thread.currentThread()))
    }

    fun awaitClassificationRelease() {
        classificationReleased.await()
    }

    fun allowFailureHandling() {
        allowFailureHandling.countDown()
    }
}
