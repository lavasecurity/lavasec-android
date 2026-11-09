package com.lavasec.app.foundation.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure setup/recovery policies ported with the backup state machine: explicit consent,
 * password and recovery-phrase validation, capability gating, and stale setup-upload admission.
 */
class BackupSetupPoliciesTest {

    // MARK: Setup consent

    @Test
    fun `copying the phrase never asserts an acknowledgment`() {
        var consent = BackupSetupConsent(attemptID = "attempt-1")

        assertFalse(consent.canFinish)
        consent = consent.recordCopy()

        assertTrue(consent.copiedRecoveryPhrase)
        assertFalse(consent.canFinish)
        assertFalse(consent.savedRecoveryPhrase)
        assertFalse(consent.understandsNoRecovery)
    }

    @Test
    fun `both explicit acknowledgments are required to finish`() {
        val copiedAndSaved = BackupSetupConsent(attemptID = "attempt-1")
            .recordCopy()
            .acknowledgeRecoveryPhraseSaved()

        assertFalse(copiedAndSaved.canFinish)

        val finished = copiedAndSaved.acknowledgeNoRecovery()
        assertTrue(finished.canFinish)
    }

    @Test
    fun `resetting a new attempt invalidates every previous acknowledgment`() {
        val acknowledged = BackupSetupConsent(attemptID = "attempt-1")
            .recordCopy()
            .acknowledgeRecoveryPhraseSaved()
            .acknowledgeNoRecovery()
        assertTrue(acknowledged.canFinish)

        val reset = acknowledged.reset { "attempt-2" }

        assertEquals("attempt-2", reset.attemptID)
        assertNotEquals(acknowledged.attemptID, reset.attemptID)
        assertFalse(reset.canFinish)
        assertFalse(reset.copiedRecoveryPhrase)
        assertFalse(reset.savedRecoveryPhrase)
        assertFalse(reset.understandsNoRecovery)
    }

    // MARK: Password policy

    @Test
    fun `short password fails the length requirement`() {
        val result = BackupPasswordPolicy.validate("abcdefg", "abcdefg")

        assertFalse(result.isValid)
        assertFalse(requirement(result, BackupPasswordRequirementID.MINIMUM_LENGTH))
    }

    @Test
    fun `a strong matching password is valid`() {
        val result = BackupPasswordPolicy.validate("lava2026!", "lava2026!")

        assertTrue(result.isValid)
    }

    @Test
    fun `mismatched confirmation fails only the match requirement`() {
        val result = BackupPasswordPolicy.validate("lava2026!", "lava2027!")

        assertFalse(result.isValid)
        assertFalse(requirement(result, BackupPasswordRequirementID.MATCHES_CONFIRMATION))
        assertTrue(requirement(result, BackupPasswordRequirementID.MINIMUM_LENGTH))
        assertTrue(requirement(result, BackupPasswordRequirementID.NUMBER))
        assertTrue(requirement(result, BackupPasswordRequirementID.SYMBOL))
    }

    @Test
    fun `a space is not a symbol`() {
        val result = BackupPasswordPolicy.validate("lava 2026", "lava 2026")

        assertFalse(result.isValid)
        assertFalse(requirement(result, BackupPasswordRequirementID.SYMBOL))
    }

    // MARK: Recovery phrase

    @Test
    fun `phrase parsing collapses separators numbering and case`() {
        assertEquals(
            listOf("river", "maple", "signal"),
            BackupRecoveryPhrase.words("River, MAPLE\nsignal"),
        )
        assertEquals(
            listOf("river", "maple", "signal"),
            BackupRecoveryPhrase.words("1. river  2) maple 3. signal"),
        )
        assertEquals(emptyList<String>(), BackupRecoveryPhrase.words("   "))
    }

    @Test
    fun `fill slots pads missing words and canonical phrase drops empties`() {
        val slots = BackupRecoveryPhrase.fillSlots("river maple", count = 4)
        assertEquals(listOf("river", "maple", "", ""), slots)

        assertEquals("river maple", BackupRecoveryPhrase.phrase(listOf(" River ", "MAPLE", "")))
    }

    @Test
    fun `generate emits the canonical word count from an injected source`() {
        val phrase = BackupRecoveryPhrase.generate { 0 }
        val words = BackupRecoveryPhrase.words(phrase)

        assertEquals(BackupRecoveryPhrase.WORD_COUNT, words.size)
        assertTrue(words.all { it == "baba" })
    }

    // MARK: Recovery capability

