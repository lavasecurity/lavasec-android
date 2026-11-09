package com.lavasec.app.foundation.onboarding

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.foundation.pause.PausePolicy
import com.lavasec.app.foundation.pause.PauseState

/**
 * Canonical protection/onboarding first-run lifecycle as a pure state machine, ported from the
 * pinned iOS commit `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`.
 *
 * The canonical pieces this file ports, and where they live on iOS:
 * - explicit user intent with a monotonic revision — `ProtectionRestoreIntentState`
 *   (`Sources/LavaSecKit/ProtectionActionOrchestrator.swift`);
 * - the single-flight action gate — `ProtectionActionOrchestrator`;
 * - confirmed NetworkExtension status and its derived predicates —
 *   `ProtectionLifecyclePolicy` (`Sources/LavaSecKit/ProtectionLifecyclePolicy.swift`);
 * - the onboarding gate (`hasCompletedOnboarding`) and the fresh-install default-filter seed
 *   (`applyOnboardingRecommendedDefaults` / `OnboardingProtectionLevel.recommended`, i.e. the
 *   `balanced` seeded level);
 * - durable pause windows — the already-ported [PausePolicy] (`deadline + protectionSessionId`,
 *   restart-reconciled against a time source).
 *
 * Two rules are structural, not conventions:
 *
 * 1. **REQUESTED is never derived from CONFIRMED.** [ProtectionIntent] changes only through an
 *    explicit user action (`recordUserIntent`) or a protected-data recovery
 *    (`recoverFromLoadedConfiguration`). A [ProtectionLifecycleMachine.confirmPlatformStatus]
 *    observation records the confirmed side and can never rewrite intent — the canonical
 *    `AppViewModelSourceTests` invariant that status observation may read the revision but never
 *    replace intent.
 * 2. **Confirmed connectivity is never synthesized.** Only a platform observation moves
 *    [ProtectionLifecycleStatus], so a start/stop transition can never fabricate a `CONNECTED`
 *    confirmation and never has to silently disable anything to obtain one.
 *
 * The VpnService adapter (interface establishment, consent, process lifecycle) deliberately stays
 * app-layer; this model is the pure decision layer beneath it.
 */

/**
 * Explicit, sticky protection intent independent of the observed tunnel status.
 *
 * Mirrors the canonical `ProtectionRestoreIntentState`: [revision] advances on every authoritative
 * intent replacement, including a same-direction reconnect, so work captured before a later user
 * action can never restore over it.
 */
data class ProtectionIntent(
    val isEnabled: Boolean,
    val revision: Long,
) {
    init {
        require(revision >= 0L) { "intent revision must not be negative" }
    }

    /**
     * Records an explicit user lifecycle choice. Unlike a status refresh this is authoritative and
     * always advances [revision], even when the direction is unchanged (a same-direction reconnect
     * must invalidate earlier captures).
     */
    fun recordUserIntent(isEnabled: Boolean): ProtectionIntent =
        ProtectionIntent(isEnabled = isEnabled, revision = revision + 1L)

    /**
     * Replaces a launch placeholder with the configuration actually loaded after protected data
     * becomes available. Also advances [revision]; a status refresh is never allowed to call this.
     */
    fun recoverFromLoadedConfiguration(isEnabled: Boolean): ProtectionIntent =
        ProtectionIntent(isEnabled = isEnabled, revision = revision + 1L)

    /** Captures the current intent and optional external-restart generation for restoration. */
    fun makeRestoreRequest(externalRestartGeneration: String? = null): ProtectionRestoreRequest =
        ProtectionRestoreRequest(
            wasEnabled = isEnabled,
            intendedEnabled = isEnabled,
            intentRevision = revision,
            externalRestartGeneration = externalRestartGeneration,
        )

    /**
     * Whether a captured request still describes the latest authoritative intent. A status-derived
     * value is intentionally not consulted: only explicit loaded/user intent is authoritative.
     */
    fun allows(request: ProtectionRestoreRequest): Boolean =
        request.wasEnabled &&
            request.intendedEnabled &&
            isEnabled &&
            request.intentRevision == revision

    companion object {
        val DisabledOnceLoaded: ProtectionIntent = ProtectionIntent(isEnabled = false, revision = 0L)
    }
}

