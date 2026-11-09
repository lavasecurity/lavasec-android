package com.lavasec.app.product.privacy

import com.lavasec.app.data.ActivityHistoryCursor
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.data.CredentialActivityWriter
import com.lavasec.app.foundation.activity.ActivityDateRange
import com.lavasec.app.foundation.activity.ActivityDateRangePolicy
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.product.EXPORT_PAGE_SIZE
import com.lavasec.app.platform.LoggingSettings
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface PrivacyAuthorization {
    data object Authorized : PrivacyAuthorization
    data object Cancelled : PrivacyAuthorization
    data class Failed(val message: String) : PrivacyAuthorization {
        init {
            require(message.isNotBlank())
        }
    }
}

fun interface PrivacyAuthorizer {
    suspend fun authorize(): PrivacyAuthorization
}

data class LocalActivityExportOptions(
    val countsAndUptime: Boolean = true,
    val domainHistory: Boolean = true,
    val networkActivity: Boolean = false,
) {
    init {
        require(countsAndUptime || domainHistory || networkActivity)
    }
}

sealed interface LocalExportResult {
    data class Completed(val handle: LocalExportHandle) : LocalExportResult
    data object Cancelled : LocalExportResult
    data class Failed(val message: String) : LocalExportResult {
        init {
            require(message.isNotBlank())
        }
    }
}

