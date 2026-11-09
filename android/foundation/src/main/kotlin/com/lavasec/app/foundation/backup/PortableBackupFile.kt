package com.lavasec.app.foundation.backup

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Android portable file version 1. Identical in Play and FOSS; it contains no application ID,
 * account binding or device custody. Payload fields use [BackupPayloadJson], encrypted by the
 * existing reviewed envelope. Only independently portable password/recovery slots are admitted.
 * Files are uncompressed and bounded before parsing, KDF work, or allocating plaintext.
 */
object PortableBackupFile {
    const val FORMAT = "lavasec-android-portable-backup"
    const val VERSION = 1
    const val MIME_TYPE = "application/vnd.lavasec.backup+json"
    const val MAX_FILE_BYTES = 262_144
    const val MAX_PLAINTEXT_BYTES = 180_000
    private val portableKinds = setOf("password", "recoveryPhrase")

    fun seal(
        payload: BackupConfigurationPayload,
        kind: String,
        secret: CharArray,
        createdAtEpochSeconds: Long,
    ): ByteArray {
        require(kind in portableKinds)
        val plaintext = BackupPayloadJson.encode(payload).toByteArray(Charsets.UTF_8)
        try {
            if (plaintext.size > MAX_PLAINTEXT_BYTES) throw PortableBackupSizeException()
            require(plaintext.isNotEmpty())
            // Validate exactly what will be exported using the existing source/resolver validators.
            BackupPayloadMapper.restore(payload)
            return encode(BackupEnvelopeCrypto.seal(plaintext, mapOf(kind to secret), createdAtEpochSeconds))
        } finally { plaintext.fill(0) }
    }

    fun encode(envelope: BackupEnvelope): ByteArray {
        validate(envelope)
        fun str(value: String) = BackupJsonValue.JsonString(value)
        fun num(value: Number) = BackupJsonValue.JsonNumber(value.toString())
        val root = BackupJsonValue.JsonObject(mapOf(
            "format" to str(FORMAT), "formatVersion" to num(VERSION),
            "envelope" to BackupJsonValue.JsonObject(mapOf(
                "schemaVersion" to num(envelope.schemaVersion), "envelopeVersion" to num(envelope.envelopeVersion),
                "cipher" to str(envelope.cipher), "payloadCiphertext" to str(envelope.payloadCiphertext),
                "ciphertextByteSize" to num(envelope.ciphertextByteSize), "createdAtEpochSeconds" to num(envelope.createdAtEpochSeconds),
                "keySlots" to BackupJsonValue.JsonArray(envelope.keySlots.map { slot ->
                    BackupJsonValue.JsonObject(mapOf("kind" to str(slot.kind), "kdf" to str(slot.kdf),
                        "salt" to str(slot.salt), "iterations" to num(slot.iterations), "wrappedKey" to str(slot.wrappedKey)))
                }),
            )),
        ))
        return BackupJsonCodec.print(root).toByteArray(Charsets.UTF_8).also { if (it.size > MAX_FILE_BYTES) throw PortableBackupSizeException() }
    }

