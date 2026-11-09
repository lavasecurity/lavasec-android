package com.lavasec.app.foundation.preferences

import com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger
import com.lavasec.app.foundation.backup.BackupLavaGuardUnlockRecord
import java.time.Instant
import java.time.ZoneId

/** Frozen iOS usage-day and availability policy; local usage never enters a backup. */
object GuardianProgressPolicy {
    const val MINIMUM_DAY_MILLIS = 600_000L
    private const val APPLE_REFERENCE_UNIX_SECONDS = 978_307_200.0
    val goals = linkedMapOf("emberObsidian" to 3, "purpleObsidian" to 7, "obsidian" to 14,
        "strawberryObsidian" to 30, "emerald" to 60, "kiwiCreme" to 90, "aquamarine" to 120)

    fun normalize(ledger: BackupLavaGuardAchievementLedger): BackupLavaGuardAchievementLedger {
        require(ledger.records.all { it.guardID.isNotEmpty() && it.unlockedAt.isFinite() })
        return BackupLavaGuardAchievementLedger(ledger.records.groupBy { it.guardID }.values
            .map { records -> records.minBy { it.unlockedAt } }
            .sortedWith(compareBy<BackupLavaGuardUnlockRecord> { it.unlockedAt }.thenBy { it.guardID }))
    }

    fun available(id: String, currentLook: String, plus: Boolean,
        ledger: BackupLavaGuardAchievementLedger): Boolean =
        id == "original" || plus || id == currentLook || ledger.records.any { it.guardID == id }

    /** Splits a confirmed uptime interval at local calendar boundaries, including DST. */
    fun accrue(usage: Map<String, Long>, start: Long, end: Long, zone: ZoneId): Map<String, Long> {
        if (end <= start) return usage
        val result = usage.toMutableMap()
        var cursor = start
        while (cursor < end) {
            val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val boundary = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val segmentEnd = minOf(end, boundary)
            if (segmentEnd <= cursor) break
            // Beyond the qualifying threshold more private uptime data is unnecessary.
            result[day.toString()] = minOf(MINIMUM_DAY_MILLIS,
                (result[day.toString()] ?: 0L) + minOf(MINIMUM_DAY_MILLIS, segmentEnd - cursor))
            cursor = segmentEnd
        }
        return result
    }

    fun earned(usage: Map<String, Long>, ledger: BackupLavaGuardAchievementLedger,
        atMillis: Long): BackupLavaGuardAchievementLedger {
        val normalized = normalize(ledger)
        val days = usage.values.count { it >= MINIMUM_DAY_MILLIS }
        val existing = normalized.records.map { it.guardID }.toSet()
        val timestamp = atMillis / 1000.0 - APPLE_REFERENCE_UNIX_SECONDS
        return normalize(BackupLavaGuardAchievementLedger(normalized.records + goals
            .filter { (id, required) -> days >= required && id !in existing }
            .map { (id, _) -> BackupLavaGuardUnlockRecord(id, timestamp) }))
    }
}
