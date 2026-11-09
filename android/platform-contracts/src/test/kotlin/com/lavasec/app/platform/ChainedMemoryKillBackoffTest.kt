package com.lavasec.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainedMemoryKillBackoffTest {

    /** A scan whose exits all carry the memory reason. */
    private fun killScan(vararg epochMillis: Long): ChainedMemoryKillScan =
        ChainedMemoryKillScan(epochMillis.map { ChainedProcessExit(it, isLowMemory = true) })

    /** A scan whose single exit is NOT a memory kill. */
    private fun nonMemoryScan(epochMillis: Long): ChainedMemoryKillScan =
        ChainedMemoryKillScan(listOf(ChainedProcessExit(epochMillis, isLowMemory = false)))

    private fun mixedScan(vararg exits: Pair<Long, Boolean>): ChainedMemoryKillScan =
        ChainedMemoryKillScan(exits.map { (at, low) -> ChainedProcessExit(at, isLowMemory = low) })

    private fun running(startedAt: Long): ChainedDeviceEligibilityRecord =
        ChainedDeviceEligibilityRecord(
            lastConsumedExitEpochMillis = startedAt,
            activeChainedSessionStartEpochMillis = startedAt,
        )

    @Test
    fun `the clean state has no strikes and no exclusion`() {
        val clean = ChainedMemoryKillBackoffPolicy.clean
        assertEquals(0, clean.killStreak)
        assertFalse(clean.excluded)
        assertNull(clean.lastKillEpochMillis)
    }

    @Test
    fun `three strikes exclude, and the streak is clamped at the threshold`() {
        var state = ChainedMemoryKillBackoffPolicy.clean
        state = ChainedMemoryKillBackoffPolicy.recordKill(state, 100L)
        assertEquals(1, state.killStreak)
        assertFalse(state.excluded)
        assertEquals(100L, state.lastKillEpochMillis)

        state = ChainedMemoryKillBackoffPolicy.recordKill(state, 200L)
        assertEquals(2, state.killStreak)
        assertFalse(state.excluded)

        state = ChainedMemoryKillBackoffPolicy.recordKill(state, 300L)
        assertEquals(ChainedMemoryKillBackoffPolicy.KILLS_BEFORE_EXCLUSION, state.killStreak)
        assertTrue(state.excluded)

        state = ChainedMemoryKillBackoffPolicy.recordKill(state, 400L)
        assertEquals(ChainedMemoryKillBackoffPolicy.KILLS_BEFORE_EXCLUSION, state.killStreak)
        assertTrue(state.excluded)
        assertEquals(400L, state.lastKillEpochMillis)
    }

    @Test
    fun `an exclusion is sticky across a hit that would not have tripped it`() {
        val excluded = ChainedMemoryKillBackoffState(killStreak = 1, excluded = true)
        assertTrue(ChainedMemoryKillBackoffPolicy.recordKill(excluded, 500L).excluded)
    }

    @Test
    fun `an out-of-range persisted streak cannot widen tolerance or trap the next strike`() {
        val negative = ChainedMemoryKillBackoffPolicy.recordKill(
            ChainedMemoryKillBackoffState(killStreak = Int.MIN_VALUE),
            1L,
        )
        assertEquals(1, negative.killStreak)

        val oversized = ChainedMemoryKillBackoffPolicy.recordKill(
            ChainedMemoryKillBackoffState(killStreak = Int.MAX_VALUE),
            1L,
        )
        assertEquals(ChainedMemoryKillBackoffPolicy.KILLS_BEFORE_EXCLUSION, oversized.killStreak)
        assertTrue(oversized.excluded)
    }

    @Test
    fun `the explicit retry clears the exclusion and restores full tolerance`() {
        assertEquals(ChainedMemoryKillBackoffPolicy.clean, ChainedMemoryKillBackoffPolicy.clear())
    }

    @Test
    fun `a clean session resets the streak but keeps a tripped exclusion sticky`() {
        val tripped = ChainedMemoryKillBackoffState(
            killStreak = ChainedMemoryKillBackoffPolicy.KILLS_BEFORE_EXCLUSION,
            excluded = true,
        )
        val reset = ChainedMemoryKillBackoffPolicy.resetStreak(tripped)
        assertEquals(0, reset.killStreak)
        assertTrue(reset.excluded)
    }

    @Test
    fun `a low-memory kill inside an active chained session counts`() {
        val reconciled = ChainedDeviceEligibilityReconciler.reconcile(
            record = running(startedAt = 300L),
            scan = killScan(350L),
            nowEpochMillis = 400L,
        )
        assertEquals(1, reconciled.backoff.killStreak)
        assertNull(reconciled.activeChainedSessionStartEpochMillis)
        assertEquals(350L, reconciled.lastConsumedExitEpochMillis)
    }

    @Test
    fun `only the first exit after the witnessed session counts`() {
        // Three retained low-memory exits, but the witness is ONE session that ended at the first.
        // Later exits are background deaths and must not strike the sticky exclusion.
        val reconciled = ChainedDeviceEligibilityReconciler.reconcile(
            record = running(startedAt = 100L),
            scan = killScan(150L, 250L, 350L),
            nowEpochMillis = 400L,
        )
        assertEquals(1, reconciled.backoff.killStreak)
        assertFalse(reconciled.backoff.excluded)
        assertNull(reconciled.activeChainedSessionStartEpochMillis)
        assertEquals(350L, reconciled.lastConsumedExitEpochMillis)
    }

    @Test
    fun `a non-memory first exit does not strike even when later exits were memory kills`() {
        // The process that owned the witness crashed for a non-memory reason; a later background
        // memory kill is a different process and must not attribute to this session.
        val reconciled = ChainedDeviceEligibilityReconciler.reconcile(
            record = running(startedAt = 100L),
            scan = mixedScan(150L to false, 250L to true),
            nowEpochMillis = 400L,
        )
        assertEquals(0, reconciled.backoff.killStreak)
        assertNull(reconciled.activeChainedSessionStartEpochMillis)
        assertEquals(250L, reconciled.lastConsumedExitEpochMillis)
    }

    @Test
    fun `a live read with no exit preserves the witness so the later kill still counts`() {
        var record = running(startedAt = 100L)

        // Readiness refresh while the session is live: nothing has exited since the start, so the
        // witness must survive. Clearing it here would make the coming kill look like background.
        record = ChainedDeviceEligibilityReconciler.reconcile(
            record = record,
            scan = ChainedMemoryKillScan(emptyList()),
            nowEpochMillis = 200L,
        )
        assertEquals(100L, record.activeChainedSessionStartEpochMillis)
        assertEquals(0, record.backoff.killStreak)

        record = ChainedDeviceEligibilityReconciler.reconcile(
            record = record,
            scan = killScan(150L),
            nowEpochMillis = 300L,
        )
        assertEquals(1, record.backoff.killStreak)
        assertNull(record.activeChainedSessionStartEpochMillis)
    }

    @Test
    fun `a read that sees only a pre-session exit preserves the witness`() {
        val record = ChainedDeviceEligibilityReconciler.reconcile(
            record = running(startedAt = 100L),
            scan = nonMemoryScan(99L),
            nowEpochMillis = 200L,
        )
        assertEquals(100L, record.activeChainedSessionStartEpochMillis)
    }

    @Test
    fun `an exit during the session ends it even when it was not a memory kill`() {
        val record = ChainedDeviceEligibilityReconciler.reconcile(
            record = running(startedAt = 100L),
            scan = nonMemoryScan(150L),
            nowEpochMillis = 200L,
        )
        assertEquals(0, record.backoff.killStreak)
        assertNull(record.activeChainedSessionStartEpochMillis)
    }

    @Test
    fun `explicit evidence retires the session and is not double counted`() {
        var record = running(startedAt = 100L)
        record = ChainedDeviceEligibilityReconciler.explicitMemoryKillRecorded(record, 150L)
        assertEquals(1, record.backoff.killStreak)
        assertNull(record.activeChainedSessionStartEpochMillis)
        assertEquals(150L, record.lastConsumedExitEpochMillis)

        // A later read still sees the same kill in OS history; the advanced baseline retires it.
        record = ChainedDeviceEligibilityReconciler.reconcile(
            record = record,
            scan = killScan(150L),
            nowEpochMillis = 200L,
        )
        assertEquals(1, record.backoff.killStreak)
    }

    @Test
    fun `a background kill with no active chained session does not count`() {
        val reconciled = ChainedDeviceEligibilityReconciler.reconcile(
            record = ChainedDeviceEligibilityRecord(lastConsumedExitEpochMillis = 100L),
            scan = killScan(150L),
            nowEpochMillis = 200L,
        )
        assertEquals(0, reconciled.backoff.killStreak)
        assertEquals(150L, reconciled.lastConsumedExitEpochMillis)
    }

    @Test
    fun `a kill before the session started does not count against it`() {
        // The scan can retain an older kill; only exits at or after the session start attribute.
        val reconciled = ChainedDeviceEligibilityReconciler.reconcile(
            record = running(startedAt = 300L),
            scan = killScan(150L, 350L),
            nowEpochMillis = 400L,
        )
        assertEquals(1, reconciled.backoff.killStreak)
    }

    @Test
    fun `starting a session consumes pending evidence and pulls the baseline forward`() {
        var record = ChainedDeviceEligibilityRecord(
            lastConsumedExitEpochMillis = 100L,
            activeChainedSessionStartEpochMillis = 100L,
        )
        record = ChainedDeviceEligibilityReconciler.startChainedSession(
            record = record,
            scan = killScan(150L),
            nowEpochMillis = 200L,
        )
        // The prior session's kill is counted, the old marker is replaced, and the new baseline
        // is the new start so an exit before it can never be attributed to the new session.
        assertEquals(1, record.backoff.killStreak)
        assertEquals(200L, record.activeChainedSessionStartEpochMillis)
        assertEquals(200L, record.lastConsumedExitEpochMillis)
    }

    @Test
    fun `a pre-feature kill is retired by the first session start and never strikes`() {
        var record = ChainedDeviceEligibilityReconciler.startChainedSession(
            record = ChainedDeviceEligibilityRecord(),
            scan = killScan(100L),
            nowEpochMillis = 300L,
        )
        assertEquals(0, record.backoff.killStreak)
        assertEquals(300L, record.lastConsumedExitEpochMillis)

        record = ChainedDeviceEligibilityReconciler.reconcile(
            record = record,
            scan = killScan(350L),
            nowEpochMillis = 400L,
        )
        assertEquals(1, record.backoff.killStreak)
    }

    @Test
    fun `a clean stop resets the streak, clears the marker, and advances the baseline`() {
        val stopped = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
            record = ChainedDeviceEligibilityRecord(
                backoff = ChainedMemoryKillBackoffState(killStreak = 2, excluded = true),
                lastConsumedExitEpochMillis = 100L,
                activeChainedSessionStartEpochMillis = 100L,
            ),
            nowEpochMillis = 300L,
        )
        assertEquals(0, stopped.backoff.killStreak)
        assertTrue(stopped.backoff.excluded)
        assertNull(stopped.activeChainedSessionStartEpochMillis)
        assertEquals(300L, stopped.lastConsumedExitEpochMillis)
    }

    @Test
    fun `an owning clean stop preserves a newer make-before-break witness`() {
        // The predecessor (started at 100) retires while a candidate recorded a NEWER witness (200)
        // during a make-before-break reload. The candidate is the live chained runtime; clearing its
        // witness here would leave its coming low-memory kill unattributed.
        val predecessor = ChainedDeviceEligibilityRecord(
            backoff = ChainedMemoryKillBackoffState(killStreak = 1),
            lastConsumedExitEpochMillis = 100L,
            activeChainedSessionStartEpochMillis = 200L,
            activeChainedSessionOwnerToken = 200L,
        )
        val preserved = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
            record = predecessor,
            nowEpochMillis = 300L,
            owningStartEpochMillis = 100L,
        )
        assertEquals(200L, preserved.activeChainedSessionStartEpochMillis)
        assertEquals(0, preserved.backoff.killStreak)
        assertEquals(300L, preserved.lastConsumedExitEpochMillis)

        // The owner's OWN witness is still cleared: a normal clean stop must not leave a stale
        // marker for the next read to attribute a background kill to.
        val own = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
            record = predecessor,
            nowEpochMillis = 300L,
            owningStartEpochMillis = 200L,
        )
        assertNull(own.activeChainedSessionStartEpochMillis)

        // No active witness: the clean stop is a no-op on the marker and still resets the streak.
        val absent = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
            record = ChainedDeviceEligibilityRecord(
                backoff = ChainedMemoryKillBackoffState(killStreak = 2),
            ),
            nowEpochMillis = 300L,
            owningStartEpochMillis = 100L,
        )
        assertNull(absent.activeChainedSessionStartEpochMillis)
        assertEquals(0, absent.backoff.killStreak)
    }

    @Test
    fun `an owning clean stop preserves a successor witness across a backward clock move`() {
        // The wall clock moved BACKWARD between the predecessor's start (200) and the
        // make-before-break candidate's (150). Ordering the witness by epoch would drop the
        // candidate's witness when the predecessor retires, leaving the live runtime
        // unattributed; clearing only the exact witness the owner wrote preserves it
        // (finding 4021942644).
        val predecessor = ChainedDeviceEligibilityRecord(
            backoff = ChainedMemoryKillBackoffState(killStreak = 1),
            lastConsumedExitEpochMillis = 100L,
            activeChainedSessionStartEpochMillis = 150L,
            activeChainedSessionOwnerToken = 150L,
        )
        val preserved = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
            record = predecessor,
            nowEpochMillis = 160L,
            owningStartEpochMillis = 200L,
        )
        assertEquals(150L, preserved.activeChainedSessionStartEpochMillis)
        assertEquals(150L, preserved.activeChainedSessionOwnerToken)
        assertEquals(0, preserved.backoff.killStreak)
    }

    @Test
    fun `an owning clean stop clears a witness the wall clock rebased`() {
        // Start under a wrong future clock. The owner token written here is 100_000, recorded
        // separately from the correlation timestamp.
        var record = ChainedDeviceEligibilityReconciler.startChainedSession(
            record = ChainedDeviceEligibilityRecord(),
            scan = null,
            nowEpochMillis = 100_000L,
        )
        assertEquals(100_000L, record.activeChainedSessionOwnerToken)

        // Clock corrected: a live read rebases the correlation witness to 500, but ownership must
        // survive so the owner's clean stop still recognises its own witness.
        record = ChainedDeviceEligibilityReconciler.reconcile(
            record = record,
            scan = ChainedMemoryKillScan(emptyList()),
            nowEpochMillis = 500L,
        )
        assertEquals(500L, record.activeChainedSessionStartEpochMillis)
        assertEquals(100_000L, record.activeChainedSessionOwnerToken)

        val stopped = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
            record = record,
            nowEpochMillis = 600L,
            owningStartEpochMillis = 100_000L,
        )
        // Before the separate token this exact-epoch fence left 500 in place (500 != 100_000), and a
        // later background low-memory exit at/after 500 would strike an already-ended session.
        assertNull(stopped.activeChainedSessionStartEpochMillis)
        assertNull(stopped.activeChainedSessionOwnerToken)
    }

    @Test
    fun `an owning clean stop still preserves a different runtime's rebased witness`() {
        // A make-before-break candidate recorded its witness at 200; a corrected-past read then
        // rebased the correlation timestamp to 150 while the ownership token stayed 200. The
        // predecessor's clean stop (owning 100) must preserve the candidate's witness.
        var record = ChainedDeviceEligibilityReconciler.startChainedSession(
            record = ChainedDeviceEligibilityRecord(),
            scan = null,
            nowEpochMillis = 200L,
        )
        record = ChainedDeviceEligibilityReconciler.reconcile(
            record = record,
            scan = ChainedMemoryKillScan(emptyList()),
            nowEpochMillis = 150L,
        )
        assertEquals(150L, record.activeChainedSessionStartEpochMillis)
        assertEquals(200L, record.activeChainedSessionOwnerToken)

        val preserved = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
            record = record,
            nowEpochMillis = 160L,
            owningStartEpochMillis = 100L,
        )
        assertEquals(150L, preserved.activeChainedSessionStartEpochMillis)
        assertEquals(200L, preserved.activeChainedSessionOwnerToken)
    }

    @Test
    fun `a stop matching the rebased start of an ownerless witness does not clear it`() {
        // A legacy record carries no ownership token, so the removed pre-token fallback would match
        // its correlation timestamp alone. A corrected-past live read rebases that timestamp
        // (200 -> 150) and the clean stop then passes the rebased value: the fallback would clear
        // the witness, while identity-by-token alone preserves it because no token names this
        // runtime. This is the token-less case the fallback removal changed.
        var record = ChainedDeviceEligibilityReconciler.reconcile(
            record = ChainedDeviceEligibilityRecord(
                activeChainedSessionStartEpochMillis = 200L,
            ),
            scan = ChainedMemoryKillScan(emptyList()),
            nowEpochMillis = 150L,
        )
        assertEquals(150L, record.activeChainedSessionStartEpochMillis)
        assertNull(record.activeChainedSessionOwnerToken)

        val preserved = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
            record = record,
            nowEpochMillis = 160L,
            owningStartEpochMillis = 150L,
        )
        assertEquals(150L, preserved.activeChainedSessionStartEpochMillis)
        assertNull(preserved.activeChainedSessionOwnerToken)
    }

    @Test
    fun `a stop cannot clear a legacy witness with no ownership token`() {
        // A record persisted before the token existed has no owner identity, so no clean stop may
        // claim it: matching the rebased correlation timestamp could clear a successor's witness.
        // It is preserved and retires only through exit evidence or an explicit kill.
        val legacy = ChainedDeviceEligibilityRecord(
            activeChainedSessionStartEpochMillis = 500L,
        )
        val preserved = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
            record = legacy,
            nowEpochMillis = 600L,
            owningStartEpochMillis = 500L,
        )
        assertEquals(500L, preserved.activeChainedSessionStartEpochMillis)
        assertNull(preserved.activeChainedSessionOwnerToken)
    }

    @Test
    fun `kill, clean session, kill, kill does not trip the exclusion`() {
        fun inSessionKill(record: ChainedDeviceEligibilityRecord, at: Long): ChainedDeviceEligibilityRecord {
            val started = ChainedDeviceEligibilityReconciler.startChainedSession(record, null, at)
            return ChainedDeviceEligibilityReconciler.reconcile(started, killScan(at + 1), at + 2)
        }

        var record = inSessionKill(ChainedDeviceEligibilityRecord(), 100L)
        assertEquals(1, record.backoff.killStreak)
        assertFalse(record.backoff.excluded)

        record = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(record, 200L)
        assertEquals(0, record.backoff.killStreak)

        record = inSessionKill(record, 300L)
        assertEquals(1, record.backoff.killStreak)

        record = inSessionKill(record, 500L)
        assertEquals(2, record.backoff.killStreak)
        assertFalse("two isolated kills after a clean session must not trip", record.backoff.excluded)
    }

    @Test
    fun `three consecutive in-session kills do trip the exclusion`() {
        fun inSessionKill(record: ChainedDeviceEligibilityRecord, at: Long): ChainedDeviceEligibilityRecord {
            val started = ChainedDeviceEligibilityReconciler.startChainedSession(record, null, at)
            return ChainedDeviceEligibilityReconciler.reconcile(started, killScan(at + 1), at + 2)
        }

        var record = ChainedDeviceEligibilityRecord()
        record = inSessionKill(record, 100L)
        record = inSessionKill(record, 300L)
        record = inSessionKill(record, 500L)
        assertEquals(3, record.backoff.killStreak)
        assertTrue(record.backoff.excluded)
    }

    @Test
    fun `a scan merge never lowers the baseline while time advances`() {
        val reconciled = ChainedDeviceEligibilityReconciler.reconcile(
            record = running(startedAt = 9_000L),
            scan = nonMemoryScan(2_000L),
            nowEpochMillis = 12_000L,
        )
        assertEquals(9_000L, reconciled.lastConsumedExitEpochMillis)
    }

    @Test
    fun `a corrected-past clock rebases a future baseline at a new session start`() {
        // A wrong clock left the mark at 100_000; the clock is then corrected to 500. The new
        // session start must rebase the mark, or the probe discards the real exit at 600.
        val started = ChainedDeviceEligibilityReconciler.startChainedSession(
            record = ChainedDeviceEligibilityRecord(
                lastConsumedExitEpochMillis = 100_000L,
            ),
            scan = null,
            nowEpochMillis = 500L,
        )
        assertEquals(500L, started.lastConsumedExitEpochMillis)
        assertEquals(500L, started.activeChainedSessionStartEpochMillis)

        val reconciled = ChainedDeviceEligibilityReconciler.reconcile(
            record = started,
            scan = killScan(600L),
            nowEpochMillis = 700L,
        )
        assertEquals(1, reconciled.backoff.killStreak)
    }

    @Test
    fun `a corrected-past start does not double count an exit it consumes`() {
        // Wrong-clock session start leaves a future mark (no kill yet).
        var record = ChainedDeviceEligibilityReconciler.startChainedSession(
            record = ChainedDeviceEligibilityRecord(),
            scan = killScan(600L),
            nowEpochMillis = 100_000L,
        )
        assertEquals(0, record.backoff.killStreak)
        assertEquals(100_000L, record.lastConsumedExitEpochMillis)

        // Clock corrected: a new session starts at 500. The retained exit at 600 is counted once,
        // and the new mark folds in that consumed exit so the next read cannot re-count it.
        record = ChainedDeviceEligibilityReconciler.startChainedSession(
            record = record,
            scan = killScan(600L),
            nowEpochMillis = 500L,
        )
        assertEquals(1, record.backoff.killStreak)
        assertEquals(600L, record.lastConsumedExitEpochMillis)
        assertEquals(500L, record.activeChainedSessionStartEpochMillis)

        // The probe filtering by the new mark returns nothing for the exit at 600.
        record = ChainedDeviceEligibilityReconciler.reconcile(
            record = record,
            scan = ChainedMemoryKillScan(emptyList()),
            nowEpochMillis = 700L,
        )
        assertEquals(1, record.backoff.killStreak)
    }

    @Test
    fun `a corrected-past read rebases a future baseline`() {
        val reconciled = ChainedDeviceEligibilityReconciler.reconcile(
            record = ChainedDeviceEligibilityRecord(lastConsumedExitEpochMillis = 100_000L),
            scan = ChainedMemoryKillScan(emptyList()),
            nowEpochMillis = 500L,
        )
        assertEquals(500L, reconciled.lastConsumedExitEpochMillis)
    }

    @Test
    fun `a future witness is rebased to the earliest retained exit on a corrected-past read`() {
        val reconciled = ChainedDeviceEligibilityReconciler.reconcile(
            record = ChainedDeviceEligibilityRecord(
                lastConsumedExitEpochMillis = 100_000L,
                activeChainedSessionStartEpochMillis = 100_000L,
            ),
            scan = killScan(600L),
            nowEpochMillis = 700L,
        )
        assertEquals(1, reconciled.backoff.killStreak)
        assertNull(reconciled.activeChainedSessionStartEpochMillis)
        assertEquals(700L, reconciled.lastConsumedExitEpochMillis)
    }

    @Test
    fun `a corrected-past clock rebases a future witness so the retained exit still strikes`() {
        var record = ChainedDeviceEligibilityRecord(
            lastConsumedExitEpochMillis = 100_000L,
            activeChainedSessionStartEpochMillis = 100_000L,
        )
        // Read at corrected now=500: the probe (using the stale future baseline) returned nothing,
        // but the witness must rebase with the baseline or the coming exit cannot be attributed.
        record = ChainedDeviceEligibilityReconciler.reconcile(
            record = record,
            scan = ChainedMemoryKillScan(emptyList()),
            nowEpochMillis = 500L,
        )
        assertEquals(500L, record.lastConsumedExitEpochMillis)
        assertEquals(500L, record.activeChainedSessionStartEpochMillis)

        // The process is killed at corrected 600; the next read returns it and it strikes.
        record = ChainedDeviceEligibilityReconciler.reconcile(
            record = record,
            scan = killScan(600L),
            nowEpochMillis = 700L,
        )
        assertEquals(1, record.backoff.killStreak)
        assertNull(record.activeChainedSessionStartEpochMillis)
    }

    @Test
    fun `an explicit kill rebases a future baseline`() {
        val recorded = ChainedDeviceEligibilityReconciler.explicitMemoryKillRecorded(
            record = ChainedDeviceEligibilityRecord(lastConsumedExitEpochMillis = 100_000L),
            nowEpochMillis = 500L,
        )
        assertEquals(500L, recorded.lastConsumedExitEpochMillis)
    }

    @Test
    fun `a clean stop rebases a future baseline`() {
        val stopped = ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
            record = ChainedDeviceEligibilityRecord(lastConsumedExitEpochMillis = 100_000L),
            nowEpochMillis = 500L,
        )
        assertEquals(500L, stopped.lastConsumedExitEpochMillis)
    }

    @Test
    fun `the exit scan policy requests the complete available history`() {
        assertEquals(0, ChainedProcessExitScanPolicy.REQUEST_ALL_AVAILABLE)
    }

    @Test
    fun `a full bounded exit request fails closed instead of classifying a truncated sequence`() {
        val exits = (1L..32L).map { ChainedProcessExit(it, isLowMemory = true) }
        assertEquals(
            ChainedMemoryKillScanResult.Failed,
            ChainedProcessExitScanPolicy.classify(exits, afterEpochMillis = null, requestedLimit = 32),
        )
    }

    @Test
    fun `an unfilled bounded exit request is classified`() {
        val exits = listOf(
            ChainedProcessExit(50L, isLowMemory = true),
            ChainedProcessExit(60L, isLowMemory = false),
        )
        assertEquals(
            ChainedMemoryKillScanResult.Scanned(ChainedMemoryKillScan(exits)),
            ChainedProcessExitScanPolicy.classify(exits, afterEpochMillis = null, requestedLimit = 32),
        )
    }

    @Test
    fun `the exit scan policy filters by the consumed baseline`() {
        val exits = listOf(
            ChainedProcessExit(50L, isLowMemory = true),
            ChainedProcessExit(150L, isLowMemory = true),
        )
        assertEquals(
            ChainedMemoryKillScanResult.Scanned(
                ChainedMemoryKillScan(listOf(ChainedProcessExit(150L, isLowMemory = true))),
            ),
            ChainedProcessExitScanPolicy.classify(exits, afterEpochMillis = 100L, requestedLimit = 0),
        )
    }

    @Test
    fun `a long retained history still identifies the first session-ending exit`() {
        // The exit that ended the session (101) sits at the front of a long list; a bounded window
        // that dropped it would have misclassified a later background exit.
        val exits = (1L..100L).map { offset ->
            ChainedProcessExit(100L + offset, isLowMemory = offset == 1L)
        }
        val reconciled = ChainedDeviceEligibilityReconciler.reconcile(
            record = running(startedAt = 100L),
            scan = ChainedMemoryKillScan(exits),
            nowEpochMillis = 1_000L,
        )
        assertEquals(1, reconciled.backoff.killStreak)
        assertEquals(200L, reconciled.lastConsumedExitEpochMillis)
        assertNull(reconciled.activeChainedSessionStartEpochMillis)
    }
}
