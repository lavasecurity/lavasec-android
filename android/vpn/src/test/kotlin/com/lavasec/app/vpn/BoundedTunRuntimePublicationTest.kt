package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.TunWriteFailureReason
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoundedTunRuntimePublicationTest {
    @Test
    fun `fixed sink rejects publication outside the writer active ticket`() = runTest {
        val fixture = PublicationFixture(this)
        val sinkBeforeStart = fixture.runtime.publicationSink

        assertFalse(sinkBeforeStart.publish(fixture.publication()))
        fixture.start()
        runCurrent()
        assertSame(sinkBeforeStart, fixture.runtime.publicationSink)
        assertFalse(fixture.runtime.publicationSink.publish(fixture.publication()))
        assertTrue(fixture.tun.attemptedWrites.isEmpty())
        assertTrue(fixture.tun.recordedWrites.isEmpty())
        assertTrue(fixture.observer.evidence.isEmpty())

        fixture.close()
    }

    @Test
    fun `writer stages synchronously clears its ticket and performs no IO in sink callback`() =
        runTest {
            var callbackReturned = false
            lateinit var fixture: PublicationFixture
            fixture = PublicationFixture(
                scope = this,
                afterPublicationCallback = {
                    assertTrue(fixture.tun.attemptedWrites.isEmpty())
                    callbackReturned = true
                },
            )
            val publication = fixture.publication()
            fixture.scriptWrites(TunWriteAttempt.WouldBlock)

            fixture.start()
            fixture.enqueue(publication)
            runCurrent()

            assertTrue(callbackReturned)
            assertEquals(1, fixture.tun.attemptedWrites.size)
            assertFalse(fixture.runtime.publicationSink.publish(fixture.publication()))
            assertTrue(fixture.observer.evidence.isEmpty())

            fixture.close()
        }

    @Test
    fun `would block writable then written commits exact terminal evidence once`() = runTest {
        val fixture = PublicationFixture(this)
        val publication = fixture.publication()
        fixture.scriptWrites(TunWriteAttempt.WouldBlock, TunWriteAttempt.Written)
        fixture.scriptWritable(TunWritable.Writable)

        fixture.start()
        fixture.enqueue(publication)
        runCurrent()

        fixture.assertAttempts(publication.responsePacket, publication.responsePacket)
        fixture.assertWritten(publication.responsePacket)
        assertEquals(1, fixture.tun.writableCalls.get())
        fixture.assertEvidence(publication)
        assertTrue(fixture.observer.health.isEmpty())
        assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)

        fixture.close()
    }

    @Test
    fun `second would block waits for a second readiness before writing`() = runTest {
        val fixture = PublicationFixture(this)
        val publication = fixture.publication()
        fixture.scriptWrites(
            TunWriteAttempt.WouldBlock,
            TunWriteAttempt.WouldBlock,
            TunWriteAttempt.Written,
        )
        fixture.scriptWritable(TunWritable.Writable, TunWritable.Writable)

        fixture.start()
        fixture.enqueue(publication)
        runCurrent()

        fixture.assertAttempts(
            publication.responsePacket,
            publication.responsePacket,
            publication.responsePacket,
        )
        fixture.assertWritten(publication.responsePacket)
        assertEquals(2, fixture.tun.writableCalls.get())
        fixture.assertEvidence(publication)

        fixture.close()
    }

    @Test
    fun `spurious writable reenters the fence and retains the exact ticket`() = runTest {
        val fixture = PublicationFixture(this)
        val publication = fixture.publication()
        fixture.scriptWrites(TunWriteAttempt.WouldBlock, TunWriteAttempt.WouldBlock)
        fixture.scriptWritable(TunWritable.Writable)

        fixture.start()
        fixture.enqueue(publication)
        runCurrent()

        fixture.assertAttempts(publication.responsePacket, publication.responsePacket)
        assertEquals(2, fixture.tun.writableCalls.get())
        assertTrue(fixture.tun.recordedWrites.isEmpty())
        assertTrue(fixture.observer.evidence.isEmpty())
        assertEquals("StagedOriginal", fixture.runtime.snapshotForTest().singleState())

        fixture.close()
    }

    @Test
    fun `evidence rejection seals after one irreversible write without resend`() = runTest {
        val fixture = PublicationFixture(this)
        val publication = fixture.publication()
        fixture.observer.evidenceAccepted = false
        fixture.scriptWrites(TunWriteAttempt.Written)

        fixture.start()
        fixture.enqueue(publication)
        runCurrent()
        fixture.runtime.join()

        fixture.assertAttempts(publication.responsePacket)
        fixture.assertWritten(publication.responsePacket)
        fixture.assertEvidence(publication)
        assertTrue(fixture.observer.health.isEmpty())
        assertEquals(1, fixture.tun.closeCalls.get())
        assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
    }

    @Test
    fun `evidence exception seals after one irreversible write without resend`() = runTest {
        val fixture = PublicationFixture(this)
        val publication = fixture.publication()
        fixture.observer.evidenceFailure = IOException("evidence")
        fixture.scriptWrites(TunWriteAttempt.Written)

        fixture.start()
        fixture.enqueue(publication)
        runCurrent()
        fixture.runtime.join()

        fixture.assertAttempts(publication.responsePacket)
        fixture.assertWritten(publication.responsePacket)
        fixture.assertEvidence(publication)
        assertTrue(fixture.observer.health.isEmpty())
        assertEquals(1, fixture.tun.closeCalls.get())
        assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
    }

    @Test
    fun `evidence rejection atomically seals before queued reader admission`() = runTest {
        listOf(false, true).forEach { throwFromObserver ->
            val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
            val fixture = PublicationFixture(this, dispatcher = dispatcher)
            val runtimeLock = fixture.installFairRuntimeLock()
            val evidenceEntered = CountDownLatch(1)
            val allowEvidenceReturn = CountDownLatch(1)
            val secondReadEntered = CountDownLatch(1)
            val allowSecondRead = CountDownLatch(1)
            val writerThread = AtomicReference<Thread>()
            val readerThread = AtomicReference<Thread>()
            val readCalls = AtomicInteger()
            var controllerOwnsLock = false
            var started = false

            try {
                fixture.observer.evidenceAccepted = false
                if (throwFromObserver) {
                    fixture.observer.evidenceFailure = IOException("evidence")
                }
                fixture.observer.onEvidence = {
                    writerThread.set(Thread.currentThread())
                    evidenceEntered.countDown()
                    assertTrue(allowEvidenceReturn.await(10, TimeUnit.SECONDS))
                }
                fixture.tun.onRead = {
                    if (readCalls.incrementAndGet() == 2) {
                        readerThread.set(Thread.currentThread())
                        secondReadEntered.countDown()
                        assertTrue(allowSecondRead.await(10, TimeUnit.SECONDS))
                    }
                }
                fixture.scriptWrites(TunWriteAttempt.Written)
                fixture.enqueue(fixture.publication())
                fixture.enqueue(fixture.publication())
                fixture.start()
                started = true

                assertTrue(evidenceEntered.await(10, TimeUnit.SECONDS))
                assertTrue(secondReadEntered.await(10, TimeUnit.SECONDS))
                runtimeLock.lock()
                controllerOwnsLock = true

                allowEvidenceReturn.countDown()
                awaitQueued(runtimeLock, checkNotNull(writerThread.get()))
                allowSecondRead.countDown()
                awaitQueued(runtimeLock, checkNotNull(readerThread.get()))

                runtimeLock.unlock()
                controllerOwnsLock = false
                fixture.runtime.join()

                assertEquals(1, fixture.plannedPacketCount())
                fixture.assertAttempts(RESPONSE_PACKET)
                fixture.assertWritten(RESPONSE_PACKET)
                assertEquals(1, fixture.observer.evidence.size)
                assertTrue(fixture.observer.health.isEmpty())
                assertEquals(1, fixture.tun.closeCalls.get())
                assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
            } finally {
                allowEvidenceReturn.countDown()
                allowSecondRead.countDown()
                if (controllerOwnsLock) runtimeLock.unlock()
                if (started) {
                    fixture.runtime.requestClose()
                    runBlocking { fixture.runtime.join() }
                }
                dispatcher.close()
            }
        }
    }

    @Test
    fun `zero and partial short writes are terminal without retry or evidence`() = runTest {
        listOf(0, RESPONSE_PACKET.size - 1).forEach { actualByteCount ->
            val fixture = PublicationFixture(this)
            val publication = fixture.publication()
            fixture.scriptWrites(TunWriteAttempt.ShortWrite(actualByteCount))

            fixture.start()
            fixture.enqueue(publication)
            runCurrent()
            fixture.runtime.join()

            fixture.assertAttempts(publication.responsePacket)
            assertTrue(fixture.tun.recordedWrites.isEmpty())
            assertTrue(fixture.observer.evidence.isEmpty())
            fixture.assertWriteHealth(TunWriteFailureReason.WRITE_FAILED)
        }
    }

    @Test
    fun `closed and failed write attempts seal once with exact health mapping`() = runTest {
        listOf(
            TunWriteAttempt.Closed to TunWriteFailureReason.DESCRIPTOR_CLOSED,
            TunWriteAttempt.Failed(IOException("write")) to
                TunWriteFailureReason.WRITE_FAILED,
        ).forEach { (attempt, expectedReason) ->
            val fixture = PublicationFixture(this)
            val publication = fixture.publication()
            fixture.scriptWrites(attempt)

            fixture.start()
            fixture.enqueue(publication)
            runCurrent()
            fixture.runtime.join()

            fixture.assertAttempts(publication.responsePacket)
            assertTrue(fixture.tun.recordedWrites.isEmpty())
            assertTrue(fixture.observer.evidence.isEmpty())
            fixture.assertWriteHealth(expectedReason)
        }
    }

    @Test
    fun `closed and failed readiness seal once with exact health mapping`() = runTest {
        listOf(
            TunWritable.Closed to TunWriteFailureReason.DESCRIPTOR_CLOSED,
            TunWritable.Failed(IOException("readiness")) to
                TunWriteFailureReason.WRITE_FAILED,
        ).forEach { (readiness, expectedReason) ->
            val fixture = PublicationFixture(this)
            val publication = fixture.publication()
            fixture.scriptWrites(TunWriteAttempt.WouldBlock)
            fixture.scriptWritable(readiness)

            fixture.start()
            fixture.enqueue(publication)
            runCurrent()
            fixture.runtime.join()

            fixture.assertAttempts(publication.responsePacket)
            assertTrue(fixture.tun.recordedWrites.isEmpty())
            assertTrue(fixture.observer.evidence.isEmpty())
            fixture.assertWriteHealth(expectedReason)
        }
    }

    @Test
    fun `closed caused by a local close request is silent`() = runTest {
        val fixture = PublicationFixture(this)
        val publication = fixture.publication()
        fixture.scriptWrites(TunWriteAttempt.Closed)
        fixture.tun.onWrite = fixture.runtime::requestClose

        fixture.start()
        fixture.enqueue(publication)
        runCurrent()
        fixture.runtime.join()

        fixture.assertAttempts(publication.responsePacket)
        assertTrue(fixture.tun.recordedWrites.isEmpty())
        assertTrue(fixture.observer.evidence.isEmpty())
        assertTrue(fixture.observer.health.isEmpty())
        assertTrue(fixture.observer.failures.isEmpty())
    }

    @Test
    fun `reentrant close defers every terminal lifecycle effect beyond writer fence`() = runTest {
        val fixture = PublicationFixture(this)
        val runtimeLock = fixture.installFairRuntimeLock()
        val lockObservations = ConcurrentLinkedQueue<Pair<String, Boolean>>()
        val completedJobs = ConcurrentLinkedQueue<String>()
        val lifecycleEscapedWriteCallback = AtomicBoolean()
        val publication = fixture.publication()

        fixture.tun.onClose = {
            lockObservations += "packet-close" to runtimeLock.isHeldByCurrentThread
        }
        fixture.onEngineClose {
            lockObservations += "engine-close" to runtimeLock.isHeldByCurrentThread
        }
        fixture.observer.onHealth = {
            lockObservations += "observer-health" to runtimeLock.isHeldByCurrentThread
        }
        fixture.observer.onFailure = {
            lockObservations += "observer-failure" to runtimeLock.isHeldByCurrentThread
        }
        fixture.scriptWrites(TunWriteAttempt.Closed)
        fixture.start()
        fixture.runtime.observeJobCompletionForTest { identity ->
            completedJobs += identity
            lockObservations +=
                "job-complete-$identity" to runtimeLock.isHeldByCurrentThread
        }
        val expectedJobs = fixture.runtime.jobTopologyForTest().childIdentities.toSet()
        fixture.tun.onWrite = {
            fixture.runtime.requestClose()
            lifecycleEscapedWriteCallback.set(
                fixture.tun.closeCalls.get() != 0 ||
                    fixture.engineCloseCalls() != 0 ||
                    completedJobs.isNotEmpty(),
            )
        }

        fixture.enqueue(publication)
        runCurrent()
        fixture.runtime.join()

        assertFalse(lifecycleEscapedWriteCallback.get())
        assertEquals(expectedJobs, completedJobs.toSet())
        assertTrue(lockObservations.any { it.first == "packet-close" })
        assertTrue(lockObservations.any { it.first == "engine-close" })
        assertTrue(lockObservations.none { it.second })
        assertTrue(fixture.observer.health.isEmpty())
        assertTrue(fixture.observer.failures.isEmpty())
    }

    @Test
    fun `reentrant close after full write commits evidence silently beyond writer fence`() =
        runTest {
            val fixture = PublicationFixture(this)
            val runtimeLock = fixture.installFairRuntimeLock()
            val publication = fixture.publication()
            val evidenceSawLock = AtomicReference<Boolean>()
            fixture.observer.onEvidence = {
                evidenceSawLock.set(runtimeLock.isHeldByCurrentThread)
            }
            fixture.scriptWrites(TunWriteAttempt.Written)
            fixture.tun.onWrite = fixture.runtime::requestClose

            fixture.start()
            fixture.enqueue(publication)
            runCurrent()
            fixture.runtime.join()

            fixture.assertAttempts(publication.responsePacket)
            fixture.assertWritten(publication.responsePacket)
            fixture.assertEvidence(publication)
            assertEquals(false, evidenceSawLock.get())
            assertTrue(fixture.observer.health.isEmpty())
            assertTrue(fixture.observer.failures.isEmpty())
            assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
        }

    @Test
    fun `write failure observer effect remains outside writer fence`() = runTest {
        val fixture = PublicationFixture(this)
        val runtimeLock = fixture.installFairRuntimeLock()
        val lockObservations = ConcurrentLinkedQueue<Boolean>()
        fixture.observer.onHealth = {
            lockObservations += runtimeLock.isHeldByCurrentThread
        }
        fixture.scriptWrites(TunWriteAttempt.Failed(IOException("write")))

        fixture.start()
        fixture.enqueue(fixture.publication())
        runCurrent()
        fixture.runtime.join()

        assertEquals(listOf(false), lockObservations.toList())
        fixture.assertWriteHealth(TunWriteFailureReason.WRITE_FAILED)
    }

    @Test
    fun `engine fence rejection wakes admission after releasing live capacity`() = runTest {
        val fixture = PublicationFixture(
            scope = this,
            capacities = TunRuntimeCapacities(
                activeResolutions = 1,
                waitingResolutions = 1,
                materializedWrites = 1,
                liveRecords = 4,
            ),
        )
        fixture.start()
        fixture.rejectEngineFence()
        repeat(5) {
            fixture.enqueue(fixture.publication())
        }

        runCurrent()

        assertEquals(5, fixture.plannedPacketCount())
        assertEquals(0, fixture.runtime.snapshotForTest().liveRecords)
        assertTrue(fixture.tun.attemptedWrites.isEmpty())
        assertTrue(fixture.observer.evidence.isEmpty())

        fixture.close()
    }

    @Test
    fun `queued close cannot suppress short or failed write health`() = runTest {
        listOf(
            TunWriteAttempt.ShortWrite(RESPONSE_PACKET.size - 1),
            TunWriteAttempt.Failed(IOException("write")),
        ).forEach { outcome ->
            val fixture = PublicationFixture(this)
            val runtimeLock = fixture.installFairRuntimeLock()
            val closeFinished = CountDownLatch(1)
            lateinit var closeThread: Thread
            fixture.tun.onWrite = {
                closeThread = Thread {
                    try {
                        fixture.runtime.requestClose()
                    } finally {
                        closeFinished.countDown()
                    }
                }
                closeThread.start()
                awaitQueued(runtimeLock, closeThread)
            }
            fixture.scriptWrites(outcome)

            fixture.start()
            fixture.enqueue(fixture.publication())
            runCurrent()
            closeFinished.await()
            closeThread.join()
            fixture.runtime.join()

            fixture.assertAttempts(RESPONSE_PACKET)
            fixture.assertWriteHealth(TunWriteFailureReason.WRITE_FAILED)
            assertEquals(1, fixture.tun.closeCalls.get())
            assertEquals(1, fixture.engineCloseCalls())
        }
    }

    private class PublicationFixture(
        scope: TestScope,
        afterPublicationCallback: (() -> Unit)? = null,
        capacities: TunRuntimeCapacities = TunRuntimeCapacities.Production,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
    ) {
        val tun = ScriptedTunPacketSession()
        val observer = RecordingTunRuntimeObserver()
        val runtime = BoundedTunRuntime(
            sessionToken = SESSION_TOKEN,
            packetSession = tun,
            observer = observer,
            dispatcher = dispatcher,
            capacities = capacities,
        )

        private lateinit var nextPublication: ProxyPublication
        private val networkContext = testNetworkContext()
        private val resolverPlan = testResolverPlan(networkContext)
        private val policyIdentity = testPolicyIdentity()
        private val proxy = TestRuntimeDnsProxy(
            planner = { packet, readyEngine ->
                ProxyPlan.Immediate(
                    result = ProxyResult.Pending(
                        fence = readyEngine,
                        publication = nextPublication,
                        sink = runtime.publicationSink,
                    ),
                    replacement = ProxyReplacementResponse(packet),
                )
            },
            unavailablePlanner = { _, _ -> ProxyPlan.Drop },
            executor = { ProxyResult.NotPublishable },
            overloadRejector = { ProxyResult.NotPublishable },
        )
        private val delegateEngine = TestReadyDnsEngine(
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
        private val engine: ReadyDnsEngine = if (afterPublicationCallback == null) {
            delegateEngine
        } else {
            CompletionObservedEngine(delegateEngine, afterPublicationCallback)
        }

        fun start() {
            runtime.start(engine)
        }

        fun rejectEngineFence() {
            delegateEngine.current = false
        }

        fun enqueue(publication: ProxyPublication) {
            nextPublication = publication
            tun.enqueuePacket(QUERY_PACKET)
        }

        fun plannedPacketCount(): Int = proxy.plannedPackets.size

        fun engineCloseCalls(): Int = delegateEngine.closeCalls

        fun onEngineClose(callback: () -> Unit) {
            delegateEngine.onClose = callback
        }

        fun installFairRuntimeLock(): ReentrantLock {
            val fairLock = ReentrantLock(true)
            BoundedTunRuntime::class.java.getDeclaredField("lock").apply {
                isAccessible = true
                set(runtime, fairLock)
            }
            return fairLock
        }

        fun scriptWrites(vararg attempts: TunWriteAttempt) {
            attempts.forEach { check(tun.writeResults.trySend(it).isSuccess) }
        }

        fun scriptWritable(vararg readiness: TunWritable) {
            readiness.forEach { check(tun.writableResults.trySend(it).isSuccess) }
        }

        fun publication() = ProxyPublication(
            responsePacket = RESPONSE_PACKET,
            activity = ACTIVITY,
            healthEvents = HEALTH_EVENTS,
            resolverQueryEvidence = RESOLVER_EVIDENCE,
            primaryAnswerBearing = true,
        )

        fun assertAttempts(vararg expected: ByteArray) {
            assertEquals(expected.size, tun.attemptedWrites.size)
            expected.zip(tun.attemptedWrites).forEach { (want, actual) ->
                assertArrayEquals(want, actual)
            }
        }

        fun assertWritten(vararg expected: ByteArray) {
            assertEquals(expected.size, tun.recordedWrites.size)
            expected.zip(tun.recordedWrites).forEach { (want, actual) ->
                assertArrayEquals(want, actual)
            }
        }

        fun assertEvidence(publication: ProxyPublication) {
            assertEquals(1, observer.evidence.size)
            val evidence = observer.evidence.single()
            assertSame(publication.activity, evidence.activity)
            assertEquals(publication.healthEvents, evidence.healthEvents)
            assertSame(publication.resolverQueryEvidence, evidence.resolverQueryEvidence)
            assertTrue(evidence.primaryAnswerBearing)
        }

        fun assertWriteHealth(reason: TunWriteFailureReason) {
            assertEquals(
                listOf(DnsHealthEvent.TunWriteFailed(reason)),
                observer.health,
            )
        }

        suspend fun close() {
            runtime.closeAndJoin()
        }
    }

    private class CompletionObservedEngine(
        private val delegate: ReadyDnsEngine,
        private val afterCompletion: () -> Unit,
    ) : ReadyDnsEngine by delegate {
        override fun completeIfCurrent(completion: () -> Boolean): Boolean =
            delegate.completeIfCurrent {
                completion().also { afterCompletion() }
            }
    }

    private companion object {
        fun awaitQueued(lock: ReentrantLock, thread: Thread) {
            var attemptsRemaining = 100_000
            while (!lock.hasQueuedThread(thread) && attemptsRemaining-- > 0) {
                Thread.yield()
            }
            assertTrue(
                "close did not queue on the writer fence",
                lock.hasQueuedThread(thread),
            )
        }

        const val SESSION_TOKEN = 23L
        val QUERY_PACKET = byteArrayOf(1, 2, 3)
        val RESPONSE_PACKET = byteArrayOf(9, 8, 7, 6)
        val ACTIVITY = DnsDecisionEvent(
            id = 91L,
            timestampMillis = TEST_NOW_MILLIS,
            normalizedDomain = "example.com",
            decision = FilterDecision.DEFAULT_ALLOW,
            resolver = ResolverKind.DEVICE,
            latencyMillis = 12L,
        )
        val HEALTH_EVENTS = listOf(
            DnsHealthEvent.ResolverAttemptFailed(
                resolver = ResolverKind.DEVICE,
                reason = ResolverFailureReason.TIMEOUT,
            ),
            DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE),
        )
        val RESOLVER_EVIDENCE =
            ResolverQueryHealthEvidence.primaryAccepted(queryId = 91L, latencyMillis = 12L)
    }
}

private fun TunSchedulerSnapshot.singleState(): String =
    states.values.single()
