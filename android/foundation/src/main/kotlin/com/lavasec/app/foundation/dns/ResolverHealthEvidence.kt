package com.lavasec.app.foundation.dns

/**
 * Pure resolver-health evidence model ported from the canonical iOS sources pinned at
 * `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`:
 *
 * - `Sources/LavaSecDNS/ResolverHealthEvidence.swift` (identity / network-episode / session split)
 * - `Sources/LavaSecDNS/ResolverHealthOrganicEvidence.swift` (organic query evidence)
 * - `Sources/LavaSecDNS/ResolverHealthSmokeEvidence.swift` (smoke-probe evidence)
 * - `Sources/LavaSecDNS/ResolverBootstrapService.swift` (generation fences)
 *
 * No socket, clock, or coroutine is touched here: samples arrive already observed and carry
 * the identity of the runtime generation that admitted them. That identity is what lets
 * [ResolverHealthPolicy] ignore a late response from a retired resolver or network instead of
 * letting it mark the current plan healthy.
 */

/**
 * Identity of the runtime generation an observation was admitted under.
 *
 * [networkGeneration] advances on a meaningful network change, [planIdentifier] names the
 * resolver plan the observation was admitted against, and [admissionToken] is the *session*
 * admission token (`admittedAtEpoch` in the canonical orchestrator) captured at the acceptance
 * boundary and carried across every hop. It is not per-query: every observation made by one
 * live session carries the same token, so a straggler from an earlier session fails the fence.
 */
data class ResolverObservationId(
    val networkGeneration: Long,
    val planIdentifier: String,
    val admissionToken: Long,
)

/** The transport family of the underlying network; drives bootstrap address ordering. */
enum class ResolverNetworkKind { WIFI, CELLULAR, OTHER }

/**
 * Identity and current resolver capture of the underlying network.
 *
 * [capturedResolvers] is the device-DNS capture for this network. A change to the capture is a
 * device-DNS change even when the generation has not moved (some handoffs reuse a generation),
 * so callers that care compare [capturedResolvers] as well as [generation].
 */
data class ResolverNetworkIdentity(
    val generation: Long,
    val kind: ResolverNetworkKind,
    val interfaceIndex: Int?,
    val capturedResolvers: List<String> = emptyList(),
)

/** The two resolver routes the milestone runtime can serve. */
enum class ResolverRouteKind { DEVICE_DNS, ENCRYPTED }

/**
 * The current plan the health policy is judging.
 *
 * [fallback] is `null` when no fallback is configured (iOS `fallbackToDeviceDNS == false` and no
 * encrypted alternative). That single absence is the "fallback disabled" case the policy must
 * distinguish from "fallback enabled but not yet carrying".
 */
data class ResolverHealthPlan(
    val identifier: String,
    val primary: ResolverRouteKind,
    val fallback: ResolverRouteKind?,
) {
    val fallbackEnabled: Boolean get() = fallback != null

    init {
        require(fallback == null || fallback != primary) {
            "the fallback route must differ from the primary route"
        }
    }
}

/**
 * Verdict of one organic query leg, mirroring the canonical
 * `ResolverOrganicUpstreamEvidence.Outcome` classification.
 */
sealed interface ResolverOutcome {
    /**
     * A leg produced a usable answer.
     *
     * [viaEncryptedFallback] and [viaDeviceDnsFallback] name the fallback that carried the
     * answer; both false means the selected primary served it. [primaryAttempted] is the
     * evidence the device-DNS fallback activation counter is gated on: a fallback success only
     * counts when the primary was actually attempted (iOS
     * `nextConsecutiveFallbackEvidenceCount`).
     */
    data class Served(
        val viaEncryptedFallback: Boolean = false,
        val viaDeviceDnsFallback: Boolean = false,
        val primaryAttempted: Boolean = false,
    ) : ResolverOutcome

    /** A wire attempt failed against the upstream. */
    data class Failed(val reason: ResolverFailureReason) : ResolverOutcome

    /** A deliberate local refusal: nothing was sent, so nothing about the upstream is proven. */
    data object DeclinedByPolicy : ResolverOutcome

    /** The resolver answered that the response does not fit UDP; liveness without service. */
    data object Truncated : ResolverOutcome

    /** The chained outage supervisor owns this leg's liveness; the physical policy is withheld. */
    data object GovernedByOutageSupervisor : ResolverOutcome
}

/** One resolver-health observation. */
sealed interface ResolverHealthSample {
    val observation: ResolverObservationId
    val occurredAtMillis: Long

    /** One organic (real client query) leg. */
    data class Organic(
        override val observation: ResolverObservationId,
        override val occurredAtMillis: Long,
        val outcome: ResolverOutcome,
        val resolverIdentifier: String,
        val transport: ResolverTransport,
        val durationMillis: Int? = null,
    ) : ResolverHealthSample

    /** An accepted-primary evidence beat that may skip a routine probe without scoring a query. */
    data class Active(
        override val observation: ResolverObservationId,
        override val occurredAtMillis: Long,
    ) : ResolverHealthSample

