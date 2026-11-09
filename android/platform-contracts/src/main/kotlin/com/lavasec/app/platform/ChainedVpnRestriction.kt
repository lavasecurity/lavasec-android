package com.lavasec.app.platform

/**
 * The honest reason the shared VPN screen shows a restriction, derived from the LIVE
 * [ChainedAvailability] verdict (the same authority that gates the engine).
 *
 * The VPN snapshot must not hardcode "no restriction": an entitled user on a device the engine
 * refuses (below the memory floor, memory-kill excluded, or an unreadable device state) would
 * otherwise see the editor with the enable control disabled and no explanation. Mapping the
 * availability verdict here, in the domain layer, keeps the projection testable on the JVM and
 * lets the platform bridge localize each case.
 *
 * `NOT_ENTITLED` is deliberately absent: it is a subscription condition the surface already
 * presents as `needsPlus`, not a device restriction. An [ChainedAvailability.Eligible] verdict
 * carries no restriction.
 */
enum class ChainedVpnRestriction {
    /** The device is below the chaining memory floor, or memory kills excluded the engine. */
    DEVICE_INSUFFICIENT_MEMORY,

    /** The device's memory/exclusion state could not be read; fail closed with a remedy. */
    DEVICE_STATE_UNREADABLE,
}

/**
 * The restriction the VPN surface should show for this availability verdict, or `null` when none
 * applies. The engine gate and this projection read the same [ChainedAvailability], so they can
 * never disagree about whether the device is usable.
 */
fun ChainedAvailability.vpnRestriction(): ChainedVpnRestriction? = when (this) {
    is ChainedAvailability.Eligible -> null
    is ChainedAvailability.Ineligible -> when (reason) {
        ChainedAvailabilityRefusal.NOT_ENTITLED -> null
        ChainedAvailabilityRefusal.INSUFFICIENT_MEMORY,
        ChainedAvailabilityRefusal.MEMORY_KILL_EXCLUDED,
        -> ChainedVpnRestriction.DEVICE_INSUFFICIENT_MEMORY

        ChainedAvailabilityRefusal.DEVICE_STATE_UNREADABLE ->
            ChainedVpnRestriction.DEVICE_STATE_UNREADABLE
    }
}
