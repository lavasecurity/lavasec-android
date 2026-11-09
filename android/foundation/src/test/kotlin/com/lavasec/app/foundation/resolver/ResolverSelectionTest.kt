package com.lavasec.app.foundation.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverSelectionTest {
    @Test
    fun `every milestone plan maps to its exact typed selection`() {
        assertEquals(
            ResolverSelectionPresets.DEVICE_DNS_ONLY,
            MilestoneResolverPlan.DEVICE_DNS_ONLY.toSelection(),
        )
        assertEquals(
            ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
            MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK.toSelection(),
        )
        assertEquals(
            ResolverSelectionPresets.MULLVAD_ONLY,
            MilestoneResolverPlan.MULLVAD_ONLY.toSelection(),
        )
    }

    @Test
    fun `every milestone selection roundtrips to its plan`() {
        for (plan in MilestoneResolverPlan.entries) {
            assertEquals(plan, plan.toSelection().toMilestonePlan())
        }
        assertThrowsIllegalArgument {
            ResolverSelection(listOf(ResolverTier(ResolverSelectionPresets.MULLVAD_DOH), ResolverTier(ResolverSelectionPresets.MULLVAD_DOH)))
        }
    }

    @Test
    fun `a disabled row is retained in position and excluded from the active projection`() {
        val selection = ResolverSelection(
            listOf(
                ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
                ResolverTier(ResolverSelectionPresets.MULLVAD_DOH, isEnabled = false),
            ),
        )
        assertEquals(2, selection.tiers.size)
        assertEquals(listOf(true, false), selection.tiers.map { it.isEnabled })
        assertEquals(listOf(ResolverSelectionPresets.DEVICE_PLAIN), selection.activeTiers.map { it.entry })
        assertEquals(ResolverSelectionPresets.DEVICE_PLAIN, selection.primary)
        assertNull(selection.fallback)
        assertFalse(selection.fallbackEnabled)
        // The disabled row still names its exact resolver, so a re-enable restores it.
        assertEquals(ResolverSelectionPresets.MULLVAD_DOH, selection.tiers[1].entry)
    }

    @Test
    fun `a disabled first row leaves the enabled second row as the primary`() {
        val selection = ResolverSelection(
            listOf(
                ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, isEnabled = false),
                ResolverTier(ResolverSelectionPresets.MULLVAD_DOH),
            ),
        )
        assertEquals(ResolverSelectionPresets.MULLVAD_DOH, selection.primary)
        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, selection.toMilestonePlan())
    }

    @Test
    fun `the ladder refuses to disable the last active row`() {
        assertThrowsIllegalArgument { ResolverSelection(emptyList()) }
        assertThrowsIllegalArgument { ResolverSelection(listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, isEnabled = false))) }
    }

    @Test
    fun `selection entries reject transports the provider does not offer`() {
        assertThrowsIllegalArgument {
            ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.DOH)
        }
    }

    @Test
    fun `selection entries reject provider transport pairs the runtime cannot serve`() {
        assertThrowsIllegalArgument {
            ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.PLAIN_DNS)
        }
        assertThrowsIllegalArgument {
            ResolverSelectionEntry(ResolverProviderId.MULLVAD, CatalogResolverTransport.DOQ)
        }
        assertThrowsIllegalArgument {
            ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH)
        }
    }

    @Test
    fun `selection identity rejects different labels for the same endpoint including disabled rows`() {
        val first = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH,
            customDohUrl = "https://dns.google/dns-query", customName = "First")
        val second = first.copy(customName = "Second")
        assertThrowsIllegalArgument { ResolverSelection(listOf(ResolverTier(first), ResolverTier(second, false))) }
        val builtin = ResolverSelectionEntry(ResolverProviderId.GOOGLE_PUBLIC_DNS, CatalogResolverTransport.DOH)
        // The builtin uses literal bootstrap; the custom URL uses network DNS. Frozen source
        // treats them as different route identities even though their HTTPS URL is the same.
        assertEquals(2, ResolverSelection(listOf(ResolverTier(first), ResolverTier(builtin))).tiers.size)
        val withSecondary = second.copy(customSecondaryEndpoint = "https://second.example/dns-query")
        assertEquals(2, ResolverSelection(listOf(ResolverTier(first), ResolverTier(withSecondary))).tiers.size)
    }

    @Test
    fun `a custom DoH selection roundtrips to its resolver configuration`() {
        val entry = ResolverSelectionEntry(
            ResolverProviderId.CUSTOM,
            CatalogResolverTransport.DOH,
            customDohUrl = "https://dns.google/dns-query",
        )
        val selection = ResolverSelection(listOf(ResolverTier(entry)))

        assertEquals(null, selection.toMilestonePlan())
        val configuration = selection.toCustomResolverConfiguration()
        assertEquals(
            ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDohUrl = "https://dns.google/dns-query",
            ),
            configuration,
        )
        assertEquals(selection, configuration!!.toSelection())
    }

    @Test
    fun `a custom DoT selection roundtrips to its resolver configuration`() {
        val entry = ResolverSelectionEntry(
            ResolverProviderId.CUSTOM,
            CatalogResolverTransport.DOT,
            customDotEndpoint = "tls://dns.google:853",
        )
        val selection = ResolverSelection(listOf(ResolverTier(entry)))

        assertEquals(null, selection.toMilestonePlan())
        val configuration = selection.toCustomResolverConfiguration()
        assertEquals(
            ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDotEndpoint = "tls://dns.google:853",
            ),
            configuration,
        )
        assertEquals(selection, configuration!!.toSelection())
    }

    @Test
    fun `a built-in selection has no custom configuration`() {
        assertEquals(null, ResolverSelectionPresets.DEVICE_DNS_ONLY.toCustomResolverConfiguration())
        assertEquals(null, ResolverSelectionPresets.MULLVAD_ONLY.toCustomResolverConfiguration())
    }

    @Test
    fun `the second tier may be device DNS or any served encrypted transport`() {
        val deviceSecond = ResolverSelection(
            listOf(
                ResolverTier(ResolverSelectionPresets.MULLVAD_DOH),
                ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
            ),
        )
        assertEquals(ResolverSelectionPresets.DEVICE_PLAIN, deviceSecond.fallback)
        assertEquals(deviceSecond, deviceSecond.toFilterResolverPlan()?.toSelection())
        assertNull(MilestoneResolverPlan.from(deviceSecond.toFilterResolverPlan()!!.configuration))

        val customSecond = ResolverSelection(
            listOf(
                ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
                ResolverTier(
                    ResolverSelectionEntry(
                        ResolverProviderId.CUSTOM,
                        CatalogResolverTransport.DOH,
                        customDohUrl = "https://dns.google/dns-query",
                        customName = "Home",
                    ),
                ),
            ),
        )
        val plan = customSecond.toFilterResolverPlan()
        assertEquals(MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK, plan?.plan)
        assertEquals("https://dns.google/dns-query", plan?.customDohUrl)
        // The ladder matches its own active projection, so no separate retained record is needed.
        assertNull(plan?.savedTiers)
    }

    @Test
    fun `two exact encrypted tiers roundtrip without collapsing the fallback into the primary`() {
        val first = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOQ,
            customDoqEndpoint = "doq://first.example:8853", customName = "First",
            customSecondaryEndpoint = "doq://first-secondary.example:853")
        val second = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOT,
            customDotEndpoint = "tls://second.example:853", customName = "Second")
        for (tiers in listOf(
            listOf(ResolverTier(first), ResolverTier(second)),
            listOf(ResolverTier(second), ResolverTier(first)),
            listOf(ResolverTier(first, false), ResolverTier(second)),
        )) {
            val selection = ResolverSelection(tiers)
            val plan = checkNotNull(selection.toFilterResolverPlan())
            assertEquals(selection, plan.toSelection())
            assertEquals(plan, FilterResolverPlan.from(plan.configuration))
            assertNull(MilestoneResolverPlan.from(plan.configuration))
        }
    }

    @Test
    fun `retained builtin name survives plan projection without choosing a different resolver`() {
        val selection = ResolverSelection(listOf(
            ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, false, "Hotel network"),
            ResolverTier(ResolverSelectionPresets.MULLVAD_DOH, true, "Private fallback"),
        ))
        val plan = checkNotNull(selection.toFilterResolverPlan())
        assertEquals(selection, plan.toSelection())
        assertEquals("Hotel network", plan.toSelection().tiers.first().displayName)
        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, MilestoneResolverPlan.from(plan.configuration))
        assertThrowsIllegalArgument { plan.configuration.copy(useDeviceDns = true) }
    }

    @Test
    fun `a duplicate second tier is refused`() {
        assertThrowsIllegalArgument {
            ResolverSelection(
                listOf(
                    ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
                    ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN),
                ),
            )
        }
    }

    @Test
    fun `the preset fallback combinations are exactly the milestone runtime routes`() {
        assertEquals(
            ResolverSelectionPresets.DEVICE_PLAIN,
            ResolverSelectionPresets.DEVICE_DNS_ONLY.primary,
        )
        assertEquals(null, ResolverSelectionPresets.DEVICE_DNS_ONLY.fallback)
        assertEquals(
            ResolverSelectionPresets.MULLVAD_DOH,
            ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK.fallback,
        )
        assertTrue(ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK.fallbackEnabled)
        assertEquals(
            ResolverSelectionPresets.MULLVAD_DOH,
            ResolverSelectionPresets.MULLVAD_ONLY.primary,
        )
        assertEquals(null, ResolverSelectionPresets.MULLVAD_ONLY.fallback)
    }

    @Test
    fun `old two-slot configurations decode to an equivalent ladder`() {
        val deviceWithFallback = ResolverConfiguration(
            useDeviceDns = true,
            useEncryptedFallback = true,
        )
        assertEquals(
            listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN), ResolverTier(ResolverSelectionPresets.MULLVAD_DOH)),
            deviceWithFallback.toSelection().tiers,
        )
        val deviceOnly = ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = false)
        assertEquals(
            listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN)),
            deviceOnly.toSelection().tiers,
        )
        val mullvadOnly = ResolverConfiguration(useDeviceDns = false, useEncryptedFallback = true)
        assertEquals(
            listOf(ResolverTier(ResolverSelectionPresets.MULLVAD_DOH)),
            mullvadOnly.toSelection().tiers,
        )
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            org.junit.Assert.assertFalse("expected IllegalArgumentException", true)
        } catch (_: IllegalArgumentException) {
            org.junit.Assert.assertTrue(true)
        }
    }
}
