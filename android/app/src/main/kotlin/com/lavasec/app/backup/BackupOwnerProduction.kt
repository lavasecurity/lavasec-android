package com.lavasec.app.backup

import android.content.Context
import android.content.SharedPreferences
import com.lavasec.app.account.AccountAvailability
import com.lavasec.app.account.AccountSessionOwner
import com.lavasec.app.foundation.backup.BackupDeletionIntent
import com.lavasec.app.foundation.backup.BackupDeletionIntentCodec
import com.lavasec.app.foundation.backup.BackupDeletionIntentStore
import com.lavasec.app.foundation.backup.BackupDeletionRead
import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.platform.ChainedUpstreamKeyItemStore
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

/** Thin composition wrapper; the cloud client keeps ownership of its request/response policy. */
internal class SupabaseBackupCloud(
    private val client: SupabaseBackupClient,
) : BackupCloudClient {
    override suspend fun upload(expectedGeneration: Long, envelope: BackupEnvelope) =
        client.upload(expectedGeneration, envelope)

    override suspend fun fetchLatest(expectedGeneration: Long): BackupEnvelope? =
        client.fetchLatest(expectedGeneration)

    override suspend fun fetchMetadata(expectedGeneration: Long): BackupRemoteMetadata? =
        client.fetchMetadata(expectedGeneration)

    override suspend fun deleteRemote(expectedGeneration: Long) =
        client.deleteRemote(expectedGeneration)
}

/**
 * Reads the process account authority. A missing backend (pre-unlock / not configured) projects as
 * signed-out with generation 0, so no backup operation can be bound to a credential that is not
 * currently installed.
 */
internal class SessionBackupAccountAuthority(
    private val sessions: () -> AccountSessionOwner?,
) : BackupAccountAuthority {
    override fun binding(): BackupAccountBinding {
        val snapshot = sessions()?.state?.value ?: return BackupAccountBinding(null, false)
        return BackupAccountBinding(
            accountID = snapshot.userId,
            signedIn = snapshot.availability == AccountAvailability.SIGNED_IN,
        )
    }

    override fun generation(): Long = sessions()?.state?.value?.generation ?: 0L
}

/**
 * Durable credential-protected backup store.
 *
 * The envelope reuses the canonical `user_backups` row codec with the reserved local identity, so
 * an envelope persisted here round-trips through exactly the fields the server wire uses. Every
 * mutation uses `commit()` (synchronous durability) because the deletion fence may not proceed on a
 * write that has not been acknowledged.
 */
internal class AndroidBackupLocalStore(context: Context) :
    BackupLocalStore,
    BackupDeletionIntentStore,
    BackupAutomaticPreferenceStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override val custodyLockIdentity: Any get() = preferences

    // Reopened owners share the preference authority. Validate the cached encoding against the
    // current record so one owner's adoption cannot leave another owner resealing old ciphertext
    // under the newly retained device key.
    private var cachedEnvelope: BackupEnvelope? = null
    private var cachedEnvelopeEncoding: String? = null
    private var cacheLoaded = false

    @Synchronized
    override fun loadEnvelope(): BackupEnvelope? {
        val encoded = preferences.getString(KEY_ENVELOPE, null)
        if (!cacheLoaded || cachedEnvelopeEncoding != encoded) {
            cachedEnvelope = encoded?.let { value ->
                runCatching { BackupEnvelopeJson.decode(JSONObject(value), LOCAL_IDENTITY) }.getOrNull()
            }
            cachedEnvelopeEncoding = encoded
            cacheLoaded = true
        }
        return cachedEnvelope
    }

    @Synchronized
    override fun saveEnvelope(envelope: BackupEnvelope): Boolean {
        val encoded = BackupEnvelopeJson.upload(envelope, LOCAL_IDENTITY).toString()
        val saved = preferences.edit()
            .putString(KEY_ENVELOPE, encoded)
            .commit()
        if (saved) {
            cachedEnvelope = envelope
            cachedEnvelopeEncoding = encoded
            cacheLoaded = true
        }
        return saved
    }

    @Synchronized
    override fun deleteEnvelope(): Boolean {
        val deleted = preferences.edit().remove(KEY_ENVELOPE).remove(KEY_ENVELOPE_ACCOUNT).commit()
        if (deleted) {
            cachedEnvelope = null
            cachedEnvelopeEncoding = null
            cacheLoaded = true
        }
        return deleted
    }

    override fun loadEnvelopeAccountID(): String? =
        preferences.getString(KEY_ENVELOPE_ACCOUNT, null)

    override fun saveEnvelopeAccountID(accountID: String?): Boolean = preferences.edit().apply {
        if (accountID == null) remove(KEY_ENVELOPE_ACCOUNT) else putString(KEY_ENVELOPE_ACCOUNT, accountID)
    }.commit()

    override fun loadUploadedAtEpochSeconds(): Long? =
        if (preferences.contains(KEY_UPLOADED_AT)) preferences.getLong(KEY_UPLOADED_AT, 0L) else null

    override fun recordUploadedAtEpochSeconds(value: Long): Boolean =
        preferences.edit().putLong(KEY_UPLOADED_AT, value).commit()

    override fun clearUploadMarker(): Boolean = preferences.edit().remove(KEY_UPLOADED_AT).commit()

    override fun loadAutomaticEnabled(): Boolean =
        preferences.getBoolean(KEY_AUTOMATIC_ENABLED, false)

    override fun saveAutomaticEnabled(enabled: Boolean): Boolean =
        preferences.edit().putBoolean(KEY_AUTOMATIC_ENABLED, enabled).commit()

    override fun read(): BackupDeletionRead {
        val encoded = preferences.getString(KEY_DELETION_INTENT, null) ?: return BackupDeletionRead.Absent
        return try {
            BackupDeletionRead.Present(BackupDeletionIntentCodec.decode(encoded))
        } catch (_: Exception) {
            BackupDeletionRead.Unreadable
        }
    }

    override fun write(intent: BackupDeletionIntent): Boolean =
        preferences.edit().putString(KEY_DELETION_INTENT, BackupDeletionIntentCodec.encode(intent)).commit()

    override fun clear(): Boolean = preferences.edit().remove(KEY_DELETION_INTENT).commit()

    private companion object {
        const val PREFERENCES_NAME = "lavasec.backup"
        const val KEY_ENVELOPE = "envelope"
        const val KEY_ENVELOPE_ACCOUNT = "envelopeAccount"
        const val KEY_UPLOADED_AT = "uploadedAtEpochSeconds"
        const val KEY_DELETION_INTENT = "deletionIntent"
        const val KEY_AUTOMATIC_ENABLED = "automaticEnabled"

        /** Reserved `user_id` for the on-device record; never a server value. */
        const val LOCAL_IDENTITY = "device-local"
    }
}

