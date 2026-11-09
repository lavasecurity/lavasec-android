package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupConfigurationPayload
import com.lavasec.app.foundation.backup.BackupDeletionIntentStore
import com.lavasec.app.foundation.backup.BackupDeletionPhase
import com.lavasec.app.foundation.backup.BackupDeletionState
import com.lavasec.app.foundation.backup.BackupDeletionStateMachine
import com.lavasec.app.foundation.backup.BackupDeletionTransition
import com.lavasec.app.foundation.backup.BackupEnablementFacts
import com.lavasec.app.foundation.backup.BackupEnablementPresentation
import com.lavasec.app.foundation.backup.BackupEnablementPolicy
import com.lavasec.app.foundation.backup.BackupEnablementState
import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupLocalState
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupPayloadMapper
import com.lavasec.app.foundation.backup.BackupPayloadMappingException
import com.lavasec.app.foundation.backup.BackupRetainedPreferences
import com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The account/generation an operation was prepared for. Backup work must be bound to both: a
 * sign-out or an A→B switch increments the account generation, so the captured pair stops matching
 * and the pending work is discarded before it can send or publish.
 */
internal data class BackupAccountBinding(
    val accountID: String?,
    val signedIn: Boolean,
)

/** Read-only account authority; the concrete adapter reads the process `AccountSessionOwner`. */
internal interface BackupAccountAuthority {
    fun binding(): BackupAccountBinding

    /** Monotonic account generation; a sign-in/sign-out/switch advances it. */
    fun generation(): Long
}

/** Resolves the current cloud client, or `null` when the backup service is not configured. */
internal fun interface BackupCloudSource {
    fun current(): BackupCloudClient?
}

/**
 * Narrow account-bound cloud seam over [SupabaseBackupClient]. Every method carries the account
 * generation so the client validates the binding around the request.
 */
internal interface BackupCloudClient {
    suspend fun upload(expectedGeneration: Long, envelope: BackupEnvelope)

    /**
     * Downloads the account's current envelope for a restore, or `null` when the account has no
     * active backup. Bound to the same account generation as every other operation: a sign-out or
     * account switch between the request and the response yields no envelope rather than another
     * account's ciphertext.
     */
    suspend fun fetchLatest(expectedGeneration: Long): BackupEnvelope?

    suspend fun fetchMetadata(expectedGeneration: Long): BackupRemoteMetadata?

    suspend fun deleteRemote(expectedGeneration: Long)
}

/** The durable local record of the backup envelope and its last confirmed upload. */
internal interface BackupLocalStore {
    /** Instances reopening the same durable store must share this identity. */
    val custodyLockIdentity: Any get() = this

    fun loadEnvelope(): BackupEnvelope?

    /** Persists [envelope]; false when durability is not acknowledged. */
    fun saveEnvelope(envelope: BackupEnvelope): Boolean

    /**
     * The account id the persisted envelope was sealed for, or `null` when no binding is recorded
     * (a legacy/restored setup). An envelope bound to another account must never be uploaded
     * through the current account's client.
     */
    fun loadEnvelopeAccountID(): String?

    /** Records the envelope's originating account; false when durability is not acknowledged. */
    fun saveEnvelopeAccountID(accountID: String?): Boolean

    /** Removes the local envelope; true when absence is acknowledged (already absent counts). */
    fun deleteEnvelope(): Boolean

    fun loadUploadedAtEpochSeconds(): Long?

    fun recordUploadedAtEpochSeconds(value: Long): Boolean

    /** Clears the upload marker; true when absence is acknowledged. */
    fun clearUploadMarker(): Boolean
}

/** Supplies the portable payload the owner would seal for a future setup path. */
internal fun interface BackupPayloadSource {
    fun currentPayload(): BackupConfigurationPayload
}

/**
 * The device-retained sealing secret that lets an already-configured backup re-seal the live
 * payload without the user's recovery phrase. The secret is a random, device-bound value stored
 * (Keystore-wrapped in production) beside the envelope; it is never the recovery phrase and never
 * leaves the device. Losing it (a restore from another device, app data cleared) simply means the
 * next setup re-retains one.
 */
internal interface BackupSealingKeyStore {
    /** The retained device sealing secret, or `null` when no backup has retained one. */
    fun load(): CharArray?

