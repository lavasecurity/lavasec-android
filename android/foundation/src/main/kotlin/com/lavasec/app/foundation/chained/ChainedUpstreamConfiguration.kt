package com.lavasec.app.foundation.chained

import com.lavasec.app.foundation.domain.IdnaHostCanonicalizer
import java.util.Base64
import java.util.Collections

/**
 * How a chained upstream routes traffic. DERIVED from the validated IPv4 `AllowedIPs` shape,
 * never parsed from a directive and never chosen by a caller:
 *
 * - [FULL_TUNNEL] when the IPv4 prefixes cover the default route `0.0.0.0/0` (a literal
 *   `0.0.0.0/0` or the `0.0.0.0/1, 128.0.0.0/1` two-halves idiom) — the route plan claims
 *   `0.0.0.0/0` and `::/0`.
 * - [SPLIT_TUNNEL] when they are a non-empty, non-covering set of IPv4 prefixes — the route
 *   plan claims exactly those prefixes plus the DNS-capture route; everything else egresses
 *   direct on the physical interface by design.
 *
 * A "split" set that covers the default route IS a full tunnel and derives [FULL_TUNNEL]. A
 * set with no usable IPv4 prefix (empty, or IPv6-only) is refused at construction rather than
 * assigned a policy. Mirrors iOS `ChainedRoutingPolicy`.
 */
enum class ChainedRoutingPolicy { FULL_TUNNEL, SPLIT_TUNNEL }

/**
 * Why a supplied upstream is not usable. Log/health identifiers, never user copy. Mirrors the
 * iOS `ChainedUpstreamConfiguration.ValidationFailure` vocabulary so both clients name the
 * same condition in a device log.
 */
enum class ChainedConfigurationFailure {
    /** The host was empty, over-long, or carried a scheme, path, or whitespace. */
    MALFORMED_ENDPOINT_HOST,

    /** Port 0 is not a destination. */
    INVALID_ENDPOINT_PORT,

    /** The key was not base64, or did not decode to exactly 32 bytes. */
    MALFORMED_PEER_PUBLIC_KEY,

    /** The key decoded to the right length but cannot authenticate a peer (a small-order point). */
    UNUSABLE_PEER_PUBLIC_KEY,

    /** A keepalive short enough to hold the radio awake. */
    KEEPALIVE_TOO_FREQUENT,

    /** A keepalive outside the 16-bit WireGuard range (`0..65535`). */
    KEEPALIVE_OUT_OF_RANGE,

    /** The client address was absent, not an IPv4 literal, or not a usable host address. */
    MALFORMED_CLIENT_ADDRESS,

    /** The client address is the tunnel's own DNS proxy address (`10.111.0.1`). */
    CLIENT_ADDRESS_COLLIDES_WITH_TUNNEL_DNS,

    /** An AllowedIPs entry was not a prefix this parser recognises. */
    MALFORMED_ALLOWED_IPS,

    /** The `AllowedIPs` carried no prefix at all — a peer that routes nothing. */
    ALLOWED_IPS_EMPTY,

    /** The `AllowedIPs` carry IPv6 prefixes but no IPv4 prefix the inner data path can carry. */
    IPV6_ONLY_UPSTREAM_UNSUPPORTED,

    /** A split-tunnel `AllowedIPs` set that also carries an IPv6 prefix. */
    SPLIT_TUNNEL_CARRIES_IPV6_ALLOWED_IPS,

    /** A configured `[Interface] MTU` below 1280. */
    MTU_BELOW_IPV6_FLOOR,

    /** A configured `[Interface] MTU` outside the 16-bit range Android's TUN accepts. */
    MTU_OUT_OF_RANGE,

    /**
     * A configured `[Interface] MTU` above the engine's inner-IP packet ceiling (1500).
     *
     * The engine ABI caps an `encapsulate` source at 1500 bytes; an MTU above it would make
     * every ordinary MTU-sized packet fail as `PacketTooLarge` and be shed. Distinct from
     * [MTU_OUT_OF_RANGE], which is Android's 16-bit TUN limit.
     */
    MTU_ABOVE_ENGINE_IP_LIMIT,

