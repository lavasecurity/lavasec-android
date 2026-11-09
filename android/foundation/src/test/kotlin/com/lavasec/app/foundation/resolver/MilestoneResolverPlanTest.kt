package com.lavasec.app.foundation.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MilestoneResolverPlanTest {
    @Test
    fun `milestone exposes exactly Device plus Mullvad Device only and Mullvad only`() {
        assertEquals(
            listOf(
                MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
                MilestoneResolverPlan.DEVICE_DNS_ONLY,
                MilestoneResolverPlan.MULLVAD_ONLY,
            ),
            MilestoneResolverPlan.entries,
        )
    }

    @Test
    fun `each milestone plan maps to its exact runtime resolver configuration`() {
        val fallback = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK.configuration
        assertTrue(fallback.useDeviceDns)
        assertTrue(fallback.useEncryptedFallback)
        assertEquals(BuiltInDohResolverIds.MULLVAD, fallback.encryptedFallbackId)

        val device = MilestoneResolverPlan.DEVICE_DNS_ONLY.configuration
        assertTrue(device.useDeviceDns)
        assertFalse(device.useEncryptedFallback)
        assertEquals(BuiltInDohResolverIds.MULLVAD, device.encryptedFallbackId)

        val mullvad = MilestoneResolverPlan.MULLVAD_ONLY.configuration
        assertFalse(mullvad.useDeviceDns)
        assertTrue(mullvad.useEncryptedFallback)
        assertEquals(BuiltInDohResolverIds.MULLVAD, mullvad.encryptedFallbackId)
    }

    @Test
    fun `unsupported resolver configurations do not masquerade as milestone plans`() {
        assertNull(MilestoneResolverPlan.from(ResolverConfiguration(true, true, BuiltInDohResolverIds.QUAD9_SECURE)))
        assertThrowsIllegalArgument {
            ResolverConfiguration(false, false, BuiltInDohResolverIds.MULLVAD)
        }
        for (plan in MilestoneResolverPlan.entries) {
            assertEquals(plan, MilestoneResolverPlan.from(plan.configuration))
        }
    }

    @Test
    fun `raw resolver configuration keeps every built in fallback available for future flows`() {
        for (resolverId in BuiltInDohResolverIds.all) {
            assertEquals(
                resolverId,
                ResolverConfiguration(
                    useDeviceDns = false,
                    useEncryptedFallback = true,
                    encryptedFallbackId = resolverId,
                ).encryptedFallbackId,
            )
        }
    }

    @Test
    fun `raw resolver configuration rejects missing routes and invalid fallback identity`() {
        assertThrowsIllegalArgument {
            ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = false,
                encryptedFallbackId = BuiltInDohResolverIds.MULLVAD,
            )
        }
        assertThrowsIllegalArgument {
            ResolverConfiguration(
                useDeviceDns = true,
                useEncryptedFallback = true,
                encryptedFallbackId = " ",
            )
        }
        assertThrowsIllegalArgument {
            ResolverConfiguration(
                useDeviceDns = true,
                useEncryptedFallback = true,
                encryptedFallbackId = "not-built-in",
            )
        }
    }

    @Test
    fun `a custom DoH configuration is not a milestone plan but roundtrips as a selection`() {
        val custom = ResolverConfiguration(
            useDeviceDns = false,
            useEncryptedFallback = true,
            customDohUrl = "https://dns.google/dns-query",
        )
        assertNull(MilestoneResolverPlan.from(custom))
        assertEquals(custom, custom.toSelection().toCustomResolverConfiguration())
        assertThrowsIllegalArgument {
            ResolverConfiguration(
                useDeviceDns = false,
                useEncryptedFallback = true,
                customDohUrl = "http://dns.google/dns-query",
            )
        }
        // A custom resolver may be the encrypted second tier under a Device primary.
        val customFallback = ResolverConfiguration(
            useDeviceDns = true,
            useEncryptedFallback = true,
            customDohUrl = "https://dns.google/dns-query",
        )
        val fallbackSelection = customFallback.toSelection()
        assertEquals(ResolverProviderId.DEVICE_DNS, fallbackSelection.primary.providerId)
        assertEquals(ResolverProviderId.CUSTOM, fallbackSelection.fallback?.providerId)
    }

    @Test
    fun `a custom DoH configuration maps to the value-carrying plan with its URL`() {
        val custom = ResolverConfiguration(
            useDeviceDns = false,
            useEncryptedFallback = true,
            customDohUrl = "https://dns.google/dns-query",
        )

        val target = requireNotNull(FilterResolverPlan.from(custom))

        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, target.plan)
        assertEquals("https://dns.google/dns-query", target.customDohUrl)
        assertEquals(custom, target.configuration)
        assertEquals(custom.toSelection(), target.toSelection())
        assertEquals(target, custom.toSelection().toFilterResolverPlan())
    }

    @Test
    fun `a custom DoT configuration maps to the value-carrying plan with its endpoint`() {
        val custom = ResolverConfiguration(
            useDeviceDns = false,
            useEncryptedFallback = true,
                customDotEndpoint = "tls://dns.google:853",
        )

        val target = requireNotNull(FilterResolverPlan.from(custom))

        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, target.plan)
        assertEquals("tls://dns.google:853", target.customDotEndpoint)
        assertNull(target.customDohUrl)
        assertEquals(custom, target.configuration)
        assertEquals(custom.toSelection(), target.toSelection())
        assertEquals(target, custom.toSelection().toFilterResolverPlan())
    }

    @Test
    fun `every built-in configuration maps to a plan with no custom URL`() {
        for (plan in MilestoneResolverPlan.entries) {
            val target = requireNotNull(FilterResolverPlan.from(plan.configuration))
            assertEquals(plan, target.plan)
            assertNull(target.customDohUrl)
            assertEquals(plan.configuration, target.configuration)
            assertEquals(target, plan.toSelection().toFilterResolverPlan())
        }
    }

    @Test
    fun `a custom plan requires a normalized endpoint and the encrypted-only route shape`() {
        assertThrowsIllegalArgument {
            FilterResolverPlan(MilestoneResolverPlan.MULLVAD_ONLY, "not-a-url")
        }
        assertThrowsIllegalArgument {
            FilterResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY, "https://dns.google/dns-query")
        }
        assertThrowsIllegalArgument {
            FilterResolverPlan(MilestoneResolverPlan.MULLVAD_ONLY, customDotEndpoint = "not-an-endpoint")
        }
        assertThrowsIllegalArgument {
            FilterResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY, customDotEndpoint = "tls://dns.google:853")
        }
        assertThrowsIllegalArgument {
            FilterResolverPlan(
                MilestoneResolverPlan.MULLVAD_ONLY,
                customDohUrl = "https://dns.google/dns-query",
            customDotEndpoint = "tls://dns.google:853",
            )
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            assertFalse("expected IllegalArgumentException", true)
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
