package com.lavasec.app.vpn

import java.net.BindException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Test peers must own the same port in both protocols before any DNS query is sent. */
internal class LocalDnsSockets private constructor(
    val udp: DatagramSocket,
    val tcp: ServerSocket?,
) : AutoCloseable {
    override fun close() {
        try { udp.close() } finally { tcp?.close() }
    }

    companion object {
        fun bind(
            address: InetAddress,
            tcp: Boolean,
            attempts: Int = 16,
            createUdp: () -> DatagramSocket = { DatagramSocket(0, address) },
        ): LocalDnsSockets {
            require(attempts > 0)
            repeat(attempts) { attempt ->
                val udp = createUdp()
                if (!tcp) return LocalDnsSockets(udp, null)
                var listener: ServerSocket? = null
                try {
                    listener = ServerSocket()
                    listener.reuseAddress = false
                    listener.bind(InetSocketAddress(address, udp.localPort), 1)
                    return LocalDnsSockets(udp, listener)
                } catch (failure: Exception) {
                    udp.close()
                    listener?.close()
                    // UDP's ephemeral allocation does not reserve the TCP port. Retry only
                    // that setup collision, never a query, assertion or transport failure.
                    if (failure !is BindException || attempt == attempts - 1) throw failure
                }
            }
            error("unreachable socket allocation")
        }
    }
}
