package com.lavasec.app.product.activity

import com.lavasec.app.data.ActivityHistoryCursor
import com.lavasec.app.data.ActivityHistoryCut
import com.lavasec.app.data.ActivityHistoryPageToken
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.foundation.activity.ActivityDateRange
import com.lavasec.app.foundation.activity.ActivityHistoryPolicy
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.product.PageToken
import com.lavasec.app.product.Revisioned
import com.lavasec.app.product.StableKey
import com.lavasec.app.product.StablePage
import com.lavasec.app.product.StableReadCut
import com.lavasec.app.product.TOP_DOMAINS_LIMIT
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/** Credential-only Network Activity persistence supplied by the operational runtime graph. */
interface CredentialNetworkActivityStore {
    fun revision(): Long

    fun loadPage(request: NetworkActivityPageRequest): Revisioned<StablePage<NetworkActivityRow>>

    /**
     * Explicit privacy clear of all retained rows. Mirrors the credential-graph logging
     * clear semantics: even an already-empty clear advances the observable revision so a
     * reload after the destructive boundary cannot republish predecessor rows.
     */
    fun clear(): Long
}

/** Android adapter that binds product reads to one credential-protected repository snapshot. */
class AndroidProductActivityPort(
    private val activityRepository: ActivityRepository,
    private val networkActivityStore: CredentialNetworkActivityStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockEpochMillis: () -> Long = System::currentTimeMillis,
    private val signals: Flow<ProductActivityRevisionSignal> =
        flowOf(ProductActivityRevisionSignal(0L)),
) : ProductActivityPort {
    override val revisionSignals: Flow<ProductActivityRevisionSignal> = signals

    override suspend fun summaryCountRevision(): Long = withContext(ioDispatcher) {
        activityRepository.revisions().summaryCountRevision
    }

    override suspend fun domainLogRevision(): Long = withContext(ioDispatcher) {
        activityRepository.revisions().domainLogRevision
    }

    override suspend fun networkRevision(): Long = withContext(ioDispatcher) {
        networkActivityStore.revision()
    }

    override suspend fun loadSummary(
        query: ActivitySummaryQuery,
        topDomainsLimit: Int,
    ): Revisioned<ActivitySummaryRead> = withContext(ioDispatcher) {
        require(topDomainsLimit in 1..TOP_DOMAINS_LIMIT) {
            "topDomainsLimit must be between 1 and $TOP_DOMAINS_LIMIT"
        }
        val capturedAtMillis = clockEpochMillis().also {
            require(it >= 0L) { "Activity clock must be non-negative" }
        }
        val snapshot = activityRepository.productSummarySnapshot(
            range = query.dateLens.toActivityDateRange(),
            action = query.topDomainDecision.toFilterAction(),
            searchText = query.topDomainSearch,
            topDomainsLimit = topDomainsLimit,
            nowMillis = capturedAtMillis,
        )
        val historyAvailability = when {
            !snapshot.policy.settings.keepHistory -> DomainHistoryAvailability.DISABLED
            snapshot.hasRetainedDomainLogs -> DomainHistoryAvailability.RETAINED
            else -> DomainHistoryAvailability.EMPTY
        }
        val result = if (
            !snapshot.policy.settings.keepCounts && !snapshot.policy.settings.keepHistory
        ) {
            ProductReadResult.Disabled
        } else {
            ProductReadResult.Available(
                ActivitySummary(
                    digest = ActivityDigest(
                        allowedCount = snapshot.summary.allowedCount,
                        blockedCount = snapshot.summary.blockedCount,
                        localProtectionUptimeMillis = snapshot.summary.localProtectionUptimeMillis,
                    ),
                    topDomains = snapshot.topDomains.map { row ->
                        TopDomainRow(row.domain, row.count)
                    },
                    dateLens = query.dateLens,
                    topDomainsDomainRevision = snapshot.revisions.domainLogRevision,
                    topDomainsPolicyRevision = snapshot.policy.revision,
                    topDomainsAvailability = historyAvailability,
                    capturedAtMillis = capturedAtMillis,
                    liveUptimeEvidenceThroughMillis =
                        snapshot.summary.liveUptimeEvidenceThroughMillis,
                    summaryCountRevision = snapshot.revisions.summaryCountRevision,
                    topDomainsDecision = query.topDomainDecision,
                    topDomainsSearch = query.topDomainSearch,
                    countsAndUptimeAvailable = snapshot.policy.settings.keepCounts,
                ),
            )
        }
        Revisioned(
            revision = snapshot.revisions.summaryCountRevision,
            value = ActivitySummaryRead(
                query = query,
                topDomainsDomainRevision = snapshot.revisions.domainLogRevision,
                topDomainsPolicyRevision = snapshot.policy.revision,
                topDomainsAvailability = historyAvailability,
                result = result,
            ),
        )
    }

    override suspend fun loadDomainHistory(
        request: DomainHistoryPageRequest,
    ): Revisioned<DomainHistoryRead> = withContext(ioDispatcher) {
        val capturedAtMillis = clockEpochMillis().also {
            require(it >= 0L) { "Activity clock must be non-negative" }
        }
        val snapshot = activityRepository.productDomainHistorySnapshot(
            action = request.query.decision.toFilterAction(),
            searchText = request.query.search,
            token = request.token?.toActivityHistoryToken(),
            nowMillis = capturedAtMillis,
        )
        val availability = when {
            !snapshot.policy.settings.keepHistory -> DomainHistoryAvailability.DISABLED
            snapshot.hasRetainedDomainLogs -> DomainHistoryAvailability.RETAINED
            else -> DomainHistoryAvailability.EMPTY
        }
        val result = snapshot.page?.let { page ->
            ProductReadResult.Available(
                StablePage(
                    rows = page.rows.map(DnsDecisionEvent::toProductRow),
                    next = page.next?.toProductToken(),
                    cut = page.cut.toProductCut(),
                ),
            )
        } ?: ProductReadResult.Disabled
        Revisioned(
            revision = snapshot.revisions.domainLogRevision,
            value = DomainHistoryRead(
                query = request.query,
                policyRevision = snapshot.policy.revision,
                availability = availability,
                result = result,
            ),
        )
    }

    override suspend fun loadNetworkActivity(
        request: NetworkActivityPageRequest,
    ): Revisioned<ProductReadResult<StablePage<NetworkActivityRow>>> =
        withContext(ioDispatcher) {
            val policy = activityRepository.loggingPolicy()
            if (!policy.settings.keepNetworkActivity) {
                Revisioned(networkActivityStore.revision(), ProductReadResult.Disabled)
            } else {
                val page = networkActivityStore.loadPage(request)
                Revisioned(page.revision, ProductReadResult.Available(page.value))
            }
        }
}

