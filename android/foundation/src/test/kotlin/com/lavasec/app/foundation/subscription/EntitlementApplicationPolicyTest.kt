package com.lavasec.app.foundation.subscription

import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence.CONFIRMED
import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence.UNCONFIRMED
import org.junit.Assert.*
import org.junit.Test

class EntitlementApplicationPolicyTest {
    @Test fun `iOS full application truth table`() {
        // The same literal vectors as EntitlementApplicationPolicyTests.swift.
        val inputs = listOf(
            Triple(false, true, UNCONFIRMED) to true,
            Triple(false, true, CONFIRMED) to false,
            Triple(true, false, UNCONFIRMED) to true,
            Triple(true, false, CONFIRMED) to true,
            Triple(true, true, UNCONFIRMED) to true,
            Triple(true, true, CONFIRMED) to true,
            Triple(false, false, UNCONFIRMED) to false,
            Triple(false, false, CONFIRMED) to false,
        )
        for ((input, expected) in inputs) {
            assertEquals(input.toString(), expected, EntitlementApplicationPolicy.resolvedHasLavaSecurityPlus(
                input.first, input.second, input.third,
            ))
        }
    }

    @Test fun `confirmed lapse is delivered after a cold empty read`() {
        assertTrue(EntitlementApplicationPolicy.shouldDeliverReading(false, CONFIRMED, UNCONFIRMED))
        assertFalse(EntitlementApplicationPolicy.shouldDeliverReading(false, CONFIRMED, CONFIRMED))
        assertFalse(EntitlementApplicationPolicy.shouldDeliverReading(false, UNCONFIRMED, CONFIRMED))
        assertFalse(EntitlementApplicationPolicy.shouldDeliverReading(false, UNCONFIRMED, UNCONFIRMED))
        for (old in EntitlementReadingConfidence.entries) for (new in EntitlementReadingConfidence.entries) {
            assertTrue(EntitlementApplicationPolicy.shouldDeliverReading(true, new, old))
        }
    }

    @Test fun `canonical tiers and store product identities`() {
        assertEquals(FeatureLimits(25, 25, 500_000, false, false, 3), FeatureLimits.FREE)
        assertEquals(FeatureLimits(1000, 1000, 2_000_000, true, true, 50), FeatureLimits.PLUS)
        assertFalse(FeatureLimits.PLUS.hasUnlimitedFilters)
        assertEquals(listOf("lava_security_plus_monthly", "lava_security_plus_yearly"),
            LavaSecurityPlusPolicy.paywallProductIDs)
        assertEquals(LavaSecurityPlusPolicy.yearly.productID, LavaSecurityPlusPolicy.yearlyPaidMonthly.productID)
        assertEquals(listOf("yearly", "monthly"), LavaSecurityPlusPolicy.fallbackOfferOrder.map { it.id })
        assertNull(LavaSecurityPlusPolicy.plan("lava_security_plus_yearly_paid_monthly"))
    }
}
