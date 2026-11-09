package com.lavasec.app.reactnative

import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.security.SecurityOwnerState

/** Facts sampled from the actual native window and live security owner, never supplied by RN. */
internal data class NativeQueryReadAuthority(
    val host: Any?,
    val nativeHostID: String?,
    val generation: Long?,
    val foreground: Boolean,
    val destroyed: Boolean,
    val security: SecurityOwnerState?,
    val displayClearRevision: Long,
)

/**
 * A query can suspend while the independent mutation lane retires its private data. A fresh
 * authorization after that retirement belongs to a new read, never to this captured request.
 * Activity pause and every view-turn revocation advance the real SecurityOwner revision, even
 * with all protection choices off; a missing security owner never admits a query.
 */
internal class NativeQueryReadFence(private val captured: NativeQueryReadAuthority, private val surface: ProtectedSurface = ProtectedSurface.ACTIVITY) {
    fun rejection(current: NativeQueryReadAuthority): String? {
        unavailable(captured)?.let { return it }
        if (current.host !== captured.host || current.nativeHostID != captured.nativeHostID || current.destroyed) {
            return "stale-host"
        }
        if (current.generation != captured.generation) return "stale-generation"
        unavailable(current)?.let { return it }
        if (current.security?.authenticationRevision != captured.security?.authenticationRevision ||
            current.displayClearRevision != captured.displayClearRevision) return "stale-query"
        return null
    }

    /** Run inside the native query lane; the caller encodes the outcome without another await. */
    suspend fun read(
        current: () -> NativeQueryReadAuthority,
        query: suspend () -> QueryOutcome,
    ): QueryOutcome {
        rejection(current())?.let { return QueryOutcome.Rejected(it) }
        val outcome = query()
        return rejection(current())?.let { QueryOutcome.Rejected(it) } ?: outcome
    }

    private fun unavailable(authority: NativeQueryReadAuthority): String? = when {
        authority.host == null || authority.nativeHostID.isNullOrEmpty() || authority.destroyed -> "stale-host"
        authority.generation == null -> "stale-generation"
        !authority.foreground -> "background"
        authority.security?.isAuthorized(ProtectedSurface.APP_UNLOCK) != true -> "app-locked"
        authority.security?.isAuthorized(surface) != true -> if (surface == ProtectedSurface.FILTERS) "filters-locked" else "activity-locked"
        else -> null
    }
}