    /** A `DNS =` entry that is not an IP literal (hostnames are deliberately refused). */
    MALFORMED_DNS_ADDRESS,
}

/** Thrown by [ChainedUpstreamConfiguration.create]; carries the typed [failure]. */
class ChainedConfigurationException(
    val failure: ChainedConfigurationFailure,
) : IllegalArgumentException(failure.name)

/**
 * The user's WireGuard peer, validated — the non-secret half of a chained upstream.
 *
 * Where to send encapsulated traffic and which peer key authenticates it. The private key and
 * any preshared key are credential-protected and referenced, never carried here: a value type
 * gets copied, encoded, diffed, and logged, and every one of those is a way for key material
 * to escape. Mirrors iOS `ChainedUpstreamConfiguration` (`Sources/LavaSecKit`).
 *
 * Construction validates and cannot produce an invalid instance ([create]; the private
 * constructor re-runs the same validation so a generated `copy` cannot bypass it). The
 * validation is the boundary the WireGuard engine relies on: BoringTun's C ABI panics on some
 * malformed arguments, and inside a `VpnService` that is a tunnel abort rather than an
 * exception, so bad input must stop here rather than at the engine.
 */
@ConsistentCopyVisibility
data class ChainedUpstreamConfiguration private constructor(
    /** Hostname or IP literal of the peer. Resolved at session start, not here. */
    val endpointHost: String,
    /** UDP port of the peer. */
    val endpointPort: Int,
    /** The peer's public key, base64 as WireGuard writes it. Public by design. */
    val peerPublicKey: String,
    /** The client's own address inside the tunnel, from the config's `[Interface] Address`. */
    val clientAddress: String,
    /** The prefixes the peer routes to this client, from the config's `[Peer] AllowedIPs`. */
    val allowedIPs: List<String>,
    /** Seconds between keepalives, or `0` for off. */
    val persistentKeepaliveSeconds: Int,
    /** `[Interface] MTU`, or `null` when the file carries none (the route plan then uses 1280). */
    val interfaceMTU: Int?,
    /** The resolvers from the config's `[Interface] DNS`, stored as written. */
    val dnsAddresses: List<String>,
    /** Display identity and participation; neither field contains credential material. */
    val displayName: String = "",
    val isEnabled: Boolean = true,
    /** Saved order is entry then this exit. Every row remains an independent profile. */
    val precedingHops: List<ChainedUpstreamConfiguration> = emptyList(),
) {
    /**
     * Full tunnel or split tunnel, derived purely from [allowedIPs] — never stored, so a
     * persisted value cannot disagree with the routes (the iOS type stores it as a decode-time
     * diagnostic; here it is a pure function, which removes the possibility entirely).
     */
    val routingPolicy: ChainedRoutingPolicy

    init {
        routingPolicy = ChainedUpstreamConfigurationValidator.validateAndDerive(
            endpointHost = endpointHost,
            endpointPort = endpointPort,
            peerPublicKey = peerPublicKey,
            clientAddress = clientAddress,
            allowedIPs = allowedIPs,
            persistentKeepaliveSeconds = persistentKeepaliveSeconds,
            interfaceMTU = interfaceMTU,
            dnsAddresses = dnsAddresses,
        )
        ChainedStackValidation.validate(this)
    }

    val orderedHops: List<ChainedUpstreamConfiguration> get() = precedingHops + this
    val activeConfiguration: ChainedUpstreamConfiguration?
        get() {
            val selected = orderedHops.filter { it.isEnabled }.map { it.withoutEntryHop() }
            return selected.lastOrNull()?.let { exit ->
                if (selected.size == 2) exit.withEntryHop(selected.first()) else exit
            }
        }
    val containsFullTunnel: Boolean get() = orderedHops.any { it.isEnabled && it.routingPolicy == ChainedRoutingPolicy.FULL_TUNNEL }
    val effectiveRoutingPolicy: ChainedRoutingPolicy
        get() = if (containsFullTunnel) ChainedRoutingPolicy.FULL_TUNNEL else ChainedRoutingPolicy.SPLIT_TUNNEL
    val usesNestedTransport: Boolean get() = precedingHops.firstOrNull()?.routingPolicy == ChainedRoutingPolicy.FULL_TUNNEL
    /** Physical endpoint used by command preflight; a nested exit has no physical socket. */
    val transportConfiguration: ChainedUpstreamConfiguration get() = if (usesNestedTransport) precedingHops.first() else this
    val capturedAllowedIPs: List<String> get() = orderedHops.flatMap { it.allowedIPs }.distinct()
    val stackDNSProfileIndex: Int get() = if (precedingHops.isNotEmpty() && routingPolicy == ChainedRoutingPolicy.FULL_TUNNEL) 1 else 0
    val stackDNSAddresses: List<String> get() = orderedHops[stackDNSProfileIndex].dnsAddresses
    val effectiveInterfaceMTU: Int
        get() {
            val requested = interfaceMTU ?: 1280
            val entry = precedingHops.firstOrNull() ?: return requested
            return if (entry.routingPolicy == ChainedRoutingPolicy.SPLIT_TUNNEL) minOf(requested, entry.interfaceMTU ?: 1280)
            else minOf(requested, ((entry.interfaceMTU ?: 1420) - 60) / 16 * 16)
        }

    fun routeIndexForIPv4(address: Long): Int? {
        fun contains(row: ChainedUpstreamConfiguration) = row.allowedIPs.any { prefix ->
            ChainedUpstreamConfigurationValidator.ipv4Block(prefix)?.let { address in it.first..it.second } == true
        }
        val entry = precedingHops.firstOrNull() ?: return if (contains(this)) 0 else null
        return if (entry.routingPolicy == ChainedRoutingPolicy.FULL_TUNNEL) {
            if (contains(this)) 1 else 0
        } else if (contains(entry)) 0 else if (contains(this)) 1 else null
    }

    fun withEntryHop(entry: ChainedUpstreamConfiguration): ChainedUpstreamConfiguration = copy(precedingHops = listOf(entry))
    fun withoutEntryHop(): ChainedUpstreamConfiguration = copy(precedingHops = emptyList())
    fun named(name: String): ChainedUpstreamConfiguration = copy(displayName = name.trim().take(80))
    fun enabled(value: Boolean): ChainedUpstreamConfiguration = copy(isEnabled = value)

    /**
     * The endpoint host in its validated ASCII (A-label) wire form.
     *
     * [endpointHost] is stored as the user wrote it (so a device log can be compared against the
     * pasted file), but the resolution boundary MUST resolve this form, not the raw spelling: the
     * module's pinned UTS46 canonicalizer is what this type validated against, and Android's
     * standard name resolution does not IDN-normalize a bare Unicode host (nor agree with UTS46
     * for transitional cases such as `faß.de`). ASCII hosts are returned unchanged.
     */
    val endpointHostWireForm: String
        get() = ChainedUpstreamConfigurationValidator.endpointHostWireFormOrNull(endpointHost)
            ?: endpointHost

    /** The only representation that may reach a log: endpoint host and key are withheld. */
    val redactedSummary: String
        get() = "ChainedUpstreamConfiguration(endpoint: <redacted>:$endpointPort, " +
            "keepalive: ${persistentKeepaliveSeconds}s, dns: ${dnsAddresses.size})"

    // Interpolating a value is the easiest way to leak it, and it happens by accident — a
    // "\(configuration)" in a log line reads as harmless. Routing both conversions through the
    // redacted form makes the careless path the safe one (iOS pins the same behaviour).
    override fun toString(): String = redactedSummary

    companion object {
        /** The WireGuard key width: X25519 public/private keys and the preshared key are all 32. */
        const val KEY_BYTE_COUNT = 32

        /** The WireGuard public-key length. The engine reads 32 bytes unconditionally. */
        const val PUBLIC_KEY_BYTE_COUNT = KEY_BYTE_COUNT

        /** A keepalive below this holds the radio awake, so it is refused rather than clamped. */
        const val MINIMUM_KEEPALIVE_SECONDS = 5

        /**
         * The tunnel's DNS proxy address; a client address equal to it would kill DNS.
         *
         * Must match the shipped Android DNS-only contract (`:vpn` `DnsOnlyTunContract.DNS_ADDRESS`,
         * `10.111.0.1`) — the chained engine answers DNS on the same proxy address the DNS-only
         * tunnel already installs, so a client address equal to it makes the OS treat queries as
         * local delivery and interception never sees them. (iOS uses `10.255.0.1`; Android's
         * shipped tunnel already uses the `10.111.0.x` block.)
         */
        const val DNS_SERVER_ADDRESS = "10.111.0.1"

        /** The smallest legal IPv6 link MTU, refused in both modes (see the validator). */
        const val MINIMUM_IPV6_LINK_MTU = 1280

        /**
         * Normalizes and validates a user-supplied upstream.
         *
         * @throws ChainedConfigurationException with the typed failure.
         */
        fun create(
            endpointHost: String,
            endpointPort: Int,
            peerPublicKey: String,
            clientAddress: String,
            allowedIPs: List<String>,
            persistentKeepaliveSeconds: Int = 0,
            interfaceMTU: Int? = null,
            dnsAddresses: List<String> = emptyList(),
            displayName: String = "",
            isEnabled: Boolean = true,
            precedingHops: List<ChainedUpstreamConfiguration> = emptyList(),
        ): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration(
            endpointHost = endpointHost.trim(),
            endpointPort = endpointPort,
            peerPublicKey = peerPublicKey,
            clientAddress = clientAddress.trim(),
            allowedIPs = Collections.unmodifiableList(
                allowedIPs.map { it.trim() }.filter { it.isNotEmpty() },
            ),
            persistentKeepaliveSeconds = persistentKeepaliveSeconds,
            interfaceMTU = interfaceMTU,
            dnsAddresses = Collections.unmodifiableList(
                dnsAddresses.map { it.trim() }.filter { it.isNotEmpty() },
            ),
            displayName = displayName.trim().take(80),
            isEnabled = isEnabled,
            precedingHops = Collections.unmodifiableList(precedingHops.toList()),
        )
    }
}

