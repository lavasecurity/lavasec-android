package com.lavasec.app.foundation.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the durable deletion state machine: remote acknowledgement strictly precedes local
 * teardown, an interrupted local write never masquerades as progress, a pending record survives a
 * serialized reopen, and an account switch invalidates the operation.
 */
class BackupDeletionLifecycleTest {

    @Test
    fun `a pending remote deletion survives a serialized reopen`() {
        val store = SerializedFenceStore()
        val begun = BackupDeletionStateMachine.beginUserDisable(
            existing = null,
            currentAccountID = "account-A",
            isProvenLocalOnly = false,
        )
        assertTrue(begun is BackupDeletionTransition.Persist)
        val persisted = (begun as BackupDeletionTransition.Persist).next
        assertEquals(BackupDeletionPhase.REMOTE_PENDING, persisted.phase)

        val applied = BackupDeletionStateMachine.apply(begun, BackupDeletionState.CLEAR, store)
        assertTrue(applied.hasPendingDeletion)

        // A new store over the same serialized bytes stands in for a process relaunch.
        val reopened = SerializedFenceStore(backing = store.backing)
        val read = reopened.read()
        assertTrue(read is BackupDeletionRead.Present)
        val reloaded = BackupDeletionState.from(read)
        assertTrue(reloaded.hasPendingDeletion)
        assertEquals(persisted, reloaded.intent)

        val presentation = BackupEnablementPolicy.evaluate(
            BackupEnablementFacts(
                deletionFenceReadable = reloaded.fenceReadable,
                deletionIntent = reloaded.intent,
                hasConfirmedLocalBackup = true,
                currentAccountId = "account-A",
            ),
        )
        assertEquals(BackupEnablementState.DELETION_PENDING, presentation.state)
        assertNull(presentation.value)
    }

    @Test
    fun `remote acknowledgement strictly precedes local teardown`() {
        val pending = BackupDeletionIntent.create("account-A", BackupDeletionPhase.REMOTE_PENDING)

        val prematureCleanup = BackupDeletionStateMachine.advanceAfterLocalCleanupCompleted(pending)
        assertEquals(
            BackupDeletionRefusal.NOT_LOCAL_CLEANUP_PENDING,
            (prematureCleanup as BackupDeletionTransition.Refused).refusal,
        )

        val unconfirmed = BackupDeletionStateMachine.advanceAfterRemoteDeletionUnconfirmed(pending)
        assertTrue(unconfirmed is BackupDeletionTransition.Retryable)
        assertEquals(BackupDeletionPhase.REMOTE_PENDING, (unconfirmed as BackupDeletionTransition.Retryable).intent?.phase)

        val confirmed = BackupDeletionStateMachine.advanceAfterRemoteDeletionConfirmed(
            pending,
            expectedAccountID = "account-A",
            currentAccountID = "account-A",
        )
        val cleanup = (confirmed as BackupDeletionTransition.Persist).next
        assertEquals(BackupDeletionPhase.LOCAL_CLEANUP_PENDING, cleanup.phase)

        val doubleConfirm = BackupDeletionStateMachine.advanceAfterRemoteDeletionConfirmed(
            cleanup,
            expectedAccountID = "account-A",
            currentAccountID = "account-A",
        )
        assertEquals(
            BackupDeletionRefusal.NOT_REMOTE_PENDING,
            (doubleConfirm as BackupDeletionTransition.Refused).refusal,
        )

        val cleanupFailed = BackupDeletionStateMachine.advanceAfterLocalCleanupFailed(cleanup)
        assertEquals(
            BackupDeletionPhase.LOCAL_CLEANUP_PENDING,
            (cleanupFailed as BackupDeletionTransition.Retryable).intent?.phase,
        )

        val completed = BackupDeletionStateMachine.advanceAfterLocalCleanupCompleted(cleanup)
        assertEquals(
            BackupDeletionPhase.DISABLED,
            (completed as BackupDeletionTransition.Persist).next.phase,
        )
    }

    @Test
    fun `an interrupted local write leaves the previous state untouched`() {
        val store = SerializedFenceStore(writable = false)
        val start = BackupDeletionState.CLEAR
        val begun = BackupDeletionStateMachine.beginUserDisable(null, "account-A", isProvenLocalOnly = false)

        val afterFailedWrite = BackupDeletionStateMachine.apply(begun, start, store)
        assertEquals(start, afterFailedWrite)
        assertFalse(afterFailedWrite.hasPendingDeletion)
        assertNull(store.backing)

        val presentation = BackupEnablementPolicy.evaluate(
            BackupEnablementFacts(
                deletionFenceReadable = afterFailedWrite.fenceReadable,
                deletionIntent = afterFailedWrite.intent,
                hasConfirmedLocalBackup = true,
            ),
        )
        assertEquals(BackupEnablementState.ON, presentation.state)

        // A retry of the same acknowledged transition now persists.
        store.writable = true
        val afterRetry = BackupDeletionStateMachine.apply(begun, start, store)
        assertTrue(afterRetry.hasPendingDeletion)
        assertEquals(BackupDeletionPhase.REMOTE_PENDING, afterRetry.intent?.phase)
    }

