package com.lavasec.app.foundation.chained

/**
 * What happened to a packet offered to a bounded [ChainedPacketQueue]. Mirrors the iOS
 * `ChainedQueueAdmission` vocabulary so a packet shed on Android and one shed on iOS carry the
 * same identifier into a device log.
 */
sealed interface ChainedQueueAdmission {
    /** Whether the queue kept the packet. */
    val wasQueued: Boolean

    /** Stable identifier for device logs. Never user copy. */
    val logValue: String

    /** Queued with room to spare. */
    data object Admitted : ChainedQueueAdmission {
        override val wasQueued: Boolean get() = true
        override val logValue: String get() = "queued"
    }

    /**
     * Queued, after evicting [packets] of the oldest packets ([bytes] bytes) to make room.
     *
     * The eviction counts are carried rather than left to the queue's running totals so a
     * caller can attribute one arrival's cost to the diagnostics event it produced.
     */
    data class AdmittedAfterEvicting(
        val packets: Int,
        val bytes: Int,
    ) : ChainedQueueAdmission {
        override val wasQueued: Boolean get() = true
        override val logValue: String get() = "queued-evicting-$packets"
    }

    /**
     * Larger than the whole byte budget, so no amount of eviction could ever fit it. The queue
     * is left untouched — evicting everything for a packet that still would not fit would
     * discard live traffic and gain nothing.
     */
    data class RefusedOversized(
        val bytes: Int,
        val limit: Int,
    ) : ChainedQueueAdmission {
        override val wasQueued: Boolean get() = false
        override val logValue: String get() = "refused-oversized"
    }

    /**
     * A zero-length packet. Nothing to send, and admitting it would let a stream of them consume
     * the packet budget while moving no bytes.
     */
    data object RefusedEmpty : ChainedQueueAdmission {
        override val wasQueued: Boolean get() = false
        override val logValue: String get() = "refused-empty"
    }

    /**
     * The queue has no packet capacity at all, whatever the packet's size. Kept distinct from
     * [RefusedOversized] because they point at different bugs: this one says the limits are
     * degenerate, that one says the arrival is too big.
     */
    data object RefusedNoCapacity : ChainedQueueAdmission {
        override val wasQueued: Boolean get() = false
        override val logValue: String get() = "refused-no-capacity"
    }
}

/**
 * How much the Android tunnel may hold on a packet's behalf.
 *
 * ## Why a byte budget and not just a packet count
 *
 * The engine bounds its own queue by COUNT (`MAX_QUEUE_DEPTH`), which is fine for it: every
 * packet it queues is one it produced under a known MTU. This queue accepts whatever the TUN
 * hands over, and a count alone does not bound memory — 256 packets is 328 KB at MTU 1280 and
 * several megabytes if something upstream offers jumbo buffers. The Android tunnel process
 * lives under the same kind of hard memory ceiling as the iOS Network Extension, so the
 * constraint that actually matters is bytes.
 *
 * ## Both ends are clamped
 *
 * [maximumPackets] is clamped at BOTH ends. The bottom was always clamped; the top was not, and
 * storage is allocated eagerly at `maximumPackets` slots — so a limit of ten million built ten
 * million slots before the first packet arrived, residency set by the packet bound and escaping
 * the byte budget entirely. [ENGINE_QUEUE_DEPTH] is the ceiling for the same reason it is the
 * default: a queue deeper than the thing it feeds cannot raise throughput, because the engine
 * drains at its own rate regardless, so above it every slot is pure latency and resident memory.
 *
 * [maximumBytes] is floored at zero rather than trusted: a negative budget would make the byte
 * comparison nonsense, and a negative count would throw in [ChainedPacketQueue].
 */
