package com.lavasec.app.platform

/**
 * Why the device-eligibility record could not be read or persisted.
 *
 * A stable log identifier, never user copy. Both cases fail closed: [ChainedAvailabilityPolicy]
 * folds either into `DEVICE_STATE_UNREADABLE` rather than guessing an eligibility verdict, the
 * same "unreadable is not false" discipline the iOS `ChainedDeviceEligibilityStore.ReadOutcome`
 * records for its override and breaker items.
 */
enum class ChainedDeviceEligibilityUnavailableReason {
    /** The device memory reading was unavailable. */
    MEMORY_UNREADABLE,

    /**
     * The durable exclusion/backoff record could not be read or written, or the API 30+ exit
     * scan threw. A platform that exposes exit reasons but cannot be queried is a truthfully
     * unknown device state, never a clean one.
     */
    ELIGIBILITY_STATE_UNREADABLE,
}

/**
 * One coherent reading of the device's eligibility facts.
 *
 * A partial reading is not offered: [ChainedAvailabilityPolicy] consumes the memory reading, the
 * durable backoff, and the API level in one decision, and a snapshot with one term guessed is the
 * lie the outcome type exists to prevent.
 */
sealed interface ChainedDeviceEligibilityRead {

    /** The memory reading, the durable backoff, and the API level that produced them. */
    data class Available(
        val memory: ChainedDeviceMemoryReading,
        val backoff: ChainedMemoryKillBackoffState,
        /**
         * `Build.VERSION.SDK_INT` at read time. The policy uses it to decide whether the
         * conservative pre-API-30 low-RAM fallback applies; the value is a plain Int so the
         * policy stays JVM-testable with no Android dependency.
         */
        val apiLevel: Int,
    ) : ChainedDeviceEligibilityRead

    /** The store could not produce a trustworthy reading; [reason] names the term that failed. */
    data class Unavailable(
        val reason: ChainedDeviceEligibilityUnavailableReason,
    ) : ChainedDeviceEligibilityRead
}

/**
 * The durable, credential-protected source of the device-eligibility facts the chained gate
 * needs.
 *
 * It owns three things the readiness gate must not invent:
 *
 * - the **memory reading** — the current device's total memory (and heap class / low-RAM flag) —
 *   so the ~50 MB engine's floor is checked against this device, never a cached or synced value;
 * - the **memory-kill exclusion/backoff** — durable across process death, because the event it
 *   records is the process being killed;
 * - the **in-progress chained session witness** — recorded while the engine runs and consumed on
 *   the next read, so only a kill that happened while chaining counts.
 *
 * ## Why the store owns the session lifecycle
 *
 * A low-memory kill anywhere in the package is not evidence about chaining. The scan is
 * package-wide, so the store must be told when chaining was actually running: the composition
 * calls [markChainedSessionStarted] when the chained session latches and
 * [markChainedSessionStoppedCleanly] when it retires through its own funnel. A kill leaves the
 * marker set; the next [read] correlates the exit timestamps against it, counts at most the
 * in-session kills, and clears it.
 *
 * ## Why the read reconciles
 *
 * [read] is not a passive getter: on API 30+ it scans `ApplicationExitInfo` for
 * `REASON_LOW_MEMORY` exits newer than its stored baseline, attributes the in-session ones,
 * and persists the advanced baseline before returning. The alternative — a separate "consume
 * evidence" call the caller must remember — is how the evidence gets missed. Reconciling on read
 * makes the recovery automatic on the next start, which is the only time a killed process can act.
 *
 * On API < 30 the platform exposes no exit reason, so the scan is absent and the policy applies
 * its own conservative low-RAM fallback; the store never fabricates a zero. On API 30+ a scan
 * that throws is a failed read (fail closed), distinct from the pre-API-30 absence.
 *
 * Implementations are platform adapters. The production Android adapter is
 * `com.lavasec.app.chained.AndroidChainedDeviceEligibilityStore`.
 */
interface ChainedDeviceEligibilityStore {

    /** Reads the memory facts and reconciles any unconsumed memory-kill evidence. */
    fun read(): ChainedDeviceEligibilityRead

    /**
     * Records that a chained session is now running, so a later kill can be attributed to it.
     * Consumes any pending evidence from a previous session first.
     */
    fun markChainedSessionStarted(nowEpochMillis: Long): ChainedDeviceEligibilityRead

    /**
     * Records that a chained session retired through its own clean funnel. Resets the streak
     * (three isolated kills are not three consecutive ones) without clearing a tripped exclusion,
     * and advances the exit baseline past the session.
     *
     * This form has no owning identity, so it clears the witness unconditionally. The runtime uses
     * the owning overload below; this remains for callers/tests that own the only session.
     */
    fun markChainedSessionStoppedCleanly(nowEpochMillis: Long): ChainedDeviceEligibilityRead

    /**
     * Records that a chained session retired through its own clean funnel, clearing the in-progress
     * witness only when it is still THIS session's.
     *
     * [owningStartEpochMillis] is the start epoch the caller passed to
     * [markChainedSessionStarted]. A make-before-break reload records a NEWER witness on the
     * candidate while the predecessor is still alive, so an unconditional clear would retire the
     * live runtime's witness and leave it unattributed; this form preserves a witness owned by a
     * different runtime. The ownership is matched against a token recorded separately from the
     * rebasable correlation timestamp, so it cannot be fooled by a backward clock move or by the
     * witness rebasing a corrected-past read applies; a legacy record with no token is preserved
     * rather than matched against the rebased start. Mirrors the iOS
     * `settleTermination(owningLifecycleID:)` fence.
     *
     * The default forwards to the unqualified form so a store that has only one lifecycle needs no
     * second implementation.
     */
    fun markChainedSessionStoppedCleanly(
        nowEpochMillis: Long,
        owningStartEpochMillis: Long,
    ): ChainedDeviceEligibilityRead = markChainedSessionStoppedCleanly(nowEpochMillis)

    /**
     * Records one memory kill durably (the explicit path, for callers that already hold the
     * evidence), returning the post-record reading. It also retires the in-progress session and
     * advances the exit baseline past [nowEpochMillis], so a later [read] cannot retrieve the same
     * kill from `ApplicationExitInfo` and count it twice.
     */
    fun recordMemoryKill(nowEpochMillis: Long): ChainedDeviceEligibilityRead

    /**
     * Clears the exclusion and restores full tolerance at an explicit retry boundary, while
     * advancing the exit baseline to [nowEpochMillis] so the OS's retained history cannot
     * immediately re-exclude the device. Reserved for a deliberate user/QA retry; a clean session
     * resets the streak without lifting the exclusion.
     */
    fun clearMemoryKillBackoff(nowEpochMillis: Long): ChainedDeviceEligibilityRead
}
