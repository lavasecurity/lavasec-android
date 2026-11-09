package com.lavasec.app.vpn.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner-identity guard: a terminal callback from an owner that a reconnect has already
 * replaced must not clear or close the replacement.
 */
class ChainedOwnerSlotTest {

    @Test
    fun `a stale callback from a replaced owner is ignored`() {
        val slot = ChainedOwnerSlot<String>()
        slot.install("first")

        // A callback reporting the old owner while the slot holds a different one must not match.
        assertFalse(slot.takeIfCurrent("stale"))
        assertEquals("first", slot.currentOrNull())

        // A fresh install replaces the old owner; only the installed identity matches.
        slot.install("second")
        assertFalse(slot.takeIfCurrent("first"))
        assertTrue(slot.takeIfCurrent("second"))
        assertNull(slot.currentOrNull())
    }

    @Test
    fun `taking the current owner clears the slot once`() {
        val slot = ChainedOwnerSlot<String>()

        assertNull(slot.take())
        slot.install("owner")
        assertEquals("owner", slot.take())
        assertNull(slot.currentOrNull())
        assertFalse(slot.takeIfCurrent("owner"))
    }

    @Test
    fun `isCurrent reports the installed owner without clearing it`() {
        val slot = ChainedOwnerSlot<String>()
        slot.install("first")

        assertTrue(slot.isCurrent("first"))
        assertFalse(slot.isCurrent("second"))
        // The check is non-destructive: the owner is still installed afterwards.
        assertEquals("first", slot.currentOrNull())
    }

    @Test
    fun `clear invalidates a queued callback`() {
        val slot = ChainedOwnerSlot<String>()
        slot.install("owner")

        slot.clear()

        assertNull(slot.currentOrNull())
        assertFalse(slot.takeIfCurrent("owner"))
    }
}
