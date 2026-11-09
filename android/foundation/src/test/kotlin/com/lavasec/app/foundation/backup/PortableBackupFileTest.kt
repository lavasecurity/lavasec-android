package com.lavasec.app.foundation.backup

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PortableBackupFileTest {
    private val payload = BackupConfigurationPayload(resolverPresetID = "cloudflare-1111",
        blockedDomains = setOf("blocked.example"), allowedDomains = setOf("allowed.example"))
    private val secret = "portable-password1!".toCharArray()

    @Test fun `password file restores independently of edition account device and server`() {
        val file = PortableBackupFile.seal(payload, "password", secret, 1)
        val text = file.toString(Charsets.UTF_8)
        for (excluded in listOf("user_id", "accountID", "serverRecoveryShare", "keychain", "passkey", "credentialID")) {
            assertFalse(excluded, text.contains(excluded))
        }
        val unlocked = PortableBackupFile.unlock(PortableBackupFile.decode(file), "password", secret)
        assertEquals(payload, (unlocked as BackupRestoreUnlock.Unlocked).payload)
    }

    @Test fun `recovery file uses the same portable schema and existing cryptography`() {
        val phrase = BackupRecoveryPhrase.generate().toCharArray()
        val file = PortableBackupFile.seal(payload, "recoveryPhrase", phrase, 1)
        val envelope = PortableBackupFile.decode(file)
        assertEquals(listOf("recoveryPhrase"), envelope.keySlots.map { it.kind })
        val unlocked = PortableBackupFile.unlock(envelope, "recoveryPhrase", phrase)
        assertEquals(payload, (unlocked as BackupRestoreUnlock.Unlocked).payload)
        phrase.fill('\u0000')
    }

    @Test fun `wrong password and authenticated corruption refuse before prepare`() {
        val envelope = PortableBackupFile.decode(PortableBackupFile.seal(payload, "password", secret, 1))
        assertEquals(BackupRestoreUnlock.WrongSecret,
            PortableBackupFile.unlock(envelope, "password", "wrong".toCharArray()))
        val ciphertext = Base64.getDecoder().decode(envelope.payloadCiphertext)
        ciphertext[12] = (ciphertext[12].toInt() xor 1).toByte()
        assertEquals(BackupRestoreUnlock.CorruptPayload, PortableBackupFile.unlock(
            envelope.copy(payloadCiphertext = Base64.getEncoder().encodeToString(ciphertext)), "password", secret))
    }

    @Test fun `file limits nesting duplicate fields unknown fields and UTF8 are bounded`() {
        assertThrows(IllegalArgumentException::class.java) { PortableBackupFile.decode(ByteArray(PortableBackupFile.MAX_FILE_BYTES + 1)) }
        assertThrows(Exception::class.java) { PortableBackupFile.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        val file = PortableBackupFile.seal(payload, "password", secret, 1).toString(Charsets.UTF_8)
        val malicious = listOf(
            file.replaceFirst("{", "{\"unexpected\":true,"),
            file.replaceFirst("{", "{\"formatVersion\":1,"),
            "[".repeat(40) + "0" + "]".repeat(40),
            file.replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
        )
        malicious.forEach { candidate -> assertThrows(IllegalArgumentException::class.java) { PortableBackupFile.decode(candidate.toByteArray()) } }
        assertThrows(PortableBackupVersionException::class.java) {
            PortableBackupFile.decode(file.replace("\"formatVersion\":1", "\"formatVersion\":2").toByteArray())
        }
    }

    @Test fun `device server passkey slots and excessive KDF work are rejected before unlock`() {
        val envelope = PortableBackupFile.decode(PortableBackupFile.seal(payload, "password", secret, 1))
        val slot = envelope.keySlots.single()
        val invalid = listOf(
            envelope.copy(serverRecoveryShare = "server-share"),
            envelope.copy(keySlots = listOf(slot.copy(kind = "keychain"))),
            envelope.copy(keySlots = listOf(slot.copy(kind = "assistedRecovery"))),
            envelope.copy(keySlots = listOf(slot.copy(kind = "passkey"))),
            envelope.copy(keySlots = listOf(slot.copy(iterations = 1_000_001))),
            envelope.copy(keySlots = listOf(slot.copy(iterations = 8))),
            envelope.copy(keySlots = listOf(slot, slot)),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { PortableBackupFile.encode(it) } }
    }

    @Test fun `authenticated future payload refuses rather than applying a downgraded subset`() {
        val envelope = BackupEnvelopeCrypto.seal("{\"schemaVersion\":999}".toByteArray(),
            mapOf("password" to secret), 1)
        val file = PortableBackupFile.encode(envelope)
        assertEquals(BackupRestoreUnlock.UnsupportedSchema(999),
            PortableBackupFile.unlock(PortableBackupFile.decode(file), "password", secret))
    }

    @Test fun `legacy schema one custom source URLs survive portable migration`() {
        val source = BackupCustomBlocklistSource(id = "custom-source", displayName = "My source",
            sourceURL = "https://example.com/list.txt")
        val legacy = payload.copy(customBlocklists = listOf(source))
        val file = PortableBackupFile.seal(legacy, "password", secret, 1)
        val decoded = (PortableBackupFile.unlock(PortableBackupFile.decode(file), "password", secret) as BackupRestoreUnlock.Unlocked).payload
        val restored = BackupPayloadMapper.restore(decoded).library.profiles.single().customBlocklists.single()
        assertEquals(source.id, restored.id)
        assertEquals(source.sourceURL, restored.sourceUrl)
    }

    @Test fun `source URL validation rejects credential URLs before export`() {
        val bad = payload.copy(customBlocklists = listOf(BackupCustomBlocklistSource(
            id = "untrusted", displayName = "untrusted", sourceURL = "https://user:password@example.com/list.txt")))
        assertThrows(IllegalArgumentException::class.java) { PortableBackupFile.seal(bad, "password", secret, 1) }
    }

    @Test fun `oversized complete configuration has a dedicated export size refusal without truncation`() {
        val domains = (0 until 8_000).map { "domain-$it.backup-bound.example" }.toSet()
        val oversized = payload.copy(blockedDomains = domains)
        assertTrue(BackupPayloadJson.encode(oversized).toByteArray(Charsets.UTF_8).size > PortableBackupFile.MAX_PLAINTEXT_BYTES)
        assertThrows(PortableBackupSizeException::class.java) {
            PortableBackupFile.seal(oversized, "password", secret, 1)
        }
        assertEquals(domains, oversized.blockedDomains)
        // A size refusal cannot make a subsequent valid export inherit a partial payload.
        val unlocked = PortableBackupFile.unlock(PortableBackupFile.decode(
            PortableBackupFile.seal(payload, "password", secret, 2)), "password", secret)
        assertEquals(payload, (unlocked as BackupRestoreUnlock.Unlocked).payload)
    }
}
