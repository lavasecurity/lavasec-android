package com.lavasec.app.product.activity

import com.lavasec.app.foundation.activity.NetworkActivityCapturedState
import com.lavasec.app.foundation.activity.NetworkActivityConnectivityState
import com.lavasec.app.foundation.activity.NetworkActivityEntry
import com.lavasec.app.foundation.activity.NetworkActivityEvent
import com.lavasec.app.foundation.activity.NetworkActivityNetworkKind
import com.lavasec.app.foundation.activity.NetworkActivityProtectionState
import com.lavasec.app.foundation.activity.NetworkActivityResolverIdentity
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.product.LOCAL_LOG_PAGE_SIZE
import com.lavasec.app.product.PageToken
import com.lavasec.app.product.Revisioned
import com.lavasec.app.product.StableKey
import com.lavasec.app.product.StablePage
import com.lavasec.app.product.StableReadCut
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProductActivityStateMachineTest {
    @Test
    fun `network content generation reaches max once then fails closed`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val stateField = ProductActivityStateMachine::class.java
            .getDeclaredField("currentState")
            .apply { isAccessible = true }
        val current = stateField.get(machine) as ProductActivityState
        stateField.set(
            machine,
            current.copy(networkContentGeneration = Long.MAX_VALUE - 1L),
        )

        machine.refreshNetwork()

        assertEquals(Long.MAX_VALUE, machine.state.networkContentGeneration)
        val requestsAtMaximum = port.networkRequests.size
        machine.refreshNetwork()

        assertEquals(Long.MAX_VALUE, machine.state.networkContentGeneration)
        assertEquals(requestsAtMaximum, port.networkRequests.size)
    }

    @Test
    fun `domain revision publishes history and availability from one envelope atomically`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)

        machine.synchronizeRevisions()

        val initial = ready(machine.state.history)
        assertEquals(1L, initial.sourceRevision)
        assertEquals(0L, initial.sourcePolicyRevision)
        assertEquals(true, ready(machine.state.retainedDomainLogs))
        assertEquals(1, port.historyRequests.size)

        port.domainRevision = 2L
        port.historyLoader = { available(domainPage(emptyList(), hasMore = false)) }
        port.historyAvailability = { _, _ -> DomainHistoryAvailability.EMPTY }
        machine.synchronizeRevisions()

        val replacement = ready(machine.state.history)
        assertEquals(2L, replacement.sourceRevision)
        assertEquals(0L, replacement.sourcePolicyRevision)
        assertTrue(replacement.rows.isEmpty())
        assertEquals(false, ready(machine.state.retainedDomainLogs))
        assertEquals(2, port.historyRequests.size)
    }

    @Test
    fun `history query and paging carry one retained envelope with exact policy provenance`() = runTest {
        val port = FakeActivityPort().apply {
            historyLoader = { request ->
                if (request.token == null) {
                    available(
                        domainPage(
                            100L downTo 71L,
                            hasMore = true,
                            decision = request.query.decision,
                        ),
                    )
                } else {
                    available(
                        domainPage(
                            70L downTo 41L,
                            hasMore = false,
                            decision = request.query.decision,
                        ),
                    )
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        machine.setHistoryDecision(DomainDecision.ALLOWED)
        machine.setHistorySearch("independent")
        assertTrue(machine.loadMoreHistory())

        val published = ready(machine.state.history)
        assertEquals(60, published.rows.size)
        assertEquals(1L, published.sourceRevision)
        assertEquals(0L, published.sourcePolicyRevision)
        assertEquals(DomainHistoryQuery(DomainDecision.ALLOWED, "independent"), published.historyQuery)
        assertEquals(true, ready(machine.state.retainedDomainLogs))
        assertEquals(4, port.historyRequests.size)
    }

    @Test
    fun `one history envelope failure fails history and availability atomically`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val historyLastGood = ready(machine.state.history)

        port.historyLoader = { error("history envelope unavailable") }
        machine.refreshHistory()

        val historyFailure = machine.state.history as ProductLoadState.Failed
        val availabilityFailure = machine.state.retainedDomainLogs as ProductLoadState.Failed
        assertEquals("history envelope unavailable", historyFailure.message)
        assertEquals(historyLastGood, historyFailure.lastGood)
        assertEquals("history envelope unavailable", availabilityFailure.message)
        assertEquals(true, availabilityFailure.lastGood)
    }

    @Test
    fun `newer history query stales the entire older history envelope`() = runTest {
        val olderEntered = CompletableDeferred<Unit>()
        val older = CompletableDeferred<ProductReadResult<StablePage<DomainHistoryRow>>>()
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.domainRevision = 2L
        port.historyLoader = { request ->
            if (request.query.search.isEmpty()) {
                olderEntered.complete(Unit)
                older.await()
            } else {
                available(domainPage(listOf(28L), hasMore = false))
            }
        }

        val revision = launch { machine.synchronizeRevisions() }
        olderEntered.await()
        assertTrue(machine.setHistorySearch("new-query"))
        older.complete(available(domainPage(listOf(29L), hasMore = false)))
        revision.join()

        val published = ready(machine.state.history)
        assertEquals(listOf(28L), published.rows.map { it.id })
        assertEquals("new-query", published.historyQuery?.search)
        assertEquals(true, ready(machine.state.retainedDomainLogs))
    }

    @Test
    fun `history envelope never emits terminal history with stale availability`() = runTest {
        val pending = CompletableDeferred<ProductReadResult<StablePage<DomainHistoryRow>>>()
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.historyLoader = { pending.await() }
        port.historyAvailability = { _, _ -> DomainHistoryAvailability.EMPTY }
        val emissions = mutableListOf<ProductActivityState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.stateFlow.collect { emissions += it }
        }
        runCurrent()
        val beforeRefresh = emissions.size

        val refresh = launch { machine.refreshHistory() }
        runCurrent()

        val replacementEmissions = emissions.drop(beforeRefresh)
        assertTrue(
            replacementEmissions.any {
                it.history is ProductLoadState.Loading &&
                    it.retainedDomainLogs is ProductLoadState.Loading
            },
        )
        assertFalse(
            replacementEmissions.any {
                it.history is ProductLoadState.Ready &&
                    it.retainedDomainLogs == ProductLoadState.Ready(true)
            },
        )
        pending.complete(available(domainPage(emptyList(), hasMore = false)))
        refresh.join()

        assertTrue(ready(machine.state.history).rows.isEmpty())
        assertEquals(false, ready(machine.state.retainedDomainLogs))
        collector.cancelAndJoin()
    }

    @Test
    fun `domain revision failure emits both failed surfaces atomically`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val emissions = mutableListOf<ProductActivityState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.stateFlow.collect { emissions += it }
        }
        runCurrent()
        val beforeFailure = emissions.size
        port.domainRevisionLoader = { error("domain revision unavailable") }

        machine.synchronizeRevisions()

        val failureEmissions = emissions.drop(beforeFailure)
        assertTrue(
            failureEmissions.any {
                it.history is ProductLoadState.Failed &&
                    it.retainedDomainLogs is ProductLoadState.Failed
            },
        )
        assertFalse(
            failureEmissions.any {
                it.history is ProductLoadState.Failed &&
                    it.retainedDomainLogs == ProductLoadState.Ready(true)
            },
        )
        collector.cancelAndJoin()
    }

    @Test
    fun `post-read history envelope cancellation stays fail closed and retries exact vector`() =
        runTest {
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            port.domainRevision = 2L
            val typedReadReady = CompletableDeferred<Unit>()
            val releaseTypedRead = CompletableDeferred<Unit>()
            port.historyLoader = {
                typedReadReady.complete(Unit)
                releaseTypedRead.await()
                currentCoroutineContext()[Job]!!.cancel(
                    CancellationException("cancel after typed history envelope"),
                )
                available(domainPage(listOf(29L), hasMore = false))
            }

            val synchronization = launch { machine.synchronizeRevisions() }
            typedReadReady.await()
            val machineMutex = privateMutex(machine)
            machineMutex.lock()
            try {
                releaseTypedRead.complete(Unit)
                runCurrent()
            } finally {
                machineMutex.unlock()
            }
            synchronization.join()

            assertEquals(ProductLoadState.Loading<ProductPagedRows<DomainHistoryRow>>(null), machine.state.history)
            assertEquals(ProductLoadState.Loading<Boolean>(null), machine.state.retainedDomainLogs)
            port.historyLoader = { available(domainPage(listOf(28L), hasMore = false)) }
            machine.synchronizeRevisions()

            assertEquals(listOf(28L), ready(machine.state.history).rows.map { it.id })
            assertEquals(2L, ready(machine.state.history).sourceRevision)
            assertEquals(3, port.historyRequests.size)
        }

    @Test
    fun `post-read summary cancellation restores last good and retries same revision`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val lastGood = ready(machine.state.summary)
        port.summaryRevision = 2L
        val typedReadReady = CompletableDeferred<Unit>()
        val releaseTypedRead = CompletableDeferred<Unit>()
        port.summaryLoader = { _, _ ->
            typedReadReady.complete(Unit)
            releaseTypedRead.await()
            currentCoroutineContext()[Job]!!.cancel(
                CancellationException("cancel after typed summary read"),
            )
            available(
                summary(
                    topDomains = listOf(TopDomainRow("cancelled.example", 2L)),
                    summaryCountRevision = 2L,
                ),
            )
        }

        val synchronization = launch { machine.synchronizeRevisions() }
        typedReadReady.await()
        val machineMutex = privateMutex(machine)
        machineMutex.lock()
        try {
            releaseTypedRead.complete(Unit)
            runCurrent()
        } finally {
            machineMutex.unlock()
        }
        synchronization.join()

        assertEquals(lastGood, ready(machine.state.summary))
        port.summaryLoader = { _, _ ->
            available(
                summary(
                    topDomains = listOf(TopDomainRow("retried.example", 2L)),
                    summaryCountRevision = 2L,
                ),
            )
        }
        machine.synchronizeRevisions()

        assertEquals("retried.example", ready(machine.state.summary).topDomains.single().domain)
        assertEquals(3, port.summaryRequests.size)
    }

    @Test
    fun `post-read first network page cancellation restores last good and retries same revision`() =
        runTest {
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            val lastGood = ready(machine.state.network)
            port.networkRevisionValue = 2L
            val typedReadReady = CompletableDeferred<Unit>()
            val releaseTypedRead = CompletableDeferred<Unit>()
            port.networkLoader = {
                typedReadReady.complete(Unit)
                releaseTypedRead.await()
                currentCoroutineContext()[Job]!!.cancel(
                    CancellationException("cancel after typed network read"),
                )
                available(networkPage(listOf(19L), hasMore = false))
            }

            val synchronization = launch { machine.synchronizeRevisions() }
            typedReadReady.await()
            val machineMutex = privateMutex(machine)
            machineMutex.lock()
            try {
                releaseTypedRead.complete(Unit)
                runCurrent()
            } finally {
                machineMutex.unlock()
            }
            synchronization.join()

            assertEquals(lastGood, ready(machine.state.network))
            assertFalse(machine.state.networkFirstPageLoading)
            port.networkLoader = { available(networkPage(listOf(18L), hasMore = false)) }
            machine.synchronizeRevisions()

            assertEquals(listOf(18L), ready(machine.state.network).rows.map { it.id })
            assertEquals(3, port.networkRequests.size)
        }

    @Test
    fun `history availability maps disabled empty and retained filtered empty exactly`() = runTest {
        val disabledPort = FakeActivityPort().apply {
            historyLoader = { ProductReadResult.Disabled }
            summaryLoader = { query, _ ->
                available(
                    summary(
                        topDomains = emptyList(),
                        dateLens = query.dateLens,
                        topDomainsDecision = query.topDomainDecision,
                        topDomainsSearch = query.topDomainSearch,
                        topDomainsAvailability = DomainHistoryAvailability.DISABLED,
                    ),
                )
            }
        }
        val disabled = ProductActivityStateMachine(disabledPort, TODAY)
        disabled.synchronizeRevisions()
        assertEquals(ProductLoadState.Disabled, disabled.state.history)
        assertEquals(ProductLoadState.Disabled, disabled.state.retainedDomainLogs)

        val emptyPort = FakeActivityPort().apply {
            historyLoader = { available(domainPage(emptyList(), hasMore = false)) }
            historyAvailability = { _, _ -> DomainHistoryAvailability.EMPTY }
            summaryLoader = { query, _ ->
                available(
                    summary(
                        topDomains = emptyList(),
                        dateLens = query.dateLens,
                        topDomainsDecision = query.topDomainDecision,
                        topDomainsSearch = query.topDomainSearch,
                        topDomainsAvailability = DomainHistoryAvailability.EMPTY,
                    ),
                )
            }
        }
        val empty = ProductActivityStateMachine(emptyPort, TODAY)
        empty.synchronizeRevisions()
        assertTrue(ready(empty.state.history).rows.isEmpty())
        assertEquals(false, ready(empty.state.retainedDomainLogs))

        val retainedPort = FakeActivityPort().apply {
            historyLoader = { available(domainPage(emptyList(), hasMore = false)) }
            historyAvailability = { _, _ -> DomainHistoryAvailability.RETAINED }
        }
        val retained = ProductActivityStateMachine(retainedPort, TODAY)
        retained.synchronizeRevisions()
        assertTrue(ready(retained.state.history).rows.isEmpty())
        assertEquals(true, ready(retained.state.retainedDomainLogs))
    }

    @Test
    fun `default product port history envelope can honestly disable both surfaces`() = runTest {
        val port = object : ProductActivityPort {
            override suspend fun summaryCountRevision(): Long = 1L
            override suspend fun domainLogRevision(): Long = 1L
            override suspend fun networkRevision(): Long = 1L
            override suspend fun loadSummary(
                query: ActivitySummaryQuery,
                topDomainsLimit: Int,
            ): Revisioned<ActivitySummaryRead> = Revisioned(
                revision = 1L,
                value = ActivitySummaryRead(
                    query = query,
                    topDomainsDomainRevision = 1L,
                    topDomainsPolicyRevision = 0L,
                    topDomainsAvailability = DomainHistoryAvailability.DISABLED,
                    result = available(
                        summary(
                            topDomains = emptyList(),
                            topDomainsAvailability = DomainHistoryAvailability.DISABLED,
                        ),
                    ),
                ),
            )

            override suspend fun loadDomainHistory(
                request: DomainHistoryPageRequest,
            ): Revisioned<DomainHistoryRead> = Revisioned(
                revision = 1L,
                value = DomainHistoryRead(
                    query = request.query,
                    policyRevision = 0L,
                    availability = DomainHistoryAvailability.DISABLED,
                    result = ProductReadResult.Disabled,
                ),
            )

            override suspend fun loadNetworkActivity(
                request: NetworkActivityPageRequest,
            ): Revisioned<ProductReadResult<StablePage<NetworkActivityRow>>> =
                Revisioned(
                    revision = 1L,
                    value = available(networkPage(listOf(20L), hasMore = false)),
                )
        }
        val machine = ProductActivityStateMachine(port, TODAY)

        machine.synchronizeRevisions()

        assertEquals(ProductLoadState.Disabled, machine.state.history)
        assertEquals(ProductLoadState.Disabled, machine.state.retainedDomainLogs)
    }

    @Test
    fun `policy-only advance fails closed then reloads both identity surfaces`() = runTest {
        val history = CompletableDeferred<ProductReadResult<StablePage<DomainHistoryRow>>>()
        val summaryRead = CompletableDeferred<ProductReadResult<ActivitySummary>>()
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val networkRequests = port.networkRequests.size
        val emissions = mutableListOf<ProductActivityState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.stateFlow.collect { emissions += it }
        }
        runCurrent()
        val beforePolicyAdvance = emissions.size
        port.policyRevision = 1L
        port.historyLoader = { history.await() }
        port.summaryLoader = { _, _ -> summaryRead.await() }

        val synchronization = launch {
            machine.synchronizeRevisions(ProductActivityRevisionSignal(token = 2L, policyRevision = 1L))
        }
        runCurrent()

        assertEquals(ProductLoadState.Loading<ActivitySummary>(null), machine.state.summary)
        assertEquals(ProductLoadState.Loading<ProductPagedRows<DomainHistoryRow>>(null), machine.state.history)
        assertEquals(ProductLoadState.Loading<Boolean>(null), machine.state.retainedDomainLogs)
        val policyTransition = emissions.drop(beforePolicyAdvance)
        assertTrue(
            policyTransition.any {
                it.summary == ProductLoadState.Loading<ActivitySummary>(null) &&
                    it.history == ProductLoadState.Loading<ProductPagedRows<DomainHistoryRow>>(null)
            },
        )
        assertFalse(
            policyTransition.any {
                it.summary.lastGoodOrNull()?.topDomainsPolicyRevision == 0L ||
                    it.history.lastGoodOrNull()?.sourcePolicyRevision == 0L
            },
        )
        history.complete(available(domainPage(listOf(29L), hasMore = false)))
        summaryRead.complete(
            available(
                summary(
                    topDomains = listOf(TopDomainRow("policy-one.example", 1L)),
                    topDomainsPolicyRevision = 1L,
                ),
            ),
        )
        synchronization.join()

        assertEquals(1L, ready(machine.state.summary).topDomainsPolicyRevision)
        assertEquals(1L, ready(machine.state.history).sourcePolicyRevision)
        assertEquals(true, ready(machine.state.retainedDomainLogs))
        assertEquals(networkRequests, port.networkRequests.size)
        collector.cancelAndJoin()
    }

    @Test
    fun `policy signal fences in flight history without a publication and exact replacement wins`() =
        runTest {
            val staleHistory =
                CompletableDeferred<ProductReadResult<StablePage<DomainHistoryRow>>>()
            val historyEntered = CompletableDeferred<Unit>()
            var historyLoads = 0
            val port = FakeActivityPort().apply {
                historyLoader = { request ->
                    historyLoads += 1
                    if (historyLoads == 1) {
                        historyEntered.complete(Unit)
                        staleHistory.await()
                    } else {
                        available(
                            domainPage(
                                ids = listOf(91L),
                                hasMore = false,
                                decision = request.query.decision,
                            ),
                        )
                    }
                }
            }
            val machine = ProductActivityStateMachine(port, TODAY)

            val initialSynchronization = launch { machine.synchronizeRevisions() }
            historyEntered.await()
            assertEquals(1, port.historyRequests.size)

            port.policyRevision = 1L
            machine.synchronizeRevisions(
                ProductActivityRevisionSignal(token = 2L, policyRevision = 1L),
            )

            assertEquals(2, port.historyRequests.size)
            assertEquals(listOf(91L), ready(machine.state.history).rows.map { it.id })
            assertEquals(1L, ready(machine.state.history).sourcePolicyRevision)

            staleHistory.complete(available(domainPage(listOf(90L), hasMore = false)))
            initialSynchronization.join()

            assertEquals(listOf(91L), ready(machine.state.history).rows.map { it.id })
            assertEquals(1L, ready(machine.state.history).sourcePolicyRevision)
        }

    @Test
    fun `policy signal fences in flight summary without a publication and exact replacement wins`() =
        runTest {
            val staleSummary = CompletableDeferred<ProductReadResult<ActivitySummary>>()
            val summaryEntered = CompletableDeferred<Unit>()
            val signalRevision = CompletableDeferred<Long>()
            var summaryLoads = 0
            val port = FakeActivityPort().apply {
                summaryLoader = { query, _ ->
                    summaryLoads += 1
                    if (summaryLoads <= 2) {
                        if (summaryLoads == 2) summaryEntered.complete(Unit)
                        staleSummary.await()
                    } else {
                        available(
                            summary(
                                topDomains = listOf(TopDomainRow("policy-one.example", 1L)),
                                dateLens = query.dateLens,
                                topDomainsDecision = query.topDomainDecision,
                                topDomainsSearch = query.topDomainSearch,
                                topDomainsPolicyRevision = 1L,
                            ),
                        )
                    }
                }
            }
            val machine = ProductActivityStateMachine(port, TODAY)

            val initialSynchronization = launch { machine.synchronizeRevisions() }
            summaryEntered.await()
            assertEquals(2, port.summaryRequests.size)

            port.policyRevision = 1L
            port.summaryRevisionLoader = { signalRevision.await() }
            val policySynchronization = launch {
                machine.synchronizeRevisions(
                    ProductActivityRevisionSignal(token = 2L, policyRevision = 1L),
                )
            }
            runCurrent()

            assertEquals(3, port.summaryRequests.size)
            assertEquals(
                "policy-one.example",
                ready(machine.state.summary).topDomains.single().domain,
            )
            assertEquals(1L, ready(machine.state.summary).topDomainsPolicyRevision)

            staleSummary.complete(available(summary(topDomainsPolicyRevision = 0L)))
            initialSynchronization.join()
            assertEquals(
                "policy-one.example",
                ready(machine.state.summary).topDomains.single().domain,
            )
            assertEquals(1L, ready(machine.state.summary).topDomainsPolicyRevision)

            signalRevision.complete(1L)
            policySynchronization.join()
            assertEquals(3, port.summaryRequests.size)
        }

    @Test
    fun `failed policy-only advance is sticky and retried by no-argument synchronization`() =
        runTest {
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            port.policyRevision = 1L
            port.historyLoader = { error("policy reload unavailable") }

            machine.synchronizeRevisions(
                ProductActivityRevisionSignal(token = 2L, policyRevision = 1L),
            )

            assertTrue(machine.state.history is ProductLoadState.Failed)
            port.historyLoader = { available(domainPage(listOf(28L), hasMore = false)) }
            machine.synchronizeRevisions()

            assertEquals(listOf(28L), ready(machine.state.history).rows.map { it.id })
            assertEquals(1L, ready(machine.state.history).sourcePolicyRevision)
            assertEquals(3, port.historyRequests.size)
        }

    @Test
    fun `later stale policy signal cannot erase the accepted policy vector`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.policyRevision = 2L
        machine.synchronizeRevisions(ProductActivityRevisionSignal(token = 3L, policyRevision = 2L))
        val summaryRequests = port.summaryRequests.size
        val historyRequests = port.historyRequests.size

        machine.synchronizeRevisions(ProductActivityRevisionSignal(token = 4L, policyRevision = 1L))

        assertEquals(summaryRequests, port.summaryRequests.size)
        assertEquals(historyRequests, port.historyRequests.size)
        assertEquals(2L, ready(machine.state.summary).topDomainsPolicyRevision)
        assertEquals(2L, ready(machine.state.history).sourcePolicyRevision)
    }

    @Test
    fun `first revision sync loads independently typed sections at pinned bounds`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)

        assertTrue(machine.state.summary is ProductLoadState.Loading)
        assertTrue(machine.state.history is ProductLoadState.Loading)
        assertTrue(machine.state.network is ProductLoadState.Loading)

        machine.synchronizeRevisions()

        assertEquals(listOf(20), port.summaryLimits)
        assertEquals(31, port.historyRequests.single().fetchLimit)
        assertEquals(31, port.networkRequests.single().fetchLimit)
        assertTrue(machine.state.summary is ProductLoadState.Ready)
        assertTrue(machine.state.history is ProductLoadState.Ready)
        assertTrue(machine.state.network is ProductLoadState.Ready)
    }

    @Test
    fun `date lens reloads summary and top domains without touching either rolling log`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val history = machine.state.history
        val network = machine.state.network

        machine.setDateLens(testLens(startDayMillis = 0L, endDayMillis = 86_400_000L))

        assertEquals(2, port.summaryRequests.size)
        assertEquals(1, port.historyRequests.size)
        assertEquals(1, port.networkRequests.size)
        assertEquals(history, machine.state.history)
        assertEquals(network, machine.state.network)
    }

    @Test
    fun `top domain controls reload only the capped summary surface`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        machine.setTopDomainSearch("  tracker  ")
        machine.setTopDomainDecision(DomainDecision.ALLOWED)

        assertEquals(listOf(20, 20, 20), port.summaryLimits)
        assertEquals("tracker", port.summaryRequests.last().topDomainSearch)
        assertEquals(DomainDecision.ALLOWED, port.summaryRequests.last().topDomainDecision)
        assertEquals(1, port.historyRequests.size)
        assertEquals(1, port.networkRequests.size)
    }

    @Test
    fun `history search filter revision and explicit refresh reset history only`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        machine.setHistorySearch("  ads  ")
        machine.setHistoryDecision(DomainDecision.ALLOWED)
        port.domainRevision = 2L
        machine.synchronizeRevisions()
        machine.refreshHistory()

        assertEquals(5, port.historyRequests.size)
        assertTrue(port.historyRequests.all { it.token == null })
        assertEquals("ads", port.historyRequests.last().query.search)
        assertEquals(DomainDecision.ALLOWED, port.historyRequests.last().query.decision)
        assertEquals(2, port.summaryRequests.size)
        assertEquals(1, port.networkRequests.size)
    }

    @Test
    fun `C reloads summary D reloads both identity surfaces and N reloads network`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        port.summaryRevision = 2L
        machine.synchronizeRevisions()
        assertEquals(2, port.summaryRequests.size)
        assertEquals(1, port.historyRequests.size)
        assertEquals(1, port.networkRequests.size)

        port.domainRevision = 2L
        machine.synchronizeRevisions()
        assertEquals(3, port.summaryRequests.size)
        assertEquals(2, port.historyRequests.size)
        assertEquals(1, port.networkRequests.size)

        port.networkRevisionValue = 2L
        machine.synchronizeRevisions()
        assertEquals(3, port.summaryRequests.size)
        assertEquals(2, port.historyRequests.size)
        assertEquals(2, port.networkRequests.size)
    }

    @Test
    fun `cancelled summary revision load restores last good and retries the same revision`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val revisionOne = ready(machine.state.summary)

        port.summaryRevision = 2L
        port.summaryLoader = { _, _ -> awaitCancellation() }
        val cancelled = launch { machine.synchronizeRevisions() }
        runCurrent()
        assertEquals(revisionOne, (machine.state.summary as ProductLoadState.Loading).lastGood)

        cancelled.cancelAndJoin()
        assertEquals(revisionOne, ready(machine.state.summary))
        port.summaryLoader = { _, _ ->
            available(
                summary(
                    topDomains = listOf(TopDomainRow("revision-two.example", 2L)),
                    summaryCountRevision = 2L,
                ),
            )
        }

        machine.synchronizeRevisions()

        assertEquals(3, port.summaryRequests.size)
        assertEquals("revision-two.example", ready(machine.state.summary).topDomains.single().domain)
    }

    @Test
    fun `cancelled history revision load stays fail closed and retries the same vector`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.domainRevision = 2L
        port.historyLoader = { awaitCancellation() }
        val cancelled = launch { machine.synchronizeRevisions() }
        runCurrent()
        assertNull((machine.state.history as ProductLoadState.Loading).lastGood)
        assertNull((machine.state.retainedDomainLogs as ProductLoadState.Loading).lastGood)

        cancelled.cancelAndJoin()
        assertEquals(ProductLoadState.Loading<ProductPagedRows<DomainHistoryRow>>(null), machine.state.history)
        assertEquals(ProductLoadState.Loading<Boolean>(null), machine.state.retainedDomainLogs)
        port.historyLoader = { available(domainPage(listOf(29L), hasMore = false)) }

        machine.synchronizeRevisions()

        assertEquals(3, port.historyRequests.size)
        assertEquals(listOf(29L), ready(machine.state.history).rows.map { it.id })
        assertEquals(2L, ready(machine.state.history).sourceRevision)
    }

    @Test
    fun `cancelled summary query change emits no stale pairing and same vector sync retries`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            val emissions = mutableListOf<ProductActivityState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                machine.stateFlow.collect { emissions += it }
            }
            runCurrent()
            val beforeQueryChange = emissions.size
            port.summaryLoader = { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }

            val cancelled = launch { machine.setTopDomainSearch("fresh") }
            entered.await()
            runCurrent()

            val transition = emissions.drop(beforeQueryChange)
            assertFalse(
                transition.any { state ->
                    val published = state.summary.lastGoodOrNull()
                    state.summaryQuery.topDomainSearch == "fresh" &&
                        published != null &&
                        published.query != state.summaryQuery
                },
            )
            assertTrue(
                transition.any { state ->
                    state.summaryQuery.topDomainSearch == "fresh" &&
                        state.summary == ProductLoadState.Loading<ActivitySummary>(null)
                },
            )
            cancelled.cancelAndJoin()
            assertEquals(ProductLoadState.Loading<ActivitySummary>(null), machine.state.summary)
            port.summaryLoader = { query, _ ->
                available(
                    summary(
                        topDomains = listOf(TopDomainRow("fresh.example", 1L)),
                        dateLens = query.dateLens,
                        topDomainsDecision = query.topDomainDecision,
                        topDomainsSearch = query.topDomainSearch,
                    ),
                )
            }

            machine.synchronizeRevisions()

            assertEquals(3, port.summaryRequests.size)
            assertEquals("fresh", ready(machine.state.summary).topDomainsSearch)
            assertEquals("fresh.example", ready(machine.state.summary).topDomains.single().domain)
            collector.cancelAndJoin()
        }

    @Test
    fun `cancelled history query change emits no stale pairing and same vector sync retries`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            val emissions = mutableListOf<ProductActivityState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                machine.stateFlow.collect { emissions += it }
            }
            runCurrent()
            val beforeQueryChange = emissions.size
            port.historyLoader = {
                entered.complete(Unit)
                awaitCancellation()
            }

            val cancelled = launch { machine.setHistorySearch("fresh") }
            entered.await()
            runCurrent()

            val transition = emissions.drop(beforeQueryChange)
            assertFalse(
                transition.any { state ->
                    val published = state.history.lastGoodOrNull()
                    state.historyQuery.search == "fresh" &&
                        published != null &&
                        published.historyQuery != state.historyQuery
                },
            )
            assertTrue(
                transition.any { state ->
                    state.historyQuery.search == "fresh" &&
                        state.history ==
                        ProductLoadState.Loading<ProductPagedRows<DomainHistoryRow>>(null)
                },
            )
            cancelled.cancelAndJoin()
            assertEquals(
                ProductLoadState.Loading<ProductPagedRows<DomainHistoryRow>>(null),
                machine.state.history,
            )
            port.historyLoader = { request ->
                available(
                    domainPage(
                        ids = listOf(29L),
                        hasMore = false,
                        decision = request.query.decision,
                    ),
                )
            }

            machine.synchronizeRevisions()

            assertEquals(3, port.historyRequests.size)
            assertEquals(
                DomainHistoryQuery(search = "fresh"),
                ready(machine.state.history).historyQuery,
            )
            assertEquals(listOf(29L), ready(machine.state.history).rows.map { it.id })
            collector.cancelAndJoin()
        }

    @Test
    fun `cancelled network revision load restores last good and retries the same revision`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val revisionOne = ready(machine.state.network)

        port.networkRevisionValue = 2L
        port.networkLoader = { awaitCancellation() }
        val cancelled = launch { machine.synchronizeRevisions() }
        runCurrent()
        assertEquals(revisionOne, (machine.state.network as ProductLoadState.Loading).lastGood)

        cancelled.cancelAndJoin()
        assertEquals(revisionOne, ready(machine.state.network))
        port.networkLoader = { available(networkPage(listOf(19L), hasMore = false)) }

        machine.synchronizeRevisions()

        assertEquals(3, port.networkRequests.size)
        assertEquals(listOf(19L), ready(machine.state.network).rows.map { it.id })
    }

    @Test
    fun `failed summary revision load retries the same revision`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        port.summaryRevision = 2L
        port.summaryLoader = { _, _ -> error("summary transient") }
        machine.synchronizeRevisions()
        assertTrue(machine.state.summary is ProductLoadState.Failed)
        port.summaryLoader = { _, _ ->
            available(
                summary(
                    topDomains = listOf(TopDomainRow("summary-retry.example", 2L)),
                    summaryCountRevision = 2L,
                ),
            )
        }

        machine.synchronizeRevisions()

        assertEquals(3, port.summaryRequests.size)
        assertEquals("summary-retry.example", ready(machine.state.summary).topDomains.single().domain)
    }

    @Test
    fun `summary rejects Top Domains from a different decision query`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.summaryLoader = { _, _ ->
            available(
                summary(
                    summaryCountRevision = 1L,
                    topDomainsDecision = DomainDecision.BLOCKED,
                ),
            )
        }

        assertTrue(machine.setTopDomainDecision(DomainDecision.ALLOWED))

        val failure = machine.state.summary as ProductLoadState.Failed
        assertTrue(failure.message.contains("decision"))
        assertNull(failure.lastGood)
        port.summaryLoader = { query, _ ->
            available(
                summary(
                    summaryCountRevision = 1L,
                    dateLens = query.dateLens,
                    topDomainsDecision = query.topDomainDecision,
                    topDomainsSearch = query.topDomainSearch,
                ),
            )
        }
        machine.refreshSummary()

        assertEquals(DomainDecision.ALLOWED, ready(machine.state.summary).topDomainsDecision)
    }

    @Test
    fun `summary rejects a different search provenance without retaining the prior query`() =
        runTest {
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            port.summaryLoader = { _, _ ->
                available(
                    summary(
                        topDomains = listOf(TopDomainRow("wrong-search.example", 1L)),
                        topDomainsSearch = "",
                    ),
                )
            }

            assertTrue(machine.setTopDomainSearch("tracker"))

            val failure = machine.state.summary as ProductLoadState.Failed
            assertTrue(failure.message.contains("search"))
            assertNull(failure.lastGood)
            port.summaryLoader = { query, _ ->
                available(
                    summary(
                        topDomains = listOf(TopDomainRow("tracker.example", 2L)),
                        dateLens = query.dateLens,
                        topDomainsDecision = query.topDomainDecision,
                        topDomainsSearch = query.topDomainSearch,
                    ),
                )
            }

            machine.refreshSummary()

            assertEquals("tracker", ready(machine.state.summary).topDomainsSearch)
        }

    @Test
    fun `summary rejects a different date lens provenance`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val newLens = testLens(startDayMillis = 0L, endDayMillis = 0L)
        port.summaryLoader = { query, _ ->
            available(
                summary(
                    dateLens = TODAY,
                    topDomainsDecision = query.topDomainDecision,
                    topDomainsSearch = query.topDomainSearch,
                ),
            )
        }

        assertTrue(machine.setDateLens(newLens))

        val failure = machine.state.summary as ProductLoadState.Failed
        assertTrue(failure.message.contains("date lens"))
        assertNull(failure.lastGood)
    }

    @Test
    fun `disabled summary rejects stale revision evidence and retries the same floor`() = runTest {
        val port = FakeActivityPort().apply { summaryRevision = 2L }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.summary)
        port.summaryLoader = { _, _ -> ProductReadResult.Disabled }
        port.summaryReadRevision = { _, _ -> 1L }

        machine.refreshSummary()

        val failure = machine.state.summary as ProductLoadState.Failed
        assertTrue(failure.message.contains("revision"))
        assertEquals(confirmed, failure.lastGood)
        port.summaryReadRevision = { _, _ -> 2L }
        machine.refreshSummary()

        assertEquals(ProductLoadState.Disabled, machine.state.summary)
    }

    @Test
    fun `disabled summary carries exact query and revision evidence`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.summaryLoader = { _, _ -> ProductReadResult.Disabled }
        port.summaryReadQuery = { query -> query.copy(topDomainSearch = "wrong") }

        machine.refreshSummary()

        val queryFailure = machine.state.summary as ProductLoadState.Failed
        assertTrue(queryFailure.message.contains("search"))
        assertTrue(queryFailure.lastGood != null)
        port.summaryReadQuery = { it }
        port.summaryRevision = 2L
        machine.synchronizeRevisions()
        val requestsAfterRevision = port.summaryRequests.size
        machine.synchronizeRevisions()

        assertEquals(ProductLoadState.Disabled, machine.state.summary)
        assertEquals(requestsAfterRevision, port.summaryRequests.size)
    }

    @Test
    fun `disabled summary cannot publish below the sticky Domain Logs policy vector`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.domainRevisionLoader = { 2L }
        port.summaryLoader = { _, _ -> ProductReadResult.Disabled }

        machine.synchronizeRevisions(
            ProductActivityRevisionSignal(token = 2L, policyRevision = 1L),
        )

        val failure = machine.state.summary as ProductLoadState.Failed
        assertTrue(failure.message.contains("revision"))
        assertNull(failure.lastGood)

        port.domainRevision = 2L
        port.policyRevision = 1L
        machine.synchronizeRevisions()

        assertEquals(ProductLoadState.Disabled, machine.state.summary)
    }

    @Test
    fun `summary revision mismatch fails and retries the same authoritative revision`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.summary)
        port.summaryRevision = 2L
        port.summaryLoader = { query, _ ->
            available(
                summary(
                    summaryCountRevision = 1L,
                    topDomainsDecision = query.topDomainDecision,
                ),
            )
        }

        machine.synchronizeRevisions()

        val failure = machine.state.summary as ProductLoadState.Failed
        assertTrue(failure.message.contains("revision"))
        assertEquals(confirmed, failure.lastGood)
        assertEquals(2, port.summaryRequests.size)
        port.summaryLoader = { query, _ ->
            available(
                summary(
                    topDomains = listOf(TopDomainRow("revision-two.example", 2L)),
                    summaryCountRevision = 2L,
                    topDomainsDecision = query.topDomainDecision,
                ),
            )
        }
        machine.synchronizeRevisions()

        assertEquals(3, port.summaryRequests.size)
        assertEquals(2L, ready(machine.state.summary).summaryCountRevision)
        assertEquals("revision-two.example", ready(machine.state.summary).topDomains.single().domain)
    }

    @Test
    fun `summary rejects stale Top Domains D with exact C and retries the required vector`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.domainRevision = 2L
        port.summaryLoader = { query, _ ->
            available(
                summary(
                    summaryCountRevision = 1L,
                    dateLens = query.dateLens,
                    topDomainsDecision = query.topDomainDecision,
                    topDomainsSearch = query.topDomainSearch,
                    topDomainsDomainRevision = 1L,
                ),
            )
        }

        machine.synchronizeRevisions()

        val failure = machine.state.summary as ProductLoadState.Failed
        assertTrue(failure.message.contains("Domain Logs revision"))
        assertNull(failure.lastGood)
        port.summaryLoader = { query, _ ->
            available(
                summary(
                    summaryCountRevision = 1L,
                    dateLens = query.dateLens,
                    topDomainsDecision = query.topDomainDecision,
                    topDomainsSearch = query.topDomainSearch,
                    topDomainsDomainRevision = 2L,
                ),
            )
        }
        machine.synchronizeRevisions()

        assertEquals(2L, ready(machine.state.summary).topDomainsDomainRevision)
    }

    @Test
    fun `summary rejects stale Top Domains P with exact C D and retries the required vector`() =
        runTest {
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            port.policyRevision = 1L
            port.summaryLoader = { query, _ ->
                available(
                    summary(
                        summaryCountRevision = 1L,
                        dateLens = query.dateLens,
                        topDomainsDecision = query.topDomainDecision,
                        topDomainsSearch = query.topDomainSearch,
                        topDomainsDomainRevision = 1L,
                        topDomainsPolicyRevision = 0L,
                    ),
                )
            }

            machine.synchronizeRevisions(
                ProductActivityRevisionSignal(token = 2L, policyRevision = 1L),
            )

            val failure = machine.state.summary as ProductLoadState.Failed
            assertTrue(failure.message.contains("policy revision"))
            assertNull(failure.lastGood)
            port.summaryLoader = { query, _ ->
                available(
                    summary(
                        summaryCountRevision = 1L,
                        dateLens = query.dateLens,
                        topDomainsDecision = query.topDomainDecision,
                        topDomainsSearch = query.topDomainSearch,
                        topDomainsDomainRevision = 1L,
                        topDomainsPolicyRevision = 1L,
                    ),
                )
            }
            machine.synchronizeRevisions()

            assertEquals(1L, ready(machine.state.summary).topDomainsPolicyRevision)
        }

    @Test
    fun `manual summary query cannot erase a pending C watermark`() = runTest {
        val pendingRevision = CompletableDeferred<ProductReadResult<ActivitySummary>>()
        val revisionEntered = CompletableDeferred<Unit>()
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        var load = 0
        port.summaryRevision = 2L
        port.summaryLoader = { query, _ ->
            when (++load) {
                1 -> {
                    revisionEntered.complete(Unit)
                    pendingRevision.await()
                }

                2 -> available(
                    summary(
                        topDomains = listOf(TopDomainRow("stale.example", 1L)),
                        summaryCountRevision = 1L,
                        dateLens = query.dateLens,
                        topDomainsDecision = query.topDomainDecision,
                        topDomainsSearch = query.topDomainSearch,
                    ),
                )

                else -> available(
                    summary(
                        topDomains = listOf(TopDomainRow("current.example", 2L)),
                        summaryCountRevision = 2L,
                        dateLens = query.dateLens,
                        topDomainsDecision = query.topDomainDecision,
                        topDomainsSearch = query.topDomainSearch,
                    ),
                )
            }
        }

        val revision = launch { machine.synchronizeRevisions() }
        revisionEntered.await()
        assertTrue(machine.setTopDomainSearch("fresh"))
        val staleFailure = machine.state.summary as ProductLoadState.Failed
        assertTrue(staleFailure.message.contains("revision"))
        pendingRevision.complete(
            available(summary(summaryCountRevision = 2L)),
        )
        revision.join()

        machine.synchronizeRevisions()

        val published = ready(machine.state.summary)
        assertEquals(2L, published.summaryCountRevision)
        assertEquals("fresh", published.topDomainsSearch)
        assertEquals("current.example", published.topDomains.single().domain)
    }

    @Test
    fun `manual summary query cannot erase sticky D and P during an in flight refresh`() =
        runTest {
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            port.domainRevision = 2L
            port.policyRevision = 1L
            machine.synchronizeRevisions(
                ProductActivityRevisionSignal(token = 2L, policyRevision = 1L),
            )

            val pendingRefresh = CompletableDeferred<ProductReadResult<ActivitySummary>>()
            val refreshEntered = CompletableDeferred<Unit>()
            var loads = 0
            port.summaryLoader = { query, _ ->
                when (++loads) {
                    1 -> {
                        refreshEntered.complete(Unit)
                        pendingRefresh.await()
                    }

                    2 -> available(
                        summary(
                            topDomains = listOf(TopDomainRow("stale.example", 1L)),
                            dateLens = query.dateLens,
                            topDomainsDecision = query.topDomainDecision,
                            topDomainsSearch = query.topDomainSearch,
                            topDomainsDomainRevision = 1L,
                            topDomainsPolicyRevision = 0L,
                        ),
                    )

                    else -> available(
                        summary(
                            topDomains = listOf(TopDomainRow("current.example", 2L)),
                            dateLens = query.dateLens,
                            topDomainsDecision = query.topDomainDecision,
                            topDomainsSearch = query.topDomainSearch,
                            topDomainsDomainRevision = 2L,
                            topDomainsPolicyRevision = 1L,
                        ),
                    )
                }
            }

            val refresh = launch { machine.refreshSummary() }
            refreshEntered.await()
            assertTrue(machine.setTopDomainSearch("fresh"))

            val staleFailure = machine.state.summary as ProductLoadState.Failed
            assertTrue(staleFailure.message.contains("revision"))
            assertNull(staleFailure.lastGood)
            pendingRefresh.complete(
                available(
                    summary(
                        topDomainsDomainRevision = 2L,
                        topDomainsPolicyRevision = 1L,
                    ),
                ),
            )
            refresh.join()

            machine.refreshSummary()

            val published = ready(machine.state.summary)
            assertEquals(2L, published.topDomainsDomainRevision)
            assertEquals(1L, published.topDomainsPolicyRevision)
            assertEquals("fresh", published.topDomainsSearch)
            assertEquals("current.example", published.topDomains.single().domain)
        }

    @Test
    fun `manual summary refresh cannot publish below the observed revision and can retry that floor`() =
        runTest {
            val port = FakeActivityPort().apply { summaryRevision = 2L }
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            val confirmed = ready(machine.state.summary)

            port.summaryLoader = { query, _ ->
                available(
                    summary(
                        topDomains = listOf(TopDomainRow("stale.example", 1L)),
                        summaryCountRevision = 1L,
                        topDomainsDecision = query.topDomainDecision,
                    ),
                )
            }
            machine.refreshSummary()

            val failure = machine.state.summary as ProductLoadState.Failed
            assertTrue(failure.message.contains("revision"))
            assertEquals(confirmed, failure.lastGood)

            port.summaryLoader = { query, _ ->
                available(
                    summary(
                        topDomains = listOf(TopDomainRow("same-floor.example", 2L)),
                        summaryCountRevision = 2L,
                        topDomainsDecision = query.topDomainDecision,
                    ),
                )
            }
            machine.refreshSummary()

            assertEquals(2L, ready(machine.state.summary).summaryCountRevision)
            assertEquals(
                "same-floor.example",
                ready(machine.state.summary).topDomains.single().domain,
            )
        }

    @Test
    fun `manual summary refresh advances the observed revision floor`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        port.summaryLoader = { query, _ ->
            available(
                summary(
                    topDomains = listOf(TopDomainRow("newer.example", 3L)),
                    summaryCountRevision = 2L,
                    topDomainsDecision = query.topDomainDecision,
                ),
            )
        }
        machine.refreshSummary()
        val requestsAfterRefresh = port.summaryRequests.size
        port.summaryRevision = 2L

        machine.synchronizeRevisions()

        assertEquals(requestsAfterRefresh, port.summaryRequests.size)
        assertEquals(2L, ready(machine.state.summary).summaryCountRevision)
    }

    @Test
    fun `failed history revision load retries the same revision`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        port.domainRevision = 2L
        port.historyLoader = { error("history transient") }
        machine.synchronizeRevisions()
        assertTrue(machine.state.history is ProductLoadState.Failed)
        port.historyLoader = { available(domainPage(listOf(28L), hasMore = false)) }

        machine.synchronizeRevisions()

        assertEquals(3, port.historyRequests.size)
        assertEquals(listOf(28L), ready(machine.state.history).rows.map { it.id })
    }

    @Test
    fun `failed network revision load retries the same revision`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        port.networkRevisionValue = 2L
        port.networkLoader = { error("network transient") }
        machine.synchronizeRevisions()
        assertTrue(machine.state.network is ProductLoadState.Failed)
        port.networkLoader = { available(networkPage(listOf(18L), hasMore = false)) }

        machine.synchronizeRevisions()

        assertEquals(3, port.networkRequests.size)
        assertEquals(listOf(18L), ready(machine.state.network).rows.map { it.id })
    }

    @Test
    fun `a slow summary revision does not hold back independent log revisions`() = runTest {
        val pendingSummary = CompletableDeferred<ProductReadResult<ActivitySummary>>()
        val port = FakeActivityPort().apply {
            summaryLoader = { _, _ -> pendingSummary.await() }
        }
        val machine = ProductActivityStateMachine(port, TODAY)

        val sync = async { machine.synchronizeRevisions() }
        runCurrent()

        // The initial C-only read is superseded by the exact D/P identity read while it is
        // still in flight. Both remain independent of History and Network publication.
        assertEquals(2, port.summaryRequests.size)
        assertEquals(1, port.historyRequests.size)
        assertEquals(1, port.networkRequests.size)
        pendingSummary.complete(available(summary()))
        sync.await()
    }

    @Test
    fun `a lower stale revision cannot roll a surface backward`() = runTest {
        val port = FakeActivityPort().apply { summaryRevision = 2L }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = machine.state.summary

        port.summaryRevision = 1L
        machine.synchronizeRevisions()

        assertEquals(1, port.summaryRequests.size)
        assertEquals(confirmed, machine.state.summary)
    }

    @Test
    fun `an older revision read finishing late cannot overwrite a newer read`() = runTest {
        val older = CompletableDeferred<Long>()
        var revisionReads = 0
        val port = FakeActivityPort().apply {
            summaryRevisionLoader = {
                revisionReads += 1
                if (revisionReads == 1) older.await() else 2L
            }
            summaryLoader = { query, _ ->
                available(
                    summary(
                        summaryCountRevision = 2L,
                        topDomainsDecision = query.topDomainDecision,
                    ),
                )
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)

        val first = async { machine.synchronizeRevisions() }
        runCurrent()
        val second = async { machine.synchronizeRevisions() }
        runCurrent()
        second.await()
        older.complete(1L)
        first.await()

        assertEquals(1, port.summaryRequests.size)
        assertTrue(machine.state.summary is ProductLoadState.Ready)
    }

    @Test
    fun `cancelled newer summary revision read cannot suppress an older success`() = runTest {
        val older = CompletableDeferred<Long>()
        val newerEntered = CompletableDeferred<Unit>()
        var revisionReads = 0
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.summaryRevision = 2L
        port.summaryRevisionLoader = {
            revisionReads += 1
            if (revisionReads == 1) {
                older.await()
            } else {
                newerEntered.complete(Unit)
                awaitCancellation()
            }
        }
        port.summaryLoader = { query, _ ->
            available(
                summary(
                    topDomains = listOf(TopDomainRow("older-success.example", 2L)),
                    summaryCountRevision = 2L,
                    dateLens = query.dateLens,
                    topDomainsDecision = query.topDomainDecision,
                    topDomainsSearch = query.topDomainSearch,
                ),
            )
        }

        val first = launch { machine.synchronizeRevisions() }
        runCurrent()
        val cancelled = launch { machine.synchronizeRevisions() }
        newerEntered.await()
        older.complete(2L)
        first.join()
        cancelled.cancelAndJoin()

        assertEquals(2L, ready(machine.state.summary).summaryCountRevision)
        assertEquals(
            "older-success.example",
            ready(machine.state.summary).topDomains.single().domain,
        )
    }

    @Test
    fun `cancelled newer domain revision read cannot strand policy fenced history`() = runTest {
        val older = CompletableDeferred<Long>()
        val newerEntered = CompletableDeferred<Unit>()
        var revisionReads = 0
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.domainRevision = 2L
        port.policyRevision = 1L
        port.domainRevisionLoader = {
            revisionReads += 1
            if (revisionReads == 1) {
                older.await()
            } else {
                newerEntered.complete(Unit)
                awaitCancellation()
            }
        }
        port.historyLoader = { available(domainPage(listOf(29L), hasMore = false)) }
        port.summaryLoader = { query, _ ->
            available(
                summary(
                    dateLens = query.dateLens,
                    topDomainsDecision = query.topDomainDecision,
                    topDomainsSearch = query.topDomainSearch,
                    topDomainsDomainRevision = 2L,
                    topDomainsPolicyRevision = 1L,
                ),
            )
        }

        val first = launch { machine.synchronizeRevisions() }
        runCurrent()
        val cancelled = launch {
            machine.synchronizeRevisions(
                ProductActivityRevisionSignal(token = 2L, policyRevision = 1L),
            )
        }
        newerEntered.await()
        runCurrent()
        assertEquals(
            ProductLoadState.Loading<ProductPagedRows<DomainHistoryRow>>(null),
            machine.state.history,
        )
        older.complete(2L)
        first.join()
        cancelled.cancelAndJoin()

        assertEquals(listOf(29L), ready(machine.state.history).rows.map { it.id })
        assertEquals(2L, ready(machine.state.history).sourceRevision)
        assertEquals(1L, ready(machine.state.history).sourcePolicyRevision)
    }

    @Test
    fun `cancelled newer network revision read cannot suppress an older success`() = runTest {
        val older = CompletableDeferred<Long>()
        val newerEntered = CompletableDeferred<Unit>()
        var revisionReads = 0
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.networkRevisionValue = 2L
        port.networkRevisionLoader = {
            revisionReads += 1
            if (revisionReads == 1) {
                older.await()
            } else {
                newerEntered.complete(Unit)
                awaitCancellation()
            }
        }
        port.networkLoader = { available(networkPage(listOf(19L), hasMore = false)) }

        val first = launch { machine.synchronizeRevisions() }
        runCurrent()
        val cancelled = launch { machine.synchronizeRevisions() }
        newerEntered.await()
        older.complete(2L)
        first.join()
        cancelled.cancelAndJoin()

        assertEquals(listOf(19L), ready(machine.state.network).rows.map { it.id })
        assertEquals(2L, ready(machine.state.network).sourceRevision)
    }

    @Test
    fun `a late revision read failure cannot overwrite a newer explicit refresh`() = runTest {
        val pendingRevision = CompletableDeferred<Long>()
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.summaryRevisionLoader = { pendingRevision.await() }
        port.summaryLoader = { _, _ ->
            available(summary(topDomains = listOf(TopDomainRow("refreshed.example", 1L))))
        }

        val staleSync = async { machine.synchronizeRevisions() }
        runCurrent()
        machine.refreshSummary()
        pendingRevision.completeExceptionally(IllegalStateException("revision unavailable"))
        staleSync.await()

        assertEquals("refreshed.example", ready(machine.state.summary).topDomains.single().domain)
    }

    @Test
    fun `revision read failure cannot invalidate an already active summary refresh`() = runTest {
        val pendingSummary = CompletableDeferred<ProductReadResult<ActivitySummary>>()
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.summaryLoader = { _, _ -> pendingSummary.await() }

        val refresh = async { machine.refreshSummary() }
        runCurrent()
        port.summaryRevisionLoader = { error("summary revision unavailable") }
        machine.synchronizeRevisions()
        pendingSummary.complete(
            available(
                summary(topDomains = listOf(TopDomainRow("active-refresh.example", 1L))),
            ),
        )
        refresh.await()

        assertEquals(
            "active-refresh.example",
            ready(machine.state.summary).topDomains.single().domain,
        )
    }

    @Test
    fun `revision read failure cannot invalidate an already active network refresh`() = runTest {
        val pendingNetwork = CompletableDeferred<ProductReadResult<StablePage<NetworkActivityRow>>>()
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.networkLoader = { pendingNetwork.await() }

        val refresh = async { machine.refreshNetwork() }
        runCurrent()
        port.networkRevisionValue = -1L
        machine.synchronizeRevisions()
        pendingNetwork.complete(available(networkPage(listOf(19L), hasMore = false)))
        refresh.await()

        assertEquals(listOf(19L), ready(machine.state.network).rows.map { it.id })
        assertFalse(machine.state.networkRefreshing)
    }

    @Test
    fun `domain revision read failure cannot invalidate an already active history envelope refresh`() =
        runTest {
            val pendingHistory =
                CompletableDeferred<ProductReadResult<StablePage<DomainHistoryRow>>>()
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            port.historyLoader = { pendingHistory.await() }
            port.historyAvailability = { _, _ -> DomainHistoryAvailability.EMPTY }

            val refresh = async { machine.refreshHistory() }
            runCurrent()
            port.domainRevisionLoader = { error("domain revision unavailable") }
            machine.synchronizeRevisions()
            pendingHistory.complete(available(domainPage(emptyList(), hasMore = false)))
            refresh.await()

            assertTrue(ready(machine.state.history).rows.isEmpty())
            assertEquals(false, ready(machine.state.retainedDomainLogs))
        }

    @Test
    fun `mismatched Domain History query evidence is rejected with last good rows`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.history)
        port.historyReadQuery = { request ->
            request.query.copy(search = "wrong")
        }

        machine.refreshHistory()

        val failure = machine.state.history as ProductLoadState.Failed
        assertTrue(failure.message.contains("query"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `Domain History rows that violate the requested decision are rejected`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.history)
        port.historyLoader = {
            available(
                stablePage(
                    rows = listOf(
                        DomainHistoryRow(
                            id = 18L,
                            timestampMillis = 180L,
                            domain = "allowed.example",
                            decision = DomainDecision.ALLOWED,
                            reason = FilterDecisionReason.DEFAULT_ALLOW,
                        ),
                    ),
                    hasMore = false,
                ),
            )
        }

        machine.refreshHistory()

        val failure = machine.state.history as ProductLoadState.Failed
        assertTrue(failure.message.contains("decision"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `stale domain evidence fails without observation and retries the same revision`() =
        runTest {
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            port.domainRevision = 2L
            port.historyLoader = { available(domainPage(listOf(17L), hasMore = false)) }
            port.historyReadRevision = { 1L }

            machine.synchronizeRevisions()

            val failure = machine.state.history as ProductLoadState.Failed
            assertTrue(failure.message.contains("authoritative"))
            port.historyReadRevision = { port.domainRevision }
            machine.synchronizeRevisions()

            assertEquals(listOf(17L), ready(machine.state.history).rows.map { it.id })
            assertEquals(3, port.historyRequests.size)
        }

    @Test
    fun `stale history policy evidence fails both surfaces and retries the remembered policy`() =
        runTest {
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            port.policyRevision = 1L
            port.historyReadPolicyRevision = { 0L }

            machine.synchronizeRevisions(
                ProductActivityRevisionSignal(token = 2L, policyRevision = 1L),
            )

            val historyFailure = machine.state.history as ProductLoadState.Failed
            val availabilityFailure = machine.state.retainedDomainLogs as ProductLoadState.Failed
            assertTrue(historyFailure.message.contains("policy revision"))
            assertTrue(availabilityFailure.message.contains("policy revision"))
            assertNull(historyFailure.lastGood)
            assertNull(availabilityFailure.lastGood)
            port.historyReadPolicyRevision = { port.policyRevision }
            machine.synchronizeRevisions()

            assertEquals(true, ready(machine.state.retainedDomainLogs))
            assertEquals(1L, ready(machine.state.history).sourcePolicyRevision)
            assertEquals(3, port.historyRequests.size)
        }

    @Test
    fun `standalone newer history evidence retains its complete domain policy vector`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val emissions = mutableListOf<ProductActivityState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.stateFlow.collect { emissions += it }
        }
        runCurrent()
        val beforeNewerEvidence = emissions.size
        port.historyReadRevision = { 2L }
        port.historyReadPolicyRevision = { 1L }

        assertTrue(machine.setHistorySearch("newer"))

        val evidenceTransition = emissions.drop(beforeNewerEvidence)
        assertFalse(
            evidenceTransition.any {
                it.history.lastGoodOrNull()?.sourcePolicyRevision == 1L &&
                    it.summary.lastGoodOrNull()?.topDomainsPolicyRevision == 0L
            },
        )
        assertTrue(
            evidenceTransition.any {
                it.history.lastGoodOrNull()?.sourcePolicyRevision == 1L &&
                    it.summary == ProductLoadState.Loading<ActivitySummary>(null)
            },
        )
        assertEquals(2L, ready(machine.state.history).sourceRevision)
        assertEquals(1L, ready(machine.state.history).sourcePolicyRevision)
        assertEquals(DomainHistoryQuery(search = "newer"), ready(machine.state.history).historyQuery)
        assertEquals(true, ready(machine.state.retainedDomainLogs))
        assertEquals(ProductLoadState.Loading<ActivitySummary>(null), machine.state.summary)

        machine.synchronizeRevisions()

        val staleSummary = machine.state.summary as ProductLoadState.Failed
        assertTrue(staleSummary.message.contains("revision"))
        assertNull(staleSummary.lastGood)
        port.domainRevision = 2L
        port.policyRevision = 1L
        machine.synchronizeRevisions()

        assertEquals(2L, ready(machine.state.summary).topDomainsDomainRevision)
        assertEquals(1L, ready(machine.state.summary).topDomainsPolicyRevision)
        collector.cancelAndJoin()
    }

    @Test
    fun `standalone newer summary evidence retains its complete domain policy vector`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val emissions = mutableListOf<ProductActivityState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.stateFlow.collect { emissions += it }
        }
        runCurrent()
        val beforeNewerEvidence = emissions.size
        port.summaryLoader = { query, _ ->
            available(
                summary(
                    topDomains = listOf(TopDomainRow("newer.example", 2L)),
                    dateLens = query.dateLens,
                    topDomainsDecision = query.topDomainDecision,
                    topDomainsSearch = query.topDomainSearch,
                    topDomainsDomainRevision = 2L,
                    topDomainsPolicyRevision = 1L,
                ),
            )
        }

        assertTrue(machine.setTopDomainSearch("newer"))

        val evidenceTransition = emissions.drop(beforeNewerEvidence)
        assertFalse(
            evidenceTransition.any {
                it.summary.lastGoodOrNull()?.topDomainsPolicyRevision == 1L &&
                    it.history.lastGoodOrNull()?.sourcePolicyRevision == 0L
            },
        )
        assertTrue(
            evidenceTransition.any {
                it.summary.lastGoodOrNull()?.topDomainsPolicyRevision == 1L &&
                    it.history == ProductLoadState.Loading<ProductPagedRows<DomainHistoryRow>>(null)
            },
        )
        assertEquals(2L, ready(machine.state.summary).topDomainsDomainRevision)
        assertEquals(1L, ready(machine.state.summary).topDomainsPolicyRevision)
        assertEquals(
            ProductLoadState.Loading<ProductPagedRows<DomainHistoryRow>>(null),
            machine.state.history,
        )
        assertEquals(ProductLoadState.Loading<Boolean>(null), machine.state.retainedDomainLogs)

        port.domainRevision = 2L
        port.policyRevision = 1L
        machine.synchronizeRevisions()

        assertEquals(2L, ready(machine.state.history).sourceRevision)
        assertEquals(1L, ready(machine.state.history).sourcePolicyRevision)
        assertEquals(true, ready(machine.state.retainedDomainLogs))
        collector.cancelAndJoin()
    }

    @Test
    fun `history query cannot erase pending D and P watermarks`() = runTest {
        val pendingRevision = CompletableDeferred<ProductReadResult<StablePage<DomainHistoryRow>>>()
        val revisionEntered = CompletableDeferred<Unit>()
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.domainRevision = 2L
        port.policyRevision = 1L
        port.historyReadRevision = {
            if (port.historyRequests.size == 3) 1L else 2L
        }
        port.historyReadPolicyRevision = {
            if (port.historyRequests.size == 3) 0L else 1L
        }
        port.historyLoader = { request ->
            when (port.historyRequests.size) {
                2 -> {
                    revisionEntered.complete(Unit)
                    pendingRevision.await()
                }

                3 -> available(domainPage(listOf(18L), hasMore = false))
                else -> available(domainPage(listOf(17L), hasMore = false))
            }
        }

        val revision = launch {
            machine.synchronizeRevisions(ProductActivityRevisionSignal(token = 2L, policyRevision = 1L))
        }
        revisionEntered.await()
        assertTrue(machine.setHistorySearch("fresh"))
        val staleHistory = machine.state.history as ProductLoadState.Failed
        val staleAvailability = machine.state.retainedDomainLogs as ProductLoadState.Failed
        assertTrue(staleHistory.message.contains("revision"))
        assertTrue(staleAvailability.message.contains("revision"))
        assertNull(staleHistory.lastGood)
        assertNull(staleAvailability.lastGood)
        pendingRevision.complete(available(domainPage(listOf(19L), hasMore = false)))
        revision.join()

        machine.synchronizeRevisions()

        val published = ready(machine.state.history)
        assertEquals(listOf(17L), published.rows.map { it.id })
        assertEquals(2L, published.sourceRevision)
        assertEquals(1L, published.sourcePolicyRevision)
        assertEquals("fresh", published.historyQuery?.search)
    }

    @Test
    fun `stale network evidence fails without observation and retries the same revision`() =
        runTest {
            val port = FakeActivityPort()
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()
            port.networkRevisionValue = 2L
            port.networkLoader = { available(networkPage(listOf(16L), hasMore = false)) }
            port.networkReadRevision = { 1L }

            machine.synchronizeRevisions()

            val failure = machine.state.network as ProductLoadState.Failed
            assertTrue(failure.message.contains("authoritative"))
            port.networkReadRevision = { port.networkRevisionValue }
            machine.synchronizeRevisions()

            assertEquals(listOf(16L), ready(machine.state.network).rows.map { it.id })
            assertEquals(3, port.networkRequests.size)
        }

    @Test
    fun `manual network refresh cannot erase a pending N watermark`() = runTest {
        val pendingRevision = CompletableDeferred<ProductReadResult<StablePage<NetworkActivityRow>>>()
        val revisionEntered = CompletableDeferred<Unit>()
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        port.networkRevisionValue = 2L
        port.networkReadRevision = {
            if (port.networkRequests.size == 3) 1L else 2L
        }
        port.networkLoader = {
            when (port.networkRequests.size) {
                2 -> {
                    revisionEntered.complete(Unit)
                    pendingRevision.await()
                }

                3 -> available(networkPage(listOf(18L), hasMore = false))
                else -> available(networkPage(listOf(17L), hasMore = false))
            }
        }

        val revision = launch { machine.synchronizeRevisions() }
        revisionEntered.await()
        machine.refreshNetwork()
        val staleFailure = machine.state.network as ProductLoadState.Failed
        assertTrue(staleFailure.message.contains("revision"))
        pendingRevision.complete(available(networkPage(listOf(19L), hasMore = false)))
        revision.join()

        machine.synchronizeRevisions()

        assertEquals(listOf(17L), ready(machine.state.network).rows.map { it.id })
        assertEquals(2L, ready(machine.state.network).sourceRevision)
    }

    @Test
    fun `Domain History load more cannot change first page revision provenance`() = runTest {
        val port = FakeActivityPort().apply {
            historyLoader = { request ->
                if (request.token == null) {
                    available(domainPage(100L downTo 71L, hasMore = true))
                } else {
                    available(domainPage(70L downTo 41L, hasMore = false))
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.history)
        port.historyReadRevision = { request -> if (request.token == null) 1L else 2L }

        assertTrue(machine.loadMoreHistory())

        val failure = machine.state.history as ProductLoadState.Failed
        assertTrue(failure.message.contains("authoritative"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `Domain History load more cannot change first page policy provenance`() = runTest {
        val port = FakeActivityPort().apply {
            historyLoader = { request ->
                if (request.token == null) {
                    available(domainPage(100L downTo 71L, hasMore = true))
                } else {
                    available(domainPage(70L downTo 41L, hasMore = false))
                }
            }
            historyReadPolicyRevision = { request -> if (request.token == null) 0L else 1L }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.history)
        assertEquals(0L, confirmed.sourcePolicyRevision)

        assertTrue(machine.loadMoreHistory())

        val failure = machine.state.history as ProductLoadState.Failed
        assertTrue(failure.message.contains("policy revision"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `network load more cannot change first page revision provenance`() = runTest {
        val port = FakeActivityPort().apply {
            networkLoader = { request ->
                if (request.token == null) {
                    available(networkPage(100L downTo 71L, hasMore = true))
                } else {
                    available(networkPage(70L downTo 41L, hasMore = false))
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.network)
        port.networkReadRevision = { request -> if (request.token == null) 1L else 2L }

        assertEquals(NetworkPageLoadOutcome.FAILED, machine.loadMoreNetwork())

        val failure = machine.state.network as ProductLoadState.Failed
        assertTrue(failure.message.contains("authoritative"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `explicit network refresh resets only network activity`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        machine.refreshNetwork()

        assertEquals(1, port.summaryRequests.size)
        assertEquals(1, port.historyRequests.size)
        assertEquals(2, port.networkRequests.size)
        assertTrue(port.networkRequests.all { it.token == null })
    }

    @Test
    fun `destructive network reload failure cannot retain predecessor rows`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        assertTrue(ready(machine.state.network).rows.isNotEmpty())
        port.networkLoader = { error("post-clear reload failed") }

        machine.reloadNetworkAfterClear()

        val failed = machine.state.network as ProductLoadState.Failed
        assertEquals(null, failed.lastGood)
        assertTrue(failed.message.contains("post-clear reload failed"))
        assertEquals(1, port.summaryRequests.size)
        assertEquals(1, port.historyRequests.size)
    }

    @Test
    fun `destructive network reload supersedes an in flight predecessor refresh`() = runTest {
        val stalePredecessor =
            CompletableDeferred<ProductReadResult<StablePage<NetworkActivityRow>>>()
        var networkLoad = 0
        val port = FakeActivityPort().apply {
            networkLoader = {
                when (++networkLoad) {
                    1 -> available(networkPage(listOf(20L), hasMore = false))
                    2 -> stalePredecessor.await()
                    else -> available(networkPage(listOf(19L), hasMore = false))
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        val predecessorRefresh = async { machine.refreshNetwork() }
        runCurrent()
        machine.reloadNetworkAfterClear()
        stalePredecessor.complete(available(networkPage(listOf(18L), hasMore = false)))
        predecessorRefresh.await()

        assertEquals(listOf(19L), ready(machine.state.network).rows.map { it.id })
        assertEquals(3, port.networkRequests.size)
    }

    @Test
    fun `cancelled destructive network reload cannot restore predecessor rows`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        assertTrue(ready(machine.state.network).rows.isNotEmpty())
        port.networkLoader = { awaitCancellation() }

        val reload = async { machine.reloadNetworkAfterClear() }
        runCurrent()
        reload.cancelAndJoin()

        val loading = machine.state.network as ProductLoadState.Loading
        assertNull(loading.lastGood)
        assertFalse(machine.state.networkLoadingMore)
    }

    @Test
    fun `history load more is single flight and appends without duplicate ids`() = runTest {
        val port = FakeActivityPort().apply {
            historyLoader = { request ->
                if (request.token == null) available(domainPage(100L downTo 71L, hasMore = true))
                else historyMore.await()
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        val first = async { machine.loadMoreHistory() }
        runCurrent()
        val duplicate = async { machine.loadMoreHistory() }
        runCurrent()

        assertFalse(duplicate.await())
        assertEquals(2, port.historyRequests.size)
        port.historyMore.complete(available(domainPageWithOlderDuplicate()))
        assertTrue(first.await())

        val rows = ready(machine.state.history).rows
        assertEquals(59, rows.size)
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
    }

    @Test
    fun `observable state distinguishes history pagination from a full reload`() = runTest {
        val pendingPage = CompletableDeferred<ProductReadResult<StablePage<DomainHistoryRow>>>()
        val port = FakeActivityPort().apply {
            historyLoader = { request ->
                if (request.token == null) available(domainPage(100L downTo 71L, hasMore = true))
                else pendingPage.await()
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        val observed = mutableListOf<ProductActivityState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.stateFlow.collect(observed::add)
        }
        machine.synchronizeRevisions()

        val loadMore = async { machine.loadMoreHistory() }
        runCurrent()

        assertTrue(machine.state.historyLoadingMore)
        assertTrue(observed.last().historyLoadingMore)
        assertEquals(30, (machine.state.history as ProductLoadState.Loading).lastGood?.rows?.size)

        pendingPage.complete(available(domainPage(70L downTo 41L, hasMore = false)))
        assertTrue(loadMore.await())
        assertFalse(machine.state.historyLoadingMore)

        val beforeRefresh = observed.size
        machine.refreshHistory()
        assertTrue(observed.drop(beforeRefresh).none(ProductActivityState::historyLoadingMore))
        collector.cancelAndJoin()
    }

    @Test
    fun `network load more also appends without duplicate ids`() = runTest {
        val port = FakeActivityPort().apply {
            networkLoader = { request ->
                if (request.token == null) available(networkPage(100L downTo 71L, hasMore = true))
                else available(networkPageWithOlderDuplicate())
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        assertEquals(
            NetworkPageLoadOutcome.COMPLETED,
            machine.loadMoreNetwork(),
        )

        val rows = ready(machine.state.network).rows
        assertEquals(59, rows.size)
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
    }

    @Test
    fun `observable state distinguishes network pagination from a full reload`() = runTest {
        val pendingPage = CompletableDeferred<ProductReadResult<StablePage<NetworkActivityRow>>>()
        val pendingRefresh = CompletableDeferred<ProductReadResult<StablePage<NetworkActivityRow>>>()
        var firstPageRequests = 0
        val port = FakeActivityPort().apply {
            networkLoader = { request ->
                if (request.token == null && firstPageRequests++ == 0) {
                    available(networkPage(100L downTo 71L, hasMore = true))
                } else if (request.token == null) {
                    pendingRefresh.await()
                }
                else pendingPage.await()
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        val observed = mutableListOf<ProductActivityState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.stateFlow.collect(observed::add)
        }
        machine.synchronizeRevisions()

        val loadMore = async { machine.loadMoreNetwork() }
        runCurrent()

        assertTrue(machine.state.networkLoadingMore)
        assertTrue(observed.last().networkLoadingMore)
        assertEquals(30, (machine.state.network as ProductLoadState.Loading).lastGood?.rows?.size)

        pendingPage.complete(available(networkPage(70L downTo 41L, hasMore = false)))
        assertEquals(
            NetworkPageLoadOutcome.COMPLETED,
            loadMore.await(),
        )
        assertFalse(machine.state.networkLoadingMore)

        val beforeRefresh = observed.size
        val refresh = async { machine.refreshNetwork() }
        runCurrent()
        assertTrue(machine.state.networkRefreshing)
        assertTrue(observed.last().networkRefreshing)
        assertFalse(machine.state.networkLoadingMore)
        assertTrue(observed.drop(beforeRefresh).none(ProductActivityState::networkLoadingMore))

        pendingRefresh.complete(available(networkPage(40L downTo 11L, hasMore = false)))
        refresh.await()
        assertFalse(machine.state.networkRefreshing)
        collector.cancelAndJoin()
    }

    @Test
    fun `initial manual and revision network reloads advance content generation and fence paging`() =
        runTest {
            val initialPage =
                CompletableDeferred<
                    ProductReadResult<StablePage<NetworkActivityRow>>
                >()
            val manualPage =
                CompletableDeferred<
                    ProductReadResult<StablePage<NetworkActivityRow>>
                >()
            val revisionPage =
                CompletableDeferred<
                    ProductReadResult<StablePage<NetworkActivityRow>>
                >()
            var firstPageAttempt = 0
            val port = FakeActivityPort().apply {
                networkLoader = { request ->
                    check(request.token == null)
                    when (++firstPageAttempt) {
                        1 -> initialPage.await()
                        2 -> manualPage.await()
                        3 -> revisionPage.await()
                        else -> error("unexpected first-page request")
                    }
                }
            }
            val machine = ProductActivityStateMachine(port, TODAY)

            val initial = async { machine.synchronizeRevisions() }
            runCurrent()
            assertEquals(1L, machine.state.networkContentGeneration)
            assertFalse(machine.state.networkHydrated)
            assertTrue(machine.state.networkFirstPageLoading)
            assertFalse(machine.state.networkLoadingMore)
            initialPage.complete(
                available(networkPage(100L downTo 71L, hasMore = true)),
            )
            initial.await()
            assertTrue(machine.state.networkHydrated)
            assertFalse(machine.state.networkFirstPageLoading)

            val manual = async { machine.refreshNetwork() }
            runCurrent()
            assertEquals(2L, machine.state.networkContentGeneration)
            assertTrue(machine.state.networkHydrated)
            assertTrue(machine.state.networkFirstPageLoading)
            assertFalse(machine.state.networkLoadingMore)
            manualPage.complete(
                available(networkPage(90L downTo 61L, hasMore = true)),
            )
            manual.await()

            port.networkRevisionValue = 2L
            val revision = async { machine.synchronizeRevisions() }
            runCurrent()
            assertEquals(3L, machine.state.networkContentGeneration)
            assertTrue(machine.state.networkFirstPageLoading)
            assertFalse(machine.state.networkLoadingMore)
            revisionPage.complete(
                available(networkPage(80L downTo 51L, hasMore = false)),
            )
            revision.await()
            assertTrue(machine.state.networkHydrated)
            assertFalse(machine.state.networkFirstPageLoading)
        }

    @Test
    fun `network paging returns typed rejected failed completed and superseded terminals`() =
        runTest {
            val failedPage = CompletableDeferred<Unit>()
            val supersededPage =
                CompletableDeferred<
                    ProductReadResult<StablePage<NetworkActivityRow>>
                >()
            var pageAttempt = 0
            val port = FakeActivityPort().apply {
                networkLoader = { request ->
                    if (request.token == null) {
                        if (networkRevisionValue == 1L) {
                            available(networkPage(100L downTo 71L, hasMore = true))
                        } else {
                            available(networkPage(listOf(8L), hasMore = false))
                        }
                    } else {
                        when (++pageAttempt) {
                            1 -> available(networkPage(70L downTo 41L, hasMore = true))
                            2 -> {
                                failedPage.await()
                                error("private page failure")
                            }
                            3 -> supersededPage.await()
                            else -> error("unexpected page request")
                        }
                    }
                }
            }
            val machine = ProductActivityStateMachine(port, TODAY)
            machine.synchronizeRevisions()

            assertEquals(
                NetworkPageLoadOutcome.COMPLETED,
                machine.loadMoreNetwork(),
            )
            val failed = async { machine.loadMoreNetwork() }
            runCurrent()
            failedPage.complete(Unit)
            assertEquals(NetworkPageLoadOutcome.FAILED, failed.await())

            val superseded = async { machine.loadMoreNetwork() }
            runCurrent()
            port.networkRevisionValue = 2L
            machine.synchronizeRevisions()
            supersededPage.complete(
                available(networkPage(40L downTo 11L, hasMore = false)),
            )
            assertEquals(
                NetworkPageLoadOutcome.SUPERSEDED,
                superseded.await(),
            )
            assertEquals(
                NetworkPageLoadOutcome.REJECTED,
                machine.loadMoreNetwork(),
            )
        }

    @Test
    fun `network paging publishes exactly ten stable pages and exhausts at 300 rows`() = runTest {
        val cut = StableReadCut(maxRowId = 300L, capturedAtMillis = 2_000L)
        val port = FakeActivityPort().apply {
            networkLoader = { request ->
                val start = request.token?.beforeExclusive?.id?.minus(1L) ?: 300L
                val endInclusive = maxOf(1L, start - 29L)
                available(
                    networkPage(
                        ids = (start downTo endInclusive).toList(),
                        hasMore = endInclusive > 1L,
                        cut = cut,
                    ),
                )
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        repeat(9) {
            assertEquals(
                NetworkPageLoadOutcome.COMPLETED,
                machine.loadMoreNetwork(),
            )
        }

        val confirmed = ready(machine.state.network)
        assertEquals(300, confirmed.rows.size)
        assertEquals((300L downTo 1L).toList(), confirmed.rows.map { it.id })
        assertNull(confirmed.next)
        assertEquals(
            NetworkPageLoadOutcome.REJECTED,
            machine.loadMoreNetwork(),
        )
    }

    @Test
    fun `network page that would publish row 301 is rejected atomically`() = runTest {
        val cut = StableReadCut(maxRowId = 330L, capturedAtMillis = 2_000L)
        val port = FakeActivityPort().apply {
            networkLoader = { request ->
                val start = request.token?.beforeExclusive?.id?.minus(1L) ?: 330L
                val ids = if (start == 30L) {
                    listOf(30L)
                } else {
                    (start downTo start - 29L).toList()
                }
                available(
                    networkPage(
                        ids = ids,
                        hasMore = start > 30L,
                        cut = cut,
                    ),
                )
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        repeat(9) {
            assertEquals(
                NetworkPageLoadOutcome.COMPLETED,
                machine.loadMoreNetwork(),
            )
        }
        val confirmed = ready(machine.state.network)
        assertEquals(300, confirmed.rows.size)

        assertEquals(
            NetworkPageLoadOutcome.FAILED,
            machine.loadMoreNetwork(),
        )

        val failure = machine.state.network as ProductLoadState.Failed
        assertTrue(failure.message.contains("300"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `a newer history search discards an older load more response`() = runTest {
        val oldMore = CompletableDeferred<ProductReadResult<StablePage<DomainHistoryRow>>>()
        val port = FakeActivityPort().apply {
            historyLoader = { request ->
                when {
                    request.query.search == "fresh" -> available(domainPage(listOf(7L), hasMore = false))
                    request.token == null -> available(domainPage(100L downTo 71L, hasMore = true))
                    else -> oldMore.await()
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        val stale = async { machine.loadMoreHistory() }
        runCurrent()
        machine.setHistorySearch("fresh")
        oldMore.complete(available(domainPage(70L downTo 41L, hasMore = false)))
        assertTrue(stale.await())

        assertEquals(listOf(7L), ready(machine.state.history).rows.map { it.id })
    }

    @Test
    fun `a newer date lens discards an older summary response`() = runTest {
        val oldSummary = CompletableDeferred<ProductReadResult<ActivitySummary>>()
        val newLens = testLens(startDayMillis = 0L, endDayMillis = 0L)
        val port = FakeActivityPort().apply {
            summaryLoader = { query, _ ->
                if (query.dateLens == TODAY) oldSummary.await()
                else {
                    available(
                        summary(
                            topDomains = listOf(TopDomainRow("new.example", 1L)),
                            dateLens = query.dateLens,
                            topDomainsDecision = query.topDomainDecision,
                            topDomainsSearch = query.topDomainSearch,
                        ),
                    )
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)

        val stale = async { machine.synchronizeRevisions() }
        runCurrent()
        machine.setDateLens(newLens)
        oldSummary.complete(available(summary(topDomains = listOf(TopDomainRow("old.example", 1L)))))
        stale.await()

        assertEquals("new.example", ready(machine.state.summary).topDomains.single().domain)
    }

    @Test
    fun `network load more is single flight and a newer network revision wins`() = runTest {
        val oldMore = CompletableDeferred<ProductReadResult<StablePage<NetworkActivityRow>>>()
        val port = FakeActivityPort().apply {
            networkLoader = { request ->
                if (request.token == null) {
                    if (networkRevisionValue == 1L) available(networkPage(100L downTo 71L, hasMore = true))
                    else available(networkPage(listOf(8L), hasMore = false))
                } else {
                    oldMore.await()
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        val stale = async { machine.loadMoreNetwork() }
        runCurrent()
        val duplicate = async { machine.loadMoreNetwork() }
        runCurrent()
        assertEquals(
            NetworkPageLoadOutcome.REJECTED,
            duplicate.await(),
        )

        port.networkRevisionValue = 2L
        machine.synchronizeRevisions()
        oldMore.complete(available(networkPage(70L downTo 41L, hasMore = false)))
        assertEquals(
            NetworkPageLoadOutcome.SUPERSEDED,
            stale.await(),
        )

        assertEquals(listOf(8L), ready(machine.state.network).rows.map { it.id })
        assertEquals(3, port.networkRequests.size)
    }

    @Test
    fun `cancelling history load more releases its single flight slot`() = runTest {
        val port = FakeActivityPort().apply {
            historyLoader = { request ->
                if (request.token == null) available(domainPage(100L downTo 71L, hasMore = true))
                else awaitCancellation()
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()

        val cancelled = launch { machine.loadMoreHistory() }
        runCurrent()
        assertTrue(machine.state.historyLoadingMore)
        cancelled.cancelAndJoin()
        assertFalse(machine.state.historyLoadingMore)
        port.historyLoader = { request ->
            check(request.token != null)
            available(domainPage(70L downTo 41L, hasMore = false))
        }

        assertTrue(machine.loadMoreHistory())
        assertEquals(60, ready(machine.state.history).rows.size)
    }

    @Test
    fun `refresh failure keeps last good rows and disabled removes retained rows`() = runTest {
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.history)

        port.historyLoader = { error("database unavailable") }
        machine.refreshHistory()

        val failure = machine.state.history as ProductLoadState.Failed
        assertEquals("database unavailable", failure.message)
        assertEquals(confirmed, failure.lastGood)

        port.historyLoader = { ProductReadResult.Disabled }
        machine.refreshHistory()

        assertEquals(ProductLoadState.Disabled, machine.state.history)
        assertNull(machine.state.history.lastGoodOrNull())
    }

    @Test
    fun `loading retains the last confirmed summary until refresh completes`() = runTest {
        val pending = CompletableDeferred<ProductReadResult<ActivitySummary>>()
        val port = FakeActivityPort()
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.summary)
        port.summaryLoader = { _, _ -> pending.await() }

        val refresh = async { machine.refreshSummary() }
        runCurrent()

        val loading = machine.state.summary as ProductLoadState.Loading
        assertEquals(confirmed, loading.lastGood)
        pending.complete(available(summary(topDomains = listOf(TopDomainRow("next.example", 1L)))))
        refresh.await()
        assertEquals("next.example", ready(machine.state.summary).topDomains.single().domain)
    }

    @Test
    fun `load more rejects a page that changes the first page frozen cut`() = runTest {
        val mismatchedCut = StableReadCut(maxRowId = 101L, capturedAtMillis = 2_100L)
        val port = FakeActivityPort().apply {
            historyLoader = { request ->
                if (request.token == null) {
                    available(domainPage(100L downTo 71L, hasMore = true))
                } else {
                    available(
                        domainPage(
                            ids = listOf(70L),
                            hasMore = false,
                            cut = mismatchedCut,
                        ),
                    )
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.history)

        assertTrue(machine.loadMoreHistory())

        val failure = machine.state.history as ProductLoadState.Failed
        assertTrue(failure.message.contains("frozen read cut"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `stable page fixture refuses more than thirty visible rows`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            stablePage(
                rows = (31L downTo 1L).map(::networkRow),
                hasMore = false,
            )
        }

        assertTrue(failure.message.orEmpty().contains("30"))
    }

    @Test
    fun `stable page fixture refuses a sentinel after a partial visible page`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            stablePage(
                rows = (29L downTo 1L).map(::networkRow),
                hasMore = true,
            )
        }

        assertTrue(failure.message.orEmpty().contains("full visible page"))
    }

    @Test
    fun `history load more rejects an equal cursor boundary row and keeps last good rows`() = runTest {
        val port = FakeActivityPort().apply {
            historyLoader = { request ->
                if (request.token == null) {
                    available(domainPage(100L downTo 71L, hasMore = true))
                } else {
                    available(
                        StablePage(
                            rows = listOf(
                                DomainHistoryRow(
                                    id = 71L,
                                    timestampMillis = 710L,
                                    domain = "boundary.example",
                                    decision = DomainDecision.BLOCKED,
                                    reason = FilterDecisionReason.BLOCKLIST,
                                ),
                            ),
                            next = null,
                            cut = request.token.cut,
                        ),
                    )
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.history)

        assertTrue(machine.loadMoreHistory())

        val failure = machine.state.history as ProductLoadState.Failed
        assertTrue(failure.message.contains("exclusive cursor"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `history load more rejects a newer nonduplicate row and keeps last good rows`() = runTest {
        val port = FakeActivityPort().apply {
            historyLoader = { request ->
                if (request.token == null) {
                    available(domainPage(100L downTo 71L, hasMore = true))
                } else {
                    available(
                        StablePage(
                            rows = listOf(
                                DomainHistoryRow(
                                    id = 70L,
                                    timestampMillis = 711L,
                                    domain = "newer.example",
                                    decision = DomainDecision.BLOCKED,
                                    reason = FilterDecisionReason.BLOCKLIST,
                                ),
                            ),
                            next = null,
                            cut = request.token.cut,
                        ),
                    )
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.history)

        assertTrue(machine.loadMoreHistory())

        val failure = machine.state.history as ProductLoadState.Failed
        assertTrue(failure.message.contains("exclusive cursor"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `network load more rejects an equal cursor boundary row and keeps last good rows`() = runTest {
        val port = FakeActivityPort().apply {
            networkLoader = { request ->
                if (request.token == null) {
                    available(networkPage(100L downTo 71L, hasMore = true))
                } else {
                    available(
                        StablePage(
                            rows = listOf(
                                networkRow(
                                    id = 71L,
                                    timestampMillis = 710L,
                                ),
                            ),
                            next = null,
                            cut = request.token.cut,
                        ),
                    )
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.network)

        assertEquals(
            NetworkPageLoadOutcome.FAILED,
            machine.loadMoreNetwork(),
        )

        val failure = machine.state.network as ProductLoadState.Failed
        assertTrue(failure.message.contains("exclusive cursor"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `network load more rejects a newer nonduplicate row and keeps last good rows`() = runTest {
        val port = FakeActivityPort().apply {
            networkLoader = { request ->
                if (request.token == null) {
                    available(networkPage(100L downTo 71L, hasMore = true))
                } else {
                    available(
                        StablePage(
                            rows = listOf(
                                networkRow(
                                    id = 70L,
                                    timestampMillis = 711L,
                                ),
                            ),
                            next = null,
                            cut = request.token.cut,
                        ),
                    )
                }
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)
        machine.synchronizeRevisions()
        val confirmed = ready(machine.state.network)

        assertEquals(
            NetworkPageLoadOutcome.FAILED,
            machine.loadMoreNetwork(),
        )

        val failure = machine.state.network as ProductLoadState.Failed
        assertTrue(failure.message.contains("exclusive cursor"))
        assertEquals(confirmed, failure.lastGood)
    }

    @Test
    fun `an oversized top domains response fails without publishing it`() = runTest {
        val port = FakeActivityPort().apply {
            summaryLoader = { _, _ ->
                available(
                    summary(
                        topDomains = (1..21).map { TopDomainRow("domain-$it.example", it.toLong()) },
                    ),
                )
            }
        }
        val machine = ProductActivityStateMachine(port, TODAY)

        machine.synchronizeRevisions()

        val failure = machine.state.summary as ProductLoadState.Failed
        assertTrue(failure.message.contains("20"))
        assertNull(failure.lastGood)
    }

    private class FakeActivityPort : ProductActivityPort {
        var summaryRevision = 1L
        var domainRevision = 1L
        var networkRevisionValue = 1L
        var summaryRevisionLoader: suspend () -> Long = { summaryRevision }
        var domainRevisionLoader: suspend () -> Long = { domainRevision }
        var networkRevisionLoader: suspend () -> Long = { networkRevisionValue }

        val summaryRequests = mutableListOf<ActivitySummaryQuery>()
        val summaryLimits = mutableListOf<Int>()
        val historyRequests = mutableListOf<DomainHistoryPageRequest>()
        val networkRequests = mutableListOf<NetworkActivityPageRequest>()
        val historyMore = CompletableDeferred<ProductReadResult<StablePage<DomainHistoryRow>>>()

        var summaryLoader: suspend (ActivitySummaryQuery, Int) -> ProductReadResult<ActivitySummary> =
            { query, _ ->
                available(
                    summary(
                        summaryCountRevision = summaryRevision,
                        dateLens = query.dateLens,
                        topDomainsDecision = query.topDomainDecision,
                        topDomainsSearch = query.topDomainSearch,
                        topDomainsDomainRevision = domainRevision,
                        topDomainsPolicyRevision = policyRevision,
                    ),
                )
            }
        var summaryReadQuery: (ActivitySummaryQuery) -> ActivitySummaryQuery = { it }
        var summaryReadRevision:
            (ActivitySummaryQuery, ProductReadResult<ActivitySummary>) -> Long = { _, result ->
                when (result) {
                    is ProductReadResult.Available -> result.value.summaryCountRevision
                    ProductReadResult.Disabled -> summaryRevision
                }
            }
        var historyLoader: suspend (DomainHistoryPageRequest) -> ProductReadResult<StablePage<DomainHistoryRow>> =
            { available(domainPage(listOf(30L), hasMore = false)) }
        var historyReadRevision: (DomainHistoryPageRequest) -> Long = { domainRevision }
        var historyReadQuery: (DomainHistoryPageRequest) -> DomainHistoryQuery = { it.query }
        var historyReadPolicyRevision: (DomainHistoryPageRequest) -> Long = { policyRevision }
        var historyAvailability:
            suspend (
                DomainHistoryPageRequest,
                ProductReadResult<StablePage<DomainHistoryRow>>,
            ) -> DomainHistoryAvailability = { _, result ->
                when (result) {
                    is ProductReadResult.Available -> DomainHistoryAvailability.RETAINED
                    ProductReadResult.Disabled -> DomainHistoryAvailability.DISABLED
                }
            }
        var networkLoader: suspend (NetworkActivityPageRequest) -> ProductReadResult<StablePage<NetworkActivityRow>> =
            { available(networkPage(listOf(20L), hasMore = false)) }
        var networkReadRevision: (NetworkActivityPageRequest) -> Long = { networkRevisionValue }
        var policyRevision = 0L

        override suspend fun summaryCountRevision(): Long = summaryRevisionLoader()

        override suspend fun domainLogRevision(): Long = domainRevisionLoader()

        override suspend fun networkRevision(): Long = networkRevisionLoader()

        override suspend fun loadSummary(
            query: ActivitySummaryQuery,
            topDomainsLimit: Int,
        ): Revisioned<ActivitySummaryRead> {
            summaryRequests += query
            summaryLimits += topDomainsLimit
            val result = summaryLoader(query, topDomainsLimit)
            return Revisioned(
                revision = summaryReadRevision(query, result),
                value = ActivitySummaryRead(
                    query = when (result) {
                        is ProductReadResult.Available -> ActivitySummaryQuery(
                            dateLens = result.value.dateLens,
                            topDomainDecision = result.value.topDomainsDecision,
                            topDomainSearch = result.value.topDomainsSearch,
                        )
                        ProductReadResult.Disabled -> summaryReadQuery(query)
                    },
                    topDomainsDomainRevision = when (result) {
                        is ProductReadResult.Available ->
                            result.value.topDomainsDomainRevision
                        ProductReadResult.Disabled -> domainRevision
                    },
                    topDomainsPolicyRevision = when (result) {
                        is ProductReadResult.Available ->
                            result.value.topDomainsPolicyRevision
                        ProductReadResult.Disabled -> policyRevision
                    },
                    topDomainsAvailability = when (result) {
                        is ProductReadResult.Available ->
                            result.value.topDomainsAvailability
                        ProductReadResult.Disabled -> DomainHistoryAvailability.DISABLED
                    },
                    result = result,
                ),
            )
        }

        override suspend fun loadDomainHistory(
            request: DomainHistoryPageRequest,
        ): Revisioned<DomainHistoryRead> {
            historyRequests += request
            val revision = historyReadRevision(request)
            val query = historyReadQuery(request)
            val readPolicyRevision = historyReadPolicyRevision(request)
            val result = historyLoader(request)
            return Revisioned(
                revision = revision,
                value = DomainHistoryRead(
                    query = query,
                    policyRevision = readPolicyRevision,
                    availability = historyAvailability(request, result),
                    result = result,
                ),
            )
        }

        override suspend fun loadNetworkActivity(
            request: NetworkActivityPageRequest,
        ): Revisioned<ProductReadResult<StablePage<NetworkActivityRow>>> {
            networkRequests += request
            val revision = networkReadRevision(request)
            val result = networkLoader(request)
            return Revisioned(revision = revision, value = result)
        }
    }

    companion object {
        private val TODAY = testLens(startDayMillis = 86_400_000L, endDayMillis = 86_400_000L)
        private val TEST_READ_CUT = StableReadCut(maxRowId = 100L, capturedAtMillis = 2_000L)

        private fun testLens(startDayMillis: Long, endDayMillis: Long) = ActivityDateLens(
            startDayMillis = startDayMillis,
            endDayMillis = endDayMillis,
            zoneId = "UTC",
            today = java.time.Instant.ofEpochMilli(endDayMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate(),
        )

        private fun summary(
            topDomains: List<TopDomainRow> = listOf(TopDomainRow("example.com", 2L)),
            summaryCountRevision: Long = 1L,
            dateLens: ActivityDateLens = TODAY,
            topDomainsDecision: DomainDecision = DomainDecision.BLOCKED,
            topDomainsSearch: String = "",
            topDomainsDomainRevision: Long = 1L,
            topDomainsPolicyRevision: Long = 0L,
            topDomainsAvailability: DomainHistoryAvailability =
                DomainHistoryAvailability.RETAINED,
        ) =
            ActivitySummary(
                digest = ActivityDigest(allowedCount = 4L, blockedCount = 2L, localProtectionUptimeMillis = 1_000L),
                topDomains = topDomains,
                dateLens = dateLens,
                topDomainsDomainRevision = topDomainsDomainRevision,
                topDomainsPolicyRevision = topDomainsPolicyRevision,
                topDomainsAvailability = topDomainsAvailability,
                summaryCountRevision = summaryCountRevision,
                topDomainsDecision = topDomainsDecision,
                topDomainsSearch = topDomainsSearch,
            )

        private fun domainPage(
            ids: LongProgression,
            hasMore: Boolean,
            decision: DomainDecision = DomainDecision.BLOCKED,
            cut: StableReadCut = TEST_READ_CUT,
        ) = domainPage(ids.toList(), hasMore, decision, cut)

        private fun domainPage(
            ids: List<Long>,
            hasMore: Boolean,
            decision: DomainDecision = DomainDecision.BLOCKED,
            cut: StableReadCut = TEST_READ_CUT,
        ): StablePage<DomainHistoryRow> {
            val rows = ids.map { id ->
                DomainHistoryRow(
                    id = id,
                    timestampMillis = id * 10L,
                    domain = "domain-$id.example",
                    decision = decision,
                    reason = when (decision) {
                        DomainDecision.ALLOWED -> FilterDecisionReason.DEFAULT_ALLOW
                        DomainDecision.BLOCKED -> FilterDecisionReason.BLOCKLIST
                    },
                )
            }
            return stablePage(rows, hasMore, cut)
        }

        private fun domainPageWithOlderDuplicate(): StablePage<DomainHistoryRow> {
            val duplicate = DomainHistoryRow(
                id = 80L,
                timestampMillis = 700L,
                domain = "domain-80.example",
                decision = DomainDecision.BLOCKED,
                reason = FilterDecisionReason.BLOCKLIST,
            )
            return stablePage(
                rows = listOf(duplicate) + domainPage(70L downTo 42L, hasMore = false).rows,
                hasMore = false,
            )
        }

        private fun networkPage(
            ids: LongProgression,
            hasMore: Boolean,
            cut: StableReadCut = TEST_READ_CUT,
        ) = networkPage(ids.toList(), hasMore, cut)

        private fun networkPage(
            ids: List<Long>,
            hasMore: Boolean,
            cut: StableReadCut = TEST_READ_CUT,
        ): StablePage<NetworkActivityRow> {
            val rows = ids.map(::networkRow)
            return stablePage(rows, hasMore, cut)
        }

        private fun networkPageWithOlderDuplicate(): StablePage<NetworkActivityRow> {
            val duplicate = networkRow(id = 80L, timestampMillis = 700L)
            return stablePage(
                rows = listOf(duplicate) + networkPage(70L downTo 42L, hasMore = false).rows,
                hasMore = false,
            )
        }

        private fun networkRow(
            id: Long,
            timestampMillis: Long = id * 10L,
        ): NetworkActivityRow = NetworkActivityRow(
            entry = NetworkActivityEntry(
                id = id,
                latestOccurrenceEpochMillis = timestampMillis,
                event = NetworkActivityEvent.ProtectionConnected,
                capturedState = NetworkActivityCapturedState(
                    protection = NetworkActivityProtectionState.PROTECTED,
                    connectivity = NetworkActivityConnectivityState.HEALTHY,
                    networkKind = NetworkActivityNetworkKind.WIFI,
                    networkPathSatisfied = true,
                    resolver = NetworkActivityResolverIdentity.NONE,
                    resolverTransport = null,
                    fallbackCoverage = ResolverFallbackCoverage.NONE,
                    activeFallback = ProtectionFallback.NONE,
                ),
            ),
        )

        private fun <T : StableActivityRow> stablePage(
            rows: List<T>,
            hasMore: Boolean,
            cut: StableReadCut = TEST_READ_CUT,
        ): StablePage<T> {
            require(rows.size <= LOCAL_LOG_PAGE_SIZE) {
                "stable page fixture exposes more than $LOCAL_LOG_PAGE_SIZE rows"
            }
            require(!hasMore || rows.size == LOCAL_LOG_PAGE_SIZE) {
                "stable page fixture requires a full visible page before a next-page sentinel"
            }
            val next = if (hasMore) {
                val last = rows.last()
                PageToken(cut = cut, beforeExclusive = StableKey(last.timestampMillis, last.id))
            } else {
                null
            }
            return StablePage(rows = rows, next = next, cut = cut)
        }

        private fun <T> available(value: T): ProductReadResult<T> = ProductReadResult.Available(value)

        @Suppress("UNCHECKED_CAST")
        private fun <T> ready(state: ProductLoadState<T>): T =
            (state as ProductLoadState.Ready<T>).value

        private fun privateMutex(machine: ProductActivityStateMachine): Mutex =
            ProductActivityStateMachine::class.java
                .getDeclaredField("mutex")
                .apply { isAccessible = true }
                .get(machine) as Mutex
    }
}