/**
 * Pure validation for [ChainedUpstreamConfiguration]. Extracted so the constructor's guarantee
 * (a value of the type is already valid) is one function, and so the prefix/route helpers are
 * directly testable.
 */
object ChainedUpstreamConfigurationValidator {

    /**
     * Validates every field and returns the derived [ChainedRoutingPolicy].
     *
     * @throws ChainedConfigurationException with the typed failure.
     */
    fun validateAndDerive(
        endpointHost: String,
        endpointPort: Int,
        peerPublicKey: String,
        clientAddress: String,
        allowedIPs: List<String>,
        persistentKeepaliveSeconds: Int,
        interfaceMTU: Int?,
        dnsAddresses: List<String>,
    ): ChainedRoutingPolicy {
        if (!isPlausibleEndpointHost(endpointHost)) {
            throw ChainedConfigurationException(ChainedConfigurationFailure.MALFORMED_ENDPOINT_HOST)
        }
        if (endpointPort == 0 || endpointPort !in 0..65535) {
            throw ChainedConfigurationException(ChainedConfigurationFailure.INVALID_ENDPOINT_PORT)
        }
        val peerKeyBytes = decodedKey(peerPublicKey)
            ?: throw ChainedConfigurationException(ChainedConfigurationFailure.MALFORMED_PEER_PUBLIC_KEY)
        if (isSmallOrderPoint(peerKeyBytes)) {
            throw ChainedConfigurationException(ChainedConfigurationFailure.UNUSABLE_PEER_PUBLIC_KEY)
        }
        if (persistentKeepaliveSeconds < 0 || persistentKeepaliveSeconds > 65535) {
            throw ChainedConfigurationException(ChainedConfigurationFailure.KEEPALIVE_OUT_OF_RANGE)
        }
        if (persistentKeepaliveSeconds != 0 &&
            persistentKeepaliveSeconds < ChainedUpstreamConfiguration.MINIMUM_KEEPALIVE_SECONDS
        ) {
            throw ChainedConfigurationException(ChainedConfigurationFailure.KEEPALIVE_TOO_FREQUENT)
        }
        if (!isUsableClientAddress(clientAddress)) {
            throw ChainedConfigurationException(ChainedConfigurationFailure.MALFORMED_CLIENT_ADDRESS)
        }
        if (clientAddress == ChainedUpstreamConfiguration.DNS_SERVER_ADDRESS) {
            throw ChainedConfigurationException(
                ChainedConfigurationFailure.CLIENT_ADDRESS_COLLIDES_WITH_TUNNEL_DNS,
            )
        }
        if (!allowedIPs.all(::isPlausiblePrefix)) {
            throw ChainedConfigurationException(ChainedConfigurationFailure.MALFORMED_ALLOWED_IPS)
        }

        // Derive the routing mode from the prefix set. Order is deliberate (coverage first,
        // then the reasons a non-covering set is unroutable) and each branch is exclusive, so
        // the derived policy is a pure function of `allowedIPs`.
        val ipv4Prefixes = allowedIPs.filter { ipv4Block(it) != null }
        val policy: ChainedRoutingPolicy = when {
            coversIPv4DefaultRoute(allowedIPs) -> ChainedRoutingPolicy.FULL_TUNNEL
            allowedIPs.isEmpty() ->
                throw ChainedConfigurationException(ChainedConfigurationFailure.ALLOWED_IPS_EMPTY)
            ipv4Prefixes.isEmpty() -> throw ChainedConfigurationException(
                ChainedConfigurationFailure.IPV6_ONLY_UPSTREAM_UNSUPPORTED,
            )
            ipv4Prefixes.size != allowedIPs.size -> throw ChainedConfigurationException(
                ChainedConfigurationFailure.SPLIT_TUNNEL_CARRIES_IPV6_ALLOWED_IPS,
            )
            else -> ChainedRoutingPolicy.SPLIT_TUNNEL
        }

        // The 1280 floor binds both modes: full tunnel claims `::/0` (RFC 8200 §5), and the
        // engine's packet-queue limits reject any sub-1280 MTU regardless of family, which
        // would downgrade a split profile to DNS-only. Refuse rather than clamp. The outermost
        // upper bound is Android's 16-bit TUN MTU: a larger value cannot be applied at session
        // setup at all.
        if (interfaceMTU != null && (interfaceMTU < 0 || interfaceMTU > 65535)) {
            throw ChainedConfigurationException(ChainedConfigurationFailure.MTU_OUT_OF_RANGE)
        }
        // The engine ABI caps an `encapsulate` source at 1500 bytes (its inner-IP limit). An MTU
        // in 1501..65535 would have the data path produce ordinary MTU-sized packets that the
        // engine rejects as `PacketTooLarge`, so the queue would shed essentially everything.
        // Refuse rather than clamp: clamping would run the interface at an MTU the user did not
        // configure, and the queue's own byte budget is sized from this value.
        if (interfaceMTU != null &&
            interfaceMTU > ChainedPacketQueueLimits.MAXIMUM_IP_PACKET_BYTE_COUNT
        ) {
            throw ChainedConfigurationException(
                ChainedConfigurationFailure.MTU_ABOVE_ENGINE_IP_LIMIT,
            )
        }
        if (interfaceMTU != null && interfaceMTU < ChainedUpstreamConfiguration.MINIMUM_IPV6_LINK_MTU) {
            throw ChainedConfigurationException(ChainedConfigurationFailure.MTU_BELOW_IPV6_FLOOR)
        }
        if (!dnsAddresses.all(::isIPLiteral)) {
            throw ChainedConfigurationException(ChainedConfigurationFailure.MALFORMED_DNS_ADDRESS)
        }
        return policy
    }

