package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupPasskeyMaterial
import com.lavasec.app.foundation.backup.BackupPayloadJson
import com.lavasec.app.foundation.backup.BackupSetupConsent
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The outcome of one committed local setup. [Completed.uploaded] distinguishes a setup that also
 * reached the account's remote copy from one that is committed locally and awaits a network/account
 * (a legitimate, retryable state, not a failure). [Rejected] carries a stable wire reason so the
 * shared screen can name exactly what stopped the setup; a refusal is never a fabricated success.
 */
internal sealed interface BackupSetupResult {
    data class Completed(val uploaded: Boolean) : BackupSetupResult

    data class Rejected(val reason: String) : BackupSetupResult
}

/** The native prompt's account lease, captured before any recovery/passkey UI suspends. */
internal data class BackupSetupBasis(val account: BackupAccountBinding, val accountGeneration: Long)

/**
 * Production "setup -> upload" for the encrypted-settings backup.
 *
 * One call seals the live portable payload with the user's recovery secret, persists the local
 * envelope through the same credential-protected store `backup.now` uploads from, and (when signed
 * in with a cloud client) uploads it bound to the captured account generation. The account binding
 * is revalidated after the local commit and before the remote send, so a credential-graph rotation
 * mid-setup can never upload one account's ciphertext to another.
 *
 * Safety gates preserved:
 *  - the caller must present an explicit [BackupSetupConsent] with both acknowledgments;
 *  - a deletion fence blocks setup exactly as it blocks every other writer;
 *  - the secret is used only to derive the wrapping key inside [BackupEnvelopeCrypto] and is never
 *    copied into a field, a log or the payload;
 *  - a local write that is not durably acknowledged refuses instead of returning success.
 *
 * An upload failure after a durable local commit is reported as [BackupSetupResult.Completed] with
 * `uploaded = false`: the backup exists on device and the automatic/`backup.now` paths retry the
 * send. Nothing here ever claims a remote copy that does not exist.
 */
