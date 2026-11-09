package com.lavasec.app.foundation.backup

import org.junit.Assert.*
import org.junit.Test

class BackupEnvelopeUpdateTest {
    @Test fun `restored device can update payload while preserving portable unlock paths`() {
        val password = "portable password".toCharArray()
        val oldDevice = "old device secret".toCharArray()
        val newDevice = "new device secret".toCharArray()
        val original = BackupEnvelopeCrypto.seal("old settings".toByteArray(), mapOf("password" to password, "keychain" to oldDevice), 1000, iterations = 8)
        val adopted = BackupEnvelopeCrypto.rekeyDeviceSlot(original, "password", password, newDevice)
        assertEquals(original.payloadCiphertext, adopted.payloadCiphertext)
        assertEquals(original.keySlots.first { it.kind == "password" }, adopted.keySlots.first { it.kind == "password" })
        assertTrue(runCatching { BackupEnvelopeCrypto.unlock(adopted, "keychain", oldDevice) }.isFailure)
        val updated = BackupEnvelopeCrypto.reseal(adopted, "new settings".toByteArray(), newDevice)
        assertEquals(adopted.keySlots, updated.keySlots)
        assertEquals(original.createdAtEpochSeconds, updated.createdAtEpochSeconds)
        assertArrayEquals("new settings".toByteArray(), BackupEnvelopeCrypto.unlock(updated, "password", password))
        assertArrayEquals("new settings".toByteArray(), BackupEnvelopeCrypto.unlock(updated, "keychain", newDevice))
    }
    @Test fun `PRF restoration adds local device access without requiring original device secret`() {
        val prf = ByteArray(32) { it.toByte() }
        val material = BackupPasskeyMaterial(prf, ByteArray(32) { (it + 1).toByte() }, "credential-fixture")
        val original = material.use { BackupEnvelopeCrypto.seal("settings".toByteArray(), emptyMap(), 1000, passkey = it) }
        val device = "device secret".toCharArray()
        val adopted = BackupEnvelopeCrypto.rekeyDeviceSlotWithPasskey(original, prf, device)
        assertArrayEquals("settings".toByteArray(), BackupEnvelopeCrypto.unlock(adopted, "keychain", device))
        assertArrayEquals("settings".toByteArray(), BackupEnvelopeCrypto.unlockWithPasskeyPrf(adopted, prf))
    }
    @Test fun `tampered old ciphertext cannot be laundered through update or device rekey`() {
        val device = "device secret".toCharArray()
        val original = BackupEnvelopeCrypto.seal("settings".toByteArray(), mapOf("keychain" to device), 1000, iterations = 8)
        val bytes = java.util.Base64.getDecoder().decode(original.payloadCiphertext)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = original.copy(payloadCiphertext = java.util.Base64.getEncoder().encodeToString(bytes))
        assertTrue(runCatching { BackupEnvelopeCrypto.reseal(tampered, "new".toByteArray(), device) }.isFailure)
        assertTrue(runCatching { BackupEnvelopeCrypto.rekeyDeviceSlot(tampered, "keychain", device, "other".toCharArray()) }.isFailure)
    }
}
