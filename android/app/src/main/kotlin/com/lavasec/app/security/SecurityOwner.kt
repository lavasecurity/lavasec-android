package com.lavasec.app.security

import com.lavasec.app.foundation.biometrics.BiometricAccessDecision
import com.lavasec.app.foundation.biometrics.BiometricAccessPolicy
import com.lavasec.app.foundation.biometrics.BiometricAvailability
import com.lavasec.app.foundation.biometrics.BiometricPresentation
import com.lavasec.app.foundation.biometrics.BiometricPromptResult
import com.lavasec.app.foundation.biometrics.BiometricSettings
import com.lavasec.app.foundation.security.LockTimeoutOption
import com.lavasec.app.foundation.security.ProtectedSurface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

enum class SecurityAvailability {
    Unavailable,
    Available,
}

/** Typed security failures; the UI maps these to copy, never to raw messages. */
enum class SecurityFailure {
    WRONG_PASSCODE,
    SECURE_STORAGE,
    OPERATION_FAILED,
}

data class SecurityPreferences(
    val enabled: Boolean = false,
    val lastUnlockEpochMillis: Long? = null,
    val lockTimeoutMinutes: Long = LockTimeoutOption.DEFAULT.minutes,
    val protectedSurfaces: Set<ProtectedSurface> = emptySet(),
    /** Durable conservative publication before a credential mutation; never a user choice. */
    val credentialChangePending: Boolean = false,
    /**
     * The durable biometric opt-in, mirroring iOS `securityBiometricEnabled`. Only ever effective
     * while a passcode exists and the platform can evaluate a usable biometric; disabling it never
     * clears the passcode gate. Cleared only on confirmed passcode removal.
     */
    val biometricEnabled: Boolean = false,
    /** Display consent is independent of conservative access gates. Null stays unknown. */
    val appUnlockCoverChoice: Boolean? = ProtectedSurface.APP_UNLOCK in protectedSurfaces,
)

data class SecurityOwnerState(
    val availability: SecurityAvailability = SecurityAvailability.Unavailable,
    val enabled: Boolean = false,
    val lastUnlockEpochMillis: Long? = null,
    /**
     * The epoch of the last successful passcode change. The Security screen uses
     * it as the durable close signal for the change dialog: a WRONG_PASSCODE
     * admission keeps the dialog open for the inline error, and only an
     * advancing epoch proves the verification succeeded.
     */
    val lastChangeEpochMillis: Long? = null,
    val lockTimeoutMinutes: Long = LockTimeoutOption.DEFAULT.minutes,
    val protectedSurfaces: Set<ProtectedSurface> = ProtectedSurface.ALL,
    val recoveryRequired: Boolean = false,
    val failure: SecurityFailure? = null,
    val inFlight: Boolean = false,
    /** In-memory view-turn grants; persisted unlock timestamps never authorize access. */
    val authenticatedSurfaces: Set<ProtectedSurface> = emptySet(),
    val authenticationRevision: Long = 0,
    val credentialsAuthenticated: Boolean = false,
    /** The durable biometric opt-in (raw); effective only via [BiometricAccessPolicy]. */
    val biometricEnabled: Boolean = false,
    /** The last reconciled platform enrollment/hardware state; [Unavailable] with no port. */
    val biometricAvailability: BiometricAvailability = BiometricAvailability.Unavailable,
    /** True while the platform locks the authenticator after too many failures. */
    val biometricLockout: Boolean = false,
    val appUnlockCoverChoice: Boolean? = if (availability == SecurityAvailability.Available && !recoveryRequired)
        ProtectedSurface.APP_UNLOCK in protectedSurfaces else null,
) {
    /** The canonical `BiometricSettings` pair the pure policy consumes. */
    internal fun biometricSettings(): BiometricSettings =
        BiometricSettings(passcodeEnabled = enabled, biometricEnabled = biometricEnabled)

    fun isAuthorized(surface: ProtectedSurface): Boolean =
        availability == SecurityAvailability.Available && !recoveryRequired &&
            (!enabled || surface !in protectedSurfaces || surface in authenticatedSurfaces)
}

/**
 * Generation-fenced owner for Android Keystore-backed app unlock. Every
 * passcode mutation runs through the serialized admission fence and the
 * injected [PasscodeVerifierPort]; a Keystore failure degrades to the honest
 * unavailable/recovery states instead of throwing. Protection choices persist;
 * grants belong only to the current in-memory navigation turn. Legacy timestamps
 * and timeout metadata cannot authorize a reopened process.
 *
 * The optional [BiometricAuthenticatorPort] mirrors the canonical iOS
 * biometric-then-passcode order: when a usable, enabled biometric exists the owner consults the
 * port first and only falls back to the injected passcode presentation. It defaults to the
 * fail-closed [UnavailableBiometricAuthenticatorPort]; no biometric is ever claimed without one.
 */
/**
 * The outcome of one authentication request. [CANCELLED] is distinct from [DENIED] so a caller can
 * suppress a user-dismissed prompt (passcode or biometric) instead of reporting an ordinary failure.
 */
enum class SecurityAuthorization { AUTHORIZED, DENIED, CANCELLED }

/** The result of the biometric-first phase: [FALLBACK] means the passcode should be presented. */
private enum class BiometricAttempt { AUTHORIZED, CANCELLED, FALLBACK }

/** Exact completion of one credential mutation, distinct from Boolean admission. */
enum class SecurityCredentialMutationResult { COMPLETED, WRONG_PASSCODE, SECURE_STORAGE, REFUSED }