/** A restore request captured before asynchronous configuration work first suspends. */
data class ProtectionRestoreRequest(
    /** Whether sticky explicit intent made restoration eligible when the request was captured. */
    val wasEnabled: Boolean,
    /** Explicit on/off direction captured independently of the observed tunnel status. */
    val intendedEnabled: Boolean,
    /** Intent generation used to reject work captured before a later user action. */
    val intentRevision: Long,
    /** Durable generation of the latest accepted direct restart at capture time. */
    val externalRestartGeneration: String? = null,
)

/** Confirmed NetworkExtension lifecycle statuses, ported verbatim from `ProtectionLifecycleStatus`. */
enum class ProtectionLifecycleStatus {
    INVALID,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    REASSERTING,
    DISCONNECTING,
}

/**
 * Pure classification of a confirmed [ProtectionLifecycleStatus]. Ported from
 * `ProtectionLifecyclePolicy`; every predicate answers a question about the platform fact, never
 * about explicit intent.
 */
object ProtectionLifecyclePolicy {
    /** `connected`, `connecting`, and `reasserting` are the canonical NetworkExtension running states. */
    fun isProtectionEnabled(status: ProtectionLifecycleStatus): Boolean = when (status) {
        ProtectionLifecycleStatus.CONNECTED,
        ProtectionLifecycleStatus.CONNECTING,
        ProtectionLifecycleStatus.REASSERTING,
        -> true

        ProtectionLifecycleStatus.INVALID,
        ProtectionLifecycleStatus.DISCONNECTED,
        ProtectionLifecycleStatus.DISCONNECTING,
        -> false
    }

    /** Whether the status can still emit a disconnect transition. */
    fun isStopPending(status: ProtectionLifecycleStatus): Boolean = when (status) {
        ProtectionLifecycleStatus.CONNECTED,
        ProtectionLifecycleStatus.CONNECTING,
        ProtectionLifecycleStatus.REASSERTING,
        ProtectionLifecycleStatus.DISCONNECTING,
        -> true

        ProtectionLifecycleStatus.INVALID,
        ProtectionLifecycleStatus.DISCONNECTED,
        -> false
    }

    /** Whether the status is still settling towards a connection. */
    fun isStartPending(status: ProtectionLifecycleStatus): Boolean = when (status) {
        ProtectionLifecycleStatus.CONNECTING,
        ProtectionLifecycleStatus.REASSERTING,
        -> true

        ProtectionLifecycleStatus.INVALID,
        ProtectionLifecycleStatus.DISCONNECTED,
        ProtectionLifecycleStatus.CONNECTED,
        ProtectionLifecycleStatus.DISCONNECTING,
        -> false
    }

    /** Uptime accrues only while actively protecting, not while transitioning. */
    fun isUptimeActive(status: ProtectionLifecycleStatus): Boolean = when (status) {
        ProtectionLifecycleStatus.CONNECTED,
        ProtectionLifecycleStatus.REASSERTING,
        -> true

        ProtectionLifecycleStatus.INVALID,
        ProtectionLifecycleStatus.DISCONNECTED,
        ProtectionLifecycleStatus.CONNECTING,
        ProtectionLifecycleStatus.DISCONNECTING,
        -> false
    }

    /**
     * Whether protection is DOWN but Connect-On-Demand is confirmed armed, so the platform will
     * reconnect on its own once a network path returns (the elevator/tunnel case). `.disconnected`
     * alone cannot distinguish a user turn-off from an armed-but-dropped tunnel; only the confirmed
     * arming bit disambiguates.
     */
    fun isAwaitingOnDemandReconnect(
        status: ProtectionLifecycleStatus,
        onDemandConfirmedEnabled: Boolean,
    ): Boolean {
        if (!onDemandConfirmedEnabled) return false
        return status == ProtectionLifecycleStatus.DISCONNECTED
    }

