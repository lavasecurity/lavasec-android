package com.lavasec.app.foundation.dns

import kotlin.math.min

/**
 * Pure health / transition policy distilled from the canonical iOS
 * `ResolverHealthCoordinator` + `ResolverHealthOrganicEvidence` + `ResolverHealthSmokeEvidence`
 * (pinned `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`).
 *
 * The policy owns three evidence lifetimes, exactly as the canonical
 * `ResolverHealthEvidenceState` does:
 *
 * - **identity** — the rejected-response streak, keyed by primary resolver identifier; survives
 *   network churn until a primary identity change or accepted-primary recovery;
 * - **episode** — failure/fallback counters, which follow the current network/runtime context and
 *   reset on a meaningful network change;
 * - **session** — cumulative transport metrics that survive both.
 *
 * Every observation is fenced by its [ResolverObservationId]. A sample whose identity is not the
 * current one is dropped ([ResolverHealthTransition.applied] is false) so a late response from a
 * retired resolver or network can never mark the new plan healthy. This is the client-side half of
 * the canonical `ResolverBootstrapService` generation fence and the orchestrator's
 * `admissionIsCurrent` guard.
 */
object ResolverHealthPolicy {
    /** Consecutive fallback successes that latch device-DNS fallback mode (iOS `queryFallbackActivationThreshold`). */
    const val DEVICE_DNS_FALLBACK_ACTIVATION_THRESHOLD: Int = 3

    /** Consecutive failures at which the policy stops calling the primary healthy. */
    const val RECONNECT_FAILURE_THRESHOLD: Int = 3

    /** Resolved-query round-trip latency at which the resolver is called slow (iOS 2.5 s). */
    const val SLOW_RESPONSE_THRESHOLD_MILLIS: Int = 2_500

    /** Saturation point for failure streaks so a chronic network cannot grow them without bound. */
    const val MAX_TRACKED_FAILURES: Int = 999

    /** The clean initial state for a freshly admitted plan/network. */
    fun initial(
        observation: ResolverObservationId,
        network: ResolverNetworkIdentity,
        plan: ResolverHealthPlan,
    ): ResolverHealthState = ResolverHealthState(
        observation = observation,
        network = network,
        plan = plan,
    )

    /**
     * A meaningful network/device-DNS change.
     *
     * The episode resets (its counters describe the old context) while identity and session
     * evidence survive, matching INV-DNS-4. The caller supplies the new [observation] because the
     * network generation and admission token move with the change.
     */
    fun onUnderlyingNetworkChanged(
        state: ResolverHealthState,
        network: ResolverNetworkIdentity,
        observation: ResolverObservationId,
    ): ResolverHealthTransition {
        val next = state.copy(
            observation = observation,
            network = network,
            episode = ResolverHealthEpisode(),
        )
        return ResolverHealthTransition(
            state = next,
            verdict = verdictOf(next),
            applied = true,
            effects = listOf(ResolverHealthEffect.PublishHealth),
        )
    }

    /** The network path gained/lost satisfaction without a generation change. */
    fun onNetworkPathChanged(
        state: ResolverHealthState,
        isSatisfied: Boolean,
    ): ResolverHealthTransition {
        val next = state.copy(
            session = state.session.copy(networkPathIsSatisfied = isSatisfied),
        )
        return ResolverHealthTransition(
            state = next,
            verdict = verdictOf(next),
            applied = true,
            effects = if (isSatisfied) {
                listOf(ResolverHealthEffect.PublishHealth)
            } else {
                listOf(ResolverHealthEffect.AwaitNetwork, ResolverHealthEffect.PublishHealth)
            },
        )
    }

