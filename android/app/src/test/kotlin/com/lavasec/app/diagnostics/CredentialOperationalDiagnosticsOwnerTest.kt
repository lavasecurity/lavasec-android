package com.lavasec.app.diagnostics

import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import com.lavasec.app.foundation.health.ProtectionHealthContext
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.vpn.VpnHealthStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialOperationalDiagnosticsOwnerTest {
    @Test
    fun `samples exact domain free health immediately then coalesces bursts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val health = MutableStateFlow(VpnHealthStatus())
        val protection = MutableStateFlow<ProtectionState>(ProtectionState.Off)
        val persisted = mutableListOf<CredentialOperationalDiagnosticsRecord>()
        val owner = CredentialOperationalDiagnosticsOwner(
            credentialOwnerGeneration = 41L,
            processGeneration = 42L,
            runtimeGeneration = 43L,
            health = health,
            protection = protection,
            sink = OperationalDiagnosticsSink { record -> persisted.add(record) },
            scope = this,
            dispatcher = dispatcher,
            wallClockMillis = { 10_000L + testScheduler.currentTime },
            elapsedRealtimeMillis = { testScheduler.currentTime },
        )
        runCurrent()
        assertTrue(persisted.isEmpty())

        protection.value = ProtectionState.Reconnecting(
            com.lavasec.app.platform.StartOrigin.Reload,
            7L,
        )
        health.value = status(revision = 1L, sequence = 1L, dropped = 2L)
        runCurrent()

        assertEquals(1, persisted.size)
        assertEquals(41L, persisted.single().snapshot.credentialOwnerGeneration)
        assertEquals(1L, persisted.single().snapshot.healthObservationSequence)
        assertEquals(2L, persisted.single().droppedPrivateTelemetryCount)
        assertTrue(persisted.single().snapshot.requiresReconnect)

        health.value = status(revision = 2L, sequence = 2L, dropped = 3L)
        health.value = status(revision = 3L, sequence = 3L, dropped = 4L)
        runCurrent()
        assertEquals(1, persisted.size)

        advanceTimeBy(CredentialOperationalDiagnosticsOwner.MINIMUM_SAMPLE_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(2, persisted.size)
        assertEquals(3L, persisted.last().snapshot.healthObservationSequence)
        assertEquals(4L, persisted.last().droppedPrivateTelemetryCount)

        owner.close()
        health.value = status(revision = 4L, sequence = 4L, dropped = 5L)
        advanceTimeBy(CredentialOperationalDiagnosticsOwner.MINIMUM_SAMPLE_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(2, persisted.size)
    }

    @Test
    fun `does not persist resolver identity or any private event payload`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val health = MutableStateFlow(status(1L, 1L, 9L))
        val protection = MutableStateFlow<ProtectionState>(ProtectionState.Off)
        val persisted = mutableListOf<CredentialOperationalDiagnosticsRecord>()

        val owner = CredentialOperationalDiagnosticsOwner(
            credentialOwnerGeneration = 1L,
            processGeneration = 2L,
            runtimeGeneration = 3L,
            health = health,
            protection = protection,
            sink = OperationalDiagnosticsSink { record -> persisted.add(record) },
            scope = this,
            dispatcher = dispatcher,
            wallClockMillis = { 100L },
            elapsedRealtimeMillis = { 200L },
        )
        runCurrent()

        val text = persisted.single().toString()
        assertFalse(text.contains("private.example"))
        assertFalse(text.contains("device-dns:v4=01010101"))
        assertEquals(9L, persisted.single().droppedPrivateTelemetryCount)
        owner.close()
    }

    @Test
    fun `failed durable sample remains pending for bounded retry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val health = MutableStateFlow(status(1L, 1L, 0L))
        val protection = MutableStateFlow<ProtectionState>(ProtectionState.Off)
        var attempts = 0
        val owner = CredentialOperationalDiagnosticsOwner(
            credentialOwnerGeneration = 1L,
            processGeneration = 1L,
            runtimeGeneration = 1L,
            health = health,
            protection = protection,
            sink = OperationalDiagnosticsSink { ++attempts > 1 },
            scope = this,
            dispatcher = dispatcher,
            wallClockMillis = { testScheduler.currentTime },
            elapsedRealtimeMillis = { testScheduler.currentTime },
        )
        runCurrent()
        assertEquals(1, attempts)

        advanceTimeBy(CredentialOperationalDiagnosticsOwner.MINIMUM_SAMPLE_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(2, attempts)
        owner.close()
    }

    private fun status(revision: Long, sequence: Long, dropped: Long) = VpnHealthStatus(
        revision = revision,
        health = ProtectionHealthAccumulator(
            isConnected = true,
            networkAvailable = true,
            consecutiveResolverFailures = 3,
            requiresReconnect = true,
            context = ProtectionHealthContext(
                sessionGeneration = 7L,
                networkGeneration = 8L,
                engineGeneration = 9L,
                observationSequence = sequence,
                observedAtMillis = 100L,
                primaryResolverIdentity = PrimaryResolverIdentity(
                    "device-dns:v4=01010101;v6=private.example",
                ),
                fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
            ),
        ),
        droppedPrivateTelemetryCount = dropped,
    )
}
