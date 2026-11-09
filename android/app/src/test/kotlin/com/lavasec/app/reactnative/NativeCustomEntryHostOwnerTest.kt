package com.lavasec.app.reactnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCustomEntryHostOwnerTest {
    @Test fun `host destruction retires both original DNS and blocklist capabilities once without React cleanup`() {
        val activity = Any(); var alive = true
        val host = NativeVisitHost(activity, "a") { alive }
        val registry = NativeVisitHostRegistry(); assertTrue(registry.publish(host))
        val retired = mutableListOf<String>()
        val custody = NativeCustomEntryHostOwner({ 7L }, registry::current)
        assertTrue(custody.register("dns-token", 7, host) { retired += "dns-token" })
        assertTrue(custody.register("blocklist-token", 7, host) { retired += "blocklist-token" })
        alive = false; assertTrue(registry.retire(activity, "a"))
        custody.retireHost(activity, "a")
        assertEquals(listOf("dns-token", "blocklist-token"), retired)
        custody.retireHost(activity, "a"); custody.close()
        assertEquals(listOf("dns-token", "blocklist-token"), retired)
    }

    @Test fun `wrong Activity or host nonce cannot retire live custom entry`() {
        val activity = Any(); val host = NativeVisitHost(activity, "a") { true }; var retired = 0
        val custody = NativeCustomEntryHostOwner({ 7L }, { host })
        assertTrue(custody.register("a-token", 7, host) { retired++ })
        custody.retireHost(Any(), "a"); custody.retireHost(activity, "other")
        assertEquals(0, retired)
        assertTrue(custody.retire("a-token", 7)); assertEquals(1, retired)
    }

    @Test fun `late old host destruction preserves current host and different successor token`() {
        val a = Any(); val b = Any(); val hostA = NativeVisitHost(a, "same-nonce") { true }
        val hostB = NativeVisitHost(b, "same-nonce") { true }; var current = hostA
        val retired = mutableListOf<String>(); val custody = NativeCustomEntryHostOwner({ 7L }, { current })
        assertTrue(custody.register("a-token", 7, hostA) { retired += "a-token" })
        current = hostB
        assertTrue(custody.register("b-token", 7, hostB) { retired += "b-token" })
        custody.retireHost(a, "same-nonce"); assertEquals(listOf("a-token"), retired)
        assertFalse(custody.retire("b-token", 6)); assertEquals(listOf("a-token"), retired)
        assertTrue(custody.retire("b-token", 7)); assertEquals(listOf("a-token", "b-token"), retired)
    }

    @Test fun `retirement uses captured witnesses without calling current graph or host ports`() {
        val activity = Any(); val host = NativeVisitHost(activity, "a") { true }; var forbidReads = false; var retired = 0
        val custody = NativeCustomEntryHostOwner(
            { check(!forbidReads); 7L }, { check(!forbidReads); host })
        assertTrue(custody.register("host-token", 7, host) { retired++ })
        assertTrue(custody.register("route-token", 7, host) { retired++ })
        assertTrue(custody.register("module-token", 7, host) { retired++ })
        forbidReads = true
        custody.retireHost(Any(), "a"); assertEquals(0, retired)
        assertTrue(custody.retire("route-token", 7)); assertEquals(1, retired)
        custody.retireHost(activity, "a"); assertEquals(3, retired)
        custody.close(); assertEquals(3, retired)
    }

    @Test fun `ordinary pause and a temporarily absent host do not abandon the custom form`() {
        val activity = Any(); val host = NativeVisitHost(activity, "a") { true }; var current: NativeVisitHost? = host; var retired = 0
        val custody = NativeCustomEntryHostOwner({ 7L }, { current })
        current = null
        assertTrue(custody.register("a-token", 7, host) { retired++ })
        current = host; assertEquals(0, retired)
        assertTrue(custody.retire("a-token", 7)); assertEquals(1, retired)
    }

    @Test fun `registration after original destruction or graph rotation compensates only its captured token`() {
        val activity = Any(); var alive = true; var generation = 7L
        val host = NativeVisitHost(activity, "a") { alive }; val retired = mutableListOf<String>()
        val custody = NativeCustomEntryHostOwner({ generation }, { host })
        alive = false
        assertFalse(custody.register("dead-token", 7, host) { retired += "dead-token" })
        alive = true; generation = 8
        assertFalse(custody.register("old-generation-token", 7, host) { retired += "old-generation-token" })
        assertTrue(custody.register("fresh-token", 8, host) { retired += "fresh-token" })
        assertEquals(listOf("dead-token", "old-generation-token"), retired)
        assertFalse(custody.retire("unknown-token", 8)); assertFalse(custody.retire("fresh-token", 7))
        assertTrue(custody.retire("fresh-token", 8))
        assertEquals(listOf("dead-token", "old-generation-token", "fresh-token"), retired)
    }

    @Test fun `module close disposes originals and late registration cannot reopen retired module`() {
        val host = NativeVisitHost(Any(), "a") { true }; val retired = mutableListOf<String>()
        val custody = NativeCustomEntryHostOwner({ 7L }, { host })
        assertTrue(custody.register("original", 7, host) { retired += "original" })
        custody.close(); custody.close()
        assertFalse(custody.register("late", 7, host) { retired += "late" })
        assertEquals(listOf("original", "late"), retired)
        assertFalse(custody.retire("original", 7))
    }

    @Test fun `a duplicate token cannot replace an existing registered native visit`() {
        val host = NativeVisitHost(Any(), "a") { true }; val retired = mutableListOf<String>()
        val custody = NativeCustomEntryHostOwner({ 7L }, { host })
        assertTrue(custody.register("same-token", 7, host) { retired += "original" })
        assertFalse(custody.register("same-token", 7, host) { retired += "replacement" })
        assertTrue(custody.retire("same-token", 7))
        assertEquals(listOf("original"), retired)
    }

    @Test fun `successful route cleanup releases custody before replacement and does not repeat at host death`() {
        val activity = Any(); val host = NativeVisitHost(activity, "a") { true }
        val retired = mutableListOf<String>(); val custody = NativeCustomEntryHostOwner({ 7L }, { host })
        repeat(32) { index ->
            val token = "completed-$index"
            assertTrue(custody.register(token, 7, host) { retired += token })
            assertTrue(custody.retire(token, 7))
            assertFalse(custody.retire(token, 7))
        }
        assertEquals(32, retired.size)
        assertTrue(custody.register("failed-save-retained", 7, host) { retired += "failed-save-retained" })
        custody.retireHost(activity, "a"); custody.close()
        assertEquals(33, retired.size); assertEquals("failed-save-retained", retired.last())
    }

    @Test fun `late original callback cannot remove a newly registered same token record`() {
        val host = NativeVisitHost(Any(), "a") { true }
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val old = Any(); val fresh = Any(); val record = java.util.concurrent.atomic.AtomicReference<Any?>(old)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val custody = NativeCustomEntryHostOwner({ 7L }, { host })
        assertTrue(custody.register("same-token", 7, host) {
            entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            record.compareAndSet(old, null)
        })
        val worker = Thread {
            try { assertTrue(custody.retire("same-token", 7)) } catch (caught: Throwable) { failure.set(caught) }
        }
        try {
            worker.start(); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            record.set(fresh)
            assertTrue(custody.register("same-token", 7, host) { record.compareAndSet(fresh, null) })
            release.countDown(); worker.join(5_000); assertFalse(worker.isAlive)
            failure.get()?.let { throw it }; assertTrue(record.get() === fresh)
            assertTrue(custody.retire("same-token", 7)); assertEquals(null, record.get())
        } finally { release.countDown(); worker.join(5_000); custody.close() }
    }

    @Test fun `module close serializes an in flight registration and retires its original exactly once`() {
        val host = NativeVisitHost(Any(), "a") { true }
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val retired = java.util.concurrent.atomic.AtomicInteger(); val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val custody = NativeCustomEntryHostOwner({
            entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); 7L
        }, { host })
        val registration = Thread {
            try { assertTrue(custody.register("original", 7, host) { retired.incrementAndGet() }) }
            catch (caught: Throwable) { failure.set(caught) }
        }
        val closing = Thread { try { custody.close() } catch (caught: Throwable) { failure.set(caught) } }
        try {
            registration.start(); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); closing.start()
            release.countDown(); registration.join(5_000); closing.join(5_000)
            assertFalse(registration.isAlive); assertFalse(closing.isAlive); failure.get()?.let { throw it }
            assertEquals(1, retired.get()); assertFalse(custody.retire("original", 7))
            assertFalse(custody.register("late", 7, host) { retired.incrementAndGet() }); assertEquals(2, retired.get())
        } finally { release.countDown(); registration.join(5_000); closing.join(5_000); custody.close() }
    }

    @Test fun `captured custom DNS admission refuses wrong or retired host and graph after authorization suspends`() {
        val activity = Any(); var alive = true
        val original = NativeVisitHost(activity, "original") { alive }
        var current: NativeVisitHost? = original; var generation: Long? = 7L; var retired = 0
        val custody = NativeCustomEntryHostOwner({ generation }, { current })
        assertTrue(custody.register("dns-token", 7, original) { retired++ })
        val admitted = checkNotNull(custody.captureAdmission("dns-token", 7, original))
        assertTrue(admitted())
        assertEquals(null, custody.captureAdmission("dns-token", 7, NativeVisitHost(Any(), "original") { true }))
        assertEquals(null, custody.captureAdmission("dns-token", 7, NativeVisitHost(activity, "other") { true }))
        assertEquals(null, custody.captureAdmission("dns-token", 8, original))
        assertEquals(null, custody.captureAdmission("unknown", 7, original))
        // Each real authority boundary must invalidate the already captured original receipt.
        current = NativeVisitHost(Any(), "replacement") { true }; assertFalse(admitted())
        assertEquals(null, custody.captureAdmission("dns-token", 7, original))
        current = null; assertFalse(admitted())
        current = original; generation = 8; assertFalse(admitted())
        generation = 7; alive = false; assertFalse(admitted())
        alive = true; assertTrue(admitted())
        custody.retireHost(activity, "original")
        assertEquals(1, retired); assertFalse(admitted()); custody.close()
        assertEquals(1, retired)
    }

    @Test fun `captured custom DNS admission cannot borrow a replaced same id registration or closed module`() {
        val host = NativeVisitHost(Any(), "same-host") { true }; var retired = 0
        val custody = NativeCustomEntryHostOwner({ 7L }, { host })
        assertTrue(custody.register("dns-token", 7, host) { retired++ })
        val original = checkNotNull(custody.captureAdmission("dns-token", 7, host))
        assertTrue(original()); assertTrue(custody.retire("dns-token", 7)); assertFalse(original())
        assertTrue(custody.register("dns-token", 7, host) { retired++ })
        val successor = checkNotNull(custody.captureAdmission("dns-token", 7, host))
        assertFalse(original()); assertTrue(successor()); assertEquals(1, retired)
        custody.close(); assertFalse(original()); assertFalse(successor()); assertEquals(2, retired)
        assertEquals(null, custody.captureAdmission("dns-token", 7, host))
    }
}
