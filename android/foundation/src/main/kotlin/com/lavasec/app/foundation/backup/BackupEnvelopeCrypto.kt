package com.lavasec.app.foundation.backup

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Wire names and AES boxes match iOS ZeroKnowledgeBackupEnvelope version 1. */
data class BackupKeySlot(
    val kind: String,
    val kdf: String,
    val salt: String,
    val iterations: Int,
    val wrappedKey: String,
    val credentialID: String? = null,
)

data class BackupEnvelope(
    val schemaVersion: Int = 1,
    val envelopeVersion: Int = 1,
    val cipher: String = "AES-256-GCM",
    val payloadCiphertext: String,
    val keySlots: List<BackupKeySlot>,
    val ciphertextByteSize: Int,
    val createdAtEpochSeconds: Long,
    val serverRecoveryShare: String? = null,
)

/**
 * The typed outcome of a password/recovery unlock, keeping the two distinct failure causes
 * separate: a secret that fails to unwrap the payload key ([WrongSecret]) versus an envelope whose
 * wrapped key or payload ciphertext fails authentication once the secret is correct
 * ([CorruptPayload]). [unlock] throws for both, so a caller that must distinguish them (a restore
 * surfaces a different user remedy) uses [BackupEnvelopeCrypto.unlockOutcome] instead.
 */
sealed interface BackupUnlockOutcome {
    /** The envelope authenticated and decrypted to this plaintext payload. Caller owns the array. */
    class Unlocked(val payload: ByteArray) : BackupUnlockOutcome

    /** The supplied secret did not unwrap any key slot's payload key. */
    data object WrongSecret : BackupUnlockOutcome

    /** The envelope metadata or authenticated ciphertext is damaged or tampered. */
    data object CorruptPayload : BackupUnlockOutcome
}

/** Native credential-provider result. Never serialize or send the PRF output to the server. */
class BackupPasskeyMaterial(output: ByteArray, salt: ByteArray, val credentialId: String) : AutoCloseable {
    private val output = output.copyOf()
    private val salt = salt.copyOf()
    private var closed = false
    init { require(output.size == 32 && salt.size == 32 && credentialId.length in 1..2048) }
    internal fun <T> withValue(action: (ByteArray, ByteArray) -> T): T = synchronized(this) {
        check(!closed) { "Backup passkey material is closed" }
        action(output, salt)
    }
    /**
     * Borrows the live PRF output and salt for [action]. The arrays are only valid for the duration
     * of the call and are never handed to the caller; use this rather than copying the output out.
     */
    fun <T> usePrf(action: (output: ByteArray, salt: ByteArray) -> T): T = withValue(action)

    override fun close() = synchronized(this) { closed = true; output.fill(0); salt.fill(0) }
    override fun toString() = "BackupPasskeyMaterial(redacted)"
}

/** No plaintext persistence or network I/O. Call off the UI thread; KDF work is bounded. */
object BackupEnvelopeCrypto {
    const val DEFAULT_ITERATIONS = 210_000
    const val MAX_ITERATIONS = 1_000_000
    const val MAX_CIPHERTEXT_BYTES = 262_144 // Same bound as the server's user_backups constraint.
    const val SLOT_PASSKEY = "passkey"
    private const val PBKDF = "PBKDF2-HMAC-SHA256"
    private const val HKDF = "HKDF-SHA256"
    private val kinds = setOf("password", "assistedRecovery", "recoveryPhrase", "keychain", SLOT_PASSKEY)
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    /** A password or device secret may wrap the same random payload key independently. */
    fun seal(
        payload: ByteArray,
        secrets: Map<String, CharArray>,
        createdAtEpochSeconds: Long,
        serverRecoveryShare: String? = null,
        iterations: Int = DEFAULT_ITERATIONS,
        passkey: BackupPasskeyMaterial? = null,
    ): BackupEnvelope {
        require(payload.size in 1..MAX_CIPHERTEXT_BYTES - 28)
        require((secrets.isNotEmpty() || passkey != null) && secrets.size <= kinds.size && secrets.keys.all { it in kinds })
        require(passkey == null || "passkey" !in secrets)
        require("assistedRecovery" !in secrets || !serverRecoveryShare.isNullOrBlank())
        require(iterations in 1..MAX_ITERATIONS)
        val key = randomBytes(32)
        try {
            val ciphertext = encrypt(payload, key)
            val slots = secrets.map { (kind, secret) ->
                val salt = randomBytes(16)
                val wrapping = derive(secret, salt, iterations)
                try { BackupKeySlot(kind, PBKDF, encoder.encodeToString(salt), iterations, encoder.encodeToString(encrypt(key, wrapping))) }
                finally { wrapping.fill(0) }
            }
            val allSlots = slots.toMutableList()
            if (passkey != null) {
                passkey.withValue { output, salt ->
                    val wrapping = derivePrf(output, salt)
                    try {
                        allSlots += BackupKeySlot("passkey", HKDF, encoder.encodeToString(salt), 0,
                            encoder.encodeToString(encrypt(key, wrapping)), passkey.credentialId)
                    } finally { wrapping.fill(0) }
                }
            }
            return BackupEnvelope(payloadCiphertext = encoder.encodeToString(ciphertext), keySlots = allSlots.toList(),
                ciphertextByteSize = ciphertext.size, createdAtEpochSeconds = createdAtEpochSeconds,
                serverRecoveryShare = serverRecoveryShare)
        } finally { key.fill(0) }
    }

