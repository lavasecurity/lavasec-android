package com.lavasec.app.product.activity

import com.lavasec.app.foundation.activity.NetworkActivityContract
import com.lavasec.app.product.LOCAL_LOG_PAGE_SIZE
import com.lavasec.app.product.PageToken
import com.lavasec.app.product.Revisioned
import com.lavasec.app.product.StableKey
import com.lavasec.app.product.StablePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ProductActivityState(
    val summaryQuery: ActivitySummaryQuery,
    val historyQuery: DomainHistoryQuery = DomainHistoryQuery(),
    val summary: ProductLoadState<ActivitySummary> = ProductLoadState.Loading(lastGood = null),
    val history: ProductLoadState<ProductPagedRows<DomainHistoryRow>> =
        ProductLoadState.Loading(lastGood = null),
    val retainedDomainLogs: ProductLoadState<Boolean> =
        ProductLoadState.Loading(lastGood = null),
    val network: ProductLoadState<ProductPagedRows<NetworkActivityRow>> =
        ProductLoadState.Loading(lastGood = null),
    val historyLoadingMore: Boolean = false,
    val networkRefreshing: Boolean = false,
    val networkLoadingMore: Boolean = false,
    val networkContentGeneration: Long = 0L,
    val networkHydrated: Boolean = false,
    val networkFirstPageLoading: Boolean = false,
)

enum class NetworkPageLoadOutcome {
    COMPLETED,
    REJECTED,
    FAILED,
    SUPERSEDED,
}

private fun nextNetworkContentGeneration(
    current: Long,
): Long? =
    current.takeIf { it < Long.MAX_VALUE }
        ?.let { value -> Math.incrementExact(value) }

/**
 * Pure Activity coordinator. Counts/uptime (C), domain identities (D), logging policy (P), and
 * network activity (N) have independent monotonic watermarks. A watermark is sticky as soon as
 * its authoritative read or signal is observed, so cancellation, failure, or a manual refresh
 * cannot publish an older snapshot after newer work has been requested.
 */
