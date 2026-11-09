package com.lavasec.app.data

import com.lavasec.app.foundation.catalog.CatalogCandidateRejection
import com.lavasec.app.foundation.catalog.CatalogProvenance
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.CustomBlocklistSourceAcquisitionException
import com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure
import com.lavasec.app.reactnative.customBlocklistSourceFailureWireCode
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the catalog acquisition/cache/fallback owner. No Robolectric and no
 * `org.json`: the owner is platform-free, so the aarch64 work host executes it. The transport is
 * a fake; the one network-touching fetcher test drives a loopback TLS `MockWebServer`.
 */
class CatalogSyncOwnerTest {
    @Test
    fun `actual custom parsing failure keeps source identity and restores the catalog cache`() = runBlocking {
        val catalogCache = tempCache()
        val baseline = owner(catalogCache, CatalogManifestFetcher { validCandidate() })
        assertEquals(CatalogRefreshStatus.UPDATED, baseline.refresh().status)
        val previousCatalogBytes = catalogCache.read()!!.bytes
        val sourceRoot = Files.createTempDirectory("catalog-custom-parse").toFile()
        try {
            val sourceCache = SeededSourceCache(sourceRoot)
            val source = SeededSourceDescriptor("custom-refresh", "https://lists.example.org/list.txt", BlocklistFormat.AUTO)
            val previous = sourceCache.acquireExact(source, SeededSourceTransport {
                SeededSourceHttpResponse.ok("old.example\n".encodeToByteArray())
            })
            val acquirer = CachedCatalogSourceAcquirer(sourceCache) {
                SeededSourceTransport { SeededSourceHttpResponse.ok("bad syntax / path\n".encodeToByteArray()) }
            }
            val refreshOwner = owner(catalogCache,
                CatalogManifestFetcher { validCandidate("catalog-3", "2026-10-01T12:00:00Z") },
                installer = CatalogInstaller {
                    try {
                        acquirer.acquire(listOf(source), refreshSources = true)
                        true
                    } catch (failure: CustomBlocklistSourceAcquisitionException) {
                        // The production filter installer resolves this label from the exact
                        // active profile; it does not classify or fabricate a parser outcome.
                        throw CustomBlocklistSourceAcquisitionException(failure.sourceId, failure.failure, "My List")
                    }
                })
            val result = refreshOwner.refresh()
            assertEquals(CatalogRefreshStatus.INSTALL_FAILED, result.status)
            val failed = requireNotNull(result.sourceFailure)
            assertEquals(source.id, failed.sourceId)
            assertEquals("My List", failed.sourceName)
            assertEquals(CustomBlocklistSourceFailure.PARSE_FAILED, failed.failure)
            assertEquals("custom-blocklist-parse-failed", customBlocklistSourceFailureWireCode(failed.failure))
            assertArrayEquals(previousCatalogBytes, catalogCache.read()!!.bytes)
            assertEquals(previous.acceptedSha256, SeededSourceCache(sourceRoot).exactOrNull(source)!!.acceptedSha256)
            val reopened = owner(catalogCache, CatalogManifestFetcher { error("must not fetch") })
            reopened.loadCachedInstalled()
            assertEquals("catalog-2", reopened.snapshot().catalogVersion)
        } finally { sourceRoot.deleteRecursively() }
    }

