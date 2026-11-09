package com.lavasec.app.data

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CustomBlocklistSource
import com.lavasec.app.foundation.filter.CustomBlocklistSourceAcquisitionException
import com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.platform.ConfigurationIdentity
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogFilterCompileRequestFactoryTest {
    @Test fun `catalog replacement keeps custom domains blocked using only their accepted cached payload`() = runBlocking {
        val root = createTempDirectory("catalog-custom-reconciliation-").toFile()
        try {
            val custom = CustomBlocklistSource("custom-kept", "My list", "https://custom.example/kept.txt",
                BlocklistFormat.PLAIN_DOMAINS)
            val disabled = CustomBlocklistSource("custom-disabled", "Disabled", "https://custom.example/disabled.txt",
                BlocklistFormat.PLAIN_DOMAINS)
            val cache = SeededSourceCache(File(root, "cache"))
            val descriptor = SeededSourceDescriptor(custom.id, custom.sourceUrl, custom.parseFormat)
            val retained = cache.acquireExact(descriptor, SeededSourceTransport {
                SeededSourceHttpResponse.ok("blocked-by-custom.example\n".encodeToByteArray())
            })
            val draft = FilterLibraryDraft.seededDefaults(CATALOG.filterSnapshot)
            val active = checkNotNull(draft.profile(draft.activeProfileId))
            val saved = draft.withProfile(active.withCustomBlocklists(listOf(custom, disabled))
                .withEnabledSourceIds(setOf("security", "vanished", custom.id)))
            val replacement = CATALOG.copy(revision = "replacement", sha256 = "b".repeat(64))
            val reconciled = reconcileLibraryForCatalog(saved, CatalogRuntimeCatalog.selectableSourceIds(replacement))
            val requests = mutableListOf<String>()
            val acquirer = CachedCatalogSourceAcquirer(cache) {
                SeededSourceTransport { url ->
                    requests += url
                    assertEquals(replacement.sources.single { it.id == "security" }.sourceUrl, url)
                    SeededSourceHttpResponse.ok("0.0.0.0 curated.example\n".encodeToByteArray())
                }
            }
            val factory = CatalogFilterCompileRequestFactory(replacement, CatalogSourceAcquirer { sources, refresh ->
                assertTrue(refresh)
                acquirer.acquireCatalogRefresh(sources)
            })
            val request = factory.create(reconciled, IDENTITY, refreshSources = true)

            com.lavasec.app.foundation.filter.FilterCompiler(File(root, "compiler")).compile(request).use { prepared ->
                (com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier.verify(prepared.file, prepared.compilationReceipt)
                    as com.lavasec.app.platform.CredentialFilterArtifactStagingResult.Verified).use { verified ->
                    assertEquals(
                        com.lavasec.app.foundation.filter.FilterDecision(com.lavasec.app.foundation.filter.FilterAction.BLOCK,
                            com.lavasec.app.foundation.filter.FilterDecisionReason.BLOCKLIST),
                        verified.artifact.snapshot.decide("blocked-by-custom.example"),
                    )
                }
            }
            assertEquals(listOf("security", custom.id), request.selectedSources.map { it.id })
            assertEquals(retained.acceptedSha256, request.selectedSources.last().acceptedSha256)
            assertEquals(listOf(replacement.sources.single { it.id == "security" }.sourceUrl), requests)
            assertEquals(listOf(custom, disabled), reconciled.profile(active.id)!!.customBlocklists)
        } finally { root.deleteRecursively() }
    }

    @Test fun `catalog maintenance refreshes curated bytes but keeps exact custom custody without network`() = runBlocking {
        val root = createTempDirectory("catalog-maintenance").toFile()
        try {
            val cache = SeededSourceCache(root)
            val custom = SeededSourceDescriptor("custom", "https://custom.example/list.txt", BlocklistFormat.PLAIN_DOMAINS)
            val curated = CATALOG.sources.first().descriptor(CATALOG)
            val retained = cache.acquireExact(custom, SeededSourceTransport { SeededSourceHttpResponse.ok("custom.example\n".encodeToByteArray()) })
            val requests = mutableListOf<String>()
            val acquirer = CachedCatalogSourceAcquirer(cache) { SeededSourceTransport { url ->
                requests += url; SeededSourceHttpResponse.ok("0.0.0.0 curated.example\n".encodeToByteArray())
            } }
            val accepted = acquirer.acquireCatalogRefresh(listOf(custom, curated))
            assertEquals(listOf(curated.sourceUrl), requests)
            assertEquals(listOf(custom.id, curated.id), accepted.map { it.descriptor.id })
            assertEquals(retained.acceptedSha256, accepted.first().acceptedSha256)
        } finally { root.deleteRecursively() }
    }

    @Test fun `missing custom cache refuses before any catalog-maintenance source fetch`() = runBlocking {
        val root = createTempDirectory("catalog-maintenance-missing").toFile()
        try {
            val custom = SeededSourceDescriptor("custom", "https://custom.example/list.txt", BlocklistFormat.PLAIN_DOMAINS)
            val acquirer = CachedCatalogSourceAcquirer(SeededSourceCache(root)) { error("No network may repair custom custody") }
            val failure = runCatching { acquirer.acquireCatalogRefresh(listOf(CATALOG.sources.first().descriptor(CATALOG), custom)) }.exceptionOrNull()
            assertTrue(failure is CustomBlocklistSourceAcquisitionException)
            assertEquals(CustomBlocklistSourceFailure.STORAGE, (failure as CustomBlocklistSourceAcquisitionException).failure)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `native debug compilation adds exact protected QA rules while ordinary compilation refuses them`() = runTest {
        val probes = com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED
        val library = FilterLibraryDraft.seededDefaults(CATALOG.filterSnapshot)
        val ordinary = library.withProfile(library.profile(library.activeProfileId)!!.withEnabledSourceIds(emptySet()))
        val desired = ordinary.withQaProbeSet(probes)
        val source = CatalogSourceAcquirer { descriptors, _ -> assertTrue(descriptors.isEmpty()); emptyList() }
        val denied = runCatching { CatalogFilterCompileRequestFactory(CATALOG, source).create(desired, IDENTITY, false) }
        assertTrue(denied.exceptionOrNull() is IllegalArgumentException)
        val root = createTempDirectory("catalog-qa-overlay-").toFile()
        try {
            val request = CatalogFilterCompileRequestFactory(CATALOG, source, allowQaProbeSets = true).create(desired, IDENTITY, false)
            assertEquals(probes.allowRules, request.manualAllowRules)
            assertEquals(probes.blockRules, request.manualBlockRules)
            assertEquals(probes.guardrailRules, request.guardrailRules)
            assertFalse(request.intentionalEmpty)
            assertTrue(request.selectedSources.isEmpty())
            com.lavasec.app.foundation.filter.FilterCompiler(root).compile(request).use { prepared ->
                (com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier.verify(prepared.file, prepared.compilationReceipt)
                    as com.lavasec.app.platform.CredentialFilterArtifactStagingResult.Verified).use { verified ->
                    assertEquals(com.lavasec.app.foundation.filter.FilterDecision.DEFAULT_ALLOW, verified.artifact.snapshot.decide(probes.allowedDomain))
                    assertEquals(com.lavasec.app.foundation.filter.FilterDecision(com.lavasec.app.foundation.filter.FilterAction.BLOCK, com.lavasec.app.foundation.filter.FilterDecisionReason.BLOCKLIST), verified.artifact.snapshot.decide(probes.blockedDomain))
                    assertEquals(com.lavasec.app.foundation.filter.FilterDecision(com.lavasec.app.foundation.filter.FilterAction.ALLOW, com.lavasec.app.foundation.filter.FilterDecisionReason.LOCAL_ALLOWLIST), verified.artifact.snapshot.decide(probes.exceptionDomain))
                    assertEquals(com.lavasec.app.foundation.filter.FilterDecision(com.lavasec.app.foundation.filter.FilterAction.BLOCK, com.lavasec.app.foundation.filter.FilterDecisionReason.THREAT_GUARDRAIL), verified.artifact.snapshot.decide(probes.guardrailDomain))
                    assertEquals(com.lavasec.app.foundation.filter.FilterDecision.DEFAULT_ALLOW, verified.artifact.snapshot.decide("sub.${probes.guardrailDomain}"))
                }
            }
            assertEquals(ordinary, desired.withQaProbeSet(null))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `release upgrade can remove QA probes and retired selections in one ordinary compile`() = runTest {
        val probes = com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED
        val seeded = FilterLibraryDraft.seededDefaults(CATALOG.filterSnapshot)
        val retained = seeded.withProfiles(seeded.profiles.map {
            it.withEnabledSourceIds(setOf("blocklistproject-malware"))
        }).withQaProbeSet(probes)
        val source = CatalogSourceAcquirer { descriptors, _ -> assertTrue(descriptors.isEmpty()); emptyList() }
        val factory = CatalogFilterCompileRequestFactory(CATALOG, source)
        // Removing only QA metadata still leaves a retired ID which the current catalog refuses.
        assertTrue(runCatching { factory.create(retained.withQaProbeSet(null), IDENTITY, false) }
            .exceptionOrNull() is IllegalArgumentException)
        val ordinary = retained.withReleaseCatalogUpgradeCleanup()
        assertEquals(seeded.activeProfileId, ordinary.activeProfileId)
        assertTrue(ordinary.profiles.all { it.enabledSourceIds.isEmpty() })
        val request = factory.create(ordinary, IDENTITY, false)
        assertTrue(request.intentionalEmpty)
        assertTrue(request.guardrailRules.isEmpty())
        val root = createTempDirectory("release-catalog-cleanup-").toFile()
        try {
            com.lavasec.app.foundation.filter.FilterCompiler(root).compile(request).use { prepared ->
                (com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier.verify(prepared.file, prepared.compilationReceipt)
                    as com.lavasec.app.platform.CredentialFilterArtifactStagingResult.Verified).use { verified ->
                    assertEquals(com.lavasec.app.foundation.filter.FilterDecision.DEFAULT_ALLOW,
                        verified.artifact.snapshot.decide(probes.guardrailDomain))
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `active signed sources compile in catalog order with exact manual rules`() = runTest {
        withFixture { fixture ->
            val active = fixture.draft.profile(fixture.draft.activeProfileId)!!
            val desired = fixture.draft.withProfile(
                active
                    .withEnabledSourceIds(linkedSetOf("ads", "security"))
                    .withManualRules(
                        ManualDomainRules(
                            allowedDomains = setOf("allowed.example"),
                            blockedDomains = setOf("blocked.example"),
                        ),
                    ),
            )

            val request = fixture.factory.create(desired, IDENTITY, refreshSources = false)

            assertEquals(ArtifactConfigurationIdentity(2, "f".repeat(64)), request.configurationIdentity)
            assertEquals(listOf("security", "ads"), request.selectedSources.map { it.id })
            assertEquals(listOf("security", "ads"), fixture.requestedIds)
            assertEquals(listOf(false), fixture.refreshRequests)
            assertEquals(listOf("allowed.example"), request.manualAllowRules.map { it.domain })
            assertEquals(listOf("blocked.example"), request.manualBlockRules.map { it.domain })
            assertTrue(request.guardrailRules.isEmpty())
            assertFalse(request.intentionalEmpty)
            assertEquals(CATALOG_REVISION, request.catalogRevision)
            assertEquals(CATALOG_SHA, request.catalogSha256)
        }
    }

    @Test
    fun `empty active profile is an explicit intentional empty compile`() = runTest {
        withFixture { fixture ->
            val active = fixture.draft.profile(fixture.draft.activeProfileId)!!
            val desired = fixture.draft.withProfile(active.withEnabledSourceIds(emptySet()))

            val request = fixture.factory.create(desired, IDENTITY, refreshSources = false)

            assertTrue(request.selectedSources.isEmpty())
            assertTrue(request.intentionalEmpty)
            assertTrue(fixture.requestedIds.isEmpty())
        }
    }

    @Test
    fun `unknown source is rejected before acquisition`() = runTest {
        withFixture { fixture ->
            val active = fixture.draft.profile(fixture.draft.activeProfileId)!!
            val desired = fixture.draft.withProfile(active.withEnabledSourceIds(setOf("unknown")))

            val failure = runCatching {
                fixture.factory.create(desired, IDENTITY, refreshSources = false)
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(fixture.requestedIds.isEmpty())
        }
    }

    @Test
    fun `forced source refresh bypasses complete exact cache while normal compile reuses it`() = runBlocking {
        val root = createTempDirectory("catalog-source-refresh-").toFile()
        try {
            val cache = SeededSourceCache(root)
            val draft = FilterLibraryDraft.seededDefaults(CATALOG.filterSnapshot)
            val active = requireNotNull(draft.profile(draft.activeProfileId))
            val descriptors = CATALOG.sources
                .filter { it.id in active.enabledSourceIds }
                .map { it.descriptor(CATALOG) }
            val oldBytes = descriptors.associate { descriptor ->
                descriptor.id to "0.0.0.0 old-${descriptor.id}.example\n".encodeToByteArray()
            }
            descriptors.forEach { descriptor ->
                cache.acquireExact(
                    descriptor,
                    RecordingTransport { SeededSourceHttpResponse.ok(oldBytes.getValue(descriptor.id)) },
                )
            }

            val requestedUrls = mutableListOf<String>()
            val refreshedBytes = descriptors.associate { descriptor ->
                descriptor.id to "0.0.0.0 refreshed-${descriptor.id}.example\n".encodeToByteArray()
            }
            val transport = RecordingTransport { url ->
                val descriptor = descriptors.single { it.sourceUrl == url }
                SeededSourceHttpResponse.ok(refreshedBytes.getValue(descriptor.id))
            }.also { recording -> recording.onRequest = { requestedUrls += it } }
            val factory = CatalogFilterCompileRequestFactory(
                catalog = CATALOG,
                sourceAcquirer = CachedCatalogSourceAcquirer(
                    cache = cache,
                    transportFactory = { transport },
                ),
            )

            val normal = factory.create(draft, IDENTITY, refreshSources = false)

            assertTrue(requestedUrls.isEmpty())
            assertEquals(
                descriptors.map { sha256(oldBytes.getValue(it.id)) },
                normal.selectedSources.map { it.acceptedSha256 },
            )

            val refreshed = factory.create(draft, IDENTITY, refreshSources = true)

            assertEquals(descriptors.map { it.sourceUrl }, requestedUrls)
            assertEquals(
                descriptors.map { sha256(refreshedBytes.getValue(it.id)) },
                refreshed.selectedSources.map { it.acceptedSha256 },
            )
            assertEquals(
                refreshed.selectedSources.map { it.acceptedSha256 },
                descriptors.map { cache.exactOrNull(it)!!.acceptedSha256 },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an enabled custom blocklist compiles alongside curated sources`() = runTest {
        val root = createTempDirectory("catalog-custom-").toFile()
        try {
            val draft = FilterLibraryDraft.seededDefaults(CATALOG.filterSnapshot)
            val active = draft.profile(draft.activeProfileId)!!
            val custom = com.lavasec.app.foundation.filter.CustomBlocklistSource(
                id = "custom-a",
                displayName = "Custom",
                sourceUrl = "https://mine.example/hosts.txt",
                parseFormat = BlocklistFormat.HOSTS,
            )
            val desired = draft.withProfile(
                active
                    .withCustomBlocklists(listOf(custom))
                    .withEnabledSourceIds(setOf("security", "custom-a")),
            )
            val factory = CatalogFilterCompileRequestFactory(
                catalog = CATALOG,
                sourceAcquirer = CatalogSourceAcquirer { descriptors, _ ->
                    descriptors.map { descriptor ->
                        val bytes = "0.0.0.0 ${descriptor.id}.example\n".encodeToByteArray()
                        val file = File(root, "${descriptor.id}.txt").apply { writeBytes(bytes) }
                        AcceptedSeededSource(descriptor, sha256(bytes), bytes.size.toLong(), file)
                    }
                },
            )

            val request = factory.create(desired, IDENTITY, refreshSources = false)

            assertEquals(listOf("security", "custom-a"), request.selectedSources.map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a custom source fetch failure is classified for the owner`() = runTest {
        val root = createTempDirectory("catalog-custom-failure-").toFile()
        try {
            val custom = CustomBlocklistSource(
                id = "custom-a",
                displayName = "Custom",
                sourceUrl = "https://mine.example/hosts.txt",
                parseFormat = BlocklistFormat.HOSTS,
            )
            val draft = FilterLibraryDraft.seededDefaults(CATALOG.filterSnapshot)
            val active = draft.profile(draft.activeProfileId)!!
            val desired = draft.withProfile(
                active
                    .withCustomBlocklists(listOf(custom))
                    .withEnabledSourceIds(setOf("custom-a")),
            )
            val factory = CatalogFilterCompileRequestFactory(
                catalog = CATALOG,
                sourceAcquirer = CachedCatalogSourceAcquirer(SeededSourceCache(root)) {
                    RecordingTransport {
                        SeededSourceHttpResponse(503, null, ByteArrayInputStream(byteArrayOf()))
                    }
                },
            )

            val failure = runCatching {
                factory.create(desired, IDENTITY, refreshSources = false)
            }.exceptionOrNull()

            assertTrue("expected classified custom failure", failure is CustomBlocklistSourceAcquisitionException)
            failure as CustomBlocklistSourceAcquisitionException
            assertEquals("custom-a", failure.sourceId)
            assertEquals(CustomBlocklistSourceFailure.HTTP_STATUS, failure.failure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a curated source fetch failure keeps its staging exception`() = runTest {
        val root = createTempDirectory("catalog-curated-failure-").toFile()
        try {
            val draft = FilterLibraryDraft.seededDefaults(CATALOG.filterSnapshot)
            val active = draft.profile(draft.activeProfileId)!!
            val desired = draft.withProfile(active.withEnabledSourceIds(setOf("security")))
            val factory = CatalogFilterCompileRequestFactory(
                catalog = CATALOG,
                sourceAcquirer = CachedCatalogSourceAcquirer(SeededSourceCache(root)) {
                    RecordingTransport {
                        SeededSourceHttpResponse(503, null, ByteArrayInputStream(byteArrayOf()))
                    }
                },
            )

            val failure = runCatching {
                factory.create(desired, IDENTITY, refreshSources = false)
            }.exceptionOrNull()

            assertTrue("expected raw staging failure", failure is SeededSourceCacheException)
            assertEquals(SeededSourceCacheFailure.HTTP_STATUS, (failure as SeededSourceCacheException).reason)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `every cache failure maps to a stable custom failure class`() {
        assertEquals(CustomBlocklistSourceFailure.INSECURE, SeededSourceCacheFailure.HTTPS_REQUIRED.toCustomSourceFailure())
        assertEquals(CustomBlocklistSourceFailure.REDIRECT, SeededSourceCacheFailure.REDIRECT_LOCATION_MISSING.toCustomSourceFailure())
        assertEquals(CustomBlocklistSourceFailure.REDIRECT, SeededSourceCacheFailure.REDIRECT_LIMIT.toCustomSourceFailure())
        assertEquals(CustomBlocklistSourceFailure.REDIRECT, SeededSourceCacheFailure.REDIRECT_HOST_MISMATCH.toCustomSourceFailure())
        assertEquals(CustomBlocklistSourceFailure.HTTP_STATUS, SeededSourceCacheFailure.HTTP_STATUS.toCustomSourceFailure())
        assertEquals(CustomBlocklistSourceFailure.EMPTY_BODY, SeededSourceCacheFailure.EMPTY_BODY.toCustomSourceFailure())
        assertEquals(CustomBlocklistSourceFailure.TOO_LARGE, SeededSourceCacheFailure.BODY_TOO_LARGE.toCustomSourceFailure())
        assertEquals(CustomBlocklistSourceFailure.TIMEOUT, SeededSourceCacheFailure.TIMEOUT.toCustomSourceFailure())
        assertEquals(CustomBlocklistSourceFailure.NETWORK, SeededSourceCacheFailure.NETWORK_IO.toCustomSourceFailure())
        assertEquals(CustomBlocklistSourceFailure.STORAGE, SeededSourceCacheFailure.STORAGE_IO.toCustomSourceFailure())
    }

    private suspend fun withFixture(test: suspend (Fixture) -> Unit) {
        val root = createTempDirectory("catalog-compile-factory-").toFile()
        try {
            val accepted = CATALOG.sources.associate { metadata ->
                val bytes = "0.0.0.0 ${metadata.id}.example\n".encodeToByteArray()
                val file = File(root, "${metadata.id}.txt").apply { writeBytes(bytes) }
                metadata.id to AcceptedSeededSource(
                    descriptor = metadata.descriptor(CATALOG),
                    acceptedSha256 = sha256(bytes),
                    acceptedByteSize = bytes.size.toLong(),
                    file = file,
                )
            }
            val requested = mutableListOf<String>()
            val refreshRequests = mutableListOf<Boolean>()
            val factory = CatalogFilterCompileRequestFactory(
                catalog = CATALOG,
                sourceAcquirer = CatalogSourceAcquirer { descriptors, refreshSources ->
                    requested += descriptors.map { it.id }
                    refreshRequests += refreshSources
                    descriptors.map { accepted.getValue(it.id) }
                },
                maxRules = 123_456,
            )
            test(
                Fixture(
                    draft = FilterLibraryDraft.seededDefaults(CATALOG.filterSnapshot),
                    factory = factory,
                    requestedIds = requested,
                    refreshRequests = refreshRequests,
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val draft: FilterLibraryDraft,
        val factory: CatalogFilterCompileRequestFactory,
        val requestedIds: MutableList<String>,
        val refreshRequests: MutableList<Boolean>,
    )

    private class RecordingTransport(
        private val response: suspend (String) -> SeededSourceHttpResponse,
    ) : SeededSourceTransport {
        var onRequest: (String) -> Unit = {}

        override suspend fun execute(url: String): SeededSourceHttpResponse {
            onRequest(url)
            return response(url)
        }
    }

    private companion object {
        const val CATALOG_REVISION = "catalog-revision"
        val CATALOG_SHA = "c".repeat(64)
        val IDENTITY = ConfigurationIdentity(2, "f".repeat(64))
        val CATALOG = BundledBlocklistCatalog(
            schemaVersion = 1,
            categories = listOf("security", "multi_purpose", "ads_tracking"),
            sources = listOf(
                source("security", FilterSourceCategory.SECURITY, defaultEnabled = true),
                source("multi", FilterSourceCategory.MULTI_PURPOSE, defaultEnabled = true),
                source("ads", FilterSourceCategory.ADS_TRACKING, defaultEnabled = false),
            ),
            revision = CATALOG_REVISION,
            sha256 = CATALOG_SHA,
            assetText = "{}",
        )

        fun source(
            id: String,
            category: FilterSourceCategory,
            defaultEnabled: Boolean,
        ) = CatalogSourceMetadata(
            id = id,
            name = id,
            category = category,
            format = CatalogSourceFormat.HOSTS,
            blocklistFormat = BlocklistFormat.HOSTS,
            sourceUrl = "https://example.test/$id.txt",
            defaultEnabled = defaultEnabled,
        )

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
