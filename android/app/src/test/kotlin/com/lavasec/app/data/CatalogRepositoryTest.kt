package com.lavasec.app.data

import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfile
import com.lavasec.app.foundation.filter.FilterSourceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogRepositoryTest {
    @Test
    fun `historical bundled bytes prove the prior seed without becoming the current catalog`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = CatalogRepository(context.assets)
        val current = repository.loadBundled()
        val previous = repository.loadHistoricalBundled().first { it.revision == "d5b44dad805ee43e28dd19419b1340d557776aaf" }
        assertEquals("d5b44dad805ee43e28dd19419b1340d557776aaf", previous.revision)
        assertEquals("88e15e8fc80092830d58da54fe01897327b24dd689fc45090f6734e3ae9155b0", previous.sha256)
        assertTrue(previous.sources.any { it.id == "blocklistproject-malware" })
        assertFalse(current.sources.any { it.id == "blocklistproject-malware" })
        assertEquals(previous.defaultSourceIds, current.defaultSourceIds)
        assertEquals(current, repository.loadBundled())
    }

    @Test
    fun `history distinguishes recommendation policies for identical catalog bytes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = CatalogRepository(context.assets)
        val index = org.json.JSONObject().put("schema_version", 1).put("snapshots", org.json.JSONArray().apply {
            for (policy in listOf(listOf("blocklistproject-basic", "stevenblack-unified"), listOf("blocklistproject-basic"))) {
                put(org.json.JSONObject()
                    .put("asset_path", CatalogRepository.ASSET_PATH)
                    .put("blob", CatalogRepository.PINNED_BLOB)
                    .put("sha256", CatalogRepository.PINNED_SHA256)
                    .put("onboarding_recommended_source_ids", org.json.JSONArray(policy)))
            }
        })
        val catalogs = repository.loadHistoricalBundled(index)
        assertEquals(2, catalogs.size)
        assertEquals(catalogs[0].assetText, catalogs[1].assetText)
        assertEquals(catalogs[0].revision, catalogs[1].revision)
        assertEquals(setOf("blocklistproject-basic", "stevenblack-unified"), catalogs[0].defaultSourceIds)
        assertEquals(setOf("blocklistproject-basic"), catalogs[1].defaultSourceIds)
        val configuration = com.lavasec.app.foundation.config.AppConfiguration.default()
        assertFalse(SeededFilterConfiguration.from(configuration, catalogs[0], 1L).identity ==
            SeededFilterConfiguration.from(configuration, catalogs[1], 1L).identity)
    }

    @Test
    fun `retirement removes only the retired ID across active and inactive profiles`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val catalog = CatalogRepository(context.assets).loadBundled()
        val seeded = FilterLibraryDraft.seededDefaults(catalog.filterSnapshot)
        val old = seeded.withProfiles(seeded.profiles.map { profile ->
            profile.withEnabledSourceIds(profile.enabledSourceIds +
                setOf("blocklistproject-malware", "unknown-user-selection"))
        })
        val migrated = old.withRetiredCatalogSelectionsRemoved()
        assertEquals(old.activeProfileId, migrated.activeProfileId)
        assertEquals(old.resolverPlan, migrated.resolverPlan)
        old.profiles.zip(migrated.profiles).forEach { (before, after) ->
            assertEquals(before.withEnabledSourceIds(before.enabledSourceIds - "blocklistproject-malware"), after)
            assertTrue("unknown-user-selection" in after.enabledSourceIds)
            assertTrue("blocklistproject-malware" in before.enabledSourceIds)
        }
        assertEquals(migrated, migrated.withRetiredCatalogSelectionsRemoved())
        assertEquals(seeded, seeded.withRetiredCatalogSelectionsRemoved())
    }

    @Test
    fun `bundled catalog is the exact pinned metadata-only asset`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val catalog = CatalogRepository(context.assets).loadBundled()

        assertEquals(1, catalog.schemaVersion)
        assertEquals(7, catalog.categories.size)
        assertEquals(33, catalog.sources.size)
        assertEquals(15, catalog.sources.count { it.format == CatalogSourceFormat.HOSTS })
        assertEquals(12, catalog.sources.count { it.format == CatalogSourceFormat.PLAIN_DOMAINS })
        assertEquals(6, catalog.sources.count { it.format == CatalogSourceFormat.ADBLOCK })
        assertEquals(setOf("blocklistproject-basic", "stevenblack-unified"), catalog.defaultSourceIds)
        assertEquals("Block List Basic", catalog.sources.first().name)
        assertEquals(FilterSourceCategory.SECURITY, catalog.sources.first().category)
        assertEquals(
            listOf(
                Triple(
                    "blocklistproject-basic",
                    "https://blocklistproject.github.io/Lists/basic.txt",
                    BlocklistFormat.HOSTS,
                ),
                Triple(
                    "stevenblack-unified",
                    "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                    BlocklistFormat.HOSTS,
                ),
            ),
            catalog.defaultSources.map { Triple(it.id, it.sourceUrl, it.blocklistFormat) },
        )
        assertEquals("587c34de8ea62e3e0d48bd9932624ff59b37aa00", catalog.revision)
        assertEquals("73f9e145092a71f869df90b0b758949b3fa8453fe22d4b942503c8d8494d74c6", catalog.sha256)
        assertFalse(catalog.assetText.contains("private_url"))
        assertFalse(catalog.assetText.contains("mirror_url"))
    }

    @Test
    fun `bundled catalog projection derives the exact three pinned profile selections`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val catalog = CatalogRepository(context.assets).loadBundled()

        val profiles = FilterProfile.seededDefaults(catalog.filterSnapshot)

        assertEquals(
            setOf("blocklistproject-basic"),
            profiles.getValue(ProtectionLevel.CORE).enabledSourceIds,
        )
        assertEquals(
            setOf("blocklistproject-basic", "stevenblack-unified"),
            profiles.getValue(ProtectionLevel.BALANCED).enabledSourceIds,
        )
        assertEquals(
            catalog.defaultSourceIds + catalog.sources
                .filter { it.category == FilterSourceCategory.ADS_TRACKING }
                .map { it.id },
            profiles.getValue(ProtectionLevel.EXTRA).enabledSourceIds,
        )
        assertEquals(4, profiles.getValue(ProtectionLevel.EXTRA).enabledSourceIds.size)
        assertTrue(catalog.filterSnapshot.guardrailSources.isEmpty())
    }
}
