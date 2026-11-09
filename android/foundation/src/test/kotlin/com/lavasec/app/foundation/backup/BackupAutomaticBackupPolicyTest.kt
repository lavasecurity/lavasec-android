package com.lavasec.app.foundation.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAutomaticBackupPolicyTest {

    private fun key(generation: Long, accountID: String? = "account-A") =
        BackupAutomaticBackupKey(accountID = accountID, configurationGeneration = generation)

    private fun schedule(
        state: BackupAutomaticBackupState,
        key: BackupAutomaticBackupKey,
        automaticEnabled: Boolean = true,
        hasLocalEnvelope: Boolean = true,
        deletionFenceBlocks: Boolean = false,
        maintenanceInProgress: Boolean = false,
        originatesFromLaunchReseed: Boolean = false,
    ): Pair<BackupAutomaticBackupState, BackupAutomaticBackupDecision> =
        BackupAutomaticBackupPolicy.onConfigurationChange(
            state = state,
            key = key,
            automaticEnabled = automaticEnabled,
            hasLocalEnvelope = hasLocalEnvelope,
            deletionFenceBlocks = deletionFenceBlocks,
            maintenanceInProgress = maintenanceInProgress,
            originatesFromLaunchReseed = originatesFromLaunchReseed,
        )

    @Test
    fun `rapid configuration changes coalesce into one upload, the newest`() {
        val (first, decisionOne) = schedule(BackupAutomaticBackupState(), key(1))
        assertEquals(1L, (decisionOne as BackupAutomaticBackupDecision.Scheduled).attempt.token)

        val (second, decisionTwo) = schedule(first, key(2))
        val latest = (decisionTwo as BackupAutomaticBackupDecision.Scheduled).attempt
        assertEquals(2L, latest.token)

        // Only the newest token is runnable; the coalesced-away one is superseded.
        assertEquals(
            BackupAutomaticBackupRun.Superseded,
            BackupAutomaticBackupPolicy.admitRun(
                state = second,
                token = 1L,
                currentKey = key(2),
                automaticEnabled = true,
                deletionFenceBlocks = false,
                maintenanceInProgress = false,
            ),
        )
        assertEquals(
            BackupAutomaticBackupRun.Run(latest),
            BackupAutomaticBackupPolicy.admitRun(
                state = second,
                token = 2L,
                currentKey = key(2),
                automaticEnabled = true,
                deletionFenceBlocks = false,
                maintenanceInProgress = false,
            ),
        )
    }

    @Test
    fun `disabled, fenced, busy or reseeded changes are suppressed and clear any pending attempt`() {
        val (armed, _) = schedule(BackupAutomaticBackupState(), key(1))
        assertEquals(1L, armed.pending?.token)

        for (keep in listOf(
            schedule(armed, key(2), automaticEnabled = false),
            schedule(armed, key(2), hasLocalEnvelope = false),
            schedule(armed, key(2), deletionFenceBlocks = true),
            schedule(armed, key(2), maintenanceInProgress = true),
            schedule(armed, key(2), originatesFromLaunchReseed = true),
        )) {
            assertEquals(BackupAutomaticBackupDecision.Suppressed, keep.second)
            assertNull("a suppressed change must drop the pending attempt", keep.first.pending)
        }
    }

    @Test
    fun `a run whose account or configuration changed since arming is superseded`() {
        val (state, decision) = schedule(BackupAutomaticBackupState(), key(3))
        val token = (decision as BackupAutomaticBackupDecision.Scheduled).attempt.token

        assertEquals(
            BackupAutomaticBackupRun.Superseded,
            BackupAutomaticBackupPolicy.admitRun(
                state = state,
                token = token,
                currentKey = key(3, accountID = "account-B"),
                automaticEnabled = true,
                deletionFenceBlocks = false,
                maintenanceInProgress = false,
            ),
        )
        assertEquals(
            BackupAutomaticBackupRun.Superseded,
            BackupAutomaticBackupPolicy.admitRun(
                state = state,
                token = token,
                currentKey = key(4),
                automaticEnabled = true,
                deletionFenceBlocks = false,
                maintenanceInProgress = false,
            ),
        )
    }

    @Test
    fun `a run is suppressed when automatic backup turns off or a fence appears after arming`() {
        val (state, decision) = schedule(BackupAutomaticBackupState(), key(5))
        val token = (decision as BackupAutomaticBackupDecision.Scheduled).attempt.token

        assertEquals(
            BackupAutomaticBackupRun.Suppressed,
            BackupAutomaticBackupPolicy.admitRun(state, token, key(5), automaticEnabled = false, deletionFenceBlocks = false, maintenanceInProgress = false),
        )
        assertEquals(
            BackupAutomaticBackupRun.Suppressed,
            BackupAutomaticBackupPolicy.admitRun(state, token, key(5), automaticEnabled = true, deletionFenceBlocks = true, maintenanceInProgress = false),
        )
        assertTrue(state.pending != null)
    }

    @Test
    fun `an unknown token is superseded rather than run`() {
        val (state, _) = schedule(BackupAutomaticBackupState(), key(1))
        assertEquals(
            BackupAutomaticBackupRun.Superseded,
            BackupAutomaticBackupPolicy.admitRun(state, token = 99L, currentKey = key(1), automaticEnabled = true, deletionFenceBlocks = false, maintenanceInProgress = false),
        )
        assertEquals(
            BackupAutomaticBackupRun.Superseded,
            BackupAutomaticBackupPolicy.admitRun(BackupAutomaticBackupState(), token = 1L, currentKey = key(1), automaticEnabled = true, deletionFenceBlocks = false, maintenanceInProgress = false),
        )
    }
}
