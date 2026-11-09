package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupAutomaticBackupAttempt
import com.lavasec.app.foundation.backup.BackupAutomaticBackupDecision
import com.lavasec.app.foundation.backup.BackupAutomaticBackupKey
import com.lavasec.app.foundation.backup.BackupAutomaticBackupPolicy
import com.lavasec.app.foundation.backup.BackupAutomaticBackupRun
import com.lavasec.app.foundation.backup.BackupAutomaticBackupState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives the already-ported [BackupAutomaticBackupPolicy] against the live [BackupOwner].
 *
 * A configuration change (filter/rules/resolver) calls [onConfigurationChanged]; the policy decides
 * whether a coalesced upload attempt is armed for the current account + configuration generation.
 * The debounce collapses a burst of edits into one upload, and the admission step revalidates the
 * account/generation and the automatic preference immediately before the send, so a queued upload
 * can never carry another account's or an obsolete configuration's state.
 *
 * [BackupOwner.backUpAutomatically] repeats admission under custody before resealing and sending
 * the live payload through the account-bound cloud client. It only ever arms when a
 * local envelope exists, so an unconfigured device schedules nothing. Nothing here generates keys
 * or a phrase; it is pure scheduling over the existing authority.
 */
internal class BackupAutomaticUploadCoordinator(
    private val owner: BackupOwner,
    private val accounts: BackupAccountAuthority,
    private val store: BackupLocalStore,
    private val automaticPreference: BackupAutomaticPreferenceStore,
    private val deletionFenceBlocks: () -> Boolean,
    private val scope: CoroutineScope,
    /** The live configuration write generation; a change here supersedes a pending attempt. */
    private val currentConfigurationGeneration: () -> Long,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
    private val mutex = Mutex()

    // Guarded by [mutex]; the pending job is the single coalesced debounce.
    private var state = BackupAutomaticBackupState()
    private var pendingJob: Job? = null

    /** Records a configuration change and, when the policy allows, arms the debounced upload. */
    fun onConfigurationChanged() {
        scope.launch {
            val attempt = mutex.withLock {
                // IO callbacks can arrive in reverse order. Sample the live authority at this
                // serialized scheduling edge, never carry an older callback's pre-launch key.
                val binding = accounts.binding()
                val key = BackupAutomaticBackupKey(binding.accountID, currentConfigurationGeneration())
                val (next, decision) = BackupAutomaticBackupPolicy.onConfigurationChange(
                    state = state,
                    key = key,
                    automaticEnabled = automaticPreference.loadAutomaticEnabled(),
                    hasLocalEnvelope = store.loadEnvelope() != null,
                    deletionFenceBlocks = deletionFenceBlocks(),
                    maintenanceInProgress = false,
                    originatesFromLaunchReseed = false,
                )
                state = next
                (decision as? BackupAutomaticBackupDecision.Scheduled)?.attempt
            } ?: return@launch
            arm(attempt)
        }
    }

    private suspend fun arm(attempt: BackupAutomaticBackupAttempt) {
        mutex.withLock {
            // A newer configuration change can have already armed its own job between this call's
            // state update and here. Only cancel/replace when THIS attempt is still the pending one,
            // or an older arm would cancel the newer job and drop that configuration's upload.
            if (state.pending?.token != attempt.token) return
            pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(debounceMillis)
                runScheduled(attempt.token)
            }
        }
    }

    private suspend fun runScheduled(token: Long) {
        if (admitScheduled(token) is BackupAutomaticBackupRun.Run) {
            // Acquiring custody and resealing can suspend after this admission. The owner repeats
            // this exact attempt/key/fence check under custody and immediately before the send.
            owner.backUpAutomatically { admitScheduled(token) is BackupAutomaticBackupRun.Run }
        }
    }

    private suspend fun admitScheduled(token: Long): BackupAutomaticBackupRun = mutex.withLock {
        val binding = accounts.binding()
        val key = BackupAutomaticBackupKey(binding.accountID, currentConfigurationGeneration())
        BackupAutomaticBackupPolicy.admitRun(
            state = state,
            token = token,
            currentKey = key,
            automaticEnabled = automaticPreference.loadAutomaticEnabled(),
            deletionFenceBlocks = deletionFenceBlocks(),
            maintenanceInProgress = false,
        )
    }

    internal companion object {
        /** A short trailing debounce: many edits within this window coalesce into one upload. */
        const val DEFAULT_DEBOUNCE_MILLIS = 5_000L
    }
}
