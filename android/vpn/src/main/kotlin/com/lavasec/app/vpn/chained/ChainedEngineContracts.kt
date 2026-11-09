package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedReconnectDecision
import com.lavasec.app.foundation.chained.ChainedSurrenderReason
import com.lavasec.app.vpn.engine.LavaWgStats

/**
 * Serializes every engine operation for one session onto a single execution context.
 *
 * The vendored WireGuard engine has no internal concurrency design, so its caller contract is
 * one serial queue per session and every operation on a [WireGuardSession] must land there.
 * This is the seam that makes that contract real without pulling Android's
 * `Handler`/`Executor` types — and their framework dependency — into the owner: the JVM tests
 * use [Direct], while production routes the engine onto the SAME serial `ChainedEngineQueue`
 * the outage driver runs on ([EngineQueueChainedDispatcher]), so a driver call reaches the
 * engine inline.
 *
 * `run` returns the block's value so a caller on the dispatcher can both mutate and read
 * (a tick that samples stats, a command that returns the emitted datagram). It MUST be
 * re-entrant: a call made while already executing on the dispatcher runs the block INLINE
 * rather than deadlocking or queueing behind itself. That is what lets the driver tick a runner
 * and the runner report its own session end back to the driver without a cross-queue wait.
 */
internal interface ChainedEngineDispatcher {
    fun <T> run(block: () -> T): T

    companion object {
        /**
         * Runs the block inline on the calling thread. The tests' dispatcher, and the
         * degenerate "already confined" case; never used in production where the engine is
         * driven from an executor.
         */
        val Direct: ChainedEngineDispatcher = object : ChainedEngineDispatcher {
            override fun <T> run(block: () -> T): T = block()
        }
    }
}

/**
 * Where the owner's produced bytes and handed-off queries go. The platform wires these to the
 * protected UDP socket, the TUN write side, and the DNS path respectively.
 *
 * The owner never allocates a buffer for a sink to fill — it hands over the engine's own
 * decoded bytes. That keeps the residency in the engine's bounded stash and out of a second
 * copy the owner would have to bound itself.
 */
internal interface ChainedEngineSink {

    /** Sends an encrypted datagram to the peer over the protected socket. */
    fun emitToNetwork(datagram: ByteArray)

    /** Writes a decrypted, allowed-IPs-checked packet into the TUN. */
    fun deliverToTunnel(packet: ByteArray)

    /** Hands a client DNS query to the filter/DNS path. Ownership of [packet] transfers. */
    fun handleDnsQuery(packet: ByteArray)
}

/**
 * The owner's externally observable lifecycle.
 *
 * Mirrors the vocabulary the control plane and the React Native surface consume, so
 * "recovering" is never rendered as a terminal "off". Every case is a singleton or carries
 * only value data; no case holds a session handle, so the state can cross to a UI thread
 * without smuggling an engine resource across the confinement boundary.
 */
internal sealed interface ChainedEngineState {

    /** Stable identifier for device logs. Never user copy. */
    val logValue: String

    /** No session exists: not started, or closed. */
    data object Idle : ChainedEngineState {
        override val logValue: String get() = "idle"
    }

    /** A session exists but has not completed a handshake yet. Traffic is queued. */
    data object Starting : ChainedEngineState {
        override val logValue: String get() = "starting"
    }

    /** The handshake completed; the data path carries traffic. */
    data class Established(val stats: LavaWgStats?) : ChainedEngineState {
        override val logValue: String get() = "established"
    }

    /** A session ended and the outage budget authorized another attempt. */
    data class Recovering(val decision: ChainedReconnectDecision.Retry) : ChainedEngineState {
        override val logValue: String get() = "recovering-${decision.logValue}"
    }

    /**
     * The outage budget is spent or the fault is deterministic: chaining stops and the control
     * plane must restart the tunnel into DNS-only.
     */
    data class Surrendered(val reason: ChainedSurrenderReason) : ChainedEngineState {
        override val logValue: String get() = "surrendered-${reason.rawValue}"
    }
}

/**
 * Whether this state means the chained path is actually carrying traffic.
 *
 * Only [ChainedEngineState.Established] proves packets flow; [ChainedEngineState.Starting]
 * (handshake pending), [ChainedEngineState.Recovering] (session dead, retry pending), and
 * [ChainedEngineState.Surrendered] do not. The status surface must derive "routed through your
 * chained upstream" from this, never from the owner merely being installed.
 */
internal val ChainedEngineState.isEstablished: Boolean
    get() = this is ChainedEngineState.Established
