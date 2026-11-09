package com.lavasec.app.reactnative

import com.lavasec.app.billing.*
import com.lavasec.app.foundation.subscription.FeatureLimits
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativePlusSnapshotTest {
    @Test fun `all price phases and actual installment commitments reach the shared paywall`() {
        val offer = PlayOffer("opaque-handle", "internal-product", "Plus", "base-plan", listOf(
            PlayPricePhase("¥0", "P1M", 1, 2),
            PlayPricePhase("¥500", "P1M", 0, 1),
        ), PlayInstallmentCommitment(6, 3))
        val result = plusSnapshot(NativePlusState(available = true), PlayPurchaseSnapshot(),
            NativePlusPresentation(offers = listOf(offer)), com.lavasec.app.edition.AppEdition.PLAY)
        val row = result.getJSONArray("offers").getJSONObject(0)
        assertEquals("opaque-handle", row.getString("id"))
        assertEquals("¥0", row.getString("price"))
        val terms = row.getString("subtitle")
        assertTrue(terms.contains("¥0 every 1 month for 1 payment, then ¥500 every 1 month until cancelled"))
        assertTrue(terms.contains("Initial commitment: 6 payments"))
        assertTrue(terms.contains("Renewal commitment: 3 payments"))
        assertFalse(terms.contains("12-month"))
        assertEquals("google-play", result.getString("store"))
        assertFalse(result.getBoolean("showsYearlyPaidMonthly"))
        assertFalse(result.toString().contains("Apple"))
        assertFalse(result.toString().contains("base-plan"))
    }

    @Test fun `unavailable refresh preserves enabled Plus while exposing verification failure`() {
        val result = plusSnapshot(NativePlusState(available = true, hasPlus = true,
            expiresAt = Instant.parse("2027-01-01T00:00:00Z")),
            PlayPurchaseSnapshot(PlayPurchaseReadStatus.UNAVAILABLE), NativePlusPresentation(), com.lavasec.app.edition.AppEdition.PLAY)
        assertTrue(result.getBoolean("enabled"))
        assertFalse(result.getBoolean("checking"))
        assertTrue(result.getString("message").contains("preserved"))
        assertEquals("2027-01-01T00:00:00Z", result.getString("expiration"))
        assertEquals(0, result.getJSONArray("offers").length())
    }

    @Test fun `limits projection changes tiers without conflating saved filter data`() {
        val plus = limitsSnapshot(FeatureLimits.PLUS)
        val free = limitsSnapshot(FeatureLimits.FREE)
        assertEquals(1000, plus.getInt("maxAllowedDomains"))
        assertEquals(2000000, plus.getInt("maxFilterRules"))
        assertEquals(25, free.getInt("maxAllowedDomains"))
        assertEquals(500000, free.getInt("maxFilterRules"))
        assertFalse(free.getBoolean("allowsCustomDNS"))
    }

    @Test fun `billing periods retain weeks and multiple units without assuming monthly billing`() {
        assertEquals("7 days", billingPeriodText("P1W"))
        assertEquals("3 months", billingPeriodText("P3M"))
        assertEquals("1 year", billingPeriodText("P1Y"))
        assertEquals("1 year 6 months", billingPeriodText("P1Y6M"))
        assertEquals("unknown", billingPeriodText("unknown"))
    }
    @Test fun `local capability projection never represents a paid or expiring entitlement`() {
        val result = plusSnapshot(NativePlusState(available = true, localCapabilities = true),
            PlayPurchaseSnapshot(), NativePlusPresentation())
        assertTrue(result.getBoolean("enabled"))
        assertFalse(result.getBoolean("paid"))
        assertEquals("", result.getString("expiration"))
        assertEquals(0, result.getJSONArray("offers").length())
    }

    @Test fun `FOSS projection discards all commercial state even when supplied a paid reading`() {
        val result = plusSnapshot(NativePlusState(available = true, hasPlus = true,
            expiresAt = Instant.parse("2027-01-01T00:00:00Z")),
            PlayPurchaseSnapshot(PlayPurchaseReadStatus.UNAVAILABLE),
            NativePlusPresentation(offers = listOf(PlayOffer("paid-handle", "sku", "Paid offer", "base", emptyList(), null)),
                busy = true, message = PlusMessage.VERIFICATION_UNAVAILABLE),
            com.lavasec.app.edition.AppEdition.FOSS)
        assertTrue(result.getBoolean("enabled"))
        for (key in listOf("paid", "busy", "checking", "showsYearlyPaidMonthly")) assertFalse(result.getBoolean(key))
        assertEquals("", result.getString("expiration"))
        assertEquals("", result.getString("message"))
        assertEquals("foss", result.getString("store"))
        assertEquals(0, result.getJSONArray("offers").length())
        assertFalse(result.toString().contains("paid-handle"))
    }

}
