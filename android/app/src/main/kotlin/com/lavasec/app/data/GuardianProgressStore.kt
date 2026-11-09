package com.lavasec.app.data

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger
import com.lavasec.app.foundation.backup.BackupLavaGuardUnlockRecord
import com.lavasec.app.foundation.preferences.GuardianProgressPolicy
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

interface GuardianAchievementAuthority {
    fun captureLedger(): BackupLavaGuardAchievementLedger
    fun replaceLedger(value: BackupLavaGuardAchievementLedger, expected: BackupLavaGuardAchievementLedger): Boolean
}

interface GuardianUsageRecorder {
    fun observe(active: Boolean, atMillis: Long, enabled: Boolean): Boolean
    fun resetTracking()
}

data class GuardianProgressState(
    val available: Boolean = true,
    val usageByDay: Map<String, Long> = emptyMap(),
    val ledger: BackupLavaGuardAchievementLedger = BackupLavaGuardAchievementLedger(),
) {
    val qualifiedUsageDays: Int get() = usageByDay.values.count { it >= GuardianProgressPolicy.MINIMUM_DAY_MILLIS }
}

/** App-level credential storage. Active anchors are process-local: reopen cannot credit downtime. */
class GuardianProgressStore internal constructor(
    file: File,
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val writeOverride: ((ByteArray) -> Boolean)? = null,
) : GuardianAchievementAuthority, GuardianUsageRecorder {
    private val atomic = AtomicFile(file)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<GuardianProgressState> = mutable.asStateFlow()
    private var anchor: Pair<Long, Long>? = null

    @Synchronized override fun captureLedger(): BackupLavaGuardAchievementLedger {
        check(mutable.value.available) { "Guardian achievement storage is unavailable" }
        return mutable.value.ledger.copy(records = mutable.value.ledger.records.toList())
    }

    @Synchronized override fun replaceLedger(value: BackupLavaGuardAchievementLedger,
        expected: BackupLavaGuardAchievementLedger): Boolean {
        val before = mutable.value
        if (!before.available || before.ledger != expected) return false
        val normalized = runCatching { GuardianProgressPolicy.normalize(value) }.getOrNull() ?: return false
        return persist(before.copy(ledger = normalized))
    }

    @Synchronized override fun resetTracking() { anchor = null }

    @Synchronized override fun observe(active: Boolean, atMillis: Long, enabled: Boolean): Boolean {
        val tick = monotonicMillis()
        val previous = anchor
        anchor = if (active && enabled) atMillis to tick else null
        val before = mutable.value
        if (!before.available || !enabled || previous == null) return false
        // Both clocks must advance. A wall-clock adjustment or replay cannot manufacture usage.
        val delta = minOf((atMillis - previous.first).coerceAtLeast(0L),
            (tick - previous.second).coerceAtLeast(0L))
        if (delta == 0L) return false
        val usage = GuardianProgressPolicy.accrue(before.usageByDay, atMillis - delta, atMillis, zone())
        val next = before.copy(usageByDay = usage,
            ledger = GuardianProgressPolicy.earned(usage, before.ledger, atMillis))
        return persist(next)
    }

    /** Privacy clearing retains already-earned portable achievements. */
    @Synchronized fun clearUsage(): Boolean {
        anchor = null
        val before = mutable.value
        return before.available && persist(before.copy(usageByDay = emptyMap()))
    }

    private fun load(): GuardianProgressState = try {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) GuardianProgressState()
        else {
            val json = JSONObject(atomic.openRead().use { String(it.readBytes(), Charsets.UTF_8) })
            require(json.getInt("schema") == 1)
            val days = json.getJSONObject("usageByDay")
            val usage = days.keys().asSequence().associateWith { key ->
                LocalDate.parse(key)
                days.getLong(key).also { require(it in 0..GuardianProgressPolicy.MINIMUM_DAY_MILLIS) }
            }
            val records = json.getJSONArray("records")
            GuardianProgressState(usageByDay = usage, ledger = GuardianProgressPolicy.normalize(
                BackupLavaGuardAchievementLedger((0 until records.length()).map { i ->
                    val record = records.getJSONObject(i)
                    BackupLavaGuardUnlockRecord(record.getString("guardID"), record.getDouble("unlockedAt"))
                })))
        }
    } catch (_: Exception) { GuardianProgressState(available = false) }

    private fun persist(value: GuardianProgressState): Boolean {
        if (value == mutable.value) return true
        val bytes = JSONObject().put("schema", 1).put("usageByDay", JSONObject(value.usageByDay))
            .put("records", JSONArray().apply { value.ledger.records.forEach { record ->
                put(JSONObject().put("guardID", record.guardID).put("unlockedAt", record.unlockedAt))
            } }).toString().toByteArray(Charsets.UTF_8)
        val written = if (writeOverride != null) writeOverride.invoke(bytes) else {
            var stream: java.io.FileOutputStream? = null
            try {
                atomic.baseFile.parentFile?.mkdirs()
                stream = atomic.startWrite()
                stream.write(bytes)
                atomic.finishWrite(stream)
                true
            } catch (_: Exception) {
                stream?.let { atomic.failWrite(it) }
                false
            }
        }
        if (written) mutable.value = value
        return written
    }

    companion object {
        private val stores = mutableMapOf<String, GuardianProgressStore>()
        @Synchronized fun get(context: Context): GuardianProgressStore {
            val file = File(context.applicationContext.filesDir, "guardian-progress-v1.json")
            return stores.getOrPut(file.absolutePath) { GuardianProgressStore(file) }
        }
    }
}
