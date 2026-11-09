package com.lavasec.app.foundation.filter

import java.util.Collections

enum class FilterSourceCategory {
    SECURITY,
    MULTI_PURPOSE,
    ADS_TRACKING,
    SOCIAL,
    NSFW,
    GAMBLING,
    PIRACY,

    /** A user-supplied custom blocklist, not part of the signed catalog. */
    CUSTOM,
}

data class CatalogFilterSource(
    val id: String,
    val category: FilterSourceCategory,
    val defaultEnabled: Boolean,
) {
    init {
        require(id.isNotBlank()) { "catalog source id must not be blank" }
    }
}

/** Portable, immutable projection of the signed catalog fields used to derive profiles. */
class FilterCatalogSnapshot(sources: List<CatalogFilterSource>) {
    val sources: List<CatalogFilterSource> = Collections.unmodifiableList(sources.toList())

    /** The pinned iOS catalog has no non-allowable guardrail feed. */
    val guardrailSources: List<CatalogFilterSource> = emptyList()

    init {
        require(this.sources.map(CatalogFilterSource::id).distinct().size == this.sources.size) {
            "catalog source ids must be unique"
        }
    }
}
