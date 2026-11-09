package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.DnsMessageException
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.vpn.chained.ChainedDnsFallback
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Cold saved-DNS composition for one consented split-chain activation. The supplied epoch retains
 * the exact filter/configuration/network/session generations; its resolver fields name the saved
 * plan rather than T0. The chained owner separately fences policy admission and TUN publication.
 * No resolver settings, network, or credentials are reloaded while a lookup is running.
 */
internal class SavedDnsChainedFallback(
    private val epoch: EngineEpoch,
    private val networkContext: NetworkContext,
    private val resolverPlan: ResolverPlan,
    resolverFactory: ReadyDnsResolverFactory,
    private val isCurrent: () -> Boolean = { true },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChainedDnsFallback {
    init {
        require(resolverPlan.isCanonicalReadyRoute())
        require(resolverPlan.network === networkContext.network)
        require(resolverPlan.networkGeneration == networkContext.networkGeneration &&
            networkContext.networkGeneration == epoch.networkGeneration)
        require(resolverPlan.isCellular == networkContext.isCellular)
        require(resolverPlan.primaryResolverIdentity == epoch.primaryResolverIdentity &&
            resolverPlan.fallbackCoverage == epoch.fallbackCoverage)
    }

    private val lock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private val queries = mutableSetOf<QueryHandle>()
    private val queryIds = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val registry = GenerationSocketRegistry(epoch)
    private val resolver = try {
        resolverFactory.create(epoch, networkContext, resolverPlan, registry)
    } catch (failure: Throwable) {
        registry.close()
        scope.cancel()
        throw failure
    }

    override val context = DnsFallbackQueryContext(
        isSatisfiedWifi = networkContext.isWifi,
        hasEnabledDeviceTier = resolverPlan.hasDeviceRoute,
        hasCurrentDeviceCapture = networkContext.dnsServers.isNotEmpty(),
        permitsPhysicalDeviceDns = true,
        portalNetworkIsCurrent = { current() && networkContext.portalNetworkIsCurrent() },
    )

    override fun resolve(query: ByteArray, completion: (ResolverResult) -> Unit): AutoCloseable =
        resolve(query, ResolverExecutionBudget.WHOLE_QUERY_NANOS, emptySet(), false, completion)

    override fun resolve(query: ByteArray, remainingNanos: Long, portalHosts: Set<String>, requiresPortalAdmission: Boolean, completion: (ResolverResult) -> Unit): AutoCloseable {
        val deadline = SystemMonotonicClock.nowNanos() + remainingNanos.coerceAtLeast(0L)
        val capturedQuery = query.copyOf()
        val handle = QueryHandle()
        val admitted = lock.withLock {
            if (closed.get()) false else { queries += handle; true }
        }
        if (!admitted) {
            completion(ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH))
            return handle
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = when {
                    !current() -> ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
                    else -> resolveCaptured(capturedQuery, deadline, portalHosts, requiresPortalAdmission)
                }
                currentCoroutineContext().ensureActive()
                if (!closed.get() && !handle.cancelled.get()) {
                    // The real coordinator consumed a registry-owned query lease before this
                    // callback. A chain replacement that won after resolution still refuses it.
                    completion(if (current()) result else ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH))
                }
            } finally { lock.withLock { queries -= handle } }
        }
        handle.job.set(job)
        if (handle.cancelled.get() || closed.get()) job.cancel() else job.start()
        return handle
    }

    private suspend fun resolveCaptured(query: ByteArray, deadline: Long, portalHosts: Set<String>, requiresPortalAdmission: Boolean): ResolverResult {
        val request = try {
            ResolverRequest(query, DnsMessage.parseQuestion(query), epoch, networkContext,
                queryId = queryIds.incrementAndGet(),
                wholeQueryDeadlineNanos = deadline, fallbackContext = context.copy(permittedPortalHosts = portalHosts, requiresPortalAdmission = requiresPortalAdmission))
        } catch (_: DnsMessageException) {
            return ResolverResult.Failure(ResolverFailureReason.INVALID_RESPONSE)
        }
        val result = try {
            withTimeout(((deadline - SystemMonotonicClock.nowNanos()) / 1_000_000L).coerceAtLeast(1L)) { resolver.resolve(request) }
        } catch (_: TimeoutCancellationException) {
            return ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            return ResolverResult.Failure(ResolverFailureReason.CANCELLED)
        } catch (_: Exception) {
            return ResolverResult.Failure(ResolverFailureReason.SOCKET_FAILED)
        }
        if (!current()) return ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
        if (result !is ResolverResult.Answer) return result
        val kind = kindFor(result.transport) ?: return invalid()
        if (!planContains(kind)) return invalid()
        // Physical UDP tuple validation belongs to DeviceDnsTransport. Recheck only the wire
        // identity here, without fabricating another physical endpoint observation. DoH alone
        // permits a canonicalized transaction ID; the reply boundary restores the client ID.
        val wireTransport = when (result.transport) {
            ResolverTransport.DEVICE_UDP, ResolverTransport.PLAIN_UDP -> ResolverTransport.WIREGUARD_UDP
            else -> result.transport
        }
        val validation = ResponseValidation.validate(request.query, result.dnsMessage, wireTransport)
        if (validation !is ResponseValidationResult.Valid || validation.truncated) return invalid()
        val restored = result.dnsMessage.also { response ->
            response[0] = request.query[0]; response[1] = request.query[1]
        }
        // T0 still needs the complete terminal address and policy facts for portal selection.
        // Its client delivery boundary applies the local TUN size limit after that selection.
        return ResolverReplyBoundary.validate(request, kind,
            ResolverResult.Answer(restored, result.transport, result.healthEvents, result.resolverQueryEvidence, result.httpProtocol))
    }

    private fun current(): Boolean = !closed.get() && registry.isCurrent(epoch) && isCurrent()

    private fun planContains(kind: ResolverKind): Boolean = resolverPlan.orderedRoutes?.any {
        it.primaryResolverKind == kind
    } ?: when (kind) {
        ResolverKind.DEVICE -> resolverPlan.hasDeviceRoute
        ResolverKind.MULLVAD_DOH -> resolverPlan.mullvadEnabled
        ResolverKind.PLAIN_DNS -> resolverPlan.primaryResolverKind == ResolverKind.PLAIN_DNS
        ResolverKind.WIREGUARD_T0 -> false
    }

    override fun close() {
        val pending = lock.withLock {
            if (!closed.compareAndSet(false, true)) return
            queries.toList().also { queries.clear() }
        }
        pending.forEach(QueryHandle::close)
        scope.cancel()
        // Revoke and drain admitted calls/sockets before retiring their long-lived runtime.
        try { registry.close() } finally { resolver.close() }
    }

    private inner class QueryHandle : AutoCloseable {
        val cancelled = AtomicBoolean(false)
        val job = AtomicReference<Job?>()
        override fun close() {
            if (!cancelled.compareAndSet(false, true)) return
            job.get()?.cancel()
            lock.withLock { queries -= this }
        }
    }

    private fun invalid() = ResolverResult.Failure(ResolverFailureReason.INVALID_RESPONSE)

    private fun kindFor(transport: ResolverTransport): ResolverKind? = when (transport) {
        ResolverTransport.DEVICE_UDP, ResolverTransport.DEVICE_TCP -> ResolverKind.DEVICE
        ResolverTransport.PLAIN_UDP, ResolverTransport.PLAIN_TCP -> ResolverKind.PLAIN_DNS
        ResolverTransport.MULLVAD_DOH, ResolverTransport.DOT, ResolverTransport.DOQ -> ResolverKind.MULLVAD_DOH
        ResolverTransport.WIREGUARD_UDP -> null
    }

    private companion object { const val QUERY_BUDGET_MILLIS = 12_000L }
}
