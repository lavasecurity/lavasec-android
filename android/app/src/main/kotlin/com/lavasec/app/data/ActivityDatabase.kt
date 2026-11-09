package com.lavasec.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lavasec.app.foundation.activity.ActivityHistoryPolicy
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Credential-protected local Activity storage.
 *
 * The event and per-domain tables contain browsing detail and are subject to the rolling
 * seven-day retention boundary. The daily count table is deliberately separate so pruning
 * detail never erases the long-lived digest totals.
 */
@SuppressLint("UseKtx") // Explicit transaction boundaries preserve WAL and rollback semantics.
class ActivityDatabase(
    context: Context,
    databaseName: String? = DATABASE_NAME,
    private val pruneNowMillis: () -> Long = System::currentTimeMillis,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION), java.io.Closeable {
    // SQLiteOpenHelper gained AutoCloseable after API 28. Declare our own Closeable contract
    // so scoped use/reopen paths also dispatch to the inherited close() on the minimum API.
    private val applicationContext = context.applicationContext

    init {
        require(!context.isDeviceProtectedStorage) {
            "Activity data must use credential-protected storage"
        }
        deleteOrphanedExportSnapshots(applicationContext.cacheDir)
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(database: SQLiteDatabase) {
        super.onConfigure(database)
        database.setForeignKeyConstraintsEnabled(true)
        database.rawQuery("PRAGMA busy_timeout = 2000", null).use { it.moveToFirst() }
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $EVENT_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_millis INTEGER NOT NULL,
                day_key TEXT NOT NULL,
                retention_epoch_millis INTEGER NOT NULL,
                domain TEXT NOT NULL,
                action TEXT NOT NULL CHECK(action IN ('ALLOW', 'BLOCK')),
                reason TEXT NOT NULL,
                resolver TEXT,
                transport TEXT,
                latency_millis INTEGER,
                error TEXT
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX idx_activity_event_time " +
                "ON $EVENT_TABLE(timestamp_millis DESC, id DESC)",
        )
        database.execSQL(
            "CREATE INDEX idx_activity_event_action_time " +
                "ON $EVENT_TABLE(action, timestamp_millis DESC, id DESC)",
        )

        database.execSQL(
            """
            CREATE TABLE $DAILY_COUNT_TABLE (
                day_key TEXT NOT NULL,
                action TEXT NOT NULL CHECK(action IN ('ALLOW', 'BLOCK')),
                count INTEGER NOT NULL CHECK(count >= 0),
                retention_epoch_millis INTEGER NOT NULL,
                PRIMARY KEY(day_key, action)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        createCountEventTable(database)

        database.execSQL(
            """
            CREATE TABLE $DAILY_DOMAIN_TABLE (
                day_key TEXT NOT NULL,
                action TEXT NOT NULL CHECK(action IN ('ALLOW', 'BLOCK')),
                domain TEXT NOT NULL,
                count INTEGER NOT NULL CHECK(count >= 0),
                error_count INTEGER NOT NULL DEFAULT 0 CHECK(error_count >= 0 AND error_count <= count),
                retention_epoch_millis INTEGER NOT NULL,
                PRIMARY KEY(day_key, action, domain)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX idx_activity_daily_domain_action_day " +
                "ON $DAILY_DOMAIN_TABLE(action, day_key, domain)",
        )
        database.execSQL(
            "CREATE INDEX idx_activity_daily_domain_retention " +
                "ON $DAILY_DOMAIN_TABLE(retention_epoch_millis)",
        )
        createProtectionSessionTable(database)
        createProtectionDailyTable(database)
        createProtectionIntervalTable(database)
        createRevisionTable(database)
        createDnsReceiptTable(database)
        createDnsProducerNamespaceTable(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var upgradedVersion = oldVersion
        if (upgradedVersion == 1 && newVersion >= 2) {
            database.execSQL("ALTER TABLE $EVENT_TABLE ADD COLUMN resolver TEXT")
            database.execSQL("ALTER TABLE $EVENT_TABLE ADD COLUMN transport TEXT")
            database.execSQL("ALTER TABLE $EVENT_TABLE ADD COLUMN latency_millis INTEGER")
            database.execSQL("ALTER TABLE $EVENT_TABLE ADD COLUMN error TEXT")
            database.execSQL("ALTER TABLE $DAILY_DOMAIN_TABLE ADD COLUMN retention_epoch_millis INTEGER")
            // Version 1 stored only a local day key. Its deterministic migration boundary is
            // the end of that ISO day interpreted as UTC; new writes capture their local day end.
            database.execSQL(
                """
                UPDATE $DAILY_DOMAIN_TABLE
                SET retention_epoch_millis = COALESCE(
                    CAST(strftime('%s', day_key || ' 00:00:00', '+1 day') AS INTEGER) * 1000,
                    0
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_activity_event_time " +
                    "ON $EVENT_TABLE(timestamp_millis DESC, id DESC)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_activity_event_action_time " +
                    "ON $EVENT_TABLE(action, timestamp_millis DESC, id DESC)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_activity_daily_domain_action_day " +
                    "ON $DAILY_DOMAIN_TABLE(action, day_key, domain)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_activity_daily_domain_retention " +
                    "ON $DAILY_DOMAIN_TABLE(retention_epoch_millis)",
            )
            upgradedVersion = 2
        }
        if (upgradedVersion == 2 && newVersion >= 3) {
            createProtectionSessionTable(database)
            upgradedVersion = 3
        }
        if (upgradedVersion == 3 && newVersion >= 4) {
            if (!database.hasColumn(PROTECTION_SESSION_TABLE, "last_evidence_millis")) {
                database.execSQL(
                    "ALTER TABLE $PROTECTION_SESSION_TABLE " +
                        "ADD COLUMN last_evidence_millis INTEGER NOT NULL DEFAULT 0",
                )
            }
            database.execSQL(
                "UPDATE $PROTECTION_SESSION_TABLE SET last_evidence_millis = " +
                    "MAX(start_millis, COALESCE(end_millis, start_millis))",
            )
            upgradedVersion = 4
        }
        if (upgradedVersion == 4 && newVersion >= 5) {
            if (!database.hasColumn(DAILY_DOMAIN_TABLE, "error_count")) {
                database.execSQL(
                    "ALTER TABLE $DAILY_DOMAIN_TABLE " +
                        "ADD COLUMN error_count INTEGER NOT NULL DEFAULT 0",
                )
            }
            createProtectionDailyTable(database)
            createProtectionIntervalTable(database)
            trimLegacyDomainPartitions(database)
            compactClosedProtectionSessions(database, ZoneId.systemDefault())
            upgradedVersion = 5
        }
        if (upgradedVersion == 5 && newVersion >= 6) {
            if (!database.hasColumn(EVENT_TABLE, "day_key")) {
                database.execSQL("ALTER TABLE $EVENT_TABLE ADD COLUMN day_key TEXT NOT NULL DEFAULT ''")
                database.execSQL(
                    "UPDATE $EVENT_TABLE SET day_key = " +
                        "COALESCE(strftime('%Y-%m-%d', timestamp_millis / 1000, 'unixepoch'), '')",
                )
            }
            if (!database.hasColumn(EVENT_TABLE, "retention_epoch_millis")) {
                database.execSQL(
                    "ALTER TABLE $EVENT_TABLE ADD COLUMN retention_epoch_millis INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "UPDATE $EVENT_TABLE SET retention_epoch_millis = COALESCE(" +
                        "CAST(strftime('%s', day_key || ' 00:00:00', '+1 day') AS INTEGER) * 1000, 0)",
                )
            }
            if (!database.hasColumn(DAILY_COUNT_TABLE, "retention_epoch_millis")) {
                database.execSQL(
                    "ALTER TABLE $DAILY_COUNT_TABLE " +
                        "ADD COLUMN retention_epoch_millis INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "UPDATE $DAILY_COUNT_TABLE SET retention_epoch_millis = COALESCE(" +
                        "CAST(strftime('%s', day_key || ' 00:00:00', '+1 day') AS INTEGER) * 1000, 0)",
                )
            }
            if (!database.hasColumn(PROTECTION_DAILY_TABLE, "retention_epoch_millis")) {
                database.execSQL(
                    "ALTER TABLE $PROTECTION_DAILY_TABLE " +
                        "ADD COLUMN retention_epoch_millis INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "UPDATE $PROTECTION_DAILY_TABLE SET retention_epoch_millis = COALESCE(" +
                        "CAST(strftime('%s', day_key || ' 00:00:00', '+1 day') AS INTEGER) * 1000, 0)",
                )
            }
            createCountEventTable(database)
            createProtectionIntervalTable(database)
            upgradedVersion = 6
        }
        if (upgradedVersion == 6 && newVersion >= 7) {
            createRevisionTable(database)
            // The current creator emits the complete v8 singleton. A database that reaches this
            // branch had no prior revision table, so no follow-up ALTER is required.
            upgradedVersion = if (newVersion >= 8) 8 else 7
        }
        if (upgradedVersion == 7 && newVersion >= 8) {
            database.execSQL(
                "ALTER TABLE $REVISION_TABLE ADD COLUMN " +
                    "policy_revision INTEGER NOT NULL DEFAULT 0 CHECK(policy_revision >= 0)",
            )
            database.execSQL(
                "ALTER TABLE $REVISION_TABLE ADD COLUMN " +
                    "keep_counts INTEGER NOT NULL DEFAULT 1 CHECK(keep_counts IN (0, 1))",
            )
            database.execSQL(
                "ALTER TABLE $REVISION_TABLE ADD COLUMN " +
                    "keep_history INTEGER NOT NULL DEFAULT 1 CHECK(keep_history IN (0, 1))",
            )
            database.execSQL(
                "ALTER TABLE $REVISION_TABLE ADD COLUMN " +
                    "keep_network_activity INTEGER NOT NULL DEFAULT 1 " +
                    "CHECK(keep_network_activity IN (0, 1))",
            )
            database.execSQL(
                "ALTER TABLE $REVISION_TABLE ADD COLUMN " +
                    "keep_lava_guard_progress INTEGER NOT NULL DEFAULT 1 " +
                    "CHECK(keep_lava_guard_progress IN (0, 1))",
            )
            upgradedVersion = 8
        }
        if (upgradedVersion == 8 && newVersion >= 9) {
            createDnsReceiptTable(database)
            upgradedVersion = 9
        }
        if (upgradedVersion == 9 && newVersion >= 10) {
            // Keep every legacy producer ID/fingerprint in its original namespace.
            database.execSQL("ALTER TABLE $DNS_RECEIPT_TABLE RENAME TO activity_dns_receipt_v9")
            database.execSQL("DROP INDEX IF EXISTS idx_activity_dns_receipt_time")
            createDnsReceiptTable(database)
            database.execSQL(
                "INSERT INTO $DNS_RECEIPT_TABLE(producer_namespace, event_id, timestamp_millis, fingerprint) " +
                    "SELECT 0, event_id, timestamp_millis, fingerprint FROM activity_dns_receipt_v9",
            )
            database.execSQL("DROP TABLE activity_dns_receipt_v9")
            createDnsProducerNamespaceTable(database)
            upgradedVersion = 10
        }
        if (upgradedVersion != newVersion) {
            error("Unsupported Activity database upgrade $oldVersion -> $newVersion")
        }
    }

    override fun onOpen(database: SQLiteDatabase) {
        super.onOpen(database)
        val nowMillis = pruneNowMillis()
        val upperBoundMillis = ActivityHistoryPolicy.upperBoundMillis(nowMillis)
        val exactCutoffMillis = rollbackSafeExactEvidenceCutoffMillis(nowMillis)
        pruneExpiredRowsOnOpen(
            database = database,
            cutoffMillis = ActivityHistoryPolicy.cutoffMillis(nowMillis),
            upperBoundMillis = upperBoundMillis,
            exactCutoffMillis = exactCutoffMillis,
            nowMillis = nowMillis,
        )
    }

    /** A private, credential-protected cache file used only for one explicit local export. */
    internal fun createExportSnapshotFile(): File =
        File.createTempFile(EXPORT_SNAPSHOT_PREFIX, ".sqlite", applicationContext.cacheDir).also {
            activeExportSnapshots += it.absolutePath
        }

    internal fun deleteExportSnapshotFile(snapshotFile: File) {
        activeExportSnapshots -= snapshotFile.absolutePath
        exportSnapshotArtifacts(snapshotFile).forEach(File::delete)
    }

    /**
     * Explicit app-composition crash recovery. Generic helpers (including the periodic pruning
     * worker) must not call this because another helper may own the currently-live session.
     */
    fun recoverAbandonedProtectionSessions(zoneId: ZoneId = ZoneId.systemDefault()): Int {
        val writable = writableDatabase
        var recovered = 0
        writable.beginTransactionNonExclusive()
        try {
            val abandoned = writable.rawQuery(
                "SELECT id, start_millis, last_evidence_millis FROM $PROTECTION_SESSION_TABLE " +
                    "WHERE end_millis IS NULL",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2)))
                    }
                }
            }
            abandoned.forEach { (id, start, lastEvidence) ->
                accumulateProtectionInterval(writable, start, maxOf(start, lastEvidence), zoneId)
                recovered += writable.delete(PROTECTION_SESSION_TABLE, "id = ?", arrayOf(id.toString()))
            }
            if (recovered > 0) {
                writable.advanceActivityRevisions(summaryChanged = true, domainChanged = false)
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        return recovered
    }

    internal fun pruneDetailedHistory(
        cutoffMillis: Long,
        upperBoundMillis: Long,
        exactCutoffMillis: Long,
    ): ActivityPruneResult = pruneDetailedHistory(
        cutoffMillis = cutoffMillis,
        upperBoundMillis = upperBoundMillis,
        exactCutoffMillis = exactCutoffMillis,
        database = writableDatabase,
    )

    private fun pruneExpiredRowsOnOpen(
        database: SQLiteDatabase,
        cutoffMillis: Long,
        upperBoundMillis: Long,
        exactCutoffMillis: Long,
        nowMillis: Long,
    ) {
        // Open-time maintenance is opportunistic: every surface and its revision changes commit
        // together, or all deletions roll back when either required revision is saturated.
        database.beginTransactionNonExclusive()
        try {
            val detailed = database.pruneDetailedHistoryRows(
                cutoffMillis = cutoffMillis,
                upperBoundMillis = upperBoundMillis,
                exactCutoffMillis = exactCutoffMillis,
            )
            val protectionDailyRowsDeleted = database.pruneProtectionDailyRows(nowMillis)
            val domainChanged = detailed.eventRowsDeleted > 0 || detailed.domainRowsDeleted > 0
            val summaryChanged =
                domainChanged || detailed.exactEvidenceRowsDeleted > 0 || protectionDailyRowsDeleted > 0
            if (
                !summaryChanged ||
                database.hasActivityRevisionCapacity(
                    summaryChanged = summaryChanged,
                    domainChanged = domainChanged,
                )
            ) {
                database.advanceActivityRevisions(
                    summaryChanged = summaryChanged,
                    domainChanged = domainChanged,
                )
                database.setTransactionSuccessful()
            }
        } finally {
            database.endTransaction()
        }
    }

    private fun pruneDetailedHistory(
        cutoffMillis: Long,
        upperBoundMillis: Long,
        exactCutoffMillis: Long,
        database: SQLiteDatabase,
    ): ActivityPruneResult {
        var result: ActivityDetailedPruneResult? = null
        database.beginTransactionNonExclusive()
        try {
            val pruned = database.pruneDetailedHistoryRows(
                cutoffMillis = cutoffMillis,
                upperBoundMillis = upperBoundMillis,
                exactCutoffMillis = exactCutoffMillis,
            )
            result = pruned
            if (pruned.rowsDeleted > 0) {
                database.advanceActivityRevisions(
                    summaryChanged = true,
                    domainChanged = pruned.eventRowsDeleted > 0 || pruned.domainRowsDeleted > 0,
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return checkNotNull(result).let {
            ActivityPruneResult(it.eventRowsDeleted, it.domainRowsDeleted)
        }
    }

    companion object {
        const val DATABASE_NAME = "activity.sqlite"
        private const val DATABASE_VERSION = 10

        internal const val EVENT_TABLE = "activity_event"
        internal const val DAILY_COUNT_TABLE = "activity_daily_count"
        internal const val COUNT_EVENT_TABLE = "activity_count_event"
        internal const val DAILY_DOMAIN_TABLE = "activity_daily_domain"
        internal const val PROTECTION_SESSION_TABLE = "activity_protection_session"
        internal const val PROTECTION_DAILY_TABLE = "activity_protection_daily"
        internal const val PROTECTION_INTERVAL_TABLE = "activity_protection_interval"
        internal const val REVISION_TABLE = "activity_revision"
        internal const val DNS_RECEIPT_TABLE = "activity_dns_receipt"
        internal const val DNS_PRODUCER_NAMESPACE_TABLE = "activity_dns_producer_namespace"
        internal const val TOP_DOMAIN_CAPACITY = 256
        internal const val UPTIME_RETENTION_DAYS = 731L
        private const val EXPORT_SNAPSHOT_PREFIX = "activity-export-"
        private val activeExportSnapshots = ConcurrentHashMap.newKeySet<String>()

        private fun createProtectionSessionTable(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $PROTECTION_SESSION_TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    start_millis INTEGER NOT NULL,
                    last_evidence_millis INTEGER NOT NULL,
                    end_millis INTEGER,
                    CHECK(last_evidence_millis >= start_millis),
                    CHECK(end_millis IS NULL OR end_millis >= start_millis)
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_activity_protection_session_range " +
                    "ON $PROTECTION_SESSION_TABLE(start_millis, end_millis)",
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_activity_protection_session_open " +
                "ON $PROTECTION_SESSION_TABLE((end_millis IS NULL)) WHERE end_millis IS NULL",
            )
        }

        private fun createCountEventTable(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $COUNT_EVENT_TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp_millis INTEGER NOT NULL,
                    day_key TEXT NOT NULL,
                    action TEXT NOT NULL CHECK(action IN ('ALLOW', 'BLOCK')),
                    retention_epoch_millis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_activity_count_event_range " +
                    "ON $COUNT_EVENT_TABLE(day_key, timestamp_millis, action)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_activity_count_event_retention " +
                    "ON $COUNT_EVENT_TABLE(retention_epoch_millis)",
            )
        }

        private fun createProtectionDailyTable(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $PROTECTION_DAILY_TABLE (
                    day_key TEXT PRIMARY KEY,
                    uptime_millis INTEGER NOT NULL CHECK(uptime_millis >= 0),
                    retention_epoch_millis INTEGER NOT NULL
                ) WITHOUT ROWID
                """.trimIndent(),
            )
        }

        private fun createProtectionIntervalTable(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $PROTECTION_INTERVAL_TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    day_key TEXT NOT NULL,
                    start_millis INTEGER NOT NULL,
                    end_millis INTEGER NOT NULL CHECK(end_millis >= start_millis),
                    retention_epoch_millis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_activity_protection_interval_range " +
                    "ON $PROTECTION_INTERVAL_TABLE(day_key, start_millis, end_millis)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_activity_protection_interval_retention " +
                    "ON $PROTECTION_INTERVAL_TABLE(retention_epoch_millis)",
            )
        }

        private fun createRevisionTable(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $REVISION_TABLE (
                    singleton INTEGER NOT NULL PRIMARY KEY CHECK(singleton = 1),
                    summary_count_revision INTEGER NOT NULL CHECK(summary_count_revision >= 0),
                    domain_log_revision INTEGER NOT NULL CHECK(domain_log_revision >= 0),
                    policy_revision INTEGER NOT NULL CHECK(policy_revision >= 0),
                    keep_counts INTEGER NOT NULL CHECK(keep_counts IN (0, 1)),
                    keep_history INTEGER NOT NULL CHECK(keep_history IN (0, 1)),
                    keep_network_activity INTEGER NOT NULL CHECK(keep_network_activity IN (0, 1)),
                    keep_lava_guard_progress INTEGER NOT NULL CHECK(keep_lava_guard_progress IN (0, 1))
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            database.execSQL(
                "INSERT OR IGNORE INTO $REVISION_TABLE(" +
                    "singleton, summary_count_revision, domain_log_revision, policy_revision, " +
                    "keep_counts, keep_history, keep_network_activity, keep_lava_guard_progress" +
                    ") VALUES(1, 0, 0, 0, 1, 1, 1, 1)",
            )
        }

        private fun createDnsProducerNamespaceTable(database: SQLiteDatabase) {
            // This is writer-incarnation custody, not a per-query counter. Reserve only inside
            // an admitted terminal logging transaction. Clear/prune keeps the high-water so an
            // in-flight old writer cannot borrow a newly issued namespace.
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $DNS_PRODUCER_NAMESPACE_TABLE (
                    singleton INTEGER NOT NULL PRIMARY KEY CHECK(singleton = 1),
                    last_namespace INTEGER NOT NULL CHECK(last_namespace >= 0)
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            database.execSQL(
                "INSERT OR IGNORE INTO $DNS_PRODUCER_NAMESPACE_TABLE(singleton, last_namespace) " +
                    "SELECT 1, COALESCE(MAX(producer_namespace), 0) FROM $DNS_RECEIPT_TABLE",
            )
        }

        private fun createDnsReceiptTable(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $DNS_RECEIPT_TABLE (
                    producer_namespace INTEGER NOT NULL DEFAULT 0 CHECK(producer_namespace >= 0),
                    event_id INTEGER NOT NULL CHECK(event_id > 0),
                    timestamp_millis INTEGER NOT NULL CHECK(timestamp_millis >= 0),
                    fingerprint BLOB NOT NULL,
                    PRIMARY KEY(producer_namespace, event_id)
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_activity_dns_receipt_time " +
                    "ON $DNS_RECEIPT_TABLE(timestamp_millis)",
            )
        }

        private fun compactClosedProtectionSessions(database: SQLiteDatabase, zoneId: ZoneId) {
            val closed = database.rawQuery(
                "SELECT id, start_millis, end_millis FROM $PROTECTION_SESSION_TABLE " +
                    "WHERE end_millis IS NOT NULL ORDER BY id",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2)))
                    }
                }
            }
            closed.forEach { (id, start, end) ->
                accumulateProtectionInterval(database, start, end, zoneId)
                database.delete(PROTECTION_SESSION_TABLE, "id = ?", arrayOf(id.toString()))
            }
        }

        private fun accumulateProtectionInterval(
            database: SQLiteDatabase,
            startMillis: Long,
            endMillis: Long,
            zoneId: ZoneId,
        ) {
            var cursorMillis = startMillis
            val safeEnd = maxOf(startMillis, endMillis)
            while (cursorMillis < safeEnd) {
                val date = Instant.ofEpochMilli(cursorMillis).atZone(zoneId).toLocalDate()
                val nextDay = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val segmentEnd = minOf(safeEnd, nextDay)
                val duration = segmentEnd - cursorMillis
                val updated = database.compileStatement(
                    "UPDATE $PROTECTION_DAILY_TABLE " +
                        "SET uptime_millis = uptime_millis + ?, " +
                        "retention_epoch_millis = MAX(retention_epoch_millis, ?) WHERE day_key = ?",
                ).use { statement ->
                    statement.bindLong(1, duration)
                    statement.bindLong(2, nextDay)
                    statement.bindString(3, date.toString())
                    statement.executeUpdateDelete()
                }
                if (updated == 0) {
                    database.execSQL(
                        "INSERT INTO $PROTECTION_DAILY_TABLE(" +
                            "day_key, uptime_millis, retention_epoch_millis) VALUES(?, ?, ?)",
                        arrayOf<Any>(date.toString(), duration, nextDay),
                    )
                }
                database.execSQL(
                    "INSERT INTO $PROTECTION_INTERVAL_TABLE(" +
                        "day_key, start_millis, end_millis, retention_epoch_millis) VALUES(?, ?, ?, ?)",
                    arrayOf<Any>(date.toString(), cursorMillis, segmentEnd, nextDay),
                )
                cursorMillis = segmentEnd
            }
        }

        private fun trimLegacyDomainPartitions(database: SQLiteDatabase) {
            val partitions = database.rawQuery(
                "SELECT day_key, action, COUNT(*) FROM $DAILY_DOMAIN_TABLE " +
                    "GROUP BY day_key, action HAVING COUNT(*) > ?",
                arrayOf(TOP_DOMAIN_CAPACITY.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                }
            }
            partitions.forEach { (dayKey, action) ->
                val victims = database.rawQuery(
                    "SELECT domain FROM $DAILY_DOMAIN_TABLE WHERE day_key = ? AND action = ? " +
                        "ORDER BY count ASC, domain COLLATE BINARY ASC " +
                        "LIMIT (SELECT COUNT(*) - ? FROM $DAILY_DOMAIN_TABLE WHERE day_key = ? AND action = ?)",
                    arrayOf(dayKey, action, TOP_DOMAIN_CAPACITY.toString(), dayKey, action),
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                victims.forEach { domain ->
                    database.delete(
                        DAILY_DOMAIN_TABLE,
                        "day_key = ? AND action = ? AND domain = ?",
                        arrayOf(dayKey, action, domain),
                    )
                }
            }
        }

        private fun deleteOrphanedExportSnapshots(cacheDir: File) {
            cacheDir.listFiles()
                ?.filter { it.name.startsWith(EXPORT_SNAPSHOT_PREFIX) }
                ?.filterNot { file ->
                    val basePath = file.absolutePath
                        .removeSuffix("-journal")
                        .removeSuffix("-wal")
                        .removeSuffix("-shm")
                    basePath in activeExportSnapshots
                }
                ?.forEach(File::delete)
        }

        private fun exportSnapshotArtifacts(snapshotFile: File): List<File> = listOf(
            snapshotFile,
            File(snapshotFile.path + "-journal"),
            File(snapshotFile.path + "-wal"),
            File(snapshotFile.path + "-shm"),
        )

        private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
            rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == column) return@use true
                }
                false
            }
    }
}

