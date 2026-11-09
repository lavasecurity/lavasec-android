package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.filter.DomainFilterDecisionSource
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.platform.ConfigurationIdentity
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

internal const val TEST_NOW_MILLIS = 1_000_000L
internal const val TEST_PROTECTION_SESSION_ID = 41L

internal fun testConfigurationIdentity(
    generation: Long = 7L,
    fingerprint: String = "configuration-fingerprint",
) = ConfigurationIdentity(generation, fingerprint)

internal fun testPolicyIdentity(
    configurationIdentity: ConfigurationIdentity = testConfigurationIdentity(),
    artifactDigest: String = "artifact-digest",
    revision: Long = 11L,
    sourceIncarnation: Long = 13L,
) = ProxyPolicyIdentity(
    configurationIdentity = configurationIdentity,
    artifactDigest = artifactDigest,
    revision = revision,
    sourceIncarnation = sourceIncarnation,
)

internal fun testNetwork(handle: Int = 42): Network =
    Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .newInstance(handle)

internal fun testNetworkContext(
    network: Network = testNetwork(),
    networkGeneration: Long = 17L,
    dnsServers: List<InetAddress> = listOf(InetAddress.getByName("192.0.2.53")),
    isCellular: Boolean = false,
) = NetworkContext(
    network = network,
    networkGeneration = networkGeneration,
    dnsServers = dnsServers,
    isCellular = isCellular,
)

internal fun testResolverPlan(
    networkContext: NetworkContext,
    mullvadEnabled: Boolean = false,
    primaryResolverKind: ResolverKind = ResolverKind.DEVICE,
    primaryResolverIdentity: PrimaryResolverIdentity = if (
        primaryResolverKind == ResolverKind.MULLVAD_DOH
    ) {
        PrimaryResolverIdentity(MullvadDohEndpoint.id)
    } else {
        PrimaryResolverIdentity("device-dns:v4=c0000235;v6=")
    },
    fallbackCoverage: ResolverFallbackCoverage = if (
        primaryResolverKind == ResolverKind.DEVICE && mullvadEnabled
    ) {
        ResolverFallbackCoverage.ENCRYPTED_DNS
    } else {
        ResolverFallbackCoverage.NONE
    },
    network: Network = networkContext.network,
    networkGeneration: Long = networkContext.networkGeneration,
    isCellular: Boolean = networkContext.isCellular,
) = ResolverPlan(
    network = network,
    networkGeneration = networkGeneration,
    isCellular = isCellular,
    deviceDnsServers = if (primaryResolverKind == ResolverKind.DEVICE) {
        networkContext.dnsServers
    } else {
        emptyList()
    },
    mullvadEnabled = mullvadEnabled,
    primaryResolverKind = primaryResolverKind,
    primaryResolverIdentity = primaryResolverIdentity,
    fallbackCoverage = fallbackCoverage,
)

internal fun testEpoch(
    policyIdentity: ProxyPolicyIdentity = testPolicyIdentity(),
    resolverPlan: ResolverPlan,
    sessionGeneration: Long = 23L,
    engineGeneration: Long = 29L,
    networkGeneration: Long = resolverPlan.networkGeneration,
    primaryResolverIdentity: PrimaryResolverIdentity = resolverPlan.primaryResolverIdentity,
    fallbackCoverage: ResolverFallbackCoverage = resolverPlan.fallbackCoverage,
) = EngineEpoch(
    sessionGeneration = sessionGeneration,
    configurationIdentity = policyIdentity.configurationIdentity,
    artifactDigest = policyIdentity.artifactDigest,
    policyRevision = policyIdentity.revision,
    networkGeneration = networkGeneration,
    engineGeneration = engineGeneration,
    primaryResolverIdentity = primaryResolverIdentity,
    fallbackCoverage = fallbackCoverage,
)

internal class TestDecisionSource(
    var result: FilterDecision = FilterDecision.DEFAULT_ALLOW,
) : DomainFilterDecisionSource {
    var decideCalls: Int = 0
    var failure: Throwable? = null

    override val blockRuleCount: Int = 0
    override val allowRuleCount: Int = 0
    override val guardrailRuleCount: Int = 0

    override fun decide(rawDomain: String): FilterDecision = decideNormalized(rawDomain)

    override fun decideNormalized(normalizedDomain: String): FilterDecision {
        decideCalls++
        failure?.let { throw it }
        return result
    }
}

internal class TestPolicySource(
    var snapshot: ProxyPolicySnapshot,
) : ProxyPolicySource {
    var calls: Int = 0
    var failure: Throwable? = null

    override fun <T> withCurrent(
        block: (ProxyPolicySnapshot) -> T,
    ): ProxyPolicyRead<T> {
        calls++
        failure?.let { throw it }
        return ProxyPolicyRead.Available(block(snapshot))
    }
}

