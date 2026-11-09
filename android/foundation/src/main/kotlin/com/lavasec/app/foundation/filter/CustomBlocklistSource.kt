package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.share.ShareableSourceUrlRejection
import com.lavasec.app.foundation.share.ShareableSourceUrlValidator
import java.net.URI

/**
 * A user-supplied blocklist source, the Android port of iOS `CustomBlocklistSource` (`c4658d9`).
 *
 * Unlike a curated catalog entry, a custom source has no signed catalog revision: it is identified
 * by its stable [id] (a `custom-<uuid>` token) and its URL, and its accepted contents are cached by
 * URL + format rather than by catalog identity.
 */
data class CustomBlocklistSource(
    val id: String,
    val displayName: String,
    val sourceUrl: String,
    val parseFormat: BlocklistFormat = BlocklistFormat.AUTO,
    val createdAtEpochMillis: Long = 0L,
    val lastAcceptedHash: String? = null,
) {
    init {
        require(id.isNotBlank()) { "custom blocklist id must not be blank" }
        require(displayName.isNotBlank()) { "custom blocklist name must not be blank" }
        require(sourceUrl.isNotBlank()) { "custom blocklist URL must not be blank" }
    }

    /**
     * The cache key derived from the URL, parse format and last accepted content hash, matching
     * iOS `CustomBlocklistSource.cacheIdentity`. The URL is the durable identity; two sources with
     * the same URL and format share a cached payload.
     */
    val cacheIdentity: String =
        listOf(sourceUrl, parseFormat.name, lastAcceptedHash ?: "").joinToString("|")

    companion object {
        /** The port of iOS `CustomBlocklistSource.init(id:displayName:rawURL:parseFormat:createdAt:)`. */
        const val MAX_CUSTOM_BLOCKLISTS_PER_PROFILE = 256

        /**
         * Creates a validated source, trimming the display name and falling back to the URL host.
         * Returns the precise [ShareableSourceUrlRejection] for an unacceptable URL, matching the
         * import validator so a URL refused on import is refused here identically.
         */
        fun create(
            displayName: String,
            rawUrl: String,
            parseFormat: BlocklistFormat = BlocklistFormat.AUTO,
            id: String? = null,
            createdAtEpochMillis: Long = 0L,
            lastAcceptedHash: String? = null,
        ): CustomBlocklistCreateResult {
            val rejection = ShareableSourceUrlValidator.validate(rawUrl)
            if (rejection != null) return CustomBlocklistCreateResult.Rejected(rejection)
            val normalizedUrl = rawUrl.trim()
            val host = runCatching { URI(normalizedUrl).host }.getOrNull().orEmpty()
            val trimmedName = displayName.trim()
            return CustomBlocklistCreateResult.Created(
                CustomBlocklistSource(
                    id = id ?: "custom-${java.util.UUID.randomUUID().toString().lowercase()}",
                    displayName = trimmedName.ifEmpty { host },
                    sourceUrl = normalizedUrl,
                    parseFormat = parseFormat,
                    createdAtEpochMillis = createdAtEpochMillis,
                    lastAcceptedHash = lastAcceptedHash,
                ),
            )
        }
    }
}

/** The result of validating and constructing a [CustomBlocklistSource]. */
sealed interface CustomBlocklistCreateResult {
    data class Created(val source: CustomBlocklistSource) : CustomBlocklistCreateResult

    data class Rejected(val reason: ShareableSourceUrlRejection) : CustomBlocklistCreateResult
}

/** Why adding/removing a custom source in a filter draft was refused. */
enum class CustomBlocklistRejection {
    INVALID_URL,
    UNSUPPORTED_SCHEME,
    MISSING_HOST,
    PRIVATE_NETWORK_HOST,
    CREDENTIALS_NOT_ALLOWED,
    REQUIRES_PLUS,
    DUPLICATE_URL,
    DUPLICATE_NAME,
    LIMIT_REACHED,
}

/**
 * The pure add/remove/undo rules for custom blocklists in one filter draft, mirroring iOS
 * `FilterDraftController.addCustomBlocklistToDraft`/`deleteCustomBlocklistFromDraft`/
 * `undoBlocklistDraftChange`.
 *
 * A custom source is enabled by construction: adding one inserts its id into
 * [FilterProfileDraft.enabledSourceIds], so the compile sees it without a second selection step.
 * A URL that matches a curated catalog source collapses to that source id (iOS
 * `KnownBlocklistURLMatcher`), dropping any duplicate custom record for the same URL.
 */
