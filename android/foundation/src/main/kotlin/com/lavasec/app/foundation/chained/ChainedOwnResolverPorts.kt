package com.lavasec.app.foundation.chained

/**
 * The production [ChainedOwnResolverPortRegistry]: the local source ports this process holds for its
 * own tunnel-carried T0 resolver queries.
 *
 * The classifier's carve-out is keyed on the LOCAL SOURCE PORT, never on the destination address,
 * so the egress must mark each query's ephemeral port here before it sends and only release it after
 * the reply is matched or the query times out. A released port stays "recently claimed" for a grace
 * window, so a straggling reply that classifies after the release is still recognized as ours and
 * cannot be served back into the resolver (`ChainedOutboundPacketClassifier`'s
 * `wasRecentlyClaimed` arm).
 *
 * Bounded on purpose: the UDP port space is 65536 wide, but a bounded in-flight query count is the
 * real ceiling. Allocation scans for a port that is neither claimed nor within its grace window, so
 * a fresh query never reuses a tuple whose late reply could be mis-matched.
 *
 * The clock is injected and monotonic (`SystemClock.elapsedRealtime` in production); a wall clock
 * would make the grace window jump under an NTP sync.
 */
class ChainedOwnResolverPorts(
    private val nowMillis: () -> Long,
    private val firstPort: Int = FIRST_EPHEMERAL_PORT,
    private val lastPort: Int = LAST_EPHEMERAL_PORT,
    private val maximumConcurrentClaims: Int = DEFAULT_MAXIMUM_CONCURRENT_CLAIMS,
    private val recentGraceMillis: Long = DEFAULT_RECENT_GRACE_MILLIS,
) : ChainedOwnResolverPortRegistry {

    init {
        require(firstPort in 1..lastPort) { "invalid ephemeral port range" }
        require(lastPort <= 65535) { "invalid ephemeral port range" }
        require(maximumConcurrentClaims > 0) { "maximum concurrent claims must be positive" }
        require(recentGraceMillis >= 0) { "recent grace must be non-negative" }
    }

    private val claimed = HashMap<Int, Int>()
    private val recentlyReleased = HashMap<Int, Long>()
    private var nextCandidate = firstPort

    /** One query's source port, or `null` when the in-flight ceiling is reached. */
    @Synchronized
    fun claimForUdp(): Int? {
        if (claimed.size >= maximumConcurrentClaims) return null
        expireRecent()
        var candidate = nextCandidate
        repeat(ephemeralPortCount()) {
            if (candidate !in claimed && candidate !in recentlyReleased) {
                claimed[candidate] = UDP_PROTOCOL
                nextCandidate = if (candidate >= lastPort) firstPort else candidate + 1
                return candidate
            }
            candidate = if (candidate >= lastPort) firstPort else candidate + 1
        }
        return null
    }

    /** Releases [sourcePort]; it remains "recently claimed" for the grace window. */
    @Synchronized
    fun release(sourcePort: Int) {
        if (claimed.remove(sourcePort) != null) {
            recentlyReleased[sourcePort] = nowMillis()
        }
    }

    /** The number of ports currently claimed, for the in-flight ceiling. */
    @Synchronized
    fun claimedCount(): Int = claimed.size

    @Synchronized
    override fun claims(sourcePort: Int, protocolNumber: Int): Boolean =
        claimed[sourcePort] == protocolNumber

    @Synchronized
    override fun wasRecentlyClaimed(sourcePort: Int, protocolNumber: Int): Boolean {
        expireRecent()
        return recentlyReleased.containsKey(sourcePort) && protocolNumber == UDP_PROTOCOL
    }

    private fun expireRecent() {
        if (recentlyReleased.isEmpty()) return
        val cutoff = nowMillis() - recentGraceMillis
        val iterator = recentlyReleased.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
    }

    private fun ephemeralPortCount(): Int = lastPort - firstPort + 1

    private companion object {
        const val UDP_PROTOCOL = 17

        /** The first IANA ephemeral port. */
        const val FIRST_EPHEMERAL_PORT = 49152

        /** The last IANA ephemeral port. */
        const val LAST_EPHEMERAL_PORT = 65535

        /**
         * The in-flight query ceiling. A few dozen concurrent lookups is far beyond what a client
         * resolver does, and bounding the map keeps residency flat under a query flood.
         */
        const val DEFAULT_MAXIMUM_CONCURRENT_CLAIMS = 64

        /**
         * How long a released port stays recognized as ours. Longer than any plausible
         * query-to-late-reply gap, shorter than the port space's reuse horizon.
         */
        const val DEFAULT_RECENT_GRACE_MILLIS = 3_000L
    }
}
