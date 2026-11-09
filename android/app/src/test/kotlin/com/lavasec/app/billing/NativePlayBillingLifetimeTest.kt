package com.lavasec.app.billing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativePlayBillingLifetimeTest {
    @Test fun `closing releases the client once and rejects new work and purchase callbacks`() {
        var changes = 0
        var releases = 0
        val lifetime = NativePlayBillingLifetime({ changes++ }, { releases++ })
        lifetime.purchaseChanged()
        assertEquals(1, changes)
        lifetime.close()
        lifetime.close()
        assertEquals(1, releases)
        assertFalse(lifetime.isOpen)
        lifetime.purchaseChanged()
        assertEquals(1, changes)
        assertThrows(PlayBillingUnavailable::class.java, lifetime::ensureOpen)
        assertFalse(lifetime.registerLaunch(CompletableDeferred()))
    }

    @Test fun `closing unresolved launch preserves uncertainty and a late success cannot rewrite it`() = runTest {
        var changes = 0
        val lifetime = NativePlayBillingLifetime({ changes++ }, {})
        val pending = CompletableDeferred<PlayLaunchResult>()
        assertTrue(lifetime.registerLaunch(pending))
        assertFalse(lifetime.registerLaunch(CompletableDeferred()))
        lifetime.close()
        assertEquals(PlayLaunchResult.UNAVAILABLE, pending.await())
        lifetime.completeLaunch(PlayLaunchResult.PURCHASED)
        lifetime.completeLaunch(PlayLaunchResult.ALREADY_OWNED)
        lifetime.purchaseChanged()
        assertEquals(PlayLaunchResult.UNAVAILABLE, pending.await())
        assertEquals(0, changes)
        assertFalse(lifetime.registerLaunch(CompletableDeferred()))
    }

    @Test fun `an actual completed store response before closure keeps its observed result`() = runTest {
        val lifetime = NativePlayBillingLifetime({}, {})
        val completed = CompletableDeferred<PlayLaunchResult>()
        assertTrue(lifetime.registerLaunch(completed))
        lifetime.completeLaunch(PlayLaunchResult.PENDING)
        lifetime.close()
        assertEquals(PlayLaunchResult.PENDING, completed.await())
    }

    @Test fun `an immediate refused launch releases only its exact pending marker`() = runTest {
        val lifetime = NativePlayBillingLifetime({}, {})
        val first = CompletableDeferred<PlayLaunchResult>()
        val second = CompletableDeferred<PlayLaunchResult>()
        assertTrue(lifetime.registerLaunch(first))
        lifetime.clearLaunch(second)
        assertFalse(lifetime.registerLaunch(second))
        lifetime.clearLaunch(first)
        assertTrue(lifetime.registerLaunch(second))
        lifetime.close()
        assertEquals(PlayLaunchResult.UNAVAILABLE, second.await())
        assertFalse(first.isCompleted)
    }

    @Test fun `SDK release failure remains visible while owner is retired and pending result stays uncertain`() = runTest {
        var releases = 0
        val failure = IllegalStateException("SDK release failed")
        val lifetime = NativePlayBillingLifetime({}, { releases++; throw failure })
        val pending = CompletableDeferred<PlayLaunchResult>()
        assertTrue(lifetime.registerLaunch(pending))
        assertSame(failure, assertThrows(IllegalStateException::class.java, lifetime::close))
        assertFalse(lifetime.isOpen)
        assertEquals(PlayLaunchResult.UNAVAILABLE, pending.await())
        lifetime.close()
        assertEquals(1, releases)
        assertFalse(lifetime.registerLaunch(CompletableDeferred()))
    }
}