class ChainedPacketQueueLimits(
    maximumPackets: Int,
    maximumBytes: Int,
) {
    // Normalized in property initializers rather than stored raw: `coerceIn` on the packet bound
    // and `coerceAtLeast` on the byte bound. The two ends are handled differently on purpose. A
    // zero-capacity queue is representable (enqueue reports it as RefusedNoCapacity), so the
    // floor must be zero, not one. Callers keep the iOS constructor shape
    // `ChainedPacketQueueLimits(maximumPackets, maximumBytes)` while the type guarantees a
    // value of it is already clamped.
    val maximumPackets: Int = maximumPackets.coerceIn(0, ENGINE_QUEUE_DEPTH)
    val maximumBytes: Int = maximumBytes.coerceAtLeast(0)

    override fun equals(other: Any?): Boolean =
        other is ChainedPacketQueueLimits &&
            other.maximumPackets == maximumPackets &&
            other.maximumBytes == maximumBytes

    override fun hashCode(): Int = 31 * maximumPackets + maximumBytes

    override fun toString(): String =
        "ChainedPacketQueueLimits(maximumPackets=$maximumPackets, maximumBytes=$maximumBytes)"

    companion object {
        /**
         * The engine's own queue depth, which is the ceiling for this one.
         *
         * Not a preference. A queue deeper than the thing it feeds cannot raise throughput — the
         * engine drains at its own rate regardless — so every extra slot only adds latency and
         * resident memory. Matching rather than exceeding also keeps the failure legible: when
         * packets are being shed, there is one queue to look at, not a shallow one hiding behind
         * a deep one. Mirrors iOS `ChainedPacketQueueLimits.engineQueueDepth`, which reads the
         * same value from the vendored BoringTun `MAX_QUEUE_DEPTH`.
         */
        const val ENGINE_QUEUE_DEPTH: Int = 256

        /**
         * Destination-buffer size for every engine call, in bytes — the ENCRYPTED datagram
         * ceiling.
         *
         * Mirrors iOS `ChainedDataPathBuffers`, which sizes both `toNetwork` and `toTunnel` at
         * this value rather than at the MTU: the engine's buffer discipline names this capacity
         * as the one rule that makes its internal panic paths unreachable, so undersizing a
         * buffer is an engine-contract violation, not a smaller allocation.
         *
         * 1500 (`LAVA_WG_MAX_IP_PACKET`) + 32 (`dataOverhead`). This is NOT the bound for a
         * queued packet: the queue holds an INNER IP packet, and the engine caps an `encapsulate`
         * source at [MAXIMUM_IP_PACKET_BYTE_COUNT] BEFORE adding the 32 bytes of WireGuard
         * transport overhead. Conflating the two would admit 1501-1532-byte inner packets that
         * the engine then rejects as `PacketTooLarge`.
         */
        const val MAXIMUM_DATAGRAM_BYTE_COUNT: Int = 1532

        /**
         * The engine ABI's cap on an `encapsulate` source, in bytes — the INNER IP packet
         * ceiling (`LAVA_WG_MAX_IP_PACKET`).
         *
         * The queue holds inner IP packets, so this is the honest maximum for an MTU-derived
         * byte budget. The 32 bytes of transport overhead counted by
         * [MAXIMUM_DATAGRAM_BYTE_COUNT] are added by the engine AFTER it accepts the inner
         * packet and must not be counted here.
         */
        const val MAXIMUM_IP_PACKET_BYTE_COUNT: Int = 1500

        /**
         * The largest configured MTU this queue will honor.
         *
         * Bounded by the engine's inner-IP cap, not by the encrypted-datagram size: 65535 was
         * the first answer and it is far too generous (256 x 65535 is ~16 MB per direction, over
         * 32 MB for both, inside a process already resident with the engine and its session
         * state), and anything above [MAXIMUM_IP_PACKET_BYTE_COUNT] describes a packet the engine
         * cannot encapsulate at all. A "bound" larger than the engine's own limit is a bound in
         * name only.
         */
        const val MAXIMUM_PLAUSIBLE_MTU: Int = MAXIMUM_IP_PACKET_BYTE_COUNT

        /**
         * Limits for one direction of a chained tunnel, or `null` when [mtu] is not an MTU.
         *
         * [mtu] is the chained plan's MTU (1280, the IPv6 minimum). The byte budget is the depth
         * times the MTU, so the two bounds agree instead of one silently dominating: at 1280 that
         * is 320 KB per direction, 640 KB across both.
         *
         * Input below the chained plan's MTU is REFUSED rather than clamped. Clamping fabricated
         * an apparently valid 256-byte queue that refuses every ordinary packet as oversized — a
         * caller bug presenting as a working queue that drops all traffic, which is the hardest
         * shape to diagnose. Returning `null` makes the caller's mistake its own. Above the
         * engine's inner-IP ceiling the value is clamped, because the engine cannot encapsulate a
         * larger inner packet, so a larger budget buys nothing. The configuration boundary
         * refuses such an MTU outright, so this clamp is for direct construction only.
         */
        fun forChainedTunnel(mtu: Int): ChainedPacketQueueLimits? {
            if (mtu <= 0) return null
            if (mtu < ChainedRoutePlan.MINIMUM_IPV6_LINK_MTU) return null
            val clampedMTU = mtu.coerceAtMost(MAXIMUM_PLAUSIBLE_MTU)
            return ChainedPacketQueueLimits(
                maximumPackets = ENGINE_QUEUE_DEPTH,
                maximumBytes = ENGINE_QUEUE_DEPTH * clampedMTU,
            )
        }
    }
}

