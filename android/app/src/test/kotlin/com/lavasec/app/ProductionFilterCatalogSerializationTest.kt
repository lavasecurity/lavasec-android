package com.lavasec.app

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.data.BundledBlocklistCatalog
import com.lavasec.app.data.CredentialConfigurationRepository
import com.lavasec.app.directboot.DeviceProtectedArtifactPublisher
import com.lavasec.app.foundation.catalog.CatalogArtifactLoader
import com.lavasec.app.foundation.catalog.CatalogProvenance
import com.lavasec.app.storage.CredentialFilterConfigurationCheckpoint
import com.lavasec.app.storage.CredentialFilterConfigurationPhase
import kotlinx.coroutines.currentCoroutineContext
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.filter.DeviceQaProbeSet
import com.lavasec.app.platform.DirectBootMirrorResult
import com.lavasec.app.platform.DirectBootSnapshotAuthenticator
import com.lavasec.app.platform.DirectBootSnapshotFreshnessAuthority
import com.lavasec.app.platform.DirectBootSnapshotKeyRotationResult
import com.lavasec.app.storage.CredentialFilterArtifactPublicationAuthority
import com.lavasec.app.storage.CredentialFilterArtifactPublicationStore
import com.lavasec.app.storage.CredentialFilterConfigurationAuthority
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import com.lavasec.app.storage.JvmArtifactPublicationFileSystem
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterCompileCheckpointObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.VpnOwnership
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import com.lavasec.app.presentation.filters.FilterSourceCatalogEntry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductionFilterCatalogSerializationTest {
    @Test
    fun `QA publication cannot observe the temporary catalog of an in flight install`() = runBlocking {
        withTimeout(30_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = Files.createTempDirectory("catalog-publication-").toFile()
            val name = "catalog-publication-${UUID.randomUUID()}"
            val scope = CoroutineScope(Job() + Dispatchers.IO)
            val catalog = BundledBlocklistCatalog(1, emptyList(), emptyList(), "initial", "c".repeat(64), "{}")
            val repository = CredentialConfigurationRepository(context, name, scope, catalog,
                freshnessAuthority = TestFreshness(), mirrorPublisher = { _, _ -> DirectBootMirrorResult.MirrorUnavailable })
            val files = JvmArtifactPublicationFileSystem
            val store = CredentialFilterConfigurationStore(root.resolve("authority"),
                CredentialFilterConfigurationAuthority(),
                CredentialFilterArtifactPublicationStore(root.resolve("authority"), CredentialFilterArtifactPublicationAuthority(), fileSystem = files),
                files, 500_000, allowQaProbeSets = true)
            val authority = ProductionFilterAuthority(context, catalog, repository, store,
                DeviceProtectedArtifactPublisher(root.resolve("device")), allowQaProbeSets = true)
            try {
                repository.completeOnboarding(AppConfiguration.default())
                val persisted = repository.state.first()
                authority.provisionInitial(checkNotNull(persisted.configuration), checkNotNull(persisted.configurationIdentity))
                val initial = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                val hosts = authority.createMutationHosts(initial, scope)
                // Freeze the presentation mirror to reproduce a delayed collector. Domain
                // evidence must use the synchronous coordinator even after a new commit.
                authority.confirmedState.first { it != null }
                @Suppress("UNCHECKED_CAST")
                val mirrors = ProductionFilterAuthority::class.java.getDeclaredField("coordinatorCollectors")
                    .apply { isAccessible = true }.get(authority) as List<Job>
                mirrors.forEach { it.cancelAndJoin() }
                val desired = initial.configuration.withQaProbeSet(DeviceQaProbeSet.HOSTED)
                // Suspend an install inside its real critical section after the repository
                // catalog swap. The test drives the actual QA port, compiler and two stores;
                // a write escaping this section commits its artifact then fails adoption.
                val gate = ProductionFilterAuthority::class.java.getDeclaredField("installMutex")
                    .apply { isAccessible = true }.get(authority) as Mutex
                gate.lock()
                repository.installCatalog(catalog.copy(revision = "pending", sha256 = "d".repeat(64)))
                val mutation = async(Dispatchers.Default) {
                    hosts.library.replaceReviewedForQa(desired, initial.configuration) { true }
                }
                try {
                    assertNull("A native write must wait until the catalog install settles", withTimeoutOrNull(3_000) { mutation.await() })
                    assertEquals(initial.configurationIdentity,
                        (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state.configurationIdentity)
                } finally {
                    repository.installCatalog(catalog)
                    gate.unlock()
                }
                assertTrue(mutation.await())
                val committed = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                assertEquals(desired, committed.configuration)
                assertEquals(initial.configurationIdentity.generation + 1, committed.configurationIdentity.generation)
                assertEquals(committed.configurationIdentity, repository.state.first().configurationIdentity)
                assertEquals(initial.configurationIdentity, authority.confirmedState.value!!.configurationIdentity)
                assertEquals("${committed.configurationIdentity.generation}:${committed.configurationIdentity.fingerprint}", authority.domainCheckRevision())
                assertEquals(committed.configurationIdentity, authority.checkDomain("example.com")!!.confirmed.configurationIdentity)
            } finally {
                authority.closePreparedCache()
                scope.cancel()
                context.preferencesDataStoreFile(name).delete()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `cancellation after durable catalog publication keeps the committed coordinator usable`() = runBlocking {
        withTimeout(60_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = Files.createTempDirectory("catalog-cancel-").toFile()
            val name = "catalog-cancel-${UUID.randomUUID()}"
            val scope = CoroutineScope(Job() + Dispatchers.IO)
            val catalog = BundledBlocklistCatalog(1, emptyList(), emptyList(), "initial", "c".repeat(64), "{}")
            val repository = CredentialConfigurationRepository(context, name, scope, catalog,
                freshnessAuthority = TestFreshness(), mirrorPublisher = { _, _ -> DirectBootMirrorResult.MirrorUnavailable })
            val files = JvmArtifactPublicationFileSystem
            var cancelAtCommit: Job? = null
            val store = CredentialFilterConfigurationStore(root.resolve("authority"),
                CredentialFilterConfigurationAuthority(),
                CredentialFilterArtifactPublicationStore(root.resolve("authority"), CredentialFilterArtifactPublicationAuthority(), fileSystem = files),
                files, 500_000, checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                    if (phase == CredentialFilterConfigurationPhase.RECORD_DIRECTORY_SYNCED) {
                        cancelAtCommit?.cancel()
                        cancelAtCommit = null
                    }
                }, allowQaProbeSets = true)
            val authority = ProductionFilterAuthority(context, catalog, repository, store,
                DeviceProtectedArtifactPublisher(root.resolve("device")), allowQaProbeSets = true)
            try {
                repository.completeOnboarding(AppConfiguration.default())
                val persisted = repository.state.first()
                authority.provisionInitial(checkNotNull(persisted.configuration), checkNotNull(persisted.configurationIdentity))
                val initial = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                val hosts = authority.createMutationHosts(initial, scope)
                val artifact = introducedCatalog("installed-after-cancel")
                val entries = MutableStateFlow<List<FilterSourceCatalogEntry>?>(emptyList())
                val install = async(Dispatchers.Default) {
                    cancelAtCommit = currentCoroutineContext()[Job]
                    installAndPublishFilterCatalog(authority, artifact, entries) { true }
                }
                install.join()
                assertTrue("The original worker cancellation still propagates", install.isCancelled)
                val committed = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                assertEquals(initial.configurationIdentity.generation + 1, committed.configurationIdentity.generation)
                assertEquals(committed.configurationIdentity, repository.state.first().configurationIdentity)
                assertEquals("installed-after-cancel", authority.catalogForQueries().revision)
                assertFalse("An adopted pointer does not require reconstruction after cancellation",
                    authority.catalogReconstructionRequired.value)
                assertEquals(listOf("introduced"), entries.value!!.map { it.id })
                assertEquals("Introduced source", entries.value!!.single().name)
                assertEquals("https://example.org/introduced.txt", entries.value!!.single().sourceUrl)
                val desired = committed.configuration.withQaProbeSet(DeviceQaProbeSet.HOSTED)
                assertTrue("The retained ports must use the committed catalog coordinator", hosts.library.replaceReviewedForQa(
                    desired, committed.configuration) { true })
                val next = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                assertEquals(committed.configurationIdentity.generation + 1, next.configurationIdentity.generation)
                assertEquals(next.configurationIdentity, repository.state.first().configurationIdentity)
            } finally {
                authority.closePreparedCache()
                scope.cancel()
                context.preferencesDataStoreFile(name).delete()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `catalog entries are published before the committed protection reload completes`() = runBlocking {
        withTimeout(60_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = Files.createTempDirectory("catalog-adoption-").toFile()
            val name = "catalog-adoption-${UUID.randomUUID()}"
            val scope = CoroutineScope(Job() + Dispatchers.IO)
            val catalog = BundledBlocklistCatalog(1, emptyList(), emptyList(), "initial", "c".repeat(64), "{}")
            val repository = CredentialConfigurationRepository(context, name, scope, catalog,
                freshnessAuthority = TestFreshness(), mirrorPublisher = { _, _ -> DirectBootMirrorResult.MirrorUnavailable })
            val files = JvmArtifactPublicationFileSystem
            val store = CredentialFilterConfigurationStore(root.resolve("authority"),
                CredentialFilterConfigurationAuthority(),
                CredentialFilterArtifactPublicationStore(root.resolve("authority"), CredentialFilterArtifactPublicationAuthority(), fileSystem = files),
                files, 500_000, allowQaProbeSets = true)
            val reloadStarted = CompletableDeferred<Unit>()
            val releaseReload = CompletableDeferred<Unit>()
            val authority = ProductionFilterAuthority(context, catalog, repository, store,
                DeviceProtectedArtifactPublisher(root.resolve("device")),
                protectionState = { ProtectionState.Protected(ConfigurationIdentity(1L, "initial"), VpnOwnership.User) },
                reloadProtection = { reloadStarted.complete(Unit); releaseReload.await() },
                allowQaProbeSets = true)
            try {
                repository.completeOnboarding(AppConfiguration.default())
                val persisted = repository.state.first()
                authority.provisionInitial(checkNotNull(persisted.configuration), checkNotNull(persisted.configurationIdentity))
                val initial = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                authority.createMutationHosts(initial, scope)
                val entries = MutableStateFlow<List<FilterSourceCatalogEntry>?>(emptyList())
                val install = async(Dispatchers.Default) {
                    installAndPublishFilterCatalog(authority, introducedCatalog("installed-before-reload"), entries) { true }
                }
                try {
                    reloadStarted.await()
                    val committed = authority.confirmedState.first {
                        it?.configurationIdentity?.generation == initial.configurationIdentity.generation + 1
                    }!!
                    assertTrue("Reload must still be suspended at the observation", !install.isCompleted)
                    assertEquals(committed.configurationIdentity,
                        (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state.configurationIdentity)
                    assertEquals("installed-before-reload", authority.catalogForQueries().revision)
                    assertEquals("Validation IDs must change when the committed coordinator changes",
                        listOf("introduced"), entries.value!!.map { it.id })
                    assertEquals("Introduced source", entries.value!!.single().name)
                    assertEquals("https://example.org/introduced.txt", entries.value!!.single().sourceUrl)
                } finally {
                    releaseReload.complete(Unit)
                }
                assertTrue(install.await())
                assertFalse("An ordinary catalog install keeps its current graph",
                    authority.catalogReconstructionRequired.value)
            } finally {
                releaseReload.complete(Unit)
                authority.closePreparedCache()
                scope.cancel()
                context.preferencesDataStoreFile(name).delete()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `durable catalog survives pointer adoption refusal`() = pointerRefusalKeepsCatalog(cancelAfterCommit = false)

    @Test
    fun `durable catalog survives pointer adoption refusal and cancellation`() = pointerRefusalKeepsCatalog(cancelAfterCommit = true)

    private fun pointerRefusalKeepsCatalog(cancelAfterCommit: Boolean) = runBlocking {
        withTimeout(60_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = Files.createTempDirectory("catalog-pointer-refusal-").toFile()
            val name = "catalog-pointer-refusal-${UUID.randomUUID()}"
            val scope = CoroutineScope(Job() + Dispatchers.IO)
            val catalog = BundledBlocklistCatalog(1, emptyList(), emptyList(), "initial", "c".repeat(64), "{}")
            val repository = CredentialConfigurationRepository(context, name, scope, catalog,
                freshnessAuthority = TestFreshness(), mirrorPublisher = { _, _ -> DirectBootMirrorResult.MirrorUnavailable })
            val files = JvmArtifactPublicationFileSystem
            var installJob: Job? = null
            val store = CredentialFilterConfigurationStore(root.resolve("authority"),
                CredentialFilterConfigurationAuthority(),
                CredentialFilterArtifactPublicationStore(root.resolve("authority"), CredentialFilterArtifactPublicationAuthority(), fileSystem = files),
                files, 500_000, checkpoint = CredentialFilterConfigurationCheckpoint { phase ->
                    if (phase == CredentialFilterConfigurationPhase.RECORD_DIRECTORY_SYNCED && installJob != null) {
                        // The real pointer adoption will reject this wrong catalog fingerprint,
                        // after the filter store has already committed the next generation.
                        repository.installCatalog(catalog)
                        if (cancelAfterCommit) installJob!!.cancel()
                    }
                }, allowQaProbeSets = true)
            val authority = ProductionFilterAuthority(context, catalog, repository, store,
                DeviceProtectedArtifactPublisher(root.resolve("device")), allowQaProbeSets = true)
            try {
                repository.completeOnboarding(AppConfiguration.default())
                val persisted = repository.state.first()
                authority.provisionInitial(checkNotNull(persisted.configuration), checkNotNull(persisted.configurationIdentity))
                val initial = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                val hosts = authority.createMutationHosts(initial, scope)
                authority.confirmedState.first { it != null }
                val entries = MutableStateFlow<List<FilterSourceCatalogEntry>?>(emptyList())
                val install = async(Dispatchers.Default) {
                    installJob = currentCoroutineContext()[Job]
                    installAndPublishFilterCatalog(authority, introducedCatalog("committed-without-pointer"), entries) { true }
                }
                install.join()
                assertEquals(cancelAfterCommit, install.isCancelled)
                val committed = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                assertEquals(initial.configurationIdentity.generation + 1, committed.configurationIdentity.generation)
                assertEquals("The pointer really failed to advance", initial.configurationIdentity, repository.state.first().configurationIdentity)
                assertEquals("committed-without-pointer", authority.catalogForQueries().revision)
                assertEquals(listOf("introduced"), entries.value!!.map { it.id })
                if (!cancelAfterCommit) assertTrue("The durable catalog must be retained in the sync cache", install.await())
                authority.confirmedState.first { it == null }
                assertTrue("A durable catalog with a refused pointer must request graph reconstruction",
                    authority.catalogReconstructionRequired.value)
                assertFalse("An unresolved pointer must withhold later writes until reconstruction",
                    hosts.library.replaceReviewedForQa(committed.configuration.withQaProbeSet(DeviceQaProbeSet.HOSTED),
                        initial.configuration) { true })
            } finally {
                authority.closePreparedCache()
                scope.cancel()
                context.preferencesDataStoreFile(name).delete()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `catalog preparation releases mutation admission and discards output when foreground commits first`() = runBlocking {
        withTimeout(60_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = Files.createTempDirectory("catalog-install-priority-").toFile()
            val name = "catalog-install-priority-${UUID.randomUUID()}"
            val scope = CoroutineScope(Job() + Dispatchers.IO)
            val catalog = BundledBlocklistCatalog(1, emptyList(), emptyList(), "initial", "c".repeat(64), "{}")
            val repository = CredentialConfigurationRepository(context, name, scope, catalog,
                freshnessAuthority = TestFreshness(), mirrorPublisher = { _, _ -> DirectBootMirrorResult.MirrorUnavailable })
            val files = JvmArtifactPublicationFileSystem
            val store = CredentialFilterConfigurationStore(root.resolve("authority"),
                CredentialFilterConfigurationAuthority(),
                CredentialFilterArtifactPublicationStore(root.resolve("authority"), CredentialFilterArtifactPublicationAuthority(), fileSystem = files),
                files, 500_000, allowQaProbeSets = true)
            val holdCatalog = AtomicBoolean(false)
            val catalogEntered = CountDownLatch(1)
            val releaseCatalog = CountDownLatch(1)
            val compiler = FilterCompiler(root.resolve("compiler"), checkpointObserver = FilterCompileCheckpointObserver {
                if (holdCatalog.compareAndSet(true, false)) {
                    catalogEntered.countDown()
                    check(releaseCatalog.await(10, TimeUnit.SECONDS)) { "Catalog fixture was not released" }
                }
            })
            val authority = ProductionFilterAuthority(context, catalog, repository, store,
                DeviceProtectedArtifactPublisher(root.resolve("device")), allowQaProbeSets = true, compiler = compiler)
            try {
                repository.completeOnboarding(AppConfiguration.default())
                val persisted = repository.state.first()
                authority.provisionInitial(checkNotNull(persisted.configuration), checkNotNull(persisted.configurationIdentity))
                val initial = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                assertTrue(initial.configuration.profiles.any { it.id != initial.configuration.activeProfileId })
                val hosts = authority.createMutationHosts(initial, scope)
                val gate = ProductionFilterAuthority::class.java.getDeclaredField("installMutex")
                    .apply { isAccessible = true }.get(authority) as Mutex
                holdCatalog.set(true)
                val entries = MutableStateFlow<List<FilterSourceCatalogEntry>?>(emptyList())
                val install = async(Dispatchers.Default) {
                    installAndPublishFilterCatalog(authority, introducedCatalog("prepared-background"), entries) { true }
                }
                try {
                    assertTrue("The real catalog compiler must reach its checkpoint", catalogEntered.await(5, TimeUnit.SECONDS))
                    assertTrue("Catalog preparation must leave foreground mutation admission open", gate.tryLock())
                    gate.unlock()
                    val desired = initial.configuration.withQaProbeSet(DeviceQaProbeSet.HOSTED)
                    val foreground = async(Dispatchers.Default) {
                        hosts.library.replaceReviewedForQa(desired, initial.configuration) { true }
                    }
                    withTimeout(3_000) { while (!gate.isLocked) delay(10) }
                    releaseCatalog.countDown()
                    assertTrue("Foreground publication must complete without lock inversion", foreground.await())
                    assertFalse("Prepared catalog output must be discarded after a foreground commit", install.await())
                    assertEquals("initial", authority.catalogForQueries().revision)
                    assertTrue("A rejected candidate must not publish catalog entries", entries.value!!.isEmpty())
                    val committed = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                    assertEquals(desired, committed.configuration)
                    assertEquals(initial.configurationIdentity.generation + 1, committed.configurationIdentity.generation)
                } finally {
                    releaseCatalog.countDown()
                    install.join()
                }
            } finally {
                releaseCatalog.countDown()
                authority.closePreparedCache()
                scope.cancel()
                context.preferencesDataStoreFile(name).delete()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `background compilation releases installation admission and yields its stale artifact to foreground work`() = runBlocking {
        withTimeout(60_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = Files.createTempDirectory("catalog-warm-priority-").toFile()
            val name = "catalog-warm-priority-${UUID.randomUUID()}"
            val scope = CoroutineScope(Job() + Dispatchers.IO)
            val catalog = BundledBlocklistCatalog(1, emptyList(), emptyList(), "initial", "c".repeat(64), "{}")
            val repository = CredentialConfigurationRepository(context, name, scope, catalog,
                freshnessAuthority = TestFreshness(), mirrorPublisher = { _, _ -> DirectBootMirrorResult.MirrorUnavailable })
            val files = JvmArtifactPublicationFileSystem
            val store = CredentialFilterConfigurationStore(root.resolve("authority"),
                CredentialFilterConfigurationAuthority(),
                CredentialFilterArtifactPublicationStore(root.resolve("authority"), CredentialFilterArtifactPublicationAuthority(), fileSystem = files),
                files, 500_000, allowQaProbeSets = true)
            val holdWarm = AtomicBoolean(false)
            val warmEntered = CountDownLatch(1)
            val releaseWarm = CountDownLatch(1)
            val compiler = FilterCompiler(root.resolve("compiler"), checkpointObserver = FilterCompileCheckpointObserver {
                if (holdWarm.compareAndSet(true, false)) {
                    warmEntered.countDown()
                    check(releaseWarm.await(10, TimeUnit.SECONDS)) { "Warm fixture was not released" }
                }
            })
            val authority = ProductionFilterAuthority(context, catalog, repository, store,
                DeviceProtectedArtifactPublisher(root.resolve("device")), allowQaProbeSets = true, compiler = compiler)
            try {
                repository.completeOnboarding(AppConfiguration.default())
                val persisted = repository.state.first()
                authority.provisionInitial(checkNotNull(persisted.configuration), checkNotNull(persisted.configurationIdentity))
                val initial = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                assertTrue(initial.configuration.profiles.any { it.id != initial.configuration.activeProfileId })
                val hosts = authority.createMutationHosts(initial, scope)
                val gate = ProductionFilterAuthority::class.java.getDeclaredField("installMutex")
                    .apply { isAccessible = true }.get(authority) as Mutex
                holdWarm.set(true)
                val warm = async(Dispatchers.Default) { authority.topUpPrepared(System.currentTimeMillis() + 60_000L) }
                try {
                    assertTrue("The real background compiler must reach its checkpoint", warmEntered.await(5, TimeUnit.SECONDS))
                    assertTrue("Background compilation must release the installation lock", gate.tryLock())
                    gate.unlock()
                    val desired = initial.configuration.withQaProbeSet(DeviceQaProbeSet.HOSTED)
                    val foreground = async(Dispatchers.Default) {
                        hosts.library.replaceReviewedForQa(desired, initial.configuration) { true }
                    }
                    withTimeout(3_000) { while (!gate.isLocked) delay(10) }
                    releaseWarm.countDown()
                    assertTrue("Foreground publication must complete without lock inversion", foreground.await())
                    assertEquals("Speculative output must be discarded when foreground owns admission", 0, warm.await())
                    val committed = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                    assertEquals(desired, committed.configuration)
                    assertEquals(initial.configurationIdentity.generation + 1, committed.configurationIdentity.generation)
                } finally {
                    releaseWarm.countDown()
                }
            } finally {
                releaseWarm.countDown()
                authority.closePreparedCache()
                scope.cancel()
                context.preferencesDataStoreFile(name).delete()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `a process refresh cannot publish after its credential graph retires during compilation`() = runBlocking {
        withTimeout(60_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = Files.createTempDirectory("catalog-retired-graph-").toFile()
            val name = "catalog-retired-graph-${UUID.randomUUID()}"
            val scope = CoroutineScope(Job() + Dispatchers.IO)
            val catalog = BundledBlocklistCatalog(1, emptyList(), emptyList(), "initial", "c".repeat(64), "{}")
            val repository = CredentialConfigurationRepository(context, name, scope, catalog,
                freshnessAuthority = TestFreshness(), mirrorPublisher = { _, _ -> DirectBootMirrorResult.MirrorUnavailable })
            val files = JvmArtifactPublicationFileSystem
            val store = CredentialFilterConfigurationStore(root.resolve("authority"),
                CredentialFilterConfigurationAuthority(),
                CredentialFilterArtifactPublicationStore(root.resolve("authority"), CredentialFilterArtifactPublicationAuthority(), fileSystem = files),
                files, 500_000, allowQaProbeSets = true)
            val holdInstall = AtomicBoolean(false)
            val compileEntered = CountDownLatch(1)
            val releaseCompile = CountDownLatch(1)
            val compiler = FilterCompiler(root.resolve("compiler"), checkpointObserver = FilterCompileCheckpointObserver {
                if (holdInstall.compareAndSet(true, false)) {
                    compileEntered.countDown()
                    check(releaseCompile.await(10, TimeUnit.SECONDS)) { "Retirement fixture was not released" }
                }
            })
            val authority = ProductionFilterAuthority(context, catalog, repository, store,
                DeviceProtectedArtifactPublisher(root.resolve("device")), allowQaProbeSets = true, compiler = compiler)
            try {
                repository.completeOnboarding(AppConfiguration.default())
                val persisted = repository.state.first()
                authority.provisionInitial(checkNotNull(persisted.configuration), checkNotNull(persisted.configurationIdentity))
                val initial = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
                authority.createMutationHosts(initial, scope)
                val entries = MutableStateFlow<List<FilterSourceCatalogEntry>?>(emptyList())
                holdInstall.set(true)
                // The refresh belongs to the process/test scope, not the retiring graph.
                val install = async(Dispatchers.Default) {
                    installAndPublishFilterCatalog(authority, introducedCatalog("retired-candidate"), entries) { scope.isActive }
                }
                try {
                    assertTrue("The real compiler must suspend before publication", compileEntered.await(5, TimeUnit.SECONDS))
                    scope.cancel()
                    assertFalse("The process refresh must still be running", install.isCompleted)
                    assertEquals("A successor can reopen the current durable generation during retirement",
                        initial.configurationIdentity,
                        (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state.configurationIdentity)
                    releaseCompile.countDown()
                    assertFalse("The retired graph must not publish into the successor's store", install.await())
                    assertFalse("A pre-commit refusal cannot request reconstruction",
                        authority.catalogReconstructionRequired.value)
                    assertEquals(initial.configurationIdentity,
                        (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state.configurationIdentity)
                    assertEquals("initial", authority.catalogForQueries().revision)
                    assertTrue("A rejected catalog cannot publish new selectable sources", entries.value!!.isEmpty())
                } finally { releaseCompile.countDown() }
            } finally {
                releaseCompile.countDown()
                authority.closePreparedCache()
                scope.cancel()
                context.preferencesDataStoreFile(name).delete()
                root.deleteRecursively()
            }
        }
    }

    private fun introducedCatalog(revision: String): com.lavasec.app.foundation.catalog.CatalogArtifact {
        val source = """{"id":"introduced","name":"Introduced source","category":"security","risk_level":"normal","default_enabled":false,"license_name":"Unlicense","attribution":"Provider","project_url":"https://example.org/introduced","source_url":"https://example.org/introduced.txt","version_id":"introduced-v1","entry_count":10,"byte_size":100,"source_hash":"${"b".repeat(64)}","accepted_source_hashes":[{"sha256":"${"b".repeat(64)}","byte_size":100,"entry_count":10,"status":"accepted"}],"normalized_hash":"${"b".repeat(64)}","published_at":"2026-08-01T00:00:00Z","redistribution_mode":"source_url_only","parse_format":"hosts","license_text_url":null,"notice_url":null}"""
        val bytes = """{"schema_version":2,"catalog_version":"$revision","generated_at":"2026-10-01T00:00:00Z","sources":[$source],"guardrails":[]}""".encodeToByteArray()
        return checkNotNull(CatalogArtifactLoader.load(bytes, CatalogProvenance.REMOTE, setOf("introduced")))
    }

    private class TestFreshness : DirectBootSnapshotFreshnessAuthority() {
        private var authenticator: DirectBootSnapshotAuthenticator? = null
        override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult {
            val next = DirectBootSnapshotAuthenticator { "a".repeat(64) }
            authenticator = next
            return rotated(next)
        }
        override fun currentAuthenticatorOrNull() = authenticator
    }
}
