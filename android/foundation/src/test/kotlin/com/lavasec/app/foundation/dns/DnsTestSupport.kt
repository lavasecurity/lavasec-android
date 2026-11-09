package com.lavasec.app.foundation.dns

internal fun dnsQuery(
    domain: String,
    rawType: Int = 1,
    rawClass: Int = 1,
    transactionId: Int = 0x1234,
    recursionDesired: Boolean = true,
    opcode: Int = 0,
    answerCount: Int = 0,
    authorityCount: Int = 0,
    additionalRecords: List<ByteArray> = emptyList(),
    declaredAdditionalCount: Int = additionalRecords.size,
    trailingBytes: ByteArray = byteArrayOf(),
): ByteArray = dnsQuery(
    labels = domain.split('.'),
    rawType = rawType,
    rawClass = rawClass,
    transactionId = transactionId,
    recursionDesired = recursionDesired,
    opcode = opcode,
    answerCount = answerCount,
    authorityCount = authorityCount,
    additionalRecords = additionalRecords,
    declaredAdditionalCount = declaredAdditionalCount,
    trailingBytes = trailingBytes,
)

internal fun dnsQueryFromLabels(labels: List<String>): ByteArray = dnsQuery(
    labels = labels,
    rawType = 1,
    rawClass = 1,
    transactionId = 0x1234,
    recursionDesired = true,
    opcode = 0,
    answerCount = 0,
    authorityCount = 0,
    additionalRecords = emptyList(),
    declaredAdditionalCount = 0,
    trailingBytes = byteArrayOf(),
)

private fun dnsQuery(
    labels: List<String>,
    rawType: Int,
    rawClass: Int,
    transactionId: Int,
    recursionDesired: Boolean,
    opcode: Int,
    answerCount: Int,
    authorityCount: Int,
    additionalRecords: List<ByteArray>,
    declaredAdditionalCount: Int,
    trailingBytes: ByteArray,
): ByteArray {
    require(opcode in 0..15)
    val bytes = mutableListOf<Byte>()
    bytes.appendUInt16(transactionId)
    bytes.appendUInt16((opcode shl 11) or if (recursionDesired) 0x0100 else 0)
    bytes.appendUInt16(1)
    bytes.appendUInt16(answerCount)
    bytes.appendUInt16(authorityCount)
    bytes.appendUInt16(declaredAdditionalCount)
    for (label in labels) {
        val labelBytes = label.toByteArray(Charsets.UTF_8)
        bytes += labelBytes.size.toByte()
        bytes += labelBytes.toList()
    }
    bytes += 0
    bytes.appendUInt16(rawType)
    bytes.appendUInt16(rawClass)
    additionalRecords.forEach { bytes += it.toList() }
    bytes += trailingBytes.toList()
    return bytes.toByteArray()
}

internal fun dnsOptRecord(
    owner: ByteArray = byteArrayOf(0),
    type: Int = 41,
    udpPayloadSize: Int = 1_232,
    ttl: Long = 0,
    rdata: ByteArray = byteArrayOf(),
    declaredRdataLength: Int = rdata.size,
): ByteArray {
    require(ttl in 0..0xffff_ffffL)
    val bytes = mutableListOf<Byte>()
    bytes += owner.toList()
    bytes.appendUInt16(type)
    bytes.appendUInt16(udpPayloadSize)
    bytes += ((ttl ushr 24) and 0xff).toByte()
    bytes += ((ttl ushr 16) and 0xff).toByte()
    bytes += ((ttl ushr 8) and 0xff).toByte()
    bytes += (ttl and 0xff).toByte()
    bytes.appendUInt16(declaredRdataLength)
    bytes += rdata.toList()
    return bytes.toByteArray()
}

internal fun ByteArray.readUInt16(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

internal fun ByteArray.readUInt32(offset: Int): Long =
    ((this[offset].toLong() and 0xff) shl 24) or
        ((this[offset + 1].toLong() and 0xff) shl 16) or
        ((this[offset + 2].toLong() and 0xff) shl 8) or
        (this[offset + 3].toLong() and 0xff)

internal fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

private fun MutableList<Byte>.appendUInt16(value: Int) {
    add(((value ushr 8) and 0xff).toByte())
    add((value and 0xff).toByte())
}
