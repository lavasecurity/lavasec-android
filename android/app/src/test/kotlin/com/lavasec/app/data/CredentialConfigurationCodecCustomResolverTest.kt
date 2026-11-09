package com.lavasec.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.FilterMetadata
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.platform.PersistedAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialConfigurationCodecCustomResolverTest {
    private val catalog = BundledBlocklistCatalog(1, emptyList(), emptyList(), "", "", "")

    private fun state(resolver: ResolverConfiguration): PersistedAppState {
        val level = ProtectionLevel.BALANCED
        return PersistedAppState(
            onboardingCompleted = true,
            configuration = AppConfiguration(
                protectionLevel = level,
                resolver = resolver,
                activeFilterId = level.seededFilterId,
                keepCounts = true,
                keepHistory = true,
            ),
            protectionIntent = true,
            activeFilter = requireNotNull(FilterMetadata.forId(level.seededFilterId)),
            logging = LoggingSettings(),
            protectionSessionId = 3L,
            configurationIdentity = ConfigurationIdentity(41L, "a".repeat(64)),
        )
    }

    private fun roundTrip(resolver: ResolverConfiguration): ResolverConfiguration? {
        val preferences = mutablePreferencesOf()
        CredentialConfigurationCodec.encode(preferences, state(resolver))
        val decoded = CredentialConfigurationCodec.decode(preferences, catalog)
        assertTrue(decoded.onboardingCompleted)
        return decoded.configuration?.resolver
    }

    @Test
    fun `plain custom rows preserve exact endpoints metadata and disabled positions`() {
        val selection = com.lavasec.app.foundation.resolver.ResolverSelection(listOf(
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.PLAIN_DNS,
                customPlainEndpoint = "192.168.1.53", customName = "Office", customSecondaryEndpoint = "192.168.1.54")),
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.QUAD9_UNFILTERED, CatalogResolverTransport.DOT), false, "Retained")))
        val resolver = selection.toFilterResolverPlan()!!.configuration
        assertEquals(resolver, roundTrip(resolver))
        assertEquals(selection, roundTrip(resolver)!!.toSelection())
    }

    @Test
    fun `DoH encoded query pairs and a disabled custom row survive the credential codec exactly`() {
        val primary = "https://resolver.example/dns%2Fquery?mode=one%2Ftwo&empty=&mode=second&flag"
        val secondary = "https://secondary.example/dns%2Fquery?mode=%23two&empty=&flag"
        val disabled = "https://resolver.example/dns%2Fquery?mode=disabled&empty=&flag"
        val selection = com.lavasec.app.foundation.resolver.ResolverSelection(listOf(
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH,
                customDohUrl = primary, customSecondaryEndpoint = secondary, customName = "Saved HTTPS")),
            ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOH,
                customDohUrl = disabled, customSecondaryEndpoint = secondary, customName = "Disabled HTTPS"), false)))
        val resolver = selection.toFilterResolverPlan()!!.configuration

        val decoded = roundTrip(resolver)!!

        assertEquals(resolver, decoded)
        assertEquals(primary, decoded.customDohUrl)
        assertEquals(secondary, decoded.customSecondaryEndpoint)
        assertEquals(selection, decoded.toSelection())
        val rows = checkNotNull(decoded.savedTiers)
        assertEquals(listOf(true, false), rows.map { it.isEnabled })
        assertEquals(disabled, rows[1].entry.customDohUrl)
        assertEquals(secondary, rows[1].entry.customSecondaryEndpoint)
    }

    @Test
    fun `DoQ plan and disabled row survive persistence with exact endpoint identities`() {
        val resolver = ResolverConfiguration(
            useDeviceDns = false, useEncryptedFallback = true,
            customDoqEndpoint = "doq://dns.example:8853", customName = "Private",
            customSecondaryEndpoint = "doq://secondary.example:853",
            savedTiers = listOf(
                ResolverTier(ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS), false),
                ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOQ,
                    customDoqEndpoint = "doq://dns.example:8853", customName = "Private",
                    customSecondaryEndpoint = "doq://secondary.example:853")),
            ),
        )
        val decoded = roundTrip(resolver)
        assertEquals(resolver, decoded)
        assertEquals(resolver.toSelection(), decoded?.toSelection())
        assertNull(roundTrip(ResolverConfiguration())?.customDoqEndpoint)
    }

    @Test
    fun `custom name and secondary round trip through the credential codec`() {
        val resolver = ResolverConfiguration(
            useDeviceDns = false,
            useEncryptedFallback = true,
            encryptedFallbackId = BuiltInDohResolverIds.MULLVAD,
            customDohUrl = "https://dns.google/dns-query",
            customName = "Home Resolver",
            customSecondaryEndpoint = "https://cloudflare-dns.com/dns-query",
        )

        val decoded = roundTrip(resolver)

        assertEquals(resolver, decoded)
        assertEquals("Home Resolver", decoded?.customName)
        assertEquals("https://cloudflare-dns.com/dns-query", decoded?.customSecondaryEndpoint)
    }

    @Test
    fun `a built-in resolver decodes with no custom name or secondary`() {
        val decoded = roundTrip(ResolverConfiguration())

        assertNull(decoded?.customName)
        assertNull(decoded?.customSecondaryEndpoint)
        assertNull(decoded?.customDohUrl)
        assertNull(decoded?.customDotEndpoint)
    }

    @Test
    fun `a retained disabled ladder round trips and a pre-ladder store decodes equivalently`() {
        val resolver = ResolverConfiguration(
            useDeviceDns = true,
            useEncryptedFallback = false,
            savedTiers = listOf(
                ResolverTier(
                    ResolverSelectionEntry(ResolverProviderId.DEVICE_DNS, CatalogResolverTransport.PLAIN_DNS),
                ),
                ResolverTier(
                    ResolverSelectionEntry(ResolverProviderId.MULLVAD, CatalogResolverTransport.DOH),
                    isEnabled = false,
                ),
            ),
        )

        val decoded = roundTrip(resolver)
        assertEquals(resolver, decoded)
        assertEquals(listOf(true, false), decoded?.savedTiers?.map { it.isEnabled })

        // A store written before the ladder key existed decodes the legacy two-slot fields into
        // an equivalent ladder rather than losing the fallback.
        val legacy = ResolverConfiguration(useDeviceDns = true, useEncryptedFallback = true)
        val legacyPreferences = mutablePreferencesOf()
        CredentialConfigurationCodec.encode(legacyPreferences, state(legacy))
        legacyPreferences.remove(
            androidx.datastore.preferences.core.stringPreferencesKey("saved_tiers"),
        )
        val legacyDecoded = CredentialConfigurationCodec.decode(legacyPreferences, catalog)
        assertNull(legacyDecoded.configuration?.resolver?.savedTiers)
        assertEquals(
            listOf(ResolverProviderId.DEVICE_DNS, ResolverProviderId.MULLVAD),
            legacyDecoded.configuration?.resolver?.toSelection()?.tiers?.map { it.entry.providerId },
        )
    }

    @Test
    fun `a store written before the name and secondary keys still decodes`() {
        val resolver = ResolverConfiguration(
            useDeviceDns = false,
            useEncryptedFallback = true,
            encryptedFallbackId = BuiltInDohResolverIds.MULLVAD,
            customDotEndpoint = "tls://dns.google:853",
        )
        val preferences = mutablePreferencesOf()
        CredentialConfigurationCodec.encode(preferences, state(resolver))
        // Simulate a pre-upgrade store: the name and secondary keys simply do not exist.
        preferences.remove(
            androidx.datastore.preferences.core.stringPreferencesKey("custom_name"),
        )
        preferences.remove(
            androidx.datastore.preferences.core.stringPreferencesKey("custom_secondary_endpoint"),
        )

        val decoded = CredentialConfigurationCodec.decode(preferences, catalog)

        assertEquals("tls://dns.google:853", decoded.configuration?.resolver?.customDotEndpoint)
        assertNull(decoded.configuration?.resolver?.customName)
        assertNull(decoded.configuration?.resolver?.customSecondaryEndpoint)
    }
}
