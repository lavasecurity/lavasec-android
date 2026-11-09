package com.lavasec.app.vpn.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Network recovery must complete a pending chained-surrender fallback as DNS-only instead of
 * reopening chained credentials (which could immediately reinstall the surrendered mode).
 */
class ChainedAwaitingNetworkPolicyTest {

    @Test
    fun `a pending DNS-only fallback rebuilds DNS-only`() {
        assertEquals(
            ChainedAwaitingNetworkAction.RebuildDnsOnly,
            ChainedAwaitingNetworkPolicy.actionFor(
                pendingDnsOnlyRebuild = true,
                hasRuntime = false,
            ),
        )
        assertEquals(
            ChainedAwaitingNetworkAction.RebuildDnsOnly,
            ChainedAwaitingNetworkPolicy.actionFor(
                pendingDnsOnlyRebuild = true,
                hasRuntime = true,
            ),
        )
    }

    @Test
    fun `an ordinary awaiting state starts or reconnects`() {
        assertEquals(
            ChainedAwaitingNetworkAction.StartInitialRuntime,
            ChainedAwaitingNetworkPolicy.actionFor(
                pendingDnsOnlyRebuild = false,
                hasRuntime = false,
            ),
        )
        assertEquals(
            ChainedAwaitingNetworkAction.ReconnectRunningRuntime,
            ChainedAwaitingNetworkPolicy.actionFor(
                pendingDnsOnlyRebuild = false,
                hasRuntime = true,
            ),
        )
    }

    @Test
    fun `only the initial-runtime recovery must preflight the chained endpoint`() {
        // Recovery into the initial runtime bypasses the command owner's preflight, so it must run
        // one itself or a hostname endpoint is refused and chaining silently degrades to DNS-only.
        assertTrue(
            ChainedAwaitingNetworkPolicy.requiresEndpointPreflight(
                ChainedAwaitingNetworkAction.StartInitialRuntime,
            ),
        )
        // These install no chained session, so they need no endpoint preflight.
        assertFalse(
            ChainedAwaitingNetworkPolicy.requiresEndpointPreflight(
                ChainedAwaitingNetworkAction.RebuildDnsOnly,
            ),
        )
        assertFalse(
            ChainedAwaitingNetworkPolicy.requiresEndpointPreflight(
                ChainedAwaitingNetworkAction.ReconnectRunningRuntime,
            ),
        )
    }
}
