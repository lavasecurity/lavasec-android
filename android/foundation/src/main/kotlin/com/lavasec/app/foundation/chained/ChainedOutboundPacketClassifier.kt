package com.lavasec.app.foundation.chained

/**
 * What the tunnel must do with one packet the OS handed it, while chained.
 *
 * Ported from iOS `ChainedOutboundDisposition`. Deliberately a separate case for the
 * own-resolver carve-out rather than reusing [Encapsulate]: making it visible in the type earns it
 * its own counter, and a spike in that counter with no resolver activity behind it becomes a
 * detectable signal rather than noise inside the general encapsulation count.
 *
 * Every case is a singleton `data object`: the disposition carries no packet size, so classifying
 * a packet allocates nothing. The caller already holds the packet and supplies its size to any
 * accounting that needs it (`packet.size`), which keeps the packet path free of per-packet garbage.
 */
sealed interface ChainedOutboundDisposition {
    /** Stable identifier for counters and the device log. Never user copy. */
    val logValue: String

    /** A DNS query for the local resolver path. Filtering owns it; the peer never sees it. */
    data object HandleAsDNS : ChainedOutboundDisposition {
        override val logValue: String get() = "handle-as-dns"
    }

    /** Ordinary traffic. Encrypt it for the peer. */
    data object Encapsulate : ChainedOutboundDisposition {
        override val logValue: String get() = "encapsulate"
    }

    /** An outbound IPv6 packet, discarded. See [ChainedOutboundPacketClassifier]. */
    data object DropOutboundIPv6 : ChainedOutboundDisposition {
        override val logValue: String get() = "drop-outbound-ipv6"
    }

    /**
     * A DNS query this path cannot filter, dropped rather than handed to the peer.
     *
     * The fail-closed rule is never failing OPEN. Encapsulating a query the filter cannot read sends
     * it to the upstream operator unfiltered and legible — the precise failure that intercepting
     * port 53 exists to prevent, arriving by a shape the interception missed. Dropping fails closed:
     * the resolver retries, or falls back to a form this path does read.
     */
    data object DropUnfilterableDNS : ChainedOutboundDisposition {
        override val logValue: String get() = "drop-unfilterable-dns"
    }

    /**
     * OUR OWN tunnel-pinned resolver query, encapsulated.
     *
     * A distinct case rather than reusing [Encapsulate] so the carve-out is visible in the type
     * rather than hidden in a branch.
     */
    data object EncapsulateOwnResolverQuery : ChainedOutboundDisposition {
        override val logValue: String get() = "encapsulate-own-resolver-query"
    }

    /** Not a packet this tunnel can reason about. Discarded without being forwarded. */
    data object DropMalformed : ChainedOutboundDisposition {
        override val logValue: String get() = "drop-malformed"
    }
}

/**
 * The local source ports this process currently holds for its own tunnel-pinned DNS queries.
 *
 * The classifier's only view of the resolver carve-out. The key is the LOCAL SOURCE PORT, and never
 * the destination address: narrowing DNS interception by destination would hand every
 * hardcoded-resolver query to the upstream operator unfiltered — a leak closed deliberately.
 *
 * This interface is the seam for the concrete registry. The iOS `ChainedResolverPortRegistry` is a
 * lock-guarded, clock-driven, capacity-bounded type in a different module; on Android the registry
 * lands with the resolver-egress component of the same data-path slice, and the classifier depends
 * only on these two reads. A fake is used in tests; the production implementation is deferred and
 * called out as such.
 */
interface ChainedOwnResolverPortRegistry {
    /** Whether this process currently holds [sourcePort] for [protocolNumber]. */
    fun claims(sourcePort: Int, protocolNumber: Int): Boolean

    /**
     * Whether [sourcePort] is ours OR was ours recently enough that a packet carrying it may still
     * be in flight. A port that left the registry a moment ago must not be served as CLIENT DNS, or
     * our own just-released query would feed our own resolver and restart the self-resolution loop.
     */
    fun wasRecentlyClaimed(sourcePort: Int, protocolNumber: Int): Boolean
}

