package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedAllowedIPs
import com.lavasec.app.foundation.chained.ChainedDnsCaptureFloor
import com.lavasec.app.foundation.chained.ChainedDroppedFragmentTable
import com.lavasec.app.foundation.chained.ChainedInboundVerdict
import com.lavasec.app.foundation.chained.ChainedOutboundDisposition
import com.lavasec.app.foundation.chained.ChainedOutboundPacketClassifier
import com.lavasec.app.foundation.chained.ChainedOwnResolverPortRegistry
import com.lavasec.app.foundation.chained.ChainedPacketQueue
import com.lavasec.app.foundation.chained.ChainedPacketQueueLimits
import com.lavasec.app.foundation.chained.ChainedSessionEndCause
import com.lavasec.app.foundation.chained.WireGuardEngineError
import com.lavasec.app.vpn.engine.LavaWgError
import com.lavasec.app.vpn.engine.LavaWgOp
import com.lavasec.app.vpn.engine.LavaWgResponse
import com.lavasec.app.vpn.engine.LavaWgStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns ONE WireGuard session and drives its single serial data path.
 *
 * ## INV-CHAIN-QUEUE-1 — one serial dispatcher per session
 *
 * This is the engine-side analogue of `INV-QUEUE-1`. The engine has no internal locking, so
 * every call into a [WireGuardSession] and every mutation of the owner's own state must run on
 * one execution context, never the DNS state queue. The owner therefore funnels every public
 * entry point through its [ChainedEngineDispatcher]; the JVM tests use the inline
 * [ChainedEngineDispatcher.Direct] and the production adapter wraps the tunnel's WireGuard
 * executor. Hanging this off the DNS state path would couple a crypto-bound loop to the queue
 * that answers queries — the two confinements are independent by design.
 *
 * ## What the owner is, and what it deliberately is not
 *
 * The owner is the packet-loop plumbing and ONE session's behaviour: classify each outbound
 * packet, move bytes through the engine, enforce the peer's `AllowedIPs` on the way in, track
 * link liveness, and report a session-ending engine verdict through [events]. It is NOT the
 * session factory, the socket, the resolver, or the outage driver: the driver owns the budget,
 * the retry ladder, the per-attempt watchdog, and the building/superseding of runners, and it
 * drives this owner through [ChainedSessionDriving]. Keeping the rebuild out of the owner is
 * what lets it be tested against a fake session with no socket and no Android runtime.
 *
 * The owner instance is stable across a tunnel lifecycle — the driver's source swaps the session
 * under it with [replaceSession] — so its published [state] flow is stable and a status collector
 * started once keeps tracking session changes.
 *
 * ## Bounded, allocation-disciplined, fail-closed
 *
 * - No buffer is allocated here. Outbound packets wait in the caller-supplied
 *   [ChainedPacketQueue], which is byte- AND count-bounded and evicts oldest-first; the
 *   fragment deny-list is bounded by the foundation type; inbound bytes are the engine codec's
 *   own decoded copy. The one size guard the owner adds is the engine's inner-IP ceiling,
 *   because a jumbo frame handed to `encapsulate` is a caller-contract violation that would
 *   otherwise end the session.
 * - A per-packet engine verdict (`protocolViolation`, `underLoad`, `noCurrentSession`,
 *   `oversizedDatagram`) is dropped and the session survives. Only a session-ending verdict or
 *   a diagnosed build failure reaches the reconnect policy. Conflating the two is the LAV-80
 *   reconnect-storm class: one hostile datagram per second must never keep a user disconnected.
 * - A decrypted packet is delivered only if its inner source passes
 *   [ChainedAllowedIPs.verdict]; a source the engine could not surface is refused (fail
 *   closed), because an unverifiable source is exactly what cryptokey routing exists to bound.
 * - A stalled link is reported, not trusted. An established peer that stops making data progress
 *   yields a [ChainedDriverLivenessSample] naming the first unanswered send, so the driver arms
 *   the outage budget FROM it — the 15 s detection window and the 15 s budget are one window, not
 *   30 s. A healthy upload-only flow is kept alive by the peer's authenticated keepalives (see
 *   [ChainedLivenessPolicy]), and a stalled peer whose handshake age also passes the rekey window
 *   has a genuinely failed rekey and ends the session here. An idle or responder-only session is
 *   never torn down on age alone.
 *
 * ## Drain contract
 *
 * `decapsulate` on an empty datagram is the engine's drain: call it again while it keeps
 * producing a datagram for the peer, and stop on anything else. The inbound entry point
 * implements exactly that, bounded at [ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH] steps so a
 * misbehaving adapter cannot spin the dispatcher.
 *
 * ## Session replacement
 *
 * [replaceSession] swaps the engine handle in place for the driver's next attempt and clears the
 * shutdown latch; the parked queue is intentionally NOT retained across attempts (the driver
 * supersedes the runner, and a packet parked against a dead session is already useless). It
 * avoids reallocating the packet-loop plumbing for every attempt.
 */