    /**
     * Whether `address` is an IPv4 literal usable as this client's own tunnel address.
     *
     * Routable unicast only: 0.0.0.0/8, 127.0.0.0/8, 169.254.0.0/16, and 224.0.0.0/4-and-up
     * (multicast, reserved, broadcast) are what a half-filled or hand-edited config produces,
     * and none is a source a peer will accept. Private ranges are deliberately allowed.
     */
    fun isUsableClientAddress(address: String): Boolean {
        val octets = ipv4Octets(address) ?: return false
        if (octets[0] == 0) return false
        if (octets[0] == 127) return false
        if (octets[0] == 169 && octets[1] == 254) return false
        if (octets[0] >= 224) return false
        return true
    }

    /**
     * Whether `prefix` parses as a prefix the data path can represent. PARSED, not
     * pattern-matched; a length is required for both families. IPv4 uses this file's own
     * canonical-octet parser (leading zeros rejected) rather than the platform's lenient
     * parser, so `010.0.0.0/8` is not a second spelling of `10.0.0.0/8`.
     */
    fun isPlausiblePrefix(prefix: String): Boolean {
        val parts = prefix.split("/")
        if (parts.size != 2) return false
        val addressText = parts[0]
        val lengthText = parts[1]
        if (lengthText.isEmpty() || !lengthText.all { it in '0'..'9' }) return false
        if (lengthText.length > 1 && lengthText[0] == '0') return false
        val length = lengthText.toIntOrNull() ?: return false
        return if (addressText.contains(':')) {
            isIpv6Literal(addressText) && length in 0..128
        } else {
            ipv4Octets(addressText) != null && length in 0..32
        }
    }

