package com.lavasec.app.vpn.chained

/** What the runtime must do when an eligible network becomes available while it awaits one. */
internal sealed interface ChainedAwaitingNetworkAction {
    /** Complete a pending chained-surrender fallback as DNS-only. */
    data object RebuildDnsOnly : ChainedAwaitingNetworkAction

    /** Start the initial runtime, honouring the durable gate (which may be chained). */
    data object StartInitialRuntime : ChainedAwaitingNetworkAction

    /** A runtime already exists; rebuild it across the network change. */
    data object ReconnectRunningRuntime : ChainedAwaitingNetworkAction
}

/**
 * The decision for network recovery while awaiting a network.
 *
 * A chained outage surrender that coincides with network loss must complete as DNS-only: without a
 * pending-DNS-only signal, the default "start the initial runtime" path would reopen chained
 * credentials and can immediately reinstall the mode that just surrendered.
 */
internal object ChainedAwaitingNetworkPolicy {
    fun actionFor(
        pendingDnsOnlyRebuild: Boolean,
        hasRuntime: Boolean,
    ): ChainedAwaitingNetworkAction = when {
        pendingDnsOnlyRebuild -> ChainedAwaitingNetworkAction.RebuildDnsOnly
        !hasRuntime -> ChainedAwaitingNetworkAction.StartInitialRuntime
        else -> ChainedAwaitingNetworkAction.ReconnectRunningRuntime
    }

    /**
     * Whether recovering into [action] must preflight the chained endpoint before it starts.
     *
     * The command-owner start path preflights before start;
     * [ChainedAwaitingNetworkAction.StartInitialRuntime] is reached only from the runtime's own
     * awaiting-network recovery, so it must preflight or a hostname endpoint is refused as
     * [ChainedPeerPreflightOutcome.NOT_ATTEMPTED] and chaining silently degrades to DNS-only with
     * no later attempt. The other actions rebuild DNS-only or reinstall a running runtime, neither
     * of which installs a chained session.
     */
    fun requiresEndpointPreflight(action: ChainedAwaitingNetworkAction): Boolean = when (action) {
        ChainedAwaitingNetworkAction.StartInitialRuntime -> true
        ChainedAwaitingNetworkAction.RebuildDnsOnly,
        ChainedAwaitingNetworkAction.ReconnectRunningRuntime,
        -> false
    }
}
