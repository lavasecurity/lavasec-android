package com.lavasec.app.product.activity

import android.content.Context
import android.util.AtomicFile
import com.lavasec.app.foundation.activity.*
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal interface NetworkActivityPersistence {
    fun load(): List<NetworkActivityEntry>
    /** Atomic replacement. A failed write must leave the previous snapshot readable. */
    fun save(entries: List<NetworkActivityEntry>)
}

/** Credential-only, domain-free retained events. Neither Android backup nor Direct Boot reads it. */
internal class AndroidNetworkActivityPersistence(context: Context) : NetworkActivityPersistence {
    init { require(!context.isDeviceProtectedStorage) }
    private val file = AtomicFile(File(context.noBackupFilesDir, "network-activity-v1.json"))

    override fun load(): List<NetworkActivityEntry> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        return file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) throw IOException("Network Activity storage exceeds limit")
                output.write(buffer, 0, count)
            }
            NetworkActivityJson.decode(output.toByteArray())
        }
    }

    override fun save(entries: List<NetworkActivityEntry>) {
        val bytes = NetworkActivityJson.encode(entries)
        require(bytes.size <= MAX_BYTES)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
    private companion object { const val MAX_BYTES = 512 * 1024 }
}

/** Exhaustive tagged encoding: no class names, Java deserialization or arbitrary detail strings. */
internal object NetworkActivityJson {
    fun encode(entries: List<NetworkActivityEntry>): ByteArray {
        require(entries.size <= NetworkActivityContract.MAX_ROWS)
        val rows = JSONArray()
        for (entry in entries) {
            val state = entry.capturedState
            rows.put(JSONObject().put("id", entry.id).put("first", entry.firstOccurrenceEpochMillis)
                .put("last", entry.latestOccurrenceEpochMillis).put("count", entry.occurrenceCount)
                .put("event", event(entry.event)).put("state", JSONObject()
                    .put("protection", state.protection.name).put("connectivity", state.connectivity.name)
                    .put("network", state.networkKind.name).put("satisfied", state.networkPathSatisfied)
                    .put("resolver", state.resolver.name).put("transport", state.resolverTransport?.name)
                    .put("coverage", state.fallbackCoverage.name).put("fallback", state.activeFallback.name)))
        }
        return JSONObject().put("version", 1).put("rows", rows).toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<NetworkActivityEntry> {
        require(bytes.size <= 512 * 1024)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val rows = root.getJSONArray("rows")
        require(rows.length() <= NetworkActivityContract.MAX_ROWS)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val state = row.getJSONObject("state")
            val id = row.getLong("id")
            val first = row.getLong("first")
            require(id in 1 until Long.MAX_VALUE && first >= 0)
            NetworkActivityEntry(id, row.getLong("last"), first, decodeEvent(row.getJSONObject("event")),
                NetworkActivityCapturedState(state.enum("protection"), state.enum("connectivity"), state.enum("network"),
                    state.getBoolean("satisfied"), state.enum("resolver"), state.optionalEnum("transport"),
                    state.enum("coverage"), state.enum("fallback")), row.getInt("count"))
        }.also { entries -> require(entries.map { it.id }.distinct().size == entries.size) }
    }

