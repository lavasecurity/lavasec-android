package com.lavasec.app.foundation.chained

/**
 * The resolver transports a chained upstream could name. Mirrors the iOS `DNSResolverTransport`
 * cases the egress policy maps.
 */
enum class ChainedResolverTransport {
    /** Plain UDP DNS; the only transport a userspace WireGuard data path can carry. */
    PLAIN_DNS,

    /** DNS over HTTPS. Needs a TLS stack on our side of the tunnel. */
    DNS_OVER_HTTPS,

    /** DNS over TLS. Needs a TLS stream on our side of the tunnel. */
    DNS_OVER_TLS,

    /** DNS over QUIC. Needs a QUIC stack on our side of the tunnel. */
    DNS_OVER_QUIC,

    /**
     * The device's captured resolvers. Plain UDP, so while chained it is redirected THROUGH the
     * tunnel to the upstream's own resolver exactly as [PLAIN_DNS] is — never to the captured local
     * resolver, which would egress around the tunnel.
     */
    DEVICE_DNS,
}

/**
 * Where a DNS query is allowed to leave from while chained mode is latched.
 *
 * The type carries no case meaning "the physical interface". That is the point: a user who routed
 * their traffic through an upstream so their network cannot see it would otherwise have their DNS
 * keep going out around the tunnel, to the same observer, naming every site they visit — and
 * nothing visibly breaks. Ported from iOS `ChainedResolverEgress`.
 */
sealed interface ChainedResolverEgress {
    /**
     * Plain UDP DNS to the upstream's own resolver, framed and encapsulated through the WireGuard
     * session. The only egress chained mode has.
     */
    data object ThroughTunnelToUpstreamResolver : ChainedResolverEgress

    /**
     * This transport cannot be used while chained. Carries the answer the tunnel gives instead —
     * refusing the I/O is only half of the fail-closed rule, which requires a RESPONSE.
     */
    data class UnavailableWhileChained(
        val reason: Unavailable,
        val answer: FailClosedAnswer,
    ) : ChainedResolverEgress

    /** Why a transport cannot be used while chained. */
    enum class Unavailable {
        /** DoH/DoT/DoQ need a TCP or QUIC stack inside the tunnel, which does not exist. */
        NEEDS_A_TRANSPORT_THE_TUNNEL_CANNOT_CARRY,
    }

    /** What the tunnel answers when a transport is unavailable: never a silent timeout. */
    enum class FailClosedAnswer {
        /** SERVFAIL, so the app fails fast and visibly rather than hanging. */
        SERVFAIL,
    }
}

/** Which DNS egress chained mode permits, and the fail-closed answer when it permits none. */
object ChainedResolverEgressPolicy {
    /**
     * The egress for a configured resolver transport while chained mode is latched.
     *
     * Plain UDP DNS (including a device-DNS selection, which is also plain UDP) maps to the tunnel.
     * The encrypted presets are unavailable rather than tunnelled: they are TCP or QUIC over a
     * URL/connection API, and pushing those through a userspace WireGuard session needs a TCP stack
     * this feature is not going to grow.
     */
    fun egressFor(transport: ChainedResolverTransport): ChainedResolverEgress =
        when (transport) {
            ChainedResolverTransport.PLAIN_DNS,
            ChainedResolverTransport.DEVICE_DNS,
            -> ChainedResolverEgress.ThroughTunnelToUpstreamResolver

            ChainedResolverTransport.DNS_OVER_HTTPS,
            ChainedResolverTransport.DNS_OVER_TLS,
            ChainedResolverTransport.DNS_OVER_QUIC,
            -> ChainedResolverEgress.UnavailableWhileChained(
                ChainedResolverEgress.Unavailable.NEEDS_A_TRANSPORT_THE_TUNNEL_CANNOT_CARRY,
                ChainedResolverEgress.FailClosedAnswer.SERVFAIL,
            )
        }

    /**
     * A truncated answer while chained: plain DNS IS carried, the query DID go through the tunnel,
     * and only this one response cannot be completed, so it fails closed with SERVFAIL rather than
     * retrying on a TCP path the tunnel does not serve.
     */
    fun truncatedAnswerEgress(): ChainedResolverEgress =
        ChainedResolverEgress.UnavailableWhileChained(
            ChainedResolverEgress.Unavailable.NEEDS_A_TRANSPORT_THE_TUNNEL_CANNOT_CARRY,
            ChainedResolverEgress.FailClosedAnswer.SERVFAIL,
        )

    /**
     * Whether a truncated answer is evidence the resolver is WORKING. It is.
     *
     * A TC-bit response is a resolver replying, promptly and correctly, that the answer is too
     * large for UDP. Counting it as a DNS failure would feed the shared outage supervisor, so one
     * large-response domain a browser retries a few times would surrender chaining for every site.
     */
    const val TRUNCATION_IS_RESOLVER_LIVENESS: Boolean = true

    /** Whether a query may be sent at all for [egress]. */
    fun permitsQuery(egress: ChainedResolverEgress): Boolean =
        egress is ChainedResolverEgress.ThroughTunnelToUpstreamResolver

    /** Stable identifier for device logs. Never user copy. */
    fun logValue(egress: ChainedResolverEgress): String = when (egress) {
        ChainedResolverEgress.ThroughTunnelToUpstreamResolver -> "resolver-through-tunnel"
        is ChainedResolverEgress.UnavailableWhileChained ->
            "resolver-unavailable-${egress.reason.name.lowercase()}-${egress.answer.name.lowercase()}"
    }
}