/**
 * A bounded, back-pressured FIFO of queued outbound packets. Pure in-memory; no threads, no
 * clock, no I/O — the session owner drives it from its single engine queue.
 *
 * ## The failure this exists to prevent
 *
 * Unbounded buffering in the tunnel is the documented SpeedTest kill mode: a fast downlink
 * offers packets faster than the engine can encapsulate them, the queue grows to absorb the
 * difference, and the process is killed for exceeding its memory limit. Bounding the queue
 * converts that into packet loss, which every transport above it already handles.
 *
 * ## Why the OLDEST packet is evicted, where the engine drops the newest
 *
 * BoringTun's own queue refuses the incoming packet once `MAX_QUEUE_DEPTH` is reached. That is
 * right for its queue, which holds traffic waiting for a handshake to complete — the backlog is
 * the point, and it drains in order the moment a session exists.
 *
 * This queue is a different thing: it absorbs a rate mismatch on an established session, and
 * under sustained overload it is always full. A packet that has sat here longer than the path
 * RTT is already useless — TCP has retransmitted it, so delivering it spends bandwidth on
 * something the receiver will discard as a duplicate. Evicting the oldest therefore delivers
 * strictly more useful bytes than refusing the newest, and it bounds queueing delay instead of
 * pinning it at the full depth. The cost is reordering, which is real and accepted: TCP recovers
 * by fast retransmit, and a queue that is overflowing has already caused loss by definition.
 *
 * ## Memory bound
 *
 * Storage is a fixed ring of [ChainedPacketQueueLimits.maximumPackets] slots allocated once in
 * the constructor and never resized, so the resident bound is structural, not merely counted.
 * At the shipped chained MTU of 1280 the byte budget is 256 x 1280 = 327,680 bytes per
 * direction, and both directions together stay well under 1 MB against a tunnel process that
 * already holds the engine and its session state. A vacated slot is reset to `null` on eviction
 * and dequeue so the ring cannot keep a shed packet's backing buffer resident after that packet
 * has been accounted as gone — the accounting-vs-residency gap this type exists to close.
 */
