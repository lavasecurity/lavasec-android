package com.lavasec.app.foundation.chained

/**
 * Which of a chained configuration's `DNS =` entries the tunnel can actually speak to.
 *
 * Ported from iOS `ChainedTunnelResolverSelection`. SELECTION, deliberately distinct from the
 * validation the configuration boundary does (which only proves each entry is an IP literal), and
 * distinct from the readiness gate (which reports WHY none is usable). This type returns the ordered
 * usable subset the resolver egress consumes, so a consumer cannot accidentally source from
 * `0.0.0.0` or from a resolver the peer's `AllowedIPs` do not route.
 *
 * The rules are the reviewed readiness predicates, reused rather than re-derived:
 * - [ChainedReadiness.isUsableTunnelResolver] — IPv4-only, routable unicast, not the in-tunnel DNS
 *   proxy address, not the configuration's own client address.
 * - [ChainedReadiness.isRoutedByAllowedIPs] — the reply arrives FROM the resolver address and the
 *   data path enforces `AllowedIPs` on that inbound source, so a resolver off the negotiated routes
 *   would latch ready and then fail every lookup.
 *
 * Duplicates collapse to the first occurrence: a failover loop that retries the same resolver twice
 * buys latency, not redundancy.
 */
object ChainedTunnelResolverSelection {
    /** A resolver selection: the ordered usable subset of the configuration's own `DNS =`. */
    data class Selection(val resolvers: List<String>)

    /** The ordered usable subset of the configuration's `DNS =` entries. Empty means unusable. */
    fun selectedResolvers(configuration: ChainedUpstreamConfiguration): List<String> =
        selection(configuration).resolvers

    /** The same set as [Selection]. */
    fun selection(configuration: ChainedUpstreamConfiguration): Selection {
        val seen = LinkedHashSet<String>()
        val selected = ArrayList<String>()
        for (address in configuration.stackDNSAddresses) {
            if (!ChainedReadiness.isUsableTunnelResolver(address, configuration.clientAddress)) {
                continue
            }
            if (!ChainedReadiness.isRoutedByAllowedIPs(address, configuration.capturedAllowedIPs)) continue
            if (seen.add(address)) selected += address
        }
        return Selection(selected)
    }

    /**
     * The four network-order bytes of an address this selection admitted, or `null`.
     *
     * A parse, not a validator: the address has already passed the IPv4-only usability gate.
     */
    fun resolverBytes(address: String): ByteArray? {
        val octets = ChainedUpstreamConfigurationValidator.ipv4Octets(address) ?: return null
        return ByteArray(4) { index -> octets[index].toByte() }
    }
}
