package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.config.ProtectionLevel
import java.util.Collections
import java.util.LinkedHashSet

class FilterProfile(
    val id: String,
    val name: String,
    val protectionLevel: ProtectionLevel,
    enabledSourceIds: Set<String>,
    emoji: String? = null,
) {
    val enabledSourceIds: Set<String> = immutableSet(enabledSourceIds)

    /**
     * The filter's emoji identity. An invalid or missing value falls back to the canonical
     * deterministic [FilterIdentityPolicy.defaultEmoji] so a stored profile always has exactly
     * one renderable identity.
     */
    val emoji: String = emoji?.takeIf(FilterIdentityPolicy::isValidEmoji)
        ?: FilterIdentityPolicy.defaultEmoji(id = id, name = name)

    init {
        require(id.isNotBlank()) { "filter profile id must not be blank" }
        require(name.isNotBlank()) { "filter profile name must not be blank" }
        require(this.enabledSourceIds.none(String::isBlank)) { "source id must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is FilterProfile &&
            id == other.id &&
            name == other.name &&
            protectionLevel == other.protectionLevel &&
            enabledSourceIds == other.enabledSourceIds &&
            emoji == other.emoji

    override fun hashCode(): Int =
        listOf(id, name, protectionLevel, enabledSourceIds, emoji).hashCode()

    companion object {
        /**
         * Derives the three cumulative iOS-shaped defaults from the signed catalog metadata.
         * Source membership is never repeated in the profile definition:
         * Core is the default-enabled Security subset, Balanced is every catalog default,
         * and Extra is Balanced plus the entire Ads & Tracking category.
         */
        fun seededDefaults(catalog: FilterCatalogSnapshot): Map<ProtectionLevel, FilterProfile> {
            val defaults = catalog.sources
                .asSequence()
                .filter(CatalogFilterSource::defaultEnabled)
                .map(CatalogFilterSource::id)
                .toCollection(linkedSetOf())
            val coreSources = catalog.sources
                .asSequence()
                .filter { it.defaultEnabled && it.category == FilterSourceCategory.SECURITY }
                .map(CatalogFilterSource::id)
                .toCollection(linkedSetOf())
            val extraSources = LinkedHashSet(defaults).apply {
                addAll(catalog.sources.filter { it.category == FilterSourceCategory.ADS_TRACKING }.map { it.id })
            }

            return Collections.unmodifiableMap(linkedMapOf(
                ProtectionLevel.CORE to FilterProfile(
                    id = stableId(ProtectionLevel.CORE),
                    name = displayName(ProtectionLevel.CORE),
                    protectionLevel = ProtectionLevel.CORE,
                    enabledSourceIds = coreSources,
                ),
                ProtectionLevel.BALANCED to FilterProfile(
                    id = stableId(ProtectionLevel.BALANCED),
                    name = displayName(ProtectionLevel.BALANCED),
                    protectionLevel = ProtectionLevel.BALANCED,
                    enabledSourceIds = defaults,
                ),
                ProtectionLevel.EXTRA to FilterProfile(
                    id = stableId(ProtectionLevel.EXTRA),
                    name = displayName(ProtectionLevel.EXTRA),
                    protectionLevel = ProtectionLevel.EXTRA,
                    enabledSourceIds = extraSources,
                ),
            ))
        }

        /**
         * Commit-order compatibility for the pre-catalog app. These IDs reproduce the previous
         * hard-coded seed exactly; production filter-library creation must use [seededDefaults]
         * with its verified signed-catalog snapshot instead.
         */
        fun seededDefaults(): Map<ProtectionLevel, FilterProfile> {
            return seededDefaults(legacyCatalogProfileInputs())
        }

        fun forLevel(level: ProtectionLevel): FilterProfile = seededDefaults().getValue(level)

        fun stableId(level: ProtectionLevel): String = level.seededFilterId

        fun displayName(level: ProtectionLevel): String = when (level) {
            ProtectionLevel.CORE -> "Core"
            ProtectionLevel.BALANCED -> "Balanced"
            ProtectionLevel.EXTRA -> "Extra"
        }

        private fun legacyCatalogProfileInputs(): FilterCatalogSnapshot = FilterCatalogSnapshot(listOf(
            CatalogFilterSource("blocklistproject-basic", FilterSourceCategory.SECURITY, defaultEnabled = true),
            CatalogFilterSource("stevenblack-unified", FilterSourceCategory.MULTI_PURPOSE, defaultEnabled = true),
            CatalogFilterSource("blocklistproject-ads", FilterSourceCategory.ADS_TRACKING, defaultEnabled = false),
            CatalogFilterSource("blocklistproject-tracking", FilterSourceCategory.ADS_TRACKING, defaultEnabled = false),
        ))
    }
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