/**
 * Decides what happens to each packet leaving the device while the tunnel is chained.
 *
 * Ported from iOS `ChainedOutboundPacketClassifier`. Plan: lavasec-infra
 * `plans/2026-09-12-android-chained-upstream-engine-plan.md` (Slice 4).
 *
 * ## The blackhole this exists to prevent
 *
 * The DNS-only tunnel claims `10.111.0.1/32`, so the only packets that arrive are DNS queries for
 * the local resolver and anything else genuinely is unexpected. Under chaining the tunnel claims
 * `0.0.0.0/0`: every TCP connection, every IPv6 packet and every non-port-53 datagram on the device
 * now arrives at that path — and is silently discarded unless this classifier says otherwise. Not
 * a degraded tunnel: a total blackhole that looks like a working one.
 *
 * ## Why the DNS branch keys on the PORT and not the destination
 *
 * A query to `8.8.8.8:53` reaches the tunnel under a default-route claim, and it is intercepted
 * exactly as a query to the tunnel resolver is. Narrowing interception to the tunnel's own resolver
 * address would hand every hardcoded-resolver query — the `8.8.8.8` an app compiled in, the
 * `1.1.1.1` a game console insists on — straight to the peer as ordinary encrypted traffic:
 * unfiltered, because filtering happens on the DNS path this would have skipped, and visible to the
 * upstream operator, who is the one party the user chose this feature to hide from.
 *
 * ## Why only IPv6 DNS is served here
 *
 * The peer's `AllowedIPs` carry no IPv6 — the configuration boundary requires only the IPv4 default
 * route — so an encapsulated v6 packet would be discarded at the far end instead of here, spending
 * an encryption and a round trip to reach the same outcome unaccounted. Client UDP/53 is served
 * locally when the installed plan advertises IPv6 DNS; its T0 leg remains IPv4. Claiming `::/0` while
 * chained is what draws those packets in to be dropped rather than leaking around the tunnel.
 *
 * ## Pure function over caller-owned bytes
 *
 * No I/O and no allocation of its own; the one piece of state is the caller's fragment table. The
 * classifier is a pure function of its arguments plus that table, so every decision is executable
 * in a unit test rather than reachable only inside a `VpnService`.
 */
object ChainedOutboundPacketClassifier {
    /** IANA protocol number for TCP. */
    private const val IPPROTO_TCP: Int = 6

    /** IANA protocol number for UDP. */
    private const val IPPROTO_UDP: Int = 17

    /** The fixed IPv4 header length; every field the classifier reads lives in these bytes. */
    private const val IPV4_FIXED_HEADER_BYTES: Int = 20

