package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.platform.ProtectionFailure
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel

internal sealed interface TunReadScript {
    data class Packet(val bytes: ByteArray) : TunReadScript
    data object EndOfStream : TunReadScript
    data object Closed : TunReadScript
    data class Failed(val cause: Throwable) : TunReadScript
}

internal class ScriptedTunPacketSession : TunPacketSession {
    val reads = Channel<TunReadScript>(Channel.UNLIMITED)
    val writeAttempts = Channel<ByteArray>(Channel.UNLIMITED)
    val writeResults = Channel<TunWriteAttempt>(Channel.UNLIMITED)
    val writableResults = Channel<TunWritable>(Channel.UNLIMITED)
    val attemptedWrites =
        Collections.synchronizedList(mutableListOf<ByteArray>())
    val recordedWrites =
        Collections.synchronizedList(mutableListOf<ByteArray>())
    val maximumConcurrentReads = AtomicInteger()
    val maximumConcurrentWrites = AtomicInteger()
    val closeCalls = AtomicInteger()
    val readCalls = AtomicInteger()
    val writableCalls = AtomicInteger()
    var onRead: (() -> Unit)? = null
    var onWrite: (() -> Unit)? = null
    var onAwaitWritable: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val closed = AtomicBoolean()
    private val concurrentReads = AtomicInteger()
    private val concurrentWrites = AtomicInteger()

    fun enqueuePacket(packet: ByteArray) {
        check(reads.trySend(TunReadScript.Packet(packet.copyOf())).isSuccess)
    }

    fun enqueue(script: TunReadScript) {
        check(reads.trySend(script).isSuccess)
    }

    override suspend fun readPacket(into: ByteArray): TunRead {
        readCalls.incrementAndGet()
        onRead?.invoke()
        val concurrent = concurrentReads.incrementAndGet()
        maximumConcurrentReads.updateAndGet { maximum -> maxOf(maximum, concurrent) }
        return try {
            when (val script = reads.receiveCatching().getOrNull()) {
                is TunReadScript.Packet -> {
                    if (script.bytes.size <= into.size) {
                        script.bytes.copyInto(into)
                    }
                    TunRead.Packet(script.bytes.size)
                }
                TunReadScript.EndOfStream -> TunRead.EndOfStream
                TunReadScript.Closed,
                null,
                -> TunRead.Closed
                is TunReadScript.Failed -> TunRead.Failed(script.cause)
            }
        } finally {
            concurrentReads.decrementAndGet()
        }
    }

    override fun tryWritePacket(packet: ByteArray): TunWriteAttempt {
        if (closed.get()) return TunWriteAttempt.Closed
        val concurrent = concurrentWrites.incrementAndGet()
        maximumConcurrentWrites.updateAndGet { maximum -> maxOf(maximum, concurrent) }
        return try {
            val copy = packet.copyOf()
            attemptedWrites += copy
            check(writeAttempts.trySend(copy).isSuccess)
            onWrite?.invoke()
            (writeResults.tryReceive().getOrNull() ?: TunWriteAttempt.WouldBlock).also {
                if (it === TunWriteAttempt.Written) recordedWrites += copy
            }
        } finally {
            concurrentWrites.decrementAndGet()
        }
    }

    override suspend fun awaitWritable(): TunWritable {
        writableCalls.incrementAndGet()
        onAwaitWritable?.invoke()
        if (closed.get()) return TunWritable.Closed
        return writableResults.receiveCatching().getOrNull() ?: TunWritable.Closed
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeCalls.incrementAndGet()
        onClose?.invoke()
        reads.close()
        writeResults.close()
        writableResults.close()
    }
}

internal class RecordingTunRuntimeObserver : TunRuntimeObserver {
    val evidence = Collections.synchronizedList(mutableListOf<TunTerminalEvidence>())
    val health = Collections.synchronizedList(mutableListOf<DnsHealthEvent>())
    val failures = Collections.synchronizedList(mutableListOf<ProtectionFailure>())
    var evidenceAccepted = true
    var evidenceFailure: Throwable? = null
    var onEvidence: ((TunTerminalEvidence) -> Unit)? = null
    var onHealth: ((DnsHealthEvent) -> Unit)? = null
    var onFailure: ((ProtectionFailure) -> Unit)? = null

    override fun acceptTerminalEvidence(evidence: TunTerminalEvidence): Boolean {
        this.evidence += evidence
        onEvidence?.invoke(evidence)
        evidenceFailure?.let { throw it }
        return evidenceAccepted
    }

    override fun publishHealth(event: DnsHealthEvent) {
        health += event
        onHealth?.invoke(event)
    }

    override fun publishFailure(failure: ProtectionFailure) {
        failures += failure
        onFailure?.invoke(failure)
    }
}

