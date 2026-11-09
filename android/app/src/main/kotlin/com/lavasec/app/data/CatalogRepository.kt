package com.lavasec.app.data

import android.content.res.AssetManager
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CatalogFilterSource
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterSourceCategory
import java.security.MessageDigest
import org.json.JSONObject

enum class CatalogSourceFormat { AUTO, HOSTS, PLAIN_DOMAINS, ADBLOCK, DNSMASQ }

data class CatalogSourceMetadata(
    val id: String,
    val name: String,
    val category: FilterSourceCategory,
    val format: CatalogSourceFormat,
    val blocklistFormat: BlocklistFormat,
    val sourceUrl: String,
    val defaultEnabled: Boolean,
    /** Human license name (for example `MIT`); blank when the artifact does not carry one. */
    val licenseName: String = "",
    /** Declared rule count for a source, or null when the catalog does not know one. */
    val entryCount: Int? = null,
)

data class BundledBlocklistCatalog(
    val schemaVersion: Int,
    val categories: List<String>,
    val sources: List<CatalogSourceMetadata>,
    val revision: String,
    val sha256: String,
    val assetText: String,
    /** Display label per category id, in the order the catalog authored them. */
    val categoryLabels: Map<String, String> = emptyMap(),
) {
    val defaultSourceIds: Set<String> = sources.filter(CatalogSourceMetadata::defaultEnabled).mapTo(linkedSetOf()) { it.id }
    val defaultSources: List<CatalogSourceMetadata> = sources.filter(CatalogSourceMetadata::defaultEnabled)
    val filterSnapshot: FilterCatalogSnapshot = FilterCatalogSnapshot(
        sources.map { source ->
            CatalogFilterSource(
                id = source.id,
                category = source.category,
                defaultEnabled = source.defaultEnabled,
            )
        },
    )
}

/** Reads the pinned metadata-only catalog bundled with the application. */
class CatalogRepository(private val assets: AssetManager) {
    fun loadBundled(): BundledBlocklistCatalog = load(
        ASSET_PATH, PINNED_BLOB, PINNED_SHA256, RECOMMENDED_SOURCE_IDS,
    )

    // Historical bytes only prove an unchanged seeded configuration during missing-store
    // recovery. They never supply the current directory or compile inputs.
    internal fun loadHistoricalBundled(): List<BundledBlocklistCatalog> {
        val index = JSONObject(assets.open(HISTORY_INDEX_PATH).use { it.readBytes() }.toString(Charsets.UTF_8))
        return loadHistoricalBundled(index)
    }

    internal fun loadHistoricalBundled(index: JSONObject): List<BundledBlocklistCatalog> {
        require(index.getInt("schema_version") == 1)
        val snapshots = index.getJSONArray("snapshots")
        val identities = mutableSetOf<Pair<String, Set<String>?>>()
        return List(snapshots.length()) { position ->
            val snapshot = snapshots.getJSONObject(position)
            val assetPath = snapshot.getString("asset_path")
            val revision = snapshot.getString("blob")
            val digest = snapshot.getString("sha256")
            require(assetPath.startsWith("catalog/") && assetPath.endsWith(".json") &&
                assetPath.split('/').none { it == ".." || it.isEmpty() })
            require(revision.matches(Regex("[a-f0-9]{40}")))
            require(digest.matches(Regex("[a-f0-9]{64}")))
            val recommendations = if (snapshot.isNull("onboarding_recommended_source_ids")) null else {
                val ids = snapshot.getJSONArray("onboarding_recommended_source_ids")
                List(ids.length()) { ids.getString(it) }.toSet()
            }
            require(identities.add(revision to recommendations))
            load(assetPath, revision, digest, recommendations)
        }
    }

