package com.lavasec.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class ActivitySummary(
    val allowedCount: Long,
    val blockedCount: Long,
    val localProtectionUptimeMillis: Long = 0L,
    val liveUptimeEvidenceThroughMillis: Long? = null,
) {
    init {
        require(liveUptimeEvidenceThroughMillis == null || liveUptimeEvidenceThroughMillis >= 0L)
    }

    val totalCount: Long get() = allowedCount + blockedCount
    val blockRate: Double get() = if (totalCount == 0L) 0.0 else blockedCount.toDouble() / totalCount

    companion object {
        val EMPTY = ActivitySummary(allowedCount = 0, blockedCount = 0)
    }
}

data class ActivityDomainFrequency(
    val domain: String,
    val count: Long,
    val errorFloor: Long = 0L,
)

data class ActivityHistoryCursor(
    val timestampMillis: Long,
    val id: Long,
)

data class ActivityHistoryCut(
    val maxRowId: Long,
    val capturedAtMillis: Long,
) {
    init {
        require(maxRowId >= 0L) { "maxRowId must be non-negative" }
        require(capturedAtMillis >= 0L) { "capturedAtMillis must be non-negative" }
    }
}

data class ActivityHistoryPageToken(
    val cut: ActivityHistoryCut,
    val beforeExclusive: ActivityHistoryCursor? = null,
) {
    init {
        require(beforeExclusive == null || beforeExclusive.id <= cut.maxRowId) {
            "page position must belong to the frozen Activity History cut"
        }
    }
}

data class ActivityHistoryPage(
    val rows: List<DnsDecisionEvent>,
    val next: ActivityHistoryPageToken?,
    val cut: ActivityHistoryCut,
) {
    init {
        require(next == null || next.cut == cut) {
            "next page must preserve the frozen Activity History cut"
        }
        require(rows.all { it.id <= cut.maxRowId }) {
            "Activity History row is outside the frozen read cut"
        }
    }

    /** Compatibility for the existing export and presentation data boundary. */
    val entries: List<DnsDecisionEvent> get() = rows
    val nextCursor: ActivityHistoryCursor? get() = next?.beforeExclusive
}

data class ActivityPruneResult(
    val eventRowsDeleted: Int,
    val domainRowsDeleted: Int,
)

data class ActivityRepositoryRevisions(
    val summaryCountRevision: Long,
    val domainLogRevision: Long,
    val policyRevision: Long = 0L,
)

data class ActivityLoggingPolicySnapshot(
    val settings: LoggingSettings,
    val revision: Long,
) {
    init {
        require(revision >= 0L)
    }
}

internal data class ActivitySummarySnapshot(
    val summary: ActivitySummary,
    val summaryCountRevision: Long,
) {
    init {
        require(summaryCountRevision >= 0L)
    }
}

internal data class ActivityProductSummarySnapshot(
    val summary: ActivitySummary,
    val topDomains: List<ActivityDomainFrequency>,
    val revisions: ActivityRepositoryRevisions,
    val policy: ActivityLoggingPolicySnapshot,
    val hasRetainedDomainLogs: Boolean,
)

internal data class ActivityProductHistorySnapshot(
    val page: ActivityHistoryPage?,
    val revisions: ActivityRepositoryRevisions,
    val policy: ActivityLoggingPolicySnapshot,
    val hasRetainedDomainLogs: Boolean,
)

internal data class ProtectionUptimePersistenceOutcome(
    val accepted: Boolean,
    val activeEvidenceThroughMillis: Long?,
    val summaryCountRevision: Long,
) {
    init {
        require(activeEvidenceThroughMillis == null || activeEvidenceThroughMillis >= 0L)
        require(summaryCountRevision >= 0L)
    }
}

/** Exact, transactionally captured Activity rows owned by a local-log clear. */
internal data class ActivityLocalLogPredecessors(
    val revisions: ActivityRepositoryRevisions,
    val includesCountsAndUptime: Boolean,
    val includesDomainLogs: Boolean,
    val countsAndUptimeRowCount: Long,
    val domainLogRowCount: Long,
) {
    init {
        require(includesCountsAndUptime || includesDomainLogs)
        require(countsAndUptimeRowCount >= 0L && domainLogRowCount >= 0L)
        require(includesCountsAndUptime || countsAndUptimeRowCount == 0L)
        require(includesDomainLogs || domainLogRowCount == 0L)
    }
}

internal data class ActivityLocalLogDeletionReceipt(
    val predecessors: ActivityLocalLogPredecessors,
    val committedRevisions: ActivityRepositoryRevisions,
    val deletedCountsAndUptimeRows: Long,
    val deletedDomainLogRows: Long,
)

/** A local value object. Creating it never writes a file, opens a share sheet, or sends data. */
data class ActivityExportSnapshot(
    val generatedAtMillis: Long,
    val range: ActivityDateRange,
    val summary: ActivitySummary,
    val allowedTopDomains: List<ActivityDomainFrequency>,
    val blockedTopDomains: List<ActivityDomainFrequency>,
    val history: List<DnsDecisionEvent>,
)

data class ActivityExportHeader(
    val generatedAtMillis: Long,
    val range: ActivityDateRange,
    val summary: ActivitySummary,
    val allowedTopDomains: List<ActivityDomainFrequency>,
    val blockedTopDomains: List<ActivityDomainFrequency>,
)

class PinnedActivityRepositoryExport internal constructor(
    val header: ActivityExportHeader,
    private val pageReader: (ActivityHistoryCursor?, Int) -> ActivityHistoryPage,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun historyPage(cursor: ActivityHistoryCursor?, limit: Int): ActivityHistoryPage {
        check(!closed.get()) { "Activity export snapshot is closed" }
        return pageReader(cursor, limit)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }
}

internal data class ActivityRuntimeDnsRecord(val recorded: Boolean, val namespace: Long?)

/**
 * Query and mutation boundary for local DNS Activity.
 *
 * Callers pass the persisted logging controls on every record. Counts and history are not
 * coupled: either surface can be collected while the other remains disabled. All mutations
 * are transactional, and every list query requires a bounded limit.
 */
