package com.lavasec.app.vpn

import com.lavasec.app.platform.VerifiedFilterRuntimeLease

/**
 * Dormant composition boundary for one exact authoritative mapped-filter engine.
 *
 * [create] transfers [lease] after its exclusive claim succeeds and then consumes it on every
 * later outcome. A rejected duplicate alias acquires no ownership and cannot close or retire the
 * winner. Policy identity fields are derived exclusively from the claimed lease; callers provide
 * only runtime, network, resolver, and generation inputs.
 */
internal class ArtifactBackedReadyDnsEngineFactory(
    private val controls: ProxyPolicyControlSource,
    private val resolverFactory: ReadyDnsResolverFactory,
    private val eventIds: DecisionEventIdSource,
    private val wallClock: EpochMillisClock,
    private val monotonicClock: MonotonicClock,
    private val publicationSink: ProxyPublicationSink,
    private val unavailablePublicationFence: ProxyPublicationFence,
) {
    fun create(
        lease: VerifiedFilterRuntimeLease,
        sessionGeneration: Long,
        engineGeneration: Long,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
    ): ReadyDnsEngineOwner {
        val policy = ArtifactBackedDnsPolicyRuntime.take(
            lease = lease,
            controls = controls,
            eventIds = eventIds,
            wallClock = wallClock,
            monotonicClock = monotonicClock,
            publicationSink = publicationSink,
            unavailablePublicationFence = unavailablePublicationFence,
        )
        val candidate = try {
            val identity = policy.identity
            val epoch = EngineEpoch(
                sessionGeneration = sessionGeneration,
                configurationIdentity = identity.configurationIdentity,
                artifactDigest = identity.artifactDigest,
                policyRevision = identity.revision,
                networkGeneration = networkContext.networkGeneration,
                engineGeneration = engineGeneration,
                primaryResolverIdentity = resolverPlan.primaryResolverIdentity,
                fallbackCoverage = resolverPlan.fallbackCoverage,
            )
            ReadyDnsEngineCandidate(
                epoch = epoch,
                networkContext = networkContext,
                resolverPlan = resolverPlan,
                policy = policy,
            )
        } catch (failure: Throwable) {
            closeSuppressingFactoryPolicy(policy, failure)
            throw failure
        }
        return ReadyDnsEngineOwner.create(candidate, resolverFactory)
    }
}

private fun closeSuppressingFactoryPolicy(
    policy: OwnedDnsPolicyRuntime,
    primary: Throwable,
) {
    try {
        policy.close()
    } catch (closeFailure: Throwable) {
        if (primary !== closeFailure) primary.addSuppressed(closeFailure)
    }
}
