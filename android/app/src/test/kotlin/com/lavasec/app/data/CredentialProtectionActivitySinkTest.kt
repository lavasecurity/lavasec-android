package com.lavasec.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.product.privacy.CredentialPrivacyFence
import com.lavasec.app.vpn.VpnProtectionEvidence
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialProtectionActivitySinkTest {
    @Test fun `Guardian retention is independent of counts and disabling it resets accrual`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = ActivityDatabase(context, null)
        val repository = ActivityRepository(database)
        val observations = mutableListOf<Pair<Boolean, Boolean>>()
        var resets = 0
        val recorder = object : GuardianUsageRecorder {
            override fun observe(active: Boolean, atMillis: Long, enabled: Boolean): Boolean {
                observations.add(active to enabled)
                return enabled
            }
            override fun resetTracking() { resets++ }
        }
        val writer = CredentialActivityWriter(repository, ActivityRevisionBus(), recorder)
        writer.applyLoggingPolicy(com.lavasec.app.platform.LoggingSettings(
            keepCounts = false, keepLavaGuardProgress = true))
        assertTrue(writer.recordProtectionState(true, 10L))
        assertTrue(writer.recordProtectionHeartbeat(20L))
        writer.applyLoggingPolicy(com.lavasec.app.platform.LoggingSettings(
            keepCounts = false, keepLavaGuardProgress = false))
        writer.recordProtectionHeartbeat(30L)
        assertEquals(listOf(true to true, true to true, true to false), observations)
        assertEquals(1, resets)
        database.close()
    }
    @Test
    fun `exact service evidence opens heartbeats and closes local uptime`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.parse("2026-08-02T12:00:00Z")
        val database = ActivityDatabase(context, null)
        val repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val sink = CredentialProtectionActivitySink(
            writer = CredentialActivityWriter(repository, ActivityRevisionBus()),
            privacyFence = CredentialPrivacyFence(),
        )
        val start = now.minusSeconds(120).toEpochMilli()
        val heartbeat = now.minusSeconds(60).toEpochMilli()

        assertTrue(sink.accept(VpnProtectionEvidence.State(1L, 1L, 1L, start, true)))
        assertTrue(sink.accept(VpnProtectionEvidence.Heartbeat(1L, 1L, 1L, heartbeat)))
        assertTrue(
            sink.accept(
                VpnProtectionEvidence.State(1L, 1L, 1L, now.toEpochMilli(), false),
            ),
        )

        assertEquals(
            120_000L,
            repository.summary(
                com.lavasec.app.foundation.activity.ActivityDateRange(
                    now.atZone(ZoneOffset.UTC).toLocalDate(),
                    now.atZone(ZoneOffset.UTC).toLocalDate(),
                ),
                now.toEpochMilli(),
            ).localProtectionUptimeMillis,
        )
        database.close()
    }
}
