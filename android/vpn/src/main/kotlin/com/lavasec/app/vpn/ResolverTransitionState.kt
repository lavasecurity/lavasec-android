package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ResolverKind

internal fun interface ResolverTransitionResultFactory {
    fun create(
        result: ResolverResult,
        event: DnsHealthEvent?,
    ): ResolverResult
}

/**
 * Tracks resolver fallback state for terminal candidates published by the current epoch.
 *
 * Callers must invoke [commit] only while their terminal publication is epoch-fenced.
 */
internal class ResolverTransitionState(
    private val resultFactory: ResolverTransitionResultFactory =
        ResolverTransitionResultFactory(::resultWith),
) {
    private var fallbackActive = false

    /** Returns the terminal result that would be published without changing transition state. */
    @Synchronized
    fun evaluate(candidate: ResolverCandidate): ResolverResult =
        resultFactory.create(candidate.result, nextEvent(candidate))

    /** Returns the terminal result and atomically records any newly published transition. */
    @Synchronized
    fun commit(candidate: ResolverCandidate): ResolverResult {
        val event = nextEvent(candidate)
        val publishedResult = resultFactory.create(candidate.result, event)
        when (event) {
            is DnsHealthEvent.FallbackActivated -> fallbackActive = true
            is DnsHealthEvent.ResolverRecovered -> fallbackActive = false
            null -> Unit
            else -> error("resolver transition state produced an unsupported event")
        }
        return publishedResult
    }

    private fun nextEvent(candidate: ResolverCandidate): DnsHealthEvent? {
        if (candidate.result !is ResolverResult.Answer) return null
        if (!candidate.acceptedAsFallback && candidate.result.resolverQueryEvidence?.tierObservations?.firstOrNull()?.outcome?.let {
                it != com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.SERVED
            } == true) return null

        return when {
            candidate.acceptedAsFallback && !fallbackActive ->
                DnsHealthEvent.FallbackActivated(checkNotNull(candidate.acceptedResolverKind))

            (candidate.orderedRoute || candidate.primaryResolverKind == ResolverKind.DEVICE) &&
                !candidate.acceptedAsFallback &&
                candidate.acceptedResolverKind == candidate.primaryResolverKind &&
                fallbackActive -> DnsHealthEvent.ResolverRecovered(candidate.primaryResolverKind)

            else -> null
        }
    }

    private companion object {
        fun resultWith(
            result: ResolverResult,
            event: DnsHealthEvent?,
        ): ResolverResult {
            val healthEvents = if (event == null) result.healthEvents else result.healthEvents + event
            return when (result) {
                is ResolverResult.Answer -> ResolverResult.Answer(
                    dnsMessage = result.dnsMessage,
                    transport = result.transport,
                    httpProtocol = result.httpProtocol,
                    healthEvents = healthEvents,
                    resolverQueryEvidence = result.resolverQueryEvidence,
                )

                is ResolverResult.Failure -> ResolverResult.Failure(
                    reason = result.reason,
                    healthEvents = healthEvents,
                    resolver = result.resolver,
                    terminal = result.terminal, peerAnswered = result.peerAnswered, local = result.local,
                    resolverQueryEvidence = result.resolverQueryEvidence,
                )
            }
        }
    }
}
