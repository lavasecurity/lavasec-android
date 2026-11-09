package com.lavasec.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.activity.ActivityDateRange
import com.lavasec.app.foundation.activity.ActivityHistoryPolicy
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.platform.LoggingSettings
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ActivityRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: ActivityDatabase
    private lateinit var repository: ActivityRepository

    private val now = Instant.parse("2026-07-19T12:00:00Z")
    private val openMaintenanceTables = listOf(
        ActivityDatabase.EVENT_TABLE,
        ActivityDatabase.DAILY_DOMAIN_TABLE,
        ActivityDatabase.COUNT_EVENT_TABLE,
        ActivityDatabase.PROTECTION_INTERVAL_TABLE,
        ActivityDatabase.PROTECTION_DAILY_TABLE,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `credential writer publishes only committed revisions and never backfills opt out`() {
        val revisions = ActivityRevisionBus()
        val writer = CredentialActivityWriter(repository, revisions)
        val first = event(
            id = 950L,
            timestamp = "2026-07-19T11:59:00Z",
            domain = "signal.example",
            action = FilterAction.BLOCK,
        )

        assertTrue(writer.record(first))
        val committed = revisions.current.value
        assertTrue(committed.token > 0L)
        assertFalse(writer.record(first))
        assertEquals(committed, revisions.current.value)

        writer.applyLoggingPolicy(
            LoggingSettings(keepCounts = false, keepHistory = false),
        )
        val disabled = revisions.current.value
        assertTrue(disabled.token > committed.token)
        assertEquals(1L, disabled.policyRevision)
        assertFalse(
            writer.record(
                event(
                    id = 951L,
                    timestamp = "2026-07-19T12:00:00Z",
                    domain = "dropped.example",
                    action = FilterAction.ALLOW,
                ),
            ),
        )
        assertEquals(disabled, revisions.current.value)
    }

    @Test
    fun `immutable DNS telemetry is idempotent and rejects event id reuse`() {
        val original = event(
            id = 900L,
            timestamp = "2026-07-19T11:59:00Z",
            domain = "once.example",
            action = FilterAction.BLOCK,
        )
        assertTrue(repository.record(original, LoggingSettings()))
        val committed = repository.revisions()

        assertFalse(repository.record(original, LoggingSettings()))
        assertEquals(committed, repository.revisions())
        assertEquals(1L, repository.summary(range("2026-07-19", "2026-07-19")).blockedCount)
        assertEquals(1, repository.history().size)

        assertThrows(IllegalStateException::class.java) {
            repository.record(original.copy(normalizedDomain = "collision.example"), LoggingSettings())
        }
        assertEquals(committed, repository.revisions())
    }

    @Test
    fun `version eight policy migrates with immutable receipt table`() {
        database.close()
        val name = "activity-v8-${UUID.randomUUID()}.sqlite"
        database = ActivityDatabase(context, name)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        val policy = LoggingSettings(
            keepCounts = false,
            keepHistory = true,
            keepNetworkActivity = false,
            keepLavaGuardProgress = false,
        )
        repository.applyLoggingPolicy(policy)
        database.writableDatabase.apply {
            execSQL("DROP TABLE ${ActivityDatabase.DNS_RECEIPT_TABLE}")
            version = 8
        }
        database.close()

        database = ActivityDatabase(context, name)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(10, database.readableDatabase.version)
        assertTrue(database.readableDatabase.hasTable(ActivityDatabase.DNS_RECEIPT_TABLE))
        assertEquals(ActivityLoggingPolicySnapshot(policy, 1L), repository.loggingPolicy())
        context.deleteDatabase(name)
    }

    @Test
    fun `logging policy advances once and clears disabled retained data atomically`() {
        assertEquals(
            ActivityLoggingPolicySnapshot(LoggingSettings(), revision = 0L),
            repository.loggingPolicy(),
        )
        repository.record(
            event(
                id = 901L,
                timestamp = "2026-07-19T11:59:00Z",
                domain = "private.example",
                action = FilterAction.BLOCK,
            ),
            LoggingSettings(),
        )

        val disabled = LoggingSettings(
            keepCounts = true,
            keepHistory = false,
            keepNetworkActivity = true,
        )
        assertEquals(
            ActivityLoggingPolicySnapshot(disabled, revision = 1L),
            repository.applyLoggingPolicy(disabled),
        )
        assertFalse(repository.hasDetailedActivity(now.toEpochMilli()))
        assertEquals(1L, repository.revisions().policyRevision)
        assertTrue(repository.revisions().domainLogRevision > 0L)

        assertEquals(
            ActivityLoggingPolicySnapshot(disabled, revision = 1L),
            repository.applyLoggingPolicy(disabled),
        )
        assertEquals(1L, repository.revisions().policyRevision)
    }

    @Test
    fun `logging policy survives credential database recreation`() {
        val disabled = LoggingSettings(
            keepCounts = false,
            keepHistory = true,
            keepNetworkActivity = false,
            keepLavaGuardProgress = false,
        )
        assertEquals(1L, repository.applyLoggingPolicy(disabled).revision)

        database.close()
        database = ActivityDatabase(context, "activity-policy-${UUID.randomUUID()}.sqlite")
        repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        // A separate database begins at the fail-safe product defaults.
        assertEquals(ActivityLoggingPolicySnapshot(LoggingSettings(), 0L), repository.loggingPolicy())
        database.close()

        val persistedName = "activity-policy-persisted-${UUID.randomUUID()}.sqlite"
        database = ActivityDatabase(context, persistedName)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        repository.applyLoggingPolicy(disabled)
        database.close()
        database = ActivityDatabase(context, persistedName)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(ActivityLoggingPolicySnapshot(disabled, 1L), repository.loggingPolicy())
        context.deleteDatabase(persistedName)
    }

    @Test
    fun `retained Domain Logs availability sees either exact or compact evidence`() {
        assertFalse(repository.hasDetailedActivity(now.toEpochMilli()))

        assertTrue(
            repository.record(
                event(
                    id = 1L,
                    timestamp = "2026-07-19T11:59:00Z",
                    domain = "retained.example",
                    action = FilterAction.ALLOW,
                ),
                LoggingSettings(keepCounts = false, keepHistory = true),
            ),
        )
        assertTrue(repository.hasDetailedActivity(now.toEpochMilli()))

        database.writableDatabase.delete(ActivityDatabase.EVENT_TABLE, null, null)
        assertTrue(repository.hasDetailedActivity(now.toEpochMilli()))

        database.writableDatabase.delete(ActivityDatabase.DAILY_DOMAIN_TABLE, null, null)
        assertFalse(repository.hasDetailedActivity(now.toEpochMilli()))
    }

    @Test
    fun `retained Domain Logs availability ignores expired and future skew rows`() {
        val expiredNow = now.minusMillis(ActivityHistoryPolicy.RETENTION_MILLIS + 172_800_000L)
        repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = Clock.fixed(expiredNow, ZoneOffset.UTC),
        )
        assertTrue(
            repository.record(
                event(
                    id = 2L,
                    timestampMillis = expiredNow.toEpochMilli(),
                    domain = "expired.example",
                    action = FilterAction.BLOCK,
                ),
                LoggingSettings(keepCounts = false, keepHistory = true),
            ),
        )

        val futureNow = now.plusSeconds(2L * 24L * 60L * 60L)
        repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = Clock.fixed(futureNow, ZoneOffset.UTC),
        )
        assertTrue(
            repository.record(
                event(
                    id = 3L,
                    timestampMillis = futureNow.toEpochMilli(),
                    domain = "future.example",
                    action = FilterAction.BLOCK,
                ),
                LoggingSettings(keepCounts = false, keepHistory = true),
            ),
        )

        repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertFalse(repository.hasDetailedActivity(now.toEpochMilli()))
    }

    @Test
    fun `count and history collection are independently respected`() {
        repository.record(
            event(
                id = 1,
                timestamp = "2026-07-18T08:00:00Z",
                domain = "count-only.example",
                action = FilterAction.ALLOW,
            ),
            logging = LoggingSettings(keepCounts = true, keepHistory = false),
        )
        repository.record(
            event(
                id = 2,
                timestamp = "2026-07-19T08:00:00Z",
                domain = "history-only.example",
                action = FilterAction.BLOCK,
            ),
            logging = LoggingSettings(keepCounts = false, keepHistory = true),
        )

        val range = range("2026-07-18", "2026-07-19")
        assertEquals(ActivitySummary(allowedCount = 1, blockedCount = 0), repository.summary(range))
        assertTrue(repository.topDomains(range, FilterAction.ALLOW).isEmpty())
        assertEquals(
            listOf(ActivityDomainFrequency("history-only.example", 1)),
            repository.topDomains(range, FilterAction.BLOCK),
        )
        assertEquals(listOf("history-only.example"), repository.history().map { it.normalizedDomain })

        assertFalse(
            repository.record(
                event(3, "2026-07-19T09:00:00Z", "disabled.example", FilterAction.BLOCK),
                logging = LoggingSettings(keepCounts = false, keepHistory = false),
            ),
        )
        assertEquals(1, repository.history().size)
    }

    @Test
    fun `failed safe decisions are never retained as ordinary Activity evidence`() {
        assertTrue(
            "the pinned iOS fail-closed reason must remain representable in Android",
            FilterDecisionReason.entries.any { it.name == "PROTECTION_UNAVAILABLE" },
        )
        val protectionUnavailable = FilterDecisionReason.valueOf("PROTECTION_UNAVAILABLE")

        assertFalse(
            repository.record(
                event(
                    id = 4L,
                    timestamp = "2026-07-19T09:00:00Z",
                    domain = "failed-safe.example",
                    action = FilterAction.BLOCK,
                    reason = protectionUnavailable,
                ),
                logging = LoggingSettings(keepCounts = true, keepHistory = true),
            ),
        )

        assertEquals(ActivitySummary.EMPTY, repository.summary(range("2026-07-19", "2026-07-19")))
        assertTrue(repository.topDomains(range("2026-07-19", "2026-07-19"), FilterAction.BLOCK).isEmpty())
        assertTrue(repository.history().isEmpty())
        assertEquals(ActivityRepositoryRevisions(0L, 0L), repository.revisions())
    }

    @Test
    fun `durable revisions assign counts to summary and domain logs to both surfaces`() {
        database.close()
        val name = "activity-revisions-${UUID.randomUUID()}.db"
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(ActivityRepositoryRevisions(0L, 0L), repository.revisions())

        assertTrue(
            repository.record(
                event(1, "2026-07-19T08:00:00Z", "count-only.example", FilterAction.ALLOW),
                LoggingSettings(keepCounts = true, keepHistory = false),
            ),
        )
        assertEquals(ActivityRepositoryRevisions(1L, 0L), repository.revisions())

        assertTrue(
            repository.record(
                event(2, "2026-07-19T09:00:00Z", "history-only.example", FilterAction.BLOCK),
                LoggingSettings(keepCounts = false, keepHistory = true),
            ),
        )
        assertEquals(ActivityRepositoryRevisions(2L, 1L), repository.revisions())

        assertTrue(
            repository.record(
                event(3, "2026-07-19T10:00:00Z", "both.example", FilterAction.BLOCK),
                LoggingSettings(keepCounts = true, keepHistory = true),
            ),
        )
        assertEquals(
            "one transaction advances each owned surface only once",
            ActivityRepositoryRevisions(3L, 2L),
            repository.revisions(),
        )

        assertFalse(
            repository.record(
                event(4, "2026-07-19T11:00:00Z", "disabled.example", FilterAction.BLOCK),
                LoggingSettings(keepCounts = false, keepHistory = false),
            ),
        )
        assertEquals(ActivityRepositoryRevisions(3L, 2L), repository.revisions())

        database.close()
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(ActivityRepositoryRevisions(3L, 2L), repository.revisions())

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `uptime revisions advance only when protection evidence changes`() {
        val started = now.minusSeconds(120).toEpochMilli()
        val heartbeat = now.minusSeconds(60).toEpochMilli()

        assertTrue(repository.recordProtectionState(active = true, atMillis = started))
        assertEquals(ActivityRepositoryRevisions(1L, 0L), repository.revisions())

        assertFalse(repository.recordProtectionState(active = true, atMillis = started))
        repository.recordProtectionHeartbeat(atMillis = started)
        assertEquals(
            "duplicate state and already-covered heartbeat are no-op mutations",
            ActivityRepositoryRevisions(1L, 0L),
            repository.revisions(),
        )

        assertTrue(repository.recordProtectionHeartbeat(atMillis = heartbeat))
        assertEquals(ActivityRepositoryRevisions(2L, 0L), repository.revisions())

        repository.recordProtectionHeartbeat(atMillis = started)
        assertEquals(ActivityRepositoryRevisions(2L, 0L), repository.revisions())

        assertTrue(repository.recordProtectionState(active = false, atMillis = now.toEpochMilli()))
        assertEquals(ActivityRepositoryRevisions(3L, 0L), repository.revisions())

        assertFalse(repository.recordProtectionState(active = false, atMillis = now.toEpochMilli()))
        assertEquals(ActivityRepositoryRevisions(3L, 0L), repository.revisions())
    }

    @Test
    fun `backward heartbeat acknowledges actual maximum persisted through timestamp`() {
        val started = now.minusSeconds(120).toEpochMilli()
        val advanced = now.minusSeconds(60).toEpochMilli()
        val backwardRequest = now.minusSeconds(90).toEpochMilli()

        assertTrue(repository.recordProtectionState(active = true, atMillis = started))
        assertEquals(
            advanced,
            repository.recordProtectionHeartbeatOutcome(atMillis = advanced).activeEvidenceThroughMillis,
        )

        val outcome = repository.recordProtectionHeartbeatOutcome(atMillis = backwardRequest)

        assertTrue(outcome.accepted)
        assertEquals(advanced, outcome.activeEvidenceThroughMillis)
    }

    @Test
    fun `detailed prune clear and privacy enforcement revise only when rows change`() {
        val expiredNow = Instant.parse("2026-07-01T12:00:00Z")
        repository = ActivityRepository(
            database,
            ZoneOffset.UTC,
            Clock.fixed(expiredNow, ZoneOffset.UTC),
        )
        repository.record(
            event(1, expiredNow.toEpochMilli(), "expired.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = false, keepHistory = true),
        )
        assertEquals(ActivityRepositoryRevisions(1L, 1L), repository.revisions())

        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(ActivityPruneResult(1, 1), repository.pruneDetailedHistory())
        assertEquals(ActivityRepositoryRevisions(2L, 2L), repository.revisions())

        assertEquals(ActivityPruneResult(0, 0), repository.pruneDetailedHistory())
        assertEquals(ActivityRepositoryRevisions(2L, 2L), repository.revisions())

        repository.record(
            event(2, "2026-07-19T08:00:00Z", "private.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = true, keepHistory = true),
        )
        assertEquals(ActivityRepositoryRevisions(3L, 3L), repository.revisions())

        repository.deleteDetailedActivity()
        assertEquals(ActivityRepositoryRevisions(4L, 4L), repository.revisions())
        repository.deleteDetailedActivity()
        assertEquals(ActivityRepositoryRevisions(4L, 4L), repository.revisions())

        assertTrue(
            repository.enforceLoggingSettings(
                LoggingSettings(keepCounts = false, keepHistory = true),
            ),
        )
        assertEquals(ActivityRepositoryRevisions(5L, 4L), repository.revisions())
        assertFalse(
            repository.enforceLoggingSettings(
                LoggingSettings(keepCounts = false, keepHistory = true),
            ),
        )
        assertEquals(ActivityRepositoryRevisions(5L, 4L), repository.revisions())

        repository.deleteAll()
        assertEquals(ActivityRepositoryRevisions(5L, 4L), repository.revisions())
    }

    @Test
    fun `repository preflight revises summary when exact uptime pruning changes timezone visibility`() {
        val pagoPago = ZoneId.of("Pacific/Pago_Pago")
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val started = Instant.parse("2026-07-20T10:59:57Z").toEpochMilli()
        val stopped = Instant.parse("2026-07-20T10:59:59Z").toEpochMilli()
        val correctedNow = Instant.parse("2026-07-20T11:05:01Z")

        repository = ActivityRepository(
            database,
            pagoPago,
            Clock.fixed(Instant.ofEpochMilli(stopped), ZoneOffset.UTC),
        )
        assertTrue(repository.recordProtectionState(active = true, atMillis = started))
        assertTrue(repository.recordProtectionState(active = false, atMillis = stopped))
        assertEquals(ActivityRepositoryRevisions(2L, 0L), repository.revisions())
        assertEquals(1L, rowCount(database, ActivityDatabase.PROTECTION_INTERVAL_TABLE))

        repository = ActivityRepository(
            database,
            kiritimati,
            Clock.fixed(correctedNow, ZoneOffset.UTC),
        )
        val selectedDay = range("2026-07-19", "2026-07-19")
        assertEquals(0L, repository.summary(selectedDay).localProtectionUptimeMillis)

        assertFalse(
            "inactive state is a no-op apart from exact-evidence maintenance",
            repository.recordProtectionState(active = false, atMillis = correctedNow.toEpochMilli()),
        )

        assertEquals(0L, rowCount(database, ActivityDatabase.PROTECTION_INTERVAL_TABLE))
        assertEquals(2_000L, repository.summary(selectedDay).localProtectionUptimeMillis)
        assertEquals(ActivityRepositoryRevisions(3L, 0L), repository.revisions())
    }

    @Test
    fun `explicit detailed prune revises summary once for exact and detailed deletion together`() {
        val recordedAt = Instant.parse("2026-07-01T10:00:00Z")
        val stoppedAt = recordedAt.plusSeconds(2)
        repository = ActivityRepository(
            database,
            ZoneOffset.UTC,
            Clock.fixed(stoppedAt, ZoneOffset.UTC),
        )
        repository.record(
            event(1, recordedAt.toEpochMilli(), "expired.example", FilterAction.BLOCK),
            LoggingSettings(),
        )
        repository.recordProtectionState(active = true, atMillis = recordedAt.toEpochMilli())
        repository.recordProtectionState(active = false, atMillis = stoppedAt.toEpochMilli())
        assertEquals(ActivityRepositoryRevisions(3L, 1L), repository.revisions())

        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(ActivityPruneResult(1, 1), repository.pruneDetailedHistory())
        assertEquals(
            "exact count and uptime evidence share the detailed-prune surface revision",
            ActivityRepositoryRevisions(4L, 2L),
            repository.revisions(),
        )
        assertEquals(0L, rowCount(database, ActivityDatabase.COUNT_EVENT_TABLE))
        assertEquals(0L, rowCount(database, ActivityDatabase.PROTECTION_INTERVAL_TABLE))
    }

    @Test
    fun `database open revises summary when it prunes exact evidence only`() {
        database.close()
        val name = "activity-exact-on-open-${UUID.randomUUID()}.db"
        val pagoPago = ZoneId.of("Pacific/Pago_Pago")
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val started = Instant.parse("2026-07-20T10:59:57Z").toEpochMilli()
        val stopped = Instant.parse("2026-07-20T10:59:59Z").toEpochMilli()
        val correctedNow = Instant.parse("2026-07-20T11:05:01Z")
        database = ActivityDatabase(context, name, pruneNowMillis = { stopped })
        repository = ActivityRepository(
            database,
            pagoPago,
            Clock.fixed(Instant.ofEpochMilli(stopped), ZoneOffset.UTC),
        )
        repository.recordProtectionState(active = true, atMillis = started)
        repository.recordProtectionState(active = false, atMillis = stopped)
        assertEquals(ActivityRepositoryRevisions(2L, 0L), repository.revisions())
        database.close()

        database = ActivityDatabase(context, name, pruneNowMillis = correctedNow::toEpochMilli)
        repository = ActivityRepository(database, kiritimati, Clock.fixed(correctedNow, ZoneOffset.UTC))

        assertEquals(ActivityRepositoryRevisions(3L, 0L), repository.revisions())
        assertEquals(0L, rowCount(database, ActivityDatabase.PROTECTION_INTERVAL_TABLE))
        assertEquals(
            2_000L,
            repository.summary(range("2026-07-19", "2026-07-19")).localProtectionUptimeMillis,
        )

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `open maintenance at max minus one prunes every surface with one revision advance`() {
        database.close()
        val name = "activity-open-maintenance-max-minus-one-${UUID.randomUUID()}.db"
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        seedPendingOpenMaintenanceRows(
            database = database,
            summaryRevision = Long.MAX_VALUE - 1L,
            domainRevision = 7L,
        )
        database.close()

        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(ActivityRepositoryRevisions(Long.MAX_VALUE, 8L), repository.revisions())
        assertEquals(
            openMaintenanceTables.associateWith { 0L },
            openMaintenanceRowCounts(database),
        )
        database.close()

        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(ActivityRepositoryRevisions(Long.MAX_VALUE, 8L), repository.revisions())
        assertEquals(
            openMaintenanceTables.associateWith { 0L },
            openMaintenanceRowCounts(database),
        )

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `saturated open maintenance defers every surface hidden across reopens`() {
        database.close()
        val name = "activity-open-maintenance-max-${UUID.randomUUID()}.db"
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        seedPendingOpenMaintenanceRows(
            database = database,
            summaryRevision = Long.MAX_VALUE,
            domainRevision = 7L,
        )
        database.close()

        repeat(2) {
            database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
            repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

            assertOpenMaintenanceDeferred(
                expectedRevisions = ActivityRepositoryRevisions(Long.MAX_VALUE, 7L),
            )
            database.close()
        }

        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `saturated domain revision defers open maintenance hidden across reopens`() {
        database.close()
        val name = "activity-open-maintenance-domain-max-${UUID.randomUUID()}.db"
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        seedPendingOpenMaintenanceRows(
            database = database,
            summaryRevision = 41L,
            domainRevision = Long.MAX_VALUE,
        )
        database.close()

        repeat(2) {
            database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
            repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

            assertOpenMaintenanceDeferred(
                expectedRevisions = ActivityRepositoryRevisions(41L, Long.MAX_VALUE),
            )
            database.close()
        }

        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `saturated summary revision rolls back count mutation and survives reopen`() {
        database.close()
        val name = "activity-summary-revision-max-${UUID.randomUUID()}.db"
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        database.writableDatabase.execSQL(
            "UPDATE ${ActivityDatabase.REVISION_TABLE} SET summary_count_revision = ? WHERE singleton = 1",
            arrayOf<Any>(Long.MAX_VALUE),
        )

        assertThrows(IllegalStateException::class.java) {
            repository.record(
                event(1, now.toEpochMilli(), "summary-max.example", FilterAction.ALLOW),
                LoggingSettings(keepCounts = true, keepHistory = false),
            )
        }
        assertEquals(0L, rowCount(database, ActivityDatabase.DAILY_COUNT_TABLE))
        assertEquals(0L, rowCount(database, ActivityDatabase.COUNT_EVENT_TABLE))
        assertEquals(ActivityRepositoryRevisions(Long.MAX_VALUE, 0L), repository.revisions())
        database.close()

        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(ActivityRepositoryRevisions(Long.MAX_VALUE, 0L), repository.revisions())
        assertEquals(0L, rowCount(database, ActivityDatabase.DAILY_COUNT_TABLE))
        assertEquals(0L, rowCount(database, ActivityDatabase.COUNT_EVENT_TABLE))
        assertEquals(
            "integer",
            revisionStorageType(database, "summary_count_revision"),
        )

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `saturated domain revision rolls back history mutation and survives reopen`() {
        database.close()
        val name = "activity-domain-revision-max-${UUID.randomUUID()}.db"
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        database.writableDatabase.execSQL(
            "UPDATE ${ActivityDatabase.REVISION_TABLE} SET domain_log_revision = ? WHERE singleton = 1",
            arrayOf<Any>(Long.MAX_VALUE),
        )

        assertThrows(IllegalStateException::class.java) {
            repository.record(
                event(1, now.toEpochMilli(), "domain-max.example", FilterAction.BLOCK),
                LoggingSettings(keepCounts = false, keepHistory = true),
            )
        }
        assertEquals(0L, rowCount(database, ActivityDatabase.EVENT_TABLE))
        assertEquals(0L, rowCount(database, ActivityDatabase.DAILY_DOMAIN_TABLE))
        assertEquals(ActivityRepositoryRevisions(0L, Long.MAX_VALUE), repository.revisions())
        database.close()

        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(ActivityRepositoryRevisions(0L, Long.MAX_VALUE), repository.revisions())
        assertEquals(0L, rowCount(database, ActivityDatabase.EVENT_TABLE))
        assertEquals(0L, rowCount(database, ActivityDatabase.DAILY_DOMAIN_TABLE))
        assertEquals(
            "integer",
            revisionStorageType(database, "domain_log_revision"),
        )

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `seven day prune removes detailed rows but preserves durable daily totals`() {
        val expired = event(1, "2026-07-01T09:00:00Z", "old.example", FilterAction.BLOCK)
        val retained = event(2, "2026-07-19T09:00:00Z", "current.example", FilterAction.ALLOW)
        repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = Clock.fixed(Instant.parse("2026-07-01T09:00:00Z"), ZoneOffset.UTC),
        )
        repository.record(expired, LoggingSettings())
        repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        repository.record(retained, LoggingSettings())

        assertTrue(
            "read boundaries must hide expired detail even before the worker physically prunes it",
            repository.topDomains(range("2026-07-01", "2026-07-01"), FilterAction.BLOCK).isEmpty(),
        )
        val result = repository.pruneDetailedHistory(now.toEpochMilli())

        assertEquals(ActivityPruneResult(eventRowsDeleted = 1, domainRowsDeleted = 1), result)
        assertEquals(
            ActivitySummary(allowedCount = 0, blockedCount = 1),
            repository.summary(range("2026-07-01", "2026-07-01")),
        )
        assertTrue(repository.topDomains(range("2026-07-01", "2026-07-01"), FilterAction.BLOCK).isEmpty())
        assertEquals(listOf("current.example"), repository.history().map { it.normalizedDomain })
    }

    @Test
    fun `clock-forward detail is hidden then physically pruned after wall clock correction`() {
        val skewedNow = now.plusSeconds(2 * 24 * 60 * 60L)
        repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = Clock.fixed(skewedNow, ZoneOffset.UTC),
        )
        assertTrue(
            repository.record(
                event(
                    id = 51,
                    timestampMillis = skewedNow.toEpochMilli(),
                    domain = "future-skew.example",
                    action = FilterAction.BLOCK,
                ),
                LoggingSettings(keepCounts = false, keepHistory = true),
            ),
        )

        repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        assertTrue(repository.history().isEmpty())
        assertTrue(
            repository.topDomains(
                range("2026-07-19", "2026-07-21"),
                FilterAction.BLOCK,
            ).isEmpty(),
        )
        assertFalse(repository.hasDetailedActivity())
        assertEquals(
            ActivityPruneResult(eventRowsDeleted = 1, domainRowsDeleted = 1),
            repository.pruneDetailedHistory(),
        )
    }

    @Test
    fun `history append rejects stale and implausibly future queued detail`() {
        val detailOnly = LoggingSettings(keepCounts = false, keepHistory = true)
        assertFalse(
            repository.record(
                event(
                    id = 61,
                    timestampMillis = now.toEpochMilli() +
                        ActivityHistoryPolicy.FUTURE_TIMESTAMP_TOLERANCE_MILLIS + 1L,
                    domain = "queued-future.example",
                    action = FilterAction.ALLOW,
                ),
                detailOnly,
            ),
        )
        assertFalse(
            repository.record(
                event(
                    id = 62,
                    timestampMillis = now.toEpochMilli() - ActivityHistoryPolicy.RETENTION_MILLIS -
                        2L * 24L * 60L * 60L * 1_000L,
                    domain = "queued-stale.example",
                    action = FilterAction.ALLOW,
                ),
                detailOnly,
            ),
        )
        assertFalse(repository.hasDetailedActivity())
    }

    @Test
    fun `future-tolerated digest evidence waits for the query watermark then appears`() {
        val mutableClock = MutableClock(now.toEpochMilli())
        repository = ActivityRepository(
            database = database,
            zoneId = ZoneOffset.UTC,
            clock = mutableClock,
        )
        val futureTimestamp = now.plusSeconds(60).toEpochMilli()
        val selectedDay = range("2026-07-19", "2026-07-19")

        assertTrue(
            repository.record(
                event(63, futureTimestamp, "future-digest.example", FilterAction.BLOCK),
                LoggingSettings(),
            ),
        )
        assertEquals(ActivitySummary.EMPTY, repository.summary(selectedDay))
        assertTrue(repository.topDomains(selectedDay, FilterAction.BLOCK).isEmpty())

        mutableClock.advanceMillis(60_000L)

        assertEquals(
            ActivitySummary(allowedCount = 0, blockedCount = 1),
            repository.summary(selectedDay),
        )
        assertEquals(
            listOf(ActivityDomainFrequency("future-digest.example", 1)),
            repository.topDomains(selectedDay, FilterAction.BLOCK),
        )
    }

    @Test
    fun `summary and top domains honor inclusive local calendar ranges`() {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        repository = ActivityRepository(
            database = database,
            zoneId = losAngeles,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        repository.record(event(1, "2026-07-19T06:30:00Z", "prior-day.example", FilterAction.BLOCK), LoggingSettings())
        repository.record(event(2, "2026-07-19T08:30:00Z", "today.example", FilterAction.ALLOW), LoggingSettings())
        repository.record(event(3, "2026-07-19T09:30:00Z", "today.example", FilterAction.ALLOW), LoggingSettings())

        assertEquals(
            ActivitySummary(allowedCount = 0, blockedCount = 1),
            repository.summary(range("2026-07-18", "2026-07-18")),
        )
        assertEquals(
            ActivitySummary(allowedCount = 2, blockedCount = 0),
            repository.summary(range("2026-07-19", "2026-07-19")),
        )
        assertEquals(
            listOf(ActivityDomainFrequency("today.example", 2)),
            repository.topDomains(range("2026-07-19", "2026-07-19"), FilterAction.ALLOW),
        )
    }

    @Test
    fun `top domains rank by count then domain and exclude paused allows`() {
        val logging = LoggingSettings(keepCounts = false, keepHistory = true)
        repository.record(event(1, "2026-07-19T01:00:00Z", "zeta.example", FilterAction.BLOCK), logging)
        repository.record(event(2, "2026-07-19T02:00:00Z", "alpha.example", FilterAction.BLOCK), logging)
        repository.record(event(3, "2026-07-19T03:00:00Z", "zeta.example", FilterAction.BLOCK), logging)
        repository.record(event(4, "2026-07-19T04:00:00Z", "alpha.example", FilterAction.BLOCK), logging)
        repository.record(event(5, "2026-07-19T05:00:00Z", "beta.example", FilterAction.BLOCK), logging)
        repository.record(
            event(
                id = 6,
                timestamp = "2026-07-19T06:00:00Z",
                domain = "paused.example",
                action = FilterAction.ALLOW,
                reason = FilterDecisionReason.PAUSED_ALLOW,
            ),
            logging,
        )

        assertEquals(
            listOf(
                ActivityDomainFrequency("alpha.example", 2),
                ActivityDomainFrequency("zeta.example", 2),
                ActivityDomainFrequency("beta.example", 1),
            ),
            repository.topDomains(range("2026-07-19", "2026-07-19"), FilterAction.BLOCK),
        )
        assertEquals(
            listOf(ActivityDomainFrequency("zeta.example", 2)),
            repository.topDomains(
                range = range("2026-07-19", "2026-07-19"),
                action = FilterAction.BLOCK,
                searchText = "ZETA",
            ),
        )
        assertTrue(repository.topDomains(range("2026-07-19", "2026-07-19"), FilterAction.ALLOW).isEmpty())
    }

    @Test
    fun `history is rolling seven days newest first with stable ties filters and search`() {
        val cutoff = now.toEpochMilli() - ActivityHistoryPolicy.RETENTION_MILLIS
        repository.record(event(40, cutoff - 1, "expired.example", FilterAction.BLOCK), LoggingSettings())
        repository.record(event(99, cutoff, "edge.example", FilterAction.BLOCK), LoggingSettings())
        repository.record(
            event(900, now.toEpochMilli() - 1_000, "first-tie.example", FilterAction.ALLOW),
            LoggingSettings(),
        )
        repository.record(
            event(1, now.toEpochMilli() - 1_000, "second-tie.example", FilterAction.ALLOW),
            LoggingSettings(),
        )
        repository.record(event(2, now.toEpochMilli(), "newest.example", FilterAction.BLOCK), LoggingSettings())

        assertEquals(
            listOf("newest.example", "second-tie.example", "first-tie.example", "edge.example"),
            repository.history().map { it.normalizedDomain },
        )
        assertEquals(
            listOf("second-tie.example", "first-tie.example"),
            repository.history(action = FilterAction.ALLOW, searchText = "TIE").map { it.normalizedDomain },
        )
        assertEquals(listOf("newest.example"), repository.history(limit = 1).map { it.normalizedDomain })
    }

    @Test
    fun `Domain History drains the whole retained window through stable keyset pages`() {
        val timestamp = now.minusSeconds(60).toEpochMilli()
        repeat(303) { index ->
            repository.record(
                event(
                    index.toLong(),
                    timestamp - (index % 3),
                    "domain-$index.example",
                    if (index % 2 == 0) FilterAction.BLOCK else FilterAction.ALLOW,
                ),
                LoggingSettings(keepCounts = false, keepHistory = true),
            )
        }

        val drained = mutableListOf<DnsDecisionEvent>()
        var cursor: ActivityHistoryCursor? = null
        do {
            val page = repository.historyPage(before = cursor, limit = 37)
            drained += page.entries
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(303, drained.size)
        assertEquals(303, drained.map { it.id }.toSet().size)
        assertEquals(303L, rowCount(database, ActivityDatabase.EVENT_TABLE))
        assertEquals(
            drained.sortedWith(compareByDescending<DnsDecisionEvent> { it.timestampMillis }.thenByDescending { it.id }),
            drained,
        )
    }

    @Test
    fun `stable Domain History orders timestamp ties by id and exposes only a thirty row page`() {
        val timestamp = now.minusSeconds(60).toEpochMilli()
        repeat(31) { index ->
            repository.record(
                event(index.toLong(), timestamp, "tie-$index.example", FilterAction.BLOCK),
                LoggingSettings(keepCounts = false, keepHistory = true),
            )
        }

        val first = repository.domainHistoryPage(action = FilterAction.BLOCK)

        assertEquals((31L downTo 2L).toList(), first.rows.map { it.id })
        assertEquals(ActivityHistoryCut(maxRowId = 31L, capturedAtMillis = now.toEpochMilli()), first.cut)
        assertEquals(
            ActivityHistoryPageToken(
                cut = first.cut,
                beforeExclusive = ActivityHistoryCursor(timestampMillis = timestamp, id = 2L),
            ),
            first.next,
        )

        val second = repository.domainHistoryPage(action = FilterAction.BLOCK, token = first.next)
        assertEquals(listOf(1L), second.rows.map { it.id })
        assertEquals(first.cut, second.cut)
        assertEquals(null, second.next)
    }

    @Test
    fun `exactly thirty stable Domain History rows do not advertise another page`() {
        repeat(30) { index ->
            repository.record(
                event(
                    index.toLong(),
                    now.minusSeconds(index.toLong()).toEpochMilli(),
                    "exact-$index.example",
                    FilterAction.BLOCK,
                ),
                LoggingSettings(keepCounts = false, keepHistory = true),
            )
        }

        val page = repository.domainHistoryPage(action = FilterAction.BLOCK)

        assertEquals(30, page.rows.size)
        assertEquals(null, page.next)
    }

    @Test
    fun `stable Domain History continuation excludes inserts after page one`() {
        val timestamp = now.minusSeconds(60).toEpochMilli()
        repeat(61) { index ->
            repository.record(
                event(index.toLong(), timestamp, "original-$index.example", FilterAction.BLOCK),
                LoggingSettings(keepCounts = false, keepHistory = true),
            )
        }
        val first = repository.domainHistoryPage(action = FilterAction.BLOCK)
        repository.record(
            event(10_000L, timestamp - 1L, "post-cut.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = false, keepHistory = true),
        )

        val drained = first.rows.toMutableList()
        var token = first.next
        while (token != null) {
            val page = repository.domainHistoryPage(action = FilterAction.BLOCK, token = token)
            assertEquals(first.cut, page.cut)
            drained += page.rows
            token = page.next
        }

        assertEquals(61, drained.size)
        assertTrue(drained.all { it.id <= first.cut.maxRowId })
        assertFalse(drained.any { it.normalizedDomain == "post-cut.example" })
    }

    @Test
    fun `stable Domain History cut survives detailed clear`() {
        val timestamp = now.minusSeconds(60).toEpochMilli()
        repeat(31) { index ->
            repository.record(
                event(index.toLong(), timestamp, "clear-$index.example", FilterAction.BLOCK),
                LoggingSettings(keepCounts = false, keepHistory = true),
            )
        }
        val first = repository.domainHistoryPage(action = FilterAction.BLOCK)

        repository.deleteDetailedActivity()
        repository.record(
            event(10_000L, timestamp - 1L, "after-clear.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = false, keepHistory = true),
        )
        val continued = repository.domainHistoryPage(action = FilterAction.BLOCK, token = first.next)

        assertEquals(first.cut, continued.cut)
        assertTrue(continued.rows.isEmpty())
        assertEquals(null, continued.next)
    }

    @Test
    fun `stable Domain History cut survives detailed prune`() {
        val timestamp = now.minusSeconds(60).toEpochMilli()
        repeat(31) { index ->
            repository.record(
                event(index.toLong(), timestamp, "prune-$index.example", FilterAction.BLOCK),
                LoggingSettings(keepCounts = false, keepHistory = true),
            )
        }
        val first = repository.domainHistoryPage(action = FilterAction.BLOCK)

        val afterRetention = now.toEpochMilli() + ActivityHistoryPolicy.RETENTION_MILLIS + 1L
        assertEquals(ActivityPruneResult(31, 0), repository.pruneDetailedHistory(afterRetention))
        val continued = repository.domainHistoryPage(action = FilterAction.BLOCK, token = first.next)

        assertEquals(first.cut, continued.cut)
        assertTrue(continued.rows.isEmpty())
        assertEquals(null, continued.next)
    }

    @Test
    fun `Top Domains uses exact live-day evidence despite bounded Space Saving aggregates`() {
        val logging = LoggingSettings(keepCounts = false, keepHistory = true)
        repeat(ActivityRepository.TOP_DOMAIN_CAPACITY) { index ->
            repository.record(
                event(index.toLong(), now.toEpochMilli(), "noise-%03d.example".format(index), FilterAction.BLOCK),
                logging,
            )
        }
        repeat(6) { index ->
            repository.record(
                event(1_000L + index, now.toEpochMilli(), "late-heavy.example", FilterAction.BLOCK),
                logging,
            )
        }

        assertEquals(
            ActivityRepository.TOP_DOMAIN_CAPACITY.toLong(),
            rowCountWhere(
                database,
                ActivityDatabase.DAILY_DOMAIN_TABLE,
                "day_key = ? AND action = ?",
                arrayOf("2026-07-19", FilterAction.BLOCK.name),
            ),
        )
        assertEquals(
            ActivityDomainFrequency(domain = "late-heavy.example", count = 6, errorFloor = 0),
            repository.topDomains(
                range("2026-07-19", "2026-07-19"),
                FilterAction.BLOCK,
                searchText = "late-heavy",
            ).single(),
        )
    }

    @Test
    fun `repository survives reopen and delete all erases every activity surface`() {
        database.close()
        val name = "activity-${UUID.randomUUID()}.db"
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        repository.record(event(1, "2026-07-19T08:00:00Z", "durable.example", FilterAction.BLOCK), LoggingSettings())
        database.close()

        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(1, repository.summary(range("2026-07-19", "2026-07-19")).blockedCount)
        assertEquals(listOf("durable.example"), repository.history().map { it.normalizedDomain })

        repository.deleteAll()

        assertEquals(ActivitySummary.EMPTY, repository.summary(range("2026-07-19", "2026-07-19")))
        assertTrue(repository.history().isEmpty())
        assertTrue(repository.topDomains(range("2026-07-19", "2026-07-19"), FilterAction.BLOCK).isEmpty())
        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `export snapshot is explicit local and read only`() {
        repository.record(event(1, "2026-07-19T08:00:00Z", "allowed.example", FilterAction.ALLOW), LoggingSettings())
        repository.record(event(2, "2026-07-19T09:00:00Z", "blocked.example", FilterAction.BLOCK), LoggingSettings())
        val range = range("2026-07-19", "2026-07-19")

        val exported = repository.exportSnapshot(range)

        assertEquals(now.toEpochMilli(), exported.generatedAtMillis)
        assertEquals(ActivitySummary(1, 1), exported.summary)
        assertEquals(listOf(ActivityDomainFrequency("allowed.example", 1)), exported.allowedTopDomains)
        assertEquals(listOf(ActivityDomainFrequency("blocked.example", 1)), exported.blockedTopDomains)
        assertEquals(listOf("blocked.example", "allowed.example"), exported.history.map { it.normalizedDomain })
        assertEquals(exported, repository.exportSnapshot(range))
        assertEquals(ActivitySummary(1, 1), repository.summary(range))
    }

    @Test
    fun `export summary is clipped to its internally captured generation instant`() {
        val day = range("2026-07-19", "2026-07-19")
        val started = Instant.parse("2026-07-19T11:00:00Z").toEpochMilli()
        val lastEvidence = Instant.parse("2026-07-19T11:30:00Z").toEpochMilli()
        val generatedAt = Instant.parse("2026-07-19T11:15:00Z").toEpochMilli()
        repository.recordProtectionState(active = true, atMillis = started)
        repository.recordProtectionHeartbeat(atMillis = lastEvidence)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(Instant.ofEpochMilli(generatedAt), ZoneOffset.UTC))

        val exported = repository.exportSnapshot(day)

        assertEquals(15 * 60_000L, exported.summary.localProtectionUptimeMillis)
        assertEquals(generatedAt, exported.generatedAtMillis)
    }

    @Test
    fun `export excludes every Activity fact after its internally captured generation instant`() {
        val selectedDay = range("2026-07-19", "2026-07-19")
        val generatedAt = Instant.parse("2026-07-19T11:15:00Z").toEpochMilli()
        val before = Instant.parse("2026-07-19T11:00:00Z").toEpochMilli()
        val after = Instant.parse("2026-07-19T11:30:00Z").toEpochMilli()
        repository.record(event(1, before, "before.example", FilterAction.BLOCK), LoggingSettings())
        repository.record(event(2, after, "after.example", FilterAction.ALLOW), LoggingSettings())
        repository.recordProtectionState(active = true, atMillis = before - 10 * 60_000L)
        repository.recordProtectionState(active = false, atMillis = before)
        repository.recordProtectionState(active = true, atMillis = after - 5 * 60_000L)
        repository.recordProtectionState(active = false, atMillis = after)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(Instant.ofEpochMilli(generatedAt), ZoneOffset.UTC))

        val exported = repository.exportSnapshot(selectedDay)

        assertEquals(ActivitySummary(0, 1, 10 * 60_000L), exported.summary)
        assertTrue(exported.allowedTopDomains.isEmpty())
        assertEquals(
            listOf(ActivityDomainFrequency("before.example", 1)),
            exported.blockedTopDomains,
        )
        assertEquals(listOf("before.example"), exported.history.map { it.normalizedDomain })
    }

    @Test
    @Config(sdk = [28])
    fun `export paging never reserves the live writer and survives concurrent clear and append`() {
        database.close()
        val name = "activity-export-concurrency-${UUID.randomUUID()}.db"
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        repeat(3) { index ->
            repository.record(
                event(
                    index.toLong(),
                    now.minusSeconds(index.toLong()).toEpochMilli(),
                    "snapshot-$index.example",
                    FilterAction.BLOCK,
                ),
                LoggingSettings(),
            )
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val captureWriterStarted = CountDownLatch(1)
            val captureWriterFinished = CountDownLatch(1)
            repository.exportCaptureBarrier = {
                executor.execute {
                    captureWriterStarted.countDown()
                    repository.record(
                        event(98, now.toEpochMilli(), "during-capture.example", FilterAction.ALLOW),
                        LoggingSettings(),
                    )
                    captureWriterFinished.countDown()
                }
                assertTrue(captureWriterStarted.await(1, TimeUnit.SECONDS))
                assertTrue(
                    "the pinned WAL snapshot must not reserve the live writer",
                    captureWriterFinished.await(2, TimeUnit.SECONDS),
                )
            }
            repository.withExportSnapshot(range("2026-07-19", "2026-07-19")) { header, page ->
                val callbackWriterFinished = CountDownLatch(1)
                executor.execute {
                    repository.deleteAll()
                    repository.record(
                        event(99, now.toEpochMilli(), "fresh.example", FilterAction.ALLOW),
                        LoggingSettings(),
                    )
                    callbackWriterFinished.countDown()
                }
                assertTrue(
                    "the export callback must not hold a writer reservation",
                    callbackWriterFinished.await(2, TimeUnit.SECONDS),
                )
                assertEquals(ActivitySummary(0, 3), header.summary)
                assertEquals(
                    listOf("snapshot-0.example", "snapshot-1.example", "snapshot-2.example"),
                    page(null, 10).entries.map { it.normalizedDomain },
                )
            }
        } finally {
            repository.exportCaptureBarrier = null
            executor.shutdownNow()
        }
        assertEquals(listOf("fresh.example"), repository.history().map { it.normalizedDomain })

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `materialized export convenience drains more than one thousand retained rows`() {
        val timestamp = now.minusSeconds(60).toEpochMilli()
        repeat(ActivityRepository.MAX_HISTORY_LIMIT + 7) { index ->
            repository.record(
                event(
                    index.toLong(),
                    timestamp - index,
                    "export-$index.example",
                    FilterAction.BLOCK,
                ),
                LoggingSettings(keepCounts = false, keepHistory = true),
            )
        }

        val exported = repository.exportSnapshot(range("2026-07-19", "2026-07-19"))

        assertEquals(ActivityRepository.MAX_HISTORY_LIMIT + 7, exported.history.size)
        assertEquals(ActivityRepository.MAX_HISTORY_LIMIT + 7, exported.history.map { it.id }.toSet().size)
    }

    @Test
    fun `ordinary mutation after local midnight retains exact evidence through the rollback horizon`() {
        var currentInstant = Instant.parse("2026-07-19T23:59:00Z")
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = currentInstant
        }
        repository = ActivityRepository(database, ZoneOffset.UTC, advancingClock)
        repository.record(
            event(1, currentInstant.toEpochMilli(), "count.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = true, keepHistory = false),
        )
        repository.recordProtectionState(active = true, atMillis = currentInstant.minusSeconds(5 * 60).toEpochMilli())
        repository.recordProtectionState(active = false, atMillis = currentInstant.toEpochMilli())
        assertEquals(1L, database.readableDatabase.rowCount(ActivityDatabase.COUNT_EVENT_TABLE))
        assertEquals(1L, database.readableDatabase.rowCount(ActivityDatabase.PROTECTION_INTERVAL_TABLE))

        currentInstant = Instant.parse("2026-07-20T00:01:00Z")
        repository.record(
            event(2, currentInstant.toEpochMilli(), "next-day.example", FilterAction.ALLOW),
            LoggingSettings(keepCounts = true, keepHistory = false),
        )

        assertEquals(2L, database.readableDatabase.rowCount(ActivityDatabase.COUNT_EVENT_TABLE))
        assertEquals(1L, database.readableDatabase.rowCount(ActivityDatabase.PROTECTION_INTERVAL_TABLE))
        assertEquals(
            ActivitySummary(0, 1, 5 * 60_000L),
            repository.summary(
                range("2026-07-19", "2026-07-19"),
                nowMillis = currentInstant.toEpochMilli(),
            ),
        )
    }

    @Test
    fun `midnight rollback keeps exact count and uptime evidence through the safety horizon`() {
        var currentInstant = Instant.parse("2026-07-19T23:59:59Z")
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = currentInstant
        }
        repository = ActivityRepository(database, ZoneOffset.UTC, advancingClock)
        val selectedDay = range("2026-07-19", "2026-07-19")

        repository.record(
            event(1, currentInstant.toEpochMilli(), "midnight.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = true, keepHistory = false),
        )
        repository.recordProtectionState(
            active = true,
            atMillis = currentInstant.minusSeconds(2).toEpochMilli(),
        )
        repository.recordProtectionState(active = false, atMillis = currentInstant.toEpochMilli())

        currentInstant = Instant.parse("2026-07-20T00:00:01Z")
        repository.record(
            event(2, currentInstant.toEpochMilli(), "next-day.example", FilterAction.ALLOW),
            LoggingSettings(keepCounts = true, keepHistory = false),
        )

        currentInstant = Instant.parse("2026-07-19T23:59:58Z")
        val expectedAtRollback = ActivitySummary(
            allowedCount = 0,
            blockedCount = 0,
            localProtectionUptimeMillis = 1_000L,
        )
        assertEquals(expectedAtRollback, repository.summary(selectedDay))
        assertEquals(expectedAtRollback, repository.exportSnapshot(selectedDay).summary)
    }

    @Test
    fun `midnight evidence compacts after the safety horizon despite the daily cache`() {
        var currentInstant = Instant.parse("2026-07-19T23:59:59Z")
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = currentInstant
        }
        repository = ActivityRepository(database, ZoneOffset.UTC, advancingClock)
        val selectedDay = range("2026-07-19", "2026-07-19")

        repository.record(
            event(1, currentInstant.toEpochMilli(), "midnight.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = true, keepHistory = false),
        )
        repository.recordProtectionState(
            active = true,
            atMillis = currentInstant.minusSeconds(2).toEpochMilli(),
        )
        repository.recordProtectionState(active = false, atMillis = currentInstant.toEpochMilli())

        currentInstant = Instant.parse("2026-07-20T00:00:01Z")
        repository.record(
            event(2, currentInstant.toEpochMilli(), "next-day.example", FilterAction.ALLOW),
            LoggingSettings(keepCounts = true, keepHistory = false),
        )
        assertEquals(2L, database.readableDatabase.rowCount(ActivityDatabase.COUNT_EVENT_TABLE))
        assertEquals(1L, database.readableDatabase.rowCount(ActivityDatabase.PROTECTION_INTERVAL_TABLE))

        currentInstant = Instant.parse("2026-07-20T00:05:01Z")
        assertTrue(repository.recordProtectionHeartbeat(atMillis = currentInstant.toEpochMilli()))

        assertEquals(1L, database.readableDatabase.rowCount(ActivityDatabase.COUNT_EVENT_TABLE))
        assertEquals(0L, database.readableDatabase.rowCount(ActivityDatabase.PROTECTION_INTERVAL_TABLE))
        assertEquals(
            ActivitySummary(allowedCount = 0, blockedCount = 1, localProtectionUptimeMillis = 2_000L),
            repository.summary(selectedDay),
        )
    }

    @Test
    fun `explicit history prune after midnight preserves rollback clipping evidence`() {
        var currentInstant = Instant.parse("2026-07-19T23:59:59Z")
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = currentInstant
        }
        repository = ActivityRepository(database, ZoneOffset.UTC, advancingClock)
        val selectedDay = range("2026-07-19", "2026-07-19")

        repository.record(
            event(1, currentInstant.toEpochMilli(), "prune-midnight.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = true, keepHistory = false),
        )
        repository.recordProtectionState(
            active = true,
            atMillis = currentInstant.minusSeconds(2).toEpochMilli(),
        )
        repository.recordProtectionState(active = false, atMillis = currentInstant.toEpochMilli())

        currentInstant = Instant.parse("2026-07-20T00:00:01Z")
        assertEquals(ActivityPruneResult(0, 0), repository.pruneDetailedHistory())

        currentInstant = Instant.parse("2026-07-19T23:59:58Z")
        val expectedAtRollback = ActivitySummary(
            allowedCount = 0,
            blockedCount = 0,
            localProtectionUptimeMillis = 1_000L,
        )
        assertEquals(expectedAtRollback, repository.summary(selectedDay))
        assertEquals(expectedAtRollback, repository.exportSnapshot(selectedDay).summary)
    }

    @Test
    fun `database reopen after midnight preserves rollback clipping evidence`() {
        database.close()
        val name = "activity-midnight-reopen-${UUID.randomUUID()}.db"
        var currentInstant = Instant.parse("2026-07-19T23:59:59Z")
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = currentInstant
        }
        database = ActivityDatabase(
            context,
            name,
            pruneNowMillis = { currentInstant.toEpochMilli() },
        )
        repository = ActivityRepository(database, ZoneOffset.UTC, advancingClock)
        val selectedDay = range("2026-07-19", "2026-07-19")

        repository.record(
            event(1, currentInstant.toEpochMilli(), "reopen-midnight.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = true, keepHistory = false),
        )
        repository.recordProtectionState(
            active = true,
            atMillis = currentInstant.minusSeconds(2).toEpochMilli(),
        )
        repository.recordProtectionState(active = false, atMillis = currentInstant.toEpochMilli())
        database.close()

        currentInstant = Instant.parse("2026-07-20T00:00:01Z")
        database = ActivityDatabase(
            context,
            name,
            pruneNowMillis = { currentInstant.toEpochMilli() },
        )
        database.readableDatabase
        repository = ActivityRepository(database, ZoneOffset.UTC, advancingClock)

        currentInstant = Instant.parse("2026-07-19T23:59:58Z")
        val expectedAtRollback = ActivitySummary(
            allowedCount = 0,
            blockedCount = 0,
            localProtectionUptimeMillis = 1_000L,
        )
        assertEquals(expectedAtRollback, repository.summary(selectedDay))
        assertEquals(expectedAtRollback, repository.exportSnapshot(selectedDay).summary)

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `database initialization removes export sidecars orphaned by process death`() {
        val orphan = File.createTempFile("activity-export-", ".sqlite", context.cacheDir)
        val orphanWal = File(orphan.path + "-wal").apply { createNewFile() }
        val orphanShm = File(orphan.path + "-shm").apply { createNewFile() }

        ActivityDatabase(context, null).close()

        assertFalse(orphan.exists())
        assertFalse(orphanWal.exists())
        assertFalse(orphanShm.exists())
    }

    @Test
    fun `history round trips terminal resolver evidence`() {
        val observed = event(
            id = 1,
            timestamp = "2026-07-19T08:00:00Z",
            domain = "evidence.example",
            action = FilterAction.ALLOW,
        ).copy(
            resolver = ResolverKind.MULLVAD_DOH,
            transport = ResolverTransport.MULLVAD_DOH,
            latencyMillis = 2_750,
            error = ResolverFailureReason.HTTP_STATUS,
        )

        repository.record(observed, LoggingSettings())

        assertEquals(listOf(observed), repository.history())
    }

    @Test
    fun `version one database migrates without losing rows and adds evidence columns`() {
        database.close()
        val name = "activity-v1-${UUID.randomUUID()}.db"
        val timestamp = Instant.parse("2026-07-20T12:00:00Z").toEpochMilli()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            createVersionOneSchema(legacy)
            legacy.execSQL(
                "INSERT INTO activity_event(timestamp_millis, domain, action, reason) VALUES(?, ?, ?, ?)",
                arrayOf<Any?>(timestamp, "legacy.example", "BLOCK", "BLOCKLIST"),
            )
            legacy.execSQL(
                "INSERT INTO activity_daily_count(day_key, action, count) VALUES(?, ?, ?)",
                arrayOf<Any?>("2026-07-20", "BLOCK", 1),
            )
            legacy.execSQL(
                "INSERT INTO activity_daily_domain(day_key, action, domain, count) VALUES(?, ?, ?, ?)",
                arrayOf<Any?>("2026-07-20", "BLOCK", "legacy.example", 1),
            )
            legacy.version = 1
        }

        database = ActivityDatabase(context, name, pruneNowMillis = { timestamp + 60_000 })
        repository = ActivityRepository(
            database,
            ZoneOffset.UTC,
            Clock.fixed(Instant.ofEpochMilli(timestamp + 60_000), ZoneOffset.UTC),
        )

        val columns = database.readableDatabase.rawQuery("PRAGMA table_info(activity_event)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue(setOf("resolver", "transport", "latency_millis", "error").all(columns::contains))
        val migratedRetentionEpoch = database.readableDatabase.rawQuery(
            "SELECT retention_epoch_millis FROM activity_daily_domain",
            null,
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        assertEquals(Instant.parse("2026-07-21T00:00:00Z").toEpochMilli(), migratedRetentionEpoch)
        val migrated = repository.history().single()
        assertEquals("legacy.example", migrated.normalizedDomain)
        assertEquals(FilterAction.BLOCK, migrated.decision.action)
        assertEquals(null, migrated.resolver)
        assertEquals(null, migrated.transport)
        assertEquals(null, migrated.latencyMillis)
        assertEquals(null, migrated.error)

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `version three open sessions migrate and seal at their only confirmed evidence`() {
        database.close()
        val name = "activity-v3-${UUID.randomUUID()}.db"
        val started = Instant.parse("2026-07-19T11:00:00Z").toEpochMilli()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            createVersionThreeSchema(legacy)
            legacy.execSQL(
                "INSERT INTO activity_protection_session(start_millis, end_millis) VALUES(?, NULL)",
                arrayOf<Any?>(started),
            )
            legacy.version = 3
        }

        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        assertEquals(1, database.recoverAbandonedProtectionSessions())
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(
            0L,
            repository.summary(range("2026-07-19", "2026-07-19")).localProtectionUptimeMillis,
        )
        assertEquals(0L, rowCount(database, ActivityDatabase.PROTECTION_SESSION_TABLE))
        assertEquals(0L, rowCount(database, ActivityDatabase.PROTECTION_DAILY_TABLE))

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    @Config(sdk = [28])
    fun `version four database migrates through compact Activity schema on API 28`() {
        database.close()
        val name = "activity-v4-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            createVersionFourSchema(legacy)
            legacy.version = 4
        }

        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        val writable = database.writableDatabase

        assertTrue(writable.hasColumn("activity_event", "day_key"))
        assertTrue(writable.hasColumn("activity_daily_count", "retention_epoch_millis"))
        assertTrue(writable.hasColumn("activity_protection_daily", "retention_epoch_millis"))
        assertTrue(writable.hasTable("activity_count_event"))
        assertTrue(writable.hasTable("activity_protection_interval"))

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    @Config(sdk = [28])
    fun `version five protection aggregates receive an absolute retention boundary on API 28`() {
        database.close()
        val name = "activity-v5-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            createVersionFiveSchema(legacy)
            legacy.execSQL(
                "INSERT INTO activity_protection_daily(day_key, uptime_millis) VALUES(?, ?)",
                arrayOf<Any>("2026-07-19", 60_000L),
            )
            legacy.version = 5
        }

        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        val migrated = database.readableDatabase.rawQuery(
            "SELECT uptime_millis, retention_epoch_millis FROM activity_protection_daily",
            null,
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0) to cursor.getLong(1)
        }

        assertEquals(60_000L, migrated.first)
        assertEquals(Instant.parse("2026-07-20T00:00:00Z").toEpochMilli(), migrated.second)

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    @Config(sdk = [28])
    fun `version six database gains zeroed revisions and default policy without losing Activity rows`() {
        database.close()
        val name = "activity-v6-${UUID.randomUUID()}.db"
        val eventMillis = now.minusSeconds(60).toEpochMilli()
        val dayEndMillis = Instant.parse("2026-07-20T00:00:00Z").toEpochMilli()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            createVersionSixSchema(legacy)
            legacy.execSQL(
                "INSERT INTO activity_event(" +
                    "timestamp_millis, day_key, retention_epoch_millis, domain, action, reason" +
                    ") VALUES(?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(eventMillis, "2026-07-19", dayEndMillis, "legacy.example", "BLOCK", "BLOCKLIST"),
            )
            legacy.execSQL(
                "INSERT INTO activity_daily_count(day_key, action, count, retention_epoch_millis) " +
                    "VALUES(?, ?, ?, ?)",
                arrayOf<Any>("2026-07-19", "BLOCK", 1L, dayEndMillis),
            )
            legacy.execSQL(
                "INSERT INTO activity_count_event(" +
                    "timestamp_millis, day_key, action, retention_epoch_millis" +
                    ") VALUES(?, ?, ?, ?)",
                arrayOf<Any>(eventMillis, "2026-07-19", "BLOCK", dayEndMillis),
            )
            legacy.execSQL(
                "INSERT INTO activity_daily_domain(" +
                    "day_key, action, domain, count, error_count, retention_epoch_millis" +
                    ") VALUES(?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("2026-07-19", "BLOCK", "legacy.example", 1L, 0L, dayEndMillis),
            )
            legacy.execSQL(
                "INSERT INTO activity_protection_session(" +
                    "start_millis, last_evidence_millis, end_millis" +
                    ") VALUES(?, ?, NULL)",
                arrayOf<Any>(eventMillis, eventMillis),
            )
            legacy.execSQL(
                "INSERT INTO activity_protection_daily(day_key, uptime_millis, retention_epoch_millis) " +
                    "VALUES(?, ?, ?)",
                arrayOf<Any>("2026-07-19", 60_000L, dayEndMillis),
            )
            legacy.execSQL(
                "INSERT INTO activity_protection_interval(" +
                    "day_key, start_millis, end_millis, retention_epoch_millis" +
                    ") VALUES(?, ?, ?, ?)",
                arrayOf<Any>("2026-07-19", eventMillis - 60_000L, eventMillis, dayEndMillis),
            )
            legacy.version = 6
        }

        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        val migrated = database.readableDatabase

        assertEquals(10, migrated.version)
        assertEquals(
            mapOf(
                ActivityDatabase.EVENT_TABLE to 1L,
                ActivityDatabase.DAILY_COUNT_TABLE to 1L,
                ActivityDatabase.COUNT_EVENT_TABLE to 1L,
                ActivityDatabase.DAILY_DOMAIN_TABLE to 1L,
                ActivityDatabase.PROTECTION_SESSION_TABLE to 1L,
                ActivityDatabase.PROTECTION_DAILY_TABLE to 1L,
                ActivityDatabase.PROTECTION_INTERVAL_TABLE to 1L,
            ),
            listOf(
                ActivityDatabase.EVENT_TABLE,
                ActivityDatabase.DAILY_COUNT_TABLE,
                ActivityDatabase.COUNT_EVENT_TABLE,
                ActivityDatabase.DAILY_DOMAIN_TABLE,
                ActivityDatabase.PROTECTION_SESSION_TABLE,
                ActivityDatabase.PROTECTION_DAILY_TABLE,
                ActivityDatabase.PROTECTION_INTERVAL_TABLE,
            ).associateWith { table -> migrated.rowCount(table) },
        )
        assertEquals(
            0L to 0L,
            migrated.rawQuery(
                "SELECT summary_count_revision, domain_log_revision FROM activity_revision",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0) to cursor.getLong(1)
            },
        )

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `domain retention uses captured day-end epoch across timezone and clock changes`() {
        val pruningNow = Instant.parse("2026-07-20T00:30:00Z")
        val cutoff = pruningNow.toEpochMilli() - ActivityHistoryPolicy.RETENTION_MILLIS
        val trailingDayEvent = cutoff - 1
        val expiredWholeDayEvent = cutoff - (15 * 60 * 60 * 1_000L)
        repository = ActivityRepository(
            database,
            ZoneId.of("Pacific/Kiritimati"),
            Clock.fixed(Instant.ofEpochMilli(trailingDayEvent), ZoneOffset.UTC),
        )
        repository.record(
            event(1, trailingDayEvent, "trailing-day.example", FilterAction.BLOCK),
            LoggingSettings(),
        )
        repository = ActivityRepository(
            database,
            ZoneId.of("Pacific/Kiritimati"),
            Clock.fixed(Instant.ofEpochMilli(expiredWholeDayEvent), ZoneOffset.UTC),
        )
        repository.record(
            event(2, expiredWholeDayEvent, "expired-day.example", FilterAction.BLOCK),
            LoggingSettings(),
        )

        repository = ActivityRepository(
            database,
            ZoneId.of("Pacific/Pago_Pago"),
            Clock.fixed(pruningNow, ZoneOffset.UTC),
        )
        val result = repository.pruneDetailedHistory()

        assertEquals(ActivityPruneResult(eventRowsDeleted = 2, domainRowsDeleted = 1), result)
        assertEquals(
            listOf(ActivityDomainFrequency("trailing-day.example", 1)),
            repository.topDomains(range("2026-07-13", "2026-07-13"), FilterAction.BLOCK),
        )
        assertEquals(
            ActivitySummary(allowedCount = 0, blockedCount = 1),
            repository.summary(range("2026-07-12", "2026-07-12")),
        )

        repository = ActivityRepository(
            database,
            ZoneId.of("Pacific/Honolulu"),
            Clock.fixed(pruningNow.minusSeconds(30 * 24 * 60 * 60L), ZoneOffset.UTC),
        )
        assertTrue("physically pruned history cannot resurrect", repository.history().isEmpty())
        assertTrue(
            "physically pruned domain detail cannot resurrect",
            repository.topDomains(range("2026-07-12", "2026-07-12"), FilterAction.BLOCK).isEmpty(),
        )
    }

    @Test
    fun `recent Kiritimati Top Domain survives reopening in Pago Pago`() {
        val instant = Instant.parse("2026-07-19T10:30:00Z")
        repository = ActivityRepository(
            database,
            ZoneId.of("Pacific/Kiritimati"),
            Clock.fixed(instant, ZoneOffset.UTC),
        )
        repository.record(
            event(1, instant.toEpochMilli(), "date-line.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = false, keepHistory = true),
        )

        repository = ActivityRepository(
            database,
            ZoneId.of("Pacific/Pago_Pago"),
            Clock.fixed(instant, ZoneOffset.UTC),
        )
        assertEquals(ActivityPruneResult(0, 0), repository.pruneDetailedHistory())
        assertEquals(1L, rowCount(database, ActivityDatabase.DAILY_DOMAIN_TABLE))
    }

    @Test
    fun `recent Kiritimati protection aggregate survives real reopen in Pago Pago`() {
        database.close()
        val name = "activity-date-line-uptime-${UUID.randomUUID()}.db"
        val instant = Instant.parse("2026-07-19T10:30:00Z")
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            database = ActivityDatabase(context, name, pruneNowMillis = instant::toEpochMilli)
            repository = ActivityRepository(
                database,
                ZoneId.of("Pacific/Kiritimati"),
                Clock.fixed(instant, ZoneOffset.UTC),
            )
            repository.recordProtectionState(active = true, atMillis = instant.minusSeconds(60).toEpochMilli())
            repository.recordProtectionState(active = false, atMillis = instant.toEpochMilli())
            database.close()

            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago"))
            database = ActivityDatabase(context, name, pruneNowMillis = instant::toEpochMilli)
            database.readableDatabase

            assertEquals(1L, rowCount(database, ActivityDatabase.PROTECTION_DAILY_TABLE))
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `opening database prunes expired detail but preserves digest counts`() {
        database.close()
        val name = "activity-open-prune-${UUID.randomUUID()}.db"
        val pruningNow = Instant.parse("2026-07-20T00:30:00Z").toEpochMilli()
        val cutoff = pruningNow - ActivityHistoryPolicy.RETENTION_MILLIS
        val expired = cutoff - (15 * 60 * 60 * 1_000L)
        database = ActivityDatabase(context, name, pruneNowMillis = { pruningNow })
        repository = ActivityRepository(
            database,
            ZoneOffset.UTC,
            Clock.fixed(Instant.ofEpochMilli(pruningNow), ZoneOffset.UTC),
        )
        repository.record(event(1, expired, "expired-on-open.example", FilterAction.BLOCK), LoggingSettings())
        database.close()

        database = ActivityDatabase(context, name, pruneNowMillis = { pruningNow })
        database.readableDatabase

        assertEquals(0L, rowCount(database, ActivityDatabase.EVENT_TABLE))
        assertEquals(0L, rowCount(database, ActivityDatabase.DAILY_DOMAIN_TABLE))
        assertEquals(1L, rowCount(database, ActivityDatabase.DAILY_COUNT_TABLE))

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `local protection uptime is clipped to the selected range and survives reopen`() {
        database.close()
        val name = "activity-uptime-${UUID.randomUUID()}.db"
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

        repository.recordProtectionState(active = true, atMillis = Instant.parse("2026-07-18T23:30:00Z").toEpochMilli())
        repository.recordProtectionState(active = false, atMillis = Instant.parse("2026-07-19T01:30:00Z").toEpochMilli())
        repository.recordProtectionState(active = true, atMillis = Instant.parse("2026-07-19T11:30:00Z").toEpochMilli())
        repository.recordProtectionHeartbeat(atMillis = now.toEpochMilli())
        database.close()

        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        assertEquals(1, database.recoverAbandonedProtectionSessions())
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))

        assertEquals(
            30 * 60_000L,
            repository.summary(range("2026-07-18", "2026-07-18")).localProtectionUptimeMillis,
        )
        assertEquals(
            2 * 60 * 60_000L,
            repository.summary(range("2026-07-19", "2026-07-19")).localProtectionUptimeMillis,
        )

        repository.recordProtectionState(active = true, atMillis = now.toEpochMilli())
        repository.recordProtectionState(active = false, atMillis = now.plusSeconds(30 * 60).toEpochMilli())
        assertEquals(
            2 * 60 * 60_000L + 30 * 60_000L,
            repository.summary(range("2026-07-19", "2026-07-19"), nowMillis = now.plusSeconds(30 * 60).toEpochMilli())
                .localProtectionUptimeMillis,
        )

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `closed protection churn compacts into a bounded daily aggregate`() {
        val dayStart = Instant.parse("2026-07-19T00:00:00Z").toEpochMilli()
        repeat(1_000) { index ->
            val start = dayStart + index * 2_000L
            assertTrue(repository.recordProtectionState(active = true, atMillis = start))
            assertTrue(repository.recordProtectionState(active = false, atMillis = start + 1_000L))
        }

        assertEquals(0L, rowCount(database, ActivityDatabase.PROTECTION_SESSION_TABLE))
        assertEquals(1L, rowCount(database, ActivityDatabase.PROTECTION_DAILY_TABLE))
        assertEquals(
            1_000_000L,
            repository.summary(range("2026-07-19", "2026-07-19"), nowMillis = now.toEpochMilli())
                .localProtectionUptimeMillis,
        )
    }

    @Test
    fun `abrupt process death never counts beyond the last confirmed protection evidence`() {
        database.close()
        val name = "activity-crash-uptime-${UUID.randomUUID()}.db"
        val started = Instant.parse("2026-07-19T11:00:00Z").toEpochMilli()
        val lastEvidence = Instant.parse("2026-07-19T11:17:00Z").toEpochMilli()
        val muchLater = Instant.parse("2026-07-19T18:00:00Z").toEpochMilli()
        database = ActivityDatabase(context, name, pruneNowMillis = { muchLater })
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        repository.recordProtectionState(active = true, atMillis = started)
        repository.recordProtectionHeartbeat(atMillis = lastEvidence)

        val live = repository.summary(range("2026-07-19", "2026-07-19"), nowMillis = muchLater)
        assertEquals(17 * 60_000L, live.localProtectionUptimeMillis)
        assertEquals(lastEvidence, live.liveUptimeEvidenceThroughMillis)
        database.close()

        database = ActivityDatabase(context, name, pruneNowMillis = { muchLater })
        assertEquals(1, database.recoverAbandonedProtectionSessions())
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(Instant.ofEpochMilli(muchLater), ZoneOffset.UTC))

        val recovered = repository.summary(
            range("2026-07-19", "2026-07-19"),
            nowMillis = muchLater,
        )
        assertEquals(17 * 60_000L, recovered.localProtectionUptimeMillis)
        assertNull(recovered.liveUptimeEvidenceThroughMillis)
        assertTrue(repository.recordProtectionState(active = true, atMillis = muchLater))

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `a second pruning helper never seals the live app protection session`() {
        database.close()
        val name = "activity-live-prune-${UUID.randomUUID()}.db"
        val started = Instant.parse("2026-07-19T11:00:00Z").toEpochMilli()
        val firstHeartbeat = Instant.parse("2026-07-19T11:17:00Z").toEpochMilli()
        val laterHeartbeat = Instant.parse("2026-07-19T11:30:00Z").toEpochMilli()
        database = ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        repository.recordProtectionState(active = true, atMillis = started)
        repository.recordProtectionHeartbeat(atMillis = firstHeartbeat)

        ActivityDatabase(context, name, pruneNowMillis = now::toEpochMilli).use { pruningHelper ->
            pruningHelper.writableDatabase
        }

        assertTrue(repository.recordProtectionHeartbeat(atMillis = laterHeartbeat))
        assertEquals(
            30 * 60_000L,
            repository.summary(range("2026-07-19", "2026-07-19"), nowMillis = laterHeartbeat)
                .localProtectionUptimeMillis,
        )

        database.close()
        context.deleteDatabase(name)
        database = ActivityDatabase(context, null)
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `deleting detailed activity preserves digest counts and protection uptime`() {
        val logging = LoggingSettings(keepCounts = true, keepHistory = true)
        val started = Instant.parse("2026-07-19T11:00:00Z").toEpochMilli()
        val ended = Instant.parse("2026-07-19T11:05:00Z").toEpochMilli()
        repository.record(event(1, "2026-07-19T11:01:00Z", "private.example", FilterAction.BLOCK), logging)
        repository.recordProtectionState(active = true, atMillis = started)
        repository.recordProtectionState(active = false, atMillis = ended)

        repository.deleteDetailedActivity()

        assertEquals(
            ActivitySummary(allowedCount = 0, blockedCount = 1, localProtectionUptimeMillis = 5 * 60_000L),
            repository.summary(range("2026-07-19", "2026-07-19"), nowMillis = ended),
        )
        assertTrue(repository.history(nowMillis = ended).isEmpty())
        assertTrue(repository.topDomains(range("2026-07-19", "2026-07-19"), FilterAction.BLOCK).isEmpty())
    }

    @Test
    fun `logging policy clears and gates disabled Activity surfaces`() {
        val logging = LoggingSettings(keepCounts = true, keepHistory = true)
        repository.record(event(1, "2026-07-19T11:01:00Z", "private.example", FilterAction.BLOCK), logging)
        repository.recordProtectionState(active = true, keepCounts = true, atMillis = now.minusSeconds(60).toEpochMilli())
        repository.recordProtectionHeartbeat(keepCounts = true, atMillis = now.toEpochMilli())

        assertTrue(
            repository.enforceLoggingSettings(
                LoggingSettings(keepCounts = false, keepHistory = false),
            ),
        )

        assertEquals(ActivitySummary.EMPTY, repository.summary(range("2026-07-19", "2026-07-19")))
        assertTrue(repository.history().isEmpty())
        assertFalse(repository.recordProtectionState(active = true, keepCounts = false, atMillis = now.toEpochMilli()))
        assertFalse(repository.recordProtectionHeartbeat(keepCounts = false, atMillis = now.toEpochMilli()))
    }

    @Test
    fun `logging enforcement rolls back every Activity deletion when one table delete fails`() {
        repository.record(
            event(1, "2026-07-19T11:01:00Z", "private.example", FilterAction.BLOCK),
            LoggingSettings(keepCounts = true, keepHistory = true),
        )
        val tables = listOf(
            ActivityDatabase.EVENT_TABLE,
            ActivityDatabase.DAILY_DOMAIN_TABLE,
            ActivityDatabase.DAILY_COUNT_TABLE,
            ActivityDatabase.COUNT_EVENT_TABLE,
        )
        val before = tables.associateWith { table -> database.readableDatabase.rowCount(table) }
        database.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_activity_counts_delete
            BEFORE DELETE ON ${ActivityDatabase.DAILY_COUNT_TABLE}
            BEGIN
                SELECT RAISE(ABORT, 'injected Activity deletion failure');
            END
            """.trimIndent(),
        )

        try {
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                repository.enforceLoggingSettings(
                    LoggingSettings(keepCounts = false, keepHistory = false),
                )
            }
            assertEquals(
                before,
                tables.associateWith { table -> database.readableDatabase.rowCount(table) },
            )
        } finally {
            database.writableDatabase.execSQL("DROP TRIGGER fail_activity_counts_delete")
        }
    }

    @Test
    fun `confirmed active heartbeat starts fresh uptime after counts are re-enabled`() {
        val initialStart = Instant.parse("2026-07-19T10:00:00Z").toEpochMilli()
        val disabledAt = Instant.parse("2026-07-19T10:05:00Z").toEpochMilli()
        val reenabledAt = Instant.parse("2026-07-19T10:20:00Z").toEpochMilli()
        val laterEvidence = Instant.parse("2026-07-19T10:21:00Z").toEpochMilli()
        val selectedDay = range("2026-07-19", "2026-07-19")

        assertTrue(repository.recordProtectionState(active = true, keepCounts = true, atMillis = initialStart))
        assertTrue(repository.recordProtectionHeartbeat(keepCounts = true, atMillis = disabledAt))
        assertTrue(
            repository.enforceLoggingSettings(
                LoggingSettings(keepCounts = false, keepHistory = true),
            ),
        )
        assertFalse(repository.recordProtectionHeartbeat(keepCounts = false, atMillis = reenabledAt))

        // The first confirmed-active evidence after opt-in starts now, not at the old session.
        assertTrue(repository.recordProtectionHeartbeat(keepCounts = true, atMillis = reenabledAt))
        assertEquals(0L, repository.summary(selectedDay, nowMillis = reenabledAt).localProtectionUptimeMillis)
        assertTrue(repository.recordProtectionHeartbeat(keepCounts = true, atMillis = laterEvidence))
        assertEquals(
            60_000L,
            repository.summary(selectedDay, nowMillis = laterEvidence).localProtectionUptimeMillis,
        )
    }

    @Test
    fun `query limits are bounded and schema has range ordering indexes`() {
        val oneDay = range("2026-07-19", "2026-07-19")
        assertThrows(IllegalArgumentException::class.java) { repository.history(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.history(limit = ActivityRepository.MAX_HISTORY_LIMIT + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.topDomains(oneDay, FilterAction.ALLOW, limit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.topDomains(oneDay, FilterAction.ALLOW, limit = ActivityRepository.MAX_TOP_DOMAIN_LIMIT + 1)
        }

        val indexes = database.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue("idx_activity_event_time" in indexes)
        assertTrue("idx_activity_event_action_time" in indexes)
        assertTrue("idx_activity_daily_domain_action_day" in indexes)
    }

    @Test
    fun `database refuses device protected storage`() {
        val deviceContext = context.createDeviceProtectedStorageContext()

        assertThrows(IllegalArgumentException::class.java) {
            ActivityDatabase(deviceContext, null)
        }
    }

    private fun createVersionOneSchema(database: android.database.sqlite.SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE activity_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_millis INTEGER NOT NULL,
                domain TEXT NOT NULL,
                action TEXT NOT NULL CHECK(action IN ('ALLOW', 'BLOCK')),
                reason TEXT NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE activity_daily_count (
                day_key TEXT NOT NULL,
                action TEXT NOT NULL CHECK(action IN ('ALLOW', 'BLOCK')),
                count INTEGER NOT NULL CHECK(count >= 0),
                PRIMARY KEY(day_key, action)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE activity_daily_domain (
                day_key TEXT NOT NULL,
                action TEXT NOT NULL CHECK(action IN ('ALLOW', 'BLOCK')),
                domain TEXT NOT NULL,
                count INTEGER NOT NULL CHECK(count >= 0),
                PRIMARY KEY(day_key, action, domain)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
    }

    private fun createVersionThreeSchema(database: android.database.sqlite.SQLiteDatabase) {
        createVersionOneSchema(database)
        database.execSQL("ALTER TABLE activity_event ADD COLUMN resolver TEXT")
        database.execSQL("ALTER TABLE activity_event ADD COLUMN transport TEXT")
        database.execSQL("ALTER TABLE activity_event ADD COLUMN latency_millis INTEGER")
        database.execSQL("ALTER TABLE activity_event ADD COLUMN error TEXT")
        database.execSQL("ALTER TABLE activity_daily_domain ADD COLUMN retention_epoch_millis INTEGER NOT NULL DEFAULT 0")
        database.execSQL(
            """
            CREATE TABLE activity_protection_session (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER,
                CHECK(end_millis IS NULL OR end_millis >= start_millis)
            )
            """.trimIndent(),
        )
    }

    private fun createVersionFourSchema(database: android.database.sqlite.SQLiteDatabase) {
        createVersionThreeSchema(database)
        database.execSQL(
            "ALTER TABLE activity_protection_session " +
                "ADD COLUMN last_evidence_millis INTEGER NOT NULL DEFAULT 0",
        )
        database.execSQL(
            "UPDATE activity_protection_session SET last_evidence_millis = " +
                "MAX(start_millis, COALESCE(end_millis, start_millis))",
        )
    }

    private fun createVersionFiveSchema(database: android.database.sqlite.SQLiteDatabase) {
        createVersionFourSchema(database)
        database.execSQL(
            "ALTER TABLE activity_daily_domain ADD COLUMN error_count INTEGER NOT NULL DEFAULT 0",
        )
        database.execSQL(
            """
            CREATE TABLE activity_protection_daily (
                day_key TEXT PRIMARY KEY,
                uptime_millis INTEGER NOT NULL CHECK(uptime_millis >= 0)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
    }

    private fun createVersionSixSchema(database: android.database.sqlite.SQLiteDatabase) {
        createVersionFiveSchema(database)
        database.execSQL("ALTER TABLE activity_event ADD COLUMN day_key TEXT NOT NULL DEFAULT ''")
        database.execSQL(
            "ALTER TABLE activity_event " +
                "ADD COLUMN retention_epoch_millis INTEGER NOT NULL DEFAULT 0",
        )
        database.execSQL(
            "ALTER TABLE activity_daily_count " +
                "ADD COLUMN retention_epoch_millis INTEGER NOT NULL DEFAULT 0",
        )
        database.execSQL(
            "ALTER TABLE activity_protection_daily " +
                "ADD COLUMN retention_epoch_millis INTEGER NOT NULL DEFAULT 0",
        )
        database.execSQL(
            """
            CREATE TABLE activity_count_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_millis INTEGER NOT NULL,
                day_key TEXT NOT NULL,
                action TEXT NOT NULL CHECK(action IN ('ALLOW', 'BLOCK')),
                retention_epoch_millis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE activity_protection_interval (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                day_key TEXT NOT NULL,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL CHECK(end_millis >= start_millis),
                retention_epoch_millis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun android.database.sqlite.SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use true
            }
            false
        }

    private fun android.database.sqlite.SQLiteDatabase.hasTable(table: String): Boolean =
        rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun android.database.sqlite.SQLiteDatabase.rowCount(table: String): Long =
        rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun rowCount(database: ActivityDatabase, table: String): Long =
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun openMaintenanceRowCounts(database: ActivityDatabase): Map<String, Long> =
        openMaintenanceTables.associateWith { table -> rowCount(database, table) }

    private fun assertOpenMaintenanceDeferred(expectedRevisions: ActivityRepositoryRevisions) {
        assertEquals(expectedRevisions, repository.revisions())
        assertEquals(
            openMaintenanceTables.associateWith { 1L },
            openMaintenanceRowCounts(database),
        )
        val expiredRange = range("2020-01-01", "2020-01-01")
        assertTrue(repository.history(nowMillis = now.toEpochMilli()).isEmpty())
        assertTrue(
            repository.topDomains(
                range = expiredRange,
                action = FilterAction.BLOCK,
                nowMillis = now.toEpochMilli(),
            ).isEmpty(),
        )
        assertEquals(
            ActivitySummary(allowedCount = 0L, blockedCount = 0L),
            repository.summary(expiredRange, nowMillis = now.toEpochMilli()),
        )
        assertEquals("integer", revisionStorageType(database, "summary_count_revision"))
        assertEquals("integer", revisionStorageType(database, "domain_log_revision"))
    }

    private fun revisionStorageType(database: ActivityDatabase, column: String): String =
        database.readableDatabase.rawQuery(
            "SELECT typeof($column) FROM ${ActivityDatabase.REVISION_TABLE} WHERE singleton = 1",
            null,
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun seedPendingOpenMaintenanceRows(
        database: ActivityDatabase,
        summaryRevision: Long,
        domainRevision: Long,
    ) {
        val expiredMillis = Instant.parse("2020-01-01T12:00:00Z").toEpochMilli()
        database.writableDatabase.apply {
            execSQL(
                "INSERT INTO ${ActivityDatabase.EVENT_TABLE}(" +
                    "timestamp_millis, day_key, retention_epoch_millis, domain, action, reason" +
                    ") VALUES(?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(expiredMillis, "2020-01-01", 0L, "expired.example", "BLOCK", "BLOCKLIST"),
            )
            execSQL(
                "INSERT INTO ${ActivityDatabase.DAILY_DOMAIN_TABLE}(" +
                    "day_key, action, domain, count, error_count, retention_epoch_millis" +
                    ") VALUES(?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("2020-01-01", "BLOCK", "expired.example", 1L, 0L, 0L),
            )
            execSQL(
                "INSERT INTO ${ActivityDatabase.COUNT_EVENT_TABLE}(" +
                    "timestamp_millis, day_key, action, retention_epoch_millis" +
                    ") VALUES(?, ?, ?, ?)",
                arrayOf<Any>(expiredMillis, "2020-01-01", "BLOCK", 0L),
            )
            execSQL(
                "INSERT INTO ${ActivityDatabase.PROTECTION_INTERVAL_TABLE}(" +
                    "day_key, start_millis, end_millis, retention_epoch_millis" +
                    ") VALUES(?, ?, ?, ?)",
                arrayOf<Any>("2020-01-01", expiredMillis, expiredMillis + 1_000L, 0L),
            )
            execSQL(
                "INSERT INTO ${ActivityDatabase.PROTECTION_DAILY_TABLE}(" +
                    "day_key, uptime_millis, retention_epoch_millis" +
                    ") VALUES(?, ?, ?)",
                arrayOf<Any>("2020-01-01", 1_000L, 0L),
            )
            execSQL(
                "UPDATE ${ActivityDatabase.REVISION_TABLE} SET " +
                    "summary_count_revision = ?, domain_log_revision = ? WHERE singleton = 1",
                arrayOf<Any>(summaryRevision, domainRevision),
            )
        }
    }

    private fun rowCountWhere(
        database: ActivityDatabase,
        table: String,
        where: String,
        args: Array<String>,
    ): Long = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $table WHERE $where",
        args,
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    @Test
    fun `one runtime writer retains exact replay identity and rejects changed data`() {
        val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
        val first = event(1L, now.toEpochMilli(), "runtime-one.example", FilterAction.ALLOW)
        assertTrue(writer.recordRuntime(first))
        val committed = repository.revisions()
        assertFalse(writer.recordRuntime(first))
        assertThrows(IllegalStateException::class.java) {
            writer.recordRuntime(first.copy(normalizedDomain = "changed-runtime-one.example"))
        }
        assertEquals(committed, repository.revisions())
        assertEquals(1L, dnsNamespaceHighWater(database))
        assertEquals(1L, repository.summary(range("2026-07-19", "2026-07-19")).allowedCount)
        assertEquals(listOf("runtime-one.example"), repository.history().map { it.normalizedDomain })
        assertThrows(IllegalArgumentException::class.java) { writer.recordRuntime(first.copy(id = 0L)) }
        assertEquals(committed, repository.revisions())
    }

    @Test
    fun `actual reopened database accepts restarted process ids without losing retained history`() {
        val name = "activity-runtime-restart-${UUID.randomUUID()}.sqlite"
        val first = event(1L, now.toEpochMilli(), "first-process.example", FilterAction.ALLOW)
        try {
            ActivityDatabase(context, name, { now.toEpochMilli() }).use { original ->
                val stored = ActivityRepository(original, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
                val originalWriter = CredentialActivityWriter(stored, ActivityRevisionBus())
                assertTrue(originalWriter.recordRuntime(first))
                assertFalse(originalWriter.recordRuntime(first))
                assertEquals(1L, dnsNamespaceHighWater(original))
            }
            ActivityDatabase(context, name, { now.toEpochMilli() }).use { reopened ->
                val stored = ActivityRepository(reopened, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
                val freshWriter = CredentialActivityWriter(stored, ActivityRevisionBus())
                val second = first.copy(normalizedDomain = "next-process.example")
                assertTrue(freshWriter.recordRuntime(second))
                assertFalse(freshWriter.recordRuntime(second))
                assertThrows(IllegalStateException::class.java) {
                    freshWriter.recordRuntime(second.copy(normalizedDomain = "forged-next-process.example"))
                }
                assertEquals(2L, dnsNamespaceHighWater(reopened))
                assertEquals(setOf(first.normalizedDomain, second.normalizedDomain), stored.history().map { it.normalizedDomain }.toSet())
                assertEquals(2L, stored.summary(range("2026-07-19", "2026-07-19")).allowedCount)
                assertEquals(2, stored.history().map { it.id }.toSet().size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun `legacy namespace keeps original synthetic replay and collision behavior`() {
        val original = event(1L, now.toEpochMilli(), "legacy-one.example", FilterAction.BLOCK)
        val synthetic = CredentialActivityWriter(repository, ActivityRevisionBus())
        assertTrue(synthetic.record(original))
        assertFalse(repository.record(original, LoggingSettings()))
        assertThrows(IllegalStateException::class.java) {
            repository.record(original.copy(normalizedDomain = "old-process-collision.example"), LoggingSettings())
        }
        assertEquals(0L, dnsNamespaceHighWater(database))
        val runtime = CredentialActivityWriter(repository, ActivityRevisionBus())
        assertTrue(runtime.recordRuntime(original.copy(normalizedDomain = "runtime-local-one.example")))
        assertEquals(1L, dnsNamespaceHighWater(database))
        assertEquals(2, repository.history().size)
        assertEquals(2L, repository.summary(range("2026-07-19", "2026-07-19")).blockedCount)
    }

    @Test
    fun `committed logging opt out never allocates a runtime writer namespace`() {
        val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
        writer.applyLoggingPolicy(LoggingSettings(keepCounts = false, keepHistory = false))
        val disabled = repository.revisions()
        for (id in 1L..4L) {
            assertFalse(writer.recordRuntime(event(id, now.toEpochMilli(), "off-$id.example", FilterAction.ALLOW)))
        }
        assertEquals(0L, dnsNamespaceHighWater(database))
        assertEquals(disabled, repository.revisions())
        assertTrue(repository.history().isEmpty())
        assertEquals(0L, repository.summary(range("2026-07-19", "2026-07-19")).totalCount)
        writer.applyLoggingPolicy(LoggingSettings())
        assertTrue(writer.recordRuntime(event(5L, now.toEpochMilli(), "enabled-five.example", FilterAction.ALLOW)))
        assertTrue(writer.recordRuntime(event(6L, now.toEpochMilli(), "enabled-six.example", FilterAction.ALLOW)))
        assertEquals("One namespace per admitted writer, not one durable increment per query", 1L, dnsNamespaceHighWater(database))
    }

    @Test
    fun `another database handle opt out is checked before any runtime reservation`() {
        val name = "activity-runtime-policy-${UUID.randomUUID()}.sqlite"
        try {
            ActivityDatabase(context, name, { now.toEpochMilli() }).use { first ->
                ActivityDatabase(context, name, { now.toEpochMilli() }).use { second ->
                    val original = ActivityRepository(first, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
                    val other = ActivityRepository(second, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
                    val writer = CredentialActivityWriter(original, ActivityRevisionBus())
                    assertTrue(writer.recordRuntime(event(1L, now.toEpochMilli(), "before-opt-out.example", FilterAction.ALLOW)))
                    other.applyLoggingPolicy(LoggingSettings(keepCounts = false, keepHistory = false))
                    val disabled = other.revisions()
                    assertFalse(writer.recordRuntime(event(2L, now.toEpochMilli(), "after-opt-out.example", FilterAction.ALLOW)))
                    assertFalse(CredentialActivityWriter(original, ActivityRevisionBus()).recordRuntime(
                        event(1L, now.toEpochMilli(), "fresh-off-writer.example", FilterAction.ALLOW),
                    ))
                    assertEquals(1L, dnsNamespaceHighWater(first))
                    assertEquals(disabled, original.revisions())
                    assertTrue(original.history().isEmpty())
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun `independent database handles atomically separate concurrent runtime writers`() {
        val name = "activity-runtime-concurrent-${UUID.randomUUID()}.sqlite"
        val first = ActivityDatabase(context, name, { now.toEpochMilli() })
        val second = ActivityDatabase(context, name, { now.toEpochMilli() })
        val original = ActivityRepository(first, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        val other = ActivityRepository(second, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        first.writableDatabase; second.writableDatabase
        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = (0 until 4).map { index ->
                workers.submit<Boolean> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    val current = if (index % 2 == 0) original else other
                    CredentialActivityWriter(current, ActivityRevisionBus()).recordRuntime(
                        event(1L, now.toEpochMilli(), "concurrent-writer-$index.example", FilterAction.ALLOW),
                    )
                }
            }
            start.countDown()
            assertTrue(results.all { it.get(10, TimeUnit.SECONDS) })
            assertEquals(4L, dnsNamespaceHighWater(first))
            assertEquals(4, original.history().size)
            assertEquals(4L, original.summary(range("2026-07-19", "2026-07-19")).allowedCount)
        } finally {
            workers.shutdownNow(); first.close(); second.close(); context.deleteDatabase(name)
        }
    }

    @Test
    fun `version nine receipt migration preserves every legacy fingerprint and policy`() {
        val name = "activity-runtime-v9-${UUID.randomUUID()}.sqlite"
        val legacy = event(71L, now.toEpochMilli(), "legacy-process.example", FilterAction.BLOCK)
        val policy = LoggingSettings(keepNetworkActivity = false)
        var fingerprint = byteArrayOf()
        try {
            ActivityDatabase(context, name, { now.toEpochMilli() }).use { old ->
                val stored = ActivityRepository(old, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
                stored.applyLoggingPolicy(policy)
                assertTrue(stored.record(legacy, policy))
                val db = old.writableDatabase
                fingerprint = db.rawQuery("SELECT fingerprint FROM ${ActivityDatabase.DNS_RECEIPT_TABLE}", null)
                    .use { cursor -> check(cursor.moveToFirst()); cursor.getBlob(0) }
                // Exact version9 table shape, including its durable positive-ID key.
                db.execSQL("ALTER TABLE ${ActivityDatabase.DNS_RECEIPT_TABLE} RENAME TO receipt_new_shape")
                db.execSQL("DROP INDEX idx_activity_dns_receipt_time")
                db.execSQL("CREATE TABLE ${ActivityDatabase.DNS_RECEIPT_TABLE} (" +
                    "event_id INTEGER NOT NULL PRIMARY KEY CHECK(event_id > 0), " +
                    "timestamp_millis INTEGER NOT NULL CHECK(timestamp_millis >= 0), " +
                    "fingerprint BLOB NOT NULL) WITHOUT ROWID")
                db.execSQL("INSERT INTO ${ActivityDatabase.DNS_RECEIPT_TABLE}(event_id,timestamp_millis,fingerprint) " +
                    "SELECT event_id,timestamp_millis,fingerprint FROM receipt_new_shape")
                db.execSQL("DROP TABLE receipt_new_shape")
                db.execSQL("CREATE INDEX idx_activity_dns_receipt_time ON ${ActivityDatabase.DNS_RECEIPT_TABLE}(timestamp_millis)")
                db.execSQL("DROP TABLE ${ActivityDatabase.DNS_PRODUCER_NAMESPACE_TABLE}")
                db.version = 9
            }
            ActivityDatabase(context, name, { now.toEpochMilli() }).use { upgraded ->
                val stored = ActivityRepository(upgraded, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
                assertEquals(10, upgraded.readableDatabase.version)
                assertEquals(ActivityLoggingPolicySnapshot(policy, 1L), stored.loggingPolicy())
                val migrated = upgraded.readableDatabase.rawQuery(
                    "SELECT producer_namespace,event_id,timestamp_millis,fingerprint FROM ${ActivityDatabase.DNS_RECEIPT_TABLE}", null,
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0)); assertEquals(legacy.id, cursor.getLong(1))
                    assertEquals(legacy.timestampMillis, cursor.getLong(2))
                    cursor.getBlob(3)
                }
                assertArrayEquals(fingerprint, migrated)
                assertFalse(stored.record(legacy, policy))
                assertEquals(0L, dnsNamespaceHighWater(upgraded))
                assertTrue(CredentialActivityWriter(stored, ActivityRevisionBus()).recordRuntime(
                    legacy.copy(normalizedDomain = "fresh-runtime-after-migration.example"),
                ))
                assertEquals(1L, dnsNamespaceHighWater(upgraded))
                assertEquals(2L, stored.summary(range("2026-07-19", "2026-07-19")).blockedCount)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun `clear prune rollback and writer replacement cannot borrow an old namespace`() {
        val name = "activity-runtime-clear-${UUID.randomUUID()}.sqlite"
        try {
            ActivityDatabase(context, name, { now.toEpochMilli() }).use { currentDatabase ->
                val stored = ActivityRepository(currentDatabase, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
                val original = CredentialActivityWriter(stored, ActivityRevisionBus())
                assertTrue(original.recordRuntime(event(1L, now.toEpochMilli(), "before-clear.example", FilterAction.ALLOW)))
                stored.deleteAll()
                assertTrue(stored.history().isEmpty())
                val successor = CredentialActivityWriter(stored, ActivityRevisionBus())
                assertTrue(successor.recordRuntime(event(1L, now.toEpochMilli(), "successor-after-clear.example", FilterAction.ALLOW)))
                assertTrue(original.recordRuntime(event(2L, now.toEpochMilli(), "old-in-flight-after-clear.example", FilterAction.ALLOW)))
                assertEquals(2L, dnsNamespaceHighWater(currentDatabase))
                assertEquals(2, stored.history().size)
                val later = now.plusSeconds(31L * 86_400L)
                val pruned = ActivityRepository(currentDatabase, ZoneOffset.UTC, Clock.fixed(later, ZoneOffset.UTC))
                pruned.pruneDetailedHistory(later.toEpochMilli())
                assertTrue(pruned.history().isEmpty())
                assertEquals(2L, dnsNamespaceHighWater(currentDatabase))
            }
            val earlier = now.minusSeconds(86_400L)
            ActivityDatabase(context, name, { earlier.toEpochMilli() }).use { reopened ->
                val stored = ActivityRepository(reopened, ZoneOffset.UTC, Clock.fixed(earlier, ZoneOffset.UTC))
                assertTrue(CredentialActivityWriter(stored, ActivityRevisionBus()).recordRuntime(
                    event(1L, earlier.toEpochMilli(), "after-clock-rollback.example", FilterAction.ALLOW),
                ))
                assertEquals(3L, dnsNamespaceHighWater(reopened))
                assertEquals(listOf("after-clock-rollback.example"), stored.history().map { it.normalizedDomain })
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun `namespace failure rolls back telemetry without legacy fallback or wrapped identity`() {
        val legacy = event(1L, now.toEpochMilli(), "retained-before-exhaustion.example", FilterAction.BLOCK)
        assertTrue(repository.record(legacy, LoggingSettings()))
        database.writableDatabase.execSQL("UPDATE ${ActivityDatabase.DNS_PRODUCER_NAMESPACE_TABLE} SET last_namespace = ? WHERE singleton = 1",
            arrayOf<Any>(Long.MAX_VALUE))
        val before = repository.revisions()
        val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
        assertThrows(IllegalStateException::class.java) {
            writer.recordRuntime(legacy.copy(normalizedDomain = "must-not-fall-back.example"))
        }
        assertEquals(Long.MAX_VALUE, dnsNamespaceHighWater(database))
        assertEquals(before, repository.revisions())
        assertEquals(listOf(legacy.normalizedDomain), repository.history().map { it.normalizedDomain })
        assertEquals(1L, repository.summary(range("2026-07-19", "2026-07-19")).blockedCount)
        writer.applyLoggingPolicy(LoggingSettings(keepCounts = false, keepHistory = false))
        assertFalse(writer.recordRuntime(legacy.copy(normalizedDomain = "off-does-not-reserve.example")))
        assertEquals(Long.MAX_VALUE, dnsNamespaceHighWater(database))
    }

    @Test
    fun `failed receipt insert rolls back namespace reservation and cached writer identity`() {
        database.writableDatabase.execSQL(
            "CREATE TRIGGER fail_runtime_receipt BEFORE INSERT ON ${ActivityDatabase.DNS_RECEIPT_TABLE} " +
                "WHEN NEW.producer_namespace > 0 BEGIN SELECT RAISE(ABORT, 'receipt refused'); END",
        )
        val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
        val receipt = event(1L, now.toEpochMilli(), "retry-after-storage-recovery.example", FilterAction.ALLOW)
        val before = repository.revisions()
        assertThrows(android.database.sqlite.SQLiteException::class.java) { writer.recordRuntime(receipt) }
        assertEquals(0L, dnsNamespaceHighWater(database))
        assertEquals(before, repository.revisions())
        assertTrue(repository.history().isEmpty())
        database.writableDatabase.execSQL("DROP TRIGGER fail_runtime_receipt")
        assertTrue(writer.recordRuntime(receipt))
        assertFalse(writer.recordRuntime(receipt))
        assertEquals(1L, dnsNamespaceHighWater(database))
        assertEquals(1L, repository.summary(range("2026-07-19", "2026-07-19")).allowedCount)
        assertEquals(listOf(receipt.normalizedDomain), repository.history().map { it.normalizedDomain })
    }

    @Test
    fun `coherent closed database restore retains namespace and history ordering`() {
        val originalName = "activity-runtime-source-${UUID.randomUUID()}.sqlite"
        val restoredName = "activity-runtime-restored-${UUID.randomUUID()}.sqlite"
        try {
            ActivityDatabase(context, originalName, { now.toEpochMilli() }).use { original ->
                val stored = ActivityRepository(original, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
                assertTrue(CredentialActivityWriter(stored, ActivityRevisionBus()).recordRuntime(
                    event(1L, now.toEpochMilli(), "before-restore.example", FilterAction.ALLOW),
                ))
                assertEquals(1L, dnsNamespaceHighWater(original))
            }
            // Copy only after the real helper closes/checkpoints; never substitute a live WAL image.
            context.getDatabasePath(originalName).copyTo(context.getDatabasePath(restoredName))
            ActivityDatabase(context, restoredName, { now.toEpochMilli() }).use { restored ->
                val stored = ActivityRepository(restored, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
                val oldRow = stored.history().single()
                assertTrue(CredentialActivityWriter(stored, ActivityRevisionBus()).recordRuntime(
                    event(1L, now.toEpochMilli(), "fresh-writer-after-restore.example", FilterAction.ALLOW),
                ))
                assertEquals(2L, dnsNamespaceHighWater(restored))
                val history = stored.history()
                assertEquals("History keeps its own monotonic SQLite row cursor", 2, history.size)
                assertTrue(history.first().id > oldRow.id)
                assertEquals(setOf("before-restore.example", "fresh-writer-after-restore.example"),
                    history.map { it.normalizedDomain }.toSet())
            }
        } finally {
            context.deleteDatabase(originalName); context.deleteDatabase(restoredName)
        }
    }

    private fun dnsNamespaceHighWater(current: ActivityDatabase): Long = current.readableDatabase.rawQuery(
        "SELECT last_namespace FROM ${ActivityDatabase.DNS_PRODUCER_NAMESPACE_TABLE} WHERE singleton = 1", null,
    ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun range(start: String, end: String) = ActivityDateRange(
        start = LocalDate.parse(start),
        endInclusive = LocalDate.parse(end),
    )

    private fun event(
        id: Long,
        timestamp: String,
        domain: String,
        action: FilterAction,
        reason: FilterDecisionReason = when (action) {
            FilterAction.ALLOW -> FilterDecisionReason.DEFAULT_ALLOW
            FilterAction.BLOCK -> FilterDecisionReason.BLOCKLIST
        },
    ) = event(id, Instant.parse(timestamp).toEpochMilli(), domain, action, reason)

    private fun event(
        id: Long,
        timestampMillis: Long,
        domain: String,
        action: FilterAction,
        reason: FilterDecisionReason = when (action) {
            FilterAction.ALLOW -> FilterDecisionReason.DEFAULT_ALLOW
            FilterAction.BLOCK -> FilterDecisionReason.BLOCKLIST
        },
    ) = DnsDecisionEvent(
        id = id,
        timestampMillis = timestampMillis,
        normalizedDomain = domain,
        decision = FilterDecision(action, reason),
    )

    private class MutableClock(
        private var currentMillis: Long,
        private val clockZone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = clockZone

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentMillis, zone)

        override fun instant(): Instant = Instant.ofEpochMilli(currentMillis)

        fun advanceMillis(millis: Long) {
            currentMillis += millis
        }
    }
}
