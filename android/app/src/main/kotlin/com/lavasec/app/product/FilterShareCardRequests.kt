package com.lavasec.app.product

import java.util.UUID

/** One ephemeral render capability, bound to native product facts and the exact foreground host. */
internal class FilterShareCardRequests(private val newToken: () -> String = { UUID.randomUUID().toString() }) {
    data class Request(
        val token: String, val content: FilterShareContent.Ready, val generation: Long?,
        val authenticationRevision: Long, val host: Any, val locale: String,
    )
    private var current: Request? = null

    @Synchronized
    fun issue(content: FilterShareContent.Ready, generation: Long?, authenticationRevision: Long,
              host: Any, locale: String): Request {
        val previous = current
        if (previous != null && previous.content == content && previous.generation == generation &&
            previous.authenticationRevision == authenticationRevision && previous.host === host &&
            previous.locale == locale) return previous
        return Request(newToken(), content, generation, authenticationRevision, host, locale).also { current = it }
    }

    @Synchronized
    fun accepted(token: String, filterId: String, generation: Long?, authenticationRevision: Long,
                 host: Any, locale: String, content: FilterShareContent): Request? = current?.takeIf {
        it.token == token && it.content.filterId == filterId && it.content == content &&
            it.generation == generation && it.authenticationRevision == authenticationRevision &&
            it.host === host && it.locale == locale
    }

    /**
     * Mirrors iOS `LavaShareCardSurfaceRegistry.reauthorize`: returns the live grant's payload
     * while [token] is still an admitted export capability, or null once the grant has been
     * retired. A transient surface unmount must not retire this grant, so a remounted surface
     * re-admits against the still-live payload instead of leaving Share enabled but refused.
     */
    @Synchronized fun authorizedPayload(token: String): String? =
        current?.takeIf { it.token == token }?.content?.url

    @Synchronized fun revoke(token: String? = null) { if (token == null || current?.token == token) current = null }
}
