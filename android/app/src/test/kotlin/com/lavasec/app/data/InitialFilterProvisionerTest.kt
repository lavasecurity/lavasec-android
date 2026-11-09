package com.lavasec.app.data

import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterCompileError
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.storage.CredentialFilterArtifactPublicationAuthority
import com.lavasec.app.storage.CredentialFilterArtifactPublicationStore
import com.lavasec.app.storage.CredentialFilterConfigurationAuthority
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import com.lavasec.app.storage.JvmArtifactPublicationFileSystem
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialFilterProvisionerTest {
    @Test
    fun `first provision compiles stages and commits exact authority once`() = runTest {
        val root = Files.createTempDirectory("initial-filter-provisioner-").toFile()
        try {
            val catalog = emptyCatalog()
            val configuration = AppConfiguration.default()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, generation = 1L)
            val store = configurationStore(root)
            var compileCount = 0
            val staged = mutableListOf<String>()
            val provisioner = InitialFilterProvisioner(
                catalog = catalog,
                configurationStore = store,
                requestFactory = CatalogFilterCompileRequestFactory(
                    catalog = catalog,
                    sourceAcquirer = CatalogSourceAcquirer { sources, _ ->
                        assertTrue(sources.isEmpty())
                        emptyList()
                    },
                    maxRules = 100,
                ),
                compile = { request ->
                    compileCount += 1
                    FilterCompiler(root.resolve("compiler-$compileCount")).compile(request)
                },
                stagePublicArtifact = { prepared ->
                    staged += prepared.sha256
                    true
                },
            )

            val first = provisioner.provision(configuration, seeded.identity)
            val second = provisioner.provision(configuration, seeded.identity)

            assertTrue(first is InitialFilterProvisionResult.Initialized)
            assertTrue(second is InitialFilterProvisionResult.Existing)
            assertEquals(1, compileCount)
            assertEquals(1, staged.size)
            val authoritative = store.readAuthoritative()
            assertTrue(authoritative is CredentialFilterConfigurationRead.Available)
            authoritative as CredentialFilterConfigurationRead.Available
            assertEquals(seeded.draft, authoritative.state.configuration)
            assertEquals(seeded.identity, authoritative.state.configurationIdentity)
            assertEquals(staged.single(), authoritative.state.verifiedArtifact.sha256)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `classified source acquisition failure never compiles stages or establishes authority`() = runTest {
        val root = Files.createTempDirectory("initial-filter-source-failure-").toFile()
        try {
            val catalog = catalogWithDefaultSource()
            val configuration = AppConfiguration.default()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, 1)
            val store = configurationStore(root)
            val provisioner = InitialFilterProvisioner(
                catalog, store,
                CatalogFilterCompileRequestFactory(catalog, CatalogSourceAcquirer { sources, _ ->
                    assertEquals(catalog.defaultSources.map { it.descriptor(catalog) }, sources)
                    throw SeededSourceCacheException(SeededSourceCacheFailure.TIMEOUT,
                        java.io.IOException("response detail must not enter diagnostic custody"))
                }),
                compile = { throw AssertionError("source failure must prevent compilation") },
                stagePublicArtifact = { throw AssertionError("source failure must prevent staging") },
            )

            val result = provisioner.provision(configuration, seeded.identity)
                as InitialFilterProvisionResult.Unavailable

            assertEquals(InitialFilterProvisionResult.Reason.SOURCE_OR_COMPILE_FAILED, result.reason)
            assertEquals(SeededSourceCacheFailure.TIMEOUT, result.sourceFailure)
            assertNull(result.compileFailure)
            assertNull(result.state)
            assertFalse(result.toString().contains("response detail"))
            assertTrue(store.readAuthoritative() is CredentialFilterConfigurationRead.Unavailable)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `real compiler refusal retains its typed reason without establishing authority`() = runTest {
        val root = Files.createTempDirectory("initial-filter-compile-failure-").toFile()
        try {
            val catalog = catalogWithDefaultSource()
            val configuration = AppConfiguration.default()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, 1)
            val store = configurationStore(root)
            val payload = "0.0.0.0 one.parity.example\n0.0.0.0 two.parity.example\n".toByteArray()
            val sourceFile = root.resolve("accepted-source.txt").apply { writeBytes(payload) }
            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) }
            val provisioner = InitialFilterProvisioner(
                catalog, store,
                CatalogFilterCompileRequestFactory(catalog, CatalogSourceAcquirer { sources, _ ->
                    sources.map { AcceptedSeededSource(it, digest, payload.size.toLong(), sourceFile) }
                }, maxRules = 1),
                compile = FilterCompiler(root.resolve("compiler"))::compile,
                stagePublicArtifact = { throw AssertionError("compiler refusal must prevent staging") },
            )

            val result = provisioner.provision(configuration, seeded.identity)
                as InitialFilterProvisionResult.Unavailable

            assertEquals(InitialFilterProvisionResult.Reason.SOURCE_OR_COMPILE_FAILED, result.reason)
            assertEquals(FilterCompileError.RULE_LIMIT_EXCEEDED, result.compileFailure)
            assertNull(result.sourceFailure)
            assertNull(result.state)
            assertTrue(store.readAuthoritative() is CredentialFilterConfigurationRead.Unavailable)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `public staging failure is distinct from source and compile failure and leaves authority absent`() = runTest {
        val root = Files.createTempDirectory("initial-filter-staging-failure-").toFile()
        try {
            val catalog = emptyCatalog()
            val configuration = AppConfiguration.default()
            val seeded = SeededFilterConfiguration.from(configuration, catalog, 1)
            val store = configurationStore(root)
            var staged = false
            val provisioner = InitialFilterProvisioner(
                catalog, store,
                CatalogFilterCompileRequestFactory(catalog, CatalogSourceAcquirer { _, _ -> emptyList() }),
                compile = FilterCompiler(root.resolve("compiler"))::compile,
                stagePublicArtifact = { staged = true; false },
            )

            val result = provisioner.provision(configuration, seeded.identity)
                as InitialFilterProvisionResult.Unavailable

            assertTrue(staged)
            assertEquals(InitialFilterProvisionResult.Reason.PUBLIC_STAGING_FAILED, result.reason)
            assertNull(result.sourceFailure)
            assertNull(result.compileFailure)
            assertNull(result.state)
            assertTrue(store.readAuthoritative() is CredentialFilterConfigurationRead.Unavailable)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `historical seed recovery compiles current catalog at the next durable generation`() = runTest {
        val root = Files.createTempDirectory("historical-seed-recovery-").toFile()
        try {
            val current = emptyCatalog()
            val previous = current.copy(revision = "previous-catalog", sha256 = "1".repeat(64))
            val oldSeed = SeededFilterConfiguration.from(AppConfiguration.default(), previous, 7L)
            val store = configurationStore(root)
            val subject = recoveryProvisioner(root, current, store, previousCatalog = { previous })

            val recovered = subject.provision(AppConfiguration.default(), oldSeed.identity)
                as InitialFilterProvisionResult.Initialized
            val expected = SeededFilterConfiguration.from(AppConfiguration.default(), current, 8L)
            assertEquals(expected.draft, recovered.state.configuration)
            assertEquals(expected.identity, recovered.state.configurationIdentity)
            assertFalse(oldSeed.identity.fingerprint == recovered.state.configurationIdentity.fingerprint)
            assertEquals(current.revision, recovered.state.verifiedArtifact.artifactIdentity.catalogRevision)
            assertEquals(current.sha256, recovered.state.verifiedArtifact.artifactIdentity.catalogSha256)
            assertEquals(recovered.state, (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state)
            // A pointer-adoption failure can retry without compiling or publishing another generation.
            val retried = subject.provision(AppConfiguration.default(), oldSeed.identity)
                as InitialFilterProvisionResult.Existing
            assertEquals(recovered.state, retried.state)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `an unproved saved library is refused without replacing it with defaults`() = runTest {
        val root = Files.createTempDirectory("historical-seed-refusal-").toFile()
        try {
            val current = emptyCatalog()
            val previous = current.copy(revision = "previous-catalog", sha256 = "1".repeat(64))
            val store = configurationStore(root)
            val subject = recoveryProvisioner(root, current, store, previousCatalog = { previous })
            val result = subject.provision(AppConfiguration.default(), ConfigurationIdentity(7L, "f".repeat(64)))
                as InitialFilterProvisionResult.Unavailable
            assertEquals(InitialFilterProvisionResult.Reason.AUTHORITY_INCOHERENT, result.reason)
            assertTrue(store.readAuthoritative() is CredentialFilterConfigurationRead.Unavailable)
            assertFalse(root.resolve("compiler").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `historical catalog read failure stays typed and never creates authority`() = runTest {
        val root = Files.createTempDirectory("historical-catalog-unavailable-").toFile()
        try {
            val current = emptyCatalog()
            val previous = current.copy(revision = "previous-catalog", sha256 = "1".repeat(64))
            val old = SeededFilterConfiguration.from(AppConfiguration.default(), previous, 7L)
            val store = configurationStore(root)
            val subject = recoveryProvisioner(root, current, store, previousCatalog = { error("asset unavailable") })
            val result = subject.provision(AppConfiguration.default(), old.identity)
                as InitialFilterProvisionResult.Unavailable
            assertEquals(InitialFilterProvisionResult.Reason.AUTHORITY_INCOHERENT, result.reason)
            assertTrue(store.readAuthoritative() is CredentialFilterConfigurationRead.Unavailable)
            assertFalse(root.resolve("compiler").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `failed staging of a recovered seed leaves the authoritative record absent`() = runTest {
        val root = Files.createTempDirectory("historical-staging-refusal-").toFile()
        try {
            val current = emptyCatalog()
            val previous = current.copy(revision = "previous-catalog", sha256 = "1".repeat(64))
            val old = SeededFilterConfiguration.from(AppConfiguration.default(), previous, 7L)
            val store = configurationStore(root)
            val subject = recoveryProvisioner(root, current, store, previousCatalog = { previous }, stage = { false })
            val result = subject.provision(AppConfiguration.default(), old.identity)
                as InitialFilterProvisionResult.Unavailable
            assertEquals(InitialFilterProvisionResult.Reason.PUBLIC_STAGING_FAILED, result.reason)
            assertTrue(store.readAuthoritative() is CredentialFilterConfigurationRead.Unavailable)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a newer authoritative record fences historical recovery without replacement`() = runTest {
        val root = Files.createTempDirectory("historical-newer-record-").toFile()
        try {
            val current = emptyCatalog()
            val previous = current.copy(revision = "previous-catalog", sha256 = "1".repeat(64))
            val store = configurationStore(root)
            val subject = recoveryProvisioner(root, current, store, previousCatalog = { previous })
            val newer = SeededFilterConfiguration.from(AppConfiguration.default(), current, 9L)
            val initial = subject.provision(AppConfiguration.default(), newer.identity)
                as InitialFilterProvisionResult.Initialized
            val old = SeededFilterConfiguration.from(AppConfiguration.default(), previous, 7L)
            val result = subject.provision(AppConfiguration.default(), old.identity)
                as InitialFilterProvisionResult.Unavailable
            assertEquals(InitialFilterProvisionResult.Reason.AUTHORITY_INCOHERENT, result.reason)
            assertEquals(initial.state, (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `historical generation overflow is refused without creating authority`() = runTest {
        val root = Files.createTempDirectory("historical-generation-overflow-").toFile()
        try {
            val current = emptyCatalog()
            val previous = current.copy(revision = "previous-catalog", sha256 = "1".repeat(64))
            val store = configurationStore(root)
            val subject = recoveryProvisioner(root, current, store, previousCatalog = { previous })
            val old = SeededFilterConfiguration.from(AppConfiguration.default(), previous, Long.MAX_VALUE)
            val result = subject.provision(AppConfiguration.default(), old.identity)
                as InitialFilterProvisionResult.Unavailable
            assertEquals(InitialFilterProvisionResult.Reason.AUTHORITY_INCOHERENT, result.reason)
            assertTrue(store.readAuthoritative() is CredentialFilterConfigurationRead.Unavailable)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `recovery can skip catalog releases while preserving the newest compile identity`() = runTest {
        val root = Files.createTempDirectory("historical-skipped-releases-").toFile()
        try {
            val current = emptyCatalog()
            val older = current.copy(revision = "older-catalog", sha256 = "1".repeat(64))
            val recent = current.copy(revision = "recent-catalog", sha256 = "2".repeat(64))
            val store = configurationStore(root)
            val subject = InitialFilterProvisioner(
                current, store,
                CatalogFilterCompileRequestFactory(current, CatalogSourceAcquirer { _, _ -> emptyList() }),
                compile = FilterCompiler(root.resolve("compiler"))::compile,
                stagePublicArtifact = { true },
                historicalCatalogs = { listOf(recent, older) },
            )
            val old = SeededFilterConfiguration.from(AppConfiguration.default(), older, 7L)
            val recovered = subject.provision(AppConfiguration.default(), old.identity)
                as InitialFilterProvisionResult.Initialized
            assertEquals(SeededFilterConfiguration.from(AppConfiguration.default(), current, 8L).identity,
                recovered.state.configurationIdentity)
            assertEquals(current.revision, recovered.state.verifiedArtifact.artifactIdentity.catalogRevision)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `recovery retains proven selections across policy-only and catalog-pin changes`() = runTest {
        for (changedPins in listOf(false, true)) {
            val root = Files.createTempDirectory("historical-policy-recovery-").toFile()
            try {
                val previous = catalogWithDefaultSource()
                val current = previous.copy(
                    revision = if (changedPins) "new-catalog-pins" else previous.revision,
                    sha256 = if (changedPins) "e".repeat(64) else previous.sha256,
                    sources = previous.sources.map { it.copy(defaultEnabled = false) },
                )
                val oldSeed = SeededFilterConfiguration.from(AppConfiguration.default(), previous, 7L)
                val currentDefault = SeededFilterConfiguration.from(AppConfiguration.default(), current, 7L)
                assertFalse(oldSeed.identity == currentDefault.identity)
                val payload = "0.0.0.0 saved-selection.parity.example\n".toByteArray()
                val sourceFile = root.resolve("accepted-source.txt").apply { writeBytes(payload) }
                val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
                val store = configurationStore(root)
                val subject = InitialFilterProvisioner(
                    current, store,
                    CatalogFilterCompileRequestFactory(current, CatalogSourceAcquirer { sources, _ ->
                        assertEquals(listOf("controlled-default"), sources.map { it.id })
                        sources.map { AcceptedSeededSource(it, digest, payload.size.toLong(), sourceFile) }
                    }, maxRules = 100),
                    compile = FilterCompiler(root.resolve("compiler"))::compile,
                    stagePublicArtifact = { true },
                    historicalCatalogs = { listOf(current, previous) },
                )
                val recovered = subject.provision(AppConfiguration.default(), oldSeed.identity)
                    as InitialFilterProvisionResult.Initialized
                assertEquals(oldSeed.draft, recovered.state.configuration)
                assertEquals(8L, recovered.state.configurationIdentity.generation)
                assertEquals(com.lavasec.app.platform.canonicalFilterConfigurationFingerprint(
                    oldSeed.draft, current.revision, current.sha256), recovered.state.configurationIdentity.fingerprint)
                if (changedPins) assertFalse(oldSeed.identity.fingerprint == recovered.state.configurationIdentity.fingerprint)
                else assertEquals(oldSeed.identity.fingerprint, recovered.state.configurationIdentity.fingerprint)
                assertEquals(current.revision, recovered.state.verifiedArtifact.artifactIdentity.catalogRevision)
            } finally { root.deleteRecursively() }
        }
    }

    @Test
    fun `failed catalog migration withholds mutation authority without losing the durable artifact`() = runTest {
        val root = Files.createTempDirectory("catalog-migration-gate-").toFile()
        try {
            val current = emptyCatalog()
            val store = configurationStore(root)
            val seeded = SeededFilterConfiguration.from(AppConfiguration.default(), current, 1L)
            val initial = recoveryProvisioner(root, current, store, previousCatalog = { current })
                .provision(AppConfiguration.default(), seeded.identity)
                as InitialFilterProvisionResult.Initialized
            for (throws in listOf(false, true)) {
                val withheld = com.lavasec.app.reconcileCatalogMigration(
                    initial.state,
                    migrate = {
                        assertEquals(initial.state, it)
                        if (throws) error("download or publication failed") else false
                    },
                    readConfirmed = { initial.state },
                )
                assertEquals(initial.state, withheld.confirmed)
                assertFalse(withheld.mutationsAvailable)
                assertEquals(initial.state, (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state)
            }
            val retried = com.lavasec.app.reconcileCatalogMigration(
                initial.state, migrate = { true }, readConfirmed = { initial.state },
            )
            assertEquals(initial.state, retried.confirmed)
            assertTrue(retried.mutationsAvailable)
            val incoherent = com.lavasec.app.reconcileCatalogMigration(
                initial.state, migrate = { false }, readConfirmed = { null },
            )
            assertNull(incoherent.confirmed)
            assertFalse(incoherent.mutationsAvailable)
            var cancelled = false
            try {
                com.lavasec.app.reconcileCatalogMigration(
                    initial.state,
                    migrate = { throw kotlinx.coroutines.CancellationException("graph closed") },
                    readConfirmed = { error("cancelled migration must not expose authority") },
                )
            } catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
            assertTrue(cancelled)
        } finally { root.deleteRecursively() }
    }

    private fun recoveryProvisioner(
        root: java.io.File,
        catalog: BundledBlocklistCatalog,
        store: CredentialFilterConfigurationStore,
        previousCatalog: () -> BundledBlocklistCatalog,
        stage: (com.lavasec.app.foundation.filter.PreparedFilterArtifact) -> Boolean = { true },
    ): InitialFilterProvisioner = InitialFilterProvisioner(
        catalog, store,
        CatalogFilterCompileRequestFactory(catalog, CatalogSourceAcquirer { _, _ -> emptyList() }),
        compile = FilterCompiler(root.resolve("compiler"))::compile,
        stagePublicArtifact = stage,
        historicalCatalogs = { listOf(previousCatalog()) },
    )

    private fun configurationStore(root: java.io.File): CredentialFilterConfigurationStore {
        val fileSystem = JvmArtifactPublicationFileSystem
        val artifactStore = CredentialFilterArtifactPublicationStore(
            rootDirectory = root.resolve("authority"),
            authority = CredentialFilterArtifactPublicationAuthority(),
            fileSystem = fileSystem,
        )
        return CredentialFilterConfigurationStore(
            rootDirectory = root.resolve("authority"),
            authority = CredentialFilterConfigurationAuthority(),
            artifactStore = artifactStore,
            fileSystem = fileSystem,
            maxRules = 100,
        )
    }

    private fun emptyCatalog(): BundledBlocklistCatalog = BundledBlocklistCatalog(
        schemaVersion = 1,
        categories = emptyList(),
        sources = emptyList(),
        revision = "empty-catalog-v1",
        sha256 = "c".repeat(64),
        assetText = "{}",
    ).also { assertTrue(it.filterSnapshot.sources == FilterCatalogSnapshot(emptyList()).sources) }

    private fun catalogWithDefaultSource(): BundledBlocklistCatalog = emptyCatalog().copy(sources = listOf(
        CatalogSourceMetadata(
            id = "controlled-default", name = "Controlled default", category = FilterSourceCategory.SECURITY,
            format = CatalogSourceFormat.HOSTS, blocklistFormat = BlocklistFormat.HOSTS,
            sourceUrl = "https://sources.parity.example/hosts", defaultEnabled = true,
        ),
    ))
}