    /** Whether `text` is an IP literal of either family in the spellings this boundary accepts. */
    fun isIPLiteral(text: String): Boolean =
        if (text.contains(':')) isIpv6Literal(text) else ipv4Octets(text) != null

    /**
     * Whether the IPv4 prefixes in `prefixes` collectively COVER `0.0.0.0/0` — whether their
     * union spans the whole 32-bit IPv4 space. Coverage, not a literal match, so the
     * `0.0.0.0/1, 128.0.0.0/1` full-tunnel idiom is recognised. IPv6 entries are ignored.
     */
    fun coversIPv4DefaultRoute(prefixes: List<String>): Boolean {
        val blocks = prefixes.mapNotNull(::ipv4Block).sortedBy { it.first }
        var nextNeeded = 0L
        for (block in blocks) {
            if (block.first > nextNeeded) return false
            if (block.second >= nextNeeded) nextNeeded = block.second + 1
            if (nextNeeded > 0xFFFFFFFFL) return true
        }
        return nextNeeded > 0xFFFFFFFFL
    }

    /**
     * The inclusive `[start, end]` 32-bit range an IPv4 prefix covers, or `null` for anything
     * that is not an IPv4 prefix. Host bits are masked off the start so `10.1.2.3/8` and
     * `10.0.0.0/8` claim the same block.
     */
    fun ipv4Block(prefix: String): Pair<Long, Long>? {
        val parts = prefix.split("/")
        if (parts.size != 2) return null
        val length = parts[1].toIntOrNull() ?: return null
        if (length !in 0..32) return null
        val octets = ipv4Octets(parts[0]) ?: return null
        val address = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
        val blockSize = 1L shl (32 - length)
        val network = address and (blockSize - 1).inv() and 0xFFFFFFFFL
        return network to (network + blockSize - 1)
    }