class ActivityRepository(
    private val database: ActivityDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Deterministic lock-boundary probe for repository tests; production leaves it unset. */
    internal var exportCaptureBarrier: (() -> Unit)? = null
    @Volatile
    private var exactEvidencePrunedHorizonDay: String? = null

    private data class ExactEvidencePrunePlan(
        val horizonDay: String,
        val cutoffMillis: Long,
        val upperBoundMillis: Long,
    )

    /** Reads both durable surface revisions from the same SQLite row and snapshot. */
    fun revisions(): ActivityRepositoryRevisions = database.readableDatabase.readActivityRevisions()

    private fun SQLiteDatabase.readActivityRevisions(): ActivityRepositoryRevisions = rawQuery(
        "SELECT summary_count_revision, domain_log_revision, policy_revision " +
            "FROM ${ActivityDatabase.REVISION_TABLE} WHERE singleton = 1",
        null,
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Activity revision state is missing" }
        ActivityRepositoryRevisions(
            summaryCountRevision = cursor.getLong(0),
            domainLogRevision = cursor.getLong(1),
            policyRevision = cursor.getLong(2),
        )
    }

    fun loggingPolicy(): ActivityLoggingPolicySnapshot =
        database.readableDatabase.readLoggingPolicy()

    /**
     * Commits Activity privacy policy and any required retained-data deletion in one SQLite
     * transaction. Repeating the same policy is a no-op and preserves the published generation.
     */
    fun applyLoggingPolicy(settings: LoggingSettings): ActivityLoggingPolicySnapshot =
        database.writableDatabase.inTransaction {
            val current = readLoggingPolicy()
            if (current.settings == settings) return@inTransaction current
            check(current.revision < Long.MAX_VALUE) { "Activity policy revision is saturated" }

            var domainRowsDeleted = 0
            var summaryRowsDeleted = 0
            if (!settings.keepHistory) {
                domainRowsDeleted += delete(ActivityDatabase.EVENT_TABLE, null, null)
                domainRowsDeleted += delete(ActivityDatabase.DAILY_DOMAIN_TABLE, null, null)
            }
            if (!settings.keepCounts) {
                summaryRowsDeleted += delete(ActivityDatabase.DAILY_COUNT_TABLE, null, null)
                summaryRowsDeleted += delete(ActivityDatabase.COUNT_EVENT_TABLE, null, null)
                summaryRowsDeleted += delete(ActivityDatabase.PROTECTION_SESSION_TABLE, null, null)
                summaryRowsDeleted += delete(ActivityDatabase.PROTECTION_DAILY_TABLE, null, null)
                summaryRowsDeleted += delete(ActivityDatabase.PROTECTION_INTERVAL_TABLE, null, null)
            }
            if (domainRowsDeleted > 0 || summaryRowsDeleted > 0) {
                advanceActivityRevisions(
                    summaryChanged = true,
                    domainChanged = domainRowsDeleted > 0,
                )
            }
            val updated = compileStatement(
                "UPDATE ${ActivityDatabase.REVISION_TABLE} SET " +
                    "policy_revision = policy_revision + 1, keep_counts = ?, keep_history = ?, " +
                    "keep_network_activity = ?, keep_lava_guard_progress = ? WHERE singleton = 1",
            ).use { statement ->
                statement.bindLong(1, settings.keepCounts.asSqlBoolean())
                statement.bindLong(2, settings.keepHistory.asSqlBoolean())
                statement.bindLong(3, settings.keepNetworkActivity.asSqlBoolean())
                statement.bindLong(4, settings.keepLavaGuardProgress.asSqlBoolean())
                statement.executeUpdateDelete()
            }
            check(updated == 1) { "Activity policy state is missing" }
            ActivityLoggingPolicySnapshot(settings, current.revision + 1L)
        }

    private fun SQLiteDatabase.readLoggingPolicy(): ActivityLoggingPolicySnapshot = rawQuery(
        "SELECT policy_revision, keep_counts, keep_history, keep_network_activity, " +
            "keep_lava_guard_progress FROM ${ActivityDatabase.REVISION_TABLE} WHERE singleton = 1",
        null,
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Activity policy state is missing" }
        ActivityLoggingPolicySnapshot(
            settings = LoggingSettings(
                keepCounts = cursor.getInt(1).toStrictBoolean("keep_counts"),
                keepHistory = cursor.getInt(2).toStrictBoolean("keep_history"),
                keepNetworkActivity = cursor.getInt(3).toStrictBoolean("keep_network_activity"),
                keepLavaGuardProgress = cursor.getInt(4).toStrictBoolean("keep_lava_guard_progress"),
            ),
            revision = cursor.getLong(0),
        )
    }

    /** Captures selected rows and their durable revisions from one SQLite snapshot. */
    internal fun captureLocalLogPredecessors(
        includeCountsAndUptime: Boolean,
        includeDomainLogs: Boolean,
    ): ActivityLocalLogPredecessors {
        require(includeCountsAndUptime || includeDomainLogs) {
            "At least one Activity local-log surface must be selected"
        }
        return database.writableDatabase.inTransaction {
            captureLocalLogPredecessors(includeCountsAndUptime, includeDomainLogs)
        }
    }

    /** Deletes every selected Activity surface in one revision-coupled SQLite transaction. */
    internal fun deleteLocalLogRetained(
        includeCountsAndUptime: Boolean,
        includeDomainLogs: Boolean,
    ): ActivityLocalLogDeletionReceipt {
        require(includeCountsAndUptime || includeDomainLogs) {
            "At least one Activity local-log surface must be selected"
        }
        return database.writableDatabase.inTransaction {
            val predecessors = captureLocalLogPredecessors(
                includeCountsAndUptime,
                includeDomainLogs,
            )
            val deletedDomainRows = if (includeDomainLogs) {
                delete(ActivityDatabase.EVENT_TABLE, null, null)
                delete(ActivityDatabase.DAILY_DOMAIN_TABLE, null, null)
                check(
                    rowCount(ActivityDatabase.EVENT_TABLE) == 0L &&
                        rowCount(ActivityDatabase.DAILY_DOMAIN_TABLE) == 0L,
                ) { "Activity domain clear did not remove every predecessor" }
                predecessors.domainLogRowCount
            } else {
                0L
            }
            // The immutable replay receipt includes a domain-bound digest. An explicit privacy
            // clear therefore removes it even when numeric counts remain selected separately.
            delete(ActivityDatabase.DNS_RECEIPT_TABLE, null, null)
            val deletedCountRows = if (includeCountsAndUptime) {
                delete(ActivityDatabase.DAILY_COUNT_TABLE, null, null)
                delete(ActivityDatabase.COUNT_EVENT_TABLE, null, null)
                delete(ActivityDatabase.PROTECTION_SESSION_TABLE, null, null)
                delete(ActivityDatabase.PROTECTION_DAILY_TABLE, null, null)
                delete(ActivityDatabase.PROTECTION_INTERVAL_TABLE, null, null)
                check(
                    rowCount(ActivityDatabase.DAILY_COUNT_TABLE) == 0L &&
                        rowCount(ActivityDatabase.COUNT_EVENT_TABLE) == 0L &&
                        rowCount(ActivityDatabase.PROTECTION_SESSION_TABLE) == 0L &&
                        rowCount(ActivityDatabase.PROTECTION_DAILY_TABLE) == 0L &&
                        rowCount(ActivityDatabase.PROTECTION_INTERVAL_TABLE) == 0L,
                ) { "Activity count clear did not remove every predecessor" }
                predecessors.countsAndUptimeRowCount
            } else {
                0L
            }
            advanceActivityRevisions(
                summaryChanged = deletedCountRows > 0L || deletedDomainRows > 0L,
                domainChanged = deletedDomainRows > 0L,
            )
            ActivityLocalLogDeletionReceipt(
                predecessors = predecessors,
                committedRevisions = readActivityRevisions(),
                deletedCountsAndUptimeRows = deletedCountRows,
                deletedDomainLogRows = deletedDomainRows,
            )
        }
    }

    private fun SQLiteDatabase.captureLocalLogPredecessors(
        includeCountsAndUptime: Boolean,
        includeDomainLogs: Boolean,
    ): ActivityLocalLogPredecessors = ActivityLocalLogPredecessors(
        revisions = readActivityRevisions(),
        includesCountsAndUptime = includeCountsAndUptime,
        includesDomainLogs = includeDomainLogs,
        countsAndUptimeRowCount = if (includeCountsAndUptime) {
            rowCount(ActivityDatabase.DAILY_COUNT_TABLE) +
                rowCount(ActivityDatabase.COUNT_EVENT_TABLE) +
                rowCount(ActivityDatabase.PROTECTION_SESSION_TABLE) +
                rowCount(ActivityDatabase.PROTECTION_DAILY_TABLE) +
                rowCount(ActivityDatabase.PROTECTION_INTERVAL_TABLE)
        } else {
            0L
        },
        domainLogRowCount = if (includeDomainLogs) {
            rowCount(ActivityDatabase.EVENT_TABLE) +
                rowCount(ActivityDatabase.DAILY_DOMAIN_TABLE)
        } else {
            0L
        },
    )

    private fun SQLiteDatabase.rowCount(table: String): Long = rawQuery(
        "SELECT COUNT(*) FROM $table",
        null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    fun record(event: DnsDecisionEvent, logging: LoggingSettings): Boolean =
        recordWithNamespace(event, { logging }, { 0L })

    /** Runtime replay identity is local to one admitted writer, never a network/query ID rewrite. */
    internal fun recordRuntime(event: DnsDecisionEvent, namespace: Long?): ActivityRuntimeDnsRecord {
        require(event.id > 0L) { "Runtime DNS producer identity must be positive" }
        require(namespace == null || namespace > 0L) { "Runtime DNS namespace must be positive" }
        var acceptedNamespace = namespace
        val recorded = recordWithNamespace(event, { readLoggingPolicy().settings }) {
            acceptedNamespace ?: reserveDnsProducerNamespace().also { acceptedNamespace = it }
        }
        return ActivityRuntimeDnsRecord(recorded, if (recorded) acceptedNamespace else namespace)
    }

    private fun SQLiteDatabase.reserveDnsProducerNamespace(): Long {
        val previous = rawQuery(
            "SELECT last_namespace FROM ${ActivityDatabase.DNS_PRODUCER_NAMESPACE_TABLE} WHERE singleton = 1",
            null,
        ).use { cursor ->
            check(cursor.moveToFirst()) { "DNS producer namespace state is missing" }
            cursor.getLong(0)
        }
        check(previous < Long.MAX_VALUE) { "DNS producer namespace space is exhausted" }
        val next = previous + 1L
        execSQL(
            "UPDATE ${ActivityDatabase.DNS_PRODUCER_NAMESPACE_TABLE} SET last_namespace = ? WHERE singleton = 1",
            arrayOf<Any>(next),
        )
        return next
    }

    private fun recordWithNamespace(
        event: DnsDecisionEvent,
        loggingSource: SQLiteDatabase.() -> LoggingSettings,
        namespaceSource: SQLiteDatabase.() -> Long,
    ): Boolean {
        val trustedNowMillis = clock.millis()
        val exactEvidencePrune = exactEvidencePrunePlan(trustedNowMillis)
        val recorded = database.writableDatabase.inTransaction {
            // Read committed opt-out in the same SQLite transaction as namespace reservation.
            val logging = loggingSource()
            if (!logging.keepCounts && !logging.keepHistory) return@inTransaction false
            if (event.decision.reason == FilterDecisionReason.PROTECTION_UNAVAILABLE) return@inTransaction false

            val retainableEvidence = ActivityHistoryPolicy.isRetainable(
                event.timestampMillis,
                trustedNowMillis,
            )
            val retainDetailedEvent = logging.keepHistory &&
                retainableEvidence

            val domain = event.normalizedDomain.trim().lowercase(Locale.ROOT)
            require(domain.isNotEmpty()) { "normalizedDomain must not be empty" }
            val eventDate = Instant.ofEpochMilli(event.timestampMillis).atZone(zoneId).toLocalDate()
            val dayKey = eventDate.toString()
            val dayEndEpochMillis = eventDate
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            val retainCountEvidence = logging.keepCounts &&
                (event.timestampMillis <= trustedNowMillis || retainableEvidence)
            val retainDailyDomain = logging.keepHistory && retainableEvidence &&
                dayEndEpochMillis > ActivityHistoryPolicy.cutoffMillis(trustedNowMillis)
            if (!retainCountEvidence && !retainDetailedEvent && !retainDailyDomain) return@inTransaction false
            val action = event.decision.action.name

            if (event.id > 0L && !admitDnsReceipt(event, namespaceSource())) return@inTransaction false
            val exactEvidenceChanged = exactEvidencePrune
                ?.let { pruneExactCutoffEvidence(it.cutoffMillis, it.upperBoundMillis).rowsDeleted > 0 }
                ?: false
            if (retainCountEvidence) {
                incrementDailyCount(
                    dayKey = dayKey,
                    action = action,
                    timestampMillis = event.timestampMillis,
                    dayEndEpochMillis = dayEndEpochMillis,
                )
                if (event.timestampMillis <= trustedNowMillis) {
                    updateOpenProtectionEvidence(event.timestampMillis)
                }
            }
            if (retainDetailedEvent) {
                insertEvent(
                    event = event,
                    domain = domain,
                    dayKey = dayKey,
                    dayEndEpochMillis = dayEndEpochMillis,
                )
            }
            // A pause is an explicit bypass state, not an ordinary allowed-domain match.
            if (retainDailyDomain && event.decision.reason != FilterDecisionReason.PAUSED_ALLOW) {
                incrementDailyDomain(
                    dayKey = dayKey,
                    action = action,
                    domain = domain,
                    dayEndEpochMillis = dayEndEpochMillis,
                )
            }
            val domainChanged = retainDetailedEvent || retainDailyDomain
            advanceActivityRevisions(
                summaryChanged = exactEvidenceChanged || retainCountEvidence || domainChanged,
                domainChanged = domainChanged,
            )
            true
        }
        if (recorded) exactEvidencePrune?.let(::markExactEvidencePruned)
        return recorded
    }

    /** Returns false for an exact replay and rejects reuse of an immutable producer identity. */
    private fun SQLiteDatabase.admitDnsReceipt(event: DnsDecisionEvent, producerNamespace: Long): Boolean {
        val fingerprint = event.immutableFingerprint()
        val existing = rawQuery(
            "SELECT fingerprint FROM ${ActivityDatabase.DNS_RECEIPT_TABLE} " +
                "WHERE producer_namespace = ? AND event_id = ?",
            arrayOf(producerNamespace.toString(), event.id.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getBlob(0) else null
        }
        if (existing != null) {
            check(existing.contentEquals(fingerprint)) {
                "Activity telemetry event id ${event.id} was reused with different immutable data"
            }
            return false
        }
        val cutoff = retentionCutoffMillis(
            clock.millis(),
            ActivityDatabase.UPTIME_RETENTION_DAYS * 24L * 60L * 60L * 1_000L,
        )
        delete(
            ActivityDatabase.DNS_RECEIPT_TABLE,
            "timestamp_millis < ? OR timestamp_millis > ?",
            arrayOf(
                cutoff.toString(),
                ActivityHistoryPolicy.upperBoundMillis(clock.millis()).toString(),
            ),
        )
        // WITHOUT ROWID tables do not produce SQLite's last_insert_rowid(). A successful
        // first receipt can therefore return zero through insertOrThrow's row-id API. Execute
        // the insert directly: SQL errors throw and roll back the surrounding transaction.
        execSQL(
            "INSERT INTO ${ActivityDatabase.DNS_RECEIPT_TABLE}(producer_namespace, event_id, timestamp_millis, fingerprint) " +
                "VALUES(?, ?, ?, ?)",
            arrayOf<Any>(producerNamespace, event.id, event.timestampMillis, fingerprint),
        )
        return true
    }

    fun summary(range: ActivityDateRange): ActivitySummary {
        return summary(range, clock.millis())
    }

    fun summary(range: ActivityDateRange, nowMillis: Long): ActivitySummary {
        return summary(database.readableDatabase, range, nowMillis)
    }

    /** Binds a summary and its durable counts/uptime revision to one SQLite snapshot. */
    internal fun summarySnapshot(
        range: ActivityDateRange,
        nowMillis: Long,
    ): ActivitySummarySnapshot = database.readableDatabase.inTransaction {
        ActivitySummarySnapshot(
            summary = summary(this, range, nowMillis),
            summaryCountRevision = readActivityRevisions().summaryCountRevision,
        )
    }

    /** Captures every product summary value and provenance field from one SQLite snapshot. */
    internal fun productSummarySnapshot(
        range: ActivityDateRange,
        action: FilterAction,
        searchText: String,
        topDomainsLimit: Int,
        nowMillis: Long,
    ): ActivityProductSummarySnapshot = database.readableDatabase.inTransaction {
        val policy = readLoggingPolicy()
        val retained = policy.settings.keepHistory && hasDetailedActivity(this, nowMillis)
        ActivityProductSummarySnapshot(
            summary = if (policy.settings.keepCounts) {
                summary(this, range, nowMillis)
            } else {
                ActivitySummary.EMPTY
            },
            topDomains = if (policy.settings.keepHistory && retained) {
                topDomains(
                    readable = this,
                    range = range,
                    action = action,
                    searchText = searchText,
                    limit = topDomainsLimit,
                    nowMillis = nowMillis,
                )
            } else {
                emptyList()
            },
            revisions = readActivityRevisions(),
            policy = policy,
            hasRetainedDomainLogs = retained,
        )
    }

    private fun summary(
        readable: SQLiteDatabase,
        range: ActivityDateRange,
        nowMillis: Long,
        tablePrefix: String = "",
    ): ActivitySummary {
        val dailyCountTable = "$tablePrefix${ActivityDatabase.DAILY_COUNT_TABLE}"
        val countEventTable = "$tablePrefix${ActivityDatabase.COUNT_EVENT_TABLE}"
        var allowed = 0L
        var blocked = 0L
        readable.rawQuery(
            """
            SELECT action, SUM(count)
            FROM (
                SELECT action, count
                FROM $dailyCountTable AS daily
                WHERE day_key >= ? AND day_key <= ?
                    AND (
                        retention_epoch_millis <= CAST(? AS INTEGER)
                        OR NOT EXISTS(
                            SELECT 1 FROM $countEventTable AS evidence
                            WHERE evidence.day_key = daily.day_key
                                AND evidence.action = daily.action
                        )
                    )
                UNION ALL
                SELECT action, COUNT(*) AS count
                FROM $countEventTable
                WHERE day_key >= ? AND day_key <= ?
                    AND retention_epoch_millis > CAST(? AS INTEGER)
                    AND timestamp_millis <= CAST(? AS INTEGER)
                GROUP BY day_key, action
            )
            GROUP BY action
            """.trimIndent(),
            arrayOf(
                range.start.toString(),
                range.endInclusive.toString(),
                nowMillis.toString(),
                range.start.toString(),
                range.endInclusive.toString(),
                nowMillis.toString(),
                nowMillis.toString(),
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                when (cursor.getString(0)) {
                    FilterAction.ALLOW.name -> allowed = cursor.getLong(1)
                    FilterAction.BLOCK.name -> blocked = cursor.getLong(1)
                }
            }
        }
        return ActivitySummary(
            allowedCount = allowed,
            blockedCount = blocked,
            localProtectionUptimeMillis = localProtectionUptime(
                readable,
                range,
                nowMillis,
                tablePrefix,
            ),
            liveUptimeEvidenceThroughMillis = liveUptimeEvidenceThroughMillis(
                readable = readable,
                range = range,
                nowMillis = nowMillis,
                tablePrefix = tablePrefix,
            ),
        )
    }

    /** Records only transition boundaries; duplicate active/inactive observations are idempotent. */
    fun recordProtectionState(active: Boolean, atMillis: Long = clock.millis()): Boolean =
        recordProtectionStateOutcome(active, atMillis).accepted

    internal fun recordProtectionStateOutcome(
        active: Boolean,
        atMillis: Long = clock.millis(),
    ): ProtectionUptimePersistenceOutcome {
        require(atMillis >= 0L) { "atMillis must be non-negative" }
        val exactEvidencePrune = exactEvidencePrunePlan(clock.millis())
        val outcome = database.writableDatabase.inTransaction {
            val exactEvidenceChanged = exactEvidencePrune
                ?.let { pruneExactCutoffEvidence(it.cutoffMillis, it.upperBoundMillis).rowsDeleted > 0 }
                ?: false
            val changed = if (active) {
                val alreadyOpen = rawQuery(
                    "SELECT 1 FROM ${ActivityDatabase.PROTECTION_SESSION_TABLE} " +
                        "WHERE end_millis IS NULL LIMIT 1",
                    null,
                ).use { it.moveToFirst() }
                if (alreadyOpen) {
                    false
                } else {
                    insertOrThrow(
                        ActivityDatabase.PROTECTION_SESSION_TABLE,
                        null,
                        ContentValues(2).apply {
                            put("start_millis", atMillis)
                            put("last_evidence_millis", atMillis)
                        },
                    ) > 0
                }
            } else {
                val open = rawQuery(
                    "SELECT id, start_millis, last_evidence_millis " +
                        "FROM ${ActivityDatabase.PROTECTION_SESSION_TABLE} " +
                        "WHERE end_millis IS NULL LIMIT 1",
                    null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
                    } else {
                        null
                    }
                }
                if (open == null) {
                    false
                } else {
                    val end = maxOf(open.second, open.third, atMillis)
                    accumulateProtectionInterval(open.second, end)
                    delete(
                        ActivityDatabase.PROTECTION_SESSION_TABLE,
                        "id = ?",
                        arrayOf(open.first.toString()),
                    ) > 0
                }
            }
            if (changed || exactEvidenceChanged) {
                advanceActivityRevisions(summaryChanged = true, domainChanged = false)
            }
            ProtectionUptimePersistenceOutcome(
                accepted = changed,
                activeEvidenceThroughMillis = openProtectionEvidenceThroughMillis(),
                summaryCountRevision = readActivityRevisions().summaryCountRevision,
            )
        }
        exactEvidencePrune?.let(::markExactEvidencePruned)
        return outcome
    }

    /** Privacy-gated transition boundary used by application composition. */
    fun recordProtectionState(
        active: Boolean,
        keepCounts: Boolean,
        atMillis: Long = clock.millis(),
    ): Boolean = keepCounts && recordProtectionState(active, atMillis)

    /**
     * Records a moment at which the runtime was actually observed active. An existing session is
     * extended; if privacy opt-out or crash recovery removed/closed it, this evidence starts a new
     * session at [atMillis]. Nothing before this confirmed heartbeat is backfilled.
     */
    fun recordProtectionHeartbeat(atMillis: Long = clock.millis()): Boolean =
        recordProtectionHeartbeatOutcome(atMillis).accepted

    internal fun recordProtectionHeartbeatOutcome(
        atMillis: Long = clock.millis(),
    ): ProtectionUptimePersistenceOutcome {
        require(atMillis >= 0L) { "atMillis must be non-negative" }
        val exactEvidencePrune = exactEvidencePrunePlan(clock.millis())
        val outcome = database.writableDatabase.inTransaction {
            val exactEvidenceChanged = exactEvidencePrune
                ?.let { pruneExactCutoffEvidence(it.cutoffMillis, it.upperBoundMillis).rowsDeleted > 0 }
                ?: false
            var evidenceChanged = false
            val accepted = if (updateOpenProtectionEvidence(atMillis) > 0) {
                evidenceChanged = true
                true
            } else if (hasOpenProtectionSession()) {
                true
            } else {
                val inserted = insertOrThrow(
                    ActivityDatabase.PROTECTION_SESSION_TABLE,
                    null,
                    ContentValues(2).apply {
                        put("start_millis", atMillis)
                        put("last_evidence_millis", atMillis)
                    },
                ) > 0L
                evidenceChanged = inserted
                inserted
            }
            if (evidenceChanged || exactEvidenceChanged) {
                advanceActivityRevisions(summaryChanged = true, domainChanged = false)
            }
            ProtectionUptimePersistenceOutcome(
                accepted = accepted,
                activeEvidenceThroughMillis = openProtectionEvidenceThroughMillis(),
                summaryCountRevision = readActivityRevisions().summaryCountRevision,
            )
        }
        exactEvidencePrune?.let(::markExactEvidencePruned)
        return outcome
    }

    /** Privacy-gated heartbeat used by application composition. */
    fun recordProtectionHeartbeat(
        keepCounts: Boolean,
        atMillis: Long = clock.millis(),
    ): Boolean = keepCounts && recordProtectionHeartbeat(atMillis)

    fun topDomains(
        range: ActivityDateRange,
        action: FilterAction,
        searchText: String = "",
        limit: Int = DEFAULT_TOP_DOMAIN_LIMIT,
        nowMillis: Long = clock.millis(),
    ): List<ActivityDomainFrequency> {
        return topDomains(
            readable = database.readableDatabase,
            range = range,
            action = action,
            searchText = searchText,
            limit = limit,
            nowMillis = nowMillis,
        )
    }

    private fun topDomains(
        readable: SQLiteDatabase,
        range: ActivityDateRange,
        action: FilterAction,
        searchText: String,
        limit: Int,
        nowMillis: Long,
    ): List<ActivityDomainFrequency> {
        require(limit in 1..MAX_TOP_DOMAIN_LIMIT) {
            "limit must be between 1 and $MAX_TOP_DOMAIN_LIMIT"
        }
        val normalizedSearch = searchText.trim().lowercase(Locale.ROOT)
        val whereSearch = if (normalizedSearch.isEmpty()) "" else " WHERE domain LIKE ? ESCAPE '\\' COLLATE NOCASE"
        val cutoffMillis = ActivityHistoryPolicy.cutoffMillis(nowMillis)
        val maximumDayEnd = maximumCapturedDayEndMillis(
            ActivityHistoryPolicy.upperBoundMillis(nowMillis),
        )
        val args = buildList {
            add(range.start.toString())
            add(range.endInclusive.toString())
            add(action.name)
            add(cutoffMillis.toString())
            add(maximumDayEnd.toString())
            add(nowMillis.toString())
            add(range.start.toString())
            add(range.endInclusive.toString())
            add(action.name)
            add(cutoffMillis.toString())
            add(maximumDayEnd.toString())
            add(nowMillis.toString())
            add(nowMillis.toString())
            if (normalizedSearch.isNotEmpty()) add(likePattern(normalizedSearch))
            add(limit.toString())
        }.toTypedArray()

        return readable.rawQuery(
            """
            SELECT domain, SUM(count) AS total_count, SUM(error_count) AS total_error
            FROM (
                SELECT domain, count, error_count
                FROM ${ActivityDatabase.DAILY_DOMAIN_TABLE}
                WHERE day_key >= ? AND day_key <= ? AND action = ?
                    AND retention_epoch_millis > CAST(? AS INTEGER)
                    AND retention_epoch_millis <= CAST(? AS INTEGER)
                    AND retention_epoch_millis <= CAST(? AS INTEGER)
                UNION ALL
                SELECT domain, COUNT(*) AS count, 0 AS error_count
                FROM ${ActivityDatabase.EVENT_TABLE}
                WHERE day_key >= ? AND day_key <= ? AND action = ?
                    AND retention_epoch_millis > CAST(? AS INTEGER)
                    AND retention_epoch_millis <= CAST(? AS INTEGER)
                    AND retention_epoch_millis > CAST(? AS INTEGER)
                    AND timestamp_millis <= CAST(? AS INTEGER)
                    AND reason != '${FilterDecisionReason.PAUSED_ALLOW.name}'
                GROUP BY day_key, action, domain
            )$whereSearch
            GROUP BY domain
            ORDER BY total_count DESC, domain COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent(),
            args,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ActivityDomainFrequency(
                            domain = cursor.getString(0),
                            count = cursor.getLong(1),
                            errorFloor = cursor.getLong(2),
                        ),
                    )
                }
            }
        }
    }

    /** Export-only query: completed days use compact aggregates; the live day uses raw evidence. */
    private fun exportTopDomains(
        readable: SQLiteDatabase,
        range: ActivityDateRange,
        action: FilterAction,
        generatedAtMillis: Long,
        tablePrefix: String = "",
    ): List<ActivityDomainFrequency> {
        val dailyDomainTable = "$tablePrefix${ActivityDatabase.DAILY_DOMAIN_TABLE}"
        val eventTable = "$tablePrefix${ActivityDatabase.EVENT_TABLE}"
        val cutoffMillis = ActivityHistoryPolicy.cutoffMillis(generatedAtMillis)
        val maximumDayEnd = maximumCapturedDayEndMillis(generatedAtMillis)
        return readable.rawQuery(
            """
            SELECT domain, SUM(count) AS total_count, SUM(error_count) AS total_error
            FROM (
                SELECT domain, count, error_count
                FROM $dailyDomainTable
                WHERE day_key >= ? AND day_key <= ? AND action = ?
                    AND retention_epoch_millis > CAST(? AS INTEGER)
                    AND retention_epoch_millis <= CAST(? AS INTEGER)
                    AND retention_epoch_millis <= CAST(? AS INTEGER)
                UNION ALL
                SELECT domain, COUNT(*) AS count, 0 AS error_count
                FROM $eventTable
                WHERE day_key >= ? AND day_key <= ? AND action = ?
                    AND retention_epoch_millis > CAST(? AS INTEGER)
                    AND retention_epoch_millis <= CAST(? AS INTEGER)
                    AND retention_epoch_millis > CAST(? AS INTEGER)
                    AND timestamp_millis <= CAST(? AS INTEGER)
                    AND reason != '${FilterDecisionReason.PAUSED_ALLOW.name}'
                GROUP BY day_key, action, domain
            )
            GROUP BY domain
            ORDER BY total_count DESC, domain COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                range.start.toString(),
                range.endInclusive.toString(),
                action.name,
                cutoffMillis.toString(),
                maximumDayEnd.toString(),
                generatedAtMillis.toString(),
                range.start.toString(),
                range.endInclusive.toString(),
                action.name,
                cutoffMillis.toString(),
                maximumDayEnd.toString(),
                generatedAtMillis.toString(),
                generatedAtMillis.toString(),
                MAX_TOP_DOMAIN_LIMIT.toString(),
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ActivityDomainFrequency(
                            domain = cursor.getString(0),
                            count = cursor.getLong(1),
                            errorFloor = cursor.getLong(2),
                        ),
                    )
                }
            }
        }
    }

    /** Domain History is always a rolling seven-day view, independent of the digest range. */
    fun history(
        action: FilterAction? = null,
        searchText: String = "",
        limit: Int = DEFAULT_HISTORY_LIMIT,
        nowMillis: Long = clock.millis(),
    ): List<DnsDecisionEvent> = historyPage(
        action = action,
        searchText = searchText,
        limit = limit,
        nowMillis = nowMillis,
    ).entries

    fun historyPage(
        action: FilterAction? = null,
        searchText: String = "",
        before: ActivityHistoryCursor? = null,
        limit: Int = DEFAULT_HISTORY_LIMIT,
        nowMillis: Long = clock.millis(),
    ): ActivityHistoryPage = historyPage(
        readable = database.readableDatabase,
        action = action,
        searchText = searchText,
        before = before,
        limit = limit,
        nowMillis = nowMillis,
    )

    /**
     * Product-shaped Domain History paging. The first page freezes a durable row-id watermark and
     * capture time; every continuation reuses both while fetching one 31st-row sentinel.
     */
    fun domainHistoryPage(
        action: FilterAction? = null,
        searchText: String = "",
        token: ActivityHistoryPageToken? = null,
    ): ActivityHistoryPage {
        val readable = database.readableDatabase
        val cut = token?.cut ?: captureHistoryCut(readable, clock.millis())
        return queryHistoryPage(
            readable = readable,
            action = action,
            searchText = searchText,
            before = token?.beforeExclusive,
            visibleLimit = DOMAIN_HISTORY_PAGE_SIZE,
            cut = cut,
        )
    }

    /** Captures policy, availability, revision, cut, and rows from one SQLite snapshot. */
    internal fun productDomainHistorySnapshot(
        action: FilterAction,
        searchText: String,
        token: ActivityHistoryPageToken?,
        nowMillis: Long,
    ): ActivityProductHistorySnapshot = database.readableDatabase.inTransaction {
        val policy = readLoggingPolicy()
        val retained = policy.settings.keepHistory && hasDetailedActivity(this, nowMillis)
        val page = if (policy.settings.keepHistory) {
            val cut = token?.cut ?: captureHistoryCut(this, nowMillis)
            queryHistoryPage(
                readable = this,
                action = action,
                searchText = searchText,
                before = token?.beforeExclusive,
                visibleLimit = DOMAIN_HISTORY_PAGE_SIZE,
                cut = cut,
            )
        } else {
            null
        }
        ActivityProductHistorySnapshot(
            page = page,
            revisions = readActivityRevisions(),
            policy = policy,
            hasRetainedDomainLogs = retained,
        )
    }

    private fun historyPage(
        readable: SQLiteDatabase,
        action: FilterAction?,
        searchText: String,
        before: ActivityHistoryCursor?,
        limit: Int,
        nowMillis: Long,
    ): ActivityHistoryPage {
        require(limit in 1..MAX_HISTORY_LIMIT) {
            "limit must be between 1 and $MAX_HISTORY_LIMIT"
        }
        return queryHistoryPage(
            readable = readable,
            action = action,
            searchText = searchText,
            before = before,
            visibleLimit = limit,
            cut = captureHistoryCut(readable, nowMillis),
        )
    }

    private fun queryHistoryPage(
        readable: SQLiteDatabase,
        action: FilterAction?,
        searchText: String,
        before: ActivityHistoryCursor?,
        visibleLimit: Int,
        cut: ActivityHistoryCut,
    ): ActivityHistoryPage {
        val cutoffMillis = ActivityHistoryPolicy.cutoffMillis(cut.capturedAtMillis)
        val upperBoundMillis = ActivityHistoryPolicy.upperBoundMillis(cut.capturedAtMillis)
        val normalizedSearch = searchText.trim().lowercase(Locale.ROOT)
        val predicates = mutableListOf(
            "timestamp_millis >= ?",
            "timestamp_millis <= ?",
            "id <= ?",
        )
        val args = mutableListOf(
            cutoffMillis.toString(),
            upperBoundMillis.toString(),
            cut.maxRowId.toString(),
        )
        if (action != null) {
            predicates += "action = ?"
            args += action.name
        }
        if (normalizedSearch.isNotEmpty()) {
            predicates += "domain LIKE ? ESCAPE '\\' COLLATE NOCASE"
            args += likePattern(normalizedSearch)
        }
        if (before != null) {
            predicates += "(timestamp_millis < ? OR (timestamp_millis = ? AND id < ?))"
            args += before.timestampMillis.toString()
            args += before.timestampMillis.toString()
            args += before.id.toString()
        }
        args += (visibleLimit + 1).toString()

        val fetched = readable.rawQuery(
            """
            SELECT id, timestamp_millis, domain, action, reason,
                resolver, transport, latency_millis, error
            FROM ${ActivityDatabase.EVENT_TABLE}
            WHERE ${predicates.joinToString(" AND ")}
            ORDER BY timestamp_millis DESC, id DESC
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray(),
        ).use(::readEvents)
        val rows = fetched.take(visibleLimit)
        return ActivityHistoryPage(
            rows = rows,
            next = rows.lastOrNull()
                ?.takeIf { fetched.size > visibleLimit }
                ?.let { row ->
                    ActivityHistoryPageToken(
                        cut = cut,
                        beforeExclusive = ActivityHistoryCursor(row.timestampMillis, row.id),
                    )
                },
            cut = cut,
        )
    }

    private fun captureHistoryCut(
        readable: SQLiteDatabase,
        capturedAtMillis: Long,
    ): ActivityHistoryCut = ActivityHistoryCut(
        maxRowId = readable.rawQuery(
            "SELECT COALESCE(MAX(id), 0) FROM ${ActivityDatabase.EVENT_TABLE}",
            null,
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        },
        capturedAtMillis = capturedAtMillis,
    )

    /**
     * Physically expires browsing detail. Numeric day totals are intentionally untouched.
     * Per-domain aggregates retain the local day-end epoch captured at write time and disappear
     * only after the whole original local day is outside the rolling window, matching iOS.
     */
    fun pruneDetailedHistory(nowMillis: Long = clock.millis()): ActivityPruneResult {
        return database.pruneDetailedHistory(
            cutoffMillis = ActivityHistoryPolicy.cutoffMillis(nowMillis),
            upperBoundMillis = ActivityHistoryPolicy.upperBoundMillis(nowMillis),
            exactCutoffMillis = rollbackSafeExactEvidenceCutoffMillis(nowMillis),
        )
    }

    /** Clears row-level history and Top Domains while preserving numeric digest totals. */
    fun deleteDetailedActivity() {
        database.writableDatabase.inTransaction {
            val deleted = delete(ActivityDatabase.EVENT_TABLE, null, null) +
                delete(ActivityDatabase.DAILY_DOMAIN_TABLE, null, null)
            if (deleted > 0) {
                advanceActivityRevisions(summaryChanged = true, domainChanged = true)
            }
        }
    }

    @Deprecated("Use deleteDetailedActivity; Activity deletion preserves counts and uptime")
    fun deleteHistory() = deleteDetailedActivity()

    /**
     * Enforces persisted privacy controls against already-collected local data. Disabling counts
     * clears both numeric digest rows and protection-session evidence; disabling Domain Logs clears
     * event history and Top Domains. This operation is idempotent and local-only.
     */
    fun enforceLoggingSettings(logging: LoggingSettings): Boolean =
        database.writableDatabase.inTransaction {
            var domainRowsDeleted = 0
            var summaryRowsDeleted = 0
            if (!logging.keepHistory) {
                domainRowsDeleted += delete(ActivityDatabase.EVENT_TABLE, null, null)
                domainRowsDeleted += delete(ActivityDatabase.DAILY_DOMAIN_TABLE, null, null)
            }
            if (!logging.keepCounts) {
                summaryRowsDeleted += delete(ActivityDatabase.DAILY_COUNT_TABLE, null, null)
                summaryRowsDeleted += delete(ActivityDatabase.COUNT_EVENT_TABLE, null, null)
                summaryRowsDeleted += delete(ActivityDatabase.PROTECTION_SESSION_TABLE, null, null)
                summaryRowsDeleted += delete(ActivityDatabase.PROTECTION_DAILY_TABLE, null, null)
                summaryRowsDeleted += delete(ActivityDatabase.PROTECTION_INTERVAL_TABLE, null, null)
            }
            val changed = domainRowsDeleted > 0 || summaryRowsDeleted > 0
            if (changed) {
                advanceActivityRevisions(
                    summaryChanged = true,
                    domainChanged = domainRowsDeleted > 0,
                )
            }
            changed
        }

    fun hasDetailedActivity(nowMillis: Long = clock.millis()): Boolean {
        return hasDetailedActivity(database.readableDatabase, nowMillis)
    }

    private fun hasDetailedActivity(readable: SQLiteDatabase, nowMillis: Long): Boolean {
        val cutoff = ActivityHistoryPolicy.cutoffMillis(nowMillis)
        val upperBound = ActivityHistoryPolicy.upperBoundMillis(nowMillis)
        val maximumDayEnd = maximumCapturedDayEndMillis(upperBound)
        return readable.rawQuery(
            """
            SELECT EXISTS(
                SELECT 1 FROM ${ActivityDatabase.EVENT_TABLE}
                WHERE timestamp_millis >= CAST(? AS INTEGER)
                    AND timestamp_millis <= CAST(? AS INTEGER)
            ) OR EXISTS(
                SELECT 1 FROM ${ActivityDatabase.DAILY_DOMAIN_TABLE}
                WHERE retention_epoch_millis > CAST(? AS INTEGER)
                    AND retention_epoch_millis <= CAST(? AS INTEGER)
            )
            """.trimIndent(),
            arrayOf(
                cutoff.toString(),
                upperBound.toString(),
                cutoff.toString(),
                maximumDayEnd.toString(),
            ),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0) != 0
        }
    }

    /** Explicit destructive boundary for every local Activity surface. */
    fun deleteAll() {
        database.writableDatabase.inTransaction {
            val domainRowsDeleted = delete(ActivityDatabase.EVENT_TABLE, null, null) +
                delete(ActivityDatabase.DAILY_DOMAIN_TABLE, null, null)
            val summaryRowsDeleted = delete(ActivityDatabase.DAILY_COUNT_TABLE, null, null) +
                delete(ActivityDatabase.COUNT_EVENT_TABLE, null, null) +
                delete(ActivityDatabase.PROTECTION_SESSION_TABLE, null, null) +
                delete(ActivityDatabase.PROTECTION_DAILY_TABLE, null, null) +
                delete(ActivityDatabase.PROTECTION_INTERVAL_TABLE, null, null)
            delete(ActivityDatabase.DNS_RECEIPT_TABLE, null, null)
            if (domainRowsDeleted > 0 || summaryRowsDeleted > 0) {
                advanceActivityRevisions(
                    summaryChanged = true,
                    domainChanged = domainRowsDeleted > 0,
                )
            }
        }
    }

    /**
     * Builds export data only when explicitly invoked. The range scopes the digest and Top
     * Domains; Domain History keeps its independent rolling-seven-day contract.
     */
    fun exportSnapshot(
        range: ActivityDateRange,
        includeCounts: Boolean = true,
        includeHistory: Boolean = true,
    ): ActivityExportSnapshot = withExportSnapshot(
        range = range,
        includeCounts = includeCounts,
        includeHistory = includeHistory,
    ) { header, historyPage ->
        val history = if (includeHistory) {
            buildList {
                var cursor: ActivityHistoryCursor? = null
                do {
                    val page = historyPage(cursor, MAX_HISTORY_LIMIT)
                    addAll(page.entries)
                    cursor = page.nextCursor
                } while (cursor != null)
            }
        } else {
            emptyList()
        }
        ActivityExportSnapshot(
            generatedAtMillis = header.generatedAtMillis,
            range = header.range,
            summary = header.summary,
            allowedTopDomains = header.allowedTopDomains,
            blockedTopDomains = header.blockedTopDomains,
            history = history,
        )
    }

    /**
     * Captures one immutable export into a short-lived SQLite sidecar, then releases the attached
     * live snapshot before [block] starts. Android 9 has no public deferred read-only transaction
     * API, so the sidecar uses an attached WAL source plus a SQLite SAVEPOINT. That pins one read
     * view while keeping the source writer available during both capture and page-by-page drain.
     */
    fun <T> withExportSnapshot(
        range: ActivityDateRange,
        includeCounts: Boolean = true,
        includeHistory: Boolean = true,
        block: (
            ActivityExportHeader,
            (ActivityHistoryCursor?, Int) -> ActivityHistoryPage,
        ) -> T,
    ): T = pinExportSnapshotInternal(
        range = range,
        generatedAtMillis = clock::millis,
        includeCounts = includeCounts,
        includeHistory = includeHistory,
    ).use { pinned ->
        block(pinned.header, pinned::historyPage)
    }

    fun pinExportSnapshot(
        range: ActivityDateRange,
        generatedAtMillis: Long,
        includeCounts: Boolean = true,
        includeHistory: Boolean = true,
    ): PinnedActivityRepositoryExport {
        require(generatedAtMillis >= 0L)
        return pinExportSnapshotInternal(
            range = range,
            generatedAtMillis = { generatedAtMillis },
            includeCounts = includeCounts,
            includeHistory = includeHistory,
        )
    }

    private fun pinExportSnapshotInternal(
        range: ActivityDateRange,
        generatedAtMillis: () -> Long,
        includeCounts: Boolean,
        includeHistory: Boolean,
    ): PinnedActivityRepositoryExport {
        val snapshotFile = database.createExportSnapshotFile()
        val snapshotDatabase = try {
            SQLiteDatabase.openOrCreateDatabase(snapshotFile, null)
        } catch (failure: Throwable) {
            database.deleteExportSnapshotFile(snapshotFile)
            throw failure
        }
        try {
            initializeExportSnapshotDatabase(snapshotDatabase)
            val header = captureExportSnapshot(
                snapshotDatabase = snapshotDatabase,
                range = range,
                generatedAtMillis = generatedAtMillis,
                includeCounts = includeCounts,
                includeHistory = includeHistory,
            )
            return PinnedActivityRepositoryExport(
                header = header,
                pageReader = { cursor, pageSize ->
                    historyPage(
                        readable = snapshotDatabase,
                        action = null,
                        searchText = "",
                        before = cursor,
                        limit = pageSize,
                        nowMillis = header.generatedAtMillis,
                    )
                },
                closeAction = {
                    try {
                        snapshotDatabase.close()
                    } finally {
                        database.deleteExportSnapshotFile(snapshotFile)
                    }
                },
            )
        } catch (failure: Throwable) {
            try {
                snapshotDatabase.close()
            } finally {
                database.deleteExportSnapshotFile(snapshotFile)
            }
            throw failure
        }
    }

    private fun initializeExportSnapshotDatabase(snapshot: SQLiteDatabase) {
        snapshot.execSQL(
            """
            CREATE TABLE ${ActivityDatabase.EVENT_TABLE} (
                id INTEGER PRIMARY KEY,
                timestamp_millis INTEGER NOT NULL,
                domain TEXT NOT NULL,
                action TEXT NOT NULL,
                reason TEXT NOT NULL,
                resolver TEXT,
                transport TEXT,
                latency_millis INTEGER,
                error TEXT
            )
            """.trimIndent(),
        )
        snapshot.execSQL(
            "CREATE INDEX idx_export_activity_event_time " +
                "ON ${ActivityDatabase.EVENT_TABLE}(timestamp_millis DESC, id DESC)",
        )
    }

    private fun captureExportSnapshot(
        snapshotDatabase: SQLiteDatabase,
        range: ActivityDateRange,
        generatedAtMillis: () -> Long,
        includeCounts: Boolean,
        includeHistory: Boolean,
    ): ActivityExportHeader {
        val source = database.readableDatabase
        val sourcePath = source.path
        if (sourcePath == ":memory:") {
            return captureInMemoryExportSnapshot(
                source = source,
                snapshotDatabase = snapshotDatabase,
                range = range,
                generatedAtMillis = generatedAtMillis,
                includeCounts = includeCounts,
                includeHistory = includeHistory,
            )
        }

        attachActivityDatabase(snapshotDatabase, sourcePath)
        snapshotDatabase.execSQL("SAVEPOINT activity_export_snapshot")
        return try {
            // SAVEPOINT is a real deferred SQLite transaction (unlike Android's intercepted
            // BEGIN). This read pins the attached WAL snapshot without reserving its writer.
            snapshotDatabase.rawQuery("SELECT 1 FROM live.sqlite_master LIMIT 1", null).use {
                it.moveToFirst()
            }
            val generatedAtMillis = generatedAtMillis()
            exportCaptureBarrier?.invoke()
            val header = buildExportHeader(
                readable = snapshotDatabase,
                tablePrefix = "live.",
                range = range,
                generatedAtMillis = generatedAtMillis,
                includeCounts = includeCounts,
                includeHistory = includeHistory,
            )
            if (includeHistory) {
                copyAttachedExportHistory(snapshotDatabase, generatedAtMillis)
            }
            snapshotDatabase.execSQL("RELEASE SAVEPOINT activity_export_snapshot")
            header
        } catch (failure: Throwable) {
            runCatching {
                snapshotDatabase.execSQL("ROLLBACK TO SAVEPOINT activity_export_snapshot")
                snapshotDatabase.execSQL("RELEASE SAVEPOINT activity_export_snapshot")
            }
            throw failure
        } finally {
            snapshotDatabase.execSQL("DETACH DATABASE live")
        }
    }

    private fun captureInMemoryExportSnapshot(
        source: SQLiteDatabase,
        snapshotDatabase: SQLiteDatabase,
        range: ActivityDateRange,
        generatedAtMillis: () -> Long,
        includeCounts: Boolean,
        includeHistory: Boolean,
    ): ActivityExportHeader {
        source.beginTransactionNonExclusive()
        snapshotDatabase.beginTransaction()
        return try {
            source.rawQuery("SELECT 1 FROM sqlite_master LIMIT 1", null).use { it.moveToFirst() }
            val generatedAtMillis = generatedAtMillis()
            exportCaptureBarrier?.invoke()
            val header = buildExportHeader(
                readable = source,
                tablePrefix = "",
                range = range,
                generatedAtMillis = generatedAtMillis,
                includeCounts = includeCounts,
                includeHistory = includeHistory,
            )
            if (includeHistory) copyInMemoryExportHistory(source, snapshotDatabase, generatedAtMillis)
            snapshotDatabase.setTransactionSuccessful()
            source.setTransactionSuccessful()
            header
        } finally {
            try {
                snapshotDatabase.endTransaction()
            } finally {
                source.endTransaction()
            }
        }
    }

    private fun buildExportHeader(
        readable: SQLiteDatabase,
        tablePrefix: String,
        range: ActivityDateRange,
        generatedAtMillis: Long,
        includeCounts: Boolean,
        includeHistory: Boolean,
    ): ActivityExportHeader = ActivityExportHeader(
        generatedAtMillis = generatedAtMillis,
        range = range,
        summary = if (includeCounts) {
            summary(readable, range, generatedAtMillis, tablePrefix)
        } else {
            ActivitySummary.EMPTY
        },
        allowedTopDomains = if (includeHistory) {
            exportTopDomains(
                readable = readable,
                range = range,
                action = FilterAction.ALLOW,
                generatedAtMillis = generatedAtMillis,
                tablePrefix = tablePrefix,
            )
        } else {
            emptyList()
        },
        blockedTopDomains = if (includeHistory) {
            exportTopDomains(
                readable = readable,
                range = range,
                action = FilterAction.BLOCK,
                generatedAtMillis = generatedAtMillis,
                tablePrefix = tablePrefix,
            )
        } else {
            emptyList()
        },
    )

    private fun attachActivityDatabase(snapshotDatabase: SQLiteDatabase, sourcePath: String) {
        val readOnlyUri = File(sourcePath).toURI().toASCIIString() + "?mode=ro"
        try {
            snapshotDatabase.execSQL("ATTACH DATABASE ? AS live", arrayOf(readOnlyUri))
        } catch (_: RuntimeException) {
            // Older vendor SQLite builds may omit URI filename support. This connection still
            // issues no writes against `live`; the plain path preserves the same WAL semantics.
            snapshotDatabase.execSQL("ATTACH DATABASE ? AS live", arrayOf(sourcePath))
        }
    }

    private fun copyAttachedExportHistory(
        snapshot: SQLiteDatabase,
        generatedAtMillis: Long,
    ) {
        snapshot.execSQL(
            """
            INSERT INTO main.${ActivityDatabase.EVENT_TABLE}(
                id, timestamp_millis, domain, action, reason,
                resolver, transport, latency_millis, error
            )
            SELECT id, timestamp_millis, domain, action, reason,
                resolver, transport, latency_millis, error
            FROM live.${ActivityDatabase.EVENT_TABLE}
            WHERE timestamp_millis >= CAST(? AS INTEGER)
                AND timestamp_millis <= CAST(? AS INTEGER)
            ORDER BY timestamp_millis DESC, id DESC
            """.trimIndent(),
            arrayOf(
                ActivityHistoryPolicy.cutoffMillis(generatedAtMillis),
                generatedAtMillis,
            ),
        )
    }

    private fun copyInMemoryExportHistory(
        source: SQLiteDatabase,
        target: SQLiteDatabase,
        generatedAtMillis: Long,
    ) {
        val cutoffMillis = ActivityHistoryPolicy.cutoffMillis(generatedAtMillis)
        source.rawQuery(
            """
            SELECT id, timestamp_millis, domain, action, reason,
                resolver, transport, latency_millis, error
            FROM ${ActivityDatabase.EVENT_TABLE}
            WHERE timestamp_millis >= CAST(? AS INTEGER)
                AND timestamp_millis <= CAST(? AS INTEGER)
            ORDER BY timestamp_millis DESC, id DESC
            """.trimIndent(),
            arrayOf(cutoffMillis.toString(), generatedAtMillis.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                target.insertOrThrow(
                    ActivityDatabase.EVENT_TABLE,
                    null,
                    ContentValues(9).apply {
                        put("id", cursor.getLong(0))
                        put("timestamp_millis", cursor.getLong(1))
                        put("domain", cursor.getString(2))
                        put("action", cursor.getString(3))
                        put("reason", cursor.getString(4))
                        if (cursor.isNull(5)) putNull("resolver") else put("resolver", cursor.getString(5))
                        if (cursor.isNull(6)) putNull("transport") else put("transport", cursor.getString(6))
                        if (cursor.isNull(7)) putNull("latency_millis") else put("latency_millis", cursor.getLong(7))
                        if (cursor.isNull(8)) putNull("error") else put("error", cursor.getString(8))
                    },
                )
            }
        }
    }

    private fun SQLiteDatabase.insertEvent(
        event: DnsDecisionEvent,
        domain: String,
        dayKey: String,
        dayEndEpochMillis: Long,
    ) {
        val values = ContentValues(10).apply {
            put("timestamp_millis", event.timestampMillis)
            put("day_key", dayKey)
            put("retention_epoch_millis", dayEndEpochMillis)
            put("domain", domain)
            put("action", event.decision.action.name)
            put("reason", event.decision.reason.name)
            event.resolver?.let { put("resolver", it.name) } ?: putNull("resolver")
            event.transport?.let { put("transport", it.name) } ?: putNull("transport")
            event.latencyMillis?.let { put("latency_millis", it) } ?: putNull("latency_millis")
            event.error?.let { put("error", it.name) } ?: putNull("error")
        }
        check(insertOrThrow(ActivityDatabase.EVENT_TABLE, null, values) > 0) {
            "Failed to persist Activity event"
        }
    }

    private fun SQLiteDatabase.incrementDailyCount(
        dayKey: String,
        action: String,
        timestampMillis: Long,
        dayEndEpochMillis: Long,
    ) {
        val updated = compileStatement(
            "UPDATE ${ActivityDatabase.DAILY_COUNT_TABLE} " +
                "SET count = count + 1, " +
                "retention_epoch_millis = MAX(retention_epoch_millis, ?) " +
                "WHERE day_key = ? AND action = ?",
        ).useStatement { statement ->
            statement.bindLong(1, dayEndEpochMillis)
            statement.bindString(2, dayKey)
            statement.bindString(3, action)
            statement.executeUpdateDelete()
        }
        if (updated == 0) {
            insertOrThrow(
                ActivityDatabase.DAILY_COUNT_TABLE,
                null,
                ContentValues(4).apply {
                    put("day_key", dayKey)
                    put("action", action)
                    put("count", 1)
                    put("retention_epoch_millis", dayEndEpochMillis)
                },
            )
        }
        insertOrThrow(
            ActivityDatabase.COUNT_EVENT_TABLE,
            null,
            ContentValues(4).apply {
                put("timestamp_millis", timestampMillis)
                put("day_key", dayKey)
                put("action", action)
                put("retention_epoch_millis", dayEndEpochMillis)
            },
        )
    }

    private fun exactEvidencePrunePlan(nowMillis: Long): ExactEvidencePrunePlan? {
        val compactionHorizonMillis = rollbackSafeExactEvidenceCutoffMillis(nowMillis)
        val compactionHorizonDay = Instant.ofEpochMilli(compactionHorizonMillis)
            .atZone(zoneId)
            .toLocalDate()
            .toString()
        return synchronized(this) {
            if (exactEvidencePrunedHorizonDay == compactionHorizonDay) {
                null
            } else {
                ExactEvidencePrunePlan(
                    horizonDay = compactionHorizonDay,
                    cutoffMillis = compactionHorizonMillis,
                    upperBoundMillis = ActivityHistoryPolicy.upperBoundMillis(nowMillis),
                )
            }
        }
    }

    private fun markExactEvidencePruned(plan: ExactEvidencePrunePlan) {
        synchronized(this) {
            exactEvidencePrunedHorizonDay = plan.horizonDay
        }
    }

    private fun SQLiteDatabase.updateOpenProtectionEvidence(atMillis: Long): Int =
        compileStatement(
            "UPDATE ${ActivityDatabase.PROTECTION_SESSION_TABLE} " +
                "SET last_evidence_millis = MAX(last_evidence_millis, start_millis, ?) " +
                "WHERE end_millis IS NULL AND last_evidence_millis < ?",
        ).useStatement { statement ->
            statement.bindLong(1, atMillis)
            statement.bindLong(2, atMillis)
            statement.executeUpdateDelete()
        }

    private fun SQLiteDatabase.hasOpenProtectionSession(): Boolean = rawQuery(
        "SELECT 1 FROM ${ActivityDatabase.PROTECTION_SESSION_TABLE} " +
            "WHERE end_millis IS NULL LIMIT 1",
        null,
    ).use { it.moveToFirst() }

    private fun SQLiteDatabase.openProtectionEvidenceThroughMillis(): Long? = rawQuery(
        "SELECT MAX(last_evidence_millis) " +
            "FROM ${ActivityDatabase.PROTECTION_SESSION_TABLE} WHERE end_millis IS NULL",
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
    }

    private fun SQLiteDatabase.incrementDailyDomain(
        dayKey: String,
        action: String,
        domain: String,
        dayEndEpochMillis: Long,
    ) {
        val updated = compileStatement(
            "UPDATE ${ActivityDatabase.DAILY_DOMAIN_TABLE} " +
                "SET count = count + 1, retention_epoch_millis = MAX(retention_epoch_millis, ?) " +
                "WHERE day_key = ? AND action = ? AND domain = ?",
        ).useStatement { statement ->
            statement.bindLong(1, dayEndEpochMillis)
            statement.bindString(2, dayKey)
            statement.bindString(3, action)
            statement.bindString(4, domain)
            statement.executeUpdateDelete()
        }
        if (updated == 0) {
            val partitionSize = rawQuery(
                "SELECT COUNT(*) FROM ${ActivityDatabase.DAILY_DOMAIN_TABLE} " +
                    "WHERE day_key = ? AND action = ?",
                arrayOf(dayKey, action),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            var initialCount = 1L
            var errorFloor = 0L
            if (partitionSize >= TOP_DOMAIN_CAPACITY) {
                val victim = rawQuery(
                    "SELECT domain, count FROM ${ActivityDatabase.DAILY_DOMAIN_TABLE} " +
                        "WHERE day_key = ? AND action = ? " +
                        "ORDER BY count ASC, domain COLLATE BINARY ASC LIMIT 1",
                    arrayOf(dayKey, action),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0) to cursor.getLong(1)
                }
                check(
                    delete(
                        ActivityDatabase.DAILY_DOMAIN_TABLE,
                        "day_key = ? AND action = ? AND domain = ?",
                        arrayOf(dayKey, action, victim.first),
                    ) == 1,
                ) { "Space-Saving victim replacement lost its transaction" }
                errorFloor = victim.second
                initialCount = victim.second + 1L
            }
            insertOrThrow(
                ActivityDatabase.DAILY_DOMAIN_TABLE,
                null,
                ContentValues(6).apply {
                    put("day_key", dayKey)
                    put("action", action)
                    put("domain", domain)
                    put("count", initialCount)
                    put("error_count", errorFloor)
                    put("retention_epoch_millis", dayEndEpochMillis)
                },
            )
        }
    }

    private fun SQLiteDatabase.accumulateProtectionInterval(startMillis: Long, endMillis: Long) {
        var cursorMillis = startMillis
        val safeEnd = maxOf(startMillis, endMillis)
        while (cursorMillis < safeEnd) {
            val date = Instant.ofEpochMilli(cursorMillis).atZone(zoneId).toLocalDate()
            val nextDay = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val segmentEnd = minOf(safeEnd, nextDay)
            val duration = segmentEnd - cursorMillis
            val updated = compileStatement(
                "UPDATE ${ActivityDatabase.PROTECTION_DAILY_TABLE} " +
                    "SET uptime_millis = uptime_millis + ?, " +
                    "retention_epoch_millis = MAX(retention_epoch_millis, ?) WHERE day_key = ?",
            ).useStatement { statement ->
                statement.bindLong(1, duration)
                statement.bindLong(2, nextDay)
                statement.bindString(3, date.toString())
                statement.executeUpdateDelete()
            }
            if (updated == 0) {
                insertOrThrow(
                    ActivityDatabase.PROTECTION_DAILY_TABLE,
                    null,
                    ContentValues(3).apply {
                        put("day_key", date.toString())
                        put("uptime_millis", duration)
                        put("retention_epoch_millis", nextDay)
                    },
                )
            }
            insertOrThrow(
                ActivityDatabase.PROTECTION_INTERVAL_TABLE,
                null,
                ContentValues(4).apply {
                    put("day_key", date.toString())
                    put("start_millis", cursorMillis)
                    put("end_millis", segmentEnd)
                    put("retention_epoch_millis", nextDay)
                },
            )
            cursorMillis = segmentEnd
        }
    }

    private fun localProtectionUptime(
        readable: SQLiteDatabase,
        range: ActivityDateRange,
        nowMillis: Long,
        tablePrefix: String = "",
    ): Long {
        val protectionDailyTable = "$tablePrefix${ActivityDatabase.PROTECTION_DAILY_TABLE}"
        val protectionIntervalTable = "$tablePrefix${ActivityDatabase.PROTECTION_INTERVAL_TABLE}"
        val protectionSessionTable = "$tablePrefix${ActivityDatabase.PROTECTION_SESSION_TABLE}"
        val epochRange = range.toEpochMillis(zoneId)
        val effectiveNow = minOf(nowMillis, epochRange.endExclusiveMillis)
        if (effectiveNow <= epochRange.startInclusiveMillis) return 0L
        val protectionRetentionCutoff = protectionDailyRetentionCutoffMillis(nowMillis)
        val maximumProtectionDayEnd = maximumCapturedDayEndMillis(nowMillis)
        val compacted = readable.rawQuery(
            "SELECT COALESCE(SUM(uptime_millis), 0) FROM $protectionDailyTable " +
                "AS daily WHERE day_key >= ? AND day_key <= ? " +
                "AND retention_epoch_millis > CAST(? AS INTEGER) " +
                "AND retention_epoch_millis <= CAST(? AS INTEGER) AND (" +
                "retention_epoch_millis <= CAST(? AS INTEGER) OR NOT EXISTS(" +
                "SELECT 1 FROM $protectionIntervalTable AS evidence " +
                "WHERE evidence.day_key = daily.day_key))",
            arrayOf(
                range.start.toString(),
                range.endInclusive.toString(),
                protectionRetentionCutoff.toString(),
                maximumProtectionDayEnd.toString(),
                effectiveNow.toString(),
            ),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        val exactClosed = readable.rawQuery(
            """
            SELECT COALESCE(SUM(
                MAX(0,
                    MIN(end_millis, CAST(? AS INTEGER), CAST(? AS INTEGER)) -
                    MAX(start_millis, CAST(? AS INTEGER))
                )
            ), 0)
            FROM $protectionIntervalTable
            WHERE day_key >= ? AND day_key <= ?
                AND retention_epoch_millis > CAST(? AS INTEGER)
                AND start_millis < CAST(? AS INTEGER)
                AND end_millis > CAST(? AS INTEGER)
            """.trimIndent(),
            arrayOf(
                effectiveNow.toString(),
                epochRange.endExclusiveMillis.toString(),
                epochRange.startInclusiveMillis.toString(),
                range.start.toString(),
                range.endInclusive.toString(),
                effectiveNow.toString(),
                epochRange.endExclusiveMillis.toString(),
                epochRange.startInclusiveMillis.toString(),
            ),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        val open = readable.rawQuery(
            """
            SELECT COALESCE(SUM(
                MAX(0,
                    MIN(COALESCE(end_millis, last_evidence_millis), CAST(? AS INTEGER), CAST(? AS INTEGER)) -
                    MAX(start_millis, CAST(? AS INTEGER))
                )
            ), 0)
            FROM $protectionSessionTable
            WHERE start_millis < CAST(? AS INTEGER)
                AND COALESCE(end_millis, last_evidence_millis) > CAST(? AS INTEGER)
            """.trimIndent(),
            arrayOf(
                effectiveNow.toString(),
                epochRange.endExclusiveMillis.toString(),
                epochRange.startInclusiveMillis.toString(),
                epochRange.endExclusiveMillis.toString(),
                epochRange.startInclusiveMillis.toString(),
            ),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        return compacted + exactClosed + open
    }

    private fun liveUptimeEvidenceThroughMillis(
        readable: SQLiteDatabase,
        range: ActivityDateRange,
        nowMillis: Long,
        tablePrefix: String = "",
    ): Long? {
        val epochRange = range.toEpochMillis(zoneId)
        val effectiveNow = minOf(nowMillis, epochRange.endExclusiveMillis)
        if (effectiveNow < epochRange.startInclusiveMillis) return null
        return readable.rawQuery(
            """
            SELECT MIN(last_evidence_millis, CAST(? AS INTEGER), CAST(? AS INTEGER))
            FROM $tablePrefix${ActivityDatabase.PROTECTION_SESSION_TABLE}
            WHERE end_millis IS NULL
                AND start_millis < CAST(? AS INTEGER)
                AND last_evidence_millis >= CAST(? AS INTEGER)
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                effectiveNow.toString(),
                epochRange.endExclusiveMillis.toString(),
                epochRange.endExclusiveMillis.toString(),
                epochRange.startInclusiveMillis.toString(),
            ),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
        }
    }

    private fun readEvents(cursor: Cursor): List<DnsDecisionEvent> = buildList {
        while (cursor.moveToNext()) {
            val action = enumValueOrNull<FilterAction>(cursor.getString(3)) ?: continue
            val reason = enumValueOrNull<FilterDecisionReason>(cursor.getString(4))
                ?: defaultReason(action)
            val resolver = cursor.stringOrNull(5)?.let { enumValueOrNull<ResolverKind>(it) }
            val transport = cursor.stringOrNull(6)?.let { enumValueOrNull<ResolverTransport>(it) }
            val latencyMillis = if (cursor.isNull(7)) null else cursor.getLong(7)
            val error = cursor.stringOrNull(8)?.let { enumValueOrNull<ResolverFailureReason>(it) }
            add(
                DnsDecisionEvent(
                    id = cursor.getLong(0),
                    timestampMillis = cursor.getLong(1),
                    normalizedDomain = cursor.getString(2),
                    decision = FilterDecision(action, reason),
                    resolver = resolver,
                    transport = transport,
                    latencyMillis = latencyMillis,
                    error = error,
                ),
            )
        }
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 100
        const val MAX_HISTORY_LIMIT = 1_000
        const val DOMAIN_HISTORY_PAGE_SIZE = 30
        const val TOP_DOMAIN_CAPACITY = ActivityDatabase.TOP_DOMAIN_CAPACITY
        const val DEFAULT_TOP_DOMAIN_LIMIT = 10
        const val MAX_TOP_DOMAIN_LIMIT = 100

        private fun likePattern(value: String): String = buildString(value.length + 2) {
            append('%')
            value.forEach { character ->
                if (character == '\\' || character == '%' || character == '_') append('\\')
                append(character)
            }
            append('%')
        }

        private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
            enumValues<T>().firstOrNull { it.name == value }

        private fun defaultReason(action: FilterAction): FilterDecisionReason = when (action) {
            FilterAction.ALLOW -> FilterDecisionReason.DEFAULT_ALLOW
            FilterAction.BLOCK -> FilterDecisionReason.BLOCKLIST
        }
    }
}

