package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsDecision
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.DnsMessageException
import com.lavasec.app.foundation.dns.DnsQuestion
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.health.PrimaryResolverOutcome
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.pause.PausePolicy
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.LazyThreadSafetyMode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ProxyReplacementResponse internal constructor(packet: ByteArray) {
    init {
        require(packet.isNotEmpty()) { "replacement packet must not be empty" }
        require(packet.size <= DnsOnlyTunContract.MTU) {
            "replacement packet exceeds the TUN MTU"
        }
    }

    private val wirePacket = packet.copyOf()

    fun snapshot(): ByteArray = wirePacket.copyOf()
}

internal sealed interface ProxyPlan {
    data object Drop : ProxyPlan

    class Immediate internal constructor(
        val result: ProxyResult.Pending,
        val replacement: ProxyReplacementResponse,
    ) : ProxyPlan

    class Forward private constructor(
        val request: ResolverRequest,
        val activity: DnsDecisionEvent,
        val policyIdentity: ProxyPolicyIdentity,
        val plannedProtectionSessionId: Long,
        val plannedPauseMaximumResponseTtlSeconds: Long?,
        val startedPaused: Boolean,
        val replacement: ProxyReplacementResponse,
        internal val engine: ReadyDnsEngine,
        internal val datagram: Ipv4UdpDnsPacket,
        private val owner: DnsProxy,
    ) : ProxyPlan {
        private val consumed = AtomicBoolean(false)

        internal fun tryConsume(expectedOwner: DnsProxy): Boolean =
            expectedOwner === owner && consumed.compareAndSet(false, true)

        companion object {
            internal fun create(
                request: ResolverRequest,
                activity: DnsDecisionEvent,
                policyIdentity: ProxyPolicyIdentity,
                plannedProtectionSessionId: Long,
                plannedPauseMaximumResponseTtlSeconds: Long?,
                startedPaused: Boolean,
                replacement: ProxyReplacementResponse,
                engine: ReadyDnsEngine,
                datagram: Ipv4UdpDnsPacket,
                owner: DnsProxy,
            ): Forward {
                require(activity.id >= 0L) { "decision event id must be non-negative" }
                require(activity.timestampMillis >= 0L) {
                    "decision event timestamp must be non-negative"
                }
                require(request.queryId == activity.id) {
                    "resolver query id must equal the decision event id"
                }
                require(plannedProtectionSessionId > 0L) {
                    "planned protection session id must be positive"
                }
                require(
                    plannedPauseMaximumResponseTtlSeconds == null ||
                        plannedPauseMaximumResponseTtlSeconds >= 0L,
                ) {
                    "planned pause TTL cap must be non-negative"
                }
                require(startedPaused || plannedPauseMaximumResponseTtlSeconds == null) {
                    "a non-paused forward cannot carry a pause TTL cap"
                }
                require(request.epoch === engine.epoch) {
                    "forward request must retain the exact engine epoch"
                }
                require(request.networkContext === engine.networkContext) {
                    "forward request must retain the exact engine network context"
                }
                require(policyIdentity === engine.policyIdentity) {
                    "forward plan must retain the exact engine policy identity"
                }
                require(engine.proxy === owner) {
                    "forward plan owner must be the engine-paired proxy"
                }
                return Forward(
                    request = request,
                    activity = activity,
                    policyIdentity = policyIdentity,
                    plannedProtectionSessionId = plannedProtectionSessionId,
                    plannedPauseMaximumResponseTtlSeconds =
                        plannedPauseMaximumResponseTtlSeconds,
                    startedPaused = startedPaused,
                    replacement = replacement,
                    engine = engine,
                    datagram = datagram,
                    owner = owner,
                )
            }
        }
    }
}

internal interface DnsProxy {
    fun plan(packet: ByteArray, engine: ReadyDnsEngine): ProxyPlan

    fun planUnavailable(
        packet: ByteArray,
        reason: ProtectionUnavailableReason,
    ): ProxyPlan

