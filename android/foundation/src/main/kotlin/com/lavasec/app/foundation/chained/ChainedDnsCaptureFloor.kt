package com.lavasec.app.foundation.chained

import com.lavasec.app.foundation.dns.ResolverAddressFamilies
import java.util.Collections
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Resolver host routes a chained split activation serves through its client DNS path.
 * DNS-only does not use this floor.
 *
 * Mirrors frozen iOS DNSCaptureFloor plus its on-link-gateway-class exclusion: a private
 * resolver is excluded even when it is not the gateway. This accepted coverage loss prevents
 * claiming gateway-class addresses. It does not identify or exclude an arbitrary public gateway.
 */
@ConsistentCopyVisibility
data class ChainedDnsCaptureFloor private constructor(
    val ipv4Routes: List<IPv4Route>,
    private val destinations: Set<Long>,
    val ipv6Routes: List<IPv6Route>,
    private val ipv6Destinations: List<IPv6Words>,
) {
    /** Route ordering is display metadata; capture membership is the installed address set. */
    fun hasSameDestinations(other: ChainedDnsCaptureFloor): Boolean =
        destinations == other.destinations && ipv6Destinations.toSet() == other.ipv6Destinations.toSet()

    /** Compares the already installed destination bytes without parsing or allocating per packet. */
    fun containsIPv4Destination(packet: ByteArray, offset: Int): Boolean {
        if (offset < 0 || packet.size - offset < 4) return false
        val value = ((packet[offset].toLong() and 255L) shl 24) or
            ((packet[offset + 1].toLong() and 255L) shl 16) or
            ((packet[offset + 2].toLong() and 255L) shl 8) or
            (packet[offset + 3].toLong() and 255L)
        return value in destinations
    }

    private data class IPv6Words(val a: Long, val b: Long, val c: Long, val d: Long)

    fun containsIPv6Destination(packet: ByteArray, offset: Int): Boolean {
        if (offset < 0 || packet.size - offset < 16) return false
        val a = word(packet, offset); val b = word(packet, offset + 4)
        val c = word(packet, offset + 8); val d = word(packet, offset + 12)
        for (index in ipv6Destinations.indices) {
            val value = ipv6Destinations[index]
            if (value.a == a && value.b == b && value.c == c && value.d == d) return true
        }
        return false
    }

    companion object {
        val empty = ChainedDnsCaptureFloor(emptyList(), emptySet(), emptyList(), emptyList())

        /** IPv4 half of the exact frozen curated set; never fetched or inferred from a provider. */
        val curatedIPv4ResolverAddresses: List<String> = Collections.unmodifiableList(listOf(
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15",
            "194.242.2.2", "194.242.2.3",
        ))

        val curatedIPv6ResolverAddresses: List<String> = Collections.unmodifiableList(listOf(
            "2001:4860:4860::8888", "2001:4860:4860::8844", "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2620:fe::fe", "2620:fe::9", "2620:119:35::35", "2620:119:53::53",
            "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff", "2a07:e340::2", "2a07:e340::3",
        ))

        private fun word(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 255L) shl 24) or
                ((bytes[offset + 1].toLong() and 255L) shl 16) or
                ((bytes[offset + 2].toLong() and 255L) shl 8) or (bytes[offset + 3].toLong() and 255L)

        private fun eligibleIPv6Literal(address: String): Inet6Address? {
            // Literal guard before InetAddress; no host resolution or zone-scoped identity.
            if (!address.contains(':') || !address.all { it in "0123456789abcdefABCDEF:." }) return null
            val parsed = runCatching { InetAddress.getByName(address) }.getOrNull() as? Inet6Address ?: return null
            val bytes = parsed.address
            // Mapped/compatible forms never become a resolver /128, as on the frozen floor.
            if (bytes.take(12).all { it == 0.toByte() } ||
                (bytes.take(10).all { it == 0.toByte() } && bytes[10] == (-1).toByte() && bytes[11] == (-1).toByte())
            ) return null
            if (!ResolverAddressFamilies.isUsableResolverAddress(address)) return null
            if ((bytes[0].toInt() and 0xfe) == 0xfc) return null // ULA / on-link gateway class
            return parsed
        }

        /** Snapshots the eligible ordinary network's DNS capture at activation, in stable order. */
        fun forCapturedResolvers(capturedResolverAddresses: List<String>): ChainedDnsCaptureFloor {
            val routes = mutableListOf<IPv4Route>()
            val seen = linkedSetOf<Long>()
            for (address in curatedIPv4ResolverAddresses + capturedResolverAddresses) {
                val octets = ChainedUpstreamConfigurationValidator.ipv4Octets(address) ?: continue
                if (!ResolverAddressFamilies.isUsableResolverAddress(address)) continue
                if (octets[0] == 10 ||
                    (octets[0] == 172 && octets[1] in 16..31) ||
                    (octets[0] == 192 && octets[1] == 168)
                ) continue
                if (address == ChainedRoutePlan.DNS_SERVER_ADDRESS) continue
                val value = (octets[0].toLong() shl 24) or (octets[1].toLong() shl 16) or
                    (octets[2].toLong() shl 8) or octets[3].toLong()
                if (seen.add(value)) routes += IPv4Route(address, 32)
            }
            val routes6 = mutableListOf<IPv6Route>()
            val seen6 = linkedSetOf<IPv6Words>()
            for (address in curatedIPv6ResolverAddresses + capturedResolverAddresses) {
                val parsed = eligibleIPv6Literal(address) ?: continue
                val bytes = parsed.address
                val value = IPv6Words(word(bytes, 0), word(bytes, 4), word(bytes, 8), word(bytes, 12))
                if (seen6.add(value)) routes6 += IPv6Route(requireNotNull(parsed.hostAddress), 128)
            }
            return ChainedDnsCaptureFloor(
                Collections.unmodifiableList(routes.toList()), Collections.unmodifiableSet(seen.toSet()),
                Collections.unmodifiableList(routes6.toList()), Collections.unmodifiableList(seen6.toList()),
            )
        }
    }
}
