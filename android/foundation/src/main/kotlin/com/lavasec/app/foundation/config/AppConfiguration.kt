package com.lavasec.app.foundation.config

import com.lavasec.app.foundation.resolver.ResolverConfiguration

enum class ProtectionLevel(val seededFilterId: String) {
    CORE("filter-essential"),
    BALANCED("filter-balanced"),
    EXTRA("filter-comprehensive"),
}

data class AppConfiguration(
    /**
     * The onboarding protection level. It pins the seeded filter chosen during setup and the
     * level of that seeded default; switching to a user-created filter does not move it, so it
     * never has to describe a filter that has no level (iOS keeps the onboarding level separate
     * from the saved filter library the same way).
     */
    val protectionLevel: ProtectionLevel,
    val resolver: ResolverConfiguration,
    /**
     * The active filter id — a seeded id during onboarding, and afterwards any saved filter's
     * id. The filter library itself is the authoritative filter-configuration record's truth;
     * this field only records which of its filters is in effect.
     */
    val activeFilterId: String,
    val keepCounts: Boolean,
    val keepHistory: Boolean,
) {
    init {
        require(activeFilterId.isNotBlank()) { "active filter id must not be blank" }
    }

    companion object {
        fun default(): AppConfiguration = AppConfiguration(
            protectionLevel = ProtectionLevel.BALANCED,
            resolver = ResolverConfiguration(),
            activeFilterId = ProtectionLevel.BALANCED.seededFilterId,
            keepCounts = true,
            keepHistory = true,
        )
    }
}
