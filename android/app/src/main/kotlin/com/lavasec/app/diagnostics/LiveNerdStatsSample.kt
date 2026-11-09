package com.lavasec.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lavasec.app.foundation.chained.ChainedRoutingPolicy
import com.lavasec.app.platform.ChainedComposition
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.vpn.LavaVpnRuntimeBridge
import com.lavasec.app.vpn.VpnHealthStatus

/** Read on demand; never persisted or published on the hot product-state flow. */
internal data class LiveNerdStatsSample(
    val sampledAtMillis: Long,
    val health: VpnHealthStatus,
    val connected: Boolean,
    val status: String,
    val chained: Boolean,
    val network: String,
    val networkPath: String,
    val systemDns: String,
    val deviceDns: List<String>,
    val vpnConfiguration: String,
)

internal suspend fun sampleLiveNerdStats(context: Context, composition: ChainedComposition?): LiveNerdStatsSample? {
    // Read only the active configuration projection. Keys and peer endpoints never enter the sample.
    val vpnConfiguration = if (composition == null) "Off\nNo configuration" else {
        val enabled = composition.preferenceStore.loadEnabled()
        val stored = composition.configurationRepository.loadConfiguration()
        val config = stored?.takeIf { it.pendingImportId == null }?.configuration?.activeConfiguration
        listOfNotNull(if (!enabled) "Off" else null, if (config == null) "No configuration" else "WireGuard",
            config?.let { if (it.effectiveRoutingPolicy == ChainedRoutingPolicy.SPLIT_TUNNEL) "Split tunnel" else "Full tunnel" },
            config?.stackDNSAddresses?.joinToString(", ")?.ifEmpty { "No DNS servers configured" }).joinToString("\n")
    }
    val service = LavaVpnRuntimeBridge.serviceGeneration
    val health = LavaVpnRuntimeBridge.health.value
    val protection = LavaVpnRuntimeBridge.state.value
    val chained = LavaVpnRuntimeBridge.chainedActivity.value.active
    val manager = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    val active = manager.activeNetwork
    val networks = listOfNotNull(active) + manager.allNetworks.toList()
    val physical = com.lavasec.app.selectOrdinarySourceNetwork(networks.distinct().mapNotNull { network ->
        manager.getNetworkCapabilities(network)?.let {
            com.lavasec.app.OrdinarySourceNetworkCandidate(network,
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
    }, active)
    val capabilities = physical?.let(manager::getNetworkCapabilities)
    val properties = physical?.let(manager::getLinkProperties)
    val dns = properties?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
    val network = when {
        capabilities == null -> "Unavailable"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }
    val path = when {
        capabilities == null -> "No network"
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> "Captive portal"
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "Satisfied"
        else -> "Not validated"
    }
    val systemDns = if (properties == null) "Unavailable" else listOfNotNull(
        if (properties.isPrivateDnsActive) "Private DNS active" else "Network DNS",
        properties.privateDnsServerName?.takeIf { it.isNotBlank() },
        dns.joinToString(", ").ifEmpty { "No DNS servers available" },
    ).joinToString("\n")
    if (service != LavaVpnRuntimeBridge.serviceGeneration || protection != LavaVpnRuntimeBridge.state.value ||
        (health.health.context?.sessionGeneration?.let { it != service } == true)) return null
    val connected = service != null && health.health.isConnected
    return LiveNerdStatsSample(System.currentTimeMillis(), health, connected,
        when (protection) {
            is ProtectionState.Protected -> "Connected"
            is ProtectionState.Paused -> "Paused"
            is ProtectionState.Starting -> "Starting"
            is ProtectionState.Reconnecting -> "Reconnecting"
            is ProtectionState.AwaitingNetwork -> "Waiting for network"
            ProtectionState.Stopping -> "Stopping"
            is ProtectionState.Failed -> "Failed"
            else -> "Not connected"
        }, chained, network, path, systemDns, dns, vpnConfiguration)
}
