package com.lavasec.app.product

import com.lavasec.app.foundation.resolver.ChainedDnsEditState
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.ResolverEditRejection
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.NativeFilterPublicationGuard

/** Captured native chain authority; publication must revalidate it under the real store locks. */
data class QaDnsPublicationAdmission(
    val chained: ChainedDnsEditState,
    val guard: NativeFilterPublicationGuard,
)

/** Minted only after the ordinary resolver policy accepts the whole ladder under admission. */
class ReviewedQaResolverEdit internal constructor(
    val expectedDnsContext: String,
    val target: FilterResolverPlan,
    val limits: FeatureLimits,
    val publicationGuard: NativeFilterPublicationGuard,
)

sealed interface QaCompositeConfigurationResult {
    data class Applied(val generation: Long) : QaCompositeConfigurationResult
    data class Refused(val rejection: ResolverEditRejection? = null) : QaCompositeConfigurationResult
    data object Unavailable : QaCompositeConfigurationResult
    /** A configuration committed; a later privacy/cleanup stage needs reconciliation. */
    data object CommittedReconciliationRequired : QaCompositeConfigurationResult
}
