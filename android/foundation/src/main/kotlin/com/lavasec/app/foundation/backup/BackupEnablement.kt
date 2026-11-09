package com.lavasec.app.foundation.backup

/**
 * Confirmed enablement state for the encrypted-backup control.
 *
 * Mirrors the canonical iOS `BackupEnablementPresentation.State` (pinned at `c4658d9`) so every
 * client names the same condition. [DELETION_PENDING] and [UNAVAILABLE] are the two states whose
 * enablement is UNKNOWN ([BackupEnablementPresentation.value] is `null`): a pending remote deletion
 * or unreadable fence must never render as a definitive Off, because rendering Off is what the user
 * reads as "there is no online copy" — and a missing local key is not proof that no online copy
 * exists.
 */
enum class BackupEnablementState(val wireValue: String) {
    /** Confirmed no local backup and no pending deletion. */
    OFF("off"),

    /** Confirmed configured, with no setup/restore or backup operation in flight. */
    ON("on"),

    /** Confirmed configured while the setup/restore sheet is presented. */
    SETUP("setup"),

    /** Confirmed configured while a backup/maintenance/upload operation is in flight. */
    BUSY("busy"),

    /** A deletion is durably pending; enablement is unknown, never Off. */
    DELETION_PENDING("deletionPending"),

    /** The durable fence could not be read, or no local-backup evidence exists yet. */
    UNAVAILABLE("unavailable"),
    ;

    val isNullableEnablement: Boolean
        get() = this == DELETION_PENDING || this == UNAVAILABLE
}

/**
 * Confirmed enablement and action availability for every backup presentation.
 *
 * `value == null` means deletion is unresolved or evidence is unavailable, NEVER Off. Automatic
 * upload scheduling is intentionally absent from this contract: whether recurring uploads should be
 * scheduled is a separate preference, not part of the enablement presentation.
 *
 * Mirrors the canonical iOS `BackupEnablementPresentation`.
 */
data class BackupEnablementPresentation(
    val state: BackupEnablementState,
    val value: Boolean?,
    val canEnable: Boolean,
    val canDisable: Boolean,
    val canBackUp: Boolean,
    val canRestore: Boolean,
    val canChangeAutomatic: Boolean,
    val canRetryDeletion: Boolean,
) {
    init {
        require(state.isNullableEnablement == (value == null)) {
            "backup enablement state $state must have ${if (state.isNullableEnablement) "null" else "non-null"} value"
        }
    }

    /** True only for a settled Off: never true for a pending deletion or unavailable evidence. */
    val isDefinitivelyOff: Boolean get() = state == BackupEnablementState.OFF && value == false

    /** True while backup is confirmed configured (setup/busy are still "on", not off). */
    val isConfirmedOn: Boolean
        get() = (state == BackupEnablementState.ON ||
            state == BackupEnablementState.SETUP ||
            state == BackupEnablementState.BUSY) && value == true
}

/**
 * The already-observed facts the enablement decision reads. Every fact is a caller-supplied value;
 * [BackupEnablementPolicy] never touches storage, the network, or Android APIs.
 *
 * [hasConfirmedLocalBackup] is nullable on purpose: `null` means no local-backup evidence has been
 * observed yet (the canonical `hasConfirmedLocalBackup` starts `nil`), which is NOT the same as a
 * confirmed absent backup.
 */
data class BackupEnablementFacts(
    /** False when the durable deletion fence could not be read; the decision then fails closed. */
    val deletionFenceReadable: Boolean,
    /** The persisted deletion intent, or `null` when none was loaded. */
    val deletionIntent: BackupDeletionIntent? = null,
    /** `null` until local evidence is observed; otherwise whether a backup is configured. */
    val hasConfirmedLocalBackup: Boolean? = null,
    val isBackupMaintenanceInProgress: Boolean = false,
    val isBackingUpNow: Boolean = false,
    val isUploadingEncryptedBackup: Boolean = false,
    /** Whether an upload task is currently registered (canonical `uploadTask != nil`). */
    val hasInFlightUpload: Boolean = false,
    val isSetupOrRestorePresented: Boolean = false,
    val isAccountSignedIn: Boolean = false,
    val currentAccountId: String? = null,
) {
    companion object {
        /**
         * Builds facts from the observed local state. A `null` [localState] leaves
         * [hasConfirmedLocalBackup] unknown, so the decision reports an explicit unavailable
         * rather than a false Off.
         */
        fun fromLocalState(
            localState: BackupLocalState?,
            deletionFenceReadable: Boolean,
            deletionIntent: BackupDeletionIntent? = null,
            isBackupMaintenanceInProgress: Boolean = false,
            isBackingUpNow: Boolean = false,
            isUploadingEncryptedBackup: Boolean = false,
            hasInFlightUpload: Boolean = false,
            isSetupOrRestorePresented: Boolean = false,
            isAccountSignedIn: Boolean = false,
            currentAccountId: String? = null,
        ): BackupEnablementFacts = BackupEnablementFacts(
            deletionFenceReadable = deletionFenceReadable,
            deletionIntent = deletionIntent,
            hasConfirmedLocalBackup = localState?.isConfigured,
            isBackupMaintenanceInProgress = isBackupMaintenanceInProgress,
            isBackingUpNow = isBackingUpNow,
            isUploadingEncryptedBackup = isUploadingEncryptedBackup,
            hasInFlightUpload = hasInFlightUpload,
            isSetupOrRestorePresented = isSetupOrRestorePresented,
            isAccountSignedIn = isAccountSignedIn,
            currentAccountId = currentAccountId,
        )
    }
}

