package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedReadiness
import com.lavasec.app.foundation.chained.ChainedReadinessRefusal
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration

/**
 * Feeds the shared [ChainedReadiness] gate from the authoritative [ChainedAvailability] verdict.
 *
 * The one seam a caller should use to map availability onto the gate, so no surface hand-rolls
 * `deviceEligible = true` (the disabled-control-with-no-remedy defect the readiness ordering
 * exists to prevent). [hasEntitlement] is passed through rather than derived: readiness reports
 * [com.lavasec.app.foundation.chained.ChainedReadinessRefusal.NOT_ENTITLED] first, and taking the
 * raw verdict keeps that account gate the caller's authoritative term rather than a re-encoding.
 * The availability verdict also covers entitlement, so the overlap is harmless and only makes a
 * fabricated `true` impossible.
 *
 * ## Client DNS over the tunnel
 *
 * [clientDnsOverTunnelAvailable] is the platform capability that the chained data path can serve
 * intercepted client DNS through the configured T0 resolver. A latched chained tunnel with an
 * unwired sink would discard every intercepted query — a hostname blackhole — so the default is
 * `false` (fail closed). Production wires `ChainedT0ResolverEgress` and passes `true`; any other
 * caller that has not wired an egress leaves it `false`. Tests pass `true` to exercise the gate's
 * ready path.
 */
fun chainedReadinessFrom(
    configuration: ChainedUpstreamConfiguration?,
    hasEntitlement: Boolean,
    availability: ChainedAvailability,
    userEnabled: Boolean,
    clientDnsOverTunnelAvailable: Boolean = false,
): ChainedReadiness {
    val readiness = ChainedReadiness.evaluate(
        configuration = configuration,
        hasEntitlement = hasEntitlement,
        deviceEligible = availability.isEligible,
        userEnabled = userEnabled,
    )
    if (readiness !is ChainedReadiness.Ready) return readiness
    if (!clientDnsOverTunnelAvailable) {
        return ChainedReadiness.NotReady(ChainedReadinessRefusal.CLIENT_DNS_OVER_TUNNEL_UNAVAILABLE)
    }
    return readiness
}

/**
 * A stable, user-facing reason code for a readiness refusal.
 *
 * Deliberately distinct from [ChainedReadinessRefusal.logValue], which is a device-log identifier
 * such as `chained-not-ready-routes-collide`. The bridge sends this code across to the JS layer,
 * which maps it to localized copy, so a log-only identifier never reaches the UI. The codes are a
 * wire contract: renaming one breaks the JS mapping, not just a log.
 */
fun ChainedReadinessRefusal.userFacingReasonCode(): String = when (this) {
    ChainedReadinessRefusal.NOT_ENTITLED -> "not-entitled"
    ChainedReadinessRefusal.DEVICE_INELIGIBLE -> "device-ineligible"
    ChainedReadinessRefusal.DISABLED_BY_USER -> "disabled-by-user"
    ChainedReadinessRefusal.NO_CONFIGURATION -> "not configured"
    ChainedReadinessRefusal.NO_USABLE_TUNNEL_DNS -> "tunnel-dns-unavailable"
    ChainedReadinessRefusal.MALFORMED_TUNNEL_DNS -> "tunnel-dns-malformed"
    ChainedReadinessRefusal.NO_USABLE_IPV4_ROUTE -> "no-ipv4-route"
    ChainedReadinessRefusal.TUNNEL_DNS_NOT_ROUTED -> "tunnel-dns-unrouted"
    ChainedReadinessRefusal.ROUTES_COLLIDE -> "routes-collide"
    ChainedReadinessRefusal.CLIENT_DNS_OVER_TUNNEL_UNAVAILABLE -> "client-dns-unavailable"
}
