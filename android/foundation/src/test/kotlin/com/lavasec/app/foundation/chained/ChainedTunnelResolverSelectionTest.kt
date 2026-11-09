package com.lavasec.app.foundation.chained

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T0 selection: the ordered usable subset of the configuration's own `DNS =`. */
class ChainedTunnelResolverSelectionTest {

    @Test
    fun `unusable and duplicate entries are dropped in order`() {
        val configuration = configuration(
            clientAddress = "10.7.0.2",
            allowedIPs = listOf("0.0.0.0/0"),
            dnsAddresses = listOf(
                "10.64.0.1",
                "1.1.1.1",
                "0.0.0.0",
                "10.111.0.1",
                "10.7.0.2",
                "10.64.0.1",
            ),
        )

        assertEquals(
            listOf("10.64.0.1", "1.1.1.1"),
            ChainedTunnelResolverSelection.selectedResolvers(configuration),
        )
    }

    @Test
    fun `a resolver outside the split AllowedIPs is not selected`() {
        val configuration = configuration(
            clientAddress = "10.7.0.2",
            allowedIPs = listOf("10.0.0.0/8"),
            dnsAddresses = listOf("10.64.0.1", "1.1.1.1"),
        )

        assertEquals(
            listOf("10.64.0.1"),
            ChainedTunnelResolverSelection.selectedResolvers(configuration),
        )
    }

    @Test
    fun `selection is empty when nothing is usable and resolverBytes parses a usable address`() {
        val configuration = configuration(
            clientAddress = "10.7.0.2",
            allowedIPs = listOf("0.0.0.0/0"),
            dnsAddresses = listOf("0.0.0.0", "10.111.0.1"),
        )

        assertTrue(ChainedTunnelResolverSelection.selection(configuration).resolvers.isEmpty())
        assertArrayEquals(
            byteArrayOf(10, 64, 0, 1),
            ChainedTunnelResolverSelection.resolverBytes("10.64.0.1"),
        )
        assertNull(ChainedTunnelResolverSelection.resolverBytes("not-an-ip"))
    }

    private fun configuration(
        clientAddress: String,
        allowedIPs: List<String>,
        dnsAddresses: List<String>,
    ): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
        endpointHost = "vpn.example.com",
        endpointPort = 51820,
        peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 }),
        clientAddress = clientAddress,
        allowedIPs = allowedIPs,
        persistentKeepaliveSeconds = 0,
        interfaceMTU = null,
        dnsAddresses = dnsAddresses,
    )
}