class ChainedPacketQueue(
    val limits: ChainedPacketQueueLimits,
) {
    /**
     * A fixed ring, not a growing list.
     *
     * Removing the head of an `ArrayList` shifts every remaining element, so on the eviction path
     * — which runs on EVERY arrival once the queue is full, and a full queue is the steady state
     * this type exists for — each packet would cost O(depth). The capacity is known at
     * construction and bounded (at most [ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH]), so the
     * ring is allocated once and never resized: enqueue, evict and dequeue are all O(1) with no
     * allocation beyond the one compact copy per admitted packet.
     */
    private val storage: Array<ByteArray?> = arrayOfNulls(limits.maximumPackets)

    /** Index of the oldest packet. Meaningless when [occupied] is zero. */
    private var head = 0
    private var occupied = 0
    private var queuedBytes = 0

    /**
     * Packets evicted or refused since this queue was created. Diagnostics only — the tunnel
     * reports shedding rather than inferring it from a throughput dip.
     *
     * A `Long` because the counter is CUMULATIVE and [removeAll] deliberately does not reset it:
     * at ordinary tunnel rates no 32-bit counter survives even a short session (a 2.5 Gbps
     * downlink passes 2^31 bytes in well under a minute), after which an `Int` would wrap
     * negative and the diagnosis would read as less shedding the worse the pressure became.
     */
    var shedPacketCount: Long = 0
        private set

    /** Bytes evicted or refused, kept in lockstep with [shedPacketCount] and `Long` for the same reason. */
    var shedByteCount: Long = 0
        private set

    /** Packets currently held. */
    val count: Int get() = occupied

    /** Bytes currently held, the value the byte bound is enforced against. */
    val byteCount: Int get() = queuedBytes

    /** Whether nothing is held. */
    val isEmpty: Boolean get() = occupied == 0

    /**
     * Drops the oldest packet, returning it. The caller has already checked `occupied > 0`.
     *
     * The vacated slot is reset to `null` rather than left holding the packet. The ring outlives
     * its entries, so a stale slot would keep that buffer alive after the packet was accounted as
     * gone — [queuedBytes] would fall while the memory did not, which is exactly the
     * accounting-vs-residency gap this type exists to close.
     */
    private fun dropOldest(): ByteArray {
        val packet = requireNotNull(storage[head])
        storage[head] = null
        head = (head + 1) % storage.size
        occupied -= 1
        queuedBytes -= packet.size
        return packet
    }

    /**
     * Offers a packet, evicting the oldest as needed to stay inside both bounds.
     *
     * The packet is STORED COMPACT, copied once into a `ByteArray` sized to it. A caller handing
     * over a large allocation that happens to hold a small packet cannot pin the large buffer:
     * this class holds its own copy, so the accounting measures what is resident. One copy per
     * packet, bounded by the byte budget enforced below.
     */
    fun enqueue(packet: ByteArray): ChainedQueueAdmission {
        if (packet.isEmpty()) {
            shedPacketCount += 1
            return ChainedQueueAdmission.RefusedEmpty
        }
        // The engine's per-packet inner-IP ceiling is ABSOLUTE, independent of the queue budget:
        // lava_wg_encapsulate returns PACKET_TOO_LARGE for anything larger, and the reconnect
        // policy treats that as a terminal caller-contract violation. Shed it here so one jumbo
        // frame cannot disable chained mode.
        if (packet.size > ChainedPacketQueueLimits.MAXIMUM_IP_PACKET_BYTE_COUNT) {
            shedPacketCount += 1
            shedByteCount += packet.size
            return ChainedQueueAdmission.RefusedOversized(
                packet.size,
                ChainedPacketQueueLimits.MAXIMUM_IP_PACKET_BYTE_COUNT,
            )
        }
        // Two different refusals, kept apart because the device log has to be able to tell them
        // apart. A packet that fits the byte budget but arrives at a queue with no packet
        // capacity is not oversized, and reporting it as `refused-oversized` sends anyone reading
        // the log looking for a jumbo frame that does not exist.
        if (limits.maximumPackets <= 0) {
            shedPacketCount += 1
            shedByteCount += packet.size
            return ChainedQueueAdmission.RefusedNoCapacity
        }
        if (packet.size > limits.maximumBytes) {
            shedPacketCount += 1
            shedByteCount += packet.size
            return ChainedQueueAdmission.RefusedOversized(packet.size, limits.maximumBytes)
        }

        var evictedPackets = 0
        var evictedBytes = 0
        // Both bounds, and the packet bound leaves room for the arrival rather than merely being
        // satisfied — otherwise a full queue admits one more and exceeds its own limit.
        //
        // The byte comparison is evaluated in `Long`. In `Int`, a directly-constructed limit with
        // `maximumBytes` near `Int.MAX_VALUE` would wrap negative once `queuedBytes + packet.size`
        // crossed the top of the range, and the check would ADMIT a packet past its budget.
        // [forChainedTunnel] cannot produce such a limit, but the constructor accepts one.
        while (occupied > 0 &&
            (occupied + 1 > limits.maximumPackets ||
                queuedBytes.toLong() + packet.size.toLong() > limits.maximumBytes.toLong())
        ) {
            val evicted = dropOldest()
            evictedPackets += 1
            evictedBytes += evicted.size
        }

        val owned = packet.copyOf()
        storage[(head + occupied) % storage.size] = owned
        occupied += 1
        queuedBytes += owned.size
        shedPacketCount += evictedPackets
        shedByteCount += evictedBytes

        return if (evictedPackets == 0) {
            ChainedQueueAdmission.Admitted
        } else {
            ChainedQueueAdmission.AdmittedAfterEvicting(evictedPackets, evictedBytes)
        }
    }

    /** Removes and returns the oldest packet, or `null` when empty. */
    fun dequeue(): ByteArray? {
        if (occupied == 0) return null
        return dropOldest()
    }

    /**
     * Discards everything without counting it as shed.
     *
     * Used when a session ends: those packets were not dropped under pressure, they belong to a
     * tunnel that no longer exists. Counting them would make a reconnect look like congestion in
     * the diagnostics. Assigning a fresh array rather than clearing slots one by one is correct
     * BY CONSTRUCTION — a loop that misses a slot leaves a buffer resident while the queue
     * reports empty, and no behavioural test can see that difference. One allocation, on session
     * end, which is not a path under time pressure.
     */
    fun removeAll() {
        storage.fill(null)
        head = 0
        occupied = 0
        queuedBytes = 0
    }
}
