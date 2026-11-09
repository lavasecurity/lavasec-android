package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.DnsQuestion
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.filter.DomainFilterDecisionSource
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.platform.ConfigurationIdentity
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable upstream network facts captured for one selected-network generation. */
class NetworkContext(
    val network: Network,
    val networkGeneration: Long,
    dnsServers: List<InetAddress>,
    val isCellular: Boolean,
    val isWifi: Boolean = false,
    internal val portalNetworkIsCurrent: () -> Boolean = { true },
) {
    val dnsServers: List<InetAddress> = Collections.unmodifiableList(dnsServers.toList())
}

/**
 * Calls [completion] synchronously on the invoking thread at most once and never retains it.
 *
 * A current fence returns exactly the callback result. A retired or rejecting fence does not call
 * it. The fence never calls the callback and then returns false or throws.
 */
internal fun interface ProxyPublicationFence {
    fun completeIfCurrent(completion: () -> Boolean): Boolean
}

internal interface ReadyDnsEngine : AutoCloseable, ProxyPublicationFence {
    val epoch: EngineEpoch
    val networkContext: NetworkContext
    val resolverPlan: ResolverPlan
    val policyIdentity: ProxyPolicyIdentity
    val proxy: DnsProxy

    fun isCurrent(): Boolean

    /**
     * Atomically admits only current work before touching the close-owned resolver.
     *
     * The request must retain the exact engine epoch and network-context objects. Rejection
     * returns typed STALE_EPOCH. Admitted work remains registry-safe if retirement begins
     * concurrently.
     */
    suspend fun resolveIfCurrent(request: ResolverRequest): ResolverResult

    /** Same exact ownership fence, without ladder rescue hiding a primary health probe. */
    suspend fun resolvePrimaryIfCurrent(request: ResolverRequest): ResolverResult =
        ResolverResult.Failure(ResolverFailureReason.OVERLOADED)

    /**
     * Retires this engine before draining it.
     *
     * Retirement makes [isCurrent] false before drain, rejects later resolution admission and
     * publication, drains registered query work, waits for entered resolution and publication,
     * and then closes the owned resolver bundle. Repeated and concurrent callers wait for the
     * same completed outcome and have no additional effect.
     *
     * A trusted publication callback may read [isCurrent]. Resolver implementations,
     * publication callbacks, and registry-owned resource close callbacks must not invoke
     * [close]; lifecycle re-entry fails fast instead of waiting on its own admission or drain.
     */
    override fun close()
}

internal data class ProxyPolicyIdentity(
    val configurationIdentity: ConfigurationIdentity,
    val artifactDigest: String,
    val revision: Long,
    val sourceIncarnation: Long,
) {
    init {
        require(artifactDigest.isNotBlank()) { "policy artifact digest must not be blank" }
        require(revision >= 0L) { "policy revision must be non-negative" }
        require(sourceIncarnation > 0L) { "policy source incarnation must be positive" }
    }
}

internal class ProxyPolicySnapshot(
    val identity: ProxyPolicyIdentity,
    val activeProtectionSessionId: Long,
    val pause: RuntimePauseDirective,
    val decisions: DomainFilterDecisionSource,
) {
    init {
        require(activeProtectionSessionId > 0L) {
            "active protection session id must be positive"
        }
    }
}

internal sealed interface ProxyPolicyRead<out T> {
    data class Available<T>(val value: T) : ProxyPolicyRead<T>

    data object Unavailable : ProxyPolicyRead<Nothing>
}

internal interface ProxyPolicySource {
    fun <T> withCurrent(block: (ProxyPolicySnapshot) -> T): ProxyPolicyRead<T>
}

/** One coherent close-owned policy source and the exact proxy bound to it. */
internal interface OwnedDnsPolicyRuntime : AutoCloseable {
    val identity: ProxyPolicyIdentity
    val proxy: DnsProxy

    /** Fails before engine state changes when close would re-enter an admitted policy read. */
    fun checkRetirementAllowed()

    /** Nonblocking, idempotent rejection of future policy reads. */
    fun beginRetirement()
}

internal fun interface DecisionEventIdSource {
    fun nextId(): Long
}

internal fun interface EpochMillisClock {
    fun nowMillis(): Long
}

internal class ProxyPublication(
    responsePacket: ByteArray,
    val activity: DnsDecisionEvent?,
    healthEvents: List<DnsHealthEvent>,
    val resolverQueryEvidence: ResolverQueryHealthEvidence?,
    /** Strict answer fact; becomes organic health evidence only after the actual TUN write. */
    val primaryAnswerBearing: Boolean = false,
) {
    init {
        require(responsePacket.isNotEmpty()) { "publication packet must not be empty" }
        require(responsePacket.size <= DnsOnlyTunContract.MTU) {
            "publication packet exceeds the TUN MTU"
        }
    }

    private val wireResponse = responsePacket.copyOf()
    private val terminalHealthEvents =
        Collections.unmodifiableList(healthEvents.toList())

    internal val responsePacket: ByteArray
        get() = wireResponse.copyOf()

    internal val healthEvents: List<DnsHealthEvent>
        get() = terminalHealthEvents
}

