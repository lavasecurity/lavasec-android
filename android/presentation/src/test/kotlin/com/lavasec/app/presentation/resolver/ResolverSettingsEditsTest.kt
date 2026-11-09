package com.lavasec.app.presentation.resolver

import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toSelection
import org.junit.Assert.*
import org.junit.Test

class ResolverSettingsEditsTest {
    @Test
    fun `provider replacement changes the addressed row and preserves Device plus exact transport`() {
        val initial = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK.toSelection()
        val cloudflare = checkNotNull(initial.withSettingsProvider(ResolverCatalog.cloudflare))
        assertEquals(initial.tiers.first(), cloudflare.tiers.first())
        assertEquals(ResolverSelectionEntry(ResolverProviderId.CLOUDFLARE, CatalogResolverTransport.DOH), cloudflare.fallback)
        val dot = checkNotNull(cloudflare.withSettingsProvider(ResolverCatalog.cloudflare, CatalogResolverTransport.DOT))
        assertEquals("tls://one.one.one.one:853", ResolverCatalog.dotEndpoint(dot.fallback!!.provider))
        assertEquals(initial.tiers.first(), dot.tiers.first())
    }

    @Test
    fun `provider edits do not lose a disabled custom endpoint label or secondary`() {
        val retained = ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOQ,
            customDoqEndpoint = "doq://resolver.example:853", customSecondaryEndpoint = "doq://secondary.example:853",
            customName = "Private"), false, "Retained")
        val initial = ResolverSelection(listOf(ResolverTier(ResolverSelectionEntry(ResolverProviderId.QUAD9_UNFILTERED,
            CatalogResolverTransport.DOH)), retained))
        assertEquals(retained, initial.withSettingsProvider(ResolverCatalog.googlePublicDns)!!.tiers.last())
        assertEquals(initial, initial.withSettingsFallback(true)!!.withSettingsFallback(false))
    }

    @Test
    fun `Device primary toggle never discards either of two retained non-device rows`() {
        val initial = ResolverSelection(listOf(ResolverTier(ResolverSelectionEntry(ResolverProviderId.CLOUDFLARE,
            CatalogResolverTransport.DOH)), ResolverTier(ResolverSelectionEntry(ResolverProviderId.QUAD9_UNFILTERED,
            CatalogResolverTransport.DOT), false, "Retained")))
        assertNull(initial.withSettingsDevicePrimary(true))
        val device = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK.toSelection()
        val off = device.withSettingsDevicePrimary(false)!!
        assertFalse(off.tiers.first().isEnabled)
        assertEquals(device.tiers.first().entry, off.tiers.first().entry)
        assertEquals(device.tiers.last(), off.tiers.last())
        assertEquals(device, off.withSettingsDevicePrimary(true))
    }

    @Test
    fun `an exact duplicate or last active row removal is refused before callbacks`() {
        val cloudflare = ResolverSelectionEntry(ResolverProviderId.CLOUDFLARE, CatalogResolverTransport.DOH)
        val initial = ResolverSelection(listOf(ResolverTier(cloudflare), ResolverTier(ResolverSelectionEntry(
            ResolverProviderId.QUAD9_UNFILTERED, CatalogResolverTransport.DOH))))
        assertNull(initial.withSettingsProvider(ResolverCatalog.quad9Unfiltered))
        val soleSecond = initial.copy(tiers = listOf(initial.tiers.first().copy(isEnabled = false), initial.tiers.last()))
        assertNull(soleSecond.withSettingsFallback(false))
    }

    @Test
    fun `disabled leading row remains addressed when Device is the only active trailing row`() {
        val retained = ResolverTier(ResolverSelectionEntry(ResolverProviderId.CLOUDFLARE,
            CatalogResolverTransport.DOT), false, "Retained")
        val device = MilestoneResolverPlan.DEVICE_DNS_ONLY.toSelection().tiers.single()
        val initial = ResolverSelection(listOf(retained, device))
        assertEquals(0, initial.settingsProviderIndex())
        val replacement = initial.withSettingsProvider(ResolverCatalog.googlePublicDns)!!
        assertEquals(device, replacement.tiers.last())
        assertFalse(replacement.tiers.first().isEnabled)
        assertEquals(CatalogResolverTransport.DOT, replacement.tiers.first().entry.transport)
        assertEquals(retained.copy(isEnabled = true), initial.withSettingsFallback(true)!!.tiers.first())
        val off = initial.withSettingsDevicePrimary(false)!!
        assertEquals(retained.copy(isEnabled = true), off.tiers.first())
        assertEquals(device.copy(isEnabled = false), off.tiers.last())
    }
}