/** Opaque native visit custody; retaining it never authorizes a locked read. */
class SecurityPresentationVisit internal constructor(private val remainsCurrent: () -> Boolean) {
    internal fun isCurrent(): Boolean = remainsCurrent()
}

class SecurityOwner private constructor(
    private val storage: SecurityStorage,
    scope: CoroutineScope,
) : AutoCloseable {
    constructor(
        verifier: PasscodeVerifierPort,
        preferences: SecurityPreferencesPort,
        scope: CoroutineScope,
        nowMillis: () -> Long = System::currentTimeMillis,
        biometricAuthenticator: BiometricAuthenticatorPort = UnavailableBiometricAuthenticatorPort,
    ) : this(SecurityStorage(verifier, preferences, nowMillis, biometricAuthenticator), scope)

    private val verifierPort get() = storage.verifier
    private val preferences get() = storage.preferences
    private val nowMillis get() = storage.nowMillis
    private val biometricAuthenticator get() = storage.biometricAuthenticator
    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private val admission get() = storage.admission
    private val stateLock get() = storage.stateLock
    private var durable: SecurityPreferences?
        get() = storage.durable
        set(value) { storage.durable = value }
    private val mutableState = synchronized(stateLock) {
        storage.state ?: MutableStateFlow(initialState()).also { storage.state = it }
    }

    /** Each RN/native graph receives a revocable handle over one process-wide storage authority. */
    fun openSession(scope: CoroutineScope): SecurityOwner {
        check(!closed.get()) { "Security owner closed" }
        return SecurityOwner(storage, scope)
    }

    val state: StateFlow<SecurityOwnerState> = mutableState.asStateFlow()

    private fun initialState(): SecurityOwnerState = reconcile(SecurityOwnerState())

    /** Navigation retires readers and pending view authentication, not foreground App Unlock. */
    fun resetAuthenticationTurn(preservingCredentials: Boolean = false): Long = synchronized(stateLock) {
        storage.authenticatedSurfaces = storage.authenticatedSurfaces.intersect(setOf(ProtectedSurface.APP_UNLOCK))
        if (!preservingCredentials) storage.credentialsAuthenticated = false
        storage.suspendedVisit = null
        storage.pausedSession = null
        storage.authenticationRevision++
        retirePending(viewOnly = true)
        publish(mutableState.value)
        storage.authenticationRevision
    }

    /** The actual native host owns the foreground. A successor cannot inherit its grants or intent. */
    fun enterForeground(owner: Any, generation: Long, visit: SecurityPresentationVisit?): Long = synchronized(stateLock) {
        val paused = storage.pausedSession
        if (storage.foregroundOwner !== owner || storage.foregroundGeneration != generation) {
            revokeForeground(clearVisit = true)
            storage.foregroundOwner = owner
            storage.foregroundGeneration = generation
        } else if (storage.suspendedVisit?.visit !== visit || visit?.isCurrent() != true) {
            storage.suspendedVisit = null
        }
        storage.pausedSession = null
        if (paused != null && !paused.owner.closed.get() && paused.owner.ownerJob.isActive &&
            paused.host === owner && paused.generation == generation &&
            paused.revision == storage.authenticationRevision && paused.visit === visit &&
            (visit == null || visit.isCurrent()) && mutableState.value.availability == SecurityAvailability.Available &&
            !mutableState.value.recoveryRequired) {
            storage.authenticatedSurfaces = paused.surfaces
            storage.credentialsAuthenticated = paused.credentials
        }
        storage.foreground = true
        publish(mutableState.value)
        storage.authenticationRevision
    }

    fun ownsForeground(owner: Any, generation: Long): Boolean = synchronized(stateLock) {
        !closed.get() && ownerJob.isActive && storage.foreground &&
            storage.foregroundOwner === owner && storage.foregroundGeneration == generation
    }

    /** An interruption retires reads/tickets, but is not a new app-unlock session. */
    fun pauseForeground(owner: Any, generation: Long, visit: SecurityPresentationVisit?): Long = synchronized(stateLock) {
        if (storage.foregroundOwner !== owner || storage.foregroundGeneration != generation || !storage.foreground) {
            return@synchronized storage.authenticationRevision
        }
        val surfaces = storage.authenticatedSurfaces
        val credentials = storage.credentialsAuthenticated
        revokeForeground(clearVisit = false)
        storage.pausedSession = PausedSecuritySession(this, owner, generation, visit, surfaces, credentials, storage.authenticationRevision)
        storage.foreground = false
        publish(mutableState.value)
        storage.authenticationRevision
    }

    /** Actual background revokes the session; only Open Lava can retain a visit for one app unlock. */
    fun suspendForeground(owner: Any, generation: Long, visit: SecurityPresentationVisit?): Long = synchronized(stateLock) {
        if (storage.foregroundOwner !== owner || storage.foregroundGeneration != generation ||
            !storage.foreground && storage.pausedSession == null) {
            return@synchronized storage.authenticationRevision
        }
        val paused = storage.pausedSession
        val readers = (paused?.surfaces ?: storage.authenticatedSurfaces).intersect(setOf(
            ProtectedSurface.FILTERS, ProtectedSurface.ACTIVITY, ProtectedSurface.APP_SETTINGS,
        ))
        val credentials = paused?.credentials ?: storage.credentialsAuthenticated
        if (SecurityPrivacyPolicy.requiresBackgroundCover(mutableState.value) && visit != null) {
            if (readers.isNotEmpty() || credentials) {
                storage.suspendedVisit = SuspendedSecurityVisit(this, visit, readers, credentials)
            } else if (storage.suspendedVisit?.visit !== visit) storage.suspendedVisit = null
        } else {
            storage.suspendedVisit = null
        }
        revokeForeground(clearVisit = false)
        storage.foreground = false
        publish(mutableState.value)
        storage.authenticationRevision
    }

    /** Read-free exact retirement; an old Activity cannot revoke a newer foreground. */
    fun retireForeground(owner: Any): Long = synchronized(stateLock) {
        if (storage.foregroundOwner === owner) {
            revokeForeground(clearVisit = true)
            storage.foregroundOwner = null
            storage.foregroundGeneration = null
            storage.foreground = false
            publish(mutableState.value)
        }
        storage.authenticationRevision
    }

    fun retirePresentationVisit(visit: SecurityPresentationVisit) = synchronized(stateLock) {
        if (storage.suspendedVisit?.visit === visit) storage.suspendedVisit = null
        if (storage.pausedSession?.visit === visit) storage.pausedSession = null
    }

    private fun revokeForeground(clearVisit: Boolean) {
        storage.pausedSession = null
        storage.authenticatedSurfaces = emptySet()
        storage.credentialsAuthenticated = false
        if (clearVisit) storage.suspendedVisit = null
        storage.foregroundRevision++
        storage.authenticationRevision++
        retirePending(viewOnly = false)
    }

    private fun retirePending(viewOnly: Boolean) {
        storage.pendingAuthentication?.takeIf { !viewOnly || it.request.surface != ProtectedSurface.APP_UNLOCK }?.let {
            storage.pendingAuthentication = null
            it.result.complete(false)
            biometricAuthenticator.cancel()
        }
    }

    private fun ticketCurrent(pending: PendingSecurityAuthentication): Boolean = storage.foreground &&
        (if (pending.request.surface == ProtectedSurface.APP_UNLOCK)
            pending.foregroundRevision == storage.foregroundRevision
         else pending.request.revision == storage.authenticationRevision)


    /**
     * Port of SecurityController.requireAuthentication / requireCredentialAuthentication.
     * Null surface is the credentials purpose, never an unprotected screen. The presenter
     * receives an opaque request, never an authorization result supplied by RN.
     */
    suspend fun requireAuthentication(
        surface: ProtectedSurface?,
        fresh: Boolean = false,
        present: suspend (SecurityAuthenticationRequest) -> Unit,
    ): Boolean = requireAuthenticationOutcome(surface, fresh, present) == SecurityAuthorization.AUTHORIZED

    /**
     * The tri-state form. [SecurityAuthorization.CANCELLED] distinguishes a user-dismissed prompt
     * (passcode dialog or biometric) from [SecurityAuthorization.DENIED], so a caller can suppress
     * the cancellation copy.
     */
    suspend fun requireAuthenticationOutcome(
        surface: ProtectedSurface?,
        fresh: Boolean = false,
        present: suspend (SecurityAuthenticationRequest) -> Unit,
    ): SecurityAuthorization = coroutineScope {
        val pending = synchronized(stateLock) {
            val current = mutableState.value
            if (closed.get() || !ownerJob.isActive || !storage.foreground || current.availability != SecurityAvailability.Available || current.recoveryRequired) {
                return@coroutineScope SecurityAuthorization.DENIED
            }
            val restoreVisit = surface == ProtectedSurface.APP_UNLOCK && storage.suspendedVisit?.let {
                !it.owner.closed.get() && it.owner.ownerJob.isActive && it.visit.isCurrent()
            } == true
            if (!current.enabled || (surface != null && surface !in current.protectedSurfaces && !restoreVisit)) {
                return@coroutineScope SecurityAuthorization.AUTHORIZED
            }
            if (!fresh && (if (surface == null) storage.credentialsAuthenticated
                else surface in storage.authenticatedSurfaces)) return@coroutineScope SecurityAuthorization.AUTHORIZED
            // Shared across native/RN handles; a second request cannot steal a prompt's result.
            if (storage.pendingAuthentication != null) return@coroutineScope SecurityAuthorization.DENIED
            PendingSecurityAuthentication(
                SecurityAuthenticationRequest(UUID.randomUUID().toString(), surface, storage.authenticationRevision),
                this@SecurityOwner,
                storage.foregroundRevision,
            ).also { storage.pendingAuthentication = it }
        }
        val biometricCancelled = AtomicBoolean(false)
        val retirement = ownerJob.invokeOnCompletion { cancelAuthentication(pending.request.id) }
        val presenter = launch {
            try {
                // Canonical iOS `authenticate`: biometrics first, passcode only as the fallback.
                // `attemptBiometric` resolves the request itself when the prompt decides it
                // (success grants, hard cancel denies); otherwise the passcode is presented.
                when (attemptBiometric(pending)) {
                    BiometricAttempt.AUTHORIZED -> Unit
                    // A hard user cancel resolves the request WITHOUT presenting the passcode; mark
                    // it so the caller reports cancellation, not an ordinary denial.
                    BiometricAttempt.CANCELLED -> {
                        biometricCancelled.set(true)
                        cancelAuthentication(pending.request.id)
                    }
                    BiometricAttempt.FALLBACK -> present(pending.request)
                }
            } finally {
                cancelAuthentication(pending.request.id)
            }
        }
        try {
            val accepted = pending.result.await()
            val granted = accepted && synchronized(stateLock) {
                !closed.get() && ownerJob.isActive && ticketCurrent(pending) &&
                    mutableState.value.availability == SecurityAvailability.Available &&
                    !mutableState.value.recoveryRequired
            }
            when {
                granted -> SecurityAuthorization.AUTHORIZED
                biometricCancelled.get() || pending.cancelledByUser -> SecurityAuthorization.CANCELLED
                else -> SecurityAuthorization.DENIED
            }
        } finally {
            cancelAuthentication(pending.request.id)
            presenter.cancel()
            retirement.dispose()
        }
    }

    /** True means THIS request verified, unlike legacy unlock's mutation-admission Boolean. */
    suspend fun completeAuthentication(requestId: String, code: String): Boolean {
        var verified: PendingSecurityAuthentication? = null
        val admitted = mutate {
            val pending = synchronized(stateLock) {
                storage.pendingAuthentication?.takeIf {
                    it.owner === this && it.request.id == requestId &&
                        ticketCurrent(it)
                }
            } ?: return@mutate mutableState.value
            if (!canEdit() || !mutableState.value.enabled) return@mutate refused()
            when (verifierPort.verify(code)) {
                PasscodeVerification.Wrong -> mutableState.value.copy(failure = SecurityFailure.WRONG_PASSCODE)
                PasscodeVerification.Unavailable -> reconcile(mutableState.value, SecurityFailure.SECURE_STORAGE)
                PasscodeVerification.Verified -> {
                    verified = pending
                    mutableState.value.copy(failure = null)
                }
            }
        }
        if (!admitted) return false
        return grantPending(verified ?: return false)
    }

    /**
     * Completes a pending request from biometric evidence. The platform prompt ran outside the
     * mutation fence, so the authenticator's effectiveness is re-validated under the fence before
     * the request can be granted; a background/rotation/stale callback leaves it unresolved.
     */
    suspend fun completeBiometricAuthentication(requestId: String): Boolean {
        val pending = synchronized(stateLock) {
            storage.pendingAuthentication?.takeIf {
                it.owner === this && it.request.id == requestId &&
                    ticketCurrent(it)
            }
        } ?: return false
        val availability = currentBiometricAvailability()
        var effective = false
        val admitted = mutate {
            if (!canEdit() || !mutableState.value.enabled) return@mutate refused()
            if (!BiometricAccessPolicy.effectiveEnabled(mutableState.value.biometricSettings(), availability)) {
                return@mutate mutableState.value.copy(failure = SecurityFailure.OPERATION_FAILED)
            }
            effective = true
            mutableState.value.copy(
                failure = null,
                biometricAvailability = availability,
                biometricLockout = currentBiometricLockout(),
            )
        }
        if (!admitted || !effective) return false
        return grantPending(pending)
    }

    /**
     * The shared, race-fenced grant. A cancelled/retired request, a turn that changed while the
     * evidence was being produced, or a disabled gate can never use that evidence as a grant.
     */
    private fun grantPending(pending: PendingSecurityAuthentication): Boolean = synchronized(stateLock) {
        if (closed.get() || !ownerJob.isActive || storage.pendingAuthentication !== pending ||
            !ticketCurrent(pending) || !pending.result.isActive ||
            !canEdit() || !mutableState.value.enabled) return@synchronized false
        if (pending.request.surface == null) storage.credentialsAuthenticated = true
        else {
            storage.authenticatedSurfaces += pending.request.surface
            if (pending.request.surface == ProtectedSurface.APP_UNLOCK) {
                storage.suspendedVisit?.takeIf {
                    !it.owner.closed.get() && it.owner.ownerJob.isActive && it.visit.isCurrent()
                }?.let {
                    storage.authenticatedSurfaces += it.surfaces.intersect(mutableState.value.protectedSurfaces)
                    storage.credentialsAuthenticated = storage.credentialsAuthenticated || it.credentials
                }
                storage.suspendedVisit = null
            }
        }
        storage.pendingAuthentication = null
        publish(mutableState.value.copy(failure = null))
        // A synchronous observer may revoke the turn while receiving publication.
        val accepted = !closed.get() && ownerJob.isActive &&
            ticketCurrent(pending)
        pending.result.complete(accepted)
        accepted
    }

    /**
     * Runs the canonical biometric-first step for a live request. Returns true when the request was
     * resolved by the prompt (success grants, hard cancel denies) and false when the passcode must
     * be presented. Lockout, missing enrollment and recoverable failures all fall back.
     */
    private suspend fun attemptBiometric(pending: PendingSecurityAuthentication): BiometricAttempt {
        val state = synchronized(stateLock) { mutableState.value }
        val availability = currentBiometricAvailability()
        // `presentation` is the pure policy: it only offers when the passcode exists, the durable
        // opt-in is set, and the authenticator is usable. Anything else is the passcode fallback.
        val presentation = BiometricAccessPolicy.presentation(
            BiometricSettings(passcodeEnabled = state.enabled, biometricEnabled = state.biometricEnabled),
            availability,
        )
        // Re-read the platform lockout rather than trusting the cached flag: a nominal 30-second
        // lockout must expire. Republish when it changed so the snapshot stays truthful.
        val lockedOut = currentBiometricLockout()
        if (lockedOut != state.biometricLockout) {
            publish(mutableState.value.copy(biometricLockout = lockedOut))
        }
        if (presentation !is BiometricPresentation.Offer || lockedOut) {
            return BiometricAttempt.FALLBACK
        }
        // A request already resolved (background/rotation) must not receive a new prompt; the
        // pending result decides, so report AUTHORIZED and skip the passcode.
        if (!isPendingCurrent(pending)) return BiometricAttempt.AUTHORIZED
        val result = try {
            biometricAuthenticator.authenticate(BIOMETRIC_PURPOSE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A platform error is a recoverable failure; never a fabricated success.
            BiometricPromptResult.Failed
        }
        val decision = BiometricAccessPolicy.decisionFor(result, availability)
        return when (decision) {
            // Success is granted through the owner, not by the port; the grant re-validates. If the
            // request is still live after a failed grant (a concurrent mutation held admission, or
            // the gate closed under the prompt) the passcode is the fallback; a request revoked
            // while the prompt ran is already resolved and must not reopen the passcode sheet.
            BiometricAccessDecision.AUTHENTICATED -> {
                completeBiometricAuthentication(pending.request.id)
                if (isPendingCurrent(pending)) BiometricAttempt.FALLBACK else BiometricAttempt.AUTHORIZED
            }

            // A hard user cancel resolves the request without the passcode fallback; the caller
            // records the cancellation. Do not cancel here — the caller marks it before resolving.
            BiometricAccessDecision.DENIED -> BiometricAttempt.CANCELLED

            // Lockout / fallback / failure / non-available evidence: present the passcode, but only
            // for a request that is still live; one cancelled during the prompt is already resolved.
            BiometricAccessDecision.FALLBACK_TO_PASSCODE,
            BiometricAccessDecision.UNAVAILABLE,
            -> {
                if (result is BiometricPromptResult.Lockout) noteBiometricLockout()
                if (isPendingCurrent(pending)) BiometricAttempt.FALLBACK else BiometricAttempt.AUTHORIZED
            }
        }
    }

    private fun isPendingCurrent(pending: PendingSecurityAuthentication): Boolean =
        synchronized(stateLock) {
            storage.pendingAuthentication === pending && pending.result.isActive && ticketCurrent(pending)
        }

    fun cancelAuthentication(requestId: String, userCancelled: Boolean = false) = synchronized(stateLock) {
        storage.pendingAuthentication?.takeIf { it.owner === this && it.request.id == requestId }?.let {
            storage.pendingAuthentication = null
            it.cancelledByUser = userCancelled
            it.result.complete(false)
            // Revoke the platform prompt so a cancelled/retired request cannot deliver a late
            // success through a stale callback.
            biometricAuthenticator.cancel()
        }
        Unit
    }

    /** The canonical durability of a biometric lockout: the passcode remains the gate. */
    private fun noteBiometricLockout() {
        publish(mutableState.value.copy(biometricLockout = true))
    }

    private fun currentBiometricAvailability(): BiometricAvailability =
        runCatching { biometricAuthenticator.availability() }
            .getOrDefault(BiometricAvailability.Unavailable)

    /**
     * Re-query the mutable platform biometric facts (enrollment, hardware, temporary lockout) and
     * publish any change. The snapshot producer calls this before reading, so a user who enrolls a
     * biometric in system settings — or whose lockout expires — without a process restart sees the
     * current capability instead of the value captured at construction.
     */
    fun refreshBiometricFacts() {
        // Read, compare and publish the two platform facts inside ONE critical section. Reading
        // them before the lock lets a concurrent mutation (e.g. `setBiometrics` regenerating the
        // key and publishing `Available`) land in between, and this block would then republish the
        // stale facts over it. `publish` takes the same reentrant lock.
        synchronized(stateLock) {
            val availability = currentBiometricAvailability()
            val lockout = currentBiometricLockout()
            val current = mutableState.value
            if (availability != current.biometricAvailability || lockout != current.biometricLockout) {
                publish(current.copy(biometricAvailability = availability, biometricLockout = lockout))
            }
        }
    }

    private fun currentBiometricLockout(): Boolean =
        runCatching { biometricAuthenticator.isLockedOut() }.getOrDefault(false)

    /** Legacy callers observe admission; native credential UI uses the exact result methods. */
    suspend fun setPasscode(code: String): Boolean = mutate { setPasscodeState(code) }
    suspend fun changePasscode(current: String, new: String): Boolean = mutate { changePasscodeState(current, new) }
    suspend fun removePasscode(code: String): Boolean = mutate { removePasscodeState(code) }

    suspend fun setPasscodeResult(code: String): SecurityCredentialMutationResult =
        mutateCredential(expectedEnabled = true) { setPasscodeState(code) }
    suspend fun changePasscodeResult(current: String, new: String): SecurityCredentialMutationResult =
        mutateCredential(expectedEnabled = true) { changePasscodeState(current, new) }
    suspend fun removePasscodeResult(code: String): SecurityCredentialMutationResult =
        mutateCredential(expectedEnabled = false) { removePasscodeState(code) }

    private fun setPasscodeState(code: String): SecurityOwnerState {
        // Setup cannot replace an existing credential without current-passcode verification.
        if (!canEdit() || mutableState.value.enabled) return refused()
        return changeCredential { verifierPort.configure(code) }
    }
    private fun changePasscodeState(current: String, new: String): SecurityOwnerState {
        if (!canEdit() || !mutableState.value.enabled) return refused()
        return when (verifierPort.verify(current)) {
            PasscodeVerification.Verified -> changeCredential(isChange = true) { verifierPort.configure(new) }
            PasscodeVerification.Wrong -> mutableState.value.copy(failure = SecurityFailure.WRONG_PASSCODE)
            PasscodeVerification.Unavailable -> reconcile(mutableState.value, SecurityFailure.SECURE_STORAGE)
        }
    }
    private fun removePasscodeState(code: String): SecurityOwnerState {
        if (!canEdit() || !mutableState.value.enabled) return refused()
        return when (verifierPort.verify(code)) {
            PasscodeVerification.Verified -> changeCredential { verifierPort.remove() }
            PasscodeVerification.Wrong -> mutableState.value.copy(failure = SecurityFailure.WRONG_PASSCODE)
            PasscodeVerification.Unavailable -> reconcile(mutableState.value, SecurityFailure.SECURE_STORAGE)
        }
    }

    private suspend fun mutateCredential(expectedEnabled: Boolean, operation: () -> SecurityOwnerState): SecurityCredentialMutationResult =
        owned(SecurityCredentialMutationResult.REFUSED) {
            // This value belongs to this operation inside admission, never a later
            // global StateFlow value that a sibling mutation may already replace.
            var result = SecurityCredentialMutationResult.SECURE_STORAGE
            val admitted = mutateAdmitted {
                operation().also { next ->
                    result = when {
                        next.failure == SecurityFailure.WRONG_PASSCODE -> SecurityCredentialMutationResult.WRONG_PASSCODE
                        next.failure == SecurityFailure.OPERATION_FAILED -> SecurityCredentialMutationResult.REFUSED
                        next.failure != null || next.availability != SecurityAvailability.Available || next.recoveryRequired ->
                            SecurityCredentialMutationResult.SECURE_STORAGE
                        next.enabled == expectedEnabled -> SecurityCredentialMutationResult.COMPLETED
                        else -> SecurityCredentialMutationResult.REFUSED
                    }
                }
            }
            if (admitted && !closed.get() && ownerJob.isActive) result else SecurityCredentialMutationResult.REFUSED
        }

    suspend fun unlock(code: String, surface: ProtectedSurface? = null, expectedRevision: Long? = null): Boolean {
        val revision = expectedRevision ?: synchronized(stateLock) { storage.authenticationRevision }
        return mutate {
            if (durable == null) return@mutate unavailable(mutableState.value)
            when (verifierPort.verify(code)) {
                PasscodeVerification.Verified -> {
                    val reconciled = reconcile(mutableState.value)
                    if (reconciled.availability == SecurityAvailability.Available && reconciled.enabled) {
                        val next = commit(reconciled.copy(lastUnlockEpochMillis = nowMillis(), failure = null))
                        synchronized(stateLock) {
                            if (!closed.get() && revision == storage.authenticationRevision && surface != null) {
                                storage.authenticatedSurfaces = storage.authenticatedSurfaces + surface
                            }
                        }
                        next
                    } else reconciled
                }
                PasscodeVerification.Wrong -> mutableState.value.copy(failure = SecurityFailure.WRONG_PASSCODE)
                PasscodeVerification.Unavailable -> reconcile(mutableState.value, SecurityFailure.SECURE_STORAGE)
            }
        }
    }

    suspend fun selectLockTimeout(option: LockTimeoutOption): Boolean = mutate {
        if (!canEdit()) return@mutate refused()
        commit(mutableState.value.copy(lockTimeoutMinutes = option.minutes))
    }

    /**
     * Port of canonical `setBiometricEnabled`. Enabling is refused (persisted off) unless a
     * passcode exists and the platform can evaluate a usable, enrolled biometric; disabling always
     * succeeds and leaves the passcode gate and its protected-surface choices untouched.
     */
    suspend fun setBiometrics(
        enabled: Boolean,
        enrollmentKeyResetAuthorized: Boolean = false,
        isCurrent: () -> Boolean = { true },
    ): Boolean = mutate {
        if (!isCurrent() || !canEdit() || !mutableState.value.enabled) return@mutate refused()
        var availability = currentBiometricAvailability()
        // A permanently invalidated enrollment key (a new biometric was enrolled) would otherwise
        // block an enable forever. Regenerating the enrollment-bound key clears the invalidation
        // that forces the passcode fallback, so it is a security-state mutation: run it only when
        // the caller proved a fresh authentication (the passcode, since the biometric key is
        // unusable) through `MainProductViewModel.securitySetBiometrics`. An unauthenticated call
        // leaves availability KeyInvalidated, so the enable policy still refuses rather than
        // silently binding the newly enrolled biometric.
        if (enabled && availability == BiometricAvailability.KeyInvalidated &&
            enrollmentKeyResetAuthorized &&
            biometricAuthenticator.resetEnrollmentBoundKey()
        ) {
            availability = currentBiometricAvailability()
        }
        val settings = BiometricAccessPolicy.setBiometricEnabled(
            mutableState.value.biometricSettings(), enabled, availability,
        )
        commit(mutableState.value.copy(
            biometricEnabled = settings.biometricEnabled,
            biometricAvailability = availability,
            biometricLockout = currentBiometricLockout(),
        ))
    }

    suspend fun setProtectedSurface(surface: ProtectedSurface, enabled: Boolean, isCurrent: () -> Boolean = { true }): Boolean {
        var written = false
        val accepted = mutate {
            if (!isCurrent() || !canEdit() || !mutableState.value.enabled) return@mutate refused()
            val previous = mutableState.value
            resetAuthenticationTurn(preservingCredentials = true)
            if (surface == ProtectedSurface.APP_UNLOCK) synchronized(stateLock) {
                storage.authenticatedSurfaces = if (enabled) storage.authenticatedSurfaces + surface
                    else storage.authenticatedSurfaces - surface
            }
            commit(previous.copy(
                protectedSurfaces = if (enabled) previous.protectedSurfaces + surface
                    else previous.protectedSurfaces - surface,
                appUnlockCoverChoice = if (surface == ProtectedSurface.APP_UNLOCK) enabled else previous.appUnlockCoverChoice,
            )).also { written = true }
        }
        return accepted && written
    }

    /** Recovery is only available for an unreadable record, never a shortcut around a valid code. */
    suspend fun resetAfterRecoveryFailure(): Boolean = mutate {
        if (!mutableState.value.recoveryRequired ||
            verifierPort.credentialState() != PasscodeCredentialState.Unavailable
        ) return@mutate refused()
        changeCredential { verifierPort.remove() }
    }

    private fun canEdit(): Boolean = durable != null &&
        mutableState.value.availability == SecurityAvailability.Available &&
        !mutableState.value.recoveryRequired

    /** Public recovery only: refresh storage classification without authorizing a protected action. */
    suspend fun retryAvailability(): Boolean = mutate {
        durable = runCatching { preferences.load() }.getOrNull()
        reconcile(mutableState.value)
    }

    private fun refused(): SecurityOwnerState = mutableState.value.copy(failure = SecurityFailure.OPERATION_FAILED)

    /** Port of prepareCredentialChange -> keychain mutation -> classified reconciliation. */
    private fun changeCredential(isChange: Boolean = false, action: () -> Boolean): SecurityOwnerState {
        val previous = mutableState.value
        val saved = durable ?: return unavailable(previous)
        synchronized(stateLock) { revokeForeground(clearVisit = true) }
        // commit() must reach disk before making a new credential usable. All Android app/service
        // readers are in the same process; SharedPreferences is the atomic Android record here.
        persist(saved.copy(credentialChangePending = true, lastUnlockEpochMillis = null))
        publish(unavailable(previous))
        val changed = try { action() } catch (_: Exception) { false }
        val reconciled = reconcile(previous, if (changed) null else SecurityFailure.SECURE_STORAGE)
        return if (changed && reconciled.availability == SecurityAvailability.Available && reconciled.enabled) {
            // A failed final publication never manufactures an unlocked session or a success signal.
            commit(reconciled.copy(
                lastUnlockEpochMillis = nowMillis(),
                lastChangeEpochMillis = if (isChange) nowMillis() else reconciled.lastChangeEpochMillis,
            ))
        } else reconciled
    }

    /** Keep stored opt-in choices separate from the temporary, conservative effective gates. */
    private fun reconcile(previous: SecurityOwnerState, failure: SecurityFailure? = null): SecurityOwnerState {
        val saved = durable ?: return unavailable(previous)
        val credential = runCatching { verifierPort.credentialState() }
            .getOrDefault(PasscodeCredentialState.Unavailable)
        val next = saved.copy(
            enabled = credential == PasscodeCredentialState.Available,
            lastUnlockEpochMillis = saved.lastUnlockEpochMillis.takeIf {
                credential == PasscodeCredentialState.Available && !saved.credentialChangePending
            },
            protectedSurfaces = if (credential == PasscodeCredentialState.Absent) emptySet()
                else if (saved.appUnlockCoverChoice == false) saved.protectedSurfaces - ProtectedSurface.APP_UNLOCK
                else saved.protectedSurfaces,
            appUnlockCoverChoice = if (credential == PasscodeCredentialState.Absent) false else saved.appUnlockCoverChoice,
            credentialChangePending = credential == PasscodeCredentialState.Unavailable,
            // Canonical `clearSecurityPreferencesAfterPasscodeRemoval`: only confirmed removal
            // clears the biometric opt-in; a transient Keystore failure never does.
            biometricEnabled = if (credential == PasscodeCredentialState.Absent) false
                else saved.biometricEnabled,
        )
        try { persist(next) } catch (_: Exception) { return unavailable(previous) }
        if (credential == PasscodeCredentialState.Unavailable) {
            val canRecover = runCatching { verifierPort.availability() }
                .getOrNull() == PasscodeVerifierAvailability.Available
            return unavailable(previous).copy(
                availability = if (canRecover) SecurityAvailability.Available else SecurityAvailability.Unavailable,
                recoveryRequired = canRecover,
            )
        }
        return previous.copy(
            availability = SecurityAvailability.Available,
            enabled = next.enabled,
            lastUnlockEpochMillis = next.lastUnlockEpochMillis,
            lockTimeoutMinutes = next.lockTimeoutMinutes,
            protectedSurfaces = next.protectedSurfaces,
            appUnlockCoverChoice = next.appUnlockCoverChoice,
            biometricEnabled = next.biometricEnabled,
            biometricAvailability = currentBiometricAvailability(),
            biometricLockout = currentBiometricLockout(),
            recoveryRequired = false,
            failure = failure,
        )
    }

    private companion object {
        /** Stable prompt reason; the platform adapter owns any localization. */
        const val BIOMETRIC_PURPOSE = "Unlock Lava"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(stateLock) {
            storage.pendingAuthentication?.takeIf { it.owner === this }?.let { cancelAuthentication(it.request.id) }
            if (storage.suspendedVisit?.owner === this) storage.suspendedVisit = null
            if (storage.pausedSession?.owner === this) storage.pausedSession = null
        }
        ownerJob.cancel()
    }

    private fun persist(next: SecurityPreferences) {
        preferences.save(next)
        durable = next
        storage.coverChoice = next.appUnlockCoverChoice
    }

    private fun commit(next: SecurityOwnerState): SecurityOwnerState {
        persist(SecurityPreferences(
            enabled = next.enabled,
            lastUnlockEpochMillis = next.lastUnlockEpochMillis,
            lockTimeoutMinutes = next.lockTimeoutMinutes,
            protectedSurfaces = next.protectedSurfaces,
            biometricEnabled = next.biometricEnabled,
            appUnlockCoverChoice = next.appUnlockCoverChoice,
        ))
        return next
    }

    private fun unavailable(previous: SecurityOwnerState): SecurityOwnerState = previous.copy(
        availability = SecurityAvailability.Unavailable,
        lastUnlockEpochMillis = null,
        lastChangeEpochMillis = null,
        protectedSurfaces = ProtectedSurface.ALL,
        appUnlockCoverChoice = storage.coverChoice,
        // A transient unavailability never clears the durable biometric opt-in, but no biometric
        // can be effective or offered while the owner cannot read its credential.
        biometricAvailability = BiometricAvailability.Unavailable,
        biometricLockout = false,
        failure = SecurityFailure.SECURE_STORAGE,
    )

    private suspend fun mutate(operation: suspend () -> SecurityOwnerState): Boolean = owned(false) {
        mutateAdmitted(operation)
    }

    private suspend fun mutateAdmitted(operation: suspend () -> SecurityOwnerState): Boolean {
        if (closed.get() || !admission.tryLock()) return false
        publish(mutableState.value.copy(inFlight = true, failure = null))
        try {
            publish(operation())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A thrown persistence operation may have reached memory but not disk. Refuse
            // access until an authoritative credential read and successful publication recover it.
            publish(unavailable(mutableState.value))
        } finally {
            publish(mutableState.value.copy(inFlight = false))
            admission.unlock()
        }
        return true
    }

    private fun publish(state: SecurityOwnerState) {
        synchronized(stateLock) {
            // A synchronous disk write can finish while its view retires. Publish that durable
            // result to sibling handles; retirement only revokes this handle's future commands.
            if (state.availability != SecurityAvailability.Available || state.recoveryRequired || !state.enabled) {
                storage.authenticatedSurfaces = emptySet()
                storage.credentialsAuthenticated = false
                storage.suspendedVisit = null
                storage.pausedSession = null
                val hadPending = storage.pendingAuthentication != null
                storage.pendingAuthentication?.result?.complete(false)
                storage.pendingAuthentication = null
                // A gate that closed under a live prompt revokes it; no late success may grant.
                if (hadPending) biometricAuthenticator.cancel()
            }
            mutableState.value = state.copy(
                authenticatedSurfaces = storage.authenticatedSurfaces,
                authenticationRevision = storage.authenticationRevision,
                credentialsAuthenticated = storage.credentialsAuthenticated,
            )
        }
    }

    private suspend fun <T> owned(closedResult: T, operation: suspend () -> T): T {
        if (closed.get()) return closedResult
        val task = ownerScope.async(Dispatchers.IO) {
            if (closed.get()) closedResult else operation()
        }
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            if (closed.get()) closedResult else {
                task.cancel()
                throw cancelled
            }
        }
    }
}

