package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedReadiness
import com.lavasec.app.foundation.chained.ChainedReadinessRefusal

/**
 * What the VPN runtime should run for the current credential generation.
 *
 * The decision is expressed as a closed choice rather than a boolean so the two paths cannot be
 * accidentally conflated: [DnsOnly] is the pre-chaining tunnel that every non-chaining user
 * gets, and [Chained] carries the exact committed configuration the runtime must install.
 */
sealed interface ChainedActivation {

    /** Run the unchanged DNS-only tunnel. */
    data object DnsOnly : ChainedActivation

    /** Run the chained data path for [configuration], whose committed identity is [identity]. */
    data class Chained(
        val configuration: com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration,
        val identity: ChainedUpstreamIdentity,
    ) : ChainedActivation
}

/**
 * The pure composition decision: given the durable facts, may the chained data path run?
 *
 * This is the single seam the VPN runtime and the React Native surface share, so neither
 * hand-rolls "is chaining on". It deliberately does NOT read secrets — the caller supplies only
 * [secretsAvailable] — because deciding must be cheap and must never decrypt key material on a
 * status query; the runtime reads the material separately once the gate clears.
 *
 * The gate is the reviewed [ChainedReadiness] evaluation (entitlement → device → preference →
 * configuration → T0 DNS → routes), with the credential-store term folded in:
 * [secretsAvailable] is required as well, because a configuration whose key half is gone is not
 * runnable even though every other gate passes. The plan's no-instantiation guarantee is
 * structural elsewhere ([ChainedEnginePermit]); this function is the policy that keeps the
 * runtime from even asking for the engine in the DNS-only case.
 */
object ChainedActivationPolicy {

    /**
     * The activation for the durable facts.
     *
     * @param stored the committed non-secret configuration, or `null` when none is configured.
     * @param hasEntitlement the authoritative Plus verdict.
     * @param availability the current device/account verdict from [ChainedAvailabilityAuthority].
     * @param userEnabled the stored preference.
     * @param secretsAvailable whether the committed configuration's key half is readable.
     * @param clientDnsOverTunnelAvailable whether the platform can serve intercepted client DNS
     *   through the configured T0 resolver. Defaults to `false` (fail closed); production wires
     *   `ChainedT0ResolverEgress` and passes `true`. See [chainedReadinessFrom].
     */
    fun decide(
        stored: StoredChainedUpstreamConfiguration?,
        hasEntitlement: Boolean,
        availability: ChainedAvailability,
        userEnabled: Boolean,
        secretsAvailable: Boolean,
        clientDnsOverTunnelAvailable: Boolean = false,
    ): ChainedActivation {
        val readiness = chainedReadinessFrom(
            configuration = stored?.configuration,
            hasEntitlement = hasEntitlement,
            availability = availability,
            userEnabled = userEnabled,
            clientDnsOverTunnelAvailable = clientDnsOverTunnelAvailable,
        )
        if (stored == null || readiness !is ChainedReadiness.Ready || !secretsAvailable) {
            return ChainedActivation.DnsOnly
        }
        return ChainedActivation.Chained(checkNotNull(stored.configuration.activeConfiguration), stored.identity)
    }

    /**
     * Whether the user may turn chaining ON, independent of the current preference.
     *
     * [decide] answers "run it now", which folds in the stored preference: an entitled, eligible,
     * configured user who has never enabled chaining gets [ChainedActivation.DnsOnly] and a
     * `ready = false` status, so a surface that gates its control on `ready` can never offer the
     * enable. This predicate drops ONLY the preference term — it re-runs the full readiness gate
     * with `userEnabled = true` and still requires the configuration and its key half — so a
     * user with an unroutable T0 or a missing key cannot latch a blackhole. The control should be
     * offered when this is `true`, and the toggle state itself comes from [ChainedStatus.enabled].
     */
    fun canEnable(
        stored: StoredChainedUpstreamConfiguration?,
        hasEntitlement: Boolean,
        availability: ChainedAvailability,
        secretsAvailable: Boolean,
        clientDnsOverTunnelAvailable: Boolean = false,
    ): Boolean {
        if (stored == null || !secretsAvailable) return false
        // Re-run the gate with the preference forced on: Ready here means every OTHER term
        // (entitlement, device, configuration, T0 DNS, routes) would clear if the user enabled.
        val readiness = chainedReadinessFrom(
            configuration = stored.configuration,
            hasEntitlement = hasEntitlement,
            availability = availability,
            userEnabled = true,
            clientDnsOverTunnelAvailable = clientDnsOverTunnelAvailable,
        )
        return readiness is ChainedReadiness.Ready
    }

