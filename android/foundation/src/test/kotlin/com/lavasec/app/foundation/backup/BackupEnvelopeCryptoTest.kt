package com.lavasec.app.foundation.backup

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class BackupEnvelopeCryptoTest {
    private val payload = Base64.getDecoder().decode("eyJzY2hlbWFWZXJzaW9uIjoyLCJhbGxvd2VkRG9tYWlucyI6WyJleGFtcGxlLmNvbSJdfQ==")
    private val password = "pässwörd 🔒".toCharArray()

    @Test fun `CryptoKit and CommonCrypto password fixture decrypts with non ASCII secret`() {
        assertArrayEquals(payload, BackupEnvelopeCrypto.unlock(iosFixture(), "password", password))
    }

    @Test fun `CryptoKit HKDF passkey fixture decrypts`() {
        val fixture = iosFixture().copy(keySlots = listOf(BackupKeySlot("passkey", "HKDF-SHA256", SALT, 0,
            "gIGCg4SFhoeIiYqL0iuYIDBmxUjfkqbcpx8eukYFMjLVJAzvuNq/hQN+zfPAPnKhmHAesgvgDBesOe2v", "fixture-credential")))
        assertArrayEquals(payload, BackupEnvelopeCrypto.unlockWithPasskeyPrf(fixture,
            Base64.getDecoder().decode("YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8=")))
    }

    @Test fun `independent slots wrap one random payload key`() {
        val secret = "device secret".toCharArray()
        val envelope = BackupEnvelopeCrypto.seal(payload, mapOf("password" to password, "keychain" to secret), 1, iterations = 8)
        assertArrayEquals(payload, BackupEnvelopeCrypto.unlock(envelope, "password", password))
        // Public test data for the independent CryptoKit verification script; never a real backup.
        val fixture = java.io.File("build/reports/backup-android-fixture.properties")
        fixture.parentFile.mkdirs()
        val slot = envelope.keySlots.first { it.kind == "password" }
        fixture.writeText(listOf(
            "password=" + Base64.getEncoder().encodeToString(String(password).toByteArray(Charsets.UTF_8)),
            "payload=" + Base64.getEncoder().encodeToString(payload),
            "salt=" + slot.salt, "wrappedKey=" + slot.wrappedKey,
            "ciphertext=" + envelope.payloadCiphertext, "iterations=" + slot.iterations,
        ).joinToString("\n"))
        assertArrayEquals(payload, BackupEnvelopeCrypto.unlock(envelope, "keychain", secret))
        assertNotEquals(envelope.payloadCiphertext,
            BackupEnvelopeCrypto.seal(payload, mapOf("password" to password), 1, iterations = 8).payloadCiphertext)
    }

    @Test fun `native PRF material creates a portable slot and cannot be reused after close`() {
        val output = ByteArray(32) { it.toByte() }
        val material = BackupPasskeyMaterial(output, ByteArray(32) { 1 }, "credential")
        val envelope = BackupEnvelopeCrypto.seal(payload, emptyMap(), 1, passkey = material)
        assertArrayEquals(payload, BackupEnvelopeCrypto.unlockWithPasskeyPrf(envelope, output))
        material.close()
        assertTrue(runCatching { BackupEnvelopeCrypto.seal(payload, emptyMap(), 1, passkey = material) }.isFailure)
    }

    @Test fun `wrong secret and tampered ciphertext never produce plaintext`() {
        val fixture = iosFixture()
        assertTrue(runCatching { BackupEnvelopeCrypto.unlock(fixture, "password", "wrong".toCharArray()) }.isFailure)
        val bytes = Base64.getDecoder().decode(fixture.payloadCiphertext)
        bytes[13] = (bytes[13].toInt() xor 1).toByte()
        val corrupted = fixture.copy(payloadCiphertext = Base64.getEncoder().encodeToString(bytes))
        assertTrue(runCatching { BackupEnvelopeCrypto.unlock(corrupted, "password", password) }.isFailure)
    }

    @Test fun `unlock outcome distinguishes a wrong secret from a damaged envelope`() {
        val fixture = iosFixture()
        val unlocked = BackupEnvelopeCrypto.unlockOutcome(fixture, "password", password)
        assertTrue(unlocked is BackupUnlockOutcome.Unlocked)
        assertArrayEquals(payload, (unlocked as BackupUnlockOutcome.Unlocked).payload)

        assertEquals(
            BackupUnlockOutcome.WrongSecret,
            BackupEnvelopeCrypto.unlockOutcome(fixture, "password", "wrong".toCharArray()),
        )

        // A valid envelope whose payload ciphertext is damaged is corrupt, not a wrong secret.
        val bytes = Base64.getDecoder().decode(fixture.payloadCiphertext)
        bytes[13] = (bytes[13].toInt() xor 1).toByte()
        val corruptedPayload = fixture.copy(payloadCiphertext = Base64.getEncoder().encodeToString(bytes))
        assertEquals(
            BackupUnlockOutcome.CorruptPayload,
            BackupEnvelopeCrypto.unlockOutcome(corruptedPayload, "password", password),
        )

        // A damaged wrapped key is indistinguishable from a wrong secret (both fail the AEAD tag on
        // the unwrap), so it is reported as a wrong secret rather than fabricated as corruption.
        val wrapped = Base64.getDecoder().decode(fixture.keySlots.single().wrappedKey)
        wrapped[13] = (wrapped[13].toInt() xor 1).toByte()
        val corruptedKey = fixture.copy(
            keySlots = listOf(fixture.keySlots.single().copy(wrappedKey = Base64.getEncoder().encodeToString(wrapped))),
        )
        assertEquals(
            BackupUnlockOutcome.WrongSecret,
            BackupEnvelopeCrypto.unlockOutcome(corruptedKey, "password", password),
        )

        // Unvalidatable metadata never decrypts and is reported as damage.
        assertEquals(
            BackupUnlockOutcome.CorruptPayload,
            BackupEnvelopeCrypto.unlockOutcome(fixture.copy(cipher = "AES-CBC"), "password", password),
        )
        // A missing slot is a wrong secret for this method, not damage.
        assertEquals(
            BackupUnlockOutcome.WrongSecret,
            BackupEnvelopeCrypto.unlockOutcome(fixture, "keychain", password),
        )
    }

    @Test fun `untrusted metadata rejects excessive work malformed slots and future versions`() {
        val fixture = iosFixture()
        val invalid = listOf(
            fixture.copy(envelopeVersion = 2), fixture.copy(cipher = "AES-CBC"),
            fixture.copy(ciphertextByteSize = 1), fixture.copy(keySlots = fixture.keySlots + fixture.keySlots),
            fixture.copy(keySlots = listOf(fixture.keySlots.single().copy(iterations = 1_000_001))),
            fixture.copy(keySlots = listOf(fixture.keySlots.single().copy(salt = ""))),
        )
        for (item in invalid) assertTrue(runCatching { BackupEnvelopeCrypto.unlock(item, "password", password) }.isFailure)
    }

    @Test fun `assisted recovery uses iOS numbered and punctuated phrase normalization`() {
        assertArrayEquals(BackupEnvelopeCrypto.assistedRecoverySecret("baba keke", "share"),
            BackupEnvelopeCrypto.assistedRecoverySecret("1. BABA, 2) KEKE", "share"))
        assertFalse(BackupEnvelopeCrypto.assistedRecoverySecret("baba keke", "share")
            .contentEquals(BackupEnvelopeCrypto.assistedRecoverySecret("baba keke", "other-share")))
    }

    private fun iosFixture() = BackupEnvelope(
        payloadCiphertext = "UFFSU1RVVldYWVpbyoLbcVlKGmxianhpBGbtjebeYx0ZmhVv1Zm8Ht9nQVCPtWTiPQPcCkT/HCqK7cy1lnJnM0j1HusDlNwLuiHH0b8bhBI=",
        keySlots = listOf(BackupKeySlot("password", "PBKDF2-HMAC-SHA256", SALT, 8,
            "QEFCQ0RFRkdISUpLalOBMLYCP9HwctzV67cO0C5lhkSecuY5HislXL9F1Xnz0Psg1cf71IUKlADhR3PW")),
        ciphertextByteSize = 80, createdAtEpochSeconds = 1,
    )
    companion object { private const val SALT = "AAECAwQFBgcICQoLDA0ODw==" }
}
