package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverSmokeProbeCadence
import com.lavasec.app.foundation.dns.TunWriteFailureReason
import com.lavasec.app.foundation.health.ContextualProtectionHealthObservation
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.PrimaryResolverOutcome
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationEvidence
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import com.lavasec.app.foundation.health.ProtectionHealthContext
import com.lavasec.app.foundation.health.ProtectionHealthObservation
import com.lavasec.app.foundation.health.ProtectionHealthPolicy
import com.lavasec.app.foundation.health.ProtectionHealthReducer
import com.lavasec.app.foundation.health.ProtectionHealthSeverity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.health.ResolverTierDiagnosticObservation
import com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome
import com.lavasec.app.foundation.health.SelfReconnectDecision
import com.lavasec.app.foundation.health.TunnelSelfReconnectPolicy
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnHealthEngineIdentity(
    val sessionGeneration: Long,
    val networkGeneration: Long,
    val engineGeneration: Long,
    val primaryResolverIdentity: PrimaryResolverIdentity,
    val fallbackCoverage: ResolverFallbackCoverage,
    val primaryResolverKind: com.lavasec.app.foundation.dns.ResolverKind = com.lavasec.app.foundation.dns.ResolverKind.MULLVAD_DOH,
) {
    init {
        require(sessionGeneration > 0L)
        require(networkGeneration > 0L)
        require(engineGeneration > 0L)
    }
}

data class VpnHealthNotificationEvidence(
    val lastRejectedResponseAtMillis: Long? = null,
    val lastTransportFailureAtMillis: Long? = null,
    val lastSlowResponseAtMillis: Long? = null,
    val lastPrimarySuccessAtMillis: Long? = null,
    val lastFallbackSuccessAtMillis: Long? = null,
) {
    fun toPolicyEvidence(): ProtectionConnectivityNotificationEvidence =
        ProtectionConnectivityNotificationEvidence(
            lastResolverFailureAtMillis = listOfNotNull(
                lastRejectedResponseAtMillis,
                lastTransportFailureAtMillis,
            ).maxOrNull(),
            lastSlowResponseAtMillis = lastSlowResponseAtMillis,
            lastPrimarySuccessAtMillis = lastPrimarySuccessAtMillis,
            lastFallbackSuccessAtMillis = lastFallbackSuccessAtMillis,
        )
}

data class VpnHealthStatus(
    val revision: Long = 0L,
    val health: ProtectionHealthAccumulator = ProtectionHealthAccumulator(),
    val notificationEvidence: VpnHealthNotificationEvidence = VpnHealthNotificationEvidence(),
    val droppedPrivateTelemetryCount: Long = 0L,
    val smokeProbeFailureCount: Int = 0,
    val diagnostics: VpnSessionDiagnostics = VpnSessionDiagnostics(),
    val chainedDiagnostics: com.lavasec.app.vpn.chained.ChainedNerdDiagnostics? = null,
    /** Surface-only platform evidence, absent for a chained or unobserved runtime. */
    val internetValidated: Boolean? = null,
)

internal class ExactVpnHealthState {
    private val lock = ReentrantLock()
    private val mutableState = MutableStateFlow(VpnHealthStatus())
    private var owner: VpnServiceOwner? = null

    val state: StateFlow<VpnHealthStatus> = mutableState.asStateFlow()

    fun install(next: VpnServiceOwner) = lock.withLock {
        owner = next
        mutableState.value = VpnHealthStatus()
    }

    fun publish(expected: VpnServiceOwner, status: VpnHealthStatus): Boolean = lock.withLock {
        if (owner != expected) return@withLock false
        mutableState.value = status
        true
    }

    fun retire(expected: VpnServiceOwner): Boolean = lock.withLock {
        if (owner != expected) return@withLock false
        owner = null
        mutableState.value = VpnHealthStatus()
        true
    }
}

