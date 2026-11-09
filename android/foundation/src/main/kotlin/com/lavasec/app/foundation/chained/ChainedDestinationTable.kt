package com.lavasec.app.foundation.chained

/**
 * What one in-tunnel destination looks like right now, as counted by the data path.
 *
 * Deliberately raw: counts and instants, no verdict. [ChainedDestinationTable] observes packets
 * and owns no thresholds; [ChainedDestinationReachabilityPolicy] owns the thresholds and observes
 * nothing. Splitting them that way is what lets the judgement have behavioural tests without a
 * data path underneath it. Mirrors iOS `ChainedDestinationObservation` (`Sources/LavaSecKit`).
 */
data class ChainedDestinationObservation(
    /** Packets sent INTO the tunnel addressed to this destination. */
    val sentPacketCount: Long,
    /** Packets delivered OUT of the tunnel sourced from this destination. */
    val receivedPacketCount: Long,
    /**
     * When the currently-open unanswered window was anchored, or `null` when no window is open
     * because a receipt closed it. Re-anchored — not merely extended — when a send follows a lapse
     * in demand; see [ChainedDestinationReachabilityPolicy.demandContinuitySeconds].
     */
    val firstUnansweredSendAtSeconds: Int?,
    /** The most recent send to this destination, or `null` if it has never been sent to. */
    val lastSendAtSeconds: Int?,
    /**
     * Sends since the CURRENT window was anchored — not the lifetime total. Reset by a receipt and
     * by a re-anchor, so it measures the demand behind THIS wait. It is what separates a
     * retransmitting connection from a single stray packet.
     */
    val sendsInCurrentWindow: Long,
)

/**
 * What this layer is willing to say about ONE destination behind the chain.
 *
 * Four cases rather than three, because "we are sending and nothing has come back yet" is neither
 * idle nor answering, and calling it either would be a lie in a type whose entire purpose is to
 * stop the tunnel claiming health it cannot see. Mirrors iOS `ChainedDestinationReachability`.
 */
sealed interface ChainedDestinationReachability {
    /** Nobody is asking. Either nothing was ever sent here, or the asking lapsed. */
    data object Idle : ChainedDestinationReachability

    /** This destination has answered since the last time we started waiting on it. */
    data object Answering : ChainedDestinationReachability

    /** Sustained demand, nothing back yet, still inside the threshold. Not a complaint. */
    data class Awaiting(val seconds: Int) : ChainedDestinationReachability

    /** Sustained demand at or past the threshold and not one packet back. The reportable state. */
    data class Unanswered(val seconds: Int) : ChainedDestinationReachability
}

/**
 * Decides whether ONE destination behind a chained tunnel is answering.
 *
 * REPORT only: it never gates establishment, declares an outage, or surrenders. Ported from iOS
 * `ChainedDestinationReachabilityPolicy` (`Sources/LavaSecKit`) because
 * [ChainedDestinationTable]'s window re-anchoring and qualification are defined in terms of these
 * three constants; keeping one canonical set here means the table and the verdict cannot disagree
 * about what "sustained" and "lapsed" mean.
 */
object ChainedDestinationReachabilityPolicy {
    /**
     * How long a destination under sustained demand may answer nothing before it is reported.
     *
     * Reused rather than tuned down: a chain that just lost forwarding may still recover on the
     * engine's own fresh handshake at `KEEPALIVE_TIMEOUT + REKEY_TIMEOUT` plus one lost-datagram
     * allowance, and a floor under that reports a recovery already in flight as a fault.
     */
    const val unansweredThresholdSeconds: Int = 21

    /**
     * How long the user may go without sending to a destination before its window LAPSES.
     *
     * Well under [unansweredThresholdSeconds] so a lapse always resets the window before it could
     * report, and comfortably above a stalled connection's retransmit backoff. Sustained demand
     * keeps the window alive; a pause of this length lapses it.
     */
    const val demandContinuitySeconds: Int = 10

    /**
     * How many sends inside the current window make it SUSTAINED demand rather than a stray packet.
     *
     * A QUALIFIED window — past the threshold with at least this many sends behind it — is neither
     * lapsed by this policy nor re-anchored by [ChainedDestinationTable], so it stays reportable
     * until a receipt clears it. Without this, a stalled TCP connect's ever-longer backoff gaps
     * were read as "the user stopped asking" and a permanent failure reported once and then
     * vanished.
     */
    const val sustainedSendFloor: Long = 3

