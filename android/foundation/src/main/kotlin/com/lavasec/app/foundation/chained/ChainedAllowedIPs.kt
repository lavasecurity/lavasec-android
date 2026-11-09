package com.lavasec.app.foundation.chained

import java.util.Collections

/**
 * One CIDR prefix from a peer's `AllowedIPs`.
 *
 * Stored as masked network-order bytes so a membership test is a bit comparison rather than
 * string work, and so `10.1.2.3/8` and `10.0.0.0/8` cannot behave differently. Mirrors iOS
 * `ChainedIPPrefix` (`Sources/LavaSecChainedUpstream/ChainedAllowedIPs.swift`).
 *
 * The bytes are exposed for the data path but are effectively immutable: every constructor path
 * masks host bits on the way in, so no caller can observe an unmasked prefix.
 */
class ChainedIPPrefix private constructor(
    /** 4 bytes for IPv4, 16 for IPv6, network order, with every bit past [prefixLength] cleared. */
    private val bytes: ByteArray,
    /** Significant bits. `0` matches the whole family; `32`/`128` is a single host. */
    val prefixLength: Int,
) {
    /** A defensive copy of the masked network bytes; mutating it cannot change this prefix. */
    val networkBytes: ByteArray get() = bytes.copyOf()

    /** Whether this prefix is IPv6 (16 bytes) rather than IPv4 (4 bytes). */
    val isIPv6: Boolean get() = bytes.size == 16

    /**
     * Whether [octets] falls inside this prefix.
     *
     * Families never cross. A 4-byte source is not tested against an IPv6 prefix even when that
     * prefix covers the IPv4-mapped range: the engine reports v4 and v6 inner packets separately,
     * and treating `::ffff:10.0.0.1/104` as authority over `10.0.0.1` would let a v6 rule silently
     * widen the v4 allowlist.
     *
     * Whole bytes are compared first, then the PARTIAL byte. Comparing whole bytes only would turn
     * every `/9`…`/15` into a `/8` and admit an eighth of the internet on a rule that reads as
     * narrow.
     */
    fun contains(octets: ByteArray): Boolean {
        if (octets.size != bytes.size) return false
        if (prefixLength <= 0) return true

        val wholeBytes = prefixLength / 8
        for (index in 0 until wholeBytes) {
            if (octets[index] != bytes[index]) return false
        }
        val remainder = prefixLength % 8
        if (remainder == 0) return true
        val mask = 0xFF shl (8 - remainder)
        return (octets[wholeBytes].toInt() and mask) == (bytes[wholeBytes].toInt() and mask)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChainedIPPrefix) return false
        return prefixLength == other.prefixLength &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + prefixLength

    override fun toString(): String = "ChainedIPPrefix(${bytes.size * 8}-bit /$prefixLength)"

    companion object {
        /**
         * Parses `address/length`, and a bare address as a single host.
         *
         * Rejects a prefix length outside the family's range. `10.0.0.0/33` is not a permissive
         * prefix or a typo to round down — it is input nobody should act on, and silently clamping
         * it would widen or narrow what a peer may claim without telling anyone. The length text is
         * the strict canonical decimal form this module's configuration boundary already accepts,
         * so `010.0.0.0/08` cannot become a second spelling of `10.0.0.0/8`.
         *
         * IPv4 octet parsing is delegated to [ChainedUpstreamConfigurationValidator.ipv4Octets] so
         * there is exactly one dotted-quad parser (leading zeros rejected); only the IPv6 byte
         * decoder lives here.
         */
        fun parse(text: String): ChainedIPPrefix? {
            val slash = text.indexOf('/')
            val addressText: String
            val lengthText: String?
            if (slash < 0) {
                addressText = text
                lengthText = null
            } else {
                addressText = text.substring(0, slash)
                lengthText = text.substring(slash + 1)
            }
            if (addressText.isEmpty()) return null

            val raw: ByteArray = if (addressText.contains(':')) {
                ipv6Bytes(addressText) ?: return null
            } else {
                val octets = ChainedUpstreamConfigurationValidator.ipv4Octets(addressText)
                    ?: return null
                ByteArray(4) { octets[it].toByte() }
            }
            val bitCount = raw.size * 8

            val length: Int
            if (lengthText != null) {
                if (lengthText.isEmpty() || !lengthText.all { it in '0'..'9' }) return null
                if (lengthText.length > 1 && lengthText[0] == '0') return null
                val parsed = lengthText.toIntOrNull() ?: return null
                if (parsed !in 0..bitCount) return null
                length = parsed
            } else {
                length = bitCount
            }

            // Masked on the way in. A prefix carrying host bits would otherwise compare unequal to
            // an identical prefix written canonically, and two spellings of one rule is how an
            // allowlist quietly gains a hole.
            for (index in raw.indices) {
                val bitsBefore = index * 8
                if (bitsBefore >= length) {
                    raw[index] = 0
                } else if (bitsBefore + 8 > length) {
                    val keep = length - bitsBefore
                    raw[index] = (raw[index].toInt() and (0xFF shl (8 - keep))).toByte()
                }
            }
            return ChainedIPPrefix(raw, length)
        }

        /**
         * The 16 network-order bytes of a strict textual IPv6 literal, or `null`.
         *
         * Hand-parsed to match [ChainedUpstreamConfigurationValidator]'s literal rules exactly: 8
         * hex groups (1-4 ASCII hex digits) or one `::` standing for one or more zero groups, with
         * an optional canonical dotted-quad occupying the last two groups. No scope ids, no
         * brackets. The IPv4 suffix is converted through the same strict octet parser, so
         * `::ffff:010.0.0.1` is rejected rather than admitted as an alias.
         */
        private fun ipv6Bytes(text: String): ByteArray? {
            if (text.isEmpty() || !text.contains(':')) return null
            if (text.any { it == '%' || it == '[' || it == ']' }) return null

            var working = text
            if (working.contains('.')) {
                val lastColon = working.lastIndexOf(':')
                if (lastColon < 0) return null
                val quad = ChainedUpstreamConfigurationValidator
                    .ipv4Octets(working.substring(lastColon + 1)) ?: return null
                working = working.substring(0, lastColon) +
                    ":" + String.format("%02x%02x:%02x%02x", quad[0], quad[1], quad[2], quad[3])
            }

            if (working.contains(":::")) return null
            if (countOccurrences(working, "::") > 1) return null

            val hasDoubleColon = working.contains("::")
            val head: List<String>
            val tail: List<String>
            if (hasDoubleColon) {
                val first = working.indexOf("::")
                val before = working.substring(0, first)
                val after = working.substring(first + 2)
                head = if (before.isEmpty()) emptyList() else before.split(':')
                tail = if (after.isEmpty()) emptyList() else after.split(':')
            } else {
                head = working.split(':')
                tail = emptyList()
            }
            if (head.any { !isHextet(it) } || tail.any { !isHextet(it) }) return null
            if (hasDoubleColon) {
                if (head.size + tail.size > 7) return null
            } else if (head.size != 8) {
                return null
            }

            val hextets = head + List(8 - head.size - tail.size) { "0" } + tail
            val bytes = ByteArray(16)
            for (index in hextets.indices) {
                val value = hextets[index].toInt(16)
                bytes[index * 2] = ((value shr 8) and 0xFF).toByte()
                bytes[index * 2 + 1] = (value and 0xFF).toByte()
            }
            return bytes
        }

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
    }
}

