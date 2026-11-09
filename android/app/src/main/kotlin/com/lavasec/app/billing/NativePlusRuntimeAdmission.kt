package com.lavasec.app.billing

import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.VerifiedFilterRuntimeLease
import com.lavasec.app.storage.CredentialFilterConfigurationRuntimeRead

/** The natural serve boundary: a later lapse does not invalidate a lease already handed off. */
internal suspend fun openPlanCheckedFilterLease(
    plan: NativePlusEntitlementOwner?,
    expectedIdentity: ConfigurationIdentity,
    isDetached: () -> Boolean,
    read: suspend () -> CredentialFilterConfigurationRuntimeRead,
): VerifiedFilterRuntimeLease {
    val open: suspend (FeatureLimits) -> VerifiedFilterRuntimeLease = { limits ->
        check(!isDetached()) { "credential VPN attachment is detached" }
        val available = read() as? CredentialFilterConfigurationRuntimeRead.Available
            ?: error("authoritative filter runtime is unavailable")
        try {
            check(available.state.configurationIdentity == expectedIdentity) {
                "authoritative filter runtime changed after attachment"
            }
            check(available.state.verifiedArtifact.artifactIdentity.ruleCounts.total <= limits.maxFilterRules) {
                "saved filter exceeds the current plan rule budget"
            }
            available.takeLease().also { lease ->
                if (isDetached()) {
                    lease.close()
                    error("credential VPN attachment was detached during filter open")
                }
            }
        } finally { available.close() }
    }
    return if (plan == null) open(FeatureLimits.FREE)
    else checkNotNull(plan.withAvailableLimits(open)) { "device plan is unavailable" }
}
