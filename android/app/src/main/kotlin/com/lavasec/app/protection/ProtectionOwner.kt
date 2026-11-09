package com.lavasec.app.protection

import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.onboarding.OnboardingPhase
import com.lavasec.app.foundation.onboarding.ProtectionLifecycle
import com.lavasec.app.foundation.onboarding.ProtectionLifecycleMachine
import com.lavasec.app.foundation.onboarding.ProtectionLifecycleResult
import com.lavasec.app.foundation.onboarding.ProtectionLifecycleStatus
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.foundation.pause.PausePolicy
import com.lavasec.app.platform.ProtectionController
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.SettingsRoute
import com.lavasec.app.presentation.GuardPrimaryAction
import com.lavasec.app.presentation.GuardProtectionHealthUi
import com.lavasec.app.vpn.VpnHealthStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.async

sealed interface ProtectionIntentCommit {
    data object Committed : ProtectionIntentCommit

    data class Rejected(val message: String) : ProtectionIntentCommit {
        init {
            require(message.isNotBlank())
        }
    }

    data class ReconstructionRequired(val message: String) : ProtectionIntentCommit {
        init {
            require(message.isNotBlank())
        }
    }
}

data class ProtectionOwnerState(
    val guard: GuardProtectionHealthUi,
    /**
     * The same runtime assessment projected as typed resolver health for the
     * DNS Resolver settings surface (slice 05). Null when no confirmed
     * assessment context exists; the selection itself is never derived from it.
     */
    val resolverHealth: com.lavasec.app.presentation.resolver.ResolverHealthUi? = null,
    val commandInFlight: Boolean = false,
    val reconstructionRequired: Boolean = false,
    val error: String? = null,
    /**
     * The canonical first-run protection lifecycle folded into the owner (B18). [ProtectionLifecycle]
     * keeps the REQUESTED intent and the CONFIRMED platform observation as separate values, so the
     * RN `protection` snapshot can report both honestly instead of inferring request from the
     * observed tunnel. It also carries the [OnboardingPhase] and the actionable recovery, so a
     * command refused before the default filter is prepared is owner-driven, never an optimistic
     * toggle. Null only before the lifecycle is seeded.
     */
    val lifecycle: ProtectionLifecycle? = null,
)

/**
 * Focused Guard owner: actual service and exact health flows are the only presentation source.
 *
 * The pure [ProtectionLifecycleMachine] is the decision layer beneath the owner: commands are
 * admitted through it (onboarding gate, requested intent, action gate) while the existing
 * VpnService adapter stays the dispatch layer. Confirmed connectivity is only ever written by a
 * controller observation — a dispatch never synthesizes it.
 */
