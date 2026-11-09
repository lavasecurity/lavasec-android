package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Internal construction seam for the Device route declared by one exact resolver plan.
 *
 * Production creates [DeviceDnsTransport]. Tests replace this seam without opening sockets.
 */
internal fun interface ReadyDnsDeviceRouteFactory {
    fun create(
        epoch: EngineEpoch,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
        registry: ResolverResourceRegistry,
    ): DeviceDnsRoute
}

/** Closeable Mullvad route runtime whose long-lived resources belong to one engine. */
internal interface ReadyDnsMullvadRuntime : AutoCloseable {
    val resolver: DnsResolver
}

/** Lookup over cold encrypted routes owned by the same engine, keyed by exact endpoint identity. */
internal interface OrderedEncryptedDnsResolver : DnsResolver {
    fun resolverFor(identity: PrimaryResolverIdentity): DnsResolver
}

/**
 * Internal construction seam for the Mullvad runtime declared by one exact resolver plan.
 *
 * A throwing implementation must close any partially created runtime that it has not returned.
 */
internal fun interface ReadyDnsMullvadRuntimeFactory {
    fun create(
        epoch: EngineEpoch,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
        registry: ResolverResourceRegistry,
    ): ReadyDnsMullvadRuntime
}

/** Internal construction seam for the coordinator over the exact selected routes. */
internal fun interface ReadyDnsCoordinatorFactory {
    fun create(
        epoch: EngineEpoch,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
        registry: ResolverResourceRegistry,
        deviceRoute: DeviceDnsRoute?,
        mullvadRoute: DnsResolver?,
        wedgeEvidence: DeviceResolverWedgeEvidence,
    ): DnsResolver
}

/**
 * Cold production composition for one already-selected resolver generation.
 *
 * Construction retains the exact supplied identities and starts no query. The caller retains
 * ownership of [ResolverResourceRegistry]; the returned bundle owns only its Mullvad runtime.
 */
