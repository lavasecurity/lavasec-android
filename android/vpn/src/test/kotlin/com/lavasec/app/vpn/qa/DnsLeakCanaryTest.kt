package com.lavasec.app.vpn.qa

import com.lavasec.app.foundation.dns.DnsMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsLeakCanaryTest {
    @Test fun `positive control is one exact invalid IN A question`() {
        val wire = DnsLeakCanary.query("A01B02C03D")
        assertEquals("a01b02c03d.leak-canary.lavasec.invalid", DnsLeakCanary.domain("A01B02C03D"))
        assertNotNull(DnsMessage.parseQuestion(wire))
        assertArrayEquals(byteArrayOf(0x1e, 0xa4.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0), wire.take(12).toByteArray())
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, 1), wire.takeLast(5).toByteArray())
    }

    @Test fun `target parsing never admits hostnames ports CIDR URI scopes or permissive quads`() {
        for (value in listOf("resolver.example", "1.2.3", "010.0.0.1", "1.1.1.1:53", "[::1]",
            "https://1.1.1.1", "192.168.1.1/32", "fe80::1%eth0", "1:00000::1"))
            assertNull(value, DnsLeakCanary.resolverBytes(value))
        // Frozen QA accepts any syntactically valid numeric target, including controlled peers.
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), DnsLeakCanary.resolverBytes("127.0.0.1"))
        assertEquals(16, DnsLeakCanary.resolverBytes("fd00::53")?.size)
        assertEquals(16, DnsLeakCanary.resolverBytes("::ffff:192.168.1.53")?.size)
    }
}
