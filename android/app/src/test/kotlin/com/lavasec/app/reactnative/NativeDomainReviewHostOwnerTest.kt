package com.lavasec.app.reactnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDomainReviewHostOwnerTest {
    @Test fun `host retirement invokes only its original callback once without reading authority`() {
        val a = Any(); val b = Any(); val hostA = NativeVisitHost(a, "a") { true }
        val hostB = NativeVisitHost(b, "b") { true }; var current = hostA; var reads = true
        val owner = NativeDomainReviewHostOwner({ check(reads); 7L }, { check(reads); current })
        val visitA = checkNotNull(owner.begin("a-token", 7, hostA)); var retiredA = 0
        assertTrue(owner.attach(visitA) { retiredA++ })
        current = hostB
        val visitB = checkNotNull(owner.begin("b-token", 7, hostB)); var retiredB = 0
        assertTrue(owner.attach(visitB) { retiredB++ }); reads = false
        owner.retireHost(a, "wrong"); owner.retireHost(Any(), "a")
        assertEquals(0, retiredA); assertEquals(0, retiredB)
        owner.retireHost(a, "a"); owner.retireHost(a, "a")
        assertEquals(1, retiredA); assertEquals(0, retiredB)
        owner.close(); owner.close(); assertEquals(1, retiredB)
    }

    @Test fun `host destruction before draft attachment compensates a late original result`() {
        val a = Any(); var alive = true; val host = NativeVisitHost(a, "a") { alive }
        val owner = NativeDomainReviewHostOwner({ 7L }, { host })
        val visit = checkNotNull(owner.begin("token", 7, host)); var callbacks = 0
        alive = false; owner.retireHost(a, "a")
        assertFalse(owner.attach(visit) { callbacks++ }); assertEquals(1, callbacks)
        assertFalse(owner.attach(visit) { callbacks++ }); assertEquals(1, callbacks)
        assertNull(owner.begin("late", 7, host)); owner.close(); assertEquals(1, callbacks)
    }

    @Test fun `module close preserves late cleanup and rejects newly opened visits`() {
        val host = NativeVisitHost(Any(), "a") { true }
        val owner = NativeDomainReviewHostOwner({ 7L }, { host })
        val visit = checkNotNull(owner.begin("token", 7, host)); var callbacks = 0
        owner.close(); assertFalse(owner.attach(visit) { callbacks++ }); assertEquals(1, callbacks)
        assertNull(owner.begin("late", 7, host)); assertFalse(owner.retire(visit)); assertEquals(1, callbacks)
    }

    @Test fun `Apply release never discards and old handles cannot retire a replacement token`() {
        val host = NativeVisitHost(Any(), "a") { true }
        val owner = NativeDomainReviewHostOwner({ 7L }, { host }); var oldCalls = 0; var freshCalls = 0
        val original = checkNotNull(owner.begin("token", 7, host))
        assertTrue(owner.attach(original) { oldCalls++ }); assertTrue(owner.release(original))
        val successor = checkNotNull(owner.begin("token", 7, host))
        assertTrue(owner.attach(successor) { freshCalls++ })
        assertFalse(owner.retire(original)); assertFalse(owner.release(original)); assertEquals(0, oldCalls)
        assertTrue(owner.isCurrent(successor)); assertTrue(owner.retire(successor)); assertEquals(1, freshCalls)
        owner.close(); assertEquals(0, oldCalls); assertEquals(1, freshCalls)
    }

    @Test fun `rotated generation and wrong current host refuse before authentication can start`() {
        val a = NativeVisitHost(Any(), "a") { true }; val b = NativeVisitHost(Any(), "b") { true }
        var generation = 7L; var current = a
        val owner = NativeDomainReviewHostOwner({ generation }, { current })
        assertNull(owner.begin("wrong-g", 8, a)); current = b; assertNull(owner.begin("wrong-host", 7, a))
        assertNotNull(owner.begin("b-token", 7, b)); generation = 8
        assertNull(owner.begin("old-g", 7, b)); owner.close()
    }
}
