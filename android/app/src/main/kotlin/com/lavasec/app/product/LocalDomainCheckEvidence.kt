package com.lavasec.app.product

import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.platform.ConfirmedFilterState

/** Immutable evidence copied while the exact compiled artifact's native lease is open. */
data class LocalDomainCheckEvidence(val domain: String, val decision: FilterDecision, val confirmed: ConfirmedFilterState) {
    val revision: String get() = "${confirmed.configurationIdentity.generation}:${confirmed.configurationIdentity.fingerprint}"
}
