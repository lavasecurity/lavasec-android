package com.lavasec.app.presentation.resolver

import androidx.annotation.StringRes
import com.lavasec.app.foundation.health.ProtectionHealthAssessment
import com.lavasec.app.foundation.health.ProtectionHealthSeverity
import com.lavasec.app.presentation.R

/**
 * Typed resolver-health vocabulary for the DNS Resolver surface. It separates
 * the live resolver health from the provider selection (slice 05 contract):
 * the selection stays authoritative on [com.lavasec.app.foundation.resolver.MilestoneResolverPlan]
 * while this value is derived exclusively from the runtime health assessment,
 * so a slow or wedged resolver can never be blamed on the provider catalog and
 * vice versa.
 *
 * The seven states map one-to-one onto [ProtectionHealthSeverity]; the runtime
 * policy decides the severities, this type only makes them presentable. In
 * particular SLOW carries its own copy: the runtime wedge-recovery policy is
 * the only authority that may pair a reconnect remedy with slowness (it does —
 * `ProtectionHealthPolicy` escalates DNS_SLOW with a RECONNECT action), and the
 * copy states the two remedies without claiming either one repairs the resolver.
 */
enum class ResolverHealthUi {
    HEALTHY,
    RECOVERING,
    DEVICE_FALLBACK,
    PLAIN_FALLBACK,
    ENCRYPTED_FALLBACK,
    SLOW,
    UNREACHABLE,
    NEEDS_RECONNECT,
    ;

    companion object {
        fun from(assessment: ProtectionHealthAssessment): ResolverHealthUi =
            when (assessment.severity) {
                ProtectionHealthSeverity.HEALTHY -> HEALTHY
                ProtectionHealthSeverity.RECOVERING -> RECOVERING
                ProtectionHealthSeverity.USING_PLAIN_DNS_FALLBACK -> PLAIN_FALLBACK
                ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK -> DEVICE_FALLBACK
                ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK -> ENCRYPTED_FALLBACK
                ProtectionHealthSeverity.DNS_SLOW -> SLOW
                ProtectionHealthSeverity.NETWORK_UNAVAILABLE -> UNREACHABLE
                ProtectionHealthSeverity.NEEDS_RECONNECT -> NEEDS_RECONNECT
            }
    }
}

/** Status title for each health state; these re-use the settled Network Activity connectivity labels. */
internal val ResolverHealthUi.titleResource: Int
    @StringRes get() = when (this) {
        ResolverHealthUi.HEALTHY -> R.string.network_activity_connectivity_healthy
        ResolverHealthUi.RECOVERING -> R.string.network_activity_connectivity_recovering
        ResolverHealthUi.PLAIN_FALLBACK -> R.string.network_activity_connectivity_plain_fallback
        ResolverHealthUi.DEVICE_FALLBACK -> R.string.network_activity_connectivity_device_fallback
        ResolverHealthUi.ENCRYPTED_FALLBACK -> R.string.network_activity_connectivity_encrypted_fallback
        ResolverHealthUi.SLOW -> R.string.network_activity_connectivity_dns_slow
        ResolverHealthUi.UNREACHABLE -> R.string.network_activity_connectivity_network_lost
        ResolverHealthUi.NEEDS_RECONNECT -> R.string.nerd_stats_reconnect_needed
    }

/**
 * Honest per-state messaging (pinned iOS copy where it exists). The four
 * problem states stay distinct: recovery retries on its own, fallback states
 * name the fallback that is carrying traffic, unreachable names the network
 * condition, and needs-reconnect names the one action the runtime requires.
 * SLOW deliberately does not claim a reconnect fixes anything — see
 * [ResolverHealthUi].
 */
internal val ResolverHealthUi.detailResource: Int
    @StringRes get() = when (this) {
        ResolverHealthUi.HEALTHY -> R.string.guard_status_healthy_subtitle
        ResolverHealthUi.RECOVERING -> R.string.settings_resolver_health_recovering_detail
        ResolverHealthUi.PLAIN_FALLBACK -> R.string.settings_resolver_health_plain_fallback_detail
        ResolverHealthUi.DEVICE_FALLBACK -> R.string.settings_resolver_health_device_fallback_detail
        ResolverHealthUi.ENCRYPTED_FALLBACK -> R.string.settings_resolver_health_encrypted_fallback_detail
        ResolverHealthUi.SLOW -> R.string.settings_resolver_health_slow_detail
        ResolverHealthUi.UNREACHABLE -> R.string.settings_resolver_health_unreachable_detail
        ResolverHealthUi.NEEDS_RECONNECT -> R.string.guard_status_reconnect_needed_subtitle
    }
