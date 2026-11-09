package com.lavasec.app.vpn.chained

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The control-plane action for each kind of chained termination: a hard failure cannot continue
 * protection, while an outage surrender must rebuild the unchanged DNS-only tunnel.
 */
class ChainedTerminationPolicyTest {

    @Test
    fun `a hard failure terminates protection`() {
        val termination = ChainedTunTermination.Failed(ChainedRuntimeFailure.ENGINE_FAILED)
        assertEquals(
            ChainedTerminationAction.TERMINATE_PROTECTION,
            ChainedTerminationPolicy.actionFor(termination),
        )
    }

    @Test
    fun `an outage surrender rebuilds DNS-only`() {
        assertEquals(
            ChainedTerminationAction.REBUILD_DNS_ONLY,
            ChainedTerminationPolicy.actionFor(ChainedTunTermination.FallBackToDnsOnly),
        )
    }

    @Test
    fun `a peer-socket transport failure rebuilds DNS-only instead of stopping protection`() {
        // A Wi-Fi/cellular handoff or transient underlay loss fails the bound peer socket; that is
        // recoverable, so protection must fall back to DNS-only rather than stop.
        val termination = ChainedTunTermination.Failed(ChainedRuntimeFailure.PEER_SOCKET_FAILED)
        assertEquals(
            ChainedTerminationAction.REBUILD_DNS_ONLY,
            ChainedTerminationPolicy.actionFor(termination),
        )
    }

    @Test
    fun `a retry authorization refusal rebuilds DNS-only instead of stopping protection`() {
        // A scheduled retry whose factory refused a session (a revoked entitlement or an ineligible
        // device) has no engine to recover, but the same refusal during initial startup falls back
        // to DNS-only; it must not shut down all VPN protection.
        val termination =
            ChainedTunTermination.Failed(ChainedRuntimeFailure.AUTHORIZATION_REFUSED)
        assertEquals(
            ChainedTerminationAction.REBUILD_DNS_ONLY,
            ChainedTerminationPolicy.actionFor(termination),
        )
    }

    @Test
    fun `each remaining hard failure terminates protection`() {
        val hardFailures = listOf(
            ChainedRuntimeFailure.ENGINE_UNAVAILABLE,
            ChainedRuntimeFailure.ENGINE_FAILED,
            ChainedRuntimeFailure.TUN_READ_FAILED,
            ChainedRuntimeFailure.TUN_WRITE_FAILED,
        )
        hardFailures.forEach { failure ->
            assertEquals(
                failure.name,
                ChainedTerminationAction.TERMINATE_PROTECTION,
                ChainedTerminationPolicy.actionFor(ChainedTunTermination.Failed(failure)),
            )
        }
    }
}