    /**
     * Judge one destination.
     *
     * The send floor applies ONLY to the qualified case. Below the threshold the lapse still
     * governs, so a stray packet followed by silence is idle within [demandContinuitySeconds] and
     * can never become a complaint.
     */
    fun verdict(
        observation: ChainedDestinationObservation,
        nowSeconds: Int,
    ): ChainedDestinationReachability {
        val lastSend = observation.lastSendAtSeconds ?: return ChainedDestinationReachability.Idle
        // A receipt closed the window. Nothing to say until the next send re-opens one.
        val since = observation.firstUnansweredSendAtSeconds
            ?: return ChainedDestinationReachability.Answering
        val waited = maxOf(0, nowSeconds - since)
        // QUALIFIED: past the threshold with sustained demand behind it. Reported regardless of the
        // lapse, because a connection in TCP backoff has gaps longer than demandContinuitySeconds by
        // design. Only a receipt clears this.
        if (waited >= unansweredThresholdSeconds &&
            observation.sendsInCurrentWindow >= sustainedSendFloor
        ) {
            return ChainedDestinationReachability.Unanswered(waited)
        }
        // The user stopped asking, and this window never qualified. Silence nobody is waiting on is
        // not a fault. The window is left OPEN rather than cleared, so the next send can re-anchor
        // to itself instead of to a question asked minutes ago.
        if (nowSeconds - lastSend >= demandContinuitySeconds) {
            return ChainedDestinationReachability.Idle
        }
        return if (waited >= unansweredThresholdSeconds) {
            ChainedDestinationReachability.Unanswered(waited)
        } else {
            ChainedDestinationReachability.Awaiting(waited)
        }
    }
}

/**
 * Per-destination send/receipt accounting for one chained session, bounded and allocation-free on
 * the packet path.
 *
 * Ported from iOS `ChainedDestinationTable`. It exists because a split-tunnel chain's aggregate
 * counters cannot distinguish "the chain is up and carrying your traffic" from "the chain is up and
 * the host you want is silent" — see [ChainedDestinationReachabilityPolicy].
 *
 * ## IPv4 only, by construction rather than by omission
 *
 * The key is a 32-bit IPv4 address (`Long`, stored in `0..0xFFFFFFFF`) because while chained there
 * is no other kind of in-tunnel destination: [ChainedOutboundPacketClassifier] drops every outbound
 * IPv6 packet and chained mode claims `::/0` deliberately so those packets are drawn in and
 * blackholed rather than escaping around the tunnel. A v6 row could therefore only ever record
 * sends this process itself discarded, which is not a statement about reachability.
 *
 * ## Memory
 *
 * Fixed capacity, no per-packet allocation, no dictionary. The storage is a flat list sized once at
 * init and never grown; a full table overwrites in place rather than appending, so after warm-up
 * the hot path performs zero heap traffic.
 *
 * This is a mutable class rather than the iOS value type because the data path owns exactly one
 * instance and mutates it per packet; the semantics are identical.
 */
class ChainedDestinationTable {
    /**
     * One destination's raw accounting. Trivial; the list holds these by reference.
     */
    private class Entry(
        var address: Long,
        var sentPacketCount: Long = 0,
        var receivedPacketCount: Long = 0,
        var firstUnansweredSendAtSeconds: Int? = null,
        var sendsInCurrentWindow: Long = 0,
        var lastSendAtSeconds: Int = 0,
        var lastActivityOrder: Long = 0,
    )

    private val entries: MutableList<Entry> = ArrayList(CAPACITY)

    /**
     * Ticks once per recorded packet, so [Entry.lastActivityOrder] is total and tie-free.
     *
     * It must be activity rather than receipt: keying on receipts would make a silent destination
     * the first evicted, which is the one row this table is sized to keep. A per-packet counter has
     * no ties, unlike the one-second timestamp it replaced — with second granularity a burst made
     * every row compare equally old and the youngest was evicted anyway.
     */
    private var activityCounter: Long = 0

    /**
     * Index of the entry touched last. The packet path arrives in bursts to one destination, so
     * checking this before scanning turns the common case into a single comparison. A stale hint is
     * harmless — it is only ever used after its address is confirmed to match.
     */
    private var hotIndex: Int = 0

    /**
     * Records one packet sent into the tunnel toward [address].
     *
     * Opens an unanswered window if none is open. If the previous send is older than
     * [ChainedDestinationReachabilityPolicy.demandContinuitySeconds] the window is RE-ANCHORED to
     * this send rather than extended: the user asked, stopped, and asked again, and the second
     * question must not inherit the age of the first.
     *
     * UNLESS THE WINDOW HAS QUALIFIED — past the threshold with
     * [ChainedDestinationReachabilityPolicy.sustainedSendFloor] sends behind it. Then the gaps ARE
     * the failure (TCP backoff), not a lapse in asking, and re-anchoring on them is what let a
     * permanent failure disappear from the signal built to report it.
     */
    fun recordSend(address: Long, nowSeconds: Int) {
        val index = indexEnsuringEntry(address, nowSeconds)
        val entry = entries[index]
        entry.sentPacketCount++
        val anchored = entry.firstUnansweredSendAtSeconds
        val qualified = anchored != null &&
            nowSeconds - anchored >= ChainedDestinationReachabilityPolicy.unansweredThresholdSeconds &&
            entry.sendsInCurrentWindow >= ChainedDestinationReachabilityPolicy.sustainedSendFloor
        val lapsed = nowSeconds - entry.lastSendAtSeconds >=
            ChainedDestinationReachabilityPolicy.demandContinuitySeconds
        if (anchored == null || (lapsed && !qualified)) {
            entry.firstUnansweredSendAtSeconds = nowSeconds
            entry.sendsInCurrentWindow = 1
        } else {
            entry.sendsInCurrentWindow++
        }
        entry.lastSendAtSeconds = nowSeconds
        activityCounter++
        entry.lastActivityOrder = activityCounter
    }