internal class TestRuntimeDnsProxy(
    private val planner: (ByteArray, ReadyDnsEngine) -> ProxyPlan,
    private val unavailablePlanner:
        (ByteArray, ProtectionUnavailableReason) -> ProxyPlan,
    private val executor: suspend (ProxyPlan.Forward) -> ProxyResult,
    private val overloadRejector: (ProxyPlan.Forward) -> ProxyResult,
) : DnsProxy {
    val plannedPackets = Collections.synchronizedList(mutableListOf<ByteArray>())
    val plannedEngineGenerations = Collections.synchronizedList(mutableListOf<Long>())
    val executedIds = Collections.synchronizedList(mutableListOf<Long>())
    val rejectedIds = Collections.synchronizedList(mutableListOf<Long>())
    var onPlan: ((ByteArray, ReadyDnsEngine) -> Unit)? = null
    var onExecute: ((ProxyPlan.Forward) -> Unit)? = null
    var onRejectOverloaded: ((ProxyPlan.Forward) -> Unit)? = null

    override fun plan(packet: ByteArray, engine: ReadyDnsEngine): ProxyPlan {
        plannedPackets += packet.copyOf()
        plannedEngineGenerations += engine.epoch.engineGeneration
        onPlan?.invoke(packet, engine)
        return planner(packet, engine)
    }

    override fun planUnavailable(
        packet: ByteArray,
        reason: ProtectionUnavailableReason,
    ): ProxyPlan = unavailablePlanner(packet, reason)

    override suspend fun execute(plan: ProxyPlan.Forward): ProxyResult {
        executedIds += plan.request.queryId
        onExecute?.invoke(plan)
        return executor(plan)
    }

    override fun rejectOverloaded(plan: ProxyPlan.Forward): ProxyResult {
        rejectedIds += plan.request.queryId
        onRejectOverloaded?.invoke(plan)
        return overloadRejector(plan)
    }
}

internal enum class TunStressPlanKind {
    DROP,
    IMMEDIATE,
    FORWARD,
}

internal data class TunStressPlan(
    val packet: ByteArray,
    val plan: ProxyPlan,
)

/**
 * Supplies production-shaped packets, plans, publications, writes, readiness, and evidence to
 * scheduler stress tests. The state oracle deliberately lives outside this fixture.
 */
internal class TunStressArtifacts(
    private val sessionToken: Long,
) {
    val packetSession = ScriptedTunPacketSession()
    val observer = RecordingTunRuntimeObserver()

    private val harness = DnsProxyHarness()
    private val networkContext = testNetworkContext()
    private val resolverPlan = testResolverPlan(networkContext)

    fun engine(generation: Long): TestReadyDnsEngine = harness.engine(
        networkContext = networkContext,
        resolverPlan = resolverPlan,
        epoch = testEpoch(
            policyIdentity = harness.policyIdentity,
            resolverPlan = resolverPlan,
            sessionGeneration = sessionToken,
            engineGeneration = generation,
        ),
    )

    fun plan(
        transactionId: Int,
        kind: TunStressPlanKind,
        engine: ReadyDnsEngine,
    ): TunStressPlan {
        val packet = when (kind) {
            TunStressPlanKind.DROP -> byteArrayOf(1)
            TunStressPlanKind.IMMEDIATE,
            TunStressPlanKind.FORWARD,
            -> testDnsPacket(testDnsQuery(transactionId = transactionId))
        }
        val previousDecision = harness.decisions.result
        harness.decisions.result = when (kind) {
            TunStressPlanKind.IMMEDIATE ->
                FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST)
            TunStressPlanKind.DROP,
            TunStressPlanKind.FORWARD,
            -> FilterDecision.DEFAULT_ALLOW
        }
        val plan = try {
            engine.proxy.plan(packet, engine)
        } finally {
            harness.decisions.result = previousDecision
        }
        check(
            when (kind) {
                TunStressPlanKind.DROP -> plan === ProxyPlan.Drop
                TunStressPlanKind.IMMEDIATE -> plan is ProxyPlan.Immediate
                TunStressPlanKind.FORWARD -> plan is ProxyPlan.Forward
            },
        ) {
            "stress fixture produced ${plan::class.simpleName} for $kind"
        }
        return TunStressPlan(packet, plan)
    }

    suspend fun finishResolution(
        plan: ProxyPlan.Forward,
        publish: Boolean,
    ): ProxyResult {
        if (!publish) return ProxyResult.NotPublishable
        (plan.engine as TestReadyDnsEngine).resolverResult =
            ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
        return plan.engine.proxy.execute(plan)
    }

    fun publish(pending: ProxyResult.Pending): ProxyPublication? {
        val before = harness.sink.publications.size
        if (!pending.publishIfCurrent()) return null
        return harness.sink.publications.getOrNull(before)
    }
}
