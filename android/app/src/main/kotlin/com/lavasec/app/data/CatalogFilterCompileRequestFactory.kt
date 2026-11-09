package com.lavasec.app.data

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.CustomBlocklistSourceAcquisitionException
import com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure
import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.platform.ConfigurationIdentity

fun interface CatalogSourceAcquirer {
    suspend fun acquire(
        sources: List<SeededSourceDescriptor>,
        refreshSources: Boolean,
    ): List<AcceptedSeededSource>
}

/** Builds one exact compiler request from the pinned catalog and active filter draft. */
internal class CatalogFilterCompileRequestFactory(
    private val catalog: BundledBlocklistCatalog,
    private val sourceAcquirer: CatalogSourceAcquirer,
    private val maxRules: Int = DEFAULT_MAX_RULES,
    private val allowQaProbeSets: Boolean = false,
) {
    init {
        require(maxRules > 0)
        require(catalog.sources.map(CatalogSourceMetadata::id).distinct().size == catalog.sources.size)
    }

    suspend fun create(
        configuration: FilterLibraryDraft,
        identity: ConfigurationIdentity,
        refreshSources: Boolean,
    ): FilterCompileRequest {
        val probes = configuration.qaProbeSet
        require(probes == null || allowQaProbeSets) { "QA probes require native debug authority" }
        val active = requireNotNull(configuration.profile(configuration.activeProfileId))
        val knownIds = catalog.sources.mapTo(linkedSetOf(), CatalogSourceMetadata::id)
        val customIds = active.customBlocklists.mapTo(linkedSetOf()) { it.id }
        require(active.enabledSourceIds.all { it in knownIds || it in customIds }) {
            "active profile contains an unknown source"
        }
        require(active.guardrailSourceIds.isEmpty()) { "guardrail sources are not available" }

        val metadata = catalog.sources.filter { it.id in active.enabledSourceIds }
        val custom = active.customBlocklists.filter { it.id in active.enabledSourceIds }
        val descriptors = metadata.map { it.descriptor(catalog) } + custom.map { source ->
            SeededSourceDescriptor(
                id = source.id,
                sourceUrl = source.sourceUrl,
                format = source.parseFormat,
            )
        }
        val accepted = sourceAcquirer.acquire(descriptors, refreshSources)
        require(accepted.size == descriptors.size)
        require(accepted.map(AcceptedSeededSource::descriptor) == descriptors) {
            "source acquisition must return exact catalog order"
        }
        val manualAllow = active.manualRules.allowedDomains
            .sorted()
            .map { DomainRule.parse(it, matchesSubdomains = true) }
            .plus(probes?.allowRules.orEmpty())
        val manualBlock = active.manualRules.blockedDomains
            .sorted()
            .map { DomainRule.parse(it, matchesSubdomains = true) }
            .plus(probes?.blockRules.orEmpty())
        return FilterCompileRequest(
            configurationIdentity = ArtifactConfigurationIdentity(
                identity.generation,
                identity.fingerprint,
            ),
            selectedSources = accepted.map(AcceptedSeededSource::asFilterPayload),
            catalogRevision = catalog.revision,
            catalogSha256 = catalog.sha256,
            maxRules = maxRules,
            intentionalEmpty = accepted.isEmpty() && manualAllow.isEmpty() && manualBlock.isEmpty(),
            manualAllowRules = manualAllow,
            manualBlockRules = manualBlock,
            guardrailRules = probes?.guardrailRules.orEmpty(),
        )
    }

    private companion object {
        const val DEFAULT_MAX_RULES = 500_000
    }
}

internal fun CatalogSourceMetadata.descriptor(
    catalog: BundledBlocklistCatalog,
): SeededSourceDescriptor = SeededSourceDescriptor(
    id = id,
    sourceUrl = sourceUrl,
    format = blocklistFormat,
    catalogRevision = catalog.revision,
    catalogSha256 = catalog.sha256,
)

/** Exact-cache-first source acquisition with an explicitly supplied network transport. */
internal class CachedCatalogSourceAcquirer(
    private val cache: SeededSourceCache,
    private val transportFactory: suspend () -> SeededSourceTransport,
) : CatalogSourceAcquirer {
    override suspend fun acquire(
        sources: List<SeededSourceDescriptor>,
        refreshSources: Boolean,
    ): List<AcceptedSeededSource> {
        val cached = if (refreshSources) {
            List<AcceptedSeededSource?>(sources.size) { null }
        } else {
            sources.map(cache::exactOrNull)
        }
        if (cached.all { it != null }) return cached.filterNotNull()

        val transport = transportFactory()
        return try {
            sources.mapIndexed { index, descriptor ->
                cached[index] ?: acquire(descriptor, transport, refreshSources)
            }
        } finally {
            (transport as? AutoCloseable)?.close()
        }
    }

    /** Catalog repair refreshes curated sources only. Resolve custom custody before any
     * network request; a missing custom cache cannot silently fetch changed user content. */
    suspend fun acquireCatalogRefresh(sources: List<SeededSourceDescriptor>): List<AcceptedSeededSource> {
        val custom = sources.filter { it.catalogSha256.isEmpty() }.associate { descriptor ->
            descriptor.id to (cache.exactOrNull(descriptor) ?: throw CustomBlocklistSourceAcquisitionException(
                descriptor.id, CustomBlocklistSourceFailure.STORAGE))
        }
        val curated = acquire(sources.filter { it.catalogSha256.isNotEmpty() }, true).associateBy { it.descriptor.id }
        return sources.map { custom[it.id] ?: checkNotNull(curated[it.id]) }
    }

    private suspend fun acquire(
        descriptor: SeededSourceDescriptor,
        transport: SeededSourceTransport,
        refreshSources: Boolean,
    ): AcceptedSeededSource = try {
        cache.acquireExact(descriptor, transport, refreshSource = refreshSources)
    } catch (failure: SeededSourceCacheException) {
        // A curated source keeps its existing generic staging failure; a custom source names
        // itself so the owner can surface a stable per-source rejection without a URL.
        if (descriptor.catalogSha256.isNotEmpty()) throw failure
        throw CustomBlocklistSourceAcquisitionException(
            descriptor.id,
            failure.reason.toCustomSourceFailure(),
        )
    }
}

internal fun SeededSourceCacheFailure.toCustomSourceFailure(): CustomBlocklistSourceFailure = when (this) {
    SeededSourceCacheFailure.HTTPS_REQUIRED -> CustomBlocklistSourceFailure.INSECURE
    SeededSourceCacheFailure.REDIRECT_LOCATION_MISSING,
    SeededSourceCacheFailure.REDIRECT_LIMIT,
    SeededSourceCacheFailure.REDIRECT_HOST_MISMATCH,
    -> CustomBlocklistSourceFailure.REDIRECT
    SeededSourceCacheFailure.HTTP_STATUS -> CustomBlocklistSourceFailure.HTTP_STATUS
    SeededSourceCacheFailure.EMPTY_BODY -> CustomBlocklistSourceFailure.EMPTY_BODY
    SeededSourceCacheFailure.BODY_TOO_LARGE -> CustomBlocklistSourceFailure.TOO_LARGE
    SeededSourceCacheFailure.TIMEOUT -> CustomBlocklistSourceFailure.TIMEOUT
    SeededSourceCacheFailure.NETWORK_IO -> CustomBlocklistSourceFailure.NETWORK
    SeededSourceCacheFailure.STORAGE_IO -> CustomBlocklistSourceFailure.STORAGE
    SeededSourceCacheFailure.PARSE_FAILED -> CustomBlocklistSourceFailure.PARSE_FAILED
}
