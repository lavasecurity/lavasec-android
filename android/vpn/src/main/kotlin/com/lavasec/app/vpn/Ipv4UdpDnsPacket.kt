package com.lavasec.app.vpn

import java.net.Inet4Address
import java.net.InetAddress

/**
 * A bounded IPv4/UDP packet carrying a DNS message.
 *
 * Parsing deliberately ignores bytes beyond the IPv4 total length because a TUN read may return a
 * capture buffer larger than the datagram. IPv4 fragments are rejected: DNS interception only
 * handles complete UDP datagrams.
 */
internal class Ipv4UdpDnsPacket private constructor(
    val sourceAddress: Inet4Address,
    val destinationAddress: Inet4Address,
    val sourcePort: Int,
    val destinationPort: Int,
    val identification: Int,
    payload: ByteArray,
) {
    private val payloadBytes = payload.copyOf()

    val payload: ByteArray
        get() = payloadBytes.copyOf()

    val isSyntheticDnsDestination: Boolean
        get() = DnsOnlyTunContract.isSyntheticDnsAddress(destinationAddress.address)

    /**
     * Builds an IPv4/UDP response by reversing this request's addresses and ports.
     *
     * UDP checksums are optional for IPv4, so the emitted checksum is zero. The IPv4 header checksum
     * is always calculated over the complete 20-byte response header.
     */
    fun response(responsePayload: ByteArray): ByteArray {
        require(responsePayload.size <= DnsOnlyTunContract.MAX_RESPONSE_DNS_BYTES) {
            "DNS response exceeds the unfragmented tunnel MTU"
        }

        val totalLength = IPV4_HEADER_BYTES + UDP_HEADER_BYTES + responsePayload.size
        return ByteArray(totalLength).also { response ->
            response[0] = IPV4_VERSION_AND_HEADER_LENGTH.toByte()
            writeUnsignedShort(response, IPV4_TOTAL_LENGTH_OFFSET, totalLength)
            writeUnsignedShort(response, IPV4_IDENTIFICATION_OFFSET, identification)
            writeUnsignedShort(
                response,
                IPV4_FLAGS_AND_FRAGMENT_OFFSET,
                IPV4_DO_NOT_FRAGMENT_FLAG,
            )
            response[IPV4_TTL_OFFSET] = RESPONSE_TTL.toByte()
            response[IPV4_PROTOCOL_OFFSET] = UDP_PROTOCOL.toByte()
            destinationAddress.address.copyInto(response, IPV4_SOURCE_ADDRESS_OFFSET)
            sourceAddress.address.copyInto(response, IPV4_DESTINATION_ADDRESS_OFFSET)

            val udpOffset = IPV4_HEADER_BYTES
            writeUnsignedShort(response, udpOffset, destinationPort)
            writeUnsignedShort(response, udpOffset + 2, sourcePort)
            writeUnsignedShort(response, udpOffset + 4, UDP_HEADER_BYTES + responsePayload.size)
            // A zero UDP checksum explicitly means "not provided" for IPv4.
            writeUnsignedShort(response, udpOffset + 6, 0)
            responsePayload.copyInto(response, udpOffset + UDP_HEADER_BYTES)

            writeUnsignedShort(
                response,
                IPV4_CHECKSUM_OFFSET,
                ipv4HeaderChecksum(response, IPV4_HEADER_BYTES),
            )
        }
    }

    companion object {
        private const val IPV4_MINIMUM_HEADER_BYTES = DnsOnlyTunContract.IPV4_HEADER_BYTES
        private const val IPV4_HEADER_BYTES = DnsOnlyTunContract.IPV4_HEADER_BYTES
        private const val UDP_HEADER_BYTES = DnsOnlyTunContract.UDP_HEADER_BYTES
        private const val MAX_DNS_BYTES = 4_096
        private const val DNS_PORT = 53
        private const val UDP_PROTOCOL = 17
        private const val RESPONSE_TTL = 64

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
        private const val IPV4_RESERVED_FLAG = 0x8000
        private const val IPV4_DO_NOT_FRAGMENT_FLAG = 0x4000
        private const val IPV4_MORE_FRAGMENTS_FLAG = 0x2000
        private const val IPV4_FRAGMENT_OFFSET_MASK = 0x1fff
        private const val IPV4_UNSUPPORTED_FRAGMENT_BITS =
            IPV4_RESERVED_FLAG or IPV4_MORE_FRAGMENTS_FLAG or IPV4_FRAGMENT_OFFSET_MASK

        fun parse(capture: ByteArray): Ipv4UdpDnsPacket? {
            if (capture.size < IPV4_MINIMUM_HEADER_BYTES + UDP_HEADER_BYTES) return null

            val versionAndHeaderLength = capture[0].toInt() and 0xff
            if (versionAndHeaderLength ushr 4 != IPV4_VERSION) return null

            val ipv4HeaderBytes = (versionAndHeaderLength and 0x0f) * 4
            if (ipv4HeaderBytes < IPV4_MINIMUM_HEADER_BYTES) return null
            if (ipv4HeaderBytes + UDP_HEADER_BYTES > capture.size) return null

            val totalLength = readUnsignedShort(capture, IPV4_TOTAL_LENGTH_OFFSET)
            if (totalLength < ipv4HeaderBytes + UDP_HEADER_BYTES) return null
            if (totalLength > capture.size) return null

            val flagsAndFragmentOffset =
                readUnsignedShort(capture, IPV4_FLAGS_AND_FRAGMENT_OFFSET)
            if (flagsAndFragmentOffset and IPV4_UNSUPPORTED_FRAGMENT_BITS != 0) return null
            if ((capture[IPV4_PROTOCOL_OFFSET].toInt() and 0xff) != UDP_PROTOCOL) return null

            val udpOffset = ipv4HeaderBytes
            val destinationPort = readUnsignedShort(capture, udpOffset + 2)
            if (destinationPort != DNS_PORT) return null

            val udpLength = readUnsignedShort(capture, udpOffset + 4)
            if (udpLength < UDP_HEADER_BYTES) return null
            if (ipv4HeaderBytes + udpLength != totalLength) return null

            val payloadLength = udpLength - UDP_HEADER_BYTES
            if (payloadLength !in 1..MAX_DNS_BYTES) return null

            val payloadOffset = udpOffset + UDP_HEADER_BYTES
            val sourceAddress =
                InetAddress.getByAddress(
                    capture.copyOfRange(
                        IPV4_SOURCE_ADDRESS_OFFSET,
                        IPV4_SOURCE_ADDRESS_OFFSET + 4,
                    ),
                ) as Inet4Address
            val destinationAddress =
                InetAddress.getByAddress(
                    capture.copyOfRange(
                        IPV4_DESTINATION_ADDRESS_OFFSET,
                        IPV4_DESTINATION_ADDRESS_OFFSET + 4,
                    ),
                ) as Inet4Address

            return Ipv4UdpDnsPacket(
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = readUnsignedShort(capture, udpOffset),
                destinationPort = destinationPort,
                identification = readUnsignedShort(capture, IPV4_IDENTIFICATION_OFFSET),
                payload = capture.copyOfRange(payloadOffset, payloadOffset + payloadLength),
            )
        }

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
