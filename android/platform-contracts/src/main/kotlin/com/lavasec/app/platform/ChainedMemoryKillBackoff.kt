package com.lavasec.app.platform

/**
 * What the device has learned about its own ability to run the chained engine without being
 * memory-killed.
 *
 * Mirrors the durable half of iOS `ChainedStartupCrashLoopPolicy.State` (its `hasTripped`
 * exclusion), narrowed to the memory-kill evidence Android can actually observe. The value is
 * persisted between processes because the whole mechanism depends on surviving a kill: a
 * counter that lived only in memory would be reset by the very event it exists to count.
 *
 * [excluded] is sticky and stored rather than derived from [killStreak]. Deriving it would
 * un-exclude a device the moment an ordinary clean session reset the streak — the breaker
 * would clear itself without the explicit retry boundary [ChainedMemoryKillBackoffPolicy.clear]
 * provides.
 */
data class ChainedMemoryKillBackoffState(
    /**
     * Consecutive memory-related kills, within chained sessions, since the last clean session
     * boundary or explicit retry. Reset to zero by a clean chained session
     * ([ChainedMemoryKillBackoffPolicy.resetStreak]), NOT by an ordinary read.
     */
    val killStreak: Int = 0,
    /** Whether the engine is excluded from starting until an explicit retry clears it. */
    val excluded: Boolean = false,
    /** Epoch millis of the most recent recorded kill, or `null` when none has been recorded. */
    val lastKillEpochMillis: Long? = null,
)

/**
 * The strike policy that turns observed memory kills into a durable exclusion: three consecutive
 * kills exclude the device, mirroring iOS `consecutiveUnprovenExitsBeforeTrip = 3` ("small enough
 * to stop a deterministic start crash loop, while tolerating two isolated exits").
 *
 * The Android evidence is weaker than iOS's: `ApplicationExitInfo` reports `REASON_LOW_MEMORY`
 * directly, but a pre-API-30 device has no exit-reason API at all, so its exclusion comes from
 * the conservative low-RAM heuristic in [ChainedAvailabilityPolicy]. That asymmetry is why the
 * count and the sticky flag both live in ONE value: a device that never had a reason API cannot
 * be allowed to "clear" an exclusion it never recorded.
 */
object ChainedMemoryKillBackoffPolicy {

    /** Three strikes, matching the iOS startup breaker's tolerance. */
    const val KILLS_BEFORE_EXCLUSION: Int = 3

    /** No unresolved memory-kill evidence. */
    val clean: ChainedMemoryKillBackoffState = ChainedMemoryKillBackoffState()

    /**
     * Records one memory kill.
     *
     * The streak is clamped at [KILLS_BEFORE_EXCLUSION] so a persisted or replayed count can
     * neither widen the tolerance (a negative) nor trap the next arithmetic (an oversized int) —
     * the same decoding-discipline argument iOS records for `consecutiveUnprovenExits`.
     */
    fun recordKill(
        previous: ChainedMemoryKillBackoffState,
        nowEpochMillis: Long,
    ): ChainedMemoryKillBackoffState {
        val streak = previous.killStreak.coerceIn(0, KILLS_BEFORE_EXCLUSION)
            .plus(1)
            .coerceAtMost(KILLS_BEFORE_EXCLUSION)
        return previous.copy(
            killStreak = streak,
            excluded = previous.excluded || streak >= KILLS_BEFORE_EXCLUSION,
            lastKillEpochMillis = nowEpochMillis,
        )
    }

    /**
     * A completed clean session breaks the streak but must NOT un-exclude a tripped device.
     *
     * The two are different facts: the streak is "consecutive kills since the last healthy
     * boundary", while the exclusion is a durable device-health verdict that only the explicit
     * [clear] retry may lift. Resetting the streak here is what makes three ISOLATED kills over
     * the install lifetime not read as three consecutive ones.
     */
    fun resetStreak(previous: ChainedMemoryKillBackoffState): ChainedMemoryKillBackoffState =
        previous.copy(killStreak = 0)

    /**
     * An explicit retry (a deliberate user/QA recovery boundary): full tolerance restored, the
     * sticky exclusion cleared. Mirrors iOS `explicitRetryRequested`, which restores the full
     * attempt window rather than leaving the count at threshold (a re-enable that kept the
     * counter would re-exclude on the very next kill, one strike instead of three).
     */
    fun clear(): ChainedMemoryKillBackoffState = clean
}