/** Produces an explicitly selected local archive under the shared privacy/export boundary. */
class LocalActivityExportService(
    private val repository: ActivityRepository,
    private val fence: CredentialPrivacyFence,
    private val artifactStore: AndroidLocalExportArtifactStore,
    private val authorizer: PrivacyAuthorizer,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val networkActivityExport: (() -> ByteArray)? = null,
    private val dnsFallbackHistoryExport: (() -> ByteArray)? = null,
) {
    suspend fun export(options: LocalActivityExportOptions): LocalExportResult {
        when (val authorization = authorizer.authorize()) {
            PrivacyAuthorization.Authorized -> Unit
            PrivacyAuthorization.Cancelled -> return LocalExportResult.Cancelled
            is PrivacyAuthorization.Failed -> return LocalExportResult.Failed(authorization.message)
        }
        return try {
            fence.withExport {
                withContext(ioDispatcher) { buildArchive(options) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            LocalExportResult.Failed(failure.message?.takeIf(String::isNotBlank) ?: "Export failed")
        }
    }

    private suspend fun buildArchive(options: LocalActivityExportOptions): LocalExportResult {
        val generatedAt = nowMillis().also { require(it >= 0L) }
        val today = Instant.ofEpochMilli(generatedAt).atZone(zoneId).toLocalDate()
        val range = ActivityDateRange(
            start = ActivityDateRangePolicy.earliestSelectableDate(today),
            endInclusive = today,
        )
        val networkSnapshot = if (options.networkActivity) {
            checkNotNull(networkActivityExport) { "Network Activity export is unavailable" }.invoke()
                .also { require(it.size <= 512 * 1024) }
        } else null
        val fallbackSnapshot = dnsFallbackHistoryExport?.invoke()?.also { require(it.size <= 128 * 1024) }
        var pending: PendingLocalExportArtifact? = null
        var published = false
        val pinned = repository.pinExportSnapshot(
            range = range,
            generatedAtMillis = generatedAt,
            includeCounts = options.countsAndUptime,
            includeHistory = options.domainHistory,
        )
        try {
            pending = artifactStore.create(generatedAt)
            val zip = ZipOutputStream(pending.output)
            zip.writeTextEntry(
                "manifest.txt",
                buildString {
                    appendLine("format=lavasec-local-log-v1")
                    appendLine("generated_at_millis=$generatedAt")
                    appendLine("counts_and_uptime_included=${options.countsAndUptime}")
                    appendLine("domain_history_included=${options.domainHistory}")
                    appendLine("network_activity_included=${options.networkActivity}")
                },
            )
            if (options.countsAndUptime) {
                zip.writeTextEntry(
                    "filtering-counts.csv",
                    buildString {
                        appendLine("allowed,blocked,uptime_millis")
                        append(pinned.header.summary.allowedCount)
                        append(',')
                        append(pinned.header.summary.blockedCount)
                        append(',')
                        appendLine(pinned.header.summary.localProtectionUptimeMillis)
                    },
                )
            }
            if (options.domainHistory) zip.writeDomainHistory(pinned)
            if (networkSnapshot != null) {
                zip.putNextEntry(ZipEntry("network-activity.json"))
                zip.write(networkSnapshot)
                zip.closeEntry()
            }
            if (fallbackSnapshot != null) {
                zip.putNextEntry(ZipEntry("dns-fallback-history.json"))
                zip.write(fallbackSnapshot)
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
            pending.sync()
            val handle = pending.publish()
            published = true
            return LocalExportResult.Completed(handle)
        } finally {
            withContext(NonCancellable) {
                runCatching { pinned.close() }
                if (!published) runCatching { pending?.discard() }
            }
        }
    }

    private suspend fun ZipOutputStream.writeDomainHistory(
        pinned: com.lavasec.app.data.PinnedActivityRepositoryExport,
    ) {
        putNextEntry(ZipEntry("domain-history.csv"))
        val writer = OutputStreamWriter(this, Charsets.UTF_8)
        writer.write("id,timestamp_millis,domain,decision,reason,resolver,transport,latency_millis,error\n")
        var cursor: ActivityHistoryCursor? = null
        do {
            val page = pinned.historyPage(cursor, EXPORT_PAGE_SIZE)
            page.rows.forEach { row -> writer.write(row.toCsvLine()) }
            writer.flush()
            cursor = page.next?.beforeExclusive
        } while (cursor != null)
        closeEntry()
    }

    private fun ZipOutputStream.writeTextEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}

data class LocalActivityClearSelection(
    val countsAndUptime: Boolean,
    val domainHistory: Boolean,
) {
    init {
        require(countsAndUptime || domainHistory)
    }
}

sealed interface PrivacyMutationResult {
    data object Completed : PrivacyMutationResult
    data object Cancelled : PrivacyMutationResult
    data class Failed(val message: String) : PrivacyMutationResult
    data class CommittedReconciliationRequired(val message: String) : PrivacyMutationResult
}

class CredentialActivityPrivacyController(
    private val repository: ActivityRepository,
    private val writer: CredentialActivityWriter,
    private val fence: CredentialPrivacyFence,
    private val authorizer: PrivacyAuthorizer,
    private val persistConfiguration: suspend (LoggingSettings) -> Unit = {},
    private val applyLoggingPolicy: (LoggingSettings) -> Unit = writer::applyLoggingPolicy,
    /**
     * Destructive boundary for retained credential Network Activity. Its atomic file is
     * separate from the DNS Activity database, so clear runs through this port under the
     * shared privacy fence and advances the live reader revision only after durability.
     */
    private val clearNetworkActivity: suspend () -> Unit = {},
    private val clearGuardProgress: () -> Unit = {},
    private val persistQaConfiguration: suspend (LoggingSettings, LoggingSettings, Long, () -> Boolean) -> Boolean = { _, _, _, _ -> false },
    private val clearQaDiagnostics: suspend () -> Unit = {},
    private val clearDnsFallbackHistory: () -> Unit = {},
    /** Production restore persists through a final native authorization guard in the store. */
    private val persistRestoreConfiguration: (suspend (LoggingSettings, () -> Boolean) -> Boolean)? = null,
) {
    /**
     * Restore's final commit boundary. A refused persistence must preserve every retained row;
     * once persistence succeeds, destructive retention is committed and failures request graph
     * reconciliation instead of pretending a boolean rollback can recreate deleted evidence.
     */
    suspend fun setLoggingForRestore(
        settings: LoggingSettings,
        isCurrent: () -> Boolean = { true },
    ): PrivacyMutationResult {
        when (val authorization = authorizer.authorize()) {
            PrivacyAuthorization.Authorized -> Unit
            PrivacyAuthorization.Cancelled -> return PrivacyMutationResult.Cancelled
            is PrivacyAuthorization.Failed -> return PrivacyMutationResult.Failed(authorization.message)
        }
        return fence.withClear {
            if (!isCurrent()) return@withClear PrivacyMutationResult.Cancelled
            withContext(NonCancellable) {
                val previous = repository.loggingPolicy().settings
                try {
                    // No restrictive SQLite policy, Network Activity clear or Guard clear may run
                    // before this durable write is acknowledged.
                    val guarded = persistRestoreConfiguration
                    if (guarded != null) {
                        if (!guarded(settings, isCurrent)) return@withContext PrivacyMutationResult.Cancelled
                    } else persistConfiguration(settings)
                } catch (failure: Exception) {
                    return@withContext PrivacyMutationResult.Failed(
                        failure.message?.takeIf(String::isNotBlank) ?: "Privacy persistence failed",
                    )
                }
                var reconciliationRequired = false
                suspend fun reconcile(operation: suspend () -> Unit) {
                    repeat(2) {
                        try { operation(); return } catch (_: Exception) { }
                    }
                    reconciliationRequired = true
                }
                if (previous.keepNetworkActivity && !settings.keepNetworkActivity) {
                    reconcile { clearNetworkActivity() }
                }
                if (!settings.keepLavaGuardProgress) reconcile { clearGuardProgress() }
                reconcile { applyLoggingPolicy(settings) }
                if (reconciliationRequired) PrivacyMutationResult.CommittedReconciliationRequired(
                    "Restored retention requires graph reconciliation",
                ) else PrivacyMutationResult.Completed
            }
        }
    }

    /** Separate-store reset: logging commits before any destructive clear; no whole-reset atomicity claim. */
    suspend fun resetForQa(
        expected: LoggingSettings,
        expectedConfigurationGeneration: Long,
        isCurrent: () -> Boolean,
    ): PrivacyMutationResult {
        when (val authorization = authorizer.authorize()) {
            PrivacyAuthorization.Authorized -> Unit
            PrivacyAuthorization.Cancelled -> return PrivacyMutationResult.Cancelled
            is PrivacyAuthorization.Failed -> return PrivacyMutationResult.Failed(authorization.message)
        }
        return fence.withClear {
            if (!isCurrent()) return@withClear PrivacyMutationResult.Cancelled
            withContext(NonCancellable) {
                val desired = expected.copy(keepHistory = false)
                val committed = try { persistQaConfiguration(expected, desired, expectedConfigurationGeneration, isCurrent) }
                    catch (_: Exception) { false }
                if (!committed) return@withContext PrivacyMutationResult.Cancelled
                // From this point, cancellation/retirement cannot pretend deleted rows rolled back.
                var failed = false
                suspend fun clear(operation: suspend () -> Unit) {
                    try { operation() } catch (_: Exception) { failed = true }
                }
                clear { repository.deleteLocalLogRetained(includeCountsAndUptime = true, includeDomainLogs = true) }
                clear { clearNetworkActivity() }
                clear { clearGuardProgress() }
                clear { clearQaDiagnostics() }
                clear { clearDnsFallbackHistory() }
                clear { applyLoggingPolicy(desired) }
                clear { writer.publishCommittedMutation() }
                if (failed) PrivacyMutationResult.CommittedReconciliationRequired("QA reset cleanup requires reconciliation") else PrivacyMutationResult.Completed
            }
        }
    }

    suspend fun setLogging(settings: LoggingSettings): PrivacyMutationResult {
        when (val authorization = authorizer.authorize()) {
            PrivacyAuthorization.Authorized -> Unit
            PrivacyAuthorization.Cancelled -> return PrivacyMutationResult.Cancelled
            is PrivacyAuthorization.Failed -> return PrivacyMutationResult.Failed(authorization.message)
        }
        var durableCommitted = false
        return try {
            fence.withClear {
                val current = repository.loggingPolicy().settings
                val restrictive = LoggingSettings(
                    keepCounts = current.keepCounts && settings.keepCounts,
                    keepHistory = current.keepHistory && settings.keepHistory,
                    keepNetworkActivity =
                        current.keepNetworkActivity && settings.keepNetworkActivity,
                    keepLavaGuardProgress =
                        current.keepLavaGuardProgress && settings.keepLavaGuardProgress,
                )
                // iOS parity (AppViewModel.setKeepNetworkActivity): turning the retention
                // class off clears the saved entries; new entries are not saved after this.
                if (current.keepNetworkActivity && !settings.keepNetworkActivity) {
                    clearNetworkActivity()
                }
                if (!settings.keepLavaGuardProgress) clearGuardProgress()
                // Commit every restriction before persistence, but do not admit a newly enabled
                // retention class until the credential authority commits it.
                applyLoggingPolicy(restrictive)
                persistConfiguration(settings)
                durableCommitted = true
                applyCommittedLoggingPolicy(settings)
            }
            PrivacyMutationResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val message = failure.message?.takeIf(String::isNotBlank) ?: "Privacy mutation failed"
            if (durableCommitted) {
                PrivacyMutationResult.CommittedReconciliationRequired(message)
            } else {
                PrivacyMutationResult.Failed(message)
            }
        }
    }

    /**
     * Clears only the retained Network Activity rows; counts/uptime and Domain History are
     * untouched, matching the iOS "Clear Activity" destructive button scoped to the log
     * (DiagnosticsNetworkActivity.swift confirmation copy).
     */
    suspend fun clearNetworkActivityEntries(): PrivacyMutationResult {
        when (val authorization = authorizer.authorize()) {
            PrivacyAuthorization.Authorized -> Unit
            PrivacyAuthorization.Cancelled -> return PrivacyMutationResult.Cancelled
            is PrivacyAuthorization.Failed -> return PrivacyMutationResult.Failed(authorization.message)
        }
        return try {
            fence.withClear {
                clearNetworkActivity()
                writer.publishCommittedMutation()
            }
            PrivacyMutationResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            PrivacyMutationResult.Failed(
                failure.message?.takeIf(String::isNotBlank) ?: "Privacy mutation failed",
            )
        }
    }

    /**
     * Clears only retained Lava Guard progress (the standalone "Clear Lava Guard progress"
     * destructive button); filtering counts, domain history and Network Activity are untouched.
     * Runs under the same privacy fence as every destructive local-log mutation.
     */
    suspend fun clearGuardProgressEntries(): PrivacyMutationResult {
        when (val authorization = authorizer.authorize()) {
            PrivacyAuthorization.Authorized -> Unit
            PrivacyAuthorization.Cancelled -> return PrivacyMutationResult.Cancelled
            is PrivacyAuthorization.Failed -> return PrivacyMutationResult.Failed(authorization.message)
        }
        return try {
            fence.withClear {
                clearGuardProgress()
                writer.publishCommittedMutation()
            }
            PrivacyMutationResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            PrivacyMutationResult.Failed(
                failure.message?.takeIf(String::isNotBlank) ?: "Privacy mutation failed",
            )
        }
    }

    private fun applyCommittedLoggingPolicy(settings: LoggingSettings) {
        var failure: Throwable? = null
        repeat(POST_COMMIT_POLICY_ATTEMPTS) {
            try {
                applyLoggingPolicy(settings)
                return
            } catch (thrown: Throwable) {
                failure?.addSuppressed(thrown) ?: run { failure = thrown }
            }
        }
        throw checkNotNull(failure)
    }

    suspend fun clear(selection: LocalActivityClearSelection): PrivacyMutationResult {
        when (val authorization = authorizer.authorize()) {
            PrivacyAuthorization.Authorized -> Unit
            PrivacyAuthorization.Cancelled -> return PrivacyMutationResult.Cancelled
            is PrivacyAuthorization.Failed -> return PrivacyMutationResult.Failed(authorization.message)
        }
        return try {
            fence.withClear {
                repository.deleteLocalLogRetained(
                    includeCountsAndUptime = selection.countsAndUptime,
                    includeDomainLogs = selection.domainHistory,
                )
                // SQL deletion is committed. A separate diagnostics-store failure cannot hide
                // that mutation from readers or turn it into an uncommitted failure result.
                var reconciliationRequired = false
                try {
                    if (selection.countsAndUptime && selection.domainHistory) clearDnsFallbackHistory()
                } catch (_: Exception) {
                    reconciliationRequired = true
                }
                try {
                    writer.publishCommittedMutation()
                } catch (_: Exception) {
                    reconciliationRequired = true
                }
                if (reconciliationRequired) PrivacyMutationResult.CommittedReconciliationRequired(
                    "Local log deletion committed; cleanup requires reconciliation",
                ) else PrivacyMutationResult.Completed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            PrivacyMutationResult.Failed(
                failure.message?.takeIf(String::isNotBlank) ?: "Privacy mutation failed",
            )
        }
    }

    private companion object {
        const val POST_COMMIT_POLICY_ATTEMPTS = 2
    }
}

data class LocalFeedbackDraft(
    val subject: String,
    val body: String,
    val attachment: LocalExportHandle?,
)

class LocalFeedbackComposer(
    private val appVersion: () -> String,
    private val deviceSummary: () -> String,
    private val diagnostics: suspend () -> String,
) {
    suspend fun compose(
        description: String,
        includeDiagnostics: Boolean,
        localLogExport: LocalExportHandle?,
    ): LocalFeedbackDraft {
        require(description.isNotBlank())
        val diagnosticText = if (includeDiagnostics) diagnostics() else null
        return LocalFeedbackDraft(
            subject = "LavaSec feedback",
            body = buildString {
                appendLine(description.trim())
                appendLine()
                appendLine("App version: ${appVersion()}")
                appendLine("Device: ${deviceSummary()}")
                diagnosticText?.let {
                    appendLine()
                    appendLine("Diagnostics (explicitly included):")
                    appendLine(it)
                }
            }.trimEnd(),
            attachment = localLogExport,
        )
    }
}

private fun DnsDecisionEvent.toCsvLine(): String = listOf(
    id.toString(),
    timestampMillis.toString(),
    normalizedDomain,
    decision.action.name,
    decision.reason.name,
    resolver?.name.orEmpty(),
    transport?.name.orEmpty(),
    latencyMillis?.toString().orEmpty(),
    error?.name.orEmpty(),
).joinToString(",") { it.csvEscape() } + "\n"

private fun String.csvEscape(): String =
    if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${replace("\"", "\"\"")}\""
    } else {
        this
    }