    /** The primary protection action is disabled only while a lifecycle action is configuring. */
    fun shouldDisablePrimaryAction(status: ProtectionLifecycleStatus, isConfiguring: Boolean): Boolean =
        isConfiguring

    /** A toggle to "on" must be disabled while protection is already enabled/settling. */
    fun shouldDisableProtectionForToggle(status: ProtectionLifecycleStatus): Boolean =
        isProtectionEnabled(status)
}

/** The confirmed side of protection: a platform status plus the confirmed arm bit. */
data class ProtectionConfirmation(
    val status: ProtectionLifecycleStatus = ProtectionLifecycleStatus.INVALID,
    val onDemandConfirmedEnabled: Boolean = false,
) {
    val isProtectionEnabled: Boolean get() = ProtectionLifecyclePolicy.isProtectionEnabled(status)

    val isStartPending: Boolean get() = ProtectionLifecyclePolicy.isStartPending(status)

    val isStopPending: Boolean get() = ProtectionLifecyclePolicy.isStopPending(status)

    val isUptimeActive: Boolean get() = ProtectionLifecyclePolicy.isUptimeActive(status)

    val isAwaitingOnDemandReconnect: Boolean
        get() = ProtectionLifecyclePolicy.isAwaitingOnDemandReconnect(status, onDemandConfirmedEnabled)

    /** Whether a runtime is up or armed to come back: the "not fully off" projection. */
    val isActiveOrArmed: Boolean get() = isProtectionEnabled || isAwaitingOnDemandReconnect
}

/** Mutually exclusive lifecycle actions, ported from `ProtectionActionKind`. */
enum class ProtectionActionKind {
    TURN_ON,
    TURN_OFF,
    TOGGLE,
    RECONNECT,
    REFRESH_LISTS,
    PAUSE,
    RESUME,
    INSTALL_PROFILE,
    ADMIN_QA_PROFILE,
}

/**
 * Immutable single-flight gate, ported from `ProtectionActionOrchestrator`.
 *
 * [claim] fails (returns `null`) while any action owns the gate, which is what makes a duplicate
 * start a no-op rather than a second run. [supersede] models the canonical
 * `abortSupersededProtectionLifecycle`: an explicit stop forcibly takes over an in-flight start and
 * advances [generation] so the superseded completion is rejected as stale.
 */
data class ProtectionActionGate(
    val inFlight: ProtectionActionKind? = null,
    val generation: Long = 0L,
) {
    init {
        require(generation >= 0L) { "gate generation must not be negative" }
    }

    val isActionInFlight: Boolean get() = inFlight != null

    /** Try-once claim for an idle gate; `null` means the gate is occupied. */
    fun claim(kind: ProtectionActionKind): ProtectionActionGate? =
        if (inFlight != null) null else ProtectionActionGate(kind, generation + 1L)

    /** Forcibly takes over the gate, invalidating whatever was in flight. */
    fun supersede(kind: ProtectionActionKind): ProtectionActionGate =
        ProtectionActionGate(kind, generation + 1L)

    /** Releases only a matching claim, so stale cleanup cannot end a newer action. */
    fun release(kind: ProtectionActionKind): ProtectionActionGate =
        if (inFlight == kind) ProtectionActionGate(null, generation) else this
}

/**
 * First-run onboarding phase for the protection lifecycle.
 *
 * A fresh install ships the recommended configuration but its default filter artifacts are not yet
 * prepared (seeded + compiled/synced). Protection may only be requested once preparation reaches
 * [READY]; a failed preparation is surfaced as an actionable [ProtectionLifecycleRecovery].
 */
enum class OnboardingPhase {
    /** Fresh install (or a cancelled preparation): defaults seeded, artifacts not prepared. */
    FRESH_INSTALL,

    /** First-run default filter preparation is in flight; protection cannot start yet. */
    PREPARING_DEFAULT_FILTER,

    /** Defaults prepared; protection may be requested. */
    READY,

    /** Preparation failed; a retry is required before protection can start. */
    PREPARATION_FAILED,
}

