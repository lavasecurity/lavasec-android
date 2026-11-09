package com.lavasec.app.vpn

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * Ordinary-VpnService protection boundary.
 *
 * A later runtime owner may implement these overloads with `VpnService.protect`. This dormant
 * transport module neither creates nor owns a service.
 */
internal interface OrdinaryVpnSocketProtector {
    fun protect(socket: DatagramSocket): Boolean

    fun protect(socket: Socket): Boolean
}

/** Exact captured-Network binding boundary, injected independently from socket protection. */
internal interface ExactNetworkSocketBinder {
    fun bind(network: Network, socket: DatagramSocket)

    fun bind(network: Network, socket: Socket)
}

/**
 * Java/Android socket adapters for dormant underlying-network DNS transport.
 *
 * [DeviceDnsTransport] owns lifecycle ordering. This platform keeps protection and exact-Network
 * binding independently injectable so no traffic can precede either operation.
 */
internal class JavaNetDeviceDnsSocketPlatform(
    private val protector: OrdinaryVpnSocketProtector,
    private val binder: ExactNetworkSocketBinder,
    private val tcpSocketFactory: () -> Socket = ::Socket,
) : DeviceDnsSocketPlatform {
    override fun createUdp(): DeviceUdpSocket =
        JavaNetUdpSocket(DatagramSocket(null as SocketAddress?))

    override fun createTcp(): DeviceTcpSocket {
        val socket = tcpSocketFactory()
        try {
            socket.apply {
                // Android materializes a Socket descriptor lazily. A local wildcard bind exists
                // solely so ordinary VpnService protection can run before exact Network binding
                // and before the socket connects to any resolver.
                bind(InetSocketAddress(0))
            }
        } catch (failure: Throwable) {
            try {
                socket.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
        return JavaNetTcpSocket(socket)
    }

    override fun protect(socket: DeviceDnsSocket): Boolean =
        when (socket) {
            is JavaNetUdpSocket -> protector.protect(socket.delegate)
            is JavaNetTcpSocket -> protector.protect(socket.delegate)
            else -> false
        }

    override fun bind(socket: DeviceDnsSocket, network: Network) {
        when (socket) {
            is JavaNetUdpSocket -> binder.bind(network, socket.delegate)
            is JavaNetTcpSocket -> binder.bind(network, socket.delegate)
            else -> throw IllegalArgumentException("unknown underlying-network DNS socket")
        }
    }
}

private class JavaNetUdpSocket(
    val delegate: DatagramSocket,
) : DeviceUdpSocket {
    override fun send(
        query: ByteArray,
        server: InetAddress,
        port: Int,
        timeoutMillis: Int,
    ) {
        require(timeoutMillis > 0) { "timeout must be positive" }
        val wireQuery = query.copyOf()
        delegate.send(DatagramPacket(wireQuery, wireQuery.size, server, port))
    }

    override fun receive(maxBytes: Int, timeoutMillis: Int): DatagramReceive {
        require(maxBytes > 0) { "receive bound must be positive" }
        require(timeoutMillis > 0) { "timeout must be positive" }
        delegate.soTimeout = timeoutMillis
        val receiveBuffer = ByteArray(maxBytes)
        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
        delegate.receive(packet)
        return DatagramReceive(
            body = receiveBuffer.copyOf(packet.length),
            source = DatagramSource(
                address = requireNotNull(packet.address),
                port = packet.port,
            ),
        )
    }

    override fun close() {
        delegate.close()
    }
}

private class JavaNetTcpSocket(
    val delegate: Socket,
) : DeviceTcpSocket {
    private val input by lazy(LazyThreadSafetyMode.NONE) { delegate.getInputStream() }
    private val output by lazy(LazyThreadSafetyMode.NONE) { delegate.getOutputStream() }

    override fun connect(
        server: InetAddress,
        port: Int,
        timeoutMillis: Int,
    ) {
        require(timeoutMillis > 0) { "timeout must be positive" }
        delegate.connect(InetSocketAddress(server, port), timeoutMillis)
    }

    override fun writeFrame(frame: ByteArray, timeoutMillis: Int) {
        require(timeoutMillis > 0) { "timeout must be positive" }
        val wireFrame = frame.copyOf()
        // OutputStream's byte-array write is all-or-throw. The surrounding transport watchdog
        // owns the write deadline and physically closes this socket if the call remains blocked.
        output.write(wireFrame)
        output.flush()
    }

    override fun readLength(timeoutMillis: Int): Int? {
        delegate.soTimeout = positiveTimeout(timeoutMillis)
        val prefix = readExactly(LENGTH_PREFIX_BYTES) ?: return null
        return ((prefix[0].toInt() and 0xff) shl 8) or (prefix[1].toInt() and 0xff)
    }

    override fun readBody(length: Int, timeoutMillis: Int): ByteArray? {
        require(length >= 0) { "body length must not be negative" }
        delegate.soTimeout = positiveTimeout(timeoutMillis)
        return readExactly(length)
    }

    private fun readExactly(length: Int): ByteArray? {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) return null
            if (read > 0) offset += read
        }
        return body
    }

    private fun positiveTimeout(timeoutMillis: Int): Int {
        require(timeoutMillis > 0) { "timeout must be positive" }
        return timeoutMillis
    }

    override fun close() {
        delegate.close()
    }

    private companion object {
        const val LENGTH_PREFIX_BYTES = 2
    }
}
