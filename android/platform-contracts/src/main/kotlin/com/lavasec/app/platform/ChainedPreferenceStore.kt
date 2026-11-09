package com.lavasec.app.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The durable, credential-protected record of the user's chained-upstream preference.
 *
 * A preference is NOT an outcome: [ChainedReadiness] outranks it, and a stored `true` never
 * claims the tunnel is latched — the runtime reports that separately. It exists so the choice
 * survives process death and graph rotation, and so the VPN runtime can ask "did the user ask
 * for chaining?" without reading the whole product configuration.
 *
 * Kept separate from [ChainedUpstreamConfigurationRepository] on purpose: "I want chaining" and
 * "here is the upstream to use" are independent facts, and the disabled state must not lose the
 * configured upstream (or vice versa).
 */
interface ChainedPreferenceStore {

    /** The current preference; the backing store's initial/default state is `false`. */
    val chainedEnabled: Flow<Boolean>

    /** Independent consent to use saved DNS settings after tunnel DNS fails in split mode.
     * It never toggles a retained DNS tier and defaults to false for legacy implementations. */
    val chainedDnsFallbackEnabled: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(false)
    suspend fun loadDnsFallbackEnabled(): Boolean = false
    suspend fun setChainedDnsFallbackEnabled(enabled: Boolean): Boolean = false
    suspend fun setChainedDnsFallbackEnabledWhileCurrent(enabled: Boolean, isCurrent: () -> Boolean): Boolean = false

    /** One-shot read of [chainedEnabled]. */
    suspend fun loadEnabled(): Boolean

    /**
     * Commits the preference. Returns `true` only when the write is durable; a failed commit
     * leaves the previous value authoritative (the VPN lifecycle must not act on an in-memory
     * value the next process would not see).
     */
    suspend fun setChainedEnabled(enabled: Boolean): Boolean

    /** Guarded native mutations refuse unless the backend checks authority at publication. */
    suspend fun setChainedEnabledWhileCurrent(enabled: Boolean, isCurrent: () -> Boolean): Boolean = false
}

/**
 * Serializes chained-preference mutations (and the reload they trigger) so the last user action
 * wins.
 *
 * The React Native surface submits toggles from a multithreaded `Dispatchers.IO` scope, so two
 * rapid requests (enable then disable) would otherwise commit concurrently: their DataStore/commit
 * order can reverse and the running tunnel can end up in the mode the user turned off. Each
 * submission waits for the previous one to finish before it commits, so the durable preference —
 * and the reload derived from it — always reflect the most recently delivered action. The
 * submission order is fixed by the synchronized entry, and each block captures the value it was
 * called with.
 *
 * The scope is owned by the caller; [cancel] releases the pending chain. A cancelled chain leaves
 * the last committed value authoritative, which is correct: a cancelled mutation never ran.
 */
class ChainedPreferenceMutationSerializer(private val scope: CoroutineScope) {
    private var tail: Job? = null

    /** Queues [mutation] after every previously submitted mutation. Never runs two concurrently. */
    @Synchronized
    fun submit(mutation: suspend () -> Unit) {
        val previous = tail
        tail = scope.launch {
            previous?.join()
            mutation()
        }
    }

    @Synchronized
    fun cancel() {
        tail?.cancel()
        tail = null
    }
}

/**
 * Deferred retry latch for a committed chained-preference reload whose dispatch failed.
 *
 * A toggle that commits the durable preference while protection is live must also make the running
 * runtime converge on it. Dispatching the reload can fail even though the commit is authoritative
 * (the service was torn down, or Android refuses a background service start), and swallowing that
 * failure would leave the durable state and UI saying chaining is off while the installed chained
 * TUN keeps routing. The requirement is armed before the first dispatch attempt of a fresh commit
 * and re-armed on a failed dispatch; a later path (the next status read or toggle) retries it. A
 * missing live generation discharges it: there is no runtime to reload, and the next start reads
 * the committed preference.
 *
 * This mirrors the filter-mutation path's `RuntimeReloadReconciliation`; it is deliberately kept
 * free of the protection-state types so the chained surface can retry without depending on the
 * filter authority.
 */
class ChainedPreferenceReloadReconciliation {
    private val lock = Any()
    private var required = false

    /**
     * Bumped by every [request]. A [reconcileOnce] captures it at entry and only discharges the
     * requirement when it is unchanged, so a commit that arrives while an older dispatch is
     * suspended is not erased by that older dispatch's success.
     */
    private var version = 0L

    /** Whether a committed preference still needs to reach the live runtime. */
    val isRequired: Boolean get() = synchronized(lock) { required }

    /** Arms the requirement before the first dispatch attempt for a fresh commit. */
    fun request() = synchronized(lock) {
        required = true
        version += 1L
    }

    /**
     * Attempts one reconciliation of the committed preference against [generation] (the live
     * service generation, or `null` when none is installed).
     *
     * Returns `true` when the requirement is discharged: it was not armed, no runtime is live to
     * reload, or the dispatch succeeded. `false` means the dispatch failed again and the
     * requirement stays armed for the next path. Cancellation propagates without discharging.
     *
     * The discharge is bound to the requirement version this attempt observed: a newer [request]
     * committed while [reload] was suspended stays armed, so if the newer toggle's own dispatch
     * fails the retry is not lost.
     */
    suspend fun reconcileOnce(
        generation: Long?,
        reload: suspend (Long) -> Unit,
    ): Boolean {
        val attempted = synchronized(lock) {
            if (!required) return true
            version
        }
        if (generation == null) {
            discharge(attempted)
            return true
        }
        return try {
            reload(generation)
            discharge(attempted)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun discharge(attempted: Long) = synchronized(lock) {
        if (version == attempted) required = false
    }
}