/** The repair a recovery state offers, so a surface can present one concrete next action. */
enum class ProtectionRecoveryAction {
    RETRY_DEFAULT_FILTER_PREPARATION,
    RETRY_START,
    RETRY_STOP,
    RETRY_PAUSE,
    RETRY_RESUME,
}

/** Actionable error/recovery states; each names the single repair the user can take. */
sealed interface ProtectionLifecycleRecovery {
    val action: ProtectionRecoveryAction
    val message: String

    data class DefaultFilterPreparationFailed(
        override val message: String = "Could not prepare the default filter.",
    ) : ProtectionLifecycleRecovery {
        override val action: ProtectionRecoveryAction
            get() = ProtectionRecoveryAction.RETRY_DEFAULT_FILTER_PREPARATION
    }

    data class StartFailed(
        override val message: String = "Could not start protection.",
    ) : ProtectionLifecycleRecovery {
        override val action: ProtectionRecoveryAction get() = ProtectionRecoveryAction.RETRY_START
    }

    data class StopFailed(
        override val message: String = "Could not stop protection.",
    ) : ProtectionLifecycleRecovery {
        override val action: ProtectionRecoveryAction get() = ProtectionRecoveryAction.RETRY_STOP
    }

    data class PauseFailed(
        override val message: String = "Could not pause protection.",
    ) : ProtectionLifecycleRecovery {
        override val action: ProtectionRecoveryAction get() = ProtectionRecoveryAction.RETRY_PAUSE
    }

    /**
     * The pause was lifted but the filter configuration could not be refreshed. Protection is no
     * longer paused, so the repair is a resume retry (the canonical
     * "Resumed protection, but could not refresh filter" path), not another pause.
     */
    data class ResumeConfigurationUnavailable(
        override val message: String = "Resumed protection, but could not refresh the filter.",
    ) : ProtectionLifecycleRecovery {
        override val action: ProtectionRecoveryAction get() = ProtectionRecoveryAction.RETRY_RESUME
    }
}

/**
 * Immutable first-run protection lifecycle snapshot.
 *
 * [intent] is the REQUESTED side; [confirmation] is the CONFIRMED side. They are deliberately
 * separate values that are allowed to disagree (e.g. a committed stop while the platform still
 * reports `CONNECTED`). [pause] is a durable window that names the protection session that created
 * it; [protectionSessionId] is the current session, which may have advanced while the pause record
 * was persisted ([reconcilePause] clears such a stale window at read time rather than by mutation).
 */
data class ProtectionLifecycle(
    val onboarding: OnboardingPhase,
    val defaultFilterLevel: ProtectionLevel,
    val intent: ProtectionIntent,
    val confirmation: ProtectionConfirmation,
    val action: ProtectionActionKind? = null,
    val claimGeneration: Long = 0L,
    val runGeneration: Long = 0L,
    val protectionSessionId: Long = 0L,
    val pause: PauseState? = null,
    val recovery: ProtectionLifecycleRecovery? = null,
) {
    init {
        require(claimGeneration >= 0L) { "claim generation must not be negative" }
        require(runGeneration >= 0L) { "run generation must not be negative" }
        require(protectionSessionId >= 0L) { "protection session id must not be negative" }
    }

    /** The REQUESTED direction: what the user last chose. */
    val isRequestedEnabled: Boolean get() = intent.isEnabled

    /** The CONFIRMED direction: what the platform currently reports (or is armed to restore). */
    val isConfirmedEnabled: Boolean get() = confirmation.isActiveOrArmed

    val isStopPending: Boolean get() = confirmation.isStopPending

    val isConfiguring: Boolean get() = action != null

    val gate: ProtectionActionGate get() = ProtectionActionGate(action, claimGeneration)

    /** The next durable pause, or null when filtering is live. */
    val activePause: PauseState? get() = pause
}

/** Why a lifecycle transition changed nothing. */
enum class ProtectionLifecycleIgnore {
    /** Another lifecycle action owns the single-flight gate. */
    ACTION_IN_FLIGHT,

    /** Default-filter preparation was already requested. */
    ALREADY_PREPARING,

    /** The lifecycle is already past preparation. */
    ALREADY_READY,

