package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.platform.ProtectionFailure
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

internal data class TunRuntimeJobTopology(
    val childCount: Int,
    val childIdentities: List<String>,
)

internal class BoundedTunRuntime(
    private val sessionToken: Long,
    private val packetSession: TunPacketSession,
    private val observer: TunRuntimeObserver,
    dispatcher: CoroutineDispatcher,
    private val capacities: TunRuntimeCapacities = TunRuntimeCapacities.Production,
) {
    private val lock = ReentrantLock()
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + dispatcher)
    private val readerWake = Channel<Unit>(Channel.CONFLATED)
    private val workerWake = Channel<Unit>(Channel.CONFLATED)
    private val readBuffer = ByteArray(DnsOnlyTunContract.MTU)
    private val jobs = mutableListOf<OwnedRuntimeJob>()
    private val replacementAttempts =
        ConcurrentHashMap<EngineIdentity, ProposalAttempt>()

    @Volatile
    private var sessionWriter: TunSessionWriter? = null

    private val fixedPublicationSink = ProxyPublicationSink { publication ->
        sessionWriter?.publish(publication) ?: false
    }

    val publicationSink: ProxyPublicationSink
        get() = fixedPublicationSink

    private var scheduler: TunQueryScheduler? = null
    private var started = false
    private var sealed = false
    private var terminationState: TerminationState? = null
    private var deferredTermination: DeferredTermination? = null
    private var readerWriterCapacityBlock: TunQueryId? = null
    private var preSealReplacementOperations = 0

    fun start(initialEngine: ReadyDnsEngine) {
        lock.withLock {
            check(!started) { "runtime can only be started once" }
            check(!sealed) { "runtime is already closed" }
        }
        validateInitialEngine(initialEngine)

        val newJobs = lock.withLock {
            check(!started) { "runtime can only be started once" }
            check(!sealed) { "runtime is already closed" }
            started = true
            val newScheduler = TunQueryScheduler(
                sessionToken = sessionToken,
                initialEngine = initialEngine,
                capacities = capacities,
            )
            val newWriter = TunSessionWriter(
                stateLock = lock,
                scheduler = newScheduler,
                packetSession = packetSession,
                observer = observer,
                sealRuntime = ::sealWriter,
                claimWriteFailure = ::claimWriteFailure,
                claimEvidenceFailure = ::claimEvidenceFailure,
                finishDeferredTermination = ::finishDeferredTermination,
                schedulerTransitioned = ::wakeAfterSchedulerTransition,
            )
            check(sessionWriter == null) { "session writer is already bound" }
            scheduler = newScheduler
            sessionWriter = newWriter
            buildList {
                repeat(capacities.activeResolutions) { workerIndex ->
                    val identity = "tun-worker-$workerIndex"
                    add(
                        OwnedRuntimeJob(
                            identity = identity,
                            job = scope.launch(
                                context = CoroutineName(identity),
                                start = CoroutineStart.LAZY,
                            ) {
                                workerLoop(workerIndex)
                            },
                        ),
                    )
                }
                val readerIdentity = "tun-reader"
                add(
                    OwnedRuntimeJob(
                        identity = readerIdentity,
                        job = scope.launch(
                            context = CoroutineName(readerIdentity),
                            start = CoroutineStart.LAZY,
                        ) {
                            readerLoop()
                        },
                    ),
                )
                val writerIdentity = "tun-writer"
                add(
                    OwnedRuntimeJob(
                        identity = writerIdentity,
                        job = scope.launch(
                            context = CoroutineName(writerIdentity),
                            start = CoroutineStart.LAZY,
                        ) {
                            newWriter.run()
                        },
                    ),
                )
            }.also(jobs::addAll)
        }
        newJobs.forEach { it.job.start() }
    }

    fun requestClose() {
        terminate(failure = null)
    }

    suspend fun replaceEngine(nextEngine: ReadyDnsEngine): Boolean {
        val lifecycle = registerReplacementLifecycle()
        val attempt = try {
            registerProposalAttempt(nextEngine)
        } catch (failure: Throwable) {
            releaseReplacementLifecycle(lifecycle)
            throw failure
        }
        try {
            val validation = validateEngine(nextEngine)
            if (validation != EngineValidation.CoherentCurrent) {
                val decision = lock.withLock {
                    val currentEngineIsProposal =
                        scheduler?.isCurrentEngine(nextEngine) == true
                    if (
                        currentEngineIsProposal ||
                        validation == EngineValidation.Retired
                    ) {
                        ReplacementDecision.RejectedCloseAlreadyOwned
                    } else if (attempt.claimClose()) {
                        ReplacementDecision.RejectedCloseProposal
                    } else {
                        ReplacementDecision.RejectedCloseAlreadyOwned
                    }
                }
                if (decision == ReplacementDecision.RejectedCloseProposal) {
                    closeEngine(nextEngine)?.let { throw it }
                }
                return false
            }

            val decision = lock.withLock {
                if (sealed) {
                    val closeOwnsProposal = terminationState
                        ?.engines
                        .orEmpty()
                        .any { it === nextEngine }
                    if (closeOwnsProposal) {
                        attempt.claimClose()
                        ReplacementDecision.RejectedCloseAlreadyOwned
                    } else if (attempt.claimClose()) {
                        ReplacementDecision.RejectedCloseProposal
                    } else {
                        ReplacementDecision.RejectedCloseAlreadyOwned
                    }
                } else {
                    when (
                        val install = checkNotNull(scheduler) {
                            "runtime has not been started"
                        }.installReplacement(nextEngine)
                    ) {
                        is TunEngineInstall.Installed -> {
                            ReplacementDecision.Installed(
                                claimEngineCloseOwnership(install.priorEngine),
                            )
                        }
                        TunEngineInstall.RejectedAlreadyCurrent ->
                            ReplacementDecision.RejectedCloseAlreadyOwned
                        TunEngineInstall.RejectedUninstalled ->
                            if (attempt.claimClose()) {
                                ReplacementDecision.RejectedCloseProposal
                            } else {
                                ReplacementDecision.RejectedCloseAlreadyOwned
                            }
                    }
                }
            }

            return when (decision) {
                is ReplacementDecision.Installed -> {
                    wakeAfterSchedulerTransition()
                    closeOwnedEngine(decision.priorClose)?.let { throw it }
                    true
                }
                ReplacementDecision.RejectedCloseProposal -> {
                    closeEngine(nextEngine)?.let { throw it }
                    false
                }
                ReplacementDecision.RejectedCloseAlreadyOwned -> false
            }
        } finally {
            try {
                releaseProposalAttempt(attempt)
            } finally {
                releaseReplacementLifecycle(lifecycle)
            }
        }
    }

    suspend fun join() {
        val ownedJobs = lock.withLock {
            check(started) { "runtime has not been started" }
            jobs.map(OwnedRuntimeJob::job)
        }
        ownedJobs.joinAll()
        val completion = lock.withLock {
            val state = checkNotNull(terminationState) {
                "runtime children completed without a termination claim"
            }
            state.jobsJoined = true
            completeTerminationIfQuiescent(state)
            state.completion
        }
        completion.await()
    }

    suspend fun closeAndJoin() {
        requestClose()
        join()
    }

    internal fun snapshotForTest(): TunSchedulerSnapshot = lock.withLock {
        checkNotNull(scheduler) { "runtime has not been started" }.snapshot()
    }

    internal fun jobTopologyForTest(): TunRuntimeJobTopology = lock.withLock {
        val children = scopeJob.children.toList()
        TunRuntimeJobTopology(
            childCount = children.size,
            childIdentities = children.map { child ->
                jobs.singleOrNull { it.job === child }?.identity ?: "<unowned>"
            },
        )
    }

    internal fun observeJobCompletionForTest(
        observer: (String) -> Unit,
    ) = lock.withLock {
        jobs.forEach { owned ->
            owned.job.invokeOnCompletion { observer(owned.identity) }
        }
    }

    private suspend fun readerLoop() {
        while (true) {
            val reservation = lock.withLock {
                if (sealed) {
                    ReaderReservation(reserved = false, materializedIntent = false)
                } else {
                    val currentScheduler = checkNotNull(scheduler)
                    val blockedId = readerWriterCapacityBlock
                    val materializedIntent =
                        blockedId != null &&
                            currentScheduler.materializeAwaitingWrite(blockedId)
                    if (
                        blockedId != null &&
                        currentScheduler.retainsReaderWriterCapacity(blockedId)
                    ) {
                        ReaderReservation(
                            reserved = false,
                            materializedIntent = materializedIntent,
                        )
                    } else {
                        readerWriterCapacityBlock = null
                        ReaderReservation(
                            reserved = currentScheduler.reserveReaderPermit(),
                            materializedIntent = materializedIntent,
                        )
                    }
                }
            }
            if (reservation.materializedIntent) sessionWriter?.wake()
            if (!reservation.reserved) {
                if (isSealed()) return
                if (readerWake.receiveCatching().getOrNull() == null) return
                continue
            }

            val read = try {
                packetSession.readPacket(readBuffer)
            } catch (cancelled: CancellationException) {
                if (isSealed()) return
                terminate(ProtectionFailure.TunnelReadFailed)
                return
            } catch (_: Throwable) {
                terminate(ProtectionFailure.TunnelReadFailed)
                return
            }

            when (read) {
                is TunRead.Packet -> {
                    if (read.byteCount !in 1..readBuffer.size) {
                        terminate(ProtectionFailure.TunnelReadFailed)
                        return
                    }
                    processPacket(readBuffer.copyOf(read.byteCount))
                }
                TunRead.EndOfStream -> {
                    terminate(ProtectionFailure.TunnelEnded)
                    return
                }
                TunRead.Closed -> {
                    if (!isSealed()) {
                        terminate(ProtectionFailure.TunnelReadFailed)
                    }
                    return
                }
                is TunRead.Failed -> {
                    terminate(ProtectionFailure.TunnelReadFailed)
                    return
                }
            }
        }
    }

    private fun processPacket(packet: ByteArray) {
        val planning = lock.withLock {
            if (sealed) {
                checkNotNull(scheduler).releaseReaderPermit()
                null
            } else {
                val currentScheduler = checkNotNull(scheduler)
                val engine = currentScheduler.currentEngineForPlanning()
                if (engine == null) {
                    currentScheduler.releaseReaderPermit()
                    null
                } else {
                    PlanningOwner(
                        id = currentScheduler.beginPlanning(packet, engine),
                        engine = engine,
                    )
                }
            }
        } ?: return

        val plan = try {
            planning.engine.proxy.plan(packet, planning.engine)
        } catch (_: Throwable) {
            handlePlanningFailure(planning.id)
            return
        }

        val commit = lock.withLock {
            checkNotNull(scheduler).commitPlan(planning.id, plan).also {
                if (it is TunPlanCommit.AwaitWriterCapacity) {
                    check(readerWriterCapacityBlock == null) {
                        "the sole reader already retains a writer-capacity intent"
                    }
                    readerWriterCapacityBlock = it.id
                }
            }
        }
        when (commit) {
            TunPlanCommit.Dropped,
            TunPlanCommit.Queued,
            is TunPlanCommit.AwaitWriterCapacity,
            is TunPlanCommit.ReplacementOwned,
            TunPlanCommit.Suppressed,
            -> wakeAfterSchedulerTransition()
            is TunPlanCommit.RejectOverloaded -> rejectOverloaded(commit)
        }
    }

    private fun rejectOverloaded(commit: TunPlanCommit.RejectOverloaded) {
        val result = try {
            commit.plan.engine.proxy.rejectOverloaded(commit.plan)
        } catch (_: Throwable) {
            handlePlanningFailure(commit.id)
            return
        }
        lock.withLock {
            checkNotNull(scheduler).commitOverload(commit.id, result).also {
                if (it is TunPlanCommit.AwaitWriterCapacity) {
                    check(readerWriterCapacityBlock == null) {
                        "the sole reader already retains a writer-capacity intent"
                    }
                    readerWriterCapacityBlock = it.id
                }
            }
        }
        wakeAfterSchedulerTransition()
    }

    private fun handlePlanningFailure(id: TunQueryId) {
        val termination = lock.withLock {
            when (checkNotNull(scheduler).classifyPlanningFailure(id)) {
                TunPlanningFailureDisposition.IGNORE_STALE -> null
                TunPlanningFailureDisposition.TERMINATE_CURRENT ->
                    claimTermination(
                        failure = ProtectionFailure.DependencyUnavailable,
                        writeFailure = null,
                    )
            }
        }
        if (termination != null) {
            finishTermination(termination)
        } else {
            wakeAfterSchedulerTransition()
        }
    }

    private suspend fun workerLoop(workerIndex: Int) {
        while (true) {
            val claim = lock.withLock {
                if (sealed) null else checkNotNull(scheduler).claimResolution(workerIndex)
            }
            if (claim == null) {
                if (isSealed()) return
                if (workerWake.receiveCatching().getOrNull() == null) return
                continue
            }

            val result = try {
                claim.plan.engine.proxy.execute(claim.plan)
            } catch (cancelled: CancellationException) {
                if (claimResolutionFailureTermination(claim, workerIndex)) return
                wakeAfterSchedulerTransition()
                continue
            } catch (_: Throwable) {
                if (claimResolutionFailureTermination(claim, workerIndex)) return
                wakeAfterSchedulerTransition()
                continue
            }

            lock.withLock {
                checkNotNull(scheduler).completeResolution(claim.id, result)
            }
            wakeAfterSchedulerTransition()
        }
    }

    private fun claimResolutionFailureTermination(
        claim: TunResolutionClaim,
        workerIndex: Int,
    ): Boolean {
        val termination = lock.withLock {
            when (checkNotNull(scheduler).classifyResolutionFailure(claim, workerIndex)) {
                TunResolutionFailureDisposition.IGNORE_STALE -> null
                TunResolutionFailureDisposition.TERMINATE_CURRENT ->
                    claimTermination(
                        failure = ProtectionFailure.DependencyUnavailable,
                        writeFailure = null,
                    )
            }
        }
        if (termination != null) finishTermination(termination)
        return termination != null
    }

    private fun wakeAfterSchedulerTransition() {
        readerWake.trySend(Unit)
        workerWake.trySend(Unit)
        sessionWriter?.wake()
    }

    private fun sealWriter(writeFailure: DnsHealthEvent.TunWriteFailed?) {
        terminate(failure = null, writeFailure = writeFailure)
    }

    private fun claimWriteFailure(
        writeFailure: DnsHealthEvent.TunWriteFailed,
    ): TunWriterTermination? {
        check(lock.isHeldByCurrentThread) {
            "write failure must be claimed under the session-writer fence"
        }
        if (deferredTermination != null) return null
        val claim = claimTermination(
            failure = null,
            writeFailure = writeFailure,
        ) ?: return null
        return TunWriterTermination {
            finishTermination(claim)
        }
    }

    private fun claimEvidenceFailure(): TunWriterTermination? {
        check(lock.isHeldByCurrentThread) {
            "evidence failure must be claimed under the session-writer fence"
        }
        val claim = claimTermination(
            failure = null,
            writeFailure = null,
        ) ?: return null
        return TunWriterTermination {
            finishTermination(claim)
        }
    }

    private fun terminate(
        failure: ProtectionFailure?,
        writeFailure: DnsHealthEvent.TunWriteFailed? = null,
    ) {
        if (lock.isHeldByCurrentThread) {
            if (!sealed && deferredTermination == null) {
                deferredTermination = DeferredTermination(failure, writeFailure)
            }
            return
        }
        val claim = lock.withLock {
            claimTermination(failure, writeFailure)
        } ?: return
        finishTermination(claim)
    }

    private fun finishDeferredTermination() {
        check(!lock.isHeldByCurrentThread) {
            "deferred termination must finish beyond the session-writer fence"
        }
        val claim = lock.withLock {
            val deferred = deferredTermination ?: return
            deferredTermination = null
            claimTermination(deferred.failure, deferred.writeFailure)
        } ?: return
        finishTermination(claim)
    }

    private fun claimTermination(
        failure: ProtectionFailure?,
        writeFailure: DnsHealthEvent.TunWriteFailed?,
    ): TerminationClaim? {
        check(lock.isHeldByCurrentThread) {
            "runtime termination must be claimed under the state lock"
        }
        if (sealed) return null
        sealed = true
        val currentScheduler = scheduler
        val engines = if (currentScheduler == null) {
            emptyList()
        } else {
            currentScheduler.releaseReaderPermit()
            currentScheduler.suppressAll()
        }
        val engineClosures = engines.map(::claimEngineCloseOwnership)
        val state = TerminationState(
            completion = CompletableDeferred(),
            engines = engines,
            failure = failure,
            writeFailure = writeFailure,
        )
        terminationState = state
        return TerminationClaim(
            engineClosures = engineClosures,
            state = state,
        )
    }

    private fun finishTermination(claim: TerminationClaim) {
        var engineCloseFailure: Throwable? = null
        try {
            runCatching { packetSession.close() }
            readerWake.close()
            workerWake.close()
            sessionWriter?.close()
            claim.engineClosures.forEach { ownership ->
                engineCloseFailure = combineFailures(
                    engineCloseFailure,
                    closeOwnedEngine(ownership),
                )
            }
            jobs.asSequence()
                .filter { it.identity.startsWith("tun-worker-") }
                .forEach { it.job.cancel() }
            val reportedFailure = claim.state.failure ?: if (engineCloseFailure != null) {
                ProtectionFailure.DependencyUnavailable
            } else {
                null
            }
            if (reportedFailure != null) {
                observer.publishFailure(reportedFailure)
            }
            if (claim.state.writeFailure != null) {
                observer.publishHealth(claim.state.writeFailure)
            }
        } finally {
            lock.withLock {
                check(!claim.state.terminationFinished) {
                    "termination ownership finished more than once"
                }
                claim.state.engineCloseFailure = engineCloseFailure
                claim.state.terminationFinished = true
                completeTerminationIfQuiescent(claim.state)
            }
        }
    }

    private fun isSealed(): Boolean = lock.withLock { sealed }

    private fun validateInitialEngine(initialEngine: ReadyDnsEngine) {
        require(sessionToken > 0L)
        require(validateEngine(initialEngine) == EngineValidation.CoherentCurrent)
    }

    private fun validateEngine(engine: ReadyDnsEngine): EngineValidation {
        val structurallyCoherent = isStructurallyCoherentEngine(engine)
        val current = runCatching { engine.isCurrent() }.getOrDefault(false)
        return when {
            !current -> EngineValidation.Retired
            structurallyCoherent -> EngineValidation.CoherentCurrent
            else -> EngineValidation.IncoherentCurrent
        }
    }

    private fun isStructurallyCoherentEngine(engine: ReadyDnsEngine): Boolean = runCatching {
        val epoch = engine.epoch
        val policyIdentity = engine.policyIdentity
        val networkContext = engine.networkContext
        val resolverPlan = engine.resolverPlan
        val selectedDeviceServers = if (resolverPlan.deviceDnsRequested) {
            DeviceDnsAddressPolicy.select(
                networkContext.dnsServers,
                networkContext.isCellular,
            )
        } else {
            emptyList()
        }

        epoch.sessionGeneration == sessionToken &&
            epoch.engineGeneration > 0L &&
            policyIdentity.configurationIdentity == epoch.configurationIdentity &&
            policyIdentity.artifactDigest == epoch.artifactDigest &&
            policyIdentity.revision == epoch.policyRevision &&
            networkContext.networkGeneration == epoch.networkGeneration &&
            resolverPlan.network === networkContext.network &&
            resolverPlan.networkGeneration == networkContext.networkGeneration &&
            resolverPlan.isCellular == networkContext.isCellular &&
            resolverPlan.primaryResolverIdentity == epoch.primaryResolverIdentity &&
            resolverPlan.fallbackCoverage == epoch.fallbackCoverage &&
            resolverPlan.isCanonicalReadyRoute() &&
            resolverPlan.deviceDnsServers == selectedDeviceServers
    }.getOrDefault(false)

    private fun closeEngine(engine: ReadyDnsEngine): Throwable? =
        runCatching { engine.close() }.exceptionOrNull()

    private fun registerReplacementLifecycle(): ReplacementLifecycle {
        val preSeal = lock.withLock {
            (!sealed).also {
                if (it) preSealReplacementOperations += 1
            }
        }
        return ReplacementLifecycle(preSeal)
    }

    private fun releaseReplacementLifecycle(lifecycle: ReplacementLifecycle) {
        if (!lifecycle.preSeal) return
        lock.withLock {
            check(preSealReplacementOperations > 0) {
                "pre-seal replacement lifecycle released more than once"
            }
            preSealReplacementOperations -= 1
            terminationState?.let(::completeTerminationIfQuiescent)
        }
    }

    private fun completeTerminationIfQuiescent(state: TerminationState) {
        check(lock.isHeldByCurrentThread) {
            "terminal completion must be checked under the state lock"
        }
        if (
            state.jobsJoined &&
            preSealReplacementOperations == 0 &&
            state.terminationFinished &&
            !state.recordsReleased
        ) {
            checkNotNull(scheduler).releaseSuppressed()
            state.recordsReleased = true
            scopeJob.complete()
            val closeFailure = state.engineCloseFailure
            if (closeFailure == null) {
                state.completion.complete(Unit)
            } else {
                state.completion.completeExceptionally(closeFailure)
            }
        }
    }

    private fun registerProposalAttempt(engine: ReadyDnsEngine): ProposalAttempt {
        val identity = EngineIdentity(engine)
        return checkNotNull(
            replacementAttempts.compute(identity) { key, current ->
                (current ?: ProposalAttempt(key)).also { it.callers += 1 }
            },
        )
    }

    private fun releaseProposalAttempt(attempt: ProposalAttempt) {
        replacementAttempts.compute(attempt.identity) { _, current ->
            check(current === attempt) { "proposal attempt ownership changed" }
            check(attempt.callers > 0) { "proposal attempt released more than once" }
            attempt.callers -= 1
            if (attempt.callers == 0 && attempt.engineCloseOwners == 0) {
                null
            } else {
                attempt
            }
        }
    }

    private fun claimEngineCloseOwnership(
        engine: ReadyDnsEngine,
    ): EngineCloseOwnership {
        val identity = EngineIdentity(engine)
        val attempt = checkNotNull(
            replacementAttempts.compute(identity) { key, current ->
                (current ?: ProposalAttempt(key)).also {
                    check(it.claimClose()) {
                        "engine close ownership was already claimed"
                    }
                    it.engineCloseOwners += 1
                }
            },
        )
        return EngineCloseOwnership(engine, attempt)
    }

    private fun closeOwnedEngine(ownership: EngineCloseOwnership): Throwable? =
        try {
            closeEngine(ownership.engine)
        } finally {
            replacementAttempts.compute(ownership.attempt.identity) { _, current ->
                check(current === ownership.attempt) {
                    "engine close ownership changed"
                }
                check(ownership.attempt.engineCloseOwners > 0) {
                    "engine close ownership released more than once"
                }
                ownership.attempt.engineCloseOwners -= 1
                if (
                    ownership.attempt.callers == 0 &&
                    ownership.attempt.engineCloseOwners == 0
                ) {
                    null
                } else {
                    ownership.attempt
                }
            }
        }

    private fun combineFailures(
        prior: Throwable?,
        next: Throwable?,
    ): Throwable? = when {
        prior == null -> next
        next == null || next === prior -> prior
        else -> prior.apply { addSuppressed(next) }
    }

    private data class PlanningOwner(
        val id: TunQueryId,
        val engine: ReadyDnsEngine,
    )

    private data class ReaderReservation(
        val reserved: Boolean,
        val materializedIntent: Boolean,
    )

    private data class OwnedRuntimeJob(
        val identity: String,
        val job: Job,
    )

    private data class TerminationClaim(
        val engineClosures: List<EngineCloseOwnership>,
        val state: TerminationState,
    )

    private data class DeferredTermination(
        val failure: ProtectionFailure?,
        val writeFailure: DnsHealthEvent.TunWriteFailed?,
    )

    private data class TerminationState(
        val completion: CompletableDeferred<Unit>,
        val engines: List<ReadyDnsEngine>,
        val failure: ProtectionFailure?,
        val writeFailure: DnsHealthEvent.TunWriteFailed?,
        var jobsJoined: Boolean = false,
        var terminationFinished: Boolean = false,
        var recordsReleased: Boolean = false,
        var engineCloseFailure: Throwable? = null,
    )

    private data class ReplacementLifecycle(
        val preSeal: Boolean,
    )

    private enum class EngineValidation {
        CoherentCurrent,
        IncoherentCurrent,
        Retired,
    }

    private sealed interface ReplacementDecision {
        data class Installed(
            val priorClose: EngineCloseOwnership,
        ) : ReplacementDecision
        data object RejectedCloseProposal : ReplacementDecision
        data object RejectedCloseAlreadyOwned : ReplacementDecision
    }

    private data class EngineCloseOwnership(
        val engine: ReadyDnsEngine,
        val attempt: ProposalAttempt,
    )

    private class EngineIdentity(
        val engine: ReadyDnsEngine,
    ) {
        override fun equals(other: Any?): Boolean =
            other is EngineIdentity && engine === other.engine

        override fun hashCode(): Int = System.identityHashCode(engine)
    }

    private class ProposalAttempt(
        val identity: EngineIdentity,
    ) {
        private val closeClaimed = AtomicBoolean()
        var callers: Int = 0
        var engineCloseOwners: Int = 0

        fun claimClose(): Boolean = closeClaimed.compareAndSet(false, true)
    }
}
