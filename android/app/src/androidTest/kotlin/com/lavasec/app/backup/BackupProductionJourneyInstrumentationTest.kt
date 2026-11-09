package com.lavasec.app.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.backup.BackupConfigurationPayload
import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupPasskeyMaterial
import com.lavasec.app.foundation.backup.BackupPasskeyPrfCapability
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupPayloadMapper
import com.lavasec.app.foundation.backup.BackupRecoveryMethod
import com.lavasec.app.foundation.backup.BackupRestoreApplyOutcome
import com.lavasec.app.foundation.backup.BackupRestoreBasis
import com.lavasec.app.foundation.backup.BackupRestoreOwner
import com.lavasec.app.foundation.backup.BackupRestorePreparation
import com.lavasec.app.foundation.backup.BackupRestoreRefusal
import com.lavasec.app.foundation.backup.BackupRestoreUnlock
import com.lavasec.app.foundation.backup.BackupSetupConsent
import com.lavasec.app.foundation.backup.BackupUnlockOutcome
import com.lavasec.app.foundation.backup.UnavailableBackupPasskeyPrfCapability
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.security.AndroidKeystoreChainedKeyItemStore
import com.lavasec.app.R
import com.lavasec.app.reactnative.LavaReactActivity
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs production backup owners, envelope codecs, credential-protected preferences and Android
 * Keystore on every configured emulator. The remote boundary is an account-bound local test port,
 * and the product-write boundary is controlled by the fixture. No operator account, network
 * service, existing preference record or production Keystore alias is accessed.
 */