    /** Persists [key]; `false` when durability was not acknowledged. */
    fun save(key: CharArray): Boolean

    /** Removes the retained secret; true when absence is acknowledged (already absent counts). */
    fun clear(): Boolean
}

/** Non-durable default for pure tests and any composition without a device key backend. */
internal class InMemoryBackupSealingKeyStore(private var key: CharArray? = null) : BackupSealingKeyStore {
    override fun load(): CharArray? = key?.copyOf()

    override fun save(key: CharArray): Boolean {
        this.key?.fill('\u0000')
        this.key = key.copyOf()
        return true
    }

    override fun clear(): Boolean {
        key?.fill('\u0000')
        key = null
        return true
    }
}

/**
 * Durable automatic-upload preference, the Android authority for the canonical
 * `settings.set backup.automatic` key. Mirrors the canonical `isAutomaticBackupEnabled` retained
 * flag: it is a preference, not proof that a recurring upload task is scheduled. The owner refuses
 * to read it on while a deletion fence blocks writers.
 */
internal interface BackupAutomaticPreferenceStore {
    fun loadAutomaticEnabled(): Boolean

    /** Persists the preference; `false` when durability was not acknowledged. */
    fun saveAutomaticEnabled(enabled: Boolean): Boolean
}

/**
 * A stable, user-facing backup failure the owner can be in. The owner is a pure component with no
 * Android dependency, so it names the condition and the host resolves it to localized copy through
 * a [BackupFailureCopy]. This keeps the failure vocabulary a contract (the canonical RN catalog
 * keys are the same English strings) instead of embedding literal English in the owner.
 */
internal enum class BackupFailure {
    UPLOAD_FAILED,
    DELETION_NOT_SAVED,
    LOCAL_CLEANUP_INCOMPLETE,
    REMOTE_DELETE_OFFLINE,
    DELETION_PENDING,
    STATE_UNAVAILABLE,
}

/** Resolves a [BackupFailure] to the detail string for the active locale. */
internal fun interface BackupFailureCopy {
    fun copyFor(failure: BackupFailure): String
}

/**
 * Pure English fallback used by tests and any composition that has not wired localized resources.
 * Production wires the app string resources so every locale in the inventory is covered.
 */
internal object EnglishBackupFailureCopy : BackupFailureCopy {
    override fun copyFor(failure: BackupFailure): String = when (failure) {
        BackupFailure.UPLOAD_FAILED -> "Encrypted locally, but upload failed: try again."
        BackupFailure.DELETION_NOT_SAVED -> "Backup deletion could not be saved safely. Try again."
        BackupFailure.LOCAL_CLEANUP_INCOMPLETE ->
            "Online backup deleted. Local backup cleanup could not finish. Try turning off backup again."
        BackupFailure.REMOTE_DELETE_OFFLINE ->
            "Couldn't delete the backup stored for your account — you may be offline or signed " +
                "out. The backup was left in place; try again when you're back online."
        BackupFailure.DELETION_PENDING ->
            "Backup deletion is pending. Sign in to the original account and try turning off " +
                "backup again."
        BackupFailure.STATE_UNAVAILABLE ->
            "Backup state is unavailable. Unlock your device and try again."
    }
}

/** Non-durable default used by pure tests; production wires the SharedPreferences-backed store. */
internal class InMemoryBackupAutomaticPreferenceStore(initial: Boolean = false) :
    BackupAutomaticPreferenceStore {
    private var enabled = initial

    override fun loadAutomaticEnabled(): Boolean = enabled

    override fun saveAutomaticEnabled(enabled: Boolean): Boolean {
        this.enabled = enabled
        return true
    }
}

/**
 * Composes the already-ported payload mapper over a live filter-library source. The library is a
 * `()`-returned draft so a committed filter mutation is observed at seal time, not construction.
 */
internal class LibraryBackupPayloadSource(
    private val library: () -> FilterLibraryDraft?,
    private val preferences: () -> BackupRetainedPreferences = { BackupRetainedPreferences.DEFAULT },
    /** Actual progress owner capture; unavailable storage refuses instead of inventing no unlocks. */
    private val guardianLedger: () -> BackupLavaGuardAchievementLedger,
) : BackupPayloadSource {
    override fun currentPayload(): BackupConfigurationPayload = BackupPayloadMapper.toPayload(
        library() ?: throw BackupPayloadMappingException(
            "no confirmed filter library is available to back up",
        ),
        preferences(),
        guardianLedger(),
    )
}

