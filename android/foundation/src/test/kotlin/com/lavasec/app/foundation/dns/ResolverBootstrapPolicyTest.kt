package com.lavasec.app.foundation.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins bootstrap address selection and the generation fence (plan slice B06). */
class ResolverBootstrapPolicyTest {
    @Test
    fun `link-local loopback and unspecified cannot serve as resolvers`() {
        assertFalse(ResolverAddressFamilies.isUsableResolverAddress("0.0.0.0"))
        assertFalse(ResolverAddressFamilies.isUsableResolverAddress("127.0.0.1"))
        assertFalse(ResolverAddressFamilies.isUsableResolverAddress("169.254.1.1"))
        assertFalse(ResolverAddressFamilies.isUsableResolverAddress("::"))
        assertFalse(ResolverAddressFamilies.isUsableResolverAddress("::1"))
        assertFalse(ResolverAddressFamilies.isUsableResolverAddress("fe80::1"))
        assertFalse(ResolverAddressFamilies.isUsableResolverAddress("not-an-ip"))

        assertTrue(ResolverAddressFamilies.isUsableResolverAddress("8.8.8.8"))
        assertTrue(ResolverAddressFamilies.isUsableResolverAddress("192.168.1.1"))
        assertTrue(ResolverAddressFamilies.isUsableResolverAddress("2606:4700:4700::1111"))
    }

    @Test
    fun `the NAT64 prefix is judged by its embedded IPv4`() {
        assertTrue(ResolverAddressFamilies.isUsableResolverAddress("64:ff9b::8.8.8.8"))
        assertFalse(ResolverAddressFamilies.isUsableResolverAddress("64:ff9b::127.0.0.1"))
        assertFalse(ResolverAddressFamilies.isUsableResolverAddress("64:ff9b::169.254.1.1"))
        assertFalse(ResolverAddressFamilies.isUsableResolverAddress("64:ff9b::"))
    }

    @Test
    fun `ordering prefers IPv6 on cellular and IPv4 otherwise, preserving intra-family order`() {
        val addresses = listOf("2606:4700:4700::1111", "1.1.1.1", "not-an-ip", "2606:4700:4700::1001", "9.9.9.9")
        assertEquals(
            listOf("1.1.1.1", "9.9.9.9", "2606:4700:4700::1111", "2606:4700:4700::1001", "not-an-ip"),
            ResolverAddressFamilies.orderedResolverAddresses(addresses, ResolverNetworkKind.WIFI),
        )
        assertEquals(
            listOf("2606:4700:4700::1111", "2606:4700:4700::1001", "1.1.1.1", "9.9.9.9", "not-an-ip"),
            ResolverAddressFamilies.orderedResolverAddresses(addresses, ResolverNetworkKind.CELLULAR),
        )
    }

    @Test
    fun `a cached hostname suppresses a pre-warm`() {
        val resolved = ResolverResolvedAddresses(ipv4 = listOf("1.1.1.1"))
        val started = ResolverBootstrapPolicy.prewarm(ResolverBootstrapState(), "host.example", admittedAtEpoch = 1L)
        val kick = started.kick
        assertNotNull(kick)
        val state = ResolverBootstrapPolicy.completeLookup(started.state, kick!!, resolved)
        assertEquals(resolved, ResolverBootstrapPolicy.cachedAddresses(state, "host.example"))

        val suppressed = ResolverBootstrapPolicy.prewarm(state, "host.example", admittedAtEpoch = 2L)
        assertNull(suppressed.kick)
    }

    @Test
    fun `a same-generation duplicate is suppressed but a newer session supersedes it`() {
        val state = ResolverBootstrapPolicy.prewarm(ResolverBootstrapState(), "host.example", admittedAtEpoch = 5L).state
        assertNull(ResolverBootstrapPolicy.prewarm(state, "host.example", admittedAtEpoch = 5L).kick)
        assertNull(ResolverBootstrapPolicy.prewarm(state, "host.example", admittedAtEpoch = 4L).kick)

        val newer = ResolverBootstrapPolicy.prewarm(state, "host.example", admittedAtEpoch = 6L)
        assertNotNull(newer.kick)
        assertEquals(6L, newer.kick!!.admittedAtEpoch)
    }

    @Test
    fun `a failed lookup is not cached and the next pre-warm retries`() {
        val started = ResolverBootstrapPolicy.prewarm(ResolverBootstrapState(), "host.example", admittedAtEpoch = 1L)
        val state = ResolverBootstrapPolicy.completeLookup(started.state, started.kick!!, ResolverResolvedAddresses.EMPTY)
        assertNull(ResolverBootstrapPolicy.cachedAddresses(state, "host.example"))

        val retry = ResolverBootstrapPolicy.prewarm(state, "host.example", admittedAtEpoch = 1L)
        assertNotNull(retry.kick)
    }

    @Test
    fun `a late lookup from a retired generation cannot repopulate the cache`() {
        val resolved = ResolverResolvedAddresses(ipv6 = listOf("2606:4700:4700::1111"))
        val started = ResolverBootstrapPolicy.prewarm(ResolverBootstrapState(), "host.example", admittedAtEpoch = 1L)
        val oldKick = started.kick!!

        val invalidated = ResolverBootstrapPolicy.invalidateAll(started.state)
        assertEquals(1L, invalidated.generation)
        val replacement = ResolverBootstrapPolicy.prewarm(invalidated, "host.example", admittedAtEpoch = 2L)
        val newKick = replacement.kick!!

        // The previous network's lookup finishes late; it is superseded and cannot cache.
        val afterLate = ResolverBootstrapPolicy.completeLookup(replacement.state, oldKick, resolved)
        assertNull(ResolverBootstrapPolicy.cachedAddresses(afterLate, "host.example"))
        assertEquals(newKick, afterLate.inFlight["host.example"])

        val committed = ResolverBootstrapPolicy.completeLookup(afterLate, newKick, resolved)
        assertEquals(resolved, ResolverBootstrapPolicy.cachedAddresses(committed, "host.example"))
    }

    @Test
    fun `an underlying network change drains the network-dependent cache`() {
        val resolved = ResolverResolvedAddresses(ipv4 = listOf("192.168.1.1"))
        val started = ResolverBootstrapPolicy.prewarm(ResolverBootstrapState(), "host.example", admittedAtEpoch = 1L)
        val state = ResolverBootstrapPolicy.completeLookup(started.state, started.kick!!, resolved)
        assertNotNull(ResolverBootstrapPolicy.cachedAddresses(state, "host.example"))

        val changed = ResolverBootstrapPolicy.onUnderlyingNetworkChanged(state)
        assertNull(ResolverBootstrapPolicy.cachedAddresses(changed, "host.example"))
        assertEquals(state.generation + 1, changed.generation)
    }
}
