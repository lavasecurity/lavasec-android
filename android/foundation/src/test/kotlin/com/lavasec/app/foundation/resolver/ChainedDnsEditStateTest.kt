package com.lavasec.app.foundation.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainedDnsEditStateTest {
    @Test
    fun `a full tunnel locks editing even with fallback consent and when chaining is off it does not`() {
        for (consent in listOf(false, true)) {
            val full = ChainedDnsEditState(
                chainingEnabled = true,
                fallbackPreference = consent,
                storedIsSplitTunnel = false,
            )
            assertEquals(false, full.fallbackEnabled)
            assertFalse(full.canChangeFallback)
            assertFalse(full.canEditDns)

            val off = ChainedDnsEditState(
                chainingEnabled = false,
                fallbackPreference = consent,
                storedIsSplitTunnel = false,
            )
            assertTrue(off.canEditDns)
        }
    }

    @Test
    fun `an unknown routing policy is editable and claims neither fallback value`() {
        val unknown = ChainedDnsEditState(
            chainingEnabled = true,
            fallbackPreference = true,
            storedIsSplitTunnel = null,
        )
        assertNull(unknown.fallbackEnabled)
        assertTrue(unknown.canEditDns)
        assertTrue(unknown.canChangeFallback)
    }

    @Test
    fun `fallback off locks editing regardless of an unknown policy`() {
        val disabled = ChainedDnsEditState(
            chainingEnabled = true,
            fallbackPreference = false,
            storedIsSplitTunnel = null,
        )
        assertEquals(false, disabled.fallbackEnabled)
        assertFalse(disabled.canEditDns)
    }

    @Test
    fun `replacing a full tunnel with a split tunnel restores the saved consent`() {
        val full = ChainedDnsEditState(chainingEnabled = true, fallbackPreference = true, storedIsSplitTunnel = false)
        val split = ChainedDnsEditState(chainingEnabled = true, fallbackPreference = true, storedIsSplitTunnel = true)
        assertEquals(false, full.fallbackEnabled)
        assertEquals(true, split.fallbackEnabled)
        assertTrue(split.canEditDns)
        assertTrue(split.canChangeFallback)
        assertFalse(
            ChainedDnsEditState(chainingEnabled = true, fallbackPreference = false, storedIsSplitTunnel = true).canEditDns,
        )
    }

    @Test
    fun `uses wireguard mirrors the chaining toggle`() {
        assertTrue(ChainedDnsEditState(true, fallbackPreference = true, storedIsSplitTunnel = null).usesWireGuard)
        assertFalse(ChainedDnsEditState(false, fallbackPreference = true, storedIsSplitTunnel = null).usesWireGuard)
    }
}
