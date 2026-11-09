package com.lavasec.app.billing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class PlayPurchaseOwnerTest {
    private val product = "lava_security_plus_yearly"
    private val expiry = Instant.parse("2027-09-01T00:00:00Z")
    private fun receipt(token: String = "secret-receipt", purchased: Boolean = true) =
        PlayPurchase(token, setOf(product), purchased, false)
    private fun verdict(active: Boolean = true) = VerifiedPlayPurchase(product, active, expiry, active)

    @Test fun `signed out purchase persists grant before acknowledgement and retries failed acknowledgement`() = runTest {
        val persisted = mutableListOf<Boolean>()
        val cache = MemoryCache()
        val plan = NativePlusEntitlementOwner(object : PlusPlanPersistence {
            override suspend fun load() = false
            override suspend fun save(hasPlus: Boolean) {
                assertEquals("secret-receipt", cache.receipts.single().token)
                persisted += hasPlus
            }
        })
        val port = Port().apply {
            receipts = listOf(receipt())
            onAcknowledge = { assertEquals(listOf(true), persisted); assertTrue(plan.state.value.hasPlus) }
            failAcknowledgement = true
        }
        val owner = PlayPurchaseOwner(port, plan, cache) { verdict() }
        assertTrue(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
        assertTrue(owner.state.value.acknowledgementPending)
        port.failAcknowledgement = false
        assertTrue(owner.refresh())
        assertFalse(owner.state.value.acknowledgementPending)
        assertEquals(2, port.acknowledgements)
        assertEquals(listOf(true), persisted)
        assertFalse(receipt().toString().contains("secret-receipt"))
    }

    @Test fun `empty SDK inventory rechecks known receipts and a confirmed expiry survives restart`() = runTest {
        val plan = plan(false)
        val cache = MemoryCache()
        val port = Port().apply { receipts = listOf(receipt()) }
        var active = true
        var verifiedTokens = mutableListOf<String>()
        val owner = PlayPurchaseOwner(port, plan, cache) { verifiedTokens += it.token; verdict(active) }
        assertTrue(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
        assertEquals(1, cache.receipts.size)
        port.receipts = emptyList() // Play no longer lists the expired subscription.
        active = false
        val restarted = PlayPurchaseOwner(port, plan, cache) { verifiedTokens += it.token; verdict(false) }
        assertTrue(restarted.refresh())
        assertFalse(plan.state.value.hasPlus)
        assertTrue(cache.receipts.isEmpty())
        assertEquals(listOf("secret-receipt", "secret-receipt"), verifiedTokens)
    }

    @Test fun `empty inventory without known verification material is uncertainty even on restore`() = runTest {
        val plan = plan(true)
        val owner = PlayPurchaseOwner(Port(), plan, MemoryCache()) { error("No receipt to verify") }
        assertFalse(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
        assertEquals(PlayPurchaseReadStatus.UNAVAILABLE, owner.state.value.status)
    }

    @Test fun `a changed SDK descriptor cannot substitute for re-verifying a retained grant`() = runTest {
        val plan = plan(true)
        val cache = MemoryCache(listOf(receipt()))
        val port = Port().apply { receipts = listOf(PlayPurchase("secret-receipt", setOf("unrelated"), true, true)) }
        val owner = PlayPurchaseOwner(port, plan, cache) { error("Unknown product must not verify") }
        assertFalse(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
        assertEquals(1, cache.receipts.size)
    }

    @Test fun `missing SDK purchase and failed server read preserve the known grant`() = runTest {
        val plan = plan(true)
        val cache = MemoryCache(listOf(receipt()))
        val owner = PlayPurchaseOwner(Port(), plan, cache) { throw IOException("offline") }
        assertFalse(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
        assertEquals(1, cache.receipts.size)
    }

    @Test fun `verification material must commit before a grant can be acknowledged`() = runTest {
        val plan = plan(false)
        val cache = MemoryCache().apply { failSave = true }
        val port = Port().apply { receipts = listOf(receipt()) }
        val owner = PlayPurchaseOwner(port, plan, cache) { verdict() }
        assertFalse(owner.refresh())
        assertFalse(plan.state.value.hasPlus)
        assertEquals(0, port.acknowledgements)
        cache.failSave = false
        port.onAcknowledge = { assertEquals("secret-receipt", cache.receipts.single().token) }
        assertTrue(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
    }

    @Test fun `query verification and pending failures never demote even during restore`() = runTest {
        val plan = plan(true)
        val port = Port().apply { failQuery = true }
        var verificationFails = true
        val owner = PlayPurchaseOwner(port, plan, MemoryCache(listOf(receipt()))) {
            if (verificationFails) throw IOException("verification unavailable")
            verdict(false)
        }
        assertFalse(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
        port.failQuery = false
        port.receipts = listOf(receipt())
        assertFalse(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
        port.receipts = listOf(receipt(purchased = false))
        assertTrue(owner.refresh())
        assertEquals(PlayPurchaseReadStatus.PENDING, owner.state.value.status)
        assertTrue(plan.state.value.hasPlus)
        assertEquals(0, port.acknowledgements)
        verificationFails = false
        port.receipts = listOf(receipt())
        assertTrue(owner.refresh())
        assertFalse(plan.state.value.hasPlus)
    }

    @Test fun `partial verification can grant but cannot revoke`() = runTest {
        val plan = plan(false)
        val port = Port().apply { receipts = listOf(receipt("good"), receipt("unavailable")) }
        var active = true
        val owner = PlayPurchaseOwner(port, plan, MemoryCache()) {
            if (it.token == "unavailable") throw IOException()
            verdict(active)
        }
        assertFalse(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
        active = false
        assertFalse(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
    }

    @Test fun `failed durable grant is never acknowledged`() = runTest {
        val plan = NativePlusEntitlementOwner(object : PlusPlanPersistence {
            override suspend fun load() = false
            override suspend fun save(hasPlus: Boolean) { throw IOException("disk full") }
        })
        val port = Port().apply { receipts = listOf(receipt()) }
        val owner = PlayPurchaseOwner(port, plan, MemoryCache()) { verdict() }
        assertFalse(owner.refresh())
        assertFalse(plan.state.value.hasPlus)
        assertEquals(0, port.acknowledgements)
    }

    @Test fun `mismatched verified product never grants or acknowledges`() = runTest {
        val plan = plan(false)
        val port = Port().apply { receipts = listOf(receipt()) }
        val owner = PlayPurchaseOwner(port, plan, MemoryCache()) { verdict().copy(productId = "unrelated") }
        assertFalse(owner.refresh())
        assertFalse(plan.state.value.hasPlus)
        assertEquals(0, port.acknowledgements)
    }

    @Test fun `unreadable verification storage cannot turn a known grant into an empty inventory`() = runTest {
        val plan = plan(true)
        val cache = MemoryCache(listOf(receipt())).apply { failLoad = true }
        val owner = PlayPurchaseOwner(Port(), plan, cache) { error("Unreadable store must not verify") }
        assertFalse(owner.refresh())
        assertTrue(plan.state.value.hasPlus)
        assertEquals(1, cache.receipts.size)
    }

    @Test fun `failed receipt cleanup after a durable lapse is retryable and never regrants`() = runTest {
        val plan = plan(true)
        val cache = MemoryCache(listOf(receipt())).apply { failSave = true }
        val owner = PlayPurchaseOwner(Port(), plan, cache) { verdict(false) }
        assertFalse(owner.refresh())
        assertFalse(plan.state.value.hasPlus)
        assertEquals(1, cache.receipts.size)
        cache.failSave = false
        assertTrue(owner.refresh())
        assertFalse(plan.state.value.hasPlus)
        assertTrue(cache.receipts.isEmpty())
    }

    private fun plan(initial: Boolean) = NativePlusEntitlementOwner(object : PlusPlanPersistence {
        var value = initial
        override suspend fun load() = value
        override suspend fun save(hasPlus: Boolean) { value = hasPlus }
    })

    private class MemoryCache(var receipts: List<PlayPurchase> = emptyList()) : PlayReceiptCache {
        var failSave = false
        var failLoad = false
        override suspend fun load(): List<PlayPurchase> {
            if (failLoad) throw IOException("storage unreadable")
            return receipts
        }
        override suspend fun save(receipts: List<PlayPurchase>) {
            if (failSave) throw IOException("storage unavailable")
            this.receipts = receipts.toList()
        }
    }

    private class Port : PlayPurchasePort {
        var receipts = emptyList<PlayPurchase>()
        var failQuery = false
        var failAcknowledgement = false
        var acknowledgements = 0
        var onAcknowledge: () -> Unit = {}
        override suspend fun purchases(): List<PlayPurchase> {
            if (failQuery) throw IOException()
            return receipts
        }
        override suspend fun acknowledge(token: String) {
            acknowledgements++
            onAcknowledge()
            if (failAcknowledgement) throw IOException()
        }
    }
}