internal fun interface ProxyPublicationSink {
    fun publish(publication: ProxyPublication): Boolean
}

internal sealed interface ProxyResult {
    data object NotPublishable : ProxyResult

    class Pending internal constructor(
        private val fence: ProxyPublicationFence,
        private val publication: ProxyPublication,
        private val sink: ProxyPublicationSink,
    ) : ProxyResult {
        private val consumed = AtomicBoolean(false)

        fun publishIfCurrent(): Boolean {
            if (!consumed.compareAndSet(false, true)) return false

            val invokingThread = Thread.currentThread()
            val inCall = AtomicBoolean(true)
            val callbackUsed = AtomicBoolean(false)
            var callbackInvoked = false
            var callbackResult = false
            var callbackFailure: Throwable? = null

            try {
                try {
                    fence.completeIfCurrent {
                        if (
                            Thread.currentThread() !== invokingThread ||
                            !inCall.get() ||
                            !callbackUsed.compareAndSet(false, true)
                        ) {
                            false
                        } else {
                            callbackInvoked = true
                            try {
                                sink.publish(publication).also { callbackResult = it }
                            } catch (failure: Throwable) {
                                callbackFailure = failure
                                throw failure
                            }
                        }
                    }
                    callbackFailure?.let { throw it }
                    return if (callbackInvoked) callbackResult else false
                } catch (failure: Throwable) {
                    callbackFailure?.let { throw it }
                    if (callbackInvoked) return callbackResult
                    throw failure
                }
            } finally {
                inCall.set(false)
            }
        }
    }
}

/** The complete identity of one engine generation; every constituent invalidates its work. */
data class EngineEpoch(
    val sessionGeneration: Long,
    val configurationIdentity: ConfigurationIdentity,
    val artifactDigest: String,
    val policyRevision: Long,
    val networkGeneration: Long,
    val engineGeneration: Long,
    val primaryResolverIdentity: PrimaryResolverIdentity,
    val fallbackCoverage: ResolverFallbackCoverage,
) {
    init {
        require(engineGeneration > 0L) { "engine generation must be positive" }
    }
}

data class DatagramSource(
    val address: InetAddress,
    val port: Int,
)

class DatagramReceive(
    body: ByteArray,
    val source: DatagramSource,
) {
    private val wireBody = body.copyOf()

    val body: ByteArray
        get() = wireBody.copyOf()
}

sealed interface ResponseValidationResult {
    class Valid(
        dnsMessage: ByteArray,
        val truncated: Boolean,
    ) : ResponseValidationResult {
        private val wireMessage = dnsMessage.copyOf()

        val dnsMessage: ByteArray
            get() = wireMessage.copyOf()
    }

    data class Invalid(
        val reason: ResolverFailureReason,
        val terminal: Boolean = false,
        val peerAnswered: Boolean = false,
    ) : ResponseValidationResult
}

class ResolverRequest(
    query: ByteArray,
    val question: DnsQuestion,
    val epoch: EngineEpoch,
    val networkContext: NetworkContext,
    val queryId: Long = 0L,
    /** Shared absolute underlying-network DNS deadline; null until a coordinator selects it. */
    internal val absoluteDeviceDeadlineNanos: Long? = null,
    internal val wholeQueryDeadlineNanos: Long? = null,
    internal val fallbackContext: DnsFallbackQueryContext = DnsFallbackQueryContext(),
    internal val tierDeadlines: java.util.concurrent.ConcurrentHashMap<Int, Long> = java.util.concurrent.ConcurrentHashMap(),
) {
    init {
        require(queryId >= 0L) { "resolver query id must be non-negative" }
        require(networkContext.networkGeneration == epoch.networkGeneration) {
            "network context generation must match epoch network generation"
        }
    }

    private val wireQuery = query.copyOf()

    val query: ByteArray
        get() = wireQuery.copyOf()

    internal fun bounded(wholeDeadline: Long, tierDeadline: Long, context: DnsFallbackQueryContext = fallbackContext) = ResolverRequest(
        query, question, epoch, networkContext, queryId, tierDeadline, wholeDeadline, context, tierDeadlines,
    )
}

sealed interface ResolverResult {
    val healthEvents: List<DnsHealthEvent>
    val resolverQueryEvidence: ResolverQueryHealthEvidence?

