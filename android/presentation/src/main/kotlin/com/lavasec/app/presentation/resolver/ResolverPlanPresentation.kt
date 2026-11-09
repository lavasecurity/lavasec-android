package com.lavasec.app.presentation.resolver

import androidx.annotation.StringRes
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverTransportUnavailableReason
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.presentation.R

/**
 * The ONE preset presentation for the milestone resolver plans. Onboarding and
 * Settings both render `MilestoneResolverPlan.entries` and both derive their
 * labels, summaries, and typed selections through this file, so the two
 * surfaces can never drift apart (slice 05 requirement: no second preset list).
 * Provider display names stay product data on the catalog; only the
 * surface copy is mapped to localized resources here.
 */
internal val MilestoneResolverPlan.labelResource: Int
    get() = when (this) {
        MilestoneResolverPlan.DEVICE_DNS_ONLY -> R.string.onboarding_resolver_device
        MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK ->
            R.string.onboarding_resolver_fallback
        MilestoneResolverPlan.MULLVAD_ONLY -> R.string.onboarding_resolver_encrypted
    }

/** The consequence summary shown on the onboarding choice cards. */
internal val MilestoneResolverPlan.summaryResource: Int
    get() = when (this) {
        MilestoneResolverPlan.DEVICE_DNS_ONLY -> R.string.onboarding_resolver_device_summary
        MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK ->
            R.string.onboarding_resolver_fallback_summary
        MilestoneResolverPlan.MULLVAD_ONLY -> R.string.onboarding_resolver_encrypted_summary
    }

/**
 * The Settings root-row summary, composed from the plan's typed selection (not
 * from the plan enum): Device DNS selections reuse their localized labels and
 * an encrypted primary renders as "<Provider> (<Transport>)" from the catalog
 * product data — "Mullvad (DoH)" today.
 */
data class ResolverRowSummary(
    @StringRes val resource: Int,
    val args: List<Any> = emptyList(),
)

internal fun MilestoneResolverPlan.rowSummary(): ResolverRowSummary = toSelection().rowSummary()

internal fun com.lavasec.app.foundation.resolver.ResolverSelection.rowSummary(): ResolverRowSummary {
    val selection = this
    val primary = selection.primary
    return when {
        primary.providerId == ResolverProviderId.DEVICE_DNS && selection.fallbackEnabled &&
            selection.fallback?.transport != CatalogResolverTransport.PLAIN_DNS ->
            ResolverRowSummary(R.string.onboarding_resolver_fallback)

        primary.providerId == ResolverProviderId.DEVICE_DNS ->
            ResolverRowSummary(R.string.onboarding_resolver_device)

        else -> ResolverRowSummary(
            R.string.settings_resolver_summary_provider_transport,
            listOf(activeTiers.first().displayName, primary.transport.token),
        )
    }
}

/** Typed unavailability reason -> localized surface copy (never product data). */
internal val ResolverTransportUnavailableReason.messageResource: Int
    get() = when (this) {
        ResolverTransportUnavailableReason.RUNTIME_SERVES_ONLY_MULLVAD_DOH ->
            R.string.settings_resolver_unavailable_reason_mullvad_doh_only
        ResolverTransportUnavailableReason.PLAIN_PROVIDER_ENDPOINTS_UNSERVED ->
            R.string.settings_resolver_unavailable_reason_plain_endpoints
        ResolverTransportUnavailableReason.DOT_RUNTIME_ABSENT ->
            R.string.settings_resolver_unavailable_reason_dot
        ResolverTransportUnavailableReason.DOQ_RUNTIME_ABSENT ->
            R.string.settings_resolver_unavailable_reason_doq
        ResolverTransportUnavailableReason.CUSTOM_RESOLVER_RUNTIME_ABSENT ->
            R.string.settings_resolver_unavailable_reason_custom
    }

/**
 * The first served transport in canonical menu order. A custom row requires its
 * committed endpoint before it can supply a transport capability.
 */
internal fun servedTransportOrNull(provider: com.lavasec.app.foundation.resolver.ResolverProvider) =
    ResolverCatalog.transportRows(provider)
        .firstOrNull { (_, capability) -> capability.available }
        ?.first
