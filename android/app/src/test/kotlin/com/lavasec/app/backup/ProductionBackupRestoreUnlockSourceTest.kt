package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupConfigurationPayload
import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupSetupConsent
import com.lavasec.app.foundation.backup.BackupRestoreUnlock
import com.lavasec.app.foundation.backup.BackupUnlockOutcome
import com.lavasec.app.foundation.backup.BackupPasskeyMaterial
import com.lavasec.app.foundation.backup.BackupPasskeyPrfCapability
import com.lavasec.app.foundation.backup.BackupPayloadMapper
import com.lavasec.app.foundation.backup.BackupRecoveryMethod
import com.lavasec.app.foundation.backup.BackupRestoreApplyOutcome
import com.lavasec.app.foundation.backup.BackupRestoreBasis
import com.lavasec.app.foundation.backup.BackupRestoreOwner
import com.lavasec.app.foundation.backup.BackupRestorePreparation
import com.lavasec.app.foundation.backup.BackupRetainedPreferences
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves production restore decrypts exact account-bound local custody for This Device and the
 * remote envelope for portable recovery, mapping failures to the owner's typed vocabulary. Plain JVM.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProductionBackupRestoreUnlockSourceTest {
    @Test
    fun `production setup retained key opens local custody without downloading another device envelope`() = runTest {
        val accounts = FakeBackupAccounts()
        val store = FakeBackupLocalStore()
        val keys = InMemoryBackupSealingKeyStore()
        val cloud = FakeBackupCloud()
        val setup = ProductionBackupSetup(BackupPayloadSource { payload }, store, accounts, { cloud },
            reEnableGate = { true }, sealingKeys = keys, deviceSecretFactory = { "device-key".toCharArray() })
        assertEquals(BackupSetupResult.Completed(true), setup.setup(phrase,
            BackupSetupConsent(savedRecoveryPhrase = true, understandsNoRecovery = true)))
        cloud.latest = BackupEnvelopeCrypto.seal(BackupPayloadJson.encode(payload).toByteArray(),
            mapOf("keychain" to "another-device-key".toCharArray()), 2_000, iterations = 1_000)
        cloud.failLatest = true
        val borrowed = mutableListOf<CharArray>()
        val observedKeys = object : BackupSealingKeyStore by keys {
            override fun load(): CharArray? = keys.load()?.also { borrowed += it }
        }
        val source = ProductionBackupRestoreUnlockSource(accounts, { cloud },
            deviceSecretFactory = { error("a working retained key must not be replaced") },
            deviceCustody = store, deviceKeys = observedKeys)
        assertTrue(source.deviceKeyAvailable())
        val unlocked = source.unlockWithDeviceKey() as BackupRestoreUnlock.Unlocked
        assertEquals(payload, unlocked.payload)
        val adoption = checkNotNull(unlocked.adoption)
        assertEquals(store.envelope, adoption.envelope)
        assertTrue(adoption.withDeviceSecret { secret ->
            BackupEnvelopeCrypto.unlockOutcome(adoption.envelope,
                ProductionBackupRestoreUnlockSource.DEVICE_KEY_SLOT, secret) is BackupUnlockOutcome.Unlocked
        } == true)
        assertTrue(BackupEnvelopeCrypto.unlockOutcome(adoption.envelope,
            ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE, phrase) is BackupUnlockOutcome.Unlocked)
        assertTrue(borrowed.size >= 2)
        assertTrue(borrowed.all { key -> key.all { it == '\u0000' } })
        adoption.close()
        assertEquals("device-key", keys.load()!!.concatToString())
        assertEquals(1, store.envelopeSaves)
        assertEquals("This Device must not fetch or overwrite the server's device slot", 0, cloud.latestCalls)
        assertEquals("only the explicit setup uploaded", 1, cloud.uploadCalls)
    }

    @Test
    fun `missing mismatched or unreadable device custody fails closed without downloading`() = runTest {
        val envelope = deviceEnvelope()
        for (failure in listOf("missing-key", "other-account", "signed-out", "read-failed", "invalid-key")) {
            val accounts = FakeBackupAccounts()
            val store = FakeBackupLocalStore().apply { this.envelope = envelope; envelopeAccountID = TEST_ACCOUNT }
            val cloud = FakeBackupCloud().apply { latest = envelope }
            val key = when (failure) {
                "missing-key" -> null
                "invalid-key" -> CharArray(5_000) { 'k' }
                else -> "device-key".toCharArray()
            }
            val keys = object : BackupSealingKeyStore {
                override fun load(): CharArray? {
                    if (failure == "read-failed") error("device locked")
                    return key
                }
                override fun save(key: CharArray) = error("read-only")
                override fun clear() = error("read-only")
            }
            if (failure == "other-account") store.envelopeAccountID = "account-B"
            if (failure == "signed-out") accounts.switchTo(null)
            val source = ProductionBackupRestoreUnlockSource(accounts, { cloud }, deviceCustody = store, deviceKeys = keys)
            assertEquals(failure, false, source.deviceKeyAvailable())
            assertEquals(failure, BackupRestoreUnlock.Unavailable, source.unlockWithDeviceKey())
            assertEquals(failure, 0, cloud.latestCalls)
            if (failure == "invalid-key") assertTrue(key!!.all { it == '\u0000' })
        }
    }

    @Test
    fun `local device wrong key account rotation lifecycle retirement and cancellation zero the borrowed secret`() = runTest {
        for (failure in listOf("wrong-key", "account-switch", "credential-rotation", "host-retired", "cancel")) {
            val accounts = FakeBackupAccounts()
            val envelope = deviceEnvelope()
            val store = FakeBackupLocalStore().apply { this.envelope = envelope; envelopeAccountID = TEST_ACCOUNT }
            val key = (if (failure == "wrong-key") "wrong-key" else "device-key").toCharArray()
            var hostCurrent = true
            val caller = currentCoroutineContext().job
            val keys = object : BackupSealingKeyStore {
                override fun load(): CharArray {
                    when (failure) {
                        "account-switch" -> accounts.switchTo("account-B")
                        "credential-rotation" -> accounts.switchTo(TEST_ACCOUNT)
                        "host-retired" -> hostCurrent = false
                        "cancel" -> throw CancellationException("retired")
                    }
                    return key
                }
                override fun save(key: CharArray) = error("read-only")
                override fun clear() = error("read-only")
            }
            val cloud = FakeBackupCloud().apply { latest = envelope }
            val source = ProductionBackupRestoreUnlockSource(accounts, { cloud }, deviceCustody = store, deviceKeys = keys)
            val result = runCatching { source.unlockWithDeviceKey { hostCurrent } }
            if (failure == "cancel") assertTrue(result.exceptionOrNull() is CancellationException)
            else assertEquals(failure, BackupRestoreUnlock.Unavailable, result.getOrThrow())
            // A throwing load did not hand off any key. Every returned borrowed array is cleared.
            if (failure != "cancel") assertTrue(failure, key.all { it == '\u0000' })
            assertEquals(envelope, store.envelope)
            assertEquals(0, store.envelopeSaves)
            assertEquals(0, cloud.latestCalls)
            assertTrue(caller.isActive)
        }
    }

    @Test
    fun `absent local envelope remote fallback holds custody and cancellation releases after key zero`() = runTest {
        val accounts = FakeBackupAccounts()
        val envelope = deviceEnvelope()
        val store = FakeBackupLocalStore().apply { envelopeAccountID = TEST_ACCOUNT }
        val keys = InMemoryBackupSealingKeyStore("device-key".toCharArray())
        var borrowed: CharArray? = null
        val observedKeys = object : BackupSealingKeyStore by keys {
            override fun load() = keys.load()?.also { borrowed = it }
        }
        val downloading = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val cloud = object : BackupCloudClient by FakeBackupCloud() {
            override suspend fun fetchLatest(expectedGeneration: Long): BackupEnvelope? {
                downloading.complete(Unit)
                never.await()
                return envelope
            }
        }
        val source = ProductionBackupRestoreUnlockSource(accounts, { cloud }, deviceCustody = store, deviceKeys = observedKeys)
        val unlock = async { source.unlockWithDeviceKey() }
        downloading.await()
        val disable = async { store.withCustodyLock {
            assertTrue(checkNotNull(borrowed).all { it == '\u0000' })
            keys.clear()
            store.deleteEnvelope()
        } }
        runCurrent()
        assertTrue(!disable.isCompleted)
        unlock.cancel()
        unlock.join()
        assertTrue(disable.await())
        assertEquals(null, keys.load())
        assertEquals(null, store.envelope)
    }

    @Test
    fun `recovery restore then This Device opens adopted local ciphertext without changing the server slot`() = runTest {
        assertRestoredLocalDeviceRoundtrip(BackupRecoveryMethod.RECOVERY_CODE)
    }

    @Test
    fun `PRF restore then This Device opens adopted local ciphertext without changing the server slot`() = runTest {
        // Controlled authenticator material exercises the real crypto/publisher; not a live provider.
        assertRestoredLocalDeviceRoundtrip(BackupRecoveryMethod.PASSKEY)
    }

    private suspend fun assertRestoredLocalDeviceRoundtrip(method: BackupRecoveryMethod) {
        val accounts = FakeBackupAccounts()
        val store = FakeBackupLocalStore()
        val keys = InMemoryBackupSealingKeyStore()
        val restoredPayload = payload.copy(blockedDomains = setOf("restored.example"))
        val output = ByteArray(32) { (it + 1).toByte() }
        val salt = ByteArray(32) { (it + 2).toByte() }
        val credential = "fixture-credential"
        val remoteKey = "original-device-key".toCharArray()
        val newKey = "restoring-device-key".toCharArray()
        val material = BackupPasskeyMaterial(output, salt, credential)
        val remote = try {
            BackupEnvelopeCrypto.seal(
                BackupPayloadJson.encode(restoredPayload).toByteArray(),
                mapOf(ProductionBackupRestoreUnlockSource.DEVICE_KEY_SLOT to remoteKey,
                    ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE to phrase),
                1_000, iterations = 1_000, passkey = material,
            )
        } finally { material.close() }
        val cloud = FakeBackupCloud().apply { latest = remote }
        val source = ProductionBackupRestoreUnlockSource(accounts, { cloud },
            deviceSecretFactory = { newKey.copyOf() }, deviceCustody = store, deviceKeys = keys)
        val unlock = when (method) {
            BackupRecoveryMethod.RECOVERY_CODE -> source.unlock(phrase)
            BackupRecoveryMethod.PASSKEY -> source.unlockWithPasskey(object : BackupPasskeyCeremony {
                override suspend fun create(salt: ByteArray) = error("restore cannot register a new credential")
                override suspend fun assert(credentialId: String, salt: ByteArray): BackupPasskeyCeremonyResult {
                    assertEquals(credential, credentialId)
                    return BackupPasskeyCeremonyResult.Obtained(BackupPasskeyMaterial(output, salt, credentialId))
                }
            })
            BackupRecoveryMethod.DEVICE_KEY -> error("this fixture must first restore a portable slot")
        } as BackupRestoreUnlock.Unlocked
        val adoption = checkNotNull(unlock.adoption)
        val adoptedEnvelope = adoption.envelope
        val previous = BackupPayloadMapper.restore(payload.copy(blockedDomains = setOf("previous.example"))).library
        val authority = LocalRestoreAuthority(previous)
        val basis = BackupRestoreBasis(TEST_ACCOUNT, 7, accounts.generation)
        val publisher = ProductionBackupRestorePublisher(authority, Dispatchers.Unconfined,
            ProductBackupRestoreCustody(store, keys) { accounts.binding().accountID }, accounts)
        val owner = BackupRestoreOwner(publisher, passkeyPrf = BackupPasskeyPrfCapability { true })
        val prepared = owner.prepare(unlock, method, previous, BackupRetainedPreferences.DEFAULT, basis)
            as BackupRestorePreparation.Prepared

        assertTrue(owner.confirm(prepared.reviewID, false, basis, previous) is BackupRestoreApplyOutcome.Applied)
        assertEquals(BackupPayloadMapper.restore(restoredPayload).library, authority.library)
        assertEquals(adoptedEnvelope, store.envelope)
        assertEquals(TEST_ACCOUNT, store.envelopeAccountID)
        assertEquals(null, adoption.withDeviceSecret { it.size })
        assertEquals(null, store.uploadedAt)
        assertEquals(1, cloud.latestCalls)
        assertEquals("restore never overwrites the server's single device slot", 0, cloud.uploadCalls)
        assertEquals(remote, cloud.latest)
        val serverPlaintext = (BackupEnvelopeCrypto.unlockOutcome(remote,
            ProductionBackupRestoreUnlockSource.DEVICE_KEY_SLOT, remoteKey) as BackupUnlockOutcome.Unlocked).payload
        try { assertEquals(restoredPayload, BackupPayloadJson.decode(serverPlaintext.toString(Charsets.UTF_8))) }
        finally { serverPlaintext.fill(0) }
        assertEquals(BackupUnlockOutcome.WrongSecret, BackupEnvelopeCrypto.unlockOutcome(remote,
            ProductionBackupRestoreUnlockSource.DEVICE_KEY_SLOT, newKey))

        cloud.failLatest = true // A later This Device restore must work even with the cloud unavailable.
        assertTrue(source.deviceKeyAvailable())
        val reopened = source.unlockWithDeviceKey() as BackupRestoreUnlock.Unlocked
        assertEquals(restoredPayload, reopened.payload)
        assertEquals(adoptedEnvelope, reopened.adoption?.envelope)
        reopened.adoption?.close()
        assertEquals(1, cloud.latestCalls)
        assertEquals(0, cloud.uploadCalls)
        assertEquals(remote, cloud.latest)
        remoteKey.fill('\u0000'); newKey.fill('\u0000'); output.fill(0); salt.fill(0)
    }

    private class LocalRestoreAuthority(var library: FilterLibraryDraft) : BackupRestoreAuthority {
        private var preferences = BackupRetainedPreferences.DEFAULT
        override fun currentLibrary() = library
        override fun currentPreferences() = preferences
        override fun generation() = 7L
        override suspend fun replaceLibrary(library: FilterLibraryDraft, expectedLibrary: FilterLibraryDraft?): Boolean {
            if (expectedLibrary != null && this.library != expectedLibrary) return false
            this.library = library
            return true
        }
        override suspend fun applyPreferences(preferences: BackupRetainedPreferences): Boolean {
            this.preferences = preferences
            return true
        }
    }

    @Test
    fun `local Device Key refuses corrupt and future payloads without a remote fallback`() = runTest {
        for (failure in listOf("corrupt", "future-schema")) {
            val accounts = FakeBackupAccounts()
            var local = if (failure == "corrupt") deviceEnvelope() else BackupEnvelopeCrypto.seal(
                BackupPayloadJson.encode(payload.copy(schemaVersion = 99)).toByteArray(),
                mapOf(ProductionBackupRestoreUnlockSource.DEVICE_KEY_SLOT to "device-key".toCharArray()),
                1_000, iterations = 1_000)
            if (failure == "corrupt") {
                val bytes = Base64.getDecoder().decode(local.payloadCiphertext)
                bytes[13] = (bytes[13].toInt() xor 1).toByte()
                local = local.copy(payloadCiphertext = Base64.getEncoder().encodeToString(bytes))
            }
            val store = FakeBackupLocalStore().apply { envelope = local; envelopeAccountID = TEST_ACCOUNT }
            val borrowed = "device-key".toCharArray()
            val keys = object : BackupSealingKeyStore {
                override fun load() = borrowed
                override fun save(key: CharArray) = error("read-only")
                override fun clear() = error("read-only")
            }
            val cloud = FakeBackupCloud().apply { latest = deviceEnvelope() }
            val source = ProductionBackupRestoreUnlockSource(accounts, { cloud }, deviceCustody = store, deviceKeys = keys)

            val expected = if (failure == "corrupt") BackupRestoreUnlock.CorruptPayload
                else BackupRestoreUnlock.UnsupportedSchema(99)
            assertEquals(failure, expected, source.unlockWithDeviceKey())
            assertTrue(borrowed.all { it == '\u0000' })
            assertEquals(0, cloud.latestCalls)
            assertEquals(0, store.envelopeSaves)
            assertEquals(local, store.envelope)
        }
    }

    @Test
    fun `local Device Key cancellation after borrowing clears that exact array and publishes no adoption`() = runTest {
        val accounts = FakeBackupAccounts()
        val store = FakeBackupLocalStore().apply { envelope = deviceEnvelope(); envelopeAccountID = TEST_ACCOUNT }
        val borrowed = "device-key".toCharArray()
        lateinit var caller: kotlinx.coroutines.Job
        val keys = object : BackupSealingKeyStore {
            override fun load(): CharArray { caller.cancel(); return borrowed }
            override fun save(key: CharArray) = error("read-only")
            override fun clear() = error("read-only")
        }
        val cloud = FakeBackupCloud()
        val source = ProductionBackupRestoreUnlockSource(accounts, { cloud }, deviceCustody = store, deviceKeys = keys)
        val query = async { caller = currentCoroutineContext().job; source.unlockWithDeviceKey() }
        query.join()
        assertTrue(query.isCancelled)
        assertTrue(borrowed.all { it == '\u0000' })
        assertEquals(0, cloud.latestCalls)
        assertEquals(0, store.envelopeSaves)
    }

    @Test
    fun `local Device Key checks the original account and host again after waiting for custody`() = runTest {
        for (retirement in listOf("account", "generation", "host")) {
            val accounts = FakeBackupAccounts()
            val store = FakeBackupLocalStore().apply { envelope = deviceEnvelope(); envelopeAccountID = TEST_ACCOUNT }
            var keyReads = 0
            val keys = object : BackupSealingKeyStore {
                override fun load(): CharArray { keyReads++; return "device-key".toCharArray() }
                override fun save(key: CharArray) = error("read-only")
                override fun clear() = error("read-only")
            }
            val cloud = FakeBackupCloud()
            var hostCurrent = true
            val acquired = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = async { store.withCustodyLock { acquired.complete(Unit); release.await() } }
            acquired.await()
            val source = ProductionBackupRestoreUnlockSource(accounts, { cloud }, deviceCustody = store, deviceKeys = keys)
            val query = async { source.unlockWithDeviceKey { hostCurrent } }
            runCurrent()
            assertTrue(!query.isCompleted)
            when (retirement) {
                "account" -> accounts.switchTo("account-B")
                "generation" -> accounts.switchTo(TEST_ACCOUNT)
                "host" -> hostCurrent = false
            }
            release.complete(Unit)
            holder.join()
            assertEquals(retirement, BackupRestoreUnlock.Unavailable, query.await())
            assertEquals(retirement, 0, keyReads)
            assertEquals(0, cloud.latestCalls)
            assertEquals(0, store.envelopeSaves)
        }
    }

    @Test
    fun `absent local ciphertext remote fallback rechecks its exact account host and custody after download`() = runTest {
        for (retirement in listOf("none", "account", "generation", "host", "local-ciphertext-appeared")) {
            val accounts = FakeBackupAccounts()
            val store = FakeBackupLocalStore().apply { envelopeAccountID = TEST_ACCOUNT }
            val borrowed = "device-key".toCharArray()
            val keys = object : BackupSealingKeyStore {
                override fun load() = borrowed
                override fun save(key: CharArray) = error("read-only")
                override fun clear() = error("read-only")
            }
            val started = CompletableDeferred<Unit>()
            val downloaded = CompletableDeferred<BackupEnvelope>()
            val remote = deviceEnvelope()
            val cloud = object : BackupCloudClient by FakeBackupCloud() {
                override suspend fun fetchLatest(expectedGeneration: Long): BackupEnvelope {
                    assertEquals(7L, expectedGeneration)
                    started.complete(Unit)
                    return downloaded.await()
                }
                override suspend fun upload(expectedGeneration: Long, envelope: BackupEnvelope) =
                    error("unlock is not a cloud upload")
            }
            var hostCurrent = true
            val source = ProductionBackupRestoreUnlockSource(accounts, { cloud }, deviceCustody = store, deviceKeys = keys)
            val query = async { source.unlockWithDeviceKey { hostCurrent } }
            started.await()
            when (retirement) {
                "account" -> accounts.switchTo("account-B")
                "generation" -> accounts.switchTo(TEST_ACCOUNT)
                "host" -> hostCurrent = false
                "local-ciphertext-appeared" -> store.envelope = deviceEnvelope()
            }
            downloaded.complete(remote)
            val result = query.await()
            if (retirement == "none") {
                val unlocked = result as BackupRestoreUnlock.Unlocked
                assertEquals(payload, unlocked.payload)
                assertEquals(remote, unlocked.adoption?.envelope)
                unlocked.adoption?.close()
            } else assertEquals(retirement, BackupRestoreUnlock.Unavailable, result)
            assertTrue(retirement, borrowed.all { it == '\u0000' })
            assertEquals(0, store.envelopeSaves)
        }
    }

    private fun deviceEnvelope() = BackupEnvelopeCrypto.seal(
        BackupPayloadJson.encode(payload).toByteArray(),
        mapOf(ProductionBackupRestoreUnlockSource.DEVICE_KEY_SLOT to "device-key".toCharArray(),
            ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE to phrase), 1_000, iterations = 1_000)

    @Test
    fun `recovery plaintext is refused when production device adoption cannot complete`() = runTest {
        for (failure in listOf("invalid-key", "key-factory-failed", "cancelled", "account-changed")) {
            val accounts = FakeBackupAccounts()
            val envelope = envelopeFrom(payload)
            val cloud = FakeBackupCloud().apply { latest = envelope }
            val key = CharArray(if (failure == "invalid-key") 5_000 else 32) { 'k' }
            val source = source(accounts, cloud, deviceSecretFactory = {
                when (failure) {
                    "key-factory-failed" -> throw IllegalStateException("key unavailable")
                    "cancelled" -> throw CancellationException("retired")
                    "account-changed" -> accounts.switchTo("account-A")
                }
                key
            })
            val result = runCatching { source.unlock(phrase) }
            if (failure == "cancelled") assertTrue(result.exceptionOrNull() is CancellationException)
            else assertEquals(failure, BackupRestoreUnlock.Unavailable, result.getOrThrow())
            if (failure == "invalid-key" || failure == "account-changed") assertTrue(key.all { it == '\u0000' })
            assertEquals("the downloaded envelope must remain unchanged", envelope, cloud.latest)
        }
    }


    private val payload = BackupConfigurationPayload(resolverPresetID = "device-dns")
    private val phrase = "baba keke dede fafa".toCharArray()

    private fun envelopeFrom(payload: BackupConfigurationPayload): BackupEnvelope =
        BackupEnvelopeCrypto.seal(
            payload = BackupPayloadJson.encode(payload).toByteArray(Charsets.UTF_8),
            secrets = mapOf(ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE to phrase),
            createdAtEpochSeconds = 1_000,
        )

    private fun source(
        accounts: FakeBackupAccounts = FakeBackupAccounts(),
        cloud: FakeBackupCloud = FakeBackupCloud(),
        deviceSecretFactory: (() -> CharArray)? = null,
    ) = ProductionBackupRestoreUnlockSource(
        accounts = accounts,
        cloud = { cloud },
        deviceSecretFactory = deviceSecretFactory,
    )

    @Test
    fun `unlock downloads decrypts and decodes the account envelope`() = runTest {
        val cloud = FakeBackupCloud().apply { latest = envelopeFrom(payload) }

        val result = source(cloud = cloud).unlock(phrase)

        assertTrue(result is BackupRestoreUnlock.Unlocked)
        assertEquals(payload.resolverPresetID, (result as BackupRestoreUnlock.Unlocked).payload.resolverPresetID)
        assertEquals(1, cloud.latestCalls)
    }

    @Test
    fun `unlock re-keys the downloaded envelope for local adoption preserving the phrase slot`() = runTest {
        val cloud = FakeBackupCloud().apply { latest = envelopeFrom(payload) }
        val deviceSecret = "device-key".toCharArray()

        val result = source(cloud = cloud, deviceSecretFactory = { deviceSecret.copyOf() }).unlock(phrase)

        val unlocked = result as BackupRestoreUnlock.Unlocked
        val adoption = checkNotNull(unlocked.adoption)
        // The adopted envelope opens with the retained device key...
        assertTrue(
            BackupEnvelopeCrypto.unlockOutcome(
                adoption.envelope,
                ProductionBackupRestoreUnlockSource.DEVICE_KEY_SLOT,
                deviceSecret,
            ) is BackupUnlockOutcome.Unlocked,
        )
        // ...and the user's recovery phrase still opens it (the portable slot is preserved).
        assertTrue(
            BackupEnvelopeCrypto.unlockOutcome(
                adoption.envelope,
                ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE,
                phrase,
            ) is BackupUnlockOutcome.Unlocked,
        )
        adoption.close()
    }

    @Test
    fun `a wrong secret is WrongSecret`() = runTest {
        val cloud = FakeBackupCloud().apply { latest = envelopeFrom(payload) }

        val result = source(cloud = cloud).unlock("wrong".toCharArray())

        assertEquals(BackupRestoreUnlock.WrongSecret, result)
    }

    @Test
    fun `a damaged payload ciphertext is CorruptPayload`() = runTest {
        val envelope = envelopeFrom(payload)
        val bytes = Base64.getDecoder().decode(envelope.payloadCiphertext)
        bytes[13] = (bytes[13].toInt() xor 1).toByte()
        val corrupted = envelope.copy(payloadCiphertext = Base64.getEncoder().encodeToString(bytes))
        val cloud = FakeBackupCloud().apply { latest = corrupted }

        val result = source(cloud = cloud).unlock(phrase)

        assertEquals(BackupRestoreUnlock.CorruptPayload, result)
    }

    @Test
    fun `a future payload schema is UnsupportedSchema`() = runTest {
        val future = BackupConfigurationPayload(schemaVersion = 99, resolverPresetID = "device-dns")
        val cloud = FakeBackupCloud().apply { latest = envelopeFrom(future) }

        val result = source(cloud = cloud).unlock(phrase)

        assertEquals(BackupRestoreUnlock.UnsupportedSchema(99), result)
    }

    @Test
    fun `signed-out and no-envelope and download-failure are all Unavailable`() = runTest {
        assertEquals(
            BackupRestoreUnlock.Unavailable,
            source(accounts = FakeBackupAccounts(accountID = null, signedIn = false)).unlock(phrase),
        )
        assertEquals(
            BackupRestoreUnlock.Unavailable,
            source(cloud = FakeBackupCloud().apply { latest = null }).unlock(phrase),
        )
        assertEquals(
            BackupRestoreUnlock.Unavailable,
            source(cloud = FakeBackupCloud().apply { failLatest = true }).unlock(phrase),
        )
    }

    @Test
    fun `a credential rotation during the download yields no plaintext`() = runTest {
        val accounts = FakeBackupAccounts()
        val cloud = FakeBackupCloud().apply {
            latest = envelopeFrom(payload)
            onFetchLatest = { accounts.switchTo("account-B") }
        }

        val result = source(accounts = accounts, cloud = cloud).unlock(phrase)

        assertEquals(BackupRestoreUnlock.Unavailable, result)
    }
}