private data class ActivityDetailedPruneResult(
    val eventRowsDeleted: Int,
    val domainRowsDeleted: Int,
    val exactEvidenceRowsDeleted: Long,
) {
    val rowsDeleted: Long =
        eventRowsDeleted.toLong() + domainRowsDeleted.toLong() + exactEvidenceRowsDeleted
}

private fun SQLiteDatabase.pruneDetailedHistoryRows(
    cutoffMillis: Long,
    upperBoundMillis: Long,
    exactCutoffMillis: Long,
): ActivityDetailedPruneResult {
    val eventRowsDeleted = delete(
        ActivityDatabase.EVENT_TABLE,
        "timestamp_millis < ? OR timestamp_millis > ?",
        arrayOf(cutoffMillis.toString(), upperBoundMillis.toString()),
    )
    val domainRowsDeleted = delete(
        ActivityDatabase.DAILY_DOMAIN_TABLE,
        "retention_epoch_millis <= ? OR retention_epoch_millis > ?",
        arrayOf(
            cutoffMillis.toString(),
            maximumCapturedDayEndMillis(upperBoundMillis).toString(),
        ),
    )
    val exactEvidenceRowsDeleted = pruneExactCutoffEvidence(
        cutoffMillis = exactCutoffMillis,
        upperBoundMillis = upperBoundMillis,
    ).rowsDeleted
    return ActivityDetailedPruneResult(
        eventRowsDeleted = eventRowsDeleted,
        domainRowsDeleted = domainRowsDeleted,
        exactEvidenceRowsDeleted = exactEvidenceRowsDeleted,
    )
}