    /**
     * Classifies one outbound packet.
     *
     * @param packet the bytes as the TUN read them.
     * @param fragments the identities whose first fragment was already dropped as unfilterable DNS.
     *   The decision for a non-first fragment cannot be made from the fragment alone, so this
     *   history is the caller's and the classifier stays a pure function of its inputs plus it.
     * @param ownResolverPorts the source ports this process holds for its own tunnel-pinned
     *   resolver queries.
     * @param reclaimsStaleDenials whether a forwarded fragment head may RELEASE its tuple from the
     *   deny-list. True only when classification happens in the order the packets will leave; a
     *   caller classifying out of release order passes false and gets the fail-closed behaviour.
     */
    fun disposition(
        packet: ByteArray,
        fragments: ChainedDroppedFragmentTable,
        ownResolverPorts: ChainedOwnResolverPortRegistry,
        reclaimsStaleDenials: Boolean = true,
        claimedResolverDestinations: ChainedDnsCaptureFloor = ChainedDnsCaptureFloor.empty,
        servesIPv6Dns: Boolean = false,
    ): ChainedOutboundDisposition {
        if (packet.isEmpty()) return ChainedOutboundDisposition.DropMalformed

        val version = (packet[0].toInt() and 0xFF) ushr 4
        if (version == 6) return if (servesIPv6Dns) {
            ipv6Disposition(packet, ownResolverPorts, claimedResolverDestinations)
        } else ChainedOutboundDisposition.DropOutboundIPv6
        if (version != 4) return ChainedOutboundDisposition.DropMalformed

        // The whole fixed header, before anything else is read. Identification, flags, addresses
        // and protocol all live in the first 20 bytes, and reading them first is what lets a
        // fragment be judged before any length rule can discard it.
        if (packet.size < IPV4_FIXED_HEADER_BYTES) return ChainedOutboundDisposition.DropMalformed
        val headerLength = (packet[0].toInt() and 0x0F) * 4

        // Fragments are inspected BEFORE being waved through: encapsulating any fragment on sight
        // would make fragmentation a DNS-filter bypass, because a UDP/53 query with more-fragments
        // set would go to the peer before its port was ever read.
        val flagsAndFragmentOffset = readUInt16(packet, 6)
        val moreFragments = flagsAndFragmentOffset and 0x2000 != 0
        val fragmentOffset = flagsAndFragmentOffset and 0x1FFF

        // A NON-FIRST FRAGMENT IS DECIDED HERE, ahead of every length guard, because the guards
        // below concern a UDP header this packet does not have. A tail of a dropped DNS query is
        // dropped only if its head was remembered.
        if (fragmentOffset != 0) {
            val identity = identity(packet)
            return if (fragments.contains(identity)) {
                ChainedOutboundDisposition.DropUnfilterableDNS
            } else {
                ChainedOutboundDisposition.Encapsulate
            }
        }

        // Only the exact installed split floor owns these encrypted resolver destinations.
        // An unclaimed :853 route may belong to the user's physical resolver and stays ordinary.
        // Read the port before length guards: a damaged or fragmented encrypted query must not
        // escape merely because its lengths are unreadable. Its tails use the existing deny-list.
        if (headerLength >= IPV4_FIXED_HEADER_BYTES && packet.size >= headerLength + 4 &&
            claimedResolverDestinations.containsIPv4Destination(packet, 16) &&
            ((packet[9].toInt() and 255) == IPPROTO_TCP || (packet[9].toInt() and 255) == IPPROTO_UDP) &&
            readUInt16(packet, headerLength + 2) == 853
        ) {
            if (moreFragments) fragments.remember(identity(packet))
            return ChainedOutboundDisposition.DropUnfilterableDNS
        }

        if (headerLength < IPV4_FIXED_HEADER_BYTES || packet.size < headerLength + 8) {
            // Too short to carry an IPv4 header plus a UDP header. It may still be a legal packet of
            // another protocol — a 20-byte TCP ACK arrives here — so it falls through to the
            // protocol check below rather than being called malformed on its own.
            return unreadableLengthDisposition(
                packet, headerLength, moreFragments, reclaimsStaleDenials, fragments,
            )
        }

        val totalLength = readUInt16(packet, 2)
        if (totalLength < headerLength + 8 || totalLength > packet.size) {
            return unreadableLengthDisposition(
                packet, headerLength, moreFragments, reclaimsStaleDenials, fragments,
            )
        }

        val protocolNumber = packet[9].toInt() and 0xFF

        // TCP/53 IS DNS, and most often the retry a truncated UDP answer forces. Treating it as
        // ordinary traffic hands the peer the very query the UDP interception just protected. The
        // resolver path reads UDP only, so this cannot be served locally; the fail-closed rule says
        // drop, not fail outward.
        if (protocolNumber == IPPROTO_TCP) {
            val tcpOffset = headerLength
            if (packet.size < tcpOffset + 4) {
                return encapsulateHead(packet, moreFragments, reclaimsStaleDenials, fragments)
            }
            if (readUInt16(packet, tcpOffset + 2) == 53) {
                // OUR OWN retry, and nothing else. Keyed on the LOCAL SOURCE PORT, never on the
                // destination. `!moreFragments` is exactness rather than caution: our own segments
                // are kernel-built and MSS-clamped, so a FRAGMENTED TCP/53 packet is never ours.
                if (!moreFragments &&
                    ownResolverPorts.claims(readUInt16(packet, tcpOffset), protocolNumber)
                ) {
                    return ChainedOutboundDisposition.EncapsulateOwnResolverQuery
                }
                // A fragmented TCP/53 query leaks through its tails exactly as a UDP one does, so
                // its identity is remembered — but only when there ARE tails.
                if (moreFragments) fragments.remember(identity(packet))
                return ChainedOutboundDisposition.DropUnfilterableDNS
            }
            return encapsulateHead(packet, moreFragments, reclaimsStaleDenials, fragments)
        }

        if (protocolNumber != IPPROTO_UDP) {
            return encapsulateHead(packet, moreFragments, reclaimsStaleDenials, fragments)
        }

        val udpOffset = headerLength
        val destinationPort = readUInt16(packet, udpOffset + 2)

        // A FIRST FRAGMENT is decided HERE, before the UDP length is consulted, because for a first
        // fragment that field describes the WHOLE reassembled datagram, not these bytes. The
        // destination port IS readable, and it is the only thing this decision needs. A fragmented
        // DNS query cannot be filtered, so it fails closed; everything else is ordinary traffic.
        if (moreFragments) {
            if (destinationPort != 53) {
                return encapsulateHead(packet, true, reclaimsStaleDenials, fragments)
            }
            fragments.remember(identity(packet))
            return ChainedOutboundDisposition.DropUnfilterableDNS
        }

        val udpLength = readUInt16(packet, udpOffset + 4)
        if (udpLength < 8 || udpOffset + udpLength > totalLength) {
            // An UNFRAGMENTED UDP length that overruns its own datagram is not something to forward:
            // "the far end will cope" is how a malformed packet becomes someone else's problem.
            return ChainedOutboundDisposition.DropMalformed
        }

        if (destinationPort != 53) {
            return encapsulateHead(packet, moreFragments, reclaimsStaleDenials, fragments)
        }

        // OUR OWN QUERY. Without this arm chained mode does not merely leak — it does not survive:
        // classifying our own UDP query as handled DNS feeds our own resolver's datagram back into
        // the resolver, opening a socket per turn and looping unbounded.
        val udpSourcePort = readUInt16(packet, udpOffset)
        if (ownResolverPorts.claims(udpSourcePort, protocolNumber)) {
            return ChainedOutboundDisposition.EncapsulateOwnResolverQuery
        }

        // OURS A MOMENT AGO, AND STILL IN FLIGHT. A packet reaches this function through the engine
        // queue, so an own-resolver query can be classified after its socket was torn down and its
        // claim released. `claims` is false by then and the query would read as a CLIENT query —
        // served by our own resolver, which answers it by sending another. Dropped rather than
        // encapsulated: the port is no longer ours to send from.
        if (ownResolverPorts.wasRecentlyClaimed(udpSourcePort, protocolNumber)) {
            return ChainedOutboundDisposition.DropUnfilterableDNS
        }

        // A port-53 datagram with no payload is not a query, and the DNS parser rejects it — so
        // classifying it as DNS would break the differential property. Dropping also matches what
        // the DNS-only path already does with this packet.
        val payloadStart = udpOffset + 8
        val payloadEnd = udpOffset + udpLength
        if (payloadEnd <= payloadStart) {
            return ChainedOutboundDisposition.DropUnfilterableDNS
        }

        return ChainedOutboundDisposition.HandleAsDNS
    }

