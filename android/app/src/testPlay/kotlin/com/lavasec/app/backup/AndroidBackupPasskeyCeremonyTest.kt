package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupConfigurationPayload
import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupPasskeyMaterial
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupRestoreUnlock
import com.lavasec.app.foundation.backup.BackupSetupConsent
import com.lavasec.app.foundation.backup.BackupUnlockOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the passkey ceremony seam with a fake authenticator so the positive path is JVM-provable
 * without a device: a held 32-byte PRF opens the passkey slot and re-keys device custody, a
 * dismissed sheet is [BackupRestoreUnlock.Cancelled], a failed ceremony is
 * [BackupRestoreUnlock.Unavailable], and setup seals a passkey slot that the same output opens.
 * Plain JVM.
 */
class AndroidBackupPasskeyCeremonyTest {
    @Test
    fun `passkey plaintext is never handed off after local rekey refusal cancellation or account rotation`() = runTest {
        for (failure in listOf("invalid-key", "key-factory-failed", "cancelled", "account-changed")) {
            val accounts = FakeBackupAccounts()
            val cloud = FakeBackupCloud().apply { latest = passkeyEnvelope() }
            val material = BackupPasskeyMaterial(prf, salt, credentialId)
            val key = CharArray(if (failure == "invalid-key") 5_000 else 32) { 'k' }
            val source = ProductionBackupRestoreUnlockSource(accounts, { cloud }, deviceSecretFactory = {
                when (failure) {
                    "key-factory-failed" -> throw IllegalStateException("key unavailable")
                    "cancelled" -> throw CancellationException("retired")
                    "account-changed" -> accounts.switchTo("account-B")
                }
                key
            })
            val result = runCatching { source.unlockWithPasskey(FakeCeremony(BackupPasskeyCeremonyResult.Obtained(material))) }
            if (failure == "cancelled") assertTrue(result.exceptionOrNull() is CancellationException)
            else assertEquals(failure, BackupRestoreUnlock.Unavailable, result.getOrThrow())
            assertTrue(runCatching { material.usePrf { _, _ -> Unit } }.isFailure)
            if (failure == "invalid-key" || failure == "account-changed") assertTrue(key.all { it == '\u0000' })
        }
    }


    private val payload = BackupConfigurationPayload(resolverPresetID = "device-dns")
    private val phrase = "baba keke dede fafa".toCharArray()
    private val consent = BackupSetupConsent(savedRecoveryPhrase = true, understandsNoRecovery = true)
    private val prf = ByteArray(32) { (it + 1).toByte() }
    private val salt = ByteArray(32) { (it + 7).toByte() }
    private val credentialId = "credential-abc"

    private fun passkeyEnvelope(): BackupEnvelope = BackupEnvelopeCrypto.seal(
        payload = BackupPayloadJson.encode(payload).toByteArray(Charsets.UTF_8),
        secrets = mapOf(ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE to phrase),
        createdAtEpochSeconds = 1_000,
        passkey = BackupPasskeyMaterial(prf, salt, credentialId),
    )

    private class FakeCeremony(private val result: BackupPasskeyCeremonyResult) : BackupPasskeyCeremony {
        var assertCalls = 0

        override suspend fun create(salt: ByteArray): BackupPasskeyCeremonyResult =
            BackupPasskeyCeremonyResult.Failed("unused")

        override suspend fun assert(credentialId: String, salt: ByteArray): BackupPasskeyCeremonyResult {
            assertCalls++
            return result
        }
    }

    private fun source(
        cloud: FakeBackupCloud,
        deviceSecret: CharArray?,
    ) = ProductionBackupRestoreUnlockSource(
        accounts = FakeBackupAccounts(),
        cloud = { cloud },
        deviceSecretFactory = deviceSecret?.let { { it.copyOf() } },
    )

