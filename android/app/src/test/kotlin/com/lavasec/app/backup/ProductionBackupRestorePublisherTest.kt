package com.lavasec.app.backup

import com.lavasec.app.data.GuardianAchievementAuthority
import com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger
import com.lavasec.app.foundation.backup.BackupLavaGuardUnlockRecord
import com.lavasec.app.foundation.preferences.GuardianProgressPolicy
import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupPayloadMapper
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupRecoveryMethod
import com.lavasec.app.foundation.backup.BackupRestoreAdoption
import com.lavasec.app.foundation.backup.BackupRestoreApplyOutcome
import com.lavasec.app.foundation.backup.BackupRestoreBasis
import com.lavasec.app.foundation.backup.BackupRestoreOwner
import com.lavasec.app.foundation.backup.BackupRestorePreparation
import com.lavasec.app.foundation.backup.BackupRestoreRefusal
import com.lavasec.app.foundation.backup.BackupRestoreUnlock
import com.lavasec.app.foundation.backup.BackupRetainedPreferences
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.platform.FilterMutationFailure
import com.lavasec.app.product.FilterLibraryMutationResult
import com.lavasec.app.product.FilterLibraryOwner
import com.lavasec.app.product.FilterLibraryPort
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the reviewed backup-restore publish path is real: the production publisher over the
 * [BackupRestoreAuthority] seam commits the whole restored library and the retained preferences
 * through the same owners the app renders, rolls a partial write back, and never reports success
 * without a durable state change. Plain JVM: no Android, no Robolectric.
 */
class ProductionBackupRestorePublisherTest {

    private val basis = BackupRestoreBasis(accountID = "account-A", configurationGeneration = 7)

    private val currentPreferences = BackupRetainedPreferences(
        keepFilteringCounts = true,
        keepDomainDiagnostics = false,
        keepNetworkActivity = true,
        keepLavaGuardProgress = true,
        protectionEnabledHint = true,
    )

