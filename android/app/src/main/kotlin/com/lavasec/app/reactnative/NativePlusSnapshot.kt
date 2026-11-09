package com.lavasec.app.reactnative

import com.lavasec.app.billing.NativePlusPresentation
import com.lavasec.app.billing.NativePlusState
import com.lavasec.app.billing.PlayOffer
import com.lavasec.app.billing.PlayPurchaseReadStatus
import com.lavasec.app.billing.PlayPurchaseSnapshot
import com.lavasec.app.billing.PlusMessage
import com.lavasec.app.foundation.subscription.FeatureLimits
import java.time.Period
import org.json.JSONArray
import org.json.JSONObject

/** Canonical RN Plus/limits fields; Play offer handles are opaque and contain no purchase token. */
internal fun plusSnapshot(
    plan: NativePlusState?, purchase: PlayPurchaseSnapshot?, ui: NativePlusPresentation?,
    edition: com.lavasec.app.edition.AppEdition = com.lavasec.app.edition.AppEdition.current,
): JSONObject =
    JSONObject().apply {
        if (edition.isFoss) {
            put("enabled", plan?.available == true)
            put("paid", false)
            put("available", plan?.available == true)
            put("busy", false)
            put("checking", false)
            put("showsYearlyPaidMonthly", false)
            put("expiration", "")
            put("message", "")
            put("store", "foss")
            put("offers", JSONArray())
            return@apply
        }
        put("enabled", plan?.localFeaturesAvailable == true)
        put("paid", plan?.hasPlus == true)
        put("available", plan?.available == true)
        put("busy", ui?.busy == true)
        put("checking", (purchase == null || purchase.status == PlayPurchaseReadStatus.NOT_CHECKED ||
            purchase.status == PlayPurchaseReadStatus.CHECKING))
        put("showsYearlyPaidMonthly", ui?.offers.orEmpty().any {
            it.installment?.initialPayments == 12 && it.phases.lastOrNull()?.billingPeriod == "P1M"
        })
        put("expiration", plan?.expiresAt?.toString().orEmpty())
        put("message", (ui?.message ?: if (purchase?.status == PlayPurchaseReadStatus.UNAVAILABLE)
            PlusMessage.VERIFICATION_UNAVAILABLE else null).displayText())
        put("store", "google-play")
        put("offers", JSONArray().apply { ui?.offers.orEmpty().forEach { put(it.presentation()) } })
    }

internal fun limitsSnapshot(limits: FeatureLimits): JSONObject = JSONObject().apply {
    put("maxAllowedDomains", limits.maxAllowedDomains)
    put("maxBlockedDomains", limits.maxBlockedDomains)
    put("maxFilterRules", limits.maxFilterRules)
    put("maxFilters", limits.maxFilters)
    put("allowsCustomBlocklists", limits.allowsCustomBlocklists)
    put("allowsCustomDNS", limits.allowsCustomDNS)
}

private fun PlayOffer.presentation(): JSONObject = JSONObject().apply {
    put("id", handle)
    put("title", title)
    put("price", phases.firstOrNull()?.formattedPrice.orEmpty())
    val schedule = phases.joinToString(", then ") { phase ->
        val period = billingPeriodText(phase.billingPeriod)
        when (phase.recurrenceMode) {
            1 -> "${phase.formattedPrice} every $period until cancelled"
            2 -> "${phase.formattedPrice} every $period for ${phase.cycles} payment${if (phase.cycles == 1) "" else "s"}"
            3 -> "${phase.formattedPrice} for $period (one payment)"
            else -> "${phase.formattedPrice} / $period"
        }
    }
    val commitment = installment?.let {
        "Initial commitment: ${it.initialPayments} payments. " +
            if (it.renewalPayments > 0) "Renewal commitment: ${it.renewalPayments} payments."
            else "No further installment commitment after the initial term."
    }.orEmpty()
    put("subtitle", listOf(schedule, commitment).filter { it.isNotEmpty() }.joinToString(" "))
    // The shared RN paywall must render Play wording instead of its Apple Account footer.
    put("terms", "Payment and subscription management are handled by Google Play. " + commitment)
}

internal fun billingPeriodText(value: String): String = runCatching {
    val period = Period.parse(value)
    require(!period.isNegative && !period.isZero)
    listOf(period.years to "year", period.months to "month", period.days to "day")
        .filter { it.first > 0 }.joinToString(" ") { (count, unit) -> "$count $unit${if (count == 1) "" else "s"}" }
}.getOrDefault(value)

private fun PlusMessage?.displayText(): String = when (this) {
    null -> ""
    PlusMessage.PURCHASE_CANCELLED -> "Purchase cancelled"
    PlusMessage.PURCHASE_PENDING -> "The Google Play purchase is pending approval."
    PlusMessage.PURCHASE_UNAVAILABLE -> "Could not complete the Google Play purchase. Please try again."
    PlusMessage.OFFER_EXPIRED -> "This offer has changed. Refresh the available plans and try again."
    PlusMessage.VERIFICATION_UNAVAILABLE -> "Could not verify purchases. Your saved plan has been preserved."
    PlusMessage.RESTORED -> "Lava Security Plus purchases restored."
    PlusMessage.NO_ACTIVE_PURCHASE -> "No active Lava Security Plus purchase was found."
    PlusMessage.PRODUCTS_UNAVAILABLE -> "Google Play plans are currently unavailable."
}