    /** A preparation completion arrived with no preparation in flight. */
    NOT_PREPARING,

    /** No pause exists to resume or reconcile. */
    NOT_PAUSED,

    /** A pause already exists. */
    ALREADY_PAUSED,

    /** A completion arrived for a superseded or older claim. */
    STALE_CLAIM,
}

/** Why a lifecycle transition was refused. The state is returned unchanged. */
enum class ProtectionLifecycleRefusal {
    /** Onboarding has not prepared the default filter, so protection may not start. */
    PROTECTION_NOT_READY,

    /** Protection is not confirmed active/armed, so it cannot be paused. */
    PROTECTION_NOT_ENABLED,
}

sealed interface ProtectionLifecycleResult {
    val state: ProtectionLifecycle

    data class Applied(override val state: ProtectionLifecycle) : ProtectionLifecycleResult

    data class Ignored(
        override val state: ProtectionLifecycle,
        val reason: ProtectionLifecycleIgnore,
    ) : ProtectionLifecycleResult

    data class Refused(
        override val state: ProtectionLifecycle,
        val refusal: ProtectionLifecycleRefusal,
    ) : ProtectionLifecycleResult
}

/**
 * Pure transitions over [ProtectionLifecycle]. No Android types, no storage.
 *
 * A caller drives platform work by claiming an action ([requestStart] / [requestStop]) and then
 * reporting its terminal outcome ([completeStart] / [completeStop]) with the returned claim
 * generation, so a superseded completion can never apply.
 */
object ProtectionLifecycleMachine {

    /** A pristine fresh install: recommended level seeded, intent off, nothing confirmed. */
    fun freshInstall(
        defaultFilterLevel: ProtectionLevel = ProtectionLevel.BALANCED,
    ): ProtectionLifecycle = ProtectionLifecycle(
        onboarding = OnboardingPhase.FRESH_INSTALL,
        defaultFilterLevel = defaultFilterLevel,
        intent = ProtectionIntent.DisabledOnceLoaded,
        confirmation = ProtectionConfirmation(),
    )

    /** Begins (or retries) first-run default filter preparation. */
    fun beginDefaultFilterPreparation(state: ProtectionLifecycle): ProtectionLifecycleResult =
        when (state.onboarding) {
            OnboardingPhase.READY -> ignored(state, ProtectionLifecycleIgnore.ALREADY_READY)
            OnboardingPhase.PREPARING_DEFAULT_FILTER ->
                ignored(state, ProtectionLifecycleIgnore.ALREADY_PREPARING)

            OnboardingPhase.FRESH_INSTALL,
            OnboardingPhase.PREPARATION_FAILED,
            -> applied(state.copy(onboarding = OnboardingPhase.PREPARING_DEFAULT_FILTER, recovery = null))
        }

    /** Reports the terminal outcome of first-run default filter preparation. */
    fun completeDefaultFilterPreparation(
        state: ProtectionLifecycle,
        prepared: Boolean,
    ): ProtectionLifecycleResult {
        if (state.onboarding != OnboardingPhase.PREPARING_DEFAULT_FILTER) {
            return ignored(state, ProtectionLifecycleIgnore.NOT_PREPARING)
        }
        return if (prepared) {
            applied(state.copy(onboarding = OnboardingPhase.READY, recovery = null))
        } else {
            applied(
                state.copy(
                    onboarding = OnboardingPhase.PREPARATION_FAILED,
                    recovery = ProtectionLifecycleRecovery.DefaultFilterPreparationFailed(),
                ),
            )
        }
    }

    /**
     * Requests a start. Fails closed before onboarding is [OnboardingPhase.READY], and is ignored
     * while another action owns the gate — a duplicate start never creates a second run
     * ([ProtectionLifecycle.runGeneration] is unchanged).
     */
    fun requestStart(state: ProtectionLifecycle): ProtectionLifecycleResult {
        if (state.gate.inFlight != null) {
            return ignored(state, ProtectionLifecycleIgnore.ACTION_IN_FLIGHT)
        }
        if (state.onboarding != OnboardingPhase.READY) {
            return refused(state, ProtectionLifecycleRefusal.PROTECTION_NOT_READY)
        }
        val gate = state.gate.claim(ProtectionActionKind.TURN_ON)!!
        return applied(
            state.copy(
                action = gate.inFlight,
                claimGeneration = gate.generation,
                runGeneration = state.runGeneration + 1L,
                protectionSessionId = state.protectionSessionId + 1L,
                intent = state.intent.recordUserIntent(true),
                pause = null,
                recovery = null,
            ),
        )
    }