    /**
     * Reduce one observation.
     *
     * A stale observation is dropped without touching state. Everything else is attributed and
     * reduced per lifetime. "Declined" outcomes advance nothing: a local refusal sends no packet,
     * so scoring it would spend the outage budget on a query the device chose not to make.
     */
    fun observe(
        state: ResolverHealthState,
        sample: ResolverHealthSample,
    ): ResolverHealthTransition {
        if (sample.observation != state.observation) {
            return ResolverHealthTransition(
                state = state,
                verdict = verdictOf(state),
                applied = false,
                effects = listOf(ResolverHealthEffect.IgnoreStaleEvidence(sample.observation)),
            )
        }
        return when (sample) {
            is ResolverHealthSample.Organic -> observeOrganic(state, sample)
            is ResolverHealthSample.Active -> observeActive(state, sample)
            is ResolverHealthSample.SmokePrimaryAccepted -> observeSmokePrimaryAccepted(state, sample)
            is ResolverHealthSample.SmokeFallbackAccepted -> observeSmokeFallbackAccepted(state, sample)
            is ResolverHealthSample.SmokeFailed -> observeSmokeFailed(state, sample)
            is ResolverHealthSample.SmokeDeclinedByPolicy -> applied(state, listOf(ResolverHealthEffect.PublishHealth))
        }
    }

    /** The verdict implied by the current state, without a new observation. */
    fun verdictOf(state: ResolverHealthState): ResolverHealthVerdict = when {
        !state.session.networkPathIsSatisfied -> ResolverHealthVerdict.DEGRADED
        state.episode.deviceDnsFallbackModeActive -> ResolverHealthVerdict.FALLBACK
        state.episode.consecutiveUpstreamFailures >= RECONNECT_FAILURE_THRESHOLD ->
            if (state.plan.fallbackEnabled) ResolverHealthVerdict.FALLBACK else ResolverHealthVerdict.SWITCH
        state.episode.consecutiveUpstreamFailures > 0 ||
            state.episode.consecutiveSmokeFailures > 0 ||
            state.session.consecutiveSlowUpstreamResponses > 0 -> ResolverHealthVerdict.DEGRADED
        else -> ResolverHealthVerdict.HEALTHY
    }

    private fun observeOrganic(
        state: ResolverHealthState,
        sample: ResolverHealthSample.Organic,
    ): ResolverHealthTransition {
        var session = state.session.copy(
            lastResolverIdentifier = sample.resolverIdentifier,
            lastResolverTransport = sample.transport,
        )
        sample.durationMillis?.let { duration ->
            session = session.copy(
                lastUpstreamDurationMillis = duration,
                consecutiveSlowUpstreamResponses = if (duration >= SLOW_RESPONSE_THRESHOLD_MILLIS) {
                    session.consecutiveSlowUpstreamResponses + 1
                } else {
                    0
                },
            )
        }

        return when (val outcome = sample.outcome) {
            ResolverOutcome.GovernedByOutageSupervisor -> {
                // The chained outage supervisor owns liveness; withhold every physical verdict AND
                // clear an existing physical wedge rather than looping a reconnect that cannot help.
                val next = state.copy(
                    session = session.copy(consecutiveSlowUpstreamResponses = 0),
                    episode = state.episode.copy(
                        consecutiveUpstreamFailures = 0,
                        lastFailureReason = null,
                        reconnectEpisodeActive = false,
                    ),
                )
                applied(next, listOf(ResolverHealthEffect.PublishHealth))
            }

            ResolverOutcome.DeclinedByPolicy -> {
                // Nothing was sent: neutral for the aggregate, but the last-resolver fields above
                // are still the diagnostic.
                applied(state.copy(session = session), emptyList())
            }

            ResolverOutcome.Truncated -> {
                // The resolver answered; liveness without service. No failure credit, no recovery.
                val next = state.copy(
                    session = session.copy(truncatedResponseCount = session.truncatedResponseCount + 1),
                )
                applied(next, listOf(ResolverHealthEffect.PublishHealth))
            }

            is ResolverOutcome.Failed -> {
                if (outcome.reason.isLocalRefusal()) {
                    applied(state.copy(session = session), emptyList())
                } else {
                    reduceFailure(state, session, outcome.reason)
                }
            }

            is ResolverOutcome.Served -> {
                val episode = state.episode.copy(
                    consecutiveUpstreamFailures = 0,
                    lastFailureReason = null,
                    reconnectEpisodeActive = false,
                )
                val successSession = session.copy(upstreamSuccessCount = session.upstreamSuccessCount + 1)
                when {
                    outcome.viaDeviceDnsFallback -> reduceDeviceDnsFallbackSuccess(
                        state = state,
                        session = successSession,
                        primaryAttempted = outcome.primaryAttempted,
                    )

                    outcome.viaEncryptedFallback -> {
                        // Service continued on the user's fallback, but the primary did not serve:
                        // accepted-primary evidence is cleared, health is degraded, not healthy.
                        val next = state.copy(
                            session = successSession,
                            episode = episode.copy(lastAcceptedPrimaryEvidenceAtMillis = null),
                        )
                        applied(next, listOf(ResolverHealthEffect.PublishHealth))
                    }

                    else -> {
                        // The selected primary served. EXIT fallback mode only here (or on a
                        // primary smoke acceptance) — never on a fallback success.
                        val wasFallback = state.episode.deviceDnsFallbackModeActive
                        val next = state.copy(
                            session = successSession,
                            episode = episode.copy(
                                deviceDnsFallbackModeActive = false,
                                deviceDnsFallbackEvidence = 0,
                                consecutiveSmokeFailures = 0,
                                lastAcceptedPrimaryEvidenceAtMillis = sample.occurredAtMillis,
                            ),
                        )
                        if (wasFallback) {
                            applied(
                                next,
                                listOf(ResolverHealthEffect.ExitFallback, ResolverHealthEffect.PublishHealth),
                                verdict = ResolverHealthVerdict.RECOVER,
                            )
                        } else {
                            applied(next, listOf(ResolverHealthEffect.PublishHealth))
                        }
                    }
                }
            }
        }
    }

