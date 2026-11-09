package com.lavasec.app.foundation.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the resolver egress decision that keeps an upstream socket out of the tunnel (plan slice B06). */
class ResolverEgressPolicyTest {
    private fun destination(
        address: String = "192.168.1.2",
        onFloor: Boolean,
        coveredByProfile: Boolean,
    ) = ResolverDestination(address, claimedByCaptureFloor = onFloor, coveredByProfileAllowedIps = coveredByProfile)

    @Test
    fun `dns-only leaves the routing table in charge`() {
        val decision = ResolverEgressPolicy.decide(
            destination = destination(onFloor = true, coveredByProfile = false),
            mode = ResolverEgressMode.DNS_ONLY,
            tunnelInterfaceIndex = null,
            physicalInterfaceIndex = 4,
        )
        assertEquals(ResolverInterfaceBinding.SystemChosen, decision.binding)
        assertFalse(decision.mustBindBeforeConnect)
        assertEquals(ResolverEgressReason.SYSTEM_ROUTING, decision.reason)
    }

    @Test
    fun `chained covered destinations ride the tunnel`() {
        val decision = ResolverEgressPolicy.decide(
            destination = destination(onFloor = false, coveredByProfile = false),
            mode = ResolverEgressMode.CHAINED,
            tunnelInterfaceIndex = 9,
            physicalInterfaceIndex = 4,
        )
        assertEquals(ResolverInterfaceBinding.BoundToTunnel(9), decision.binding)
        assertTrue(decision.mustBindBeforeConnect)
        assertEquals(ResolverEgressReason.TUNNEL_CARRY, decision.reason)
    }

    @Test
    fun `a floor-claimed destination the profile does not cover is pinned physical`() {
        val decision = ResolverEgressPolicy.decide(
            destination = destination(onFloor = true, coveredByProfile = false),
            mode = ResolverEgressMode.CHAINED,
            tunnelInterfaceIndex = 9,
            physicalInterfaceIndex = 4,
        )
        assertEquals(ResolverInterfaceBinding.BoundToPhysical(4), decision.binding)
        assertEquals(4, decision.requiredInterfaceIndex)
        assertEquals(ResolverEgressReason.PHYSICAL_FLOOR_PIN, decision.reason)
    }

    @Test
    fun `a floor-claimed destination the profile covers stays on the tunnel`() {
        val decision = ResolverEgressPolicy.decide(
            destination = destination(onFloor = true, coveredByProfile = true),
            mode = ResolverEgressMode.CHAINED,
            tunnelInterfaceIndex = 9,
            physicalInterfaceIndex = 4,
        )
        assertEquals(ResolverInterfaceBinding.BoundToTunnel(9), decision.binding)
    }

    @Test
    fun `a missing physical pin fails closed rather than re-entering the tunnel`() {
        val decision = ResolverEgressPolicy.decide(
            destination = destination(onFloor = true, coveredByProfile = false),
            mode = ResolverEgressMode.CHAINED,
            tunnelInterfaceIndex = 9,
            physicalInterfaceIndex = null,
        )
        assertTrue(decision.failClosed)
        assertNull(decision.binding)
        assertEquals(ResolverEgressReason.NO_INTERFACE_AVAILABLE, decision.reason)
    }

    @Test
    fun `a missing tunnel fails closed`() {
        val decision = ResolverEgressPolicy.decide(
            destination = destination(onFloor = false, coveredByProfile = false),
            mode = ResolverEgressMode.CHAINED,
            tunnelInterfaceIndex = null,
            physicalInterfaceIndex = 4,
        )
        assertTrue(decision.failClosed)
        assertNull(decision.binding)
    }

    @Test
    fun `chained mode never chooses the routing table`() {
        val destinations = listOf(
            destination(onFloor = false, coveredByProfile = false),
            destination(onFloor = true, coveredByProfile = true),
            destination(onFloor = true, coveredByProfile = false),
        )
        for (destination in destinations) {
            val decision = ResolverEgressPolicy.decide(
                destination = destination,
                mode = ResolverEgressMode.CHAINED,
                tunnelInterfaceIndex = 9,
                physicalInterfaceIndex = 4,
            )
            assertEquals(
                "a chained/split resolver must never be system-routed",
                false,
                decision.binding == ResolverInterfaceBinding.SystemChosen,
            )
        }
    }
}