    fun unlock(envelope: BackupEnvelope, kind: String, secret: CharArray): ByteArray {
        validate(envelope)
        val slot = envelope.keySlots.singleOrNull { it.kind == kind } ?: error("Backup unlock method unavailable")
        require(slot.kdf == PBKDF && slot.iterations in 1..MAX_ITERATIONS)
        val wrapping = derive(secret, decode(slot.salt, 64), slot.iterations)
        return try { unlockWithWrappingKey(envelope, slot, wrapping) } finally { wrapping.fill(0) }
    }

    /**
     * Password/recovery unlock that reports the distinct failure cause instead of throwing.
     *
     * The wrapped payload key is unwrapped first: a failure there means the secret is wrong (the
     * slot salt is public, so a correct secret always authenticates), while a failure decrypting the
     * payload ciphertext afterwards means the envelope itself is damaged. Envelope metadata that
     * cannot be validated is damage, not a wrong secret.
     */
    fun unlockOutcome(envelope: BackupEnvelope, kind: String, secret: CharArray): BackupUnlockOutcome {
        try {
            validate(envelope)
        } catch (_: IllegalArgumentException) {
            return BackupUnlockOutcome.CorruptPayload
        }
        val slot = envelope.keySlots.singleOrNull { it.kind == kind }
        if (slot == null || slot.kdf != PBKDF || slot.iterations !in 1..MAX_ITERATIONS) {
            return BackupUnlockOutcome.WrongSecret
        }
        val wrapping = try {
            derive(secret, decode(slot.salt, 64), slot.iterations)
        } catch (_: Exception) {
            return BackupUnlockOutcome.WrongSecret
        }
        try {
            val key = try {
                decrypt(decode(slot.wrappedKey, 60), wrapping)
            } catch (_: Exception) {
                return BackupUnlockOutcome.WrongSecret
            }
            try {
                require(key.size == 32)
                return BackupUnlockOutcome.Unlocked(
                    decrypt(decode(envelope.payloadCiphertext, MAX_CIPHERTEXT_BYTES), key),
                )
            } catch (_: Exception) {
                return BackupUnlockOutcome.CorruptPayload
            } finally {
                key.fill(0)
            }
        } finally {
            wrapping.fill(0)
        }
    }

    fun unlockWithPasskeyPrf(envelope: BackupEnvelope, prfOutput: ByteArray): ByteArray {
        validate(envelope)
        require(prfOutput.size == 32)
        val slot = envelope.keySlots.singleOrNull { it.kind == "passkey" } ?: error("Backup passkey unavailable")
        require(slot.kdf == HKDF && slot.iterations == 0)
        val wrapping = derivePrf(prfOutput, decode(slot.salt, 64))
        return try { unlockWithWrappingKey(envelope, slot, wrapping) } finally { wrapping.fill(0) }
    }

    /** Update settings without invalidating any existing password, recovery or passkey slot. */
    fun reseal(envelope: BackupEnvelope, payload: ByteArray, deviceSecret: CharArray): BackupEnvelope {
        require(payload.size in 1..MAX_CIPHERTEXT_BYTES - 28)
        return withPasswordKey(envelope, "keychain", deviceSecret) { key ->
            val encrypted = encrypt(payload, key)
            envelope.copy(payloadCiphertext = encoder.encodeToString(encrypted), ciphertextByteSize = encrypted.size)
        }
    }

    /** Adopt a restored envelope on this device while preserving every portable unlock path. */
    fun rekeyDeviceSlot(envelope: BackupEnvelope, kind: String, secret: CharArray, newDeviceSecret: CharArray): BackupEnvelope =
        withPasswordKey(envelope, kind, secret) { key -> rekeyDeviceSlot(envelope, key, newDeviceSecret) }

    fun rekeyDeviceSlotWithPasskey(envelope: BackupEnvelope, prfOutput: ByteArray, newDeviceSecret: CharArray): BackupEnvelope {
        validate(envelope)
        require(prfOutput.size == 32)
        val slot = envelope.keySlots.singleOrNull { it.kind == "passkey" } ?: error("Backup passkey unavailable")
        require(slot.kdf == HKDF && slot.iterations == 0)
        val wrapping = derivePrf(prfOutput, decode(slot.salt, 64))
        return try { withAuthenticatedPayloadKey(envelope, slot, wrapping) { key -> rekeyDeviceSlot(envelope, key, newDeviceSecret) } }
        finally { wrapping.fill(0) }
    }