class ProtectionOwner(
    private val controller: ProtectionController,
    health: Flow<VpnHealthStatus>,
    private val commitProtectionIntent: suspend (Boolean) -> ProtectionIntentCommit,
    scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    runtimeReloadRequired: Flow<Boolean> = MutableStateFlow(false),
    appStarted: Flow<Boolean> = MutableStateFlow(false),
    private val reconcileRuntimeReload: (suspend () -> Boolean)? = null,
    private val validateChainedConfigurationForStart: suspend () -> ChainedConfigurationIssue? = { null },
    /**
     * First-run phase the owner starts from. Production constructs the owner only once the
     * default filter is confirmed prepared (READY); the other phases exist so preparation and
     * its failures stay owner-driven and testable.
     */
    initialOnboardingPhase: OnboardingPhase = OnboardingPhase.READY,
    defaultFilterLevel: ProtectionLevel = ProtectionLevel.BALANCED,
    /**
     * Whether the configuration needed to resume a paused session is available. A false answer
     * still lifts the pause (the resume dispatch already ran) but surfaces the canonical
     * `ResumeConfigurationUnavailable` recovery so the caller can retry the refresh.
     */
    private val resumeConfigurationAvailable: suspend () -> Boolean = { true },
) : AutoCloseable {
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private val commandInFlight = AtomicBoolean(false)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private var pendingSettingsRoute: SettingsRoute? = null
    private var lifecycle: ProtectionLifecycle = seedLifecycle(initialOnboardingPhase, defaultFilterLevel)
    private val mutableState = MutableStateFlow(
        ProtectionOwnerState(
            guard = GuardProtectionHealthUi.from(
                state = controller.state.value,
                health = null,
                nowEpochMillis = checkedNow(),
            ),
            lifecycle = lifecycle,
        ),
    )

    val state: StateFlow<ProtectionOwnerState> = mutableState.asStateFlow()

    init {
        // The platform observation is the only writer of the CONFIRMED side: it can never touch
        // explicit intent, so a start/stop dispatch never fabricates a `CONNECTED` confirmation.
        ownerScope.launch {
            controller.state.collect { protectionState -> confirmPlatformStatus(protectionState) }
        }
        ownerScope.launch {
            combine(controller.state, health) { protection, exactHealth ->
                // One assessment serves both projections: the Guard status and
                // the typed resolver health for the DNS Resolver surface, so the
                // two surfaces can never disagree about the runtime state.
                val assessment = exactHealth.health.assessment.takeIf {
                    exactHealth.health.context != null
                }
                GuardProtectionHealthUi.from(
                    state = protection,
                    health = assessment,
                    nowEpochMillis = checkedNow(),
                    internetValidated = exactHealth.internetValidated,
                    rechecking = exactHealth.chainedDiagnostics?.rechecking == true,
                ) to assessment?.let(com.lavasec.app.presentation.resolver.ResolverHealthUi::from)
            }.collect { (guard, resolverHealth) -> publishGuard(guard, resolverHealth) }
        }
        if (reconcileRuntimeReload != null) {
            ownerScope.launch {
                // Re-dispatch the deferred runtime attachment whenever the requirement is
                // armed or the runtime state changes while it stays armed, and whenever the
                // app returns to the foreground while it stays armed: a failed dispatch
                // (the app was backgrounded) is retried on the next foreground entry even
                // when the runtime state never transitions, and a successful one discharges
                // the requirement through the authority. A pure transition out of the
                // foreground is not a trigger — a dispatch there would just be rejected by
                // the same background execution limits that armed the latch.
                var wasStarted = false
                combine(controller.state, runtimeReloadRequired, appStarted) { _, required, started ->
                    required to started
                }.collect { (required, started) ->
                    val backgroundExit = !started && wasStarted
                    wasStarted = started
                    if (!required || backgroundExit) return@collect
                    try {
                        reconcileRuntimeReload()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The authority keeps the requirement armed for a later path.
                    }
                }
            }
        }
    }

    /** Returns false only when the owner is closed or another command already owns admission. */
    suspend fun perform(action: GuardPrimaryAction): Boolean = owned {
        if (action is GuardPrimaryAction.OpenSettings) {
            synchronized(stateLock) {
                if (closed.get()) return@owned false
                pendingSettingsRoute = action.route
            }
            return@owned true
        }
        if (!commandInFlight.compareAndSet(false, true)) return@owned false
        publishCommand(inFlight = true, reconstructionRequired = false, error = null)
        try {
            if (action == GuardPrimaryAction.TurnOn || action == GuardPrimaryAction.Reconnect
            ) {
                val issue = try { validateChainedConfigurationForStart() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ChainedConfigurationIssue.UNAVAILABLE }
                if (closed.get()) return@owned false
                if (issue != null) {
                    publishCommand(inFlight = true, error = issue.message)
                    return@owned true
                }
            }
            when (action) {
                GuardPrimaryAction.TurnOn ->
                    requestLifecycleStart(commitIntent = true) { controller.start() }
                GuardPrimaryAction.TurnOff -> requestLifecycleStop()
                GuardPrimaryAction.Resume -> requestLifecycleResume()
                GuardPrimaryAction.Reconnect ->
                    requestLifecycleStart(commitIntent = false) { controller.reconnect() }
                is GuardPrimaryAction.OpenSettings -> error("settings returned before admission")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publishCommand(
                inFlight = true,
                reconstructionRequired = false,
                error = failure.message?.takeIf(String::isNotBlank) ?: "Protection command failed.",
            )
        } finally {
            commandInFlight.set(false)
            publishCommand(inFlight = false)
        }
        return@owned true
    }

    suspend fun pause(duration: PauseDuration): Boolean = owned {
        if (!commandInFlight.compareAndSet(false, true)) return@owned false
        publishCommand(inFlight = true, reconstructionRequired = false, error = null)
        try {
            val requested = applyLifecycleResult { current ->
                ProtectionLifecycleMachine.requestPause(current, checkedNow(), duration)
            }
            when (requested) {
                is ProtectionLifecycleResult.Refused -> {
                    publishCommand(inFlight = true, error = PROTECTION_NOT_ENABLED_MESSAGE)
                }
                is ProtectionLifecycleResult.Ignored -> Unit
                is ProtectionLifecycleResult.Applied -> controller.pause(duration)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publishCommand(
                inFlight = true,
                error = failure.message?.takeIf(String::isNotBlank) ?: "Pause command failed.",
            )
        } finally {
            commandInFlight.set(false)
            publishCommand(inFlight = false)
        }
        return@owned true
    }

    /**
     * Begins (or retries) first-run default-filter preparation. Fails closed: protection cannot be
     * requested until the preparation completes.
     */
    suspend fun beginDefaultFilterPreparation(): Boolean = owned {
        applyLifecycleResult {
            ProtectionLifecycleMachine.beginDefaultFilterPreparation(it)
        } is ProtectionLifecycleResult.Applied
    }

    /** Reports the terminal outcome of first-run default-filter preparation. */
    suspend fun completeDefaultFilterPreparation(prepared: Boolean): Boolean = owned {
        applyLifecycleResult {
            ProtectionLifecycleMachine.completeDefaultFilterPreparation(it, prepared)
        } is ProtectionLifecycleResult.Applied
    }

    /**
     * Reconciles a durable pause against the current protection session and clock. A persisted
     * window that expired or belongs to a superseded session is cleared at read time rather than
     * applied (the restart path).
     */
    suspend fun reconcilePause(): Boolean = owned {
        applyLifecycleResult {
            ProtectionLifecycleMachine.reconcilePause(it, checkedNow())
        } is ProtectionLifecycleResult.Applied
    }

    fun consumeSettingsRoute(): SettingsRoute? = synchronized(stateLock) {
        pendingSettingsRoute.also { pendingSettingsRoute = null }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        commandInFlight.set(false)
        synchronized(stateLock) { pendingSettingsRoute = null }
        ownerJob.cancel()
    }

    private suspend fun requestLifecycleStart(
        commitIntent: Boolean,
        dispatch: suspend () -> Unit,
    ) {
        val requested = applyLifecycleResult { ProtectionLifecycleMachine.requestStart(it) }
        when (requested) {
            // Default-filter preparation has not reached READY: the requested intent is not
            // recorded and no runtime is dispatched. The recovery is surfaced, not a silent no-op.
            is ProtectionLifecycleResult.Refused -> {
                publishCommand(inFlight = true, error = PROTECTION_NOT_READY_MESSAGE)
                return
            }
            // Another lifecycle action owns the single-flight gate; a duplicate start is a no-op.
            is ProtectionLifecycleResult.Ignored -> return
            is ProtectionLifecycleResult.Applied -> Unit
        }
        if (commitIntent && !commitIntentOrPublish(enabled = true)) {
            completeStartClaim(success = false)
            return
        }
        try {
            dispatch()
            completeStartClaim(success = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            completeStartClaim(
                success = false,
                message = failure.message?.takeIf(String::isNotBlank),
            )
            throw failure
        }
    }

    private suspend fun requestLifecycleStop() {
        applyLifecycleResult { ProtectionLifecycleMachine.requestStop(it) }
        if (!commitIntentOrPublish(enabled = false)) {
            completeStopClaim(success = false)
            return
        }
        try {
            controller.stop()
            completeStopClaim(success = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            completeStopClaim(
                success = false,
                message = failure.message?.takeIf(String::isNotBlank),
            )
            throw failure
        }
    }

    private suspend fun requestLifecycleResume() {
        val available = try {
            resumeConfigurationAvailable()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        applyLifecycleResult { ProtectionLifecycleMachine.requestResume(it, available) }
        controller.resume()
    }

    private fun completeStartClaim(success: Boolean, message: String? = null) {
        applyLifecycleResult { current ->
            ProtectionLifecycleMachine.completeStart(
                current,
                claimGeneration = current.claimGeneration,
                success = success,
                message = message,
            )
        }
    }

    private fun completeStopClaim(success: Boolean, message: String? = null) {
        applyLifecycleResult { current ->
            ProtectionLifecycleMachine.completeStop(
                current,
                claimGeneration = current.claimGeneration,
                success = success,
                message = message,
            )
        }
    }

    private fun confirmPlatformStatus(protectionState: ProtectionState) {
        applyLifecycle { current ->
            val observed = ProtectionLifecycleMachine.confirmPlatformStatus(
                state = current,
                status = protectionState.toLifecycleStatus(),
                // Android exposes Always-on VPN as an ownership fact on a running tunnel, not a
                // separate arm bit while disconnected, so the on-demand flag is never fabricated.
                onDemandConfirmedEnabled = false,
            ).state
            if (protectionState !is ProtectionState.Paused) return@applyLifecycle observed
            // A durable pause reported by the runtime is adopted only when the pure
            // model has none: the persisted deadline and the active session decide validity, so an
            // expired or superseded window is dropped instead of disabling filtering.
            val pause = observed.pause ?: PausePolicy.restore(
                persistedDeadlineMillis = protectionState.untilEpochMillis,
                persistedSessionId = observed.protectionSessionId,
                activeSessionId = observed.protectionSessionId,
                nowMillis = checkedNow(),
            )
            observed.copy(pause = pause)
        }
    }

    private suspend fun commitIntentOrPublish(enabled: Boolean): Boolean =
        when (val result = commitProtectionIntent(enabled)) {
            ProtectionIntentCommit.Committed -> true
            is ProtectionIntentCommit.Rejected -> {
                publishCommand(inFlight = true, reconstructionRequired = false, error = result.message)
                false
            }
            is ProtectionIntentCommit.ReconstructionRequired -> {
                publishCommand(inFlight = true, reconstructionRequired = true, error = result.message)
                false
            }
        }

    private fun publishGuard(
        guard: GuardProtectionHealthUi,
        resolverHealth: com.lavasec.app.presentation.resolver.ResolverHealthUi?,
    ) {
        synchronized(stateLock) {
            if (closed.get()) return
            mutableState.value = mutableState.value.copy(
                guard = guard,
                resolverHealth = resolverHealth,
            )
        }
    }

    private fun publishCommand(
        inFlight: Boolean,
        reconstructionRequired: Boolean = mutableState.value.reconstructionRequired,
        error: String? = mutableState.value.error,
    ) {
        synchronized(stateLock) {
            if (closed.get()) return
            mutableState.value = mutableState.value.copy(
                commandInFlight = inFlight,
                reconstructionRequired = reconstructionRequired,
                error = error,
            )
        }
    }

    /** Applies an arbitrary lifecycle transition and publishes the resulting snapshot. */
    private fun applyLifecycle(transform: (ProtectionLifecycle) -> ProtectionLifecycle): ProtectionLifecycle =
        synchronized(stateLock) {
            val next = transform(lifecycle)
            lifecycle = next
            if (!closed.get()) mutableState.value = mutableState.value.copy(lifecycle = next)
            next
        }

    /** Applies a pure machine transition and returns its typed result (Applied/Ignored/Refused). */
    private fun applyLifecycleResult(
        transform: (ProtectionLifecycle) -> ProtectionLifecycleResult,
    ): ProtectionLifecycleResult = synchronized(stateLock) {
        val result = transform(lifecycle)
        lifecycle = result.state
        if (!closed.get()) mutableState.value = mutableState.value.copy(lifecycle = result.state)
        result
    }

    private fun checkedNow(): Long = nowMillis().also { require(it >= 0L) }

    private fun seedLifecycle(phase: OnboardingPhase, level: ProtectionLevel): ProtectionLifecycle {
        val fresh = ProtectionLifecycleMachine.freshInstall(level)
        return when (phase) {
            OnboardingPhase.FRESH_INSTALL -> fresh
            OnboardingPhase.PREPARING_DEFAULT_FILTER ->
                ProtectionLifecycleMachine.beginDefaultFilterPreparation(fresh).state
            OnboardingPhase.READY -> {
                val preparing = ProtectionLifecycleMachine.beginDefaultFilterPreparation(fresh).state
                ProtectionLifecycleMachine.completeDefaultFilterPreparation(preparing, prepared = true)
                    .state
                    // A prepared product has a live/restorable protection session to bind a
                    // durable pause to; the exact platform status still comes from confirmation.
                    .copy(protectionSessionId = 1L)
            }
            OnboardingPhase.PREPARATION_FAILED -> {
                val preparing = ProtectionLifecycleMachine.beginDefaultFilterPreparation(fresh).state
                ProtectionLifecycleMachine.completeDefaultFilterPreparation(preparing, prepared = false).state
            }
        }
    }

    private suspend fun owned(operation: suspend () -> Boolean): Boolean {
        if (closed.get()) return false
        val task = ownerScope.async {
            if (closed.get()) false else operation()
        }
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            if (closed.get()) false else {
                task.cancel()
                throw cancelled
            }
        }
    }

    private companion object {
        const val PROTECTION_NOT_READY_MESSAGE =
            "Protection is not ready yet. Finish setting up your default filter."
        const val PROTECTION_NOT_ENABLED_MESSAGE =
            "Protection is not running, so it cannot be paused."
    }
}

private fun ProtectionState.toLifecycleStatus(): ProtectionLifecycleStatus = when (this) {
    ProtectionState.Off,
    ProtectionState.SetupRequired,
    -> ProtectionLifecycleStatus.DISCONNECTED

    is ProtectionState.Starting,
    is ProtectionState.AwaitingNetwork,
    -> ProtectionLifecycleStatus.CONNECTING

    is ProtectionState.Reconnecting -> ProtectionLifecycleStatus.REASSERTING
    is ProtectionState.Protected -> ProtectionLifecycleStatus.CONNECTED
    is ProtectionState.Paused -> ProtectionLifecycleStatus.CONNECTED
    ProtectionState.Stopping -> ProtectionLifecycleStatus.DISCONNECTING
    is ProtectionState.Failed -> ProtectionLifecycleStatus.DISCONNECTED
}
