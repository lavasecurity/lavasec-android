package com.lavasec.app.reactnative

import com.lavasec.app.foundation.filter.FilterIdentityPolicy
import com.lavasec.app.foundation.filter.FilterLibraryEditSession

/** One captured whole-library view supplies both form identity and name validation. */
internal class NativeLibraryRenameContext(
    val session: FilterLibraryEditSession,
    private val filterId: String,
) {
    private val displayed = session.displayedFilters.toList()
    val target = displayed.firstOrNull { it.id == filterId }

    fun isCurrent(current: FilterLibraryEditSession?): Boolean = current === session

    fun isNameAvailable(name: String): Boolean {
        val canonical = FilterIdentityPolicy.normalizedName(name)
        return displayed.none { it.id != filterId &&
            FilterIdentityPolicy.normalizedName(it.name).equals(canonical, ignoreCase = true)
        }
    }
}
