package com.lavasec.app.foundation.dns

import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.DomainFilterDecisionSource

sealed interface DnsDecision {
    data class Forward(
        val question: DnsQuestion,
        val filterDecision: FilterDecision,
    ) : DnsDecision

    class Respond(
        val question: DnsQuestion,
        val filterDecision: FilterDecision,
        response: ByteArray,
    ) : DnsDecision {
        private val responseBytes = response.copyOf()
        val response: ByteArray get() = responseBytes.copyOf()
    }

    class Failure(
        val error: DnsMessageException,
        response: ByteArray?,
    ) : DnsDecision {
        private val responseBytes = response?.copyOf()
        val response: ByteArray? get() = responseBytes?.copyOf()
    }

    companion object {
        /** Invalid wire names fail as DNS SERVFAIL before the filter's invalid-domain policy boundary. */
        fun evaluate(query: ByteArray, snapshot: DomainFilterDecisionSource): DnsDecision {
            val question = try {
                DnsMessage.parseQuestion(query)
            } catch (error: DnsMessageException) {
                return Failure(error, DnsMessage.servfailResponse(query))
            }
            return evaluate(query, question, snapshot)
        }

        fun evaluate(
            query: ByteArray,
            question: DnsQuestion,
            snapshot: DomainFilterDecisionSource,
        ): DnsDecision {
            try {
                question.questionBytes(query)
            } catch (error: DnsMessageException) {
                return Failure(error, DnsMessage.servfailResponse(query))
            }
            val decision = snapshot.decideNormalized(question.normalizedDomain)
            return if (decision.action == FilterAction.ALLOW) {
                Forward(question, decision)
            } else {
                try {
                    Respond(question, decision, DnsMessage.blockedResponse(query, question))
                } catch (error: DnsMessageException) {
                    Failure(error, DnsMessage.servfailResponse(query))
                }
            }
        }
    }
}
