package com.lavasec.app.data

import com.lavasec.app.foundation.catalog.CatalogArtifact
import com.lavasec.app.foundation.catalog.CatalogArtifactLoader
import com.lavasec.app.foundation.catalog.CatalogProvenance
import com.lavasec.app.foundation.catalog.CatalogProvenancePolicy
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CustomBlocklistSource
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM coverage of the catalog -> runtime-catalog projection that bridges a validated remote
 * manifest into the shape the filter authority compiles from. No Robolectric or `org.json`, so the
 * aarch64 work host executes it.
 */
class CatalogRuntimeCatalogTest {
    private companion object {
        val BUNDLED_SHA = "a".repeat(64)
        val HASH = "b".repeat(64)
        val CANDIDATE_AT = "2026-10-01T00:00:00Z"
    }

    private fun bundledCatalog(): BundledBlocklistCatalog = BundledBlocklistCatalog(
        schemaVersion = 1,
        categories = listOf("security"),
        sources = listOf(
            CatalogSourceMetadata(
                id = "approved-on",
                name = "Approved On",
                category = FilterSourceCategory.SECURITY,
                format = CatalogSourceFormat.HOSTS,
                blocklistFormat = BlocklistFormat.HOSTS,
                sourceUrl = "https://example.org/on.txt",
                defaultEnabled = true,
            ),
            CatalogSourceMetadata(
                id = "approved-off",
                name = "Approved Off",
                category = FilterSourceCategory.SECURITY,
                format = CatalogSourceFormat.HOSTS,
                blocklistFormat = BlocklistFormat.HOSTS,
                sourceUrl = "https://example.org/off.txt",
                defaultEnabled = false,
            ),
        ),
        revision = "bundled-revision",
        sha256 = BUNDLED_SHA,
        assetText = "{}",
    )

    private fun approvedSourceIds(bundled: BundledBlocklistCatalog): Set<String> =
        CatalogProvenancePolicy.approvedSourceIds(CatalogBundledArtifact.from(bundled).manifest)

    private fun sourceJson(
        id: String,
        defaultEnabled: Boolean = false,
        category: String = "security",
        format: String = "hosts",
    ): String =
        """{"id":"$id","name":"List $id","category":"$category","risk_level":"normal",""" +
            """"default_enabled":$defaultEnabled,"license_name":"Unlicense","attribution":"Provider",""" +
            """"project_url":"https://example.org/$id","source_url":"https://example.org/$id.txt",""" +
            """"version_id":"$id-v1","entry_count":10,"byte_size":100,"source_hash":"$HASH",""" +
            """"accepted_source_hashes":[{"sha256":"$HASH","byte_size":100,"entry_count":10,"status":"accepted"}],""" +
            """"normalized_hash":"$HASH","published_at":"2026-08-01T00:00:00Z",""" +
            """"redistribution_mode":"source_url_only","parse_format":"$format",""" +
            """"license_text_url":null,"notice_url":null}"""

    private fun manifestJson(
        version: String,
        sources: List<String>,
        guardrails: List<String> = emptyList(),
    ): ByteArray = (
        """{"schema_version":2,"catalog_version":"$version","generated_at":"$CANDIDATE_AT",""" +
            """"sources":[${sources.joinToString(",")}],"guardrails":[${guardrails.joinToString(",")}]}"""
        ).encodeToByteArray()

    private fun remoteArtifact(bytes: ByteArray, approved: Set<String>): CatalogArtifact =
        requireNotNull(CatalogArtifactLoader.load(bytes, CatalogProvenance.REMOTE, approved))

    @Test
    fun `projects selectable sources and pins the remote revision and digest`() {
        val bundled = bundledCatalog()
        val artifact = remoteArtifact(
            manifestJson("catalog-2", listOf(sourceJson("approved-on"), sourceJson("brand-new"))),
            approvedSourceIds(bundled),
        )

        val projected = CatalogRuntimeCatalog.fromArtifact(artifact, bundled)

        assertEquals(setOf("approved-on", "brand-new"), CatalogRuntimeCatalog.selectableSourceIds(projected))
        assertEquals("catalog-2", projected.revision)
        assertEquals(artifact.contentSha256, projected.sha256)
        assertEquals(listOf("security"), projected.categories)
    }

    @Test
    fun `retired entries cannot reappear from remote or cached catalog projections`() {
        val bundled = bundledCatalog()
        val bytes = manifestJson("old-cache", listOf(sourceJson("approved-on"), sourceJson("blocklistproject-malware")))
        for (provenance in listOf(CatalogProvenance.REMOTE, CatalogProvenance.LAST_KNOWN_GOOD)) {
            val artifact = requireNotNull(CatalogArtifactLoader.load(bytes, provenance, approvedSourceIds(bundled)))
            val projected = CatalogRuntimeCatalog.fromArtifact(artifact, bundled)
            assertEquals(setOf("approved-on"), CatalogRuntimeCatalog.selectableSourceIds(projected))
            assertEquals(artifact.catalogVersion, projected.revision)
            assertEquals(artifact.contentSha256, projected.sha256)
        }
    }

