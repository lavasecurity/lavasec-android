package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupConfigurationPayload
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupSetupConsent
import com.lavasec.app.foundation.backup.BackupUnlockOutcome
import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupDeletionPhase
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Proves the setup "seal -> store -> upload" path is real: a valid consent seals the portable
 * payload with the recovery secret, persists the local envelope, uploads it through the
 * account-bound client, and every gate (consent, deletion fence, local durability, generation) fails
 * closed without fabricating success. Plain JVM.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProductionBackupSetupTest {

    private val payload = BackupConfigurationPayload(resolverPresetID = "device-dns")
    private val phrase = "baba keke dede fafa".toCharArray()

    private fun setup(
        store: FakeBackupLocalStore = FakeBackupLocalStore(),
        cloud: FakeBackupCloud = FakeBackupCloud(),
        accounts: FakeBackupAccounts = FakeBackupAccounts(),
        deletionBlocks: Boolean = false,
        source: BackupPayloadSource = BackupPayloadSource { payload },
    ): Pair<ProductionBackupSetup, Fixture> {
        val fixture = Fixture(store, cloud, accounts)
        val owner = ProductionBackupSetup(
            payloadSource = source,
            store = store,
            accounts = accounts,
            cloud = { cloud },
            reEnableGate = { !deletionBlocks },
            reEnableCommit = { fixture.reEnableCommits++; true },
            nowEpochSeconds = { 1_000L },
        )
        return owner to fixture
    }

    private data class Fixture(
        val store: FakeBackupLocalStore,
        val cloud: FakeBackupCloud,
        val accounts: FakeBackupAccounts,
    ) {
        var reEnableCommits = 0
    }

    private val consent: BackupSetupConsent
        get() = BackupSetupConsent(savedRecoveryPhrase = true, understandsNoRecovery = true)

    @Test
    fun `setup seals stores and uploads the envelope and the stored copy decrypts`() = runTest {
        val (owner, fixture) = setup()

        val result = owner.setup(phrase, consent)

        assertEquals(BackupSetupResult.Completed(uploaded = true), result)
        assertEquals(1, fixture.cloud.uploadCalls)
        assertEquals(1_000L, fixture.store.uploadedAt)
        val envelope = fixture.store.envelope
        assertNotNull(envelope)
        val outcome = BackupEnvelopeCrypto.unlockOutcome(
            envelope!!,
            ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE,
            phrase,
        )
        assertTrue(outcome is BackupUnlockOutcome.Unlocked)
        val decoded = BackupPayloadJson.decode(
            (outcome as BackupUnlockOutcome.Unlocked).payload.toString(Charsets.UTF_8),
        )
        assertEquals(payload.resolverPresetID, decoded.resolverPresetID)
    }

    @Test
    fun `setup without both acknowledgments is refused and writes nothing`() = runTest {
        val (owner, fixture) = setup()

        val result = owner.setup(phrase, BackupSetupConsent())

        assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_CONSENT), result)
        assertNull(fixture.store.envelope)
        assertEquals(0, fixture.cloud.uploadCalls)
    }

    @Test
    fun `account and graph changes during the native prompt refuse before payload seal or writes`() = runTest {
        for (transition in listOf("account", "same-account-generation", "graph")) {
            val accounts = FakeBackupAccounts()
            val basis = BackupSetupBasis(accounts.binding(), accounts.generation())
            var payloadReads = 0
            val (owner, fixture) = setup(accounts = accounts, source = BackupPayloadSource {
                payloadReads++
                payload
            })
            when (transition) {
                "account" -> accounts.switchTo("account-B")
                "same-account-generation" -> accounts.switchTo("account-A")
            }
            val outcome = owner.setup(phrase, consent, expectedBasis = basis,
                stillAuthorized = { transition != "graph" })
            assertEquals(transition, BackupSetupResult.Rejected(ProductionBackupSetup.REASON_ACCOUNT_CHANGED), outcome)
            assertEquals(transition, 0, payloadReads)
            assertEquals(transition, 0, fixture.store.envelopeSaves)
            assertEquals(transition, 0, fixture.cloud.uploadCalls)
        }
    }

    @Test
    fun `a deletion fence blocks setup`() = runTest {
        val (owner, fixture) = setup(deletionBlocks = true)

        val result = owner.setup(phrase, consent)

        assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_DELETION_PENDING), result)
        assertEquals(0, fixture.store.envelopeSaves)
        // The re-enable tombstone is only cleared by a successful setup, never at admission.
        assertEquals(0, fixture.reEnableCommits)
    }

    @Test
    fun `a durable local commit commits the re-enable and a non-durable one does not`() = runTest {
        val (ok, okFixture) = setup()
        assertEquals(BackupSetupResult.Completed(uploaded = true), ok.setup(phrase, consent))
        assertEquals(1, okFixture.reEnableCommits)

        // A local write that never became durable must not clear the deletion tombstone.
        val failing = FakeBackupLocalStore().apply { failSave = true }
        val (refused, refusedFixture) = setup(store = failing)
        assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_LOCAL_WRITE_FAILED), refused.setup(phrase, consent))
        assertEquals(0, refusedFixture.reEnableCommits)
    }

    @Test
    fun `a local write that is not durable refuses`() = runTest {
        val store = FakeBackupLocalStore().apply { failSave = true }
        val cloud = FakeBackupCloud()
        val (owner, _) = setup(store = store, cloud = cloud)

        val result = owner.setup(phrase, consent)

        assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_LOCAL_WRITE_FAILED), result)
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun `a signed-out device commits locally without sending`() = runTest {
        val (owner, fixture) = setup(accounts = FakeBackupAccounts(accountID = null, signedIn = false))

        val result = owner.setup(phrase, consent)

        assertEquals(BackupSetupResult.Completed(uploaded = false), result)
        assertNotNull(fixture.store.envelope)
        assertEquals(0, fixture.cloud.uploadCalls)
    }

    @Test
    fun `an upload failure after the local commit reports a pending upload not a failure`() = runTest {
        val cloud = FakeBackupCloud().apply { failUpload = true }
        val (owner, fixture) = setup(cloud = cloud)

        val result = owner.setup(phrase, consent)

        assertEquals(BackupSetupResult.Completed(uploaded = false), result)
        assertNotNull(fixture.store.envelope)
        assertEquals(1, fixture.cloud.uploadCalls)
    }

    @Test
    fun `a credential-graph rotation during setup refuses the upload`() = runTest {
        val accounts = FakeBackupAccounts()
        val store = FakeBackupLocalStore().apply { onSave = { accounts.switchTo("account-B") } }
        val (owner, fixture) = setup(store = store, accounts = accounts)

        val result = owner.setup(phrase, consent)

        assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_ACCOUNT_CHANGED), result)
        assertEquals("no ciphertext may be sent after a rotation", 0, fixture.cloud.uploadCalls)
        // The local envelope is account-independent and stays; only the send is refused.
        assertNotNull(fixture.store.envelope)
    }

    @Test
    fun `an unavailable payload source refuses`() = runTest {
        val (owner, _) = setup(source = BackupPayloadSource { throw IllegalStateException("none") })

        val result = owner.setup(phrase, consent)

        assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_PAYLOAD_UNAVAILABLE), result)
    }

    @Test
    fun `an account rotation while capturing the payload refuses before sealing or saving`() = runTest {
        val accounts = FakeBackupAccounts()
        val (owner, fixture) = setup(accounts = accounts, source = BackupPayloadSource {
            accounts.switchTo("account-B")
            payload
        })

        assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_ACCOUNT_CHANGED), owner.setup(phrase, consent))
        assertEquals(0, fixture.store.envelopeSaves)
        assertEquals(0, fixture.cloud.uploadCalls)
    }

    @Test
    fun `temporary device secrets are zeroed on success seal rejection and cancellation`() = runTest {
        for (mode in listOf("success", "invalid-slot", "cancelled")) {
            val deviceSecret = "fresh-device-secret".toCharArray()
            val store = FakeBackupLocalStore()
            val owner = ProductionBackupSetup(
                BackupPayloadSource { payload }, store, FakeBackupAccounts(), { FakeBackupCloud() }, { true },
                deviceSecretFactory = { deviceSecret },
                nowEpochSeconds = { if (mode == "cancelled") throw CancellationException("retired") else 1_000L },
            )
            val result = runCatching {
                owner.setup(phrase, consent, slotKind = if (mode == "invalid-slot") "unsupported" else "recoveryPhrase")
            }
            when (mode) {
                "success" -> assertEquals(BackupSetupResult.Completed(true), result.getOrThrow())
                "invalid-slot" -> assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_SEAL_FAILED), result.getOrThrow())
                "cancelled" -> assertTrue(result.exceptionOrNull() is CancellationException)
            }
            assertTrue("temporary custody must be spent on $mode", deviceSecret.all { it == '\u0000' })
            if (mode != "success") assertEquals(0, store.envelopeSaves)
        }
    }

    @Test
    fun `a throwing durable store still releases temporary device custody`() = runTest {
        val deviceSecret = "fresh-device-secret".toCharArray()
        val store = object : BackupLocalStore by FakeBackupLocalStore() {
            override fun saveEnvelope(envelope: com.lavasec.app.foundation.backup.BackupEnvelope): Boolean =
                throw IllegalStateException("storage unavailable")
        }
        val owner = ProductionBackupSetup(
            BackupPayloadSource { payload }, store, FakeBackupAccounts(), { FakeBackupCloud() }, { true },
            deviceSecretFactory = { deviceSecret }, nowEpochSeconds = { 1_000L },
        )
        assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_LOCAL_WRITE_FAILED), owner.setup(phrase, consent))
        assertTrue(deviceSecret.all { it == '\u0000' })
    }

    @Test
    fun `partial replacement failures preserve the usable prior envelope key binding and marker`() = runTest {
        for (mode in listOf("account", "key", "marker", "reenable")) {
            val oldSecret = "old-device-secret".toCharArray()
            val previous = BackupEnvelopeCrypto.seal(BackupPayloadJson.encode(payload).toByteArray(),
                mapOf("recoveryPhrase" to phrase, "keychain" to oldSecret), 900)
            val durable = FakeBackupLocalStore().apply {
                envelope = previous; envelopeAccountID = TEST_ACCOUNT; uploadedAt = 950
            }
            var failedOnce = false
            val store = object : BackupLocalStore by durable {
                override fun saveEnvelopeAccountID(accountID: String?): Boolean {
                    if (mode == "account" && !failedOnce) { failedOnce = true; return false }
                    return durable.saveEnvelopeAccountID(accountID)
                }
                override fun clearUploadMarker(): Boolean {
                    durable.clearUploadMarker()
                    if (mode == "marker" && !failedOnce) { failedOnce = true; return false }
                    return true
                }
            }
            val capturedKeys = mutableListOf<CharArray>()
            val retained = InMemoryBackupSealingKeyStore(oldSecret.copyOf())
            val keys = object : BackupSealingKeyStore by retained {
                override fun load(): CharArray? = retained.load()?.also { capturedKeys += it }
                override fun save(key: CharArray): Boolean {
                    retained.save(key) // A write can land even when durability is refused.
                    if (mode == "key" && !failedOnce) { failedOnce = true; return false }
                    return true
                }
            }
            val freshSecret = "new-device-secret".toCharArray()
            val cloud = FakeBackupCloud()
            val setup = ProductionBackupSetup(BackupPayloadSource { payload }, store, FakeBackupAccounts(), { cloud }, { true },
                reEnableCommit = { mode != "reenable" }, sealingKeys = keys, deviceSecretFactory = { freshSecret }, nowEpochSeconds = { 1_000L })

            assertEquals(BackupSetupResult.Rejected(if (mode == "reenable") ProductionBackupSetup.REASON_DELETION_PENDING
                else ProductionBackupSetup.REASON_LOCAL_WRITE_FAILED), setup.setup(phrase, consent))
            assertEquals(previous, durable.loadEnvelope())
            assertEquals(TEST_ACCOUNT, durable.loadEnvelopeAccountID())
            assertEquals(950L, durable.loadUploadedAtEpochSeconds())
            assertArrayEquals(oldSecret, retained.load())
            assertTrue(capturedKeys.single().all { it == '\u0000' })
            assertTrue(freshSecret.all { it == '\u0000' })
            assertTrue(BackupEnvelopeCrypto.unlockOutcome(durable.loadEnvelope()!!, "keychain", retained.load()!!)
                is BackupUnlockOutcome.Unlocked)
            assertEquals(0, cloud.uploadCalls)
        }
    }

    @Test
    fun `an unacknowledged rollback has its own refusal and never sends`() = runTest {
        val previous = BackupEnvelopeCrypto.seal("old".toByteArray(), mapOf("recoveryPhrase" to phrase), 900)
        val durable = FakeBackupLocalStore().apply { envelope = previous; failSave = true }
        val cloud = FakeBackupCloud()
        val (setup, _) = setup(store = durable, cloud = cloud)
        assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_LOCAL_ROLLBACK_FAILED), setup.setup(phrase, consent))
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun `disable through a reopened custody owner waits for setup then removes every local component`() = runTest {
        val accounts = FakeBackupAccounts()
        val durable = FakeBackupLocalStore()
        val keys = InMemoryBackupSealingKeyStore()
        val deletion = FakeBackupDeletionStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cloud = object : BackupCloudClient by FakeBackupCloud() {
            override suspend fun upload(expectedGeneration: Long, envelope: BackupEnvelope) {
                entered.complete(Unit)
                release.await()
            }
        }
        val reopened = object : BackupLocalStore by durable {
            override val custodyLockIdentity: Any = durable.custodyLockIdentity
        }
        val deleting = BackupOwner({ cloud }, accounts, reopened, deletion, sealingKeys = keys)
        val setup = ProductionBackupSetup(BackupPayloadSource { payload }, durable, accounts, { cloud },
            reEnableGate = { deleting.admitReEnableForSetup() }, reEnableCommit = { deleting.commitReEnableForSetup() },
            sealingKeys = keys, nowEpochSeconds = { 1_000L })
        val pending = async { setup.setup(phrase, consent) }
        entered.await()
        val disable = async { deleting.execute(BackupCommand.Disable) }
        runCurrent()
        assertFalse(disable.isCompleted)
        assertNotNull(durable.envelope)
        release.complete(Unit)
        assertEquals(BackupSetupResult.Completed(true), pending.await())
        assertEquals(BackupCommandResult.Completed, disable.await())
        assertNull(durable.envelope)
        assertNull(durable.envelopeAccountID)
        assertNull(durable.uploadedAt)
        assertNull(keys.load())
        assertEquals(BackupDeletionPhase.DISABLED, deletion.intent?.phase)
    }

    @Test
    fun `an account switch during upload cannot publish a current-account receipt`() = runTest {
        val accounts = FakeBackupAccounts()
        val store = FakeBackupLocalStore()
        val cloud = object : BackupCloudClient by FakeBackupCloud() {
            override suspend fun upload(expectedGeneration: Long, envelope: BackupEnvelope) { accounts.switchTo("account-B") }
        }
        val setup = ProductionBackupSetup(BackupPayloadSource { payload }, store, accounts, { cloud }, { true },
            nowEpochSeconds = { 1_000L })
        assertEquals(BackupSetupResult.Rejected(ProductionBackupSetup.REASON_ACCOUNT_CHANGED), setup.setup(phrase, consent))
        assertEquals(TEST_ACCOUNT, store.envelopeAccountID)
        assertNotNull(store.envelope)
        assertNull(store.uploadedAt)
    }
}