    fun rejectOverloaded(plan: ProxyPlan.Forward): ProxyResult

    suspend fun execute(plan: ProxyPlan.Forward): ProxyResult
}

internal class DefaultDnsProxy(
    private val policySource: ProxyPolicySource,
    private val eventIds: DecisionEventIdSource,
    private val wallClock: EpochMillisClock,
    private val monotonicClock: MonotonicClock,
    private val publicationSink: ProxyPublicationSink,
    private val unavailablePublicationFence: ProxyPublicationFence,
) : DnsProxy {
    override fun plan(packet: ByteArray, engine: ReadyDnsEngine): ProxyPlan {
        if (engine.proxy !== this) return ProxyPlan.Drop

        val datagram = Ipv4UdpDnsPacket.parse(packet) ?: return ProxyPlan.Drop
        if (!datagram.isSyntheticDnsDestination) return ProxyPlan.Drop

        val query = datagram.payload
        val question = try {
            DnsMessage.parseQuestion(query)
        } catch (_: DnsMessageException) {
            val response = DnsMessage.servfailResponse(query) ?: return ProxyPlan.Drop
            return immediate(
                datagram = datagram,
                response = response,
                replacement = replacement(datagram, response),
                activity = null,
                healthEvents = emptyList(),
                fence = engine,
            )
        }
        val replacement = replacement(
            datagram,
            servfailResponse(query, question),
        )

        if (!hasCoherentResolverIdentity(engine)) {
            return runtimeUnavailable(datagram, query, question, replacement, engine)
        }

        val bootstrap = if (engine.resolverPlan.mullvadEnabled) {
            MullvadDohEndpoint.bootstrapResponse(query, question)
        } else {
            null
        }
        if (bootstrap != null) {
            return immediate(
                datagram = datagram,
                response = bootstrap,
                replacement = replacement,
                activity = null,
                healthEvents = emptyList(),
                fence = engine,
            )
        }

        val policyRead = try {
            policySource.withCurrent { snapshot ->
                validatePolicy(engine, snapshot)
                planWithPolicy(
                    datagram = datagram,
                    query = query,
                    question = question,
                    replacement = replacement,
                    engine = engine,
                    snapshot = snapshot,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ProxyPolicyRead.Unavailable
        }
        return when (policyRead) {
            is ProxyPolicyRead.Available -> policyRead.value
            ProxyPolicyRead.Unavailable ->
                runtimeUnavailable(datagram, query, question, replacement, engine)
        }
    }

    private fun planWithPolicy(
        datagram: Ipv4UdpDnsPacket,
        query: ByteArray,
        question: DnsQuestion,
        replacement: ProxyReplacementResponse,
        engine: ReadyDnsEngine,
        snapshot: ProxyPolicySnapshot,
    ): ProxyPlan {
        val decisionTimeMillis = lazy(LazyThreadSafetyMode.NONE) {
            wallClock.nowMillis().also { nowMillis ->
                require(nowMillis >= 0L) { "decision timestamp must be non-negative" }
            }
        }
        val outcome = DnsQueryDispatcher.dispatch(
            bootstrapResponse = { null },
            pausedDecision = {
                val paused = snapshot.pause as? RuntimePauseDirective.Paused
                    ?: return@dispatch null
                val nowMillis = decisionTimeMillis.value
                if (!snapshot.pause.isActive(
                        nowMillis,
                        snapshot.activeProtectionSessionId,
                    )
                ) {
                    return@dispatch null
                }
                val wouldBeDecision = decide(snapshot, question.normalizedDomain)
                PausePolicy.queryDecision(
                    pauseState = paused.state,
                    nowMillis = nowMillis,
                    activeProtectionSessionId = snapshot.activeProtectionSessionId,
                    wouldBeDecision = wouldBeDecision,
                )
            },
            filterDecision = {
                evaluate(query, question, snapshot)
            },
        )
        return when (outcome) {
            is DnsQueryDispatchOutcome.Bootstrap -> immediate(
                datagram = datagram,
                response = outcome.response,
                replacement = replacement,
                activity = null,
                healthEvents = emptyList(),
                fence = engine,
            )

            is DnsQueryDispatchOutcome.Paused -> {
                val activity = activity(
                    question.normalizedDomain,
                    outcome.decision.decision,
                    decisionTimeMillis.value,
                )
                forward(
                    datagram = datagram,
                    query = query,
                    question = question,
                    activity = activity,
                    policy = snapshot,
                    engine = engine,
                    startedPaused = true,
                    pauseMaximumResponseTtlSeconds =
                        outcome.decision.maximumResponseTtlSeconds,
                    replacement = replacement,
                )
            }

            is DnsQueryDispatchOutcome.Filter -> when (val decision = outcome.decision) {
                is DnsDecision.Forward -> {
                    val activity = activity(
                        decision.question.normalizedDomain,
                        decision.filterDecision,
                        decisionTimeMillis.value,
                    )
                    forward(
                        datagram = datagram,
                        query = query,
                        question = decision.question,
                        activity = activity,
                        policy = snapshot,
                        engine = engine,
                        startedPaused = false,
                        pauseMaximumResponseTtlSeconds = null,
                        replacement = replacement,
                    )
                }

                is DnsDecision.Respond -> {
                    val activity = activity(
                        decision.question.normalizedDomain,
                        decision.filterDecision,
                        decisionTimeMillis.value,
                    )
                    immediate(
                        datagram = datagram,
                        response = decision.response,
                        replacement = replacement,
                        activity = activity,
                        healthEvents = emptyList(),
                        fence = engine,
                    )
                }

                is DnsDecision.Failure -> {
                    val response = decision.response ?: return ProxyPlan.Drop
                    immediate(
                        datagram = datagram,
                        response = response,
                        replacement = replacement,
                        activity = null,
                        healthEvents = emptyList(),
                        fence = engine,
                    )
                }
            }
        }
    }

    override fun planUnavailable(
        packet: ByteArray,
        reason: ProtectionUnavailableReason,
    ): ProxyPlan {
        val datagram = Ipv4UdpDnsPacket.parse(packet) ?: return ProxyPlan.Drop
        if (!datagram.isSyntheticDnsDestination) return ProxyPlan.Drop

        val query = datagram.payload
        val question = try {
            DnsMessage.parseQuestion(query)
        } catch (_: DnsMessageException) {
            val response = DnsMessage.servfailResponse(query) ?: return ProxyPlan.Drop
            return immediate(
                datagram = datagram,
                response = response,
                replacement = replacement(datagram, response),
                activity = null,
                healthEvents = emptyList(),
                fence = unavailablePublicationFence,
            )
        }
        val replacement = replacement(
            datagram,
            servfailResponse(query, question),
        )

        val response = when (reason) {
            is ProtectionUnavailableReason.Artifact ->
                DnsMessage.blockedResponse(query, question, ttl = 1L)

            is ProtectionUnavailableReason.ResolverPlan,
            ProtectionUnavailableReason.NetworkUnavailable,
            ProtectionUnavailableReason.RuntimeUnavailable,
            -> servfailResponse(query, question)
        }
        return immediate(
            datagram = datagram,
            response = response,
            replacement = replacement,
            activity = null,
            healthEvents = listOf(DnsHealthEvent.ProtectionUnavailable(reason)),
            fence = unavailablePublicationFence,
        )
    }

    override fun rejectOverloaded(plan: ProxyPlan.Forward): ProxyResult {
        if (!plan.tryConsume(this)) return ProxyResult.NotPublishable
        if (!plan.engine.isCurrent()) return ProxyResult.NotPublishable
        return ProxyResult.Pending(
            fence = plan.engine,
            publication = ProxyPublication(
                responsePacket = plan.replacement.snapshot(),
                activity = plan.activity.copy(
                    resolver = plan.engine.resolverPlan.primaryResolverKind,
                    transport = null,
                    latencyMillis = null,
                    error = ResolverFailureReason.OVERLOADED,
                ),
                healthEvents = emptyList(),
                resolverQueryEvidence = null,
            ),
            sink = publicationSink,
        )
    }

    override suspend fun execute(plan: ProxyPlan.Forward): ProxyResult {
        if (!plan.tryConsume(this)) return ProxyResult.NotPublishable

        val engine = plan.engine
        if (!engine.isCurrent()) return ProxyResult.NotPublishable

        val startedAtNanos = monotonicClock.nowNanos()
        val outcome = try {
            val result = engine.resolveIfCurrent(plan.request)
            if (result.isLifecycleFailure()) {
                currentCoroutineContext().ensureActive()
                return ProxyResult.NotPublishable
            }
            val completedAtNanos = monotonicClock.nowNanos()
            currentCoroutineContext().ensureActive()
            ExecutionOutcome.Resolved(
                result = result,
                latencyMillis = elapsedMillis(startedAtNanos, completedAtNanos),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ExecutionOutcome.UnexpectedFailure(
                latencyMillis = elapsedMillis(startedAtNanos, monotonicClock.nowNanos()),
                failure = failure,
            )
        }

        currentCoroutineContext().ensureActive()
        if (!engine.isCurrent()) return ProxyResult.NotPublishable
        val publicationRead = try {
            policySource.withCurrent { completionPolicy ->
                if (!completionPolicyMatches(plan, completionPolicy)) {
                    null
                } else {
                    try {
                        terminalPublication(plan, outcome, completionPolicy)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        runtimeUnavailablePublication(plan)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return ProxyResult.NotPublishable
        }
        val publication = when (publicationRead) {
            is ProxyPolicyRead.Available ->
                publicationRead.value ?: return ProxyResult.NotPublishable
            ProxyPolicyRead.Unavailable -> return ProxyResult.NotPublishable
        }

        currentCoroutineContext().ensureActive()
        if (!engine.isCurrent()) return ProxyResult.NotPublishable
        currentCoroutineContext().ensureActive()
        return ProxyResult.Pending(
            fence = engine,
            publication = publication,
            sink = publicationSink,
        )
    }

    private fun hasCoherentResolverIdentity(engine: ReadyDnsEngine): Boolean {
        val epoch = engine.epoch
        val context = engine.networkContext
        val plan = engine.resolverPlan
        return context.networkGeneration == epoch.networkGeneration &&
            plan.network === context.network &&
            plan.networkGeneration == context.networkGeneration &&
            plan.isCellular == context.isCellular &&
            plan.primaryResolverIdentity == epoch.primaryResolverIdentity &&
            plan.fallbackCoverage == epoch.fallbackCoverage
    }

    private fun validatePolicy(
        engine: ReadyDnsEngine,
        snapshot: ProxyPolicySnapshot,
    ) {
        val identity = snapshot.identity
        val engineIdentity = engine.policyIdentity
        val epoch = engine.epoch
        if (
            identity != engineIdentity ||
            engineIdentity.configurationIdentity != epoch.configurationIdentity ||
            engineIdentity.artifactDigest != epoch.artifactDigest ||
            engineIdentity.revision != epoch.policyRevision
        ) {
            throw PlanningUnavailableException()
        }
    }

    private fun evaluate(
        query: ByteArray,
        question: DnsQuestion,
        snapshot: ProxyPolicySnapshot,
    ): DnsDecision {
        val decision = try {
            DnsDecision.evaluate(query, question, snapshot.decisions)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw PlanningUnavailableException(failure)
        }
        val filterDecision = when (decision) {
            is DnsDecision.Forward -> decision.filterDecision
            is DnsDecision.Respond -> decision.filterDecision
            is DnsDecision.Failure -> return decision
        }
        if (filterDecision.reason == FilterDecisionReason.PROTECTION_UNAVAILABLE) {
            throw PlanningUnavailableException()
        }
        return decision
    }

    private fun decide(
        snapshot: ProxyPolicySnapshot,
        normalizedDomain: String,
    ): FilterDecision {
        val decision = try {
            snapshot.decisions.decideNormalized(normalizedDomain)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw PlanningUnavailableException(failure)
        }
        if (decision.reason == FilterDecisionReason.PROTECTION_UNAVAILABLE) {
            throw PlanningUnavailableException()
        }
        return decision
    }

    private fun activity(
        normalizedDomain: String,
        decision: FilterDecision,
        timestampMillis: Long,
    ): DnsDecisionEvent {
        val id = eventIds.nextId()
        require(id >= 0L) { "decision event id must be non-negative" }
        return DnsDecisionEvent(
            id = id,
            timestampMillis = timestampMillis,
            normalizedDomain = normalizedDomain,
            decision = decision,
        )
    }

    private fun forward(
        datagram: Ipv4UdpDnsPacket,
        query: ByteArray,
        question: DnsQuestion,
        activity: DnsDecisionEvent,
        policy: ProxyPolicySnapshot,
        engine: ReadyDnsEngine,
        startedPaused: Boolean,
        pauseMaximumResponseTtlSeconds: Long?,
        replacement: ProxyReplacementResponse,
    ): ProxyPlan.Forward {
        val request = ResolverRequest(
            query = query,
            question = question,
            epoch = engine.epoch,
            networkContext = engine.networkContext,
            queryId = activity.id,
            wholeQueryDeadlineNanos = monotonicClock.nowNanos().let { now ->
                if (now > Long.MAX_VALUE - ResolverExecutionBudget.WHOLE_QUERY_NANOS) Long.MAX_VALUE else now + ResolverExecutionBudget.WHOLE_QUERY_NANOS
            },
            fallbackContext = DnsFallbackQueryContext(
                isSatisfiedWifi = engine.networkContext.isWifi,
                hasEnabledDeviceTier = engine.resolverPlan.hasDeviceRoute,
                hasCurrentDeviceCapture = engine.networkContext.dnsServers.isNotEmpty(),
                permitsPhysicalDeviceDns = true,
                portalNetworkIsCurrent = { engine.isCurrent() && engine.networkContext.portalNetworkIsCurrent() },
                permittedPortalHosts = if (engine.networkContext.isWifi && engine.resolverPlan.hasDeviceRoute &&
                    engine.networkContext.dnsServers.isNotEmpty() && question.rawRecordType in setOf(1, 28) &&
                    question.normalizedDomain in DnsPortalTierFallback.registeredHosts) {
                    DnsPortalTierFallback.registeredHosts.filter { host ->
                        val decision = decide(policy, host)
                        decision.action == com.lavasec.app.foundation.filter.FilterAction.ALLOW
                    }.toSet()
                } else emptySet(),
            ),
        )
        return ProxyPlan.Forward.create(
            request = request,
            activity = activity,
            policyIdentity = engine.policyIdentity,
            plannedProtectionSessionId = policy.activeProtectionSessionId,
            plannedPauseMaximumResponseTtlSeconds = pauseMaximumResponseTtlSeconds,
            startedPaused = startedPaused,
            replacement = replacement,
            engine = engine,
            datagram = datagram,
            owner = this,
        )
    }

    private fun completionPolicyMatches(
        plan: ProxyPlan.Forward,
        snapshot: ProxyPolicySnapshot,
    ): Boolean {
        val identity = snapshot.identity
        val engine = plan.engine
        val epoch = engine.epoch
        if (
            identity != plan.policyIdentity ||
            identity != engine.policyIdentity ||
            identity.configurationIdentity != epoch.configurationIdentity ||
            identity.artifactDigest != epoch.artifactDigest ||
            identity.revision != epoch.policyRevision ||
            snapshot.activeProtectionSessionId != plan.plannedProtectionSessionId
        ) {
            return false
        }
        return true
    }

    private fun terminalPublication(
        plan: ProxyPlan.Forward,
        outcome: ExecutionOutcome,
        completionPolicy: ProxyPolicySnapshot,
    ): ProxyPublication {
        return when (outcome) {
            is ExecutionOutcome.UnexpectedFailure ->
                runtimeUnavailablePublication(plan)

            is ExecutionOutcome.Resolved -> when (val result = outcome.result) {
                is ResolverResult.Answer -> answerPublication(
                    plan = plan,
                    answer = result,
                    latencyMillis = outcome.latencyMillis,
                    completionPolicy = completionPolicy,
                )

                is ResolverResult.Failure -> failurePublication(
                    plan = plan,
                    failure = result,
                    latencyMillis = outcome.latencyMillis,
                )
            }
        }
    }

    private fun answerPublication(
        plan: ProxyPlan.Forward,
        answer: ResolverResult.Answer,
        latencyMillis: Long,
        completionPolicy: ProxyPolicySnapshot,
    ): ProxyPublication {
        val evidence = answer.resolverQueryEvidence
        if (!answerEvidenceIsCoherent(plan, answer.transport, evidence)) {
            return runtimeUnavailablePublication(plan)
        }
        var response = answer.dnsMessage
        var activity = plan.activity.copy(
            resolver = answer.transport.resolverKind(),
            transport = answer.transport,
            latencyMillis = latencyMillis,
            error = null,
        )

        if (plan.startedPaused) {
            val nowMillis = wallClock.nowMillis()
            require(nowMillis >= 0L) { "completion timestamp must be non-negative" }
            if (
                completionPolicy.pause.isActive(
                    nowMillis,
                    completionPolicy.activeProtectionSessionId,
                )
            ) {
                plan.plannedPauseMaximumResponseTtlSeconds?.let { maximum ->
                    response = DnsResponseTtlCap.capCacheableTtls(response, maximum)
                        ?: return runtimeUnavailablePublication(plan)
                }
            } else {
                when (
                    val decision = evaluate(
                        plan.request.query,
                        plan.request.question,
                        completionPolicy,
                    )
                ) {
                    is DnsDecision.Forward -> Unit
                    is DnsDecision.Respond -> {
                        response = decision.response
                        activity = plan.activity.copy(
                            decision = decision.filterDecision,
                            resolver = null,
                            transport = null,
                            latencyMillis = null,
                            error = null,
                        )
                    }

                    is DnsDecision.Failure ->
                        return runtimeUnavailablePublication(plan)
                }
            }
        }

        val clientResponse = ResolverReplyBoundary.boundForClient(plan.request.query, response)
            ?: return runtimeUnavailablePublication(plan)
        return ProxyPublication(
            responsePacket = plan.datagram.response(clientResponse),
            activity = activity,
            healthEvents = answer.healthEvents,
            resolverQueryEvidence = evidence,
            primaryAnswerBearing = activity.transport != null && evidence != null &&
                evidence.primaryOutcome == PrimaryResolverOutcome.ACCEPTED &&
                evidence.successfulFallback == ProtectionFallback.NONE &&
                DnsResolverSmokeProbe.classify(plan.request.query, clientResponse) ==
                    DnsResolverSmokeProbe.Reply.ACCEPTED_ANSWER,
        )
    }

    private fun failurePublication(
        plan: ProxyPlan.Forward,
        failure: ResolverResult.Failure,
        latencyMillis: Long,
    ): ProxyPublication {
        val evidence = failure.resolverQueryEvidence
        val resolver = failure.resolver
        if (!failureEvidenceIsCoherent(plan, resolver, evidence)) {
            return runtimeUnavailablePublication(plan)
        }
        return ProxyPublication(
            responsePacket = plan.datagram.response(
                servfailResponse(plan.request.query, plan.request.question),
            ),
            activity = plan.activity.copy(
                resolver = resolver,
                transport = null,
                latencyMillis = latencyMillis,
                error = failure.reason,
            ),
            healthEvents = failure.healthEvents,
            resolverQueryEvidence = evidence,
        )
    }

    private fun runtimeUnavailablePublication(
        plan: ProxyPlan.Forward,
    ): ProxyPublication =
        ProxyPublication(
            responsePacket = plan.datagram.response(
                servfailResponse(plan.request.query, plan.request.question),
            ),
            activity = null,
            healthEvents = listOf(
                DnsHealthEvent.ProtectionUnavailable(
                    ProtectionUnavailableReason.RuntimeUnavailable,
                ),
            ),
            resolverQueryEvidence = null,
        )

    private fun answerEvidenceIsCoherent(
        plan: ProxyPlan.Forward,
        transport: ResolverTransport,
        evidence: ResolverQueryHealthEvidence?,
    ): Boolean {
        evidence ?: return false
        if (evidence.queryId != plan.request.queryId) return false
        val terminalResolver = transport.resolverKind()
        return when (evidence.primaryOutcome) {
            PrimaryResolverOutcome.ACCEPTED ->
                evidence.successfulFallback == ProtectionFallback.NONE &&
                    terminalResolver == plan.engine.resolverPlan.primaryResolverKind

            PrimaryResolverOutcome.FAILED,
            PrimaryResolverOutcome.REJECTED,
            -> evidence.successfulFallback != ProtectionFallback.NONE &&
                fallbackIsCovered(
                    plan = plan.engine.resolverPlan,
                    fallback = evidence.successfulFallback,
                    terminalResolver = terminalResolver,
                )
        }
    }

    private fun failureEvidenceIsCoherent(
        plan: ProxyPlan.Forward,
        resolver: ResolverKind?,
        evidence: ResolverQueryHealthEvidence?,
    ): Boolean {
        resolver ?: return false
        evidence ?: return false
        return evidence.queryId == plan.request.queryId &&
            evidence.primaryOutcome != PrimaryResolverOutcome.ACCEPTED &&
            evidence.successfulFallback == ProtectionFallback.NONE &&
            resolverIsPlausible(plan.engine.resolverPlan, resolver)
    }

    private fun fallbackIsCovered(
        plan: ResolverPlan,
        fallback: ProtectionFallback,
        terminalResolver: ResolverKind,
    ): Boolean = when (fallback) {
        ProtectionFallback.PLAIN_DNS -> plan.fallbackCoverage == ResolverFallbackCoverage.PLAIN_DNS &&
            plan.orderedRoutes?.getOrNull(1)?.primaryResolverKind == ResolverKind.PLAIN_DNS && terminalResolver == ResolverKind.PLAIN_DNS
        ProtectionFallback.NONE -> false
        ProtectionFallback.ENCRYPTED_DNS ->
            plan.fallbackCoverage == ResolverFallbackCoverage.ENCRYPTED_DNS &&
                plan.mullvadEnabled &&
                (plan.primaryResolverKind != ResolverKind.MULLVAD_DOH || plan.orderedRoutes?.size == 2) &&
                terminalResolver == ResolverKind.MULLVAD_DOH

        ProtectionFallback.DEVICE_DNS ->
            plan.fallbackCoverage == ResolverFallbackCoverage.DEVICE_DNS &&
                plan.deviceDnsServers.isNotEmpty() &&
                plan.primaryResolverKind != ResolverKind.DEVICE &&
                terminalResolver == ResolverKind.DEVICE
    }

    private fun resolverIsPlausible(
        plan: ResolverPlan,
        resolver: ResolverKind,
    ): Boolean =
        resolver == plan.primaryResolverKind ||
            when (resolver) {
                ResolverKind.PLAIN_DNS -> plan.fallbackCoverage == ResolverFallbackCoverage.PLAIN_DNS &&
                    plan.orderedRoutes?.getOrNull(1)?.primaryResolverKind == ResolverKind.PLAIN_DNS
                ResolverKind.WIREGUARD_T0 -> false
                ResolverKind.MULLVAD_DOH ->
                    plan.fallbackCoverage == ResolverFallbackCoverage.ENCRYPTED_DNS &&
                        plan.mullvadEnabled

                ResolverKind.DEVICE ->
                    plan.fallbackCoverage == ResolverFallbackCoverage.DEVICE_DNS &&
                        plan.deviceDnsServers.isNotEmpty()
            }

    private fun ResolverTransport.resolverKind(): ResolverKind = when (this) {
        ResolverTransport.DEVICE_UDP,
        ResolverTransport.DEVICE_TCP,
        -> ResolverKind.DEVICE

        ResolverTransport.MULLVAD_DOH, ResolverTransport.DOQ, ResolverTransport.DOT -> ResolverKind.MULLVAD_DOH
        ResolverTransport.PLAIN_UDP, ResolverTransport.PLAIN_TCP -> ResolverKind.PLAIN_DNS
        ResolverTransport.WIREGUARD_UDP -> ResolverKind.WIREGUARD_T0
    }

    private fun ResolverResult.isLifecycleFailure(): Boolean =
        this is ResolverResult.Failure &&
            (
                reason == ResolverFailureReason.STALE_EPOCH ||
                    reason == ResolverFailureReason.CANCELLED
            )

    private fun elapsedMillis(
        startedAtNanos: Long,
        completedAtNanos: Long,
    ): Long {
        if (completedAtNanos <= startedAtNanos) return 0L
        return TimeUnit.NANOSECONDS.toMillis(completedAtNanos - startedAtNanos)
    }

    private sealed interface ExecutionOutcome {
        data class Resolved(
            val result: ResolverResult,
            val latencyMillis: Long,
        ) : ExecutionOutcome

        data class UnexpectedFailure(
            val latencyMillis: Long,
            val failure: Throwable,
        ) : ExecutionOutcome
    }

    private fun runtimeUnavailable(
        datagram: Ipv4UdpDnsPacket,
        query: ByteArray,
        question: DnsQuestion,
        replacement: ProxyReplacementResponse,
        engine: ReadyDnsEngine,
    ): ProxyPlan {
        return immediate(
            datagram = datagram,
            response = servfailResponse(query, question),
            replacement = replacement,
            activity = null,
            healthEvents = listOf(
                DnsHealthEvent.ProtectionUnavailable(
                    ProtectionUnavailableReason.RuntimeUnavailable,
                ),
            ),
            fence = engine,
        )
    }

    private fun servfailResponse(
        query: ByteArray,
        question: DnsQuestion,
    ): ByteArray {
        val questionBytes = question.questionBytes(query)
        return ByteArray(DnsMessage.HEADER_BYTES + questionBytes.size).also { response ->
            response.writeUInt16(0, question.transactionId)
            response.writeUInt16(
                2,
                0x8000 or (query.readUInt16(2) and 0x0100) or 0x0080 or 2,
            )
            response.writeUInt16(4, 1)
            questionBytes.copyInto(response, DnsMessage.HEADER_BYTES)
        }
    }

    private fun immediate(
        datagram: Ipv4UdpDnsPacket,
        response: ByteArray,
        replacement: ProxyReplacementResponse,
        activity: DnsDecisionEvent?,
        healthEvents: List<DnsHealthEvent>,
        fence: ProxyPublicationFence,
    ): ProxyPlan.Immediate = ProxyPlan.Immediate(
        ProxyResult.Pending(
            fence = fence,
            publication = ProxyPublication(
                responsePacket = datagram.response(response),
                activity = activity,
                healthEvents = healthEvents,
                resolverQueryEvidence = null,
            ),
            sink = publicationSink,
        ),
        replacement,
    )

    private fun replacement(
        datagram: Ipv4UdpDnsPacket,
        servfail: ByteArray,
    ): ProxyReplacementResponse =
        ProxyReplacementResponse(datagram.response(servfail))

    private class PlanningUnavailableException(
        cause: Throwable? = null,
    ) : RuntimeException(cause)

    private fun ByteArray.readUInt16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or
            (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.writeUInt16(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }
}