    /** A smoke probe whose primary was accepted: resets the failure streak and exits any fallback. */
    data class SmokePrimaryAccepted(
        override val observation: ResolverObservationId,
        override val occurredAtMillis: Long,
        val resolverIdentifier: String,
        val transport: ResolverTransport,
        val reason: String = "periodic-health-check",
        val dohHttpVersion: String? = null,
    ) : ResolverHealthSample

    /** A smoke probe accepted by the device-DNS fallback: feeds the activation counter. */
    data class SmokeFallbackAccepted(
        override val observation: ResolverObservationId,
        override val occurredAtMillis: Long,
        val resolverIdentifier: String,
        val primaryAttempted: Boolean,
        val reason: String = "fallback-recovery",
    ) : ResolverHealthSample

    /** A smoke probe that failed, optionally with a resolver-rejected response. */
    data class SmokeFailed(
        override val observation: ResolverObservationId,
        override val occurredAtMillis: Long,
        val failure: ResolverFailureReason,
        val resolverIdentifier: String,
        val transport: ResolverTransport,
        val rejectedResponse: Boolean = false,
        val probeReason: String = "periodic-health-check",
    ) : ResolverHealthSample

    /** A smoke probe declined by egress policy: neutral, but the probe cadence still advanced. */
    data class SmokeDeclinedByPolicy(
        override val observation: ResolverObservationId,
        override val occurredAtMillis: Long,
        val reason: String = "declined-by-policy",
    ) : ResolverHealthSample
}

/** Rejected-response identity evidence (the iOS `ResolverIdentityEvidence`). Survives network churn. */
data class ResolverIdentityEvidence(
    val primaryIdentifier: String? = null,
    val rejectedResponseCount: Int = 0,
    val rejectedResponseResolverIdentifier: String? = null,
)

/** Network-episode evidence (the iOS `ResolverNetworkEpisodeEvidence`). Resets on network change. */
data class ResolverHealthEpisode(
    val consecutiveUpstreamFailures: Int = 0,
    val consecutiveSmokeFailures: Int = 0,
    val deviceDnsFallbackEvidence: Int = 0,
    val deviceDnsFallbackModeActive: Boolean = false,
    val lastAcceptedPrimaryEvidenceAtMillis: Long? = null,
    val lastFailureReason: ResolverFailureReason? = null,
    val reconnectEpisodeActive: Boolean = false,
)

/** Tunnel-session evidence (the iOS `ResolverTunnelSessionEvidence`), narrowed to what the policy reads. */
data class ResolverSessionEvidence(
    val networkPathIsSatisfied: Boolean = true,
    val upstreamSuccessCount: Int = 0,
    val upstreamFailureCount: Int = 0,
    val consecutiveSlowUpstreamResponses: Int = 0,
    val truncatedResponseCount: Int = 0,
    val lastUpstreamDurationMillis: Int? = null,
    val lastResolverIdentifier: String? = null,
    val lastResolverTransport: ResolverTransport? = null,
)

/** The complete health state. Identity and session survive network churn; the episode does not. */
data class ResolverHealthState(
    val observation: ResolverObservationId,
    val network: ResolverNetworkIdentity,
    val plan: ResolverHealthPlan,
    val episode: ResolverHealthEpisode = ResolverHealthEpisode(),
    val identity: ResolverIdentityEvidence = ResolverIdentityEvidence(),
    val session: ResolverSessionEvidence = ResolverSessionEvidence(),
)

/** The distilled verdicts the runtime slice consumes. */
enum class ResolverHealthVerdict { HEALTHY, DEGRADED, SWITCH, FALLBACK, RECOVER }

/** Orderly side effects a runtime executor interprets; no I/O happens in this model. */
sealed interface ResolverHealthEffect {
    /** Republish the derived health projection. */
    data object PublishHealth : ResolverHealthEffect

    /** The network path is unsatisfied; await a new network instead of scoring the resolver. */
    data object AwaitNetwork : ResolverHealthEffect

    /** Recommend replacing the current primary resolver. */
    data object SwitchResolver : ResolverHealthEffect

    /** Recommend activating the configured fallback route. */
    data object ActivateFallback : ResolverHealthEffect

    /** The fallback episode ended; return to the primary route. */
    data object ExitFallback : ResolverHealthEffect

    /** Schedule the wedge/fallback recovery probe. */
    data object ScheduleRecoveryProbe : ResolverHealthEffect

    /** Evidence carried an identity that is no longer current and was dropped. */
    data class IgnoreStaleEvidence(val observation: ResolverObservationId) : ResolverHealthEffect
}

/**
 * One reduction result: the next state, the derived verdict, whether the sample was attributed
 * to the current generation, and the ordered effects a runtime should execute.
 */
data class ResolverHealthTransition(
    val state: ResolverHealthState,
    val verdict: ResolverHealthVerdict,
    val applied: Boolean,
    val effects: List<ResolverHealthEffect>,
)
