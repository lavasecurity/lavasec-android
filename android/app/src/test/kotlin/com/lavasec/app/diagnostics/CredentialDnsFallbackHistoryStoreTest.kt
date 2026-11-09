package com.lavasec.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.DnsFallbackDisposition
import com.lavasec.app.foundation.health.DnsFallbackObservation
import com.lavasec.app.foundation.health.DnsFallbackReason
import com.lavasec.app.foundation.health.DnsFallbackResponseClass
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.health.ResolverTierDiagnosticObservation
import com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class CredentialDnsFallbackHistoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `bounded numeric evidence survives restart and rejects replay without domain retention`() {
        var now = 1_800_000_000_000L
        val name = "dns-${UUID.randomUUID()}.json"
        val store = CredentialDnsFallbackHistoryStore(context, name) { now }
        repeat(30) { index ->
            now += HOUR
            store.record(1L, 2L, now, evidence(index.toLong()))
        }
        val beforeReplay = store.exportSnapshot().toString(Charsets.UTF_8)
        store.record(1L, 2L, now, evidence(29L))
        assertEquals(beforeReplay, store.exportSnapshot().toString(Charsets.UTF_8))
        val snapshot = JSONObject(beforeReplay)
        assertEquals(24, snapshot.getJSONArray("hours").length())
        assertEquals(8, snapshot.getJSONArray("incidents").length())
        assertTrue(beforeReplay.contains("PORTAL_ADDRESS_NEGATIVE"))
        assertFalse(beforeReplay.contains("deltawifi.com"))
        assertFalse(beforeReplay.contains("192.168."))
        assertTrue(store.exportSnapshot().size <= CredentialDnsFallbackHistoryStore.MAX_BYTES)
        store.close()
        val restored = CredentialDnsFallbackHistoryStore(context, name) { now }
        assertEquals(beforeReplay, restored.exportSnapshot().toString(Charsets.UTF_8))
        now += 25L * HOUR
        assertEquals(0, JSONObject(restored.exportSnapshot().toString(Charsets.UTF_8)).getJSONArray("hours").length())
        restored.close()
        File(context.noBackupFilesDir, name).delete()
    }

    @Test fun `all logs clear durably fences delayed pre-clear callbacks while new evidence resumes`() {
        var now = 1_800_000_000_000L
        val name = "dns-${UUID.randomUUID()}.json"
        val store = CredentialDnsFallbackHistoryStore(context, name) { now }
        store.record(1L, 2L, now - 100L, evidence(1L))
        store.clear()
        store.record(1L, 2L, now - 1L, evidence(2L))
        assertEquals(0, JSONObject(store.exportSnapshot().toString(Charsets.UTF_8)).getJSONArray("incidents").length())
        store.close()
        val restored = CredentialDnsFallbackHistoryStore(context, name) { now }
        restored.record(1L, 2L, now, evidence(3L))
        assertEquals(0, JSONObject(restored.exportSnapshot().toString(Charsets.UTF_8)).getJSONArray("incidents").length())
        now += 1L
        restored.record(1L, 2L, now, evidence(4L))
        assertEquals(1, JSONObject(restored.exportSnapshot().toString(Charsets.UTF_8)).getJSONArray("incidents").length())
        restored.close()
        File(context.noBackupFilesDir, name).delete()
    }

    @Test fun `persisted unknown text cannot reappear in numeric exports`() {
        val now = 1_800_000_000_000L
        val name = "dns-${UUID.randomUUID()}.json"
        val store = CredentialDnsFallbackHistoryStore(context, name) { now }
        store.record(1L, 2L, now, evidence(1L))
        store.close()
        val file = File(context.noBackupFilesDir, name)
        val raw = JSONObject(file.readText())
        raw.put("domain", "private.example")
        raw.getJSONArray("hours").getJSONObject(0).getJSONObject("reasons").put("private.example", 1L)
        raw.getJSONArray("incidents").getJSONObject(0).put("failure", "private.example")
            .put("domain", "private.example")
        file.writeText(raw.toString())
        val restored = CredentialDnsFallbackHistoryStore(context, name) { now }
        assertFalse(restored.exportSnapshot().toString(Charsets.UTF_8).contains("private.example"))
        restored.close()
        file.delete()
    }

    @Test fun `negative answer local refusal and late peer liveness remain distinct with unknown wire facts`() {
        val now = 1_800_000_000_000L
        val name = "dns-${UUID.randomUUID()}.json"
        val store = CredentialDnsFallbackHistoryStore(context, name) { now }
        store.record(1L, 2L, now, evidence(1L).copy(tierObservations = listOf(
            ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.SERVED,
                ResolverTransport.DEVICE_UDP, responseClass = DnsFallbackResponseClass.NEGATIVE,
                responseCode = 3, queryType = 28, terminalAddressPresent = false, packetSent = true))))
        store.record(1L, 2L, now, evidence(2L).copy(tierObservations = listOf(
            ResolverTierDiagnosticObservation(1, ResolverKind.MULLVAD_DOH, ResolverTierDiagnosticOutcome.NOT_ATTEMPTED,
                failure = com.lavasec.app.foundation.dns.ResolverFailureReason.BACKED_OFF, packetSent = false))))
        store.record(1L, 2L, now, evidence(3L).copy(tierObservations = listOf(
            ResolverTierDiagnosticObservation(1, ResolverKind.MULLVAD_DOH, ResolverTierDiagnosticOutcome.ANSWERED,
                peerLivenessOnly = true))))
        val rows = JSONObject(store.exportSnapshot().toString(Charsets.UTF_8)).getJSONArray("incidents")
        val negative = rows.getJSONObject(0)
        assertEquals("NEGATIVE", negative.getString("responseClass"))
        assertEquals(3, negative.getInt("responseCode"))
        assertEquals(28, negative.getInt("queryType"))
        assertFalse(negative.getBoolean("terminalAddressPresent"))
        assertTrue(negative.getBoolean("packetSent"))
        assertEquals(12L, negative.getLong("elapsedMillis"))
        val local = rows.getJSONObject(1)
        assertEquals("BACKED_OFF", local.getString("localAdmissionReason"))
        assertFalse(local.getBoolean("packetSent")); assertFalse(local.getBoolean("attempted"))
        assertTrue(rows.getJSONObject(2).getBoolean("peerLivenessOnly"))
        assertTrue(rows.getJSONObject(2).isNull("packetSent"))
        store.close()
        val restored = CredentialDnsFallbackHistoryStore(context, name) { now }
        assertEquals(rows.toString(), JSONObject(restored.exportSnapshot().toString(Charsets.UTF_8)).getJSONArray("incidents").toString())
        restored.close()
        File(context.noBackupFilesDir, name).delete()
    }

    private fun evidence(id: Long) = ResolverQueryHealthEvidence.primaryAccepted(id, 12L).copy(
        tierObservations = listOf(ResolverTierDiagnosticObservation(2, ResolverKind.DEVICE,
            ResolverTierDiagnosticOutcome.SERVED, ResolverTransport.DEVICE_UDP)),
        fallbackObservations = listOf(DnsFallbackObservation(1, 2, DnsFallbackReason.PORTAL_ADDRESS_NEGATIVE,
            DnsFallbackDisposition.SERVED)),
    )

    private companion object { const val HOUR = 3_600_000L }
}
