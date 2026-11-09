package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.health.PrimaryResolverOutcome
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.health.ResolverTierDiagnosticObservation
import com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome
import com.lavasec.app.foundation.health.DnsFallbackObservation
import com.lavasec.app.foundation.health.DnsFallbackReason
import com.lavasec.app.foundation.health.DnsFallbackDisposition
import java.util.concurrent.TimeUnit

internal fun interface DeviceResolverWedgeEvidence {
    fun reconnectEpisodeIsActive(epoch: EngineEpoch): Boolean
}

/** Immutable terminal route result awaiting epoch-fenced transition publication. */
internal class ResolverCandidate internal constructor(
    val result: ResolverResult,
    val primaryResolverKind: ResolverKind,
    val acceptedResolverKind: ResolverKind?,
    val acceptedAsFallback: Boolean = result.resolverQueryEvidence?.successfulFallback?.let {
        it != ProtectionFallback.NONE
    } ?: (primaryResolverKind == ResolverKind.DEVICE && acceptedResolverKind == ResolverKind.MULLVAD_DOH),
    val orderedRoute: Boolean = false,
) {
    init {
        require(acceptedResolverKind == null || result is ResolverResult.Answer) {
            "an accepted resolver requires a terminal answer"
        }
    }
}

/**
 * Runs the immutable plan's routes once and produces terminal evidence without transition state.
 */
