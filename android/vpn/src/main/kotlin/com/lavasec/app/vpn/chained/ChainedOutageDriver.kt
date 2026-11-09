package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedReconnectPolicy
import com.lavasec.app.foundation.chained.ChainedSessionEndCause
import com.lavasec.app.foundation.chained.ChainedSurrenderReason
import com.lavasec.app.foundation.chained.WireGuardEngineError
import com.lavasec.app.vpn.ChainedEngineQueue

/**
 * One inbound datagram and the peer address it arrived from (0, 4, or 16 bytes).
 *
 * It lives with the channel contract rather than the concrete data path so the driver's rebind
 * path can be driven from a host-JVM test without the Android packet session.
 */
internal class ChainedPeerDatagram(
    val packet: ByteArray,
    val sourceAddress: ByteArray,
)

/**
 * The UDP transport to the WireGuard peer, bound to one underlay — the Android mirror of iOS
 * `ChainedUpstreamChannel`.
 *
 * A path change invalidates the SOCKET, not the WireGuard session (the engine holds keys and an
 * index, not an endpoint), so the outage driver replaces just this channel under a live session
 * instead of running the full rebuild ladder. [send] returns `false` rather than throwing so a
 * transport failure stays one terminal signal; [receive] returns `null` when the channel ends.
 * [close] is idempotent.
 */
internal interface ChainedUpstreamChannel : AutoCloseable {
    fun send(datagram: ByteArray): Boolean
    suspend fun receive(): ChainedPeerDatagram?
    override fun close()
}

/**
 * What replacing a session's transport achieved — the Android mirror of iOS
 * `ChainedRebindOutcome`. Stable identifiers, never user copy.
 */
internal enum class ChainedRebindOutcome {
    /** Swapped, and a keepalive went out on the existing keypair. */
    REBOUND,

    /**
     * Swapped, but NO KEYPAIR IS CURRENT, so nothing was probed. The caller must rebuild rather
     * than wait for a confirmation that cannot arrive.
     */
    NO_CURRENT_SESSION,

    /** Not swapped. The runner is shut down, or the replacement could not be adopted. */
    REFUSED,
}

/**
 * The session behaviour the driver depends on — the Android mirror of iOS
 * `ChainedSessionDriving`.
 *
 * A protocol rather than the concrete engine owner, for two reasons that point the same way: the
 * driver has no business knowing how a session is CONSTRUCTED (endpoint, socket, key material),
 * and a driver wired to a concrete runner cannot be tested without standing up a real engine for
 * every question about the budget. Engine queue only.
 */
internal interface ChainedSessionDriving {

    /** Hands one outbound batch to the engine, queueing it until a handshake completes. */
    fun handleOutboundBatch(packets: List<ByteArray>)

    /**
     * Replaces the transport, preserving the engine. Engine queue only.
     *
     * The default refuses, so a runner that owns no replaceable transport (the legacy engine
     * owner) makes the driver fall back to a full rebuild rather than believe a probe went out.
     */
    fun adoptChannel(replacement: ChainedUpstreamChannel): ChainedRebindOutcome {
        runCatching { replacement.close() }
        return ChainedRebindOutcome.REFUSED
    }

    /** Advances the engine's timers (handshake retries, keepalives, rekeys). */
    fun tick()

    /** Forces a handshake initiation, e.g. on wake or when a fresh attempt is adopted. */
    fun forceHandshake()

    /** Retire pre-suspension evidence before a paired wake re-arms the session. */
    fun resetLivenessAfterSuspension() { takeLivenessSample() }

    /**
     * The read-and-clear link-silence signal. [ChainedDriverLivenessSample.firstUnansweredSendAtSeconds]
     * is `null` when the peer made progress since the last unanswered send; otherwise it names the
     * instant of the first send nothing answered, so the budget is measured from there and not
     * from the moment detection runs.
     */
    fun takeLivenessSample(): ChainedDriverLivenessSample

    /**
     * The store generation this runner's key material came from. Immutable and infallible, so a
     * live runner can never be confused with none by a fallible transport read.
     */
    fun acceptedUpstreamGeneration(): Long

    /** Shuts the session down. Idempotent. */
    fun shutdown()
}

/**
 * Where a new session comes from — the driver's ENTIRE knowledge of the transport. The Android
 * mirror of iOS `ChainedSessionSource`.
 *
 * It builds the runner rather than handing back parts, because everything construction needs
 * belongs to the layer that owns the transport. Called inline on the engine queue and must not
 * block it: the armed watchdog's handler is enqueued behind the build on the same serial queue,
 * so a blocking build is a blackhole the watchdog cannot bound.
 *
 * iOS's runner construction is in-process Swift and satisfies that contract inline. Android's
 * ~50 MB BoringTun/JNI `sessionFactory.create()` is synchronous and NON-cancellable, so it cannot:
 * [makeSession] must start the build WITHOUT waiting for the allocation and deliver the outcome
 * back through [ChainedSessionDelivery] once it returns. [makeSession] itself must return
 * promptly, and a build failure — a factory refusal (`null`) or a thrown error — travels the same
 * seam and is triaged by [ChainedSessionEndCause.buildFailure] exactly as iOS does. See
 * `docs/chained-port-from-ios.md` deviation 11.
 */
internal interface ChainedSessionSource {
    /**
     * Starts building a REPLACEMENT transport for a session that is still alive and returns
     * WITHOUT blocking the engine queue. The build's outcome must be reported exactly once through
     * [delivery].
     *
     * Same no-blocking contract as [makeSession], and for the same reason: iOS's `makeChannel` is
     * in-process Swift and satisfies it inline, but Android's replacement channel resolves a
     * hostname through `Network.getAllByName`, which blocks. Called inline on the engine queue, a
     * slow or stuck underlay resolver would defer the driver's ticks, its armed outage deadline,
     * and teardown while the full-route TUN is pinned to the dead path, so the blackhole outlives
     * the budget (finding 4022018493). The whole build (underlay selection, resolution, socket
     * bind) therefore runs off the engine queue, exactly like the ~50 MB session allocation
     * (deviation 11), and reports back through the ownership fence.
     *
     * @return `true` when the build was started (its outcome arrives through [delivery]); `false`
     *   when no build could even start, so the driver falls back to the ladder synchronously. A
     *   source may also call [ChainedChannelDelivery.failed] synchronously and return `true`.
     */
    fun makeChannel(delivery: ChainedChannelDelivery): Boolean = false

    /**
     * Starts building one REPLACEMENT session and returns WITHOUT blocking the engine queue. The
     * build's outcome must be reported exactly once through [delivery], which fences the install
     * against the attempt the driver authorized.
     */
    fun makeSession(events: ChainedSessionEvents, delivery: ChainedSessionDelivery)
}

