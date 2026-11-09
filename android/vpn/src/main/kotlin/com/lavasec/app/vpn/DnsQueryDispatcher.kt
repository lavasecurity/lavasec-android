package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsDecision
import com.lavasec.app.foundation.pause.PausedQueryDecision

sealed interface DnsQueryDispatchOutcome {
    class Bootstrap(response: ByteArray) : DnsQueryDispatchOutcome {
        private val responseBytes = response.copyOf()
        val response: ByteArray get() = responseBytes.copyOf()
    }

    data class Paused(val decision: PausedQueryDecision) : DnsQueryDispatchOutcome

    data class Filter(val decision: DnsDecision) : DnsQueryDispatchOutcome
}

object DnsQueryDispatcher {
    fun dispatch(
        bootstrapResponse: () -> ByteArray?,
        pausedDecision: () -> PausedQueryDecision?,
        filterDecision: () -> DnsDecision,
    ): DnsQueryDispatchOutcome {
        bootstrapResponse()?.let { return DnsQueryDispatchOutcome.Bootstrap(it) }
        pausedDecision()?.let { return DnsQueryDispatchOutcome.Paused(it) }
        return DnsQueryDispatchOutcome.Filter(filterDecision())
    }
}
