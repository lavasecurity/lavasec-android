package com.lavasec.app.product.privacy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.data.ActivityDatabase
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.data.ActivityRevisionBus
import com.lavasec.app.data.CredentialActivityWriter
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.platform.LoggingSettings
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class LocalPrivacyServicesTest {
    private lateinit var context: Context
    private lateinit var database: ActivityDatabase
    private lateinit var repository: ActivityRepository
    private lateinit var root: File
    private val now = Instant.parse("2026-07-19T12:00:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = ActivityDatabase(context, "privacy-${UUID.randomUUID()}.sqlite")
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        root = File(context.cacheDir, "privacy-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test fun `QA reset CAS refusal and retired visit preserve every local record and retention`() = runTest {
        repository.record(event(901, "qa-retained.example"), LoggingSettings())
        for (current in listOf(true, false)) {
            var writes = 0; var clears = 0
            val subject = CredentialActivityPrivacyController(repository, CredentialActivityWriter(repository, ActivityRevisionBus()),
                CredentialPrivacyFence(), PrivacyAuthorizer { PrivacyAuthorization.Authorized },
                persistQaConfiguration = { _, _, _, admitted -> writes++; assertTrue(admitted()); false },
                clearNetworkActivity = { clears++ }, clearGuardProgress = { clears++ }, clearQaDiagnostics = { clears++ })
            assertEquals(PrivacyMutationResult.Cancelled, subject.resetForQa(LoggingSettings(), 4) { current })
            assertEquals(if (current) 1 else 0, writes); assertEquals(0, clears)
            assertEquals(1, repository.history(searchText = "qa-retained.example").size)
            assertEquals(LoggingSettings(), repository.loggingPolicy().settings)
        }
    }

    @Test fun `QA reset commits history before actual SQL clear and cleanup failures remain committed`() = runTest {
        repository.record(event(902, "qa-clear.example"), LoggingSettings())
        val order = mutableListOf<String>()
        val subject = CredentialActivityPrivacyController(repository, CredentialActivityWriter(repository, ActivityRevisionBus()),
            CredentialPrivacyFence(), PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            persistQaConfiguration = { previous, next, generation, admitted ->
                assertEquals(LoggingSettings(), previous); assertEquals(previous.copy(keepHistory = false), next)
                assertEquals(4L, generation); assertTrue(admitted())
                assertEquals(1, repository.history(searchText = "qa-clear.example").size)
                order += "persist"; true
            }, clearNetworkActivity = { order += "network"; assertTrue(repository.history().isEmpty()) },
            clearGuardProgress = { order += "guard"; error("controlled cleanup failure") },
            clearQaDiagnostics = { order += "diagnostics" })
        assertTrue(subject.resetForQa(LoggingSettings(), 4) { true } is PrivacyMutationResult.CommittedReconciliationRequired)
        assertEquals(listOf("persist", "network", "guard", "diagnostics"), order)
        assertTrue(repository.history().isEmpty()); assertFalse(repository.loggingPolicy().settings.keepHistory)
        assertTrue(repository.loggingPolicy().settings.keepCounts)
    }

    @Test
    fun `export encodes one stable private snapshot and publishes no filesystem path`() = runTest {
        repeat(31) { index ->
            repository.record(
                event(index + 1L, "domain-$index.example"),
                LoggingSettings(),
            )
        }
        val store = AndroidLocalExportArtifactStore(
            context = context,
            rootDirectory = root,
            ioDispatcher = Dispatchers.Unconfined,
            uriForFile = { Uri.parse("content://exports/${it.name}") },
        )
        val service = LocalActivityExportService(
            repository = repository,
            fence = CredentialPrivacyFence(),
            artifactStore = store,
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            nowMillis = now::toEpochMilli,
            zoneId = ZoneOffset.UTC,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val completed = service.export(LocalActivityExportOptions()) as LocalExportResult.Completed
        val file = store.resolve(completed.handle)
        ZipFile(file).use { zip ->
            assertEquals(
                setOf("manifest.txt", "filtering-counts.csv", "domain-history.csv"),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
            val manifest = zip.getInputStream(zip.getEntry("manifest.txt")).bufferedReader().readText()
            val history = zip.getInputStream(zip.getEntry("domain-history.csv")).bufferedReader().readLines()
            assertTrue(manifest.contains("network_activity_included=false"))
            assertFalse(manifest.contains(database.readableDatabase.path))
            assertEquals(32, history.size)
        }
    }

    @Test
    fun `Network Activity export requires explicit selection and its own authoritative snapshot`() = runTest {
        val artifactStore = AndroidLocalExportArtifactStore(context = context, rootDirectory = root,
            ioDispatcher = Dispatchers.Unconfined, uriForFile = { Uri.parse("content://exports/${it.name}") })
        var reads = 0
        val service = LocalActivityExportService(repository = repository, fence = CredentialPrivacyFence(),
            artifactStore = artifactStore, authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            nowMillis = now::toEpochMilli, zoneId = ZoneOffset.UTC, ioDispatcher = Dispatchers.Unconfined,
            networkActivityExport = { reads++; "{\"version\":1,\"rows\":[]}".toByteArray() })
        service.export(LocalActivityExportOptions())
        assertEquals(0, reads)
        val result = service.export(LocalActivityExportOptions(countsAndUptime = false, domainHistory = false,
            networkActivity = true)) as LocalExportResult.Completed
        ZipFile(artifactStore.resolve(result.handle)).use { zip ->
            assertEquals(setOf("manifest.txt", "network-activity.json"), zip.entries().asSequence().map { it.name }.toSet())
            assertTrue(zip.getInputStream(zip.getEntry("manifest.txt")).bufferedReader().readText().contains("network_activity_included=true"))
            assertEquals("{\"version\":1,\"rows\":[]}", zip.getInputStream(zip.getEntry("network-activity.json")).bufferedReader().readText())
        }
        assertEquals(1, reads)
        val unavailable = LocalActivityExportService(repository = repository, fence = CredentialPrivacyFence(),
            artifactStore = artifactStore, authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            nowMillis = now::toEpochMilli, ioDispatcher = Dispatchers.Unconfined)
        assertTrue(unavailable.export(LocalActivityExportOptions(false, false, true)) is LocalExportResult.Failed)
    }

    @Test
    fun `numeric fallback history accompanies both private export modes and only all logs clear erases it`() = runTest {
        val artifactStore = AndroidLocalExportArtifactStore(context = context, rootDirectory = root,
            ioDispatcher = Dispatchers.Unconfined, uriForFile = { Uri.parse("content://exports/${it.name}") })
        var historyReads = 0
        val snapshot = "{\"schemaVersion\":1,\"hours\":[],\"incidents\":[]}".toByteArray()
        val service = LocalActivityExportService(repository, CredentialPrivacyFence(), artifactStore,
            PrivacyAuthorizer { PrivacyAuthorization.Authorized }, now::toEpochMilli, ZoneOffset.UTC,
            Dispatchers.Unconfined, dnsFallbackHistoryExport = { historyReads++; snapshot })
        for (domainHistory in listOf(false, true)) {
            val result = service.export(LocalActivityExportOptions(domainHistory = domainHistory)) as LocalExportResult.Completed
            ZipFile(artifactStore.resolve(result.handle)).use { zip ->
                assertEquals(snapshot.toString(Charsets.UTF_8), zip.getInputStream(zip.getEntry("dns-fallback-history.json"))
                    .bufferedReader().readText())
                assertEquals(domainHistory, zip.getEntry("domain-history.csv") != null)
            }
        }
        assertEquals(2, historyReads)
        var clears = 0
        val controller = CredentialActivityPrivacyController(repository,
            CredentialActivityWriter(repository, ActivityRevisionBus()), CredentialPrivacyFence(),
            PrivacyAuthorizer { PrivacyAuthorization.Authorized }, clearDnsFallbackHistory = { clears++ })
        controller.clear(LocalActivityClearSelection(false, true))
        controller.clear(LocalActivityClearSelection(true, false))
        assertEquals(0, clears)
        controller.clear(LocalActivityClearSelection(true, true))
        assertEquals(1, clears)
        controller.setLogging(LoggingSettings(keepCounts = false, keepHistory = false, keepNetworkActivity = false))
        assertEquals(1, clears)
    }

    @Test
    fun `all logs clear publishes committed deletions when fallback history cleanup fails`() = runTest {
        val range = com.lavasec.app.foundation.activity.ActivityDateRange(
            now.atOffset(ZoneOffset.UTC).toLocalDate(), now.atOffset(ZoneOffset.UTC).toLocalDate())
        for ((index, failure) in listOf(
            java.io.IOException("controlled atomic write failure"),
            IllegalStateException("controlled retired history store"),
            kotlinx.coroutines.CancellationException("controlled post-commit cleanup cancellation"),
        ).withIndex()) {
            repository.record(event(950L + index, "committed-clear.example"), LoggingSettings())
            assertEquals(1, repository.history().size)
            assertEquals(1L, repository.summary(range).blockedCount)
            val bus = ActivityRevisionBus()
            var cleanupAttempts = 0
            var cleanupUnavailable = true
            var retainedDnsEvidence = true
            val controller = CredentialActivityPrivacyController(repository,
                CredentialActivityWriter(repository, bus), CredentialPrivacyFence(),
                PrivacyAuthorizer { PrivacyAuthorization.Authorized }, clearDnsFallbackHistory = {
                    cleanupAttempts++
                    assertTrue(repository.history().isEmpty())
                    assertEquals(0L, repository.summary(range).blockedCount)
                    if (cleanupUnavailable) throw failure
                    retainedDnsEvidence = false
                })
            val before = bus.current.value.token

            val result = controller.clear(LocalActivityClearSelection(true, true))

            assertTrue(failure.javaClass.simpleName, result is PrivacyMutationResult.CommittedReconciliationRequired)
            assertEquals(1, cleanupAttempts)
            assertEquals(before + 1L, bus.current.value.token)
            assertTrue(repository.history().isEmpty())
            assertEquals(0L, repository.summary(range).blockedCount)
            assertTrue(retainedDnsEvidence)

            cleanupUnavailable = false
            assertEquals(PrivacyMutationResult.Completed, controller.clear(LocalActivityClearSelection(true, true)))
            assertFalse(retainedDnsEvidence)
            assertEquals(2, cleanupAttempts)
            assertEquals(before + 2L, bus.current.value.token)
            assertTrue(repository.history().isEmpty())
        }
    }

    @Test
    fun `private provider resolves only the opaque published export`() = runTest {
        val store = AndroidLocalExportArtifactStore(
            context = context,
            ioDispatcher = Dispatchers.Unconfined,
            zoneId = ZoneOffset.UTC,
        )
        val pending = store.create(now.toEpochMilli())
        pending.output.write(byteArrayOf(1))
        pending.sync()
        val handle = pending.publish()

        val uri = store.uri(handle)
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )!!.use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.local-log-export", uri.authority)
        assertEquals("lava-local-logs-2026-07-19-1200.zip", displayName)
        pending.discard()
    }

    @Test
    fun `failed atomic publication removes every staged export artifact`() = runTest {
        repository.record(event(50L, "cleanup.example"), LoggingSettings())
        val store = AndroidLocalExportArtifactStore(
            context = context,
            rootDirectory = root,
            ioDispatcher = Dispatchers.Unconfined,
            fileOperations = object : LocalExportFileOperations {
                override fun sync(output: FileOutputStream) {
                    output.flush()
                    output.fd.sync()
                }

                override fun moveAtomically(source: File, target: File) {
                    error("atomic publication unavailable")
                }
            },
            uriForFile = { Uri.parse("content://exports/${it.name}") },
        )
        val service = LocalActivityExportService(
            repository = repository,
            fence = CredentialPrivacyFence(),
            artifactStore = store,
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            nowMillis = now::toEpochMilli,
            zoneId = ZoneOffset.UTC,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = service.export(LocalActivityExportOptions())

        assertTrue(result is LocalExportResult.Failed)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `share intent grants exactly one opaque provider uri`() = runTest {
        val store = AndroidLocalExportArtifactStore(
            context = context,
            rootDirectory = root,
            ioDispatcher = Dispatchers.Unconfined,
            uriForFile = { Uri.parse("content://exports/${it.name}") },
        )
        val pending = store.create(now.toEpochMilli())
        pending.output.write(byteArrayOf(1, 2, 3))
        pending.sync()
        val handle = pending.publish()

        val intent = AndroidLocalExportShareIntentFactory(context, store).create(handle)
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        assertEquals("content", uri?.scheme)
        assertEquals(1, intent.clipData?.itemCount)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(setOf(Intent.EXTRA_STREAM), intent.extras?.keySet())
        assertFalse(intent.extras.toString().contains(root.absolutePath))
        pending.discard()
    }

    @Test fun `restore guarded persistence refusal after waiting never deletes retained activity`() = runTest {
        repository.record(event(100L, "restore-authority-retired.example"), LoggingSettings())
        val previous = repository.loggingPolicy()
        var current = true
        var clears = 0
        val controller = CredentialActivityPrivacyController(
            repository = repository, writer = CredentialActivityWriter(repository, ActivityRevisionBus()),
            fence = CredentialPrivacyFence(), authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            persistConfiguration = { error("Restore must use guarded production persistence") },
            persistRestoreConfiguration = { _, isCurrent ->
                current = false // Models authorization retirement while the store admission waits.
                isCurrent()
            },
            clearNetworkActivity = { clears++ }, clearGuardProgress = { clears++ },
        )
        assertEquals(PrivacyMutationResult.Cancelled,
            controller.setLoggingForRestore(LoggingSettings(false, false, false, false)) { current })
        assertEquals(previous, repository.loggingPolicy())
        assertEquals(1, repository.history(searchText = "restore-authority-retired.example").size)
        assertEquals(0, clears)
    }

    @Test
    fun `restore persistence refusal preserves retained rows and never clears other surfaces`() = runTest {
        repository.record(event(100L, "restore-preserved.example"), LoggingSettings())
        val previousPolicy = repository.loggingPolicy()
        var clears = 0
        val controller = CredentialActivityPrivacyController(
            repository = repository,
            writer = CredentialActivityWriter(repository, ActivityRevisionBus()),
            fence = CredentialPrivacyFence(),
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            persistConfiguration = { error("credential persistence refused") },
            clearNetworkActivity = { clears++ },
            clearGuardProgress = { clears++ },
        )
        val disabled = LoggingSettings(false, false, false, false)

        assertTrue(controller.setLoggingForRestore(disabled) is PrivacyMutationResult.Failed)

        assertEquals(previousPolicy, repository.loggingPolicy())
        assertEquals(1, repository.history(searchText = "restore-preserved.example").size)
        assertEquals(0, clears)
    }

    @Test
    fun `restore commits persistence before destructive retention and refuses a retired owner`() = runTest {
        repository.record(event(100L, "restore-committed.example"), LoggingSettings())
        val operations = mutableListOf<String>()
        val controller = CredentialActivityPrivacyController(
            repository = repository,
            writer = CredentialActivityWriter(repository, ActivityRevisionBus()),
            fence = CredentialPrivacyFence(),
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            persistConfiguration = {
                assertEquals(1, repository.history(searchText = "restore-committed.example").size)
                operations += "persist"
            },
            clearNetworkActivity = { operations += "network" },
            clearGuardProgress = { operations += "progress" },
        )
        val disabled = LoggingSettings(false, false, false, false)
        assertEquals(PrivacyMutationResult.Cancelled, controller.setLoggingForRestore(disabled) { false })
        assertTrue(operations.isEmpty())

        assertEquals(PrivacyMutationResult.Completed, controller.setLoggingForRestore(disabled))

        assertEquals(listOf("persist", "network", "progress"), operations)
        assertTrue(repository.history().isEmpty())
        assertEquals(disabled, repository.loggingPolicy().settings)
    }

    @Test
    fun `restore revalidates its lease after waiting for the privacy fence`() = runTest {
        repository.record(event(100L, "restore-stale.example"), LoggingSettings())
        val fence = CredentialPrivacyFence()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holding = async { fence.withClear { held.complete(Unit); release.await() } }
        held.await()
        var current = true
        var persisted = false
        val controller = CredentialActivityPrivacyController(
            repository = repository, writer = CredentialActivityWriter(repository, ActivityRevisionBus()),
            fence = fence, authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            persistConfiguration = { persisted = true },
        )
        val pending = async { controller.setLoggingForRestore(LoggingSettings(false, false, false, false)) { current } }
        kotlinx.coroutines.yield()
        current = false
        release.complete(Unit)
        holding.await()

        assertEquals(PrivacyMutationResult.Cancelled, pending.await())
        assertFalse(persisted)
        assertEquals(1, repository.history(searchText = "restore-stale.example").size)
    }

    @Test
    fun `restore post commit clearing failure remains a durable commit requiring reconciliation`() = runTest {
        repository.record(event(100L, "restore-reconcile.example"), LoggingSettings())
        var persisted = false
        var attempts = 0
        val controller = CredentialActivityPrivacyController(
            repository = repository,
            writer = CredentialActivityWriter(repository, ActivityRevisionBus()),
            fence = CredentialPrivacyFence(),
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            persistConfiguration = { persisted = true },
            clearNetworkActivity = { attempts++; error("network custody temporarily unavailable") },
        )
        val result = controller.setLoggingForRestore(LoggingSettings(false, false, false, false))

        assertTrue(result is PrivacyMutationResult.CommittedReconciliationRequired)
        assertTrue(persisted)
        assertEquals(2, attempts)
        assertTrue(repository.history().isEmpty())
    }

    @Test
    fun `logging opt out clears first and remains fail closed when configuration persistence fails`() =
        runTest {
            repository.record(event(101L, "policy.example"), LoggingSettings())
            val bus = ActivityRevisionBus()
            var persisted: LoggingSettings? = null
            val controller = CredentialActivityPrivacyController(
                repository = repository,
                writer = CredentialActivityWriter(repository, bus),
                fence = CredentialPrivacyFence(),
                authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
                persistConfiguration = { settings ->
                    persisted = settings
                    assertFalse(repository.hasDetailedActivity(now.toEpochMilli()))
                    error("credential preferences unavailable")
                },
            )
            val disabled = LoggingSettings(keepCounts = true, keepHistory = false)

            val result = controller.setLogging(disabled)

            assertTrue(result is PrivacyMutationResult.Failed)
            assertEquals(disabled, persisted)
            assertEquals(disabled, repository.loggingPolicy().settings)
            assertFalse(repository.hasDetailedActivity(now.toEpochMilli()))
        }

    @Test
    fun `logging opt in remains disabled until configuration persistence commits`() = runTest {
        val disabled = LoggingSettings(
            keepCounts = false,
            keepHistory = false,
            keepNetworkActivity = false,
            keepLavaGuardProgress = false,
        )
        repository.applyLoggingPolicy(disabled)
        val bus = ActivityRevisionBus()
        val writer = CredentialActivityWriter(repository, bus)
        var writeAcceptedDuringPersistence: Boolean? = null
        val controller = CredentialActivityPrivacyController(
            repository = repository,
            writer = writer,
            fence = CredentialPrivacyFence(),
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            persistConfiguration = { settings ->
                assertEquals(LoggingSettings(), settings)
                assertEquals(disabled, repository.loggingPolicy().settings)
                writeAcceptedDuringPersistence = writer.record(event(102L, "must-stay-disabled.example"))
                error("credential preferences unavailable")
            },
        )

        val result = controller.setLogging(LoggingSettings())

        assertTrue(result is PrivacyMutationResult.Failed)
        assertEquals(false, writeAcceptedDuringPersistence)
        assertEquals(disabled, repository.loggingPolicy().settings)
        assertFalse(repository.hasDetailedActivity(now.toEpochMilli()))
    }

    @Test
    fun `logging opt in admits writes only after configuration persistence returns`() = runTest {
        val disabled = LoggingSettings(
            keepCounts = false,
            keepHistory = false,
            keepNetworkActivity = false,
            keepLavaGuardProgress = false,
        )
        repository.applyLoggingPolicy(disabled)
        val bus = ActivityRevisionBus()
        val writer = CredentialActivityWriter(repository, bus)
        var persistenceCompleted = false
        val controller = CredentialActivityPrivacyController(
            repository = repository,
            writer = writer,
            fence = CredentialPrivacyFence(),
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            persistConfiguration = { settings ->
                assertEquals(LoggingSettings(), settings)
                assertEquals(disabled, repository.loggingPolicy().settings)
                assertFalse(writer.record(event(103L, "not-yet-enabled.example")))
                persistenceCompleted = true
            },
        )

        val result = controller.setLogging(LoggingSettings())

        assertEquals(PrivacyMutationResult.Completed, result)
        assertTrue(persistenceCompleted)
        assertEquals(LoggingSettings(), repository.loggingPolicy().settings)
        assertTrue(writer.record(event(104L, "enabled-after-commit.example")))
    }

    @Test
    fun `durable logging commit retries a failed runtime policy reconciliation`() = runTest {
        val disabled = LoggingSettings(
            keepCounts = false,
            keepHistory = false,
            keepNetworkActivity = false,
            keepLavaGuardProgress = false,
        )
        repository.applyLoggingPolicy(disabled)
        val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
        var durableCommitted = false
        var postCommitAttempts = 0
        val controller = CredentialActivityPrivacyController(
            repository = repository,
            writer = writer,
            fence = CredentialPrivacyFence(),
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            persistConfiguration = { durableCommitted = true },
            applyLoggingPolicy = { settings ->
                if (durableCommitted && settings == LoggingSettings()) {
                    postCommitAttempts += 1
                    if (postCommitAttempts == 1) error("transient SQLite policy failure")
                }
                writer.applyLoggingPolicy(settings)
            },
        )

        val result = controller.setLogging(LoggingSettings())

        assertEquals(PrivacyMutationResult.Completed, result)
        assertEquals(2, postCommitAttempts)
        assertEquals(LoggingSettings(), repository.loggingPolicy().settings)
    }

    @Test
    fun `durable logging commit requests graph reconciliation after bounded policy failures`() =
        runTest {
            val disabled = LoggingSettings(
                keepCounts = false,
                keepHistory = false,
                keepNetworkActivity = false,
                keepLavaGuardProgress = false,
            )
            repository.applyLoggingPolicy(disabled)
            val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
            var durableCommitted = false
            var postCommitAttempts = 0
            val controller = CredentialActivityPrivacyController(
                repository = repository,
                writer = writer,
                fence = CredentialPrivacyFence(),
                authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
                persistConfiguration = { durableCommitted = true },
                applyLoggingPolicy = { settings ->
                    if (durableCommitted && settings == LoggingSettings()) {
                        postCommitAttempts += 1
                        error("persistent SQLite policy failure")
                    }
                    writer.applyLoggingPolicy(settings)
                },
            )

            val result = controller.setLogging(LoggingSettings())

            assertTrue(result is PrivacyMutationResult.CommittedReconciliationRequired)
            assertEquals(2, postCommitAttempts)
            assertEquals(disabled, repository.loggingPolicy().settings)
        }

    @Test
    fun `clear commits exact selected surfaces under the privacy fence`() = runTest {
        repository.record(event(100L, "clear.example"), LoggingSettings())
        val bus = ActivityRevisionBus()
        val controller = CredentialActivityPrivacyController(
            repository = repository,
            writer = CredentialActivityWriter(repository, bus),
            fence = CredentialPrivacyFence(),
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
        )

        assertEquals(
            PrivacyMutationResult.Completed,
            controller.clear(LocalActivityClearSelection(countsAndUptime = false, domainHistory = true)),
        )
        assertFalse(repository.hasDetailedActivity(now.toEpochMilli()))
        assertEquals(
            0L,
            database.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ${ActivityDatabase.DNS_RECEIPT_TABLE}",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            },
        )
        assertEquals(1L, repository.summary(com.lavasec.app.foundation.activity.ActivityDateRange(
            java.time.LocalDate.parse("2026-07-19"), java.time.LocalDate.parse("2026-07-19"),
        )).blockedCount)
        assertTrue(bus.current.value.token > 0L)
    }

    @Test
    fun `disabling Guard retention clears saved progress before committing and does not resurrect it`() = runTest {
        val store = com.lavasec.app.sudoku.SharedPreferencesSudokuGameStore(context)
        store.save(com.lavasec.app.foundation.sudoku.SudokuGame.fresh(
            com.lavasec.app.foundation.sudoku.SudokuPuzzle.generate(42),
        ))
        val controller = CredentialActivityPrivacyController(
            repository = repository,
            writer = CredentialActivityWriter(repository, ActivityRevisionBus()),
            fence = CredentialPrivacyFence(),
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
            persistConfiguration = { assertNull(store.load()) },
            clearGuardProgress = store::clear,
        )
        assertEquals(PrivacyMutationResult.Completed,
            controller.setLogging(LoggingSettings(keepLavaGuardProgress = false)))
        assertNull(store.load())
        assertEquals(PrivacyMutationResult.Completed, controller.setLogging(LoggingSettings()))
        assertNull(store.load())
    }

    @Test
    fun `disabling network activity retention clears the store before the policy commit`() =
        runTest {
            val bus = ActivityRevisionBus()
            val calls = mutableListOf<String>()
            val controller = CredentialActivityPrivacyController(
                repository = repository,
                writer = CredentialActivityWriter(repository, bus),
                fence = CredentialPrivacyFence(),
                authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
                persistConfiguration = { calls += "persist" },
                clearNetworkActivity = {
                    calls += "network-clear"
                    assertEquals(
                        "the store must be empty before durable persistence",
                        listOf("network-clear"),
                        calls,
                    )
                },
            )
            repository.applyLoggingPolicy(LoggingSettings())

            val result = controller.setLogging(LoggingSettings(keepNetworkActivity = false))

            assertEquals(PrivacyMutationResult.Completed, result)
            assertEquals(listOf("network-clear", "persist"), calls)
            assertFalse(repository.loggingPolicy().settings.keepNetworkActivity)

            // Re-committing the same disabled policy must not clear again: the toggle-off
            // clear runs exactly once per true→false transition (iOS setKeepNetworkActivity).
            calls.clear()
            controller.setLogging(LoggingSettings(keepNetworkActivity = false))
            assertEquals(listOf("persist"), calls)
        }

    @Test
    fun `network activity clear is authorized and fenced and publishes the mutation`() =
        runTest {
            val bus = ActivityRevisionBus()
            var cleared = false
            val controller = CredentialActivityPrivacyController(
                repository = repository,
                writer = CredentialActivityWriter(repository, bus),
                fence = CredentialPrivacyFence(),
                authorizer = PrivacyAuthorizer { PrivacyAuthorization.Authorized },
                clearNetworkActivity = { cleared = true },
            )

            assertEquals(PrivacyMutationResult.Completed, controller.clearNetworkActivityEntries())

            assertTrue(cleared)
            assertTrue(bus.current.value.token > 0L)
        }

    @Test
    fun `network activity clear respects a cancelled authorization`() = runTest {
        var cleared = false
        val controller = CredentialActivityPrivacyController(
            repository = repository,
            writer = CredentialActivityWriter(repository, ActivityRevisionBus()),
            fence = CredentialPrivacyFence(),
            authorizer = PrivacyAuthorizer { PrivacyAuthorization.Cancelled },
            clearNetworkActivity = { cleared = true },
        )

        assertEquals(PrivacyMutationResult.Cancelled, controller.clearNetworkActivityEntries())
        assertFalse(cleared)
    }

    @Test
    fun `feedback attachments are absent unless each private source is explicitly selected`() =
        runTest {
            val composer = LocalFeedbackComposer(
                appVersion = { "0.1.0" },
                deviceSummary = { "Android 36" },
                diagnostics = { "resolver=healthy" },
            )

            val plain = composer.compose(
                description = "Something happened",
                includeDiagnostics = false,
                localLogExport = null,
            )
            assertFalse(plain.body.contains("resolver=healthy"))
            assertNull(plain.attachment)

            val handle = LocalExportHandle(UUID.randomUUID().toString())
            val selected = composer.compose(
                description = "Something happened",
                includeDiagnostics = true,
                localLogExport = handle,
            )
            assertTrue(selected.body.contains("resolver=healthy"))
            assertEquals(handle, selected.attachment)
        }

    private fun event(id: Long, domain: String) = DnsDecisionEvent(
        id = id,
        timestampMillis = now.toEpochMilli(),
        normalizedDomain = domain,
        decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
    )
}
