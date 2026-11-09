package com.lavasec.app.foundation.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chained resolver-egress policy: plain UDP is carried, everything else fails closed. */
class ChainedResolverEgressTest {

    @Test
    fun `plain and device DNS are carried through the tunnel`() {
        listOf(
            ChainedResolverTransport.PLAIN_DNS,
            ChainedResolverTransport.DEVICE_DNS,
        ).forEach { transport ->
            val egress = ChainedResolverEgressPolicy.egressFor(transport)
            assertEquals(ChainedResolverEgress.ThroughTunnelToUpstreamResolver, egress)
            assertTrue(ChainedResolverEgressPolicy.permitsQuery(egress))
        }
    }

    @Test
    fun `the encrypted presets fail closed with SERVFAIL`() {
        listOf(
            ChainedResolverTransport.DNS_OVER_HTTPS,
            ChainedResolverTransport.DNS_OVER_TLS,
            ChainedResolverTransport.DNS_OVER_QUIC,
        ).forEach { transport ->
            val egress = ChainedResolverEgressPolicy.egressFor(transport)
            val unavailable = egress as ChainedResolverEgress.UnavailableWhileChained
            assertEquals(
                ChainedResolverEgress.Unavailable.NEEDS_A_TRANSPORT_THE_TUNNEL_CANNOT_CARRY,
                unavailable.reason,
            )
            assertEquals(ChainedResolverEgress.FailClosedAnswer.SERVFAIL, unavailable.answer)
            assertFalse(ChainedResolverEgressPolicy.permitsQuery(egress))
        }
    }

    @Test
    fun `no transport egresses on the physical interface while chained`() {
        // The type carries no physical-interface case at all; assert the whole transport space
        // maps to either the tunnel or an explicit fail-closed refusal.
        ChainedResolverTransport.entries.forEach { transport ->
            val egress = ChainedResolverEgressPolicy.egressFor(transport)
            when (egress) {
                ChainedResolverEgress.ThroughTunnelToUpstreamResolver -> Unit
                is ChainedResolverEgress.UnavailableWhileChained ->
                    assertEquals(ChainedResolverEgress.FailClosedAnswer.SERVFAIL, egress.answer)
            }
        }
    }

    @Test
    fun `a truncated answer fails closed and is still resolver liveness`() {
        val egress = ChainedResolverEgressPolicy.truncatedAnswerEgress()
        assertTrue(egress is ChainedResolverEgress.UnavailableWhileChained)
        assertFalse(ChainedResolverEgressPolicy.permitsQuery(egress))
        assertTrue(ChainedResolverEgressPolicy.TRUNCATION_IS_RESOLVER_LIVENESS)
    }

    @Test
    fun `log values are stable identifiers`() {
        assertEquals(
            "resolver-through-tunnel",
            ChainedResolverEgressPolicy.logValue(
                ChainedResolverEgress.ThroughTunnelToUpstreamResolver,
            ),
        )
    }
}