    fun decode(file: ByteArray): BackupEnvelope {
        require(file.size in 1..MAX_FILE_BYTES) { "Portable backup exceeds file limit" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file)).toString()
        val root = obj(BackupJsonCodec.parse(text), setOf("format", "formatVersion", "envelope"))
        require(string(root, "format") == FORMAT) { "Not a Lava Android portable backup" }
        val version = int(root, "formatVersion")
        if (version != VERSION) throw PortableBackupVersionException(version)
        val envelope = obj(root.getValue("envelope"), setOf("schemaVersion", "envelopeVersion", "cipher",
            "payloadCiphertext", "ciphertextByteSize", "createdAtEpochSeconds", "keySlots"))
        val slots = (envelope.getValue("keySlots") as? BackupJsonValue.JsonArray)?.values
            ?: throw IllegalArgumentException("Invalid key slots")
        require(slots.size in 1..2)
        return BackupEnvelope(
            schemaVersion = int(envelope, "schemaVersion"), envelopeVersion = int(envelope, "envelopeVersion"),
            cipher = string(envelope, "cipher"), payloadCiphertext = string(envelope, "payloadCiphertext"),
            ciphertextByteSize = int(envelope, "ciphertextByteSize"),
            createdAtEpochSeconds = (envelope.getValue("createdAtEpochSeconds") as? BackupJsonValue.JsonNumber)
                ?.raw?.toLongOrNull() ?: throw IllegalArgumentException("Invalid creation date"),
            keySlots = slots.map { value ->
                val slot = obj(value, setOf("kind", "kdf", "salt", "iterations", "wrappedKey"))
                BackupKeySlot(string(slot, "kind"), string(slot, "kdf"), string(slot, "salt"),
                    int(slot, "iterations"), string(slot, "wrappedKey"))
            },
        ).also(::validate)
    }

    fun unlock(envelope: BackupEnvelope, kind: String, secret: CharArray): BackupRestoreUnlock {
        try { validate(envelope) } catch (_: IllegalArgumentException) { return BackupRestoreUnlock.CorruptPayload }
        require(kind in portableKinds)
        return when (val unlocked = BackupEnvelopeCrypto.unlockOutcome(envelope, kind, secret)) {
            BackupUnlockOutcome.WrongSecret -> BackupRestoreUnlock.WrongSecret
            BackupUnlockOutcome.CorruptPayload -> BackupRestoreUnlock.CorruptPayload
            is BackupUnlockOutcome.Unlocked -> try {
                require(unlocked.payload.size <= MAX_PLAINTEXT_BYTES)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(unlocked.payload)).toString()
                BackupRestoreUnlock.Unlocked(BackupPayloadJson.decode(text))
            } catch (future: BackupSchemaUnsupportedException) {
                BackupRestoreUnlock.UnsupportedSchema(future.schemaVersion)
            } catch (_: Exception) {
                BackupRestoreUnlock.CorruptPayload
            } finally { unlocked.payload.fill(0) }
        }
    }

    private fun validate(envelope: BackupEnvelope) {
        BackupEnvelopeCrypto.validate(envelope)
        require(envelope.createdAtEpochSeconds >= 0)
        require(envelope.ciphertextByteSize <= MAX_PLAINTEXT_BYTES + 28)
        require(envelope.serverRecoveryShare == null && envelope.keySlots.size in 1..2)
        require(envelope.keySlots.all {
            it.kind in portableKinds && it.credentialID == null && it.kdf == "PBKDF2-HMAC-SHA256" &&
                it.iterations in BackupEnvelopeCrypto.DEFAULT_ITERATIONS..BackupEnvelopeCrypto.MAX_ITERATIONS
        })
    }

    private fun obj(value: BackupJsonValue, keys: Set<String>): Map<String, BackupJsonValue> {
        val members = (value as? BackupJsonValue.JsonObject)?.members ?: throw IllegalArgumentException("Invalid object")
        require(members.keys == keys) { "Unexpected portable file fields" }
        return members
    }
    private fun string(obj: Map<String, BackupJsonValue>, key: String): String =
        (obj.getValue(key) as? BackupJsonValue.JsonString)?.value ?: throw IllegalArgumentException("Invalid $key")
    private fun int(obj: Map<String, BackupJsonValue>, key: String): Int =
        (obj.getValue(key) as? BackupJsonValue.JsonNumber)?.raw?.toIntOrNull() ?: throw IllegalArgumentException("Invalid $key")
}

class PortableBackupVersionException(val version: Int) : IllegalArgumentException("Unsupported portable backup version")

/** Export cannot represent this complete configuration within the portable size bound. */
class PortableBackupSizeException : IllegalArgumentException("Portable backup exceeds size limit")