private fun ActivityDateLens.toActivityDateRange(): ActivityDateRange {
    val zone = try {
        ZoneId.of(zoneId)
    } catch (failure: DateTimeException) {
        throw IllegalArgumentException("Activity lens zoneId is invalid", failure)
    }
    return ActivityDateRange(
        start = startDayMillis.toExactLocalDay(zone, "startDayMillis"),
        endInclusive = endDayMillis.toExactLocalDay(zone, "endDayMillis"),
    ).also { range ->
        require(!range.endInclusive.isAfter(today)) {
            "Activity lens cannot extend beyond its authoritative current day"
        }
    }
}

private fun Long.toExactLocalDay(zoneId: ZoneId, field: String): LocalDate {
    val day = try {
        Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()
    } catch (failure: DateTimeException) {
        throw IllegalArgumentException("$field is outside the supported local-date range", failure)
    }
    val exactStart = try {
        day.atStartOfDay(zoneId).toInstant().toEpochMilli()
    } catch (failure: DateTimeException) {
        throw IllegalArgumentException("$field is outside the supported local-date range", failure)
    } catch (failure: ArithmeticException) {
        throw IllegalArgumentException("$field overflows epoch milliseconds", failure)
    }
    require(this == exactStart) { "$field must identify an exact local-day start" }
    return day
}

private fun DomainDecision.toFilterAction(): FilterAction = when (this) {
    DomainDecision.ALLOWED -> FilterAction.ALLOW
    DomainDecision.BLOCKED -> FilterAction.BLOCK
}

private fun FilterAction.toDomainDecision(): DomainDecision = when (this) {
    FilterAction.ALLOW -> DomainDecision.ALLOWED
    FilterAction.BLOCK -> DomainDecision.BLOCKED
}

private fun DnsDecisionEvent.toProductRow() = DomainHistoryRow(
    id = id,
    timestampMillis = timestampMillis,
    domain = normalizedDomain,
    decision = decision.action.toDomainDecision(),
    reason = decision.reason,
)

private fun PageToken.toActivityHistoryToken(): ActivityHistoryPageToken {
    val validated = validatedContinuation()
    return ActivityHistoryPageToken(
        cut = ActivityHistoryCut(
            maxRowId = validated.cut.maxRowId,
            capturedAtMillis = validated.cut.capturedAtMillis,
        ),
        beforeExclusive = checkNotNull(validated.beforeExclusive).let { key ->
            ActivityHistoryCursor(key.timestampMillis, key.id)
        },
    )
}

private fun ActivityHistoryPageToken.toProductToken() = PageToken(
    cut = cut.toProductCut(),
    beforeExclusive = beforeExclusive?.let { cursor ->
        StableKey(cursor.timestampMillis, cursor.id)
    },
)

private fun ActivityHistoryCut.toProductCut() = StableReadCut(
    maxRowId = maxRowId,
    capturedAtMillis = capturedAtMillis,
)

private fun PageToken.validatedContinuation(): PageToken {
    val key = requireNotNull(beforeExclusive) {
        "continuation token must include an exclusive page position"
    }
    val cutoffMillis = ActivityHistoryPolicy.cutoffMillis(cut.capturedAtMillis)
    val upperBoundMillis = ActivityHistoryPolicy.upperBoundMillis(cut.capturedAtMillis)
    require(key.timestampMillis in cutoffMillis..upperBoundMillis) {
        "continuation position falls outside its frozen retention cut"
    }
    return this
}
