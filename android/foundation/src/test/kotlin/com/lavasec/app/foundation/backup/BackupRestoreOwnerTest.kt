package com.lavasec.app.foundation.backup

import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreOwnerTest {

    private val basis = BackupRestoreBasis(accountID = "account-A", configurationGeneration = 7)

    private val currentPreferences = BackupRetainedPreferences(
        keepFilteringCounts = true,
        keepDomainDiagnostics = false,
        keepNetworkActivity = true,
        keepLavaGuardProgress = true,
        protectionEnabledHint = true,
    )

    @Test
    fun `checkpoint publish and rollback exceptions consume the review and close adoption custody`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val envelope = BackupEnvelopeCrypto.seal(BackupPayloadJson.encode(payload).toByteArray(),
            mapOf("recoveryPhrase" to "phrase".toCharArray()), 1_000, iterations = 1_000)
        for (phase in listOf("checkpoint", "publish", "restore")) {
            val publisher = FakePublisher().apply {
                if (phase == "checkpoint") checkpointException = IllegalStateException("read failed")
                if (phase == "publish") publishException = IllegalStateException("write failed")
                if (phase == "restore") { failNextPublish = true; restoreException = IllegalStateException("rollback failed") }
            }
            val adoption = BackupRestoreAdoption(envelope, "transient-device-key".toCharArray())
            val captured = adoption.withDeviceSecret { it }!!
            val owner = BackupRestoreOwner(publisher)
            val prepared = owner.prepare(BackupRestoreUnlock.Unlocked(payload, adoption),
                BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, basis) as BackupRestorePreparation.Prepared
            val outcome = owner.confirm(prepared.reviewID, false, basis, current) as BackupRestoreApplyOutcome.Refused
            assertEquals(phase, if (phase == "restore") BackupRestoreRefusal.RollbackFailed
                else BackupRestoreRefusal.PublishInterrupted, outcome.refusal)
            assertNull(phase, adoption.withDeviceSecret { true })
            assertTrue(phase, captured.all { it == '\u0000' })
            assertEquals(phase, if (phase == "checkpoint") 0 else 1, publisher.restoreCalls)
            assertEquals(BackupRestoreRefusal.NoReview,
                (owner.confirm(prepared.reviewID, false, basis, current) as BackupRestoreApplyOutcome.Refused).refusal)
        }
    }

    @Test
    fun `cancellation during checkpoint or publication propagates after custody closes`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val envelope = BackupEnvelopeCrypto.seal(BackupPayloadJson.encode(payload).toByteArray(),
            mapOf("recoveryPhrase" to "phrase".toCharArray()), 1_000, iterations = 1_000)
        for (publishing in listOf(false, true)) {
            val publisher = FakePublisher().apply {
                if (publishing) publishException = CancellationException("retired")
                else checkpointException = CancellationException("retired")
            }
            val adoption = BackupRestoreAdoption(envelope, "device-secret".toCharArray())
            val owner = BackupRestoreOwner(publisher)
            val prepared = owner.prepare(BackupRestoreUnlock.Unlocked(payload, adoption),
                BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, basis) as BackupRestorePreparation.Prepared
            assertTrue(runCatching { owner.confirm(prepared.reviewID, false, basis, current) }.exceptionOrNull()
                is CancellationException)
            assertNull(adoption.withDeviceSecret { true })
            assertEquals(if (publishing) 1 else 0, publisher.restoreCalls)
            assertNull(publisher.committed)
        }
    }

    @Test
    fun `a valid restore prepares a review and publishes atomically once`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK)
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val publisher = FakePublisher()
        val owner = BackupRestoreOwner(publisher)

        val preparation = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        )
        val prepared = preparation as BackupRestorePreparation.Prepared
        assertEquals(BackupRestorePhase.REVIEW, owner.state().phase)
        assertTrue("the retired Mullvad header migrates to Quad9 and needs explicit review", prepared.plan.requiresResolverConfirmation)

        val outcome = owner.confirm(
            reviewID = prepared.reviewID,
            resolverChangeConfirmed = true,
            currentBasis = basis,
            currentLibrary = current,
        )
        val applied = outcome as BackupRestoreApplyOutcome.Applied
        assertEquals(1, publisher.publishCalls)
        assertEquals(applied.plan.restored, publisher.committed)
        assertEquals(BackupRestorePhase.APPLIED, owner.state().phase)
        assertEquals(applied.plan, owner.state().plan)
    }

    @Test
    fun `a decrypted payload that maps through the mapper keeps this devices protection intent`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK)
        // The backup was made on a device with protection off; this device has it on.
        val payload = BackupPayloadMapper.toPayload(
            current,
            currentPreferences.copy(protectionEnabledHint = false),
        )
        val owner = BackupRestoreOwner(FakePublisher())

        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        assertTrue(prepared.plan.preferences.protectionEnabledHint)
    }

    @Test
    fun `a schema newer than this build refuses without staging or publishing`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val publisher = FakePublisher()
        val owner = BackupRestoreOwner(publisher)

        val unlockRefusal = owner.prepare(
            unlock = BackupRestoreUnlock.UnsupportedSchema(BackupConfigurationPayload.CURRENT_SUPPORTED_SCHEMA_VERSION + 1),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        )
        assertEquals(
            BackupRestoreRefusal.UnsupportedSchema(BackupConfigurationPayload.CURRENT_SUPPORTED_SCHEMA_VERSION + 1),
            (unlockRefusal as BackupRestorePreparation.Refused).refusal,
        )

        // The mapper refuses the newer schema too, even when the unlock claims success.
        val newerPayload = BackupConfigurationPayload(
            schemaVersion = BackupConfigurationPayload.CURRENT_SUPPORTED_SCHEMA_VERSION + 1,
            resolverPresetID = BackupResolverPresetBridge.DEVICE_DNS_ID,
        )
        val envelope = BackupEnvelopeCrypto.seal(
            payload = BackupPayloadJson.encode(newerPayload).toByteArray(),
            secrets = mapOf("recoveryPhrase" to "phrase".toCharArray()),
            createdAtEpochSeconds = 1_000,
        )
        val adoption = BackupRestoreAdoption(envelope, "device-secret".toCharArray())
        val planRefusal = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(newerPayload, adoption),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        )
        assertEquals(
            BackupRestoreRefusal.UnsupportedSchema(BackupConfigurationPayload.CURRENT_SUPPORTED_SCHEMA_VERSION + 1),
            (planRefusal as BackupRestorePreparation.Refused).refusal,
        )
        assertEquals(0, publisher.publishCalls)
        assertEquals(BackupRestorePhase.IDLE, owner.state().phase)
        assertNull("a refused plan must release device custody", adoption.withDeviceSecret { true })
    }

    @Test
    fun `a corrupt payload refuses and never publishes`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val publisher = FakePublisher()
        val owner = BackupRestoreOwner(publisher)

        val preparation = owner.prepare(
            unlock = BackupRestoreUnlock.CorruptPayload,
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        )
        assertEquals(BackupRestoreRefusal.CorruptPayload, (preparation as BackupRestorePreparation.Refused).refusal)
        assertNull(owner.state().reviewID)
        assertEquals(0, publisher.publishCalls)
        assertNull(publisher.committed)
    }

    @Test
    fun `a wrong secret refuses and never publishes`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val publisher = FakePublisher()
        val owner = BackupRestoreOwner(publisher)

        val preparation = owner.prepare(
            unlock = BackupRestoreUnlock.WrongSecret,
            method = BackupRecoveryMethod.DEVICE_KEY,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        )
        assertEquals(BackupRestoreRefusal.WrongSecret, (preparation as BackupRestorePreparation.Refused).refusal)
        assertEquals(0, publisher.publishCalls)
    }

    @Test
    fun `an account switch or configuration write makes the review stale and publishes nothing`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK)
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val publisher = FakePublisher()
        val owner = BackupRestoreOwner(publisher)
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val accountSwitch = owner.confirm(
            reviewID = prepared.reviewID,
            resolverChangeConfirmed = false,
            currentBasis = basis.copy(accountID = "account-B"),
            currentLibrary = current,
        )
        assertEquals(BackupRestoreRefusal.StaleReview, (accountSwitch as BackupRestoreApplyOutcome.Refused).refusal)

        // Re-prepare, then write a configuration change: the library no longer matches the basis.
        val rePrepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared
        val changedLibrary = current.withProfile(
            current.profiles.first().withEnabledSourceIds(setOf("changed-source")),
        )
        val configChange = owner.confirm(
            reviewID = rePrepared.reviewID,
            resolverChangeConfirmed = false,
            currentBasis = basis,
            currentLibrary = changedLibrary,
        )
        assertEquals(BackupRestoreRefusal.StaleReview, (configChange as BackupRestoreApplyOutcome.Refused).refusal)

        assertEquals(0, publisher.publishCalls)
        assertEquals(BackupRestorePhase.IDLE, owner.state().phase)
    }

    @Test
    fun `an interrupted publish is rolled back and refused, changing nothing`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK)
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val publisher = FakePublisher().apply { failNextPublish = true }
        val owner = BackupRestoreOwner(publisher)
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val outcome = owner.confirm(
            reviewID = prepared.reviewID,
            resolverChangeConfirmed = true,
            currentBasis = basis,
            currentLibrary = current,
        )

        assertEquals(BackupRestoreRefusal.PublishInterrupted, (outcome as BackupRestoreApplyOutcome.Refused).refusal)
        assertEquals(1, publisher.publishCalls)
        assertEquals(1, publisher.restoreCalls)
        assertNull("a partial publish must be rolled back to the prior state", publisher.committed)
        assertNotEquals(BackupRestorePhase.APPLIED, owner.state().phase)
        assertNull(owner.state().plan)
    }

    @Test
    fun `an interrupted publish whose rollback cannot be committed refuses as RollbackFailed`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK)
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val publisher = FakePublisher().apply { failNextPublish = true; failNextRestore = true }
        val owner = BackupRestoreOwner(publisher)
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val outcome = owner.confirm(
            reviewID = prepared.reviewID,
            resolverChangeConfirmed = true,
            currentBasis = basis,
            currentLibrary = current,
        )

        assertEquals(
            "a rollback that is not durably re-committed must not promise nothing changed",
            BackupRestoreRefusal.RollbackFailed,
            (outcome as BackupRestoreApplyOutcome.Refused).refusal,
        )
        assertEquals(1, publisher.restoreCalls)
        assertNotEquals(BackupRestorePhase.APPLIED, owner.state().phase)
    }

    @Test
    fun `discarding a review prevents a later confirmation`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK)
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val publisher = FakePublisher()
        val owner = BackupRestoreOwner(publisher)
        val envelope = BackupEnvelopeCrypto.seal(BackupPayloadJson.encode(payload).toByteArray(),
            mapOf("recoveryPhrase" to "phrase".toCharArray()), 1_000)
        val adoption = BackupRestoreAdoption(envelope, "device-secret".toCharArray())
        val capturedSecret = adoption.withDeviceSecret { it }!!
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload, adoption),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        assertTrue(owner.discard(prepared.reviewID))
        assertNull(adoption.withDeviceSecret { true })
        assertTrue(capturedSecret.all { it == '\u0000' })
        val outcome = owner.confirm(prepared.reviewID, false, basis, current)
        assertEquals(BackupRestoreRefusal.NoReview, (outcome as BackupRestoreApplyOutcome.Refused).refusal)
        assertEquals(0, publisher.publishCalls)
    }

    @Test
    fun `a changed resolver needs its own acknowledgement`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val incoming = library(MilestoneResolverPlan.MULLVAD_ONLY)
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        val publisher = FakePublisher()
        val owner = BackupRestoreOwner(publisher)
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared
        assertTrue(prepared.plan.requiresResolverConfirmation)

        val unacknowledged = owner.confirm(prepared.reviewID, false, basis, current)
        assertEquals(
            BackupRestoreRefusal.ResolverConfirmationRequired,
            (unacknowledged as BackupRestoreApplyOutcome.Refused).refusal,
        )

        // The refused review is consumed; a fresh review with acknowledgement applies.
        val rePrepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared
        val applied = owner.confirm(rePrepared.reviewID, true, basis, current)
        assertTrue(applied is BackupRestoreApplyOutcome.Applied)
        assertEquals(1, publisher.publishCalls)
        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, publisher.committed?.library?.resolverPlan)
    }

    @Test
    fun `a custom resolver endpoint change needs acknowledgement and is committed`() {
        val current = library(MilestoneResolverPlan.MULLVAD_ONLY)
        val customDohUrl = "https://dns.home.example/dns-query"
        val incoming = FilterLibraryDraft(
            profiles = current.profiles,
            activeProfileId = current.activeProfileId,
            resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
            customDohUrl = customDohUrl,
        )
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        val publisher = FakePublisher()
        val owner = BackupRestoreOwner(publisher)
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared
        assertTrue(
            "a custom endpoint change between two encrypted plans still needs acknowledgement",
            prepared.plan.requiresResolverConfirmation,
        )

        val applied = owner.confirm(prepared.reviewID, true, basis, current)
        assertTrue(applied is BackupRestoreApplyOutcome.Applied)
        assertEquals(customDohUrl, publisher.committed?.library?.customDohUrl)
    }

    @Test
    fun `the review lists added removed and changed filters`() {
        val current = FilterLibraryDraft(
            profiles = listOf(profile("a", "Alpha", blocked = setOf("old.example"))),
            activeProfileId = "a",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
        )
        val incoming = FilterLibraryDraft(
            profiles = listOf(
                profile("a", "Alpha", blocked = setOf("new.example")),
                profile("b", "Beta", blocked = setOf("b.example")),
            ),
            activeProfileId = "b",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
        )
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        val owner = BackupRestoreOwner(FakePublisher())

        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val changes = prepared.plan.filterChanges
        assertEquals(listOf("a", "b"), changes.map { it.filterID })
        assertTrue(changes.first { it.filterID == "a" }.hasChanges)
        assertTrue(changes.first { it.filterID == "b" }.isAdded)
        assertFalse(changes.any { it.isRemoved })
    }

    @Test
    fun `device-key restore needs observed custody even after a valid payload was decoded`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val publisher = FakePublisher()
        val absent = BackupRestoreOwner(publisher)
        assertFalse(absent.selectMethod(BackupRecoveryMethod.DEVICE_KEY).isUsable)
        val refusal = absent.prepare(BackupRestoreUnlock.Unlocked(payload), BackupRecoveryMethod.DEVICE_KEY,
            current, currentPreferences, basis) as BackupRestorePreparation.Refused
        assertEquals(BackupRestoreRefusal.Unavailable, refusal.refusal)
        assertEquals(0, publisher.publishCalls)
        val available = BackupRestoreOwner(publisher, deviceKey = BackupDeviceKeyCapability { true })
        assertTrue(available.selectMethod(BackupRecoveryMethod.DEVICE_KEY).isUsable)
        assertTrue(available.prepare(BackupRestoreUnlock.Unlocked(payload), BackupRecoveryMethod.DEVICE_KEY,
            current, currentPreferences, basis) is BackupRestorePreparation.Prepared)
    }

    @Test
    fun `passkey restore is honestly unavailable when the platform has no PRF`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val publisher = FakePublisher()
        val owner = BackupRestoreOwner(publisher)

        assertFalse(owner.selectMethod(BackupRecoveryMethod.PASSKEY).isUsable)
        val preparation = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.PASSKEY,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        )
        assertEquals(BackupRestoreRefusal.PasskeyUnavailable, (preparation as BackupRestorePreparation.Refused).refusal)
        assertEquals(0, publisher.publishCalls)
    }

    @Test
    fun `passkey restore is admitted when the platform confirms PRF support`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val owner = BackupRestoreOwner(
            publisher = FakePublisher(),
            passkeyPrf = BackupPasskeyPrfCapability { true },
        )

        assertTrue(owner.selectMethod(BackupRecoveryMethod.PASSKEY).isUsable)
        val preparation = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.PASSKEY,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        )
        assertTrue(preparation is BackupRestorePreparation.Prepared)
    }

    private class FakePublisher : BackupRestorePublisher {
        var committed: RestoredBackupSettings? = null
        var publishCalls = 0
        var restoreCalls = 0
        var failNextPublish = false
        var failNextRestore = false
        var checkpointException: Exception? = null
        var publishException: Exception? = null
        var restoreException: Exception? = null

        private data class Snapshot(val value: RestoredBackupSettings?) : BackupRestoreCheckpoint

        override fun checkpoint(): BackupRestoreCheckpoint {
            checkpointException?.let { throw it }
            return Snapshot(committed)
        }

        override fun publish(plan: BackupRestorePlan, adoption: BackupRestoreAdoption?): Boolean {
            publishCalls++
            // Simulate a write that lands partially: the port must either be rolled back (false) or
            // have committed the whole plan (true).
            committed = plan.restored
            publishException?.let { throw it }
            return !failNextPublish
        }

        override fun restore(checkpoint: BackupRestoreCheckpoint): Boolean {
            restoreCalls++
            restoreException?.let { throw it }
            committed = (checkpoint as Snapshot).value
            return !failNextRestore
        }
    }

    private fun library(plan: MilestoneResolverPlan): FilterLibraryDraft = FilterLibraryDraft(
        profiles = listOf(profile("filter-balanced", "Balanced", blocked = setOf("casino.example"))),
        activeProfileId = "filter-balanced",
        resolverPlan = plan,
    )

    private fun profile(
        id: String,
        name: String,
        blocked: Set<String> = emptySet(),
        allowed: Set<String> = emptySet(),
    ): FilterProfileDraft = FilterProfileDraft(
        id = id,
        name = name,
        protectionLevel = null,
        enabledSourceIds = setOf("blocklistproject-basic"),
        manualRules = ManualDomainRules(allowedDomains = allowed, blockedDomains = blocked),
    )
}
