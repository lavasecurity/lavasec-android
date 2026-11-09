package com.lavasec.app.directboot

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.ArtifactRuleCounts
import com.lavasec.app.foundation.filter.BlocklistParser
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactIdentity
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverSelectionPresets
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.DirectBootAppState
import com.lavasec.app.platform.FilterArtifactReference
import com.lavasec.app.platform.PersistedDirectBootConfigurationSnapshot
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DirectBootSnapshotCodecTest {
    @Test
    fun `all frozen stamp pairs survive Direct Boot codec without dropping bootstrap metadata`() {
        for (fixture in com.lavasec.app.DnsStampFixture.frozen) {
            val rows = listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, false, "Retained network"),
                ResolverTier(fixture.entry(), true, "Raw stamp"))
            val resolver = checkNotNull(ResolverSelection(rows).toFilterResolverPlan()).configuration
            val state = state().copy(useDeviceDns = resolver.useDeviceDns, useEncryptedFallback = resolver.useEncryptedFallback,
                encryptedFallbackId = resolver.encryptedFallbackId, customDohUrl = resolver.customDohUrl,
                customDotEndpoint = resolver.customDotEndpoint, customDoqEndpoint = resolver.customDoqEndpoint,
                customName = resolver.customName, customSecondaryEndpoint = resolver.customSecondaryEndpoint,
                savedTiers = resolver.savedTiers)
            val original = snapshot(state)
            val restored = decode(DirectBootSnapshotCodec.encode(original))
            assertEquals(original, restored)
            assertEquals(rows, restored?.state?.savedTiers)
        }
    }

    @Test
    fun `two encrypted rows and retained name secondary order and disabled bits roundtrip`() {
        val doq = ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.DOQ,
            customDoqEndpoint = "doq://first.example:8853", customName = "First",
            customSecondaryEndpoint = "doq://secondary.example:853")
        for (tiers in listOf(
            listOf(ResolverTier(doq), ResolverTier(ResolverSelectionPresets.MULLVAD_DOH, true, "Fallback")),
            listOf(ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, false, "Hotel"), ResolverTier(doq)),
            listOf(ResolverTier(doq), ResolverTier(ResolverSelectionPresets.DEVICE_PLAIN, true, "Network")),
        )) {
            val plan = checkNotNull(ResolverSelection(tiers).toFilterResolverPlan())
            val resolver = plan.configuration
            val state = state().copy(useDeviceDns = resolver.useDeviceDns,
                customDoqEndpoint = resolver.customDoqEndpoint, customName = resolver.customName,
                customSecondaryEndpoint = resolver.customSecondaryEndpoint, savedTiers = resolver.savedTiers)
            val snapshot = snapshot(state)
            val decoded = decode(DirectBootSnapshotCodec.encode(snapshot))
            assertEquals(snapshot, decoded)
            assertEquals(tiers, decoded?.state?.savedTiers)
            assertEquals(ResolverSelection(tiers), plan.toSelection())
        }
    }

    @Test
    fun `plain custom rows roundtrip without changing the address authority or disabled metadata`() {
        val tiers = listOf(ResolverTier(ResolverSelectionEntry(ResolverProviderId.CUSTOM, CatalogResolverTransport.PLAIN_DNS,
            customPlainEndpoint = "192.168.1.53", customName = "Office", customSecondaryEndpoint = "192.168.1.54")),
            ResolverTier(ResolverSelectionPresets.MULLVAD_DOH, false, "Retained"))
        val resolver = ResolverSelection(tiers).toFilterResolverPlan()!!.configuration
        val original = snapshot(state().copy(useDeviceDns = true, useEncryptedFallback = false, savedTiers = resolver.savedTiers))
        assertEquals(original, decode(DirectBootSnapshotCodec.encode(original)))
    }

    @Test
    fun `real prior mirror layouts remain readable with absent new metadata`() {
        for (version in 1..5) {
            val state = state().copy(customDoqEndpoint = if (version >= 4) "doq://dns.example:8853" else null)
            val current = DirectBootSnapshotCodec.encode(snapshot(state))
            val stateEnd = stateEnd(current)
            // V5 appended null name, null secondary and absent retained rows (three bytes).
            // V1..V3 additionally omit fields not yet present in their original wire layout.
            val remove = if (version >= 5) 0 else 3 + when (version) { 1 -> 3; 2 -> 2; 3 -> 1; else -> 0 }
            val legacy = current.copyOfRange(0, stateEnd - remove) + current.copyOfRange(stateEnd, current.size)
            ByteBuffer.wrap(legacy, 8, 4).putInt(version)
            val decoded = decode(legacy)
            assertEquals("schema $version", state, decoded?.state)
            assertNull(decoded?.state?.savedTiers)
        }
    }

    @Test
    fun `unknown future mirror schema and truncation fail closed`() {
        val bytes = DirectBootSnapshotCodec.encode(snapshot(state()))
        val future = bytes.copyOf().also { ByteBuffer.wrap(it, 8, 4).putInt(7) }
        assertNull(decode(future))
        assertNull(decode(bytes.copyOf(bytes.size - 1)))
    }

    private fun state() = DirectBootAppState.safeDefault().copy(onboardingCompleted = true,
        protectionIntent = true, useDeviceDns = false, useEncryptedFallback = true,
        encryptedFallbackId = "mullvad-doh", activeProfileId = "filter-balanced",
        configurationIdentity = ConfigurationIdentity(7L, "a".repeat(64)))

    private fun snapshot(state: DirectBootAppState) = PersistedDirectBootConfigurationSnapshot(state,
        FilterArtifactReference("b".repeat(64), FilterArtifactIdentity(
            ArtifactConfigurationIdentity(7L, "a".repeat(64)), emptyList(), "catalog-v1", "c".repeat(64),
            FilterArtifact.ARTIFACT_SCHEMA, FilterArtifact.POINTER_SCHEMA, BlocklistParser.RULES_VERSION,
            ArtifactRuleCounts(0, 0, 0, 0, 0, 0), "d".repeat(64), "e".repeat(64), true)), "f".repeat(64))

    private fun decode(bytes: ByteArray): PersistedDirectBootConfigurationSnapshot? {
        val file = Files.createTempFile("direct-boot-codec", ".snapshot").toFile()
        return try { file.writeBytes(bytes); DirectBootSnapshotCodec.decodeOrNull(file) } finally { file.delete() }
    }

    private fun stateEnd(bytes: ByteArray): Int {
        val source = ByteArrayInputStream(bytes)
        DataInputStream(source).use { input ->
            input.skipBytes(12) // Magic and version.
            repeat(4) { input.readBoolean() }
            fun string() { input.skipBytes(input.readInt()) }
            fun nullable() { if (input.readBoolean()) string() }
            nullable(); nullable()
            if (input.readBoolean()) { input.readLong(); string() }
            input.readBoolean()
            repeat(5) { nullable() } // DoH, DoT, DoQ, name and secondary.
            assertFalse(input.readBoolean()) // This helper's legacy fixture has no rows.
            return bytes.size - source.available()
        }
    }
}
