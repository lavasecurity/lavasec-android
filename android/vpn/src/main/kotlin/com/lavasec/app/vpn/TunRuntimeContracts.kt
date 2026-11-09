package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.platform.ProtectionFailure
import java.util.Collections

internal data class TunRuntimeCapacities(
    val activeResolutions: Int,
    val waitingResolutions: Int,
    val materializedWrites: Int,
    val liveRecords: Int,
) {
    init {
        require(activeResolutions > 0) { "active resolutions must be positive" }
        require(waitingResolutions > 0) { "waiting resolutions must be positive" }
        require(materializedWrites > 0) { "materialized writes must be positive" }
        require(liveRecords > 0) { "live records must be positive" }
        require(
            liveRecords.toLong() >=
                activeResolutions.toLong() +
                waitingResolutions.toLong() +
                materializedWrites.toLong() +
                1L,
        ) {
            "live records must cover active, waiting, materialized, and terminal records"
        }
    }

    companion object {
        val Production = TunRuntimeCapacities(
            activeResolutions = 8,
            waitingResolutions = 64,
            materializedWrites = 64,
            liveRecords = 137,
        )
    }
}

internal class TunTerminalEvidence(
    val engineGeneration: Long,
    val activity: DnsDecisionEvent?,
    healthEvents: List<DnsHealthEvent>,
    val resolverQueryEvidence: ResolverQueryHealthEvidence?,
    val primaryAnswerBearing: Boolean = false,
) {
    init {
        require(engineGeneration > 0L)
    }

    private val terminalHealthEvents =
        Collections.unmodifiableList(healthEvents.toList())

    val healthEvents: List<DnsHealthEvent>
        get() = terminalHealthEvents
}

internal interface TunRuntimeObserver {
    fun acceptTerminalEvidence(evidence: TunTerminalEvidence): Boolean
    fun publishHealth(event: DnsHealthEvent)
    fun publishFailure(failure: ProtectionFailure)
}
