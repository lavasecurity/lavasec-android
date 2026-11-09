package com.lavasec.app.diagnostics

import com.lavasec.app.foundation.diagnostics.OperationalAndroidPathState
import com.lavasec.app.foundation.diagnostics.OperationalAndroidTransport
import com.lavasec.app.foundation.diagnostics.OperationalDiagnosticsSnapshotV1
import com.lavasec.app.foundation.diagnostics.OperationalServiceState
import com.lavasec.app.foundation.diagnostics.OperationalTunnelState
import com.lavasec.app.product.privacy.LocalFeedbackDraft
import com.lavasec.app.product.privacy.LocalFeedbackComposer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsFeedbackOwnerTest {
    @Test
    fun `refresh and feedback expose only exact local diagnostic evidence`() = runTest {
        val record = record()
        val owner = DiagnosticsFeedbackOwner(
            load = { record },
            clear = { true },
            feedback = LocalFeedbackComposer(
                appVersion = { "1.0" },
                deviceSummary = { "Android" },
                diagnostics = { "session=${record.snapshot.healthSessionGeneration}" },
            ),
            scope = backgroundScope,
        )

        assertTrue(owner.refresh())
        assertEquals(record, owner.state.value.record)
        val draft = owner.composeFeedback(
            description = "Broken path",
            includeDiagnostics = true,
            localLogExport = null,
        )
        assertTrue(draft is LocalFeedbackDraft)
        assertTrue(draft!!.body.contains("session=3"))
        assertTrue(owner.clear())
        assertNull(owner.state.value.record)
    }

    @Test
    fun `closed owner rejects work and retains frozen state`() = runTest {
        var loads = 0
        val owner = DiagnosticsFeedbackOwner(
            load = { loads += 1; record() },
            clear = { true },
            feedback = LocalFeedbackComposer({ "1" }, { "device" }, { "diagnostic" }),
            scope = backgroundScope,
        )
        assertTrue(owner.refresh())
        val frozen = owner.state.value
        owner.close()

        assertFalse(owner.refresh())
        assertFalse(owner.clear())
        assertNull(owner.composeFeedback("later", true, null))
        assertEquals(1, loads)
        assertEquals(frozen, owner.state.value)
    }

    @Test
    fun `graph retirement cancels in flight diagnostics feedback`() = runTest {
        val started = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val owner = DiagnosticsFeedbackOwner(
            load = { null },
            clear = { true },
            feedback = LocalFeedbackComposer(
                appVersion = { "1" },
                deviceSummary = { "device" },
                diagnostics = {
                    started.complete(Unit)
                    neverComplete.await()
                    "late"
                },
            ),
            scope = backgroundScope,
        )
        val feedback = async { owner.composeFeedback("broken", true, null) }
        started.await()

        owner.close()

        assertNull(feedback.await())
        assertFalse(owner.state.value.inFlight)
    }

    private fun record() = CredentialOperationalDiagnosticsRecord(
        snapshot = OperationalDiagnosticsSnapshotV1(
            schemaVersion = 1,
            credentialOwnerGeneration = 2L,
            healthSessionGeneration = 3L,
            healthNetworkGeneration = 4L,
            healthEngineGeneration = 5L,
            healthObservationSequence = 6L,
            sampledWallClockMillis = 7L,
            sampledElapsedRealtimeMillis = 8L,
            processGeneration = 9L,
            runtimeGeneration = 10L,
            serviceState = OperationalServiceState.RUNNING,
            tunnelState = OperationalTunnelState.CONNECTED,
            androidTransport = OperationalAndroidTransport.UNKNOWN,
            androidPathState = OperationalAndroidPathState.UNKNOWN,
            isConnected = true,
            networkAvailable = true,
            consecutiveResolverFailures = 0,
            consecutiveResolverRejections = 0,
            recentResponseLatenciesMillis = emptyList(),
            activeFallback = com.lavasec.app.foundation.health.ProtectionFallback.NONE,
            requiresReconnect = false,
            recentResolverQueryIds = emptyList(),
            resolverQueryIdHighWatermark = null,
        ),
        droppedPrivateTelemetryCount = 11L,
    )
}
