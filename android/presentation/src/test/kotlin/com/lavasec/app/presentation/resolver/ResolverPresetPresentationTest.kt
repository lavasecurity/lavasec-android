package com.lavasec.app.presentation.resolver

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverTransportUnavailableReason
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.presentation.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverPresetPresentationTest {
    @Test
    fun `the shared preset mapping covers every milestone plan with distinct labels`() {
        val labels = MilestoneResolverPlan.entries.map { it to it.labelResource }
        assertEquals(MilestoneResolverPlan.entries.size, labels.map { it.second }.distinct().size)
        assertEquals(
            R.string.onboarding_resolver_device,
            MilestoneResolverPlan.DEVICE_DNS_ONLY.labelResource,
        )
        assertEquals(
            R.string.onboarding_resolver_fallback,
            MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK.labelResource,
        )
        assertEquals(
            R.string.onboarding_resolver_encrypted,
            MilestoneResolverPlan.MULLVAD_ONLY.labelResource,
        )
    }

    @Test
    fun `every milestone plan summary is composed from its typed selection`() {
        assertEquals(
            ResolverRowSummary(R.string.onboarding_resolver_device),
            MilestoneResolverPlan.DEVICE_DNS_ONLY.rowSummary(),
        )
        assertEquals(
            ResolverRowSummary(R.string.onboarding_resolver_fallback),
            MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK.rowSummary(),
        )
        // The encrypted primary summary renders "<Provider> (<Transport>)"
        // from the catalog product data, not from the plan enum.
        assertEquals(
            ResolverRowSummary(
                R.string.settings_resolver_summary_provider_transport,
                listOf("Mullvad", "DoH"),
            ),
            MilestoneResolverPlan.MULLVAD_ONLY.rowSummary(),
        )
    }

    @Test
    fun `a Device primary with plain fallback never claims encrypted fallback in its summary`() {
        val selection = com.lavasec.app.foundation.resolver.ResolverSelection(listOf(
            MilestoneResolverPlan.DEVICE_DNS_ONLY.toSelection().tiers.single(),
            com.lavasec.app.foundation.resolver.ResolverTier(
                com.lavasec.app.foundation.resolver.ResolverSelectionEntry(ResolverProviderId.CLOUDFLARE,
                    com.lavasec.app.foundation.resolver.CatalogResolverTransport.PLAIN_DNS)),
        ))
        assertEquals(ResolverRowSummary(R.string.onboarding_resolver_device), selection.rowSummary())
    }

    @Test
    fun `onboarding and settings share one preset list and one default selection`() {
        // Both surfaces render MilestoneResolverPlan.entries through the shared
        // label mapping (slice 05: no second list), and the onboarding default
        // configuration is the exact plan Settings shows as the recommended
        // Device-DNS-with-encrypted-fallback selection.
        val defaultPlan = MilestoneResolverPlan.from(AppConfiguration.default().resolver)
        assertEquals(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK, defaultPlan)
        val defaultSelection = checkNotNull(defaultPlan).toSelection()
        assertEquals(ResolverProviderId.DEVICE_DNS, defaultSelection.primary.providerId)
        assertEquals(
            ResolverCatalog.recommendedProvider.id,
            defaultSelection.primary.providerId,
        )
        assertEquals(ResolverProviderId.MULLVAD, defaultSelection.fallback?.providerId)
    }

    @Test
    fun `every plan selection names a runtime served provider transport pair`() {
        for (plan in MilestoneResolverPlan.entries) {
            val selection = plan.toSelection()
            assertTrue(
                ResolverCatalog.capability(selection.primary.provider, selection.primary.transport).available,
            )
        }
    }

    @Test
    fun `every unavailability reason maps to its own localized message`() {
        val resources = ResolverTransportUnavailableReason.entries.map { it.messageResource }
        assertEquals(
            ResolverTransportUnavailableReason.entries.size,
            resources.distinct().size,
        )
        assertEquals(
            R.string.settings_resolver_unavailable_reason_mullvad_doh_only,
            ResolverTransportUnavailableReason.RUNTIME_SERVES_ONLY_MULLVAD_DOH.messageResource,
        )
        assertEquals(
            R.string.settings_resolver_unavailable_reason_plain_endpoints,
            ResolverTransportUnavailableReason.PLAIN_PROVIDER_ENDPOINTS_UNSERVED.messageResource,
        )
        assertEquals(
            R.string.settings_resolver_unavailable_reason_dot,
            ResolverTransportUnavailableReason.DOT_RUNTIME_ABSENT.messageResource,
        )
        assertEquals(
            R.string.settings_resolver_unavailable_reason_doq,
            ResolverTransportUnavailableReason.DOQ_RUNTIME_ABSENT.messageResource,
        )
        assertEquals(
            R.string.settings_resolver_unavailable_reason_custom,
            ResolverTransportUnavailableReason.CUSTOM_RESOLVER_RUNTIME_ABSENT.messageResource,
        )
    }

    @Test
    fun `canonical provider rows expose their actually served transports and custom requires its endpoint`() {
        for (provider in ResolverCatalog.providers.filter { it.id != ResolverProviderId.CUSTOM }) {
            assertEquals(com.lavasec.app.foundation.resolver.CatalogResolverTransport.PLAIN_DNS,
                servedTransportOrNull(provider))
        }
        assertEquals(null, servedTransportOrNull(ResolverCatalog.custom))
        for (provider in listOf(ResolverCatalog.mullvad, ResolverCatalog.quad9Secure)) {
            assertEquals(com.lavasec.app.foundation.resolver.CatalogResolverTransport.PLAIN_DNS,
                servedTransportOrNull(provider))
        }
    }
}