/** Exact-session reducer and reconnect admission owner; it stores no DNS name or private payload. */
internal class ExactSessionHealthOwner(
    private val sessionGeneration: Long,
    private val nowMillis: () -> Long,
    private val publish: (VpnHealthStatus) -> Unit,
    private val requestReconnect: (engineGeneration: Long) -> Unit,
) {
    private val lock = ReentrantLock()
    private var identity: VpnHealthEngineIdentity? = null
    private var observationSequence = 0L
    private var status = VpnHealthStatus()
    private var reconnectAttempts = emptyList<Long>()
    private var deviceResolverEpoch: EngineEpoch? = null
    private var deviceResolverReconnectEpisode: PrimaryResolverIdentity? = null
    private var primaryResolverEpoch: EngineEpoch? = null
    private var lastSmokeSequence = -1L
    private var lastOrganicPrimaryAnswerAtMillis: Long? = null

    init {
        require(sessionGeneration > 0L)
    }

    fun installEngine(
        next: VpnHealthEngineIdentity,
        devicePrimaryEpoch: EngineEpoch? = null,
        primaryEpoch: EngineEpoch? = devicePrimaryEpoch,
        internetValidated: Boolean? = null,
    ) {
        require(next.sessionGeneration == sessionGeneration)
        require(devicePrimaryEpoch == null || next.matches(devicePrimaryEpoch)) {
            "Device health authority must belong to the installed engine epoch"
        }
        require(primaryEpoch == null || next.matches(primaryEpoch)) { "primary probe must belong to the installed epoch" }
        val publication = lock.withLock {
            val current = identity
            if (current != null) {
                require(next.networkGeneration >= current.networkGeneration)
                require(
                    next.networkGeneration > current.networkGeneration ||
                        next.engineGeneration > current.engineGeneration,
                ) { "health engine identity must advance" }
            }
            // A reconnect of the same actual Device primary does not prove its recovery.
            // A role/identity change cannot carry that old resolver's rejection allowance.
            if (devicePrimaryEpoch == null || current?.primaryResolverIdentity != next.primaryResolverIdentity) {
                deviceResolverReconnectEpisode = null
            }
            deviceResolverEpoch = devicePrimaryEpoch
            if (primaryEpoch == null || current?.primaryResolverIdentity != next.primaryResolverIdentity ||
                current?.networkGeneration != next.networkGeneration) {
                status = status.copy(smokeProbeFailureCount = 0)
            }
            primaryResolverEpoch = primaryEpoch
            // Organic evidence belongs to this installed network/engine, including reconnects.
            lastOrganicPrimaryAnswerAtMillis = null
            status = status.copy(diagnostics = status.diagnostics.installed(current, next, checkedNow()),
                internetValidated = internetValidated)
            identity = next
            reduceLocked(ProtectionHealthObservation.ConnectionChanged(true))
            advanceLocked()
        }
        publishSafely(publication)
    }

    fun recordResolverQuery(
        engineGeneration: Long,
        evidence: ResolverQueryHealthEvidence,
    ): Boolean = recordBatch(engineGeneration, emptyList(), evidence)

    /**
     * Records a connection-state transition for [engineGeneration].
     *
     * [installEngine] already publishes the initial `ConnectionChanged(true)`. This reports later
     * transitions for a session that is not driven by the DNS engine — a chained session going
     * `Established`→`Recovering`→`Established` — so the health accumulator's `isConnected` tracks
     * the live session instead of staying at its reset default (`false`), which would classify an
     * otherwise `Protected` session as `RECONNECT_NEEDED` and withhold Guard/resolver health.
     *
     * @return `false` when no engine is installed or [engineGeneration] is stale.
     */
    fun recordConnectionChanged(engineGeneration: Long, connected: Boolean): Boolean {
        val publication = lock.withLock {
            val current = identity ?: return false
            if (current.engineGeneration != engineGeneration) return false
            if (!connected) {
                status = status.copy(internetValidated = null)
                deviceResolverReconnectEpisode = null
                lastOrganicPrimaryAnswerAtMillis = null
                status = status.copy(smokeProbeFailureCount = 0)
            }
            reduceLocked(ProtectionHealthObservation.ConnectionChanged(connected))
            advanceLocked()
        }
        publishSafely(publication)
        return true
    }

    fun recordHealth(engineGeneration: Long, event: DnsHealthEvent): Boolean =
        recordBatch(engineGeneration, listOf(event), null)

    fun recordBatch(
        engineGeneration: Long,
        healthEvents: List<DnsHealthEvent>,
        resolverQueryEvidence: ResolverQueryHealthEvidence?,
        primaryAnswerBearing: Boolean = false,
    ): Boolean {
        var reconnect: Long? = null
        val publication = lock.withLock {
            val current = identity ?: return false
            if (current.engineGeneration != engineGeneration) return false
            val observedAt = checkedNow()
            healthEvents.forEach { event ->
                reduceLocked(ProtectionHealthObservation.Health(event), observedAt)
                updateHealthNotificationEvidenceLocked(event, observedAt)
            }
            resolverQueryEvidence?.let { evidence ->
                val priorQueryIds = status.health.recentResolverQueryIds
                reduceLocked(ProtectionHealthObservation.ResolverQueryCompleted(evidence), observedAt)
                updateNotificationEvidenceLocked(evidence, observedAt)
                // Only newly admitted terminal evidence can arm or clear the episode. A replayed
                // old success cannot erase a real later outage, and lifecycle/local failures
                // cannot manufacture a broad resolver failure.
                if (evidence.queryId !in priorQueryIds && evidence.queryId in status.health.recentResolverQueryIds) {
                    status = status.copy(diagnostics = status.diagnostics.query(evidence, observedAt))
                    updateDeviceReconnectEpisodeLocked(current, evidence)
                    if (primaryAnswerBearing && primaryResolverEpoch != null &&
                        evidence.primaryWasServed() &&
                        evidence.successfulFallback == ProtectionFallback.NONE && status.health.isConnected
                    ) {
                        lastOrganicPrimaryAnswerAtMillis = observedAt
                    }
                }
            }
            if (
                resolverQueryEvidence != null &&
                status.health.assessment.severity == ProtectionHealthSeverity.DNS_SLOW
            ) {
                status = status.copy(
                    notificationEvidence = status.notificationEvidence.copy(
                        lastSlowResponseAtMillis = observedAt,
                    ),
                )
            }
            reconnect = reconnectIfAdmittedLocked(current.engineGeneration, observedAt)
            advanceLocked()
        }
        publishSafely(publication)
        reconnect?.let { engine -> requestReconnectSafely(engine) }
        return true
    }

    fun recordPrivateTelemetryDrop(engineGeneration: Long): Boolean {
        val publication = lock.withLock {
            val current = identity ?: return false
            if (current.engineGeneration != engineGeneration) return false
            status = status.copy(
                droppedPrivateTelemetryCount = saturatedIncrement(
                    status.droppedPrivateTelemetryCount,
                ),
            )
            advanceLocked()
        }
        publishSafely(publication)
        return true
    }

    fun recordChainedDiagnostics(engineGeneration: Long, value: com.lavasec.app.vpn.chained.ChainedNerdDiagnostics): Boolean {
        val publication = lock.withLock {
            if (identity?.engineGeneration != engineGeneration) return false
            if (status.chainedDiagnostics == value) return true
            status = status.copy(chainedDiagnostics = value)
            advanceLocked()
        }
        publishSafely(publication)
        return true
    }

    fun current(): VpnHealthStatus = lock.withLock { status }

    /** This evidence never runs the DNS reducer, reconnect admission or notification policy. */
    fun recordInternetValidation(engineGeneration: Long, validated: Boolean): Boolean {
        val publication = lock.withLock {
            if (identity?.engineGeneration != engineGeneration) return false
            if (!status.health.isConnected) return false
            if (status.internetValidated == validated) return true
            status = status.copy(internetValidated = validated)
            advanceLocked()
        }
        publishSafely(publication)
        return true
    }

    fun primaryProbeIsCurrent(epoch: EngineEpoch): Boolean = lock.withLock {
        primaryResolverEpoch == epoch && identity?.matches(epoch) == true &&
            status.health.isConnected && status.health.networkAvailable
    }

    /** Missing/negative/old evidence never waives a routine wire probe. Forced probes ignore this. */
    fun hasRecentOrganicPrimaryAnswer(epoch: EngineEpoch, nowMillis: Long, intervalMillis: Long): Boolean = lock.withLock {
        val current = identity ?: return false
        val evidenceAt = lastOrganicPrimaryAnswerAtMillis ?: return false
        if (primaryResolverEpoch != epoch || !current.matches(epoch) || intervalMillis <= 0L ||
            !status.health.isConnected || !status.health.networkAvailable ||
            status.smokeProbeFailureCount != 0 || status.health.consecutiveResolverFailures != 0 ||
            status.health.consecutivePrimaryRejections != 0 || status.health.requiresReconnect ||
            status.health.activeFallback != ProtectionFallback.NONE || deviceResolverReconnectEpisode != null
        ) return false
        // Check order before subtraction, so a future stamp or overflow cannot suppress probes.
        nowMillis >= evidenceAt && nowMillis - evidenceAt in 0L..intervalMillis
    }

    /** Actual primary-only canary completion; neither rescue nor generic service state is evidence. */
    fun recordSmokeProbe(
        epoch: EngineEpoch,
        sequence: Long,
        queryId: Long,
        query: ByteArray,
        result: ResolverResult,
        latencyMillis: Long = 0L,
    ): Boolean {
        require(latencyMillis >= 0L)
        var reconnect: Long? = null
        val publication = lock.withLock {
            val current = identity ?: return false
            if (primaryResolverEpoch != epoch || !current.matches(epoch) || sequence <= lastSmokeSequence ||
                !status.health.isConnected || !status.health.networkAvailable) return false
            if (result is ResolverResult.Failure && result.reason.isProbeAbandonment()) return false
            lastSmokeSequence = sequence
            val reply = (result as? ResolverResult.Answer)?.let { DnsResolverSmokeProbe.classify(query, it.dnsMessage) }
            val failure = result as? ResolverResult.Failure
            val rawPrimary = result.resolverQueryEvidence?.tierObservations?.firstOrNull()
            val peerLivenessOnly = failure?.peerAnswered == true || rawPrimary?.peerLivenessOnly == true
            val local = failure?.local == true || rawPrimary?.outcome == ResolverTierDiagnosticOutcome.NOT_ATTEMPTED
            val neutral = peerLivenessOnly || local
            val queryEvidence = when (reply) {
                DnsResolverSmokeProbe.Reply.ACCEPTED_ANSWER -> ResolverQueryHealthEvidence.primaryAccepted(queryId, latencyMillis)
                DnsResolverSmokeProbe.Reply.REJECTED_OR_ANSWERLESS ->
                    ResolverQueryHealthEvidence(queryId, PrimaryResolverOutcome.REJECTED)
                else -> ResolverQueryHealthEvidence.primaryFailed(queryId,
                    failure?.reason ?: ResolverFailureReason.INVALID_RESPONSE)
            }
            val observedAt = checkedNow()
            val resolver = rawPrimary?.resolver ?: failure?.resolver ?: when ((result as? ResolverResult.Answer)?.transport) {
                com.lavasec.app.foundation.dns.ResolverTransport.DEVICE_UDP, com.lavasec.app.foundation.dns.ResolverTransport.DEVICE_TCP -> com.lavasec.app.foundation.dns.ResolverKind.DEVICE
                com.lavasec.app.foundation.dns.ResolverTransport.PLAIN_UDP, com.lavasec.app.foundation.dns.ResolverTransport.PLAIN_TCP -> com.lavasec.app.foundation.dns.ResolverKind.PLAIN_DNS
                else -> if (deviceResolverEpoch == epoch) com.lavasec.app.foundation.dns.ResolverKind.DEVICE else current.primaryResolverKind
            }
            val outcome = when {
                peerLivenessOnly -> ResolverTierDiagnosticOutcome.ANSWERED
                local -> ResolverTierDiagnosticOutcome.NOT_ATTEMPTED
                reply == DnsResolverSmokeProbe.Reply.ACCEPTED_ANSWER -> ResolverTierDiagnosticOutcome.SERVED
                reply == DnsResolverSmokeProbe.Reply.REJECTED_OR_ANSWERLESS -> ResolverTierDiagnosticOutcome.ANSWERED
                else -> ResolverTierDiagnosticOutcome.FAILED
            }
            val observation = (rawPrimary ?: ResolverTierDiagnosticObservation(1, resolver, outcome)).copy(
                outcome = outcome,
                transport = (result as? ResolverResult.Answer)?.transport ?: rawPrimary?.transport,
                failure = failure?.reason ?: rawPrimary?.failure,
                peerLivenessOnly = peerLivenessOnly,
                packetSent = if (peerLivenessOnly) true else if (local) false else rawPrimary?.packetSent ?: if (result is ResolverResult.Answer) true else null,
            )
            // Keep this completion's query identity and the stricter smoke answer bar, while
            // carrying the same raw peer/local facts to every downstream reducer.
            val evidence = queryEvidence.copy(
                tierObservations = listOf(observation),
                httpProtocol = (result as? ResolverResult.Answer)?.httpProtocol ?: result.resolverQueryEvidence?.httpProtocol,
            )
            status = status.copy(diagnostics = status.diagnostics.query(evidence, observedAt, smoke = true))
            reduceLocked(ProtectionHealthObservation.ResolverQueryCompleted(evidence), observedAt)
            updateNotificationEvidenceLocked(evidence, observedAt)
            updateDeviceReconnectEpisodeLocked(current, evidence)
            val served = evidence.primaryWasServed()
            status = status.copy(smokeProbeFailureCount = if (served) 0
                else if (neutral) status.smokeProbeFailureCount
                else ResolverSmokeProbeCadence.nextFailureCount(status.smokeProbeFailureCount))
            if (served) {
                deviceResolverReconnectEpisode = null
            } else if (!neutral && deviceResolverEpoch == epoch &&
                status.smokeProbeFailureCount >= ProtectionHealthPolicy.RECONNECT_FAILURE_THRESHOLD &&
                status.health.assessment.severity == ProtectionHealthSeverity.NEEDS_RECONNECT) {
                deviceResolverReconnectEpisode = current.primaryResolverIdentity
            }
            if (!neutral) reconnect = reconnectIfAdmittedLocked(current.engineGeneration, observedAt)
            advanceLocked()
        }
        publishSafely(publication)
        reconnect?.let(::requestReconnectSafely)
        return true
    }

    /** Exact immutable query epoch, never the service's generic Reconnecting presentation. */
    fun deviceResolverReconnectEpisodeIsActive(epoch: EngineEpoch): Boolean = lock.withLock {
        deviceResolverEpoch == epoch && identity?.matches(epoch) == true &&
            status.health.isConnected && status.health.networkAvailable &&
            deviceResolverReconnectEpisode == epoch.primaryResolverIdentity
    }

    private fun updateDeviceReconnectEpisodeLocked(
        current: VpnHealthEngineIdentity,
        evidence: ResolverQueryHealthEvidence,
    ) {
        val rawPrimary = evidence.tierObservations.firstOrNull()
        if (rawPrimary?.outcome == com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.NOT_ATTEMPTED) return
        if (rawPrimary?.peerLivenessOnly == true) { deviceResolverReconnectEpisode = null; return }
        if (rawPrimary?.outcome == com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.ANSWERED) return
        if (evidence.primaryWasServed()) {
            status = status.copy(smokeProbeFailureCount = 0)
        }
        if (deviceResolverEpoch == null) return
        if (evidence.primaryWasServed()) {
            deviceResolverReconnectEpisode = null
        } else if (evidence.primaryOutcome == PrimaryResolverOutcome.FAILED &&
            evidence.successfulFallback == ProtectionFallback.NONE &&
            evidence.primaryFailureReason?.isActionableTransportFailure() == true &&
            status.health.isConnected && status.health.networkAvailable &&
            status.health.consecutiveResolverFailures >= ProtectionHealthPolicy.RECONNECT_FAILURE_THRESHOLD &&
            status.health.assessment.severity == ProtectionHealthSeverity.NEEDS_RECONNECT
        ) {
            deviceResolverReconnectEpisode = current.primaryResolverIdentity
        }
    }

    private fun reduceLocked(
        observation: ProtectionHealthObservation,
        observedAt: Long = checkedNow(),
    ) {
        val currentIdentity = checkNotNull(identity)
        observationSequence = Math.addExact(observationSequence, 1L)
        status = status.copy(
            health = ProtectionHealthReducer.reduce(
                status.health,
                ContextualProtectionHealthObservation(
                    context = ProtectionHealthContext(
                        sessionGeneration = currentIdentity.sessionGeneration,
                        networkGeneration = currentIdentity.networkGeneration,
                        engineGeneration = currentIdentity.engineGeneration,
                        observationSequence = observationSequence,
                        observedAtMillis = observedAt,
                        primaryResolverIdentity = currentIdentity.primaryResolverIdentity,
                        fallbackCoverage = currentIdentity.fallbackCoverage,
                    ),
                    observation = observation,
                ),
            ),
        )
    }

    private fun updateNotificationEvidenceLocked(
        evidence: ResolverQueryHealthEvidence,
        observedAt: Long,
    ) {
        val rawPrimary = evidence.tierObservations.firstOrNull()
        if (rawPrimary?.outcome == com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.NOT_ATTEMPTED || rawPrimary?.peerLivenessOnly == true) {
            if (evidence.successfulFallback != ProtectionFallback.NONE) status = status.copy(
                notificationEvidence = status.notificationEvidence.copy(lastFallbackSuccessAtMillis = observedAt))
            return
        }
        val prior = status.notificationEvidence
        val serviceOutcome = if (rawPrimary?.outcome == com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.ANSWERED)
            PrimaryResolverOutcome.REJECTED else evidence.primaryOutcome
        status = status.copy(
            notificationEvidence = when (serviceOutcome) {
                PrimaryResolverOutcome.ACCEPTED -> prior.copy(
                    lastPrimarySuccessAtMillis = observedAt,
                )
                PrimaryResolverOutcome.REJECTED -> prior.copy(
                    lastRejectedResponseAtMillis = observedAt,
                    lastFallbackSuccessAtMillis = if (
                        evidence.successfulFallback != ProtectionFallback.NONE
                    ) {
                        observedAt
                    } else {
                        prior.lastFallbackSuccessAtMillis
                    },
                )
                PrimaryResolverOutcome.FAILED -> prior.copy(
                    lastTransportFailureAtMillis = if (
                        evidence.primaryFailureReason?.isActionableTransportFailure() == true
                    ) {
                        observedAt
                    } else {
                        prior.lastTransportFailureAtMillis
                    },
                    lastFallbackSuccessAtMillis = if (
                        evidence.successfulFallback != ProtectionFallback.NONE
                    ) {
                        observedAt
                    } else {
                        prior.lastFallbackSuccessAtMillis
                    },
                )
            },
        )
    }

    /** A delivered DNS error can prove reachability while still withholding service credit. */
    private fun ResolverQueryHealthEvidence.primaryWasServed(): Boolean =
        primaryOutcome == PrimaryResolverOutcome.ACCEPTED && (tierObservations.firstOrNull()?.let {
            it.outcome == com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome.SERVED && !it.peerLivenessOnly
        } != false)

    private fun updateHealthNotificationEvidenceLocked(
        event: DnsHealthEvent,
        observedAt: Long,
    ) {
        val prior = status.notificationEvidence
        val next = when (event) {
            is DnsHealthEvent.FallbackActivated -> prior.copy(
                lastFallbackSuccessAtMillis = observedAt,
            )
            is DnsHealthEvent.ProtectionUnavailable -> when (event.reason) {
                ProtectionUnavailableReason.NetworkUnavailable -> prior
                is ProtectionUnavailableReason.Artifact,
                is ProtectionUnavailableReason.ResolverPlan,
                ProtectionUnavailableReason.RuntimeUnavailable,
                -> prior.copy(lastTransportFailureAtMillis = observedAt)
            }
            is DnsHealthEvent.TunWriteFailed -> when (event.reason) {
                TunWriteFailureReason.STALE_SESSION -> prior
                TunWriteFailureReason.DESCRIPTOR_CLOSED,
                TunWriteFailureReason.WRITE_FAILED,
                -> prior.copy(lastTransportFailureAtMillis = observedAt)
            }
            is DnsHealthEvent.ResolverAttemptFailed,
            is DnsHealthEvent.ResolverRecovered,
            -> prior
        }
        if (next != prior) status = status.copy(notificationEvidence = next)
    }

    private fun reconnectIfAdmittedLocked(engineGeneration: Long, now: Long): Long? {
        reconnectAttempts = TunnelSelfReconnectPolicy.prunedAttemptTimes(reconnectAttempts, now)
        return when (
            TunnelSelfReconnectPolicy.decision(
                assessment = status.health.assessment,
                protectionEnabled = true,
                recoveryArmed = status.health.isConnected,
                restartReason = TunnelSelfReconnectPolicy.RestartReason.WEDGE,
                recentReconnectTimesEpochMillis = reconnectAttempts,
                nowEpochMillis = now,
            )
        ) {
            SelfReconnectDecision.RECONNECT -> {
                reconnectAttempts = reconnectAttempts + now
                engineGeneration
            }
            SelfReconnectDecision.THROTTLED,
            SelfReconnectDecision.NO_ACTION,
            -> null
        }
    }

    private fun advanceLocked(): VpnHealthStatus {
        status = status.copy(revision = Math.addExact(status.revision, 1L))
        return status
    }

    private fun checkedNow(): Long = nowMillis().also { require(it >= 0L) }

    private fun publishSafely(publication: VpnHealthStatus) {
        runCatching { publish(publication) }
    }

    private fun requestReconnectSafely(engineGeneration: Long) {
        runCatching { requestReconnect(engineGeneration) }
    }

    private fun saturatedIncrement(value: Long): Long =
        if (value == Long.MAX_VALUE) value else value + 1L
}

