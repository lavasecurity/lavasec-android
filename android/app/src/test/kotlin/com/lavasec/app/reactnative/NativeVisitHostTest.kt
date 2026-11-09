package com.lavasec.app.reactnative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVisitHostTest {
    @Test fun `ordinary same Activity pause preserves original host ownership`() {
        val activity = Any()
        val host = NativeVisitHost(activity, "a") { true }
        assertTrue(host.retains(NativeVisitHost(activity, "a") { true }))
        assertTrue(host.matches(NativeVisitHost(activity, "a") { true }))
    }
    @Test fun `temporary missing current host retains but never authorizes original visit`() {
        val host = NativeVisitHost(Any(), "a") { true }
        assertTrue(host.retains(null))
        assertFalse(host.matches(null))
    }
    @Test fun `successor Activity cannot inherit a witness even with equal nonce`() {
        val host = NativeVisitHost(Any(), "a") { true }
        val successor = NativeVisitHost(Any(), "a") { true }
        assertFalse(host.retains(successor))
        assertFalse(host.matches(successor))
    }
    @Test fun `same Activity with a different native host identity cannot inherit visit`() {
        val activity = Any()
        val host = NativeVisitHost(activity, "a") { true }
        val successor = NativeVisitHost(activity, "b") { true }
        assertFalse(host.retains(successor))
        assertFalse(host.matches(successor))
    }
    @Test fun `destroyed captured Activity is retired with and without a current host`() {
        var alive = true
        val activity = Any()
        val host = NativeVisitHost(activity, "a") { alive }
        alive = false
        assertFalse(host.retains(null))
        assertFalse(host.retains(NativeVisitHost(activity, "a") { true }))
        assertFalse(host.matches(NativeVisitHost(activity, "a") { true }))
    }
}
