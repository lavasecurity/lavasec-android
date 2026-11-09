package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedUpstreamConfigurationValidator

/** Stateless IPv4 translation between the one TUN address and a row's assigned address.
 * Repairs IP, TCP/UDP pseudoheaders and ICMP quotations using RFC 1624. Unknown protocols
 * are refused when translation is required; no flow table or credential is retained.
 */
internal class ChainedStackAddressTranslation(localAddress: String, remoteAddress: String) {
    private val local = octets(localAddress)
    private val remote = octets(remoteAddress)

    fun outbound(packet: ByteArray): ByteArray? = rewrite(packet, local, remote, true)
    fun inbound(packet: ByteArray): ByteArray? = rewrite(packet, remote, local, false)

    /** ICMP errors belong to the quoted outgoing destination, not the reporting router. */
    fun inboundRouteAddress(packet: ByteArray): Long? {
        if (packet.size < 20 || u(packet[0]) ushr 4 != 4) return null
        val header = (u(packet[0]) and 15) * 4
        if (header < 20 || packet.size < header || !matches(packet, 16, remote)) return null
        var address = 12
        if (u(packet[9]) == 1 && packet.size > header && u(packet[header]) in ICMP_ERRORS) {
            val quote = header + 8
            if ((word(packet, 6) and 0x3fff) != 0 || packet.size < quote + 20 || u(packet[quote]) ushr 4 != 4 ||
                (u(packet[quote]) and 15) < 5 || packet.size < quote + (u(packet[quote]) and 15) * 4 || !matches(packet, quote + 12, remote)) return null
            address = quote + 16
        }
        return ipv4(packet, address)
    }

    private fun rewrite(packet: ByteArray, from: ByteArray, to: ByteArray, source: Boolean): ByteArray? {
        if (packet.size < 20 || u(packet[0]) ushr 4 != 4) return null
        val header = (u(packet[0]) and 15) * 4
        if (header < 20 || packet.size < header || word(packet, 2) != packet.size || !matches(packet, if (source) 12 else 16, from)) return null
        if (from.contentEquals(to)) return packet.copyOf()
        val bytes = packet.copyOf()
        return if (change(bytes, 0, bytes.size, from, to, source, false)) bytes else null
    }

    private fun change(bytes: ByteArray, base: Int, end: Int, from: ByteArray, to: ByteArray, source: Boolean, quoted: Boolean): Boolean {
        if (end - base < 20 || u(bytes[base]) ushr 4 != 4) return false
        val header = (u(bytes[base]) and 15) * 4
        if (header < 20 || end - base < header) return false
        val address = base + if (source) 12 else 16
        if (!matches(bytes, address, from)) return false
        val fragment = word(bytes, base + 6)
        val offset = (fragment and 0x1fff) * 8
        val protocol = u(bytes[base + 9])
        val payload = base + header
        if (protocol != 6 && protocol != 17 && protocol != 1) return false
        var checksum: Int? = null
        if (protocol == 6 || protocol == 17) {
            val field = if (protocol == 6) 16 else 6
            if (offset <= field && field < offset + end - payload) {
                val at = payload + field - offset
                if (at + 2 > end) return false
                if (protocol != 17 || word(bytes, at) != 0) checksum = at
            } else if (offset == 0 && !quoted) return false
        }
        if (protocol == 1 && offset == 0) {
            if (end - payload < 8) return false
            val type = u(bytes[payload])
            if (type in ICMP_ERRORS) {
                if (quoted || (fragment and 0x2000) != 0) return false
                val old = bytes.copyOf()
                if (!change(bytes, payload + 8, end, from, to, !source, true)) return false
                var sum = word(bytes, payload + 2)
                for (index in payload + 8 until end - 1 step 2) sum = adjusted(sum, word(old, index), word(bytes, index))
                put(bytes, payload + 2, sum)
            } else if (type != 0 && type != 8) return false
        }
        for (index in 0 until 4 step 2) {
            val old = word(from, index)
            val new = word(to, index)
            put(bytes, base + 10, adjusted(word(bytes, base + 10), old, new))
            checksum?.let { at ->
                val value = adjusted(word(bytes, at), old, new)
                put(bytes, at, if (protocol == 17 && value == 0) 65535 else value)
            }
            put(bytes, address + index, new)
        }
        return true
    }

    private fun adjusted(checksum: Int, old: Int, new: Int): Int {
        var sum = (checksum.inv() and 65535) + (old.inv() and 65535) + new
        while (sum > 65535) sum = (sum and 65535) + (sum ushr 16)
        return sum.inv() and 65535
    }

    companion object {
        private val ICMP_ERRORS = setOf(3, 4, 5, 11, 12)
        internal fun octets(address: String): ByteArray = requireNotNull(ChainedUpstreamConfigurationValidator.ipv4Octets(address)).map { it.toByte() }.toByteArray()
        internal fun ipv4(bytes: ByteArray, at: Int): Long = (at until at + 4).fold(0L) { value, index -> (value shl 8) or u(bytes[index]).toLong() }
        internal fun u(value: Byte): Int = value.toInt() and 255
        internal fun word(bytes: ByteArray, at: Int): Int = (u(bytes[at]) shl 8) or u(bytes[at + 1])
        private fun put(bytes: ByteArray, at: Int, value: Int) { bytes[at] = (value ushr 8).toByte(); bytes[at + 1] = value.toByte() }
        private fun matches(bytes: ByteArray, at: Int, address: ByteArray): Boolean = at >= 0 && at + 4 <= bytes.size && (0..3).all { bytes[at + it] == address[it] }
    }
}