    /**
     * Whether a 32-byte X25519 public key is one of Curve25519's small-order points. A
     * small-order peer key drives the DH to an all-zero shared secret whatever our key is,
     * making the session's key material predictable; nothing below us checks it.
     */
    fun isSmallOrderPoint(key: ByteArray): Boolean {
        if (key.size != ChainedUpstreamConfiguration.PUBLIC_KEY_BYTE_COUNT) return false
        return smallOrderPoints.any { it.contentEquals(key) }
    }

    /**
     * The table's size, for the test that asserts nothing was added to it. Only the count is
     * exposed; the table stays private so a test cannot take its expected values from it (an
     * oracle derived from the thing under test agrees with a dropped or corrupted entry).
     */
    fun smallOrderPointCount(): Int = smallOrderPoints.size

    /**
     * The seven canonical u-coordinates of order dividing 8 plus each with the high bit set —
     * that bit is ignored during scalar multiplication, so the twin is an equivalent key and
     * checking only the canonical spelling would be a check that looks complete and is not.
     */
    private val smallOrderPoints: List<ByteArray> = run {
        val canonical = listOf(
            ByteArray(32) { 0x00 },
            byteArrayOf(0x01) + ByteArray(31) { 0x00 },
            byteArrayOf(
                0xe0.toByte(), 0xeb.toByte(), 0x7a, 0x7c, 0x3b, 0x41, 0xb8.toByte(), 0xae.toByte(),
                0x16, 0x56, 0xe3.toByte(), 0xfa.toByte(), 0xf1.toByte(), 0x9f.toByte(), 0xc4.toByte(),
                0x6a, 0xda.toByte(), 0x09, 0x8d.toByte(), 0xeb.toByte(), 0x9c.toByte(), 0x32, 0xb1.toByte(),
                0xfd.toByte(), 0x86.toByte(), 0x62, 0x05, 0x16, 0x5f, 0x49, 0xb8.toByte(), 0x00,
            ),
            byteArrayOf(
                0x5f, 0x9c.toByte(), 0x95.toByte(), 0xbc.toByte(), 0xa3.toByte(), 0x50, 0x8c.toByte(),
                0x24, 0xb1.toByte(), 0xd0.toByte(), 0xb1.toByte(), 0x55, 0x9c.toByte(), 0x83.toByte(),
                0xef.toByte(), 0x5b, 0x04, 0x44, 0x5c, 0xc4.toByte(), 0x58, 0x1c, 0x8e.toByte(), 0x86.toByte(),
                0xd8.toByte(), 0x22, 0x4e, 0xdd.toByte(), 0xd0.toByte(), 0x9f.toByte(), 0x11, 0x57,
            ),
            byteArrayOf(0xec.toByte()) + ByteArray(30) { 0xff.toByte() } + byteArrayOf(0x7f),
            byteArrayOf(0xed.toByte()) + ByteArray(30) { 0xff.toByte() } + byteArrayOf(0x7f),
            byteArrayOf(0xee.toByte()) + ByteArray(30) { 0xff.toByte() } + byteArrayOf(0x7f),
        )
        canonical + canonical.map { point ->
            point.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() }
        }
    }

    /**
     * Whether `host` is a plausible endpoint host, i.e. it has a valid ASCII (A-label) wire form.
     *
     * Delegates to [endpointHostWireFormOrNull] so validation and the configuration's
     * `endpointHostWireForm` can never disagree about normalization — an earlier split accepted
     * `host。.` (one ASCII root dot, one mapped) while the getter re-canonicalized the untrimmed
     * input and produced two trailing dots.
     */
    fun isPlausibleEndpointHost(host: String): Boolean = endpointHostWireFormOrNull(host) != null

    /**
     * The validated ASCII wire form of `host`, or `null` when it is not a plausible host.
     *
     * Strips one raw trailing root dot, canonicalizes non-ASCII with the module's pinned UTS46
     * canonicalizer, strips one root dot the canonicalizer may have mapped (e.g. U+3002), then
     * applies the delimiter/control, length, and label checks to that final ASCII form.
     */
    fun endpointHostWireFormOrNull(host: String): String? {
        if (host.isEmpty()) return null
        val candidate = if (host.endsWith(".")) host.dropLast(1) else host
        if (candidate.isEmpty()) return null
        for (ch in candidate) {
            if (ch.isWhitespace() || ch.isISOControl()) return null
            if (ch in URI_DELIMITERS) return null
        }
        val wireForm = if (candidate.all { it.code < 0x80 }) {
            candidate
        } else {
            try {
                IdnaHostCanonicalizer.toAscii(candidate)
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
        // The canonicalizer can map a UTS46 root separator (e.g. '。') onto an ASCII trailing
        // dot; strip it so an internationalized FQDN gets the same treatment as "host.". Strip
        // it ONLY when `candidate` did not itself end in an ASCII dot: a candidate still ending
        // in '.' means the original carried a second real root dot (`host..`), which must fail
        // the empty-label check below rather than be papered over. Testing the canonicalized
        // string instead would wrongly repair `vpn。a..`, whose mapped dot sits mid-host.
        val normalized = if (candidate.endsWith(".")) wireForm else wireForm.removeSuffix(".")
        if (normalized.isEmpty()) return null
        // Re-check the ASCII (A-label) form: the canonicalizer applies UTS46 mappings, which can
        // map Unicode compatibility characters onto ASCII delimiters (fullwidth '：' -> ':').
        for (ch in normalized) {
            if (ch.isWhitespace() || ch.isISOControl() || ch in URI_DELIMITERS) return null
        }
        if (normalized.toByteArray(Charsets.UTF_8).size > 253) return null
        for (label in normalized.split('.')) {
            if (label.isEmpty() || label.toByteArray(Charsets.UTF_8).size > 63) return null
        }
        return normalized
    }

    /**
     * The strict base64 decode: rejects trailing junk, unpadded spellings, noncanonical pad
     * bits, and any length other than 32 bytes. Java's decoder tolerates a missing `=` and
     * ignores the unused low bits of the final sextet, so a 43-character key or a `...J=` key
     * that decodes to the same bytes as `...I=` would otherwise be accepted and stored as a
     * second spelling of one key. Re-encoding and comparing keeps one canonical form.
     */
    fun decodedKey(base64: String): ByteArray? {
        if (base64.isEmpty() || base64.length % 4 != 0) return null
        val decoded = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.size != ChainedUpstreamConfiguration.KEY_BYTE_COUNT) return null
        if (Base64.getEncoder().encodeToString(decoded) != base64) return null
        return decoded
    }

    /**
     * Whether `text` is an IPv6 literal in the strict textual syntax WireGuard/`inet_pton`
     * accept.
     *
     * Hand-parsed rather than delegated to `InetAddress.getByName`, whose permissiveness kept
     * admitting spellings the downstream parser rejects (leading-zero octets in an embedded
     * IPv4 suffix, URIs-style brackets, scope ids, and overlong/zero-padded hextets such as
     * `1:00000::1`). Rules: 8 hex groups (1-4 ASCII hex digits each) or one `::` standing for
     * one or more zero groups, with an optional canonical dotted-quad suffix occupying the
     * last two groups.
     */
    private fun isIpv6Literal(text: String): Boolean {
        if (text.isEmpty() || !text.contains(':')) return false
        if (text.any { it == '%' || it == '[' || it == ']' }) return false

        var working = text
        if (working.contains('.')) {
            val lastColon = working.lastIndexOf(':')
            if (lastColon < 0) return false
            if (ipv4Octets(working.substring(lastColon + 1)) == null) return false
            // The embedded quad occupies the final 32 bits: two hextet slots.
            working = working.substring(0, lastColon) + ":0:0"
        }

        if (working.contains(":::")) return false
        if (countOccurrences(working, "::") > 1) return false

        val hasDoubleColon = working.contains("::")
        val groups: List<String>
        if (hasDoubleColon) {
            val parts = working.split("::", limit = 2)
            val head = if (parts[0].isEmpty()) emptyList() else parts[0].split(':')
            val tail = if (parts[1].isEmpty()) emptyList() else parts[1].split(':')
            groups = head + tail
        } else {
            groups = working.split(':')
        }
        if (groups.any { !isHextet(it) }) return false
        return if (hasDoubleColon) groups.size <= 7 else groups.size == 8
    }

    /** One IPv6 hextet: 1-4 ASCII hex digits, canonical (no other character). */
    private fun isHextet(group: String): Boolean =
        group.length in 1..4 &&
            group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    /**
     * The four octets of a dotted-quad IPv4 literal, or `null`. Strictly canonical: exactly
     * four decimal 0-255 parts, no leading `+`, sign, whitespace, or leading zeros.
     */
    fun ipv4Octets(text: String): IntArray? {
        val parts = text.split(".")
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
            val value = part.toIntOrNull() ?: return null
            if (value > 255) return null
            if (part.length > 1 && part[0] == '0') return null
            octets[index] = value
        }
        return octets
    }

    private val URI_DELIMITERS: Set<Char> = setOf(
        ':', '/', '@', '?', '#', '[', ']', '%', '\\', '|', '^', '`', '"', '\'', '<', '>', '{', '}', ',',
    )
}
