package com.lavasec.app.data

import com.lavasec.app.foundation.filter.CustomBlocklistSource
import com.lavasec.app.foundation.filter.FilterSourceCategory

/** One selectable catalog source as the shared "Choose Blocklists" picker renders it. */
internal data class CatalogQuerySource(
    val id: String,
    val name: String,
    val licenseName: String,
    val sourceUrl: String,
    val metadata: String?,
)

/** One category section of the catalog picker. */
internal data class CatalogQuerySection(
    val title: String,
    val isCustom: Boolean,
    val sources: List<CatalogQuerySource>,
)

/**
 * The canonical `catalog.query` response (`ReactNative/review/FilterScreens.tsx` `CatalogResponse`).
 *
 * Android has no per-source rule-count compile, so `count` is always the honest `0` and the bar is
 * [indeterminate]; the picker still renders the real sections and every source row. No row, count or
 * budget is invented: sections come from the pinned/installed catalog the filter authority compiles
 * against, and a missing metric stays unknown rather than fabricated.
 */
internal data class CatalogQueryResult(
    val sections: List<CatalogQuerySection>,
    val count: Int,
    val budget: Int,
    val pending: Int,
    val exceeded: Boolean,
    val summary: String,
    val fraction: Double,
    val indeterminate: Boolean,
    val atOrOverBudget: Boolean,
)

/**
 * Read-only projection of the effective blocklist catalog for the shared blocklist picker. The
 * catalog is the one the filter authority already compiles against (bundled, or a validated
 * last-known-good install); this owner never fetches, mutates or re-enables anything.
 *
 * The selection is bounded on purpose: a malformed/huge catalog can never produce an unbounded
 * response. Sections follow the catalog's authored category order, and every emitted source is a
 * real catalog entry with its authored name/license/URL.
 */
internal class CatalogQueryOwner(private val catalog: BundledBlocklistCatalog) {
    fun query(
        ids: List<String>,
        budget: Int,
        customSources: List<CustomBlocklistSource> = emptyList(),
    ): CatalogQueryResult {
        val known = catalog.sources.mapTo(HashSet()) { it.id }
        customSources.forEach { known += it.id }
        val selected = ids.count { it in known }
        val sections = ArrayList<CatalogQuerySection>()
        var emitted = 0
        for (categoryId in catalog.categories) {
            if (emitted >= MAX_SOURCES || sections.size >= MAX_SECTIONS) break
            val rows = ArrayList<CatalogQuerySource>()
            for (source in catalog.sources) {
                if (source.category.wireId != categoryId) continue
                if (emitted + rows.size >= MAX_SOURCES) break
                rows += CatalogQuerySource(
                    id = source.id,
                    name = source.name,
                    licenseName = source.licenseName,
                    sourceUrl = source.sourceUrl,
                    metadata = source.entryCount?.takeIf { it > 0 }?.let { "$it rules" },
                )
            }
            if (rows.isEmpty()) continue
            emitted += rows.size
            sections += CatalogQuerySection(
                title = catalog.categoryLabels[categoryId]?.takeIf { it.isNotBlank() }
                    ?: FALLBACK_LABELS[categoryId]
                    ?: "Blocklists",
                isCustom = false,
                sources = rows,
            )
        }
        // The user's own lists render after the curated categories (iOS "Your Lists"), each with a
        // delete affordance; they are real durable definitions, never fabricated rows.
        if (customSources.isNotEmpty() && sections.size < MAX_SECTIONS) {
            val rows = customSources.take(MAX_SOURCES - emitted).map { source ->
                CatalogQuerySource(
                    id = source.id,
                    name = source.displayName,
                    licenseName = "Custom List",
                    sourceUrl = source.sourceUrl,
                    metadata = "Pending refresh",
                )
            }
            if (rows.isNotEmpty()) {
                emitted += rows.size
                sections += CatalogQuerySection(
                    title = "Your Lists",
                    isCustom = true,
                    sources = rows,
                )
            }
        }
        return CatalogQueryResult(
            sections = sections,
            count = 0,
            budget = budget.coerceAtLeast(0),
            pending = 0,
            exceeded = false,
            summary = "$selected of ${catalog.sources.size + customSources.size} lists selected",
            fraction = 0.0,
            indeterminate = true,
            atOrOverBudget = false,
        )
    }

    private companion object {
        /** Response bounds; the shipping catalog is far smaller, so this only caps malformed input. */
        private const val MAX_SOURCES = 500
        private const val MAX_SECTIONS = 50

        /** English labels matching the bundled catalog for a taxonomy the anchor does not label. */
        private val FALLBACK_LABELS = mapOf(
            "security" to "Security & Threat Intel",
            "multi_purpose" to "Multi-purpose",
            "ads_tracking" to "Ads & Trackers",
            "social" to "Social Media",
            "nsfw" to "Adult Content",
            "gambling" to "Gambling",
            "piracy" to "Piracy & Torrent",
        )
    }
}

/** The catalog's authored category id for a source category (matches the bundled/bundled-derived ids). */
private val FilterSourceCategory.wireId: String get() = name.lowercase()
