package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Internal lifecycle observation used to make query-lease linearization deterministic in tests.
 *
 * Implementations must not throw and production uses [NONE].
 */
internal interface ResolverLeaseLifecycleObserver {
    fun afterAcceptedBeforeStart() = Unit

    fun onStarted() = Unit

    fun beforeChildDrain() = Unit

    fun onChildDrained() = Unit

    fun onCloseInvoked() = Unit

    fun onRetirementStarted() = Unit

    fun onRetirementFinished() = Unit

    companion object {
        val NONE: ResolverLeaseLifecycleObserver = object : ResolverLeaseLifecycleObserver {}
    }
}

/** Owns route coordination and publication for one exact engine epoch. */
internal class ResolverCoordinator(
    private val epoch: EngineEpoch,
    private val plan: ResolverPlan,
    private val registry: ResolverResourceRegistry,
    deviceRoute: DeviceDnsRoute?,
    mullvadRoute: DnsResolver?,
    wedgeEvidence: DeviceResolverWedgeEvidence,
    clock: MonotonicClock = SystemMonotonicClock,
    private val transitionState: ResolverTransitionState = ResolverTransitionState(),
    private val leaseObserver: ResolverLeaseLifecycleObserver =
        ResolverLeaseLifecycleObserver.NONE,
    private val childDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PrimaryDnsResolver {
    private val endpointBackoff = ResolverEndpointBackoffPolicy(clock)
    private val sequencer: ResolverRouteSequencer

    init {
        require(plan.networkGeneration == epoch.networkGeneration) {
            "resolver plan network generation must match the engine epoch"
        }
        require(plan.primaryResolverIdentity == epoch.primaryResolverIdentity) {
            "resolver plan primary identity must match the engine epoch"
        }
        require(plan.fallbackCoverage == epoch.fallbackCoverage) {
            "resolver plan fallback coverage must match the engine epoch"
        }
        require(plan.hasDeviceRoute == (deviceRoute != null)) {
            "Device route presence must match the resolver plan"
        }
        require(plan.hasNonDeviceRoutes == (mullvadRoute != null)) {
            "Mullvad route presence must match the resolver plan"
        }

        sequencer = ResolverRouteSequencer(
            plan = plan,
            deviceRoute = deviceRoute,
            mullvadRoute = mullvadRoute,
            endpointBackoff = endpointBackoff,
            wedgeEvidence = wedgeEvidence,
            clock = clock,
        )
    }

    override suspend fun resolve(request: ResolverRequest): ResolverResult = resolveOwned(request, false)

    override suspend fun resolvePrimary(request: ResolverRequest): ResolverResult = resolveOwned(request, true)

    private suspend fun resolveOwned(request: ResolverRequest, primaryOnly: Boolean): ResolverResult {
        preflightFailure(request)?.let { return ResolverResult.Failure(it) }

        val queryLease = ResolverQueryLease(
            endpointBackoff = endpointBackoff,
            childDispatcher = childDispatcher,
            observer = leaseObserver,
        ) {
            try {
                if (primaryOnly) sequencer.resolvePrimary(request) else sequencer.resolve(request)
            } catch (_: CancellationException) {
                ResolverCandidate(
                    result = cancelled(),
                    primaryResolverKind = plan.primaryResolverKind,
                    acceptedResolverKind = null,
                )
            }
        }
        if (!registry.registerIfCurrent(queryLease, epoch)) return staleEpoch()
        if (!queryLease.accept()) return staleEpoch()
        leaseObserver.afterAcceptedBeforeStart()
        if (!queryLease.start()) return staleEpoch()

        val childOutcome = try {
            queryLease.awaitOutcome()
        } catch (cancelled: CancellationException) {
            val callerWasActive = currentCoroutineContext().isActive
            val ownershipTransferred = consumeAbortedLease(queryLease)
            if (!callerWasActive) throw cancelled
            return if (ownershipTransferred) cancelled() else staleEpoch()
        }

        return when (childOutcome) {
            is ResolverChildOutcome.Candidate ->
                publishCandidate(queryLease, childOutcome.candidate, primaryOnly)

            is ResolverChildOutcome.Failed -> {
                consumeAbortedLease(queryLease)
                throw childOutcome.failure
            }
        }
    }

    private fun preflightFailure(request: ResolverRequest): ResolverFailureReason? {
        if (request.epoch != epoch) return ResolverFailureReason.STALE_EPOCH
        val networkContext = request.networkContext
        if (networkContext.network != plan.network) return ResolverFailureReason.STALE_EPOCH
        if (networkContext.networkGeneration != plan.networkGeneration) {
            return ResolverFailureReason.STALE_EPOCH
        }
        if (networkContext.isCellular != plan.isCellular) {
            return ResolverFailureReason.STALE_EPOCH
        }
        if (
            plan.deviceDnsRequested &&
            DeviceDnsAddressPolicy.select(
                networkContext.dnsServers,
                networkContext.isCellular,
            ) != plan.deviceDnsServers
        ) {
            return ResolverFailureReason.STALE_EPOCH
        }
        ResolverReplyBoundary.validateRequest(request)?.let { return it.reason }
        return if (registry.isCurrent(epoch)) null else ResolverFailureReason.STALE_EPOCH
    }

    private suspend fun consumeAbortedLease(queryLease: ResolverQueryLease): Boolean =
        withContext(NonCancellable) {
            queryLease.cancelAndDrain()
            if (!registry.unregister(queryLease)) return@withContext false
            check(queryLease.releaseCancelled()) {
                "cancelled resolver lease ownership did not transfer"
            }
            true
        }

    private fun publishCandidate(
        queryLease: ResolverQueryLease,
        candidate: ResolverCandidate,
        primaryOnly: Boolean,
    ): ResolverResult {
        var publishedResult: ResolverResult = staleEpoch()
        var transitionFailure: Throwable? = null
        val published = registry.completeIfCurrent(queryLease, epoch) {
            if (!queryLease.release()) return@completeIfCurrent false
            try {
                // A health-only query cannot turn the client-facing fallback mode off or on.
                publishedResult = if (primaryOnly) candidate.result else transitionState.commit(candidate)
                true
            } catch (failure: Throwable) {
                transitionFailure = failure
                false
            }
        }
        transitionFailure?.let { throw it }
        return if (published) publishedResult else staleEpoch()
    }

    private fun staleEpoch(): ResolverResult.Failure =
        ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)

    private fun cancelled(): ResolverResult.Failure =
        ResolverResult.Failure(ResolverFailureReason.CANCELLED)
}