    /**
     * Requests a stop. An explicit stop supersedes an in-flight start (canonical
     * `abortSupersededProtectionLifecycle`): it forcibly takes the gate and advances the claim
     * generation so the abandoned start completion is rejected as stale. A stop while the default
     * filter is still being prepared simply cancels preparation back to [OnboardingPhase.FRESH_INSTALL].
     */
    fun requestStop(state: ProtectionLifecycle): ProtectionLifecycleResult {
        val occupiedByNonStart =
            state.gate.inFlight != null && state.gate.inFlight != ProtectionActionKind.TURN_ON
        if (occupiedByNonStart) {
            return ignored(state, ProtectionLifecycleIgnore.ACTION_IN_FLIGHT)
        }

        val onboarding = if (state.onboarding == OnboardingPhase.PREPARING_DEFAULT_FILTER) {
            OnboardingPhase.FRESH_INSTALL
        } else {
            state.onboarding
        }

        val hasRuntimeToStop =
            state.gate.inFlight == ProtectionActionKind.TURN_ON || state.confirmation.isActiveOrArmed
        val gate = if (hasRuntimeToStop) {
            state.gate.supersede(ProtectionActionKind.TURN_OFF)
        } else {
            state.gate
        }

        return applied(
            state.copy(
                onboarding = onboarding,
                action = gate.inFlight,
                claimGeneration = gate.generation,
                intent = state.intent.recordUserIntent(false),
                pause = null,
                recovery = null,
            ),
        )
    }

    /** Reports the terminal outcome of a start claim. A stale/superseded claim is ignored. */
    fun completeStart(
        state: ProtectionLifecycle,
        claimGeneration: Long,
        success: Boolean,
        message: String? = null,
    ): ProtectionLifecycleResult {
        if (!isCurrentClaim(state, ProtectionActionKind.TURN_ON, claimGeneration)) {
            return ignored(state, ProtectionLifecycleIgnore.STALE_CLAIM)
        }
        val gate = state.gate.release(ProtectionActionKind.TURN_ON)
        // A failed start leaves explicit intent ON: the requested state is never silently disabled
        // to settle into a stable-looking off state, and confirmed connectivity is never synthesized.
        val recovery = if (success) null else ProtectionLifecycleRecovery.StartFailed(
            message?.takeIf(String::isNotBlank) ?: "Could not start protection.",
        )
        return applied(state.copy(action = gate.inFlight, recovery = recovery))
    }

    /** Reports the terminal outcome of a stop claim. A stale/superseded claim is ignored. */
    fun completeStop(
        state: ProtectionLifecycle,
        claimGeneration: Long,
        success: Boolean,
        message: String? = null,
    ): ProtectionLifecycleResult {
        if (!isCurrentClaim(state, ProtectionActionKind.TURN_OFF, claimGeneration)) {
            return ignored(state, ProtectionLifecycleIgnore.STALE_CLAIM)
        }
        val gate = state.gate.release(ProtectionActionKind.TURN_OFF)
        val recovery = if (success) null else ProtectionLifecycleRecovery.StopFailed(
            message?.takeIf(String::isNotBlank) ?: "Could not stop protection.",
        )
        return applied(state.copy(action = gate.inFlight, recovery = recovery))
    }

