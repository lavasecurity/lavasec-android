package com.lavasec.app.foundation.subscription

/** Direct port of LavaSecKit/SubscriptionPolicy.swift (iOS c4658d9). */
data class FeatureLimits(
    val maxAllowedDomains: Int,
    val maxBlockedDomains: Int,
    val maxFilterRules: Int,
    val allowsCustomBlocklists: Boolean,
    val allowsCustomDNS: Boolean,
    val maxFilters: Int = 3,
) {
    val hasUnlimitedFilters: Boolean get() = maxFilters == Int.MAX_VALUE

    companion object {
        val FREE = FeatureLimits(25, 25, 500_000, false, false, 3)
        val PAID = FeatureLimits(1_000, 1_000, 2_000_000, true, true, 50)
        val PLUS = PAID
        // Commercial counts are removed. Parser/engine's 2M rule ceiling remains a safety bound.
        val FOSS = FeatureLimits(Int.MAX_VALUE, Int.MAX_VALUE, com.lavasec.app.foundation.filter.FilterCompiler.MAX_SOURCE_RULES, true, true, Int.MAX_VALUE)
    }
}
