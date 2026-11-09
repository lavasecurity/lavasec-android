package com.lavasec.app.vpn

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/** Packet engine only: every socket remains owned, protected and bound by Android. */
internal interface DoqSession : AutoCloseable {
    fun nextEvent(): ByteArray
    fun receive(packet: ByteArray): Int
    fun timeoutMillis(): Long
    fun onTimeout()
    fun approveIpIdentity()
    fun cancel()
}

internal fun interface DoqSessionFactory {
    fun create(endpoint: CustomDoqEndpoint, peer: InetAddress, postedQuery: ByteArray): DoqSession
}

/** Trusted roots supplied explicitly; an empty/missing trust store fails before network I/O. */
internal class NativeDoqSessionFactory(
    private val trustLocations: () -> String = {
        listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
            .filter { File(it).isDirectory }
            .joinToString("\n") { "dir:$it" }
    },
) : DoqSessionFactory {
    override fun create(
        endpoint: CustomDoqEndpoint,
        peer: InetAddress,
        postedQuery: ByteArray,
    ): DoqSession {
        val address = InetSocketAddress(peer, endpoint.port)
        val host = requireNotNull(address.address.hostAddress)
        val peerToken = if (host.contains(':')) "[$host]:${address.port}" else "$host:${address.port}"
        val handle = NativeDoqCore.create(endpoint.host, peerToken, postedQuery, trustLocations())
        check(handle != 0L) { "DoQ trust or session initialization failed" }
        return object : DoqSession {
            // A query confines all calls, including free, to its IO worker. Retirement closes
            // its socket through the existing registry; it never frees a JNI handle in use.
            private var current = handle
            override fun nextEvent(): ByteArray = NativeDoqCore.nextEvent(current)
            override fun receive(packet: ByteArray): Int = NativeDoqCore.receive(current, packet)
            override fun timeoutMillis(): Long = NativeDoqCore.timeoutMillis(current)
            override fun onTimeout() = NativeDoqCore.onTimeout(current)
            override fun approveIpIdentity() = NativeDoqCore.approveIpIdentity(current)
            override fun cancel() = NativeDoqCore.cancel(current)
            override fun close() {
                if (current != 0L) {
                    NativeDoqCore.free(current)
                    current = 0
                }
            }
        }
    }
}

/** Kept as one class so JNI names survive package-private Kotlin implementation details. */
internal object NativeDoqCore {
    init { System.loadLibrary("lavasec_doq_core") }
    external fun create(host: String, peer: String, query: ByteArray, trust: String): Long
    external fun nextEvent(handle: Long): ByteArray
    external fun receive(handle: Long, packet: ByteArray): Int
    external fun timeoutMillis(handle: Long): Long
    external fun onTimeout(handle: Long)
    external fun approveIpIdentity(handle: Long)
    external fun cancel(handle: Long)
    external fun free(handle: Long)
}
