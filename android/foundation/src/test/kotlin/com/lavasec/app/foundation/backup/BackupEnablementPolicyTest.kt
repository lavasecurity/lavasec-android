package com.lavasec.app.foundation.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the nullable enablement contract: an unresolved deletion or unreadable fence is NEVER a
 * definitive Off, and a completed tombstone keeps Off authoritative over a stale local projection.
 */
class BackupEnablementPolicyTest {

    @Test
    fun `an unreadable deletion fence is unavailable with a null value`() {
        val presentation = BackupEnablementPolicy.evaluate(
            facts(deletionFenceReadable = false, hasConfirmedLocalBackup = true),
        )

        assertEquals(BackupEnablementState.UNAVAILABLE, presentation.state)
        assertNull(presentation.value)
        assertFalse(presentation.isDefinitivelyOff)
        assertFalse(presentation.canEnable)
        assertTrue(presentation.state.isNullableEnablement)
    }

    @Test
    fun `unknown local evidence is unavailable rather than off`() {
        val presentation = BackupEnablementPolicy.evaluate(
            facts(deletionFenceReadable = true, hasConfirmedLocalBackup = null),
        )

        assertEquals(BackupEnablementState.UNAVAILABLE, presentation.state)
        assertNull(presentation.value)
        assertFalse(presentation.isDefinitivelyOff)
    }

    @Test
    fun `a pending remote deletion is deletionPending with a null value even when local evidence says on`() {
        val pending = BackupDeletionIntent.create("account-A", BackupDeletionPhase.REMOTE_PENDING)
        val presentation = BackupEnablementPolicy.evaluate(
            facts(deletionFenceReadable = true, deletionIntent = pending, hasConfirmedLocalBackup = true),
        )

        assertEquals(BackupEnablementState.DELETION_PENDING, presentation.state)
        assertNull(presentation.value)
        assertFalse(presentation.isDefinitivelyOff)
        assertFalse(presentation.isConfirmedOn)
    }

    @Test
    fun `a completed tombstone forces a definitive off over a stale configured projection`() {
        val completed = BackupDeletionIntent.create(
            "account-A",
            BackupDeletionPhase.DISABLED,
        )
        val presentation = BackupEnablementPolicy.evaluate(
            facts(deletionFenceReadable = true, deletionIntent = completed, hasConfirmedLocalBackup = true),
        )

        assertEquals(BackupEnablementState.OFF, presentation.state)
        assertEquals(false, presentation.value)
        assertTrue(presentation.isDefinitivelyOff)
    }

    @Test
    fun `confirmed on while signed in exposes every action but never enable`() {
        val presentation = BackupEnablementPolicy.evaluate(
            facts(deletionFenceReadable = true, hasConfirmedLocalBackup = true, isAccountSignedIn = true),
        )

        assertEquals(BackupEnablementState.ON, presentation.state)
        assertEquals(true, presentation.value)
        assertTrue(presentation.isConfirmedOn)
        assertFalse(presentation.canEnable)
        assertTrue(presentation.canDisable)
        assertTrue(presentation.canBackUp)
        assertTrue(presentation.canRestore)
        assertTrue(presentation.canChangeAutomatic)
    }

    @Test
    fun `confirmed off while signed in can enable but has no on-actions`() {
        val presentation = BackupEnablementPolicy.evaluate(
            facts(deletionFenceReadable = true, hasConfirmedLocalBackup = false, isAccountSignedIn = true),
        )

        assertEquals(BackupEnablementState.OFF, presentation.state)
        assertEquals(false, presentation.value)
        assertTrue(presentation.canEnable)
        assertFalse(presentation.canDisable)
        assertFalse(presentation.canBackUp)
        assertTrue(presentation.canRestore)
    }

