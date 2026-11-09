package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedInnerUdpPacket
import com.lavasec.app.foundation.chained.ChainedOwnResolverPorts
import com.lavasec.app.foundation.chained.ChainedRoutingPolicy
import com.lavasec.app.foundation.chained.ChainedTunnelResolverSelection
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.foundation.dns.ChainedIpv6DnsResponsePolicy
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.DnsMessageException
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.vpn.ResolverResult
import com.lavasec.app.vpn.ResponseValidation
import com.lavasec.app.vpn.ResponseValidationResult
import com.lavasec.app.vpn.DnsEdns0
import com.lavasec.app.vpn.DnsFallbackResponse
import com.lavasec.app.vpn.DnsPortalTierFallback
import com.lavasec.app.vpn.ResolverReplyBoundary
import kotlin.coroutines.cancellation.CancellationException

/**
 * The T0 client-DNS egress: answers intercepted client DNS queries through the WireGuard session to
 * the configuration's own `DNS =` resolver.
 *
 * ## The leak this path exists to prevent
 *
 * The DNS-only tunnel answers client DNS locally. While chained, the tunnel claims the device's
 * routes, and the only sanctioned resolver egress is plain UDP DNS encapsulated through the
 * session to the upstream's resolver. A split activation can separately supply an explicitly
 * consented, epoch-bound saved-DNS fallback. This class opens NO socket: it rebuilds each intercepted
 * query as an inner IPv4/UDP datagram and hands it to the engine through [bind]'s `sendQuery`
 * (which classifies it as `.encapsulateOwnResolverQuery` because the ephemeral source port is in
 * [ChainedOwnResolverPorts]). A physical-interface DNS egress is unrepresentable here.
 *
 * Ported from the iOS split — `ChainedResolverEgress` (the policy), `ChainedTunnelResolverSelection`
 * (which `DNS =` entries are usable), `ChainedInboundDNSReply` (reply identification) and
 * `TunnelledPlainDNSResolution` (the plain-UDP resolution loop). Android cannot use the iOS
 * kernel-socket carry (a socket bound to the tunnel interface, whose packets resurface at the
 * provider's own read loop) because a `VpnService` has no in-kernel tunnel stack; this class does
 * the equivalent userspace reconstruction. That platform difference is recorded in
 * `docs/chained-port-from-ios.md`.
 *
 * ## Fail-closed by construction
 *
 * - No resolver, no port, a malformed question or a query over the in-flight ceiling all produce a
 *   SERVFAIL to the client. Physical DNS is available only through the separately owned,
 *   consented split-tunnel adapter after terminal T0 failure.
 * - The IPv6 response policy runs on the RESPONSE and only declines records; it never admits a
 *   blocked name, and a malformed RDATA is passed through byte-for-byte (`stripIpv6Hints`).
 * - An inbound packet to one of our ports that is not a valid reply for the pending query is
 *   consumed and dropped; a spoof cannot both consume the lookup and be delivered to the client.
 *
 * ## Confinement
 *
 * Every method runs on the chained engine queue (`ChainedDataPath`'s dispatcher), the same
 * confinement as the engine and the classifier, so no lock is needed here. [bind] is called once,
 * during `ChainedDataPath` construction, before any packet can arrive.
 */
