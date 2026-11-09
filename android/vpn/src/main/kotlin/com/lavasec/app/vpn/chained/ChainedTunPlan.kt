package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedDnsCaptureFloor
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.foundation.chained.ChainedRoutePlanCollision
import com.lavasec.app.foundation.chained.ChainedRoutingPolicy
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration

/**
 * What the tunnel should claim for a given lifecycle.
 *
 * [Ready] carries the exact [ChainedRoutePlan] the `VpnService.Builder` must install; a `null`
 * configuration is the chained-inactive lifecycle and resolves to the pre-chaining DNS-only
 * plan, so the caller has one shape to apply either way. [Rejected] is a split-tunnel
 * configuration whose `AllowedIPs` collide with the tunnel's own addresses and must be refused
 * at the boundary rather than installed.
 */
internal sealed interface ChainedTunPlanResolution {
    /** The plan to install. */
    data class Ready(val plan: ChainedRoutePlan) : ChainedTunPlanResolution

    /** A split-tunnel plan that must not be installed; see [ChainedRoutePlanCollision]. */
    data class Rejected(
        val collisions: List<ChainedRoutePlanCollision>,
    ) : ChainedTunPlanResolution
}

/**
 * Resolves the mode-specific route plan, applying the split-tunnel collision guard that
 * [ChainedRoutePlan.make] deliberately leaves to its caller.
 *
 * `null` is the chained-inactive path: it returns [ChainedRoutePlan.dnsOnly] unchanged, which is
 * byte-for-byte the route shape the tunnel claimed before chaining existed. A full-tunnel
 * configuration is returned as built: its `0.0.0.0/0` legitimately contains the DNS proxy and
 * the client address, so the collision guard must not run against it (the guard is scoped to
 * split mode by construction). A split-tunnel configuration is checked first, because the guard
 * decides whether the configuration is installable at all.
 *
 * Pure: no Android types, no I/O. The mapping decision is executable in a host JVM test.
 */
internal object ChainedTunPlan {
    fun resolve(
        configuration: ChainedUpstreamConfiguration?,
        capturedResolverAddresses: List<String> = emptyList(),
    ): ChainedTunPlanResolution {
        val active = configuration?.activeConfiguration
        if (active == null) {
            return ChainedTunPlanResolution.Ready(ChainedRoutePlan.dnsOnly())
        }
        if (active.effectiveRoutingPolicy == ChainedRoutingPolicy.SPLIT_TUNNEL) {
            val collisions = ChainedRoutePlan.collisions(
                allowedIPs = active.capturedAllowedIPs,
                clientAddress = active.clientAddress,
            )
            if (collisions.isNotEmpty()) {
                return ChainedTunPlanResolution.Rejected(collisions)
            }
        }
        return ChainedTunPlanResolution.Ready(ChainedRoutePlan.make(
            active, ChainedDnsCaptureFloor.forCapturedResolvers(capturedResolverAddresses),
        ))
    }
}
