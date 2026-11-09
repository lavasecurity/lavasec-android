package com.lavasec.app.vpn

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The outcome of a route claim made by a tunnel-lifecycle operation.
 *
 * Mirrors iOS's "the session stays until it is replaced": a claim the engine queue has not
 * superseded is [INSTALLED] and the caller drives it, while a claim a newer operation superseded is
 * [RETAINED] — the queue keeps it as the live interface so the successor's establishment replaces
 * it without a gap. [REFUSED] means the queue is closed and the caller must release the claim itself.
 */
internal enum class ChainedClaimOutcome {
    INSTALLED,
    RETAINED,
    REFUSED,
}

/**
 * The one serial engine confinement every tunnel-lifecycle object runs on, and the single authority
 * on whether the caller is standing on it — the Android analogue of the iOS `ChainedEngineQueue`
 * (`Sources/LavaSecChainedUpstream/ChainedEngineQueue.swift`).
 *
 * ## Why the key belongs to the QUEUE, not to its users
 *
 * iOS used to create its specific key per session and moved it onto the shared queue because a
 * tunnel lifecycle outlives its sessions: the outage driver holds the budget across attempts and
 * builds a fresh runner per attempt, so several runners share one queue and the driver's timers
 * fire on it too. Android's confinement has the same shape — a runtime outlives each chained
 * session and each DNS generation — so "am I on the queue?" is a fact about the QUEUE, tracked
 * with a `ThreadLocal` on the queue rather than on any one owner. `isCurrent` answers it, and
 * [run] uses it to avoid the deadlock a blocking wait from the queue itself would produce.
 *
 * This queue IS the chained wire engine's serial context: the outage driver, its runners, and the
 * engine calls they make all run here (`EngineQueueChainedDispatcher` routes the engine through
 * [runSerial], non-superseding and re-entrant), mirroring iOS's one queue shared by the driver and
 * its runners. It is still distinct from the DNS-only engine's serialization (`BoundedTunRuntime`),
 * which is a separate path that never runs at the same time; the two must not block on each other.
 *
 * ## The single-establishment invariant, and the route claim
 *
 * iOS establishes its tunnel once per provider lifecycle through
 * `setTunnelNetworkSettings`, so its queue never has to invalidate an in-flight attempt: a fresh
 * session is swapped in underneath the one interface and the old session is released only after the
 * new one is live. Android's `VpnService.Builder.establish()` **replaces** the active interface, so
 * the claim is the one operation that must never run twice concurrently and must never be torn down
 * out from under its successor.
 *
 * The Android mapping is [resolveRouteClaim]: the monotonic [generation]/[supersede] token and the
 * retained-claim slot share one [lock], so the decision "is this operation still current?" and the
 * record of the claimed interface are one critical section with respect to any supersession. An
 * operation that is still current gets [ChainedClaimOutcome.INSTALLED]; a superseded one gets
 * [ChainedClaimOutcome.RETAINED] and the queue KEEPS the claimed interface, releasing it only when
 * the next operation installs its own claim or the queue closes. That is how "the running tunnel
 * stays active until its replacement is ready" survives the platform's replace-on-establish
 * behaviour: the successor's [resolveRouteClaim] releases the retained predecessor at the moment
 * its own claim is live. A closed queue refuses the claim so the caller releases it.
 *
 * A newer start/reload/reconnect/stop calls [supersede]; because it takes the same [lock], it can
 * never interleave between the generation check and the claim/retention that consumes its token.
 * That binding is the Android mapping of iOS's single `setTunnelNetworkSettings`-per-lifecycle
 * guarantee; the confinement itself is the ported part.
 *
 * The executor is a process-wide single-thread daemon by default, so two different runtimes (a
 * live predecessor and its replacement candidate) still cannot establish at the same time. Each
 * queue keeps its OWN generation and latch, so retiring one runtime never invalidates the other;
 * tests may inject a dedicated executor.
 *
 * ## Re-entrancy
 *
 * [run] runs [work] on the queue and returns its result. A call made while already executing on
 * the queue — an operation that nests an install, the termination action that rebuilds DNS-only —
 * runs the block INLINE with the SAME generation token, mirroring the iOS queue's
 * `DispatchSpecificKey` re-entrancy contract: a blocking wait from the queue itself would
 * deadlock. [enqueue] is the always-asynchronous variant, used for fire-and-forget teardown so a
 * slow engine join never blocks the caller (iOS `enqueue`).
 */
