package com.lavasec.app.foundation.dns

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Pure bootstrap / underlying-network selection policy ported from the canonical iOS
 * `Sources/LavaSecKit/DeviceDNSFallbackPolicy.swift`,
 * `Sources/LavaSecKit/ResolverAddressIdentity.swift` and
 * `Sources/LavaSecDNS/ResolverBootstrapService.swift`
 * (pinned `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`).
 *
 * The actual hostname lookup is a transport concern owned by another slice. What is modeled here
 * is the *policy*: which captured addresses can serve as resolvers, how they are ordered for the
 * current network, and the generation fence that stops a lookup admitted on a previous network
 * from repopulating the freshly-cleared bootstrap cache.
 */

/** Addresses a bootstrap lookup resolved, partitioned by the family the lookup classified them into. */
data class ResolverResolvedAddresses(
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = ipv4.isEmpty() && ipv6.isEmpty()

    companion object {
        val EMPTY: ResolverResolvedAddresses = ResolverResolvedAddresses()
    }
}

/**
 * One bootstrap lookup admitted under a specific cache generation and session token.
 *
 * The [admittedAtEpoch] is carried verbatim so a lookup that runs after its kicking session ended
 * can refuse rather than egress into whatever session is live by then.
 */
data class ResolverBootstrapKick(
    val hostname: String,
    val generation: Long,
    val admittedAtEpoch: Long,
)

/** Outcome of a pre-warm request: the next state plus the lookup to run, or `null` when suppressed. */
data class ResolverBootstrapPrewarm(
    val state: ResolverBootstrapState,
    val kick: ResolverBootstrapKick?,
)

/** Thread-safe-cache-equivalent immutable state: generation, resolved addresses, in-flight kicks. */
data class ResolverBootstrapState(
    val generation: Long = 0L,
    val cached: Map<String, ResolverResolvedAddresses> = emptyMap(),
    val inFlight: Map<String, ResolverBootstrapKick> = emptyMap(),
)

/** Address-family utility and literal validation shared by the bootstrap and egress policies. */
object ResolverAddressFamilies {
    private val IPV4_LITERAL = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
    private val IPV6_CHARS = Regex("[0-9a-fA-F:.]+")

    /**
     * Whether a captured system-DNS address can serve as an upstream resolver, by structure alone.
     *
     * Rejects the ranges that can never answer a real query and only ever appear as transient or
     * half-configured captures: unspecified, loopback, and IPv6 link-local (`fe80::/10`), plus the
     * IPv4 equivalents `0.0.0.0/8`, `127.0.0.0/8` and `169.254.0.0/16`. The IPv6 well-known NAT64
     * prefix `64:ff9b::/96` is judged by its embedded IPv4 rather than dropped wholesale, because
     * a NAT64-mapped public resolver is routable through the CLAT while a NAT64-mapped
     * loopback/link-local/unspecified still cannot answer. An unparseable literal is unusable.
     */
    fun isUsableResolverAddress(address: String): Boolean {
        val parsed = parseLiteral(address) ?: return false
        return when (parsed) {
            is Inet4Address -> isUsableIpv4(parsed.address)
            is Inet6Address -> isUsableIpv6(parsed.address)
            else -> false
        }
    }

    /**
     * Groups resolver literals by family, preferring IPv6 on cellular and preserving relative
     * order within each group. Unrecognized strings stay in the caller's order at the tail.
     */
    fun orderedResolverAddresses(
        addresses: List<String>,
        networkKind: ResolverNetworkKind,
    ): List<String> {
        val ipv4 = mutableListOf<String>()
        val ipv6 = mutableListOf<String>()
        val other = mutableListOf<String>()
        for (address in addresses) {
            when (parseLiteral(address)) {
                is Inet4Address -> ipv4 += address
                is Inet6Address -> ipv6 += address
                else -> other += address
            }
        }
        return if (networkKind == ResolverNetworkKind.CELLULAR) {
            ipv6 + ipv4 + other
        } else {
            ipv4 + ipv6 + other
        }
    }

