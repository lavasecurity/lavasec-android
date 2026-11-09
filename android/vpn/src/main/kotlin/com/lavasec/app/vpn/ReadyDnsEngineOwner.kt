package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Exact borrowed inputs for one structurally coherent engine generation. */
internal class ReadyDnsEngineCandidate(
    val epoch: EngineEpoch,
    val networkContext: NetworkContext,
    val resolverPlan: ResolverPlan,
    policy: OwnedDnsPolicyRuntime,
) {
    private val ownedPolicy = AtomicReference<OwnedDnsPolicyRuntime?>(policy)

    val policyIdentity: ProxyPolicyIdentity = policy.identity
    val proxy: DnsProxy = policy.proxy

    fun takePolicy(): OwnedDnsPolicyRuntime =
        checkNotNull(ownedPolicy.getAndSet(null)) {
            "ready DNS engine candidate policy has already transferred"
        }
}

/**
 * Long-lived resolver ownership for one engine.
 *
 * Query leases, calls, and sockets belong in the supplied [ResolverResourceRegistry]. This bundle
 * owns the resolver runtimes whose bounded close outcome must remain visible to the engine owner.
 */
internal interface OwnedReadyDnsResolver : PrimaryDnsResolver, AutoCloseable

/**
 * Synchronously constructs one cold resolver bundle and starts no query.
 *
 * A failing factory must close any partially built long-lived bundle that it has not returned.
 */
internal fun interface ReadyDnsResolverFactory {
    fun create(
        epoch: EngineEpoch,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
        registry: ResolverResourceRegistry,
    ): OwnedReadyDnsResolver
}

/**
 * Production owner for one exact ready DNS-engine generation.
 *
 * Cross-generation proposal and replacement remain exclusively owned by [BoundedTunRuntime].
 */
