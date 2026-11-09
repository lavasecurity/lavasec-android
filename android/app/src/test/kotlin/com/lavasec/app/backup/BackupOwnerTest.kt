package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupDeletionIntent
import com.lavasec.app.foundation.backup.BackupDeletionIntentStore
import com.lavasec.app.foundation.backup.BackupDeletionPhase
import com.lavasec.app.foundation.backup.BackupDeletionRead
import com.lavasec.app.foundation.backup.BackupConfigurationPayload
import com.lavasec.app.foundation.backup.BackupEnablementState
import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupRetainedPreferences
import com.lavasec.app.foundation.backup.BackupUnlockOutcome
import com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger
import com.lavasec.app.foundation.backup.BackupLavaGuardUnlockRecord
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the backup owner. Deliberately no Robolectric and no `org.json`: the owner is
 * platform-free, so the aarch64 work host can execute it. Cloud behavior is a fake client; the
 * binding/generation fences are exercised with a deferred gate that switches the account mid-flight.
 */
class BackupOwnerTest {
    private fun envelope(byteSize: Int = 64): BackupEnvelope =
        BackupEnvelopeCrypto.seal(
            payload = ByteArray(byteSize),
            secrets = mapOf("password" to "secret".toCharArray()),
            createdAtEpochSeconds = 1_000,
            iterations = 8,
        )

    private class FakeAccounts(
        var accountID: String? = ACCOUNT_A,
        var signedIn: Boolean = true,
        var generation: Long = 7,
    ) : BackupAccountAuthority {
        override fun binding() = BackupAccountBinding(accountID, signedIn)
        override fun generation() = generation

        fun switchTo(id: String?) {
            accountID = id
            signedIn = id != null
            generation += 1
        }
    }

    private class FakeCloud : BackupCloudClient {
        var uploadCalls = 0
        var metadataCalls = 0
        var deleteCalls = 0
        var latestCalls = 0
        var metadata: BackupRemoteMetadata? = null
        var latest: BackupEnvelope? = null
        var failUpload = false
        var failMetadata = false
        var failDelete = false
        var failLatest = false
        var uploadGate: CompletableDeferred<Unit>? = null
        var metadataGate: CompletableDeferred<Unit>? = null
        var latestGate: CompletableDeferred<Unit>? = null
        var lastUploaded: BackupEnvelope? = null

        override suspend fun upload(expectedGeneration: Long, envelope: BackupEnvelope) {
            uploadCalls += 1
            lastUploaded = envelope
            uploadGate?.await()
            if (failUpload) throw RuntimeException("upload failed")
        }

        override suspend fun fetchLatest(expectedGeneration: Long): BackupEnvelope? {
            latestCalls += 1
            latestGate?.await()
            if (failLatest) throw RuntimeException("download failed")
            return latest
        }

        override suspend fun fetchMetadata(expectedGeneration: Long): BackupRemoteMetadata? {
            metadataCalls += 1
            metadataGate?.await()
            if (failMetadata) throw RuntimeException("metadata failed")
            return metadata
        }

        override suspend fun deleteRemote(expectedGeneration: Long) {
            deleteCalls += 1
            if (failDelete) throw RuntimeException("delete failed")
        }
    }

    private class FakeLocalStore : BackupLocalStore {
        var envelope: BackupEnvelope? = null
        var envelopeAccountID: String? = null
        var failSaveAccount = false
        var uploadedAt: Long? = null
        var envelopeDeletes = 0
        var markerClears = 0
        var failClearMarker = false
        var afterSave: (() -> Unit)? = null

        override fun loadEnvelope() = envelope
        override fun saveEnvelope(envelope: BackupEnvelope): Boolean {
            this.envelope = envelope
            afterSave?.invoke()
            return true
        }

        override fun loadEnvelopeAccountID(): String? = envelopeAccountID
        override fun saveEnvelopeAccountID(accountID: String?): Boolean {
            if (failSaveAccount) return false
            envelopeAccountID = accountID
            return true
        }

        override fun deleteEnvelope(): Boolean {
            envelopeDeletes += 1
            envelope = null
            envelopeAccountID = null
            return true
        }

        override fun loadUploadedAtEpochSeconds() = uploadedAt
        override fun recordUploadedAtEpochSeconds(value: Long): Boolean {
            uploadedAt = value
            return true
        }

        override fun clearUploadMarker(): Boolean {
            markerClears += 1
            if (failClearMarker) return false
            uploadedAt = null
            return true
        }
    }

    private class FakeDeletionStore : BackupDeletionIntentStore {
        var intent: BackupDeletionIntent? = null
        var unreadable = false
        var writes = 0
        var writeResult = true

        override fun read(): BackupDeletionRead = when {
            unreadable -> BackupDeletionRead.Unreadable
            intent == null -> BackupDeletionRead.Absent
            else -> BackupDeletionRead.Present(checkNotNull(intent))
        }

        override fun write(intent: BackupDeletionIntent): Boolean {
            writes += 1
            if (!writeResult) return false
            this.intent = intent
            return true
        }

        override fun clear(): Boolean {
            intent = null
            return true
        }
    }

