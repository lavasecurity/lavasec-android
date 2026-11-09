package com.lavasec.app.foundation.share

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareableSourceUrlValidatorTest {
    @Test fun `resolved addresses share the public literal gate without hostname lookups`() {
        for (literal in listOf("0.0.0.0", "10.1.2.3", "172.16.1.2", "192.168.1.2", "127.0.0.1",
            "169.254.1.2", "224.0.0.1", "255.255.255.255", "100.64.1.2", "192.0.0.1",
            "192.0.2.1", "192.88.99.1", "198.18.1.2", "198.51.100.1", "203.0.113.1",
            "::", "::1", "fc00::1", "fd00::1", "fe80::1", "fec0::1", "ff02::1", "2001:db8::1",
            "::ffff:10.1.2.3", "::10.1.2.3", "64:ff9b::a01:203", "64:ff9b:1::1")) {
            val host = if (':' in literal) "[$literal]" else literal
            assertFalse(literal, ShareableSourceUrlValidator.isAcceptable("https://$host/list.txt"))
            assertFalse(literal, ShareableSourceUrlValidator.isPublicAddress(InetAddress.getByName(literal)))
        }
        // Keep the embedded-policy test genuinely IPv6 even on a JVM that collapses mapped
        // literals into Inet4Address while parsing.
        val mapped = ByteArray(16).apply {
            this[10] = 0xff.toByte(); this[11] = 0xff.toByte()
            this[12] = 10; this[13] = 1; this[14] = 2; this[15] = 3
        }
        assertFalse(ShareableSourceUrlValidator.isPublicAddress(Inet6Address.getByAddress(null, mapped, -1)))
    }

    @Test fun `resolved public IPv4 IPv6 and embedded public addresses remain allowed`() {
        for (literal in listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "2001:4860:4860::8888",
            "::ffff:8.8.8.8", "64:ff9b::808:808")) {
            val host = if (':' in literal) "[$literal]" else literal
            assertTrue(literal, ShareableSourceUrlValidator.isAcceptable("https://$host/list.txt"))
            assertTrue(literal, ShareableSourceUrlValidator.isPublicAddress(InetAddress.getByName(literal)))
        }
    }

    @Test fun `alternate numeric IP spellings never pass as domains`() {
        for (host in listOf("localhost.", "service.localhost.", "127.1", "10.1", "2130706433", "0x7f000001", "0177.0.0.1",
            "127.0.0.1.", "0x7f.0.0.1", "127.0.1", "8.8.8.999", "008.008.008.008")) {
            assertFalse(host, ShareableSourceUrlValidator.isAcceptable("https://$host/list.txt"))
        }
    }
    @Test fun `public canonical IPs and normal hostnames remain allowed`() {
        for (host in listOf("8.8.8.8", "1.1.1.1", "123.example.com", "example.org")) {
            assertTrue(host, ShareableSourceUrlValidator.isAcceptable("https://$host/list.txt"))
        }
    }
}
