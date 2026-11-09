package com.lavasec.app.vpn

import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.platform.ProtectionFailure
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.vpnReloadIsAdmitted
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal sealed interface VpnServiceCommand {
    data class Start(val origin: StartOrigin) : VpnServiceCommand
    data object Stop : VpnServiceCommand
    data object Revoke : VpnServiceCommand
    data class Pause(val duration: PauseDuration) : VpnServiceCommand
    data object Resume : VpnServiceCommand
    data object Reconnect : VpnServiceCommand

    /**
     * Rebuilds the installed runtime, but only while [expectedServiceGeneration] is still the
     * installed session generation.
     *
     * A chained preference write snapshots the generation it saw and passes it here, so a reload
     * queued behind a stop (which retired that generation) becomes a no-op instead of resurrecting
     * protection. `null` is the internal runtime rebuild, which is bound to the live runtime and
     * therefore always admitted.
     */
    data class Reload(val expectedServiceGeneration: Long?) : VpnServiceCommand
}

internal enum class VpnCommandResult {
    /** The command was applied to a runtime the service must keep. */
    Sticky,

    /** The command was terminal: the service must finish this start. */
    NotSticky,

    /**
     * The command was a generation-rejected reload. It is a harmless no-op: a newer start owns the
     * runtime, and finishing this start would remove its foreground notification, stop the service,
     * and close that runtime.
     */
    Superseded,
}

/**
 * Whether a command result must finish the service start.
 *
 * [VpnCommandResult.Superseded] is deliberately NOT terminal, so `onStartCommand` leaves the newer
 * runtime's service running.
 */
internal fun isTerminalStart(result: VpnCommandResult): Boolean = when (result) {
    VpnCommandResult.Sticky,
    VpnCommandResult.Superseded,
    -> false

    VpnCommandResult.NotSticky -> true
}

internal fun interface VpnForegroundAdmission {
    /** Must complete the platform foreground-service admission synchronously. */
    fun enter()
}

internal fun interface VpnStatePublication {
    fun publish(state: ProtectionState): Boolean
}

internal fun interface VpnHealthPublication {
    fun publish(status: VpnHealthStatus): Boolean
}

internal data class VpnOwnedRuntimeRequest(
    val owner: VpnServiceOwner,
    val origin: StartOrigin,
    val host: CredentialHostSnapshot,
    val attachment: CredentialVpnAttachment,
    val publication: VpnStatePublication,
    val healthPublication: VpnHealthPublication = VpnHealthPublication { false },
    val terminalPublication: VpnRuntimeTerminalPublication =
        VpnRuntimeTerminalPublication { _, _ -> false },
    /**
     * Clears the process-wide chained-activity mode when [owner] is still the service's current
     * owner.
     *
     * A runtime's teardown (`ProductionVpnOwnedRuntime.finishClose`) runs on whatever thread closed
     * it — including a superseded asynchronous candidate's `finally { candidate.close() }` after a
     * newer start installed a replacement. Clearing the mode unconditionally there would overwrite
     * the replacement's live `DNS_ONLY`/`CHAINED` publication, and because chained activity is
     * deduplicated and DNS-only publishes only at installation, the UI could stay incorrectly
     * inactive/recovering with no later event to correct it. Implemented by
     * [VpnServiceCommandOwner] to do the check-and-clear under its monitor, so a clear for a
     * superseded owner is a no-op while `stop`/terminal paths still clear.
     */
    val clearChainedActivityWhenCurrent: (VpnServiceOwner) -> Unit = {},
)

internal fun interface VpnOwnedRuntimeFactory {
    /** Construction may establish platform resources and is therefore after foreground admission. */
    fun create(request: VpnOwnedRuntimeRequest): VpnOwnedRuntime
}

internal fun interface VpnRuntimeTerminalPublication {
    fun publish(runtime: VpnOwnedRuntime, failure: ProtectionFailure): Boolean
}

internal fun interface VpnTerminalServiceExit {
    fun exit()
}

internal interface VpnOwnedRuntime : AutoCloseable {
    /**
     * Resolves anything the runtime needs before the predecessor's tunnel is retired.
     *
     * A reload retires the predecessor before [start]; a chained hostname lookup performed in
     * [start] would then run with no tunnel installed, so a slow/timed-out resolver would let
     * device traffic bypass protection on non-lockdown devices. The command owner calls this while
     * the predecessor still owns its tunnel. Best-effort: any refusal simply makes [start] fall
     * back to DNS-only.
     *
     * Suspending so the lookup runs under structured cancellation rather than blocking the caller;
     * the command owner invokes it from its own scope so `onStartCommand` never waits on the
     * resolver.
     */
    suspend fun preflight() = Unit

    fun start()
    fun pause(duration: PauseDuration) = Unit
    fun resume() = Unit
    suspend fun reconnect() = Unit

    /**
     * Invalidates any in-flight tunnel-lifecycle operation on this runtime (bumps its engine-queue
     * generation) without tearing it down.
     *
     * The command owner calls this synchronously — from the service thread/monitor — when it
     * supersedes a pending candidate or stops the installed runtime, so a candidate that is
     * mid-establishment cannot complete its claim after the decision to discard it. It must be
     * cheap and non-blocking; the actual teardown still happens through [close]. Non-production
     * runtimes may leave it as the no-op default.
     */
    fun supersede() = Unit
    override fun close()
}