    private class Fixture(
        val accounts: FakeAccounts = FakeAccounts(),
        val cloud: FakeCloud = FakeCloud(),
        val localStore: FakeLocalStore = FakeLocalStore(),
        val deletionStore: FakeDeletionStore = FakeDeletionStore(),
        val automaticStore: BackupAutomaticPreferenceStore = InMemoryBackupAutomaticPreferenceStore(),
        payloadSource: BackupPayloadSource? = null,
        sealingKeys: BackupSealingKeyStore = InMemoryBackupSealingKeyStore(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        val owner = BackupOwner(
            cloud = { cloud },
            accounts = accounts,
            store = localStore,
            deletionStore = deletionStore,
            automaticPreference = automaticStore,
            payloadSource = payloadSource,
            sealingKeys = sealingKeys,
            nowEpochSeconds = { 5_000L },
            ioDispatcher = ioDispatcher,
        )
    }

    @Test
    fun `first upload retry preserves the committed envelope and reports only a cloud acknowledgment`() = runTest {
        val fixture = Fixture(automaticStore = InMemoryBackupAutomaticPreferenceStore(false))
        val original = envelope()
        fixture.localStore.envelope = original
        fixture.localStore.envelopeAccountID = ACCOUNT_A
        val basis = BackupSetupBasis(fixture.accounts.binding(), fixture.accounts.generation)
        fixture.cloud.failUpload = true
        assertFalse(fixture.owner.retryFirstUpload(basis) { true })
        assertNull(fixture.localStore.uploadedAt)
        assertEquals(original, fixture.localStore.envelope)
        fixture.cloud.failUpload = false
        assertTrue(fixture.owner.retryFirstUpload(basis) { true })
        assertEquals(original, fixture.cloud.lastUploaded)
        assertEquals(original, fixture.localStore.envelope)
        assertEquals(5_000L, fixture.localStore.uploadedAt)
        assertEquals(2, fixture.cloud.uploadCalls)
    }

    @Test
    fun `first upload cannot borrow a changed account a revoked visit or an unbound envelope`() = runTest {
        for (reason in listOf("account", "revoked", "unbound", "missing")) {
            val fixture = Fixture()
            fixture.localStore.envelope = if (reason == "missing") null else envelope()
            fixture.localStore.envelopeAccountID = if (reason == "unbound") null else ACCOUNT_A
            val basis = BackupSetupBasis(fixture.accounts.binding(), fixture.accounts.generation)
            if (reason == "account") fixture.accounts.switchTo(ACCOUNT_B)
            assertFalse(reason, fixture.owner.retryFirstUpload(basis) { reason != "revoked" })
            assertEquals(reason, 0, fixture.cloud.uploadCalls)
        }
    }

    @Test
    fun `first upload receipt does not follow an account switch during the remote request`() = runTest {
        val fixture = Fixture()
        fixture.localStore.envelope = envelope()
        fixture.localStore.envelopeAccountID = ACCOUNT_A
        val basis = BackupSetupBasis(fixture.accounts.binding(), fixture.accounts.generation)
        val gate = CompletableDeferred<Unit>()
        fixture.cloud.uploadGate = gate
        val sending = async { fixture.owner.retryFirstUpload(basis) { true } }
        runCurrent()
        assertEquals(1, fixture.cloud.uploadCalls)
        fixture.accounts.switchTo(ACCOUNT_B)
        gate.complete(Unit)
        assertFalse(sending.await())
        assertNull(fixture.localStore.uploadedAt)
    }

    @Test
    fun `first upload retry cannot publish success or failure after visit authorization is withdrawn`() = runTest {
        for (fails in listOf(false, true)) {
            val fixture = Fixture()
            val original = envelope()
            fixture.localStore.envelope = original
            fixture.localStore.envelopeAccountID = ACCOUNT_A
            val basis = BackupSetupBasis(fixture.accounts.binding(), fixture.accounts.generation)
            val before = fixture.owner.snapshot()
            val gate = CompletableDeferred<Unit>()
            fixture.cloud.uploadGate = gate
            fixture.cloud.failUpload = fails
            var authorized = true
            val sending = async { fixture.owner.retryFirstUpload(basis) { authorized } }
            runCurrent()
            assertEquals(1, fixture.cloud.uploadCalls)
            authorized = false
            gate.complete(Unit)
            assertFalse(sending.await())
            assertNull(fixture.localStore.uploadedAt)
            assertEquals(original, fixture.localStore.envelope)
            assertEquals(before, fixture.owner.snapshot())
        }
    }

    @Test
    fun `a fresh signed-in device with no envelope is a settled off`() {
        val fixture = Fixture()
        val snapshot = fixture.owner.snapshot()
        assertEquals(BackupEnablementState.OFF, snapshot.enablement.state)
        assertEquals(false, snapshot.enablement.value)
        assertTrue(snapshot.enablement.canEnable)
        assertFalse(snapshot.configured)
        assertEquals(BackupRemoteStatus.NO_BACKUP, snapshot.remoteStatus)
        assertFalse(snapshot.deletionPending)
    }

    @Test
    fun `an unreadable deletion fence is unavailable with a null value`() {
        val fixture = Fixture(deletionStore = FakeDeletionStore().apply { unreadable = true })
        val snapshot = fixture.owner.snapshot()
        assertEquals(BackupEnablementState.UNAVAILABLE, snapshot.enablement.state)
        assertNull(snapshot.enablement.value)
        // Remote evidence is independent of the unreadable local fence: it is still "checking".
        assertEquals(BackupRemoteStatus.CHECKING, snapshot.remoteStatus)
        assertFalse(snapshot.statusUnavailable)
    }

    @Test
    fun `backup now uploads the local envelope and records the upload marker`() = runTest {
        val fixture = Fixture()
        fixture.localStore.envelope = envelope()
        val result = fixture.owner.execute(BackupCommand.BackUpNow)
        assertEquals(BackupCommandResult.Completed, result)
        assertEquals(1, fixture.cloud.uploadCalls)
        assertEquals(5_000L, fixture.localStore.uploadedAt)
        val snapshot = fixture.owner.snapshot()
        assertEquals(BackupEnablementState.ON, snapshot.enablement.state)
        assertTrue(snapshot.configured)
        assertEquals(BackupRemoteStatus.AVAILABLE, snapshot.remoteStatus)
        assertTrue(snapshot.remoteAvailable)
    }

    @Test
    fun `backup now refuses to upload an envelope bound to another account`() = runTest {
        val fixture = Fixture(accounts = FakeAccounts(accountID = "account-B", signedIn = true))
        fixture.localStore.envelope = envelope()
        fixture.localStore.envelopeAccountID = "account-A"

        fixture.owner.execute(BackupCommand.BackUpNow)

        assertEquals("another account's ciphertext must never be uploaded", 0, fixture.cloud.uploadCalls)
        assertNull(fixture.localStore.uploadedAt)
    }

    @Test
    fun `backup now reseals the live payload with the retained device key`() = runTest {
        val deviceKey = "device-key".toCharArray()
        val source = object : BackupPayloadSource {
            override fun currentPayload() = BackupConfigurationPayload(resolverPresetID = "live")
        }
        val sealing = InMemoryBackupSealingKeyStore().apply { save(deviceKey) }
        val setupEnvelope = BackupEnvelopeCrypto.seal(
            payload = BackupPayloadJson.encode(
                BackupConfigurationPayload(resolverPresetID = "setup-time"),
            ).toByteArray(Charsets.UTF_8),
            secrets = mapOf(
                "recoveryPhrase" to "phrase".toCharArray(),
                "keychain" to deviceKey,
            ),
            createdAtEpochSeconds = 1_000,
            iterations = 8,
        )
        val fixture = Fixture(payloadSource = source, sealingKeys = sealing)
        fixture.localStore.envelope = setupEnvelope
        fixture.localStore.envelopeAccountID = ACCOUNT_A

        fixture.owner.execute(BackupCommand.BackUpNow)

        val uploaded = checkNotNull(fixture.cloud.lastUploaded)
        val unlocked = BackupEnvelopeCrypto.unlockOutcome(uploaded, "keychain", deviceKey)
        assertTrue(unlocked is BackupUnlockOutcome.Unlocked)
        val decoded = BackupPayloadJson.decode(
            (unlocked as BackupUnlockOutcome.Unlocked).payload.toString(Charsets.UTF_8),
        )
        assertEquals("the upload must reflect the live payload", "live", decoded.resolverPresetID)
        // The user's own recovery-phrase slot is preserved by the re-seal.
        assertTrue(
            BackupEnvelopeCrypto.unlockOutcome(uploaded, "recoveryPhrase", "phrase".toCharArray())
                is BackupUnlockOutcome.Unlocked,
        )
    }

    @Test
    fun `borrowed device key is zeroed when payload capture or actual JSON serialization fails`() = runTest {
        for (failure in listOf("capture", "serialization")) {
            val borrowed = "retained-device-key".toCharArray()
            var sourceCalls = 0
            val keys = object : BackupSealingKeyStore {
                override fun load() = borrowed
                override fun save(key: CharArray) = error("no replacement is authorized")
                override fun clear() = error("no deletion is authorized")
            }
            val source = BackupPayloadSource {
                sourceCalls++
                if (failure == "capture") error("source unavailable")
                // This real encoder refuses non-finite ledger dates; no injected decoder seam.
                val invalid = BackupConfigurationPayload(resolverPresetID = "device-dns",
                    lavaGuardUnlocks = BackupLavaGuardAchievementLedger(
                        listOf(BackupLavaGuardUnlockRecord("emerald", Double.NaN))))
                assertTrue(runCatching { BackupPayloadJson.encode(invalid) }.exceptionOrNull() is
                    com.lavasec.app.foundation.backup.BackupPayloadFormatException)
                invalid
            }
            val fixture = Fixture(payloadSource = source, sealingKeys = keys,
                ioDispatcher = StandardTestDispatcher(testScheduler))
            val prior = envelope()
            fixture.localStore.envelope = prior
            fixture.localStore.envelopeAccountID = ACCOUNT_A

            assertEquals(BackupCommandResult.Completed, fixture.owner.execute(BackupCommand.BackUpNow))

            assertEquals(failure, 1, sourceCalls)
            assertTrue(failure, borrowed.all { it == '\u0000' })
            assertEquals(failure, prior, fixture.localStore.envelope)
            assertEquals(failure, prior, fixture.cloud.lastUploaded)
        }
    }

    @Test
    fun `disable retains local cleanup pending until device key removal is acknowledged`() = runTest {
        var retained = true
        var acknowledgeClear = false
        var clearCalls = 0
        val keys = object : BackupSealingKeyStore {
            override fun load() = if (retained) "device-key".toCharArray() else null
            override fun save(key: CharArray) = error("disable must not generate a key")
            override fun clear(): Boolean {
                clearCalls++
                if (!acknowledgeClear) return false
                retained = false
                return true
            }
        }
        val fixture = Fixture(sealingKeys = keys)
        fixture.localStore.envelope = envelope()
        fixture.localStore.envelopeAccountID = ACCOUNT_A
        fixture.localStore.uploadedAt = 111L

        fixture.owner.execute(BackupCommand.Disable)

        assertTrue(retained)
        assertEquals(1, clearCalls)
        assertEquals(1, fixture.cloud.deleteCalls)
        assertEquals(BackupDeletionPhase.LOCAL_CLEANUP_PENDING, fixture.deletionStore.intent?.phase)
        assertTrue(fixture.owner.snapshot().needsAttention)
        assertTrue(fixture.owner.snapshot().deletionPending)
        assertEquals(EnglishBackupFailureCopy.copyFor(BackupFailure.LOCAL_CLEANUP_INCOMPLETE),
            fixture.owner.snapshot().detail)
        assertNull(fixture.localStore.envelope)
        assertNull(fixture.localStore.uploadedAt)

        acknowledgeClear = true
        fixture.owner.execute(BackupCommand.Disable)

        assertFalse(retained)
        assertEquals(2, clearCalls)
        assertEquals("the confirmed remote deletion must not be repeated", 1, fixture.cloud.deleteCalls)
        assertEquals(BackupDeletionPhase.DISABLED, fixture.deletionStore.intent?.phase)
        assertFalse(fixture.owner.snapshot().needsAttention)
        assertFalse(fixture.owner.snapshot().deletionPending)
        assertEquals(BackupEnablementState.OFF, fixture.owner.snapshot().enablement.state)
    }

    @Test
    fun `automatic upload waiting for custody refuses acknowledged Off without affecting manual backup`() = runTest {
        var keyLoads = 0
        var payloadReads = 0
        val keys = object : BackupSealingKeyStore {
            override fun load(): CharArray { keyLoads++; return "device-key".toCharArray() }
            override fun save(key: CharArray) = error("upload must not replace key custody")
            override fun clear() = error("upload must not delete key custody")
        }
        val fixture = Fixture(sealingKeys = keys,
            payloadSource = BackupPayloadSource { payloadReads++; BackupConfigurationPayload(resolverPresetID = "device-dns") },
            ioDispatcher = StandardTestDispatcher(testScheduler))
        val prior = envelope()
        fixture.localStore.envelope = prior
        fixture.localStore.envelopeAccountID = ACCOUNT_A
        fixture.localStore.uploadedAt = 111L
        assertTrue(fixture.owner.setAutomaticEnabled(true))
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { fixture.localStore.withCustodyLock { held.complete(Unit); release.await() } }
        held.await()
        val automatic = async { fixture.owner.backUpAutomatically { true } }
        runCurrent()
        assertFalse("automatic upload is actually suspended behind custody", automatic.isCompleted)
        assertTrue("Off was acknowledged before custody admission", fixture.owner.setAutomaticEnabled(false))
        release.complete(Unit)
        holder.await()

        assertEquals(BackupCommandResult.Completed, automatic.await())
        assertEquals(0, keyLoads)
        assertEquals(0, payloadReads)
        assertEquals(0, fixture.cloud.uploadCalls)
        assertEquals(prior, fixture.localStore.envelope)
        assertEquals(111L, fixture.localStore.uploadedAt)
        assertFalse(fixture.owner.snapshot().automatic)
        assertFalse(fixture.owner.snapshot().busy)
        assertEquals(BackupCommandResult.Completed, fixture.owner.execute(BackupCommand.BackUpNow))
        assertEquals("explicit backup is still permitted with automatic Off", 1, fixture.cloud.uploadCalls)
        assertEquals(1, keyLoads)
        assertEquals(1, payloadReads)
    }

    @Test
    fun `automatic admission suspension rechecks acknowledged Off and propagates caller cancellation`() = runTest {
        for (retirement in listOf("automatic-off", "cancelled")) {
            val fixture = Fixture(ioDispatcher = StandardTestDispatcher(testScheduler))
            val prior = envelope()
            fixture.localStore.envelope = prior
            fixture.localStore.envelopeAccountID = ACCOUNT_A
            assertTrue(fixture.owner.setAutomaticEnabled(true))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val automatic = async { fixture.owner.backUpAutomatically {
                entered.complete(Unit)
                release.await()
                true
            } }
            entered.await()
            if (retirement == "automatic-off") {
                assertTrue(fixture.owner.setAutomaticEnabled(false))
                release.complete(Unit)
                assertEquals(BackupCommandResult.Completed, automatic.await())
            } else {
                automatic.cancel()
                automatic.join()
                assertTrue(automatic.isCancelled)
            }
            assertEquals(retirement, 0, fixture.cloud.uploadCalls)
            assertEquals(retirement, prior, fixture.localStore.envelope)
            assertNull(fixture.localStore.uploadedAt)
            assertFalse(fixture.owner.snapshot().busy)
            // Actual cancellation released custody; a subsequent explicit command still completes.
            assertEquals(BackupCommandResult.Completed, fixture.owner.execute(BackupCommand.BackUpNow))
            assertEquals(1, fixture.cloud.uploadCalls)
        }
    }

    @Test
    fun `automatic reseal rechecks preference attempt and account before custody writes`() = runTest {
        for (retirement in listOf("automatic-off", "superseded-attempt", "account-switch")) {
            val secret = "device-key".toCharArray()
            val retained = InMemoryBackupSealingKeyStore(secret)
            val borrowed = mutableListOf<CharArray>()
            val keys = object : BackupSealingKeyStore by retained {
                override fun load() = retained.load()?.also { borrowed += it }
            }
            var currentAttempt = true
            var payloadReads = 0
            lateinit var fixture: Fixture
            fixture = Fixture(sealingKeys = keys, ioDispatcher = StandardTestDispatcher(testScheduler),
                payloadSource = BackupPayloadSource {
                    payloadReads++
                    when (retirement) {
                        "automatic-off" -> assertTrue(fixture.owner.setAutomaticEnabled(false))
                        "superseded-attempt" -> currentAttempt = false
                        "account-switch" -> fixture.accounts.switchTo("account-B")
                    }
                    BackupConfigurationPayload(resolverPresetID = "device-dns", blockedDomains = setOf("new.example"))
                })
            val prior = BackupEnvelopeCrypto.seal(
                BackupPayloadJson.encode(BackupConfigurationPayload(resolverPresetID = "device-dns")).toByteArray(),
                mapOf("keychain" to secret), 1_000, iterations = 8)
            fixture.localStore.envelope = prior
            fixture.localStore.envelopeAccountID = ACCOUNT_A
            fixture.localStore.uploadedAt = 111L
            assertTrue(fixture.owner.setAutomaticEnabled(true))

            assertEquals(BackupCommandResult.Completed, fixture.owner.backUpAutomatically { currentAttempt })

            assertEquals(retirement, 1, payloadReads)
            assertEquals("retirement after CPU work must not replace custody", prior, fixture.localStore.envelope)
            assertEquals(retirement, 111L, fixture.localStore.uploadedAt)
            assertEquals(retirement, 0, fixture.localStore.markerClears)
            assertEquals(retirement, 1, borrowed.size)
            assertTrue(borrowed.single().all { it == '\u0000' })
            assertEquals(retirement, 0, fixture.cloud.uploadCalls)
            assertNull(fixture.cloud.lastUploaded)
            assertFalse(fixture.owner.snapshot().busy)
            secret.fill('\u0000')
        }
    }

    @Test
    fun `automatic ciphertext replacement never retains an old receipt when final send is refused`() = runTest {
        for (retirement in listOf("automatic-off", "superseded-attempt", "account-switch")) {
            val secret = "device-key".toCharArray()
            val retained = InMemoryBackupSealingKeyStore(secret)
            val borrowed = mutableListOf<CharArray>()
            val keys = object : BackupSealingKeyStore by retained {
                override fun load() = retained.load()?.also { borrowed += it }
            }
            val nextPayload = BackupConfigurationPayload(resolverPresetID = "device-dns", blockedDomains = setOf("new.example"))
            val fixture = Fixture(sealingKeys = keys, ioDispatcher = StandardTestDispatcher(testScheduler),
                payloadSource = BackupPayloadSource { nextPayload })
            val prior = BackupEnvelopeCrypto.seal(
                BackupPayloadJson.encode(BackupConfigurationPayload(resolverPresetID = "device-dns")).toByteArray(),
                mapOf("keychain" to secret), 1_000, iterations = 8)
            fixture.localStore.envelope = prior
            fixture.localStore.envelopeAccountID = ACCOUNT_A
            fixture.localStore.uploadedAt = 111L
            var currentAttempt = true
            fixture.localStore.afterSave = {
                assertNull("old receipt is acknowledged absent before replacement is published", fixture.localStore.uploadedAt)
                when (retirement) {
                    "automatic-off" -> assertTrue(fixture.owner.setAutomaticEnabled(false))
                    "superseded-attempt" -> currentAttempt = false
                    "account-switch" -> fixture.accounts.switchTo("account-B")
                }
            }
            assertTrue(fixture.owner.setAutomaticEnabled(true))

            assertEquals(BackupCommandResult.Completed, fixture.owner.backUpAutomatically { currentAttempt })

            val resealed = checkNotNull(fixture.localStore.envelope)
            assertFalse("an actual new ciphertext was acknowledged", prior == resealed)
            val opened = BackupEnvelopeCrypto.unlockOutcome(resealed, "keychain", secret) as BackupUnlockOutcome.Unlocked
            try { assertEquals(nextPayload, BackupPayloadJson.decode(opened.payload.toString(Charsets.UTF_8))) }
            finally { opened.payload.fill(0) }
            assertEquals(retirement, 1, borrowed.size)
            assertTrue(borrowed.single().all { it == '\u0000' })
            assertEquals(retirement, 1, fixture.localStore.markerClears)
            assertNull(fixture.localStore.uploadedAt)
            assertEquals(retirement, 0, fixture.cloud.uploadCalls)
            assertNull(fixture.cloud.lastUploaded)
            assertFalse(fixture.owner.snapshot().busy)
            secret.fill('\u0000')
        }
    }

    @Test
    fun `automatic reseal refuses replacement and send unless prior receipt removal is acknowledged`() = runTest {
        val secret = "device-key".toCharArray()
        val retained = InMemoryBackupSealingKeyStore(secret)
        val borrowed = mutableListOf<CharArray>()
        val keys = object : BackupSealingKeyStore by retained {
            override fun load() = retained.load()?.also { borrowed += it }
        }
        val fixture = Fixture(sealingKeys = keys, ioDispatcher = StandardTestDispatcher(testScheduler),
            payloadSource = BackupPayloadSource {
                BackupConfigurationPayload(resolverPresetID = "device-dns", blockedDomains = setOf("new.example"))
            })
        val prior = BackupEnvelopeCrypto.seal(
            BackupPayloadJson.encode(BackupConfigurationPayload(resolverPresetID = "device-dns")).toByteArray(),
            mapOf("keychain" to secret), 1_000, iterations = 8)
        fixture.localStore.envelope = prior
        fixture.localStore.envelopeAccountID = ACCOUNT_A
        fixture.localStore.uploadedAt = 111L
        fixture.localStore.failClearMarker = true
        fixture.localStore.afterSave = { error("replacement is forbidden after receipt-removal refusal") }
        assertTrue(fixture.owner.setAutomaticEnabled(true))

        assertEquals(BackupCommandResult.Completed, fixture.owner.backUpAutomatically { true })

        assertEquals(prior, fixture.localStore.envelope)
        assertEquals(111L, fixture.localStore.uploadedAt)
        assertEquals(1, fixture.localStore.markerClears)
        assertEquals(1, borrowed.size)
        assertTrue(borrowed.single().all { it == '\u0000' })
        assertEquals(0, fixture.cloud.uploadCalls)
        assertTrue(fixture.owner.snapshot().needsAttention)
        assertEquals(EnglishBackupFailureCopy.copyFor(BackupFailure.UPLOAD_FAILED), fixture.owner.snapshot().detail)
        assertFalse(fixture.owner.snapshot().busy)
        secret.fill('\u0000')
    }

    @Test
    fun `automatic unavailable capture or encoding never refreshes the old ciphertext receipt`() = runTest {
        for (failure in listOf("no-source", "no-key", "key-read-failed", "guardian-unavailable",
            "serialization", "retired-account", "retired-attempt")) {
            val borrowed = "device-key".toCharArray()
            var keyLoads = 0
            var returnedKey = false
            var payloadReads = 0
            var guardianReads = 0
            var currentAttempt = true
            val accounts = FakeAccounts()
            val keys = object : BackupSealingKeyStore {
                override fun load(): CharArray? {
                    keyLoads++
                    if (failure == "no-key") return null
                    if (failure == "key-read-failed") error("key custody unavailable")
                    returnedKey = true
                    return borrowed
                }
                override fun save(key: CharArray) = error("automatic upload cannot replace key custody")
                override fun clear() = error("automatic upload cannot delete key custody")
            }
            val source = when (failure) {
                "no-source" -> null
                // This is the actual production capture composition: a temporarily unavailable
                // Guardian owner must not turn old ciphertext into a freshly acknowledged backup.
                "guardian-unavailable" -> LibraryBackupPayloadSource(
                    library = {
                        payloadReads++
                        FilterLibraryDraft(profiles = listOf(FilterProfileDraft(id = "filter-1",
                            name = "Private", protectionLevel = null, enabledSourceIds = emptySet())),
                            activeProfileId = "filter-1", resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY)
                    },
                    guardianLedger = { guardianReads++; error("portable achievements unavailable") },
                )
                else -> BackupPayloadSource {
                    payloadReads++
                    when (failure) {
                        "retired-account" -> { accounts.switchTo(ACCOUNT_B); error("retired capture") }
                        "retired-attempt" -> { currentAttempt = false; error("superseded capture") }
                    }
                    // The actual JSON encoder refuses this non-finite ledger timestamp.
                    BackupConfigurationPayload(resolverPresetID = "device-dns",
                        lavaGuardUnlocks = BackupLavaGuardAchievementLedger(
                            listOf(BackupLavaGuardUnlockRecord("emerald", Double.NaN))))
                }
            }
            val prior = BackupEnvelopeCrypto.seal(
                BackupPayloadJson.encode(BackupConfigurationPayload(resolverPresetID = "device-dns",
                    blockedDomains = setOf("prior.example"))).toByteArray(),
                mapOf("keychain" to borrowed), 1_000, iterations = 8)
            val fixture = Fixture(accounts = accounts, payloadSource = source, sealingKeys = keys,
                ioDispatcher = StandardTestDispatcher(testScheduler))
            fixture.localStore.envelope = prior
            fixture.localStore.envelopeAccountID = ACCOUNT_A
            fixture.localStore.uploadedAt = 111L
            fixture.localStore.afterSave = { error("failed automatic reseal must not replace the envelope") }
            assertTrue(fixture.owner.setAutomaticEnabled(true))
            try {
                assertEquals(BackupCommandResult.Completed, fixture.owner.backUpAutomatically { currentAttempt })

                assertEquals(failure, prior, fixture.localStore.envelope)
                assertEquals(failure, ACCOUNT_A, fixture.localStore.envelopeAccountID)
                assertEquals(failure, 111L, fixture.localStore.uploadedAt)
                assertEquals(failure, 0, fixture.localStore.markerClears)
                assertEquals(failure, 0, fixture.cloud.uploadCalls)
                assertNull(fixture.cloud.lastUploaded)
                assertEquals(failure, if (failure == "no-source") 0 else 1, keyLoads)
                assertEquals(failure, if (failure in listOf("no-source", "no-key", "key-read-failed")) 0 else 1,
                    payloadReads)
                assertEquals(failure, if (failure == "guardian-unavailable") 1 else 0, guardianReads)
                if (returnedKey) assertTrue("$failure clears the exact borrowed key", borrowed.all { it == '\u0000' })
                val snapshot = fixture.owner.snapshot()
                val retired = failure in listOf("retired-account", "retired-attempt")
                assertEquals(failure, !retired, snapshot.needsAttention)
                assertEquals(failure, if (retired) null else EnglishBackupFailureCopy.copyFor(BackupFailure.UPLOAD_FAILED),
                    snapshot.detail)
                assertFalse(snapshot.busy)
            } finally { borrowed.fill('\u0000') }
        }
    }

    @Test
    fun `upload waits for custody adoption and reseals with the adopted envelope key`() = runTest {
        val oldKey = "old-device-key".toCharArray()
        val newKey = "new-device-key".toCharArray()
        val keys = InMemoryBackupSealingKeyStore().apply { save(oldKey) }
        val fixture = Fixture(payloadSource = { BackupConfigurationPayload(resolverPresetID = "restored-live") }, sealingKeys = keys)
        fixture.localStore.envelope = envelope()
        fixture.localStore.envelopeAccountID = ACCOUNT_A
        val restored = BackupEnvelopeCrypto.seal(
            BackupPayloadJson.encode(BackupConfigurationPayload(resolverPresetID = "restored")).toByteArray(),
            mapOf("keychain" to newKey, "recoveryPhrase" to "restored-phrase".toCharArray()),
            createdAtEpochSeconds = 2_000, iterations = 8,
        )
        val custody = ProductBackupRestoreCustody(fixture.localStore, keys) { ACCOUNT_A }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adoption = async {
            custody.withExclusiveAccess {
                entered.complete(Unit)
                release.await()
                assertTrue(custody.adopt(restored, newKey, ACCOUNT_A))
            }
        }
        entered.await()
        val upload = async { fixture.owner.execute(BackupCommand.BackUpNow) }
        kotlinx.coroutines.yield()
        assertEquals(0, fixture.cloud.uploadCalls)
        release.complete(Unit)
        adoption.await()
        upload.await()

        val uploaded = checkNotNull(fixture.cloud.lastUploaded)
        val unlocked = BackupEnvelopeCrypto.unlockOutcome(uploaded, "keychain", newKey) as BackupUnlockOutcome.Unlocked
        assertEquals("restored-live", BackupPayloadJson.decode(unlocked.payload.toString(Charsets.UTF_8)).resolverPresetID)
        assertEquals(uploaded, fixture.localStore.envelope)
        assertTrue(BackupEnvelopeCrypto.unlockOutcome(uploaded, "recoveryPhrase", "restored-phrase".toCharArray())
            is BackupUnlockOutcome.Unlocked)
        oldKey.fill('\u0000'); newKey.fill('\u0000'); unlocked.payload.fill(0)
    }

    @Test
    fun `custody adoption waits until an already running upload has finished`() = runTest {
        val fixture = Fixture()
        fixture.localStore.envelope = envelope()
        fixture.localStore.envelopeAccountID = ACCOUNT_A
        val gate = CompletableDeferred<Unit>()
        fixture.cloud.uploadGate = gate
        val upload = async { fixture.owner.execute(BackupCommand.BackUpNow) }
        withTimeout(5_000) { while (fixture.cloud.uploadCalls == 0) kotlinx.coroutines.yield() }
        val restored = envelope(128)
        val keys = InMemoryBackupSealingKeyStore()
        val custody = ProductBackupRestoreCustody(fixture.localStore, keys) { ACCOUNT_A }
        val adoption = async { custody.withExclusiveAccess { custody.adopt(restored, "new-key".toCharArray(), ACCOUNT_A) } }
        kotlinx.coroutines.yield()
        assertFalse(adoption.isCompleted)
        gate.complete(Unit)
        upload.await()
        assertTrue(adoption.await())
        assertEquals(restored, fixture.localStore.envelope)
        assertEquals("new-key", keys.load()?.concatToString())
    }

    @Test
    fun `disable waits for an active upload so deletion cannot be resurrected`() = runTest {
        val fixture = Fixture()
        fixture.localStore.envelope = envelope()
        fixture.localStore.envelopeAccountID = ACCOUNT_A
        val gate = CompletableDeferred<Unit>()
        fixture.cloud.uploadGate = gate
        val upload = async { fixture.owner.execute(BackupCommand.BackUpNow) }
        withTimeout(5_000) { while (fixture.cloud.uploadCalls == 0) kotlinx.coroutines.yield() }
        val disable = async { fixture.owner.execute(BackupCommand.Disable) }
        kotlinx.coroutines.yield()
        assertFalse(disable.isCompleted)
        gate.complete(Unit)
        upload.await()
        disable.await()
        assertNull(fixture.localStore.envelope)
        assertEquals(1, fixture.cloud.deleteCalls)
        assertEquals(BackupDeletionPhase.DISABLED, fixture.deletionStore.intent?.phase)
    }

    @Test
    fun `backup now with no envelope never contacts the cloud`() = runTest {
        val fixture = Fixture()
        fixture.owner.execute(BackupCommand.BackUpNow)
        assertEquals(0, fixture.cloud.uploadCalls)
        assertNull(fixture.localStore.uploadedAt)
    }

    @Test
    fun `a signed-out device never sends the upload`() = runTest {
        val fixture = Fixture(accounts = FakeAccounts(accountID = null, signedIn = false))
        fixture.localStore.envelope = envelope()
        fixture.owner.execute(BackupCommand.BackUpNow)
        assertEquals(0, fixture.cloud.uploadCalls)
    }

    @Test
    fun `an account switch before the upload response suppresses the publication`() = runTest {
        val fixture = Fixture()
        fixture.localStore.envelope = envelope()
        val gate = CompletableDeferred<Unit>()
        fixture.cloud.uploadGate = gate
        val pending = async { fixture.owner.execute(BackupCommand.BackUpNow) }
        withTimeout(5_000) {
            while (fixture.cloud.uploadCalls == 0) kotlinx.coroutines.yield()
        }
        fixture.accounts.switchTo(ACCOUNT_B)
        gate.complete(Unit)
        assertEquals(BackupCommandResult.Completed, pending.await())
        assertNull(fixture.localStore.uploadedAt)
        assertFalse(fixture.owner.snapshot().remoteAvailable)
    }

    @Test
    fun `refresh publishes remote presence and absence`() = runTest {
        val fixture = Fixture()
        fixture.localStore.envelope = envelope()
        fixture.cloud.metadata = BackupRemoteMetadata(ACCOUNT_A, 42L)
        fixture.owner.execute(BackupCommand.Refresh)
        assertEquals(1, fixture.cloud.metadataCalls)
        assertEquals(BackupRemoteStatus.AVAILABLE, fixture.owner.snapshot().remoteStatus)
        assertEquals(42L, fixture.owner.snapshot().remoteUploadedAtEpochSeconds)

        fixture.cloud.metadata = null
        fixture.owner.execute(BackupCommand.Refresh)
        assertEquals(BackupRemoteStatus.NO_BACKUP, fixture.owner.snapshot().remoteStatus)
        assertFalse(fixture.owner.snapshot().remoteAvailable)
    }

    @Test
    fun `refresh never runs while a deletion fence is present`() = runTest {
        val fixture = Fixture(
            deletionStore = FakeDeletionStore().apply {
                intent = BackupDeletionIntent.create(ACCOUNT_A, BackupDeletionPhase.REMOTE_PENDING)
            },
        )
        fixture.localStore.envelope = envelope()
        fixture.owner.execute(BackupCommand.Refresh)
        assertEquals(0, fixture.cloud.metadataCalls)
    }

    @Test
    fun `disable writes the fence before the remote delete then tears down locally`() = runTest {
        val fixture = Fixture()
        fixture.localStore.envelope = envelope()
        fixture.owner.execute(BackupCommand.Disable)
        assertEquals(1, fixture.cloud.deleteCalls)
        assertEquals(1, fixture.localStore.envelopeDeletes)
        assertEquals(1, fixture.localStore.markerClears)
        val intent = checkNotNull(fixture.deletionStore.intent)
        assertEquals(BackupDeletionPhase.DISABLED, intent.phase)
        val snapshot = fixture.owner.snapshot()
        assertEquals(false, snapshot.enablement.value)
        assertFalse(snapshot.deletionPending)
        assertFalse(snapshot.configured)
    }

    @Test
    fun `an unconfirmed remote delete leaves a nullable pending deletion`() = runTest {
        val fixture = Fixture()
        fixture.localStore.envelope = envelope()
        fixture.cloud.failDelete = true
        fixture.owner.execute(BackupCommand.Disable)
        assertEquals(BackupDeletionPhase.REMOTE_PENDING, checkNotNull(fixture.deletionStore.intent).phase)
        assertEquals(0, fixture.localStore.envelopeDeletes)
        val snapshot = fixture.owner.snapshot()
        assertEquals(BackupEnablementState.DELETION_PENDING, snapshot.enablement.state)
        assertNull(snapshot.enablement.value)
        assertTrue(snapshot.deletionPending)
        assertTrue(snapshot.needsAttention)
    }

    @Test
    fun `a refused fence write surfaces a failure and no remote delete`() = runTest {
        val fixture = Fixture()
        fixture.localStore.envelope = envelope()
        fixture.deletionStore.writeResult = false
        fixture.owner.execute(BackupCommand.Disable)
        assertEquals(0, fixture.cloud.deleteCalls)
        assertNull(fixture.deletionStore.intent)
        assertEquals(BackupEnablementState.ON, fixture.owner.snapshot().enablement.state)
    }

    @Test
    fun `disable without an account and not proven local-only is refused`() = runTest {
        val fixture = Fixture(accounts = FakeAccounts(accountID = null, signedIn = false))
        fixture.localStore.envelope = envelope()
        fixture.owner.execute(BackupCommand.Disable)
        assertNull(fixture.deletionStore.intent)
        assertEquals(0, fixture.cloud.deleteCalls)
    }

    @Test
    fun `delete removes the remote copy and keeps the local configuration`() = runTest {
        val fixture = Fixture()
        fixture.localStore.envelope = envelope()
        fixture.localStore.uploadedAt = 111L
        fixture.owner.execute(BackupCommand.Delete)
        assertEquals(1, fixture.cloud.deleteCalls)
        assertEquals(1, fixture.localStore.markerClears)
        assertEquals(0, fixture.localStore.envelopeDeletes)
        val snapshot = fixture.owner.snapshot()
        assertTrue(snapshot.configured)
        assertEquals(BackupEnablementState.ON, snapshot.enablement.state)
        assertEquals(BackupRemoteStatus.NO_BACKUP, snapshot.remoteStatus)
    }

    @Test
    fun `delete is a no-op while a deletion fence exists`() = runTest {
        val fixture = Fixture(
            deletionStore = FakeDeletionStore().apply {
                intent = BackupDeletionIntent.create(ACCOUNT_A, BackupDeletionPhase.REMOTE_PENDING)
            },
        )
        fixture.localStore.envelope = envelope()
        fixture.owner.execute(BackupCommand.Delete)
        assertEquals(0, fixture.cloud.deleteCalls)
    }

    @Test
    fun `admit re-enable checks a completed disabled fence and clears it only on commit`() {
        // A completed `disabled` fence is the canonical re-enable case: setup may proceed, but the
        // tombstone must survive admission so a later setup failure cannot drop the user's Off intent.
        val completed = Fixture(
            deletionStore = FakeDeletionStore().apply {
                intent = BackupDeletionIntent.create(ACCOUNT_A, BackupDeletionPhase.DISABLED)
            },
        )
        completed.localStore.envelope = envelope()
        assertFalse(completed.owner.snapshot().configured)
        assertTrue(completed.owner.admitReEnableForSetup())
        // Admission alone must not clear the fence; the Off intent remains.
        assertNotNull(completed.deletionStore.intent)
        assertFalse(completed.owner.snapshot().automatic)
        // Only the durable setup commit clears it, after which the envelope configures backup again.
        assertTrue(completed.owner.commitReEnableForSetup())
        assertNull(completed.deletionStore.intent)
        assertTrue(completed.owner.snapshot().configured)

        // An unfinished fence stays blocked and is not cleared by admission or commit.
        val pending = Fixture(
            deletionStore = FakeDeletionStore().apply {
                intent = BackupDeletionIntent.create(ACCOUNT_A, BackupDeletionPhase.REMOTE_PENDING)
            },
        )
        assertFalse(pending.owner.admitReEnableForSetup())
        assertFalse(pending.owner.commitReEnableForSetup())
        assertNotNull(pending.deletionStore.intent)

        // An unreadable fence fails closed and is never cleared.
        val unreadable = Fixture(deletionStore = FakeDeletionStore().apply { unreadable = true })
        assertFalse(unreadable.owner.admitReEnableForSetup())
        assertFalse(unreadable.owner.commitReEnableForSetup())
    }

    @Test
    fun `automatic upload is off by default and a committed enable is projected`() {
        val fixture = Fixture()
        assertFalse(fixture.owner.snapshot().automatic)
        assertTrue(fixture.owner.setAutomaticEnabled(true))
        assertTrue(fixture.automaticStore.loadAutomaticEnabled())
        assertTrue(fixture.owner.snapshot().automatic)
        assertTrue(fixture.owner.setAutomaticEnabled(false))
        assertFalse(fixture.owner.snapshot().automatic)
    }

    @Test
    fun `a deletion fence refuses an automatic enable but still allows disabling`() {
        val fixture = Fixture(
            deletionStore = FakeDeletionStore().apply {
                intent = BackupDeletionIntent.create(ACCOUNT_A, BackupDeletionPhase.REMOTE_PENDING)
            },
            automaticStore = InMemoryBackupAutomaticPreferenceStore(initial = true),
        )
        // Enabling is refused while the fence blocks writers...
        assertFalse(fixture.owner.setAutomaticEnabled(true))
        // ...and the pending deletion keeps the projection off even though the store says true.
        assertFalse(fixture.owner.snapshot().automatic)
        // Disabling always commits, so an already-sticky preference can be cleared.
        assertTrue(fixture.owner.setAutomaticEnabled(false))
        assertFalse(fixture.automaticStore.loadAutomaticEnabled())
    }

    @Test
    fun `an automatic preference store that does not acknowledge the write is an honest failure`() {
        val failing = object : BackupAutomaticPreferenceStore {
            override fun loadAutomaticEnabled(): Boolean = false
            override fun saveAutomaticEnabled(enabled: Boolean): Boolean = false
        }
        val fixture = Fixture(automaticStore = failing)
        assertFalse(fixture.owner.setAutomaticEnabled(true))
        assertFalse(fixture.owner.snapshot().automatic)
    }

    @Test
    fun `payload source composes the canonical mapper over a live library`() {
        val profile = FilterProfileDraft(
            id = "filter-1",
            name = "Private",
            protectionLevel = null,
            enabledSourceIds = setOf("source-1"),
            manualRules = ManualDomainRules(
                allowedDomains = setOf("allow.example"),
                blockedDomains = setOf("block.example"),
            ),
        )
        val source = LibraryBackupPayloadSource(
            library = {
                FilterLibraryDraft(
                    profiles = listOf(profile),
                    activeProfileId = "filter-1",
                    resolverPlan = MilestoneResolverPlan.DEVICE_DNS_ONLY,
                )
            },
            preferences = { BackupRetainedPreferences(keepDomainDiagnostics = true) },
            guardianLedger = { com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger() },
        )
        val payload = source.currentPayload()
        assertEquals(setOf("source-1"), payload.enabledBlocklistIDs)
        assertEquals(setOf("block.example"), payload.blockedDomains)
        assertEquals("filter-1", payload.filterLibrary?.activeFilterID)
        assertTrue(payload.keepDomainDiagnostics)
    }

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
    }
}
