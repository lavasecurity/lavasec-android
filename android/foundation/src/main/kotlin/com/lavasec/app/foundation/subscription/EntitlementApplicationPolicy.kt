package com.lavasec.app.foundation.subscription

/** Direct port of LavaSecKit/EntitlementApplicationPolicy.swift (iOS c4658d9). */
enum class EntitlementReadingConfidence { CONFIRMED, UNCONFIRMED }

object EntitlementApplicationPolicy {
    /** A cold/offline empty read is not evidence that a previously verified purchase lapsed. */
    fun resolvedHasLavaSecurityPlus(
        computed: Boolean,
        persisted: Boolean,
        confidence: EntitlementReadingConfidence,
    ): Boolean = if (persisted && !computed && confidence == EntitlementReadingConfidence.UNCONFIRMED) {
        persisted
    } else {
        computed
    }

    /** Deliver a real lapse even when an earlier unconfirmed empty read had the same value. */
    fun shouldDeliverReading(
        valueChanged: Boolean,
        newConfidence: EntitlementReadingConfidence,
        previousConfidence: EntitlementReadingConfidence,
    ): Boolean = valueChanged || (
        newConfidence == EntitlementReadingConfidence.CONFIRMED &&
            previousConfidence == EntitlementReadingConfidence.UNCONFIRMED
        )
}
