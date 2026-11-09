package com.lavasec.app.product.activity

import com.lavasec.app.product.DomainActionOwner
import com.lavasec.app.product.ProductActivityUiMapper
import com.lavasec.app.presentation.activity.ActivityUiState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Focused Activity owner over the existing exact revisioned state machine and native mapper. */
class ActivityOwner(
    private val port: ProductActivityPort,
    initialDateLens: ActivityDateLens,
    private val domainActions: DomainActionOwner,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val machine = ProductActivityStateMachine(port, initialDateLens)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + dispatcher + ownerJob)
    private val mutableState = MutableStateFlow(
        ProductActivityUiMapper.map(
            machine.state,
            domainActions.state.value.host,
            domainActions.state.value.review,
        ),
    )

    val state: StateFlow<ActivityUiState> = mutableState.asStateFlow()

    init {
        ownerScope.launch {
            combine(machine.stateFlow, domainActions.state) { activity, domain ->
                ProductActivityUiMapper.map(activity, domain.host, domain.review)
            }.collect { exact ->
                if (!closed.get()) mutableState.value = exact
            }
        }
        ownerScope.launch {
            port.revisionSignals.collect(machine::synchronizeRevisions)
        }
    }

    suspend fun setDateLens(lens: ActivityDateLens): Boolean =
        !closed.get() && machine.setDateLens(lens)

    suspend fun setTopDomainDecision(decision: DomainDecision): Boolean =
        !closed.get() && machine.setTopDomainDecision(decision)

    suspend fun setTopDomainSearch(search: String): Boolean =
        !closed.get() && machine.setTopDomainSearch(search)

    suspend fun refreshSummary(): Boolean {
        if (closed.get()) return false
        machine.refreshSummary()
        return true
    }

    suspend fun setHistoryDecision(decision: DomainDecision): Boolean =
        !closed.get() && machine.setHistoryDecision(decision)

    suspend fun setHistorySearch(search: String): Boolean =
        !closed.get() && machine.setHistorySearch(search)

    suspend fun refreshHistory(): Boolean {
        if (closed.get()) return false
        machine.refreshHistory()
        return true
    }

    suspend fun refreshNetwork(): Boolean {
        if (closed.get()) return false
        machine.refreshNetwork()
        return true
    }

    suspend fun reloadNetworkAfterClear(): Boolean {
        if (closed.get()) return false
        machine.reloadNetworkAfterClear()
        return true
    }

    suspend fun loadMoreHistory(): Boolean =
        !closed.get() && machine.loadMoreHistory()

    /**
     * Applies the canonical `activity.query`/`domains.query` request (Top Domains surface) as one
     * coordinated summary lens change. Only the non-null parts move; a null part keeps the owner's
     * current value. Returns false once the owner is closed.
     */
    suspend fun setSummaryQuery(
        dateLens: ActivityDateLens? = null,
        decision: DomainDecision? = null,
        search: String? = null,
    ): Boolean {
        if (closed.get()) return false
        // A null part keeps the owner's current value; an unchanged part is a no-op, not a failure.
        dateLens?.let { machine.setDateLens(it) }
        decision?.let { machine.setTopDomainDecision(it) }
        search?.let { machine.setTopDomainSearch(it) }
        return !closed.get()
    }

    /** Applies the canonical `domains.query` request for the Domain History surface. */
    suspend fun setHistoryQuery(
        decision: DomainDecision? = null,
        search: String? = null,
    ): Boolean {
        if (closed.get()) return false
        decision?.let { machine.setHistoryDecision(it) }
        search?.let { machine.setHistorySearch(it) }
        return !closed.get()
    }

    suspend fun loadMoreNetwork(): NetworkPageLoadOutcome? =
        if (closed.get()) null else machine.loadMoreNetwork()

    override fun close() {
        if (closed.compareAndSet(false, true)) ownerJob.cancel()
    }
}