private sealed interface ResolverChildOutcome {
    data class Candidate(val candidate: ResolverCandidate) : ResolverChildOutcome

    data class Failed(val failure: Throwable) : ResolverChildOutcome
}

/**
 * Registry-owned query work whose child lifetime is independent of the resolving caller.
 *
 * Success, caller cancellation, and epoch retirement consume distinct terminal states.
 */
private class ResolverQueryLease(
    private val endpointBackoff: ResolverEndpointBackoffPolicy,
    childDispatcher: CoroutineDispatcher,
    private val observer: ResolverLeaseLifecycleObserver,
    operation: suspend () -> ResolverCandidate,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val synchronousDrain = CountDownLatch(1)
    private val suspendingDrain = CompletableDeferred<Unit>()
    private val drainSignalled = AtomicBoolean(false)
    private val childBodyEntered = AtomicBoolean(false)
    private val retirementFinished = CountDownLatch(1)
    private var state = State.CREATED
    private val child = CoroutineScope(childDispatcher).async(
        start = CoroutineStart.LAZY,
    ) {
        childBodyEntered.set(true)
        try {
            ResolverChildOutcome.Candidate(operation())
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            ResolverChildOutcome.Failed(failure)
        } finally {
            completeChildDrain()
        }
    }

    init {
        child.invokeOnCompletion {
            if (childBodyEntered.compareAndSet(false, true)) {
                completeChildDrain()
            }
        }
    }

    fun accept(): Boolean = lock.withLock {
        if (state != State.CREATED) return@withLock false
        state = State.ACCEPTED
        true
    }

    fun start(): Boolean {
        val started = lock.withLock {
            if (state != State.ACCEPTED) return false
            state = State.RUNNING
            check(child.start()) { "accepted resolver child could not start" }
            true
        }
        if (started) observer.onStarted()
        return started
    }

    suspend fun awaitOutcome(): ResolverChildOutcome = child.await()

    /** Successful publication transfer; deliberately does not cancel or wait. */
    fun release(): Boolean = lock.withLock {
        if (state != State.DRAINED) return@withLock false
        state = State.RELEASED
        true
    }

    suspend fun cancelAndDrain() {
        val directlyDrained = lock.withLock {
            when (state) {
                State.CREATED,
                State.ACCEPTED,
                -> {
                    state = State.CANCELLED_DRAINED
                    true
                }

                State.RUNNING -> {
                    state = State.CANCELLING
                    false
                }

                State.DRAINED -> {
                    state = State.CANCELLED_DRAINED
                    true
                }

                State.CANCELLING,
                State.RETIRING,
                -> false

                State.CANCELLED_DRAINED,
                State.RETIRED,
                State.RELEASED,
                -> true
            }
        }
        child.cancel()
        if (directlyDrained) signalDrain()
        suspendingDrain.await()
    }

    fun releaseCancelled(): Boolean = lock.withLock {
        if (state != State.CANCELLED_DRAINED) return@withLock false
        state = State.RELEASED
        true
    }

    /**
     * Epoch-retirement path. Policy deactivation is ordered before child cancellation and drain.
     */
    override fun close() {
        observer.onCloseInvoked()
        val closeAction = lock.withLock {
            when (state) {
                State.RELEASED,
                State.RETIRED,
                -> CloseAction.ALREADY_FINISHED

                State.RETIRING -> CloseAction.WAIT_FOR_RETIREMENT

                State.CREATED,
                State.ACCEPTED,
                -> {
                    state = State.RETIRING
                    CloseAction.UNSTARTED
                }

                State.DRAINED,
                State.CANCELLED_DRAINED,
                -> {
                    state = State.RETIRING
                    CloseAction.ALREADY_DRAINED
                }

                State.RUNNING,
                State.CANCELLING,
                -> {
                    state = State.RETIRING
                    CloseAction.RUNNING
                }
            }
        }

        when (closeAction) {
            CloseAction.ALREADY_FINISHED -> return
            CloseAction.WAIT_FOR_RETIREMENT -> {
                awaitRetirementUninterruptibly()
                return
            }

            CloseAction.UNSTARTED,
            CloseAction.ALREADY_DRAINED,
            CloseAction.RUNNING,
            -> Unit
        }

        observer.onRetirementStarted()
        endpointBackoff.retire()
        child.cancel()
        if (closeAction != CloseAction.RUNNING) signalDrain()
        awaitDrainUninterruptibly()
        try {
            observer.onRetirementFinished()
        } finally {
            lock.withLock {
                state = State.RETIRED
                retirementFinished.countDown()
            }
        }
    }

    private fun childDrained() {
        lock.withLock {
            state = when (state) {
                State.RUNNING -> State.DRAINED
                State.CANCELLING -> State.CANCELLED_DRAINED
                State.RETIRING -> State.RETIRING
                State.CREATED,
                State.ACCEPTED,
                -> State.CANCELLED_DRAINED

                State.DRAINED,
                State.CANCELLED_DRAINED,
                State.RETIRED,
                State.RELEASED,
                -> state
            }
        }
        signalDrain()
    }

    private fun completeChildDrain() {
        try {
            observer.beforeChildDrain()
        } finally {
            childDrained()
        }
    }

    private fun signalDrain() {
        if (!drainSignalled.compareAndSet(false, true)) return
        synchronousDrain.countDown()
        suspendingDrain.complete(Unit)
        observer.onChildDrained()
    }

    private fun awaitDrainUninterruptibly() {
        var interrupted = false
        while (true) {
            try {
                synchronousDrain.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun awaitRetirementUninterruptibly() {
        var interrupted = false
        while (true) {
            try {
                retirementFinished.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private enum class State {
        CREATED,
        ACCEPTED,
        RUNNING,
        DRAINED,
        CANCELLING,
        CANCELLED_DRAINED,
        RETIRING,
        RETIRED,
        RELEASED,
    }

    private enum class CloseAction {
        ALREADY_FINISHED,
        WAIT_FOR_RETIREMENT,
        UNSTARTED,
        ALREADY_DRAINED,
        RUNNING,
    }
}