    private fun observeActive(
        state: ResolverHealthState,
        sample: ResolverHealthSample.Active,
    ): ResolverHealthTransition {
        val next = state.copy(
            episode = state.episode.copy(
                consecutiveUpstreamFailures = 0,
                consecutiveSmokeFailures = 0,
                lastFailureReason = null,
                reconnectEpisodeActive = false,
                lastAcceptedPrimaryEvidenceAtMillis = sample.occurredAtMillis,
            ),
        )
        return applied(next, listOf(ResolverHealthEffect.PublishHealth))
    }

    private fun observeSmokePrimaryAccepted(
        state: ResolverHealthState,
        sample: ResolverHealthSample.SmokePrimaryAccepted,
    ): ResolverHealthTransition {
        val wasFallback = state.episode.deviceDnsFallbackModeActive
        val next = state.copy(
            session = state.session.copy(
                lastResolverIdentifier = sample.resolverIdentifier,
                lastResolverTransport = sample.transport,
            ),
            episode = state.episode.copy(
                consecutiveUpstreamFailures = 0,
                consecutiveSmokeFailures = 0,
                deviceDnsFallbackEvidence = 0,
                deviceDnsFallbackModeActive = false,
                lastAcceptedPrimaryEvidenceAtMillis = sample.occurredAtMillis,
                lastFailureReason = null,
                reconnectEpisodeActive = false,
            ),
            identity = state.identity.copy(rejectedResponseCount = 0, rejectedResponseResolverIdentifier = null),
        )
        return if (wasFallback) {
            applied(
                next,
                listOf(ResolverHealthEffect.ExitFallback, ResolverHealthEffect.PublishHealth),
                verdict = ResolverHealthVerdict.RECOVER,
            )
        } else {
            applied(next, listOf(ResolverHealthEffect.PublishHealth))
        }
    }

