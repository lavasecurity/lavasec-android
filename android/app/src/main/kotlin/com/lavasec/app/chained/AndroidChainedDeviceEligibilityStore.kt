package com.lavasec.app.chained

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.lavasec.app.platform.ChainedDeviceEligibilityRead
import com.lavasec.app.platform.ChainedDeviceEligibilityRecord
import com.lavasec.app.platform.ChainedDeviceEligibilityReconciler
import com.lavasec.app.platform.ChainedDeviceEligibilityStore
import com.lavasec.app.platform.ChainedDeviceEligibilityUnavailableReason
import com.lavasec.app.platform.ChainedDeviceMemoryReading
import com.lavasec.app.platform.ChainedMemoryKillBackoffPolicy
import com.lavasec.app.platform.ChainedMemoryKillBackoffState
import com.lavasec.app.platform.ChainedMemoryKillScanResult

/**
 * The production Android [ChainedDeviceEligibilityStore].
 *
 * ## Storage
 *
 * A credential-protected `SharedPreferences` file (`MODE_PRIVATE` in the app's default,
 * credential-protected context — the same convention as `SecurityPreferenceStore`), because the
 * backoff is a property of this installation and must not ride a backup to another device: a
 * device that was memory-killed here has no evidence about a replacement device's hardware.
 * The record is tiny (three integers), so SharedPreferences is sufficient and avoids dragging the
 * DataStore graph into the VPN process.
 *
 * ## Read reconciles, and a failed scan fails closed
 *
 * [read] performs the durability work: it folds any unconsumed `ApplicationExitInfo` memory-kill
 * evidence into the stored backoff and persists the advanced baseline. Only kills that happened
 * while this store had a chained session in progress (see [markChainedSessionStarted]) count; a
 * package-wide background kill does not. On API < 30 the exit probe reports
 * [ChainedMemoryKillScanResult.Unsupported], so the record is returned unchanged and the policy's
 * conservative low-RAM fallback decides. On API 30+ a scan that throws reports
 * [ChainedMemoryKillScanResult.Failed], which fails the read as `ELIGIBILITY_STATE_UNREADABLE` —
 * never as a clean device.
 *
 * ## Atomicity
 *
 * A single `commit()` writes the backoff, the baseline, and the session witness together.
 * Splitting them would let a kill land between two writes and either double-count one exit, lose
 * a strike, or lose the session correlation the next read needs. `commit()` (not `apply()`) is
 * used so the record is durable before a transition returns; the callers are gated chained
 * lifecycle boundaries that can afford the synchronous write.
 *
 * A synchronous write is not enough on its own: a readiness `read()` and a chained lifecycle
 * mutation (`markChainedSessionStoppedCleanly`, `markChainedSessionStarted`, `recordMemoryKill`)
 * can run on different threads, each loading the same record and committing independently, so a
 * slow read that loaded before a clean stop could persist its stale streak/witness over it. Every
 * public entry point therefore holds the store's transaction lock across the COMPLETE
 * load → scan → transform → write transaction, so the operations serialize at the store boundary.
 *
 * The lock is process-wide and keyed to the backing `SharedPreferences` file, not per instance:
 * the readiness owner and the VPN-lifecycle owner can each hold their own store object over the
 * same file, and per-instance locks would not coordinate, letting one overwrite the other's clean
 * stop. [ChainedEligibilityFileLocks] mirrors the `DirectBootRootLocks`/credential-repository
 * keyed-lock pattern.
 */