    /**
     * Requests a durable pause bound to the active protection session. Explicit intent is
     * unchanged — a pause is a temporary filtering window, not a turn-off.
     */
    fun requestPause(
        state: ProtectionLifecycle,
        nowMillis: Long,
        duration: PauseDuration,
    ): ProtectionLifecycleResult {
        if (state.gate.inFlight != null) {
            return ignored(state, ProtectionLifecycleIgnore.ACTION_IN_FLIGHT)
        }
        if (state.pause != null) {
            return ignored(state, ProtectionLifecycleIgnore.ALREADY_PAUSED)
        }
        if (!state.confirmation.isActiveOrArmed || state.protectionSessionId <= 0L) {
            return refused(state, ProtectionLifecycleRefusal.PROTECTION_NOT_ENABLED)
        }
        val pause = PausePolicy.start(
            nowMillis = nowMillis,
            duration = duration,
            protectionSessionId = state.protectionSessionId,
        )
        return applied(state.copy(pause = pause, recovery = null))
    }

    /**
     * Requests a resume. A missing configuration does not silently do nothing: the pause is lifted
     * (the canonical resume command already ran) and an actionable recovery is surfaced so the
     * caller can retry the filter refresh. Intent is never changed.
     */
    fun requestResume(
        state: ProtectionLifecycle,
        configurationAvailable: Boolean,
    ): ProtectionLifecycleResult {
        if (state.gate.inFlight != null) {
            return ignored(state, ProtectionLifecycleIgnore.ACTION_IN_FLIGHT)
        }
        val awaitingResumeRetry =
            state.recovery is ProtectionLifecycleRecovery.ResumeConfigurationUnavailable
        if (state.pause == null && !awaitingResumeRetry) {
            return ignored(state, ProtectionLifecycleIgnore.NOT_PAUSED)
        }
        return if (configurationAvailable) {
            applied(state.copy(pause = null, recovery = null))
        } else {
            applied(
                state.copy(
                    pause = null,
                    recovery = ProtectionLifecycleRecovery.ResumeConfigurationUnavailable(),
                ),
            )
        }
    }

    /**
     * Reconciles a durable pause across a process restart or a time-source step. The persisted
     * deadline and session id are the only inputs: an expired deadline or a session that no longer
     * matches the active protection session clears the pause at read time (never by mutation).
     */
    fun reconcilePause(
        state: ProtectionLifecycle,
        nowMillis: Long,
    ): ProtectionLifecycleResult {
        val pause = state.pause ?: return ignored(state, ProtectionLifecycleIgnore.NOT_PAUSED)
        val restored = PausePolicy.restore(
            persistedDeadlineMillis = pause.deadlineMillis,
            persistedSessionId = pause.protectionSessionId,
            activeSessionId = state.protectionSessionId,
            nowMillis = nowMillis,
        )
        return if (restored == null) {
            applied(state.copy(pause = null))
        } else {
            applied(state.copy(pause = restored))
        }
    }

    /**
     * Records a confirmed platform observation. This is the ONLY writer of
     * [ProtectionLifecycle.confirmation]; it never touches explicit intent. A status that is no
     * longer active/armed clears any pause, because a pause cannot outlive the runtime it bounds.
     */
    fun confirmPlatformStatus(
        state: ProtectionLifecycle,
        status: ProtectionLifecycleStatus,
        onDemandConfirmedEnabled: Boolean,
    ): ProtectionLifecycleResult {
        val confirmation = ProtectionConfirmation(status, onDemandConfirmedEnabled)
        val pause = if (!confirmation.isActiveOrArmed) null else state.pause
        return applied(state.copy(confirmation = confirmation, pause = pause))
    }

    private fun isCurrentClaim(
        state: ProtectionLifecycle,
        kind: ProtectionActionKind,
        claimGeneration: Long,
    ): Boolean = state.action == kind && state.claimGeneration == claimGeneration

    private fun applied(state: ProtectionLifecycle): ProtectionLifecycleResult =
        ProtectionLifecycleResult.Applied(state)

    private fun ignored(
        state: ProtectionLifecycle,
        reason: ProtectionLifecycleIgnore,
    ): ProtectionLifecycleResult = ProtectionLifecycleResult.Ignored(state, reason)

    private fun refused(
        state: ProtectionLifecycle,
        refusal: ProtectionLifecycleRefusal,
    ): ProtectionLifecycleResult = ProtectionLifecycleResult.Refused(state, refusal)
}