/** One retained process exit: when it happened and whether the platform attributed it to memory. */
data class ChainedProcessExit(
    val epochMillis: Long,
    val isLowMemory: Boolean,
)

/**
 * The process exits from one scan, newer than the caller's consumed baseline.
 *
 * The complete exit list is carried — every reason, not just low-memory ones — because the
 * reconciler must identify the FIRST exit at or after the witnessed session start. The witness
 * represents exactly one session, and that session ended at exactly one exit; a later low-memory
 * exit is a different, background death and must not strike the chained exclusion.
 */
data class ChainedMemoryKillScan(
    val exits: List<ChainedProcessExit>,
)

/**
 * Why the platform could not produce a [ChainedMemoryKillScan].
 *
 * The two cases are deliberately distinct and must not collapse into one `null`:
 *
 * - [Unsupported] is the ordinary pre-API-30 state — no `ApplicationExitInfo` exists — and the
 *   policy answers it with the conservative low-RAM fallback.
 * - [Failed] means the exit service itself threw on a platform that DOES expose exit reasons.
 *   That is a transient or permission failure, not evidence of a clean device; conflating it with
 *   [Unsupported] would let a scan failure mint an `Eligible` verdict from a truthfully unknown
 *   device. The store fails closed on it instead.
 */
sealed interface ChainedMemoryKillScanResult {
    /** The platform reported exits; [scan] carries them for the reconciler to classify. */
    data class Scanned(val scan: ChainedMemoryKillScan) : ChainedMemoryKillScanResult

    /** The platform exposes no exit-reason API (pre-API-30). */
    data object Unsupported : ChainedMemoryKillScanResult

    /** The exit-reason API exists but the scan threw; treat the device state as unreadable. */
    data object Failed : ChainedMemoryKillScanResult
}

/**
 * Turns the platform's retained exit history into a scan, or fails closed when it is incomplete.
 *
 * `ActivityManager.getHistoricalProcessExitReasons` returns at most the requested `maxNum` newest
 * exits. A bounded request that comes back FULL (`size >= requestedLimit`) means older exits were
 * omitted, and the omitted window can contain the exit that ended the witnessed session; classifying
 * the truncated sequence would then strike — or fail to strike — on an unrelated exit. Production
 * therefore requests the COMPLETE available history ([REQUEST_ALL_AVAILABLE]), and the guard stays
 * so a future bounded request cannot silently reintroduce the misclassification.
 *
 * Extracted here so the request policy and the truncation response are JVM-testable without the
 * Android framework.
 */
object ChainedProcessExitScanPolicy {

    /** The `maxNum` that asks `getHistoricalProcessExitReasons` for the complete available history. */
    const val REQUEST_ALL_AVAILABLE: Int = 0

    /**
     * Classifies one raw exit list.
     *
     * @param requestedLimit the `maxNum` the caller asked for; [REQUEST_ALL_AVAILABLE] means the
     *   complete history was requested and no truncation is possible.
     * @return [ChainedMemoryKillScanResult.Failed] when the requested window was filled, otherwise
     *   the exits newer than [afterEpochMillis] (order preserved; the reconciler selects the first).
     */
    fun classify(
        exits: List<ChainedProcessExit>,
        afterEpochMillis: Long?,
        requestedLimit: Int,
    ): ChainedMemoryKillScanResult {
        require(requestedLimit >= 0) { "exit-history request limit must not be negative" }
        if (requestedLimit > 0 && exits.size >= requestedLimit) {
            return ChainedMemoryKillScanResult.Failed
        }
        return ChainedMemoryKillScanResult.Scanned(
            ChainedMemoryKillScan(
                exits = exits.filter {
                    afterEpochMillis == null || it.epochMillis > afterEpochMillis
                },
            ),
        )
    }
}

