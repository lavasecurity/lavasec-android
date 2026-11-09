package com.lavasec.app.foundation.dns

/**
 * Pure resolver egress (interface binding) policy ported from the canonical iOS
 * `Sources/LavaSecKit/ResolverInterfaceBinding.swift` and the F2 routing rule in
 * `Sources/LavaSecDNS/ResolverOrchestrator.swift`
 * (pinned `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`).
 *
 * The socket is not opened here. What is modeled is the decision the socket layer would need: a
 * resolver socket for a floor-claimed destination that is NOT covered by the profile's `AllowedIPs`
 * must leave on the physical interface (F2), because an unbound socket follows the routing table
 * back INTO the tunnel and is refused by a peer that does not carry the destination. The binding is
 * a REQUIREMENT, not a preference: when the required interface index is unavailable the query fails
 * closed rather than silently re-entering (the one case that would look exactly like a leak).
 */

/** Which interface a resolver socket must leave from. */
sealed interface ResolverInterfaceBinding {
    /** The routing table chooses: DNS-only mode and any path with no tunnel to prefer. */
    data object SystemChosen : ResolverInterfaceBinding

    /** The socket must be pinned to the tunnel's interface before connect. */
    data class BoundToTunnel(val interfaceIndex: Int) : ResolverInterfaceBinding

    /** The socket must be pinned to the live primary physical interface before connect. */
    data class BoundToPhysical(val interfaceIndex: Int) : ResolverInterfaceBinding

    /** The index a socket must be pinned to, or `null` when the routing table decides. */
    val requiredInterfaceIndex: Int?
        get() = when (this) {
            SystemChosen -> null
            is BoundToTunnel -> interfaceIndex
            is BoundToPhysical -> interfaceIndex
        }

    /** Whether a failure to apply the binding must fail the socket. */
    val mustBindBeforeConnect: Boolean get() = requiredInterfaceIndex != null
}

/** Why an egress decision was made; a log vocabulary, never user copy. */
enum class ResolverEgressReason {
    SYSTEM_ROUTING,
    TUNNEL_CARRY,
    PHYSICAL_FLOOR_PIN,
    NO_INTERFACE_AVAILABLE,
}

/**
 * The destination a resolver query is addressed to, and the two things that decide its route.
 *
 * [claimedByCaptureFloor] is true when the DNS capture floor claims the address as a host route
 * (F1). [coveredByProfileAllowedIps] is true when the user's profile already routes that address,
 * in which case the tunnel is the correct path and F2 must not override it.
 */
data class ResolverDestination(
    val address: String,
    val claimedByCaptureFloor: Boolean,
    val coveredByProfileAllowedIps: Boolean,
)

/**
 * One egress decision. Exactly one of [binding]/[failClosed] describes the outcome: a `null`
 * binding with `failClosed = true` means the query must not be sent at all.
 */
data class ResolverEgressDecision(
    val binding: ResolverInterfaceBinding?,
    val failClosed: Boolean,
    val reason: ResolverEgressReason,
) {
    init {
        require((binding != null) != failClosed) {
            "an egress decision is either a binding or fail-closed, never both or neither"
        }
    }

    val mustBindBeforeConnect: Boolean get() = binding?.mustBindBeforeConnect == true

    val requiredInterfaceIndex: Int? get() = binding?.requiredInterfaceIndex
}

/** Which runtime mode the resolver is operating in. */
enum class ResolverEgressMode { DNS_ONLY, CHAINED }

object ResolverEgressPolicy {
    /**
     * Decides the interface binding for one resolver destination.
     *
     * - DNS-only: the routing table decides.
     * - Chained, floor-claimed and not covered by the profile's `AllowedIPs`: pin the physical
     *   interface (F2). If no physical index is live, fail closed.
     * - Chained otherwise: pin the tunnel interface. If the tunnel index is unknown, fail closed.
     *
     * `SystemChosen` is UNREACHABLE in chained mode: that is the recursive-route/leak shape this
     * model exists to forbid.
     */
    fun decide(
        destination: ResolverDestination,
        mode: ResolverEgressMode,
        tunnelInterfaceIndex: Int?,
        physicalInterfaceIndex: Int?,
    ): ResolverEgressDecision = when (mode) {
        ResolverEgressMode.DNS_ONLY -> ResolverEgressDecision(
            binding = ResolverInterfaceBinding.SystemChosen,
            failClosed = false,
            reason = ResolverEgressReason.SYSTEM_ROUTING,
        )

        ResolverEgressMode.CHAINED -> {
            val requiresPhysical = destination.claimedByCaptureFloor &&
                !destination.coveredByProfileAllowedIps
            if (requiresPhysical) {
                if (physicalInterfaceIndex != null) {
                    ResolverEgressDecision(
                        binding = ResolverInterfaceBinding.BoundToPhysical(physicalInterfaceIndex),
                        failClosed = false,
                        reason = ResolverEgressReason.PHYSICAL_FLOOR_PIN,
                    )
                } else {
                    // An unbound socket would re-enter the tunnel and be refused; never send it.
                    ResolverEgressDecision(
                        binding = null,
                        failClosed = true,
                        reason = ResolverEgressReason.NO_INTERFACE_AVAILABLE,
                    )
                }
            } else if (tunnelInterfaceIndex != null) {
                ResolverEgressDecision(
                    binding = ResolverInterfaceBinding.BoundToTunnel(tunnelInterfaceIndex),
                    failClosed = false,
                    reason = ResolverEgressReason.TUNNEL_CARRY,
                )
            } else {
                // No tunnel to pin to and no permission to leak physically: fail closed.
                ResolverEgressDecision(
                    binding = null,
                    failClosed = true,
                    reason = ResolverEgressReason.NO_INTERFACE_AVAILABLE,
                )
            }
        }
    }
}