internal class ReadyDnsEngineOwner private constructor(
    candidate: ReadyDnsEngineCandidate,
    private val registry: GenerationSocketRegistry,
    private val gatedRegistry: ColdResolverResourceRegistry,
    private val resolver: OwnedReadyDnsResolver,
    private val policy: OwnedDnsPolicyRuntime,
    private val closeReentryGuard: EngineCloseReentryGuard,
) : ReadyDnsEngine {
    override val epoch: EngineEpoch = candidate.epoch
    override val networkContext: NetworkContext = candidate.networkContext
    override val resolverPlan: ResolverPlan = candidate.resolverPlan
    override val policyIdentity: ProxyPolicyIdentity = candidate.policyIdentity
    override val proxy: DnsProxy = candidate.proxy

    private val lock = ReentrantLock()
    private val closeCompleted = lock.newCondition()
    private val quiescent = lock.newCondition()
    private val publicationDepth = ThreadLocal<Int>()
    private val resolutionDepth = ThreadLocal<Int>()
    private var current = false
    private var closing = false
    private var closed = false
    private var enteredResolves = 0
    private var enteredPublications = 0
    private var closeOwnerThread: Thread? = null
    private var closeFailure: Throwable? = null

    init {
        lock.withLock {
            current = true
            gatedRegistry.activate()
        }
    }

    override fun isCurrent(): Boolean = lock.withLock {
        current && !closing && !closed
    }

    override suspend fun resolveIfCurrent(request: ResolverRequest): ResolverResult = resolveOwned(request, false)

    override suspend fun resolvePrimaryIfCurrent(request: ResolverRequest): ResolverResult = resolveOwned(request, true)

    private suspend fun resolveOwned(request: ResolverRequest, primaryOnly: Boolean): ResolverResult {
        val admitted = lock.withLock {
            if (
                !current ||
                closing ||
                closed ||
                request.epoch !== epoch ||
                request.networkContext !== networkContext
            ) {
                false
            } else {
                enteredResolves += 1
                true
            }
        }
        if (!admitted) return staleEpoch()

        return try {
            withContext(
                resolutionDepth.asContextElement(resolutionDepth.getOrZero() + 1),
            ) {
                if (primaryOnly) resolver.resolvePrimary(request) else resolver.resolve(request)
            }
        } finally {
            lock.withLock {
                check(enteredResolves > 0) { "resolve admission released more than once" }
                enteredResolves -= 1
                if (enteredResolves == 0) quiescent.signalAll()
            }
        }
    }

    override fun completeIfCurrent(completion: () -> Boolean): Boolean {
        val admitted = lock.withLock {
            if (!current || closing || closed) {
                false
            } else {
                enteredPublications += 1
                true
            }
        }
        if (!admitted) return false

        val priorPublicationDepth = publicationDepth.getOrZero()
        publicationDepth.set(priorPublicationDepth + 1)
        return try {
            completion()
        } finally {
            if (priorPublicationDepth == 0) {
                publicationDepth.remove()
            } else {
                publicationDepth.set(priorPublicationDepth)
            }
            lock.withLock {
                check(enteredPublications > 0) {
                    "publication admission released more than once"
                }
                enteredPublications -= 1
                if (enteredPublications == 0) quiescent.signalAll()
            }
        }
    }

    override fun close() {
        check(publicationDepth.getOrZero() == 0) {
            "engine close cannot re-enter an admitted publication callback"
        }
        check(resolutionDepth.getOrZero() == 0) {
            "engine close cannot re-enter an admitted resolver call"
        }
        check(!closeReentryGuard.isClosingResource()) {
            "engine close cannot re-enter a resolver-resource close"
        }
        policy.checkRetirementAllowed()

        var ownsClose = false
        var observedFailure: Throwable? = null
        lock.withLock {
            when {
                closed -> observedFailure = closeFailure
                closing -> {
                    check(closeOwnerThread !== Thread.currentThread()) {
                        "engine close cannot re-enter its physical drain"
                    }
                    while (!closed) closeCompleted.awaitUninterruptibly()
                    observedFailure = closeFailure
                }
                else -> {
                    policy.beginRetirement()
                    closing = true
                    current = false
                    closeOwnerThread = Thread.currentThread()
                    gatedRegistry.deactivate()
                    ownsClose = true
                }
            }
        }
        if (!ownsClose) {
            observedFailure?.let { throw it }
            return
        }

        var failure: Throwable? = null
        try {
            try {
                registry.close()
            } catch (thrown: Throwable) {
                failure = thrown
            }

            lock.withLock {
                while (enteredResolves > 0 || enteredPublications > 0) {
                    quiescent.awaitUninterruptibly()
                }
            }

            try {
                resolver.close()
            } catch (thrown: Throwable) {
                val prior = failure
                if (prior == null) {
                    failure = thrown
                } else if (prior !== thrown) {
                    prior.addSuppressed(thrown)
                }
            }
            try {
                policy.close()
            } catch (thrown: Throwable) {
                val prior = failure
                if (prior == null) {
                    failure = thrown
                } else if (prior !== thrown) {
                    prior.addSuppressed(thrown)
                }
            }
        } finally {
            lock.withLock {
                closeFailure = failure
                closed = true
                closing = false
                closeOwnerThread = null
                closeCompleted.signalAll()
            }
        }
        failure?.let { throw it }
    }

    private fun staleEpoch(): ResolverResult.Failure =
        ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)

    private fun ThreadLocal<Int>.getOrZero(): Int = get() ?: 0

    companion object {
        /**
         * Returns one already-current engine owned by the caller.
         *
         * The caller must close it unless and until a runtime successfully accepts ownership.
         */
        fun create(
            candidate: ReadyDnsEngineCandidate,
            resolverFactory: ReadyDnsResolverFactory,
        ): ReadyDnsEngineOwner {
            val policy = candidate.takePolicy()
            var registry: GenerationSocketRegistry? = null
            var resolver: OwnedReadyDnsResolver? = null
            try {
                validate(candidate, policy)

                val closeReentryGuard = EngineCloseReentryGuard()
                registry = GenerationSocketRegistry.withCloseScope(
                    initialEpoch = candidate.epoch,
                    resourceCloseScope = closeReentryGuard,
                )
                val gatedRegistry = ColdResolverResourceRegistry(
                    epoch = candidate.epoch,
                    delegate = registry,
                    closeReentryGuard = closeReentryGuard,
                )
                resolver = resolverFactory.create(
                    epoch = candidate.epoch,
                    networkContext = candidate.networkContext,
                    resolverPlan = candidate.resolverPlan,
                    registry = gatedRegistry,
                )
                return ReadyDnsEngineOwner(
                    candidate = candidate,
                    registry = registry,
                    gatedRegistry = gatedRegistry,
                    resolver = resolver,
                    policy = policy,
                    closeReentryGuard = closeReentryGuard,
                )
            } catch (failure: Throwable) {
                registry?.let { closeAfterConstructionFailure(it, failure) }
                resolver?.let { closeAfterConstructionFailure(it, failure) }
                closeAfterConstructionFailure(policy, failure)
                throw failure
            }
        }

        private fun validate(
            candidate: ReadyDnsEngineCandidate,
            policyRuntime: OwnedDnsPolicyRuntime,
        ) {
            val epoch = candidate.epoch
            val context = candidate.networkContext
            val plan = candidate.resolverPlan
            val policy = candidate.policyIdentity

            require(policyRuntime.identity === policy) {
                "candidate policy identity must remain bound to its owned runtime"
            }
            require(policyRuntime.proxy === candidate.proxy) {
                "candidate proxy must remain bound to its owned policy runtime"
            }
            require(epoch.sessionGeneration > 0L) {
                "engine protection session generation must be positive"
            }
            require(policy.configurationIdentity == epoch.configurationIdentity) {
                "policy configuration identity must match the engine epoch"
            }
            require(policy.artifactDigest == epoch.artifactDigest) {
                "policy artifact digest must match the engine epoch"
            }
            require(policy.revision == epoch.policyRevision) {
                "policy revision must match the engine epoch"
            }
            require(context.networkGeneration == epoch.networkGeneration) {
                "network context generation must match the engine epoch"
            }
            require(plan.network === context.network) {
                "resolver plan must retain the exact selected Network"
            }
            require(plan.networkGeneration == context.networkGeneration) {
                "resolver plan generation must match the network context"
            }
            require(plan.isCellular == context.isCellular) {
                "resolver plan cellular identity must match the network context"
            }
            require(plan.primaryResolverIdentity == epoch.primaryResolverIdentity) {
                "resolver primary identity must match the engine epoch"
            }
            require(plan.fallbackCoverage == epoch.fallbackCoverage) {
                "resolver fallback coverage must match the engine epoch"
            }
            require(plan.isCanonicalReadyRoute()) {
                "resolver plan must retain one canonical ready route shape"
            }

            val selectedDeviceServers = if (plan.deviceDnsRequested) {
                DeviceDnsAddressPolicy.select(context.dnsServers, context.isCellular)
            } else {
                emptyList()
            }
            require(plan.deviceDnsServers == selectedDeviceServers) {
                "resolver plan Device DNS addresses must match the network context"
            }
        }
    }
}

