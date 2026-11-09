package com.lavasec.app.vpn.chained

/**
 * The network generation to use when rebuilding DNS-only after a chained surrender.
 *
 * A chained session can surrender because its underlay vanished (a handoff or loss), so the
 * network captured when the chained session started cannot be trusted for the rebuild: it may be
 * defunct, or pin DNS resolution to a dead underlay. The caller re-runs the eligibility selection
 * and uses this policy to fence the DNS engine to the new underlay. A missing network means the
 * runtime must await one rather than rebuild against a dead underlay.
 */
internal object ChainedSurrenderNetworkPolicy {

    /**
     * @param networkAvailable whether an eligible network was reselected.
     * @param sameUnderlyingNetwork whether the reselected network is the one the chained session
     *   used.
     * @param currentGeneration the runtime's current network generation.
     * @return the generation for the DNS-only rebuild, or `null` when no network is available and
     *   the caller must await one.
     */
    fun dnsOnlyRebuildGeneration(
        networkAvailable: Boolean,
        sameUnderlyingNetwork: Boolean,
        currentGeneration: Long,
    ): Long? = when {
        !networkAvailable -> null
        sameUnderlyingNetwork -> currentGeneration
        else -> Math.addExact(currentGeneration, 1L)
    }
}