internal class ChainedEngineQueue(
    private val executor: ExecutorService = SharedExecutor,
) : AutoCloseable {

    /**
     * Guards [generation], [closed], and [retainedClaim] together, so a supersede can never land
     * between a route claim's generation check and the record of the claimed interface.
     */
    private val lock = ReentrantLock()

    /** Monotonic operation token. Bumped by every new operation and by [supersede]/[close]. */
    private var generation = 0L

    private var closed = false

    /**
     * The interface a superseded operation had already claimed, kept live so the successor's
     * establishment replaces it instead of finding no tunnel. Owned by the queue; released when a
     * successor installs its claim or when the queue closes.
     */
    private var retainedClaim: AutoCloseable? = null

    private val onQueue = ThreadLocal.withInitial { false }
    private val currentOperation = ThreadLocal<Long?>()

    val isClosed: Boolean get() = lock.withLock { closed }

    /**
     * Whether the caller is already executing on this queue — the iOS `isCurrent` property.
     *
     * Distinct from [isOperationCurrent], which asks whether a captured generation is still
     * current. This one answers "am I on the serial thread?": the fact [run] uses to run inline
     * rather than re-enter the executor.
     */
    val isCurrent: Boolean get() = onQueue.get()

    fun currentGeneration(): Long = lock.withLock { generation }

    /**
     * Invalidate every in-flight operation of this queue and return the new token.
     *
     * Any thread may call this; it never blocks. An operation that captured an older token fails
     * [isEstablishAllowed] and retains what it already claimed instead of handing a route-claiming
     * tunnel to an operation the caller has superseded. This is the Android platform addition
     * described in the type doc.
     */
    fun supersede(): Long = lock.withLock { ++generation }

    /** Whether [token] is still the queue's current operation. */
    fun isOperationCurrent(token: Long): Boolean = lock.withLock { !closed && token == generation }

    /** The gate an establishment must pass immediately before it claims the interface. */
    fun isEstablishAllowed(token: Long): Boolean = isOperationCurrent(token)

    /**
     * Atomically resolves the route claim [claim] made by operation [token].
     *
     * The check "is [token] still current?" and the claim are one critical section with respect to
     * [supersede]/[close], so a supersession can never land between them and admit a stale claim.
     *
     * - [ChainedClaimOutcome.INSTALLED]: [token] is current. Any previously retained predecessor is
     *   dead (this claim replaced its interface), so it is released; the caller owns and drives
     *   [claim].
     * - [ChainedClaimOutcome.RETAINED]: [token] was superseded while the queue is open. [claim]
     *   becomes the queue's live tunnel so the successor's establishment can replace it without a
     *   gap; the caller must neither drive nor close it. Any previous retained predecessor is
     *   released.
     * - [ChainedClaimOutcome.REFUSED]: the queue is closed. The caller releases [claim].
     */
    fun resolveRouteClaim(token: Long, claim: AutoCloseable): ChainedClaimOutcome = lock.withLock {
        if (closed) return ChainedClaimOutcome.REFUSED
        val displaced = retainedClaim
        retainedClaim = null
        displaced?.closeSilently()
        if (token == generation) {
            ChainedClaimOutcome.INSTALLED
        } else {
            retainedClaim = claim
            ChainedClaimOutcome.RETAINED
        }
    }

    /**
     * Retains [claim] as the current tunnel after a supersession that landed between the caller's
     * [resolveRouteClaim] and its later registration.
     *
     * A current-operation re-check at registration can lose the race; the interface is already
     * claimed, so closing it would reopen the gap this whole path exists to close. The queue owns
     * it instead and releases it when a successor installs or the queue closes. A closed queue
     * never retains: it owns nothing and the caller must release.
     */
    fun retainRouteClaim(claim: AutoCloseable): Boolean = lock.withLock {
        if (closed) return false
        val displaced = retainedClaim
        retainedClaim = claim
        displaced?.closeSilently()
        true
    }

    /**
     * Runs [work] on the queue as its newest operation, inline when already on the queue.
     *
     * If the caller is already executing a queued operation, [work] runs inline with the existing
     * operation token. Otherwise the block runs on the queue thread and this call waits for its
     * result. The token parameter is the Android platform addition (tunnel supersede/teardown):
     * iOS's `run` closure takes no argument because it has no in-flight attempt to invalidate.
     */
    fun <T> run(work: (Long) -> T): T {
        currentOperation.get()?.let { return work(it) }
        val token = supersede()
        return execute {
            currentOperation.set(token)
            try {
                work(token)
            } finally {
                currentOperation.set(null)
            }
        }
    }

    /**
     * Runs [work] on the queue as ENGINE WORK, without becoming a new lifecycle operation.
     *
     * The outage driver and every runner it builds share this one serial confinement (the Android
     * form of iOS's "the driver and its runners run on one `ChainedEngineQueue`"), so a driver
     * timer or a runner reporting its own session end must never bump the lifecycle generation the
     * way [run] does: an engine tick or a session-end callback is not a tunnel transition, and
     * invalidating an in-flight establishment from one would drop a route claim for no reason. It
     * is still the same serial owner, so a caller already on the queue runs inline exactly as
     * [run] does; a caller elsewhere executes here and waits.
     */
    fun <T> runSerial(work: () -> T): T {
        if (onQueue.get()) return work()
        return execute { task(work) }
    }

    /**
     * Schedules [work] here, always asynchronously — the iOS `enqueue`.
     *
     * Used for fire-and-forget teardown so a slow engine join never blocks the calling (service)
     * thread. A rejection means the shared executor is shutting down and the runtime is already
     * latched closed, so the work is dropped. Unlike iOS's bare `queue.async`, a thrown teardown
     * failure is swallowed: it is non-fatal by contract (the runtime is already closed) and must
     * not reach the executor's uncaught-exception handler.
     */
    fun enqueue(work: () -> Unit) {
        try {
            executor.execute {
                task {
                    try {
                        work()
                    } catch (_: Throwable) {
                        // Teardown failures are non-fatal; the runtime is already closed.
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Executor shutting down: nothing left to tear down that it does not already own.
        }
    }

    override fun close() {
        val retained = lock.withLock {
            closed = true
            generation++
            val displaced = retainedClaim
            retainedClaim = null
            displaced
        }
        retained?.closeSilently()
    }

    private fun <T> execute(work: () -> T): T {
        if (onQueue.get()) return work()
        val future: Future<T> = try {
            executor.submit(Callable { task(work) })
        } catch (_: RejectedExecutionException) {
            // A latched/closed queue only refuses after the owning runtime stopped it; the block's
            // own closed check makes running it inline safe.
            return work()
        }
        return try {
            future.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("tunnel lifecycle operation was interrupted", interrupted)
        }
    }

    private fun <T> task(work: () -> T): T {
        onQueue.set(true)
        try {
            return work()
        } finally {
            onQueue.set(false)
        }
    }

    private fun AutoCloseable.closeSilently() {
        runCatching { close() }
    }

    private companion object {
        val SharedExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lava-chained-engine").apply { isDaemon = true }
        }
    }
}