    @Test
    fun `a source outside the canonical taxonomy is never projected as selectable`() {
        val bundled = bundledCatalog()
        val artifact = remoteArtifact(
            manifestJson(
                "catalog-2",
                listOf(sourceJson("approved-on"), sourceJson("mystery", category = "mystery")),
            ),
            approvedSourceIds(bundled),
        )

        val projected = CatalogRuntimeCatalog.fromArtifact(artifact, bundled)

        assertEquals(listOf("approved-on"), projected.sources.map(CatalogSourceMetadata::id))
    }

    @Test
    fun `auto and dnsmasq parse formats map to the runtime formats`() {
        val bundled = bundledCatalog()
        val artifact = remoteArtifact(
            manifestJson(
                "catalog-2",
                listOf(sourceJson("auto", format = "auto"), sourceJson("dns", format = "dnsmasq")),
            ),
            approvedSourceIds(bundled),
        )

        val projected = CatalogRuntimeCatalog.fromArtifact(artifact, bundled)

        assertEquals(CatalogSourceFormat.AUTO, projected.sources.single { it.id == "auto" }.format)
        assertEquals(BlocklistFormat.AUTO, projected.sources.single { it.id == "auto" }.blocklistFormat)
        assertEquals(CatalogSourceFormat.DNSMASQ, projected.sources.single { it.id == "dns" }.format)
        assertEquals(BlocklistFormat.DNSMASQ, projected.sources.single { it.id == "dns" }.blocklistFormat)
    }

    @Test
    fun `the bundled anchor round-trips to the exact supplied catalog`() {
        val bundled = bundledCatalog()

        val projected = CatalogRuntimeCatalog.fromArtifact(CatalogBundledArtifact.from(bundled), bundled)

        assertSame(bundled, projected)
    }

    @Test
    fun `reconcile intersects enabled ids and preserves the rest of the profile`() {
        val profile = FilterProfileDraft(
            id = "balanced",
            name = "Balanced",
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = setOf("approved-on", "vanished"),
        )
        val library = FilterLibraryDraft(
            profiles = listOf(profile),
            activeProfileId = "balanced",
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )

        val reconciled = reconcileLibraryForCatalog(library, setOf("approved-on", "brand-new"))

        assertEquals(setOf("approved-on"), reconciled.profile("balanced")!!.enabledSourceIds)
        assertEquals(profile.name, reconciled.profile("balanced")!!.name)
        assertEquals(profile.protectionLevel, reconciled.profile("balanced")!!.protectionLevel)
        assertEquals(library.activeProfileId, reconciled.activeProfileId)
    }

    @Test
    fun `catalog reconciliation preserves each profiles own custom selections without enabling others`() {
        val activeCustom = CustomBlocklistSource("custom-active", "My active list", "https://example.org/active.txt")
        val disabledCustom = CustomBlocklistSource("custom-disabled", "My disabled list", "https://example.org/disabled.txt")
        val inactiveCustom = CustomBlocklistSource("custom-inactive", "My other list", "https://example.org/other.txt")
        val active = FilterProfileDraft(
            id = "active", name = "Active", protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = setOf("approved-on", "vanished", activeCustom.id, inactiveCustom.id),
            customBlocklists = listOf(activeCustom, disabledCustom),
        )
        val inactive = FilterProfileDraft(
            id = "inactive", name = "Inactive", protectionLevel = null,
            enabledSourceIds = setOf("vanished", inactiveCustom.id, activeCustom.id),
            customBlocklists = listOf(inactiveCustom),
        )
        val library = FilterLibraryDraft(
            profiles = listOf(active, inactive), activeProfileId = active.id,
            resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
        )

        val reconciled = reconcileLibraryForCatalog(library, setOf("approved-on", "brand-new"))

        assertEquals(setOf("approved-on", activeCustom.id), reconciled.profile(active.id)!!.enabledSourceIds)
        assertEquals(setOf(inactiveCustom.id), reconciled.profile(inactive.id)!!.enabledSourceIds)
        assertEquals(active.customBlocklists, reconciled.profile(active.id)!!.customBlocklists)
        assertEquals(inactive.customBlocklists, reconciled.profile(inactive.id)!!.customBlocklists)
        assertEquals(library.activeProfileId, reconciled.activeProfileId)
        assertEquals(setOf("approved-on", "vanished", activeCustom.id, inactiveCustom.id), active.enabledSourceIds)
    }
}