    private fun observeSmokeFallbackAccepted(
        state: ResolverHealthState,
        sample: ResolverHealthSample.SmokeFallbackAccepted,
    ): ResolverHealthTransition {
        var episode = state.episode.copy(
            consecutiveUpstreamFailures = 0,
            consecutiveSmokeFailures = 0,
            lastFailureReason = null,
            reconnectEpisodeActive = false,
        )
        val evidence = if (sample.primaryAttempted) {
            min(episode.deviceDnsFallbackEvidence + 1, DEVICE_DNS_FALLBACK_ACTIVATION_THRESHOLD)
        } else {
            episode.deviceDnsFallbackEvidence
        }
        episode = episode.copy(deviceDnsFallbackEvidence = evidence)

        val wasFallback = state.episode.deviceDnsFallbackModeActive
        val activatesNow = !wasFallback && evidence >= DEVICE_DNS_FALLBACK_ACTIVATION_THRESHOLD
        if (wasFallback || activatesNow) {
            // Sticky once active: only a primary recovery or a failed probe exits the mode.
            episode = episode.copy(deviceDnsFallbackModeActive = true)
        }
        val next = state.copy(episode = episode)

        val effects = buildList {
            if (activatesNow) add(ResolverHealthEffect.ActivateFallback)
            add(ResolverHealthEffect.ScheduleRecoveryProbe)
            add(ResolverHealthEffect.PublishHealth)
        }
        return ResolverHealthTransition(
            state = next,
            verdict = verdictOf(next),
            applied = true,
            effects = effects,
        )
    }

    private fun observeSmokeFailed(
        state: ResolverHealthState,
        sample: ResolverHealthSample.SmokeFailed,
    ): ResolverHealthTransition {
        if (sample.failure.isLocalRefusal()) {
            return applied(state, listOf(ResolverHealthEffect.PublishHealth))
        }
        // A failed probe while the fallback was carrying means BOTH routes are down; mode exits
        // and the policy escalates instead of staying on the fallback.
        val wasFallback = state.episode.deviceDnsFallbackModeActive
        var episode = state.episode.copy(
            consecutiveUpstreamFailures = saturate(state.episode.consecutiveUpstreamFailures + 1),
            consecutiveSmokeFailures = saturate(state.episode.consecutiveSmokeFailures + 1),
            lastFailureReason = sample.failure,
            lastAcceptedPrimaryEvidenceAtMillis = null,
            // The reconnect episode escalates only at the same bar organic failures use.
            reconnectEpisodeActive = state.episode.consecutiveUpstreamFailures + 1 >= RECONNECT_FAILURE_THRESHOLD,
        )
        if (wasFallback) {
            episode = episode.copy(deviceDnsFallbackModeActive = false, deviceDnsFallbackEvidence = 0)
        }

        var identity = state.identity
        if (sample.rejectedResponse) {
            identity = if (identity.rejectedResponseResolverIdentifier == sample.resolverIdentifier) {
                identity.copy(rejectedResponseCount = saturate(identity.rejectedResponseCount + 1))
            } else {
                identity.copy(
                    rejectedResponseResolverIdentifier = sample.resolverIdentifier,
                    rejectedResponseCount = 1,
                )
            }
        }

        val next = state.copy(
            session = state.session.copy(
                upstreamFailureCount = saturate(state.session.upstreamFailureCount + 1),
                lastResolverIdentifier = sample.resolverIdentifier,
                lastResolverTransport = sample.transport,
            ),
            episode = episode,
            identity = identity,
        )

        val effects = buildList {
            if (wasFallback) add(ResolverHealthEffect.ExitFallback)
            if (next.episode.consecutiveUpstreamFailures >= RECONNECT_FAILURE_THRESHOLD) {
                if (state.plan.fallbackEnabled) {
                    add(ResolverHealthEffect.ActivateFallback)
                } else {
                    add(ResolverHealthEffect.SwitchResolver)
                }
            }
            add(ResolverHealthEffect.PublishHealth)
            if (wasFallback || next.episode.reconnectEpisodeActive) {
                add(ResolverHealthEffect.ScheduleRecoveryProbe)
            }
        }
        return ResolverHealthTransition(
            state = next,
            verdict = verdictOf(next),
            applied = true,
            effects = effects,
        )
    }