private fun closeAfterConstructionFailure(
    closeable: AutoCloseable,
    primary: Throwable,
) {
    try {
        closeable.close()
    } catch (closeFailure: Throwable) {
        if (primary !== closeFailure) primary.addSuppressed(closeFailure)
    }
}

/**
 * Keeps a fresh epoch registry cold until its complete engine is ready.
 *
 * Registration rejection consumes the resource exactly like the underlying registry.
 */
private class ColdResolverResourceRegistry(
    private val epoch: EngineEpoch,
    private val delegate: GenerationSocketRegistry,
    private val closeReentryGuard: EngineCloseReentryGuard,
) : ResolverResourceRegistry {
    private val state = AtomicReference(State.COLD)

    fun activate() {
        check(state.compareAndSet(State.COLD, State.ACTIVE)) {
            "resolver resource registry activated more than once"
        }
    }

    fun deactivate() {
        check(state.compareAndSet(State.ACTIVE, State.RETIRED)) {
            "resolver resource registry retired outside its active lifetime"
        }
    }

    override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean {
        if (state.get() != State.ACTIVE || epoch !== this.epoch) {
            closeQuietly(resource)
            return false
        }
        return delegate.registerIfCurrent(resource, epoch)
    }

    override fun isCurrent(epoch: EngineEpoch): Boolean =
        state.get() == State.ACTIVE && epoch === this.epoch && delegate.isCurrent(epoch)

    override fun unregister(resource: AutoCloseable): Boolean =
        delegate.unregister(resource)

    override fun completeIfCurrent(
        resource: AutoCloseable,
        epoch: EngineEpoch,
        completion: () -> Boolean,
    ): Boolean {
        if (state.get() != State.ACTIVE || epoch !== this.epoch) {
            delegate.completeIfCurrent(resource, this.epoch) { false }
            return false
        }
        return delegate.completeIfCurrent(resource, epoch, completion)
    }

    private fun closeQuietly(resource: AutoCloseable) {
        try {
            closeReentryGuard.run { resource.close() }
        } catch (_: Throwable) {
            // Rejected ownership is consumed even when physical close reports failure.
        }
    }

    private enum class State {
        COLD,
        ACTIVE,
        RETIRED,
    }
}

private class EngineCloseReentryGuard : ResolverResourceCloseScope {
    private val resourceCloseDepth = ThreadLocal.withInitial { 0 }

    override fun run(close: () -> Unit) {
        val previousDepth = resourceCloseDepth.get() ?: 0
        resourceCloseDepth.set(previousDepth + 1)
        try {
            close()
        } finally {
            if (previousDepth == 0) {
                resourceCloseDepth.remove()
            } else {
                resourceCloseDepth.set(previousDepth)
            }
        }
    }

    fun isClosingResource(): Boolean = (resourceCloseDepth.get() ?: 0) > 0
}
