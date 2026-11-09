package com.lavasec.app.vpn.qa

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress

/** Debug variant only: the Release variant contains no socket sender. */
internal object DnsLeakCanaryEmitterFactory {
    fun create(service: VpnService, network: Network, arm: DnsLeakCanaryArm,
        serviceGeneration: Long, nowMillis: () -> Long): PreparedDnsLeakCanary =
        PhysicalDatagram(service, network, arm, serviceGeneration, nowMillis)

    private class PhysicalDatagram(
        private val service: VpnService,
        private val network: Network,
        private val arm: DnsLeakCanaryArm,
        private val serviceGeneration: Long,
        private val nowMillis: () -> Long,
    ) : PreparedDnsLeakCanary {
        private val lock = Any()
        private var fd: FileDescriptor? = null
        private var closed = false
        private val bytes = requireNotNull(DnsLeakCanary.resolverBytes(arm.resolverIP))
        private val address = InetAddress.getByAddress(bytes) // Never name resolution.
        private val query = DnsLeakCanary.query(arm.nonce)

        override fun prepare(): Boolean = synchronized(lock) {
            if (closed || fd != null || !eligible()) return false
            try {
                // Android documents O_NONBLOCK as the same socket flag as SOCK_NONBLOCK,
                // available since API 21; SOCK_NONBLOCK itself is public only since API 29.
                val socket = Os.socket(if (bytes.size == 4) OsConstants.AF_INET else OsConstants.AF_INET6,
                    OsConstants.SOCK_DGRAM or OsConstants.O_NONBLOCK, 0)
                fd = socket
                // dup refers to the SAME socket; protecting it sets the shared socket's mark.
                // The duplicate only exposes the public numeric fd API and closes immediately.
                val protected = ParcelFileDescriptor.dup(socket).use { service.protect(it.fd) }
                if (!protected) { close(); return false }
                network.bindSocket(socket) // protect BEFORE captured NOT_VPN network bind.
                // UDP connect sends no DNS datagram. It fixes the numeric peer and selects the
                // actual local source tuple so getsockname does not report wildcard 0.0.0.0.
                Os.connect(socket, address, 53)
                true
            } catch (_: Exception) { close(); false }
        }

        override fun send(chainedSessionGeneration: Long): DnsLeakCanarySend = synchronized(lock) {
            val socket = fd ?: return DnsLeakCanarySend.NoDatagramSent
            if (closed) return DnsLeakCanarySend.NoDatagramSent
            // The caller holds the exact runtime/attempt/arm fences. O_NONBLOCK ensures this
            // final syscall cannot park the engine dispatcher while a stop/disarm is waiting.
            val count = try {
                Os.sendto(socket, query, 0, query.size, 0, address, 53)
            } catch (_: ErrnoException) { return DnsLeakCanarySend.NoDatagramSent }
            if (count != query.size) return if (count == 0) DnsLeakCanarySend.NoDatagramSent
                else DnsLeakCanarySend.Indeterminate
            val local = runCatching { Os.getsockname(socket) as? InetSocketAddress }.getOrNull()
            DnsLeakCanarySend.Accepted(DnsLeakCanaryReceipt(
                arm = arm, serviceGeneration = serviceGeneration,
                chainedSessionGeneration = chainedSessionGeneration, networkHandle = network.networkHandle,
                acceptedBytes = count, observedAtMillis = nowMillis().coerceAtLeast(0),
                localAddress = local?.address?.hostAddress, localPort = local?.port,
            ))
        }

        private fun eligible(): Boolean {
            val capabilities = service.getSystemService(ConnectivityManager::class.java)
                ?.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        override fun close(): Unit = synchronized(lock) {
            closed = true
            fd?.let { runCatching { Os.close(it) } }
            fd = null
        }
    }
}