    @Test
    fun `a held PRF unlocks the passkey slot and re-keys the device slot`() = runTest {
        val cloud = FakeBackupCloud().apply { latest = passkeyEnvelope() }
        val ceremony = FakeCeremony(
            BackupPasskeyCeremonyResult.Obtained(BackupPasskeyMaterial(prf, salt, credentialId)),
        )
        val deviceSecret = "device-secret".toCharArray()

        val result = source(cloud, deviceSecret).unlockWithPasskey(ceremony)

        val unlocked = result as BackupRestoreUnlock.Unlocked
        assertEquals(payload.resolverPresetID, unlocked.payload.resolverPresetID)
        assertEquals(1, ceremony.assertCalls)
        val adoption = checkNotNull(unlocked.adoption)
        // The adopted envelope opens with the retained device key...
        assertTrue(
            BackupEnvelopeCrypto.unlockOutcome(
                adoption.envelope,
                ProductionBackupRestoreUnlockSource.DEVICE_KEY_SLOT,
                deviceSecret,
            ) is BackupUnlockOutcome.Unlocked,
        )
        // ...the passkey slot is preserved...
        assertTrue(BackupEnvelopeCrypto.unlockWithPasskeyPrf(adoption.envelope, prf).isNotEmpty())
        // ...and so is the user's recovery phrase.
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
    fun `a dismissed ceremony is Cancelled and applies nothing`() = runTest {
        val cloud = FakeBackupCloud().apply { latest = passkeyEnvelope() }

        val result = source(cloud, null)
            .unlockWithPasskey(FakeCeremony(BackupPasskeyCeremonyResult.Cancelled))

        assertEquals(BackupRestoreUnlock.Cancelled, result)
    }

    @Test
    fun `a failed ceremony is Unavailable`() = runTest {
        val cloud = FakeBackupCloud().apply { latest = passkeyEnvelope() }

        val result = source(cloud, null).unlockWithPasskey(
            FakeCeremony(BackupPasskeyCeremonyResult.Failed(BackupPasskeyCeremonyResult.REASON_PRF_MISSING)),
        )

        assertEquals(BackupRestoreUnlock.Unavailable, result)
    }

    @Test
    fun `an account switch during the ceremony yields no plaintext or device custody`() = runTest {
        val accounts = FakeBackupAccounts()
        val cloud = FakeBackupCloud().apply { latest = passkeyEnvelope() }
        val material = BackupPasskeyMaterial(prf, salt, credentialId)
        var deviceSecretCalls = 0
        val ceremony = object : BackupPasskeyCeremony {
            override suspend fun create(salt: ByteArray) = error("unused")
            override suspend fun assert(credentialId: String, salt: ByteArray): BackupPasskeyCeremonyResult {
                accounts.switchTo("account-B")
                return BackupPasskeyCeremonyResult.Obtained(material)
            }
        }
        val source = ProductionBackupRestoreUnlockSource(
            accounts, { cloud }, deviceSecretFactory = { deviceSecretCalls++; "unused".toCharArray() },
        )

        assertEquals(BackupRestoreUnlock.Unavailable, source.unlockWithPasskey(ceremony))
        assertEquals(0, deviceSecretCalls)
        assertTrue(runCatching { material.usePrf { _, _ -> Unit } }.isFailure)
    }

    @Test
    fun `a changed generation for the same account retires the ceremony material`() = runTest {
        val accounts = FakeBackupAccounts()
        val cloud = FakeBackupCloud().apply { latest = passkeyEnvelope() }
        val material = BackupPasskeyMaterial(prf, salt, credentialId)
        val ceremony = object : BackupPasskeyCeremony {
            override suspend fun create(salt: ByteArray) = error("unused")
            override suspend fun assert(credentialId: String, salt: ByteArray): BackupPasskeyCeremonyResult {
                accounts.generation++
                return BackupPasskeyCeremonyResult.Obtained(material)
            }
        }

        assertEquals(BackupRestoreUnlock.Unavailable,
            ProductionBackupRestoreUnlockSource(accounts, { cloud }).unlockWithPasskey(ceremony))
        assertTrue(runCatching { material.usePrf { _, _ -> Unit } }.isFailure)
    }

    @Test
    fun `a ceremony result must match both stored credential and PRF salt`() = runTest {
        for (material in listOf(
            BackupPasskeyMaterial(prf, salt, "other-credential"),
            BackupPasskeyMaterial(prf, ByteArray(32) { 0 }, credentialId),
        )) {
            val cloud = FakeBackupCloud().apply { latest = passkeyEnvelope() }
            val result = source(cloud, null).unlockWithPasskey(
                FakeCeremony(BackupPasskeyCeremonyResult.Obtained(material)),
            )
            assertEquals(BackupRestoreUnlock.Unavailable, result)
            assertTrue(runCatching { material.usePrf { _, _ -> Unit } }.isFailure)
        }
    }

    @Test
    fun `an envelope without a passkey slot is Unavailable without a ceremony`() = runTest {
        val cloud = FakeBackupCloud().apply {
            latest = BackupEnvelopeCrypto.seal(
                payload = BackupPayloadJson.encode(payload).toByteArray(Charsets.UTF_8),
                secrets = mapOf(ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE to phrase),
                createdAtEpochSeconds = 1_000,
            )
        }
        val ceremony = FakeCeremony(
            BackupPasskeyCeremonyResult.Obtained(BackupPasskeyMaterial(prf, salt, credentialId)),
        )

        assertEquals(BackupRestoreUnlock.Unavailable, source(cloud, null).unlockWithPasskey(ceremony))
        assertEquals(0, ceremony.assertCalls)
    }

    @Test
    fun `setup seals a passkey slot that the same PRF opens and keeps the phrase slot`() = runTest {
        val store = FakeBackupLocalStore()
        val cloud = FakeBackupCloud()
        val owner = ProductionBackupSetup(
            payloadSource = BackupPayloadSource { payload },
            store = store,
            accounts = FakeBackupAccounts(),
            cloud = { cloud },
            reEnableGate = { true },
            nowEpochSeconds = { 1_000L },
        )
        val material = BackupPasskeyMaterial(prf, salt, credentialId)

        val result = owner.setup(phrase, consent, passkey = material)

        assertEquals(BackupSetupResult.Completed(uploaded = true), result)
        val envelope = checkNotNull(store.envelope)
        val decoded = BackupPayloadJson.decode(
            BackupEnvelopeCrypto.unlockWithPasskeyPrf(envelope, prf).toString(Charsets.UTF_8),
        )
        assertEquals(payload.resolverPresetID, decoded.resolverPresetID)
        assertTrue(
            BackupEnvelopeCrypto.unlockOutcome(
                envelope,
                ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE,
                phrase,
            ) is BackupUnlockOutcome.Unlocked,
        )
        material.close()
    }
}
