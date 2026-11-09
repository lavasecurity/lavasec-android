package com.lavasec.app.vpn

internal object DnsResponseTtlCap {
    fun capCacheableTtls(message: ByteArray, maximum: Long): ByteArray? {
        if (maximum !in 0..MAX_UNSIGNED_32) return null
        val walk = DnsResponseWireWalker.walk(message) ?: return null
        val capped = message.copyOf()
        walk.records.forEach { record ->
            if (record.type == OPT_RECORD_TYPE) return@forEach
            val ttl = read32(message, record.ttlOffset)
            if (ttl > maximum) write32(capped, record.ttlOffset, maximum)
        }
        return capped
    }

    private fun read32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun write32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private const val OPT_RECORD_TYPE = 41
    private const val MAX_UNSIGNED_32 = 0xffff_ffffL
}