/**
 * Where an off-queue replacement-channel build reports its outcome — the Android form of
 * [ChainedSessionSource]'s "must not block the engine queue" contract for the rebind path.
 *
 * The driver mints one of these per rebind and hands it to [ChainedSessionSource.makeChannel].
 * The source performs the slow resolution/socket bind OFF the engine queue and then reports
 * through exactly one of [adopt] (a channel was built) or [failed] (no channel: no eligible
 * underlay, a refused resolver, or an unbindable socket). Both deliver ON the engine queue,
 * fenced against the rebind they were built for, so a channel whose rebind was superseded, whose
 * session went away, or whose outage began while the lookup ran is refused rather than swapped
 * and the caller closes it.
 */
internal interface ChainedChannelDelivery {
    /**
     * Offers the built channel to the driver. The driver adopts it ON the engine queue only when
     * this rebind is still current, swapping it under the live session and arming the rebind
     * confirmation. Returns `true` when the channel was consumed (adopted, or already closed by a
     * declined swap); `false` when the rebind was superseded, in which case the caller MUST close
     * the channel.
     */
    fun adopt(channel: ChainedUpstreamChannel): Boolean

    /**
     * Reports that no channel could be built. Triaged as a declined rebind: the counters record
     * it and the driver falls back to the full ladder.
     */
    fun failed()
}

/**
 * Where an off-queue session build reports its outcome — the Android form of
 * [ChainedSessionSource]'s "must not block the engine queue" contract.
 *
 * The driver mints one of these per attempt and hands it to the source. The source performs the
 * slow, non-cancellable allocation OFF the engine queue and then reports through exactly one of
 * [adopt] (a session was built) or [failed] (a refusal or an error). Both deliver ON the engine
 * queue, fenced against the attempt they were built for, so a session whose attempt was retired
 * or superseded while allocating is refused rather than adopted and the caller releases it.
 */
internal interface ChainedSessionDelivery {
    /**
     * Offers the built runner to the driver. The driver runs [install] ON the engine queue only
     * when this build's attempt is still current, then adopts the runner it returns and forces
     * its handshake. Returns `true` when adopted; `false` when the attempt was retired or
     * superseded while allocating, in which case the caller MUST release the built session.
     */
    fun adopt(install: () -> ChainedSessionDriving): Boolean

    /**
     * Reports that the build produced no session: `null` for the factory's refusal (a lapsed
     * entitlement/eligibility), or the thrown error. Triaged by
     * [ChainedSessionEndCause.buildFailure], exactly as the old synchronous throw was.
     */
    fun failed(failure: Throwable?)
}

/** Where a runner reports its own death — the Android mirror of iOS `ChainedSessionEvents`. */
internal fun interface ChainedSessionEvents {
    fun sessionEnded(cause: ChainedSessionEndCause)
}

/**
 * One link-liveness reading. A level, not a tally: it describes the live runner only.
 */
internal data class ChainedDriverLivenessSample(
    /** Whether a handshake has completed. No handshake means there is no progress to watch. */
    val hasHandshake: Boolean,
    /** The first unanswered send, or `null` when the peer made progress since the last send. */
    val firstUnansweredSendAtSeconds: Int?,
    /**
     * Whether an AUTHENTICATED peer datagram (a successfully decapsulated transport-data packet,
     * a zero-length keepalive included) was seen since the last sample — read-and-clear.
     *
     * This is the ONLY evidence that confirms a rebind. The handshake age is session age, not a
     * statement about the CURRENT socket: an established-but-idle session reads `hasHandshake`
     * true and has no outstanding send whether or not its replacement channel ever carried a
     * byte, so the handshake-age test confirmed a swap the new socket had not earned. The driver
     * DRAINS this sample immediately before swapping the channel
     * (`recoverAfterPathChange`/`performPathSocketRecovery`), so a `true` here is necessarily peer
     * activity NEWER than the swap. Mirrors the iOS `sawAuthenticatedPeerDatagram`.
     */
    val sawAuthenticatedPeerDatagram: Boolean = false,
    val sawObligingNonDnsSend: Boolean = false,
    val forwardingGeneration: Long = 0L,
    val forwardedNonDnsBytes: Long = 0L,
    val transmittedBytes: Long? = null,
    val receivedBytes: Long? = null,
)

/**
 * A cancellation handle for one armed one-shot timer. iOS uses `ChainedArmedTimer`.
 */
internal interface ChainedArmedTimer {
    fun cancel()
}

/**
 * Where the driver's timers come from — the Android mirror of iOS `ChainedTimerScheduling`.
 *
 * [arm] must deliver [fire] on the engine queue, and `cancel()` cannot retract a handler the queue
 * already holds, which is why the driver guards every handler with the serial it captured at arm
 * time. [scheduleTick] turns the repeating engine pump on (a non-null interval) or off (`null`).
 */
internal interface ChainedDriverTimers {
    fun arm(afterMillis: Long, fire: () -> Unit): ChainedArmedTimer
    fun scheduleTick(intervalMillis: Long?)

    /**
     * Releases any scheduler this binding owns. Called when the runtime retires, so a rebuild does
     * not accumulate one permanent timer thread per lifecycle. A binding that owns no scheduler
     * (the host-JVM test fakes) keeps the no-op default.
     */
    fun shutdown() = Unit
}

/** What the driver reports about itself. Not user copy. The Android mirror of `ChainedDriverCounters`. */
internal data class ChainedDriverCounters(
    /** Outages begun since this driver was created. */
    var outageCount: Int = 0,
    /** Attempts authorized and started. */
    var startedAttemptCount: Int = 0,
    /** Attempts refused because a ticket was stale or one was already running. */
    var stoodDownCount: Int = 0,
    /** Sessions that ended, however they ended. */
    var sessionEndCount: Int = 0,
    /** Times the monitored path went unsatisfied and the driver quiesced. */
    var offlinePathCount: Int = 0,
    /** Times a path came back, or changed interfaces, and the driver re-armed. */
    var pathRecoveryCount: Int = 0,
    /** Transports swapped under a live session — the fast path, which costs no ladder rung. */
    var rebindCount: Int = 0,
    /** Path changes where the fast path was refused and the full ladder ran instead. */
    var rebindDeclinedCount: Int = 0,
    /**
     * Rebinds that swapped but never proved themselves — not even after the one re-anchor retry —
     * and fell back to a full rebuild.
     */
    var rebindUnconfirmedCount: Int = 0,
    /**
     * Lone rebinds granted ONE more bounded re-anchor (a forced handshake + a fresh window) before
     * any rebuild.
     */
    var rebindReanchorRetryCount: Int = 0,
    /**
     * Path transitions whose SOCKET recovery was coalesced into the settle window, so a flap burst
     * does not build one socket per callback.
     */
    var coalescedPathRecoveryCount: Int = 0,
    var tunnelDnsOutageCount: Int = 0,
    var egressDeadOutageCount: Int = 0,
    var suspensionSelfHealCount: Int = 0,
)

