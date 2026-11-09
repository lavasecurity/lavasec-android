package com.lavasec.app.backup

import com.lavasec.app.data.GuardianProgressStore
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger
import com.lavasec.app.foundation.backup.BackupLavaGuardUnlockRecord
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupPayloadMapper
import com.lavasec.app.foundation.backup.BackupRecoveryMethod
import com.lavasec.app.foundation.backup.BackupRestoreApplyOutcome
import com.lavasec.app.foundation.backup.BackupRestoreBasis
import com.lavasec.app.foundation.backup.BackupRestoreOwner
import com.lavasec.app.foundation.backup.BackupRestorePreparation
import com.lavasec.app.foundation.backup.BackupRetainedPreferences
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.LoggingSettings
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real durable ledger -> portable capture -> seal/download/decrypt -> reviewed publisher -> reopen. */
@RunWith(RobolectricTestRunner::class)
class BackupGuardianLedgerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val library = FilterLibraryDraft(listOf(FilterProfileDraft("filter", "Saved", null, emptySet(),
        ManualDomainRules())), "filter", MilestoneResolverPlan.DEVICE_DNS_ONLY)

    @Test fun `downloaded portable achievements replace ledger while local usage stays local through reopen`() = runTest {
        val sourceFile = File(temporary.root, "source.json")
        val sourceStore = GuardianProgressStore(sourceFile)
        val imported = BackupLavaGuardAchievementLedger(listOf(BackupLavaGuardUnlockRecord("emerald", 30.0),
            BackupLavaGuardUnlockRecord("emerald", 10.0), BackupLavaGuardUnlockRecord("futureGuard", 20.0)))
        assertTrue(sourceStore.replaceLedger(imported, sourceStore.captureLedger()))
        val capture = LibraryBackupPayloadSource({ library }, guardianLedger = sourceStore::captureLedger)
        val payload = capture.currentPayload()
        assertEquals(listOf("emerald", "futureGuard"), payload.lavaGuardUnlocks.records.map { it.guardID })
        val json = BackupPayloadJson.encode(payload)
        assertFalse(json.contains("usageByDay"))
        val phrase = "baba keke dede fafa".toCharArray()
        val envelope = BackupEnvelopeCrypto.seal(json.toByteArray(), mapOf("recoveryPhrase" to phrase),
            1_000, iterations = 1_000)
        val cloud = FakeBackupCloud().apply { latest = envelope }
        val unlock = ProductionBackupRestoreUnlockSource(FakeBackupAccounts(), { cloud }).unlock(phrase)

        var tick = 0L
        val targetFile = File(temporary.root, "target.json")
        val targetStore = GuardianProgressStore(targetFile, { tick }, { ZoneOffset.UTC })
        val start = 1_700_000_000_000L
        targetStore.observe(true, start, true)
        tick = 600_000
        targetStore.observe(false, start + tick, true)
        val prior = BackupLavaGuardAchievementLedger(listOf(BackupLavaGuardUnlockRecord("emberObsidian", 5.0)))
        assertTrue(targetStore.replaceLedger(prior, targetStore.captureLedger()))
        val usageBefore = targetStore.state.value.usageByDay
        var liveLibrary = library
        var logging = LoggingSettings()
        val authority = ProductBackupRestoreAuthority({ liveLibrary }, { logging }, { 7L },
            replaceLibraryWith = { value, expected ->
                if (expected != liveLibrary) false else { liveLibrary = value; true }
            }, setLoggingWith = { logging = it; true })
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, guardian = targetStore))
        val basis = BackupRestoreBasis(TEST_ACCOUNT, 7, guardianLedger = targetStore.captureLedger())
        val preparation = owner.prepare(unlock, BackupRecoveryMethod.RECOVERY_CODE, library,
            logging.toBackupRetainedPreferences(false), basis) as BackupRestorePreparation.Prepared
        assertEquals(payload.lavaGuardUnlocks, preparation.plan.restored.lavaGuardUnlocks)
        assertTrue(owner.confirm(preparation.reviewID, false, basis, library) is BackupRestoreApplyOutcome.Applied)
        val reopened = GuardianProgressStore(targetFile)
        assertEquals(payload.lavaGuardUnlocks, reopened.captureLedger())
        assertEquals(usageBefore, reopened.state.value.usageByDay)
        assertEquals(1, reopened.state.value.qualifiedUsageDays)
        assertFalse(reopened.captureLedger().records.any { it.guardID == "emberObsidian" })
        assertTrue(reopened.clearUsage())
        assertEquals(payload.lavaGuardUnlocks, GuardianProgressStore(targetFile).captureLedger())
        assertTrue(GuardianProgressStore(targetFile).state.value.usageByDay.isEmpty())
        assertEquals(1, cloud.latestCalls)
    }

    @Test fun `unavailable durable achievement capture cannot emit an empty backup`() {
        val file = File(temporary.root, "corrupt.json").apply { writeText("invalid") }
        val unavailable = GuardianProgressStore(file)
        val capture = LibraryBackupPayloadSource({ library }, guardianLedger = unavailable::captureLedger)
        assertThrows(IllegalStateException::class.java) { capture.currentPayload() }
        assertEquals("invalid", file.readText())
    }

    @Test fun `discarded review never adopts or clears portable or local achievements`() {
        val file = File(temporary.root, "retained.json")
        val store = GuardianProgressStore(file)
        val prior = BackupLavaGuardAchievementLedger(listOf(BackupLavaGuardUnlockRecord("emberObsidian", 5.0)))
        assertTrue(store.replaceLedger(prior, store.captureLedger()))
        val authority = ProductBackupRestoreAuthority({ library }, { LoggingSettings() }, { 7L },
            replaceLibraryWith = { _, _ -> error("discard must never publish") },
            setLoggingWith = { error("discard must never publish") })
        val owner = BackupRestoreOwner(ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined, guardian = store))
        val payload = BackupPayloadMapper.toPayload(library, lavaGuardUnlocks = BackupLavaGuardAchievementLedger(
            listOf(BackupLavaGuardUnlockRecord("emerald", 10.0))))
        val preparation = owner.prepare(com.lavasec.app.foundation.backup.BackupRestoreUnlock.Unlocked(payload),
            BackupRecoveryMethod.RECOVERY_CODE, library, BackupRetainedPreferences(),
            BackupRestoreBasis(TEST_ACCOUNT, 7, guardianLedger = prior)) as BackupRestorePreparation.Prepared
        assertTrue(owner.discard(preparation.reviewID))
        assertEquals(prior, GuardianProgressStore(file).captureLedger())
    }
}
