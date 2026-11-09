package com.lavasec.app.foundation.preferences

import com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger
import com.lavasec.app.foundation.backup.BackupLavaGuardUnlockRecord
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class GuardianProgressTest {
    @Test fun `ten confirmed minutes on three distinct days earns first look once`() {
        var usage = emptyMap<String, Long>()
        var ledger = BackupLavaGuardAchievementLedger()
        for (day in 1..3) {
            val start = Instant.parse("2026-10-0${day}T12:00:00Z").toEpochMilli()
            usage = GuardianProgressPolicy.accrue(usage, start, start + 599_999, ZoneId.of("UTC"))
            ledger = GuardianProgressPolicy.earned(usage, ledger, start + 599_999)
            assertTrue(ledger.records.isEmpty())
            usage = GuardianProgressPolicy.accrue(usage, start + 599_999, start + 600_000, ZoneId.of("UTC"))
        }
        ledger = GuardianProgressPolicy.earned(usage, ledger, 978_307_201_000)
        assertEquals(listOf(BackupLavaGuardUnlockRecord("emberObsidian", 1.0)), ledger.records)
        assertEquals(ledger, GuardianProgressPolicy.earned(usage, ledger, 978_307_999_000))
    }

    @Test fun `calendar boundaries include daylight saving and do not count a partial day`() {
        val zone = ZoneId.of("America/New_York")
        val start = Instant.parse("2026-03-08T04:55:00Z").toEpochMilli()
        val end = Instant.parse("2026-03-08T07:05:00Z").toEpochMilli()
        val usage = GuardianProgressPolicy.accrue(emptyMap(), start, end, zone)
        assertEquals(300_000L, usage["2026-03-07"])
        assertEquals(600_000L, usage["2026-03-08"])
        assertEquals(usage, GuardianProgressPolicy.accrue(usage, end, start, zone))
    }

    @Test fun `portable ledger normalizes earliest timestamps and entitlement courtesy rules`() {
        val ledger = GuardianProgressPolicy.normalize(BackupLavaGuardAchievementLedger(listOf(
            BackupLavaGuardUnlockRecord("emerald", 20.0), BackupLavaGuardUnlockRecord("emerald", 10.0),
            BackupLavaGuardUnlockRecord("obsidian", 10.0))))
        assertEquals(listOf("emerald", "obsidian"), ledger.records.map { it.guardID })
        assertEquals(10.0, ledger.records[0].unlockedAt, 0.0)
        assertTrue(GuardianProgressPolicy.available("emerald", "original", false, ledger))
        assertTrue(GuardianProgressPolicy.available("aquamarine", "aquamarine", false, ledger))
        assertTrue(GuardianProgressPolicy.available("aquamarine", "original", true, ledger))
        assertFalse(GuardianProgressPolicy.available("aquamarine", "original", false, ledger))
    }
}