internal class ResolverRouteSequencer(
    private val plan: ResolverPlan,
    private val deviceRoute: DeviceDnsRoute?,
    private val mullvadRoute: DnsResolver?,
    private val endpointBackoff: ResolverEndpointBackoffPolicy,
    private val wedgeEvidence: DeviceResolverWedgeEvidence,
    private val clock: MonotonicClock = SystemMonotonicClock,
) {
    init {
        require(plan.hasDeviceRoute == (deviceRoute != null)) {
            "Device route presence must match the resolver plan"
        }
        require(plan.hasNonDeviceRoutes == (mullvadRoute != null)) {
            "Mullvad route presence must match the resolver plan"
        }
    }

    suspend fun resolve(unboundedRequest: ResolverRequest): ResolverCandidate {
        val request = ResolverExecutionBudget.whole(unboundedRequest, clock)
        ResolverReplyBoundary.validateRequest(request)?.let { invalid ->
            return candidate(
                result = ResolverResult.Failure(invalid.reason),
                acceptedResolver = null,
            )
        }

        val startedAtNanos = clock.nowNanos()
        val terminal = plan.orderedRoutes?.let { resolveOrdered(request, it, startedAtNanos) } ?: when (plan.primaryResolverKind) {
            ResolverKind.PLAIN_DNS -> error("fixed plain routes require their exact ordered leaf")
            ResolverKind.WIREGUARD_T0 -> error("WireGuard resolver requires the chained data path")
            ResolverKind.DEVICE -> resolveDevice(request, startedAtNanos)
            ResolverKind.MULLVAD_DOH -> resolveMullvadPrimary(request, startedAtNanos)
        }
        val result = terminal.result
        val evidence = result.resolverQueryEvidence ?: return terminal
        val enriched = evidence.copy(
            responseLatencyMillis = if (result is ResolverResult.Answer) elapsedMillis(startedAtNanos) else null,
            httpProtocol = (result as? ResolverResult.Answer)?.httpProtocol,
            tierObservations = evidence.tierObservations.map { observation ->
                if (result is ResolverResult.Answer && observation.resolver == terminal.acceptedResolverKind &&
                    observation.outcome == ResolverTierDiagnosticOutcome.SERVED)
                    routeObservation(observation.tier, observation.resolver, result, request.query)
                else observation
            },
        )
        return candidate(result.withDiagnostics(enriched), terminal.acceptedResolverKind)
    }

    /** Real primary only; uses the same registry/backoff and leaves client transition state alone. */
    suspend fun resolvePrimary(unboundedRequest: ResolverRequest): ResolverCandidate {
        val request = ResolverExecutionBudget.whole(unboundedRequest, clock)
        ResolverReplyBoundary.validateRequest(request)?.let { return candidate(it, null) }
        val route = plan.orderedRoutes?.first()
        if (route == null && plan.primaryResolverKind == ResolverKind.MULLVAD_DOH) {
            return resolveMullvadPrimary(request, clock.nowNanos())
        }
        val result = orderedAttempt(request, route ?: plan, plan.configuredTierNumbers.first())
        return candidate(result, if (result is ResolverResult.Answer) plan.primaryResolverKind else null)
    }

    private suspend fun resolveOrdered(
        request: ResolverRequest,
        routes: List<ResolverPlan>,
        startedAtNanos: Long,
    ): ResolverCandidate {
        var primaryFailure: ResolverFailureReason? = null
        var primaryRejected = false
        var heldResponse: ResolverResult.Answer? = null
        var heldPortal = false
        var fallbackReason = DnsFallbackReason.TRANSPORT_FAILURE
        val events = mutableListOf<DnsHealthEvent>()
        val observations = mutableListOf<ResolverTierDiagnosticObservation>()
        fun facts(disposition: DnsFallbackDisposition) = if (observations.size < 2) emptyList() else
            listOf(DnsFallbackObservation(1, 2, fallbackReason, disposition))
        fun preserved(): ResolverCandidate {
            val original = checkNotNull(heldResponse)
            return candidate(ResolverResult.Answer(original.dnsMessage, original.transport, events,
                ResolverQueryHealthEvidence.primaryAccepted(request.queryId, elapsedMillis(startedAtNanos)).copy(
                    tierObservations = observations.toList(), fallbackObservations = facts(if (heldPortal) DnsFallbackDisposition.PRESERVED_NEGATIVE else DnsFallbackDisposition.ATTEMPTED)),
                original.httpProtocol), routes.first().primaryResolverKind)
        }
        for ((index, route) in routes.withIndex()) {
            val attemptRequest = if (heldPortal) request.bounded(checkNotNull(request.wholeQueryDeadlineNanos),
                request.absoluteDeviceDeadlineNanos ?: checkNotNull(request.wholeQueryDeadlineNanos),
                request.fallbackContext.copy(requiresPortalAdmission = true)) else request
            val tier = plan.configuredTierNumbers[index]
            val result = orderedAttempt(attemptRequest, route, tier)
            observations += routeObservation(tier, route.primaryResolverKind, result, request.query)
            when (result) {
                is ResolverResult.Answer -> {
                    val classification = DnsFallbackResponse.classify(request.query, result.dnsMessage)
                    if (heldResponse != null && if (heldPortal) !DnsPortalTierFallback.rescueAnswer(request.query, result.dnsMessage, request.fallbackContext)
                        else classification.kind !in setOf(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.Kind.NEGATIVE)) {
                        // A reply discarded by selection proves reachability but did not serve
                        // this lookup, including a valid negative that could not rescue a portal.
                        observations[observations.lastIndex] = observations.last().copy(outcome = ResolverTierDiagnosticOutcome.ANSWERED)
                        return preserved()
                    }
                    val next = routes.getOrNull(index + 1)
                    val operational = request.fallbackContext.operationalFallbackEnabled && next != null &&
                        (classification.kind == DnsFallbackResponse.Kind.TRANSIENT_FAILURE ||
                            classification.kind == DnsFallbackResponse.Kind.AMBIGUOUS_FAILURE &&
                            route.primaryResolverKind == ResolverKind.DEVICE && wedgeEvidence.reconnectEpisodeIsActive(request.epoch))
                    val portal = next?.primaryResolverKind == ResolverKind.DEVICE &&
                        DnsPortalTierFallback.eligible(request.query, result.dnsMessage, request.fallbackContext)
                    if (index == 0 && (operational || portal)) {
                        primaryRejected = true
                        fallbackReason = when {
                            portal -> DnsFallbackReason.PORTAL_ADDRESS_NEGATIVE
                            classification.kind == DnsFallbackResponse.Kind.TRANSIENT_FAILURE -> DnsFallbackReason.OPERATIONAL_ERROR
                            else -> DnsFallbackReason.RESOLVER_OUTAGE
                        }
                        heldResponse = result
                        heldPortal = portal
                        if (!portal) observations[observations.lastIndex] = observations.last().copy(outcome = ResolverTierDiagnosticOutcome.ANSWERED)
                        continue
                    }
                    if (classification.kind !in setOf(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.Kind.NEGATIVE))
                        observations[observations.lastIndex] = observations.last().copy(outcome = ResolverTierDiagnosticOutcome.ANSWERED)
                    val fallback = ResolverPlanFactory.coverageFor(route.primaryResolverKind).let { coverage -> when (coverage) {
                        com.lavasec.app.foundation.health.ResolverFallbackCoverage.DEVICE_DNS -> ProtectionFallback.DEVICE_DNS
                        com.lavasec.app.foundation.health.ResolverFallbackCoverage.PLAIN_DNS -> ProtectionFallback.PLAIN_DNS
                        com.lavasec.app.foundation.health.ResolverFallbackCoverage.ENCRYPTED_DNS -> ProtectionFallback.ENCRYPTED_DNS
                        else -> ProtectionFallback.NONE
                    } }
                    if (index > 0 && classification.kind !in setOf(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.Kind.NEGATIVE)) {
                        return candidate(ResolverResult.Failure(ResolverFailureReason.INVALID_RESPONSE, events,
                            route.primaryResolverKind, ResolverQueryHealthEvidence.primaryFailed(request.queryId, checkNotNull(primaryFailure))
                                .copy(tierObservations = observations.toList(), fallbackObservations = facts(DnsFallbackDisposition.ATTEMPTED))), null)
                    }
                    if (index > 0 && heldPortal) {
                        // The original negative proved reachability, but the selected rescue
                        // now owns service credit. Preserved negatives retain their credit.
                        observations[0] = observations.first().copy(outcome = ResolverTierDiagnosticOutcome.ANSWERED)
                    }
                    val evidence = if (index == 0) ResolverQueryHealthEvidence.primaryAccepted(request.queryId, elapsedMillis(startedAtNanos))
                        else if (primaryRejected) ResolverQueryHealthEvidence.primaryRejectedWithFallback(request.queryId, fallback)
                        else ResolverQueryHealthEvidence.primaryFailed(request.queryId, checkNotNull(primaryFailure), fallback)
                    return candidate(ResolverResult.Answer(result.dnsMessage, result.transport, events,
                        evidence.copy(tierObservations = observations.toList(), fallbackObservations = facts(DnsFallbackDisposition.SERVED)), result.httpProtocol), route.primaryResolverKind)
                }
                is ResolverResult.Failure -> {
                    if (index == 0) {
                        primaryFailure = result.reason
                        fallbackReason = if (result.reason == ResolverFailureReason.TIMEOUT) DnsFallbackReason.TIER_BUDGET_EXPIRED else DnsFallbackReason.TRANSPORT_FAILURE
                    }
                    if (!result.peerAnswered && !result.local) events += failureEvent(route.primaryResolverKind, result.reason)
                    if (heldResponse != null && result.reason !in setOf(ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.CANCELLED)) return preserved()
                    if (result.terminal || result.reason in setOf(ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.CANCELLED,
                            ResolverFailureReason.OVERLOADED, ResolverFailureReason.MISMATCH_LIMIT) || index == routes.lastIndex ||
                        !request.fallbackContext.operationalFallbackEnabled ||
                        request.wholeQueryDeadlineNanos?.let { clock.nowNanos() >= it } == true) {
                        val suppressed = index > 0 && !primaryRejected && result.reason == ResolverFailureReason.BACKED_OFF
                        return candidate(ResolverResult.Failure(
                            reason = if (suppressed) checkNotNull(primaryFailure) else result.reason,
                            healthEvents = events,
                            resolver = if (suppressed) routes.first().primaryResolverKind else route.primaryResolverKind,
                            terminal = result.terminal, peerAnswered = result.peerAnswered, local = result.local,
                            resolverQueryEvidence = (if (primaryRejected) ResolverQueryHealthEvidence(request.queryId, PrimaryResolverOutcome.REJECTED)
                                else ResolverQueryHealthEvidence.primaryFailed(request.queryId, checkNotNull(primaryFailure)))
                                .copy(tierObservations = observations.toList(), fallbackObservations = facts(DnsFallbackDisposition.ATTEMPTED)),
                        ), null)
                    }
                }
            }
        }
        error("an ordered resolver plan must contain a route")
    }

    private suspend fun orderedAttempt(request: ResolverRequest, route: ResolverPlan, tier: Int = 1): ResolverResult {
        val bounded = ResolverExecutionBudget.tier(request, clock, tier)
        return ResolverExecutionBudget.execute(bounded, clock) { orderedAttemptUnbounded(bounded, route) }
    }

    private suspend fun orderedAttemptUnbounded(request: ResolverRequest, route: ResolverPlan): ResolverResult {
        if (route.primaryResolverKind == ResolverKind.DEVICE) {
            val outcome = checkNotNull(deviceRoute).resolve(request, endpointBackoff)
            val answer = outcome.result as? ResolverResult.Answer ?: return outcome.result
            val prepared = ResolverReplyBoundary.validate(request, ResolverKind.DEVICE, answer)
            if (prepared is ResolverResult.Failure) {
                outcome.answeredEndpoint?.let { if (prepared.peerAnswered) endpointBackoff.recordSuccess(it) else if (!prepared.local) endpointBackoff.recordFailure(it, prepared.reason) }
            }
            return prepared
        }
        if (route.primaryResolverKind == ResolverKind.PLAIN_DNS) {
            val resolver = checkNotNull(mullvadRoute as? OrderedEncryptedDnsResolver).resolverFor(route.primaryResolverIdentity)
            if (resolver is DeviceDnsRoute) {
                val outcome = resolver.resolve(request, endpointBackoff)
                val answer = outcome.result as? ResolverResult.Answer ?: return outcome.result
                val prepared = ResolverReplyBoundary.validate(request, ResolverKind.PLAIN_DNS, answer)
                if (prepared is ResolverResult.Failure) outcome.answeredEndpoint?.let { if (prepared.peerAnswered) endpointBackoff.recordSuccess(it) else if (!prepared.local) endpointBackoff.recordFailure(it, prepared.reason) }
                return prepared
            }
        }
        val key = ResolverEndpointKey.Encrypted(route.primaryResolverIdentity)
        if (endpointBackoff.isBackedOff(key)) return ResolverResult.Failure(ResolverFailureReason.BACKED_OFF)
        val resolver = checkNotNull(mullvadRoute as? OrderedEncryptedDnsResolver).resolverFor(route.primaryResolverIdentity)
        val result = when (val response = resolver.resolve(request)) {
            is ResolverResult.Answer -> ResolverReplyBoundary.validate(request, route.primaryResolverKind, response)
            is ResolverResult.Failure -> response
        }
        when (result) {
            is ResolverResult.Answer -> endpointBackoff.recordSuccess(key)
            is ResolverResult.Failure -> if (result.peerAnswered) endpointBackoff.recordSuccess(key) else if (!result.local) endpointBackoff.recordFailure(key, result.reason)
        }
        return result
    }

    private suspend fun resolveDevice(
        request: ResolverRequest,
        startedAtNanos: Long,
    ): ResolverCandidate {
        val bounded = ResolverExecutionBudget.tier(request, clock)
        var routeOutcome: DeviceDnsRouteResult? = null
        val boundedResult = ResolverExecutionBudget.execute(bounded, clock) {
            checkNotNull(deviceRoute).resolve(bounded, endpointBackoff).also { routeOutcome = it }.result
        }
        if (boundedResult is ResolverResult.Failure && boundedResult.terminal) return terminalDeviceFailure(request, boundedResult.reason, boundedResult)
        val routeOutcomeValue = routeOutcome ?: DeviceDnsRouteResult(boundedResult, null)
        val routeResult = boundedResult
        return when (routeResult) {
            is ResolverResult.Answer -> {
                when (
                    val prepared = ResolverReplyBoundary.validate(
                        request,
                        ResolverKind.DEVICE,
                        routeResult,
                    )
                ) {
                    is ResolverResult.Answer -> resolvePreparedDeviceAnswer(
                        request = request,
                        answer = prepared,
                        startedAtNanos = startedAtNanos,
                    )

                    is ResolverResult.Failure -> {
                        if (prepared.peerAnswered) endpointBackoff.recordSuccess(checkNotNull(routeOutcomeValue.answeredEndpoint))
                        else if (!prepared.local) endpointBackoff.recordFailure(checkNotNull(routeOutcomeValue.answeredEndpoint), prepared.reason)
                        resolveDeviceFailure(
                            request = request,
                            reason = prepared.reason,
                            raw = prepared,
                        )
                    }
                }
            }

            is ResolverResult.Failure -> {
                if (
                    routeResult.reason == ResolverFailureReason.STALE_EPOCH ||
                    routeResult.reason == ResolverFailureReason.CANCELLED || routeResult.reason == ResolverFailureReason.OVERLOADED ||
                    routeResult.reason == ResolverFailureReason.MISMATCH_LIMIT || routeResult.terminal
                ) {
                    terminalDeviceFailure(request, routeResult.reason, routeResult)
                } else {
                    resolveDeviceFailure(request, routeResult.reason, routeResult)
                }
            }
        }
    }

    private suspend fun resolvePreparedDeviceAnswer(
        request: ResolverRequest,
        answer: ResolverResult.Answer,
        startedAtNanos: Long,
    ): ResolverCandidate {
        val classification = DnsFallbackResponse.classify(request.query, answer.dnsMessage)
        val retry = request.fallbackContext.operationalFallbackEnabled &&
            (classification.kind == DnsFallbackResponse.Kind.TRANSIENT_FAILURE ||
                classification.kind == DnsFallbackResponse.Kind.AMBIGUOUS_FAILURE && wedgeEvidence.reconnectEpisodeIsActive(request.epoch))
        if (!retry) {
            return acceptedAnswer(
                answer = answer,
                acceptedResolver = ResolverKind.DEVICE,
                evidence = ResolverQueryHealthEvidence.primaryAccepted(
                    request.queryId,
                    elapsedMillis(startedAtNanos),
                ),
            )
        }

        if (!plan.mullvadEnabled) {
            return acceptedAnswer(
                answer = answer,
                acceptedResolver = ResolverKind.DEVICE,
                evidence = ResolverQueryHealthEvidence.primaryAccepted(
                    request.queryId,
                    elapsedMillis(startedAtNanos),
                ),
            )
        }

        return resolveMullvadFallback(
            request = request,
            primary = DevicePrimaryOutcome.Rejected(answer),
            events = emptyList(),
        )
    }

    private suspend fun resolveDeviceFailure(
        request: ResolverRequest,
        reason: ResolverFailureReason,
        raw: ResolverResult.Failure? = null,
    ): ResolverCandidate {
        if (!plan.mullvadEnabled || raw?.terminal == true || !request.fallbackContext.operationalFallbackEnabled) return terminalDeviceFailure(request, reason, raw)
        val outcome = resolveMullvadFallback(
            request = request,
            primary = DevicePrimaryOutcome.Failed(reason, raw),
            events = if (raw?.local == true || raw?.peerAnswered == true) emptyList() else failureEvent(ResolverKind.DEVICE, reason),
        )
        val evidence = outcome.result.resolverQueryEvidence
        return if (raw == null || evidence == null) outcome else candidate(outcome.result.withDiagnostics(evidence.copy(
            tierObservations = listOf(routeObservation(1, ResolverKind.DEVICE, raw, request.query)) + evidence.tierObservations.drop(1))), outcome.acceptedResolverKind)
    }

    private fun terminalDeviceFailure(
        request: ResolverRequest,
        reason: ResolverFailureReason,
        raw: ResolverResult.Failure? = null,
    ): ResolverCandidate = candidate(
        result = ResolverResult.Failure(
            reason = reason,
            terminal = raw?.terminal ?: false, peerAnswered = raw?.peerAnswered ?: false, local = raw?.local ?: false,
            healthEvents = if (raw?.peerAnswered == true || raw?.local == true) emptyList() else failureEvent(ResolverKind.DEVICE, reason),
            resolver = ResolverKind.DEVICE,
            resolverQueryEvidence = ResolverQueryHealthEvidence.primaryFailed(
                request.queryId,
                reason,
            ),
        ),
        acceptedResolver = null,
    )

    private suspend fun resolveMullvadPrimary(
        request: ResolverRequest,
        startedAtNanos: Long,
    ): ResolverCandidate {
        if (endpointBackoff.isBackedOff(ResolverEndpointKey.MullvadDoh)) {
            return terminalMullvadPrimaryFailure(
                request = request,
                reason = ResolverFailureReason.BACKED_OFF,
                events = emptyList(),
            )
        }

        val bounded = ResolverExecutionBudget.tier(request, clock, plan.configuredTierNumbers.first())
        return when (val routeResult = ResolverExecutionBudget.execute(bounded, clock) { checkNotNull(mullvadRoute).resolve(bounded) }) {
            is ResolverResult.Answer -> {
                when (
                    val prepared = ResolverReplyBoundary.validate(
                        request,
                        ResolverKind.MULLVAD_DOH,
                        routeResult,
                    )
                ) {
                    is ResolverResult.Answer -> {
                        endpointBackoff.recordSuccess(ResolverEndpointKey.MullvadDoh)
                        acceptedAnswer(
                            answer = prepared,
                            acceptedResolver = ResolverKind.MULLVAD_DOH,
                            evidence = ResolverQueryHealthEvidence.primaryAccepted(
                                request.queryId,
                                elapsedMillis(startedAtNanos),
                            ),
                        )
                    }

                    is ResolverResult.Failure -> {
                        if (prepared.peerAnswered) endpointBackoff.recordSuccess(ResolverEndpointKey.MullvadDoh)
                        else if (!prepared.local) endpointBackoff.recordFailure(ResolverEndpointKey.MullvadDoh, prepared.reason)
                        terminalMullvadPrimaryFailure(
                            request = request,
                            reason = prepared.reason,
                            events = if (prepared.peerAnswered || prepared.local) emptyList() else failureEvent(ResolverKind.MULLVAD_DOH, prepared.reason),
                            raw = prepared,
                        )
                    }
                }
            }

            is ResolverResult.Failure -> {
                if (!routeResult.peerAnswered && !routeResult.local) endpointBackoff.recordFailure(ResolverEndpointKey.MullvadDoh, routeResult.reason)
                else if (routeResult.peerAnswered) endpointBackoff.recordSuccess(ResolverEndpointKey.MullvadDoh)
                terminalMullvadPrimaryFailure(
                    request = request,
                    reason = routeResult.reason,
                    events = if (routeResult.peerAnswered || routeResult.local) emptyList() else failureEvent(ResolverKind.MULLVAD_DOH, routeResult.reason),
                    raw = routeResult,
                )
            }
        }
    }

    private fun terminalMullvadPrimaryFailure(
        request: ResolverRequest,
        reason: ResolverFailureReason,
        events: List<DnsHealthEvent>,
        raw: ResolverResult.Failure? = null,
    ): ResolverCandidate = candidate(
        result = ResolverResult.Failure(
            reason = reason,
            healthEvents = events,
            terminal = raw?.terminal ?: false, peerAnswered = raw?.peerAnswered ?: false, local = raw?.local ?: false,
            resolver = ResolverKind.MULLVAD_DOH,
            resolverQueryEvidence = ResolverQueryHealthEvidence.primaryFailed(
                request.queryId,
                reason,
            ),
        ),
        acceptedResolver = null,
    )

    private suspend fun resolveMullvadFallback(
        request: ResolverRequest,
        primary: DevicePrimaryOutcome,
        events: List<DnsHealthEvent>,
    ): ResolverCandidate {
        if (endpointBackoff.isBackedOff(ResolverEndpointKey.MullvadDoh)) {
            return terminalSuppressedFallback(request, primary, events)
        }

        val bounded = ResolverExecutionBudget.tier(request, clock, 2)
        return when (val routeResult = ResolverExecutionBudget.execute(bounded, clock) { checkNotNull(mullvadRoute).resolve(bounded) }) {
            is ResolverResult.Answer -> {
                when (
                    val prepared = ResolverReplyBoundary.validate(
                        request,
                        ResolverKind.MULLVAD_DOH,
                        routeResult,
                    )
                ) {
                    is ResolverResult.Answer -> {
                        endpointBackoff.recordSuccess(ResolverEndpointKey.MullvadDoh)
                        val meaning = DnsFallbackResponse.classify(request.query, prepared.dnsMessage)
                        if (meaning.kind !in setOf(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.Kind.NEGATIVE)) {
                            if (primary is DevicePrimaryOutcome.Rejected) return preservedRejection(request, primary, prepared, events)
                            return terminalFallbackFailure(request, primary, ResolverFailureReason.INVALID_RESPONSE, events)
                        }
                        acceptedAnswer(
                            answer = prepared,
                            acceptedResolver = ResolverKind.MULLVAD_DOH,
                            evidence = primary.successfulFallbackEvidence(request.queryId),
                            events = events,
                        )
                    }

                    is ResolverResult.Failure -> {
                        if (primary is DevicePrimaryOutcome.Rejected) {
                            if (prepared.peerAnswered) endpointBackoff.recordSuccess(ResolverEndpointKey.MullvadDoh)
                            else if (!prepared.local) endpointBackoff.recordFailure(ResolverEndpointKey.MullvadDoh, prepared.reason)
                            return preservedRejection(request, primary, prepared, events)
                        }
                        endpointBackoff.recordFailure(
                            ResolverEndpointKey.MullvadDoh,
                            prepared.reason,
                        )
                        terminalFallbackFailure(
                            request = request,
                            primary = primary,
                            reason = prepared.reason,
                            events = events + failureEvent(
                                ResolverKind.MULLVAD_DOH,
                                prepared.reason,
                            ),
                        )
                    }
                }
            }

            is ResolverResult.Failure -> {
                if (primary is DevicePrimaryOutcome.Rejected && routeResult.reason !in setOf(ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.CANCELLED)) {
                    if (routeResult.peerAnswered) endpointBackoff.recordSuccess(ResolverEndpointKey.MullvadDoh)
                    else if (!routeResult.local) endpointBackoff.recordFailure(ResolverEndpointKey.MullvadDoh, routeResult.reason)
                    return preservedRejection(request, primary, routeResult, events)
                }
                if (!routeResult.peerAnswered && !routeResult.local) endpointBackoff.recordFailure(ResolverEndpointKey.MullvadDoh, routeResult.reason)
                else if (routeResult.peerAnswered) endpointBackoff.recordSuccess(ResolverEndpointKey.MullvadDoh)
                if (routeResult.reason == ResolverFailureReason.BACKED_OFF) {
                    terminalSuppressedFallback(request, primary, events)
                } else {
                    terminalFallbackFailure(
                        request = request,
                        primary = primary,
                        reason = routeResult.reason,
                        events = events + if (routeResult.local || routeResult.peerAnswered) emptyList() else failureEvent(
                            ResolverKind.MULLVAD_DOH, routeResult.reason),
                        raw = routeResult,
                    )
                }
            }
        }
    }

    private fun terminalSuppressedFallback(
        request: ResolverRequest,
        primary: DevicePrimaryOutcome,
        events: List<DnsHealthEvent>,
    ): ResolverCandidate = when (primary) {
        is DevicePrimaryOutcome.Failed -> candidate(
            result = ResolverResult.Failure(
                reason = primary.reason,
                terminal = primary.raw?.terminal ?: false, peerAnswered = primary.raw?.peerAnswered ?: false, local = primary.raw?.local ?: false,
                healthEvents = events,
                resolver = ResolverKind.DEVICE,
                resolverQueryEvidence = primary.terminalEvidence(request.queryId),
            ),
            acceptedResolver = null,
        )

        is DevicePrimaryOutcome.Rejected -> preservedRejection(request, primary,
            ResolverResult.Failure(ResolverFailureReason.BACKED_OFF, local = true), events)
    }

    private fun preservedRejection(request: ResolverRequest, primary: DevicePrimaryOutcome.Rejected,
        fallbackResult: ResolverResult, events: List<DnsHealthEvent>): ResolverCandidate {
        val classification = DnsFallbackResponse.classify(request.query, primary.original.dnsMessage)
        return acceptedAnswer(primary.original, ResolverKind.DEVICE,
            ResolverQueryHealthEvidence.primaryAccepted(request.queryId, 0).copy(
                tierObservations = listOf(routeObservation(1, ResolverKind.DEVICE, primary.original, request.query),
                    routeObservation(2, ResolverKind.MULLVAD_DOH, fallbackResult, request.query)),
                fallbackObservations = listOf(DnsFallbackObservation(1, 2,
                    if (classification.kind == DnsFallbackResponse.Kind.TRANSIENT_FAILURE) DnsFallbackReason.OPERATIONAL_ERROR else DnsFallbackReason.RESOLVER_OUTAGE,
                    DnsFallbackDisposition.ATTEMPTED))), events)
    }

    private fun terminalFallbackFailure(
        request: ResolverRequest,
        primary: DevicePrimaryOutcome,
        reason: ResolverFailureReason,
        events: List<DnsHealthEvent>,
        raw: ResolverResult.Failure? = null,
    ): ResolverCandidate = candidate(
        result = ResolverResult.Failure(
            reason = reason,
            healthEvents = events,
            terminal = raw?.terminal ?: false, peerAnswered = raw?.peerAnswered ?: false, local = raw?.local ?: false,
            resolver = ResolverKind.MULLVAD_DOH,
            resolverQueryEvidence = primary.terminalEvidence(request.queryId),
        ),
        acceptedResolver = null,
    )

    private fun acceptedAnswer(
        answer: ResolverResult.Answer,
        acceptedResolver: ResolverKind,
        evidence: ResolverQueryHealthEvidence,
        events: List<DnsHealthEvent> = emptyList(),
    ): ResolverCandidate = candidate(
        result = ResolverResult.Answer(
            dnsMessage = answer.dnsMessage,
            transport = answer.transport,
            healthEvents = events,
            resolverQueryEvidence = evidence,
            httpProtocol = answer.httpProtocol,
        ),
        acceptedResolver = acceptedResolver,
    )

    private fun candidate(
        result: ResolverResult,
        acceptedResolver: ResolverKind?,
    ): ResolverCandidate = ResolverCandidate(
        result = result.resolverQueryEvidence?.let { evidence ->
            if (evidence.tierObservations.isNotEmpty()) result else result.withDiagnostics(evidence.copy(
                tierObservations = legacyObservations(result, evidence, acceptedResolver),
                fallbackObservations = if (plan.fallbackCoverage != com.lavasec.app.foundation.health.ResolverFallbackCoverage.NONE &&
                    evidence.primaryOutcome != PrimaryResolverOutcome.ACCEPTED &&
                    (acceptedResolver != null || (result as? ResolverResult.Failure)?.resolver == ResolverKind.MULLVAD_DOH))
                    listOf(DnsFallbackObservation(1, 2,
                        if (evidence.primaryOutcome == PrimaryResolverOutcome.REJECTED) DnsFallbackReason.RESOLVER_OUTAGE else DnsFallbackReason.TRANSPORT_FAILURE,
                        if (acceptedResolver != null) DnsFallbackDisposition.SERVED else DnsFallbackDisposition.ATTEMPTED))
                    else emptyList(),
            ))
        } ?: result,
        primaryResolverKind = plan.primaryResolverKind,
        acceptedResolverKind = acceptedResolver,
        orderedRoute = plan.orderedRoutes != null,
    )

    private fun legacyObservations(result: ResolverResult, evidence: ResolverQueryHealthEvidence, accepted: ResolverKind?): List<ResolverTierDiagnosticObservation> {
        val primary = when (evidence.primaryOutcome) {
            PrimaryResolverOutcome.ACCEPTED -> routeObservation(plan.configuredTierNumbers.first(), plan.primaryResolverKind, result)
            PrimaryResolverOutcome.REJECTED -> ResolverTierDiagnosticObservation(plan.configuredTierNumbers.first(), plan.primaryResolverKind, ResolverTierDiagnosticOutcome.ANSWERED)
            PrimaryResolverOutcome.FAILED -> routeObservation(plan.configuredTierNumbers.first(), plan.primaryResolverKind,
                if (result is ResolverResult.Failure && result.resolver == plan.primaryResolverKind) result
                else ResolverResult.Failure(checkNotNull(evidence.primaryFailureReason)))
        }
        if (plan.fallbackCoverage == com.lavasec.app.foundation.health.ResolverFallbackCoverage.NONE || evidence.primaryOutcome == PrimaryResolverOutcome.ACCEPTED) return listOf(primary)
        // Legacy ladders have exactly one Device primary and one encrypted fallback.
        val fallback = when {
            accepted != null -> routeObservation(2, accepted, result)
            result is ResolverResult.Failure && result.resolver == ResolverKind.MULLVAD_DOH -> routeObservation(2, ResolverKind.MULLVAD_DOH, result)
            else -> ResolverTierDiagnosticObservation(2, ResolverKind.MULLVAD_DOH, ResolverTierDiagnosticOutcome.NOT_ATTEMPTED)
        }
        return listOf(primary, fallback)
    }

    private fun ResolverResult.withDiagnostics(evidence: ResolverQueryHealthEvidence): ResolverResult = when (this) {
        is ResolverResult.Answer -> ResolverResult.Answer(dnsMessage, transport, healthEvents, evidence, httpProtocol)
        is ResolverResult.Failure -> ResolverResult.Failure(reason, healthEvents, resolver, evidence, terminal, peerAnswered, local)
    }

    private fun routeObservation(tier: Int, resolver: ResolverKind, result: ResolverResult, query: ByteArray? = null) = when (result) {
        is ResolverResult.Answer -> {
            val classification = query?.let { DnsFallbackResponse.classify(it, result.dnsMessage) }
            ResolverTierDiagnosticObservation(tier, resolver,
                if (classification == null || classification.kind in setOf(DnsFallbackResponse.Kind.POSITIVE, DnsFallbackResponse.Kind.NEGATIVE)) ResolverTierDiagnosticOutcome.SERVED else ResolverTierDiagnosticOutcome.ANSWERED,
                result.transport, packetSent = true,
                responseClass = classification?.kind?.let { kind -> when (kind) {
                    DnsFallbackResponse.Kind.POSITIVE -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.POSITIVE
                    DnsFallbackResponse.Kind.NEGATIVE -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.NEGATIVE
                    DnsFallbackResponse.Kind.MALFORMED -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.MALFORMED
                    DnsFallbackResponse.Kind.MISMATCHED -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.MISMATCHED
                    DnsFallbackResponse.Kind.TRUNCATED -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.TRUNCATED
                    else -> com.lavasec.app.foundation.health.DnsFallbackResponseClass.DNS_ERROR
                } }, responseCode = classification?.fullResponseCode,
                queryType = query?.let { runCatching { com.lavasec.app.foundation.dns.DnsMessage.parseQuestion(it).rawRecordType }.getOrNull() },
                terminalAddressPresent = classification?.let { it.terminalAddressCount > 0 })
        }
        is ResolverResult.Failure -> ResolverTierDiagnosticObservation(tier, resolver,
            if (result.peerAnswered) ResolverTierDiagnosticOutcome.ANSWERED
            else if (result.local || result.reason in setOf(ResolverFailureReason.BACKED_OFF, ResolverFailureReason.CANCELLED,
                    ResolverFailureReason.STALE_EPOCH, ResolverFailureReason.OVERLOADED)) ResolverTierDiagnosticOutcome.NOT_ATTEMPTED
            else ResolverTierDiagnosticOutcome.FAILED, failure = result.reason, peerLivenessOnly = result.peerAnswered, packetSent = if (result.local) false else if (result.peerAnswered) true else null)
    }

    private fun failureEvent(
        resolver: ResolverKind,
        reason: ResolverFailureReason,
    ): List<DnsHealthEvent> =
        if (reason == ResolverFailureReason.BACKED_OFF) {
            emptyList()
        } else {
            listOf(DnsHealthEvent.ResolverAttemptFailed(resolver, reason))
        }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((clock.nowNanos() - startedAtNanos).coerceAtLeast(0L))

    private sealed interface DevicePrimaryOutcome {
        data class Failed(val reason: ResolverFailureReason, val raw: ResolverResult.Failure? = null) : DevicePrimaryOutcome

        data class Rejected(val original: ResolverResult.Answer) : DevicePrimaryOutcome

        fun successfulFallbackEvidence(queryId: Long): ResolverQueryHealthEvidence = when (this) {
            is Failed -> ResolverQueryHealthEvidence.primaryFailed(
                queryId = queryId,
                reason = reason,
                successfulFallback = ProtectionFallback.ENCRYPTED_DNS,
            )

            is Rejected -> ResolverQueryHealthEvidence.primaryRejectedWithFallback(
                queryId = queryId,
                fallback = ProtectionFallback.ENCRYPTED_DNS,
            )
        }

        fun terminalEvidence(queryId: Long): ResolverQueryHealthEvidence = when (this) {
            is Failed -> ResolverQueryHealthEvidence.primaryFailed(queryId, reason)
            is Rejected -> ResolverQueryHealthEvidence(
                queryId = queryId,
                primaryOutcome = PrimaryResolverOutcome.REJECTED,
            )
        }
    }
}
