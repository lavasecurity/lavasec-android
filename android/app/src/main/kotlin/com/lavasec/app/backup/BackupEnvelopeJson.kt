package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import com.lavasec.app.foundation.backup.BackupKeySlot
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** The existing iOS UserBackupUpsert/BackupEnvelopeMetadata wire format, not a new Android schema. */
internal object BackupEnvelopeJson {
    fun upload(envelope: BackupEnvelope, userId: String): JSONObject {
        BackupEnvelopeCrypto.validate(envelope)
        val slots = JSONArray()
        envelope.keySlots.forEach { slot ->
            slots.put(JSONObject().put("kind", slot.kind).put("kdf", slot.kdf).put("salt", slot.salt)
                .put("iterations", slot.iterations).put("wrappedKey", slot.wrappedKey).put("credentialID", slot.credentialID))
        }
        val metadata = JSONObject().put("cipher", envelope.cipher).put("key_slots", slots)
            .put("server_recovery_share", envelope.serverRecoveryShare)
            .put("created_at", Instant.ofEpochSecond(envelope.createdAtEpochSeconds).toString())
        require(metadata.toString().toByteArray(Charsets.UTF_8).size <= 32768)
        return JSONObject().put("user_id", userId).put("backup_version", envelope.schemaVersion)
            .put("envelope_version", envelope.envelopeVersion).put("ciphertext", envelope.payloadCiphertext)
            .put("metadata", metadata).put("ciphertext_byte_size", envelope.ciphertextByteSize)
            .put("disabled_at", JSONObject.NULL)
    }

    fun decode(row: JSONObject, expectedUserId: String): BackupEnvelope {
        require(row.getString("user_id") == expectedUserId)
        require(row.isNull("disabled_at"))
        val metadata = row.getJSONObject("metadata")
        require(metadata.toString().toByteArray(Charsets.UTF_8).size <= 32768)
        val slots = metadata.getJSONArray("key_slots")
        require(slots.length() in 1..8)
        val envelope = BackupEnvelope(
            schemaVersion = row.getInt("backup_version"), envelopeVersion = row.getInt("envelope_version"),
            cipher = metadata.getString("cipher"), payloadCiphertext = row.getString("ciphertext"),
            keySlots = (0 until slots.length()).map { index ->
                val slot = slots.getJSONObject(index)
                BackupKeySlot(slot.getString("kind"), slot.getString("kdf"), slot.getString("salt"),
                    slot.getInt("iterations"), slot.getString("wrappedKey"), nullableString(slot, "credentialID"))
            },
            ciphertextByteSize = row.getInt("ciphertext_byte_size"),
            createdAtEpochSeconds = Instant.parse(metadata.getString("created_at")).epochSecond,
            serverRecoveryShare = nullableString(metadata, "server_recovery_share"),
        )
        BackupEnvelopeCrypto.validate(envelope)
        return envelope
    }

    private fun nullableString(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.getString(key)
}
