package com.lavasec.app.foundation.subscription

enum class LavaSecurityPlusPlanKind(val id: String) {
    MONTHLY("monthly"), YEARLY("yearly"), YEARLY_PAID_MONTHLY("yearlyPaidMonthly"),
}

data class LavaSecurityPlusPlan(
    val kind: LavaSecurityPlusPlanKind,
    val productID: String,
    val displayName: String,
    val fallbackDisplayPrice: String,
    val isSubscription: Boolean,
) {
    val id: String get() = kind.id
}

/**
 * Direct port of LavaSecKit/LavaSecurityPlusPolicy.swift (iOS c4658d9).
 * Fallback copy is display-only; a purchase requires the platform store's current offer.
 * The installment offer shares the yearly product; it is not a third subscription product.
 */
object LavaSecurityPlusPolicy {
    val monthly = LavaSecurityPlusPlan(
        LavaSecurityPlusPlanKind.MONTHLY, "lava_security_plus_monthly", "Monthly", "$3.99", true,
    )
    val yearly = LavaSecurityPlusPlan(
        LavaSecurityPlusPlanKind.YEARLY, "lava_security_plus_yearly", "Yearly", "$29.99", true,
    )
    val yearlyPaidMonthly = LavaSecurityPlusPlan(
        LavaSecurityPlusPlanKind.YEARLY_PAID_MONTHLY,
        yearly.productID, "Yearly, paid monthly", "$2.99/month", true,
    )
    val recommendedOfferOrder = listOf(yearly, yearlyPaidMonthly, monthly)
    val fallbackOfferOrder = listOf(yearly, monthly)
    val paywallProductIDs = listOf(monthly.productID, yearly.productID)
    val entitlementPlans = listOf(monthly, yearly)
    fun plan(productID: String): LavaSecurityPlusPlan? = entitlementPlans.firstOrNull { it.productID == productID }
}
