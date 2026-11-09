package com.lavasec.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductionFilterAuthorityNetworkTest {
    @Test
    fun `active validated WiFi wins over earlier validated cellular enumeration`() {
        val cellular = ordinary("cellular-101")
        val wifi = ordinary("wifi-102")
        for (enumeration in listOf(listOf(cellular, wifi), listOf(wifi, cellular))) {
            assertEquals("wifi-102", selectOrdinarySourceNetwork(enumeration, "wifi-102"))
        }
    }

    @Test
    fun `an eligible active default remains preferred while validation is pending`() {
        assertEquals("wifi", selectOrdinarySourceNetwork(
            listOf(ordinary("cellular"), ordinary("wifi", validated = false)), "wifi"))
    }

    @Test
    fun `VPN default cannot authorize source traffic and validated ordinary fallback wins`() {
        val vpn = OrdinarySourceNetworkCandidate("vpn", notVpn = false, internet = true, validated = true)
        assertEquals("cellular", selectOrdinarySourceNetwork(
            listOf(vpn, ordinary("wifi", validated = false), ordinary("cellular")), "vpn"))
    }

    @Test
    fun `default without internet is excluded and missing default still uses validated fallback`() {
        val localOnly = OrdinarySourceNetworkCandidate("local", notVpn = true, internet = false, validated = true)
        val candidates = listOf(localOnly, ordinary("unvalidated", validated = false), ordinary("validated"))
        assertEquals("validated", selectOrdinarySourceNetwork(candidates, "local"))
        assertEquals("validated", selectOrdinarySourceNetwork(candidates, null))
        assertEquals("validated", selectOrdinarySourceNetwork(candidates, "vanished"))
    }

    @Test
    fun `no ordinary internet route refuses and sole unvalidated ordinary route is retained`() {
        assertNull(selectOrdinarySourceNetwork(listOf(
            OrdinarySourceNetworkCandidate("vpn", notVpn = false, internet = true, validated = true),
            OrdinarySourceNetworkCandidate("local", notVpn = true, internet = false, validated = true),
        ), "vpn"))
        assertNull(selectOrdinarySourceNetwork(emptyList<OrdinarySourceNetworkCandidate<String>>(), null))
        assertEquals("ordinary", selectOrdinarySourceNetwork(
            listOf(ordinary("ordinary", validated = false)), null))
    }

    private fun ordinary(network: String, validated: Boolean = true) =
        OrdinarySourceNetworkCandidate(network, notVpn = true, internet = true, validated = validated)
}