/**
 * What the tunnel does with a decrypted packet.
 *
 * Mirrors iOS `ChainedInboundVerdict`. The two drop reasons are distinct on purpose:
 * [DropSpoofedSource] says the authenticated PEER lied about the source address, while
 * [DropMalformedSource] says our own layers handed us a length that is neither 4 nor 16 bytes —
 * an ABI-level impossibility. Collapsing them would attribute our defect to the peer.
 */
sealed interface ChainedInboundVerdict {
    /** Whether the packet reaches the tunnel. */
    val deliversToTunnel: Boolean get() = this is Deliver

    /** Stable identifier for device logs. Never user copy, and never the address itself. */
    val logValue: String

    /** The source is inside the peer's `AllowedIPs`. Write it to the tunnel. */
    data class Deliver(val byteCount: Int) : ChainedInboundVerdict {
        override val logValue: String get() = "inbound-deliver"
    }

    /** The source is outside `AllowedIPs`. Discard it and count it — see [ChainedAllowedIPs]. */
    data class DropSpoofedSource(val byteCount: Int) : ChainedInboundVerdict {
        override val logValue: String get() = "inbound-drop-source-not-allowed"
    }

    /** The engine reported a source address that is neither 4 nor 16 bytes. */
    data object DropMalformedSource : ChainedInboundVerdict {
        override val logValue: String get() = "inbound-drop-source-malformed"
    }
}