/** The remote-evidence condition rendered by every surface; stable code, localized copy is the host's. */
internal enum class BackupRemoteStatus(val wireValue: String) {
    UNAVAILABLE("unavailable"),
    NO_BACKUP("noBackup"),
    CHECKING("checking"),
    AVAILABLE("available"),
}

/** Confirmed backup facts for one snapshot emission. Nullable enablement is never a false Off. */
internal data class BackupSnapshot(
    val enablement: BackupEnablementPresentation,
    val configured: Boolean,
    val detail: String?,
    val remoteStatus: BackupRemoteStatus,
    val remoteAvailable: Boolean,
    val remoteUploadedAtEpochSeconds: Long?,
    val statusUnavailable: Boolean,
    val needsAttention: Boolean,
    val deletionPending: Boolean,
    val backingUp: Boolean,
    val busy: Boolean,
    val automatic: Boolean,
) {
    companion object {
        /** The honest "no backup owner/evidence" projection; never a settled Off. */
        val UNAVAILABLE: BackupSnapshot = BackupSnapshot(
            enablement = BackupEnablementPresentation(
                state = BackupEnablementState.UNAVAILABLE,
                value = null,
                canEnable = false,
                canDisable = false,
                canBackUp = false,
                canRestore = false,
                canChangeAutomatic = false,
                canRetryDeletion = false,
            ),
            configured = false,
            detail = null,
            remoteStatus = BackupRemoteStatus.UNAVAILABLE,
            remoteAvailable = false,
            remoteUploadedAtEpochSeconds = null,
            statusUnavailable = true,
            needsAttention = false,
            deletionPending = false,
            backingUp = false,
            busy = false,
            automatic = false,
        )
    }
}

/** Canonical `backup.*` command vocabulary. */
internal sealed interface BackupCommand {
    data object Refresh : BackupCommand

    data object BackUpNow : BackupCommand

    data object Disable : BackupCommand

    data object Delete : BackupCommand
}

/** Outcome of one backup command; the bridge maps it to the canonical wire result. */
internal sealed interface BackupCommandResult {
    data object Completed : BackupCommandResult

    data class Rejected(val reason: String) : BackupCommandResult

    data class Cancelled(val reason: String) : BackupCommandResult
}

/**
 * Durable app-level owner of the encrypted-settings backup lifecycle.
 *
 * Ported from the canonical iOS `BackupController` (pinned at `c4658d9`), restricted to the four
 * owner-backed commands the Android parity slice exposes: `backup.refresh`, `backup.now`,
 * `backup.disable`, `backup.delete`, plus the durable automatic-upload preference behind
 * `settings.set backup.automatic`. Setup/restore and the native recovery/passkey UI are separate
 * slices; [portablePayload] is the composition seam they will call, and until that restores a
 * reviewed plan the bridge answers those flows with a typed unavailable.
 *
 * Three invariants are structural:
 *
 * 1. **Account/generation binding.** Every operation captures `(accountID, generation)` and
 *    revalidates it before the network send and before publishing any response-derived state. A
 *    sign-out or A→B switch therefore invalidates pending work rather than publishing stale facts.
 * 2. **Deletion fence first.** `disable` writes the durable [BackupDeletionIntentStore] fence
 *    before any remote delete and before any local teardown; the phase only advances on an
 *    acknowledged write, so an interruption cannot masquerade as a completed deletion.
 * 3. **Nullable enablement.** A pending deletion or unreadable fence projects a `null` value, never
 *    a definitive Off — the surface must render attention, not "no online copy".
 */
