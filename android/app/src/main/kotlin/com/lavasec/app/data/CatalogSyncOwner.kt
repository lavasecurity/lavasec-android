package com.lavasec.app.data

import com.lavasec.app.foundation.catalog.CatalogArtifact
import com.lavasec.app.foundation.catalog.CatalogArtifactLoader
import com.lavasec.app.foundation.catalog.CatalogCandidateRejection
import com.lavasec.app.foundation.catalog.CatalogFetchEnvelope
import com.lavasec.app.foundation.catalog.CatalogFreshnessPolicy
import com.lavasec.app.foundation.catalog.CatalogProvenance
import com.lavasec.app.foundation.catalog.CatalogProvenancePolicy
import com.lavasec.app.foundation.catalog.CatalogSelection
import com.lavasec.app.foundation.catalog.CatalogSelectionOutcome
import com.lavasec.app.foundation.catalog.CatalogSelectionPolicy
import com.lavasec.app.foundation.filter.CustomBlocklistSourceAcquisitionException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a refresh landed. Every value is reachable and honest; none is a fabricated success. */
internal enum class CatalogRefreshStatus {
    /** A newer valid remote catalog was selected and is now the installed last-known-good. */
    UPDATED,

    /** The candidate was byte-identical to what is installed; nothing changed. */
    UNCHANGED,

    /** A valid installed catalog was retained because the candidate was invalid or not newer. */
    KEPT_INSTALLED,

    /** No valid installed catalog exists; the bundled trust anchor remains effective. */
    BUNDLED_FALLBACK,

    /** Every configured endpoint failed; the installed/bundled catalog is untouched. */
    OFFLINE,

    /** A valid newer candidate could not be durably cached; the previous catalog is retained. */
    PERSIST_FAILED,

    /**
     * A valid newer candidate was selected, but installing it into the production filter
     * authority (recompile/reload) failed. The resident artifact and the effective catalog are
     * untouched; the cache is rolled back to the pre-refresh bytes.
     */
    INSTALL_FAILED,

    /** A newer refresh superseded this one under the revision fence; it was not applied. */
    SUPERSEDED,
}

/**
 * Installs one validated catalog into the production filter authority. Returning `false` means the
 * authority kept its resident artifact; the owner then rolls the persisted selection back so the
 * durable last-known-good never disagrees with what the filter authority is actually running.
 */
internal fun interface CatalogInstaller {
    suspend fun install(artifact: CatalogArtifact): Boolean
}

/** Cancellation still reaches the worker, but its exact requested catalog is already durable. */
internal class CatalogInstallCommittedCancellation(cause: CancellationException) :
    CancellationException("Catalog publication committed before cancellation") {
    init { initCause(cause) }
}

internal data class CatalogRefreshResult(
    val status: CatalogRefreshStatus,
    val rejection: CatalogCandidateRejection? = null,
    val fetchFailure: CatalogFetchFailureReason? = null,
    val coalesced: Boolean = false,
    val sourceFailure: CustomBlocklistSourceAcquisitionException? = null,
)

/** Owner state a surface may render. Never includes source names or hashes. */
internal data class CatalogSyncSnapshot(
    val provenance: CatalogProvenance,
    val catalogVersion: String,
    val generatedAtEpochSeconds: Long,
    val sourceCount: Int,
    val guardrailCount: Int,
    val fresh: Boolean,
    val ageSeconds: Long?,
    val refreshing: Boolean,
    val lastStatus: CatalogRefreshStatus?,
    /** Last installation needs explicit foreground source repair; no source identity is retained. */
    val sourceRepairRequired: Boolean = false,
)

/**
 * Validated remote-catalog acquisition, cache and fallback owner.
 *
 * One [refresh] fetches each configured endpoint in order, hands the first response to the pure
 * [CatalogSelectionPolicy] against the bundled anchor and the current last-known-good, then
 * persists a selected candidate as the new last-known-good behind a compare-and-swap. Concurrent
 * callers coalesce onto a single in-flight refresh; a refresh that has been superseded by a newer
 * one is fenced out rather than allowed to overwrite it; and no failure path ever discards a
 * valid installed artifact — the bundled catalog is always effective when nothing better exists.
 *
 * The fetch/cache/selection work is off the caller's thread; [snapshot] is a plain in-memory read.
 */
