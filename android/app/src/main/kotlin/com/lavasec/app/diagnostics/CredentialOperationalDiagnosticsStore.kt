package com.lavasec.app.diagnostics

import android.content.Context
import android.util.AtomicFile
import com.lavasec.app.foundation.diagnostics.OperationalAndroidPathState
import com.lavasec.app.foundation.diagnostics.OperationalAndroidTransport
import com.lavasec.app.foundation.diagnostics.OperationalDiagnosticsSnapshotV1
import com.lavasec.app.foundation.diagnostics.OperationalServiceState
import com.lavasec.app.foundation.diagnostics.OperationalTunnelState
import com.lavasec.app.foundation.health.ProtectionFallback
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

internal data class CredentialOperationalDiagnosticsRecord(
    val snapshot: OperationalDiagnosticsSnapshotV1,
    val droppedPrivateTelemetryCount: Long,
) {
    init {
        require(droppedPrivateTelemetryCount >= 0L)
    }
}

internal object OperationalDiagnosticsSnapshotV1Retention {
    const val MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
}

/** One strict, domain-free operational sample in credential-protected no-backup storage. */
internal class CredentialOperationalDiagnosticsStore(
    credentialContext: Context,
    fileName: String = FILE_NAME,
    private val credentialAvailable: () -> Boolean,
) : OperationalDiagnosticsSink {
    private val atomicFile: AtomicFile
    private val lock: Any

    init {
        require(!credentialContext.isDeviceProtectedStorage) {
            "operational diagnostics must remain credential protected"
        }
        require(fileName.matches(Regex("[A-Za-z0-9._-]+\\.json")))
        val directory = File(credentialContext.noBackupFilesDir, DIRECTORY)
        val file = File(directory, fileName)
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        atomicFile = AtomicFile(file)
        lock = FileLocks.forPath(file.canonicalPath)
    }

    override fun replace(record: CredentialOperationalDiagnosticsRecord): Boolean =
        synchronized(lock) {
            if (!credentialsAvailable()) return@synchronized false
            val encoded = encode(record).toByteArray(StandardCharsets.UTF_8)
            if (encoded.size > MAX_FILE_BYTES) return@synchronized false
            val parent = atomicFile.baseFile.parentFile ?: return@synchronized false
            if ((!parent.isDirectory && !parent.mkdirs()) || !parent.isDirectory) {
                return@synchronized false
            }
            var output: FileOutputStream? = runCatching { atomicFile.startWrite() }.getOrNull()
                ?: return@synchronized false
            try {
                checkNotNull(output).write(encoded)
                checkNotNull(output).fd.sync()
                atomicFile.finishWrite(checkNotNull(output))
                output = null
            } catch (_: Exception) {
                output?.let { runCatching { atomicFile.failWrite(it) } }
                return@synchronized false
            }
            runCatching { readRecord() == record }.getOrDefault(false)
        }

    fun load(
        expectedOwnerGeneration: Long,
        nowWallClockMillis: Long,
    ): CredentialOperationalDiagnosticsRecord? {
        require(expectedOwnerGeneration >= 0L)
        require(nowWallClockMillis >= 0L)
        return synchronized(lock) {
            if (!credentialsAvailable()) return@synchronized null
            val record = runCatching { readRecord() }.getOrElse {
                atomicFile.delete()
                return@synchronized null
            } ?: return@synchronized null
            val sampledAt = record.snapshot.sampledWallClockMillis
            if (
                sampledAt > nowWallClockMillis ||
                nowWallClockMillis - sampledAt >
                OperationalDiagnosticsSnapshotV1Retention.MAX_AGE_MILLIS
            ) {
                atomicFile.delete()
                return@synchronized null
            }
            record.takeIf {
                it.snapshot.credentialOwnerGeneration == expectedOwnerGeneration
            }
        }
    }

    fun clear(): Boolean = synchronized(lock) {
        if (!credentialsAvailable()) return@synchronized false
        atomicFile.delete()
        !atomicArtifactsExist()
    }

    internal fun fileForTest(): File = atomicFile.baseFile

    private fun credentialsAvailable(): Boolean =
        runCatching(credentialAvailable).getOrDefault(false)

    private fun atomicArtifactsExist(): Boolean =
        atomicFile.baseFile.exists() ||
            File("${atomicFile.baseFile.path}.bak").exists() ||
            File("${atomicFile.baseFile.path}.new").exists()

    private fun readRecord(): CredentialOperationalDiagnosticsRecord? {
        val input = try {
            atomicFile.openRead()
        } catch (_: FileNotFoundException) {
            return null
        }
        val bytes = input.use { stream ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total = Math.addExact(total, read)
                require(total <= MAX_FILE_BYTES) { "operational diagnostics file is oversized" }
                result.write(buffer, 0, read)
            }
            result.toByteArray()
        }
        return decode(String(bytes, StandardCharsets.UTF_8))
    }

    private fun encode(record: CredentialOperationalDiagnosticsRecord): String {
        val snapshot = record.snapshot
        return JSONObject()
            .put(SCHEMA_VERSION, snapshot.schemaVersion)
            .put(CREDENTIAL_OWNER_GENERATION, snapshot.credentialOwnerGeneration)
            .put(HEALTH_SESSION_GENERATION, snapshot.healthSessionGeneration)
            .put(HEALTH_NETWORK_GENERATION, snapshot.healthNetworkGeneration)
            .put(HEALTH_ENGINE_GENERATION, snapshot.healthEngineGeneration)
            .put(HEALTH_OBSERVATION_SEQUENCE, snapshot.healthObservationSequence)
            .put(SAMPLED_WALL_CLOCK_MILLIS, snapshot.sampledWallClockMillis)
            .put(SAMPLED_ELAPSED_REALTIME_MILLIS, snapshot.sampledElapsedRealtimeMillis)
            .put(PROCESS_GENERATION, snapshot.processGeneration)
            .put(RUNTIME_GENERATION, snapshot.runtimeGeneration)
            .put(SERVICE_STATE, snapshot.serviceState.name)
            .put(TUNNEL_STATE, snapshot.tunnelState.name)
            .put(ANDROID_TRANSPORT, snapshot.androidTransport.name)
            .put(ANDROID_PATH_STATE, snapshot.androidPathState.name)
            .put(IS_CONNECTED, snapshot.isConnected)
            .put(NETWORK_AVAILABLE, snapshot.networkAvailable)
            .put(CONSECUTIVE_RESOLVER_FAILURES, snapshot.consecutiveResolverFailures)
            .put(CONSECUTIVE_RESOLVER_REJECTIONS, snapshot.consecutiveResolverRejections)
            .put(
                RECENT_RESPONSE_LATENCIES_MILLIS,
                JSONArray(snapshot.recentResponseLatenciesMillis),
            )
            .put(ACTIVE_FALLBACK, snapshot.activeFallback.name)
            .put(REQUIRES_RECONNECT, snapshot.requiresReconnect)
            .put(RECENT_RESOLVER_QUERY_IDS, JSONArray(snapshot.recentResolverQueryIds))
            .put(
                RESOLVER_QUERY_ID_HIGH_WATERMARK,
                snapshot.resolverQueryIdHighWatermark ?: JSONObject.NULL,
            )
            .put(DROPPED_PRIVATE_TELEMETRY_COUNT, record.droppedPrivateTelemetryCount)
            .toString()
    }

    private fun decode(encoded: String): CredentialOperationalDiagnosticsRecord {
        val json = JSONObject(encoded)
        require(json.keysSet() == KEYS) { "unexpected operational diagnostics fields" }
        val snapshot = OperationalDiagnosticsSnapshotV1(
            schemaVersion = json.getInt(SCHEMA_VERSION),
            credentialOwnerGeneration = json.getLong(CREDENTIAL_OWNER_GENERATION),
            healthSessionGeneration = json.getLong(HEALTH_SESSION_GENERATION),
            healthNetworkGeneration = json.getLong(HEALTH_NETWORK_GENERATION),
            healthEngineGeneration = json.getLong(HEALTH_ENGINE_GENERATION),
            healthObservationSequence = json.getLong(HEALTH_OBSERVATION_SEQUENCE),
            sampledWallClockMillis = json.getLong(SAMPLED_WALL_CLOCK_MILLIS),
            sampledElapsedRealtimeMillis = json.getLong(SAMPLED_ELAPSED_REALTIME_MILLIS),
            processGeneration = json.getLong(PROCESS_GENERATION),
            runtimeGeneration = json.getLong(RUNTIME_GENERATION),
            serviceState = enumValueOf(json.getString(SERVICE_STATE)),
            tunnelState = enumValueOf(json.getString(TUNNEL_STATE)),
            androidTransport = enumValueOf(json.getString(ANDROID_TRANSPORT)),
            androidPathState = enumValueOf(json.getString(ANDROID_PATH_STATE)),
            isConnected = json.getBoolean(IS_CONNECTED),
            networkAvailable = json.getBoolean(NETWORK_AVAILABLE),
            consecutiveResolverFailures = json.getInt(CONSECUTIVE_RESOLVER_FAILURES),
            consecutiveResolverRejections = json.getInt(CONSECUTIVE_RESOLVER_REJECTIONS),
            recentResponseLatenciesMillis = json.getJSONArray(
                RECENT_RESPONSE_LATENCIES_MILLIS,
            ).longValues(),
            activeFallback = enumValueOf(json.getString(ACTIVE_FALLBACK)),
            requiresReconnect = json.getBoolean(REQUIRES_RECONNECT),
            recentResolverQueryIds = json.getJSONArray(RECENT_RESOLVER_QUERY_IDS).longValues(),
            resolverQueryIdHighWatermark = if (json.isNull(RESOLVER_QUERY_ID_HIGH_WATERMARK)) {
                null
            } else {
                json.getLong(RESOLVER_QUERY_ID_HIGH_WATERMARK)
            },
        )
        return CredentialOperationalDiagnosticsRecord(
            snapshot = snapshot,
            droppedPrivateTelemetryCount = json.getLong(DROPPED_PRIVATE_TELEMETRY_COUNT),
        )
    }

    companion object {
        const val MAX_FILE_BYTES = 64 * 1_024
        private const val DIRECTORY = "lavasec-operational-diagnostics"
        private const val FILE_NAME = "operational-diagnostics-v1.json"
        private const val SCHEMA_VERSION = "schema_version"
        private const val CREDENTIAL_OWNER_GENERATION = "credential_owner_generation"
        private const val HEALTH_SESSION_GENERATION = "health_session_generation"
        private const val HEALTH_NETWORK_GENERATION = "health_network_generation"
        private const val HEALTH_ENGINE_GENERATION = "health_engine_generation"
        private const val HEALTH_OBSERVATION_SEQUENCE = "health_observation_sequence"
        private const val SAMPLED_WALL_CLOCK_MILLIS = "sampled_wall_clock_millis"
        private const val SAMPLED_ELAPSED_REALTIME_MILLIS = "sampled_elapsed_realtime_millis"
        private const val PROCESS_GENERATION = "process_generation"
        private const val RUNTIME_GENERATION = "runtime_generation"
        private const val SERVICE_STATE = "service_state"
        private const val TUNNEL_STATE = "tunnel_state"
        private const val ANDROID_TRANSPORT = "android_transport"
        private const val ANDROID_PATH_STATE = "android_path_state"
        private const val IS_CONNECTED = "is_connected"
        private const val NETWORK_AVAILABLE = "network_available"
        private const val CONSECUTIVE_RESOLVER_FAILURES = "consecutive_resolver_failures"
        private const val CONSECUTIVE_RESOLVER_REJECTIONS = "consecutive_resolver_rejections"
        private const val RECENT_RESPONSE_LATENCIES_MILLIS = "recent_response_latencies_millis"
        private const val ACTIVE_FALLBACK = "active_fallback"
        private const val REQUIRES_RECONNECT = "requires_reconnect"
        private const val RECENT_RESOLVER_QUERY_IDS = "recent_resolver_query_ids"
        private const val RESOLVER_QUERY_ID_HIGH_WATERMARK = "resolver_query_id_high_watermark"
        private const val DROPPED_PRIVATE_TELEMETRY_COUNT = "dropped_private_telemetry_count"
        private val KEYS = setOf(
            SCHEMA_VERSION,
            CREDENTIAL_OWNER_GENERATION,
            HEALTH_SESSION_GENERATION,
            HEALTH_NETWORK_GENERATION,
            HEALTH_ENGINE_GENERATION,
            HEALTH_OBSERVATION_SEQUENCE,
            SAMPLED_WALL_CLOCK_MILLIS,
            SAMPLED_ELAPSED_REALTIME_MILLIS,
            PROCESS_GENERATION,
            RUNTIME_GENERATION,
            SERVICE_STATE,
            TUNNEL_STATE,
            ANDROID_TRANSPORT,
            ANDROID_PATH_STATE,
            IS_CONNECTED,
            NETWORK_AVAILABLE,
            CONSECUTIVE_RESOLVER_FAILURES,
            CONSECUTIVE_RESOLVER_REJECTIONS,
            RECENT_RESPONSE_LATENCIES_MILLIS,
            ACTIVE_FALLBACK,
            REQUIRES_RECONNECT,
            RECENT_RESOLVER_QUERY_IDS,
            RESOLVER_QUERY_ID_HIGH_WATERMARK,
            DROPPED_PRIVATE_TELEMETRY_COUNT,
        )
    }
}

internal fun interface OperationalDiagnosticsSink {
    fun replace(record: CredentialOperationalDiagnosticsRecord): Boolean
}

private fun JSONArray.longValues(): List<Long> =
    List(length()) { index -> getLong(index) }

private fun JSONObject.keysSet(): Set<String> = buildSet {
    val iterator = keys()
    while (iterator.hasNext()) add(iterator.next())
}

private object FileLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun forPath(path: String): Any = locks.computeIfAbsent(path) { Any() }
}