internal class TestOwnedDnsPolicyRuntime(
    override val identity: ProxyPolicyIdentity,
    override val proxy: DnsProxy,
) : OwnedDnsPolicyRuntime {
    override fun checkRetirementAllowed() = Unit

    override fun beginRetirement() = Unit

    override fun close() = Unit
}

internal class TestEventIds(
    var next: Long = 101L,
) : DecisionEventIdSource {
    var calls: Int = 0
    var failure: Throwable? = null

    override fun nextId(): Long {
        calls++
        failure?.let { throw it }
        return next
    }
}

internal class TestEpochMillisClock(
    var now: Long = TEST_NOW_MILLIS,
) : EpochMillisClock {
    var calls: Int = 0
    var failure: Throwable? = null

    override fun nowMillis(): Long {
        calls++
        failure?.let { throw it }
        return now
    }
}

internal class TestMonotonicClock(
    var now: Long = 5_000L,
) : MonotonicClock {
    var calls: Int = 0
    var failure: Throwable? = null
    var onRead: ((Int) -> Unit)? = null
    val readings = ArrayDeque<Long>()

    override fun nowNanos(): Long {
        calls++
        onRead?.invoke(calls)
        failure?.let { throw it }
        return if (readings.isEmpty()) now else readings.removeFirst()
    }
}

internal class TestPublicationFence(
    var current: Boolean = true,
) : ProxyPublicationFence {
    var calls: Int = 0

    override fun completeIfCurrent(completion: () -> Boolean): Boolean {
        calls++
        return if (current) completion() else false
    }
}

internal class RecordingPublicationSink(
    var result: Boolean = true,
) : ProxyPublicationSink {
    private val published = Collections.synchronizedList(mutableListOf<ProxyPublication>())
    val calls = AtomicInteger()
    var failure: Throwable? = null

    val publications: List<ProxyPublication>
        get() = synchronized(published) { published.toList() }

    override fun publish(publication: ProxyPublication): Boolean {
        calls.incrementAndGet()
        failure?.let { throw it }
        published += publication
        return result
    }
}