    class Answer(
        dnsMessage: ByteArray,
        val transport: ResolverTransport,
        healthEvents: List<DnsHealthEvent> = emptyList(),
        override val resolverQueryEvidence: ResolverQueryHealthEvidence? = null,
        val httpProtocol: com.lavasec.app.foundation.health.DnsHttpProtocol? = null,
    ) : ResolverResult {
        private val wireMessage = dnsMessage.copyOf()
        private val terminalHealthEvents = Collections.unmodifiableList(healthEvents.toList())

        val dnsMessage: ByteArray
            get() = wireMessage.copyOf()

        override val healthEvents: List<DnsHealthEvent>
            get() = terminalHealthEvents
    }

    class Failure(
        val reason: ResolverFailureReason,
        healthEvents: List<DnsHealthEvent> = emptyList(),
        val resolver: ResolverKind? = null,
        override val resolverQueryEvidence: ResolverQueryHealthEvidence? = null,
        /** A validated late reply ends the ladder without claiming peer failure. */
        internal val terminal: Boolean = false,
        internal val peerAnswered: Boolean = false,
        internal val local: Boolean = reason == ResolverFailureReason.PROTECT_FAILED || reason == ResolverFailureReason.BIND_FAILED,
    ) : ResolverResult {
        private val terminalHealthEvents = Collections.unmodifiableList(healthEvents.toList())

        override val healthEvents: List<DnsHealthEvent>
            get() = terminalHealthEvents
    }
}

/** One upstream route; the sequencer replaces route-local metadata with terminal evidence. */
fun interface DnsResolver {
    suspend fun resolve(request: ResolverRequest): ResolverResult
}

internal interface PrimaryDnsResolver : DnsResolver {
    /** Unsupported test/composition adapters refuse rather than probing the whole ladder. */
    suspend fun resolvePrimary(request: ResolverRequest): ResolverResult =
        ResolverResult.Failure(ResolverFailureReason.OVERLOADED)
}

/**
 * Device owns the complete address snapshot and all per-address iteration for one route call.
 *
 * A successful route identifies the exact address whose transport observation produced the
 * answer, so a later reply-boundary rejection can replace success for that same endpoint.
 */
internal class DeviceDnsRouteResult(
    val result: ResolverResult,
    val answeredEndpoint: ResolverEndpointKey.Address?,
) {
    init {
        require((result is ResolverResult.Answer) == (answeredEndpoint != null)) {
            "an ordinary DNS answer must identify exactly one endpoint"
        }
    }
}

internal fun interface DeviceDnsRoute {
    suspend fun resolve(
        request: ResolverRequest,
        endpointBackoff: ResolverEndpointBackoffPolicy,
    ): DeviceDnsRouteResult
}

fun interface MonotonicClock {
    fun nowNanos(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
}

/** Owns all closeable resolver work belonging to an [EngineEpoch]. */
interface ResolverResourceRegistry {
    /**
     * Consumes [resource] on every return or throw path. A true result keeps it registry-owned
     * until [unregister] returns true; rejection closes it before returning. A rejected caller
     * must not close, unregister, or start the already-consumed resource.
     */
    fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean

    fun isCurrent(epoch: EngineEpoch): Boolean

    /**
     * Returns true only when ownership transfers back to the caller; never throws. The caller is
     * then responsible for a non-retiring terminal release of that resource.
     */
    fun unregister(resource: AutoCloseable): Boolean

    /**
     * Runs [completion] only while [resource] is still owned by the current [epoch].
     *
     * A true return linearizes completion with epoch retirement and transfers ownership to
     * [completion]. Every false or throw path consumes and closes ownership when this registry
     * successfully removed [resource]. The callback runs inside the registry's completion fence:
     * it must be non-blocking and must not cancel work, wait for drain, or re-enter this registry.
     */
    fun completeIfCurrent(
        resource: AutoCloseable,
        epoch: EngineEpoch,
        completion: () -> Boolean,
    ): Boolean
}

interface DeviceDnsSocket : AutoCloseable

interface DeviceUdpSocket : DeviceDnsSocket {
    fun send(query: ByteArray, server: InetAddress, port: Int, timeoutMillis: Int)

    fun receive(maxBytes: Int, timeoutMillis: Int): DatagramReceive
}

interface DeviceTcpSocket : DeviceDnsSocket {
    fun connect(server: InetAddress, port: Int, timeoutMillis: Int)

    fun writeFrame(frame: ByteArray, timeoutMillis: Int)

    fun readLength(timeoutMillis: Int): Int?

    fun readBody(length: Int, timeoutMillis: Int): ByteArray?
}

interface DeviceDnsSocketPlatform {
    fun createUdp(): DeviceUdpSocket

    fun createTcp(): DeviceTcpSocket

    fun protect(socket: DeviceDnsSocket): Boolean

    fun bind(socket: DeviceDnsSocket, network: Network)
}