    @Test
    fun `an unsupported recovery capability maps to explicit unavailable never a success`() {
        val capabilities = listOf(
            BackupRecoveryCapability(BackupRecoveryMethod.PASSKEY, BackupRecoveryAvailability.UNAVAILABLE),
        )

        val selection = BackupRecoveryCapabilityPolicy.select(BackupRecoveryMethod.PASSKEY, capabilities)

        assertTrue(selection is BackupRecoverySelection.Unavailable)
        assertFalse(selection.isUsable)
    }

    @Test
    fun `a method with no capability fact is unavailable`() {
        val selection = BackupRecoveryCapabilityPolicy.select(BackupRecoveryMethod.PASSKEY, emptyList())

        assertTrue(selection is BackupRecoverySelection.Unavailable)
        assertFalse(selection.isUsable)
    }

    @Test
    fun `an available capability selects the method`() {
        val capabilities = listOf(
            BackupRecoveryCapability(BackupRecoveryMethod.RECOVERY_CODE, BackupRecoveryAvailability.AVAILABLE),
        )

        val selection = BackupRecoveryCapabilityPolicy.select(BackupRecoveryMethod.RECOVERY_CODE, capabilities)

        assertTrue(selection is BackupRecoverySelection.Usable)
        assertTrue(selection.isUsable)
    }

    // MARK: Setup upload admission

    @Test
    fun `a current attempt is admitted for upload`() {
        val decision = BackupSetupUploadPolicy.nextStep(
            attempt = attempt(accountID = "account-A", generation = 7),
            progress = null,
            currentAccountID = "account-A",
            generation = 7,
            deletionFenceBlocks = false,
            isBackupEnabled = true,
            envelopeStillCurrent = true,
        )

        assertTrue(decision is BackupSetupUploadDecision.Upload)
    }

    @Test
    fun `an account switch during setup invalidates the pending upload`() {
        val decision = BackupSetupUploadPolicy.nextStep(
            attempt = attempt(accountID = "account-A", generation = 7),
            progress = null,
            currentAccountID = "account-B",
            generation = 7,
            deletionFenceBlocks = false,
            isBackupEnabled = true,
            envelopeStillCurrent = true,
        )

        assertEquals(BackupSetupUploadDecision.Unavailable, decision)
    }

    @Test
    fun `a lifecycle change deletion fence or envelope change invalidates the pending upload`() {
        val base = attempt(accountID = "account-A", generation = 7)

        fun decide(
            generation: Long = 7,
            deletionFenceBlocks: Boolean = false,
            isBackupEnabled: Boolean = true,
            envelopeStillCurrent: Boolean = true,
        ) = BackupSetupUploadPolicy.nextStep(
            attempt = base,
            progress = null,
            currentAccountID = "account-A",
            generation = generation,
            deletionFenceBlocks = deletionFenceBlocks,
            isBackupEnabled = isBackupEnabled,
            envelopeStillCurrent = envelopeStillCurrent,
        )

        assertEquals(BackupSetupUploadDecision.Unavailable, decide(generation = 8))
        assertEquals(BackupSetupUploadDecision.Unavailable, decide(deletionFenceBlocks = true))
        assertEquals(BackupSetupUploadDecision.Unavailable, decide(isBackupEnabled = false))
        assertEquals(BackupSetupUploadDecision.Unavailable, decide(envelopeStillCurrent = false))
    }

    @Test
    fun `an already-uploaded attempt is not retried`() {
        val base = attempt(accountID = "account-A", generation = 7)
        val progress = BackupSetupUploadProgress(base.id, BackupSetupUploadStatus.UPLOADED)

        val decision = BackupSetupUploadPolicy.nextStep(
            attempt = base,
            progress = progress,
            currentAccountID = "account-A",
            generation = 7,
            deletionFenceBlocks = false,
            isBackupEnabled = true,
            envelopeStillCurrent = true,
        )

        assertEquals(BackupSetupUploadDecision.AlreadySettled, decision)
    }

    @Test
    fun `a failed upload progress requires a condition message`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupSetupUploadProgress("attempt-1", BackupSetupUploadStatus.FAILED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSetupUploadProgress("attempt-1", BackupSetupUploadStatus.UPLOADED, "unexpected")
        }
    }

    private fun requirement(
        result: BackupPasswordValidationResult,
        id: BackupPasswordRequirementID,
    ): Boolean = result.requirements.single { it.id == id }.isSatisfied

    private fun attempt(accountID: String?, generation: Long) = BackupSetupAttempt(
        id = "attempt-1",
        accountID = accountID,
        generation = generation,
        estimatedByteSize = 2_048,
    )
}