    @Test
    fun `an operation in flight presents busy but keeps the confirmed value`() {
        val presentation = BackupEnablementPolicy.evaluate(
            facts(
                deletionFenceReadable = true,
                hasConfirmedLocalBackup = true,
                isAccountSignedIn = true,
                isBackingUpNow = true,
            ),
        )

        assertEquals(BackupEnablementState.BUSY, presentation.state)
        assertEquals(true, presentation.value)
        assertFalse(presentation.canDisable)
        assertFalse(presentation.canBackUp)
    }

    @Test
    fun `an in-flight upload task also presents busy`() {
        val presentation = BackupEnablementPolicy.evaluate(
            facts(
                deletionFenceReadable = true,
                hasConfirmedLocalBackup = true,
                isAccountSignedIn = true,
                hasInFlightUpload = true,
            ),
        )

        assertEquals(BackupEnablementState.BUSY, presentation.state)
        assertEquals(true, presentation.value)
    }

    @Test
    fun `a presented setup sheet labels the state setup but preserves the value`() {
        val presentation = BackupEnablementPolicy.evaluate(
            facts(
                deletionFenceReadable = true,
                hasConfirmedLocalBackup = false,
                isAccountSignedIn = true,
                isSetupOrRestorePresented = true,
            ),
        )

        assertEquals(BackupEnablementState.SETUP, presentation.state)
        assertEquals(false, presentation.value)
        assertFalse(presentation.canEnable)
    }

    @Test
    fun `signed out never exposes an action`() {
        val presentation = BackupEnablementPolicy.evaluate(
            facts(deletionFenceReadable = true, hasConfirmedLocalBackup = false, isAccountSignedIn = false),
        )

        assertEquals(BackupEnablementState.OFF, presentation.state)
        assertFalse(presentation.canEnable)
        assertFalse(presentation.canRestore)
    }

    @Test
    fun `retry deletion is admitted for local cleanup regardless of account and for the owning remote account only`() {
        val localCleanup = BackupDeletionIntent.create("account-A", BackupDeletionPhase.LOCAL_CLEANUP_PENDING)
        val cleanupPresentation = BackupEnablementPolicy.evaluate(
            facts(deletionFenceReadable = true, deletionIntent = localCleanup, currentAccountId = "account-B"),
        )
        assertTrue(cleanupPresentation.canRetryDeletion)

        val remotePending = BackupDeletionIntent.create("account-A", BackupDeletionPhase.REMOTE_PENDING)
        val owning = BackupEnablementPolicy.evaluate(
            facts(deletionFenceReadable = true, deletionIntent = remotePending, currentAccountId = "account-A"),
        )
        assertTrue(owning.canRetryDeletion)

        val other = BackupEnablementPolicy.evaluate(
            facts(deletionFenceReadable = true, deletionIntent = remotePending, currentAccountId = "account-B"),
        )
        assertFalse(other.canRetryDeletion)
    }

    private fun facts(
        deletionFenceReadable: Boolean,
        deletionIntent: BackupDeletionIntent? = null,
        hasConfirmedLocalBackup: Boolean? = null,
        isBackupMaintenanceInProgress: Boolean = false,
        isBackingUpNow: Boolean = false,
        isUploadingEncryptedBackup: Boolean = false,
        hasInFlightUpload: Boolean = false,
        isSetupOrRestorePresented: Boolean = false,
        isAccountSignedIn: Boolean = false,
        currentAccountId: String? = null,
    ) = BackupEnablementFacts(
        deletionFenceReadable = deletionFenceReadable,
        deletionIntent = deletionIntent,
        hasConfirmedLocalBackup = hasConfirmedLocalBackup,
        isBackupMaintenanceInProgress = isBackupMaintenanceInProgress,
        isBackingUpNow = isBackingUpNow,
        isUploadingEncryptedBackup = isUploadingEncryptedBackup,
        hasInFlightUpload = hasInFlightUpload,
        isSetupOrRestorePresented = isSetupOrRestorePresented,
        isAccountSignedIn = isAccountSignedIn,
        currentAccountId = currentAccountId,
    )
}