/**
 * Drives one chained tunnel's health — the Android mirror of iOS `ChainedOutageDriver`.
 *
 * It decides when the tunnel is in an outage, spends the blackhole budget on attempts to fix it,
 * and surrenders when the budget is gone. The budget must OUTLIVE sessions and must also bound the
 * time spent FAILING to build one, so there is ONE driver per tunnel lifecycle and a fresh runner
 * per attempt, built by the [source] under this driver's control rather than by the control plane.
 *
 * ## Queue
 *
 * Every entry point runs on the one serial [engineQueue], shared with every runner it builds. All
 * timer families fire on it too. No hop.
 *
 * ## What this deliberately owns, and what it does not
 *
 * It owns the attempt lifecycle end to end: the budget across attempts, the retry ladder and its
 * backoff, the per-attempt watchdog, building and superseding the runner through [source], and the
 * surrender/fall-back decision. It does NOT own establishment, the socket, or the TUN — those stay
 * in the platform control plane, which observes only the surrender outcome to choose a DNS-only
 * rebuild versus a terminal failure.
 */
internal class ChainedOutageDriver(
    private val engineQueue: ChainedEngineQueue,
    private val timers: ChainedDriverTimers,
    private val source: ChainedSessionSource,
    private val clock: () -> Int,
    private val onSurrender: (ChainedSurrenderReason, ChainedDriverCounters) -> Unit = { _, _ -> },
    /**
     * Called on the engine queue whenever the live runner is adopted, rebuilt or retired, so a
     * consumer whose value must follow the runner's lifetime can refresh promptly. Must not block.
     */
    private val onRunnerChanged: () -> Unit = {},
    fullTunnel: Boolean = false,
    private val onDiagnostics: (ChainedNerdDiagnostics) -> Unit = {},
) {

    private val supervisor = ChainedOutageSupervisor()
    private val counters = ChainedDriverCounters()
    private val applicationHealth = ChainedApplicationHealth(fullTunnel)
    private val diagnosticWindow = ChainedDiagnosticWindow()
    private var longestUnansweredSeconds = 0
    private var latestDiagnosticSample: ChainedDriverLivenessSample? = null
    private fun publishDiagnostics() {
        val now = clock()
        val sample = latestDiagnosticSample
        val unanswered = sample?.firstUnansweredSendAtSeconds?.let { (now - it).coerceAtLeast(0) } ?: 0
        longestUnansweredSeconds = maxOf(longestUnansweredSeconds, unanswered)
        val totals = diagnosticWindow.totals(now)
        runCatching { onDiagnostics(ChainedNerdDiagnostics(counters.outageCount, counters.tunnelDnsOutageCount,
            counters.egressDeadOutageCount, applicationHealth.unansweredDestinationCount, longestUnansweredSeconds,
            totals?.first, totals?.second, rechecking = isSuspended || awaitingWakeEvidence)) }
    }

    /** A one-shot timer together with the serial its handler must present. */
    private class ArmedSlot(val serial: Long, val timer: ChainedArmedTimer)

    /**
     * An attempt authorized AND armed. The watchdog is non-optional, so the two cannot come apart:
     * a freshly built session is always bounded by the deadline it was authorized for.
     */
    private class LiveAttempt(
        val receipt: ChainedAttemptReceipt,
        val watchdog: ArmedSlot,
    )

    /** The live runner. `onRunnerChanged` fires on every assignment so no path can forget it. */
    private var runner: ChainedSessionDriving? = null
        set(value) {
            field = value
            onRunnerChanged()
        }

    private var nextSerial: Long = 0L
    private var nextRebindSerial: Long = 0L
    private var live: LiveAttempt? = null
    private var outageDeadline: ArmedSlot? = null
    private var retryDelay: ArmedSlot? = null
    private var generation: ChainedOutageGeneration? = null
    private var pendingTicket: ChainedAttemptTicket? = null
    private var lastRetiredReceipt: ChainedAttemptReceipt? = null
    private var hasSurrendered = false
    private var isRetired = false
    private var isSuspended = false
    private var awaitingWakeEvidence = false

    /**
     * Whether a suspension was actually observed, so a wake can be trusted as one.
     *
     * An unpaired wake is not evidence of a user-invisible interval — it is just a callback, and
     * refunding the budget on it would let repeated wakes extend the blackhole past the bound the
     * budget exists to be.
     */
    private var sawSleep = false
    private var suspensionBoundaryPassed = false

    /** The monitored path is unsatisfied — there is no network at all. */
    private var isOffline = false

    /** A satisfied path transition that arrived while suspended, waiting for the paired wake. */
    private var pendingPathRecovery = false

    /**
     * A coalesced SOCKET recovery that is owed but has no armed timer: interrupted by [sleep]
     * (consumed by the paired wake), or deferred past an outage the settle window overlaps
     * (satisfied by the ladder's next fresh build — see `startAuthorizedAttempt`'s adoption — or
     * consumed by [endOutage]).
     */
    private var pendingSettledRecovery = false

    /**
     * A monotonic count of satisfied path TRANSITIONS, bumped on the engine queue whenever the
     * monitored underlay actually changes.
     *
     * A ladder build selects its underlay OFF the engine queue (`ChainedTunRuntimeOwner.prepare`
     * reads `currentNetwork()` before the ~50 MB allocation), so a transition that lands between
     * that selection and the on-queue adoption leaves the freshly installed socket bound to the
     * pre-transition path. The build captures this epoch before it starts and adoption clears the
     * coalesced-recovery debt only while the epoch still matches; otherwise the debt survives so
     * `endOutage`/`wake` still run the socket recovery (finding 4048065691).
     */
    private var pathEpoch: Long = 0L

    /**
     * Armed when a rebind swapped the transport, cleared by authenticated evidence on the new one.
     * On fire, a LONE rebind spends one bounded re-anchor retry before any rebuild; a second miss,
     * or a rebind caught in a flap burst, falls back to a full rebuild.
     */
    private var rebindConfirmation: ArmedSlot? = null

    /** Whether the CURRENT rebind's confirmation has already spent its one re-anchor retry. */
    private var rebindReanchorRetried = false

    /** When the socket half of a path recovery last RAN, so a flap burst cannot run it 1:1. */
    private var lastPathSocketRecoveryAtSeconds: Int? = null

    /** Armed while a coalesced burst waits for its settle window; the fire runs the one trailing recovery. */
    private var settledPathRecovery: ArmedSlot? = null

    /**
     * The off-queue replacement-channel build currently in flight, or `0` when none. An identity
     * token rather than a timer: the build is bounded by the same liveness/outage machinery that
     * bounds any dead path (the engine queue is no longer blocked, so its ticks and deadline run),
     * and when that outage begins the token is cleared so the late channel is refused rather than
     * swapped under a runner the outage has already retired (finding 4022018493).
     */
    private var pendingRebind: Long = 0L

    /** Session ends that arrived while one was already being processed; the FIFO keeps the cause. */
    private val pendingEnds = ArrayDeque<ChainedSessionEndCause>()
    private var isProcessingEnd = false

    /**
     * No network, no user present, or torn down: the driver has nothing to blackhole, so
     * `INV-CHAIN-3`'s armed-budget rule is exempt for exactly these windows.
     */
    private val isQuiesced: Boolean get() = isSuspended || isOffline || isRetired

    // MARK: - Entry points

    /** The tunnel has a session to drive. Called once, by the wiring that builds the first one. */
    fun adopt(adopted: ChainedSessionDriving) {
        engineQueue.runSerial {
            if (isRetired) return@runSerial
            runner = adopted
            rescheduleTick()
        }
    }

    /**
     * Hands a batch to whichever runner is current, and answers whether this driver can still
     * accept batches at all — `false` once retired, so a stale read loop can stop.
     */
    fun handleOutboundBatch(packets: List<ByteArray>): Boolean = engineQueue.runSerial {
        if (isRetired) return@runSerial false
        if (isSuspended && suspensionBoundaryPassed) {
            counters.suspensionSelfHealCount++
            wake()
        }
        runner?.handleOutboundBatch(packets)
        true
    }

    /**
     * The engine pump and the outage detector. While suspended this decides NOTHING: cancelling a
     * timer cannot retract a tick the queue already holds, and a tick after the boundary would
     * re-arm exactly what the boundary quiesced.
     */
    fun tick() {
        engineQueue.runSerial {
            if (hasSurrendered || isQuiesced) return@runSerial
            runner?.tick()
            sampleLiveness()
        }
    }

    /**
     * The device is suspending: cancel the one-shots so none can surrender, and latch the
     * quiescence so a tick already queued decides nothing. A paired [wake] ends the outage rather
     * than resuming it, so the budget stays bounded.
     */
    fun sleep() {
        engineQueue.runSerial {
            sawSleep = true
            suspensionBoundaryPassed = false
            applicationHealth.resetWindow(clearHolds = true)
            timers.scheduleTick(null)
            outageDeadline?.timer?.cancel()
            outageDeadline = null
            retryDelay?.timer?.cancel()
            retryDelay = null
            pendingTicket = null
            retireLiveAttempt()
            // The rebind confirmation dies with the suspension: a deadline already due cannot be
            // retracted, and one that fires while the device is going dark can only surrender.
            rebindConfirmation?.timer?.cancel()
            rebindConfirmation = null
            // A pending off-queue channel build is fenced by the suspension: its late delivery
            // must not swap a socket while the process promised the engine quiet.
            pendingRebind = 0L
            // A coalesced recovery survives the suspension as its own latch, so the burst's FINAL
            // path still gets its socket instead of the wake re-handshaking into the pre-burst one.
            if (settledPathRecovery != null) {
                pendingSettledRecovery = true
            }
            settledPathRecovery?.timer?.cancel()
            settledPathRecovery = null
            isSuspended = true
            awaitingWakeEvidence = true
            publishDiagnostics()
        }
    }

    /**
     * The device has woken, and the budget is REFUNDED if an outage was in progress.
     *
     * A sleep is not a flap: nothing is blackholed while suspended, and without the refund
     * sleeping past the midpoint of an outage would surrender on wake without a single handshake.
     * An UNPAIRED wake is not a suspension this driver saw, so it refunds nothing.
     */
    /** Called after the platform has completed suspension; queued earlier packets cannot wake us. */
    fun confirmSuspensionBoundary() {
        engineQueue.runSerial {
            if (isSuspended) suspensionBoundaryPassed = true
        }
    }

    fun wake() {
        engineQueue.runSerial {
            isSuspended = false
            suspensionBoundaryPassed = false
            if (hasSurrendered || isRetired) {
                sawSleep = false
                return@runSerial
            }
            val observedSuspension = sawSleep
            sawSleep = false
            if (!observedSuspension) {
                rescheduleTick()
                return@runSerial
            }
            runner?.resetLivenessAfterSuspension()
            applicationHealth.resetWindow(clearHolds = true)
            val now = clock()
            if (supervisor.isTimingAnOutage) {
                generation?.let { supervisor.endOutage(it) }
                generation = null
                retireLiveAttempt()
                outageDeadline?.timer?.cancel()
                outageDeadline = null
                retryDelay?.timer?.cancel()
                retryDelay = null
                pendingTicket = null
            }
            // STILL OFFLINE MEANS STILL QUIESCED: `wake()` has no authority over the path latch,
            // and rebuilding here would arm retries against a network that is still absent.
            if (isOffline) return@runSerial
            // A transition that arrived while suspended is consumed HERE rather than dropped.
            if (pendingPathRecovery) {
                pendingPathRecovery = false
                pendingSettledRecovery = false
                recoverAfterPathChange(now)
                return@runSerial
            }
            if (pendingSettledRecovery) {
                pendingSettledRecovery = false
                performPathSocketRecovery(now)
                return@runSerial
            }
            // NO RUNNER MEANS REBUILD, whether or not an outage was being timed. During a retry
            // delay there is none because the ladder has not built one yet; after a session end
            // there is none because that end retired it.
            if (runner == null) {
                beginOutage(now)
                return@runSerial
            }
            // A surviving runner is re-armed, not merely kept: the pre-sleep liveness is stale,
            // and a handshake may already be in flight, so force a fresh one.
            runner?.forceHandshake()
            rescheduleTick()
        }
    }

    // MARK: - Path changes

    /**
     * The monitored underlay changed, or came back. The Android mirror of iOS
     * `ChainedOutageDriver.pathChanged`.
     *
     * A SATISFIED transition (a roam, or the same network returning) is evidence, not fault: the
     * socket is pinned to the old underlay and fails silently, so the runner's transport is
     * replaced — by a rebind under the live session, or by the ladder when there is no session —
     * rather than the lifecycle being torn down. An UNSATISFIED transition means there is no
     * network at all, so there is nothing to blackhole and the driver quiesces until it returns.
     *
     * It acts only on a TRANSITION: repeated satisfied updates are delivered by the platform for
     * reasons that are not transitions, and clearing the silence clock on each would postpone
     * detection indefinitely.
     */
    fun pathChanged(satisfied: Boolean, interfacesChanged: Boolean) {
        engineQueue.runSerial {
            if (hasSurrendered || isRetired) return@runSerial

            if (!satisfied) {
                if (isOffline) return@runSerial
                isOffline = true
                applicationHealth.resetWindow(clearHolds = true)
                counters.offlinePathCount += 1
                // A DEFERRED RECOVERY IS SUPERSEDED BY GOING OFFLINE AGAIN: leaving it latched
                // would make a later unrelated wake consume it and rebuild for nothing.
                pendingPathRecovery = false
                pendingSettledRecovery = false
                outageDeadline?.timer?.cancel()
                outageDeadline = null
                retryDelay?.timer?.cancel()
                retryDelay = null
                pendingTicket = null
                retireLiveAttempt()
                rebindConfirmation?.timer?.cancel()
                rebindConfirmation = null
                // The coalesced recovery is CANCELLED, not deferred: the path it was going to
                // rebuild onto is gone. A later satisfied transition re-enters recovery itself.
                settledPathRecovery?.timer?.cancel()
                settledPathRecovery = null
                // A pending off-queue channel build is fenced by going offline: its underlay is
                // gone, and its late delivery must not swap a socket onto it.
                pendingRebind = 0L
                generation?.let { supervisor.endOutage(it) }
                timers.scheduleTick(null)
                return@runSerial
            }

            val wasOffline = isOffline
            isOffline = false
            // ONLY A TRANSITION ACTS.
            if (!wasOffline && !interfacesChanged) {
                rescheduleTick()
                return@runSerial
            }
            // A SATISFIED TRANSITION INVALIDATES AN IN-FLIGHT LADDER BUILD'S UNDERLAY CAPTURE: the
            // build selects its underlay off-queue, so a transition landing between that selection
            // and adoption leaves the installed socket on the pre-transition path. Bump the epoch
            // so adoption can tell the build predates the transition (finding 4048065691).
            pathEpoch += 1L
            applicationHealth.resetWindow(clearHolds = !supervisor.isTimingAnOutage)
            // DEFERRED WHILE SUSPENDED, not performed: recovering there would arm one-shots into
            // a quiescing process and drive the engine through the interval sleep promised quiet.
            if (isSuspended) {
                pendingPathRecovery = true
                return@runSerial
            }
            recoverAfterPathChange(clock())
        }
    }

    /**
     * Rebuilds onto the path the device is now on.
     *
     * The socket is pinned to a specific underlay at construction and fails silently when that
     * underlay goes away, so a surviving runner has to be re-bound rather than merely re-handshaked.
     * A rebind under the live session is the fast path; the full ladder is the fallback.
     */
    private fun recoverAfterPathChange(now: Int) {
        counters.pathRecoveryCount += 1
        // Discard the pre-transition sample: a peer answer latched on the outgoing socket must not
        // be read by the next tick as evidence on the replacement.
        runner?.takeLivenessSample()
        // THE SETTLE WINDOW. One recovery leads a flap burst immediately; transitions inside the
        // window coalesce into the single trailing recovery, which lands on the burst's FINAL path.
        val last = lastPathSocketRecoveryAtSeconds
        if (last != null && now - last < PATH_RECOVERY_SETTLE_SECONDS) {
            counters.coalescedPathRecoveryCount += 1
            // NEVER ARMED WHILE AN OUTAGE IS BEING TIMED: the ladder owns recovery then, and a
            // fire landing mid-ladder would retire the live replacement. Recorded as a debt the
            // ladder's next fresh build satisfies (cleared by `ChainedSessionDelivery.adopt`), or
            // that [endOutage] consumes.
            if (supervisor.isTimingAnOutage) {
                pendingSettledRecovery = true
            } else if (settledPathRecovery == null) {
                settledPathRecovery = armTimer(last + PATH_RECOVERY_SETTLE_SECONDS) { serial ->
                    settledPathRecoveryFired(serial)
                }
            }
            return
        }
        performPathSocketRecovery(now)
    }

    /** The socket half of a path recovery — the part the settle window rate-bounds. */
    private fun performPathSocketRecovery(now: Int) {
        lastPathSocketRecoveryAtSeconds = now
        settledPathRecovery?.timer?.cancel()
        settledPathRecovery = null
        runner?.takeLivenessSample()
        // THE FAST PATH: only outside an outage, because once one is being timed the ladder owns
        // recovery (and `startOutageClock` may already have nulled the runner).
        if (runner != null && !supervisor.isTimingAnOutage && attemptRebind(now)) return
        // `beginOutage` shuts the runner down and nulls it, so this is the rebuild.
        beginOutage(now)
    }

    /** The settle window ended with coalesced transitions inside it — run the one trailing recovery. */
    private fun settledPathRecoveryFired(serial: Long) {
        if (hasSurrendered || isQuiesced || settledPathRecovery?.serial != serial) return
        settledPathRecovery = null
        // AN OUTSTANDING CONFIRMATION RESOLVES FIRST: it and this timer share one interval, so
        // this fire landing first must not cancel a still-pending confirmation and grant the next
        // socket a fresh window.
        if (rebindConfirmation != null) {
            sampleLiveness()
            if (supervisor.isTimingAnOutage) return
            if (rebindConfirmation != null) {
                rebindConfirmation?.timer?.cancel()
                rebindConfirmation = null
                counters.rebindUnconfirmedCount += 1
                beginOutage(clock())
                return
            }
        }
        performPathSocketRecovery(clock())
    }

    /**
     * Swaps the transport under a live session, and arms a deadline to prove it worked.
     *
     * THE CONFIRMATION IS NOT OPTIONAL: the keepalive obliges the peer nothing, so its going out
     * is not evidence the new socket works — and a claimed `0.0.0.0/0` with nothing armed is the
     * blackhole `INV-CHAIN-3` exists to forbid. Every failure direction falls back rather than
     * forward: a channel that cannot be built, a runner that refuses, and a session with no
     * current keypair all end in a full rebuild.
     */
    private fun attemptRebind(now: Int): Boolean {
        val current = runner ?: return false
        // FENCE BY IDENTITY, not a timer: the build is already bounded by the same liveness/outage
        // machinery that bounds any dead path, and the queue is no longer blocked, so its ticks and
        // deadline run. A newer rebind, a suspension, or the outage that machinery opens clears or
        // replaces the token, so a channel delivered after any of those is refused rather than
        // swapped under a runner that is no longer current (finding 4022018493).
        nextRebindSerial += 1
        val rebindSerial = nextRebindSerial
        pendingRebind = rebindSerial
        var delivered = false
        // The production source delivers from its off-queue `sessionBuilds` scope, so every body
        // hops back onto the one serial [engineQueue] before touching driver state — exactly like
        // `ChainedSessionDelivery`. A fake source that delivers inline (already on the queue) runs
        // this body inline, because `runSerial` is re-entrant.
        val delivery = object : ChainedChannelDelivery {
            override fun adopt(channel: ChainedUpstreamChannel): Boolean = engineQueue.runSerial {
                delivered = true
                if (pendingRebind != rebindSerial) return@runSerial false
                if (hasSurrendered || isQuiesced || runner !== current) {
                    pendingRebind = 0L
                    return@runSerial false
                }
                pendingRebind = 0L
                // THE SWAP HAPPENS HERE, not at detection: drain the pre-swap sample NOW so a peer
                // answer latched on the outgoing socket is not read as evidence on the replacement.
                current.takeLivenessSample()
                when (current.adoptChannel(channel)) {
                    ChainedRebindOutcome.REBOUND -> {
                        counters.rebindCount += 1
                        rebindReanchorRetried = false
                        rebindConfirmation?.timer?.cancel()
                        rebindConfirmation = armTimer(clock() + REBIND_CONFIRMATION_SECONDS) { serial ->
                            rebindUnconfirmed(serial)
                        }
                        rescheduleTick()
                        true
                    }

                    ChainedRebindOutcome.NO_CURRENT_SESSION,
                    ChainedRebindOutcome.REFUSED,
                    -> {
                        counters.rebindDeclinedCount += 1
                        beginOutage(clock())
                        true
                    }
                }
            }

            override fun failed() = engineQueue.runSerial {
                delivered = true
                if (pendingRebind != rebindSerial) return@runSerial
                pendingRebind = 0L
                if (hasSurrendered || isQuiesced || runner !== current) return@runSerial
                counters.rebindDeclinedCount += 1
                beginOutage(clock())
            }
        }
        val started = runCatching { source.makeChannel(delivery) }.getOrDefault(false)
        if (!started && !delivered) {
            // No build could even start (no eligible underlay): fall back to the ladder now.
            if (pendingRebind == rebindSerial) pendingRebind = 0L
            return false
        }
        return true
    }

    /**
     * The rebind did not prove itself in time. A LONE rebind gets one bounded re-anchor retry
     * first; a second miss (or a rebind caught in a flap burst) falls back to a full rebuild.
     */
    private fun rebindUnconfirmed(serial: Long) {
        if (hasSurrendered || isQuiesced || rebindConfirmation?.serial != serial) return
        // ONE LAST SAMPLE BEFORE DECIDING: evidence the runner has already latched is not evidence
        // this driver has read. `sampleLiveness` clears `rebindConfirmation` when it finds
        // evidence, so the guard below is how the answer comes back.
        sampleLiveness()
        if (rebindConfirmation?.serial != serial) return
        // ONE BOUNDED RE-ANCHOR RETRY, only for a LONE rebind and only with no trailing recovery
        // due: a flap burst keeps its bounded fallback, while a single lost re-anchor keepalive on
        // a working path does not cost a full rebuild.
        if (
            !rebindReanchorRetried &&
            runner != null &&
            settledPathRecovery == null &&
            !pendingSettledRecovery
        ) {
            rebindReanchorRetried = true
            counters.rebindReanchorRetryCount += 1
            runner?.forceHandshake()
            rebindConfirmation = armTimer(clock() + REBIND_REANCHOR_RETRY_SECONDS) { next ->
                rebindUnconfirmed(next)
            }
            return
        }
        rebindConfirmation = null
        counters.rebindUnconfirmedCount += 1
        beginOutage(clock())
    }

    /**
     * The tunnel is tearing down: shut the runner down, disarm everything, and go inert.
     *
     * TERMINAL, unlike [sleep]: nothing clears `isRetired`, so every entry point is a no-op
     * afterwards. The one-shot SLOTS are cleared, not merely cancelled, because a cancelled timer
     * whose slot is still installed still satisfies its handler's serial guard.
     */
    fun retire() {
        engineQueue.runSerial {
            isRetired = true
            timers.scheduleTick(null)
            outageDeadline?.timer?.cancel()
            outageDeadline = null
            retryDelay?.timer?.cancel()
            retryDelay = null
            pendingTicket = null
            pendingEnds.clear()
            rebindConfirmation?.timer?.cancel()
            rebindConfirmation = null
            settledPathRecovery?.timer?.cancel()
            settledPathRecovery = null
            pendingPathRecovery = false
            pendingSettledRecovery = false
            // A pending off-queue channel build is fenced by retirement: its late delivery must be
            // refused, and the source closes the channel.
            pendingRebind = 0L
            retireLiveAttempt()
            runner?.shutdown()
            runner = null
        }
    }

    /**
     * A runner reported its own session end.
     *
     * Queued with its cause and drained by the outermost frame: the runner reaches this from
     * inside its own loops, so a re-entrant delivery is ordinary rather than exceptional.
     */
    fun sessionEnded(cause: ChainedSessionEndCause) {
        engineQueue.runSerial {
            if (isQuiesced) {
                counters.sessionEndCount += 1
                runner?.shutdown()
                runner = null
                return@runSerial
            }
            pendingEnds.addLast(cause)
            if (isProcessingEnd) return@runSerial
            isProcessingEnd = true
            try {
                while (pendingEnds.isNotEmpty()) {
                    finishAttempt(pendingEnds.removeFirst(), clock())
                }
            } finally {
                isProcessingEnd = false
            }
        }
    }

    // MARK: - Liveness

    /**
     * Reconciles the outage with the runner's link liveness.
     *
     * A handshake-less runner has no progress to watch, so the outage (and its watchdog) simply
     * stays open. An established runner that stopped making progress opens the outage FROM the
     * first unanswered send — not from detection — so the detection window and the budget are one
     * window, not two.
     */
    private fun sampleLiveness() {
        val sample = runner?.takeLivenessSample() ?: return
        val now = clock()
        latestDiagnosticSample = sample
        diagnosticWindow.observe(now, sample.forwardingGeneration, sample.transmittedBytes, sample.receivedBytes)
        try {
            if (!sample.hasHandshake) {
                if (!supervisor.isTimingAnOutage) {
                    beginOutage(now)
                } else {
                    act(supervisor.action(now, endedBy = null, receipt = null))
                }
                return
            }
            // A REBIND IS CONFIRMED BY PEER ACTIVITY NEWER THAN THE CHANNEL SWAP, not by the keepalive
            // having been emitted and not by the session's handshake age: the keepalive obliges the
            // peer nothing, so emitting one proves only that the socket accepted a write — which a
            // black-holed socket also does — and `hasHandshake` is session age, true for an idle
            // established session regardless of which socket last carried a byte. Testing it cleared
            // the confirmation on the first tick after a path change even when the replacement socket
            // had received nothing, so a dead replacement bypassed the 3 s fallback and blackholed the
            // first subsequent traffic until the longer stall detector reacted.
            //
            // `sawAuthenticatedPeerDatagram` is read-and-clear, and the driver drains the sample
            // immediately before `attemptRebind` swaps the channel (see `performPathSocketRecovery`),
            // so evidence read here belongs to the replacement. A genuinely dead replacement still
            // falls back: user traffic advances `txBytes` with `rxBytes` flat, no authenticated
            // datagram arrives, and the confirmation window is missed.
            if (sample.sawAuthenticatedPeerDatagram) {
                awaitingWakeEvidence = false
                rebindConfirmation?.timer?.cancel()
                rebindConfirmation = null
            }
            val unanswered = sample.firstUnansweredSendAtSeconds
            val linkStalled = unanswered != null && now - unanswered >= ChainedLivenessPolicy.LIVENESS_STALL_WINDOW_SECONDS
            if (applicationHealth.sample(sample, now, canDeclare = !supervisor.isTimingAnOutage && !linkStalled)) {
                counters.egressDeadOutageCount++
                beginOutage(now)
                return
            }
            if (unanswered == null) {
                if (supervisor.isTimingAnOutage && !applicationHealth.holdsOutage) endOutage()
                return
            }
            if (now - unanswered < ChainedLivenessPolicy.LIVENESS_STALL_WINDOW_SECONDS) return
            if (!supervisor.isTimingAnOutage) {
                beginOutage(unanswered)
            } else {
                act(supervisor.action(now, endedBy = null, receipt = null))
            }
        } finally { publishDiagnostics() }
    }

    /** Capture when the lookup starts: a timeout from an old path cannot seed a new window. */
    fun dnsObservationEpoch(): Long = engineQueue.runSerial { applicationHealth.dnsEpoch }

    /** A null name key means a source-matched resolver reply, not necessarily DNS success. */
    fun reportTunnelDns(epoch: Long, unansweredNameKey: String?) {
        engineQueue.runSerial {
            if (hasSurrendered || isQuiesced || epoch != applicationHealth.dnsEpoch) return@runSerial
            if (unansweredNameKey == null) {
                val held = applicationHealth.dnsAnswered(epoch)
                if (held && !applicationHealth.holdsOutage && supervisor.isTimingAnOutage && runner != null) endOutage()
            } else if (applicationHealth.dnsUnanswered(
                    epoch, unansweredNameKey, clock(),
                    canDeclare = runner != null && !supervisor.isTimingAnOutage,
                )) {
                counters.tunnelDnsOutageCount++
                beginOutage(clock())
            }
            publishDiagnostics()
        }
    }

    // MARK: - Queries

    /** Whether the tunnel is currently timing an outage. */
    fun isTimingAnOutage(): Boolean = engineQueue.runSerial { supervisor.isTimingAnOutage }

    /** Whether chaining has been abandoned for the rest of the lifecycle. */
    fun hasSurrenderedChaining(): Boolean = engineQueue.runSerial { hasSurrendered }

    /** A snapshot of the driver's tallies. */
    fun snapshotCounters(): ChainedDriverCounters = engineQueue.runSerial { counters.copy() }

    // MARK: - The outage

    /**
     * Starts the clock, arms the budget, and SYNTHESIZES the session end that starts the ladder.
     *
     * The synthesis is not a convenience: a ticket is minted only inside the supervisor's retry
     * branch, which needs a non-nil session end, and on a simply dead path the engine reports
     * nothing until long after the budget is gone. Without this the whole budget would be
     * `CarryOn` and then surrender, with ZERO attempts made. `connectionExpired` is exactly what
     * the data path produces for a dead session — the driver reports the same fact earlier.
     */
    private fun beginOutage(now: Int) {
        startOutageClock(now)
        val cause = ChainedSessionEndCause.engineVerdict(WireGuardEngineError.ConnectionExpired)
            ?: return
        act(supervisor.action(now, endedBy = cause, receipt = lastRetiredReceipt))
    }

    /** Starts the clock and arms the budget, WITHOUT deciding anything about a session. */
    private fun startOutageClock(now: Int) {
        val wasAlreadyTiming = supervisor.isTimingAnOutage
        generation = supervisor.beginOutage(now)
        if (!wasAlreadyTiming) counters.outageCount += 1
        // THE DEADLINE IS ANCHORED TO THE FIRST FAILURE. The supervisor preserves the original
        // budget start across a re-`beginOutage` (a satisfied path change mid-outage), so re-arming
        // the deadline at `now + MAXIMUM_BLACKHOLE_SECONDS` would silently extend the blackhole
        // toward a second full window — and a transition that also retires the pending retry could
        // leave no tick left to evaluate the original deadline. Only a NEWLY started outage (or a
        // missing timer) arms it; an outage already being timed keeps its own deadline.
        if (!wasAlreadyTiming || outageDeadline == null) {
            outageDeadline?.timer?.cancel()
            outageDeadline = armTimer(now + ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS) { serial ->
                outageDeadlineFired(serial)
            }
        }
        // THE REBIND CONFIRMATION DIES WITH THE SESSION IT WAS CONFIRMING: once an outage is being
        // timed the ladder owns recovery, and a confirmation left installed would fire mid-ladder
        // and retire whatever replacement attempt is live.
        rebindConfirmation?.timer?.cancel()
        rebindConfirmation = null
        // The trailing recovery dies with the same session era: its next attempt builds a fresh
        // socket on the CURRENT path, so a deferred rebuild would spend a rung on a socket the
        // ladder was about to build anyway.
        settledPathRecovery?.timer?.cancel()
        settledPathRecovery = null
        // The pending off-queue channel build dies with the same session era too: the ladder
        // builds its own channel, and a late rebind channel must be refused, not swapped under
        // the runner this outage is about to retire.
        pendingRebind = 0L
        retireLiveAttempt()
        runner?.shutdown()
        runner = null
        rescheduleTick()
    }

    private fun outageDeadlineFired(serial: Long) {
        if (hasSurrendered || !supervisor.isTimingAnOutage) return
        if (outageDeadline?.serial != serial) return
        act(supervisor.action(clock(), endedBy = null, receipt = null))
    }

    private fun act(action: ChainedOutageAction) {
        when (action) {
            is ChainedOutageAction.CarryOn -> Unit
            is ChainedOutageAction.Surrender -> reportSurrender(action.reason)
            is ChainedOutageAction.Attempt -> {
                pendingTicket = action.ticket
                retryDelay?.timer?.cancel()
                retryDelay = armTimer(clock() + action.afterSeconds) { serial ->
                    startAuthorizedAttempt(serial)
                }
            }
        }
    }

    /**
     * Authorizes an attempt and ARMS ITS WATCHDOG in one indivisible step.
     *
     * The watchdog and the live attempt are recorded BEFORE the build is started: the build can
     * fail, and arming afterwards would leave an authorized attempt with nothing bounding it
     * while the supervisor's in-flight flag stayed set, wedging the ladder forever.
     *
     * The build itself does NOT run here. `source.makeSession` starts it and returns immediately,
     * delivering the result back through a per-attempt [ChainedSessionDelivery] once the
     * non-cancellable allocation — which runs OFF the engine queue — returns. That is what keeps
     * this attempt's watchdog free to fire while a slow or hung allocation is in flight; a build
     * that blocks the queue would defer the very surrender that bounds the blackhole.
     */
    private fun startAuthorizedAttempt(serial: Long) {
        if (hasSurrendered || retryDelay?.serial != serial) return
        val now = clock()
        val ticket = pendingTicket ?: return
        pendingTicket = null
        val authorization = when (val decision = supervisor.authorizeAttemptStartingNow(now, ticket)) {
            is ChainedAttemptAuthorization.StandDown -> {
                counters.stoodDownCount += 1
                return
            }

            is ChainedAttemptAuthorization.Refused -> {
                reportSurrender(decision.reason)
                return
            }

            is ChainedAttemptAuthorization.Authorized -> decision
        }
        val watchdog = armTimer(authorization.deadlineAtSeconds) { watchdogSerial ->
            watchdogFired(watchdogSerial)
        }
        val attempt = LiveAttempt(
            receipt = authorization.receipt,
            watchdog = watchdog,
        )
        live = attempt
        counters.startedAttemptCount += 1

        // ---- Everything below may fail, throw, or never happen. ----

        // CAPTURED BEFORE THE BUILD STARTS, which is before the source selects its underlay
        // off-queue. If the path transitions while the allocation runs, the fresh socket may be
        // bound to the pre-transition path even though it is adopted after the transition, so the
        // debt must not be erased (finding 4048065691).
        val buildPathEpoch = pathEpoch
        // The fence is the attempt identity captured here, NOT a re-read of `live`: a session
        // allocated for this attempt and delivered after the attempt was retired, surrendered, or
        // replaced by a newer one is refused (and closed by the source) instead of installed.
        val delivery = object : ChainedSessionDelivery {
            override fun adopt(install: () -> ChainedSessionDriving): Boolean = engineQueue.runSerial {
                if (isRetired || hasSurrendered || live !== attempt) return@runSerial false
                val adopted = install()
                runner = adopted
                // The fresh attempt bound a new socket on the CURRENT path ONLY IF no transition
                // landed since the build started. A transition after the off-queue underlay
                // selection leaves the socket on the pre-transition path, so keep the coalesced
                // recovery debt: `endOutage`/`wake` must still run the socket recovery instead of
                // the debt being erased for a socket that never bound to the new path.
                if (pathEpoch == buildPathEpoch) {
                    pendingSettledRecovery = false
                }
                rescheduleTick()
                adopted.forceHandshake()
                true
            }

            override fun failed(failure: Throwable?) = engineQueue.runSerial {
                if (isRetired || hasSurrendered || live !== attempt) return@runSerial
                // The error is carried, not discarded: a transient build failure spends a rung
                // exactly like a wire condition, while a permanent one (or a `null` refusal)
                // surrenders.
                finishAttempt(ChainedSessionEndCause.buildFailure(failure), clock())
            }
        }
        try {
            source.makeSession(
                events = ChainedSessionEvents { cause -> sessionEnded(cause) },
                delivery = delivery,
            )
        } catch (failure: Throwable) {
            // A source that could not even START the off-queue build reports it synchronously;
            // it travels the same triage as an asynchronous [ChainedSessionDelivery.failed].
            delivery.failed(failure)
        }
    }

    private fun watchdogFired(serial: Long) {
        if (hasSurrendered) return
        val attempt = live ?: return
        if (attempt.watchdog.serial != serial) return
        act(supervisor.action(clock(), endedBy = null, receipt = null))
    }

    /** The mirror of the authorization step, and equally indivisible. */
    private fun finishAttempt(cause: ChainedSessionEndCause, now: Int) {
        counters.sessionEndCount += 1
        // A session can die with no outage in progress. The supervisor guards `action` on an
        // active outage, and by now the runner has been shut down and the tick cancelled, so
        // without this no later liveness sample could start one either.
        if (!supervisor.isTimingAnOutage) startOutageClock(now)
        // The watchdog is cancelled BEFORE the attempt is forgotten, always, in one body.
        retireLiveAttempt()
        runner?.shutdown()
        runner = null
        rescheduleTick()
        act(supervisor.action(now, endedBy = cause, receipt = lastRetiredReceipt))
    }

    private fun retireLiveAttempt() {
        val retired = live ?: return
        retired.watchdog.timer.cancel()
        live = null
        // REMEMBERED, not merely recorded: the session this attempt started usually outlives the
        // attempt, so a later outage's synthesized end has to be able to name it.
        lastRetiredReceipt = retired.receipt
        supervisor.recordAttempt(retired.receipt)
    }

    private fun reportSurrender(reason: ChainedSurrenderReason) {
        if (hasSurrendered) return
        hasSurrendered = true
        live?.watchdog?.timer?.cancel()
        live = null
        outageDeadline?.timer?.cancel()
        outageDeadline = null
        retryDelay?.timer?.cancel()
        retryDelay = null
        pendingTicket = null
        rebindConfirmation?.timer?.cancel()
        rebindConfirmation = null
        settledPathRecovery?.timer?.cancel()
        settledPathRecovery = null
        pendingRebind = 0L
        runner?.shutdown()
        runner = null
        timers.scheduleTick(null)
        onSurrender(reason, counters)
    }

    private fun endOutage() {
        val current = generation ?: return
        applicationHealth.resetWindow(clearHolds = true)
        supervisor.endOutage(current)
        generation = null
        retireLiveAttempt()
        outageDeadline?.timer?.cancel()
        outageDeadline = null
        retryDelay?.timer?.cancel()
        retryDelay = null
        pendingTicket = null
        rescheduleTick()
        // This path ends the outage on EVIDENCE and keeps the runner, so no ladder attempt will
        // ever bind the settled path — and the confirming evidence can arrive through the old
        // interface's brief afterlife, leaving the session on the pre-transition socket until
        // silence detection. The owed socket half therefore runs now, outside the outage.
        if (pendingSettledRecovery) {
            pendingSettledRecovery = false
            performPathSocketRecovery(clock())
        }
    }

    /**
     * The engine pump's cadence, a pure function of driver state, re-scheduled only on a
     * transition. No runner means nothing to pump, so the tick is cancelled outright.
     */
    private fun rescheduleTick() {
        if (isQuiesced || runner == null || hasSurrendered) {
            timers.scheduleTick(null)
            return
        }
        timers.scheduleTick(
            if (supervisor.isTimingAnOutage) {
                OUTAGE_TICK_INTERVAL_MILLIS
            } else {
                ESTABLISHED_TICK_INTERVAL_MILLIS
            },
        )
    }

    /**
     * Arms a one-shot timer and hands its OWN serial to the handler.
     *
     * The serial is captured at arming time, not read when the timer fires: a guard that compares
     * the live attempt's serial against itself cannot fail. [ChainedArmedTimer.cancel] does not
     * retract a handler already delivered to the queue, so this is the only real guard.
     */
    private fun armTimer(atSeconds: Int, fire: (Long) -> Unit): ArmedSlot {
        nextSerial += 1
        val serial = nextSerial
        val delayMillis = (atSeconds.toLong() - clock().toLong()).coerceAtLeast(0L) * 1_000L
        val timer = timers.arm(delayMillis) { fire(serial) }
        return ArmedSlot(serial, timer)
    }

    private companion object {
        /** The pump cadence while an outage is being timed. */
        const val OUTAGE_TICK_INTERVAL_MILLIS = 250L

        /** The pump cadence while a session is established. */
        const val ESTABLISHED_TICK_INTERVAL_MILLIS = 500L

        /**
         * How long a rebind has to prove itself before the driver falls back to a full rebuild.
         * Well under the liveness stall window, and a small fraction of the blackhole budget, so a
         * rebind that fails still leaves most of the budget for the ladder that follows it.
         */
        const val REBIND_CONFIRMATION_SECONDS = 3

        /**
         * The re-anchor RETRY's confirmation window — longer than [REBIND_CONFIRMATION_SECONDS] by
         * derivation: the retry re-anchors with a forced handshake, whose lost initiation the
         * engine retransmits only at `REKEY_TIMEOUT` (5 s), so a 3 s window would expire before the
         * retransmit.
         */
        const val REBIND_REANCHOR_RETRY_SECONDS = 7

        /**
         * The shortest interval between two path-triggered SOCKET recoveries. Equal to the
         * confirmation window by derivation: a socket replaced faster than its own confirmation can
         * close is superseded before it can prove anything, so a flap burst is rate-bounded to one
         * leading recovery and one trailing one.
         */
        const val PATH_RECOVERY_SETTLE_SECONDS = REBIND_CONFIRMATION_SECONDS
    }
}