/**
 * The durable record the store reads and writes.
 *
 * [lastConsumedExitEpochMillis] is the high-water mark of exit history already consumed, so kills
 * are counted exactly once; without it every read would re-scan the OS's retained history and
 * keep striking the same exit until the device was excluded for a single historical kill, and
 * without advancing it on the transitions that end a session an explicit retry would immediately
 * re-exclude from that same history. It is rebased to the current time when the wall clock
 * regresses (see [rebasedBaseline]), so a future value from a wrong clock cannot strand the mark
 * and hide subsequent real exits.
 *
 * [activeChainedSessionStartEpochMillis] is the durable "chaining is running" witness. The kill
 * that matters is the one that happens while the engine is resident, and a killed process cannot
 * write a post-mortem; so the start is recorded while it runs and a later read correlates the
 * `ApplicationExitInfo` timestamps against it. Absent (null) means no chained session is known
 * to be in progress, which is the ordinary idle state and the state after a clean stop.
 *
 * [activeChainedSessionOwnerToken] records WHICH runtime wrote the witness, separately from the
 * correlation timestamp. It is the value the owning runtime captured when it called
 * [ChainedDeviceEligibilityStore.markChainedSessionStarted], and it is NEVER rebased: the
 * correlation timestamp is clamped when the wall clock regresses (see [rebasedWitness]), and a
 * clean stop that matched on the timestamp would then fail to recognise its own witness and
 * orphan it, attributing a later background kill to an already-ended session. Ownership therefore
 * survives rebasing.
 *
 * The witness survives a live read: readiness refresh calls [ChainedDeviceEligibilityStore.read]
 * while the session is still running, and a scan that found no exit yet is NOT evidence the
 * session ended. Only an exit at or after the start (the reconciler), the clean-stop transition,
 * or explicit evidence retires it.
 */
data class ChainedDeviceEligibilityRecord(
    val backoff: ChainedMemoryKillBackoffState = ChainedMemoryKillBackoffPolicy.clean,
    val lastConsumedExitEpochMillis: Long? = null,
    val activeChainedSessionStartEpochMillis: Long? = null,
    /**
     * The identity the owning runtime wrote at start, matched by an owning clean stop. Distinct
     * from [activeChainedSessionStartEpochMillis] because that correlation timestamp is rebased
     * on a clock regression while this token is not.
     */
    val activeChainedSessionOwnerToken: Long? = null,
)

/**
 * Folds exit scans and session lifecycles into the durable record, purely.
 *
 * Kept as value functions so the "count once, only during a chained session, reset on a clean
 * stop" rules are exercised on the JVM without a device or a SharedPreferences.
 */
object ChainedDeviceEligibilityReconciler {

    /**
     * Consumes one exit scan.
     *
     * The witnessed session ended at the FIRST exit at or after its start, and ONLY that exit's
     * reason can strike: at most one kill is recorded per witnessed session. A session is one
     * process, so once it has exited, every later exit is a background process death — counting
     * them would let three retained background kills trip the sticky exclusion for one chained
     * kill. Later exits still advance the baseline.
     *
     * A scan with no exit at or after the start may simply be a live read taken while the session
     * is still running, so it must PRESERVE the witness; clearing it there would make the coming
     * kill look like a background kill and the streak would never advance. With no in-progress
     * session the scan still advances the baseline, so background kills and exits predating the
     * feature are retired without ever striking the chained exclusion.
     *
     * The witness is rebased with the baseline when the wall clock has regressed: a start stamped
     * by a wrong future clock would otherwise reject the real exit (recorded after the clock was
     * corrected) as predating the session, permanently losing the strike. See [rebasedWitness].
     */
    fun reconcile(
        record: ChainedDeviceEligibilityRecord,
        scan: ChainedMemoryKillScan,
        nowEpochMillis: Long,
    ): ChainedDeviceEligibilityRecord {
        val activeStart = rebasedWitness(
            start = record.activeChainedSessionStartEpochMillis,
            nowEpochMillis = nowEpochMillis,
            scan = scan,
        )
        val endingExit = if (activeStart == null) {
            null
        } else {
            scan.exits.filter { it.epochMillis >= activeStart }.minByOrNull { it.epochMillis }
        }
        val kills = if (endingExit?.isLowMemory == true) 1 else 0
        var backoff = record.backoff
        repeat(kills) { backoff = ChainedMemoryKillBackoffPolicy.recordKill(backoff, nowEpochMillis) }
        return record.copy(
            backoff = backoff,
            // Order matters: rebase the STORED mark first (it may be a wrong-clock future value),
            // THEN merge the observed exits. Merging first and clamping after would clamp away the
            // real retained exit's timestamp too, dropping it below the probe window so the next
            // read re-counts it.
            lastConsumedExitEpochMillis = laterOf(
                rebasedBaseline(record.lastConsumedExitEpochMillis, nowEpochMillis),
                scan.exits.maxOfOrNull { it.epochMillis },
            ),
            activeChainedSessionStartEpochMillis = if (endingExit != null) null else activeStart,
            // Ownership is NOT rebased with the correlation timestamp: a clean stop matches it to
            // recognise its own witness even after [rebasedWitness] clamped the start (finding
            // 4021942644's fix matched the raw epoch, which the rebase can move under it).
            activeChainedSessionOwnerToken =
                if (endingExit != null) null else record.activeChainedSessionOwnerToken,
        )
    }

