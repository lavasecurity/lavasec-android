package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedInnerUdpPacket
import com.lavasec.app.foundation.dns.DnsDecision
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.DnsQuestion
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.pause.PausePolicy
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.vpn.DecisionEventIdSource
import com.lavasec.app.vpn.DnsResponseTtlCap
import com.lavasec.app.vpn.EpochMillisClock
import com.lavasec.app.vpn.ProxyPolicyIdentity
import com.lavasec.app.vpn.ProxyPolicyRead
import com.lavasec.app.vpn.ProxyPolicySnapshot
import com.lavasec.app.vpn.ProxyPolicySource
import com.lavasec.app.vpn.ProxyPublicationFence
import com.lavasec.app.vpn.TunWriteAttempt
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** A client reply commits its evidence only after the complete packet is accepted by the TUN. */
internal class ChainedDnsResponse(
    private val attempt: ((ByteArray) -> TunWriteAttempt) -> TunWriteAttempt?,
) {
    private var consumed = false

    fun tryWrite(write: (ByteArray) -> TunWriteAttempt): TunWriteAttempt? {
        if (consumed) return null
        return try {
            attempt(write).also { if (it != TunWriteAttempt.WouldBlock) consumed = true }
        } catch (failure: Throwable) {
            consumed = true
            throw failure
        }
    }
}

internal class ChainedDnsQuery(
    val datagram: ChainedInnerUdpPacket,
    val question: DnsQuestion,
    val identity: ProxyPolicyIdentity,
    val protectionSessionId: Long,
    val activity: DnsDecisionEvent,
    val immediateResponse: ByteArray?,
    val startedPaused: Boolean,
    val maximumResponseTtlSeconds: Long?,
)

/**
 * The same verified artifact, DnsDecision and pause policy as DNS-only, with a tunnel-only
 * transport. The immutable artifact identity and logical protection session are checked again
 * before a reply is written, including replies retained under TUN backpressure. No Activity is
 * recorded at query admission or upstream receipt: only an authoritative client write commits it.
 */