private fun SQLiteDatabase.pruneProtectionDailyRows(nowMillis: Long): Int = delete(
    ActivityDatabase.PROTECTION_DAILY_TABLE,
    "retention_epoch_millis <= ? OR retention_epoch_millis > ?",
    arrayOf(
        protectionDailyRetentionCutoffMillis(nowMillis).toString(),
        maximumCapturedDayEndMillis(nowMillis).toString(),
    ),
)

internal fun protectionDailyRetentionCutoffMillis(nowMillis: Long): Long {
    val retentionMillis = ActivityDatabase.UPTIME_RETENTION_DAYS * 24L * 60L * 60L * 1_000L
    return if (nowMillis < Long.MIN_VALUE + retentionMillis) {
        Long.MIN_VALUE
    } else {
        nowMillis - retentionMillis
    }
}

internal fun SQLiteDatabase.advanceActivityRevisions(
    summaryChanged: Boolean,
    domainChanged: Boolean,
) {
    if (!summaryChanged && !domainChanged) return
    val advanceSummary = summaryChanged || domainChanged
    val current = activityRevisions()
    check(!advanceSummary || current.first < Long.MAX_VALUE) {
        "Activity summary/count revision is saturated"
    }
    check(!domainChanged || current.second < Long.MAX_VALUE) {
        "Activity domain-log revision is saturated"
    }
    val updated = compileStatement(
        "UPDATE ${ActivityDatabase.REVISION_TABLE} SET " +
            "summary_count_revision = summary_count_revision + ?, " +
            "domain_log_revision = domain_log_revision + ? WHERE singleton = 1",
    ).use { statement ->
        statement.bindLong(1, if (advanceSummary) 1L else 0L)
        statement.bindLong(2, if (domainChanged) 1L else 0L)
        statement.executeUpdateDelete()
    }
    check(updated == 1) { "Activity revision state is missing" }
}

