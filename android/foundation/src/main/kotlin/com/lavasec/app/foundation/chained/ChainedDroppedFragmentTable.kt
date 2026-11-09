package com.lavasec.app.foundation.chained

/**
 * What IPv4 reassembly uses to decide that two fragments belong to the same datagram.
 *
 * Source, destination, protocol and identification — and deliberately NOT the ports, because a
 * non-first fragment carries no transport header. That absence is the whole reason this type exists
 * and also its one weakness; see [ChainedDroppedFragmentTable]. Mirrors iOS
 * `ChainedFragmentIdentity`.
 */
data class ChainedFragmentIdentity(
    val source: Long,
    val destination: Long,
    val identification: Int,
    val protocolNumber: Int,
)

/**
 * The datagrams whose FIRST fragment was dropped as unfilterable DNS, so their continuations can be
 * dropped too.
 *
 * Ported from iOS `ChainedDroppedFragmentTable`.
 *
 * ## The leak this closes
 *
 * Dropping fragment zero of a DNS query stops the query from working, but it does NOT stop it from
 * being READ: the tails carry the DNS message's own bytes in plaintext, and an observer with all of
 * them can concatenate the QNAME without any transport header or successful reassembly. A first
 * fragment may legally carry as little as the UDP header itself, leaving the ENTIRE question in the
 * fragments that were forwarded. "Killing the head kills the query" was true; "and denies the
 * observer the part that identifies it" was not, and that half is what the fail-closed DNS rule is
 * about.
 *
 * ## Why a deny-list rather than an allow-list
 *
 * The two shapes fail in opposite directions. Remembering PERMITTED heads and dropping any tail
 * without one fails closed on a miss — but every fragmented flow on the device competes for the
 * table, so under ordinary fragmented traffic entries are evicted between a head and its tails and
 * legitimate datagrams are destroyed continuously. Remembering DROPPED DNS heads inverts both: a
 * miss forwards, a hit drops. The bound that makes it safe is that a miss requires [CAPACITY] other
 * DNS fragment heads to be dropped between this head and its own tails, and the kernel emits one
 * datagram's fragments back to back.
 *
 * ## The collision, stated rather than dismissed
 *
 * The key cannot include ports, so two datagrams to the same destination with the same
 * identification are one identity here. A permitted UDP flow and a DNS query sharing an
 * identification collide, and the permitted flow's tails are dropped — the fail-CLOSED direction,
 * which is the other reason the deny-list is the right way round.
 */
class ChainedDroppedFragmentTable {
    /**
     * Oldest first. Not a ring: [remember] has to move an existing entry to the young end, which a
     * ring cannot do without shifting anyway. Bounded at [CAPACITY], and only a dropped DNS
     * fragment head reaches it, so this is not a per-packet path.
     */
    private val entries: MutableList<ChainedFragmentIdentity> = ArrayList()

    /**
     * Records that this datagram's head was dropped, or REFRESHES it if already known.
     *
     * Refreshing is what makes the bound in this type's documentation true: an entry left at its
     * original age would be evicted by one further dropped head, so the stated "32 intervening
     * heads" would really be one in exactly the shape a retransmitting resolver produces. Moving
     * rather than appending also keeps a head repeated a thousand times to a single entry.
     */
    fun remember(identity: ChainedFragmentIdentity) {
        entries.remove(identity)
        entries += identity
        if (entries.size > CAPACITY) {
            entries.subList(0, entries.size - CAPACITY).clear()
        }
    }

    /** Whether this datagram's head was dropped. */
    fun contains(identity: ChainedFragmentIdentity): Boolean = entries.contains(identity)

    /**
     * Releases an identity because a LATER head with the same tuple was FORWARDED.
     *
     * A permitted datagram reusing a denied tuple would otherwise have its head forwarded and its
     * tails dropped, recurring on every identification wrap for as long as the entry lived. A
     * forwarded head is the classifier's own statement that the tuple's current datagram is
     * permitted, and the table must not contradict it. Invoked only when the head has continuations
     * to protect and the caller can assert release order.
     */
    fun forget(identity: ChainedFragmentIdentity) {
        entries.remove(identity)
    }

    /** Identities currently remembered. Diagnostics and tests only. */
    val rememberedCount: Int get() = entries.size

    companion object {
        /**
         * How many dropped heads are remembered.
         *
         * Small on purpose. Every entry is one datagram whose tails are still in flight, and
         * fragments of one datagram are emitted consecutively — so the table needs to span
         * concurrent fragmented DNS queries, not time. 32 is far past any plausible count of those.
         */
        const val CAPACITY: Int = 32
    }
}