internal class ChainedDnsPolicy(
    private val source: ProxyPolicySource,
    private val identity: ProxyPolicyIdentity,
    private val ownedSource: AutoCloseable,
    private val eventIds: DecisionEventIdSource,
    private val clock: EpochMillisClock,
    private val fence: ProxyPublicationFence,
    private val record: (DnsDecisionEvent?, List<DnsHealthEvent>, ResolverQueryHealthEvidence?) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun plan(datagram: ChainedInnerUdpPacket, question: DnsQuestion): ChainedDnsQuery? =
        read { snapshot ->
            val now = clock.nowMillis()
            require(now >= 0L) { "decision timestamp must be non-negative" }
            val decision = DnsDecision.evaluate(datagram.payload, question, snapshot.decisions)
            val filter = when (decision) {
                is DnsDecision.Forward -> decision.filterDecision
                is DnsDecision.Respond -> decision.filterDecision
                is DnsDecision.Failure -> return@read null
            }
            if (filter.reason == FilterDecisionReason.PROTECTION_UNAVAILABLE) return@read null
            val paused = PausePolicy.queryDecision(
                pauseState = (snapshot.pause as? RuntimePauseDirective.Paused)?.state,
                nowMillis = now,
                activeProtectionSessionId = snapshot.activeProtectionSessionId,
                wouldBeDecision = filter,
            )
            ChainedDnsQuery(
                datagram = datagram,
                question = question,
                identity = snapshot.identity,
                protectionSessionId = snapshot.activeProtectionSessionId,
                activity = DnsDecisionEvent(
                    id = eventIds.nextId().also { require(it >= 0L) },
                    timestampMillis = now,
                    normalizedDomain = question.normalizedDomain,
                    decision = paused?.decision ?: filter,
                ),
                immediateResponse = if (paused == null) (decision as? DnsDecision.Respond)?.response else null,
                startedPaused = paused != null,
                maximumResponseTtlSeconds = paused?.maximumResponseTtlSeconds,
            )
        }

    /** Re-check ownership and pause expiry before every emission, including resolver retries. */
    fun forward(
        query: ChainedDnsQuery,
        send: () -> Unit,
        respond: (ChainedDnsResponse) -> Unit,
    ): Boolean = !closed.get() && fence.completeIfCurrent {
        var local: ByteArray? = null
        var unavailable = false
        val current = read { snapshot ->
            if (!matches(query, snapshot)) return@read false
            if (query.startedPaused && !snapshot.pause.isActive(clock.nowMillis(), snapshot.activeProtectionSessionId)) {
                when (val decision = DnsDecision.evaluate(query.datagram.payload, query.question, snapshot.decisions)) {
                    is DnsDecision.Forward -> {
                        unavailable = decision.filterDecision.reason == FilterDecisionReason.PROTECTION_UNAVAILABLE
                    }
                    is DnsDecision.Respond -> {
                        unavailable = decision.filterDecision.reason == FilterDecisionReason.PROTECTION_UNAVAILABLE
                        local = decision.response
                    }
                    is DnsDecision.Failure -> unavailable = true
                }
            }
            !unavailable && local == null
        } == true
        when {
            unavailable -> respond(unavailableResponse(query.datagram))
            local != null -> respond(response(query, requireNotNull(local)))
            current -> send()
        }
        current
    }

    fun permittedPortalHosts(query: ChainedDnsQuery): Set<String> {
        if (query.question.rawRecordType !in setOf(1, 28) ||
            query.question.normalizedDomain !in com.lavasec.app.vpn.DnsPortalTierFallback.registeredHosts) return emptySet()
        return read { snapshot ->
            if (!matches(query, snapshot)) emptySet() else com.lavasec.app.vpn.DnsPortalTierFallback.registeredHosts.filter { host ->
                val decision = snapshot.decisions.decideNormalized(host)
                decision.action == com.lavasec.app.foundation.filter.FilterAction.ALLOW &&
                    decision.reason != FilterDecisionReason.PROTECTION_UNAVAILABLE
            }.toSet()
        }.orEmpty()
    }

    fun response(
        query: ChainedDnsQuery,
        payload: ByteArray,
        upstream: Boolean = false,
        failure: ResolverFailureReason? = null,
        latencyMillis: Long = 0L,
        fallbackTransport: ResolverTransport? = null,
        primaryFailure: ResolverFailureReason? = null,
        fallbackEvidence: ResolverQueryHealthEvidence? = null,
        primaryServedNegative: Boolean = false,
        primaryReplyOnly: Boolean = false,
        portalDisposition: com.lavasec.app.foundation.health.DnsFallbackDisposition? = null,
        primaryResponse: ByteArray? = null,
        primaryLateReply: Boolean = false,
        primaryLocal: Boolean = false,
        primaryResponseIsSynthetic: Boolean = false,
    ): ChainedDnsResponse = reply {
        read { snapshot ->
            if (!matches(query, snapshot)) return@read null
            var response = payload
            var activity = query.activity
            var resolvedUpstream = upstream
            if (query.startedPaused) {
                if (snapshot.pause.isActive(clock.nowMillis(), snapshot.activeProtectionSessionId)) {
                    query.maximumResponseTtlSeconds?.let { maximum ->
                        response = DnsResponseTtlCap.capCacheableTtls(response, maximum)
                            ?: return@read unavailable(query.datagram)
                    }
                } else {
                    when (val decision = DnsDecision.evaluate(query.datagram.payload, query.question, snapshot.decisions)) {
                        is DnsDecision.Forward -> {
                            if (decision.filterDecision.reason == FilterDecisionReason.PROTECTION_UNAVAILABLE) {
                                return@read unavailable(query.datagram)
                            }
                            activity = activity.copy(decision = decision.filterDecision)
                        }
                        is DnsDecision.Respond -> {
                            if (decision.filterDecision.reason == FilterDecisionReason.PROTECTION_UNAVAILABLE) {
                                return@read unavailable(query.datagram)
                            }
                            response = decision.response
                            activity = activity.copy(decision = decision.filterDecision)
                            resolvedUpstream = false
                        }
                        is DnsDecision.Failure -> return@read unavailable(query.datagram)
                    }
                }
            }
            if (resolvedUpstream) {
                activity = activity.copy(
                    resolver = fallbackTransport?.fallbackResolverKind() ?: ResolverKind.WIREGUARD_T0,
                    transport = if (failure == null) fallbackTransport ?: ResolverTransport.WIREGUARD_UDP else null,
                    latencyMillis = latencyMillis,
                    error = failure,
                )
            }
            val primaryMeaning = (primaryResponse ?: payload.takeIf { primaryFailure == null && !primaryResponseIsSynthetic })?.let {
                com.lavasec.app.vpn.DnsFallbackResponse.classify(query.datagram.payload, it)
            }
            val savedReplySelected = fallbackTransport != null && failure == null &&
                portalDisposition != com.lavasec.app.foundation.health.DnsFallbackDisposition.PRESERVED_NEGATIVE
            val evidence = if (resolvedUpstream) {
                when {
                    fallbackTransport != null && primaryFailure != null -> ResolverQueryHealthEvidence.primaryFailed(
                        activity.id, primaryFailure, fallbackTransport.fallbackCategory(),
                    )
                    primaryReplyOnly -> ResolverQueryHealthEvidence(activity.id, com.lavasec.app.foundation.health.PrimaryResolverOutcome.REJECTED)
                    failure == null -> ResolverQueryHealthEvidence.primaryAccepted(activity.id, latencyMillis)
                    else -> ResolverQueryHealthEvidence.primaryFailed(activity.id, failure)
                }.let { evidence -> evidence.copy(
                    responseLatencyMillis = if (failure == null) latencyMillis else null,
                    httpProtocol = if (savedReplySelected) fallbackEvidence?.httpProtocol else null,
                    fallbackObservations = (if (primaryFailure != null || portalDisposition != null) listOf(
                        com.lavasec.app.foundation.health.DnsFallbackObservation(0, fallbackEvidence?.tierObservations?.firstOrNull()?.tier ?: 1,
                            if (primaryServedNegative) com.lavasec.app.foundation.health.DnsFallbackReason.PORTAL_ADDRESS_NEGATIVE
                            else com.lavasec.app.foundation.health.DnsFallbackReason.TRANSPORT_FAILURE,
                            portalDisposition ?: if (fallbackTransport != null && failure == null) com.lavasec.app.foundation.health.DnsFallbackDisposition.SERVED
                                else com.lavasec.app.foundation.health.DnsFallbackDisposition.ATTEMPTED)) else emptyList()) + fallbackEvidence?.fallbackObservations.orEmpty().map { observation ->
                        if (!savedReplySelected && observation.disposition == com.lavasec.app.foundation.health.DnsFallbackDisposition.SERVED)
                            observation.copy(disposition = com.lavasec.app.foundation.health.DnsFallbackDisposition.ATTEMPTED)
                        else observation
                    },
                    tierObservations = listOf(com.lavasec.app.foundation.health.ResolverTierDiagnosticObservation(
                        0, ResolverKind.WIREGUARD_T0,
                        if (primaryLocal) com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.NOT_ATTEMPTED
                        else if (primaryServedNegative && savedReplySelected) com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.ANSWERED
                        else if (primaryServedNegative || failure == null && primaryFailure == null && !primaryReplyOnly) com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.SERVED
                        else if (primaryReplyOnly || primaryMeaning != null) com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.ANSWERED
                        else com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.FAILED,
                        if (primaryServedNegative || failure == null && primaryFailure == null) ResolverTransport.WIREGUARD_UDP else null,
                        if (primaryServedNegative) null else primaryFailure ?: failure,
                        peerLivenessOnly = primaryLateReply,
                        responseClass = primaryMeaning?.kind?.let { kind -> when (kind) {
                            com.lavasec.app.vpn.DnsFallbackResponse.Kind.POSITIVE -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.POSITIVE
                            com.lavasec.app.vpn.DnsFallbackResponse.Kind.NEGATIVE -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.NEGATIVE
                            com.lavasec.app.vpn.DnsFallbackResponse.Kind.MALFORMED -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.MALFORMED
                            com.lavasec.app.vpn.DnsFallbackResponse.Kind.MISMATCHED -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.MISMATCHED
                            com.lavasec.app.vpn.DnsFallbackResponse.Kind.TRUNCATED -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.TRUNCATED
                            else -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.DNS_ERROR
                        } }, responseCode = primaryMeaning?.fullResponseCode,
                        queryType = query.question.rawRecordType,
                        terminalAddressPresent = primaryMeaning?.let { it.terminalAddressCount > 0 },
                        packetSent = if (primaryLocal) false else if (primaryMeaning != null) true else null,
                    )) + fallbackEvidence?.tierObservations.orEmpty().filter { it.tier != 0 }.map { observation ->
                        if (!savedReplySelected && observation.outcome == com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.SERVED)
                            observation.copy(outcome = com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.ANSWERED)
                        else observation
                    },
                ) }
            } else null
            Completed(query.datagram.responseWith(response), activity, emptyList(), evidence)
        }
    }

    private fun ResolverTransport.fallbackResolverKind(): ResolverKind = when (this) {
        ResolverTransport.DEVICE_UDP, ResolverTransport.DEVICE_TCP -> ResolverKind.DEVICE
        ResolverTransport.PLAIN_UDP, ResolverTransport.PLAIN_TCP -> ResolverKind.PLAIN_DNS
        ResolverTransport.MULLVAD_DOH, ResolverTransport.DOT, ResolverTransport.DOQ -> ResolverKind.MULLVAD_DOH
        ResolverTransport.WIREGUARD_UDP -> error("a fallback cannot be the T0 transport")
    }

    private fun ResolverTransport.fallbackCategory(): ProtectionFallback = when (this) {
        ResolverTransport.DEVICE_UDP, ResolverTransport.DEVICE_TCP -> ProtectionFallback.DEVICE_DNS
        ResolverTransport.PLAIN_UDP, ResolverTransport.PLAIN_TCP -> ProtectionFallback.PLAIN_DNS
        ResolverTransport.MULLVAD_DOH, ResolverTransport.DOT, ResolverTransport.DOQ -> ProtectionFallback.ENCRYPTED_DNS
        ResolverTransport.WIREGUARD_UDP -> error("a fallback cannot be the T0 transport")
    }

    fun unavailableResponse(datagram: ChainedInnerUdpPacket): ChainedDnsResponse =
        reply { unavailable(datagram) }

    fun malformedResponse(datagram: ChainedInnerUdpPacket): ChainedDnsResponse = reply {
        DnsMessage.servfailResponse(datagram.payload)?.let {
            Completed(datagram.responseWith(it), null, emptyList())
        }
    }

    private fun unavailable(datagram: ChainedInnerUdpPacket): Completed? =
        DnsMessage.servfailResponse(datagram.payload)?.let {
            Completed(
                datagram.responseWith(it),
                null,
                listOf(DnsHealthEvent.ProtectionUnavailable(ProtectionUnavailableReason.RuntimeUnavailable)),
            )
        }

    private fun reply(prepare: () -> Completed?): ChainedDnsResponse = ChainedDnsResponse { write ->
        var result: TunWriteAttempt? = null
        if (!closed.get()) fence.completeIfCurrent {
            val completed = prepare() ?: return@completeIfCurrent false
            if (closed.get()) return@completeIfCurrent false
            result = write(completed.packet)
            if (result == TunWriteAttempt.Written) record(completed.activity, completed.health, completed.evidence)
            true
        }
        result
    }

    private fun matches(query: ChainedDnsQuery, snapshot: ProxyPolicySnapshot): Boolean =
        query.identity == snapshot.identity && query.protectionSessionId == snapshot.activeProtectionSessionId

    private fun <T> read(block: (ProxyPolicySnapshot) -> T?): T? {
        if (closed.get()) return null
        return try {
            when (val result = source.withCurrent { if (it.identity == identity) block(it) else null }) {
                is ProxyPolicyRead.Available -> result.value
                ProxyPolicyRead.Unavailable -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ownedSource.close()
    }

    private class Completed(
        val packet: ByteArray,
        val activity: DnsDecisionEvent?,
        val health: List<DnsHealthEvent>,
        val evidence: ResolverQueryHealthEvidence? = null,
    )
}
