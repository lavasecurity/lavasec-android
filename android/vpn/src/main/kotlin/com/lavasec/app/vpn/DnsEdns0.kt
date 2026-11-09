package com.lavasec.app.vpn

/** The iOS DNSEDNS0 contract, using Android's shared bounded resource-record walker. */
internal object DnsEdns0 {
    const val TUNNEL_PAYLOAD_BYTES = 1232

    /** Lower only: preserve classic DNS, smaller client limits, DO/options and signed messages. */
    fun capTunnelPayload(query: ByteArray): ByteArray {
        if (query.size !in 12..4096 || query[2].toInt() and 0x80 != 0) return query
        val walk = DnsResponseWireWalker.walk(query) ?: return query
        if (walk.records.any { it.type == 250 || it.type == 24 }) return query
        val opt = walk.records.singleOrNull { it.type == 41 } ?: return query
        val classOffset = opt.ttlOffset - 2
        val advertised = read16(query, classOffset)
        if (advertised <= TUNNEL_PAYLOAD_BYTES) return query
        return query.copyOf().also {
            it[classOffset] = (TUNNEL_PAYLOAD_BYTES ushr 8).toByte()
            it[classOffset + 1] = TUNNEL_PAYLOAD_BYTES.toByte()
        }
    }

    /** Extended EDNS errors such as BADVERS must never look like NOERROR. */
    fun fullResponseCode(response: ByteArray): Int? {
        if (response.size !in 12..4096 || response[2].toInt() and 0x80 == 0) return null
        val walk = DnsResponseWireWalker.walk(response) ?: return null
        val opt = walk.records.singleOrNull { it.type == 41 }
        val extension = opt?.let { response[it.ttlOffset].toInt() and 0xff } ?: 0
        return (extension shl 4) or (response[3].toInt() and 0x0f)
    }

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
