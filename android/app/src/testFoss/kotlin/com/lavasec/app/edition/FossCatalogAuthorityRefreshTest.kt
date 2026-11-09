package com.lavasec.app.edition

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.ProductionFilterAuthority
import com.lavasec.app.installAndPublishFilterCatalog
import com.lavasec.app.data.BundledBlocklistCatalog
import com.lavasec.app.data.BundledCatalogRefreshOwner
import com.lavasec.app.data.CatalogBundledArtifact
import com.lavasec.app.data.CatalogSourceFormat
import com.lavasec.app.data.CatalogSourceMetadata
import com.lavasec.app.data.CredentialConfigurationRepository
import com.lavasec.app.data.SeededSourceHttpResponse
import com.lavasec.app.data.SeededSourceTransport
import com.lavasec.app.directboot.DeviceProtectedArtifactPublisher
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.platform.DirectBootMirrorResult
import com.lavasec.app.platform.DirectBootSnapshotAuthenticator
import com.lavasec.app.platform.DirectBootSnapshotFreshnessAuthority
import com.lavasec.app.platform.DirectBootSnapshotKeyRotationResult
import com.lavasec.app.presentation.filters.FilterSourceCatalogEntry
import com.lavasec.app.storage.CredentialFilterArtifactPublicationAuthority
import com.lavasec.app.storage.CredentialFilterArtifactPublicationStore
import com.lavasec.app.storage.CredentialFilterConfigurationAuthority
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import com.lavasec.app.storage.JvmArtifactPublicationFileSystem
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual composition callback, downloader, compiler and durable generation CAS. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class FossCatalogAuthorityRefreshTest {
    @Test fun `explicit refresh downloads new selected source bytes under unchanged bundled metadata`() = runBlocking {
        withAuthority { fixture ->
            assertEquals(listOf(SELECTED_URL), fixture.downloads)
            val initial = fixture.committed()
            assertEquals(1, fixture.blockRules())
            // Automatic metadata installation still rejects an identical catalog.
            assertFalse(fixture.authority.installCatalog(fixture.artifact, { true }) {})
            assertEquals(1, fixture.downloads.size)

            fixture.body = "one.example\ntwo.example\n"
            assertTrue(fixture.owner.refresh())
            val updated = fixture.committed()
            assertEquals(listOf(SELECTED_URL, SELECTED_URL), fixture.downloads)
            assertEquals(2, fixture.blockRules())
            assertSame(fixture.catalog, fixture.authority.catalogForQueries())
            assertEquals(initial.configuration, updated.configuration)
            assertEquals(initial.configurationIdentity.generation + 1, updated.configurationIdentity.generation)
            assertEquals(updated.configurationIdentity, fixture.repository.state.first().configurationIdentity)
            assertEquals(listOf("selected", "inactive"), fixture.entries.value!!.map { it.id })
            assertNotEquals(initial.verifiedArtifact.artifactIdentity, updated.verifiedArtifact.artifactIdentity)

            // A second explicit refresh must bypass the newly populated exact source cache too.
            fixture.body = "one.example\ntwo.example\nthree.example\n"
            assertTrue(fixture.owner.refresh())
            assertEquals(3, fixture.downloads.size)
            assertEquals(3, fixture.blockRules())
            assertEquals(updated.configurationIdentity.generation + 1, fixture.committed().configurationIdentity.generation)
        }
    }

    @Test fun `failed selected upstream refresh keeps the previous durable filter generation`() = runBlocking {
        withAuthority { fixture ->
            val initial = fixture.committed()
            fixture.offline = true
            assertFalse(fixture.owner.refresh())
            assertEquals(2, fixture.downloads.size)
            assertEquals(initial.configurationIdentity, fixture.committed().configurationIdentity)
            assertEquals(initial.verifiedArtifact.artifactIdentity, fixture.committed().verifiedArtifact.artifactIdentity)
            assertEquals(initial.configurationIdentity, fixture.repository.state.first().configurationIdentity)
            assertSame(fixture.catalog, fixture.authority.catalogForQueries())
        }
    }

    @Test fun `retired graph cannot publish freshly downloaded bytes`() = runBlocking {
        withAuthority { fixture ->
            val initial = fixture.committed()
            fixture.body = "replacement.example\n"
            fixture.afterDownload = { fixture.current = false }
            assertFalse(fixture.owner.refresh())
            assertEquals(2, fixture.downloads.size)
            assertEquals(initial.configurationIdentity, fixture.committed().configurationIdentity)
            assertEquals(initial.verifiedArtifact.artifactIdentity, fixture.committed().verifiedArtifact.artifactIdentity)
            assertEquals(initial.configurationIdentity, fixture.repository.state.first().configurationIdentity)
        }
    }

    private suspend fun withAuthority(block: suspend (Fixture) -> Unit) = withTimeout(60_000) {
        val root = Files.createTempDirectory("foss-real-refresh-").toFile()
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = root.resolve("cache").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = root.resolve("no-backup").apply { mkdirs() }
        }
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val fixture = Fixture(context, root, scope)
        try {
            fixture.repository.completeOnboarding(AppConfiguration.default())
            val persisted = fixture.repository.state.first()
            fixture.authority.provisionInitial(checkNotNull(persisted.configuration), checkNotNull(persisted.configurationIdentity))
            fixture.authority.createMutationHosts(fixture.committed(), scope)
            block(fixture)
        } finally {
            fixture.authority.closePreparedCache()
            scope.cancel()
            context.preferencesDataStoreFile(fixture.name).delete()
            root.deleteRecursively()
        }
    }

    private class Fixture(context: Context, root: File, scope: CoroutineScope) {
        val name = "foss-source-refresh-${UUID.randomUUID()}"
        val catalog = BundledBlocklistCatalog(1, listOf("security"), listOf(
            CatalogSourceMetadata("selected", "Selected", FilterSourceCategory.SECURITY,
                CatalogSourceFormat.PLAIN_DOMAINS, BlocklistFormat.PLAIN_DOMAINS, SELECTED_URL, true),
            CatalogSourceMetadata("inactive", "Inactive", FilterSourceCategory.SECURITY,
                CatalogSourceFormat.PLAIN_DOMAINS, BlocklistFormat.PLAIN_DOMAINS, "https://example.org/inactive.txt", false),
        ), "pinned-bundled", "c".repeat(64), "{}")
        val repository = CredentialConfigurationRepository(context, name, scope, catalog,
            freshnessAuthority = TestFreshness(), mirrorPublisher = { _, _ -> DirectBootMirrorResult.MirrorUnavailable })
        private val files = JvmArtifactPublicationFileSystem
        private val store = CredentialFilterConfigurationStore(root.resolve("authority"),
            CredentialFilterConfigurationAuthority(),
            CredentialFilterArtifactPublicationStore(root.resolve("authority"), CredentialFilterArtifactPublicationAuthority(), fileSystem = files),
            files, 500_000, allowQaProbeSets = true)
        var body = "one.example\n"
        var offline = false
        var current = true
        var afterDownload: () -> Unit = {}
        val downloads = mutableListOf<String>()
        val authority = ProductionFilterAuthority(context, catalog, repository, store,
            DeviceProtectedArtifactPublisher(root.resolve("device")), allowQaProbeSets = true,
            sourceTransportFactory = { SeededSourceTransport { url ->
                downloads += url
                assertEquals("Only the selected configured upstream may be fetched", SELECTED_URL, url)
                if (offline) throw IOException("offline")
                SeededSourceHttpResponse.ok(body.encodeToByteArray()).also { afterDownload() }
            } })
        val artifact = CatalogBundledArtifact.from(catalog)
        val entries = MutableStateFlow<List<FilterSourceCatalogEntry>?>(emptyList())
        val owner = BundledCatalogRefreshOwner(artifact) {
            installAndPublishFilterCatalog(authority, it, entries) { current }
        }
        suspend fun committed() = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
        suspend fun blockRules() = committed().verifiedArtifact.artifactIdentity.ruleCounts.let { it.blockExact + it.blockSuffix }
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

    private companion object { const val SELECTED_URL = "https://example.org/selected.txt" }
}