private fun ResolverFailureReason.isActionableTransportFailure(): Boolean = when (this) {
    ResolverFailureReason.TIMEOUT,
    ResolverFailureReason.PROTECT_FAILED,
    ResolverFailureReason.BIND_FAILED,
    ResolverFailureReason.SOCKET_FAILED,
    ResolverFailureReason.SEND_FAILED,
    ResolverFailureReason.RECEIVE_FAILED,
    ResolverFailureReason.MISMATCH_LIMIT,
    ResolverFailureReason.INVALID_RESPONSE,
    ResolverFailureReason.OVERSIZED_RESPONSE,
    ResolverFailureReason.HTTP_STATUS,
    ResolverFailureReason.HTTP_CONTENT_TYPE,
    ResolverFailureReason.TLS_FAILED,
    -> true

    ResolverFailureReason.BACKED_OFF,
    ResolverFailureReason.CANCELLED,
    ResolverFailureReason.STALE_EPOCH,
    ResolverFailureReason.OVERLOADED,
    -> false
}

private fun VpnHealthEngineIdentity.matches(epoch: EngineEpoch): Boolean =
    sessionGeneration == epoch.sessionGeneration && networkGeneration == epoch.networkGeneration &&
        engineGeneration == epoch.engineGeneration && primaryResolverIdentity == epoch.primaryResolverIdentity &&
        fallbackCoverage == epoch.fallbackCoverage