internal class BackupOwner(
    private val cloud: BackupCloudSource,
    private val accounts: BackupAccountAuthority,
    private val store: BackupLocalStore,
    private val deletionStore: BackupDeletionIntentStore,
    private val payloadSource: BackupPayloadSource? = null,
    private val automaticPreference: BackupAutomaticPreferenceStore =
        InMemoryBackupAutomaticPreferenceStore(),
    private val sealingKeys: BackupSealingKeyStore = InMemoryBackupSealingKeyStore(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val failureCopy: BackupFailureCopy = EnglishBackupFailureCopy,
    /** Dispatcher for the CPU/IO-bound re-seal; injectable so virtual-time tests are deterministic. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val backingUp = AtomicBoolean(false)
    private val maintenance = AtomicBoolean(false)

    private var deletion: BackupDeletionState =
        BackupDeletionState.from(deletionStore.read())

    private var failure: BackupFailure? = null
    private var localOnlyEnvelope: BackupEnvelope? = null
    private var remoteAccountID: String? = null
    private var remoteAvailable = false
    private var hasConfirmedRemote = false
    private var remoteUploadedAt: Long? = null
    private var remoteStatusUnavailable = false

    /** The portable payload a setup/restore slice would seal; never sent by the four commands. */
    fun portablePayload(): BackupConfigurationPayload? = payloadSource?.currentPayload()

    fun snapshot(): BackupSnapshot = synchronized(lock) {
        val binding = accounts.binding()
        val local = currentLocalStateLocked()
        val presentation = BackupEnablementPolicy.evaluate(
            BackupEnablementFacts(
                deletionFenceReadable = deletion.fenceReadable,
                deletionIntent = deletion.intent,
                hasConfirmedLocalBackup = local.isConfigured,
                isBackupMaintenanceInProgress = maintenance.get(),
                isBackingUpNow = backingUp.get(),
                isAccountSignedIn = binding.signedIn,
                currentAccountId = binding.accountID,
            ),
        )
        BackupSnapshot(
            enablement = presentation,
            configured = !deletion.hasDeletionFence && local.isConfigured,
            detail = failure?.let(failureCopy::copyFor),
            remoteStatus = remoteStatusLocked(binding, local),
            remoteAvailable = remoteAvailable,
            remoteUploadedAtEpochSeconds = remoteUploadedAt,
            statusUnavailable = remoteStatusUnavailable,
            needsAttention = local is BackupLocalState.Failed || failure != null,
            deletionPending = deletion.hasPendingDeletion,
            backingUp = backingUp.get(),
            busy = backingUp.get() || maintenance.get(),
            // The canonical preference is retained, not evidence of a scheduled upload; a deletion
            // fence keeps it off until explicit re-enablement, matching `loadAutomaticBackupPreference`.
            automatic = !deletion.hasDeletionFence && automaticPreference.loadAutomaticEnabled(),
        )
    }

    /**
     * Commits the durable automatic-upload preference. Enabling is refused while a deletion fence
     * blocks writers or a maintenance/backup operation is in flight; disabling always commits,
     * exactly like the canonical `setAutomaticBackupEnabled` guard. `false` reports a write that was
     * not acknowledged, so the caller surfaces a typed refusal instead of a silent success.
     */
    fun setAutomaticEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        if (enabled && (deletion.hasDeletionFence || maintenance.get() || backingUp.get())) return false
        automaticPreference.saveAutomaticEnabled(enabled)
    }

    /**
     * The explicit re-enable admission gate for setup. Returns true only when a completed
     * `disabled` fence or no fence at all lets setup proceed; an unfinished or unreadable fence
     * stays blocked. It deliberately does NOT clear the completed fence: a setup can still fail,
     * refuse or be cancelled after this first fallible step, and clearing here would drop the
     * user's durable Off intent before [commitReEnableForSetup] confirms the setup durably landed.
     */
    fun admitReEnableForSetup(): Boolean = synchronized(lock) {
        deletion = BackupDeletionState.from(deletionStore.read())
        when {
            !deletion.fenceReadable -> false
            deletion.intent == null -> true
            deletion.intent?.phase == BackupDeletionPhase.DISABLED -> true
            else -> false
        }
    }

    /**
     * Clears a completed `disabled` deletion fence once setup's local envelope commit is durable.
     * This is the canonical re-enable side effect, so it runs only from the successful setup path.
     * Returns true when the fence is absent (already cleared) or was just cleared; false when a
     * concurrent transition made the fence unreadable or non-`disabled`, so the caller does not
     * report a re-enable it did not achieve.
     */
    fun commitReEnableForSetup(): Boolean = synchronized(lock) {
        deletion = BackupDeletionState.from(deletionStore.read())
        when {
            !deletion.fenceReadable -> false
            deletion.intent == null -> true
            deletion.intent?.phase == BackupDeletionPhase.DISABLED ->
                deletionStore.clear().also { cleared -> if (cleared) deletion = BackupDeletionState.CLEAR }
            else -> false
        }
    }

    suspend fun execute(command: BackupCommand): BackupCommandResult = when (command) {
        BackupCommand.Refresh -> refresh()
        BackupCommand.BackUpNow -> backUpNow()
        BackupCommand.Disable -> disable()
        BackupCommand.Delete -> delete()
    }

    // --- refresh ------------------------------------------------------------------------------

    private suspend fun refresh(): BackupCommandResult {
        val captured = capture()
        synchronized(lock) { synchronizeRemoteAccountLocked(captured.binding.accountID) }
        val client = cloud.current()
        if (client == null) {
            if (captured.binding.signedIn) markRemoteUnavailable()
            return BackupCommandResult.Completed
        }
        if (!captured.binding.signedIn) return BackupCommandResult.Completed
        if (isBusy() || hasDeletionFence() || !isConfigured()) return BackupCommandResult.Completed
        return try {
            val metadata = client.fetchMetadata(captured.generation)
            if (stillBound(captured)) {
                publishRemote(
                    available = metadata != null,
                    uploadedAt = metadata?.uploadedAtEpochSeconds,
                    accountID = checkNotNull(captured.binding.accountID),
                )
            }
            BackupCommandResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (stillBound(captured)) markRemoteUnavailable()
            BackupCommandResult.Completed
        }
    }

    // --- backup.now ---------------------------------------------------------------------------

    private suspend fun backUpNow(): BackupCommandResult = store.withCustodyLock {
        backUpNowLocked()
    }

    /** Explicit first-upload retry. True means the cloud acknowledged this existing envelope. */
    suspend fun retryFirstUpload(basis: BackupSetupBasis, stillAuthorized: () -> Boolean): Boolean = store.withCustodyLock {
        val captured = capture()
        fun admitted() = stillAuthorized() && stillBound(captured) && captured.binding == basis.account &&
            captured.generation == basis.accountGeneration && captured.binding.signedIn && !hasDeletionFence()
        if (isBusy() || !admitted()) return@withCustodyLock false
        // Exact account attribution is required here; this is never a legacy/unbound upload.
        if (store.loadEnvelopeAccountID() != captured.binding.accountID) return@withCustodyLock false
        val envelope = store.loadEnvelope() ?: return@withCustodyLock false
        val client = cloud.current() ?: return@withCustodyLock false
        if (!admitted()) return@withCustodyLock false
        backingUp.set(true)
        try {
            client.upload(captured.generation, envelope)
            if (!admitted()) return@withCustodyLock false
            recordConfirmedUpload(envelope, checkNotNull(captured.binding.accountID))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (admitted()) setFailure(BackupFailure.UPLOAD_FAILED)
            false
        } finally { backingUp.set(false) }
    }

    /** Automatic-only admission; an explicit Back Up Now remains independent of this preference. */
    suspend fun backUpAutomatically(stillAdmitted: suspend () -> Boolean): BackupCommandResult = store.withCustodyLock {
        backUpNowLocked(stillAdmitted)
    }

    private suspend fun automaticStillAdmitted(admission: (suspend () -> Boolean)?, captured: Captured): Boolean {
        if (admission == null) return true
        currentCoroutineContext().ensureActive()
        if (!admission()) return false
        currentCoroutineContext().ensureActive()
        // The attempt check can suspend on the coordinator mutex. Read the durable preference
        // afterwards, so an acknowledged Off during that wait cannot be followed by an auto-send.
        return automaticPreference.loadAutomaticEnabled() && stillBound(captured) && !hasDeletionFence()
    }

    // Keep the envelope/key read, reseal and send in the same exclusion as restore publication.
    // In particular an older automatic upload cannot replace a just-adopted envelope with one
    // encrypted under the previous device key, or upload the restore's intermediate product state.
    private suspend fun backUpNowLocked(automaticAdmission: (suspend () -> Boolean)? = null): BackupCommandResult {
        val captured = capture()
        if (isBusy() || hasDeletionFence() || !automaticStillAdmitted(automaticAdmission, captured)) {
            return BackupCommandResult.Completed
        }
        val loadedEnvelope = store.loadEnvelope()
        if (loadedEnvelope == null) {
            synchronized(lock) { failure = null }
            return BackupCommandResult.Completed
        }
        // Account binding (setup records the originating account): a sign-out/A->B switch must never
        // upload A's ciphertext through B's client. A null binding is a legacy/restored envelope we
        // cannot attribute, so it is not blocked here.
        val boundAccount = store.loadEnvelopeAccountID()
        if (boundAccount != null && boundAccount != captured.binding.accountID) {
            return BackupCommandResult.Completed
        }
        backingUp.set(true)
        try {
            // Re-seal the live payload with the retained device key so filters, resolver and
            // retained-settings changes committed since setup are reflected in the uploaded
            // ciphertext. An explicit manual command keeps the legacy prior-ciphertext fallback;
            // automatic synchronization refuses when the live payload cannot be resealed. The
            // Keystore read and payload encode run off the caller's (main) dispatcher.
            val envelope = withContext(ioDispatcher) {
                if (!automaticStillAdmitted(automaticAdmission, captured)) return@withContext null
                val resealed = if (automaticAdmission == null) resealLivePayload(loadedEnvelope) else try {
                    resealLivePayload(loadedEnvelope)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (!automaticStillAdmitted(automaticAdmission, captured)) return@withContext null
                if (automaticAdmission != null && resealed == null) {
                    // Automatic synchronization must represent the current live payload. An
                    // unavailable source/key must not upload old ciphertext with a fresh receipt.
                    if (stillBound(captured)) setFailure(BackupFailure.UPLOAD_FAILED)
                    return@withContext null
                }
                if (resealed != null) {
                    // Automatic admission may be retired after this write but before the send.
                    // Never attach the prior envelope's confirmed receipt to replacement ciphertext.
                    if (automaticAdmission != null && resealed != loadedEnvelope && !store.clearUploadMarker()) {
                        if (stillBound(captured)) setFailure(BackupFailure.UPLOAD_FAILED)
                        return@withContext null
                    }
                    if (!store.saveEnvelope(resealed)) {
                        if (stillBound(captured)) setFailure(BackupFailure.UPLOAD_FAILED)
                        return@withContext null
                    }
                }
                resealed ?: loadedEnvelope
            } ?: return BackupCommandResult.Completed
            val client = cloud.current()
            if (client == null || !captured.binding.signedIn) {
                if (stillBound(captured)) clearFailure()
                return BackupCommandResult.Completed
            }
            if (!automaticStillAdmitted(automaticAdmission, captured) || !stillBound(captured)) {
                return BackupCommandResult.Completed
            }
            return try {
                client.upload(captured.generation, envelope)
                if (stillBound(captured)) {
                    recordConfirmedUpload(envelope, checkNotNull(captured.binding.accountID))
                }
                BackupCommandResult.Completed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (stillBound(captured)) {
                    setFailure(BackupFailure.UPLOAD_FAILED)
                }
                BackupCommandResult.Completed
            }
        } finally {
            backingUp.set(false)
        }
    }

    /**
     * Re-seals [envelope]'s payload ciphertext with the retained device key and the current live
     * payload, or returns `null` when there is no payload source, no retained key, or the envelope
     * has no device-key slot (a setup that predates retention). The payload plaintext is zeroized.
     */
    private fun resealLivePayload(envelope: BackupEnvelope): BackupEnvelope? {
        val source = payloadSource ?: return null
        val key = sealingKeys.load() ?: return null
        var payload: ByteArray? = null
        return try {
            val encoded = BackupPayloadJson.encode(source.currentPayload()).toByteArray(Charsets.UTF_8)
            payload = encoded
            BackupEnvelopeCrypto.reseal(envelope, encoded, key)
        } catch (_: Exception) {
            null
        } finally {
            payload?.fill(0)
            key.fill('\u0000')
        }
    }

    // --- backup.disable -----------------------------------------------------------------------

    private suspend fun disable(): BackupCommandResult = store.withCustodyLock { disableLocked() }

    private suspend fun disableLocked(): BackupCommandResult {
        if (maintenance.get()) return BackupCommandResult.Completed
        val captured = capture()
        synchronized(lock) { deletion = BackupDeletionState.from(deletionStore.read()) }
        if (!deletion.fenceReadable) {
            showDeletionFenceStatus()
            return BackupCommandResult.Completed
        }
        if (deletion.intent?.phase == BackupDeletionPhase.DISABLED) return BackupCommandResult.Completed
        if (deletion.intent == null) {
            val provenLocalOnly = cloud.current() == null && localOnlyEnvelope != null &&
                store.loadEnvelope() == localOnlyEnvelope
            when (
                val transition = BackupDeletionStateMachine.beginUserDisable(
                    existing = null,
                    currentAccountID = captured.binding.accountID,
                    isProvenLocalOnly = provenLocalOnly,
                )
            ) {
                is BackupDeletionTransition.Persist ->
                    if (!persist(transition)) {
                        setFailure(BackupFailure.DELETION_NOT_SAVED)
                        return BackupCommandResult.Completed
                    }

                is BackupDeletionTransition.Refused -> {
                    showDeletionFenceStatus()
                    return BackupCommandResult.Completed
                }

                is BackupDeletionTransition.Retryable,
                is BackupDeletionTransition.Clear,
                -> Unit
            }
        }
        var intent = synchronized(lock) { deletion.intent } ?: return BackupCommandResult.Completed
        maintenance.set(true)
        return try {
            if (intent.phase == BackupDeletionPhase.REMOTE_PENDING) {
                if (!intent.canDeleteRemote(captured.binding.accountID)) {
                    showDeletionFenceStatus()
                    return BackupCommandResult.Completed
                }
                val deleted = deleteRemoteBound(captured)
                if (!deleted || accounts.binding().accountID != intent.accountID) {
                    showDeletionFenceStatus()
                    return BackupCommandResult.Completed
                }
                val advance = BackupDeletionStateMachine.advanceAfterRemoteDeletionConfirmed(
                    intent = intent,
                    expectedAccountID = intent.accountID,
                    currentAccountID = accounts.binding().accountID,
                )
                if (advance !is BackupDeletionTransition.Persist || !persist(advance)) {
                    showDeletionFenceStatus()
                    return BackupCommandResult.Completed
                }
                intent = advance.next
            }
            finishLocalDeletion(intent)
            BackupCommandResult.Completed
        } finally {
            maintenance.set(false)
        }
    }

    private fun finishLocalDeletion(intent: com.lavasec.app.foundation.backup.BackupDeletionIntent) {
        if (!store.deleteEnvelope() || !store.clearUploadMarker()) {
            setFailure(BackupFailure.LOCAL_CLEANUP_INCOMPLETE)
            return
        }
        if (!sealingKeys.clear()) {
            setFailure(BackupFailure.LOCAL_CLEANUP_INCOMPLETE)
            return
        }
        localOnlyEnvelope = null
        val advance = BackupDeletionStateMachine.advanceAfterLocalCleanupCompleted(intent)
        if (advance !is BackupDeletionTransition.Persist || !persist(advance)) {
            setFailure(BackupFailure.LOCAL_CLEANUP_INCOMPLETE)
            return
        }
        synchronized(lock) { failure = null }
    }

    // --- backup.delete ------------------------------------------------------------------------

    private suspend fun delete(): BackupCommandResult = store.withCustodyLock { deleteLocked() }

    private suspend fun deleteLocked(): BackupCommandResult {
        if (isBusy() || hasDeletionFence()) return BackupCommandResult.Completed
        val captured = capture()
        maintenance.set(true)
        return try {
            val deleted = deleteRemoteBound(captured)
            if (deleted) {
                store.clearUploadMarker()
                localOnlyEnvelope = null
                if (stillBound(captured)) {
                    publishRemote(
                        available = false,
                        uploadedAt = null,
                        accountID = checkNotNull(captured.binding.accountID),
                    )
                }
            } else if (stillBound(captured)) {
                markRemoteUnavailable()
                setFailure(BackupFailure.REMOTE_DELETE_OFFLINE)
            }
            return BackupCommandResult.Completed
        } finally {
            maintenance.set(false)
        }
    }

    private suspend fun deleteRemoteBound(captured: Captured): Boolean {
        val client = cloud.current()
        if (client == null || !captured.binding.signedIn) return false
        return try {
            client.deleteRemote(captured.generation)
            stillBound(captured)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    // --- state transitions --------------------------------------------------------------------

    /** Applies a `Persist` through the store; true only when the write was acknowledged. */
    private fun persist(transition: BackupDeletionTransition.Persist): Boolean = synchronized(lock) {
        val next = BackupDeletionStateMachine.apply(transition, deletion, deletionStore)
        deletion = next
        next.fenceReadable && next.intent == transition.next
    }

    private fun recordConfirmedUpload(envelope: BackupEnvelope, accountID: String) = synchronized(lock) {
        val uploadedAt = nowEpochSeconds()
        if (!store.recordUploadedAtEpochSeconds(uploadedAt)) {
            failure = null
            return@synchronized
        }
        failure = null
        remoteAccountID = accountID
        hasConfirmedRemote = true
        remoteAvailable = true
        remoteUploadedAt = uploadedAt
        remoteStatusUnavailable = false
    }

    private fun publishRemote(available: Boolean, uploadedAt: Long?, accountID: String) = synchronized(lock) {
        remoteAccountID = accountID
        hasConfirmedRemote = true
        remoteAvailable = available
        remoteUploadedAt = uploadedAt
        remoteStatusUnavailable = false
    }

    private fun markRemoteUnavailable() = synchronized(lock) {
        remoteStatusUnavailable = true
    }

    private fun synchronizeRemoteAccountLocked(accountID: String?) = synchronized(lock) {
        if (remoteAccountID == accountID) return@synchronized
        remoteAccountID = accountID
        hasConfirmedRemote = false
        remoteAvailable = false
        remoteUploadedAt = null
        remoteStatusUnavailable = accountID == null
    }

    private fun showDeletionFenceStatus() = synchronized(lock) {
        failure = when {
            deletion.fenceReadable && deletion.intent?.phase == BackupDeletionPhase.DISABLED -> null
            deletion.fenceReadable && deletion.intent?.phase == BackupDeletionPhase.LOCAL_CLEANUP_PENDING ->
                BackupFailure.LOCAL_CLEANUP_INCOMPLETE
            deletion.fenceReadable -> BackupFailure.DELETION_PENDING
            else -> BackupFailure.STATE_UNAVAILABLE
        }
    }

    private fun setFailure(failure: BackupFailure) = synchronized(lock) { this.failure = failure }

    private fun clearFailure() = synchronized(lock) { failure = null }

    private fun currentLocalStateLocked(): BackupLocalState {
        failure?.let { return BackupLocalState.Failed(failureCopy.copyFor(it)) }
        val envelope = store.loadEnvelope() ?: return BackupLocalState.Off
        val uploadedAt = store.loadUploadedAtEpochSeconds()
        return if (uploadedAt == null) {
            BackupLocalState.WaitingForSignIn(envelope.ciphertextByteSize)
        } else {
            BackupLocalState.Synced(envelope.ciphertextByteSize, uploadedAt)
        }
    }

    private fun remoteStatusLocked(binding: BackupAccountBinding, local: BackupLocalState): BackupRemoteStatus {
        if (!local.isConfigured && !deletion.hasDeletionFence) return BackupRemoteStatus.NO_BACKUP
        if (remoteStatusUnavailable) return BackupRemoteStatus.UNAVAILABLE
        if (remoteAccountID != binding.accountID) {
            return if (binding.accountID == null) {
                BackupRemoteStatus.UNAVAILABLE
            } else {
                BackupRemoteStatus.CHECKING
            }
        }
        return when {
            !hasConfirmedRemote -> BackupRemoteStatus.CHECKING
            remoteAvailable -> BackupRemoteStatus.AVAILABLE
            else -> BackupRemoteStatus.NO_BACKUP
        }
    }

    // --- binding ------------------------------------------------------------------------------

    private data class Captured(val binding: BackupAccountBinding, val generation: Long)

    private fun capture(): Captured = Captured(accounts.binding(), accounts.generation())

    private fun stillBound(captured: Captured): Boolean =
        accounts.binding() == captured.binding && accounts.generation() == captured.generation

    private fun isBusy(): Boolean = backingUp.get() || maintenance.get()

    private fun hasDeletionFence(): Boolean = synchronized(lock) { deletion.hasDeletionFence }

    private fun isConfigured(): Boolean = synchronized(lock) {
        !deletion.hasDeletionFence && store.loadEnvelope() != null
    }
}
