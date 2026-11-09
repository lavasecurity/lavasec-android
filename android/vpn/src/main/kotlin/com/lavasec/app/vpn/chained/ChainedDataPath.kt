package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedAllowedIPs
import com.lavasec.app.foundation.chained.ChainedOwnResolverPortRegistry
import com.lavasec.app.foundation.chained.ChainedPacketQueue
import com.lavasec.app.foundation.chained.ChainedPacketQueueLimits
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.vpn.TunPacketSession
import com.lavasec.app.vpn.TunRead
import com.lavasec.app.vpn.TunWriteAttempt
import com.lavasec.app.vpn.qa.DnsLeakCanarySend
import com.lavasec.app.vpn.qa.PreparedDnsLeakCanary
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.StateFlow

/**
 * The protected UDP socket bound to the underlying `Network` that carries WireGuard traffic.
 *
 * The socket is created, protected, and bound by the caller using the same
 * create → register → protect → bind → epoch-gate ordering as the resolver path; this seam only
 * sends and receives. `send` returns `false` rather than throwing so the data path can turn a
 * transport failure into one terminal signal; `receive` returns `null` when the socket ends.
 *
 * A [ChainedPeerSocket] is the concrete form of the driver's replaceable
 * [ChainedUpstreamChannel], so the rebind seam and the data path's socket are one type.
 */
internal interface ChainedPeerSocket : ChainedUpstreamChannel

/**
 * The chained packet-path composition, synchronous and free of Android and JNI.
 *
 * It is the stable RUNNER the outage driver drives: the driver builds a fresh engine session per
 * attempt through its `ChainedSessionSource` and installs it here with [replaceSession], while
 * this object keeps owning the TUN, the protected peer socket, the engine dispatcher, and the
 * engine owner across attempts. Everything is driven by explicit calls ([handleOutboundBatch],
 * [tick], [onPeerDatagram], [forceHandshake], [shutdown]) so the wiring and its ordering are
 * executable in a host JVM test; [ChainedTunnelRuntime] is the thin coroutine driver that calls
 * the read/receive side, and the outage driver calls the tick/attempt side.
 *
 * ## Confinement
 *
 * Every engine mutation runs through the injected [ChainedEngineDispatcher], which in production
 * is the SAME serial `ChainedEngineQueue` the driver and its timers run on
 * ([EngineQueueChainedDispatcher]) — the Android form of iOS's one queue shared by the driver and
 * its runners. A driver call therefore reaches the engine inline and a session end reported here
 * reaches the driver inline, so the two can never block on each other. The pending tunnel-write
 * deque is only touched from inside that confinement.
 *
 * ## Ownership
 *
 * The composition owns [packetSession], the peer channel, [session], and the owner, and releases them
 * in [close]. [shutdown] is the driver's attempt-retirement call: it retires the current engine
 * session but keeps the owner and the lifecycle resources alive for [replaceSession]. A caller
 * must [close] after observing [ChainedRuntimeFailure]; the failure callback is advisory and must
 * not re-enter the owner.
 */
