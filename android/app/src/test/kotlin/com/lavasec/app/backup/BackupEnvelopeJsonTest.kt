package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupEnvelopeCrypto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupEnvelopeJsonTest {
    @Test fun `Supabase row follows existing iOS envelope metadata and recovers payload`() {
        val secret = "fixture password".toCharArray()
        val payload = "{\"schemaVersion\":2}".toByteArray()
        val envelope = BackupEnvelopeCrypto.seal(payload, mapOf("password" to secret), 1_000, iterations = 8)
        val row = BackupEnvelopeJson.upload(envelope, USER)
        assertEquals(1, row.getInt("backup_version"))
        assertEquals("1970-01-01T00:16:40Z", row.getJSONObject("metadata").getString("created_at"))
        assertTrue(row.getJSONObject("metadata").has("key_slots"))
        assertFalse(row.toString().contains("fixture password"))
        assertFalse(row.toString().contains("schemaVersion"))
        assertArrayEquals(payload, BackupEnvelopeCrypto.unlock(BackupEnvelopeJson.decode(row, USER), "password", secret))
    }

    @Test fun `foreign-account disabled and future-version rows cannot restore`() {
        val envelope = BackupEnvelopeCrypto.seal("{}".toByteArray(), mapOf("password" to "password".toCharArray()), 1000, iterations = 8)
        val row = BackupEnvelopeJson.upload(envelope, USER)
        assertTrue(runCatching { BackupEnvelopeJson.decode(row, "20000000-0000-0000-0000-000000000000") }.isFailure)
        row.put("disabled_at", "2026-09-27T00:00:00Z")
        assertTrue(runCatching { BackupEnvelopeJson.decode(row, USER) }.isFailure)
        row.put("disabled_at", org.json.JSONObject.NULL).put("envelope_version", 2)
        assertTrue(runCatching { BackupEnvelopeJson.decode(row, USER) }.isFailure)
    }

    companion object { const val USER = "10000000-0000-0000-0000-000000000000" }
}