    @Test
    fun `an unreadable record fails closed instead of reporting an absent fence`() {
        val store = SerializedFenceStore(backing = "{ this is not a deletion intent")
        val state = BackupDeletionState.from(store.read())

        assertFalse(state.fenceReadable)
        assertTrue(state.hasDeletionFence)

        val presentation = BackupEnablementPolicy.evaluate(
            BackupEnablementFacts(
                deletionFenceReadable = state.fenceReadable,
                deletionIntent = state.intent,
                hasConfirmedLocalBackup = true,
            ),
        )
        assertEquals(BackupEnablementState.UNAVAILABLE, presentation.state)
        assertNull(presentation.value)
    }

    @Test
    fun `a proven device-local backup starts in local cleanup without a remote phase`() {
        val begun = BackupDeletionStateMachine.beginUserDisable(
            existing = null,
            currentAccountID = null,
            isProvenLocalOnly = true,
        )
        val intent = (begun as BackupDeletionTransition.Persist).next

        assertEquals(BackupDeletionIntent.DEVICE_LOCAL_ONLY_ACCOUNT_ID, intent.accountID)
        assertEquals(BackupDeletionPhase.LOCAL_CLEANUP_PENDING, intent.phase)
    }

    @Test
    fun `no account and not device-local refuses to fabricate a fence`() {
        val refused = BackupDeletionStateMachine.beginUserDisable(null, null, isProvenLocalOnly = false)
        assertEquals(
            BackupDeletionRefusal.NO_ACCOUNT,
            (refused as BackupDeletionTransition.Refused).refusal,
        )
    }

    @Test
    fun `an account switch invalidates a pending deletion operation`() {
        val pending = BackupDeletionIntent.create("account-A", BackupDeletionPhase.REMOTE_PENDING)

        val wrongCurrent = BackupDeletionStateMachine.advanceAfterRemoteDeletionConfirmed(
            pending,
            expectedAccountID = "account-A",
            currentAccountID = "account-B",
        )
        assertEquals(
            BackupDeletionRefusal.ACCOUNT_MISMATCH,
            (wrongCurrent as BackupDeletionTransition.Refused).refusal,
        )

        val state = BackupDeletionState(fenceReadable = true, intent = pending)
        assertTrue(state.canRetryDeletion("account-A"))
        assertFalse(state.canRetryDeletion("account-B"))

        val otherAccountFence = BackupDeletionStateMachine.prepareAccountDeletion(pending, "account-B")
        assertEquals(
            BackupDeletionRefusal.ACCOUNT_MISMATCH,
            (otherAccountFence as BackupDeletionTransition.Refused).refusal,
        )
    }

    @Test
    fun `a lost account-deletion response only clears for a fresh exact-account session`() {
        val preparation = BackupDeletionIntent.create(
            "account-A",
            BackupDeletionPhase.REMOTE_PENDING,
            retainsEnvelopeForRecovery = true,
        )
        assertEquals(3, preparation.version)

        val wrongSession = BackupDeletionStateMachine.cancelAccountDeletionPreparation(
            preparation,
            currentAccountID = "account-A",
            freshSessionAccountID = "account-B",
        )
        assertTrue(wrongSession is BackupDeletionTransition.Refused)

        val rightSession = BackupDeletionStateMachine.cancelAccountDeletionPreparation(
            preparation,
            currentAccountID = "account-A",
            freshSessionAccountID = "account-A",
        )
        assertTrue(rightSession is BackupDeletionTransition.Clear)
    }

    @Test
    fun `preparing account deletion mints a retained-envelope version-three fence`() {
        val prepared = BackupDeletionStateMachine.prepareAccountDeletion(null, "account-A")
        val intent = (prepared as BackupDeletionTransition.Persist).next

        assertEquals(3, intent.version)
        assertTrue(intent.retainsEnvelopeForRecovery == true)
        assertEquals(BackupDeletionPhase.REMOTE_PENDING, intent.phase)
        assertTrue(intent.operationID != null)
    }

    /** In-memory durable seam that stores the canonical serialized record, not the object. */
    private class SerializedFenceStore(
        var backing: String? = null,
        var writable: Boolean = true,
    ) : BackupDeletionIntentStore {
        override fun read(): BackupDeletionRead {
            val value = backing ?: return BackupDeletionRead.Absent
            return try {
                BackupDeletionRead.Present(BackupDeletionIntentCodec.decode(value))
            } catch (_: IllegalArgumentException) {
                BackupDeletionRead.Unreadable
            }
        }

        override fun write(intent: BackupDeletionIntent): Boolean {
            if (!writable) return false
            backing = BackupDeletionIntentCodec.encode(intent)
            return true
        }

        override fun clear(): Boolean {
            if (!writable) return false
            backing = null
            return true
        }
    }
}