    private fun event(event: NetworkActivityEvent): JSONObject {
        val json = JSONObject()
        when (event) {
            is NetworkActivityEvent.NetworkChanged -> json.put("type", "network").put("from", event.from?.name)
                .put("to", event.to.name).put("satisfied", event.pathSatisfied)
            NetworkActivityEvent.ProtectionConnected -> json.put("type", "connected")
            is NetworkActivityEvent.UserAction -> json.put("type", "action").put("action", event.action.name)
            is NetworkActivityEvent.DnsSmokeProbeSucceeded -> json.put("type", "probe-ok").put("resolver", event.resolver.name)
                .put("transport", event.transport.name).put("http", event.dohHttpVersion?.name)
            is NetworkActivityEvent.DnsSmokeProbeFailed -> {
                json.put("type", "probe-failed")
                when (val reason = event.reason) {
                    NetworkActivityProbeFailure.RejectedResponse -> json.put("reason", "rejected")
                    is NetworkActivityProbeFailure.ResolverFailure -> json.put("reason", "resolver").put("failure", reason.reason.name)
                }
            }
            is NetworkActivityEvent.FallbackActivated -> {
                json.put("type", "fallback").put("fallback", event.fallback.name)
                when (val cause = event.cause) {
                    NetworkActivityFallbackActivationCause.ProducerUnspecified -> json.put("cause", "unspecified")
                    NetworkActivityFallbackActivationCause.QueryFallback -> json.put("cause", "query")
                    NetworkActivityFallbackActivationCause.FallbackCheck -> json.put("cause", "check")
                    is NetworkActivityFallbackActivationCause.PrimaryFailure -> json.put("cause", "primary").put("failure", cause.reason.name)
                }
            }
            NetworkActivityEvent.DeviceDnsFallbackRecovered -> json.put("type", "device-recovered")
            is NetworkActivityEvent.ReconnectNeeded -> {
                json.put("type", "reconnect")
                when (val reason = event.reason) {
                    NetworkActivityReconnectReason.RejectedResponse -> json.put("reason", "rejected")
                    NetworkActivityReconnectReason.DeviceDnsRecapture -> json.put("reason", "recapture")
                    NetworkActivityReconnectReason.RuntimeUnavailable -> json.put("reason", "runtime")
                    is NetworkActivityReconnectReason.ResolverFailure -> json.put("reason", "resolver").put("failure", reason.reason.name)
                }
            }
            is NetworkActivityEvent.ConnectivityRecovered -> json.put("type", "recovered").put("reason", event.reason.name)
            is NetworkActivityEvent.NetworkSettingsReapplyFailed -> json.put("type", "settings-failed").put("reason", event.reason.name)
        }
        return json
    }

    private fun decodeEvent(json: JSONObject): NetworkActivityEvent = when (json.getString("type")) {
        "network" -> NetworkActivityEvent.NetworkChanged(json.optionalEnum("from"), json.enum("to"), json.getBoolean("satisfied"))
        "connected" -> NetworkActivityEvent.ProtectionConnected
        "action" -> NetworkActivityEvent.UserAction(json.enum("action"))
        "probe-ok" -> NetworkActivityEvent.DnsSmokeProbeSucceeded(json.enum("resolver"), json.enum("transport"), json.optionalEnum("http"))
        "probe-failed" -> NetworkActivityEvent.DnsSmokeProbeFailed(when (json.getString("reason")) {
            "rejected" -> NetworkActivityProbeFailure.RejectedResponse
            "resolver" -> NetworkActivityProbeFailure.ResolverFailure(json.enum("failure"))
            else -> error("Unknown probe failure")
        })
        "fallback" -> NetworkActivityEvent.FallbackActivated(json.enum("fallback"), when (json.getString("cause")) {
            "unspecified" -> NetworkActivityFallbackActivationCause.ProducerUnspecified
            "query" -> NetworkActivityFallbackActivationCause.QueryFallback
            "check" -> NetworkActivityFallbackActivationCause.FallbackCheck
            "primary" -> NetworkActivityFallbackActivationCause.PrimaryFailure(json.enum("failure"))
            else -> error("Unknown fallback cause")
        })
        "device-recovered" -> NetworkActivityEvent.DeviceDnsFallbackRecovered
        "reconnect" -> NetworkActivityEvent.ReconnectNeeded(when (json.getString("reason")) {
            "rejected" -> NetworkActivityReconnectReason.RejectedResponse
            "recapture" -> NetworkActivityReconnectReason.DeviceDnsRecapture
            "runtime" -> NetworkActivityReconnectReason.RuntimeUnavailable
            "resolver" -> NetworkActivityReconnectReason.ResolverFailure(json.enum("failure"))
            else -> error("Unknown reconnect reason")
        })
        "recovered" -> NetworkActivityEvent.ConnectivityRecovered(json.enum("reason"))
        "settings-failed" -> NetworkActivityEvent.NetworkSettingsReapplyFailed(json.enum("reason"))
        else -> error("Unknown Network Activity event")
    }
    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String): T = enumValueOf(getString(key))
    private inline fun <reified T : Enum<T>> JSONObject.optionalEnum(key: String): T? = if (isNull(key)) null else enum(key)
}