    /**
     * Records one packet delivered out of the tunnel from [address], closing its window.
     *
     * An address with no entry is IGNORED rather than admitted. The table's population is driven by
     * demand — a destination nobody asked about has no window to close, and admitting it would let
     * unsolicited inbound traffic evict the row the user is waiting on, which is the one eviction
     * [CAPACITY] is sized to prevent.
     */
    fun recordReceipt(address: Long, nowSeconds: Int) {
        val index = indexOfExistingEntry(address) ?: return
        val entry = entries[index]
        entry.receivedPacketCount++
        entry.firstUnansweredSendAtSeconds = null
        entry.sendsInCurrentWindow = 0
        activityCounter++
        entry.lastActivityOrder = activityCounter
        hotIndex = index
    }

    /** This destination's raw accounting, or `null` if it is not tracked. */
    fun observationFor(address: Long): ChainedDestinationObservation? {
        val index = indexOfExistingEntry(address) ?: return null
        return observationOf(entries[index])
    }

    /**
     * Every tracked destination, in no meaningful order.
     *
     * The one allocating member, and deliberately so: it is read on the outage driver's tick, not
     * on the packet path, and at most [CAPACITY] elements.
     */
    fun destinations(): List<Pair<Long, ChainedDestinationObservation>> =
        entries.map { it.address to observationOf(it) }

    /** How many destinations are tracked. Diagnostics and tests only. */
    val trackedCount: Int get() = entries.size

    private fun observationOf(entry: Entry): ChainedDestinationObservation =
        ChainedDestinationObservation(
            sentPacketCount = entry.sentPacketCount,
            receivedPacketCount = entry.receivedPacketCount,
            firstUnansweredSendAtSeconds = entry.firstUnansweredSendAtSeconds,
            lastSendAtSeconds = entry.lastSendAtSeconds,
            sendsInCurrentWindow = entry.sendsInCurrentWindow,
        )

    private fun indexOfExistingEntry(address: Long): Int? {
        if (hotIndex < entries.size && entries[hotIndex].address == address) return hotIndex
        return entries.indexOfFirst { it.address == address }.takeIf { it >= 0 }
    }

    /**
     * The index for [address], creating or REPLACING an entry when it is not tracked yet.
     *
     * Replacement overwrites the least recently active slot in place; the list is never reordered
     * and never grown past [CAPACITY], so no packet allocates. Ties go to the lowest index; with
     * [Entry.lastActivityOrder] being a per-packet counter there are no ties to break, but the rule
     * is stated so a future coarser key cannot reintroduce them silently.
     */
    private fun indexEnsuringEntry(address: Long, nowSeconds: Int): Int {
        val existing = indexOfExistingEntry(address)
        if (existing != null) {
            hotIndex = existing
            return existing
        }
        if (entries.size < CAPACITY) {
            entries += Entry(
                address = address,
                lastSendAtSeconds = nowSeconds,
                lastActivityOrder = activityCounter + 1,
            )
            hotIndex = entries.size - 1
            return hotIndex
        }
        var oldest = 0
        for (index in 1 until entries.size) {
            if (entries[index].lastActivityOrder < entries[oldest].lastActivityOrder) {
                oldest = index
            }
        }
        // Reuse the evicted slot IN PLACE: reset every field so no stale accounting survives, and
        // allocate nothing on the steady-state packet path under destination churn.
        val reused = entries[oldest]
        reused.address = address
        reused.sentPacketCount = 0
        reused.receivedPacketCount = 0
        reused.firstUnansweredSendAtSeconds = null
        reused.sendsInCurrentWindow = 0
        reused.lastSendAtSeconds = nowSeconds
        reused.lastActivityOrder = activityCounter + 1
        hotIndex = oldest
        return oldest
    }

    companion object {
        /**
         * How many destinations are tracked at once.
         *
         * Chosen for EVICTION, not memory: an entry is an address, two counters and three instants,
         * so the whole table is well under the process memory budget. The failure this type reports
         * is "the one host you are waiting on is silent", so the only harmful eviction is that host
         * crowded out by other in-range chatter. A split tunnel's claimed range is typically a whole
         * tailnet — peer nodes, the resolver, discovery and multicast noise — and the entry the user
         * is actively sending to is by definition the youngest by activity, so it is the last thing
         * evicted rather than the first.
         *
         * Unlike a fail-closed table, eviction here loses a REPORT, gates nothing and surrenders
         * nothing, so the honest bias is generous rather than tight.
         */
        const val CAPACITY: Int = 32
    }
}
