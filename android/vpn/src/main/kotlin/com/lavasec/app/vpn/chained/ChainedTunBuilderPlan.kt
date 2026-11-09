package com.lavasec.app.vpn.chained

import android.app.PendingIntent
import android.net.Network
import android.os.Build
import android.system.OsConstants
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.vpn.TunBuilder

/**
 * Installs a [ChainedRoutePlan] onto the platform `VpnService.Builder`.
 *
 * This is the only place the plan's fields become builder calls, so the DNS-only, full, and
 * split shapes cannot drift from the plan they were derived from. [com.lavasec.app.vpn.TunBuilderPlan]
 * delegates here with [ChainedRoutePlan.dnsOnly], which keeps the pre-chaining call sequence
 * (session, MTU, blocking, address, DNS server, DNS-capture route, both families, underlying
 * network, metered, configure intent) the single source of truth rather than a second copy.
 *
 * Device-gated: `android.net.Network` and `PendingIntent` require the platform, so the mapping
 * itself is exercised by the `:vpn` Robolectric suite and not the host JVM harness.
 */
internal object ChainedTunBuilderPlan {
    fun configure(
        builder: TunBuilder,
        productLabel: String,
        plan: ChainedRoutePlan,
        underlyingNetwork: Network,
        configureIntent: PendingIntent,
        sdkInt: Int,
    ) {
        builder.setSession(productLabel)
        builder.setMtu(plan.mtu)
        builder.setBlocking(false)
        builder.addAddress(plan.tunnelAddress, plan.tunnelPrefixLength)
        builder.addDnsServer(plan.dnsServerAddress)
        for (route in plan.includedIPv4Routes) {
            builder.addRoute(route.destinationAddress, route.prefixLength)
        }
        val tunnelIPv6Address = plan.tunnelIPv6Address
        if (tunnelIPv6Address != null) {
            builder.addAddress(
                tunnelIPv6Address,
                requireNotNull(plan.tunnelIPv6PrefixLength) {
                    "an IPv6 tunnel address requires a prefix length"
                },
            )
        }
        plan.dnsServerIPv6Address?.let(builder::addDnsServer)
        for (route in plan.includedIPv6Routes) {
            builder.addRoute(route.destinationAddress, route.prefixLength)
        }
        // The IPv4 address, DNS server, and route already enable AF_INET; repeat it explicitly so
        // both intended address-family policies remain visible and stable at the builder boundary.
        builder.allowFamily(OsConstants.AF_INET)
        // AF_INET6 carries the plan's address/route when one is claimed, and otherwise lets IPv6
        // fall through exactly as the DNS-only tunnel always has.
        builder.allowFamily(OsConstants.AF_INET6)
        builder.setUnderlyingNetworks(arrayOf(underlyingNetwork))
        if (sdkInt >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        builder.setConfigureIntent(configureIntent)
    }
}
