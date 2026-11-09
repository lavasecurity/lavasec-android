package com.lavasec.app.vpn.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The DNS-only rebuild must be fenced to a freshly reselected network: no network awaits, the same
 * underlay keeps the generation, and a new underlay bumps it.
 */
class ChainedSurrenderNetworkPolicyTest {

    @Test
    fun `no eligible network means await, not rebuild`() {
        assertNull(
            ChainedSurrenderNetworkPolicy.dnsOnlyRebuildGeneration(
                networkAvailable = false,
                sameUnderlyingNetwork = true,
                currentGeneration = 7L,
            ),
        )
    }

    @Test
    fun `the same underlay keeps the network generation`() {
        assertEquals(
            7L,
            ChainedSurrenderNetworkPolicy.dnsOnlyRebuildGeneration(
                networkAvailable = true,
                sameUnderlyingNetwork = true,
                currentGeneration = 7L,
            ),
        )
    }

    @Test
    fun `a new underlay bumps the network generation`() {
        assertEquals(
            8L,
            ChainedSurrenderNetworkPolicy.dnsOnlyRebuildGeneration(
                networkAvailable = true,
                sameUnderlyingNetwork = false,
                currentGeneration = 7L,
            ),
        )
    }
}
