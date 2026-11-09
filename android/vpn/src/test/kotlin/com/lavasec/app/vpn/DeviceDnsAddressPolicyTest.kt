package com.lavasec.app.vpn

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDnsAddressPolicyTest {
    @Test
    fun `filters invalid addresses then deduplicates orders and caps immutable resolver snapshot`() {
        val input = listOf(
            "0.0.0.0", "127.0.0.1", "169.254.2.3", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "ff02::1", "64:ff9b::7f00:0001", "64:ff9b::a9fe:0101",
            "10.0.0.1", "8.8.8.8", "8.8.8.8", "9.9.9.9", "1.1.1.1", "4.4.4.4",
            "2001:db8::1", "64:ff9b::0808:0808", "2001:db8::2", "2001:db8::3", "2001:db8::4",
        ).map(InetAddress::getByName)

        val nonCellular = DeviceDnsAddressPolicy.select(input, isCellular = false)
        assertEquals(
            listOf(
                "10.0.0.1", "8.8.8.8", "9.9.9.9", "1.1.1.1", "4.4.4.4",
                "2001:db8:0:0:0:0:0:1", "64:ff9b:0:0:0:0:808:808", "2001:db8:0:0:0:0:0:2",
            ),
            nonCellular.map { it.hostAddress },
        )
        assertEquals(
            listOf(
                "2001:db8:0:0:0:0:0:1", "64:ff9b:0:0:0:0:808:808", "2001:db8:0:0:0:0:0:2",
                "2001:db8:0:0:0:0:0:3", "2001:db8:0:0:0:0:0:4", "10.0.0.1", "8.8.8.8", "9.9.9.9",
            ),
            DeviceDnsAddressPolicy.select(input, isCellular = true).map { it.hostAddress },
        )
        assertFalse(DeviceDnsAddressPolicy.isUsable(InetAddress.getByName("64:ff9b::0000:0000")))
        assertTrue(DeviceDnsAddressPolicy.isUsable(InetAddress.getByName("64:ff9b::0808:0808")))
        assertImmutable(nonCellular)
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertImmutable(values: List<InetAddress>) {
        try {
            (values as MutableList<InetAddress>).clear()
        } catch (_: UnsupportedOperationException) {
            return
        }
        throw AssertionError("selected addresses were mutable")
    }
}