internal class ChainedEngineOwner(
    private var session: WireGuardSession,
    private val sink: ChainedEngineSink,
    private val allowedIPs: ChainedAllowedIPs,
    private val queue: ChainedPacketQueue,
    private val ownResolverPorts: ChainedOwnResolverPortRegistry,
    private val nowSeconds: () -> Int,
    private val upstreamGeneration: Long = 0L,
    private val dispatcher: ChainedEngineDispatcher = ChainedEngineDispatcher.Direct,
    private val events: ChainedSessionEvents = ChainedSessionEvents {},
    private val dnsCaptureFloor: ChainedDnsCaptureFloor = ChainedDnsCaptureFloor.empty,
    private val servesIPv6Dns: Boolean = false,
) : ChainedSessionDriving, AutoCloseable {

    /** Datagrams whose first fragment was dropped as unfilterable DNS; classifier-owned. */
    private val fragments = ChainedDroppedFragmentTable()

    /**
     * Tracks whether the established peer is still making data progress, the primary liveness
     * signal. Reset on a session replacement.
     */
    private var livenessTracker = ChainedLivenessTracker.start()

    /** The latest unanswered-send reading, in the shape the driver's liveness sample expects. */
    private var livenessOutstandingUnansweredSend: Boolean = false
    private var livenessFirstUnansweredAtSeconds: Int? = null

    /**
     * Whether an authenticated peer datagram was decapsulated since the last [takeLivenessSample].
     *
     * Read-and-clear, because the driver asks "has the CURRENT transport proven itself since I
     * last looked". It is what confirms a rebind: a completed handshake is session age, not
     * evidence about the replacement socket, so the driver must require this.
     */
    private var livenessSawAuthenticatedPeerDatagram: Boolean = false
    private var sawObligingNonDnsSend = false

    /** Whether the current session has completed a handshake and may carry traffic. */
    private var established = false

    /**
     * Set when [shutdown] retires the current session (the driver superseded the attempt) while
     * the owner stays reusable; cleared by [replaceSession]. Distinct from [closed], which is
     * lifecycle-terminal.
     */
    @Volatile
    private var ended = false

    /** Set on the dispatcher at close; read from any thread for the cheap terminal checks. */
    @Volatile
    private var closed = false

    /** Latest stats snapshot, published alongside [state] and readable without the engine. */
    @Volatile
    private var lastStats: LavaWgStats? = null

    private val mutableState = MutableStateFlow<ChainedEngineState>(
        if (session.isOpen) ChainedEngineState.Starting else ChainedEngineState.Idle,
    )

    /** The owner's lifecycle. Safe to collect from any thread; mutations stay on the dispatcher. */
    val state: StateFlow<ChainedEngineState> get() = mutableState.asStateFlow()

    /** The most recent engine health snapshot, or `null` before the first sample. */
    fun stats(): LavaWgStats? = lastStats

    /**
     * Classifies and acts on one inner IPv4 packet the TUN handed over.
     *
     * While no handshake has completed the packet is parked in the bounded queue rather than
     * handed to the engine: under chaining the tunnel claims `0.0.0.0/0`, so dropping instead
     * would blackhole the user's first packets during a handshake that is about to succeed.
     */
    fun handleOutboundPacket(packet: ByteArray): Unit = dispatcher.run {
        if (closed || ended) return@run
        // The engine's per-packet inner-IP ceiling. Oversized input would surface as
        // `packetTooLarge`, a caller-contract violation that surrenders chaining — so it is shed
        // here, where the disposition is a drop rather than a session end.
        if (packet.size > ChainedPacketQueueLimits.MAXIMUM_IP_PACKET_BYTE_COUNT) return@run
        classifyAndAct(packet, mayReclaim = true, mayQueue = true)
    }

    /** The batch entry point the driver drives. */
    override fun handleOutboundBatch(packets: List<ByteArray>): Unit =
        packets.forEach(::handleOutboundPacket)

    /**
     * Decrypts one inbound UDP datagram and drains any control replies it obliged.
     *
     * Deliveries are allowed-IPs-checked; a `WRITE_TO_NETWORK` reply (a cookie or our answer to
     * a peer-initiated handshake) goes out; anything else is a per-packet drop or a session end.
     *
     * A datagram larger than the engine's destination buffer is dropped BEFORE the JNI call:
     * the C ABI reports that remotely-chosen size as `DESTINATION_BUFFER_TOO_SMALL`, a per-packet
     * drop, and letting it reach the session-end path would let one unauthenticated UDP datagram
     * force a DNS-only downgrade.
     *
     * A successfully decrypted transport-data datagram is recorded as authenticated peer
     * activity, which clears the stall clock. This is the keepalive signal `rxBytes` cannot see:
     * a zero-length keepalive advances no bytes but proves the peer holds the session keys, so a
     * healthy upload-only flow is not mistaken for a dead path.
     */
    fun handleInboundDatagram(datagram: ByteArray, sourceAddress: ByteArray): Unit =
        dispatcher.run {
            if (closed || ended) return@run
            if (datagram.size > ChainedPacketQueueLimits.MAXIMUM_DATAGRAM_BYTE_COUNT) return@run
            var response = session.decapsulate(datagram, sourceAddress)
            if (session.provesPeerActivity(datagram, response)) {
                livenessTracker = livenessTracker.notePeerActivity(nowSeconds())
                // Latched for the driver's rebind confirmation; cleared when the sample is read.
                livenessSawAuthenticatedPeerDatagram = true
            }
            var steps = ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH
            while (true) {
                val outcome = handleEngineResponses(response, inbound = true)
                steps--
                if (outcome != EngineResponseOutcome.CONTINUE_DRAIN || steps <= 0) return@run
                response = session.decapsulate(EMPTY_BYTES, sourceAddress)
            }
        }

    /**
     * Advances engine timers, emits whatever `tick` produced, and reconciles the published
     * state with the engine's health.
     *
     * The reconciliation is what promotes [ChainedEngineState.Starting] to
     * [ChainedEngineState.Established] (releasing the parked queue). A tick that ended the session
     * is NOT reconciled against stats in the same pass: the expired session's snapshot must not be
     * read as a fresh handshake before the next attempt. The driver's per-attempt watchdog — not
     * this owner — bounds a handshake that never completes.
     */
    override fun tick(): Unit = dispatcher.run {
        if (closed || ended) return@run
        val now = nowSeconds()
        val outcome = handleEngineResponses(session.tick())
        if (outcome == EngineResponseOutcome.SESSION_ENDED) return@run
        refreshFromStats(now)
    }

    /** Forces a handshake and emits whatever control datagram it produced. */
    override fun forceHandshake(): Unit = dispatcher.run {
        if (closed || ended) return@run
        handleEngineResponses(session.forceHandshake())
    }

    fun transportChanged(): Unit = dispatcher.run { session.transportChanged() }

    override fun resetLivenessAfterSuspension(): Unit = dispatcher.run {
        livenessTracker = ChainedLivenessTracker.start()
        livenessOutstandingUnansweredSend = false
        livenessFirstUnansweredAtSeconds = null
        livenessSawAuthenticatedPeerDatagram = false
        sawObligingNonDnsSend = false
        // Establish a fresh byte baseline: old transmitted bytes do not become a new obligation.
        lastStats?.let { livenessTracker = livenessTracker.observe(nowSeconds(), it).tracker }
    }

    /**
     * Emits one data keepalive on the current keypair — the probe a channel rebind sends on the
     * replacement socket, mirroring the iOS runner's `.unobliging` rebind keepalive.
     *
     * An empty `encapsulate` is the engine's keepalive; it is deliberately NOT `.obliging`, so the
     * peer owes no reply and the driver's own rebind-confirmation window is what proves the new
     * socket, not the send completing.
     */
    fun sendKeepalive(): Unit = dispatcher.run {
        if (closed || ended) return@run
        handleEngineResponses(session.encapsulate(EMPTY_BYTES))
    }

    /**
     * The link-liveness reading the driver consumes.
     *
     * [ChainedDriverLivenessSample.hasHandshake] is derived from the last stats sample, and
     * [ChainedDriverLivenessSample.firstUnansweredSendAtSeconds] is non-null only while a send is
     * outstanding unanswered (progress clears it), so the driver can both arm the budget FROM the
     * first unanswered send and end the outage when it clears.
     */
    override fun takeLivenessSample(): ChainedDriverLivenessSample = dispatcher.run {
        val stats = lastStats
        val sample = ChainedDriverLivenessSample(
            hasHandshake = stats != null && stats.timeSinceLastHandshakeMs >= 0L,
            firstUnansweredSendAtSeconds =
                if (livenessOutstandingUnansweredSend) livenessFirstUnansweredAtSeconds else null,
            sawAuthenticatedPeerDatagram = livenessSawAuthenticatedPeerDatagram,
            sawObligingNonDnsSend = sawObligingNonDnsSend,
            transmittedBytes = stats?.txBytes,
            receivedBytes = stats?.rxBytes,
        )
        // READ-AND-CLEAR: the driver asks what the current transport has proven since it last
        // looked, and a latch that stayed set would confirm every later rebind from one datagram.
        livenessSawAuthenticatedPeerDatagram = false
        sawObligingNonDnsSend = false
        sample
    }

    /** The store generation this session's key material came from. */
    override fun acceptedUpstreamGeneration(): Long = upstreamGeneration

    /**
     * Retires the current session for the driver's next attempt WITHOUT closing the owner: the
     * owner instance is stable and [replaceSession] installs the replacement. Idempotent.
     */
    override fun shutdown(): Unit = dispatcher.run {
        if (closed) return@run
        ended = true
        established = false
        // Evidence belongs to the retired attempt; the next session must prove itself afresh.
        livenessSawAuthenticatedPeerDatagram = false
        sawObligingNonDnsSend = false
        // The attempt is over, so the state must stop reporting `Established`: the driver observes
        // this flow to publish "chained active", and a stale Established would claim traffic is
        // routed upstream while no runner can carry it. The parked queue is dropped too — a packet
        // parked against this attempt is already useless and must not be flushed onto a later one.
        mutableState.value = ChainedEngineState.Starting
        queue.removeAll()
        runCatching { session.close() }
    }

    /**
     * Swaps in the freshly constructed session the driver's next attempt built, in place.
     *
     * The previous handle is closed here because ownership transfers with the call; closing is
     * idempotent, so a driver that already shut the previous attempt down is safe. A
     * lifecycle-terminal owner cannot accept the handle, so it closes the replacement before
     * returning: the caller offered ownership and will not close it, and a silent return would
     * leak the JNI/Rust handle and its key material.
     */
    fun replaceSession(replacement: WireGuardSession): Unit = dispatcher.run {
        if (closed) {
            if (replacement !== session) runCatching { replacement.close() }
            return@run
        }
        val previous = session
        session = replacement
        ended = false
        established = false
        lastStats = null
        livenessTracker = ChainedLivenessTracker.start()
        livenessOutstandingUnansweredSend = false
        livenessFirstUnansweredAtSeconds = null
        livenessSawAuthenticatedPeerDatagram = false
        sawObligingNonDnsSend = false
        if (previous !== replacement) runCatching { previous.close() }
        mutableState.value = ChainedEngineState.Starting
    }

    /** Closes the owned session and parks the state at [ChainedEngineState.Idle]. Idempotent. */
    override fun close(): Unit = dispatcher.run {
        if (closed) return@run
        closed = true
        ended = true
        runCatching { session.close() }
        mutableState.value = ChainedEngineState.Idle
    }

    // MARK: - Outbound

    /**
     * One classification pass. [mayQueue] is false on the release path, where a packet that
     * reclassifies out of an encapsulating disposition is acted on rather than parked again —
     * re-parking would put it behind the whole queue on every tick.
     */
    private fun classifyAndAct(packet: ByteArray, mayReclaim: Boolean, mayQueue: Boolean) {
        val disposition = ChainedOutboundPacketClassifier.disposition(
            packet = packet,
            fragments = fragments,
            ownResolverPorts = ownResolverPorts,
            reclaimsStaleDenials = mayReclaim,
            claimedResolverDestinations = dnsCaptureFloor,
            servesIPv6Dns = servesIPv6Dns,
        )
        when (disposition) {
            ChainedOutboundDisposition.Encapsulate,
            ChainedOutboundDisposition.EncapsulateOwnResolverQuery,
            -> if (!established && mayQueue) {
                queue.enqueue(packet)
            } else {
                if (disposition == ChainedOutboundDisposition.Encapsulate) sawObligingNonDnsSend = true
                handleEngineResponses(session.encapsulate(packet))
            }

            ChainedOutboundDisposition.HandleAsDNS -> sink.handleDnsQuery(packet)

            ChainedOutboundDisposition.DropOutboundIPv6,
            ChainedOutboundDisposition.DropUnfilterableDNS,
            ChainedOutboundDisposition.DropMalformed,
            -> Unit // The classifier's decision is final; forwarding would undo it.
        }
    }

    /**
     * Releases the parked queue oldest-first, once a session is established.
     *
     * Re-classified on release (`mayReclaim = false`) exactly as on arrival, so a query whose
     * own-resolver claim lapsed while it waited is not served as a client query. Stops the
     * moment the session is no longer established — a mid-flush session end must not keep
     * feeding a dead engine.
     */
    private fun flushQueuedPackets() {
        while (established && !closed && !ended) {
            val packet = queue.dequeue() ?: return
            classifyAndAct(packet, mayReclaim = false, mayQueue = false)
        }
    }

    // MARK: - Inbound and engine outcomes

    /** What one engine response requires of its caller. */
    private enum class EngineResponseOutcome {
        /** A datagram for the peer was emitted; ask again with an empty source. */
        CONTINUE_DRAIN,

        /** The response was consumed (tunnel packet, idle, or per-packet drop). */
        SETTLED,

        /** The session is over; the reconnect path has already been consulted. */
        SESSION_ENDED,
    }

    /** Flush adapter-owned output from this operation before another packet or timer is driven.
     * The native inbound empty-datagram drain remains separate; this never invokes an engine. */
    private fun handleEngineResponses(
        response: LavaWgResponse,
        inbound: Boolean = false,
    ): EngineResponseOutcome {
        val first = handleEngineResponse(response, inbound)
        if (first == EngineResponseOutcome.SESSION_ENDED) return first
        repeat(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH - 1) {
            val pending = session.takePendingResponse()
            if (pending == LavaWgResponse.None) return first
            if (handleEngineResponse(pending, inbound) == EngineResponseOutcome.SESSION_ENDED) {
                return EngineResponseOutcome.SESSION_ENDED
            }
        }
        return first
    }

    private fun handleEngineResponse(
        response: LavaWgResponse,
        inbound: Boolean = false,
    ): EngineResponseOutcome =
        when (response) {
            is LavaWgResponse.Produced -> when (response.op) {
                LavaWgOp.WRITE_TO_NETWORK -> {
                    sink.emitToNetwork(response.packet)
                    EngineResponseOutcome.CONTINUE_DRAIN
                }

                LavaWgOp.WRITE_TO_TUNNEL_V4,
                LavaWgOp.WRITE_TO_TUNNEL_V6,
                -> {
                    deliverToTunnel(response.packet, response.sourceAddress)
                    EngineResponseOutcome.SETTLED
                }

                LavaWgOp.NONE -> EngineResponseOutcome.SETTLED
            }

            LavaWgResponse.None -> EngineResponseOutcome.SETTLED

            is LavaWgResponse.Failure -> {
                if (handleEngineFailure(response.error, inbound)) {
                    EngineResponseOutcome.SESSION_ENDED
                } else {
                    EngineResponseOutcome.SETTLED
                }
            }
        }

    /**
     * Enforces the peer's `AllowedIPs` on a decrypted packet. Only [ChainedInboundVerdict.Deliver]
     * reaches the TUN; a spoofed or unverifiable source is dropped.
     *
     * IPv6 is included rather than special-cased: while chained the tunnel claims `::/0` only to
     * blackhole it, and a v6 packet whose source the peer's (IPv4-only) `AllowedIPs` cannot
     * authorize must not be delivered on the strength of its family.
     */
    private fun deliverToTunnel(packet: ByteArray, sourceAddress: ByteArray?) {
        val source = sourceAddress ?: EMPTY_BYTES
        if (allowedIPs.verdict(source, packet.size) is ChainedInboundVerdict.Deliver) {
            sink.deliverToTunnel(packet)
        }
    }

    /** @return whether the engine verdict ended the session. */
    private fun handleEngineFailure(error: LavaWgError, inbound: Boolean): Boolean {
        // The same ABI code means two different things by direction. On the INBOUND path a
        // `DESTINATION_BUFFER_TOO_SMALL` is the remotely-chosen datagram size, which the C ABI
        // documents as a per-packet drop — mapping it to the caller-bug verdict would let one
        // unauthenticated datagram tear the session down. Outbound, an undersized destination
        // is our own buffer discipline and stays a caller-contract violation. `engineVerdict`
        // then rejects the inbound case (OversizedDatagram is per-packet) and admits the
        // outbound one, so the two cannot be reached for the same code.
        val mapped = if (inbound && error == LavaWgError.DESTINATION_BUFFER_TOO_SMALL) {
            WireGuardEngineError.OversizedDatagram
        } else {
            error.toFoundationError()
        }
        val cause = ChainedSessionEndCause.engineVerdict(mapped)
            ?: return false // per-packet verdict: drop it, the session survives
        reportSessionEnd(cause)
        return true
    }

    /**
     * Reports a session end to the driver through [events].
     *
     * The owner no longer classifies the end against an outage budget. The driver owns the budget
     * across attempts, the retry ladder, and the per-attempt watchdog, and it decides whether this
     * end spends a rung or surrenders. The owner only states the fact — the engine's own verdict,
     * or the rekey-window failure below — on the serial dispatcher the driver drives it from.
     */
    private fun reportSessionEnd(cause: ChainedSessionEndCause) {
        established = false
        mutableState.value = ChainedEngineState.Starting
        events.sessionEnded(cause)
    }

    /**
     * Reconciles the published state with the engine's health.
     *
     * The progress signal is a SUSPECT the driver bounds, not a verdict here: once an established
     * peer stops advancing `rxBytes` while we keep sending past
     * [ChainedLivenessPolicy.LIVENESS_STALL_WINDOW_SECONDS], the sample names
     * [ChainedLivenessStep.firstUnansweredSendAtSeconds] and the driver arms the budget FROM that
     * instant. The session stays driven so BoringTun's reachability handshake response is accepted;
     * a healthy upload-only flow survives because its authenticated keepalives (recorded in
     * [handleInboundDatagram]) clear the stall. The budget and the detection window are the same
     * 15 s, so a dead path is bounded out at 15 s, not 30.
     *
     * The handshake-age guard is a verdict, but ONLY alongside the stall evidence: an unanswered
     * send proves a reachability handshake was initiated (BoringTun starts one at
     * `KEEPALIVE + REKEY_TIMEOUT` regardless of handshake role), so if the rekey window has also
     * passed the rekey genuinely failed and the session is ended here. Age alone is not a verdict —
     * an idle or responder-only session (BoringTun only rekeys when the initiator has sent data)
     * has a high age while healthy and must not be torn down.
     */
    private fun refreshFromStats(now: Int) {
        val stats = session.stats()
        lastStats = stats

        if (stats == null || stats.timeSinceLastHandshakeMs < 0L) {
            // No session (or no completed handshake): there is no progress to watch. The driver
            // sees `hasHandshake = false` and starts the outage from the budget clock.
            livenessTracker = ChainedLivenessTracker.start()
            livenessOutstandingUnansweredSend = false
            livenessFirstUnansweredAtSeconds = null
            established = false
            mutableState.value = ChainedEngineState.Starting
            return
        }

        val step = livenessTracker.observe(now, stats)
        livenessTracker = step.tracker
        livenessOutstandingUnansweredSend = step.hasUnansweredSend
        livenessFirstUnansweredAtSeconds = step.firstUnansweredSendAtSeconds

        if (step.isStallSuspected && ChainedLivenessPolicy.isRekeyWindowExceeded(stats)) {
            // Unanswered sends prove a reachability handshake was initiated (BoringTun starts one
            // whenever data has gone unanswered for KEEPALIVE + REKEY_TIMEOUT, whatever this
            // endpoint's handshake role), and the whole rekey window has since passed: the rekey
            // failed. `?: return` is unreachable — ConnectionExpired is session-ending — but keeps
            // the packet path free of a throw.
            established = false
            val cause =
                ChainedSessionEndCause.engineVerdict(WireGuardEngineError.ConnectionExpired)
                    ?: return
            reportSessionEnd(cause)
            return
        }

        // Established (or stalled but still probing): publish the sample and keep the path driven.
        // An idle or responder-only session is healthy at any handshake age and is never torn down
        // on age alone — BoringTun only rekeys when the initiator has sent data
        // (`timers.rs:238-249`).
        established = true
        mutableState.value = ChainedEngineState.Established(stats)
        flushQueuedPackets()
    }

    private companion object {
        /** Shared empty source for an engine drain and for an unsurfaced peer address. */
        val EMPTY_BYTES = ByteArray(0)

    }
}

/**
 * Maps the Android ABI error enum onto the shared, portable engine-error vocabulary the
 * reconnect policy is written against. The Android enum is deliberately smaller; every case
 * maps to exactly one foundation case, so the two switches cannot drift by omission.
 */
private fun LavaWgError.toFoundationError(): WireGuardEngineError = when (this) {
    LavaWgError.INVALID_ARGUMENT -> WireGuardEngineError.InvalidArgument
    LavaWgError.DESTINATION_BUFFER_TOO_SMALL -> WireGuardEngineError.DestinationBufferTooSmall
    LavaWgError.NO_CURRENT_SESSION -> WireGuardEngineError.NoCurrentSession
    LavaWgError.UNDER_LOAD -> WireGuardEngineError.UnderLoad
    LavaWgError.PROTOCOL -> WireGuardEngineError.ProtocolViolation
    LavaWgError.CONNECTION_EXPIRED -> WireGuardEngineError.ConnectionExpired
    LavaWgError.INTERNAL -> WireGuardEngineError.EngineInternal
    LavaWgError.PACKET_TOO_LARGE -> WireGuardEngineError.PacketTooLarge
}
