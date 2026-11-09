package com.lavasec.app.foundation.chained

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainedDnsCaptureFloorTest {
    @Test
    fun `IPv6 floor preserves canonical byte identity and exact public captured hosts`() {
        val captured = listOf("2606:4700:4700:0:0:0:0:1111", "2001:4860:4860::8888", "2001:4860:4860::1234", "fc00::53", "fe80::53")
        val floor = ChainedDnsCaptureFloor.forCapturedResolvers(captured)
        assertEquals(13, floor.ipv6Routes.size)
        assertTrue(floor.ipv6Routes.all { it.prefixLength == 128 })
        assertTrue(floor.containsIPv6Destination(InetAddress.getByName("2606:4700:4700::1111").address, 0))
        assertTrue(floor.containsIPv6Destination(InetAddress.getByName("2001:4860:4860::1234").address, 0))
        assertFalse(floor.containsIPv6Destination(InetAddress.getByName("fc00::53").address, 0))
        assertTrue(floor.hasSameDestinations(ChainedDnsCaptureFloor.forCapturedResolvers(captured.reversed())))
    }

    @Test
    fun `curated addresses are the exact frozen IPv4 set and captured public addresses append once`() {
        val expected = listOf(
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15",
            "194.242.2.2", "194.242.2.3",
        )
        val captured = mutableListOf("4.2.2.2", "8.8.8.8", "4.2.2.2")
        val floor = ChainedDnsCaptureFloor.forCapturedResolvers(captured)
        captured.clear()
        assertEquals(expected + "4.2.2.2", floor.ipv4Routes.map { it.destinationAddress })
        assertTrue(floor.ipv4Routes.all { it.prefixLength == 32 })
        assertTrue(floor.containsIPv4Destination(byteArrayOf(4, 2, 2, 2), 0))
        assertFalse(floor.containsIPv4Destination(byteArrayOf(4, 2, 2, 3), 0))
        assertThrows(UnsupportedOperationException::class.java) {
            (floor.ipv4Routes as MutableList<IPv4Route>).clear()
        }
    }

    @Test
    fun `gateway class unusable malformed and mapped IPv6 captures never become host routes`() {
        val rejected = listOf(
            "10.0.0.53", "172.16.0.53", "172.31.255.53", "192.168.1.53", "10.111.0.1",
            "0.0.0.0", "0.1.2.3", "127.0.0.53", "169.254.1.53", "01.2.3.4", "1.2.3.256",
            "resolver.example", "8.8.8.8:53", "2606:4700:4700::1111", "::ffff:8.8.8.8",
            "fc00::53", "fe80::53", "::1",
        )
        assertEquals(
            ChainedDnsCaptureFloor.forCapturedResolvers(emptyList()),
            ChainedDnsCaptureFloor.forCapturedResolvers(rejected),
        )
        // Private exclusion is exactly /12, not every 172/8 address.
        val boundary = ChainedDnsCaptureFloor.forCapturedResolvers(listOf("172.15.0.53", "172.32.0.53"))
        assertEquals(listOf("172.15.0.53", "172.32.0.53"), boundary.ipv4Routes.takeLast(2).map { it.destinationAddress })
    }
}
