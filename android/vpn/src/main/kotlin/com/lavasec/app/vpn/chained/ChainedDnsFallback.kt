package com.lavasec.app.vpn.chained

import com.lavasec.app.vpn.ResolverResult
import com.lavasec.app.vpn.DnsFallbackQueryContext

/**
 * One cold, saved-DNS route bound to the chained activation's physical network and epoch.
 * Only split-tunnel activations with explicit chain fallback consent may construct it.
 * Completion may arrive on any thread; the chained owner marshals it onto its engine queue.
 * Closing a query handle cancels that query. Closing the route revokes all its owned work.
 */
internal interface ChainedDnsFallback : AutoCloseable {
    val context: DnsFallbackQueryContext get() = DnsFallbackQueryContext()
    fun resolve(query: ByteArray, remainingNanos: Long, portalHosts: Set<String>, requiresPortalAdmission: Boolean = false, completion: (ResolverResult) -> Unit): AutoCloseable = resolve(query, completion)
    fun resolve(query: ByteArray, completion: (ResolverResult) -> Unit): AutoCloseable
}
