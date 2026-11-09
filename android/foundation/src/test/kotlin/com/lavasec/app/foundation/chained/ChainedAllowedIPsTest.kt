package com.lavasec.app.foundation.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ChainedAllowedIPsTest {

    private fun bytes(address: String): ByteArray = InetAddress.getByName(address).address

    /**
     * 16 bytes for a v6 literal, built through the prefix parser rather than `InetAddress`, which
     * collapses an IPv4-mapped address such as `::ffff:10.0.0.9` to a 4-byte `Inet4Address`.
     */
    private fun v6bytes(address: String): ByteArray =
        requireNotNull(ChainedIPPrefix.parse("$address/128")).networkBytes

    // MARK: - IPv4 parse and membership

    @Test
    fun `a covering prefix admits its members and refuses outsiders`() {
        val allowed = requireNotNull(ChainedAllowedIPs.parse("10.0.0.0/8"))

        assertTrue(allowed.permits(bytes("10.1.2.3")))
        assertTrue(allowed.permits(bytes("10.255.255.255")))
        assertFalse(allowed.permits(bytes("11.0.0.0")))
        assertFalse(allowed.permits(bytes("192.168.1.1")))
    }

    @Test
    fun `host bits are masked so two spellings of one rule agree`() {
        val sloppy = requireNotNull(ChainedAllowedIPs.parse("10.1.2.3/8"))
        val canonical = requireNotNull(ChainedAllowedIPs.parse("10.0.0.0/8"))

        assertEquals(canonical, sloppy)
        assertEquals(32, sloppy.prefixes.single().let { it.networkBytes.size * 8 })
        assertEquals(8, sloppy.prefixes.single().prefixLength)
        assertTrue(sloppy.permits(bytes("10.200.4.4")))
    }

    @Test
    fun `a bare address is a single host`() {
        val allowed = requireNotNull(ChainedAllowedIPs.parse("192.168.1.5"))
        val prefix = allowed.prefixes.single()

        assertEquals(32, prefix.prefixLength)
        assertTrue(allowed.permits(bytes("192.168.1.5")))
        assertFalse(allowed.permits(bytes("192.168.1.6")))
    }

    @Test
    fun `a partial byte is compared, not rounded to the whole byte`() {
        // A /9 that ignored its final significant bit would admit all of 10.0.0.0/8.
        val nine = requireNotNull(ChainedAllowedIPs.parse("10.0.0.0/9"))
        assertTrue(nine.permits(bytes("10.127.255.255")))
        assertFalse(nine.permits(bytes("10.128.0.0")))

        // /15 exercises the same partial-byte path one byte further in.
        val fifteen = requireNotNull(ChainedAllowedIPs.parse("10.0.0.0/15"))
        assertTrue(fifteen.permits(bytes("10.1.255.255")))
        assertFalse(fifteen.permits(bytes("10.2.0.0")))
    }

    @Test
    fun `a zero length prefix matches every address of its own family only`() {
        val anyV4 = requireNotNull(ChainedAllowedIPs.parse("0.0.0.0/0"))

        assertTrue(anyV4.permits(bytes("8.8.8.8")))
        assertTrue(anyV4.permits(bytes("255.255.255.255")))
        assertFalse(anyV4.permits(bytes("fd00::1")))
    }

    // MARK: - IPv6

    @Test
    fun `an IPv6 prefix parses and matches within its family`() {
        val allowed = requireNotNull(ChainedAllowedIPs.parse("fd00::/8"))
        val prefix = allowed.prefixes.single()

        assertTrue(prefix.isIPv6)
        assertEquals(16, prefix.networkBytes.size)
        assertTrue(allowed.permits(bytes("fd00::1")))
        assertTrue(allowed.permits(bytes("fdff:ffff::abcd")))
        assertFalse(allowed.permits(bytes("fe80::1")))
        // Family isolation: a 4-byte source is never tested against a 16-byte prefix.
        assertFalse(allowed.permits(bytes("10.0.0.1")))
    }

    @Test
    fun `a bare IPv6 address is a host route`() {
        val allowed = requireNotNull(ChainedAllowedIPs.parse("fd00::1"))
        assertEquals(128, allowed.prefixes.single().prefixLength)
        assertTrue(allowed.permits(bytes("fd00::1")))
        assertFalse(allowed.permits(bytes("fd00::2")))
    }

    @Test
    fun `an IPv4-mapped prefix never widens the IPv4 allowlist`() {
        val allowed = requireNotNull(ChainedAllowedIPs.parse("::ffff:10.0.0.0/104"))

        // The mapped 16-byte spelling is admitted...
        assertTrue(allowed.permits(v6bytes("::ffff:10.0.0.9")))
        // ...but the 4-byte source is not, even though it is the same address.
        assertFalse(allowed.permits(bytes("10.0.0.9")))
    }

    @Test
    fun `a mixed list admits members of both families`() {
        val allowed = requireNotNull(ChainedAllowedIPs.parse("10.0.0.0/8, fd00::/8"))

        assertEquals(2, allowed.prefixes.size)
        assertTrue(allowed.permits(bytes("10.1.2.3")))
        assertTrue(allowed.permits(bytes("fd00::1")))
        assertFalse(allowed.permits(bytes("192.168.1.1")))
    }

    @Test
    fun `a prefix with host bits masks the v6 network too`() {
        val sloppy = requireNotNull(ChainedAllowedIPs.parse("fd00:1a7a::7/32"))
        val canonical = requireNotNull(ChainedAllowedIPs.parse("fd00:1a7a::/32"))
        assertEquals(canonical, sloppy)
    }

    // MARK: - Refusals (fail closed)

    @Test
    fun `an empty list is a configuration error, not an empty allowlist`() {
        assertNull(ChainedAllowedIPs.parse(""))
        assertNull(ChainedAllowedIPs.parse("   "))
        assertNull(ChainedAllowedIPs.parse(",,"))
        assertNull(ChainedAllowedIPs.parse(" , , "))
    }

    @Test
    fun `one unparseable entry refuses the whole list`() {
        // A partial parse would silently narrow the allowlist, failing closed in a way that looks
        // like a working tunnel dropping traffic.
        assertNull(ChainedAllowedIPs.parse("10.0.0.0/8, not-an-ip/8"))
        assertNull(ChainedAllowedIPs.parse("10.0.0.0/8, 192.168.1.0/33"))
        assertNull(ChainedAllowedIPs.parse("10.0.0.0/8, 10.0.0.0.0"))
    }

    @Test
    fun `a prefix length outside the family range is refused rather than clamped`() {
        assertNull(ChainedAllowedIPs.parse("10.0.0.0/33"))
        assertNull(ChainedAllowedIPs.parse("10.0.0.0/-1"))
        assertNull(ChainedAllowedIPs.parse("fd00::/129"))
    }

    @Test
    fun `noncanonical spellings are refused`() {
        assertNull(ChainedAllowedIPs.parse("010.0.0.0/8"))
        assertNull(ChainedAllowedIPs.parse("10.0.0.0/08"))
        assertNull(ChainedAllowedIPs.parse("10.0.0/8"))
        assertNull(ChainedAllowedIPs.parse("10.0.0.0/8/x"))
        assertNull(ChainedAllowedIPs.parse("1:00000::1/64"))
        assertNull(ChainedAllowedIPs.parse(":::/0"))
        assertNull(ChainedAllowedIPs.parse("fd00::1%eth0/128"))
    }

    @Test
    fun `an empty set permits nothing`() {
        // The fail-closed rule is unchanged: only the empty LIST is refused at the parser.
        val empty = ChainedAllowedIPs.of(emptyList())

        assertFalse(empty.permits(bytes("10.1.2.3")))
        assertFalse(empty.permits(bytes("fd00::1")))
    }

    // MARK: - Inbound verdict

    @Test
    fun `an allowed source is delivered and a spoofed source is dropped`() {
        val allowed = requireNotNull(ChainedAllowedIPs.parse("10.0.0.0/8"))

        val delivered = allowed.verdict(bytes("10.1.2.3"), byteCount = 100)
        assertEquals(ChainedInboundVerdict.Deliver(100), delivered)
        assertTrue(delivered.deliversToTunnel)

        val spoofed = allowed.verdict(bytes("8.8.8.8"), byteCount = 100)
        assertEquals(ChainedInboundVerdict.DropSpoofedSource(100), spoofed)
        assertFalse(spoofed.deliversToTunnel)
    }

    @Test
    fun `a malformed source length is its own verdict, not a spoof`() {
        val allowed = requireNotNull(ChainedAllowedIPs.parse("0.0.0.0/0"))

        val malformed = allowed.verdict(ByteArray(5) { 1 }, byteCount = 9)
        assertEquals(ChainedInboundVerdict.DropMalformedSource, malformed)
        assertFalse(malformed.deliversToTunnel)
        assertEquals("inbound-drop-source-malformed", malformed.logValue)
    }

    @Test
    fun `the verdict log values never carry the address`() {
        assertEquals("inbound-deliver", ChainedInboundVerdict.Deliver(1).logValue)
        assertEquals(
            "inbound-drop-source-not-allowed",
            ChainedInboundVerdict.DropSpoofedSource(1).logValue,
        )
    }

    @Test
    fun `a prefix only equals an identical prefix`() {
        val a = requireNotNull(ChainedAllowedIPs.parse("10.0.0.0/8"))
        val b = requireNotNull(ChainedAllowedIPs.parse("10.0.0.0/8"))
        val c = requireNotNull(ChainedAllowedIPs.parse("10.0.0.0/9"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
