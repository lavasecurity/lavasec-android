package com.lavasec.app.vpn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one serial engine confinement that replaces Android's per-window ownership fences:
 * operations run one at a time, nesting reuses the enclosing token, `isCurrent` reports the queue
 * itself, and a supersede/close makes every in-flight operation report itself no longer current.
 */
class ChainedEngineQueueTest {

    private fun engineQueue(): Pair<ChainedEngineQueue, ExecutorService> {
        val executor = Executors.newSingleThreadExecutor()
        return ChainedEngineQueue(executor) to executor
    }

    @Test
    fun `run returns the block value and runs it once`() {
        val (engineQueue, executor) = engineQueue()
        try {
            val runs = AtomicInteger()
            val result = engineQueue.run { token ->
                runs.incrementAndGet()
                assertTrue(engineQueue.isEstablishAllowed(token))
                "value"
            }
            assertEquals("value", result)
            assertEquals(1, runs.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a nested run reuses the enclosing token`() {
        val (engineQueue, executor) = engineQueue()
        try {
            var innerToken = -1L
            val outerToken = engineQueue.run { outer ->
                innerToken = engineQueue.run { inner -> inner }
                outer
            }
            assertEquals(outerToken, innerToken)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `isCurrent reports whether the caller is on the queue`() {
        val (engineQueue, executor) = engineQueue()
        try {
            // Off the queue.
            assertFalse(engineQueue.isCurrent)
            var sawCurrentInside = false
            engineQueue.run {
                // On the queue, in the operation the block is running.
                sawCurrentInside = engineQueue.isCurrent
            }
            assertTrue(sawCurrentInside)
            assertFalse(engineQueue.isCurrent)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `runSerial runs inline on the queue and never mints a lifecycle operation`() {
        val (engineQueue, executor) = engineQueue()
        try {
            val before = engineQueue.currentGeneration()
            var generationDuringOuter = -1L
            engineQueue.run { outer ->
                generationDuringOuter = engineQueue.currentGeneration()
                engineQueue.runSerial {
                    // Inline on the same serial owner, with no new operation token.
                    assertTrue(engineQueue.isCurrent)
                }
                outer
            }
            // The enclosing `run` minted exactly one operation; `runSerial` minted none.
            assertEquals(before + 1L, engineQueue.currentGeneration())
            assertEquals(before + 1L, generationDuringOuter)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `off-queue runSerial runs engine work without invalidating an in-flight operation`() {
        val (engineQueue, executor) = engineQueue()
        try {
            val token = engineQueue.run { it }
            val result = engineQueue.runSerial {
                // Engine work stays on the same operation; it must not supersede the establish.
                assertTrue(engineQueue.isEstablishAllowed(token))
                42
            }
            assertEquals(42, result)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `supersede invalidates the operation that captured the old token`() {
        val (engineQueue, executor) = engineQueue()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val allowedBefore = AtomicBoolean(false)
            val allowedAfter = AtomicBoolean(true)
            val worker = thread(name = "operation") {
                engineQueue.run { token ->
                    allowedBefore.set(engineQueue.isEstablishAllowed(token))
                    entered.countDown()
                    release.await()
                    allowedAfter.set(engineQueue.isEstablishAllowed(token))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            engineQueue.supersede()
            release.countDown()
            worker.join(5_000)

            assertTrue(allowedBefore.get())
            assertFalse("a superseded operation must not establish", allowedAfter.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `only one operation runs at a time`() {
        val (engineQueue, executor) = engineQueue()
        try {
            val concurrent = AtomicInteger()
            val maximum = AtomicInteger()
            val workers = (1..4).map {
                thread(name = "operation-$it") {
                    engineQueue.run {
                        maximum.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
                        Thread.sleep(20L)
                        concurrent.decrementAndGet()
                    }
                }
            }
            workers.forEach { it.join(5_000) }

            assertEquals(1, maximum.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `close latches and invalidates in-flight operations`() {
        val (engineQueue, executor) = engineQueue()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val allowedAfter = AtomicBoolean(true)
            val worker = thread(name = "operation") {
                engineQueue.run { token ->
                    entered.countDown()
                    release.await()
                    allowedAfter.set(engineQueue.isEstablishAllowed(token))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            engineQueue.close()
            assertTrue(engineQueue.isClosed)
            release.countDown()
            worker.join(5_000)

            assertFalse(allowedAfter.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `close during a non-cancellable allocation prevents the stale establish`() {
        val (engineQueue, executor) = engineQueue()
        try {
            val allocationEntered = CountDownLatch(1)
            val releaseAllocation = CountDownLatch(1)
            val claimedInterface = AtomicBoolean(false)
            // The operation stands in for a start: it prepares resources (the blocking part), then
            // checks the gate and would claim the interface. The allocation is non-cancellable, so
            // the stop cannot interrupt it — only the generation gate can refuse the claim.
            val operation = thread(name = "establish") {
                engineQueue.run { token ->
                    allocationEntered.countDown()
                    releaseAllocation.await()
                    if (engineQueue.isEstablishAllowed(token)) {
                        claimedInterface.set(true)
                    }
                }
            }
            assertTrue(allocationEntered.await(5, TimeUnit.SECONDS))

            // The service thread stops protection while the allocation is in flight. It must latch
            // and supersede without waiting for the allocation to finish.
            engineQueue.close()
            releaseAllocation.countDown()
            operation.join(5_000)

            assertFalse("a stopped operation must not claim the interface", claimedInterface.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `enqueue runs confined work without blocking the caller`() {
        val (engineQueue, executor) = engineQueue()
        try {
            val done = CountDownLatch(1)
            engineQueue.enqueue { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a superseded in-flight claim is retained so the successor replaces it without a gap`() {
        val (engineQueue, executor) = engineQueue()
        val claim = FakeClaim()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val outcome = AtomicReference<ChainedClaimOutcome?>(null)
            // Stands in for a start: the blocking part is the non-cancellable establish, which
            // claims the device interface before resolving the claim with the queue.
            val worker = thread(name = "establish") {
                engineQueue.run { token ->
                    entered.countDown()
                    release.await()
                    outcome.set(engineQueue.resolveRouteClaim(token, claim))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // A newer Start/Reload supersedes the operation while its establish is in flight.
            engineQueue.supersede()
            release.countDown()
            worker.join(5_000)

            assertEquals(ChainedClaimOutcome.RETAINED, outcome.get())
            // The claimed interface must stay up: closing it here would leave no tunnel until the
            // successor establishes (the bypass the retention exists to prevent).
            assertEquals(0, claim.closes.get())

            // The successor establishes and installs its own claim; only then is the retained
            // predecessor released.
            val successorClaim = FakeClaim()
            val successorOutcome = engineQueue.run { token ->
                engineQueue.resolveRouteClaim(token, successorClaim)
            }
            assertEquals(ChainedClaimOutcome.INSTALLED, successorOutcome)
            assertEquals("the successor's establish releases the retained predecessor", 1, claim.closes.get())
            assertEquals(0, successorClaim.closes.get())
        } finally {
            executor.shutdownNow()
            engineQueue.close()
        }
    }

    @Test
    fun `a superseded claim is never installed and a closed queue refuses one`() {
        val (engineQueue, executor) = engineQueue()
        val claim = FakeClaim()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val outcome = AtomicReference<ChainedClaimOutcome?>(null)
            val worker = thread(name = "establish") {
                engineQueue.run { token ->
                    entered.countDown()
                    release.await()
                    outcome.set(engineQueue.resolveRouteClaim(token, claim))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            engineQueue.supersede()
            release.countDown()
            worker.join(5_000)

            // A superseded claim is never reported INSTALLED, so the caller cannot register/start a
            // stale runtime; the queue retains the interface instead.
            assertEquals(ChainedClaimOutcome.RETAINED, outcome.get())

            engineQueue.close()
            val refused = FakeClaim()
            var refusedOutcome: ChainedClaimOutcome? = null
            engineQueue.run { token -> refusedOutcome = engineQueue.resolveRouteClaim(token, refused) }
            assertEquals(ChainedClaimOutcome.REFUSED, refusedOutcome)
            // The closed queue owns nothing, so the caller releases the refused claim.
            refused.close()
            assertEquals(1, refused.closes.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `close releases a retained claim`() {
        val (engineQueue, executor) = engineQueue()
        val claim = FakeClaim()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val outcome = AtomicReference<ChainedClaimOutcome?>(null)
            val worker = thread(name = "establish") {
                engineQueue.run { token ->
                    entered.countDown()
                    release.await()
                    outcome.set(engineQueue.resolveRouteClaim(token, claim))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            engineQueue.supersede()
            release.countDown()
            worker.join(5_000)
            assertEquals(ChainedClaimOutcome.RETAINED, outcome.get())

            engineQueue.close()
            assertEquals(1, claim.closes.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `retain keeps a late superseded claim alive until a successor installs`() {
        val (engineQueue, executor) = engineQueue()
        val late = FakeClaim()
        try {
            // The generation check at registration lost the race after the claim was already made;
            // the queue retains the interface instead of closing it.
            assertTrue(engineQueue.retainRouteClaim(late))
            assertEquals(0, late.closes.get())

            val successor = FakeClaim()
            val outcome = engineQueue.run { token ->
                engineQueue.resolveRouteClaim(token, successor)
            }
            assertEquals(ChainedClaimOutcome.INSTALLED, outcome)
            assertEquals(1, late.closes.get())
            assertEquals(0, successor.closes.get())
        } finally {
            executor.shutdownNow()
            engineQueue.close()
        }
    }
}

private class FakeClaim : AutoCloseable {
    val closes = AtomicInteger()
    override fun close() {
        closes.incrementAndGet()
    }
}