    @Test
    fun `missing custom bytes stop catalog retries and foreground source repair clears the block`() = runBlocking {
        val sourceRoot = Files.createTempDirectory("catalog-custom-repair").toFile()
        try {
            val cache = SeededSourceCache(sourceRoot)
            val source = SeededSourceDescriptor("custom-refresh", "https://lists.example.org/list.txt", BlocklistFormat.AUTO)
            val acquirer = CachedCatalogSourceAcquirer(cache) { error("Catalog repair must never fetch custom content") }
            val catalogCache = tempCache()
            val refreshOwner = owner(catalogCache, CatalogManifestFetcher { validCandidate() },
                installer = CatalogInstaller { acquirer.acquireCatalogRefresh(listOf(source)); true })
            val failed = refreshOwner.refresh()
            assertEquals(CatalogRefreshStatus.INSTALL_FAILED, failed.status)
            assertEquals(CustomBlocklistSourceFailure.STORAGE, failed.sourceFailure?.failure)
            assertEquals(source.id, failed.sourceFailure?.sourceId)
            assertNull(catalogCache.read())
            assertTrue(refreshOwner.snapshot().sourceRepairRequired)
            assertTrue(catalogRefreshWorkComplete(failed))
            assertFalse(shouldScheduleCatalogRepair(refreshOwner.snapshot()))

            // Explicit foreground acquisition restores custody of the user's source.
            cache.acquireExact(source, SeededSourceTransport {
                SeededSourceHttpResponse.ok("restored.example\n".encodeToByteArray())
            })
            assertEquals(CatalogRefreshStatus.UPDATED, refreshOwner.refresh().status)
            assertFalse(refreshOwner.snapshot().sourceRepairRequired)
            assertEquals(CatalogRefreshStatus.UPDATED, refreshOwner.snapshot().lastStatus)
            assertTrue(shouldScheduleCatalogRepair(refreshOwner.snapshot()))
        } finally { sourceRoot.deleteRecursively() }
    }

    @Test
    fun `a reconstructed graph waits for rejection before reading the installed catalog`() =
        assertGraphWaitsForCatalogInstall(accepted = false)

    @Test
    fun `a reconstructed graph reads the candidate only after installation commits`() =
        assertGraphWaitsForCatalogInstall(accepted = true)