class AndroidChainedDeviceEligibilityStore(
    context: Context,
    private val memoryProbe: ChainedDeviceMemoryProbe,
    private val exitProbe: ChainedProcessExitProbe,
    private val apiLevel: Int,
    private val nowMillis: () -> Long,
) : ChainedDeviceEligibilityStore {

    /** Production wiring: the real platform probes, the current SDK, the wall clock. */
    constructor(context: Context) : this(
        context = context,
        memoryProbe = AndroidChainedDeviceMemoryProbe.from(context),
        exitProbe = AndroidChainedProcessExitProbe.from(context),
        apiLevel = Build.VERSION.SDK_INT,
        nowMillis = System::currentTimeMillis,
    )

    private val applicationContext: Context = context.applicationContext
    // Serializes each COMPLETE record transaction (load → scan → transform → write). Shared across
    // every instance that targets this preferences file, so separate readiness/VPN-lifecycle store
    // objects cannot interleave. See the class doc's Atomicity note.
    private val transactionLock: Any = ChainedEligibilityFileLocks.forPath(
        File(applicationContext.dataDir, "shared_prefs/$PREFERENCES_NAME.xml").path,
    )
    // A credential-protected store opened before first unlock throws, and a chained gate must fail
    // closed (ELIGIBILITY_STATE_UNREADABLE) rather than crash the caller that is only asking
    // whether chaining is available. Only a SUCCESSFUL open is cached: caching the pre-unlock
    // failure would leave a long-lived store unreadable for its whole lifetime even after
    // USER_UNLOCKED, so every transaction retries until the open succeeds.
    @Volatile
    private var openedPreferences: SharedPreferences? = null

    override fun read(): ChainedDeviceEligibilityRead = locked { readRecord() }

    override fun markChainedSessionStarted(nowEpochMillis: Long): ChainedDeviceEligibilityRead =
        locked { startSession(nowEpochMillis) }

    override fun markChainedSessionStoppedCleanly(nowEpochMillis: Long): ChainedDeviceEligibilityRead =
        locked {
            mutate { record ->
                ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(record, nowEpochMillis)
            }
        }

    override fun markChainedSessionStoppedCleanly(
        nowEpochMillis: Long,
        owningStartEpochMillis: Long,
    ): ChainedDeviceEligibilityRead =
        locked {
            mutate { record ->
                // The owning form: a make-before-break candidate may have recorded a witness while
                // this (predecessor) session was still alive, so clear only the EXACT ownership
                // token this session wrote and preserve any other (finding 4021176701; matched by
                // token, never wall-clock order, per finding 4021942644). The token is the start
                // epoch the caller passed to `markChainedSessionStarted`, recorded separately from
                // the correlation timestamp so it survives a wrong-clock witness rebase.
                ChainedDeviceEligibilityReconciler.cleanChainedSessionStopped(
                    record,
                    nowEpochMillis,
                    owningStartEpochMillis,
                )
            }
        }

    override fun recordMemoryKill(nowEpochMillis: Long): ChainedDeviceEligibilityRead =
        locked {
            mutate { record ->
                // Retiring the witness and advancing the baseline here is what keeps a later read
                // from fetching the same kill out of ApplicationExitInfo and counting it twice.
                ChainedDeviceEligibilityReconciler.explicitMemoryKillRecorded(record, nowEpochMillis)
            }
        }

    override fun clearMemoryKillBackoff(nowEpochMillis: Long): ChainedDeviceEligibilityRead =
        locked {
            mutate { record ->
                record.copy(
                    backoff = ChainedMemoryKillBackoffPolicy.clear(),
                    // Advance the baseline to now so the OS's retained history cannot immediately
                    // re-exclude the device the explicit retry just cleared. Assigning now (not a
                    // max) also rebases a future mark left by a wrong clock.
                    lastConsumedExitEpochMillis = nowEpochMillis,
                )
            }
        }

    private fun readRecord(): ChainedDeviceEligibilityRead {
        val memory = readMemoryOrNull() ?: return unavailableMemory()
        val loaded = load() ?: return unavailableState()
        val now = nowMillis()
        return when (val result = exitProbe.scanMemoryKills(scanBaseline(loaded, now))) {
            ChainedMemoryKillScanResult.Failed -> unavailableState()
            ChainedMemoryKillScanResult.Unsupported -> commit(memory, loaded, loaded)
            is ChainedMemoryKillScanResult.Scanned -> commit(
                memory,
                loaded,
                ChainedDeviceEligibilityReconciler.reconcile(loaded, result.scan, now),
            )
        }
    }

    private fun startSession(nowEpochMillis: Long): ChainedDeviceEligibilityRead {
        val memory = readMemoryOrNull() ?: return unavailableMemory()
        val loaded = load() ?: return unavailableState()
        // Consume a previous session's pending evidence before claiming a new session, and fail
        // closed if an API 30+ scan cannot run — starting chaining while unable to read the exit
        // history would silently defeat the breaker.
        val scan = when (
            val result = exitProbe.scanMemoryKills(scanBaseline(loaded, nowEpochMillis))
        ) {
            ChainedMemoryKillScanResult.Failed -> return unavailableState()
            ChainedMemoryKillScanResult.Unsupported -> null
            is ChainedMemoryKillScanResult.Scanned -> result.scan
        }
        return commit(
            memory,
            loaded,
            ChainedDeviceEligibilityReconciler.startChainedSession(loaded, scan, nowEpochMillis),
        )
    }

    /**
     * The baseline handed to the exit probe.
     *
     * A stored mark in the FUTURE is a wrong-clock artifact. Filtering by it would discard every
     * real exit before [ChainedDeviceEligibilityReconciler.reconcile] can rebase, consuming those
     * exits without a strike. When the mark is ahead of [nowEpochMillis] the probe is asked for the
     * complete retained history (`null`) so the corrected-clock exits are still considered.
     */
    private fun scanBaseline(
        record: ChainedDeviceEligibilityRecord,
        nowEpochMillis: Long,
    ): Long? = record.lastConsumedExitEpochMillis?.takeIf { it <= nowEpochMillis }

    private inline fun <T> locked(block: () -> T): T = synchronized(transactionLock, block)

    /** Loads, transforms, persists-if-changed, and wraps the result with the memory reading. */
    private fun mutate(
        transform: (ChainedDeviceEligibilityRecord) -> ChainedDeviceEligibilityRecord,
    ): ChainedDeviceEligibilityRead {
        val memory = readMemoryOrNull() ?: return unavailableMemory()
        val loaded = load() ?: return unavailableState()
        return commit(memory, loaded, transform(loaded))
    }

    private fun commit(
        memory: ChainedDeviceMemoryReading,
        previous: ChainedDeviceEligibilityRecord,
        next: ChainedDeviceEligibilityRecord,
    ): ChainedDeviceEligibilityRead {
        if (next != previous && !persist(next)) return unavailableState()
        return ChainedDeviceEligibilityRead.Available(memory, next.backoff, apiLevel)
    }

    private fun readMemoryOrNull(): ChainedDeviceMemoryReading? = memoryProbe.read()

    private fun unavailableMemory(): ChainedDeviceEligibilityRead.Unavailable =
        ChainedDeviceEligibilityRead.Unavailable(
            ChainedDeviceEligibilityUnavailableReason.MEMORY_UNREADABLE,
        )

    private fun unavailableState(): ChainedDeviceEligibilityRead.Unavailable =
        ChainedDeviceEligibilityRead.Unavailable(
            ChainedDeviceEligibilityUnavailableReason.ELIGIBILITY_STATE_UNREADABLE,
        )

    /**
     * The credential-protected preferences, opening on first success and retrying after failure.
     *
     * Called only from inside the transaction lock. Caching a pre-unlock failure would strand the
     * store, so a failed open is not cached and a later transaction tries again.
     */
    private fun preferencesOrNull(): SharedPreferences? {
        openedPreferences?.let { return it }
        return runCatching {
            applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        }.getOrNull()?.also { openedPreferences = it }
    }

    /** A missing record is the honest clean/never-set state; an unreadable store is `null`. */
    private fun load(): ChainedDeviceEligibilityRecord? = runCatching {
        val store = preferencesOrNull() ?: return null
        if (!store.contains(KEY_KILL_STREAK) && !store.contains(KEY_EXCLUDED)) {
            return ChainedDeviceEligibilityRecord()
        }
        ChainedDeviceEligibilityRecord(
            backoff = ChainedMemoryKillBackoffState(
                killStreak = store.getInt(KEY_KILL_STREAK, 0)
                    .coerceIn(0, ChainedMemoryKillBackoffPolicy.KILLS_BEFORE_EXCLUSION),
                excluded = store.getBoolean(KEY_EXCLUDED, false),
                lastKillEpochMillis = store.getLong(KEY_LAST_KILL, ABSENT)
                    .takeIf { it != ABSENT },
            ),
            lastConsumedExitEpochMillis = store.getLong(KEY_CONSUMED_EXIT, ABSENT)
                .takeIf { it != ABSENT },
            activeChainedSessionStartEpochMillis = store.getLong(KEY_ACTIVE_SESSION, ABSENT)
                .takeIf { it != ABSENT },
            activeChainedSessionOwnerToken = store.getLong(KEY_ACTIVE_SESSION_OWNER, ABSENT)
                .takeIf { it != ABSENT },
        )
    }.getOrNull()

    private fun persist(record: ChainedDeviceEligibilityRecord): Boolean = runCatching {
        val store = preferencesOrNull() ?: return false
        val editor = store.edit()
            .putInt(KEY_KILL_STREAK, record.backoff.killStreak)
            .putBoolean(KEY_EXCLUDED, record.backoff.excluded)
        record.backoff.lastKillEpochMillis
            ?.let { editor.putLong(KEY_LAST_KILL, it) }
            ?: editor.remove(KEY_LAST_KILL)
        record.lastConsumedExitEpochMillis
            ?.let { editor.putLong(KEY_CONSUMED_EXIT, it) }
            ?: editor.remove(KEY_CONSUMED_EXIT)
        record.activeChainedSessionStartEpochMillis
            ?.let { editor.putLong(KEY_ACTIVE_SESSION, it) }
            ?: editor.remove(KEY_ACTIVE_SESSION)
        record.activeChainedSessionOwnerToken
            ?.let { editor.putLong(KEY_ACTIVE_SESSION_OWNER, it) }
            ?: editor.remove(KEY_ACTIVE_SESSION_OWNER)
        editor.commit()
    }.getOrDefault(false)

    private companion object {
        const val PREFERENCES_NAME = "lavasec_chained_eligibility"
        const val KEY_KILL_STREAK = "memory_kill_streak"
        const val KEY_EXCLUDED = "memory_kill_excluded"
        const val KEY_LAST_KILL = "last_memory_kill_epoch_millis"
        const val KEY_CONSUMED_EXIT = "last_consumed_exit_epoch_millis"
        const val KEY_ACTIVE_SESSION = "active_chained_session_start_epoch_millis"
        const val KEY_ACTIVE_SESSION_OWNER = "active_chained_session_owner_token"
        const val ABSENT = Long.MIN_VALUE
    }
}

/**
 * Process-wide transaction locks keyed by the backing preferences file path.
 *
 * Every store instance that addresses the same file shares one lock, so a readiness `read()` and a
 * VPN-lifecycle mutation commit serialized even when they are separate objects. Mirrors
 * `DirectBootRootLocks` / the credential repository's `rotationLocks`.
 */
private object ChainedEligibilityFileLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun forPath(path: String): Any = locks.computeIfAbsent(path) { Any() }
}