/**
 * Pure enablement decision, ported from the canonical iOS
 * `BackupController.enablementPresentation(isSetupOrRestorePresented:)`.
 *
 * Ordering is load-bearing and follows canon exactly:
 *
 * 1. An unreadable deletion fence is [BackupEnablementState.UNAVAILABLE] with a `null` value.
 * 2. A pending (not `disabled`) deletion intent is [BackupEnablementState.DELETION_PENDING] with a
 *    `null` value — even a `disabled` intent forces the value to `false`, so a completed tombstone
 *    keeps Off authoritative until explicit re-enablement clears it.
 * 3. Unknown local evidence is [BackupEnablementState.UNAVAILABLE] with a `null` value.
 * 4. Otherwise the confirmed value selects on/off, with setup/restore presentation and busy
 *    operations overriding the label but never the value.
 *
 * Actionability additionally requires an ordinary (non-null) state, a signed-in account, and no
 * interaction already in flight.
 */
object BackupEnablementPolicy {

    fun evaluate(facts: BackupEnablementFacts): BackupEnablementPresentation {
        val operationBusy = facts.isBackingUpNow ||
            facts.isBackupMaintenanceInProgress ||
            facts.isUploadingEncryptedBackup ||
            facts.hasInFlightUpload
        val interactionBusy = operationBusy || facts.isSetupOrRestorePresented

        val state: BackupEnablementState
        val value: Boolean?
        if (!facts.deletionFenceReadable) {
            state = BackupEnablementState.UNAVAILABLE
            value = null
        } else if (facts.deletionIntent != null && facts.deletionIntent.blocksBackupWork) {
            state = BackupEnablementState.DELETION_PENDING
            value = null
        } else if (facts.hasConfirmedLocalBackup != null) {
            // A completed `disabled` tombstone overrides a stale configured projection, exactly as
            // the canonical ternary does: `deletionIntent?.phase == .disabled ? false : hasConfirmed`.
            val confirmed = if (facts.deletionIntent?.phase == BackupDeletionPhase.DISABLED) {
                false
            } else {
                facts.hasConfirmedLocalBackup
            }
            value = confirmed
            state = when {
                facts.isSetupOrRestorePresented -> BackupEnablementState.SETUP
                operationBusy -> BackupEnablementState.BUSY
                confirmed -> BackupEnablementState.ON
                else -> BackupEnablementState.OFF
            }
        } else {
            state = BackupEnablementState.UNAVAILABLE
            value = null
        }

        val ordinary = value != null &&
            state != BackupEnablementState.UNAVAILABLE &&
            state != BackupEnablementState.DELETION_PENDING
        val canAct = ordinary && facts.isAccountSignedIn && !interactionBusy

        val canRetryDeletion = if (facts.deletionFenceReadable && facts.deletionIntent != null && !interactionBusy) {
            facts.deletionIntent.phase == BackupDeletionPhase.LOCAL_CLEANUP_PENDING ||
                facts.deletionIntent.canDeleteRemote(facts.currentAccountId)
        } else {
            false
        }

        return BackupEnablementPresentation(
            state = state,
            value = value,
            canEnable = canAct && value == false,
            canDisable = canAct && value == true,
            canBackUp = canAct && value == true,
            canRestore = canAct,
            canChangeAutomatic = canAct && value == true,
            canRetryDeletion = canRetryDeletion,
        )
    }
}