/**
 * The peer's `AllowedIPs`, enforced rather than assumed.
 *
 * Plan: lavasec-infra `plans/2026-09-12-android-chained-upstream-engine-plan.md` (Slice 4).
 *
 * ## Why this exists
 *
 * WireGuard calls it cryptokey routing, and it is half of the protocol's security model: a peer
 * may only send you packets whose SOURCE lies inside the range you assigned it. BoringTun performs
 * this in its `Device` layer; this engine drives `Tunn` directly, and `Tunn` does not filter. So on
 * this path the check exists here or nowhere — and "nowhere" means a peer can hand us a packet
 * claiming the resolver's address, a bank's, or `10.111.0.1`, our own in-tunnel DNS proxy. Those
 * packets would be written into the TUN and every app would treat them as having come from the
 * address they claim.
 *
 * ## An empty set permits nothing
 *
 * Fail closed. An empty `AllowedIPs` grants a peer no addresses; reading it as "unrestricted" would
 * turn the most restrictive input into the most permissive one. The empty SET still permits nothing
 * — that rule is unchanged — but [parse] additionally refuses the empty LIST so a blank
 * `AllowedIPs =` line cannot present as a peer attack.
 */
class ChainedAllowedIPs private constructor(
    val prefixes: List<ChainedIPPrefix>,
) {
    /**
     * Whether a decrypted packet's source address is permitted.
     *
     * Allocation-free: the caller passes the captured address bytes directly.
     */
    fun permits(sourceOctets: ByteArray): Boolean = prefixes.any { it.contains(sourceOctets) }

    /**
     * The verdict for one decrypted packet.
     *
     * A source byte count that is neither 4 nor 16 is [ChainedInboundVerdict.DropMalformedSource]
     * BEFORE any prefix test: no valid prefix can match it, and reporting it as a spoof would blame
     * the peer for a length only our own capture path can produce.
     */
    fun verdict(sourceOctets: ByteArray, byteCount: Int): ChainedInboundVerdict {
        if (sourceOctets.size != 4 && sourceOctets.size != 16) {
            return ChainedInboundVerdict.DropMalformedSource
        }
        return if (permits(sourceOctets)) {
            ChainedInboundVerdict.Deliver(byteCount)
        } else {
            ChainedInboundVerdict.DropSpoofedSource(byteCount)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChainedAllowedIPs) return false
        return prefixes == other.prefixes
    }

    override fun hashCode(): Int = prefixes.hashCode()

    companion object {
        /**
         * A set over already-parsed prefixes. An EMPTY set is legal here and permits nothing;
         * [parse] is the boundary that refuses to build one from a blank configuration line.
         */
        fun of(prefixes: List<ChainedIPPrefix>): ChainedAllowedIPs =
            ChainedAllowedIPs(Collections.unmodifiableList(prefixes.toList()))

        /**
         * Parses a comma-separated `AllowedIPs` list, as a `.conf` writes it.
         *
         * Returns `null` if ANY entry is unparseable, and also if the list is EMPTY. A partial
         * parse would silently narrow the allowlist, which fails closed and therefore looks like a
         * working tunnel that drops some traffic — the hardest misconfiguration to diagnose. An
         * empty list is refused for a sharper reason: parsing it succeeded with no prefixes, and
         * since an empty set permits nothing, every packet would surface as a spoofed source, so a
         * blank `AllowedIPs =` line would have presented as the peer attacking.
         */
        fun parse(list: String): ChainedAllowedIPs? {
            val parsed = mutableListOf<ChainedIPPrefix>()
            for (entry in list.split(',')) {
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) continue
                val prefix = ChainedIPPrefix.parse(trimmed) ?: return null
                parsed += prefix
            }
            if (parsed.isEmpty()) return null
            return ChainedAllowedIPs(Collections.unmodifiableList(parsed.toList()))
        }
    }
}