    /**
     * Retires a session and records one kill from evidence the caller already holds (an explicit
     * notification rather than an `ApplicationExitInfo` re-scan).
     *
     * The baseline advances to [nowEpochMillis] and the witness clears, so the same kill cannot be
     * retrieved from the OS history by a later [ChainedDeviceEligibilityStore.read] and counted a
     * second time — the double-count that could exclude a device after fewer than three actual
     * kills.
     */
    fun explicitMemoryKillRecorded(
        record: ChainedDeviceEligibilityRecord,
        nowEpochMillis: Long,
    ): ChainedDeviceEligibilityRecord = record.copy(
        backoff = ChainedMemoryKillBackoffPolicy.recordKill(record.backoff, nowEpochMillis),
        // The kill happened no later than now: retire everything through now, dropping a bogus
        // future mark left by a wrong clock so it cannot mask later exits.
        lastConsumedExitEpochMillis = nowEpochMillis,
        activeChainedSessionStartEpochMillis = null,
        activeChainedSessionOwnerToken = null,
    )

    /**
     * Records that a chained session is starting at [nowEpochMillis].
     *
     * Any pending evidence from a prior session is consumed first (so a kill from the previous
     * session is not lost), and the baseline is pulled forward to the new start so low-memory
     * exits from before this first attempt cannot count against it. The session start is then
     * durable, which is what lets a later read attribute a kill to chaining.
     */
    fun startChainedSession(
        record: ChainedDeviceEligibilityRecord,
        scan: ChainedMemoryKillScan?,
        nowEpochMillis: Long,
    ): ChainedDeviceEligibilityRecord {
        // Rebase the stored mark even when there is no scan: an unsupported (pre-API-30) probe
        // still must not carry a wrong-clock future mark into the new session.
        val reconciled = if (scan != null) {
            reconcile(record, scan, nowEpochMillis)
        } else {
            record.copy(
                lastConsumedExitEpochMillis =
                    rebasedBaseline(record.lastConsumedExitEpochMillis, nowEpochMillis),
            )
        }
        return reconciled.copy(
            // Retire everything through the new start, but never below an exit the reconcile just
            // consumed: max(reconciledMark, now) rebases a future mark (reconciledMark <= now after
            // the rebase) while preserving a real exit stamped above now, so the next read does not
            // re-count it.
            lastConsumedExitEpochMillis =
                laterOf(reconciled.lastConsumedExitEpochMillis, nowEpochMillis),
            activeChainedSessionStartEpochMillis = nowEpochMillis,
            // The ownership token is the value THIS caller writes, recorded beside the
            // correlation timestamp and never rebased, so the matching clean stop recognises it.
            activeChainedSessionOwnerToken = nowEpochMillis,
        )
    }

    /**
     * Records that a chained session stopped through its own clean funnel.
     *
     * The streak resets (three isolated kills are not three consecutive ones) but a tripped
     * exclusion stays sticky — only the explicit [ChainedMemoryKillBackoffPolicy.clear] retry may
     * lift it. The baseline advances past the whole session so its exit history is never
     * re-counted, and the in-progress marker clears.
     */
    fun cleanChainedSessionStopped(
        record: ChainedDeviceEligibilityRecord,
        nowEpochMillis: Long,
    ): ChainedDeviceEligibilityRecord = record.copy(
        backoff = ChainedMemoryKillBackoffPolicy.resetStreak(record.backoff),
        // The session ended by now; the whole retained history through now is spent. Assigning
        // now rebases a future mark left by a wrong clock.
        lastConsumedExitEpochMillis = nowEpochMillis,
        activeChainedSessionStartEpochMillis = null,
        activeChainedSessionOwnerToken = null,
    )

