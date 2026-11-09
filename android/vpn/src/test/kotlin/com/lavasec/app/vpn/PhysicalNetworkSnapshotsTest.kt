package com.lavasec.app.vpn

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class PhysicalNetworkSnapshotsTest {
    @Test fun `internet validation follows the exact usable underlay without requiring it for DNS`() {
        val cache = PhysicalNetworkSnapshots<String>()
        cache.seed(PhysicalNetworkSnapshot("wifi", eligible, dns()))
        assertFalse(cache.internetValidated("wifi"))
        assertEquals("wifi", cache.select(listOf("wifi"))!!.key)
        cache.capabilities("wifi", eligible.copy(validated = true))
        assertTrue(cache.internetValidated("wifi"))
        assertFalse(cache.internetValidated("other"))
        cache.blocked("wifi", true)
        assertFalse(cache.internetValidated("wifi"))
        cache.blocked("wifi", false)
        cache.capabilities("wifi", eligible.copy(validated = true, notVpn = false))
        assertFalse(cache.internetValidated("wifi"))
        cache.capabilities("wifi", eligible.copy(validated = true))
        cache.lost("wifi")
        assertFalse(cache.internetValidated("wifi"))
        assertFalse(cache.capabilities("wifi", eligible.copy(validated = true)))
        cache.available("wifi")
        cache.capabilities("wifi", eligible.copy(validated = true))
        assertFalse(cache.internetValidated("wifi"))
        cache.dns("wifi", dns())
        assertTrue(cache.internetValidated("wifi"))
        cache.close()
        assertFalse(cache.internetValidated("wifi"))
    }
    private val eligible = PhysicalNetworkCapabilities(internet = true, notVpn = true, cellular = false)
    private fun dns(address: String = "192.0.2.53", active: Boolean = false, name: String? = null) =
        PhysicalDnsProperties(listOf(InetAddress.getByName(address)), active, name)

    @Test fun `new network requires both ordered capability and link snapshots`() {
        val cache = PhysicalNetworkSnapshots<String>()
        cache.available("wifi")
        assertNull(cache.select(listOf("wifi")))
        cache.capabilities("wifi", eligible)
        assertNull(cache.select(listOf("wifi")))
        cache.dns("wifi", dns())
        assertEquals(dns(), cache.select(listOf("wifi"))!!.dns)
    }
    @Test fun `same-network DNS and strict Private DNS changes invalidate the resolver facts`() {
        val cache = PhysicalNetworkSnapshots<String>()
        cache.seed(PhysicalNetworkSnapshot("wifi", eligible, dns()))
        assertFalse(cache.dns("wifi", dns()))
        val before = cache.select(listOf("wifi"))
        assertTrue(cache.dns("wifi", dns("192.0.2.54", true, "resolver.example")))
        val after = cache.select(listOf("wifi"))!!
        assertNotEquals(before, after)
        assertEquals("resolver.example", after.dns.privateDnsName)
        assertTrue(after.dns.privateDnsActive)
    }
    @Test fun `late loss and link callbacks cannot replace a successor network`() {
        val cache = PhysicalNetworkSnapshots<String>()
        cache.seed(PhysicalNetworkSnapshot("old", eligible, dns()))
        cache.seed(PhysicalNetworkSnapshot("new", eligible, dns("192.0.2.54")))
        cache.lost("old")
        assertFalse(cache.dns("old", dns("192.0.2.55")))
        assertFalse(cache.capabilities("old", eligible))
        assertEquals("new", cache.select(listOf("old", "new"))!!.key)
        cache.available("old") // a fresh lifecycle must receive both payloads again
        cache.capabilities("old", eligible)
        assertEquals("new", cache.select(listOf("old", "new"))!!.key)
    }
    @Test fun `VPN blocked and noninternet candidates cannot become the physical resolver`() {
        val cache = PhysicalNetworkSnapshots<String>()
        cache.seed(PhysicalNetworkSnapshot("vpn", eligible.copy(notVpn = false), dns()))
        cache.seed(PhysicalNetworkSnapshot("offline", eligible.copy(internet = false), dns()))
        cache.seed(PhysicalNetworkSnapshot("blocked", eligible, dns()))
        cache.blocked("blocked", true)
        assertNull(cache.select(listOf("vpn", "offline", "blocked")))
        cache.blocked("blocked", false)
        assertEquals("blocked", cache.select(listOf("vpn", "offline", "blocked"))!!.key)
    }
    @Test fun `close revokes retained snapshots and all late callbacks`() {
        val cache = PhysicalNetworkSnapshots<String>()
        cache.seed(PhysicalNetworkSnapshot("wifi", eligible, dns()))
        cache.close();cache.available("wifi");cache.seed(PhysicalNetworkSnapshot("wifi", eligible, dns()))
        assertFalse(cache.capabilities("wifi", eligible))
        assertFalse(cache.dns("wifi", dns()))
        assertNull(cache.select(listOf("wifi")))
    }

    @Test fun `Wi-Fi admission belongs to the selected physical network and changes invalidate it`() {
        val cache = PhysicalNetworkSnapshots<String>()
        cache.seed(PhysicalNetworkSnapshot("wifi", eligible.copy(isWifi = true), dns()))
        cache.seed(PhysicalNetworkSnapshot("cellular", eligible.copy(cellular = true), dns("192.0.2.54")))
        assertTrue(cache.select(listOf("wifi", "cellular"))!!.capabilities.isWifi)
        assertFalse(cache.select(listOf("cellular", "wifi"))!!.capabilities.isWifi)

        val previous = cache.select(listOf("wifi"))
        assertTrue(cache.capabilities("wifi", eligible))
        assertFalse(cache.select(listOf("wifi"))!!.capabilities.isWifi)
        assertNotEquals(previous, cache.select(listOf("wifi")))
        cache.lost("wifi")
        assertFalse(cache.capabilities("wifi", eligible.copy(isWifi = true)))
        assertFalse(cache.select(listOf("wifi", "cellular"))!!.capabilities.isWifi)
    }
}
