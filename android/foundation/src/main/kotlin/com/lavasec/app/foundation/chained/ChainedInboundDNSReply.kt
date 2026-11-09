package com.lavasec.app.foundation.chained

/**
 * Reads a delivered inner IPv4 packet's transport source port, to tell an actual DNS reply from
 * ordinary traffic a resolver ADDRESS also serves.
 *
 * Ported from iOS `ChainedInboundDNSReply`. A public resolver IP does not only serve DNS: `1.1.1.1`,
 * `8.8.8.8` and `9.9.9.9` all answer HTTPS on the same address. Any accounting that treats a
 * resolver-sourced packet as "DNS" by ADDRESS alone is wrong, so the port is read instead.
 *
 * ## Fail-closed asymmetry
 *
 * [carriesNonDNSSourcePort] returns true ONLY for a packet whose UDP/TCP source port it can
 * positively read AND that port is not 53. Everything it cannot read that way — a port-53 reply, a
 * non-first fragment carrying no transport header, a non-UDP/TCP protocol, a truncated header —
 * returns false, so the caller keeps treating it as DNS-shaped. Only a byte this can PROVE is
 * non-DNS is reported as such.
 *
 * ## Allocation-free over caller-owned bytes
 *
 * A bounded walk of the fixed IPv4 header, no copy and no allocation, mirroring the iOS reading on
 * the inbound side.
 */
object ChainedInboundDNSReply {
    /** The DNS transport port, in the inner packet's transport SOURCE port. */
    const val DNS_PORT: Int = 53

    private const val IPV4_FIXED_HEADER_BYTES = 20
    private const val IPPROTO_TCP = 6
    private const val IPPROTO_UDP = 17

    /**
     * The inner packet's UDP or TCP source port, or `null` when it cannot be positively read
     * (not IPv4, a non-first fragment, not UDP/TCP, or a truncated header).
     */
    fun sourcePort(packet: ByteArray): Int? {
        if (packet.size < IPV4_FIXED_HEADER_BYTES) return null
        if ((packet[0].toInt() and 0xff) ushr 4 != 4) return null

        val headerLength = (packet[0].toInt() and 0x0f) * 4
        if (headerLength < IPV4_FIXED_HEADER_BYTES) return null
        if (packet.size < headerLength + 2) return null

        // A non-first fragment carries no transport header, so its "source port" is not a fact
        // about this packet; leave it unreadable rather than reading payload bytes as a port.
        val fragmentOffset = (((packet[6].toInt() and 0xff) shl 8) or
            (packet[7].toInt() and 0xff)) and 0x1fff
        if (fragmentOffset != 0) return null

        val protocolNumber = packet[9].toInt() and 0xff
        if (protocolNumber != IPPROTO_UDP && protocolNumber != IPPROTO_TCP) return null

        return ((packet[headerLength].toInt() and 0xff) shl 8) or
            (packet[headerLength + 1].toInt() and 0xff)
    }

    /**
     * Whether [packet] positively carries a UDP or TCP source port OTHER than 53 — the signature of
     * ordinary traffic from a resolver address rather than a DNS reply.
     */
    fun carriesNonDNSSourcePort(packet: ByteArray): Boolean {
        val port = sourcePort(packet) ?: return false
        return port != DNS_PORT
    }

    /**
     * Whether [packet] is a DNS reply by the same fail-closed rule: a source port it can read AND
     * that port is 53. An unreadable packet is NOT a DNS reply.
     */
    fun isDNSReply(packet: ByteArray): Boolean = sourcePort(packet) == DNS_PORT
}
