package com.lavasec.app.foundation.chained

/**
 * Raw IPv4/TCP/UDP packet builders for the classifier tests.
 *
 * Hand-built rather than captured so a test names exactly the header shape it exercises —
 * options, fragment flags, and deliberately damaged length fields included.
 */
internal object ChainedPacketFixtures {
    const val IPPROTO_TCP = 6
    const val IPPROTO_UDP = 17

    fun ipv4(
        protocol: Int,
        destination: String,
        transport: ByteArray,
        source: String = "10.7.0.2",
        headerLength: Int = 20,
        identification: Int = 0,
        moreFragments: Boolean = false,
        fragmentOffset: Int = 0,
        totalLength: Int? = null,
    ): ByteArray {
        require(headerLength >= 20 && headerLength % 4 == 0)
        require(fragmentOffset in 0..0x1FFF)
        val total = totalLength ?: (headerLength + transport.size)
        val out = ByteArray(headerLength + transport.size)
        out[0] = (0x40 or (headerLength / 4)).toByte()
        out[2] = ((total shr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        out[4] = ((identification shr 8) and 0xFF).toByte()
        out[5] = (identification and 0xFF).toByte()
        var flags = fragmentOffset and 0x1FFF
        if (moreFragments) flags = flags or 0x2000
        out[6] = ((flags shr 8) and 0xFF).toByte()
        out[7] = (flags and 0xFF).toByte()
        out[8] = 64
        out[9] = protocol.toByte()
        writeAddress(out, 12, source)
        writeAddress(out, 16, destination)
        transport.copyInto(out, headerLength)
        return out
    }

    fun udp(
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray = ByteArray(0),
        lengthOverride: Int? = null,
    ): ByteArray {
        val length = lengthOverride ?: (8 + payload.size)
        val out = ByteArray(8 + payload.size)
        putU16(out, 0, sourcePort)
        putU16(out, 2, destinationPort)
        putU16(out, 4, length)
        payload.copyInto(out, 8)
        return out
    }

    fun tcp(
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArray(20 + payload.size)
        putU16(out, 0, sourcePort)
        putU16(out, 2, destinationPort)
        putU16(out, 12, 0x5000)
        putU16(out, 14, 0x0010)
        payload.copyInto(out, 20)
        return out
    }

    private fun putU16(out: ByteArray, index: Int, value: Int) {
        out[index] = ((value shr 8) and 0xFF).toByte()
        out[index + 1] = (value and 0xFF).toByte()
    }

    private fun writeAddress(out: ByteArray, index: Int, address: String) {
        val octets = requireNotNull(
            ChainedUpstreamConfigurationValidator.ipv4Octets(address),
        ) { "not an IPv4 address: $address" }
        for (offset in octets.indices) {
            out[index + offset] = octets[offset].toByte()
        }
    }
}