internal class ProductionReadyDnsResolverFactory internal constructor(
    private val deviceRouteFactory: ReadyDnsDeviceRouteFactory,
    private val mullvadRuntimeFactory: ReadyDnsMullvadRuntimeFactory,
    private val coordinatorFactory: ReadyDnsCoordinatorFactory,
    private val wedgeEvidence: DeviceResolverWedgeEvidence,
    private val fixedPlainRuntimeFactory: ReadyDnsMullvadRuntimeFactory? = null,
) : ReadyDnsResolverFactory {
    constructor(
        deviceSocketPlatform: DeviceDnsSocketPlatform,
        dohSocketProtector: DohSocketProtector,
        dohSocketBinder: DohSocketBinder,
        wedgeEvidence: DeviceResolverWedgeEvidence,
        clock: MonotonicClock = SystemMonotonicClock,
    ) : this(
        deviceRouteFactory = ReadyDnsDeviceRouteFactory { _, _, _, registry ->
            DeviceDnsTransport(
                platform = deviceSocketPlatform,
                registry = registry,
                clock = clock,
            )
        },
        mullvadRuntimeFactory = ReadyDnsMullvadRuntimeFactory {
                epoch,
                networkContext,
                resolverPlan,
                registry,
            ->
            fun dotRuntime(endpoint: CustomDotEndpoint, bootstrap: List<java.net.InetAddress>): ReadyDnsMullvadRuntime = CustomDotRuntime(
                epoch = epoch,
                networkContext = networkContext,
                registry = registry,
                protector = dohSocketProtector,
                binder = dohSocketBinder,
                endpoint = endpoint,
                bootstrapAddresses = bootstrap,
                clock = clock,
            )
            fun doqRuntime(endpoint: CustomDoqEndpoint, bootstrap: List<java.net.InetAddress>): ReadyDnsMullvadRuntime = CustomDoqRuntime(
                epoch, networkContext, registry, deviceSocketPlatform, endpoint, clock = clock,
                bootstrapAddresses = bootstrap,
            )
            fun dohRuntime(endpoint: String?, bootstrap: List<java.net.InetAddress>): ReadyDnsMullvadRuntime = DefaultReadyDnsMullvadRuntime(
                MullvadDohRuntime(
                    epoch = epoch,
                    networkContext = networkContext,
                    registry = registry,
                    protector = dohSocketProtector,
                    binder = dohSocketBinder,
                    endpoint = endpoint?.toHttpUrl() ?: MullvadDohEndpoint.url,
                    clock = clock,
                    dns = when {
                        bootstrap.isNotEmpty() -> CatalogDohBootstrap(checkNotNull(endpoint).toHttpUrl().host, bootstrap)
                        endpoint != null -> NetworkDohBootstrap(networkContext.network)
                        else -> MullvadDohEndpoint
                    },
                ),
            )
            buildReadyEncryptedRuntime(resolverPlan, ::dohRuntime, ::dotRuntime, ::doqRuntime)
        },
        coordinatorFactory = ReadyDnsCoordinatorFactory {
                epoch,
                _,
                resolverPlan,
                registry,
                deviceRoute,
                mullvadRoute,
                suppliedWedgeEvidence,
            ->
            ResolverCoordinator(
                epoch = epoch,
                plan = resolverPlan,
                registry = registry,
                deviceRoute = deviceRoute,
                mullvadRoute = mullvadRoute,
                wedgeEvidence = suppliedWedgeEvidence,
                clock = clock,
            )
        },
        fixedPlainRuntimeFactory = ReadyDnsMullvadRuntimeFactory { _, _, plan, registry ->
            object : ReadyDnsMullvadRuntime {
                override val resolver = DeviceDnsTransport(platform = deviceSocketPlatform, registry = registry,
                    clock = clock, fixedDnsServers = plan.fixedDnsServers, fixedResolverIdentity = plan.primaryResolverIdentity)
                override fun close() = Unit // Query-owned sockets are drained by the epoch registry.
            }
        },
        wedgeEvidence = wedgeEvidence,
    )

    override fun create(
        epoch: EngineEpoch,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
        registry: ResolverResourceRegistry,
    ): OwnedReadyDnsResolver {
        validateResolverInputs(epoch, networkContext, resolverPlan)

        val deviceRoute = if (resolverPlan.hasDeviceRoute) {
            deviceRouteFactory.create(
                epoch = epoch,
                networkContext = networkContext,
                resolverPlan = resolverPlan,
                registry = registry,
            )
        } else {
            null
        }

        var mullvadRuntime: ReadyDnsMullvadRuntime? = null
        return try {
            val mullvadRoute = if (resolverPlan.hasNonDeviceRoutes) {
                createEncryptedRuntime(
                    epoch = epoch,
                    networkContext = networkContext,
                    resolverPlan = resolverPlan,
                    registry = registry,
                ).also { mullvadRuntime = it }.resolver
            } else {
                null
            }
            val coordinator = coordinatorFactory.create(
                epoch = epoch,
                networkContext = networkContext,
                resolverPlan = resolverPlan,
                registry = registry,
                deviceRoute = deviceRoute,
                mullvadRoute = mullvadRoute,
                wedgeEvidence = wedgeEvidence,
            )
            ComposedReadyDnsResolver(
                coordinator = coordinator,
                mullvadRuntime = mullvadRuntime,
            )
        } catch (failure: Throwable) {
            try {
                mullvadRuntime?.close()
            } catch (closeFailure: Throwable) {
                if (failure !== closeFailure) failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private fun createEncryptedRuntime(
        epoch: EngineEpoch,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
        registry: ResolverResourceRegistry,
    ): ReadyDnsMullvadRuntime {
        val leaves = resolverPlan.orderedRoutes?.filter { it.primaryResolverKind != ResolverKind.DEVICE }
            ?: return if (resolverPlan.primaryResolverKind == ResolverKind.PLAIN_DNS) {
                checkNotNull(fixedPlainRuntimeFactory).create(epoch, networkContext, resolverPlan, registry)
            } else mullvadRuntimeFactory.create(epoch, networkContext, resolverPlan, registry)
        val created = mutableListOf<Pair<PrimaryResolverIdentity, ReadyDnsMullvadRuntime>>()
        try {
            for (leaf in leaves) {
                created += leaf.primaryResolverIdentity to
                    if (leaf.primaryResolverKind == ResolverKind.PLAIN_DNS) {
                        checkNotNull(fixedPlainRuntimeFactory).create(epoch, networkContext, leaf, registry)
                    } else mullvadRuntimeFactory.create(epoch, networkContext, leaf, registry)
            }
            return OrderedEncryptedRuntime(created)
        } catch (failure: Throwable) {
            for ((_, runtime) in created.asReversed()) {
                try { runtime.close() } catch (closeFailure: Throwable) {
                    if (failure !== closeFailure) failure.addSuppressed(closeFailure)
                }
            }
            throw failure
        }
    }

    private fun validateResolverInputs(
        epoch: EngineEpoch,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
    ) {
        require(resolverPlan.isCanonicalReadyRoute()) {
            "resolver plan must retain one canonical ready route shape"
        }
        require(resolverPlan.network === networkContext.network) {
            "resolver plan must retain the exact selected Network"
        }
        require(resolverPlan.networkGeneration == networkContext.networkGeneration) {
            "resolver plan generation must match the network context"
        }
        require(networkContext.networkGeneration == epoch.networkGeneration) {
            "network context generation must match the engine epoch"
        }
        require(resolverPlan.isCellular == networkContext.isCellular) {
            "resolver plan cellular identity must match the network context"
        }
        require(resolverPlan.primaryResolverIdentity == epoch.primaryResolverIdentity) {
            "resolver primary identity must match the engine epoch"
        }
        require(resolverPlan.fallbackCoverage == epoch.fallbackCoverage) {
            "resolver fallback coverage must match the engine epoch"
        }
        val selectedDeviceServers = if (resolverPlan.deviceDnsRequested) {
            DeviceDnsAddressPolicy.select(
                networkContext.dnsServers,
                networkContext.isCellular,
            )
        } else {
            emptyList()
        }
        require(resolverPlan.deviceDnsServers == selectedDeviceServers) {
            "resolver plan Device DNS addresses must match the network context"
        }
    }
}

private class OrderedEncryptedRuntime(
    private val runtimes: List<Pair<PrimaryResolverIdentity, ReadyDnsMullvadRuntime>>,
) : ReadyDnsMullvadRuntime {
    override val resolver: OrderedEncryptedDnsResolver = object : OrderedEncryptedDnsResolver {
        private val routes = runtimes.associate { (identity, runtime) -> identity to runtime.resolver }
        override fun resolverFor(identity: PrimaryResolverIdentity): DnsResolver = checkNotNull(routes[identity])
        override suspend fun resolve(request: ResolverRequest): ResolverResult =
            error("ordered resolution requires an exact route identity")
    }
    override fun close() {
        var failure: Throwable? = null
        for ((_, runtime) in runtimes.asReversed()) {
            try { runtime.close() } catch (thrown: Throwable) {
                if (failure == null) failure = thrown else if (failure !== thrown) failure.addSuppressed(thrown)
            }
        }
        failure?.let { throw it }
    }
}

/** Production selection boundary; primary and secondary never share stamp bootstrap metadata. */
internal fun buildReadyEncryptedRuntime(
    plan: ResolverPlan,
    doh: (String?, List<java.net.InetAddress>) -> ReadyDnsMullvadRuntime,
    dot: (CustomDotEndpoint, List<java.net.InetAddress>) -> ReadyDnsMullvadRuntime,
    doq: (CustomDoqEndpoint, List<java.net.InetAddress>) -> ReadyDnsMullvadRuntime,
): ReadyDnsMullvadRuntime {
    val primary = when {
        plan.doqEndpoint != null -> doq(plan.doqEndpoint, plan.encryptedBootstrapAddresses)
        plan.dotEndpoint != null -> dot(plan.dotEndpoint, plan.encryptedBootstrapAddresses)
        else -> doh(plan.dohEndpointUrl, plan.encryptedBootstrapAddresses)
    }
    val secondary = try {
        when {
            plan.doqSecondaryEndpoint != null -> doq(plan.doqSecondaryEndpoint, plan.encryptedSecondaryBootstrapAddresses)
            plan.dotSecondaryEndpoint != null -> dot(plan.dotSecondaryEndpoint, plan.encryptedSecondaryBootstrapAddresses)
            plan.dohSecondaryEndpointUrl != null -> doh(plan.dohSecondaryEndpointUrl, plan.encryptedSecondaryBootstrapAddresses)
            else -> return primary
        }
    } catch (failure: Throwable) {
        try { primary.close() } catch (closeFailure: Throwable) {
            if (failure !== closeFailure) failure.addSuppressed(closeFailure)
        }
        throw failure
    }
    return FallbackReadyDnsMullvadRuntime(primary, secondary)
}

private class DefaultReadyDnsMullvadRuntime(
    private val delegate: MullvadDohRuntime,
) : ReadyDnsMullvadRuntime {
    override val resolver: DnsResolver
        get() = delegate.resolver

    override fun close() {
        delegate.close()
    }
}

/**
 * One custom-encrypted runtime with an optional same-transport secondary endpoint.
 *
 * The primary is tried first; a non-terminal failure falls back to the secondary. Both runtimes
 * belong to the same engine epoch and are closed together.
 */
private class FallbackReadyDnsMullvadRuntime(
    private val primary: ReadyDnsMullvadRuntime,
    private val secondary: ReadyDnsMullvadRuntime,
) : ReadyDnsMullvadRuntime {
    override val resolver: DnsResolver = SecondaryEndpointFallbackResolver(primary.resolver, secondary.resolver)

    override fun close() {
        var failure: Throwable? = null
        try {
            primary.close()
        } catch (thrown: Throwable) {
            failure = thrown
        }
        try {
            secondary.close()
        } catch (thrown: Throwable) {
            if (failure == null) failure = thrown else failure.addSuppressed(thrown)
        }
        failure?.let { throw it }
    }
}

/**
 * Tries [primary] and falls back to [secondary] on any failure except [STALE_EPOCH], which is
 * terminal for the whole engine epoch and must not be retried against the sibling endpoint.
 */
internal class SecondaryEndpointFallbackResolver(
    private val primary: DnsResolver,
    private val secondary: DnsResolver,
) : DnsResolver {
    override suspend fun resolve(request: ResolverRequest): ResolverResult {
        val result = primary.resolve(request)
        if (result is ResolverResult.Answer) return result
        if (result is ResolverResult.Failure && (result.terminal || result.reason in setOf(ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.CANCELLED, ResolverFailureReason.OVERLOADED, ResolverFailureReason.MISMATCH_LIMIT))) {
            return result
        }
        return secondary.resolve(request)
    }
}

/**
 * One coordinator and its optional long-lived Mullvad runtime.
 *
 * The owning engine closes its query registry before closing this bundle. Repeated and concurrent
 * callers wait for and observe the same physical close outcome.
 */
private class ComposedReadyDnsResolver(
    private val coordinator: DnsResolver,
    private val mullvadRuntime: ReadyDnsMullvadRuntime?,
) : OwnedReadyDnsResolver {
    private val lock = ReentrantLock()
    private val closeCompleted = lock.newCondition()
    private var closing = false
    private var closed = false
    private var closeOwnerThread: Thread? = null
    private var closeFailure: Throwable? = null

    override suspend fun resolve(request: ResolverRequest): ResolverResult =
        coordinator.resolve(request)

    override suspend fun resolvePrimary(request: ResolverRequest): ResolverResult =
        (coordinator as? PrimaryDnsResolver)?.resolvePrimary(request)
            ?: ResolverResult.Failure(ResolverFailureReason.OVERLOADED)

    override fun close() {
        var ownsClose = false
        var observedFailure: Throwable? = null
        lock.withLock {
            when {
                closed -> observedFailure = closeFailure
                closing -> {
                    check(closeOwnerThread !== Thread.currentThread()) {
                        "resolver bundle close cannot re-enter its physical drain"
                    }
                    while (!closed) closeCompleted.awaitUninterruptibly()
                    observedFailure = closeFailure
                }
                else -> {
                    closing = true
                    closeOwnerThread = Thread.currentThread()
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
            mullvadRuntime?.close()
        } catch (thrown: Throwable) {
            failure = thrown
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
}
