package com.lavasec.app.vpn

import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ConfigurationIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSocketRegistryTest {
    @Test
    fun `registration consumes accepted rejected and throwing resources`() {
        val current = epoch()
        val registry = GenerationSocketRegistry(current)
        val accepted = Resource()
        assertTrue(registry.registerIfCurrent(accepted, current))
        assertTrue(registry.unregister(accepted))

        val stale = Resource()
        assertFalse(registry.registerIfCurrent(stale, current.copy(engineGeneration = 0 + 2)))
        assertEquals(1, stale.closed)

        val hashThrowing = Resource(throwHashCode = true)
        assertTrue(registry.registerIfCurrent(hashThrowing, current))
        assertTrue(registry.unregister(hashThrowing))
        assertEquals(0, hashThrowing.closed)
    }

    @Test
    fun `interrupted successor registration throws and consumes its resource`() {
        val old = epoch()
        val next = old.copy(engineGeneration = 2)
        val registry = GenerationSocketRegistry(old)
        val closeStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val advance = Thread {
            registry.advanceTo(next)
        }
        val resource = Resource()
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>()
        val registration = Thread {
            started.countDown()
            try {
                registry.registerIfCurrent(resource, next)
            } catch (error: Throwable) {
                thrown.set(error)
            } finally {
                done.countDown()
            }
        }
        try {
            assertTrue(registry.registerIfCurrent(Resource(onClose = { closeStarted.countDown(); release.await() }), old))
            advance.start()
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            registration.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse(done.await(100, TimeUnit.MILLISECONDS))
            registration.interrupt()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(thrown.get() is InterruptedException)
            assertEquals(1, resource.closed)
        } finally {
            release.countDown()
            registration.interrupt()
            registration.join(2_000)
            advance.join(2_000)
        }
    }

    @Test
    fun `advance drains old work before admitting successor and close permanently rejects`() {
        val old = epoch()
        val next = old.copy(networkGeneration = 2, engineGeneration = 2)
        val registry = GenerationSocketRegistry(old)
        val closeStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldResource = Resource(onClose = { closeStarted.countDown(); release.await(2, TimeUnit.SECONDS) })
        assertTrue(registry.registerIfCurrent(oldResource, old))
        Thread { registry.advanceTo(next) }.start()
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
        val admitted = AtomicBoolean(false)
        val successor = Resource()
        val registrationDone = CountDownLatch(1)
        Thread { admitted.set(registry.registerIfCurrent(successor, next)); registrationDone.countDown() }.start()
        assertFalse(registrationDone.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(registrationDone.await(2, TimeUnit.SECONDS))
        assertTrue(admitted.get())
        registry.close()
        assertEquals(1, oldResource.closed)
        assertEquals(1, successor.closed)
        val late = Resource()
        assertFalse(registry.registerIfCurrent(late, next))
        assertEquals(1, late.closed)
    }

    @Test
    fun `network handoff with reset engine drains old resources and admits successor`() {
        val old = epoch().copy(networkGeneration = 7, engineGeneration = 5)
        val next = old.copy(networkGeneration = 8, engineGeneration = 1)
        val registry = GenerationSocketRegistry(old)
        val oldResource = Resource()
        val successor = Resource()
        assertTrue(registry.registerIfCurrent(oldResource, old))

        registry.advanceTo(next)

        assertEquals(1, oldResource.closed)
        assertFalse(registry.isCurrent(old))
        assertTrue(registry.isCurrent(next))
        assertTrue(registry.registerIfCurrent(successor, next))
        registry.close()
        assertEquals(1, successor.closed)
    }

    @Test
    fun `new session with reset subordinate generations advances`() {
        val old = epoch().copy(
            sessionGeneration = 3,
            networkGeneration = 7,
            engineGeneration = 5,
        )
        val next = old.copy(
            sessionGeneration = 4,
            networkGeneration = 1,
            engineGeneration = 1,
        )
        val registry = GenerationSocketRegistry(old)
        val oldResource = Resource()
        assertTrue(registry.registerIfCurrent(oldResource, old))

        registry.advanceTo(next)

        assertEquals(1, oldResource.closed)
        assertTrue(registry.isCurrent(next))
    }

    @Test
    fun `older network cannot replace current epoch with a higher engine counter`() {
        val current = epoch().copy(networkGeneration = 8, engineGeneration = 1)
        val stale = current.copy(networkGeneration = 7, engineGeneration = 99)
        val registry = GenerationSocketRegistry(current)
        val currentResource = Resource()
        assertTrue(registry.registerIfCurrent(currentResource, current))

        registry.advanceTo(stale)

        assertTrue(registry.isCurrent(current))
        assertFalse(registry.isCurrent(stale))
        assertEquals(0, currentResource.closed)
        registry.close()
        assertEquals(1, currentResource.closed)
    }

    @Test
    fun `stale registration rejects immediately while successor registration remains fenced`() {
        val old = epoch()
        val next = old.copy(engineGeneration = 2)
        val registry = GenerationSocketRegistry(old)
        val closeStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val advance = Thread { registry.advanceTo(next) }
        val stale = Resource()
        val staleStarted = CountDownLatch(1)
        val staleDone = CountDownLatch(1)
        val staleAccepted = AtomicBoolean(true)
        val staleRegistration = Thread {
            staleStarted.countDown()
            staleAccepted.set(registry.registerIfCurrent(stale, old))
            staleDone.countDown()
        }
        val successorStarted = CountDownLatch(1)
        val successorReturned = CountDownLatch(1)
        val successorResource = Resource()
        val successorAccepted = AtomicReference<Boolean?>()
        val successorFailure = AtomicReference<Throwable?>()
        val successor = Thread {
            successorStarted.countDown()
            try {
                successorAccepted.set(registry.registerIfCurrent(successorResource, next))
            } catch (error: Throwable) {
                successorFailure.set(error)
            } finally {
                successorReturned.countDown()
            }
        }
        try {
            assertTrue(registry.registerIfCurrent(Resource(onClose = { closeStarted.countDown(); release.await() }), old))
            advance.start()
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            staleRegistration.start()
            assertTrue(staleStarted.await(2, TimeUnit.SECONDS))
            assertTrue(staleDone.await(2, TimeUnit.SECONDS))
            assertFalse(staleAccepted.get())
            assertEquals(1, stale.closed)
            successor.start()
            assertTrue(successorStarted.await(2, TimeUnit.SECONDS))
            assertFalse(successorReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            staleRegistration.interrupt()
            successor.interrupt()
            staleRegistration.join(2_000)
            successor.join(2_000)
            advance.join(2_000)
            registry.close()
            assertFalse(staleRegistration.isAlive)
            assertFalse(successor.isAlive)
            assertFalse(advance.isAlive)
            assertTrue(
                successorAccepted.get() != null || successorFailure.get() is InterruptedException,
            )
            assertEquals(1, successorResource.closed)
            assertEquals(0, registry.sizeForTesting)
        }
    }

    @Test
    fun `distinct value equal resources retain independent ownership`() {
        val registry = GenerationSocketRegistry(epoch())
        val first = ValueEqualResource()
        val second = ValueEqualResource()
        assertTrue(registry.registerIfCurrent(first, epoch()))
        assertTrue(registry.registerIfCurrent(second, epoch()))
        assertFalse(registry.unregister(ValueEqualResource()))
        assertTrue(registry.unregister(first))
        registry.close()
        assertEquals(0, first.closed)
        assertEquals(1, second.closed)
    }

    @Test
    fun `failed completion drain fences epoch advance and successor admission`() {
        val old = epoch()
        val next = old.copy(engineGeneration = 2)
        val registry = GenerationSocketRegistry(old)
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val resource = Resource(
            onClose = {
                closeStarted.countDown()
                releaseClose.await()
            },
        )
        val completionReturned = CountDownLatch(1)
        val completionResult = AtomicReference<Boolean?>()
        val completion = Thread {
            completionResult.set(registry.completeIfCurrent(resource, old) { false })
            completionReturned.countDown()
        }
        val advanceReturned = CountDownLatch(1)
        val advance = Thread {
            registry.advanceTo(next)
            advanceReturned.countDown()
        }
        val successor = Resource()
        val successorReturned = CountDownLatch(1)
        val successorAccepted = AtomicBoolean(false)
        val registration = Thread {
            successorAccepted.set(registry.registerIfCurrent(successor, next))
            successorReturned.countDown()
        }

        try {
            assertTrue(registry.registerIfCurrent(resource, old))
            completion.start()
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))

            advance.start()
            awaitConditionWaitOrReturn(advance, advanceReturned, "epoch advance")
            assertEquals("advance returned before failed close drained", 1L, advanceReturned.count)

            registration.start()
            awaitConditionWaitOrReturn(
                registration,
                successorReturned,
                "successor registration",
            )
            assertEquals(
                "successor registration returned before failed close drained",
                1L,
                successorReturned.count,
            )

            releaseClose.countDown()
            completion.join(2_000)
            advance.join(2_000)
            registration.join(2_000)

            assertFalse(completion.isAlive)
            assertFalse(advance.isAlive)
            assertFalse(registration.isAlive)
            assertEquals(false, completionResult.get())
            assertTrue(successorAccepted.get())
            assertEquals(1, resource.closed)
            registry.close()
            assertEquals(1, successor.closed)
            assertEquals(0, registry.sizeForTesting)
        } finally {
            releaseClose.countDown()
            completion.interrupt()
            registration.interrupt()
            completion.join(2_000)
            advance.join(2_000)
            registration.join(2_000)
            registry.close()
        }
    }

    @Test
    fun `unregister advance and close races leave no resource and one physical close`() {
        val current = epoch()
        val registry = GenerationSocketRegistry(current)
        val resource = Resource()
        assertTrue(registry.registerIfCurrent(resource, current))
        val start = CountDownLatch(1)
        val done = CountDownLatch(3)
        listOf(
            { registry.unregister(resource) },
            { registry.advanceTo(current.copy(engineGeneration = 2)) },
            { registry.close() },
        ).forEach { action -> Thread { start.await(); action(); done.countDown() }.start() }
        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(resource.closed in 0..1)
        if (resource.closed == 0) resource.close()
        assertEquals(1, resource.closed)
        assertEquals(0, registry.sizeForTesting)
    }

    private fun awaitConditionWaitOrReturn(
        thread: Thread,
        returned: CountDownLatch,
        label: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            returned.count != 0L &&
            thread.state != Thread.State.WAITING &&
            System.nanoTime() < deadline
        ) {
            Thread.onSpinWait()
        }
        assertTrue(
            "$label neither entered the registry condition wait nor returned",
            returned.count == 0L || thread.state == Thread.State.WAITING,
        )
    }

    private fun epoch() = EngineEpoch(1, ConfigurationIdentity(1, "cfg"), "artifact", 1, 1, 1,
        PrimaryResolverIdentity("device-dns"), ResolverFallbackCoverage.NONE)

    private class Resource(
        private val throwHashCode: Boolean = false,
        private val onClose: () -> Unit = {},
    ) : AutoCloseable {
        var closed = 0
        override fun hashCode(): Int = if (throwHashCode) throw IllegalStateException("hash") else System.identityHashCode(this)
        override fun close() { closed++; onClose() }
    }

    private class ValueEqualResource : AutoCloseable {
        var closed = 0
        override fun equals(other: Any?): Boolean = other is ValueEqualResource
        override fun hashCode(): Int = 7
        override fun close() { closed++ }
    }
}
