package com.lavasec.app.foundation.chained

/**
 * A parsed UDP datagram plus the client reply builder the chained T0 resolver egress needs.
 *
 * WHY THIS EXISTS. While chained, client DNS is intercepted and served by a userspace resolver
 * egress that has NO socket: the query is rebuilt as an inner IPv4/UDP packet and encrypted by the
 * WireGuard engine ([ChainedOutboundPacketClassifier] carves it out on the local source port), and
 * the decrypted reply is rewritten back into a response for the original client tuple. That needs a
 * general IPv4/UDP codec, not the DNS-only `Ipv4UdpDnsPacket` (which only admits destination port
 * 53, while a resolver REPLY's destination port is the ephemeral port the engine used).
 *
 * Pure and bounded: no I/O, no Android types, and a payload copy owned by the value. It is the
 * Android analogue of the iOS `IPv4UDPDNSPacket` parse/build pair, kept in the foundation layer so
 * the egress can be exercised in a host JVM test.
 */
class ChainedInnerUdpPacket private constructor(
    sourceAddress: ByteArray,
    destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val identification: Int,
    payload: ByteArray,
) {
    private val sourceAddressBytes = sourceAddress.copyOf()
    private val destinationAddressBytes = destinationAddress.copyOf()
    private val payloadBytes = payload.copyOf()

    /** Network-order source bytes: four for T0/IPv4, sixteen for an admitted IPv6 client. */
    val sourceAddress: ByteArray get() = sourceAddressBytes.copyOf()

    /** Network-order destination bytes in the same family as the source. */
    val destinationAddress: ByteArray get() = destinationAddressBytes.copyOf()

    /** The UDP payload (the DNS message). */
    val payload: ByteArray get() = payloadBytes.copyOf()

    /**
     * A response to this packet: addresses and ports are reversed and [responsePayload] becomes the
     * UDP payload. This is the CLIENT-facing rewrite (the original query's source becomes the
     * destination), not the resolver-facing packet the egress sends.
     */
    fun responseWith(responsePayload: ByteArray): ByteArray = build(
        sourceAddress = destinationAddressBytes,
        destinationAddress = sourceAddressBytes,
        sourcePort = destinationPort,
        destinationPort = sourcePort,
        identification = identification,
        payload = responsePayload,
    )

    companion object {
        private const val IPV4_FIXED_HEADER_BYTES = 20
        private const val IPV4_HEADER_BYTES = 20
        private const val UDP_HEADER_BYTES = 8
        private const val UDP_PROTOCOL = 17
        private const val DNS_PORT = 53
        private const val DEFAULT_TTL = 64

        private const val IPV4_TOTAL_LENGTH_OFFSET = 2
        private const val IPV4_IDENTIFICATION_OFFSET = 4
        private const val IPV4_FLAGS_AND_FRAGMENT_OFFSET = 6
        private const val IPV4_TTL_OFFSET = 8
        private const val IPV4_PROTOCOL_OFFSET = 9
        private const val IPV4_CHECKSUM_OFFSET = 10
        private const val IPV4_SOURCE_ADDRESS_OFFSET = 12
        private const val IPV4_DESTINATION_ADDRESS_OFFSET = 16

        private const val IPV4_VERSION = 4
        private const val IPV4_VERSION_AND_HEADER_LENGTH = 0x45
        private const val IPV4_DO_NOT_FRAGMENT_FLAG = 0x4000
        private const val IPV4_UNSUPPORTED_FRAGMENT_BITS = 0x3fff

        /**
         * Parses one inner IPv4/UDP datagram, or `null` when it is not a complete, unfragmented
         * UDP datagram. Any destination port is accepted: the egress parses both the client query
         * (destination 53) and the resolver reply (destination = the engine's ephemeral port).
         */
        fun parse(capture: ByteArray): ChainedInnerUdpPacket? {
            if (capture.size < IPV4_FIXED_HEADER_BYTES + UDP_HEADER_BYTES) return null

            val versionAndHeaderLength = capture[0].toInt() and 0xff
            if (versionAndHeaderLength ushr 4 != IPV4_VERSION) return null

            val ipv4HeaderBytes = (versionAndHeaderLength and 0x0f) * 4
            if (ipv4HeaderBytes < IPV4_FIXED_HEADER_BYTES) return null
            if (ipv4HeaderBytes + UDP_HEADER_BYTES > capture.size) return null

            val totalLength = readUnsignedShort(capture, IPV4_TOTAL_LENGTH_OFFSET)
            if (totalLength < ipv4HeaderBytes + UDP_HEADER_BYTES) return null
            if (totalLength > capture.size) return null

            val flagsAndFragmentOffset =
                readUnsignedShort(capture, IPV4_FLAGS_AND_FRAGMENT_OFFSET)
            if (flagsAndFragmentOffset and IPV4_UNSUPPORTED_FRAGMENT_BITS != 0) return null
            if ((capture[IPV4_PROTOCOL_OFFSET].toInt() and 0xff) != UDP_PROTOCOL) return null

            val udpOffset = ipv4HeaderBytes
            val udpLength = readUnsignedShort(capture, udpOffset + 4)
            if (udpLength < UDP_HEADER_BYTES) return null
            if (ipv4HeaderBytes + udpLength != totalLength) return null

            val payloadLength = udpLength - UDP_HEADER_BYTES
            if (payloadLength < 1) return null

            val payloadOffset = udpOffset + UDP_HEADER_BYTES
            return ChainedInnerUdpPacket(
                sourceAddress = capture.copyOfRange(
                    IPV4_SOURCE_ADDRESS_OFFSET,
                    IPV4_SOURCE_ADDRESS_OFFSET + 4,
                ),
                destinationAddress = capture.copyOfRange(
                    IPV4_DESTINATION_ADDRESS_OFFSET,
                    IPV4_DESTINATION_ADDRESS_OFFSET + 4,
                ),
                sourcePort = readUnsignedShort(capture, udpOffset),
                destinationPort = readUnsignedShort(capture, udpOffset + 2),
                identification = readUnsignedShort(capture, IPV4_IDENTIFICATION_OFFSET),
                payload = capture.copyOfRange(payloadOffset, payloadOffset + payloadLength),
            )
        }

        /** Explicit dual-family client DNS admission; the general engine parse remains IPv4-only. */
        fun parseClientDns(capture: ByteArray): ChainedInnerUdpPacket? {
            val parsed = if (capture.isNotEmpty() && (capture[0].toInt() and 255) ushr 4 == 6) {
                parseIPv6Udp(capture)
            } else parse(capture)
            return parsed?.takeIf { it.destinationPort == DNS_PORT }
        }

        /** Fixed-header, unfragmented IPv6 UDP, explicitly requested by the client/reply path. */
        fun parseIPv6Udp(capture: ByteArray): ChainedInnerUdpPacket? {
            if (capture.size < 48 || (capture[0].toInt() and 255) ushr 4 != 6 ||
                (capture[6].toInt() and 255) != UDP_PROTOCOL
            ) return null
            val payloadLength = readUnsignedShort(capture, 4)
            if (payloadLength < 8 || 40 + payloadLength > capture.size) return null
            val udpLength = readUnsignedShort(capture, 44)
            if (udpLength <= 8 || 40 + udpLength > 40 + payloadLength) return null
            return ChainedInnerUdpPacket(
                capture.copyOfRange(8, 24), capture.copyOfRange(24, 40),
                readUnsignedShort(capture, 40), readUnsignedShort(capture, 42), 0,
                capture.copyOfRange(48, 40 + udpLength),
            )
        }

        /**
         * Builds one UDP datagram in the supplied address family. IPv6 replies compute the
         * mandatory pseudo-header UDP checksum; no IPv6 bytes are sent to the WireGuard peer.
         *
         * IPv4 checksum is computed over the header; the UDP checksum is left zero (explicitly
         * valid for IPv4). The DF flag is set and the TTL is the ordinary 64, matching the DNS-only
         * packet builder.
         */
        fun build(
            sourceAddress: ByteArray,
            destinationAddress: ByteArray,
            sourcePort: Int,
            destinationPort: Int,
            identification: Int,
            payload: ByteArray,
        ): ByteArray {
            require(sourceAddress.size == destinationAddress.size && (sourceAddress.size == 4 || sourceAddress.size == 16)) {
                "source and destination must share an IPv4 or IPv6 family"
            }
            require(sourcePort in 0..0xffff) { "source port must fit an unsigned 16-bit field" }
            require(destinationPort in 0..0xffff) {
                "destination port must fit an unsigned 16-bit field"
            }
            require(identification in 0..0xffff) {
                "identification must fit an unsigned 16-bit field"
            }

            if (sourceAddress.size == 16) return buildIPv6(sourceAddress, destinationAddress, sourcePort, destinationPort, payload)
            val totalLength = IPV4_HEADER_BYTES + UDP_HEADER_BYTES + payload.size
            return ByteArray(totalLength).also { packet ->
                packet[0] = IPV4_VERSION_AND_HEADER_LENGTH.toByte()
                writeUnsignedShort(packet, IPV4_TOTAL_LENGTH_OFFSET, totalLength)
                writeUnsignedShort(packet, IPV4_IDENTIFICATION_OFFSET, identification)
                writeUnsignedShort(
                    packet,
                    IPV4_FLAGS_AND_FRAGMENT_OFFSET,
                    IPV4_DO_NOT_FRAGMENT_FLAG,
                )
                packet[IPV4_TTL_OFFSET] = DEFAULT_TTL.toByte()
                packet[IPV4_PROTOCOL_OFFSET] = UDP_PROTOCOL.toByte()
                sourceAddress.copyInto(packet, IPV4_SOURCE_ADDRESS_OFFSET)
                destinationAddress.copyInto(packet, IPV4_DESTINATION_ADDRESS_OFFSET)

                val udpOffset = IPV4_HEADER_BYTES
                writeUnsignedShort(packet, udpOffset, sourcePort)
                writeUnsignedShort(packet, udpOffset + 2, destinationPort)
                writeUnsignedShort(packet, udpOffset + 4, UDP_HEADER_BYTES + payload.size)
                writeUnsignedShort(packet, udpOffset + 6, 0)
                payload.copyInto(packet, udpOffset + UDP_HEADER_BYTES)

                writeUnsignedShort(
                    packet,
                    IPV4_CHECKSUM_OFFSET,
                    ipv4HeaderChecksum(packet, IPV4_HEADER_BYTES),
                )
            }
        }

        private fun buildIPv6(
            source: ByteArray, destination: ByteArray, sourcePort: Int, destinationPort: Int, payload: ByteArray,
        ): ByteArray {
            val udpLength = 8 + payload.size
            require(udpLength <= 0xffff) { "IPv6 UDP payload must fit the 16-bit length field" }
            val packet = ByteArray(40 + udpLength)
            packet[0] = 0x60
            writeUnsignedShort(packet, 4, udpLength)
            packet[6] = UDP_PROTOCOL.toByte(); packet[7] = DEFAULT_TTL.toByte()
            source.copyInto(packet, 8); destination.copyInto(packet, 24)
            writeUnsignedShort(packet, 40, sourcePort); writeUnsignedShort(packet, 42, destinationPort)
            writeUnsignedShort(packet, 44, udpLength)
            payload.copyInto(packet, 48)
            // Mandatory IPv6 pseudo-header: source, destination, 32-bit UDP length, next-header.
            var sum = udpLength.toLong() + UDP_PROTOCOL
            for (offset in 8 until 40 step 2) sum += readUnsignedShort(packet, offset)
            for (offset in 40 until packet.size step 2) {
                sum += ((packet[offset].toInt() and 255) shl 8) +
                    if (offset + 1 < packet.size) packet[offset + 1].toInt() and 255 else 0
            }
            while (sum > 0xffff) sum = (sum and 0xffff) + (sum ushr 16)
            val checksum = sum.inv().toInt() and 0xffff
            writeUnsignedShort(packet, 46, if (checksum == 0) 0xffff else checksum)
            return packet
        }

        /** Whether [capture] is an unfragmented IPv4/UDP datagram addressed to port 53. */
        fun isDnsQuery(capture: ByteArray): Boolean =
            parse(capture)?.let { it.destinationPort == DNS_PORT } == true

        private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or
                (bytes[offset + 1].toInt() and 0xff)

        private fun writeUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }

        private fun ipv4HeaderChecksum(bytes: ByteArray, headerLength: Int): Int {
            var sum = 0L
            for (offset in 0 until headerLength step 2) {
                sum += readUnsignedShort(bytes, offset).toLong()
                while (sum > 0xffffL) {
                    sum = (sum and 0xffffL) + (sum ushr 16)
                }
            }
            return sum.inv().toInt() and 0xffff
        }
    }
}