internal class CatalogSyncOwner(
    private val bundled: CatalogArtifact,
    private val cache: CatalogManifestCache,
    private val fetcher: CatalogManifestFetcher,
    private val catalogUrls: List<String>,
    private val freshnessPolicy: CatalogFreshnessPolicy = CatalogFreshnessPolicy(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val installer: CatalogInstaller? = null,
) {
    init {
        require(bundled.provenance == CatalogProvenance.BUNDLED) {
            "the bundled catalog must carry BUNDLED provenance"
        }
        require(catalogUrls.isNotEmpty()) { "at least one catalog endpoint is required" }
    }

    private val approvedSourceIds = CatalogProvenancePolicy.approvedSourceIds(bundled.manifest)
    private val stateLock = Any()
    private val refreshScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val revision = AtomicLong()
    private class RefreshFlight(val result: Deferred<CatalogRefreshResult>) {
        var waiters = 1
        var retiring = false
    }
    private var inFlight: RefreshFlight? = null

    // Artifact and verification time are read as one value so a concurrent publish can never pair
    // a new artifact with an old timestamp (the split-read footgun the DNS family already hit).
    private data class Installed(val artifact: CatalogArtifact, val verifiedAtEpochSeconds: Long)

    @Volatile private var installedState: Installed? = null
    @Volatile private var refreshing = false
    private data class LastRefresh(val status: CatalogRefreshStatus, val sourceRepairRequired: Boolean)
    @Volatile private var lastRefresh: LastRefresh? = null
    @Volatile private var cacheLoaded = false

    /**
     * Reads the persisted last-known-good once. Corrupt, oversized or unparseable cache content is
     * ignored, leaving the bundled catalog effective. Idempotent and safe to call from any thread
     * (callers should keep it off the main thread, since it performs file I/O).
     */
    fun loadCachedInstalled() {
        synchronized(stateLock) {
            if (cacheLoaded) return
            adoptCachedInstalled()
            cacheLoaded = true
        }
    }

    /** In-memory projection for the product snapshot. Does no I/O. */
    fun snapshot(): CatalogSyncSnapshot {
        val current = installedState
        val completed = lastRefresh
        val effective = current?.artifact ?: bundled
        val age = current?.let { (nowEpochSeconds() - it.verifiedAtEpochSeconds).coerceAtLeast(0L) }
        return CatalogSyncSnapshot(
            provenance = effective.provenance,
            catalogVersion = effective.catalogVersion,
            generatedAtEpochSeconds = effective.generatedAtEpochSeconds,
            sourceCount = effective.manifest.sources.size,
            guardrailCount = effective.manifest.guardrails.size,
            // No installed remote catalog is not "stale"; the bundled anchor is always valid.
            fresh = current == null || freshnessPolicy.isFresh(age, statusIsError = false),
            ageSeconds = age,
            refreshing = refreshing,
            lastStatus = completed?.status,
            sourceRepairRequired = completed?.sourceRepairRequired == true,
        )
    }

    /**
     * Coalesced refresh. Every concurrent caller receives the same result; a caller that joins an
     * in-flight refresh is marked [CatalogRefreshResult.coalesced]. Work belongs to this process
     * owner, not the first caller. It retires only after its last waiting caller leaves.
     */
    suspend fun refresh(): CatalogRefreshResult {
        while (true) {
            val (flight, coalesced, retiring) = synchronized(stateLock) {
                val current = inFlight
                if (current != null) {
                    if (!current.retiring) current.waiters++
                    Triple(current, true, current.retiring)
                } else {
                    val result = refreshScope.async(start = CoroutineStart.LAZY) {
                        loadCachedInstalled()
                        try {
                            performRefresh()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            CatalogRefreshResult(CatalogRefreshStatus.OFFLINE,
                                fetchFailure = CatalogFetchFailureReason.NETWORK_IO)
                        }
                    }
                    val created = RefreshFlight(result)
                    inFlight = created
                    result.invokeOnCompletion {
                        synchronized(stateLock) { if (inFlight === created) inFlight = null }
                    }
                    Triple(created, false, false)
                }
            }
            if (retiring) {
                // A last-caller cancellation must finish rollback/adoption before a successor
                // can select or persist another candidate.
                flight.result.join()
                continue
            }
            try {
                flight.result.start()
                return flight.result.await().copy(coalesced = coalesced)
            } finally {
                withContext(NonCancellable) {
                    val cancel = synchronized(stateLock) {
                        flight.waiters--
                        (flight.waiters == 0 && !flight.result.isCompleted).also {
                            if (it) flight.retiring = true
                        }
                    }
                    if (cancel) {
                        flight.result.cancel()
                        flight.result.join()
                    }
                }
            }
        }
    }

    private fun recordRefresh(result: CatalogRefreshResult) {
        synchronized(stateLock) {
            // Publish the status and retry reason together without retaining private source details.
            lastRefresh = LastRefresh(result.status,
                result.status == CatalogRefreshStatus.INSTALL_FAILED && result.sourceFailure != null)
        }
    }

    private suspend fun performRefresh(): CatalogRefreshResult {
        val assigned = revision.incrementAndGet()
        synchronized(stateLock) { refreshing = true }
        try {
            val baseline = installedState?.artifact
            val outcome = fetchFirstAvailable()
            val now = nowEpochSeconds()
            val selection = CatalogSelectionPolicy.select(
                bundled = bundled,
                lastKnownGood = baseline,
                candidate = outcome.envelope,
                nowEpochSeconds = now,
            )
            // A refresh that lost its place to a newer one has nothing authority to apply.
            if (assigned < revision.get()) {
                return CatalogRefreshResult(CatalogRefreshStatus.SUPERSEDED)
            }
            val result = applySelection(selection, outcome, baseline, now)
            recordRefresh(result)
            return result
        } finally {
            synchronized(stateLock) { refreshing = false }
        }
    }

    private suspend fun applySelection(
        selection: CatalogSelection,
        outcome: FetchOutcome,
        baseline: CatalogArtifact?,
        now: Long,
    ): CatalogRefreshResult {
        // A transport failure means there was no candidate at all; report it as such rather than
        // as a rejected candidate, even though the selection policy keeps the old catalog either
        // way.
        if (outcome.failure != null) {
            return CatalogRefreshResult(
                status = CatalogRefreshStatus.OFFLINE,
                fetchFailure = outcome.failure,
            )
        }
        return when (selection.outcome) {
            CatalogSelectionOutcome.SELECTED_CANDIDATE ->
                persistSelected(selection, outcome.envelope?.bytes, baseline, now)

            CatalogSelectionOutcome.KEPT_INSTALLED ->
                renewUnchanged(selection, outcome.envelope?.bytes, baseline, now)

            CatalogSelectionOutcome.KEPT_LAST_KNOWN_GOOD ->
                CatalogRefreshResult(CatalogRefreshStatus.KEPT_INSTALLED, rejection = selection.rejection)

            CatalogSelectionOutcome.FELL_BACK_TO_BUNDLED ->
                CatalogRefreshResult(CatalogRefreshStatus.BUNDLED_FALLBACK, rejection = selection.rejection)

            CatalogSelectionOutcome.BUNDLED_ONLY ->
                CatalogRefreshResult(CatalogRefreshStatus.BUNDLED_FALLBACK)
        }
    }

    /** Only the exact verified bytes renew freshness; equal publication dates alone do not. */
    private suspend fun renewUnchanged(
        selection: CatalogSelection, bytes: ByteArray?, baseline: CatalogArtifact?, now: Long,
    ): CatalogRefreshResult {
        if (baseline == null || bytes == null || catalogSha256Hex(bytes) != baseline.contentSha256)
            return CatalogRefreshResult(
                if (baseline == null) CatalogRefreshStatus.BUNDLED_FALLBACK else CatalogRefreshStatus.KEPT_INSTALLED,
                rejection = selection.rejection,
            )
        currentCoroutineContext().ensureActive()
        val result = withContext(NonCancellable + ioDispatcher) {
            when (cache.write(bytes, now, baseline.contentSha256)) {
                CatalogCacheWriteResult.WRITTEN -> {
                    installedState = Installed(baseline, now)
                    CatalogRefreshResult(CatalogRefreshStatus.UNCHANGED)
                }
                CatalogCacheWriteResult.CONFLICT -> {
                    adoptCachedInstalled()
                    CatalogRefreshResult(CatalogRefreshStatus.SUPERSEDED)
                }
                CatalogCacheWriteResult.STORAGE_FAILED -> CatalogRefreshResult(CatalogRefreshStatus.PERSIST_FAILED)
            }.also(::recordRefresh)
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun persistSelected(
        selection: CatalogSelection,
        candidateBytes: ByteArray?,
        baseline: CatalogArtifact?,
        now: Long,
    ): CatalogRefreshResult = CatalogInstallationGate.withStableCatalog {
        persistSelectedSerialized(selection, candidateBytes, baseline, now)
    }

    private suspend fun persistSelectedSerialized(
        selection: CatalogSelection,
        candidateBytes: ByteArray?,
        baseline: CatalogArtifact?,
        now: Long,
    ): CatalogRefreshResult {
        val bytes = candidateBytes
            ?: return CatalogRefreshResult(
                status = CatalogRefreshStatus.OFFLINE,
                fetchFailure = CatalogFetchFailureReason.EMPTY_BODY,
            )
        // Snapshot the pre-refresh durable bytes so a failed install can restore the exact
        // last-known-good rather than leaving the cache ahead of the running authority.
        val previous = withContext(ioDispatcher) { cache.read() }
        var wrote = false
        var adopted = false
        return try {
            val write = withContext(NonCancellable + ioDispatcher) {
                cache.write(
                    bytes = bytes,
                    verifiedAtEpochSeconds = now,
                    expectedCurrentSha256 = baseline?.contentSha256,
                ).also { wrote = it == CatalogCacheWriteResult.WRITTEN }
            }
            currentCoroutineContext().ensureActive()
            when (write) {
                CatalogCacheWriteResult.WRITTEN -> {
                    // Persisting the selection without installing it would let the durable cache
                    // diverge from the filter authority. The installer is optional only so the
                    // platform-free owner stays testable; production always supplies one.
                    val install = installer
                    var sourceFailure: CustomBlocklistSourceAcquisitionException? = null
                    val installed = try {
                        install == null || install.install(selection.artifact)
                    } catch (committed: CatalogInstallCommittedCancellation) {
                        adopted = true
                        installedState = Installed(selection.artifact, now)
                        recordRefresh(CatalogRefreshResult(CatalogRefreshStatus.UPDATED))
                        throw committed
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: CustomBlocklistSourceAcquisitionException) {
                        sourceFailure = failure
                        false
                    } catch (_: Exception) {
                        false
                    }
                    if (installed) {
                        adopted = true
                        installedState = Installed(selection.artifact, now)
                        CatalogRefreshResult(CatalogRefreshStatus.UPDATED)
                    } else {
                        rollbackCache(previous, bytes)
                        CatalogRefreshResult(CatalogRefreshStatus.INSTALL_FAILED, sourceFailure = sourceFailure)
                    }
                }

                CatalogCacheWriteResult.CONFLICT -> {
                    // A concurrent writer advanced the cache; adopt its artifact instead of ours so
                    // the newer revision wins.
                    withContext(ioDispatcher) { adoptCachedInstalled() }
                    CatalogRefreshResult(CatalogRefreshStatus.SUPERSEDED)
                }

                CatalogCacheWriteResult.STORAGE_FAILED ->
                    CatalogRefreshResult(CatalogRefreshStatus.PERSIST_FAILED)
            }
        } catch (cancelled: CancellationException) {
            if (wrote && !adopted) rollbackCache(previous, bytes)
            throw cancelled
        }
    }

    private suspend fun rollbackCache(
        previous: CatalogCachedManifest?,
        writtenBytes: ByteArray,
    ) {
        withContext(NonCancellable + ioDispatcher) {
            if (previous == null) {
                // There was no last-known-good before this refresh; drop the just-written file so a
                // restart resolves the bundle rather than an uninstalled remote selection.
                cache.delete()
            } else {
                cache.write(
                    bytes = previous.bytes,
                    verifiedAtEpochSeconds = previous.verifiedAtEpochSeconds,
                    expectedCurrentSha256 = catalogSha256Hex(writtenBytes),
                )
            }
        }
    }

    private data class FetchOutcome(
        val envelope: CatalogFetchEnvelope?,
        val failure: CatalogFetchFailureReason?,
    )

    private suspend fun fetchFirstAvailable(): FetchOutcome {
        var lastFailure: CatalogFetchFailureReason? = null
        for (url in catalogUrls) {
            try {
                val bytes = fetcher.fetch(url)
                if (bytes.isEmpty()) {
                    lastFailure = CatalogFetchFailureReason.EMPTY_BODY
                    continue
                }
                return FetchOutcome(CatalogFetchEnvelope(bytes), null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CatalogFetchException) {
                lastFailure = error.reason
            } catch (_: Throwable) {
                lastFailure = CatalogFetchFailureReason.NETWORK_IO
            }
        }
        return FetchOutcome(
            envelope = null,
            failure = lastFailure ?: CatalogFetchFailureReason.NETWORK_IO,
        )
    }

    private fun adoptCachedInstalled() {
        val cached = cache.read() ?: return
        val artifact = CatalogArtifactLoader.load(
            bytes = cached.bytes,
            provenance = CatalogProvenance.LAST_KNOWN_GOOD,
            approvedSourceIds = approvedSourceIds,
        ) ?: return
        installedState = Installed(artifact, cached.verifiedAtEpochSeconds)
    }
}
