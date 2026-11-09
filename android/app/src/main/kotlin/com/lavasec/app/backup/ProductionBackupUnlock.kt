package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupRestoreAdoption
import com.lavasec.app.foundation.backup.BackupRestoreUnlock
import com.lavasec.app.foundation.backup.BackupSchemaUnsupportedException
import com.lavasec.app.foundation.backup.BackupUnlockOutcome
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Production download + decrypt adapter for a reviewed restore.
 *
 * This is the seam the restore owner consumes: it loads the account's current envelope through the
 * account-bound cloud client and decrypts it with the user-supplied secret, mapping the distinct
 * failure causes onto the owner's [BackupRestoreUnlock] vocabulary. The account and its generation
 * are captured before the download and revalidated after it, so a sign-out or A->B switch that lands
 * while the request is in flight yields [BackupRestoreUnlock.Unavailable] rather than another
 * account's plaintext.
 *
 * No secret is ever logged or handed back across the process boundary; the plaintext payload is
 * parsed in-process and only the portable [BackupConfigurationPayload] leaves this class. The
 * decrypted byte array is zeroed once decoded.
 */
internal class ProductionBackupRestoreUnlockSource(
    private val accounts: BackupAccountAuthority,
    private val cloud: BackupCloudSource,
    /** The envelope key-slot kind this build seals and opens; recovery phrase by default. */
    private val slotKind: String = SLOT_RECOVERY_PHRASE,
    /**
     * When supplied, a successful unlock re-keys the downloaded envelope with a fresh device-held
     * sealing key (preserving every portable slot) and returns it as an adoption, so a restored
     * device continues to back up without retaining the recovery phrase. `null` keeps unlock read-only.
     */
    private val deviceSecretFactory: (() -> CharArray)? = null,
    private val deviceCustody: BackupLocalStore? = null,
    private val deviceKeys: BackupSealingKeyStore? = null,
) {
    /** Local admission only. Actual download/decryption still has to prove the retained key. */
    suspend fun deviceKeyAvailable(): Boolean {
        val store = deviceCustody ?: return false
        val keys = deviceKeys ?: return false
        return try {
            store.withCustodyLock {
                val binding = accounts.binding()
                val generation = accounts.generation()
                if (!binding.signedIn || binding.accountID == null || store.loadEnvelopeAccountID() != binding.accountID ||
                    store.loadEnvelope()?.keySlots?.none { it.kind == DEVICE_KEY_SLOT } != false) return@withCustodyLock false
                val key = keys.load() ?: return@withCustodyLock false
                try {
                    key.size in 1..4096 && accounts.binding() == binding && accounts.generation() == generation
                } finally { key.fill('\u0000') }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens this account's exact local envelope with its retained Keystore-wrapped key. A phrase or
     * PRF restore replaces only the local device slot, so the server may still hold another device's
     * slot. Like the frozen iOS local-first restore, this path must not download over working local
     * custody or implicitly upload a replacement slot. Only absent local ciphertext permits the
     * existing account-bound remote fallback; a damaged local envelope never falls through to it.
     */
    suspend fun unlockWithDeviceKey(stillCurrent: () -> Boolean = { true }): BackupRestoreUnlock {
        val store = deviceCustody ?: return BackupRestoreUnlock.Unavailable
        val keys = deviceKeys ?: return BackupRestoreUnlock.Unavailable
        val binding = accounts.binding()
        val generation = accounts.generation()
        val caller = currentCoroutineContext()
        caller.ensureActive()
        if (!binding.signedIn || binding.accountID == null || !stillCurrent()) return BackupRestoreUnlock.Unavailable
        return try {
            store.withCustodyLock {
                caller.ensureActive()
                if (!stillCurrent() || accounts.binding() != binding || accounts.generation() != generation ||
                    store.loadEnvelopeAccountID() != binding.accountID) return@withCustodyLock BackupRestoreUnlock.Unavailable
                val localEnvelope = store.loadEnvelope()
                val secret = try { keys.load() } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null } ?: return@withCustodyLock BackupRestoreUnlock.Unavailable
                try {
                    caller.ensureActive()
                    if (secret.size !in 1..4096) return@withCustodyLock BackupRestoreUnlock.Unavailable
                    val envelope = localEnvelope ?: run {
                        val client = cloud.current() ?: return@withCustodyLock BackupRestoreUnlock.Unavailable
                        try { client.fetchLatest(generation) } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { null }
                    } ?: return@withCustodyLock BackupRestoreUnlock.Unavailable
                    caller.ensureActive()
                    if (!stillCurrent() || accounts.binding() != binding || accounts.generation() != generation ||
                        store.loadEnvelopeAccountID() != binding.accountID || store.loadEnvelope() != localEnvelope) {
                        return@withCustodyLock BackupRestoreUnlock.Unavailable
                    }
                    when (val outcome = BackupEnvelopeCrypto.unlockOutcome(envelope, DEVICE_KEY_SLOT, secret)) {
                        BackupUnlockOutcome.WrongSecret -> BackupRestoreUnlock.Unavailable
                        BackupUnlockOutcome.CorruptPayload -> BackupRestoreUnlock.CorruptPayload
                        is BackupUnlockOutcome.Unlocked -> decodeBoundPayload(outcome.payload, binding, generation) {
                            caller.ensureActive()
                            if (!stillCurrent() || store.loadEnvelopeAccountID() != binding.accountID ||
                                store.loadEnvelope() != localEnvelope) {
                                throw BackupLocalAdoptionUnavailable(IllegalStateException("device custody retired"))
                            }
                            // Preserve this exact envelope/key pair; no fresh key or phrase is needed.
                            BackupRestoreAdoption(envelope, secret)
                        }
                    }
                } finally { secret.fill('\u0000') }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BackupRestoreUnlock.Unavailable
        }
    }

    suspend fun unlock(secret: CharArray): BackupRestoreUnlock {
        val binding = accounts.binding()
        val generation = accounts.generation()
        if (!binding.signedIn || binding.accountID == null) return BackupRestoreUnlock.Unavailable
        val client = cloud.current() ?: return BackupRestoreUnlock.Unavailable
        val envelope: BackupEnvelope = try {
            client.fetchLatest(generation) ?: return BackupRestoreUnlock.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupRestoreUnlock.Unavailable
        }
        // A pending restore must never decrypt another account's ciphertext: the binding captured
        // before the download has to still be current when the ciphertext arrives.
        if (accounts.binding() != binding || accounts.generation() != generation) {
            return BackupRestoreUnlock.Unavailable
        }
        return when (val outcome = BackupEnvelopeCrypto.unlockOutcome(envelope, slotKind, secret)) {
            BackupUnlockOutcome.WrongSecret -> BackupRestoreUnlock.WrongSecret
            BackupUnlockOutcome.CorruptPayload -> BackupRestoreUnlock.CorruptPayload
            is BackupUnlockOutcome.Unlocked -> decodeBoundPayload(outcome.payload, binding, generation) { adoptEnvelope(envelope, secret) }
        }
    }

    /**
     * Downloads the account's envelope, evaluates the stored credential's PRF through [ceremony],
     * and opens the passkey slot. A missing slot, an absent provider/credential, a dismissed sheet
     * and a failed PRF are distinct typed results; a successful unlock re-keys the device slot while
     * preserving the passkey and every other portable slot.
     *
     * The ceremony owns the authenticator material; this method only borrows the 32-byte output
     * inside the material's lock and never copies it out.
     */
    suspend fun unlockWithPasskey(ceremony: BackupPasskeyCeremony): BackupRestoreUnlock {
        val binding = accounts.binding()
        val generation = accounts.generation()
        if (!binding.signedIn || binding.accountID == null) return BackupRestoreUnlock.Unavailable
        val client = cloud.current() ?: return BackupRestoreUnlock.Unavailable
        val envelope: BackupEnvelope = try {
            client.fetchLatest(generation) ?: return BackupRestoreUnlock.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupRestoreUnlock.Unavailable
        }
        if (accounts.binding() != binding || accounts.generation() != generation) {
            return BackupRestoreUnlock.Unavailable
        }
        val slot = envelope.keySlots.singleOrNull { it.kind == BackupEnvelopeCrypto.SLOT_PASSKEY }
            ?: return BackupRestoreUnlock.Unavailable
        val credentialId = slot.credentialID ?: return BackupRestoreUnlock.Unavailable
        val salt = try {
            Base64.getDecoder().decode(slot.salt)
        } catch (_: Exception) {
            return BackupRestoreUnlock.CorruptPayload
        }
        if (salt.size != 32) return BackupRestoreUnlock.CorruptPayload
        return when (val result = ceremony.assert(credentialId, salt)) {
            is BackupPasskeyCeremonyResult.Obtained -> try {
                // The provider sheet can outlive a sign-out or account switch. Its material is
                // still closed below, but no plaintext or adoption may leave a retired account.
                if (accounts.binding() != binding || accounts.generation() != generation ||
                    result.material.credentialId != credentialId
                ) {
                    return BackupRestoreUnlock.Unavailable
                }
                result.material.usePrf { output, returnedSalt ->
                    if (!returnedSalt.contentEquals(salt)) return@usePrf BackupRestoreUnlock.Unavailable
                    val plaintext = try {
                        BackupEnvelopeCrypto.unlockWithPasskeyPrf(envelope, output)
                    } catch (_: Exception) {
                        return@usePrf BackupRestoreUnlock.WrongSecret
                    }
                    decodeBoundPayload(plaintext, binding, generation) { adoptEnvelopeWithPasskey(envelope, output) }
                }
            } finally {
                // The ceremony's copy is spent; zeroize it even on the failure paths.
                result.material.close()
            }

            BackupPasskeyCeremonyResult.Cancelled -> BackupRestoreUnlock.Cancelled
            is BackupPasskeyCeremonyResult.Failed -> BackupRestoreUnlock.Unavailable
        }
    }

    private fun decodeBoundPayload(
        plaintext: ByteArray,
        binding: BackupAccountBinding,
        generation: Long,
        adopt: () -> BackupRestoreAdoption?,
    ): BackupRestoreUnlock {
        var adoption: BackupRestoreAdoption? = null
        var handedOff = false
        return try {
            val decoded = BackupPayloadJson.decode(plaintext.toString(Charsets.UTF_8))
            adoption = adopt()
            if (accounts.binding() != binding || accounts.generation() != generation) {
                BackupRestoreUnlock.Unavailable
            } else {
                BackupRestoreUnlock.Unlocked(decoded, adoption).also { handedOff = true }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: BackupLocalAdoptionUnavailable) {
            BackupRestoreUnlock.Unavailable
        } catch (unsupported: BackupSchemaUnsupportedException) {
            BackupRestoreUnlock.UnsupportedSchema(unsupported.schemaVersion)
        } catch (_: Exception) {
            BackupRestoreUnlock.CorruptPayload
        } finally {
            if (!handedOff) adoption?.close()
            plaintext.fill(0)
        }
    }

    /**
     * Re-keys the downloaded envelope's device slot with a fresh random secret while preserving
     * every portable slot (the user's recovery phrase remains valid elsewhere). Returns `null` when
     * no device key is retained or the slot kind is already the device slot.
     */
    private fun adoptEnvelope(envelope: BackupEnvelope, secret: CharArray): BackupRestoreAdoption? {
        val factory = deviceSecretFactory ?: return null
        if (slotKind == DEVICE_KEY_SLOT) return null
        val deviceSecret = freshDeviceSecret(factory)
        return try {
            val rekeyed = BackupEnvelopeCrypto.rekeyDeviceSlot(envelope, slotKind, secret, deviceSecret)
            BackupRestoreAdoption(rekeyed, deviceSecret)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            throw BackupLocalAdoptionUnavailable(failed)
        } finally {
            deviceSecret.fill('\u0000')
        }
    }

    /** Passkey adoption: re-key the device slot with a fresh secret while preserving the PRF slot. */
    private fun adoptEnvelopeWithPasskey(envelope: BackupEnvelope, output: ByteArray): BackupRestoreAdoption? {
        val factory = deviceSecretFactory ?: return null
        val deviceSecret = freshDeviceSecret(factory)
        return try {
            val rekeyed = BackupEnvelopeCrypto.rekeyDeviceSlotWithPasskey(envelope, output, deviceSecret)
            BackupRestoreAdoption(rekeyed, deviceSecret)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            throw BackupLocalAdoptionUnavailable(failed)
        } finally {
            deviceSecret.fill('\u0000')
        }
    }

    private class BackupLocalAdoptionUnavailable(cause: Exception) : Exception(cause)

    private fun freshDeviceSecret(factory: () -> CharArray): CharArray = try {
        factory()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Exception) {
        throw BackupLocalAdoptionUnavailable(failed)
    }

    internal companion object {
        /** Envelope key-slot kind for a recovery phrase; matches `BackupEnvelopeCrypto` kinds. */
        const val SLOT_RECOVERY_PHRASE = "recoveryPhrase"

        /** Envelope key-slot kind for a user password. */
        const val SLOT_PASSWORD = "password"

        /** The device-retained re-seal key slot; see `ProductionBackupSetup.DEVICE_KEY_SLOT`. */
        const val DEVICE_KEY_SLOT = "keychain"
    }
}