interface SecurityPreferencesPort {
    fun load(): SecurityPreferences
    /** Independently readable consent when another preference field is damaged. */
    fun appUnlockCoverChoice(): Boolean? = null

    fun save(preferences: SecurityPreferences)
}

/** Shared durable facts and mutation admission; no screen owns or independently caches these. */
private class SecurityStorage(
    val verifier: PasscodeVerifierPort,
    val preferences: SecurityPreferencesPort,
    val nowMillis: () -> Long,
    val biometricAuthenticator: BiometricAuthenticatorPort,
) {
    val admission = Mutex()
    val stateLock = Any()
    var durable = runCatching { preferences.load() }.getOrNull()
    var coverChoice = if (durable != null) durable?.appUnlockCoverChoice else runCatching { preferences.appUnlockCoverChoice() }.getOrNull()
    var state: MutableStateFlow<SecurityOwnerState>? = null
    var authenticatedSurfaces: Set<ProtectedSurface> = emptySet()
    var authenticationRevision = 0L
    var credentialsAuthenticated = false
    var pendingAuthentication: PendingSecurityAuthentication? = null
    var foreground = true
    var foregroundOwner: Any? = null
    var foregroundGeneration: Long? = null
    var foregroundRevision = 0L
    var suspendedVisit: SuspendedSecurityVisit? = null
    var pausedSession: PausedSecuritySession? = null
}

private class PausedSecuritySession(val owner: SecurityOwner, val host: Any, val generation: Long, val visit: SecurityPresentationVisit?,
    val surfaces: Set<ProtectedSurface>, val credentials: Boolean, val revision: Long)

private class SuspendedSecurityVisit(
    val owner: SecurityOwner,
    val visit: SecurityPresentationVisit,
    val surfaces: Set<ProtectedSurface>,
    val credentials: Boolean,
)

/** Native-only request identity. No passcode or verifier is ever projected to JavaScript. */
class SecurityAuthenticationRequest internal constructor(
    val id: String,
    val surface: ProtectedSurface?,
    val revision: Long,
)

private class PendingSecurityAuthentication(
    val request: SecurityAuthenticationRequest,
    val owner: SecurityOwner,
    val foregroundRevision: Long,
    val result: CompletableDeferred<Boolean> = CompletableDeferred(),
    @Volatile var cancelledByUser: Boolean = false,
)