object CustomBlocklistEditor {
    fun add(
        profile: FilterProfileDraft,
        displayName: String,
        rawUrl: String,
        parseFormat: BlocklistFormat = BlocklistFormat.AUTO,
        limits: com.lavasec.app.foundation.subscription.FeatureLimits,
        id: String? = null,
        createdAtEpochMillis: Long = 0L,
        catalogSourceIdForUrl: (String) -> String? = { null },
    ): CustomBlocklistEditResult {
        if (!limits.allowsCustomBlocklists) {
            return CustomBlocklistEditResult.Rejected(CustomBlocklistRejection.REQUIRES_PLUS)
        }
        val created = CustomBlocklistSource.create(
            displayName = displayName,
            rawUrl = rawUrl,
            parseFormat = parseFormat,
            id = id,
            createdAtEpochMillis = createdAtEpochMillis,
        )
        val source = when (created) {
            is CustomBlocklistCreateResult.Rejected ->
                return CustomBlocklistEditResult.Rejected(created.reason.toEditorRejection())
            is CustomBlocklistCreateResult.Created -> created.source
        }

        val catalogSourceId = catalogSourceIdForUrl(source.sourceUrl)
        if (catalogSourceId != null) {
            val withoutDuplicateCustom = profile.customBlocklists.filterNot {
                it.sourceUrl == source.sourceUrl || catalogSourceIdForUrl(it.sourceUrl) == catalogSourceId
            }
            return CustomBlocklistEditResult.Applied(
                profile = profile.withCustomBlocklists(withoutDuplicateCustom)
                    .withEnabledSourceIds(profile.enabledSourceIds + catalogSourceId),
                source = source,
                collapsedToCatalogSourceId = catalogSourceId,
            )
        }

        if (profile.customBlocklists.any { it.sourceUrl == source.sourceUrl }) {
            return CustomBlocklistEditResult.Rejected(CustomBlocklistRejection.DUPLICATE_URL)
        }
        val displayKey = displayNameKey(source)
        if (profile.customBlocklists.any {
            it.sourceUrl != source.sourceUrl && displayNameKey(it) == displayKey
        }) {
            return CustomBlocklistEditResult.Rejected(CustomBlocklistRejection.DUPLICATE_NAME)
        }
        if (profile.customBlocklists.size >= CustomBlocklistSource.MAX_CUSTOM_BLOCKLISTS_PER_PROFILE) {
            return CustomBlocklistEditResult.Rejected(CustomBlocklistRejection.LIMIT_REACHED)
        }
        val updated = profile
            .withCustomBlocklists(profile.customBlocklists + source)
            .withEnabledSourceIds(profile.enabledSourceIds + source.id)
        return CustomBlocklistEditResult.Applied(updated, source, collapsedToCatalogSourceId = null)
    }

    /** Removes a source from the draft outright (iOS `deleteCustomBlocklistFromDraft`). */
    fun delete(profile: FilterProfileDraft, sourceId: String): FilterProfileDraft {
        if (profile.customBlocklists.none { it.id == sourceId }) return profile
        return profile
            .withCustomBlocklists(profile.customBlocklists.filterNot { it.id == sourceId })
            .withEnabledSourceIds(profile.enabledSourceIds - sourceId)
    }

    /**
     * Restores one source to the SAVED profile's membership/definition (iOS
     * `undoBlocklistDraftChange`). An id absent from [baseline] is simply disabled.
     */
    fun undo(
        profile: FilterProfileDraft,
        sourceId: String,
        baseline: FilterProfileDraft,
    ): FilterProfileDraft {
        val baselineSource = baseline.customBlocklists.firstOrNull { it.id == sourceId }
        val restored = when {
            baselineSource == null ->
                profile.withCustomBlocklists(profile.customBlocklists.filterNot { it.id == sourceId })
            profile.customBlocklists.any { it.id == sourceId } -> profile
            else -> profile.withCustomBlocklists(profile.customBlocklists + baselineSource)
        }
        return if (sourceId in baseline.enabledSourceIds) {
            restored.withEnabledSourceIds(restored.enabledSourceIds + sourceId)
        } else {
            restored.withEnabledSourceIds(restored.enabledSourceIds - sourceId)
        }
    }

    private fun displayNameKey(source: CustomBlocklistSource): String =
        source.displayName.trim().lowercase(java.util.Locale.ROOT)

    private fun ShareableSourceUrlRejection.toEditorRejection(): CustomBlocklistRejection = when (this) {
        ShareableSourceUrlRejection.INVALID_URL -> CustomBlocklistRejection.INVALID_URL
        ShareableSourceUrlRejection.UNSUPPORTED_SCHEME -> CustomBlocklistRejection.UNSUPPORTED_SCHEME
        ShareableSourceUrlRejection.MISSING_HOST -> CustomBlocklistRejection.MISSING_HOST
        ShareableSourceUrlRejection.PRIVATE_NETWORK_HOST -> CustomBlocklistRejection.PRIVATE_NETWORK_HOST
        ShareableSourceUrlRejection.CREDENTIALS_NOT_ALLOWED -> CustomBlocklistRejection.CREDENTIALS_NOT_ALLOWED
    }
}

/** The result of adding a custom blocklist to a filter draft. */
sealed interface CustomBlocklistEditResult {
    /**
     * The draft now carries (and enables) the source. [collapsedToCatalogSourceId] is non-null when
     * the URL matched a curated catalog source instead, in which case the custom record was not
     * added and that catalog source is enabled.
     */
    data class Applied(
        val profile: FilterProfileDraft,
        val source: CustomBlocklistSource,
        val collapsedToCatalogSourceId: String?,
    ) : CustomBlocklistEditResult

    data class Rejected(val reason: CustomBlocklistRejection) : CustomBlocklistEditResult
}