    /**
     * The owning form: retires [owningStartEpochMillis]'s session, and ONLY that exact witness.
     *
     * During a make-before-break reload the candidate records its witness while the predecessor is
     * still alive; the predecessor's clean stop must not clear it, or the live chained runtime runs
     * without attribution and repeated low-memory kills evade the exclusion breaker. This is the
     * Android mapping of the iOS `settleTermination(owningLifecycleID:)` fence, which clears only
     * while `current.activeSessionLifecycleID == owningLifecycleID`.
     *
     * The comparison is EXACT IDENTITY, never wall-clock ORDERING. An earlier version preserved
     * any witness strictly greater than the owner's start, which is unsound when the wall clock
     * moves backward between the predecessor's start and the candidate's: the candidate then
     * writes a SMALLER timestamp, ordering drops it, and the live runtime's coming low-memory kill
     * goes unattributed.
     *
     * The match is against [ChainedDeviceEligibilityRecord.activeChainedSessionOwnerToken] ALONE,
     * never the correlation timestamp: [reconcile] rebases
     * [ChainedDeviceEligibilityRecord.activeChainedSessionStartEpochMillis] when the wall clock
     * regresses, so a fallback to it cannot be correct — a stop invoked with the epoch the owner
     * actually wrote sees the rebased value and computes the wrong verdict, orphaning the owner's
     * own witness or clearing a successor's. New records always carry a token
     * ([startChainedSession]); a legacy record without one is owned by no current runtime and is
     * therefore PRESERVED, retiring only through exit evidence or an explicit kill. The owner
     * token survives the rebase.
     *
     * The streak reset and the baseline advance are still applied: a clean stop breaks the
     * consecutive-kill chain, and pulling the baseline to now spends the retired session's exit
     * history whether or not a replacement is live.
     */
    fun cleanChainedSessionStopped(
        record: ChainedDeviceEligibilityRecord,
        nowEpochMillis: Long,
        owningStartEpochMillis: Long,
    ): ChainedDeviceEligibilityRecord {
        val preserve = record.activeChainedSessionOwnerToken != owningStartEpochMillis
        return record.copy(
            backoff = ChainedMemoryKillBackoffPolicy.resetStreak(record.backoff),
            lastConsumedExitEpochMillis = nowEpochMillis,
            activeChainedSessionStartEpochMillis =
                record.activeChainedSessionStartEpochMillis.takeIf { preserve },
            activeChainedSessionOwnerToken =
                record.activeChainedSessionOwnerToken.takeIf { preserve },
        )
    }
}

/**
 * The STORED exit high-water mark, rebased when the wall clock has regressed.
 *
 * Apply this to the stored mark BEFORE merging newly observed exit timestamps. If [nowEpochMillis]
 * is before the stored mark, that mark is a future value left by a wrong clock; retaining it would
 * make the probe discard every real exit not strictly newer — ignoring real chained kills until
 * wall time caught up, possibly indefinitely. The stored mark is therefore clamped to
 * [nowEpochMillis]; observed exit timestamps are real (corrected-clock) values and must be merged
 * after, so this must not be applied to their max.
 */
private fun rebasedBaseline(previous: Long?, nowEpochMillis: Long): Long? = when {
    previous == null -> null
    nowEpochMillis < previous -> nowEpochMillis
    else -> previous
}

/**
 * The active-session witness, rebased when the wall clock has regressed.
 *
 * A witness stamped by a wrong future clock is ahead of every real exit: after the clock is
 * corrected, a real exit's timestamp is below the stored start and the "first exit at or after the
 * start" selection rejects it, silently losing the strike. Clamping a future witness to
 * [nowEpochMillis] alone is not enough — the real exit predates the read — so the clamp is to the
 * EARLIEST retained exit (the session must have begun before any exit recorded under the corrected
 * clock), or to [nowEpochMillis] when the scan is empty. That admits the real post-correction kill
 * while keeping the "only the first exit strikes" rule.
 */
private fun rebasedWitness(
    start: Long?,
    nowEpochMillis: Long,
    scan: ChainedMemoryKillScan,
): Long? {
    if (start == null || nowEpochMillis >= start) return start
    return (scan.exits.minOfOrNull { it.epochMillis } ?: nowEpochMillis).coerceAtMost(nowEpochMillis)
}

private fun laterOf(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}
