package com.lavasec.app.foundation.catalog

/**
 * Freshness window for a cached remote catalog, ported from iOS `BlocklistCatalogFreshnessPolicy`.
 *
 * The one-week default is the window inside which a cached catalog may be reused without a
 * network refresh. A missing age is treated as fresh (the cache exists but its mtime is
 * unavailable); an error status or a negative age is never fresh.
 */
class CatalogFreshnessPolicy(
    val maxAgeSeconds: Long = ONE_WEEK_SECONDS,
) {
    init {
        require(maxAgeSeconds > 0) { "catalog freshness maxAge must be positive" }
    }

    fun isFresh(ageSeconds: Long?, statusIsError: Boolean): Boolean {
        if (statusIsError) return false
        val age = ageSeconds ?: return true
        return age >= 0 && age < maxAgeSeconds
    }

    fun needsRefresh(ageSeconds: Long?, statusIsError: Boolean): Boolean =
        !isFresh(ageSeconds, statusIsError)

    companion object {
        /** Default evaluation window of one week, matching iOS. */
        const val ONE_WEEK_SECONDS = 7L * 24L * 60L * 60L
    }
}
