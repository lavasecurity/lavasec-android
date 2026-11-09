package com.lavasec.app.foundation.share

/**
 * Whether a filter can be shared, and if not, which honest reason applies. Mirrors the canonical
 * iOS `AppViewModel.isFilterShareable` predicate (pinned `c4658d9`): a filter is shareable only
 * when it has something to share, carries no private source parameters, and fits the shareable
 * code capacity.
 */
sealed interface FilterShareEligibility {
    data object Shareable : FilterShareEligibility

    /** Nothing to share: no blocklists, blocked domains, custom sources, or exceptions. */
    data object Empty : FilterShareEligibility

    /** A custom source carries a query, fragment, or userinfo that must not leave the device. */
    data object ContainsPrivateSourceParameters : FilterShareEligibility

    /** The setup is too large for even the higher (copyable-code) capacity. */
    data object ExceedsShareCapacity : FilterShareEligibility
}

object FilterShareEligibilityPolicy {
    fun evaluate(configuration: ShareableFilterConfiguration): FilterShareEligibility = when {
        configuration.isEmpty -> FilterShareEligibility.Empty
        configuration.containsPrivateSourceParameters ->
            FilterShareEligibility.ContainsPrivateSourceParameters
        !configuration.fitsShareableCodeCapacity() -> FilterShareEligibility.ExceedsShareCapacity
        else -> FilterShareEligibility.Shareable
    }

    /** Convenience predicate mirroring `isFilterShareable`. */
    fun isShareable(configuration: ShareableFilterConfiguration): Boolean =
        evaluate(configuration) == FilterShareEligibility.Shareable
}
