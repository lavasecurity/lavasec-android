package com.lavasec.app.reactnative

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class NativeVisitHostRegistryTest {
    @Test fun `registry publishes the actual immutable witness`() {
        val registry = NativeVisitHostRegistry()
        val owner = Any()
        val host = NativeVisitHost(owner, "original") { true }
        assertNull(registry.current())
        assertTrue(registry.publish(host))
        assertSame(host, registry.current())
        assertTrue(host.matches(registry.current()))
    }

    @Test fun `retirement requires both exact owner reference and host identity`() {
        val registry = NativeVisitHostRegistry()
        val owner = Any()
        val host = NativeVisitHost(owner, "original") { true }
        registry.publish(host)
        assertFalse(registry.retire(Any(), "original"))
        assertFalse(registry.retire(owner, "different"))
        assertSame(host, registry.current())
        assertTrue(registry.retire(owner, "original"))
        assertNull(registry.current())
        assertFalse(registry.retire(owner, "original"))
    }

    @Test fun `late A retirement cannot clear a published B even with the same nonce`() {
        val registry = NativeVisitHostRegistry()
        val ownerA = Any()
        val hostA = NativeVisitHost(ownerA, "shared-fixture-id") { true }
        val hostB = NativeVisitHost(Any(), "shared-fixture-id") { true }
        val pending = CountDownLatch(1)
        val retire = CountDownLatch(1)
        val io = Executors.newSingleThreadExecutor()
        try {
            registry.publish(hostA)
            val oldRetirement = io.submit<Boolean> {
                pending.countDown()
                check(retire.await(5, TimeUnit.SECONDS))
                registry.retire(ownerA, hostA.nativeHostID)
            }
            assertTrue(pending.await(5, TimeUnit.SECONDS))
            registry.publish(hostB)
            retire.countDown()
            assertFalse(oldRetirement.get(5, TimeUnit.SECONDS))
            assertSame(hostB, registry.current())
            assertTrue(hostB.matches(registry.current()))
            assertFalse(hostA.retains(registry.current()))
        } finally {
            retire.countDown()
            io.shutdownNow()
        }
    }

    @Test fun `a dead original cannot republish over a successor`() {
        val registry = NativeVisitHostRegistry()
        val alive = AtomicBoolean(true)
        val original = NativeVisitHost(Any(), "A") { alive.get() }
        val successor = NativeVisitHost(Any(), "B") { true }
        registry.publish(original)
        alive.set(false)
        registry.retire(original.owner, original.nativeHostID)
        registry.publish(successor)
        assertFalse(registry.publish(original))
        assertSame(successor, registry.current())
    }

    @Test fun `atomic publication makes a successor visible to an already running IO reader`() {
        val registry = NativeVisitHostRegistry()
        val first = NativeVisitHost(Any(), "A") { true }
        val second = NativeVisitHost(Any(), "B") { true }
        val reading = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val io = Executors.newSingleThreadExecutor()
        try {
            registry.publish(first)
            val observed = io.submit<NativeVisitHost?> {
                assertSame(first, registry.current())
                reading.countDown()
                while (!stop.get()) {
                    val current = registry.current()
                    if (current !== first) return@submit current
                    Thread.yield()
                }
                null
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            // No latch or future releases the reader after this publication: the
            // registry itself supplies the cross-thread visibility contract.
            registry.publish(second)
            assertSame(second, observed.get(5, TimeUnit.SECONDS))
        } finally {
            stop.set(true)
            io.shutdownNow()
        }
    }
}
