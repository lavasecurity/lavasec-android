package com.lavasec.app.billing

import com.lavasec.app.work.installPlayReconciliationIfConfigured
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativePlusControllerTest {
    private val offer = PlayOffer("native-handle", "lava_security_plus_yearly", "Plus", "yearly", emptyList(), null)

    @Test fun `a purchase waits for the store result and refuses overlap without a second prompt`() = runTest {
        val fixture = Fixture()
        fixture.controller.loadProducts()
        val launched = CompletableDeferred<Unit>()
        val result = CompletableDeferred<PlayLaunchResult>()
        val buying = async {
            fixture.controller.buy(offer.handle) {
                assertTrue(fixture.scheduled)
                launched.complete(Unit)
                result.await()
            }
        }
        launched.await()
        assertTrue(fixture.controller.state.value.busy)
        assertFalse(fixture.controller.restore())
        assertFalse(fixture.controller.buy(offer.handle) { error("overlapping prompt") })
        assertFalse(fixture.plan.state.value.hasPlus)
        result.complete(PlayLaunchResult.PURCHASED)
        assertTrue(buying.await())
        assertTrue(fixture.plan.state.value.hasPlus)
        assertEquals(1, fixture.acknowledgements)
        assertFalse(fixture.controller.state.value.busy)
        assertNull(fixture.controller.state.value.message)
    }

    @Test fun `pending cancellation and SDK failures settle the UI without granting a plan`() = runTest {
        for ((result, message) in listOf(
            PlayLaunchResult.PENDING to PlusMessage.PURCHASE_PENDING,
            PlayLaunchResult.CANCELLED to PlusMessage.PURCHASE_CANCELLED,
            PlayLaunchResult.UNAVAILABLE to PlusMessage.PURCHASE_UNAVAILABLE,
            PlayLaunchResult.STALE_OFFER to PlusMessage.OFFER_EXPIRED,
        )) {
            val fixture = Fixture()
            fixture.controller.loadProducts()
            fixture.controller.buy(offer.handle) { result }
            assertEquals(message, fixture.controller.state.value.message)
            assertFalse(fixture.controller.state.value.busy)
            assertFalse(fixture.plan.state.value.hasPlus)
            assertEquals(0, fixture.acknowledgements)
            fixture.controller.clearMessage()
            assertNull(fixture.controller.state.value.message)
        }
    }

    @Test fun `a fabricated handle and failed durable scheduling cannot launch Play`() = runTest {
        val fixture = Fixture()
        fixture.controller.loadProducts()
        assertFalse(fixture.controller.buy("invented") { error("must not launch") })
        assertEquals(PlusMessage.OFFER_EXPIRED, fixture.controller.state.value.message)
        fixture.scheduleFails = true
        assertFalse(fixture.controller.buy(offer.handle) { error("must not launch") })
        assertFalse(fixture.controller.state.value.busy)
    }

    @Test fun `cancelled native host releases busy state and does not acknowledge`() = runTest {
        val fixture = Fixture()
        fixture.controller.loadProducts()
        val entered = CompletableDeferred<Unit>()
        val buying = launch {
            fixture.controller.buy(offer.handle) { entered.complete(Unit); CompletableDeferred<PlayLaunchResult>().await() }
        }
        entered.await()
        buying.cancelAndJoin()
        assertFalse(fixture.controller.state.value.busy)
        assertEquals(0, fixture.acknowledgements)
        assertTrue(fixture.controller.restore())
    }

    @Test fun `restoring an uncertain inventory preserves Plus and reports verification unavailable`() = runTest {
        val fixture = Fixture(previouslyPlus = true)
        fixture.receipts = emptyList()
        assertTrue(fixture.controller.restore())
        assertTrue(fixture.plan.state.value.hasPlus)
        assertEquals(PlusMessage.VERIFICATION_UNAVAILABLE, fixture.controller.state.value.message)
    }

    @Test fun `failed offer reload clears old handles and stale price presentation`() = runTest {
        val fixture = Fixture()
        fixture.controller.loadProducts()
        assertEquals(listOf(offer), fixture.controller.state.value.offers)
        fixture.productsFail = true
        fixture.controller.loadProducts()
        assertTrue(fixture.controller.state.value.offers.isEmpty())
        assertFalse(fixture.controller.state.value.loading)
        assertFalse(fixture.controller.buy(offer.handle) { error("must not launch stale price") })
    }

    @Test fun `a product reload clears its own error but preserves purchase feedback`() = runTest {
        val fixture = Fixture()
        fixture.productsFail = true
        fixture.controller.loadProducts()
        assertEquals(PlusMessage.PRODUCTS_UNAVAILABLE, fixture.controller.state.value.message)
        fixture.productsFail = false
        fixture.controller.loadProducts()
        assertNull(fixture.controller.state.value.message)
        fixture.controller.buy(offer.handle) { PlayLaunchResult.PENDING }
        fixture.productsFail = true
        fixture.controller.loadProducts()
        assertEquals(PlusMessage.PURCHASE_PENDING, fixture.controller.state.value.message)
    }

    @Test fun `server failure after store success never grants or acknowledges`() = runTest {
        val fixture = Fixture()
        fixture.verificationFails = true
        fixture.controller.loadProducts()
        fixture.controller.buy(offer.handle) { PlayLaunchResult.PURCHASED }
        assertFalse(fixture.plan.state.value.hasPlus)
        assertEquals(0, fixture.acknowledgements)
        assertEquals(PlusMessage.VERIFICATION_UNAVAILABLE, fixture.controller.state.value.message)
    }

    private inner class Fixture(previouslyPlus: Boolean = false) {
        var scheduled = false
        var scheduleFails = false
        var productsFail = false
        var verificationFails = false
        var acknowledgements = 0
        var receipts = listOf(PlayPurchase("private-test-token", setOf(offer.productId), true, false))
        val plan = NativePlusEntitlementOwner(object : PlusPlanPersistence {
            var stored = previouslyPlus
            override suspend fun load() = stored
            override suspend fun save(hasPlus: Boolean) { stored = hasPlus }
        })
        val purchases = PlayPurchaseOwner(object : PlayPurchasePort {
            override suspend fun purchases() = receipts
            override suspend fun acknowledge(token: String) { acknowledgements++ }
        }, plan, object : PlayReceiptCache {
            var values = emptyList<PlayPurchase>()
            override suspend fun load() = values
            override suspend fun save(receipts: List<PlayPurchase>) { values = receipts }
        }) {
            if (verificationFails) throw IOException()
            VerifiedPlayPurchase(offer.productId, true, Instant.parse("2027-09-01T00:00:00Z"), true)
        }
        val controller = NativePlusController(plan, purchases,
            loadOffers = { if (productsFail) throw IOException(); listOf(offer) },
            ensureReconciliation = {
                installPlayReconciliationIfConfigured(true) {
                    if (scheduleFails) throw IOException()
                    scheduled = true
                }
            },
        )
    }
}