internal class ChainedT0ResolverEgress(
    configuration: ChainedUpstreamConfiguration,
    private val ports: ChainedOwnResolverPorts,
    private val nowMillis: () -> Long,
    private val policy: ChainedDnsPolicy,
    private val maximumPendingQueries: Int = DEFAULT_MAXIMUM_PENDING_QUERIES,
    private val queryTimeoutMillis: Long = DEFAULT_QUERY_TIMEOUT_MILLIS,
    private val observationEpoch: () -> Long = { 0L },
    private val observeResolution: (epoch: Long, unansweredNameKey: String?) -> Unit = { _, _ -> },
    private val fallback: ChainedDnsFallback? = null,
    private val dispatchCompletion: (() -> Unit) -> Unit = { it() },
) : ChainedClientDns {

    private val clientAddress: ByteArray = requireNotNull(
        ChainedTunnelResolverSelection.resolverBytes(configuration.clientAddress),
    ) {
        "a validated configuration carries a usable IPv4 client address"
    }

    /** Plain UDP DNS is the only tunnel-carried transport, so every resolver is a T0 carry. */
    private val resolvers: List<ByteArray> = ChainedTunnelResolverSelection
        .selectedResolvers(configuration)
        .mapNotNull(ChainedTunnelResolverSelection::resolverBytes)

    /**
     * Whether the active data path drops outbound IPv6. Only a FULL-tunnel chained upstream claims
     * `::/0` to blackhole v6; a split upstream leaves IPv6 direct where it works, so its AAAA must
     * NOT be suppressed and its `ipv6hint` must NOT be stripped.
     */
    private val fullTunnelChained: Boolean =
        configuration.effectiveRoutingPolicy == ChainedRoutingPolicy.FULL_TUNNEL

    private var sendQuery: (ByteArray) -> Unit = {}
    private var writeClientResponse: (ChainedDnsResponse) -> Unit = {}

    /** Pending lookups keyed by the ephemeral source port we allocated for each. */
    private val pending = LinkedHashMap<Int, PendingQuery>()

    private var closed = false

    override fun bind(
        sendQuery: (ByteArray) -> Unit,
        writeClientResponse: (ChainedDnsResponse) -> Unit,
    ) {
        this.sendQuery = sendQuery
        this.writeClientResponse = writeClientResponse
    }

    /**
     * Serves one intercepted client DNS query. Called on the engine queue from
     * `ChainedEngineSink.handleDnsQuery`.
     */
    override fun handleQuery(packet: ByteArray) {
        if (closed) return
        val query = ChainedInnerUdpPacket.parseClientDns(packet) ?: return
        if (query.destinationPort != DNS_PORT) return

        val question = try {
            DnsMessage.parseQuestion(query.payload)
        } catch (_: DnsMessageException) {
            // Fail closed with a response whenever the query is formable enough to answer; a
            // truly truncated datagram has no question to echo and is dropped.
            writeClientResponse(policy.malformedResponse(query))
            return
        }

        val admission = policy.plan(query, question)
        if (admission == null) {
            writeClientResponse(policy.unavailableResponse(query))
            return
        }
        admission.immediateResponse?.let { response ->
            writeClientResponse(policy.response(admission, response))
            return
        }

        // IPv6 consistency: a full-tunnel chained data path drops outbound IPv6, so an AAAA query
        // is answered NODATA locally BEFORE any upstream work, exactly as the iOS path does. This
        // declines a record; it never admits a blocked name (filtering is upstream of here).
        if (ChainedIpv6DnsResponsePolicy.answersWithNoData(fullTunnelChained, question.recordType)) {
            val noData = try {
                ChainedIpv6DnsResponsePolicy.noDataResponse(query.payload)
            } catch (_: DnsMessageException) {
                null
            }
            if (noData != null) {
                writeClientResponse(policy.response(admission, noData))
            } else {
                writeServfailForQuery(admission)
            }
            return
        }

        if ((resolvers.isEmpty() && (fullTunnelChained || fallback == null)) || pending.size >= maximumPendingQueries) {
            writeServfailForQuery(admission)
            return
        }
        val sourcePortForResolver = ports.claimForUdp()
        if (sourcePortForResolver == null) {
            writeServfailForQuery(admission)
            return
        }
        val entry = PendingQuery(
            identification = query.identification,
            transactionId = question.transactionId,
            questionBytes = question.questionBytes(query.payload),
            query = DnsEdns0.capTunnelPayload(query.payload),
            sourcePortForResolver = sourcePortForResolver,
            resolverIndex = 0,
            resolverAddress = resolvers.firstOrNull() ?: ByteArray(4),
            deadlineMillis = nowMillis() + queryTimeoutMillis / resolvers.size.coerceAtLeast(1),
            tierDeadlineMillis = nowMillis() + queryTimeoutMillis,
            wholeDeadlineMillis = nowMillis() + WHOLE_QUERY_TIMEOUT_MILLIS,
            startedAtMillis = nowMillis(),
            admission = admission,
            observationEpoch = observationEpoch(),
            nameKey = java.security.MessageDigest.getInstance("SHA-256")
                .digest(question.normalizedDomain.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
        pending[sourcePortForResolver] = entry
        if (resolvers.isEmpty()) retryOrFail(entry, ResolverFailureReason.SOCKET_FAILED) else sendToResolver(entry)
    }

    /**
     * Offers one decrypted inbound inner packet to the T0 egress.
     *
     * @return true when this packet belonged to a pending lookup and must NOT be written to the
     *   tunnel as ordinary decrypted traffic; false when it is an ordinary packet.
     */
    override fun observeInbound(packet: ByteArray): Boolean {
        if (closed) return false
        val parsed = ChainedInnerUdpPacket.parse(packet) ?: return false
        val entry = pending[parsed.destinationPort] ?: return false
        // The client lookup moved to its consented saved-DNS route. Retain and consume the
        // tunnel port until that lookup settles; a late T0 answer cannot double-publish it.
        if (entry.fallbackFailure != null) return true

        // Addressed to a port we hold: consume it from ordinary delivery either way. A packet that
        // is not a valid reply for this lookup is dropped, never delivered to the client and never
        // allowed to complete the lookup (a spoof must not deny the real answer).
        if (parsed.sourcePort != DNS_PORT) return true
        if (!parsed.destinationAddress.contentEquals(clientAddress)) return true
        if (!parsed.sourceAddress.contentEquals(entry.resolverAddress)) return true
        // Tuple-matched DNS replies prove resolver reachability even if their transaction is
        // wrong or their answer is truncated. Off-source packets above never count as evidence.
        if (parsed.payload.size >= DNS_HEADER_BYTES && parsed.payload[2].toInt() and QR_FLAG != 0) {
            if (!entry.sawResolverReply) {
                entry.sawResolverReply = true
                observeResolution(entry.observationEpoch, null)
            }
        }
        if (!isValidResponse(entry, parsed.payload)) return true

        // Match the DNS-only bounded wire validator. A TC response tries the remaining tunnel
        // resolvers, never TCP or physical DNS. Every rung uses the same lower-only EDNS cap.
        val validation = ResponseValidation.validate(entry.query, parsed.payload, ResolverTransport.WIREGUARD_UDP)
        if (validation is ResponseValidationResult.Invalid) {
            if (validation.terminal) {
                complete(entry)
                writeClientResponse(policy.response(entry.admission, DnsMessage.servfailResponse(entry.query)!!,
                    upstream = true, failure = validation.reason, primaryReplyOnly = true, primaryResponse = parsed.payload,
                    latencyMillis = (nowMillis() - entry.startedAtMillis).coerceAtLeast(0L)))
            } else retryOrFail(entry, validation.reason)
            return true
        }
        if ((validation as ResponseValidationResult.Valid).truncated) {
            retryOrFail(entry, ResolverFailureReason.INVALID_RESPONSE)
            return true
        }
        val classification = DnsFallbackResponse.classify(entry.query, parsed.payload)
        if (nowMillis() >= entry.tierDeadlineMillis) {
            complete(entry)
            writeClientResponse(policy.response(entry.admission, DnsMessage.servfailResponse(entry.query)!!,
                upstream = true, failure = ResolverFailureReason.TIMEOUT, primaryReplyOnly = true,
                primaryLateReply = classification.kind !in setOf(DnsFallbackResponse.Kind.MALFORMED, DnsFallbackResponse.Kind.MISMATCHED, DnsFallbackResponse.Kind.TRUNCATED),
                primaryResponse = parsed.payload,
                latencyMillis = (nowMillis() - entry.startedAtMillis).coerceAtLeast(0L)))
            return true
        }

        if (classification.kind == DnsFallbackResponse.Kind.NEGATIVE && !fullTunnelChained && fallback != null &&
            DnsPortalTierFallback.eligible(entry.query, parsed.payload, fallback.context.copy(
                permittedPortalHosts = policy.permittedPortalHosts(entry.admission)))) {
            entry.firstFailureResponse = parsed.payload.copyOf()
            entry.portalNegative = true
            startFallback(entry, ResolverFailureReason.INVALID_RESPONSE)
            return true
        }
        if (classification.kind !in setOf(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.Kind.NEGATIVE)) {
            if (entry.firstFailureResponse == null && classification.kind in setOf(DnsFallbackResponse.Kind.TRANSIENT_FAILURE,
                    DnsFallbackResponse.Kind.AMBIGUOUS_FAILURE, DnsFallbackResponse.Kind.PROHIBITED)) entry.firstFailureResponse = parsed.payload.copyOf()
            if (classification.kind == DnsFallbackResponse.Kind.TRANSIENT_FAILURE || classification.kind == DnsFallbackResponse.Kind.MALFORMED) {
                retryOrFail(entry, ResolverFailureReason.INVALID_RESPONSE)
            } else {
                // A bare error or explicit policy/DNSSEC verdict cannot authorize another view.
                complete(entry)
                writeClientResponse(policy.response(entry.admission, parsed.payload, upstream = true,
                    primaryReplyOnly = true, latencyMillis = (nowMillis() - entry.startedAtMillis).coerceAtLeast(0L)))
            }
            return true
        }

        // Full tunnel only: surgically strip the ipv6hint SvcParam from HTTPS/SVCB answers. A
        // malformed RDATA makes `stripIpv6Hints` return the response unchanged.
        val rewritten = ChainedIpv6DnsResponsePolicy.stripIpv6Hints(
            parsed.payload,
            fullTunnelChained,
        )
        complete(entry)
        writeClientResponse(
            policy.response(
                entry.admission, rewritten, upstream = true,
                latencyMillis = (nowMillis() - entry.startedAtMillis).coerceAtLeast(0L),
            ),
        )
        return true
    }

    /**
     * Advances the pending lookups: a timed-out one is retried against the next selected resolver,
     * and once the selection is exhausted the client gets a fail-closed SERVFAIL.
     */
    override fun expire() {
        if (closed || pending.isEmpty()) return
        val now = nowMillis()
        for (entry in pending.values.toList()) {
            if (now < entry.deadlineMillis) continue
            if (entry.fallbackFailure != null) {
                complete(entry)
                writeServfailToEntry(entry, requireNotNull(entry.fallbackFailure))
            } else retryOrFail(entry, ResolverFailureReason.TIMEOUT)
        }
    }

    private fun retryOrFail(entry: PendingQuery, failure: ResolverFailureReason) {
        val nextIndex = entry.resolverIndex + 1
        if (nextIndex < resolvers.size && nowMillis() < entry.tierDeadlineMillis) {
            entry.resolverIndex = nextIndex
            entry.resolverAddress = resolvers[nextIndex]
            entry.deadlineMillis = minOf(entry.tierDeadlineMillis, nowMillis() + (entry.tierDeadlineMillis - nowMillis()) / (resolvers.size - nextIndex))
            sendToResolver(entry)
        } else {
            // Saved-DNS success cannot prove the WireGuard resolver is alive. Preserve this
            // T0 observation before starting fallback, including while it is still pending.
            if (entry.t0PacketSent && !entry.sawResolverReply) observeResolution(entry.observationEpoch, entry.nameKey)
            // Reporting T0 outage can synchronously retire this owner through its driver.
            if (closed || pending[entry.sourcePortForResolver] !== entry) return
            if (!fullTunnelChained && fallback != null && !failure.isLifecycleFailure() && nowMillis() < entry.wholeDeadlineMillis) {
                startFallback(entry, failure)
            } else {
                complete(entry)
                writeServfailToEntry(entry, failure)
            }
        }
    }

    private fun startFallback(entry: PendingQuery, failure: ResolverFailureReason) {
        entry.fallbackFailure = failure
        entry.deadlineMillis = entry.wholeDeadlineMillis
        val sent = policy.forward(entry.admission, send = {
            try {
                val handle = requireNotNull(fallback).resolve(entry.query.copyOf(),
                    (entry.wholeDeadlineMillis - nowMillis()).coerceAtLeast(0) * 1_000_000L,
                    policy.permittedPortalHosts(entry.admission), entry.portalNegative) { result ->
                    dispatchCompletion { completeFallback(entry, result) }
                }
                // A cold adapter may complete inline. Do not install a returned handle into a
                // lookup already settled by that completion; release it immediately instead.
                if (!closed && pending[entry.sourcePortForResolver] === entry) entry.fallbackHandle = handle
                else runCatching { handle.close() }
            } catch (_: CancellationException) {
                if (pending[entry.sourcePortForResolver] === entry) {
                    complete(entry)
                    writeClientResponse(policy.unavailableResponse(entry.admission.datagram))
                }
            } catch (_: Exception) {
                if (pending[entry.sourcePortForResolver] === entry) {
                    complete(entry)
                    writeServfailToEntry(entry, failure)
                }
            }
        }, respond = writeClientResponse)
        if (!sent) complete(entry)
    }

    private fun completeFallback(entry: PendingQuery, result: ResolverResult) {
        if (closed || pending[entry.sourcePortForResolver] !== entry) return
        val primaryFailure = entry.fallbackFailure ?: return
        if (nowMillis() >= entry.wholeDeadlineMillis) {
            complete(entry)
            writeServfailToEntry(entry, ResolverFailureReason.TIMEOUT, result.resolverQueryEvidence)
            return
        }
        // Lifecycle cancellation is an authority loss, never another retry or fallback success.
        if (result is ResolverResult.Failure && result.reason.isLifecycleFailure()) {
            complete(entry)
            writeClientResponse(policy.unavailableResponse(entry.admission.datagram))
            return
        }
        if (result !is ResolverResult.Answer || result.transport == ResolverTransport.WIREGUARD_UDP) {
            complete(entry)
            writeServfailToEntry(entry, primaryFailure, result.resolverQueryEvidence)
            return
        }
        val response = result.dnsMessage
        // The adapter already owns the physical UDP source/endpoint proof. Revalidate bounded
        // wire structure here without inventing a second endpoint observation at this layer.
        val wireTransport = when (result.transport) {
            ResolverTransport.DEVICE_UDP, ResolverTransport.PLAIN_UDP -> ResolverTransport.WIREGUARD_UDP
            else -> result.transport
        }
        val validation = ResponseValidation.validate(entry.query, response, wireTransport)
        val code = DnsEdns0.fullResponseCode(response)
        // iOS a6141541 ResolverOrchestrator.shouldSelectFallback: the first enabled tier
        // completing this scoped lookup serves it. A selected fixed/encrypted positive must
        // not force another Device query; its actual transport retains ordinary tier credit.
        if (entry.portalNegative && !DnsPortalTierFallback.rescueAnswer(entry.query, response,
                requireNotNull(fallback).context.copy(permittedPortalHosts = policy.permittedPortalHosts(entry.admission)))) {
            complete(entry)
            writeServfailToEntry(entry, primaryFailure, result.resolverQueryEvidence)
            return
        }
        if (!isValidResponse(entry, response) || validation !is ResponseValidationResult.Valid ||
            validation.truncated || (code != 0 && code != 3)) {
            complete(entry)
            writeServfailToEntry(entry, primaryFailure, result.resolverQueryEvidence)
            return
        }
        val clientResponse = ResolverReplyBoundary.boundForClient(entry.query, response)
        if (clientResponse == null) {
            complete(entry)
            writeServfailToEntry(entry, primaryFailure, result.resolverQueryEvidence)
            return
        }
        complete(entry)
        writeClientResponse(policy.response(
            entry.admission, clientResponse, upstream = true,
            latencyMillis = (nowMillis() - entry.startedAtMillis).coerceAtLeast(0L),
            fallbackTransport = result.transport, primaryFailure = primaryFailure,
            fallbackEvidence = result.resolverQueryEvidence,
            primaryServedNegative = entry.portalNegative,
            primaryResponse = entry.firstFailureResponse,
            primaryLocal = !entry.t0PacketSent,
            portalDisposition = if (entry.portalNegative) com.lavasec.app.foundation.health.DnsFallbackDisposition.SERVED else null,
        ))
    }

    private fun ResolverFailureReason.isLifecycleFailure(): Boolean =
        this == ResolverFailureReason.STALE_EPOCH || this == ResolverFailureReason.CANCELLED

    override fun close() {
        if (closed) return
        closed = true
        for (entry in pending.values.toList()) complete(entry)
        runCatching { fallback?.close() }
        policy.close()
    }

    private fun sendToResolver(entry: PendingQuery) {
        val sent = policy.forward(entry.admission, send = {
            sendQuery(
                ChainedInnerUdpPacket.build(
                    sourceAddress = clientAddress,
                    destinationAddress = entry.resolverAddress,
                    sourcePort = entry.sourcePortForResolver,
                    destinationPort = DNS_PORT,
                    identification = entry.identification,
                    payload = entry.query,
                ),
            )
            entry.t0PacketSent = true
        }, respond = writeClientResponse)
        if (!sent) complete(entry)
    }

    private fun complete(entry: PendingQuery) {
        if (pending[entry.sourcePortForResolver] !== entry) return
        pending.remove(entry.sourcePortForResolver)
        val handle = entry.fallbackHandle
        entry.fallbackHandle = null
        runCatching { handle?.close() }
        ports.release(entry.sourcePortForResolver)
    }

    private fun writeServfailForQuery(query: ChainedDnsQuery) {
        val failure = DnsMessage.servfailResponse(query.datagram.payload) ?: return
        writeClientResponse(policy.response(query, failure, upstream = true, failure = ResolverFailureReason.OVERLOADED, primaryLocal = true, primaryResponseIsSynthetic = true))
    }

    private fun writeServfailToEntry(
        entry: PendingQuery,
        reason: ResolverFailureReason = ResolverFailureReason.TIMEOUT,
        fallbackEvidence: com.lavasec.app.foundation.health.ResolverQueryHealthEvidence? = null,
    ) {
        val failure = entry.firstFailureResponse ?: DnsMessage.servfailResponse(entry.query) ?: return
        writeClientResponse(
            policy.response(
                entry.admission, failure, upstream = true,
                failure = if (entry.portalNegative) null else reason, fallbackEvidence = fallbackEvidence,
                primaryServedNegative = entry.portalNegative,
                primaryResponse = entry.firstFailureResponse,
                primaryResponseIsSynthetic = entry.firstFailureResponse == null,
                primaryLocal = !entry.t0PacketSent,
                portalDisposition = if (entry.portalNegative) com.lavasec.app.foundation.health.DnsFallbackDisposition.PRESERVED_NEGATIVE else null,
                latencyMillis = (nowMillis() - entry.startedAtMillis).coerceAtLeast(0L),
            ),
        )
    }

    /**
     * Whether [response] is a valid DNS reply for [entry]: a response (QR=1) to the same
     * transaction with the identical, single question. This is the Android form of the iOS
     * `DNSWireMessage.isValidResponse` anti-spoof gate.
     */
    private fun isValidResponse(entry: PendingQuery, response: ByteArray): Boolean {
        if (response.size < DNS_HEADER_BYTES) return false
        if (response[2].toInt() and QR_FLAG == 0) return false
        if (readUInt16(response, 0) != entry.transactionId) return false
        if (((readUInt16(response, 2) ushr OPCODE_SHIFT) and OPCODE_MASK) != QUERY_OPCODE) {
            return false
        }
        if (readUInt16(response, 4) != 1) return false
        if (response.size < DNS_HEADER_BYTES + entry.questionBytes.size) return false
        return response.copyOfRange(
            DNS_HEADER_BYTES,
            DNS_HEADER_BYTES + entry.questionBytes.size,
        ).contentEquals(entry.questionBytes)
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    /** One in-flight client lookup and the resolver it is currently addressed to. */
    private class PendingQuery(
        val identification: Int,
        val transactionId: Int,
        val questionBytes: ByteArray,
        val query: ByteArray,
        val sourcePortForResolver: Int,
        var resolverIndex: Int,
        var resolverAddress: ByteArray,
        var deadlineMillis: Long,
        val tierDeadlineMillis: Long,
        val wholeDeadlineMillis: Long,
        val startedAtMillis: Long,
        val admission: ChainedDnsQuery,
        val observationEpoch: Long,
        val nameKey: String,
        var sawResolverReply: Boolean = false,
        var t0PacketSent: Boolean = false,
        var firstFailureResponse: ByteArray? = null,
        var portalNegative: Boolean = false,
        var fallbackFailure: ResolverFailureReason? = null,
        var fallbackHandle: AutoCloseable? = null,
    )

    private companion object {
        const val DNS_PORT = 53
        const val DNS_HEADER_BYTES = 12
        const val QR_FLAG = 0x80
        const val OPCODE_SHIFT = 11
        const val OPCODE_MASK = 0x0f
        const val QUERY_OPCODE = 0

        const val DEFAULT_MAXIMUM_PENDING_QUERIES = 64
        const val DEFAULT_QUERY_TIMEOUT_MILLIS = 1_800L
        const val WHOLE_QUERY_TIMEOUT_MILLIS = 12_000L
    }
}