class ProductActivityStateMachine(
    private val port: ProductActivityPort,
    initialDateLens: ActivityDateLens,
) {
    private val mutex = Mutex()

    private val mutableStateFlow = MutableStateFlow(
        ProductActivityState(
            summaryQuery = ActivitySummaryQuery(dateLens = initialDateLens),
        ),
    )

    @Volatile
    private var currentState = mutableStateFlow.value
        set(value) {
            field = value
            mutableStateFlow.value = value
        }

    val stateFlow: StateFlow<ProductActivityState> = mutableStateFlow.asStateFlow()

    val state: ProductActivityState
        get() = currentState

    private var observedSummaryRevision: Long? = null
    private var observedDomainRevision: Long? = null
    private var observedDomainPolicyRevision: Long? = null
    private var observedNetworkRevision: Long? = null

    private var requiredSummaryRevision: Long? = null
    private var requiredDomainRevision: Long? = null
    private var requiredDomainPolicyRevision: Long? = null
    private var requiredNetworkRevision: Long? = null

    private var publishedSummaryDomainRevision: Long? = null
    private var publishedSummaryPolicyRevision: Long? = null
    private var publishedHistoryRevision: Long? = null
    private var publishedHistoryPolicyRevision: Long? = null

    private var rememberedRevisionSignal = ProductActivityRevisionSignal(token = 0L)
    private var pendingSummaryRevision: SummaryRevisionLoad? = null
    private var pendingDomainRevision: DomainRevisionLoad? = null
    private var pendingNetworkRevision: RevisionLoad? = null

    private var summaryRevisionEpoch = 0L
    private var domainRevisionEpoch = 0L
    private var networkRevisionEpoch = 0L
    private var completedSummaryRevisionEpoch = 0L
    private var completedDomainRevisionEpoch = 0L
    private var completedNetworkRevisionEpoch = 0L
    private var summaryEpoch = 0L
    private var historyEpoch = 0L
    private var networkEpoch = 0L
    private var activeSummaryEpoch: Long? = null
    private var activeHistoryEpoch: Long? = null
    private var activeNetworkEpoch: Long? = null
    private var historyLoadMoreEpoch: Long? = null
    private var networkLoadMoreEpoch: Long? = null

    suspend fun synchronizeRevisions() = synchronizeRevisionsInternal(signal = null)

    suspend fun synchronizeRevisions(signal: ProductActivityRevisionSignal) =
        synchronizeRevisionsInternal(signal)

    private suspend fun synchronizeRevisionsInternal(signal: ProductActivityRevisionSignal?) {
        val synchronization = RevisionSynchronization()
        signal?.let { incoming ->
            mutex.withLock {
                rememberedRevisionSignal = ProductActivityRevisionSignal(
                    token = maxOf(rememberedRevisionSignal.token, incoming.token),
                    policyRevision = maxOf(
                        rememberedRevisionSignal.policyRevision,
                        incoming.policyRevision,
                    ),
                )
                val previousRequired = requiredDomainPolicyRevision ?: 0L
                requiredDomainPolicyRevision = maxRevision(
                    requiredDomainPolicyRevision,
                    incoming.policyRevision,
                )
                val requiredPolicy = requireNotNull(requiredDomainPolicyRevision)
                if (requiredPolicy > previousRequired) {
                    val summaryHasCoveringPublication =
                        publishedSummaryPolicyRevision?.let { it >= requiredPolicy } == true
                    val historyHasCoveringPublication =
                        publishedHistoryPolicyRevision?.let { it >= requiredPolicy } == true
                    val summaryHasPublicationOrActiveWork =
                        publishedSummaryPolicyRevision != null ||
                            activeSummaryEpoch == summaryEpoch
                    val historyHasPublicationOrActiveWork =
                        publishedHistoryPolicyRevision != null ||
                            activeHistoryEpoch == historyEpoch
                    val fenceSummary =
                        !summaryHasCoveringPublication &&
                            summaryHasPublicationOrActiveWork
                    val fenceHistory =
                        !historyHasCoveringPublication &&
                            historyHasPublicationOrActiveWork
                    invalidateIdentitySurfacesLocked(
                        summary = fenceSummary,
                        history = fenceHistory,
                    )
                }
            }
        }
        coroutineScope {
            launch { synchronizeSummaryRevision(synchronization) }
            launch { synchronizeDomainRevision(synchronization) }
            launch { synchronizeNetworkRevision() }
        }
    }

    private fun invalidateSummaryMetadataLocked() {
        summaryEpoch += 1L
        activeSummaryEpoch = null
        pendingSummaryRevision = null
        publishedSummaryDomainRevision = null
        publishedSummaryPolicyRevision = null
    }

    private fun invalidateHistoryMetadataLocked() {
        historyEpoch += 1L
        activeHistoryEpoch = null
        pendingDomainRevision = null
        historyLoadMoreEpoch = null
        publishedHistoryRevision = null
        publishedHistoryPolicyRevision = null
    }

    private fun invalidateIdentitySurfacesLocked(
        summary: Boolean,
        history: Boolean,
    ) {
        if (!summary && !history) return
        if (summary) invalidateSummaryMetadataLocked()
        if (history) invalidateHistoryMetadataLocked()
        currentState = currentState.withIdentitySurfacesInvalidated(
            summary = summary,
            history = history,
        )
    }

    private fun ProductActivityState.withIdentitySurfacesInvalidated(
        summary: Boolean = false,
        history: Boolean = false,
    ): ProductActivityState = copy(
        summary = if (summary) {
            ProductLoadState.Loading(lastGood = null)
        } else {
            this.summary
        },
        history = if (history) {
            ProductLoadState.Loading(lastGood = null)
        } else {
            this.history
        },
        retainedDomainLogs = if (history) {
            ProductLoadState.Loading(lastGood = null)
        } else {
            retainedDomainLogs
        },
        historyLoadingMore = if (history) false else historyLoadingMore,
    )

    private suspend fun synchronizeSummaryRevision(
        synchronization: RevisionSynchronization,
    ) {
        val ticket = mutex.withLock {
            RevisionReadTicket(
                readEpoch = ++summaryRevisionEpoch,
                surfaceEpoch = summaryEpoch,
                surfaceWasActive = activeSummaryEpoch == summaryEpoch,
            )
        }
        val revision = try {
            port.summaryCountRevision().validRevision()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failSummaryRevision(ticket, failure)
            return
        }
        val work = mutex.withLock {
            // Starting a newer read must not revoke an older successful result: the newer
            // caller may still be cancelled. Only a completed read supersedes older tickets.
            if (ticket.readEpoch <= completedSummaryRevisionEpoch) return@withLock null
            completedSummaryRevisionEpoch = ticket.readEpoch
            requiredSummaryRevision = maxRevision(requiredSummaryRevision, revision)
            val target = summaryTargetLocked()
            if (
                summaryCoversLocked(target) ||
                pendingSummaryRevision?.covers(target) == true ||
                !synchronization.claimSummary(target)
            ) {
                null
            } else {
                beginSummaryReloadLocked(
                    exactCountRevision = revision.takeIf { it == target.countRevision },
                ).let { started ->
                    val pending = target.copy(surfaceEpoch = started.epoch)
                    pendingSummaryRevision = pending
                    started.copy(pendingRevision = pending)
                }
            }
        } ?: return
        executeSummary(work)
    }

    private suspend fun synchronizeDomainRevision(
        synchronization: RevisionSynchronization,
    ) {
        val ticket = mutex.withLock {
            DomainRevisionReadTicket(
                readEpoch = ++domainRevisionEpoch,
                historyEpoch = historyEpoch,
                policyRevision = rememberedRevisionSignal.policyRevision,
                historyWasActive = activeHistoryEpoch == historyEpoch,
            )
        }
        val revision = try {
            port.domainLogRevision().validRevision()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failDomainRevision(ticket, failure)
            return
        }
        val work = mutex.withLock {
            if (ticket.readEpoch <= completedDomainRevisionEpoch) return@withLock null
            completedDomainRevisionEpoch = ticket.readEpoch

            val previousDataRequirement = requiredDomainRevision
            val previousPolicyRequirement = requiredDomainPolicyRevision ?: 0L
            requiredDomainRevision = maxRevision(requiredDomainRevision, revision)
            requiredDomainPolicyRevision = maxRevision(
                requiredDomainPolicyRevision,
                ticket.policyRevision,
            )
            val targetData = requireNotNull(maxRevision(
                requiredDomainRevision,
                observedDomainRevision,
                publishedSummaryDomainRevision,
                publishedHistoryRevision,
            ))
            val targetPolicy = requireNotNull(maxRevision(
                requiredDomainPolicyRevision,
                observedDomainPolicyRevision,
                publishedSummaryPolicyRevision,
                publishedHistoryPolicyRevision,
                rememberedRevisionSignal.policyRevision,
            ))
            requiredDomainRevision = targetData
            requiredDomainPolicyRevision = targetPolicy

            val dataWatermarkAdvanced =
                previousDataRequirement?.let { targetData > it } ?: (targetData > 0L)
            val policyWatermarkAdvanced = targetPolicy > previousPolicyRequirement
            val watermarkAdvanced = dataWatermarkAdvanced || policyWatermarkAdvanced
            if (watermarkAdvanced) {
                // A missing publication is not proof that an already-active manual/query read
                // captured this newer vector. Fence both identity surfaces first; pending work
                // below is then recreated with the exact sticky D/P target.
                invalidateIdentitySurfacesLocked(summary = true, history = true)
            }

            val historyNeeded =
                activeHistoryEpoch != historyEpoch &&
                    !historyCoversLocked(targetData, targetPolicy) &&
                    pendingDomainRevision?.covers(targetData, targetPolicy) != true
            val summaryTarget = summaryTargetLocked()
            val summaryNeeded =
                activeSummaryEpoch != summaryEpoch &&
                    !summaryCoversLocked(summaryTarget) &&
                    pendingSummaryRevision?.covers(summaryTarget) != true &&
                    synchronization.claimSummary(summaryTarget)

            val history = if (historyNeeded) {
                beginHistoryReloadLocked(
                    exactRevision = revision.takeIf { it == targetData },
                    exactPolicyRevision = targetPolicy,
                ).let { started ->
                    val pending = DomainRevisionLoad(
                        revision = targetData,
                        policyRevision = targetPolicy,
                        historyEpoch = started.epoch,
                    )
                    pendingDomainRevision = pending
                    started.copy(pendingRevision = pending)
                }
            } else {
                null
            }
            val summary = if (summaryNeeded) {
                beginSummaryReloadLocked(
                    exactDomainRevision = targetData,
                    exactPolicyRevision = targetPolicy,
                ).let { started ->
                    val pending = summaryTarget.copy(surfaceEpoch = started.epoch)
                    pendingSummaryRevision = pending
                    started.copy(pendingRevision = pending)
                }
            } else {
                null
            }
            DomainSynchronizationWork(history = history, summary = summary)
                .takeIf { it.history != null || it.summary != null }
        } ?: return

        coroutineScope {
            val history = work.history?.let { async { executeHistory(it) } }
            val summary = work.summary?.let { async { executeSummary(it) } }
            history?.await()
            summary?.await()
        }
    }

    private suspend fun synchronizeNetworkRevision() {
        val ticket = mutex.withLock {
            RevisionReadTicket(
                readEpoch = ++networkRevisionEpoch,
                surfaceEpoch = networkEpoch,
                surfaceWasActive = activeNetworkEpoch == networkEpoch,
            )
        }
        val revision = try {
            port.networkRevision().validRevision()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failNetworkRevision(ticket, failure)
            return
        }
        val work = mutex.withLock {
            if (ticket.readEpoch <= completedNetworkRevisionEpoch) return@withLock null
            completedNetworkRevisionEpoch = ticket.readEpoch
            requiredNetworkRevision = maxRevision(requiredNetworkRevision, revision)
            val targetRevision = requireNotNull(requiredNetworkRevision)
            if (
                observedNetworkRevision?.let { it >= targetRevision } == true ||
                pendingNetworkRevision?.let { it.revision >= targetRevision } == true
            ) {
                null
            } else {
                beginNetworkReloadLocked()
                    ?.copy(
                        revision = targetRevision,
                        exactRevision = revision.takeIf { it == targetRevision },
                        minimumRevision = targetRevision,
                    )
                    ?.also { pendingWork ->
                        pendingNetworkRevision = RevisionLoad(targetRevision, pendingWork.epoch)
                    }
            }
        } ?: return
        executeNetwork(work)
    }

    suspend fun setDateLens(dateLens: ActivityDateLens): Boolean = changeSummaryQuery { query ->
        query.copy(dateLens = dateLens)
    }

    suspend fun setTopDomainDecision(decision: DomainDecision): Boolean =
        changeSummaryQuery { query -> query.copy(topDomainDecision = decision) }

    suspend fun setTopDomainSearch(search: String): Boolean = changeSummaryQuery { query ->
        query.copy(topDomainSearch = search.trim())
    }

    suspend fun refreshSummary() {
        val work = mutex.withLock { beginSummaryReloadLocked() }
        executeSummary(work)
    }

    suspend fun setHistoryDecision(decision: DomainDecision): Boolean =
        changeHistoryQuery { query -> query.copy(decision = decision) }

    suspend fun setHistorySearch(search: String): Boolean = changeHistoryQuery { query ->
        query.copy(search = search.trim())
    }

    suspend fun refreshHistory() {
        val work = mutex.withLock { beginHistoryReloadLocked() }
        executeHistory(work)
    }

    suspend fun refreshNetwork() {
        val work = mutex.withLock {
            beginNetworkReloadLocked(refreshing = true)
        } ?: return
        executeNetwork(work)
    }

    /** Reloads after a verified destructive boundary without retaining predecessor rows. */
    suspend fun reloadNetworkAfterClear() {
        val work = mutex.withLock {
            beginNetworkReloadLocked(retainLastGood = false)
        } ?: return
        executeNetwork(work)
    }

    /** Returns false when there is no next page or another history page is already in flight. */
    suspend fun loadMoreHistory(): Boolean {
        val work = mutex.withLock {
            if (historyLoadMoreEpoch != null) {
                null
            } else {
                val base = currentState.history.pageAvailableForLoadMore()
                val next = base?.next
                val policyRevision = base?.sourcePolicyRevision
                val requiredData = requiredDomainRevision
                val requiredPolicy = requiredDomainPolicyRevision
                if (
                    base == null || next == null || policyRevision == null ||
                    base.historyQuery != currentState.historyQuery ||
                    requiredData?.let { base.sourceRevision < it } == true ||
                    requiredPolicy?.let { policyRevision < it } == true
                ) {
                    null
                } else {
                    historyLoadMoreEpoch = historyEpoch
                    activeHistoryEpoch = historyEpoch
                    currentState = currentState.copy(
                        history = ProductLoadState.Loading(lastGood = base),
                        historyLoadingMore = true,
                    )
                    HistoryWork(
                        epoch = historyEpoch,
                        query = currentState.historyQuery,
                        request = DomainHistoryPageRequest(
                            query = currentState.historyQuery,
                            token = next,
                        ),
                        lastGood = base,
                        retainedLastGood = currentState.retainedDomainLogs.lastGoodOrNull(),
                        appendTo = base,
                        minimumRevision = maxRevision(base.sourceRevision, requiredData),
                        exactRevision = base.sourceRevision,
                        minimumPolicyRevision = maxRevision(policyRevision, requiredPolicy),
                        exactPolicyRevision = policyRevision,
                    )
                }
            }
        } ?: return false

        try {
            executeHistory(work)
        } catch (cancelled: CancellationException) {
            releaseCancelledHistoryLoadMore(work)
            throw cancelled
        }
        return true
    }

    /**
     * Returns an explicit terminal for every admitted invocation. Cancellation remains a
     * coroutine cancellation so the caller can retain its exact cancellation identity.
     */
    suspend fun loadMoreNetwork(): NetworkPageLoadOutcome {
        val work = mutex.withLock {
            if (networkLoadMoreEpoch != null) {
                null
            } else {
                val base = currentState.network.pageAvailableForLoadMore()
                val next = base?.next
                if (
                    base == null || next == null ||
                    requiredNetworkRevision?.let { base.sourceRevision < it } == true
                ) {
                    null
                } else {
                    networkLoadMoreEpoch = networkEpoch
                    activeNetworkEpoch = networkEpoch
                    currentState = currentState.copy(
                        network = ProductLoadState.Loading(lastGood = base),
                        networkRefreshing = false,
                        networkLoadingMore = true,
                    )
                    NetworkWork(
                        epoch = networkEpoch,
                        request = NetworkActivityPageRequest(token = next),
                        lastGood = base,
                        appendTo = base,
                        minimumRevision = maxRevision(base.sourceRevision, requiredNetworkRevision),
                        exactRevision = base.sourceRevision,
                    )
                }
            }
        } ?: return NetworkPageLoadOutcome.REJECTED

        return try {
            executeNetwork(work)
        } catch (cancelled: CancellationException) {
            releaseCancelledNetworkLoadMore(work)
            throw cancelled
        }
    }

    private suspend fun changeSummaryQuery(
        transform: (ActivitySummaryQuery) -> ActivitySummaryQuery,
    ): Boolean {
        val work = mutex.withLock {
            val next = transform(currentState.summaryQuery)
            if (next == currentState.summaryQuery) {
                null
            } else {
                publishedSummaryDomainRevision = null
                publishedSummaryPolicyRevision = null
                beginSummaryReloadLocked(query = next)
            }
        } ?: return false

        executeSummary(work)
        return true
    }

    private suspend fun changeHistoryQuery(
        transform: (DomainHistoryQuery) -> DomainHistoryQuery,
    ): Boolean {
        val work = mutex.withLock {
            val next = transform(currentState.historyQuery)
            if (next == currentState.historyQuery) {
                null
            } else {
                publishedHistoryRevision = null
                publishedHistoryPolicyRevision = null
                beginHistoryReloadLocked(query = next)
            }
        } ?: return false

        executeHistory(work)
        return true
    }

    private fun beginSummaryReloadLocked(
        exactCountRevision: Long? = null,
        exactDomainRevision: Long? = null,
        exactPolicyRevision: Long? = null,
        query: ActivitySummaryQuery = currentState.summaryQuery,
    ): SummaryWork {
        val previousPending = pendingSummaryRevision
        val minimumCount = maxRevision(
            requiredSummaryRevision,
            observedSummaryRevision,
            previousPending?.countRevision,
        )
        val minimumDomain = maxRevision(
            requiredDomainRevision,
            publishedSummaryDomainRevision,
            previousPending?.domainRevision,
        )
        val minimumPolicy = maxRevision(
            requiredDomainPolicyRevision,
            publishedSummaryPolicyRevision,
            previousPending?.policyRevision,
            rememberedRevisionSignal.policyRevision,
        )
        summaryEpoch += 1L
        activeSummaryEpoch = summaryEpoch
        pendingSummaryRevision = null
        val lastGood = currentState.summary.lastGoodOrNull()
            ?.takeIf { value ->
                value.query == query &&
                    minimumDomain?.let { value.topDomainsDomainRevision >= it } != false &&
                    minimumPolicy?.let { value.topDomainsPolicyRevision >= it } != false
            }
        currentState = currentState.copy(
            summaryQuery = query,
            summary = ProductLoadState.Loading(lastGood),
        )
        return SummaryWork(
            epoch = summaryEpoch,
            query = query,
            lastGood = lastGood,
            minimumCountRevision = minimumCount,
            exactCountRevision = exactCountRevision,
            minimumDomainRevision = minimumDomain,
            exactDomainRevision = exactDomainRevision,
            minimumPolicyRevision = minimumPolicy,
            exactPolicyRevision = exactPolicyRevision,
        )
    }

    private fun beginHistoryReloadLocked(
        exactRevision: Long? = null,
        exactPolicyRevision: Long? = null,
        query: DomainHistoryQuery = currentState.historyQuery,
    ): HistoryWork {
        val previousPending = pendingDomainRevision
        val minimumRevision = maxRevision(
            requiredDomainRevision,
            observedDomainRevision,
            publishedHistoryRevision,
            previousPending?.revision,
        )
        val minimumPolicyRevision = maxRevision(
            requiredDomainPolicyRevision,
            observedDomainPolicyRevision,
            publishedHistoryPolicyRevision,
            previousPending?.policyRevision,
            rememberedRevisionSignal.policyRevision,
        )
        historyEpoch += 1L
        activeHistoryEpoch = historyEpoch
        pendingDomainRevision = null
        historyLoadMoreEpoch = null
        val lastGood = currentState.history.lastGoodOrNull()
            ?.takeIf { value ->
                value.historyQuery == query &&
                    minimumRevision?.let { value.sourceRevision >= it } != false &&
                    minimumPolicyRevision?.let {
                        (value.sourcePolicyRevision ?: -1L) >= it
                    } != false
            }
        val retainedLastGood = currentState.retainedDomainLogs.lastGoodOrNull()
            ?.takeIf {
                publishedHistoryRevision?.let { published ->
                    minimumRevision?.let { published >= it } ?: true
                } != false &&
                    publishedHistoryPolicyRevision?.let { published ->
                        minimumPolicyRevision?.let { published >= it } ?: true
                    } != false
            }
        currentState = currentState.copy(
            historyQuery = query,
            history = ProductLoadState.Loading(lastGood),
            retainedDomainLogs = ProductLoadState.Loading(retainedLastGood),
            historyLoadingMore = false,
        )
        return HistoryWork(
            epoch = historyEpoch,
            query = query,
            request = DomainHistoryPageRequest(query = query),
            lastGood = lastGood,
            retainedLastGood = retainedLastGood,
            appendTo = null,
            minimumRevision = minimumRevision,
            exactRevision = exactRevision,
            minimumPolicyRevision = minimumPolicyRevision,
            exactPolicyRevision = exactPolicyRevision,
        )
    }

    private fun beginNetworkReloadLocked(
        retainLastGood: Boolean = true,
        refreshing: Boolean = false,
    ): NetworkWork? {
        val nextContentGeneration = nextNetworkContentGeneration(
            currentState.networkContentGeneration,
        ) ?: return null
        val minimumRevision = maxRevision(
            observedNetworkRevision,
            requiredNetworkRevision,
            pendingNetworkRevision?.revision,
        )
        networkEpoch += 1L
        activeNetworkEpoch = networkEpoch
        pendingNetworkRevision = null
        networkLoadMoreEpoch = null
        val lastGood = currentState.network.lastGoodOrNull()
            .takeIf { retainLastGood }
        currentState = currentState.copy(
            network = ProductLoadState.Loading(lastGood),
            networkRefreshing = refreshing,
            networkLoadingMore = false,
            networkContentGeneration = nextContentGeneration,
            networkFirstPageLoading = true,
        )
        return NetworkWork(
            epoch = networkEpoch,
            request = NetworkActivityPageRequest(),
            lastGood = lastGood,
            appendTo = null,
            minimumRevision = minimumRevision,
        )
    }

    private suspend fun executeSummary(work: SummaryWork) {
        try {
            val attempt = readAttempt {
                port.loadSummary(
                    work.query,
                    topDomainsLimit = com.lavasec.app.product.TOP_DOMAINS_LIMIT,
                ).also { evidence -> validateSummaryEvidence(work, evidence) }
            }

            mutex.withLock {
                if (activeSummaryEpoch == work.epoch) activeSummaryEpoch = null
                if (work.epoch != summaryEpoch || work.query != currentState.summaryQuery) {
                    return@withLock
                }
                work.pendingRevision?.let { pending ->
                    if (pendingSummaryRevision != pending) return@withLock
                    pendingSummaryRevision = null
                }
                if (attempt is ReadAttempt.Read) {
                    val evidence = attempt.value
                    val domainRevision = evidence.value.topDomainsDomainRevision
                    val policyRevision = evidence.value.topDomainsPolicyRevision
                    observedSummaryRevision = maxRevision(
                        observedSummaryRevision,
                        evidence.revision,
                    )
                    requiredSummaryRevision = maxRevision(
                        requiredSummaryRevision,
                        evidence.revision,
                    )
                    publishedSummaryDomainRevision = maxRevision(
                        publishedSummaryDomainRevision,
                        domainRevision,
                    )
                    publishedSummaryPolicyRevision = maxRevision(
                        publishedSummaryPolicyRevision,
                        policyRevision,
                    )
                    val domainRequirementAdvanced =
                        requiredDomainRevision?.let { domainRevision > it } ?: (domainRevision > 0L)
                    val policyRequirementAdvanced =
                        policyRevision > (requiredDomainPolicyRevision ?: 0L)
                    requiredDomainRevision = maxRevision(requiredDomainRevision, domainRevision)
                    requiredDomainPolicyRevision = maxRevision(
                        requiredDomainPolicyRevision,
                        policyRevision,
                    )
                    val fenceHistory =
                        (domainRequirementAdvanced || policyRequirementAdvanced) &&
                            !historyCoversLocked(domainRevision, policyRevision)
                    if (fenceHistory) invalidateHistoryMetadataLocked()
                    currentState = currentState
                        .withIdentitySurfacesInvalidated(history = fenceHistory)
                        .copy(summary = attempt.toSummaryLoadState(work.lastGood))
                } else {
                    currentState = currentState.copy(
                        summary = attempt.toSummaryLoadState(work.lastGood),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            releaseCancelledSummaryReload(work)
            throw cancelled
        }
    }

    private fun validateSummaryEvidence(
        work: SummaryWork,
        evidence: Revisioned<ActivitySummaryRead>,
    ) {
        require(evidence.value.query.dateLens == work.query.dateLens) {
            "summary date lens does not match its Activity request"
        }
        require(evidence.value.query.topDomainDecision == work.query.topDomainDecision) {
            "summary Top Domains decision does not match its Activity request"
        }
        require(evidence.value.query.topDomainSearch == work.query.topDomainSearch) {
            "summary Top Domains search does not match its Activity request"
        }
        work.exactCountRevision?.let { exact ->
            require(evidence.revision == exact) {
                "summary count revision does not match its authoritative read"
            }
        }
        work.minimumCountRevision?.let { minimum ->
            require(evidence.revision >= minimum) {
                "summary count revision regressed below its required revision"
            }
        }
        work.exactDomainRevision?.let { exact ->
            require(evidence.value.topDomainsDomainRevision == exact) {
                "Top Domains Domain Logs revision does not match its authoritative read"
            }
        }
        work.minimumDomainRevision?.let { minimum ->
            require(evidence.value.topDomainsDomainRevision >= minimum) {
                "Top Domains Domain Logs revision regressed below its required revision"
            }
        }
        work.exactPolicyRevision?.let { exact ->
            require(evidence.value.topDomainsPolicyRevision == exact) {
                "Top Domains policy revision does not match its authoritative read"
            }
        }
        work.minimumPolicyRevision?.let { minimum ->
            require(evidence.value.topDomainsPolicyRevision >= minimum) {
                "Top Domains policy revision regressed below its required revision"
            }
        }
        val result = evidence.value.result
        if (result is ProductReadResult.Available) {
            require(result.value.topDomains.size <= com.lavasec.app.product.TOP_DOMAINS_LIMIT) {
                "Top Domains exceeds hard cap ${com.lavasec.app.product.TOP_DOMAINS_LIMIT}"
            }
            require(result.value.summaryCountRevision == evidence.revision) {
                "summary payload revision does not match its read evidence"
            }
        }
    }

    private suspend fun executeHistory(work: HistoryWork) {
        try {
            val attempt = readAttempt {
                port.loadDomainHistory(work.request).also { evidence ->
                    validateHistoryEvidence(work, evidence)
                }
            }

            mutex.withLock {
                if (activeHistoryEpoch == work.epoch) activeHistoryEpoch = null
                if (work.epoch != historyEpoch || work.query != currentState.historyQuery) {
                    return@withLock
                }
                if (work.appendTo != null && historyLoadMoreEpoch != work.epoch) {
                    return@withLock
                }
                if (work.appendTo != null) historyLoadMoreEpoch = null
                work.pendingRevision?.let { pending ->
                    if (pendingDomainRevision != pending) return@withLock
                    pendingDomainRevision = null
                }

                if (attempt is ReadAttempt.Read) {
                    val evidence = attempt.value
                    val policyRevision = evidence.value.policyRevision
                    val domainRequirementAdvanced =
                        requiredDomainRevision?.let { evidence.revision > it }
                            ?: (evidence.revision > 0L)
                    val policyRequirementAdvanced =
                        policyRevision > (requiredDomainPolicyRevision ?: 0L)
                    observedDomainRevision = maxRevision(
                        observedDomainRevision,
                        evidence.revision,
                    )
                    observedDomainPolicyRevision = maxRevision(
                        observedDomainPolicyRevision,
                        policyRevision,
                    )
                    requiredDomainRevision = maxRevision(
                        requiredDomainRevision,
                        evidence.revision,
                    )
                    requiredDomainPolicyRevision = maxRevision(
                        requiredDomainPolicyRevision,
                        policyRevision,
                    )
                    publishedHistoryRevision = maxRevision(
                        publishedHistoryRevision,
                        evidence.revision,
                    )
                    publishedHistoryPolicyRevision = maxRevision(
                        publishedHistoryPolicyRevision,
                        policyRevision,
                    )
                    val fenceSummary =
                        (domainRequirementAdvanced || policyRequirementAdvanced) &&
                            !summaryCoversLocked(summaryTargetLocked())
                    if (fenceSummary) invalidateSummaryMetadataLocked()
                    currentState = currentState
                        .withIdentitySurfacesInvalidated(summary = fenceSummary)
                        .copy(
                            history = attempt.toHistoryLoadState(work),
                            retainedDomainLogs = if (work.appendTo == null) {
                                attempt.toRetainedDomainLogsLoadState(work.retainedLastGood)
                            } else {
                                currentState.retainedDomainLogs
                            },
                            historyLoadingMore = false,
                        )
                } else {
                    currentState = currentState.copy(
                        history = attempt.toHistoryLoadState(work),
                        retainedDomainLogs = if (work.appendTo == null) {
                            attempt.toRetainedDomainLogsLoadState(work.retainedLastGood)
                        } else {
                            currentState.retainedDomainLogs
                        },
                        historyLoadingMore = false,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            if (work.appendTo == null) releaseCancelledHistoryReload(work)
            throw cancelled
        }
    }

    private fun validateHistoryEvidence(
        work: HistoryWork,
        evidence: Revisioned<DomainHistoryRead>,
    ) {
        require(evidence.value.query == work.query) {
            "domain history query does not match its request"
        }
        work.exactRevision?.let { exact ->
            require(evidence.revision == exact) {
                "domain history revision does not match its authoritative read"
            }
        }
        work.minimumRevision?.let { minimum ->
            require(evidence.revision >= minimum) {
                "domain history revision regressed below its required revision"
            }
        }
        work.exactPolicyRevision?.let { exact ->
            require(evidence.value.policyRevision == exact) {
                "domain history policy revision does not match its authoritative read"
            }
        }
        work.minimumPolicyRevision?.let { minimum ->
            require(evidence.value.policyRevision >= minimum) {
                "domain history policy revision regressed below its required revision"
            }
        }
        if (work.appendTo != null) {
            require(
                evidence.value.availability == DomainHistoryAvailability.RETAINED &&
                    evidence.value.result is ProductReadResult.Available,
            ) {
                "domain history load more must preserve retained availability"
            }
        }
        if (evidence.value.result is ProductReadResult.Available) {
            val page = evidence.value.result.value
            validateStablePage(page, work.request.token)
            require(page.rows.all { it.decision == work.query.decision }) {
                "domain history rows do not match the requested decision"
            }
        }
    }

    private suspend fun executeNetwork(
        work: NetworkWork,
    ): NetworkPageLoadOutcome = try {
        var responseRevision: Long? = null
        val attempt = readAttempt {
            port.loadNetworkActivity(work.request).let { evidence ->
                responseRevision = evidence.revision
                work.exactRevision?.let { exact ->
                    require(evidence.revision == exact) {
                        "network revision does not match its authoritative read"
                    }
                }
                work.minimumRevision?.let { minimum ->
                    require(evidence.revision >= minimum) {
                        "network revision regressed below its required revision"
                    }
                }
                if (evidence.value is ProductReadResult.Available) {
                    validateStablePage(evidence.value.value, work.request.token)
                    validateNetworkRowBound(evidence.value.value, work.appendTo)
                }
                evidence.value
            }
        }

        mutex.withLock {
            if (activeNetworkEpoch == work.epoch) activeNetworkEpoch = null
            if (work.epoch != networkEpoch) {
                return@withLock NetworkPageLoadOutcome.SUPERSEDED
            }
            if (work.appendTo != null && networkLoadMoreEpoch != work.epoch) {
                return@withLock NetworkPageLoadOutcome.SUPERSEDED
            }
            if (work.appendTo != null) networkLoadMoreEpoch = null
            work.revision?.let { revision ->
                if (pendingNetworkRevision != RevisionLoad(revision, work.epoch)) {
                    return@withLock NetworkPageLoadOutcome.SUPERSEDED
                }
                pendingNetworkRevision = null
            }

            if (attempt is ReadAttempt.Read) {
                val revision = requireNotNull(responseRevision)
                observedNetworkRevision = maxRevision(observedNetworkRevision, revision)
                requiredNetworkRevision = maxRevision(requiredNetworkRevision, revision)
            }

            currentState = currentState.copy(
                network = attempt.toNetworkLoadState(
                    lastGood = work.lastGood,
                    appendTo = work.appendTo,
                    sourceRevision = responseRevision,
                ),
                networkRefreshing = false,
                networkLoadingMore = false,
                networkHydrated = if (work.appendTo == null) {
                    true
                } else {
                    currentState.networkHydrated
                },
                networkFirstPageLoading = if (work.appendTo == null) {
                    false
                } else {
                    currentState.networkFirstPageLoading
                },
            )
            if (attempt is ReadAttempt.Failed) {
                NetworkPageLoadOutcome.FAILED
            } else {
                NetworkPageLoadOutcome.COMPLETED
            }
        }
    } catch (cancelled: CancellationException) {
        if (work.appendTo == null) releaseCancelledNetworkReload(work)
        throw cancelled
    }

    private suspend fun releaseCancelledSummaryReload(work: SummaryWork) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (work.epoch != summaryEpoch || work.query != currentState.summaryQuery) {
                    return@withLock
                }
                if (activeSummaryEpoch == work.epoch) activeSummaryEpoch = null
                work.pendingRevision?.let { pending ->
                    if (pendingSummaryRevision == pending) pendingSummaryRevision = null
                }
                currentState = currentState.copy(
                    summary = work.lastGood?.let { ProductLoadState.Ready(it) }
                        ?: ProductLoadState.Loading(lastGood = null),
                )
            }
        }
    }

    private suspend fun releaseCancelledHistoryReload(work: HistoryWork) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (work.epoch != historyEpoch || work.query != currentState.historyQuery) {
                    return@withLock
                }
                if (activeHistoryEpoch == work.epoch) activeHistoryEpoch = null
                work.pendingRevision?.let { pending ->
                    if (pendingDomainRevision == pending) pendingDomainRevision = null
                }
                currentState = currentState.copy(
                    history = work.lastGood?.let { ProductLoadState.Ready(it) }
                        ?: ProductLoadState.Loading(lastGood = null),
                    retainedDomainLogs = work.retainedLastGood?.let { ProductLoadState.Ready(it) }
                        ?: ProductLoadState.Loading(lastGood = null),
                    historyLoadingMore = false,
                )
            }
        }
    }

    private suspend fun releaseCancelledNetworkReload(work: NetworkWork) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (work.epoch != networkEpoch) return@withLock
                if (activeNetworkEpoch == work.epoch) activeNetworkEpoch = null
                work.revision?.let { revision ->
                    if (pendingNetworkRevision == RevisionLoad(revision, work.epoch)) {
                        pendingNetworkRevision = null
                    }
                }
                currentState = currentState.copy(
                    network = work.lastGood?.let { ProductLoadState.Ready(it) }
                        ?: ProductLoadState.Loading(lastGood = null),
                    networkRefreshing = false,
                    networkLoadingMore = false,
                    networkFirstPageLoading = false,
                )
            }
        }
    }

    private suspend fun releaseCancelledHistoryLoadMore(work: HistoryWork) {
        val base = work.appendTo ?: return
        withContext(NonCancellable) {
            mutex.withLock {
                if (work.epoch == historyEpoch && historyLoadMoreEpoch == work.epoch) {
                    if (activeHistoryEpoch == work.epoch) activeHistoryEpoch = null
                    historyLoadMoreEpoch = null
                    currentState = currentState.copy(
                        history = ProductLoadState.Ready(base),
                        historyLoadingMore = false,
                    )
                }
            }
        }
    }

    private suspend fun releaseCancelledNetworkLoadMore(work: NetworkWork) {
        val base = work.appendTo ?: return
        withContext(NonCancellable) {
            mutex.withLock {
                if (work.epoch == networkEpoch && networkLoadMoreEpoch == work.epoch) {
                    if (activeNetworkEpoch == work.epoch) activeNetworkEpoch = null
                    networkLoadMoreEpoch = null
                    currentState = currentState.copy(
                        network = ProductLoadState.Ready(base),
                        networkRefreshing = false,
                        networkLoadingMore = false,
                    )
                }
            }
        }
    }

    private suspend fun failSummaryRevision(ticket: RevisionReadTicket, failure: Throwable) {
        mutex.withLock {
            if (ticket.readEpoch <= completedSummaryRevisionEpoch) return@withLock
            completedSummaryRevisionEpoch = ticket.readEpoch
            if (
                ticket.surfaceEpoch != summaryEpoch ||
                ticket.surfaceWasActive
            ) {
                return@withLock
            }
            summaryEpoch += 1L
            pendingSummaryRevision = null
            currentState = currentState.copy(
                summary = ProductLoadState.Failed(
                    message = failure.activityMessage(),
                    lastGood = currentState.summary.lastGoodOrNull(),
                ),
            )
        }
    }

    private suspend fun failDomainRevision(
        ticket: DomainRevisionReadTicket,
        failure: Throwable,
    ) {
        mutex.withLock {
            if (ticket.readEpoch <= completedDomainRevisionEpoch) return@withLock
            completedDomainRevisionEpoch = ticket.readEpoch
            if (
                ticket.historyEpoch != historyEpoch ||
                ticket.historyWasActive
            ) {
                return@withLock
            }
            historyEpoch += 1L
            pendingDomainRevision = null
            historyLoadMoreEpoch = null
            val message = failure.activityMessage()
            currentState = currentState.copy(
                history = ProductLoadState.Failed(
                    message = message,
                    lastGood = currentState.history.lastGoodOrNull(),
                ),
                retainedDomainLogs = ProductLoadState.Failed(
                    message = message,
                    lastGood = currentState.retainedDomainLogs.lastGoodOrNull(),
                ),
                historyLoadingMore = false,
            )
        }
    }

    private suspend fun failNetworkRevision(ticket: RevisionReadTicket, failure: Throwable) {
        mutex.withLock {
            if (ticket.readEpoch <= completedNetworkRevisionEpoch) return@withLock
            completedNetworkRevisionEpoch = ticket.readEpoch
            if (
                ticket.surfaceEpoch != networkEpoch ||
                ticket.surfaceWasActive
            ) {
                return@withLock
            }
            networkEpoch += 1L
            pendingNetworkRevision = null
            networkLoadMoreEpoch = null
            currentState = currentState.copy(
                network = ProductLoadState.Failed(
                    message = failure.activityMessage(),
                    lastGood = currentState.network.lastGoodOrNull(),
                ),
                networkRefreshing = false,
                networkLoadingMore = false,
                networkHydrated = true,
                networkFirstPageLoading = false,
            )
        }
    }

    private fun summaryTargetLocked(): SummaryRevisionLoad = SummaryRevisionLoad(
        countRevision = maxRevision(requiredSummaryRevision, observedSummaryRevision) ?: 0L,
        domainRevision = maxRevision(
            requiredDomainRevision,
            publishedSummaryDomainRevision,
        ) ?: 0L,
        policyRevision = maxRevision(
            requiredDomainPolicyRevision,
            publishedSummaryPolicyRevision,
            rememberedRevisionSignal.policyRevision,
        ) ?: 0L,
        surfaceEpoch = summaryEpoch,
    )

    private fun summaryCoversLocked(target: SummaryRevisionLoad): Boolean =
        observedSummaryRevision?.let { it >= target.countRevision } == true &&
            publishedSummaryDomainRevision?.let { it >= target.domainRevision } == true &&
            publishedSummaryPolicyRevision?.let { it >= target.policyRevision } == true

    private fun historyCoversLocked(
        revision: Long,
        policyRevision: Long,
    ): Boolean =
        publishedHistoryRevision?.let { it >= revision } == true &&
            publishedHistoryPolicyRevision?.let { it >= policyRevision } == true

    private data class SummaryWork(
        val epoch: Long,
        val query: ActivitySummaryQuery,
        val lastGood: ActivitySummary?,
        val minimumCountRevision: Long?,
        val exactCountRevision: Long?,
        val minimumDomainRevision: Long?,
        val exactDomainRevision: Long?,
        val minimumPolicyRevision: Long?,
        val exactPolicyRevision: Long?,
        val pendingRevision: SummaryRevisionLoad? = null,
    )

    private data class RevisionReadTicket(
        val readEpoch: Long,
        val surfaceEpoch: Long,
        val surfaceWasActive: Boolean,
    )

    private data class DomainRevisionReadTicket(
        val readEpoch: Long,
        val historyEpoch: Long,
        val policyRevision: Long,
        val historyWasActive: Boolean,
    )

    private data class RevisionLoad(
        val revision: Long,
        val surfaceEpoch: Long,
    )

    private data class SummaryRevisionLoad(
        val countRevision: Long,
        val domainRevision: Long,
        val policyRevision: Long,
        val surfaceEpoch: Long,
    ) {
        fun covers(other: SummaryRevisionLoad): Boolean =
            countRevision >= other.countRevision &&
                domainRevision >= other.domainRevision &&
                policyRevision >= other.policyRevision
    }

    /**
     * Prevents sibling revision readers in one synchronization pass from retrying the same
     * C/D/P vector after a fast failure. A genuinely newer vector is still claimed and loaded.
     *
     * Access is serialized by [mutex].
     */
    private class RevisionSynchronization {
        private val summaryAttempts = mutableListOf<SummaryRevisionLoad>()

        fun claimSummary(target: SummaryRevisionLoad): Boolean {
            if (summaryAttempts.any { it.covers(target) }) return false
            summaryAttempts += target
            return true
        }
    }

    private data class DomainRevisionLoad(
        val revision: Long,
        val policyRevision: Long,
        val historyEpoch: Long,
    ) {
        fun covers(
            revision: Long,
            policyRevision: Long,
        ): Boolean = this.revision >= revision && this.policyRevision >= policyRevision
    }

    private data class HistoryWork(
        val epoch: Long,
        val query: DomainHistoryQuery,
        val request: DomainHistoryPageRequest,
        val lastGood: ProductPagedRows<DomainHistoryRow>?,
        val retainedLastGood: Boolean?,
        val appendTo: ProductPagedRows<DomainHistoryRow>?,
        val minimumRevision: Long?,
        val exactRevision: Long? = null,
        val minimumPolicyRevision: Long?,
        val exactPolicyRevision: Long? = null,
        val pendingRevision: DomainRevisionLoad? = null,
    )

    private data class DomainSynchronizationWork(
        val history: HistoryWork?,
        val summary: SummaryWork?,
    )

    private data class NetworkWork(
        val epoch: Long,
        val request: NetworkActivityPageRequest,
        val lastGood: ProductPagedRows<NetworkActivityRow>?,
        val appendTo: ProductPagedRows<NetworkActivityRow>?,
        val minimumRevision: Long?,
        val revision: Long? = null,
        val exactRevision: Long? = null,
    )

    private sealed interface ReadAttempt<out T> {
        data class Read<T>(val value: T) : ReadAttempt<T>

        data class Failed(val message: String) : ReadAttempt<Nothing>
    }

    private suspend fun <T> readAttempt(block: suspend () -> T): ReadAttempt<T> = try {
        ReadAttempt.Read(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        ReadAttempt.Failed(failure.activityMessage())
    }

    private fun ReadAttempt<Revisioned<ActivitySummaryRead>>.toSummaryLoadState(
        lastGood: ActivitySummary?,
    ): ProductLoadState<ActivitySummary> = when (this) {
        is ReadAttempt.Read -> when (val result = value.value.result) {
            is ProductReadResult.Available -> ProductLoadState.Ready(result.value)
            ProductReadResult.Disabled -> ProductLoadState.Disabled
        }

        is ReadAttempt.Failed -> ProductLoadState.Failed(message = message, lastGood = lastGood)
    }

    private fun ReadAttempt<Revisioned<DomainHistoryRead>>.toHistoryLoadState(
        work: HistoryWork,
    ): ProductLoadState<ProductPagedRows<DomainHistoryRow>> = when (this) {
        is ReadAttempt.Read -> when (val result = value.value.result) {
            is ProductReadResult.Available -> ProductLoadState.Ready(
                publishHistoryPage(
                    page = result.value,
                    appendTo = work.appendTo,
                    sourceRevision = value.revision,
                    sourcePolicyRevision = value.value.policyRevision,
                    historyQuery = work.query,
                ),
            )

            ProductReadResult.Disabled -> ProductLoadState.Disabled
        }

        is ReadAttempt.Failed -> ProductLoadState.Failed(
            message = message,
            lastGood = work.lastGood,
        )
    }

    private fun ReadAttempt<Revisioned<DomainHistoryRead>>.toRetainedDomainLogsLoadState(
        lastGood: Boolean?,
    ): ProductLoadState<Boolean> = when (this) {
        is ReadAttempt.Read -> when (value.value.availability) {
            DomainHistoryAvailability.DISABLED -> ProductLoadState.Disabled
            DomainHistoryAvailability.EMPTY -> ProductLoadState.Ready(false)
            DomainHistoryAvailability.RETAINED -> ProductLoadState.Ready(true)
        }

        is ReadAttempt.Failed -> ProductLoadState.Failed(message = message, lastGood = lastGood)
    }

    private fun ReadAttempt<ProductReadResult<StablePage<NetworkActivityRow>>>.toNetworkLoadState(
        lastGood: ProductPagedRows<NetworkActivityRow>?,
        appendTo: ProductPagedRows<NetworkActivityRow>?,
        sourceRevision: Long?,
    ): ProductLoadState<ProductPagedRows<NetworkActivityRow>> = when (this) {
        is ReadAttempt.Read -> when (val result = value) {
            is ProductReadResult.Available -> ProductLoadState.Ready(
                publishNetworkPage(
                    page = result.value,
                    appendTo = appendTo,
                    sourceRevision = requireNotNull(sourceRevision) {
                        "available stable pages require authoritative revision provenance"
                    },
                ),
            )

            ProductReadResult.Disabled -> ProductLoadState.Disabled
        }

        is ReadAttempt.Failed -> ProductLoadState.Failed(message = message, lastGood = lastGood)
    }

    private fun publishHistoryPage(
        page: StablePage<DomainHistoryRow>,
        appendTo: ProductPagedRows<DomainHistoryRow>?,
        sourceRevision: Long,
        sourcePolicyRevision: Long,
        historyQuery: DomainHistoryQuery,
    ): ProductPagedRows<DomainHistoryRow> = if (appendTo == null) {
        ProductPagedRows.domainHistory(
            rows = page.rows,
            next = page.next,
            cut = page.cut,
            sourceRevision = sourceRevision,
            sourcePolicyRevision = sourcePolicyRevision,
            historyQuery = historyQuery,
        )
    } else {
        require(appendTo.sourceRevision == sourceRevision) {
            "load more changed the first-page authoritative revision"
        }
        require(appendTo.sourcePolicyRevision == sourcePolicyRevision) {
            "load more changed the first-page policy revision"
        }
        require(appendTo.historyQuery == historyQuery) {
            "load more changed the first-page query provenance"
        }
        val seen = appendTo.rows.mapTo(mutableSetOf()) { it.id }
        ProductPagedRows.domainHistory(
            rows = appendTo.rows + page.rows.filter { seen.add(it.id) },
            next = page.next,
            cut = appendTo.cut,
            sourceRevision = appendTo.sourceRevision,
            sourcePolicyRevision = sourcePolicyRevision,
            historyQuery = historyQuery,
        )
    }

    private fun publishNetworkPage(
        page: StablePage<NetworkActivityRow>,
        appendTo: ProductPagedRows<NetworkActivityRow>?,
        sourceRevision: Long,
    ): ProductPagedRows<NetworkActivityRow> = if (appendTo == null) {
        ProductPagedRows.networkActivity(
            rows = page.rows,
            next = page.next,
            cut = page.cut,
            sourceRevision = sourceRevision,
        )
    } else {
        require(appendTo.sourceRevision == sourceRevision) {
            "load more changed the first-page authoritative revision"
        }
        val seen = appendTo.rows.mapTo(mutableSetOf()) { it.id }
        ProductPagedRows.networkActivity(
            rows = appendTo.rows + page.rows.filter { seen.add(it.id) },
            next = page.next,
            cut = appendTo.cut,
            sourceRevision = appendTo.sourceRevision,
        )
    }

    private fun validateNetworkRowBound(
        page: StablePage<NetworkActivityRow>,
        appendTo: ProductPagedRows<NetworkActivityRow>?,
    ) {
        val retainedIds = appendTo?.rows?.mapTo(mutableSetOf()) { it.id } ?: mutableSetOf()
        val nextRowCount = retainedIds.size + page.rows.count { retainedIds.add(it.id) }
        require(nextRowCount <= NetworkActivityContract.MAX_ROWS) {
            "network activity cannot publish more than ${NetworkActivityContract.MAX_ROWS} rows"
        }
    }

    private fun <T : StableActivityRow> validateStablePage(
        page: StablePage<T>,
        expectedToken: PageToken?,
    ) {
        require(page.rows.size <= LOCAL_LOG_PAGE_SIZE) {
            "stable page exposes more than $LOCAL_LOG_PAGE_SIZE rows"
        }
        require(expectedToken == null || page.cut == expectedToken.cut) {
            "load more changed the frozen read cut"
        }
        require(page.rows.map { it.id }.distinct().size == page.rows.size) {
            "one stable page contains duplicate row ids"
        }
        require(page.rows.all { it.id <= page.cut.maxRowId }) {
            "stable page contains a row newer than its frozen read cut"
        }
        expectedToken?.let { token ->
            val beforeExclusive = requireNotNull(token.beforeExclusive) {
                "load more requires an exclusive cursor"
            }
            require(page.rows.all { row ->
                row.timestampMillis < beforeExclusive.timestampMillis ||
                    (
                        row.timestampMillis == beforeExclusive.timestampMillis &&
                            row.id < beforeExclusive.id
                    )
            }) {
                "load-more rows must be strictly older than the exclusive cursor"
            }
        }
        require(page.rows.zipWithNext().all { (newer, older) ->
            newer.timestampMillis > older.timestampMillis ||
                (newer.timestampMillis == older.timestampMillis && newer.id > older.id)
        }) {
            "stable page must be ordered by timestamp and id descending"
        }

        page.next?.let { next ->
            require(page.rows.size == LOCAL_LOG_PAGE_SIZE) {
                "a next-page sentinel requires a full visible page"
            }
            val oldestVisible = page.rows.last()
            require(next.beforeExclusive == StableKey(oldestVisible.timestampMillis, oldestVisible.id)) {
                "next page must continue after the oldest visible row"
            }
        }
    }

    private fun Long.validRevision(): Long = also { require(it >= 0L) }

    private fun maxRevision(
        vararg revisions: Long?,
    ): Long? = revisions.filterNotNull().maxOrNull()

    private fun Throwable.activityMessage(): String =
        message ?: this::class.simpleName ?: "Activity read failed"

    private fun <T : StableActivityRow> ProductLoadState<ProductPagedRows<T>>
        .pageAvailableForLoadMore(): ProductPagedRows<T>? = when (this) {
        is ProductLoadState.Ready -> value
        is ProductLoadState.Failed -> lastGood
        is ProductLoadState.Loading,
        ProductLoadState.Disabled,
        -> null
    }
}