internal class ProductionBackupSetup(
    private val payloadSource: BackupPayloadSource,
    private val store: BackupLocalStore,
    private val accounts: BackupAccountAuthority,
    private val cloud: BackupCloudSource,
    /**
     * The setup admission gate. Returns true when setup may proceed (a completed `disabled`
     * deletion fence, or none); returns false for an unfinished or unreadable fence, which must
     * keep blocking writers. This only checks: it never clears the fence, because a setup can still
     * fail after admission.
     */
    private val reEnableGate: () -> Boolean,
    /**
     * The re-enable commit, invoked only after the local envelope commit is durable. A failed,
     * refused or cancelled setup therefore leaves a completed `disabled` deletion fence intact
     * (the user's Off intent is never silently dropped before a setup actually lands).
     */
    private val reEnableCommit: () -> Boolean = { true },
    private val slotKind: String = ProductionBackupRestoreUnlockSource.SLOT_RECOVERY_PHRASE,
    /** The retained device-key backend that lets later uploads re-seal without the phrase. */
    private val sealingKeys: BackupSealingKeyStore = InMemoryBackupSealingKeyStore(),
    /** A fresh device-held sealing secret; injectable so tests can pin it. */
    private val deviceSecretFactory: () -> CharArray = ::newBackupDeviceSecret,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    suspend fun setup(
        secret: CharArray,
        consent: BackupSetupConsent,
        /** An optional passkey-PRF slot to seal alongside the typed secret; the caller owns it. */
        passkey: BackupPasskeyMaterial? = null,
        /** The key-slot kind the typed secret occupies; the caller chooses the recovery method. */
        slotKind: String = this.slotKind,
        expectedBasis: BackupSetupBasis? = null,
        /** The caller's credential graph must remain attached for this setup. */
        stillAuthorized: () -> Boolean = { true },
    ): BackupSetupResult {
        // Share the upload/delete/restore exclusion: a waiting disable runs only after this setup
        // finishes, and a pending deletion is re-read after a waiting setup acquires the lock.
        return store.withCustodyLock { setupLocked(secret, consent, passkey, slotKind, expectedBasis, stillAuthorized) }
    }

    private suspend fun setupLocked(
        secret: CharArray,
        consent: BackupSetupConsent,
        passkey: BackupPasskeyMaterial?,
        slotKind: String,
        expectedBasis: BackupSetupBasis?,
        stillAuthorized: () -> Boolean,
    ): BackupSetupResult {
        if (!consent.canFinish) return BackupSetupResult.Rejected(REASON_CONSENT)
        if (!reEnableGate()) return BackupSetupResult.Rejected(REASON_DELETION_PENDING)
        val binding = accounts.binding()
        val generation = accounts.generation()
        if (!stillAuthorized() || (expectedBasis != null &&
                (binding != expectedBasis.account || generation != expectedBasis.accountGeneration))) {
            return BackupSetupResult.Rejected(REASON_ACCOUNT_CHANGED)
        }
        fun remainsBound(): Boolean = stillAuthorized() && accounts.binding() == binding && accounts.generation() == generation
        val payload = try {
            payloadSource.currentPayload()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupSetupResult.Rejected(REASON_PAYLOAD_UNAVAILABLE)
        }
        if (!remainsBound()) {
            return BackupSetupResult.Rejected(REASON_ACCOUNT_CHANGED)
        }
        val encoded = BackupPayloadJson.encode(payload).toByteArray(Charsets.UTF_8)
        try {
            if (encoded.size > MAX_PAYLOAD_BYTES) {
                return BackupSetupResult.Rejected(REASON_PAYLOAD_TOO_LARGE)
            }
            // Retain a device-held key so later uploads can re-seal the live payload without the user's
            // phrase. It is additive to the user's slot; reconciliation with the phrase is untouched.
            val sealsDeviceKey = slotKind != DEVICE_KEY_SLOT
            val deviceSecret = try {
                deviceSecretFactory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return BackupSetupResult.Rejected(REASON_SEAL_FAILED)
            }
            try {
                val secrets = buildMap {
                    put(slotKind, secret)
                    if (sealsDeviceKey) put(DEVICE_KEY_SLOT, deviceSecret)
                }
                val envelope = try {
                    BackupEnvelopeCrypto.seal(
                        payload = encoded,
                        secrets = secrets,
                        createdAtEpochSeconds = nowEpochSeconds(),
                        passkey = passkey,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return BackupSetupResult.Rejected(REASON_SEAL_FAILED)
                }
                currentCoroutineContext().ensureActive()
                if (!remainsBound()) {
                    return BackupSetupResult.Rejected(REASON_ACCOUNT_CHANGED)
                }
                // A replacement must not erase an existing usable backup on a partial write.
                // Read the whole prior custody under the shared lock, then compensate any refusal.
                val previous = try {
                    LocalCustody(store.loadEnvelope(), store.loadEnvelopeAccountID(),
                        store.loadUploadedAtEpochSeconds(), sealingKeys.load())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return BackupSetupResult.Rejected(REASON_LOCAL_WRITE_FAILED)
                }
                try {
                    val committed = try {
                        store.saveEnvelope(envelope) && store.saveEnvelopeAccountID(binding.accountID) &&
                            (!sealsDeviceKey || sealingKeys.save(deviceSecret)) && store.clearUploadMarker()
                    } catch (cancelled: CancellationException) {
                        restoreCustody(previous)
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (!committed) return refusedLocalWrite(previous)
                    // Clear a completed Off fence only once envelope/key custody is durable. A
                    // failed fence commit restores prior custody and leaves Off intent in force.
                    val reEnabled = try {
                        reEnableCommit()
                    } catch (cancelled: CancellationException) {
                        restoreCustody(previous)
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (!reEnabled) {
                        return BackupSetupResult.Rejected(if (restoreCustody(previous)) REASON_DELETION_PENDING else REASON_LOCAL_ROLLBACK_FAILED)
                    }
                } finally {
                    previous.sealingKey?.fill('\u0000')
                }
                // The account may have rotated while the (CPU-bound) seal ran; only a still-current binding
                // may send. The local envelope is account-independent, so it stays; the send does not.
                if (!remainsBound()) {
                    return BackupSetupResult.Rejected(REASON_ACCOUNT_CHANGED)
                }
                val client = cloud.current()
                if (client == null || !binding.signedIn) return BackupSetupResult.Completed(uploaded = false)
                return try {
                    client.upload(generation, envelope)
                    if (!remainsBound()) {
                        return BackupSetupResult.Rejected(REASON_ACCOUNT_CHANGED)
                    }
                    store.recordUploadedAtEpochSeconds(nowEpochSeconds())
                    BackupSetupResult.Completed(uploaded = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    BackupSetupResult.Completed(uploaded = false)
                }
            } finally {
                deviceSecret.fill('\u0000')
            }
        } finally {
            encoded.fill(0)
        }
    }

    private data class LocalCustody(
        val envelope: BackupEnvelope?,
        val accountID: String?,
        val uploadedAt: Long?,
        val sealingKey: CharArray?,
    )

    private fun refusedLocalWrite(previous: LocalCustody): BackupSetupResult.Rejected =
        BackupSetupResult.Rejected(if (restoreCustody(previous)) REASON_LOCAL_WRITE_FAILED else REASON_LOCAL_ROLLBACK_FAILED)

    private fun restoreCustody(previous: LocalCustody): Boolean {
        // Attempt each independent component even when one fails, and acknowledge only a fully
        // durable restoration. Never hide a partial rollback behind the ordinary retry copy.
        val envelopeRestored = runCatching {
            if (previous.envelope == null) store.deleteEnvelope() else store.saveEnvelope(previous.envelope)
        }.getOrDefault(false)
        val accountRestored = runCatching { store.saveEnvelopeAccountID(previous.accountID) }.getOrDefault(false)
        val keyRestored = runCatching {
            if (previous.sealingKey == null) sealingKeys.clear() else sealingKeys.save(previous.sealingKey)
        }.getOrDefault(false)
        val markerRestored = runCatching {
            if (previous.uploadedAt == null) store.clearUploadMarker() else store.recordUploadedAtEpochSeconds(previous.uploadedAt)
        }.getOrDefault(false)
        return envelopeRestored && accountRestored && keyRestored && markerRestored
    }

    internal companion object {
        /** Mirror of the server's `user_backups` ciphertext bound; never seal an oversized payload. */
        const val MAX_PAYLOAD_BYTES = BackupEnvelopeCrypto.MAX_CIPHERTEXT_BYTES - 28

        /** The envelope key-slot kind for the device-retained re-seal key. */
        const val DEVICE_KEY_SLOT = "keychain"

        const val REASON_CONSENT = "backup-setup-consent-required"
        const val REASON_DELETION_PENDING = "backup-setup-deletion-pending"
        const val REASON_PAYLOAD_UNAVAILABLE = "backup-setup-payload-unavailable"
        const val REASON_PAYLOAD_TOO_LARGE = "backup-setup-payload-too-large"
        const val REASON_SEAL_FAILED = "backup-setup-seal-failed"
        const val REASON_LOCAL_WRITE_FAILED = "backup-setup-local-write-failed"
        const val REASON_LOCAL_ROLLBACK_FAILED = "backup-setup-local-rollback-failed"
        const val REASON_ACCOUNT_CHANGED = "backup-setup-account-changed"
    }
}

/** A fresh random device-held sealing secret, Base64 so it round-trips through the key store. */
internal fun newBackupDeviceSecret(): CharArray {
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    return try { Base64.getEncoder().encodeToString(bytes).toCharArray() } finally { bytes.fill(0) }
}