    private fun assertGraphWaitsForCatalogInstall(accepted: Boolean) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = tempCache()
        val previous = validCandidate()
        val candidate = validCandidate("catalog-3", "2026-10-01T12:00:00Z")
        assertEquals(CatalogCacheWriteResult.WRITTEN, cache.write(previous, NOW, null))
        val compiling = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val sync = owner(cache, CatalogManifestFetcher { candidate }, ioDispatcher = dispatcher,
            installer = CatalogInstaller { compiling.complete(Unit); finish.await(); accepted })
        val refresh = async { sync.refresh() }
        compiling.await()
        val successor = async { CatalogInstallationGate.withStableCatalog { cache.read() } }
        try {
            runCurrent()
            assertFalse("A new credential graph must not consume an uncommitted candidate", successor.isCompleted)
            finish.complete(Unit)
            assertEquals(if (accepted) CatalogRefreshStatus.UPDATED else CatalogRefreshStatus.INSTALL_FAILED,
                refresh.await().status)
            assertArrayEquals(if (accepted) candidate else previous, checkNotNull(successor.await()).bytes)
        } finally { finish.complete(Unit); refresh.join() }
    }

    @Test
    fun `cancelled installation restores the old catalog before graph reconstruction resumes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = tempCache()
        val previous = validCandidate()
        assertEquals(CatalogCacheWriteResult.WRITTEN, cache.write(previous, NOW, null))
        val compiling = CompletableDeferred<Unit>()
        val sync = owner(cache, CatalogManifestFetcher { validCandidate("catalog-3", "2026-10-01T12:00:00Z") },
            ioDispatcher = dispatcher, installer = CatalogInstaller { compiling.complete(Unit); awaitCancellation() })
        val refresh = async { sync.refresh() }
        compiling.await()
        val successor = async { CatalogInstallationGate.withStableCatalog { cache.read() } }
        try {
            runCurrent()
            assertFalse("A successor must suspend while the retired installer is still compiling", successor.isCompleted)
            refresh.cancelAndJoin()
            assertArrayEquals(previous, checkNotNull(successor.await()).bytes)
        } finally { refresh.cancelAndJoin() }
    }

    @Test
    fun `a catalog install waits for a graph already constructing from the installed bytes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = tempCache()
        val previous = validCandidate()
        assertEquals(CatalogCacheWriteResult.WRITTEN, cache.write(previous, NOW, null))
        val constructing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val graph = async { CatalogInstallationGate.withStableCatalog {
            val captured = checkNotNull(cache.read())
            constructing.complete(Unit)
            finish.await()
            assertArrayEquals(captured.bytes, checkNotNull(cache.read()).bytes)
            captured
        } }
        constructing.await()
        val installing = CompletableDeferred<Unit>()
        val sync = owner(cache, CatalogManifestFetcher { validCandidate("catalog-3", "2026-10-01T12:00:00Z") },
            ioDispatcher = dispatcher, installer = CatalogInstaller { installing.complete(Unit); true })
        val refresh = async { sync.refresh() }
        try {
            runCurrent()
            assertFalse("Catalog publication cannot overtake graph construction", installing.isCompleted)
            assertArrayEquals(previous, checkNotNull(cache.read()).bytes)
            finish.complete(Unit)
            graph.await()
            assertEquals(CatalogRefreshStatus.UPDATED, refresh.await().status)
        } finally { finish.complete(Unit) }
    }

    private companion object {
        val BUNDLED_SHA = "a".repeat(64)
        val HASH = "b".repeat(64)
        val NOW = Instant.parse("2026-10-02T00:00:00Z").epochSecond
        val CANDIDATE_AT = "2026-10-01T00:00:00Z"
        val OLDER_AT = "2026-09-01T00:00:00Z"
        val FRESH_LATER = Instant.parse("2026-10-08T12:00:00Z").epochSecond
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

    private fun owner(
        cache: CatalogManifestCache,
        fetcher: CatalogManifestFetcher,
        now: () -> Long = { NOW },
        urls: List<String> = listOf("https://api.lavasecurity.app/v1/catalog"),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        installer: CatalogInstaller? = null,
    ): CatalogSyncOwner = CatalogSyncOwner(
        bundled = CatalogBundledArtifact.from(bundledCatalog()),
        cache = cache,
        fetcher = fetcher,
        catalogUrls = urls,
        nowEpochSeconds = now,
        ioDispatcher = ioDispatcher,
        installer = installer,
    )

    private fun tempCache(): CatalogManifestCache =
        CatalogManifestCache(Files.createTempDirectory("catalog-sync").toFile())

    private fun sourceJson(id: String, defaultEnabled: Boolean = false, category: String = "security"): String =
        """{"id":"$id","name":"List $id","category":"$category","risk_level":"normal",""" +
            """"default_enabled":$defaultEnabled,"license_name":"Unlicense","attribution":"Provider",""" +
            """"project_url":"https://example.org/$id","source_url":"https://example.org/$id.txt",""" +
            """"version_id":"$id-v1","entry_count":10,"byte_size":100,"source_hash":"$HASH",""" +
            """"accepted_source_hashes":[{"sha256":"$HASH","byte_size":100,"entry_count":10,"status":"accepted"}],""" +
            """"normalized_hash":"$HASH","published_at":"2026-08-01T00:00:00Z",""" +
            """"redistribution_mode":"source_url_only","parse_format":"hosts",""" +
            """"license_text_url":null,"notice_url":null}"""

    private fun manifestJson(
        version: String,
        generatedAt: String,
        sources: List<String>,
        guardrails: List<String> = emptyList(),
    ): ByteArray = (
        """{"schema_version":2,"catalog_version":"$version","generated_at":"$generatedAt",""" +
            """"sources":[${sources.joinToString(",")}],"guardrails":[${guardrails.joinToString(",")}]}"""
        ).encodeToByteArray()

    private fun validCandidate(version: String = "catalog-2", at: String = CANDIDATE_AT): ByteArray =
        manifestJson(version, at, listOf(sourceJson("approved-on"), sourceJson("brand-new")))

    @Test
    fun `a newer valid remote catalog is selected, persisted, and reported`() = runTest {
        val cache = tempCache()
        val bytes = validCandidate()
        val owner = owner(cache, CatalogManifestFetcher { bytes })

        val result = owner.refresh()

        assertEquals(CatalogRefreshStatus.UPDATED, result.status)
        assertNull(result.rejection)
        val snapshot = owner.snapshot()
        assertEquals(CatalogProvenance.REMOTE, snapshot.provenance)
        assertEquals("catalog-2", snapshot.catalogVersion)
        assertTrue(snapshot.fresh)
        assertEquals(0L, snapshot.ageSeconds)
        // The last-known-good survives a fresh owner over the same cache (restart).
        val reloaded = owner(cache, CatalogManifestFetcher { error("must not fetch") })
        reloaded.loadCachedInstalled()
        assertEquals(CatalogProvenance.LAST_KNOWN_GOOD, reloaded.snapshot().provenance)
        assertEquals("catalog-2", reloaded.snapshot().catalogVersion)
    }

    @Test
    fun `an identical candidate keeps the installed artifact unchanged`() = runTest {
        val cache = tempCache()
        val bytes = validCandidate()
        val owner = owner(cache, CatalogManifestFetcher { bytes })
        assertEquals(CatalogRefreshStatus.UPDATED, owner.refresh().status)

        val second = owner.refresh()
        assertEquals(CatalogRefreshStatus.UNCHANGED, second.status)
        assertEquals(CatalogProvenance.REMOTE, owner.snapshot().provenance)
    }

    @Test fun `identical verification renews stale freshness durably without reinstalling`() = runTest {
        val cache = tempCache(); val bytes = validCandidate(); var now = NOW; var installs = 0
        val subject = owner(cache, CatalogManifestFetcher { bytes }, now = { now },
            installer = CatalogInstaller { installs++; true })
        assertEquals(CatalogRefreshStatus.UPDATED, subject.refresh().status)
        now += 8 * 24 * 3600
        assertFalse(subject.snapshot().fresh)
        assertEquals(CatalogRefreshStatus.UNCHANGED, subject.refresh().status)
        assertTrue(subject.snapshot().fresh); assertEquals(0L, subject.snapshot().ageSeconds)
        assertEquals(1, installs); assertArrayEquals(bytes, cache.read()!!.bytes)
        val reloaded = owner(cache, CatalogManifestFetcher { error("must not fetch") }, now = { now })
        reloaded.loadCachedInstalled(); assertTrue(reloaded.snapshot().fresh)
    }

    @Test fun `same-date changed bytes and failed persistence cannot renew freshness`() = runTest {
        for (failure in listOf("changed-bytes", "storage")) {
            val cache = tempCache(); val bytes = validCandidate(); var now = NOW; var response = bytes
            val subject = owner(cache, CatalogManifestFetcher { response }, now = { now })
            assertEquals(CatalogRefreshStatus.UPDATED, subject.refresh().status)
            now += 8 * 24 * 3600
            if (failure == "changed-bytes") response = validCandidate("same-date-different-content") else cache.delete()
            assertEquals(if (failure == "storage") CatalogRefreshStatus.PERSIST_FAILED else CatalogRefreshStatus.KEPT_INSTALLED,
                subject.refresh().status)
            assertFalse(subject.snapshot().fresh)
            assertEquals("Only a transient storage failure should enqueue hourly repair",
                failure == "storage", shouldScheduleCatalogRepair(subject.snapshot()))
            if (failure == "changed-bytes") assertArrayEquals(bytes, cache.read()!!.bytes)
        }
    }

    @Test
    fun `a corrupt candidate falls back to the bundle and reports the parse failure`() = runTest {
        val owner = owner(tempCache(), CatalogManifestFetcher { "not a catalog".encodeToByteArray() })

        val result = owner.refresh()

        assertEquals(CatalogRefreshStatus.BUNDLED_FALLBACK, result.status)
        assertTrue(result.rejection is CatalogCandidateRejection.Invalid)
        assertEquals(CatalogProvenance.BUNDLED, owner.snapshot().provenance)
    }

    @Test
    fun `a corrupt candidate never discards a valid installed catalog`() = runTest {
        val cache = tempCache()
        val good = validCandidate()
        val goodOwner = owner(cache, CatalogManifestFetcher { good })
        assertEquals(CatalogRefreshStatus.UPDATED, goodOwner.refresh().status)

        val owner = owner(cache, CatalogManifestFetcher { "{ broken".encodeToByteArray() })
        val result = owner.refresh()

        assertEquals(CatalogRefreshStatus.KEPT_INSTALLED, result.status)
        assertTrue(result.rejection is CatalogCandidateRejection.Invalid)
        assertEquals(CatalogProvenance.LAST_KNOWN_GOOD, owner.snapshot().provenance)
        assertEquals("catalog-2", owner.snapshot().catalogVersion)
    }

    @Test
    fun `a stale rollback candidate is refused and the installed catalog is kept`() = runTest {
        val cache = tempCache()
        val first = owner(cache, CatalogManifestFetcher { validCandidate("catalog-2", CANDIDATE_AT) })
        assertEquals(CatalogRefreshStatus.UPDATED, first.refresh().status)

        val rollback = owner(
            cache,
            CatalogManifestFetcher { validCandidate("catalog-1", OLDER_AT) },
            now = { FRESH_LATER },
        )
        val result = rollback.refresh()

        assertEquals(CatalogRefreshStatus.KEPT_INSTALLED, result.status)
        assertEquals(CatalogCandidateRejection.StaleRollback, result.rejection)
        assertEquals("catalog-2", rollback.snapshot().catalogVersion)
    }

    @Test
    fun `every endpoint failing is a truthful offline result that keeps the installed catalog`() = runTest {
        val cache = tempCache()
        val good = owner(cache, CatalogManifestFetcher { validCandidate() })
        assertEquals(CatalogRefreshStatus.UPDATED, good.refresh().status)

        val offline = owner(
            cache,
            CatalogManifestFetcher {
                throw CatalogFetchException(CatalogFetchFailureReason.NETWORK_IO)
            },
            urls = listOf("https://one.example/v1/catalog", "https://two.example/v1/catalog"),
        )
        val result = offline.refresh()

        assertEquals(CatalogRefreshStatus.OFFLINE, result.status)
        assertEquals(CatalogFetchFailureReason.NETWORK_IO, result.fetchFailure)
        assertEquals("catalog-2", offline.snapshot().catalogVersion)
    }

    @Test
    fun `offline with no cache leaves the bundled catalog effective`() = runTest {
        val owner = owner(
            tempCache(),
            CatalogManifestFetcher { throw CatalogFetchException(CatalogFetchFailureReason.TIMEOUT) },
        )

        val result = owner.refresh()

        assertEquals(CatalogRefreshStatus.OFFLINE, result.status)
        assertEquals(CatalogFetchFailureReason.TIMEOUT, result.fetchFailure)
        assertEquals(CatalogProvenance.BUNDLED, owner.snapshot().provenance)
    }

    @Test
    fun `a fresh cache ages out of the freshness window`() = runTest {
        val cache = tempCache()
        val bytes = validCandidate()
        val owner = owner(cache, CatalogManifestFetcher { bytes })
        assertEquals(CatalogRefreshStatus.UPDATED, owner.refresh().status)
        assertTrue(owner.snapshot().fresh)

        val later = owner(cache, CatalogManifestFetcher { bytes }, now = { NOW + 6 * 24 * 3600 })
        later.loadCachedInstalled()
        // Six days later is still inside the one-week window; beyond it is not.
        assertTrue(later.snapshot().fresh)
        val expired = owner(cache, CatalogManifestFetcher { bytes }, now = { NOW + 8 * 24 * 3600 })
        expired.loadCachedInstalled()
        assertFalse(expired.snapshot().fresh)
        assertEquals(8 * 24 * 3600L, expired.snapshot().ageSeconds)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `concurrent refreshes coalesce onto a single fetch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fetchStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetchCount = AtomicInteger()
        val owner = owner(
            tempCache(),
            CatalogManifestFetcher {
                fetchCount.incrementAndGet()
                fetchStarted.complete(Unit)
                release.await()
                validCandidate()
            },
            ioDispatcher = dispatcher,
        )

        val first = async { owner.refresh() }
        // The owner's refresh has reached the fetch and is parked on the gate.
        runCurrent()
        assertTrue(fetchStarted.isCompleted)
        val second = async { owner.refresh() }
        // The second caller reaches the shared in-flight refresh instead of starting its own.
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        val firstResult = first.await()
        val secondResult = second.await()
        assertEquals(1, fetchCount.get())
        assertTrue(firstResult.coalesced != secondResult.coalesced)
        assertEquals(firstResult.status, secondResult.status)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling the first caller does not cancel a joined refresh`() = runTest {
        val release = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var fetches = 0
        val owner = owner(tempCache(), CatalogManifestFetcher {
            fetches++
            release.await()
            validCandidate()
        }, ioDispatcher = dispatcher)
        val worker = async { owner.refresh() }
        runCurrent()
        val foreground = async { owner.refresh() }
        runCurrent()
        worker.cancel()
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(worker.isCancelled)
        assertFalse("The joined foreground request must keep the shared refresh alive", foreground.isCancelled)
        assertEquals(CatalogRefreshStatus.UPDATED, foreground.await().status)
        assertTrue(foreground.await().coalesced)
        assertEquals(1, fetches)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `last caller cancellation retires shared work before a new refresh starts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var fetches = 0
        var retired = false
        val releaseCleanup = CompletableDeferred<Unit>()
        val owner = owner(tempCache(), CatalogManifestFetcher {
            if (++fetches == 1) {
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { releaseCleanup.await() }
                    retired = true
                }
            }
            assertTrue("The previous fetch must retire before a successor", retired)
            validCandidate()
        }, ioDispatcher = dispatcher)
        val worker = async { owner.refresh() }
        runCurrent()
        worker.cancel()
        runCurrent()
        val next = async { owner.refresh() }
        runCurrent()
        assertEquals("A successor cannot fetch while prior cleanup is suspended", 1, fetches)
        assertFalse(next.isCompleted)
        releaseCleanup.complete(Unit)
        advanceUntilIdle()
        worker.join()
        assertTrue(retired)
        assertEquals(CatalogRefreshStatus.UPDATED, next.await().status)
        assertEquals(2, fetches)
        assertFalse(owner.snapshot().refreshing)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling a joined caller leaves the original refresh alive`() = runTest {
        val release = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var fetches = 0
        val owner = owner(tempCache(), CatalogManifestFetcher {
            fetches++
            release.await()
            validCandidate()
        }, ioDispatcher = dispatcher)
        val foreground = async { owner.refresh() }
        runCurrent()
        val worker = async { owner.refresh() }
        runCurrent()
        worker.cancel()
        runCurrent()
        assertFalse(foreground.isCompleted)
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(worker.isCancelled)
        assertEquals(CatalogRefreshStatus.UPDATED, foreground.await().status)
        assertFalse(foreground.await().coalesced)
        assertEquals(1, fetches)
    }

    @Test
    fun `cancellation at durable unchanged renewal retains the fresh in memory timestamp`() = runTest {
        var cancelAfterWrite: Job? = null
        val cache = CatalogManifestCache(Files.createTempDirectory("catalog-renew-cancel").toFile(),
            afterDurableWrite = { cancelAfterWrite?.cancel() })
        val bytes = validCandidate()
        assertEquals(CatalogRefreshStatus.UPDATED, owner(cache, CatalogManifestFetcher { bytes }).refresh().status)
        val later = NOW + 8 * 24 * 3600
        val owner = owner(cache, CatalogManifestFetcher {
            cancelAfterWrite = currentCoroutineContext()[Job]
            bytes
        }, now = { later })
        owner.loadCachedInstalled()
        assertFalse(owner.snapshot().fresh)
        val attempt = async { owner.refresh() }
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertEquals(later, cache.read()!!.verifiedAtEpochSeconds)
        assertTrue("A durable renewed timestamp must also be adopted in memory", owner.snapshot().fresh)
        assertEquals(0L, owner.snapshot().ageSeconds)
        assertEquals(CatalogRefreshStatus.UNCHANGED, owner.snapshot().lastStatus)
    }

    @Test
    fun `a cache write fenced by a newer artifact is a conflict, not a rollback`() {
        val cache = tempCache()
        val older = validCandidate("catalog-2", CANDIDATE_AT)
        val newer = validCandidate("catalog-3", "2026-10-01T12:00:00Z")

        assertEquals(
            CatalogCacheWriteResult.WRITTEN,
            cache.write(older, NOW, expectedCurrentSha256 = null),
        )
        val olderSha = cache.read()!!.let { catalogSha256Hex(it.bytes) }
        // A second writer advances the cache; the stale writer's CAS must now fail.
        assertEquals(CatalogCacheWriteResult.WRITTEN, cache.write(newer, NOW, expectedCurrentSha256 = olderSha))
        assertEquals(
            CatalogCacheWriteResult.CONFLICT,
            cache.write(validCandidate("catalog-4"), NOW, expectedCurrentSha256 = olderSha),
        )
        assertEquals(
            "catalog-3",
            cache.read()!!.bytes.decodeToString().substringAfter("\"catalog_version\":\"").substringBefore('"'),
        )
    }

    @Test
    fun `a refresh whose cache slot was taken adopts the winning revision`() = runTest {
        val cache = tempCache()
        val winner = validCandidate("catalog-9", "2026-10-01T18:00:00Z")
        val owner = owner(
            cache,
            CatalogManifestFetcher {
                // Simulate a concurrent writer committing a newer artifact during the fetch.
                cache.write(winner, NOW, expectedCurrentSha256 = null)
                validCandidate("catalog-2", CANDIDATE_AT)
            },
        )

        val result = owner.refresh()

        assertEquals(CatalogRefreshStatus.SUPERSEDED, result.status)
        assertEquals("catalog-9", owner.snapshot().catalogVersion)
    }

    @Test
    fun `the bundled anchor carries the shipped ids and empty accepted hashes`() {
        val artifact = CatalogBundledArtifact.from(bundledCatalog())
        assertEquals(CatalogProvenance.BUNDLED, artifact.provenance)
        assertEquals("built-in-source-url-catalog-v1", artifact.catalogVersion)
        assertEquals(setOf("approved-on", "approved-off"), artifact.manifest.sources.map { it.id }.toSet())
        assertTrue(artifact.manifest.sources.all { it.sourceHash.isEmpty() && it.acceptedSourceHashes.isEmpty() })
    }

    @Test
    fun `production endpoints use the configured origin and only fall back from production`() {
        assertEquals(
            listOf(
                "https://api.lavasecurity.app/v1/catalog",
                "https://lavasec-api.lavasec.workers.dev/v1/catalog",
            ),
            CatalogSyncEndpoints.catalogUrls("https://api.lavasecurity.app/"),
        )
        assertEquals(
            listOf("https://staging.example.com/v1/catalog"),
            CatalogSyncEndpoints.catalogUrls("https://staging.example.com/"),
        )
        // A non-https or malformed override never silently becomes production.
        assertEquals(
            listOf("https://api.lavasecurity.app/v1/catalog", "https://lavasec-api.lavasec.workers.dev/v1/catalog"),
            CatalogSyncEndpoints.catalogUrls("http://insecure.example/"),
        )
    }

    @Test
    fun `a selected catalog is installed through the authority before it becomes installed`() = runTest {
        val cache = tempCache()
        val bytes = validCandidate()
        val installedVersions = mutableListOf<String>()
        val owner = owner(
            cache,
            CatalogManifestFetcher { bytes },
            installer = CatalogInstaller {
                installedVersions += it.catalogVersion
                true
            },
        )

        val result = owner.refresh()

        assertEquals(CatalogRefreshStatus.UPDATED, result.status)
        assertEquals(listOf("catalog-2"), installedVersions)
        assertEquals(CatalogProvenance.REMOTE, owner.snapshot().provenance)
        assertEquals(
            "catalog-2",
            cache.read()!!.bytes.decodeToString()
                .substringAfter("\"catalog_version\":\"").substringBefore('"'),
        )
    }

    @Test
    fun `a failed install keeps the resident catalog and rolls the cache back`() = runTest {
        val cache = tempCache()
        val baseline = owner(
            cache,
            CatalogManifestFetcher { validCandidate("catalog-2", CANDIDATE_AT) },
        )
        assertEquals(CatalogRefreshStatus.UPDATED, baseline.refresh().status)
        val baselineBytes = cache.read()!!.bytes

        val newer = owner(
            cache,
            CatalogManifestFetcher { validCandidate("catalog-3", "2026-10-01T12:00:00Z") },
            installer = CatalogInstaller { false },
        )
        val result = newer.refresh()

        assertEquals(CatalogRefreshStatus.INSTALL_FAILED, result.status)
        // The durable last-known-good is exactly the pre-refresh artifact...
        assertArrayEquals(baselineBytes, cache.read()!!.bytes)
        // ...and a fresh owner resolves that artifact, not the uninstalled candidate.
        val reloaded = owner(cache, CatalogManifestFetcher { error("must not fetch") })
        reloaded.loadCachedInstalled()
        assertEquals("catalog-2", reloaded.snapshot().catalogVersion)
    }

    @Test
    fun `a failed install with no prior cache deletes the uninstalled selection`() = runTest {
        val cache = tempCache()
        val owner = owner(
            cache,
            CatalogManifestFetcher { validCandidate("catalog-2", CANDIDATE_AT) },
            installer = CatalogInstaller { false },
        )

        assertEquals(CatalogRefreshStatus.INSTALL_FAILED, owner.refresh().status)
        assertNull(cache.read())
        assertEquals(CatalogProvenance.BUNDLED, owner.snapshot().provenance)
    }

    @Test
    fun `cancelled precommit installation restores the previous cache and permits another refresh`() = runTest {
        val cache = tempCache()
        val before = validCandidate()
        assertEquals(CatalogRefreshStatus.UPDATED, owner(cache, CatalogManifestFetcher { before }).refresh().status)
        var shouldCancel = true
        val after = validCandidate("catalog-3", "2026-10-01T12:00:00Z")
        val owner = owner(cache, CatalogManifestFetcher { after }, installer = CatalogInstaller {
            if (shouldCancel) {
                currentCoroutineContext().cancel()
                throw CancellationException("before publication")
            }
            true
        })
        val attempt = async { owner.refresh() }
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertArrayEquals(before, cache.read()!!.bytes)
        assertEquals("catalog-2", owner.snapshot().catalogVersion)
        shouldCancel = false
        assertEquals(CatalogRefreshStatus.UPDATED, owner.refresh().status)
        assertArrayEquals(after, cache.read()!!.bytes)
    }

    @Test
    fun `cancelled committed installation keeps exact durable cache and live catalog`() = runTest {
        val cache = tempCache()
        val bytes = validCandidate()
        val owner = owner(cache, CatalogManifestFetcher { bytes }, installer = CatalogInstaller {
            currentCoroutineContext().cancel()
            throw CatalogInstallCommittedCancellation(CancellationException("after publication"))
        })
        val attempt = async { owner.refresh() }
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertArrayEquals(bytes, cache.read()!!.bytes)
        assertEquals("catalog-2", owner.snapshot().catalogVersion)
        assertEquals(CatalogRefreshStatus.UPDATED, owner.snapshot().lastStatus)
        assertEquals(CatalogRefreshStatus.UNCHANGED, owner.refresh().status)
        val reopened = owner(cache, CatalogManifestFetcher { error("must not fetch") })
        reopened.loadCachedInstalled()
        assertEquals("catalog-2", reopened.snapshot().catalogVersion)
    }

    @Test
    fun `the okhttp fetcher returns bounded bytes over tls and refuses oversize or plaintext`() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .build()
            val fetcher = OkHttpCatalogManifestFetcher(client, maximumBytes = 8)

            server.enqueue(MockResponse().setBody("hi"))
            assertEquals("hi", runBlocking { fetcher.fetch(server.url("/v1/catalog").toString()) }.decodeToString())

            server.enqueue(MockResponse().setBody("0123456789"))
            val oversize = runBlocking {
                runCatching { fetcher.fetch(server.url("/v1/catalog").toString()) }
            }.exceptionOrNull()
            assertEquals(CatalogFetchFailureReason.BODY_TOO_LARGE, (oversize as CatalogFetchException).reason)

            server.enqueue(MockResponse().setResponseCode(503))
            val status = runBlocking {
                runCatching { fetcher.fetch(server.url("/v1/catalog").toString()) }
            }.exceptionOrNull()
            assertEquals(CatalogFetchFailureReason.HTTP_STATUS, (status as CatalogFetchException).reason)

            val plaintext = runBlocking {
                runCatching { fetcher.fetch("http://example.com/v1/catalog") }
            }.exceptionOrNull()
            assertEquals(CatalogFetchFailureReason.HTTPS_REQUIRED, (plaintext as CatalogFetchException).reason)
        } finally {
            server.shutdown()
        }
    }
}
