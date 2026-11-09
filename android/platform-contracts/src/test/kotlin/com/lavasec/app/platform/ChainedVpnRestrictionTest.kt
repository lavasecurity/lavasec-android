package com.lavasec.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChainedVpnRestrictionTest {
    @Test
    fun `an eligible device carries no restriction`() {
        assertNull(ChainedAvailability.Eligible().vpnRestriction())
    }

    @Test
    fun `a non-entitled account is not a device restriction`() {
        assertNull(
            ChainedAvailability.Ineligible(ChainedAvailabilityRefusal.NOT_ENTITLED).vpnRestriction(),
        )
    }

    @Test
    fun `memory and exclusion refusals project the insufficient-memory restriction`() {
        assertEquals(
            ChainedVpnRestriction.DEVICE_INSUFFICIENT_MEMORY,
            ChainedAvailability.Ineligible(ChainedAvailabilityRefusal.INSUFFICIENT_MEMORY)
                .vpnRestriction(),
        )
        assertEquals(
            ChainedVpnRestriction.DEVICE_INSUFFICIENT_MEMORY,
            ChainedAvailability.Ineligible(ChainedAvailabilityRefusal.MEMORY_KILL_EXCLUDED)
                .vpnRestriction(),
        )
    }

    @Test
    fun `an unreadable device state projects its own restriction`() {
        assertEquals(
            ChainedVpnRestriction.DEVICE_STATE_UNREADABLE,
            ChainedAvailability.Ineligible(ChainedAvailabilityRefusal.DEVICE_STATE_UNREADABLE)
                .vpnRestriction(),
        )
    }
}
