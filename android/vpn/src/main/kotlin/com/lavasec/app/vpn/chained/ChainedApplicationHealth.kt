package com.lavasec.app.vpn.chained

/** Engine-queue confined application evidence; transport keepalives are deliberately insufficient. */
internal class ChainedApplicationHealth(private val fullTunnel: Boolean) {
    var dnsEpoch = 0L
        private set
    var dnsHeld = false
        private set
    var egressHeld = false
        private set
    var dnsDeclarations = 0
        private set
    var egressDeclarations = 0
        private set
    private var firstDnsFailure: Int? = null
    private val failedNames = mutableSetOf<String>()
    private var firstDemand: Int? = null
    private var lastDemand: Int? = null
    private var forwardingGeneration: Long? = null
    private var forwardedBytes = 0L

    val unansweredDestinationCount: Int get() = failedNames.size

    val holdsOutage: Boolean get() = dnsHeld || egressHeld

    fun clearDnsWindow() {
        dnsEpoch++
        firstDnsFailure = null
        failedNames.clear()
    }

    /** A current resolver reply proves reachability even when its DNS result is an error. */
    fun dnsAnswered(epoch: Long): Boolean {
        if (epoch != dnsEpoch) return false
        val held = dnsHeld
        dnsHeld = false
        clearDnsWindow()
        return held
    }

    fun dnsUnanswered(epoch: Long, nameKey: String, now: Int, canDeclare: Boolean): Boolean {
        if (epoch != dnsEpoch || !canDeclare || dnsDeclarations >= MAX_DECLARATIONS) return false
        if (firstDnsFailure == null) firstDnsFailure = now
        if (failedNames.size < 2) failedNames.add(nameKey)
        if (now - requireNotNull(firstDnsFailure) < STALL_SECONDS || failedNames.size < 2) return false
        dnsDeclarations++
        dnsHeld = true
        return true
    }

    /** Only actual application bytes may release an egress-dead hold; a new empty session cannot. */
    fun sample(sample: ChainedDriverLivenessSample, now: Int, canDeclare: Boolean): Boolean {
        val newGeneration = forwardingGeneration != sample.forwardingGeneration
        val realProgress = if (newGeneration) sample.forwardedNonDnsBytes > 0
            else sample.forwardedNonDnsBytes > forwardedBytes
        forwardingGeneration = sample.forwardingGeneration
        forwardedBytes = sample.forwardedNonDnsBytes
        if (newGeneration || realProgress) clearDemand()
        if (realProgress) egressHeld = false
        if (lastDemand?.let { now - it >= DEMAND_CONTINUITY_SECONDS } == true) clearDemand()
        if (sample.sawObligingNonDnsSend) {
            if (firstDemand == null) firstDemand = now
            lastDemand = now
        }
        if (!fullTunnel || !canDeclare || egressDeclarations >= MAX_DECLARATIONS) return false
        val first = firstDemand ?: return false
        if (now - first < STALL_SECONDS) return false
        egressDeclarations++
        egressHeld = true
        return true
    }

    fun resetWindow(clearHolds: Boolean) {
        clearDnsWindow()
        clearDemand()
        if (clearHolds) {
            dnsHeld = false
            egressHeld = false
        }
    }

    private fun clearDemand() {
        firstDemand = null
        lastDemand = null
    }

    private companion object {
        const val STALL_SECONDS = 21
        const val DEMAND_CONTINUITY_SECONDS = 10
        const val MAX_DECLARATIONS = 3
    }
}