    /**
     * Parses only IP literals. A non-literal is refused before any resolution API is consulted,
     * so this helper never performs a name lookup (matching `inet_pton` semantics).
     */
    private fun parseLiteral(address: String): InetAddress? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return null
        val isIpv6 = trimmed.contains(':')
        if (isIpv6) {
            if (!IPV6_CHARS.matches(trimmed)) return null
        } else if (!IPV4_LITERAL.matches(trimmed)) {
            return null
        }
        return try {
            InetAddress.getByName(trimmed)
        } catch (_: UnknownHostException) {
            null
        }
    }

    private fun isUsableIpv4(octets: ByteArray): Boolean = when (octets[0].toInt() and 0xff) {
        0 -> false // 0.0.0.0/8 unspecified
        127 -> false // 127.0.0.0/8 loopback
        169 -> (octets[1].toInt() and 0xff) != 254 // 169.254.0.0/16 link-local
        else -> true
    }

    private fun isUsableIpv6(bytes: ByteArray): Boolean {
        if (bytes.all { it == 0.toByte() }) return false // :: unspecified
        if (bytes.copyOfRange(0, 15).all { it == 0.toByte() } && bytes[15] == 1.toByte()) {
            return false // ::1 loopback
        }
        if ((bytes[0].toInt() and 0xff) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80) {
            return false // fe80::/10 link-local
        }
        val isNat64 = bytes[0] == 0.toByte() &&
            bytes[1] == 0x64.toByte() &&
            bytes[2] == 0xff.toByte() &&
            bytes[3] == 0x9b.toByte() &&
            bytes.copyOfRange(4, 12).all { it == 0.toByte() }
        if (isNat64) {
            // Judge the prefix by its embedded IPv4 (the low 32 bits).
            return isUsableIpv4(bytes.copyOfRange(12, 16))
        }
        return true
    }
}

/**
 * Pure generation-aware bootstrap cache.
 *
 * Mirrors `ResolverBootstrapService`: a pre-warm is suppressed only while a lookup for the current
 * generation AND a session at least as new is already running; a lookup only clears its marker and
 * caches when it still owns the marker and its generation is unchanged. [invalidateAll] drops every
 * cached address and bumps the generation, so a lookup kicked on a previous network can never
 * repopulate the cache with stale addresses (and a still-in-flight one is superseded).
 */
object ResolverBootstrapPolicy {
    fun cachedAddresses(state: ResolverBootstrapState, hostname: String): ResolverResolvedAddresses? =
        state.cached[hostname]

    /**
     * Asks to resolve [hostname] unless it is cached or a same-generation lookup at least as new is
     * already in flight. Lifecycle generations are monotonic, so `>` orders sessions; an equal
     * token is the ordinary duplicate. A different cache generation means [invalidateAll] ran and
     * any kick may proceed.
     */
    fun prewarm(
        state: ResolverBootstrapState,
        hostname: String,
        admittedAtEpoch: Long,
    ): ResolverBootstrapPrewarm {
        if (state.cached.containsKey(hostname)) {
            return ResolverBootstrapPrewarm(state, null)
        }
        val existing = state.inFlight[hostname]
        if (existing != null &&
            existing.generation == state.generation &&
            admittedAtEpoch <= existing.admittedAtEpoch
        ) {
            return ResolverBootstrapPrewarm(state, null)
        }
        val kick = ResolverBootstrapKick(
            hostname = hostname,
            generation = state.generation,
            admittedAtEpoch = admittedAtEpoch,
        )
        return ResolverBootstrapPrewarm(
            state = state.copy(inFlight = state.inFlight + (hostname to kick)),
            kick = kick,
        )
    }

    /**
     * Completes a lookup. Only the lookup that still owns the in-flight marker clears it and may
     * cache, and only when its generation is still current and the result is non-empty (failed
     * lookups are never cached, so the next pre-warm retries).
     */
    fun completeLookup(
        state: ResolverBootstrapState,
        kick: ResolverBootstrapKick,
        addresses: ResolverResolvedAddresses,
    ): ResolverBootstrapState {
        if (state.inFlight[kick.hostname] != kick) {
            // Superseded by a newer invalidateAll()/re-prewarm: leave the newer marker intact and
            // drop this previous-network/previous-session result.
            return state
        }
        val cleared = state.copy(inFlight = state.inFlight - kick.hostname)
        return if (kick.generation == state.generation && !addresses.isEmpty) {
            cleared.copy(cached = cleared.cached + (kick.hostname to addresses))
        } else {
            cleared
        }
    }

    /** Drops every cached address and advances the generation (network change / resolver switch). */
    fun invalidateAll(state: ResolverBootstrapState): ResolverBootstrapState = state.copy(
        generation = state.generation + 1,
        cached = emptyMap(),
    )

    /** A meaningful underlying-network change invalidates every network-dependent bootstrap address. */
    fun onUnderlyingNetworkChanged(state: ResolverBootstrapState): ResolverBootstrapState =
        invalidateAll(state)
}
