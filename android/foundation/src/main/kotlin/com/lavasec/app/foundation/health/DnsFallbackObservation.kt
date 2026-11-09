package com.lavasec.app.foundation.health

/** Finite numeric evidence only. A query name, address, endpoint or server text cannot enter it. */
enum class DnsFallbackReason { TRANSPORT_FAILURE, TIER_BUDGET_EXPIRED, OPERATIONAL_ERROR, RESOLVER_OUTAGE, PORTAL_ADDRESS_NEGATIVE }
enum class DnsFallbackDisposition { ATTEMPTED, SERVED, PRESERVED_NEGATIVE }
data class DnsFallbackObservation(
    val fromTier: Int,
    val toTier: Int,
    val reason: DnsFallbackReason,
    val disposition: DnsFallbackDisposition,
) {
    init { require(fromTier in 0..1 && toTier in 1..2 && toTier > fromTier) }
}