/**
 * The device-retained backup sealing key, stored as a Keystore-wrapped item (the same
 * non-exportable, device-bound, credential-protected posture as the chained-upstream keys).
 *
 * The secret is a random Base64 string; it never leaves the device and is distinct from the user's
 * recovery phrase, so automatic/manual re-sealing needs no user prompt and no phrase retention.
 * [save] replaces any prior key (a re-setup rotates it) because the old key only opened the old
 * envelope, which [saveEnvelope] overwrites in the same setup.
 */
internal class KeyItemBackupSealingKeyStore(
    private val items: ChainedUpstreamKeyItemStore,
) : BackupSealingKeyStore {
    override fun load(): CharArray? = runCatching {
        val bytes = items.load(ACCOUNT) ?: return@runCatching null
        var decoded: CharBuffer? = null
        try {
            // Preserve String's prior UTF-8 replacement semantics without an immutable secret
            // String. The returned array belongs to the caller; every conversion copy belongs here.
            // UTF-8 cannot produce more UTF-16 code units than input bytes. Allocate once so the
            // convenience decoder cannot grow and abandon an earlier plaintext scratch array.
            val characters = CharBuffer.allocate(bytes.size)
            decoded = characters
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            check(decoder.decode(ByteBuffer.wrap(bytes), characters, true).isUnderflow)
            check(decoder.flush(characters).isUnderflow)
            (characters as Buffer).flip()
            CharArray(characters.remaining()).also { characters.get(it) }
        } finally {
            bytes.fill(0)
            decoded?.array()?.fill('\u0000')
        }
    }.getOrNull()

    override fun save(key: CharArray): Boolean = runCatching {
        items.delete(ACCOUNT)
        var encoded: ByteBuffer? = null
        var bytes: ByteArray? = null
        try {
            // The encoder borrows the caller's CharArray. Avoid concatToString(), whose plaintext
            // cannot be cleared, and retain the exact historical UTF-8 wire/replacement behavior.
            // Three bytes per UTF-16 code unit covers all UTF-8 input (including surrogate pairs)
            // and replacement bytes, without an encoder growth copy retaining plaintext.
            val buffer = ByteBuffer.allocate(Math.multiplyExact(key.size, 3))
            encoded = buffer
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            check(encoder.encode(CharBuffer.wrap(key), buffer, true).isUnderflow)
            check(encoder.flush(buffer).isUnderflow)
            (buffer as Buffer).flip()
            val value = ByteArray(buffer.remaining()).also { buffer.get(it) }
            bytes = value
            items.add(value, ACCOUNT)
        } finally {
            // add() borrows this input synchronously; refusal and exceptions spend it too.
            bytes?.fill(0)
            encoded?.array()?.fill(0)
        }
    }.getOrDefault(false)

    override fun clear(): Boolean = runCatching { items.delete(ACCOUNT) }.getOrDefault(false)

    internal companion object {
        /** A reserved account name; never a chained generation, so key sweeps ignore it. */
        const val ACCOUNT = "lavasec.backup.sealing-key"
    }
}
