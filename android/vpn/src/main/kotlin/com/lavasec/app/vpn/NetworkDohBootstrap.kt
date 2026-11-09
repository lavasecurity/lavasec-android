package com.lavasec.app.vpn

import android.net.Network
import java.net.InetAddress
import java.util.Collections
import okhttp3.Dns

/**
 * Resolves a custom DoH endpoint's own hostname through the exact underlying network the DoH
 * socket is bound to, never the process resolver (which the tunnel may own).
 *
 * Only the bootstrap lookup is network-scoped; the DoH connection itself is still protected
 * and bound by [ProtectedDohSocketFactory], so no unguarded query path is introduced. The
 * pinned Mullvad endpoint keeps using [MullvadDohEndpoint]'s literal bootstrap list instead.
 */
internal class NetworkDohBootstrap(private val network: Network) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        Collections.unmodifiableList(network.getAllByName(hostname).toList())
}

/** DNS-free lookup for a catalog endpoint; TLS still verifies the original URL hostname. */
internal class CatalogDohBootstrap(
    private val hostname: String,
    addresses: List<InetAddress>,
) : Dns {
    private val addresses = Collections.unmodifiableList(addresses.toList())
    init { require(this.addresses.isNotEmpty()) }
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname != this.hostname) throw java.net.UnknownHostException(hostname)
        return addresses
    }
}
