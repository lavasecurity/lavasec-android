package com.lavasec.app.vpn.chained

/**
 * How a chained runtime ended, as the control plane must act on it.
 *
 * A [Failed] means the engine/data path stopped, but [ChainedTerminationPolicy] may treat a
 * recoverable transport failure as a fallback: peer-socket loss on a network handoff is not a
 * terminal condition. A [FallBackToDnsOnly] is NOT a failure — the outage budget is spent and the
 * session must give way to the unchanged DNS-only tunnel so filtering continues; conflating the two
 * would disable DNS protection on an upstream outage instead of falling back.
 */
internal sealed interface ChainedTunTermination {

    /**
     * The engine or data path failed. Whether this is terminal or recoverable is decided by
     * [ChainedTerminationPolicy], not by the failure alone.
     */
    data class Failed(val failure: ChainedRuntimeFailure) : ChainedTunTermination

    /** The outage budget is spent; chaining must give way to the DNS-only tunnel. */
    data object FallBackToDnsOnly : ChainedTunTermination
}

/** What the control plane must do when a chained runtime ends. */
internal enum class ChainedTerminationAction {
    /** Protection cannot continue; publish the terminal failure and tear the runtime down. */
    TERMINATE_PROTECTION,

    /** Rebuild the unchanged DNS-only tunnel; filtering must continue. */
    REBUILD_DNS_ONLY,
}

/** The single mapping from a chained termination to the control-plane action. */
internal object ChainedTerminationPolicy {
    fun actionFor(termination: ChainedTunTermination): ChainedTerminationAction =
        when (termination) {
            is ChainedTunTermination.Failed -> when (termination.failure) {
                // A network handoff or transient underlay loss makes the bound peer socket fail to
                // send/receive. That is recoverable transport loss, not a terminal data-path
                // failure: fall back to the unchanged DNS-only tunnel (reselecting a network)
                // instead of stopping protection and leaving the user unprotected.
                ChainedRuntimeFailure.PEER_SOCKET_FAILED ->
                    ChainedTerminationAction.REBUILD_DNS_ONLY

                // A scheduled retry whose factory refused a session (a revoked entitlement or an
                // ineligible device) has no engine to recover, but the same refusal during initial
                // startup falls back to DNS-only. Surrender to the working DNS-only tunnel rather
                // than stopping all protection.
                ChainedRuntimeFailure.AUTHORIZATION_REFUSED ->
                    ChainedTerminationAction.REBUILD_DNS_ONLY

                ChainedRuntimeFailure.ENGINE_UNAVAILABLE,
                ChainedRuntimeFailure.ENGINE_FAILED,
                ChainedRuntimeFailure.TUN_READ_FAILED,
                ChainedRuntimeFailure.TUN_WRITE_FAILED,
                -> ChainedTerminationAction.TERMINATE_PROTECTION
            }

            ChainedTunTermination.FallBackToDnsOnly -> ChainedTerminationAction.REBUILD_DNS_ONLY
        }
}