    @Test
    fun `portable publication excludes automatic upload until commit or compensation without cloud custody`() = runBlocking {
        for (failPreferences in listOf(false, true)) {
            val previous = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
            val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
            val forwardPublished = CountDownLatch(1)
            val releasePreferences = CountDownLatch(1)
            val uploadAttempted = CountDownLatch(1)
            val uploadedLibrary = AtomicReference<FilterLibraryDraft?>()
            val sharedCustody = Mutex()
            val authority = FakeAuthority(previous, currentPreferences).apply {
                this.failPreferences = failPreferences
                beforePreferences = {
                    forwardPublished.countDown()
                    check(releasePreferences.await(10, TimeUnit.SECONDS))
                }
            }
            val exclusion = object : BackupRestorePublicationExclusion {
                override suspend fun <T> withExclusiveAccess(block: suspend () -> T): T =
                    sharedCustody.withLock { block() }
            }
            val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority,
                publicationExclusion = exclusion))
            val localBasis = basis.copy(accountID = null)
            val prepared = owner.prepare(BackupRestoreUnlock.Unlocked(
                BackupPayloadMapper.toPayload(incoming, currentPreferences)),
                BackupRecoveryMethod.RECOVERY_CODE, previous, currentPreferences, localBasis)
                as BackupRestorePreparation.Prepared
            val reviewedLibrary = prepared.plan.library
            assertNotEquals(previous, reviewedLibrary)
            val importing = async(Dispatchers.IO) {
                owner.confirm(prepared.reviewID, false, localBasis, previous)
            }
            var upload: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                assertTrue(forwardPublished.await(10, TimeUnit.SECONDS))
                assertEquals(reviewedLibrary, authority.library)
                upload = async(Dispatchers.IO) {
                    uploadAttempted.countDown()
                    sharedCustody.withLock { uploadedLibrary.set(authority.library) }
                }
                assertTrue(uploadAttempted.await(10, TimeUnit.SECONDS))
                assertFalse(upload.isCompleted)
                assertNull(uploadedLibrary.get())
            } finally { releasePreferences.countDown() }
            val outcome = importing.await()
            upload?.await()
            if (failPreferences) {
                assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted), outcome)
                assertEquals(previous, uploadedLibrary.get())
            } else {
                assertTrue(outcome is BackupRestoreApplyOutcome.Applied)
                assertEquals(reviewedLibrary, uploadedLibrary.get())
            }
        }
    }

    @Test
    fun `portable restore compensates its exact forward write after authorization retires`() {
        val previous = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        var live = previous
        var authorized = true
        var rollbacks = 0
        val authority = ProductBackupRestoreAuthority(
            library = { live }, logging = { currentPreferences.toLoggingSettings() }, currentGeneration = { 7L },
            replaceLibraryWith = { value, expected ->
                if (!authorized || expected != live) false else {
                    live = value
                    authorized = false // Background/security retirement as the forward write lands.
                    true
                }
            },
            rollbackLibraryWith = { value, expected ->
                rollbacks++
                if (expected != live) false else { live = value; true }
            },
            setLoggingWith = { authorized },
            restoreLoggingWith = { _, _, _ -> authorized },
            protectionEnabledHint = { currentPreferences.protectionEnabledHint },
        )
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined))
        val localBasis = basis.copy(accountID = null)
        val preparation = owner.prepare(BackupRestoreUnlock.Unlocked(BackupPayloadMapper.toPayload(incoming, currentPreferences)),
            BackupRecoveryMethod.RECOVERY_CODE, previous, currentPreferences, localBasis) as BackupRestorePreparation.Prepared
        assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted),
            owner.confirm(preparation.reviewID, false, localBasis, previous))
        assertEquals(previous, live)
        assertEquals(1, rollbacks)
    }

    @Test
    fun `restore adopts portable achievements and rolls only its exact ledger back on failed preferences`() {
        for (failure in listOf("none", "preferences", "concurrent-before-cas", "concurrent-before-rollback", "unreadable")) {
            val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
            val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
            val previous = ledger("emberObsidian")
            val target = ledger("emerald")
            val earned = ledger("aquamarine")
            val guardian = FakeGuardian(previous)
            val authority = FakeAuthority(current, currentPreferences).apply {
                failPreferences = failure == "preferences" || failure == "concurrent-before-rollback"
                beforePreferences = { if (failure == "concurrent-before-rollback")
                    guardian.ledger = GuardianProgressPolicy.normalize(BackupLavaGuardAchievementLedger(target.records + earned.records)) }
            }
            val capturedBasis = basis.copy(guardianLedger = previous)
            val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, guardian = guardian))
            val preparation = owner.prepare(BackupRestoreUnlock.Unlocked(
                BackupPayloadMapper.toPayload(incoming, currentPreferences, target)),
                BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, capturedBasis) as BackupRestorePreparation.Prepared
            if (failure == "concurrent-before-cas") guardian.beforeReplace = {
                guardian.ledger = GuardianProgressPolicy.normalize(BackupLavaGuardAchievementLedger(previous.records + earned.records))
            }
            guardian.failRead = failure == "unreadable"
            val outcome = owner.confirm(preparation.reviewID, false, capturedBasis, current)
            when (failure) {
                "none" -> { assertTrue(outcome is BackupRestoreApplyOutcome.Applied); assertEquals(target, guardian.ledger) }
                "concurrent-before-rollback" -> {
                    assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.RollbackFailed), outcome)
                    assertEquals(setOf("emerald", "aquamarine"), guardian.ledger.records.map { it.guardID }.toSet())
                    assertEquals(current, authority.library)
                }
                "concurrent-before-cas" -> {
                    assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted), outcome)
                    assertEquals(setOf("emberObsidian", "aquamarine"), guardian.ledger.records.map { it.guardID }.toSet())
                    assertEquals(current, authority.library)
                    assertEquals(0, authority.preferenceCalls)
                }
                else -> {
                    assertEquals(failure, BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted), outcome)
                    assertEquals(failure, previous, guardian.ledger)
                    assertEquals(failure, current, authority.library)
                    if (failure == "unreadable") assertEquals(0, authority.replaceCalls)
                }
            }
        }
    }

    @Test
    fun `earning a guard while the review is open consumes the stale review without publication`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = emptySet())
        val previous = ledger("emberObsidian")
        val guardian = FakeGuardian(previous)
        val authority = FakeAuthority(current, currentPreferences)
        val capturedBasis = basis.copy(guardianLedger = previous)
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, guardian = guardian))
        val preparation = owner.prepare(BackupRestoreUnlock.Unlocked(BackupPayloadMapper.toPayload(current, currentPreferences,
            ledger("emerald"))), BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, capturedBasis) as BackupRestorePreparation.Prepared
        guardian.ledger = ledger("purpleObsidian")
        assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.StaleReview),
            owner.confirm(preparation.reviewID, false, capturedBasis.copy(guardianLedger = guardian.ledger), current))
        assertEquals(0, authority.replaceCalls)
        assertEquals(0, guardian.replaceCalls)
        assertEquals(ledger("purpleObsidian"), guardian.ledger)
    }

    @Test
    fun `a publisher without the achievement authority refuses a nonempty portable ledger`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = emptySet())
        val authority = FakeAuthority(current, currentPreferences)
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined))
        val preparation = owner.prepare(BackupRestoreUnlock.Unlocked(BackupPayloadMapper.toPayload(current, currentPreferences,
            ledger("emerald"))), BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, basis) as BackupRestorePreparation.Prepared
        assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted),
            owner.confirm(preparation.reviewID, false, basis, current))
        assertEquals(0, authority.replaceCalls)
        assertEquals(0, authority.preferenceCalls)
    }

    private fun ledger(id: String) = BackupLavaGuardAchievementLedger(listOf(BackupLavaGuardUnlockRecord(id, 10.0)))
    private class FakeGuardian(var ledger: BackupLavaGuardAchievementLedger) : GuardianAchievementAuthority {
        var replaceCalls = 0
        var beforeReplace: (() -> Unit)? = null
        var failRead = false
        override fun captureLedger(): BackupLavaGuardAchievementLedger {
            check(!failRead) { "Guardian storage unavailable" }
            return ledger
        }
        override fun replaceLedger(value: BackupLavaGuardAchievementLedger, expected: BackupLavaGuardAchievementLedger): Boolean {
            replaceCalls++
            beforeReplace?.also { beforeReplace = null }?.invoke()
            if (ledger != expected) return false
            ledger = GuardianProgressPolicy.normalize(value)
            return true
        }
    }

    @Test
    fun `failed production checkpoint reads clear copied keys and allow a fresh review without writes`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        val envelope = BackupEnvelopeCrypto.seal(BackupPayloadJson.encode(payload).toByteArray(),
            mapOf("recoveryPhrase" to "phrase".toCharArray()), 1_000, iterations = 1_000)
        for (phase in listOf("preferences", "envelope", "account-binding", "upload-marker")) {
            val authority = FakeAuthority(current, currentPreferences).apply { throwPreferencesRead = phase == "preferences" }
            val custody = FakeCustody().apply {
                sealingKey = "retained-old-key".toCharArray()
                throwRead = phase
            }
            val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, custody))
            val adoption = BackupRestoreAdoption(envelope, "transient-new-key".toCharArray())
            val prepared = owner.prepare(BackupRestoreUnlock.Unlocked(payload, adoption), BackupRecoveryMethod.RECOVERY_CODE,
                current, currentPreferences, basis) as BackupRestorePreparation.Prepared
            val outcome = owner.confirm(prepared.reviewID, false, basis, current) as BackupRestoreApplyOutcome.Refused
            assertEquals(phase, BackupRestoreRefusal.PublishInterrupted, outcome.refusal)
            assertEquals(phase, 0, authority.replaceCalls)
            assertEquals(phase, 0, authority.preferenceCalls)
            assertEquals(phase, 0, custody.adoptCalls)
            assertNull(adoption.withDeviceSecret { true })
            assertTrue(phase, custody.returnedKeys.single().all { it == '\u0000' })
            assertEquals("retained-old-key", String(custody.sealingKey!!))
            authority.throwPreferencesRead = false
            custody.throwRead = null
            val fresh = owner.prepare(BackupRestoreUnlock.Unlocked(payload), BackupRecoveryMethod.RECOVERY_CODE,
                current, currentPreferences, basis) as BackupRestorePreparation.Prepared
            assertTrue(owner.confirm(fresh.reviewID, false, basis, current) is BackupRestoreApplyOutcome.Applied)
            assertEquals(phase, fresh.plan.library, authority.library)
            assertTrue(custody.returnedKeys.all { key -> key.all { it == '\u0000' } })
        }
    }

    @Test
    fun `a valid plan commits the restored library and preferences to the live authority`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = FilterLibraryDraft(
            profiles = listOf(
                profile("filter-balanced", "Balanced", blocked = setOf("new.example")),
                profile("filter-extra", "Extra", blocked = setOf("extra.example")),
            ),
            activeProfileId = "filter-extra",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
        )
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        val authority = FakeAuthority(current, currentPreferences)
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined))

        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val outcome = owner.confirm(prepared.reviewID, false, basis, current)

        assertTrue(outcome is BackupRestoreApplyOutcome.Applied)
        assertEquals(1, authority.replaceCalls)
        assertEquals(1, authority.preferenceCalls)
        assertEquals(prepared.plan.library, authority.library)
        assertEquals(
            prepared.plan.preferences.keepFilteringCounts,
            authority.preferences?.keepFilteringCounts,
        )
        assertTrue(prepared.plan.filterChanges.any { it.filterID == "filter-extra" })
    }

    @Test
    fun `a failed preference write rolls the library back and refuses`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        val authority = FakeAuthority(current, currentPreferences).apply { failPreferences = true }
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined))

        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val outcome = owner.confirm(prepared.reviewID, false, basis, current)

        assertEquals(
            BackupRestoreRefusal.PublishInterrupted,
            (outcome as BackupRestoreApplyOutcome.Refused).refusal,
        )
        // The library was replaced first, then the preference write failed; the owner's rollback
        // restored the exact pre-publish library.
        assertEquals(current, authority.library)
        assertEquals(currentPreferences, authority.preferences)
    }

    @Test
    fun `a failed library rollback reports the stronger RollbackFailed refusal`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        // Publish replaces the library, then the preference write fails; the rollback's library
        // re-commit (the second replaceLibrary call) also fails, so the changed state may persist.
        val authority = FakeAuthority(current, currentPreferences).apply {
            failPreferences = true
            failLibraryFromCall = 2
        }
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined))
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val outcome = owner.confirm(prepared.reviewID, false, basis, current)

        assertEquals(
            BackupRestoreRefusal.RollbackFailed,
            (outcome as BackupRestoreApplyOutcome.Refused).refusal,
        )
        assertNotEquals("a failed library rollback leaves the post-publish library live", current, authority.library)
    }

    @Test
    fun `a credential rotation mid-transaction refuses the publication and reports a failed rollback`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        // The library write lands, then the credential graph rotates before the logging write.
        val authority = FakeAuthority(current, currentPreferences).apply { rotateOnReplace = true }
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined))
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val outcome = owner.confirm(prepared.reviewID, false, basis, current)

        assertEquals(
            "a rotation between the two writes must fail the publication, and the rollback is also refused",
            BackupRestoreRefusal.RollbackFailed,
            (outcome as BackupRestoreApplyOutcome.Refused).refusal,
        )
        assertEquals(1, authority.replaceCalls)
        // The rotation is detected before the logging write, so it is never attempted.
        assertEquals(0, authority.preferenceCalls)
    }

    /**
     * The restore's compare-and-replace: a filter mutation committed after the review's checkpoint
     * but before the publish must refuse the restore rather than overwrite the newer library.
     */
    @Test
    fun `a concurrent library mutation between review and write refuses the restore`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        val authority = FakeAuthority(current, currentPreferences)
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined))
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        // A different filter mutation commits between the confirmation validation and the write.
        val concurrent = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("concurrent.example"))
        authority.beforeReplace = { authority.library = concurrent }

        val outcome = owner.confirm(prepared.reviewID, false, basis, current)

        assertEquals(
            BackupRestoreRefusal.PublishInterrupted,
            (outcome as BackupRestoreApplyOutcome.Refused).refusal,
        )
        assertEquals("the concurrent newer library must survive", concurrent, authority.library)
    }

    @Test
    fun `a missing live library refuses without any write`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val payload = BackupPayloadMapper.toPayload(current, currentPreferences)
        val authority = FakeAuthority(current, currentPreferences).apply { library = null }
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined))

        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val outcome = owner.confirm(prepared.reviewID, false, basis, current)

        assertEquals(
            BackupRestoreRefusal.PublishInterrupted,
            (outcome as BackupRestoreApplyOutcome.Refused).refusal,
        )
        assertEquals(0, authority.replaceCalls)
        assertNull(authority.library)
    }

    /**
     * The production publisher drives the REAL [FilterLibraryOwner] (the same generation-fenced
     * coordinator every filter mutation uses), not just the fake authority: the wholesale
     * replacement lands in the live port and the owner settles on the committed library.
     */
    @Test
    fun `the publisher drives the real filter library owner through a wholesale replacement`(): Unit = runBlocking {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        val port = MutableLibraryPort(current)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val libraryOwner = FilterLibraryOwner(port, scope)
        val retained = arrayOf<BackupRetainedPreferences?>(currentPreferences)
        val authority = object : BackupRestoreAuthority {
            override fun currentLibrary(): FilterLibraryDraft? = libraryOwner.currentLibrary()

            override fun currentPreferences(): BackupRetainedPreferences? = retained[0]

            override fun generation(): Long? = 1L

            override suspend fun replaceLibrary(
                library: FilterLibraryDraft,
                expectedLibrary: FilterLibraryDraft?,
            ): Boolean = libraryOwner.replaceAll(library, expectedLibrary = expectedLibrary)

            override suspend fun applyPreferences(preferences: BackupRetainedPreferences): Boolean {
                retained[0] = preferences
                return true
            }
        }
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.IO))
        val libraryBasis = basis.copy(configurationGeneration = 1L)

        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(payload),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = libraryBasis,
        ) as BackupRestorePreparation.Prepared

        val outcome = owner.confirm(prepared.reviewID, false, libraryBasis, current)

        assertTrue(outcome is BackupRestoreApplyOutcome.Applied)
        assertEquals(prepared.plan.library, port.currentLibrary())
        libraryOwner.close()
        scope.cancel()
    }

    private fun adoptionEnvelope(): BackupEnvelope = BackupEnvelopeCrypto.seal(
        payload = ByteArray(32) { 1 },
        secrets = mapOf("keychain" to "device-key".toCharArray()),
        createdAtEpochSeconds = 1_000,
        iterations = 8,
    )

    /** A cloud receipt never follows a locally re-keyed adoption; compensation retains its own receipt. */
    @Test
    fun `production custody resets the adopted upload receipt and compensates only its previous receipt`() {
        for (phase in listOf("committed", "preferences-refused", "marker-clear-refused", "marker-restore-refused")) {
            val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
            val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
            val previous = adoptionEnvelope()
            val adopted = adoptionEnvelope()
            val backing = FakeBackupLocalStore().apply {
                envelope = previous
                envelopeAccountID = TEST_ACCOUNT
                uploadedAt = 111L
            }
            val forwardWrites = mutableListOf<String>()
            val store = object : BackupLocalStore by backing {
                override fun clearUploadMarker(): Boolean =
                    if (phase == "marker-clear-refused") false else backing.clearUploadMarker()
                override fun saveEnvelope(envelope: BackupEnvelope): Boolean {
                    if (envelope == adopted) {
                        forwardWrites += "envelope"
                        assertNull("the old receipt must be absent before new ciphertext is stored", backing.uploadedAt)
                    }
                    return backing.saveEnvelope(envelope)
                }
                override fun saveEnvelopeAccountID(accountID: String?): Boolean {
                    if (backing.envelope == adopted) {
                        forwardWrites += "account"
                        assertNull("new account custody must never inherit the old receipt", backing.uploadedAt)
                    }
                    return backing.saveEnvelopeAccountID(accountID)
                }
                override fun recordUploadedAtEpochSeconds(value: Long): Boolean =
                    if (phase == "marker-restore-refused") false else backing.recordUploadedAtEpochSeconds(value)
            }
            val retainedKeys = InMemoryBackupSealingKeyStore("old-device-key".toCharArray())
            val keys = object : BackupSealingKeyStore by retainedKeys {
                override fun save(key: CharArray): Boolean {
                    if (backing.envelope == adopted) {
                        forwardWrites += "key"
                        assertNull("new device-key custody must never inherit the old receipt", backing.uploadedAt)
                    }
                    return retainedKeys.save(key)
                }
            }
            val custody = ProductBackupRestoreCustody(store, keys) { TEST_ACCOUNT }
            val authority = FakeAuthority(current, currentPreferences).apply {
                failPreferences = phase == "preferences-refused" || phase == "marker-restore-refused"
            }
            val capturedBasis = basis.copy(accountID = TEST_ACCOUNT)
            val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, custody))
            val preparation = owner.prepare(BackupRestoreUnlock.Unlocked(
                BackupPayloadMapper.toPayload(incoming, currentPreferences),
                BackupRestoreAdoption(adopted, "new-device-key".toCharArray())),
                BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, capturedBasis)
                as BackupRestorePreparation.Prepared

            val result = owner.confirm(preparation.reviewID, false, capturedBasis, current)

            if (phase == "committed") {
                assertTrue(result is BackupRestoreApplyOutcome.Applied)
                assertEquals(adopted, backing.envelope)
                assertEquals("new-device-key", keys.load()!!.concatToString())
                assertNull("local adoption is not proof of a cloud upload", backing.uploadedAt)
                // Portable restore normalizes seeded identity and retained DNS rows. The complete
                // durable target is the reviewed plan, not the pre-export helper's raw shape.
                assertEquals(preparation.plan.library, authority.library)
            } else {
                val refusal = if (phase == "marker-restore-refused") BackupRestoreRefusal.RollbackFailed
                    else BackupRestoreRefusal.PublishInterrupted
                assertEquals(phase, BackupRestoreApplyOutcome.Refused(refusal), result)
                assertEquals(phase, previous, backing.envelope)
                assertEquals(phase, TEST_ACCOUNT, backing.envelopeAccountID)
                assertEquals(phase, "old-device-key", keys.load()!!.concatToString())
                assertEquals(phase, current, authority.library)
                assertEquals(phase, if (phase == "marker-restore-refused") null else 111L, backing.uploadedAt)
                if (phase == "marker-clear-refused") assertEquals(0, authority.preferenceCalls)
            }
            assertEquals(phase, if (phase == "marker-clear-refused") emptyList<String>()
                else listOf("envelope", "account", "key"), forwardWrites)
        }
    }

    /** A successful restore adopts the downloaded envelope into local custody. */
    @Test
    fun `a successful restore adopts the downloaded envelope and custody`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        val authority = FakeAuthority(current, currentPreferences)
        val custody = FakeCustody()
        val owner = BackupRestoreOwner(
            ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, custody),
        )
        val adoptedEnvelope = adoptionEnvelope()
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(
                payload,
                BackupRestoreAdoption(adoptedEnvelope, "device-key".toCharArray()),
            ),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val outcome = owner.confirm(prepared.reviewID, false, basis, current)

        assertTrue(outcome is BackupRestoreApplyOutcome.Applied)
        assertEquals(1, custody.adoptCalls)
        assertEquals(adoptedEnvelope, custody.envelope)
        assertEquals("account-A", custody.accountID)
    }

    /** A failed restore puts the previous envelope/key custody back. */
    @Test
    fun `a failed restore rolls the adopted envelope custody back`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val payload = BackupPayloadMapper.toPayload(incoming, currentPreferences)
        val previous = adoptionEnvelope()
        val authority = FakeAuthority(current, currentPreferences).apply { failPreferences = true }
        val custody = FakeCustody().apply { envelope = previous; accountID = "account-A" }
        val owner = BackupRestoreOwner(
            ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, custody),
        )
        val prepared = owner.prepare(
            unlock = BackupRestoreUnlock.Unlocked(
                payload,
                BackupRestoreAdoption(adoptionEnvelope(), "new-key".toCharArray()),
            ),
            method = BackupRecoveryMethod.RECOVERY_CODE,
            currentLibrary = current,
            currentPreferences = currentPreferences,
            basis = basis,
        ) as BackupRestorePreparation.Prepared

        val outcome = owner.confirm(prepared.reviewID, false, basis, current)

        assertTrue(outcome is BackupRestoreApplyOutcome.Refused)
        assertEquals(previous, custody.envelope)
        assertEquals(1, custody.restoreCalls)
    }

    @Test
    fun `an account session revoked during library publication refuses before adoption or retention`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val accounts = MutableAccounts()
        val authority = FakeAuthority(current, currentPreferences).apply {
            beforeReplace = { accounts.binding = BackupAccountBinding(null, false); accounts.generation++ }
        }
        val custody = FakeCustody()
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, custody, accounts))
        val accountBasis = basis.copy(accountGeneration = accounts.generation)
        val prepared = owner.prepare(
            BackupRestoreUnlock.Unlocked(BackupPayloadMapper.toPayload(incoming, currentPreferences),
                BackupRestoreAdoption(adoptionEnvelope(), "device-key".toCharArray())),
            BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, accountBasis,
        ) as BackupRestorePreparation.Prepared

        val result = owner.confirm(prepared.reviewID, false, accountBasis, current)

        assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted), result)
        assertEquals(current, authority.library)
        assertEquals(0, authority.preferenceCalls)
        assertEquals(0, custody.adoptCalls)
    }

    @Test
    fun `session revocation during adoption compensates custody and never rebinds to the next account`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val accounts = MutableAccounts()
        val authority = FakeAuthority(current, currentPreferences)
        val previous = adoptionEnvelope()
        val custody = FakeCustody().apply {
            envelope = previous
            accountID = "account-A"
            sealingKey = "old-device-key".toCharArray()
            afterAdopt = {
                assertEquals("account-A", accountID)
                accounts.binding = BackupAccountBinding("account-B", true)
                accounts.generation++
                liveAccount = "account-B"
            }
        }
        val accountBasis = basis.copy(accountGeneration = accounts.generation)
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, custody, accounts))
        val prepared = owner.prepare(
            BackupRestoreUnlock.Unlocked(BackupPayloadMapper.toPayload(incoming, currentPreferences),
                BackupRestoreAdoption(adoptionEnvelope(), "new-key".toCharArray())),
            BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, accountBasis,
        ) as BackupRestorePreparation.Prepared

        val result = owner.confirm(prepared.reviewID, false, accountBasis, current)

        assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted), result)
        assertEquals(current, authority.library)
        assertEquals(0, authority.preferenceCalls)
        assertEquals(previous, custody.envelope)
        assertEquals("account-A", custody.accountID)
        assertEquals("old-device-key", custody.sealingKey?.concatToString())
    }

    @Test
    fun `a graph rotation before checkpoint refuses even when the library is unchanged`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val authority = FakeAuthority(current, currentPreferences)
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined))
        val prepared = owner.prepare(
            BackupRestoreUnlock.Unlocked(BackupPayloadMapper.toPayload(current, currentPreferences)),
            BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, basis,
        ) as BackupRestorePreparation.Prepared
        // The bridge already sampled basis N; IO confirmation starts after graph N+1 attaches.
        authority.generation = basis.configurationGeneration + 1

        val result = owner.confirm(prepared.reviewID, false, basis, current)

        assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted), result)
        assertEquals(0, authority.replaceCalls)
        assertEquals(0, authority.preferenceCalls)
    }

    @Test
    fun `same account with a new session refuses the captured review without a write`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val accounts = MutableAccounts()
        val accountBasis = basis.copy(accountGeneration = accounts.generation)
        val authority = FakeAuthority(current, currentPreferences)
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, accounts = accounts))
        val prepared = owner.prepare(
            BackupRestoreUnlock.Unlocked(BackupPayloadMapper.toPayload(current, currentPreferences)),
            BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, accountBasis,
        ) as BackupRestorePreparation.Prepared
        accounts.generation++

        val result = owner.confirm(prepared.reviewID, false, accountBasis, current)

        assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted), result)
        assertEquals(0, authority.replaceCalls)
        assertEquals(0, authority.preferenceCalls)
    }

    @Test
    fun `adoption failure occurs before destructive retained preferences are submitted`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val authority = FakeAuthority(current, currentPreferences)
        val custody = FakeCustody().apply { failAdopt = true }
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, custody))
        val disabled = currentPreferences.copy(keepFilteringCounts = false, keepDomainDiagnostics = false,
            keepNetworkActivity = false, keepLavaGuardProgress = false)
        val prepared = owner.prepare(
            BackupRestoreUnlock.Unlocked(BackupPayloadMapper.toPayload(incoming, disabled),
                BackupRestoreAdoption(adoptionEnvelope(), "device-key".toCharArray())),
            BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, basis,
        ) as BackupRestorePreparation.Prepared

        val result = owner.confirm(prepared.reviewID, false, basis, current)

        assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted), result)
        assertEquals(0, authority.preferenceCalls)
        assertEquals(currentPreferences, authority.preferences)
        assertEquals(current, authority.library)
        assertEquals(1, custody.restoreCalls)
    }

    @Test
    fun `a reconciliation rotation after durable retention commit remains applied`() {
        val current = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("old.example"))
        val incoming = library(MilestoneResolverPlan.DEVICE_DNS_ONLY, blocked = setOf("new.example"))
        val authority = FakeAuthority(current, currentPreferences).apply { afterPreferences = { generation = 2 } }
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined))
        val prepared = owner.prepare(
            BackupRestoreUnlock.Unlocked(BackupPayloadMapper.toPayload(incoming, currentPreferences)),
            BackupRecoveryMethod.RECOVERY_CODE, current, currentPreferences, basis,
        ) as BackupRestorePreparation.Prepared

        assertTrue(owner.confirm(prepared.reviewID, false, basis, current) is BackupRestoreApplyOutcome.Applied)
        assertEquals(1, authority.replaceCalls)
        assertEquals(1, authority.preferenceCalls)
    }

    private class MutableAccounts : BackupAccountAuthority {
        var binding = BackupAccountBinding("account-A", true)
        var generation = 7L
        override fun binding() = binding
        override fun generation() = generation
    }

    @Test
    fun `the retention facts map onto the logging authority and back`() {
        val logging = com.lavasec.app.platform.LoggingSettings(
            keepCounts = false,
            keepHistory = true,
            keepNetworkActivity = false,
            keepLavaGuardProgress = true,
        )
        val mapped = logging.toBackupRetainedPreferences(protectionEnabledHint = true)
        assertEquals(false, mapped.keepFilteringCounts)
        assertEquals(true, mapped.keepDomainDiagnostics)
        assertEquals(false, mapped.keepNetworkActivity)
        assertEquals(true, mapped.keepLavaGuardProgress)
        assertEquals(logging, mapped.toLoggingSettings())
    }

    private class FakeCustody : BackupRestoreLocalCustody {
        var envelope: BackupEnvelope? = null
        var accountID: String? = null
        var uploadedAt: Long? = null
        var liveAccount: String? = "account-A"
        var sealingKey: CharArray? = null
        var adoptCalls = 0
        var restoreCalls = 0
        var failAdopt = false
        var afterAdopt: (() -> Unit)? = null
        var throwRead: String? = null
        val returnedKeys = mutableListOf<CharArray>()

        override fun currentEnvelope(): BackupEnvelope? {
            if (throwRead == "envelope") throw IllegalStateException("envelope read failed")
            return envelope
        }

        override fun currentEnvelopeAccountID(): String? {
            if (throwRead == "account-binding") throw IllegalStateException("binding read failed")
            return accountID
        }

        override fun liveAccountID(): String? = liveAccount

        override fun currentUploadedAtEpochSeconds(): Long? {
            if (throwRead == "upload-marker") throw IllegalStateException("receipt read failed")
            return uploadedAt
        }

        override fun restoreUploadMarker(uploadedAt: Long?): Boolean {
            this.uploadedAt = uploadedAt
            return true
        }

        override fun currentSealingKey(): CharArray? = sealingKey?.copyOf()?.also { returnedKeys += it }

        override fun adopt(envelope: BackupEnvelope, deviceSecret: CharArray, accountID: String?): Boolean {
            adoptCalls++
            if (failAdopt) return false
            this.envelope = envelope
            this.accountID = accountID
            this.sealingKey = deviceSecret.copyOf()
            uploadedAt = null
            afterAdopt?.invoke()
            return true
        }

        override fun restore(envelope: BackupEnvelope?, accountID: String?, sealingKey: CharArray?): Boolean {
            restoreCalls++
            this.envelope = envelope
            this.accountID = accountID
            this.sealingKey = sealingKey?.copyOf()
            return true
        }
    }

    private class FakeAuthority(
        var library: FilterLibraryDraft?,
        var preferences: BackupRetainedPreferences?,
    ) : BackupRestoreAuthority {
        var replaceCalls = 0
        var preferenceCalls = 0
        var failPreferences = false
        var failLibraryFromCall = Int.MAX_VALUE
        /** Mutable so a test can simulate a credential-graph rotation mid-transaction. */
        var generation: Long? = 7L
        /** Rotate the credential graph as the library write lands, before the logging write. */
        var rotateOnReplace = false
        /** Simulates a concurrent filter mutation landing between the review and the write. */
        var beforeReplace: (() -> Unit)? = null
        var afterPreferences: (() -> Unit)? = null
        var beforePreferences: (() -> Unit)? = null
        var throwPreferencesRead = false

        override fun currentLibrary(): FilterLibraryDraft? = library

        override fun currentPreferences(): BackupRetainedPreferences? {
            if (throwPreferencesRead) throw IllegalStateException("preferences read failed")
            return preferences
        }

        override fun generation(): Long? = generation

        override suspend fun replaceLibrary(
            library: FilterLibraryDraft,
            expectedLibrary: FilterLibraryDraft?,
        ): Boolean {
            replaceCalls++
            // Enforce the compare-and-replace contract exactly like the production coordinator: a
            // forward write whose expectation no longer matches the live library is superseded.
            beforeReplace?.invoke()
            if (expectedLibrary != null && expectedLibrary != this.library) return false
            if (replaceCalls >= failLibraryFromCall) return false
            this.library = library
            if (rotateOnReplace) generation = 2L
            return true
        }

        override suspend fun applyPreferences(preferences: BackupRetainedPreferences): Boolean {
            preferenceCalls++
            beforePreferences?.invoke()
            if (failPreferences) return false
            this.preferences = preferences
            afterPreferences?.invoke()
            return true
        }
    }

    private class MutableLibraryPort(initial: FilterLibraryDraft) : FilterLibraryPort {
        var library = initial
        var generation = 1L

        override fun currentLibrary(): FilterLibraryDraft = library

        override fun reviewBasis(): com.lavasec.app.product.FilterLibraryReviewBasis =
            com.lavasec.app.product.FilterLibraryReviewBasis(library, generation)

        override suspend fun apply(
            desired: FilterLibraryDraft,
            admission: FeatureLimitAdmission,
        ): FilterLibraryMutationResult {
            library = desired
            generation += 1
            return FilterLibraryMutationResult.Applied(generation)
        }

        override suspend fun applyReviewed(
            desired: FilterLibraryDraft,
            expected: com.lavasec.app.product.FilterLibraryReviewBasis,
            admission: FeatureLimitAdmission,
        ): FilterLibraryMutationResult {
            if (expected.library != library || expected.generation != generation) {
                return FilterLibraryMutationResult.Superseded
            }
            library = desired
            generation += 1
            return FilterLibraryMutationResult.Applied(generation)
        }

        override suspend fun restoreDefaults(
            admission: FeatureLimitAdmission,
        ): FilterLibraryMutationResult =
            FilterLibraryMutationResult.Failed(FilterMutationFailure.COMPILE_FAILED)
    }

    private fun library(plan: MilestoneResolverPlan, blocked: Set<String>): FilterLibraryDraft =
        FilterLibraryDraft(
            profiles = listOf(profile("filter-balanced", "Balanced", blocked = blocked)),
            activeProfileId = "filter-balanced",
            resolverPlan = plan,
        )

    private fun profile(
        id: String,
        name: String,
        blocked: Set<String> = emptySet(),
    ): FilterProfileDraft = FilterProfileDraft(
        id = id,
        name = name,
        protectionLevel = null,
        enabledSourceIds = setOf("blocklistproject-basic"),
        manualRules = ManualDomainRules(allowedDomains = emptySet(), blockedDomains = blocked),
    )
}