internal class TestReadyDnsEngine(
    override val epoch: EngineEpoch,
    override val networkContext: NetworkContext,
    override val resolverPlan: ResolverPlan,
    override val policyIdentity: ProxyPolicyIdentity,
    override val proxy: DnsProxy,
) : ReadyDnsEngine {
    var current: Boolean = true
    var resolveCalls: Int = 0
    var currentChecks: Int = 0
    var fenceCalls: Int = 0
    var closeCalls: Int = 0
    var resolverResult: ResolverResult = ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
    var resolveHandler: (suspend (ResolverRequest) -> ResolverResult)? = null
    var onCurrentCheck: ((Int) -> Unit)? = null
    var onFence: ((Int) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    val resolvedRequests = Collections.synchronizedList(mutableListOf<ResolverRequest>())

    override fun isCurrent(): Boolean {
        currentChecks++
        onCurrentCheck?.invoke(currentChecks)
        return current
    }

    override suspend fun resolveIfCurrent(request: ResolverRequest): ResolverResult {
        resolveCalls++
        resolvedRequests += request
        return resolveHandler?.invoke(request) ?: resolverResult
    }

    override fun completeIfCurrent(completion: () -> Boolean): Boolean {
        fenceCalls++
        onFence?.invoke(fenceCalls)
        return if (current) completion() else false
    }

    override fun close() {
        closeCalls++
        onClose?.invoke()
        current = false
    }
}

internal class DnsProxyHarness(
    decision: FilterDecision = FilterDecision.DEFAULT_ALLOW,
    pause: RuntimePauseDirective = RuntimePauseDirective.Filtering,
    activeProtectionSessionId: Long = TEST_PROTECTION_SESSION_ID,
    val policyIdentity: ProxyPolicyIdentity = testPolicyIdentity(),
    unavailableFence: TestPublicationFence = TestPublicationFence(),
    publicationSink: ProxyPublicationSink? = null,
) {
    val decisions = TestDecisionSource(decision)
    val policySource = TestPolicySource(
        ProxyPolicySnapshot(
            identity = policyIdentity,
            activeProtectionSessionId = activeProtectionSessionId,
            pause = pause,
            decisions = decisions,
        ),
    )
    val eventIds = TestEventIds()
    val wallClock = TestEpochMillisClock()
    val monotonicClock = TestMonotonicClock()
    val sink = RecordingPublicationSink()
    private val selectedPublicationSink = publicationSink ?: sink
    val unavailableFence = unavailableFence
    val proxy = DefaultDnsProxy(
        policySource = policySource,
        eventIds = eventIds,
        wallClock = wallClock,
        monotonicClock = monotonicClock,
        publicationSink = selectedPublicationSink,
        unavailablePublicationFence = unavailableFence,
    )

    fun engine(
        networkContext: NetworkContext = testNetworkContext(),
        resolverPlan: ResolverPlan = testResolverPlan(networkContext),
        epoch: EngineEpoch = testEpoch(policyIdentity, resolverPlan),
        enginePolicyIdentity: ProxyPolicyIdentity = policyIdentity,
    ) = TestReadyDnsEngine(
        epoch = epoch,
        networkContext = networkContext,
        resolverPlan = resolverPlan,
        policyIdentity = enginePolicyIdentity,
        proxy = proxy,
    )

    fun publish(plan: ProxyPlan): ProxyPublication {
        val before = sink.publications.size
        check((plan as ProxyPlan.Immediate).result.publishIfCurrent())
        return sink.publications[before]
    }
}

internal fun testDnsQuery(
    domain: String = "example.com",
    rawType: Int = 1,
    transactionId: Int = 0x1234,
    flags: Int = 0x0100,
    questionClass: Int = 1,
    answerCount: Int = 0,
    authorityCount: Int = 0,
    additionalRecords: ByteArray = byteArrayOf(),
    additionalCount: Int = 0,
    trailingBytes: ByteArray = byteArrayOf(),
): ByteArray = ByteArrayOutputStream().apply {
    appendUInt16(transactionId)
    appendUInt16(flags)
    appendUInt16(1)
    appendUInt16(answerCount)
    appendUInt16(authorityCount)
    appendUInt16(additionalCount)
    for (label in domain.split('.')) {
        val bytes = label.encodeToByteArray()
        write(bytes.size)
        write(bytes)
    }
    write(0)
    appendUInt16(rawType)
    appendUInt16(questionClass)
    write(additionalRecords)
    write(trailingBytes)
}.toByteArray()

internal fun testOptRecord(
    rdata: ByteArray = byteArrayOf(),
    declaredRdataLength: Int = rdata.size,
): ByteArray = ByteArrayOutputStream().apply {
    write(0)
    appendUInt16(41)
    appendUInt16(1_232)
    write(ByteArray(4))
    appendUInt16(declaredRdataLength)
    write(rdata)
}.toByteArray()

internal fun testOrdinaryAdditionalRecord(): ByteArray = ByteArrayOutputStream().apply {
    write(0)
    appendUInt16(1)
    appendUInt16(1)
    write(ByteArray(4))
    appendUInt16(4)
    write(byteArrayOf(192.toByte(), 0, 2, 1))
}.toByteArray()

internal fun testDnsPacket(
    payload: ByteArray = testDnsQuery(),
    destinationAddress: ByteArray = byteArrayOf(10, 111, 0, 1),
    destinationPort: Int = 53,
    protocol: Int = 17,
    flagsAndFragmentOffset: Int = 0,
): ByteArray {
    val totalLength = 20 + 8 + payload.size
    return ByteArray(totalLength).also { packet ->
        packet[0] = 0x45
        packet.writeUInt16(2, totalLength)
        packet.writeUInt16(4, 0x4321)
        packet.writeUInt16(6, flagsAndFragmentOffset)
        packet[8] = 64
        packet[9] = protocol.toByte()
        byteArrayOf(10, 0, 0, 8).copyInto(packet, 12)
        destinationAddress.copyInto(packet, 16)
        packet.writeUInt16(20, 53_000)
        packet.writeUInt16(22, destinationPort)
        packet.writeUInt16(24, 8 + payload.size)
        payload.copyInto(packet, 28)
    }
}

internal fun ProxyPublication.dnsPayload(): ByteArray = responsePacket.copyOfRange(28, responsePacket.size)

internal fun ByteArray.readUInt16(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

internal fun ByteArray.readUInt32(offset: Int): Long =
    ((this[offset].toLong() and 0xff) shl 24) or
        ((this[offset + 1].toLong() and 0xff) shl 16) or
        ((this[offset + 2].toLong() and 0xff) shl 8) or
        (this[offset + 3].toLong() and 0xff)

private fun ByteArrayOutputStream.appendUInt16(value: Int) {
    write((value ushr 8) and 0xff)
    write(value and 0xff)
}

private fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal val runtimeUnavailableHealth =
    listOf(DnsHealthEvent.ProtectionUnavailable(
        com.lavasec.app.foundation.dns.ProtectionUnavailableReason.RuntimeUnavailable,
    ))

internal val protectionUnavailableDecision =
    FilterDecision(FilterAction.BLOCK, FilterDecisionReason.PROTECTION_UNAVAILABLE)