/** Serial Android-service owner; every callback is fenced by its exact installed session. */
internal class VpnServiceCommandOwner(
    private val foreground: VpnForegroundAdmission,
    private val hostRegistry: CredentialVpnHostRegistry,
    private val state: ExactVpnServiceState,
    private val runtimeFactory: VpnOwnedRuntimeFactory,
    private val health: ExactVpnHealthState = ExactVpnHealthState(),
    private val terminalExit: VpnTerminalServiceExit = VpnTerminalServiceExit {},
    /**
     * Clears the process-wide chained-activity mode as part of an accepted terminal retirement.
     *
     * Invoked under this owner's monitor, so a concurrent `Start` cannot install a replacement
     * between the acceptance and the clear (which would let the clear overwrite the replacement's
     * mode). Defaults to a no-op so non-production owners do not need the bridge.
     */
    private val clearChainedActivity: () -> Unit = {},
    /**
     * Runs the replacement's endpoint preflight and commit off the service thread.
     *
     * Defaults to an inline scope so a construction that does not inject a dispatcher (unit tests)
     * performs the start synchronously; production ([LavaVpnService]) injects [Dispatchers.IO] so a
     * slow endpoint DNS preflight cannot block `onStartCommand`.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    /**
     * Dispatcher for deferred runtime teardown.
     *
     * Defaults to [scope]'s dispatcher so a construction that does not inject one tests the
     * teardown inline (the default scope is [Dispatchers.Unconfined]); production
     * ([LavaVpnService]) injects [Dispatchers.IO] via [scope] so a slow JNI/engine close never runs
     * on the service main thread. It is a separate parameter so a test can keep an ordered
     * single-thread [scope] (for deterministic preflight/start) while still proving the close runs
     * on a different thread.
     */
    private val teardownDispatcher: CoroutineDispatcher =
        scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
            ?: Dispatchers.IO,
) : AutoCloseable {
    /**
     * A Pause/Resume that arrived while a start was still preflighting, carrying the control
     * sequence assigned at delivery.
     *
     * The sequence lets the deferred action run through the SAME ordered control path as a control
     * against an installed runtime: if a newer control is delivered before this one runs,
     * `sequence != latestControlSequence` and the stale intent is a no-op instead of overwriting the
     * newer command (a Pause deferred during startup followed by a Resume must end resumed).
     */
    private class DeferredControl(
        val sequence: Long,
        val action: (VpnOwnedRuntime) -> Unit,
    )

    /**
     * A candidate runtime whose asynchronous preflight/start has not been adopted yet.
     *
     * [runtime] remains the live predecessor until the candidate starts, so [pending] is the only
     * thing that knows the not-yet-installed candidate's identity. Tracking it first-class (rather
     * than inferring it from `runtime`) is what lets teardown close a route-claiming TUN that an
     * in-flight start established, and lets a terminal report published during that start be
     * latched until adoption instead of being rejected as stale.
     */
    private class PendingRuntime(
        val owner: VpnServiceOwner,
        val candidate: VpnOwnedRuntime,
        val predecessor: VpnOwnedRuntime?,
        /**
         * A Pause/Resume that arrived while this candidate was still preflighting, to be applied
         * once it is adopted.
         *
         * It travels WITH the attempt so a later Start/Reload that supersedes the attempt inherits
         * the user's intent instead of silently dropping it (a routine reload after Pause must not
         * leave protection filtering).
         */
        val afterAdopt: DeferredControl?,
        /**
         * Runtimes a newer Start/Reload superseded while this candidate was being prepared — plus
         * any earlier supersession handed forward. They are closed only AFTER this candidate is
         * live (make-before-break) or when this attempt fails/tears down, so a superseded candidate
         * that already claimed the device interface is not torn down while its replacement is still
         * preflighting (the Android mapping of iOS's "the session stays until it is replaced").
         */
        val superseded: List<VpnOwnedRuntime>,
        /**
         * Set when a newer Start/Reload transfers this attempt to its successor. This attempt's
         * launcher must then not close its own candidate in `finally`: the successor owns the close
         * so the claimed interface survives until the successor is live.
         */
        val transferred: AtomicBoolean = AtomicBoolean(false),
    )

    private var currentOwner: VpnServiceOwner? = null
    private var runtime: VpnOwnedRuntime? = null
    private var closed = false
    private var pending: PendingRuntime? = null
    private var pendingJob: Job? = null
    private var pendingTerminal: ProtectionFailure? = null

    /**
     * Runtimes claimed while [handle] holds the monitor, closed after it is released.
     *
     * A superseded candidate's production `close()` can synchronously tear down the chained JNI
     * owner (or `runBlocking { closeAndJoin() }`), so it must never run inside the monitor — where
     * `onStartCommand` delivers Stop/Revoke/Start — nor inline on the thread that called
     * `onStartCommand` (the service main thread), where it could ANR the service and delay later
     * commands. The claim is made under the monitor; the blocking close is dispatched to
     * [teardownScope] (the injected dispatcher, production `Dispatchers.IO`).
     */
    private val deferredCloses = ArrayDeque<VpnOwnedRuntime>()

    /**
     * Owns the deferred runtime teardown.
     *
     * Uses the injected [teardownDispatcher] (production `Dispatchers.IO`) with a SEPARATE
     * [SupervisorJob]: [close] cancels [scope] to stop in-flight preflight/start work, and a claimed
     * candidate's close must still run when it is drained afterwards.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + teardownDispatcher)

    /**
     * Serializes pause/resume/reconnect controls so their execution order matches the delivery
     * order. Production's [scope] is a multithreaded IO dispatcher, so two controls would otherwise
     * run concurrently and out of order: a Pause submitted first could land after the Resume that
     * superseded it and leave the durable pause set, and an older control's failure could retire a
     * runtime a newer control already succeeded on.
     */
    private val controlLock = ReentrantLock()

    /** Monotonic id of the most recently submitted control; only the latest one may act. */
    private var latestControlSequence = 0L

    /**
     * The most recently DELIVERED Pause/Resume intent that has not yet been handed to an attempt.
     *
     * A control against the installed runtime is applied from a launched coroutine, so a
     * Start/Reload can replace `currentOwner` before that coroutine runs; the control would then be
     * discarded by its identity fence and the successor would never receive the user's intent (a
     * routine reload could leave protection filtering after Pause). The intent is parked here at
     * delivery and inherited by the next `start` (unless that start already carries a newer
     * deferred control), so it reaches the replacement through the same ordered control path.
     * Cleared by an accepted stop/terminal retirement so a later fresh start is not re-paused.
     */
    private var latestControlIntent: DeferredControl? = null

    fun handle(command: VpnServiceCommand): VpnCommandResult {
        try {
            return synchronized(this) {
                if (closed) {
                    VpnCommandResult.NotSticky
                } else {
                    when (command) {
                        is VpnServiceCommand.Start -> start(command.origin)
                        VpnServiceCommand.Stop -> stop()
                        VpnServiceCommand.Revoke ->
                            stop(ProtectionState.Failed(ProtectionFailure.Revoked))
                        is VpnServiceCommand.Pause -> control { it.pause(command.duration) }
                        VpnServiceCommand.Resume -> control(VpnOwnedRuntime::resume)
                        VpnServiceCommand.Reconnect -> reconnect()
                        is VpnServiceCommand.Reload -> reload(command.expectedServiceGeneration)
                    }
                }
            }
        } finally {
            // Always drain: a command that claimed runtimes under the monitor must never leave a
            // route-claiming candidate open because its own decision threw.
            drainDeferredCloses()
        }
    }

    private fun deferClose(owned: VpnOwnedRuntime?) {
        if (owned != null) deferredCloses.addLast(owned)
    }

    /**
     * Claims the queued runtimes under the monitor and dispatches their blocking closes off the
     * calling thread.
     *
     * The batch is taken atomically, so a concurrent [deferClose] is picked up by the next drain
     * and no close is left uncaptured. The closes run on [teardownScope] (production
     * `Dispatchers.IO`): a slow chained JNI/engine teardown must never run on the service main
     * thread that called `onStartCommand`, nor be cancelled by [close]'s `scope.cancel()`.
     */
    private fun drainDeferredCloses() {
        val claimed = synchronized(this) {
            if (deferredCloses.isEmpty()) {
                emptyList()
            } else {
                deferredCloses.toList().also { deferredCloses.clear() }
            }
        }
        if (claimed.isEmpty()) return
        teardownScope.launch {
            claimed.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Rebuilds the installed runtime unless the reload's originating session generation is stale.
     *
     * A reload queued behind a stop (or replaced by a newer start) must be a no-op, or protection
     * would restart after the user turned it off.
     */
    private fun reload(expectedServiceGeneration: Long?): VpnCommandResult {
        // A start whose asynchronous preflight/commit has not finished yet is represented by
        // [pending]: `runtime` is still the live predecessor while `currentOwner` is the candidate.
        // A reload arriving in this window (a chained-preference change committing during a slow
        // lookup) must be admitted against the candidate's generation and run a fresh start: the
        // candidate may already hold credentials opened under the preference in force when it
        // began, so adopting it would install a mode the now-changed preference no longer wants
        // while status reports the preference off. [start] supersedes the in-flight candidate; its
        // commit is fenced on the owner identity the fresh start replaces under this monitor before
        // the old candidate can be adopted, and the fresh preflight re-reads the durable gate.
        val attempt = pending
        if (attempt != null) {
            if (!vpnReloadIsAdmitted(expectedServiceGeneration, attempt.owner.sessionGeneration)) {
                // A newer live start already owns the service; this reload is a harmless no-op.
                return VpnCommandResult.Superseded
            }
            return start(StartOrigin.Reload)
        }
        // No live runtime and no pending start remain (a stop or a failed start left none): the
        // reload has nothing to rebuild, and leaving the service started with no runtime would
        // let a later sticky restart re-enable protection the user turned off. Finish this start.
        if (runtime == null) return VpnCommandResult.NotSticky
        val currentGeneration = currentOwner?.sessionGeneration
            ?: return VpnCommandResult.NotSticky
        if (!vpnReloadIsAdmitted(expectedServiceGeneration, currentGeneration)) {
            // A newer live runtime owns the service now; this reload is a harmless no-op that must
            // not finish the current service start.
            return VpnCommandResult.Superseded
        }
        return start(StartOrigin.Reload)
    }

    private fun start(
        origin: StartOrigin,
        /**
         * Applied to the candidate once it is adopted as the installed runtime.
         *
         * A Pause/Resume that arrives while this start is in flight cannot control the predecessor
         * (whose owner this start already replaced) and cannot wait for the candidate on the command
         * monitor, so the control path runs a fresh start and passes the action here; it executes on
         * [scope] after adoption, through the same ordered control path as an installed-runtime
         * control (see [applyControl]).
         */
        afterAdopt: DeferredControl? = null,
    ): VpnCommandResult {
        try {
            foreground.enter()
        } catch (_: Throwable) {
            return VpnCommandResult.NotSticky
        }

        // A new start replaces any in-flight candidate. The superseded attempt is NOT closed here:
        // it is handed to this successor as a superseded predecessor and closed only once the
        // successor is live, so a candidate that already claimed the device interface keeps the
        // tunnel up until its replacement can take over (no gap) — the Android mapping of iOS's
        // "the session stays until it is replaced". Any Pause/Resume it was carrying is inherited
        // by this attempt so a routine reload cannot silently discard the user's intent.
        val superseded = supersedePending()
        // A Pause/Resume delivered against the predecessor but not yet applied is inherited here:
        // this start replaces the owner the control targeted, so the launched `applyControl` will
        // discard it on its identity fence. The parked intent (newest wins) is carried onto this
        // attempt and applied to the successor after adoption through the ordered control path.
        val inheritedControl = superseded?.afterAdopt
        val parkedControl = takeOutstandingControl()
        val deferredControl = afterAdopt
            ?: listOfNotNull(inheritedControl, parkedControl).maxByOrNull { it.sequence }
        val supersededChain = buildList {
            superseded?.let {
                add(it.candidate)
                addAll(it.superseded)
            }
        }

        val owner = state.install(origin)
        health.install(owner)
        currentOwner = owner
        val predecessor = runtime
        // Fence the predecessor's FUTURE lifecycle operations before the candidate can claim the
        // interface. The predecessor keeps its installed tunnel for make-before-break, but a queued
        // internal reconnect/awaiting-network recovery on its own engine queue must not run after
        // the candidate establishes: `Builder.establish()` would deactivate the candidate's newly
        // adopted TUN and leave the service owning a runtime with no functioning interface.
        // `supersede()` latches a monotonic quiesced state that every lifecycle entry point rejects
        // (a generation bump alone is not enough, because the engine queue re-mints a fresh token on
        // the next `run`); it does not touch the live tunnel.
        predecessor?.supersede()

        val host = hostRegistry.currentForRuntime()
            ?: return failAndRetire(
                owner,
                predecessor,
                supersededChain,
                ProtectionFailure.DependencyUnavailable,
            )
        val request = CredentialVpnAttachRequest(owner.sessionGeneration, origin)
        val attachment = try {
            host.host.openAttachment(request)
        } catch (_: Throwable) {
            null
        } ?: return failAndRetire(
            owner,
            predecessor,
            supersededChain,
            ProtectionFailure.DependencyUnavailable,
        )
        if (attachment.graphGeneration != host.graphGeneration) {
            runCatching { attachment.detach() }
            return failAndRetire(
                owner,
                predecessor,
                supersededChain,
                ProtectionFailure.DependencyUnavailable,
            )
        }

        val candidate = try {
            runtimeFactory.create(
                VpnOwnedRuntimeRequest(
                    owner = owner,
                    origin = origin,
                    host = host,
                    attachment = attachment,
                    publication = VpnStatePublication { publication ->
                        state.publish(owner, publication)
                    },
                    healthPublication = VpnHealthPublication { status ->
                        health.publish(owner, status)
                    },
                    terminalPublication = VpnRuntimeTerminalPublication { completed, failure ->
                        retireTerminalRuntime(owner, completed, failure)
                    },
                    // Fence the close-time mode clear to the current owner: a superseded candidate's
                    // close must not publish NONE over the replacement's DNS_ONLY/CHAINED mode.
                    clearChainedActivityWhenCurrent = { targetOwner ->
                        synchronized(this@VpnServiceCommandOwner) {
                            if (!closed && currentOwner === targetOwner) {
                                clearChainedActivity()
                            }
                        }
                    },
                ),
            )
        } catch (failure: Throwable) {
            runCatching { attachment.detach() }
            return failAndRetire(
                owner,
                predecessor,
                supersededChain,
                (failure as? VpnRuntimeStartException)?.failure
                    ?: ProtectionFailure.DependencyUnavailable,
            )
        }

        val attempt = PendingRuntime(owner, candidate, predecessor, deferredControl, supersededChain)
        pending = attempt
        pendingTerminal = null
        // The replacement's endpoint preflight can block on DNS and its start can run the ~50 MB
        // engine allocation. Run both OFF the service/main thread while the predecessor keeps owning
        // its tunnel, and adopt only once the candidate is live. A slow lookup or start therefore
        // neither opens a no-tunnel gap nor blocks `onStartCommand`, and a Start/Reload racing the
        // in-flight attempt captures the still-functioning predecessor rather than the not-yet-live
        // candidate. `pending` is published before the launch so an Unconfined test scope cannot
        // adopt (and clear `pending`) before the owner records it.
        pendingJob = scope.launch {
            var installed = false
            try {
                candidate.preflight()
                // Start the candidate while the predecessor still owns its tunnel, then adopt it
                // under the command monitor only once it is live. Both the start and the blocking
                // predecessor teardown run OUTSIDE the command monitor: holding it (and `handle` is
                // synchronized, called from `onStartCommand`) would let a concurrent Stop/Revoke
                // block the service thread.
                candidate.start()
                // A candidate whose engine terminated during its own start published its terminal
                // event before adoption; `retireTerminalRuntime` latched it instead of rejecting it
                // as stale. Refuse to adopt a dead candidate and handle the failure exactly like a
                // terminal retirement (publish Failed, retire owner/health, clear the chained mode).
                var terminalFailure: ProtectionFailure? = null
                val adopted = synchronized(this@VpnServiceCommandOwner) {
                    val attempt = pending
                    val latched = pendingTerminal
                    when {
                        closed || attempt == null || attempt.candidate !== candidate -> false
                        latched != null -> {
                            terminalFailure = latched
                            pending = null
                            pendingTerminal = null
                            pendingJob = null
                            runtime = null
                            state.retire(owner, ProtectionState.Failed(latched))
                            health.retire(owner)
                            if (currentOwner === owner) currentOwner = null
                            latestControlIntent = null
                            clearChainedActivity()
                            false
                        }
                        else -> {
                            runtime = candidate
                            pending = null
                            pendingJob = null
                            true
                        }
                    }
                }
                val handledTerminal = terminalFailure
                if (handledTerminal != null) {
                    // The candidate closed itself as part of its terminal teardown; retire the
                    // stranded predecessor and any superseded candidates OUTSIDE the monitor (their
                    // closes can join the engine).
                    supersededChain.forEach { runCatching { it.close() } }
                    runCatching { predecessor?.close() }
                    exitServiceIfUnowned()
                    return@launch
                }
                if (!adopted) return@launch
                // Make-before-break: the candidate is live, so retiring the predecessor now leaves
                // no tunnel gap. Runtimes an earlier supersession handed forward are released here
                // too, only AFTER this candidate is live, so a candidate that had already claimed
                // the device interface is not torn down while its replacement was preflighting.
                supersededChain.forEach { runCatching { it.close() } }
                runCatching { predecessor?.close() }
                installed = true
                // A Pause/Resume that raced this start could not control the predecessor (whose
                // owner this start already replaced), so it handed the action here; apply it to the
                // now-installed successor, off the command monitor. The intent is carried on the
                // attempt, so a Start/Reload that superseded the attempt it was deferred on
                // inherited it rather than dropping it. Deliver it through the same ordered control
                // path as an installed-runtime control, carrying the sequence assigned when the
                // command arrived: a newer control delivered in the interim wins by sequence, so a
                // Pause deferred during startup cannot overwrite a later Resume (or vice versa).
                deferredControl?.let { deferred ->
                    scope.launch {
                        applyControl(owner, candidate, deferred.sequence, deferred.action)
                    }
                }
            } catch (cancelled: CancellationException) {
                // A newer start/reload/stop superseded this attempt. That is not a failure: the
                // superseding command owns the runtime identity, and the finally below closes the
                // candidate unless a successor inherited it. Rethrow so the cancellation is not
                // swallowed.
                throw cancelled
            } catch (failure: Throwable) {
                // The candidate never adopted, so `runtime` is still the predecessor kept alive for
                // the candidate's start. Claim the failed attempt atomically under the monitor,
                // then close the stranded predecessor OUTSIDE it: its close can join the engine, and
                // holding the monitor across it would block the service thread that delivers
                // Stop/Revoke/Start (ANR). If this start still owns the service, retire it and stop
                // the service; leaving it owned by a failed owner would strand a route-claiming TUN
                // and let a null-intent restart re-enable protection the user did not ask for.
                val failureReason = (failure as? VpnRuntimeStartException)?.failure
                    ?: ProtectionFailure.DependencyUnavailable
                var strandedPredecessor: VpnOwnedRuntime? = null
                val claimed = synchronized(this@VpnServiceCommandOwner) {
                    val attempt = pending
                    when {
                        closed || currentOwner !== owner -> false
                        attempt == null || attempt.candidate !== candidate -> false
                        else -> {
                            val reason = pendingTerminal ?: failureReason
                            pending = null
                            pendingTerminal = null
                            pendingJob = null
                            strandedPredecessor = runtime
                            runtime = null
                            fail(owner, reason)
                            true
                        }
                    }
                }
                if (claimed) {
                    supersededChain.forEach { runCatching { it.close() } }
                    runCatching { strandedPredecessor?.close() }
                    exitServiceIfUnowned()
                }
            } finally {
                // A superseded attempt is owned by its successor, which closes it only once that
                // successor is live; closing it here would tear down an interface the successor is
                // about to replace (or still needs). `transferred` is set by `supersedePending`.
                if (!installed && !attempt.transferred.get()) runCatching { candidate.close() }
            }
        }
        // The service stays alive with the predecessor tunnel until the asynchronous replacement
        // commits (or stops the service on failure).
        return VpnCommandResult.Sticky
    }

    /**
     * Takes ownership of the in-flight candidate, if any, and marks it transferred to the successor
     * that is replacing it.
     *
     * Runs under the command monitor. The candidate is NOT closed here: the caller carries it (and
     * any runtimes an earlier supersession handed forward) as a superseded predecessor and closes it
     * only once the successor is live. That is what keeps a candidate that already claimed the
     * device interface from being torn down while its replacement is still preflighting (the
     * Android mapping of iOS's "the session stays until it is replaced"). `transferred` stops the
     * candidate's own launcher from closing it in `finally`; the superseding engine-queue bump still
     * runs immediately so a candidate whose slow JNI allocation is already past the entry check
     * refuses the route claim and retains what it prepared. Returns the claimed attempt so a
     * successor can inherit its deferred control intent and predecessor chain.
     */
    private fun supersedePending(): PendingRuntime? {
        val attempt = pending
        pending = null
        pendingTerminal = null
        pendingJob?.cancel()
        pendingJob = null
        // Invalidate the candidate's in-flight establishment immediately: the engine-queue bump is
        // synchronous, so a candidate past the entry check refuses the route claim and retains (not
        // closes) the interface it already claimed.
        attempt?.transferred?.set(true)
        attempt?.candidate?.supersede()
        return attempt
    }

    /**
     * Retires the predecessor and any runtimes a supersession handed forward before failing a start
     * that never reached a candidate, so a claimed interface is never leaked by an early failure.
     */
    private fun failAndRetire(
        owner: VpnServiceOwner,
        predecessor: VpnOwnedRuntime?,
        superseded: List<VpnOwnedRuntime>,
        failure: ProtectionFailure,
    ): VpnCommandResult {
        runtime = null
        superseded.forEach { deferClose(it) }
        deferClose(predecessor)
        return fail(owner, failure)
    }

    private fun fail(
        owner: VpnServiceOwner,
        failure: ProtectionFailure,
    ): VpnCommandResult {
        state.publish(owner, ProtectionState.Failed(failure))
        health.retire(owner)
        // A failed start owns no runtime: leaving `currentOwner` set would make a later
        // generation-bound reload look superseded (keeping a runtime-less service alive) instead of
        // terminal.
        if (currentOwner === owner) {
            currentOwner = null
            latestControlIntent = null
            // There is no live runtime left to carry the close-time clear (its `close()` runs after
            // `currentOwner` is already null, so the owner-fenced callback is a no-op), yet the
            // failed/replaced runtime may have published a chained/DNS-only mode. Clear it under the
            // monitor like the accepted terminal retirement.
            clearChainedActivity()
        }
        return VpnCommandResult.NotSticky
    }

    private fun stop(terminal: ProtectionState = ProtectionState.Off): VpnCommandResult {
        val owner = currentOwner
        if (owner != null) state.publish(owner, ProtectionState.Stopping)
        val attempt = pending
        pending = null
        pendingTerminal = null
        pendingJob?.cancel()
        pendingJob = null
        val ownedRuntime = runtime
        runtime = null
        // Retire the installed runtime AND the in-flight candidate. Cancellation cannot interrupt
        // the candidate's non-suspending `start()`, so a TUN it establishes mid-start could
        // otherwise remain active after protection reports Off. Each runtime is superseded FIRST
        // (an immediate engine-queue bump that makes its in-flight establishment refuse to register
        // a stale runtime; a claim it already made is retained only until the deferred close
        // releases it), then its blocking close is deferred after the monitor is released, because
        // a production `close()` can tear down the chained JNI owner. The pending job is never
        // joined; `close()` (the owner's terminal teardown) drains any close this path schedules.
        ownedRuntime?.supersede()
        attempt?.candidate?.supersede()
        deferClose(ownedRuntime)
        deferClose(attempt?.candidate)
        attempt?.superseded?.forEach { deferClose(it) }
        if (owner != null) state.retire(owner, terminal)
        if (owner != null) health.retire(owner)
        // The runtime's own close-time clear is fenced to `currentOwner`, which this path nulls
        // before the deferred closes run, so clear the mode here under the monitor as part of the
        // accepted retirement (mirroring [fail] and the accepted terminal retirement).
        if (owner != null) clearChainedActivity()
        currentOwner = null
        // A stop retires every owner; a parked control intent must not survive to re-arm a later
        // fresh start (which would pause/resume a runtime the user's stop already tore down).
        latestControlIntent = null
        return VpnCommandResult.NotSticky
    }

    /**
     * Runs a pause/resume transition off the service thread.
     *
     * A chained resume reconciles activation through the runtime attachment, whose production
     * implementation waits in `runBlocking(Dispatchers.IO)` for DataStore and potentially
     * hardware-backed Keystore reads; invoking it inline from synchronized `handle()` would block
     * `onStartCommand` and hold the command owner's monitor, so a concurrent Stop/Revoke waits and
     * the service can ANR. The action is dispatched on [scope] (production: `Dispatchers.IO`)
     * exactly like the start/reconnect paths, with the same `closed`/`currentOwner`/`runtime`
     * identity fence: a stop or newer start that retired the runtime makes a late outcome a no-op.
     * A live failure is terminal for this start (the command already reported
     * [VpnCommandResult.Sticky]), so the exact runtime is claimed and retired under the monitor and
     * the service is finished outside it through [exitServiceIfUnowned].
     *
     * When a Start/Reload is still in flight, the candidate owns `currentOwner`/`state` while
     * `runtime` still holds the predecessor; [start] runs a fresh, consistently owned attempt and
     * applies the action through [applyControl] after that attempt is adopted, carrying the intent
     * (and its delivery sequence) on the attempt so a later supersession inherits it and a later
     * control still wins by delivery order.
     *
     * Controls against the installed runtime are serialized by [controlLock] and fenced by
     * [latestControlSequence], so their execution order matches delivery order even on a
     * multithreaded dispatcher: only the most recently delivered control may act, and an older
     * control cannot overwrite a newer one or retire a runtime the newer control already succeeded
     * on.
     */
    private fun control(action: (VpnOwnedRuntime) -> Unit): VpnCommandResult {
        // A Start/Reload whose asynchronous preflight/commit is still in flight has installed the
        // candidate's owner (`currentOwner`/`state`) while `runtime` still holds the predecessor.
        // Dropping the attempt and controlling the predecessor would leave that predecessor
        // stale-owned: the exact-owner fence rejects its publications and generation-bound rebuilds,
        // so protection stays stranded in `Starting` and a Pause never tears down the still-running
        // predecessor. Run a fresh, consistently owned start (exactly like the reconnect path) and
        // apply the user's control to the successor once it is adopted, so a Pause persists and
        // rebuilds DNS-only and a Resume re-evaluates activation on a live runtime. The control
        // itself runs OFF the command monitor, exactly like the installed-runtime path below. The
        // sequence is assigned NOW (at delivery), so a control delivered later — even if applied to
        // the installed runtime first — supersedes this deferred intent through the same ordering.
        if (pending != null) {
            val deferred = DeferredControl(++latestControlSequence, action)
            latestControlIntent = deferred
            return start(StartOrigin.Reload, afterAdopt = deferred)
        }
        val current = runtime ?: return VpnCommandResult.NotSticky
        val owner = currentOwner
        val sequence = ++latestControlSequence
        val deferred = DeferredControl(sequence, action)
        // Park the intent before launching: a Start/Reload that replaces this runtime before the
        // coroutine runs inherits it via `takeOutstandingControl()` instead of the applyControl
        // identity fence silently discarding it.
        latestControlIntent = deferred
        scope.launch {
            applyControl(owner, current, sequence, action)
        }
        return VpnCommandResult.Sticky
    }

    /** Claims the parked control intent for the next attempt (called under the monitor). */
    private fun takeOutstandingControl(): DeferredControl? =
        latestControlIntent.also { latestControlIntent = null }

    /**
     * Runs a Pause/Resume through the single ordered control path.
     *
     * Both a control against the installed runtime and a deferred control applied after adoption
     * enter here: [controlLock] serializes their execution and [sequence] is compared against
     * [latestControlSequence], so only the most recently DELIVERED control may act. Without the
     * shared sequence a deferred Pause (delivered first, applied after adoption) could run after a
     * later Resume and leave protection paused.
     */
    private fun applyControl(
        owner: VpnServiceOwner?,
        current: VpnOwnedRuntime,
        sequence: Long,
        action: (VpnOwnedRuntime) -> Unit,
    ) {
        controlLock.withLock {
            val live = synchronized(this@VpnServiceCommandOwner) {
                !closed && runtime === current && currentOwner === owner &&
                    sequence == latestControlSequence
            }
            if (live) {
                runControl(owner, current, action)
                // The intent has reached a live runtime, so it must not be re-applied by a later
                // start (re-applying a Pause would extend its deadline). A newer control replaces
                // the parked intent with a higher sequence, which this equality check leaves alone.
                synchronized(this@VpnServiceCommandOwner) {
                    if (latestControlIntent?.sequence == sequence) latestControlIntent = null
                }
            }
        }
    }

    /**
     * Applies [action] to the installed [current] runtime and retires the exact owner/runtime pair
     * on failure.
     *
     * Runs OFF the command monitor (the caller dispatches it on [scope]): a chained pause/resume
     * reconciles activation through the credential attachment, whose production implementation can
     * block on DataStore/Keystore, and holding the monitor across it would block the service thread
     * that delivers Stop/Revoke (ANR). The liveness check and the failure fence both run under the
     * monitor, so a runtime replaced or closed before this runs is left untouched. A live failure is
     * terminal for this start (the command already reported [VpnCommandResult.Sticky]), so the exact
     * runtime is claimed and retired under the monitor, then closed and the service finished
     * outside it through [exitServiceIfUnowned].
     */
    private fun runControl(
        owner: VpnServiceOwner?,
        current: VpnOwnedRuntime,
        action: (VpnOwnedRuntime) -> Unit,
    ) {
        val live = synchronized(this@VpnServiceCommandOwner) {
            !closed && runtime === current && currentOwner === owner
        }
        if (!live) return
        try {
            action(current)
        } catch (cancelled: CancellationException) {
            // A newer command superseded this control action (the runtime is already being replaced
            // or torn down by that command); not a terminal failure.
            throw cancelled
        } catch (failure: Throwable) {
            // Claim the exact runtime and retire its owner/health UNDER the monitor, then close it
            // and finish the service OUTSIDE it: production `close()` can synchronously tear down
            // the chained JNI owner (or join its engine), and holding the monitor across it would
            // block the service thread that delivers Stop/Revoke/Start (ANR).
            val claimed = synchronized(this@VpnServiceCommandOwner) {
                if (closed || runtime !== current || currentOwner !== owner) {
                    false
                } else {
                    runtime = null
                    if (owner != null) {
                        state.retire(
                            owner,
                            ProtectionState.Failed(
                                (failure as? VpnRuntimeStartException)?.failure
                                    ?: ProtectionFailure.DependencyUnavailable,
                            ),
                        )
                        health.retire(owner)
                    }
                    currentOwner = null
                    // The exact-owner retirement is accepted under this monitor, so clear the
                    // process-wide chained activity HERE: the subsequent `current.close()` cannot
                    // clear it (its owner-fenced callback sees `currentOwner` already null), and a
                    // stale `active = true`/CHAINED snapshot would survive protection going Failed.
                    clearChainedActivity()
                    latestControlIntent = null
                    true
                }
            }
            if (claimed) {
                runCatching { current.close() }
                // Another Start may have installed a replacement while the close ran; only finish
                // the service when no command owns it.
                exitServiceIfUnowned()
            }
        }
    }

    /**
     * Runs the installed runtime's reconnect off the service thread.
     *
     * A chained reconnect internally preflights its endpoint (a credential/Keystore read and a
     * blocking hostname lookup) before it drops the live predecessor, so invoking it inline would
     * freeze `onStartCommand` on the main thread and hold the runtime's reconnect serialization
     * against a concurrent Stop. It is dispatched on [scope] (production: `Dispatchers.IO`) exactly
     * like the asynchronous start commit, and its failure is fenced on the same
     * `closed`/`currentOwner`/`runtime` identities, so a stop or newer start that retired the
     * runtime makes the outcome a no-op instead of tearing the replacement down. A failure is
     * terminal for this start (the command already reported [VpnCommandResult.Sticky]), so it
     * finishes the service through [terminalExit].
     */
    private fun reconnect(): VpnCommandResult {
        // A replacement whose preflight/commit is still in flight has published its owner but not
        // its runtime yet: `runtime` is still the predecessor while `currentOwner` is the candidate.
        // A reconnect that captured that mismatched pair would cancel the attempt and then act on
        // the predecessor as if the candidate owned it, so the predecessor's publications are
        // rejected by the exact-owner fence and protection stays stuck in Starting. Supersede the
        // pending attempt with a fresh start (which re-adopts a consistent owner/runtime pair)
        // instead; [start] closes the superseded candidate so its TUN cannot outlive the
        // replacement.
        if (pending != null) {
            return start(StartOrigin.Reload)
        }
        val current = runtime ?: return VpnCommandResult.NotSticky
        val owner = currentOwner
        scope.launch {
            try {
                current.reconnect()
            } catch (cancelled: CancellationException) {
                // A newer command superseded this reconnect (the runtime is already being replaced
                // or torn down by that command); not a terminal failure.
                throw cancelled
            } catch (failure: Throwable) {
                // Same claim-under-the-monitor / close-outside-it split as [runControl]: the
                // production close can join the chained engine, which must never hold the monitor
                // the service thread delivers Stop/Revoke/Start on.
                val claimed = synchronized(this@VpnServiceCommandOwner) {
                    if (closed || runtime !== current || currentOwner !== owner) {
                        false
                    } else {
                        runtime = null
                        if (owner != null) {
                            state.retire(
                                owner,
                                ProtectionState.Failed(
                                    (failure as? VpnRuntimeStartException)?.failure
                                        ?: ProtectionFailure.DependencyUnavailable,
                                ),
                            )
                            health.retire(owner)
                        }
                        currentOwner = null
                        // Clear the chained mode inside the accepted retirement critical section, as
                        // `runControl` does: `current.close()` runs after `currentOwner` is nulled,
                        // so its owner-fenced clear is a no-op and a stale chained/DNS-only mode
                        // would otherwise linger after protection went Failed.
                        clearChainedActivity()
                        latestControlIntent = null
                        true
                    }
                }
                if (claimed) {
                    runCatching { current.close() }
                    exitServiceIfUnowned()
                }
            }
        }
        return VpnCommandResult.Sticky
    }

    /**
     * Finishes the service only while no command owns it.
     *
     * Called after a terminal retirement whose slow work — the predecessor close — ran OUTSIDE the
     * command monitor. A Start that installed a replacement, or whose asynchronous commit is still
     * in flight ([pending]), during that window owns the service now, so an unconditional
     * `stopSelf()` would destroy that replacement via `onDestroy` and drop protection. The check and
     * the fast [VpnTerminalServiceExit] are one critical section; the blocking close must stay
     * outside it.
     */
    @Synchronized
    private fun exitServiceIfUnowned() {
        if (currentOwner == null && pending == null) {
            terminalExit.exit()
        }
    }

    private fun retireTerminalRuntime(
        owner: VpnServiceOwner,
        completed: VpnOwnedRuntime,
        failure: ProtectionFailure,
    ): Boolean {
        var latched = false
        val accepted = synchronized(this) {
            val attempt = pending
            if (attempt != null && attempt.candidate === completed && attempt.owner === owner) {
                // The candidate died during its own asynchronous start, before it was adopted.
                // Latch the failure for the start job, which owns the terminal retirement and the
                // predecessor teardown outside this monitor; retiring the owner here would race the
                // job's adoption and leave the service sticky with an already-closed runtime.
                pendingTerminal = failure
                latched = true
                true
            } else if (closed || currentOwner != owner || runtime !== completed) {
                false
            } else {
                runtime = null
                state.retire(owner, ProtectionState.Failed(failure))
                health.retire(owner)
                currentOwner = null
                latestControlIntent = null
                // Clear the chained mode as part of the exact-owner retirement, under this monitor,
                // so a concurrent Start cannot install a replacement between the acceptance and the
                // clear (which would let a stale NONE overwrite the replacement's DNS_ONLY/CHAINED
                // mode).
                clearChainedActivity()
                true
            }
        }
        // A latched pending terminal is not yet retired: the start job performs the exit after it
        // has closed the stranded predecessor outside the monitor. An accepted retirement still
        // re-checks ownership, so a replacement installed before this point is not stopped.
        if (accepted && !latched) exitServiceIfUnowned()
        return accepted
    }

    @Synchronized
    override fun close() {
        if (closed) return
        stop()
        closed = true
        pendingJob?.cancel()
        pendingJob = null
        scope.cancel()
        // A superseded candidate claimed by a concurrent `handle` may still be waiting for that
        // command's post-monitor drain; schedule it here too. The teardown scope is a separate Job,
        // so the just-cancelled [scope] cannot cancel this close before it runs.
        drainDeferredCloses()
    }
}
