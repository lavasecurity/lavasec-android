package com.lavasec.app.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections

/** Stable selection policy for the resolver addresses supplied by the selected network. */
object DeviceDnsAddressPolicy {
    private const val MAX_SERVERS = 8
    private val wellKnownNat64Prefix = byteArrayOf(
        0x00, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0,
    )

    fun select(addresses: List<InetAddress>, isCellular: Boolean): List<InetAddress> {
        val unique = LinkedHashMap<List<Byte>, InetAddress>()
        addresses.forEach { address ->
            if (isUsable(address)) unique.putIfAbsent(address.address.toList(), address)
        }
        val first = if (isCellular) Inet6Address::class.java else Inet4Address::class.java
        val second = if (isCellular) Inet4Address::class.java else Inet6Address::class.java
        return Collections.unmodifiableList(
            (unique.values.filter { first.isInstance(it) } + unique.values.filter { second.isInstance(it) })
                .take(MAX_SERVERS),
        )
    }

    fun isUsable(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> isUsableIpv4(address.address)
        is Inet6Address -> isUsableIpv6(address)
        else -> false
    }

    private fun isUsableIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first != 0 && first != 127 && !(first == 169 && second == 254) &&
            first !in 224..239 && !bytes.all { (it.toInt() and 0xff) == 255 }
    }

    private fun isUsableIpv6(address: Inet6Address): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
            return false
        }
        val bytes = address.address
        return !bytes.copyOfRange(0, 12).contentEquals(wellKnownNat64Prefix) ||
            isUsableIpv4(bytes.copyOfRange(12, 16))
    }
}
