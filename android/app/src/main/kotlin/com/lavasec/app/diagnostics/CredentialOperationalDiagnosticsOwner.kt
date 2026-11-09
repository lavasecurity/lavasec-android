package com.lavasec.app.diagnostics

import com.lavasec.app.foundation.diagnostics.OperationalAndroidPathState
import com.lavasec.app.foundation.diagnostics.OperationalAndroidTransport
import com.lavasec.app.foundation.diagnostics.OperationalDiagnosticsSnapshotV1
import com.lavasec.app.foundation.diagnostics.OperationalServiceState
import com.lavasec.app.foundation.diagnostics.OperationalTunnelState
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.vpn.VpnHealthStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Credential-graph owner that samples the exact public VPN health projection.
 *
 * Resolver identity is intentionally consumed only as transient reducer context; the durable
 * snapshot constructor has no field capable of retaining it.
 */
internal class CredentialOperationalDiagnosticsOwner(
    private val credentialOwnerGeneration: Long,
    private val processGeneration: Long,
    private val runtimeGeneration: Long,
    health: Flow<VpnHealthStatus>,
    protection: Flow<ProtectionState>,
    private val sink: OperationalDiagnosticsSink,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMillis: () -> Long,
) : AutoCloseable {
    private val lock = Any()
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)

    private var closed = false
    private var pending: CredentialOperationalDiagnosticsRecord? = null
    private var scheduled: Job? = null
    private var lastAttemptElapsedRealtimeMillis: Long? = null

    init {
        require(credentialOwnerGeneration >= 0L)
        require(processGeneration >= 0L)
        require(runtimeGeneration >= 0L)
        ownerScope.launch {
            combine(health, protection) { exactHealth, exactProtection ->
                project(exactHealth, exactProtection)
            }.collect { record ->
                if (record != null) submit(record)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending = null
            scheduled = null
        }
        ownerJob.cancel()
    }

    private fun project(
        status: VpnHealthStatus,
        protection: ProtectionState,
    ): CredentialOperationalDiagnosticsRecord? {
        if (status.health.context == null) return null
        val sampledWallClockMillis = wallClockMillis().takeIf { it >= 0L } ?: return null
        val sampledElapsedRealtimeMillis = elapsedRealtimeMillis().takeIf { it >= 0L } ?: return null
        val operationalState = protection.toOperationalState()
        val snapshot = runCatching {
            OperationalDiagnosticsSnapshotV1.fromHealth(
                credentialOwnerGeneration = credentialOwnerGeneration,
                sampledWallClockMillis = sampledWallClockMillis,
                sampledElapsedRealtimeMillis = sampledElapsedRealtimeMillis,
                processGeneration = processGeneration,
                runtimeGeneration = runtimeGeneration,
                serviceState = operationalState.service,
                tunnelState = operationalState.tunnel,
                androidTransport = OperationalAndroidTransport.UNKNOWN,
                androidPathState = if (status.health.networkAvailable) {
                    OperationalAndroidPathState.UNKNOWN
                } else {
                    OperationalAndroidPathState.LOST
                },
                health = status.health,
            )
        }.getOrNull() ?: return null
        return CredentialOperationalDiagnosticsRecord(
            snapshot = snapshot,
            droppedPrivateTelemetryCount = status.droppedPrivateTelemetryCount,
        )
    }

    private fun submit(record: CredentialOperationalDiagnosticsRecord) {
        synchronized(lock) {
            if (closed) return
            pending = record
            scheduleLocked()
        }
    }

    private fun scheduleLocked() {
        if (closed || pending == null || scheduled != null) return
        val now = elapsedRealtimeMillis().takeIf { it >= 0L } ?: return
        val delayMillis = lastAttemptElapsedRealtimeMillis?.let { previous ->
            if (now < previous) {
                MINIMUM_SAMPLE_INTERVAL_MILLIS
            } else {
                (MINIMUM_SAMPLE_INTERVAL_MILLIS - (now - previous)).coerceAtLeast(0L)
            }
        } ?: 0L
        scheduled = ownerScope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            persistPending()
        }
    }

    private suspend fun persistPending() {
        val record = synchronized(lock) {
            if (closed) return
            val exact = pending
            pending = null
            lastAttemptElapsedRealtimeMillis = elapsedRealtimeMillis().takeIf { it >= 0L }
            exact
        }
        val succeeded = record == null ||
            runCatching { withContext(dispatcher) { sink.replace(record) } }.getOrDefault(false)
        synchronized(lock) {
            if (closed) return
            if (!succeeded && pending == null) pending = record
            scheduled = null
            scheduleLocked()
        }
    }

    companion object {
        const val MINIMUM_SAMPLE_INTERVAL_MILLIS = 30_000L
    }
}

private data class OperationalState(
    val service: OperationalServiceState,
    val tunnel: OperationalTunnelState,
)

private fun ProtectionState.toOperationalState(): OperationalState = when (this) {
    ProtectionState.Off,
    ProtectionState.SetupRequired,
    is ProtectionState.Paused,
    -> OperationalState(OperationalServiceState.STOPPED, OperationalTunnelState.DISCONNECTED)

    is ProtectionState.Starting,
    is ProtectionState.AwaitingNetwork,
    -> OperationalState(OperationalServiceState.STARTING, OperationalTunnelState.CONNECTING)

    is ProtectionState.Reconnecting ->
        OperationalState(OperationalServiceState.RUNNING, OperationalTunnelState.RECONNECTING)

    is ProtectionState.Protected ->
        OperationalState(OperationalServiceState.RUNNING, OperationalTunnelState.CONNECTED)

    ProtectionState.Stopping ->
        OperationalState(OperationalServiceState.STOPPING, OperationalTunnelState.DISCONNECTING)

    is ProtectionState.Failed ->
        OperationalState(OperationalServiceState.FAILED, OperationalTunnelState.FAILED)
}
