package com.lavasec.app.product.activity

import com.lavasec.app.foundation.activity.NetworkActivityUserAction
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.resolver.FilterResolverPlan
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.product.FilterProfileSwitchPort
import com.lavasec.app.product.FilterProfileSwitchResult
import com.lavasec.app.product.ResolverPlanSwitchPort
import com.lavasec.app.product.ResolverPlanSwitchResult

/**
 * Wraps the exact resolver-switch port so one [ResolverPlanSwitchResult.Applied] plan
 * change becomes the user-action event the iOS surface records at
 * `setFallbackResolver` / `setFallbackToDeviceDNS` (AppViewModel.swift). The category
 * is derived from the before/after plan comparison at the Applied result — recording
 * before the commit would log a change that never landed, and a NO_CHANGE switch is
 * rejected upstream, so an equal before/after means "nothing honest to record".
 */
internal class CredentialNetworkActivityRecordingResolverSwitchPort(
    private val delegate: ResolverPlanSwitchPort,
    private val currentPlan: () -> MilestoneResolverPlan?,
    private val onRecorded: (NetworkActivityUserAction) -> Unit,
) : ResolverPlanSwitchPort {
    override suspend fun apply(plan: FilterResolverPlan): ResolverPlanSwitchResult {
        val previous = currentPlan()
        val result = delegate.apply(plan)
        if (result is ResolverPlanSwitchResult.Applied) {
            resolverSwitchUserAction(previous, plan.plan)?.let(onRecorded)
        }
        return result
    }
}

/**
 * The exact user-action category one Applied plan switch represents, derived only from
 * facts both plans carry. A missing previous plan (the plan flow has not emitted yet)
 * records nothing rather than guessing; a change to the primary route or the encrypted
 * fallback preset is a resolver change, while a fallback-only flag flip is the
 * Device-DNS-fallback toggle — mirroring the two iOS recordings.
 */
internal fun resolverSwitchUserAction(
    previous: MilestoneResolverPlan?,
    next: MilestoneResolverPlan,
): NetworkActivityUserAction? {
    if (previous == null) return null
    val before = previous.configuration
    val after = next.configuration
    return when {
        before.useDeviceDns != after.useDeviceDns -> NetworkActivityUserAction.CHANGE_RESOLVER
        before.encryptedFallbackId != after.encryptedFallbackId ->
            NetworkActivityUserAction.CHANGE_RESOLVER
        before.useEncryptedFallback != after.useEncryptedFallback ->
            NetworkActivityUserAction.TOGGLE_DEVICE_DNS_FALLBACK
        else -> null
    }
}

/**
 * Wraps the exact whole-profile switch port so one Applied switch becomes the
 * CHANGE_FILTERS user-action event. A switch always changes the filter in effect
 * (NO_CHANGE is rejected upstream), so no before/after comparison is needed.
 */
internal class CredentialNetworkActivityRecordingProfileSwitchPort(
    private val delegate: FilterProfileSwitchPort,
    private val onRecorded: () -> Unit,
) : FilterProfileSwitchPort {
    override suspend fun apply(targetProfileId: String): FilterProfileSwitchResult {
        val result = delegate.apply(targetProfileId)
        if (result is FilterProfileSwitchResult.Applied) {
            onRecorded()
        }
        return result
    }
}

/**
 * Whether an Applied library mutation changed what is actually filtering: the active
 * profile switched, or the active profile's rule content (enabled sources or manual
 * domain rules) changed. Renaming a non-active filter or editing a non-active draft
 * commits through the same coordinator but does not change the filter in effect, so it
 * must not produce a "Filters changed" row.
 */
internal fun libraryActiveFilteringChanged(
    before: FilterLibraryDraft,
    after: FilterLibraryDraft,
): Boolean {
    if (before.activeProfileId != after.activeProfileId) return true
    val previous = before.profile(before.activeProfileId) ?: return true
    val current = after.profile(after.activeProfileId) ?: return true
    return previous.enabledSourceIds != current.enabledSourceIds ||
        previous.manualRules != current.manualRules
}
