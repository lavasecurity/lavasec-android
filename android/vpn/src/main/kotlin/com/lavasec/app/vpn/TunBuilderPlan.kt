package com.lavasec.app.vpn

import android.app.PendingIntent
import android.net.Network
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.vpn.chained.ChainedTunBuilderPlan

/**
 * Narrow adapter over the standard consumer [android.net.VpnService.Builder].
 *
 * Keeping the platform builder behind this surface makes the DNS-only route
 * contract testable without introducing device-owner or profile-owner APIs.
 */
internal interface TunBuilder {
    fun setSession(label: String)

    fun setMtu(mtu: Int)

    fun setBlocking(blocking: Boolean)

    fun addAddress(address: String, prefix: Int)

    fun addDnsServer(address: String)

    fun addRoute(address: String, prefix: Int)

    fun allowFamily(family: Int)

    fun setUnderlyingNetworks(networks: Array<Network>)

    fun setConfigureIntent(intent: PendingIntent)

    fun setMetered(metered: Boolean)
}

/**
 * Configures a TUN from a [ChainedRoutePlan].
 *
 * The four-argument [configure] applies the DNS-only plan — only the synthetic DNS server is
 * routed into Lava, and IPv6 is allowed to bypass. The five-argument overload applies the plan
 * the chained lifecycle resolved (full or split); [ChainedTunBuilderPlan] is the single
 * translation from plan fields to builder calls.
 */
internal class TunBuilderPlan(
    private val productLabel: String,
) {
    fun configure(
        builder: TunBuilder,
        underlyingNetwork: Network,
        configureIntent: PendingIntent,
        sdkInt: Int,
    ) {
        configure(builder, underlyingNetwork, configureIntent, sdkInt, ChainedRoutePlan.dnsOnly())
    }

    /**
     * Applies [plan] instead of the fixed DNS-only shape.
     *
     * Delegates to [ChainedTunBuilderPlan] so the DNS-only, full, and split shapes share one
     * translation from plan fields to builder calls; the four-argument overload above is exactly
     * that translation with [ChainedRoutePlan.dnsOnly].
     */
    fun configure(
        builder: TunBuilder,
        underlyingNetwork: Network,
        configureIntent: PendingIntent,
        sdkInt: Int,
        plan: ChainedRoutePlan,
    ) {
        ChainedTunBuilderPlan.configure(
            builder = builder,
            productLabel = productLabel,
            plan = plan,
            underlyingNetwork = underlyingNetwork,
            configureIntent = configureIntent,
            sdkInt = sdkInt,
        )
    }
}
