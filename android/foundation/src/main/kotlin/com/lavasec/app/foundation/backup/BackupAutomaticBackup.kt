package com.lavasec.app.foundation.backup

/**
 * The account + configuration identity an automatic upload is bound to. A change to either side
 * (an A→B account switch, or any configuration-generation write) makes a pending attempt stale, so
 * a queued upload can never send state that belongs to a different account or configuration.
 */
data class BackupAutomaticBackupKey(
    val accountID: String?,
    val configurationGeneration: Long,
)

/** One pending automatic upload, identified by a monotonically advancing token. */
data class BackupAutomaticBackupAttempt(
    val token: Long,
    val key: BackupAutomaticBackupKey,
)

/** Pure scheduling state; [pending] is the single coalesced attempt, or `null` when suppressed. */
data class BackupAutomaticBackupState(
    val nextToken: Long = 0L,
    val pending: BackupAutomaticBackupAttempt? = null,
)

/** What a configuration change did to the schedule. */
sealed interface BackupAutomaticBackupDecision {
    /** A fresh (or coalesced) attempt is now pending; the caller arms the debounce for it. */
    data class Scheduled(val attempt: BackupAutomaticBackupAttempt) : BackupAutomaticBackupDecision

    /** Scheduling is off for this change (disabled, fenced, busy or recovery scaffolding). */
    data object Suppressed : BackupAutomaticBackupDecision
}

/** What an armed attempt should do when the debounce elapses. */
sealed interface BackupAutomaticBackupRun {
    /** The caller may upload for this attempt. */
    data class Run(val attempt: BackupAutomaticBackupAttempt) : BackupAutomaticBackupRun

    /** The attempt was coalesced away by a newer change, or its identity changed. */
    data object Superseded : BackupAutomaticBackupRun

    /** The attempt is dropped because automatic backup became suppressed. */
    data object Suppressed : BackupAutomaticBackupRun
}

/**
 * Pure automatic-upload trigger/coalesce/suppress policy, ported from the canonical iOS
 * `BackupController.scheduleAutomaticBackupAfterConfigurationChange` /
 * `runScheduledAutomaticBackup` (pinned at `c4658d9`).
 *
 * Coalescing is structural: every configuration change mints a new token and replaces the single
 * pending attempt, so many rapid edits produce one upload — the newest. An armed attempt only runs
 * when it is still the pending token, its account/configuration key still matches, and no fence,
 * maintenance lease, disablement or launch-reseed scaffolding suppresses it.
 *
 * Suppression mirrors the iOS guards exactly: a launch reseed is recovery scaffolding, not user
 * intent (INV-PERSIST-1 blast-radius guard), and a deletion fence or maintenance lease must never
 * be overlapped.
 */
object BackupAutomaticBackupPolicy {

    /**
     * Applies a configuration change to the schedule. A suppressed change clears any pending
     * attempt so an older token cannot run afterwards.
     */
    fun onConfigurationChange(
        state: BackupAutomaticBackupState,
        key: BackupAutomaticBackupKey,
        automaticEnabled: Boolean,
        hasLocalEnvelope: Boolean,
        deletionFenceBlocks: Boolean,
        maintenanceInProgress: Boolean,
        originatesFromLaunchReseed: Boolean,
    ): Pair<BackupAutomaticBackupState, BackupAutomaticBackupDecision> {
        val suppressed = !automaticEnabled ||
            !hasLocalEnvelope ||
            deletionFenceBlocks ||
            maintenanceInProgress ||
            originatesFromLaunchReseed
        if (suppressed) {
            return state.copy(pending = null) to BackupAutomaticBackupDecision.Suppressed
        }
        val attempt = BackupAutomaticBackupAttempt(token = state.nextToken + 1, key = key)
        return BackupAutomaticBackupState(nextToken = attempt.token, pending = attempt) to
            BackupAutomaticBackupDecision.Scheduled(attempt)
    }

    /**
     * Admits (or rejects) an armed attempt when its debounce elapses. Only the current pending
     * token for the still-current account/configuration may run.
     */
    fun admitRun(
        state: BackupAutomaticBackupState,
        token: Long,
        currentKey: BackupAutomaticBackupKey,
        automaticEnabled: Boolean,
        deletionFenceBlocks: Boolean,
        maintenanceInProgress: Boolean,
    ): BackupAutomaticBackupRun {
        val pending = state.pending
        if (pending == null || pending.token != token) return BackupAutomaticBackupRun.Superseded
        if (!automaticEnabled || deletionFenceBlocks || maintenanceInProgress) {
            return BackupAutomaticBackupRun.Suppressed
        }
        if (pending.key != currentKey) return BackupAutomaticBackupRun.Superseded
        return BackupAutomaticBackupRun.Run(pending)
    }
}