private fun SQLiteDatabase.hasActivityRevisionCapacity(
    summaryChanged: Boolean,
    domainChanged: Boolean,
): Boolean {
    val current = activityRevisions()
    return (!summaryChanged || current.first < Long.MAX_VALUE) &&
        (!domainChanged || current.second < Long.MAX_VALUE)
}

private fun SQLiteDatabase.activityRevisions(): Pair<Long, Long> = rawQuery(
    "SELECT summary_count_revision, domain_log_revision " +
        "FROM ${ActivityDatabase.REVISION_TABLE} WHERE singleton = 1",
    null,
).use { cursor ->
    check(cursor.moveToFirst()) { "Activity revision state is missing" }
    cursor.getLong(0) to cursor.getLong(1)
}

internal data class ActivityExactEvidencePruneResult(
    val countEventRowsDeleted: Int,
    val protectionIntervalRowsDeleted: Int,
) {
    val rowsDeleted: Long = countEventRowsDeleted.toLong() + protectionIntervalRowsDeleted
}

internal fun SQLiteDatabase.pruneExactCutoffEvidence(
    cutoffMillis: Long,
    upperBoundMillis: Long,
): ActivityExactEvidencePruneResult {
    val maximumDayEnd = maximumCapturedDayEndMillis(upperBoundMillis)
    val args = arrayOf(cutoffMillis.toString(), maximumDayEnd.toString())
    return ActivityExactEvidencePruneResult(
        countEventRowsDeleted = delete(
            ActivityDatabase.COUNT_EVENT_TABLE,
            "retention_epoch_millis <= ? OR retention_epoch_millis > ?",
            args,
        ),
        protectionIntervalRowsDeleted = delete(
            ActivityDatabase.PROTECTION_INTERVAL_TABLE,
            "retention_epoch_millis <= ? OR retention_epoch_millis > ?",
            args,
        ),
    )
}

/**
 * A retained domain row stores the absolute end of the local day captured when it was written.
 * Civil days around offset transitions can exceed 24 hours, so allow a conservative 26-hour
 * envelope beyond the event clock tolerance. This rejects multi-day wall-clock jumps without
 * reinterpreting the captured day in the device's current timezone.
 */
internal fun maximumCapturedDayEndMillis(upperBoundMillis: Long): Long {
    val allowance = 26L * 60L * 60L * 1_000L
    return if (upperBoundMillis > Long.MAX_VALUE - allowance) Long.MAX_VALUE else upperBoundMillis + allowance
}

/** Exact evidence remains queryable across the accepted backward wall-clock correction window. */
internal fun rollbackSafeExactEvidenceCutoffMillis(nowMillis: Long): Long =
    if (nowMillis < Long.MIN_VALUE + ActivityHistoryPolicy.FUTURE_TIMESTAMP_TOLERANCE_MILLIS) {
        Long.MIN_VALUE
    } else {
        nowMillis - ActivityHistoryPolicy.FUTURE_TIMESTAMP_TOLERANCE_MILLIS
    }
