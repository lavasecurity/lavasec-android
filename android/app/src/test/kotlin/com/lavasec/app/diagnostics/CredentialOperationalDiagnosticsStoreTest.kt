package com.lavasec.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.diagnostics.OperationalAndroidPathState
import com.lavasec.app.foundation.diagnostics.OperationalAndroidTransport
import com.lavasec.app.foundation.diagnostics.OperationalDiagnosticsSnapshotV1
import com.lavasec.app.foundation.diagnostics.OperationalServiceState
import com.lavasec.app.foundation.diagnostics.OperationalTunnelState
import com.lavasec.app.foundation.health.ProtectionFallback
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class CredentialOperationalDiagnosticsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = CredentialOperationalDiagnosticsStore(
        credentialContext = context,
        fileName = "${UUID.randomUUID()}.json",
        credentialAvailable = { true },
    )

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun `one credential safe record round trips without private payload`() {
        val expected = CredentialOperationalDiagnosticsRecord(
            snapshot = snapshot(owner = 9L, sampledAt = 20_000L),
            droppedPrivateTelemetryCount = 7L,
        )

        assertTrue(store.replace(expected))
        assertEquals(expected, store.load(expectedOwnerGeneration = 9L, nowWallClockMillis = 20_001L))
        assertNull(store.load(expectedOwnerGeneration = 10L, nowWallClockMillis = 20_001L))

        val bytes = store.fileForTest().readText()
        assertFalse(bytes.contains("private.example"))
        assertFalse(bytes.contains("1.1.1.1"))
        assertFalse(bytes.contains(context.filesDir.absolutePath))
        assertFalse(bytes.contains("primaryResolverIdentity"))
        assertTrue(bytes.length <= CredentialOperationalDiagnosticsStore.MAX_FILE_BYTES)
    }

    @Test
    fun `future stale and malformed records fail closed and are removed`() {
        assertTrue(store.replace(CredentialOperationalDiagnosticsRecord(snapshot(1L, 30_000L), 0L)))
        assertNull(store.load(1L, 29_999L))
        assertFalse(store.fileForTest().exists())

        assertTrue(store.replace(CredentialOperationalDiagnosticsRecord(snapshot(1L, 1L), 0L)))
        assertNull(
            store.load(
                1L,
                OperationalDiagnosticsSnapshotV1Retention.MAX_AGE_MILLIS + 2L,
            ),
        )
        assertFalse(store.fileForTest().exists())

        store.fileForTest().parentFile!!.mkdirs()
        store.fileForTest().writeText("{\"schema_version\":999,\"domain\":\"private.example\"}")
        assertNull(store.load(1L, 40_000L))
        assertFalse(store.fileForTest().exists())
    }

    private fun snapshot(owner: Long, sampledAt: Long) = OperationalDiagnosticsSnapshotV1(
        credentialOwnerGeneration = owner,
        healthSessionGeneration = 11L,
        healthNetworkGeneration = 12L,
        healthEngineGeneration = 13L,
        healthObservationSequence = 14L,
        sampledWallClockMillis = sampledAt,
        sampledElapsedRealtimeMillis = 15L,
        processGeneration = 16L,
        runtimeGeneration = 17L,
        serviceState = OperationalServiceState.RUNNING,
        tunnelState = OperationalTunnelState.CONNECTED,
        androidTransport = OperationalAndroidTransport.UNKNOWN,
        androidPathState = OperationalAndroidPathState.VALIDATED,
        isConnected = true,
        networkAvailable = true,
        consecutiveResolverFailures = 2,
        consecutiveResolverRejections = 1,
        recentResponseLatenciesMillis = listOf(20L, 30L),
        activeFallback = ProtectionFallback.NONE,
        requiresReconnect = false,
        recentResolverQueryIds = listOf(101L, 102L),
        resolverQueryIdHighWatermark = 102L,
    )
}
