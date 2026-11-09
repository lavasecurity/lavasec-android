package com.lavasec.app.billing

import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence.CONFIRMED
import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence.UNCONFIRMED
import com.lavasec.app.foundation.subscription.FeatureLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativePlusEntitlementOwnerTest {
    @Test fun `tier publication fences a simultaneous confirmed lapse`() = runTest {
        var durable = true
        val owner = NativePlusEntitlementOwner(object : PlusPlanPersistence {
            override suspend fun load() = durable
            override suspend fun save(hasPlus: Boolean) { durable = hasPlus }
        })
        assertTrue(owner.initialize())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publication = async {
            owner.withAdmission(requireNotNull(owner.captureAdmission())) {
                assertEquals(FeatureLimits.PLUS, owner.state.value.limits)
                entered.complete(Unit)
                release.await()
                assertTrue(durable)
                true
            }
        }
        entered.await()
        val lapse = async { owner.apply(VerifiedPlusReading(false, confidence = CONFIRMED)) }
        runCurrent()
        assertFalse(lapse.isCompleted)
        release.complete(Unit)
        assertEquals(true, publication.await())
        assertTrue(lapse.await())
        assertEquals(FeatureLimits.FREE, owner.withAvailableLimits { it })
    }

    @Test fun `uncertain plan persistence refuses publication until durable recovery`() = runTest {
        var durable = true
        val owner = NativePlusEntitlementOwner(object : PlusPlanPersistence {
            override suspend fun load() = durable
            override suspend fun save(hasPlus: Boolean) {
                durable = hasPlus
                throw java.io.IOException("readback unavailable after commit")
            }
        })
        assertNull(owner.withAvailableLimits { error("not initialized") })
        assertTrue(owner.initialize())
        assertFalse(owner.apply(VerifiedPlusReading(false, confidence = CONFIRMED)))
        assertNull(owner.withAvailableLimits { error("uncertain plan must not admit") })
        assertTrue(owner.initialize())
        assertEquals(FeatureLimits.FREE, owner.withAvailableLimits { it })
    }

    @Test fun `lapse during compilation invalidates admission even after a later grant`() = runTest {
        val owner = NativePlusEntitlementOwner(MemoryPlan(true))
        assertNull(owner.captureAdmission())
        assertTrue(owner.initialize())
        val admitted = requireNotNull(owner.captureAdmission())
        assertEquals(FeatureLimits.PLUS, admitted.limits)
        assertTrue(owner.apply(VerifiedPlusReading(false, confidence = CONFIRMED)))
        assertNull(owner.withAdmission(admitted) { error("stale edit") })
        assertTrue(owner.apply(VerifiedPlusReading(true, expiry, CONFIRMED)))
        assertNull(owner.withAdmission(admitted) { error("new grant cannot revive old admission") })
        assertEquals(true, owner.withAdmission(requireNotNull(owner.captureAdmission())) { true })
    }

    @Test fun `renewal and unconfirmed negative preserve an admitted edit`() = runTest {
        val owner = NativePlusEntitlementOwner(MemoryPlan(true))
        owner.initialize()
        val admitted = requireNotNull(owner.captureAdmission())
        owner.apply(VerifiedPlusReading(true, expiry, CONFIRMED))
        owner.apply(VerifiedPlusReading(false, confidence = UNCONFIRMED))
        assertSame(admitted, owner.captureAdmission())
        assertEquals(true, owner.withAdmission(admitted) { true })
    }

    @Test fun `recovery after uncertain storage never revives a pre-failure admission`() = runTest {
        val storage = MemoryPlan(true)
        val owner = NativePlusEntitlementOwner(storage)
        owner.initialize()
        val admitted = requireNotNull(owner.captureAdmission())
        storage.failSave = true
        assertFalse(owner.apply(VerifiedPlusReading(false, confidence = CONFIRMED)))
        assertNull(owner.captureAdmission())
        assertNull(owner.withAdmission(admitted) { error("uncertain storage") })
        storage.failSave = false
        assertTrue(owner.initialize())
        assertTrue(owner.state.value.hasPlus)
        assertNull(owner.withAdmission(admitted) { error("recovery must re-admit") })
        assertEquals(true, owner.withAdmission(requireNotNull(owner.captureAdmission())) { true })
    }

    @Test fun `admission from a retired owner cannot authorize a new owner`() = runTest {
        val storage = MemoryPlan(true)
        val old = NativePlusEntitlementOwner(storage)
        old.initialize()
        val replacement = NativePlusEntitlementOwner(storage)
        replacement.initialize()
        assertNull(replacement.withAdmission(requireNotNull(old.captureAdmission())) { error("foreign witness") })
    }

    private val expiry = Instant.parse("2026-10-01T00:00:00Z")

    @Test fun `signed out grant survives restart and cold empty read until confirmed lapse`() = runTest {
        val storage = MemoryPlan()
        val owner = NativePlusEntitlementOwner(storage)
        assertTrue(owner.initialize())
        assertTrue(owner.apply(VerifiedPlusReading(true, expiry, CONFIRMED)))
        assertEquals(FeatureLimits.PLUS, owner.state.value.limits)
        assertTrue(owner.apply(VerifiedPlusReading(false, confidence = UNCONFIRMED)))
        assertEquals(expiry, owner.state.value.expiresAt)
        assertTrue(owner.state.value.hasPlus)
        val restarted = NativePlusEntitlementOwner(storage)
        assertTrue(restarted.initialize())
        assertTrue(restarted.state.value.hasPlus)
        assertTrue(owner.apply(VerifiedPlusReading(false, confidence = CONFIRMED)))
        assertFalse(owner.state.value.hasPlus)
        assertNull(owner.state.value.expiresAt)
        assertEquals(listOf(true, false), storage.writes)
    }

    @Test fun `renewal updates expiry without rewriting the plan or reloading filters`() = runTest {
        val storage = MemoryPlan(true)
        val owner = NativePlusEntitlementOwner(storage)
        owner.initialize()
        owner.apply(VerifiedPlusReading(true, expiry, UNCONFIRMED))
        owner.apply(VerifiedPlusReading(true, expiry.plusSeconds(86_400), CONFIRMED))
        assertEquals(expiry.plusSeconds(86_400), owner.state.value.expiresAt)
        assertTrue(storage.writes.isEmpty())
    }

    @Test fun `failed persistence neither publishes nor consumes a verified reading`() = runTest {
        val storage = MemoryPlan()
        val owner = NativePlusEntitlementOwner(storage)
        owner.initialize()
        storage.failSave = true
        val reading = VerifiedPlusReading(true, expiry, CONFIRMED)
        assertFalse(owner.apply(reading))
        assertFalse(owner.state.value.hasPlus)
        storage.failSave = false
        assertTrue(owner.initialize())
        assertTrue(owner.apply(reading))
        assertTrue(owner.state.value.hasPlus)
        storage.failSave = true
        assertFalse(owner.apply(VerifiedPlusReading(false, confidence = CONFIRMED)))
        assertTrue(owner.state.value.hasPlus)
        assertEquals(expiry, owner.state.value.expiresAt)
    }

    @Test fun `uncertain committed grant is recovered before a later lapse`() = runTest {
        val storage = object : PlusPlanPersistence {
            var value = false
            override suspend fun load() = value
            override suspend fun save(hasPlus: Boolean) {
                value = hasPlus
                if (hasPlus) throw IOException("read-back failed after commit")
            }
        }
        val owner = NativePlusEntitlementOwner(storage)
        owner.initialize()
        assertFalse(owner.apply(VerifiedPlusReading(true, expiry, CONFIRMED)))
        assertFalse(owner.state.value.available)
        assertFalse(owner.apply(VerifiedPlusReading(false, confidence = CONFIRMED)))
        assertTrue(owner.initialize())
        assertTrue(owner.apply(VerifiedPlusReading(false, confidence = CONFIRMED)))
        assertFalse(storage.value)
    }

    @Test fun `unreadable persisted state cannot be overwritten by an empty read`() = runTest {
        val storage = MemoryPlan(true).apply { failLoad = true }
        val owner = NativePlusEntitlementOwner(storage)
        assertFalse(owner.initialize())
        assertFalse(owner.state.value.available)
        assertFalse(owner.apply(VerifiedPlusReading(false, confidence = CONFIRMED)))
        assertTrue(storage.writes.isEmpty())
        storage.failLoad = false
        assertTrue(owner.initialize())
        assertTrue(owner.state.value.hasPlus)
    }

    @Test fun `publication waits for durable grant and concurrent lapse settles in order`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = object : PlusPlanPersistence {
            var value = false
            override suspend fun load() = value
            override suspend fun save(hasPlus: Boolean) {
                if (hasPlus) { entered.complete(Unit); release.await() }
                value = hasPlus
            }
        }
        val owner = NativePlusEntitlementOwner(storage)
        owner.initialize()
        val grant = async { owner.apply(VerifiedPlusReading(true, expiry, CONFIRMED)) }
        entered.await()
        assertFalse(owner.state.value.hasPlus)
        val lapse = async { owner.apply(VerifiedPlusReading(false, confidence = CONFIRMED)) }
        release.complete(Unit)
        assertTrue(grant.await())
        assertTrue(lapse.await())
        assertFalse(owner.state.value.hasPlus)
        assertFalse(storage.value)
    }

    private class MemoryPlan(var value: Boolean = false) : PlusPlanPersistence {
        var failLoad = false
        var failSave = false
        val writes = mutableListOf<Boolean>()
        override suspend fun load(): Boolean {
            if (failLoad) throw IOException("unreadable")
            return value
        }
        override suspend fun save(hasPlus: Boolean) {
            if (failSave) throw IOException("disk full")
            value = hasPlus
            writes += hasPlus
        }
    }
}