    private fun ipv6Disposition(
        packet: ByteArray,
        ownResolverPorts: ChainedOwnResolverPortRegistry,
        claimed: ChainedDnsCaptureFloor,
    ): ChainedOutboundDisposition {
        if (packet.size < 48 || (packet[6].toInt() and 255) != IPPROTO_UDP) {
            return ChainedOutboundDisposition.DropOutboundIPv6
        }
        val sourcePort = readUInt16(packet, 40)
        val destinationPort = readUInt16(packet, 42)
        if (destinationPort == 853 && claimed.containsIPv6Destination(packet, 24)) {
            return ChainedOutboundDisposition.DropUnfilterableDNS
        }
        val payloadLength = readUInt16(packet, 4)
        val udpLength = readUInt16(packet, 44)
        if (payloadLength < 8 || 40 + payloadLength > packet.size || udpLength < 8 || udpLength > payloadLength) {
            return if (destinationPort == 53) ChainedOutboundDisposition.DropUnfilterableDNS
            else ChainedOutboundDisposition.DropOutboundIPv6
        }
        if (destinationPort != 53) return ChainedOutboundDisposition.DropOutboundIPv6
        // The installed T0 egress constructs IPv4 packets only. This registry holds numeric
        // source ports, not address-family tuples, so an IPv6 client can collide with an IPv4
        // claim. It must never acquire that claim's unfiltered encapsulation carve-out or be
        // handed to a peer that cannot carry IPv6. Real own queries retain the IPv4 arm above.
        if (ownResolverPorts.claims(sourcePort, IPPROTO_UDP) ||
            ownResolverPorts.wasRecentlyClaimed(sourcePort, IPPROTO_UDP) || udpLength == 8
        ) {
            return ChainedOutboundDisposition.DropUnfilterableDNS
        }
        return ChainedOutboundDisposition.HandleAsDNS
    }