internal class ChainedDataPath(
    configuration: ChainedUpstreamConfiguration,
    installedPlan: ChainedRoutePlan,
    private val packetSession: TunPacketSession,
    peerSocket: ChainedUpstreamChannel,
    session: WireGuardSession,
    ownResolverPorts: ChainedOwnResolverPortRegistry,
    private val clientDns: ChainedClientDns,
    private val dispatcher: ChainedEngineDispatcher,
    nowSeconds: () -> Int,
    upstreamGeneration: Long = 0L,
    private val onFailure: (ChainedRuntimeFailure) -> Unit = {},
) : ChainedSessionDriving, AutoCloseable {

    private val allowedIPs: ChainedAllowedIPs = requireNotNull(
        ChainedAllowedIPs.parse(configuration.capturedAllowedIPs.joinToString(",")),
    ) {
        "a validated configuration carries parseable AllowedIPs"
    }

    private val plan: ChainedRoutePlan = installedPlan

    private val queue: ChainedPacketQueue = ChainedPacketQueue(
        requireNotNull(ChainedPacketQueueLimits.forChainedTunnel(plan.mtu)) {
            "a validated configuration carries a queueable MTU"
        },
    )

    /** Decrypted packets the TUN would not accept yet; drained on the engine tick. */
    private val pendingTunnelWrites = ArrayDeque<PendingTunnelWrite>()
    private var forwardingGeneration = 0L
    // Generation zero is an actual initial session, not an unset sentinel. Transport-only
    // rebinds leave this generation alone; a new native session may deliberately fire again.
    private var canaryFiredGeneration: Long? = null
    private var canaryResource: PreparedDnsLeakCanary? = null
    private var forwardedNonDnsBytes = 0L
    private val resolverAddresses = com.lavasec.app.foundation.chained.ChainedTunnelResolverSelection
        .selectedResolvers(configuration)
        .mapNotNull(com.lavasec.app.foundation.chained.ChainedTunnelResolverSelection::resolverBytes)

    private val failed = AtomicBoolean(false)

    private val closed = AtomicBoolean(false)

    /**
     * Set when a superseded owner hands its route-claiming TUN to the engine queue so the successor
     * can replace it without a gap; [close] then releases everything except the detached session.
     */
    private val packetSessionDetached = AtomicBoolean(false)

    private val started = AtomicBoolean(false)

    /**
     * Where the current engine session reports its own end. The driver's `makeSession` sets this
     * to the driver's events sink; the owner is stable, so the sink is routed through this holder.
     */
    private val eventsRef = AtomicReference<ChainedSessionEvents>(ChainedSessionEvents {})

    /** Whether the driver currently owns an attempt; inbound datagrams are dropped when it does not. */
    @Volatile
    private var attemptLive = false

    /**
     * The live transport together with the generation it was installed at.
     *
     * Channel and generation are one immutable value published through ONE volatile reference, not
     * two fields written in sequence: a receive-loop pass that read the bumped generation and then
     * the old (now closed) socket would treat the old socket's `null` as the LIVE channel ending and
     * report `PEER_SOCKET_FAILED` during an ordinary rebind. Snapshotting the pair atomically means
     * a reader either sees the old pair or the new pair, never a mix.
     */
    private class ChannelSnapshot(
        val generation: Long,
        val channel: ChainedUpstreamChannel,
    )

    @Volatile
    private var channelSnapshot = ChannelSnapshot(0L, peerSocket)

    private val sink = object : ChainedEngineSink {
        override fun emitToNetwork(datagram: ByteArray) {
            if (!channelSnapshot.channel.send(datagram)) {
                terminate(ChainedRuntimeFailure.PEER_SOCKET_FAILED)
            }
        }

        override fun deliverToTunnel(packet: ByteArray) {
            // T0 resolver replies are addressed to the egress's own ephemeral ports, so they are
            // consumed here BEFORE ordinary tunnel delivery — writing one through would hand the
            // client a datagram it never asked for on a tuple its resolver does not own.
            if (!clientDns.observeInbound(packet)) writeToTunnel(packet)
        }

        override fun handleDnsQuery(packet: ByteArray) {
            clientDns.handleQuery(packet)
        }
    }

    val owner: ChainedEngineOwner = ChainedEngineOwner(
        session = session,
        sink = sink,
        allowedIPs = allowedIPs,
        queue = queue,
        ownResolverPorts = ownResolverPorts,
        dnsCaptureFloor = plan.dnsCaptureFloor,
        servesIPv6Dns = plan.dnsServerIPv6Address != null,
        nowSeconds = nowSeconds,
        upstreamGeneration = upstreamGeneration,
        dispatcher = dispatcher,
        events = ChainedSessionEvents { cause -> eventsRef.get().sessionEnded(cause) },
    )

    init {
        // ONE wiring point for the egress's two directions, run before any packet can arrive. The
        // resolver query rides `owner.handleOutboundPacket` so it is classified and carved out on
        // the same path as any other packet; the answer is written straight to the TUN.
        clientDns.bind(
            sendQuery = { packet -> owner.handleOutboundPacket(packet) },
            writeClientResponse = { response -> writeToTunnel(PendingTunnelWrite(dnsResponse = response)) },
        )
    }

    /** The owner's lifecycle, for a control plane that renders "recovering" as non-terminal. */
    val state: StateFlow<ChainedEngineState> get() = owner.state

    /**
     * Points the current and future sessions at [events] (the driver's sink), so a session end
     * reaches the driver that built it.
     */
    fun installEvents(events: ChainedSessionEvents) {
        eventsRef.set(events)
    }

    /**
     * The batch entry point the outage driver drives. The owner parks packets until the handshake
     * completes, exactly as the TUN read loop used to.
     */
    override fun handleOutboundBatch(packets: List<ByteArray>) {
        owner.handleOutboundBatch(packets)
    }

    /** Classifies and acts on one packet the TUN read loop produced. */
    fun onTunPacket(packet: ByteArray) {
        owner.handleOutboundPacket(packet)
    }

    /** Decrypts and delivers one datagram the peer socket received. */
    fun onPeerDatagram(packet: ByteArray, sourceAddress: ByteArray) {
        if (!attemptLive) return
        owner.handleInboundDatagram(packet, sourceAddress)
    }

    /** Drains deferred TUN writes, then advances the engine timers. Driven by the driver. */
    override fun tick() {
        dispatcher.run {
            drainTunnelWrites()
            owner.tick()
            // Time-bound DNS state (pending resolver lookups) is confined to the same serial
            // dispatcher as the engine, so it advances exactly once per engine tick.
            clientDns.expire()
        }
    }

    /**
     * Swaps the engine handle the driver's next attempt built in place and marks the attempt live.
     * The driver forces the handshake itself (a fresh session has no keys yet).
     */
    fun replaceSession(replacement: WireGuardSession) {
        dispatcher.run {
            closeCanaryResource()
            owner.replaceSession(replacement)
            forwardingGeneration++
            forwardedNonDnsBytes = 0L
            attemptLive = true
        }
    }

    /**
     * Swaps the transport under a live session, preserving the engine — the Android mirror of the
     * iOS runner's `adoptChannel`.
     *
     * A path change invalidates the socket, not the WireGuard session, so only the channel is
     * replaced: the swap costs one keepalive on the existing keypair instead of the full ladder.
     * The keepalive is sent only while a keypair is current; with none the engine would queue the
     * empty packet and emit a handshake instead, so the honest answer is
     * [ChainedRebindOutcome.NO_CURRENT_SESSION] and the driver rebuilds. The previous channel is
     * closed here (idempotently), so a refused swap never leaks a bound UDP port.
     */
    override fun adoptChannel(replacement: ChainedUpstreamChannel): ChainedRebindOutcome {
        if (closed.get() || !attemptLive) {
            runCatching { replacement.close() }
            return ChainedRebindOutcome.REFUSED
        }
        val stats = owner.stats()
        val hasKeypair = stats != null && stats.timeSinceLastHandshakeMs >= 0L
        swapChannel(replacement)
        owner.transportChanged()
        if (!hasKeypair) return ChainedRebindOutcome.NO_CURRENT_SESSION
        owner.sendKeepalive()
        return ChainedRebindOutcome.REBOUND
    }

    /**
     * Installs [replacement] as the live transport for the next attempt built by the driver's
     * source, closing the previous channel. Used by a full rebuild, which binds a fresh socket to
     * the path the device is on now.
     */
    fun replaceChannel(replacement: ChainedUpstreamChannel) {
        swapChannel(replacement)
    }

    /**
     * Installs one off-queue-built attempt in a single on-engine-queue step: its fresh transport,
     * its engine session, and the events sink the driver wants its ends routed to.
     *
     * The driver's source calls this from INSIDE the ownership fence (`ChainedSessionDelivery
     * .adopt`), so a build whose attempt was retired or superseded while the non-cancellable JNI
     * allocation ran never reaches here — the source closes the session and channel instead. The
     * channel is swapped before the session so the replacement session's first send rides the
     * replacement socket.
     */
    fun installSession(
        replacement: WireGuardSession,
        channel: ChainedUpstreamChannel,
        events: ChainedSessionEvents,
    ) {
        replaceChannel(channel)
        replaceSession(replacement)
        installEvents(events)
    }

    /**
     * Publishes the replacement channel and its new generation as ONE snapshot, then closes the
     * previous channel. The atomic publish is what lets the receive loop distinguish a superseded
     * channel's closed-socket `null` from the live channel genuinely ending: a reader that captured
     * the previous snapshot sees the reference change and retries, while a reader that captured the
     * replacement gets the live channel.
     */
    private fun swapChannel(replacement: ChainedUpstreamChannel) {
        val previous = channelSnapshot
        channelSnapshot = ChannelSnapshot(previous.generation + 1L, replacement)
        runCatching { previous.channel.close() }
    }

    /** Forces the initial handshake for the first adopted session. */
    override fun forceHandshake() {
        owner.forceHandshake()
    }

    override fun takeLivenessSample(): ChainedDriverLivenessSample = dispatcher.run {
        owner.takeLivenessSample().copy(
            forwardingGeneration = forwardingGeneration,
            forwardedNonDnsBytes = forwardedNonDnsBytes,
        )
    }

    override fun resetLivenessAfterSuspension() = owner.resetLivenessAfterSuspension()

    override fun acceptedUpstreamGeneration(): Long = owner.acceptedUpstreamGeneration()

    /**
     * Retires the current session for the driver's next attempt. The owner and the lifecycle
     * resources stay alive; [replaceSession] installs the replacement.
     */
    override fun shutdown(): Unit = dispatcher.run {
        attemptLive = false
        closeCanaryResource()
        // A deferred TUN write belongs to the attempt being retired; carrying it into the next
        // attempt would deliver a stale packet after a retry delay.
        pendingTunnelWrites.clear()
        owner.shutdown()
    }

    /** Actual handshake/session witness, read on the same dispatcher that replaces attempts. */
    fun canaryGenerationIfEstablished(): Long? = dispatcher.run {
        if (canaryResource == null && canaryFiredGeneration != forwardingGeneration && canaryEstablished())
            forwardingGeneration else null
    }

    /** Takes the not-yet-prepared auxiliary FD before any physical socket operation. */
    fun adoptCanary(expectedGeneration: Long, resource: PreparedDnsLeakCanary): Boolean = dispatcher.run {
        if (!canaryEstablished() || forwardingGeneration != expectedGeneration ||
            canaryFiredGeneration == expectedGeneration || canaryResource != null) {
            runCatching { resource.close() }
            false
        } else {
            canaryResource = resource
            true
        }
    }

    fun runCanary(expectedGeneration: Long, resource: PreparedDnsLeakCanary,
        send: () -> DnsLeakCanarySend): DnsLeakCanarySend = dispatcher.run {
        if (canaryResource !== resource || forwardingGeneration != expectedGeneration ||
            !canaryEstablished() || canaryFiredGeneration == expectedGeneration) {
            DnsLeakCanarySend.NoDatagramSent
        } else {
            val outcome = runCatching(send).getOrDefault(DnsLeakCanarySend.Indeterminate)
            if (outcome != DnsLeakCanarySend.NoDatagramSent) canaryFiredGeneration = expectedGeneration
            outcome
        }
    }

    fun releaseCanary(resource: PreparedDnsLeakCanary): Unit = dispatcher.run {
        if (canaryResource === resource) closeCanaryResource() else runCatching { resource.close() }
        Unit
    }

    private fun canaryEstablished(): Boolean = !closed.get() && !failed.get() && attemptLive &&
        (owner.state.value as? ChainedEngineState.Established)?.stats?.timeSinceLastHandshakeMs?.let { it >= 0 } == true

    private fun closeCanaryResource() {
        val previous = canaryResource
        canaryResource = null
        runCatching { previous?.close() }
    }

    /** Kept so [ChainedTunRuntimeOwner] can force the first handshake after adoption. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        attemptLive = true
        owner.forceHandshake()
    }

    /** Reads one packet from the TUN (delegated so the driver holds only this composition). */
    suspend fun readTunPacket(into: ByteArray): TunRead = packetSession.readPacket(into)

    /**
     * Receives one peer datagram from the live transport.
     *
     * A rebind closes the previous channel while the receive loop may be parked in its
     * `receive()`; that close surfaces as `null`. A superseded channel's `null` is NOT the end of
     * the transport — the loop re-reads the replacement — so only the LIVE channel ending returns
     * `null` to the runtime.
     */
    suspend fun receivePeerDatagram(): ChainedPeerDatagram? {
        while (true) {
            val snapshot = channelSnapshot
            val datagram = snapshot.channel.receive()
            if (closed.get()) return null
            if (snapshot !== channelSnapshot) continue
            return datagram
        }
    }

    /**
     * Detaches the route-claiming TUN session so a superseded owner can retain it as the current
     * tunnel instead of tearing it down (a successor's `establish` will replace it). After this,
     * [close] releases the engine, socket, and dispatcher but leaves the session to the caller.
     * Returns `null` once the composition is already closed.
     */
    fun detachPacketSession(): TunPacketSession? {
        if (closed.get()) return null
        packetSessionDetached.set(true)
        return packetSession
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // DNS completions and pending writes live on the same queue as the engine. Retire
        // those owned resources there as well; asynchronous fallback callbacks must never
        // race a caller-thread clear of the pending lookup/write maps.
        dispatcher.run {
            attemptLive = false
            closeCanaryResource()
            pendingTunnelWrites.clear()
            runCatching { owner.close() }
            runCatching { clientDns.close() }
        }
        runCatching { channelSnapshot.channel.close() }
        if (!packetSessionDetached.get()) runCatching { packetSession.close() }
    }

    /**
     * Writes one decrypted packet to the TUN.
     *
     * A full TUN is a back-pressure signal, not a loss to discard: the packet is held in a
     * bounded deque and retried on the next tick. The deque is bounded at the engine queue depth
     * and drops the OLDEST entry on overflow, matching the chained outbound queue's policy — a
     * packet that has waited longer than the path RTT is already useless to TCP, so shedding it
     * ahead of a fresher one moves more useful bytes.
     */
    private class PendingTunnelWrite(
        val packet: ByteArray? = null,
        val dnsResponse: ChainedDnsResponse? = null,
    ) {
        fun tryWrite(session: TunPacketSession): TunWriteAttempt? =
            dnsResponse?.tryWrite(session::tryWritePacket)
                ?: if (dnsResponse == null) session.tryWritePacket(requireNotNull(packet)) else null
    }

    private fun writeToTunnel(packet: ByteArray) = writeToTunnel(PendingTunnelWrite(packet = packet.copyOf()))

    private fun writeToTunnel(pending: PendingTunnelWrite) {
        if (closed.get()) return
        when (val attempt = pending.tryWrite(packetSession)) {
            null -> Unit
            TunWriteAttempt.Written -> recordForwarding(pending)
            TunWriteAttempt.WouldBlock -> {
                if (pendingTunnelWrites.size >= ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH) {
                    pendingTunnelWrites.removeFirst()
                }
                pendingTunnelWrites.addLast(pending)
            }
            TunWriteAttempt.Closed,
            is TunWriteAttempt.Failed,
            is TunWriteAttempt.ShortWrite,
            -> terminate(ChainedRuntimeFailure.TUN_WRITE_FAILED)
        }
    }

    private fun drainTunnelWrites() {
        while (pendingTunnelWrites.isNotEmpty()) {
            when (val attempt = pendingTunnelWrites.first().tryWrite(packetSession)) {
                null -> pendingTunnelWrites.removeFirst()
                TunWriteAttempt.Written -> recordForwarding(pendingTunnelWrites.removeFirst())
                TunWriteAttempt.WouldBlock -> return
                TunWriteAttempt.Closed,
                is TunWriteAttempt.Failed,
                is TunWriteAttempt.ShortWrite,
                -> {
                    pendingTunnelWrites.clear()
                    terminate(ChainedRuntimeFailure.TUN_WRITE_FAILED)
                    return
                }
            }
        }
    }

    private fun recordForwarding(pending: PendingTunnelWrite) {
        val packet = pending.packet ?: return // Locally synthesized DNS is not application progress.
        val udp = com.lavasec.app.foundation.chained.ChainedInnerUdpPacket.parse(packet)
        if (udp != null && udp.sourcePort == 53 && resolverAddresses.any { it.contentEquals(udp.sourceAddress) }) return
        forwardedNonDnsBytes += packet.size.toLong()
    }

    private fun terminate(failure: ChainedRuntimeFailure) {
        if (closed.get()) return
        if (failed.compareAndSet(false, true)) onFailure(failure)
    }
}
