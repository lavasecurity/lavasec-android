package com.lavasec.app.foundation.backup

/**
 * The upload outcome of one committed setup. Mirrors the canonical iOS
 * `BackupSetupUploadProgress.State` (pinned at `c4658d9`): an upload result belongs to one locally
 * committed setup, never a cached sync state, and [UNAVAILABLE] is the explicit "the attempt no
 * longer applies" outcome — never a false success.
 */
enum class BackupSetupUploadStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED,
    UNAVAILABLE,
}

/** Progress for one setup-upload attempt. A [FAILED] status always carries a condition message. */
data class BackupSetupUploadProgress(
    val attemptID: String,
    val status: BackupSetupUploadStatus,
    val failureMessage: String? = null,
) {
    init {
        require((status == BackupSetupUploadStatus.FAILED) == (failureMessage != null)) {
            "a FAILED setup upload must carry a message and no other status may"
        }
    }
}

/**
 * A locally committed setup attempt, bound to the account and lifecycle generation that created it.
 * No key material or phrase is held here — only the identity needed to reject a stale retry.
 */
data class BackupSetupAttempt(
    val id: String,
    /** The account the setup was committed for, or `null` when it was committed signed-out. */
    val accountID: String?,
    /** The lifecycle generation observed at commit time. */
    val generation: Long,
    val estimatedByteSize: Int,
) {
    /**
     * Whether this attempt is still the current one: same account, same lifecycle generation,
     * backup still enabled, no deletion fence blocking writers, and the local envelope unchanged.
     * An account switch during setup therefore invalidates the pending upload.
     */
    fun isCurrent(
        currentAccountID: String?,
        generation: Long,
        deletionFenceBlocks: Boolean,
        isBackupEnabled: Boolean,
        envelopeStillCurrent: Boolean,
    ): Boolean = accountID != null &&
        accountID == currentAccountID &&
        this.generation == generation &&
        isBackupEnabled &&
        !deletionFenceBlocks &&
        envelopeStillCurrent
}

/** What a setup-upload retry should do next. Never a Boolean that conflates "done" with "stale". */
sealed interface BackupSetupUploadDecision {
    /** The caller may upload [attempt]; its own bookkeeping sets UPLOADING first. */
    data class Upload(val attempt: BackupSetupAttempt) : BackupSetupUploadDecision

    /** The attempt no longer applies (account/generation/envelope changed, or fenced). */
    data object Unavailable : BackupSetupUploadDecision

    /** The attempt already uploaded successfully; retrying would be redundant. */
    data object AlreadySettled : BackupSetupUploadDecision
}

/**
 * Pure setup-upload retry admission, ported from the canonical `BackupController.retrySetupUpload`
 * guards. It never generates replacement keys or a phrase: a stale attempt yields
 * [BackupSetupUploadDecision.Unavailable] and the caller surfaces that explicitly.
 */
object BackupSetupUploadPolicy {

    fun nextStep(
        attempt: BackupSetupAttempt,
        progress: BackupSetupUploadProgress?,
        currentAccountID: String?,
        generation: Long,
        deletionFenceBlocks: Boolean,
        isBackupEnabled: Boolean,
        envelopeStillCurrent: Boolean,
    ): BackupSetupUploadDecision {
        if (progress != null && progress.attemptID == attempt.id &&
            progress.status == BackupSetupUploadStatus.UPLOADED
        ) {
            return BackupSetupUploadDecision.AlreadySettled
        }
        val current = attempt.isCurrent(
            currentAccountID = currentAccountID,
            generation = generation,
            deletionFenceBlocks = deletionFenceBlocks,
            isBackupEnabled = isBackupEnabled,
            envelopeStillCurrent = envelopeStillCurrent,
        )
        return if (current) {
            BackupSetupUploadDecision.Upload(attempt)
        } else {
            BackupSetupUploadDecision.Unavailable
        }
    }
}
