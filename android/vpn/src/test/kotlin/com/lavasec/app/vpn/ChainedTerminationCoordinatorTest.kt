package com.lavasec.app.vpn

import com.lavasec.app.vpn.chained.ChainedOwnerSlot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal/reconnect serialization: a termination only acts when the owner that reported it is
 * still current once the engine queue is held.
 */
class ChainedTerminationCoordinatorTest {

    private fun coordinator(
        slot: ChainedOwnerSlot<String>,
        engineQueue: ChainedEngineQueue,
    ): ChainedTerminationCoordinator<String> =
        ChainedTerminationCoordinator(engineQueue, slot)

    @Test
    fun `a termination that wins the engine queue claims its owner and applies`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val slot = ChainedOwnerSlot<String>()
            slot.install("failing")

            var applied = false
            val result = coordinator(slot, ChainedEngineQueue(executor))
                .claimAndRun("failing", guard = { true }) { _ ->
                    applied = true
                    "done"
                }

            assertEquals("done", result)
            assertTrue(applied)
            assertNull(slot.currentOrNull())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a reconnect that replaced the owner makes the pending termination a no-op`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val slot = ChainedOwnerSlot<String>()
            val engineQueue = ChainedEngineQueue(executor)
            slot.install("failing")

            // A reconnect runs first, closes the failing owner and installs a replacement.
            engineQueue.run { _ ->
                slot.take()
                slot.install("replacement")
            }

            var applied = false
            val result = coordinator(slot, engineQueue).claimAndRun("failing", guard = { true }) { _ ->
                applied = true
                "done"
            }

            // The pending action is skipped and the replacement is left untouched.
            assertNull(result)
            assertFalse(applied)
            assertEquals("replacement", slot.currentOrNull())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a closed runtime is never claimed`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val slot = ChainedOwnerSlot<String>()
            slot.install("failing")

            var applied = false
            val result = coordinator(slot, ChainedEngineQueue(executor))
                .claimAndRun("failing", guard = { false }) { _ ->
                    applied = true
                    "done"
                }

            assertNull(result)
            assertFalse(applied)
            assertEquals("failing", slot.currentOrNull())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `an install callback cannot interleave between the claim check and its publication`() {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        try {
            val slot = ChainedOwnerSlot<String>()
            val engineQueue = ChainedEngineQueue(executor)
            slot.install("failing")
            val published = CopyOnWriteArrayList<String>()
            val dnsOnlyInstalled = AtomicBoolean(false)
            val checked = CountDownLatch(1)
            val release = CountDownLatch(1)

            // The terminal action claims the owner and, INSIDE the same engine queue, checks for a
            // live DNS-only runtime and publishes the clear.
            val termination = thread(name = "termination") {
                coordinator(slot, engineQueue).claimAndRun("failing", guard = { true }) { _ ->
                    val clear = slot.currentOrNull() == null && !dnsOnlyInstalled.get()
                    checked.countDown()
                    release.await()
                    if (clear) published += "NONE"
                }
            }
            assertTrue(checked.await(2, TimeUnit.SECONDS))

            // A network-availability callback installs and publishes the DNS-only fallback; it
            // runs as a new operation on the SAME engine queue, so it must wait rather than slip
            // between the check and the publish (which would overwrite the settled DNS_ONLY with a
            // stale NONE).
            val installed = CountDownLatch(1)
            val network = thread(name = "network") {
                engineQueue.run { _ ->
                    dnsOnlyInstalled.set(true)
                    published += "DNS_ONLY"
                    installed.countDown()
                }
            }
            assertFalse(installed.await(200, TimeUnit.MILLISECONDS))

            release.countDown()
            termination.join()
            network.join()

            assertEquals(listOf("NONE", "DNS_ONLY"), published)
        } finally {
            executor.shutdownNow()
        }
    }
}
