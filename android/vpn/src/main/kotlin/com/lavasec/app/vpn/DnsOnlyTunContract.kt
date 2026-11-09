package com.lavasec.app.vpn

/** One source of truth for the dormant DNS-only TUN wire contract. */
internal object DnsOnlyTunContract {
    const val MTU = 1_280
    const val CLIENT_ADDRESS = "10.111.0.2"
    const val DNS_ADDRESS = "10.111.0.1"
    const val HOST_PREFIX_LENGTH = 32
    const val IPV4_HEADER_BYTES = 20
    const val UDP_HEADER_BYTES = 8
    const val MAX_RESPONSE_DNS_BYTES = MTU - IPV4_HEADER_BYTES - UDP_HEADER_BYTES

    private val dnsAddressBytes = byteArrayOf(10, 111, 0, 1)

    fun isSyntheticDnsAddress(address: ByteArray): Boolean =
        address.contentEquals(dnsAddressBytes)
}
