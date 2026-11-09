package com.lavasec.app.diagnostics

import android.content.Context
import android.util.AtomicFile
import com.lavasec.app.foundation.health.DnsFallbackObservation
import com.lavasec.app.foundation.health.DnsFallbackReason
import com.lavasec.app.foundation.health.DnsFallbackResponseClass
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.health.ResolverTierDiagnosticObservation
import com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Credential-only numeric DNS evidence, independent of domain-history and debug-log consent. */
internal class CredentialDnsFallbackHistoryStore(
    context: Context,
    fileName: String = "dns-fallback-history-v1.json",
    private val onDirty: () -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val file: AtomicFile
    private var clearedAtMillis = -1L
    private var hours = sortedMapOf<Long, Hour>()
    private var incidents = emptyList<Incident>()
    private val seen = LinkedHashSet<Triple<Long, Long, Long>>()
    private var dirty = false
    private var closed = false

    init {
        require(!context.isDeviceProtectedStorage)
        require(fileName.matches(Regex("[A-Za-z0-9._-]+\\.json")))
        file = AtomicFile(File(context.noBackupFilesDir, fileName))
        runCatching {
            file.openRead().use { input ->
                val bytes = input.readBytesBounded()
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                require(json.getInt("schemaVersion") == 1)
                clearedAtMillis = json.optLong("clearedAtMillis", -1L).coerceAtLeast(-1L)
                val savedHours = json.getJSONArray("hours")
                require(savedHours.length() <= 24)
                repeat(savedHours.length()) { index ->
                    val row = savedHours.getJSONObject(index)
                    val start = row.getLong("startedAtMillis")
                    require(start >= 0L && start % HOUR_MILLIS == 0L)
                    val decisions = bounded(row.optLong("decisions"))
                    hours[start] = Hour(start,
                        decisions, bounded(row.optLong("attempted")).coerceAtMost(decisions),
                        outcomes = finiteCounts(row.optJSONObject("outcomes"), ResolverTierDiagnosticOutcome.entries.map { it.name }.toSet()),
                        tiers = finiteCounts(row.optJSONObject("tiers"), setOf("0", "1", "2")),
                        reasons = finiteCounts(row.optJSONObject("reasons"), DnsFallbackReason.entries.map { it.name }.toSet()),
                        responses = finiteCounts(row.optJSONObject("responses"), DnsFallbackResponseClass.entries.map { it.name }.toSet()))
                }
                val savedIncidents = json.getJSONArray("incidents")
                require(savedIncidents.length() <= 8)
                incidents = (0 until savedIncidents.length()).mapNotNull { index ->
                    val row = savedIncidents.getJSONObject(index)
                    runCatching { Incident(row.getLong("timestampMillis"), ResolverTierDiagnosticObservation(
                        tier = row.getInt("tier"), resolver = enumValueOf(row.getString("resolver")),
                        outcome = enumValueOf(row.getString("outcome")),
                        transport = row.optionalEnum("transport"),
                        failure = row.optionalEnum("failure") ?: row.optionalEnum("localAdmissionReason"),
                        peerLivenessOnly = row.optBoolean("peerLivenessOnly"),
                        responseClass = row.optionalEnum("responseClass"), responseCode = row.optionalInt("responseCode"),
                        queryType = row.optionalInt("queryType"), terminalAddressPresent = row.optionalBoolean("terminalAddressPresent"),
                        packetSent = row.optionalBoolean("packetSent")),
                        fallback = if (row.isNull("fallback")) null else row.getJSONObject("fallback").let { detail ->
                            runCatching { DnsFallbackObservation(detail.getInt("fromTier"), detail.getInt("toTier"),
                                enumValueOf(detail.getString("reason")), enumValueOf(detail.getString("disposition"))) }.getOrNull()
                        }, elapsedMillis = row.optionalLong("elapsedMillis")?.coerceIn(0L, 12_000L)) }.getOrNull()
                }.filter { it.atMillis > clearedAtMillis }
            }
        }.onFailure {
            // Optional corrupt evidence cannot discard any user's filtering or domain records.
            hours.clear(); incidents = emptyList(); clearedAtMillis = -1L
        }
        prune()
    }

    /** Called only after terminal delivery under exact graph/engine and privacy admission. */
    @Synchronized
    fun record(service: Long, engine: Long, startedAtMillis: Long, evidence: ResolverQueryHealthEvidence) {
        if (closed || startedAtMillis <= clearedAtMillis || startedAtMillis < 0L) return
        val now = nowMillis()
        val start = hour(startedAtMillis)
        val current = hour(now)
        if (start < current - 23L * HOUR_MILLIS || start > current) return
        if (!seen.add(Triple(service, engine, evidence.queryId))) return
        while (seen.size > 256) seen.remove(seen.first())
        if (evidence.tierObservations.isEmpty()) return
        prune()
        for (observation in evidence.tierObservations) {
            val bucket = hours.getOrPut(start) { Hour(start) }
            bucket.decisions = increment(bucket.decisions)
            if (observation.outcome != ResolverTierDiagnosticOutcome.NOT_ATTEMPTED) bucket.attempted = increment(bucket.attempted)
            bucket.outcomes.increment(observation.outcome.name)
            bucket.tiers.increment(observation.tier.toString())
            observation.responseClass?.let { bucket.responses.increment(it.name) }
            val fallback = evidence.fallbackObservations.firstOrNull { it.toTier == observation.tier }
            fallback?.let { bucket.reasons.increment(it.reason.name) }
            incidents = (incidents + Incident(startedAtMillis, observation, fallback,
                (evidence.responseLatencyMillis ?: evidence.latencyMillis)?.coerceIn(0L, 12_000L)))
                .sortedBy { it.atMillis }.takeLast(8)
        }
        dirty = true
        onDirty()
    }

    /** Periodic graph-owned flush keeps filesystem work off the packet publication path. */
    @Synchronized
    fun flush() {
        if (closed) return
        prune()
        if (!dirty) return
        write(encode())
        dirty = false
    }

    /** The durable fence prevents delayed pre-clear callbacks from rebuilding erased evidence. */
    @Synchronized
    fun clear() {
        check(!closed)
        val next = maxOf(clearedAtMillis, nowMillis())
        val bytes = JSONObject().put("schemaVersion", 1).put("clearedAtMillis", next)
            .put("hours", JSONArray()).put("incidents", JSONArray()).toString().toByteArray(Charsets.UTF_8)
        write(bytes)
        clearedAtMillis = next
        hours.clear(); incidents = emptyList(); dirty = false
    }

    /** A bounded frozen snapshot, taken under the same privacy boundary as the ZIP export. */
    @Synchronized
    fun exportSnapshot(): ByteArray {
        check(!closed)
        prune()
        return encode()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        try { flush() } finally { closed = true; hours.clear(); incidents = emptyList(); seen.clear() }
    }

    private fun prune() {
        val current = hour(nowMillis())
        val retained = hours.filterKeys { it in (current - 23L * HOUR_MILLIS)..current }.toSortedMap()
        if (retained != hours) { hours = retained; dirty = true }
        // Incidents retain the most recent eight summaries even after their hour ages out.
        incidents = incidents.sortedBy { it.atMillis }.takeLast(8)
    }

    private fun encode(): ByteArray {
        val buckets = JSONArray()
        hours.values.forEach { value -> buckets.put(JSONObject().put("startedAtMillis", value.start)
            .put("decisions", value.decisions).put("attempted", value.attempted)
            .put("outcomes", JSONObject(value.outcomes)).put("tiers", JSONObject(value.tiers))
            .put("reasons", JSONObject(value.reasons)).put("responses", JSONObject(value.responses))) }
        val summaries = JSONArray()
        incidents.forEach { value -> summaries.put(JSONObject().put("timestampMillis", value.atMillis)
            .put("tier", value.observation.tier).put("resolver", value.observation.resolver.name)
            .put("transport", value.observation.transport?.name).put("outcome", value.observation.outcome.name)
            .put("peerLivenessOnly", value.observation.peerLivenessOnly)
            .put("localAdmissionReason", if (value.observation.outcome == ResolverTierDiagnosticOutcome.NOT_ATTEMPTED)
                value.observation.failure?.name ?: JSONObject.NULL else JSONObject.NULL)
            .put("responseClass", value.observation.responseClass?.name ?: JSONObject.NULL)
            .put("responseCode", value.observation.responseCode ?: JSONObject.NULL)
            .put("queryType", value.observation.queryType ?: JSONObject.NULL)
            .put("terminalAddressPresent", value.observation.terminalAddressPresent ?: JSONObject.NULL)
            .put("attempted", value.observation.outcome != ResolverTierDiagnosticOutcome.NOT_ATTEMPTED)
            .put("packetSent", value.observation.packetSent ?: JSONObject.NULL)
            .put("elapsedMillis", value.elapsedMillis ?: JSONObject.NULL)
            .put("failure", if (value.observation.outcome == ResolverTierDiagnosticOutcome.NOT_ATTEMPTED)
                JSONObject.NULL else value.observation.failure?.name ?: JSONObject.NULL)
            .put("fallback", value.fallback?.let { detail ->
                JSONObject().put("fromTier", detail.fromTier).put("toTier", detail.toTier)
                    .put("reason", detail.reason.name).put("disposition", detail.disposition.name)
            })) }
        return JSONObject().put("schemaVersion", 1).put("clearedAtMillis", clearedAtMillis)
            .put("hours", buckets).put("incidents", summaries).toString().toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_BYTES) }
    }

    private fun write(bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class Incident(val atMillis: Long, val observation: ResolverTierDiagnosticObservation,
        val fallback: DnsFallbackObservation? = null, val elapsedMillis: Long? = null)
    private data class Hour(val start: Long, var decisions: Long = 0L, var attempted: Long = 0L,
        val outcomes: MutableMap<String, Long> = mutableMapOf(), val tiers: MutableMap<String, Long> = mutableMapOf(),
        val reasons: MutableMap<String, Long> = mutableMapOf(), val responses: MutableMap<String, Long> = mutableMapOf())

    companion object {
        const val MAX_BYTES = 128 * 1024
        private const val HOUR_MILLIS = 3_600_000L
        private fun hour(time: Long) = time.coerceAtLeast(0L) / HOUR_MILLIS * HOUR_MILLIS
        private fun bounded(value: Long) = value.coerceIn(0L, 4_294_967_295L)
        private fun increment(value: Long) = (value + 1L).coerceAtMost(4_294_967_295L)
        private fun MutableMap<String, Long>.increment(key: String) { this[key] = increment(this[key] ?: 0L) }
        private fun finiteCounts(json: JSONObject?, keys: Set<String>): MutableMap<String, Long> =
            keys.mapNotNull { key -> json?.takeIf { it.has(key) }?.let { key to bounded(it.optLong(key)) } }.toMap().toMutableMap()
        private inline fun <reified T : Enum<T>> JSONObject.optionalEnum(key: String): T? =
            if (isNull(key)) null else runCatching { enumValueOf<T>(getString(key)) }.getOrNull()
        private fun JSONObject.optionalInt(key: String): Int? = if (isNull(key)) null else getInt(key)
        private fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else getLong(key)
        private fun JSONObject.optionalBoolean(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)
    }
}