private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransactionNonExclusive()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}

private fun Boolean.asSqlBoolean(): Long = if (this) 1L else 0L

private fun Int.toStrictBoolean(column: String): Boolean = when (this) {
    0 -> false
    1 -> true
    else -> error("Invalid Activity policy boolean in $column")
}

private fun retentionCutoffMillis(nowMillis: Long, retentionMillis: Long): Long =
    if (nowMillis < Long.MIN_VALUE + retentionMillis) Long.MIN_VALUE else nowMillis - retentionMillis

private fun DnsDecisionEvent.immutableFingerprint(): ByteArray {
    val encoded = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeLong(id)
            output.writeLong(timestampMillis)
            output.writeUTF(normalizedDomain)
            output.writeUTF(decision.action.name)
            output.writeUTF(decision.reason.name)
            output.writeNullableEnum(resolver?.name)
            output.writeNullableEnum(transport?.name)
            output.writeBoolean(latencyMillis != null)
            latencyMillis?.let(output::writeLong)
            output.writeNullableEnum(error?.name)
        }
        bytes.toByteArray()
    }
    return MessageDigest.getInstance("SHA-256").digest(encoded)
}

private fun DataOutputStream.writeNullableEnum(value: String?) {
    writeBoolean(value != null)
    value?.let(::writeUTF)
}

private inline fun <T> android.database.sqlite.SQLiteStatement.useStatement(
    block: (android.database.sqlite.SQLiteStatement) -> T,
): T = try {
    block(this)
} finally {
    close()
}

private fun Cursor.stringOrNull(columnIndex: Int): String? =
    if (isNull(columnIndex)) null else getString(columnIndex)