    private fun rekeyDeviceSlot(envelope: BackupEnvelope, key: ByteArray, secret: CharArray): BackupEnvelope {
        val iterations = envelope.keySlots.singleOrNull { it.kind == "keychain" }?.iterations ?: DEFAULT_ITERATIONS
        val salt = randomBytes(16)
        val wrapping = derive(secret, salt, iterations)
        val slot = try { BackupKeySlot("keychain", PBKDF, encoder.encodeToString(salt), iterations, encoder.encodeToString(encrypt(key, wrapping))) }
        finally { wrapping.fill(0) }
        return envelope.copy(keySlots = listOf(slot) + envelope.keySlots.filter { it.kind != "keychain" })
    }

    private fun <T> withPasswordKey(envelope: BackupEnvelope, kind: String, secret: CharArray, action: (ByteArray) -> T): T {
        validate(envelope)
        val slot = envelope.keySlots.singleOrNull { it.kind == kind } ?: error("Backup unlock method unavailable")
        require(slot.kdf == PBKDF)
        val wrapping = derive(secret, decode(slot.salt, 64), slot.iterations)
        return try { withAuthenticatedPayloadKey(envelope, slot, wrapping, action) } finally { wrapping.fill(0) }
    }

    private fun <T> withAuthenticatedPayloadKey(envelope: BackupEnvelope, slot: BackupKeySlot, wrapping: ByteArray, action: (ByteArray) -> T): T {
        val key = decrypt(decode(slot.wrappedKey, 60), wrapping)
        try {
            require(key.size == 32)
            // Authenticate the old payload too: never turn a damaged envelope into a valid update.
            decrypt(decode(envelope.payloadCiphertext, MAX_CIPHERTEXT_BYTES), key).fill(0)
            return action(key)
        } finally { key.fill(0) }
    }

    /** Passwordless recovery hashes the normalized phrase plus server-held share exactly as iOS. */
    fun assistedRecoverySecret(phrase: String, serverShare: String): CharArray {
        require(phrase.length <= 4096 && serverShare.length in 1..4096)
        val normalized = phrase.replace(Regex("\\b\\d{1,2}[.)]"), " ")
            .replace(Regex("[-_,;:\n\r\t]+"), " ").trim().lowercase(java.util.Locale.ROOT)
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val input = "LavaSec assisted recovery v1\u0000$serverShare\u0000$normalized".toByteArray(Charsets.UTF_8)
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(input)).toCharArray()
        } finally { input.fill(0) }
    }

    fun validate(envelope: BackupEnvelope) {
        require(envelope.schemaVersion == 1 && envelope.envelopeVersion == 1 && envelope.cipher == "AES-256-GCM")
        require(envelope.ciphertextByteSize in 29..MAX_CIPHERTEXT_BYTES)
        require(decode(envelope.payloadCiphertext, MAX_CIPHERTEXT_BYTES).size == envelope.ciphertextByteSize)
        require(envelope.keySlots.size in 1..8)
        require(envelope.keySlots.map { it.kind }.distinct().size == envelope.keySlots.size)
        for (slot in envelope.keySlots) {
            require(slot.kind in kinds)
            require(decode(slot.salt, 64).size in 16..64)
            require(decode(slot.wrappedKey, 60).size == 60)
            require(slot.credentialID == null || slot.credentialID.length in 1..2048)
            require((slot.kdf == PBKDF && slot.iterations in 1..MAX_ITERATIONS) ||
                (slot.kind == "passkey" && slot.kdf == HKDF && slot.iterations == 0))
        }
        require(envelope.serverRecoveryShare == null || envelope.serverRecoveryShare.length in 1..4096)
    }

    private fun unlockWithWrappingKey(envelope: BackupEnvelope, slot: BackupKeySlot, wrapping: ByteArray): ByteArray {
        val key = decrypt(decode(slot.wrappedKey, 60), wrapping)
        try {
            require(key.size == 32)
            return decrypt(decode(envelope.payloadCiphertext, MAX_CIPHERTEXT_BYTES), key)
        } finally { key.fill(0) }
    }

    private fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(secret.size in 1..4096 && iterations in 1..MAX_ITERATIONS)
        val spec = PBEKeySpec(secret, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun derivePrf(input: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val extracted = mac.doFinal(input)
        return try {
            mac.init(SecretKeySpec(extracted, "HmacSHA256"))
            mac.doFinal("LavaSec passkey backup PRF v1".toByteArray(Charsets.UTF_8) + byteArrayOf(1))
        } finally { extracted.fill(0) }
    }

    private fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    private fun decrypt(combined: ByteArray, key: ByteArray): ByteArray {
        require(combined.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        return cipher.doFinal(combined, 12, combined.size - 12)
    }

    private fun decode(value: String, limit: Int): ByteArray {
        require(value.length <= ((limit + 2) / 3) * 4)
        val result = decoder.decode(value)
        require(result.size <= limit && encoder.encodeToString(result) == value)
        return result
    }
    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