    private fun reduceFailure(
        state: ResolverHealthState,
        session: ResolverSessionEvidence,
        reason: ResolverFailureReason,
    ): ResolverHealthTransition {
        // INV-DNS-4: candidate fallback evidence resets on an upstream failure, but an already
        // latched fallback mode stays until recovery or a real context reset.
        val episode = state.episode.copy(
            consecutiveUpstreamFailures = saturate(state.episode.consecutiveUpstreamFailures + 1),
            deviceDnsFallbackEvidence = 0,
            lastFailureReason = reason,
            lastAcceptedPrimaryEvidenceAtMillis = null,
        )
        val next = state.copy(
            session = session.copy(upstreamFailureCount = saturate(session.upstreamFailureCount + 1)),
            episode = episode,
        )
        val effects = buildList {
            if (episode.consecutiveUpstreamFailures >= RECONNECT_FAILURE_THRESHOLD) {
                if (state.plan.fallbackEnabled) {
                    add(ResolverHealthEffect.ActivateFallback)
                } else {
                    add(ResolverHealthEffect.SwitchResolver)
                }
            }
            add(ResolverHealthEffect.PublishHealth)
        }
        return ResolverHealthTransition(
            state = next,
            verdict = verdictOf(next),
            applied = true,
            effects = effects,
        )
    }

    private fun reduceDeviceDnsFallbackSuccess(
        state: ResolverHealthState,
        session: ResolverSessionEvidence,
        primaryAttempted: Boolean,
    ): ResolverHealthTransition {
        val evidence = if (primaryAttempted) {
            min(state.episode.deviceDnsFallbackEvidence + 1, DEVICE_DNS_FALLBACK_ACTIVATION_THRESHOLD)
        } else {
            state.episode.deviceDnsFallbackEvidence
        }
        val wasFallback = state.episode.deviceDnsFallbackModeActive
        val activatesNow = !wasFallback && evidence >= DEVICE_DNS_FALLBACK_ACTIVATION_THRESHOLD
        val episode = state.episode.copy(
            consecutiveUpstreamFailures = 0,
            consecutiveSmokeFailures = 0,
            deviceDnsFallbackEvidence = evidence,
            deviceDnsFallbackModeActive = wasFallback || activatesNow,
            lastAcceptedPrimaryEvidenceAtMillis = null,
            lastFailureReason = null,
            reconnectEpisodeActive = false,
        )
        val next = state.copy(session = session, episode = episode)
        val effects = buildList {
            if (activatesNow) add(ResolverHealthEffect.ActivateFallback)
            add(ResolverHealthEffect.ScheduleRecoveryProbe)
            add(ResolverHealthEffect.PublishHealth)
        }
        return ResolverHealthTransition(
            state = next,
            verdict = verdictOf(next),
            applied = true,
            effects = effects,
        )
    }

    private fun applied(
        state: ResolverHealthState,
        effects: List<ResolverHealthEffect>,
        verdict: ResolverHealthVerdict = verdictOf(state),
    ): ResolverHealthTransition = ResolverHealthTransition(
        state = state,
        verdict = verdict,
        applied = true,
        effects = effects,
    )

    private fun saturate(value: Int): Int = min(value, MAX_TRACKED_FAILURES)

    /**
     * Whether a failure reason is a local condition that sends nothing on the wire.
     *
     * Mirrors the canonical backoff exclusions (`.backedOff`, `.unsupported`,
     * `.deviceDNSUnavailable`, `.tunnelInterfaceUnavailable`, `.physicalInterfaceUnavailable`)
     * mapped onto the Android failure vocabulary: a cancelled/stale/overloaded attempt and a
     * policy backoff are decisions the device made, not upstream faults.
     */
    fun ResolverFailureReason.isLocalRefusal(): Boolean = when (this) {
        ResolverFailureReason.BACKED_OFF,
        ResolverFailureReason.CANCELLED,
        ResolverFailureReason.STALE_EPOCH,
        ResolverFailureReason.OVERLOADED,
        -> true

        else -> false
    }
}
