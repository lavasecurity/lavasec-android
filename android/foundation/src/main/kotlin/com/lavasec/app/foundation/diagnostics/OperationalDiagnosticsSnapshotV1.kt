package com.lavasec.app.foundation.diagnostics

import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import java.util.Collections

enum class OperationalServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

enum class OperationalTunnelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTING,
    FAILED,
}

enum class OperationalAndroidTransport {
    UNKNOWN,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER,
}

enum class OperationalAndroidPathState {
    UNKNOWN,
    VALIDATED,
    CAPTIVE_PORTAL,
    UNVALIDATED,
    LOST,
}

/**
 * The only durable operational-health evidence shape.
 *
 * Every member is a bounded numeric, boolean, or closed-enum fact. Resolver identity and all
 * traffic content deliberately stop at the transient health reducer.
 */
class OperationalDiagnosticsSnapshotV1(
    val schemaVersion: Int = SCHEMA_VERSION,
    val credentialOwnerGeneration: Long,
    val healthSessionGeneration: Long,
    val healthNetworkGeneration: Long,
    val healthEngineGeneration: Long,
    val healthObservationSequence: Long,
    val sampledWallClockMillis: Long,
    val sampledElapsedRealtimeMillis: Long,
    val processGeneration: Long,
    val runtimeGeneration: Long,
    val serviceState: OperationalServiceState,
    val tunnelState: OperationalTunnelState,
    val androidTransport: OperationalAndroidTransport,
    val androidPathState: OperationalAndroidPathState,
    val isConnected: Boolean,
    val networkAvailable: Boolean,
    val consecutiveResolverFailures: Int,
    val consecutiveResolverRejections: Int,
    recentResponseLatenciesMillis: List<Long>,
    val activeFallback: ProtectionFallback,
    val requiresReconnect: Boolean,
    recentResolverQueryIds: List<Long>,
    val resolverQueryIdHighWatermark: Long?,
) {
    val recentResponseLatenciesMillis: List<Long> =
        Collections.unmodifiableList(ArrayList(recentResponseLatenciesMillis))
    val recentResolverQueryIds: List<Long> =
        Collections.unmodifiableList(ArrayList(recentResolverQueryIds))

    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported operational diagnostics schema" }
        require(credentialOwnerGeneration >= 0L)
        require(healthSessionGeneration >= 0L)
        require(healthNetworkGeneration >= 0L)
        require(healthEngineGeneration >= 0L)
        require(healthObservationSequence >= 0L)
        require(sampledWallClockMillis >= 0L)
        require(sampledElapsedRealtimeMillis >= 0L)
        require(processGeneration >= 0L)
        require(runtimeGeneration >= 0L)
        require(consecutiveResolverFailures >= 0)
        require(consecutiveResolverRejections >= 0)
        require(
            recentResponseLatenciesMillis.size <=
                ProtectionHealthAccumulator.MAX_RECENT_RESPONSE_LATENCIES,
        )
        require(recentResponseLatenciesMillis.all { it >= 0L })
        require(recentResolverQueryIds.size <= ProtectionHealthAccumulator.MAX_RECENT_RESOLVER_QUERY_IDS)
        require(recentResolverQueryIds.all { it >= 0L })
        require(recentResolverQueryIds.distinct().size == recentResolverQueryIds.size)
        if (resolverQueryIdHighWatermark == null) {
            require(recentResolverQueryIds.isEmpty())
        } else {
            require(resolverQueryIdHighWatermark >= 0L)
            val retainedFloor = maxOf(
                0L,
                resolverQueryIdHighWatermark -
                    (ProtectionHealthAccumulator.MAX_RECENT_RESOLVER_QUERY_IDS - 1L),
            )
            require(resolverQueryIdHighWatermark in recentResolverQueryIds)
            require(recentResolverQueryIds.all { it in retainedFloor..resolverQueryIdHighWatermark })
        }
    }

    fun copy(
        schemaVersion: Int = this.schemaVersion,
        credentialOwnerGeneration: Long = this.credentialOwnerGeneration,
        healthSessionGeneration: Long = this.healthSessionGeneration,
        healthNetworkGeneration: Long = this.healthNetworkGeneration,
        healthEngineGeneration: Long = this.healthEngineGeneration,
        healthObservationSequence: Long = this.healthObservationSequence,
        sampledWallClockMillis: Long = this.sampledWallClockMillis,
        sampledElapsedRealtimeMillis: Long = this.sampledElapsedRealtimeMillis,
        processGeneration: Long = this.processGeneration,
        runtimeGeneration: Long = this.runtimeGeneration,
        serviceState: OperationalServiceState = this.serviceState,
        tunnelState: OperationalTunnelState = this.tunnelState,
        androidTransport: OperationalAndroidTransport = this.androidTransport,
        androidPathState: OperationalAndroidPathState = this.androidPathState,
        isConnected: Boolean = this.isConnected,
        networkAvailable: Boolean = this.networkAvailable,
        consecutiveResolverFailures: Int = this.consecutiveResolverFailures,
        consecutiveResolverRejections: Int = this.consecutiveResolverRejections,
        recentResponseLatenciesMillis: List<Long> = this.recentResponseLatenciesMillis,
        activeFallback: ProtectionFallback = this.activeFallback,
        requiresReconnect: Boolean = this.requiresReconnect,
        recentResolverQueryIds: List<Long> = this.recentResolverQueryIds,
        resolverQueryIdHighWatermark: Long? = this.resolverQueryIdHighWatermark,
    ) = OperationalDiagnosticsSnapshotV1(
        schemaVersion = schemaVersion,
        credentialOwnerGeneration = credentialOwnerGeneration,
        healthSessionGeneration = healthSessionGeneration,
        healthNetworkGeneration = healthNetworkGeneration,
        healthEngineGeneration = healthEngineGeneration,
        healthObservationSequence = healthObservationSequence,
        sampledWallClockMillis = sampledWallClockMillis,
        sampledElapsedRealtimeMillis = sampledElapsedRealtimeMillis,
        processGeneration = processGeneration,
        runtimeGeneration = runtimeGeneration,
        serviceState = serviceState,
        tunnelState = tunnelState,
        androidTransport = androidTransport,
        androidPathState = androidPathState,
        isConnected = isConnected,
        networkAvailable = networkAvailable,
        consecutiveResolverFailures = consecutiveResolverFailures,
        consecutiveResolverRejections = consecutiveResolverRejections,
        recentResponseLatenciesMillis = recentResponseLatenciesMillis,
        activeFallback = activeFallback,
        requiresReconnect = requiresReconnect,
        recentResolverQueryIds = recentResolverQueryIds,
        resolverQueryIdHighWatermark = resolverQueryIdHighWatermark,
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is OperationalDiagnosticsSnapshotV1 &&
            schemaVersion == other.schemaVersion &&
            credentialOwnerGeneration == other.credentialOwnerGeneration &&
            healthSessionGeneration == other.healthSessionGeneration &&
            healthNetworkGeneration == other.healthNetworkGeneration &&
            healthEngineGeneration == other.healthEngineGeneration &&
            healthObservationSequence == other.healthObservationSequence &&
            sampledWallClockMillis == other.sampledWallClockMillis &&
            sampledElapsedRealtimeMillis == other.sampledElapsedRealtimeMillis &&
            processGeneration == other.processGeneration &&
            runtimeGeneration == other.runtimeGeneration &&
            serviceState == other.serviceState &&
            tunnelState == other.tunnelState &&
            androidTransport == other.androidTransport &&
            androidPathState == other.androidPathState &&
            isConnected == other.isConnected &&
            networkAvailable == other.networkAvailable &&
            consecutiveResolverFailures == other.consecutiveResolverFailures &&
            consecutiveResolverRejections == other.consecutiveResolverRejections &&
            recentResponseLatenciesMillis == other.recentResponseLatenciesMillis &&
            activeFallback == other.activeFallback &&
            requiresReconnect == other.requiresReconnect &&
            recentResolverQueryIds == other.recentResolverQueryIds &&
            resolverQueryIdHighWatermark == other.resolverQueryIdHighWatermark

    override fun hashCode(): Int = listOf(
        schemaVersion,
        credentialOwnerGeneration,
        healthSessionGeneration,
        healthNetworkGeneration,
        healthEngineGeneration,
        healthObservationSequence,
        sampledWallClockMillis,
        sampledElapsedRealtimeMillis,
        processGeneration,
        runtimeGeneration,
        serviceState,
        tunnelState,
        androidTransport,
        androidPathState,
        isConnected,
        networkAvailable,
        consecutiveResolverFailures,
        consecutiveResolverRejections,
        recentResponseLatenciesMillis,
        activeFallback,
        requiresReconnect,
        recentResolverQueryIds,
        resolverQueryIdHighWatermark,
    ).hashCode()

    override fun toString(): String =
        "OperationalDiagnosticsSnapshotV1(" +
            "schemaVersion=$schemaVersion, " +
            "credentialOwnerGeneration=$credentialOwnerGeneration, " +
            "healthSessionGeneration=$healthSessionGeneration, " +
            "healthNetworkGeneration=$healthNetworkGeneration, " +
            "healthEngineGeneration=$healthEngineGeneration, " +
            "healthObservationSequence=$healthObservationSequence, " +
            "sampledWallClockMillis=$sampledWallClockMillis, " +
            "sampledElapsedRealtimeMillis=$sampledElapsedRealtimeMillis, " +
            "processGeneration=$processGeneration, " +
            "runtimeGeneration=$runtimeGeneration, " +
            "serviceState=$serviceState, " +
            "tunnelState=$tunnelState, " +
            "androidTransport=$androidTransport, " +
            "androidPathState=$androidPathState, " +
            "isConnected=$isConnected, " +
            "networkAvailable=$networkAvailable, " +
            "consecutiveResolverFailures=$consecutiveResolverFailures, " +
            "consecutiveResolverRejections=$consecutiveResolverRejections, " +
            "recentResponseLatenciesMillis=$recentResponseLatenciesMillis, " +
            "activeFallback=$activeFallback, " +
            "requiresReconnect=$requiresReconnect, " +
            "recentResolverQueryIds=$recentResolverQueryIds, " +
            "resolverQueryIdHighWatermark=$resolverQueryIdHighWatermark)"

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_SNAPSHOTS = 1

        fun fromHealth(
            credentialOwnerGeneration: Long,
            sampledWallClockMillis: Long,
            sampledElapsedRealtimeMillis: Long,
            processGeneration: Long,
            runtimeGeneration: Long,
            serviceState: OperationalServiceState,
            tunnelState: OperationalTunnelState,
            androidTransport: OperationalAndroidTransport,
            androidPathState: OperationalAndroidPathState,
            health: ProtectionHealthAccumulator,
        ): OperationalDiagnosticsSnapshotV1 {
            val context = requireNotNull(health.context) {
                "Operational diagnostics require ordered health context"
            }
            return OperationalDiagnosticsSnapshotV1(
                credentialOwnerGeneration = credentialOwnerGeneration,
                healthSessionGeneration = context.sessionGeneration,
                healthNetworkGeneration = context.networkGeneration,
                healthEngineGeneration = context.engineGeneration,
                healthObservationSequence = context.observationSequence,
                sampledWallClockMillis = sampledWallClockMillis,
                sampledElapsedRealtimeMillis = sampledElapsedRealtimeMillis,
                processGeneration = processGeneration,
                runtimeGeneration = runtimeGeneration,
                serviceState = serviceState,
                tunnelState = tunnelState,
                androidTransport = androidTransport,
                androidPathState = androidPathState,
                isConnected = health.isConnected,
                networkAvailable = health.networkAvailable,
                consecutiveResolverFailures = health.consecutiveResolverFailures,
                consecutiveResolverRejections = health.consecutivePrimaryRejections,
                recentResponseLatenciesMillis = health.recentResponseLatenciesMillis.toList(),
                activeFallback = health.activeFallback,
                requiresReconnect = health.requiresReconnect,
                recentResolverQueryIds = health.recentResolverQueryIds.toList(),
                resolverQueryIdHighWatermark = health.resolverQueryIdHighWatermark,
            )
        }
    }
}
