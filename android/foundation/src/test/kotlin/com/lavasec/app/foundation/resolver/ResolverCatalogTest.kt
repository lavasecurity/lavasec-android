package com.lavasec.app.foundation.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverCatalogTest {
    @Test
    fun `picker follows frozen catalog and retired readers retain their original endpoints`() {
        assertEquals(listOf("device-dns", "quad9-unfiltered", "cloudflare-1111", "hagezi-root", "google-public-dns", "custom-dns"),
            ResolverCatalog.providers.map { it.id.rawId })
        assertEquals("https://dns.mullvad.net/dns-query", ResolverCatalog.dohUrl(ResolverCatalog.provider(ResolverProviderId.MULLVAD)))
        assertEquals("https://dns.quad9.net/dns-query", ResolverCatalog.dohUrl(ResolverCatalog.provider(ResolverProviderId.QUAD9_SECURE)))
        assertEquals("https://dns10.quad9.net/dns-query", ResolverCatalog.dohUrl(ResolverCatalog.quad9Unfiltered))
        assertEquals("https://root.hagezi.org/dns-query", ResolverCatalog.dohUrl(ResolverCatalog.hagezi))
        assertEquals(listOf("9.9.9.10", "2620:fe::10"), ResolverCatalog.addresses(ResolverCatalog.quad9Unfiltered))
    }

    @Test
    fun `catalog encrypted endpoints are served and eligible in either tier`() {
        for (provider in ResolverCatalog.providers) {
            assertTrue(provider.fallbackEligible)
            assertEquals(provider.id == ResolverProviderId.CUSTOM, provider.requiresEntitlement)
            if (provider.id in setOf(ResolverProviderId.CUSTOM, ResolverProviderId.DEVICE_DNS)) continue
            assertTrue(ResolverCatalog.capability(provider, CatalogResolverTransport.DOH).available)
            assertTrue(ResolverCatalog.capability(provider, CatalogResolverTransport.DOT).available)
            assertFalse(ResolverCatalog.capability(provider, CatalogResolverTransport.DOQ).available)
            assertTrue(ResolverCatalog.capability(provider, CatalogResolverTransport.PLAIN_DNS).available)
        }
        assertTrue(ResolverCatalog.capability(ResolverCatalog.deviceDns, CatalogResolverTransport.PLAIN_DNS).available)
        assertEquals(listOf(ResolverProviderId.DEVICE_DNS), ResolverCatalog.providers.filter { it.recommended }.map { it.id })
    }

    @Test
    fun `portable preset IDs are exact and retired migration is explicit`() {
        for (provider in ResolverCatalog.providers.filter { it.id !in setOf(ResolverProviderId.CUSTOM, ResolverProviderId.DEVICE_DNS) }) {
            for ((suffix, transport) in listOf("-doh" to CatalogResolverTransport.DOH, "-dot" to CatalogResolverTransport.DOT)) {
                val id = provider.id.rawId + suffix
                val entry = ResolverCatalog.entryForPresetId(id, migrateRetired = false)!!
                assertEquals(provider.id, entry.providerId)
                assertEquals(transport, entry.transport)
                assertEquals(id, ResolverCatalog.presetId(entry))
            }
        }
        for (retired in listOf("dns-sb", "mullvad", "quad9-secure")) {
            for (suffix in listOf("-doh", "-dot")) {
                assertNull(ResolverCatalog.entryForPresetId(retired + suffix, migrateRetired = false))
                assertEquals("quad9-unfiltered" + suffix, ResolverCatalog.presetId(ResolverCatalog.entryForPresetId(retired + suffix)!!))
            }
        }
        for (unknown in listOf("unknown", "custom-dns", "cloudflare-1111-doq", "device-dns-doh", "cloudflare-1111-dot-doh")) {
            assertNull(ResolverCatalog.entryForPresetId(unknown))
        }
    }

    @Test
    fun `every custom transport is unavailable with the custom runtime absent reason`() {
        for (transport in CatalogResolverTransport.entries) {
            val capability = ResolverCatalog.capability(ResolverCatalog.custom, transport)
            assertFalse(capability.available)
            assertEquals(
                ResolverTransportUnavailableReason.CUSTOM_RESOLVER_RUNTIME_ABSENT,
                capability.unavailableReason,
            )
        }
    }

    @Test
    fun `custom DoH is served only when it carries a valid normalized URL`() {
        assertTrue(
            ResolverCatalog.capability(
                ResolverCatalog.custom,
                CatalogResolverTransport.DOH,
                "https://dns.google/dns-query",
            ).available,
        )
        // Unparseable, wrong-scheme and pathless values stay unavailable.
        for (url in listOf("not a url", "http://dns.google/dns-query", "https://dns.google", "")) {
            val capability = ResolverCatalog.capability(
                ResolverCatalog.custom,
                CatalogResolverTransport.DOH,
                url,
            )
            assertFalse("url $url", capability.available)
            assertEquals(
                ResolverTransportUnavailableReason.CUSTOM_RESOLVER_RUNTIME_ABSENT,
                capability.unavailableReason,
            )
        }
        // A DoT endpoint does not make a custom DoH route available.
        assertFalse(
            ResolverCatalog.capability(
                ResolverCatalog.custom,
                CatalogResolverTransport.DOT,
                "tls://dns.google:853",
            ).available,
        )
    }

    @Test
    fun `custom DoT is served only when it carries a valid normalized endpoint`() {
        assertTrue(
            ResolverCatalog.capability(
                ResolverCatalog.custom,
                CatalogResolverTransport.DOT,
                customDotEndpoint = "tls://dns.google:853",
            ).available,
        )
        // Unparseable, path-carrying and scheme-less values stay unavailable.
        for (endpoint in listOf("not an endpoint", "tls://dns.google/path", "https://dns.google/dns-query", "")) {
            val capability = ResolverCatalog.capability(
                ResolverCatalog.custom,
                CatalogResolverTransport.DOT,
                customDotEndpoint = endpoint,
            )
            assertFalse("endpoint $endpoint", capability.available)
            assertEquals(
                ResolverTransportUnavailableReason.CUSTOM_RESOLVER_RUNTIME_ABSENT,
                capability.unavailableReason,
            )
        }
        // Carrying both endpoints is incoherent and stays unavailable.
        assertFalse(
            ResolverCatalog.capability(
                ResolverCatalog.custom,
                CatalogResolverTransport.DOT,
                customDohUrl = "https://dns.google/dns-query",
                customDotEndpoint = "tls://dns.google:853",
            ).available,
        )
    }

    @Test
    fun `transport rows keep the iOS menu order with their capabilities`() {
        // The legacy provider remains a reader of its exact original encrypted endpoints.
        assertEquals(
            listOf(
                CatalogResolverTransport.PLAIN_DNS to true,
                CatalogResolverTransport.DOH to true,
                CatalogResolverTransport.DOT to true,
            ),
            ResolverCatalog.transportRows(ResolverCatalog.mullvad).map { (transport, capability) ->
                transport to capability.available
            },
        )
        // The custom provider offers every transport; every row is unavailable.
        assertEquals(
            listOf(
                CatalogResolverTransport.PLAIN_DNS to false,
                CatalogResolverTransport.DOH to false,
                CatalogResolverTransport.DOT to false,
                CatalogResolverTransport.DOQ to false,
            ),
            ResolverCatalog.transportRows(ResolverCatalog.custom).map { (transport, capability) ->
                transport to capability.available
            },
        )
    }

    @Test
    fun `capability value requires exactly one availability fact`() {
        assertNull(ResolverTransportCapability.available().unavailableReason)
        assertEquals(
            ResolverTransportUnavailableReason.DOT_RUNTIME_ABSENT,
            ResolverTransportCapability.unavailable(
                ResolverTransportUnavailableReason.DOT_RUNTIME_ABSENT,
            ).unavailableReason,
        )
    }
}
