package com.lavasec.app.data

import com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger
import com.lavasec.app.foundation.backup.BackupLavaGuardUnlockRecord
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GuardianProgressStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.root, "progress.json")
    @Test fun `only observed monotonic uptime qualifies and reopen does not credit offline time`() {
        var tick = 0L
        val file = file()
        val store = GuardianProgressStore(file, { tick }, { ZoneOffset.UTC })
        val start = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
        store.observe(true, start, true)
        tick = 599_999
        store.observe(true, start + 599_999, true)
        assertEquals(0, store.state.value.qualifiedUsageDays)
        // Moving only the wall clock cannot invent the missing millisecond.
        store.observe(true, start + 86_400_000, true)
        assertEquals(0, store.state.value.qualifiedUsageDays)
        val reopened = GuardianProgressStore(file, { tick }, { ZoneOffset.UTC })
        tick += 10_000_000
        reopened.observe(true, start + 100_000_000, true)
        assertEquals(0, reopened.state.value.qualifiedUsageDays)
    }

    @Test fun `restore CAS preserves local usage and privacy clear preserves earned ledger`() {
        var tick = 0L
        val file = file()
        val store = GuardianProgressStore(file, { tick }, { ZoneOffset.UTC })
        val start = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
        store.observe(true, start, true)
        tick = 600_000
        store.observe(false, start + tick, true)
        val before = store.captureLedger()
        val restored = BackupLavaGuardAchievementLedger(listOf(BackupLavaGuardUnlockRecord("emerald", 17.0)))
        assertTrue(store.replaceLedger(restored, before))
        assertEquals(1, store.state.value.qualifiedUsageDays)
        assertFalse(store.replaceLedger(before, before))
        assertTrue(store.clearUsage())
        val reopened = GuardianProgressStore(file)
        assertEquals(0, reopened.state.value.qualifiedUsageDays)
        assertEquals(restored, reopened.captureLedger())
    }

    @Test fun `write failure and corrupt storage cannot publish or fabricate achievements`() {
        val fail = GuardianProgressStore(file(), writeOverride = { false })
        val earned = BackupLavaGuardAchievementLedger(listOf(BackupLavaGuardUnlockRecord("emerald", 17.0)))
        assertFalse(fail.replaceLedger(earned, fail.captureLedger()))
        assertTrue(fail.captureLedger().records.isEmpty())
        val corrupt = file().apply { writeText("broken") }
        val unavailable = GuardianProgressStore(corrupt)
        assertFalse(unavailable.state.value.available)
        assertThrows(IllegalStateException::class.java) { unavailable.captureLedger() }
        assertFalse(unavailable.clearUsage())
        assertEquals("broken", corrupt.readText())
    }
}
