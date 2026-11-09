package com.lavasec.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicBoolean

internal interface DnsNetworkObservation : AutoCloseable {
    /** Called on the runtime worker, never from a ConnectivityManager callback. */
    fun snapshot(): SelectedVpnNetwork?
    /** System validation for this exact physical network; never inferred from a DNS response. */
    fun internetValidated(network: Network): Boolean = false
}

/** Persistent DNS-only observation. Callback payloads own DNS and capability truth. */
internal class AndroidDnsNetworkObservation(context: Context, initial: SelectedVpnNetwork, private val changed: () -> Unit) : DnsNetworkObservation {
    private val manager = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val snapshots = PhysicalNetworkSnapshots<Network>()
    private val closed = AtomicBoolean()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { snapshots.available(network) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (snapshots.capabilities(network, PhysicalNetworkCapabilities(
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)))) changed()
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (snapshots.dns(network, PhysicalDnsProperties(properties.dnsServers.toList(),
                    properties.isPrivateDnsActive, properties.privateDnsServerName))) changed()
        }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (snapshots.blocked(network, blocked)) changed()
        }
        override fun onLost(network: Network) { if (snapshots.lost(network)) changed() }
    }
    init {
        snapshots.seed(PhysicalNetworkSnapshot(initial.network, PhysicalNetworkCapabilities(true, true, initial.isCellular, initial.isWifi),
            PhysicalDnsProperties(initial.dnsServers, initial.privateDnsActive, initial.privateDnsName)))
        manager.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
    }
    @Suppress("DEPRECATION")
    override fun snapshot(): SelectedVpnNetwork? {
        if (closed.get()) return null
        // Preserve the runtime's platform ordering. Only identities are sampled here;
        // DNS and capability values are from the ordered callback cache.
        val candidates = buildList {
            manager.activeNetwork?.let(::add)
            manager.allNetworks.forEach { if (it !in this) add(it) }
        }
        return snapshots.select(candidates)?.let {
            SelectedVpnNetwork(it.key, it.dns.servers, it.capabilities.cellular, it.dns.privateDnsActive,
                it.dns.privateDnsName, it.capabilities.isWifi)
        }
    }
    override fun internetValidated(network: Network): Boolean = snapshots.internetValidated(network)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        snapshots.close()
        runCatching { manager.unregisterNetworkCallback(callback) }
    }
}