    private fun load(
        assetPath: String,
        revision: String,
        expectedSha256: String,
        recommendations: Set<String>?,
    ): BundledBlocklistCatalog {
        val bytes = assets.open(assetPath).use { it.readBytes() }
        val digest = sha256(bytes)
        require(digest == expectedSha256) { "Bundled blocklist catalog does not match its pinned digest" }
        val text = bytes.toString(Charsets.UTF_8)
        val root = JSONObject(text)
        val categoryArray = root.getJSONArray("categories")
        val categoryLabels = LinkedHashMap<String, String>()
        val categories = List(categoryArray.length()) { index ->
            val value = categoryArray.get(index)
            if (value is JSONObject) {
                val id = value.optString("id", value.toString())
                value.optString("label").takeIf { it.isNotBlank() }?.let { categoryLabels[id] = it }
                id
            } else {
                value.toString()
            }
        }
        val sourceArray = root.getJSONArray("sources")
        val sources = List(sourceArray.length()) { index ->
            val source = sourceArray.getJSONObject(index)
            val format = when (source.getString("parse_format")) {
                "hosts" -> CatalogSourceFormat.HOSTS
                "domain_list" -> CatalogSourceFormat.PLAIN_DOMAINS
                "adblock" -> CatalogSourceFormat.ADBLOCK
                else -> error("Unsupported catalog parse format")
            }
            CatalogSourceMetadata(
                id = source.getString("id"),
                name = source.getString("name"),
                category = sourceCategory(source.getString("category")),
                format = format,
                blocklistFormat = when (format) {
                    CatalogSourceFormat.AUTO -> BlocklistFormat.AUTO
                    CatalogSourceFormat.HOSTS -> BlocklistFormat.HOSTS
                    CatalogSourceFormat.PLAIN_DOMAINS -> BlocklistFormat.PLAIN_DOMAINS
                    CatalogSourceFormat.ADBLOCK -> BlocklistFormat.ADBLOCK
                    CatalogSourceFormat.DNSMASQ -> BlocklistFormat.DNSMASQ
                },
                sourceUrl = source.getString("source_url"),
                defaultEnabled = recommendations?.contains(source.getString("id"))
                    ?: source.optBoolean("default_enabled", false),
                licenseName = source.optString("license"),
                entryCount = if (source.has("entry_count")) source.optInt("entry_count") else null,
            )
        }
        return BundledBlocklistCatalog(
            schemaVersion = root.getInt("schema_version"),
            categories = categories,
            sources = sources,
            revision = revision,
            sha256 = digest,
            assetText = text,
            categoryLabels = categoryLabels,
        )
    }

    companion object {
        // Android owns onboarding recommendations; catalog updates never choose lists for users.
        private val RECOMMENDED_SOURCE_IDS = setOf("blocklistproject-basic", "stevenblack-unified")
        const val ASSET_PATH = "catalog/blocklist-catalog.json"
        private const val HISTORY_INDEX_PATH = "catalog/history/index.json"
        const val PINNED_BLOB = "587c34de8ea62e3e0d48bd9932624ff59b37aa00"
        const val PINNED_SHA256 = "73f9e145092a71f869df90b0b758949b3fa8453fe22d4b942503c8d8494d74c6"

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        private fun sourceCategory(raw: String): FilterSourceCategory = when (raw) {
            "security" -> FilterSourceCategory.SECURITY
            "multi_purpose" -> FilterSourceCategory.MULTI_PURPOSE
            "ads_tracking" -> FilterSourceCategory.ADS_TRACKING
            "social" -> FilterSourceCategory.SOCIAL
            "nsfw" -> FilterSourceCategory.NSFW
            "gambling" -> FilterSourceCategory.GAMBLING
            "piracy" -> FilterSourceCategory.PIRACY
            else -> error("Unsupported catalog category: $raw")
        }
    }
}


/**
 * Explicit upgrade retirement, never a blanket purge of unrecognized user selections.
 * The canonical directory retired this historical Android-only entry. Preserve all other
 * choices and profile fields; the caller commits through the ordinary artifact transaction.
 */
internal fun FilterLibraryDraft.withRetiredCatalogSelectionsRemoved(): FilterLibraryDraft =
    withProfiles(profiles.map { profile ->
        profile.withEnabledSourceIds(profile.enabledSourceIds - RETIRED_CATALOG_SOURCE_IDS)
    })

/** One ordinary compile must remove both debug-only rules and retired catalog selections. */
internal fun FilterLibraryDraft.withReleaseCatalogUpgradeCleanup(): FilterLibraryDraft =
    withQaProbeSet(null).withRetiredCatalogSelectionsRemoved()

internal val RETIRED_CATALOG_SOURCE_IDS: Set<String> = setOf("blocklistproject-malware")
