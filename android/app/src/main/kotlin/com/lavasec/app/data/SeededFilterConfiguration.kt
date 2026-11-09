package com.lavasec.app.data

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint

/** Exact signed-catalog filter-library value corresponding to an app configuration. */
internal data class SeededFilterConfiguration(
    val draft: FilterLibraryDraft,
    val identity: ConfigurationIdentity,
) {
    companion object {
        fun from(
            configuration: AppConfiguration,
            catalog: BundledBlocklistCatalog,
            generation: Long,
        ): SeededFilterConfiguration {
            require(generation > 0L)
            val resolverPlan = requireNotNull(
                FilterResolverPlan.from(configuration.resolver),
            ) { "app configuration uses a resolver plan unavailable to the runtime" }
            val draft = FilterLibraryDraft.seededDefaults(catalog.filterSnapshot)
                .withActiveProfile(configuration.activeFilterId)
                .withResolverPlan(resolverPlan)
            val active = requireNotNull(draft.profile(draft.activeProfileId))
            require(active.protectionLevel == configuration.protectionLevel) {
                "app protection level and signed-catalog profile do not match"
            }
            return SeededFilterConfiguration(
                draft = draft,
                identity = ConfigurationIdentity(
                    generation = generation,
                    fingerprint = canonicalFilterConfigurationFingerprint(
                        configuration = draft,
                        catalogRevision = catalog.revision,
                        catalogSha256 = catalog.sha256,
                    ),
                ),
            )
        }
    }
}