    /**
     * Whether a live runtime must be rebuilt to match a freshly evaluated activation.
     *
     * The pause→filtering transition re-evaluates activation because chained mode may have been
     * enabled (or the gate regained) while the session was paused, in which case the runtime that
     * was deliberately built DNS-only must switch. Rebuilding is a mode change, not a retry: it
     * is only required when the desired mode differs from the live one.
     */
    fun requiresRuntimeRebuild(currentChained: Boolean, desiredChained: Boolean): Boolean =
        currentChained != desiredChained
}

/**
 * The honest status the React Native surface renders.
 *
 * [ready] is the only field that promises the runtime can honor a toggle: it requires the whole
 * [readiness] gate AND the credential store, matching what the runtime itself will decide. But
 * `ready` is false for a legitimately-off upstream (the stored preference is one of the readiness
 * gates), so it must NOT gate the control: [canEnable] is the preference-independent answer to
 * "may this user turn chaining on", and the surface enables its toggle when `canEnable` is true
 * even while a disabled-by-user `ready` is false.
 *
 * [readiness] folds in the stored preference, so when chaining is off it stops at
 * [com.lavasec.app.foundation.chained.ChainedReadinessRefusal.DISABLED_BY_USER] before any
 * downstream gate. [enableReadiness] is the same gate with the preference forced on, so the caller
 * can report the genuine blocker when enabling is impossible (see [blockingReadinessRefusal]).
 * [configured] and [eligible] stay separate so "no upstream" is not rendered as "your device is
 * unsupported" (the disabled-control-with-no-remedy defect the readiness ordering exists to
 * prevent).
 */
data class ChainedStatus(
    val configured: Boolean,
    val eligible: Boolean,
    val enabled: Boolean,
    val canEnable: Boolean,
    val ready: Boolean,
    val readiness: ChainedReadiness,
    val enableReadiness: ChainedReadiness,
    val secretsAvailable: Boolean,
) {
    companion object {
        fun evaluate(
            stored: StoredChainedUpstreamConfiguration?,
            hasEntitlement: Boolean,
            availability: ChainedAvailability,
            userEnabled: Boolean,
            secretsAvailable: Boolean,
            clientDnsOverTunnelAvailable: Boolean = false,
        ): ChainedStatus {
            val readiness = chainedReadinessFrom(
                configuration = stored?.configuration,
                hasEntitlement = hasEntitlement,
                availability = availability,
                userEnabled = userEnabled,
                clientDnsOverTunnelAvailable = clientDnsOverTunnelAvailable,
            )
            // The preference-independent gate: identical evaluation with the switch forced on.
            val enableReadiness = chainedReadinessFrom(
                configuration = stored?.configuration,
                hasEntitlement = hasEntitlement,
                availability = availability,
                userEnabled = true,
                clientDnsOverTunnelAvailable = clientDnsOverTunnelAvailable,
            )
            return ChainedStatus(
                configured = stored != null,
                eligible = availability.isEligible,
                enabled = userEnabled,
                // The reviewed policy is the source of truth; `enableReadiness` mirrors its gate
                // so the reported refusal and the enable verdict cannot disagree.
                canEnable = ChainedActivationPolicy.canEnable(
                    stored = stored,
                    hasEntitlement = hasEntitlement,
                    availability = availability,
                    secretsAvailable = secretsAvailable,
                    clientDnsOverTunnelAvailable = clientDnsOverTunnelAvailable,
                ),
                ready = readiness is ChainedReadiness.Ready && secretsAvailable,
                readiness = readiness,
                enableReadiness = enableReadiness,
                secretsAvailable = secretsAvailable,
            )
        }
    }
}

/**
 * The readiness refusal that actually blocks the feature, or `null` when the gate is ready.
 *
 * The preference-bearing [ChainedStatus.readiness] stops at
 * [com.lavasec.app.foundation.chained.ChainedReadinessRefusal.DISABLED_BY_USER] before the
 * downstream configuration/platform gates, so when enabling is impossible
 * ([ChainedStatus.canEnable] false) the user would otherwise be told only that chaining is off,
 * hiding the real blocker and its remedy. This returns the preference-independent refusal for that
 * case, while a genuinely-off-and-otherwise-fine upstream still reports `DISABLED_BY_USER`.
 *
 * A missing credential is not a readiness refusal; the caller checks
 * [ChainedStatus.secretsAvailable].
 */
fun ChainedStatus.blockingReadinessRefusal(): ChainedReadinessRefusal? {
    if (readiness is ChainedReadiness.Ready) return null
    if (!canEnable) {
        // Enabling is blocked: the preference-independent gate names the real blocker. When it is
        // Ready the only remaining blocker is the credential store, which is not a readiness
        // refusal, so return null and let the caller report the key condition — never fall back to
        // the preference-bearing DISABLED_BY_USER, which would hide an unreadable key.
        return (enableReadiness as? ChainedReadiness.NotReady)?.reason
    }
    return (readiness as? ChainedReadiness.NotReady)?.reason
}
