package com.lavasec.app.vpn.chained

/**
 * Where the owner hands a client DNS query the tunnel intercepted, and where the decrypted inbound
 * packets are offered before ordinary tunnel delivery.
 *
 * Both directions live on ONE seam because they are one protocol: the client-DNS egress sends the
 * resolver query out through the engine and must see the matching reply before it reaches the
 * tunnel. Splitting them would let a caller wire one without the other and silently blackhole DNS.
 *
 * [bind] is called once by `ChainedDataPath` during construction, before any packet can arrive; it
 * hands the egress the engine's outbound path and the TUN write path without exposing them across
 * the module boundary. A `null`/no-op default is not offered: an unwired sink is exactly the
 * hostname blackhole the readiness gate refuses.
 *
 * The production implementation is `ChainedT0ResolverEgress`; the seam exists so the packet-path
 * composition can be driven against a recording double in the host JVM.
 */
internal interface ChainedClientDns {
    /** Binds the egress to the engine's outbound path and the tunnel write path. */
    fun bind(sendQuery: (ByteArray) -> Unit, writeClientResponse: (ChainedDnsResponse) -> Unit)

    /** Serves one intercepted client DNS query. Ownership of [packet] transfers. */
    fun handleQuery(packet: ByteArray)

    /**
     * Offers one decrypted inbound packet.
     *
     * @return true when the egress consumed it (a resolver reply, or a packet to one of its own
     *   ports), false when it is ordinary traffic the TUN must receive.
     */
    fun observeInbound(packet: ByteArray): Boolean

    /** Advances time-bound state (query timeouts). Called on the engine tick. */
    fun expire()

    /** Releases every held port. Called once on composition close. */
    fun close()
}
