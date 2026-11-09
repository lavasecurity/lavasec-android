package com.lavasec.app.foundation.backup

/**
 * Presentation state for the LOCAL encrypted-settings backup on this device.
 *
 * A pure value type: it derives everything from the persisted facts (whether a local envelope
 * exists, its estimated size, and the last confirmed upload time). It carries no UI copy — the
 * canonical iOS `EncryptedBackupState` owns the localized strings, which are a presentation
 * concern the host supplies from this shape.
 *
 * Mirrors the canonical iOS `EncryptedBackupState` (pinned at `c4658d9`) case-for-case. `isConfigured`
 * is the "an existing backup configuration is present" fact consumed by the enablement model, and
 * `FAILED` is configured-but-needs-attention, NOT off.
 */
sealed interface BackupLocalState {

    /** No encrypted backup envelope exists on this device. */
    data object Off : BackupLocalState

    /** A local envelope exists but has no recorded upload timestamp. */
    data class WaitingForSignIn(val estimatedByteSize: Int) : BackupLocalState

    /** The local envelope has a recorded upload timestamp. */
    data class Synced(val estimatedByteSize: Int, val uploadedAtEpochSeconds: Long) : BackupLocalState

    /** Setup remains configured but its latest operation failed with this condition. */
    data class Failed(val message: String) : BackupLocalState

    /**
     * Whether this state represents an existing backup configuration. Only [Off] is not configured:
     * a [Failed] state is a configured backup that needs attention, never a settled Off.
     */
    val isConfigured: Boolean
        get() = when (this) {
            Off -> false
            is WaitingForSignIn, is Synced, is Failed -> true
        }
}