    /**
     * A packet whose lengths do not describe it, classified by what it is rather than by what it is
     * not.
     *
     * A short IPv4 packet is ordinary traffic — a bare TCP ACK is 40 bytes and legal — so it is
     * encapsulated. Only a packet whose own header length field points past its end is malformed,
     * because that is a packet no reader can act on. THE PORT IS STILL READ: a UDP/53 datagram whose
     * total-length field was nonsense is not filtered locally either, so handing it to the peer
     * would leak the query bytes to the observer. A packet too damaged to classify is exactly the
     * one that must fail closed, and the destination port survives the damage whenever four
     * transport bytes are present.
     */
    private fun unreadableLengthDisposition(
        packet: ByteArray,
        headerLength: Int,
        moreFragments: Boolean,
        reclaims: Boolean,
        fragments: ChainedDroppedFragmentTable,
    ): ChainedOutboundDisposition {
        if (headerLength < IPV4_FIXED_HEADER_BYTES || packet.size < headerLength) {
            return ChainedOutboundDisposition.DropMalformed
        }
        if (packet.size < headerLength + 4) {
            return encapsulateHead(packet, moreFragments, reclaims, fragments)
        }
        val protocolNumber = packet[9].toInt() and 0xFF
        val isTransportWithPorts =
            protocolNumber == IPPROTO_UDP || protocolNumber == IPPROTO_TCP
        if (isTransportWithPorts && readUInt16(packet, headerLength + 2) == 53) {
            // Remembered here too: a port-53 first fragment whose total length was damaged must
            // still silence its well-formed tails, or they are forwarded with the query bytes.
            if (moreFragments) fragments.remember(identity(packet))
            return ChainedOutboundDisposition.DropUnfilterableDNS
        }
        return encapsulateHead(packet, moreFragments, reclaims, fragments)
    }

    /**
     * Forwards a first fragment, RECLAIMING its tuple from the deny-list on the way out.
     *
     * Every `.encapsulate` returned for an offset-zero packet passes through here, so the table
     * never contradicts the disposition of the most recent head it has seen for a tuple. The
     * reclaim happens only when the head has continuations to protect and the caller can assert
     * release order; otherwise the stale denial stands, which is the fail-CLOSED direction.
     */
    private fun encapsulateHead(
        packet: ByteArray,
        moreFragments: Boolean,
        reclaims: Boolean,
        fragments: ChainedDroppedFragmentTable,
    ): ChainedOutboundDisposition {
        if (moreFragments && reclaims) fragments.forget(identity(packet))
        return ChainedOutboundDisposition.Encapsulate
    }

    /**
     * This packet's IPv4 destination, or `null` when it is not a readable IPv4 header.
     *
     * Public because the reachability accounting ([ChainedDestinationTable]) needs the same field,
     * and IP-header offsets belong in one file. It re-validates rather than trusting the caller.
     * Returns `null` for IPv6 by construction: while chained every outbound v6 packet is dropped
     * here, so a v6 destination could only record a send this process itself discarded.
     */
    fun ipv4Destination(packet: ByteArray): Long? {
        if (packet.size < IPV4_FIXED_HEADER_BYTES) return null
        if (((packet[0].toInt() and 0xFF) ushr 4) != 4) return null
        return readUInt32(packet, 16)
    }

    /**
     * The reassembly key: source, destination, protocol and identification.
     *
     * Every field read here lives in the first 20 bytes, and callers have already established
     * `headerLength >= 20` with the packet at least that long.
     */
    private fun identity(packet: ByteArray): ChainedFragmentIdentity =
        ChainedFragmentIdentity(
            source = readUInt32(packet, 12),
            destination = readUInt32(packet, 16),
            identification = readUInt16(packet, 4),
            protocolNumber = packet[9].toInt() and 0xFF,
        )

    /** Big-endian 32-bit read in the range `0..0xFFFFFFFF`. */
    private fun readUInt32(packet: ByteArray, index: Int): Long =
        ((packet[index].toLong() and 0xFF) shl 24) or
            ((packet[index + 1].toLong() and 0xFF) shl 16) or
            ((packet[index + 2].toLong() and 0xFF) shl 8) or
            (packet[index + 3].toLong() and 0xFF)

    /** Big-endian 16-bit read in the range `0..65535`. */
    private fun readUInt16(packet: ByteArray, index: Int): Int =
        ((packet[index].toInt() and 0xFF) shl 8) or (packet[index + 1].toInt() and 0xFF)
}