@RunWith(AndroidJUnit4::class)
class BackupProductionJourneyInstrumentationTest {
    @Test
    fun nativeSetupRestoreAndReviewPromptsDriveTheProductionBackupJourney(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, LavaReactActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val promptScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            Fixture().use { fixture ->
                val cancelled = promptScope.async { activity.presentBackupSetupPhrase(BackupRecoveryPhraseGenerator { PHRASE.concatToString() }) }
                awaitText(activity.getString(R.string.backup_setup_title))
                clickText(activity.getString(android.R.string.cancel))
                assertNull(withTimeout(10_000L) { cancelled.await() })
                assertNull(fixture.store().loadEnvelope())

                val setupPrompt = promptScope.async { activity.presentBackupSetupPhrase(BackupRecoveryPhraseGenerator { PHRASE.concatToString() }) }
                awaitText(activity.getString(R.string.backup_setup_title))
                clickText(activity.getString(R.string.backup_save))
                awaitText(activity.getString(R.string.backup_setup_confirm_both))
                clickText(activity.getString(R.string.backup_setup_saved_ack))
                clickText(activity.getString(R.string.backup_setup_no_recovery_ack))
                enterText("wrong phrase")
                clickText(activity.getString(R.string.backup_save))
                awaitText(activity.getString(R.string.backup_setup_phrase_mismatch))
                enterText(PHRASE.concatToString())
                clickText(activity.getString(R.string.backup_save))
                val collected = checkNotNull(withTimeout(10_000L) { setupPrompt.await() })
                try {
                    assertTrue(collected.consent.canFinish)
                    assertEquals(BackupSetupResult.Completed(true), fixture.setup().setup(collected.phrase, collected.consent))
                } finally {
                    collected.phrase.fill('\u0000')
                }

                fixture.library = library("native-local.invalid")
                val local = fixture.library
                val restorePhrase = promptScope.async { activity.presentBackupRestorePhrase() }
                awaitText(activity.getString(R.string.backup_restore_title))
                enterText(PHRASE.concatToString())
                clickText(activity.getString(R.string.backup_restore))
                val entered = checkNotNull(withTimeout(10_000L) { restorePhrase.await() })
                val unlocked = try { fixture.unlock().unlock(entered) } finally { entered.fill('\u0000') }
                val owner = fixture.restoreOwner()
                val review = fixture.prepare(owner, unlocked)
                val dismissed = promptScope.async { activity.presentBackupRestoreReview("One filter changes", review.plan.requiresResolverConfirmation) }
                awaitText(activity.getString(R.string.backup_restore_review_title))
                clickText(activity.getString(android.R.string.cancel))
                assertFalse(withTimeout(10_000L) { dismissed.await() })
                assertTrue(owner.discard(review.reviewID))
                assertEquals(local, fixture.library)

                val reviewed = fixture.prepare(owner, fixture.unlock().unlock(PHRASE))
                val confirmed = promptScope.async { activity.presentBackupRestoreReview("One filter changes", reviewed.plan.requiresResolverConfirmation) }
                awaitText(activity.getString(R.string.backup_restore_review_title))
                clickText(activity.getString(R.string.backup_restore))
                assertTrue(withTimeout(10_000L) { confirmed.await() })
                assertTrue(owner.confirm(reviewed.reviewID, true, fixture.basis(), fixture.library) is BackupRestoreApplyOutcome.Applied)
                assertEquals(library("backed-up.invalid"), fixture.library)
            }
        } finally {
            promptScope.cancel()
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun setupReopenReviewedRestoreAndLiveResealRetainUsableKeystoreCustody(): Unit = runBlocking {
        Fixture().use { fixture ->
            val originalLibrary = fixture.library
            val originalPreferences = fixture.logging
            assertEquals(BackupSetupResult.Completed(true), fixture.setup().setup(PHRASE, CONSENT))
            assertEquals(1, fixture.remote.uploads)

            // Fresh objects reload the envelope from Android preferences and unwrap the sealing
            // secret with a fresh AndroidKeyStore handle, instead of using either owner's cache.
            val reopenedStore = fixture.store()
            val reopenedKeys = fixture.keys()
            val envelope = checkNotNull(reopenedStore.loadEnvelope())
            val observingStore = fixture.store()
            assertEquals(envelope, observingStore.loadEnvelope()) // Warm an independent reader.
            assertEquals(ACCOUNT_A, reopenedStore.loadEnvelopeAccountID())
            assertNotNull(reopenedStore.loadUploadedAtEpochSeconds())
            val retained = checkNotNull(reopenedKeys.load())
            try {
                assertTrue(BackupEnvelopeCrypto.unlockOutcome(envelope, "keychain", retained) is BackupUnlockOutcome.Unlocked)
            } finally {
                retained.fill('\u0000')
            }
            val aes = fixture.keyStore().getKey(fixture.alias, null)
            assertNotNull(aes)
            assertNull("AndroidKeyStore AES wrapping keys must remain non-exportable", aes.encoded)

            fixture.library = library("local-only.invalid")
            fixture.logging = LoggingSettings(keepCounts = false, keepHistory = true,
                keepNetworkActivity = false, keepLavaGuardProgress = false)
            val beforeRestore = fixture.library
            val unlocked = fixture.unlock().unlock(PHRASE) as BackupRestoreUnlock.Unlocked
            val restore = fixture.restoreOwner(reopenedStore, reopenedKeys)
            val review = fixture.prepare(restore, unlocked)
            assertEquals("Preparing a review must not mutate the product", beforeRestore, fixture.library)
            assertTrue(restore.confirm(review.reviewID, true, fixture.basis(), fixture.library) is BackupRestoreApplyOutcome.Applied)
            assertEquals(originalLibrary, fixture.library)
            assertEquals(originalPreferences, fixture.logging)
            assertEquals(1, fixture.editingDiscards)
            assertNull("A consumed review cannot expose the transient key", unlocked.adoption!!.withDeviceSecret { it.copyOf() })

            // Restoration rotates device custody but preserves the user's portable phrase slot.
            val adopted = checkNotNull(fixture.store().loadEnvelope())
            assertEquals("An already-warm owner must observe another instance's adoption", adopted, observingStore.loadEnvelope())
            assertTrue(BackupEnvelopeCrypto.unlockOutcome(adopted, "recoveryPhrase", PHRASE) is BackupUnlockOutcome.Unlocked)
            val adoptedSecret = checkNotNull(fixture.keys().load())
            try {
                assertTrue(BackupEnvelopeCrypto.unlockOutcome(adopted, "keychain", adoptedSecret) is BackupUnlockOutcome.Unlocked)
            } finally {
                adoptedSecret.fill('\u0000')
            }
            fixture.library = library("edited-after-restore.invalid")
            assertEquals(BackupCommandResult.Completed, fixture.owner(observingStore).execute(BackupCommand.BackUpNow))
            val latest = fixture.unlock().unlock(PHRASE) as BackupRestoreUnlock.Unlocked
            assertEquals(fixture.library, BackupPayloadMapper.restore(latest.payload).library)
            latest.adoption?.close()
        }
    }

    @Test
    fun cancelledReviewWrongPhraseAndAccountRotationPublishNothing(): Unit = runBlocking {
        Fixture().use { fixture ->
            assertEquals(BackupSetupResult.Completed(true), fixture.setup().setup(PHRASE, CONSENT))
            fixture.library = library("keep-local.invalid")
            val local = fixture.library
            val before = fixture.store().loadEnvelope()
            val restore = fixture.restoreOwner()
            val unlocked = fixture.unlock().unlock(PHRASE) as BackupRestoreUnlock.Unlocked
            val review = fixture.prepare(restore, unlocked)
            assertTrue(restore.discard(review.reviewID))
            assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.NoReview),
                restore.confirm(review.reviewID, true, fixture.basis(), local))
            assertNull(unlocked.adoption!!.withDeviceSecret { it.copyOf() })
            assertEquals(local, fixture.library)
            assertEquals(before, fixture.store().loadEnvelope())
            assertEquals(0, fixture.editingDiscards)

            assertEquals(BackupRestoreUnlock.WrongSecret, fixture.unlock().unlock("wrong secret".toCharArray()))
            fixture.remote.afterFetch = { fixture.accounts.switchTo(ACCOUNT_B) }
            assertEquals(BackupRestoreUnlock.Unavailable, fixture.unlock().unlock(PHRASE))
            assertEquals(local, fixture.library)
            assertEquals(before, fixture.store().loadEnvelope())
        }
    }

    @Test
    fun failedProductPublishRollsBackAndLeavesPreviousCustodyUsableAfterReopen(): Unit = runBlocking {
        Fixture().use { fixture ->
            assertEquals(BackupSetupResult.Completed(true), fixture.setup().setup(PHRASE, CONSENT))
            val envelope = fixture.store().loadEnvelope()
            val key = checkNotNull(fixture.keys().load())
            fixture.library = library("rollback-local.invalid")
            val local = fixture.library
            fixture.failPreferences = true
            val restore = fixture.restoreOwner()
            val unlocked = fixture.unlock().unlock(PHRASE) as BackupRestoreUnlock.Unlocked
            val review = fixture.prepare(restore, unlocked)
            assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted),
                restore.confirm(review.reviewID, true, fixture.basis(), local))
            assertEquals(local, fixture.library)
            assertEquals(envelope, fixture.store().loadEnvelope())
            val reopenedKey = checkNotNull(fixture.keys().load())
            try {
                assertTrue(key.contentEquals(reopenedKey))
                assertTrue(BackupEnvelopeCrypto.unlockOutcome(checkNotNull(envelope), "keychain", reopenedKey) is BackupUnlockOutcome.Unlocked)
            } finally {
                key.fill('\u0000')
                reopenedKey.fill('\u0000')
            }
            assertEquals(0, fixture.editingDiscards)
        }
    }

    @Test
    fun lostKeyCustodyDuringAdoptionReportsRollbackFailureInsteadOfNothingApplied(): Unit = runBlocking {
        Fixture().use { fixture ->
            assertEquals(BackupSetupResult.Completed(true), fixture.setup().setup(PHRASE, CONSENT))
            fixture.library = library("custody-rollback.invalid")
            val local = fixture.library
            val durable = fixture.store()
            val previousEnvelope = durable.loadEnvelope()
            val goodCustody = ProductBackupRestoreCustody(durable, fixture.keys()) { ACCOUNT_A }
            val unavailableKeys = KeyItemBackupSealingKeyStore(AndroidKeystoreChainedKeyItemStore(
                fixture.context, alias = fixture.alias, preferencesName = "custody", keyStoreProvider = { null }))
            val failedCustody = ProductBackupRestoreCustody(durable, unavailableKeys) { ACCOUNT_A }
            val custody = object : BackupRestoreLocalCustody by goodCustody {
                override fun adopt(envelope: BackupEnvelope, deviceSecret: CharArray, accountID: String?) =
                    failedCustody.adopt(envelope, deviceSecret, accountID)
                override fun restore(envelope: BackupEnvelope?, accountID: String?, sealingKey: CharArray?) =
                    failedCustody.restore(envelope, accountID, sealingKey)
            }
            val owner = fixture.restoreOwner(custodyOverride = custody)
            val review = fixture.prepare(owner, fixture.unlock().unlock(PHRASE))
            assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.RollbackFailed),
                owner.confirm(review.reviewID, true, fixture.basis(), local))
            assertEquals(local, fixture.library)
            assertEquals(previousEnvelope, fixture.store().loadEnvelope())
            assertNull("Unacknowledged key restoration must not be called a complete rollback", fixture.keys().load())
            assertEquals(0, fixture.editingDiscards)
        }
    }

    @Test
    fun adoptionRefusalPreservesRetainedPreferencesBeforeTheFinalCommit(): Unit = runBlocking {
        Fixture().use { fixture ->
            assertEquals(BackupSetupResult.Completed(true), fixture.setup().setup(PHRASE, CONSENT))
            fixture.library = library("preference-rollback.invalid")
            fixture.logging = LoggingSettings(keepCounts = false, keepHistory = true,
                keepNetworkActivity = false, keepLavaGuardProgress = false)
            val previousLogging = fixture.logging
            fixture.failPreferencesFromCall = 2
            val good = ProductBackupRestoreCustody(fixture.store(), fixture.keys()) { ACCOUNT_A }
            val refusedAdoption = object : BackupRestoreLocalCustody by good {
                override fun adopt(envelope: BackupEnvelope, deviceSecret: CharArray, accountID: String?) = false
            }
            val owner = fixture.restoreOwner(custodyOverride = refusedAdoption)
            val review = fixture.prepare(owner, fixture.unlock().unlock(PHRASE))
            assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted),
                owner.confirm(review.reviewID, true, fixture.basis(), fixture.library))
            assertEquals(previousLogging, fixture.logging)
            assertEquals(0, fixture.preferenceWriteCount())
            assertEquals(0, fixture.editingDiscards)
        }
    }

    @Test
    fun setupAdmissionStorageFailureAndAccountFenceNeverFabricateUpload(): Unit = runBlocking {
        Fixture().use { fixture ->
            assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_CONSENT),
                fixture.setup().setup(PHRASE, BackupSetupConsent()))
            assertNull(fixture.store().loadEnvelope())

            val failedKeys = object : BackupSealingKeyStore {
                override fun load(): CharArray? = null
                override fun save(key: CharArray): Boolean = false
                override fun clear(): Boolean = fixture.keys().clear()
            }
            assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_LOCAL_WRITE_FAILED),
                fixture.setup(keys = failedKeys).setup(PHRASE, CONSENT))
            assertNull(fixture.store().loadEnvelope())
            assertNull(fixture.keys().load())
            assertEquals(0, fixture.remote.uploads)

            val actual = fixture.store()
            val rotatingStore = object : BackupLocalStore by actual {
                override fun saveEnvelope(envelope: BackupEnvelope): Boolean {
                    val saved = actual.saveEnvelope(envelope)
                    fixture.accounts.switchTo(ACCOUNT_B)
                    return saved
                }
            }
            assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_ACCOUNT_CHANGED),
                fixture.setup(store = rotatingStore).setup(PHRASE, CONSENT))
            assertEquals(ACCOUNT_A, fixture.store().loadEnvelopeAccountID())
            assertEquals(0, fixture.remote.uploads)
            assertEquals(BackupCommandResult.Completed, fixture.owner().execute(BackupCommand.BackUpNow))
            assertEquals("An A-bound envelope may never upload to B", 0, fixture.remote.uploads)
        }
    }

    @Test
    fun uploadFailureKeepsDurableLocalCopyAndAutomaticUploadUsesCurrentConfiguration(): Unit = runBlocking {
        Fixture().use { fixture ->
            fixture.remote.failUpload = true
            assertEquals(BackupSetupResult.Completed(false), fixture.setup().setup(PHRASE, CONSENT))
            assertNotNull(fixture.store().loadEnvelope())
            assertNull(fixture.store().loadUploadedAtEpochSeconds())
            fixture.remote.failUpload = false
            val owner = fixture.owner()
            assertTrue(owner.setAutomaticEnabled(true))
            assertTrue(fixture.store().loadAutomaticEnabled())
            fixture.library = library("automatic-current.invalid")
            val uploaded = CompletableDeferred<Unit>()
            fixture.remote.afterUpload = { uploaded.complete(Unit) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val automatic = BackupAutomaticUploadCoordinator(owner, fixture.accounts, fixture.store(),
                    fixture.store(), { false }, scope, { 19L }, debounceMillis = 20L)
                automatic.onConfigurationChanged()
                withTimeout(15_000L) { uploaded.await() }
                withTimeout(5_000L) { while (owner.snapshot().backingUp) delay(10L) }
                val restored = fixture.unlock().unlock(PHRASE) as BackupRestoreUnlock.Unlocked
                assertEquals(fixture.library, BackupPayloadMapper.restore(restored.payload).library)
                restored.adoption?.close()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun cancelledDownloadAndUnsupportedPasskeyLeaveDurableStateUntouched(): Unit = runBlocking {
        Fixture().use { fixture ->
            assertEquals(BackupSetupResult.Completed(true), fixture.setup().setup(PHRASE, CONSENT))
            val envelope = fixture.store().loadEnvelope()
            fixture.remote.cancelFetch = true
            var cancelled = false
            try {
                fixture.unlock().unlock(PHRASE)
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue("Cancellation must propagate instead of producing a successful restore", cancelled)
            assertEquals(envelope, fixture.store().loadEnvelope())
            // The unsupported-passkey path is exercised with a controlled unavailable capability:
            // with the release RP origin configured, the production capability now depends on a
            // device authenticator fact, so asserting the global default would be environment- and
            // configuration-dependent rather than a property of the restore owner.
            assertFalse(fixture.restoreOwner(passkeyPrfOverride = UnavailableBackupPasskeyPrfCapability)
                .selectMethod(BackupRecoveryMethod.PASSKEY).isUsable)
        }
    }

    @Test
    fun passkeyPrfSeamDecryptsAdoptsAndPreservesEveryPortableSlot(): Unit = runBlocking {
        Fixture().use { fixture ->
            val prf = ByteArray(32) { (it + 1).toByte() }
            val salt = ByteArray(32) { (it + 3).toByte() }
            val credentialId = "emulator-credential"
            val passkeyPayload = BackupConfigurationPayload(resolverPresetID = "device-dns")
            fixture.remote.upload(
                fixture.accounts.generation(),
                BackupEnvelopeCrypto.seal(
                    payload = BackupPayloadJson.encode(passkeyPayload).toByteArray(Charsets.UTF_8),
                    secrets = mapOf("recoveryPhrase" to PHRASE),
                    createdAtEpochSeconds = 1_000,
                    passkey = BackupPasskeyMaterial(prf, salt, credentialId),
                ),
            )

            val unlocked = fixture.unlock().unlockWithPasskey(
                object : BackupPasskeyCeremony {
                    override suspend fun create(salt: ByteArray): BackupPasskeyCeremonyResult =
                        BackupPasskeyCeremonyResult.Failed("unused")

                    override suspend fun assert(credentialId: String, salt: ByteArray): BackupPasskeyCeremonyResult =
                        BackupPasskeyCeremonyResult.Obtained(BackupPasskeyMaterial(prf, salt, credentialId))
                },
            ) as BackupRestoreUnlock.Unlocked

            assertEquals(passkeyPayload.resolverPresetID, unlocked.payload.resolverPresetID)
            val adoption = checkNotNull(unlocked.adoption)
            assertTrue(
                BackupEnvelopeCrypto.unlockOutcome(adoption.envelope, "recoveryPhrase", PHRASE) is BackupUnlockOutcome.Unlocked,
            )
            assertTrue(BackupEnvelopeCrypto.unlockWithPasskeyPrf(adoption.envelope, prf).isNotEmpty())
            adoption.close()
        }
    }

    private class Fixture : AutoCloseable {
        private val namespace = "backup-journey-${UUID.randomUUID()}"
        val context = IsolatedPreferencesContext(InstrumentationRegistry.getInstrumentation().targetContext, namespace)
        val alias = "$namespace.wrapping-key"
        val accounts = TestAccounts()
        val remote = LocalBackupPort(accounts)
        var library = library("backed-up.invalid")
        var logging = LoggingSettings(keepCounts = true, keepHistory = false,
            keepNetworkActivity = true, keepLavaGuardProgress = true)
        var failPreferences = false
        var failPreferencesFromCall = Int.MAX_VALUE
        private var preferenceWrites = 0
        var editingDiscards = 0

        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun store() = AndroidBackupLocalStore(context)
        fun keys() = KeyItemBackupSealingKeyStore(AndroidKeystoreChainedKeyItemStore(
            context, alias = alias, preferencesName = "custody"))
        fun payload() = LibraryBackupPayloadSource({ library }, { logging.toBackupRetainedPreferences(false) },
            { com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger() })
        fun setup(store: BackupLocalStore = store(), keys: BackupSealingKeyStore = keys()) = ProductionBackupSetup(
            payload(), store, accounts, { remote }, { true }, sealingKeys = keys,
        )
        fun unlock() = ProductionBackupRestoreUnlockSource(accounts, { remote }, deviceSecretFactory = ::newBackupDeviceSecret)
        fun owner(localStore: AndroidBackupLocalStore = store()) = localStore.let { local -> BackupOwner(
            cloud = { remote }, accounts = accounts, store = local, deletionStore = local,
            payloadSource = payload(), automaticPreference = local, sealingKeys = keys(),
        ) }
        fun basis() = BackupRestoreBasis(accounts.binding().accountID, 19L, accounts.generation())
        fun preferenceWriteCount() = preferenceWrites
        fun restoreOwner(local: BackupLocalStore = store(), sealingKeys: BackupSealingKeyStore = keys(),
            custodyOverride: BackupRestoreLocalCustody? = null,
            passkeyPrfOverride: BackupPasskeyPrfCapability? = null): BackupRestoreOwner {
            val authority = ProductBackupRestoreAuthority(
                library = { library }, logging = { logging }, currentGeneration = { 19L },
                replaceLibraryWith = { desired, expected ->
                    if (expected != null && expected != library) false else { library = desired; true }
                },
                setLoggingWith = { desired ->
                    preferenceWrites++
                    if (failPreferences || preferenceWrites >= failPreferencesFromCall) false else { logging = desired; true }
                },
                discardEditingWith = { editingDiscards++ },
            )
            return BackupRestoreOwner(ProductionBackupRestorePublisher(authority,
                custody = custodyOverride ?: ProductBackupRestoreCustody(local, sealingKeys) { accounts.binding().accountID },
                accounts = accounts),
                passkeyPrf = passkeyPrfOverride ?: AndroidBackupPasskeyPrfCapability.production(context))
        }
        fun prepare(owner: BackupRestoreOwner, unlock: BackupRestoreUnlock): BackupRestorePreparation.Prepared =
            owner.prepare(unlock, BackupRecoveryMethod.RECOVERY_CODE, library,
                logging.toBackupRetainedPreferences(false), basis()) as BackupRestorePreparation.Prepared

        override fun close() {
            keyStore().deleteEntry(alias)
            context.clear()
        }
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }?.let { return it }
            SystemClock.sleep(50L)
        }
        throw AssertionError("Native backup prompt did not display the expected control")
    }

    private fun clickText(text: String) {
        val node = awaitText(text)
        assertTrue("Native backup control was not clickable", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun enterText(text: String) {
        fun findInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.className?.toString() == "android.widget.EditText") return node
            repeat(node.childCount) { index -> node.getChild(index)?.let { findInput(it) }?.let { return it } }
            return null
        }
        val root = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        val input = checkNotNull(findInput(root))
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        assertTrue("Native backup secret field refused input", input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
    }

    /** The only simulated boundary: canonical ciphertext rows scoped to synthetic account IDs. */
    private class LocalBackupPort(private val accounts: TestAccounts) : BackupCloudClient {
        private val rows = mutableMapOf<String, String>()
        var uploads = 0
        var failUpload = false
        var cancelFetch = false
        var afterFetch: (() -> Unit)? = null
        var afterUpload: (() -> Unit)? = null
        override suspend fun upload(expectedGeneration: Long, envelope: BackupEnvelope) {
            val binding = accounts.binding()
            check(binding.signedIn && accounts.generation() == expectedGeneration)
            if (failUpload) throw IOException("Controlled local port unavailable")
            val id = checkNotNull(binding.accountID)
            rows[id] = BackupEnvelopeJson.upload(envelope, id).toString()
            uploads++
            afterUpload?.invoke()
        }
        override suspend fun fetchLatest(expectedGeneration: Long): BackupEnvelope? {
            check(accounts.generation() == expectedGeneration)
            if (cancelFetch) throw CancellationException("Controlled download cancellation")
            val id = checkNotNull(accounts.binding().accountID)
            val row = rows[id]?.let { BackupEnvelopeJson.decode(JSONObject(it), id) }
            afterFetch?.invoke()
            return row
        }
        override suspend fun fetchMetadata(expectedGeneration: Long): BackupRemoteMetadata? = null
        override suspend fun deleteRemote(expectedGeneration: Long) {
            check(accounts.generation() == expectedGeneration)
            rows.remove(accounts.binding().accountID)
        }
    }

    private class TestAccounts : BackupAccountAuthority {
        private var id = ACCOUNT_A
        private var revision = 7L
        override fun binding() = BackupAccountBinding(id, true)
        override fun generation() = revision
        fun switchTo(account: String) { id = account; revision++ }
    }

    /** Preserves real Android persistence while reserving every preference file for this test. */
    private class IsolatedPreferencesContext(base: Context, private val namespace: String) : ContextWrapper(base) {
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$namespace.$name"
            synchronized(names) { names.add(isolatedName) }
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun clear() { synchronized(names) { names.toList() }.forEach { baseContext.deleteSharedPreferences(it) } }
    }

    private companion object {
        const val ACCOUNT_A = "emulator-account-a"
        const val ACCOUNT_B = "emulator-account-b"
        val PHRASE = "baba keke dede fafa".toCharArray()
        val CONSENT = BackupSetupConsent(savedRecoveryPhrase = true, understandsNoRecovery = true)
        fun library(blocked: String) = FilterLibraryDraft(
            profiles = listOf(FilterProfileDraft("emulator-filter", "Emulator filter", null,
                setOf("blocklistproject-basic"), ManualDomainRules(blockedDomains = setOf(blocked)))),
            activeProfileId = "emulator-filter", resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
        )
    }
}
